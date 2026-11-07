import SceneKit
import UIKit
import simd

/// SceneKit nodes for the splat view: surfels as soft discs, and RoomPlan's walls and floor as a one-sided
/// "dollhouse" shell (walls only show their inside, so from outside you look straight into the room).
enum SplatScene {
    /// The discs are drawn in one opaque pass (writing depth), each a hard-edged disc of 1.4x its density radius,
    /// so neighbouring discs overlap into a closed surface. (A blended gaussian halo pass like Android's was tried:
    /// SceneKit's transparent pass washed the colours out to white, so it was dropped.)
    static func splatNode(_ surfels: [SurfelRecord]) -> SCNNode {
        let n = surfels.count
        // One interleaved vertex buffer, 4 vertices per surfel (all at its centre; the shader opens them up):
        // position 3, normal 3, colour 4, corner uv 2, radius 2 floats. Filled with plain loops so it stays quick
        // in Debug builds.
        let floatsPerVertex = 14
        let stride = floatsPerVertex * 4
        var vertexData = Data(count: n * 4 * stride)
        var indexData = Data(count: n * 6 * 4)
        vertexData.withUnsafeMutableBytes { raw in
            let f = raw.baseAddress!.assumingMemoryBound(to: Float.self)
            surfels.withUnsafeBufferPointer { S in
                var i = 0
                while i < n {
                    let s = S[i]
                    let r = Float(s.rgba & 0xFF) / 255, g = Float((s.rgba >> 8) & 0xFF) / 255,
                        b = Float((s.rgba >> 16) & 0xFF) / 255
                    var corner = 0
                    while corner < 4 {
                        let o = (i * 4 + corner) * floatsPerVertex
                        f[o] = s.x; f[o + 1] = s.y; f[o + 2] = s.z
                        f[o + 3] = s.nx; f[o + 4] = s.ny; f[o + 5] = s.nz
                        f[o + 6] = r; f[o + 7] = g; f[o + 8] = b; f[o + 9] = 1
                        f[o + 10] = (corner == 1 || corner == 2) ? 1 : 0
                        f[o + 11] = corner >= 2 ? 1 : 0
                        f[o + 12] = s.radius; f[o + 13] = 0
                        corner += 1
                    }
                    i += 1
                }
            }
        }
        indexData.withUnsafeMutableBytes { raw in
            let x = raw.baseAddress!.assumingMemoryBound(to: UInt32.self)
            var i = 0
            while i < n {
                let b = UInt32(i * 4), o = i * 6
                x[o] = b; x[o + 1] = b + 1; x[o + 2] = b + 2; x[o + 3] = b; x[o + 4] = b + 2; x[o + 5] = b + 3
                i += 1
            }
        }
        func source(_ semantic: SCNGeometrySource.Semantic, _ comps: Int, at offset: Int) -> SCNGeometrySource {
            SCNGeometrySource(data: vertexData, semantic: semantic, vectorCount: n * 4, usesFloatComponents: true,
                              componentsPerVector: comps, bytesPerComponent: 4, dataOffset: offset * 4,
                              dataStride: stride)
        }
        let sources = [source(.vertex, 3, at: 0), source(.normal, 3, at: 3), source(.color, 4, at: 6),
                       source(.texcoord, 2, at: 10), source(.texcoord, 2, at: 12)]
        let element = SCNGeometryElement(data: indexData, primitiveType: .triangles, primitiveCount: n * 2,
                                         bytesPerIndex: 4)

        let geometry = SCNGeometry(sources: sources, elements: [element])
        geometry.materials = [material(scale: 1.4)]
        return SCNNode(geometry: geometry)
    }

    /// Opens each quad (four copies of the surfel centre) into a disc on the surfel's plane, or facing the camera
    /// when it has no normal.
    private static let geometryModifier = """
    #pragma arguments
    float splatScale;
    #pragma body
    float2 c = _geometry.texcoords[0] * 2.0 - 1.0;
    float rad = _geometry.texcoords[1].x;
    float3 n = _geometry.normal;
    float3 t1;
    float3 t2;
    if (dot(n, n) < 0.25) {
        t1 = (scn_frame.inverseViewTransform * float4(1.0, 0.0, 0.0, 0.0)).xyz;
        t2 = (scn_frame.inverseViewTransform * float4(0.0, 1.0, 0.0, 0.0)).xyz;
        n = (scn_frame.inverseViewTransform * float4(0.0, 0.0, 1.0, 0.0)).xyz;
    } else {
        float3 a = abs(n.y) < 0.9 ? float3(0.0, 1.0, 0.0) : float3(1.0, 0.0, 0.0);
        t1 = normalize(cross(a, n));
        t2 = cross(n, t1);
    }
    _geometry.position.xyz += (t1 * c.x + t2 * c.y) * rad * splatScale;
    _geometry.normal = n;
    """

