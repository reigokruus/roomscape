import SwiftUI
import RoomPlan

/// The scan's saved thumbnail, or a placeholder for scans saved before thumbnails existed.
struct ScanThumbnail: View {
    let url: URL
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Color.black
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Image(systemName: "cube.transparent").foregroundStyle(.secondary)
            }
        }
        .frame(width: 88, height: 66)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .task(id: url) {
            let url = url
            image = await Task.detached(priority: .utility) { UIImage(contentsOfFile: url.path) }.value
        }
    }
}

struct HomeView: View {
    @State private var scans: [SavedScan] = []
    @State private var path: [SavedScan] = []
    @State private var scanning = false
    @State private var justSaved: SavedScan?

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
                                HStack(spacing: 12) {
                                    ScanThumbnail(url: scan.thumbnailURL)
                                    VStack(alignment: .leading) {
                                        Text(scan.date.formatted(date: .abbreviated, time: .shortened))
                                        Text(scan.name).font(.caption).foregroundStyle(.secondary)
                                    }
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
            .fullScreenCover(isPresented: $scanning, onDismiss: {
                // Open the result only after the scanner has fully closed, so the two transitions don't collide.
                scans = ScanStore.list()
                if let saved = justSaved {
                    justSaved = nil
                    path = [saved]
                }
            }) {
                ScannerScreen(
                    onFinished: { saved in
                        justSaved = saved
                        scanning = false
                    },
                    onCancel: { scanning = false }
                )
            }
        }
    }
}
