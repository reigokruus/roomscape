import Foundation

// Metres in RoomPlan's world space: +Y up, camera forward is -Z at yaw zero.
struct PlanPoint: Codable, Equatable {
    var x: Double
    var y: Double
    var z: Double
    static let zero = PlanPoint(x: 0, y: 0, z: 0)
    static func + (a: Self, b: Self) -> Self { .init(x: a.x+b.x, y: a.y+b.y, z: a.z+b.z) }
    static func - (a: Self, b: Self) -> Self { .init(x: a.x-b.x, y: a.y-b.y, z: a.z-b.z) }
    static func * (a: Self, b: Double) -> Self { .init(x: a.x*b, y: a.y*b, z: a.z*b) }
    var length: Double { sqrt(x*x+y*y+z*z) }
    var finite: Bool { [x,y,z].allSatisfy(\.isFinite) }
    func dot(_ b: Self) -> Double { x*b.x+y*b.y+z*b.z }
    func rotated(yaw: Double) -> Self {
        .init(x: x*cos(yaw)+z*sin(yaw), y: y, z: -x*sin(yaw)+z*cos(yaw))
    }
}

struct PlanWall: Codable, Equatable {
    var a: PlanPoint
    var b: PlanPoint
    var bottom: Double
    var top: Double
}

struct PlanRoom: Codable, Equatable {
    var walls: [PlanWall]
    var floor: Double
    var ceiling: Double
    var heightsEstimated = true
    var bounds: (minX: Double, maxX: Double, minZ: Double, maxZ: Double) {
        let points = walls.flatMap { [$0.a, $0.b] }
        return (points.map(\.x).min() ?? -2, points.map(\.x).max() ?? 2,
                points.map(\.z).min() ?? -2, points.map(\.z).max() ?? 2)
    }
    var center: PlanPoint {
        let b = bounds
        return .init(x: (b.minX+b.maxX)/2, y: floor, z: (b.minZ+b.maxZ)/2)
    }
    var closed: Bool {
        let ends = walls.flatMap { [$0.a, $0.b] }
        // Refuse ambiguous/open segments rather than granting a room-fit pass.
        guard walls.count >= 3, ends.indices.allSatisfy({ i in
            ends.indices.filter { j in i != j && Self.distanceXZ(ends[i], ends[j]) < 0.06 }.count == 1
        }) else { return false }
        // A single connected cycle, not two disjoint rooms or a bow-tie polygon.
        var reached:Set<Int> = [0], pending = [0]
        while let i = pending.popLast() {
            for j in walls.indices where !reached.contains(j) {
                if [walls[i].a,walls[i].b].contains(where:{ a in
                    [walls[j].a,walls[j].b].contains { Self.distanceXZ(a,$0) < 0.06 }
                }) { reached.insert(j); pending.append(j) }
            }
        }
        guard reached.count == walls.count else { return false }
        for i in walls.indices { for j in walls.indices where j > i {
            let a = walls[i].a, b = walls[j].a
            let d = walls[i].b-a, e = walls[j].b-b, delta = b-a
            let denom = d.x*e.z-d.z*e.x
            if abs(denom) < 0.000001 { continue }
            let t = (delta.x*e.z-delta.z*e.x)/denom
            let u = (delta.x*d.z-delta.z*d.x)/denom
            if t > 0.0001 && t < 0.9999 && u > 0.0001 && u < 0.9999 { return false }
        } }
        return true
    }
    static func distanceXZ(_ a: PlanPoint, _ b: PlanPoint) -> Double { hypot(a.x-b.x, a.z-b.z) }
    /// Floor-plan distance from `p` to the nearest wall.
    func distanceToWalls(_ p: PlanPoint) -> Double {
        walls.map { w in
            let dx = w.b.x-w.a.x, dz = w.b.z-w.a.z
            let u = max(0, min(1, ((p.x-w.a.x)*dx+(p.z-w.a.z)*dz)/max(0.000001,dx*dx+dz*dz)))
            return hypot(p.x-w.a.x-u*dx, p.z-w.a.z-u*dz)
        }.min() ?? .infinity
    }
    /// `p` when it's inside the room and `margin` clear of the walls; otherwise the first such point on the way to
    /// the room's centre, or the centre itself. Height is kept, within the floor and ceiling. (A bounding-box guess
    /// such as "near the back edge" lands outside the walls in a rotated or L-shaped room.)
    func pulledInside(_ p: PlanPoint, margin: Double = 0.3) -> PlanPoint {
        let y = min(max(p.y, floor+0.05), ceiling-0.05)
        let start = PlanPoint(x:p.x, y:y, z:p.z), c = PlanPoint(x:center.x, y:y, z:center.z)
        for step in 0...20 {
            let q = start+(c-start)*(Double(step)/20)
            if contains(q) && distanceToWalls(q) >= margin { return q }
        }
        return c
    }
    func contains(_ p: PlanPoint) -> Bool {
        guard p.y >= floor-0.001, p.y <= ceiling+0.001 else { return false }
        var inside = false
        for wall in walls {
            let a = wall.a, b = wall.b
            let dx = b.x-a.x, dz = b.z-a.z
            let u = max(0, min(1, ((p.x-a.x)*dx+(p.z-a.z)*dz)/max(0.000001,dx*dx+dz*dz)))
            if hypot(p.x-a.x-u*dx, p.z-a.z-u*dz) < 0.001 { return true }
            if (a.z > p.z) != (b.z > p.z), p.x < (b.x-a.x)*(p.z-a.z)/(b.z-a.z)+a.x { inside.toggle() }
        }
        return inside
    }
    func wallHit(from p: PlanPoint, to q: PlanPoint) -> Bool {
        let d = q-p
        for w in walls {
            let e = w.b-w.a, delta = w.a-p
            let denom = d.x*e.z-d.z*e.x
            if abs(denom) < 0.000001 { continue }
            let t = (delta.x*e.z-delta.z*e.x)/denom
            let u = (delta.x*d.z-delta.z*d.x)/denom
            let y = p.y+t*d.y
            if t > 0.0001 && t < 0.9999 && u >= 0 && u <= 1 && y >= w.bottom && y <= w.top { return true }
        }
        return false
    }
}

