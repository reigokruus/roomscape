import Foundation
import os
import simd

/// Splat training on the result screen (task 6): trains the scan's splats against its photos on the GPU
/// (`SplatTrainer`), shows the improving splats live, and pauses on request. Paused or left, the trained splats are
/// saved as `trained.bin` next to the scan, and reopening the scan shows them.
@MainActor
final class SplatTrainingRun: ObservableObject {
    /// `done`: it stopped by itself because it had stopped improving (tapping Train again still continues).
    enum State: Equatable { case idle, preparing, training, paused, done, failed(String) }

    @Published private(set) var state: State = .idle
    /// "Step 120 · matches the photos 23% better than the raw scan".
    @Published private(set) var progress = ""
    /// New splats to show, published about every 1.5 s while training and once on pause.
    var onPreview: ([SurfelRecord]) -> Void = { _ in }

    let scan: SavedScan
    private let base: [SurfelRecord]
    private let worker = DispatchQueue(label: "CineX.splat-training", qos: .userInitiated)
    private let running = OSAllocatedUnfairLock(initialState: false)
    /// Only touched on `worker`.
    private let box = TrainerBox()

    /// Splats are drawn as hard discs (SceneKit's blending washes them out). A trained gaussian is shown as a disc of
    /// 2 sigma, whatever its opacity: sizing discs by opacity opened gaps where training made splats see-through
    /// (their neighbours' soft edges filled those in the trainer). Splats that training made mostly transparent are hidden.
    nonisolated static let discSigmas: Float = 2
    nonisolated static let minDisplayOpacity: Float = 0.3

    private let shell: RoomShell

    init(scan: SavedScan, base: [SurfelRecord], shell: RoomShell) {
        self.scan = scan
        self.base = base
        self.shell = shell
    }

    var isTraining: Bool { state == .training || state == .preparing }

    func toggle() { isTraining ? pause() : start() }

    func start() {
        guard !isTraining else { return }
        running.withLock { $0 = true }
        state = box.trainer == nil ? .preparing : .training
        let scan = scan, base = base, shell = shell, box = box, running = running
        worker.async { [weak self] in
            if box.trainer == nil {
                do {
                    let trainer = try Self.makeTrainer(scan: scan, base: base, shell: shell)
                    box.setUp(trainer)
                } catch {
                    let message = (error as? LocalizedError)?.errorDescription ?? "Could not start training (\(error))."
                    DispatchQueue.main.async { self?.state = .failed(message) }
                    return
                }
                DispatchQueue.main.async { if self?.state == .preparing { self?.state = .training } }
            }
            guard let trainer = box.trainer else { return }
            var lastPreview = Date()
            let started = Date(), startIteration = trainer.iteration
            var stop = Stop.paused
            box.startRun()
            while running.withLock({ $0 }) {
                // Like the scanner: stop before the phone gets too hot.
                let thermal = ProcessInfo.processInfo.thermalState
                if thermal == .serious || thermal == .critical {
                    stop = .hot
                    running.withLock { $0 = false }
                    break
                }
                trainer.step(view: box.trainViews.randomElement()!)
                // Most of the gain comes early; once it levels off, more steps only warm the phone.
                if box.check(trainer, runStart: startIteration) {
                    stop = .done
                    running.withLock { $0 = false }
                    break
                }
                if Date().timeIntervalSince(lastPreview) > 1.5 {
                    lastPreview = Date()
                    let ms = Date().timeIntervalSince(started) * 1000 / Double(max(1, trainer.iteration - startIteration))
                    let splats = Self.surfels(trainer.output())
                    let text = box.progressText(iteration: trainer.iteration, ms: ms)
                    DispatchQueue.main.async {
                        guard let self, self.state == .training else { return }
                        self.progress = text
                        self.onPreview(splats)
                    }
                }
            }
            let splats = Self.surfels(trainer.output())
            Surfels.write(splats, to: ScanStore.trainedSurfelsURL(scan))
            let text = box.progressText(iteration: trainer.iteration, ms: nil, stop: stop)
            DispatchQueue.main.async {
                guard let self else { return }
                self.progress = text
                self.state = stop == .done ? .done : .paused
                self.onPreview(splats)
            }
        }
    }

