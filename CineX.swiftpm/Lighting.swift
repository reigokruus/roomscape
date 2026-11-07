import Foundation
import simd

/// Shot lighting preview: gear lights and a subject in the scanned room (port of Android `Lighting.java`, handoff
/// section 3; keep these numbers).
///
/// The scan's colours are the room as the phone camera saw it, already lit by the room's own light (`roomLux`,
/// 250 lx). So each surface's albedo is its scanned colour / 250 lx, and with gear lights on it shows
///   linear(colour) × (roomDimmer + Σ gear lux / roomLux) × exposure
/// then a highlight shoulder above 0.8. The camera is exposed for the subject's face, like a DP would, ± an EV offset.
enum LightType: String, CaseIterable, Codable, Identifiable {
    case fresnel, panel, softbox, tube, practical, ringLight

    var id: String { rawValue }

    var label: String {
        switch self {
        case .fresnel: return "Fresnel 300 W LED"
        case .panel: return "LED panel 1×1"
        case .softbox: return "Softbox 90 cm"
        case .tube: return "LED tube"
        case .practical: return "Practical lamp"
        case .ringLight: return "Ring light 18″"
        }
    }

    /// Lux at 1 m at full power.
    var luxAt1m: Float {
        switch self {
        case .fresnel: return 20000
        case .panel: return 3000
        case .softbox: return 6000
        case .tube: return 800
        case .practical: return 300
        case .ringLight: return 1500
        }
    }

    /// Full beam angle, degrees.
    var beamDegrees: Float {
        switch self {
        case .fresnel: return 30
        case .panel: return 60
        case .softbox: return 100
        case .tube: return 140
        case .practical: return 170
        case .ringLight: return 90
        }
    }

    /// How far the light wraps around a surface (0 = hard, ~0.7 = very soft).
    var wrap: Float {
        switch self {
        case .fresnel: return 0
        case .panel: return 0.3
        case .softbox: return 0.6
        case .tube: return 0.5
        case .practical: return 0.7
        case .ringLight: return 0.4
        }
    }

    /// Best guess from a gear name the user typed ("Softbox", "Desk lamp", "Ring light"…).
    static func guess(from name: String) -> LightType {
        let n = name.lowercased()
        if n.contains("softbox") || n.contains("soft box") { return .softbox }
        if n.contains("ring") { return .ringLight }
        if n.contains("fresnel") || n.contains("spot") || n.contains("cob") { return .fresnel }
        if n.contains("tube") || n.contains("stick") { return .tube }
        if n.contains("lamp") || n.contains("practical") || n.contains("clamp") || n.contains("bulb") { return .practical }
        return .panel
    }
}

/// One light, placed relative to the subject: `angle` degrees around them (0 = from the camera's side, positive =
/// camera left… i.e. counter-clockwise seen from above), `distance` metres away, `height` metres above the floor.
/// It always aims at the subject's face.
struct ShotLight: Identifiable, Equatable, Codable {
    var id = UUID()
    var name: String
    var type: LightType
    var on = true
    /// 0…1.
    var power: Float
    var kelvin: Float = 5600
    var angle: Float
    var distance: Float
    var height: Float

    var luxAt1m: Float { on ? type.luxAt1m * power : 0 }
}

enum SubjectPose: String, CaseIterable, Codable, Identifiable {
    case seated, standing
    var id: String { rawValue }
    var label: String { self == .seated ? "Seated" : "Standing" }
    /// Face height above the floor (handoff: standing 1.62 m, seated 1.18 m).
    var faceHeight: Float { self == .seated ? 1.18 : 1.62 }
}

struct LightingSetup: Equatable {
    /// Room light in the scan (handoff: assume 250 lx).
    static let roomLux: Float = 250

    var lights: [ShotLight]
    var pose: SubjectPose = .seated
    /// Where the subject stands or sits, on the floor (x, z), and which way they face (degrees, 0 = +z).
    var subjectX: Float
    var subjectZ: Float
    var facing: Float
    var floorY: Float
    /// 0 = room lights off, 1 = as scanned.
    var roomDimmer: Float = 1
    /// + brighter.
    var exposureEV: Float = 0
    var whiteBalance: Float = 5600
    var iso: Float = 800
    var shutter: Float = 1.0 / 50

    static let maxLights = 4

    var faceCenter: SIMD3<Float> { SIMD3(subjectX, floorY + pose.faceHeight, subjectZ) }

    /// Unit vector the subject faces (horizontal).
    var facingDirection: SIMD3<Float> {
        let a = facing * .pi / 180
        return SIMD3(sin(a), 0, cos(a))
    }

    /// The shoot camera: 2 m in front of the face, at face height.
    var cameraPosition: SIMD3<Float> { faceCenter + facingDirection * 2 }

    func position(of light: ShotLight) -> SIMD3<Float> {
        let a = (facing + light.angle) * .pi / 180
        return SIMD3(subjectX + sin(a) * light.distance, floorY + light.height, subjectZ + cos(a) * light.distance)
    }

    /// Lux on a surface at `p` facing `n` from one light: inverse square (min distance² 0.04), beam falloff
    /// (smoothstep between beam/2 and 0.6 × beam/2), wrap-lambert.
    func lux(from light: ShotLight, at p: SIMD3<Float>, normal n: SIMD3<Float>) -> Float {
        guard light.on else { return 0 }
        let lp = position(of: light)
        var l = lp - p
        let d2 = max(0.04, simd_length_squared(l))
        l /= d2.squareRoot()
        let ndl = simd_dot(n, l)
        let w = light.type.wrap
        let lambert = max(0, (ndl + w) / (1 + w))
        let aim = simd_normalize(faceCenter - lp)
        let c = simd_dot(-l, aim)
        let half = light.type.beamDegrees / 2 * .pi / 180
        let cone = Self.smoothstep(cos(half), cos(half * 0.6), c)
        return light.luxAt1m * cone * lambert / d2
    }

