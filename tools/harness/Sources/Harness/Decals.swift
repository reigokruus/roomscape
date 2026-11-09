import Foundation
import simd

/// `harness decals <recording>`: runs the app's DecalFinder on a raw recording (sharp text patches), into harness-out.
enum Decals {
    static func run(_ dir: URL, out: URL) async throws {
        let fd = try Data(contentsOf: dir.appendingPathComponent("frames.bin"))
        var frames: [DecalFinder.Frame] = []
        for i in 0..<(fd.count / 120) {
            fd.withUnsafeBytes { b in
                let o = i * 120
                var c: [SIMD4<Float>] = []
                for j in 0..<4 { c.append(b.loadUnaligned(fromByteOffset: o + 8 + j * 16, as: SIMD4<Float>.self)) }
                var k: [SIMD3<Float>] = []
                for j in 0..<3 { let q = o + 72 + j * 12; k.append(SIMD3(b.loadUnaligned(fromByteOffset: q, as: Float.self), b.loadUnaligned(fromByteOffset: q + 4, as: Float.self), b.loadUnaligned(fromByteOffset: q + 8, as: Float.self))) }
                frames.append(DecalFinder.Frame(time: b.loadUnaligned(fromByteOffset: o, as: Double.self), pose: simd_float4x4(c[0], c[1], c[2], c[3]),
                                                k: simd_float3x3(k[0], k[1], k[2]), width: Int(b.loadUnaligned(fromByteOffset: o + 108, as: Int32.self)),
                                                height: Int(b.loadUnaligned(fromByteOffset: o + 112, as: Int32.self))))
            }
        }
        let dd = try Data(contentsOf: dir.appendingPathComponent("depth.bin"))
        var depth: [DecalFinder.Depth] = []
        var at = 0
        while at + 12 <= dd.count {
            let t = dd.subdata(in: at..<(at + 8)).withUnsafeBytes { $0.loadUnaligned(as: Double.self) }
            let len = Int(dd.subdata(in: (at + 8)..<(at + 12)).withUnsafeBytes { $0.loadUnaligned(as: UInt32.self) })
            at += 12
            guard at + len <= dd.count, let raw = try? (dd.subdata(in: at..<(at + len)) as NSData).decompressed(using: .lzfse) as Data else { break }
            at += len
            let w = Int(raw.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 0, as: UInt16.self) }), h = Int(raw.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 2, as: UInt16.self) })
            depth.append((t, w, h, raw.subdata(in: 4..<(4 + w * h * 2)).withUnsafeBytes { Array($0.bindMemory(to: UInt16.self)) }))
        }
        let target = out.appendingPathComponent("decals-\(dir.lastPathComponent)")
        let started = Date()
        let n = try await DecalFinder.find(recording: dir, frames: frames, depth: depth, out: target, log: { print($0) })
        print(String(format: "%d decals in %@ (%.1f s)", n, target.path, Date().timeIntervalSince(started)))
    }
}
