import Foundation

enum GearCategory: String, CaseIterable, Codable {
    case camera = "Camera"
    case light = "Light"
    case modifier = "Modifier"
    case support = "Support"
    case audio = "Audio"
    case other = "Other"

    var icon: String {
        switch self {
        case .camera:   return "camera"
        case .light:    return "lightbulb"
        case .modifier: return "circle.lefthalf.filled"
        case .support:  return "arrow.up.and.down.and.arrow.left.and.right"
        case .audio:    return "mic"
        case .other:    return "ellipsis.circle"
        }
    }

    var suggestions: [String] {
        switch self {
        case .camera:   return ["iPhone (Pro)", "iPhone", "Action camera", "DSLR", "Mirrorless camera"]
        case .light:    return ["Ring light", "Desk lamp", "LED panel", "Softbox", "Clamp light", "Practical lamp"]
        case .modifier: return ["White sheet / reflector", "Silver reflector", "Gold reflector", "Diffusion fabric", "V-flat"]
        case .support:  return ["Tripod", "Flexible tripod", "Selfie stick", "Desk clamp", "Wall clamp"]
        case .audio:    return ["Built-in mic", "Lavalier mic", "USB microphone", "Shotgun mic"]
        case .other:    return ["Extension cord", "Gaffer tape", "Background stand", "Clamp"]
        }
    }
}

struct GearItem: Identifiable, Codable, Hashable {
    var id: UUID
    var category: GearCategory
    var name: String
    var notes: String

    init(id: UUID = UUID(), category: GearCategory, name: String, notes: String = "") {
        self.id = id
        self.category = category
        self.name = name
        self.notes = notes
    }
}

enum GearStore {
    private static var url: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("gear.json")
    }

    static func load() -> [GearItem] {
        guard let data = try? Data(contentsOf: url),
              let items = try? JSONDecoder().decode([GearItem].self, from: data)
        else { return defaults }
        return items
    }

    static let defaults: [GearItem] = [
        GearItem(category: .camera,   name: "iPhone (Pro)"),
        GearItem(category: .light,    name: "Ring light"),
        GearItem(category: .light,    name: "Desk lamp"),
        GearItem(category: .modifier, name: "White sheet / reflector"),
        GearItem(category: .support,  name: "Tripod"),
    ]

    static func save(_ items: [GearItem]) {
        guard let data = try? JSONEncoder().encode(items) else { return }
        try? data.write(to: url, options: .atomic)
    }
}
