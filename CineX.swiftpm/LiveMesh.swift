import ARKit
import Metal
import QuartzCore
import os

/// The LiDAR mesh ARKit reconstructs inside RoomPlan's session (its `ARMeshAnchor`s), kept as GPU buffers for the live
/// overlay: white see-through triangles with white edges, areas seen for the first time in the last moments tinted
/// orange.
///
/// Anchors are only *read* from `arSession.currentFrame` (see `LiveCapture`). Buffers are rebuilt off the main thread
/// and only when an anchor's geometry changed, at most every `interval`, so memory stays flat while scanning: each
/// rebuild replaces the previous buffers, which are freed once the GPU is done with them.
final class LiveMesh: @unchecked Sendable {
    /// One vertex on the GPU: world position and when that spot was first seen (seconds since `start`).
    struct Vertex {
        var x, y, z: Float
        var seen: Float
    }

    /// What the renderer draws: one vertex buffer, triangle indices and edge indices into it.
    struct Buffers {
        let vertices: MTLBuffer
        let triangles: MTLBuffer
        let triangleIndexCount: Int
        let edges: MTLBuffer
        let edgeIndexCount: Int
    }

    /// Seconds between rebuilds (about 5 Hz; slower when the phone is hot). Main thread only.
    private var interval: TimeInterval = 0.2
    /// Size of the cells that remember when a spot was first seen, metres.
    private static let freshCell: Float = 0.05

    private let device: MTLDevice
    private let queue = DispatchQueue(label: "CineX.live-mesh", qos: .utility)
    private let published = OSAllocatedUnfairLock<Buffers?>(initialState: nil)
    private let startLock = OSAllocatedUnfairLock(initialState: CACurrentMediaTime())

    // Worker-queue state.
    private struct Part {
        var vertices: [Vertex]
        var triangles: [UInt32]
        var edges: [UInt32]
    }
    private var parts: [UUID: Part] = [:]
    /// Each anchor's geometry as last built, by identity: ARKit gives an anchor a new geometry object when it changes.
    private var built: [UUID: ObjectIdentifier] = [:]
    private var firstSeen: [Int64: Float] = [:]
    private var busy = false
    private var lastSubmit: TimeInterval = 0
    #if DEBUG
    private var loggedFirst = false
    #endif

    init(device: MTLDevice) {
        self.device = device
    }

    /// Seconds since the scan started, the clock `Vertex.seen` uses.
    var now: Float { Float(CACurrentMediaTime() - startLock.withLock { $0 }) }

    var buffers: Buffers? { published.withLock { $0 } }

    func reset() {
        queue.async { [self] in
            parts.removeAll()
            built.removeAll()
            firstSeen.removeAll()
            startLock.withLock { $0 = CACurrentMediaTime() }
            published.withLock { $0 = nil }
        }
    }

    @MainActor
    func setPowerSaving(_ on: Bool) {
        interval = on ? 0.4 : 0.2
    }

    /// Called from the main thread with the frame's mesh anchors. Drops the call if a rebuild is running or the last
    /// one was too recent; anchors are passed on, never the frame, so ARKit gets its camera buffers back right away.
    @MainActor
    func offer(_ anchors: [ARMeshAnchor]) {
        let t = CACurrentMediaTime()
        guard !busy, t - lastSubmit >= interval else { return }
        lastSubmit = t
        busy = true
        queue.async { [self] in
            update(anchors)
            DispatchQueue.main.async { self.busy = false }
        }
    }

    private func update(_ anchors: [ARMeshAnchor]) {
        #if DEBUG
        if !loggedFirst, !anchors.isEmpty {
            loggedFirst = true
            print("LiveMesh: RoomPlan's session has \(anchors.count) mesh anchors")
        }
        #endif
        let time = now
        var changed = false
        var present = Set<UUID>()
        for anchor in anchors {
            present.insert(anchor.identifier)
            let id = ObjectIdentifier(anchor.geometry)
            if built[anchor.identifier] == id { continue }
            built[anchor.identifier] = id
            parts[anchor.identifier] = part(for: anchor, time: time)
            changed = true
        }
        for id in parts.keys where !present.contains(id) {
            parts[id] = nil
            built[id] = nil
            changed = true
        }
        if changed { publish() }
    }

