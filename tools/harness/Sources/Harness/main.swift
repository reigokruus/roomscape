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
