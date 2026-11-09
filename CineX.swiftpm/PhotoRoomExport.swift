import Foundation
import ImageIO
import simd
import UniformTypeIdentifiers

/// The photo room as one binary glTF 2.0 file (.glb), for viewing on a computer.
///
/// - The whole fused mesh (ceiling included), in metres, Y up (ARKit's world is gravity aligned), moved so the floor
///   is at about y = 0.
/// - One primitive per photo, with that photo as its unlit, double-sided base colour texture (the saved 1024 px JPEG,
///   or 640 px when the file would pass `maxBytes`), and TEXCOORD_0 from projecting the triangle into it. glTF's UV
///   origin is the image's top left, the same as `PhotoLayout.Layout.uv`, so V is used as is.
/// - One more primitive for the triangles no photo covers: grey, with COLOR_0 from the scan's point colours.
/// Non-indexed triangles: each primitive has its own copy of its vertices.
///
/// Foundation, simd and ImageIO only.
enum PhotoRoomExport {
    static let maxBytes = 60_000_000
    static let maxPhotos = 80

    struct Result {
        var data: Data
        var triangles: Int
        var photos: Int
        var uncovered: Int
        var textureWidth: Int
    }

    /// Builds the .glb for a scan's capture folder, or nil without a mesh or photos. Slow (a second or two): call off
    /// the main thread.
    /// `floorY`: the floor height to move to y = 0 (RoomPlan's floor in a .rmsc export); by default the mesh's own
    /// lowest surfaces.
    static func glb(folder: URL, floorY knownFloor: Float? = nil) -> Result? {
        guard let layout = PhotoLayout.make(folder: folder, maxPhotos: maxPhotos, leaveOutCeiling: false) else { return nil }
        let mesh = layout.mesh
        let ys = mesh.positions.map(\.y).sorted()
        let floorY = knownFloor ?? ys[min(ys.count - 1, ys.count * 5 / 1000)]
        let shift = SIMD3<Float>(0, -floorY, 0)

        // Photos: the saved JPEGs, smaller when everything wouldn't fit in `maxBytes`.
        var jpegs: [Int: Data] = [:]
        for (k, _) in layout.groups {
            jpegs[k] = try? Data(contentsOf: folder.appendingPathComponent(layout.photos[k].file))
        }
        let geometryBytes = mesh.triangles.count * (12 + 12)
        var textureWidth = 1024
        if jpegs.values.reduce(0, { $0 + $1.count }) + geometryBytes > maxBytes {
            textureWidth = 640
            for (k, jpeg) in jpegs { jpegs[k] = shrink(jpeg, to: textureWidth) ?? jpeg }
        }

        var bin = Data()
        var bufferViews: [[String: Any]] = [], accessors: [[String: Any]] = []
        var images: [[String: Any]] = [], textures: [[String: Any]] = [], materials: [[String: Any]] = []
        var primitives: [[String: Any]] = []

        func view(_ data: Data, target: Int?) -> Int {
            while bin.count % 4 != 0 { bin.append(0) }
            var v: [String: Any] = ["buffer": 0, "byteOffset": bin.count, "byteLength": data.count]
            if let target { v["target"] = target }
            bin.append(data)
            bufferViews.append(v)
            return bufferViews.count - 1
        }
        func accessor(_ floats: [Float], type: String, components: Int, minMax: Bool) -> Int {
            let bv = floats.withUnsafeBytes { view(Data($0), target: 34962) }
            var a: [String: Any] = ["bufferView": bv, "componentType": 5126, "count": floats.count / components, "type": type]
            if minMax {
                var lo = [Float](repeating: .greatestFiniteMagnitude, count: components)
                var hi = [Float](repeating: -.greatestFiniteMagnitude, count: components)
                for (i, f) in floats.enumerated() {
                    lo[i % components] = min(lo[i % components], f)
                    hi[i % components] = max(hi[i % components], f)
                }
                a["min"] = lo.map(Double.init)
                a["max"] = hi.map(Double.init)
            }
            accessors.append(a)
            return accessors.count - 1
        }
        func positions(_ triangles: [Int]) -> [Float] {
            var out: [Float] = []
            out.reserveCapacity(triangles.count * 9)
            for t in triangles {
                for c in 0..<3 {
                    let p = mesh.positions[Int(mesh.triangles[t * 3 + c])] + shift
                    out += [p.x, p.y, p.z]
                }
            }
            return out
        }

        var plain = layout.plain
        var photosUsed = 0
        for (k, triangles) in layout.groups {
            guard let jpeg = jpegs[k] else { plain += triangles; continue }
            var uv: [Float] = []
            uv.reserveCapacity(triangles.count * 6)
            for t in triangles {
                for c in 0..<3 {
                    let q = layout.uv(k, mesh.positions[Int(mesh.triangles[t * 3 + c])])
                    uv += [q.x, q.y]
                }
            }
            images.append(["bufferView": view(jpeg, target: nil), "mimeType": "image/jpeg"])
            textures.append(["source": images.count - 1, "sampler": 0])
            materials.append([
                "name": "photo \(k)",
                "pbrMetallicRoughness": ["baseColorTexture": ["index": textures.count - 1],
                                         "metallicFactor": 0, "roughnessFactor": 1],
                "doubleSided": true,
                "extensions": ["KHR_materials_unlit": [String: Any]()],
            ])
            primitives.append([
                "attributes": ["POSITION": accessor(positions(triangles), type: "VEC3", components: 3, minMax: true),
                               "TEXCOORD_0": accessor(uv, type: "VEC2", components: 2, minMax: false)],
                "material": materials.count - 1,
                "mode": 4,
            ])
            photosUsed += 1
        }
        if !plain.isEmpty {
            let colors = PointColors(folder: folder)
            var rgb: [Float] = []
            rgb.reserveCapacity(plain.count * 9)
            for t in plain {
                for c in 0..<3 {
                    let col = colors?.color(near: mesh.positions[Int(mesh.triangles[t * 3 + c])]) ?? SIMD3(repeating: 0.55)
                    rgb += [col.x, col.y, col.z]
                }
            }
            let tinted = colors != nil
            materials.append([
                "name": "uncovered",
                "pbrMetallicRoughness": ["baseColorFactor": tinted ? [1.0, 1.0, 1.0, 1.0] : [0.27, 0.27, 0.27, 1.0],
                                         "metallicFactor": 0, "roughnessFactor": 1],
                "doubleSided": true,
                "extensions": ["KHR_materials_unlit": [String: Any]()],
            ])
            var attributes: [String: Any] = ["POSITION": accessor(positions(plain), type: "VEC3", components: 3, minMax: true)]
            if tinted { attributes["COLOR_0"] = accessor(rgb, type: "VEC3", components: 3, minMax: false) }
            primitives.append(["attributes": attributes, "material": materials.count - 1, "mode": 4])
        }
        while bin.count % 4 != 0 { bin.append(0) }

        var json: [String: Any] = [
            "asset": ["version": "2.0", "generator": "RoomScape photo room"],
            "extensionsUsed": ["KHR_materials_unlit"],
            "scene": 0,
            "scenes": [["nodes": [0]]],
            "nodes": [["mesh": 0, "name": "PhotoRoom"]],
            "meshes": [["name": "PhotoRoom", "primitives": primitives]],
            "materials": materials,
            "accessors": accessors,
            "bufferViews": bufferViews,
            "buffers": [["byteLength": bin.count]],
        ]
        if !images.isEmpty {
            json["images"] = images
            json["textures"] = textures
            // Linear filtering with mipmaps, clamped at the photo's edges.
            json["samplers"] = [["magFilter": 9729, "minFilter": 9987, "wrapS": 33071, "wrapT": 33071]]
        }
        guard var jsonData = try? JSONSerialization.data(withJSONObject: json, options: [.sortedKeys]) else { return nil }
        while jsonData.count % 4 != 0 { jsonData.append(0x20) }

        var out = Data()
        func u32(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { out.append(contentsOf: $0) } }
        u32(0x4654_6C67)  // "glTF"
        u32(2)
        u32(UInt32(12 + 8 + jsonData.count + 8 + bin.count))
        u32(UInt32(jsonData.count))
        u32(0x4E4F_534A)  // "JSON"
        out.append(jsonData)
        u32(UInt32(bin.count))
        u32(0x004E_4942)  // "BIN\0"
        out.append(bin)
        return Result(data: out, triangles: mesh.triangles.count / 3, photos: photosUsed,
                      uncovered: plain.count, textureWidth: textureWidth)
    }

