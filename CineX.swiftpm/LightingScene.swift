import SceneKit
import UIKit
import simd

/// Metal shader-modifier code for the lighting preview (handoff section 3, `LIGHT_GLSL` on Android).
/// Up to four lights. Colours are linear here; SceneKit encodes the output.
enum LightingShader {
    static let maxLights = LightingSetup.maxLights
    static let maxRoom = LightingSetup.maxRoomSources

    /// Declarations for `#pragma arguments`.
    static let arguments: String =
        // SceneKit binds every argument as its own Metal constant buffer, and a shader may use at most 31, so they're
        // packed: litA = (on, room dimmer, daylight dimmer, room source count), litB = (room scale, exposure, -, -);
        // one 4x4 matrix per light property (column i = light i); room sources in two matrices each
        // (rPos.w = 1 for a window; rDir.xyz = direction or zero for all round, rDir.w = strength).
        "float4 litA;\nfloat4 litB;\nfloat4x4 lPosM;\nfloat4x4 lAimM;\nfloat4x4 lColM;\nfloat4x4 lConeM;\n"
            + "float4x4 rPosA;\nfloat4x4 rPosB;\nfloat4x4 rDirA;\nfloat4x4 rDirB;\n"

    /// Adds every gear light's illuminance to `E` for a point `P` with normal `N` (`hasN` = 0: no normal).
    /// Inverse square (min d² 0.04), beam cone smoothstep, wrap-lambert; `twoSided` uses |n·l| (splat normals from
    /// PCA have no reliable sign).
    static func gear(twoSided: Bool) -> String {
        (0..<maxLights).map { i in
            """
            {
                float3 L = lPosM[\(i)].xyz - P; float d2 = max(0.04, dot(L, L)); L *= rsqrt(d2);
                float ndl = hasN > 0.5 ? \(twoSided ? "abs(dot(N, L))" : "dot(N, L)") : 0.6;
                float w = lConeM[\(i)].z; float lam = max(0.0, (ndl + w) / (1.0 + w));
                float3 A = lAimM[\(i)].xyz - lPosM[\(i)].xyz; float al = length(A);
                float cc = al > 0.001 ? dot(-L, A / al) : 1.0;
                E += lColM[\(i)].rgb * smoothstep(lConeM[\(i)].x, lConeM[\(i)].y, cc) * lam / d2;
            }
            """
        }.joined(separator: "\n")
    }

    /// The room's own light at `P`: the fittings' and windows' shapes (same maths as `LightingSetup.roomShape`),
    /// combined into `roomFactor`. A scanned surface (`baked`) already shows the room light, so it keeps the share
    /// each kind gave it, dimmed by its slider; an unscanned one (the person) gets the absolute, calibrated amount.
    /// With no known sources the room light is flat: `litA.y`.
    private static func rPos(_ i: Int) -> String { i < 4 ? "rPosA[\(i)]" : "rPosB[\(i - 4)]" }
    private static func rDir(_ i: Int) -> String { i < 4 ? "rDirA[\(i)]" : "rDirB[\(i - 4)]" }

    static func room(twoSided: Bool, baked: Bool) -> String {
        var s = "float Ef = 0.0; float Ew = 0.0;\n"
        for i in 0..<maxRoom {
            s += """
            if (litA.w > \(i).5) {
                float3 L = \(rPos(i)).xyz - P; float d2 = max(\(rPos(i)).w > 0.5 ? 0.25 : 0.09, dot(L, L)); L *= rsqrt(d2);
                float ndl = hasN > 0.5 ? \(twoSided ? "abs(dot(N, L))" : "dot(N, L)") : 0.6;
                float lam = max(0.0, (ndl + 0.5) / 1.5);
                float3 D = \(rDir(i)).xyz;
                float emit = \(rPos(i)).w > 0.5 ? max(0.0, dot(-L, D)) : (dot(D, D) < 0.25 ? 1.0 : 0.3 + 0.7 * max(0.0, dot(-L, D)));
                float e = \(rDir(i)).w * emit * lam / d2;
                if (\(rPos(i)).w > 0.5) { Ew += e; } else { Ef += e; }
            }

            """
        }
        s += baked
            ? "float roomFactor = (Ef + Ew) > 1e-6 ? (litA.y * Ef + litA.z * Ew) / (Ef + Ew) : (litA.w > 0.5 ? 0.5 * (litA.y + litA.z) : litA.y);\n"
            : "float roomFactor = litA.w > 0.5 ? litB.x * (litA.y * Ef + litA.z * Ew) : litA.y * 0.7;\n"
        return s
    }

