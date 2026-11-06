import SwiftUI

struct GearInventoryView: View {
    @State private var items: [GearItem] = []
    @State private var adding = false

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
                    description: Text("Add the camera, lights and other equipment you own and CineX will use it when planning your shoot.")
                )
            } else {
                ForEach(grouped, id: \.0) { category, catItems in
                    Section(category.rawValue) {
                        ForEach(catItems) { item in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.name)
                                if !item.notes.isEmpty {
                                    Text(item.notes)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
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
        .onAppear { items = GearStore.load() }
    }
}

private struct AddGearSheet: View {
    let onAdd: (GearItem) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var category: GearCategory = .camera
    @State private var name = ""
    @State private var notes = ""

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
            .navigationTitle("Add Gear")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        onAdd(GearItem(category: category, name: name, notes: notes))
                        dismiss()
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }
}