    /// Stops after the current step; the splats are then shown and saved.
    func pause() {
        running.withLock { $0 = false }
    }

    // MARK: Worker

    private enum StartError: LocalizedError {
        case noPhotos
        var errorDescription: String? { "This scan has no photos to train against. Scan again to capture them." }
    }

    nonisolated private static func makeTrainer(scan: SavedScan, base: [SurfelRecord], shell: RoomShell) throws -> SplatTrainer {
        let folder = scan.captureURL
        guard let data = try? Data(contentsOf: folder.appendingPathComponent("keyframes.json")),
              let records = try? JSONDecoder().decode([KeyframeRecord].self, from: data) else { throw StartError.noPhotos }
        let views = records.compactMap { r -> SplatTrainer.View? in
            guard r.cameraToWorld.count == 16 else { return nil }
            let m = r.cameraToWorld
            let toWorld = simd_float4x4(SIMD4(m[0], m[1], m[2], m[3]), SIMD4(m[4], m[5], m[6], m[7]),
                                        SIMD4(m[8], m[9], m[10], m[11]), SIMD4(m[12], m[13], m[14], m[15]))
            return SplatTrainer.view(photo: folder.appendingPathComponent(r.file), photoWidth: r.width, photoHeight: r.height,
                                     fx: r.fx, fy: r.fy, cx: r.cx, cy: r.cy, cameraToWorld: toWorld)
        }
        guard !views.isEmpty else { throw StartError.noPhotos }
        let input = base.map {
            SplatTrainer.Surfel(position: SIMD3($0.x, $0.y, $0.z), normal: SIMD3($0.nx, $0.ny, $0.nz), radius: $0.radius,
                                rgba: $0.rgba, wallAngle: $0.wallAngle)
        }
        let start = Date()
        var (seeds, cell) = SplatTrainer.seeds(from: input, noWall: SurfelRecord.noWall)
        let pinned = pin(&seeds, to: shell)
        let trainer = try SplatTrainer(seeds: seeds, views: views)
        print("training: \(seeds.count) splats (\(Int(cell * 1000)) mm, \(pinned) on the floor or walls) from \(base.count) surfels, "
              + "\(views.count) photos, ready in \(Int(Date().timeIntervalSince(start) * 1000)) ms")
        return trainer
    }

    /// Marks the splats that lie on RoomPlan's floor or a wall (where `Surfels.snap` put their surfels, 5 mm in front):
    /// training may slide them along that surface but not off it, or the drawn floor and walls would hide them.
    nonisolated static func pin(_ seeds: inout [SplatTrainer.Seed], to shell: RoomShell) -> Int {
        let tolerance: Float = 0.015, nudge: Float = 0.005
        var pinned = 0
        for i in seeds.indices {
            let p = seeds[i].position, n = seeds[i].normal
            let hasNormal = simd_length_squared(n) > 0.25
            if abs(p.y - (shell.floorY + nudge)) < tolerance, !hasNormal || abs(n.y) > 0.8 {
                seeds[i].plane = SIMD3(0, 1, 0)
                pinned += 1
                continue
            }
            for wall in shell.walls {
                let d = p - wall.center
                guard abs(simd_dot(d, wall.inward) - nudge) < tolerance,
                      abs(simd_dot(d, wall.right)) <= wall.halfWidth + 0.05, abs(simd_dot(d, wall.up)) <= wall.halfHeight + 0.05,
                      !hasNormal || abs(simd_dot(n, wall.inward)) > 0.8 else { continue }
                seeds[i].plane = wall.inward
                pinned += 1
                break
            }
        }
        return pinned
    }

