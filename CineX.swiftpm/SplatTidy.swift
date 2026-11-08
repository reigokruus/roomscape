import Foundation
import simd

/// Cleans the splats up for viewing (the result screen's 3D view and the storyboard's frame view). The cached and
/// trained splats are left as they are; only what's drawn changes:
/// - splats outside the room (seen through doors and windows, below the floor, above the ceiling) are dropped;
/// - floaters (small clumps of splats with nothing around them: depth noise, reflections) are dropped;
/// - splats on the ceiling are marked so the viewer hides them while the camera is above the ceiling, like the
///   walls' dollhouse cutaway: from the orbit and top views you look straight down into the room;
/// - the walls' and floor's typical scanned colours are measured, so in the Photo look the room shell behind the
///   splats is painted in them and gaps between splats don't flash grey.
enum SplatTidy {
    /// `SurfelRecord.wallAngle` for a splat on the ceiling (any real wall angle is within ±π).
    static let ceiling: Float = 50

    struct Result {
        var surfels: [SurfelRecord]
        var colors: ShellColors
        var outside = 0, floaters = 0, onCeiling = 0
    }

    /// The scanned colour (sRGB bytes) of each wall (same order as `RoomShell.walls`) and of the floor, or nil
    /// where too little of it was scanned.
    struct ShellColors {
        var walls: [SIMD3<UInt8>?] = []
        var floor: SIMD3<UInt8>?
    }

    /// Splats this far outside the floor outline still count as in the room (RoomPlan's walls sit a little off).
    static let footprintMargin: Float = 0.2
    /// Floater test: a splat is kept when its cell and the 26 around it hold at least this many splats.
    static let minNeighbours = 8

    static func tidy(_ input: [SurfelRecord], shell: RoomShell) -> Result {
        let floorY = shell.floorY
        let ceilingY = shell.ceilingY
        let tops = shell.walls.map { $0.center.y + $0.halfHeight }
        let roof = (tops.max() ?? (floorY + 3)) + 0.15
        let outline = shell.floorOutline.map { SIMD2($0.x, $0.z) }

        var result = Result(surfels: [], colors: ShellColors())
        var kept: [SurfelRecord] = []
        kept.reserveCapacity(input.count)
        for var s in input {
            if s.y < floorY - 0.08 || s.y > roof
                || (outline.count >= 3 && !inside(SIMD2(s.x, s.z), outline, margin: footprintMargin)) {
                result.outside += 1
                continue
            }
            if let ceilingY, s.y > ceilingY - 0.15, s.nx * s.nx + s.nz * s.nz < 0.3 { // flat or no normal
                s.wallAngle = ceiling
                result.onCeiling += 1
            }
            kept.append(s)
        }

        result.surfels = dropFloaters(kept, removed: &result.floaters)
        result.colors = shellColors(result.surfels, shell: shell)
        return result
    }

    // MARK: Footprint

    /// Whether `p` is inside the polygon or within `margin` of its edges.
    private static func inside(_ p: SIMD2<Float>, _ poly: [SIMD2<Float>], margin: Float) -> Bool {
        var crossings = false
        var nearest = Float.infinity
        var j = poly.count - 1
        for i in 0..<poly.count {
            let a = poly[j], b = poly[i]
            if (b.y > p.y) != (a.y > p.y), p.x < (a.x - b.x) * (p.y - b.y) / (a.y - b.y) + b.x { crossings.toggle() }
            let ab = a - b
            let t = simd_clamp(simd_dot(p - b, ab) / max(simd_length_squared(ab), 1e-8), 0, 1)
            nearest = min(nearest, simd_length_squared(p - (b + ab * t)))
            j = i
        }
        return crossings || nearest <= margin * margin
    }

    // MARK: Floaters

