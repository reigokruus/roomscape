import SwiftUI
import RoomPlan

struct HomeView: View {
    @State private var scans: [SavedScan] = []
    @State private var path: [SavedScan] = []
    @State private var scanning = false
    @State private var justSaved: SavedScan?
    /// Improve scan: the saved scan the scanner adds a second pass to.
    @State private var improving: SavedScan?

    var body: some View {
        NavigationStack(path: $path) {
            List {
                // The logo stands in for a title; its asset has a dark-lettered version for light mode.
                Image("Logo")
                    .resizable()
                    .scaledToFit()
                    .frame(height: 120)
                    .frame(maxWidth: .infinity)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                    .accessibilityLabel("RoomScape")
                    .accessibilityAddTraits(.isHeader)

                Section {
                    if RoomCaptureSession.isSupported {
                        Button {
                            scanning = true
                        } label: {
                            Label("New room scan", systemImage: "camera.viewfinder")
                                .font(.headline)
                        }
                    } else {
                        Label("This iPhone has no LiDAR scanner. RoomScape needs an iPhone 12 Pro or newer Pro model.",
                              systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.secondary)
                    }
                } footer: {
                    Text("Walk slowly around the room and point the phone at every wall, window, door and piece of furniture.")
                }

                Section {
                    NavigationLink(destination: GearInventoryView()) {
                        Label("My gear", systemImage: "checklist")
                    }
                    NavigationLink(destination: PlanningLibraryView()) {
                        Label("Planning projects", systemImage: "rectangle.stack")
                    }
                } footer: {
                    Text("Your lights, camera and kit. The lighting preview and the shoot planner start from them.")
                }

                if !scans.isEmpty {
                    Section("Saved scans") {
                        ForEach(scans) { scan in
                            NavigationLink(value: scan) {
                                HStack(spacing: 12) {
                                    ScanThumbnailView(scan: scan)
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
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: SavedScan.self) { scan in
                ResultView(scan: scan, onImprove: {
                    // Close the result first; it opens again with the merged scan when the scanner is done.
                    improving = scan
                    path = []
                    scanning = true
                })
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    NavigationLink(destination: GearInventoryView()) {
                        Label("My Gear", systemImage: "checklist")
                            .labelStyle(.titleAndIcon)
                    }
                }
            }
            .onAppear {
                scans = ScanStore.list()
                #if DEBUG
                // UI checks in the Simulator: launch with -openLatestScan to go straight to the newest scan.
                if ProcessInfo.processInfo.arguments.contains("-openLatestScan"), path.isEmpty, let latest = scans.first {
                    path = [latest]
                }
                #endif
            }
            .fullScreenCover(isPresented: $scanning, onDismiss: {
                // Open the result only after the scanner has fully closed, so the two transitions don't collide.
                scans = ScanStore.list()
                // Improve scan closed without merging (cancelled or failed): go back to the scan it was improving,
                // not the home screen, where "New room scan" would start a separate scan.
                let back = justSaved ?? improving
                improving = nil
                justSaved = nil
                if let back { path = [back] }
            }) {
                ScannerScreen(
                    improving: improving,
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

/// A saved scan's thumbnail. Older scans get theirs rendered the first time they're shown.
private struct ScanThumbnailView: View {
    let scan: SavedScan
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Color.black
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            }
        }
        .frame(width: 88, height: 66)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .task(id: scan.id) {
            let scan = scan
            image = await Task.detached(priority: .utility) { () -> UIImage? in
                guard let url = ScanStore.ensureThumbnail(scan) else { return nil }
                return UIImage(contentsOfFile: url.path)
            }.value
        }
    }
}
