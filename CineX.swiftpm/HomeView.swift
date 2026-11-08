import SwiftUI
import RoomPlan

struct HomeView: View {
    @State private var scans: [SavedScan] = []
    @State private var path: [SavedScan] = []
    @State private var scanning = false
    @State private var justSaved: SavedScan?
    /// The scan being renamed, and the name being typed.
    @State private var renaming: SavedScan?
    @State private var newTitle = ""
    /// Debug builds only: -openGear / -openPlanning open those screens at launch.
    @State private var debugGear = false
    @State private var debugPlanning = false
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
                    if RoomCaptureSession.isSupported {
                        Text("Walk slowly around the room and point the phone at every wall, window, door and piece of furniture.")
                    }
                }

                Section {
                    NavigationLink(destination: GearInventoryView()) {
                        Label("My gear", systemImage: "checklist")
                    }
                    NavigationLink(destination: PlanningLibraryView()) {
                        Label("Planning projects", systemImage: "rectangle.stack")
                    }
                } footer: {
                    Text("Add the lights and camera you own: the lighting preview and the shoot planner start from them. Storyboards you've made are under Planning projects.")
                }

                if !scans.isEmpty {
                    Section {
                        ForEach(scans) { scan in
                            NavigationLink(value: scan) {
                                HStack(spacing: 12) {
                                    ScanThumbnailView(scan: scan)
                                    VStack(alignment: .leading) {
                                        Text(scan.displayName)
                                        if scan.title != nil {
                                            Text(scan.defaultDisplayName).font(.caption).foregroundStyle(.secondary)
                                        }
                                    }
                                }
                            }
                            .contextMenu {
                                Button("Rename", systemImage: "pencil") { startRenaming(scan) }
                                Button("Delete", systemImage: "trash", role: .destructive) {
                                    ScanStore.delete(scan)
                                    scans = ScanStore.list()
                                }
                            }
                            .swipeActions(edge: .leading) {
                                Button("Rename", systemImage: "pencil") { startRenaming(scan) }
                                    .tint(.accentColor)
                            }
                        }
                        .onDelete { idx in
                            idx.map { scans[$0] }.forEach(ScanStore.delete)
                            scans = ScanStore.list()
                        }
                    } header: {
                        Text("Saved scans")
                    } footer: {
                        Text("Touch and hold a scan to give the room a name.")
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
            .navigationDestination(isPresented: $debugGear) { GearInventoryView() }
            .navigationDestination(isPresented: $debugPlanning) { PlanningLibraryView() }
            .alert("Name this room", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
                TextField("e.g. Living room", text: $newTitle)
                Button("Save") {
                    if let scan = renaming { ScanStore.rename(scan, to: newTitle) }
                    renaming = nil
                    scans = ScanStore.list()
                }
                Button("Cancel", role: .cancel) { renaming = nil }
            } message: {
                Text("Leave it empty to go back to the scan's date.")
            }
            .onAppear {
                scans = ScanStore.list()
                #if DEBUG
                // UI checks in the Simulator: launch with -openLatestScan to go straight to the newest scan.
                let args = ProcessInfo.processInfo.arguments
                if args.contains("-openLatestScan"), path.isEmpty, let latest = scans.first {
                    path = [latest]
                }
                if args.contains("-openGear") { debugGear = true }
                if args.contains("-openPlanning") { debugPlanning = true }
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

extension HomeView {
    private func startRenaming(_ scan: SavedScan) {
        newTitle = scan.title ?? ""
        renaming = scan
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
