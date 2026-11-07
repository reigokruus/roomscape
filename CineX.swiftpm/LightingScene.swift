import SceneKit
import UIKit
import simd

/// Metal shader-modifier code for the lighting preview (handoff section 3, `LIGHT_GLSL` on Android).
/// Up to four lights. Colours are linear here; SceneKit encodes the output.
enum LightingShader {
    static let maxLights = LightingSetup.maxLights

    /// Declarations for `#pragma arguments`.
    static let arguments: String = {
        var s = "float litOn;\nfloat roomDimmer;\nfloat expo;\n"
        for i in 0..<maxLights { s += "float4 lPos\(i);\nfloat4 lAim\(i);\nfloat4 lCol\(i);\nfloat4 lCone\(i);\n" }
        return s
    }()

    /// Adds every gear light's illuminance to `E` for a point `P` with normal `N` (`hasN` = 0: no normal).
    /// Inverse square (min d² 0.04), beam cone smoothstep, wrap-lambert; `twoSided` uses |n·l| (splat normals from
    /// PCA have no reliable sign).
    static func gear(twoSided: Bool) -> String {
        (0..<maxLights).map { i in
            """
            {
                float3 L = lPos\(i).xyz - P; float d2 = max(0.04, dot(L, L)); L *= rsqrt(d2);
                float ndl = hasN > 0.5 ? \(twoSided ? "abs(dot(N, L))" : "dot(N, L)") : 0.6;
                float w = lCone\(i).z; float lam = max(0.0, (ndl + w) / (1.0 + w));
                float3 A = lAim\(i).xyz - lPos\(i).xyz; float al = length(A);
                float cc = al > 0.001 ? dot(-L, A / al) : 1.0;
                E += lCol\(i).rgb * smoothstep(lCone\(i).x, lCone\(i).y, cc) * lam / d2;
            }
            """
        }.joined(separator: "\n")
    }

    /// `lin` (linear albedo × room light as scanned) → lit, exposed, with a highlight shoulder above 0.8.
    /// Bright spots (luminance > 0.8: lamps, windows) glow by themselves and aren't lit by gear.
    static func shade(twoSided: Bool) -> String {
        """
        float3 E = float3(roomDimmer);
        if (dot(lin, float3(0.3, 0.59, 0.11)) < 0.8) {
        \(gear(twoSided: twoSided))
        }
        float3 v = lin * E * expo;
        float3 shoulder = 0.8 + 0.2 * (1.0 - exp(-(v - 0.8) / 0.2));
        v = clamp(select(v, shoulder, v > 0.8), 0.0, 1.0);
        """
    }

    /// Inserted at the start of the splat geometry modifier's body (before the disc is opened up, so `position` is
    /// the splat's centre). Writes the lit colour to the vertex colour.
    static let splatBody = """
    if (litOn > 0.5) {
        float3 P = _geometry.position.xyz;
        float3 N = _geometry.normal;
        float hasN = dot(N, N) > 0.25 ? 1.0 : 0.0;
        float3 lin = _geometry.color.rgb;
    \(shade(twoSided: true))
        _geometry.color.rgb = v;
    }
    """

    /// Surface modifier for the room shell and the props: per pixel, in world space.
    static let surfaceModifier = """
    #pragma arguments
    \(arguments)
    #pragma body
    if (litOn > 0.5) {
        float3 P = (scn_frame.inverseViewTransform * float4(_surface.position, 1.0)).xyz;
        float3 N = normalize((scn_frame.inverseViewTransform * float4(_surface.normal, 0.0)).xyz);
        float hasN = 1.0;
        float3 lin = _surface.diffuse.rgb;
    \(shade(twoSided: false))
        _surface.diffuse.rgb = v;
    }
    """

