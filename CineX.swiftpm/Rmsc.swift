import Foundation
import RoomPlan
import SceneKit
import simd
import UniformTypeIdentifiers

/// .rmsc: the RoomScape scan package shared by the iPhone app, the Android app and the web viewer (format:
/// docs/rmsc-format.md). A zip with manifest.json, points.ply, photos (+ depth), room.glb, mesh.ply, thumb.jpg and
/// iOS extras in ios/.
///
/// Export moves everything so the floor is at y = 0 (RoomPlan's floor). The iOS extras (world map, RoomPlan room,
/// model, objects) stay in the scan's own coordinates, and ios/shift.json records the move, so an iPhone importing
/// its own package moves everything back and the world map still recognises the room for Continue scan.
enum Rmsc {
    static let type = UTType(exportedAs: "com.roomscape.rmsc", conformingTo: .zip)
    static let fileExtension = "rmsc"

    enum Failure: LocalizedError {
        case noCapture, notRmsc, newerVersion(Int)
        var errorDescription: String? {
            switch self {
            case .noCapture: return "This scan has no saved points or photos to export."
            case .notRmsc: return "This file isn't a RoomScape scan (.rmsc)."
            case .newerVersion(let v): return "This scan was made by a newer version of the app (format \(v)). Update the app to open it."
            }
        }
    }

    // MARK: Export

