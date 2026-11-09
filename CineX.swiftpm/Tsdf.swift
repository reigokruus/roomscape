import simd

/// Volumetric fusion (TSDF) for the live scan mesh, ported from Android's `Tsdf.java`: every LiDAR depth sample votes
/// into a sparse 3D grid of "signed distance to the nearest surface". Looking at a spot again averages the votes, and
/// free space seen in front of a surface erases stale geometry. `extractMesh` pulls a triangle mesh out of the grid
/// with surface nets (one vertex per cell the surface crosses), with a per-vertex confidence from how often the
/// surface there was seen.
///
/// Fixed memory, allocated once: a hash table of voxel coordinates and per-voxel arrays. When it's full, new voxels are
/// no longer added (existing ones keep improving). Not thread-safe: use it from one queue.
final class Tsdf {
    /// Voxel size, truncation band and how far in front of a surface free space erases old geometry, metres.
    let vs: Float, trunc: Float, carve: Float
    static let maxW: Int16 = 40
    /// Voxels seen fewer times are ignored for the mesh.
    var minW: Int16 = 2

    let capacity: Int
    private let mask: Int
    private let tableKeys: UnsafeMutablePointer<UInt64>
    private let tableSlots: UnsafeMutablePointer<Int32>
    private let voxelKeys: UnsafeMutablePointer<UInt64>
    private let sdf: UnsafeMutablePointer<Float>
    private let w: UnsafeMutablePointer<Int16>
    private(set) var count = 0
    private static let empty = UInt64.max

    init(capacity: Int, voxel: Float, truncation: Float, carve: Float) {
        vs = voxel
        trunc = truncation
        self.carve = carve
        self.capacity = capacity
        var table = 1
        while table < capacity * 3 / 2 { table <<= 1 }
        mask = table - 1
        tableKeys = .allocate(capacity: table)
        tableSlots = .allocate(capacity: table)
        voxelKeys = .allocate(capacity: capacity)
        sdf = .allocate(capacity: capacity)
        w = .allocate(capacity: capacity)
        clear()
    }

    deinit {
        tableKeys.deallocate()
        tableSlots.deallocate()
        voxelKeys.deallocate()
        sdf.deallocate()
        w.deallocate()
    }

    /// Resets every voxel whose centre passes the test (tap to clear while scanning); returns how many.
    func clearWhere(_ test: (SIMD3<Float>) -> Bool) -> Int {
        var cleared = 0
        for i in 0..<count {
            let c = Self.coords(voxelKeys[i])
            let p = (SIMD3<Float>(Float(c.x), Float(c.y), Float(c.z)) + 0.5) * vs
            if test(p) { w[i] = 0; sdf[i] = 0; cleared += 1 }
        }
        return cleared
    }

    func clear() {
        tableKeys.update(repeating: Self.empty, count: mask + 1)
        count = 0
    }

    // MARK: Hashing

    @inline(__always) static func key(_ v: SIMD3<Int32>) -> UInt64 {
        (UInt64(UInt32(bitPattern: v.x &+ (1 << 20))) & 0x1FFFFF)
            | ((UInt64(UInt32(bitPattern: v.y &+ (1 << 20))) & 0x1FFFFF) << 21)
            | ((UInt64(UInt32(bitPattern: v.z &+ (1 << 20))) & 0x1FFFFF) << 42)
    }

    @inline(__always) static func coords(_ k: UInt64) -> SIMD3<Int32> {
        SIMD3(Int32(truncatingIfNeeded: k & 0x1FFFFF) - (1 << 20),
              Int32(truncatingIfNeeded: (k >> 21) & 0x1FFFFF) - (1 << 20),
              Int32(truncatingIfNeeded: (k >> 42) & 0x1FFFFF) - (1 << 20))
    }

    @inline(__always) static func hash(_ k: UInt64, _ mask: Int) -> Int {
        let h = k &* 0x9E37_79B9_7F4A_7C15
        return Int(truncatingIfNeeded: h ^ (h >> 32)) & mask
    }

    @inline(__always) private func find(_ k: UInt64) -> Int {
        var i = Self.hash(k, mask)
        while tableKeys[i] != Self.empty && tableKeys[i] != k { i = (i + 1) & mask }
        return i
    }

    @inline(__always) func index(_ v: SIMD3<Int32>) -> Int {
        let k = Self.key(v)
        let i = find(k)
        return tableKeys[i] == k ? Int(tableSlots[i]) : -1
    }

