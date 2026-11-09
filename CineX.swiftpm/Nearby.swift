import Foundation
import Network
import SwiftUI
import UIKit

/// "Send to nearby phone": RoomScape phones on the same Wi-Fi find each other and send .rmsc scans directly
/// (protocol: docs/rmsc-format.md, "Sending to a nearby phone"). While the app is open it listens and advertises
/// itself as `_roomscape._tcp` over Bonjour. A scan arrives as HTTP `POST /rmsc` with the raw .rmsc as the body. The
/// first scan from a phone asks "Accept scans from …?"; with Always allow, later ones import without asking.
@MainActor
final class Nearby: ObservableObject {
    static let shared = Nearby()
    static let serviceType = "_roomscape._tcp"

    struct Peer: Identifiable, Hashable {
        let id: String
        let name: String
        let endpoint: NWEndpoint
    }

    /// A scan waiting for the user to accept it.
    struct Request: Identifiable {
        let id = UUID()
        let deviceId: String
        let deviceName: String
        let fileName: String
        fileprivate let answer: (Answer) -> Void
    }
    enum Answer { case once, always, decline }

    @Published var peers: [Peer] = []
    @Published var request: Request?
    /// Set when a scan was imported (shown briefly on Home).
    @Published var received: (scan: SavedScan, from: String)?

    let deviceId: String
    /// What other phones see: the name the user gave this phone ("RK's iPhone"). iOS only tells apps "iPhone".
    @Published var deviceName: String
    /// True until the user has named this phone.
    var needsName: Bool { UserDefaults.standard.string(forKey: "nearbyName") == nil }
    private var listener: NWListener?
    private var browser: NWBrowser?
    private let queue = DispatchQueue(label: "RoomScape.nearby")
    private var trusted: Set<String> {
        get { Set(UserDefaults.standard.stringArray(forKey: "nearbyTrusted") ?? []) }
        set { UserDefaults.standard.set(Array(newValue), forKey: "nearbyTrusted") }
    }

    private init() {
        if let id = UserDefaults.standard.string(forKey: "nearbyDeviceId") {
            deviceId = id
        } else {
            deviceId = UUID().uuidString
            UserDefaults.standard.set(deviceId, forKey: "nearbyDeviceId")
        }
        deviceName = UserDefaults.standard.string(forKey: "nearbyName") ?? UIDevice.current.model
    }

    /// Renames this phone for other phones (advertised again right away). The id stays, so trust is kept.
    func rename(_ name: String) {
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        UserDefaults.standard.set(String(clean.prefix(40)), forKey: "nearbyName")
        deviceName = String(clean.prefix(40))
        stop()
        start()
    }

    // MARK: Receiving

    /// Listens and advertises while the app is open. Safe to call again.
    func start() {
        guard listener == nil else { return }
        do {
            let params = NWParameters.tcp
            params.includePeerToPeer = false
            let listener = try NWListener(using: params)
            var txt = NWTXTRecord()
            txt["id"] = deviceId
            txt["name"] = deviceName
            txt["platform"] = "ios"
            listener.service = NWListener.Service(name: deviceName, type: Self.serviceType, txtRecord: txt)
            listener.newConnectionHandler = { [weak self] connection in
                Task { @MainActor in self?.serve(connection) }
            }
            listener.stateUpdateHandler = { [weak self] state in
                if case .failed = state { Task { @MainActor in self?.listener = nil } }
            }
            listener.start(queue: queue)
            self.listener = listener
        } catch {
            print("nearby: listener failed: \(error)")
        }
    }

    func stop() {
        listener?.cancel()
        listener = nil
    }

    private func serve(_ connection: NWConnection) {
        connection.start(queue: queue)
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("nearby-\(UUID().uuidString).rmsc")
        HTTP.readRequest(connection, bodyTo: file) { [weak self] head in
            Task { @MainActor in
                guard let self else { return }
                guard let head, head.method == "POST", head.path == "/rmsc" else {
                    HTTP.respond(connection, 400, "Bad request"); try? FileManager.default.removeItem(at: file); return
                }
                let id = head.headers["x-device-id"] ?? "unknown"
                let name = head.headers["x-device-name"] ?? "A phone"
                let fileName = head.headers["x-file-name"] ?? "scan.rmsc"
                let finish: (Bool) -> Void = { accept in
                    guard accept else {
                        HTTP.respond(connection, 403, "Declined"); try? FileManager.default.removeItem(at: file); return
                    }
                    Task.detached(priority: .userInitiated) {
                        let result = Result { try Rmsc.importScan(from: file) }
                        try? FileManager.default.removeItem(at: file)
                        await MainActor.run {
                            switch result {
                            case .success(let scan):
                                HTTP.respond(connection, 200, "OK")
                                self.received = (scan, name)
                            case .failure(let error):
                                HTTP.respond(connection, 422, error.localizedDescription)
                            }
                        }
                    }
                }
                if self.trusted.contains(id) {
                    finish(true)
                } else {
                    self.request = Request(deviceId: id, deviceName: name, fileName: fileName) { [weak self] answer in
                        if answer == .always { self?.trusted.insert(id) }
                        finish(answer != .decline)
                    }
                }
            }
        }
    }