    /// Counts splats per grid cell; drops splats whose 3x3x3 block of cells holds fewer than `minNeighbours`.
    /// The cell follows the splats' size, so coarse (trained) splats aren't mistaken for sparse ones.
    private static func dropFloaters(_ surfels: [SurfelRecord], removed: inout Int) -> [SurfelRecord] {
        guard surfels.count > 1000 else { return surfels }
        var radii = Swift.stride(from: 0, to: surfels.count, by: max(1, surfels.count / 2000)).map { surfels[$0].radius }
        radii.sort()
        let cell = min(0.15, max(0.06, 4 * radii[radii.count / 2]))

        func key(_ ix: Int, _ iy: Int, _ iz: Int) -> UInt64 {
            UInt64((ix + 1_048_576) & 0x1F_FFFF) | UInt64((iy + 1_048_576) & 0x1F_FFFF) << 21
                | UInt64((iz + 1_048_576) & 0x1F_FFFF) << 42
        }
        let cells = surfels.map { s -> (Int, Int, Int) in
            (Int((s.x / cell).rounded(.down)), Int((s.y / cell).rounded(.down)), Int((s.z / cell).rounded(.down)))
        }
        var counts: [UInt64: Int] = [:]
        counts.reserveCapacity(surfels.count / 8)
        for c in cells { counts[key(c.0, c.1, c.2), default: 0] += 1 }

        var keep: [UInt64: Bool] = [:]
        keep.reserveCapacity(counts.count)
        var out: [SurfelRecord] = []
        out.reserveCapacity(surfels.count)
        for (i, c) in cells.enumerated() {
            let k = key(c.0, c.1, c.2)
            let ok: Bool
            if let known = keep[k] {
                ok = known
            } else {
                var total = 0
                for d in 0..<27 where total < minNeighbours {
                    total += counts[key(c.0 + d / 9 - 1, c.1 + (d / 3) % 3 - 1, c.2 + d % 3 - 1)] ?? 0
                }
                ok = total >= minNeighbours
                keep[k] = ok
            }
            if ok { out.append(surfels[i]) } else { removed += 1 }
        }
        return out
    }

    // MARK: Shell colours

    /// The median colour of the splats lying on each wall and on the floor (`Surfels.snap` and training put them
    /// there, 5 mm in front). Per-channel medians, so a poster or a rug doesn't tint the whole wall or floor.
    private static func shellColors(_ surfels: [SurfelRecord], shell: RoomShell) -> ShellColors {
        let bins = 256
        var wallHist = [[Int]](repeating: [Int](repeating: 0, count: bins * 3), count: shell.walls.count)
        var floorHist = [Int](repeating: 0, count: bins * 3)
        var wallCount = [Int](repeating: 0, count: shell.walls.count)
        var floorCount = 0

        func add(_ rgba: UInt32, to hist: inout [Int]) {
            hist[Int(rgba & 0xFF)] += 1
            hist[bins + Int((rgba >> 8) & 0xFF)] += 1
            hist[2 * bins + Int((rgba >> 16) & 0xFF)] += 1
        }

        for s in surfels {
            let p = SIMD3(s.x, s.y, s.z), n = SIMD3(s.nx, s.ny, s.nz)
            if abs(n.y) > 0.9, abs(p.y - shell.floorY) < 0.02 {
                add(s.rgba, to: &floorHist)
                floorCount += 1
                continue
            }
            for (w, wall) in shell.walls.enumerated() {
                let d = p - wall.center
                let off = simd_dot(d, wall.inward)
                guard off > -0.01, off < 0.02, abs(simd_dot(n, wall.inward)) > 0.9,
                      abs(simd_dot(d, wall.right)) <= wall.halfWidth,
                      abs(simd_dot(d, wall.up)) <= wall.halfHeight else { continue }
                add(s.rgba, to: &wallHist[w])
                wallCount[w] += 1
                break
            }
        }

        func median(_ hist: [Int], count: Int) -> SIMD3<UInt8>? {
            guard count >= 50 else { return nil }
            var rgb = SIMD3<UInt8>()
            for ch in 0..<3 {
                var seen = 0
                for v in 0..<bins {
                    seen += hist[ch * bins + v]
                    if seen * 2 >= count { rgb[ch] = UInt8(v); break }
                }
            }
            return rgb
        }
        return ShellColors(walls: shell.walls.indices.map { median(wallHist[$0], count: wallCount[$0]) },
                           floor: median(floorHist, count: floorCount))
    }
}
