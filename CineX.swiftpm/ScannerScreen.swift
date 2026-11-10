import ARKit
import SwiftUI
import RoomPlan
import os

private let log = Logger(subsystem: "SetScan", category: "scan")

/// Drives RoomPlan's RoomCaptureView for the live scan, then builds and saves the room model itself.
///
/// We don't let RoomCaptureView present its own result: we return `false` from `shouldPresent` and run
/// `RoomBuilder` ourselves. That way every outcome (success, tracking failure, processing error) reaches
/// our code, and the UI can never get stuck waiting for a callback that doesn't come.
@MainActor
final class ScanController: NSObject, ObservableObject, RoomCaptureViewDelegate {
    enum Phase: Equatable {
        /// Improve scan: waiting for ARKit to recognise the saved room before RoomPlan starts.
        case relocalizing
        case scanning
        case processing
        case failed(String)
    }

    /// After this long, the scan screen suggests finishing. Long RoomPlan sessions heat the phone and use a lot of memory.
    static let suggestedMaxScanSeconds: TimeInterval = 120

    @Published var phase: Phase = .scanning
    @Published var saved: SavedScan?
    @Published var phaseStarted = Date()
    /// Set when the app ended the scan by itself (memory or heat), so the user knows why.
    @Published var endedEarlyReason: String?
    /// The phone is hot (.serious): the app's capture runs at half rate.
    @Published var powerSaving = false
    /// The scan card: hint and the This spot / Room bars (see `updateHud`), the same as the Android app's.
    @Published var hud = ScanHud()
    /// When a tap last cleared part of the scan (the card says so for a moment).
    private var clearedAt = Date.distantPast
    /// Scan area again: how far the marked areas are scanned, and which way to turn when they're off screen.
    @Published var areaNote: String?
    private var hudTask: Task<Void, Never>?
    private var lastMemoryWarning: Date?
    /// What the screen showed while scanning (test switch "Screen video"), saved with the scan as screen.mp4.
    private var screen: ScreenVideo?
    let captureView: RoomCaptureView
    /// LiDAR points and live discs, read from RoomPlan's own ARKit session.
    let live: LiveCapture
    private var running = false
    /// RoomPlan stopped at its size limit (about 9 × 9 m; rk 10 Nov 20:09) but the scan carries on with ARKit alone:
    /// points, tiles, photos and the recording keep going; RoomPlan's room (walls, doors) is what it had up to then.
    private var roomPlanData: CapturedRoomData?
    @Published private(set) var beyondRoomPlan = false
    private var processingTask: Task<Void, Never>?
    /// The saved scan this pass improves (see `ImproveScan.swift`), and its world map.
    let improving: SavedScan?
    private let worldMap: ARWorldMap?
    /// Improve scan recognises the saved room in a session of its own (shown in `relocalizeView`): RoomPlan, run on a
    /// session holding the saved map, stopped within seconds with "the space is too big for one scan".
    let relocalizeView: ARSCNView?
    private var relocalizeTask: Task<Void, Never>?
    /// Maps the improving pass's coordinates onto the saved scan's (identity unless RoomPlan restarted tracking when it
    /// started; see `measureHandoff`).
    private var alignment = matrix_identity_float4x4
    /// This pass's world map, saved with the scan so it can be improved (again) later.
    private var worldMapTask: Task<Data?, Never>?

    init(improving: SavedScan? = nil) {
        self.improving = improving
        worldMap = improving.flatMap(WorldMapStore.load)
        relocalizeView = worldMap == nil ? nil : ARSCNView(frame: .zero)
        captureView = RoomCaptureView(frame: .zero)
        live = LiveCapture(session: captureView.captureSession.arSession)
        super.init()
        captureView.delegate = self
        // Don't set captureView.captureSession.delegate: taking it over stops RoomCaptureView's live outlines,
        // and the view then never reports the end of the scan, so the app hangs after Done.
        NotificationCenter.default.addObserver(self, selector: #selector(memoryWarning),
                                               name: UIApplication.didReceiveMemoryWarningNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(thermalStateChanged),
                                               name: ProcessInfo.thermalStateDidChangeNotification, object: nil)
    }

    nonisolated required init?(coder: NSCoder) {
        fatalError("not supported")
    }

    nonisolated func encode(with coder: NSCoder) {}

    func start() {
        guard !running else { return }
        running = true
        saved = nil
        endedEarlyReason = nil
        worldMapTask = nil
        if let worldMap {
            relocalize(with: worldMap)
        } else {
            beginCapture()
        }
    }

