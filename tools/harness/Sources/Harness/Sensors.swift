import Foundation
import simd

/// A virtual phone: a hand-held walking path with shake, a LiDAR-like depth sensor, a camera, a gyro and an
/// ARKit-like pose with drift. Camera coordinates: x right, y down, z forward; poses map camera to world (y up).
struct Profile {
    var name: String
    /// Depth map size and field of view (the wide camera's, which depth is aligned to).
    var depthWidth = 256, depthHeight = 192
    var hfovDegrees: Float = 71.4
    /// Depth noise: σ = a + b·d² metres (calibrated from real scans, see `Calibration`).
    /// Calibrated 9 Nov from rk's iPhone scan (Scan_2026-11-09_14-20-34): 0.4 mm at 1–4 m, ~4 mm at 4.5–5 m, 9.9%
    /// dropouts. ARKit smooths its depth, so the real error is mostly a slow warp across the frame, not pixel noise:
    /// `warp` (fraction of depth, per frame) stands in for it (published LiDAR accuracy ~1 cm at 2–3 m).
    var noiseA: Float = 0.0005, noiseB: Float = 0.00012
    var warp: Float = 0.004
    var range: ClosedRange<Float> = 0.25...5
    /// Extra dropouts on plain, textureless surfaces (depth from motion has nothing to match there).
    var plainDropout: Float = 0
    /// Depth only once the phone has moved this far (depth from motion), metres.
    var needsMotion: Float = 0
    /// Pose drift (ARKit / ARCore-like): fraction of the distance walked, and yaw degrees per metre.
    var drift: Float = 0.01, yawDriftPerMetre: Float = 0.4

    static let iPhone15Pro = Profile(name: "iPhone 15 Pro LiDAR")
    /// Calibrated from rk's S24 scan (Room 9 Nov 14:19): σ = 1.3 mm + 0.52 mm·d², 6.9% dropouts, 3.6% depth edges.
    static let s24 = Profile(name: "S24 depth from motion", depthWidth: 160, depthHeight: 120, hfovDegrees: 66,
                             noiseA: 0.0013, noiseB: 0.00052, warp: 0.012, range: 0.3...4, plainDropout: 0.35, needsMotion: 0.15,
                             drift: 0.02, yawDriftPerMetre: 0.8)

    var fx: Float { Float(depthWidth) / 2 / tan(hfovDegrees * .pi / 360) }
}

struct Frame {
    var time: TimeInterval
    /// Ground truth and the ARKit-like (drifting) pose.
    var truth: simd_float4x4
    var arkit: simd_float4x4
    var depth: UltraCapture.DepthFrame
    /// Brightness 0…1 at each depth pixel (the camera picture registered to the depth, as the app does).
    var intensity: [Float]
    /// 0 low, 1 medium, 2 high, like ARConfidenceLevel.
    var confidence: [UInt8]
    /// The gyro's rotation since the previous frame (camera coordinates, current to previous), with bias and noise.
    var gyro: simd_float3x3?
    var walked: Float
}

/// Deterministic random numbers, so runs repeat.
struct Rand {
    var s: UInt64
    mutating func next() -> Float { s = s &* 6364136223846793005 &+ 1442695040888963407; return Float(s >> 40) / Float(1 << 24) }
    mutating func gauss() -> Float { let u = max(1e-7, next()), v = next(); return sqrt(-2 * log(u)) * cos(2 * .pi * v) }
}

enum Sensors {
    /// The path: a slow loop around the room's middle at eye height, looking outwards and sweeping left / right and
    /// up / down, with walking bob and hand tremor. One loop, back to the start.
    static func pose(_ room: Room, _ t: Float, duration: Float) -> simd_float4x4 {
        let c = SIMD3(room.size.x / 2, 1.4, room.size.z / 2)
        let rx = max(0.25, room.size.x / 2 - 0.6), rz = max(0.25, room.size.z / 2 - 0.6)
        let a = 2 * Float.pi * t / duration
        var p = c + SIMD3(rx * cos(a), 0, rz * sin(a))
        // Walking bob and tremor.
        p += SIMD3(0.003 * sin(t * 51), 0.01 * sin(t * 11.3) + 0.002 * sin(t * 47), 0.003 * sin(t * 43))
        let yaw = a + 0.7 * sin(t * 0.9) + 0.004 * sin(t * 44), pitch = -0.35 + 0.42 * sin(t * 0.55) + 0.004 * sin(t * 39) // down to ~45°, as people look at the floor
        // Look outwards (away from the middle), turned by yaw / pitch.
        let forward = simd_normalize(SIMD3(cos(yaw) * cos(pitch), sin(pitch), sin(yaw) * cos(pitch)))
        let right = simd_normalize(simd_cross(forward, SIMD3(0, 1, 0)))
        let down = simd_cross(forward, right)
        return simd_float4x4(SIMD4(right, 0), SIMD4(down, 0), SIMD4(forward, 0), SIMD4(p, 1))
    }

