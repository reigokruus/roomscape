import Foundation
import SceneKit
import UIKit

/// Summary of what was captured next to RoomPlan's model: `<scan>.capture/meta.json`.
///
/// Folder layout:
/// - `meta.json`: this summary.
/// - `points.bin`: `pointCount` × 16 bytes, little-endian: float32 x, y, z (ARKit world, metres, y up),
///   then uint8 r, g, b and how solid the spot was (1…255, 255 = solid).
/// - `keyframes.json`: one `KeyframeRecord` per photo.
/// - `photos/NNN.jpg`: the photos, in the camera sensor's landscape orientation.
/// - `photos/NNN.depth`: LiDAR depth for that photo (uint16 millimetres, see `KeyframeRecord.depthWidth`), if any.
/// - `mesh.bin`: the surface mesh fused from LiDAR depth while scanning (`ScanMesh`), if any.
struct CaptureMeta: Codable {
    var version = 1
    var pointCount: Int
    var keyframeCount: Int
    var voxelSize: Float
}

/// One photo's camera: projection is `u = (fx * xc / d + cx)`, `v = (cy - fy * yc / d)` in pixels,
/// with camera coordinates from `inverse(cameraToWorld)` and `d = -zc`.
struct KeyframeRecord: Codable {
    var file: String
    var timestamp: TimeInterval
    var width: Int, height: Int
    var fx: Float, fy: Float, cx: Float, cy: Float
    /// Camera-to-world transform, 16 floats, column-major.
    var cameraToWorld: [Float]
    /// Missing in scans saved before 7 Nov 2026 13:30.
    var exposure: PhotoExposure?
    /// The photo's depth file and its size (same view as the photo); missing in scans saved before 9 Nov 2026.
    var depthFile: String? = nil
    var depthWidth: Int? = nil, depthHeight: Int? = nil
}

enum CaptureStore {
    /// Writes into a `.saving` folder first and swaps it in at the end, so an interrupted save never leaves half a capture.
    static func write(_ capture: CaptureSnapshot, to folder: URL) throws {
        let fm = FileManager.default
        let temp = folder.deletingLastPathComponent().appendingPathComponent(folder.lastPathComponent + ".saving")
        try? fm.removeItem(at: temp)
        try fm.createDirectory(at: temp.appendingPathComponent("photos"), withIntermediateDirectories: true)

        try capture.points.write(to: temp.appendingPathComponent("points.bin"))
        var records: [KeyframeRecord] = []
        for (i, keyframe) in capture.keyframes.enumerated() {
            let file = String(format: "photos/%03d.jpg", i)
            try keyframe.jpeg.write(to: temp.appendingPathComponent(file))
            var depthFile: String?
            if let depth = keyframe.depth, depth.count == keyframe.depthWidth * keyframe.depthHeight, !depth.isEmpty {
                depthFile = String(format: "photos/%03d.depth", i)
                try depth.withUnsafeBytes { Data($0) }.write(to: temp.appendingPathComponent(depthFile!))
            }
            let m = keyframe.cameraToWorld
            records.append(KeyframeRecord(
                file: file, timestamp: keyframe.timestamp, width: keyframe.width, height: keyframe.height,
                fx: keyframe.fx, fy: keyframe.fy, cx: keyframe.cx, cy: keyframe.cy,
                cameraToWorld: [m.columns.0, m.columns.1, m.columns.2, m.columns.3].flatMap { [$0.x, $0.y, $0.z, $0.w] },
                exposure: keyframe.exposure, depthFile: depthFile,
                depthWidth: depthFile == nil ? nil : keyframe.depthWidth,
                depthHeight: depthFile == nil ? nil : keyframe.depthHeight))
        }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try encoder.encode(records).write(to: temp.appendingPathComponent("keyframes.json"))
        if let mesh = capture.mesh { try mesh.write(to: temp.appendingPathComponent(ScanMesh.fileName)) }
        let meta = CaptureMeta(pointCount: capture.pointCount, keyframeCount: records.count, voxelSize: capture.voxelSize)
        try encoder.encode(meta).write(to: temp.appendingPathComponent("meta.json"))

        try? fm.removeItem(at: folder)
        try fm.moveItem(at: temp, to: folder)
    }

    static func loadMeta(_ folder: URL) -> CaptureMeta? {
        guard let data = try? Data(contentsOf: folder.appendingPathComponent("meta.json")) else { return nil }
        return try? JSONDecoder().decode(CaptureMeta.self, from: data)
    }
}

/// A small picture of a scan for the home screen: the 3D view the result page opens with (handoff: thumbnails).
enum ScanThumbnail {
    static let size = CGSize(width: 360, height: 270)

    static func render(modelURL: URL) -> Data? {
        guard let scene = try? SCNScene(url: modelURL, options: nil),
              let device = MTLCreateSystemDefaultDevice() else { return nil }
        ResultView.colorize(scene.rootNode)
        scene.background.contents = UIColor.black
        let framing = RoomSceneView.framing(of: scene)
        let camera = SCNNode()
        camera.camera = RoomSceneView.makeCamera()
        camera.position = RoomSceneView.orbitPosition(framing)
        camera.look(at: framing.center)
        scene.rootNode.addChildNode(camera)
        RoomSceneView.addAmbientLight(to: scene)

        let renderer = SCNRenderer(device: device, options: nil)
        renderer.scene = scene
        renderer.autoenablesDefaultLighting = true
        renderer.pointOfView = camera
        return renderer.snapshot(atTime: 0, with: size, antialiasingMode: .multisampling4X)
            .jpegData(compressionQuality: 0.8)
    }
}