    /// Runs a separate AR session from the saved map until ARKit recognises the room (tracking goes from relocalising
    /// back to normal), notes where the phone is in the saved scan's coordinates, closes that session, and starts
    /// RoomPlan in a fresh one, like a normal scan.
    private func relocalize(with map: ARWorldMap) {
        guard let session = relocalizeView?.session else { return beginCapture() }
        setPhase(.relocalizing)
        let configuration = ARWorldTrackingConfiguration()
        configuration.initialWorldMap = map
        session.run(configuration, options: [.resetTracking, .removeExistingAnchors])
        relocalizeTask?.cancel()
        ImproveLog.write("finding the saved room of \(improving?.name ?? "?") (map: \(map.anchors.count) anchors, \(map.rawFeaturePoints.points.count) feature points)")
        relocalizeTask = Task { [weak self] in
            var sawRelocalizing = false
            let started = Date()
            var lastNote = Date()
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 250_000_000)
                guard let self, self.phase == .relocalizing else { return }
                guard let frame = session.currentFrame else { continue }
                let camera = frame.camera
                if Date().timeIntervalSince(lastNote) > 5 {
                    lastNote = Date()
                    ImproveLog.write(String(format: "  %.0f s: tracking %@, mapping %d, %d feature points in view",
                                            Date().timeIntervalSince(started), String(describing: camera.trackingState),
                                            frame.worldMappingStatus.rawValue, frame.rawFeaturePoints?.points.count ?? 0))
                }
                switch camera.trackingState {
                case .limited(.relocalizing):
                    sawRelocalizing = true
                case .normal where sawRelocalizing || Date().timeIntervalSince(started) > 2:
                    ImproveLog.write("recognised the room after \(String(format: "%.1f", Date().timeIntervalSince(started))) s (saw relocalising: \(sawRelocalizing))")
                    let before = camera.transform
                    ImproveLog.write("  pose at recognition: \(ImproveLog.pose(before))")
                    session.pause()
                    self.beginCapture()
                    self.measureHandoff(before: before, recognisedAt: Date())
                    return
                default:
                    break
                }
            }
        }
    }

    /// The pose where the saved room was recognised (saved scan's coordinates) against RoomPlan's first tracked pose
    /// (its fresh session's coordinates), while the phone is held still between the two: a first guess for mapping the
    /// pass onto the saved scan, which `PassRegistration` then fits on the LiDAR points.
    private func measureHandoff(before: simd_float4x4, recognisedAt: Date) {
        let session = captureView.captureSession.arSession
        Task { [weak self] in
            while Date().timeIntervalSince(recognisedAt) < 8 {
                try? await Task.sleep(nanoseconds: 20_000_000)
                guard let self, self.phase == .scanning else { return }
                guard let camera = session.currentFrame?.camera, case .normal = camera.trackingState else { continue }
                self.alignment = ImproveAlignment.yawAndShift(from: camera.transform, to: before)
                // The saved scan's outline, from its coordinates into this pass's.
                self.live.ghost.setTransform(self.alignment.inverse)
                ImproveLog.write(String(format: "RoomPlan tracking after %.1f s: ", Date().timeIntervalSince(recognisedAt))
                                 + "\(ImproveLog.pose(camera.transform)) <- saved room \(ImproveLog.pose(before))")
                return
            }
            ImproveLog.write("RoomPlan didn't track within 8 s; fitting the pass from scratch")
        }
    }

    private func beginCapture() {
        setPhase(.scanning)
        beyondRoomPlan = false
        roomPlanData = nil
        // No coaching overlay (the animated phone over a grid, "Move device to start" and the other prompts): the
        // live mesh already shows where the scan has been.
        // Hardware use while scanning (PerfLog): kept with the scan as perf-scan.jsonl (perf-continue for a pass).
        let perf = PerfLog()
        perf.start(FileManager.default.temporaryDirectory.appendingPathComponent("perf-scan-\(UUID().uuidString).jsonl"), stage: "scanning")
        perf.mark("before RoomPlan")
        var configuration = RoomCaptureSession.Configuration()
        configuration.isCoachingEnabled = false
        captureView.captureSession.run(configuration: configuration)
        perf.mark("RoomPlan running")
        live.start()
        perf.mark("live capture started", sizes: live.memorySizes)
        // Continue scan: show what the saved scan already covers, in blue, once it's lined up.
        if let improving, let device = live.device {
            let ghost = live.ghost
            ghost.activate()
            Task.detached(priority: .utility) {
                if let mesh = ScanMesh.read(from: improving.captureURL.appendingPathComponent(ScanMesh.fileName)) {
                    ghost.load(mesh, device: device)
                }
            }
        }
        startHud()
        if RawRecorder.enabled { live.recorder = RawRecorder() }
        if ScreenVideo.enabled { let s = ScreenVideo(); screen = s; s.begin() }
        live.perf = perf
        live.mesh?.perf = perf
        live.attachDiscs(to: captureView)
        live.pinPreviewTopRight(in: captureView)
        lastMemoryWarning = nil
        // Already warm from a previous scan: start in power saving.
        powerSaving = ProcessInfo.processInfo.thermalState == .serious
        if powerSaving { live.setPowerSaving(true) }
    }

    /// User tapped Done. RoomPlan answers through `shouldPresent`, where processing starts.
    /// The AR session keeps running for a moment so its world map can be saved with the scan (for Improve scan).
    func finish() {
        guard phase == .scanning, running else { return }
        setPhase(.processing)
        running = false
        live.stop()
        let session = captureView.captureSession.arSession
        if beyondRoomPlan, let data = roomPlanData {
            // RoomPlan already ended (size limit): build its room from what it had, with all the points
            worldMapTask = Task {
                let map = await WorldMapStore.capture(from: session)
                session.pause()
                return map
            }
            process(data, sessionError: nil)
            return
        }
        captureView.captureSession.stop(pauseARSession: false)
        // Continue scan: move this pass's map onto the saved scan's coordinates (by the hand-off guess), so it can
        // replace the saved map and the next Continue scan recognises the new areas too. The points and photos
        // were already copied out, in the pass's own coordinates.
        if improving != nil, alignment != matrix_identity_float4x4 {
            session.setWorldOrigin(relativeTransform: alignment.inverse)
        }
        worldMapTask = Task {
            let map = await WorldMapStore.capture(from: session)
            session.pause()
            return map
        }
    }

    func cancel() {
        log.info("cancel")
        RescanTargets.current = []
        if let recorder = live.recorder {
            live.recorder = nil
            recorder.finish { folder, _ in try? FileManager.default.removeItem(at: folder) } // Cancel discards the scan
        }
        if let s = screen { screen = nil; s.end { file in if let file { try? FileManager.default.removeItem(at: file) } } }
        live.perf?.stop()
        live.perf = nil
        if improving != nil { ImproveLog.write("cancelled while \(phase)") }
        processingTask?.cancel()
        processingTask = nil
        relocalizeTask?.cancel()
        relocalizeTask = nil
        live.stop()
        if running {
            running = false
            if phase == .relocalizing {
                relocalizeView?.session.pause()
            } else if beyondRoomPlan {
                captureView.captureSession.arSession.pause()   // RoomPlan already ended at its size limit
            } else {
                captureView.captureSession.stop()
            }
        }
    }

    func retry() {
        cancel()
        start()
    }

    /// Ends the scan from the app's side (low memory or heat), keeping what was captured so far.
    func endEarly(_ reason: String) {
        guard phase == .scanning, running else { return }
        log.warning("ending scan early: \(reason, privacy: .public)")
        endedEarlyReason = reason
        finish()
    }

    /// Twice a second while scanning: what the scan card says (the same rules and words as the Android app).
    private func startHud() {
        hudTask?.cancel()
        hud = ScanHud()
        let session = captureView.captureSession.arSession
        hudTask = Task { [weak self] in
            var lastPosition: SIMD3<Float>?
            var lastTime: TimeInterval = 0
            var speed: Float = 0
            var tooFast = false
            var shown: (hint: String, level: Int, at: Date)?
            var scanSeconds: Double = 0, lastTick = Date()
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 500_000_000)
                guard let self, self.phase == .scanning else { return }
                guard let frame = session.currentFrame else { continue }
                if case .normal = frame.camera.trackingState { scanSeconds += min(1, Date().timeIntervalSince(lastTick)) }
                lastTick = Date()
                let t = frame.camera.transform.columns.3
                let position = SIMD3<Float>(t.x, t.y, t.z)
                if let lastPosition, frame.timestamp > lastTime {
                    let now = simd_distance(position, lastPosition) / Float(frame.timestamp - lastTime)
                    speed = speed * 0.5 + now * 0.5
                }
                lastPosition = position
                lastTime = frame.timestamp
                tooFast = speed > (tooFast ? 0.45 : 0.6) // a slow walk (~0.4 m/s) is fine
                let cloud = self.live.cloud
                var spot = cloud?.detailHere ?? -1
                // What you get is what you see (LivePreview): "This spot" and "Room" count surface that has its
                // photo, not only its shape.
                let filled = self.live.mesh?.filledShare
                if let filled, filled.room > 0 || filled.inView > 0, spot >= 0 { spot = min(spot, filled.inView) }
                let points = cloud?.count ?? 0
                var (hint, level) = ScanHud.hint(tracking: frame.camera.trackingState, spot: spot, points: points, tooFast: tooFast)
                if Date().timeIntervalSince(self.clearedAt) < 3 { (hint, level) = ("Cleared, scan that spot again", 1) }
                // Keep a hint up for 1.5 s, unless a warning replaces it.
                if let s = shown, s.hint != hint, Date().timeIntervalSince(s.at) < 1.5, !(level == 2 && s.level < 2) {
                    (hint, level) = (s.hint, s.level)
                } else if shown?.hint != hint {
                    shown = (hint, level, Date())
                }
                var room = await Task.detached(priority: .utility) { cloud?.solidShare() ?? 0 }.value
                // What you get is what you see (LivePreview): the bars count surface that has its photo, not only
                // its shape.
                if let filled, filled.room > 0 || filled.inView > 0 { room *= filled.room }
                self.areaNote = Self.areaNote(frame: frame, cloud: cloud)
                let justCleared = Date().timeIntervalSince(self.clearedAt) < 3
                self.hud = ScanHud(hint: hint, level: level, spot: justCleared ? min(max(0, spot), self.hud.spot) : max(0, spot),
                                   room: justCleared ? room : max(self.hud.room, room), seconds: Int(scanSeconds))
            }
        }
    }

    /// Tap to clear (rk 9 Nov): wipes our own points and live mesh in the tapped circle so the spot can be scanned
    /// again. RoomPlan's own outlines can't be edited; they redo themselves as you scan.
    func clearTapped(at point: CGPoint, radius: CGFloat) {
        guard phase == .scanning else { return }
        live.clear(at: point, radius: radius) { [weak self] removed in
            guard let self, removed > 0 else { return }
            self.clearedAt = Date()
            self.hud = ScanHud(hint: "Cleared, scan that spot again", level: 1, spot: 0,
                               room: self.live.cloud?.solidShare() ?? self.hud.room, seconds: self.hud.seconds)
        }
    }

    /// "This area: x%" (solid share of the points scanned in the marked areas), and a turn hint when they're off screen.
    private static func areaNote(frame: ARFrame, cloud: LiveCloud?) -> String? {
        let targets = RescanTargets.current
        guard !targets.isEmpty else { return nil }
        var solid = 0, all = 0
        if let cloud {
            let n = cloud.count, step = max(1, n / 60_000)
            let pts = cloud.buffer.contents().bindMemory(to: LivePoint.self, capacity: n)
            for i in stride(from: 0, to: n, by: step) where RescanTargets.inside(SIMD3(pts[i].x, pts[i].y, pts[i].z), targets) {
                all += 1
                if pts[i].rgba >> 24 == 255 { solid += 1 }
            }
        }
        var note = "This area: \(all < 20 ? 0 : Int((Float(solid) / Float(all) * 100).rounded()))%"
        // Off screen: which way to turn to the nearest marked area.
        let view = frame.camera.viewMatrix(for: .portrait)
        if let t = targets.min(by: { simd_distance($0, SIMD3(frame.camera.transform.columns.3.x, frame.camera.transform.columns.3.y, frame.camera.transform.columns.3.z)) < simd_distance($1, SIMD3(frame.camera.transform.columns.3.x, frame.camera.transform.columns.3.y, frame.camera.transform.columns.3.z)) }) {
            let c = view * SIMD4(t, 1) // camera: x right, y up, looking down -z
            let ahead = -c.z
            let ax = atan2(c.x, max(0.01, ahead)), ay = atan2(c.y, max(0.01, ahead))
            if ahead < 0.1 || abs(ax) > 0.5 || abs(ay) > 0.6 {
                let side = abs(c.x) >= abs(c.y) || ahead < 0.1 ? (c.x >= 0 ? "right" : "left") : (c.y >= 0 ? "up" : "down")
                note += " · turn \(side) to the marked area"
            }
        }
        return note
    }

    private func setPhase(_ newPhase: Phase) {
        log.info("phase -> \(String(describing: newPhase), privacy: .public)")
        phase = newPhase
        phaseStarted = Date()
    }

    // MARK: System pressure

    /// iOS sends memory warnings early, as a cue to free caches. The first one makes the capture give memory back
    /// (coarser cells, fewer photos); only a second one within 30 s ends the scan.
    @objc nonisolated private func memoryWarning() {
        Task { @MainActor in
            let now = Date()
            if let last = self.lastMemoryWarning, now.timeIntervalSince(last) < 30 {
                self.endEarly("The phone was running low on memory, so the scan was finished early and what you scanned so far was kept.")
            } else {
                log.warning("memory warning: shedding capture memory")
                self.live.shedMemory()
            }
            self.lastMemoryWarning = now
        }
    }

    /// Apple's guidance: reduce work at .serious, stop at .critical. RoomPlan stops by itself (deviceTooHot) if it
    /// must; until then the app's own extra work is halved so the scan can be finished.
    @objc nonisolated private func thermalStateChanged() {
        let state = ProcessInfo.processInfo.thermalState
        Task { @MainActor in
            log.info("thermal state \(state.rawValue)")
            switch state {
            case .critical:
                self.endEarly("The phone got too hot, so the scan was finished early and what you scanned so far was kept.")
            case .serious:
                self.powerSaving = true
                self.live.setPowerSaving(true)
            default:
                if self.powerSaving {
                    self.powerSaving = false
                    self.live.setPowerSaving(false)
                }
            }
        }
    }

    // MARK: RoomCaptureViewDelegate

    /// Called whenever the session ends: after Done, or on its own after an error such as world tracking failure.
    nonisolated func captureView(shouldPresent roomDataForProcessing: CapturedRoomData, error: Error?) -> Bool {
        Task { @MainActor in
            self.process(roomDataForProcessing, sessionError: error)
        }
        return false
    }

    nonisolated func captureView(didPresent processedResult: CapturedRoom, error: Error?) {}

    // MARK: Processing

    private func process(_ data: CapturedRoomData, sessionError: Error?) {
        log.info("session ended, error: \(String(describing: sessionError), privacy: .public)")
        // Too big for RoomPlan: keep scanning without it (a new scan only; Continue scan keeps the old behaviour).
        if let error = sessionError as? RoomCaptureSession.CaptureError, case .exceedSceneSizeLimit = error,
           phase == .scanning, running, improving == nil, !beyondRoomPlan {
            roomPlanData = data
            beyondRoomPlan = true
            let session = captureView.captureSession.arSession
            let configuration = ARWorldTrackingConfiguration()
            if ARWorldTrackingConfiguration.supportsFrameSemantics(.sceneDepth) { configuration.frameSemantics = [.sceneDepth] }
            configuration.worldAlignment = .gravity
            session.run(configuration)   // no reset options: the same coordinates, the scan carries on
            log.info("RoomPlan reached its size limit: scanning on with ARKit only")
            live.perf?.mark("RoomPlan size limit: scanning on without it")
            return
        }
        running = false
        live.stop()
        // The session ended with an error and the user didn't ask to finish. Some of RoomPlan's own stops (area too
        // big, phone too hot) still hand over valid data for what was scanned so far: build that instead of throwing
        // the whole scan away. Others (lost tracking) leave data that can't be trusted: let the user decide.
        if let sessionError, phase == .scanning {
            guard let reason = Self.keptScanReason(for: sessionError) else {
                setPhase(.failed(Self.message(for: sessionError)))
                return
            }
            endedEarlyReason = reason
            if improving != nil { ImproveLog.write("RoomPlan stopped: \(sessionError); keeping the pass") }
        }
        guard phase == .processing || phase == .scanning else { return }
        if phase != .processing { setPhase(.processing) }

        processingTask?.cancel()
        processingTask = Task {
            do {
                let builder = RoomBuilder(options: [.beautifyObjects])
                // Improving keeps the saved room model, so a pass RoomPlan can't make a room from still adds its points.
                let room: CapturedRoom?
                if self.improving != nil {
                    room = try? await builder.capturedRoom(from: data)
                } else {
                    room = try await builder.capturedRoom(from: data)
                }
                try Task.checkCancellation()
                log.info("built room: \(room?.walls.count ?? -1) walls, \(room?.objects.count ?? -1) objects")
                if self.improving == nil, room?.walls.isEmpty ?? true {
                    // After a size stop, the size is the useful thing to explain, not the missing walls.
                    self.setPhase(.failed(sessionError.map(Self.message(for:))
                        ?? "No walls were found in this scan. Stand inside the room, point the camera at a wall from 1–3 m away, and turn slowly until white outlines appear on every wall."))
                    return
                }
                let capture = await self.live.snapshot()
                let worldMap = await self.worldMapTask?.value
                log.info("world map: \(worldMap?.count ?? 0, privacy: .public) bytes")
                let improving = self.improving, alignment = self.alignment
                let saved = try await Task.detached(priority: .userInitiated) {
                    if let improving {
                        return try ScanStore.improve(improving, with: capture, room: room, worldMap: worldMap,
                                                     alignment: alignment)
                    }
                    guard let room else { throw CancellationError() } // not reached: checked above
                    let saved = try ScanStore.save(room, capture: capture)
                    if let worldMap { WorldMapStore.save(worldMap, for: saved) }
                    return saved
                }.value
                RescanTargets.current = [] // used by the merge just now
                if let perf = self.live.perf, let file = perf.file {
                    self.live.perf = nil
                    perf.stop()
                    perf.move(file, into: saved.captureURL, as: self.improving != nil ? "perf-continue" : "perf-scan")
                }
                try Task.checkCancellation()
                log.info("saved \(saved.name, privacy: .public)")
                // The raw recording goes into the scan's folder (kept; nothing deletes it automatically).
                if let recorder = self.live.recorder {
                    self.live.recorder = nil
                    recorder.finish { folder, bytes in
                        let main = RawRecorder.move(folder, into: saved)
                        log.info("raw recording: \(recorder.frames) frames, \(bytes / 1_000_000) MB")
                        // Process later: in the background, now that the recording is in the scan's folder.
                        if main { Task { @MainActor in ProcessingQueue.shared.scanSaved(saved) } }
                    }
                }
                if FlatTiles.enabled, let tiles = self.live.tiles {
                    let n = tiles.save(to: saved.captureURL)
                    log.info("flat tiles: \(n) saved; \(tiles.stats(), privacy: .public)")
                }
                if let s = self.screen {
                    self.screen = nil
                    s.end { file in
                        if let file { ScreenVideo.move(file, into: saved.captureURL) }
                        log.info("screen video: \(s.frames) frames")
                    }
                }
                self.saved = saved
            } catch is CancellationError {
                // User closed the scanner.
            } catch let error as ScanStore.ImproveError {
                self.setPhase(.failed(error.localizedDescription))
            } catch {
                log.error("processing failed: \(String(describing: error), privacy: .public)")
                self.setPhase(.failed("Could not build the room model. \(Self.message(for: error))"))
            }
        }
    }

    private static func message(for error: Error) -> String {
        if let captureError = error as? RoomCaptureSession.CaptureError {
            switch captureError {
            case .worldTrackingFailure:
                return "The phone lost track of where it is. Scan again in good light, move slowly, and keep the camera pointed at walls and furniture, not at blank surfaces or the ceiling."
            case .exceedSceneSizeLimit:
                return "The scanned area got bigger than RoomPlan can handle (about 9 × 9 m). Scan one room at a time: stay inside it, and don't point the camera through open doors, large windows or at mirrors. The depth sensor sees through or into them and counts that space as part of the room."
            case .deviceTooHot:
                return "Scanning stopped because the phone got too hot. Let it cool down for a few minutes and try again."
            default:
                break
            }
        }
        return error.localizedDescription
    }

    /// For RoomPlan stops that still leave a usable scan, what to tell the user while the scan is built. nil means
    /// the captured data can't be trusted and the scan fails.
    private static func keptScanReason(for error: Error) -> String? {
        guard let captureError = error as? RoomCaptureSession.CaptureError else { return nil }
        switch captureError {
        case .exceedSceneSizeLimit:
            return "The scanned area reached RoomPlan's size limit (about 9 × 9 m), so the scan stopped there. What you scanned so far was kept."
        case .deviceTooHot:
            return "The phone got too hot, so RoomPlan stopped the scan. What you scanned so far was kept."
        default:
            return nil
        }
    }
}