    /// Frames at `fps` over `duration` seconds.
    static func frames(_ room: Room, _ profile: Profile, duration: Float = 40, fps: Float = 15, seed: UInt64 = 1) -> [Frame] {
        var rng = Rand(s: seed)
        var out: [Frame] = []
        var walked: Float = 0
        var last: simd_float4x4?
        let gyroBias = SIMD3<Float>(0.004, -0.003, 0.002) // rad/s
        var driftDir = simd_normalize(SIMD3<Float>(rng.gauss(), 0.2 * rng.gauss(), rng.gauss()))
        let n = Int(duration * fps)
        for i in 0..<n {
            let t = Float(i) / fps
            let truth = pose(room, t, duration: duration)
            var gyro: simd_float3x3?
            if let last {
                walked += simd_distance(truth.columns.3.ux3, last.columns.3.ux3)
                let rel = simd_float3x3(last.columns.0.ux3, last.columns.1.ux3, last.columns.2.ux3).transpose
                    * simd_float3x3(truth.columns.0.ux3, truth.columns.1.ux3, truth.columns.2.ux3) // current camera to previous
                let dt = 1 / fps
                let err = (gyroBias + SIMD3(rng.gauss(), rng.gauss(), rng.gauss()) * 0.003 / sqrt(dt)) * dt
                gyro = rel * simd_float3x3(simd_quatf(angle: simd_length(err), axis: simd_length(err) > 0 ? simd_normalize(err) : SIMD3(0, 1, 0)))
            }
            last = truth
            // ARKit-like pose: drift growing with the distance walked, a slow yaw drift, small jitter.
            driftDir = simd_normalize(driftDir + SIMD3(rng.gauss(), 0, rng.gauss()) * 0.01)
            let yawDrift = profile.yawDriftPerMetre * walked * .pi / 180
            var arkit = simd_float4x4(simd_quatf(angle: yawDrift, axis: SIMD3(0, 1, 0))) * truth
            arkit.columns.3 += SIMD4(driftDir * profile.drift * walked + SIMD3(rng.gauss(), rng.gauss(), rng.gauss()) * 0.002, 0)
            let (depth, conf, gray) = render(room, profile, truth, time: Double(t), moved: walked, rng: &rng)
            out.append(Frame(time: Double(t), truth: truth, arkit: arkit, depth: depth, intensity: gray, confidence: conf, gyro: gyro, walked: walked))
        }
        return out
    }