enum PlanItemKind: String, Codable, CaseIterable { case light, camera, support, audio, furniture, subject, other }
enum PlanItemSource: String, Codable { case scanned, planned }

struct PlanItem: Identifiable, Codable, Equatable {
    var id = UUID()
    var name: String
    var kind: PlanItemKind
    var source: PlanItemSource = .planned
    var position: PlanPoint // bottom centre, NOT geometric centre
    var size: PlanPoint // width, height, depth
    var yaw: Double = 0
    var dailyRate: Double = 0 // EUR/day, illustrative unless user changes it
    var equipment: Bool { source == .planned && kind != .furniture && kind != .subject }
    var corners: [PlanPoint] {
        [-1.0,1.0].flatMap { x in [0.0,1.0].flatMap { y in [-1.0,1.0].map { z in
            position + PlanPoint(x:x*size.x/2, y:y*size.y, z:z*size.z/2).rotated(yaw:yaw)
        } } }
    }
    func contains(_ p: PlanPoint) -> Bool {
        let d = (p-position).rotated(yaw:-yaw)
        return abs(d.x) < size.x/2 && abs(d.z) < size.z/2 && d.y >= 0 && d.y <= size.y
    }
}

struct ShotCamera: Codable, Equatable {
    var position: PlanPoint
    var yaw: Double = 0
    var focalMM: Double = 35
    var sensorWidthMM: Double = 36
    var sensorHeightMM: Double = 24
    var forward: PlanPoint { PlanPoint(x:0,y:0,z:-1).rotated(yaw:yaw) }
}

struct PlanShot: Identifiable, Codable, Equatable {
    var id = UUID()
    var name: String
    var notes = ""
    var camera: ShotCamera
    var items: [PlanItem]
    var targetID: UUID?
    var days = 1
    var dailyTotal: Double { items.reduce(0) { $0+$1.dailyRate } }
    var total: Double { dailyTotal*Double(days) }
    func duplicated() -> Self {
        var copy = self
        copy.id = UUID()
        copy.name = String((name+" copy").prefix(120))
        var targets: [UUID:UUID] = [:]
        copy.items = items.map { item in
            var next = item; next.id = UUID(); targets[item.id] = next.id; return next
        }
        copy.targetID = targetID.flatMap { targets[$0] }
        return copy
    }
}

struct EquipmentPreset: Identifiable, Codable, Equatable {
    var id = UUID()
    var name: String
    var items: [PlanItem] // positions relative to room centre, bottom-relative-to-floor
    static func capture(name: String, shot: PlanShot, room: PlanRoom) -> Self {
        .init(name:name, items:shot.items.filter(\.equipment).map { item in
            var next = item; next.position = item.position-room.center; return next
        })
    }
    func applying(to shot: PlanShot, room: PlanRoom) throws -> PlanShot {
        var next = shot
        let kept = shot.items.filter { !$0.equipment }
        var occupied = kept
        let added = try items.map { item -> PlanItem in
            var item = item; item.id = UUID(); item.position = item.position+room.center
            if !Placement.canPlace(item, in:room, avoiding:occupied) {
                item.position = try Placement.quickPosition(for:item, in:room, avoiding:occupied, baseY:item.position.y)
            }
            occupied.append(item)
            return item
        }
        next.items = kept+added
        if !next.items.contains(where: { $0.id == next.targetID }) { next.targetID = nil }
        return next
    }
}

