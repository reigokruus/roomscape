import ARKit
import AVFoundation
import Compression
import Foundation

/// "Record raw now, process later" (rk 9 Nov): while scanning, the raw stream goes into a recording, so the heavy
/// building can run after Done from it, and old scans can be rebuilt when the processing improves. The same files
/// feed the Mac's replay / test harness.
///
/// A recording is a folder (`<scan>.capture/recording/`) with:
/// - `video.mov`: the camera picture, HEVC, at the rate the scan reads frames (10 / s), full ARKit resolution;
/// - `depth.bin`: per frame, LiDAR depth (UInt16 mm, 256 × 192) and confidence (UInt8), LZFSE-compressed;
/// - `frames.bin`: per frame, time, camera pose, intrinsics, image size and tracking state;
/// - `clears.json`: tap-to-clear events (time and the cleared cone), so processing drops what came before them;
/// - `info.json`: format version, device, start time.
/// Recordings stay on the phone (too big to sync); nothing deletes them automatically ("data is gold").
final class RawRecorder: @unchecked Sendable {
    /// Turns recording off.
    static let enabled = true
    static let formatVersion = 1

    let folder: URL
    private let queue = DispatchQueue(label: "CineX.raw-recorder", qos: .utility, autoreleaseFrequency: .workItem)
    private var writer: AVAssetWriter?
    private var input: AVAssetWriterInput?
    private var adaptor: AVAssetWriterInputPixelBufferAdaptor?
    private var depthOut: FileHandle?
    private var framesOut: FileHandle?
    private var start: TimeInterval?
    private var clears: [[String: Any]] = []
    private var pending = 0
    private(set) var frames = 0
    private var failed = false

