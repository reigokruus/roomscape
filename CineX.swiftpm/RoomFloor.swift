import Foundation
import RoomPlan

/// A RoomPlan room's floor area and size, from RoomPlan's floor outline (`FloorOutline`). The outline follows the
/// walls RoomPlan found, so furniture standing on the floor doesn't make it smaller, and an L-shaped room stays an L.
struct RoomFloor {
    let area: Double
    /// Along the room's own walls, longer first.
    let length: Double, width: Double
    /// The outline's corners, x and z in the scan's coordinates.
    let corners: [FloorPoint]
    /// RoomPlan gave no floor outline, so it was pieced together from the walls' ends ("about").
    let rough: Bool

    init?(room: CapturedRoom) {
        let shell = RoomShell(room: room)
        let corners = shell.floorOutline.map { FloorPoint(x: Double($0.x), z: Double($0.z)) }
        let area = FloorOutline.area(corners)
        // Not a room: a sliver of floor along a single wall (a scan stopped right after it started).
        guard area >= 0.5, let size = FloorOutline.size(corners), size.width >= 0.5 else { return nil }
        self.area = area
        (length, width) = size
        self.corners = corners
        rough = (room.floors.first?.polygonCorners.count ?? 0) < 3
    }

    var text: String { FloorOutline.text(area: area, rough: rough) }

    /// `floorArea` for a .rmsc manifest (docs/rmsc-format.md): one room. `width` is the longer side there.
    var manifest: [String: Any] {
        func r(_ v: Double, _ places: Double = 100) -> Double { (v * places).rounded() / places }
        let room: [String: Any] = ["area": r(area), "width": r(length), "length": r(width),
                                   "outline": corners.map { [r($0.x, 1000), r($0.z, 1000)] }]
        return ["total": r(area), "rooms": [room]]
    }
}

/// A floor area another phone wrote into a .rmsc (`floorArea` in its manifest): the Android app splits the floor into
/// rooms at doorways.
struct PackageFloorArea {
    struct Room {
        var area: Double, width: Double, length: Double
        var rough: Bool
    }
    var total: Double
    var rooms: [Room]

    init?(_ json: Any?) {
        guard let j = json as? [String: Any] else { return nil }
        func num(_ any: Any?) -> Double? { (any as? NSNumber)?.doubleValue }
        rooms = (j["rooms"] as? [[String: Any]] ?? []).compactMap { r in
            guard let area = num(r["area"]) else { return nil }
            // No outline: the seen floor. Walls on fewer than 4 sides: partly the seen floor. Both are "about",
            // unless the user set the walls by hand.
            let handSet = r["handSet"] as? Bool ?? false
            let partly = (r["wallSides"] as? NSNumber).map { $0.intValue < 4 } ?? false
            return Room(area: area, width: num(r["width"]) ?? 0, length: num(r["length"]) ?? 0,
                        rough: !handSet && (r["outline"] == nil || partly))
        }
        total = num(j["total"]) ?? rooms.reduce(0) { $0 + $1.area }
        guard total >= 0.5 else { return nil }
    }

    /// "≈9.9 m²", or "≈35 m² in 2 rooms".
    var text: String {
        guard rooms.count >= 2 else { return FloorOutline.text(area: total, rough: rooms.first?.rough ?? true) }
        return FloorOutline.text(area: total, rough: rooms.contains(where: \.rough)) + " in \(rooms.count) rooms"
    }

    /// "≈6.4 m², 3.3 × 2.9 m".
    static func text(_ room: Room) -> String {
        FloorOutline.text(area: room.area, rough: room.rough)
            + (room.width > 0 && room.length > 0 ? String(format: ", %.1f × %.1f m", room.width, room.length) : "")
    }
}
