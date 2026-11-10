import Foundation
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

    /// `old` plus the triangles of `new` (already in `old`'s coordinates) whose centre isn't within ~4–8 cm of `old`'s
    /// surface, i.e. what a Continue-scan pass added. Either may be missing.
    static func merge(old: ScanMesh?, new: ScanMesh?) -> ScanMesh? {
        guard let new, !new.triangles.isEmpty else { return old }
        guard let old, !old.triangles.isEmpty else { return new }
        let cell: Float = 0.04
        func key(_ p: SIMD3<Float>) -> SIMD3<Int32> { SIMD3<Int32>((p / cell).rounded(.down)) }
        // Only corners of triangles that are still there count (rk 9 Nov: Scan area again cut the old triangles out
        // but left their corners, which then kept the new surface out: a hole).
        var used = [Bool](repeating: false, count: old.positions.count)
        for i in old.triangles { used[Int(i)] = true }
        var covered = Set<SIMD3<Int32>>()
        for (n, p) in old.positions.enumerated() where used[n] {
            let k = key(p)
            for dx: Int32 in -1...1 { for dy: Int32 in -1...1 { for dz: Int32 in -1...1 { covered.insert(k &+ SIMD3(dx, dy, dz)) } } }
        }
        var out = old
        var remap = [Int32](repeating: -1, count: new.positions.count)
        for t in 0..<(new.triangles.count / 3) {
            let i = (0..<3).map { Int(new.triangles[t * 3 + $0]) }
            let centre = (new.positions[i[0]] + new.positions[i[1]] + new.positions[i[2]]) / 3
            if covered.contains(key(centre)) { continue }
            for v in i {
                if remap[v] < 0 {
                    remap[v] = Int32(out.positions.count)
                    out.positions.append(new.positions[v])
                }
                out.triangles.append(UInt32(remap[v]))
            }
        }
        return out
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

/// Which photo paints each triangle of a scan's fused mesh (port of Android's `PhotoRoom.java`): the best photo that
/// saw it, i.e. close, head-on, every corner inside the photo, and not hidden behind something nearer in that photo's
/// depth. Shared by the photo room in the result view (`PhotoRoom`) and the .glb export (`PhotoRoomExport`).
/// Foundation and simd only.
enum PhotoLayout {
    struct Photo {
        var file: String
        var view: simd_float4x4
        var position: SIMD3<Float>
        var fx, fy, cx, cy, width, height: Float
        var depth: [UInt16]?
        var dw = 0, dh = 0
    }

    struct Layout {
        var mesh: ScanMesh
        var photos: [Photo]
        /// Triangle indices (into `mesh.triangles` / 3) per photo, by photo index, sorted.
        var groups: [(photo: Int, triangles: [Int])]
        /// Triangles no photo covers.
        var plain: [Int]
        /// Triangles left out (the ceiling, when asked).
        var leftOut: Int

        /// Where `p` lands in a photo, 0…1 with (0, 0) at the photo's top left.
        func uv(_ photo: Int, _ p: SIMD3<Float>) -> SIMD2<Float> {
            let ph = photos[photo]
            guard let px = PhotoLayout.project(ph, p) else { return .zero }
            return SIMD2(px.x / ph.width, px.y / ph.height)
        }
    }

    /// The layout for the capture folder of a scan, or nil without a mesh or photos. Uses at most `maxPhotos` photos.
    /// Slow (around half a second for a room): call off the main thread.
    static func make(folder: URL, maxPhotos: Int, leaveOutCeiling: Bool) -> Layout? {
        guard let mesh = ScanMesh.read(from: folder.appendingPathComponent(ScanMesh.fileName)), !mesh.triangles.isEmpty,
              let data = try? Data(contentsOf: folder.appendingPathComponent("keyframes.json")),
              let records = try? JSONDecoder().decode([KeyframeRecord].self, from: data) else { return nil }
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
        let ceiling = leaveOutCeiling ? ceilingTriangles(mesh) : [Bool](repeating: false, count: triangleCount)
        // First pass over every photo; when more than `maxPhotos` are used, keep the most used ones and pick again.
        var pick = choose(mesh, photos, allowed: nil)
        var uses = [Int](repeating: 0, count: photos.count)
        for k in pick where k >= 0 { uses[k] += 1 }
        if uses.filter({ $0 > 0 }).count > maxPhotos {
            let keep = Set(uses.indices.sorted { uses[$0] > uses[$1] }.prefix(maxPhotos))
            pick = choose(mesh, photos, allowed: keep)
        }
        var groups: [Int: [Int]] = [:]
        var plain: [Int] = []
        var leftOut = 0
        for t in 0..<triangleCount {
            if ceiling[t] { leftOut += 1; continue }
            if pick[t] >= 0 { groups[pick[t], default: []].append(t) } else { plain.append(t) }
        }
        return Layout(mesh: mesh, photos: photos, groups: groups.sorted { $0.key < $1.key }.map { ($0.key, $0.value) },
                      plain: plain, leftOut: leftOut)
    }

    /// The ceiling (flat, facing down or up, within 30 cm of the mesh's top), left out like in the splat view so the
    /// room can be seen into from above.
    static func ceilingTriangles(_ mesh: ScanMesh) -> [Bool] {
        let ys = mesh.positions.map(\.y).sorted()
        guard let top = ys.isEmpty ? nil : ys[min(ys.count - 1, ys.count * 995 / 1000)] else { return [] }
        return (0..<(mesh.triangles.count / 3)).map { t in
            let a = mesh.positions[Int(mesh.triangles[t * 3])]
            let b = mesh.positions[Int(mesh.triangles[t * 3 + 1])]
            let c = mesh.positions[Int(mesh.triangles[t * 3 + 2])]
            let n = simd_normalize(simd_cross(b - a, c - a))
            return (a.y + b.y + c.y) / 3 > top - 0.3 && abs(n.y) > 0.7
        }
    }

    /// The best photo for every triangle (-1 = none): close and head-on (score cos² / distance), every corner inside
    /// the photo, and the centre not hidden behind something nearer in that photo's depth.
    static func choose(_ mesh: ScanMesh, _ photos: [Photo], allowed: Set<Int>?) -> [Int] {
        let triangleCount = mesh.triangles.count / 3
        var pick = [Int](repeating: -1, count: triangleCount)
        let candidates = allowed.map { Array($0).sorted() } ?? Array(photos.indices)
        for t in 0..<triangleCount {
            let a = mesh.positions[Int(mesh.triangles[t * 3])]
            let b = mesh.positions[Int(mesh.triangles[t * 3 + 1])]
            let c = mesh.positions[Int(mesh.triangles[t * 3 + 2])]
            pick[t] = best(a, b, c, photos, among: candidates).photo
        }
        return pick
    }

    /// The best of `among` (photo indices) for one triangle, and its score; (-1, `beat`) when none beats `beat`.
    /// Shared by the photo room and the live scan preview (LivePreview), so both pick the same photo.
    static func best(_ a: SIMD3<Float>, _ b: SIMD3<Float>, _ c: SIMD3<Float>, _ photos: [Photo], among: [Int],
                     beat: Float = 0) -> (photo: Int, score: Float) {
        let centre = (a + b + c) / 3
        let cross = simd_cross(b - a, c - a)
        let length = simd_length(cross)
        guard length > 1e-9 else { return (-1, beat) }
        let normal = cross / length
        var best = -1
        var bestScore = beat
        for k in among {
            let photo = photos[k]
            let toCamera = photo.position - centre
            let distance = simd_length(toCamera)
            if distance < 0.15 || distance > 4.5 { continue }
            let cosA = abs(simd_dot(normal, toCamera)) / distance
            if cosA < 0.25 { continue }
            let score = cosA * cosA / distance
            if score <= bestScore { continue }
            // Not hidden anywhere on the triangle: the centre and the corners pulled slightly in (rk 9 Nov: the photo of
            // a 3 cm bar was painted onto the wall behind it, as splatters, when only the centre was checked).
            guard inside(photo, a), inside(photo, b), inside(photo, c), !occluded(photo, centre) else { continue }
            if checkCorners, occluded(photo, centre + (a - centre) * 0.8) || occluded(photo, centre + (b - centre) * 0.8)
                || occluded(photo, centre + (c - centre) * 0.8) { continue }
            best = k
            bestScore = score
        }
        return (best, bestScore)
    }

    /// Also check three inner corners for occlusion, not just the centre (harness compares both).
    nonisolated(unsafe) static var checkCorners = true

    /// Pixel position of `p` in the photo (row 0 at the top), or nil when it's behind the camera.
    static func project(_ photo: Photo, _ p: SIMD3<Float>) -> SIMD2<Float>? {
        let c = photo.view * SIMD4(p, 1)
        let d = -c.z
        guard d >= 0.1 else { return nil }
        return SIMD2(photo.fx * c.x / d + photo.cx, photo.cy - photo.fy * c.y / d)
    }

    static func inside(_ photo: Photo, _ p: SIMD3<Float>) -> Bool {
        guard let uv = project(photo, p) else { return false }
        let s = uv.x / photo.width, t = uv.y / photo.height
        return s >= 0.01 && s <= 0.99 && t >= 0.01 && t <= 0.99
    }

    /// True if the photo's depth says something nearer was in front of `p`.
    static func occluded(_ photo: Photo, _ p: SIMD3<Float>) -> Bool {
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

}
