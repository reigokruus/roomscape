import Foundation
import simd

/// Replays a raw recording from the iPhone (RawRecorder's format: frames.bin, depth.bin) on the Mac: fuses every
/// depth frame with its recorded pose into the app's TSDF, as "Rebuild from recording" does on the phone, and
/// reports and renders the surface. `harness replay recordings/ios/<scan>`.
enum Replay {
    /// Depth filtering: which confidence counts, drop flying pixels at depth edges, pixel step, range.
    struct Filter { var name: String; var minConfidence: UInt8; var flying: Bool; var step: Int; var maxDepth: Float }
    static let filters = [
        Filter(name: "A rebuild as on the phone (any confidence, no edge filter, every 2nd pixel, 5 m)", minConfidence: 0, flying: false, step: 2, maxDepth: 5),
        Filter(name: "B like the live scan (high confidence, edge filter, every 4th pixel, 4 m)", minConfidence: 2, flying: true, step: 4, maxDepth: 4),
        Filter(name: "C medium+ confidence, edge filter, every 2nd pixel, 4 m", minConfidence: 1, flying: true, step: 2, maxDepth: 4)]

    static func readMesh(_ url: URL) -> [SIMD3<Float>]? {
        guard let data = try? Data(contentsOf: url), data.count >= 12, data.prefix(4) == Data("MSH1".utf8) else { return nil }
        return data.withUnsafeBytes { raw in
            let nv = Int(raw.loadUnaligned(fromByteOffset: 4, as: UInt32.self))
            return (0..<nv).map { i in SIMD3(raw.loadUnaligned(fromByteOffset: 12 + i * 12, as: Float.self), raw.loadUnaligned(fromByteOffset: 16 + i * 12, as: Float.self), raw.loadUnaligned(fromByteOffset: 20 + i * 12, as: Float.self)) }
        }
    }

    /// Share of `a`'s points with no point of `b` within ~5 cm, and where the biggest such patch is.
    static func missing(_ a: [SIMD3<Float>], from b: [SIMD3<Float>]) -> (share: Float, centre: SIMD3<Float>, count: Int) {
        let cell: Float = 0.05
        func key(_ p: SIMD3<Float>) -> SIMD3<Int32> { SIMD3(Int32((p.x / cell).rounded(.down)), Int32((p.y / cell).rounded(.down)), Int32((p.z / cell).rounded(.down))) }
        var grid = Set<SIMD3<Int32>>()
        for p in b { grid.insert(key(p)) }
        var miss: [SIMD3<Float>] = []
        for p in a {
            let k = key(p)
            var found = false
            outer: for dx in -1...1 { for dy in -1...1 { for dz in -1...1 where grid.contains(k &+ SIMD3(Int32(dx), Int32(dy), Int32(dz))) { found = true; break outer } } }
            if !found { miss.append(p) }
        }
        // The densest 50 cm block of missing points.
        var blocks: [SIMD3<Int32>: [SIMD3<Float>]] = [:]
        for p in miss { blocks[SIMD3(Int32((p.x / 0.5).rounded(.down)), Int32((p.y / 0.5).rounded(.down)), Int32((p.z / 0.5).rounded(.down))), default: []].append(p) }
        let top = blocks.values.max { $0.count < $1.count } ?? []
        return (Float(miss.count) / Float(max(1, a.count)), top.isEmpty ? .zero : top.reduce(.zero, +) / Float(top.count), top.count)
    }

    static func compare(_ dir: URL, out: URL) throws {
        guard let original = readMesh(dir.appendingPathComponent("phone/before-rebuild/mesh.bin")) else { print("no phone/before-rebuild/mesh.bin"); return }
        if let rebuilt = readMesh(dir.appendingPathComponent("phone/mesh.bin")) {
            let lost = missing(original, from: rebuilt), added = missing(rebuilt, from: original)
            print(String(format: "phone's rebuild vs the live original: %.1f%% of the original surface missing (biggest patch: %d corners around %.1f, %.1f, %.1f), %.1f%% new surface", lost.share * 100, lost.count, lost.centre.x, lost.centre.y, lost.centre.z, added.share * 100))
        }
        for f in filters {
            let mesh = try fuse(dir, f)
            let lost = missing(original, from: mesh.positions), added = missing(mesh.positions, from: original)
            print(String(format: "%@: %d triangles; original missing %.1f%% (biggest patch %d corners around %.1f, %.1f, %.1f); new surface %.1f%%",
                         f.name, mesh.triangles.count / 3, lost.share * 100, lost.count, lost.centre.x, lost.centre.y, lost.centre.z, added.share * 100))
        }
    }

