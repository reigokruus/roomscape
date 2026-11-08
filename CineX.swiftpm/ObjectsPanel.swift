import SwiftUI

/// Objects in the scan: find more with Claude, select, remove / put back, move (tap the floor) and turn movable
/// things, and see where the room's lights and power sockets are. Swipe a row left to remove it (or put it back),
/// right to reset it; a selected thing's controls open right under its row.
struct ObjectsPanel: View {
    @Binding var objects: [RoomObject]
    @Binding var selected: UUID?
    let floorY: Float
    let claudeChecked: Bool
    let finding: String?
    let error: String?
    let onFind: () -> Void

    @State private var showingKey = false
    /// The key sheet was opened by the Find button: the search starts once a key is saved.
    @State private var findAfterKey = false

    private var movable: [RoomObject] { objects.filter { !$0.kind.isMarker } }
    private var lights: [RoomObject] { objects.filter { $0.kind.isMarker && !$0.kind.isPower } }
    private var power: [RoomObject] { objects.filter { $0.kind.isPower } }

    var body: some View {
        List {
            Section {
                if let finding {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text(finding).font(.callout)
                    }
                } else {
                    Button {
                        if ClaudeKeyStore.key == nil { findAfterKey = true; showingKey = true } else { onFind() }
                    } label: {
                        Label(claudeChecked ? "Look again with Claude" : "Find objects, sockets and lights with Claude",
                              systemImage: "sparkle.magnifyingglass")
                    }
                }
                if let error {
                    Text(error).font(.caption).foregroundStyle(.red)
                }
                Button("Claude API key…") { findAfterKey = false; showingKey = true }
                    .font(.caption)
            } footer: {
                Text("Claude looks at up to 30 of the scan's photos (about 5 requests on your API key). RoomPlan's furniture is already listed without it.")
            }

            Section {
                if movable.isEmpty {
                    Text("Nothing found yet.").foregroundStyle(.secondary)
                }
                ForEach(movable) { o in
                    row(o, detail: o.sizeText)
                    if selected == o.id { editor(o.id) }
                }
            } header: {
                Text("Furniture and things")
            } footer: {
                if !movable.isEmpty {
                    Text("Tap a thing to select it, then tap the floor in the 3D view to move it there. Swipe left to remove it.")
                }
            }

            if !lights.isEmpty {
                Section {
                    ForEach(lights) { o in row(o, detail: height(o)) }
                } header: {
                    Text("Room lights")
                } footer: {
                    Text("Swipe left on one that isn't there or doesn't work.")
                }
            }
            if !power.isEmpty {
                Section("Power") {
                    ForEach(power) { o in row(o, detail: height(o)) }
                }
            }
        }
        .listStyle(.insetGrouped)
        .sheet(isPresented: $showingKey) {
            ClaudeKeySheet(onSaved: findAfterKey ? onFind : nil)
        }
    }

    /// Turn and reset for the selected thing, right under its row.
    @ViewBuilder private func editor(_ id: UUID) -> some View {
        if let i = objects.firstIndex(where: { $0.id == id }) {
            if objects[i].removed {
                Button {
                    objects[i].removed = false
                } label: {
                    Label("Put it back", systemImage: "arrow.uturn.backward")
                }
            } else {
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text("Turn")
                        Spacer()
                        Text("\(Int((objects[i].turn * 180 / .pi).rounded()))°").monospacedDigit().foregroundStyle(.secondary)
                    }
                    Slider(value: $objects[i].turn, in: -Float.pi...Float.pi, step: Float.pi / 36)
                    HStack(spacing: 12) {
                        Button(role: .destructive) {
                            objects[i].removed = true
                        } label: {
                            Label("Remove", systemImage: "trash")
                        }
                        if objects[i].isEdited {
                            Button {
                                reset(id)
                            } label: {
                                Label("Reset", systemImage: "arrow.counterclockwise")
                            }
                        }
                    }
                    .buttonStyle(.bordered)
                    .font(.callout)
                }
                .padding(.leading, 38)
            }
        }
    }

    private func reset(_ id: UUID) {
        guard let i = objects.firstIndex(where: { $0.id == id }) else { return }
        objects[i].removed = false
        objects[i].offset = .zero
        objects[i].turn = 0
    }

    private func height(_ o: RoomObject) -> String {
        "\(Int(((o.center.y - floorY) * 100).rounded())) cm up"
    }

    private func row(_ o: RoomObject, detail: String) -> some View {
        Button {
            selected = selected == o.id ? nil : o.id
        } label: {
            HStack(spacing: 12) {
                Image(systemName: o.kind.icon)
                    .frame(width: 26)
                    .foregroundStyle(o.removed ? Color.secondary : Color.accentColor)
                VStack(alignment: .leading, spacing: 2) {
                    Text(o.label).strikethrough(o.removed)
                    HStack(spacing: 6) {
                        if !detail.isEmpty { Text(detail) }
                        Text(o.source == .roomPlan ? "RoomPlan" : "Claude, \(o.views) photo\(o.views == 1 ? "" : "s")")
                        if o.isEdited && !o.removed { Text("moved") }
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
                Spacer()
                if selected == o.id { Image(systemName: "checkmark").foregroundStyle(.tint) }
            }
        }
        .foregroundStyle(.primary)
        // No destructive role: the row stays (struck through) so it can be put back.
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            Button {
                if let i = objects.firstIndex(where: { $0.id == o.id }) { objects[i].removed.toggle() }
            } label: {
                Label(o.removed ? "Put back" : "Remove", systemImage: o.removed ? "arrow.uturn.backward" : "trash")
            }
            .tint(o.removed ? .green : .red)
        }
        .swipeActions(edge: .leading) {
            if o.isEdited {
                Button {
                    reset(o.id)
                } label: {
                    Label("Reset", systemImage: "arrow.counterclockwise")
                }
                .tint(.gray)
            }
        }
    }
}

/// Enter, replace or remove the Claude API key (kept in the Keychain on this phone only).
struct ClaudeKeySheet: View {
    /// Run after a key is saved (the action that asked for it), if any.
    var onSaved: (() -> Void)? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var key = ""
    @State private var hasKey = ClaudeKeyStore.key != nil

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    SecureField(hasKey ? "Replace the saved key" : "sk-ant-…", text: $key)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } footer: {
                    Text("Your key from console.anthropic.com. It's stored in this phone's Keychain and only sent to Anthropic's API.")
                }
                if hasKey {
                    Button("Remove the saved key", role: .destructive) {
                        ClaudeKeyStore.delete()
                        hasKey = false
                    }
                }
            }
            .navigationTitle("Claude API key")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        ClaudeKeyStore.save(key)
                        dismiss()
                        if ClaudeKeyStore.key != nil { onSaved?() }
                    }
                    .disabled(key.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }
}