struct PlanProject: Identifiable, Codable, Equatable {
    var id = UUID()
    var name: String
    var scanName: String? // reference only; never used as a path from imported JSON
    var room: PlanRoom
    var shots: [PlanShot]
    var presets: [EquipmentPreset] = []
    var total: Double { shots.reduce(0) { $0+$1.total } }
    static func make(name:String, scanName:String?, room:PlanRoom, items:[PlanItem]) -> Self {
        let b = room.bounds
        // Near the back of the room, inside its walls, looking at the first item.
        var camera = ShotCamera(position:room.pulledInside(.init(x:room.center.x,y:min(room.ceiling-0.1,room.floor+1.5),z:b.maxZ-0.4)))
        if let target = items.first {
            let d = target.position-camera.position
            if hypot(d.x,d.z) > 0.01 { camera.yaw = atan2(-d.x,-d.z) }
        }
        return .init(name:name, scanName:scanName, room:room, shots:[.init(name:"View 1",camera:camera,items:items,targetID:items.first?.id)])
    }
    func validate() throws {
        try PlanValidation.text(name, max:120)
        if let scanName { try PlanValidation.text(scanName,max:200) }
        try PlanValidation.room(room)
        guard (1...50).contains(shots.count), presets.count <= 50,
              Set(shots.map(\.id)).count == shots.count,
              Set(presets.map(\.id)).count == presets.count else { throw PlanError.invalid("Use 1–50 uniquely identified views and at most 50 presets.") }
        for shot in shots { try PlanValidation.shot(shot) }
        for preset in presets { try PlanValidation.preset(preset) }
    }
}

enum PlanError: LocalizedError {
    case invalid(String)
    var errorDescription: String? { switch self { case .invalid(let message): return message } }
}

enum PlanValidation {
    static func text(_ value:String, max:Int, empty:Bool = false) throws {
        guard value.count <= max, empty || !value.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty else { throw PlanError.invalid("Text is empty or too long (maximum \(max) characters).") }
    }
    static func point(_ value:PlanPoint) -> Bool { value.finite && [value.x,value.y,value.z].allSatisfy { abs($0) <= 1000 } }
    static func room(_ room:PlanRoom) throws {
        guard (3...200).contains(room.walls.count), room.floor.isFinite, room.ceiling.isFinite,
              abs(room.floor) <= 1000, room.ceiling > room.floor, room.ceiling-room.floor <= 30,
              room.walls.allSatisfy({ point($0.a) && point($0.b) && PlanRoom.distanceXZ($0.a,$0.b) > 0.01 &&
                  $0.bottom.isFinite && $0.top.isFinite && $0.bottom >= room.floor-0.2 && $0.top <= room.ceiling+0.2 && $0.top > $0.bottom }) else {
            throw PlanError.invalid("Room geometry is missing or invalid.")
        }
    }
    static func items(_ items:[PlanItem]) throws {
        guard items.count <= 100, Set(items.map(\.id)).count == items.count else { throw PlanError.invalid("A view can contain at most 100 uniquely identified items.") }
        for item in items {
            try text(item.name,max:120)
            guard point(item.position), point(item.size), [item.size.x,item.size.y,item.size.z].allSatisfy({ $0 > 0 && $0 <= 30 }),
                  item.yaw.isFinite, abs(item.yaw) <= Double.pi+0.0001,
                  item.dailyRate.isFinite, (0...1_000_000).contains(item.dailyRate) else { throw PlanError.invalid("Invalid item dimensions, position or rental rate.") }
        }
    }
    static func shot(_ shot:PlanShot) throws {
        try text(shot.name,max:120); try text(shot.notes,max:10000,empty:true); try items(shot.items)
        let c = shot.camera
        guard point(c.position), c.yaw.isFinite, abs(c.yaw) <= Double.pi+0.0001,
              c.focalMM.isFinite, (1...1000).contains(c.focalMM),
              c.sensorWidthMM.isFinite, (1...100).contains(c.sensorWidthMM),
              c.sensorHeightMM.isFinite, (1...100).contains(c.sensorHeightMM),
              (1...365).contains(shot.days), shot.targetID == nil || shot.items.contains(where: { $0.id == shot.targetID }) else {
            throw PlanError.invalid("Invalid camera, shoot days or missing target item.")
        }
    }
    static func preset(_ preset:EquipmentPreset) throws {
        try text(preset.name,max:120); try items(preset.items)
        guard preset.items.allSatisfy(\.equipment) else { throw PlanError.invalid("Presets contain planned equipment only, not subjects or scanned furniture.") }
    }
}
