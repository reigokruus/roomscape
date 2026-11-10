import simd

/// Frame-to-model alignment (rk 10 Nov: looking at the bars again and again made them worse: later passes came in a
/// few cm off and every pass fused a shifted copy, so rails doubled and rungs wore away). Before a depth frame is
/// fused, its pose is nudged so its depth points land on the surface fused so far (the zero crossing of the
/// TSDF: Gauss–Newton on the signed distance, a few iterations); the correction carries on to the next frames, and a
/// frame that doesn't fit is skipped. Works on the GPU block's memory (TsdfGPU, shared with the CPU after finish()).
/// Shared by the app (rebuild from recording) and the Mac harness.
struct FrameAlign {
    /// World ← ARKit correction, carried from frame to frame.
    var correction = matrix_identity_float4x4
    /// Statistics of the last frame: points used, RMS distance to the surface (m).
    private(set) var lastUsed = 0
    private(set) var lastRMS: Float = 0
    private(set) var aligned = 0, skipped = 0, tooLittle = 0, skippedFar = 0, skippedRMS = 0

    /// Fewer surface points than this: nothing to align to yet (fused as ARKit says).
    static let minPoints = 150
    /// After aligning, a frame further than this from the surface (RMS) is skipped.
    static let maxRMS: Float = 0.02
    /// The largest correction step per frame (m / rad): a jump bigger than this isn't a small drift.
    static let maxStep: Float = 0.03, maxTurn: Float = 0.03
    /// The largest total correction (m / rad) away from ARKit.
    static let maxTotal: Float = 0.08, maxTotalTurn: Float = 0.052
    /// Share of the correction let go back towards ARKit each frame (before the fit pulls it where the surface says).
    static var leak: Float = 0
    /// Frames that don't fit / ask for too big a correction: skipped (true) or fused with the last good correction.
    static var skipUnfit = true

    /// Trilinear signed distance (metres) and gradient at `p`, or nil where the surface isn't well seen.
    private static func sample(_ gpu: TsdfGPU, _ sdf: UnsafeMutablePointer<Float>, _ w: UnsafeMutablePointer<UInt16>, _ p: SIMD3<Float>) -> (Float, SIMD3<Float>)? {
        let g = p / gpu.vs - SIMD3<Float>(gpu.origin) - 0.5
        let i0 = SIMD3<Int32>(g.rounded(.down))
        let f = g - SIMD3<Float>(i0)
        let nx = Int(gpu.dims.x), ny = Int(gpu.dims.y)
        guard all(i0 .>= 0), all(i0 &+ 1 .< gpu.dims) else { return nil }
        var v = [Float](repeating: 0, count: 8)
        for c in 0..<8 {
            let o = SIMD3<Int32>(Int32(c & 1), Int32((c >> 1) & 1), Int32(c >> 2))
            let q = i0 &+ o
            let i = (Int(q.z) * ny + Int(q.y)) * nx + Int(q.x)
            guard w[i] >= 3 else { return nil }
            v[c] = sdf[i]
        }
        func lerp(_ a: Float, _ b: Float, _ t: Float) -> Float { a + (b - a) * t }
        let x00 = lerp(v[0], v[1], f.x), x10 = lerp(v[2], v[3], f.x), x01 = lerp(v[4], v[5], f.x), x11 = lerp(v[6], v[7], f.x)
        let y0 = lerp(x00, x10, f.y), y1 = lerp(x01, x11, f.y)
        let s = lerp(y0, y1, f.z)
        guard abs(s) < 0.9 else { return nil }
        // Gradient (per voxel, band units) from the cell's differences, then metric.
        let gx = lerp(lerp(v[1] - v[0], v[3] - v[2], f.y), lerp(v[5] - v[4], v[7] - v[6], f.y), f.z)
        let gy = lerp(lerp(v[2] - v[0], v[3] - v[1], f.x), lerp(v[6] - v[4], v[7] - v[5], f.x), f.z)
        let gz = lerp(lerp(v[4] - v[0], v[5] - v[1], f.x), lerp(v[6] - v[2], v[7] - v[3], f.x), f.y)
        let grad = SIMD3(gx, gy, gz) * gpu.trunc / gpu.vs
        let gl = simd_length(grad)
        guard gl > 0.3 else { return nil }
        return (s * gpu.trunc, grad / gl)
    }

