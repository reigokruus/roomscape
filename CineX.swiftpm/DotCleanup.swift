import Foundation
import simd

/// The live dots' cleanup rules (rk 13 Nov, stray black dots that never went away: "would it be possible to update
/// EVERY spot thats closer than its current saved state?", "also when it sees a spot in mid air ... then it removes
/// that", and "add a rectangle in the center instead that only shows the good scannable area and only captures in
/// that zone"). Pure maths, no ARKit, so the Mac harness replays recordings through the same rules (`harness strays`).
enum DotCleanup {
    /// The capture zone: the centred share of the screen's width and height where depth makes and updates dots and
    /// can erase them, drawn as a thin outline on the scan screen. Replayed on rk's 13 Nov 10:24 recording (floaters =
    /// dots later seen clearly through): the whole frame 8 515 floaters, 66% of the surface covered; the screen only
    /// (1.0) 3 732, 59%; 0.85 2 918, 55%; 0.7 1 650, 47%. Height 0.7 keeps Cancel / Done below it on a 15 Pro.
    static let zoneWidth: Float = 0.85, zoneHeight: Float = 0.7

    /// The zone in the depth map's normalised coordinates (x along the sensor's long side, y along its short side,
    /// 0…1), for a portrait screen of `viewAspect` (width / height) showing the camera aspect-filled, as RoomPlan's
    /// view and the live renderer do. The screen's height runs along the sensor's long side; its width covers only the
    /// middle part of the short side (about 62% on a 15 Pro): the rest of the depth frame is never on screen.
    static func zoneRect(imageWidth: Int, imageHeight: Int, viewAspect: Float, width: Float = DotCleanup.zoneWidth, height: Float = DotCleanup.zoneHeight) -> (x0: Float, x1: Float, y0: Float, y1: Float) {
        let portraitAspect = Float(imageHeight) / Float(imageWidth)   // the camera picture turned upright, width / height
        var alongLong: Float = 1, alongShort: Float = 1               // the share of each sensor side that is on screen
        if viewAspect > 0 {
            if viewAspect < portraitAspect { alongShort = viewAspect / portraitAspect } else { alongLong = portraitAspect / viewAspect }
        }
        let hx = height * alongLong / 2, hy = width * alongShort / 2   // the screen's height runs along the long side
        return (0.5 - hx, 0.5 + hx, 0.5 - hy, 0.5 + hy)
    }

    /// Closest good look wins (as Android's CLOSEST_WINS): a sure reading clearly closer than the dot's closest look so
    /// far replaces its position and colour: under 80% of that distance and at least 15 cm closer. Farther or equal
    /// looks only blend in, so repeated looks settle instead of churning. Distances in cm.
    @inline(__always) static func closerWins(stored: UInt16, now: UInt16) -> Bool {
        stored > 0 && Int(now) * 5 < Int(stored) * 4 && Int(stored) - Int(now) >= 15
    }

    /// See-through: frames in which the camera's sure depth was clearly behind a dot (over its whole spot) before it
    /// goes; a dot seen solid (in 10+ frames) needs one more.
    static let throughFrames: UInt8 = 2, throughFramesSolid: UInt8 = 3
    /// How much farther than the dot the measured depth must be: max(8 cm, 5% of the distance).
    @inline(__always) static func margin(_ d: Float) -> Float { max(0.08, 0.05 * d) }

    /// Dots tested per depth frame, round robin over the cloud (the whole cloud every few frames).
    static let budget = 200_000

    /// One depth frame for the see-through test.
    struct DepthFrame {
        var depth: UnsafePointer<Float32>, depthRow: Int
        var confidence: UnsafePointer<UInt8>, confidenceRow: Int
        var width: Int, height: Int
        var fx: Float, fy: Float, cx: Float, cy: Float
        /// World to camera (ARKit camera: x right, y up, looking down -z).
        var worldToCamera: simd_float4x4
        /// The capture zone in pixels.
        var x0: Int, x1: Int, y0: Int, y1: Int
        var voxel: Float
    }

