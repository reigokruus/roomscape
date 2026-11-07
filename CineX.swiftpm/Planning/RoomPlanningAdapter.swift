import Foundation
import RoomPlan
import simd

// Preserve RoomPlan's world origin/rotation, including rotated rooms. Never replace it with a sample rectangle.
enum RoomPlanningAdapter {
    static func makeProject(room:CapturedRoom,scan:SavedScan) throws -> PlanProject {
        let walls = room.walls.map { wall -> PlanWall in
            let t = wall.transform, center = t.columns.3
            let half = wall.dimensions.x/2
            let a = center-simd_float4(t.columns.0.x,t.columns.0.y,t.columns.0.z,0)*half
            let b = center+simd_float4(t.columns.0.x,t.columns.0.y,t.columns.0.z,0)*half
            return .init(a:.init(x:Double(a.x),y:Double(center.y),z:Double(a.z)),
                         b:.init(x:Double(b.x),y:Double(center.y),z:Double(b.z)),
                         bottom:Double(center.y-wall.dimensions.y/2),top:Double(center.y+wall.dimensions.y/2))
        }
        guard let floor = walls.map(\.bottom).min(),let ceiling = walls.map(\.top).max() else { throw PlanError.invalid("No walls in this scan; scan the room boundary before planning.") }
        let geometry = PlanRoom(walls:walls,floor:floor,ceiling:ceiling)
        guard room.objects.count <= 100 else { throw PlanError.invalid("This scan has more than 100 detected objects. Scan a smaller area for a planning project; the saved scan is unchanged.") }
        let objects = room.objects.map { object -> PlanItem in
            let t = object.transform, d = object.dimensions
            return .init(name:String(describing:object.category).capitalized,kind:.furniture,source:.scanned,
                         position:.init(x:Double(t.columns.3.x),y:Double(t.columns.3.y-d.y/2),z:Double(t.columns.3.z)),
                         size:.init(x:Double(d.x),y:Double(d.y),z:Double(d.z)),yaw:atan2(Double(t.columns.2.x),Double(t.columns.2.z)))
        }
        var project = PlanProject.make(name:scan.name,scanName:scan.name,room:geometry,items:objects)
        project.presets = PlanCatalog.starterPresets
        try project.validate()
        return project
    }
}
