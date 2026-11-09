import Foundation
import SceneKit
import UIKit

/// A power outlet found in the room (by the Android app's Claude search, synced here through .rmsc): kind
/// ("outlet", "power strip", "floor socket"), how many sockets, and where (the scan's coordinates, metres).
struct RoomOutlet: Codable, Equatable {
    var kind: String
    var count: Int
    var position: [Float]

    var point: SIMD3<Float> { SIMD3(position[0], position[1], position[2]) }

    /// "Outlet ×2 · 4 cm up", as the Android app labels them.
    func label(floorY: Float) -> String {
        let name = kind.contains("strip") ? "Power strip" : kind.contains("floor") ? "Floor socket" : "Outlet"
        let up = max(0, (point.y - floorY) * 100)
        return "\(name)\(count > 1 ? " ×\(count)" : "") · \(Int(up.rounded())) cm up"
    }
}

/// The scan's outlets, kept in `<scan>.capture/outlets.json`.
enum OutletStore {
    static func url(_ scan: SavedScan) -> URL { scan.captureURL.appendingPathComponent("outlets.json") }

    static func load(_ scan: SavedScan) -> [RoomOutlet] {
        guard let data = try? Data(contentsOf: url(scan)) else { return [] }
        return (try? JSONDecoder().decode([RoomOutlet].self, from: data)) ?? []
    }

    static func save(_ outlets: [RoomOutlet], for scan: SavedScan) {
        if outlets.isEmpty { try? FileManager.default.removeItem(at: url(scan)); return }
        if let data = try? JSONEncoder().encode(outlets) { try? data.write(to: url(scan), options: .atomic) }
    }

    /// Both lists together; within 15 cm is the same outlet (the newer entry wins).
    static func merge(_ newer: [RoomOutlet], _ older: [RoomOutlet]) -> [RoomOutlet] {
        var out = newer
        for o in older where !out.contains(where: { simd_distance($0.point, o.point) < 0.15 }) { out.append(o) }
        return out
    }

    /// The manifest's shared "outlets" list (docs/rmsc-format.md), moved up by `lift`.
    static func fromManifest(_ list: [[String: Any]]?, lift: Float) -> [RoomOutlet] {
        (list ?? []).compactMap { o in
            guard let p = (o["position"] as? [NSNumber])?.map(\.floatValue), p.count == 3 else { return nil }
            return RoomOutlet(kind: o["kind"] as? String ?? "outlet", count: (o["count"] as? NSNumber)?.intValue ?? 1,
                              position: [p[0], p[1] + lift, p[2]])
        }
    }

    /// The outlets for a manifest, with the floor moved to y = 0.
    static func toManifest(_ outlets: [RoomOutlet], floorY: Float) -> [[String: Any]] {
        outlets.map { o in
            ["kind": o.kind, "count": o.count, "position": [o.position[0], o.position[1] - floorY, o.position[2]],
             "heightAboveFloor": o.position[1] - floorY, "source": "claude"]
        }
    }

    /// Orange lightning pills over each outlet, always facing the camera and drawn on top, like the Android app.
    static func node(_ outlets: [RoomOutlet], floorY: Float) -> SCNNode {
        let root = SCNNode()
        root.name = "outlets"
        for o in outlets {
            let text = "⚡ " + o.label(floorY: floorY)
            let image = pill(text)
            let width = image.size.width * 0.0035 // 3.5 mm per point: readable across a room
            let plane = SCNPlane(width: width, height: width * image.size.height / image.size.width)
            let m = SCNMaterial()
            m.diffuse.contents = image
            m.lightingModel = .constant
            m.readsFromDepthBuffer = false
            m.writesToDepthBuffer = false
            m.isDoubleSided = true
            plane.materials = [m]
            let label = SCNNode(geometry: plane)
            label.simdPosition = o.point + SIMD3(0, 0.12, 0)
            label.constraints = [SCNBillboardConstraint()]
            label.renderingOrder = 100
            let dot = SCNNode(geometry: SCNSphere(radius: 0.025))
            dot.geometry?.firstMaterial?.diffuse.contents = UIColor(red: 0.93, green: 0.69, blue: 0.30, alpha: 1)
            dot.geometry?.firstMaterial?.lightingModel = .constant
            dot.simdPosition = o.point
            dot.renderingOrder = 100
            root.addChildNode(dot)
            root.addChildNode(label)
        }
        return root
    }

    /// The label as an image: white text on a dark orange pill.
    private static func pill(_ text: String) -> UIImage {
        let font = UIFont.systemFont(ofSize: 15, weight: .semibold)
        let size = (text as NSString).size(withAttributes: [.font: font])
        let box = CGSize(width: ceil(size.width) + 20, height: ceil(size.height) + 10)
        return UIGraphicsImageRenderer(size: box).image { _ in
            UIColor(red: 0.55, green: 0.30, blue: 0.08, alpha: 0.9).setFill()
            UIBezierPath(roundedRect: CGRect(origin: .zero, size: box), cornerRadius: box.height / 2).fill()
            (text as NSString).draw(at: CGPoint(x: 10, y: 5), withAttributes: [.font: font, .foregroundColor: UIColor.white])
        }
    }
}
