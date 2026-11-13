import AVFoundation
import CoreImage
import Foundation
import ImageIO
import UniformTypeIdentifiers
import UIKit
import simd

/// "Process later": builds a scan's surface, points and photos from its raw recording (RawRecorder), paced by the
/// phone's heat (ThermalGovernor). Run through ProcessingQueue: after Done (the text patches; everything with
/// "Record, then process") and by "Rebuild from recording".
///
/// Never loses data: the scan's files a job replaces move into a backup folder first.
enum RecordingProcessor {
    struct Progress: Sendable { var stage: String; var fraction: Float; var note: String? }

    struct FrameRecord { var time: Double; var pose: simd_float4x4; var k: simd_float3x3; var width: Int; var height: Int; var tracking: Int32 }

    static func hasRecording(_ scan: SavedScan) -> Bool {
        FileManager.default.fileExists(atPath: scan.captureURL.appendingPathComponent("recording/frames.bin").path)
    }

    /// What a job makes: `rebuild` the whole scan (surface, points, photos, text) from the recording, `text` only
    /// the sharp text patches (after Done while the live scan stays the main result).
    /// `splat`: the photo-real room preview (SplatPreview), trained on the phone from the recording.
    enum Job: String, Codable, Sendable {
        case rebuild, text, splat
        /// The photo-real preview as a memory test (PhotoRealTest): from step 0 into splat-test/, the real one kept.
        case splatTest
        /// The memory test's "building from the recording": the same work as `rebuild`, in its own folder, and its
        /// results are thrown away instead of swapped into the scan (the scan is never changed).
        case rebuildTest
        var isPhotoReal: Bool { self == .splat || self == .splatTest }
    }

    typealias DepthFrame = (time: Double, w: Int, h: Int, mm: [UInt16], conf: [UInt8])

    /// Rebuilds the whole scan from its recording (kept for callers; same as `run(.rebuild, …)`).
    static func rebuild(_ scan: SavedScan, progress: @escaping @Sendable (Progress) -> Void) async throws {
        try await run(.rebuild, on: scan, progress: progress)
    }

