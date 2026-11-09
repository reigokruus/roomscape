import ARKit
import Foundation
import RoomPlan
import simd

/// "Improve scan" (task 6): a second pass over a saved scan, lined up by ARKit itself.
///
/// Every scan saves the phone's ARWorldMap of the room (`worldmap.arexperience` in its capture folder). Improving a
/// scan loads that map into a new AR session; once ARKit recognises the room (relocalises), the new pass is in the
/// saved scan's coordinates, so its LiDAR points and photos can simply be added. No corner taps (Android needs two).
///
/// The saved RoomPlan model (walls, doors, furniture) stays as it was: a second pass usually covers only part of the
/// room, and the user's object edits and storyboards refer to the original model.
enum WorldMapStore {
    static func url(_ scan: SavedScan) -> URL { scan.captureURL.appendingPathComponent("worldmap.arexperience") }

    static func exists(_ scan: SavedScan) -> Bool { FileManager.default.fileExists(atPath: url(scan).path) }

    static func load(_ scan: SavedScan) -> ARWorldMap? {
        guard let data = try? Data(contentsOf: url(scan)) else { return nil }
        return try? NSKeyedUnarchiver.unarchivedObject(ofClass: ARWorldMap.self, from: data)
    }

    static func save(_ data: Data, for scan: SavedScan) {
        try? FileManager.default.createDirectory(at: scan.captureURL, withIntermediateDirectories: true)
        try? data.write(to: url(scan), options: .atomic)
    }

    /// The session's current map, archived. Call while the AR session is still running.
    static func capture(from session: ARSession) async -> Data? {
        let map: ARWorldMap? = await withCheckedContinuation { continuation in
            session.getCurrentWorldMap { map, _ in continuation.resume(returning: map) }
        }
        guard let map else { return nil }
        return try? NSKeyedArchiver.archivedData(withRootObject: map, requiringSecureCoding: true)
    }
}

extension ScanStore {
    enum ImproveError: LocalizedError {
        /// Share of the pass's points that could be placed on the saved scan.
        case notLinedUp(Float)
        var errorDescription: String? {
            switch self {
            case .notLinedUp(let overlap):
                return "This pass didn't line up with the saved scan (only \(Int(overlap * 100))% of it matched), so the saved scan was left unchanged. Tap Scan again, and this time also scan some of what you scanned before, not only new parts."
            }
        }
    }

    /// Files in the capture folder that the result screen saved and that stay valid after a merge.
    /// The world map is kept too: it's in the saved scan's coordinates, so the scan can be continued again (replaced
    /// below by the pass's own map when that is in the same coordinates).
    private static let keptFiles = ["objects.json", "storyboard-objects.json", "improved.json", "worldmap.arexperience"]
    /// Caches built from the points and photos: rebuilt from the merged ones the next time the scan opens.
    private static let rebuiltFiles = ["surfels.bin", "trained.bin", "windows.json"]

