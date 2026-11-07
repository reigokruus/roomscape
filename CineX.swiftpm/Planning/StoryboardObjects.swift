import Foundation
import simd

/// Keeps a scan's storyboard in step with the result screen's Objects panel.
///
/// Each time the storyboard opens, objects whose edit changed since the last time are applied to every view:
/// removed ones leave, put-back ones return, moved or turned ones move, and Claude's new finds are added.
/// A view's furniture is matched by where the last sync put it, so furniture the user moved or edited inside the
/// storyboard isn't touched, and objects nobody changed in the Objects panel leave the storyboard alone.
enum StoryboardObjects {
    /// Where an object stands in plan terms: bottom centre, size (width, height, depth), yaw, or removed.
    struct Placed: Codable, Equatable {
        var position: PlanPoint
        var size: PlanPoint
        var yaw: Double
        var removed: Bool
    }

    /// The last applied state of each object, saved next to the scan's capture.
    typealias Snapshot = [UUID: Placed]

    /// Applies the Objects panel's changes since the last sync to `project`. Returns whether the project changed and
    /// the snapshot to save once the project is saved.
    static func sync(_ project: inout PlanProject, objects: [RoomObject], scan: SavedScan) -> (changed: Bool, snapshot: Snapshot)? {
        let movable = objects.filter { !$0.kind.isMarker }
        guard !movable.isEmpty else { return nil }
        // No snapshot yet: the storyboard was made from RoomPlan's objects as scanned (RoomPlanningAdapter).
        let last = loadSnapshot(scan) ?? Dictionary(movable.filter { $0.source == .roomPlan }.map { ($0.id, unedited($0)) },
                                                    uniquingKeysWith: { a, _ in a })
        var changed = false
        var snapshot: Snapshot = [:]
        for object in movable {
            let now = placed(object)
            snapshot[object.id] = now
            let before = last[object.id]
            guard before != now else { continue }
            for s in project.shots.indices {
                var shot = project.shots[s]
                let i = before.flatMap { b in b.removed ? nil : shot.items.firstIndex { matches($0, b) } }
                if now.removed {
                    guard let i else { continue }
                    if shot.targetID == shot.items[i].id { shot.targetID = nil }
                    shot.items.remove(at: i)
                } else if let i {
                    shot.items[i].position = now.position
                    shot.items[i].yaw = now.yaw
                } else if before == nil || before?.removed == true, shot.items.count < 100 {
                    shot.items.append(item(object, now))
                } else {
                    continue
                }
                project.shots[s] = shot
                changed = true
            }
        }
        return (changed, snapshot)
    }

    static func saveSnapshot(_ snapshot: Snapshot, for scan: SavedScan) {
        try? FileManager.default.createDirectory(at: scan.captureURL, withIntermediateDirectories: true)
        if let data = try? JSONEncoder().encode(snapshot) { try? data.write(to: url(scan), options: .atomic) }
    }

    private static func url(_ scan: SavedScan) -> URL {
        scan.captureURL.appendingPathComponent("storyboard-objects.json")
    }

    private static func loadSnapshot(_ scan: SavedScan) -> Snapshot? {
        guard let data = try? Data(contentsOf: url(scan)) else { return nil }
        return try? JSONDecoder().decode(Snapshot.self, from: data)
    }

    /// The object with its edits, in the storyboard's terms (same conventions as `RoomPlanningAdapter`).
    private static func placed(_ o: RoomObject) -> Placed {
        let c = o.center + o.offset
        let size = simd_max(o.halfExtents * 2, SIMD3(repeating: 0.02))
        return Placed(position: PlanPoint(x: Double(c.x), y: Double(c.y - o.halfExtents.y), z: Double(c.z)),
                      size: PlanPoint(x: Double(size.x), y: Double(size.y), z: Double(size.z)),
                      yaw: wrap(Double(o.yaw + o.turn)), removed: o.removed)
    }

    /// As RoomPlan scanned it, before any edit.
    private static func unedited(_ o: RoomObject) -> Placed {
        var o = o
        o.offset = .zero
        o.turn = 0
        o.removed = false
        return placed(o)
    }

    private static func item(_ o: RoomObject, _ p: Placed) -> PlanItem {
        PlanItem(name: String(o.label.prefix(120)), kind: .furniture, source: .scanned,
                 position: p.position, size: p.size, yaw: p.yaw)
    }

    /// The view's scanned item still standing where the last sync (or the scan) put it.
    private static func matches(_ item: PlanItem, _ p: Placed) -> Bool {
        guard item.source == .scanned else { return false }
        let d = item.position - p.position
        return (d.x * d.x + d.z * d.z).squareRoot() < 0.05 && abs(d.y) < 0.05 && abs(wrap(item.yaw - p.yaw)) < 0.05
    }

    /// Into -π…π, the range the storyboard accepts.
    private static func wrap(_ a: Double) -> Double {
        atan2(sin(a), cos(a))
    }
}
