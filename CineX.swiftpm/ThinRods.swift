import simd

/// Thin bars and rods the fused surface misses (rk 9 Nov: the climbing ladder's 3 cm bars came out as fragments or
/// paint splatters on the wall behind). The 4 cm surface can't hold a 3 cm bar, but the LiDAR does see it: depth
/// points that sit well off the surface, in the same place in several frames. Those are collected in 1.5 cm cells,
/// straight runs of them are found (RANSAC, then PCA), and each becomes a rod: a short cylinder added to the mesh.
///
/// Shared by the app (rebuild from recording) and the Mac harness (RODS=1).
enum ThinRods {
    struct Rod: Equatable {
        var a: SIMD3<Float>, b: SIMD3<Float>
        var radius: Float
        /// Cells along it.
        var support: Int
        var length: Float { simd_distance(a, b) }
    }

    /// Depth nearer than this is used (metres): further away a 3 cm bar is a pixel or two.
    static let maxDepth: Float = 2.0
    /// A point this far off the surface (metres, along its normal) counts as off it.
    static let offSurface: Float = 0.03
    static let cell: Float = 0.015
    /// Frames a cell must be seen in.
    static let minFrames = 3
    /// A rod: cells within `lineTolerance` of a line, at least `minLength` long with no gap over `maxGap`.
    static let lineTolerance: Float = 0.02
    static let minLength: Float = 0.15
    static let maxGap: Float = 0.05
    static let minSupport = 8
    static let maxRods = 60

    /// Collects off-surface depth points, frame by frame.
    final class Collector {
        private let positions: [SIMD3<Float>]
        private var normals: [SIMD3<Float>]
        private var grid: [SIMD3<Int32>: [Int32]] = [:]
        private static let gridCell: Float = 0.03
        private struct Cell { var sum: SIMD3<Float>; var count: Int32; var frames: Int32; var lastFrame: Int32 }
        private var cells: [SIMD3<Int32>: Cell] = [:]
        private var frame: Int32 = 0
        /// Depth points looked at, those with no surface within ~6 cm, and those further than `offSurface` from it.
        private(set) var stats = (seen: 0, noSurface: 0, off: 0)

        init(positions: [SIMD3<Float>], triangles: [UInt32]) {
            self.positions = positions
            normals = [SIMD3<Float>](repeating: .zero, count: positions.count)
            for t in stride(from: 0, to: triangles.count - 2, by: 3) {
                let a = Int(triangles[t]), b = Int(triangles[t + 1]), c = Int(triangles[t + 2])
                let n = simd_cross(positions[b] - positions[a], positions[c] - positions[a])
                normals[a] += n; normals[b] += n; normals[c] += n
            }
            for i in normals.indices { let l = simd_length(normals[i]); normals[i] = l > 1e-12 ? normals[i] / l : .zero }
            for (i, p) in positions.enumerated() { grid[Self.key(p, Self.gridCell), default: []].append(Int32(i)) }
        }

        private static func key(_ p: SIMD3<Float>, _ s: Float) -> SIMD3<Int32> { SIMD3<Int32>((p / s).rounded(.down)) }

        /// How far `p` is off the surface towards `camera`: along the nearest corner's normal, 0 when it's behind the
        /// surface (seen from the camera, a point behind it is the surface itself a little off, or seen through it,
        /// never a bar: a bar stands between the camera and what's behind it); infinity with no corner within ~6 cm.
        func distance(_ p: SIMD3<Float>, camera: SIMD3<Float>) -> Float {
            let k = Self.key(p, Self.gridCell)
            var best = Float.infinity, bi: Int32 = -1
            for dz: Int32 in -2...2 { for dy: Int32 in -2...2 { for dx: Int32 in -2...2 {
                guard let list = grid[k &+ SIMD3(dx, dy, dz)] else { continue }
                for i in list { let d = simd_distance_squared(p, positions[Int(i)]); if d < best { best = d; bi = i } }
            } } }
            guard bi >= 0 else { return .infinity }
            let v = positions[Int(bi)], n = normals[Int(bi)]
            guard simd_length_squared(n) > 0.5 else { return best.squareRoot() }
            let side = simd_dot(p - v, n), camSide = simd_dot(camera - v, n)
            return side * camSide > 0 ? abs(side) : 0
        }

        /// How far `p` is off the surface: along the nearest corner's normal; infinity with no corner within ~6 cm.
        func distance(_ p: SIMD3<Float>) -> Float {
            let k = Self.key(p, Self.gridCell)
            var best = Float.infinity, bi: Int32 = -1
            for dz: Int32 in -2...2 { for dy: Int32 in -2...2 { for dx: Int32 in -2...2 {
                guard let list = grid[k &+ SIMD3(dx, dy, dz)] else { continue }
                for i in list { let d = simd_distance_squared(p, positions[Int(i)]); if d < best { best = d; bi = i } }
            } } }
            guard bi >= 0 else { return .infinity }
            let n = normals[Int(bi)]
            return simd_length_squared(n) > 0.5 ? abs(simd_dot(p - positions[Int(bi)], n)) : best.squareRoot()
        }

