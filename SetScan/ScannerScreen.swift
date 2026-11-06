import SwiftUI
import RoomPlan

/// Drives RoomPlan's RoomCaptureView: live scan, then processing, then saving the result.
final class ScanController: NSObject, ObservableObject, RoomCaptureViewDelegate {
    enum Phase: Equatable {
        case scanning
        case processing
        case done
        case failed(String)
    }

    @Published var phase: Phase = .scanning
    @Published var saved: SavedScan?
    let captureView: RoomCaptureView
    private var started = false

    override init() {
        captureView = RoomCaptureView(frame: .zero)
        super.init()
        captureView.delegate = self
    }

    required init?(coder: NSCoder) {
        fatalError("not supported")
    }

    func encode(with coder: NSCoder) {}

    func start() {
        guard !started else { return }
        started = true
        captureView.captureSession.run(configuration: RoomCaptureSession.Configuration())
    }

    func finish() {
        phase = .processing
        captureView.captureSession.stop()
    }

    func cancel() {
        if phase == .scanning { captureView.captureSession.stop() }
    }

    // MARK: RoomCaptureViewDelegate

    func captureView(shouldPresent roomDataForProcessing: CapturedRoomData, error: Error?) -> Bool {
        true
    }

    func captureView(didPresent processedResult: CapturedRoom, error: Error?) {
        DispatchQueue.main.async {
            if let error {
                self.phase = .failed(error.localizedDescription)
                return
            }
            do {
                self.saved = try ScanStore.save(processedResult)
                self.phase = .done
            } catch {
                self.phase = .failed("Could not save the scan: \(error.localizedDescription)")
            }
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

    var body: some View {
        ZStack(alignment: .bottom) {
            RoomCaptureRepresentable(controller: controller)
                .ignoresSafeArea()
                .onAppear { controller.start() }

            VStack(spacing: 12) {
                switch controller.phase {
                case .scanning:
                    HStack(spacing: 16) {
                        Button("Cancel") {
                            controller.cancel()
                            onCancel()
                        }
                        .buttonStyle(.bordered)
                        Button("Done") { controller.finish() }
                            .buttonStyle(.borderedProminent)
                    }
                case .processing:
                    ProgressView("Building the room model…")
                        .padding()
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                case .done:
                    HStack(spacing: 16) {
                        Button("Close") { onCancel() }
                            .buttonStyle(.bordered)
                        Button("Open 3D view") {
                            if let saved = controller.saved { onFinished(saved) }
                        }
                        .buttonStyle(.borderedProminent)
                    }
                case .failed(let message):
                    Text(message)
                        .padding()
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                    Button("Close") { onCancel() }
                        .buttonStyle(.borderedProminent)
                }
            }
            .controlSize(.large)
            .padding(.bottom, 32)
        }
    }
}
