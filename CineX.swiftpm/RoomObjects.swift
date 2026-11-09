import CoreImage
import Foundation
import ImageIO
import RoomPlan
import simd

/// What a found object is. Movable things own the scan's splats inside their box (remove / move / turn them);
/// fixed things (sockets, room lights) are markers with a position.
enum ObjectKind: String, Codable, CaseIterable {
    case table, chair, sofa, bed, storage, lamp, plant, screen, appliance, fixture, other
    case ceilingLight, wallLight, powerSocket, powerStrip

    var isMarker: Bool { [.ceilingLight, .wallLight, .powerSocket, .powerStrip].contains(self) }
    var isLight: Bool { self == .ceilingLight || self == .wallLight || self == .lamp }
    var isPower: Bool { self == .powerSocket || self == .powerStrip }

    var label: String {
        switch self {
        case .table: return "Table"
        case .chair: return "Chair"
        case .sofa: return "Sofa"
        case .bed: return "Bed"
        case .storage: return "Shelf / cabinet"
        case .lamp: return "Lamp"
        case .plant: return "Plant"
        case .screen: return "Screen / TV"
        case .appliance: return "Appliance"
        case .fixture: return "Fixture"
        case .other: return "Object"
        case .ceilingLight: return "Ceiling light"
        case .wallLight: return "Wall light"
        case .powerSocket: return "Power socket"
        case .powerStrip: return "Power strip"
        }
    }

    var icon: String {
        switch self {
        case .table: return "table.furniture"
        case .chair: return "chair"
        case .sofa: return "sofa"
        case .bed: return "bed.double"
        case .storage: return "cabinet"
        case .lamp: return "lamp.floor"
        case .plant: return "leaf"
        case .screen: return "tv"
        case .appliance: return "refrigerator"
        case .fixture: return "shower"
        case .other: return "shippingbox"
        case .ceilingLight: return "lamp.ceiling"
        case .wallLight: return "light.recessed"
        case .powerSocket: return "poweroutlet.type.f"
        case .powerStrip: return "powerplug"
        }
    }
}

/// An object in the scanned room and the user's edits to it.
struct RoomObject: Codable, Identifiable, Equatable {
    /// Where it came from: RoomPlan, Claude, or another phone (the Android app, its catalogue).
    enum Source: String, Codable { case roomPlan, claude, android, catalogue }

    var id = UUID()
    var kind: ObjectKind
    var label: String
    var source: Source
    /// Oriented box: centre (world, metres), half sizes along its own axes, turn about the vertical (radians).
    var center: SIMD3<Float>
    var halfExtents: SIMD3<Float>
    var yaw: Float
    /// How many photos Claude saw it in (0 for RoomPlan-only).
    var views = 0
    // Edits.
    var removed = false
    var offset = SIMD3<Float>(0, 0, 0)
    var turn: Float = 0

    var isEdited: Bool { removed || simd_length(offset) > 0.001 || abs(turn) > 0.001 }

    /// World transform applied to the object's splats: turn about its own centre, then move by `offset`.
    var editTransform: simd_float4x4 {
        let c = center
        let r = simd_float4x4(simd_quatf(angle: turn, axis: SIMD3(0, 1, 0)))
        var t1 = matrix_identity_float4x4; t1.columns.3 = SIMD4(c + offset, 1)
        var t0 = matrix_identity_float4x4; t0.columns.3 = SIMD4(-c, 1)
        return t1 * r * t0
    }

    func contains(_ p: SIMD3<Float>, margin: Float) -> Bool {
        let d = p - center
        let c = cos(-yaw), s = sin(-yaw)
        let lx = c * d.x + s * d.z, lz = -s * d.x + c * d.z
        return abs(lx) <= halfExtents.x + margin && abs(d.y) <= halfExtents.y + margin && abs(lz) <= halfExtents.z + margin
    }

    var sizeText: String {
        if kind.isMarker { return "" }
        return String(format: "%.1f × %.1f × %.1f m", halfExtents.x * 2, halfExtents.z * 2, halfExtents.y * 2)
    }
}

/// Saved in `<scan>.capture/objects.json`.
struct ObjectsFile: Codable {
    var version = 1
    var claudeChecked = false
    var objects: [RoomObject]
}

