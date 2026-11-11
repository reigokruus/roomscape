import Foundation

/// The floor split into rooms at doorways, from the scan's points: a port of the Android app's `FloorArea.java`
/// (same steps and numbers), so a space scanned on either phone splits the same way. The points go onto a 5 cm grid
/// over the floor: floor cells, furniture cells (the floor under them counts) and wall cells (a surface from low to
/// above head height). Small gaps are closed, enclosed holes filled, and doorways cut by opening the floor with a
/// 45 cm disc; each part left is a room's core, grown back over the floor. A big room splits again where it narrows
/// under ~1.5 m (open plan). Each room gets its area, its size along its own walls, and the rectangle of its walls.
///
/// The disc steps (grow / shrink by a disc) use an exact distance transform instead of Android's per-offset loop:
/// the same cells, much faster in the app's Debug builds.
enum RoomSplit {
    static let cell: Float = 0.05

    struct Room {
        /// m² of floor seen (with gaps closed and enclosed holes filled).
        var area: Float = 0
        /// Size along its own walls (the smallest rectangle around it), longer first.
        var w: Float = 0, l: Float = 0
        /// m² inside its outline along its own walls (an L stays an L).
        var hull: Float = 0
        /// m² bounded by its walls; 0 when fewer than 2 walls were found.
        var walled: Float = 0
        /// How many of the 4 sides have a wall (fewer: "about").
        var wallSides = 0
        /// The wall rectangle's sides u0, u1, v0, v1 in the room's own directions (`deg`), metres from the grid's
        /// corner: x = x0 + u cos - v sin, z = z0 + u sin + v cos.
        var rect: [Float] = [0, 0, 0, 0]
        var deg: Float = 0
        var cells = 0

        /// The floor to show: bounded by its walls, else within its outline when the seen floor fills most of it,
        /// else what was seen.
        var shown: Float { walled > 0 ? walled : hull > 0 && area / hull >= 0.8 ? hull : area }
    }

    struct Result {
        /// m² of the main (biggest) room, m² of floor outside it, and how much of its edge is a wall (0…1).
        var area: Float = 0, other: Float = 0, enclosed: Float = 0
        /// Rooms of 2 m² or more, biggest first.
        var rooms: [Room] = []
        /// Per grid cell: room index + 1, 0 = none.
        var roomOf: [Int32] = []
        var nx = 0, nz = 0
        var x0: Float = 0, z0: Float = 0

        /// A room's wall rectangle corners in world x, z, in order: (u0, v0), (u1, v0), (u1, v1), (u0, v1).
        func corners(_ room: Room) -> [(x: Float, z: Float)] {
            let r = room.rect
            return [(r[0], r[2]), (r[1], r[2]), (r[1], r[3]), (r[0], r[3])].map { world(room, u: $0.0, v: $0.1) }
        }

        func world(_ room: Room, u: Float, v: Float) -> (x: Float, z: Float) {
            let a = room.deg * .pi / 180
            return (x0 + u * cos(a) - v * sin(a), z0 + u * sin(a) + v * cos(a))
        }

        /// The room at a floor point (x, z): its index, or nil.
        func room(atX x: Float, z: Float) -> Int? {
            let cx = Int(((x - x0) / RoomSplit.cell).rounded(.down)), cz = Int(((z - z0) / RoomSplit.cell).rounded(.down))
            guard cx >= 0, cz >= 0, cx < nx, cz < nz else { return nil }
            let id = Int(roomOf[cz * nx + cx])
            return id > 0 ? id - 1 : nil
        }

        /// One room only, and its outline is open (walls on fewer than 4 sides, or little wall around the seen floor).
        var rough: Bool {
            guard let r0 = rooms.first else { return true }
            return r0.walled > 0 ? r0.wallSides < 4 : enclosed < 0.6
        }
    }