    /// A JPEG scaled to `width` pixels wide.
    private static func shrink(_ jpeg: Data, to width: Int) -> Data? {
        guard let source = CGImageSourceCreateWithData(jpeg as CFData, nil),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceThumbnailMaxPixelSize: width,
              ] as CFDictionary) else { return nil }
        let out = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.8] as CFDictionary)
        return CGImageDestinationFinalize(destination) ? out as Data : nil
    }

    /// The scan's point colours (`points.bin`), averaged per 5 cm cell, in linear RGB for glTF's COLOR_0.
    struct PointColors {
        static let cell: Float = 0.05
        var cells: [SIMD3<Int32>: SIMD4<Float>] = [:]

        init?(folder: URL) {
            guard let data = try? Data(contentsOf: folder.appendingPathComponent("points.bin"), options: .mappedIfSafe),
                  data.count >= 16 else { return nil }
            data.withUnsafeBytes { raw in
                for i in 0..<(raw.count / 16) {
                    let o = i * 16
                    let p = SIMD3(raw.loadUnaligned(fromByteOffset: o, as: Float.self),
                                  raw.loadUnaligned(fromByteOffset: o + 4, as: Float.self),
                                  raw.loadUnaligned(fromByteOffset: o + 8, as: Float.self))
                    let rgba = raw.loadUnaligned(fromByteOffset: o + 12, as: UInt32.self)
                    let c = SIMD3(Float(rgba & 0xFF), Float((rgba >> 8) & 0xFF), Float((rgba >> 16) & 0xFF)) / 255
                    cells[Self.key(p), default: .zero] += SIMD4(c, 1)
                }
            }
            if cells.isEmpty { return nil }
        }

        static func key(_ p: SIMD3<Float>) -> SIMD3<Int32> { SIMD3<Int32>((p / cell).rounded(.down)) }

        /// The colour of the points in `p`'s cell, else in the cells around it; nil when there are none.
        func color(near p: SIMD3<Float>) -> SIMD3<Float>? {
            let k = Self.key(p)
            var sum = cells[k] ?? .zero
            if sum.w == 0 {
                for dx: Int32 in -1...1 { for dy: Int32 in -1...1 { for dz: Int32 in -1...1 {
                    sum += cells[k &+ SIMD3(dx, dy, dz)] ?? .zero
                } } }
            }
            guard sum.w > 0 else { return nil }
            let srgb = SIMD3(sum.x, sum.y, sum.z) / sum.w
            return SIMD3(pow(srgb.x, 2.2), pow(srgb.y, 2.2), pow(srgb.z, 2.2))
        }

        /// The same colour as stored (sRGB, 0…1), for files that keep colours as bytes.
        func srgb(near p: SIMD3<Float>) -> SIMD3<Float>? {
            color(near: p).map { SIMD3(pow($0.x, 1 / 2.2), pow($0.y, 1 / 2.2), pow($0.z, 1 / 2.2)) }
        }
    }
}
