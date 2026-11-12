import AVFoundation
import CoreImage
import Foundation
import UIKit
import UserNotifications
import os

/// Photo-real room preview on the phone (rk 12 Nov: "put in the lightweight brush thing … make it start automatically
/// after the scan is done and show a progress bar"). A Gaussian splat of the room, trained on the phone from the
/// scan's recording, as a preview for when there's no PC or cloud; the full version is made on a PC.
///
/// Runs as a job of the processing queue (`RecordingProcessor.Job.splat`), after the scan is saved and its recording
/// is in the scan's folder (the scan and recording are never at risk: this only reads them). Stages:
/// 1. dataset: the sharpest frame in each stretch of the video (at most `maxFrames`, half size), their poses and
///    lens (`SplatDataset`, Nerfstudio transforms.json) and the scan's dots as starting points;
/// 2. training: the `Trainer` (Brush once it's linked in; until then `DotsTrainer`, a stand-in that turns the dots
///    into round blobs so the flow, progress, viewer and .rmsc can be tried end to end);
/// 3. the result: `<capture>/splat/preview.ply` (and splat/view.json, where the viewer starts), in the .rmsc as an
///    optional file. The dataset's pictures are deleted afterwards (they are made again from the recording if needed).
/// GPU work only while the app is open (iOS doesn't allow it in the background): it waits, saying so, when left.
/// Paced by the phone's heat (ThermalGovernor). Off by default (home screen switch "Photo-real preview").
enum SplatPreview {
    static let log = Logger(subsystem: "RoomScape", category: "SplatPreview")

    /// Home screen switch, off by default until a real trainer is linked in.
    static var enabled: Bool {
        get { UserDefaults.standard.bool(forKey: "splatPreview") || ProcessInfo.processInfo.arguments.contains("-splatPreview") }
        set { UserDefaults.standard.set(newValue, forKey: "splatPreview") }
    }

    /// Lightweight settings (rk 12 Nov): half-size pictures, a few hundred frames, a few hundred thousand splats.
    /// Defaults for the PC's quick dataset ("For a PC rebuild"): the three-computer benchmark's dataset A. On the phone
    /// the job sizes itself to the scan (`SplatPacing.plan`).
    static let maxFrames = 300
    static let maxPoints = 200_000

    /// The phone's plan for this scan: from its registered dots (points.bin, 16 bytes each) and video frames
    /// (frames.bin, 120 bytes each).
    static func plan(for scan: SavedScan) -> SplatPacing.Plan {
        func size(_ name: String) -> Int {
            ((try? FileManager.default.attributesOfItem(atPath: scan.captureURL.appendingPathComponent(name).path)[.size]) as? Int) ?? 0
        }
        return SplatPacing.plan(points: size("points.bin") / 16, frames: size("recording/frames.bin") / 120)
    }

    static func folder(_ scan: SavedScan) -> URL { scan.captureURL.appendingPathComponent("splat") }
    static func result(_ scan: SavedScan) -> URL { folder(scan).appendingPathComponent("preview.ply") }
    static func hasResult(_ scan: SavedScan) -> Bool { FileManager.default.fileExists(atPath: result(scan).path) }

    /// What trains the splat from the dataset folder (transforms.json, images/, points.ply) into `output`.
    protocol Trainer: Sendable {
        var name: String { get }
        /// Its settings, kept with checkpoints (a checkpoint from other settings is not carried on).
        var setup: String { get }
        /// Trains `steps` steps in all, carrying on from `resume` (a checkpoint in output's folder) if given, and saving
        /// a checkpoint there every `SplatPacing.checkpointEvery` steps. `progress` gets the steps done in all
        /// (resume.step…steps) and a note when heat is holding it back (nil when not).
        func train(dataset: URL, output: URL, steps: Int, maxSplats: Int, setup: String, resume: SplatPacing.Checkpoint?,
                   progress: @escaping @Sendable (Int, String?) -> Void) async throws
    }

    /// The trainer in use: Brush when its library is linked in, else the stand-in.
    static var trainer: Trainer {
        #if canImport(BrushC)
        BrushTrainer()
        #else
        DotsTrainer()
        #endif
    }

    // MARK: The job

