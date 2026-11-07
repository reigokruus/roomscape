import SwiftUI
import UniformTypeIdentifiers

@MainActor
final class PlanningStore: ObservableObject {
    static var disk:PlanDiskStore {
        PlanDiskStore(directory:FileManager.default.urls(for:.documentDirectory,in:.userDomainMask)[0].appendingPathComponent("PlanningProjects",isDirectory:true))
    }
    @Published private(set) var project:PlanProject
    @Published var selectedShotID:UUID
    @Published var selectedItemID:UUID?
    @Published var error:String?
    @Published private(set) var saved = false
    private var history = PlanHistory()
    var shot:PlanShot { project.shots.first(where:{ $0.id == selectedShotID }) ?? project.shots[0] }
    var canUndo:Bool { !history.undo.isEmpty }
    var canRedo:Bool { !history.redo.isEmpty }
    init(project:PlanProject) { self.project = project; selectedShotID = project.shots[0].id }
    func persist() { do { try Self.disk.save(project); saved = true } catch { self.error = error.localizedDescription; saved = false } }
    func change(_ edit:(inout PlanProject) throws -> Void) {
        do {
            var next = project; try edit(&next); try next.validate()
            guard next != project else { return }
            try Self.disk.save(next)
            history.record(project); project = next; saved = true; reconcileSelection()
        } catch { self.error = error.localizedDescription }
    }
    func updateShot(_ edit:(inout PlanShot) throws -> Void) {
        let id = shot.id
        change { p in if let i = p.shots.firstIndex(where:{ $0.id == id }) { try edit(&p.shots[i]) } }
    }
    private func reconcileSelection() {
        if !project.shots.contains(where: { $0.id == selectedShotID }) { selectedShotID = project.shots[0].id }
        if !shot.items.contains(where: { $0.id == selectedItemID }) { selectedItemID = nil }
    }
    func undo() { restore(undo:true) }
    func redo() { restore(undo:false) }
    private func restore(undo:Bool) {
        var nextHistory = history
        let next = undo ? nextHistory.undo(current:project) : nextHistory.redo(current:project)
        guard let next else { return }
        do { try Self.disk.save(next); history = nextHistory; project = next; saved = true; reconcileSelection() }
        catch { self.error = error.localizedDescription }
    }
    @discardableResult
    func add(_ template:PlanItem) -> Bool {
        do {
            var item = template; item.id = UUID(); item.source = .planned
            item.position = try Placement.quickPosition(for:item,in:project.room,avoiding:shot.items)
            updateShot { $0.items.append(item) }
            guard shot.items.contains(where:{ $0.id == item.id }) else { return false }
            selectedItemID = item.id; return true
        } catch { self.error = error.localizedDescription; return false }
    }
    func move(_ id:UUID,to point:PlanPoint) {
        let room = project.room
        updateShot { shot in
            guard let i = shot.items.firstIndex(where:{ $0.id == id }) else { return }
            var item = shot.items[i]; item.position = Placement.snapped(point)
            guard Placement.canPlace(item,in:room,avoiding:shot.items) else { throw PlanError.invalid("That position overlaps an item or leaves the scanned room. The previous position was kept.") }
            shot.items[i] = item
        }
    }
    func duplicateShot() {
        let copy = shot.duplicated()
        change { $0.shots.append(copy) }
        if project.shots.contains(where:{ $0.id == copy.id }) { selectedShotID = copy.id }
    }
    func newShot() {
        var next = shot
        next.id = UUID(); next.name = "View \(project.shots.count+1)"; next.notes = ""; next.days = 1
        next.items = shot.items.filter { $0.source == .scanned }; next.targetID = next.items.first?.id
        // Fresh IDs so every view is independently addressable.
        next = next.duplicated(); next.name = "View \(project.shots.count+1)"
        change { $0.shots.append(next) }
        if project.shots.contains(where: { $0.id == next.id }) { selectedShotID = next.id }
    }
    func removeShot() {
        let id = shot.id
        change { p in
            guard p.shots.count > 1 else { throw PlanError.invalid("Keep at least one storyboard view.") }
            p.shots.removeAll { $0.id == id }
        }
    }
    func importFile(_ url:URL) {
        let granted = url.startAccessingSecurityScopedResource(); defer { if granted { url.stopAccessingSecurityScopedResource() } }
        do { let transfer = try PlanFiles.read(url); change { $0 = try PlanFiles.append(transfer,to:$0) } }
        catch { self.error = error.localizedDescription }
    }
    func apply(_ preset:EquipmentPreset) {
        let room = project.room
        updateShot { $0 = try preset.applying(to:$0,room:room) }
    }
    func savePreset(name:String) {
        let preset = EquipmentPreset.capture(name:name,shot:shot,room:project.room)
        change { p in
            guard !preset.items.isEmpty else { throw PlanError.invalid("Add planned equipment before saving a setup preset.") }
            p.presets.append(preset)
        }
    }
    func addOwnedGear(_ gear:GearItem) {
        // Dimensions are editable planning envelopes, never inferred manufacturer specifications.
        let kind:PlanItemKind
        switch gear.category {
        case .camera: kind = .camera
        case .light,.modifier: kind = .light
        case .support: kind = .support
        case .audio: kind = .audio
        case .other: kind = .other
        }
        add(.init(name:gear.name,kind:kind,position:.zero,size:.init(x:0.6,y:1.5,z:0.6)))
    }
}

struct PlanningJSONDocument: FileDocument {
    static var readableContentTypes:[UTType] { [.json] }
    let data:Data
    init(_ transfer:PlanTransfer) throws { data = try PlanFiles.encode(transfer) }
    init(configuration:ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data(); _ = try PlanFiles.decode(data)
    }
    func fileWrapper(configuration:WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents:data) }
}
