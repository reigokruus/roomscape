import Foundation
import RoomPlan

/// A saved scan: a 3D model (.usdz) plus the RoomPlan data (.json) describing walls, windows, doors and furniture.
struct SavedScan: Identifiable, Hashable {
    let name: String
    let modelURL: URL
    let dataURL: URL
    let date: Date
    /// The name the user gave the room ("Living room"), or nil: then the scan goes by its date.
    var title: String? = nil
    var id: String { name }
    /// What the app calls the scan: its title, else when it was made.
    var displayName: String { title ?? defaultDisplayName }
    var defaultDisplayName: String { date.formatted(date: .abbreviated, time: .shortened) }
    /// LiDAR points and photos from the scan (see `CaptureMeta`). Missing for scans made before they were saved.
    var captureURL: URL { modelURL.deletingLastPathComponent().appendingPathComponent(name + ".capture") }
    var thumbnailURL: URL { modelURL.deletingLastPathComponent().appendingPathComponent(name + ".thumb.jpg") }
    var titleURL: URL { modelURL.deletingLastPathComponent().appendingPathComponent(name + ".title") }
}

enum ScanStore {
    static var directory: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    private static let nameFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX") // a fixed format, whatever the phone's 12/24-hour setting
        f.dateFormat = "yyyy-MM-dd_HH-mm-ss"
        return f
    }()

    /// A free scan name for a scan made (or imported) now: "Scan_<date>".
    static func newScanName(_ date: Date = Date()) -> String {
        var date = date
        while FileManager.default.fileExists(atPath: directory.appendingPathComponent("Scan_" + nameFormatter.string(from: date) + ".usdz").path) {
            date += 1
        }
        return "Scan_" + nameFormatter.string(from: date)
    }

    static func save(_ room: CapturedRoom, capture: CaptureSnapshot?) throws -> SavedScan {
        let date = Date()
        let name = "Scan_" + nameFormatter.string(from: date)
        let model = directory.appendingPathComponent(name + ".usdz")
        let data = directory.appendingPathComponent(name + ".json")
        try room.export(to: model)
        try JSONEncoder().encode(room).write(to: data)
        let scan = SavedScan(name: name, modelURL: model, dataURL: data, date: date)
        if let capture, capture.pointCount > 0 || !capture.keyframes.isEmpty {
            try CaptureStore.write(capture, to: scan.captureURL)
        }
        _ = ensureThumbnail(scan)
        return scan
    }

    /// Thumbnails are rendered one at a time: each photo room holds its textures while it renders.
    private static let thumbnailLock = NSLock()

    /// The scan's thumbnail: its photo room from the 3/4 view, else RoomPlan's model. Made the first time it's needed
    /// (and once more for scans whose old thumbnail shows the model, see `thumbnailKind`). Slow: call off the main thread.
    static func ensureThumbnail(_ scan: SavedScan) -> URL? {
        let url = scan.thumbnailURL, fm = FileManager.default
        let kindURL = scan.captureURL.appendingPathComponent("thumbnail-kind")
        if fm.fileExists(atPath: url.path), fm.fileExists(atPath: kindURL.path) { return url }
        thumbnailLock.lock()
        defer { thumbnailLock.unlock() }
        if fm.fileExists(atPath: url.path), fm.fileExists(atPath: kindURL.path) { return url }
        if let jpeg = ScanThumbnail.renderPhotoRoom(scan), (try? jpeg.write(to: url, options: .atomic)) != nil {
            try? "photo".write(to: kindURL, atomically: true, encoding: .utf8)
            return url
        }
        // No photo room: keep an existing thumbnail, else render the model; either way don't try again.
        if fm.fileExists(atPath: scan.captureURL.path) { try? "model".write(to: kindURL, atomically: true, encoding: .utf8) }
        if fm.fileExists(atPath: url.path) { return url }
        guard let jpeg = ScanThumbnail.render(modelURL: scan.modelURL),
              (try? jpeg.write(to: url, options: .atomic)) != nil else { return nil }
        return url
    }

    /// Makes the scan's thumbnail again next time (its photo room changed, e.g. after Continue scan).
    static func invalidateThumbnail(_ scan: SavedScan) {
        try? FileManager.default.removeItem(at: scan.captureURL.appendingPathComponent("thumbnail-kind"))
        try? FileManager.default.removeItem(at: scan.thumbnailURL)
    }

    static func list() -> [SavedScan] {
        let fm = FileManager.default
        guard let files = try? fm.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.creationDateKey]) else { return [] }
        return files
            .filter { $0.pathExtension == "usdz" }
            .map { url in
                let name = url.deletingPathExtension().lastPathComponent
                // The date is in the name; the file's own date changes when scans are copied between devices.
                let date = name.hasPrefix("Scan_") ? nameFormatter.date(from: String(name.dropFirst(5))) : nil
                var scan = SavedScan(name: name, modelURL: url, dataURL: directory.appendingPathComponent(name + ".json"),
                                     date: date ?? (try? url.resourceValues(forKeys: [.creationDateKey]).creationDate)
                                         ?? Date.distantPast)
                scan.title = (try? String(contentsOf: scan.titleURL, encoding: .utf8)).flatMap(cleanTitle)
                return scan
            }
            .sorted { $0.date > $1.date }
    }

    /// Names the scan (an empty name goes back to the date) and returns it with the new title. A rename is a change
    /// that syncs to the phones the scan is shared with (`touch`).
    @discardableResult
    static func rename(_ scan: SavedScan, to title: String, touch: Bool = true) -> SavedScan {
        defer { if touch { SyncStore.touch(scan) } }
        var renamed = scan
        renamed.title = cleanTitle(title)
        if let t = renamed.title {
            try? t.write(to: scan.titleURL, atomically: true, encoding: .utf8)
        } else {
            try? FileManager.default.removeItem(at: scan.titleURL)
        }
        return renamed
    }

    /// The scan's model as "<room name>.usdz" in the temporary folder (a clone of the file, instant on APFS), so a
    /// shared model carries the room's name. Nil for unnamed scans or if the copy fails.
    static func namedModelCopy(_ scan: SavedScan) -> URL? {
        guard let title = scan.title else { return nil }
        let safe = title.components(separatedBy: CharacterSet(charactersIn: "/\\:?%*|\"<>")).joined(separator: "-")
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("share-\(scan.name)", isDirectory: true)
        let url = folder.appendingPathComponent(safe + ".usdz")
        try? FileManager.default.removeItem(at: folder)
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try FileManager.default.copyItem(at: scan.modelURL, to: url)
            return url
        } catch {
            return nil
        }
    }

    private static func cleanTitle(_ raw: String) -> String? {
        let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : String(t.prefix(60))
    }

    static func loadRoom(_ scan: SavedScan) -> CapturedRoom? {
        guard let data = try? Data(contentsOf: scan.dataURL) else { return nil }
        return try? JSONDecoder().decode(CapturedRoom.self, from: data)
    }

    // MARK: Splats

    /// The capture's points (`points.bin`, see `CaptureMeta`), or empty for scans saved without them.
    static func loadPoints(_ scan: SavedScan) -> [LivePoint] {
        let url = scan.captureURL.appendingPathComponent("points.bin")
        guard let data = try? Data(contentsOf: url, options: .mappedIfSafe) else { return [] }
        let stride = MemoryLayout<LivePoint>.stride
        return data.withUnsafeBytes { raw in
            (0..<(raw.count / stride)).map { raw.loadUnaligned(fromByteOffset: $0 * stride, as: LivePoint.self) }
        }
    }

    /// The scan's splats: from `surfels.bin` in the capture folder, or built from the points, snapped to RoomPlan's
    /// walls and floor, and cached there. Slow the first time (about a second): call off the main thread.
    static func loadSurfels(_ scan: SavedScan, room: CapturedRoom) -> [SurfelRecord]? {
        let cache = scan.captureURL.appendingPathComponent("surfels.bin")
        if let cached = Surfels.read(from: cache) { return cached }
        let points = loadPoints(scan)
        guard !points.isEmpty else { return nil }
        let start = Date()
        let voxel = CaptureStore.loadMeta(scan.captureURL)?.voxelSize ?? LiveCloud.voxel
        var (surfels, stats) = Surfels.build(points, voxel: voxel)
        let snapped = Surfels.snap(&surfels, to: RoomShell(room: room))
        print("splats: \(surfels.count) from \(points.count) points (\(stats.flat) flat, \(stats.curved) curved, "
              + "\(stats.lonely) lonely, \(snapped) on walls/floor) in \(Int(Date().timeIntervalSince(start) * 1000)) ms")
        Surfels.write(surfels, to: cache)
        return surfels
    }

    /// Where splat training saves its result (see `SplatTrainingRun`).
    static func trainedSurfelsURL(_ scan: SavedScan) -> URL {
        scan.captureURL.appendingPathComponent("trained.bin")
    }

    /// The trained splats, when the scan was trained before.
    static func loadTrainedSurfels(_ scan: SavedScan) -> [SurfelRecord]? {
        guard let splats = Surfels.read(from: trainedSurfelsURL(scan)), !splats.isEmpty else { return nil }
        return splats
    }

    /// Puts `new`'s files in place of `old`'s (same name, date and place in the list) and deletes `new`'s name.
    static func replace(_ old: SavedScan, with new: SavedScan) throws -> SavedScan {
        let fm = FileManager.default
        for (a, b) in [(old.modelURL, new.modelURL), (old.dataURL, new.dataURL), (old.captureURL, new.captureURL),
                       (old.thumbnailURL, new.thumbnailURL), (old.titleURL, new.titleURL)] {
            try? fm.removeItem(at: a)
            if fm.fileExists(atPath: b.path) { try fm.moveItem(at: b, to: a) }
        }
        var scan = old
        scan.title = (try? String(contentsOf: old.titleURL, encoding: .utf8)).flatMap(cleanTitle)
        return scan
    }

    /// A copy of the scan under a new name and a new sync id (kept when a synced update would replace it).
    static func duplicate(_ scan: SavedScan, title: String) throws {
        let fm = FileManager.default
        let name = newScanName(Date())
        let copy = SavedScan(name: name, modelURL: directory.appendingPathComponent(name + ".usdz"),
                             dataURL: directory.appendingPathComponent(name + ".json"), date: Date())
        for (a, b) in [(scan.modelURL, copy.modelURL), (scan.dataURL, copy.dataURL), (scan.captureURL, copy.captureURL),
                       (scan.thumbnailURL, copy.thumbnailURL)] where fm.fileExists(atPath: a.path) {
            try fm.copyItem(at: a, to: b)
        }
        try? fm.removeItem(at: SyncStore.url(copy)) // a new identity
        _ = rename(copy, to: title, touch: false)
    }

    static func delete(_ scan: SavedScan) {
        try? FileManager.default.removeItem(at: scan.modelURL)
        try? FileManager.default.removeItem(at: scan.dataURL)
        try? FileManager.default.removeItem(at: scan.captureURL)
        try? FileManager.default.removeItem(at: scan.thumbnailURL)
        try? FileManager.default.removeItem(at: scan.titleURL)
    }
}