    /// Sets a lit material's light values (off when `setup` is nil). Every argument is always set: with only some
    /// set, SceneKit didn't bind them at all and the splats rendered black.
    static func apply(_ setup: LightingSetup?, to m: SCNMaterial) {
        m.setValue(NSNumber(value: setup == nil ? 0 : 1), forKey: "litOn")
        m.setValue(NSNumber(value: setup?.roomDimmer ?? 1), forKey: "roomDimmer")
        m.setValue(NSNumber(value: setup?.exposure ?? 1), forKey: "expo")
        let face = setup?.faceCenter ?? SIMD3(0, 0, 0)
        for i in 0..<maxLights {
            var pos = SIMD3<Float>(0, 10, 0), col = SIMD3<Float>(0, 0, 0)
            // Unused slots: a valid cone range and an aim that isn't the light's own position.
            var cone = SIMD3<Float>(0, 1, 0), aim = SIMD3<Float>(0, 0, 0)
            if let setup, i < setup.lights.count {
                let l = setup.lights[i]
                pos = setup.position(of: l)
                aim = face
                col = LightingSetup.tint(l.kelvin, whiteBalance: setup.whiteBalance) * (l.luxAt1m / LightingSetup.roomLux)
                let half = l.type.beamDegrees / 2 * .pi / 180
                cone = SIMD3(cos(half), cos(half * 0.6), l.type.wrap)
            }
            m.setValue(NSValue(scnVector4: SCNVector4(pos.x, pos.y, pos.z, 1)), forKey: "lPos\(i)")
            m.setValue(NSValue(scnVector4: SCNVector4(aim.x, aim.y, aim.z, 1)), forKey: "lAim\(i)")
            m.setValue(NSValue(scnVector4: SCNVector4(col.x, col.y, col.z, 1)), forKey: "lCol\(i)")
            m.setValue(NSValue(scnVector4: SCNVector4(cone.x, cone.y, cone.z, 0)), forKey: "lCone\(i)")
        }
    }
}

/// The lighting preview's scene objects (subject, camera marker, light fixtures) and the lit materials of the
/// splats and room shell, kept in step with a `LightingSetup`.
@MainActor
final class LightingScene {
    let root = SCNNode()
    private var splatAndShell: [SCNMaterial] = []
    private var shellMaterials: [SCNMaterial] = []
    private var propMaterials: [SCNMaterial] = []

    /// Collects the lit materials under `node` (splats and room shell built by `SplatScene`).
    func register(_ node: SCNNode) {
        node.enumerateHierarchy { n, _ in
            for m in n.geometry?.materials ?? [] {
                guard let mods = m.shaderModifiers else { continue }
                if mods[.geometry]?.contains("litOn") == true { splatAndShell.append(m) }
                if mods[.surface]?.contains("litOn") == true { splatAndShell.append(m); shellMaterials.append(m) }
            }
        }
    }

    /// Shows the setup (or turns the preview off with nil).
    func apply(_ setup: LightingSetup?) {
        for m in splatAndShell { LightingShader.apply(setup, to: m) }
        // The shell is lit by the preview instead of SceneKit's default lights while it's on.
        for m in shellMaterials { m.lightingModel = setup == nil ? .lambert : .constant }
        root.childNodes.forEach { $0.removeFromParentNode() }
        propMaterials = []
        guard let setup else { return }
        root.addChildNode(subject(setup))
        root.addChildNode(cameraMarker(setup))
        for light in setup.lights { root.addChildNode(fixture(light, setup)) }
        for m in propMaterials { LightingShader.apply(setup, to: m) }
    }

    // MARK: Props

    private func litMaterial(_ r: CGFloat, _ g: CGFloat, _ b: CGFloat) -> SCNMaterial {
        let m = SCNMaterial()
        m.diffuse.contents = UIColor(red: r, green: g, blue: b, alpha: 1)
        m.lightingModel = .constant
        m.shaderModifiers = [.surface: LightingShader.surfaceModifier]
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
        let stand = SCNNode(geometry: SCNCylinder(radius: 0.01, height: CGFloat(s.faceCenter.y - s.floorY)))
        stand.geometry?.firstMaterial?.diffuse.contents = UIColor.darkGray
        stand.position = SCNVector3(0, -(s.faceCenter.y - s.floorY) / 2, 0)
        n.addChildNode(stand)
        n.simdPosition = s.cameraPosition
        n.simdLook(at: s.faceCenter)
        return n
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
