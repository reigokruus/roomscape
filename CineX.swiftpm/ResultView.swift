import SwiftUI
import SceneKit
import RoomPlan
import simd

/// Shows a saved scan as a 3D model with a summary of what RoomPlan found.
struct ResultView: View {
    let scan: SavedScan
    @State private var scene: SCNScene?
    @State private var room: CapturedRoom?
    @State private var topView = false
    @State private var loadError: String?

    var body: some View {
        VStack(spacing: 0) {
            ZStack(alignment: .topTrailing) {
                if let scene {
                    RoomSceneView(scene: scene, topView: topView)
                        .background(Color.black)
                } else if let loadError {
                    Text(loadError).padding()
                } else {
                    ProgressView()
                }
                Picker("View", selection: $topView) {
                    Text("3D").tag(false)
                    Text("Top").tag(true)
                }
                .pickerStyle(.segmented)
                .frame(width: 140)
                .padding(10)
            }
            .frame(maxHeight: .infinity)

            if let room {
                RoomSummary(room: room)
                    .frame(maxHeight: 260)
            }
        }
        .navigationTitle(scan.date.formatted(date: .abbreviated, time: .shortened))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let room {
                NavigationLink(destination: ShootPlannerView(room: room)) {
                    Label("Plan a Shoot", systemImage: "sparkles")
                }
            }
            ShareLink(item: scan.modelURL)
        }
        .task {
            // Decoding the room and loading the USDZ can take a few seconds, so keep it off the main thread.
            let scan = scan
            let loaded = await Task.detached(priority: .userInitiated) { () -> (CapturedRoom?, Result<SCNScene, Error>) in
                let room = ScanStore.loadRoom(scan)
                let result = Result { () throws -> SCNScene in
                    let s = try SCNScene(url: scan.modelURL, options: nil)
                    ResultView.colorize(s.rootNode)
                    return s
                }
                return (room, result)
            }.value
            room = loaded.0
            switch loaded.1 {
            case .success(let s): scene = s
            case .failure(let error): loadError = "Could not open the 3D model: \(error.localizedDescription)"
            }
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

        let (minB, maxB) = scene.rootNode.boundingBox
        let center = SCNVector3((minB.x + maxB.x) / 2, (minB.y + maxB.y) / 2, (minB.z + maxB.z) / 2)
        let size = max(maxB.x - minB.x, max(maxB.y - minB.y, maxB.z - minB.z))
        let extent = Float(size.isFinite && size > 0 ? size : 5)

        func makeCamera() -> SCNCamera {
            let cam = SCNCamera()
            cam.zNear = 0.05
            cam.zFar = 500
            cam.fieldOfView = 60
            return cam
        }

        let orbit = context.coordinator.orbitCamera
        orbit.camera = makeCamera()
        orbit.position = SCNVector3(center.x + extent * 0.9, center.y + extent * 0.8, center.z + extent * 0.9)
        orbit.look(at: center)
        scene.rootNode.addChildNode(orbit)

        let top = context.coordinator.topCamera
        top.camera = makeCamera()
        top.position = SCNVector3(center.x, center.y + extent * 1.4, center.z)
        top.eulerAngles = SCNVector3(-Float.pi / 2, 0, 0)
        scene.rootNode.addChildNode(top)

        // Soft ambient light so the model reads well from every side.
        let ambient = SCNNode()
        ambient.light = SCNLight()
        ambient.light?.type = .ambient
        ambient.light?.intensity = 500
        scene.rootNode.addChildNode(ambient)

        view.defaultCameraController.target = center
        view.pointOfView = orbit
        context.coordinator.lastTop = false
        return view
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

    var body: some View {
        List {
            Section("Room") {
                LabeledContent("Approx. size", value: sizeText)
                LabeledContent("Walls", value: "\(room.walls.count)")
                LabeledContent("Windows (natural light)", value: "\(room.windows.count)")
                LabeledContent("Doors", value: "\(room.doors.count)")
                if !room.openings.isEmpty {
                    LabeledContent("Openings", value: "\(room.openings.count)")
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
