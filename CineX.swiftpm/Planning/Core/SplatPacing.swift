import Foundation

/// How the phone trains the photo-real preview without overheating (rk 12 Nov: "if heat is the problem just run it
/// with less performance but cooler"), and how a stopped training carries on. Pure logic, tested in
/// SplatPacingTests; the trainer (BrushTrainer) applies it.
public enum SplatPacing {
    /// Rest after training, as a share of the time the training itself just took, by ProcessInfo.ThermalState raw
    /// value (0 nominal, 1 fair, 2 serious, 3 critical); nil: pause until the phone cools. Serious = half speed at
    /// most (rk 12 Nov 16:41: the earlier fixed 0.25 s a step was about 14 of the 16 minutes on a small scan).
    public static func restFactor(thermal: Int) -> Double? {
        switch thermal {
        case ...0: return 0.1
        case 1: return 0.4
        case 2: return 1.0
        default: return nil
        }
    }

    /// The card's note while training is held back by heat (nil when it isn't).
    public static func note(thermal: Int) -> String? {
        switch thermal {
        case ...0: return nil
        case 1: return "Running a little slower to keep the phone cool"
        case 2: return nil   // rk 13 Nov: it's at half speed almost always, so no note (the pacing stays)
        default: return "Paused: the phone is hot, carries on when it cools"
        }
    }

    /// How much training a scan gets on the phone (rk 12 Nov: "scale the work to the scan … tie the step limit to how
    /// many points the scan registered"). Inside today's memory-safe limits: 720 px pictures and at most 200k splats
    /// (refine peaks were 2.97 GB with 400k at 960 px); starting points at most half the splat cap, so the cap holds.
    public struct Plan: Equatable, CustomStringConvertible {
        public var steps: Int, pictures: Int, maxSplats: Int, startPoints: Int
        public var description: String { "\(steps) steps, \(pictures) pictures, \(maxSplats / 1000)k splats, \(startPoints / 1000)k start points" }
    }

    /// `points`: the scan's registered dots; `frames`: the recording's video frames (a sanity bound: at most one
    /// picture in 4 frames). Small scan (≤ 150k dots) 3,000 steps and 150 pictures; a typical room (about 600k)
    /// 6,000 and 250; large (≥ 1.5 M) 9,000 and 300. Linear in between, steps in 500s.
    public static func plan(points: Int, frames: Int) -> Plan {
        func lerp(_ x: Double, _ x0: Double, _ x1: Double, _ y0: Double, _ y1: Double) -> Double {
            y0 + (y1 - y0) * min(1, max(0, (x - x0) / (x1 - x0)))
        }
        let p = Double(max(0, points))
        let steps = p <= 600_000 ? lerp(p, 150_000, 600_000, 3000, 6000) : lerp(p, 600_000, 1_500_000, 6000, 9000)
        let pics = p <= 600_000 ? lerp(p, 150_000, 600_000, 150, 250) : lerp(p, 600_000, 1_500_000, 250, 300)
        let maxSplats = p <= 150_000 ? 150_000 : 200_000
        let pictures = max(20, min(Int(pics.rounded()), frames > 0 ? max(20, frames / 4) : Int(pics)))
        return Plan(steps: Int((steps / 500).rounded()) * 500, pictures: pictures, maxSplats: maxSplats,
                    startPoints: min(maxSplats / 2, max(1, points)))
    }

    /// How often the trainer saves where it is (a .ply of the splat so far), so a closed app carries on from there.
    /// Every 150 steps: the first save comes before Brush's first refine (step 200), where the memory peaks are, so a
    /// run that dies there carries on from 150, not 0 (12 Nov 17:05 scan: it died before step 250 with nothing saved).
    public static let checkpointEvery = 150

    /// The card's training line: "Step 1,200 of 6,000 · about 9 min left".
    public static func stage(step: Int, of steps: Int, minutesLeft: Int?) -> String {
        let f = NumberFormatter(); f.numberStyle = .decimal
        let s = "Step \(f.string(from: NSNumber(value: step)) ?? "\(step)") of \(f.string(from: NSNumber(value: steps)) ?? "\(steps)")"
        return s + (minutesLeft.map { " · about \($0) min left" } ?? "")
    }

    /// Where a stopped training is: the steps already done (saved next to checkpoint.ply as checkpoint.json).
    public struct Checkpoint: Codable, Equatable {
        public var step: Int
        public var steps: Int
        /// The trainer's settings it was made with (e.g. "brush 720px 200k"): a checkpoint from other settings isn't
        /// carried on (a 400k-splat checkpoint would start above a 200k cap).
        public var setup: String?
        public init(step: Int, steps: Int, setup: String? = nil) { self.step = step; self.steps = steps; self.setup = setup }

        /// The checkpoint to carry on from, if it fits this training (same length, part-way, its .ply present).
        public static func load(from folder: URL, steps: Int, setup: String? = nil) -> Checkpoint? {
            guard let d = try? Data(contentsOf: folder.appendingPathComponent("checkpoint.json")),
                  let c = try? JSONDecoder().decode(Checkpoint.self, from: d),
                  c.steps == steps, c.setup == setup, c.step > 0, c.step < steps,
                  FileManager.default.fileExists(atPath: folder.appendingPathComponent("checkpoint.ply").path) else { return nil }
            return c
        }

        /// Saves `ply` (a finished Brush export) as the folder's checkpoint: moved in, then the step written.
        public func save(ply: URL, to folder: URL) throws {
            let fm = FileManager.default
            let dest = folder.appendingPathComponent("checkpoint.ply")
            try? fm.removeItem(at: folder.appendingPathComponent("checkpoint.json"))   // never a step without its .ply
            if fm.fileExists(atPath: dest.path) { try fm.removeItem(at: dest) }
            try fm.moveItem(at: ply, to: dest)
            try JSONEncoder().encode(self).write(to: folder.appendingPathComponent("checkpoint.json"), options: .atomic)
        }

        public static func clear(in folder: URL) {
            for n in ["checkpoint.json", "checkpoint.ply"] { try? FileManager.default.removeItem(at: folder.appendingPathComponent(n)) }
        }
    }
}