    /// Runs a job on the scan's recording, paced by the phone's heat.
    ///
    /// Resumable: every finished stage stays in `<capture>/processing-<job>/`, so a job that was stopped part-way
    /// (background time ran out, the app was closed, the phone restarted) carries on from its last finished stage.
    /// Cancelling throws CancellationError and leaves the scan as it was. Only the last step swaps the new files in,
    /// and the files they replace move into a backup folder first (`before-rebuild`, `before-rebuild-2`, …).
    static func run(_ job: Job, on scan: SavedScan, progress: @escaping @Sendable (Progress) -> Void) async throws {
        if job.isPhotoReal { return try await SplatPreview.run(on: scan, test: job == .splatTest, progress: progress) }
        let fm = FileManager.default
        let rec = scan.captureURL.appendingPathComponent("recording")
        let work = scan.captureURL.appendingPathComponent("processing-\(job.rawValue)")
        if job == .rebuildTest { try? fm.removeItem(at: work) }   // the test always does the whole work
        try fm.createDirectory(at: work, withIntermediateDirectories: true)
        func done(_ stage: String) -> Bool { fm.fileExists(atPath: work.appendingPathComponent("\(stage).done").path) }
        func mark(_ stage: String) { fm.createFile(atPath: work.appendingPathComponent("\(stage).done").path, contents: Data()) }
        let note: @Sendable (String?) -> Void = { n in progress(Progress(stage: "", fraction: -1, note: n)) }
        // Where each stage's progress bar starts and ends.
        let rebuilding = job == .rebuild || job == .rebuildTest
        let span: [String: (Float, Float)] = rebuilding
            ? ["surface": (0.02, 0.6), "photos": (0.6, 0.85), "text": (0.85, 0.95), "save": (0.95, 1)]
            : ["text": (0.05, 0.95), "save": (0.95, 1)]
        func report(_ stage: String, _ title: String, _ f: Float) {
            let (a, b) = span[stage] ?? (0, 1)
            progress(Progress(stage: title, fraction: a + (b - a) * max(0, min(1, f)), note: nil))
        }

        progress(Progress(stage: "Reading the recording", fraction: 0, note: nil))
        let frames = try readFrames(rec.appendingPathComponent("frames.bin"))
        guard !frames.isEmpty else { throw NSError(domain: "Rebuild", code: 1, userInfo: [NSLocalizedDescriptionKey: "the recording has no frames"]) }
        let depthFrames = try readDepth(rec.appendingPathComponent("depth.bin"))
        try Task.checkCancellation()

        // 3. Sharp text: patches where text was seen, straightened onto their surface (DecalFinder). It runs alongside
        // the surface and photos: Vision works mostly on the Neural Engine and the surface on one CPU core (perf log
        // 9 Nov: surface 5 s at one P core, text 15 s, one after the other).
        let textDone = done("text")
        async let textStage: Void = {
            guard !textDone else { return }
            let out = work.appendingPathComponent("decals")
            try? FileManager.default.removeItem(at: out)
            let decalFrames = frames.map { DecalFinder.Frame(time: $0.time, pose: $0.pose, k: $0.k, width: $0.width, height: $0.height) }
            let depthAt: (Double) -> DecalFinder.Depth? = { t in
                depthFrames.nearest(t).map { i in let d = depthFrames[i]; return (time: d.time, w: d.w, h: d.h, mm: d.mm) }
            }
            _ = try await DecalFinder.find(recording: rec, frames: decalFrames, depthAt: depthAt, out: out,
                                           pace: { await ThermalGovernor.pace(note: note) },
                                           progress: { if job == .text { report("text", "Finding text", $0) } })
            FileManager.default.createFile(atPath: work.appendingPathComponent("text.done").path, contents: Data())
        }()
        if rebuilding {
            // 1. Surface and points.
            if !done("surface") {
                let clears = Clear.load(rec.appendingPathComponent("clears.json"))
                // The surface (TSDF) and the points are worked out side by side, on two cores.
                async let meshPart: (ScanMesh, [SIMD3<Float>]) = {
                    let t0 = Date()
                    if useGPU, let mesh = try await gpuSurface(frames: frames, depth: depthFrames, clears: clears,
                                                                progress: { report("surface", "Building the surface", $0) }, note: note) {
                        PerfLog.processing?.note("surface on the GPU", ms: Date().timeIntervalSince(t0) * 1000)
                        return (mesh, [])
                    }
                    let r = try await surface(frames: frames, depth: depthFrames, clears: clears, mesh: true, points: false,
                                              progress: { report("surface", "Building the surface", $0) }, note: note)
                    PerfLog.processing?.note("surface on the CPU", ms: Date().timeIntervalSince(t0) * 1000)
                    return r
                }()
                async let pointPart = surface(frames: frames, depth: depthFrames, clears: clears, mesh: false, points: true,
                                              progress: { _ in }, note: { _ in })
                let mesh = try await meshPart.0, points = try await pointPart.1
                try mesh.write(to: work.appendingPathComponent(ScanMesh.fileName))
                try points.withUnsafeBytes { Data($0) }.write(to: work.appendingPathComponent("points-raw.bin"))
                mark("surface")
            }
            // 2. Photos: from the video, every 20 cm or 15°, with the recorded pose and lens.
            if !done("photos") {
                report("photos", "Picking photos", 0)
                let photos = try await keyframes(rec.appendingPathComponent("video.mov"), frames: frames, depth: depthFrames,
                                                 progress: { report("photos", "Picking photos", $0) }, note: note)
                try? fm.removeItem(at: work.appendingPathComponent("photos"))
                try writePhotos(photos, into: work)
                mark("photos")
            }
        }
        // 3. (started above, alongside the surface and photos)
        if rebuilding, !textDone { report("text", "Finding text", 0) }
        try await textStage
        // 4. Points coloured from the photos, then swap the new files in.
        report("save", "Saving", 0)
        if rebuilding, !done("points") {
            let raw = try Data(contentsOf: work.appendingPathComponent("points-raw.bin"))
            let positions = raw.withUnsafeBytes { Array($0.bindMemory(to: SIMD3<Float>.self)) }
            let photos = loadPhotos(work)
            try writePoints(positions.isEmpty ? (ScanMesh.read(from: work.appendingPathComponent(ScanMesh.fileName))?.positions ?? []) : positions,
                            photos: photos, into: work)
            try? fm.removeItem(at: work.appendingPathComponent("points-raw.bin"))
            mark("points")
        }
        try Task.checkCancellation()
        if job == .rebuildTest {
            // the memory test: the work is done (its memory use is what the test needs); nothing goes into the scan
        } else if job == .rebuild {
            try swapIn(from: work, into: scan.captureURL, backupBase: "before-rebuild",
                       names: ["points.bin", "keyframes.json", "photos", ScanMesh.fileName, "meta.json", "decals"],
                       aside: ["surfels.bin", "trained.bin", "thumbnail-kind"])
            ScanStore.invalidateThumbnail(scan)
        } else {
            try swapIn(from: work, into: scan.captureURL, backupBase: "before-text", names: ["decals"], aside: [])
        }
        try? fm.removeItem(at: work)
        progress(Progress(stage: "Done", fraction: 1, note: nil))
    }

