import Foundation
import simd

// World geometry is in metres; sensor dimensions / actual focal lengths are in mm.
// Camera.forward points INTO the shot (SceneKit local -Z), not toward the operator.
struct Lens: Equatable {
    let focalLengthMM: Float
    let label: String
    init(_ focalLengthMM: Float, label: String? = nil) {
        self.focalLengthMM = focalLengthMM
        self.label = label ?? String(format: "%.1f mm", focalLengthMM)
    }
}

struct Sensor: Equatable {
    let name: String
    let widthMM: Float
    let heightMM: Float
    let approximate: Bool
    let menuUsesEquivalentMM: Bool
    static let fullFrame = Sensor(name: "Full frame", widthMM: 36, heightMM: 24, approximate: false, menuUsesEquivalentMM: false)
    // Nominal 35mm full-aperture gate. Digital Super35 dimensions vary by camera/mode.
    static let super35 = Sensor(name: "Super35 (approx.)", widthMM: 24.89, heightMM: 18.66, approximate: true, menuUsesEquivalentMM: false)
    static let apsC = Sensor(name: "APS-C (nominal)", widthMM: 23.5, heightMM: 15.6, approximate: true, menuUsesEquivalentMM: false)
    // Illustrative physical area, NOT a calibrated iPhone model. No stabilisation crop.
    static let iPhoneMain = Sensor(name: "iPhone main (illustrative approx.)", widthMM: 9.8, heightMM: 7.3, approximate: true, menuUsesEquivalentMM: true)
    static let presets = [fullFrame, super35, apsC, iPhoneMain]
    func menuLens(_ mm: Float) -> Lens {
        menuUsesEquivalentMM
            ? Lens(mm * widthMM / 36, label: String(format: "%.0f mm eq", mm))
            : Lens(mm, label: String(format: "%.0f mm", mm))
    }
}

struct Subject {
    var center: SIMD3<Float>
    var size: SIMD3<Float> // width, height, depth in its own orthonormal basis
    var orientation = matrix_identity_float3x3
    var corners: [SIMD3<Float>] {
        var result: [SIMD3<Float>] = []
        for x in [Float(-1), 1] { for y in [Float(-1), 1] { for z in [Float(-1), 1] {
            result.append(center + orientation * (size * SIMD3(x, y, z) / 2))
        } } }
        return result
    }
    static let presets: [(String, SIMD3<Float>)] = [
        ("One person · full body", SIMD3(0.65, 1.8, 0.35)),
        ("Two-person · medium crop", SIMD3(1.6, 1.0, 0.4)),
        ("Tabletop arrangement", SIMD3(1.2, 0.5, 0.8))
    ]
}

struct Camera {
    var position: SIMD3<Float>
    var forward: SIMD3<Float>
    var up = SIMD3<Float>(0, 1, 0)
}

struct Wall {
    var transform: simd_float4x4
    var width: Float
    var height: Float
    var center: SIMD3<Float> { transform.columns.3.xyz }
    var endpoints: [SIMD3<Float>] {
        [center - transform.columns.0.xyz * width / 2, center + transform.columns.0.xyz * width / 2]
    }
    // RoomPlan surface-local XY rectangle, local Z normal. Rigid transform assumed.
    func intersection(origin: SIMD3<Float>, direction: SIMD3<Float>) -> Float? {
        let inverse = simd_inverse(transform)
        let p = (inverse * SIMD4(origin, 1)).xyz
        let d = (inverse * SIMD4(direction, 0)).xyz
        guard abs(d.z) > 0.000001 else { return nil }
        let t = -p.z / d.z
        guard t > 0.0001 else { return nil }
        let q = p + d * t
        return abs(q.x) <= width / 2 + 0.0001 && abs(q.y) <= height / 2 + 0.0001 ? t : nil
    }
}

