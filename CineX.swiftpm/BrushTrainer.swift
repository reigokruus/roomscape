#if canImport(BrushC)
import BrushC
import Foundation
import UIKit

/// The real trainer for the photo-real preview: Brush (Apache-2.0, https://github.com/ArthurBrussee/brush), linked in
/// as a static library when CineX.swiftpm/Vendor/BrushC.xcframework exists on the Mac that builds the app (see
/// tools/brush-ios). Without it the app builds as before and uses the stand-in `DotsTrainer`.
///
/// Phone settings (rk 12 Nov): pictures at most 960 px, at most 400k splats, spherical harmonics degree 1 (less memory
/// than Brush's 3), one export at the end. Brush blocks while it trains, so it runs on its own thread; cancelling the
/// job sets the cancel byte Brush checks after every step.
///
/// Heat and restarts (rk 12 Nov: the app closed by itself mid-training, at thermal state serious, and always started
/// again from zero; "if heat is the problem just run it with less performance but cooler"):
/// - paced: after every report the Brush thread rests a share of the time training just took (`SplatPacing.restFactor`: a little at nominal, more at fair,
///   a lot at serious, paused at critical). Brush's message stream only moves on when the step's message is taken,
///   so resting in the callback holds training back without changing Brush.
/// - checkpoints: Brush exports the splat every `SplatPacing.checkpointEvery` steps; each finished export becomes
///   splat/checkpoint.ply (+ checkpoint.json with the step). A new run puts it in the dataset as init.ply, which
///   Brush loads as its starting splat (brush-dataset: init.ply overrides the starting points), and trains the steps
///   still to do. The learning rates start again from the top for those steps, so a resumed preview is a little
///   less sharp than one in one go; much better than starting from zero.
struct BrushTrainer: SplatPreview.Trainer {
    var name: String { "Brush" }
    var setup: String { Self.setup }
    /// Memory (12 Nov 13:04Z, measured on the phone): the footprint peaks at Brush's refine steps (every 200 steps)
    /// and the peak grows with the splat count: 2.26 GB loading, 2.66 GB at step 1,200, 2.97 GB at step 1,600 with
    /// 960 px and a 400k cap; the app was stopped by iOS each time at about step 1,400-1,600. So 720 px pictures and
    /// at most 200k splats on the phone (the PC's full version has millions).
    static let maxResolution: UInt32 = 720, maxSplats: UInt32 = 200_000, shDegree: UInt32 = 1
    static let setup = "brush \(maxResolution)px sh\(shDegree)"   // + the plan's splat cap (SplatPreview.run)

    /// Brush's memory marks (brush-train memmarks, iOS: loading-marks.jsonl, refine-marks.jsonl) and its refine-skips
    /// note, copied into <folder>/memory-marks/<time>-<name> so they survive (also from a run iOS stopped), and a line
    /// in the processing log. Read off the phone afterwards (13 Nov: finding Brush's 2 GB spikes).
    static func keepMarks(from outDir: URL, in folder: URL) {
        let fm = FileManager.default
        let names = ["loading-marks.jsonl", "refine-marks.jsonl", "step-marks.jsonl", "refine-skips.txt"]
        let found = names.filter { fm.fileExists(atPath: outDir.appendingPathComponent($0).path) }
        guard !found.isEmpty else { return }
        let keep = folder.appendingPathComponent("memory-marks")
        try? fm.createDirectory(at: keep, withIntermediateDirectories: true)
        let stamp = Int(Date().timeIntervalSince1970)
        var lines = 0
        for name in found {
            let src = outDir.appendingPathComponent(name)
            lines += ((try? String(contentsOf: src, encoding: .utf8)) ?? "").split(separator: "\n").count
            try? fm.copyItem(at: src, to: keep.appendingPathComponent("\(stamp)-\(name)"))
        }
        ProcessingQueue.logMemory("Brush memory marks kept (\(found.joined(separator: ", ")), \(lines) lines) in \(folder.lastPathComponent)/memory-marks")
    }

    /// The refine guard in Brush (iOS): a refine runs only with this much memory available, else it's skipped
    /// (refine-skips.txt). 2250 MB for real runs (13 Nov: the refine's ~2 GB spike); the memory test sets 1200 so
    /// refines run and refine-marks.jsonl names the part that spikes (a crash there is acceptable: the marks survive).
    static func setRefineGuard(test: Bool) {
        setenv("BRUSH_REFINE_MIN_AVAIL_MB", test ? "1200" : "2250", 1)
    }

