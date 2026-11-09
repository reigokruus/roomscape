import SwiftUI
import SceneKit
import RoomPlan
import simd

/// Shows a saved scan: as splats (the captured points as discs on their surfaces, inside RoomPlan's walls and floor)
/// when the scan has points, or as RoomPlan's own 3D model. The splats show as a white mesh with a grid (Mesh) or in
/// the scanned colours (Photo). Below it, a summary of what RoomPlan found.
struct ResultView: View {
    enum Style: Hashable { case mesh, photo, model }

    let scan: SavedScan
    /// Starts a second pass over this scan (Improve scan); only offered when the scan saved its world map.
    var onImprove: (() -> Void)?
    @State private var scene: SCNScene?
    @State private var room: CapturedRoom?
    @State private var capture: CaptureMeta?
    /// When this scan was improved with a second pass (see `ImproveHistory`).
    @State private var improved: [Date] = []
    /// Windows found from the photos and LiDAR (see `WindowFinder`), including ones RoomPlan also found.
    @State private var foundWindows: [WindowFinder.Found] = []
    @State private var topView = false
    @State private var loadError: String?
    @State private var style: Style = .model
    @State private var modelGroup: SCNNode?
    @State private var splatGroup: SCNNode?
    /// The scan's photos painted onto its fused mesh (`PhotoRoom`): what Photo shows when the scan has both; else the
    /// splats in their scanned colours.
    @State private var photoRoom: SCNNode?
    /// Power outlets in the room (from .rmsc: the Android app's Claude search), shown as labels in the 3D view.
    @State private var outlets: [RoomOutlet] = []
    /// "Enter the room": picking where to stand, then standing there (first person). See `RoomSceneView`.
    @State private var walk: WalkState = .off
    /// Held ▲ (+1) or ▼ (-1) while standing in the room.
    @State private var walkMove = 0
    /// Eye height above the floor when standing in the room, metres; remembered.
    @AppStorage("walkEyeHeight") private var eyeHeight = 1.5
    /// The photo room as a .glb (`PhotoRoomExport`), being written or ready to share (AirDrop, Save to Files).
    @State private var exportingGLB = false
    @State private var glbFile: SharedFile?
    @State private var glbError: String?
    @State private var sendingNearby = false
    @State private var buildingSplats = false
    /// Lighting preview (handoff section 3): shown on the splats, with its panel in place of the summary.
    @State private var lightingOn = false
    @State private var lighting: LightingSetup?
    @State private var lightingScene: LightingScene?
    @State private var shell: RoomShell?
    /// Objects (RoomPlan's furniture, plus what Claude finds in the photos): edited in the Objects panel.
    @State private var objectsOn = false
    @State private var objects: [RoomObject] = []
    @State private var claudeChecked = false
    @State private var selectedObject: UUID?
    @State private var objectsScene: ObjectsScene?
    /// The same edits on RoomPlan's boxes, for the Model view.
    @State private var modelObjects: ModelObjects?
    @State private var surfels: [SurfelRecord] = []
    /// Splat training (task 6), once the splats are built.
    @State private var training: SplatTrainingRun?
    @State private var rebuildingSplats = false
    @State private var pendingSplats: [SurfelRecord]?
    @Environment(\.scenePhase) private var scenePhase
    @State private var finding: String?
    @State private var findError: String?
    /// The camera's view in the lighting preview: small in the corner, or large.
    @State private var shotLarge = false
    /// Height of the controls over the 3D view (one or two rows, with their padding).
    @State private var controlsHeight: CGFloat = 58
    @State private var planningShot = false
    @State private var planError: String?
    /// The lighting setup being opened as a new storyboard view.
    @State private var storyboardSetup: LightingSetup?
    @State private var openingStoryboard = false
    /// The scan is loaded once. SwiftUI restarts `.task` when the screen reappears (after the storyboard or the
    /// shoot planner was pushed on top); loading again built a second scene the 3D view never showed, so the
    /// Mesh / Photo / Model switch seemed to do nothing.
    @State private var loadStarted = false
    /// The scan as renamed on this screen (the Name row), else nil; `current` is the scan with its latest name.
    @State private var renamed: SavedScan?
    @State private var renaming = false
    @State private var newTitle = ""
    private var current: SavedScan { renamed ?? scan }
    /// The model under the room's name, for Share (else the scan's file, "Scan_<date>.usdz").
    @State private var shareURL: URL?
    /// Debug builds only: -openPlanner opens the shoot planner once the room is loaded.
    @State private var openingPlanner = false

    var body: some View {
        VStack(spacing: 0) {
            ZStack(alignment: .top) {
                if let scene {
                    RoomSceneView(scene: scene, topView: topView, topTurn: room.map(Self.squaringTurn) ?? 0,
                                  floorY: (lightingOn || objectsOn || walk == .choosing) ? walkFloorY : nil,
                                  onFloorTap: floorTapped,
                                  standAt: walk.standingAt, walkFloorY: walkFloorY, walkMove: walkMove,
                                  eyeHeight: Float(min(eyeHeight, maxEyeHeight)))
                } else if let loadError {
                    Text(loadError).padding()
                } else {
                    ProgressView()
                }
                if lightingOn, let scene, let lighting {
                    // Under the controls row, so a large view never covers the buttons.
                    ShotPreviewInset(scene: scene, setup: lighting, large: $shotLarge)
                        .padding(.horizontal, shotLarge ? 16 : 10)
                        .padding(.top, shotLarge ? controlsHeight + 6 : 0)
                        .padding(.bottom, shotLarge ? 16 : 10)
                        .frame(maxWidth: .infinity, maxHeight: .infinity,
                               alignment: shotLarge ? .center : .bottomTrailing)
                }
                // Must fit a 375-pt-wide phone: compact controls in one row, or two rows if they still don't fit
                // (large text). A row wider than the screen would also push the panel below off both edges.
                // The mode buttons carry a word as well as an icon whenever there's room for it.
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) {
                        modeButtons(labeled: true)
                        Spacer(minLength: 4)
                        viewPickers
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(spacing: 8) { modeButtons(labeled: true) }
                        HStack(spacing: 8) { viewPickers }
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(spacing: 8) { modeButtons(labeled: false) }
                        HStack(spacing: 8) { viewPickers }
                    }
                }
                .padding(10)
                // One row or two: the large camera view starts below them.
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { controlsHeight = $0 }
            }
            .overlay(alignment: .bottomLeading) {
                if scene != nil, walkFloorY != nil { walkControls.padding(12) }
            }
            .overlay(alignment: .bottom) {
                if let training, walk == .off {
                    TrainingStatus(run: training)
                        .padding(10)
                        .allowsHitTesting(false)
                }
            }
            // With the room summary below, the 3D view keeps a fixed share of the screen and the summary scrolls in
            // the rest (it only had 260 pt, too little for large text). Panels (objects, lighting) keep the old split.
            .frame(maxWidth: .infinity, maxHeight: summaryShown ? UIScreen.main.bounds.height * 0.42 : .infinity)
            .background(Color.black)
            // The 3D area is always black, so its controls use dark styling even when the phone is in light mode.
            .environment(\.colorScheme, .dark)

