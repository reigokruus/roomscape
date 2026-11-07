import Foundation

enum Placement {
    static func canPlace(_ item:PlanItem, in room:PlanRoom, avoiding items:[PlanItem]) -> Bool {
        // Corners AND boundary-crossing tests: concave rooms are not a bounding rectangle.
        let c = item.corners
        guard c.allSatisfy(room.contains) else { return false }
        for i in [0,1,4,5] {
            for j in [0,1,4,5] where i != j {
                if room.wallHit(from:c[i]+PlanPoint(x:0,y:0.01,z:0),to:c[j]+PlanPoint(x:0,y:0.01,z:0)) { return false }
            }
        }
        let footprint = aabb(item)
        return !items.contains { other in
            guard other.id != item.id else { return false }
            let b = aabb(other)
            return footprint.0 < b.1-0.01 && footprint.1 > b.0+0.01 && footprint.2 < b.3-0.01 && footprint.3 > b.2+0.01 &&
                item.position.y < other.position.y+other.size.y && item.position.y+item.size.y > other.position.y
        }
    }
    private static func aabb(_ item:PlanItem) -> (Double,Double,Double,Double) {
        let c = item.corners
        return (c.map(\.x).min()!,c.map(\.x).max()!,c.map(\.z).min()!,c.map(\.z).max()!)
    }
    static func quickPosition(for item:PlanItem, in room:PlanRoom, avoiding items:[PlanItem], baseY:Double? = nil) throws -> PlanPoint {
        let b = room.bounds
        let step = max(0.2,ceil(max(b.maxX-b.minX,b.maxZ-b.minZ)/120*10)/10)
        // Hard cap bounds work even for a malicious or huge imported room.
        let nx = min(120,Int(ceil((b.maxX-b.minX)/step)))
        let nz = min(120,Int(ceil((b.maxZ-b.minZ)/step)))
        var candidates:[PlanPoint] = []
        for ix in 0...nx { for iz in 0...nz {
            candidates.append(snapped(.init(x:b.minX+Double(ix)*step,y:baseY ?? room.floor,z:b.minZ+Double(iz)*step)))
        } }
        candidates.sort { PlanRoom.distanceXZ($0,room.center) < PlanRoom.distanceXZ($1,room.center) }
        for p in candidates {
            var candidate = item; candidate.position = p
            if canPlace(candidate,in:room,avoiding:items) { return p }
        }
        throw PlanError.invalid("No clear floor space for this item. Move something, choose a smaller item or inspect the scan boundary.")
    }
    static func snapped(_ point:PlanPoint) -> PlanPoint { .init(x:(point.x*10).rounded()/10,y:point.y,z:(point.z*10).rounded()/10) }
}

struct ShotFit {
    var inFrame: Bool
    var cameraInside: Bool
    var targetInside: Bool
    var wallBlocked: Bool
    var itemBlocked: Bool
    var geometryClosed: Bool
    var horizontalDegrees: Double
    var verticalDegrees: Double
    var stepBack: Double
    var warnings:[String]
    var fits: Bool { inFrame && cameraInside && targetInside && !wallBlocked && !itemBlocked && geometryClosed }
}

