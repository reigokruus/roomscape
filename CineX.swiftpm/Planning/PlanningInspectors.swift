import SwiftUI

struct PlanningItemInspector: View {
    @State var item:PlanItem
    let floor:Double
    let onSave:(PlanItem) -> String?
    let onDelete:(UUID) -> String?
    @Environment(\.dismiss) private var dismiss
    @State private var error:String?
    @State private var deleting = false
    var body:some View {
        NavigationStack {
            Form {
                Section("Item") {
                    TextField("Name",text:$item.name)
                    Text(item.source == .scanned ? "Measured RoomPlan envelope; edits affect this plan only, not the saved scan." : "Planned envelope. Dimensions and rate are estimates until you verify them.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Section("Dimensions in metres") {
                    number("Width",value:$item.size.x)
                    number("Height",value:$item.size.y)
                    number("Depth",value:$item.size.z)
                }
                Section("Placement") {
                    number("World X (m)",value:$item.position.x)
                    number("World Z (m)",value:$item.position.z)
                    number("Above floor (m)",value:Binding(get:{ item.position.y-floor },set:{ item.position.y = floor+$0 }))
                    Stepper("Rotation: \(Int(item.yaw*180 / .pi))°",value:Binding(get:{ item.yaw*180 / .pi },set:{ item.yaw = $0 * .pi/180 }),in:-180...180,step:15)
                }
                Section("Budget") { number("EUR / day",value:$item.dailyRate) }
                Button("Remove from this view",role:.destructive) { deleting = true }
            }
            .navigationTitle("Item inspector").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement:.cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement:.confirmationAction) { Button("Save") {
                    do { try PlanValidation.items([item]); if let failure = onSave(item) { self.error = failure } else { dismiss() } }
                    catch { self.error = error.localizedDescription }
                } }
            }
            .confirmationDialog("Remove this item?",isPresented:$deleting,titleVisibility:.visible) {
                Button("Remove",role:.destructive) { if let failure = onDelete(item.id) { self.error = failure } else { dismiss() } }
            }
            .alert("Invalid item",isPresented:Binding(get:{ error != nil },set:{ if !$0 { error = nil } })) { Button("OK") { error = nil } } message:{ Text(error ?? "") }
        }
    }
    private func number(_ title:String,value:Binding<Double>) -> some View {
        HStack { Text(title); Spacer(); TextField(title,value:value,format:.number.precision(.fractionLength(0...2))).multilineTextAlignment(.trailing).keyboardType(.numbersAndPunctuation) }
    }
}

struct PlanningShotDetails: View {
    @State var projectName:String
    @State var shot:PlanShot
    let onSave:(String,PlanShot) -> String?
    @Environment(\.dismiss) private var dismiss
    @State private var error:String?
    var body:some View {
        NavigationStack {
            Form {
                Section("Project") { TextField("Project name",text:$projectName) }
                Section("View") { TextField("View name",text:$shot.name) }
                Section("Notes") { TextEditor(text:$shot.notes).frame(minHeight:180) }
            }
            .navigationTitle("Storyboard details")
            .toolbar {
                ToolbarItem(placement:.cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement:.confirmationAction) { Button("Save") {
                    do { try PlanValidation.text(projectName,max:120); try PlanValidation.shot(shot); if let failure = onSave(projectName,shot) { self.error = failure } else { dismiss() } }
                    catch { self.error = error.localizedDescription }
                } }
            }
            .alert("Invalid details",isPresented:Binding(get:{ error != nil },set:{ if !$0 { error = nil } })) { Button("OK") { error = nil } } message:{ Text(error ?? "") }
        }
    }
}