enum RoomObjects {
    static func url(_ scan: SavedScan) -> URL { scan.captureURL.appendingPathComponent("objects.json") }

    /// The manifest's shared "objects" (docs/rmsc-format.md): every movable thing, with its id, where it is now and
    /// where the scan had it, and whether it's removed. Moved down by `dropY` (the floor to y = 0).
    static func toManifest(_ objects: [RoomObject], dropY: Float) -> [[String: Any]] {
        objects.filter { !$0.kind.isMarker }.map { o in
            let c = o.center + o.offset, c0 = o.center
            return ["id": o.id.uuidString, "name": o.label, "kind": o.kind.rawValue, "source": o.source.rawValue,
                    "center": [c.x, c.y - dropY, c.z],
                    "size": [o.halfExtents.x * 2, o.halfExtents.y * 2, o.halfExtents.z * 2], "yaw": o.yaw + o.turn,
                    "original": ["center": [c0.x, c0.y - dropY, c0.z], "yaw": o.yaw],
                    "removed": o.removed, "moved": simd_length(o.offset) > 0.001 || abs(o.turn) > 0.001]
        }
    }

    /// The manifest's shared "objects", moved up by `lift`.
    static func fromManifest(_ list: [[String: Any]]?, lift: Float) -> [RoomObject] {
        (list ?? []).compactMap { e in
            func v3(_ a: Any?) -> SIMD3<Float>? {
                guard let n = (a as? [NSNumber])?.map(\.floatValue), n.count == 3 else { return nil }
                return SIMD3(n[0], n[1], n[2])
            }
            guard let c = v3(e["center"]), let size = v3(e["size"]) else { return nil }
            let orig = e["original"] as? [String: Any]
            let c0 = v3(orig?["center"]) ?? c
            let yaw = (e["yaw"] as? NSNumber)?.floatValue ?? 0
            let yaw0 = (orig?["yaw"] as? NSNumber)?.floatValue ?? yaw
            let name = e["name"] as? String ?? "Object"
            var o = RoomObject(kind: (e["kind"] as? String).flatMap(ObjectKind.init(rawValue:)) ?? .other, label: name,
                               source: (e["source"] as? String).flatMap(RoomObject.Source.init(rawValue:)) ?? .android,
                               center: c0 + SIMD3(0, lift, 0), halfExtents: size / 2, yaw: yaw0)
            if let id = (e["id"] as? String).flatMap(UUID.init(uuidString:)) { o.id = id }
            o.removed = e["removed"] as? Bool ?? false
            o.offset = SIMD3(c.x - c0.x, 0, c.z - c0.z)
            o.turn = yaw - yaw0
            return o
        }
    }

    /// Both lists by id: `newer`'s version of each object, plus `older`'s objects it doesn't have (nothing dropped).
    static func merge(_ newer: [RoomObject], _ older: [RoomObject]) -> [RoomObject] {
        let ids = Set(newer.map(\.id))
        return newer + older.filter { !ids.contains($0.id) }
    }

    /// Writes the file without counting it as an edit (imports and sync merges).
    static func write(_ file: ObjectsFile, for scan: SavedScan) {
        if let data = try? JSONEncoder().encode(file) { try? data.write(to: url(scan), options: .atomic) }
    }

    static func load(_ scan: SavedScan) -> ObjectsFile? {
        guard let data = try? Data(contentsOf: url(scan)) else { return nil }
        return try? JSONDecoder().decode(ObjectsFile.self, from: data)
    }

    static func save(_ file: ObjectsFile, for scan: SavedScan) {
        try? FileManager.default.createDirectory(at: scan.captureURL, withIntermediateDirectories: true)
        let old = try? Data(contentsOf: url(scan))
        if let data = try? JSONEncoder().encode(file) {
            try? data.write(to: url(scan), options: .atomic)
            if let old, data != old { SyncStore.touch(scan) } // an edit: syncs to the phones it's shared with
        }
    }

