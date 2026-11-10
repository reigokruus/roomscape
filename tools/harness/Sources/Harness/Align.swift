import Foundation
import simd

/// Lines up an S24 scan with an iPhone scan of the same room and shows where the S24 has surface the iPhone lacks
/// (rk 9 Nov: "the S24 has some parts filled in even better than the iPhone").
/// `harness align <iPhone capture folder> <S24 scan folder>`: floors matched, then yaw, scale and position searched on
/// a floor-plan grid, refined with ICP; prints the S24-only areas and writes a top view (grey = iPhone, red = S24 only).
enum Align {
    static func readPly(_ url: URL) -> [SIMD3<Float>] {
        guard let data = try? Data(contentsOf: url), let end = data.range(of: Data("end_header\n".utf8)) else { return [] }
        let header = String(decoding: data[..<end.lowerBound], as: UTF8.self)
        guard let line = header.split(separator: "\n").first(where: { $0.hasPrefix("element vertex") }), let n = Int(line.split(separator: " ")[2]) else { return [] }
        let stride = 15 // x y z float, r g b uchar
        let base = end.upperBound
        return data.withUnsafeBytes { raw in
            (0..<n).map { i in
                let o = base + i * stride
                return SIMD3(raw.loadUnaligned(fromByteOffset: o, as: Float.self), raw.loadUnaligned(fromByteOffset: o + 4, as: Float.self),
                             raw.loadUnaligned(fromByteOffset: o + 8, as: Float.self))
            }
        }
    }

    static func floorY(_ p: [SIMD3<Float>]) -> Float {
        let ys = p.map(\.y).sorted()
        return ys.isEmpty ? 0 : ys[ys.count / 50]
    }