    /// Adds a second pass to a saved scan: its LiDAR points fill cells the saved scan didn't have (or replace cells it
    /// saw less solidly), and its photos are added to the saved ones. `room` is the second pass's RoomPlan result,
    /// only used to check that the pass really lined up. Slow (reads every photo): call off the main thread.
    /// `alignment` maps the pass's coordinates onto the saved scan's (identity when ARKit kept the saved map's).
    static func improve(_ scan: SavedScan, with capture: CaptureSnapshot?, room: CapturedRoom?, worldMap: Data?,
                        alignment: simd_float4x4 = matrix_identity_float4x4) throws -> SavedScan {
        #if DEBUG
        // Launch with -improveDebug to keep each pass (~3 MB) for study on the Mac.
        if let room, ProcessInfo.processInfo.arguments.contains("-improveDebug") {
            ImproveLog.dump(scan: scan, room: room, capture: capture, alignment: alignment)
        }
        #endif
        // Fit the pass onto the saved scan's points, starting from the hand-off guess.
        let handoff = alignment
        var alignment = alignment
        if let capture, capture.pointCount > 0 {
            let start = Date()
            let pass = capture.points.withUnsafeBytes { raw in
                (0..<min(capture.pointCount, raw.count / MemoryLayout<LivePoint>.stride)).map { i -> SIMD3<Float> in
                    let p = raw.loadUnaligned(fromByteOffset: i * MemoryLayout<LivePoint>.stride, as: LivePoint.self)
                    return SIMD3(p.x, p.y, p.z)
                }
            }
            let fit = PassRegistration.align(pass: pass, saved: loadPoints(scan).map { SIMD3($0.x, $0.y, $0.z) }, guesses: [alignment])
            ImproveLog.write(String(format: "fitted the pass: %.0f%% of its points on the saved scan, in %.1f s", fit.overlap * 100,
                                    Date().timeIntervalSince(start)))
            if fit.overlap < PassRegistration.minOverlap { throw ImproveError.notLinedUp(fit.overlap) }
            alignment = fit.transform
        }
        if let room, let saved = loadRoom(scan) {
            ImproveLog.write("walls after fitting: \(wallDistances(room, from: saved, alignment: alignment).map { String(format: "%.2f", $0) }) m")
        }
        let capture = capture.map { ImproveAlignment.apply(alignment, to: $0) }
        let folder = scan.captureURL
        let fm = FileManager.default
        var kept: [String: Data] = [:]
        for name in keptFiles { kept[name] = try? Data(contentsOf: folder.appendingPathComponent(name)) }

        // Points: one per cell, the more solid one wins.
        let oldVoxel = CaptureStore.loadMeta(folder)?.voxelSize ?? LiveCloud.voxel
        let voxel = max(oldVoxel, capture?.voxelSize ?? LiveCloud.voxel)
        var points = loadPoints(scan)
        var cells: [SIMD3<Int32>: Int] = [:]
        cells.reserveCapacity(points.count)
        func cell(_ p: LivePoint) -> SIMD3<Int32> {
            SIMD3(Int32((p.x / voxel).rounded(.down)), Int32((p.y / voxel).rounded(.down)), Int32((p.z / voxel).rounded(.down)))
        }
        for (i, p) in points.enumerated() { cells[cell(p)] = i }
        var added = 0, replaced = 0
        if let capture {
            capture.points.withUnsafeBytes { raw in
                let stride = MemoryLayout<LivePoint>.stride
                for i in 0..<min(capture.pointCount, raw.count / stride) {
                    let p = raw.loadUnaligned(fromByteOffset: i * stride, as: LivePoint.self)
                    let key = cell(p)
                    if let j = cells[key] {
                        if p.rgba >> 24 > points[j].rgba >> 24 { points[j] = p; replaced += 1 }
                    } else {
                        cells[key] = points.count
                        points.append(p)
                        added += 1
                    }
                }
            }
        }

        // Photos: the saved ones, then the new pass's.
        var keyframes: [Keyframe] = []
        if let data = try? Data(contentsOf: folder.appendingPathComponent("keyframes.json")),
           let records = try? JSONDecoder().decode([KeyframeRecord].self, from: data) {
            for r in records where r.cameraToWorld.count == 16 {
                guard let jpeg = try? Data(contentsOf: folder.appendingPathComponent(r.file)) else { continue }
                let m = r.cameraToWorld
                keyframes.append(Keyframe(
                    timestamp: r.timestamp,
                    cameraToWorld: simd_float4x4(SIMD4(m[0], m[1], m[2], m[3]), SIMD4(m[4], m[5], m[6], m[7]),
                                                 SIMD4(m[8], m[9], m[10], m[11]), SIMD4(m[12], m[13], m[14], m[15])),
                    fx: r.fx, fy: r.fy, cx: r.cx, cy: r.cy, width: r.width, height: r.height, jpeg: jpeg, exposure: r.exposure))
                // Keep the photo's depth, for the photo room's hidden-surface check.
                if let file = r.depthFile, let w = r.depthWidth, let h = r.depthHeight,
                   let raw = try? Data(contentsOf: folder.appendingPathComponent(file)), raw.count == w * h * 2 {
                    keyframes[keyframes.count - 1].depth = raw.withUnsafeBytes { Array($0.bindMemory(to: UInt16.self)) }
                    keyframes[keyframes.count - 1].depthWidth = w
                    keyframes[keyframes.count - 1].depthHeight = h
                }
            }
        }
        let oldPhotos = keyframes.count
        keyframes += capture?.keyframes ?? []

        // Mesh: the saved one, plus the pass's surfaces where the saved scan had none.
        let oldMesh = ScanMesh.read(from: folder.appendingPathComponent(ScanMesh.fileName))
        let mesh = ScanMesh.merge(old: oldMesh, new: capture?.mesh)
        let merged = CaptureSnapshot(points: points.withUnsafeBytes { Data($0) }, pointCount: points.count,
                                     keyframes: keyframes, voxelSize: voxel, mesh: mesh)
        try CaptureStore.write(merged, to: folder)
        for (name, data) in kept { try? data.write(to: folder.appendingPathComponent(name), options: .atomic) }
        for name in rebuiltFiles { try? fm.removeItem(at: folder.appendingPathComponent(name)) }
        ImproveHistory.record(Date(), for: scan)
        invalidateThumbnail(scan) // the photo room grew: show the new one on Home
        SyncStore.touch(scan)
        // The pass's own map is only in the saved scan's coordinates when nothing had to be mapped back.
        // The pass's map was moved onto the saved scan by the hand-off guess (see `ScanController.finish`): it replaces
        // the saved map (and so covers the new areas too) when the fit agrees with that guess within 10 cm and 3°.
        // Otherwise the saved map stays (kept above), so the scan can always be continued again.
        if let worldMap, ImproveAlignment.close(alignment, handoff) {
            WorldMapStore.save(worldMap, for: scan)
            ImproveLog.write("saved the pass's world map")
        } else if worldMap != nil {
            ImproveLog.write("kept the saved world map (the fit moved the pass more than the hand-off guess)")
        }
        ImproveLog.write("merged: \(added) points added, \(replaced) made more solid (\(points.count) now); "
                         + "\(keyframes.count - oldPhotos) photos added (\(keyframes.count) now)")
        return scan
    }