    /// One depth frame: noise growing with distance, dropouts (dark, glossy, glass, grazing, out of range, plain
    /// surfaces for depth from motion), flying pixels at depth edges, and a confidence map.
    static func render(_ room: Room, _ p: Profile, _ pose: simd_float4x4, time: TimeInterval, moved: Float, rng: inout Rand)
        -> (UltraCapture.DepthFrame, [UInt8], [Float]) {
        let w = p.depthWidth, h = p.depthHeight, f = p.fx, cx = Float(w) / 2, cy = Float(h) / 2
        let r = simd_float3x3(pose.columns.0.ux3, pose.columns.1.ux3, pose.columns.2.ux3), o = pose.columns.3.ux3
        var truth = [Float](repeating: 0, count: w * h)
        var conf = [UInt8](repeating: 2, count: w * h)
        var depth = [Float](repeating: 0, count: w * h)
        var gray = [Float](repeating: 0.02, count: w * h)
        let light = SIMD3(room.size.x / 2, room.size.y - 0.05, room.size.z / 2)
        let exposure = 1 + 0.06 * sin(Float(time) * 0.7)
        guard moved >= p.needsMotion else {
            return (UltraCapture.DepthFrame(depth: depth, width: w, height: h, fx: f, fy: f, cx: cx, cy: cy, time: time), [UInt8](repeating: 0, count: w * h), gray)
        }
        // This frame's slow warp: a gentle tilt and bowl across the picture.
        let gx = rng.gauss() * p.warp, gy = rng.gauss() * p.warp, bowl = rng.gauss() * p.warp
        for y in 0..<h {
            for x in 0..<w {
                let dc = simd_normalize(SIMD3((Float(x) + 0.5 - cx) / f, (Float(y) + 0.5 - cy) / f, 1))
                let dw = r * dc
                guard let hit = room.cast(o, dw) else { continue }
                let z = hit.t * dc.z // depth along the optical axis
                let i = y * w + x
                truth[i] = z
                // Brightness as the camera sees it: shaded colour, slow exposure change, sensor noise.
                let toLight = light - hit.point, ll = simd_length(toLight)
                let lambert = max(0, simd_dot(hit.normal, toLight / ll)) / (1 + 0.15 * ll * ll)
                let c = room.colour(hit) * (0.35 + 0.9 * lambert) * exposure
                gray[i] = hit.material.dropout > 0.85 ? 0.9 : max(0, min(1, (c.x + c.y + c.z) / 3 + rng.gauss() * 0.01))
                let cosA = abs(simd_dot(hit.normal, dw))
                var drop = hit.material.dropout
                if cosA < 0.17 { drop = max(drop, 0.7); conf[i] = 0 }
                if hit.material.albedo.max() < 0.08 { conf[i] = 0 }
                if hit.material.texture == 0 { drop = max(drop, p.plainDropout) }
                if !p.range.contains(z) || rng.next() < drop { depth[i] = 0; conf[i] = 0; continue }
                let sigma = p.noiseA + p.noiseB * z * z
                let u = (Float(x) - cx) / cx, v = (Float(y) - cy) / cy
                depth[i] = z * (1 + gx * u + gy * v + bowl * (u * u + v * v - 0.5)) + rng.gauss() * sigma
                if z > 3 { conf[i] = min(conf[i], 1) }
            }
        }
        // Flying pixels: half of the pixels on a depth edge land between the two surfaces.
        for y in 1..<(h - 1) {
            for x in 1..<(w - 1) {
                let i = y * w + x
                guard depth[i] > 0 else { continue }
                let nb = [truth[i - 1], truth[i + 1], truth[i - w], truth[i + w]].filter { $0 > 0 && abs($0 - truth[i]) > 0.1 }
                if let other = nb.first {
                    conf[i] = min(conf[i], 1)
                    if rng.next() < 0.5 { depth[i] = truth[i] + (other - truth[i]) * rng.next() }
                }
            }
        }
        return (UltraCapture.DepthFrame(depth: depth, width: w, height: h, fx: f, fy: f, cx: cx, cy: cy, time: time), conf, gray)
    }

    /// A camera picture (RGB 0…255) from a pose: Lambert shading from a light at the ceiling's middle, slow
    /// auto-exposure changes and sensor noise. `hfov` 71.4° for the wide camera, ~106° for the ultrawide.
    static func photo(_ room: Room, _ pose: simd_float4x4, width: Int, height: Int, hfov: Float, time: Float, rng: inout Rand) -> [UInt8] {
        let f = Float(width) / 2 / tan(hfov * .pi / 360), cx = Float(width) / 2, cy = Float(height) / 2
        let r = simd_float3x3(pose.columns.0.ux3, pose.columns.1.ux3, pose.columns.2.ux3), o = pose.columns.3.ux3
        let light = SIMD3(room.size.x / 2, room.size.y - 0.05, room.size.z / 2)
        let exposure = 1 + 0.06 * sin(time * 0.7)
        var px = [UInt8](repeating: 0, count: width * height * 4)
        for y in 0..<height {
            for x in 0..<width {
                let dw = r * simd_normalize(SIMD3((Float(x) + 0.5 - cx) / f, (Float(y) + 0.5 - cy) / f, 1))
                var c = SIMD3<Float>(0.02, 0.02, 0.02)
                if let hit = room.cast(o, dw) {
                    let toLight = light - hit.point
                    let l = simd_length(toLight)
                    let lambert = max(0, simd_dot(hit.normal, toLight / l)) / (1 + 0.15 * l * l)
                    c = room.colour(hit) * (0.35 + 0.9 * lambert) * exposure
                    if hit.material.dropout > 0.85 { c = SIMD3(0.85, 0.9, 1.0) } // a bright window
                }
                c += SIMD3(rng.gauss(), rng.gauss(), rng.gauss()) * 0.012
                let i = (y * width + x) * 4
                px[i] = UInt8(max(0, min(255, c.x * 255))); px[i + 1] = UInt8(max(0, min(255, c.y * 255)))
                px[i + 2] = UInt8(max(0, min(255, c.z * 255))); px[i + 3] = 255
            }
        }
        return px
    }
}

extension SIMD3 where Scalar == Float {
    func max() -> Float { Swift.max(x, y, z) }
}
