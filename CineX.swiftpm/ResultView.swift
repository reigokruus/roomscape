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

    var body: some View {
        VStack(spacing: 0) {
            ZStack(alignment: .top) {
                if let scene {
                    RoomSceneView(scene: scene, topView: topView)
                } else if let loadError {
                    Text(loadError).padding()
                } else {
                    ProgressView()
                }
                HStack {
                    if splatGroup != nil {
                        Picker("Style", selection: $style) {
                            Text("Splats").tag(Style.splats)
                            Text("Model").tag(Style.model)
                        }
                        .pickerStyle(.segmented)
                        .frame(width: 160)
                    } else if buildingSplats {
                        Label("Building splats…", systemImage: "circle.dotted")
                            .font(.caption.weight(.medium))
                            .padding(.horizontal, 10).padding(.vertical, 6)
                            .background(.regularMaterial, in: Capsule())
                    }
                    Spacer()
                    Picker("View", selection: $topView) {
                        Text("3D").tag(false)
                        Text("Top").tag(true)
                    }
                    .pickerStyle(.segmented)
                    .frame(width: 120)
                }
                .padding(10)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.black)
            // The 3D area is always black, so its controls use dark styling even when the phone is in light mode.
            .environment(\.colorScheme, .dark)

            if let room {
                RoomSummary(room: room, capture: capture, foundWindows: foundWindows)
                    .frame(maxHeight: 260)
            }
        }
        .navigationTitle(scan.date.formatted(date: .abbreviated, time: .shortened))
        .navigationBarTitleDisplayMode(.inline)
        // Black bar above the black 3D view, with light title, buttons and status bar in light mode too.
        .toolbarBackground(Color.black, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .toolbar {
            if let room {
                NavigationLink(destination: PlanningLaunchView(room: room, scan: scan)) {
                    Label("Storyboard", systemImage: "rectangle.stack")
                }
                NavigationLink(destination: ShootPlannerView(
                    room: room, extraWindowAreas: foundWindows.filter { !$0.matchesRoomPlan }.map(\.area))) {
                    Label("Plan a Shoot", systemImage: "sparkles")
                }
            }
            ShareLink(item: scan.modelURL)
        }
        .onChange(of: style) { _, newStyle in
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
            let splats = await Task.detached(priority: .userInitiated) { () -> SCNNode? in
                guard let surfels = ScanStore.loadSurfels(scan, room: room), !surfels.isEmpty else { return nil }
                let node = SCNNode()
                node.addChildNode(SplatScene.shellNode(RoomShell(room: room)))
                node.addChildNode(SplatScene.splatNode(surfels))
                return node
            }.value
            buildingSplats = false
            guard let splats else { return }
            splats.isHidden = true
            scene.rootNode.addChildNode(splats)
            splatGroup = splats
            style = .splats
        }
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

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator {
        var orbitCamera = SCNNode()
        var topCamera = SCNNode()
        var lastTop: Bool?
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
            if !room.objects.isEmpty {
                Section("Furniture & objects") {
                    ForEach(objectCounts, id: \.0) { item in
                        LabeledContent(item.0.capitalized, value: "\(item.1)")
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    private var objectCounts: [(String, Int)] {
        var counts: [String: Int] = [:]
        for object in room.objects {
            counts[String(describing: object.category), default: 0] += 1
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
