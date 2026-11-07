import Accelerate
import Foundation
import Metal
import simd

/// On-phone gaussian-splat training on the GPU (port of Android `Trainer.java`, handoff section 2 "Splat training").
///
/// The scan's surfels are merged into fewer, larger splats (one per 3 cm cell, smaller cells for close-ups). Then,
/// one photo at a time: draw the splats from that photo's camera as soft gaussians (front-to-back alpha blending over
/// a constant grey), compare with the photo pixel by pixel, and nudge every splat's colour, opacity, size and 3D
/// position (at most 12 cm) to match better (Adam). The background must stay constant: with the photo behind them,
/// the splats would "cheat" by going transparent.
///
/// Not thread-safe: call everything from one serial queue.
final class SplatTrainer {
    static let cell: Float = 0.03
    static let background: Float = 0.45
    static let maxOffset: Float = 0.12

    /// A starting splat: one per cell of the scan's surfels.
    struct Seed {
        var position: SIMD3<Float>
        /// Zero when the cell's surfels don't agree on a surface.
        var normal: SIMD3<Float>
        var radius: Float
        /// sRGB 0…1, like the photos.
        var color: SIMD3<Float>
        /// Carried through for the viewer (`SurfelRecord.wallAngle`).
        var wallAngle: Float
        /// Unit normal of the RoomPlan floor or wall the splat lies on (it may slide along it but not leave it),
        /// or zero when it's free.
        var plane: SIMD3<Float> = .zero
    }

    /// A trained splat, for drawing.
    struct Output {
        var position: SIMD3<Float>
        var normal: SIMD3<Float>
        /// The gaussian's sigma in metres.
        var sigma: Float
        var color: SIMD3<Float>
        var opacity: Float
        var wallAngle: Float
    }

    /// One training photo: the keyframe shrunk to the training size, with its camera.
    struct View {
        var worldToCamera: simd_float4x4
        /// Pixels at the training size; v grows downwards.
        var fx: Float, fy: Float, cx: Float, cy: Float
        var width: Int, height: Int
        /// width × height × 4 bytes, sRGB.
        var rgba: [UInt8]
    }

    // Must match `Splat`, `Proj` and `StepParams` in the shader source.
    struct GPUSplat { var bx, by, bz, rad0, ox, oy, oz, ls, r, g, b, a: Float }
    private struct GPUProj { var u, v, sig, o, d, xc, yc, pad: Float }
    private struct StepParams {
        var view: simd_float4x4
        var fx, fy, cu, cv: Float
        var width, height, tileW, tileH: UInt32
        var n, nv, imageOffset, pad0: UInt32
        var bg, maxT, c1, c2: Float
    }

    enum TrainerError: Error { case noMetal, shader(String), noViews, mixedPhotoSizes }

    let n: Int
    let seeds: [Seed]
    private(set) var iteration = 0
    private(set) var firstLoss: Float = -1
    private(set) var lastLoss: Float = 0
    let viewCount: Int

    private let device: MTLDevice
    private let queue: MTLCommandQueue
    private let projectPipeline, forwardPipeline, backwardPipeline, adamPipeline: MTLComputePipelineState
    private let tileW: Int, tileH: Int
    private let width: Int, height: Int
    private let views: [(matrix: simd_float4x4, fx: Float, fy: Float, cx: Float, cy: Float)]

    private let splats, proj, ids, depths, order, count, grads, m1, m2, pixT, pixLast, pixDR, images, planes: MTLBuffer
    private var sortIndex: [vDSP_Length] = []

