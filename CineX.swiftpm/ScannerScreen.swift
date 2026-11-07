import SwiftUI
import RoomPlan
import os
import Metal

private let log = Logger(subsystem: "SetScan", category: "scan")

/// Live counts shown while scanning.
struct ScanStats: Equatable {
    var walls = 0, floors = 0, doors = 0, windows = 0, objects = 0
    var isEmpty: Bool { walls + floors + doors + windows + objects == 0 }
}

/// Drives RoomPlan's RoomCaptureView for the live scan, then builds and saves the room model itself.
///
/// We don't let RoomCaptureView present its own result: we return `false` from `shouldPresent` and run
/// `RoomBuilder` ourselves. That way every outcome (success, tracking failure, processing error) reaches
/// our code, and the UI can never get stuck waiting for a callback that doesn't come.
@MainActor
final class ScanController: NSObject, ObservableObject, RoomCaptureViewDelegate, RoomCaptureSessionDelegate {
    enum Phase: Equatable {
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
    @Published var scanStats = ScanStats()
    let captureView: RoomCaptureView
    /// LiDAR point cloud with seen counters, drawn as live discs over the camera. Nil if Metal is unavailable.
    let liveCloud: LiveCloud?
    private var running = false
    private var processingTask: Task<Void, Never>?
    /// The session delegate RoomCaptureView had before we took over (see init). Set once, then only read.
    nonisolated(unsafe) private var forwardSessionEvents: RoomCaptureSessionDelegate?

    override init() {
        captureView = RoomCaptureView(frame: .zero)
        liveCloud = MTLCreateSystemDefaultDevice().flatMap { LiveCloud(device: $0) }
        super.init()
        captureView.delegate = self
        // We listen to the session for live counts. RoomCaptureView may rely on the session's delegate itself,
        // so keep whatever delegate it set and pass every event on to it; otherwise its UI could stop updating.
        forwardSessionEvents = captureView.captureSession.delegate
        captureView.captureSession.delegate = self
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
        scanStats = ScanStats()
        liveCloud?.reset()
        setPhase(.scanning)
        captureView.captureSession.run(configuration: RoomCaptureSession.Configuration())
    }

    /// User tapped Done. RoomPlan answers through `shouldPresent`, where processing starts.
    func finish() {
        guard phase == .scanning, running else { return }
        setPhase(.processing)
        running = false
        captureView.captureSession.stop()
    }

