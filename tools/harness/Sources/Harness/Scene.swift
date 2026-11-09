import simd

/// A synthetic room: an inside-out box (walls, floor, ceiling) with a door and a window on the walls, and furniture
/// boxes, each with a material. Rays are cast analytically, so ground truth is exact. Floor at y = 0, y up.
struct Material {
    var albedo: SIMD3<Float>
    /// Procedural texture strength (0 = plain paint), and stripe direction for wood.
    var texture: Float = 0
    var stripes = false
    /// LiDAR behaviour: how often a sample drops out (black, glossy, glass), 0…1.
    var dropout: Float = 0
    var name = ""
}

struct Box {
    var min: SIMD3<Float>, max: SIMD3<Float>
    var material: Material
    var name: String
    /// Movable furniture (for objects / hole-fill tests), vs fixed parts of the room.
    var furniture = true
}

struct Hit {
    var t: Float
    var normal: SIMD3<Float>
    var material: Material
    var point: SIMD3<Float>
    var object: String
}

struct Room {
    var size: SIMD3<Float> // width (x), height (y), depth (z)
    var walls: [Material]  // -x, +x, -z, +z
    var floor: Material, ceiling: Material
    var boxes: [Box]
    /// Door and window as thin boxes on the walls (for the RoomPlan-like ground truth and the window's glass).
    var openings: [(box: Box, isWindow: Bool)]
    var name: String

    /// The nearest surface along a ray (direction unit length), or nil.
    func cast(_ o: SIMD3<Float>, _ d: SIMD3<Float>) -> Hit? {
        var best: Hit?
        // Room shell: inside faces.
        let planes: [(Int, Float, SIMD3<Float>, Material)] = [
            (0, 0, SIMD3(1, 0, 0), walls[0]), (0, size.x, SIMD3(-1, 0, 0), walls[1]),
            (2, 0, SIMD3(0, 0, 1), walls[2]), (2, size.z, SIMD3(0, 0, -1), walls[3]),
            (1, 0, SIMD3(0, 1, 0), floor), (1, size.y, SIMD3(0, -1, 0), ceiling)]
        for (axis, value, n, m) in planes {
            let dv = d[axis]
            guard abs(dv) > 1e-6 else { continue }
            let t = (value - o[axis]) / dv
            guard t > 1e-4, simd_dot(d, n) < 0 else { continue }
            if best == nil || t < best!.t {
                best = Hit(t: t, normal: n, material: m, point: o + d * t, object: "shell")
            }
        }
        for b in boxes + openings.map(\.box) {
            if let (t, n) = Self.slab(o, d, b.min, b.max), best == nil || t < best!.t {
                best = Hit(t: t, normal: n, material: b.material, point: o + d * t, object: b.name)
            }
        }
        return best
    }

    /// Ray against an axis-aligned box from outside: distance and outward normal.
    static func slab(_ o: SIMD3<Float>, _ d: SIMD3<Float>, _ lo: SIMD3<Float>, _ hi: SIMD3<Float>) -> (Float, SIMD3<Float>)? {
        var t0: Float = 1e-4, t1: Float = .greatestFiniteMagnitude
        var axis = 0, sign: Float = 1
        for k in 0..<3 {
            if abs(d[k]) < 1e-9 {
                if o[k] < lo[k] || o[k] > hi[k] { return nil }
                continue
            }
            var a = (lo[k] - o[k]) / d[k], b = (hi[k] - o[k]) / d[k]
            var s: Float = -1
            if a > b { swap(&a, &b); s = 1 }
            if a > t0 { t0 = a; axis = k; sign = s }
            t1 = Swift.min(t1, b)
            if t0 > t1 { return nil }
        }
        guard t0 > 1e-4 else { return nil }
        var n = SIMD3<Float>(repeating: 0)
        n[axis] = sign
        return (t0, n)
    }

    /// The surface colour at a hit (albedo with its procedural texture), 0…1.
    func colour(_ h: Hit) -> SIMD3<Float> {
        var c = h.material.albedo
        if h.material.texture > 0 {
            let p = h.point
            if h.material.stripes {
                // Floorboards: stripes along x, with per-board tone.
                let board = (p.x / 0.14).rounded(.down)
                let tone = 0.85 + 0.3 * fract(sin(board * 12.9898) * 43758.5)
                let grain = 0.95 + 0.05 * sin(p.z * 40 + board)
                c *= tone * grain
            } else {
                let n = noise(p * 6) * 0.6 + noise(p * 23) * 0.4
                c *= 1 - h.material.texture + h.material.texture * (0.6 + 0.8 * n)
            }
        }
        return simd_clamp(c, SIMD3(repeating: 0), SIMD3(repeating: 1))
    }

    /// Distance from a point to the nearest ground-truth surface (for mesh error).
    func surfaceDistance(_ p: SIMD3<Float>) -> Float {
        var d = Swift.min(abs(p.x), abs(p.x - size.x), abs(p.z), abs(p.z - size.z), abs(p.y), abs(p.y - size.y))
        for b in boxes + openings.map(\.box) {
            let q = simd_max(b.min - p, simd_max(SIMD3(repeating: 0), p - b.max))
            let inside = simd_reduce_max(simd_min(p - b.min, b.max - p))
            d = Swift.min(d, inside > 0 ? inside : simd_length(q))
        }
        return d
    }
}

func fract(_ x: Float) -> Float { x - x.rounded(.down) }