    /// Writes the scan as "<name>.rmsc" in the temporary folder. Slow (reads every photo): call off the main thread.
    static func export(_ scan: SavedScan) throws -> URL {
        let folder = scan.captureURL
        guard let meta = CaptureStore.loadMeta(folder) else { throw Failure.noCapture }
        let room = ScanStore.loadRoom(scan)
        let shell = room.map(RoomShell.init)
        let points = ScanStore.loadPoints(scan)
        let mesh = ScanMesh.read(from: folder.appendingPathComponent(ScanMesh.fileName))
        let floorY = shell?.floorY ?? lowY(mesh?.positions ?? points.map { SIMD3($0.x, $0.y, $0.z) })
        let shift = SIMD3<Float>(0, -floorY, 0)

        let safeName = (scan.title ?? scan.name).components(separatedBy: CharacterSet(charactersIn: "/\\:?%*|\"<>")).joined(separator: "-")
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("rmsc-\(scan.name)", isDirectory: true)
        try? FileManager.default.removeItem(at: dir)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent(safeName + "." + fileExtension)
        let zip = try Zip.Writer(url: url)

        // Points.
        var lo = SIMD3<Float>(repeating: .greatestFiniteMagnitude), hi = -lo
        var ply = Data(Ply.header(vertices: points.count, faces: nil).utf8)
        ply.reserveCapacity(ply.count + points.count * 15)
        for p in points {
            let q = SIMD3(p.x, p.y, p.z) + shift
            lo = simd_min(lo, q); hi = simd_max(hi, q)
            ply.appendFloats(q)
            ply.append(contentsOf: [UInt8(p.rgba & 0xFF), UInt8((p.rgba >> 8) & 0xFF), UInt8((p.rgba >> 16) & 0xFF)])
        }
        try zip.add("points.ply", data: ply)

        // Photos and their depth.
        var photos: [[String: Any]] = []
        if let data = try? Data(contentsOf: folder.appendingPathComponent("keyframes.json")),
           let records = try? JSONDecoder().decode([KeyframeRecord].self, from: data) {
            for (i, r) in records.enumerated() where r.cameraToWorld.count == 16 {
                guard let jpeg = try? Data(contentsOf: folder.appendingPathComponent(r.file)) else { continue }
                let file = String(format: "photos/%03d.jpg", i)
                try zip.add(file, data: jpeg)
                var pose = r.cameraToWorld
                pose[13] -= floorY
                var photo: [String: Any] = ["file": file, "width": r.width, "height": r.height,
                                            "fx": r.fx, "fy": r.fy, "cx": r.cx, "cy": r.cy, "pose": pose, "time": r.timestamp]
                if let df = r.depthFile, let dw = r.depthWidth, let dh = r.depthHeight,
                   let depth = try? Data(contentsOf: folder.appendingPathComponent(df)) {
                    let dfile = String(format: "depth/%03d.bin", i)
                    try zip.add(dfile, data: depth)
                    let s = Float(dw) / Float(r.width)
                    photo["depth"] = ["file": dfile, "width": dw, "height": dh,
                                      "fx": r.fx * s, "fy": r.fy * s, "cx": r.cx * s, "cy": r.cy * s]
                }
                photos.append(photo)
            }
        }
        try zip.add("photos.json", data: try JSONSerialization.data(withJSONObject: ["photos": photos], options: [.prettyPrinted, .sortedKeys]))

        // Photo room and mesh.
        var triangles = 0
        if let glb = PhotoRoomExport.glb(folder: folder, floorY: floorY) {
            try zip.add("room.glb", data: glb.data)
            triangles = glb.triangles
        }
        if let mesh {
            let colors = PhotoRoomExport.PointColors(folder: folder)
            var data = Data(Ply.header(vertices: mesh.positions.count, faces: mesh.triangles.count / 3).utf8)
            for p in mesh.positions {
                data.appendFloats(p + shift)
                let c = colors?.srgb(near: p) ?? SIMD3(repeating: 0.7)
                data.append(contentsOf: [c.x, c.y, c.z].map { UInt8(max(0, min(255, $0 * 255))) })
            }
            for t in 0..<(mesh.triangles.count / 3) {
                data.append(3)
                for c in 0..<3 { withUnsafeBytes(of: Int32(mesh.triangles[t * 3 + c]).littleEndian) { data.append(contentsOf: $0) } }
            }
            try zip.add("mesh.ply", data: data)
            triangles = mesh.triangles.count / 3
        }
        if FileManager.default.fileExists(atPath: scan.thumbnailURL.path) { try zip.add("thumb.jpg", file: scan.thumbnailURL) }

        // iOS extras, in the scan's own coordinates.
        let worldMap = folder.appendingPathComponent("worldmap.arexperience")
        if FileManager.default.fileExists(atPath: worldMap.path) { try zip.add("ios/worldmap.arexperience", file: worldMap) }
        if FileManager.default.fileExists(atPath: scan.dataURL.path) { try zip.add("ios/room.json", file: scan.dataURL) }
        if FileManager.default.fileExists(atPath: scan.modelURL.path) { try zip.add("ios/model.usdz", file: scan.modelURL) }
        let objectsURL = RoomObjects.url(scan)
        if FileManager.default.fileExists(atPath: objectsURL.path) { try zip.add("ios/objects.json", file: objectsURL) }
        try zip.add("ios/shift.json", data: try JSONSerialization.data(withJSONObject: ["floorY": floorY]))

        // Manifest.
        let iso = ISO8601DateFormatter()
        let sync = SyncStore.info(scan)
        var manifest: [String: Any] = [
            "format": "rmsc", "version": 1,
            "id": sync.id, "revision": sync.revision, "revisionBy": sync.revisionBy,
            // Unnamed: "Room <date>" in one fixed format, which Android also uses and shows as just the date.
            "name": scan.title ?? "Room " + { let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "d MMM yyyy, HH:mm"; return f.string(from: scan.date) }(),
            "created": iso.string(from: scan.date),
            "modified": iso.string(from: Date()),
            "source": ["platform": "ios", "app": "RoomScape",
                       "appVersion": Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "",
                       "device": deviceModel()],
            "floorY": 0,
            "counts": ["points": meta.pointCount, "photos": photos.count, "triangles": triangles],
        ]
        if !points.isEmpty { manifest["bounds"] = ["min": [lo.x, lo.y, lo.z], "max": [hi.x, hi.y, hi.z]] }
        if let shell {
            if let ceiling = shell.ceilingY { manifest["ceilingY"] = ceiling - floorY }
            manifest["room"] = [
                "corners": shell.floorOutline.map { [$0.x, $0.y - floorY, $0.z] },
                "walls": shell.walls.map { w -> [Float] in
                    let a = w.center - w.right * w.halfWidth, b = w.center + w.right * w.halfWidth
                    return [a.x, a.z, b.x, b.z, w.halfHeight * 2]
                },
            ]
        }
        let objects = RoomObjects.load(scan)?.objects ?? room.map(RoomObjects.fromRoomPlan) ?? []
        manifest["objects"] = objects.filter { !$0.removed && !$0.kind.isMarker }.map { o -> [String: Any] in
            let c = o.center + o.offset + shift
            return ["name": o.label, "center": [c.x, c.y, c.z],
                    "size": [o.halfExtents.x * 2, o.halfExtents.y * 2, o.halfExtents.z * 2], "yaw": o.yaw + o.turn]
        }
        try zip.add("manifest.json", data: try JSONSerialization.data(withJSONObject: manifest, options: [.prettyPrinted, .sortedKeys]))
        try zip.finish()
        return url
    }

