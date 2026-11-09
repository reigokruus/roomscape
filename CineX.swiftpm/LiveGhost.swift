import Metal
import os
import simd

/// What a saved scan already covers, shown while continuing it: a coarse, light blue outline of its surfaces (one
/// square per 15 cm cell, lying on the cell's main surface direction), so the live mesh's white and orange stand out
/// for what's new. Built once from the saved `ScanMesh`; the full-detail mesh is never drawn or fused.
final class LiveGhost: @unchecked Sendable {
    static let cell: Float = 0.15

    struct Buffers {
        let vertices: MTLBuffer
        let triangles: MTLBuffer
        let triangleIndexCount: Int
        let edges: MTLBuffer
        let edgeIndexCount: Int
    }

    private let state = OSAllocatedUnfairLock<(buffers: Buffers?, transform: simd_float4x4?)>(initialState: (nil, nil))
    /// The saved scan's 15 cm cells with surface (its coordinates), and the live session → saved scan transform.
    private let coverage = OSAllocatedUnfairLock<(cells: Set<SIMD3<Int32>>?, toSaved: simd_float4x4?)>(initialState: (nil, nil))

    private let active = OSAllocatedUnfairLock(initialState: false)

    /// Continuing a scan: from now on the discs stay hidden and nothing is meshed until the saved scan is loaded and
    /// lined up.
    func activate() { active.withLock { $0 = true } }
    var isActive: Bool { active.withLock { $0 } }

    /// While continuing a scan: a test for "the saved scan already has this spot" (live session coordinates), so the
    /// pass doesn't fuse and mesh the blue areas again. Nil when not continuing. Until the pass is lined up with the
    /// saved scan, every spot counts as covered (nothing is meshed yet).
    func coveredTest() -> ((SIMD3<Float>) -> Bool)? {
        let c = coverage.withLock { $0 }
        guard active.withLock({ $0 }) else { return nil }
        guard let cells = c.cells else { return { _ in true } }
        guard let m = c.toSaved else { return { _ in true } }
        return { p in
            let q = m * SIMD4(p, 1)
            return cells.contains(SIMD3<Int32>((SIMD3(q.x, q.y, q.z) / LiveGhost.cell).rounded(.down)))
        }
    }

    /// The squares, and where the saved scan sits in the live session (nil = not known yet: hidden).
    var current: (buffers: Buffers?, transform: simd_float4x4?) { state.withLock { $0 } }

    /// Maps the saved scan's coordinates into the live session's.
    func setTransform(_ m: simd_float4x4) {
        state.withLock { $0.transform = m }
        coverage.withLock { $0.toSaved = m.inverse }
    }

    /// Builds the squares from the saved mesh. Call off the main thread.
    func load(_ mesh: ScanMesh, device: MTLDevice) {
        struct Cell { var sum = SIMD3<Float>.zero; var count: Float = 0; var normal = SIMD3<Float>.zero }
        var cells: [SIMD3<Int32>: Cell] = [:]
        for t in 0..<(mesh.triangles.count / 3) {
            let a = mesh.positions[Int(mesh.triangles[t * 3])]
            let b = mesh.positions[Int(mesh.triangles[t * 3 + 1])]
            let c = mesh.positions[Int(mesh.triangles[t * 3 + 2])]
            let centre = (a + b + c) / 3
            let key = SIMD3<Int32>((centre / Self.cell).rounded(.down))
            var cell = cells[key, default: Cell()]
            cell.sum += centre
            cell.count += 1
            cell.normal += abs(simd_cross(b - a, c - a))  // area-weighted, sign-free: which axis the surface faces
            cells[key] = cell
        }
        // Covered: cells with surface, grown by one cell so the edges of the blue areas aren't meshed again either.
        var covered = Set<SIMD3<Int32>>()
        for (key, cell) in cells where cell.count >= 3 {
            for dx: Int32 in -1...1 { for dy: Int32 in -1...1 { for dz: Int32 in -1...1 { covered.insert(key &+ SIMD3(dx, dy, dz)) } } }
        }
        coverage.withLock { $0.cells = covered }
        var vertices: [LiveMesh.Vertex] = [], triangles: [UInt32] = [], edges: [UInt32] = []
        for (key, cell) in cells where cell.count >= 3 {
            let n = cell.normal
            let axis = n.x >= n.y && n.x >= n.z ? 0 : (n.y >= n.z ? 1 : 2)
            let lo = SIMD3<Float>(key) * Self.cell, hi = lo + Self.cell
            let level = cell.sum[axis] / cell.count
            let (u, v) = axis == 0 ? (1, 2) : (axis == 1 ? (0, 2) : (0, 1))
            let base = UInt32(vertices.count)
            for (cu, cv) in [(false, false), (true, false), (true, true), (false, true)] {
                var p = SIMD3<Float>.zero
                p[axis] = level
                p[u] = cu ? hi[u] : lo[u]
                p[v] = cv ? hi[v] : lo[v]
                vertices.append(LiveMesh.Vertex(x: p.x, y: p.y, z: p.z, confidence: 1))
            }
            triangles += [base, base + 1, base + 2, base, base + 2, base + 3]
            edges += [base, base + 1, base + 1, base + 2, base + 2, base + 3, base + 3, base]
        }
        guard !triangles.isEmpty,
              let vb = vertices.withUnsafeBytes({ device.makeBuffer(bytes: $0.baseAddress!, length: $0.count) }),
              let tb = triangles.withUnsafeBytes({ device.makeBuffer(bytes: $0.baseAddress!, length: $0.count) }),
              let eb = edges.withUnsafeBytes({ device.makeBuffer(bytes: $0.baseAddress!, length: $0.count) }) else { return }
        let buffers = Buffers(vertices: vb, triangles: tb, triangleIndexCount: triangles.count, edges: eb, edgeIndexCount: edges.count)
        state.withLock { $0.buffers = buffers }
    }
}