            if objectsOn, let shell {
                ObjectsPanel(objects: $objects, selected: $selectedObject, floorY: shell.floorY,
                             claudeChecked: claudeChecked, finding: finding, error: findError, onFind: findObjects)
                    .frame(maxHeight: 340)
            } else if lightingOn, lighting != nil {
                LightingPanel(setup: Binding(get: { lighting! }, set: { lighting = $0 }),
                              planning: planningShot, planError: planError, onPlan: planShot,
                              onStoryboard: {
                                  storyboardSetup = lighting
                                  openingStoryboard = true
                              })
                    .frame(maxHeight: 340)
            } else if let room, walk == .off {
                RoomSummary(room: room, name: current.title, onRename: {
                                newTitle = current.title ?? ""
                                renaming = true
                            }, capture: capture, foundWindows: foundWindows, objects: objects, improved: improved,
                            onImprove: WorldMapStore.exists(scan) ? onImprove : nil, outletCount: outlets.count)
                    .frame(maxHeight: .infinity)
            }
        }
        // A title menu here would push Plan a Shoot and Share into an overflow menu; rooms are named on Home.
        .navigationTitle(current.title ?? scan.date.formatted(.dateTime.day().month(.abbreviated).hour().minute()))
        .modifier(ImprovedSubtitle(date: improved.last))
        .navigationBarTitleDisplayMode(.inline)
        // Black bar above the black 3D view, with light title, buttons and status bar in light mode too.
        .toolbarBackground(Color.black, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .toolbar {
            if let room {
                NavigationLink(destination: PlanningLaunchView(room: room, scan: current, objects: objects)) {
                    Label("Storyboard", systemImage: "rectangle.stack")
                }
                NavigationLink(destination: ShootPlannerView(
                    room: room, extraWindowAreas: foundWindows.filter { !$0.matchesRoomPlan }.map(\.area),
                    objects: objects)) {
                    Label("Plan a Shoot", systemImage: "sparkles")
                }
            }
            if SyncStore.info(current).shared {
                Image(systemName: "person.2.fill")
                    .foregroundStyle(Color.accentColor)
                    .accessibilityLabel("Shared: syncs with other phones")
            }
            if exportingGLB {
                ProgressView()
            } else if photoRoom != nil || capture != nil {
                Menu {
                    Button { sendingNearby = true } label: { Label("Send to nearby phone", systemImage: "iphone.radiowaves.left.and.right") }
                    Button(action: exportRmsc) { Label("RoomScape scan (.rmsc)", systemImage: "shippingbox") }
                    if photoRoom != nil {
                        Button(action: exportGLB) { Label("Photo room for a computer (.glb)", systemImage: "desktopcomputer") }
                    }
                    ShareLink(item: shareURL ?? scan.modelURL) { Label("Room model (.usdz)", systemImage: "cube") }
                } label: {
                    Label("Share", systemImage: "square.and.arrow.up")
                }
            } else {
                ShareLink(item: shareURL ?? scan.modelURL)
            }
        }
        .sheet(isPresented: $sendingNearby) { SendNearbySheet(scan: current) }
        .sheet(item: $glbFile) { file in
            ActivitySheet(items: [file.url]).ignoresSafeArea()
        }
        .alert("Couldn't export the scan", isPresented: Binding(get: { glbError != nil }, set: { if !$0 { glbError = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(glbError ?? "")
        }
        .modifier(RenameRoomAlert(isPresented: $renaming, title: $newTitle, onSave: saveName))
        .onDisappear { training?.pause() }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { training?.pause() }
        }
        .navigationDestination(isPresented: $openingStoryboard) {
            if let room {
                PlanningLaunchView(room: room, scan: current, objects: objects, addSetup: storyboardSetup)
            }
        }
        .navigationDestination(isPresented: $openingPlanner) {
            if let room {
                ShootPlannerView(room: room, extraWindowAreas: foundWindows.filter { !$0.matchesRoomPlan }.map(\.area),
                                 objects: objects)
            }
        }
        .onChange(of: lighting) { _, setup in
            if lightingOn { lightingScene?.apply(setup) }
        }
        .onChange(of: objects) { _, objects in
            objectsScene?.apply(objects, selected: selectedObject)
            modelObjects?.apply(objects)
            if lighting != nil { refreshRoomLight() }
            RoomObjects.save(ObjectsFile(claudeChecked: claudeChecked, objects: objects), for: scan)
        }
        .onChange(of: selectedObject) { _, id in
            objectsScene?.apply(objects, selected: id)
        }
        .onChange(of: style) { _, newStyle in
            // The preview lights the splats and objects move splats; RoomPlan's model has neither.
            if newStyle == .model, lightingOn { toggleLighting() }
            if newStyle == .model, objectsOn { toggleObjects() }
            showStyle(newStyle)
        }
        .task {
            guard !loadStarted else { return }
            loadStarted = true
            // Decoding the room and loading the USDZ can take a few seconds, so keep it off the main thread.
            let scan = scan
            capture = await Task.detached(priority: .utility) { CaptureStore.loadMeta(scan.captureURL) }.value
            improved = await Task.detached(priority: .utility) { ImproveHistory.dates(scan) }.value
            shareURL = await Task.detached(priority: .utility) { ScanStore.namedModelCopy(scan) }.value
            let loaded = await Task.detached(priority: .userInitiated) { () -> (CapturedRoom?, Result<(SCNScene, SCNNode), Error>) in
                let room = ScanStore.loadRoom(scan)
                let result = Result { () throws -> (SCNScene, SCNNode) in
                    let s = try SCNScene(url: scan.modelURL, options: nil)
                    ResultView.colorize(s.rootNode)
                    // Gather the model under one node so the splat view can hide it.
                    let group = SCNNode()
                    for child in s.rootNode.childNodes { group.addChildNode(child) }
                    s.rootNode.addChildNode(group)
                    return (s, group)
                }
                return (room, result)
            }.value
            room = loaded.0
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("-openPlanner"), room != nil { openingPlanner = true }
            if ProcessInfo.processInfo.arguments.contains("-topView") { topView = true }
            if ProcessInfo.processInfo.arguments.contains("-shotLarge") { shotLarge = true }
            #endif
            switch loaded.1 {
            case .success(let (s, group)):
                scene = s
                modelGroup = group
            case .failure(let error):
                loadError = "Could not open the 3D model: \(error.localizedDescription)"
            }

            // Windows from the photos and LiDAR: RoomPlan misses many (curtained ones especially). Shown on the walls
            // in both styles, counted in the summary and given to the shoot planner.
            if let room = loaded.0, let scene, capture != nil {
                let windows = await Task.detached(priority: .userInitiated) { WindowDetection.load(scan, room: room) }.value
                foundWindows = windows
                scene.rootNode.addChildNode(WindowDetection.node(windows, room: room))
            }

            // Power outlets, as orange labels in every view (like the Android app).
            if let scene {
                let found = await Task.detached(priority: .utility) { OutletStore.load(scan) }.value
                outlets = found
                if !found.isEmpty {
                    // RoomPlan's floor; scans imported without one were moved so their floor is at 0.
                    let floor = room.map { RoomShell(room: $0).floorY } ?? 0
                    scene.rootNode.addChildNode(OutletStore.node(found, floorY: floor))
                }
            }

            // Photo room, when the scan has a mesh and photos: built in the background, shown by Photo.
            if let scene {
                // Painted from the mesh and photos; for a scan imported without a mesh, the package's own room.glb.
                let photo = await Task.detached(priority: .userInitiated) {
                    PhotoRoom.build(scan) ?? GLBScene.node(forScan: scan.captureURL)
                }.value
                if let photo {
                    scene.rootNode.addChildNode(photo)
                    photoRoom = photo
                    style = .photo
                    showStyle(style)
                }
            }

            // Splats, when the scan has points: built in the background (cached after the first time), then shown
            // instead of RoomPlan's boxes. The Splats / Model switch flips between the two.
            guard let room = loaded.0, let scene, (capture?.pointCount ?? 0) > 0 else { return }
            buildingSplats = true
            let shell = RoomShell(room: room)
            // Objects: what was saved (with the user's edits), else RoomPlan's furniture.
            let saved = RoomObjects.load(scan)
            let objects = saved?.objects ?? RoomObjects.fromRoomPlan(room)
            // Shown: the trained splats when the scan was trained before, else the raw ones (also the training's start),
            // tidied for viewing (see `SplatTidy`).
            let built = await Task.detached(priority: .userInitiated) { () -> ([SurfelRecord], SplatTidy.Result, ObjectsScene.Built)? in
                guard let base = ScanStore.loadSurfels(scan, room: room), !base.isEmpty else { return nil }
                let tidy = SplatTidy.tidy(ScanStore.loadTrainedSurfels(scan) ?? base, shell: shell)
                print("splats shown: \(tidy.surfels.count) (dropped \(tidy.outside) outside the room, \(tidy.floaters) "
                      + "floaters; \(tidy.onCeiling) on the ceiling)")
                return (base, tidy, ObjectsScene.build(tidy.surfels, objects: objects, floorY: shell.floorY))
            }.value
            buildingSplats = false
            guard let (base, tidy, parts) = built else { return }
            let surfels = tidy.surfels
            let splats = SCNNode()
            splats.addChildNode(SplatScene.shellNode(shell, colors: tidy.colors))
            let objectsScene = ObjectsScene()
            objectsScene.install(parts, objects: objects)
            splats.addChildNode(objectsScene.root)
            splats.isHidden = true
            scene.rootNode.addChildNode(splats)
            splatGroup = splats
            self.surfels = surfels
            self.objectsScene = objectsScene
            if let modelGroup {
                let modelObjects = ModelObjects(model: modelGroup)
                modelObjects.apply(objects)
                self.modelObjects = modelObjects
            }
            self.objects = objects
            claudeChecked = saved?.claudeChecked ?? false
            let lightingScene = LightingScene()
            lightingScene.register(splats)
            scene.rootNode.addChildNode(lightingScene.root)
            self.lightingScene = lightingScene
            self.shell = shell
            // Photo is the default look: the photo room, or the splats in their scanned colours.
            style = .photo
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("-stylePhoto") { style = .photo }
            #endif
            let training = SplatTrainingRun(scan: scan, base: base, shell: shell)
            training.onPreview = { showSplats($0) }
            self.training = training
            #if DEBUG
            // UI checks in the Simulator: -openPanel lighting | objects
            let args = ProcessInfo.processInfo.arguments
            if let i = args.firstIndex(of: "-openPanel"), i + 1 < args.count {
                if args[i + 1] == "lighting" {
                    toggleLighting()
                    if let j = args.firstIndex(of: "-roomDimmer"), j + 1 < args.count, let v = Float(args[j + 1]) {
                        lighting?.roomDimmer = v
                    }
                    if let j = args.firstIndex(of: "-cameraHeight"), j + 1 < args.count, let v = Float(args[j + 1]) {
                        lighting?.cameraHeight = v
                    }
                }
                if args[i + 1] == "objects" { toggleObjects() }
                if args.contains("-storyboardView") {
                    // The storyboard as saved (no new view), opened on the view named after the flag.
                    storyboardSetup = nil
                    openingStoryboard = true
                } else if args.contains("-openStoryboard"), lighting != nil {
                    // The lighting setup as a new storyboard view.
                    storyboardSetup = lighting
                    openingStoryboard = true
                }
            }
            if args.contains("-replayFind"), let saved {
                // Replays the end of a Claude search with the saved result, starting from RoomPlan's objects only.
                let found = saved.objects
                let roomPlanOnly = RoomObjects.fromRoomPlan(room)
                let built0 = await Task.detached { ObjectsScene.build(surfels, objects: roomPlanOnly, floorY: shell.floorY) }.value
                objectsScene.install(built0, objects: roomPlanOnly)
                self.objects = roomPlanOnly
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                print("debug: replaying find with \(found.count) objects")
                await applyFound(found, previousCount: roomPlanOnly.count, surfels: surfels, floorY: shell.floorY)
                print("debug: find applied")
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
            if let i = args.firstIndex(of: "-selectKind"), i + 1 < args.count {
                for kind in args[i + 1].split(separator: ",") {
                    print("debug: selecting first \(kind)")
                    selectedObject = objects.first { $0.kind.rawValue == kind }?.id
                    try? await Task.sleep(nanoseconds: 2_000_000_000)
                    print("debug: selected \(kind), still alive")
                }
            }
            if args.contains("-styleModel") {
                Task {
                    try? await Task.sleep(nanoseconds: 3_000_000_000)
                    print("debug: switching to model; splatGroup hidden before:", splatGroup?.isHidden as Any)
                    style = .model
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                    print("debug: after switch: style", style, "splatGroup hidden:", splatGroup?.isHidden as Any,
                          "model hidden:", modelGroup?.isHidden as Any)
                }
            }
            // Training on the phone without taps: -train <seconds> [-snapshotPhoto <n>] trains, pauses, and with a photo
            // number saves Documents/debug-before.png and debug-after.png, the splats seen from that photo's camera.
            if let i = args.firstIndex(of: "-train"), i + 1 < args.count, let seconds = Double(args[i + 1]) {
                let photo = args.firstIndex(of: "-snapshotPhoto").flatMap { $0 + 1 < args.count ? Int(args[$0 + 1]) : nil }
                if let photo {
                    try? await Task.sleep(nanoseconds: 3_000_000_000) // SceneKit's first frames skip the splat shaders
                    debugSnapshot(scene, photo: photo, name: "before")
                }
                training.start()
                try? await Task.sleep(nanoseconds: UInt64(seconds * 1e9))
                let live = training.progress
                training.pause()
                while training.state == .training || training.state == .preparing {
                    try? await Task.sleep(nanoseconds: 200_000_000)
                }
                try? await Task.sleep(nanoseconds: 3_000_000_000) // the last splats swap in
                let report = "training \(training.state): \(live) / \(training.progress), showing \(self.surfels.count) splats"
                print("debug: \(report)")
                try? report.write(to: URL.documentsDirectory.appendingPathComponent("debug-training.txt"), atomically: true, encoding: .utf8)
                if let photo { debugSnapshot(scene, photo: photo, name: "after") }
            }
            #endif
        }
    }

    #if DEBUG
    /// Renders the 3D view from a keyframe photo's camera into Documents/debug-<name>.png.
    private func debugSnapshot(_ scene: SCNScene, photo index: Int, name: String) {
        guard let data = try? Data(contentsOf: scan.captureURL.appendingPathComponent("keyframes.json")),
              let records = try? JSONDecoder().decode([KeyframeRecord].self, from: data), index < records.count else { return }
        let r = records[index], m = r.cameraToWorld
        let camera = SCNNode()
        camera.camera = RoomSceneView.makeCamera()
        camera.camera?.projectionDirection = .vertical
        camera.camera?.fieldOfView = CGFloat(2 * atan(Float(r.height) / 2 / r.fy) * 180 / .pi)
        camera.simdTransform = simd_float4x4(SIMD4(m[0], m[1], m[2], m[3]), SIMD4(m[4], m[5], m[6], m[7]),
                                             SIMD4(m[8], m[9], m[10], m[11]), SIMD4(m[12], m[13], m[14], m[15]))
        scene.rootNode.addChildNode(camera)
        let renderer = SCNRenderer(device: MTLCreateSystemDefaultDevice(), options: nil)
        renderer.scene = scene
        renderer.pointOfView = camera
        renderer.autoenablesDefaultLighting = true
        let image = renderer.snapshot(atTime: 0, with: CGSize(width: r.width, height: r.height), antialiasingMode: .none)
        camera.removeFromParentNode()
        let url = URL.documentsDirectory.appendingPathComponent("debug-\(name).png")
        try? image.pngData()?.write(to: url)
        print("debug: saved \(url.lastPathComponent) from photo \(r.file)")
    }
    #endif

    /// Lighting and objects toggles (only once the splats are ready), or the "building" note.
    @ViewBuilder private func modeButtons(labeled: Bool) -> some View {
        if splatGroup != nil {
            Button {
                toggleLighting()
            } label: {
                ModeLabel(title: "Lighting", icon: lightingOn ? "lightbulb.fill" : "lightbulb", labeled: labeled)
            }
            .buttonStyle(.bordered)
            .tint(lightingOn ? .yellow : nil)
            .accessibilityLabel(lightingOn ? "Turn off the lighting preview" : "Lighting preview")
            Button {
                toggleObjects()
            } label: {
                ModeLabel(title: "Objects", icon: objectsOn ? "cube.fill" : "cube", labeled: labeled)
            }
            .buttonStyle(.bordered)
            .tint(objectsOn ? .yellow : nil)
            .accessibilityLabel(objectsOn ? "Close objects" : "Objects in the room")
            if let training {
                TrainButton(run: training, labeled: labeled) { style = .photo } // training sharpens the colours
            }
        } else if buildingSplats {
            Label("Building splats…", systemImage: "circle.dotted")
                .font(.caption.weight(.medium))
                .padding(.horizontal, 10).padding(.vertical, 6)
                .background(.regularMaterial, in: Capsule())
        }
    }

    /// Splats / Model (once splats exist) and 3D / top view.
    @ViewBuilder private var viewPickers: some View {
        if splatGroup != nil {
            Picker("Style", selection: $style) {
                Text("Mesh").tag(Style.mesh)
                Text("Photo").tag(Style.photo)
                Text("Model").tag(Style.model)
            }
            .pickerStyle(.segmented)
            .fixedSize()
        }
        Picker("View", selection: $topView) {
            Image(systemName: "rotate.3d").accessibilityLabel("3D view").tag(false)
            Image(systemName: "square.grid.3x3").accessibilityLabel("Top view").tag(true)
        }
        .pickerStyle(.segmented)
        .fixedSize()
    }

    private func saveName() {
        let scan = ScanStore.rename(current, to: newTitle)
        renamed = scan
        Task { shareURL = await Task.detached(priority: .utility) { ScanStore.namedModelCopy(scan) }.value }
    }

    private func floorTapped(_ p: SIMD3<Float>) {
        if walk == .choosing {
            walk = .inside(p)
        } else if objectsOn {
            // Move the selected object so its centre stands over the tapped spot.
            guard let id = selectedObject, let i = objects.firstIndex(where: { $0.id == id }),
                  !objects[i].kind.isMarker, !objects[i].removed else { return }
            objects[i].offset = SIMD3(p.x - objects[i].center.x, 0, p.z - objects[i].center.z)
        } else if lightingOn {
            lighting?.subjectX = p.x
            lighting?.subjectZ = p.z
        }
    }

    /// Writes the photo room as RoomScape-<scan date>.glb in the temporary folder and opens the share sheet for it.
    /// Writes the whole scan as a .rmsc package (see `Rmsc`) and opens the share sheet for it.
    private func exportRmsc() {
        exportingGLB = true
        let scan = current
        Task {
            let result = await Task.detached(priority: .userInitiated) { () -> Result<URL, Error> in
                Result { try Rmsc.export(scan) }
            }.value
            exportingGLB = false
            switch result {
            case .success(let url): glbFile = SharedFile(url: url)
            case .failure(let error): glbError = error.localizedDescription
            }
        }
    }

    private func exportGLB() {
        exportingGLB = true
        let scan = scan
        Task {
            let url = await Task.detached(priority: .userInitiated) { () -> URL? in
                guard let result = PhotoRoomExport.glb(folder: scan.captureURL) else { return nil }
                let stamp = scan.captureURL.deletingPathExtension().lastPathComponent.replacingOccurrences(of: "Scan_", with: "")
                let url = FileManager.default.temporaryDirectory.appendingPathComponent("RoomScape-\(stamp).glb")
                do { try result.data.write(to: url, options: .atomic) } catch { return nil }
                print("photo room .glb: \(result.data.count) bytes, \(result.photos) photos at \(result.textureWidth) px, "
                      + "\(result.triangles) triangles (\(result.uncovered) uncovered)")
                return url
            }.value
            exportingGLB = false
            if let url { glbFile = SharedFile(url: url) } else { glbError = "This scan has no saved mesh or photos to export." }
        }
    }

    /// The room summary list shows under the 3D view (no objects or lighting panel, not standing in the room).
    private var summaryShown: Bool { !objectsOn && !lightingOn && room != nil && walk == .off }

    /// The floor height for standing in the room: RoomPlan's floor plane.
    private var walkFloorY: Float? {
        // Imported scans without RoomPlan's room: the bottom of the photo room.
        shell?.floorY ?? room.map { RoomShell(room: $0).floorY } ?? photoRoom.map { Float($0.boundingBox.min.y) + $0.simdPosition.y }
    }

    /// Highest eye height: 2.2 m, and 10 cm under RoomPlan's ceiling.
    private var maxEyeHeight: Double {
        let s = shell ?? room.map { RoomShell(room: $0) }
        guard let s, let ceiling = s.ceilingY else { return 2.2 }
        return max(0.3, min(2.2, Double(ceiling - s.floorY) - 0.1))
    }

    /// Enter the room / the floor hint / ▲▼ and Exit while standing in it.
    @ViewBuilder private var walkControls: some View {
        switch walk {
        case .off:
            Button {
                if lightingOn { toggleLighting() }
                if objectsOn { toggleObjects() }
                walk = .choosing
            } label: {
                Label("Enter the room", systemImage: "figure.walk")
            }
            .buttonStyle(.borderedProminent)
        case .choosing:
            HStack(spacing: 8) {
                Label("Tap the floor where you want to stand", systemImage: "hand.tap")
                    .font(.callout)
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(.regularMaterial, in: Capsule())
                Button("Cancel") { walk = .off }
                    .buttonStyle(.bordered)
            }
        case .inside:
            HStack(alignment: .bottom, spacing: 12) {
                Button {
                    walkMove = 0
                    walk = .off
                } label: {
                    Label("Exit", systemImage: "arrow.uturn.backward")
                }
                .buttonStyle(.borderedProminent)
                Spacer()
                // Eye height: taller / shorter, 10 cm a tap.
                VStack(spacing: 4) {
                    Button { eyeHeight = min(maxEyeHeight, (eyeHeight + 0.1)) } label: { Image(systemName: "chevron.up") }
                        .accessibilityLabel("Taller")
                    Text(String(format: "%.2f m", min(eyeHeight, maxEyeHeight)))
                        .font(.caption.monospacedDigit())
                    Button { eyeHeight = max(0.3, min(eyeHeight, maxEyeHeight) - 0.1) } label: { Image(systemName: "chevron.down") }
                        .accessibilityLabel("Shorter")
                }
                .buttonStyle(.bordered)
                .padding(6)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                VStack(spacing: 8) {
                    HoldButton(systemImage: "arrowtriangle.up.fill") { walkMove = $0 ? 1 : 0 }
                    HoldButton(systemImage: "arrowtriangle.down.fill") { walkMove = $0 ? -1 : 0 }
                }
            }
            .frame(maxWidth: .infinity)
            .overlay(alignment: .top) {
                Text("Drag to look · pinch to zoom · double-tap the floor to walk there")
                    .font(.caption2)
                    .padding(.horizontal, 8).padding(.vertical, 4)
                    .background(.regularMaterial, in: Capsule())
                    .offset(y: -36)
            }
        }
    }

    /// Shows the part of the scene for a style: RoomPlan's model, the photo room, or the splats (mesh look or colours).
    private func showStyle(_ style: Style) {
        let photos = style == .photo && photoRoom != nil
        modelGroup?.isHidden = style != .model
        photoRoom?.isHidden = !photos
        splatGroup?.isHidden = style == .model || photos
        if let splatGroup { SplatScene.setMeshLook(style != .photo, in: splatGroup) }
    }

    private func toggleObjects() {
        objectsOn.toggle()
        if objectsOn {
            // Objects move splats: not in RoomPlan's model or the photo room.
            if style == .model || (style == .photo && photoRoom != nil) { style = .mesh }
            if lightingOn { toggleLighting() }
        } else {
            selectedObject = nil
        }
    }

    /// Asks Claude for everything in the photos, then splits the splats again for the new set of objects.
    private func findObjects() {
        guard let room, let shell, finding == nil else { return }
        findError = nil
        finding = "Preparing photos…"
        let scan = scan, current = objects, surfels = surfels
        Task {
            do {
                let found = try await ObjectFinder.find(scan: scan, room: room, objects: current) { p in
                    Task { @MainActor in finding = "Claude is looking at the photos… \(p.done)/\(p.total)" }
                }
                await applyFound(found, previousCount: current.count, surfels: surfels, floorY: shell.floorY)
            } catch {
                finding = nil
                findError = error.localizedDescription
            }
        }
    }

    /// Shows new splats (from training): split them by object again and swap them in. While one swap is being built,
    /// only the newest waiting set is kept.
    private func showSplats(_ splats: [SurfelRecord]) {
        guard let shell, !rebuildingSplats else {
            pendingSplats = splats
            return
        }
        rebuildingSplats = true
        let current = objects
        Task {
            let (tidied, parts) = await Task.detached(priority: .userInitiated) {
                let tidied = SplatTidy.tidy(splats, shell: shell).surfels
                return (tidied, ObjectsScene.build(tidied, objects: current, floorY: shell.floorY))
            }.value
            objectsScene?.install(parts, objects: current)
            objectsScene?.apply(objects, selected: selectedObject)
            surfels = tidied
            if let splatGroup {
                // The new splat nodes start in the mesh look.
                SplatScene.setMeshLook(style != .photo, in: splatGroup)
                lightingScene?.reregister(splatGroup)
            }
            if lighting != nil { refreshRoomLight() }
            if lightingOn { lightingScene?.apply(lighting) }
            rebuildingSplats = false
            if let next = pendingSplats {
                pendingSplats = nil
                showSplats(next)
            }
        }
    }

    /// After a Claude search: split the splats for the new set of objects and show them.
    private func applyFound(_ found: [RoomObject], previousCount: Int, surfels: [SurfelRecord], floorY: Float) async {
        finding = "Placing what it found…"
        let parts = await Task.detached(priority: .userInitiated) {
            ObjectsScene.build(surfels, objects: found, floorY: floorY)
        }.value
        objectsScene?.install(parts, objects: found)
        if let splatGroup {
            // The new splat nodes start in the mesh look.
            SplatScene.setMeshLook(style != .photo, in: splatGroup)
            lightingScene?.reregister(splatGroup)
        }
        if lightingOn { lightingScene?.apply(lighting) }
        claudeChecked = true
        objects = found
        finding = nil
        if found.count == previousCount { findError = "Claude found nothing new." }
    }

    /// The room's own light sources and power points for the lighting preview: room lights and lamps from the
    /// objects (as they stand after the user's edits; removed ones are off), windows from RoomPlan and the photos.
    /// Calibrated against a sample of the scanned splats so a typical surface gets the scan's 250 lx.
    private func refreshRoomLight() {
        guard var setup = lighting, let shell else { return }
        var sources: [RoomLightSource] = []
        for o in shell.openings where o.wall.halfWidth > 0 && o.isWindow {
            sources.append(RoomLightSource(kind: .window, label: "Window", position: o.wall.center,
                                           direction: o.wall.inward,
                                           strength: 3 * 4 * o.wall.halfWidth * o.wall.halfHeight))
        }
        for w in foundWindows where !w.matchesRoomPlan && w.wallIndex < shell.walls.count {
            let wall = shell.walls[w.wallIndex]
            let c = wall.center + wall.right * ((w.minAlong + w.maxAlong) / 2) + wall.up * ((w.minUp + w.maxUp) / 2)
            sources.append(RoomLightSource(kind: .window, label: w.label, position: c, direction: wall.inward,
                                           strength: 3 * w.area))
        }
        sources.sort { $0.strength > $1.strength }
        sources = Array(sources.prefix(3))
        var fittings: [RoomLightSource] = []
        for o in objects where !o.removed {
            let p = o.center + o.offset
            switch o.kind {
            case .ceilingLight:
                fittings.append(RoomLightSource(kind: .ceiling, label: o.label, position: p, direction: SIMD3(0, -1, 0), strength: 1))
            case .wallLight:
                fittings.append(RoomLightSource(kind: .wall, label: o.label, position: p, direction: .zero, strength: 1))
            case .lamp:
                fittings.append(RoomLightSource(kind: .lamp, label: o.label, position: p + SIMD3(0, o.halfExtents.y, 0),
                                                direction: .zero, strength: 0.5))
            default: break
            }
        }
        // The shader takes eight: windows first, then the fittings nearest the person.
        let face = setup.faceCenter
        fittings.sort { simd_distance($0.position, face) < simd_distance($1.position, face) }
        sources += fittings.prefix(LightingSetup.maxRoomSources - sources.count)
        setup.roomSources = sources
        setup.power = objects.filter { $0.kind.isPower && !$0.removed }.map { PowerPoint(label: $0.label, position: $0.center) }
        let step = max(1, surfels.count / 2000)
        let samples = Swift.stride(from: 0, to: surfels.count, by: step).compactMap { i -> (p: SIMD3<Float>, n: SIMD3<Float>)? in
            let s = surfels[i]
            let n = SIMD3(s.nx, s.ny, s.nz)
            return simd_length_squared(n) > 0.25 ? (SIMD3(s.x, s.y, s.z), n) : nil
        }
        setup.calibrate(samples)
        if setup != lighting { lighting = setup }
    }

    /// Asks Claude for a setup for this kind of shot (see `ShotDirector`) and shows it.
    private func planShot(_ scene: SceneType?, wish: String) {
        guard let setup = lighting, let shell, !planningShot else { return }
        planningShot = true
        planError = nil
        let objects = objects, gear = GearStore.load()
        Task {
            do {
                lighting = try await ShotDirector.propose(scene: scene, note: wish, setup: setup, shell: shell,
                                                          objects: objects, gear: gear)
                // The room lights nearest the person's new spot.
                refreshRoomLight()
            } catch {
                planError = error.localizedDescription
            }
            planningShot = false
        }
    }

    private func toggleLighting() {
        lightingOn.toggle()
        guard lightingOn else {
            lightingScene?.apply(nil)
            return
        }
        if style == .model || (style == .photo && photoRoom != nil) { style = .mesh }
        if objectsOn { toggleObjects() }
        if lighting == nil, let shell {
            // Start in the middle of the room with the longest wall behind the person as the background.
            let center = shell.walls.reduce(SIMD3<Float>(), { $0 + $1.center }) / Float(max(1, shell.walls.count))
            let facing: Float = shell.walls.max(by: { $0.halfWidth < $1.halfWidth }).map {
                atan2($0.inward.x, $0.inward.z) * 180 / .pi
            } ?? 0
            lighting = .starting(subjectX: center.x, subjectZ: center.z, facing: facing, floorY: shell.floorY,
                                 gear: GearStore.load())
        }
        refreshRoomLight()
        lightingScene?.apply(lighting)
    }

    /// How far to turn the top view so the room's longest wall lies square to the screen (at most 45°), as in
    /// the storyboard's floor plan.
    nonisolated static func squaringTurn(_ room: CapturedRoom) -> Float {
        guard let longest = room.walls.max(by: { $0.dimensions.x < $1.dimensions.x }) else { return 0 }
        let r = longest.transform.columns.0
        return -remainder(atan2(r.z, r.x), .pi / 2)
    }

    /// Tints RoomPlan's grey boxes by type so windows (natural light) and doors stand out.
    ///
    /// Each piece is colored by its own name ("Wall0", "Door0", "Window0"…), not its group's: RoomPlan puts a wall's
    /// doors and windows inside that wall's "Wall_N_grp" group, so coloring whole groups would paint them as walls.
    nonisolated static func colorize(_ node: SCNNode) {
        if let geometry = node.geometry, let color = color(forPartNamed: node.name ?? "") {
            let m = SCNMaterial()
            m.diffuse.contents = color
            m.lightingModel = .physicallyBased
            m.roughness.contents = 0.8
            m.isDoubleSided = true
            node.geometry = geometry.copy() as? SCNGeometry
            node.geometry?.materials = [m]
        }
        node.childNodes.forEach(colorize)
    }

    nonisolated private static func color(forPartNamed rawName: String) -> UIColor? {
        let name = rawName.lowercased()
        if name.contains("window") { return UIColor(red: 0.45, green: 0.75, blue: 1.0, alpha: 0.85) }
        if name.contains("door") { return UIColor(red: 0.65, green: 0.45, blue: 0.3, alpha: 1) }
        if name.contains("opening") { return UIColor(red: 0.5, green: 0.9, blue: 0.6, alpha: 0.7) }
        if name.contains("wall") { return UIColor(white: 0.85, alpha: 1) }
        if name.contains("floor") { return UIColor(white: 0.45, alpha: 1) }
        return nil
    }
}

/// SceneKit view with orbit / pinch / pan controls and a switchable top-down camera.
struct RoomSceneView: UIViewRepresentable {
    let scene: SCNScene
    let topView: Bool
    /// Turns the top view about the vertical (radians), so the room can sit square on the screen.
    var topTurn: Float = 0
    /// When set, a tap on the 3D view reports where it hits the floor plane at this height.
    var floorY: Float? = nil
    var onFloorTap: (SIMD3<Float>) -> Void = { _ in }
    /// Standing in the room (first person) at this floor point; nil = the orbit or top view.
    var standAt: SIMD3<Float>? = nil
    /// The floor, for walking to a double-tapped spot.
    var walkFloorY: Float? = nil
    /// Held ▲ (+1) / ▼ (-1): walk forward or back, level, in the look direction.
    var walkMove = 0
    /// Eye height above the floor while standing, metres.
    var eyeHeight: Float = 1.5

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate, SCNSceneRendererDelegate {
        /// The 3D view's height in points, for sizing labels on screen (set on the main thread, read when rendering).
        var viewHeight: CGFloat = 0

        func renderer(_ renderer: SCNSceneRenderer, updateAtTime time: TimeInterval) {
            guard let scene = renderer.scene, let pov = renderer.pointOfView else { return }
            OutletStore.screenSizes(in: scene, camera: pov, viewHeight: viewHeight > 0 ? viewHeight : 360, fov: pov.camera?.fieldOfView ?? 60)
        }

        // Orbit, our own instead of SceneKit's camera control (whose two-finger pan often turned into a rotation):
        // one finger turns around the target, two fingers pan and pinch-zoom together, like the Android app.
        var orbitTarget = SIMD3<Float>(0, 0, 0)
        var orbitGestures: [UIGestureRecognizer] = []
        private var spin = SIMD2<Float>(0, 0)
        private var inertia: CADisplayLink?
        private var lastInertia: CFTimeInterval = 0
        /// Radians per point of drag (about the Android app's feel).
        static let turn: Float = 0.006

        /// Turns the current camera around the target; the pitch stays between just under level and straight down.
        func orbitBy(dx: Float, dy: Float) {
            guard let view, let pov = view.pointOfView, pov !== topCamera, standing == nil else { return }
            var off = pov.simdWorldPosition - orbitTarget
            off = simd_quatf(angle: -dx * Self.turn, axis: SIMD3(0, 1, 0)).act(off)
            let right = simd_normalize(simd_cross(SIMD3<Float>(0, 1, 0), off))
            let tilted = simd_quatf(angle: -dy * Self.turn, axis: right).act(off) // drag up tilts the view up (rk)
            let elevation = asin(max(-1, min(1, tilted.y / simd_length(tilted))))
            if elevation > -0.15, elevation < 1.5 { off = tilted }
            pov.simdWorldPosition = orbitTarget + off
            pov.simdLook(at: orbitTarget, up: SIMD3(0, 1, 0), localFront: SIMD3(0, 0, -1))
        }

        @objc func orbitPanned(_ g: UIPanGestureRecognizer) {
            guard let view = g.view else { return }
            switch g.state {
            case .began:
                inertia?.invalidate(); inertia = nil
            case .changed:
                let d = g.translation(in: view)
                g.setTranslation(.zero, in: view)
                orbitBy(dx: Float(d.x), dy: Float(d.y))
            case .ended:
                // A light flick keeps turning for a moment.
                let v = g.velocity(in: view)
                spin = SIMD2(Float(v.x), Float(v.y)) * 0.6
                lastInertia = CACurrentMediaTime()
                let link = CADisplayLink(target: self, selector: #selector(coast))
                link.add(to: .main, forMode: .common)
                inertia = link
            default:
                break
            }
        }

        @objc func coast() {
            let now = CACurrentMediaTime()
            let dt = Float(min(0.05, now - lastInertia))
            lastInertia = now
            orbitBy(dx: spin.x * dt, dy: spin.y * dt)
            spin *= pow(0.02, dt) // most of it gone within a second
            if simd_length(spin) < 5 { inertia?.invalidate(); inertia = nil }
        }

        /// Two fingers: move the camera and its target together, along the screen.
        @objc func orbitMoved(_ g: UIPanGestureRecognizer) {
            guard let view = g.view as? SCNView, let pov = view.pointOfView, standing == nil else { return }
            inertia?.invalidate(); inertia = nil
            let d = g.translation(in: view)
            g.setTranslation(.zero, in: view)
            let dist = pov === topCamera ? max(1, pov.simdWorldPosition.y - orbitTarget.y) : simd_length(pov.simdWorldPosition - orbitTarget)
            // 1:1 with the fingers: what's under them at the target's distance stays under them.
            let fov = Float(pov.camera?.fieldOfView ?? 60) * .pi / 180
            let s = 2 * dist * tan(fov / 2) / Float(max(1, view.bounds.height))
            let delta = (-Float(d.x) * pov.simdWorldRight + Float(d.y) * pov.simdWorldUp) * s
            pov.simdWorldPosition += delta
            orbitTarget += delta
        }

        @objc func orbitPinched(_ g: UIPinchGestureRecognizer) {
            guard let view = g.view as? SCNView, let pov = view.pointOfView, standing == nil, g.scale > 0 else { return }
            _ = view
            if pov === topCamera {
                pov.simdWorldPosition.y = orbitTarget.y + (pov.simdWorldPosition.y - orbitTarget.y) / Float(g.scale)
            } else {
                let off = pov.simdWorldPosition - orbitTarget
                let len = simd_length(off)
                let newLen = max(0.3, min(60, len / Float(g.scale)))
                pov.simdWorldPosition = orbitTarget + off / len * newLen
            }
            g.scale = 1
        }

        func gestureRecognizer(_ a: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith b: UIGestureRecognizer) -> Bool {
            // Two-finger pan and pinch together; the one-finger turn never mixes with them.
            let pair: Set<ObjectIdentifier> = Set(orbitGestures.dropFirst().map(ObjectIdentifier.init))
            return pair.contains(ObjectIdentifier(a)) && pair.contains(ObjectIdentifier(b))
        }

        var orbitCamera = SCNNode()
        var topCamera = SCNNode()
        var lastTop: Bool?
        var floorY: Float?
        var onFloorTap: (SIMD3<Float>) -> Void = { _ in }

        // First person ("Enter the room").
        weak var view: SCNView?
        let walkCamera = SCNNode()
        var savedPointOfView: SCNNode?
        var standing: SIMD3<Float>?
        var walkFloorY: Float?
        var walkMove = 0
        var yaw: Float = 0, pitch: Float = 0
        var walkGestures: [UIGestureRecognizer] = []
        var link: CADisplayLink?
        var lastStep: CFTimeInterval = 0
        var eyeHeight: Float = 1.5

        /// A new eye height while standing: rise or sink there.
        func setEyeHeight(_ h: Float) {
            guard h != eyeHeight else { return }
            eyeHeight = h
            guard let standing else { return }
            SCNTransaction.begin()
            SCNTransaction.animationDuration = 0.3
            walkCamera.simdPosition.y = standing.y + h
            SCNTransaction.commit()
        }

        func applyLook() {
            walkCamera.simdOrientation = simd_quatf(angle: yaw, axis: SIMD3(0, 1, 0)) * simd_quatf(angle: pitch, axis: SIMD3(1, 0, 0))
        }

        /// Steps into the room at `p` (or walks there when already inside), or back out to the previous view.
        func setStanding(_ p: SIMD3<Float>?) {
            guard let view, p != standing else { return }
            let wasInside = standing != nil
            standing = p
            if let p {
                let eye = SIMD3(p.x, p.y + eyeHeight, p.z)
                marker(at: p)
                if !wasInside {
                    // Start from the current view, facing the same way, level-ish.
                    let from = view.pointOfView ?? orbitCamera
                    savedPointOfView = from
                    let f = -SIMD3(from.simdWorldTransform.columns.2.x, from.simdWorldTransform.columns.2.y, from.simdWorldTransform.columns.2.z)
                    yaw = atan2(-f.x, -f.z)
                    pitch = -0.1
                    if walkCamera.camera == nil {
                        let cam = RoomSceneView.makeCamera()
                        cam.zNear = 0.05
                        walkCamera.camera = cam
                        from.parent?.addChildNode(walkCamera)
                        if walkCamera.parent == nil { view.scene?.rootNode.addChildNode(walkCamera) }
                    }
                    walkCamera.camera?.fieldOfView = from.camera?.fieldOfView ?? 60
                    SCNTransaction.begin()
                    SCNTransaction.disableActions = true
                    walkCamera.simdWorldTransform = from.simdWorldTransform
                    view.pointOfView = walkCamera
                    SCNTransaction.commit()
                    orbitGestures.forEach { $0.isEnabled = false }
                    walkGestures.forEach { $0.isEnabled = true }
                    let link = CADisplayLink(target: self, selector: #selector(step))
                    link.add(to: .main, forMode: .common)
                    self.link = link
                    lastStep = CACurrentMediaTime()
                }
                SCNTransaction.begin()
                SCNTransaction.animationDuration = wasInside ? Double(min(2, simd_distance(walkCamera.simdPosition, eye) / 1.2)) : 0.9
                SCNTransaction.animationTimingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
                walkCamera.simdPosition = eye
                applyLook()
                walkCamera.camera?.fieldOfView = 65
                SCNTransaction.commit()
            } else {
                link?.invalidate()
                link = nil
                walkGestures.forEach { $0.isEnabled = false }
                orbitGestures.forEach { $0.isEnabled = true }
                SCNTransaction.begin()
                SCNTransaction.animationDuration = 0.7
                view.pointOfView = savedPointOfView ?? orbitCamera
                SCNTransaction.commit()
            }
        }

        /// A small ring where the user will stand, fading out.
        func marker(at p: SIMD3<Float>) {
            let ring = SCNNode(geometry: SCNTorus(ringRadius: 0.25, pipeRadius: 0.02))
            ring.geometry?.firstMaterial?.diffuse.contents = UIColor.systemOrange
            ring.geometry?.firstMaterial?.lightingModel = .constant
            ring.simdPosition = p + SIMD3(0, 0.02, 0)
            view?.scene?.rootNode.addChildNode(ring)
            ring.runAction(.sequence([.wait(duration: 0.6), .fadeOut(duration: 0.6), .removeFromParentNode()]))
        }

        /// Held ▲/▼: about 1.2 m/s, level, in the look direction.
        @objc func step() {
            let now = CACurrentMediaTime()
            let dt = Float(min(0.1, now - lastStep))
            lastStep = now
            guard walkMove != 0, standing != nil else { return }
            let forward = SIMD3<Float>(-sin(yaw), 0, -cos(yaw))
            walkCamera.simdPosition += forward * Float(walkMove) * 1.2 * dt
        }

        @objc func looked(_ g: UIPanGestureRecognizer) {
            guard let view = g.view else { return }
            let d = g.translation(in: view)
            g.setTranslation(.zero, in: view)
            // Drag the scene: turn the opposite way to the finger, like a photo sphere.
            let scale = Float((walkCamera.camera?.fieldOfView ?? 60) / 60) * 0.005
            yaw += Float(d.x) * scale
            pitch = max(-1.3, min(1.3, pitch + Float(d.y) * scale))
            applyLook()
        }

        @objc func pinched(_ g: UIPinchGestureRecognizer) {
            guard let cam = walkCamera.camera else { return }
            cam.fieldOfView = max(30, min(90, cam.fieldOfView / g.scale))
            g.scale = 1
        }

        @objc func doubleTapped(_ g: UITapGestureRecognizer) {
            guard standing != nil, let floorY = walkFloorY, let view = g.view as? SCNView,
                  let p = floorPoint(g.location(in: view), floorY: floorY, in: view) else { return }
            setStanding(p)
        }

        func floorPoint(_ p: CGPoint, floorY: Float, in view: SCNView) -> SIMD3<Float>? {
            let near = view.unprojectPoint(SCNVector3(Float(p.x), Float(p.y), 0))
            let far = view.unprojectPoint(SCNVector3(Float(p.x), Float(p.y), 1))
            let a = SIMD3<Float>(near.x, near.y, near.z), b = SIMD3<Float>(far.x, far.y, far.z)
            let dy = b.y - a.y
            guard abs(dy) > 1e-5 else { return nil }
            let t = (floorY - a.y) / dy
            guard t > 0, t < 1 else { return nil }
            return a + (b - a) * t
        }

        /// Intersects the tapped ray with the floor plane. (SceneKit's hit test can't see the splats: their discs
        /// are opened up in the shader, so on the CPU they have no area.)
        @objc func tapped(_ gesture: UITapGestureRecognizer) {
            guard standing == nil, let floorY, let view = gesture.view as? SCNView,
                  let p = floorPoint(gesture.location(in: view), floorY: floorY, in: view) else { return }
            onFloorTap(p)
        }
    }

    func makeUIView(context: Context) -> SCNView {
        let view = SCNView()
        view.scene = scene
        view.allowsCameraControl = false // our own orbit controls (see Coordinator.orbitBy)
        view.delegate = context.coordinator // sizes the outlet labels on screen each frame
        view.isPlaying = true
        view.autoenablesDefaultLighting = true
        view.backgroundColor = .black
        view.antialiasingMode = .multisampling4X

        let framing = Self.framing(of: scene)
        let center = framing.center

        let orbit = context.coordinator.orbitCamera
        orbit.camera = Self.makeCamera()
        orbit.position = Self.orbitPosition(framing)
        orbit.look(at: center)
        scene.rootNode.addChildNode(orbit)

        let top = context.coordinator.topCamera
        top.camera = Self.makeCamera()
        top.position = SCNVector3(center.x, center.y + framing.extent * 1.4, center.z)
        top.simdOrientation = simd_quatf(angle: topTurn, axis: SIMD3(0, 1, 0))
            * simd_quatf(angle: -.pi / 2, axis: SIMD3(1, 0, 0))
        scene.rootNode.addChildNode(top)

        Self.addAmbientLight(to: scene)

        context.coordinator.orbitTarget = SIMD3(center.x, center.y, center.z)
        view.pointOfView = orbit
        context.coordinator.lastTop = false
        view.addGestureRecognizer(UITapGestureRecognizer(target: context.coordinator,
                                                         action: #selector(Coordinator.tapped(_:))))
        // First-person gestures, only enabled while standing in the room.
        let c = context.coordinator
        c.view = view
        let doubleTap = UITapGestureRecognizer(target: c, action: #selector(Coordinator.doubleTapped(_:)))
        doubleTap.numberOfTapsRequired = 2
        c.walkGestures = [UIPanGestureRecognizer(target: c, action: #selector(Coordinator.looked(_:))),
                          UIPinchGestureRecognizer(target: c, action: #selector(Coordinator.pinched(_:))), doubleTap]
        for g in c.walkGestures {
            g.isEnabled = false
            view.addGestureRecognizer(g)
        }
        let turn = UIPanGestureRecognizer(target: c, action: #selector(Coordinator.orbitPanned(_:)))
        turn.maximumNumberOfTouches = 1
        let move = UIPanGestureRecognizer(target: c, action: #selector(Coordinator.orbitMoved(_:)))
        move.minimumNumberOfTouches = 2
        move.maximumNumberOfTouches = 2
        let zoom = UIPinchGestureRecognizer(target: c, action: #selector(Coordinator.orbitPinched(_:)))
        c.orbitGestures = [turn, move, zoom]
        for g in c.orbitGestures {
            g.delegate = c
            view.addGestureRecognizer(g)
        }
        return view
    }

    /// The model's centre and its largest dimension.
    nonisolated static func framing(of scene: SCNScene) -> (center: SCNVector3, extent: Float) {
        let (minB, maxB) = scene.rootNode.boundingBox
        let center = SCNVector3((minB.x + maxB.x) / 2, (minB.y + maxB.y) / 2, (minB.z + maxB.z) / 2)
        let size = max(maxB.x - minB.x, max(maxB.y - minB.y, maxB.z - minB.z))
        return (center, Float(size.isFinite && size > 0 ? size : 5))
    }

    /// Where the 3D view's camera starts: above and to one side, looking at the centre.
    nonisolated static func orbitPosition(_ framing: (center: SCNVector3, extent: Float)) -> SCNVector3 {
        let (c, e) = framing
        return SCNVector3(c.x + e * 0.9, c.y + e * 0.8, c.z + e * 0.9)
    }

    nonisolated static func makeCamera() -> SCNCamera {
        let cam = SCNCamera()
        cam.zNear = 0.05
        cam.zFar = 500
        cam.fieldOfView = 60
        return cam
    }

    /// Soft ambient light so the model reads well from every side.
    nonisolated static func addAmbientLight(to scene: SCNScene) {
        let ambient = SCNNode()
        ambient.light = SCNLight()
        ambient.light?.type = .ambient
        ambient.light?.intensity = 500
        scene.rootNode.addChildNode(ambient)
    }

    func updateUIView(_ view: SCNView, context: Context) {
        context.coordinator.floorY = floorY
        context.coordinator.onFloorTap = onFloorTap
        context.coordinator.walkFloorY = walkFloorY
        context.coordinator.viewHeight = view.bounds.height
        context.coordinator.walkMove = walkMove
        context.coordinator.setEyeHeight(eyeHeight)
        context.coordinator.setStanding(standAt)
        guard standAt == nil, context.coordinator.lastTop != topView else { return }
        context.coordinator.lastTop = topView
        SCNTransaction.begin()
        SCNTransaction.animationDuration = 0.4
        view.pointOfView = topView ? context.coordinator.topCamera : context.coordinator.orbitCamera
        SCNTransaction.commit()
    }
}

/// Plain-language summary of what was found: size, light sources (windows), doors and furniture.
struct RoomSummary: View {
    let room: CapturedRoom
    /// The room's name, if it has one, and how to change it (nil: no Name row).
    var name: String?
    var onRename: (() -> Void)?
    var capture: CaptureMeta?
    var foundWindows: [WindowFinder.Found] = []
    /// The Objects panel's list, with the user's edits. Empty for scans without points: then RoomPlan's own objects.
    var objects: [RoomObject] = []
    var improved: [Date] = []
    var onImprove: (() -> Void)?
    /// Power outlets found in the room (synced from the Android app's search).
    var outletCount = 0

    private var missedWindows: [WindowFinder.Found] { foundWindows.filter { !$0.matchesRoomPlan } }

    var body: some View {
        List {
            // First under the 3D view: add to this scan.
            if capture != nil, let onImprove {
                Section {
                    Button(action: onImprove) {
                        Label("Continue scan", systemImage: "plus.viewfinder")
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 6)
                    }
                    .buttonStyle(.borderedProminent)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                } footer: {
                    Text("Add parts you missed, or that came out thin or blurry. The phone recognises the room and adds to this scan.")
                }
            }
            Section("Room") {
                if let onRename {
                    Button(action: onRename) {
                        LabeledContent("Name") {
                            HStack(spacing: 6) {
                                if let name {
                                    Text(name).foregroundStyle(.secondary)
                                } else {
                                    Text("Add a name").foregroundStyle(Color.accentColor)
                                }
                                Image(systemName: "pencil").font(.footnote).foregroundStyle(Color.accentColor)
                            }
                        }
                    }
                    .tint(.primary)
                }
                LabeledContent("Approx. size", value: sizeText)
                LabeledContent("Walls", value: "\(room.walls.count)")
                LabeledContent("Windows (natural light)", value: "\(room.windows.count + missedWindows.count)")
                LabeledContent("Doors", value: "\(room.doors.count)")
                if outletCount > 0 {
                    LabeledContent("Power outlets", value: "\(outletCount)")
                }
                if !room.openings.isEmpty {
                    LabeledContent("Openings", value: "\(room.openings.count)")
                }
            }
            if !missedWindows.isEmpty {
                Section {
                    ForEach(missedWindows.indices, id: \.self) { i in
                        Label(missedWindows[i].label, systemImage: "sun.max")
                    }
                } header: {
                    Text("Windows found from the photos")
                } footer: {
                    Text("RoomPlan missed these. They are bright in the photos and shown in light blue on the walls.")
                }
            }
            if let capture {
                Section {
                    LabeledContent("Points", value: capture.pointCount.formatted())
                    LabeledContent("Photos", value: capture.keyframeCount.formatted())
                    if let last = improved.last {
                        let when = last.formatted(.dateTime.day().month(.abbreviated).hour().minute())
                        LabeledContent("Improved", value: improved.count == 1 ? when : "\(improved.count) times, last \(when)")
                    }
                } header: {
                    Text("Capture")
                }
            }
            if !objectCounts.isEmpty || removedCount > 0 {
                Section {
                    ForEach(objectCounts, id: \.0) { item in
                        LabeledContent(item.0, value: "\(item.1)")
                    }
                } header: {
                    Text("Furniture & objects")
                } footer: {
                    if removedCount > 0 {
                        Text("\(removedCount) removed in Objects, not counted.")
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    private var removedCount: Int { objects.filter(\.removed).count }

    /// Counts by type: from the Objects list (removed ones left out) when there is one, else from RoomPlan.
    private var objectCounts: [(String, Int)] {
        var counts: [String: Int] = [:]
        if objects.isEmpty {
            for object in room.objects {
                counts[String(describing: object.category).capitalized, default: 0] += 1
            }
        } else {
            for object in objects where !object.removed {
                counts[object.kind.label, default: 0] += 1
            }
        }
        return counts.sorted { $0.key < $1.key }.map { ($0.key, $0.value) }
    }

    /// Length and width measured along the room's longest wall, so a room scanned at an angle isn't overstated.
    private var sizeText: String {
        guard let longest = room.walls.max(by: { $0.dimensions.x < $1.dimensions.x }) else { return "–" }
        let along = simd_normalize(simd_float3(longest.transform.columns.0.x, 0, longest.transform.columns.0.z))
        let across = simd_float3(-along.z, 0, along.x)
        var minA = Float.greatestFiniteMagnitude, maxA = -Float.greatestFiniteMagnitude
        var minB = Float.greatestFiniteMagnitude, maxB = -Float.greatestFiniteMagnitude
        var height: Float = 0
        for wall in room.walls {
            let t = wall.transform
            let c = simd_float3(t.columns.3.x, t.columns.3.y, t.columns.3.z)
            let r = simd_float3(t.columns.0.x, t.columns.0.y, t.columns.0.z)
            let half = wall.dimensions.x / 2
            for p in [c + r * half, c - r * half] {
                minA = min(minA, simd_dot(p, along)); maxA = max(maxA, simd_dot(p, along))
                minB = min(minB, simd_dot(p, across)); maxB = max(maxB, simd_dot(p, across))
            }
            height = max(height, wall.dimensions.y)
        }
        let (long, short) = (max(maxA - minA, maxB - minB), min(maxA - minA, maxB - minB))
        return String(format: "%.1f × %.1f m, %.1f m high", long, short, height)
    }
}

/// Starts splat training, or pauses it (task 6).
private struct TrainButton: View {
    @ObservedObject var run: SplatTrainingRun
    var labeled = false
    var onStart: () -> Void

    var body: some View {
        Button {
            if !run.isTraining { onStart() }
            run.toggle()
        } label: {
            ModeLabel(title: run.isTraining ? "Pause" : "Sharpen",
                      icon: run.isTraining ? "pause.fill" : "wand.and.stars", labeled: labeled)
        }
        .buttonStyle(.bordered)
        .tint(run.isTraining ? .yellow : nil)
        .accessibilityLabel(run.isTraining ? "Pause training" : "Train the splats against the photos")
    }
}

/// A mode button's face: icon and word, or the icon alone when space is short.
private struct ModeLabel: View {
    let title: String
    let icon: String
    let labeled: Bool

    var body: some View {
        if labeled {
            Label(title, systemImage: icon)
                .font(.subheadline.weight(.medium))
                .labelStyle(.titleAndIcon)
        } else {
            Image(systemName: icon)
        }
    }
}

/// What the training is doing, over the bottom of the 3D view.
private struct TrainingStatus: View {
    @ObservedObject var run: SplatTrainingRun
    /// Set a moment after training stopped, so the note doesn't stay over the 3D view.
    @State private var hidden = false

    /// How long the note stays once training has stopped (paused, done, or failed).
    static let paused: Duration = .seconds(3)
    static let failed: Duration = .seconds(6)

    var body: some View {
        Group {
            if hidden {
                EmptyView()
            } else {
                switch run.state {
                case .idle:
                    EmptyView()
                case .preparing:
                    note(Label("Getting the photos ready…", systemImage: "photo.stack"))
                case .training:
                    note(Label(run.progress.isEmpty ? "Training the splats…" : run.progress, systemImage: "wand.and.stars"))
                case .paused:
                    note(Label(run.progress + ". Saved.", systemImage: "pause.circle"))
                case .done:
                    note(Label(run.progress + (run.progress.hasPrefix("Training can't") ? "." : ". Saved."),
                               systemImage: "checkmark.circle"))
                case .failed(let message):
                    note(Label(message, systemImage: "exclamationmark.triangle"))
                }
            }
        }
        .animation(.easeOut(duration: 0.3), value: hidden)
        // Every new state shows again; a finished one goes away by itself after a few seconds.
        .task(id: run.state) {
            hidden = false
            let wait: Duration?
            switch run.state {
            case .paused, .done: wait = Self.paused
            case .failed: wait = Self.failed
            default: wait = nil
            }
            guard let wait else { return }
            try? await Task.sleep(for: wait)
            if !Task.isCancelled { hidden = true }
        }
    }

    private func note(_ label: some View) -> some View {
        label
            .font(.caption.weight(.medium))
            .padding(.horizontal, 10).padding(.vertical, 6)
            .background(.regularMaterial, in: Capsule())
            .transition(.opacity)
    }
}

/// "Improved 7 Nov at 17:05" under the title (iOS 26 and later; the Capture section says it on every version).
private struct ImprovedSubtitle: ViewModifier {
    let date: Date?

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *), let date {
            content.navigationSubtitle("Improved \(date.formatted(.dateTime.day().month(.abbreviated).hour().minute()))")
        } else {
            content
        }
    }
}


/// A file to hand to the share sheet.
struct SharedFile: Identifiable {
    let url: URL
    var id: URL { url }
}

/// UIKit's share sheet (AirDrop, Save to Files, …) for files SwiftUI's ShareLink can't wait for.
struct ActivitySheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}


/// "Enter the room" in the result view.
enum WalkState: Equatable {
    case off, choosing, inside(SIMD3<Float>)

    var standingAt: SIMD3<Float>? {
        if case .inside(let p) = self { return p }
        return nil
    }
}

/// A round button that reports while it's held down (walk ▲/▼).
struct HoldButton: View {
    let systemImage: String
    let onHold: (Bool) -> Void
    @State private var held = false

    var body: some View {
        Image(systemName: systemImage)
            .font(.title2)
            .frame(width: 56, height: 56)
            .background(held ? AnyShapeStyle(Color.accentColor.opacity(0.6)) : AnyShapeStyle(.regularMaterial), in: Circle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { _ in if !held { held = true; onHold(true) } }
                .onEnded { _ in held = false; onHold(false) })
            .accessibilityAddTraits(.isButton)
    }
}
