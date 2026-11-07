import SwiftUI
import SceneKit
import RoomPlan
import simd

/// Shows a saved scan: as splats (the captured points as discs on their surfaces, inside RoomPlan's walls and floor)
/// when the scan has points, or as RoomPlan's own 3D model. Below it, a summary of what RoomPlan found.
struct ResultView: View {
    enum Style: Hashable { case splats, model }

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
    @State private var finding: String?
    @State private var findError: String?

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
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.black)
            // The 3D area is always black, so its controls use dark styling even when the phone is in light mode.
            .environment(\.colorScheme, .dark)

            if objectsOn, let shell {
                ObjectsPanel(objects: $objects, selected: $selectedObject, floorY: shell.floorY,
                             claudeChecked: claudeChecked, finding: finding, error: findError, onFind: findObjects)
                    .frame(maxHeight: 340)
            } else if lightingOn, lighting != nil {
                LightingPanel(setup: Binding(get: { lighting! }, set: { lighting = $0 }))
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
        .onChange(of: lighting) { _, setup in
            if lightingOn { lightingScene?.apply(setup) }
        }
        .onChange(of: objects) { _, objects in
            objectsScene?.apply(objects, selected: selectedObject)
            modelObjects?.apply(objects)
            RoomObjects.save(ObjectsFile(claudeChecked: claudeChecked, objects: objects), for: scan)
        }
        .onChange(of: selectedObject) { _, id in
            objectsScene?.apply(objects, selected: id)
        }
        .onChange(of: style) { _, newStyle in
            // The preview lights the splats and objects move splats; RoomPlan's model has neither.
            if newStyle == .model, lightingOn { toggleLighting() }
            if newStyle == .model, objectsOn { toggleObjects() }
            modelGroup?.isHidden = newStyle == .splats
            splatGroup?.isHidden = newStyle == .model
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
            let built = await Task.detached(priority: .userInitiated) { () -> ([SurfelRecord], ObjectsScene.Built)? in
                guard let surfels = ScanStore.loadSurfels(scan, room: room), !surfels.isEmpty else { return nil }
                return (surfels, ObjectsScene.build(surfels, objects: objects, floorY: shell.floorY))
            }.value
            buildingSplats = false
            guard let (surfels, parts) = built else { return }
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
            style = .splats
            #if DEBUG
            // UI checks in the Simulator: -openPanel lighting | objects
            let args = ProcessInfo.processInfo.arguments
            if let i = args.firstIndex(of: "-openPanel"), i + 1 < args.count {
                if args[i + 1] == "lighting" { toggleLighting() }
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
            #endif
        }
    }

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
                Text("Splats").tag(Style.splats)
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
            style = .splats
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

    /// After a Claude search: split the splats for the new set of objects and show them.
    private func applyFound(_ found: [RoomObject], previousCount: Int, surfels: [SurfelRecord], floorY: Float) async {
        finding = "Placing what it found…"
        let parts = await Task.detached(priority: .userInitiated) {
            ObjectsScene.build(surfels, objects: found, floorY: floorY)
        }.value
        objectsScene?.install(parts, objects: found)
        if let splatGroup { lightingScene?.reregister(splatGroup) }
        if lightingOn { lightingScene?.apply(lighting) }
        claudeChecked = true
        objects = found
        finding = nil
        if found.count == previousCount { findError = "Claude found nothing new." }
    }

    private func toggleLighting() {
        lightingOn.toggle()
        guard lightingOn else {
            lightingScene?.apply(nil)
            return
        }
        style = .splats
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
