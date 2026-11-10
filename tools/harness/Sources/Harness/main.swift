import Foundation
import simd

// RoomScape test harness. See docs/test-harness.md.
//   harness calibrate <scan.rmsc>              depth noise, dropouts and edges from a real scan
//   harness track [scenario] [iphone|s24]      the experimental tracker vs ground truth, meshes as PNGs
//   harness holefill                           hole fill with the chest of drawers removed
//   harness clear                              tap-to-clear's rule on the TSDF
//   harness rmsc <scenario> <out.rmsc> [s24]   a synthetic scan the apps can open
//   harness all                                every test on every scenario

let args = Array(CommandLine.arguments.dropFirst())
Runs.depthOnly = args.contains("depthonly")
let out = URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appendingPathComponent("harness-out")
try? FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
func profile(_ s: String?) -> Profile { s == "s24" ? .s24 : .iPhone15Pro }

switch args.first ?? "all" {
case "tiles":
    try TilesRun.run(URL(fileURLWithPath: args[1]), out: out, limit: args.count > 2 ? Double(args[2]) ?? 1e9 : 1e9)
case "calibrate":
    for path in args.dropFirst() { try Calibration.run(URL(fileURLWithPath: path)) }
case "track":
    let scenarios = args.count > 1 ? [Scenario(rawValue: args[1]) ?? .tight] : Scenario.allCases
    for s in scenarios { Runs.track(s, profile(args.count > 2 ? args[2] : nil), out: out) }
case "debugtrack":
    let room = (Scenario(rawValue: args.count > 1 ? args[1] : "normal") ?? .normal).room
    var prof = Profile.iPhone15Pro
    if args.contains("clean") { prof.noiseA = 0; prof.noiseB = 0; prof.warp = 0 }
    let frames = Sensors.frames(room, prof, duration: 40, fps: 15)
    let useGyro = !args.contains("nogyro")
    let tracker = UltraTracker()
    UltraTracker.debug = args.contains("verbose")
    var align = matrix_identity_float4x4
    var lastPose: simd_float4x4?
    for (i, f) in frames.enumerated() {
        if i == 0 {
            let r = simd_float3x3(f.truth.columns.0.ux3, f.truth.columns.1.ux3, f.truth.columns.2.ux3)
            tracker.begin(gravity: r.transpose * SIMD3(0, -1, 0))
        }
        let res = tracker.track(f.depth, intensity: Runs.depthOnly ? nil : f.intensity, gyro: useGyro ? f.gyro : nil)
        if i == 0 { align = f.truth * tracker.pose.inverse }
        let p = align * tracker.pose
        let e = simd_distance(p.columns.3.ux3, f.truth.columns.3.ux3)
        if i < 25 || i % 10 == 0 { print(String(format: "%3d ok %d inl %.2f rms %.1f mm err %.1f cm", i, res.ok ? 1 : 0, res.inliers, res.rms * 1000, e * 100)) }
        if i > 0 && i < 8 {
            let tp = frames[i - 1].truth, tc = f.truth
            let trueRel = tp.inverse * tc  // current camera in previous camera's coordinates
            print(String(format: "     true move (prev cam) %+.3f %+.3f %+.3f", trueRel.columns.3.x, trueRel.columns.3.y, trueRel.columns.3.z))
            if let prev = lastPose { let er = prev.inverse * tracker.pose; print(String(format: "     est  move (prev cam) %+.3f %+.3f %+.3f", er.columns.3.x, er.columns.3.y, er.columns.3.z)) }
        }
        lastPose = tracker.pose
    }
case "compare":
    try Replay.compare(URL(fileURLWithPath: args[1]), out: out)
case "why":
    Why.run(URL(fileURLWithPath: args[1]), SIMD3(Float(args[2])!, Float(args[3])!, Float(args[4])!))
case "align":
    Align.run(iphone: URL(fileURLWithPath: args[1]), s24: URL(fileURLWithPath: args[2]), out: URL(fileURLWithPath: "harness-out/align-top.png"))
case "photocover":
    // harness photocover <capture folder>...: triangles painted vs left grey, with and without the corner occlusion check.
    for path in args.dropFirst() {
        for corners in [false, true] {
            PhotoLayout.checkCorners = corners
            let t = Date()
            guard let l = PhotoLayout.make(folder: URL(fileURLWithPath: path), maxPhotos: Int(ProcessInfo.processInfo.environment["PHOTOS"] ?? "80") ?? 80, leaveOutCeiling: true) else { print("no layout: \(path)"); break }
            let painted = l.groups.reduce(0) { $0 + $1.triangles.count }
            print(String(format: "%@ corners %@: %d painted, %d grey (%.1f%%), %d photos, %.1f s", URL(fileURLWithPath: path).lastPathComponent, corners ? "on " : "off", painted, l.plain.count, Double(l.plain.count) * 100 / Double(max(1, painted + l.plain.count)), l.groups.count, Date().timeIntervalSince(t)))
        }
    }
case "gpubench":
    // harness gpubench <recording> [voxel metres] [frame to render from] [gpuonly]
    try GPUBench.run(URL(fileURLWithPath: args[1]), voxel: args.count > 2 ? Float(args[2]) ?? 0.04 : 0.04,
                     view: args.count > 3 ? Int(args[3]) : nil, skipCPU: args.contains("gpuonly"),
                     carve: args.contains("nocarve") ? 0 : 1.5, tag: args.contains("nocarve") ? "-nocarve" : "")
case "recover":
    let sem = DispatchSemaphore(value: 0)
    Task { do { try await Recover.run(URL(fileURLWithPath: args[1]), to: URL(fileURLWithPath: args[2]), name: args.count > 3 ? args[3] : "Recovered scan") } catch { print("recover failed: \(error)") }; sem.signal() }
    sem.wait()
case "decals":
    let sem = DispatchSemaphore(value: 0)
    Task { do { try await Decals.run(URL(fileURLWithPath: args[1]), out: out) } catch { print("decals failed: \(error)") }; sem.signal() }
    sem.wait()
case "plyview":
    // harness plyview <ply> <S24 scan folder> <photo index>... : the mesh from that S24 photo's camera, plyview-<name>-<i>.png
    let ply = URL(fileURLWithPath: args[1]), scan = URL(fileURLWithPath: args[2])
    let pos = Align.readPly(ply), tris = Align.readPlyFaces(ply)
    let kd = try Data(contentsOf: scan.appendingPathComponent("kf.bin"))
    var o = 0
    func rf() -> Float { let v = UInt32(bigEndian: kd.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: o, as: UInt32.self) }); o += 4; return Float(bitPattern: v) }
    func ri() -> Int { let v = Int32(bigEndian: kd.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: o, as: Int32.self) }); o += 4; return Int(v) }
    let nk = ri()
    var cams: [(simd_float4x4, simd_float3x3, Int, Int)] = []
    for _ in 0..<nk {
        for _ in 0..<16 { _ = rf() }
        var c = [Float](); for _ in 0..<16 { c.append(rf()) }
        let fx = rf(), fy = rf(), cx = rf(), cy = rf(), w = rf(), h = rf()
        o += 1
        let dw = ri(), dh = ri(); for _ in 0..<4 { _ = rf() }; o += dw * dh * 2
        let pose = simd_float4x4(SIMD4(c[0], c[1], c[2], c[3]), SIMD4(c[4], c[5], c[6], c[7]), SIMD4(c[8], c[9], c[10], c[11]), SIMD4(c[12], c[13], c[14], c[15]))
        cams.append((pose, simd_float3x3(SIMD3(fx, 0, 0), SIMD3(0, fy, 0), SIMD3(cx, cy, 1)), Int(w), Int(h)))
    }
    for a in args.dropFirst(3) { if let i = Int(a), i < cams.count {
        let (pose, k, w, h) = cams[i]
        let url = URL(fileURLWithPath: "harness-out/plyview-\(ply.deletingPathExtension().lastPathComponent)-\(i).png")
        Images.renderFromCamera(pos, tris, pose: pose, k: k, imageWidth: w, imageHeight: h, to: url)
        print("  render: \(url.path)")
    } }
case "replay":
    try Replay.run(URL(fileURLWithPath: args[1]), out: out)
case "holefill":
    Runs.holeFill()
case "clear":
    Runs.tapClear()
case "rmsc":
    guard args.count >= 3, let s = Scenario(rawValue: args[1]) else { print("harness rmsc <scenario> <out.rmsc> [s24]"); exit(1) }
    try Runs.writeRmsc(s, profile(args.count > 3 ? args[3] : nil), to: URL(fileURLWithPath: args[2]))
default:
    for s in Scenario.allCases { Runs.track(s, .iPhone15Pro, out: out) }
    Runs.holeFill()
    Runs.tapClear()
}
print("Pictures in \(out.path)")