    /// `lin` (linear albedo × room light as scanned) → lit, exposed, with a highlight shoulder above 0.8.
    /// Bright spots (luminance > 0.8: lamps, windows) glow by themselves and aren't lit by gear.
    static func shade(twoSided: Bool, baked: Bool = true) -> String {
        """
        \(room(twoSided: twoSided, baked: baked))
        float3 E = float3(roomFactor);
        if (dot(lin, float3(0.3, 0.59, 0.11)) < 0.8) {
        \(gear(twoSided: twoSided))
        }
        float3 v = lin * E * litB.y;
        float3 shoulder = 0.8 + 0.2 * (1.0 - exp(-(v - 0.8) / 0.2));
        v = clamp(select(v, shoulder, v > 0.8), 0.0, 1.0);
        """
    }

    /// Inserted at the start of the splat geometry modifier's body (before the disc is opened up), after
    /// `worldCenter` / `worldNormal` are set. Writes the lit colour to the vertex colour.
    static let splatBody = """
    if (litA.x > 0.5) {
        float3 P = worldCenter;
        float3 N = worldNormal;
        float hasN = dot(N, N) > 0.25 ? 1.0 : 0.0;
        float3 lin = _geometry.color.rgb;
    \(shade(twoSided: true))
        _geometry.color.rgb = v;
    }
    """

    /// Surface modifiers, per pixel in world space: the room shell (stands in for scanned surfaces, so `baked`)
    /// and the props (the person and stool, not scanned).
    /// The shell's also takes the mesh look (`clay`, see `MeshLook`): clay colour before the lighting, grid after.
    static let surfaceModifier = surface(baked: true, meshLook: true)
    static let propSurfaceModifier = surface(baked: false, meshLook: false)

    private static func surface(baked: Bool, meshLook: Bool) -> String {
        """
        #pragma arguments
        \(arguments)\(meshLook ? "float clay;\n" : "")
        #pragma body
        float3 surfaceP = (scn_frame.inverseViewTransform * float4(_surface.position, 1.0)).xyz;
        float3 surfaceN = normalize((scn_frame.inverseViewTransform * float4(_surface.normal, 0.0)).xyz);
        \(meshLook ? "if (clay > 0.5) { _surface.diffuse.rgb = \(MeshLook.clay("surfaceN")); }" : "")
        if (litA.x > 0.5) {
            float3 P = surfaceP;
            float3 N = surfaceN;
            float hasN = 1.0;
            float3 lin = _surface.diffuse.rgb;
        \(shade(twoSided: false, baked: baked))
            _surface.diffuse.rgb = v;
        }
        \(meshLook ? "if (clay > 0.5) \(MeshLook.grid(p: "surfaceP", n: "surfaceN"))" : "")
        """
    }

    /// Sets a lit material's light values (off when `setup` is nil). Every argument is always set: with only some
    /// set, SceneKit didn't bind them at all and the splats rendered black.
    static func apply(_ setup: LightingSetup?, to m: SCNMaterial) {
        let room = setup?.roomSources ?? []
        func vec(_ v: SIMD4<Float>) -> NSValue { NSValue(scnVector4: SCNVector4(v.x, v.y, v.z, v.w)) }
        func mat(_ c: [SIMD4<Float>]) -> NSValue {
            NSValue(scnMatrix4: SCNMatrix4(simd_float4x4(c[0], c[1], c[2], c[3])))
        }
        m.setValue(vec(SIMD4(setup == nil ? 0 : 1, setup?.roomDimmer ?? 1, setup?.daylight ?? 1, Float(room.count))),
                   forKey: "litA")
        m.setValue(vec(SIMD4(setup?.roomScale ?? 1, setup?.exposure ?? 1, 0, 0)), forKey: "litB")
        let face = setup?.faceCenter ?? SIMD3(0, 0, 0)
        var pos = [SIMD4<Float>](), aim = [SIMD4<Float>](), col = [SIMD4<Float>](), cone = [SIMD4<Float>]()
        for i in 0..<maxLights {
            // Unused slots: a valid cone range and an aim that isn't the light's own position.
            if let setup, i < setup.lights.count {
                let l = setup.lights[i]
                let half = l.type.beamDegrees / 2 * .pi / 180
                pos.append(SIMD4(setup.position(of: l), 1))
                aim.append(SIMD4(face, 1))
                col.append(SIMD4(LightingSetup.tint(l.kelvin, whiteBalance: setup.whiteBalance) * (l.luxAt1m / LightingSetup.roomLux), 1))
                cone.append(SIMD4(cos(half), cos(half * 0.6), l.type.wrap, 0))
            } else {
                pos.append(SIMD4(0, 10, 0, 1)); aim.append(SIMD4(0, 0, 0, 1)); col.append(.zero); cone.append(SIMD4(0, 1, 0, 0))
            }
        }
        m.setValue(mat(pos), forKey: "lPosM")
        m.setValue(mat(aim), forKey: "lAimM")
        m.setValue(mat(col), forKey: "lColM")
        m.setValue(mat(cone), forKey: "lConeM")
        var rp = [SIMD4<Float>](), rd = [SIMD4<Float>]()
        for i in 0..<maxRoom {
            if i < room.count {
                rp.append(SIMD4(room[i].position, room[i].isWindow ? 1 : 0))
                rd.append(SIMD4(room[i].direction, room[i].strength))
            } else {
                rp.append(SIMD4(0, 10, 0, 0)); rd.append(.zero)
            }
        }
        m.setValue(mat(Array(rp[0..<4])), forKey: "rPosA")
        m.setValue(mat(Array(rp[4..<8])), forKey: "rPosB")
        m.setValue(mat(Array(rd[0..<4])), forKey: "rDirA")
        m.setValue(mat(Array(rd[4..<8])), forKey: "rDirB")
    }
}

