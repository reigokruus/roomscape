import Foundation
import RoomPlan
import simd

/// One splat: a small disc lying on its surface. A zero normal means "no clear surface": draw it facing the camera.
struct SurfelRecord {
    var x: Float, y: Float, z: Float
    var nx: Float, ny: Float, nz: Float
    var radius: Float
    var rgba: UInt32
    /// Direction (radians, atan2(z, x)) of the inward normal of the RoomPlan wall this splat is on or against
    /// (within 30 cm), or `noWall`. The viewer hides it while the camera is outside that wall.
    var wallAngle: Float = SurfelRecord.noWall

    static let noWall: Float = -100
}

/// On-phone splats without training (port of Android `Surfels.java`): every scanned point becomes a disc lying
/// flat on its surface. For each point we look at its neighbours within 5 cm:
/// - the flattest direction of the neighbours (PCA) is the surface normal;
/// - how many neighbours there are sets the disc size, so sparse areas still close up and dense areas stay sharp;
/// - on clearly flat patches the point is pulled onto the fitted plane, removing depth noise without moving edges.
/// Then points near RoomPlan's walls and floor are snapped onto those planes.
enum Surfels {
    /// Neighbour radius and grid cell for 2 cm points; both scale with the scan's cell size (`build(_:voxel:)`).
    static let radius: Float = 0.05
    static let cell: Float = 0.05
    static let maxNeighbours = 48
    /// More points than this are thinned first; the density-based radius grows to keep the surfaces closed.
    static let maxSurfels = 350_000

    struct Stats { var flat = 0, curved = 0, lonely = 0, snapped = 0 }

    private static func cellKey(_ ix: Int, _ iy: Int, _ iz: Int) -> UInt64 {
        UInt64((ix + 1024) & 2047) | UInt64((iy + 1024) & 2047) << 11 | UInt64((iz + 1024) & 2047) << 22
    }