    /// RoomPlan's own furniture: accurate 3D boxes, no Claude needed.
    static func fromRoomPlan(_ room: CapturedRoom) -> [RoomObject] {
        room.objects.map { o in
            let t = o.transform
            let kind: ObjectKind
            switch o.category {
            case .table: kind = .table
            case .chair: kind = .chair
            case .sofa: kind = .sofa
            case .bed: kind = .bed
            case .storage: kind = .storage
            case .television: kind = .screen
            case .refrigerator, .stove, .oven, .dishwasher, .washerDryer: kind = .appliance
            case .sink, .toilet, .bathtub, .fireplace, .stairs: kind = .fixture
            @unknown default: kind = .other
            }
            let name = String(describing: o.category)
            return RoomObject(kind: kind, label: name.prefix(1).uppercased() + name.dropFirst(), source: .roomPlan,
                              center: SIMD3(t.columns.3.x, t.columns.3.y, t.columns.3.z),
                              halfExtents: o.dimensions / 2, yaw: atan2(t.columns.0.z, t.columns.0.x) * -1)
        }
    }
}

// MARK: - Finding objects with Claude

/// Looks through the scan's photos with Claude for everything RoomPlan doesn't find (power sockets, room lights,
/// lamps, plants, screens…) and names what it did find. Each photo comes with the already-known objects in view and
/// where they are, so Claude names those instead of reporting them twice, and only new things get placed:
/// a new 2D box is lifted into the room with the scan's own LiDAR points behind it.
enum ObjectFinder {
    static let maxPhotos = 30
    static let photosPerRequest = 6

    struct Progress { var done: Int; var total: Int }

    /// One photo prepared for Claude: turned upright (Claude reads upright photos better than the sensor's sideways
    /// ones), with a map from upright box coordinates back to the original photo.
    private struct Prepared {
        let info: KeyframeRecord
        let pose: simd_float4x4
        let jpeg: Data
        let quarterTurns: Int // counter-clockwise turns applied to make it upright
        var known: [(index: Int, box: SIMD4<Float>)] // in upright 0…1000 coordinates
    }

    private struct Reply: Decodable {
        struct Item: Decodable {
            let photo: Int
            let known: Int
            let kind: String
            let label: String
            let x0: Double, y0: Double, x1: Double, y1: Double
        }
        let objects: [Item]
    }

    static let schema: [String: Any] = [
        "type": "object",
        "properties": [
            "objects": [
                "type": "array",
                "items": [
                    "type": "object",
                    "properties": [
                        "photo": ["type": "integer", "description": "Photo number"],
                        "known": ["type": "integer", "description": "Number of the known object this is, or -1 if new"],
                        "kind": ["type": "string", "enum": ObjectKind.allCases.map(\.rawValue)],
                        "label": ["type": "string", "description": "Short specific name, e.g. 'oak dining table', 'double wall socket'"],
                        "x0": ["type": "number"], "y0": ["type": "number"], "x1": ["type": "number"], "y1": ["type": "number"],
                    ],
                    "required": ["photo", "known", "kind", "label", "x0", "y0", "x1", "y1"],
                    "additionalProperties": false,
                ] as [String: Any],
            ] as [String: Any],
        ],
        "required": ["objects"],
        "additionalProperties": false,
    ]

