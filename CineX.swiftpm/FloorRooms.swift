import Foundation
import RoomPlan
import SceneKit
import simd

/// The floor's rooms on the result screen, as on the Android app: each room's floor area and size, a tap on a room's
/// floor picks it, and its sides can be dragged where the scan measured a curtain or a cupboard as the wall.
///
/// Where they come from: the scan's points split into rooms at doorways (`RoomSplit`, the Android app's method) when
/// they show two rooms or more; else RoomPlan's floor outline (`RoomFloor`), the more exact one for a single room.
/// Dragged sides are kept in `floor-edits.json` (outward metres per side), the automatic outline stays as it was;
/// the numbers shown are saved in `floor-area.json` (the .rmsc's `floorArea`).
struct FloorRooms {
    enum Source: String { case roomPlan, points }

    struct Room {
        /// The automatic outline on the floor (x, z), corners in order: the sides that can be dragged.
        var outline: [FloorPoint]
        var autoArea: Double
        var autoLength: Double, autoWidth: Double
        /// "about": walls not found on every side, or RoomPlan gave no floor.
        var rough: Bool
        /// Points rooms: how many of its 4 sides have a wall (for the .rmsc).
        var wallSides: Int?
    }

    var source: Source
    var floorY: Float
    var rooms: [Room]
    /// The points' grid, for tapping a room and tinting its floor (points rooms only).
    var split: RoomSplit.Result?
    /// Per room, per side: how far the user moved it outwards, metres (0 = where the scan put it).
    var edits: [[Double]]

    // MARK: Making it

    /// Measures a scan's floor; nil when there's no room to measure.
    static func make(room: CapturedRoom, points: [LivePoint], scan: SavedScan) -> FloorRooms? {
        let floorY = RoomShell(room: room).floorY
        var made: FloorRooms?
        if !points.isEmpty {
            var pos = [Float]()
            pos.reserveCapacity(points.count * 3)
            for p in points { pos.append(p.x); pos.append(p.y); pos.append(p.z) }
            let split = RoomSplit.compute(pos, floorY: floorY)
            if split.rooms.count >= 2 || (split.rooms.count == 1 && RoomFloor(room: room) == nil) {
                let rooms = split.rooms.map { r in
                    Room(outline: split.corners(r).map { FloorPoint(x: Double($0.x), z: Double($0.z)) },
                         autoArea: Double(r.shown), autoLength: Double(r.w), autoWidth: Double(r.l),
                         rough: split.rooms.count == 1 ? split.rough : false, wallSides: r.wallSides)
                }
                made = FloorRooms(source: .points, floorY: floorY, rooms: rooms, split: split, edits: [])
            }
        }
        if made == nil, let floor = RoomFloor(room: room) {
            let outline = FloorOutline.simplified(floor.corners)
            made = FloorRooms(source: .roomPlan, floorY: floorY,
                              rooms: [Room(outline: outline, autoArea: floor.area, autoLength: floor.length, autoWidth: floor.width,
                                           rough: floor.rough, wallSides: nil)],
                              split: nil, edits: [])
        }
        guard var made else { return nil }
        made.edits = made.rooms.map { [Double](repeating: 0, count: $0.outline.count) }
        made.loadEdits(scan)
        print("floor rooms: \(made.rooms.count) from \(made.source.rawValue), \(made.areaText), \(points.count) points")
        return made
    }

    // MARK: Numbers

    var total: Double { rooms.indices.reduce(0) { $0 + area($1) } }

    func isEdited(_ i: Int) -> Bool { edits.indices.contains(i) && edits[i].contains { $0 != 0 } }
    var anyEdited: Bool { rooms.indices.contains(where: isEdited) }

    /// The room's area: changed by exactly what moving its sides added or took away.
    func area(_ i: Int) -> Double {
        let r = rooms[i]
        guard isEdited(i) else { return r.autoArea }
        return max(0, r.autoArea + FloorOutline.area(editedOutline(i)) - FloorOutline.area(r.outline))
    }

    /// Length × width, longer first.
    func size(_ i: Int) -> (length: Double, width: Double) {
        let r = rooms[i]
        guard isEdited(i), let s = FloorOutline.size(editedOutline(i)) else { return (r.autoLength, r.autoWidth) }
        return s
    }

    /// "≈19 m²" for one room (no ≈ once its walls were set by hand); "≈26 m² in 2 rooms".
    var areaText: String {
        if rooms.count == 1 {
            return isEdited(0) ? FloorOutline.m2(area(0)) + " m² (walls set by hand)" : FloorOutline.text(area: total, rough: rooms[0].rough)
        }
        return (anyEdited ? "" : "≈") + FloorOutline.m2(total) + " m² in \(rooms.count) rooms"
    }