    /// A fresh recording in a temporary folder (moved into the scan's folder when it's saved).
    init() {
        folder = FileManager.default.temporaryDirectory.appendingPathComponent("recording-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        FileManager.default.createFile(atPath: folder.appendingPathComponent("depth.bin").path, contents: nil)
        FileManager.default.createFile(atPath: folder.appendingPathComponent("frames.bin").path, contents: nil)
        depthOut = try? FileHandle(forWritingTo: folder.appendingPathComponent("depth.bin"))
        framesOut = try? FileHandle(forWritingTo: folder.appendingPathComponent("frames.bin"))
        let info: [String: Any] = ["format": "roomscape-recording", "version": Self.formatVersion, "device": "iPhone",
                                   "started": ISO8601DateFormatter().string(from: Date())]
        try? JSONSerialization.data(withJSONObject: info).write(to: folder.appendingPathComponent("info.json"))
    }

    /// Main thread, with the frame the scan just read: copies what's needed (so ARKit gets its buffers back at
    /// once) and writes it on the recorder's queue. Frames are skipped while two are still being written.
    func append(_ frame: ARFrame) {
        guard !failed, pending < 2, let image = Self.copy(frame.capturedImage) else { return }
        let depth = frame.sceneDepth.map { Self.depthBytes($0) }
        let camera = frame.camera
        let pose = camera.transform, k = camera.intrinsics, res = camera.imageResolution
        let tracking: Int32 = { if case .normal = camera.trackingState { return 2 }; if case .limited = camera.trackingState { return 1 }; return 0 }()
        let time = frame.timestamp
        pending += 1
        queue.async { [self] in
            defer { DispatchQueue.main.async { self.pending -= 1 } }
            if writer == nil { startVideo(width: CVPixelBufferGetWidth(image), height: CVPixelBufferGetHeight(image), at: time) }
            guard let start, let adaptor, let input else { return }
            if input.isReadyForMoreMediaData {
                adaptor.append(image, withPresentationTime: CMTime(seconds: time - start, preferredTimescale: 600))
            }
            // frames.bin: time (f64), pose (16 f32), intrinsics (9 f32), width, height, tracking (i32).
            var rec = Data()
            withUnsafeBytes(of: time) { rec.append(contentsOf: $0) }
            for c in [pose.columns.0, pose.columns.1, pose.columns.2, pose.columns.3] { withUnsafeBytes(of: c) { rec.append(contentsOf: $0) } }
            for c in [k.columns.0, k.columns.1, k.columns.2] { for v in [c.x, c.y, c.z] { withUnsafeBytes(of: v) { rec.append(contentsOf: $0) } } }
            for v in [Int32(res.width), Int32(res.height), tracking] { withUnsafeBytes(of: v) { rec.append(contentsOf: $0) } }
            framesOut?.write(rec)
            // depth.bin: time (f64), size (u32), compressed bytes (width u16, height u16, mm[], confidence[]).
            if let depth {
                var d = Data()
                withUnsafeBytes(of: time) { d.append(contentsOf: $0) }
                withUnsafeBytes(of: UInt32(depth.count)) { d.append(contentsOf: $0) }
                d.append(depth)
                depthOut?.write(d)
            }
            frames += 1
        }
    }

    /// A tap cleared part of the scan: processing drops what's inside this cone from frames before `time`.
    func cleared(time: TimeInterval, viewProjection: simd_float4x4, center: CGPoint, radius: CGFloat, viewSize: CGSize, limit: Float) {
        queue.async { [self] in
            clears.append(["time": time, "center": [center.x, center.y], "radius": radius,
                           "viewSize": [viewSize.width, viewSize.height], "limit": limit,
                           "viewProjection": [viewProjection.columns.0, viewProjection.columns.1, viewProjection.columns.2, viewProjection.columns.3].flatMap { [$0.x, $0.y, $0.z, $0.w] }])
        }
    }

    /// ARKit's world frame jumped and was corrected (LiveCapture's pose guard): from `time` on the recorded poses are
    /// already corrected, and the frames in between were not recorded. Kept as recording/pose-jump.json for the tools.
    func poseJump(time: TimeInterval, correction: simd_float4x4) {
        queue.async { [self] in
            let m = [correction.columns.0, correction.columns.1, correction.columns.2, correction.columns.3].flatMap { [$0.x, $0.y, $0.z, $0.w] }
            let entry: [String: Any] = ["time": time, "correction": m, "applied": "live (ARSession.setWorldOrigin)"]
            var list = (try? JSONSerialization.jsonObject(with: Data(contentsOf: folder.appendingPathComponent("pose-jump.json")))) as? [[String: Any]] ?? []
            list.append(entry)
            if let d = try? JSONSerialization.data(withJSONObject: list, options: .prettyPrinted) {
                try? d.write(to: folder.appendingPathComponent("pose-jump.json"))
            }
        }
    }

    /// Finishes the files; calls back with the folder and its size in bytes.
    func finish(_ done: @escaping (URL, Int64) -> Void) {
        queue.async { [self] in
            try? framesOut?.close(); try? depthOut?.close()
            if let clearsData = try? JSONSerialization.data(withJSONObject: clears) {
                try? clearsData.write(to: folder.appendingPathComponent("clears.json"))
            }
            input?.markAsFinished()
            let folder = folder
            let complete = {
                let size = (FileManager.default.enumerator(at: folder, includingPropertiesForKeys: [.fileSizeKey])?.allObjects as? [URL] ?? [])
                    .reduce(Int64(0)) { $0 + Int64((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
                done(folder, size)
            }
            if let writer { writer.finishWriting(completionHandler: complete) } else { complete() }
        }
    }

    /// At launch: recordings left in the temporary folder by a scan that never finished (the app was killed) move to
    /// Documents/interrupted/, where the system doesn't clear them, to be rebuilt (harness `recover`, or later in the
    /// app). Returns how many.
    @discardableResult
    static func keepInterrupted() -> Int {
        let fm = FileManager.default
        let tmp = fm.temporaryDirectory
        let target = fm.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("interrupted")
        var kept = 0
        for url in (try? fm.contentsOfDirectory(at: tmp, includingPropertiesForKeys: nil)) ?? [] where url.lastPathComponent.hasPrefix("recording-") {
            guard fm.fileExists(atPath: url.appendingPathComponent("frames.bin").path) else { continue }
            try? fm.createDirectory(at: target, withIntermediateDirectories: true)
            if (try? fm.moveItem(at: url, to: target.appendingPathComponent(url.lastPathComponent))) != nil { kept += 1 }
        }
        // Its hardware log too.
        for url in (try? fm.contentsOfDirectory(at: tmp, includingPropertiesForKeys: nil)) ?? [] where url.lastPathComponent.hasPrefix("perf-scan-") {
            try? fm.createDirectory(at: target, withIntermediateDirectories: true)
            try? fm.moveItem(at: url, to: target.appendingPathComponent(url.lastPathComponent))
        }
        return kept
    }

    /// Moves a finished recording into a scan's capture folder, as `recording`. Never replaces one that's there
    /// (Continue scan keeps the scan's first recording): then it goes next to it as `recording-continue[-n]`.
    /// True if it became the scan's `recording` (the one processing reads).
    @discardableResult
    static func move(_ folder: URL, into scan: SavedScan) -> Bool {
        let fm = FileManager.default
        var target = scan.captureURL.appendingPathComponent("recording")
        let main = !fm.fileExists(atPath: target.path)
        var n = 1
        while fm.fileExists(atPath: target.path) {
            target = scan.captureURL.appendingPathComponent(n == 1 ? "recording-continue" : "recording-continue-\(n)")
            n += 1
        }
        do { try fm.moveItem(at: folder, to: target) } catch { return false }
        return main
    }

    // MARK: Internals

    private func startVideo(width: Int, height: Int, at time: TimeInterval) {
        guard let w = try? AVAssetWriter(outputURL: folder.appendingPathComponent("video.mov"), fileType: .mov) else { failed = true; return }
        // Crash-safe (rk 9 Nov: a scan killed mid-way left a video without its index): a movie fragment every 5 s, so a
        // killed app loses at most the last few seconds of video.
        w.movieFragmentInterval = CMTime(seconds: 5, preferredTimescale: 600)
        let settings: [String: Any] = [AVVideoCodecKey: AVVideoCodecType.hevc, AVVideoWidthKey: width, AVVideoHeightKey: height,
                                       AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 6_000_000]]
        let i = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
        i.expectsMediaDataInRealTime = true
        let a = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: i, sourcePixelBufferAttributes: nil)
        guard w.canAdd(i) else { failed = true; return }
        w.add(i)
        w.startWriting()
        w.startSession(atSourceTime: .zero)
        writer = w; input = i; adaptor = a; start = time
    }

    /// Depth (UInt16 mm) and confidence, LZFSE-compressed, with the size in front.
    private static func depthBytes(_ d: ARDepthData) -> Data {
        let map = d.depthMap
        let w = CVPixelBufferGetWidth(map), h = CVPixelBufferGetHeight(map)
        var raw = Data(capacity: 4 + w * h * 3)
        withUnsafeBytes(of: UInt16(w)) { raw.append(contentsOf: $0) }
        withUnsafeBytes(of: UInt16(h)) { raw.append(contentsOf: $0) }
        CVPixelBufferLockBaseAddress(map, .readOnly)
        if let base = CVPixelBufferGetBaseAddress(map) {
            let row = CVPixelBufferGetBytesPerRow(map)
            var mm = [UInt16](repeating: 0, count: w * h)
            for y in 0..<h {
                let p = base.advanced(by: y * row).assumingMemoryBound(to: Float32.self)
                for x in 0..<w { let v = p[x]; mm[y * w + x] = v.isFinite ? UInt16(max(0, min(65535, v * 1000))) : 0 }
            }
            mm.withUnsafeBytes { raw.append(contentsOf: $0) }
        }
        CVPixelBufferUnlockBaseAddress(map, .readOnly)
        if let conf = d.confidenceMap {
            CVPixelBufferLockBaseAddress(conf, .readOnly)
            if let base = CVPixelBufferGetBaseAddress(conf) {
                let row = CVPixelBufferGetBytesPerRow(conf)
                for y in 0..<h { raw.append(Data(bytes: base.advanced(by: y * row), count: w)) }
            }
            CVPixelBufferUnlockBaseAddress(conf, .readOnly)
        }
        return (try? (raw as NSData).compressed(using: .lzfse) as Data) ?? raw
    }

    /// A copy of a camera buffer (ARKit's own must go back quickly).
    private static func copy(_ src: CVPixelBuffer) -> CVPixelBuffer? {
        var out: CVPixelBuffer?
        let attrs: [String: Any] = [kCVPixelBufferIOSurfacePropertiesKey as String: [:]]
        CVPixelBufferCreate(nil, CVPixelBufferGetWidth(src), CVPixelBufferGetHeight(src), CVPixelBufferGetPixelFormatType(src),
                            attrs as CFDictionary, &out)
        guard let dst = out else { return nil }
        CVPixelBufferLockBaseAddress(src, .readOnly); CVPixelBufferLockBaseAddress(dst, [])
        defer { CVPixelBufferUnlockBaseAddress(src, .readOnly); CVPixelBufferUnlockBaseAddress(dst, []) }
        for plane in 0..<max(1, CVPixelBufferGetPlaneCount(src)) {
            guard let s = CVPixelBufferGetBaseAddressOfPlane(src, plane), let d = CVPixelBufferGetBaseAddressOfPlane(dst, plane) else { continue }
            let sRow = CVPixelBufferGetBytesPerRowOfPlane(src, plane), dRow = CVPixelBufferGetBytesPerRowOfPlane(dst, plane)
            let rows = CVPixelBufferGetHeightOfPlane(src, plane)
            for r in 0..<rows { memcpy(d.advanced(by: r * dRow), s.advanced(by: r * sRow), min(sRow, dRow)) }
        }
        return dst
    }
}
