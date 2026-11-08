import SceneKit
import SwiftUI
import simd

/// The scanned room for the storyboard's frame preview: the scan's splats (photo colours or the white mesh) and
/// RoomPlan's shell, plus simple stand-ins for what the view plans (the person, light stands, other kit).
///
/// Scanned furniture is the real scan: each movable object's splats are matched to the view's scanned item of the
/// same size nearest to it, and moved and turned to where that item stands in this view. Objects the view no longer
/// has are hidden; a scanned item with no splats to match (resized in the inspector, say) gets a grey box instead.
/// Loaded once per editor, on first use (the splats come from the scan's cache, so it's quick after the result
/// screen has shown them).
@MainActor
final class StoryboardScanScene: ObservableObject {
    enum State { case idle, loading, ready, unavailable }
    @Published private(set) var state = State.idle

    let scene = SCNScene()
    let cameraNode = SCNNode()
    private let splatRoot = SCNNode()
    private let propsRoot = SCNNode()
    private var objects: [RoomObject] = []
    private var objectNodes: [UUID: SCNNode] = [:]

    init() {
        let camera = SCNCamera()
        camera.zNear = 0.02
        camera.zFar = 200
        cameraNode.camera = camera
        scene.rootNode.addChildNode(splatRoot)
        scene.rootNode.addChildNode(propsRoot)
        scene.rootNode.addChildNode(cameraNode)
        // Soft light for the room shell (where the scan has gaps) and the stand-ins; the splats are unlit.
        let ambient = SCNNode()
        ambient.light = SCNLight()
        ambient.light?.type = .ambient
        ambient.light?.intensity = 600
        scene.rootNode.addChildNode(ambient)
        let key = SCNNode()
        key.light = SCNLight()
        key.light?.type = .directional
        key.light?.intensity = 500
        key.eulerAngles = SCNVector3(-Float.pi / 3, Float.pi / 6, 0)
        scene.rootNode.addChildNode(key)
    }

    func load(scanName: String?) async {
        guard state == .idle else { return }
        state = .loading
        guard let scanName, let scan = ScanStore.list().first(where: { $0.name == scanName }) else {
            state = .unavailable
            return
        }
        let loaded = await Task.detached(priority: .userInitiated) { () -> (RoomShell, [RoomObject], ObjectsScene.Built, SplatTidy.ShellColors)? in
            guard let room = ScanStore.loadRoom(scan) else { return nil }
            let shell = RoomShell(room: room)
            let objects = RoomObjects.load(scan)?.objects ?? RoomObjects.fromRoomPlan(room)
            guard let base = ScanStore.loadSurfels(scan, room: room), !base.isEmpty else { return nil }
            let tidy = SplatTidy.tidy(ScanStore.loadTrainedSurfels(scan) ?? base, shell: shell)
            return (shell, objects, ObjectsScene.build(tidy.surfels, objects: objects, floorY: shell.floorY), tidy.colors)
        }.value
        guard let (shell, objects, built, colors) = loaded else {
            state = .unavailable
            return
        }
        splatRoot.addChildNode(SplatScene.shellNode(shell, colors: colors))
        if let ceiling = Self.ceiling(shell) { splatRoot.addChildNode(ceiling) }
        splatRoot.addChildNode(built.room)
        for node in built.objects.values { splatRoot.addChildNode(node) }
        self.objects = objects
        objectNodes = built.objects
        state = .ready
    }