    /// A low percentile of heights: the floor when there's no RoomPlan floor.
    private static func lowY(_ ps: [SIMD3<Float>]) -> Float {
        let ys = ps.map(\.y).sorted()
        return ys.isEmpty ? 0 : ys[min(ys.count - 1, ys.count * 5 / 1000)]
    }

    private static func deviceModel() -> String {
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { String(decoding: $0.prefix(while: { $0 != 0 }), as: UTF8.self) }
    }

    // MARK: Import

    struct ImportResult {
        enum Outcome { case new, updated, unchanged, conflict }
        var scan: SavedScan
        var outcome: Outcome
    }

    /// Imports a .rmsc. A package whose `id` matches a scan here updates that scan in place when it's a newer
    /// revision (if both sides changed it, this phone's version is kept as a copy first: data is never lost);
    /// anything else becomes a new scan. `peer` is the phone it came from (the scan is then shared with it).
    /// Slow: call off the main thread.
    static func importScan(from url: URL, peer: (id: String, name: String)? = nil) throws -> ImportResult {
        let zip = try Zip.Reader(url: url)
        guard let manifestData = try zip.read("manifest.json"),
              let manifest = try JSONSerialization.jsonObject(with: manifestData) as? [String: Any] else { throw Failure.notRmsc }
        let id = manifest["id"] as? String
        let revision = (manifest["revision"] as? NSNumber)?.intValue ?? 1
        let by = manifest["revisionBy"] as? String ?? peer?.id ?? ""
        var outcome = ImportResult.Outcome.new
        var existing: SavedScan?
        var previous: SyncInfo?
        var repair = false
        let counts = manifest["counts"] as? [String: Any] ?? [:]
        let incomingHasData = ((counts["points"] as? NSNumber)?.intValue ?? 0) > 0 || ((counts["photos"] as? NSNumber)?.intValue ?? 0) > 0
        if let id, let local = SyncStore.scan(withId: id) {
            let info = SyncStore.info(local)
            let meta = CaptureStore.loadMeta(local.captureURL)
            let localHasData = (meta?.pointCount ?? 0) > 0 || (meta?.keyframeCount ?? 0) > 0
            // Data is gold: a package without points or photos never replaces a scan that has them (only its name
            // and version are taken), and a scan that lost its data takes a full package back, whatever its version.
            if localHasData && !incomingHasData {
                var i = info
                if revision > i.revision {
                    if let title = manifest["name"] as? String, !title.isEmpty, !title.hasPrefix("Room ") { _ = ScanStore.rename(local, to: title, touch: false) }
                    i.revision = revision; i.revisionBy = by; i.syncedRevision = revision
                }
                if let peer { i.shared = true; i.peers[peer.id] = peer.name }
                SyncStore.save(i, for: local)
                return ImportResult(scan: local, outcome: revision > info.revision ? .updated : .unchanged)
            }
            repair = !localHasData && incomingHasData
            if revision <= info.revision, !repair {
                if let peer { SyncStore.markShared(local, with: peer.id, name: peer.name, synced: revision) }
                return ImportResult(scan: local, outcome: .unchanged)
            }
            existing = local
            previous = info
            // Changed here too since the last sync: keep this phone's version as a copy under a new id.
            if !repair, info.revision > info.syncedRevision, info.syncedRevision > 0 {
                outcome = .conflict
                try ScanStore.duplicate(local, title: "\(local.displayName) (copy from \(Nearby.localName))")
            } else {
                outcome = .updated
            }
        }
        var scan = try importNew(from: url, zip: zip, manifest: manifest)
        if let existing { scan = try ScanStore.replace(existing, with: scan) }
        var info = SyncStore.info(scan)
        info.id = id ?? info.id
        // A repaired scan gets a revision above both, so the phones that have the empty version take it back.
        info.revision = repair ? max(revision, previous?.revision ?? 0) + 1 : revision
        info.revisionBy = repair ? SyncStore.deviceId : by
        info.modified = Date()
        info.syncedRevision = repair ? 0 : revision
        if let previous { info.peers.merge(previous.peers) { a, _ in a }; info.shared = info.shared || previous.shared }
        if let peer { info.shared = true; info.peers[peer.id] = peer.name }
        SyncStore.save(info, for: scan)
        if existing == nil { ScanStore.invalidateThumbnail(scan) }
        if repair { Task { @MainActor in Nearby.shared.syncSoon() } }
        return ImportResult(scan: scan, outcome: outcome)
    }