        /// One depth frame: metres per pixel (0 = none), camera → world pose (ARKit axes), intrinsics at its size.
        func add(depth: [Float], width: Int, height: Int, pose: simd_float4x4, fx: Float, fy: Float, cx: Float, cy: Float, step: Int = 2) {
            frame += 1
            for y in stride(from: 0, to: height, by: step) { for x in stride(from: 0, to: width, by: step) {
                let z = depth[y * width + x]
                guard z > 0.2, z < ThinRods.maxDepth else { continue }
                let p4 = pose * SIMD4((Float(x) + 0.5 - cx) * z / fx, -(Float(y) + 0.5 - cy) * z / fy, -z, 1)
                let p = SIMD3(p4.x, p4.y, p4.z)
                let dist = distance(p, camera: SIMD3(pose.columns.3.x, pose.columns.3.y, pose.columns.3.z))
                stats.seen += 1
                if dist == .infinity { stats.noSurface += 1 }
                guard dist > ThinRods.offSurface else { continue }
                stats.off += 1
                let k = Self.key(p, ThinRods.cell)
                var c = cells[k] ?? Cell(sum: .zero, count: 0, frames: 0, lastFrame: 0)
                c.sum += p; c.count += 1
                if c.lastFrame != frame { c.frames += 1; c.lastFrame = frame }
                cells[k] = c
            } }
        }

        /// Second pass, after every frame went through `add`: for the cells seen in enough frames, counts the frames
        /// that could have seen them (in view, nearer than `maxDepth`, and not hidden behind something nearer). Depth
        /// noise off a wall lands in a cell now and then; a real bar is there nearly every time it's in view.
        func check(depth: [Float], width: Int, height: Int, pose: simd_float4x4, fx: Float, fy: Float, cx: Float, cy: Float) {
            if candidates == nil {
                let keep = cells.filter { $0.value.frames >= Int32(ThinRods.minFrames) }
                candidates = keep.map { (key: $0.key, p: $0.value.sum / Float($0.value.count), frames: $0.value.frames) }
                visible = [Int32](repeating: 0, count: candidates!.count)
            }
            let toCam = pose.inverse
            for (i, c) in candidates!.enumerated() {
                let q = toCam * SIMD4(c.p, 1)
                let z = -q.z
                guard z > 0.2, z < ThinRods.maxDepth else { continue }
                let u = Int(fx * q.x / z + cx), v = Int(cy - fy * q.y / z)
                guard u >= 0, v >= 0, u < width, v < height else { continue }
                let d = depth[v * width + u]
                // Unknown depth there, or something clearly nearer in front: not a chance to see it.
                guard d > 0, d > z - 0.05 else { continue }
                visible[i] += 1
            }
        }
        private var candidates: [(key: SIMD3<Int32>, p: SIMD3<Float>, frames: Int32)]?
        private var visible: [Int32] = []
        /// Share of the frames that could see a cell in which it was hit.
        static let minHitShare: Float = 0.5

        /// The off-surface cells seen in enough frames (their mean points); after `check`, only those hit in at
        /// least `minHitShare` of the frames that could see them.
        var points: [SIMD3<Float>] {
            guard let candidates else {
                return cells.values.filter { $0.frames >= Int32(ThinRods.minFrames) }.map { $0.sum / Float($0.count) }
            }
            return candidates.indices.filter { Float(candidates[$0].frames) >= Self.minHitShare * Float(max(1, visible[$0])) }.map { candidates[$0].p }
        }

        func rods() -> [Rod] { ThinRods.fit(points) }
    }

