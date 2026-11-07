import RoomPlan
import simd

// MARK: - Scene type

enum SceneType: String, CaseIterable, Identifiable {
    case talkingHead = "Talking Head"
    case interview   = "Interview"
    case vlog        = "Vlog"
    case productShot = "Product Shot"
    case lifestyle   = "Lifestyle"

    var id: String { rawValue }

    var icon: String {
        switch self {
        case .talkingHead: return "person.crop.rectangle"
        case .interview:   return "person.2"
        case .vlog:        return "video"
        case .productShot: return "cube.box"
        case .lifestyle:   return "fork.knife"
        }
    }

    var tagline: String {
        switch self {
        case .talkingHead: return "Single presenter"
        case .interview:   return "Host and guest"
        case .vlog:        return "Presenter to camera"
        case .productShot: return "Object on a surface"
        case .lifestyle:   return "Dining or everyday"
        }
    }
}

// MARK: - Plan output

struct PlacementTip: Identifiable {
    var id = UUID()
    let icon: String
    let label: String
    let instruction: String
}

struct ShootPlan {
    let scene: SceneType
    let intro: String
    let tips: [PlacementTip]
    let notes: [String]
}

// MARK: - Engine

enum ShootPlanner {
    /// - Parameter extraWindowAreas: areas (m²) of windows found from the scan's photos that RoomPlan missed.
    static func plan(scene: SceneType, room: CapturedRoom, gear: [GearItem], extraWindowAreas: [Float] = []) -> ShootPlan {
        let r = RoomFacts(room, extraWindowAreas: extraWindowAreas)
        let g = GearFacts(gear)
        switch scene {
        case .talkingHead: return talkingHead(r, g)
        case .interview:   return interview(r, g)
        case .vlog:        return vlog(r, g)
        case .productShot: return productShot(r, g)
        case .lifestyle:   return lifestyle(r, g)
        }
    }
}

// MARK: - Room analysis

private struct RoomFacts {
    let hasWindows: Bool
    let windowCount: Int
    let largestWindowArea: Float
    let hasTable: Bool
    let hasChair: Bool
    let hasSofa: Bool

    init(_ room: CapturedRoom, extraWindowAreas: [Float] = []) {
        let areas = room.windows.map { $0.dimensions.x * $0.dimensions.y } + extraWindowAreas
        windowCount = areas.count
        hasWindows  = !areas.isEmpty
        largestWindowArea = areas.max() ?? 0

        let cats = Set(room.objects.map { $0.category })
        // RoomPlan reports desks and counters as tables. Storage counts too when its top is at table height
        // (a dresser or sideboard), but not wardrobes or tall shelves. Storage stands on the floor, so its height is its top.
        hasTable = cats.contains(.table)
            || room.objects.contains { $0.category == .storage && (0.6...1.1).contains($0.dimensions.y) }
        hasChair = cats.contains(.chair)
        hasSofa  = cats.contains(.sofa)
    }

    var hasGoodWindowLight: Bool { hasWindows && largestWindowArea > 0.3 }
}

// MARK: - Gear analysis

private struct GearFacts {
    let camera: GearItem?
    let lights: [GearItem]
    let modifier: GearItem?
    let hasTripod: Bool
    let hasRingLight: Bool

    init(_ gear: [GearItem]) {
        camera   = gear.first { $0.category == .camera }
        modifier = gear.first { $0.category == .modifier }
        hasTripod    = gear.contains { $0.category == .support && $0.name.lowercased().contains("tripod") }
        hasRingLight = gear.contains { $0.name.lowercased().contains("ring") }

        let rank: (GearItem) -> Int = { item in
            let n = item.name.lowercased()
            if n.contains("led") || n.contains("panel") { return 4 }
            if n.contains("softbox")                    { return 3 }
            if n.contains("ring")                       { return 2 }
            if n.contains("desk") || n.contains("lamp") { return 1 }
            return 0
        }
        lights = gear.filter { $0.category == .light }.sorted { rank($0) > rank($1) }
    }

    var cameraName: String { camera?.name ?? "Camera" }
    var key: GearItem?  { lights.first }
    var fill: GearItem? { lights.count > 1 ? lights[1] : nil }
    var hair: GearItem? { lights.count > 2 ? lights[2] : nil }
}

// MARK: - Scene plans

private extension ShootPlanner {

