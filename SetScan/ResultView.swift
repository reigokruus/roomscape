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
            ShareLink(item: scan.modelURL)
        }
        .task {
            room = ScanStore.loadRoom(scan)
            do {
                let s = try SCNScene(url: scan.modelURL, options: nil)
                Self.colorize(s.rootNode)
                scene = s
            } catch {
                loadError = "Could not open the 3D model: \(error.localizedDescription)"
            }
        }
    }

    /// Tints RoomPlan's grey boxes by type so windows (natural light) and doors stand out.
    static func colorize(_ node: SCNNode) {
        let name = (node.name ?? "").lowercased()
        var color: UIColor?
        if name.contains("window") { color = UIColor(red: 0.45, green: 0.75, blue: 1.0, alpha: 0.85) }
        else if name.contains("door") { color = UIColor(red: 0.65, green: 0.45, blue: 0.3, alpha: 1) }
        else if name.contains("opening") { color = UIColor(red: 0.5, green: 0.9, blue: 0.6, alpha: 0.7) }
        else if name.contains("wall") { color = UIColor(white: 0.85, alpha: 1) }
        else if name.contains("floor") { color = UIColor(white: 0.45, alpha: 1) }
        if let color {
            applyColor(color, to: node)
        } else {
            node.childNodes.forEach(colorize)
        }
    }

    private static func applyColor(_ color: UIColor, to node: SCNNode) {
        if let geometry = node.geometry {
            let m = SCNMaterial()
            m.diffuse.contents = color
            m.lightingModel = .physicallyBased
            m.roughness.contents = 0.8
            m.isDoubleSided = true
            node.geometry = geometry.copy() as? SCNGeometry
            node.geometry?.materials = [m]
        }
        node.childNodes.forEach { applyColor(color, to: $0) }
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
                    ForEach(objectCounts, id: \.0) { name, count in
                        LabeledContent(name.capitalized, value: "\(count)")
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
