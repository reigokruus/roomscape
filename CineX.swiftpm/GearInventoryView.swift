import SwiftUI

struct GearInventoryView: View {
    @State private var items: [GearItem] = []
    @State private var adding = false
    /// The item being edited (tap a row).
    @State private var editing: GearItem?

    private var grouped: [(GearCategory, [GearItem])] {
        GearCategory.allCases.compactMap { cat in
            let catItems = items.filter { $0.category == cat }
            return catItems.isEmpty ? nil : (cat, catItems)
        }
    }

    var body: some View {
        List {
            if items.isEmpty {
                ContentUnavailableView(
                    "No gear yet",
                    systemImage: "camera.on.rectangle",
                    description: Text("Add the camera, lights and other equipment you own and RoomScape will use it when planning your shoot.")
                )
            } else {
                ForEach(grouped, id: \.0) { category, catItems in
                    Section(category.rawValue) {
                        ForEach(catItems) { item in
                            Button { editing = item } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(item.name)
                                    if !item.notes.isEmpty {
                                        Text(item.notes)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .contentShape(Rectangle())
                            }
                            .tint(.primary)
                        }
                        .onDelete { idx in
                            let ids = idx.map { catItems[$0].id }
                            items.removeAll { ids.contains($0.id) }
                            GearStore.save(items)
                        }
                    }
                }
            }
        }
        .navigationTitle("My Gear")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("Add", systemImage: "plus") { adding = true }
            }
        }
        .sheet(isPresented: $adding) {
            AddGearSheet { item in
                items.append(item)
                GearStore.save(items)
            }
        }
        .sheet(item: $editing) { item in
            AddGearSheet(existing: item) { edited in
                if let i = items.firstIndex(where: { $0.id == edited.id }) { items[i] = edited }
                GearStore.save(items)
            }
        }
        .onAppear { items = GearStore.load() }
    }
}

private struct AddGearSheet: View {
    /// The item to edit, or nil to add a new one.
    let existing: GearItem?
    let onAdd: (GearItem) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var category: GearCategory
    @State private var name: String
    @State private var notes: String

    init(existing: GearItem? = nil, onAdd: @escaping (GearItem) -> Void) {
        self.existing = existing
        self.onAdd = onAdd
        _category = State(initialValue: existing?.category ?? .camera)
        _name = State(initialValue: existing?.name ?? "")
        _notes = State(initialValue: existing?.notes ?? "")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Category", selection: $category) {
                        ForEach(GearCategory.allCases, id: \.self) { cat in
                            Label(cat.rawValue, systemImage: cat.icon).tag(cat)
                        }
                    }
                    .onChange(of: category) { name = "" }
                }

                Section("Quick add") {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 140))], spacing: 8) {
                        ForEach(category.suggestions, id: \.self) { suggestion in
                            Button(suggestion) { name = suggestion }
                                .buttonStyle(.bordered)
                                .tint(name == suggestion ? .accentColor : .secondary)
                        }
                    }
                    .padding(.vertical, 4)
                }

                Section("Name") {
                    TextField("e.g. Ring light", text: $name)
                }

                Section("Notes (optional)") {
                    TextField("e.g. 26 cm, dimmable", text: $notes, axis: .vertical)
                        .lineLimit(2...4)
                }
            }
            .navigationTitle(existing == nil ? "Add Gear" : "Edit Gear")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(existing == nil ? "Add" : "Save") {
                        onAdd(GearItem(id: existing?.id ?? UUID(), category: category,
                                       name: name.trimmingCharacters(in: .whitespaces), notes: notes))
                        dismiss()
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }
}