    static func talkingHead(_ r: RoomFacts, _ g: GearFacts) -> ShootPlan {
        var tips: [PlacementTip] = []
        var notes: [String] = []

        let cameraInstr = g.hasTripod
            ? "On the tripod, at eye level, slightly off-centre — dead-centre looks flat. Leave a little headroom above the frame."
            : "Prop the phone at eye level on a stack of books or a glass. Keep it landscape for a more professional look."
        tips.append(.init(icon: "camera", label: g.cameraName, instruction: cameraInstr))
        if !g.hasTripod { notes.append("A tripod would make framing and stability much easier.") }

        if r.hasGoodWindowLight {
            tips.append(.init(icon: "sun.max", label: "Window (key light)",
                instruction: "Face the window. Natural window light is soft and flattering — the larger the window the better. Angle yourself 30–45° to it rather than straight on."))
            if let key = g.key {
                tips.append(.init(icon: "lightbulb", label: "\(key.name) (fill)",
                    instruction: "Shadow side of your face, same distance as the window, turned down low. It just lifts the shadows — it shouldn't compete with the window."))
            } else if let mod = g.modifier {
                tips.append(.init(icon: "circle.lefthalf.filled", label: "\(mod.name) (fill)",
                    instruction: "Angle the reflector toward the shadow side of your face. Even a white sheet on a chair bounces enough light to soften the shadows."))
            }
        } else if let key = g.key {
            tips.append(.init(icon: "lightbulb", label: "\(key.name) (key)",
                instruction: "45° to your front-left or front-right, slightly above eye level. This creates the classic Rembrandt triangle on the cheek that reads well on camera."))
            if let fill = g.fill {
                tips.append(.init(icon: "lightbulb", label: "\(fill.name) (fill)",
                    instruction: "Opposite side from the key, same distance, 1–2 stops dimmer. Softens shadows without flattening your face."))
            } else if let mod = g.modifier {
                tips.append(.init(icon: "circle.lefthalf.filled", label: "\(mod.name) (fill)",
                    instruction: "Shadow side, angled to bounce the key light back in. Silver reflects sharper; white gives a softer fill."))
            } else {
                notes.append("No fill light or reflector. Turn the key closer to on-axis to reduce shadow contrast.")
            }
        } else {
            notes.append("No dedicated light. Face a window, or place any lamp at 45° to your face. Avoid a bright window behind you — it will silhouette you.")
        }

        if let hair = g.hair {
            tips.append(.init(icon: "lightbulb", label: "\(hair.name) (separation)",
                instruction: "Behind you and slightly above, aimed at the top of your head and shoulders. Even at low power it separates you from the background and adds depth."))
        }

        return ShootPlan(scene: .talkingHead,
            intro: "Single presenter facing the camera. The goal is clean, even light on the face with enough depth to avoid looking flat. Eye level is essential — shooting up or down even a little changes the feel dramatically.",
            tips: tips, notes: notes)
    }

    static func interview(_ r: RoomFacts, _ g: GearFacts) -> ShootPlan {
        var tips: [PlacementTip] = []
        var notes: [String] = []

        let cameraInstr = g.hasTripod
            ? "On the tripod, at eye level, centred between both subjects for a two-shot. For a more polished edit, shoot each person separately at the same angle and cut between them."
            : "Prop the phone at eye level facing both people. If the room is tight, go wide and crop in post."
        tips.append(.init(icon: "camera", label: g.cameraName, instruction: cameraInstr))
        if !g.hasTripod { notes.append("Two angles cut together would elevate this — even a second phone on a glass works.") }

        if r.hasGoodWindowLight {
            tips.append(.init(icon: "sun.max", label: "Window (key light)",
                instruction: "Seat both subjects angled toward the window. Both faces should be lit equally — avoid seating anyone with the window directly behind them."))
            if let fill = g.key {
                tips.append(.init(icon: "lightbulb", label: "\(fill.name) (fill)",
                    instruction: "Shadow side, low power — just enough to lift the dark half of each face without fighting the window."))
            } else if let mod = g.modifier {
                tips.append(.init(icon: "circle.lefthalf.filled", label: "\(mod.name) (fill)",
                    instruction: "Shadow side, angled to catch window light and throw it back at both faces."))
            }
        } else if let key = g.key {
            tips.append(.init(icon: "lightbulb", label: "\(key.name) (key)",
                instruction: "Front-centre, slightly above eye level, equidistant between both subjects. Front-centre is less dramatic than 45° but ensures both faces are lit evenly."))
            if let fill = g.fill {
                tips.append(.init(icon: "lightbulb", label: "\(fill.name) (background)",
                    instruction: "Aim it at the wall behind the subjects. This separates them from the background and adds depth without introducing a competing facial light source."))
            } else if let mod = g.modifier {
                tips.append(.init(icon: "circle.lefthalf.filled", label: "\(mod.name) (fill)",
                    instruction: "Behind the camera, angled to fill shadows on both faces symmetrically."))
            }
        } else {
            notes.append("No dedicated light. Try to place both subjects near a window or under an overhead light — even overhead room light is better than nothing.")
        }

        if let hair = g.hair {
            tips.append(.init(icon: "lightbulb", label: "\(hair.name) (rim/separation)",
                instruction: "Behind both subjects, aimed at the edge of their shoulders and hair. Keep it subtle — it's a production detail, not a main source."))
        }

        if r.hasChair || r.hasSofa {
            tips.append(.init(icon: "person.2", label: "Seating",
                instruction: "Seat subjects at the same height so neither looks dominant. A slight angle toward each other — rather than both facing camera — looks more natural and conversational."))
        }

        return ShootPlan(scene: .interview,
            intro: "Two-person setup. The main challenge is lighting both faces evenly — a single off-axis light that works for one person will shadow the other. Front or window light solves this cleanly.",
            tips: tips, notes: notes)
    }