    static func run(iphone: URL, s24: URL, out: URL) {
        let a0 = Replay.readMesh(iphone.appendingPathComponent("mesh.bin")) ?? []
        var b0 = readPly(s24.appendingPathComponent("surface.ply"))
        // KF_POINTS=1: the S24's geometry from its keyframes' depth instead (DEPTH_FIX="k,c": corrected as (raw - c) / k).
        if ProcessInfo.processInfo.environment["KF_POINTS"] == "1", let data = try? Data(contentsOf: s24.appendingPathComponent("kf.bin")) {
            let fix = (ProcessInfo.processInfo.environment["DEPTH_FIX"] ?? "1,0").split(separator: ",").compactMap { Float($0) }
            let k = fix.count == 2 ? fix[0] : 1, c = fix.count == 2 ? fix[1] : 0
            var o = 0, pts: [SIMD3<Float>] = []
            func f() -> Float { let v = UInt32(bigEndian: data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: o, as: UInt32.self) }); o += 4; return Float(bitPattern: v) }
            func i32() -> Int { let v = Int32(bigEndian: data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: o, as: Int32.self) }); o += 4; return Int(v) }
            let nk = i32()
            for _ in 0..<nk {
                o += 64
                var pose = [Float](); for _ in 0..<16 { pose.append(f()) }
                o += 25
                let dw = i32(), dh = i32(); let dfx = f(), dfy = f(), dcx = f(), dcy = f()
                guard dw > 0 else { continue }
                let M = simd_float4x4(SIMD4(pose[0], pose[1], pose[2], pose[3]), SIMD4(pose[4], pose[5], pose[6], pose[7]),
                                      SIMD4(pose[8], pose[9], pose[10], pose[11]), SIMD4(pose[12], pose[13], pose[14], pose[15]))
                for v in stride(from: 1, to: dh - 1, by: 2) { for u in stride(from: 1, to: dw - 1, by: 2) {
                    let mm = Float(UInt16(bigEndian: data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: o + (v * dw + u) * 2, as: UInt16.self) }))
                    guard mm > 300, mm < 4000 else { continue }
                    let d = (mm / 1000 - c) / k
                    let pw = M * SIMD4((Float(u) + 0.5 - dcx) * d / dfx, (dcy - (Float(v) + 0.5)) * d / dfy, -d, 1)
                    pts.append(SIMD3(pw.x, pw.y, pw.z))
                } }
                o += dw * dh * 2
            }
            print("S24 geometry from \(nk) keyframes' depth: \(pts.count) points, depth fix k \(k) c \(c)")
            b0 = pts
        }
        guard !a0.isEmpty, !b0.isEmpty else { print("missing meshes (\(a0.count), \(b0.count))"); return }
        // Floors at 0.
        let fa = floorY(a0), fb = floorY(b0)
        let a = a0.map { $0 - SIMD3(0, fa, 0) }, b = b0.map { $0 - SIMD3(0, fb, 0) }
        print(String(format: "iPhone floor at y %.2f (heights below are above the floor; add this for iPhone coordinates)", fa))
        // Floor plans of the walls and furniture (0.3–2.0 m up), 10 cm cells.
        func plan(_ p: [SIMD3<Float>]) -> Set<SIMD2<Int32>> {
            Set(p.filter { $0.y > 0.3 && $0.y < 2.0 }.map { SIMD2(Int32(($0.x / 0.1).rounded(.down)), Int32(($0.z / 0.1).rounded(.down))) })
        }
        let pa = plan(a)
        let ca = a.reduce(.zero, +) / Float(a.count), cb = b.reduce(.zero, +) / Float(b.count)
        var best = (score: -1, yaw: Float(0), scale: Float(1), t: SIMD2<Float>.zero)
        let band = b.filter { $0.y > 0.3 && $0.y < 2.0 }
        let sample = Array(band.enumerated().filter { $0.offset % max(1, band.count / 800) == 0 }.map(\.element))
        for scale in [Float(0.86), 0.88, 0.90, 0.92, 0.94, 0.96, 0.97, 0.98, 0.99, 1.0, 1.01, 1.02, 1.04] {
            for deg in stride(from: Float(0), to: 360, by: 4) {
                let r = deg * .pi / 180, c = cos(r), s = sin(r)
                // Rotate/scale about the S24's centre, then search the shift on the grid around the iPhone's centre.
                let moved = sample.map { q -> SIMD2<Float> in
                    let x = (q.x - cb.x) * scale, z = (q.z - cb.z) * scale
                    return SIMD2(c * x - s * z, s * x + c * z)
                }
                for dx in stride(from: Float(-1.5), through: 1.5, by: 0.15) { for dz in stride(from: Float(-1.5), through: 1.5, by: 0.15) {
                    let t = SIMD2(ca.x + dx, ca.z + dz)
                    var score = 0
                    for m in moved where pa.contains(SIMD2(Int32(((m.x + t.x) / 0.1).rounded(.down)), Int32(((m.y + t.y) / 0.1).rounded(.down)))) { score += 1 }
                    if score > best.score { best = (score, deg, scale, t) }
                } }
            }
        }
        let r = best.yaw * .pi / 180
        func transform(_ q: SIMD3<Float>) -> SIMD3<Float> {
            let x = (q.x - cb.x) * best.scale, z = (q.z - cb.z) * best.scale
            return SIMD3(cos(r) * x - sin(r) * z + best.t.x, q.y * best.scale, sin(r) * x + cos(r) * z + best.t.y)
        }
        var bt = b.map(transform)
        // ICP (translation and yaw only, a few rounds) on points with a partner within 15 cm.
        var grid: [SIMD3<Int32>: [SIMD3<Float>]] = [:]
        for p in a { grid[SIMD3<Int32>((p / 0.15).rounded(.down)), default: []].append(p) }
        func nearest(_ p: SIMD3<Float>) -> SIMD3<Float>? {
            let k = SIMD3<Int32>((p / 0.15).rounded(.down))
            var bestP: SIMD3<Float>?, bestD = Float(0.15 * 0.15)
            for dx: Int32 in -1...1 { for dy: Int32 in -1...1 { for dz: Int32 in -1...1 {
                for q in grid[k &+ SIMD3(dx, dy, dz)] ?? [] { let d = simd_distance_squared(p, q); if d < bestD { bestD = d; bestP = q } }
            } } }
            return bestP
        }
        for _ in 0..<8 {
            var shift = SIMD3<Float>.zero, n = 0
            for (i, p) in bt.enumerated() where i % 5 == 0 { if let q = nearest(p) { shift += q - p; n += 1 } }
            guard n > 0 else { break }
            bt = bt.map { $0 + shift / Float(n) }
        }
        // Where the S24 has surface the iPhone lacks (no iPhone corner within 6 cm).
        var ag = Set<SIMD3<Int32>>()
        for p in a { ag.insert(SIMD3<Int32>((p / 0.06).rounded(.down))) }
        func covered(_ p: SIMD3<Float>) -> Bool {
            let k = SIMD3<Int32>((p / 0.06).rounded(.down))
            for dx: Int32 in -1...1 { for dy: Int32 in -1...1 { for dz: Int32 in -1...1 where ag.contains(k &+ SIMD3(dx, dy, dz)) { return true } } }
            return false
        }
        let onlyS24 = bt.filter { !covered($0) }
        var matched = 0
        for (i, p) in bt.enumerated() where i % 3 == 0 && nearest(p) != nil { matched += 1 }
        print(String(format: "aligned: yaw %.0f°, S24 scale %.2f, %d%% of S24 surface within 15 cm of the iPhone's", best.yaw, best.scale, matched * 300 / max(1, bt.count)))
        print(String(format: "S24-only surface: %d of %d corners (%.1f%%)", onlyS24.count, bt.count, Float(onlyS24.count) * 100 / Float(bt.count)))
        // The biggest S24-only clusters (30 cm blocks): where they are and how high.
        var blocks: [SIMD3<Int32>: Int] = [:]
        for p in onlyS24 { blocks[SIMD3<Int32>((p / 0.3).rounded(.down)), default: 0] += 1 }
        for (k, n) in blocks.sorted(by: { $0.value > $1.value }).prefix(8) {
            let c = (SIMD3<Float>(k) + 0.5) * 0.3
            print(String(format: "  %5d S24-only corners around x %.1f, y %.1f (height), z %.1f", n, c.x, c.y, c.z))
        }
        // Top view: iPhone grey, S24-only red; 2 cm per pixel.
        let all = a + onlyS24
        let lo = all.reduce(SIMD3<Float>(repeating: .infinity)) { simd_min($0, $1) }, hi = all.reduce(SIMD3<Float>(repeating: -.infinity)) { simd_max($0, $1) }
        let w = Int((hi.x - lo.x) / 0.02) + 1, h = Int((hi.z - lo.z) / 0.02) + 1
        var px = [UInt8](repeating: 255, count: w * h * 4)
        func put(_ p: SIMD3<Float>, _ c: (UInt8, UInt8, UInt8)) {
            let x = Int((p.x - lo.x) / 0.02), y = Int((p.z - lo.z) / 0.02)
            guard x >= 0, y >= 0, x < w, y < h else { return }
            let o = (y * w + x) * 4; px[o] = c.0; px[o + 1] = c.1; px[o + 2] = c.2; px[o + 3] = 255
        }
        for p in a where p.y < 2.2 { put(p, (150, 150, 150)) }
        for p in onlyS24 where p.y < 2.2 { put(p, (220, 40, 40)) }
        Images.png(px, width: w, height: h, to: out)
        print("top view: \(out.path)")
        // ALIGN_DEPTH=1: where the S24's depth disagrees with the iPhone's surface. Each S24 keyframe's depth pixel, moved
        // with the alignment (incl. its scale), is followed along its ray to the iPhone surface: S24 depth vs true depth.
        if ProcessInfo.processInfo.environment["ALIGN_DEPTH"] == "1" {
            let shiftD = bt.isEmpty ? SIMD3<Float>.zero : bt[0] - transform(b[0])
            func toI(_ q: SIMD3<Float>) -> SIMD3<Float> { transform(q - SIMD3(0, fb, 0)) + shiftD }
            var occ = Set<SIMD3<Int32>>()
            for p in a { let k = SIMD3<Int32>((p / 0.02).rounded(.down)); for dx: Int32 in -1...1 { for dy: Int32 in -1...1 { for dz: Int32 in -1...1 { occ.insert(k &+ SIMD3(dx, dy, dz)) } } } }
            var pairs: [(Float, Float)] = []   // (S24 depth along the ray, true distance)
            if let data = try? Data(contentsOf: s24.appendingPathComponent("kf.bin")) {
                var o = 0
                func f() -> Float { let v = UInt32(bigEndian: data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: o, as: UInt32.self) }); o += 4; return Float(bitPattern: v) }
                func i32() -> Int { let v = Int32(bigEndian: data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: o, as: Int32.self) }); o += 4; return Int(v) }
                let nk = i32()
                for _ in 0..<nk {
                    var view = [Float](), pose = [Float]()
                    for _ in 0..<16 { view.append(f()) }
                    for _ in 0..<16 { pose.append(f()) }
                    o += 24; o += 1   // fx fy cx cy w h, closeUp
                    let dw = i32(), dh = i32()
                    let dfx = f(), dfy = f(), dcx = f(), dcy = f()
                    var dep = [UInt16](repeating: 0, count: dw * dh)
                    for j in 0..<(dw * dh) { dep[j] = UInt16(bigEndian: data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: o + j * 2, as: UInt16.self) }) }
                    o += dw * dh * 2
                    guard dw > 0 else { continue }
                    let M = simd_float4x4(SIMD4(pose[0], pose[1], pose[2], pose[3]), SIMD4(pose[4], pose[5], pose[6], pose[7]),
                                          SIMD4(pose[8], pose[9], pose[10], pose[11]), SIMD4(pose[12], pose[13], pose[14], pose[15]))
                    let c = toI(SIMD3(M.columns.3.x, M.columns.3.y, M.columns.3.z))
                    for v in stride(from: 2, to: dh - 2, by: 3) { for u in stride(from: 2, to: dw - 2, by: 3) {
                        let mm = Float(dep[v * dw + u]); guard mm > 300, mm < 4000 else { continue }
                        let d = mm / 1000
                        let pc = SIMD4((Float(u) + 0.5 - dcx) * d / dfx, (dcy - (Float(v) + 0.5)) * d / dfy, -d, 1)
                        let pw4 = M * pc
                        let p = toI(SIMD3(pw4.x, pw4.y, pw4.z))
                        let dir = simd_normalize(p - c), ds = simd_distance(p, c)
                        var t = ds * 0.6, hit: Float = -1
                        while t < ds * 1.5 { if occ.contains(SIMD3<Int32>(((c + dir * t) / 0.02).rounded(.down))) { hit = t; break }; t += 0.01 }
                        if hit > 0 { pairs.append((ds, hit)) }
                    } }
                }
            }
            let ratios = pairs.map { $0.0 / $0.1 }.sorted()
            if !ratios.isEmpty {
                let med = ratios[ratios.count / 2]
                print(String(format: "depth check: %d S24 depth samples hit the iPhone surface; S24 depth / true distance: median %.3f (25%% %.3f, 75%% %.3f)", ratios.count, med, ratios[ratios.count / 4], ratios[ratios.count * 3 / 4]))
                for (lo, hi) in [(0.3, 1.0), (1.0, 1.5), (1.5, 2.0), (2.0, 3.0), (3.0, 4.0)] as [(Float, Float)] {
                    let r = pairs.filter { $0.1 >= lo && $0.1 < hi }.map { $0.0 / $0.1 }.sorted()
                    if r.count > 20 { print(String(format: "  true %.1f-%.1f m: %6d samples, median ratio %.3f", lo, hi, r.count, r[r.count / 2])) }
                }
            } else { print("depth check: no samples") }
        }
        // ALIGN_LOOK="x,y,z;x,y,z": the two surfaces from the same camera, looking at each spot (iPhone coordinates)
        // from 1.6 m towards the room's middle, 1.5 m above the floor: harness-out/look-<n>-iphone.png / -s24.png.
        guard let looks = ProcessInfo.processInfo.environment["ALIGN_LOOK"] else { return }
        let am = readMeshTriangles(iphone.appendingPathComponent("mesh.bin")), bf = readPlyFaces(s24.appendingPathComponent("surface.ply"))
        let aPos = am.positions.map { $0 - SIMD3(0, fa, 0) }
        let bPos = b.map(transform).map { $0 + (bt.first.map { _ in SIMD3<Float>.zero } ?? .zero) }
        // The ICP shift: the same as bt minus the plain transform (bt was shifted as a whole).
        let shift = bt.isEmpty ? SIMD3<Float>.zero : bt[0] - transform(b[0])
        let bAligned = bPos.map { $0 + shift }
        let k = simd_float3x3(SIMD3(500, 0, 0), SIMD3(0, 500, 0), SIMD3(320, 240, 1))
        for (n, spec) in looks.split(separator: ";").enumerated() {
            let v = spec.split(separator: ",").compactMap { Float($0) }
            guard v.count == 3 else { continue }
            let target = SIMD3(v[0], v[1] - fa, v[2])
            var away = SIMD3(ca.x - target.x, 0, ca.z - target.z)
            away = simd_length(away) > 0.01 ? simd_normalize(away) : SIMD3(0, 0, 1)
            let eye = SIMD3(target.x, 1.5, target.z) + away * 1.6
            let f = simd_normalize(target - eye), z = -f
            let x = simd_normalize(simd_cross(SIMD3<Float>(0, 1, 0), z)), y = simd_cross(z, x)
            let pose = simd_float4x4(SIMD4(x, 0), SIMD4(y, 0), SIMD4(z, 0), SIMD4(eye, 1))
            Images.renderFromCamera(aPos, am.triangles, pose: pose, k: k, imageWidth: 640, imageHeight: 480, to: URL(fileURLWithPath: "harness-out/look-\(n)-iphone.png"))
            Images.renderFromCamera(bAligned, bf, pose: pose, k: k, imageWidth: 640, imageHeight: 480, to: URL(fileURLWithPath: "harness-out/look-\(n)-s24.png"))
            print(String(format: "look %d at (%.2f, %.2f, %.2f): harness-out/look-%d-iphone.png / -s24.png", n, v[0], v[1], v[2], n))
        }
    }

    static func readMeshTriangles(_ url: URL) -> (positions: [SIMD3<Float>], triangles: [UInt32]) {
        guard let data = try? Data(contentsOf: url), data.count >= 12, data.prefix(4) == Data("MSH1".utf8) else { return ([], []) }
        return data.withUnsafeBytes { raw in
            let nv = Int(raw.loadUnaligned(fromByteOffset: 4, as: UInt32.self)), ni = Int(raw.loadUnaligned(fromByteOffset: 8, as: UInt32.self))
            let pos = (0..<nv).map { i in SIMD3(raw.loadUnaligned(fromByteOffset: 12 + i * 12, as: Float.self), raw.loadUnaligned(fromByteOffset: 16 + i * 12, as: Float.self), raw.loadUnaligned(fromByteOffset: 20 + i * 12, as: Float.self)) }
            let base = 12 + nv * 12
            return (pos, (0..<ni).map { raw.loadUnaligned(fromByteOffset: base + $0 * 4, as: UInt32.self) })
        }
    }

    /// The faces of an Android surface.ply (triangles: a uchar 3 and three ints each).
    static func readPlyFaces(_ url: URL) -> [UInt32] {
        guard let data = try? Data(contentsOf: url), let end = data.range(of: Data("end_header\n".utf8)) else { return [] }
        let header = String(decoding: data[..<end.lowerBound], as: UTF8.self)
        func count(_ name: String) -> Int { header.split(separator: "\n").first(where: { $0.hasPrefix("element \(name)") }).flatMap { Int($0.split(separator: " ")[2]) } ?? 0 }
        let nv = count("vertex"), nf = count("face")
        var out: [UInt32] = []
        out.reserveCapacity(nf * 3)
        data.withUnsafeBytes { raw in
            var o = end.upperBound + nv * 15
            for _ in 0..<nf {
                guard o < raw.count else { break }
                let c = Int(raw.load(fromByteOffset: o, as: UInt8.self)); o += 1
                let idx = (0..<c).map { raw.loadUnaligned(fromByteOffset: o + $0 * 4, as: Int32.self) }; o += c * 4
                if c == 3 { out += idx.map { UInt32($0) } }
            }
        }
        return out
    }
}

