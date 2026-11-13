import Foundation
import simd

/// How filled the live dots look up close (13 Nov, rk: "the background shines through"): replays a recording into
/// live-style dots (2 cm cells, 0.8 cm under 1 m, the capture zone and cleanup rules), then renders them from the
/// recorded camera at the frame where the phone was closest to things, with the old sizing and the new one, and
/// measures the share of pixels on measured surface (the frame's own depth) that show no dot core or a core from
/// more than 10 cm behind that surface (see-through).
///   harness dotlook recordings/ios/<scan>/<scan>.capture
enum DotLook {
    struct Dot { var p: SIMD3<Float>; var c: SIMD3<Float>; var seen: Int; var fine: Bool; var stamp: Int }
    struct Sizing { var name: String; var core: (Float, Float, Bool) -> Float; var halo: (Float, Float, Bool) -> Float; var shrink: Bool; var capHalf: Float }

    static func run(_ capture: URL) throws {
        let rec = capture.appendingPathComponent("recording")
        let fd = try Data(contentsOf: rec.appendingPathComponent("frames.bin"))
        var frames: [(t: Double, pose: simd_float4x4, k: simd_float3x3, w: Int, h: Int, ok: Bool)] = []
        fd.withUnsafeBytes { b in
            for i in 0..<(fd.count / 120) {
                let o = i * 120
                var c: [SIMD4<Float>] = []
                for j in 0..<4 { c.append(b.loadUnaligned(fromByteOffset: o + 8 + j * 16, as: SIMD4<Float>.self)) }
                var k: [SIMD3<Float>] = []
                for j in 0..<3 { let q = o + 72 + j * 12; k.append(SIMD3(b.loadUnaligned(fromByteOffset: q, as: Float.self), b.loadUnaligned(fromByteOffset: q + 4, as: Float.self), b.loadUnaligned(fromByteOffset: q + 8, as: Float.self))) }
                frames.append((b.loadUnaligned(fromByteOffset: o, as: Double.self), simd_float4x4(c[0], c[1], c[2], c[3]), simd_float3x3(k[0], k[1], k[2]),
                               Int(b.loadUnaligned(fromByteOffset: o + 108, as: Int32.self)), Int(b.loadUnaligned(fromByteOffset: o + 112, as: Int32.self)),
                               b.loadUnaligned(fromByteOffset: o + 116, as: Int32.self) == 2))
            }
        }
        let dd = try Data(contentsOf: rec.appendingPathComponent("depth.bin"))
        struct DF { var t: Double; var pose: simd_float4x4; var fx: Float; var fy: Float; var cx: Float; var cy: Float; var w: Int; var h: Int; var depth: [Float32]; var conf: [UInt8] }
        var list: [DF] = []
        var at = 0, fi = 0, lastT = -1.0
        while at + 12 <= dd.count {
            let t = dd.subdata(in: at..<(at + 8)).withUnsafeBytes { $0.loadUnaligned(as: Double.self) }
            let len = Int(dd.subdata(in: (at + 8)..<(at + 12)).withUnsafeBytes { $0.loadUnaligned(as: UInt32.self) })
            at += 12
            guard at + len <= dd.count, let raw = try? (dd.subdata(in: at..<(at + len)) as NSData).decompressed(using: .lzfse) as Data else { break }
            at += len
            if t - lastT < 0.095 { continue }
            lastT = t
            while fi + 1 < frames.count, abs(frames[fi + 1].t - t) <= abs(frames[fi].t - t) { fi += 1 }
            let f = frames[fi]
            guard f.ok else { continue }
            let w = Int(raw.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 0, as: UInt16.self) }), h = Int(raw.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 2, as: UInt16.self) })
            var depth = [Float32](repeating: 0, count: w * h), conf = [UInt8](repeating: 0, count: w * h)
            raw.withUnsafeBytes { b in
                for i in 0..<(w * h) {
                    depth[i] = Float(b.loadUnaligned(fromByteOffset: 4 + i * 2, as: UInt16.self)) / 1000
                    conf[i] = b.loadUnaligned(fromByteOffset: 4 + w * h * 2 + i, as: UInt8.self)
                }
            }
            let sx = Float(w) / Float(f.w), sy = Float(h) / Float(f.h)
            list.append(DF(t: t - frames[0].t, pose: f.pose, fx: f.k[0][0] * sx, fy: f.k[1][1] * sy, cx: f.k[2][0] * sx, cy: f.k[2][1] * sy, w: w, h: h, depth: depth, conf: conf))
        }
        // the closest look: lowest median sure depth inside the middle of the frame, after the first 20 s
        var best = 0, bestMed: Float = .infinity
        for (i, f) in list.enumerated() where f.t > 20 {
            var ds: [Float] = []
            for y in stride(from: f.h / 4, to: f.h * 3 / 4, by: 4) { for x in stride(from: f.w / 4, to: f.w * 3 / 4, by: 4) where f.conf[y * f.w + x] >= 2 && f.depth[y * f.w + x] > 0.2 { ds.append(f.depth[y * f.w + x]) } }
            guard ds.count > 200 else { continue }
            ds.sort()
            if ds[ds.count / 2] < bestMed { bestMed = ds[ds.count / 2]; best = i }
        }
        setvbuf(stdout, nil, _IOLBF, 0)
        print("closest view: t = \(String(format: "%.1f", list[best].t)) s, median depth \(String(format: "%.2f", bestMed)) m")
        // live dots up to that frame (colour = a grey from the depth row, enough to see coverage)
        var dots: [Dot] = []
        var index: [Int64: Int] = [:]
        let zr = DotCleanup.zoneRect(imageWidth: 1920, imageHeight: 1440, viewAspect: 1179.0 / 2556.0)
        for (si, f) in list.enumerated() where si <= best {
            let zx0 = Int(zr.x0 * Float(f.w)), zx1 = Int((zr.x1 * Float(f.w)).rounded(.up)), zy0 = Int(zr.y0 * Float(f.h)), zy1 = Int((zr.y1 * Float(f.h)).rounded(.up))
            for v in 1..<(f.h - 1) { for u in 1..<(f.w - 1) {
                let coarse = u & 1 == 1 && v & 1 == 1
                guard f.conf[v * f.w + u] >= 2 else { continue }
                let d = f.depth[v * f.w + u]
                guard d > 0.2, d < 4 else { continue }
                let fine = d < 1
                if !coarse && !fine { continue }
                let tol = d * 0.04 + 0.02
                if abs(f.depth[v * f.w + u - 1] - d) > tol || abs(f.depth[v * f.w + u + 1] - d) > tol || abs(f.depth[(v - 1) * f.w + u] - d) > tol || abs(f.depth[(v + 1) * f.w + u] - d) > tol { continue }
                if u < zx0 || u >= zx1 || v < zy0 || v >= zy1 { continue }
                let p4 = f.pose * SIMD4((Float(u) + 0.5 - f.cx) * d / f.fx, (f.cy - (Float(v) + 0.5)) * d / f.fy, -d, 1)
                let p = SIMD3(p4.x, p4.y, p4.z)
                let vox: Float = fine ? 0.008 : 0.02
                var key = (Int64(floor(p.x / vox)) & 0x1F_FFFF) << 42 | (Int64(floor(p.y / vox)) & 0x1F_FFFF) << 21 | (Int64(floor(p.z / vox)) & 0x1F_FFFF)
                if fine { key ^= Int64.min }
                let shade = 0.35 + 0.5 * Float((u * 7 + v * 3) % 17) / 17
                if let i = index[key] {
                    if dots[i].stamp != si { dots[i].seen += 1; dots[i].stamp = si }
                    dots[i].p += (p - dots[i].p) / Float(min(dots[i].seen, 20) + 1)
                } else {
                    index[key] = dots.count
                    dots.append(Dot(p: p, c: SIMD3(repeating: shade), seen: 1, fine: fine, stamp: si))
                }
            } }
        }
        // the 2 cm dot of a cell with fine dots isn't drawn
        var fineCells = Set<Int64>()
        for d in dots where d.fine { fineCells.insert((Int64(floor(d.p.x / 0.02)) & 0x1F_FFFF) << 42 | (Int64(floor(d.p.y / 0.02)) & 0x1F_FFFF) << 21 | (Int64(floor(d.p.z / 0.02)) & 0x1F_FFFF)) }
        let shown = dots.filter { $0.fine || !fineCells.contains((Int64(floor($0.p.x / 0.02)) & 0x1F_FFFF) << 42 | (Int64(floor($0.p.y / 0.02)) & 0x1F_FFFF) << 21 | (Int64(floor($0.p.z / 0.02)) & 0x1F_FFFF)) }
        print("dots: \(shown.count) shown (\(shown.filter(\.fine).count) fine)")

        let f = list[best]
        let W = 960, H = 720
        let s = Float(W) / Float(f.w)
        let fx = f.fx * s, fy = f.fy * s, cx = f.cx * s, cy = f.cy * s
        let inv = f.pose.inverse
        // the old sizing (a5f3ce3): quad half = 0.6 cell, opaque core r² <= 0.146 of it, halo 1.6×, near shrink, 24 px cap
        // (on a 2340 px screen; here the same share of this image's height). New: core 0.65 cell (seen solid) down to
        // 0.25 cell (seen once), halo 1.0 cell, no shrink, 80 px cap on 2556.
        let solidFrames = 10
        let old = Sizing(name: "old", core: { cell, _, _ in cell * 0.6 * 0.382 }, halo: { cell, _, _ in cell * 0.6 * 1.6 }, shrink: true, capHalf: 24 * Float(H) / 2340)
        let new = Sizing(name: "new", core: { cell, solid, _ in cell * (0.25 + (0.65 - 0.25) * solid) }, halo: { cell, _, _ in cell * 1.0 }, shrink: false, capHalf: 80 * Float(H) / 2556)
        for sizing in [old, new] {
            var zbuf = [Float](repeating: .infinity, count: W * H)
            var img = [SIMD3<Float>](repeating: SIMD3(0.06, 0.06, 0.07), count: W * H)
            var proj: [(x: Float, y: Float, d: Float, core: Float, halo: Float, c: SIMD3<Float>)] = []
            for dot in shown {
                let c = inv * SIMD4(dot.p, 1)
                let d = -c.z
                guard d > 0.05 else { continue }
                let x = fx * c.x / d + cx, y = cy - fy * c.y / d
                guard x > -100, y > -100, x < Float(W + 100), y < Float(H + 100) else { continue }
                let cell: Float = dot.fine ? 0.008 : 0.02
                let solid = min(1, Float(dot.seen) / Float(solidFrames))
                var core = sizing.core(cell, solid, dot.fine), halo = sizing.halo(cell, solid, dot.fine)
                if sizing.shrink { let k = max(0.3, min(1, d / 1.5)); core *= k; halo *= k }
                let px = fy / d   // pixels per metre at this depth
                let cap = sizing.capHalf
                core = min(core * px, cap * (sizing.shrink ? 0.382 : 1)); halo = min(halo * px, cap * (sizing.shrink ? 1 : 1.54))
                proj.append((x, y, d, core, halo, dot.c))
            }
            // cores with depth
            var owner = [Float](repeating: .infinity, count: W * H)
            for q in proj {
                let r = q.core, r2 = r * r
                let y0 = max(0, Int(q.y - r)), y1 = min(H - 1, Int(q.y + r)), x0 = max(0, Int(q.x - r)), x1 = min(W - 1, Int(q.x + r))
                guard y0 <= y1, x0 <= x1 else { continue }
                for yy in y0...y1 { for xx in x0...x1 {
                    let dx = Float(xx) + 0.5 - q.x, dy = Float(yy) + 0.5 - q.y
                    guard dx * dx + dy * dy <= r2, q.d < zbuf[yy * W + xx] else { continue }
                    zbuf[yy * W + xx] = q.d; owner[yy * W + xx] = q.d; img[yy * W + xx] = q.c
                } }
            }
            // halos: blended, depth tested against the cores, far to near
            for q in proj.sorted(by: { $0.d > $1.d }) {
                let r = q.halo, r2 = r * r
                guard r >= 0.5 else { continue }
                let y0 = max(0, Int(q.y - r)), y1 = min(H - 1, Int(q.y + r)), x0 = max(0, Int(q.x - r)), x1 = min(W - 1, Int(q.x + r))
                guard y0 <= y1, x0 <= x1 else { continue }
                for yy in y0...y1 { for xx in x0...x1 {
                    let dx = Float(xx) + 0.5 - q.x, dy = Float(yy) + 0.5 - q.y
                    let rr = (dx * dx + dy * dy) / r2
                    guard rr <= 1, q.d <= zbuf[yy * W + xx] + 0.001 else { continue }
                    let a = exp(-rr * 3.5)
                    img[yy * W + xx] = img[yy * W + xx] * (1 - a) + q.c * a
                } }
            }
            // see-through on measured surface: no core, or a core > 10 cm behind the frame's own sure depth
            var surface = 0, empty = 0, behind = 0
            for yy in stride(from: 0, to: H, by: 2) { for xx in stride(from: 0, to: W, by: 2) {
                let u = Int(Float(xx) / s), v = Int(Float(yy) / s)
                guard u < f.w, v < f.h, f.conf[v * f.w + u] >= 2 else { continue }
                let z = f.depth[v * f.w + u]
                guard z > 0.2, z < 3.5 else { continue }
                let zone = Float(u) / Float(f.w), zv = Float(v) / Float(f.h)
                guard zone > zr.x0, zone < zr.x1, zv > zr.y0, zv < zr.y1 else { continue }   // only where dots are made
                surface += 1
                let o = owner[yy * W + xx]
                if !o.isFinite { empty += 1 } else if o > z * 1.0 + 0.10 { behind += 1 }
            } }
            print("\(sizing.name): on measured surface in the zone, no dot core \(String(format: "%.1f", 100 * Double(empty) / Double(max(1, surface))))%, a core from > 10 cm behind \(String(format: "%.1f", 100 * Double(behind) / Double(max(1, surface))))%")
            // PPM picture
            var ppm = Data("P6\n\(W) \(H)\n255\n".utf8)
            for p in img { ppm.append(contentsOf: [UInt8(min(255, p.x * 255)), UInt8(min(255, p.y * 255)), UInt8(min(255, p.z * 255))]) }
            let out = URL(fileURLWithPath: "harness-out/dotlook-\(sizing.name)-\(capture.deletingPathExtension().lastPathComponent).ppm")
            try? FileManager.default.createDirectory(at: out.deletingLastPathComponent(), withIntermediateDirectories: true)
            try ppm.write(to: out)
            print("  picture: \(out.path)")
        }
    }
}
