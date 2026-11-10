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
        /// Per triangle: the photo slot that fills it (LivePreview), or -1.
        let photoSlots: MTLBuffer
    }

    /// The live preview's photos (LivePreview), set by LiveCapture.
    var photos: LivePhotoStore?
    private let assigner = LivePhotoAssigner()
    private let filled = OSAllocatedUnfairLock(initialState: (inView: Float(0), room: Float(0)))
    /// Share of confirmed surface already filled with its photo: ahead of the phone, and in the whole room.
    var filledShare: (inView: Float, room: Float) { filled.withLock { $0 } }
    private var cameraPose = matrix_identity_float4x4
    private var edgeSeen = Set<UInt64>()

    /// 4 cm voxels, a ±8 cm band, free space erased up to 1.5 m in front of a surface. 700k voxels (~25 MB) hold about
    /// 170 m² of surface, a large room; past that new areas stop being added.
    let tsdf = Tsdf(capacity: 700_000, voxel: 0.04, truncation: 0.08, carve: 1.5)
    /// Surface counts as solid at this many samples on its least-seen corner (about 5 depth frames at room distance).
    static let solidWeight: Float = 20
    /// Only the surface this close to the phone is meshed, metres. Was 3.5 (rk 9 Nov: stepping back made the far
    /// parts vanish); the whole of a normal room now stays on screen.
    static let radius: Float = 8

    /// Fuse on the GPU (TsdfGPU: a 12 × 4.8 × 12 m block around where the scan started, ~65 MB): every
    /// confident depth pixel, not every 4th, so the mesh fills in faster. The CPU Tsdf stays the fallback and is what
    /// the mesh is extracted from (the GPU block is copied into it before each build).
    static let useGPU = true
    private var gpu: TsdfGPU?
    private var gpuFailed = false

    private let device: MTLDevice
    private let published = OSAllocatedUnfairLock<Buffers?>(initialState: nil)

    // Worker-queue state.
    private let scratch = Tsdf.MeshScratch()
    private var sets: [(vertices: MTLBuffer?, triangles: MTLBuffer?, edges: MTLBuffer?, slots: MTLBuffer?)] = Array(repeating: (nil, nil, nil, nil), count: 3)
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

    /// Worker queue: what the mesh holds, in MB (the hardware log's size marks).
    func sizesMB() -> [String: Double] {
        let mb = 1.0 / 1_048_576
        let meshBytes = sets.reduce(0) { $0 + ($1.vertices?.length ?? 0) + ($1.triangles?.length ?? 0) + ($1.edges?.length ?? 0) + ($1.slots?.length ?? 0) }
        var out: [String: Double] = ["mesh buffers": Double(meshBytes) * mb,
                                     "tsdf": Double(tsdf.capacity * 30) * mb,
                                     "tsdf used voxels (k)": Double(tsdf.count) / 1000]
        if let gpu { out["gpu tsdf"] = Double(gpu.voxelCount * 6) * mb }
        return out
    }

    /// Worker queue.
    func reset() {
        tsdf.clear()
        gpu = nil
        gpuSynced = false
        gpuFailed = false
        assigner.reset()
        filled.withLock { $0 = (0, 0) }
        lastBuild = 0
        lastDuration = 0
        published.withLock { $0 = nil }
    }

    /// Worker queue: true when depth goes to the GPU block (made around `origin`, the phone, on the first frame).
    func gpuReady(origin: SIMD3<Float>) -> Bool {
        guard Self.useGPU, !gpuFailed else { return false }
        if gpu == nil {
            gpu = TsdfGPU(device: device, min: origin + SIMD3(-6, -2.2, -6), max: origin + SIMD3(6, 2.6, 6),
                          voxel: tsdf.vs, truncation: tsdf.trunc, carve: tsdf.carve)
            if gpu == nil { gpuFailed = true; return false }
        }
        return true
    }

    /// Worker queue: one frame's depth (metres, 0 = none) into the GPU block.
    func fuseGPU(depth: [Float], width: Int, height: Int, pose: simd_float4x4, fx: Float, fy: Float, cx: Float, cy: Float) {
        gpu?.integrate(depth: depth, width: width, height: height, pose: pose, fx: fx, fy: fy, cx: cx, cy: cy, step: 2)
    }

    /// Worker queue: tap to clear in the GPU block too.
    func clearGPU(_ test: (SIMD3<Float>) -> Bool) { _ = gpu?.clearWhere(test) }

    /// Worker queue: the GPU block into the CPU Tsdf, for extraction.
    private func syncFromGPU() {
        guard let gpu else { return }
        // The first time the whole block, then only the bricks the GPU changed since (incremental: the CPU Tsdf is
        // only ever written from here while the GPU fuses).
        if gpuSynced {
            gpu.copyDirty(into: tsdf)
        } else {
            tsdf.clear()
            gpu.copy(into: tsdf)
            gpu.clearDirty()
            gpuSynced = true
        }
    }
    private var gpuSynced = false

    /// Worker queue: tap to clear, the photo choices there are worked out again.
    func forgetPhotos(near points: [SIMD3<Float>], radius: Float) { assigner.forget(near: points, radius: radius) }

    /// Worker queue.
    func setPowerSaving(_ on: Bool) {
        interval = on ? 1.0 : 0.5
    }

    /// Worker queue, after a frame was fused: rebuilds the mesh around the camera if it's time.
    /// The hardware log (set by LiveCapture while scanning): each build's stages, in ms.
    var perf: PerfLog?
    /// Worker queue: the last build's stage times in ms (sync from the GPU, mesh extraction, photo choice, upload).
    private(set) var lastTimes = (sync: 0.0, extract: 0.0, photos: 0.0, upload: 0.0, triangles: 0)

    func rebuildIfDue(camera pose: simd_float4x4, time: TimeInterval) {
        guard time - lastBuild >= max(interval, lastDuration * 5), gpu != nil || tsdf.count > 50 else { return }
        cameraPose = pose
        let camera = SIMD3(pose.columns.3.x, pose.columns.3.y, pose.columns.3.z)
        let t0 = CACurrentMediaTime()
        syncFromGPU()
        lastBuild = time
        let started = CACurrentMediaTime()
        defer { lastDuration = CACurrentMediaTime() - started }
        let triangleCount = tsdf.extractMesh(near: camera, radius: Self.radius, solidWeight: Self.solidWeight, into: scratch)
        let t1 = CACurrentMediaTime()
        lastTimes = ((started - t0) * 1000, (t1 - started) * 1000, 0, 0, triangleCount)
        guard triangleCount > 0 else { return }
        publish()
        lastTimes.upload = (CACurrentMediaTime() - t1) * 1000 - lastTimes.photos
        if let perf {
            perf.note("live mesh: sync from GPU", ms: lastTimes.sync)
            perf.note("live mesh: extract", ms: lastTimes.extract)
            perf.note("live mesh: photo choice", ms: lastTimes.photos)
            perf.note("live mesh: upload + wireframe", ms: lastTimes.upload)
        }
    }

    /// Worker queue: the whole room's surface, for saving with the scan (the photo room is painted onto it).
    func wholeMesh() -> ScanMesh? {
        syncFromGPU()
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
              let edges = buffer(set.edges, bytes: max(scratch.edges.count, 1) * 4),
              let slots = buffer(set.slots, bytes: max(scratch.triangles.count / 3, 1) * 4) else { return }
        set = (vertices, triangles, edges, slots)
        sets[nextSet] = set
        nextSet = (nextSet + 1) % sets.count
        let v = vertices.contents().bindMemory(to: Vertex.self, capacity: vertexCount)
        for i in 0..<vertexCount {
            let p = scratch.positions[i]
            v[i] = Vertex(x: p.x, y: p.y, z: p.z, confidence: scratch.confidence[i])
        }
        scratch.triangles.withUnsafeBytes { triangles.contents().copyMemory(from: $0.baseAddress!, byteCount: $0.count) }
        let slotPointer = slots.contents().bindMemory(to: Int32.self, capacity: max(scratch.triangles.count / 3, 1))
        if let photos {
            let ta = CACurrentMediaTime()
            defer { lastTimes.photos = (CACurrentMediaTime() - ta) * 1000 }
            assigner.assign(positions: scratch.positions, confidence: scratch.confidence, triangles: scratch.triangles,
                            store: photos, camera: cameraPose, into: slotPointer)
            filled.withLock { $0 = (assigner.filledInView, assigner.filledRoom) }
        } else {
            for t in 0..<(scratch.triangles.count / 3) { slotPointer[t] = -1 }
        }
        // The wireframe only on triangles not filled with their photo (the photo reads cleanly).
        var edgeList = scratch.edges
        if photos != nil {
            edgeList.removeAll(keepingCapacity: true)
            edgeSeen.removeAll(keepingCapacity: true)
            for t in 0..<(scratch.triangles.count / 3) where slotPointer[t] < 0 {
                for e in 0..<3 {
                    let a = scratch.triangles[t * 3 + e], b = scratch.triangles[t * 3 + (e + 1) % 3]
                    let key = UInt64(min(a, b)) << 32 | UInt64(max(a, b))
                    if edgeSeen.insert(key).inserted { edgeList.append(a); edgeList.append(b) }
                }
            }
        }
        if !edgeList.isEmpty {
            edgeList.withUnsafeBytes { edges.contents().copyMemory(from: $0.baseAddress!, byteCount: $0.count) }
        }
        let result = Buffers(vertices: vertices, triangles: triangles, triangleIndexCount: scratch.triangles.count,
                             edges: edges, edgeIndexCount: edgeList.count, photoSlots: slots)
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
    private let dimPipeline: MTLRenderPipelineState
    private let noDepth: MTLDepthStencilState

    private struct Uniforms {
        var viewProjection: simd_float4x4
    }

    /// LivePreview's colours and thresholds, for the shaders.
    private struct Colours {
        var tentative = LivePreview.tentative, tentativeEdge = LivePreview.tentativeEdge
        var solid = LivePreview.solid, solidEdge = LivePreview.solidEdge
        var confirmed = LivePreview.confirmed
    }

    init?(device: MTLDevice, mesh: LiveMesh) {
        let options = MTLCompileOptions()
        options.preserveInvariance = true
        guard let library = try? device.makeLibrary(source: Self.shaderSource, options: options),
              let vertex = library.makeFunction(name: "meshVertex"),
              let face = library.makeFunction(name: "meshFace"),
              let edge = library.makeFunction(name: "meshEdge"),
              let dimVertex = library.makeFunction(name: "dimVertex"),
              let dimFragment = library.makeFunction(name: "dimFragment") else { return nil }
        func pipeline(_ fragment: MTLFunction?, vertexFunction: MTLFunction? = nil) -> MTLRenderPipelineState? {
            let descriptor = MTLRenderPipelineDescriptor()
            descriptor.vertexFunction = vertexFunction ?? vertex
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
              let depthEqual = depthState(.lessEqual, write: false),
              let dimPipeline = pipeline(dimFragment, vertexFunction: dimVertex),
              let noDepth = depthState(.always, write: false) else { return nil }
        self.dimPipeline = dimPipeline
        self.noDepth = noDepth
        self.mesh = mesh
        self.depthPipeline = depthPipeline
        self.facePipeline = facePipeline
        self.edgePipeline = edgePipeline
        self.depthWrite = depthWrite
        self.depthEqual = depthEqual
    }

    /// Darkens the camera picture under the preview (LivePreview.dim): what isn't scanned yet stays dark.
    func encodeDim(_ encoder: MTLRenderCommandEncoder, alpha: Float) {
        guard alpha > 0.01 else { return }
        var a = alpha
        encoder.setDepthStencilState(noDepth)
        encoder.setRenderPipelineState(dimPipeline)
        encoder.setFragmentBytes(&a, length: 4, index: 0)
        encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
    }

    /// The phone's camera picture right now, projected onto the mesh from where the phone is (the wide overview: each
    /// camera pixel lands on its own surface from any viewpoint, instead of a flat picture that doesn't line up).
    struct LiveCamera {
        var y: MTLTexture, cbcr: MTLTexture
        /// Depth at the same view (metres), so surface hidden from the phone isn't painted with the camera picture.
        var depth: MTLTexture?
        /// World → (u·d, v·d, d), u and v 0…1 across the camera image (LivePhotoStore.projection).
        var projection: simd_float4x4
    }

    private struct LiveUniform { var projection: simd_float4x4; var on: Float; var hasDepth: Float }

    /// Draws the mesh if there is one yet; false if there's nothing to draw.
    func encode(_ encoder: MTLRenderCommandEncoder, viewProjection: simd_float4x4, live: LiveCamera? = nil) -> Bool {
        guard let buffers = mesh.buffers else { return false }
        var uniforms = Uniforms(viewProjection: viewProjection)
        encoder.setCullMode(.none)
        encoder.setVertexBuffer(buffers.vertices, offset: 0, index: 0)
        encoder.setVertexBytes(&uniforms, length: MemoryLayout<Uniforms>.stride, index: 1)
        encoder.setFragmentBytes(&uniforms, length: MemoryLayout<Uniforms>.stride, index: 0)
        encoder.setFragmentBuffer(buffers.photoSlots, offset: 0, index: 1)
        var colours = Colours()
        encoder.setFragmentBytes(&colours, length: MemoryLayout<Colours>.stride, index: 3)
        var liveUniform = LiveUniform(projection: live?.projection ?? matrix_identity_float4x4, on: live == nil ? 0 : 1,
                                      hasDepth: live?.depth == nil ? 0 : 1)
        encoder.setFragmentBytes(&liveUniform, length: MemoryLayout<LiveUniform>.stride, index: 4)
        if let live {
            encoder.setFragmentTexture(live.y, index: 1)
            encoder.setFragmentTexture(live.cbcr, index: 2)
            encoder.setFragmentTexture(live.depth ?? live.y, index: 3)
        }
        if let photos = mesh.photos {
            encoder.setFragmentBuffer(photos.projections, offset: 0, index: 2)
            encoder.setFragmentTexture(photos.texture, index: 0)
        } else {
            encoder.setFragmentBuffer(buffers.photoSlots, offset: 0, index: 2) // never read: every slot is -1
        }
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
    struct Colours { float4 tentative; float4 tentativeEdge; float4 solid; float4 solidEdge; float confirmed; };
    struct LiveUniform { float4x4 projection; float on; float hasDepth; };
    struct MeshOut {
        float4 position [[position, invariant]];
        float3 world;
        float confidence;
    };

    vertex MeshOut meshVertex(uint vid [[vertex_id]],
                              const device MeshVertex *vertices [[buffer(0)]],
                              constant Uniforms &u [[buffer(1)]]) {
        MeshVertex v = vertices[vid];
        MeshOut out;
        out.position = u.viewProjection * float4(float3(v.position), 1.0);
        out.world = float3(v.position);
        out.confidence = v.confidence;
        return out;
    }

    // Orange while the surface has only been seen a few times, white once it's solid (as on Android), and the photo
    // the Photo view will use once it's confirmed and has one (LivePreview), at full brightness.
    fragment float4 meshFace(MeshOut in [[stage_in]], uint prim [[primitive_id]],
                             const device int *slots [[buffer(1)]],
                             const device float4x4 *projections [[buffer(2)]],
                             constant Colours &c [[buffer(3)]],
                             constant LiveUniform &live [[buffer(4)]],
                             texture2d_array<float> photos [[texture(0)]],
                             texture2d<float> camY [[texture(1)]],
                             texture2d<float> camCbCr [[texture(2)]],
                             texture2d<float> camDepth [[texture(3)]]) {
        constexpr sampler s(filter::linear, address::clamp_to_edge);
        // The wide overview: the camera picture right now, where the phone sees this surface (not hidden behind
        // something nearer in its depth).
        if (live.on > 0.5) {
            float4 q = live.projection * float4(in.world, 1.0);
            if (q.z > 0.05) {
                float2 uv = q.xy / q.z;
                if (uv.x > 0.0 && uv.x < 1.0 && uv.y > 0.0 && uv.y < 1.0) {
                    bool visible = true;
                    if (live.hasDepth > 0.5) {
                        float d = camDepth.sample(s, uv).r;
                        visible = d <= 0.0 || q.z <= d + 0.1 + 0.04 * q.z;
                    }
                    if (visible) {
                        float Y = camY.sample(s, uv).r;
                        float2 C = camCbCr.sample(s, uv).rg - float2(0.5, 0.5);
                        float3 rgb = float3(Y + 1.402 * C.y, Y - 0.344136 * C.x - 0.714136 * C.y, Y + 1.772 * C.x);
                        return float4(saturate(rgb), 1.0);
                    }
                }
            }
        }
        int slot = slots[prim];
        if (slot >= 0) {
            float4 q = projections[slot] * float4(in.world, 1.0);
            if (q.z > 0.05) {
                return float4(photos.sample(s, q.xy / q.z, uint(slot)).rgb, 1.0);
            }
        }
        float solid = smoothstep(0.15, c.confirmed, in.confidence);
        return mix(c.tentative, c.solid, saturate(solid));
    }

    vertex MeshOut dimVertex(uint vid [[vertex_id]]) {
        MeshOut out;
        out.position = float4(vid & 1 ? 1.0 : -1.0, vid & 2 ? 1.0 : -1.0, 0.0, 1.0);
        out.world = float3(0.0);
        out.confidence = 0.0;
        return out;
    }

    fragment float4 dimFragment(MeshOut in [[stage_in]], constant float &alpha [[buffer(0)]]) {
        return float4(0.0, 0.0, 0.0, alpha);
    }

    fragment float4 meshEdge(MeshOut in [[stage_in]], constant Colours &c [[buffer(3)]]) {
        float solid = smoothstep(0.15, c.confirmed, in.confidence);
        return mix(c.tentativeEdge, c.solidEdge, saturate(solid));
    }
    """
}
