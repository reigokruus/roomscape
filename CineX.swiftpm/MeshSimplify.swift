import simd

/// Fewer triangles where the surface is flat, the same shape where it bends (rk 10 Nov: the 1.9 cm close-up surface
/// shows the 3 cm bars as rods but is ~116k triangles for one 3 m area). Quadric edge collapse (Garland & Heckbert):
/// every corner keeps the planes of the triangles around it; an edge is merged into one corner, placed where it
/// strays least from all those planes, cheapest first, until the next one would move the surface more than
/// `maxError`. Open borders keep extra planes so they don't shrink; a merge that would flip a triangle is skipped.
/// Shared by the app (rebuild from recording) and the Mac harness.
enum MeshSimplify {
    /// Symmetric 4×4 quadric: a² ab ac ad b² bc bd c² cd d².
    struct Quadric {
        var m = [Double](repeating: 0, count: 10)
        init() {}
        init(plane n: SIMD3<Double>, d: Double, weight: Double = 1) {
            let a = n.x, b = n.y, c = n.z
            m = [a * a, a * b, a * c, a * d, b * b, b * c, b * d, c * c, c * d, d * d].map { $0 * weight }
        }
        static func + (l: Quadric, r: Quadric) -> Quadric { var q = Quadric(); for i in 0..<10 { q.m[i] = l.m[i] + r.m[i] }; return q }
        func error(_ v: SIMD3<Double>) -> Double {
            let x = v.x, y = v.y, z = v.z
            return m[0] * x * x + 2 * m[1] * x * y + 2 * m[2] * x * z + 2 * m[3] * x
                + m[4] * y * y + 2 * m[5] * y * z + 2 * m[6] * y + m[7] * z * z + 2 * m[8] * z + m[9]
        }
        /// The point with the least error, or nil when the planes don't pin one down (flat or straight).
        func optimum() -> SIMD3<Double>? {
            let a = simd_double3x3(SIMD3(m[0], m[1], m[2]), SIMD3(m[1], m[4], m[5]), SIMD3(m[2], m[5], m[7]))
            let det = a.determinant
            guard abs(det) > 1e-12 else { return nil }
            return -(a.inverse * SIMD3(m[3], m[6], m[8]))
        }
    }

    private struct Candidate: Comparable {
        var cost: Double, u: Int32, v: Int32, stampU: Int32, stampV: Int32, target: SIMD3<Double>
        static func < (a: Candidate, b: Candidate) -> Bool { a.cost < b.cost }
        static func == (a: Candidate, b: Candidate) -> Bool { a.cost == b.cost }
    }