    static func run(on scan: SavedScan, progress: @escaping @Sendable (RecordingProcessor.Progress) -> Void) async throws {
        let fm = FileManager.default
        let dir = folder(scan), dataset = dir.appendingPathComponent("dataset")
        try fm.createDirectory(at: dataset, withIntermediateDirectories: true)
        let note: @Sendable (String?) -> Void = { n in progress(.init(stage: "", fraction: -1, note: n)) }
        let exported = dataset.appendingPathComponent("export.done")
        let plan = plan(for: scan)
        let steps = plan.steps
        log.info("preview plan for \(scan.name, privacy: .public): \(plan.description, privacy: .public)")
        if !fm.fileExists(atPath: exported.path) {
            progress(.init(stage: "Picking sharp pictures", fraction: 0.01, note: nil))
            try await exportDataset(scan: scan, to: dataset, maxFrames: plan.pictures, maxPoints: plan.startPoints,
                                    pace: { await ThermalGovernor.pace(note: note) }) { f in
                progress(.init(stage: "Picking sharp pictures", fraction: 0.01 + 0.24 * f, note: nil))
            }
            fm.createFile(atPath: exported.path, contents: Data())
        }
        try Task.checkCancellation()
        // GPU work only while the app is open
        await waitForForeground(note: note)
        let trainer = self.trainer
        let started = Date()
        let partial = dir.appendingPathComponent("preview-training.ply")
        // Carries on from the last checkpoint if the app was closed or left mid-way (rk 12 Nov: it always restarted
        // from zero), and says so on the card.
        let setup = trainer.setup + " \(plan.maxSplats / 1000)k"
        let resume = SplatPacing.Checkpoint.load(from: dir, steps: steps, setup: setup)
        let from = resume?.step ?? 0
        if let resume { log.info("preview resumes from step \(resume.step) of \(steps)") }
        progress(.init(stage: SplatPacing.stage(step: from, of: steps, minutesLeft: nil), fraction: 0.25 + 0.73 * Float(from) / Float(steps), note: nil))
        if let resume { note("Carrying on from step \(resume.step) (saved before RoomScape closed)") }
        // the heat note when it changes; the "carrying on" note stays for the first 200 steps
        let noteState = OSAllocatedUnfairLock<(last: String?, resumeShown: Bool)>(initialState: (nil, resume != nil))
        try await trainer.train(dataset: dataset, output: partial, steps: steps, maxSplats: plan.maxSplats, setup: setup, resume: resume) { done, heat in
            // time left from this run's own pace (it includes the rests for heat)
            let f = Double(done - from) / Double(max(1, steps - from))
            let left = SplatDataset.minutesLeft(fraction: f, elapsed: Date().timeIntervalSince(started))
            progress(.init(stage: SplatPacing.stage(step: done, of: steps, minutesLeft: left),
                           fraction: 0.25 + 0.73 * Float(done) / Float(steps), note: nil))
            let say = noteState.withLock { st -> Bool in
                if st.resumeShown, done < from + 200, heat == nil { return false }
                defer { st.last = heat; st.resumeShown = false }
                return st.last != heat || st.resumeShown
            }
            if say { note(heat) }
        }
        try Task.checkCancellation()
        progress(.init(stage: "Saving the preview", fraction: 0.99, note: nil))
        if fm.fileExists(atPath: result(scan).path) { try fm.removeItem(at: result(scan)) }
        try fm.moveItem(at: partial, to: result(scan))
        try? fm.removeItem(at: dataset.appendingPathComponent("images"))   // made again from the recording if needed
        SplatPacing.Checkpoint.clear(in: dir)
        // Share link (switch "Share links (test)"): put the preview online at the scan's link. A failed upload
        // doesn't fail the preview; the room's Share menu can try again.
        if RoomLink.enabled, RoomLink.Keychain.uploadKey != nil {
            progress(.init(stage: "Putting the preview online", fraction: 0.995, note: nil))
            do { try await RoomLink.uploadPreview(scan) } catch is CancellationError { throw CancellationError() } catch {
                log.error("share link upload failed: \(error.localizedDescription, privacy: .public)")
            }
        }
        log.info("preview ready for \(scan.name, privacy: .public) (\(trainer.name, privacy: .public), \(Int(Date().timeIntervalSince(started))) s)")
    }

    /// Waits while the app is in the background (GPU work isn't allowed there), saying so in the progress card.
    static func waitForForeground(note: @escaping @Sendable (String?) -> Void) async {
        var said = false
        while await MainActor.run(body: { UIApplication.shared.applicationState == .background }) {
            if !said { note("Paused: open RoomScape to carry on with the photo-real preview"); said = true }
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            if Task.isCancelled { return }
        }
        if said { note(nil) }
    }

    // MARK: Dataset