    /// "≈6.4 m², 3.3 × 2.9 m", or "6.4 m², 3.30 × 2.90 m (walls set by hand)".
    func roomText(_ i: Int) -> String {
        let s = size(i)
        if isEdited(i) { return FloorOutline.m2(area(i)) + String(format: " m², %.2f × %.2f m (walls set by hand)", s.length, s.width) }
        return FloorOutline.text(area: area(i), rough: rooms[i].rough) + String(format: ", %.1f × %.1f m", s.length, s.width)
    }

    /// The short line under the scan's name: "≈5.65 × 3.51 m · 19 m²", "Room 2: ≈7.2 m², 3.8 × 2.6 m",
    /// "2 rooms · ≈26 m² in all". `wallSize` is RoomPlan's own length × width (shown for one room not set by hand).
    func titleText(selected: Int?, wallSize: (Double, Double)?) -> String {
        if let i = selected, rooms.indices.contains(i), rooms.count >= 2 { return "Room \(i + 1): " + roomText(i) }
        if rooms.count == 1 {
            let edited = isEdited(0)
            let s = edited ? size(0) : wallSize.map { (length: $0.0, width: $0.1) } ?? size(0)
            return String(format: "%@%.2f × %.2f m · %@ m²", edited ? "" : "≈", s.length, s.width, FloorOutline.m2(area(0)))
        }
        return "\(rooms.count) rooms · " + (anyEdited ? "" : "≈") + FloorOutline.m2(total) + " m² in all"
    }

    /// `floorArea` for the .rmsc and `floor-area.json` (docs/rmsc-format.md): `width` is the longer side there.
    var manifest: [String: Any] {
        func r(_ v: Double, _ places: Double = 100) -> Double { (v * places).rounded() / places }
        let list: [[String: Any]] = rooms.indices.map { i in
            let s = size(i)
            var o: [String: Any] = ["area": r(area(i)), "width": r(s.length), "length": r(s.width), "handSet": isEdited(i),
                                    "outline": editedOutline(i).map { [r($0.x, 1000), r($0.z, 1000)] }]
            if let w = rooms[i].wallSides { o["wallSides"] = w }
            return o
        }
        return ["total": r(total), "rooms": list]
    }

    // MARK: Sides

    /// Each side's outward direction (x, z), unit length.
    func normals(_ i: Int) -> [FloorPoint] { FloorOutline.outwardNormals(rooms[i].outline) }

    /// The outline with each side moved outwards by its edit (corners where the moved sides meet).
    func editedOutline(_ i: Int) -> [FloorPoint] {
        FloorOutline.offset(rooms[i].outline, by: edits.indices.contains(i) ? edits[i] : [])
    }

    /// The side of room `i` nearest to a floor point, within `tolerance` metres.
    func side(near p: FloorPoint, room i: Int, tolerance: Double) -> Int? {
        FloorOutline.nearestSide(editedOutline(i), to: p, tolerance: tolerance)
    }

    /// Puts side `k` of room `i` through a floor point (1 cm steps, at most 1.5 m from where the scan put it).
    mutating func drag(room i: Int, side k: Int, to p: FloorPoint) {
        let a = rooms[i].outline[k], n = normals(i)[k]
        let out = (p.x - a.x) * n.x + (p.z - a.z) * n.z
        edits[i][k] = (max(-1.5, min(1.5, out)) * 100).rounded() / 100
    }

    /// The room under a floor point.
    func room(at p: FloorPoint) -> Int? {
        if source == .points, let split { return split.room(atX: Float(p.x), z: Float(p.z)) }
        return rooms.indices.first { FloorOutline.contains(editedOutline($0), p) }
    }

    // MARK: Files

    private static func editsURL(_ scan: SavedScan) -> URL { scan.captureURL.appendingPathComponent("floor-edits.json") }
    static func areaURL(_ scan: SavedScan) -> URL { scan.captureURL.appendingPathComponent("floor-area.json") }

    /// Edits saved for the same rooms (same source, same number of sides); others are left out.
    private mutating func loadEdits(_ scan: SavedScan) {
        guard let data = try? Data(contentsOf: Self.editsURL(scan)),
              let j = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              j["source"] as? String == source.rawValue,
              let saved = j["rooms"] as? [[NSNumber]], saved.count == rooms.count else { return }
        for i in rooms.indices where saved[i].count == rooms[i].outline.count { edits[i] = saved[i].map(\.doubleValue) }
    }

