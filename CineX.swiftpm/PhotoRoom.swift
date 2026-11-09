import Foundation
import ImageIO
import SceneKit
import simd

/// The surface mesh fused from LiDAR depth while scanning, saved with the scan as `mesh.bin`:
/// "MSH1", uint32 vertex count, uint32 index count, then float32 x, y, z per vertex (ARKit world, metres) and uint32
/// triangle indices, little-endian.
struct ScanMesh: Sendable {
    static let fileName = "mesh.bin"
    var positions: [SIMD3<Float>]
    var triangles: [UInt32]

    func write(to url: URL) throws {
        var data = Data("MSH1".utf8)
        var counts = [UInt32(positions.count), UInt32(triangles.count)]
        data.append(Data(bytes: &counts, count: 8))
        let flat = positions.flatMap { [$0.x, $0.y, $0.z] }
        flat.withUnsafeBytes { data.append(contentsOf: $0) }
        triangles.withUnsafeBytes { data.append(contentsOf: $0) }
        try data.write(to: url)
    }

    static func read(from url: URL) -> ScanMesh? {
        guard let data = try? Data(contentsOf: url, options: .mappedIfSafe), data.count >= 12,
              data.prefix(4) == Data("MSH1".utf8) else { return nil }
        return data.withUnsafeBytes { raw -> ScanMesh? in
            let nv = Int(raw.loadUnaligned(fromByteOffset: 4, as: UInt32.self))
            let ni = Int(raw.loadUnaligned(fromByteOffset: 8, as: UInt32.self))
            guard raw.count >= 12 + nv * 12 + ni * 4, ni % 3 == 0 else { return nil }
            var positions = [SIMD3<Float>]()
            positions.reserveCapacity(nv)
            for i in 0..<nv {
                let o = 12 + i * 12
                positions.append(SIMD3(raw.loadUnaligned(fromByteOffset: o, as: Float.self),
                                       raw.loadUnaligned(fromByteOffset: o + 4, as: Float.self),
                                       raw.loadUnaligned(fromByteOffset: o + 8, as: Float.self)))
            }
            let base = 12 + nv * 12
            let triangles = (0..<ni).map { raw.loadUnaligned(fromByteOffset: base + $0 * 4, as: UInt32.self) }
            guard triangles.allSatisfy({ Int($0) < nv }) else { return nil }
            return ScanMesh(positions: positions, triangles: triangles)
        }
    }
}

/// Photographic room (port of Android's `PhotoRoom.java`): every triangle of the scan's fused mesh is painted with the
/// part of the best photo that saw it: close, head-on, and not hidden behind something else in that photo (checked
/// against the depth saved with the photo). Triangles no photo covers stay a neutral grey.
///
/// At most `maxPhotos` photos become textures, scaled to `textureWidth`, so memory stays around 100 MB at most.
enum PhotoRoom {
    static let maxPhotos = 80
    static let textureWidth = 640

    private struct Photo {
        var file: String
        var view: simd_float4x4
        var position: SIMD3<Float>
        var fx, fy, cx, cy, width, height: Float
        var depth: [UInt16]?
        var dw = 0, dh = 0
    }

