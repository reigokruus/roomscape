import SwiftUI

/// Objects in the scan: find more with Claude, select, remove / put back, move (tap the floor) and turn movable
/// things, and see where the room's lights and power sockets are.
struct ObjectsPanel: View {
    @Binding var objects: [RoomObject]
    @Binding var selected: UUID?
    let floorY: Float
    let claudeChecked: Bool
    let finding: String?
    let error: String?
    let onFind: () -> Void

    @State private var showingKey = false

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
                        if ClaudeKeyStore.key == nil { showingKey = true } else { onFind() }
                    } label: {
                        Label(claudeChecked ? "Look again with Claude" : "Find objects, sockets and lights with Claude",
                              systemImage: "sparkle.magnifyingglass")
                    }
                }
                if let error {
                    Text(error).font(.caption).foregroundStyle(.red)
                }
                Button("Claude API key…") { showingKey = true }
                    .font(.caption)
            } footer: {
                Text("Claude looks at up to 30 of the scan's photos (about 5 requests on your API key). RoomPlan's furniture is already listed without it.")
            }

            if let id = selected, let i = objects.firstIndex(where: { $0.id == id }), !objects[i].kind.isMarker {
                Section("Selected: \(objects[i].label)") {
                    Text(objects[i].removed ? "Removed from the room." : "Tap the floor in the 3D view to move it there.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                    if !objects[i].removed {
                        VStack(alignment: .leading, spacing: 2) {
                            HStack {
                                Text("Turn")
                                Spacer()
                                Text("\(Int((objects[i].turn * 180 / .pi).rounded()))°").monospacedDigit().foregroundStyle(.secondary)
                            }
                            Slider(value: $objects[i].turn, in: -Float.pi...Float.pi, step: Float.pi / 36)
                        }
                    }
                    HStack {
                        Button(objects[i].removed ? "Put back" : "Remove") { objects[i].removed.toggle() }
                        Spacer()
                        if objects[i].isEdited {
                            Button("Reset") {
                                objects[i].removed = false
                                objects[i].offset = .zero
                                objects[i].turn = 0
                            }
                        }
                    }
                    .buttonStyle(.bordered)
                }
            }

            Section("Furniture and things") {
                if movable.isEmpty {
                    Text("Nothing found yet.").foregroundStyle(.secondary)
                }
                ForEach(movable) { o in
                    row(o, detail: o.sizeText)
                }
            }

            if !lights.isEmpty {
                Section("Room lights") {
                    ForEach(lights) { o in row(o, detail: height(o)) }
                }
            }
            if !power.isEmpty {
                Section("Power") {
                    ForEach(power) { o in row(o, detail: height(o)) }
                }
            }
        }
        .listStyle(.insetGrouped)
        .sheet(isPresented: $showingKey) { ClaudeKeySheet() }
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
    }
}

/// Enter, replace or remove the Claude API key (kept in the Keychain on this phone only).
struct ClaudeKeySheet: View {
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
                    }
                    .disabled(key.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }
}