/// The lighting preview's scene objects (subject, camera marker, light fixtures) and the lit materials of the
/// splats and room shell, kept in step with a `LightingSetup`.
@MainActor
final class LightingScene {
    /// Category of the camera marker, so the shot preview's camera (which stands inside it) leaves it out.
    nonisolated static let cameraMarkerCategory = 2

    let root = SCNNode()
    private var splatAndShell: [SCNMaterial] = []
    private var shellMaterials: [SCNMaterial] = []
    private var propMaterials: [SCNMaterial] = []

    /// Collects the lit materials under `node` (splats and room shell built by `SplatScene`).
    func register(_ node: SCNNode) {
        node.enumerateHierarchy { n, _ in
            for m in n.geometry?.materials ?? [] {
                guard let mods = m.shaderModifiers else { continue }
                if mods[.geometry]?.contains("litA") == true { splatAndShell.append(m) }
                if mods[.surface]?.contains("litA") == true { splatAndShell.append(m); shellMaterials.append(m) }
            }
        }
    }

    /// Forgets the registered materials and collects them again (after the splat nodes were rebuilt).
    func reregister(_ node: SCNNode) {
        splatAndShell = []
        shellMaterials = []
        register(node)
    }

    /// Shows the setup (or turns the preview off with nil).
    func apply(_ setup: LightingSetup?) {
        for m in splatAndShell { LightingShader.apply(setup, to: m) }
        // The shell is lit by the preview instead of SceneKit's default lights while it's on (and shades itself
        // in the mesh look).
        for m in shellMaterials { SplatScene.updateLightingModel(m) }
        root.childNodes.forEach { $0.removeFromParentNode() }
        propMaterials = []
        guard let setup else { return }
        root.addChildNode(subject(setup))
        root.addChildNode(cameraMarker(setup))
        for light in setup.lights {
            root.addChildNode(fixture(light, setup))
            if light.on, let run = setup.powerRun(for: light) { root.addChildNode(cable(light, to: run.to, setup)) }
        }
        for m in propMaterials { LightingShader.apply(setup, to: m) }
    }

    // MARK: Props

    private func litMaterial(_ r: CGFloat, _ g: CGFloat, _ b: CGFloat) -> SCNMaterial {
        let m = SCNMaterial()
        m.diffuse.contents = UIColor(red: r, green: g, blue: b, alpha: 1)
        m.lightingModel = .constant
        m.shaderModifiers = [.surface: LightingShader.propSurfaceModifier]
        propMaterials.append(m)
        return m
    }

    private func part(_ geometry: SCNGeometry, _ m: SCNMaterial, at p: SIMD3<Float>) -> SCNNode {
        geometry.materials = [m]
        let node = SCNNode(geometry: geometry)
        node.simdPosition = p
        return node
    }

    /// A simple person (head, torso, legs), seated on a stool or standing, facing the camera.
    private func subject(_ s: LightingSetup) -> SCNNode {
        let skin = litMaterial(0.78, 0.58, 0.47), cloth = litMaterial(0.22, 0.27, 0.36)
        let trousers = litMaterial(0.18, 0.18, 0.2), seat = litMaterial(0.32, 0.32, 0.34)
        let g = SCNNode()
        let face = s.pose.faceHeight
        g.addChildNode(part(SCNSphere(radius: 0.11), skin, at: SIMD3(0, face, 0)))
        g.addChildNode(part(SCNCapsule(capRadius: 0.17, height: 0.62), cloth, at: SIMD3(0, face - 0.42, 0)))
        if s.pose == .seated {
            g.addChildNode(part(SCNBox(width: 0.34, height: 0.14, length: 0.46, chamferRadius: 0.05), trousers,
                                at: SIMD3(0, 0.52, 0.12)))
            g.addChildNode(part(SCNBox(width: 0.3, height: 0.45, length: 0.12, chamferRadius: 0.04), trousers,
                                at: SIMD3(0, 0.23, 0.33)))
            g.addChildNode(part(SCNCylinder(radius: 0.2, height: 0.45), seat, at: SIMD3(0, 0.225, -0.02)))
        } else {
            g.addChildNode(part(SCNCapsule(capRadius: 0.13, height: 0.9), trousers, at: SIMD3(0, 0.45, 0)))
        }
        g.simdPosition = SIMD3(s.subjectX, s.floorY, s.subjectZ)
        g.eulerAngles.y = s.facing * .pi / 180
        return g
    }

