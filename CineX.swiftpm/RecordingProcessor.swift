import AVFoundation
import CoreImage
import Foundation
import ImageIO
import UniformTypeIdentifiers
import simd

/// "Process later": builds a scan's surface, points and photos from its raw recording (RawRecorder), paced by the
/// phone's heat (ThermalGovernor). Used by "Rebuild from recording"; the same steps will run after Done once the
/// recording path matches the live one.
///
/// Never loses data: the scan's current points, photos, mesh and meta move into `before-rebuild/` first.
enum RecordingProcessor {
    struct Progress: Sendable { var stage: String; var fraction: Float; var note: String? }

    struct FrameRecord { var time: Double; var pose: simd_float4x4; var k: simd_float3x3; var width: Int; var height: Int; var tracking: Int32 }

    static func hasRecording(_ scan: SavedScan) -> Bool {
        FileManager.default.fileExists(atPath: scan.captureURL.appendingPathComponent("recording/frames.bin").path)
    }

    static func rebuild(_ scan: SavedScan, progress: @escaping @Sendable (Progress) -> Void) async throws {
        let rec = scan.captureURL.appendingPathComponent("recording")
        let frames = try readFrames(rec.appendingPathComponent("frames.bin"))
        guard !frames.isEmpty else { throw NSError(domain: "Rebuild", code: 1, userInfo: [NSLocalizedDescriptionKey: "the recording has no frames"]) }
        let clears = (try? JSONSerialization.jsonObject(with: Data(contentsOf: rec.appendingPathComponent("clears.json")))) as? [[String: Any]] ?? []
        let note: @Sendable (String?) -> Void = { n in progress(Progress(stage: "", fraction: -1, note: n)) }

        // 1. Surface: fuse every depth frame into the TSDF (4 cm, as live), skipping what a later tap cleared.
        let tsdf = Tsdf(capacity: 700_000, voxel: 0.04, truncation: 0.08, carve: 1.5)
        var depthFrames: [(time: Double, w: Int, h: Int, mm: [UInt16], conf: [UInt8])] = []
        let depthData = try Data(contentsOf: rec.appendingPathComponent("depth.bin"))
        var at = 0
        while at + 12 <= depthData.count {
            let time = depthData.subdata(in: at..<(at + 8)).withUnsafeBytes { $0.loadUnaligned(as: Double.self) }
            let len = Int(depthData.subdata(in: (at + 8)..<(at + 12)).withUnsafeBytes { $0.loadUnaligned(as: UInt32.self) })
            at += 12
            guard at + len <= depthData.count else { break }
            let packed = depthData.subdata(in: at..<(at + len))
            at += len
            let raw = (try? (packed as NSData).decompressed(using: .lzfse) as Data) ?? packed
            guard raw.count >= 4 else { continue }
            let w = Int(raw.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 0, as: UInt16.self) })
            let h = Int(raw.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 2, as: UInt16.self) })
            guard raw.count >= 4 + w * h * 2 else { continue }
            let mm = raw.subdata(in: 4..<(4 + w * h * 2)).withUnsafeBytes { Array($0.bindMemory(to: UInt16.self)) }
            let conf = raw.count >= 4 + w * h * 3 ? [UInt8](raw.subdata(in: (4 + w * h * 2)..<(4 + w * h * 3))) : []
            depthFrames.append((time, w, h, mm, conf))
        }
        // Points: the depth samples in 2 cm cells (averaged), like the live scan, for the splats and points views.
        var cells: [SIMD3<Int32>: (sum: SIMD3<Float>, n: Int32)] = [:]
        cells.reserveCapacity(400_000)
        var fi = 0
        for (n, d) in depthFrames.enumerated() {
            while fi + 1 < frames.count, abs(frames[fi + 1].time - d.time) <= abs(frames[fi].time - d.time) { fi += 1 }
            let f = frames[fi]
            guard f.tracking == 2 else { continue }
            // Depth intrinsics: the camera's, scaled to the depth map.
            let sx = Float(d.w) / Float(f.width), sy = Float(d.h) / Float(f.height)
            let fx = f.k[0][0] * sx, fy = f.k[1][1] * sy, cx = f.k[2][0] * sx, cy = f.k[2][1] * sy
            let origin = SIMD3(f.pose.columns.3.x, f.pose.columns.3.y, f.pose.columns.3.z)
            let step = ThermalGovernor.batchScale < 0.5 ? 4 : 2 // ease back when warm
            // Medium or high confidence, no flying pixels at depth edges (they bridged the gaps between shelf boards
            // and buried them), up to 4 m: on rk's 9 Nov recording this keeps all of the live surface and adds 7%
            // (any confidence, unfiltered, added 22%, much of it bridges). Checked with `harness compare`.
            func depth(_ x: Int, _ y: Int) -> Float { Float(d.mm[y * d.w + x]) / 1000 }
            for y in stride(from: 1, to: d.h - 1, by: step) {
                for x in stride(from: 1, to: d.w - 1, by: step) {
                    let z = depth(x, y)
                    guard z > 0.2, z < 4, d.conf.isEmpty || d.conf[y * d.w + x] >= 1 else { continue }
                    let tol = z * 0.04 + 0.02
                    if abs(depth(x - 1, y) - z) > tol || abs(depth(x + 1, y) - z) > tol || abs(depth(x, y - 1) - z) > tol || abs(depth(x, y + 1) - z) > tol { continue }
                    // ARKit camera: x right, y up, looking down -z (image rows go down).
                    let pc = SIMD4((Float(x) - cx) * z / fx, -(Float(y) - cy) * z / fy, -z, 1)
                    let pw4 = f.pose * pc
                    let pw = SIMD3(pw4.x, pw4.y, pw4.z)
                    if clears.contains(where: { Self.cleared(pw, at: d.time, by: $0) }) { continue }
                    tsdf.integrate(origin: origin, point: pw)
                    if cells.count < 1_200_000 {
                        let k = SIMD3(Int32((pw.x / 0.02).rounded(.down)), Int32((pw.y / 0.02).rounded(.down)), Int32((pw.z / 0.02).rounded(.down)))
                        let c = cells[k] ?? (.zero, 0)
                        cells[k] = (c.sum + pw, c.n + 1)
                    }
                }
            }
            if n % 10 == 0 {
                progress(Progress(stage: "Building the surface", fraction: 0.6 * Float(n) / Float(depthFrames.count), note: nil))
                await ThermalGovernor.pace(note: note)
            }
        }
        let scratch = Tsdf.MeshScratch()
        _ = tsdf.extractMesh(near: .zero, radius: 10_000, solidWeight: 20, into: scratch)
        let mesh = ScanMesh(positions: scratch.positions, triangles: scratch.triangles)

        // 2. Photos: from the video, every 20 cm or 15°, with the recorded pose and lens.
        progress(Progress(stage: "Picking photos", fraction: 0.6, note: nil))
        let photos = try await keyframes(rec.appendingPathComponent("video.mov"), frames: frames, depth: depthFrames, progress: progress, note: note)

        // 3. Write, keeping the old files.
        progress(Progress(stage: "Saving", fraction: 0.95, note: nil))
        // Cells seen at least twice (a single sighting is often a stray sample).
        let points = cells.values.filter { $0.n >= 2 }.map { $0.sum / Float($0.n) }
        try write(scan, mesh: mesh, points: points.isEmpty ? mesh.positions : points, photos: photos)
        progress(Progress(stage: "Done", fraction: 1, note: nil))
    }

    // MARK: Reading

    static func readFrames(_ url: URL) throws -> [FrameRecord] {
        let data = try Data(contentsOf: url)
        let size = 8 + 64 + 36 + 12
        var out: [FrameRecord] = []
        var at = 0
        while at + size <= data.count {
            let r = data.subdata(in: at..<(at + size))
            at += size
            r.withUnsafeBytes { b in
                let time = b.loadUnaligned(fromByteOffset: 0, as: Double.self)
                var cols: [SIMD4<Float>] = []
                for c in 0..<4 { cols.append(b.loadUnaligned(fromByteOffset: 8 + c * 16, as: SIMD4<Float>.self)) }
                var kc: [SIMD3<Float>] = []
                for c in 0..<3 {
                    let o = 72 + c * 12
                    kc.append(SIMD3(b.loadUnaligned(fromByteOffset: o, as: Float.self), b.loadUnaligned(fromByteOffset: o + 4, as: Float.self),
                                    b.loadUnaligned(fromByteOffset: o + 8, as: Float.self)))
                }
                let w = b.loadUnaligned(fromByteOffset: 108, as: Int32.self), h = b.loadUnaligned(fromByteOffset: 112, as: Int32.self)
                let t = b.loadUnaligned(fromByteOffset: 116, as: Int32.self)
                out.append(FrameRecord(time: time, pose: simd_float4x4(cols[0], cols[1], cols[2], cols[3]),
                                       k: simd_float3x3(kc[0], kc[1], kc[2]), width: Int(w), height: Int(h), tracking: t))
            }
        }
        return out
    }

    /// Whether a tap cleared this point from frames before it (the tap's cone, as it was on screen).
    static func cleared(_ p: SIMD3<Float>, at time: Double, by c: [String: Any]) -> Bool {
        guard let t = c["time"] as? Double, time < t, let vp = c["viewProjection"] as? [NSNumber], vp.count == 16,
              let center = c["center"] as? [NSNumber], let size = c["viewSize"] as? [NSNumber],
              let r = (c["radius"] as? NSNumber)?.floatValue, let limit = (c["limit"] as? NSNumber)?.floatValue else { return false }
        let v = vp.map(\.floatValue)
        let m = simd_float4x4(SIMD4(v[0], v[1], v[2], v[3]), SIMD4(v[4], v[5], v[6], v[7]), SIMD4(v[8], v[9], v[10], v[11]), SIMD4(v[12], v[13], v[14], v[15]))
        let q = m * SIMD4(p, 1)
        guard q.w > 0.05, q.w <= limit else { return false }
        let sx = (q.x / q.w + 1) / 2 * size[0].floatValue, sy = (1 - q.y / q.w) / 2 * size[1].floatValue
        return simd_distance(SIMD2(sx, sy), SIMD2(center[0].floatValue, center[1].floatValue)) < r
    }

    /// Photos from the video where the phone had moved 20 cm or turned 15° since the last one.
    static func keyframes(_ url: URL, frames: [FrameRecord], depth: [(time: Double, w: Int, h: Int, mm: [UInt16], conf: [UInt8])],
                          progress: @escaping @Sendable (Progress) -> Void, note: @escaping @Sendable (String?) -> Void) async throws -> [Keyframe] {
        let asset = AVURLAsset(url: url)
        guard let track = try await asset.loadTracks(withMediaType: .video).first else { return [] }
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA])
        reader.add(output)
        reader.startReading()
        let start = frames[0].time
        let ctx = CIContext(options: [.cacheIntermediates: false])
        var out: [Keyframe] = []
        var last: simd_float4x4?
        var fi = 0
        while let sample = output.copyNextSampleBuffer() {
            let t = start + CMSampleBufferGetPresentationTimeStamp(sample).seconds
            while fi + 1 < frames.count, frames[fi + 1].time <= t + 0.001 { fi += 1 }
            let f = frames[fi]
            guard f.tracking == 2 else { continue }
            if let last {
                let moved = simd_distance(SIMD3(f.pose.columns.3.x, f.pose.columns.3.y, f.pose.columns.3.z), SIMD3(last.columns.3.x, last.columns.3.y, last.columns.3.z))
                let turned = acos(min(1, max(-1, simd_dot(SIMD3(f.pose.columns.2.x, f.pose.columns.2.y, f.pose.columns.2.z), SIMD3(last.columns.2.x, last.columns.2.y, last.columns.2.z))))) * 180 / .pi
                guard moved > 0.2 || turned > 15 else { continue }
            }
            guard out.count < 160, let buffer = CMSampleBufferGetImageBuffer(sample) else { continue }
            last = f.pose
            let w = CVPixelBufferGetWidth(buffer)
            let scale = 1024 / Float(w)
            let image = CIImage(cvPixelBuffer: buffer).transformed(by: CGAffineTransform(scaleX: CGFloat(scale), y: CGFloat(scale)))
            guard let jpeg = ctx.jpegRepresentation(of: image, colorSpace: CGColorSpace(name: CGColorSpace.sRGB)!,
                                                    options: [kCGImageDestinationLossyCompressionQuality as CIImageRepresentationOption: 0.8]) else { continue }
            var kf = Keyframe(timestamp: t, cameraToWorld: f.pose, fx: f.k[0][0] * scale, fy: f.k[1][1] * scale,
                              cx: f.k[2][0] * scale, cy: f.k[2][1] * scale, width: 1024, height: Int(Float(f.height) * scale), jpeg: jpeg, exposure: nil)
            // Its depth at half size (128 × 96), as live photos keep it.
            if let d = depth.min(by: { abs($0.time - t) < abs($1.time - t) }), abs(d.time - t) < 0.15 {
                let dw = d.w / 2, dh = d.h / 2
                var small = [UInt16](repeating: 0, count: dw * dh)
                for y in 0..<dh { for x in 0..<dw { small[y * dw + x] = d.mm[(y * 2) * d.w + x * 2] } }
                kf.depth = small; kf.depthWidth = dw; kf.depthHeight = dh
            }
            out.append(kf)
            if out.count % 5 == 0 {
                progress(Progress(stage: "Picking photos", fraction: 0.6 + 0.3 * Float(fi) / Float(frames.count), note: nil))
                await ThermalGovernor.pace(note: note)
            }
        }
        return out
    }

    // MARK: Writing

    static func write(_ scan: SavedScan, mesh: ScanMesh, points pointPositions: [SIMD3<Float>], photos: [Keyframe]) throws {
        let fm = FileManager.default
        let folder = scan.captureURL
        // The current files move aside first (a rebuild never loses the scan it replaces).
        let backup = folder.appendingPathComponent("before-rebuild")
        try? fm.removeItem(at: backup)
        try fm.createDirectory(at: backup, withIntermediateDirectories: true)
        for name in ["points.bin", "keyframes.json", "photos", "mesh.bin", "meta.json", "surfels.bin", "trained.bin", "thumbnail-kind"] {
            let src = folder.appendingPathComponent(name)
            if fm.fileExists(atPath: src.path) { try fm.moveItem(at: src, to: backup.appendingPathComponent(name)) }
        }
        try fm.createDirectory(at: folder.appendingPathComponent("photos"), withIntermediateDirectories: true)
        var records: [KeyframeRecord] = []
        for (i, k) in photos.enumerated() {
            let file = String(format: "photos/%03d.jpg", i)
            try k.jpeg.write(to: folder.appendingPathComponent(file))
            var depthFile: String?
            if let d = k.depth, !d.isEmpty {
                depthFile = String(format: "photos/%03d.depth", i)
                try d.withUnsafeBytes { Data($0) }.write(to: folder.appendingPathComponent(depthFile!))
            }
            let m = k.cameraToWorld
            records.append(KeyframeRecord(file: file, timestamp: k.timestamp, width: k.width, height: k.height,
                                          fx: k.fx, fy: k.fy, cx: k.cx, cy: k.cy,
                                          cameraToWorld: [m.columns.0, m.columns.1, m.columns.2, m.columns.3].flatMap { [$0.x, $0.y, $0.z, $0.w] },
                                          exposure: nil, depthFile: depthFile, depthWidth: depthFile == nil ? nil : k.depthWidth,
                                          depthHeight: depthFile == nil ? nil : k.depthHeight))
        }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try encoder.encode(records).write(to: folder.appendingPathComponent("keyframes.json"))
        try mesh.write(to: folder.appendingPathComponent(ScanMesh.fileName))
        // Points coloured from the first photo that sees each (grey where none does).
        let colours = colour(pointPositions, photos)
        let points = pointPositions.enumerated().map { i, p in
            LivePoint(x: p.x, y: p.y, z: p.z, rgba: UInt32(colours[i].x) | (UInt32(colours[i].y) << 8) | (UInt32(colours[i].z) << 16) | (255 << 24))
        }
        try points.withUnsafeBytes { Data($0) }.write(to: folder.appendingPathComponent("points.bin"))
        try encoder.encode(CaptureMeta(pointCount: points.count, keyframeCount: records.count, voxelSize: 0.02))
            .write(to: folder.appendingPathComponent("meta.json"))
    }

    static func colour(_ positions: [SIMD3<Float>], _ frames: [Keyframe]) -> [SIMD3<UInt8>] {
        var out = [SIMD3<UInt8>](repeating: SIMD3(150, 150, 150), count: positions.count)
        var done = [Bool](repeating: false, count: positions.count)
        for f in frames {
            guard let source = CGImageSourceCreateWithData(f.jpeg as CFData, nil),
                  let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                                                          kCGImageSourceThumbnailMaxPixelSize: 320] as CFDictionary) else { continue }
            let w = cg.width, h = cg.height
            var px = [UInt8](repeating: 0, count: w * h * 4)
            guard let ctx = CGContext(data: &px, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { continue }
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
            let s = Float(w) / Float(f.width)
            let inv = f.cameraToWorld.inverse
            for i in positions.indices where !done[i] {
                let c = inv * SIMD4(positions[i], 1) // ARKit camera: looking down -z, y up
                guard c.z < -0.1 else { continue }
                let u = (f.fx * c.x / -c.z + f.cx) * s, v = (f.fy * -c.y / -c.z + f.cy) * s
                guard u >= 0, v >= 0, u < Float(w), v < Float(h) else { continue }
                let o = (Int(v) * w + Int(u)) * 4
                out[i] = SIMD3(px[o], px[o + 1], px[o + 2])
                done[i] = true
            }
        }
        return out
    }
}
