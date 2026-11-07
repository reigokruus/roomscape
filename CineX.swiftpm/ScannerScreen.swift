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

    override init() {
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
        setPhase(.scanning)
        captureView.captureSession.run(configuration: RoomCaptureSession.Configuration())
        live.start()
        live.attachDiscs(to: captureView)
        lastMemoryWarning = nil
        // Already warm from a previous scan: start in power saving.
        powerSaving = ProcessInfo.processInfo.thermalState == .serious
        if powerSaving { live.setPowerSaving(true) }
    }

    /// User tapped Done. RoomPlan answers through `shouldPresent`, where processing starts.
    func finish() {
        guard phase == .scanning, running else { return }
        setPhase(.processing)
        running = false
        live.stop()
        captureView.captureSession.stop()
    }

    func cancel() {
        log.info("cancel")
        processingTask?.cancel()
        processingTask = nil
        live.stop()
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
                let capture = await self.live.snapshot()
                let saved = try await Task.detached(priority: .userInitiated) {
                    try ScanStore.save(room, capture: capture)
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

/// "Detail here: N% solid" while scanning: how much of what the phone points at is captured well.
private struct LiveStatusLine: View {
    @ObservedObject var live: LiveCapture

    var body: some View {
        Text(live.detailLine)
            .font(.subheadline.weight(.medium))
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(.regularMaterial, in: Capsule())
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
                // The live discs are drawn inside the capture view, under RoomPlan's own UI (see attachDiscs).
                LiveStatusLine(live: controller.live)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                    .padding(.top, 60)
                    .allowsHitTesting(false)
            }

            VStack(spacing: 12) {
                switch controller.phase {
                case .scanning:
                    if controller.powerSaving {
                        Label("The phone is warm, so the live discs update less often. Keep scanning.",
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
                            // Solid backing so the label stays readable over the live discs.
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