    static func vlog(_ r: RoomFacts, _ g: GearFacts) -> ShootPlan {
        var tips: [PlacementTip] = []
        var notes: [String] = []

        let cameraInstr = g.hasTripod
            ? "On the tripod, at eye level, arm's length away (roughly 60–80 cm). Slightly above eye level and tilted down gives the classic vlog look."
            : "Hold the phone at arm's length, slightly above your face, tilted down. This is the standard hand-held vlog angle — the slight down-angle is flattering."
        tips.append(.init(icon: "camera", label: g.cameraName, instruction: cameraInstr))

        if g.hasRingLight, let rl = g.lights.first(where: { $0.name.lowercased().contains("ring") }) {
            tips.append(.init(icon: "lightbulb", label: "\(rl.name) (key)",
                instruction: "Directly behind the camera at the same height, so the camera shoots through the ring. This gives even, shadow-free light and the characteristic bright catchlights in the eyes that read as 'YouTube quality'."))
        } else if r.hasGoodWindowLight {
            tips.append(.init(icon: "sun.max", label: "Window (key light)",
                instruction: "Face the window and put the camera between you and the window, slightly to one side. Soft daylight is the best vlog light — it needs no colour correction."))
        } else if let key = g.key {
            tips.append(.init(icon: "lightbulb", label: "\(key.name) (key)",
                instruction: "30° to the front-side of your face, just above eye level. Closer to on-axis than a dramatic shoot — vlogs look better with flatter, brighter light."))
            if let mod = g.modifier {
                tips.append(.init(icon: "circle.lefthalf.filled", label: "\(mod.name) (fill)",
                    instruction: "Shadow side, angled to soften the key. For vlogs, low contrast reads as more approachable."))
            }
        } else {
            notes.append("No dedicated light. Face the brightest window or any lamp. Avoid overhead-only lighting — it creates unflattering under-eye shadows.")
        }

        tips.append(.init(icon: "rectangle.on.rectangle", label: "Background",
            instruction: "Stay 1–2 m from the wall behind you so it goes slightly soft. Clear anything distracting — a plain wall, bookshelf or simple props works well. Avoid very bright backgrounds that compete with your face."))

        return ShootPlan(scene: .vlog,
            intro: "Presenter talking directly to camera. The key goal is bright, even light on the face and a clean background. Ring light on-axis is the classic solution; a large window is even better.",
            tips: tips, notes: notes)
    }