    private func part(for anchor: ARMeshAnchor, time: Float) -> Part {
        let geometry = anchor.geometry
        let source = geometry.vertices
        let faces = geometry.faces
        let transform = anchor.transform
        var vertices: [Vertex] = []
        vertices.reserveCapacity(source.count)
        let base = source.buffer.contents().advanced(by: source.offset)
        for i in 0..<source.count {
            let p = base.advanced(by: i * source.stride).assumingMemoryBound(to: Float.self)
            let w = transform * SIMD4<Float>(p[0], p[1], p[2], 1)
            vertices.append(Vertex(x: w.x, y: w.y, z: w.z, seen: seen(w, time: time)))
        }
        var triangles: [UInt32] = []
        var edges: [UInt32] = []
        let perFace = faces.indexCountPerPrimitive
        guard perFace == 3 else { return Part(vertices: vertices, triangles: [], edges: []) }
        triangles.reserveCapacity(faces.count * 3)
        edges.reserveCapacity(faces.count * 3)
        let indexBase = faces.buffer.contents()
        let wide = faces.bytesPerIndex == 4
        func index(_ k: Int) -> UInt32 {
            wide ? indexBase.load(fromByteOffset: k * 4, as: UInt32.self)
                 : UInt32(indexBase.load(fromByteOffset: k * 2, as: UInt16.self))
        }
        for f in 0..<faces.count {
            let a = index(f * 3), b = index(f * 3 + 1), c = index(f * 3 + 2)
            triangles.append(contentsOf: [a, b, c])
            // A shared edge appears once in each direction: keep one of them. Edges on the mesh's border are only
            // kept when they run "upwards", which is enough to outline it.
            for (u, v) in [(a, b), (b, c), (c, a)] where u < v {
                edges.append(u)
                edges.append(v)
            }
        }
        return Part(vertices: vertices, triangles: triangles, edges: edges)
    }

    /// When the cell holding `p` was first seen, remembering it now if it's new.
    private func seen(_ p: SIMD4<Float>, time: Float) -> Float {
        let q = (SIMD3<Float>(p.x, p.y, p.z) / Self.freshCell).rounded(.down)
        let key = (Int64(Int32(clamping: Int(q.x))) & 0x1FFFFF)
            | ((Int64(Int32(clamping: Int(q.y))) & 0x1FFFFF) << 21)
            | ((Int64(Int32(clamping: Int(q.z))) & 0x1FFFFF) << 42)
        if let t = firstSeen[key] { return t }
        firstSeen[key] = time
        return time
    }

    private func publish() {
        var vertexCount = 0, triangleCount = 0, edgeCount = 0
        for part in parts.values {
            vertexCount += part.vertices.count
            triangleCount += part.triangles.count
            edgeCount += part.edges.count
        }
        guard vertexCount > 0, triangleCount > 0,
              let vertices = device.makeBuffer(length: vertexCount * MemoryLayout<Vertex>.stride, options: .storageModeShared),
              let triangles = device.makeBuffer(length: triangleCount * 4, options: .storageModeShared),
              let edges = device.makeBuffer(length: max(edgeCount, 1) * 4, options: .storageModeShared) else {
            published.withLock { $0 = nil }
            return
        }
        let v = vertices.contents().bindMemory(to: Vertex.self, capacity: vertexCount)
        let t = triangles.contents().bindMemory(to: UInt32.self, capacity: triangleCount)
        let e = edges.contents().bindMemory(to: UInt32.self, capacity: max(edgeCount, 1))
        var vo = 0, to = 0, eo = 0
        for part in parts.values {
            let shift = UInt32(vo)
            part.vertices.withUnsafeBufferPointer { (v + vo).update(from: $0.baseAddress!, count: $0.count) }
            for (i, index) in part.triangles.enumerated() { t[to + i] = index + shift }
            for (i, index) in part.edges.enumerated() { e[eo + i] = index + shift }
            vo += part.vertices.count
            to += part.triangles.count
            eo += part.edges.count
        }
        let result = Buffers(vertices: vertices, triangles: triangles, triangleIndexCount: triangleCount,
                             edges: edges, edgeIndexCount: edgeCount)
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
        var now: Float
        var fresh: Float
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
        var uniforms = Uniforms(viewProjection: viewProjection, now: mesh.now, fresh: 1.5)
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

    struct MeshVertex { packed_float3 position; float seen; };
    struct Uniforms { float4x4 viewProjection; float now; float fresh; };
    struct MeshOut {
        float4 position [[position, invariant]];
        float fresh;
    };

    vertex MeshOut meshVertex(uint vid [[vertex_id]],
                              const device MeshVertex *vertices [[buffer(0)]],
                              constant Uniforms &u [[buffer(1)]]) {
        MeshVertex v = vertices[vid];
        MeshOut out;
        out.position = u.viewProjection * float4(float3(v.position), 1.0);
        // 1 when just seen, fading to 0 over the last part of the fresh window.
        out.fresh = 1.0 - smoothstep(u.fresh * 0.5, u.fresh, u.now - v.seen);
        return out;
    }

    constant float3 orange = float3(1.0, 0.55, 0.15);

    fragment float4 meshFace(MeshOut in [[stage_in]]) {
        float f = saturate(in.fresh);
        return float4(mix(float3(0.92, 0.94, 1.0), orange, f), mix(0.42, 0.55, f));
    }

    fragment float4 meshEdge(MeshOut in [[stage_in]]) {
        float f = saturate(in.fresh);
        return float4(mix(float3(1.0), orange, f), mix(0.75, 0.9, f));
    }
    """
}
