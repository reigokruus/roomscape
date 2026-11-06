import SwiftUI
import RoomPlan

struct ScanStats: Equatable {
    var walls = 0, floors = 0, ceilings = 0, doors = 0, windows = 0, objects = 0
    var isEmpty: Bool { walls + floors + ceilings + doors + windows + objects == 0 }
}

/// Drives RoomPlan's RoomCaptureView: live scan, then processing, then saving the result.
final class ScanController: NSObject, ObservableObject, RoomCaptureViewDelegate, RoomCaptureSessionDelegate {
    enum Phase: Equatable {
        case scanning
        case processing
        case done
        case failed(String)
    }

    @Published var phase: Phase = .scanning
    @Published var saved: SavedScan?
    @Published var scanStats = ScanStats()
    let captureView: RoomCaptureView
    private var started = false

    override init() {
        captureView = RoomCaptureView(frame: .zero)
        super.init()
        captureView.delegate = self
        captureView.captureSession.delegate = self
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

    // MARK: RoomCaptureSessionDelegate

    func captureSession(_ session: RoomCaptureSession, didUpdate room: CapturedRoom) {
        DispatchQueue.main.async {
            self.scanStats = ScanStats(
                walls: room.walls.count,
                floors: room.floors.count,
                ceilings: room.ceilings.count,
                doors: room.doors.count,
                windows: room.windows.count,
                objects: room.objects.count
            )
        }
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

            if controller.phase == .scanning {
                ScanStatsHUD(stats: controller.scanStats)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                    .padding(.top, 60)
                    .animation(.easeInOut(duration: 0.25), value: controller.scanStats)
            }

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