    static func productShot(_ r: RoomFacts, _ g: GearFacts) -> ShootPlan {
        var tips: [PlacementTip] = []
        var notes: [String] = []

        let cameraInstr = g.hasTripod
            ? "On the tripod, same height as the product or slightly above and tilted down at 30°. Lock the exposure and white balance before shooting — tap and hold to lock on iPhone."
            : "Prop the phone facing down at the product at a slight angle. Stability is critical for product shots — improvise with books or a box if needed."
        tips.append(.init(icon: "camera", label: g.cameraName, instruction: cameraInstr))
        if !g.hasTripod { notes.append("A tripod is very helpful here for sharp, consistent shots.") }

        let surfaceInstr = r.hasTable
            ? "Place the product on the existing table or desk. Lay a plain white card, sheet or dark fabric underneath to isolate it from the table texture. White is clean and commercial; dark is moody and premium."
            : "Find a flat, clean surface — the floor works if the camera angle allows. A plain white sheet underneath keeps the focus on the product."
        tips.append(.init(icon: "square.on.square", label: "Surface", instruction: surfaceInstr))

        if r.hasGoodWindowLight {
            tips.append(.init(icon: "sun.max", label: "Window (key light)",
                instruction: "Position the product at 45–90° to the window so the light rakes across it and reveals texture. Diffuse harsh direct sun with a white sheet or tissue taped over the window."))
            if let mod = g.modifier {
                tips.append(.init(icon: "circle.lefthalf.filled", label: "\(mod.name) (fill)",
                    instruction: "Opposite the window, as close to the product as the frame allows. Even a white sheet of paper propped up does the job."))
            } else {
                notes.append("A white card or sheet on the shadow side would fill the under-shadows noticeably.")
            }
        } else if let key = g.key {
            tips.append(.init(icon: "lightbulb", label: "\(key.name) (key)",
                instruction: "45° front-side of the product, slightly above. If you can diffuse it — shine it through a white sheet — the light will be much softer and more product-photography appropriate."))
            if let mod = g.modifier {
                tips.append(.init(icon: "circle.lefthalf.filled", label: "\(mod.name) (fill/bounce)",
                    instruction: "Opposite the key, as close to the product as the frame allows. Even a sheet of white paper propped up lifts the shadows."))
            } else if let fill = g.fill {
                tips.append(.init(icon: "lightbulb", label: "\(fill.name) (fill)",
                    instruction: "Opposite the key, turned well down. For a product, you want the fill to lift shadows without killing the shape of the object."))
            }
        } else {
            notes.append("No dedicated light. Use window light if at all possible — it's diffuse and shows product texture beautifully. A desk lamp can work in a pinch, aimed through tracing paper.")
        }

        return ShootPlan(scene: .productShot,
            intro: "Object on a surface, cleanly lit. The two priorities are a plain background to isolate the product, and soft, directional light that reveals its shape and texture without harsh shadows.",
            tips: tips, notes: notes)
    }

    static func lifestyle(_ r: RoomFacts, _ g: GearFacts) -> ShootPlan {
        var tips: [PlacementTip] = []
        let notes: [String] = []

        let cameraInstr = g.hasTripod
            ? "On the tripod, eye level or slightly above. Leave more room in the frame than you think — lifestyle shots breathe better with space."
            : "Hand-held or propped at eye level. Looser framing than a formal shoot — a little movement feels natural here."
        tips.append(.init(icon: "camera", label: g.cameraName, instruction: cameraInstr))

        if r.hasGoodWindowLight {
            tips.append(.init(icon: "sun.max", label: "Window (ambient)",
                instruction: "Let the window light fill the scene and place subjects near it. Lifestyle shots work best with soft, directionless ambient light rather than dramatic one-source setups."))
        }

        let deskLamp = g.lights.first { $0.name.lowercased().contains("desk") || $0.name.lowercased().contains("lamp") }
        if let lamp = deskLamp {
            tips.append(.init(icon: "lightbulb", label: "\(lamp.name) (practical)",
                instruction: "Leave it in the shot as a 'practical' — a visible light source that also lights the scene. It adds warmth and authenticity. Warm bulbs (2700–3000 K) look most natural."))
        } else if let key = g.key {
            tips.append(.init(icon: "lightbulb", label: "\(key.name) (ambient fill)",
                instruction: "Bounce it off the ceiling or a white wall rather than aiming it directly at subjects. This creates a soft, ambient wrap rather than a harsh source."))
        }

        if g.hasRingLight, deskLamp != nil, let rl = g.lights.first(where: { $0.name.lowercased().contains("ring") }) {
            tips.append(.init(icon: "lightbulb", label: "\(rl.name) (subtle fill)",
                instruction: "Turn it right down (20–30%) and use it to lift the overall exposure without overpowering the practical light. If it looks like a ring-light setup, it's too bright."))
        }

        if r.hasTable {
            tips.append(.init(icon: "square.on.square", label: "Table / surface",
                instruction: "Style the table with props relevant to the scene — a plate, a cup, a book. One or two well-placed props read better than a bare table or a cluttered one."))
        }

        return ShootPlan(scene: .lifestyle,
            intro: "Dining, cooking, or everyday living scene. Lifestyle shots work best with soft, slightly warm ambient light — think magazine spread, not product photo. Practical lights in-shot add authenticity.",
            tips: tips, notes: notes)
    }
}