    @inline(__always) private func indexOrCreate(_ v: SIMD3<Int32>) -> Int {
        let k = Self.key(v)
        let i = find(k)
        if tableKeys[i] == k { return Int(tableSlots[i]) }
        guard count < capacity else { return -1 }
        tableKeys[i] = k
        tableSlots[i] = Int32(count)
        voxelKeys[count] = k
        sdf[count] = 0
        w[count] = 0
        count += 1
        return count - 1
    }

    @inline(__always) private func voxel(_ p: SIMD3<Float>) -> SIMD3<Int32> {
        SIMD3<Int32>((p / vs).rounded(.down))
    }

    // MARK: Fusion

    /// One depth sample: camera at `o`, surface point `p`. Updates the voxels along the ray within the band.
    func integrate(origin o: SIMD3<Float>, point p: SIMD3<Float>) {
        var dir = p - o
        let len = simd_length(dir)
        guard len > 1e-3 else { return }
        dir /= len
        // Free space in front of the surface erases stale surfaces and mid-air noise (existing voxels only). A margin
        // that grows with distance keeps depth noise from eating the real surface.
        let farS = min(carve, len - 0.25), nearS = trunc + 0.02 * len
        var s = farS
        while s > nearS {
            let i = index(voxel(p - dir * s))
            s -= vs
            guard i >= 0 else { continue }
            let wi = w[i]
            sdf[i] = (sdf[i] * Float(wi) + 1) / Float(wi + 1)
            if wi < Self.maxW { w[i] = wi + 1 }
        }
        var last = SIMD3<Int32>(.min, .min, .min)
        s = -trunc
        while s <= trunc + 1e-4 {
            let v = voxel(p + dir * s)
            s += vs * 0.5
            if v == last { continue }
            last = v
            // Signed distance from the voxel centre to the surface, along the ray (+ = in front).
            let centre = (SIMD3<Float>(v) + 0.5) * vs
            var d = (len - simd_dot(centre - o, dir)) / trunc
            if d < -1 { continue }  // too far behind the surface: unknown, don't touch
            if d > 1 { d = 1 }
            let i = d > 0.99 ? index(v) : indexOrCreate(v)  // free space only carves existing voxels
            guard i >= 0 else { continue }
            let wi = w[i]
            sdf[i] = (sdf[i] * Float(wi) + d) / Float(wi + 1)
            if wi < Self.maxW { w[i] = wi + 1 }
        }
    }

    // MARK: Mesh extraction (surface nets)

    /// Reused between extractions so a live preview doesn't allocate every time.
    final class MeshScratch {
        var positions: [SIMD3<Float>] = []
        var confidence: [Float] = []
        var triangles: [UInt32] = []
        var edges: [UInt32] = []
        fileprivate var near: [Int32] = []
        fileprivate var cellKeys: [UInt64] = []
        fileprivate var cellVerts: [Int32] = []
        fileprivate var edgeSet = Set<UInt64>()
    }

    private static let corners: [SIMD3<Int32>] = [[0, 0, 0], [1, 0, 0], [0, 1, 0], [1, 1, 0],
                                                  [0, 0, 1], [1, 0, 1], [0, 1, 1], [1, 1, 1]]
    private static let cubeEdges: [(Int, Int)] = [(0, 1), (2, 3), (4, 5), (6, 7), (0, 2), (1, 3),
                                                  (4, 6), (5, 7), (0, 4), (1, 5), (2, 6), (3, 7)]

    @inline(__always) private func good(_ i: Int) -> Bool { i >= 0 && w[i] >= minW }

