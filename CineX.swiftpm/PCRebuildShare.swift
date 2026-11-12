import Foundation

/// "For a PC rebuild (.zip)" in a room's Share menu (rk 12 Nov: send a scan from a shooting site through Google Drive to
/// the PC that makes the photo-real room). Only reads the scan; the zip goes to the temporary folder and is shared with
/// the standard share sheet (Google Drive, Files, AirDrop).
///
/// - Quick: the preview dataset (SplatPreview: up to 300 sharpest half-size frames, transforms.json, points.ply), about
///   30-40 MB, exactly what Brush reads: `brush <unzipped folder> --total-steps 7000 …` (docs/pc-splat-rebuild.md).
/// - Full quality: the raw recording (video.mov, frames.bin, depth.bin) and the dots, as `<Scan>.capture/…`, 100-500 MB,
///   for tools/room-rebuild/ios_to_nerfstudio.py (up to 1000 frames at full size).
/// Files are stored, not compressed (video and JPEGs are compressed already), and streamed from disk one at a time.
enum PCRebuildShare {
    enum Kind { case quick, full }

    static func zip(_ scan: SavedScan, _ kind: Kind, progress: @escaping @Sendable (String) -> Void = { _ in }) async throws -> URL {
        let fm = FileManager.default
        let stamp = scan.name.replacingOccurrences(of: "Scan_", with: "")
        let out = fm.temporaryDirectory.appendingPathComponent("RoomScape-\(stamp)-for-PC-\(kind == .quick ? "quick" : "full").zip")
        try? fm.removeItem(at: out)
        var files: [(path: String, url: URL)] = []
        switch kind {
        case .quick:
            let dataset = SplatPreview.folder(scan).appendingPathComponent("dataset")
            let images = dataset.appendingPathComponent("images")
            // The pictures are deleted after a preview is made: pick them again from the recording (takes seconds).
            let haveImages = ((try? fm.contentsOfDirectory(atPath: images.path))?.isEmpty == false)
            if !haveImages || !fm.fileExists(atPath: dataset.appendingPathComponent("transforms.json").path) {
                progress("Picking sharp pictures…")
                try fm.createDirectory(at: dataset, withIntermediateDirectories: true)
                try await SplatPreview.exportDataset(scan: scan, to: dataset, pace: {}, progress: { _ in })
            }
            for name in ["transforms.json", "points.ply"] { files.append((name, dataset.appendingPathComponent(name))) }
            for name in (try fm.contentsOfDirectory(atPath: images.path)).sorted() where name.hasSuffix(".jpg") {
                files.append(("images/" + name, images.appendingPathComponent(name)))
            }
        case .full:
            let cap = scan.captureURL, folder = scan.name + ".capture/"
            for name in ["video.mov", "frames.bin", "depth.bin"] {
                let url = cap.appendingPathComponent("recording/" + name)
                if fm.fileExists(atPath: url.path) { files.append((folder + "recording/" + name, url)) }
            }
            guard files.contains(where: { $0.path.hasSuffix("frames.bin") }) else {
                throw NSError(domain: "PCRebuildShare", code: 1, userInfo: [NSLocalizedDescriptionKey: "This scan has no recording to send."])
            }
            for name in ["points.bin", "meta.json"] where fm.fileExists(atPath: cap.appendingPathComponent(name).path) {
                files.append((folder + name, cap.appendingPathComponent(name)))
            }
        }
        progress("Packing \(files.count) files…")
        let writer = try Zip.Writer(url: out)
        try writer.add("README.txt", data: Data(readme(scan, kind).utf8))
        for f in files {
            try Task.checkCancellation()
            try autoreleasepool { try writer.add(f.path, file: f.url) }   // mapped from disk, not read into memory
        }
        try writer.finish()
        return out
    }

    static func readme(_ scan: SavedScan, _ kind: Kind) -> String {
        "RoomScape scan \(scan.name) (\(scan.displayName)) for a photo-real rebuild on a PC.\n"
        + "Private: it shows a real place. Delete it from shared storage once the rebuild is back.\n\n"
        + (kind == .quick
           ? "Quick: a Nerfstudio dataset (transforms.json, images/, points.ply). Train it with Brush, e.g.\n"
             + "  brush <this folder> --total-steps 7000 --max-splats 400000 --max-resolution 960 --export-path out\n"
           : "Full quality: the raw recording. Make the dataset with tools/room-rebuild/ios_to_nerfstudio.py, e.g.\n"
             + "  python ios_to_nerfstudio.py \(scan.name).capture ds-full --max-frames 1000 --scale 1.0\n"
             + "then train with Brush (docs/pc-splat-rebuild.md).\n")
    }
}