    /// Writes the edits (or removes them when there are none) and the numbers shown.
    func save(_ scan: SavedScan) {
        if anyEdited {
            let j: [String: Any] = ["source": source.rawValue, "rooms": edits]
            if let data = try? JSONSerialization.data(withJSONObject: j) { try? data.write(to: Self.editsURL(scan), options: .atomic) }
        } else {
            try? FileManager.default.removeItem(at: Self.editsURL(scan))
        }
        if let data = try? JSONSerialization.data(withJSONObject: manifest, options: [.sortedKeys]) {
            try? data.write(to: Self.areaURL(scan), options: .atomic)
        }
    }
}

/// The floor's rooms drawn in the 3D view: the picked room's floor tinted amber, and the sides of the room that can
/// be dragged as amber lines (white while dragged), drawn over everything like on the Android app.
final class FloorRoomsNode {
    let root: SCNNode = { let n = SCNNode(); n.name = "floorRooms"; return n }()
    private static let amber = UIColor(red: 1, green: 0.69, blue: 0.13, alpha: 1)

    func update(_ rooms: FloorRooms, selected: Int?, adjustable: Int, dragging: Int?) {
        root.childNodes.forEach { $0.removeFromParentNode() }
        let y = rooms.floorY
        if let s = selected, rooms.rooms.indices.contains(s), let tint = tint(rooms, s, y: y + 0.015) { root.addChildNode(tint) }
        guard rooms.rooms.indices.contains(adjustable) else { return }
        let q = rooms.editedOutline(adjustable)
        for k in q.indices {
            let a = SIMD3<Float>(Float(q[k].x), y + 0.02, Float(q[k].z))
            let b = SIMD3<Float>(Float(q[(k + 1) % q.count].x), y + 0.02, Float(q[(k + 1) % q.count].z))
            let len = simd_length(b - a)
            guard len > 0.01 else { continue }
            let box = SCNBox(width: CGFloat(len), height: 0.012, length: dragging == k ? 0.06 : 0.04, chamferRadius: 0)
            box.materials = [Self.material(dragging == k ? .white : Self.amber)]
            let node = SCNNode(geometry: box)
            node.simdPosition = (a + b) / 2
            node.simdOrientation = simd_quatf(angle: atan2(-(b.z - a.z), b.x - a.x), axis: SIMD3(0, 1, 0))
            node.renderingOrder = 120
            root.addChildNode(node)
        }
    }

    private static func material(_ color: UIColor) -> SCNMaterial {
        let m = SCNMaterial()
        m.diffuse.contents = color
        m.lightingModel = .constant
        m.readsFromDepthBuffer = false
        m.writesToDepthBuffer = false
        m.isDoubleSided = true
        return m
    }

    /// The room's floor: its grid cells (points rooms, as on Android), or inside its outline (RoomPlan's).
    private func tint(_ rooms: FloorRooms, _ i: Int, y: Float) -> SCNNode? {
        var verts: [SIMD3<Float>] = []
        if rooms.source == .points, let s = rooms.split {
            let c = RoomSplit.cell, id = Int32(i + 1)
            for k in s.roomOf.indices where s.roomOf[k] == id {
                let x0 = s.x0 + Float(k % s.nx) * c, z0 = s.z0 + Float(k / s.nx) * c
                verts += [SIMD3(x0, y, z0), SIMD3(x0 + c, y, z0), SIMD3(x0 + c, y, z0 + c),
                          SIMD3(x0, y, z0), SIMD3(x0 + c, y, z0 + c), SIMD3(x0, y, z0 + c)]
            }
        } else {
            let path = UIBezierPath()
            let q = rooms.editedOutline(i)
            guard q.count >= 3 else { return nil }
            path.move(to: CGPoint(x: q[0].x, y: -q[0].z))
            for p in q.dropFirst() { path.addLine(to: CGPoint(x: p.x, y: -p.z)) }
            path.close()
            path.flatness = 0.01
            let shape = SCNShape(path: path, extrusionDepth: 0)
            let fill = Self.amber.withAlphaComponent(0.4)
            let m = Self.material(fill)
            m.readsFromDepthBuffer = true
            shape.materials = [m]
            let node = SCNNode(geometry: shape)
            node.eulerAngles.x = -.pi / 2   // the shape's x, y onto the floor's x, -z
            node.position.y = y
            node.renderingOrder = 110
            return node
        }
        guard !verts.isEmpty else { return nil }
        let source = SCNGeometrySource(vertices: verts.map { SCNVector3($0) })
        let element = SCNGeometryElement(indices: (0..<Int32(verts.count)).map { $0 }, primitiveType: .triangles)
        let geo = SCNGeometry(sources: [source], elements: [element])
        let m = Self.material(Self.amber.withAlphaComponent(0.4))
        m.readsFromDepthBuffer = true
        geo.materials = [m]
        let node = SCNNode(geometry: geo)
        node.renderingOrder = 110
        return node
    }
}