    /// Builds the mesh of the surface within `radius` of `centre` into `out`: positions, a 0…1 confidence per vertex
    /// (the fewest times any of its corners was seen, over `solidWeight`), triangles, and the triangles' edges (each
    /// once). Returns the triangle count.
    @discardableResult
    func extractMesh(near centre: SIMD3<Float>, radius: Float, solidWeight: Float, into out: MeshScratch) -> Int {
        out.positions.removeAll(keepingCapacity: true)
        out.confidence.removeAll(keepingCapacity: true)
        out.triangles.removeAll(keepingCapacity: true)
        out.edges.removeAll(keepingCapacity: true)
        out.edgeSet.removeAll(keepingCapacity: true)
        out.near.removeAll(keepingCapacity: true)

        // The voxels near the camera that are seen well enough.
        let c = centre / vs, r = radius / vs, r2 = r * r
        for i in 0..<count where w[i] >= minW {
            let d = SIMD3<Float>(Self.coords(voxelKeys[i])) + 0.5 - c
            if simd_length_squared(d) <= r2 { out.near.append(Int32(i)) }
        }
        guard !out.near.isEmpty else { return 0 }

        var table = 16
        while table < out.near.count * 2 { table <<= 1 }
        let cmask = table - 1
        if out.cellKeys.count != table {
            out.cellKeys = [UInt64](repeating: Self.empty, count: table)
            out.cellVerts = [Int32](repeating: 0, count: table)
        } else {
            out.cellKeys.withUnsafeMutableBufferPointer { $0.update(repeating: Self.empty) }
        }
        @inline(__always) func cellSlot(_ k: UInt64) -> Int {
            var s = Self.hash(k, cmask)
            while out.cellKeys[s] != Self.empty && out.cellKeys[s] != k { s = (s + 1) & cmask }
            return s
        }

        // 1) One vertex per cell with a sign change among its 8 well-seen corners.
        var ci = [Int](repeating: 0, count: 8)
        for i32 in out.near {
            let i = Int(i32)
            let v = Self.coords(voxelKeys[i])
            var ok = true, pos = false, neg = false
            for k in 0..<8 {
                let j = k == 0 ? i : index(v &+ Self.corners[k])
                if !good(j) { ok = false; break }
                ci[k] = j
                if sdf[j] >= 0 { pos = true } else { neg = true }
            }
            guard ok, pos, neg else { continue }
            var sum = SIMD3<Float>.zero
            var crossings = 0
            for (e0, e1) in Self.cubeEdges {
                let a = sdf[ci[e0]], b = sdf[ci[e1]]
                if (a >= 0) == (b >= 0) || abs(a - b) > 1.6 { continue }  // +1 next to -1: truncation artefact
                let t = a / (a - b)
                let p0 = SIMD3<Float>(Self.corners[e0]), p1 = SIMD3<Float>(Self.corners[e1])
                sum += p0 + (p1 - p0) * t
                crossings += 1
            }
            guard crossings > 0 else { continue }
            var wmin = Self.maxW
            for k in 0..<8 { wmin = min(wmin, w[ci[k]]) }
            let k = Self.key(v)
            let s = cellSlot(k)
            out.cellKeys[s] = k
            out.cellVerts[s] = Int32(out.positions.count)
            out.positions.append((SIMD3<Float>(v) + 0.5 + sum / Float(crossings)) * vs)
            out.confidence.append(min(1, Float(wmin) / solidWeight))
        }

        // 2) A quad for every voxel edge that crosses the surface, joining the 4 cells around that edge.
        var q = [UInt32](repeating: 0, count: 4)
        for i32 in out.near {
            let i = Int(i32)
            let v = Self.coords(voxelKeys[i])
            for axis in 0..<3 {
                var step = SIMD3<Int32>.zero
                step[axis] = 1
                let j = index(v &+ step)
                guard good(j) else { continue }
                let a = sdf[i], b = sdf[j]
                if (a >= 0) == (b >= 0) || abs(a - b) > 1.6 { continue }
                var found = true
                for k in 0..<4 {
                    var cell = v
                    let o1 = -Int32(k & 1), o2 = -Int32((k >> 1) & 1)
                    switch axis {
                    case 0: cell.y += o1; cell.z += o2
                    case 1: cell.x += o1; cell.z += o2
                    default: cell.x += o1; cell.y += o2
                    }
                    let ck = Self.key(cell)
                    let s = cellSlot(ck)
                    if out.cellKeys[s] != ck { found = false; break }
                    q[k] = UInt32(out.cellVerts[s])
                }
                guard found else { continue }
                // Cells (0,0), (1,0), (0,1), (1,1) around the edge: two triangles, and their edges including the
                // diagonal, so the wireframe reads as triangles (as on Android) rather than a grid of squares.
                out.triangles.append(contentsOf: [q[0], q[1], q[3], q[0], q[3], q[2]])
                for (x, y) in [(q[0], q[1]), (q[1], q[3]), (q[3], q[2]), (q[2], q[0]), (q[0], q[3])] {
                    let lo = min(x, y), hi = max(x, y)
                    if out.edgeSet.insert(UInt64(lo) << 32 | UInt64(hi)).inserted {
                        out.edges.append(lo)
                        out.edges.append(hi)
                    }
                }
            }
        }
        return out.triangles.count / 3
    }
}