    /// Signed distance (m) from `p` to the fused surface, or nil where it isn't well seen (harness checks).
    static func distance(_ gpu: TsdfGPU, _ p: SIMD3<Float>) -> Float? { sample(gpu, gpu.sdfPointer, gpu.weightPointer, p)?.0 }

    /// Aligns one frame: `points` in camera space (metres, ARKit axes), `pose` camera → world (ARKit). Returns the
    /// corrected pose to fuse with, or nil to skip this frame. Call after the block's `finish()`.
    mutating func align(_ gpu: TsdfGPU, points: [SIMD3<Float>], pose: simd_float4x4) -> simd_float4x4? {
        let sdf = gpu.sdfPointer, w = gpu.weightPointer
        var C = correction
        if Self.leak > 0 {
            let q = simd_quatf(simd_float3x3(SIMD3(C.columns.0.x, C.columns.0.y, C.columns.0.z), SIMD3(C.columns.1.x, C.columns.1.y, C.columns.1.z),
                                             SIMD3(C.columns.2.x, C.columns.2.y, C.columns.2.z)))
            let q2 = simd_slerp(q, simd_quatf(ix: 0, iy: 0, iz: 0, r: 1), Self.leak)
            var L = simd_float4x4(q2)
            L.columns.3 = SIMD4(SIMD3(C.columns.3.x, C.columns.3.y, C.columns.3.z) * (1 - Self.leak), 1)
            C = L
        }
        let start = C
        var used = 0, rms: Float = 0
        for _ in 0..<6 {
            var H = [Float](repeating: 0, count: 36), b = [Float](repeating: 0, count: 6)
            used = 0; var sum: Float = 0
            let M = C * pose
            for pc in points {
                let p4 = M * SIMD4(pc, 1)
                let p = SIMD3(p4.x, p4.y, p4.z)
                guard let (r, n) = Self.sample(gpu, sdf, w, p) else { continue }
                // Huber weight (1 cm).
                let a = abs(r) < 0.01 ? Float(1) : 0.01 / abs(r)
                let J: [Float] = [n.x, n.y, n.z, (simd_cross(p, n)).x, (simd_cross(p, n)).y, (simd_cross(p, n)).z]
                for i in 0..<6 { b[i] -= a * J[i] * r; for j in 0..<6 { H[i * 6 + j] += a * J[i] * J[j] } }
                used += 1; sum += r * r
            }
            rms = used > 0 ? (sum / Float(used)).squareRoot() : 0
            guard used >= Self.minPoints else { break }
            // Only along directions the surface pins down (a flat wall fixes one direction, not the slide along it).
            guard let x = Self.solveConstrained(H, b) else { break }
            var t = SIMD3(x[0], x[1], x[2]), om = SIMD3(x[3], x[4], x[5])
            if simd_length(t) > Self.maxStep { t *= Self.maxStep / simd_length(t) }
            if simd_length(om) > Self.maxTurn { om *= Self.maxTurn / simd_length(om) }
            let ang = simd_length(om)
            var step = matrix_identity_float4x4
            if ang > 1e-7 { step = simd_float4x4(simd_quatf(angle: ang, axis: om / ang)) }
            step.columns.3 = SIMD4(t, 1)
            C = step * C
            if simd_length(t) < 1e-4, ang < 1e-4 { break }
        }
        lastUsed = used; lastRMS = rms
        if used < Self.minPoints { tooLittle += 1; return correction * pose }   // nothing to align to: as ARKit says
        // A correction this big isn't drift any more: skip the frame, keep the last correction.
        let ct = SIMD3(C.columns.3.x, C.columns.3.y, C.columns.3.z)
        let turn = acos(max(-1, min(1, (C.columns.0.x + C.columns.1.y + C.columns.2.z - 1) / 2)))
        if simd_length(ct) > Self.maxTotal || turn > Self.maxTotalTurn { skipped += 1; skippedFar += 1; correction = start; return Self.skipUnfit ? nil : start * pose }
        if rms > Self.maxRMS { skipped += 1; skippedRMS += 1; correction = start; return Self.skipUnfit ? nil : start * pose }                       // doesn't fit the model: skip
        correction = C
        aligned += 1
        return C * pose
    }