    /// The recording's sharpest frames (half size) with poses and lens, and the scan's dots as starting points.
    static func exportDataset(scan: SavedScan, to dataset: URL, maxFrames: Int = maxFrames, maxPoints: Int = maxPoints,
                              pace: () async -> Void, progress: (Float) -> Void) async throws {
        let rec = scan.captureURL.appendingPathComponent("recording")
        let frames = try RecordingProcessor.readFrames(rec.appendingPathComponent("frames.bin"))
        guard frames.count > 10 else { throw NSError(domain: "SplatPreview", code: 1, userInfo: [NSLocalizedDescriptionKey: "the recording is too short"]) }
        let images = dataset.appendingPathComponent("images")
        try? FileManager.default.removeItem(at: images)
        try FileManager.default.createDirectory(at: images, withIntermediateDirectories: true)

        let asset = AVURLAsset(url: rec.appendingPathComponent("video.mov"))
        guard let track = try await asset.loadTracks(withMediaType: .video).first else { throw NSError(domain: "SplatPreview", code: 2) }
        let reader = try AVAssetReader(asset: asset)
        // Full-range YCbCr: the luma plane gives the sharpness for free; Core Image turns the picked ones into JPEGs.
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange])
        output.alwaysCopiesSampleData = false
        reader.add(output)
        reader.startReading()
        let ctx = CIContext(options: [.cacheIntermediates: false])
        let sRGB = CGColorSpace(name: CGColorSpace.sRGB)!
        // One frame per stretch of `window` frames (the same windows as SplatDataset.pickSharpest), kept as it's read.
        let window = max(1, Int((Double(frames.count) / Double(maxFrames)).rounded(.up)))
        let start = frames[0].time
        var fi = 0, currentWindow = -1
        var best: (score: Float, index: Int, sample: CMSampleBuffer)?
        var picked: [SplatDataset.Frame] = []
        var view: [String: [Float]]?

        func flush() throws {
            guard let b = best, let buffer = CMSampleBufferGetImageBuffer(b.sample) else { return }
            let f = frames[b.index]
            let w = CVPixelBufferGetWidth(buffer), h = CVPixelBufferGetHeight(buffer)
            let file = String(format: "images/%05d.jpg", b.index)
            try autoreleasepool {
                let img = CIImage(cvPixelBuffer: buffer).transformed(by: CGAffineTransform(scaleX: 0.5, y: 0.5))
                guard let jpeg = ctx.jpegRepresentation(of: img, colorSpace: sRGB,
                                                        options: [CIImageRepresentationOption(rawValue: kCGImageDestinationLossyCompressionQuality as String): 0.85])
                else { return }
                try jpeg.write(to: dataset.appendingPathComponent(file))
            }
            let k = f.k
            let lens = SplatDataset.scaledLens(fx: k[0][0], fy: k[1][1], cx: k[2][0], cy: k[2][1], from: (f.width, f.height), to: (w / 2, h / 2))
            let c = f.pose
            let m: [Float] = [c.columns.0, c.columns.1, c.columns.2, c.columns.3].flatMap { [$0.x, $0.y, $0.z, $0.w] }
            picked.append(.init(file: file, cameraToWorld: m, fx: lens.fx, fy: lens.fy, cx: lens.cx, cy: lens.cy, width: w / 2, height: h / 2))
            if view == nil {   // where the viewer starts: the first picked camera, looking where it looked
                let p = c.columns.3, fwd = -c.columns.2
                view = ["cam": [p.x, p.y, p.z], "look": [p.x + 2 * fwd.x, p.y + 2 * fwd.y, p.z + 2 * fwd.z]]
            }
            best = nil
        }

        while let sample = output.copyNextSampleBuffer() {
            try Task.checkCancellation()
            let t = start + CMSampleBufferGetPresentationTimeStamp(sample).seconds
            while fi + 1 < frames.count, frames[fi + 1].time <= t + 0.001 { fi += 1 }
            let w = fi / window
            if w != currentWindow { try flush(); currentWindow = w; progress(Float(fi) / Float(frames.count)); await pace() }
            guard frames[fi].tracking == 2, let buffer = CMSampleBufferGetImageBuffer(sample) else { continue }
            let s = sharpness(buffer)
            if best == nil || s > best!.score { best = (s, fi, sample) }
        }
        try flush()
        guard picked.count >= 20 else { throw NSError(domain: "SplatPreview", code: 3, userInfo: [NSLocalizedDescriptionKey: "too few sharp pictures in the recording"]) }

        // Starting points: the scan's dots (points.bin: x y z float, r g b a bytes, 16 per point).
        let pts = (try? Data(contentsOf: scan.captureURL.appendingPathComponent("points.bin"))) ?? Data()
        let n = pts.count / 16
        var xyz = [Float](repeating: 0, count: n * 3), rgb = [UInt8](repeating: 0, count: n * 3)
        pts.withUnsafeBytes { raw in
            for i in 0..<n {
                for k in 0..<3 { xyz[i * 3 + k] = Float(bitPattern: raw.loadUnaligned(fromByteOffset: i * 16 + k * 4, as: UInt32.self)) }
                for k in 0..<3 { rgb[i * 3 + k] = raw[i * 16 + 12 + k] }
            }
        }
        try SplatDataset.pointsPLY(xyz: xyz, rgb: rgb, maxPoints: maxPoints).write(to: dataset.appendingPathComponent("points.ply"))
        try SplatDataset.transformsJSON(frames: picked, pointsFile: "points.ply").write(to: dataset.appendingPathComponent("transforms.json"))
        if let view { try JSONSerialization.data(withJSONObject: view).write(to: dataset.deletingLastPathComponent().appendingPathComponent("view.json")) }
        log.info("dataset: \(picked.count) pictures of \(frames.count), \(min(n, maxPoints)) starting points")
    }

    /// Sharpness of a frame: mean absolute Laplacian of the luma, every 4th pixel (cheap, enough to rank neighbours).
    static func sharpness(_ buffer: CVPixelBuffer) -> Float {
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard CVPixelBufferGetPlaneCount(buffer) >= 1, let base = CVPixelBufferGetBaseAddressOfPlane(buffer, 0) else { return 0 }
        let w = CVPixelBufferGetWidthOfPlane(buffer, 0), h = CVPixelBufferGetHeightOfPlane(buffer, 0)
        let row = CVPixelBufferGetBytesPerRowOfPlane(buffer, 0)
        let p = base.assumingMemoryBound(to: UInt8.self)
        var sum = 0, count = 0
        for y in stride(from: 4, to: h - 4, by: 4) {
            for x in stride(from: 4, to: w - 4, by: 4) {
                let c = Int(p[y * row + x]) * 4
                let l = Int(p[y * row + x - 1]) + Int(p[y * row + x + 1]) + Int(p[(y - 1) * row + x]) + Int(p[(y + 1) * row + x])
                sum += abs(c - l); count += 1
            }
        }
        return count > 0 ? Float(sum) / Float(count) : 0
    }

    // MARK: Notification

    /// Asks once (when the switch is turned on) whether the app may say when a preview is ready.
    static func askForNotifications() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    /// "Room preview ready" (a local notification; shows when the app isn't on screen).
    static func notifyReady(_ scan: SavedScan) {
        let content = UNMutableNotificationContent()
        content.title = "Room preview ready"
        content.body = "\(scan.displayName): open it in RoomScape to look around."
        content.sound = .default
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "splat-\(scan.name)", content: content, trigger: nil))
    }
}