struct RoomGeometry {
    var walls: [Wall]
    var floorY: Float
    var ceilingY: Float
    // Floor / ceiling inferred from wall extents are estimates, not surveyed surfaces.
    var heightsAreEstimated = true
    var closedFootprint: Bool {
        let ends = walls.flatMap(\.endpoints)
        guard walls.count >= 3 else { return false }
        return ends.indices.allSatisfy { i in
            ends.indices.filter { j in
                i != j && simd_length(SIMD2(ends[i].x - ends[j].x, ends[i].z - ends[j].z)) < 0.03
            }.count == 1
        }
    }
    // Even/odd test using wall segments projected onto world XZ; supports concave rooms.
    // Requires a closed, non-self-intersecting, single-room footprint.
    func contains(_ p: SIMD3<Float>) -> Bool {
        guard p.y >= floorY - 0.001 && p.y <= ceilingY + 0.001 else { return false }
        var inside = false
        for wall in walls {
            let a = wall.endpoints[0], b = wall.endpoints[1]
            let ab = SIMD2(b.x - a.x, b.z - a.z), ap = SIMD2(p.x - a.x, p.z - a.z)
            let u = min(1, max(0, simd_dot(ap, ab) / max(0.000001, simd_dot(ab, ab))))
            if simd_length(ap - ab * u) < 0.001 { return true }
            if (a.z > p.z) != (b.z > p.z), p.x < (b.x - a.x) * (p.z - a.z) / (b.z - a.z) + a.x { inside.toggle() }
        }
        return inside
    }
    func wallDistance(from p: SIMD3<Float>, along d: SIMD3<Float>) -> Float? {
        walls.compactMap { $0.intersection(origin: p, direction: d) }.min()
    }
    func boundaryHit(from p: SIMD3<Float>, along d: SIMD3<Float>) -> (distance: Float, surface: String)? {
        var hits: [(Float, String)] = []
        if let t = wallDistance(from: p, along: d) { hits.append((t, "wall")) }
        if abs(d.y) > 0.000001 {
            let floorT = (floorY - p.y) / d.y, ceilingT = (ceilingY - p.y) / d.y
            if floorT > 0.0001 { hits.append((floorT, "floor")) }
            if ceilingT > 0.0001 { hits.append((ceilingT, "ceiling")) }
        }
        return hits.min { $0.0 < $1.0 }.map { ($0.0, $0.1) }
    }
}

struct FrameCheckResult {
    var horizontalFOVDegrees: Float
    var verticalFOVDegrees: Float
    var subjectDepth: Float // optical-axis distance, NOT Euclidean distance
    var requiredDistance: Float // exact corner-based required optical-axis depth
    var centeredRequiredDistance: Float // size-only reference, ignoring lateral offset
    var stepBack: Float
    var backWallDistance: Float?
    var forwardWallDistance: Float?
    var usableBackwardTravel: Float? // includes operator clearance, floor/ceiling stops
    var maximumSubjectDistance: Float?
    var canStepBack: Bool?
    var subjectFullyInFrame: Bool
    var subjectInsideRoom: Bool
    var cameraInsideRoom: Bool
    var obstructedByWall: Bool
    var geometryComplete: Bool
    // Surfaces intercept the frame BEFORE its subject-depth plane, not see-through walls.
    var roomIntrusions: [String]
    var fits: Bool
    var fittingLensesNow: [Lens] = []
    var fittingLensesWithStepBack: [Lens] = []
    var warnings: [String]
}

enum FrameCheckError: Error { case invalidInput(String) }

enum FrameChecker {
    static func fov(sensorMM: Float, focalMM: Float) -> Float { 2 * atan(sensorMM / (2 * focalMM)) }