/// Draws a `LiveGhost` after the live mesh: a depth pass of its squares (so ones behind a wall stay hidden), then a
/// faint blue fill and light blue outlines where they're nearest, not covering the live mesh in front of them.
final class LiveGhostRenderer {
    private let ghost: LiveGhost
    private let depthPipeline: MTLRenderPipelineState
    private let facePipeline: MTLRenderPipelineState
    private let edgePipeline: MTLRenderPipelineState
    private let depthWrite: MTLDepthStencilState
    private let depthEqual: MTLDepthStencilState

    private struct Uniforms { var viewProjection: simd_float4x4; var model: simd_float4x4 }

    init?(device: MTLDevice, ghost: LiveGhost) {
        let options = MTLCompileOptions()
        options.preserveInvariance = true
        guard let library = try? device.makeLibrary(source: Self.shaderSource, options: options),
              let vertex = library.makeFunction(name: "ghostVertex"),
              let face = library.makeFunction(name: "ghostFace"),
              let edge = library.makeFunction(name: "ghostEdge") else { return nil }
        func pipeline(_ fragment: MTLFunction?) -> MTLRenderPipelineState? {
            let d = MTLRenderPipelineDescriptor()
            d.vertexFunction = vertex
            d.fragmentFunction = fragment
            d.depthAttachmentPixelFormat = .depth32Float
            let c = d.colorAttachments[0]!
            c.pixelFormat = .bgra8Unorm
            c.writeMask = fragment == nil ? [] : .all
            c.isBlendingEnabled = fragment != nil
            c.sourceRGBBlendFactor = .sourceAlpha
            c.destinationRGBBlendFactor = .oneMinusSourceAlpha
            c.sourceAlphaBlendFactor = .one
            c.destinationAlphaBlendFactor = .oneMinusSourceAlpha
            return try? device.makeRenderPipelineState(descriptor: d)
        }
        func depthState(_ compare: MTLCompareFunction, write: Bool) -> MTLDepthStencilState? {
            let d = MTLDepthStencilDescriptor()
            d.depthCompareFunction = compare
            d.isDepthWriteEnabled = write
            return device.makeDepthStencilState(descriptor: d)
        }
        guard let depthPipeline = pipeline(nil), let facePipeline = pipeline(face), let edgePipeline = pipeline(edge),
              let depthWrite = depthState(.less, write: true), let depthEqual = depthState(.lessEqual, write: false) else { return nil }
        self.ghost = ghost
        self.depthPipeline = depthPipeline
        self.facePipeline = facePipeline
        self.edgePipeline = edgePipeline
        self.depthWrite = depthWrite
        self.depthEqual = depthEqual
    }

    func encode(_ encoder: MTLRenderCommandEncoder, viewProjection: simd_float4x4) {
        let current = ghost.current
        guard let buffers = current.buffers, let model = current.transform else { return }
        var uniforms = Uniforms(viewProjection: viewProjection, model: model)
        encoder.setCullMode(.none)
        encoder.setVertexBuffer(buffers.vertices, offset: 0, index: 0)
        encoder.setVertexBytes(&uniforms, length: MemoryLayout<Uniforms>.stride, index: 1)
        encoder.setDepthBias(4, slopeScale: 2, clamp: 0.01)
        encoder.setRenderPipelineState(depthPipeline)
        encoder.setDepthStencilState(depthWrite)
        encoder.drawIndexedPrimitives(type: .triangle, indexCount: buffers.triangleIndexCount, indexType: .uint32,
                                      indexBuffer: buffers.triangles, indexBufferOffset: 0)
        encoder.setRenderPipelineState(facePipeline)
        encoder.setDepthStencilState(depthEqual)
        encoder.drawIndexedPrimitives(type: .triangle, indexCount: buffers.triangleIndexCount, indexType: .uint32,
                                      indexBuffer: buffers.triangles, indexBufferOffset: 0)
        encoder.setDepthBias(0, slopeScale: 0, clamp: 0)
        encoder.setRenderPipelineState(edgePipeline)
        encoder.drawIndexedPrimitives(type: .line, indexCount: buffers.edgeIndexCount, indexType: .uint32,
                                      indexBuffer: buffers.edges, indexBufferOffset: 0)
    }

    private static let shaderSource = """
    #include <metal_stdlib>
    using namespace metal;
    struct GhostVertex { packed_float3 position; float confidence; };
    struct Uniforms { float4x4 viewProjection; float4x4 model; };
    struct GhostOut { float4 position [[position, invariant]]; };

    vertex GhostOut ghostVertex(uint vid [[vertex_id]], const device GhostVertex *vertices [[buffer(0)]],
                                constant Uniforms &u [[buffer(1)]]) {
        GhostOut out;
        out.position = u.viewProjection * (u.model * float4(float3(vertices[vid].position), 1.0));
        return out;
    }
    fragment float4 ghostFace(GhostOut in [[stage_in]]) { return float4(0.30, 0.55, 1.0, 0.12); }
    fragment float4 ghostEdge(GhostOut in [[stage_in]]) { return float4(0.45, 0.70, 1.0, 0.60); }
    """
}