    static func find(scan: SavedScan, room: CapturedRoom, objects existing: [RoomObject],
                     progress: @escaping @Sendable (Progress) -> Void) async throws -> [RoomObject] {
        guard let data = try? Data(contentsOf: scan.captureURL.appendingPathComponent("keyframes.json")),
              let infos = try? JSONDecoder().decode([KeyframeRecord].self, from: data), !infos.isEmpty else { return existing }
        let step = max(1, Int((Double(infos.count) / Double(maxPhotos)).rounded(.up)))
        let chosen = Swift.stride(from: 0, to: infos.count, by: step).map { infos[$0] }

        let shell = RoomShell(room: room)
        let all = ScanStore.loadPoints(scan)
        let thin = max(1, all.count / 250_000)
        let points = Swift.stride(from: 0, to: all.count, by: thin).map { SIMD3(all[$0].x, all[$0].y, all[$0].z) }

        var prepared = chosen.compactMap { prepare($0, scan: scan) }
        for i in prepared.indices { prepared[i].known = knownInView(existing, photo: prepared[i]) }

        let batches = Swift.stride(from: 0, to: prepared.count, by: photosPerRequest).map {
            Array(prepared[$0..<min($0 + photosPerRequest, prepared.count)])
        }
        progress(Progress(done: 0, total: batches.count))
        var results: [(batch: [Prepared], reply: Reply)] = []
        // One failed request (a timeout, a declined photo, an unreadable reply) costs only its own photos: the
        // search fails only when every request does. A bad key or a cancel still stops everything.
        var firstFailure: Error?
        try await withThrowingTaskGroup(of: ([Prepared], Result<Reply, Error>).self) { group in
            var next = 0, done = 0
            func launch() {
                guard next < batches.count else { return }
                let batch = batches[next]
                next += 1
                group.addTask {
                    do {
                        let data = try await ClaudeAPI.askAboutPhotos(batch.map(\.jpeg),
                                                                      instructions: instructions(batch, known: existing),
                                                                      schema: schema)
                        do {
                            return (batch, .success(try JSONDecoder().decode(Reply.self, from: data)))
                        } catch {
                            return (batch, .failure(ClaudeAPI.Failure.badReply(error.localizedDescription)))
                        }
                    } catch ClaudeAPI.Failure.noKey {
                        throw ClaudeAPI.Failure.noKey
                    } catch ClaudeAPI.Failure.badKey {
                        throw ClaudeAPI.Failure.badKey
                    } catch is CancellationError {
                        throw CancellationError()
                    } catch {
                        return (batch, .failure(error))
                    }
                }
            }
            for _ in 0..<3 { launch() } // three requests at a time
            while let r = try await group.next() {
                switch r.1 {
                case .success(let reply): results.append((r.0, reply))
                case .failure(let error): firstFailure = firstFailure ?? error
                }
                done += 1
                progress(Progress(done: done, total: batches.count))
                launch()
            }
        }
        if results.isEmpty, let firstFailure { throw firstFailure }

        var objects = existing
        for (batch, reply) in results {
            for item in reply.objects {
                guard item.photo >= 0, item.photo < batch.count, let kind = ObjectKind(rawValue: item.kind) else { continue }
                let label = item.label.trimmingCharacters(in: .whitespacesAndNewlines)
                if item.known >= 0, item.known < existing.count {
                    let id = existing[item.known].id
                    if let i = objects.firstIndex(where: { $0.id == id }) {
                        objects[i].views += 1
                        if !label.isEmpty, objects[i].source == .roomPlan || label.count > objects[i].label.count {
                            objects[i].label = label.prefix(1).uppercased() + label.dropFirst()
                        }
                    }
                    continue
                }
                let photo = batch[item.photo]
                let upright = SIMD4<Float>(Float(min(item.x0, item.x1)), Float(min(item.y0, item.y1)),
                                           Float(max(item.x0, item.x1)), Float(max(item.y0, item.y1))) / 1000
                let box = original(upright, quarterTurns: photo.quarterTurns)
                guard box.z > box.x, box.w > box.y,
                      var found = lift(box, kind: kind, photo: photo, points: points, shell: shell) else { continue }
                found.label = label.isEmpty ? kind.label : label.prefix(1).uppercased() + label.dropFirst()
                merge(found, into: &objects)
            }
        }
        return objects
    }

    private static func instructions(_ batch: [Prepared], known: [RoomObject]) -> String {
        var s = """
        These photos come from a phone scanning a room, to plan a film or photo shoot in it. \
        Find the things a crew would care about: furniture (tables, desks, chairs, sofas, beds, shelves, cabinets), \
        lamps, ceiling lights and wall lights (the fittings, lit or not), power sockets and power strips (not light \
        switches, network or antenna sockets), plants, screens and TVs, appliances, and other large objects. \
        Only things you can actually see; an empty list is fine.

        Some objects are already known from the 3D scan. For each photo they are listed with their number and box. \
        If a thing you report is one of them, set "known" to its number and give it a short, specific label \
        (e.g. "grey fabric sofa"); otherwise set "known" to -1. A known object may be hidden behind something in a \
        photo: then don't report it there.

        Boxes are tight bounding boxes in thousandths of that photo's width and height (0-1000, top-left origin).
        """
        for (i, p) in batch.enumerated() where !p.known.isEmpty {
            let list = p.known.map { k in
                String(format: "#%d %@ [%.0f, %.0f, %.0f, %.0f]", k.index, known[k.index].kind.label,
                       k.box.x, k.box.y, k.box.z, k.box.w)
            }.joined(separator: "; ")
            s += "\nPhoto \(i) known: \(list)"
        }
        return s
    }

    // MARK: Photos

