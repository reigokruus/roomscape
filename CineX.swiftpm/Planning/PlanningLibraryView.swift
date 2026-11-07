import SwiftUI
import UniformTypeIdentifiers
import RoomPlan

struct PlanningLibraryView: View {
    @State private var projects:[PlanProject] = []
    @State private var importing = false
    @State private var error:String?
    @State private var warnings:[String] = []
    var body:some View {
        List {
            Section {
                Button("Import project or view JSON",systemImage:"square.and.arrow.down") { importing = true }
                Text("Create a project from a saved room: open a scan, then tap Storyboard. Imports include measured geometry, not the USDZ or photos.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("Local planning projects") {
                if projects.isEmpty { Text("No projects yet").foregroundStyle(.secondary) }
                ForEach(projects) { project in
                    NavigationLink { PlanningEditorView(project:project) } label: {
                        VStack(alignment:.leading) {
                            Text(project.name)
                            Text("\(project.shots.count) views · \(project.total,format:.currency(code:"EUR")) allocated").font(.caption)
                        }
                    }
                }
            }
            if !warnings.isEmpty {
                Section("Files needing attention — not overwritten") {
                    ForEach(warnings,id:\.self) { Text($0).font(.caption).foregroundStyle(.red) }
                }
            }
        }
        .navigationTitle("Planning projects")
        .onAppear(perform:reload)
        .fileImporter(isPresented:$importing,allowedContentTypes:[.json]) { result in
            do {
                let url = try result.get()
                let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
                let project = try PlanFiles.newProject(from:PlanFiles.read(url))
                try PlanningStore.disk.save(project); reload()
            } catch { self.error = error.localizedDescription }
        }
        .alert("Planning file",isPresented:Binding(get:{ error != nil },set:{ if !$0 { error = nil } })) {
            Button("OK") { error = nil }
        } message: { Text(error ?? "") }
    }
    private func reload() {
        do { let result = try PlanningStore.disk.list(); projects = result.projects; warnings = result.errors }
        catch { self.error = error.localizedDescription }
    }
}

struct PlanningLaunchView: View {
    let room:CapturedRoom
    let scan:SavedScan
    /// The result screen's Objects list with the user's edits; its changes are applied to the storyboard.
    var objects:[RoomObject] = []
    /// A lighting setup to add as a new view (see `LightingStoryboard`); the storyboard then opens on it.
    var addSetup:LightingSetup? = nil
    @State private var project:PlanProject?
    @State private var shotID:UUID?
    @State private var error:String?
    var body:some View {
        Group {
            if let project { PlanningEditorView(project:project,shotID:shotID) }
            else if let error { ContentUnavailableView("Could not open planning project",systemImage:"exclamationmark.triangle",description:Text(error)) }
            else { ProgressView("Opening storyboard…") }
        }
        .task {
            guard project == nil, error == nil else { return }
            do {
                let files = try PlanningStore.disk.list()
                // Never silently create a fresh project over a corrupt local save.
                guard files.errors.isEmpty else { throw PlanError.invalid("A planning file needs attention. Open Planning projects to inspect it before creating another project.") }
                let saved = files.projects.first(where:{ $0.scanName == scan.name })
                var next = try saved ?? RoomPlanningAdapter.makeProject(room:room,scan:scan)
                let sync = StoryboardObjects.sync(&next,objects:objects,scan:scan)
                var added = false
                if let addSetup {
                    let shot = try LightingStoryboard.shot(from:addSetup,in:next)
                    next.shots.append(shot)
                    shotID = shot.id
                    added = true
                }
                if saved == nil || sync?.changed == true || added {
                    try next.validate()
                    try PlanningStore.disk.save(next)
                }
                if let sync { StoryboardObjects.saveSnapshot(sync.snapshot,for:scan) }
                project = next
            } catch { self.error = error.localizedDescription }
        }
    }
}