    /// pos: x, y, z per point; floorY: the floor's height.
    static func compute(_ pos: [Float], floorY: Float) -> Result {
        let count = pos.count / 3, CELL = cell
        var x0 = Float.greatestFiniteMagnitude, x1 = -Float.greatestFiniteMagnitude
        var z0 = Float.greatestFiniteMagnitude, z1 = -Float.greatestFiniteMagnitude
        for i in 0..<count {
            let h = pos[i * 3 + 1] - floorY
            if h < -0.4 || h > 2.4 { continue }
            x0 = min(x0, pos[i * 3]); x1 = max(x1, pos[i * 3]); z0 = min(z0, pos[i * 3 + 2]); z1 = max(z1, pos[i * 3 + 2])
        }
        var r = Result()
        if x1 <= x0 || z1 <= z0 || (x1 - x0) * (z1 - z0) > 60 * 60 { return r }   // nothing, or something odd
        x0 -= 1; z0 -= 1; x1 += 1; z1 += 1
        let nx = Int(((x1 - x0) / CELL).rounded(.up)), nz = Int(((z1 - z0) / CELL).rounded(.up)), n = nx * nz
        var floor = [Int32](repeating: 0, count: n), low = floor, high = floor, mid = floor
        for i in 0..<count {
            let h = pos[i * 3 + 1] - floorY
            if h < -0.4 || h > 2.4 { continue }
            let cx = Int((pos[i * 3] - x0) / CELL), cz = Int((pos[i * 3 + 2] - z0) / CELL), c = cz * nx + cx
            guard cx >= 0, cz >= 0, cx < nx, cz < nz else { continue }
            if h < 0.04 { floor[c] += 1 }                                   // the floor, or its reflection below it
            else if h < 1.0 { low[c] += 1; if h > 0.3 { mid[c] += 1 } }
            else if h > 1.6 { high[c] += 1 }                                // above head height: walls (and tall cupboards)
        }
        var g = [UInt8](repeating: 0, count: n)
        for c in 0..<n {
            if high[c] >= 2 && mid[c] >= 2 { g[c] = 3 }                    // a surface from low to high: a wall
            else if floor[c] >= 1 { g[c] = 1 }
            else if low[c] >= 2 { g[c] = 2 }                                // furniture: the floor under it counts
        }
        var inside = g.map { $0 == 1 || $0 == 2 }
        // close small gaps (15 cm), not across walls
        inside = erode(dilate(inside, nx, nz, 3), nx, nz, 3)
        for c in 0..<n where g[c] == 3 { inside[c] = false }
        // holes the room encloses (under a sofa, a rug the depth missed): what the outside can't reach
        var outside = [Bool](repeating: false, count: n)
        var q = Queue()
        for x in 0..<nx { q.add(x); q.add((nz - 1) * nx + x) }
        for z in 0..<nz { q.add(z * nx); q.add(z * nx + nx - 1) }
        while let c = q.poll() {
            if outside[c] || inside[c] || g[c] == 3 { continue }
            outside[c] = true
            let x = c % nx, z = c / nx
            if x > 0 { q.add(c - 1) }; if x < nx - 1 { q.add(c + 1) }; if z > 0 { q.add(c - nx) }; if z < nz - 1 { q.add(c + nx) }
        }
        for c in 0..<n where !outside[c] && g[c] != 3 && !inside[c] { inside[c] = true; if g[c] == 0 { g[c] = 4 } }

        // rooms: open with a 45 cm disc (doorways, 70-90 cm, disappear), each big remaining part is a room's core
        let core = erode(inside, nx, nz, 9)
        var lab = [Int](repeating: 0, count: n)
        var k = 0
        var sizes: [Int] = []
        for c in 0..<n where core[c] && lab[c] == 0 {
            k += 1
            var size = 0
            q.add(c); lab[c] = k
            while let d = q.poll() {
                size += 1
                for e in neighbours(d, nx, nz) where core[e] && lab[e] == 0 { lab[e] = k; q.add(e) }
            }
            sizes.append(size)
        }
        // cores under 0.5 m² (a corridor end, a niche) don't make a room of their own
        for c in 0..<n where lab[c] > 0 && Float(sizes[lab[c] - 1]) * CELL * CELL < 0.5 { lab[c] = 0 }
        // grow each core back by the 45 cm it lost (walking over the floor, nearest core first)
        let allowed = dilate(lab.map { $0 > 0 }, nx, nz, 9)
        var owner = [Int](repeating: 0, count: n)
        for c in 0..<n where lab[c] > 0 && inside[c] { owner[c] = lab[c]; q.add(c) }
        while let d = q.poll() {
            for e in neighbours(d, nx, nz) where inside[e] && allowed[e] && owner[e] == 0 { owner[e] = owner[d]; q.add(e) }
        }
        // open plan: a big room splits again where it narrows under ~1.5 m (a 75 cm disc), when that leaves two or
        // more parts of 1 m² core or more
        let k0 = k
        for id in stride(from: 1, through: k0, by: 1) {
            let mine = owner.map { $0 == id }
            let size = mine.reduce(0) { $0 + ($1 ? 1 : 0) }
            if Float(size) * CELL * CELL < 20 { continue }
            let core2 = erode(mine, nx, nz, 15)
            var lab2 = [Int](repeating: 0, count: n)
            var parts: [Int] = []
            var kk = 0
            for c in 0..<n where core2[c] && lab2[c] == 0 {
                kk += 1
                var sz = 0
                q.add(c); lab2[c] = kk
                while let d = q.poll() {
                    sz += 1
                    for e in neighbours(d, nx, nz) where core2[e] && lab2[e] == 0 { lab2[e] = kk; q.add(e) }
                }
                parts.append(sz)
            }
            if parts.filter({ Float($0) * CELL * CELL >= 1 }).count < 2 { continue }
            // each big part becomes a room; the rest of the room's floor goes to the nearest part
            var newId = [Int](repeating: 0, count: kk + 1)
            var first = true
            for j in stride(from: 1, through: kk, by: 1) where Float(parts[j - 1]) * CELL * CELL >= 1 {
                if first { newId[j] = id } else { k += 1; newId[j] = k }
                first = false
            }
            var own2 = [Int](repeating: 0, count: n)
            for c in 0..<n where lab2[c] > 0 && newId[lab2[c]] > 0 { own2[c] = newId[lab2[c]]; q.add(c) }
            while let d = q.poll() {
                for e in neighbours(d, nx, nz) where mine[e] && own2[e] == 0 { own2[e] = own2[d]; q.add(e) }
            }
            for c in 0..<n where mine[c] { owner[c] = own2[c] }
        }
        var count2 = [Int](repeating: 0, count: k + 1)
        for c in 0..<n { count2[owner[c]] += 1 }
        // rooms of 2 m² or more, biggest first; smaller bits stay with nobody
        let order = (stride(from: 1, through: k, by: 1)).filter { Float(count2[$0]) * CELL * CELL >= 2 }
            .sorted { count2[$0] != count2[$1] ? count2[$0] > count2[$1] : $0 < $1 }
        var index = [Int](repeating: 0, count: k + 1)
        for (i, id) in order.enumerated() { index[id] = i + 1 }
        r.roomOf = owner.map { Int32($0 > 0 ? index[$0] : 0) }
        r.nx = nx; r.nz = nz; r.x0 = x0; r.z0 = z0
        for i in order.indices {
            var room = Room()
            room.cells = count2[order[i]]
            room.area = Float(room.cells) * CELL * CELL
            let cells = (0..<n).filter { r.roomOf[$0] == Int32(i + 1) }
            let wl = minRect(cells, nx)
            room.w = max(wl.w, wl.l); room.l = min(wl.w, wl.l)
            room.hull = orthoHullArea(cells, nx, nz, wl.deg)
            wallRoom(&room, cells, r.roomOf, Int32(i + 1), g, nx, nz, wl.deg)
            // no wall rectangle (open plan, walls not scanned): the smallest rectangle around its floor, so its sides
            // still show and can be dragged (the area stays the seen floor until a side is moved)
            if room.walled == 0 { room.rect = [wl.u0, wl.u0 + wl.w, wl.v0, wl.v0 + wl.l]; room.deg = wl.deg }
            r.rooms.append(room)
        }
        let main = r.roomOf.map { $0 == 1 }
        var cells = 0, otherCells = 0, edge = 0, edgeWall = 0
        for c in 0..<n {
            if main[c] { cells += 1 } else if inside[c] { otherCells += 1 }
        }
        // how closed the outline is: room-edge cells with a wall within 15 cm outward
        for c in 0..<n where main[c] {
            let x = c % nx, z = c / nx
            let border = x == 0 || z == 0 || x == nx - 1 || z == nz - 1 || !main[c - 1] || !main[c + 1] || !main[c - nx] || !main[c + nx]
            if !border { continue }
            edge += 1
            search: for dz in -3...3 {
                for dx in -3...3 {
                    let xx = x + dx, zz = z + dz
                    if xx < 0 || zz < 0 || xx >= nx || zz >= nz { continue }
                    if g[zz * nx + xx] & 7 == 3 { edgeWall += 1; break search }
                }
            }
        }
        r.area = Float(cells) * CELL * CELL
        r.other = Float(otherCells) * CELL * CELL
        r.enclosed = edge == 0 ? 0 : Float(edgeWall) / Float(edge)
        return r
    }