    /// Light on the subject's face (facing the camera) from the room and from each gear light.
    var faceLux: (room: Float, gear: [(name: String, lux: Float)]) {
        let n = facingDirection
        let gear = lights.filter(\.on).map { ($0.name, lux(from: $0, at: faceCenter, normal: n)) }
        // The room light on a face is about 70% of what the walls got (handoff readout).
        return (Self.roomLux * roomDimmer * 0.7, gear)
    }

    /// Exposure multiplier that brings the face back to how the room looked in the scan, then ± exposureEV.
    var exposure: Float {
        let n = facingDirection
        let onFace = Self.roomLux * roomDimmer + lights.reduce(0) { $0 + lux(from: $1, at: faceCenter, normal: n) }
        return Self.roomLux / max(5, onFace) * pow(2, exposureEV)
    }

    /// Incident-meter f-stop for the face: N = √(E · t · ISO / 250), then the EV offset.
    var fStop: Float {
        let f = faceLux
        let e = f.room + f.gear.reduce(0) { $0 + $1.lux }
        return (e * shutter * iso / 250).squareRoot() * pow(2, -exposureEV / 2)
    }

    var readout: String {
        let f = faceLux
        let total = f.room + f.gear.reduce(0) { $0 + $1.lux }
        let n = fStop
        let stop = n < 0.9 ? "below f/1 (too dark)" : "f/\(Self.nearestStop(n))"
        return String(format: "Face %.0f lx → %@ at ISO %.0f, 1/%.0f s", total, stop, iso, 1 / shutter)
    }

    var breakdown: String {
        let f = faceLux
        let parts = f.gear.map { String(format: "%@ %.0f", $0.name, $0.lux) } + [String(format: "room %.0f", f.room)]
        return parts.joined(separator: " · ") + " lx"
    }

    static func nearestStop(_ n: Float) -> String {
        let stops: [Float] = [1.0, 1.2, 1.4, 1.8, 2.0, 2.4, 2.8, 3.5, 4.0, 4.8, 5.6, 6.7, 8.0, 9.5, 11, 13, 16, 19, 22]
        let best = stops.min { abs(log($0 / n)) < abs(log($1 / n)) }!
        return best == best.rounded() ? String(Int(best)) : String(format: "%.1f", best)
    }

    static func smoothstep(_ e0: Float, _ e1: Float, _ x: Float) -> Float {
        let t = max(0, min(1, (x - e0) / (e1 - e0)))
        return t * t * (3 - 2 * t)
    }

    /// Colour of a black body at `kelvin` relative to the white balance (Tanner Helland's approximation).
    static func tint(_ kelvin: Float, whiteBalance: Float) -> SIMD3<Float> {
        let a = blackBody(kelvin), b = blackBody(whiteBalance)
        return a / b
    }

    static func blackBody(_ k: Float) -> SIMD3<Float> {
        let t = Double(k) / 100
        var r: Double, g: Double, b: Double
        if t <= 66 {
            r = 255
            g = 99.4708025861 * log(t) - 161.1195681661
        } else {
            r = 329.698727446 * pow(t - 60, -0.1332047592)
            g = 288.1221695283 * pow(t - 60, -0.0755148492)
        }
        if t >= 66 { b = 255 } else if t <= 19 { b = 0 } else { b = 138.5177312231 * log(t - 10) - 305.0447927307 }
        func c(_ v: Double) -> Float { Float(max(0.02, min(1, v / 255))) }
        return SIMD3(c(r), c(g), c(b))
    }

    /// A starting setup: the user's own lights from My Gear (up to four), else a softbox key (60%, 45° to one side,
    /// above the face) and an LED panel fill (30%, other side), as in the handoff.
    static func starting(subjectX: Float, subjectZ: Float, facing: Float, floorY: Float, gear: [GearItem]) -> LightingSetup {
        let pose = SubjectPose.seated
        let face = pose.faceHeight
        let owned = gear.filter { $0.category == .light }.prefix(maxLights)
        var lights: [ShotLight]
        if owned.isEmpty {
            lights = [
                ShotLight(name: "Key", type: .softbox, power: 0.6, angle: 45, distance: 1.5, height: face + 0.6),
                ShotLight(name: "Fill", type: .panel, power: 0.3, angle: -50, distance: 1.7, height: face + 0.3),
            ]
        } else {
            let slots: [(name: String, power: Float, angle: Float, distance: Float, lift: Float)] = [
                ("Key", 0.6, 45, 1.5, 0.6), ("Fill", 0.3, -50, 1.7, 0.3), ("Back", 0.4, 160, 1.5, 0.8),
                ("Background", 0.3, -150, 2.0, 0.2),
            ]
            lights = owned.enumerated().map { i, item in
                let s = slots[i]
                return ShotLight(name: "\(s.name): \(item.name)", type: .guess(from: item.name), power: s.power,
                                 angle: s.angle, distance: s.distance, height: face + s.lift)
            }
        }
        return LightingSetup(lights: lights, pose: pose, subjectX: subjectX, subjectZ: subjectZ, facing: facing,
                             floorY: floorY)
    }
}