    /// Makes a new saved scan from a .rmsc file. Slow: call off the main thread.
    private static func importNew(from url: URL, zip: Zip.Reader, manifest: [String: Any]) throws -> SavedScan {
        guard manifest["format"] as? String == "rmsc" else { throw Failure.notRmsc }
        if let v = manifest["version"] as? Int, v > 1 { throw Failure.newerVersion(v) }
        let fm = FileManager.default

        // An iPhone's own package goes back to its own coordinates, so its world map still fits. Any other package is
        // moved so its floor (`floorY`, which Android writes as the real floor height) is at y = 0.
        var lift: Float = -((manifest["floorY"] as? NSNumber)?.floatValue ?? 0)
        if let s = try zip.read("ios/shift.json"), let j = try? JSONSerialization.jsonObject(with: s) as? [String: Any],
           let f = (j["floorY"] as? NSNumber)?.floatValue, zip.contains("ios/worldmap.arexperience") {
            lift = f
        }
        let up = SIMD3<Float>(0, lift, 0)

        let name = ScanStore.newScanName()
        let modelURL = ScanStore.directory.appendingPathComponent(name + ".usdz")
        var scan = SavedScan(name: name, modelURL: modelURL, dataURL: ScanStore.directory.appendingPathComponent(name + ".json"), date: Date())
        let folder = scan.captureURL
        try? fm.removeItem(at: folder)
        try fm.createDirectory(at: folder.appendingPathComponent("photos"), withIntermediateDirectories: true)
        do {
            // Points.
            var points: [LivePoint] = []
            if let data = try zip.read("points.ply") {
                let ply = try Ply(data)
                points = ply.vertices.map { v in
                    let p = v.position + up
                    return LivePoint(x: p.x, y: p.y, z: p.z,
                                     rgba: UInt32(v.color.x) | (UInt32(v.color.y) << 8) | (UInt32(v.color.z) << 16) | (255 << 24))
                }
            }
            try points.withUnsafeBytes { Data($0) }.write(to: folder.appendingPathComponent("points.bin"))

            // Photos.
            var records: [KeyframeRecord] = []
            /// Depth images with their own intrinsics, to build a mesh when the package has none.
            var depthViews: [(pose: simd_float4x4, depth: Data, w: Int, h: Int, fx: Float, fy: Float, cx: Float, cy: Float)] = []
            if let data = try zip.read("photos.json"),
               let list = (try JSONSerialization.jsonObject(with: data) as? [String: Any])?["photos"] as? [[String: Any]] {
                for p in list {
                    func f(_ k: String, _ d: [String: Any] = p) -> Float? { (d[k] as? NSNumber)?.floatValue }
                    guard let file = p["file"] as? String, let jpeg = try zip.read(file),
                          let w = p["width"] as? Int, let h = p["height"] as? Int,
                          let fx = f("fx"), let fy = f("fy"), let cx = f("cx"), let cy = f("cy"),
                          var pose = (p["pose"] as? [NSNumber])?.map(\.floatValue), pose.count == 16 else { continue }
                    pose[13] += lift
                    if let d = p["depth"] as? [String: Any], let df = d["file"] as? String,
                       let dw = d["width"] as? Int, let dh = d["height"] as? Int,
                       let dfx = f("fx", d), let dfy = f("fy", d), let dcx = f("cx", d), let dcy = f("cy", d),
                       let depth = try zip.read(df), depth.count == dw * dh * 2 {
                        let m = simd_float4x4(SIMD4(pose[0], pose[1], pose[2], pose[3]), SIMD4(pose[4], pose[5], pose[6], pose[7]),
                                              SIMD4(pose[8], pose[9], pose[10], pose[11]), SIMD4(pose[12], pose[13], pose[14], pose[15]))
                        depthViews.append((m, depth, dw, dh, dfx, dfy, dcx, dcy))
                    }
                    let i = records.count
                    let out = String(format: "photos/%03d.jpg", i)
                    try jpeg.write(to: folder.appendingPathComponent(out))
                    var record = KeyframeRecord(file: out, timestamp: (p["time"] as? NSNumber)?.doubleValue ?? 0, width: w, height: h,
                                                fx: fx, fy: fy, cx: cx, cy: cy, cameraToWorld: pose, exposure: nil)
                    // Depth: only when it's the same view as the photo, scaled (what the photo room assumes).
                    if let d = p["depth"] as? [String: Any], let df = d["file"] as? String,
                       let dw = d["width"] as? Int, let dh = d["height"] as? Int, let dfx = f("fx", d),
                       abs(dfx - fx * Float(dw) / Float(w)) < fx * Float(dw) / Float(w) * 0.05,
                       let depth = try zip.read(df), depth.count == dw * dh * 2 {
                        let dout = String(format: "photos/%03d.depth", i)
                        try depth.write(to: folder.appendingPathComponent(dout))
                        record.depthFile = dout
                        record.depthWidth = dw
                        record.depthHeight = dh
                    }
                    records.append(record)
                }
            }
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
            try encoder.encode(records).write(to: folder.appendingPathComponent("keyframes.json"))

            // Mesh: the package's, else fused from its depth images (Android packages have only points, photos and
            // depth), so the photo room can be painted. The package's room.glb is kept for when neither works.
            var mesh: ScanMesh?
            if let data = try zip.read("mesh.ply") {
                let ply = try Ply(data)
                if !ply.faces.isEmpty { mesh = ScanMesh(positions: ply.vertices.map { $0.position + up }, triangles: ply.faces) }
            }
            if mesh == nil, !depthViews.isEmpty { mesh = fuse(depthViews) }
            try mesh?.write(to: folder.appendingPathComponent(ScanMesh.fileName))
            if let glb = try zip.read("room.glb") { try glb.write(to: folder.appendingPathComponent(GLBScene.fileName)) }
            try JSONSerialization.data(withJSONObject: ["y": lift]).write(to: folder.appendingPathComponent(GLBScene.liftFileName))

            // iOS extras.
            if let map = try zip.read("ios/worldmap.arexperience") { try map.write(to: folder.appendingPathComponent("worldmap.arexperience")) }
            if let objects = try zip.read("ios/objects.json") { try objects.write(to: folder.appendingPathComponent("objects.json")) }
            if let room = try zip.read("ios/room.json") { try room.write(to: scan.dataURL) }
            if let model = try zip.read("ios/model.usdz") {
                try model.write(to: modelURL)
            } else {
                // The scan list goes by the .usdz files: an empty model for scans without RoomPlan's.
                _ = SCNScene().write(to: modelURL, options: nil, delegate: nil, progressHandler: nil)
            }
            if let thumb = try zip.read("thumb.jpg") { try thumb.write(to: scan.thumbnailURL) }

            let meta = CaptureMeta(pointCount: points.count, keyframeCount: records.count, voxelSize: LiveCloud.voxel)
            try encoder.encode(meta).write(to: folder.appendingPathComponent("meta.json"))
            if let title = manifest["name"] as? String, !title.isEmpty { scan = ScanStore.rename(scan, to: title, touch: false) }
            return scan
        } catch {
            ScanStore.delete(scan)
            throw error
        }
    }
}

