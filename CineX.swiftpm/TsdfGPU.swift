import Metal
import simd

/// TSDF fusion on the GPU (rk 9 Nov: use the GPU, it sat idle): a dense voxel block around the scan, each voxel
/// updated from every depth frame at once by a Metal compute kernel, with the same rules as the CPU `Tsdf`:
/// a ±`trunc` band around the surface, free space in front of it erased up to `carve` with a margin that grows with
/// distance, and solid surface (weight ≥ `Tsdf.solidW`) only nudged by free space, at most once per frame.
/// The result goes into a CPU `Tsdf` (`copy(into:)`) for the usual mesh extraction, so everything after fusion is
/// unchanged. Shared by the app (rebuild from recording) and the Mac harness (where CPU and GPU are compared).
///
/// One update per voxel per frame (the CPU adds one per depth sample through it), so weights are scaled by how many
/// samples a voxel would have got: (voxel size / pixel footprint)², capped.
final class TsdfGPU {
    let vs: Float, trunc: Float, carve: Float
    /// Grid origin in voxel coordinates (so voxel indices match the CPU Tsdf's), and size.
    let origin: SIMD3<Int32>, dims: SIMD3<Int32>
    private let device: MTLDevice
    private let queue: MTLCommandQueue
    private let pipeline: MTLComputePipelineState
    private let sdf: MTLBuffer, weight: MTLBuffer
    /// One byte per 8×8×8 brick, set by the kernel when it changes a voxel there: the live scan copies only those
    /// (copying the whole block every mesh build took ~0.8 s in the virtual scan; rk 10 Nov).
    private let dirty: MTLBuffer
    let bricks: SIMD3<Int32>
    private var commands: MTLCommandBuffer?
    private var encoder: MTLComputeCommandEncoder?
    private var pending = 0
    private var depthBuffers: [MTLBuffer] = []
    /// A command buffer failed (e.g. GPU work isn't allowed: the app is in the background). The caller falls back.
    private(set) var failed = false

    struct Params {
        var worldToCam: simd_float4x4
        var fx: Float, fy: Float, cx: Float, cy: Float
        var width: UInt32, height: UInt32
        var origin: SIMD3<Int32>
        var dims: SIMD3<Int32>
        var vs: Float, trunc: Float, carve: Float
        var step: Float
        var camera: SIMD3<Float>
    }

    /// A block from `min` to `max` (metres). Nil without Metal or when the block is too big (> 40M voxels).
    init?(device: MTLDevice? = MTLCreateSystemDefaultDevice(), min: SIMD3<Float>, max: SIMD3<Float>,
          voxel: Float, truncation: Float, carve: Float) {
        guard let device, let queue = device.makeCommandQueue() else { return nil }
        let lo = SIMD3<Int32>((min / voxel).rounded(.down)) &- 2, hi = SIMD3<Int32>((max / voxel).rounded(.up)) &+ 2
        let dims = hi &- lo
        let count = Int(dims.x) * Int(dims.y) * Int(dims.z)
        guard count > 0, count < 40_000_000,
              let library = try? device.makeLibrary(source: Self.source, options: nil),
              let function = library.makeFunction(name: "integrate"),
              let pipeline = try? device.makeComputePipelineState(function: function),
              let sdf = device.makeBuffer(length: count * 4, options: .storageModeShared),
              let weight = device.makeBuffer(length: count * 2, options: .storageModeShared) else { return nil }
        let bricks = (dims &+ 7) / 8
        guard let dirty = device.makeBuffer(length: Swift.max(1, Int(bricks.x) * Int(bricks.y) * Int(bricks.z)), options: .storageModeShared) else { return nil }
        memset(sdf.contents(), 0, count * 4)
        memset(weight.contents(), 0, count * 2)
        memset(dirty.contents(), 0, dirty.length)
        self.dirty = dirty; self.bricks = bricks
        self.device = device; self.queue = queue; self.pipeline = pipeline
        self.sdf = sdf; self.weight = weight
        self.vs = voxel; self.trunc = truncation; self.carve = carve
        self.origin = lo; self.dims = dims
    }

    var voxelCount: Int { Int(dims.x) * Int(dims.y) * Int(dims.z) }

