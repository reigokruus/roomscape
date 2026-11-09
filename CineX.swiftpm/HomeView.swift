import SwiftUI
import RoomPlan
import UniformTypeIdentifiers

struct HomeView: View {
    @State private var scans: [SavedScan] = []
    @State private var path: [SavedScan] = []
    /// The scanner, when open, and the saved scan it continues (nil = a new scan). One value, so the scanner always
    /// gets the scan to continue: with a separate flag, SwiftUI built the scanner from the state before `improving`
    /// was set, and Continue scan started a new scan instead.
    @State private var scanRequest: ScanRequest?
    @State private var justSaved: SavedScan?
    /// The scan being renamed, and the name being typed.
    @State private var renaming: SavedScan?
    @State private var newTitle = ""
    /// Debug builds only: -openGear / -openPlanning open those screens at launch.
    @State private var debugGear = false
    @State private var debugPlanning = false
    /// Improve scan: the saved scan the scanner adds a second pass to.
    @State private var improving: SavedScan?
    /// Importing a .rmsc scan (from Files, AirDrop or another app).
    @State private var pickingImport = false
    @State private var importing = false
    @State private var importError: String?
    /// Scans from nearby phones (see `Nearby`).
    @ObservedObject private var nearby = Nearby.shared
    @Environment(\.scenePhase) private var scenePhase

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
                            scanRequest = ScanRequest(improving: nil)
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

                // Experimental ultrawide scanner (Experimental/Ultrawide, discardable): its only hook into the app.
                ExperimentalUltrawideRow { scans = ScanStore.list() }

                Section {
                    Button {
                        pickingImport = true
                    } label: {
                        if importing {
                            Label("Importing scan…", systemImage: "hourglass")
                        } else {
                            Label("Import a scan (.rmsc)", systemImage: "square.and.arrow.down")
                        }
                    }
                    .disabled(importing)
                } footer: {
                    Text("Open a room scanned with RoomScape on another iPhone or Android phone. You can also AirDrop a .rmsc file to this phone, or open it from Files.")
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
            .fileImporter(isPresented: $pickingImport, allowedContentTypes: [Rmsc.type, .zip]) { result in
                if case .success(let url) = result { importRmsc(url) }
            }
            // AirDrop, Files and "Open in RoomScape" hand the file to the app here.
            .onOpenURL { url in
                if url.isFileURL { importRmsc(url) }
            }
            .onAppear { nearby.start() }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { nearby.start() }
            }
            .alert("Couldn't import the scan", isPresented: Binding(get: { importError != nil }, set: { if !$0 { importError = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(importError ?? "")
            }
            .navigationDestination(for: SavedScan.self) { scan in
                ResultView(scan: scan, onImprove: {
                    // Close the result first; it opens again with the merged scan when the scanner is done.
                    improving = scan
                    path = []
                    scanRequest = ScanRequest(improving: scan)
                })
            }
            .navigationDestination(isPresented: $debugGear) { GearInventoryView() }
            .navigationDestination(isPresented: $debugPlanning) { PlanningLibraryView() }
            .modifier(RenameRoomAlert(isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } }),
                                      title: $newTitle) {
                if let scan = renaming { ScanStore.rename(scan, to: newTitle) }
                renaming = nil
                scans = ScanStore.list()
            })
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
            .fullScreenCover(item: $scanRequest, onDismiss: {
                // Open the result only after the scanner has fully closed, so the two transitions don't collide.
                scans = ScanStore.list()
                // Improve scan closed without merging (cancelled or failed): go back to the scan it was improving,
                // not the home screen, where "New room scan" would start a separate scan.
                let back = justSaved ?? improving
                improving = nil
                justSaved = nil
                if let back { path = [back] }
            }) { request in
                ScannerScreen(
                    improving: request.improving,
                    onFinished: { saved in
                        justSaved = saved
                        scanRequest = nil
                    },
                    onCancel: { scanRequest = nil }
                )
            }
        }
        // A nearby phone wants to send a scan: asked once per phone (Always allow), then they just arrive.
        .alert(nearby.request.map { "Accept scans from \($0.deviceName)?" } ?? "",
               isPresented: Binding(get: { nearby.request != nil }, set: { if !$0, let r = nearby.request { nearby.answer(r, .decline) } })) {
            if let r = nearby.request {
                Button("Always allow") { nearby.answer(r, .always) }
                Button("Just this once") { nearby.answer(r, .once) }
                Button("Decline", role: .cancel) { nearby.answer(r, .decline) }
            }
        } message: {
            Text("It's sending a room scan (\(nearby.request?.fileName ?? "")). With Always allow, scans from this phone arrive without asking.")
        }
        .onChange(of: nearby.received?.scan.id) { _, id in
            guard id != nil else { return }
            scans = ScanStore.list()
            Task {
                try? await Task.sleep(nanoseconds: 4_000_000_000)
                nearby.received = nil
            }
        }
        .overlay(alignment: .top) {
            if let r = nearby.received {
                Label("Scan received from \(r.from)", systemImage: "tray.and.arrow.down.fill")
                    .font(.callout)
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .background(.regularMaterial, in: Capsule())
                    .padding(.top, 8)
                    .onTapGesture { path = [r.scan]; nearby.received = nil }
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
    }
}

