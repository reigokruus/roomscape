import SwiftUI
import RoomPlan

struct ShootPlannerView: View {
    let room: CapturedRoom
    /// Areas of windows found from the scan's photos that RoomPlan missed (see `WindowDetection`).
    var extraWindowAreas: [Float] = []
    /// The Objects list with the user's edits (removed furniture doesn't count); empty means RoomPlan's objects.
    var objects: [RoomObject] = []
    @State private var scene: SceneType = .talkingHead
    @State private var gear: [GearItem] = []

    private var plan: ShootPlan {
        ShootPlanner.plan(scene: scene, room: room, gear: gear, extraWindowAreas: extraWindowAreas, objects: objects)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                ScenePickerRow(selected: $scene)

                Text(plan.intro)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal)

                // The plan works with nothing but a phone and the room's light; gear makes it specific.
                if gear.isEmpty {
                    NavigationLink(destination: GearInventoryView()) {
                        HStack(spacing: 12) {
                            Image(systemName: "camera.on.rectangle")
                                .font(.title3)
                                .foregroundStyle(.tint)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Planning with just your phone")
                                    .font(.subheadline.weight(.semibold))
                                Text("Add the lights and camera you own for tips that use them.")
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer(minLength: 0)
                            Image(systemName: "chevron.right")
                                .font(.footnote.weight(.semibold))
                                .foregroundStyle(.tertiary)
                        }
                        .padding(12)
                        .background(Color.accentColor.opacity(0.1), in: RoundedRectangle(cornerRadius: 12))
                    }
                    .buttonStyle(.plain)
                    .padding(.horizontal)
                }

                TipList(tips: plan.tips)

                if !plan.notes.isEmpty {
                    NotesList(notes: plan.notes)
                }
            }
            .padding(.vertical)
        }
        .navigationTitle("Shoot Planner")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { gear = GearStore.load() }
        .animation(.easeInOut(duration: 0.2), value: scene)
    }
}

// MARK: - Sub-views

private struct ScenePickerRow: View {
    @Binding var selected: SceneType

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(SceneType.allCases) { scene in
                    Button { selected = scene } label: {
                        VStack(spacing: 4) {
                            Image(systemName: scene.icon)
                                .font(.title2)
                                .frame(height: 28)
                            Text(scene.rawValue)
                                .font(.caption.weight(.semibold))
                            Text(scene.tagline)
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .background(
                            selected == scene
                                ? Color.accentColor.opacity(0.15)
                                : Color(.secondarySystemGroupedBackground),
                            in: RoundedRectangle(cornerRadius: 10)
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 10)
                                .strokeBorder(selected == scene ? Color.accentColor : .clear, lineWidth: 1.5)
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal)
        }
    }
}

private struct TipList: View {
    let tips: [PlacementTip]

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(tips.enumerated()), id: \.element.id) { index, tip in
                TipRow(tip: tip)
                if index < tips.count - 1 {
                    Divider().padding(.leading, 52)
                }
            }
        }
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
        .padding(.horizontal)
    }
}

private struct TipRow: View {
    let tip: PlacementTip

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: tip.icon)
                .font(.body)
                .foregroundStyle(.tint)
                .frame(width: 28, height: 28)
                .background(Color.accentColor.opacity(0.12), in: Circle())
                .padding(.top, 10)

            VStack(alignment: .leading, spacing: 3) {
                Text(tip.label)
                    .font(.subheadline.weight(.semibold))
                Text(tip.instruction)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .padding(.vertical, 12)
        }
        .padding(.horizontal, 12)
        // Every row starts at the left edge, whatever the length of its text.
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct NotesList: View {
    let notes: [String]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(notes, id: \.self) { note in
                Label(note, systemImage: "info.circle")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal)
    }
}