    func answer(_ request: Request, _ answer: Answer) {
        self.request = nil
        request.answer(answer)
    }

    // MARK: Sending

    /// Looks for other RoomScape phones while the send sheet is open.
    func browse() {
        guard browser == nil else { return }
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: Self.serviceType, domain: nil), using: .tcp)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            let found: [Peer] = results.compactMap { r in
                guard case .service(let name, _, _, _) = r.endpoint else { return nil }
                var id = name, peerName = name
                if case .bonjour(let txt) = r.metadata {
                    id = txt["id"] ?? name
                    peerName = txt["name"] ?? name
                }
                return Peer(id: id, name: peerName, endpoint: r.endpoint)
            }
            Task { @MainActor in
                guard let self else { return }
                self.peers = found.filter { $0.id != self.deviceId }.sorted { $0.name < $1.name }
            }
        }
        browser.start(queue: queue)
        self.browser = browser
    }

    func stopBrowsing() {
        browser?.cancel()
        browser = nil
        peers = []
    }

    enum SendResult { case sent, declined, failed(String) }

    /// Sends a .rmsc file to a peer and waits until it has imported it (or declined).
    nonisolated func send(_ file: URL, to peer: Peer, deviceId: String, deviceName: String) async -> SendResult {
        guard let body = try? Data(contentsOf: file, options: .mappedIfSafe) else { return .failed("Couldn't read the scan file.") }
        return await withCheckedContinuation { continuation in
            let connection = NWConnection(to: peer.endpoint, using: .tcp)
            var done = false
            func finish(_ r: SendResult) {
                guard !done else { return }
                done = true
                connection.cancel()
                continuation.resume(returning: r)
            }
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    let name = file.lastPathComponent.addingPercentEncoding(withAllowedCharacters: .alphanumerics.union(.init(charactersIn: ".-_ "))) ?? "scan.rmsc"
                    let head = "POST /rmsc HTTP/1.1\r\nHost: roomscape\r\nContent-Type: application/vnd.roomscape.rmsc+zip\r\n"
                        + "Content-Length: \(body.count)\r\nX-Device-Id: \(deviceId)\r\nX-Device-Name: \(deviceName)\r\n"
                        + "X-File-Name: \(name)\r\nConnection: close\r\n\r\n"
                    connection.send(content: Data(head.utf8) + body, completion: .contentProcessed { error in
                        if let error { finish(.failed(error.localizedDescription)) }
                    })
                    // The reply comes after the other phone has imported the scan (or the user answered).
                    HTTP.readStatus(connection) { status in
                        switch status {
                        case 200: finish(.sent)
                        case 403: finish(.declined)
                        case nil: finish(.failed("The other phone didn't answer."))
                        case let s?: finish(.failed("The other phone couldn't import it (\(s))."))
                        }
                    }
                case .failed(let error), .waiting(let error):
                    finish(.failed(error.localizedDescription))
                default:
                    break
                }
            }
            connection.start(queue: DispatchQueue(label: "RoomScape.nearby.send"))
            DispatchQueue.global().asyncAfter(deadline: .now() + 180) { finish(.failed("Timed out.")) }
        }
    }
}

/// Just enough HTTP/1.1 for the nearby transfer: one request with a Content-Length body, one short reply.
enum HTTP {
    struct Head {
        var method: String
        var path: String
        var headers: [String: String]
    }

    /// Reads the request line and headers, then streams the body (Content-Length bytes) into `file`.
    static func readRequest(_ c: NWConnection, bodyTo file: URL, done: @escaping (Head?) -> Void) {
        var buffer = Data()
        func readHead() {
            c.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { data, _, complete, error in
                if let data { buffer.append(data) }
                guard let end = buffer.range(of: Data("\r\n\r\n".utf8)) else {
                    if complete || error != nil || buffer.count > 65_536 { done(nil) } else { readHead() }
                    return
                }
                let lines = String(decoding: buffer[buffer.startIndex..<end.lowerBound], as: UTF8.self).components(separatedBy: "\r\n")
                let first = lines.first?.split(separator: " ").map(String.init) ?? []
                guard first.count >= 2 else { done(nil); return }
                var headers: [String: String] = [:]
                for line in lines.dropFirst() {
                    guard let colon = line.firstIndex(of: ":") else { continue }
                    headers[line[..<colon].lowercased()] = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
                        .removingPercentEncoding ?? ""
                }
                let head = Head(method: first[0], path: first[1], headers: headers)
                guard let length = Int(headers["content-length"] ?? ""), length > 0, length < 1_000_000_000 else { done(nil); return }
                FileManager.default.createFile(atPath: file.path, contents: nil)
                guard let out = try? FileHandle(forWritingTo: file) else { done(nil); return }
                let start = buffer[end.upperBound...]
                try? out.write(contentsOf: start)
                var remaining = length - start.count
                func readBody() {
                    guard remaining > 0 else { try? out.close(); done(head); return }
                    c.receive(minimumIncompleteLength: 1, maximumLength: min(remaining, 1 << 20)) { data, _, complete, error in
                        if let data, !data.isEmpty { try? out.write(contentsOf: data); remaining -= data.count }
                        if remaining <= 0 { try? out.close(); done(head) }
                        else if complete || error != nil { try? out.close(); done(nil) }
                        else { readBody() }
                    }
                }
                readBody()
            }
        }
        readHead()
    }

