import ARKit
import Metal
import os

/// The live scan mesh, built by the app itself from LiDAR depth (RoomPlan keeps ARKit's own mesh to itself: its
/// session has no `ARMeshAnchor`s). Depth samples are fused into a `Tsdf` on `LiveCapture`'s worker queue, inside
/// `LiveCloud.integrate`; every `interval` the surface within a few metres of the phone is extracted into GPU buffers
/// for the overlay: white see-through triangles with white edges, orange where the surface has been seen only a few
/// times, turning white once it's solid (seen in about 5 depth frames).
///
/// Memory stays flat: the TSDF is allocated once, and the GPU buffers rotate through three reused sets that only
/// grow when the mesh outgrows them.
final class LiveMesh: @unchecked Sendable {
    /// One vertex on the GPU: world position and how solid the surface is there, 0…1.
    struct Vertex {
        var x, y, z: Float
        var confidence: Float
    }

    /// What the renderer draws: one vertex buffer, triangle indices and edge indices into it.
    struct Buffers {
        let vertices: MTLBuffer
        let triangles: MTLBuffer
        let triangleIndexCount: Int
        let edges: MTLBuffer
        let edgeIndexCount: Int
    }

    /// 4 cm voxels, a ±8 cm band, free space erased up to 1.5 m in front of a surface. 700k voxels (~25 MB) hold about
    /// 170 m² of surface, a large room; past that new areas stop being added.
    let tsdf = Tsdf(capacity: 700_000, voxel: 0.04, truncation: 0.08, carve: 1.5)
    /// Surface counts as solid at this many samples on its least-seen corner (about 5 depth frames at room distance).
    static let solidWeight: Float = 20
    /// Only the surface this close to the phone is meshed, metres.
    static let radius: Float = 3.5

    private let device: MTLDevice
    private let published = OSAllocatedUnfairLock<Buffers?>(initialState: nil)

    // Worker-queue state.
    private let scratch = Tsdf.MeshScratch()
    private var sets: [(vertices: MTLBuffer?, triangles: MTLBuffer?, edges: MTLBuffer?)] = Array(repeating: (nil, nil, nil), count: 3)
    private var nextSet = 0
    private var lastBuild: TimeInterval = 0
    /// Seconds between mesh builds: twice a second, once a second when the phone is hot.
    private var interval: TimeInterval = 0.5
    /// How long the last build took. Builds share the worker queue with depth capture, so they're spaced at least five
    /// times their own duration apart (a large room, ~150 ms per build, then rebuilds about every 0.75 s).
    private var lastDuration: TimeInterval = 0

    init(device: MTLDevice) {
        self.device = device
    }

    var buffers: Buffers? { published.withLock { $0 } }

    /// Worker queue.
    func reset() {
        tsdf.clear()
        lastBuild = 0
        lastDuration = 0
        published.withLock { $0 = nil }
    }

    /// Worker queue.
    func setPowerSaving(_ on: Bool) {
        interval = on ? 1.0 : 0.5
    }

    /// Worker queue, after a frame was fused: rebuilds the mesh around the camera if it's time.
    func rebuildIfDue(camera: SIMD3<Float>, time: TimeInterval) {
        guard time - lastBuild >= max(interval, lastDuration * 5), tsdf.count > 50 else { return }
        lastBuild = time
        let started = CACurrentMediaTime()
        defer { lastDuration = CACurrentMediaTime() - started }
        let triangleCount = tsdf.extractMesh(near: camera, radius: Self.radius, solidWeight: Self.solidWeight, into: scratch)
        guard triangleCount > 0 else { return }
        publish()
    }

    /// Worker queue: the whole room's surface, for saving with the scan (the photo room is painted onto it).
    func wholeMesh() -> ScanMesh? {
        let all = Tsdf.MeshScratch()
        guard tsdf.extractMesh(near: .zero, radius: 10_000, solidWeight: Self.solidWeight, into: all) > 0 else { return nil }
        return ScanMesh(positions: all.positions, triangles: all.triangles)
    }

    private func buffer(_ existing: MTLBuffer?, bytes: Int) -> MTLBuffer? {
        if let existing, existing.length >= bytes { return existing }
        return device.makeBuffer(length: max(bytes + bytes / 2, 1024), options: .storageModeShared)
    }

    private func publish() {
        let vertexCount = scratch.positions.count
        // The set the renderer used two builds ago (a second or more back), long done on the GPU.
        var set = sets[nextSet]
        guard let vertices = buffer(set.vertices, bytes: vertexCount * MemoryLayout<Vertex>.stride),
              let triangles = buffer(set.triangles, bytes: scratch.triangles.count * 4),
              let edges = buffer(set.edges, bytes: max(scratch.edges.count, 1) * 4) else { return }
        set = (vertices, triangles, edges)
        sets[nextSet] = set
        nextSet = (nextSet + 1) % sets.count
        let v = vertices.contents().bindMemory(to: Vertex.self, capacity: vertexCount)
        for i in 0..<vertexCount {
            let p = scratch.positions[i]
            v[i] = Vertex(x: p.x, y: p.y, z: p.z, confidence: scratch.confidence[i])
        }
        scratch.triangles.withUnsafeBytes { triangles.contents().copyMemory(from: $0.baseAddress!, byteCount: $0.count) }
        if !scratch.edges.isEmpty {
            scratch.edges.withUnsafeBytes { edges.contents().copyMemory(from: $0.baseAddress!, byteCount: $0.count) }
        }
        let result = Buffers(vertices: vertices, triangles: triangles, triangleIndexCount: scratch.triangles.count,
                             edges: edges, edgeIndexCount: scratch.edges.count)
        published.withLock { $0 = result }
    }
}