    private static func prepare(_ info: KeyframeRecord, scan: SavedScan) -> Prepared? {
        guard info.cameraToWorld.count == 16 else { return nil }
        let m = info.cameraToWorld
        let pose = simd_float4x4(SIMD4(m[0], m[1], m[2], m[3]), SIMD4(m[4], m[5], m[6], m[7]),
                                 SIMD4(m[8], m[9], m[10], m[11]), SIMD4(m[12], m[13], m[14], m[15]))
        let url = scan.captureURL.appendingPathComponent(info.file)
        guard let image = CIImage(contentsOf: url) else { return nil }
        // Which way is "up" in the photo: world up seen from the camera, in image axes (u right, v down).
        let upCam = simd_transpose(simd_float3x3(SIMD3(pose.columns.0.x, pose.columns.0.y, pose.columns.0.z),
                                                 SIMD3(pose.columns.1.x, pose.columns.1.y, pose.columns.1.z),
                                                 SIMD3(pose.columns.2.x, pose.columns.2.y, pose.columns.2.z))) * SIMD3(0, 1, 0)
        let upU = upCam.x, upV = -upCam.y
        let turns: Int // counter-clockwise quarter turns that bring "up" to the top
        if abs(upV) >= abs(upU) { turns = upV < 0 ? 0 : 2 } else { turns = upU > 0 ? 1 : 3 }
        var rotated = image.transformed(by: CGAffineTransform(rotationAngle: CGFloat(turns) * .pi / 2))
        rotated = rotated.transformed(by: CGAffineTransform(translationX: -rotated.extent.origin.x, y: -rotated.extent.origin.y))
        let context = CIContext()
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let jpeg = context.jpegRepresentation(of: rotated, colorSpace: space,
                  options: [CIImageRepresentationOption(rawValue: kCGImageDestinationLossyCompressionQuality as String): 0.8])
        else { return nil }
        return Prepared(info: info, pose: pose, jpeg: jpeg, quarterTurns: turns, known: [])
    }

    /// Upright-photo box (0…1, x0 y0 x1 y1) → the same box in the original photo.
    static func original(_ b: SIMD4<Float>, quarterTurns: Int) -> SIMD4<Float> {
        func map(_ x: Float, _ y: Float) -> SIMD2<Float> {
            switch quarterTurns {
            case 1: return SIMD2(1 - y, x)   // upright = original turned counter-clockwise
            case 2: return SIMD2(1 - x, 1 - y)
            case 3: return SIMD2(y, 1 - x)   // turned clockwise
            default: return SIMD2(x, y)
            }
        }
        let a = map(b.x, b.y), c = map(b.z, b.w)
        return SIMD4(min(a.x, c.x), min(a.y, c.y), max(a.x, c.x), max(a.y, c.y))
    }

    /// Original-photo box → upright (inverse of `original`).
    static func upright(_ b: SIMD4<Float>, quarterTurns: Int) -> SIMD4<Float> {
        original(b, quarterTurns: (4 - quarterTurns) % 4)
    }

    /// Known objects whose box is mostly in view in this photo, with their upright box in thousandths.
    private static func knownInView(_ objects: [RoomObject], photo: Prepared) -> [(index: Int, box: SIMD4<Float>)] {
        let view = photo.pose.inverse
        let info = photo.info
        var out: [(Int, SIMD4<Float>)] = []
        for (i, o) in objects.enumerated() where !o.kind.isMarker {
            var lo = SIMD2<Float>(repeating: .infinity), hi = SIMD2<Float>(repeating: -.infinity)
            var behind = false
            for sx in [-1, 1] as [Float] { for sy in [-1, 1] as [Float] { for sz in [-1, 1] as [Float] {
                let c = cos(o.yaw), s = sin(o.yaw)
                let local = SIMD3(sx * o.halfExtents.x, sy * o.halfExtents.y, sz * o.halfExtents.z)
                let w = o.center + SIMD3(c * local.x + s * local.z, local.y, -s * local.x + c * local.z)
                let pc = view * SIMD4(w, 1)
                let d = -pc.z
                if d < 0.3 { behind = true; continue }
                let u = (info.fx * pc.x / d + info.cx) / Float(info.width)
                let v = (info.cy - info.fy * pc.y / d) / Float(info.height)
                lo = simd_min(lo, SIMD2(u, v)); hi = simd_max(hi, SIMD2(u, v))
            }}}
            guard !behind else { continue }
            let clippedLo = simd_clamp(lo, SIMD2(0, 0), SIMD2(1, 1)), clippedHi = simd_clamp(hi, SIMD2(0, 0), SIMD2(1, 1))
            let full = (hi.x - lo.x) * (hi.y - lo.y)
            let shown = max(0, clippedHi.x - clippedLo.x) * max(0, clippedHi.y - clippedLo.y)
            guard full > 0, shown / full > 0.4, shown > 0.002 else { continue }
            let b = upright(SIMD4(clippedLo.x, clippedLo.y, clippedHi.x, clippedHi.y), quarterTurns: photo.quarterTurns)
            out.append((i, b * 1000))
        }
        return out
    }

