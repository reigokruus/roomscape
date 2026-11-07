import Foundation
import RoomPlan
import SceneKit
import UIKit
import simd

/// A saved scan. All files share the scan's name in the Documents folder:
/// - `<name>.usdz`: RoomPlan's 3D model; `<name>.json`: RoomPlan's walls, windows, doors and furniture
/// - `<name>.points.bin`: the LiDAR point cloud (see `ScanStore.writePoints`)
/// - `<name>.keyframes/`: `NNN.jpg` photos plus `frames.json` (pose, intrinsics, size per photo)
/// - `<name>.thumb.jpg`: thumbnail for the home screen
struct SavedScan: Identifiable, Hashable {
    let name: String
    let modelURL: URL
    let dataURL: URL
    let date: Date
    var id: String { name }

    private var base: URL { modelURL.deletingLastPathComponent() }
    var pointsURL: URL { base.appendingPathComponent(name + ".points.bin") }
    var keyframesURL: URL { base.appendingPathComponent(name + ".keyframes", isDirectory: true) }
    var thumbnailURL: URL { base.appendingPathComponent(name + ".thumb.jpg") }
    /// Splats built from the points (a cache: rebuilt from points.bin if missing).
    var surfelsURL: URL { base.appendingPathComponent(name + ".surfels.bin") }
}

/// One entry of `frames.json`. `pose` is the camera-to-world transform, 16 floats in column-major order.
struct KeyframeInfo: Codable {
    let file: String
    let pose: [Float]
    let fx: Float, fy: Float, cx: Float, cy: Float
    let width: Int, height: Int
    let timestamp: Double
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

    /// Points file: "CXP1", UInt32 point count, then 16-byte `SavedPoint` records (little-endian).
    private static let pointsMagic: UInt32 = 0x3150_5843 // "CXP1"

    static func save(_ room: CapturedRoom, cloud: CloudSnapshot? = nil) throws -> SavedScan {
        let date = Date()
        let name = "Scan_" + nameFormatter.string(from: date)
        let model = directory.appendingPathComponent(name + ".usdz")
        let data = directory.appendingPathComponent(name + ".json")
        let scan = SavedScan(name: name, modelURL: model, dataURL: data, date: date)

        // Extras first: the scan only shows up in the list once its .usdz exists, so it never appears half-saved.
        if let cloud {
            try writePoints(cloud.points, to: scan.pointsURL)
            try writeKeyframes(cloud.keyframes, to: scan.keyframesURL)
        }
        let tmpModel = directory.appendingPathComponent(name + ".saving.usdz")
        try room.export(to: tmpModel)
        try JSONEncoder().encode(room).write(to: data)
        if let thumb = renderThumbnail(modelURL: tmpModel, points: cloud?.points ?? []) {
            try? thumb.write(to: scan.thumbnailURL)
        }
        try FileManager.default.moveItem(at: tmpModel, to: model)
        return scan
    }

