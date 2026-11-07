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
    @State private var scene: SCNScene?
    @State private var room: CapturedRoom?
    @State private var capture: CaptureMeta?
    /// Windows found from the photos and LiDAR (see `WindowFinder`), including ones RoomPlan also found.
    @State private var foundWindows: [WindowFinder.Found] = []
    @State private var topView = false
    @State private var loadError: String?
    @State private var style: Style = .model
    @State private var modelGroup: SCNNode?
    @State private var splatGroup: SCNNode?
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
    @State private var planningShot = false
    @State private var planError: String?

    var body: some View {
        VStack(spacing: 0) {
            ZStack(alignment: .top) {
                if let scene {
                    RoomSceneView(scene: scene, topView: topView,
                                  floorY: (lightingOn || objectsOn) ? shell?.floorY : nil,
                                  onFloorTap: floorTapped)
                } else if let loadError {
                    Text(loadError).padding()
                } else {
                    ProgressView()
                }
                if lightingOn, let scene, let lighting {
                    // Under the controls row, so a large view never covers the buttons.
                    ShotPreviewInset(scene: scene, setup: lighting, large: $shotLarge)
                        .padding(.horizontal, shotLarge ? 16 : 10)
                        .padding(.top, shotLarge ? 64 : 0)
                        .padding(.bottom, shotLarge ? 16 : 10)
                        .frame(maxWidth: .infinity, maxHeight: .infinity,
                               alignment: shotLarge ? .center : .bottomTrailing)
                }
                // Must fit a 375-pt-wide phone: compact controls in one row, or two rows if they still don't fit
                // (large text). A row wider than the screen would also push the panel below off both edges.
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) {
                        modeButtons
                        Spacer(minLength: 4)
                        viewPickers
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(spacing: 8) { modeButtons }
                        HStack(spacing: 8) { viewPickers }
                    }
                }
                .padding(10)
            }
            .overlay(alignment: .bottom) {
                if let training {
                    TrainingStatus(run: training)
                        .padding(10)
                        .allowsHitTesting(false)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.black)
            // The 3D area is always black, so its controls use dark styling even when the phone is in light mode.
            .environment(\.colorScheme, .dark)

            if objectsOn, let shell {
                ObjectsPanel(objects: $objects, selected: $selectedObject, floorY: shell.floorY,
                             claudeChecked: claudeChecked, finding: finding, error: findError, onFind: findObjects)
                    .frame(maxHeight: 340)
            } else if lightingOn, lighting != nil {
                LightingPanel(setup: Binding(get: { lighting! }, set: { lighting = $0 }),
                              planning: planningShot, planError: planError, onPlan: planShot)
                    .frame(maxHeight: 340)
            } else if let room {
                RoomSummary(room: room, capture: capture, foundWindows: foundWindows, objects: objects)
                    .frame(maxHeight: 260)
            }
        }
        .navigationTitle(scan.date.formatted(.dateTime.day().month(.abbreviated).hour().minute()))
        .navigationBarTitleDisplayMode(.inline)
        // Black bar above the black 3D view, with light title, buttons and status bar in light mode too.
        .toolbarBackground(Color.black, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .toolbar {
            if let room {
                NavigationLink(destination: PlanningLaunchView(room: room, scan: scan, objects: objects)) {
                    Label("Storyboard", systemImage: "rectangle.stack")
                }
                NavigationLink(destination: ShootPlannerView(
                    room: room, extraWindowAreas: foundWindows.filter { !$0.matchesRoomPlan }.map(\.area),
                    objects: objects)) {
                    Label("Plan a Shoot", systemImage: "sparkles")
                }
            }
            ShareLink(item: scan.modelURL)
        }
        .onDisappear { training?.pause() }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { training?.pause() }
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
            modelGroup?.isHidden = newStyle != .model
            splatGroup?.isHidden = newStyle == .model
            if let splatGroup { SplatScene.setMeshLook(newStyle != .photo, in: splatGroup) }
        }
        .task {
            // Decoding the room and loading the USDZ can take a few seconds, so keep it off the main thread.
            let scan = scan
            capture = await Task.detached(priority: .utility) { CaptureStore.loadMeta(scan.captureURL) }.value
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

            // Splats, when the scan has points: built in the background (cached after the first time), then shown
            // instead of RoomPlan's boxes. The Splats / Model switch flips between the two.
            guard let room = loaded.0, let scene, (capture?.pointCount ?? 0) > 0 else { return }
            buildingSplats = true
            let shell = RoomShell(room: room)
            // Objects: what was saved (with the user's edits), else RoomPlan's furniture.
            let saved = RoomObjects.load(scan)
            let objects = saved?.objects ?? RoomObjects.fromRoomPlan(room)
            // Shown: the trained splats when the scan was trained before, else the raw ones (also the training's start).
            let built = await Task.detached(priority: .userInitiated) { () -> ([SurfelRecord], [SurfelRecord], ObjectsScene.Built)? in
                guard let base = ScanStore.loadSurfels(scan, room: room), !base.isEmpty else { return nil }
                let shown = ScanStore.loadTrainedSurfels(scan) ?? base
                return (base, shown, ObjectsScene.build(shown, objects: objects, floorY: shell.floorY))
            }.value
            buildingSplats = false
            guard let (base, surfels, parts) = built else { return }
            let splats = SCNNode()
            splats.addChildNode(SplatScene.shellNode(shell))
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
            style = .mesh
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
                }
                if args[i + 1] == "objects" { toggleObjects() }
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
    @ViewBuilder private var modeButtons: some View {
        if splatGroup != nil {
            Button {
                toggleLighting()
            } label: {
                Image(systemName: lightingOn ? "lightbulb.fill" : "lightbulb")
            }
            .buttonStyle(.bordered)
            .tint(lightingOn ? .yellow : nil)
            .accessibilityLabel(lightingOn ? "Turn off the lighting preview" : "Lighting preview")
            Button {
                toggleObjects()
            } label: {
                Image(systemName: objectsOn ? "cube.fill" : "cube")
            }
            .buttonStyle(.bordered)
            .tint(objectsOn ? .yellow : nil)
            .accessibilityLabel(objectsOn ? "Close objects" : "Objects in the room")
            if let training {
                TrainButton(run: training) { style = .photo } // training sharpens the colours
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

    private func floorTapped(_ p: SIMD3<Float>) {
        if objectsOn {
            // Move the selected object so its centre stands over the tapped spot.
            guard let id = selectedObject, let i = objects.firstIndex(where: { $0.id == id }),
                  !objects[i].kind.isMarker, !objects[i].removed else { return }
            objects[i].offset = SIMD3(p.x - objects[i].center.x, 0, p.z - objects[i].center.z)
        } else if lightingOn {
            lighting?.subjectX = p.x
            lighting?.subjectZ = p.z
        }
    }

    private func toggleObjects() {
        objectsOn.toggle()
        if objectsOn {
            if style == .model { style = .mesh }
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
            let parts = await Task.detached(priority: .userInitiated) {
                ObjectsScene.build(splats, objects: current, floorY: shell.floorY)
            }.value
            objectsScene?.install(parts, objects: current)
            objectsScene?.apply(objects, selected: selectedObject)
            surfels = splats
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
    private func planShot(_ scene: SceneType, wish: String) {
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
        if style == .model { style = .mesh }
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
    /// When set, a tap on the 3D view reports where it hits the floor plane at this height.
    var floorY: Float? = nil
    var onFloorTap: (SIMD3<Float>) -> Void = { _ in }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject {
        var orbitCamera = SCNNode()
        var topCamera = SCNNode()
        var lastTop: Bool?
        var floorY: Float?
        var onFloorTap: (SIMD3<Float>) -> Void = { _ in }

        /// Intersects the tapped ray with the floor plane. (SceneKit's hit test can't see the splats: their discs
        /// are opened up in the shader, so on the CPU they have no area.)
        @objc func tapped(_ gesture: UITapGestureRecognizer) {
            guard let floorY, let view = gesture.view as? SCNView else { return }
            let p = gesture.location(in: view)
            let near = view.unprojectPoint(SCNVector3(Float(p.x), Float(p.y), 0))
            let far = view.unprojectPoint(SCNVector3(Float(p.x), Float(p.y), 1))
            let a = SIMD3<Float>(near.x, near.y, near.z), b = SIMD3<Float>(far.x, far.y, far.z)
            let dy = b.y - a.y
            guard abs(dy) > 1e-5 else { return }
            let t = (floorY - a.y) / dy
            guard t > 0, t < 1 else { return }
            onFloorTap(a + (b - a) * t)
        }
    }

    func makeUIView(context: Context) -> SCNView {
        let view = SCNView()
        view.scene = scene
        view.allowsCameraControl = true
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
        top.eulerAngles = SCNVector3(-Float.pi / 2, 0, 0)
        scene.rootNode.addChildNode(top)

        Self.addAmbientLight(to: scene)

        view.defaultCameraController.target = center
        view.pointOfView = orbit
        context.coordinator.lastTop = false
        view.addGestureRecognizer(UITapGestureRecognizer(target: context.coordinator,
                                                         action: #selector(Coordinator.tapped(_:))))
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
        guard context.coordinator.lastTop != topView else { return }
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
    var capture: CaptureMeta?
    var foundWindows: [WindowFinder.Found] = []
    /// The Objects panel's list, with the user's edits. Empty for scans without points: then RoomPlan's own objects.
    var objects: [RoomObject] = []

    private var missedWindows: [WindowFinder.Found] { foundWindows.filter { !$0.matchesRoomPlan } }

    var body: some View {
        List {
            Section("Room") {
                LabeledContent("Approx. size", value: sizeText)
                LabeledContent("Walls", value: "\(room.walls.count)")
                LabeledContent("Windows (natural light)", value: "\(room.windows.count + missedWindows.count)")
                LabeledContent("Doors", value: "\(room.doors.count)")
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
                Section("Capture") {
                    LabeledContent("Points", value: capture.pointCount.formatted())
                    LabeledContent("Photos", value: capture.keyframeCount.formatted())
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
        guard !room.walls.isEmpty else { return "–" }
        var minX = Float.greatestFiniteMagnitude, maxX = -Float.greatestFiniteMagnitude
        var minZ = Float.greatestFiniteMagnitude, maxZ = -Float.greatestFiniteMagnitude
        var height: Float = 0
        for wall in room.walls {
            let t = wall.transform
            let c = simd_float3(t.columns.3.x, t.columns.3.y, t.columns.3.z)
            let r = simd_float3(t.columns.0.x, t.columns.0.y, t.columns.0.z)
            let half = wall.dimensions.x / 2
            for p in [c + r * half, c - r * half] {
                minX = min(minX, p.x); maxX = max(maxX, p.x)
                minZ = min(minZ, p.z); maxZ = max(maxZ, p.z)
            }
            height = max(height, wall.dimensions.y)
        }
        return String(format: "%.1f × %.1f m, %.1f m high", maxX - minX, maxZ - minZ, height)
    }
}

/// Starts splat training, or pauses it (task 6).
private struct TrainButton: View {
    @ObservedObject var run: SplatTrainingRun
    var onStart: () -> Void

    var body: some View {
        Button {
            if !run.isTraining { onStart() }
            run.toggle()
        } label: {
            Image(systemName: run.isTraining ? "pause.fill" : "wand.and.stars")
        }
        .buttonStyle(.bordered)
        .tint(run.isTraining ? .yellow : nil)
        .accessibilityLabel(run.isTraining ? "Pause training" : "Train the splats against the photos")
    }
}

/// What the training is doing, over the bottom of the 3D view.
private struct TrainingStatus: View {
    @ObservedObject var run: SplatTrainingRun

    var body: some View {
        Group {
            switch run.state {
            case .idle:
                EmptyView()
            case .preparing:
                Label("Getting the photos ready…", systemImage: "photo.stack")
            case .training:
                Label(run.progress.isEmpty ? "Training the splats…" : run.progress, systemImage: "wand.and.stars")
            case .paused:
                Label(run.progress + ". Saved.", systemImage: "pause.circle")
            case .done:
                Label(run.progress + ". Saved.", systemImage: "checkmark.circle")
            case .failed(let message):
                Label(message, systemImage: "exclamationmark.triangle")
            }
        }
        .font(.caption.weight(.medium))
        .padding(.horizontal, 10).padding(.vertical, 6)
        .background(.regularMaterial, in: Capsule())
    }
}