    static func fuse(_ dir: URL, _ filter: Filter) throws -> Tsdf.MeshScratch {
        let fd = try Data(contentsOf: dir.appendingPathComponent("frames.bin"))
        var frames: [(t: Double, pose: simd_float4x4, k: simd_float3x3, w: Int, h: Int, ok: Bool)] = []
        for i in 0..<(fd.count / 120) {
            fd.withUnsafeBytes { b in
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
        let dd = try Data(contentsOf: dir.appendingPathComponent("depth.bin"))
        let tsdf = Tsdf(capacity: 1_500_000, voxel: 0.04, truncation: 0.08, carve: 1.5)
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
            tsdf.beginFrame()
            let w = Int(raw.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 0, as: UInt16.self) }), h = Int(raw.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 2, as: UInt16.self) })
            let sx = Float(w) / Float(f.w), sy = Float(h) / Float(f.h)
            let fx = f.k[0][0] * sx, fy = f.k[1][1] * sy, cx = f.k[2][0] * sx, cy = f.k[2][1] * sy
            let o = SIMD3(f.pose.columns.3.x, f.pose.columns.3.y, f.pose.columns.3.z)
            raw.withUnsafeBytes { b in
                func z(_ x: Int, _ y: Int) -> Float { Float(b.loadUnaligned(fromByteOffset: 4 + (y * w + x) * 2, as: UInt16.self)) / 1000 }
                for y in stride(from: 1, to: h - 1, by: filter.step) { for x in stride(from: 1, to: w - 1, by: filter.step) {
                    let d = z(x, y)
                    let c = b.loadUnaligned(fromByteOffset: 4 + w * h * 2 + y * w + x, as: UInt8.self)
                    guard d > 0.2, d < filter.maxDepth, c >= filter.minConfidence else { continue }
                    if filter.flying {
                        let tol = d * 0.04 + 0.02
                        if abs(z(x - 1, y) - d) > tol || abs(z(x + 1, y) - d) > tol || abs(z(x, y - 1) - d) > tol || abs(z(x, y + 1) - d) > tol { continue }
                    }
                    let p4 = f.pose * SIMD4((Float(x) + 0.5 - cx) * d / fx, -(Float(y) + 0.5 - cy) * d / fy, -d, 1)
                    tsdf.integrate(origin: o, point: SIMD3(p4.x, p4.y, p4.z))
                } }
            }
        }
        return Fusion.mesh(tsdf)
    }

    static func run(_ dir: URL, out: URL) throws {
        let fd = try Data(contentsOf: dir.appendingPathComponent("frames.bin"))
        var frames: [(t: Double, pose: simd_float4x4, k: simd_float3x3, w: Int, h: Int, ok: Bool)] = []
        for i in 0..<(fd.count / 120) {
            fd.withUnsafeBytes { b in
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
        let dd = try Data(contentsOf: dir.appendingPathComponent("depth.bin"))
        let tsdf = Tsdf(capacity: 1_500_000, voxel: 0.04, truncation: 0.08, carve: 1.5)
        var at = 0, used = 0, fi = 0
        let started = Date()
        var lo = SIMD3<Float>(repeating: .greatestFiniteMagnitude), hi = -lo
        while at + 12 <= dd.count {
            let t = dd.subdata(in: at..<(at + 8)).withUnsafeBytes { $0.loadUnaligned(as: Double.self) }
            let len = Int(dd.subdata(in: (at + 8)..<(at + 12)).withUnsafeBytes { $0.loadUnaligned(as: UInt32.self) })
            at += 12
            guard at + len <= dd.count, let raw = try? (dd.subdata(in: at..<(at + len)) as NSData).decompressed(using: .lzfse) as Data else { break }
            at += len
            while fi + 1 < frames.count, abs(frames[fi + 1].t - t) <= abs(frames[fi].t - t) { fi += 1 }
            let f = frames[fi]
            guard f.ok else { continue }
            tsdf.beginFrame()
            let w = Int(raw.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 0, as: UInt16.self) }), h = Int(raw.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 2, as: UInt16.self) })
            let sx = Float(w) / Float(f.w), sy = Float(h) / Float(f.h)
            let fx = f.k[0][0] * sx, fy = f.k[1][1] * sy, cx = f.k[2][0] * sx, cy = f.k[2][1] * sy
            let o = SIMD3(f.pose.columns.3.x, f.pose.columns.3.y, f.pose.columns.3.z)
            raw.withUnsafeBytes { b in
                for y in stride(from: 0, to: h, by: 2) { for x in stride(from: 0, to: w, by: 2) {
                    let z = Float(b.loadUnaligned(fromByteOffset: 4 + (y * w + x) * 2, as: UInt16.self)) / 1000
                    let c = b.loadUnaligned(fromByteOffset: 4 + w * h * 2 + y * w + x, as: UInt8.self)
                    guard z > 0.2, z < 5, c >= 1 else { continue } // medium or high confidence
                    let p4 = f.pose * SIMD4((Float(x) - cx) * z / fx, -(Float(y) - cy) * z / fy, -z, 1)
                    let p = SIMD3(p4.x, p4.y, p4.z)
                    lo = simd_min(lo, p); hi = simd_max(hi, p)
                    tsdf.integrate(origin: o, point: p)
                } }
            }
            used += 1
        }
        let mesh = Fusion.mesh(tsdf)
        let size = hi - lo
        print(String(format: "Replay of %@: %d of %d depth frames fused (tracking normal), %d triangles, %d voxels, extent %.1f × %.1f × %.1f m, %.1f s on the Mac",
                     dir.lastPathComponent, used, frames.count, mesh.triangles.count / 3, tsdf.count, size.x, size.y, size.z, Date().timeIntervalSince(started)))
        // A view from outside, like the harness renders: a stand-in room box around what was scanned.
        let ys = mesh.positions.map(\.y).sorted()
        let floor = ys.isEmpty ? 0 : ys[ys.count / 50]
        let shifted = mesh.positions.map { $0 - SIMD3(lo.x, floor, lo.z) }
        let room = Room(size: SIMD3(size.x, max(0.5, (ys.last ?? 2.5) - floor), size.z), walls: [Material(albedo: .one), Material(albedo: .one), Material(albedo: .one), Material(albedo: .one)],
                        floor: Material(albedo: .one), ceiling: Material(albedo: .one), boxes: [], openings: [], name: "replay")
        let url = out.appendingPathComponent("replay-\(dir.lastPathComponent).png")
        Images.renderMesh(shifted, mesh.triangles, room: room, to: url)
        print("  picture: \(url.path)")
    }
}