    /// Everything the Brush thread's callback needs (it runs on that thread, for every step).
    private final class Box: @unchecked Sendable {
        let progress: @Sendable (Int, String?) -> Void
        let cancel = UnsafeMutablePointer<UInt8>.allocate(capacity: 1)
        /// Set while the app is losing focus (willResignActive): Brush waits before its next GPU work, so the GPU is
        /// idle by the time iOS puts the app in the background (12:31Z: a GPU "firmware-detected lockup" report 3 s
        /// after the app was left mid-step).
        let hold = UnsafeMutablePointer<UInt8>.allocate(capacity: 1)
        let from: Int, steps: Int, folder: URL, outDir: URL, setup: String
        init(progress: @escaping @Sendable (Int, String?) -> Void, from: Int, steps: Int, folder: URL, outDir: URL, setup: String) {
            self.progress = progress; self.from = from; self.steps = steps; self.folder = folder; self.outDir = outDir; self.setup = setup
            cancel.pointee = 0; hold.pointee = 0
        }
        deinit { cancel.deallocate(); hold.deallocate() }

        /// Brush reports every 5th step (`iter` counts from 1 in this run; brush-process UPDATE_EVERY) and exports
        /// (export_every) before it reports that step.
        private var lastIter = 0
        /// Measured, for the processing log: seconds Brush spent training, resting for heat, and paused (critical,
        /// or the app losing focus). The clock starts at Brush's first report (loading the pictures isn't counted).
        private(set) var trainSeconds = 0.0, restSeconds = 0.0, pausedSeconds = 0.0, lastStepDone = 0
        private var lastReport: Date?
        func step(_ iter: Int) {
            let done = from + iter
            lastStepDone = done
            let thermal = ProcessInfo.processInfo.thermalState.rawValue
            let now = Date()
            // time since the last report, minus nothing: rests and pauses happen after the report, below
            let worked = lastReport.map { now.timeIntervalSince($0) } ?? 0
            trainSeconds += worked
            progress(done, SplatPacing.note(thermal: thermal))
            if iter % SplatPacing.checkpointEvery == 0, done < steps { checkpoint(localStep: iter) }
            var t = Date()
            while hold.pointee != 0, cancel.pointee == 0 { Thread.sleep(forTimeInterval: 0.1) }
            lastIter = iter
            if let factor = SplatPacing.restFactor(thermal: thermal) {
                // rest a share of the time the last steps took (serious: as long again = half speed)
                pausedSeconds += Date().timeIntervalSince(t)
                let rest = min(2.0, factor * worked)
                if rest > 0 { Thread.sleep(forTimeInterval: rest); restSeconds += rest }
            } else {
                // critical: wait (in short naps, so cancelling still works) until it cools
                while cancel.pointee == 0, SplatPacing.restFactor(thermal: ProcessInfo.processInfo.thermalState.rawValue) == nil {
                    progress(done, SplatPacing.note(thermal: 3))
                    Thread.sleep(forTimeInterval: 2)
                }
                pausedSeconds += Date().timeIntervalSince(t)
            }
            t = Date(); lastReport = t
        }

        private func checkpoint(localStep: Int) {
            let fm = FileManager.default
            let plys = ((try? fm.contentsOfDirectory(at: outDir, includingPropertiesForKeys: nil)) ?? []).filter { $0.pathExtension == "ply" }
            guard let ply = plys.first(where: { Self.number($0) == localStep }) else { return }
            do {
                try SplatPacing.Checkpoint(step: from + localStep, steps: steps, setup: setup).save(ply: ply, to: folder)
                SplatPreview.log.info("checkpoint at step \(self.from + localStep)")
            } catch {
                SplatPreview.log.error("checkpoint not saved: \(error.localizedDescription, privacy: .public)")
            }
            for p in plys where p != ply { try? fm.removeItem(at: p) }   // older exports
        }

        static func number(_ url: URL) -> Int? {
            Int(url.deletingPathExtension().lastPathComponent.split(separator: "_").last ?? "")
        }
    }

    /// cubecl (Brush's GPU layer) tunes its kernels by trying candidates with full-size scratch buffers whenever a
    /// shape is new: at the start and after each refine. On the phone the footprint flipped between about 0.1 and
    /// 2.2-2.4 GB in exactly those moments (13 Nov 08:39 run), on top of the about 1.1 GB the app keeps after a scan.
    /// The least tuning is asked for here (read once, at Brush's first use).
    private static let tuneLess: Void = { setenv("CUBECL_AUTOTUNE_LEVEL", "minimal", 1) }()