    /// Queues one depth frame: metres per pixel (0 = no depth: filtered out), `width` × `height`, camera pose
    /// (camera → world, ARKit: x right, y up, looking down -z) and intrinsics at the depth map's size. `step` is the
    /// pixel step the CPU fusion would use (for the weights). Runs in batches; call `finish()` at the end.
    func integrate(depth: [Float], width: Int, height: Int, pose: simd_float4x4, fx: Float, fy: Float, cx: Float, cy: Float, step: Int) {
        guard let buffer = device.makeBuffer(bytes: depth, length: depth.count * 4, options: .storageModeShared) else { return }
        if encoder == nil {
            // Made inside an autorelease pool and held only by us: otherwise the command buffer (and the 32 depth
            // buffers it keeps) stays alive until the caller's loop ends; a long recording reached 1.1 GB.
            autoreleasepool {
                commands = queue.makeCommandBuffer()
                encoder = commands?.makeComputeCommandEncoder()
            }
        }
        guard let encoder else { return }
        var p = Params(worldToCam: pose.inverse, fx: fx, fy: fy, cx: cx, cy: cy, width: UInt32(width), height: UInt32(height),
                       origin: origin, dims: dims, vs: vs, trunc: trunc, carve: carve, step: Float(step),
                       camera: SIMD3(pose.columns.3.x, pose.columns.3.y, pose.columns.3.z))
        encoder.setComputePipelineState(pipeline)
        encoder.setBuffer(sdf, offset: 0, index: 0)
        encoder.setBuffer(weight, offset: 0, index: 1)
        encoder.setBuffer(buffer, offset: 0, index: 2)
        encoder.setBytes(&p, length: MemoryLayout<Params>.stride, index: 3)
        encoder.setBuffer(dirty, offset: 0, index: 4)
        let size = MTLSize(width: Int(dims.x), height: Int(dims.y), depth: Int(dims.z))
        let w = pipeline.threadExecutionWidth
        encoder.dispatchThreads(size, threadsPerThreadgroup: MTLSize(width: w, height: max(1, pipeline.maxTotalThreadsPerThreadgroup / w / 4), depth: 4))
        encoder.memoryBarrier(scope: .buffers) // frames in order
        depthBuffers.append(buffer)
        pending += 1
        if pending >= 32 { flush() }
    }

    /// Runs what's queued and waits for it.
    func finish() { flush() }

    private func flush() {
        guard let encoder, let commands else { return }
        encoder.endEncoding()
        commands.commit()
        commands.waitUntilCompleted()
        if commands.status == .error { failed = true }
        self.encoder = nil; self.commands = nil
        pending = 0
        depthBuffers.removeAll()
    }

    /// Every voxel that was seen goes into `tsdf` (same voxel coordinates), for mesh extraction.
    /// `onlyUnseen`: only into voxels `tsdf` doesn't have yet (a second, less sure pass fills holes, never overrides);
    /// `nearSeen` > 0: and only within that many voxels of its surface (holes in what was seen, not new far-off areas).
    func copy(into tsdf: Tsdf, onlyUnseen: Bool = false, nearSeen: Int32 = 0, minWeight: UInt16 = 1) {
        finish()
        let s = sdf.contents().bindMemory(to: Float.self, capacity: voxelCount)
        let w = weight.contents().bindMemory(to: UInt16.self, capacity: voxelCount)
        let nx = Int(dims.x), ny = Int(dims.y)
        for i in 0..<voxelCount where w[i] >= max(1, minWeight) {
            let x = i % nx, y = (i / nx) % ny, z = i / (nx * ny)
            let v = origin &+ SIMD3(Int32(x), Int32(y), Int32(z))
            if onlyUnseen, tsdf.index(v) >= 0 { continue }
            if nearSeen > 0 {
                var found = false
                search: for dz in -nearSeen...nearSeen { for dy in -nearSeen...nearSeen { for dx in -nearSeen...nearSeen where tsdf.isSurface(v &+ SIMD3(dx, dy, dz)) {
                    found = true; break search
                } } }
                if !found { continue }
            }
            tsdf.set(v, sdf: s[i], weight: Int16(clamping: Int(w[i])))
        }
    }

