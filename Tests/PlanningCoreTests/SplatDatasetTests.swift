import XCTest
@testable import PlanningCore

final class SplatDatasetTests: XCTestCase {
    func testRowsOfAColumnMajorPose() {
        // camera at (1, 2, 3), turned 90 degrees about y: x axis -> -z, z axis -> x
        let m: [Float] = [0, 0, -1, 0,   0, 1, 0, 0,   1, 0, 0, 0,   1, 2, 3, 1]
        let r = SplatDataset.rows(m)
        XCTAssertEqual(r[0], [0, 0, 1, 1])
        XCTAssertEqual(r[1], [0, 1, 0, 2])
        XCTAssertEqual(r[2], [-1, 0, 0, 3])
        XCTAssertEqual(r[3], [0, 0, 0, 1])
    }

    func testCameraLooksAlongMinusZOfItsPose() {
        // ARKit and Nerfstudio share the camera: it looks down its own -z. With the pose above it looks along world -x.
        let m: [Float] = [0, 0, -1, 0,   0, 1, 0, 0,   1, 0, 0, 0,   1, 2, 3, 1]
        let r = SplatDataset.rows(m)
        let forward = (0..<3).map { -r[$0][2] }
        XCTAssertEqual(forward, [-1, 0, 0])
    }

    func testScaledLensHalfSize() {
        let l = SplatDataset.scaledLens(fx: 1342, fy: 1342, cx: 958, cy: 721, from: (1920, 1440), to: (960, 720))
        XCTAssertEqual(l.fx, 671, accuracy: 1e-3)
        XCTAssertEqual(l.cx, 479, accuracy: 1e-3)
        XCTAssertEqual(l.cy, 360.5, accuracy: 1e-3)
    }

    func testTransformsJSONIsWhatBrushReads() throws {
        let f = SplatDataset.Frame(file: "images/00001.jpg", cameraToWorld: [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0.5, 1, 2, 1],
                                   fx: 671, fy: 671, cx: 480, cy: 360, width: 960, height: 720)
        let data = try SplatDataset.transformsJSON(frames: [f, f], pointsFile: "points.ply")
        let j = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(j["ply_file_path"] as? String, "points.ply")
        let frames = try XCTUnwrap(j["frames"] as? [[String: Any]])
        XCTAssertEqual(frames.count, 2)
        let tm = try XCTUnwrap(frames[0]["transform_matrix"] as? [[Double]])
        XCTAssertEqual(tm[0], [1, 0, 0, 0.5]); XCTAssertEqual(tm[2], [0, 0, 1, 2]); XCTAssertEqual(tm[3], [0, 0, 0, 1])
        XCTAssertEqual(frames[0]["fl_x"] as? Double, 671)
        XCTAssertEqual(frames[0]["w"] as? Int, 960)
    }

    func testPickSharpestOnePerWindow() {
        // 10 frames, at most 5 picked: windows of 2; frame 3 unusable (nil)
        let s: [Float?] = [1, 5, 2, nil, 9, 8, 0, 0, 3, 4]
        XCTAssertEqual(SplatDataset.pickSharpest(scores: s, maxFrames: 5), [1, 2, 4, 6, 9])
        XCTAssertEqual(SplatDataset.pickSharpest(scores: [nil, nil], maxFrames: 5), [])
        XCTAssertEqual(SplatDataset.pickSharpest(scores: s, maxFrames: 100).count, 9)   // every usable frame
    }

    func testPointsPLYLayout() {
        let xyz: [Float] = [0, 1, 2, 3, 4, 5, 6, 7, 8]
        let rgb: [UInt8] = [10, 20, 30, 40, 50, 60, 70, 80, 90]
        let d = SplatDataset.pointsPLY(xyz: xyz, rgb: rgb, maxPoints: 2)   // every 2nd: points 0 and 2
        let text = String(decoding: d.prefix(200), as: UTF8.self)
        XCTAssertTrue(text.contains("element vertex 2\n"))
        let header = text.range(of: "end_header\n")!
        let body = d.suffix(from: d.startIndex + text.distance(from: text.startIndex, to: header.upperBound))
        XCTAssertEqual(body.count, 2 * 15)
        XCTAssertEqual(Array(body.suffix(3)), [70, 80, 90])
    }