    /// Simplifies until merging any more would move the surface more than `maxError` metres (or `targetTriangles` is
    /// reached). Returns the new positions and triangles (unused corners dropped).
    static func simplify(positions: [SIMD3<Float>], triangles: [UInt32], maxError: Float,
                         targetTriangles: Int = 0) -> (positions: [SIMD3<Float>], triangles: [UInt32]) {
        let nv = positions.count, nt = triangles.count / 3
        guard nv > 3, nt > 1 else { return (positions, triangles) }
        var pos = positions.map { SIMD3<Double>($0) }
        var tri = triangles.map { Int32($0) }
        var faceAlive = [Bool](repeating: true, count: nt)
        var vertAlive = [Bool](repeating: true, count: nv)
        var stamp = [Int32](repeating: 0, count: nv)
        var faces = [[Int32]](repeating: [], count: nv)
        for t in 0..<nt { for c in 0..<3 { faces[Int(tri[t * 3 + c])].append(Int32(t)) } }

        // Corner quadrics from their triangles' planes; borders get a perpendicular plane, weighted, so they hold.
        var q = [Quadric](repeating: Quadric(), count: nv)
        var edgeCount: [UInt64: Int32] = [:]
        func key(_ a: Int32, _ b: Int32) -> UInt64 { UInt64(UInt32(min(a, b))) << 32 | UInt64(UInt32(max(a, b))) }
        for t in 0..<nt {
            let i = (Int(tri[t * 3]), Int(tri[t * 3 + 1]), Int(tri[t * 3 + 2]))
            let n0 = simd_cross(pos[i.1] - pos[i.0], pos[i.2] - pos[i.0])
            let l = simd_length(n0)
            guard l > 1e-15 else { continue }
            let n = n0 / l
            let plane = Quadric(plane: n, d: -simd_dot(n, pos[i.0]))
            q[i.0] = q[i.0] + plane; q[i.1] = q[i.1] + plane; q[i.2] = q[i.2] + plane
            for (a, b) in [(i.0, i.1), (i.1, i.2), (i.2, i.0)] { edgeCount[key(Int32(a), Int32(b)), default: 0] += 1 }
        }
        for t in 0..<nt {
            let i = [Int(tri[t * 3]), Int(tri[t * 3 + 1]), Int(tri[t * 3 + 2])]
            let n0 = simd_cross(pos[i[1]] - pos[i[0]], pos[i[2]] - pos[i[0]])
            guard simd_length(n0) > 1e-15 else { continue }
            let n = simd_normalize(n0)
            for e in 0..<3 {
                let a = i[e], b = i[(e + 1) % 3]
                guard edgeCount[key(Int32(a), Int32(b))] == 1 else { continue }
                let along = pos[b] - pos[a]
                guard simd_length(along) > 1e-12 else { continue }
                let side = simd_normalize(simd_cross(along, n))
                let border = Quadric(plane: side, d: -simd_dot(side, pos[a]), weight: 100)
                q[a] = q[a] + border; q[b] = q[b] + border
            }
        }

        // Candidates: every edge once.
        var heap: [Candidate] = []
        heap.reserveCapacity(edgeCount.count)
        func candidate(_ u: Int32, _ v: Int32) -> Candidate {
            let qq = q[Int(u)] + q[Int(v)]
            var best = (pos[Int(u)] + pos[Int(v)]) / 2
            var cost = qq.error(best)
            if let o = qq.optimum(), simd_distance(o, best) < simd_distance(pos[Int(u)], pos[Int(v)]) * 2 {
                let c = qq.error(o); if c < cost { cost = c; best = o }
            }
            for p in [pos[Int(u)], pos[Int(v)]] { let c = qq.error(p); if c < cost { cost = c; best = p } }
            return Candidate(cost: max(0, cost), u: u, v: v, stampU: stamp[Int(u)], stampV: stamp[Int(v)], target: best)
        }
        func push(_ c: Candidate) {
            heap.append(c)
            var i = heap.count - 1
            while i > 0 { let p = (i - 1) / 2; if heap[p] <= heap[i] { break }; heap.swapAt(p, i); i = p }
        }
        func pop() -> Candidate? {
            guard !heap.isEmpty else { return nil }
            let top = heap[0]
            let last = heap.removeLast()
            if !heap.isEmpty {
                heap[0] = last
                var i = 0
                while true {
                    let l = 2 * i + 1, r = l + 1
                    var m = i
                    if l < heap.count, heap[l] < heap[m] { m = l }
                    if r < heap.count, heap[r] < heap[m] { m = r }
                    if m == i { break }
                    heap.swapAt(i, m); i = m
                }
            }
            return top
        }
        for k in edgeCount.keys { push(candidate(Int32(truncatingIfNeeded: k >> 32), Int32(truncatingIfNeeded: k & 0xFFFF_FFFF))) }

        let limit = Double(maxError) * Double(maxError)
        var alive = nt
        while alive > targetTriangles, let c = pop() {
            guard c.cost <= limit else { break }
            let u = Int(c.u), v = Int(c.v)
            guard vertAlive[u], vertAlive[v], stamp[u] == c.stampU, stamp[v] == c.stampV else { continue }
            // Would any remaining triangle around u or v flip (or collapse to a sliver)?
            var flips = false
            for (w, other) in [(u, v), (v, u)] {
                for f in faces[w] where faceAlive[Int(f)] {
                    let t = Int(f) * 3
                    let ids = [Int(tri[t]), Int(tri[t + 1]), Int(tri[t + 2])]
                    if ids.contains(other) { continue }
                    let p = ids.map { $0 == w ? c.target : pos[$0] }
                    let before = simd_cross(pos[ids[1]] - pos[ids[0]], pos[ids[2]] - pos[ids[0]])
                    let after = simd_cross(p[1] - p[0], p[2] - p[0])
                    let lb = simd_length(before), la = simd_length(after)
                    if lb < 1e-15 { continue }
                    if la < 1e-15 || simd_dot(before / lb, after / la) < 0.3 { flips = true; break }
                }
                if flips { break }
            }
            if flips { continue }
            // Merge v into u.
            pos[u] = c.target
            q[u] = q[u] + q[v]
            vertAlive[v] = false
            stamp[u] += 1
            for f in faces[v] where faceAlive[Int(f)] {
                let t = Int(f) * 3
                if tri[t] == Int32(u) || tri[t + 1] == Int32(u) || tri[t + 2] == Int32(u) {
                    faceAlive[Int(f)] = false; alive -= 1
                } else {
                    for k in 0..<3 where tri[t + k] == Int32(v) { tri[t + k] = Int32(u) }
                    faces[u].append(f)
                }
            }
            faces[v] = []
            faces[u] = faces[u].filter { faceAlive[Int($0)] }
            // New candidates for the edges around u.
            var seen = Set<Int32>()
            for f in faces[u] {
                let t = Int(f) * 3
                for k in 0..<3 { let w = tri[t + k]; if w != Int32(u), seen.insert(w).inserted { push(candidate(Int32(u), w)) } }
            }
        }

        // Compact.
        var remap = [Int32](repeating: -1, count: nv)
        var outPos: [SIMD3<Float>] = [], outTri: [UInt32] = []
        outTri.reserveCapacity(alive * 3)
        for t in 0..<nt where faceAlive[t] {
            for k in 0..<3 {
                let v = Int(tri[t * 3 + k])
                if remap[v] < 0 { remap[v] = Int32(outPos.count); outPos.append(SIMD3<Float>(pos[v])) }
                outTri.append(UInt32(remap[v]))
            }
        }
        return (outPos, outTri)
    }
}
