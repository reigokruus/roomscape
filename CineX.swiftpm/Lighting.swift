import Foundation
import simd

/// Shot lighting preview: gear lights and a subject in the scanned room (port of Android `Lighting.java`, handoff
/// section 3; keep these numbers).
///
/// The scan's colours are the room as the phone camera saw it, already lit by the room's own light (`roomLux`,
/// 250 lx). So each surface's albedo is its scanned colour / 250 lx, and with gear lights on it shows
///   linear(colour) × (roomDimmer + Σ gear lux / roomLux) × exposure
/// then a highlight shoulder above 0.8. The camera stops down for a face brighter than the scan (never pushes it up), ± an EV offset.
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

/// A light source that was already in the room when it was scanned: a ceiling or wall light or lamp (from the
/// objects found in the photos), or a window (daylight). The scan's colours contain their light; knowing where they
/// are lets the preview tell, for every surface, how much of its light came from which kind (see `roomShape`).
struct RoomLightSource: Equatable {
    enum Kind: Equatable { case ceiling, wall, lamp, window }
    var kind: Kind
    var label: String
    var position: SIMD3<Float>
    /// Unit vector it shines towards (down for a ceiling light, into the room for a window), or zero for all round.
    var direction: SIMD3<Float>
    /// Relative strength. Guesses, only their ratios matter: a fitting 1, a lamp 0.5, a window 3 per m² of glass.
    var strength: Float

    var isWindow: Bool { kind == .window }
}

/// A place to plug in, from the objects found in the photos.
struct PowerPoint: Equatable {
    var label: String
    var position: SIMD3<Float>
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
    /// 0 = room lights off, 1 = as scanned. With known sources this dims only the fittings' share of the light.
    var roomDimmer: Float = 1
    /// 0 = no daylight (night), 1 = as scanned: dims the windows' share of the light.
    var daylight: Float = 1
    /// The room's own light sources (at most `maxRoomSources`); empty = the room light is one flat level.
    var roomSources: [RoomLightSource] = []
    /// Scales the sources' summed shape so a typical scanned surface gets `roomLux` (set by `calibrate`).
    var roomScale: Float = 1
    /// Sockets and power strips, for the cable run to each light.
    var power: [PowerPoint] = []
    /// + brighter.
    var exposureEV: Float = 0
    var whiteBalance: Float = 5600
    var iso: Float = 800
    var shutter: Float = 1.0 / 50
    /// The shoot camera: how far in front of the face (metres), its lens (full-frame equivalent focal length, mm)
    /// and whether the frame is upright (9:16, for phones and reels) or wide (16:9).
    var cameraDistance: Float = 2
    /// The camera's height above the floor (metres), or nil for the person's eye level, which follows seated /
    /// standing. The camera always aims at the face, so higher looks down on them and lower looks up.
    var cameraHeight: Float?
    var focalLength: Float = 26
    var vertical = false
    /// Claude's kind of shot ("Interview"…), reasoning and practical tips for the setup it proposed
    /// (see `ShotDirector`), or nil.
    var planTitle: String?
    var planNote: String?
    var planTips: [String] = []

    /// Lenses offered (full-frame equivalent): iPhone ultra wide, main, then common primes.
    static let lenses: [Float] = [13, 26, 35, 50, 85]

    static let maxLights = 4
    static let maxRoomSources = 8

    var faceCenter: SIMD3<Float> { SIMD3(subjectX, floorY + pose.faceHeight, subjectZ) }

    /// Unit vector the subject faces (horizontal).
    var facingDirection: SIMD3<Float> {
        let a = facing * .pi / 180
        return SIMD3(sin(a), 0, cos(a))
    }

    /// The shoot camera: `cameraDistance` in front of the face (along the floor), at `cameraHeight` or eye level.
    var cameraPosition: SIMD3<Float> {
        var p = faceCenter + facingDirection * cameraDistance
        if let cameraHeight { p.y = floorY + cameraHeight }
        return p
    }

    /// Range offered for `cameraHeight`, metres above the floor: a low table shot to a raised tripod.
    static let cameraHeights: ClosedRange<Float> = 0.3...2.4

    /// The frame's full field of view along its long side, degrees (36 mm full-frame long side).
    var fieldOfView: Float { 2 * atan(18 / focalLength) * 180 / .pi }

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

    // MARK: The room's own light

    /// How strongly one room source lights a surface at `p` facing `n` (relative units). Mirrored exactly in the
    /// shader (`LightingShader`): inverse square (min distance² 0.09, 0.25 for a window), soft wrap-lambert (0.5),
    /// and emission towards its direction (a window: cosine; a fitting: 30% all round + 70% cosine).
    static func roomShape(_ s: RoomLightSource, at p: SIMD3<Float>, normal n: SIMD3<Float>) -> Float {
        var l = s.position - p
        let d2 = max(s.isWindow ? 0.25 : 0.09, simd_length_squared(l))
        l /= d2.squareRoot()
        let lambert = max(0, (simd_dot(n, l) + 0.5) / 1.5)
        let emit: Float
        if s.isWindow {
            emit = max(0, simd_dot(-l, s.direction))
        } else if simd_length_squared(s.direction) < 0.25 {
            emit = 1
        } else {
            emit = 0.3 + 0.7 * max(0, simd_dot(-l, s.direction))
        }
        return s.strength * emit * lambert / d2
    }