    /// One voxel's signed distance and weight (tests).
    func value(at v: SIMD3<Int32>) -> (sdf: Float, weight: UInt16)? {
        finish()
        let g = v &- origin
        guard all(g .>= 0), all(g .< dims) else { return nil }
        let i = (Int(g.z) * Int(dims.y) + Int(g.y)) * Int(dims.x) + Int(g.x)
        return (sdf.contents().bindMemory(to: Float.self, capacity: voxelCount)[i], weight.contents().bindMemory(to: UInt16.self, capacity: voxelCount)[i])
    }

    private func markDirty(_ g: SIMD3<Int32>) {
        let b = g / 8
        dirty.contents().storeBytes(of: 1, toByteOffset: Int((b.z * bricks.y + b.y) * bricks.x + b.x), as: UInt8.self)
    }

    /// Forgets which bricks changed (after a full `copy(into:)`).
    func clearDirty() { finish(); memset(dirty.contents(), 0, dirty.length) }

    /// Copies only the bricks changed since the last call into `tsdf`, which must hold everything from before (a full
    /// `copy(into:)` then `clearDirty()` to start). Voxels reset to unseen there (tap to clear) are reset in `tsdf`
    /// too. Returns how many bricks were copied.
    @discardableResult
    func copyDirty(into tsdf: Tsdf) -> Int {
        finish()
        let s = sdf.contents().bindMemory(to: Float.self, capacity: voxelCount)
        let w = weight.contents().bindMemory(to: UInt16.self, capacity: voxelCount)
        let d = dirty.contents().bindMemory(to: UInt8.self, capacity: dirty.length)
        let nx = Int(dims.x), ny = Int(dims.y), nz = Int(dims.z)
        let bx = Int(bricks.x), by = Int(bricks.y), bz = Int(bricks.z)
        var copied = 0
        for b in 0..<(bx * by * bz) where d[b] != 0 {
            d[b] = 0
            copied += 1
            let x0 = (b % bx) * 8, y0 = ((b / bx) % by) * 8, z0 = (b / (bx * by)) * 8
            for z in z0..<min(z0 + 8, nz) { for y in y0..<min(y0 + 8, ny) {
                var i = (z * ny + y) * nx + x0
                for x in x0..<min(x0 + 8, nx) {
                    let v = origin &+ SIMD3(Int32(x), Int32(y), Int32(z))
                    if w[i] > 0 { tsdf.set(v, sdf: s[i], weight: Int16(clamping: Int(w[i]))) }
                    else if tsdf.index(v) >= 0 { tsdf.set(v, sdf: 0, weight: 0) }
                    i += 1
                }
            } }
        }
        return copied
    }

    /// Resets every voxel whose centre passes the test (tap to clear while scanning); returns how many.
    func clearWhere(_ test: (SIMD3<Float>) -> Bool) -> Int {
        finish()
        let s = sdf.contents().bindMemory(to: Float.self, capacity: voxelCount)
        let w = weight.contents().bindMemory(to: UInt16.self, capacity: voxelCount)
        let nx = Int(dims.x), ny = Int(dims.y)
        var cleared = 0
        for i in 0..<voxelCount where w[i] > 0 {
            let v = origin &+ SIMD3(Int32(i % nx), Int32((i / nx) % ny), Int32(i / (nx * ny)))
            if test((SIMD3<Float>(v) + 0.5) * vs) { w[i] = 0; s[i] = 0; cleared += 1; markDirty(v &- origin) }
        }
        return cleared
    }