    // MARK: Into the room

    /// Places a photo box in the room using the scan's points that project inside it: the front-most surface there
    /// (30th percentile of depth in the box's middle). Fixed things become a point on that surface; movable things
    /// get a box around the points in a depth band behind the front, without the floor and walls.
    private static func lift(_ box: SIMD4<Float>, kind: ObjectKind, photo: Prepared, points: [SIMD3<Float>],
                             shell: RoomShell) -> RoomObject? {
        let info = photo.info
        let view = photo.pose.inverse
        let w = Float(info.width), h = Float(info.height)
        let mid = SIMD2((box.x + box.z) / 2, (box.y + box.w) / 2)
        let half = SIMD2(max(0.01, (box.z - box.x) / 4), max(0.01, (box.w - box.y) / 4))
        var inner: [Float] = []
        var inBox: [(p: SIMD3<Float>, d: Float, inner: Bool)] = []
        for p in points {
            let pc = view * SIMD4(p, 1)
            let d = -pc.z
            guard d > 0.2, d < 8 else { continue }
            let u = (info.fx * pc.x / d + info.cx) / w, v = (info.cy - info.fy * pc.y / d) / h
            guard u >= box.x, u <= box.z, v >= box.y, v <= box.w else { continue }
            let isInner = abs(u - mid.x) <= half.x && abs(v - mid.y) <= half.y
            inBox.append((p, d, isInner))
            if isInner { inner.append(d) }
        }
        guard inner.count >= 5 else { return nil }
        inner.sort()
        let front = inner[Int(Float(inner.count) * 0.3)]
        let metricW = (box.z - box.x) * w / info.fx * front, metricH = (box.w - box.y) * h / info.fy * front

        if kind.isMarker {
            let xc = (mid.x * w - info.cx) / info.fx * front, yc = (info.cy - mid.y * h) / info.fy * front
            let p = photo.pose * SIMD4(xc, yc, -front, 1)
            return RoomObject(kind: kind, label: kind.label, source: .claude, center: SIMD3(p.x, p.y, p.z),
                              halfExtents: SIMD3(max(0.04, metricW / 2), max(0.04, metricH / 2), 0.03), yaw: 0, views: 1)
        }

        let depthBand = min(1.5, max(0.3, metricW))
        let yaw = roomYaw(shell)
        let c = cos(-yaw), s = sin(-yaw)
        let band = inBox.filter { $0.d >= front - 0.1 && $0.d <= front + depthBand && $0.p.y > shell.floorY + 0.04
            && !nearWall($0.p, shell) }
        // Keep the connected clump (6 cm grid) with the most points under the middle of the box, so a chair
        // behind a table, or the next thing over, isn't taken along.
        let local = largestClump(band).map { SIMD3(c * $0.x + s * $0.z, $0.y, -s * $0.x + c * $0.z) }
        guard local.count >= 40 else { return nil }
        func pct(_ values: [Float], _ q: Float) -> Float { values.sorted()[Int(Float(values.count - 1) * q)] }
        let xs = local.map(\.x), ys = local.map(\.y), zs = local.map(\.z)
        var lo = SIMD3(pct(xs, 0.02), pct(ys, 0.02), pct(zs, 0.02))
        let hi = SIMD3(pct(xs, 0.98), pct(ys, 0.98), pct(zs, 0.98))
        if lo.y < shell.floorY + 0.35 { lo.y = shell.floorY + 0.02 } // stands on the floor: include its feet
        let centerLocal = (lo + hi) / 2
        let ci = cos(yaw), si = sin(yaw)
        let center = SIMD3(ci * centerLocal.x + si * centerLocal.z, centerLocal.y, -si * centerLocal.x + ci * centerLocal.z)
        return RoomObject(kind: kind, label: kind.label, source: .claude, center: center,
                          halfExtents: simd_max((hi - lo) / 2, SIMD3(repeating: 0.05)), yaw: yaw, views: 1)
    }

