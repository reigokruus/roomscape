import Foundation

/// How the phone trains the photo-real preview without overheating (rk 12 Nov: "if heat is the problem just run it
/// with less performance but cooler"), and how a stopped training carries on. Pure logic, tested in
/// SplatPacingTests; the trainer (BrushTrainer) applies it.
public enum SplatPacing {
    /// Rest after each training step, by ProcessInfo.ThermalState raw value (0 nominal, 1 fair, 2 serious,
    /// 3 critical). nil: pause until the phone cools. A step takes about 0.14 s on an iPhone 15 Pro, so these are
    /// about 10%, 40% and 180% slower; slower total time is fine (rk).
    public static func restSeconds(thermal: Int) -> Double? {
        switch thermal {
        case ...0: return 0.015
        case 1: return 0.06
        case 2: return 0.25
        default: return nil
        }
    }

    /// The card's note while training is held back by heat (nil when it isn't).
    public static func note(thermal: Int) -> String? {
        switch thermal {
        case ...0: return nil
        case 1: return "Running a little slower to keep the phone cool"
        case 2: return "Running slowly to keep the phone cool"
        default: return "Paused: the phone is hot, carries on when it cools"
        }
    }

    /// How often the trainer saves where it is (a .ply of the splat so far), so a closed app carries on from there.
    public static let checkpointEvery = 500

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
        public init(step: Int, steps: Int) { self.step = step; self.steps = steps }

        /// The checkpoint to carry on from, if it fits this training (same length, part-way, its .ply present).
        public static func load(from folder: URL, steps: Int) -> Checkpoint? {
            guard let d = try? Data(contentsOf: folder.appendingPathComponent("checkpoint.json")),
                  let c = try? JSONDecoder().decode(Checkpoint.self, from: d),
                  c.steps == steps, c.step > 0, c.step < steps,
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