    /// The job's progress folder, if a job was stopped part-way (it resumes from there).
    static func hasUnfinished(_ job: Job, _ scan: SavedScan) -> Bool {
        FileManager.default.fileExists(atPath: scan.captureURL.appendingPathComponent("processing-\(job.rawValue)").path)
    }

    /// Fuse on the GPU (TsdfGPU; harness gpubench: 0.34 s vs 21.7 s on the CPU for a 3-minute recording, surfaces
    /// within 1%). The CPU fusion below stays as the fallback.
    static let useGPU = true

    /// The surface fused on the GPU, or nil (no Metal, the app not in the foreground, a GPU error, or a block too big for
    /// the phone: then the CPU does it). Two passes over the depth, one frame at a time, so memory stays flat: the
    /// room's extent first, then the fusion.
    static func gpuSurface(frames: [FrameRecord], depth depthFrames: DepthFrames, clears: [Clear],
                           progress: (Float) -> Void, note: @escaping @Sendable (String?) -> Void) async throws -> ScanMesh? {
        // GPU work is only allowed while the app is in the foreground (a background continued-processing task would
        // need the background-GPU entitlement); in the background the CPU fuses.
        let foreground = await MainActor.run { UIApplication.shared.applicationState != .background }
        guard foreground else { return nil }
        func frame(_ n: Int) -> (f: FrameRecord, d: DepthFrame)? {
            let d = depthFrames[n]
            guard d.w > 0, !frames.isEmpty else { return nil }
            var lo = 0, hi = frames.count - 1
            while lo < hi { let mid = (lo + hi) / 2; if frames[mid].time < d.time { lo = mid + 1 } else { hi = mid } }
            if lo > 0, abs(frames[lo - 1].time - d.time) < abs(frames[lo].time - d.time) { lo -= 1 }
            let f = frames[lo]
            guard f.tracking == 2 else { return nil }
            return (f, d)
        }
        // Each depth frame filtered as for the CPU fusion (medium+ confidence, no flying pixels, 0.2-4 m, not cleared).
        // With `low`, also the low-confidence pixels for the hole fill (LowConfidenceFill), in `ml`.
        func filtered(_ f: FrameRecord, _ d: DepthFrame, bounds: Bool, low: Bool = false) -> (m: [Float], lo: SIMD3<Float>, hi: SIMD3<Float>, k: SIMD4<Float>, ml: [Float]) {
            let sx = Float(d.w) / Float(f.width), sy = Float(d.h) / Float(f.height)
            let fx = f.k[0][0] * sx, fy = f.k[1][1] * sy, cx = f.k[2][0] * sx, cy = f.k[2][1] * sy
            var m = [Float](repeating: 0, count: bounds ? 0 : d.w * d.h)
            var ml = [Float](repeating: 0, count: low && !bounds && !d.conf.isEmpty ? d.w * d.h : 0)
            var lo = SIMD3<Float>(repeating: .infinity), hi = SIMD3<Float>(repeating: -.infinity)
            func depth(_ x: Int, _ y: Int) -> Float { Float(d.mm[y * d.w + x]) / 1000 }
            for y in stride(from: 1, to: d.h - 1, by: bounds ? 8 : 1) {
                for x in stride(from: 1, to: d.w - 1, by: bounds ? 8 : 1) {
                    let z = depth(x, y)
                    guard z > 0.2, z < 4 else { continue }
                    let tol = z * 0.04 + 0.02
                    if abs(depth(x - 1, y) - z) > tol || abs(depth(x + 1, y) - z) > tol || abs(depth(x, y - 1) - z) > tol || abs(depth(x, y + 1) - z) > tol { continue }
                    if !d.conf.isEmpty, d.conf[y * d.w + x] < 1 {
                        guard !ml.isEmpty, LowConfidenceFill.use(x, y, width: d.w, height: d.h, fx: fx, fy: fy, cx: cx, cy: cy, z: { depth($0, $1) }) else { continue }
                        if !clears.isEmpty {
                            let p4 = f.pose * SIMD4((Float(x) - cx) * z / fx, -(Float(y) - cy) * z / fy, -z, 1)
                            if clears.contains(where: { $0.covers(SIMD3(p4.x, p4.y, p4.z), at: d.time) }) { continue }
                        }
                        ml[y * d.w + x] = z
                        continue
                    }
                    if bounds || !clears.isEmpty {
                        let p4 = f.pose * SIMD4((Float(x) - cx) * z / fx, -(Float(y) - cy) * z / fy, -z, 1)
                        let pw = SIMD3(p4.x, p4.y, p4.z)
                        if !clears.isEmpty, clears.contains(where: { $0.covers(pw, at: d.time) }) { continue }
                        if bounds { lo = simd_min(lo, pw); hi = simd_max(hi, pw); continue }
                    }
                    m[y * d.w + x] = z
                }
            }
            return (m, lo, hi, SIMD4(fx, fy, cx, cy), ml)
        }
        var lo = SIMD3<Float>(repeating: .infinity), hi = SIMD3<Float>(repeating: -.infinity)
        for n in depthFrames.indices where n % 2 == 0 {
            guard let (f, d) = frame(n) else { continue }
            let r = filtered(f, d, bounds: true)
            lo = simd_min(lo, r.lo); hi = simd_max(hi, r.hi)
            if n % 100 == 0 { try Task.checkCancellation(); progress(0.15 * Float(n) / Float(depthFrames.count)) }
        }
        guard lo.x.isFinite else { return nil }
        // A phone-sized block (12 M voxels, ~70 MB); bigger rooms go to the CPU.
        // Finer surface (test switch): 1.9 cm voxels up to 40 M (~240 MB), else the usual 4 cm.
        let fineSize = (hi - lo + 0.4) / FineSurface.voxel
        let fine = ProcessingQueue.fineSurface && Double(fineSize.x) * Double(fineSize.y) * Double(fineSize.z) < Double(FineSurface.maxVoxels)
        let vs: Float = fine ? FineSurface.voxel : 0.04
        let size = (hi - lo + 0.4) / 0.04
        guard fine || size.x * size.y * size.z < 12_000_000,
              let gpu = TsdfGPU(min: lo - 0.2, max: hi + 0.2, voxel: vs, truncation: vs * 2, carve: 1.5) else { return nil }
        // The hole fill's block (same size); without it the scan is built as before. Not with the finer surface (a
        // second 1.9 cm block would double the memory).
        let low = fine ? nil : TsdfGPU(min: lo - 0.2, max: hi + 0.2, voxel: 0.04, truncation: 0.08, carve: 1.5)
        // Each frame lined up with the surface fused so far (FrameAlign: only along directions the surface pins down,
        // drifting back to ARKit where it doesn't; rk 10 Nov: later passes over the bars fused shifted copies). Harness:
        // 10:34 bars median 0.75 -> 0.43 cm off their own surface, 19:00 room 0.93 -> 0.61 cm, 38 of 1944 frames
        // left as ARKit says. Stops after 25 s of aligning (the rest fused with the last correction).
        var aligner = FrameAlign()
        FrameAlign.leak = 0.05; FrameAlign.skipUnfit = false
        var alignTime = 0.0
        for n in depthFrames.indices {
            guard let (f, d) = frame(n) else { continue }
            let r = filtered(f, d, bounds: false, low: low != nil)
            var pose = f.pose
            if alignTime < 25 {
                let t0 = Date()
                gpu.finish()
                var pts: [SIMD3<Float>] = []
                pts.reserveCapacity(800)
                for y in stride(from: 2, to: d.h - 2, by: 6) { for x in stride(from: 2, to: d.w - 2, by: 6) {
                    let z = r.m[y * d.w + x]; guard z > 0.3, z < 3 else { continue }
                    pts.append(SIMD3((Float(x) + 0.5 - r.k.z) * z / r.k.x, -(Float(y) + 0.5 - r.k.w) * z / r.k.y, -z))
                } }
                pose = aligner.align(gpu, points: pts, pose: f.pose) ?? aligner.correction * f.pose
                alignTime += Date().timeIntervalSince(t0)
            } else {
                pose = aligner.correction * f.pose
            }
            gpu.integrate(depth: r.m, width: d.w, height: d.h, pose: pose, fx: r.k.x, fy: r.k.y, cx: r.k.z, cy: r.k.w, step: 2)
            if let low, !r.ml.isEmpty {
                low.integrate(depth: r.ml, width: d.w, height: d.h, pose: pose, fx: r.k.x, fy: r.k.y, cx: r.k.z, cy: r.k.w, step: 2)
            }
            if n % 32 == 31 {
                gpu.finish()
                if gpu.failed { return nil } // e.g. the app went to the background mid-way: the CPU starts over
                try Task.checkCancellation()
                progress(0.15 + 0.8 * Float(n) / Float(depthFrames.count))
                await ThermalGovernor.paceBurst(note: note)
            }
        }
        PerfLog.processing?.note(String(format: "frame alignment (%d aligned, %d left as ARKit, %d too little surface)",
                                         aligner.aligned, aligner.skipped, aligner.tooLittle), ms: alignTime * 1000)
        gpu.finish()
        if gpu.failed { return nil }
        if fine {
            let t0 = Date()
            let mesh = FineSurface.extract(gpu, cancelled: { Task.isCancelled }, progress: { progress(0.95 + 0.05 * $0) })
            try Task.checkCancellation()
            PerfLog.processing?.mark("finer surface", sizes: ["triangles (k)": Double(mesh.triangles.count / 3) / 1000,
                                                             "extract s": Date().timeIntervalSince(t0),
                                                             "GPU block MB": Double(gpu.voxelCount * 6) / 1_048_576])
            progress(1)
            return mesh
        }
        let tsdf = Tsdf(capacity: max(700_000, gpu.voxelCount / 4), voxel: 0.04, truncation: 0.08, carve: 1.5)
        gpu.copy(into: tsdf)
        if let low {
            low.finish()
            if !low.failed {
                let before = tsdf.count
                low.copy(into: tsdf, onlyUnseen: true, nearSeen: LowConfidenceFill.nearSeen, minWeight: LowConfidenceFill.minWeight)
                PerfLog.processing?.mark("hole fill from low-confidence depth", sizes: ["voxels (k)": Double(tsdf.count - before) / 1000])
            }
        }
        let scratch = Tsdf.MeshScratch()
        _ = tsdf.extractMesh(near: .zero, radius: 10_000, solidWeight: 20, into: scratch)
        progress(1)
        return ScanMesh(positions: scratch.positions, triangles: scratch.triangles)
    }

