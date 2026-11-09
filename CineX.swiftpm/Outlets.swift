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
    /// Sized in points on screen, not metres: `RoomSceneView` rescales them every frame (see `screenSizes`), so they
    /// stay ~27 pt tall at any distance, the same as on Android.
    static func node(_ outlets: [RoomOutlet], floorY: Float) -> SCNNode {
        let root = SCNNode()
        root.name = "outlets"
        for o in outlets {
            let image = pill("⚡ " + o.label(floorY: floorY))
            // A unit-tall plane; the per-frame scale makes it image.size.height points tall.
            let plane = SCNPlane(width: image.size.width / image.size.height, height: 1)
            let m = SCNMaterial()
            m.diffuse.contents = image
            m.lightingModel = .constant
            m.readsFromDepthBuffer = false
            m.writesToDepthBuffer = false
            m.isDoubleSided = true
            plane.materials = [m]
            let label = SCNNode(geometry: plane)
            label.name = "screen:\(Double(image.size.height)):1.4" // points tall : offset above the dot, in heights
            label.constraints = [SCNBillboardConstraint()]
            label.renderingOrder = 101
            let ring = SCNNode(geometry: SCNPlane(width: 1, height: 1))
            ring.geometry?.firstMaterial = dotMaterial(UIColor.black, size: 14)
            ring.name = "screen:14:0"
            ring.constraints = [SCNBillboardConstraint()]
            ring.renderingOrder = 100
            let dot = SCNNode(geometry: SCNPlane(width: 1, height: 1))
            dot.geometry?.firstMaterial = dotMaterial(UIColor(red: 1, green: 0.62, blue: 0.1, alpha: 1), size: 12)
            dot.name = "screen:12:0"
            dot.constraints = [SCNBillboardConstraint()]
            dot.renderingOrder = 101
            // One anchor per outlet at its position; the sized parts sit in it (see `screenSizes`).
            let anchor = SCNNode()
            anchor.simdPosition = o.point
            anchor.addChildNode(ring)
            anchor.addChildNode(dot)
            anchor.addChildNode(label)
            root.addChildNode(anchor)
        }
        return root
    }

    private static func dotMaterial(_ color: UIColor, size: CGFloat) -> SCNMaterial {
        let image = UIGraphicsImageRenderer(size: CGSize(width: 64, height: 64)).image { _ in
            color.setFill()
            UIBezierPath(ovalIn: CGRect(x: 0, y: 0, width: 64, height: 64)).fill()
        }
        let m = SCNMaterial()
        m.diffuse.contents = image
        m.lightingModel = .constant
        m.readsFromDepthBuffer = false
        m.writesToDepthBuffer = false
        m.isDoubleSided = true
        return m
    }

    /// Every frame: scale the outlet labels and dots so they keep their size in points on screen, lifting each
    /// label above its dot. `viewHeight` in points, `fov` the camera's vertical field of view in degrees.
    static func screenSizes(in scene: SCNScene, camera: SCNNode, viewHeight: CGFloat, fov: CGFloat) {
        guard let root = scene.rootNode.childNode(withName: "outlets", recursively: false), viewHeight > 0 else { return }
        let metresPerPoint = 2 * tan(Float(fov) * .pi / 360) / Float(viewHeight)
        let eye = camera.simdWorldPosition
        for anchor in root.childNodes {
            let d = max(0.05, simd_distance(eye, anchor.simdWorldPosition))
            for n in anchor.childNodes {
                guard let parts = n.name?.split(separator: ":"), parts.count == 3, parts[0] == "screen",
                      let points = Float(parts[1]), let lift = Float(parts[2]) else { continue }
                let s = d * metresPerPoint * points
                n.simdScale = SIMD3(repeating: s)
                n.simdPosition = SIMD3(0, s * lift, 0)
            }
        }
    }

    /// The label as an image: white text on a dark orange pill.
    /// 13.5 pt semibold white text in a ~27 pt pill (the Android label's colours), growing with large text sizes only
    /// up to 1.2x.
    private static func pill(_ text: String) -> UIImage {
        let scale = min(1.2, UIFontMetrics(forTextStyle: .footnote).scaledValue(for: 1))
        let font = UIFont.systemFont(ofSize: 13.5 * scale, weight: .semibold)
        let size = (text as NSString).size(withAttributes: [.font: font])
        let box = CGSize(width: ceil(size.width) + 20, height: ceil(27 * scale))
        return UIGraphicsImageRenderer(size: box).image { _ in
            UIColor(red: 0.50, green: 0.25, blue: 0.06, alpha: 0.82).setFill()
            UIBezierPath(roundedRect: CGRect(origin: .zero, size: box), cornerRadius: box.height / 2).fill()
            (text as NSString).draw(at: CGPoint(x: 10, y: (box.height - size.height) / 2),
                                    withAttributes: [.font: font, .foregroundColor: UIColor.white])
        }
    }
}