    /// The room bounded by its walls: in the room's own directions, each side's wall is the strongest line of wall
    /// cells near the edge of its floor; a side without one keeps the floor's edge. Inside that rectangle the cells
    /// within 60 cm of the room's floor count (an L-shaped room keeps its notch, a doorway is closed straight).
    private static func wallRoom(_ room: inout Room, _ roomCells: [Int], _ roomOf: [Int32], _ id: Int32, _ g: [UInt8],
                                 _ nx: Int, _ nz: Int, _ deg: Float) {
        let CELL = cell
        let a = deg * .pi / 180, ca = cos(a), sa = sin(a)
        var fl: [(Float, Float)] = [], wa: [(Float, Float)] = []
        var bx0 = nx, bx1 = 0, bz0 = nz, bz1 = 0
        for c in roomCells { let x = c % nx, z = c / nx; bx0 = min(bx0, x); bx1 = max(bx1, x); bz0 = min(bz0, z); bz1 = max(bz1, z) }
        let pad = 20
        for z in max(0, bz0 - pad)...min(nz - 1, bz1 + pad) {
            for x in max(0, bx0 - pad)...min(nx - 1, bx1 + pad) {
                let c = z * nx + x
                let px = Float(x) * CELL, pz = Float(z) * CELL, u = px * ca + pz * sa, v = -px * sa + pz * ca
                if roomOf[c] == id { fl.append((u, v)) } else if g[c] & 7 == 3 { wa.append((u, v)) }
            }
        }
        if fl.count < 50 { return }
        let us = fl.map(\.0).sorted(), vs = fl.map(\.1).sorted()
        let fu0 = us[Int(Float(us.count) * 0.02)], fu1 = us[Int(Float(us.count) * 0.98)]
        let fv0 = vs[Int(Float(vs.count) * 0.02)], fv1 = vs[Int(Float(vs.count) * 0.98)]
        let edges = [fu0, fu1, fv0, fv1]
        var side = [Float](repeating: 0, count: 4)
        var found = 0
        for sd in 0..<4 {
            let alongU = sd < 2   // sides 0, 1: walls across u (at u = const); 2, 3: across v
            let edge = edges[sd]
            let lo = alongU ? fv0 + 0.2 : fu0 + 0.2, hi = alongU ? fv1 - 0.2 : fu1 - 0.2
            let inward: Float = sd % 2 == 0 ? 1 : -1
            var hist = [Int](repeating: 0, count: 40)   // 5 cm bins from 60 cm inside the floor's edge to 1.4 m beyond it
            for w in wa {
                let along = alongU ? w.1 : w.0, across = alongU ? w.0 : w.1
                if along < lo || along > hi { continue }
                let b = Int((((edge - across) * inward + 1.4) / 0.05).rounded(.down))
                if b >= 0 && b < 40 { hist[b] += 1 }
            }
            // a wall stands at or beyond the floor's edge (30 cm leeway)
            var best = -1
            for b in 0..<34 where best < 0 || hist[b] > hist[best] { best = b }
            let need = Int(max(8, 0.15 * (hi - lo) / CELL))
            if best >= 0 && hist[best] >= need { side[sd] = edge - inward * ((Float(best) + 0.5) * 0.05 - 1.4); found += 1 }
            else { side[sd] = edge }
        }
        // a "wall" with lots of this room's floor beyond it (over 0.5 m²) isn't the room's wall: that side keeps the
        // floor's edge
        let extremes = [us[0], us[us.count - 1], vs[0], vs[vs.count - 1]]
        for sd in 0..<4 {
            let edge = edges[sd]
            if side[sd] == edge { continue }
            var beyond = 0
            for f in fl {
                let c = sd < 2 ? f.0 : f.1
                if sd % 2 == 0 ? c < side[sd] - 0.1 : c > side[sd] + 0.1 { beyond += 1 }
            }
            if Float(beyond) * CELL * CELL > 0.5 {
                side[sd] = sd % 2 == 0 ? min(edge, extremes[sd]) : max(edge, extremes[sd])
                found -= 1
            }
        }
        room.wallSides = found
        if found < 2 { return }   // hardly any walls: the seen floor is the better answer ("about")
        let u0 = side[0], u1 = side[1], v0 = side[2], v1 = side[3]
        if u1 - u0 < 0.5 || v1 - v0 < 0.5 { return }
        // cells of that rectangle within 60 cm of the room's floor
        let mu = Int(((u1 - u0) / CELL).rounded(.up)), mv = Int(((v1 - v0) / CELL).rounded(.up))
        var d = [Int](repeating: Int.max, count: mu * mv)
        var q = Queue()
        for f in fl {
            let iu = Int((f.0 - u0) / CELL), iv = Int((f.1 - v0) / CELL)
            if iu >= 0 && iv >= 0 && iu < mu && iv < mv && d[iv * mu + iu] != 0 { d[iv * mu + iu] = 0; q.add(iv * mu + iu) }
        }
        var cells = 0
        while let c = q.poll() {
            cells += 1
            let iu = c % mu, iv = c / mu
            if d[c] >= 12 { continue }
            for dv in -1...1 {
                for du in -1...1 {
                    let ju = iu + du, jv = iv + dv
                    if ju < 0 || jv < 0 || ju >= mu || jv >= mv { continue }
                    let e = jv * mu + ju
                    if d[e] == Int.max { d[e] = d[c] + 1; q.add(e) }
                }
            }
        }
        // plus seen floor outside that rectangle on a side without a wall; floor beyond a found wall is the next
        // room's, it doesn't count
        let hasWall = (0..<4).map { side[$0] != edges[$0] && side[$0] != extremes[$0] }
        var extra = 0
        for f in fl {
            if f.0 >= u0 && f.0 < u1 && f.1 >= v0 && f.1 < v1 { continue }
            if (f.0 < u0 && hasWall[0]) || (f.0 >= u1 && hasWall[1]) || (f.1 < v0 && hasWall[2]) || (f.1 >= v1 && hasWall[3]) { continue }
            extra += 1
        }
        cells += extra
        room.walled = Float(cells) * CELL * CELL
        room.wallSides = found
        room.rect = [u0, u1, v0, v1]; room.deg = deg
        room.w = max(u1 - u0, v1 - v0); room.l = min(u1 - u0, v1 - v0)
    }

