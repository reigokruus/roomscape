import SwiftUI
import RoomPlan

struct HomeView: View {
    @State private var scans: [SavedScan] = []
    @State private var path: [SavedScan] = []
    @State private var scanning = false

    var body: some View {
        NavigationStack(path: $path) {
            List {
                Section {
                    if RoomCaptureSession.isSupported {
                        Button {
                            scanning = true
                        } label: {
                            Label("New room scan", systemImage: "camera.viewfinder")
                                .font(.headline)
                        }
                    } else {
                        Label("This iPhone has no LiDAR scanner. CineX needs an iPhone 12 Pro or newer Pro model.",
                              systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.secondary)
                    }
                } footer: {
                    Text("Walk slowly around the room and point the phone at every wall, window, door and piece of furniture.")
                }

                if !scans.isEmpty {
                    Section("Saved scans") {
                        ForEach(scans) { scan in
                            NavigationLink(value: scan) {
                                VStack(alignment: .leading) {
                                    Text(scan.date.formatted(date: .abbreviated, time: .shortened))
                                    Text(scan.name).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                        .onDelete { idx in
                            idx.map { scans[$0] }.forEach(ScanStore.delete)
                            scans = ScanStore.list()
                        }
                    }
                }
            }
            .navigationTitle("CineX")
            .navigationDestination(for: SavedScan.self) { scan in
                ResultView(scan: scan)
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    NavigationLink(destination: GearInventoryView()) {
                        Label("My Gear", systemImage: "checklist")
                    }
                }
            }
            .onAppear { scans = ScanStore.list() }
            .fullScreenCover(isPresented: $scanning) {
                ScannerScreen(
                    onFinished: { saved in
                        scanning = false
                        scans = ScanStore.list()
                        path = [saved]
                    },
                    onCancel: { scanning = false }
                )
            }
        }
    }
}