/// Stand-in trainer until Brush is linked in: one round blob per scan dot (the dataset's points.ply), so the job,
/// progress, viewer and .rmsc can be tried end to end. Not photo-real.
struct DotsTrainer: SplatPreview.Trainer {
    var name: String { "stand-in (dots as blobs)" }
    var setup: String { "dots" }
    func train(dataset: URL, output: URL, steps: Int, maxSplats: Int, setup: String, resume: SplatPacing.Checkpoint?,
               progress: @escaping @Sendable (Int, String?) -> Void) async throws {
        let ply = try Data(contentsOf: dataset.appendingPathComponent("points.ply"))
        guard let end = ply.range(of: Data("end_header\n".utf8)) else { throw NSError(domain: "SplatPreview", code: 4) }
        let body = ply[end.upperBound...]
        let n = body.count / 15
        var xyz = [Float](repeating: 0, count: n * 3), rgb = [UInt8](repeating: 0, count: n * 3)
        body.withUnsafeBytes { raw in
            for i in 0..<n {
                for k in 0..<3 { xyz[i * 3 + k] = Float(bitPattern: raw.loadUnaligned(fromByteOffset: i * 15 + k * 4, as: UInt32.self)) }
                for k in 0..<3 { rgb[i * 3 + k] = raw[i * 15 + 12 + k] }
            }
        }
        for s in stride(from: 0, through: steps, by: max(1, steps / 20)) { try Task.checkCancellation(); progress(s, nil); try await Task.sleep(nanoseconds: 50_000_000) }
        try SplatDataset.dotsSplatPLY(xyz: xyz, rgb: rgb, maxPoints: SplatPreview.maxPoints).write(to: output)
    }
}