    /// - Parameter voxel: the capture's cell size (`CaptureMeta.voxelSize`): neighbourhoods and disc sizes scale
    ///   with it, so a large room captured at coarser cells still closes up.
    static func build(_ input: [LivePoint], voxel: Float = 0.02) -> (surfels: [SurfelRecord], stats: Stats) {
        let scale = max(1, voxel / 0.02)
        let radius = Self.radius * scale, cell = Self.cell * scale
        let thin = max(1, (input.count + maxSurfels - 1) / maxSurfels)
        let src = thin == 1 ? input : Swift.stride(from: 0, to: input.count, by: thin).map { input[$0] }
        let n = src.count
        var stats = Stats()
        guard n > 0 else { return ([], stats) }

        let xs = src.map(\.x), ys = src.map(\.y), zs = src.map(\.z)

        // Sort point indices by grid cell (cell key in the high bits, index in the low 21 bits).
        var keyIndex = [UInt64](repeating: 0, count: n)
        for i in 0..<n {
            keyIndex[i] = cellKey(Int((xs[i] / cell).rounded(.down)), Int((ys[i] / cell).rounded(.down)),
                                  Int((zs[i] / cell).rounded(.down))) << 21 | UInt64(i)
        }
        keyIndex.sort()
        var keys = [UInt64](), start = [Int]()
        keys.reserveCapacity(n); start.reserveCapacity(n + 1)
        var order = [Int](repeating: 0, count: n)
        for j in 0..<n {
            let k = keyIndex[j] >> 21
            if keys.last != k { keys.append(k); start.append(j) }
            order[j] = Int(keyIndex[j] & 0x1F_FFFF)
        }
        start.append(n)
        keyIndex = []

        var out = [SurfelRecord](repeating: SurfelRecord(x: 0, y: 0, z: 0, nx: 0, ny: 0, nz: 0, radius: 0, rgba: 0), count: n)
        let r2max = radius * radius

        xs.withUnsafeBufferPointer { X in
        ys.withUnsafeBufferPointer { Y in
        zs.withUnsafeBufferPointer { Z in
        keys.withUnsafeBufferPointer { K in
        start.withUnsafeBufferPointer { S in
        order.withUnsafeBufferPointer { O in
            let nb = UnsafeMutablePointer<Int>.allocate(capacity: maxNeighbours)
            let d2s = UnsafeMutablePointer<Float>.allocate(capacity: maxNeighbours)
            let a = UnsafeMutablePointer<Double>.allocate(capacity: 9) // covariance, row-major
            let v = UnsafeMutablePointer<Double>.allocate(capacity: 9) // eigenvectors in the columns
            defer { nb.deallocate(); d2s.deallocate(); a.deallocate(); v.deallocate() }
            // Everything per point is plain scalar maths and while loops: generic code (ranges, SIMD conversions)
            // is ~20x slower in Debug builds, and this loop runs ~100 neighbour checks for every point.
            var i = 0
            while i < n {
                let px = X[i], py = Y[i], pz = Z[i]
                let cx = Int((px / cell).rounded(.down)), cy = Int((py / cell).rounded(.down)),
                    cz = Int((pz / cell).rounded(.down))
                var m = 0, found = 0, worst: Float = 0, worstAt = 0
                var dc = 0
                while dc < 27 {
                    let dx = dc / 9 - 1, dy = (dc / 3) % 3 - 1, dz = dc % 3 - 1
                    dc += 1
                    guard let u = search(K, cellKey(cx + dx, cy + dy, cz + dz)) else { continue }
                    var j = S[u]
                    let end = S[u + 1]
                    while j < end {
                        let q = O[j]
                        j += 1
                        let ax = X[q] - px, ay = Y[q] - py, az = Z[q] - pz
                        let d2 = ax * ax + ay * ay + az * az
                        if d2 > r2max { continue }
                        found += 1
                        if m < maxNeighbours {
                            nb[m] = q; d2s[m] = d2
                            if d2 > worst { worst = d2; worstAt = m }
                            m += 1
                        } else if d2 < worst { // keep the closest ones
                            nb[worstAt] = q; d2s[worstAt] = d2
                            worst = 0
                            var t = 0
                            while t < m { if d2s[t] > worst { worst = d2s[t]; worstAt = t }; t += 1 }
                        }
                    }
                }

                var ox = px, oy = py, oz = pz
                var nx: Float = 0, ny: Float = 0, nz: Float = 0
                // Disc radius from density: `found` points share the pi*R^2 patch around this one.
                let spacing = (Float.pi * r2max / Float(max(1, found))).squareRoot()
                var rad = min(0.035 * scale, max(0.008 * scale, spacing * 0.75))
                if m < 5 {
                    stats.lonely += 1
                    rad = 0.012 * scale
                } else {
                    var mx = 0.0, my = 0.0, mz = 0.0
                    var t = 0
                    while t < m { let q = nb[t]; mx += Double(X[q]); my += Double(Y[q]); mz += Double(Z[q]); t += 1 }
                    mx /= Double(m); my /= Double(m); mz /= Double(m)
                    var cxx = 0.0, cxy = 0.0, cxz = 0.0, cyy = 0.0, cyz = 0.0, czz = 0.0
                    t = 0
                    while t < m {
                        let q = nb[t]
                        let ax = Double(X[q]) - mx, ay = Double(Y[q]) - my, az = Double(Z[q]) - mz
                        cxx += ax * ax; cxy += ax * ay; cxz += ax * az; cyy += ay * ay; cyz += ay * az; czz += az * az
                        t += 1
                    }
                    a[0] = cxx; a[1] = cxy; a[2] = cxz
                    a[3] = cxy; a[4] = cyy; a[5] = cyz
                    a[6] = cxz; a[7] = cyz; a[8] = czz
                    jacobi(a, v)
                    var lo = 0
                    if a[4] < a[0] { lo = 1 }
                    if a[8] < a[lo * 4] { lo = 2 }
                    let sum = max(1e-12, a[0] + a[4] + a[8])
                    let curvature = a[lo * 4] / sum // 0 = perfectly flat, 1/3 = a blob
                    if curvature < 0.22 {
                        stats.flat += 1
                        nx = Float(v[lo]); ny = Float(v[3 + lo]); nz = Float(v[6 + lo])
                        // Pull onto the local plane: fully when clearly flat, fading out towards edges and corners.
                        let w = Float(max(0, min(1, (0.22 - curvature) / 0.12)))
                        let off = (px - Float(mx)) * nx + (py - Float(my)) * ny + (pz - Float(mz)) * nz
                        ox = px - nx * off * w; oy = py - ny * off * w; oz = pz - nz * off * w
                    } else {
                        stats.curved += 1
                    }
                }
                // Keep the point's colour; the top byte (how solid it was while scanning) becomes full opacity.
                out[i] = SurfelRecord(x: ox, y: oy, z: oz, nx: nx, ny: ny, nz: nz, radius: rad,
                                      rgba: (src[i].rgba & 0x00FF_FFFF) | 0xFF00_0000)
                i += 1
            }
        }}}}}}
        return (out, stats)
    }

    private static func search(_ keys: UnsafeBufferPointer<UInt64>, _ key: UInt64) -> Int? {
        var lo = 0, hi = keys.count - 1
        while lo <= hi {
            let mid = (lo + hi) >> 1
            let k = keys[mid]
            if k == key { return mid }
            if k < key { lo = mid + 1 } else { hi = mid - 1 }
        }
        return nil
    }

