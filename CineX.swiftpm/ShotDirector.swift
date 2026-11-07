import Foundation
import simd

/// Asks Claude to set up a shot in the scanned room: where the person sits or stands and which way they face,
/// which of the user's lights go where, the camera's distance, lens and frame, and how much room light and daylight
/// to keep. Claude gets the room as a text floor plan (walls, doors, windows, furniture, room lights, sockets) and
/// the user's gear, and answers in a fixed JSON shape. The answer is checked before it's used: everything is
/// clamped to the lighting panel's ranges, only lights the user owns are used, and a person, light or camera that
/// would end up outside the room is kept inside it. The result is an ordinary `LightingSetup`, fully adjustable.
enum ShotDirector {
    private struct Reply: Decodable {
        struct Light: Decodable {
            let gear: Int
            let type: String
            let role: String
            let power: Double
            let angle: Double
            let distance: Double
            let height: Double
            let kelvin: Double
        }
        let shot: String
        let subjectX: Double
        let subjectZ: Double
        let lookTowardX: Double
        let lookTowardZ: Double
        let pose: String
        let cameraDistance: Double
        let lens: Double
        let vertical: Bool
        let roomLights: Double
        let daylight: Double
        let lights: [Light]
        let explanation: String
        let tips: [String]
    }

    private static func number(_ description: String) -> [String: Any] {
        ["type": "number", "description": description]
    }

    private static let schema: [String: Any] = [
        "type": "object",
        "properties": [
            "shot": ["type": "string", "enum": SceneType.allCases.map(\.rawValue),
                     "description": "The kind of shot this setup is for"],
            "subjectX": number("Where the person stands or sits: x, metres"),
            "subjectZ": number("Where the person stands or sits: z, metres"),
            "lookTowardX": number("A point the person faces (the camera is on this side): x, metres"),
            "lookTowardZ": number("A point the person faces (the camera is on this side): z, metres"),
            "pose": ["type": "string", "enum": ["seated", "standing"]],
            "cameraDistance": number("Camera distance from the face, metres (0.6 to 5)"),
            "lens": number("Full-frame equivalent focal length, mm: 13, 26, 35, 50 or 85"),
            "vertical": ["type": "boolean", "description": "true for an upright 9:16 frame, false for wide 16:9"],
            "roomLights": number("Room's own lights: 0 = off, 1 = as scanned"),
            "daylight": number("Daylight through the windows: 0 = none (curtains closed), 1 = as scanned"),
            "lights": [
                "type": "array",
                "description": "At most four lights; fewer is better when they do the job",
                "items": [
                    "type": "object",
                    "properties": [
                        "gear": ["type": "integer", "description": "Number of the light in the user's gear, or -1 for a generic light (only when the user lists none)"],
                        "type": ["type": "string", "enum": LightType.allCases.map(\.rawValue)],
                        "role": ["type": "string", "description": "Key, Fill, Back, Background, Hair, Kicker or similar"],
                        "power": number("0.05 to 1 of full power"),
                        "angle": number("Degrees around the person: 0 = from the camera's side, 90 = from their left, -90 = their right, 180 = behind them"),
                        "distance": number("Horizontal distance from the person, metres (0.5 to 4)"),
                        "height": number("Height above the floor, metres (0.3 to 3.5)"),
                        "kelvin": number("Colour temperature, 2700 to 6500"),
                    ],
                    "required": ["gear", "type", "role", "power", "angle", "distance", "height", "kelvin"],
                    "additionalProperties": false,
                ] as [String: Any],
            ] as [String: Any],
            "explanation": ["type": "string", "description": "Two or three plain sentences on why this setup works, for a creator, not a film crew"],
            "tips": ["type": "array", "items": ["type": "string"], "description": "Two to five short, practical things to do on the day"],
        ] as [String: Any],
        "required": ["shot", "subjectX", "subjectZ", "lookTowardX", "lookTowardZ", "pose", "cameraDistance", "lens", "vertical",
                     "roomLights", "daylight", "lights", "explanation", "tips"],
        "additionalProperties": false,
    ]