    /// Shows `shot` from its camera: scanned furniture where the view has it, stand-ins for planned things.
    func show(_ shot: PlanShot, meshLook: Bool) {
        SplatScene.setMeshLook(meshLook, in: splatRoot)
        placeCamera(shot.camera)

        var scanned = shot.items.filter { $0.source == .scanned }
        for o in objects where !o.kind.isMarker {
            guard let node = objectNodes[o.id] else { continue }
            let size = simd_max(o.halfExtents * 2, SIMD3(repeating: 0.02))
            let here = o.center + o.offset
            let match = scanned.indices.filter { i in
                let s = scanned[i].size
                return abs(s.x - Double(size.x)) < 0.02 && abs(s.y - Double(size.y)) < 0.02 && abs(s.z - Double(size.z)) < 0.02
            }.min { a, b in
                distance(scanned[a].position, here) < distance(scanned[b].position, here)
            }
            guard let i = match else {
                node.isHidden = true
                continue
            }
            let item = scanned.remove(at: i)
            node.isHidden = false
            let center = SIMD3(Float(item.position.x), Float(item.position.y) + o.halfExtents.y, Float(item.position.z))
            var to = matrix_identity_float4x4; to.columns.3 = SIMD4(center, 1)
            var from = matrix_identity_float4x4; from.columns.3 = SIMD4(-o.center, 1)
            node.simdTransform = to * simd_float4x4(simd_quatf(angle: Float(item.yaw) - o.yaw, axis: SIMD3(0, 1, 0))) * from
        }

        propsRoot.childNodes.forEach { $0.removeFromParentNode() }
        let target = shot.items.first { $0.id == shot.targetID }
        for item in scanned { propsRoot.addChildNode(Self.box(item, color: UIColor(white: 0.6, alpha: 0.55))) }
        for item in shot.items where item.source == .planned {
            switch item.kind {
            case .subject: propsRoot.addChildNode(Self.person(item))
            case .light: propsRoot.addChildNode(Self.light(item, aim: target, camera: shot.camera))
            case .camera where item.contains(shot.camera.position): continue // the camera we look through
            default: propsRoot.addChildNode(Self.box(item, color: Self.color(item.kind)))
            }
        }
    }

    /// Same camera as the storyboard: level, turned by `yaw`, keeping the sensor's aspect. Nothing closer than
    /// 30 cm is drawn: splats right at the lens (a camera against a wall) otherwise blow up into huge blobs, and no
    /// lens focuses that close anyway.
    private func placeCamera(_ c: ShotCamera) {
        cameraNode.simdPosition = SIMD3(Float(c.position.x), Float(c.position.y), Float(c.position.z))
        cameraNode.eulerAngles = SCNVector3(0, Float(c.yaw), 0)
        let near = 0.3, far = 200.0
        var m = SCNMatrix4Identity
        m.m11 = Float(2 * c.focalMM / c.sensorWidthMM)
        m.m22 = Float(2 * c.focalMM / c.sensorHeightMM)
        m.m33 = Float(-(far + near) / (far - near)); m.m34 = -1
        m.m43 = Float(-2 * far * near / (far - near)); m.m44 = 0
        cameraNode.camera?.projectionTransform = m
    }

    private func distance(_ p: PlanPoint, _ q: SIMD3<Float>) -> Float {
        hypot(Float(p.x) - q.x, Float(p.z) - q.z)
    }

    /// A plain ceiling: one plane over the whole room (the walls hide what's beyond them), at the walls' typical
    /// height. The 3D view leaves it off (looking in from above), but through the camera an open top reads as a
    /// black band. A fan over the floor outline left gaps in rooms with a notch.
    private static func ceiling(_ shell: RoomShell) -> SCNNode? {
        let outline = shell.floorOutline
        guard outline.count >= 3 else { return nil }
        let top = shell.ceilingY ?? shell.floorY + 2.5
        let xs = outline.map(\.x), zs = outline.map(\.z)
        let plane = SCNPlane(width: CGFloat(xs.max()! - xs.min()! + 2), height: CGFloat(zs.max()! - zs.min()! + 2))
        let m = material(UIColor(white: 0.85, alpha: 1))
        m.isDoubleSided = true
        plane.materials = [m]
        let node = SCNNode(geometry: plane)
        node.simdPosition = SIMD3((xs.min()! + xs.max()!) / 2, top, (zs.min()! + zs.max()!) / 2)
        node.eulerAngles.x = .pi / 2 // facing down
        return node
    }

    // MARK: Stand-ins

    private static func color(_ kind: PlanItemKind) -> UIColor {
        switch kind {
        case .support: return UIColor(white: 0.25, alpha: 1)
        case .audio: return UIColor(red: 0.3, green: 0.45, blue: 0.7, alpha: 1)
        case .camera: return UIColor(white: 0.15, alpha: 1)
        default: return UIColor(red: 0.9, green: 0.55, blue: 0.25, alpha: 0.85)
        }
    }

    private static func material(_ color: UIColor, emissive: Bool = false) -> SCNMaterial {
        let m = SCNMaterial()
        m.diffuse.contents = color
        m.lightingModel = emissive ? .constant : .lambert
        m.transparency = color.cgColor.alpha
        return m
    }