    /// Whether this frame sees clearly past the dot at `p`: true = seen through, false = not, nil = can't tell (outside
    /// the zone, out of range, unsure depth, or a depth edge at the spot).
    static func seenThrough(_ p: SIMD3<Float>, _ f: DepthFrame) -> Bool? {
        let c = f.worldToCamera * SIMD4(p, 1)
        let d = -c.z
        guard d > 0.25, d < 3.5 else { return nil }
        // the inverse of the dot's own projection (pixel centre at u + 0.5)
        let u = Int(f.fx * c.x / d + f.cx), v = Int(f.cy - f.fy * c.y / d)
        // The whole spot must be seen through, not only its own pixel (Android carve(): close up a 2 cm dot covers
        // several pixels, and a chair leg's dot beside its pixel would see the wall behind and be erased).
        let r = max(1, min(6, Int((0.6 * f.voxel * f.fx / d).rounded())))
        guard u - r >= f.x0, u + r < f.x1, v - r >= f.y0, v + r < f.y1, u - r >= 1, v - r >= 1,
              u + r < f.width - 1, v + r < f.height - 1 else { return nil }
        let limit = d + margin(d)
        let high = UInt8(2)
        for dy in -1...1 {
            for dx in -1...1 {
                let x = u + dx * r, y = v + dy * r
                guard f.confidence[y * f.confidenceRow + x] >= high else { return nil }
                let z = f.depth[y * f.depthRow + x]
                guard z > 0.2 else { return nil }
                if z <= limit { return false }
            }
        }
        // No depth edge at the spot's own pixel (flying pixels at silhouettes aren't evidence).
        let z = f.depth[v * f.depthRow + u], tol = z * 0.04 + 0.02
        if abs(f.depth[v * f.depthRow + u - 1] - z) > tol || abs(f.depth[v * f.depthRow + u + 1] - z) > tol
            || abs(f.depth[(v - 1) * f.depthRow + u] - z) > tol || abs(f.depth[(v + 1) * f.depthRow + u] - z) > tol { return nil }
        return true
    }
}

extension DotCleanup {
    /// Whether this frame's sure depth sees the point at about its own depth (within the margin) at its pixel.
    static func seenAt(_ p: SIMD3<Float>, _ f: DepthFrame) -> Bool {
        let c = f.worldToCamera * SIMD4(p, 1)
        let d = -c.z
        guard d > 0.25, d < 3.5 else { return false }
        let u = Int(f.fx * c.x / d + f.cx), v = Int(f.cy - f.fy * c.y / d)
        guard u >= 1, v >= 1, u < f.width - 1, v < f.height - 1, f.confidence[v * f.confidenceRow + u] >= 2 else { return false }
        return abs(f.depth[v * f.depthRow + u] - d) < margin(d)
    }
}

/// The room built from the recording after Done (13 Nov, coordinator for rk: the same see-through idea as the live
/// dots): a point that sure depth from other frames sees clearly through (over its whole spot, as `seenThrough`, the
/// whole frame, no capture zone) in at least `minThrough` frames, and in at least as many frames as see it at its
/// own depth, is dropped. Every `frameStep`-th depth frame is used.
enum RebuildCleanup {
    static let minThrough = 3, frameStep = 3

    /// One depth frame: depth in metres, confidence 0-2 (empty = unknown), row-major `width` × `height`, intrinsics
    /// for this size, world to camera.
    struct Frame { var depth: [Float32]; var confidence: [UInt8]; var width: Int; var height: Int
                   var fx: Float; var fy: Float; var cx: Float; var cy: Float; var worldToCamera: simd_float4x4 }

    /// Which points to keep. `frame(i)` gives depth frame i (nil to skip it); called once per used frame, in order.
    static func keep(_ points: [SIMD3<Float>], frameCount: Int, voxel: Float = 0.02, frame: (Int) -> Frame?) -> [Bool] {
        let n = points.count
        guard n > 0 else { return [] }
        var through = [UInt16](repeating: 0, count: n), at = [UInt16](repeating: 0, count: n)
        let parts = 6
        for i in stride(from: 0, to: frameCount, by: frameStep) {
            guard let f = frame(i), !f.confidence.isEmpty else { continue }
            f.depth.withUnsafeBufferPointer { dp in f.confidence.withUnsafeBufferPointer { cp in
                through.withUnsafeMutableBufferPointer { tp in at.withUnsafeMutableBufferPointer { ap in
                    let view = DotCleanup.DepthFrame(depth: dp.baseAddress!, depthRow: f.width, confidence: cp.baseAddress!, confidenceRow: f.width,
                                                     width: f.width, height: f.height, fx: f.fx, fy: f.fy, cx: f.cx, cy: f.cy,
                                                     worldToCamera: f.worldToCamera, x0: 0, x1: f.width, y0: 0, y1: f.height, voxel: voxel)
                    let tpp = tp.baseAddress!, app = ap.baseAddress!
                    DispatchQueue.concurrentPerform(iterations: parts) { part in
                        for j in stride(from: part, to: n, by: parts) {
                            if let s = DotCleanup.seenThrough(points[j], view) {
                                if s { tpp[j] &+= 1 } else if DotCleanup.seenAt(points[j], view) { app[j] &+= 1 }
                            }
                        }
                    }
                } }
            } }
        }
        return (0..<n).map { !(Int(through[$0]) >= minThrough && through[$0] >= at[$0]) }
    }
}