    /// Median distance from the new pass's walls to the saved room's nearest wall plane (same direction, overlapping),
    /// in metres. Small when the second pass lined up.
    static func wallOffset(_ room: CapturedRoom, from saved: CapturedRoom,
                           alignment: simd_float4x4 = matrix_identity_float4x4) -> Float {
        var distances = wallDistances(room, from: saved, alignment: alignment)
        guard !distances.isEmpty else { return .infinity }
        distances.sort()
        return distances[distances.count / 2]
    }

    /// For each of the pass's walls, the distance to the nearest parallel, overlapping saved wall (infinity if none).
    static func wallDistances(_ room: CapturedRoom, from saved: CapturedRoom,
                              alignment: simd_float4x4 = matrix_identity_float4x4) -> [Float] {
        func frame(_ s: CapturedRoom.Surface, _ m: simd_float4x4 = matrix_identity_float4x4) -> (c: SIMD3<Float>, n: SIMD3<Float>, r: SIMD3<Float>, half: Float) {
            let t = m * s.transform
            return (SIMD3(t.columns.3.x, t.columns.3.y, t.columns.3.z), simd_normalize(SIMD3(t.columns.2.x, t.columns.2.y, t.columns.2.z)),
                    simd_normalize(SIMD3(t.columns.0.x, t.columns.0.y, t.columns.0.z)), s.dimensions.x / 2)
        }
        let old = saved.walls.map { frame($0) }
        var distances: [Float] = []
        for wall in room.walls.map({ frame($0, alignment) }) {
            var best = Float.infinity
            for o in old where abs(simd_dot(wall.n, o.n)) > 0.9 {
                let d = wall.c - o.c
                guard abs(simd_dot(d, o.r)) < o.half + wall.half + 0.5 else { continue }
                best = min(best, abs(simd_dot(d, o.n)))
            }
            distances.append(best)
        }
        return distances
    }
}

/// When a scan was improved (`improved.json` in its capture folder), shown under its title.
enum ImproveHistory {
    static func url(_ scan: SavedScan) -> URL { scan.captureURL.appendingPathComponent("improved.json") }

    static func dates(_ scan: SavedScan) -> [Date] {
        if let data = try? Data(contentsOf: url(scan)), let dates = try? JSONDecoder().decode([Date].self, from: data) {
            return dates
        }
        // Improved before this list existed: an improve rewrites the capture well after the scan was saved. Measured
        // against the model file, written at the same moment as the capture on save: the scan's date comes from its
        // name, so a scan copied or restored onto the phone (every file newly dated) would look improved against it.
        func modified(_ url: URL) -> Date? {
            (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate
        }
        if let written = modified(scan.captureURL.appendingPathComponent("meta.json")),
           let saved = modified(scan.modelURL), written.timeIntervalSince(saved) > 30 {
            return [written]
        }
        return []
    }

    static func record(_ date: Date, for scan: SavedScan) {
        var dates = (try? Data(contentsOf: url(scan))).flatMap { try? JSONDecoder().decode([Date].self, from: $0) } ?? []
        dates.append(date)
        try? JSONEncoder().encode(dates).write(to: url(scan), options: .atomic)
    }
}

/// Rigid moves between the improving pass and the saved scan.
enum ImproveAlignment {
    /// The turn about the vertical and the shift that take pose `a` onto pose `b` (ARKit keeps gravity fixed, so only
    /// the heading can differ between two sessions).
    static func yawAndShift(from a: simd_float4x4, to b: simd_float4x4) -> simd_float4x4 {
        func heading(_ m: simd_float4x4) -> Float { atan2(-m.columns.2.x, -m.columns.2.z) } // camera looks down -z
        let yaw = heading(b) - heading(a)
        var t = simd_float4x4(simd_quatf(angle: yaw, axis: SIMD3(0, 1, 0)))
        let pa = SIMD4(a.columns.3.x, a.columns.3.y, a.columns.3.z, 1)
        let rotated = t * pa
        t.columns.3 = SIMD4(b.columns.3.x - rotated.x, b.columns.3.y - rotated.y, b.columns.3.z - rotated.z, 1)
        return t
    }