    // The diffuse texture is plain white, only there so `diffuseTexcoord` carries the corner position;
    // the disc shape is cut out here.
    private static let discFragment = """
    float2 d = _surface.diffuseTexcoord * 2.0 - 1.0;
    if (dot(d, d) > 1.0) discard_fragment();
    _output.color.a = 1.0;
    """

    private static func material(scale: Float) -> SCNMaterial {
        let m = SCNMaterial()
        m.lightingModel = .constant
        m.diffuse.contents = whiteTexture
        m.diffuse.wrapS = .clamp
        m.diffuse.wrapT = .clamp
        m.isDoubleSided = true
        m.blendMode = .replace
        m.shaderModifiers = [.geometry: geometryModifier, .fragment: discFragment]
        m.setValue(NSNumber(value: scale), forKey: "splatScale")
        return m
    }

    /// 4x4 plain white texture (see the surface modifiers).
    private static let whiteTexture: UIImage = {
        UIGraphicsImageRenderer(size: CGSize(width: 4, height: 4)).image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: 4, height: 4))
        }
    }()

    // MARK: Room shell

    static func shellNode(_ shell: RoomShell) -> SCNNode {
        let group = SCNNode()
        for wall in shell.walls {
            group.addChildNode(quad(wall, inset: 0, color: UIColor(white: 0.78, alpha: 1)))
        }
        for opening in shell.openings {
            let color = opening.isWindow ? UIColor(red: 0.45, green: 0.75, blue: 1.0, alpha: 1)
                                         : UIColor(red: 0.65, green: 0.45, blue: 0.3, alpha: 1)
            group.addChildNode(quad(opening.wall, inset: 0.003, color: color))
        }
        if shell.floorOutline.count >= 3 {
            group.addChildNode(floor(shell.floorOutline, color: UIColor(white: 0.45, alpha: 1)))
        }
        return group
    }

    private static func quad(_ wall: RoomShell.Wall, inset: Float, color: UIColor) -> SCNNode {
        let c = wall.center + wall.inward * inset
        let r = wall.right * wall.halfWidth, u = wall.up * wall.halfHeight
        var v = [c - r - u, c + r - u, c + r + u, c - r + u]
        // Wind the triangles so the front face looks into the room; the back face is culled.
        if simd_dot(simd_cross(v[1] - v[0], v[2] - v[0]), wall.inward) < 0 { v = [v[0], v[3], v[2], v[1]] }
        return mesh(v, normal: wall.inward, indices: [0, 1, 2, 0, 2, 3], color: color)
    }

    private static func floor(_ outline: [SIMD3<Float>], color: UIColor) -> SCNNode {
        // Fan from the centre: fine for the convex or star-shaped outlines rooms have.
        let centre = outline.reduce(.zero, +) / Float(outline.count)
        let v = [centre] + outline
        var idx: [UInt32] = []
        for i in 0..<outline.count {
            var tri: [UInt32] = [0, UInt32(i + 1), UInt32((i + 1) % outline.count + 1)]
            if simd_cross(v[Int(tri[1])] - v[0], v[Int(tri[2])] - v[0]).y < 0 { tri.swapAt(1, 2) }
            idx += tri
        }
        return mesh(v, normal: SIMD3(0, 1, 0), indices: idx, color: color)
    }

    private static func mesh(_ v: [SIMD3<Float>], normal: SIMD3<Float>, indices: [UInt32], color: UIColor) -> SCNNode {
        let verts = SCNGeometrySource(vertices: v.map { SCNVector3($0.x, $0.y, $0.z) })
        let normals = SCNGeometrySource(normals: v.map { _ in SCNVector3(normal.x, normal.y, normal.z) })
        let element = SCNGeometryElement(indices: indices, primitiveType: .triangles)
        let g = SCNGeometry(sources: [verts, normals], elements: [element])
        let m = SCNMaterial()
        m.diffuse.contents = color
        m.lightingModel = .lambert
        m.cullMode = .back
        g.materials = [m]
        return SCNNode(geometry: g)
    }
}
