import Foundation
import simd
import os

/// Stray live dots (13 Nov): replays an iPhone recording (frames.bin + depth.bin) through the live dots' rules, the
/// old ones (whole depth frame, nothing ever removed) and the new ones (DotCleanup: capture zone, closest good look
/// wins, see-through erase), and counts dots with no surface near them (the scan's mesh.bin within 6 cm) = strays.
/// 2 cm dots only (the fine dots under 1 m follow the same rules). Colour isn't replayed.
///   harness strays recordings/ios/<scan>/<scan>.capture [around-seconds]
let floatersLock = OSAllocatedUnfairLock(initialState: 0)
let partLock = OSAllocatedUnfairLock<(Int, Int, Int, Float)>(initialState: (0, 0, 0, 0))
enum Strays {
    struct Dot { var p: SIMD3<Float>; var near: UInt16; var through: UInt8; var seen: Int; var erased: Bool
                 var t: Float; var u: Float; var v: Float; var d: Float; var stamp: Int }

    static func run(_ capture: URL, around: Float?) throws {
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
        guard let t0 = frames.first?.t else { print("no frames"); return }
        let mesh = Replay.readMesh(capture.appendingPathComponent("mesh.bin")) ?? []
        print("recording: \(frames.count) frames, \(String(format: "%.0f", (frames.last!.t - t0))) s; mesh \(mesh.count) vertices")
        // the surface, for "is there anything within 6 cm"
        let cell: Float = 0.03
        func gk(_ p: SIMD3<Float>) -> SIMD3<Int32> { SIMD3(Int32((p.x / cell).rounded(.down)), Int32((p.y / cell).rounded(.down)), Int32((p.z / cell).rounded(.down))) }
        var surface = Set<SIMD3<Int32>>()
        for p in mesh { surface.insert(gk(p)) }
        func nearSurface(_ p: SIMD3<Float>) -> Bool {
            let k = gk(p)
            for dx in -2...2 { for dy in -2...2 { for dz in -2...2 where surface.contains(k &+ SIMD3(Int32(dx), Int32(dy), Int32(dz))) { return true } } }
            return false
        }

        let dd = try Data(contentsOf: rec.appendingPathComponent("depth.bin"))
        let viewAspect: Float = 1179.0 / 2556.0   // iPhone 15 Pro portrait
        let env = ProcessInfo.processInfo.environment
        let zoneTry = env["STRAYS_ZONE"].flatMap(Float.init) ?? DotCleanup.zoneWidth
        let zoneTryH = env["STRAYS_ZONE_H"].flatMap(Float.init) ?? (env["STRAYS_ZONE"] == nil ? DotCleanup.zoneHeight : zoneTry)
        let budgetTry = env["STRAYS_BUDGET"].flatMap(Int.init) ?? DotCleanup.budget
        print("zone \(zoneTry) wide, \(zoneTryH) high, erase budget \(budgetTry)")
        struct Seen { var depth: [Float32]; var conf: [UInt8]; var w: Int; var h: Int; var fx: Float; var fy: Float; var cx: Float; var cy: Float; var inv: simd_float4x4 }
        var kept3: [Seen] = []
        for newRules in [false, true] {
            var dots: [Dot] = []
            var index: [Int64: Int] = [:]
            var at = 0, fi = 0, stamp = 0, cursor = 0, closer = 0, erased = 0
            var eraseSeconds = 0.0, eraseFrames = 0
            var lastT = -1.0
            while at + 12 <= dd.count {
                let t = dd.subdata(in: at..<(at + 8)).withUnsafeBytes { $0.loadUnaligned(as: Double.self) }
                let len = Int(dd.subdata(in: (at + 8)..<(at + 12)).withUnsafeBytes { $0.loadUnaligned(as: UInt32.self) })
                at += 12
                guard at + len <= dd.count, let raw = try? (dd.subdata(in: at..<(at + len)) as NSData).decompressed(using: .lzfse) as Data else { break }
                at += len
                // the live scan samples about 10 depth frames a second
                if t - lastT < 0.095 { continue }
                lastT = t
                while fi + 1 < frames.count, abs(frames[fi + 1].t - t) <= abs(frames[fi].t - t) { fi += 1 }
                let f = frames[fi]
                guard f.ok else { continue }
                stamp += 1
                let w = Int(raw.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 0, as: UInt16.self) }), h = Int(raw.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 2, as: UInt16.self) })
                var depth = [Float32](repeating: 0, count: w * h), conf = [UInt8](repeating: 0, count: w * h)
                raw.withUnsafeBytes { b in
                    for i in 0..<(w * h) {
                        depth[i] = Float(b.loadUnaligned(fromByteOffset: 4 + i * 2, as: UInt16.self)) / 1000
                        conf[i] = b.loadUnaligned(fromByteOffset: 4 + w * h * 2 + i, as: UInt8.self)
                    }
                }
                let sx = Float(w) / Float(f.w), sy = Float(h) / Float(f.h)
                let fx = f.k[0][0] * sx, fy = f.k[1][1] * sy, cx = f.k[2][0] * sx, cy = f.k[2][1] * sy
                if !newRules, stamp % 2 == 0 { kept3.append(Seen(depth: depth, conf: conf, w: w, h: h, fx: fx, fy: fy, cx: cx, cy: cy, inv: f.pose.inverse)) }
                let zr = newRules ? DotCleanup.zoneRect(imageWidth: f.w, imageHeight: f.h, viewAspect: viewAspect, width: zoneTry, height: zoneTryH) : (0, 1, 0, 1)
                let zx0 = Int(zr.0 * Float(w)), zx1 = Int((zr.1 * Float(w)).rounded(.up)), zy0 = Int(zr.2 * Float(h)), zy1 = Int((zr.3 * Float(h)).rounded(.up))
                let tt = Float(t - t0)
                for v in stride(from: 1, to: h - 1, by: 2) { for u in stride(from: 1, to: w - 1, by: 2) {
                    let c = conf[v * w + u]
                    guard c >= 2 else { continue }
                    let d = depth[v * w + u]
                    guard d > 0.2, d < 4 else { continue }
                    let tol = d * 0.04 + 0.02
                    if abs(depth[v * w + u - 1] - d) > tol || abs(depth[v * w + u + 1] - d) > tol || abs(depth[(v - 1) * w + u] - d) > tol || abs(depth[(v + 1) * w + u] - d) > tol { continue }
                    if u < zx0 || u >= zx1 || v < zy0 || v >= zy1 { continue }
                    let p4 = f.pose * SIMD4((Float(u) + 0.5 - cx) * d / fx, (cy - (Float(v) + 0.5)) * d / fy, -d, 1)
                    let p = SIMD3(p4.x, p4.y, p4.z)
                    let key = (Int64(floor(p.x / 0.02)) & 0x1F_FFFF) << 42 | (Int64(floor(p.y / 0.02)) & 0x1F_FFFF) << 21 | (Int64(floor(p.z / 0.02)) & 0x1F_FFFF)
                    let cm = UInt16(min(65_535, Int((d * 100).rounded())))
                    if let i = index[key] {
                        if dots[i].stamp != stamp { dots[i].seen += 1; dots[i].stamp = stamp }
                        if newRules, dots[i].erased || DotCleanup.closerWins(stored: dots[i].near, now: cm) {
                            if !dots[i].erased { closer += 1 }
                            dots[i] = Dot(p: p, near: cm, through: 0, seen: dots[i].erased ? 1 : dots[i].seen, erased: false, t: tt,
                                          u: Float(u) / Float(w), v: Float(v) / Float(h), d: d, stamp: stamp)
                            continue
                        }
                        let wt = 1 / Float(min(dots[i].seen, 20) + 1)
                        dots[i].p += (p - dots[i].p) * wt
                        if cm < dots[i].near { dots[i].near = cm }
                    } else {
                        index[key] = dots.count
                        dots.append(Dot(p: p, near: cm, through: 0, seen: 1, erased: false, t: tt, u: Float(u) / Float(w), v: Float(v) / Float(h), d: d, stamp: stamp))
                    }
                } }
                guard newRules, !dots.isEmpty else { continue }
                let started = Date()
                depth.withUnsafeBufferPointer { dp in conf.withUnsafeBufferPointer { cp in
                    let view = DotCleanup.DepthFrame(depth: dp.baseAddress!, depthRow: w, confidence: cp.baseAddress!, confidenceRow: w, width: w, height: h,
                                                     fx: fx, fy: fy, cx: cx, cy: cy, worldToCamera: f.pose.inverse, x0: zx0, x1: zx1, y0: zy0, y1: zy1, voxel: 0.02)
                    var left = min(dots.count, budgetTry)
                    var i = cursor % dots.count
                    while left > 0 {
                        left -= 1
                        defer { i += 1; if i >= dots.count { i = 0 } }
                        if dots[i].erased || dots[i].stamp == stamp { continue }
                        guard let seen = DotCleanup.seenThrough(dots[i].p, view) else { continue }
                        if !seen { dots[i].through = 0; continue }
                        dots[i].through &+= 1
                        if dots[i].through >= (dots[i].seen >= 10 ? DotCleanup.throughFramesSolid : DotCleanup.throughFrames) {
                            dots[i].erased = true; dots[i].through = 0; erased += 1
                        }
                    }
                    cursor = i
                } }
                eraseSeconds += Date().timeIntervalSince(started); eraseFrames += 1
            }
            let kept = dots.filter { !$0.erased }
            // Ground truth from the whole recording (every 2nd sampled frame, all dots, whole frame): a floater is a dot
            // that sure depth saw clearly past in 3+ frames and that more frames saw through than at it.
            var floaters = 0
            DispatchQueue.concurrentPerform(iterations: 8) { part in
                var local = 0
                for j in stride(from: part, to: kept.count, by: 8) {
                    let q = kept[j].p
                    var thr = 0, at = 0
                    for f in kept3 {
                        let c = f.inv * SIMD4(q, 1)
                        let d = -c.z
                        guard d > 0.25, d < 3.5 else { continue }
                        let u = Int(f.fx * c.x / d + f.cx), v = Int(f.cy - f.fy * c.y / d)
                        guard u >= 1, v >= 1, u < f.w - 1, v < f.h - 1, f.conf[v * f.w + u] >= 2 else { continue }
                        let z = f.depth[v * f.w + u]
                        if z > d + DotCleanup.margin(d) { thr += 1 } else if abs(z - d) < DotCleanup.margin(d) { at += 1 }
                    }
                    if thr >= 3, thr > at {
                        local += 1
                        let k = kept[j]
                        let off = abs(k.v - 0.5) > 0.31, band = abs(k.u - 0.5) > 0.35 || abs(k.v - 0.5) > 0.35
                        let near68 = abs(k.t - (around ?? -100)) <= 5
                        partLock.withLock { $0.0 += off ? 1 : 0; $0.1 += band ? 1 : 0; $0.2 += near68 ? 1 : 0; $0.3 += k.d }
                    }
                }
                floatersLock.withLock { $0 += local }
            }
            floaters = floatersLock.withLock { v in defer { v = 0 }; return v }
            let pl = partLock.withLock { v in defer { v = (0, 0, 0, 0) }; return v }
            // coverage: mesh vertices with a dot within 4 cm
            var dotCells = Set<SIMD3<Int32>>()
            for k in kept { dotCells.insert(SIMD3(Int32((k.p.x / 0.02).rounded(.down)), Int32((k.p.y / 0.02).rounded(.down)), Int32((k.p.z / 0.02).rounded(.down)))) }
            var covered = 0
            for (n, m) in mesh.enumerated() where n % 4 == 0 {
                let c = SIMD3(Int32((m.x / 0.02).rounded(.down)), Int32((m.y / 0.02).rounded(.down)), Int32((m.z / 0.02).rounded(.down)))
                var hit = false
                outer: for dx in -2...2 { for dy in -2...2 { for dz in -2...2 where dotCells.contains(c &+ SIMD3(Int32(dx), Int32(dy), Int32(dz))) { hit = true; break outer } } }
                if hit { covered += 1 }
            }
            print("\n   floaters (seen through in 3+ frames, more than seen at): \(floaters); made never on screen \(pl.0), in the outer 15% band \(pl.1), around the given time \(pl.2), mean distance \(String(format: "%.2f", pl.3 / Float(max(1, floaters)))) m; surface covered (mesh within 4 cm of a dot) \(String(format: "%.1f", 100 * Double(covered) / Double(max(1, (mesh.count + 3) / 4))))%", terminator: "")
            let strays = kept.filter { !nearSurface($0.p) }
            let one = strays.filter { $0.seen == 1 }.count
            print("\n== \(newRules ? "new rules (capture zone, closest wins, see-through erase)" : "old rules (whole frame, nothing removed)")")
            print("dots \(kept.count), strays (nothing within 6 cm) \(strays.count) = \(String(format: "%.2f", 100 * Double(strays.count) / Double(max(1, kept.count))))%, of them seen in 1 frame only \(one)")
            if newRules {
                print("replaced by a closer look \(closer), erased (seen through) \(erased); erase pass \(String(format: "%.2f", 1000 * eraseSeconds / Double(max(1, eraseFrames)))) ms a frame on this Mac (\(eraseFrames) frames)")
            }
            // where in the depth frame the strays were made: the outer band vs the middle, and how far
            let edge = strays.filter { abs($0.u - 0.5) > 0.35 || abs($0.v - 0.5) > 0.35 }.count
            let dist = strays.map(\.d).sorted()
            if !dist.isEmpty { print("strays made in the outer 15% band of the depth frame: \(edge); distance when made: median \(String(format: "%.2f", dist[dist.count / 2])) m, 90% \(String(format: "%.2f", dist[dist.count * 9 / 10])) m") }
            if let around {
                let s = strays.filter { abs($0.t - around) <= 5 }
                let e = s.filter { abs($0.u - 0.5) > 0.35 || abs($0.v - 0.5) > 0.35 }.count
                let off = s.filter { abs($0.v - 0.5) > 0.31 }.count   // off screen: the short side beyond the middle 62%
                print("made \(Int(around - 5))-\(Int(around + 5)) s: strays \(s.count) (outer band \(e), never on screen \(off)); median distance \(s.isEmpty ? 0 : s.map(\.d).sorted()[s.count / 2]) m")
            }
        }
    }
}

