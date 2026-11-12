import Foundation
import Security
import SwiftUI
import os

/// Share links for rooms (rk 12 Nov: "when i scan a room and it finishes on the phone i can already share it, then when
/// the hardware rendering is done on legion it updates that link"). Server: roomscape-web (docs/share-links.md there).
///
/// Each scan gets a secret link https://roomscape.eu/r/<id> (16 random bytes), kept in `<capture>/splat/share.json`
/// and reused: the phone uploads its preview there when the preview is done, the PC (upload_room.py) later the full
/// version to the same id (the id travels in the "For a PC rebuild" zip as share.json). Uploading needs the upload
/// key (a Worker secret), entered once on the home screen and kept in the Keychain. Off by default (switch
/// "Share links (test)"); nothing is uploaded until it's on and a key is set.
enum RoomLink {
    static let log = Logger(subsystem: "RoomScape", category: "RoomLink")
    static let base = URL(string: "https://roomscape.eu")!

    static var enabled: Bool {
        get { UserDefaults.standard.bool(forKey: "roomLinks") }
        set { UserDefaults.standard.set(newValue, forKey: "roomLinks") }
    }

    struct Info: Codable {
        var id: String
        var uploaded: [String: Date] = [:]   // version → when
        var link: URL { RoomLink.base.appendingPathComponent("r").appendingPathComponent(id) }
    }

    static func file(_ scan: SavedScan) -> URL { SplatPreview.folder(scan).appendingPathComponent("share.json") }
    static func info(_ scan: SavedScan) -> Info? {
        let d = JSONDecoder(); d.dateDecodingStrategy = .iso8601
        return (try? Data(contentsOf: file(scan))).flatMap { try? d.decode(Info.self, from: $0) }
    }
    /// The scan's link if its preview is online.
    static func link(_ scan: SavedScan) -> URL? { info(scan).flatMap { $0.uploaded.isEmpty ? nil : $0.link } }

    /// The scan's room id, made the first time (128 bits, base64url).
    static func ensureInfo(_ scan: SavedScan) throws -> Info {
        if let i = info(scan) { return i }
        var bytes = [UInt8](repeating: 0, count: 16)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw err("no random bytes") }
        let id = Data(bytes).base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        let i = Info(id: id)
        try FileManager.default.createDirectory(at: SplatPreview.folder(scan), withIntermediateDirectories: true)
        try save(i, scan)
        return i
    }

    private static func save(_ i: Info, _ scan: SavedScan) throws {
        let e = JSONEncoder(); e.outputFormatting = .prettyPrinted; e.dateEncodingStrategy = .iso8601
        try e.encode(i).write(to: file(scan), options: .atomic)
    }
    private static func err(_ s: String) -> NSError { NSError(domain: "RoomLink", code: 1, userInfo: [NSLocalizedDescriptionKey: s]) }

    /// Uploads the scan's preview to its link (one request: the phone's preview is well under the 95 MB limit), with
    /// up to 4 retries. Returns the link.
    @discardableResult
    static func uploadPreview(_ scan: SavedScan, progress: @escaping @Sendable (Double) -> Void = { _ in }) async throws -> URL {
        guard let key = Keychain.uploadKey, !key.isEmpty else { throw err("No upload key: set it on the home screen (Share links).") }
        let ply = SplatPreview.result(scan)
        guard FileManager.default.fileExists(atPath: ply.path) else { throw err("No photo-real preview yet.") }
        var info = try ensureInfo(scan)
        var req = URLRequest(url: base.appendingPathComponent("api/rooms/\(info.id)/preview").appending(queryItems: [.init(name: "format", value: "ply")]))
        req.httpMethod = "PUT"
        req.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        req.setValue(scan.displayName, forHTTPHeaderField: "X-Room-Title")
        if let view = try? Data(contentsOf: SplatPreview.folder(scan).appendingPathComponent("view.json")),
           let s = String(data: view, encoding: .utf8) { req.setValue(s.replacingOccurrences(of: "\n", with: ""), forHTTPHeaderField: "X-Room-View") }
        let size = (try? FileManager.default.attributesOfItem(atPath: ply.path)[.size] as? Int) ?? 0
        req.setValue(String(size), forHTTPHeaderField: "Content-Length")
        var lastError: Error = err("upload failed")
        for attempt in 0..<5 {
            try Task.checkCancellation()
            do {
                let (data, response) = try await URLSession.shared.upload(for: req, fromFile: ply, delegate: Progress(progress))
                let code = (response as? HTTPURLResponse)?.statusCode ?? 0
                if code == 201 || code == 200 {
                    info.uploaded["preview"] = Date()
                    try save(info, scan)
                    log.info("preview uploaded for \(scan.name, privacy: .public) (\(size) bytes)")
                    return info.link
                }
                let why = String(data: data, encoding: .utf8) ?? ""
                lastError = err("The server said \(code): \(why)")
                if code < 500 { break }   // a wrong key or a bad request doesn't get better by trying again
            } catch is CancellationError { throw CancellationError() } catch { lastError = error }
            try await Task.sleep(nanoseconds: UInt64(2 << attempt) * 1_000_000_000)   // 2, 4, 8, 16 s
        }
        log.error("preview upload failed: \(lastError.localizedDescription, privacy: .public)")
        throw lastError
    }

    private final class Progress: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
        let report: @Sendable (Double) -> Void
        init(_ report: @escaping @Sendable (Double) -> Void) { self.report = report }
        func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64, totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
            if totalBytesExpectedToSend > 0 { report(Double(totalBytesSent) / Double(totalBytesExpectedToSend)) }
        }
    }

    /// The upload key, in the Keychain (this device only).
    enum Keychain {
        private static let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                                   kSecAttrService as String: "RoomScape share links", kSecAttrAccount as String: "upload"]
        static var uploadKey: String? {
            get {
                var q = query; q[kSecReturnData as String] = true
                var out: CFTypeRef?
                guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
                return String(data: d, encoding: .utf8)
            }
            set {
                SecItemDelete(query as CFDictionary)
                guard let v = newValue?.trimmingCharacters(in: .whitespacesAndNewlines), !v.isEmpty else { return }
                var q = query; q[kSecValueData as String] = Data(v.utf8)
                q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
                SecItemAdd(q as CFDictionary, nil)
            }
        }
    }
}

/// Home screen: the switch and the upload key (a secure field; the key isn't shown again once saved).
struct RoomLinkSettings: View {
    @State private var on = RoomLink.enabled
    @State private var key = ""
    @State private var hasKey = RoomLink.Keychain.uploadKey != nil

    var body: some View {
        Toggle("Share links (test)", isOn: $on).onChange(of: on) { _, v in RoomLink.enabled = v }
        if on {
            HStack {
                SecureField(hasKey ? "Upload key saved (paste to replace)" : "Upload key", text: $key)
                    .textContentType(.password).autocorrectionDisabled().textInputAutocapitalization(.never)
                Button("Save") { RoomLink.Keychain.uploadKey = key; key = ""; hasKey = RoomLink.Keychain.uploadKey != nil }
                    .disabled(key.isEmpty)
            }
        }
    }
}
