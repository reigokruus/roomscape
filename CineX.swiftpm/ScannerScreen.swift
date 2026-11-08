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
    private var lastMemoryWarning: Date?
    let captureView: RoomCaptureView
    /// LiDAR points and live discs, read from RoomPlan's own ARKit session.
    let live: LiveCapture
    private var running = false
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
                ImproveLog.write(String(format: "RoomPlan tracking after %.1f s: ", Date().timeIntervalSince(recognisedAt))
                                 + "\(ImproveLog.pose(camera.transform)) <- saved room \(ImproveLog.pose(before))")
                return
            }
            ImproveLog.write("RoomPlan didn't track within 8 s; fitting the pass from scratch")
        }
    }

    private func beginCapture() {
        setPhase(.scanning)
        // No coaching overlay (the animated phone over a grid, "Move device to start" and the other prompts): the
        // live mesh already shows where the scan has been.
        var configuration = RoomCaptureSession.Configuration()
        configuration.isCoachingEnabled = false
        captureView.captureSession.run(configuration: configuration)
        live.start()
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
        captureView.captureSession.stop(pauseARSession: false)
        worldMapTask = Task {
            let map = await WorldMapStore.capture(from: session)
            session.pause()
            return map
        }
    }

    func cancel() {
        log.info("cancel")
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
                try Task.checkCancellation()
                log.info("saved \(saved.name, privacy: .public)")
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

            VStack(spacing: 12) {
                switch controller.phase {
                case .relocalizing:
                    VStack(spacing: 8) {
                        ProgressView()
                        Text("Finding the saved room")
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
                    if controller.improving != nil {
                        Label("Lined up with the saved scan. Scan the parts you want sharper, then tap Done.",
                              systemImage: "checkmark.circle")
                            .font(.footnote)
                            .padding(.horizontal, 12).padding(.vertical, 8)
                            .background(.regularMaterial, in: Capsule())
                    }
                    if controller.powerSaving {
                        Label("The phone is warm, so the live mesh updates less often. Keep scanning.",
                              systemImage: "thermometer.medium")
                            .font(.footnote)
                            .padding(.horizontal, 12).padding(.vertical, 8)
                            .background(.regularMaterial, in: Capsule())
                    }
                    TimelineView(.periodic(from: controller.phaseStarted, by: 1)) { context in
                        let elapsed = context.date.timeIntervalSince(controller.phaseStarted)
                        if elapsed > ScanController.suggestedMaxScanSeconds {
                            Text("Long scans heat up the phone. Tap Done once every wall has a white outline.")
                                .font(.callout)
                                .multilineTextAlignment(.center)
                                .padding(10)
                                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                        }
                    }
                    HStack(spacing: 16) {
                        Button("Cancel") { close() }
                            .buttonStyle(.bordered)
                            // Solid backing so the label stays readable over the live mesh.
                            .background(.regularMaterial, in: Capsule())
                        Button("Done") { controller.finish() }
                            .buttonStyle(.borderedProminent)
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

    private func close() {
        controller.cancel()
        onCancel()
    }
}
