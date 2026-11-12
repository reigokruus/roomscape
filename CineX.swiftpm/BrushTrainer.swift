#if canImport(BrushC)
import BrushC
import Foundation

/// The real trainer for the photo-real preview: Brush (Apache-2.0, https://github.com/ArthurBrussee/brush), linked in
/// as a static library when CineX.swiftpm/Vendor/BrushC.xcframework exists on the Mac that builds the app (see
/// tools/brush-ios). Without it the app builds as before and uses the stand-in `DotsTrainer`.
///
/// Phone settings (rk 12 Nov): pictures at most 960 px, at most 400k splats, spherical harmonics degree 1 (less memory
/// than Brush's 3), one export at the end. Brush blocks while it trains, so it runs on its own thread; cancelling the
/// job sets the cancel byte Brush checks after every step.
struct BrushTrainer: SplatPreview.Trainer {
    var name: String { "Brush" }
    static let maxResolution: UInt32 = 960, maxSplats: UInt32 = 400_000, shDegree: UInt32 = 1

    private final class Box: @unchecked Sendable {
        let progress: @Sendable (Int) -> Void
        let cancel = UnsafeMutablePointer<UInt8>.allocate(capacity: 1)
        init(_ progress: @escaping @Sendable (Int) -> Void) { self.progress = progress; cancel.pointee = 0 }
        deinit { cancel.deallocate() }
    }

    func train(dataset: URL, output: URL, steps: Int, progress: @escaping @Sendable (Int) -> Void) async throws {
        let fm = FileManager.default
        let outDir = output.deletingLastPathComponent().appendingPathComponent("brush-out")
        try? fm.removeItem(at: outDir)
        try fm.createDirectory(at: outDir, withIntermediateDirectories: true)
        let box = Box(progress)
        let code: BrushTrainExitCode = await withTaskCancellationHandler {
            await withCheckedContinuation { (done: CheckedContinuation<BrushTrainExitCode, Never>) in
                let thread = Thread {
                    let user = Unmanaged.passRetained(box)
                    let r = dataset.path.withCString { ds in
                        outDir.path.withCString { op in
                            var options = BrushTrainOptions(total_train_steps: UInt32(steps), refine_every: 200,
                                                            max_resolution: Self.maxResolution, export_every: UInt32(steps),
                                                            output_path: op, max_splats: Self.maxSplats,
                                                            sh_degree: Self.shDegree, cancel: UnsafePointer(box.cancel))
                            return train_and_save(ds, &options, { message, userData in
                                guard let userData, message.tag == BrushTraining.rawValue else { return }
                                Unmanaged<Box>.fromOpaque(userData).takeUnretainedValue().progress(Int(message.iter))
                            }, user.toOpaque())
                        }
                    }
                    user.release()
                    done.resume(returning: r)
                }
                thread.stackSize = 8 << 20   // Brush's async runtime wants more than a background thread's 512 KB
                thread.qualityOfService = .userInitiated
                thread.start()
            }
        } onCancel: {
            box.cancel.pointee = 1
        }
        try Task.checkCancellation()
        guard code == BrushTrainSuccess else {
            // brush-c writes why into brush-error.txt (RoomScape's patch): into the processing log with it
            let why = (try? String(contentsOf: outDir.appendingPathComponent("brush-error.txt"), encoding: .utf8)) ?? "no reason given"
            SplatPreview.log.error("Brush failed: \(why, privacy: .public)")
            throw NSError(domain: "SplatPreview", code: 10, userInfo: [NSLocalizedDescriptionKey: "Brush couldn't train the preview: " + why])
        }
        // Brush names its export export_<step>.ply: take the newest
        let plys = (try fm.contentsOfDirectory(at: outDir, includingPropertiesForKeys: [.contentModificationDateKey]))
            .filter { $0.pathExtension == "ply" }
        guard let last = plys.max(by: {
            ((try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast)
                < ((try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast)
        }) else { throw NSError(domain: "SplatPreview", code: 11, userInfo: [NSLocalizedDescriptionKey: "Brush wrote no splat"]) }
        if fm.fileExists(atPath: output.path) { try fm.removeItem(at: output) }
        try fm.moveItem(at: last, to: output)
        try? fm.removeItem(at: outDir)
    }
}
#endif