extension Rmsc {
    /// A mesh from depth images and their camera poses: each depth pixel is fused into a 4 cm TSDF, like the live
    /// scan, and the surface is extracted. Depth is millimetres (0 = none), the camera looks along -Z with +Y up.
    static func fuse(_ views: [(pose: simd_float4x4, depth: Data, w: Int, h: Int, fx: Float, fy: Float, cx: Float, cy: Float)]) -> ScanMesh? {
        let tsdf = Tsdf(capacity: 1_200_000, voxel: 0.04, truncation: 0.08, carve: 1.5)
        tsdf.minW = 1
        for v in views {
            let origin = SIMD3(v.pose.columns.3.x, v.pose.columns.3.y, v.pose.columns.3.z)
            // About one ray per 4 cm at room distance: every pixel of small depth images, every second of larger ones.
            let step = max(1, v.w / 128)
            v.depth.withUnsafeBytes { raw in
                for y in stride(from: 0, to: v.h, by: step) {
                    for x in stride(from: 0, to: v.w, by: step) {
                        let mm = UInt16(littleEndian: raw.loadUnaligned(fromByteOffset: (y * v.w + x) * 2, as: UInt16.self))
                        guard mm > 200, mm < 5000 else { continue }
                        let d = Float(mm) / 1000
                        let cam = SIMD4<Float>((Float(x) + 0.5 - v.cx) * d / v.fx, (v.cy - (Float(y) + 0.5)) * d / v.fy, -d, 1)
                        let w = v.pose * cam
                        tsdf.integrate(origin: origin, point: SIMD3(w.x, w.y, w.z))
                    }
                }
            }
        }
        let out = Tsdf.MeshScratch()
        guard tsdf.extractMesh(near: .zero, radius: 10_000, solidWeight: 20, into: out) > 0 else { return nil }
        return ScanMesh(positions: out.positions, triangles: out.triangles)
    }
}

