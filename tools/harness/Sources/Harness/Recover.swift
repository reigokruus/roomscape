import AVFoundation
import CoreImage
import Foundation
import ImageIO
import UniformTypeIdentifiers
import simd

/// Turns a raw iPhone recording (frames.bin, depth.bin, video.mov) into an importable .rmsc on the Mac, without the
/// app: surface fused as the phone's rebuild does (Replay filter C), photos from the video every 20 cm or 15° with their
/// recorded pose, lens and depth, points coloured from the photos. Floor moved to 0 as the .rmsc format wants.
/// `harness recover <recording> <out.rmsc> [name]` (rk 9 Nov: a scan killed mid-way by an install).
enum Recover {
    static func run(_ dir: URL, to url: URL, name: String) async throws {
        let started = Date()
        let mesh = try Replay.fuse(dir, Replay.filters[2])
        // Frames.
        let fd = try Data(contentsOf: dir.appendingPathComponent("frames.bin"))
        struct F { var t: Double; var pose: simd_float4x4; var k: simd_float3x3; var w: Int; var h: Int; var ok: Bool }
        var frames: [F] = []
        for i in 0..<(fd.count / 120) {
            fd.withUnsafeBytes { b in
                let o = i * 120
                var c: [SIMD4<Float>] = []
                for j in 0..<4 { c.append(b.loadUnaligned(fromByteOffset: o + 8 + j * 16, as: SIMD4<Float>.self)) }
                var k: [SIMD3<Float>] = []
                for j in 0..<3 { let q = o + 72 + j * 12; k.append(SIMD3(b.loadUnaligned(fromByteOffset: q, as: Float.self), b.loadUnaligned(fromByteOffset: q + 4, as: Float.self), b.loadUnaligned(fromByteOffset: q + 8, as: Float.self))) }
                frames.append(F(t: b.loadUnaligned(fromByteOffset: o, as: Double.self), pose: simd_float4x4(c[0], c[1], c[2], c[3]), k: simd_float3x3(k[0], k[1], k[2]),
                                w: Int(b.loadUnaligned(fromByteOffset: o + 108, as: Int32.self)), h: Int(b.loadUnaligned(fromByteOffset: o + 112, as: Int32.self)),
                                ok: b.loadUnaligned(fromByteOffset: o + 116, as: Int32.self) == 2))
            }
        }
        // Depth frames (for each photo's depth).
        let dd = try Data(contentsOf: dir.appendingPathComponent("depth.bin"))
        var depth: [(t: Double, w: Int, h: Int, mm: [UInt16], conf: [UInt8])] = []
        var at = 0
        while at + 12 <= dd.count {
            let t = dd.subdata(in: at..<(at + 8)).withUnsafeBytes { $0.loadUnaligned(as: Double.self) }
            let len = Int(dd.subdata(in: (at + 8)..<(at + 12)).withUnsafeBytes { $0.loadUnaligned(as: UInt32.self) })
            at += 12
            guard at + len <= dd.count, let raw = try? (dd.subdata(in: at..<(at + len)) as NSData).decompressed(using: .lzfse) as Data else { break }
            at += len
            let w = Int(raw.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 0, as: UInt16.self) }), h = Int(raw.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 2, as: UInt16.self) })
            let mm = raw.subdata(in: 4..<(4 + w * h * 2)).withUnsafeBytes { Array($0.bindMemory(to: UInt16.self)) }
            let conf = [UInt8](raw.subdata(in: (4 + w * h * 2)..<min(raw.count, 4 + w * h * 3)))
            depth.append((t, w, h, mm, conf))
        }
        // Floor: the lowest dense level of the surface.
        let ys = mesh.positions.map(\.y).sorted()
        let floorY = ys.isEmpty ? 0 : ys[ys.count / 100]
        let shift = SIMD3<Float>(0, -floorY, 0)
        // Photos from the video.
        let w = try Zip.Writer(url: url)
        var photos: [[String: Any]] = []
        var shots: [(pose: simd_float4x4, fx: Float, fy: Float, cx: Float, cy: Float, w: Int, h: Int, rgba: [UInt8])] = []
        let asset = AVURLAsset(url: dir.appendingPathComponent("video.mov"))
        guard let track = try await asset.loadTracks(withMediaType: .video).first else { throw NSError(domain: "recover", code: 1) }
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA])
        reader.add(output); reader.startReading()
        let ctx = CIContext()
        var last: simd_float4x4?, fi = 0
        let start = frames.first?.t ?? 0
        while let sample = output.copyNextSampleBuffer() {
            let t = start + CMSampleBufferGetPresentationTimeStamp(sample).seconds
            while fi + 1 < frames.count, frames[fi + 1].t <= t + 0.001 { fi += 1 }
            let f = frames[fi]
            guard f.ok, photos.count < 160, let buffer = CMSampleBufferGetImageBuffer(sample) else { continue }
            if let last {
                let moved = simd_distance(last.columns.3.ux3, f.pose.columns.3.ux3)
                let turned = acos(min(1, max(-1, simd_dot(last.columns.2.ux3, f.pose.columns.2.ux3)))) * 180 / .pi
                guard moved > 0.2 || turned > 15 else { continue }
            }
            last = f.pose
            let scale = 1024 / Float(CVPixelBufferGetWidth(buffer))
            let img = CIImage(cvPixelBuffer: buffer).transformed(by: CGAffineTransform(scaleX: CGFloat(scale), y: CGFloat(scale)))
            guard let jpeg = ctx.jpegRepresentation(of: img, colorSpace: CGColorSpace(name: CGColorSpace.sRGB)!, options: [:]),
                  let cg = ctx.createCGImage(img, from: img.extent) else { continue }
            let pw = cg.width, ph = cg.height
            var rgba = [UInt8](repeating: 0, count: pw * ph * 4)
            let bc = CGContext(data: &rgba, width: pw, height: ph, bitsPerComponent: 8, bytesPerRow: pw * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
            bc?.draw(cg, in: CGRect(x: 0, y: 0, width: pw, height: ph))
            let i = photos.count
            try w.add(String(format: "photos/%03d.jpg", i), data: jpeg)
            var pose = f.pose
            pose.columns.3 += SIMD4(shift, 0)
            var entry: [String: Any] = ["file": String(format: "photos/%03d.jpg", i), "width": pw, "height": ph,
                                        "fx": f.k[0][0] * scale, "fy": f.k[1][1] * scale, "cx": f.k[2][0] * scale, "cy": f.k[2][1] * scale, "time": t - start,
                                        "pose": [pose.columns.0, pose.columns.1, pose.columns.2, pose.columns.3].flatMap { [$0.x, $0.y, $0.z, $0.w] }]
            if let d = depth.min(by: { abs($0.t - t) < abs($1.t - t) }), abs(d.t - t) < 0.15 {
                let dw = d.w / 2, dh = d.h / 2
                var mm = [UInt16](repeating: 0, count: dw * dh)
                for y in 0..<dh { for x in 0..<dw { let j = (y * 2) * d.w + x * 2; if d.conf.isEmpty || d.conf[j] >= 1 { mm[y * dw + x] = d.mm[j] } } }
                try w.add(String(format: "depth/%03d.bin", i), data: mm.withUnsafeBytes { Data($0) })
                let sx = Float(dw) / Float(f.w), sy = Float(dh) / Float(f.h)
                entry["depth"] = ["file": String(format: "depth/%03d.bin", i), "width": dw, "height": dh,
                                  "fx": f.k[0][0] * sx, "fy": f.k[1][1] * sy, "cx": f.k[2][0] * sx, "cy": f.k[2][1] * sy]
            }
            photos.append(entry)
            shots.append((pose, f.k[0][0] * scale, f.k[1][1] * scale, f.k[2][0] * scale, f.k[2][1] * scale, pw, ph, rgba))
        }
        try w.add("photos.json", data: try JSONSerialization.data(withJSONObject: ["photos": photos]))
        // Mesh and points (the mesh's corners), coloured from the first photo that sees each.
        let positions = mesh.positions.map { $0 + shift }
        func colour(_ p: SIMD3<Float>) -> [UInt8] {
            for s in shots {
                let c = s.pose.inverse * SIMD4(p, 1)
                guard c.z < -0.1 else { continue }
                let u = Int(s.fx * c.x / -c.z + s.cx), v = Int(s.cy - s.fy * c.y / -c.z)
                guard u >= 0, v >= 0, u < s.w, v < s.h else { continue }
                let o = (v * s.w + u) * 4
                return [s.rgba[o], s.rgba[o + 1], s.rgba[o + 2]]
            }
            return [150, 150, 150]
        }
        var ply = Data("ply\nformat binary_little_endian 1.0\nelement vertex \(positions.count)\nproperty float x\nproperty float y\nproperty float z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n".utf8)
        var meshPly = Data("ply\nformat binary_little_endian 1.0\ncomment RoomScape harness recover\nelement vertex \(positions.count)\nproperty float x\nproperty float y\nproperty float z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\nelement face \(mesh.triangles.count / 3)\nproperty list uchar int vertex_indices\nend_header\n".utf8)
        for p in positions {
            var v = p
            for k in 0..<3 { withUnsafeBytes(of: &v[k]) { ply.append(contentsOf: $0); meshPly.append(contentsOf: $0) } }
            let rgb = colour(p)
            ply.append(contentsOf: rgb); meshPly.append(contentsOf: rgb)
        }
        for t in stride(from: 0, to: mesh.triangles.count, by: 3) {
            meshPly.append(3)
            for k in 0..<3 { var idx = Int32(mesh.triangles[t + k]); withUnsafeBytes(of: &idx) { meshPly.append(contentsOf: $0) } }
        }
        try w.add("points.ply", data: ply)
        try w.add("mesh.ply", data: meshPly)
        let top = ys.isEmpty ? 2.5 : ys[ys.count * 995 / 1000] - floorY
        let manifest: [String: Any] = [
            "format": "rmsc", "version": 1, "id": UUID().uuidString, "revision": 1, "revisionBy": "harness",
            "name": name, "created": ISO8601DateFormatter().string(from: Date()),
            "source": ["platform": "harness", "app": "RoomScape test harness (recovered from a raw recording)", "device": "iPhone"],
            "floorY": 0, "ceilingY": top,
            "counts": ["points": positions.count, "photos": photos.count, "triangles": mesh.triangles.count / 3],
            "objects": [[String: Any]]()]
        try w.add("manifest.json", data: try JSONSerialization.data(withJSONObject: manifest, options: .prettyPrinted))
        try w.finish()
        print(String(format: "Recovered %@: %d photos, %d triangles, %d points, height %.2f m, in %.1f s", url.lastPathComponent, photos.count,
                     mesh.triangles.count / 3, positions.count, top, Date().timeIntervalSince(started)))
    }
}