    static func check(room: RoomGeometry, camera: Camera, subject: Subject, lens: Lens,
                      sensor: Sensor, margin: Float = 1.10, clearance: Float = 0.25,
                      candidateLenses: [Lens] = []) throws -> FrameCheckResult {
        var result = try evaluate(room: room, camera: camera, subject: subject, lens: lens,
                                  sensor: sensor, margin: margin, clearance: clearance)
        for alternative in candidateLenses {
            let r = try evaluate(room: room, camera: camera, subject: subject, lens: alternative,
                                 sensor: sensor, margin: margin, clearance: clearance)
            if r.fits { result.fittingLensesNow.append(alternative) }
            // Recommendations are NOT optical-only: room / occlusion constraints apply too.
            if r.geometryComplete && r.subjectDepth > 0 && r.canStepBack == true {
                var moved = camera
                let spare = max(0, (r.usableBackwardTravel ?? 0) - r.stepBack)
                moved.position -= simd_normalize(camera.forward) * (r.stepBack + min(0.001, spare))
                let afterMove = try evaluate(room: room, camera: moved, subject: subject, lens: alternative,
                                             sensor: sensor, margin: margin, clearance: clearance)
                if afterMove.fits { result.fittingLensesWithStepBack.append(alternative) }
            }
        }
        return result
    }