    /// Area inside the room's outline along its own wall directions (a cell counts when room cells lie on both sides
    /// of it in its row and in its column): fills unseen floor along straight walls, an L stays an L. m².
    private static func orthoHullArea(_ roomCells: [Int], _ nx: Int, _ nz: Int, _ deg: Float) -> Float {
        let a = deg * .pi / 180, ca = cos(a), sa = sin(a)
        let m = Int(Double(nx * nx + nz * nz).squareRoot().rounded(.up)) + 2, half = m / 2
        var g = [Bool](repeating: false, count: m * m)
        let cx = Float(nx) / 2, cz = Float(nz) / 2
        func round(_ v: Float) -> Int { Int((v + 0.5).rounded(.down)) }   // Java's Math.round
        for c in roomCells {
            let x = Float(c % nx) - cx, z = Float(c / nx) - cz
            let u = round(x * ca + z * sa) + half, v = round(-x * sa + z * ca) + half
            if u >= 0 && v >= 0 && u < m && v < m { g[v * m + u] = true }
        }
        var u0 = [Int](repeating: Int.max, count: m), u1 = [Int](repeating: -1, count: m)
        var v0 = [Int](repeating: Int.max, count: m), v1 = [Int](repeating: -1, count: m)
        for v in 0..<m {
            for u in 0..<m where g[v * m + u] {
                u0[v] = min(u0[v], u); u1[v] = max(u1[v], u); v0[u] = min(v0[u], v); v1[u] = max(v1[u], v)
            }
        }
        var cells = 0
        for v in 0..<m {
            for u in 0..<m where u >= u0[v] && u <= u1[v] && v >= v0[u] && v <= v1[u] { cells += 1 }
        }
        return Float(cells) * cell * cell
    }