    /// The shoot camera's position, so it's clear which way "front" is.
    private func cameraMarker(_ s: LightingSetup) -> SCNNode {
        let body = SCNBox(width: 0.16, height: 0.1, length: 0.08, chamferRadius: 0.01)
        body.firstMaterial?.diffuse.contents = UIColor.darkGray
        let lens = SCNCylinder(radius: 0.035, height: 0.06)
        lens.firstMaterial?.diffuse.contents = UIColor.black
        let n = SCNNode(geometry: body)
        let l = SCNNode(geometry: lens)
        l.eulerAngles.x = .pi / 2
        l.position = SCNVector3(0, 0, -0.06)
        n.addChildNode(l)
        n.simdPosition = s.cameraPosition
        n.simdLook(at: s.faceCenter)
        // The stand goes straight down to the floor, however the camera tilts.
        let height = s.cameraPosition.y - s.floorY
        let stand = SCNNode(geometry: SCNCylinder(radius: 0.01, height: CGFloat(height)))
        stand.geometry?.firstMaterial?.diffuse.contents = UIColor.darkGray
        n.addChildNode(stand)
        // World placement through the marker, which isn't in the scene yet: its parent (the preview's root) sits at
        // the origin, so its own transform is its world transform.
        stand.simdTransform = n.simdTransform.inverse
            * simd_float4x4(translation: s.cameraPosition - SIMD3(0, height / 2, 0))
        n.enumerateHierarchy { node, _ in node.categoryBitMask = Self.cameraMarkerCategory }
        return n
    }

    /// An orange cable from the stand's foot along the floor to below the socket, then up to it.
    private func cable(_ light: ShotLight, to socket: PowerPoint, _ s: LightingSetup) -> SCNNode {
        let lp = s.position(of: light)
        let y = s.floorY + 0.01
        let points = [SCNVector3(lp.x, y, lp.z), SCNVector3(socket.position.x, y, socket.position.z),
                      SCNVector3(socket.position.x, socket.position.y, socket.position.z)]
        let g = SCNGeometry(sources: [SCNGeometrySource(vertices: points)],
                            elements: [SCNGeometryElement(indices: [UInt16(0), 1, 1, 2], primitiveType: .line)])
        let m = SCNMaterial()
        m.diffuse.contents = UIColor.systemOrange
        m.lightingModel = .constant
        g.materials = [m]
        return SCNNode(geometry: g)
    }

    /// A glowing head on a stand, tinted by its colour temperature, dim when switched off.
    private func fixture(_ light: ShotLight, _ s: LightingSetup) -> SCNNode {
        let p = s.position(of: light)
        let tint = LightingSetup.blackBody(light.kelvin)
        let head = SCNBox(width: 0.24, height: 0.24, length: 0.06, chamferRadius: 0.02)
        let m = SCNMaterial()
        m.lightingModel = .constant
        let k = light.on ? CGFloat(0.4 + 0.6 * light.power) : 0.15
        m.diffuse.contents = UIColor(red: CGFloat(tint.x) * k, green: CGFloat(tint.y) * k, blue: CGFloat(tint.z) * k, alpha: 1)
        head.materials = [m]
        let n = SCNNode(geometry: head)
        n.simdPosition = p
        n.simdLook(at: s.faceCenter)
        let standHeight = CGFloat(p.y - s.floorY)
        let stand = SCNNode(geometry: SCNCylinder(radius: 0.012, height: standHeight))
        stand.geometry?.firstMaterial?.diffuse.contents = UIColor(white: 0.2, alpha: 1)
        stand.simdPosition = SIMD3(p.x, s.floorY + Float(standHeight) / 2, p.z)
        let g = SCNNode()
        g.addChildNode(n)
        g.addChildNode(stand)
        return g
    }
}

private extension simd_float4x4 {
    init(translation t: SIMD3<Float>) {
        self = matrix_identity_float4x4
        columns.3 = SIMD4(t, 1)
    }
}