/// The rebuild's points (RecordingProcessor.surface: medium+ confidence, every 2nd pixel, 2 cm cells seen twice) from a
/// recording, before and after RebuildCleanup, with the same floater ground truth and the time it takes.
///   harness rebuildstrays recordings/ios/<scan>/<scan>.capture
enum RebuildStrays {
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
        var all: [RebuildCleanup.Frame] = []
        var cells: [SIMD3<Int32>: (sum: SIMD3<Float>, n: Int32)] = [:]
        var at = 0, fi = 0
        while at + 12 <= dd.count {
            let t = dd.subdata(in: at..<(at + 8)).withUnsafeBytes { $0.loadUnaligned(as: Double.self) }
            let len = Int(dd.subdata(in: (at + 8)..<(at + 12)).withUnsafeBytes { $0.loadUnaligned(as: UInt32.self) })
            at += 12
            guard at + len <= dd.count, let raw = try? (dd.subdata(in: at..<(at + len)) as NSData).decompressed(using: .lzfse) as Data else { break }
            at += len
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
            let fx = f.k[0][0] * sx, fy = f.k[1][1] * sy, cx = f.k[2][0] * sx, cy = f.k[2][1] * sy
            all.append(RebuildCleanup.Frame(depth: depth, confidence: conf, width: w, height: h, fx: fx, fy: fy, cx: cx, cy: cy, worldToCamera: f.pose.inverse))
            // as RecordingProcessor.surface (its pixel centre convention included)
            func z(_ x: Int, _ y: Int) -> Float { depth[y * w + x] }
            for y in stride(from: 1, to: h - 1, by: 2) { for x in stride(from: 1, to: w - 1, by: 2) {
                let d = z(x, y)
                guard d > 0.2, d < 4, conf[y * w + x] >= 1 else { continue }
                let tol = d * 0.04 + 0.02
                if abs(z(x - 1, y) - d) > tol || abs(z(x + 1, y) - d) > tol || abs(z(x, y - 1) - d) > tol || abs(z(x, y + 1) - d) > tol { continue }
                let p4 = f.pose * SIMD4((Float(x) - cx) * d / fx, -(Float(y) - cy) * d / fy, -d, 1)
                let pw = SIMD3(p4.x, p4.y, p4.z)
                guard cells.count < 1_200_000 else { continue }
                let k = SIMD3(Int32((pw.x / 0.02).rounded(.down)), Int32((pw.y / 0.02).rounded(.down)), Int32((pw.z / 0.02).rounded(.down)))
                let c = cells[k] ?? (.zero, 0)
                cells[k] = (c.sum + pw, c.n + 1)
            } }
        }
        let points = cells.values.filter { $0.n >= 2 }.map { $0.sum / Float($0.n) }
        print("rebuild points: \(points.count) from \(all.count) depth frames")
        let t0 = Date()
        let keep = RebuildCleanup.keep(points, frameCount: all.count) { all[$0] }
        let secs = Date().timeIntervalSince(t0)
        let kept = points.indices.filter { keep[$0] }.map { points[$0] }
        let dropped = points.indices.filter { !keep[$0] }.map { points[$0] }
        print("cleanup: dropped \(dropped.count) (\(String(format: "%.2f", 100 * Double(dropped.count) / Double(points.count)))%) in \(String(format: "%.1f", secs)) s on this Mac")
        // ground truth as in `strays` (every 6th frame here), before and after
        func floaters(_ pts: [SIMD3<Float>]) -> Int {
            let lock = OSAllocatedUnfairLock(initialState: 0)
            let sample = Swift.stride(from: 0, to: all.count, by: 6).map { all[$0] }
            DispatchQueue.concurrentPerform(iterations: 8) { part in
                var local = 0
                for j in Swift.stride(from: part, to: pts.count, by: 8) {
                    var thr = 0, atc = 0
                    for f in sample {
                        let c = f.worldToCamera * SIMD4(pts[j], 1)
                        let d = -c.z
                        guard d > 0.25, d < 3.5 else { continue }
                        let u = Int(f.fx * c.x / d + f.cx), v = Int(f.cy - f.fy * c.y / d)
                        guard u >= 1, v >= 1, u < f.width - 1, v < f.height - 1, f.confidence[v * f.width + u] >= 2 else { continue }
                        let zz = f.depth[v * f.width + u]
                        if zz > d + DotCleanup.margin(d) { thr += 1 } else if abs(zz - d) < DotCleanup.margin(d) { atc += 1 }
                    }
                    if thr >= 3, thr > atc { local += 1 }
                }
                lock.withLock { $0 += local }
            }
            return lock.withLock { $0 }
        }
        print("floaters before \(floaters(points)), after \(floaters(kept))")
        // dropped points right on the scan's surface (possible real things lost): mesh within 3 cm
        let mesh = Replay.readMesh(capture.appendingPathComponent("mesh.bin")) ?? []
        var surface = Set<SIMD3<Int32>>()
        for p in mesh { surface.insert(SIMD3(Int32((p.x / 0.03).rounded(.down)), Int32((p.y / 0.03).rounded(.down)), Int32((p.z / 0.03).rounded(.down)))) }
        let onSurface = dropped.filter { p in
            let k = SIMD3(Int32((p.x / 0.03).rounded(.down)), Int32((p.y / 0.03).rounded(.down)), Int32((p.z / 0.03).rounded(.down)))
            for dx in -1...1 { for dy in -1...1 { for dz in -1...1 where surface.contains(k &+ SIMD3(Int32(dx), Int32(dy), Int32(dz))) { return true } } }
            return false
        }
        print("dropped points within 3 cm of the scan's surface: \(onSurface.count)")
        // a PLY of the dropped points (red) and the kept ones (grey), to look at
        let out = URL(fileURLWithPath: "harness-out/rebuild-strays-\(capture.deletingPathExtension().lastPathComponent).ply")
        var ply = "ply\nformat ascii 1.0\nelement vertex \(kept.count / 4 + dropped.count)\nproperty float x\nproperty float y\nproperty float z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n"
        for (i, p) in kept.enumerated() where i % 4 == 0 { ply += "\(p.x) \(p.y) \(p.z) 170 170 170\n" }
        for p in dropped { ply += "\(p.x) \(p.y) \(p.z) 255 30 30\n" }
        try? FileManager.default.createDirectory(at: out.deletingLastPathComponent(), withIntermediateDirectories: true)
        try ply.write(to: out, atomically: true, encoding: .utf8)
        print("view: \(out.path)")
    }
}
