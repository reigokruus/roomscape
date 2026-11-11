import Foundation

/// A corner of a room's floor outline: x and z in metres (y is up).
struct FloorPoint: Equatable {
    var x: Double
    var z: Double
}

/// Floor area and size from a room's floor outline. On the iPhone the outline is RoomPlan's floor polygon, which
/// follows the walls, so an L-shaped room keeps its missing corner out of the area. (Android works its rooms out from
/// the scanned points instead: `FloorArea.java`.)
enum FloorOutline {
    /// Area inside the outline, m² (corners in order, either way round; the shoelace formula).
    static func area(_ corners: [FloorPoint]) -> Double {
        guard corners.count >= 3 else { return 0 }
        var twice = 0.0
        for i in corners.indices {
            let a = corners[i], b = corners[(i + 1) % corners.count]
            twice += a.x * b.z - b.x * a.z
        }
        return abs(twice) / 2
    }

    /// Length × width (longer first) of the smallest rectangle around the outline that lines up with one of its
    /// sides: the room's size along its own walls, however it was turned while scanning.
    static func size(_ corners: [FloorPoint]) -> (length: Double, width: Double)? {
        guard corners.count >= 3 else { return nil }
        var best: (area: Double, a: Double, b: Double)?
        for i in corners.indices {
            let p = corners[i], q = corners[(i + 1) % corners.count]
            let dx = q.x - p.x, dz = q.z - p.z, len = (dx * dx + dz * dz).squareRoot()
            guard len > 0.01 else { continue }
            let ux = dx / len, uz = dz / len
            var loU = Double.infinity, hiU = -Double.infinity, loV = Double.infinity, hiV = -Double.infinity
            for c in corners {
                let u = c.x * ux + c.z * uz, v = -c.x * uz + c.z * ux
                loU = min(loU, u); hiU = max(hiU, u); loV = min(loV, v); hiV = max(hiV, v)
            }
            let a = hiU - loU, b = hiV - loV
            if best == nil || a * b < best!.area - 1e-9 { best = (a * b, a, b) }
        }
        guard let best else { return nil }
        return (max(best.a, best.b), min(best.a, best.b))
    }

    /// "9.9" under 10 m², "14" from 10 m² up (as the Android app shows it).
    static func m2(_ area: Double) -> String {
        area < 10 ? String(format: "%.1f", area) : String(format: "%.0f", area)
    }

    /// "≈9.9 m²", or "about 9.9 m²" when the outline is a rougher guess (pieced together from the walls).
    static func text(area: Double, rough: Bool = false) -> String {
        (rough ? "about " : "≈") + m2(area) + " m²"
    }
}
