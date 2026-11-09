import Foundation
import simd

/// Replays a raw recording from the iPhone (RawRecorder's format: frames.bin, depth.bin) on the Mac: fuses every
/// depth frame with its recorded pose into the app's TSDF, as "Rebuild from recording" does on the phone, and
/// reports and renders the surface. `harness replay recordings/ios/<scan>`.
enum Replay {
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