    func cancel() {
        log.info("cancel")
        processingTask?.cancel()
        processingTask = nil
        if running {
            running = false
            captureView.captureSession.stop()
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

    @objc nonisolated private func memoryWarning() {
        Task { @MainActor in
            self.endEarly("The phone was running low on memory, so the scan was finished early and what you scanned so far was kept.")
        }
    }

    @objc nonisolated private func thermalStateChanged() {
        let state = ProcessInfo.processInfo.thermalState
        Task { @MainActor in
            log.info("thermal state \(state.rawValue)")
            if state == .serious || state == .critical {
                self.endEarly("The phone got hot, so the scan was finished early and what you scanned so far was kept.")
            }
        }
    }

    // MARK: RoomCaptureSessionDelegate

    nonisolated func captureSession(_ session: RoomCaptureSession, didUpdate room: CapturedRoom) {
        forwardSessionEvents?.captureSession(session, didUpdate: room)
        let stats = ScanStats(
            walls: room.walls.count,
            floors: room.floors.count,
            doors: room.doors.count,
            windows: room.windows.count,
            objects: room.objects.count
        )
        Task { @MainActor in self.scanStats = stats }
    }

    nonisolated func captureSession(_ session: RoomCaptureSession, didAdd room: CapturedRoom) {
        forwardSessionEvents?.captureSession(session, didAdd: room)
    }

    nonisolated func captureSession(_ session: RoomCaptureSession, didChange room: CapturedRoom) {
        forwardSessionEvents?.captureSession(session, didChange: room)
    }

    nonisolated func captureSession(_ session: RoomCaptureSession, didRemove room: CapturedRoom) {
        forwardSessionEvents?.captureSession(session, didRemove: room)
    }

    nonisolated func captureSession(_ session: RoomCaptureSession, didProvide instruction: RoomCaptureSession.Instruction) {
        forwardSessionEvents?.captureSession(session, didProvide: instruction)
    }

    nonisolated func captureSession(_ session: RoomCaptureSession, didStartWith configuration: RoomCaptureSession.Configuration) {
        forwardSessionEvents?.captureSession(session, didStartWith: configuration)
    }

    nonisolated func captureSession(_ session: RoomCaptureSession, didEndWith data: CapturedRoomData, error: Error?) {
        forwardSessionEvents?.captureSession(session, didEndWith: data, error: error)
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
        // The session ended with an error and the user didn't ask to finish. Let them decide.
        if let sessionError, phase == .scanning {
            setPhase(.failed(Self.message(for: sessionError)))
            return
        }
        guard phase == .processing || phase == .scanning else { return }
        if phase != .processing { setPhase(.processing) }

        processingTask?.cancel()
        processingTask = Task {
            do {
                let builder = RoomBuilder(options: [.beautifyObjects])
                let room = try await builder.capturedRoom(from: data)
                try Task.checkCancellation()
                log.info("built room: \(room.walls.count) walls, \(room.objects.count) objects")
                guard !room.walls.isEmpty else {
                    self.setPhase(.failed("No walls were found in this scan. Stand inside the room, point the camera at a wall from 1–3 m away, and turn slowly until white outlines appear on every wall."))
                    return
                }
                let saved = try await Task.detached(priority: .userInitiated) {
                    try ScanStore.save(room)
                }.value
                try Task.checkCancellation()
                log.info("saved \(saved.name, privacy: .public)")
                self.saved = saved
            } catch is CancellationError {
                // User closed the scanner.
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
                return "The space is too big for one scan. Scan a single room at a time."
            case .deviceTooHot:
                return "Scanning stopped because the phone got too hot. Let it cool down for a few minutes and try again."
            default:
                break
            }
        }
        return error.localizedDescription
    }
}

private struct ScanStatsHUD: View {
    let stats: ScanStats

    var body: some View {
        if !stats.isEmpty {
            HStack(spacing: 14) {
                if stats.walls > 0 {
                    Label("^[\(stats.walls) wall](inflect: true)", systemImage: "rectangle.portrait")
                }
                if stats.doors > 0 {
                    Label("^[\(stats.doors) door](inflect: true)", systemImage: "door.left.hand.open")
                }
                if stats.windows > 0 {
                    Label("^[\(stats.windows) window](inflect: true)", systemImage: "rectangle.inset.filled")
                }
                if stats.objects > 0 {
                    Label("^[\(stats.objects) object](inflect: true)", systemImage: "cube")
                }
            }
            .font(.subheadline.weight(.medium))
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(.regularMaterial, in: Capsule())
            .transition(.opacity.combined(with: .scale(scale: 0.92)))
        }
    }
}

/// "Detail here: N% solid" for the spot the camera is looking at, refreshed three times a second.
private struct DetailLine: View {
    let cloud: LiveCloud

    var body: some View {
        TimelineView(.periodic(from: .now, by: 0.33)) { _ in
            let stats = cloud.stats
            let text: String = {
                if !stats.hasDepth { return "Point the phone at the room…" }
                if stats.detailHere < 0 { return "Walk slowly. Hold still where discs are red or yellow." }
                let pct = Int((stats.detailHere * 100).rounded())
                return pct >= 70
                    ? "Detail here: \(pct)% solid. Move on to the next area."
                    : "Detail here: \(pct)% solid. Sway the phone gently here until the discs turn green."
            }()
            Text(text)
                .font(.callout.weight(.medium))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(.regularMaterial, in: Capsule())
        }
    }
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
    @StateObject private var controller = ScanController()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack(alignment: .bottom) {
            RoomCaptureRepresentable(controller: controller)
                .ignoresSafeArea()
                .onAppear { controller.start() }

            if controller.phase == .scanning {
                if let cloud = controller.liveCloud {
                    LiveDiscsOverlay(cloud: cloud, session: controller.captureView.captureSession.arSession)
                        .ignoresSafeArea()
                        .allowsHitTesting(false)
                }
                ScanStatsHUD(stats: controller.scanStats)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                    .padding(.top, 60)
                    .animation(.easeInOut(duration: 0.25), value: controller.scanStats)
            }

            VStack(spacing: 12) {
                switch controller.phase {
                case .scanning:
                    if let cloud = controller.liveCloud {
                        DetailLine(cloud: cloud)
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
                        ProgressView("Building the room model…")
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
            if newPhase == .background, controller.phase == .scanning {
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
