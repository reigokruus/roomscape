import SceneKit
import UIKit
import simd

/// The splat view split by object: the room's splats in one node, and each movable object's own scanned splats in
/// a node of their own, so removing hides them and moving / turning sets that node's transform. Nothing is drawn
/// in place of an object: what moves is what the scan captured. Fixed things (sockets, room lights) get small
/// markers; the selected object gets a thin outline.
@MainActor
final class ObjectsScene {
    /// Parent of the room splats, object splats and markers. Rebuilt when the set of objects changes.
    let root = SCNNode()
    private(set) var objectNodes: [UUID: SCNNode] = [:]
    private var markerNodes: [UUID: SCNNode] = [:]
    private let outline = SCNNode()
    /// Objects mode: a thin light-blue box around every object, the selected one yellow, as in the Android app.
    /// Not inside the splats, so they show over the photo room too.
    let boxes = SCNNode()
    private var boxNodes: [UUID: SCNNode] = [:]
    private static let boxColor = UIColor(red: 0.4, green: 0.85, blue: 1, alpha: 0.85)
    private static let selectedColor = UIColor(red: 1, green: 0.85, blue: 0.2, alpha: 1)

    init() {
        // The 12 edges of a unit cube (no face diagonals).
        var corners: [SCNVector3] = []
        for i in 0..<8 { corners.append(SCNVector3(Float(i & 1) - 0.5, Float((i >> 1) & 1) - 0.5, Float((i >> 2) & 1) - 0.5)) }
        var edges: [UInt16] = []
        for i in 0..<8 { for bit in [1, 2, 4] where i & bit == 0 { edges += [UInt16(i), UInt16(i | bit)] } }
        let box = SCNGeometry(sources: [SCNGeometrySource(vertices: corners)],
                              elements: [SCNGeometryElement(indices: edges, primitiveType: .line)])
        let m = SCNMaterial()
        m.diffuse.contents = UIColor.systemYellow
        m.lightingModel = .constant
        box.materials = [m]
        outline.geometry = box
        outline.isHidden = true
        outline.renderingOrder = 30
        boxes.name = "objectBoxes"
    }

    /// The 12 edges of a unit cube, in one colour, drawn over everything.
    private static func wireBox(_ color: UIColor) -> SCNGeometry {
        var corners: [SCNVector3] = []
        for i in 0..<8 { corners.append(SCNVector3(Float(i & 1) - 0.5, Float((i >> 1) & 1) - 0.5, Float((i >> 2) & 1) - 0.5)) }
        var edges: [UInt16] = []
        for i in 0..<8 { for bit in [1, 2, 4] where i & bit == 0 { edges += [UInt16(i), UInt16(i | bit)] } }
        let g = SCNGeometry(sources: [SCNGeometrySource(vertices: corners)],
                            elements: [SCNGeometryElement(indices: edges, primitiveType: .line)])
        let m = SCNMaterial()
        m.diffuse.contents = color
        m.lightingModel = .constant
        m.readsFromDepthBuffer = false
        m.writesToDepthBuffer = false
        g.materials = [m]
        return g
    }

    /// The boxes for the current objects: one per movable, not removed thing, at its real size and turn.
    private func updateBoxes(_ objects: [RoomObject], selected: UUID?) {
        var keep = Set<UUID>()
        for o in objects where !o.kind.isMarker && !o.removed {
            keep.insert(o.id)
            let node = boxNodes[o.id] ?? {
                let n = SCNNode()
                n.renderingOrder = 40
                boxes.addChildNode(n)
                boxNodes[o.id] = n
                return n
            }()
            let isSelected = o.id == selected
            if node.name != (isSelected ? "selected" : "box") {
                node.name = isSelected ? "selected" : "box"
                node.geometry = Self.wireBox(isSelected ? Self.selectedColor : Self.boxColor)
            }
            var t = simd_float4x4(simd_quatf(angle: o.yaw + o.turn, axis: SIMD3(0, 1, 0)))
            t.columns.3 = SIMD4(o.center + o.offset, 1)
            node.simdTransform = t * simd_float4x4(diagonal: SIMD4(o.halfExtents * 2, 1))
        }
        for (id, node) in boxNodes where !keep.contains(id) {
            node.removeFromParentNode()
            boxNodes[id] = nil
        }
    }