    static func largestClump(_ pts: [(p: SIMD3<Float>, d: Float, inner: Bool)]) -> [SIMD3<Float>] {
        let cell: Float = 0.06
        func key(_ p: SIMD3<Float>) -> SIMD3<Int32> { SIMD3<Int32>(floor(p / cell)) }
        var grid: [SIMD3<Int32>: [Int]] = [:]
        for (i, q) in pts.enumerated() { grid[key(q.p), default: []].append(i) }
        var label = [Int](repeating: -1, count: pts.count)
        var best: (score: Int, members: [Int]) = (0, [])
        for start in pts.indices where label[start] < 0 {
            var members: [Int] = [], stack = [start], score = 0
            label[start] = start
            var visitedCells = Set<SIMD3<Int32>>()
            while let i = stack.popLast() {
                members.append(i)
                if pts[i].inner { score += 1 }
                let k = key(pts[i].p)
                guard visitedCells.insert(k).inserted else { continue }
                for dx in -1...1 { for dy in -1...1 { for dz in -1...1 {
                    for j in grid[k &+ SIMD3(Int32(dx), Int32(dy), Int32(dz))] ?? [] where label[j] < 0 {
                        label[j] = start
                        stack.append(j)
                    }
                }}}
            }
            if score > best.score { best = (score, members) }
        }
        return best.members.map { pts[$0].p }
    }

    /// The direction of the room's longest wall, so new boxes line up with the room.
    static func roomYaw(_ shell: RoomShell) -> Float {
        guard let w = shell.walls.max(by: { $0.halfWidth < $1.halfWidth }) else { return 0 }
        return -atan2(w.right.z, w.right.x)
    }

    private static func nearWall(_ p: SIMD3<Float>, _ shell: RoomShell) -> Bool {
        shell.walls.contains { w in
            let d = p - w.center
            return abs(simd_dot(d, w.inward)) < 0.05 && abs(simd_dot(d, w.right)) <= w.halfWidth
                && abs(simd_dot(d, w.up)) <= w.halfHeight
        }
    }

    /// Adds a newly placed object, or folds it into one already known: markers of the same kind within 25 cm, or a
    /// movable object of the same kind whose box overlaps.
    private static func merge(_ new: RoomObject, into objects: inout [RoomObject]) {
        if new.kind.isMarker {
            if let i = objects.firstIndex(where: { $0.kind == new.kind && simd_distance($0.center, new.center) < 0.25 }) {
                let n = Float(objects[i].views)
                objects[i].center = (objects[i].center * n + new.center) / (n + 1)
                objects[i].views += 1
                return
            }
            objects.append(new)
            return
        }
        if let i = objects.firstIndex(where: { o in
            !o.kind.isMarker && (o.kind == new.kind || o.kind == .other || new.kind == .other)
                && (o.contains(new.center, margin: 0.15) || new.contains(o.center, margin: 0.15))
        }) {
            objects[i].views += 1
            if objects[i].source == .claude {
                // Grow to cover both sightings, in the room's axes (both boxes line up with the room).
                let yaw = objects[i].yaw
                func local(_ p: SIMD3<Float>) -> SIMD3<Float> {
                    SIMD3(cos(yaw) * p.x - sin(yaw) * p.z, p.y, sin(yaw) * p.x + cos(yaw) * p.z)
                }
                let a = local(objects[i].center), b = local(new.center)
                let lo = simd_min(a - objects[i].halfExtents, b - new.halfExtents)
                let hi = simd_max(a + objects[i].halfExtents, b + new.halfExtents)
                let m = (lo + hi) / 2
                objects[i].center = SIMD3(cos(yaw) * m.x + sin(yaw) * m.z, m.y, -sin(yaw) * m.x + cos(yaw) * m.z)
                objects[i].halfExtents = (hi - lo) / 2
            }
            return
        }
        objects.append(new)
    }
}