extension HomeView {
    /// Imports a .rmsc package as a new scan and opens it.
    private func importRmsc(_ url: URL) {
        importing = true
        Task {
            let result = await Task.detached(priority: .userInitiated) { () -> Result<SavedScan, Error> in
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                return Result { try Rmsc.importScan(from: url).scan }
            }.value
            importing = false
            switch result {
            case .success(let scan):
                scans = ScanStore.list()
                path = [scan]
            case .failure(let error):
                importError = error.localizedDescription
            }
        }
    }

    private func startRenaming(_ scan: SavedScan) {
        newTitle = scan.title ?? ""
        renaming = scan
    }
}

/// A saved scan's thumbnail. Older scans get theirs rendered the first time they're shown.
private struct ScanThumbnailView: View {
    let scan: SavedScan
    @State private var image: UIImage?
    @State private var shared = false
    /// Changes when a synced update arrives, so the thumbnail is read again.
    @ObservedObject private var nearby = Nearby.shared

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
        // Shared rooms (synced with other phones): a small badge in the corner.
        .overlay(alignment: .bottomTrailing) {
            if shared { SharedBadge().padding(3) }
        }
        .task(id: "\(scan.id)-\(nearby.received?.scan.id ?? "")") {
            let scan = scan
            shared = await Task.detached(priority: .utility) { SyncStore.info(scan).shared }.value
            image = await Task.detached(priority: .utility) { () -> UIImage? in
                guard let url = ScanStore.ensureThumbnail(scan) else { return nil }
                return UIImage(contentsOfFile: url.path)
            }.value
        }
    }
}

/// "Name this room": the alert that names a scan, on Home and on the scan's own screen.
struct RenameRoomAlert: ViewModifier {
    @Binding var isPresented: Bool
    @Binding var title: String
    let onSave: () -> Void

    /// A small sheet, not an alert: an alert's text field keeps its height and clips large text sizes.
    func body(content: Content) -> some View {
        content.sheet(isPresented: $isPresented) {
            RenameSheet(title: $title, onSave: { isPresented = false; onSave() }, onCancel: { isPresented = false })
                .presentationDetents([.medium])
        }
    }
}

private struct RenameSheet: View {
    @Binding var title: String
    let onSave: () -> Void
    let onCancel: () -> Void
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("e.g. Living room", text: $title)
                        .focused($focused)
                        .submitLabel(.done)
                        .onSubmit(onSave)
                } footer: {
                    Text("Leave it empty to go back to the scan's date.")
                }
            }
            .navigationTitle("Name this room")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onCancel) }
                ToolbarItem(placement: .confirmationAction) { Button("Save", action: onSave).bold() }
            }
        }
        .onAppear { focused = true }
    }
}

/// Opening the scanner: a new scan, or continuing a saved one.
struct ScanRequest: Identifiable {
    let id = UUID()
    let improving: SavedScan?
}

/// The "shared" mark: this room syncs with other phones.
struct SharedBadge: View {
    var body: some View {
        Image(systemName: "person.2.fill")
            .font(.system(size: 9, weight: .bold))
            .foregroundStyle(.black)
            .padding(4)
            .background(Color.accentColor, in: Circle())
            .accessibilityLabel("Shared")
    }
}
