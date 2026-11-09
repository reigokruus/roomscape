import simd

/// Where the phone is, without ARKit: RGB-D odometry. Each frame is aligned to the current keyframe with two kinds of
/// evidence at once (coarse to fine, Gauss-Newton, robust weights):
/// - depth: point-to-plane ICP (KinectFusion's projective matching), which pins down the directions the surfaces face;
/// - brightness: the camera picture registered to the depth (direct photometric alignment), which sees sliding along
///   a textured wall or floor that depth alone can't.
/// It starts from the gyro's rotation. A frame that doesn't fit is skipped (nothing is fused from it); after a few
/// such frames the tracker tries to find its place again against the stored keyframes. Plain, textureless walls
/// give neither kind of evidence for sliding along them; there it holds still.
///
/// Camera coordinates: x right, y down, z forward. `pose` maps the depth camera's coordinates to the world's (y up).
/// Plain Swift + simd, so the core can move to the Android app (Java) later and runs in the Mac test harness.
final class UltraTracker {
    static var debug = false
    private(set) var pose = matrix_identity_float4x4
    private(set) var started = false
    private(set) var relocalised = 0

    struct Result { var ok: Bool; var inliers: Float; var rms: Float; var photo: Float = 0 }

    /// A keyframe: half-resolution maps in world coordinates, and its brightness.
    private final class Keyframe {
        let pose: simd_float4x4
        let points: [SIMD3<Float>], normals: [SIMD3<Float>]
        let gray: [Float]
        let w: Int, h: Int
        let k: SIMD4<Float>
        init(pose: simd_float4x4, points: [SIMD3<Float>], normals: [SIMD3<Float>], gray: [Float], w: Int, h: Int, k: SIMD4<Float>) {
            self.pose = pose; self.points = points; self.normals = normals; self.gray = gray; self.w = w; self.h = h; self.k = k
        }
    }
    private var reference: Keyframe?
    private var keyframes: [Keyframe] = []
    private var lost = 0
    private var relocCursor = 0

    /// The first frame: the world's y axis goes up, against gravity (given in camera coordinates).
    func begin(gravity g: SIMD3<Float>) {
        let up = -simd_normalize(g)
        var back = SIMD3<Float>(0, 0, -1)
        back -= up * simd_dot(back, up)
        if simd_length(back) < 1e-3 { back = SIMD3(0, 1, 0) - up * up.y }
        back = simd_normalize(back)
        let right = simd_normalize(simd_cross(up, back))
        let r = simd_float3x3(rows: [right, up, back])
        pose = simd_float4x4(SIMD4(r.columns.0, 0), SIMD4(r.columns.1, 0), SIMD4(r.columns.2, 0), SIMD4(0, 0, 0, 1))
        started = false
        reference = nil
        keyframes = []
    }

