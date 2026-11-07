import Foundation
import RoomPlan

/// A saved scan: a 3D model (.usdz) plus the RoomPlan data (.json) describing walls, windows, doors and furniture.
struct SavedScan: Identifiable, Hashable {
    let name: String
    let modelURL: URL
    let dataURL: URL
    let date: Date
    var id: String { name }
    /// LiDAR points and photos from the scan (see `CaptureMeta`). Missing for scans made before they were saved.
    var captureURL: URL { modelURL.deletingLastPathComponent().appendingPathComponent(name + ".capture") }
    var thumbnailURL: URL { modelURL.deletingLastPathComponent().appendingPathComponent(name + ".thumb.jpg") }
}

enum ScanStore {
    static var directory: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    private static let nameFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd_HH-mm-ss"
        return f
    }()

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

    /// The scan's thumbnail, rendered and saved the first time it's needed. Slow: call off the main thread.
    static func ensureThumbnail(_ scan: SavedScan) -> URL? {
        let url = scan.thumbnailURL
        if FileManager.default.fileExists(atPath: url.path) { return url }
        guard let jpeg = ScanThumbnail.render(modelURL: scan.modelURL),
              (try? jpeg.write(to: url, options: .atomic)) != nil else { return nil }
        return url
    }

    static func list() -> [SavedScan] {
        let fm = FileManager.default
        guard let files = try? fm.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.creationDateKey]) else { return [] }
        return files
            .filter { $0.pathExtension == "usdz" }
            .map { url in
                let name = url.deletingPathExtension().lastPathComponent
                let date = (try? url.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? Date.distantPast
                return SavedScan(name: name, modelURL: url,
                                 dataURL: directory.appendingPathComponent(name + ".json"), date: date)
            }
            .sorted { $0.date > $1.date }
    }

    static func loadRoom(_ scan: SavedScan) -> CapturedRoom? {
        guard let data = try? Data(contentsOf: scan.dataURL) else { return nil }
        return try? JSONDecoder().decode(CapturedRoom.self, from: data)
    }

    static func delete(_ scan: SavedScan) {
        try? FileManager.default.removeItem(at: scan.modelURL)
        try? FileManager.default.removeItem(at: scan.dataURL)
        try? FileManager.default.removeItem(at: scan.captureURL)
        try? FileManager.default.removeItem(at: scan.thumbnailURL)
    }
}