enum ShotFraming {
    static func fov(sensor:Double,focal:Double) -> Double { 2*atan(sensor/(2*focal)) }
    static func check(shot:PlanShot, room:PlanRoom) throws -> ShotFit {
        try PlanValidation.room(room); try PlanValidation.shot(shot)
        guard let target = shot.items.first(where:{ $0.id == shot.targetID }) else { throw PlanError.invalid("Choose a target item for the frame check.") }
        let camera = shot.camera
        let forward = camera.forward
        let right = PlanPoint(x:1,y:0,z:0).rotated(yaw:camera.yaw)
        let h = fov(sensor:camera.sensorWidthMM,focal:camera.focalMM)
        let v = fov(sensor:camera.sensorHeightMM,focal:camera.focalMM)
        let th = tan(h/2), tv = tan(v/2), margin = 1.1
        let points = target.corners
        var inFrame = true, step:Double = 0
        for corner in points {
            let delta = corner-camera.position, depth = delta.dot(forward)
            let dx = abs(delta.dot(right))*margin, dy = abs(delta.y)*margin
            if depth <= 0.001 || dx > depth*th || dy > depth*tv { inFrame = false }
            step = max(step, max(dx/th-depth, max(dy/tv-depth,0.001-depth)))
        }
        let samples = points+[target.position+PlanPoint(x:0,y:target.size.y/2,z:0)]
        let wallBlocked = samples.contains { room.wallHit(from:camera.position,to:$0) }
        let ownRig = Set(shot.items.filter { $0.kind == .camera && $0.contains(camera.position) }.map(\.id))
        let cameraOverlaps = shot.items.contains { !ownRig.contains($0.id) && $0.contains(camera.position) }
        let itemBlocked = shot.items.filter { $0.id != target.id && !ownRig.contains($0.id) }.contains { item in
            samples.contains { rayIntersects(item:item,from:camera.position,to:$0) }
        }
        let inside = room.contains(camera.position), targetInside = Placement.canPlace(target,in:room,avoiding:[])
        var warnings = ["Planning estimate: 10% framing margin. Level camera only; no pitch, roll, lens distortion or stabilisation crop.",
                        "RoomPlan openings are conservatively treated as solid wall segments. Collision checks are planning aids, not safety guarantees."]
        if room.heightsEstimated { warnings.append("Floor and ceiling inferred from wall extents; verify on location.") }
        if !room.closed { warnings.append("Open or ambiguous room footprint: room-fit is unverified, never a pass.") }
        if !inside { warnings.append("Camera is outside the inferred room.") }
        if cameraOverlaps { warnings.append("Camera position overlaps an item's envelope.") }
        if !ownRig.isEmpty { warnings.append("The camera-kit envelope containing the shot camera is treated as its own rig, not a sightline obstruction.") }
        if !targetInside { warnings.append("Target extends outside the inferred room.") }
        if wallBlocked { warnings.append("A scanned wall intersects a line of sight to the target.") }
        if itemBlocked { warnings.append("Another item's estimated envelope blocks part of the target.") }
        if !inFrame { warnings.append(String(format:"Optical step-back needed: %.2f m (space behind camera NOT guaranteed).",max(0,step))) }
        return ShotFit(inFrame:inFrame,cameraInside:inside && !cameraOverlaps,targetInside:targetInside,
                       wallBlocked:wallBlocked,itemBlocked:itemBlocked,geometryClosed:room.closed,
                       horizontalDegrees:h*180 / .pi,verticalDegrees:v*180 / .pi,stepBack:max(0,step),warnings:warnings)
    }
    private static func rayIntersects(item:PlanItem,from:PlanPoint,to:PlanPoint) -> Bool {
        let p = (from-item.position).rotated(yaw:-item.yaw)
        let d = (to-from).rotated(yaw:-item.yaw)
        var near = 0.0001, far = 0.9999
        for (o,v,low,high) in [(p.x,d.x,-item.size.x/2,item.size.x/2),(p.y,d.y,0,item.size.y),(p.z,d.z,-item.size.z/2,item.size.z/2)] {
            if abs(v) < 0.0000001 { if o < low || o > high { return false }; continue }
            let a = (low-o)/v, b = (high-o)/v
            near = max(near,min(a,b)); far = min(far,max(a,b))
            if near > far { return false }
        }
        return true
    }
}

enum PlanCatalog {
    static let items:[PlanItem] = [
        .init(name:"LED light + stand",kind:.light,position:.zero,size:.init(x:0.9,y:2.1,z:0.9),dailyRate:35),
        .init(name:"90 cm softbox + stand",kind:.light,position:.zero,size:.init(x:1.0,y:2.1,z:1.0),dailyRate:25),
        .init(name:"LED panel + stand",kind:.light,position:.zero,size:.init(x:0.85,y:2.0,z:0.85),dailyRate:20),
        .init(name:"Camera + tripod",kind:.camera,position:.zero,size:.init(x:0.9,y:1.6,z:0.9),dailyRate:65),
        .init(name:"C-stand",kind:.support,position:.zero,size:.init(x:1.0,y:2.2,z:1.0),dailyRate:8),
        .init(name:"Boom microphone + stand",kind:.audio,position:.zero,size:.init(x:1.2,y:2.3,z:0.9),dailyRate:15),
        .init(name:"Standing person (envelope)",kind:.subject,position:.zero,size:.init(x:0.65,y:1.8,z:0.4)),
        .init(name:"Seated person (envelope)",kind:.subject,position:.zero,size:.init(x:0.7,y:1.3,z:0.8)),
        .init(name:"Table (planning footprint)",kind:.furniture,position:.zero,size:.init(x:1.4,y:0.75,z:0.8),dailyRate:10),
        .init(name:"Chair (planning footprint)",kind:.furniture,position:.zero,size:.init(x:0.55,y:0.85,z:0.55),dailyRate:4),
        .init(name:"Sofa (planning footprint)",kind:.furniture,position:.zero,size:.init(x:2,y:0.9,z:0.9),dailyRate:25)
    ]
    static var starterPresets:[EquipmentPreset] {
        [EquipmentPreset(name:"Interview kit",items:[items[0],items[2],items[3],items[5]]),
         EquipmentPreset(name:"Product kit",items:[items[1],items[2],items[3]]),
         EquipmentPreset(name:"Simple vlog kit",items:[items[2],items[3]])]
    }
}