    /// The smallest rectangle (turned in 2° steps) around a room's cells: width, length, angle and its near corner.
    private static func minRect(_ roomCells: [Int], _ nx: Int) -> (w: Float, l: Float, deg: Float, u0: Float, v0: Float) {
        var best = Float.greatestFiniteMagnitude
        var out: (w: Float, l: Float, deg: Float, u0: Float, v0: Float) = (0, 0, 0, 0, 0)
        for deg in stride(from: 0, to: 90, by: 2) {
            let a = Float(deg) * .pi / 180, ca = cos(a), sa = sin(a)
            var u0 = Float.greatestFiniteMagnitude, u1 = -Float.greatestFiniteMagnitude
            var v0 = Float.greatestFiniteMagnitude, v1 = -Float.greatestFiniteMagnitude
            for c in roomCells {
                let x = Float(c % nx) * cell, z = Float(c / nx) * cell, u = x * ca + z * sa, v = -x * sa + z * ca
                u0 = min(u0, u); u1 = max(u1, u); v0 = min(v0, v); v1 = max(v1, v)
            }
            let w = u1 - u0 + cell, l = v1 - v0 + cell
            if w * l < best { best = w * l; out = (w, l, Float(deg), u0, v0) }
        }
        return out
    }

    private static func neighbours(_ d: Int, _ nx: Int, _ nz: Int) -> [Int] {
        let x = d % nx, z = d / nx
        var e: [Int] = []
        e.reserveCapacity(4)
        if x > 0 { e.append(d - 1) }; if x < nx - 1 { e.append(d + 1) }; if z > 0 { e.append(d - nx) }; if z < nz - 1 { e.append(d + nx) }
        return e
    }

