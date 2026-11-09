import Foundation
import SceneKit
import UIKit

/// Loads a binary glTF (.glb) photo room into SceneKit, which can't read glTF itself. Covers what .rmsc packages
/// hold (docs/rmsc-format.md): triangle meshes with POSITION, optional TEXCOORD_0 / COLOR_0 and indices, materials
/// with a base colour texture (JPEG/PNG in the file) or colour, drawn unlit and two-sided, and node transforms.
enum GLBScene {
    /// Where an imported package's room.glb is kept in the capture folder.
    static let fileName = "room.glb"
    /// How far up the imported files were moved (an iPhone's own package goes back to its coordinates).
    static let liftFileName = "rmsc-import.json"

    /// The imported photo room of a scan, or nil. Call off the main thread.
    static func node(forScan folder: URL) -> SCNNode? {
        guard let data = try? Data(contentsOf: folder.appendingPathComponent(fileName), options: .mappedIfSafe),
              let node = load(data) else { return nil }
        if let l = try? Data(contentsOf: folder.appendingPathComponent(liftFileName)),
           let j = try? JSONSerialization.jsonObject(with: l) as? [String: Any], let y = (j["y"] as? NSNumber)?.floatValue {
            node.simdPosition.y += y
        }
        node.name = "photoRoom"
        return node
    }