    /// Builds the photo room for a scan, or nil when it has no mesh or no photos. Slow (a second or more): call off
    /// the main thread.
    static func build(_ scan: SavedScan) -> SCNNode? {
        let folder = scan.captureURL
        guard let mesh = ScanMesh.read(from: folder.appendingPathComponent(ScanMesh.fileName)), !mesh.triangles.isEmpty,
              let data = try? Data(contentsOf: folder.appendingPathComponent("keyframes.json")),
              let records = try? JSONDecoder().decode([KeyframeRecord].self, from: data) else { return nil }
        let started = Date()
        let photos: [Photo] = records.compactMap { r in
            guard r.cameraToWorld.count == 16, r.width > 0, r.height > 0 else { return nil }
            let m = r.cameraToWorld
            let pose = simd_float4x4(SIMD4(m[0], m[1], m[2], m[3]), SIMD4(m[4], m[5], m[6], m[7]),
                                     SIMD4(m[8], m[9], m[10], m[11]), SIMD4(m[12], m[13], m[14], m[15]))
            var photo = Photo(file: r.file, view: pose.inverse, position: SIMD3(m[12], m[13], m[14]),
                              fx: r.fx, fy: r.fy, cx: r.cx, cy: r.cy, width: Float(r.width), height: Float(r.height))
            if let file = r.depthFile, let w = r.depthWidth, let h = r.depthHeight,
               let raw = try? Data(contentsOf: folder.appendingPathComponent(file)), raw.count == w * h * 2 {
                photo.depth = raw.withUnsafeBytes { Array($0.bindMemory(to: UInt16.self)) }
                photo.dw = w
                photo.dh = h
            }
            return photo
        }
        guard !photos.isEmpty else { return nil }

        let triangleCount = mesh.triangles.count / 3
        // First pass over every photo; when more than `maxPhotos` are used, keep the most used ones and pick again.
        var pick = choose(mesh, photos, allowed: nil)
        var uses = [Int](repeating: 0, count: photos.count)
        for k in pick where k >= 0 { uses[k] += 1 }
        if uses.filter({ $0 > 0 }).count > maxPhotos {
            let keep = Set(uses.indices.sorted { uses[$0] > uses[$1] }.prefix(maxPhotos))
            pick = choose(mesh, photos, allowed: keep)
        }

        // One draw group (and texture) per photo, plus the uncovered triangles.
        var groups: [Int: [Int]] = [:]
        var plain: [Int] = []
        for t in 0..<triangleCount {
            if pick[t] >= 0 { groups[pick[t], default: []].append(t) } else { plain.append(t) }
        }
        var vertices: [SCNVector3] = [], uvs: [CGPoint] = []
        var elements: [SCNGeometryElement] = [], materials: [SCNMaterial] = []
        func addElement(count: Int) {
            let start = Int32(vertices.count - count)
            let indices = (0..<Int32(count)).map { start + $0 }
            elements.append(SCNGeometryElement(indices: indices, primitiveType: .triangles))
        }
        for (k, triangles) in groups.sorted(by: { $0.key < $1.key }) {
            guard let image = texture(folder.appendingPathComponent(photos[k].file)) else {
                plain += triangles
                continue
            }
            let photo = photos[k]
            for t in triangles {
                for c in 0..<3 {
                    let p = mesh.positions[Int(mesh.triangles[t * 3 + c])]
                    vertices.append(SCNVector3(p))
                    let uv = project(photo, p) ?? .zero
                    uvs.append(CGPoint(x: CGFloat(uv.x / photo.width), y: CGFloat(uv.y / photo.height)))
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
        print("photo room: \(triangleCount) triangles, \(triangleCount - plain.count) from \(groups.count) photos, "
              + "\(plain.count) uncovered, in \(Int(Date().timeIntervalSince(started) * 1000)) ms")
        let node = SCNNode(geometry: geometry)
        node.name = "photoRoom"
        return node
    }

    /// The best photo for every triangle (-1 = none): close and head-on (score cos² / distance), every corner inside
    /// the photo, and the centre not hidden behind something nearer in that photo's depth.
    private static func choose(_ mesh: ScanMesh, _ photos: [Photo], allowed: Set<Int>?) -> [Int] {
        let triangleCount = mesh.triangles.count / 3
        var pick = [Int](repeating: -1, count: triangleCount)
        for t in 0..<triangleCount {
            let a = mesh.positions[Int(mesh.triangles[t * 3])]
            let b = mesh.positions[Int(mesh.triangles[t * 3 + 1])]
            let c = mesh.positions[Int(mesh.triangles[t * 3 + 2])]
            let centre = (a + b + c) / 3
            let cross = simd_cross(b - a, c - a)
            let length = simd_length(cross)
            guard length > 1e-9 else { continue }
            let normal = cross / length
            var best = -1
            var bestScore: Float = 0
            for k in photos.indices {
                if let allowed, !allowed.contains(k) { continue }
                let photo = photos[k]
                let toCamera = photo.position - centre
                let distance = simd_length(toCamera)
                if distance < 0.15 || distance > 4.5 { continue }
                let cosA = abs(simd_dot(normal, toCamera)) / distance
                if cosA < 0.25 { continue }
                let score = cosA * cosA / distance
                if score <= bestScore { continue }
                guard inside(photo, a), inside(photo, b), inside(photo, c), !occluded(photo, centre) else { continue }
                best = k
                bestScore = score
            }
            pick[t] = best
        }
        return pick
    }

    /// Pixel position of `p` in the photo (row 0 at the top), or nil when it's behind the camera.
    private static func project(_ photo: Photo, _ p: SIMD3<Float>) -> SIMD2<Float>? {
        let c = photo.view * SIMD4(p, 1)
        let d = -c.z
        guard d >= 0.1 else { return nil }
        return SIMD2(photo.fx * c.x / d + photo.cx, photo.cy - photo.fy * c.y / d)
    }

    private static func inside(_ photo: Photo, _ p: SIMD3<Float>) -> Bool {
        guard let uv = project(photo, p) else { return false }
        let s = uv.x / photo.width, t = uv.y / photo.height
        return s >= 0.01 && s <= 0.99 && t >= 0.01 && t <= 0.99
    }

    /// True if the photo's depth says something nearer was in front of `p`.
    private static func occluded(_ photo: Photo, _ p: SIMD3<Float>) -> Bool {
        guard let depth = photo.depth else { return false }
        let c = photo.view * SIMD4(p, 1)
        let d = -c.z
        guard d >= 0.1 else { return true }
        let scale = Float(photo.dw) / photo.width
        let u = Int((photo.fx * c.x / d + photo.cx) * scale), v = Int((photo.cy - photo.fy * c.y / d) * scale)
        guard u >= 0, v >= 0, u < photo.dw, v < photo.dh else { return false }
        let mm = depth[v * photo.dw + u]
        guard mm > 0 else { return false }
        return d > Float(mm) * 0.001 + 0.1 + d * 0.04
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
