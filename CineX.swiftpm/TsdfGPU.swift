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
    private var commands: MTLCommandBuffer?
    private var encoder: MTLComputeCommandEncoder?
    private var pending = 0
    private var depthBuffers: [MTLBuffer] = []

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
        memset(sdf.contents(), 0, count * 4)
        memset(weight.contents(), 0, count * 2)
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
            commands = queue.makeCommandBuffer()
            encoder = commands?.makeComputeCommandEncoder()
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
        self.encoder = nil; self.commands = nil
        pending = 0
        depthBuffers.removeAll()
    }

    /// Every voxel that was seen goes into `tsdf` (same voxel coordinates), for mesh extraction.
    func copy(into tsdf: Tsdf) {
        finish()
        let s = sdf.contents().bindMemory(to: Float.self, capacity: voxelCount)
        let w = weight.contents().bindMemory(to: UInt16.self, capacity: voxelCount)
        let nx = Int(dims.x), ny = Int(dims.y)
        for i in 0..<voxelCount where w[i] > 0 {
            let x = i % nx, y = (i / nx) % ny, z = i / (nx * ny)
            tsdf.set(origin &+ SIMD3(Int32(x), Int32(y), Int32(z)), sdf: s[i], weight: Int16(clamping: Int(w[i])))
        }
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
                          uint3 g [[thread_position_in_grid]]) {
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
        ushort w = wgt[i];
        // How many CPU samples (every `step`th pixel) would pass through this voxel: its size over a pixel's footprint.
        float footprint = z / p.fx * p.step;
        ushort add = ushort(clamp((p.vs / footprint) * (p.vs / footprint), 1.0, 8.0));
        if (s > p.trunc) {
            // Free space: only voxels already seen, between the margin and `carve` in front of the surface.
            float nearS = p.trunc + 0.05 * L, farS = min(p.carve, L - 0.25);
            if (w == 0 || s <= nearS || s > farS) return;
            if (w >= solidW) { sdf[i] += (1.0 - sdf[i]) * 0.04; return; }
            float sum = sdf[i] * float(w) + float(add);
            ushort nw = min(ushort(w + add), maxW);
            sdf[i] = sum / float(w + add);
            wgt[i] = nw;
            return;
        }
        if (s < -p.trunc) return;              // behind the surface: unknown
        float dn = clamp(s / p.trunc, -1.0, 1.0);
        sdf[i] = (sdf[i] * float(w) + dn * float(add)) / float(w + add);
        wgt[i] = min(ushort(w + add), maxW);
    }
    """
}