/// Binary little-endian PLY, as .rmsc uses it: vertices with x y z (float or double) and optional red green blue
/// (uchar), and optional faces as a list of vertex indices (triangles; larger polygons are fanned).
struct Ply {
    struct Vertex { var position: SIMD3<Float>; var color: SIMD3<UInt8> }
    var vertices: [Vertex] = []
    var faces: [UInt32] = []

    enum Failure: LocalizedError {
        case bad(String)
        var errorDescription: String? { if case .bad(let s) = self { return "Couldn't read a PLY file in the package (\(s))." }; return nil }
    }

    static func header(vertices: Int, faces: Int?) -> String {
        var h = "ply\nformat binary_little_endian 1.0\ncomment RoomScape\nelement vertex \(vertices)\n"
        h += "property float x\nproperty float y\nproperty float z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\n"
        if let faces { h += "element face \(faces)\nproperty list uchar int vertex_indices\n" }
        return h + "end_header\n"
    }

    private static func size(_ type: String) -> Int? {
        switch type {
        case "char", "uchar", "int8", "uint8": return 1
        case "short", "ushort", "int16", "uint16": return 2
        case "int", "uint", "float", "int32", "uint32", "float32": return 4
        case "double", "float64": return 8
        default: return nil
        }
    }

    private static func value(_ raw: UnsafeRawBufferPointer, _ at: Int, _ type: String) -> Double {
        switch type {
        case "char", "int8": return Double(raw.loadUnaligned(fromByteOffset: at, as: Int8.self))
        case "uchar", "uint8": return Double(raw.loadUnaligned(fromByteOffset: at, as: UInt8.self))
        case "short", "int16": return Double(Int16(littleEndian: raw.loadUnaligned(fromByteOffset: at, as: Int16.self)))
        case "ushort", "uint16": return Double(UInt16(littleEndian: raw.loadUnaligned(fromByteOffset: at, as: UInt16.self)))
        case "int", "int32": return Double(Int32(littleEndian: raw.loadUnaligned(fromByteOffset: at, as: Int32.self)))
        case "uint", "uint32": return Double(UInt32(littleEndian: raw.loadUnaligned(fromByteOffset: at, as: UInt32.self)))
        case "float", "float32": return Double(Float(bitPattern: UInt32(littleEndian: raw.loadUnaligned(fromByteOffset: at, as: UInt32.self))))
        default: return Double(bitPattern: UInt64(littleEndian: raw.loadUnaligned(fromByteOffset: at, as: UInt64.self)))
        }
    }

