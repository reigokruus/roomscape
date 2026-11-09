import simd

/// Where the phone is, from the LiDAR depth alone (no ARKit): each depth frame is matched to the previous good one
/// with point-to-plane ICP (KinectFusion's projective matching), coarse to fine, starting from the gyro's rotation
/// and the last motion. A frame that doesn't match well is skipped (nothing is fused from it) and the next frames are
/// matched against the last good one again, so a slip doesn't spoil the scan.
///
/// Camera coordinates: x right, y down, z forward. `pose` maps the depth camera's coordinates to the world's (y up).
final class UltraTracker {
    private(set) var pose = matrix_identity_float4x4
    private(set) var started = false
    private var velocity = SIMD3<Float>(repeating: 0)

    // The last good frame, in world coordinates, at half resolution.
    private var refPoints: [SIMD3<Float>] = []
    private var refNormals: [SIMD3<Float>] = []
    private var refW = 0, refH = 0
    private var refK = SIMD4<Float>(repeating: 0) // fx, fy, cx, cy at half resolution
    private var refPose = matrix_identity_float4x4

    struct Result { var ok: Bool; var inliers: Float; var rms: Float }

    /// The first frame: the world's y axis goes up, against gravity (given in camera coordinates).
    func begin(gravity g: SIMD3<Float>) {
        let up = -simd_normalize(g)
        var back = SIMD3<Float>(0, 0, -1) // camera looks along +z; world z points back, as in ARKit
        back -= up * simd_dot(back, up)
        if simd_length(back) < 1e-3 { back = SIMD3(0, 1, 0) - up * up.y }
        back = simd_normalize(back)
        let right = simd_normalize(simd_cross(up, back))
        // Rows: the world axes in camera coordinates, so world = R * camera.
        let r = simd_float3x3(rows: [right, up, back])
        pose = simd_float4x4(SIMD4(r.columns.0, 0), SIMD4(r.columns.1, 0), SIMD4(r.columns.2, 0), SIMD4(0, 0, 0, 1))
        started = false
    }

    /// Tracks one depth frame (metres, 0 = none), with the gyro's rotation since the last frame (camera coordinates,
    /// current to previous), if known.
    func track(_ f: UltraCapture.DepthFrame, gyro: simd_float3x3?) -> Result {
        let (pts, nrm, w, h) = Self.maps(f)
        let k = SIMD4(f.fx / 2, f.fy / 2, f.cx / 2, f.cy / 2)
        if !started {
            started = true
            setReference(pts, nrm, w, h, k, pose)
            return Result(ok: true, inliers: 1, rms: 0)
        }
        // Prediction: the gyro's turn and the last movement.
        var t = pose
        if let gyro {
            let r = simd_float3x3(t.columns.0.ux3, t.columns.1.ux3, t.columns.2.ux3) * gyro
            t.columns.0 = SIMD4(r.columns.0, 0); t.columns.1 = SIMD4(r.columns.1, 0); t.columns.2 = SIMD4(r.columns.2, 0)
        }
        t.columns.3 += SIMD4(velocity, 0)
        let predicted = t
        var last = Result(ok: false, inliers: 0, rms: 1)
        for (step, iterations, gate) in [(4, 6, Float(0.12)), (2, 5, Float(0.06)), (1, 3, Float(0.04))] {
            for _ in 0..<iterations {
                guard let (delta, res) = solve(pts, nrm, w, h, t, step: step, gate: gate) else { return Result(ok: false, inliers: 0, rms: 1) }
                t = delta * t
                last = res
                if simd_length(SIMD3(delta.columns.3.x, delta.columns.3.y, delta.columns.3.z)) < 1e-4 { break }
            }
        }
        let moved = simd_distance(SIMD3(t.columns.3.x, t.columns.3.y, t.columns.3.z), SIMD3(pose.columns.3.x, pose.columns.3.y, pose.columns.3.z))
        let ok = last.inliers > 0.45 && last.rms < 0.02 && moved < 0.25
        last.ok = ok
        guard ok else { return last }
        velocity = (SIMD3(t.columns.3.x, t.columns.3.y, t.columns.3.z) - SIMD3(pose.columns.3.x, pose.columns.3.y, pose.columns.3.z)) * 0.5
        _ = predicted
        pose = t
        setReference(pts, nrm, w, h, k, pose)
        return last
    }

    /// The depth frame's points (camera coordinates) for fusing, every `step` pixels.
    static func points(_ f: UltraCapture.DepthFrame, step: Int) -> [SIMD3<Float>] {
        var out: [SIMD3<Float>] = []
        out.reserveCapacity(f.width * f.height / (step * step))
        for y in stride(from: 0, to: f.height, by: step) {
            for x in stride(from: 0, to: f.width, by: step) {
                let z = f.depth[y * f.width + x]
                guard z > 0.2, z < 5 else { continue }
                out.append(SIMD3((Float(x) - f.cx) * z / f.fx, (Float(y) - f.cy) * z / f.fy, z))
            }
        }
        return out
    }

    // MARK: Internals