    /// Trained gaussians as the viewer's hard discs (`SplatScene` draws discs at 1.4x `radius`).
    nonisolated static func surfels(_ splats: [SplatTrainer.Output]) -> [SurfelRecord] {
        var out: [SurfelRecord] = []
        out.reserveCapacity(splats.count)
        for s in splats where s.opacity > minDisplayOpacity {
            let r = s.sigma * discSigmas
            let c = simd_clamp(s.color, SIMD3(repeating: 0), SIMD3(repeating: 1)) * 255
            let rgba = UInt32(c.x.rounded()) | UInt32(c.y.rounded()) << 8 | UInt32(c.z.rounded()) << 16 | 0xFF00_0000
            out.append(SurfelRecord(x: s.position.x, y: s.position.y, z: s.position.z,
                                    nx: s.normal.x, ny: s.normal.y, nz: s.normal.z,
                                    radius: r / 1.4, rgba: rgba, wallAngle: s.wallAngle))
        }
        return out
    }

    /// Why a training run ended.
    private enum Stop { case paused, hot, done }

    /// The trainer and its running error, kept on the worker queue.
    private final class TrainerBox: @unchecked Sendable {
        var trainer: SplatTrainer?
        /// Every 8th photo is kept out of training. The splats are checked against these to see how much better they
        /// got at photos they didn't learn from (the photos they train on keep improving long after these stop: that's
        /// memorising), and training ends once these stop improving.
        private(set) var trainViews: [Int] = []
        private var heldViews: [Int] = []
        private var heldBaseline: [Float] = []
        /// (step, gain on the held-out photos) every `checkEvery` steps of this run.
        private var history: [(iteration: Int, gain: Float)] = []
        private var lastGain: Float?

        static let checkEvery = 250
        /// Done when the held-out photos gained less than `minGain` over the last `window` steps.
        static let window = 1000
        static let minGain: Float = 0.01
        /// Too few photos to keep some out: stop after this many steps.
        static let fixedSteps = 2000

        func setUp(_ trainer: SplatTrainer) {
            let n = trainer.viewCount
            heldViews = n >= 8 ? Array(stride(from: 3, to: n, by: 8)) : []
            trainViews = (0..<n).filter { !heldViews.contains($0) }
            heldBaseline = heldViews.map { trainer.step(view: $0, update: false) }
            self.trainer = trainer
        }

        func startRun() { history = [] }

        /// Measures the held-out photos every `checkEvery` steps; true when training should end.
        func check(_ trainer: SplatTrainer, runStart: Int) -> Bool {
            let done = trainer.iteration - runStart
            guard done > 0, done % Self.checkEvery == 0 else { return false }
            guard !heldViews.isEmpty else { return done >= Self.fixedSteps }
            var ratio: Float = 0
            for (v, base) in zip(heldViews, heldBaseline) where base > 0 {
                ratio += trainer.step(view: v, update: false) / base
            }
            let gain = 1 - ratio / Float(heldViews.count)
            lastGain = gain
            history.append((trainer.iteration, gain))
            guard let then = history.last(where: { $0.iteration <= trainer.iteration - Self.window }) else { return false }
            return gain - then.gain < Self.minGain
        }

        /// Not a progress bar: 100% would mean a pixel-perfect copy of every photo, which noise, blur and moving
        /// people rule out. It's how much smaller the difference to photos it didn't train on is than with the raw scan.
        func progressText(iteration: Int, ms: Double?, stop: Stop? = nil) -> String {
            let match = lastGain.map { "matches the photos \(Int(max(0, $0) * 100))% better than the raw scan" }
            let parts: [String?]
            switch stop {
            case nil:
                parts = ["Step \(iteration)", match, ms.map { String(format: "%.0f ms a step", $0) }]
            case .paused?:
                parts = ["Paused at step \(iteration)", match]
            case .hot?:
                parts = ["Paused at step \(iteration) because the phone got hot", match]
            case .done?:
                parts = ["Done at step \(iteration), it stopped improving", match]
            }
            return parts.compactMap { $0 }.joined(separator: " · ")
        }
    }
}