    init(_ data: Data) throws {
        guard let end = data.range(of: Data("end_header\n".utf8)) else { throw Failure.bad("no header") }
        let header = String(decoding: data[data.startIndex..<end.lowerBound], as: UTF8.self)
        guard header.contains("binary_little_endian") else { throw Failure.bad("not binary little-endian") }
        struct Element { var name: String; var count: Int; var props: [(name: String, type: String, listCount: String?)] = [] }
        var elements: [Element] = []
        for line in header.split(separator: "\n") {
            let w = line.split(separator: " ").map(String.init)
            if w.first == "element", w.count == 3, let n = Int(w[2]) { elements.append(Element(name: w[1], count: n)) }
            if w.first == "property", !elements.isEmpty {
                if w.count == 5, w[1] == "list" { elements[elements.count - 1].props.append((w[4], w[3], w[2])) }
                else if w.count == 3 { elements[elements.count - 1].props.append((w[2], w[1], nil)) }
            }
        }
        var p = end.upperBound - data.startIndex
        var vertices: [Vertex] = [], faces: [UInt32] = []
        try data.withUnsafeBytes { raw in
            for e in elements {
                for _ in 0..<e.count {
                    var pos = SIMD3<Float>.zero, col = SIMD3<UInt8>(180, 180, 180)
                    var polygon: [UInt32] = []
                    for prop in e.props {
                        if let countType = prop.listCount {
                            guard let cs = Ply.size(countType), let s = Ply.size(prop.type), p + cs <= raw.count else { throw Failure.bad("list") }
                            let n = Int(Ply.value(raw, p, countType)); p += cs
                            guard p + n * s <= raw.count else { throw Failure.bad("truncated") }
                            for k in 0..<n { polygon.append(UInt32(Ply.value(raw, p + k * s, prop.type))) }
                            p += n * s
                        } else {
                            guard let s = Ply.size(prop.type), p + s <= raw.count else { throw Failure.bad("truncated") }
                            let v = Ply.value(raw, p, prop.type); p += s
                            switch prop.name {
                            case "x": pos.x = Float(v)
                            case "y": pos.y = Float(v)
                            case "z": pos.z = Float(v)
                            case "red": col.x = UInt8(clamping: Int(v))
                            case "green": col.y = UInt8(clamping: Int(v))
                            case "blue": col.z = UInt8(clamping: Int(v))
                            default: break
                            }
                        }
                    }
                    if e.name == "vertex" { vertices.append(Vertex(position: pos, color: col)) }
                    if e.name == "face", polygon.count >= 3 {
                        for k in 1..<(polygon.count - 1) { faces += [polygon[0], polygon[k], polygon[k + 1]] }
                    }
                }
            }
        }
        guard faces.allSatisfy({ Int($0) < vertices.count }) else { throw Failure.bad("face index out of range") }
        self.vertices = vertices
        self.faces = faces
    }
}

private extension Data {
    mutating func appendFloats(_ v: SIMD3<Float>) {
        for f in [v.x, v.y, v.z] { Swift.withUnsafeBytes(of: f.bitPattern.littleEndian) { append(contentsOf: $0) } }
    }
}