    /// Claude's setup for `scene` in this room (or the kind of shot Claude finds suits the room and gear best,
    /// when nil), starting from `setup` (which keeps the room's light sources and sockets; `refreshRoomLight`
    /// updates them for the new spot afterwards).
    static func propose(scene: SceneType?, note: String, setup: LightingSetup, shell: RoomShell,
                        objects: [RoomObject], gear: [GearItem]) async throws -> LightingSetup {
        let text = instructions(scene: scene, note: note, setup: setup, shell: shell, objects: objects, gear: gear)
        let data = try await ClaudeAPI.askAboutPhotos([], instructions: text, schema: schema, effort: "medium")
        let reply: Reply
        do {
            reply = try JSONDecoder().decode(Reply.self, from: data)
        } catch {
            throw ClaudeAPI.Failure.badReply(error.localizedDescription)
        }
        return apply(reply, to: setup, shell: shell, gear: gear)
    }

    // MARK: The request

    private static func instructions(scene: SceneType?, note: String, setup: LightingSetup, shell: RoomShell,
                                     objects: [RoomObject], gear: [GearItem]) -> String {
        func xz(_ p: SIMD3<Float>) -> String { String(format: "(%.2f, %.2f)", p.x, p.z) }
        func up(_ y: Float) -> String { String(format: "%.2f m", y - shell.floorY) }
        let what: String
        if let scene {
            what = "Plan the lighting and camera for a \(scene.rawValue.lowercased()) shot "
                + "(\(scene.tagline.lowercased())); set \"shot\" to \"\(scene.rawValue)\"."
        } else {
            what = "Pick the kind of shot this room and gear suit best ("
                + SceneType.allCases.map { "\($0.rawValue): \($0.tagline.lowercased())" }.joined(separator: "; ")
                + "), name it in \"shot\", and plan its lighting and camera."
        }
        var s = """
        \(what) The room was scanned with a phone. The user is an everyday creator, not a film crew, and owns the \
        gear listed below.

        Coordinates are metres on the floor plan: x and z; heights are above the floor.

        Room
        """
        if shell.floorOutline.count >= 3 {
            s += "\nFloor outline, in order: " + shell.floorOutline.map(xz).joined(separator: ", ")
        }
        for (i, w) in shell.walls.enumerated() {
            s += "\nWall \(i): \(xz(w.center - w.right * w.halfWidth)) to \(xz(w.center + w.right * w.halfWidth)), "
                + String(format: "%.1f m high", w.halfHeight * 2)
        }
        for o in shell.openings {
            let w = o.wall
            let ends = "\(xz(w.center - w.right * w.halfWidth)) to \(xz(w.center + w.right * w.halfWidth))"
            s += o.isWindow
                ? "\nWindow: \(ends), from \(up(w.center.y - w.halfHeight)) to \(up(w.center.y + w.halfHeight)) high"
                : "\nDoor: \(ends)"
        }
        for w in setup.roomSources where w.isWindow && w.label != "Window" {
            s += "\nWindow (seen in the photos): around \(xz(w.position)), \(up(w.position.y)) high"
        }

        let standing = objects.filter { !$0.removed }
        if !standing.isEmpty {
            s += "\n\nThings in the room"
            for o in standing {
                let c = o.center + o.offset
                if o.kind.isMarker {
                    s += "\n- \(o.label) (\(o.kind.label.lowercased())) at \(xz(c)), \(up(c.y)) high"
                } else {
                    s += "\n- \(o.label) (\(o.kind.label.lowercased())): centre \(xz(c)), "
                        + String(format: "%.1f × %.1f m footprint, %.1f m tall, turned %.0f°",
                                 o.halfExtents.x * 2, o.halfExtents.z * 2, o.halfExtents.y * 2,
                                 (o.yaw + o.turn) * 180 / .pi)
                }
            }
        }

        let lights = gear.filter { $0.category == .light }
        s += "\n\nThe user's lights"
        if lights.isEmpty {
            s += "\nNone listed: use generic lights (gear -1) with a fitting type, at most two."
        } else {
            for (i, g) in lights.enumerated() {
                let t = LightType.guess(from: g.name)
                s += "\n\(i): \(g.name) (\(t.rawValue), about \(Int(t.luxAt1m)) lux at 1 m at full power, "
                    + "\(Int(t.beamDegrees))° beam)"
            }
        }
        let others = gear.filter { $0.category != .light }
        if !others.isEmpty {
            s += "\nOther gear: " + others.map { "\($0.name) (\($0.category.rawValue.lowercased()))" }.joined(separator: ", ")
        }
        s += String(format: "\n\nThe person is now at (%.2f, %.2f).", setup.subjectX, setup.subjectZ)
        let trimmed = note.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { s += "\nWhat the user wants: \(trimmed)" }

        s += """


        How the answer is used
        - The person is placed at subjectX, subjectZ and faces lookToward; the camera stands cameraDistance metres \
        from their face in that direction, at face height (seated 1.18 m, standing 1.62 m). They may sit on a chair \
        or sofa from the list.
        - Each light stands at its angle and distance around the person and always aims at their face. Use only the \
        user's lights, each at most once, by number.
        - Keep the person, the camera and every light stand inside the floor outline and clear of furniture.

        Good practice: leave at least a metre between the person and the wall behind them, so the background falls \
        off; choose a tidy, interesting background; don't put a bright window straight behind the person unless the \
        user wants a silhouette; use a window as soft key or fill light when there is one; turn room lights down when \
        they would mix colours or flatten the light; place lights where a socket can reach them. Mention reflectors or \
        other gear in the tips when they help.
        """
        return s
    }