    private static let source = """
    #include <metal_stdlib>
    using namespace metal;
    struct Params {
        float4x4 worldToCam;
        float fx, fy, cx, cy;
        uint width, height;
        int3 origin;      // 16-byte aligned, as Swift's SIMD3<Int32>
        int3 dims;
        float vs, trunc, carve, step;
        float3 camera;
    };
    constant ushort maxW = 40;
    constant ushort solidW = 20;

    kernel void integrate(device float *sdf [[buffer(0)]], device ushort *wgt [[buffer(1)]],
                          const device float *depth [[buffer(2)]], constant Params &p [[buffer(3)]],
                          device uchar *dirty [[buffer(4)]], uint3 g [[thread_position_in_grid]]) {
        int3 dims = p.dims;
        if (g.x >= uint(dims.x) || g.y >= uint(dims.y) || g.z >= uint(dims.z)) return;
        float3 c = (float3(p.origin + int3(g)) + 0.5) * p.vs;
        float4 q = p.worldToCam * float4(c, 1.0);
        float z = -q.z;
        if (z < 0.1) return;
        float u = p.fx * q.x / z + p.cx, v = p.cy - p.fy * q.y / z;
        if (u < 0.0 || v < 0.0 || u >= float(p.width) || v >= float(p.height)) return;
        float d = depth[uint(v) * p.width + uint(u)];
        if (d <= 0.0) return;
        // Along the ray: the surface is at L, the voxel at r (as the CPU measures it).
        float r = length(c - p.camera);
        float L = d * r / z;
        float s = L - r;                       // + = in front of the surface
        uint i = (uint(g.z) * uint(dims.y) + uint(g.y)) * uint(dims.x) + uint(g.x);
        uint3 b = g / 8; uint3 nb = (uint3(dims) + 7) / 8;
        uint bi = (b.z * nb.y + b.y) * nb.x + b.x;
        ushort w = wgt[i];
        // How many CPU samples (every `step`th pixel) would pass through this voxel: its size over a pixel's footprint.
        float footprint = z / p.fx * p.step;
        ushort add = ushort(clamp((p.vs / footprint) * (p.vs / footprint), 1.0, 8.0));
        if (s > p.trunc) {
            // Free space: only voxels already seen, between the margin and `carve` in front of the surface.
            float nearS = p.trunc + 0.05 * L, farS = min(p.carve, L - 0.25);
            if (w == 0 || s <= nearS || s > farS) return;
            if (w >= solidW) { sdf[i] += (1.0 - sdf[i]) * 0.04; dirty[bi] = 1; return; }
            float sum = sdf[i] * float(w) + float(add);
            ushort nw = min(ushort(w + add), maxW);
            sdf[i] = sum / float(w + add);
            wgt[i] = nw;
            dirty[bi] = 1;
            return;
        }
        if (s < -p.trunc) return;              // behind the surface: unknown
        float dn = clamp(s / p.trunc, -1.0, 1.0);
        sdf[i] = (sdf[i] * float(w) + dn * float(add)) / float(w + add);
        wgt[i] = min(ushort(w + add), maxW);
        dirty[bi] = 1;
    }
    """
}

/// Holes filled from low-confidence depth (rk 9 Nov: the S24 filled spots the iPhone left open, e.g. floor around the
/// chair; it fuses depth the iPhone throws away). After the normal pass, a second block is fused from the iPhone's
/// low-confidence depth, but only from pixels on a smooth surface seen at a glancing angle (floors and box sides go
/// low-confidence that way; the edges of thin things are low-confidence head-on and would bridge a ladder into a sheet),
/// 0.4–3 m away; its voxels only go where the normal pass has nothing, within 3 voxels of its surface, and only where
/// several frames agreed. Checked on rk's 20:33 and 22:39 recordings (harness LOWCONF=1): +1–2% surface, nothing new
/// far from the room, no sheets at the ladder; S24-only surface 16.6% → 11.7%.
enum LowConfidenceFill {
    static let nearSeen: Int32 = 3
    static let minWeight: UInt16 = 6
    /// Glancing: the surface normal at more than 60° from the view ray.
    static let maxCos: Float = 0.5

    /// Whether the low-confidence depth pixel (x, y) is used. `z(x, y)` is the depth in metres (0 = none).
    @inline(__always)
    static func use(_ x: Int, _ y: Int, width: Int, height: Int, fx: Float, fy: Float, cx: Float, cy: Float, z: (Int, Int) -> Float) -> Bool {
        let d = z(x, y)
        guard d > 0.4, d < 3, x >= 2, y >= 2, x < width - 2, y < height - 2 else { return false }
        let tol = d * 0.02 + 0.01
        for dy in -2...2 { for dx in -2...2 where abs(z(x + dx, y + dy) - d) > tol { return false } }
        func pt(_ px: Int, _ py: Int) -> SIMD3<Float> {
            let zz = z(px, py)
            return SIMD3((Float(px) + 0.5 - cx) * zz / fx, -(Float(py) + 0.5 - cy) * zz / fy, -zz)
        }
        let n = simd_cross(pt(x + 2, y) - pt(x - 2, y), pt(x, y - 2) - pt(x, y + 2))
        guard simd_length(n) > 1e-9 else { return false }
        return abs(simd_dot(simd_normalize(n), simd_normalize(pt(x, y)))) < maxCos
    }
}