/// Draws a `LiveMesh`: a depth-only pass of the triangles (pushed back slightly with depth bias), the triangles again
/// in colour where they're nearest, so the surface blends once per pixel, then the edges on top of them.
final class LiveMeshRenderer {
    private let mesh: LiveMesh
    private let depthPipeline: MTLRenderPipelineState
    private let facePipeline: MTLRenderPipelineState
    private let edgePipeline: MTLRenderPipelineState
    private let depthWrite: MTLDepthStencilState
    private let depthEqual: MTLDepthStencilState

    private struct Uniforms {
        var viewProjection: simd_float4x4
    }

    init?(device: MTLDevice, mesh: LiveMesh) {
        let options = MTLCompileOptions()
        options.preserveInvariance = true
        guard let library = try? device.makeLibrary(source: Self.shaderSource, options: options),
              let vertex = library.makeFunction(name: "meshVertex"),
              let face = library.makeFunction(name: "meshFace"),
              let edge = library.makeFunction(name: "meshEdge") else { return nil }
        func pipeline(_ fragment: MTLFunction?) -> MTLRenderPipelineState? {
            let descriptor = MTLRenderPipelineDescriptor()
            descriptor.vertexFunction = vertex
            descriptor.fragmentFunction = fragment
            descriptor.depthAttachmentPixelFormat = .depth32Float
            let color = descriptor.colorAttachments[0]!
            color.pixelFormat = .bgra8Unorm
            color.writeMask = fragment == nil ? [] : .all
            color.isBlendingEnabled = fragment != nil
            color.sourceRGBBlendFactor = .sourceAlpha
            color.destinationRGBBlendFactor = .oneMinusSourceAlpha
            color.sourceAlphaBlendFactor = .one
            color.destinationAlphaBlendFactor = .oneMinusSourceAlpha
            return try? device.makeRenderPipelineState(descriptor: descriptor)
        }
        func depthState(_ compare: MTLCompareFunction, write: Bool) -> MTLDepthStencilState? {
            let depth = MTLDepthStencilDescriptor()
            depth.depthCompareFunction = compare
            depth.isDepthWriteEnabled = write
            return device.makeDepthStencilState(descriptor: depth)
        }
        guard let depthPipeline = pipeline(nil), let facePipeline = pipeline(face), let edgePipeline = pipeline(edge),
              let depthWrite = depthState(.less, write: true),
              let depthEqual = depthState(.lessEqual, write: false) else { return nil }
        self.mesh = mesh
        self.depthPipeline = depthPipeline
        self.facePipeline = facePipeline
        self.edgePipeline = edgePipeline
        self.depthWrite = depthWrite
        self.depthEqual = depthEqual
    }

    /// Draws the mesh if there is one yet; false if there's nothing to draw.
    func encode(_ encoder: MTLRenderCommandEncoder, viewProjection: simd_float4x4) -> Bool {
        guard let buffers = mesh.buffers else { return false }
        var uniforms = Uniforms(viewProjection: viewProjection)
        encoder.setCullMode(.none)
        encoder.setVertexBuffer(buffers.vertices, offset: 0, index: 0)
        encoder.setVertexBytes(&uniforms, length: MemoryLayout<Uniforms>.stride, index: 1)
        encoder.setFragmentBytes(&uniforms, length: MemoryLayout<Uniforms>.stride, index: 0)
        // Faces sit a little behind their edges, so the edges pass the depth test along them.
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
        if buffers.edgeIndexCount > 0 {
            encoder.setRenderPipelineState(edgePipeline)
            encoder.drawIndexedPrimitives(type: .line, indexCount: buffers.edgeIndexCount, indexType: .uint32,
                                          indexBuffer: buffers.edges, indexBufferOffset: 0)
        }
        return true
    }

    private static let shaderSource = """
    #include <metal_stdlib>
    using namespace metal;

    struct MeshVertex { packed_float3 position; float confidence; };
    struct Uniforms { float4x4 viewProjection; };
    struct MeshOut {
        float4 position [[position, invariant]];
        float solid;
    };

    vertex MeshOut meshVertex(uint vid [[vertex_id]],
                              const device MeshVertex *vertices [[buffer(0)]],
                              constant Uniforms &u [[buffer(1)]]) {
        MeshVertex v = vertices[vid];
        MeshOut out;
        out.position = u.viewProjection * float4(float3(v.position), 1.0);
        // Orange while the surface has only been seen a few times, white once it's solid (as on Android).
        out.solid = smoothstep(0.15, 0.6, v.confidence);
        return out;
    }

    fragment float4 meshFace(MeshOut in [[stage_in]]) {
        return mix(float4(1.0, 0.55, 0.15, 0.40), float4(0.92, 0.94, 1.0, 0.42), saturate(in.solid));
    }

    fragment float4 meshEdge(MeshOut in [[stage_in]]) {
        return mix(float4(1.0, 0.45, 0.1, 0.85), float4(1.0, 1.0, 1.0, 0.75), saturate(in.solid));
    }
    """
}