    private static func evaluate(room: RoomGeometry, camera: Camera, subject: Subject, lens: Lens,
                                 sensor: Sensor, margin: Float, clearance: Float) throws -> FrameCheckResult {
        let numbers = [lens.focalLengthMM, sensor.widthMM, sensor.heightMM, margin, clearance, room.floorY, room.ceilingY]
        guard numbers.allSatisfy(\.isFinite), lens.focalLengthMM > 0, sensor.widthMM > 0, sensor.heightMM > 0,
              margin >= 1, clearance >= 0, room.ceilingY > room.floorY,
              camera.position.finite, camera.forward.finite, camera.up.finite,
              subject.center.finite, subject.size.finite, subject.size.x > 0, subject.size.y > 0, subject.size.z > 0,
              simd_length(camera.forward) > 0.0001 else { throw FrameCheckError.invalidInput("Nonfinite / nonpositive dimensions, lens, or direction") }
        let f = simd_normalize(camera.forward)
        guard simd_length(simd_cross(f, camera.up)) > 0.0001 else { throw FrameCheckError.invalidInput("Camera up is parallel to its forward direction") }
        guard (0..<3).allSatisfy({ i in
            let v = subject.orientation[i]
            return v.finite && abs(simd_length(v) - 1) < 0.001 && (0..<3).allSatisfy { j in i == j || abs(simd_dot(v, subject.orientation[j])) < 0.001 }
        }) else { throw FrameCheckError.invalidInput("Subject orientation must be orthonormal") }
        guard room.walls.allSatisfy({ wall in
            let t = wall.transform
            return wall.width.isFinite && wall.height.isFinite && wall.width > 0 && wall.height > 0 &&
                t.columns.3.xyz.finite && abs(t.columns.3.w - 1) < 0.001 && (0..<3).allSatisfy { i in
                    let v = t[i].xyz
                    return v.finite && abs(t[i].w) < 0.001 && abs(simd_length(v) - 1) < 0.001 &&
                        (0..<3).allSatisfy { j in i == j || abs(simd_dot(v, t[j].xyz)) < 0.001 }
                }
        }) else { throw FrameCheckError.invalidInput("Wall dimensions / rigid transforms invalid") }
        let right = simd_normalize(simd_cross(f, camera.up)), up = simd_cross(right, f)
        let h = fov(sensorMM: sensor.widthMM, focalMM: lens.focalLengthMM)
        let v = fov(sensorMM: sensor.heightMM, focalMM: lens.focalLengthMM)
        let th = tan(h / 2), tv = tan(v / 2)
        let centerDepth = simd_dot(subject.center - camera.position, f)
        let points = subject.corners
        let local = points.map { p -> SIMD3<Float> in
            let delta = p - camera.position
            return SIMD3(simd_dot(delta, right), simd_dot(delta, up), simd_dot(delta, f))
        }
        let requiredAdditionalDistance = local.reduce(-Float.infinity) { required, p in
            max(required, max(max(abs(p.x) * margin / th - p.z, abs(p.y) * margin / tv - p.z), 0.001 - p.z))
        }
        let stepBack = max(0, requiredAdditionalDistance)
        let fullyFramed = local.allSatisfy { $0.z > 0 && abs($0.x) * margin <= $0.z * th + 0.00001 && abs($0.y) * margin <= $0.z * tv + 0.00001 }
        let width = (local.map(\.x).max()! - local.map(\.x).min()!) * margin
        let height = (local.map(\.y).max()! - local.map(\.y).min()!) * margin
        let centeredDistance = max(width / (2 * th), height / (2 * tv))
        let back = room.wallDistance(from: camera.position, along: -f)
        let forward = room.wallDistance(from: camera.position, along: f)
        let backwardBoundary = room.boundaryHit(from: camera.position, along: -f)
        let usable = backwardBoundary.map { max(0, $0.distance - clearance) }
        let canStep: Bool? = usable.map { stepBack <= $0 + 0.00001 }
        let complete = room.closedFootprint
        let cameraInside = room.contains(camera.position)
        let subjectInside = points.allSatisfy(room.contains)
        let obstructed = ([subject.center] + points).contains { p in
            let delta = p - camera.position, distance = simd_length(delta)
            guard distance > 0.0001 else { return false }
            return room.wallDistance(from: camera.position, along: delta / distance).map { $0 < distance - 0.001 } ?? false
        }
        var intrusions = Set<String>()
        // 3x3 ray sample at frame edges / centre. Report presence, not visible percentage.
        if centerDepth > 0 {
            for x in [Float(-1), 0, 1] { for y in [Float(-1), 0, 1] {
                let ray = simd_normalize(f + right * x * th + up * y * tv)
                let atPlane = centerDepth / simd_dot(ray, f)
                if let hit = room.boundaryHit(from: camera.position, along: ray), hit.distance < atPlane - 0.001 { intrusions.insert(hit.surface) }
            } }
        }
        var warnings: [String] = []
        if !complete { warnings.append("Open/incomplete wall footprint: room-fit verdict is unverified, not a pass.") }
        if !cameraInside { warnings.append("Camera lies outside the inferred room volume.") }
        if !subjectInside { warnings.append("Part of the subject lies outside the inferred room volume.") }
        if obstructed { warnings.append("A scanned wall blocks at least one subject sample.") }
        if back == nil { warnings.append("No rear wall intersection: backward wall distance unknown.") }
        if centerDepth <= 0 { warnings.append("Subject is behind the camera; turn toward it first.") }
        if room.heightsAreEstimated { warnings.append("Floor/ceiling heights inferred from wall extents; verify on location.") }
        if sensor.approximate { warnings.append("Sensor preset is nominal/approximate; verify the actual active recording area.") }
        return FrameCheckResult(horizontalFOVDegrees: h * 180 / .pi, verticalFOVDegrees: v * 180 / .pi,
            subjectDepth: centerDepth, requiredDistance: centerDepth + requiredAdditionalDistance, centeredRequiredDistance: centeredDistance,
            stepBack: stepBack, backWallDistance: back, forwardWallDistance: forward,
            usableBackwardTravel: usable, maximumSubjectDistance: usable.map { centerDepth + $0 }, canStepBack: canStep,
            subjectFullyInFrame: fullyFramed, subjectInsideRoom: subjectInside, cameraInsideRoom: cameraInside,
            obstructedByWall: obstructed, geometryComplete: complete, roomIntrusions: intrusions.sorted(),
            fits: fullyFramed && complete && cameraInside && subjectInside && !obstructed, warnings: warnings)
    }
}

private extension SIMD4 where Scalar == Float { var xyz: SIMD3<Float> { SIMD3(x, y, z) } }
private extension SIMD3 where Scalar == Float { var finite: Bool { x.isFinite && y.isFinite && z.isFinite } }
