import SwiftUI
import UniformTypeIdentifiers

@MainActor
struct PlanningEditorView: View {
    @StateObject private var store:PlanningStore
    @State private var panel = 0
    @State private var importing = false
    @State private var exporting = false
    @State private var exportDocument:PlanningJSONDocument?
    @State private var editItem:PlanItem?
    @State private var details = false
    @State private var presetName = ""
    @State private var namingPreset = false
    @State private var confirmingDeleteShot = false
    @State private var placeCamera = false
    @State private var search = ""
    @State private var category = "All"
    @State private var recent:[UUID] = []

    init(project:PlanProject) { _store = StateObject(wrappedValue:PlanningStore(project:project)) }
    var body:some View {
        ScrollView {
            VStack(alignment:.leading,spacing:14) {
                Picker("Storyboard view",selection:$store.selectedShotID) {
                    ForEach(store.project.shots) { Text($0.name).tag($0.id) }
                }.pickerStyle(.menu)
                HStack {
                    Label(store.saved ? "Saved on this iPhone" : "Not yet saved",systemImage:store.saved ? "checkmark.circle" : "exclamationmark.circle")
                    Spacer()
                    Text("\(store.project.shots.count)/50 views")
                }.font(.caption).foregroundStyle(.secondary)
                if panel == 1 {
                    PlanningLensPreview(room:store.project.room,shot:store.shot)
                        .aspectRatio(store.shot.camera.sensorWidthMM/store.shot.camera.sensorHeightMM,contentMode:.fit).clipShape(RoundedRectangle(cornerRadius:12))
                    Text("Schematic lens preview — measured envelopes, not a photo, AR or a lighting simulation.").font(.caption).foregroundStyle(.secondary)
                } else {
                    PlanningCanvas(room:store.project.room,shot:store.shot,selectedID:store.selectedItemID,
                                   select:{ store.selectedItemID = $0 },move:{ store.move($0,to:$1) },
                                   placeCamera:placeCamera,cameraPoint:{ point in
                        guard store.project.room.contains(point) else { store.error = "Camera must be inside the scanned room."; return }
                        store.updateShot { $0.camera.position.x = point.x; $0.camera.position.z = point.z }
                        placeCamera = false
                    }).frame(height:290).coordinateSpace(name:"planFloor")
                    Text("Scanned furniture is grey; planned items are orange; target is green. Drag a footprint to move it on a 10 cm grid. These are dimensioned plans, not realistic assets.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Picker("Tools",selection:$panel) {
                    Text("Room").tag(0); Text("Frame").tag(1); Text("Budget").tag(2); Text("Views").tag(3)
                }.pickerStyle(.segmented)
                switch panel {
                case 0: layoutPanel
                case 1: framePanel
                case 2: budgetPanel
                default: storyboardPanel
                }
            }.padding()
        }
        .navigationTitle(store.project.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement:.bottomBar) {
                Button { store.undo() } label:{ Image(systemName:"arrow.uturn.backward") }.disabled(!store.canUndo).accessibilityLabel("Undo")
                Button { store.redo() } label:{ Image(systemName:"arrow.uturn.forward") }.disabled(!store.canRedo).accessibilityLabel("Redo")
                Spacer()
                Menu {
                    Button("Export whole project JSON") { export(.project(store.project)) }
                    Button("Export current view JSON") { export(.shot(room:store.project.room,shot:store.shot)) }
                    Button("Import views or preset JSON") { importing = true }
                    Button("Save again") { store.persist() }
                } label:{ Label("Files",systemImage:"square.and.arrow.up") }
            }
        }
        .onAppear { store.persist() }
        .fileImporter(isPresented:$importing,allowedContentTypes:[.json]) { result in
            do { store.importFile(try result.get()) } catch { store.error = error.localizedDescription }
        }
        .fileExporter(isPresented:$exporting,document:exportDocument,contentType:.json,defaultFilename:"Shootlocal-\(store.project.id.uuidString)") { result in
            if case .failure(let error) = result { store.error = error.localizedDescription }
        }
        .sheet(item:$editItem) { item in
            PlanningItemInspector(item:item,floor:store.project.room.floor,onSave:{ updated in
                let room = store.project.room
                store.updateShot { shot in
                    guard let i = shot.items.firstIndex(where:{ $0.id == updated.id }) else { return }
                    // Rate/name edits must still work when the original scan has overlapping envelopes.
                    if updated.position != shot.items[i].position || updated.size != shot.items[i].size || updated.yaw != shot.items[i].yaw {
                        guard Placement.canPlace(updated,in:room,avoiding:shot.items) else { throw PlanError.invalid("This envelope leaves the room or overlaps another item. Try a smaller move.") }
                    }
                    shot.items[i] = updated
                }
                return takeError()
            },onDelete:{ id in
                store.updateShot { $0.items.removeAll { $0.id == id }; if $0.targetID == id { $0.targetID = nil } }
                return takeError()
            })
        }
        .sheet(isPresented:$details) {
            PlanningShotDetails(projectName:store.project.name,shot:store.shot) { name,shot in
                let id = store.shot.id
                store.change { p in p.name = name; if let i = p.shots.firstIndex(where:{ $0.id == id }) { p.shots[i] = shot } }
                return takeError()
            }
        }
        .alert("Save equipment preset",isPresented:$namingPreset) {
            TextField("Preset name",text:$presetName)
            Button("Save") { store.savePreset(name:presetName) }
            Button("Cancel",role:.cancel) {}
        } message:{ Text("Only planned equipment is captured; furniture, camera framing, subject and notes stay independent.") }
        .confirmationDialog("Delete this storyboard view?",isPresented:$confirmingDeleteShot,titleVisibility:.visible) {
            Button("Delete view",role:.destructive) { store.removeShot() }
        }
        .alert("Change not completed",isPresented:Binding(get:{ store.error != nil },set:{ if !$0 { store.error = nil } })) {
            Button("OK") { store.error = nil }
        } message:{ Text(store.error ?? "") }
    }
    private var layoutPanel:some View {
        VStack(alignment:.leading,spacing:12) {
            Toggle("Tap floor to place shot camera",isOn:$placeCamera)
            if let item = store.shot.items.first(where:{ $0.id == store.selectedItemID }) {
                Button("Edit \(item.name) · dimensions / rate / rotation") { editItem = item }
                Button("Use selected item as frame target") { store.updateShot { $0.targetID = item.id }; panel = 1 }
                Button("Duplicate selected item") { store.add(item) }
            }
            TextField("Quick add: search",text:$search).textFieldStyle(.roundedBorder)
            Picker("Category",selection:$category) {
                Text("All").tag("All")
                ForEach(PlanItemKind.allCases,id:\.rawValue) { Text($0.rawValue.capitalized).tag($0.rawValue) }
            }.pickerStyle(.menu)
            if search.isEmpty && category == "All" && !recent.isEmpty {
                Text("Recently added").font(.caption.bold())
                ScrollView(.horizontal) {
                    HStack { ForEach(recent.compactMap { id in PlanCatalog.items.first(where:{ $0.id == id }) }) { item in
                        Button(item.name) { quickAdd(item) }.buttonStyle(.bordered)
                    } }
                }
            }
            LazyVGrid(columns:[GridItem(.adaptive(minimum:145))],alignment:.leading,spacing:8) {
                ForEach(PlanCatalog.items.filter { (category == "All" || $0.kind.rawValue == category) && (search.isEmpty || $0.name.localizedCaseInsensitiveContains(search)) }) { item in
                    Button { quickAdd(item) } label:{
                        VStack(alignment:.leading,spacing:4) {
                            Label(item.name,systemImage:"plus.circle")
                            Text(String(format:"%.0f × %.0f cm · ~€%.0f/day",item.size.x*100,item.size.z*100,item.dailyRate)).font(.caption2)
                        }.frame(maxWidth:.infinity,alignment:.leading).padding(8)
                    }.buttonStyle(.bordered)
                }
            }
            Menu("Add from My Gear (owned, €0/day)") {
                ForEach(GearStore.load()) { item in Button(item.name) { store.addOwnedGear(item) } }
            }
            Text("Catalog dimensions and EUR/day rates are representative estimates. Owned gear starts with an editable 60 cm footprint; check the real size in the inspector.")
                .font(.caption).foregroundStyle(.secondary)
        }
    }
    private var framePanel:some View {
        VStack(alignment:.leading,spacing:12) {
            Picker("Target",selection:Binding(get:{ store.shot.targetID },set:{ id in store.updateShot { $0.targetID = id } })) {
                Text("Choose target").tag(nil as UUID?)
                ForEach(store.shot.items) { Text($0.name).tag(Optional($0.id)) }
            }
            Picker("Actual focal length (mm)",selection:cameraBinding(\.focalMM)) {
                ForEach(Array(Set([18.0,24,35,50,85,store.shot.camera.focalMM])).sorted(),id:\.self) { Text("\(Int($0)) mm").tag($0) }
            }
            Picker("Sensor (active area)",selection:Binding(get:{ store.shot.camera.sensorWidthMM },set:{ width in
                store.updateShot { $0.camera.sensorWidthMM = width; $0.camera.sensorHeightMM = width == 36 ? 24 : 15.6 }
            })) { Text("Full frame 36 × 24 mm").tag(36.0); Text("APS-C 23.5 × 15.6 mm (nominal)").tag(23.5) }
            Text("Camera yaw \(Int(store.shot.camera.yaw*180 / .pi))°")
            Stepper("Turn camera 5°",value:Binding(get:{ store.shot.camera.yaw*180 / .pi },set:{ degrees in store.updateShot { $0.camera.yaw = degrees * .pi/180 } }),in:-180...180,step:5)
            HStack {
                Text("Above floor (m)")
                TextField("Height",value:Binding(get:{ store.shot.camera.position.y-store.project.room.floor },set:{ height in let y = store.project.room.floor+height; store.updateShot { $0.camera.position.y = y } }),format:.number.precision(.fractionLength(2))).keyboardType(.decimalPad).textFieldStyle(.roundedBorder)
            }
            Button("Aim camera at target") {
                if let item = store.shot.items.first(where:{ $0.id == store.shot.targetID }) {
                    store.updateShot { s in let d = item.position-s.camera.position; s.camera.yaw = atan2(-d.x,-d.z) }
                }
            }
            Button("Move camera on room plan") { panel = 0; placeCamera = true }
            switch framing {
            case .success(let fit):
                Label(fit.fits ? "Fits in this measured plan" : "Not a verified fit — inspect warnings",systemImage:fit.fits ? "checkmark.circle.fill" : "exclamationmark.triangle")
                    .foregroundStyle(fit.fits ? Color.green : Color.orange).font(.headline)
                Text(String(format:"HFOV %.1f° · VFOV %.1f°",fit.horizontalDegrees,fit.verticalDegrees)).font(.caption)
                ForEach(fit.warnings,id:\.self) { Text("• "+$0).font(.caption) }
            case .failure(let error): Text(error.localizedDescription).foregroundStyle(.secondary)
            }
        }
    }
    private var budgetPanel:some View {
        VStack(alignment:.leading,spacing:12) {
            Stepper("Allocated shoot days: \(store.shot.days)",value:Binding(get:{ store.shot.days },set:{ days in store.updateShot { $0.days = days } }),in:1...365)
            ForEach(store.shot.items) { item in
                Button { editItem = item } label:{
                    HStack { Text(item.name); Spacer(); Text(item.dailyRate,format:.currency(code:"EUR")); Text("/day").font(.caption) }
                }
            }
            LabeledContent("This view / day",value:store.shot.dailyTotal.formatted(.currency(code:"EUR")))
            LabeledContent("This view allocation",value:store.shot.total.formatted(.currency(code:"EUR")))
            LabeledContent("All views allocation",value:store.project.total.formatted(.currency(code:"EUR"))).font(.headline)
            Text("Illustrative EUR rental allocation, not a quote. Each view counts its kit separately; shared gear is NOT deduplicated. Excludes crew, tax, transport and deposits. Edit rates to match your supplier.").font(.caption).foregroundStyle(.secondary)
            Divider()
            Text("Reusable equipment setups").font(.headline)
            Button("Save current kit as preset") { presetName = ""; namingPreset = true }
            ForEach(store.project.presets) { preset in
                HStack {
                    VStack(alignment:.leading) { Text(preset.name); Text("\(preset.items.count) items").font(.caption) }
                    Spacer()
                    Menu("Actions") {
                        Button("Replace current equipment with this kit") { store.apply(preset) }
                        Button("Export preset JSON") { export(.preset(preset)) }
                        Button("Delete preset",role:.destructive) { store.change { $0.presets.removeAll { $0.id == preset.id } } }
                    }
                }
            }
            Text("Applying replaces planned equipment only. Subjects and furniture stay. If the kit cannot fit, nothing changes; Undo restores a previous kit.").font(.caption).foregroundStyle(.secondary)
        }
    }
    private var storyboardPanel:some View {
        VStack(alignment:.leading,spacing:12) {
            HStack { Button("New view") { store.newShot() }; Button("Duplicate view") { store.duplicateShot() } }.buttonStyle(.bordered)
            Button("Edit project / view name and notes") { details = true }
            Text(store.shot.notes.isEmpty ? "No notes yet" : store.shot.notes).foregroundStyle(.secondary)
            ForEach(store.project.shots) { shot in
                Button { store.selectedShotID = shot.id } label:{
                    HStack {
                        Image(systemName:shot.id == store.shot.id ? "checkmark.circle.fill" : "rectangle.stack")
                        Text(shot.name); Spacer(); Text("\(Int(shot.camera.focalMM)) mm · \(shot.items.count) items").font(.caption)
                    }
                }
            }
            Button("Delete current view",role:.destructive) { confirmingDeleteShot = true }.disabled(store.project.shots.count == 1)
            Text("Each view owns its camera, lens, subject target, items, rates, days and notes. New view keeps the current scanned-furniture plan; duplicate copies the whole setup. JSON export is your portable backup; no cloud sync.")
                .font(.caption).foregroundStyle(.secondary)
        }
    }
    private var framing:Result<ShotFit,Error> { Result { try ShotFraming.check(shot:store.shot,room:store.project.room) } }
    private func cameraBinding(_ key:WritableKeyPath<ShotCamera,Double>) -> Binding<Double> {
        Binding(get:{ store.shot.camera[keyPath:key] },set:{ value in store.updateShot { $0.camera[keyPath:key] = value } })
    }
    private func takeError() -> String? {
        let message = store.error; store.error = nil; return message
    }
    private func export(_ transfer:PlanTransfer) {
        do { exportDocument = try PlanningJSONDocument(transfer); exporting = true } catch { store.error = error.localizedDescription }
    }
    private func quickAdd(_ item:PlanItem) {
        guard store.add(item) else { return }; recent.removeAll { $0 == item.id }; recent.insert(item.id,at:0); recent = Array(recent.prefix(4))
    }
}