    static func list() -> [SavedScan] {
        let fm = FileManager.default
        guard let files = try? fm.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.creationDateKey]) else { return [] }
        return files
            .filter { $0.pathExtension == "usdz" && !$0.lastPathComponent.hasSuffix(".saving.usdz") }
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
        for url in [scan.modelURL, scan.dataURL, scan.pointsURL, scan.keyframesURL, scan.thumbnailURL, scan.surfelsURL] {
            try? FileManager.default.removeItem(at: url)
        }
    }

    // MARK: Points

    static func writePoints(_ points: [SavedPoint], to url: URL) throws {
        var data = Data(capacity: 8 + points.count * MemoryLayout<SavedPoint>.stride)
        withUnsafeBytes(of: pointsMagic.littleEndian) { data.append(contentsOf: $0) }
        withUnsafeBytes(of: UInt32(points.count).littleEndian) { data.append(contentsOf: $0) }
        points.withUnsafeBytes { data.append(contentsOf: $0) }
        try data.write(to: url, options: .atomic)
    }

    static func loadPoints(_ scan: SavedScan) -> [SavedPoint] {
        guard let data = try? Data(contentsOf: scan.pointsURL, options: .mappedIfSafe), data.count >= 8 else { return [] }
        return data.withUnsafeBytes { raw -> [SavedPoint] in
            guard raw.load(fromByteOffset: 0, as: UInt32.self) == pointsMagic else { return [] }
            let stated = Int(raw.load(fromByteOffset: 4, as: UInt32.self))
            let count = min(stated, (raw.count - 8) / MemoryLayout<SavedPoint>.stride)
            return (0..<count).map { raw.loadUnaligned(fromByteOffset: 8 + $0 * MemoryLayout<SavedPoint>.stride,
                                                       as: SavedPoint.self) }
        }
    }

    /// The scan's splats: from the cache, or built from the points, snapped to the room and cached.
    static func loadSurfels(_ scan: SavedScan, room: CapturedRoom) -> [SurfelRecord]? {
        if let cached = Surfels.read(from: scan.surfelsURL) { return cached }
        let points = loadPoints(scan)
        guard !points.isEmpty else { return nil }
        let start = Date()
        var (surfels, stats) = Surfels.build(points)
        let snapped = Surfels.snap(&surfels, to: RoomShell(room: room))
        print("splats: \(surfels.count) from \(points.count) points (\(stats.flat) flat, \(stats.curved) curved, "
              + "\(stats.lonely) lonely, \(snapped) on walls/floor) in \(Int(Date().timeIntervalSince(start) * 1000)) ms")
        Surfels.write(surfels, to: scan.surfelsURL)
        return surfels
    }

    /// Point and photo counts without loading everything, for summaries.
    static func detailCounts(_ scan: SavedScan) -> (points: Int, photos: Int) {
        let size = (try? scan.pointsURL.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        let points = max(0, (size - 8) / MemoryLayout<SavedPoint>.stride)
        let photos = loadKeyframeInfo(scan).count
        return (points, photos)
    }

    // MARK: Keyframes

    static func writeKeyframes(_ keyframes: [Keyframe], to dir: URL) throws {
        let fm = FileManager.default
        try? fm.removeItem(at: dir)
        try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        var infos: [KeyframeInfo] = []
        for (i, kf) in keyframes.enumerated() {
            let file = String(format: "%03d.jpg", i)
            try kf.jpeg.write(to: dir.appendingPathComponent(file))
            let m = kf.pose
            let pose = [m.columns.0, m.columns.1, m.columns.2, m.columns.3].flatMap { [$0.x, $0.y, $0.z, $0.w] }
            infos.append(KeyframeInfo(file: file, pose: pose, fx: kf.fx, fy: kf.fy, cx: kf.cx, cy: kf.cy,
                                      width: kf.width, height: kf.height, timestamp: kf.timestamp))
        }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted]
        try encoder.encode(infos).write(to: dir.appendingPathComponent("frames.json"))
    }

    static func loadKeyframeInfo(_ scan: SavedScan) -> [KeyframeInfo] {
        guard let data = try? Data(contentsOf: scan.keyframesURL.appendingPathComponent("frames.json")) else { return [] }
        return (try? JSONDecoder().decode([KeyframeInfo].self, from: data)) ?? []
    }

    // MARK: Thumbnail

    /// Renders the saved room (RoomPlan model plus coloured points) from the same corner view the result screen
    /// opens with, 360 px wide.
    static func renderThumbnail(modelURL: URL, points: [SavedPoint]) -> Data? {
        guard let device = MTLCreateSystemDefaultDevice(),
              let scene = try? SCNScene(url: modelURL, options: nil) else { return nil }
        ResultView.colorize(scene.rootNode)
        scene.background.contents = UIColor.black

        if !points.isEmpty {
            scene.rootNode.addChildNode(pointNode(points, maxPoints: 120_000, pointSize: 3))
        }

        let (minB, maxB) = scene.rootNode.boundingBox
        let center = SCNVector3((minB.x + maxB.x) / 2, (minB.y + maxB.y) / 2, (minB.z + maxB.z) / 2)
        let size = max(maxB.x - minB.x, max(maxB.y - minB.y, maxB.z - minB.z))
        let extent = Float(size.isFinite && size > 0 ? size : 5)
        let cam = SCNNode()
        cam.camera = SCNCamera()
        cam.camera?.zNear = 0.05
        cam.camera?.zFar = 500
        cam.camera?.fieldOfView = 55
        cam.position = SCNVector3(center.x + extent * 0.8, center.y + extent * 0.75, center.z + extent * 0.8)
        cam.look(at: center)
        scene.rootNode.addChildNode(cam)

        let renderer = SCNRenderer(device: device, options: nil)
        renderer.scene = scene
        renderer.pointOfView = cam
        renderer.autoenablesDefaultLighting = true
        let image = renderer.snapshot(atTime: 0, with: CGSize(width: 360, height: 270), antialiasingMode: .multisampling4X)
        return image.jpegData(compressionQuality: 0.8)
    }

    /// A SceneKit node drawing the points as small coloured dots (every n-th point if there are more than `maxPoints`).
    static func pointNode(_ points: [SavedPoint], maxPoints: Int, pointSize: CGFloat) -> SCNNode {
        let step = max(1, points.count / maxPoints)
        var verts: [SIMD3<Float>] = []
        var colors: [SIMD3<Float>] = []
        verts.reserveCapacity(points.count / step + 1)
        colors.reserveCapacity(points.count / step + 1)
        for i in Swift.stride(from: 0, to: points.count, by: step) {
            let p = points[i]
            verts.append(SIMD3(p.x, p.y, p.z))
            colors.append(SIMD3(Float(p.r), Float(p.g), Float(p.b)) / 255)
        }
        let vData = verts.withUnsafeBytes { Data($0) }
        let cData = colors.withUnsafeBytes { Data($0) }
        let stride = MemoryLayout<SIMD3<Float>>.stride
        let vSource = SCNGeometrySource(data: vData, semantic: .vertex, vectorCount: verts.count,
                                        usesFloatComponents: true, componentsPerVector: 3,
                                        bytesPerComponent: 4, dataOffset: 0, dataStride: stride)
        let cSource = SCNGeometrySource(data: cData, semantic: .color, vectorCount: colors.count,
                                        usesFloatComponents: true, componentsPerVector: 3,
                                        bytesPerComponent: 4, dataOffset: 0, dataStride: stride)
        let indices = Array(0..<UInt32(verts.count)).withUnsafeBytes { Data($0) }
        let element = SCNGeometryElement(data: indices, primitiveType: .point, primitiveCount: verts.count, bytesPerIndex: 4)
        element.pointSize = pointSize
        element.minimumPointScreenSpaceRadius = 1
        element.maximumPointScreenSpaceRadius = pointSize * 2
        let geometry = SCNGeometry(sources: [vSource, cSource], elements: [element])
        let material = SCNMaterial()
        material.lightingModel = .constant
        geometry.materials = [material]
        return SCNNode(geometry: geometry)
    }
}