    /// Built off the main thread (partition + vertex buffers), then attached with `install`.
    struct Built: @unchecked Sendable {
        let room: SCNNode
        let objects: [UUID: SCNNode]
    }

    nonisolated static func build(_ surfels: [SurfelRecord], objects: [RoomObject], floorY: Float) -> Built {
        let (room, byObject) = partition(surfels, objects: objects, floorY: floorY)
        var nodes: [UUID: SCNNode] = [:]
        for (id, splats) in byObject where !splats.isEmpty { nodes[id] = SplatScene.splatNode(splats) }
        return Built(room: SplatScene.splatNode(room), objects: nodes)
    }

    /// Splits the splats: a few hundred thousand box tests.
    nonisolated static func partition(_ surfels: [SurfelRecord], objects: [RoomObject], floorY: Float)
        -> (room: [SurfelRecord], byObject: [UUID: [SurfelRecord]]) {
        let movable = objects.filter { !$0.kind.isMarker }
        // Quick reject: each object's bounding sphere.
        let radius2 = movable.map { o -> Float in let r = simd_length(o.halfExtents) + 0.05; return r * r }
        var room: [SurfelRecord] = []
        var byObject: [UUID: [SurfelRecord]] = [:]
        room.reserveCapacity(surfels.count)
        for s in surfels {
            let p = SIMD3(s.x, s.y, s.z)
            var owner: UUID?
            if p.y > floorY + 0.015 { // the floor under furniture stays with the room
                for (i, o) in movable.enumerated() where simd_length_squared(p - o.center) <= radius2[i]
                    && o.contains(p, margin: 0.03) {
                    owner = o.id
                    break
                }
            }
            if let owner { byObject[owner, default: []].append(s) } else { room.append(s) }
        }
        return (room, byObject)
    }

    /// Replaces the scene contents with freshly built nodes.
    func install(_ built: Built, objects: [RoomObject]) {
        root.childNodes.forEach { $0.removeFromParentNode() }
        objectNodes = built.objects
        markerNodes = [:]
        root.addChildNode(built.room)
        for node in built.objects.values { root.addChildNode(node) }
        for o in objects where o.kind.isMarker {
            let node = marker(o)
            root.addChildNode(node)
            markerNodes[o.id] = node
        }
        root.addChildNode(outline)
        apply(objects, selected: nil)
    }

    /// Applies edits (removed, moved, turned) and the selection outline. Cheap: no geometry is rebuilt.
    func apply(_ objects: [RoomObject], selected: UUID?) {
        updateBoxes(objects, selected: selected)
        for o in objects {
            if let node = objectNodes[o.id] {
                node.isHidden = o.removed
                node.simdTransform = o.editTransform
            }
            markerNodes[o.id]?.isHidden = o.removed
        }
        // Movable things are outlined by their box (see `updateBoxes`); the extra outline is for markers.
        if let id = selected, let o = objects.first(where: { $0.id == id }), o.kind.isMarker {
            outline.isHidden = false
            let size = o.halfExtents * 2 + SIMD3(repeating: o.kind.isMarker ? 0.06 : 0.04)
            var t = simd_float4x4(simd_quatf(angle: o.yaw + o.turn, axis: SIMD3(0, 1, 0)))
            t.columns.3 = SIMD4(o.center + o.offset, 1)
            outline.simdTransform = t * simd_float4x4(diagonal: SIMD4(size, 1))
        } else {
            outline.isHidden = true
        }
    }

    /// A small marker: an orange plate for power, a warm disc for a room light.
    private func marker(_ o: RoomObject) -> SCNNode {
        let m = SCNMaterial()
        m.lightingModel = .constant
        let g: SCNGeometry
        if o.kind.isPower {
            m.diffuse.contents = UIColor.systemOrange
            g = SCNBox(width: 0.09, height: 0.09, length: 0.02, chamferRadius: 0.01)
        } else {
            m.diffuse.contents = UIColor(red: 1, green: 0.85, blue: 0.45, alpha: 1)
            g = SCNSphere(radius: 0.07)
        }
        g.materials = [m]
        let node = SCNNode(geometry: g)
        node.simdPosition = o.center
        // Always visible over the splats it sits on.
        node.renderingOrder = 25
        m.readsFromDepthBuffer = false
        return node
    }
}