    func testDotsSplatHasThe3DGSFields() {
        let d = SplatDataset.dotsSplatPLY(xyz: [0, 0, 0], rgb: [255, 128, 0], maxPoints: 10)
        let text = String(decoding: d.prefix(600), as: UTF8.self)
        for p in ["f_dc_0", "opacity", "scale_2", "rot_3"] { XCTAssertTrue(text.contains("property float \(p)\n"), p) }
        XCTAssertTrue(text.contains("element vertex 1\n"))
    }

    func testMinutesLeft() {
        XCTAssertNil(SplatDataset.minutesLeft(fraction: 0.01, elapsed: 30))
        XCTAssertEqual(SplatDataset.minutesLeft(fraction: 0.25, elapsed: 120), 6)   // 120 s for 25%: 360 s left
    }

    /// On a real iPhone recording (CINEX_SPLAT_RECORDING=<scan>.capture/recording): the poses read from frames.bin
    /// go into transforms.json unchanged and the cameras sit where the phone was (moved < 1 m between picked frames).
    /// Writes transforms.json + frame times to CINEX_SPLAT_OUT for the Mac check (tools/room-rebuild/check_nerfstudio.py).
    func testRealRecordingPoses() throws {
        guard let rec = ProcessInfo.processInfo.environment["CINEX_SPLAT_RECORDING"] else { throw XCTSkip("no recording given") }
        let fb = try Data(contentsOf: URL(fileURLWithPath: rec).appendingPathComponent("frames.bin"))
        let n = fb.count / 120
        XCTAssertGreaterThan(n, 100)
        func f32(_ o: Int) -> Float { Float(bitPattern: fb.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: o, as: UInt32.self) }) }
        var frames: [SplatDataset.Frame] = [], times: [Double] = []
        let t0 = fb.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 0, as: Double.self) }
        let picked = SplatDataset.pickSharpest(scores: (0..<n).map { i in
            fb.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: i * 120 + 116, as: Int32.self) } == 2 ? 1 : nil }, maxFrames: 300)
        for i in picked {
            let o = i * 120
            let pose = (0..<16).map { f32(o + 8 + $0 * 4) }
            let k = (0..<9).map { f32(o + 72 + $0 * 4) }    // columns: fx = k[0], fy = k[4], cx = k[6], cy = k[7]
            let w = Int(fb.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: o + 108, as: Int32.self) })
            let h = Int(fb.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: o + 112, as: Int32.self) })
            let l = SplatDataset.scaledLens(fx: k[0], fy: k[4], cx: k[6], cy: k[7], from: (w, h), to: (w / 2, h / 2))
            frames.append(.init(file: "images/\(i).jpg", cameraToWorld: pose, fx: l.fx, fy: l.fy, cx: l.cx, cy: l.cy, width: w / 2, height: h / 2))
            times.append(fb.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: o, as: Double.self) } - t0)
            XCTAssertEqual(SplatDataset.rows(pose)[0][3], pose[12])
        }
        XCTAssertGreaterThan(frames.count, 150)
        for (a, b) in zip(frames, frames.dropFirst()) {
            let d = (0..<3).map { a.cameraToWorld[12 + $0] - b.cameraToWorld[12 + $0] }
            XCTAssertLessThan((d[0] * d[0] + d[1] * d[1] + d[2] * d[2]).squareRoot(), 1.0)
        }
        if let out = ProcessInfo.processInfo.environment["CINEX_SPLAT_OUT"] {
            let dir = URL(fileURLWithPath: out)
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try SplatDataset.transformsJSON(frames: frames, pointsFile: nil).write(to: dir.appendingPathComponent("transforms.json"))
            try JSONSerialization.data(withJSONObject: zip(frames.map(\.file), times).map { ["file": $0.0, "time": $0.1] })
                .write(to: dir.appendingPathComponent("times.json"))
        }
    }
}