    /// The pass's points and photo poses in the saved scan's coordinates.
    /// Within 10 cm and 3° of each other.
    static func close(_ a: simd_float4x4, _ b: simd_float4x4) -> Bool {
        let d = a.inverse * b
        let shift = simd_length(SIMD3(d.columns.3.x, d.columns.3.y, d.columns.3.z))
        let cosAngle = (d.columns.0.x + d.columns.1.y + d.columns.2.z - 1) / 2
        return shift < 0.1 && cosAngle > cos(3 * Float.pi / 180)
    }

    static func apply(_ m: simd_float4x4, to capture: CaptureSnapshot) -> CaptureSnapshot {
        guard m != matrix_identity_float4x4 else { return capture }
        var out = capture
        var points = capture.points
        points.withUnsafeMutableBytes { raw in
            let stride = MemoryLayout<LivePoint>.stride
            for i in 0..<min(capture.pointCount, raw.count / stride) {
                var p = raw.loadUnaligned(fromByteOffset: i * stride, as: LivePoint.self)
                let q = m * SIMD4(p.x, p.y, p.z, 1)
                p.x = q.x; p.y = q.y; p.z = q.z
                raw.storeBytes(of: p, toByteOffset: i * stride, as: LivePoint.self)
            }
        }
        out.points = points
        out.keyframes = capture.keyframes.map { var k = $0; k.cameraToWorld = m * k.cameraToWorld; return k }
        out.mesh = capture.mesh.map { mesh in
            var mesh = mesh
            mesh.positions = mesh.positions.map { simd_make_float3(m * SIMD4($0, 1)) }
            return mesh
        }
        return out
    }
}

/// What Improve scan did, appended to Documents/improve-log.txt (to see on the Mac what happened on the phone).
enum ImproveLog {
    static func pose(_ m: simd_float4x4) -> String {
        String(format: "at (%.2f, %.2f, %.2f) heading %.0f°", m.columns.3.x, m.columns.3.y, m.columns.3.z,
               atan2(-m.columns.2.x, -m.columns.2.z) * 180 / .pi)
    }

    /// Keeps the pass as captured (before any merge) in Documents/improve-debug/<scan>-<time>/, so a pass that didn't
    /// line up can be studied on the Mac: room.json, points.bin (LivePoint), poses.json, alignment.json.
    static func dump(scan: SavedScan, room: CapturedRoom, capture: CaptureSnapshot?, alignment: simd_float4x4) {
        let stamp = Date().formatted(.iso8601.dateSeparator(.dash).timeSeparator(.omitted).time(includingFractionalSeconds: false))
        let folder = ScanStore.directory.appendingPathComponent("improve-debug/\(scan.name)-\(stamp)")
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try? JSONEncoder().encode(room).write(to: folder.appendingPathComponent("room.json"))
        if let capture {
            try? capture.points.write(to: folder.appendingPathComponent("points.bin"))
            let poses = capture.keyframes.map { k in
                [k.cameraToWorld.columns.0, k.cameraToWorld.columns.1, k.cameraToWorld.columns.2, k.cameraToWorld.columns.3]
                    .flatMap { [$0.x, $0.y, $0.z, $0.w] }
            }
            try? JSONEncoder().encode(poses).write(to: folder.appendingPathComponent("poses.json"))
        }
        let a = [alignment.columns.0, alignment.columns.1, alignment.columns.2, alignment.columns.3].flatMap { [$0.x, $0.y, $0.z, $0.w] }
        try? JSONEncoder().encode(a).write(to: folder.appendingPathComponent("alignment.json"))
        write("kept the pass in improve-debug/\(folder.lastPathComponent)")
    }

    static func write(_ line: String) {
        print("improve: \(line)")
        let url = ScanStore.directory.appendingPathComponent("improve-log.txt")
        // Keep the newest ~100 KB.
        if let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize, size > 200_000,
           let old = try? String(contentsOf: url, encoding: .utf8) {
            try? String(old.suffix(100_000)).write(to: url, atomically: true, encoding: .utf8)
        }
        let text = "\(Date().formatted(date: .omitted, time: .standard)) \(line)\n"
        if let handle = try? FileHandle(forWritingTo: url) {
            handle.seekToEndOfFile()
            handle.write(Data(text.utf8))
            try? handle.close()
        } else {
            try? text.write(to: url, atomically: true, encoding: .utf8)
        }
    }
}
