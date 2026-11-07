import Foundation

enum PlanTransfer: Codable, Equatable {
    case project(PlanProject)
    case shot(room:PlanRoom, shot:PlanShot)
    case preset(EquipmentPreset)
}

struct PlanEnvelope: Codable {
    var format = "shootlocal-native"
    var version = 1
    var content: PlanTransfer
}

enum PlanFiles {
    static let maxBytes = 2_000_000
    static func encode(_ content:PlanTransfer) throws -> Data {
        try validate(content)
        let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted,.sortedKeys]
        let data = try encoder.encode(PlanEnvelope(content:content))
        guard data.count <= maxBytes else { throw PlanError.invalid("Export exceeds the 2 MB limit; export fewer views.") }
        return data
    }
    static func decode(_ data:Data) throws -> PlanTransfer {
        guard data.count <= maxBytes else { throw PlanError.invalid("File exceeds the 2 MB limit.") }
        do {
            let envelope = try JSONDecoder().decode(PlanEnvelope.self,from:data)
            guard envelope.format == "shootlocal-native", envelope.version == 1 else { throw PlanError.invalid("Unsupported file format or version. Choose a native Shootlocal JSON export.") }
            try validate(envelope.content)
            return envelope.content
        } catch let error as PlanError { throw error }
        catch { throw PlanError.invalid("This is not a valid native Shootlocal JSON file. Browser-prototype files use a different format.") }
    }
    static func read(_ url:URL) throws -> PlanTransfer {
        let values = try url.resourceValues(forKeys:[.fileSizeKey])
        guard let count = values.fileSize, count <= maxBytes else { throw PlanError.invalid("Cannot import a file larger than 2 MB.") }
        // Bound the read as well: provider files may change after resourceValues.
        let handle = try FileHandle(forReadingFrom:url); defer { try? handle.close() }
        let data = try handle.read(upToCount:maxBytes+1) ?? Data()
        return try decode(data)
    }
    static func validate(_ transfer:PlanTransfer) throws {
        switch transfer {
        case .project(let project): try project.validate()
        case .shot(let room,let shot): try PlanValidation.room(room); try PlanValidation.shot(shot)
        case .preset(let preset): try PlanValidation.preset(preset)
        }
    }
    static func append(_ transfer:PlanTransfer,to project:PlanProject) throws -> PlanProject {
        try validate(transfer)
        var next = project
        switch transfer {
        case .project(let imported):
            guard imported.room == project.room else { throw PlanError.invalid("Different room. Import this project from the Planning projects library instead.") }
            next.shots += imported.shots.map { $0.duplicated() }
            next.presets += imported.presets.map { preset in var copy = preset; copy.id = UUID(); return copy }
        case .shot(let room,let shot):
            guard room == project.room else { throw PlanError.invalid("This view belongs to another room. Open it in the Planning projects library instead.") }
            next.shots.append(shot.duplicated())
        case .preset(var preset):
            preset.id = UUID(); next.presets.append(preset)
        }
        try next.validate()
        return next
    }
    static func newProject(from transfer:PlanTransfer) throws -> PlanProject {
        try validate(transfer)
        var next:PlanProject
        switch transfer {
        case .project(let project): next = project; next.id = UUID(); next.scanName = nil
        case .shot(let room,let shot): next = .init(name:shot.name,room:room,shots:[shot.duplicated()])
        case .preset: throw PlanError.invalid("Open a room project first to import an equipment preset.")
        }
        try next.validate()
        return next
    }
}

// No imported name or scan reference is used in a filesystem path.
struct PlanDiskStore {
    let directory:URL
    func save(_ project:PlanProject) throws {
        let data = try PlanFiles.encode(.project(project))
        try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
        try data.write(to:url(project.id),options:.atomic)
    }
    func load(_ id:UUID) throws -> PlanProject {
        guard case .project(let project) = try PlanFiles.read(url(id)), project.id == id else { throw PlanError.invalid("Saved project identity mismatch.") }
        return project
    }
    func list() throws -> (projects:[PlanProject],errors:[String]) {
        guard FileManager.default.fileExists(atPath:directory.path) else { return ([],[]) }
        let urls = try FileManager.default.contentsOfDirectory(at:directory,includingPropertiesForKeys:nil)
        var projects:[PlanProject] = [], errors:[String] = []
        for file in urls where file.pathExtension == "json" {
            guard let id = UUID(uuidString:file.deletingPathExtension().lastPathComponent) else { continue }
            do { projects.append(try load(id)) }
            catch { errors.append("\(file.lastPathComponent): \(error.localizedDescription)") }
        }
        return (projects.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending },errors)
    }
    private func url(_ id:UUID) -> URL { directory.appendingPathComponent(id.uuidString).appendingPathExtension("json") }
}

// Bounded value snapshots; failed operations never consume history or mutate the document.
struct PlanHistory {
    private(set) var undo:[PlanProject] = []
    private(set) var redo:[PlanProject] = []
    mutating func record(_ project:PlanProject) { undo.append(project); if undo.count > 30 { undo.removeFirst() }; redo.removeAll() }
    mutating func undo(current:PlanProject) -> PlanProject? {
        guard let previous = undo.popLast() else { return nil }; redo.append(current); return previous
    }
    mutating func redo(current:PlanProject) -> PlanProject? {
        guard let next = redo.popLast() else { return nil }; undo.append(current); return next
    }
}
