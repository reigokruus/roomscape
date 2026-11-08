import SceneKit
import UIKit
import simd

/// Metal snippets for the mesh look (white clay with a grid), shared by the splats and the room shell so the two
/// read as one surface.
enum MeshLook {
    /// Grid size, metres.
    static let cell: Float = 0.2

    /// Linear clay colour for a surface facing `n` (any length, any sign: splat normals have no reliable sign).
    /// A fixed key from above and one side: floors brightest, walls facing different ways in different greys.
    static func clay(_ n: String) -> String {
        "float3(pow(0.92 * (0.6 + 0.4 * abs(dot(normalize(\(n)), float3(0.535, 0.802, 0.267)))), 2.2))"
    }

    /// Darkens `_surface.diffuse` on grid lines for a surface at `p` facing `n`: on floors and tabletops lines
    /// every `cell` along x and z; on walls (of any direction) along the wall and in height. About a pixel wide at
    /// any distance, faded out where the cells get too small to draw cleanly.
    static func grid(p: String, n: String) -> String {
        """
        {
            float3 gp = \(p);
            float3 gn = \(n);
            float2 uv;
            if (abs(gn.y) > 0.7 * length(gn)) {
                uv = gp.xz;
            } else {
                float2 along = normalize(float2(-gn.z, gn.x) + float2(1e-6, 0.0));
                uv = float2(dot(gp.xz, along), gp.y);
            }
            uv *= \(1 / cell);
            float2 fw = max(fwidth(uv), float2(1e-4));
            float2 g = abs(fract(uv - 0.5) - 0.5) / fw;
            float line = (1.0 - min(min(g.x, g.y), 1.0)) * (1.0 - smoothstep(0.12, 0.35, max(fw.x, fw.y)));
            _surface.diffuse.rgb *= 1.0 - 0.5 * line;
        }
        """
    }
}