    static func load(_ data: Data) -> SCNNode? {
        guard data.count > 20, data.prefix(4) == Data("glTF".utf8) else { return nil }
        func u32(_ i: Int) -> Int { Int(data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: i, as: UInt32.self).littleEndian }) }
        let jsonLength = u32(12)
        guard 20 + jsonLength <= data.count,
              let json = try? JSONSerialization.jsonObject(with: data.subdata(in: 20..<(20 + jsonLength))) as? [String: Any] else { return nil }
        var bin = Data()
        let binStart = 20 + jsonLength
        if binStart + 8 <= data.count {
            let length = u32(binStart)
            if binStart + 8 + length <= data.count { bin = data.subdata(in: (binStart + 8)..<(binStart + 8 + length)) }
        }
        let views = json["bufferViews"] as? [[String: Any]] ?? []
        let accessors = json["accessors"] as? [[String: Any]] ?? []
        let materials = json["materials"] as? [[String: Any]] ?? []
        let textures = json["textures"] as? [[String: Any]] ?? []
        let images = json["images"] as? [[String: Any]] ?? []

        func viewData(_ i: Int) -> (Data, Int)? {
            guard i < views.count else { return nil }
            let v = views[i], off = v["byteOffset"] as? Int ?? 0, len = v["byteLength"] as? Int ?? 0
            guard off + len <= bin.count else { return nil }
            return (bin.subdata(in: off..<(off + len)), v["byteStride"] as? Int ?? 0)
        }
        /// An accessor's values as floats (normalised integers mapped to 0…1), `components` per element.
        func floats(_ i: Int) -> (values: [Float], components: Int)? {
            guard i < accessors.count else { return nil }
            let a = accessors[i]
            guard let bv = a["bufferView"] as? Int, let (d, stride0) = viewData(bv), let count = a["count"] as? Int,
                  let type = a["type"] as? String, let ct = a["componentType"] as? Int else { return nil }
            let n = ["SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4][type] ?? 1
            let size = [5120: 1, 5121: 1, 5122: 2, 5123: 2, 5125: 4, 5126: 4][ct] ?? 4
            let stride = stride0 > 0 ? stride0 : n * size
            let base = a["byteOffset"] as? Int ?? 0
            let norm = a["normalized"] as? Bool ?? false
            guard base + (count - 1) * stride + n * size <= d.count || count == 0 else { return nil }
            var out = [Float](repeating: 0, count: count * n)
            d.withUnsafeBytes { raw in
                for e in 0..<count {
                    for c in 0..<n {
                        let o = base + e * stride + c * size
                        var v: Float
                        switch ct {
                        case 5126: v = Float(bitPattern: raw.loadUnaligned(fromByteOffset: o, as: UInt32.self))
                        case 5121: v = Float(raw.loadUnaligned(fromByteOffset: o, as: UInt8.self)); if norm { v /= 255 }
                        case 5123: v = Float(raw.loadUnaligned(fromByteOffset: o, as: UInt16.self)); if norm { v /= 65535 }
                        case 5125: v = Float(raw.loadUnaligned(fromByteOffset: o, as: UInt32.self))
                        case 5120: v = Float(raw.loadUnaligned(fromByteOffset: o, as: Int8.self))
                        default: v = Float(raw.loadUnaligned(fromByteOffset: o, as: Int16.self))
                        }
                        out[e * n + c] = v
                    }
                }
            }
            return (out, n)
        }
        func material(_ i: Int?) -> SCNMaterial {
            let m = SCNMaterial()
            m.lightingModel = .constant
            m.isDoubleSided = true
            m.diffuse.contents = UIColor(white: 0.6, alpha: 1)
            guard let i, i < materials.count else { return m }
            let pbr = materials[i]["pbrMetallicRoughness"] as? [String: Any] ?? [:]
            if let f = pbr["baseColorFactor"] as? [NSNumber], f.count >= 3 {
                m.diffuse.contents = UIColor(red: CGFloat(f[0].doubleValue), green: CGFloat(f[1].doubleValue),
                                             blue: CGFloat(f[2].doubleValue), alpha: 1)
            }
            if let t = (pbr["baseColorTexture"] as? [String: Any])?["index"] as? Int, t < textures.count,
               let s = textures[t]["source"] as? Int, s < images.count, let bv = images[s]["bufferView"] as? Int,
               let (d, _) = viewData(bv), let image = UIImage(data: d) {
                m.diffuse.contents = image
                m.diffuse.mipFilter = .linear
                m.diffuse.wrapS = .clamp
                m.diffuse.wrapT = .clamp
            }
            return m
        }
        func meshNode(_ mesh: [String: Any]) -> SCNNode {
            let node = SCNNode()
            for prim in mesh["primitives"] as? [[String: Any]] ?? [] {
                guard (prim["mode"] as? Int ?? 4) == 4, let attrs = prim["attributes"] as? [String: Int],
                      let pi = attrs["POSITION"], let pos = floats(pi), pos.components == 3 else { continue }
                let count = pos.values.count / 3
                var sources = [SCNGeometrySource(vertices: (0..<count).map {
                    SCNVector3(pos.values[$0 * 3], pos.values[$0 * 3 + 1], pos.values[$0 * 3 + 2])
                })]
                if let ti = attrs["TEXCOORD_0"], let uv = floats(ti), uv.components == 2 {
                    sources.append(SCNGeometrySource(textureCoordinates: (0..<count).map {
                        CGPoint(x: CGFloat(uv.values[$0 * 2]), y: CGFloat(uv.values[$0 * 2 + 1]))
                    }))
                }
                if let ci = attrs["COLOR_0"], let col = floats(ci), col.components >= 3 {
                    // glTF colours are linear; SceneKit's vertex colours too.
                    let rgb = (0..<count).flatMap { e in (0..<3).map { col.values[e * col.components + $0] } }
                    let data = rgb.withUnsafeBytes { Data($0) }
                    sources.append(SCNGeometrySource(data: data, semantic: .color, vectorCount: count, usesFloatComponents: true,
                                                     componentsPerVector: 3, bytesPerComponent: 4, dataOffset: 0, dataStride: 12))
                }
                let indices: [Int32]
                if let ii = prim["indices"] as? Int, let idx = floats(ii) {
                    indices = idx.values.map { Int32($0) }
                } else {
                    indices = (0..<Int32(count)).map { $0 }
                }
                let geometry = SCNGeometry(sources: sources, elements: [SCNGeometryElement(indices: indices, primitiveType: .triangles)])
                let m = material(prim["material"] as? Int)
                if attrs["COLOR_0"] != nil { m.diffuse.contents = UIColor.white }
                geometry.materials = [m]
                node.addChildNode(SCNNode(geometry: geometry))
            }
            return node
        }

        let nodes = json["nodes"] as? [[String: Any]] ?? []
        let meshes = json["meshes"] as? [[String: Any]] ?? []
        func build(_ i: Int, depth: Int = 0) -> SCNNode {
            let n = nodes[i], node = SCNNode()
            if let m = n["matrix"] as? [NSNumber], m.count == 16 {
                let f = m.map(\.floatValue)
                node.simdTransform = simd_float4x4(SIMD4(f[0], f[1], f[2], f[3]), SIMD4(f[4], f[5], f[6], f[7]),
                                                   SIMD4(f[8], f[9], f[10], f[11]), SIMD4(f[12], f[13], f[14], f[15]))
            } else {
                if let t = n["translation"] as? [NSNumber], t.count == 3 { node.simdPosition = SIMD3(t.map(\.floatValue)) }
                if let r = n["rotation"] as? [NSNumber], r.count == 4 {
                    node.simdOrientation = simd_quatf(ix: r[0].floatValue, iy: r[1].floatValue, iz: r[2].floatValue, r: r[3].floatValue)
                }
                if let s = n["scale"] as? [NSNumber], s.count == 3 { node.simdScale = SIMD3(s.map(\.floatValue)) }
            }
            if let m = n["mesh"] as? Int, m < meshes.count { node.addChildNode(meshNode(meshes[m])) }
            if depth < 32 {
                for c in n["children"] as? [Int] ?? [] where c < nodes.count { node.addChildNode(build(c, depth: depth + 1)) }
            }
            return node
        }
        let root = SCNNode()
        let scenes = json["scenes"] as? [[String: Any]] ?? []
        let sceneIndex = json["scene"] as? Int ?? 0
        let top = sceneIndex < scenes.count ? (scenes[sceneIndex]["nodes"] as? [Int] ?? []) : Array(nodes.indices)
        for i in top where i < nodes.count { root.addChildNode(build(i)) }
        if nodes.isEmpty { for m in meshes { root.addChildNode(meshNode(m)) } }
        return root.childNodes.isEmpty ? nil : root
    }
}
