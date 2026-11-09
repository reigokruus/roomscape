import Foundation

/// A scan's identity for sharing and syncing (docs/rmsc-format.md, "Shared scans"): a permanent id, a revision that
/// goes up with every saved change, and whether (and with whom) it's shared. Kept in `<scan>.capture/sync.json`.
struct SyncInfo: Codable, Equatable {
    var id: String
    var revision: Int
    /// Device id of the phone that made this revision.
    var revisionBy: String
    var modified: Date
    var shared: Bool
    /// Phones it's shared with: device id -> name.
    var peers: [String: String]
    /// The revision both sides last agreed on: a change on both sides since then is a conflict.
    var syncedRevision: Int
}

enum SyncStore {
    static func url(_ scan: SavedScan) -> URL { scan.captureURL.appendingPathComponent("sync.json") }

    /// The scan's sync info, created (unshared, revision 1) the first time it's asked for.
    static func info(_ scan: SavedScan) -> SyncInfo {
        if let data = try? Data(contentsOf: url(scan)), let info = try? decoder.decode(SyncInfo.self, from: data) { return info }
        let info = SyncInfo(id: UUID().uuidString, revision: 1, revisionBy: deviceId, modified: scan.date, shared: false,
                            peers: [:], syncedRevision: 0)
        save(info, for: scan)
        return info
    }

    static func save(_ info: SyncInfo, for scan: SavedScan) {
        try? FileManager.default.createDirectory(at: scan.captureURL, withIntermediateDirectories: true)
        if let data = try? encoder.encode(info) { try? data.write(to: url(scan), options: .atomic) }
    }

    /// A saved change to the scan (Continue scan, rename, objects): a new revision, synced to the other phones.
    static func touch(_ scan: SavedScan) {
        var i = info(scan)
        i.revision += 1
        i.revisionBy = deviceId
        i.modified = Date()
        save(i, for: scan)
        if i.shared { Task { @MainActor in Nearby.shared.syncSoon() } }
    }

    /// Marks the scan shared with a phone (after sending it there, or receiving it from there).
    static func markShared(_ scan: SavedScan, with peerId: String, name: String, synced revision: Int) {
        var i = info(scan)
        i.shared = true
        i.peers[peerId] = name
        i.syncedRevision = max(i.syncedRevision, revision)
        save(i, for: scan)
    }

    /// The local scan with this sync id, if any.
    static func scan(withId id: String) -> SavedScan? {
        ScanStore.list().first { scan in
            guard let data = try? Data(contentsOf: url(scan)), let i = try? decoder.decode(SyncInfo.self, from: data) else { return false }
            return i.id == id
        }
    }

    /// Shared scans, for GET /scans.
    static func sharedScans() -> [(scan: SavedScan, info: SyncInfo)] {
        ScanStore.list().compactMap { scan in
            guard let data = try? Data(contentsOf: url(scan)), let i = try? decoder.decode(SyncInfo.self, from: data), i.shared else { return nil }
            return (scan, i)
        }
    }

    static var deviceId: String {
        if let id = UserDefaults.standard.string(forKey: "nearbyDeviceId") { return id }
        let id = UUID().uuidString
        UserDefaults.standard.set(id, forKey: "nearbyDeviceId")
        return id
    }

    static let encoder: JSONEncoder = { let e = JSONEncoder(); e.dateEncodingStrategy = .iso8601; e.outputFormatting = [.prettyPrinted, .sortedKeys]; return e }()
    static let decoder: JSONDecoder = { let d = JSONDecoder(); d.dateDecodingStrategy = .iso8601; return d }()
}