    init(seeds: [Seed], views: [View], device: MTLDevice? = MTLCreateSystemDefaultDevice()) throws {
        guard let device, let queue = device.makeCommandQueue() else { throw TrainerError.noMetal }
        guard let first = views.first else { throw TrainerError.noViews }
        guard views.allSatisfy({ $0.width == first.width && $0.height == first.height }) else { throw TrainerError.mixedPhotoSizes }
        self.device = device
        self.queue = queue
        self.seeds = seeds
        n = seeds.count
        viewCount = views.count
        width = first.width
        height = first.height

        let options = MTLCompileOptions()
        options.languageVersion = .version3_0 // float atomics
        let library: MTLLibrary
        do { library = try device.makeLibrary(source: SplatTrainerShaders.source, options: options) }
        catch { throw TrainerError.shader(String(describing: error)) }
        func pipeline(_ name: String) throws -> MTLComputePipelineState {
            guard let f = library.makeFunction(name: name) else { throw TrainerError.shader("missing \(name)") }
            return try device.makeComputePipelineState(function: f)
        }
        projectPipeline = try pipeline("project")
        forwardPipeline = try pipeline("forward")
        backwardPipeline = try pipeline("backward")
        adamPipeline = try pipeline("adam")
        // 16x16 pixel tiles when the GPU allows 256 threads for both passes, else smaller.
        let maxThreads = min(forwardPipeline.maxTotalThreadsPerThreadgroup, backwardPipeline.maxTotalThreadsPerThreadgroup)
        (tileW, tileH) = maxThreads >= 256 ? (16, 16) : maxThreads >= 128 ? (16, 8) : (8, 8)

        func buffer(_ length: Int) -> MTLBuffer {
            device.makeBuffer(length: max(16, length), options: .storageModeShared)!
        }
        splats = buffer(n * MemoryLayout<GPUSplat>.stride)
        proj = buffer(n * MemoryLayout<GPUProj>.stride)
        ids = buffer(n * 4)
        depths = buffer(n * 4)
        order = buffer(n * 4)
        count = buffer(4)
        grads = buffer(n * 8 * 4)
        m1 = buffer(n * 8 * 4)
        m2 = buffer(n * 8 * 4)
        let pixels = width * height
        pixT = buffer(pixels * 4)
        pixLast = buffer(pixels * 4)
        pixDR = buffer(pixels * 16)
        images = buffer(pixels * 4 * views.count)
        planes = buffer(n * 16)
        let plane = planes.contents().bindMemory(to: SIMD4<Float>.self, capacity: n)
        for i in 0..<n {
            let p = seeds[i].plane
            plane[i] = simd_length_squared(p) > 0.5 ? SIMD4(p, 1) : .zero
        }
        memset(grads.contents(), 0, grads.length)
        memset(m1.contents(), 0, m1.length)
        memset(m2.contents(), 0, m2.length)

        let s = splats.contents().bindMemory(to: GPUSplat.self, capacity: n)
        for i in 0..<n {
            let seed = seeds[i]
            s[i] = GPUSplat(bx: seed.position.x, by: seed.position.y, bz: seed.position.z, rad0: seed.radius,
                            ox: 0, oy: 0, oz: 0, ls: 0, r: seed.color.x, g: seed.color.y, b: seed.color.z,
                            a: 2) // opacity 0.88
        }
        let image = images.contents().bindMemory(to: UInt8.self, capacity: pixels * 4 * views.count)
        for (i, view) in views.enumerated() {
            view.rgba.withUnsafeBufferPointer { src in
                (image + i * pixels * 4).update(from: src.baseAddress!, count: min(src.count, pixels * 4))
            }
        }
        self.views = views.map { ($0.worldToCamera, $0.fx, $0.fy, $0.cx, $0.cy) }
    }

    /// One training step on one photo. Returns the mean squared error before the update.
    @discardableResult
    func step(view index: Int, update: Bool = true) -> Float {
        run(view: index, backward: update, adam: update)
    }

    /// The raw per-splat gradients for one photo (colour 3, opacity, sigma, u, v, unused), without a step. For tests.
    func rawGradients(view index: Int) -> [Float] {
        run(view: index, backward: true, adam: false)
        let g = grads.contents().bindMemory(to: Float.self, capacity: n * 8)
        let out = Array(UnsafeBufferPointer(start: g, count: n * 8))
        memset(grads.contents(), 0, grads.length)
        return out
    }