    // MARK: Using the answer

    private static func apply(_ r: Reply, to base: LightingSetup, shell: RoomShell, gear: [GearItem]) -> LightingSetup {
        var s = base
        let outline = shell.floorOutline.map { SIMD2($0.x, $0.z) }
        func inside(_ p: SIMD3<Float>) -> Bool { contains(outline, SIMD2(p.x, p.z)) }
        func clamp(_ v: Double, _ lo: Float, _ hi: Float) -> Float { min(hi, max(lo, Float(v))) }

        let spot = SIMD3(Float(r.subjectX), s.floorY, Float(r.subjectZ))
        if inside(spot) {
            s.subjectX = spot.x
            s.subjectZ = spot.z
        }
        let toward = SIMD2(Float(r.lookTowardX) - s.subjectX, Float(r.lookTowardZ) - s.subjectZ)
        if simd_length(toward) > 0.05 { s.facing = atan2(toward.x, toward.y) * 180 / .pi }
        s.pose = r.pose == "standing" ? .standing : .seated
        s.vertical = r.vertical
        s.focalLength = LightingSetup.lenses.min { abs($0 - Float(r.lens)) < abs($1 - Float(r.lens)) } ?? 26
        s.roomDimmer = clamp(r.roomLights, 0, 1)
        s.daylight = clamp(r.daylight, 0, 1)
        s.exposureEV = 0

        s.cameraDistance = clamp(r.cameraDistance, 0.6, 5)
        while s.cameraDistance > 0.6, !inside(s.cameraPosition) { s.cameraDistance -= 0.1 }

        let owned = gear.filter { $0.category == .light }
        var used = Set<Int>()
        var lights: [ShotLight] = []
        for l in r.lights where lights.count < LightingSetup.maxLights {
            let type: LightType, name: String
            if l.gear >= 0, l.gear < owned.count, !used.contains(l.gear) {
                used.insert(l.gear)
                type = .guess(from: owned[l.gear].name)
                name = owned[l.gear].name
            } else if owned.isEmpty, let generic = LightType(rawValue: l.type) {
                type = generic
                name = generic.label
            } else {
                continue
            }
            let role = l.role.trimmingCharacters(in: .whitespacesAndNewlines)
            var light = ShotLight(name: role.isEmpty ? name : "\(role.prefix(1).uppercased() + role.dropFirst()): \(name)",
                                  type: type, power: clamp(l.power, 0.05, 1), kelvin: clamp(l.kelvin, 2700, 6500),
                                  angle: clamp(l.angle, -180, 180), distance: clamp(l.distance, 0.5, 4),
                                  height: clamp(l.height, 0.3, 3.5))
            while light.distance > 0.5, !inside(s.position(of: light)) { light.distance -= 0.1 }
            lights.append(light)
        }
        s.lights = lights

        s.planTitle = SceneType(rawValue: r.shot)?.rawValue
        let note = r.explanation.trimmingCharacters(in: .whitespacesAndNewlines)
        s.planNote = note.isEmpty ? nil : note
        s.planTips = r.tips.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }.prefix(6).map { $0 }
        return s
    }

    /// Point in polygon (even-odd); true when there's no outline to test against.
    private static func contains(_ polygon: [SIMD2<Float>], _ p: SIMD2<Float>) -> Bool {
        guard polygon.count >= 3 else { return true }
        var inside = false
        var j = polygon.count - 1
        for i in polygon.indices {
            let a = polygon[i], b = polygon[j]
            if (a.y > p.y) != (b.y > p.y), p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x { inside.toggle() }
            j = i
        }
        return inside
    }
}