    /// The fittings' and the windows' shape at a point.
    func roomShares(at p: SIMD3<Float>, normal n: SIMD3<Float>) -> (fittings: Float, daylight: Float) {
        var f: Float = 0, d: Float = 0
        for s in roomSources {
            let e = Self.roomShape(s, at: p, normal: n)
            if s.isWindow { d += e } else { f += e }
        }
        return (f, d)
    }

    /// Room light on a surface that wasn't scanned (the person), in lux: placed sources if known, else flat.
    func roomLux(at p: SIMD3<Float>, normal n: SIMD3<Float>) -> (fittings: Float, daylight: Float) {
        guard !roomSources.isEmpty else { return (Self.roomLux * roomDimmer * 0.7, 0) }
        let s = roomShares(at: p, normal: n)
        return (Self.roomLux * roomScale * roomDimmer * s.fittings, Self.roomLux * roomScale * daylight * s.daylight)
    }

    /// Sets `roomScale` so the average of `samples` (scanned surfaces: position, normal) gets `roomLux`.
    mutating func calibrate(_ samples: [(p: SIMD3<Float>, n: SIMD3<Float>)]) {
        guard !roomSources.isEmpty, !samples.isEmpty else { roomScale = 1; return }
        let mean = samples.reduce(Float(0)) { sum, x in
            let s = roomShares(at: x.p, normal: x.n)
            return sum + s.fittings + s.daylight
        } / Float(samples.count)
        roomScale = mean > 1e-6 ? 1 / mean : 1
    }

    // MARK: Power

    /// Cable from the light stand's foot to the nearest socket: along the floor, then up to the socket, plus 30 cm.
    func powerRun(for light: ShotLight) -> (meters: Float, to: PowerPoint)? {
        let lp = position(of: light)
        return power.map { pt -> (Float, PowerPoint) in
            let flat = simd_distance(SIMD2(lp.x, lp.z), SIMD2(pt.position.x, pt.position.z))
            return (flat + max(0, pt.position.y - floorY) + 0.3, pt)
        }.min { $0.0 < $1.0 }.map { (meters: $0.0, to: $0.1) }
    }

    /// Light on the subject's face (facing the camera) from the room and from each gear light.
    var faceLux: (room: Float, daylight: Float, gear: [(name: String, lux: Float)]) {
        let n = facingDirection
        let gear = lights.filter(\.on).map { ($0.name, lux(from: $0, at: faceCenter, normal: n)) }
        let room = roomLux(at: faceCenter, normal: n)
        return (room.fittings, room.daylight, gear)
    }

    /// Exposure multiplier that brings the face back to how the room looked in the scan, then ± exposureEV.
    /// Only ever darkens: bright gear on the face stops the room down, but a face lit by less than the scan's room
    /// light (no gear on, or the estimated room light on an unscanned face is low) doesn't push the whole scan up.
    /// Uncapped, removing the last light gave gains up to 50x and blew every scanned surface out to white. The
    /// readout still meters the real face, and the EV slider can brighten on purpose.
    var exposure: Float {
        let n = facingDirection
        let room: Float
        if roomSources.isEmpty {
            room = Self.roomLux * roomDimmer
        } else {
            let r = roomLux(at: faceCenter, normal: n)
            room = r.fittings + r.daylight
        }
        let onFace = room + lights.reduce(0) { $0 + lux(from: $1, at: faceCenter, normal: n) }
        return min(1, Self.roomLux / max(5, onFace)) * pow(2, exposureEV)
    }

    /// Incident-meter f-stop for the face: N = √(E · t · ISO / 250), then the EV offset.
    var fStop: Float {
        let f = faceLux
        let e = f.room + f.daylight + f.gear.reduce(0) { $0 + $1.lux }
        return (e * shutter * iso / 250).squareRoot() * pow(2, -exposureEV / 2)
    }

    var readout: String {
        let f = faceLux
        let total = f.room + f.daylight + f.gear.reduce(0) { $0 + $1.lux }
        let n = fStop
        let stop = n < 0.9 ? "below f/1 (too dark)" : "f/\(Self.nearestStop(n))"
        return String(format: "Face %.0f lx → %@ at ISO %.0f, 1/%.0f s", total, stop, iso, 1 / shutter)
    }

    var breakdown: String {
        let f = faceLux
        var parts = f.gear.map { String(format: "%@ %.0f", $0.name, $0.lux) }
        parts.append(String(format: roomSources.isEmpty ? "room %.0f" : "room lights %.0f", f.room))
        if roomSources.contains(where: \.isWindow) { parts.append(String(format: "daylight %.0f", f.daylight)) }
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
        // A phone's main camera unless My Gear lists a proper camera.
        let cameras = gear.filter { $0.category == .camera }.map { $0.name.lowercased() }
        let realCamera = cameras.contains { !$0.contains("iphone") && !$0.contains("phone") && !$0.contains("action") }
        return LightingSetup(lights: lights, pose: pose, subjectX: subjectX, subjectZ: subjectZ, facing: facing,
                             floorY: floorY, focalLength: realCamera ? 35 : 26)
    }
}