    @discardableResult
    private func run(view index: Int, backward: Bool, adam: Bool) -> Float {
        let update = adam
        let v = views[index]
        var params = StepParams(view: v.matrix, fx: v.fx, fy: v.fy, cu: v.cx, cv: v.cy,
                                width: UInt32(width), height: UInt32(height), tileW: UInt32(tileW), tileH: UInt32(tileH),
                                n: UInt32(n), nv: 0, imageOffset: UInt32(index * width * height), pad0: 0,
                                bg: Self.background, maxT: Self.maxOffset, c1: 1, c2: 1)

        // 1. Project, and list the visible splats.
        count.contents().storeBytes(of: UInt32(0), as: UInt32.self)
        guard let cb1 = queue.makeCommandBuffer(), let e1 = cb1.makeComputeCommandEncoder() else { return lastLoss }
        e1.setComputePipelineState(projectPipeline)
        e1.setBytes(&params, length: MemoryLayout<StepParams>.stride, index: 0)
        e1.setBuffers([splats, proj, ids, count], offsets: [0, 0, 0, 0], range: 1..<5)
        e1.setBuffer(depths, offset: 0, index: 11)
        e1.dispatchThreads(MTLSize(width: n, height: 1, depth: 1),
                           threadsPerThreadgroup: MTLSize(width: min(n, 256), height: 1, depth: 1))
        e1.endEncoding()
        cb1.commit()
        cb1.waitUntilCompleted()
        let nv = Int(count.contents().load(as: UInt32.self))

        // 2. Front to back.
        sortByDepth(count: nv)
        params.nv = UInt32(nv)
        if update {
            iteration += 1
            params.c1 = 1 - pow(0.9, Float(iteration))
            params.c2 = 1 - pow(0.999, Float(iteration))
        }

        // 3. Forward, backward, Adam.
        guard let cb2 = queue.makeCommandBuffer(), let e2 = cb2.makeComputeCommandEncoder() else { return lastLoss }
        let tiles = MTLSize(width: (width + tileW - 1) / tileW, height: (height + tileH - 1) / tileH, depth: 1)
        let tile = MTLSize(width: tileW, height: tileH, depth: 1)
        e2.setBytes(&params, length: MemoryLayout<StepParams>.stride, index: 0)
        e2.setBuffers([splats, proj, order, images, pixT, pixLast, pixDR, grads, m1, m2],
                      offsets: Array(repeating: 0, count: 10), range: 1..<11)
        e2.setBuffer(planes, offset: 0, index: 12)
        e2.setComputePipelineState(forwardPipeline)
        e2.dispatchThreadgroups(tiles, threadsPerThreadgroup: tile)
        if backward && nv > 0 {
            e2.setComputePipelineState(backwardPipeline)
            e2.dispatchThreadgroups(tiles, threadsPerThreadgroup: tile)
        }
        if adam && nv > 0 {
            e2.setComputePipelineState(adamPipeline)
            e2.dispatchThreads(MTLSize(width: nv, height: 1, depth: 1),
                               threadsPerThreadgroup: MTLSize(width: min(nv, 256), height: 1, depth: 1))
        }
        e2.endEncoding()
        cb2.commit()
        cb2.waitUntilCompleted()

        // Squared error is in every 4th float of pixDR.
        var sum: Float = 0
        vDSP_sve(pixDR.contents().bindMemory(to: Float.self, capacity: width * height * 4) + 3, 4, &sum,
                 vDSP_Length(width * height))
        lastLoss = sum / Float(width * height)
        if firstLoss < 0 { firstLoss = lastLoss }
        return lastLoss
    }

    /// Fills `order` with the visible splats front to back. Accelerate's sort is fast even in Debug builds.
    private func sortByDepth(count nv: Int) {
        guard nv > 0 else { return }
        if sortIndex.count < nv { sortIndex = [vDSP_Length](repeating: 0, count: n) }
        let id = ids.contents().bindMemory(to: UInt32.self, capacity: nv)
        let out = order.contents().bindMemory(to: UInt32.self, capacity: nv)
        sortIndex.withUnsafeMutableBufferPointer { idx in
            let p = idx.baseAddress!
            for k in 0..<nv { p[k] = vDSP_Length(k) }
            vDSP_vsorti(depths.contents().bindMemory(to: Float.self, capacity: nv), p, nil, vDSP_Length(nv), 1)
            for k in 0..<nv { out[k] = id[Int(p[k])] }
        }
    }

    /// The splats as they are now. Nearly transparent ones are left out.
    func output(minOpacity: Float = 0.08) -> [Output] {
        let s = splats.contents().bindMemory(to: GPUSplat.self, capacity: n)
        var out: [Output] = []
        out.reserveCapacity(n)
        for i in 0..<n {
            let p = s[i]
            let opacity = 1 / (1 + exp(-p.a))
            guard opacity > minOpacity else { continue }
            out.append(Output(position: SIMD3(p.bx + p.ox, p.by + p.oy, p.bz + p.oz), normal: seeds[i].normal,
                              sigma: p.rad0 * exp(p.ls) * 0.6, color: SIMD3(p.r, p.g, p.b), opacity: opacity,
                              wallAngle: seeds[i].wallAngle))
        }
        return out
    }