    /// A first-in first-out queue of cell indices.
    private struct Queue {
        private var items: [Int] = []
        private var head = 0
        mutating func add(_ c: Int) { items.append(c) }
        mutating func poll() -> Int? {
            guard head < items.count else { items.removeAll(keepingCapacity: true); head = 0; return nil }
            head += 1
            return items[head - 1]
        }
    }

    // MARK: Disc steps

    /// Cells within `r` cells (a disc: dx² + dz² ≤ r²) of a set cell.
    static func dilate(_ a: [Bool], _ nx: Int, _ nz: Int, _ r: Int) -> [Bool] {
        let d2 = distance2(to: a, nx, nz)
        let r2 = Double(r * r)
        return d2.map { $0 <= r2 }
    }

    /// Cells whose whole disc of `r` cells is set (outside the grid counts as not set).
    static func erode(_ a: [Bool], _ nx: Int, _ nz: Int, _ r: Int) -> [Bool] {
        // The distance to the nearest unset cell, with a ring of unset cells around the grid.
        let px = nx + 2, pz = nz + 2
        var unset = [Bool](repeating: true, count: px * pz)
        for z in 0..<nz { for x in 0..<nx { unset[(z + 1) * px + x + 1] = !a[z * nx + x] } }
        let d2 = distance2(to: unset, px, pz)
        let r2 = Double(r * r)
        var out = [Bool](repeating: false, count: nx * nz)
        for z in 0..<nz { for x in 0..<nx { out[z * nx + x] = d2[(z + 1) * px + x + 1] > r2 } }
        return out
    }

    /// Squared distance (in cells) from each cell to the nearest set cell: Felzenszwalb and Huttenlocher's exact
    /// distance transform, a pass along the rows then along the columns. Very large where nothing is set.
    static func distance2(to a: [Bool], _ nx: Int, _ nz: Int) -> [Double] {
        let inf = 1e20
        var f = a.map { $0 ? 0 : inf }
        var line = [Double](repeating: 0, count: max(nx, nz))
        var out = [Double](repeating: 0, count: max(nx, nz))
        var v = [Int](repeating: 0, count: max(nx, nz))
        var zs = [Double](repeating: 0, count: max(nx, nz) + 1)
        func pass(_ n: Int) {
            var k = 0
            v[0] = 0; zs[0] = -inf; zs[1] = inf
            if n > 1 {
                for q in 1..<n {
                    var s: Double
                    repeat {
                        let p = v[k]
                        s = ((line[q] + Double(q * q)) - (line[p] + Double(p * p))) / Double(2 * q - 2 * p)
                        if s <= zs[k] { k -= 1 } else { break }
                    } while k >= 0
                    k += 1
                    v[k] = q; zs[k] = s; zs[k + 1] = inf
                }
            }
            k = 0
            for q in 0..<n {
                while zs[k + 1] < Double(q) { k += 1 }
                let d = Double(q - v[k])
                out[q] = d * d + line[v[k]]
            }
        }
        for z in 0..<nz {
            for x in 0..<nx { line[x] = f[z * nx + x] }
            pass(nx)
            for x in 0..<nx { f[z * nx + x] = out[x] }
        }
        for x in 0..<nx {
            for z in 0..<nz { line[z] = f[z * nx + x] }
            pass(nz)
            for z in 0..<nz { f[z * nx + x] = out[z] }
        }
        return f
    }
}