/// CPU vs GPU fusion of a raw recording (TsdfGPU): the same depth (filter C: medium+ confidence, no flying pixels,
/// 0.2–4 m), timing and surface difference. `harness gpubench recordings/ios/<scan>`.
enum GPUBench {
    static func run(_ dir: URL, voxel: Float = 0.04, view: Int? = nil, skipCPU: Bool = false, carve: Float = 1.5, tag: String = "") throws {
        // CPU, as the phone's rebuild.
        let t0 = Date()
        let cpu = skipCPU ? Tsdf.MeshScratch() : try Replay.fuse(dir, Replay.filters[2])
        let cpuTime = Date().timeIntervalSince(t0)
        // GPU.
        let t1 = Date()
        let fd = try Data(contentsOf: dir.appendingPathComponent("frames.bin"))
        var frames: [(t: Double, pose: simd_float4x4, k: simd_float3x3, w: Int, h: Int, ok: Bool)] = []
        for i in 0..<(fd.count / 120) {
            fd.withUnsafeBytes { b in
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
        let dd = try Data(contentsOf: dir.appendingPathComponent("depth.bin"))
        var depth: [(pose: simd_float4x4, fx: Float, fy: Float, cx: Float, cy: Float, w: Int, h: Int, m: [Float])] = []
        var lo = SIMD3<Float>(repeating: .infinity), hi = SIMD3<Float>(repeating: -.infinity)
        var depthFrameIndex: [Int] = []
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
            let sx = Float(w) / Float(f.w), sy = Float(h) / Float(f.h)
            let fx = f.k[0][0] * sx, fy = f.k[1][1] * sy, cx = f.k[2][0] * sx, cy = f.k[2][1] * sy
            var m = [Float](repeating: 0, count: w * h)
            raw.withUnsafeBytes { b in
                func z(_ x: Int, _ y: Int) -> Float { Float(b.loadUnaligned(fromByteOffset: 4 + (y * w + x) * 2, as: UInt16.self)) / 1000 }
                for y in 1..<(h - 1) { for x in 1..<(w - 1) {
                    let d = z(x, y)
                    let c = b.loadUnaligned(fromByteOffset: 4 + w * h * 2 + y * w + x, as: UInt8.self)
                    guard d > 0.2, d < 4, c >= 1 else { continue }
                    let tol = d * 0.04 + 0.02
                    if abs(z(x - 1, y) - d) > tol || abs(z(x + 1, y) - d) > tol || abs(z(x, y - 1) - d) > tol || abs(z(x, y + 1) - d) > tol { continue }
                    m[y * w + x] = d
                    if x % 8 == 1, y % 8 == 1 {
                        let p4 = f.pose * SIMD4((Float(x) + 0.5 - cx) * d / fx, -(Float(y) + 0.5 - cy) * d / fy, -d, 1)
                        lo = simd_min(lo, SIMD3(p4.x, p4.y, p4.z)); hi = simd_max(hi, SIMD3(p4.x, p4.y, p4.z))
                    }
                } }
            }
            depth.append((f.pose, fx, fy, cx, cy, w, h, m))
            depthFrameIndex.append(fi)
        }
        let prepTime = Date().timeIntervalSince(t1)
        // Fine voxels: only a box around the chosen view's camera (a detail zone), so the block fits.
        if voxel < 0.02, let view, view < frames.count {
            let c = SIMD3(frames[view].pose.columns.3.x, frames[view].pose.columns.3.y, frames[view].pose.columns.3.z)
            lo = simd_max(lo, c - 1.6); hi = simd_min(hi, c + 1.6)
        }
        guard let gpu = TsdfGPU(min: lo - 0.2, max: hi + 0.2, voxel: voxel, truncation: max(0.03, voxel * 2), carve: carve) else { print("no GPU TSDF (block too big?)"); return }
        let t2 = Date()
        // `only`: just the depth frames around the view (to see what a few frames give before the rest is fused).
        let onlyNear = ProcessInfo.processInfo.environment["ONLY_NEAR"].flatMap(Int.init)
        for (i, d) in depth.enumerated() {
            if let k = onlyNear, let view, abs(depthFrameIndex[i] - view) > k { continue }
            gpu.integrate(depth: d.m, width: d.w, height: d.h, pose: d.pose, fx: d.fx, fy: d.fy, cx: d.cx, cy: d.cy, step: 2)
        }
        gpu.finish()
        let gpuTime = Date().timeIntervalSince(t2)
        // PROBE=x,y: the voxels along the ray through depth pixel (x, y) of the view's frame.
        if let probe = ProcessInfo.processInfo.environment["PROBE"]?.split(separator: ",").compactMap({ Int($0) }), probe.count == 2, let view {
            if let i = depthFrameIndex.firstIndex(where: { $0 >= view }) {
                let d = depth[i], x = probe[0], y = probe[1], z = d.m[y * d.w + x]
                print("probe depth \(z) m at (\(x), \(y))")
                let dir4 = d.pose * SIMD4((Float(x) + 0.5 - d.cx) / d.fx, -(Float(y) + 0.5 - d.cy) / d.fy, -1, 0)
                let o = SIMD3(d.pose.columns.3.x, d.pose.columns.3.y, d.pose.columns.3.z)
                for k in stride(from: -8, through: 8, by: 1) {
                    let p = o + SIMD3(dir4.x, dir4.y, dir4.z) * (z + Float(k) * voxel / 2)
                    let v = SIMD3<Int32>((p / voxel).rounded(.down))
                    if let val = gpu.value(at: v) { print(String(format: "  %+5.1f cm: sdf %+.2f  w %d", Float(k) * voxel / 2 * 100, val.sdf, val.weight)) }
                }
            }
        }
        let t3 = Date()
        let tsdf = Tsdf(capacity: 12_000_000, voxel: voxel, truncation: max(0.03, voxel * 2), carve: 1.5)
        tsdf.unseenAsEmpty = ProcessInfo.processInfo.environment["UNSEEN"] == "1"
        gpu.copy(into: tsdf)
        let gpuMesh = Fusion.mesh(tsdf)
        let extractTime = Date().timeIntervalSince(t3)
        print(String(format: "voxel %.1f cm: GPU block %d voxels (%.0f MB), %d triangles", voxel * 100, gpu.voxelCount, Double(gpu.voxelCount) * 6 / 1e6, gpuMesh.triangles.count / 3))
        if let view, view < frames.count {
            let f = frames[view]
            let url = URL(fileURLWithPath: "harness-out/detail-\(dir.deletingLastPathComponent().lastPathComponent)-f\(view)-\(Int((voxel * 1000).rounded()))mm\(tag)\(ProcessInfo.processInfo.environment["UNSEEN"] == "1" ? "-unseen" : "").png")
            Images.renderFromCamera(gpuMesh.positions, gpuMesh.triangles, pose: f.pose, k: f.k, imageWidth: f.w, imageHeight: f.h, to: url)
            print("  render: \(url.path)")
        }
        if skipCPU { return }
        let lost = Replay.missing(cpu.positions, from: gpuMesh.positions), added = Replay.missing(gpuMesh.positions, from: cpu.positions)
        print(String(format: "CPU fusion: %.2f s, %d triangles", cpuTime, cpu.triangles.count / 3))
        print(String(format: "GPU fusion: %.2f s on the GPU (%d frames, %d voxels block), + %.2f s depth prep, + %.2f s copy and mesh; %d triangles",
                     gpuTime, depth.count, gpu.voxelCount, prepTime, extractTime, gpuMesh.triangles.count / 3))
        print(String(format: "GPU vs CPU surface: %.1f%% of the CPU surface missing (biggest patch %d corners around %.1f, %.1f, %.1f), %.1f%% extra",
                     lost.share * 100, lost.count, lost.centre.x, lost.centre.y, lost.centre.z, added.share * 100))
    }
}