    /// The trainable parameters, for tests.
    var parameters: [GPUSplat] {
        Array(UnsafeBufferPointer(start: splats.contents().bindMemory(to: GPUSplat.self, capacity: n), count: n))
    }

    /// Sorts the seed keys with the C library (a Swift sort of 300k keys is slow in Debug builds).
    static func sortKeys(_ keys: UnsafeMutablePointer<UInt64>, count: Int) {
        guard count > 1 else { return }
        qsort(keys, count, 8) { a, b in
            let x = a!.load(as: UInt64.self), y = b!.load(as: UInt64.self)
            return x < y ? -1 : x > y ? 1 : 0
        }
    }

    // MARK: Seeds

    /// One surfel of the scan (`SurfelRecord`'s fields).
    struct Surfel {
        var position: SIMD3<Float>
        var normal: SIMD3<Float>
        var radius: Float
        var rgba: UInt32
        var wallAngle: Float
    }

    /// Merges surfels into one splat per cell (average position, colour and normal). 3 cm cells suit a room;
    /// a close-up needs smaller ones, so the cell shrinks until there are enough splats for the detail scanned.
    static func seeds(from surfels: [Surfel], noWall: Float) -> (seeds: [Seed], cell: Float) {
        let n = surfels.count
        guard n > 0 else { return ([], cell) }
        let target = min(60_000, Int(Float(n) * 0.35))
        var keys = [UInt64](repeating: 0, count: n)
        var cellUsed = cell
        var cells = 0
        surfels.withUnsafeBufferPointer { s in
            keys.withUnsafeMutableBufferPointer { k in
                for cs in [cell, 0.02, 0.014, 0.01] as [Float] {
                    cellUsed = cs
                    for i in 0..<n {
                        let p = s[i].position
                        let ix = UInt64(bitPattern: Int64(floor(p.x / cs)) + 4096) & 8191
                        let iy = UInt64(bitPattern: Int64(floor(p.y / cs)) + 4096) & 8191
                        let iz = UInt64(bitPattern: Int64(floor(p.z / cs)) + 4096) & 8191
                        k[i] = ((ix | (iy << 13) | (iz << 26)) << 21) | UInt64(i)
                    }
                    sortKeys(k.baseAddress!, count: n)
                    cells = 0
                    for j in 0..<n where j == 0 || (k[j] >> 21) != (k[j - 1] >> 21) { cells += 1 }
                    if cells >= target { break }
                }
            }
        }
        var seeds: [Seed] = []
        seeds.reserveCapacity(cells)
        surfels.withUnsafeBufferPointer { s in
            var j = 0
            while j < n {
                var end = j + 1
                while end < n && (keys[end] >> 21) == (keys[j] >> 21) { end += 1 }
                var pos = SIMD3<Float>(), col = SIMD3<Float>(), nrm = SIMD3<Float>(), first = SIMD3<Float>()
                var rad: Float = 0
                var normals = 0
                var wall = noWall
                for q in j..<end {
                    let e = s[Int(keys[q] & 0x1F_FFFF)]
                    pos += e.position
                    col += SIMD3(Float(e.rgba & 0xFF), Float((e.rgba >> 8) & 0xFF), Float((e.rgba >> 16) & 0xFF)) / 255
                    var a = e.normal
                    if simd_length_squared(a) > 0.25 {
                        if normals == 0 { first = a }
                        if simd_dot(a, first) < 0 { a = -a }
                        nrm += a
                        normals += 1
                    }
                    rad += e.radius
                    if wall == noWall { wall = e.wallAngle }
                }
                let cnt = Float(end - j)
                let l = simd_length(nrm)
                let normal = normals * 2 >= end - j && l > Float(normals) * 0.5 ? nrm / l : SIMD3<Float>()
                seeds.append(Seed(position: pos / cnt, normal: normal,
                                  radius: max(rad / cnt, cellUsed * 0.6) * (end - j == 1 ? 0.8 : 1),
                                  color: col / cnt, wallAngle: wall))
                j = end
            }
        }
        return (seeds, cellUsed)
    }
}
