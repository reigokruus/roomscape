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

    /// Each side's outward direction (x, z), unit length; side k runs from corner k to corner k + 1.
    static func outwardNormals(_ p: [FloorPoint]) -> [FloorPoint] {
        var signed = 0.0
        for k in p.indices { let a = p[k], b = p[(k + 1) % p.count]; signed += a.x * b.z - b.x * a.z }
        return p.indices.map { k in
            let a = p[k], b = p[(k + 1) % p.count]
            let dx = b.x - a.x, dz = b.z - a.z, len = max(1e-9, (dx * dx + dz * dz).squareRoot())
            return signed > 0 ? FloorPoint(x: dz / len, z: -dx / len) : FloorPoint(x: -dz / len, z: dx / len)
        }
    }

    /// The outline with side k moved outwards by `out[k]` metres (inwards when negative); the new corners are where
    /// the moved sides meet, so a rectangle stays a rectangle and an L stays an L.
    static func offset(_ p: [FloorPoint], by out: [Double]) -> [FloorPoint] {
        guard out.count == p.count, p.count >= 3, out.contains(where: { $0 != 0 }) else { return p }
        let n = outwardNormals(p), m = p.count
        return (0..<m).map { j in
            let a = (j + m - 1) % m   // the side that ends at corner j; side j starts there
            let pa = FloorPoint(x: p[a].x + n[a].x * out[a], z: p[a].z + n[a].z * out[a])
            let pj = FloorPoint(x: p[j].x + n[j].x * out[j], z: p[j].z + n[j].z * out[j])
            let next = p[(j + 1) % m]
            let ta = FloorPoint(x: p[j].x - p[a].x, z: p[j].z - p[a].z), tj = FloorPoint(x: next.x - p[j].x, z: next.z - p[j].z)
            let c = ta.x * tj.z - ta.z * tj.x
            let la = (ta.x * ta.x + ta.z * ta.z).squareRoot(), lj = (tj.x * tj.x + tj.z * tj.z).squareRoot()
            guard la > 1e-9, lj > 1e-9, abs(c) / (la * lj) > 0.15 else {
                // Nearly straight on: the corner moves by both sides' moves, averaged.
                return FloorPoint(x: p[j].x + (n[a].x * out[a] + n[j].x * out[j]) / 2, z: p[j].z + (n[a].z * out[a] + n[j].z * out[j]) / 2)
            }
            let dx = pj.x - pa.x, dz = pj.z - pa.z
            let s = (dx * tj.z - dz * tj.x) / c
            return FloorPoint(x: pa.x + ta.x * s, z: pa.z + ta.z * s)
        }
    }

    /// Drops corners where the outline hardly turns (under 10°) and sides under 5 cm, so each wall is one side.
    static func simplified(_ corners: [FloorPoint]) -> [FloorPoint] {
        var pts = corners
        var changed = true
        while changed && pts.count > 3 {
            changed = false
            for i in pts.indices {
                let a = pts[(i + pts.count - 1) % pts.count], b = pts[i], c = pts[(i + 1) % pts.count]
                let ux = b.x - a.x, uz = b.z - a.z, vx = c.x - b.x, vz = c.z - b.z
                let lu = (ux * ux + uz * uz).squareRoot(), lv = (vx * vx + vz * vz).squareRoot()
                let sine = lu < 1e-9 || lv < 1e-9 ? 0 : abs((ux * vz - uz * vx) / (lu * lv))
                let straight = (ux * vx + uz * vz) > 0 && sine < sin(10 * Double.pi / 180)
                if lu < 0.05 || straight {
                    pts.remove(at: i)
                    changed = true
                    break
                }
            }
        }
        return pts
    }

    /// The side nearest a point, within `tolerance` metres.
    static func nearestSide(_ p: [FloorPoint], to q: FloorPoint, tolerance: Double) -> Int? {
        var best: (Int, Double)?
        for k in p.indices {
            let a = p[k], b = p[(k + 1) % p.count]
            let dx = b.x - a.x, dz = b.z - a.z, l2 = dx * dx + dz * dz
            let t = l2 == 0 ? 0 : max(0, min(1, ((q.x - a.x) * dx + (q.z - a.z) * dz) / l2))
            let ex = q.x - a.x - t * dx, ez = q.z - a.z - t * dz
            let d = (ex * ex + ez * ez).squareRoot()
            if d < tolerance, best == nil || d < best!.1 { best = (k, d) }
        }
        return best?.0
    }

    /// Whether a point is inside the outline.
    static func contains(_ poly: [FloorPoint], _ p: FloorPoint) -> Bool {
        var inside = false
        var j = poly.count - 1
        for i in poly.indices {
            let a = poly[i], b = poly[j]
            if (a.z > p.z) != (b.z > p.z), p.x < (b.x - a.x) * (p.z - a.z) / (b.z - a.z) + a.x { inside.toggle() }
            j = i
        }
        return inside
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