    /// Half-resolution point and normal maps (camera coordinates; zero = none).
    private static func maps(_ f: UltraCapture.DepthFrame) -> ([SIMD3<Float>], [SIMD3<Float>], Int, Int) {
        let w = f.width / 2, h = f.height / 2
        var p = [SIMD3<Float>](repeating: .zero, count: w * h)
        for y in 0..<h {
            for x in 0..<w {
                let z = f.depth[(y * 2) * f.width + x * 2]
                guard z > 0.2, z < 5, z.isFinite else { continue }
                let u = Float(x * 2), v = Float(y * 2)
                p[y * w + x] = SIMD3((u - f.cx) * z / f.fx, (v - f.cy) * z / f.fy, z)
            }
        }
        var n = [SIMD3<Float>](repeating: .zero, count: w * h)
        for y in 0..<(h - 1) {
            for x in 0..<(w - 1) {
                let a = p[y * w + x], b = p[y * w + x + 1], c = p[(y + 1) * w + x]
                guard a.z > 0, b.z > 0, c.z > 0, abs(b.z - a.z) < 0.05, abs(c.z - a.z) < 0.05 else { continue }
                var nn = simd_cross(b - a, c - a)
                let l = simd_length(nn)
                guard l > 1e-9 else { continue }
                nn /= l
                if simd_dot(nn, a) > 0 { nn = -nn } // facing the camera
                n[y * w + x] = nn
            }
        }
        return (p, n, w, h)
    }

    private func setReference(_ pts: [SIMD3<Float>], _ nrm: [SIMD3<Float>], _ w: Int, _ h: Int, _ k: SIMD4<Float>, _ pose: simd_float4x4) {
        let r = simd_float3x3(pose.columns.0.ux3, pose.columns.1.ux3, pose.columns.2.ux3)
        let t = pose.columns.3.ux3
        refPoints = pts.map { $0.z > 0 ? r * $0 + t : .zero }
        refNormals = nrm.map { simd_length_squared($0) > 0 ? r * $0 : .zero }
        refW = w; refH = h; refK = k; refPose = pose
    }

    /// One Gauss-Newton step of point-to-plane ICP: the small world-space motion that best fits this frame (at
    /// `pose`) onto the reference, and how good the fit is.
    private func solve(_ pts: [SIMD3<Float>], _ nrm: [SIMD3<Float>], _ w: Int, _ h: Int, _ pose: simd_float4x4,
                       step: Int, gate: Float) -> (simd_float4x4, Result)? {
        let r = simd_float3x3(pose.columns.0.ux3, pose.columns.1.ux3, pose.columns.2.ux3)
        let t = pose.columns.3.ux3
        let refInv = refPose.inverse
        var a = [Double](repeating: 0, count: 36), b = [Double](repeating: 0, count: 6)
        var used = 0, tried = 0
        var sq: Double = 0
        for y in stride(from: 0, to: h, by: step) {
            for x in stride(from: 0, to: w, by: step) {
                let pc = pts[y * w + x], nc = nrm[y * w + x]
                guard pc.z > 0, simd_length_squared(nc) > 0 else { continue }
                tried += 1
                let pw = r * pc + t
                let q = refInv * SIMD4(pw, 1)
                guard q.z > 0.1 else { continue }
                let u = Int((q.x * refK.x / q.z + refK.z).rounded()), v = Int((q.y * refK.y / q.z + refK.w).rounded())
                guard u >= 0, v >= 0, u < refW, v < refH else { continue }
                let rp = refPoints[v * refW + u], rn = refNormals[v * refW + u]
                guard simd_length_squared(rn) > 0 else { continue }
                let d = pw - rp
                guard simd_length(d) < gate, simd_dot(r * nc, rn) > 0.8 else { continue }
                let e = Double(simd_dot(rn, d))
                let c = simd_cross(pw, rn)
                let j = [Double(c.x), Double(c.y), Double(c.z), Double(rn.x), Double(rn.y), Double(rn.z)]
                for i in 0..<6 {
                    b[i] -= j[i] * e
                    for k in i..<6 { a[i * 6 + k] += j[i] * j[k] }
                }
                sq += e * e
                used += 1
            }
        }
        guard used > 60 else { return nil }
        for i in 0..<6 { for k in 0..<i { a[i * 6 + k] = a[k * 6 + i] } }
        guard let x = Self.solve6(a, b) else { return nil }
        let w3 = SIMD3(Float(x[0]), Float(x[1]), Float(x[2]))
        let angle = simd_length(w3)
        let rot = angle > 1e-9 ? simd_float3x3(simd_quatf(angle: angle, axis: w3 / angle)) : matrix_identity_float3x3
        let delta = simd_float4x4(SIMD4(rot.columns.0, 0), SIMD4(rot.columns.1, 0), SIMD4(rot.columns.2, 0),
                                  SIMD4(Float(x[3]), Float(x[4]), Float(x[5]), 1))
        return (delta, Result(ok: true, inliers: Float(used) / Float(max(1, tried)), rms: Float((sq / Double(used)).squareRoot())))
    }

    /// Solves the 6×6 system a·x = b (Gaussian elimination with pivoting).
    private static func solve6(_ a0: [Double], _ b0: [Double]) -> [Double]? {
        var a = a0, b = b0
        for c in 0..<6 {
            var p = c
            for r in (c + 1)..<6 where abs(a[r * 6 + c]) > abs(a[p * 6 + c]) { p = r }
            guard abs(a[p * 6 + c]) > 1e-12 else { return nil }
            if p != c { for k in 0..<6 { a.swapAt(c * 6 + k, p * 6 + k) }; b.swapAt(c, p) }
            for r in (c + 1)..<6 {
                let f = a[r * 6 + c] / a[c * 6 + c]
                for k in c..<6 { a[r * 6 + k] -= f * a[c * 6 + k] }
                b[r] -= f * b[c]
            }
        }
        var x = [Double](repeating: 0, count: 6)
        for r in stride(from: 5, through: 0, by: -1) {
            var s = b[r]
            for k in (r + 1)..<6 { s -= a[r * 6 + k] * x[k] }
            x[r] = s / a[r * 6 + r]
        }
        return x
    }
}

extension SIMD4 where Scalar == Float {
    var ux3: SIMD3<Float> { SIMD3(x, y, z) }
}
