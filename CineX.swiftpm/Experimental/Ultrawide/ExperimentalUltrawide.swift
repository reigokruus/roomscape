import SwiftUI

/// Experimental ultrawide scanner (rk 9 Nov, option b): scans with the real ultrawide camera plus LiDAR depth, with
/// our own tracking instead of ARKit (ARKit can't use the ultrawide; see docs/ultrawide-probe-2026-11-09.txt).
///
/// Self-contained and discardable: everything is in Experimental/Ultrawide/, the only hook into the app is
/// `ExperimentalUltrawideRow` on Home, and scans it makes are ordinary scans (no RoomPlan model, like a room from the
/// Android app) that keep opening if this folder is deleted. How to remove it: docs/experimental-ultrawide.md.
enum ExperimentalUltrawide {
    /// Turns the Home entry (and so the whole scanner) off.
    static let enabled = true
}

/// The Home entry: a small row under New room scan, and the scanner in a full-screen cover.
struct ExperimentalUltrawideRow: View {
    /// Called after a scan was saved, so Home lists it.
    var onSaved: () -> Void
    @State private var scanning = false

    var body: some View {
        if ExperimentalUltrawide.enabled {
            Section {
                Button {
                    scanning = true
                } label: {
                    Label("Experimental ultrawide scan", systemImage: "camera.metering.matrix")
                }
            } footer: {
                Text("Uses the ultrawide camera for a wider view, with RoomScape's own tracking. Experimental: walk slowly; it may lose its place on bare walls.")
            }
            .fullScreenCover(isPresented: $scanning, onDismiss: onSaved) {
                UltraScanScreen { scanning = false }
            }
        }
    }
}

/// The scan screen: the ultrawide picture with the live mesh over it, the same top card (hint, This spot, Room) and
/// Cancel / Done as the normal scanner.
struct UltraScanScreen: View {
    var onClose: () -> Void
    @StateObject private var scanner = UltraScanner()

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let view = scanner.metalView {
                UltraMetalView(view: view).ignoresSafeArea()
            }
            VStack {
                ScanHudCard(hud: scanner.hud, notes: scanner.notes)
                    .padding(.horizontal, 12)
                    .padding(.top, 8)
                Spacer()
                switch scanner.phase {
                case .scanning:
                    HStack(spacing: 16) {
                        Button { scanner.cancel(); onClose() } label: { Text("Cancel").frame(minWidth: 80) }
                            .buttonStyle(.bordered)
                            .buttonBorderShape(.capsule)
                            .background(Color(white: 0.17).opacity(0.9), in: Capsule())
                        Button { scanner.finish(onSaved: onClose) } label: {
                            Text("Done").fontWeight(.semibold).foregroundStyle(.black).frame(minWidth: 80)
                        }
                        .buttonStyle(.borderedProminent)
                        .buttonBorderShape(.capsule)
                    }
                case .saving:
                    ProgressView("Saving the scan…")
                        .padding()
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                case .failed(let message):
                    VStack(spacing: 12) {
                        Text(message).multilineTextAlignment(.center)
                        Button("Close") { scanner.cancel(); onClose() }.buttonStyle(.borderedProminent)
                    }
                    .padding()
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                }
            }
            .controlSize(.large)
            .padding(.bottom, 32)
        }
        .environment(\.colorScheme, .dark)
        .onAppear { scanner.start() }
        .onDisappear { scanner.cancel() }
    }
}

private struct UltraMetalView: UIViewRepresentable {
    let view: UIView
    func makeUIView(context: Context) -> UIView { view }
    func updateUIView(_ uiView: UIView, context: Context) {}
}