    /// Tracks one depth frame (metres, 0 = none) with its brightness image (0…1 per depth pixel, or nil) and the
    /// gyro's rotation since the last frame (camera coordinates, current to previous), if known.
    func track(_ f: UltraCapture.DepthFrame, intensity: [Float]? = nil, gyro: simd_float3x3?) -> Result {
        let cur = Self.current(f, intensity)
        if !started {
            started = true
            makeKeyframe(cur, pose)
            return Result(ok: true, inliers: 1, rms: 0)
        }
        guard let ref = reference else { return Result(ok: false, inliers: 0, rms: 1) }
        var start = pose
        if let gyro {
            let r = simd_float3x3(start.columns.0.ux3, start.columns.1.ux3, start.columns.2.ux3) * gyro
            start.columns.0 = SIMD4(r.columns.0, 0); start.columns.1 = SIMD4(r.columns.1, 0); start.columns.2 = SIMD4(r.columns.2, 0)
        }
        var (t, res) = align(cur, to: ref, from: start)
        if !res.ok {
            lost += 1
            // Lost for a while: try to find the place again against stored keyframes (a few per frame).
            if lost >= 3, !keyframes.isEmpty {
                for _ in 0..<min(4, keyframes.count) {
                    let kf = keyframes[relocCursor % keyframes.count]
                    relocCursor += 1
                    let (rt, rr) = align(cur, to: kf, from: kf.pose)
                    if rr.ok {
                        t = rt; res = rr; reference = kf; relocalised += 1
                        break
                    }
                }
            }
            guard res.ok else { return res }
        }
        lost = 0
        pose = t
        // A new keyframe once the view has moved on (or the overlap is getting thin).
        let moved = simd_distance(t.columns.3.ux3, reference!.pose.columns.3.ux3)
        let turned = acos(min(1, max(-1, simd_dot(t.columns.2.ux3, reference!.pose.columns.2.ux3)))) * 180 / .pi
        if moved > 0.15 || turned > 10 || res.inliers < 0.55 { makeKeyframe(cur, t) }
        return res
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

    // MARK: Frames

    /// The current frame at half resolution: points and normals (camera coordinates), and a brightness pyramid.
    private struct Current {
        var points: [SIMD3<Float>], normals: [SIMD3<Float>]
        var levels: [[Float]] // brightness: half, quarter, eighth resolution
        var w: Int, h: Int
        var k: SIMD4<Float>
        var hasGray: Bool
    }

    private static func current(_ f: UltraCapture.DepthFrame, _ intensity: [Float]?) -> Current {
        let w = f.width / 2, h = f.height / 2
        var p = [SIMD3<Float>](repeating: .zero, count: w * h)
        var g = [Float](repeating: 0, count: w * h)
        for y in 0..<h {
            for x in 0..<w {
                let i0 = (y * 2) * f.width + x * 2
                if let intensity {
                    g[y * w + x] = (intensity[i0] + intensity[i0 + 1] + intensity[i0 + f.width] + intensity[i0 + f.width + 1]) / 4
                }
                let z = f.depth[i0]
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
                if simd_dot(nn, a) > 0 { nn = -nn }
                n[y * w + x] = nn
            }
        }
        var levels = [g]
        var lw = w, lh = h
        for _ in 0..<2 {
            let prev = levels.last!, nw = lw / 2, nh = lh / 2
            var next = [Float](repeating: 0, count: nw * nh)
            for y in 0..<nh { for x in 0..<nw {
                next[y * nw + x] = (prev[(2 * y) * lw + 2 * x] + prev[(2 * y) * lw + 2 * x + 1] + prev[(2 * y + 1) * lw + 2 * x] + prev[(2 * y + 1) * lw + 2 * x + 1]) / 4
            } }
            levels.append(next); lw = nw; lh = nh
        }
        return Current(points: p, normals: n, levels: levels, w: w, h: h, k: SIMD4(f.fx / 2, f.fy / 2, f.cx / 2, f.cy / 2), hasGray: intensity != nil)
    }

    private func makeKeyframe(_ c: Current, _ pose: simd_float4x4) {
        let r = simd_float3x3(pose.columns.0.ux3, pose.columns.1.ux3, pose.columns.2.ux3), t = pose.columns.3.ux3
        let kf = Keyframe(pose: pose, points: c.points.map { $0.z > 0 ? r * $0 + t : .zero },
                          normals: c.normals.map { simd_length_squared($0) > 0 ? r * $0 : .zero },
                          gray: c.hasGray ? c.levels[0] : [], w: c.w, h: c.h, k: c.k)
        reference = kf
        // Kept for relocalising: spread out (at least 30 cm or 25° from the last kept one), up to 60.
        if let last = keyframes.last {
            let moved = simd_distance(pose.columns.3.ux3, last.pose.columns.3.ux3)
            let turned = acos(min(1, max(-1, simd_dot(pose.columns.2.ux3, last.pose.columns.2.ux3)))) * 180 / .pi
            guard moved > 0.3 || turned > 25 else { return }
        }
        keyframes.append(kf)
        if keyframes.count > 60 { keyframes.removeFirst() }
    }

    // MARK: Alignment

    /// Aligns the frame to a keyframe starting from `start`: depth and brightness together, coarse to fine.
    private func align(_ c: Current, to ref: Keyframe, from start: simd_float4x4) -> (simd_float4x4, Result) {
        var t = start
        var last = Result(ok: false, inliers: 0, rms: 1)
        for (level, iterations, gate) in [(2, 6, Float(0.12)), (1, 5, Float(0.06)), (0, 4, Float(0.04))] {
            for _ in 0..<iterations {
                guard let (delta, res) = step(c, ref, t, level: level, gate: gate) else { return (t, Result(ok: false, inliers: 0, rms: 1)) }
                t = delta * t
                last = res
                if simd_length(delta.columns.3.ux3) < 1e-4 { break }
            }
        }
        let moved = simd_distance(t.columns.3.ux3, start.columns.3.ux3)
        last.ok = last.inliers > 0.4 && last.rms < 0.02 && moved < 0.3
        return (t, last)
    }

    /// One Gauss-Newton step over both kinds of evidence; the small world-space motion that fits best.
    private func step(_ c: Current, _ ref: Keyframe, _ pose: simd_float4x4, level: Int, gate: Float) -> (simd_float4x4, Result)? {
        let stride = 1 << level
        let r = simd_float3x3(pose.columns.0.ux3, pose.columns.1.ux3, pose.columns.2.ux3), tt = pose.columns.3.ux3
        let refInv = ref.pose.inverse
        var a = [Double](repeating: 0, count: 36), b = [Double](repeating: 0, count: 6)
        func add(_ j: [Double], _ e: Double, _ w: Double) {
            for i in 0..<6 {
                b[i] -= j[i] * e * w
                for k in i..<6 { a[i * 6 + k] += j[i] * j[k] * w }
            }
        }
        // Depth: this frame's points onto the keyframe's surfaces (point-to-plane), σ ≈ 1 cm, Huber at 2σ.
        var used = 0, tried = 0
        var sq: Double = 0
        for y in Swift.stride(from: 0, to: c.h, by: stride) {
            for x in Swift.stride(from: 0, to: c.w, by: stride) {
                let pc = c.points[y * c.w + x], nc = c.normals[y * c.w + x]
                guard pc.z > 0, simd_length_squared(nc) > 0 else { continue }
                tried += 1
                let pw = r * pc + tt
                let q = refInv * SIMD4(pw, 1)
                guard q.z > 0.1 else { continue }
                let u = Int((q.x * ref.k.x / q.z + ref.k.z).rounded()), v = Int((q.y * ref.k.y / q.z + ref.k.w).rounded())
                guard u >= 0, v >= 0, u < ref.w, v < ref.h else { continue }
                let rp = ref.points[v * ref.w + u], rn = ref.normals[v * ref.w + u]
                guard simd_length_squared(rn) > 0 else { continue }
                let d = pw - rp
                guard simd_length(d) < gate, simd_dot(r * nc, rn) > 0.8 else { continue }
                let e = Double(simd_dot(rn, d))
                let cr = simd_cross(pw, rn)
                let sigma = 0.01, huber = abs(e) < 2 * sigma ? 1.0 : 2 * sigma / abs(e)
                add([Double(cr.x), Double(cr.y), Double(cr.z), Double(rn.x), Double(rn.y), Double(rn.z)], e, huber / (sigma * sigma))
                sq += e * e
                used += 1
            }
        }
        // Brightness: the keyframe's textured points, seen in this frame's picture, σ ≈ 0.03, Huber at 2σ.
        // e = I(π(p)) − I_ref with p = Rᵀ(P − t); for the world motion (ω, τ): ∂p/∂ω = Rᵀ[P]×, ∂p/∂τ = −Rᵀ,
        // so with d = R·(∂π/∂p)ᵀ∇I: J_ω = d × P, J_τ = −d.
        var photoUsed = 0, photoTried = 0
        if c.hasGray, !ref.gray.isEmpty {
            let img = c.levels[level]
            let lw = c.w >> level, lh = c.h >> level, s = Float(1 << level)
            let k = c.k / SIMD4(s, s, s, s)
            let rt = r.transpose
            var samples: [(j: [Double], e: Float)] = []
            for y in Swift.stride(from: 1, to: ref.h - 1, by: stride) {
                for x in Swift.stride(from: 1, to: ref.w - 1, by: stride) {
                    let i = y * ref.w + x
                    let pw = ref.points[i]
                    guard pw != .zero else { continue }
                    let gx = ref.gray[i + 1] - ref.gray[i - 1], gy = ref.gray[i + ref.w] - ref.gray[i - ref.w]
                    guard gx * gx + gy * gy > 0.0004 else { continue } // only where there's texture
                    photoTried += 1
                    let pc = rt * (pw - tt)
                    guard pc.z > 0.1 else { continue }
                    let u = k.x * pc.x / pc.z + k.z, v = k.y * pc.y / pc.z + k.w
                    guard u >= 1, v >= 1, u < Float(lw - 2), v < Float(lh - 2) else { continue }
                    let ic = Self.sample(img, lw, u, v)
                    let dx = (Self.sample(img, lw, u + 1, v) - Self.sample(img, lw, u - 1, v)) / 2
                    let dy = (Self.sample(img, lw, u, v + 1) - Self.sample(img, lw, u, v - 1)) / 2
                    let iz = 1 / pc.z
                    let dp = SIMD3(dx * k.x * iz, dy * k.y * iz, -(dx * k.x * pc.x + dy * k.y * pc.y) * iz * iz)
                    let d = r * dp
                    let jw = simd_cross(d, pw), jt = -d
                    samples.append(([Double(jw.x), Double(jw.y), Double(jw.z), Double(jt.x), Double(jt.y), Double(jt.z)], ic - ref.gray[i]))
                }
            }
            if samples.count > 40 {
                // The brightness offset between the two (exposure change): the median difference.
                let offset = samples.map(\.e).sorted()[samples.count / 2]
                for s in samples {
                    let e = Double(s.e - offset)
                    let sigma = 0.03, huber = abs(e) < 2 * sigma ? 1.0 : 2 * sigma / abs(e)
                    add(s.j, e, huber / (sigma * sigma) * 0.5)
                    if abs(e) < 2 * sigma { photoUsed += 1 }
                }
            }
        }
        if UltraTracker.debug { print("  level \(level): depth \(used)/\(tried), brightness \(photoUsed)/\(photoTried)") }
        guard used + photoUsed > 60 else { return nil }
        for i in 0..<6 { for k in 0..<i { a[i * 6 + k] = a[k * 6 + i] } }
        let damp = (a[0] + a[7] + a[14] + a[21] + a[28] + a[35]) / 6 * 1e-3
        for i in 0..<6 { a[i * 6 + i] += damp }
        guard let x = Self.solve6(a, b) else { return nil }
        let w3 = SIMD3(Float(x[0]), Float(x[1]), Float(x[2]))
        let angle = simd_length(w3)
        let rot = angle > 1e-9 ? simd_float3x3(simd_quatf(angle: angle, axis: w3 / angle)) : matrix_identity_float3x3
        let delta = simd_float4x4(SIMD4(rot.columns.0, 0), SIMD4(rot.columns.1, 0), SIMD4(rot.columns.2, 0),
                                  SIMD4(Float(x[3]), Float(x[4]), Float(x[5]), 1))
        let inl = used > 60 ? Float(used) / Float(max(1, tried)) : Float(photoUsed) / Float(max(1, photoTried))
        let rms = used > 0 ? Float((sq / Double(used)).squareRoot()) : 0.01
        return (delta, Result(ok: true, inliers: inl, rms: rms, photo: Float(photoUsed) / Float(max(1, photoTried))))
    }

    @inline(__always) private static func sample(_ img: [Float], _ w: Int, _ u: Float, _ v: Float) -> Float {
        let x0 = Int(u), y0 = Int(v), fx = u - Float(x0), fy = v - Float(y0)
        let i = y0 * w + x0
        return (img[i] * (1 - fx) + img[i + 1] * fx) * (1 - fy) + (img[i + w] * (1 - fx) + img[i + w + 1] * fx) * fy
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
        for r in Swift.stride(from: 5, through: 0, by: -1) {
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