    /// Solves H x = b only in the eigen-directions of H that are well constrained (eigenvalue at least 5% of the
    /// largest); the others stay 0.
    static func solveConstrained(_ H: [Float], _ b: [Float]) -> [Float]? {
        let n = 6
        var a = H.map(Double.init)
        var v = [Double](repeating: 0, count: 36)
        for i in 0..<n { v[i * n + i] = 1 }
        // Jacobi rotations.
        for _ in 0..<60 {
            var off = 0.0
            for i in 0..<n { for j in (i + 1)..<n { off += a[i * n + j] * a[i * n + j] } }
            if off < 1e-18 { break }
            for p in 0..<n { for q in (p + 1)..<n where abs(a[p * n + q]) > 1e-15 {
                let theta = (a[q * n + q] - a[p * n + p]) / (2 * a[p * n + q])
                let t = (theta >= 0 ? 1.0 : -1.0) / (abs(theta) + (theta * theta + 1).squareRoot())
                let c = 1 / (t * t + 1).squareRoot(), s = t * c
                for k in 0..<n {
                    let akp = a[k * n + p], akq = a[k * n + q]
                    a[k * n + p] = c * akp - s * akq; a[k * n + q] = s * akp + c * akq
                }
                for k in 0..<n {
                    let apk = a[p * n + k], aqk = a[q * n + k]
                    a[p * n + k] = c * apk - s * aqk; a[q * n + k] = s * apk + c * aqk
                }
                for k in 0..<n {
                    let vkp = v[k * n + p], vkq = v[k * n + q]
                    v[k * n + p] = c * vkp - s * vkq; v[k * n + q] = s * vkp + c * vkq
                }
            } }
        }
        let ev = (0..<n).map { a[$0 * n + $0] }
        guard let maxEv = ev.max(), maxEv > 1e-9 else { return nil }
        var x = [Double](repeating: 0, count: n)
        for k in 0..<n where ev[k] >= 0.05 * maxEv {
            var proj = 0.0
            for i in 0..<n { proj += v[i * n + k] * Double(b[i]) }
            let coef = proj / ev[k]
            for i in 0..<n { x[i] += coef * v[i * n + k] }
        }
        return x.map(Float.init)
    }

    /// Solves the 6×6 system H x = b (Gaussian elimination with pivoting), or nil when singular.
    static func solve6(_ H: [Float], _ b: [Float]) -> [Float]? {
        var a = H.map(Double.init), r = b.map(Double.init)
        let n = 6
        for c in 0..<n {
            var p = c
            for i in (c + 1)..<n where abs(a[i * n + c]) > abs(a[p * n + c]) { p = i }
            guard abs(a[p * n + c]) > 1e-12 else { return nil }
            if p != c { for j in 0..<n { a.swapAt(c * n + j, p * n + j) }; r.swapAt(c, p) }
            for i in (c + 1)..<n {
                let f = a[i * n + c] / a[c * n + c]
                for j in c..<n { a[i * n + j] -= f * a[c * n + j] }
                r[i] -= f * r[c]
            }
        }
        var x = [Double](repeating: 0, count: n)
        for i in stride(from: n - 1, through: 0, by: -1) {
            var s = r[i]
            for j in (i + 1)..<n { s -= a[i * n + j] * x[j] }
            x[i] = s / a[i * n + i]
        }
        return x.map(Float.init)
    }
}