    /// Eigen-decomposition of a symmetric 3x3 (row-major `a`) by Jacobi rotations, in place: afterwards the
    /// eigenvalues are on a's diagonal and the eigenvectors in the columns of `v`. Straight port of the Java.
    @inline(__always)
    private static func jacobi(_ a: UnsafeMutablePointer<Double>, _ v: UnsafeMutablePointer<Double>) {
        v[0] = 1; v[1] = 0; v[2] = 0; v[3] = 0; v[4] = 1; v[5] = 0; v[6] = 0; v[7] = 0; v[8] = 1
        var sweep = 0
        while sweep < 12 {
            sweep += 1
            if a[1] * a[1] + a[2] * a[2] + a[5] * a[5] < 1e-20 { break }
            var pq = 0
            while pq < 3 {
                let pp = pq == 2 ? 1 : 0, qq = pq == 0 ? 1 : 2
                pq += 1
                let apq = a[pp * 3 + qq]
                if abs(apq) < 1e-30 { continue }
                let theta = (a[qq * 3 + qq] - a[pp * 3 + pp]) / (2 * apq)
                var t = (theta < 0 ? -1.0 : 1.0) / (abs(theta) + (theta * theta + 1).squareRoot())
                if theta == 0 { t = 1 }
                let cs = 1 / (t * t + 1).squareRoot(), sn = t * cs
                var k = 0
                while k < 3 { // a = J^T a J
                    let akp = a[k * 3 + pp], akq = a[k * 3 + qq]
                    a[k * 3 + pp] = cs * akp - sn * akq; a[k * 3 + qq] = sn * akp + cs * akq
                    k += 1
                }
                k = 0
                while k < 3 {
                    let apk = a[pp * 3 + k], aqk = a[qq * 3 + k]
                    a[pp * 3 + k] = cs * apk - sn * aqk; a[qq * 3 + k] = sn * apk + cs * aqk
                    k += 1
                }
                k = 0
                while k < 3 {
                    let vkp = v[k * 3 + pp], vkq = v[k * 3 + qq]
                    v[k * 3 + pp] = cs * vkp - sn * vkq; v[k * 3 + qq] = sn * vkp + cs * vkq
                    k += 1
                }
            }
        }
    }

    // MARK: Snapping to RoomPlan's room

    /// Pulls splats within 6 cm of a RoomPlan wall or the floor onto that plane and gives them its normal, then
    /// nudges them 5 mm into the room so they sit just in front of the drawn wall or floor instead of flickering with it.
    static func snap(_ surfels: inout [SurfelRecord], to shell: RoomShell) -> Int {
        var snapped = 0
        let reach: Float = 0.06, nudge: Float = 0.005
        for i in surfels.indices {
            var s = surfels[i]
            let p = SIMD3(s.x, s.y, s.z), n = SIMD3(s.nx, s.ny, s.nz)
            let hasNormal = simd_length_squared(n) > 0.25
            var best: (dist: Float, point: SIMD3<Float>, normal: SIMD3<Float>)?

            if abs(p.y - shell.floorY) < reach, !hasNormal || abs(n.y) > 0.8 {
                best = (abs(p.y - shell.floorY), SIMD3(p.x, shell.floorY + nudge, p.z), SIMD3(0, 1, 0))
            }
            for wall in shell.walls {
                let d = p - wall.center
                let along = simd_dot(d, wall.right), up = simd_dot(d, wall.up), off = simd_dot(d, wall.inward)
                guard abs(off) < reach, abs(along) <= wall.halfWidth + 0.05, abs(up) <= wall.halfHeight + 0.05,
                      !hasNormal || abs(simd_dot(n, wall.inward)) > 0.8 else { continue }
                if best == nil || abs(off) < best!.dist {
                    best = (abs(off), p - wall.inward * (off - nudge), wall.inward)
                }
            }
            // Remember the nearest wall within 30 cm (snapped or not: a shelf against the wall counts too).
            var nearest: (dist: Float, wall: RoomShell.Wall)?
            for wall in shell.walls {
                let d = SIMD3(s.x, s.y, s.z) - wall.center
                let off = simd_dot(d, wall.inward)
                guard off > -0.1, off < 0.3, abs(simd_dot(d, wall.right)) <= wall.halfWidth + 0.1,
                      abs(simd_dot(d, wall.up)) <= wall.halfHeight + 0.1 else { continue }
                if nearest == nil || abs(off) < nearest!.dist { nearest = (abs(off), wall) }
            }
            if let wall = nearest?.wall {
                s.wallAngle = atan2(wall.inward.z, wall.inward.x)
                surfels[i] = s
            }
            if let best {
                s.x = best.point.x; s.y = best.point.y; s.z = best.point.z
                s.nx = best.normal.x; s.ny = best.normal.y; s.nz = best.normal.z
                surfels[i] = s
                snapped += 1
            }
        }
        return snapped
    }

    // MARK: Cache

    /// "CXS2": records carry `wallAngle`; older "CXS1" caches are rebuilt.
    private static let magic: UInt32 = 0x3253_5843 // "CXS2"