/// SceneKit nodes for the splat view: surfels as soft discs, and RoomPlan's walls and floor as a one-sided
/// "dollhouse" shell (walls only show their inside, so from outside you look straight into the room).
enum SplatScene {
    /// Splats on or against a wall (`SurfelRecord.wallAngle`) vanish while the camera is outside that wall, so the
    /// near walls never hide the room in the 3D view (the dollhouse cutaway the one-sided shell walls give).
    /// The discs are drawn in one opaque pass (writing depth), each a hard-edged disc of 1.4x its density radius,
    /// so neighbouring discs overlap into a closed surface. (A blended gaussian halo pass like Android's was tried:
    /// SceneKit's transparent pass washed the colours out to white, so it was dropped.)
    /// sRGB byte → linear light (the exact sRGB curve).
    static let linear: [Float] = (0..<256).map { i in
        let c = Float(i) / 255
        return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
    }

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
                    // The camera's colours are sRGB-encoded; SceneKit treats vertex colours as linear light and encodes
                    // them again on output, which washed everything out ((220, 120, 80) showed as (243, 195, 168)).
                    let r = linear[Int(s.rgba & 0xFF)], g = linear[Int((s.rgba >> 8) & 0xFF)],
                        b = linear[Int((s.rgba >> 16) & 0xFF)]
                    var corner = 0
                    while corner < 4 {
                        let o = (i * 4 + corner) * floatsPerVertex
                        f[o] = s.x; f[o + 1] = s.y; f[o + 2] = s.z
                        f[o + 3] = s.nx; f[o + 4] = s.ny; f[o + 5] = s.nz
                        f[o + 6] = r; f[o + 7] = g; f[o + 8] = b; f[o + 9] = 1
                        f[o + 10] = (corner == 1 || corner == 2) ? 1 : 0
                        f[o + 11] = corner >= 2 ? 1 : 0
                        f[o + 12] = s.radius; f[o + 13] = s.wallAngle
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
    ///
    /// `clay` = 1: the mesh look. The scanned colours are replaced by white clay shaded by the disc's normal (so
    /// walls, floor and furniture read by their shape, not by the photo's noisy colours), and discs with a normal
    /// get a grid (see `gridSurface`). The grid flag rides on the corner texcoord (+2 on u), because an argument
    /// can only be declared in one of the material's shader modifiers.
    private static let geometryModifier = """
    #pragma arguments
    float splatScale;
    float clay;
    \(LightingShader.arguments)
    #pragma body
    // World position and normal of the splat's centre: object splats live in their own (movable) node.
    float3 worldCenter = (scn_node.modelTransform * float4(_geometry.position.xyz, 1.0)).xyz;
    float3 worldNormal = (scn_node.modelTransform * float4(_geometry.normal, 0.0)).xyz;
    float gridFlag = 0.0;
    if (clay > 0.5) {
        bool hasNormal = dot(worldNormal, worldNormal) > 0.25;
        _geometry.color.rgb = hasNormal ? \(MeshLook.clay("worldNormal")) : float3(pow(0.92 * 0.75, 2.2));
        gridFlag = hasNormal ? 2.0 : 0.0;
    }
    \(LightingShader.splatBody)
    float2 c = _geometry.texcoords[0] * 2.0 - 1.0;
    float rad = _geometry.texcoords[1].x;
    float3 n = _geometry.normal;
    float3 t1;
    float3 t2;
    if (dot(n, n) < 0.25) {
        // Face the camera (camera axes taken into this node's own space).
        t1 = (scn_node.inverseModelTransform * (scn_frame.inverseViewTransform * float4(1.0, 0.0, 0.0, 0.0))).xyz;
        t2 = (scn_node.inverseModelTransform * (scn_frame.inverseViewTransform * float4(0.0, 1.0, 0.0, 0.0))).xyz;
        n = (scn_node.inverseModelTransform * (scn_frame.inverseViewTransform * float4(0.0, 0.0, 1.0, 0.0))).xyz;
    } else {
        float3 a = abs(n.y) < 0.9 ? float3(0.0, 1.0, 0.0) : float3(1.0, 0.0, 0.0);
        t1 = normalize(cross(a, n));
        t2 = cross(n, t1);
    }
    // Dollhouse cutaway: a splat on or against a wall (texcoords[1].y = direction of the wall's inward normal in
    // the floor plane, -100 = none) is hidden while the camera is outside that wall, like the one-sided shell.
    // A splat on the ceiling (50, see `SplatTidy`) is hidden while the camera is above it.
    float wallAngle = _geometry.texcoords[1].y;
    if (wallAngle > 40.0) {
        if (scn_frame.inverseViewTransform[3].y > worldCenter.y) { rad = 0.0; }
    } else if (wallAngle > -50.0) {
        float3 inward = float3(cos(wallAngle), 0.0, sin(wallAngle));
        float3 camera = scn_frame.inverseViewTransform[3].xyz;
        if (dot(camera - worldCenter, inward) < 0.0) { rad = 0.0; }
    }
    _geometry.position.xyz += (t1 * c.x + t2 * c.y) * rad * splatScale;
    _geometry.normal = n;
    _geometry.texcoords[0].x += gridFlag;
    """

    /// The mesh look's grid on the splats, in the node's own space so it moves with a moved object.
    private static let gridSurface = """
    #pragma body
    if (_surface.diffuseTexcoord.x > 1.5) {
        float4 wp = scn_frame.inverseViewTransform * float4(_surface.position, 1.0);
        float3 lp = (scn_node.inverseModelTransform * wp).xyz;
        float3 wn = (scn_frame.inverseViewTransform * float4(_surface.normal, 0.0)).xyz;
        float3 ln = (scn_node.inverseModelTransform * float4(wn, 0.0)).xyz;
    \(MeshLook.grid(p: "lp", n: "ln"))
    }
    """

    // The diffuse texture is plain white, only there so `diffuseTexcoord` carries the corner position;
    // the disc shape is cut out here.
    private static let discFragment = """
    float2 t = _surface.diffuseTexcoord;
    if (t.x > 1.5) { t.x -= 2.0; }
    float2 d = t * 2.0 - 1.0;
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
        m.shaderModifiers = [.geometry: geometryModifier, .surface: gridSurface, .fragment: discFragment]
        m.setValue(NSNumber(value: scale), forKey: "splatScale")
        m.setValue(NSNumber(value: 1), forKey: "clay")
        LightingShader.apply(nil, to: m)
        return m
    }

    /// Switches every splat material and the shell's walls and floor under `node` between the mesh look (white
    /// clay with a grid) and the scanned colours. New splat nodes start in the mesh look. Doors and windows keep
    /// their colours in both.
    static func setMeshLook(_ on: Bool, in node: SCNNode) {
        let value = NSNumber(value: on ? 1 : 0)
        node.enumerateHierarchy { n, _ in
            for m in n.geometry?.materials ?? [] {
                if m.shaderModifiers?[.geometry]?.contains("splatScale") == true {
                    m.setValue(value, forKey: "clay")
                } else if m.name?.hasPrefix(shellSurfaceName) == true {
                    m.setValue(value, forKey: "clay")
                    updateLightingModel(m)
                }
            }
        }
    }

    /// Name of the shell's wall and floor materials (the ones that take the mesh look); the ones painted in the
    /// scanned colour add `scannedSuffix`.
    static let shellSurfaceName = "shellSurface"
    private static let scannedSuffix = ".scanned"

    /// The shell shades itself (`.constant`) in the mesh look and while the lighting preview is on, and always where
    /// it's painted in the scanned colour (so it matches the unshaded splats in front of it); otherwise SceneKit's
    /// default lights shade it.
    static func updateLightingModel(_ m: SCNMaterial) {
        let clay = (m.value(forKey: "clay") as? NSNumber)?.floatValue ?? 0
        let lit = (m.value(forKey: "litA") as? NSValue)?.scnVector4Value.x ?? 0
        let scanned = m.name?.hasSuffix(scannedSuffix) == true
        m.lightingModel = clay > 0.5 || lit > 0.5 || scanned ? .constant : .lambert
    }

    /// 4x4 plain white texture (see the surface modifiers).
    private static let whiteTexture: UIImage = {
        UIGraphicsImageRenderer(size: CGSize(width: 4, height: 4)).image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: 4, height: 4))
        }
    }()

    // MARK: Room shell

    /// - Parameter colors: the walls' and floor's scanned colours (`SplatTidy`): those surfaces are painted in them
    ///   outside the mesh look, so gaps between the splats in front don't show as grey speckles.
    static func shellNode(_ shell: RoomShell, colors: SplatTidy.ShellColors? = nil) -> SCNNode {
        func scanned(_ c: SIMD3<UInt8>?) -> UIColor? {
            c.map { UIColor(red: CGFloat($0.x) / 255, green: CGFloat($0.y) / 255, blue: CGFloat($0.z) / 255, alpha: 1) }
        }
        let group = SCNNode()
        for (i, wall) in shell.walls.enumerated() {
            let tint = scanned(colors.flatMap { i < $0.walls.count ? $0.walls[i] : nil })
            group.addChildNode(quad(wall, inset: 0, color: tint ?? UIColor(white: 0.78, alpha: 1), meshLook: true,
                                    scanned: tint != nil))
        }
        for opening in shell.openings {
            let color = opening.isWindow ? UIColor(red: 0.45, green: 0.75, blue: 1.0, alpha: 1)
                                         : UIColor(red: 0.65, green: 0.45, blue: 0.3, alpha: 1)
            group.addChildNode(quad(opening.wall, inset: 0.003, color: color))
        }
        if shell.floorOutline.count >= 3 {
            let tint = scanned(colors?.floor)
            group.addChildNode(floor(shell.floorOutline, color: tint ?? UIColor(white: 0.45, alpha: 1), meshLook: true,
                                     scanned: tint != nil))
        }
        return group
    }

    private static func quad(_ wall: RoomShell.Wall, inset: Float, color: UIColor, meshLook: Bool = false,
                             scanned: Bool = false) -> SCNNode {
        let c: SIMD3<Float> = wall.center + wall.inward * inset
        let r: SIMD3<Float> = wall.right * wall.halfWidth, u: SIMD3<Float> = wall.up * wall.halfHeight
        var v: [SIMD3<Float>] = [c - r - u, c + r - u, c + r + u, c - r + u]
        // Wind the triangles so the front face looks into the room; the back face is culled.
        if simd_dot(simd_cross(v[1] - v[0], v[2] - v[0]), wall.inward) < 0 { v = [v[0], v[3], v[2], v[1]] }
        return mesh(v, normal: wall.inward, indices: [0, 1, 2, 0, 2, 3], color: color, meshLook: meshLook,
                    scanned: scanned)
    }

    private static func floor(_ outline: [SIMD3<Float>], color: UIColor, meshLook: Bool, scanned: Bool) -> SCNNode {
        // Fan from the centre: fine for the convex or star-shaped outlines rooms have.
        let centre = outline.reduce(.zero, +) / Float(outline.count)
        let v = [centre] + outline
        var idx: [UInt32] = []
        for i in 0..<outline.count {
            var tri: [UInt32] = [0, UInt32(i + 1), UInt32((i + 1) % outline.count + 1)]
            if simd_cross(v[Int(tri[1])] - v[0], v[Int(tri[2])] - v[0]).y < 0 { tri.swapAt(1, 2) }
            idx += tri
        }
        return mesh(v, normal: SIMD3(0, 1, 0), indices: idx, color: color, meshLook: meshLook, scanned: scanned)
    }

    private static func mesh(_ v: [SIMD3<Float>], normal: SIMD3<Float>, indices: [UInt32], color: UIColor,
                             meshLook: Bool, scanned: Bool = false) -> SCNNode {
        let verts = SCNGeometrySource(vertices: v.map { SCNVector3($0.x, $0.y, $0.z) })
        let normals = SCNGeometrySource(normals: v.map { _ in SCNVector3(normal.x, normal.y, normal.z) })
        let element = SCNGeometryElement(indices: indices, primitiveType: .triangles)
        let g = SCNGeometry(sources: [verts, normals], elements: [element])
        let m = SCNMaterial()
        m.diffuse.contents = color
        m.lightingModel = .lambert
        m.cullMode = .back
        // Lit by the lighting preview when it's on (see LightingScene). Every material gets `clay` (all arguments
        // must be set); only the walls and floor are switched by `setMeshLook`.
        m.shaderModifiers = [.surface: LightingShader.surfaceModifier]
        m.setValue(NSNumber(value: 0), forKey: "clay")
        if meshLook { m.name = shellSurfaceName + (scanned ? scannedSuffix : "") }
        LightingShader.apply(nil, to: m)
        updateLightingModel(m)
        g.materials = [m]
        return SCNNode(geometry: g)
    }
}
