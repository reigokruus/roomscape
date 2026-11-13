import SwiftUI
import SceneKit
import RoomPlan
import simd
import CoreMotion

/// Shows a saved scan: as splats (the captured points as discs on their surfaces, inside RoomPlan's walls and floor)
/// when the scan has points, or as RoomPlan's own 3D model. The splats show as a white mesh with a grid (Mesh) or in
/// the scanned colours (Photo). Below it, a summary of what RoomPlan found.
struct ResultView: View {
    /// The same set as the Android app, in the same order: Photo · Splats · Points · Mesh (and Model, RoomPlan's own).
    enum Style: Hashable { case photo, splats, points, mesh, model, photoReal }

    let scan: SavedScan
    /// Starts a second pass over this scan (Improve scan); only offered when the scan saved its world map.
    var onImprove: (() -> Void)?
    /// Opened again in place after a rebuild (RoomHost): keep the look that was selected.
    var reloaded = false
    /// The look last selected on each room's screen (this app run), for `reloaded`.
    @MainActor static var lastStyle: [String: Style] = [:]
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
    @State private var tilesNode: SCNNode?
    @State private var splatGroup: SCNNode?
    /// The scan's photos painted onto its fused mesh (`PhotoRoom`): what Photo shows when the scan has both; else the
    /// splats in their scanned colours.
    @State private var photoRoom: SCNNode?
    /// Mesh: the fused surface, untextured, light grey with a faint wireframe.
    @State private var meshNode: SCNNode?
    /// Power outlets in the room (from .rmsc: the Android app's Claude search), shown as labels in the 3D view.
    @State private var outlets: [RoomOutlet] = []
    /// The .rmsc being saved with Save to Files.
    @State private var savingFile: SharedFile?
    /// Objects mode's undo: the objects before each edit (move, turn, remove, put back), newest last.
    @State private var objectHistory: [[RoomObject]] = []
    /// Size of the scan's raw recording (RawRecorder), shown in the room panel; nil without one.
    @State private var recordingBytes: Int64?
    /// Scan area again: marking the areas to scan again (taps on the room), and the marked spots.
    @State private var pickingAreas = false
    @State private var areaTargets: [SIMD3<Float>] = []
    private let areaRoot: SCNNode = { let n = SCNNode(); n.name = "rescanTargets"; return n }()
    @State private var rebuildDone: String?
    /// Fills for the holes removed or moved objects leave (HoleFill), by object; generated, never saved.
    @State private var holeFills: [UUID: SCNNode] = [:]
    @State private var holeFillsPending: Set<UUID> = []
    private let holeFillRoot: SCNNode = { let n = SCNNode(); n.name = "holeFills"; return n }()
    /// Outlet labels on or off (the ⚡ Outlets button), remembered across scans; on by default.
    @AppStorage("showOutlets") private var showOutlets = true
    /// "Enter the room": picking where to stand, then standing there (first person). See `RoomSceneView`.
    @State private var walk: WalkState = .off
    /// Held ▲ (+1) or ▼ (-1) while standing in the room.
    @State private var walkMove = 0
    @State private var walkStrafe = 0
    /// Measure (rk 10 Nov): tap two spots on the scan while standing in the room.
    @State private var measuring = false
    @State private var measureClear = 0
    @State private var confirmClearMeasures = false
    /// Eye height above the floor when standing in the room, metres; remembered.
    @AppStorage("walkEyeHeight") private var eyeHeight = 1.5
    /// The photo room as a .glb (`PhotoRoomExport`), being written or ready to share (AirDrop, Save to Files).
    @State private var exportingGLB = false
    @State private var glbFile: SharedFile?
    @State private var glbError: String?
    @State private var linkMade: URL?
    @State private var sendingNearby = false
    @State private var showingSplat = false
    /// The Photo-real tab (rk 13 Nov 17:24, as on Android): the splat over the room view, from the room view's camera.
    @State private var cameraLink = RoomCameraLink()
    @State private var splatLink = SplatViewLink()
    @State private var splatStart: RoomCameraLink.Pose?
    @State private var buildingSplats = false
    /// Lighting preview (handoff section 3): shown on the splats, with its panel in place of the summary.
    @State private var lightingOn = false
    /// Gyro look while standing in the room (on by default, remembered).
    @AppStorage("walkGyro") private var walkGyro = true
    @State private var lighting: LightingSetup?
    @State private var lightingScene: LightingScene?
    @State private var shell: RoomShell?
    /// The floor's rooms (area, size, tap a room, drag its walls), as on the Android app; see `FloorRooms`.
    @State private var floorRooms: FloorRooms?
    @State private var selectedRoom: Int?
    @State private var dragSide: Int?
    private let floorRoomsNode = FloorRoomsNode()
    /// The rooms' wall lines on or off (the Walls button), remembered across scans; on by default. Off, the lines are
    /// hidden and a finger only turns the view (no wall grabbed by accident); the numbers stay in the title and card.
    @AppStorage("showRoomLines") private var showRoomLines = true
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
    @ObservedObject private var processing = ProcessingQueue.shared
    /// "Make photo-real": asked once before it starts (it takes minutes, warms the phone, needs the app open)
    @State private var confirmingSplat = false
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
                                  floorY: (lightingOn || objectsOn || walk == .choosing || measuringRooms) ? walkFloorY : nil,
                                  onFloorTap: floorTapped,
                                  onTapRay: pickingAreas ? areaTapped : objectsOn ? objectTapped : nil,
                                  onTwist: objectsOn && selectedMovable ? objectTwisted : nil,
                                  onObjectDrag: objectsOn && selectedMovable ? objectDragged : nil,
                                  onFloorDrag: measuringRooms ? wallDragged : nil,
                                  standAt: walk.standingAt, walkFloorY: walkFloorY, walkMove: walkMove, walkStrafe: walkStrafe, gyroLook: walkGyro,
                                  eyeHeight: Float(min(eyeHeight, maxEyeHeight)),
                                  measuring: measuring && walk.standingAt != nil, measureClear: measureClear,
                                  measureFile: scan.captureURL.appendingPathComponent("measurements.json"),
                                  cameraLink: cameraLink)
                    // The room is drawn below the controls, never under them.
                    .padding(.top, walk == .off ? controlsHeight : 0)
                    .clipped()
                } else if let loadError {
                    Text(loadError).padding()
                } else if processing.gpuJob {
                    RoomPausedForPreview()
                } else {
                    ProgressView()
                }
                if style == .photoReal, scene != nil, SplatPreview.hasResult(current) {
                    SplatViewerView(splat: SplatPreview.result(current), view: splatStart.map { ($0.cam, $0.look) },
                                    fov: splatStart?.fov, link: splatLink)
                        .id(current.name)
                        .padding(.top, controlsHeight)
                }
                if lightingOn, let scene, let lighting { shotInset(scene, lighting) }
                // Must fit a 375-pt-wide phone: compact controls in one row, or two rows if they still don't fit
                // (large text). A row wider than the screen would also push the panel below off both edges.
                // The mode buttons carry a word as well as an icon whenever there's room for it.
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) {
                        modeButtons(labeled: true)
                        Spacer(minLength: 4)
                        viewPickers
                    }
                    // Too wide for one row: the buttons keep their words and wrap, the switches go under them.
                    VStack(alignment: .leading, spacing: 8) {
                        FlowRow(spacing: 8) { modeButtons(labeled: true) }
                        // never wider than the screen: the switches scroll sideways on their own if they must
                        ScrollView(.horizontal, showsIndicators: false) { HStack(spacing: 8) { viewPickers } }
                    }
                }
                .padding(10)
                // One row or two: the large camera view starts below them.
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { controlsHeight = $0 }
            }
            .overlay(alignment: .top) {
                // The picked room's line, and Reset walls once a wall was dragged (Android: next to the size line).
                if measuringRooms, let rooms = floorRooms, selectedRoom != nil || dragSide != nil || rooms.anyEdited {
                    roomBar(rooms).padding(.top, controlsHeight + 6).padding(.horizontal, 12)
                }
            }
            .overlay(alignment: .bottomLeading) {
                if pickingAreas { areaBar.padding(12) }
                else if scene != nil, walkFloorY != nil { walkControls.padding(12) }
            }
            .onChange(of: showOutlets) { _, on in
                scene?.rootNode.childNode(withName: "outlets", recursively: false)?.isHidden = !on
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
            .background(ZebraBackground())
            // The 3D area is always black, so its controls use dark styling even when the phone is in light mode.
            .environment(\.colorScheme, .dark)

            if objectsOn, shell != nil {
                // The same bar as the Android app's: what's selected, and Remove / Turn 15° / Removed… under it.
                ObjectsBar(objects: $objects, selected: $selectedObject, history: $objectHistory, claudeChecked: claudeChecked,
                           finding: finding, error: findError, onFind: findObjects, onDone: toggleObjects)
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
                            floorRooms: floorRooms,
                            onImprove: WorldMapStore.exists(scan) ? onImprove : nil, outletCount: outlets.count,
                            recordingBytes: recordingBytes,
                            onScanArea: WorldMapStore.exists(scan) && onImprove != nil ? { startPickingAreas() } : nil)
                    .frame(maxHeight: .infinity)
            } else if walk == .off, let capture {
                // A room from the Android app (no RoomPlan model): the same panel from its own data.
                ImportedRoomSummary(name: current.title, onRename: {
                                        newTitle = current.title ?? ""
                                        renaming = true
                                    }, capture: capture, manifest: SourceManifest.load(scan), surfels: surfels,
                                    objects: objects, outletCount: outlets.count)
                    .frame(maxHeight: .infinity)
            }
        }
        // A title menu here would push Plan a Shoot and Share into an overflow menu; rooms are named on Home.
        .navigationTitle(current.title ?? scan.date.formatted(.dateTime.day().month(.abbreviated).hour().minute()))
        .modifier(RoomSubtitle(text: subtitle))
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
                    // The photo-real preview (SplatPreview): open it, or make it (queued after the scan's own jobs)
                    if SplatPreview.hasResult(current) {
                        Button { if splatGroup != nil { style = .photoReal } else { showingSplat = true } } label: { Label("Photo-real preview", systemImage: "cube.transparent") }
                    } else if recordingBytes != nil, !processing.splatQueued(current) {
                        Button { confirmingSplat = true } label: { Label("Make photo-real preview", systemImage: "cube.transparent") }
                    }
                    roomLinkItems
                    PhotoRealTestMenuItems(scan: current)
                    Button { sendingNearby = true } label: { Label("Send to nearby phone", systemImage: "iphone.radiowaves.left.and.right") }
                    Button { exportRmsc(save: true) } label: { Label("Save to Files…", systemImage: "folder") }
                    Button { exportRmsc(save: false) } label: { Label("RoomScape scan (.rmsc)", systemImage: "shippingbox") }
                    if recordingBytes != nil {
                        // rk 12 Nov: send a scan to the PC that makes the photo-real room (Google Drive, Files, AirDrop)
                        Menu {
                            Button { exportForPC(.quick) } label: { Label("Quick (about 30-40 MB)", systemImage: "hare") }
                            Button { exportForPC(.full) } label: { Label("Full quality (the whole recording)", systemImage: "film") }
                        } label: { Label("For a PC rebuild (.zip)", systemImage: "desktopcomputer.and.arrow.down") }
                    }
                    if photoRoom != nil {
                        Button(action: exportGLB) { Label("Photo room for a computer (.glb)", systemImage: "desktopcomputer") }
                    }
                    ShareLink(item: shareURL ?? scan.modelURL) { Label("Room model (.usdz)", systemImage: "cube") }
                    if recordingBytes != nil {
                        // Test: build the scan again from its raw recording (record now, process later).
                        Button { rebuildFromRecording() } label: { Label("Rebuild from recording (test)", systemImage: "arrow.triangle.2.circlepath") }
                    }
                } label: {
                    Label("Share", systemImage: "square.and.arrow.up")
                }
            } else {
                ShareLink(item: shareURL ?? scan.modelURL)
            }
        }
        .sheet(isPresented: $sendingNearby) { SendNearbySheet(scan: current) }
        .fullScreenCover(isPresented: $showingSplat) { SplatPreviewSheet(scan: current) }
        .overlay(alignment: .center) {
            ProcessingOverlay(scan: current.name, done: $rebuildDone)
        }
        .alert((rebuildDone ?? "").contains("photo-real") ? "Photo-real preview" : "Rebuilt from the recording", isPresented: Binding(get: { rebuildDone != nil }, set: { if !$0 { rebuildDone = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(rebuildDone ?? "") }
        .sheet(item: $glbFile) { file in
            ActivitySheet(items: [file.url]).ignoresSafeArea()
        }
        .sheet(item: $savingFile) { file in
            SaveToFilesSheet(url: file.url).ignoresSafeArea()
        }
        .alert("Couldn't export the scan", isPresented: Binding(get: { glbError != nil }, set: { if !$0 { glbError = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(glbError ?? "")
        }
        .modifier(LinkMadeAlert(link: $linkMade))
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
            updateHoles()
            modelObjects?.apply(objects)
            if lighting != nil { refreshRoomLight() }
            RoomObjects.save(ObjectsFile(claudeChecked: claudeChecked, objects: objects), for: scan)
        }
        .onChange(of: measuringRooms) { _, _ in showFloorRooms() }
        .onChange(of: showRoomLines) { _, on in
            // Lines off: let go of the picked room, so the title goes back to the whole floor.
            if !on { selectedRoom = nil; dragSide = nil; showFloorRooms() }
        }
        .onChange(of: selectedObject) { _, id in
            objectsScene?.apply(objects, selected: id)
        }
        .onChange(of: style) { oldStyle, newStyle in
            if newStyle == .photoReal {
                // The splat covers the room: lighting and objects close, the room keeps its look underneath
                if lightingOn { toggleLighting() }
                if objectsOn { toggleObjects() }
                splatStart = cameraLink.pose()
                return
            }
            if oldStyle == .photoReal {   // back at the splat view's camera
                let link = cameraLink
                splatLink.camera { pose in if let pose { link.set(cam: pose.cam, look: pose.look) } }
            }
            // The preview lights the splats and objects move splats; RoomPlan's model has neither.
            if newStyle == .model, lightingOn { toggleLighting() }
            if newStyle == .model, objectsOn { toggleObjects() }
            showStyle(newStyle)
            Self.lastStyle[scan.name] = newStyle
        }
        .task { await openRoom() }
        // The photo-real preview needs the memory the 3D room holds (12 Nov 22:08): let it go while that runs, load it
        // again when it's done
        .modifier(ReleaseDuringPreview(busy: processing.gpuJob, release: releaseRoom, reload: { await loadRoom() }))
        .modifier(ConfirmPhotoReal(isPresented: $confirmingSplat, scan: current))
    }

    /// Loads the room when the screen opens (or opens again in place after a rebuild, `reloaded`).
    private func openRoom() async {
        ProcessingQueue.logMemory(reloaded ? "room screen reloaded after a rebuild" : "room screen opened")
        await loadRoom()
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
        if !outlets.isEmpty { outletsButton(labeled: labeled) }
        if floorRooms != nil, !lightingOn, !objectsOn { roomLinesButton(labeled: labeled) }
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
            // Splat training is gone with the Splats view (rk 9 Nov).
        } else if buildingSplats {
            Label("Building splats…", systemImage: "circle.dotted")
                .font(.caption.weight(.medium))
                .padding(.horizontal, 10).padding(.vertical, 6)
                .background(.regularMaterial, in: Capsule())
        }
    }

    /// ⚡ Outlets n: shows or hides the outlet labels. Filled orange while they show.
    @ViewBuilder private func outletsButton(labeled: Bool) -> some View {
        let face = ModeLabel(title: "Outlets \(outlets.count)", icon: "bolt.fill", labeled: labeled)
        if showOutlets {
            Button { showOutlets = false } label: { face.foregroundStyle(.black) }
                .buttonStyle(.borderedProminent)
                .accessibilityLabel("Hide the \(outlets.count) power outlets")
        } else {
            Button { showOutlets = true } label: { face }
                .buttonStyle(.bordered)
                .accessibilityLabel("Show the \(outlets.count) power outlets")
        }
    }

    /// Walls: shows or hides the rooms' amber wall lines (and with them tapping a room and dragging a wall). Filled
    /// amber while they show.
    @ViewBuilder private func roomLinesButton(labeled: Bool) -> some View {
        let face = ModeLabel(title: "Walls", icon: "square.dashed", labeled: labeled)
        if showRoomLines {
            Button { showRoomLines = false } label: { face.foregroundStyle(.black) }
                .buttonStyle(.borderedProminent)
                .tint(.orange)
                .accessibilityLabel("Hide the wall lines")
        } else {
            Button { showRoomLines = true } label: { face }
                .buttonStyle(.bordered)
                .accessibilityLabel("Show the wall lines")
        }
    }

    /// Splats / Model (once splats exist) and 3D / top view.
    @ViewBuilder private var viewPickers: some View {
        if splatGroup != nil {
            Picker("Style", selection: $style) {
                Text("Photo").tag(Style.photo)
                Text("Points").tag(Style.points)
                Text("Mesh").tag(Style.mesh)
                if room != nil { Text("Model").tag(Style.model) } // RoomPlan's model: iPhone scans only
                // an icon, not a word: a sixth word made the row wider than the phone (rk 13 Nov 17:44)
                if SplatPreview.hasResult(current) { Image(systemName: "cube.transparent").accessibilityLabel("Photo-real").tag(Style.photoReal) }
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

    /// Removed or moved objects: their old spot is hidden in the photo room (as the splats already do) and the floor
    /// and wall it hid get a best-guess fill (HoleFill), made in the background the first time.
    private func updateHoles() {
        guard let scene, let shell else { return }
        if holeFillRoot.parent == nil { scene.rootNode.addChildNode(holeFillRoot) }
        let away = objects.filter { !$0.kind.isMarker && ($0.removed || simd_length($0.offset) > 0.001 || abs($0.turn) > 0.001) }
        let awayIds = Set(away.map(\.id))
        for (id, node) in holeFills { node.isHidden = !awayIds.contains(id) }
        let surfels = self.surfels
        if !surfels.isEmpty {
            for o in away where holeFills[o.id] == nil && !holeFillsPending.contains(o.id) {
                holeFillsPending.insert(o.id)
                let floorY = shell.floorY
                Task {
                    let fill = await Task.detached(priority: .userInitiated) { () -> (positions: [SIMD3<Float>], colors: [SIMD3<Float>]) in
                        let r = simd_length(SIMD2(o.halfExtents.x, o.halfExtents.z)) + 0.4
                        let near = surfels.compactMap { s -> HoleFill.Point? in
                            guard abs(s.x - o.center.x) <= r, abs(s.z - o.center.z) <= r else { return nil }
                            let c = SIMD3<Float>(Float(s.rgba & 0xFF), Float((s.rgba >> 8) & 0xFF), Float((s.rgba >> 16) & 0xFF)) / 255
                            return HoleFill.Point(p: SIMD3(s.x, s.y, s.z), c: c)
                        }
                        return HoleFill.build(near, center: SIMD2(o.center.x, o.center.z), u: SIMD2(cos(o.yaw), -sin(o.yaw)),
                                              hu: o.halfExtents.x, hv: o.halfExtents.z,
                                              y0: o.center.y - o.halfExtents.y, y1: o.center.y + o.halfExtents.y, floorY: floorY)
                    }.value
                    holeFillsPending.remove(o.id)
                    guard !fill.positions.isEmpty else { return }
                    let node = HoleFill.node(positions: fill.positions, colors: fill.colors)
                    holeFillRoot.addChildNode(node)
                    holeFills[o.id] = node
                    updateHoles()
                }
            }
        }
        // The photo room: leave out its triangles in the old spots (no custom shader: a failed one draws magenta).
        let cuts = away.map { ($0.id, HoleFill.hideBox($0), $0.removed ? nil : $0.editTransform) }
        if let photoRoom { HoleFill.cutOut(photoRoom, objects: cuts) }
        if let meshNode {
            HoleFill.cutOut(meshNode, objects: cuts)
            BareMesh.refreshWire(meshNode)
        }
    }

    // MARK: Scan area again

    private func startPickingAreas() {
        if objectsOn { toggleObjects() }
        if lightingOn { toggleLighting() }
        areaTargets = []
        areaRoot.childNodes.forEach { $0.removeFromParentNode() }
        if areaRoot.parent == nil { scene?.rootNode.addChildNode(areaRoot) }
        pickingAreas = true
    }

    /// A tap on the room marks an area (a ball around the spot it hit); another tap marks another.
    private func areaTapped(_ origin: SIMD3<Float>, _ dir: SIMD3<Float>) -> Bool {
        guard let scene else { return true }
        let to = origin + dir * 30
        let hits = scene.rootNode.hitTestWithSegment(from: SCNVector3(origin), to: SCNVector3(to), options: nil)
            .filter { hit in !(hit.node.parent === areaRoot || hit.node === areaRoot) }
        guard let hit = hits.first else { return true }
        let p = SIMD3<Float>(hit.worldCoordinates)
        areaTargets.append(p)
        let ball = SCNNode(geometry: SCNSphere(radius: CGFloat(RescanTargets.radius)))
        let m = SCNMaterial()
        m.diffuse.contents = UIColor(red: 1, green: 0.6, blue: 0.15, alpha: 0.35)
        m.lightingModel = .constant
        m.writesToDepthBuffer = false
        ball.geometry?.materials = [m]
        ball.simdPosition = p
        ball.renderingOrder = 50
        areaRoot.addChildNode(ball)
        return true
    }

    private var areaBar: some View {
        HStack(spacing: 10) {
            Text(areaTargets.isEmpty ? "Tap the area to rescan" : "\(areaTargets.count) marked · tap to add more")
                .font(.callout)
                .padding(.horizontal, 12).padding(.vertical, 8)
                .background(.regularMaterial, in: Capsule())
            Button("Cancel") {
                pickingAreas = false
                areaTargets = []
                areaRoot.childNodes.forEach { $0.removeFromParentNode() }
            }
            .buttonStyle(.bordered)
            Button("Start") {
                RescanTargets.current = areaTargets
                pickingAreas = false
                areaRoot.childNodes.forEach { $0.removeFromParentNode() }
                onImprove?()
            }
            .buttonStyle(.borderedProminent)
            .disabled(areaTargets.isEmpty)
        }
    }

    /// The selected object can be moved and turned (a movable thing, not removed).
    private var selectedMovable: Bool {
        guard let id = selectedObject, let o = objects.first(where: { $0.id == id }) else { return false }
        return !o.kind.isMarker && !o.removed
    }

    /// Where a drag grabbed the selected object: its centre minus the grab point on its base plane (x, z).
    @State private var dragGrab: SIMD2<Float>?
    @State private var dragPlaneY: Float = 0

    /// One finger on the selected object slides it along the floor, as on Android: the touch ray meets the plane of
    /// the object's base, and the object keeps its place under the finger (no jump). One undo step per drag.
    private func objectDragged(_ phase: Int, _ origin: SIMD3<Float>, _ dir: SIMD3<Float>) -> Bool {
        guard let id = selectedObject, let i = objects.firstIndex(where: { $0.id == id }) else { return false }
        let o = objects[i]
        let now = SIMD2(o.center.x + o.offset.x, o.center.z + o.offset.z)
        switch phase {
        case 0:
            // Only a drag that starts on the selected object's box moves it; any other drag turns the view. The drag
            // runs on the level plane through the point the finger touched (not the floor: from a grazing view the
            // floor point behind a tall object runs far ahead of the finger), so that point stays under the finger.
            guard let (hitId, t) = objectHitDistance(origin, dir), hitId == id else { return false }
            let p = origin + dir * t
            dragPlaneY = p.y
            objectHistory.append(objects)
            dragGrab = now - SIMD2(p.x, p.z)
            return true
        case 1:
            guard let grab = dragGrab, abs(dir.y) > 1e-4 else { return false }
            let t = (dragPlaneY - origin.y) / dir.y
            guard t > 0 else { return true }
            let p = origin + dir * t
            let c = SIMD2(p.x, p.z) + grab
            objects[i].offset = SIMD3(c.x - o.center.x, 0, c.y - o.center.z)
            return true
        default:
            dragGrab = nil
            return true
        }
    }

    /// Two fingers sideways on a selected object: 0 began (one undo step), 1 turn by radians, 2 ended (settles on a
    /// 15° step when within 3° of one).
    private func objectTwisted(_ phase: Int, _ radians: Float) {
        guard let id = selectedObject, let i = objects.firstIndex(where: { $0.id == id }) else { return }
        switch phase {
        case 0: objectHistory.append(objects)
        case 1:
            var t = objects[i].turn + radians
            if t > .pi { t -= 2 * .pi } else if t < -.pi { t += 2 * .pi }
            objects[i].turn = t
        default:
            let step = Float.pi / 12, near = (objects[i].turn / step).rounded() * step
            if abs(objects[i].turn - near) < 3 * .pi / 180 { objects[i].turn = near }
        }
    }

    /// Objects mode: a tap on an object's box selects it; a tap on empty space lets go of the selected one, as on
    /// Android. Always takes the tap.
    private func objectTapped(_ origin: SIMD3<Float>, _ dir: SIMD3<Float>) -> Bool {
        selectedObject = objectHit(origin, dir)
        return true
    }

    /// The object whose box the ray meets first, if any (movable, not removed; 3 cm margin).
    private func objectHit(_ origin: SIMD3<Float>, _ dir: SIMD3<Float>) -> UUID? { objectHitDistance(origin, dir)?.0 }

    /// The same, with how far along the ray it meets the box.
    private func objectHitDistance(_ origin: SIMD3<Float>, _ dir: SIMD3<Float>) -> (UUID, Float)? {
        var best: (UUID, Float)?
        for o in objects where !o.kind.isMarker && !o.removed {
            // The ray in the box's own frame (turned by yaw + turn, moved by the offset), slab test with a 3 cm margin.
            let c = o.center + o.offset
            let q = simd_quatf(angle: -(o.yaw + o.turn), axis: SIMD3(0, 1, 0))
            let ro = q.act(origin - c), rd = q.act(dir)
            let h = o.halfExtents + 0.03
            var t0: Float = 0, t1: Float = .greatestFiniteMagnitude
            var hit = true
            for k in 0..<3 {
                if abs(rd[k]) < 1e-6 {
                    if abs(ro[k]) > h[k] { hit = false; break }
                } else {
                    var a = (-h[k] - ro[k]) / rd[k], b = (h[k] - ro[k]) / rd[k]
                    if a > b { swap(&a, &b) }
                    t0 = max(t0, a); t1 = min(t1, b)
                    if t0 > t1 { hit = false; break }
                }
            }
            if hit, best == nil || t0 < best!.1 { best = (o.id, t0) }
        }
        return best
    }

    // MARK: Rooms on the floor (area, tap a room, drag its walls)

    /// The plain 3D view (no panel, not standing in the room or marking areas) with the wall lines on: rooms can be
    /// tapped, walls dragged.
    private var measuringRooms: Bool {
        showRoomLines && floorRooms != nil && walk == .off && !lightingOn && !objectsOn && !pickingAreas
    }

    /// The room whose walls show and can be dragged: the picked one, else the first (as on Android).
    private var adjustableRoom: Int { selectedRoom ?? 0 }

    private var subtitle: String? {
        var parts: [String] = []
        if let floorRooms {
            parts.append(floorRooms.titleText(selected: selectedRoom, wallSize: room.flatMap(RoomSummary.wallSize).map { ($0.long, $0.short) }))
        }
        if let last = improved.last, selectedRoom == nil {
            parts.append("Improved \(last.formatted(.dateTime.day().month(.abbreviated).hour().minute()))")
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    /// The picked room's numbers, and Reset walls.
    private func roomBar(_ rooms: FloorRooms) -> some View {
        HStack(spacing: 8) {
            if selectedRoom != nil || dragSide != nil {
                let i = adjustableRoom
                Text(rooms.rooms.count >= 2 ? "Room \(i + 1): " + rooms.roomText(i) : rooms.roomText(i))
                    .font(.callout.monospacedDigit())
                    .lineLimit(2)
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(.regularMaterial, in: Capsule())
            }
            if rooms.anyEdited, dragSide == nil {
                Button("Reset walls") { resetWalls() }
                    .buttonStyle(.bordered)
                    .font(.callout)
            }
        }
    }

    /// Shows the rooms' lines and the picked room in the 3D view (hidden while a panel is open).
    private func showFloorRooms() {
        guard let scene, let floorRooms else { return }
        if floorRoomsNode.root.parent == nil { scene.rootNode.addChildNode(floorRoomsNode.root) }
        floorRoomsNode.update(floorRooms, selected: selectedRoom, adjustable: adjustableRoom, dragging: dragSide)
        floorRoomsNode.root.isHidden = !measuringRooms
    }

    private func resetWalls() {
        guard var rooms = floorRooms else { return }
        rooms.edits = rooms.rooms.map { [Double](repeating: 0, count: $0.outline.count) }
        floorRooms = rooms
        saveFloorRooms()
        showFloorRooms()
    }

    private func saveFloorRooms() {
        guard let rooms = floorRooms else { return }
        let scan = scan
        Task.detached(priority: .utility) { rooms.save(scan) }
    }

    /// One finger that starts on a wall line moves that wall along the floor (1 cm steps); else it turns the view.
    private func wallDragged(_ phase: Int, _ origin: SIMD3<Float>, _ dir: SIMD3<Float>) -> Bool {
        guard var rooms = floorRooms, abs(dir.y) > 1e-4 else { return false }
        let t = (rooms.floorY - origin.y) / dir.y
        guard t > 0 else { return phase != 0 }
        let hit = origin + dir * t
        let p = FloorPoint(x: Double(hit.x), z: Double(hit.z))
        switch phase {
        case 0:
            // Within about a finger's width on screen: wider the further the floor is from the camera.
            let tolerance = max(0.12, Double(t) * 0.05)
            guard let side = rooms.side(near: p, room: adjustableRoom, tolerance: tolerance) else { return false }
            dragSide = side
        case 1:
            guard let side = dragSide else { return false }
            rooms.drag(room: adjustableRoom, side: side, to: p)
            floorRooms = rooms
        default:
            guard dragSide != nil else { return false }
            dragSide = nil
            saveFloorRooms()
        }
        showFloorRooms()
        return true
    }

    private func floorTapped(_ p: SIMD3<Float>) {
        if measuringRooms, let rooms = floorRooms {
            // A tap on a room's floor picks it (its numbers show, its walls can be dragged); again, or outside, lets go.
            let hit = rooms.room(at: FloorPoint(x: Double(p.x), z: Double(p.z)))
            selectedRoom = hit == selectedRoom ? nil : hit
            showFloorRooms()
            return
        }
        if walk == .choosing {
            walk = .inside(p)
        } else if objectsOn {
            // Move the selected object so its centre stands over the tapped spot.
            guard let id = selectedObject, let i = objects.firstIndex(where: { $0.id == id }),
                  !objects[i].kind.isMarker, !objects[i].removed else { return }
            objectHistory.append(objects)
            objects[i].offset = SIMD3(p.x - objects[i].center.x, 0, p.z - objects[i].center.z)
        } else if lightingOn {
            lighting?.subjectX = p.x
            lighting?.subjectZ = p.z
        }
    }

    /// Writes the photo room as RoomScape-<scan date>.glb in the temporary folder and opens the share sheet for it.
    /// Writes the whole scan as a .rmsc package (see `Rmsc`) and opens the share sheet for it.
    /// Loads the room, its photo room, mesh, splats, objects and the rest (was the body of `.task`; a method so the
    /// compiler checks it statement by statement instead of as one huge closure).
    /// Drops the 3D scene and what hangs on it (points, photo room, splats), so the photo-real preview has the memory.
    private func releaseRoom() {
        guard scene != nil || loadStarted else { return }
        walk = .off
        if lightingOn { toggleLighting() }
        if objectsOn { toggleObjects() }
        scene = nil; modelGroup = nil; splatGroup = nil; photoRoom = nil
        loadStarted = false
        ProcessingQueue.logMemory("room view released")
    }

    /// Beside "Enter the room": open the photo-real room, or make it (asked first), or say it's being made.
    @ViewBuilder private var photoRealButton: some View {
        if SplatPreview.hasResult(current) {
            Button { if splatGroup != nil { style = .photoReal } else { showingSplat = true } } label: { Label("Photo-real", systemImage: "cube.transparent") }
                .buttonStyle(.borderedProminent)
        } else if processing.splatQueued(current) {
            Label("Making photo-real…", systemImage: "hourglass")
                .font(.callout).padding(.horizontal, 12).padding(.vertical, 8)
                .background(.regularMaterial, in: Capsule())
        } else if recordingBytes != nil {
            Button { confirmingSplat = true } label: { Label("Make photo-real", systemImage: "cube.transparent") }
                .buttonStyle(.bordered)
        }
    }

    private func loadRoom() async {
        // not while the photo-real preview runs: it needs the memory (the view loads when it's done)
        guard !loadStarted, !ProcessingQueue.shared.gpuJob else { return }
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

        // The floor's rooms (area, size, tap a room, drag its walls): measured alongside the rest of the loading.
        if let room = loaded.0 {
            Task {
                let made = await Task.detached(priority: .userInitiated) {
                    FloorRooms.make(room: room, points: ScanStore.loadPoints(scan), scan: scan)
                }.value
                floorRooms = made
                // floor-area.json, the numbers a shared .rmsc carries.
                if let made { Task.detached(priority: .utility) { made.save(scan) } }
                showFloorRooms()
            }
        }

        // Windows from the photos and LiDAR: RoomPlan misses many (curtained ones especially). Shown on the walls
        // in both styles, counted in the summary and given to the shoot planner.
        if let room = loaded.0, let scene, capture != nil {
            let windows = await Task.detached(priority: .userInitiated) { WindowDetection.load(scan, room: room) }.value
            foundWindows = windows
            scene.rootNode.addChildNode(WindowDetection.node(windows, room: room))
        }

        recordingBytes = await Task.detached(priority: .utility) { () -> Int64? in
            let folder = scan.captureURL.appendingPathComponent("recording")
            guard FileManager.default.fileExists(atPath: folder.path) else { return nil }
            let files = FileManager.default.enumerator(at: folder, includingPropertiesForKeys: [.fileSizeKey])?.allObjects as? [URL] ?? []
            return files.reduce(Int64(0)) { $0 + Int64((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
        }.value

        // Power outlets, as orange labels in every view (like the Android app).
        if let scene {
            let found = await Task.detached(priority: .utility) { OutletStore.load(scan) }.value
            outlets = found
            if !found.isEmpty {
                // RoomPlan's floor; scans imported without one were moved so their floor is at 0.
                let floor = room.map { RoomShell(room: $0).floorY } ?? 0
                let node = OutletStore.node(found, floorY: floor)
                node.isHidden = !showOutlets
                scene.rootNode.addChildNode(node)
            }
        }

        // Photo room, when the scan has a mesh and photos: built in the background, shown by Photo.
        if let scene {
            // Painted from the mesh and photos; for a scan imported without a mesh, the package's own room.glb.
            let photo = await Task.detached(priority: .userInitiated) {
                PhotoRoom.build(scan) ?? GLBScene.node(forScan: scan.captureURL)
            }.value
            // Flat tiles saved with the scan: over the points and the photo room
            if let tiles = FlatTiles.node(folder: scan.captureURL) {
                tiles.isHidden = style == .model || style == .mesh
                scene.rootNode.addChildNode(tiles)
                tilesNode = tiles
            }
            if let mesh = await Task.detached(priority: .utility) { BareMesh.node(scan) }.value {
                mesh.isHidden = style != .mesh
                scene.rootNode.addChildNode(mesh)
                meshNode = mesh
            }
            if let photo {
                // Sharp text patches over the photo room (shown and hidden with it).
                if let decals = await Task.detached(priority: .utility) { DecalFinder.node(folder: scan.captureURL) }.value {
                    photo.addChildNode(decals)
                }
                scene.rootNode.addChildNode(photo)
                photoRoom = photo
                updateHoles()
                style = LivePreview.livePoints ? .points : .photo
                showStyle(style)
            }
        }

        // Splats, when the scan has points: built in the background (cached after the first time), then shown
        // instead of RoomPlan's boxes. The Splats / Model switch flips between the two.
        // Scans from the Android app have no RoomPlan model: they get splats, objects and lighting too, in a room
        // with its floor at 0 (where imports put it) and no walls guessed.
        let room = loaded.0
        guard let scene, (capture?.pointCount ?? 0) > 0 else { return }
        buildingSplats = true
        let shell = room.map(RoomShell.init(room:)) ?? RoomShell(floorY: 0)
        // Objects: what was saved (with the user's edits), else RoomPlan's furniture.
        let saved = RoomObjects.load(scan)
        let objects = saved?.objects ?? room.map(RoomObjects.fromRoomPlan) ?? []
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
        objectsScene.boxes.isHidden = !objectsOn
        scene.rootNode.addChildNode(objectsScene.boxes)
        splats.isHidden = true
        scene.rootNode.addChildNode(splats)
        splatGroup = splats
        self.surfels = surfels
        updateHoles()
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
        // The photo room is what Lighting lights now (rk 9 Nov); the splats only when there's no photo room.
        if let photoRoom { lightingScene.register(photoRoom) }
        scene.rootNode.addChildNode(lightingScene.root)
        self.lightingScene = lightingScene
        self.shell = shell
        // Photo is the default look: the photo room, or the splats in their scanned colours (Points with the
        // points-only test switch).
        style = LivePreview.livePoints ? .points : .photo
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-stylePhoto") { style = .photo }
        #endif
        if reloaded, let kept = Self.lastStyle[scan.name] { style = kept }
        // Shown now: the splats were added hidden, and when the style was already Points (set when the photo room
        // came in) the change handler doesn't run, so nothing showed (rk 10 Nov 19:22 scan, points-only switch on).
        showStyle(style)
        // No splat training any more (rk 9 Nov: the Splats view is gone; the splat data only feeds Lighting).
        #if DEBUG
        // UI checks in the Simulator: -openPanel lighting | objects
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-openPanel"), i + 1 < args.count {
            if args[i + 1] == "lighting" { debugOpenLighting(args) }
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
        if args.contains("-replayFind"), let saved, let room {
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
        await debugStyleAndSelection(args)
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
        if let i = args.firstIndex(of: "-train"), i + 1 < args.count, let seconds = Double(args[i + 1]), let training = self.training {
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

    /// The shot preview over the 3D view: under the controls row, so a large view never covers the buttons.
    @ViewBuilder private func shotInset(_ scene: SCNScene, _ setup: LightingSetup) -> some View {
        let large = shotLarge
        let side: CGFloat = large ? 16 : 10
        let top: CGFloat = large ? controlsHeight + 6 : 0
        let place: Alignment = large ? .center : .bottomTrailing
        ShotPreviewInset(scene: scene, setup: setup, large: $shotLarge)
            .padding(.horizontal, side)
            .padding(.top, top)
            .padding(.bottom, side)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: place)
    }

    #if DEBUG
    private func debugOpenLighting(_ args: [String]) {
        toggleLighting()
        if let j = args.firstIndex(of: "-roomDimmer"), j + 1 < args.count, let v: Float = Float(args[j + 1]) { lighting?.roomDimmer = v }
        if let j = args.firstIndex(of: "-cameraHeight"), j + 1 < args.count, let v: Float = Float(args[j + 1]) { lighting?.cameraHeight = v }
    }

    /// tools/ui-shots.sh: -style photo|splats|points|mesh, -selectObject <name> (opens Objects with it selected).
    private func debugStyleAndSelection(_ args: [String]) async {
        if let i = args.firstIndex(of: "-style"), i + 1 < args.count {
            try? await Task.sleep(nanoseconds: 500_000_000)
            switch args[i + 1] {
            case "splats": style = .points
            case "points": style = .points
            case "mesh": style = .mesh
            default: style = .photo
            }
        }
        if let i = args.firstIndex(of: "-selectObject"), i + 1 < args.count {
            if !objectsOn { toggleObjects() }
            let name = args[i + 1]
            selectedObject = objects.first { $0.label.localizedCaseInsensitiveContains(name) }?.id
            // -objectEdit remove | move | putback: what the buttons / a drag do, for UI checks in the Simulator.
            if let j = args.firstIndex(of: "-objectEdit"), j + 1 < args.count,
               let k = objects.firstIndex(where: { $0.id == selectedObject }) {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                objectHistory.append(objects)
                switch args[j + 1] {
                case "remove": objects[k].removed = true
                case "move": objects[k].removed = false; objects[k].offset = SIMD3(-0.8, 0, 0.6)
                default: objects[k].removed = false; objects[k].offset = .zero
                }
                print("debug: object \(objects[k].label) \(args[j + 1])")
            }
        }
        // -enterRoom: stand in the middle of the room (Enter the room), for UI checks in the Simulator.
        if args.contains("-enterRoom"), let node = photoRoom ?? meshNode {
            let (lo, hi) = node.boundingBox
            let c = node.simdConvertPosition(SIMD3((Float(lo.x) + Float(hi.x)) / 2, 0, (Float(lo.z) + Float(hi.z)) / 2), to: nil)
            if let fy = walkFloorY { walk = .inside(SIMD3(c.x, fy, c.z)) }
        }
    }
    #endif

    /// Builds this scan's surface, points and photos again from its raw recording, in the background, paced by heat.
    /// The files it replaces are kept in before-rebuild/.
    private func rebuildFromRecording() {
        ProcessingQueue.shared.add(current, .rebuild)
    }

    private func exportRmsc(save: Bool) {
        exportingGLB = true
        let scan = current
        Task {
            let result = await Task.detached(priority: .userInitiated) { () -> Result<URL, Error> in
                Result { try Rmsc.export(scan) }
            }.value
            exportingGLB = false
            switch result {
            case .success(let url): if save { savingFile = SharedFile(url: url) } else { glbFile = SharedFile(url: url) }
            case .failure(let error): glbError = error.localizedDescription
            }
        }
    }

    /// Share link items of the Share menu (RoomLink, switch "Share links (test)"): the room online for others' browsers.
    @ViewBuilder private var roomLinkItems: some View {
        if RoomLink.enabled {
            if let link = RoomLink.link(current) {
                ShareLink(item: link) { Label("Share link to this room", systemImage: "link") }
                Button { UIPasteboard.general.url = link } label: { Label("Copy share link", systemImage: "doc.on.doc") }
            } else if SplatPreview.hasResult(current) {
                Button { makeRoomLink() } label: { Label("Make share link", systemImage: "link.badge.plus") }
            }
        }
    }

    private func makeRoomLink() {
        exportingGLB = true
        let scan = current
        Task {
            do { let link = try await RoomLink.uploadPreview(scan); exportingGLB = false; UIPasteboard.general.url = link; linkMade = link }
            catch { exportingGLB = false; glbError = error.localizedDescription }
        }
    }

    private func exportForPC(_ kind: PCRebuildShare.Kind) {
        exportingGLB = true
        let scan = current
        Task {
            let result = await Task.detached(priority: .userInitiated) { () -> Result<URL, Error> in
                do { return .success(try await PCRebuildShare.zip(scan, kind)) } catch { return .failure(error) }
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
    private var summaryShown: Bool { !objectsOn && !lightingOn && (room != nil || capture != nil) && walk == .off }

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

    /// Exit, Gyro, Measure (and Clear) while standing in the room.
    @ViewBuilder private var walkBarButtons: some View {
        Button {
            walkMove = 0; walkStrafe = 0
            measuring = false
            walk = .off
        } label: {
            Label("Exit", systemImage: "arrow.uturn.backward")
        }
        .buttonStyle(.borderedProminent)
        Button { walkGyro.toggle() } label: {
            Label(walkGyro ? "Gyro on" : "Gyro off", systemImage: walkGyro ? "gyroscope" : "hand.draw")
        }
        .buttonStyle(.bordered)
        .accessibilityLabel(walkGyro ? "Turn off looking around by turning the phone" : "Look around by turning the phone")
        Button { measuring.toggle() } label: {
            Label(measuring ? "Measure: on" : "Measure", systemImage: "ruler")
        }
        .buttonStyle(.bordered)
        if measuring {
            Button("Clear") { confirmClearMeasures = true }
                .buttonStyle(.bordered)
                .confirmationDialog("Remove all measurements? They're saved with this scan.", isPresented: $confirmClearMeasures, titleVisibility: .visible) {
                    Button("Remove all", role: .destructive) { measureClear += 1 }
                }
        }
    }

    /// Enter the room / the floor hint / ▲▼ and Exit while standing in it.
    @ViewBuilder private var walkControls: some View {
        switch walk {
        case .off:
            HStack(spacing: 8) {
                Button {
                    if lightingOn { toggleLighting() }
                    if objectsOn { toggleObjects() }
                    walk = .choosing
                } label: {
                    Label("Enter the room", systemImage: "figure.walk")
                }
                .buttonStyle(.borderedProminent)
                // The photo-real preview, once made: an obvious button on the room view, not only in Share (rk 12 Nov:
                // "i cant see the brush marks anywhere"). Here, not in the switch row above: there it made that row wider
                // than the screen (rk 16:09).
                photoRealButton
            }
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
            // The moves (forward, back, left, right; hold) and the eye height in their own cluster on the right, above
            // a bottom bar that wraps, so nothing leaves the screen (rk 10 Nov 12:07, the same as Android).
            VStack(alignment: .trailing, spacing: 10) {
                HStack(alignment: .bottom, spacing: 10) {
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
                    VStack(spacing: 6) {
                        HoldButton(systemImage: "arrowtriangle.up.fill") { walkMove = $0 ? 1 : 0 }
                        HStack(spacing: 6) {
                            HoldButton(systemImage: "arrowtriangle.left.fill") { walkStrafe = $0 ? -1 : 0 }
                            HoldButton(systemImage: "arrowtriangle.down.fill") { walkMove = $0 ? -1 : 0 }
                            HoldButton(systemImage: "arrowtriangle.right.fill") { walkStrafe = $0 ? 1 : 0 }
                        }
                    }
                }
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 10) { walkBarButtons; Spacer(minLength: 0) }
                    VStack(alignment: .leading, spacing: 8) { walkBarButtons }
                }
            }
            .frame(maxWidth: .infinity)
            .overlay(alignment: .top) {
                Text(measuring ? "Tap two spots on the scan to measure · tap a measurement to select or delete it"
                     : walkGyro ? "Turn the phone or drag to look · pinch to zoom · double-tap the floor to walk there"
                              : "Drag to look · pinch to zoom · double-tap the floor to walk there")
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
        // Mesh: the bare fused surface; a scan without one shows the splats in grey instead (the old Mesh look).
        let bareMesh = style == .mesh && meshNode != nil
        modelGroup?.isHidden = style != .model
        photoRoom?.isHidden = !photos
        meshNode?.isHidden = !bareMesh
        tilesNode?.isHidden = style == .model || style == .mesh
        splatGroup?.isHidden = style == .model || photos || bareMesh
        if let splatGroup {
            SplatScene.setMeshLook(style == .mesh, in: splatGroup)
            SplatScene.setPointLook(style == .points, in: splatGroup)
        }
    }

    private func toggleObjects() {
        objectsOn.toggle()
        objectsScene?.boxes.isHidden = !objectsOn
        if objectsOn {
            // The outlines show in every style but RoomPlan's own model (moving and removing moves the splats).
            if style == .model || style == .photoReal { style = .points }
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
                lightingScene?.reregister([splatGroup] + (photoRoom.map { [$0] } ?? []))
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
            lightingScene?.reregister([splatGroup] + (photoRoom.map { [$0] } ?? []))
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
        // Calibrated on what's lit: the photo room's surface, else the splats.
        var samples = photoRoom.map { PhotoRoom.samples($0, count: 2000) } ?? []
        if samples.isEmpty {
            let step = max(1, surfels.count / 2000)
            samples = Swift.stride(from: 0, to: surfels.count, by: step).compactMap { i -> (p: SIMD3<Float>, n: SIMD3<Float>)? in
                let s = surfels[i]
                let n = SIMD3(s.nx, s.ny, s.nz)
                return simd_length_squared(n) > 0.25 ? (SIMD3(s.x, s.y, s.z), n) : nil
            }
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
        // Lit: the photo room (rk 9 Nov), or the splats as points when the scan has no photo room.
        if style == .model || style == .mesh || style == .photoReal { style = photoRoom != nil ? .photo : .points }
        if objectsOn { toggleObjects() }
        if lighting == nil, let shell {
            // Start in the middle of the room with the longest wall behind the person as the background.
            // No walls (a scan from the Android app): the middle of the splats.
            let center = shell.walls.isEmpty
                ? surfels.reduce(SIMD3<Float>(), { $0 + SIMD3($1.x, $1.y, $1.z) }) / Float(max(1, surfels.count))
                : shell.walls.reduce(SIMD3<Float>(), { $0 + $1.center }) / Float(max(1, shell.walls.count))
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
    /// Gets every tap first, as a ray (origin, unit direction); returns true when it used it (an object was hit).
    var onTapRay: ((SIMD3<Float>, SIMD3<Float>) -> Bool)? = nil
    /// Set while an object is selected: two fingers sideways turn it (0 began, 1 by radians, 2 ended), not the view.
    var onTwist: ((Int, Float) -> Void)? = nil
    /// Objects mode: a one-finger drag that starts on the selected object moves it (0 began, 1 moved, 2 ended), as a
    /// ray (origin, unit direction); the began call returns true when it took the drag (the view doesn't turn then).
    var onObjectDrag: ((Int, SIMD3<Float>, SIMD3<Float>) -> Bool)? = nil
    /// The same for a room's wall lines on the floor (used when no object can be dragged).
    var onFloorDrag: ((Int, SIMD3<Float>, SIMD3<Float>) -> Bool)? = nil
    /// Standing in the room (first person) at this floor point; nil = the orbit or top view.
    var standAt: SIMD3<Float>? = nil
    /// The floor, for walking to a double-tapped spot.
    var walkFloorY: Float? = nil
    /// Held ▲ (+1) / ▼ (-1): walk forward or back, level, in the look direction.
    var walkMove = 0
    /// Held ◀ (-1) / ▶ (+1): sideways, level, across the look direction.
    var walkStrafe = 0
    /// Standing in the room: turning / tilting the phone looks around (rk 10 Nov).
    var gyroLook = true
    /// Eye height above the floor while standing, metres.
    var eyeHeight: Float = 1.5
    /// Measure mode while standing: taps pick spots on the scan; a change of `measureClear` removes all measurements.
    var measuring = false
    var measureClear = 0
    /// Where the scan's measurements are kept.
    var measureFile: URL? = nil
    /// Lets the screen read and set the orbit camera (the Photo-real tab).
    var cameraLink: RoomCameraLink? = nil

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate, SCNSceneRendererDelegate {
        /// The 3D view's height in points, for sizing labels on screen (set on the main thread, read when rendering).
        var viewHeight: CGFloat = 0

        func renderer(_ renderer: SCNSceneRenderer, updateAtTime time: TimeInterval) {
            guard let scene = renderer.scene, let pov = renderer.pointOfView else { return }
            OutletStore.screenSizes(in: scene, camera: pov, viewHeight: viewHeight > 0 ? viewHeight : 360, fov: pov.camera?.fieldOfView ?? 60)
            scaleMeasureLabels(camera: pov.simdWorldPosition)
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
            // Objects mode: a drag that starts on the selected object slides it along the floor instead.
            if let scn = view as? SCNView, let onObjectDrag = onObjectDrag ?? onFloorDrag, standing == nil {
                let t = g.translation(in: view), at = g.location(in: view)
                if g.state == .began, let (o, d) = ray(CGPoint(x: at.x - t.x, y: at.y - t.y), in: scn), onObjectDrag(0, o, d) {
                    draggingObject = true
                    inertia?.invalidate(); inertia = nil
                    if let (o, d) = ray(at, in: scn) { _ = onObjectDrag(1, o, d) }
                    return
                }
                if draggingObject {
                    if let (o, d) = ray(at, in: scn) { _ = onObjectDrag(g.state == .changed ? 1 : 2, o, d) }
                    if g.state != .changed { draggingObject = false }
                    g.setTranslation(.zero, in: view)
                    return
                }
            }
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
            // An object is selected: two fingers turn it about the vertical (rk 9 Nov), after a 28 pt mostly-sideways
            // dead zone, 1° per 3 pt; the view doesn't pan meanwhile (pinch still zooms).
            if let onTwist {
                switch g.state {
                case .began:
                    twistMode = 0; twistMoved = .zero
                    if !otherActive(g) { twoFingerLock = 0; pinchTotal = 1 }
                case .changed:
                    twistMoved.x += d.x; twistMoved.y += d.y
                    if twistMode == 0, twoFingerLock != 2, abs(twistMoved.x) > 28, abs(twistMoved.x) > 1.5 * abs(twistMoved.y) {
                        twoFingerLock = 1
                        twistMode = 1
                        onTwist(0, 0)
                    } else if twistMode == 1 {
                        onTwist(1, Float(d.x) / 3 * .pi / 180)
                    }
                default:
                    if twistMode == 1 { onTwist(2, 0) }
                    twistMode = 0
                }
                return
            }
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
            if onTwist != nil {
                // An object is selected: zoom only once the pinch is clearly a pinch, and never during a turn.
                if g.state == .began, !otherActive(g) { twoFingerLock = 0; pinchTotal = 1 }
                pinchTotal *= g.scale
                if twoFingerLock == 1 { g.scale = 1; return }
                if twoFingerLock == 0 {
                    guard abs(pinchTotal - 1) > 0.08 else { g.scale = 1; return }
                    twoFingerLock = 2
                    g.scale = pinchTotal // the zoom so far, all at once
                }
            }
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
        var onTapRay: ((SIMD3<Float>, SIMD3<Float>) -> Bool)?
        var onTwist: ((Int, Float) -> Void)?
        var onObjectDrag: ((Int, SIMD3<Float>, SIMD3<Float>) -> Bool)?
        var onFloorDrag: ((Int, SIMD3<Float>, SIMD3<Float>) -> Bool)?
        private var draggingObject = false

        func ray(_ p: CGPoint, in view: SCNView) -> (SIMD3<Float>, SIMD3<Float>)? {
            let near = view.unprojectPoint(SCNVector3(Float(p.x), Float(p.y), 0))
            let far = view.unprojectPoint(SCNVector3(Float(p.x), Float(p.y), 1))
            let a = SIMD3<Float>(near.x, near.y, near.z), b = SIMD3<Float>(far.x, far.y, far.z)
            guard simd_length(b - a) > 1e-6 else { return nil }
            return (a, simd_normalize(b - a))
        }
        /// The two-finger turn: 0 deciding (dead zone), 1 turning; and how far the fingers have gone meanwhile.
        private var twistMode = 0
        private var twistMoved = CGPoint.zero
        /// With an object selected, one two-finger gesture is either a turn (1) or a zoom (2), never both: whichever
        /// passes its threshold first (28 pt sideways, or 8% pinch) wins until the fingers lift. 0 = not decided.
        private var twoFingerLock = 0
        private var pinchTotal: CGFloat = 1
        private func otherActive(_ g: UIGestureRecognizer) -> Bool {
            g.view?.gestureRecognizers?.contains { $0 !== g && $0.numberOfTouches >= 2 && ($0.state == .began || $0.state == .changed) } ?? false
        }

        // ---- Measure (rk 10 Nov): lines between tapped spots on the scan, a distance label that faces the camera and
        // keeps its size on screen; one spot shows its height above the floor. View-only (not saved). Same as Android. ----
        var measuring = false
        var measureClearSeen = 0
        let measureRoot = SCNNode()
        private var measurePending: (p: SIMD3<Float>, node: SCNNode)?
        private var measureItems: [SCNNode] = []
        private var measureEnds: [(SIMD3<Float>, SIMD3<Float>)] = []
        private var measureSelected: Int?
        /// measurements.json in the scan's folder ([[x0, y0, z0, x1, y1, z1], ...]); loaded when first set.
        var measureFile: URL? { didSet { if measureFile != oldValue { loadMeasures() } } }
        static let measureYellow = UIColor(red: 1, green: 0.84, blue: 0.04, alpha: 1)

        static func distanceText(_ m: Float) -> String { m < 1 ? "\(Int((m * 100).rounded())) cm" : String(format: "%.2f m", m) }

        /// Nearly upright or level (within ~5°): the straight height or width, which is what's meant (as on Android).
        static func lineText(_ a: SIMD3<Float>, _ b: SIMD3<Float>) -> String {
            let d = simd_distance(a, b), dy = abs(b.y - a.y), hz = simd_length(SIMD2(b.x - a.x, b.z - a.z))
            if hz < 0.08 * d { return distanceText(dy) + " ↕" }
            if dy < 0.08 * d { return distanceText(hz) + " ↔" }
            return distanceText(d)
        }

        private func makeItem(_ a: SIMD3<Float>, _ b: SIMD3<Float>) -> SCNNode {
            let item = SCNNode()
            item.addChildNode(dot(a)); item.addChildNode(dot(b))
            let d = simd_distance(a, b)
            let cyl = SCNCylinder(radius: 0.004, height: CGFloat(d)); cyl.firstMaterial?.diffuse.contents = Self.measureYellow; onTop(cyl.firstMaterial!)
            let line = SCNNode(geometry: cyl); line.renderingOrder = 100; line.name = "measure-line"
            line.simdPosition = (a + b) / 2
            if d > 1e-4 { line.simdOrientation = simd_quatf(from: SIMD3(0, 1, 0), to: simd_normalize(b - a)) }
            item.addChildNode(line)
            item.addChildNode(label(Self.lineText(a, b), at: (a + b) / 2))
            return item
        }

        private func saveMeasures() {
            guard let url = measureFile else { return }
            let arr = measureEnds.map { [$0.0.x, $0.0.y, $0.0.z, $0.1.x, $0.1.y, $0.1.z].map(Double.init) }
            if arr.isEmpty { try? FileManager.default.removeItem(at: url); return }
            if let data = try? JSONSerialization.data(withJSONObject: arr) { try? data.write(to: url) }
        }

        private func loadMeasures() {
            measureItems.forEach { $0.removeFromParentNode() }; measureItems = []; measureEnds = []; measureSelected = nil
            guard let url = measureFile, let data = try? Data(contentsOf: url),
                  let arr = try? JSONSerialization.jsonObject(with: data) as? [[Double]] else { return }
            for m in arr where m.count == 6 {
                let a = SIMD3(Float(m[0]), Float(m[1]), Float(m[2])), b = SIMD3(Float(m[3]), Float(m[4]), Float(m[5]))
                let item = makeItem(a, b); measureRoot.addChildNode(item); measureItems.append(item); measureEnds.append((a, b))
            }
            if measureRoot.parent == nil, let root = view?.scene?.rootNode { root.addChildNode(measureRoot) }
        }

        /// A tap on a measurement selects it (white line, a Delete chip by its label); on the Delete chip removes it;
        /// anywhere else, with one selected, deselects. True when the tap was used.
        func measureSelectTap(_ pt: CGPoint, in view: SCNView) -> Bool {
            let hits = view.hitTest(pt, options: [.searchMode: SCNHitTestSearchMode.all.rawValue, .rootNode: measureRoot])
            if let del = hits.first(where: { $0.node.name == "measure-delete" }), let i = measureSelected, i < measureItems.count {
                _ = del
                measureItems[i].removeFromParentNode(); measureItems.remove(at: i); measureEnds.remove(at: i); measureSelected = nil
                UIImpactFeedbackGenerator(style: .medium).impactOccurred(); saveMeasures(); return true
            }
            if let h = hits.first(where: { $0.node.name == "measure-label" || $0.node.name == "measure-line" }), let item = h.node.parent,
               let i = measureItems.firstIndex(where: { $0 === item }) {
                select(measureSelected == i ? nil : i); return true
            }
            if measureSelected != nil { select(nil); return true }
            return false
        }

        private func select(_ i: Int?) {
            for (k, item) in measureItems.enumerated() {
                item.childNode(withName: "measure-delete", recursively: false)?.removeFromParentNode()
                let line = item.childNode(withName: "measure-line", recursively: false)
                line?.geometry?.firstMaterial?.diffuse.contents = k == i ? UIColor.white : Self.measureYellow
            }
            measureSelected = i
            if let i, i < measureItems.count, let lbl = measureItems[i].childNode(withName: "measure-label", recursively: false) {
                let del = label("Delete", at: lbl.simdPosition, color: UIColor(red: 0.9, green: 0.28, blue: 0.3, alpha: 0.95))
                del.name = "measure-delete"
                del.simdPosition = lbl.simdPosition + SIMD3(0, -0.09, 0)
                measureItems[i].addChildNode(del)
            }
        }

        func clearMeasures() {
            measureItems.forEach { $0.removeFromParentNode() }; measureItems = []; measureEnds = []; measureSelected = nil; saveMeasures()
            measurePending?.node.removeFromParentNode(); measurePending = nil
        }

        private func onTop(_ m: SCNMaterial) { m.readsFromDepthBuffer = false; m.lightingModel = .constant }

        private func dot(_ p: SIMD3<Float>) -> SCNNode {
            let s = SCNSphere(radius: 0.012); s.firstMaterial?.diffuse.contents = Self.measureYellow; onTop(s.firstMaterial!)
            let n = SCNNode(geometry: s); n.simdPosition = p; n.renderingOrder = 100; return n
        }

        private func label(_ text: String, at p: SIMD3<Float>, color: UIColor = UIColor(white: 0.11, alpha: 0.9)) -> SCNNode {
            let font = UIFont.boldSystemFont(ofSize: 40)
            let size = (text as NSString).size(withAttributes: [.font: font])
            let w = size.width + 36, h = size.height + 20
            let img = UIGraphicsImageRenderer(size: CGSize(width: w, height: h)).image { _ in
                color.setFill()
                UIBezierPath(roundedRect: CGRect(x: 0, y: 0, width: w, height: h), cornerRadius: h / 2).fill()
                (text as NSString).draw(at: CGPoint(x: 18, y: 10), withAttributes: [.font: font, .foregroundColor: UIColor.white])
            }
            let plane = SCNPlane(width: CGFloat(w / h) * 0.1, height: 0.1)
            plane.firstMaterial?.diffuse.contents = img; onTop(plane.firstMaterial!)
            let n = SCNNode(geometry: plane); n.name = "measure-label"; n.simdPosition = p; n.renderingOrder = 101
            n.constraints = [SCNBillboardConstraint()]
            return n
        }

        func measureTap(_ pt: CGPoint, in view: SCNView) {
            if measureRoot.parent == nil { view.scene?.rootNode.addChildNode(measureRoot) }
            // The scanned surface under the finger (not our own measure nodes).
            let hits = view.hitTest(pt, options: [.searchMode: SCNHitTestSearchMode.all.rawValue, .ignoreHiddenNodes: true])
            guard let hit = hits.first(where: { h in var n: SCNNode? = h.node; while let x = n { if x === measureRoot { return false }; n = x.parent }; return true }) else {
                UINotificationFeedbackGenerator().notificationOccurred(.warning); return
            }
            let p = SIMD3<Float>(Float(hit.worldCoordinates.x), Float(hit.worldCoordinates.y), Float(hit.worldCoordinates.z))
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            if let first = measurePending {
                first.node.removeFromParentNode(); measurePending = nil
                let item = makeItem(first.p, p)
                measureRoot.addChildNode(item); measureItems.append(item); measureEnds.append((first.p, p)); saveMeasures()
            } else {
                let n = SCNNode(); n.addChildNode(dot(p))
                if let fy = walkFloorY { n.addChildNode(label(Self.distanceText(max(0, p.y - fy)) + " above the floor", at: p + SIMD3(0, 0.06, 0))) }
                measureRoot.addChildNode(n); measurePending = (p, n)
            }
        }

        @objc func measureLongPressed(_ g: UILongPressGestureRecognizer) {
            guard measuring, g.state == .began, let view = g.view as? SCNView else { return }
            let hits = view.hitTest(g.location(in: view), options: [.searchMode: SCNHitTestSearchMode.all.rawValue, .rootNode: measureRoot])
            guard let h = hits.first(where: { $0.node.name == "measure-label" }), let item = h.node.parent,
                  let i = measureItems.firstIndex(where: { $0 === item }) else { return }
            item.removeFromParentNode(); measureItems.remove(at: i); measureEnds.remove(at: i); measureSelected = nil; saveMeasures()
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        }

        /// Labels keep about the same size on screen: scaled with their distance from the camera.
        func scaleMeasureLabels(camera: SIMD3<Float>) {
            for item in measureItems + [measurePending?.node].compactMap({ $0 }) {
                for n in item.childNodes where n.name == "measure-label" {
                    n.simdScale = SIMD3(repeating: max(0.3, simd_distance(n.simdWorldPosition, camera) * 0.45))
                }
            }
        }

        // First person ("Enter the room").
        weak var view: SCNView?
        let walkCamera = SCNNode()
        var savedPointOfView: SCNNode?
        var standing: SIMD3<Float>?
        var walkFloorY: Float?
        var walkMove = 0
        var walkStrafe = 0
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
            walkCamera.simdOrientation = simd_quatf(angle: lookYaw, axis: SIMD3(0, 1, 0)) * simd_quatf(angle: lookPitch, axis: SIMD3(1, 0, 0))
        }

        // Gyro look: `yaw` / `pitch` are then offsets (drags) on top of the phone's own heading and tilt.
        let motion = CMMotionManager()
        var gyroLook = true
        private var heading: Double?, heading0: Double?, devicePitch: Double = 0
        private var attitudeIsDeviceToWorld: Bool?

        var lookYaw: Float {
            guard gyroLook, let heading, let heading0 else { return yaw }
            return yaw + Float(heading - heading0)
        }
        var lookPitch: Float { gyroLook && heading != nil ? max(-1.45, min(1.45, pitch + Float(devicePitch))) : pitch }

        func setGyroLook(_ on: Bool) {
            guard on != gyroLook else { return }
            if !on, standing != nil {
                // Keep the view where it is.
                yaw = lookYaw; pitch = lookPitch
                stopMotion()
            }
            gyroLook = on
            if on, standing != nil { pitch = 0; startMotion() }
            applyLook()
        }

        func startMotion() {
            guard gyroLook, motion.isDeviceMotionAvailable, !motion.isDeviceMotionActive else { return }
            heading = nil; heading0 = nil; attitudeIsDeviceToWorld = nil
            motion.deviceMotionUpdateInterval = 1.0 / 60
            motion.startDeviceMotionUpdates(using: .xArbitraryZVertical)
        }

        func stopMotion() {
            if motion.isDeviceMotionActive { motion.stopDeviceMotionUpdates() }
            heading = nil; heading0 = nil
        }

        /// The back camera's heading (about the vertical, relative to when it started) and tilt from level, smoothed.
        func readMotion() {
            guard gyroLook, let dm = motion.deviceMotion else { return }
            let g = SIMD3(dm.gravity.x, dm.gravity.y, dm.gravity.z)
            let tilt = asin(max(-1, min(1, g.z)))     // the back (-z) against up (-gravity)
            let q = dm.attitude.quaternion
            let qd = simd_quatd(ix: q.x, iy: q.y, iz: q.z, r: q.w)
            if attitudeIsDeviceToWorld == nil {
                // Which way the attitude turns: device → world puts gravity at (0, 0, -1).
                attitudeIsDeviceToWorld = qd.act(g).z < qd.inverse.act(g).z
            }
            let rot = attitudeIsDeviceToWorld! ? qd : qd.inverse
            let back = rot.act(SIMD3<Double>(0, 0, -1))
            let h = atan2(back.y, back.x)
            if let old = heading {
                var d = h - old
                while d > .pi { d -= 2 * .pi }
                while d < -.pi { d += 2 * .pi }
                heading = old + d * 0.6
                devicePitch += (tilt - devicePitch) * 0.6
            } else {
                heading = h; heading0 = h; devicePitch = tilt
            }
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
                    pitch = gyroLook ? 0 : -0.1
                    startMotion()
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
                stopMotion()
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
            if gyroLook, standing != nil, motion.isDeviceMotionActive {
                readMotion()
                applyLook()
            }
            guard walkMove != 0 || walkStrafe != 0, standing != nil else { return }
            let forward = SIMD3<Float>(-sin(lookYaw), 0, -cos(lookYaw)), right = SIMD3<Float>(cos(lookYaw), 0, -sin(lookYaw))
            walkCamera.simdPosition += forward * Float(walkMove) * 1.2 * dt + right * Float(walkStrafe) * 1.0 * dt
        }

        @objc func looked(_ g: UIPanGestureRecognizer) {
            guard let view = g.view else { return }
            let d = g.translation(in: view)
            g.setTranslation(.zero, in: view)
            // Drag the scene: turn the opposite way to the finger, like a photo sphere.
            let scale = Float((walkCamera.camera?.fieldOfView ?? 60) / 60) * 0.005
            yaw += Float(d.x) * scale
            let limit: Float = gyroLook && motion.isDeviceMotionActive ? 2.6 : 1.3
            pitch = max(-limit, min(limit, pitch + Float(d.y) * scale))
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
            if standing != nil, let view = gesture.view as? SCNView, measureSelectTap(gesture.location(in: view), in: view) { return }
            if measuring, standing != nil, let view = gesture.view as? SCNView { measureTap(gesture.location(in: view), in: view); return }
            if standing == nil, let onTapRay, let view = gesture.view as? SCNView {
                let p = gesture.location(in: view)
                let near = view.unprojectPoint(SCNVector3(Float(p.x), Float(p.y), 0))
                let far = view.unprojectPoint(SCNVector3(Float(p.x), Float(p.y), 1))
                let a = SIMD3<Float>(near.x, near.y, near.z), b = SIMD3<Float>(far.x, far.y, far.z)
                if simd_length(b - a) > 1e-6, onTapRay(a, simd_normalize(b - a)) { return }
            }
            guard standing == nil, let floorY, let view = gesture.view as? SCNView,
                  let p = floorPoint(gesture.location(in: view), floorY: floorY, in: view) else { return }
            onFloorTap(p)
        }
    }

    func makeUIView(context: Context) -> SCNView {
        cameraLink?.coordinator = context.coordinator
        let view = SCNView()
        view.scene = scene
        view.allowsCameraControl = false // our own orbit controls (see Coordinator.orbitBy)
        view.delegate = context.coordinator // sizes the outlet labels on screen each frame
        view.isPlaying = true
        view.autoenablesDefaultLighting = true
        // Transparent: the zebra pattern behind it shows where nothing was scanned (ZebraBackground).
        view.backgroundColor = .clear
        view.isOpaque = false
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
        view.addGestureRecognizer(UILongPressGestureRecognizer(target: c, action: #selector(Coordinator.measureLongPressed(_:))))
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
        context.coordinator.onTapRay = onTapRay
        context.coordinator.onTwist = onTwist
        context.coordinator.onObjectDrag = onObjectDrag
        context.coordinator.onFloorDrag = onFloorDrag
        context.coordinator.walkFloorY = walkFloorY
        context.coordinator.viewHeight = view.bounds.height
        context.coordinator.walkMove = walkMove
        context.coordinator.walkStrafe = walkStrafe
        context.coordinator.setGyroLook(gyroLook)
        context.coordinator.setEyeHeight(eyeHeight)
        context.coordinator.setStanding(standAt)
        context.coordinator.measuring = measuring
        context.coordinator.measureFile = measureFile
        if context.coordinator.measureClearSeen != measureClear { context.coordinator.measureClearSeen = measureClear; context.coordinator.clearMeasures() }
        guard standAt == nil, context.coordinator.lastTop != topView else { return }
        context.coordinator.lastTop = topView
        SCNTransaction.begin()
        SCNTransaction.animationDuration = 0.4
        view.pointOfView = topView ? context.coordinator.topCamera : context.coordinator.orbitCamera
        SCNTransaction.commit()
    }
}

/// Plain-language summary of what was found: size, light sources (windows), doors and furniture.
/// What the sending phone wrote about a room (its .rmsc manifest), kept on import.
struct SourceManifest {
    var json: [String: Any]
    static func load(_ scan: SavedScan) -> SourceManifest? {
        guard let data = try? Data(contentsOf: scan.captureURL.appendingPathComponent("source-manifest.json")),
              let j = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return SourceManifest(json: j)
    }
    var room: [String: Any]? { json["room"] as? [String: Any] }
    var walls: [[Double]] { (room?["walls"] as? [[NSNumber]])?.map { $0.map(\.doubleValue) } ?? [] }
    var corners: [[Double]] { (room?["corners"] as? [[NSNumber]])?.map { $0.map(\.doubleValue) } ?? [] }
    var ceilingY: Double? { (json["ceilingY"] as? NSNumber)?.doubleValue }
    var floorArea: PackageFloorArea? { PackageFloorArea(json["floorArea"]) }
    var device: String? { (json["source"] as? [String: Any])?["device"] as? String }
    var platform: String? { (json["source"] as? [String: Any])?["platform"] as? String }
    var created: Date? { (json["created"] as? String).flatMap { ISO8601DateFormatter().date(from: $0) } }
}

/// The room panel for a scan without RoomPlan's model (a room from the Android app): name, size, walls, outlets,
/// objects, and how it was captured, like `RoomSummary`.
struct ImportedRoomSummary: View {
    var name: String?
    var onRename: () -> Void
    var capture: CaptureMeta
    var manifest: SourceManifest?
    var surfels: [SurfelRecord]
    var objects: [RoomObject]
    var outletCount: Int

    var body: some View {
        List {
            Section("Room") {
                Button(action: onRename) {
                    LabeledContent("Name") {
                        HStack(spacing: 6) {
                            if let name { Text(name).foregroundStyle(.secondary) } else { Text("Add a name").foregroundStyle(Color.accentColor) }
                            Image(systemName: "pencil").font(.footnote).foregroundStyle(Color.accentColor)
                        }
                    }
                }
                .tint(.primary)
                LabeledContent("Approx. size", value: sizeText)
                if let floor = manifest?.floorArea {
                    LabeledContent("Floor area", value: floor.text)
                    if floor.rooms.count >= 2 {
                        ForEach(floor.rooms.indices, id: \.self) { i in
                            LabeledContent("Room \(i + 1)", value: PackageFloorArea.text(floor.rooms[i]))
                        }
                    }
                } else if let corners = manifest?.corners, corners.count >= 3 {
                    let area = FloorOutline.area(corners.filter { $0.count >= 3 }.map { FloorPoint(x: $0[0], z: $0[2]) })
                    if area >= 0.5 { LabeledContent("Floor area", value: FloorOutline.text(area: area, rough: true)) }
                }
                if let walls = manifest?.walls, !walls.isEmpty { LabeledContent("Walls", value: "\(walls.count)") }
                if outletCount > 0 { LabeledContent("Power outlets", value: "\(outletCount)") }
            }
            Section("Capture") {
                LabeledContent("Points", value: capture.pointCount.formatted())
                LabeledContent("Photos", value: capture.keyframeCount.formatted())
                if let device = manifest?.device {
                    LabeledContent("Scanned with", value: (manifest?.platform == "android" ? "Android · " : "") + device)
                }
                if let created = manifest?.created {
                    LabeledContent("Scanned", value: created.formatted(.dateTime.day().month(.abbreviated).year().hour().minute()))
                }
            }
            let counts = objectCounts
            if !counts.isEmpty {
                Section {
                    ForEach(counts, id: \.0) { item in LabeledContent(item.0, value: "\(item.1)") }
                } header: {
                    Text("Furniture & objects")
                } footer: {
                    let removed = objects.filter { $0.removed && !$0.kind.isMarker }.count
                    if removed > 0 { Text("\(removed) removed in Objects, not counted.") }
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    /// By name (the Android app names things itself; its kinds are all "object").
    private var objectCounts: [(String, Int)] {
        var counts: [String: Int] = [:]
        for o in objects where !o.removed && !o.kind.isMarker { counts[o.kind == .other ? o.label : o.kind.label, default: 0] += 1 }
        return counts.sorted { $0.key < $1.key }.map { ($0.key, $0.value) }
    }

    /// From the room's corners when the sending phone found them, else the scan's extent; height from the ceiling.
    private var sizeText: String {
        var xs: [Double] = [], zs: [Double] = []
        if let corners = manifest?.corners, corners.count >= 3 {
            xs = corners.map { $0[0] }; zs = corners.map { $0[2] }
        } else if !surfels.isEmpty {
            // The middle 98% of the splats, so a few stray ones don't stretch it.
            let x = surfels.map { Double($0.x) }.sorted(), z = surfels.map { Double($0.z) }.sorted()
            let lo = x.count / 100, hi = x.count - 1 - x.count / 100
            xs = [x[lo], x[hi]]; zs = [z[lo], z[hi]]
        }
        guard let x0 = xs.min(), let x1 = xs.max(), let z0 = zs.min(), let z1 = zs.max() else { return "–" }
        let a = max(x1 - x0, z1 - z0), b = min(x1 - x0, z1 - z0)
        var text = String(format: "%.1f × %.1f m", a, b)
        if let h = manifest?.ceilingY, h > 1.5 { text += String(format: ", %.1f m high", h) }
        return text
    }
}

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
    /// The floor's rooms as measured (and maybe set by hand) in the 3D view; nil: RoomPlan's floor outline only.
    var floorRooms: FloorRooms? = nil
    var onImprove: (() -> Void)?
    /// Power outlets found in the room (synced from the Android app's search).
    var outletCount = 0
    /// The scan's raw recording, bytes (kept for rebuilding later), or nil.
    var recordingBytes: Int64?
    /// Scan area again: mark parts of the room and scan just those again (nil: not offered).
    var onScanArea: (() -> Void)?

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
                if let onScanArea {
                    Section {
                        Button(action: onScanArea) { Label("Scan area again", systemImage: "scope") }
                    } footer: {
                        Text("Tap the parts that came out wrong, then scan just those again. The new scan replaces them once it covers them well.")
                    }
                }
            }
            Section {
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
                if let rooms = floorRooms, rooms.rooms.count == 1, rooms.isEdited(0) {
                    let s = rooms.size(0)
                    LabeledContent("Size", value: String(format: "%.2f × %.2f m, %.2f m high (walls set by hand)", s.length, s.width,
                                                         Self.wallSize(room)?.height ?? 0))
                } else {
                    LabeledContent("Approx. size", value: sizeText)
                }
                if let rooms = floorRooms {
                    LabeledContent("Floor area", value: rooms.areaText)
                    if rooms.rooms.count >= 2 {
                        ForEach(rooms.rooms.indices, id: \.self) { i in
                            LabeledContent("Room \(i + 1)", value: rooms.roomText(i))
                        }
                    }
                } else if let floor = RoomFloor(room: room) {
                    LabeledContent("Floor area", value: floor.text)
                }
                LabeledContent("Walls", value: "\(room.walls.count)")
                LabeledContent("Windows (natural light)", value: "\(room.windows.count + missedWindows.count)")
                LabeledContent("Doors", value: "\(room.doors.count)")
                if outletCount > 0 {
                    LabeledContent("Power outlets", value: "\(outletCount)")
                }
                if !room.openings.isEmpty {
                    LabeledContent("Openings", value: "\(room.openings.count)")
                }
            } header: {
                Text("Room")
            } footer: {
                if let rooms = floorRooms {
                    Text((rooms.rooms.count >= 2 ? "Tap a room's floor in the 3D view for its own size. " : "")
                         + "A curtain or a cupboard against a wall is measured as the wall: drag the amber line on the floor to where the wall really is. "
                         + "The Walls button above the room hides the lines.")
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
                    if let recordingBytes {
                        LabeledContent("Raw recording", value: ByteCountFormatter.string(fromByteCount: recordingBytes, countStyle: .file))
                    }
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

    private var sizeText: String {
        guard let s = Self.wallSize(room) else { return "–" }
        return String(format: "%.2f × %.2f m, %.2f m high", s.long, s.short, s.height)
    }

    /// Length and width measured along the room's longest wall, so a room scanned at an angle isn't overstated.
    static func wallSize(_ room: CapturedRoom) -> (long: Double, short: Double, height: Double)? {
        guard let longest = room.walls.max(by: { $0.dimensions.x < $1.dimensions.x }) else { return nil }
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
        return (Double(max(maxA - minA, maxB - minB)), Double(min(maxA - minA, maxB - minB)), Double(height))
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

/// The line under the title: the room's size and floor area (or the picked room's), and "Improved 7 Nov at 17:05"
/// (iOS 26 and later; the Room and Capture sections say the same on every version).
private struct RoomSubtitle: ViewModifier {
    let text: String?

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *), let text {
            content.navigationSubtitle(text)
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
/// Save to Files: the system's folder picker, saving a copy of the file where the user picks.
struct SaveToFilesSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        UIDocumentPickerViewController(forExporting: [url], asCopy: true)
    }
    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}
}

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

/// The Mesh view: the scan's fused surface (mesh.bin), untextured, light grey with simple lighting and a faint
/// wireframe like the scan screen; the ceiling left out as in Photo.
enum BareMesh {
    static func node(_ scan: SavedScan) -> SCNNode? {
        guard let mesh = ScanMesh.read(from: scan.captureURL.appendingPathComponent(ScanMesh.fileName)), !mesh.triangles.isEmpty else { return nil }
        let ceiling = PhotoLayout.ceilingTriangles(mesh)
        var indices: [Int32] = []
        indices.reserveCapacity(mesh.triangles.count)
        for t in 0..<(mesh.triangles.count / 3) where !(t < ceiling.count && ceiling[t]) {
            indices += [Int32(mesh.triangles[t * 3]), Int32(mesh.triangles[t * 3 + 1]), Int32(mesh.triangles[t * 3 + 2])]
        }
        // Per-corner normals, so the grey is shaded.
        var normals = [SIMD3<Float>](repeating: .zero, count: mesh.positions.count)
        for t in stride(from: 0, to: indices.count, by: 3) {
            let a = Int(indices[t]), b = Int(indices[t + 1]), c = Int(indices[t + 2])
            let n = simd_cross(mesh.positions[b] - mesh.positions[a], mesh.positions[c] - mesh.positions[a])
            normals[a] += n; normals[b] += n; normals[c] += n
        }
        let geometry = SCNGeometry(sources: [SCNGeometrySource(vertices: mesh.positions.map { SCNVector3($0) }),
                                             SCNGeometrySource(normals: normals.map { SCNVector3(simd_length($0) > 0 ? simd_normalize($0) : SIMD3(0, 1, 0)) })],
                                   elements: [SCNGeometryElement(indices: indices, primitiveType: .triangles)])
        let m = SCNMaterial()
        m.diffuse.contents = UIColor(white: 0.78, alpha: 1)
        m.lightingModel = .lambert
        m.isDoubleSided = true
        geometry.materials = [m]
        let node = SCNNode(geometry: geometry)
        node.name = "bareMesh"
        let wire = SCNNode()
        wire.name = "bareMeshWire"
        node.addChildNode(wire)
        refreshWire(node)
        return node
    }

    /// The wireframe follows the surface (also after removed objects were cut out of it).
    static func refreshWire(_ node: SCNNode) {
        guard let surface = node.geometry, let wire = node.childNode(withName: "bareMeshWire", recursively: false) else { return }
        let g = SCNGeometry(sources: surface.sources, elements: surface.elements)
        let m = SCNMaterial()
        m.diffuse.contents = UIColor(white: 1, alpha: 0.22)
        m.lightingModel = .constant
        m.fillMode = .lines
        m.isDoubleSided = true
        m.writesToDepthBuffer = false
        g.materials = [m]
        wire.geometry = g
    }
}

/// "Nothing here" behind the 3D room (rk 9 Nov): 45° grey zebra stripes, fixed on screen, so holes in the scan can't
/// be mistaken for black things. One place to tune: colours and stripe width.
struct ZebraBackground: View {
    static let light = Color(red: 0x6b / 255, green: 0x6b / 255, blue: 0x6b / 255)
    static let dark = Color(red: 0x3a / 255, green: 0x3a / 255, blue: 0x3a / 255)
    /// Stripe width, points.
    static let width: CGFloat = 11

    var body: some View {
        Canvas { ctx, size in
            ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Self.dark))
            let step = Self.width * 2 * 1.41421 // across the diagonal
            var path = Path()
            var x = -size.height
            while x < size.width {
                path.move(to: CGPoint(x: x, y: size.height))
                path.addLine(to: CGPoint(x: x + size.height, y: 0))
                path.addLine(to: CGPoint(x: x + size.height + step / 2, y: 0))
                path.addLine(to: CGPoint(x: x + step / 2, y: size.height))
                path.closeSubpath()
                x += step
            }
            ctx.fill(path, with: .color(Self.light))
        }
        .ignoresSafeArea()
    }
}

/// "Share link copied" after a link is made.
private struct LinkMadeAlert: ViewModifier {
    @Binding var link: URL?
    func body(content: Content) -> some View {
        content.alert("Share link copied", isPresented: Binding(get: { link != nil }, set: { if !$0 { link = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("\(link?.absoluteString ?? "")\n\nAnyone with the link can look around the room for 30 days. When the PC makes the full version, the same link shows it.")
        }
    }
}

/// Shown instead of the 3D room while the photo-real preview runs (the room is let go: Brush needs the memory).
private struct RoomPausedForPreview: View {
    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: "cube.transparent").font(.largeTitle).foregroundStyle(.secondary)
            Text("Making the photo-real room. The 3D view comes back when it's done.")
                .font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
        }
        .padding(.top, 120).padding(.horizontal, 24)
    }
}

/// Lets the room go when the photo-real preview starts and loads it again when it's done.
private struct ReleaseDuringPreview: ViewModifier {
    let busy: Bool
    let release: () -> Void
    let reload: () async -> Void
    func body(content: Content) -> some View {
        content.onChange(of: busy) { _, now in
            if now { release() } else { Task { await reload() } }
        }
    }
}

/// One question before the photo-real preview starts (rk 12 Nov 22:41): it takes minutes, warms the phone, and the app
/// has to stay open. Time from the scan's plan (SplatPacing.plan) at the phone's measured pace (about 12-25 steps/s).
private struct ConfirmPhotoReal: ViewModifier {
    @Binding var isPresented: Bool
    let scan: SavedScan
    func body(content: Content) -> some View {
        content.alert("Make the photo-real room?", isPresented: $isPresented) {
            Button("Start") {
                // the app becomes just the photo-real screen right away (it needs the memory; rk 13 Nov)
                ProcessingQueue.shared.photoRealScan = scan.name
                ProcessingQueue.shared.add(scan, .splat)
            }
            Button("Not now", role: .cancel) {}
        } message: {
            Text(Self.message(scan))
        }
    }
    static func message(_ scan: SavedScan) -> String {
        let steps = Double(SplatPreview.plan(for: scan).steps)
        let low = max(2, Int((steps / 25 / 60).rounded(.up))), high = max(low + 1, Int((steps / 12 / 60).rounded(.up)))
        let resume = FileManager.default.fileExists(atPath: SplatPreview.folder(scan).appendingPathComponent("checkpoint.json").path)
        return "It takes about \(low)-\(high) minutes on this phone\(resume ? " (it carries on from where it stopped)" : ""). "
            + "The phone gets warm, and RoomScape needs to stay open on screen until it's done. Starting a new scan pauses it."
    }
}

/// The room view's orbit camera for the Photo-real tab: where it looks from (handed to the splat view) and setting it
/// back when the tab is left. Only the orbit view: top view and standing in the room are left alone.
final class RoomCameraLink {
    struct Pose { var cam: [Float]; var look: [Float]; var fov: Float }
    weak var coordinator: RoomSceneView.Coordinator?

    func pose() -> Pose? {
        guard let c = coordinator, let pov = c.view?.pointOfView else { return nil }
        let p = pov.simdWorldPosition
        let fov = Float(pov.camera?.fieldOfView ?? 60)
        if pov === c.orbitCamera, c.standing == nil {
            let t = c.orbitTarget
            return Pose(cam: [p.x, p.y, p.z], look: [t.x, t.y, t.z], fov: fov)
        }
        let f = pov.simdWorldFront   // top view or standing: two metres ahead
        let t = p + 2 * f
        return Pose(cam: [p.x, p.y, p.z], look: [t.x, t.y, t.z], fov: fov)
    }

    func set(cam: [Float], look: [Float]) {
        guard let c = coordinator, cam.count == 3, look.count == 3, c.standing == nil,
              c.view?.pointOfView === c.orbitCamera else { return }
        let e = SIMD3<Float>(cam[0], cam[1], cam[2]), t = SIMD3<Float>(look[0], look[1], look[2])
        guard simd_length(e - t) > 0.05, simd_length(e - t) < 200 else { return }
        c.orbitTarget = t
        c.orbitCamera.simdWorldPosition = e
        c.orbitCamera.simdLook(at: t, up: SIMD3(0, 1, 0), localFront: SIMD3(0, 0, -1))
    }
}
