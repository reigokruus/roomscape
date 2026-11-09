import Foundation
import ImageIO
import SceneKit
import simd

/// The photo room in the result view: the scan's fused mesh with each triangle painted from its photo (see
/// `PhotoLayout`). The ceiling is left out, like in the splat view, so the room can be seen into from above.
///
/// At most `maxPhotos` photos become textures, scaled to `textureWidth`, so memory stays around 100 MB at most.
enum PhotoRoom {
    static let maxPhotos = 80
    static let textureWidth = 640

    /// Builds the photo room for a scan, or nil when it has no mesh or no photos. Slow (a second or more): call off
    /// the main thread.
    static func build(_ scan: SavedScan) -> SCNNode? {
        let folder = scan.captureURL
        let started = Date()
        guard let layout = PhotoLayout.make(folder: folder, maxPhotos: maxPhotos, leaveOutCeiling: true) else { return nil }
        let mesh = layout.mesh
        var plain = layout.plain
        var vertices: [SCNVector3] = [], uvs: [CGPoint] = []
        var elements: [SCNGeometryElement] = [], materials: [SCNMaterial] = []
        func addElement(count: Int) {
            let start = Int32(vertices.count - count)
            let indices = (0..<Int32(count)).map { start + $0 }
            elements.append(SCNGeometryElement(indices: indices, primitiveType: .triangles))
        }
        for (k, triangles) in layout.groups {
            guard let image = texture(folder.appendingPathComponent(layout.photos[k].file)) else {
                plain += triangles
                continue
            }
            for t in triangles {
                for c in 0..<3 {
                    let p = mesh.positions[Int(mesh.triangles[t * 3 + c])]
                    vertices.append(SCNVector3(p))
                    let uv = layout.uv(k, p)
                    uvs.append(CGPoint(x: CGFloat(uv.x), y: CGFloat(uv.y)))
                }
            }
            addElement(count: triangles.count * 3)
            let m = SCNMaterial()
            m.diffuse.contents = image
            m.diffuse.mipFilter = .linear
            m.diffuse.wrapS = .clamp
            m.diffuse.wrapT = .clamp
            m.lightingModel = .constant
            m.isDoubleSided = true
            materials.append(m)
        }
        if !plain.isEmpty {
            for t in plain {
                for c in 0..<3 {
                    vertices.append(SCNVector3(mesh.positions[Int(mesh.triangles[t * 3 + c])]))
                    uvs.append(.zero)
                }
            }
            addElement(count: plain.count * 3)
            let m = SCNMaterial()
            m.diffuse.contents = UIColor(white: 0.55, alpha: 1)
            m.lightingModel = .constant
            m.isDoubleSided = true
            materials.append(m)
        }
        let geometry = SCNGeometry(sources: [SCNGeometrySource(vertices: vertices), SCNGeometrySource(textureCoordinates: uvs)],
                                   elements: elements)
        geometry.materials = materials
        print("photo room: \(mesh.triangles.count / 3) triangles (\(layout.leftOut) ceiling left out), "
              + "\(layout.groups.reduce(0) { $0 + $1.triangles.count }) from \(layout.groups.count) photos, "
              + "\(plain.count) uncovered, in \(Int(Date().timeIntervalSince(started) * 1000)) ms")
        let node = SCNNode(geometry: geometry)
        node.name = "photoRoom"
        return node
    }

    /// The photo scaled to `textureWidth`, decoded now so SceneKit doesn't stall on it later.
    private static func texture(_ url: URL) -> CGImage? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        let options: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                        kCGImageSourceThumbnailMaxPixelSize: textureWidth,
                                        kCGImageSourceShouldCacheImmediately: true]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }
}