    func train(dataset: URL, output: URL, steps: Int, maxSplats: Int, setup: String, resume: SplatPacing.Checkpoint?,
               progress: @escaping @Sendable (Int, String?) -> Void) async throws {
        _ = Self.tuneLess
        let fm = FileManager.default
        let folder = output.deletingLastPathComponent()
        let outDir = folder.appendingPathComponent("brush-out")
        // a run that iOS stopped leaves its memory marks here: keep them before the folder is cleared
        Self.keepMarks(from: outDir, in: folder)
        try? fm.removeItem(at: outDir)
        try fm.createDirectory(at: outDir, withIntermediateDirectories: true)
        // Starting splat: the checkpoint (resume) or the scan's dots (points.ply, Brush's default)
        let initPly = dataset.appendingPathComponent("init.ply")
        try? fm.removeItem(at: initPly)
        if resume != nil { try fm.copyItem(at: folder.appendingPathComponent("checkpoint.ply"), to: initPly) }
        defer { try? fm.removeItem(at: initPly) }
        let from = resume?.step ?? 0
        let box = Box(progress: progress, from: from, steps: steps, folder: folder, outDir: outDir, setup: setup)
        let cap = UInt32(min(Int(Self.maxSplats), maxSplats))
        // iOS stops GPU work in the background: when the app is left, Brush is stopped cleanly (cancel byte) and the
        // job waits in the queue, starting again when the app is back (rk 14:20).
        let left = UnsafeMutablePointer<Bool>.allocate(capacity: 1); left.pointee = false
        defer { left.deallocate() }
        let leaving = NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: nil) { _ in
            left.pointee = true; box.cancel.pointee = 1
        }
        defer { NotificationCenter.default.removeObserver(leaving) }
        let resigning = NotificationCenter.default.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: nil) { _ in
            box.hold.pointee = 1
        }
        let back = NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: nil) { _ in
            box.hold.pointee = 0
        }
        defer { NotificationCenter.default.removeObserver(resigning); NotificationCenter.default.removeObserver(back) }
        let code: BrushTrainExitCode = await withTaskCancellationHandler {
            await withCheckedContinuation { (done: CheckedContinuation<BrushTrainExitCode, Never>) in
                let thread = Thread {
                    let user = Unmanaged.passRetained(box)
                    let r = dataset.path.withCString { ds in
                        outDir.path.withCString { op in
                            var options = BrushTrainOptions(total_train_steps: UInt32(steps - from), refine_every: 200,
                                                            max_resolution: Self.maxResolution,
                                                            export_every: UInt32(SplatPacing.checkpointEvery),
                                                            output_path: op, max_splats: cap,
                                                            sh_degree: Self.shDegree, cancel: UnsafePointer(box.cancel))
                            return train_and_save(ds, &options, { message, userData in
                                guard let userData, message.tag == BrushTraining.rawValue else { return }
                                Unmanaged<Box>.fromOpaque(userData).takeUnretainedValue().step(Int(message.iter))
                            }, user.toOpaque())
                        }
                    }
                    user.release()
                    done.resume(returning: r)
                }
                thread.stackSize = 8 << 20   // Brush's async runtime wants more than a background thread's 512 KB
                thread.qualityOfService = .userInitiated
                thread.start()
            }
        } onCancel: {
            box.cancel.pointee = 1
        }
        // this run's measured times, kept with the scan (SplatPreview sums them into the processing log)
        let times: [String: Any] = ["from": from, "to": box.lastStepDone, "train": (box.trainSeconds * 10).rounded() / 10,
                                    "rest": (box.restSeconds * 10).rounded() / 10, "paused": (box.pausedSeconds * 10).rounded() / 10]
        if let line = try? JSONSerialization.data(withJSONObject: times) {
            let url = folder.appendingPathComponent("train-times.jsonl")
            if let h = try? FileHandle(forWritingTo: url) { h.seekToEndOfFile(); h.write(line + Data("\n".utf8)); try? h.close() }
            else { try? (line + Data("\n".utf8)).write(to: url) }
        }
        try Task.checkCancellation()
        if left.pointee {
            SplatPreview.log.info("Brush stopped: the app was left; the preview starts again when RoomScape is open")
            throw CancellationError()   // the queue keeps the job and runs it when the app is back
        }
        guard code == BrushTrainSuccess else {
            // brush-c writes why into brush-error.txt (RoomScape's patch): into the processing log with it
            let why = (try? String(contentsOf: outDir.appendingPathComponent("brush-error.txt"), encoding: .utf8)) ?? "no reason given"
            SplatPreview.log.error("Brush failed: \(why, privacy: .public)")
            throw NSError(domain: "SplatPreview", code: 10, userInfo: [NSLocalizedDescriptionKey: "Brush couldn't train the preview: " + why])
        }
        // Brush names its export export_<step>.ply: take the last step's
        let plys = (try fm.contentsOfDirectory(at: outDir, includingPropertiesForKeys: nil)).filter { $0.pathExtension == "ply" }
        guard let last = plys.max(by: { (Box.number($0) ?? -1) < (Box.number($1) ?? -1) }) else {
            throw NSError(domain: "SplatPreview", code: 11, userInfo: [NSLocalizedDescriptionKey: "Brush wrote no splat"])
        }
        if fm.fileExists(atPath: output.path) { try fm.removeItem(at: output) }
        try fm.moveItem(at: last, to: output)
        Self.keepMarks(from: outDir, in: folder)
        // refines Brush skipped for memory (brush-process, iOS): kept next to the preview for SplatPreview's summary
        if let skips = try? String(contentsOf: outDir.appendingPathComponent("refine-skips.txt"), encoding: .utf8) {
            let url = folder.appendingPathComponent("refine-skips.txt")
            let old = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
            try? (old + skips).write(to: url, atomically: true, encoding: .utf8)
        }
        try? fm.removeItem(at: outDir)
    }
}
#endif
