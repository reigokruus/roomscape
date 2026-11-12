import Foundation

/// The photo-real room preview (Gaussian splat, rk 12 Nov): the dataset a splat trainer reads, made from a scan's
/// recording. Pure maths and file formats, shared by the app and its tests (no ARKit, no video here).
///
/// Format: Nerfstudio `transforms.json` (what Brush, gsplat and nerfstudio read): per frame an image file, a
/// camera-to-world `transform_matrix` (4 rows of 4) and the lens; `ply_file_path` names the starting points.
/// Convention: Nerfstudio uses the OpenGL camera (x right, y up, looking down -z), which is ARKit's camera too, so a
/// frame's ARKit pose goes in unchanged, only written row by row. (ARCore's camera is the same; COLMAP/OpenCV would
/// need y and z flipped.)
public enum SplatDataset {
    /// One picked frame: its image file (relative to transforms.json), pose and lens at the written image size.
    public struct Frame: Equatable {
        public var file: String
        /// Camera-to-world, 16 values column-major (simd_float4x4's columns one after another).
        public var cameraToWorld: [Float]
        public var fx: Float, fy: Float, cx: Float, cy: Float
        public var width: Int, height: Int
        public init(file: String, cameraToWorld: [Float], fx: Float, fy: Float, cx: Float, cy: Float, width: Int, height: Int) {
            self.file = file; self.cameraToWorld = cameraToWorld
            self.fx = fx; self.fy = fy; self.cx = cx; self.cy = cy; self.width = width; self.height = height
        }
    }

    /// The pose as Nerfstudio's 4 rows (row r = column-major values r, r+4, r+8, r+12).
    public static func rows(_ m: [Float]) -> [[Float]] {
        precondition(m.count == 16, "a 4x4 matrix has 16 values")
        return (0..<4).map { r in (0..<4).map { c in m[c * 4 + r] } }
    }

    /// Lens for an image scaled from `from` to `to` pixels (pixel edges scale; ARKit's cx, cy are in pixels).
    public static func scaledLens(fx: Float, fy: Float, cx: Float, cy: Float, from: (w: Int, h: Int), to: (w: Int, h: Int))
        -> (fx: Float, fy: Float, cx: Float, cy: Float) {
        let sx = Float(to.w) / Float(from.w), sy = Float(to.h) / Float(from.h)
        return (fx * sx, fy * sy, cx * sx, cy * sy)
    }

    /// transforms.json for these frames, with the starting points file if there is one.
    public static func transformsJSON(frames: [Frame], pointsFile: String?) throws -> Data {
        var root: [String: Any] = ["camera_model": "PERSPECTIVE"]
        if let pointsFile { root["ply_file_path"] = pointsFile }
        root["frames"] = frames.map { f -> [String: Any] in
            ["file_path": f.file, "transform_matrix": rows(f.cameraToWorld),
             "fl_x": f.fx, "fl_y": f.fy, "cx": f.cx, "cy": f.cy, "w": f.width, "h": f.height]
        }
        return try JSONSerialization.data(withJSONObject: root, options: [.prettyPrinted, .sortedKeys])
    }

    /// Picks the frames to train from: the sharpest in each run of `window` consecutive frames (nil score = not
    /// usable, e.g. tracking not normal), at most `maxFrames` of them spread evenly. Returns indices in order.
    public static func pickSharpest(scores: [Float?], maxFrames: Int) -> [Int] {
        guard maxFrames > 0, !scores.isEmpty else { return [] }
        let window = max(1, Int((Double(scores.count) / Double(maxFrames)).rounded(.up)))
        var out: [Int] = []
        var start = 0
        while start < scores.count {
            let end = min(scores.count, start + window)
            var best: Int?
            for i in start..<end { if let s = scores[i], best == nil || s > scores[best!]! { best = i } }
            if let best { out.append(best) }
            start = end
        }
        return out
    }

    /// Starting points as a binary little-endian PLY: x y z (float) and red green blue (uchar), what the trainers
    /// read. At most `maxPoints`, taken evenly.
    public static func pointsPLY(xyz: [Float], rgb: [UInt8], maxPoints: Int) -> Data {
        let n = xyz.count / 3
        let step = max(1, Int((Double(n) / Double(max(1, maxPoints))).rounded(.up)))
        let kept = stride(from: 0, to: n, by: step).map { $0 }
        var out = Data("ply\nformat binary_little_endian 1.0\nelement vertex \(kept.count)\nproperty float x\nproperty float y\nproperty float z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n".utf8)
        out.reserveCapacity(out.count + kept.count * 15)
        for i in kept {
            for k in 0..<3 { withUnsafeBytes(of: xyz[i * 3 + k].bitPattern.littleEndian) { out.append(contentsOf: $0) } }
            out.append(contentsOf: [rgb[i * 3], rgb[i * 3 + 1], rgb[i * 3 + 2]])
        }
        return out
    }

    /// A Gaussian splat PLY (the 3DGS layout every splat viewer reads) with one small round blob per point: the
    /// stand-in result until a real trainer is linked in, so the whole flow (job, progress, viewer, .rmsc) works.
    /// `size` is the blob radius in metres.
    public static func dotsSplatPLY(xyz: [Float], rgb: [UInt8], maxPoints: Int, size: Float = 0.012) -> Data {
        let n = xyz.count / 3
        let step = max(1, Int((Double(n) / Double(max(1, maxPoints))).rounded(.up)))
        let kept = stride(from: 0, to: n, by: step).map { $0 }
        let props = ["x", "y", "z", "f_dc_0", "f_dc_1", "f_dc_2", "opacity", "scale_0", "scale_1", "scale_2", "rot_0", "rot_1", "rot_2", "rot_3"]
        var out = Data(("ply\nformat binary_little_endian 1.0\nelement vertex \(kept.count)\n"
                        + props.map { "property float \($0)\n" }.joined() + "end_header\n").utf8)
        let c0: Float = 0.28209479177387814   // degree-0 spherical harmonic: colour = 0.5 + c0 * f_dc
        let logScale = log(size), logitOpacity: Float = 4   // sigmoid(4) = 0.98
        for i in kept {
            var v: [Float] = [xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2]]
            for k in 0..<3 { v.append((Float(rgb[i * 3 + k]) / 255 - 0.5) / c0) }
            v += [logitOpacity, logScale, logScale, logScale, 1, 0, 0, 0]
            for x in v { withUnsafeBytes(of: x.bitPattern.littleEndian) { out.append(contentsOf: $0) } }
        }
        return out
    }

    /// Minutes left from progress so far (fraction 0...1 and seconds spent), or nil before there's enough to say.
    public static func minutesLeft(fraction: Double, elapsed: Double) -> Int? {
        guard fraction > 0.03, elapsed > 20 else { return nil }
        return max(1, Int((elapsed / fraction * (1 - fraction) / 60).rounded(.up)))
    }
}