    static func write(_ surfels: [SurfelRecord], to url: URL) {
        var data = Data(capacity: 8 + surfels.count * MemoryLayout<SurfelRecord>.stride)
        withUnsafeBytes(of: magic.littleEndian) { data.append(contentsOf: $0) }
        withUnsafeBytes(of: UInt32(surfels.count).littleEndian) { data.append(contentsOf: $0) }
        surfels.withUnsafeBytes { data.append(contentsOf: $0) }
        try? data.write(to: url, options: .atomic)
    }

    static func read(from url: URL) -> [SurfelRecord]? {
        guard let data = try? Data(contentsOf: url, options: .mappedIfSafe), data.count >= 8 else { return nil }
        return data.withUnsafeBytes { raw -> [SurfelRecord]? in
            guard raw.load(fromByteOffset: 0, as: UInt32.self) == magic else { return nil }
            let stride = MemoryLayout<SurfelRecord>.stride
            let count = min(Int(raw.load(fromByteOffset: 4, as: UInt32.self)), (raw.count - 8) / stride)
            return (0..<count).map { raw.loadUnaligned(fromByteOffset: 8 + $0 * stride, as: SurfelRecord.self) }
        }
    }
}

/// RoomPlan's walls and floor as plain planes, with each wall's normal pointing into the room.
struct RoomShell {
    struct Wall {
        let center: SIMD3<Float>
        let right: SIMD3<Float>, up: SIMD3<Float>, inward: SIMD3<Float>
        let halfWidth: Float, halfHeight: Float
    }
    struct Opening {
        let wall: Wall
        let isWindow: Bool
    }

    let walls: [Wall]
    let openings: [Opening]
    let floorY: Float
    /// Floor outline in world space (y = floorY), in order.
    let floorOutline: [SIMD3<Float>]

    /// Ceiling height: the walls' typical top (the median, so one odd wall doesn't move it), or nil with no walls.
    var ceilingY: Float? {
        let tops = walls.map { $0.center.y + $0.halfHeight }.sorted()
        return tops.isEmpty ? nil : tops[tops.count / 2]
    }

    /// A room without RoomPlan's model (a scan from the Android app): its floor height, and no walls or outline
    /// unless known, so nothing is cut away from what was scanned.
    init(floorY: Float, walls: [Wall] = [], outline: [SIMD3<Float>] = []) {
        self.walls = walls
        openings = []
        self.floorY = floorY
        floorOutline = outline
    }

    init(room: CapturedRoom) {
        func plane(_ s: CapturedRoom.Surface) -> (c: SIMD3<Float>, r: SIMD3<Float>, u: SIMD3<Float>, n: SIMD3<Float>) {
            let t = s.transform
            return (SIMD3(t.columns.3.x, t.columns.3.y, t.columns.3.z),
                    simd_normalize(SIMD3(t.columns.0.x, t.columns.0.y, t.columns.0.z)),
                    simd_normalize(SIMD3(t.columns.1.x, t.columns.1.y, t.columns.1.z)),
                    simd_normalize(SIMD3(t.columns.2.x, t.columns.2.y, t.columns.2.z)))
        }
        let centers = room.walls.map { plane($0).c }
        let roomCenter = centers.isEmpty ? .zero : centers.reduce(.zero, +) / Float(centers.count)

        func wall(_ s: CapturedRoom.Surface) -> Wall {
            let (c, r, u, n) = plane(s)
            var flat = roomCenter - c
            flat.y = 0
            return Wall(center: c, right: r, up: u, inward: simd_dot(flat, n) >= 0 ? n : -n,
                        halfWidth: s.dimensions.x / 2, halfHeight: s.dimensions.y / 2)
        }
        walls = room.walls.map(wall)
        openings = room.windows.map { Opening(wall: wall($0), isWindow: true) }
            + room.doors.map { Opening(wall: wall($0), isWindow: false) }

        let wallFloor = room.walls.map { plane($0).c.y - $0.dimensions.y / 2 }.min() ?? 0
        if let floor = room.floors.first {
            let y = floor.transform.columns.3.y
            floorY = y
            floorOutline = floor.polygonCorners.map { corner in
                let w = floor.transform * SIMD4(corner.x, corner.y, corner.z, 1)
                return SIMD3(w.x, y, w.z)
            }
        } else {
            floorY = wallFloor
            // No floor polygon: use the walls' bottom edges in angular order around the room centre.
            var pts = walls.flatMap { [$0.center - $0.right * $0.halfWidth, $0.center + $0.right * $0.halfWidth] }
                .map { SIMD3($0.x, wallFloor, $0.z) }
            pts.sort { atan2($0.z - roomCenter.z, $0.x - roomCenter.x) < atan2($1.z - roomCenter.z, $1.x - roomCenter.x) }
            floorOutline = pts
        }
    }
}