    static func respond(_ c: NWConnection, _ status: Int, _ text: String) {
        let body = Data(text.utf8)
        let head = "HTTP/1.1 \(status) \(status == 200 ? "OK" : "Error")\r\nContent-Type: text/plain\r\nContent-Length: \(body.count)\r\nConnection: close\r\n\r\n"
        c.send(content: Data(head.utf8) + body, completion: .contentProcessed { _ in c.cancel() })
    }

    /// The status code of a reply, or nil if the connection ended first.
    static func readStatus(_ c: NWConnection, done: @escaping (Int?) -> Void) {
        var buffer = Data()
        func read() {
            c.receive(minimumIncompleteLength: 1, maximumLength: 4096) { data, _, complete, error in
                if let data { buffer.append(data) }
                if let line = buffer.range(of: Data("\r\n".utf8)) {
                    let parts = String(decoding: buffer[buffer.startIndex..<line.lowerBound], as: UTF8.self).split(separator: " ")
                    done(parts.count >= 2 ? Int(parts[1]) : nil)
                } else if complete || error != nil {
                    done(nil)
                } else {
                    read()
                }
            }
        }
        read()
    }
}

/// The share-menu sheet: phones running RoomScape nearby; tap one to send the scan.
struct SendNearbySheet: View {
    let scan: SavedScan
    @ObservedObject private var nearby = Nearby.shared
    @Environment(\.dismiss) private var dismiss
    @State private var status: String?
    @State private var sending: String?
    @State private var name = ""

    var body: some View {
        NavigationStack {
            List {
                Section {
                    TextField("Your phone's name", text: $name)
                        .submitLabel(.done)
                        .onSubmit { nearby.rename(name) }
                } header: {
                    Text("Your phone's name")
                } footer: {
                    Text(nearby.needsName ? "Give this phone a name, like \"RK's iPhone\". Other phones see it when you send them a scan." : "Other phones see this name.")
                }
                Section {
                    if nearby.peers.isEmpty {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text("Looking for phones with RoomScape open…").foregroundStyle(.secondary)
                        }
                    }
                    ForEach(nearby.peers) { peer in
                        Button {
                            send(to: peer)
                        } label: {
                            HStack {
                                Label(peer.name, systemImage: "iphone.gen3.radiowaves.left.and.right")
                                Spacer()
                                if sending == peer.id { ProgressView() }
                            }
                        }
                        .disabled(sending != nil)
                    }
                } footer: {
                    Text("Both phones need to be on the same Wi-Fi (or one phone's hotspot), with RoomScape open on the other phone. The first time, the other phone asks to accept scans from this one.")
                }
                if let status {
                    Section { Text(status) }
                }
            }
            .navigationTitle("Send to nearby phone")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
        }
        .onAppear {
            name = nearby.needsName ? "" : nearby.deviceName
            nearby.browse()
        }
        .onDisappear {
            if !name.trimmingCharacters(in: .whitespaces).isEmpty, name != nearby.deviceName { nearby.rename(name) }
            nearby.stopBrowsing()
        }
    }

    private func send(to peer: Nearby.Peer) {
        if !name.trimmingCharacters(in: .whitespaces).isEmpty, name != nearby.deviceName { nearby.rename(name) }
        sending = peer.id
        status = "Packing the scan…"
        let scan = scan, id = nearby.deviceId, name = nearby.deviceName
        Task {
            let file = await Task.detached(priority: .userInitiated) { try? Rmsc.export(scan) }.value
            guard let file else { status = "Couldn't pack the scan."; sending = nil; return }
            status = "Sending to \(peer.name)… (the other phone may ask to accept it)"
            let result = await Nearby.shared.send(file, to: peer, deviceId: id, deviceName: name)
            sending = nil
            switch result {
            case .sent: status = "Sent. It's open as a new scan on \(peer.name)."
            case .declined: status = "\(peer.name) declined the scan."
            case .failed(let why): status = "Couldn't send: \(why)"
            }
        }
    }
}
