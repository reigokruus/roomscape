import Foundation
import RoomPlan
import SceneKit
import UIKit
import simd

/// Runs `WindowFinder` on a saved scan and caches the result in `<scan>.capture/windows.json`.
enum WindowDetection {
    /// Bump when the finder's rules change, so cached results are recomputed.
    static let version = 2

    private struct Cache: Codable {
        var version: Int
        var windows: [WindowFinder.Found]
    }

    /// Windows found from the scan's photos and points, or empty for scans saved without them.
    /// Slow the first time (photo decoding, well under a second): call off the main thread.
    static func load(_ scan: SavedScan, room: CapturedRoom) -> [WindowFinder.Found] {
        let cacheURL = scan.captureURL.appendingPathComponent("windows.json")
        if let data = try? Data(contentsOf: cacheURL),
           let cache = try? JSONDecoder().decode(Cache.self, from: data), cache.version == version {
            return cache.windows
        }
        guard let photoData = try? Data(contentsOf: scan.captureURL.appendingPathComponent("keyframes.json")),
              let records = try? JSONDecoder().decode([KeyframeRecord].self, from: photoData),
              !records.isEmpty else { return [] }
        let photos = records.compactMap { k -> WindowFinder.Photo? in
            guard k.cameraToWorld.count == 16 else { return nil }
            let m = k.cameraToWorld
            return WindowFinder.Photo(
                cameraToWorld: simd_float4x4(SIMD4(m[0], m[1], m[2], m[3]), SIMD4(m[4], m[5], m[6], m[7]),
                                             SIMD4(m[8], m[9], m[10], m[11]), SIMD4(m[12], m[13], m[14], m[15])),
                fx: k.fx, fy: k.fy, cx: k.cx, cy: k.cy, width: k.width, height: k.height,
                url: scan.captureURL.appendingPathComponent(k.file))
        }
        // Every n-th point is plenty for counting surfaces and see-through per 10 cm cell, and keeps big scans fast.
        let all = ScanStore.loadPoints(scan)
        let step = max(1, all.count / 400_000)
        let points = Swift.stride(from: 0, to: all.count, by: step).map { SIMD3(all[$0].x, all[$0].y, all[$0].z) }

        let shell = RoomShell(room: room)
        let walls = shell.walls.map {
            WindowFinder.Wall(center: $0.center, right: $0.right, up: $0.up, inward: $0.inward,
                              halfWidth: $0.halfWidth, halfHeight: $0.halfHeight)
        }
        // RoomPlan's own windows, on the wall they belong to, so matches can be marked.
        let wallIDs = room.walls.map(\.identifier)
        let roomPlanWindows = room.windows.compactMap { w -> (wall: Int, minAlong: Float, maxAlong: Float, minUp: Float, maxUp: Float)? in
            guard let parent = w.parentIdentifier, let wi = wallIDs.firstIndex(of: parent) else { return nil }
            let wall = walls[wi]
            let d = SIMD3(w.transform.columns.3.x, w.transform.columns.3.y, w.transform.columns.3.z) - wall.center
            let along = simd_dot(d, wall.right), up = simd_dot(d, wall.up)
            return (wi, along - w.dimensions.x / 2, along + w.dimensions.x / 2,
                    up - w.dimensions.y / 2, up + w.dimensions.y / 2)
        }

        let start = Date()
        let found = WindowFinder.find(walls: walls, points: points, photos: photos, roomPlanWindows: roomPlanWindows)
        print("windows: \(found.count) found (\(found.filter { !$0.matchesRoomPlan }.count) new) from \(photos.count) photos "
              + "in \(Int(Date().timeIntervalSince(start) * 1000)) ms")
        if let data = try? JSONEncoder().encode(Cache(version: version, windows: found)) {
            try? data.write(to: cacheURL, options: .atomic)
        }
        return found
    }

    /// Light-blue outlined panels on the walls where windows were found that RoomPlan missed, drawn 1 cm into the
    /// room so they show over both RoomPlan's model and the splats.
    static func node(_ windows: [WindowFinder.Found], room: CapturedRoom) -> SCNNode {
        let group = SCNNode()
        let walls = RoomShell(room: room).walls
        for w in windows where !w.matchesRoomPlan && w.wallIndex < walls.count {
            let wall = walls[w.wallIndex]
            let along = (w.minAlong + w.maxAlong) / 2, up = (w.minUp + w.maxUp) / 2
            let center = wall.center + wall.right * along + wall.up * up + wall.inward * 0.01
            let plane = SCNPlane(width: CGFloat(w.width), height: CGFloat(w.height))
            let m = SCNMaterial()
            m.diffuse.contents = UIColor(red: 0.45, green: 0.75, blue: 1.0, alpha: 0.45)
            m.lightingModel = .constant
            m.isDoubleSided = true
            m.writesToDepthBuffer = false
            plane.materials = [m]
            let node = SCNNode(geometry: plane)
            // SCNPlane lies in its local x-y plane facing +z: line x up with the wall, +z into the room.
            node.simdTransform = simd_float4x4(SIMD4(wall.right, 0), SIMD4(wall.up, 0), SIMD4(wall.inward, 0),
                                               SIMD4(center, 1))
            node.renderingOrder = 20
            group.addChildNode(node)
        }
        return group
    }
}

extension WindowFinder.Found {
    /// "Curtained window, 6.3 × 3.1 m" for summaries.
    var label: String {
        let kindText = kind == .curtained ? "Curtained window" : "Window (clear glass)"
        return String(format: "%@, %.1f × %.1f m", kindText, width, height)
    }
}