    /// Fuses every depth frame into the TSDF (4 cm, as live), skipping what a later tap cleared; points are the depth
    /// samples in 2 cm cells (averaged) seen at least twice, like the live scan.
    static func surface(frames: [FrameRecord], depth depthFrames: DepthFrames, clears: [Clear],
                        mesh wantMesh: Bool = true, points wantPoints: Bool = true,
                        progress: (Float) -> Void, note: @escaping @Sendable (String?) -> Void) async throws -> (ScanMesh, [SIMD3<Float>]) {
        let tsdf = Tsdf(capacity: wantMesh ? 700_000 : 16, voxel: 0.04, truncation: 0.08, carve: 1.5)
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
            tsdf.beginFrame()
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
                    if !clears.isEmpty, clears.contains(where: { $0.covers(pw, at: d.time) }) { continue }
                    if wantMesh { tsdf.integrate(origin: origin, point: pw) }
                    if wantPoints, cells.count < 1_200_000 {
                        let k = SIMD3(Int32((pw.x / 0.02).rounded(.down)), Int32((pw.y / 0.02).rounded(.down)), Int32((pw.z / 0.02).rounded(.down)))
                        let c = cells[k] ?? (.zero, 0)
                        cells[k] = (c.sum + pw, c.n + 1)
                    }
                }
            }
            if n % 10 == 0 {
                try Task.checkCancellation()
                progress(Float(n) / Float(depthFrames.count))
                await ThermalGovernor.pace(note: note)
            }
        }
        let scratch = Tsdf.MeshScratch()
        if wantMesh { _ = tsdf.extractMesh(near: .zero, radius: 10_000, solidWeight: 20, into: scratch) }
        let mesh = ScanMesh(positions: scratch.positions, triangles: scratch.triangles)
        // Cells seen at least twice (a single sighting is often a stray sample).
        let points = cells.values.filter { $0.n >= 2 }.map { $0.sum / Float($0.n) }
        return (mesh, points)
    }

    // MARK: Reading

    /// depth.bin's frames, decoded one at a time when used (rk 9 Nov: an 8.7-minute scan's depth decoded all at once
    /// was ~770 MB, and iOS stopped the rebuild). Only the compressed file and an index stay in memory.
    struct DepthFrames: RandomAccessCollection, @unchecked Sendable {
        let data: Data
        let times: [Double]
        let ranges: [Range<Int>]
        var startIndex: Int { 0 }
        var endIndex: Int { times.count }

        subscript(i: Int) -> DepthFrame {
            // In an autorelease pool: NSData's decompression result is autoreleased, and in a long loop with no pool
            // every frame's would stay until the loop ends (the Simulator run on rk's 8.7-minute scan reached 3.8 GB).
            autoreleasepool { decode(i) }
        }

        private func decode(_ i: Int) -> DepthFrame {
            let packed = data.subdata(in: ranges[i])
            let raw = (try? (packed as NSData).decompressed(using: .lzfse) as Data) ?? packed
            guard raw.count >= 4 else { return (times[i], 0, 0, [], []) }
            let w = Int(raw.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 0, as: UInt16.self) })
            let h = Int(raw.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: 2, as: UInt16.self) })
            guard raw.count >= 4 + w * h * 2 else { return (times[i], 0, 0, [], []) }
            let mm = raw.subdata(in: 4..<(4 + w * h * 2)).withUnsafeBytes { Array($0.bindMemory(to: UInt16.self)) }
            let conf = raw.count >= 4 + w * h * 3 ? [UInt8](raw.subdata(in: (4 + w * h * 2)..<(4 + w * h * 3))) : []
            return (times[i], w, h, mm, conf)
        }

        /// The frame nearest in time to `t` (index), if one is within `within` seconds.
        func nearest(_ t: Double, within: Double = 0.15) -> Int? {
            guard !times.isEmpty else { return nil }
            var lo = 0, hi = times.count - 1
            while lo < hi { let mid = (lo + hi) / 2; if times[mid] < t { lo = mid + 1 } else { hi = mid } }
            let best = lo > 0 && abs(times[lo - 1] - t) < abs(times[lo] - t) ? lo - 1 : lo
            return abs(times[best] - t) <= within ? best : nil
        }
    }

    /// depth.bin: per frame time f64, length u32, then LZFSE (width u16, height u16, mm u16[], confidence u8[]).
    static func readDepth(_ url: URL) throws -> DepthFrames {
        let depthData = try Data(contentsOf: url, options: .mappedIfSafe)
        var times: [Double] = [], ranges: [Range<Int>] = []
        var at = 0
        while at + 12 <= depthData.count {
            let time = depthData.subdata(in: at..<(at + 8)).withUnsafeBytes { $0.loadUnaligned(as: Double.self) }
            let len = Int(depthData.subdata(in: (at + 8)..<(at + 12)).withUnsafeBytes { $0.loadUnaligned(as: UInt32.self) })
            at += 12
            guard at + len <= depthData.count else { break }
            times.append(time); ranges.append(at..<(at + len))
            at += len
        }
        return DepthFrames(data: depthData, times: times, ranges: ranges)
    }

    /// The photos a stage wrote (keyframes.json + photos/), for colouring the points.
    static func loadPhotos(_ folder: URL) -> [Keyframe] {
        guard let data = try? Data(contentsOf: folder.appendingPathComponent("keyframes.json")),
              let records = try? JSONDecoder().decode([KeyframeRecord].self, from: data) else { return [] }
        return records.compactMap { r in
            guard r.cameraToWorld.count == 16, let jpeg = try? Data(contentsOf: folder.appendingPathComponent(r.file)) else { return nil }
            let c = r.cameraToWorld
            let m = simd_float4x4(SIMD4(c[0], c[1], c[2], c[3]), SIMD4(c[4], c[5], c[6], c[7]), SIMD4(c[8], c[9], c[10], c[11]), SIMD4(c[12], c[13], c[14], c[15]))
            return Keyframe(timestamp: r.timestamp, cameraToWorld: m, fx: r.fx, fy: r.fy, cx: r.cx, cy: r.cy,
                            width: r.width, height: r.height, jpeg: jpeg, exposure: nil)
        }
    }

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

    /// A tap to clear from the recording (clears.json), read once: checking the JSON dictionaries for every depth
    /// sample was slow and, with Foundation's bridged numbers piling up, took gigabytes on rk's 8.7-minute scan.
    struct Clear {
        var time: Double, m: simd_float4x4, center: SIMD2<Float>, size: SIMD2<Float>, radius: Float, limit: Float

        static func load(_ url: URL) -> [Clear] {
            guard let data = try? Data(contentsOf: url),
                  let list = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else { return [] }
            return list.compactMap { c in
                guard let t = c["time"] as? Double, let vp = c["viewProjection"] as? [NSNumber], vp.count == 16,
                      let center = c["center"] as? [NSNumber], center.count >= 2, let size = c["viewSize"] as? [NSNumber], size.count >= 2,
                      let r = (c["radius"] as? NSNumber)?.floatValue, let limit = (c["limit"] as? NSNumber)?.floatValue else { return nil }
                let v = vp.map(\.floatValue)
                let m = simd_float4x4(SIMD4(v[0], v[1], v[2], v[3]), SIMD4(v[4], v[5], v[6], v[7]), SIMD4(v[8], v[9], v[10], v[11]), SIMD4(v[12], v[13], v[14], v[15]))
                return Clear(time: t, m: m, center: SIMD2(center[0].floatValue, center[1].floatValue),
                             size: SIMD2(size[0].floatValue, size[1].floatValue), radius: r, limit: limit)
            }
        }

        /// Whether this tap cleared `p` from frames before it (the tap's cone, as it was on screen).
        func covers(_ p: SIMD3<Float>, at frameTime: Double) -> Bool {
            guard frameTime < time else { return false }
            let q = m * SIMD4(p, 1)
            guard q.w > 0.05, q.w <= limit else { return false }
            let s = SIMD2((q.x / q.w + 1) / 2 * size.x, (1 - q.y / q.w) / 2 * size.y)
            return simd_distance(s, center) < radius
        }
    }

    /// Photos from the video where the phone had moved 20 cm or turned 15° since the last one.
    static func keyframes(_ url: URL, frames: [FrameRecord], depth: DepthFrames,
                          progress: (Float) -> Void, note: @escaping @Sendable (String?) -> Void) async throws -> [Keyframe] {
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
            let jpegData: Data? = autoreleasepool {
                let image = CIImage(cvPixelBuffer: buffer).transformed(by: CGAffineTransform(scaleX: CGFloat(scale), y: CGFloat(scale)))
                return ctx.jpegRepresentation(of: image, colorSpace: CGColorSpace(name: CGColorSpace.sRGB)!,
                                              options: [kCGImageDestinationLossyCompressionQuality as CIImageRepresentationOption: 0.8])
            }
            guard let jpeg = jpegData else { continue }
            var kf = Keyframe(timestamp: t, cameraToWorld: f.pose, fx: f.k[0][0] * scale, fy: f.k[1][1] * scale,
                              cx: f.k[2][0] * scale, cy: f.k[2][1] * scale, width: 1024, height: Int(Float(f.height) * scale), jpeg: jpeg, exposure: nil)
            // Its depth at half size (128 × 96), as live photos keep it.
            if let i = depth.nearest(t) {
                let d = depth[i]
                let dw = d.w / 2, dh = d.h / 2
                var small = [UInt16](repeating: 0, count: dw * dh)
                for y in 0..<dh { for x in 0..<dw { small[y * dw + x] = d.mm[(y * 2) * d.w + x * 2] } }
                kf.depth = small; kf.depthWidth = dw; kf.depthHeight = dh
            }
            out.append(kf)
            if out.count % 5 == 0 {
                try Task.checkCancellation()
                progress(Float(fi) / Float(frames.count))
                await ThermalGovernor.pace(note: note)
            }
        }
        return out
    }

    // MARK: Writing

    static func writePhotos(_ photos: [Keyframe], into folder: URL) throws {
        let fm = FileManager.default
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
    }

    /// points.bin (coloured from the first photo that sees each point, grey where none does) and meta.json.
    static func writePoints(_ positions: [SIMD3<Float>], photos: [Keyframe], into folder: URL) throws {
        let colours = colour(positions, photos)
        let points = positions.enumerated().map { i, p in
            LivePoint(x: p.x, y: p.y, z: p.z, rgba: UInt32(colours[i].x) | (UInt32(colours[i].y) << 8) | (UInt32(colours[i].z) << 16) | (255 << 24))
        }
        try points.withUnsafeBytes { Data($0) }.write(to: folder.appendingPathComponent("points.bin"))
        let photoCount = (try? JSONDecoder().decode([KeyframeRecord].self, from: Data(contentsOf: folder.appendingPathComponent("keyframes.json"))).count) ?? photos.count
        try JSONEncoder().encode(CaptureMeta(pointCount: points.count, keyframeCount: photoCount, voxelSize: 0.02))
            .write(to: folder.appendingPathComponent("meta.json"))
    }

    /// Moves the new files from `work` into the scan; what they replace (and the `aside` files they make stale) moves
    /// into a backup folder first. The backup folder's name is kept in `work`, so a swap stopped part-way resumes
    /// into the same one; an earlier backup is never overwritten.
    static func swapIn(from work: URL, into folder: URL, backupBase: String, names: [String], aside: [String]) throws {
        let fm = FileManager.default
        let nameFile = work.appendingPathComponent("backup-name")
        var backupName = ((try? String(contentsOf: nameFile, encoding: .utf8)) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if backupName.isEmpty {
            backupName = backupBase
            var n = 2
            while fm.fileExists(atPath: folder.appendingPathComponent(backupName).path) { backupName = "\(backupBase)-\(n)"; n += 1 }
            try backupName.write(to: nameFile, atomically: true, encoding: .utf8)
        }
        let backup = folder.appendingPathComponent(backupName)
        try fm.createDirectory(at: backup, withIntermediateDirectories: true)
        func moveAside(_ name: String) throws {
            let current = folder.appendingPathComponent(name)
            guard fm.fileExists(atPath: current.path) else { return }
            try fm.moveItem(at: current, to: backup.appendingPathComponent(name))
        }
        for name in names {
            let fresh = work.appendingPathComponent(name)
            guard fm.fileExists(atPath: fresh.path) else { continue } // nothing new (or already moved in)
            try moveAside(name)
            try fm.moveItem(at: fresh, to: folder.appendingPathComponent(name))
        }
        for name in aside { try moveAside(name) }
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
