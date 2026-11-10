import AVFoundation
import CoreGraphics
import Foundation
import ImageIO
import Metal
import simd

/// `harness tiles <recording folder> [seconds]`: the iPhone's flat tiles (FlatTiles.swift, unchanged) run on a raw
/// recording (video.mov, depth.bin, frames.bin) as if live; writes the shared tiles.json + tiles.png (the .rmsc form)
/// to harness-out/tiles-<scan>/ and prints the tile counts.
enum TilesRun {
    static func run(_ dir: URL, out: URL, limit: Double) throws {
        guard let device = MTLCreateSystemDefaultDevice(), let tiles = FlatTiles(device: device) else { print("no Metal"); return }
        FlatTiles.forceForTests = true
        // frames.bin: time (f64), pose (16 f32), intrinsics (9 f32), width, height, tracking (i32): 120 bytes each
        let fd = try Data(contentsOf: dir.appendingPathComponent("frames.bin"))
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
        guard let start = frames.first?.t else { print("no frames"); return }
        // depth.bin: per frame time (f64), length (u32), LZFSE( w u16, h u16, depth u16 mm [w*h], confidence u8 [w*h] )
        let dd = try Data(contentsOf: dir.appendingPathComponent("depth.bin"))
        var depths: [(t: Double, range: Range<Int>)] = []
        var at = 0
        while at + 12 <= dd.count {
            let t = dd.subdata(in: at..<(at + 8)).withUnsafeBytes { $0.loadUnaligned(as: Double.self) }
            let len = Int(dd.subdata(in: (at + 8)..<(at + 12)).withUnsafeBytes { $0.loadUnaligned(as: UInt32.self) })
            at += 12
            guard at + len <= dd.count else { break }
            depths.append((t, at..<(at + len))); at += len
        }
        let asset = AVURLAsset(url: dir.appendingPathComponent("video.mov"))
        let sem = DispatchSemaphore(value: 0)
        var track: AVAssetTrack?
        Task { track = try? await asset.loadTracks(withMediaType: .video).first; sem.signal() }
        sem.wait()
        guard let track, let reader = try? AVAssetReader(asset: asset) else { print("no video"); return }
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA])
        reader.add(output); reader.startReading()
        var looks = 0, fi = 0, di = 0
        let t0 = Date()
        var updateMs = 0.0
        while let sample = output.copyNextSampleBuffer(), let image = CMSampleBufferGetImageBuffer(sample) {
            let time = start + CMSampleBufferGetPresentationTimeStamp(sample).seconds
            if time - start > limit { break }
            while fi + 1 < frames.count, abs(frames[fi + 1].t - time) <= abs(frames[fi].t - time) { fi += 1 }
            while di + 1 < depths.count, abs(depths[di + 1].t - time) <= abs(depths[di].t - time) { di += 1 }
            let f = frames[fi]
            guard f.ok, abs(f.t - time) < 0.02, !depths.isEmpty, abs(depths[di].t - time) < 0.02,
                  time - tiles.lastLookTime >= 0.5,
                  let raw = try? (dd.subdata(in: depths[di].range) as NSData).decompressed(using: .lzfse) as Data else { continue }
            CVPixelBufferLockBaseAddress(image, .readOnly)
            let vw = CVPixelBufferGetWidth(image), vh = CVPixelBufferGetHeight(image), row = CVPixelBufferGetBytesPerRow(image)
            let px = CVPixelBufferGetBaseAddress(image)!.assumingMemoryBound(to: UInt8.self)
            let sx = Double(vw) / Double(f.w), sy = Double(vh) / Double(f.h)
            raw.withUnsafeBytes { b in
                let w = Int(b.loadUnaligned(fromByteOffset: 0, as: UInt16.self)), h = Int(b.loadUnaligned(fromByteOffset: 2, as: UInt16.self))
                let look = FlatTiles.Look(time: time, pose: f.pose, dw: w, dh: h,
                    depth: { u, v in Float(b.loadUnaligned(fromByteOffset: 4 + (v * w + u) * 2, as: UInt16.self)) / 1000 },
                    confident: { u, v in b.loadUnaligned(fromByteOffset: 4 + w * h * 2 + v * w + u, as: UInt8.self) >= 1 },
                    k: f.k, iw: f.w, ih: f.h,
                    color: { u, v in
                        let x = min(vw - 1, Int(Double(u) * sx)), y = min(vh - 1, Int(Double(v) * sy)), o = y * row + x * 4
                        return SIMD3(px[o + 2], px[o + 1], px[o])
                    })
                let u0 = Date(); tiles.update(look); updateMs += Date().timeIntervalSince(u0) * 1000
            }
            CVPixelBufferUnlockBaseAddress(image, .readOnly)
            looks += 1
        }
        print(String(format: "%@: %d looks in %.0f s (%.0f ms per look on the Mac)", dir.deletingLastPathComponent().lastPathComponent, looks, Date().timeIntervalSince(t0), updateMs / Double(max(1, looks))))
        print(tiles.stats())
        for (i, p) in tiles.planes.enumerated() { print(String(format: "plane %d: n %.2f %.2f %.2f d %.2f", i, p.n.x, p.n.y, p.n.z, p.d)) }
        if let f = frames.first { print("first pose", f.pose, "k", f.k, f.w, f.h) }
        let folder = out.appendingPathComponent("tiles-" + dir.deletingLastPathComponent().deletingPathExtension().lastPathComponent)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        if let json = tiles.sharedJSON(floorY: 0) { try json.write(to: folder.appendingPathComponent("tiles.json")) }
        let rows = tiles.usedRows
        if rows > 0 {
            var bytes = tiles.atlasBytes(rows: rows)
            let w = FlatTiles.atlas, h = rows * FlatTiles.px
            let ctx = bytes.withUnsafeMutableBytes { CGContext(data: $0.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) }
            if let img = ctx?.makeImage(), let dest = CGImageDestinationCreateWithURL(folder.appendingPathComponent("tiles.png") as CFURL, "public.png" as CFString, 1, nil) {
                CGImageDestinationAddImage(dest, img, nil); CGImageDestinationFinalize(dest)
            }
        }
        print("wrote", folder.path)
    }
}