    /// Straight runs in `points`, longest-supported first.
    static func fit(_ input: [SIMD3<Float>]) -> [Rod] {
        var points = input
        var rods: [Rod] = []
        var rng = SplitMix(seed: 0x5eed)
        while rods.count < maxRods, points.count >= minSupport {
            // Neighbours for sampling: 30 cm cells.
            var grid: [SIMD3<Int32>: [Int]] = [:]
            for (i, p) in points.enumerated() { grid[SIMD3<Int32>((p / 0.3).rounded(.down)), default: []].append(i) }
            var best: (inliers: [Int], a: SIMD3<Float>, d: SIMD3<Float>)? = nil
            for _ in 0..<600 {
                let i = Int(rng.next() % UInt64(points.count))
                let k = SIMD3<Int32>((points[i] / 0.3).rounded(.down))
                var near: [Int] = []
                for dz: Int32 in -1...1 { for dy: Int32 in -1...1 { for dx: Int32 in -1...1 { near += grid[k &+ SIMD3(dx, dy, dz)] ?? [] } } }
                guard near.count >= minSupport else { continue }
                let j = near[Int(rng.next() % UInt64(near.count))]
                let v = points[j] - points[i], l = simd_length(v)
                guard l > 0.08 else { continue }
                let d = v / l
                // Inliers among the neighbourhood only (a rod longer than ~60 cm grows in the refinement below).
                var inl: [Int] = []
                for n in near {
                    let e = points[n] - points[i]
                    if simd_length_squared(e - simd_dot(e, d) * d) < lineTolerance * lineTolerance { inl.append(n) }
                }
                if inl.count > (best?.inliers.count ?? minSupport - 1) { best = (inl, points[i], d) }
            }
            guard var b = best else { break }
            // Refine: PCA axis of the inliers, then all points near that line (whole cloud), twice.
            var rod: Rod? = nil
            var used: [Int] = []
            for _ in 0..<2 {
                let pts = b.inliers.map { points[$0] }
                let mean = pts.reduce(.zero, +) / Float(pts.count)
                var cov = simd_float3x3()
                for q in pts { let e = q - mean; cov += simd_float3x3(e * e.x, e * e.y, e * e.z) }
                var axis = b.d
                for _ in 0..<30 { let n = cov * axis; if simd_length(n) > 1e-12 { axis = simd_normalize(n) } }
                var inl: [Int] = []
                for (n, q) in points.enumerated() {
                    let e = q - mean
                    if simd_length_squared(e - simd_dot(e, axis) * axis) < lineTolerance * lineTolerance { inl.append(n) }
                }
                b = (inl, mean, axis)
            }
            // The longest run without a gap, along the axis.
            let along = b.inliers.map { (simd_dot(points[$0] - b.a, b.d), $0) }.sorted { $0.0 < $1.0 }
            var runStart = 0, bestRun = (0, -1)
            for i in along.indices {
                if i > 0, along[i].0 - along[i - 1].0 > maxGap { runStart = i }
                if i - runStart > bestRun.1 - bestRun.0 { bestRun = (runStart, i) }
            }
            if bestRun.1 >= 0 {
                let run = Array(along[bestRun.0...bestRun.1])
                used = run.map(\.1)
                let pts = run.map { points[$0.1] }
                // Thin: the spread across the axis stays small (a noisy wall patch isn't a rod).
                let perp = pts.map { q -> Float in let e = q - b.a; return simd_length(e - simd_dot(e, b.d) * b.d) }.sorted()
                let length = run.last!.0 - run.first!.0
                if length >= minLength, pts.count >= minSupport, Float(pts.count) >= length / cell * 0.3 {
                    // The cells lie on the side the camera saw: the axis is about a radius behind them, so the
                    // spread across the axis underestimates it. Radius from the 80th-percentile spread, 0.8–2.5 cm.
                    let r = min(0.025, max(0.008, perp[perp.count * 4 / 5] * 1.5 + 0.005))
                    rod = Rod(a: b.a + b.d * run.first!.0, b: b.a + b.d * run.last!.0, radius: r, support: pts.count)
                }
            }
            if let rod { rods.append(rod) }
            // Remove what was used (or the sample's inliers when no rod came of it, so the search moves on).
            let drop = Set(used.isEmpty ? b.inliers : used)
            if drop.isEmpty { break }
            points = points.enumerated().filter { !drop.contains($0.offset) }.map(\.element)
        }
        return rods
    }

    /// The rods as closed 8-sided cylinders.
    static func mesh(_ rods: [Rod], sides: Int = 8) -> (positions: [SIMD3<Float>], triangles: [UInt32]) {
        var pos: [SIMD3<Float>] = [], tri: [UInt32] = []
        for r in rods {
            let d = simd_normalize(r.b - r.a)
            let helper: SIMD3<Float> = abs(d.y) < 0.9 ? [0, 1, 0] : [1, 0, 0]
            let u = simd_normalize(simd_cross(d, helper)), v = simd_cross(d, u)
            let base = UInt32(pos.count)
            for end in [r.a, r.b] {
                for s in 0..<sides {
                    let a = Float(s) / Float(sides) * 2 * .pi
                    pos.append(end + (u * cos(a) + v * sin(a)) * r.radius)
                }
            }
            pos.append(r.a); pos.append(r.b)
            let ca = base + UInt32(2 * sides), cb = ca + 1
            for s in 0..<sides {
                let s0 = base + UInt32(s), s1 = base + UInt32((s + 1) % sides)
                let t0 = s0 + UInt32(sides), t1 = s1 + UInt32(sides)
                tri += [s0, s1, t1, s0, t1, t0, ca, s1, s0, cb, t0, t1]
            }
        }
        return (pos, tri)
    }

    /// Small deterministic random numbers (same rods every run).
    struct SplitMix {
        var state: UInt64
        init(seed: UInt64) { state = seed }
        mutating func next() -> UInt64 {
            state &+= 0x9E3779B97F4A7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
            z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
            return z ^ (z >> 31)
        }
    }
}