/// The camera while Improve scan looks for the saved room (its own AR session).
private struct RelocalizeCamera: UIViewRepresentable {
    let view: ARSCNView
    func makeUIView(context: Context) -> ARSCNView { view }
    func updateUIView(_ uiView: ARSCNView, context: Context) {}
}

private struct RoomCaptureRepresentable: UIViewRepresentable {
    let controller: ScanController

    func makeUIView(context: Context) -> RoomCaptureView {
        controller.captureView
    }

    func updateUIView(_ uiView: RoomCaptureView, context: Context) {}
}

struct ScannerScreen: View {
    let onFinished: (SavedScan) -> Void
    let onCancel: () -> Void
    @StateObject private var controller: ScanController
    @Environment(\.scenePhase) private var scenePhase
    @State private var confirmCancel = false
    @State private var flash: ClearFlash?
    /// Clearing a spot while scanning (press and hold): off for now, rk 10 Nov 11:20 (the same on Android).
    static let clearWhileScanning = false
    /// Press and hold to clear: where the finger is down and since when (nil = no hold).
    @State private var hold: (at: CGPoint, since: Date, id: UUID)?

    /// `improving`: a saved scan to add a second pass to (Improve scan), instead of making a new one.
    init(improving: SavedScan? = nil, onFinished: @escaping (SavedScan) -> Void, onCancel: @escaping () -> Void) {
        self.onFinished = onFinished
        self.onCancel = onCancel
        _controller = StateObject(wrappedValue: ScanController(improving: improving))
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            RoomCaptureRepresentable(controller: controller)
                .ignoresSafeArea()
                .onAppear { controller.start() }
            if controller.phase == .relocalizing, let view = controller.relocalizeView {
                RelocalizeCamera(view: view).ignoresSafeArea()
            }
            // Press and hold to clear (0.9 s, a ring fills under the finger; a tap did it until 10 Nov, and a first-time
            // user taps by instinct): wipes the scan in a circle around it (11% of the screen's short side) so it can
            // be scanned again. The card and buttons above take their own taps. The same gesture as the Android app.
            if controller.phase == .scanning, Self.clearWhileScanning {
                GeometryReader { g in
                    Color.clear
                        .contentShape(Rectangle())
                        .gesture(DragGesture(minimumDistance: 0)
                            .onChanged { e in
                                if hold == nil {
                                    let id = UUID()
                                    hold = (e.startLocation, Date(), id)
                                    Task { @MainActor in
                                        try? await Task.sleep(nanoseconds: 900_000_000)
                                        guard let h = hold, h.id == id else { return }
                                        hold = nil
                                        let r = min(g.size.width, g.size.height) * 0.11
                                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                                        controller.clearTapped(at: h.at, radius: r)
                                        flash = ClearFlash(center: h.at, radius: r)
                                        withAnimation(.easeOut(duration: 0.7)) { flash?.visible = false }
                                    }
                                } else if let h = hold, hypot(e.location.x - h.at.x, e.location.y - h.at.y) > 12 {
                                    hold = nil   // moved: not a hold
                                }
                            }
                            .onEnded { _ in hold = nil })
                    if let h = hold {
                        TimelineView(.animation) { ctx in
                            let f = min(1, ctx.date.timeIntervalSince(h.since) / 0.9)
                            ZStack {
                                Circle().stroke(Color.black.opacity(0.4), lineWidth: 5)
                                Circle().trim(from: 0, to: f).stroke(Color.white, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                                    .rotationEffect(.degrees(-90))
                            }
                            .frame(width: 60, height: 60)
                            .position(h.at)
                        }
                        .allowsHitTesting(false)
                    }
                    if let flash {
                        Circle()
                            .fill(Color.white.opacity(0.25))
                            .overlay(Circle().stroke(Color.white.opacity(0.8), lineWidth: 2))
                            .frame(width: flash.radius * 2, height: flash.radius * 2)
                            .position(flash.center)
                            .opacity(flash.visible ? 1 : 0)
                            .allowsHitTesting(false)
                    }
                }
                .ignoresSafeArea()
            }

            VStack(spacing: 12) {
                switch controller.phase {
                case .relocalizing:
                    VStack(spacing: 8) {
                        ProgressView()
                        Text("Looking for the room…")
                            .font(.headline)
                        Text("Slowly point the phone at the walls and furniture you scanned before. When it recognises them, hold the phone still for a second while scanning starts.")
                            .font(.callout)
                            .multilineTextAlignment(.center)
                        TimelineView(.periodic(from: controller.phaseStarted, by: 1)) { context in
                            if context.date.timeIntervalSince(controller.phaseStarted) > 20 {
                                Text("Still looking. Try the spot and the direction you started from, in the same light.")
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                                    .multilineTextAlignment(.center)
                            }
                        }
                    }
                    .padding()
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                    Button("Cancel") { close() }
                        .buttonStyle(.bordered)
                        .background(.regularMaterial, in: Capsule())
                case .scanning:
                    // Cancel and Done, the same as on the Android app.
                    HStack(spacing: 16) {
                        // Cancel throws the scan away (a Continue scan pass only; the saved room is kept). After a
                        // minute of scanning it asks first, the same as on Android.
                        Button {
                            if Date().timeIntervalSince(controller.phaseStarted) < 60 { close() } else { confirmCancel = true }
                        } label: { Text("Cancel").frame(minWidth: 80) }
                            .buttonStyle(.bordered)
                            .buttonBorderShape(.capsule)
                            // Solid backing so the label stays readable over the live mesh.
                            .background(Color(white: 0.17).opacity(0.9), in: Capsule())
                        Button { controller.finish() } label: { Text("Done").fontWeight(.semibold).foregroundStyle(.black).frame(minWidth: 80) }
                            .buttonStyle(.borderedProminent)
                            .buttonBorderShape(.capsule)
                    }
                case .processing:
                    VStack(spacing: 8) {
                        if let reason = controller.endedEarlyReason {
                            Text(reason)
                                .font(.callout)
                                .multilineTextAlignment(.center)
                        }
                        ProgressView(controller.improving == nil ? "Building the room model…" : "Adding this pass to the saved scan…")
                        // A ticking counter shows the app is alive. Big rooms can take up to a minute.
                        TimelineView(.periodic(from: controller.phaseStarted, by: 1)) { context in
                            Text("\(Int(context.date.timeIntervalSince(controller.phaseStarted))) s")
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding()
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                    Button("Cancel") { close() }
                        .buttonStyle(.bordered)
                case .failed(let message):
                    Text(message)
                        .multilineTextAlignment(.center)
                        .padding()
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                    HStack(spacing: 16) {
                        Button("Close") { close() }
                            .buttonStyle(.bordered)
                        Button("Scan again") { controller.retry() }
                            .buttonStyle(.borderedProminent)
                    }
                }
            }
            .controlSize(.large)
            .padding(.horizontal, 16)
            .padding(.bottom, 32)

            // The scan card at the top: one hint and two progress bars, as on the Android app.
            if controller.phase == .scanning {
                TimelineView(.periodic(from: controller.phaseStarted, by: 1)) { context in
                    ScanHudCard(hud: controller.hud, notes: scanNotes(elapsed: context.date.timeIntervalSince(controller.phaseStarted)))
                }
                .padding(.horizontal, 12)
                .padding(.top, 8)
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { controller.live.previewTop = $0 + 16 }
                .frame(maxHeight: .infinity, alignment: .top)
            }
        }
        .alert("Discard this scan?", isPresented: $confirmCancel) {
            Button("Discard", role: .destructive) { close() }
            Button("Keep scanning", role: .cancel) {}
        } message: {
            Text(controller.improving != nil ? "The new pass is thrown away. The saved room stays as it was."
                                             : "Everything scanned so far is thrown away.")
        }
        .onChange(of: controller.saved) { _, saved in
            if let saved { onFinished(saved) }
        }
        .onChange(of: scenePhase) { _, newPhase in
            // ARKit can't keep tracking in the background, so a backgrounded scan always fails. Say so plainly.
            if newPhase == .background, controller.phase == .scanning || controller.phase == .relocalizing {
                controller.cancel()
                controller.phase = .failed("Scanning stopped because the app went to the background. Tap Scan again to restart.")
            }
        }
    }

    /// The smaller lines under the hint: Continue scan, heat, and a long scan.
    private func scanNotes(elapsed: TimeInterval) -> [String] {
        var notes: [String] = []
        if let area = controller.areaNote { notes.append(area) }
        if controller.improving != nil { notes.append("✓ Saved room recognised. Keep scanning, the saved part is shown in blue") }
        if controller.beyondRoomPlan { notes.append("Large space: points and tiles keep going; walls and doors come from the first 9 × 9 m") }
        if controller.powerSaving { notes.append("The phone is warm, so the live mesh updates less often. Keep scanning") }
        if elapsed > ScanController.suggestedMaxScanSeconds { notes.append("Long scans heat up the phone. Tap Done once every wall is covered in white") }
        return notes
    }

    private func close() {
        controller.cancel()
        onCancel()
    }
}

/// What the scan card shows. The rules and the words are the same as the Android app's (`ScanRenderer.postHud`).
struct ScanHud: Equatable {
    var hint = "Point at the room and move the phone slowly"
    /// 0 fine, 1 keep going, 2 a warning (red).
    var level = 1
    /// This spot: share of solid surface where the phone points, 0…1.
    var spot: Float = 0
    /// Room: share of everything scanned that is solid, 0…1; never goes down.
    var room: Float = 0
    /// Scan length (rk 10 Nov): seconds of tracking so far (not while lost).
    var seconds = 0

    static let spotDone: Float = 0.85

    static func hint(tracking: ARCamera.TrackingState, spot: Float, points: Int, tooFast: Bool) -> (String, Int) {
        switch tracking {
        case .notAvailable:
            return ("Lost track: move slowly, pointing at the floor or furniture", 2)
        case .limited(let reason):
            switch reason {
            case .initializing: return ("Point at the room and move the phone slowly", 1)
            case .excessiveMotion: return ("Lost track: moving too fast. Slow down", 2)
            case .insufficientFeatures: return ("Lost track: point at something with texture, not a plain wall", 2)
            default: return ("Lost track: move slowly, pointing at the floor or furniture", 2)
            }
        case .normal:
            break
        }
        // Points-only (as on Android): the hints talk about points, not a mesh turning white
        let pointsOnly = LivePreview.livePoints
        if spot < 0 || points < 3000 {
            return (pointsOnly ? "Walk slowly and sway the phone over the room: points appear as it's captured"
                    : "Walls and objects get covered in a mesh as they're found. Walk slowly and sway the phone over bare or orange areas", 1)
        }
        if tooFast && spot < 0.7 { return ("Too fast. Slow down and sway gently over this spot", 2) }
        if spot < 0.35 { return (pointsOnly ? "Sway the phone slowly over this spot" : "Sway the phone slowly over this spot until the mesh covers it in white", pointsOnly ? 1 : 2) }
        if spot < spotDone { return ("Good. Keep swaying gently here a little longer", 1) }
        return (pointsOnly ? "This spot is done. Move on" : "This spot is done. Move on to the next orange area", 0)
    }
}

/// The scan card: dark, the hint in white (red for warnings), smaller notes, and the This spot / Room bars in orange
/// (This spot turns green when done). The same as the Android app's.
struct ScanHudCard: View {
    let hud: ScanHud
    let notes: [String]

    static let accent = Color(red: 0.93, green: 0.69, blue: 0.30)
    static let green = Color(red: 0.20, green: 0.82, blue: 0.48)
    static let warning = Color(red: 1, green: 0.42, blue: 0.36)

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(hud.hint)
                .font(.headline)
                .foregroundStyle(hud.level == 2 ? Self.warning : .white)
                .fixedSize(horizontal: false, vertical: true)
                .animation(nil, value: hud.hint)
            ForEach(notes, id: \.self) { note in
                Text(note)
                    .font(.footnote)
                    .foregroundStyle(.white.opacity(0.8))
                    .fixedSize(horizontal: false, vertical: true)
            }
            bar("This spot", hud.spot, hud.spot >= ScanHud.spotDone ? Self.green : Self.accent)
                .padding(.top, 4)
            bar("Room", hud.room, Self.accent)
            Text(String(format: "%d:%02d", hud.seconds / 60, hud.seconds % 60))
                .font(.caption2.monospacedDigit())
                .foregroundStyle(.white.opacity(0.6))
                .frame(maxWidth: .infinity, alignment: .trailing)
                .accessibilityLabel("Scan length \(hud.seconds / 60) minutes \(hud.seconds % 60) seconds")
        }
        .padding(.horizontal, 16)
        .padding(.top, 12)
        .padding(.bottom, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(red: 0.11, green: 0.11, blue: 0.118).opacity(0.85), in: RoundedRectangle(cornerRadius: 14))
        .environment(\.colorScheme, .dark)
    }

    private func bar(_ label: String, _ value: Float, _ color: Color) -> some View {
        HStack(spacing: 8) {
            Text(label)
                .font(.footnote)
                .foregroundStyle(.white.opacity(0.8))
                .frame(width: 80, alignment: .leading)
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(.white.opacity(0.33))
                    Capsule().fill(color).frame(width: g.size.width * CGFloat(min(1, max(0, value))))
                }
            }
            .frame(height: 8)
            Text("\(Int((min(1, max(0, value)) * 100).rounded()))%")
                .font(.footnote.monospacedDigit())
                .foregroundStyle(.white)
                .frame(width: 44, alignment: .trailing)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(label) \(Int(value * 100)) percent")
    }
}

/// The circle a tap-to-clear flashes where it cleared.
struct ClearFlash {
    var center: CGPoint
    var radius: CGFloat
    var visible = true
}