/// Cheap smooth value noise, 0…1.
func noise(_ p: SIMD3<Float>) -> Float {
    let i = SIMD3<Float>(p.x.rounded(.down), p.y.rounded(.down), p.z.rounded(.down)), f = p - i
    func h(_ q: SIMD3<Float>) -> Float { fract(sin(simd_dot(q, SIMD3(127.1, 311.7, 74.7))) * 43758.5453) }
    let u = f * f * (3 - 2 * f)
    var v: Float = 0
    for dz in 0...1 { for dy in 0...1 { for dx in 0...1 {
        let w = (dx == 1 ? u.x : 1 - u.x) * (dy == 1 ? u.y : 1 - u.y) * (dz == 1 ? u.z : 1 - u.z)
        v += w * h(i + SIMD3(Float(dx), Float(dy), Float(dz)))
    } } }
    return v
}

// MARK: Scenarios

enum Scenario: String, CaseIterable {
    case tight      // rk's case: a tight 2.5 × 3 m room
    case normal     // 4 × 3 × 2.6 m, furnished, some textured walls
    case corridor   // plain white walls, the hard case for depth-only tracking
    case chest      // a chest of drawers against a wall and a chair (object removal / hole fill)

    var room: Room {
        let white = Material(albedo: SIMD3(0.86, 0.86, 0.84), name: "white paint")
        let grey = Material(albedo: SIMD3(0.62, 0.64, 0.66), texture: 0.25, name: "grey plaster")
        let blue = Material(albedo: SIMD3(0.55, 0.66, 0.78), texture: 0.15, name: "blue wall")
        let wood = Material(albedo: SIMD3(0.62, 0.45, 0.30), texture: 1, stripes: true, name: "parquet")
        let ceiling = Material(albedo: SIMD3(0.92, 0.92, 0.92), name: "ceiling")
        let darkWood = Material(albedo: SIMD3(0.30, 0.20, 0.13), texture: 0.3, name: "dark wood")
        let fabric = Material(albedo: SIMD3(0.75, 0.30, 0.32), texture: 0.4, name: "red fabric")
        let black = Material(albedo: SIMD3(0.04, 0.04, 0.05), dropout: 0.6, name: "black gloss")
        let glass = Material(albedo: SIMD3(0.9, 0.95, 1.0), dropout: 0.9, name: "window glass")
        let door = Material(albedo: SIMD3(0.80, 0.78, 0.72), texture: 0.1, name: "door")
        switch self {
        case .tight:
            let s = SIMD3<Float>(2.5, 2.6, 3.0)
            return Room(size: s, walls: [white, grey, white, blue], floor: wood, ceiling: ceiling,
                        boxes: [Box(min: SIMD3(0, 0, 0.6), max: SIMD3(0.9, 0.5, 2.6), material: fabric, name: "Bed"),
                                Box(min: SIMD3(1.9, 0, 0), max: SIMD3(2.5, 1.9, 0.4), material: darkWood, name: "Shelf")],
                        openings: [(Box(min: SIMD3(1.0, 0, 2.97), max: SIMD3(1.85, 2.0, 3.0), material: door, name: "Door", furniture: false), false),
                                   (Box(min: SIMD3(2.47, 0.9, 1.0), max: SIMD3(2.5, 2.1, 2.2), material: glass, name: "Window", furniture: false), true)],
                        name: "tight 2.5 × 3 m")
        case .normal:
            let s = SIMD3<Float>(4, 2.6, 3)
            return Room(size: s, walls: [grey, white, blue, white], floor: wood, ceiling: ceiling,
                        boxes: [Box(min: SIMD3(0.1, 0, 0.1), max: SIMD3(1.6, 0.5, 2.1), material: fabric, name: "Bed"),
                                Box(min: SIMD3(2.4, 0, 0.4), max: SIMD3(3.4, 0.75, 1.2), material: darkWood, name: "Table"),
                                Box(min: SIMD3(2.7, 0, 1.4), max: SIMD3(3.15, 0.9, 1.85), material: fabric, name: "Chair"),
                                Box(min: SIMD3(3.6, 0, 1.8), max: SIMD3(4, 1.8, 2.9), material: darkWood, name: "Shelf"),
                                Box(min: SIMD3(1.8, 0, 0), max: SIMD3(2.2, 0.6, 0.35), material: black, name: "TV stand")],
                        openings: [(Box(min: SIMD3(0.2, 0, 2.97), max: SIMD3(1.05, 2.0, 3.0), material: door, name: "Door", furniture: false), false),
                                   (Box(min: SIMD3(1.2, 0.9, 0), max: SIMD3(2.6, 2.1, 0.03), material: glass, name: "Window", furniture: false), true)],
                        name: "normal 4 × 3 m")
        case .corridor:
            let s = SIMD3<Float>(1.4, 2.5, 5)
            return Room(size: s, walls: [white, white, white, white], floor: Material(albedo: SIMD3(0.7, 0.7, 0.68), name: "grey floor"),
                        ceiling: ceiling, boxes: [],
                        openings: [(Box(min: SIMD3(0.3, 0, 4.97), max: SIMD3(1.1, 2.0, 5), material: door, name: "Door", furniture: false), false)],
                        name: "plain white corridor 1.4 × 5 m")
        case .chest:
            let s = SIMD3<Float>(3.5, 2.6, 3)
            return Room(size: s, walls: [blue, white, grey, white], floor: wood, ceiling: ceiling,
                        boxes: [Box(min: SIMD3(1.2, 0, 0), max: SIMD3(2.2, 0.85, 0.45), material: darkWood, name: "Chest of drawers"),
                                Box(min: SIMD3(2.6, 0, 1.6), max: SIMD3(3.05, 0.9, 2.05), material: fabric, name: "Chair")],
                        openings: [(Box(min: SIMD3(0.3, 0, 2.97), max: SIMD3(1.15, 2.0, 3), material: door, name: "Door", furniture: false), false)],
                        name: "chest of drawers against a wall")
        }
    }
}
