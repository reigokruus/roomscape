import SceneKit
import simd

/// Applies the Objects panel's edits (remove, move, turn) to RoomPlan's own furniture boxes, so the Model view shows
/// the same room as the splats.
///
/// RoomPlan's USDZ names its boxes by category ("Chair0", "Table0"), not by object, so each box is matched to the
/// RoomPlan object whose box holds the box's centre. The USDZ uses the same world coordinates as `CapturedRoom`.
@MainActor
final class ModelObjects {
    private struct Part {
        let node: SCNNode
        /// World transform and centre as loaded, before any edit.
        let original: simd_float4x4
        let center: SIMD3<Float>
    }

    private let parts: [Part]

    init(model: SCNNode) {
        var parts: [Part] = []
        func visit(_ node: SCNNode) {
            if node.geometry != nil, !Self.isArchitecture(node.name) {
                let (lo, hi) = node.boundingBox
                let local = SIMD3<Float>(Float(lo.x + hi.x), Float(lo.y + hi.y), Float(lo.z + hi.z)) / 2
                parts.append(Part(node: node, original: node.simdWorldTransform,
                                  center: node.simdConvertPosition(local, to: nil)))
                return // its children move with it
            }
            node.childNodes.forEach(visit)
        }
        visit(model)
        self.parts = parts
    }

    /// Hides removed objects and moves / turns edited ones. Cheap: a few dozen box tests.
    func apply(_ objects: [RoomObject]) {
        // Claude's finds have no box in RoomPlan's model; RoomPlan's own objects keep their source after a search.
        let boxes = objects.filter { $0.source == .roomPlan && !$0.kind.isMarker }
        for part in parts {
            let owner = boxes.filter { $0.contains(part.center, margin: 0.1) }
                .min { simd_distance($0.center, part.center) < simd_distance($1.center, part.center) }
            part.node.isHidden = owner?.removed ?? false
            part.node.simdWorldTransform = (owner?.editTransform ?? matrix_identity_float4x4) * part.original
        }
    }

    /// Walls, floor, doors, windows and openings stay put (same names `ResultView.colorize` uses).
    private static func isArchitecture(_ rawName: String?) -> Bool {
        let name = (rawName ?? "").lowercased()
        return ["wall", "floor", "door", "window", "opening"].contains { name.contains($0) }
    }
}