/// Why the iPhone has no surface at a spot: every frame of its recording that saw the spot, what its depth said there.
/// `harness why <recording> x y z` (iPhone world coordinates).
enum Why {
    static func run(_ dir: URL, _ p: SIMD3<Float>) {
        guard let fd = try? Data(contentsOf: dir.appendingPathComponent("frames.bin")), let dd = try? Data(contentsOf: dir.appendingPathComponent("depth.bin")) else { return }
        var frames: [(t: Double, pose: simd_float4x4, k: simd_float3x3, w: Int, h: Int)] = []
        for i in 0..<(fd.count / 120) {
            fd.withUnsafeBytes { b in
                let o = i * 120
                var c: [SIMD4<Float>] = []
                for j in 0..<4 { c.append(b.loadUnaligned(fromByteOffset: o + 8 + j * 16, as: SIMD4<Float>.self)) }
                var k: [SIMD3<Float>] = []
                for j in 0..<3 { let q = o + 72 + j * 12; k.append(SIMD3(b.loadUnaligned(fromByteOffset: q, as: Float.self), b.loadUnaligned(fromByteOffset: q + 4, as: Float.self), b.loadUnaligned(fromByteOffset: q + 8, as: Float.self))) }
                frames.append((b.loadUnaligned(fromByteOffset: o, as: Double.self), simd_float4x4(c[0], c[1], c[2], c[3]), simd_float3x3(k[0], k[1], k[2]),
                               Int(b.loadUnaligned(fromByteOffset: o + 108, as: Int32.self)), Int(b.loadUnaligned(fromByteOffset: o + 112, as: Int32.self))))
            }
        }
        var at = 0, fi = 0
        var seen = 0, confCount = [0, 0, 0], agree = 0, nearer = 0, farther = 0, none = 0, dists: [Float] = []
        while at + 12 <= dd.count {
            let t = dd.subdata(in: at..<(at + 8)).withUnsafeBytes { $0.loadUnaligned(as: Double.self) }
            let len = Int(dd.subdata(in: (at + 8)..<(at + 12)).withUnsafeBytes { $0.loadUnaligned(as: UInt32.self) })
            at += 12
            guard at + len <= dd.count, let raw = try? (dd.subdata(in: at..<(at + len)) as NSData).decompressed(using: .lzfse) as Data else { break }
            at += len
            while fi + 1 < frames.count, abs(frames[fi + 1].t - t) <= abs(frames[fi].t - t) { fi += 1 }
            let f = frames[fi]
            let w = Int(raw.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 0, as: UInt16.self) }), h = Int(raw.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 2, as: UInt16.self) })
            let c = f.pose.inverse * SIMD4(p, 1)
            let z = -c.z
            guard z > 0.2 else { continue }
            let sx = Float(w) / Float(f.w), sy = Float(h) / Float(f.h)
            let u = Int(f.k[0][0] * sx * c.x / z + f.k[2][0] * sx), v = Int(f.k[2][1] * sy - f.k[1][1] * sy * c.y / z)
            guard u >= 0, v >= 0, u < w, v < h else { continue }
            seen += 1
            dists.append(z)
            raw.withUnsafeBytes { b in
                let d = Float(b.loadUnaligned(fromByteOffset: 4 + (v * w + u) * 2, as: UInt16.self)) / 1000
                let cf = Int(b.loadUnaligned(fromByteOffset: 4 + w * h * 2 + v * w + u, as: UInt8.self))
                confCount[min(2, cf)] += 1
                if d <= 0 { none += 1 } else if abs(d - z) < 0.08 { agree += 1 } else if d < z { nearer += 1 } else { farther += 1 }
            }
        }
        dists.sort()
        print(String(format: "spot (%.2f, %.2f, %.2f): in view in %d depth frames, %.1f–%.1f m away (median %.1f)", p.x, p.y, p.z, seen,
                     dists.first ?? 0, dists.last ?? 0, dists.isEmpty ? 0 : dists[dists.count / 2]))
        print("  depth there: \(agree) agree (±8 cm), \(nearer) something nearer in front, \(farther) further (seen through / past it), \(none) no depth")
        print("  confidence: low \(confCount[0]), medium \(confCount[1]), high \(confCount[2])")
    }
}