    private static func node(_ g: SCNGeometry, _ m: SCNMaterial, at p: SIMD3<Float>) -> SCNNode {
        g.materials = [m]
        let n = SCNNode(geometry: g)
        n.simdPosition = p
        return n
    }

    private static func base(_ item: PlanItem) -> SIMD3<Float> {
        SIMD3(Float(item.position.x), Float(item.position.y), Float(item.position.z))
    }

    private static func box(_ item: PlanItem, color: UIColor) -> SCNNode {
        let g = SCNBox(width: item.size.x, height: item.size.y, length: item.size.z, chamferRadius: 0.01)
        let n = node(g, material(color), at: base(item) + SIMD3(0, Float(item.size.y) / 2, 0))
        n.eulerAngles.y = Float(item.yaw)
        return n
    }

    /// A simple figure, seated (on a stool) when the item is under 1.45 m tall, scaled to its height.
    private static func person(_ item: PlanItem) -> SCNNode {
        let h = Float(item.size.y)
        let seated = h < 1.45
        let face = seated ? h - 0.15 : h - 0.13
        let skin = material(UIColor(red: 0.78, green: 0.58, blue: 0.47, alpha: 1))
        let cloth = material(UIColor(red: 0.22, green: 0.27, blue: 0.36, alpha: 1))
        let legs = material(UIColor(white: 0.2, alpha: 1))
        let g = SCNNode()
        g.addChildNode(node(SCNSphere(radius: 0.11), skin, at: SIMD3(0, face, 0)))
        g.addChildNode(node(SCNCapsule(capRadius: 0.17, height: 0.62), cloth, at: SIMD3(0, face - 0.42, 0)))
        if seated {
            g.addChildNode(node(SCNCylinder(radius: 0.2, height: CGFloat(max(0.2, face - 0.75))), legs,
                                at: SIMD3(0, max(0.1, (face - 0.75) / 2), 0)))
        } else {
            g.addChildNode(node(SCNCapsule(capRadius: 0.13, height: CGFloat(max(0.5, face - 0.55))), legs,
                                at: SIMD3(0, max(0.25, (face - 0.55) / 2), 0)))
        }
        g.simdPosition = base(item)
        g.eulerAngles.y = Float(item.yaw)
        return g
    }

    /// A stand with a glowing head at the item's height, aimed at the target (or the camera).
    private static func light(_ item: PlanItem, aim target: PlanItem?, camera: ShotCamera) -> SCNNode {
        let g = SCNNode()
        let top = base(item) + SIMD3(0, Float(item.size.y), 0)
        let stand = SCNCylinder(radius: 0.012, height: item.size.y)
        g.addChildNode(node(stand, material(UIColor(white: 0.2, alpha: 1)), at: base(item) + SIMD3(0, Float(item.size.y) / 2, 0)))
        let head = node(SCNBox(width: 0.3, height: 0.3, length: 0.08, chamferRadius: 0.02),
                        material(UIColor(red: 1, green: 0.95, blue: 0.82, alpha: 1), emissive: true), at: top)
        let aim: SIMD3<Float>
        if let target {
            aim = base(target) + SIMD3(0, Float(target.size.y) * 0.85, 0)
        } else {
            aim = SIMD3(Float(camera.position.x), Float(camera.position.y), Float(camera.position.z))
        }
        if simd_distance(aim, top) > 0.05 { head.simdLook(at: aim) }
        g.addChildNode(head)
        return g
    }
}

/// The frame preview: the scene from the storyboard view's camera.
struct StoryboardFramePreview: UIViewRepresentable {
    @ObservedObject var scan: StoryboardScanScene
    let shot: PlanShot
    let meshLook: Bool

    func makeUIView(context: Context) -> SCNView {
        let view = SCNView()
        view.scene = scan.scene
        view.pointOfView = scan.cameraNode
        // Dark grey, not black: any gap left above a low wall reads as shadow, not a hole.
        view.backgroundColor = UIColor(white: 0.25, alpha: 1)
        view.antialiasingMode = .multisampling4X
        view.autoenablesDefaultLighting = true
        view.isUserInteractionEnabled = false
        scan.show(shot, meshLook: meshLook)
        return view
    }

    func updateUIView(_ view: SCNView, context: Context) {
        scan.show(shot, meshLook: meshLook)
    }
}
