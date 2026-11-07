import SwiftUI

/// Controls for the lighting preview: the face reading, the subject, the room light and exposure, and each light.
struct LightingPanel: View {
    @Binding var setup: LightingSetup

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 4) {
                    Text(setup.readout)
                        .font(.headline.monospacedDigit())
                    Text(setup.breakdown)
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            } footer: {
                Text("Tap the floor in the 3D view to move the person. The camera (grey box) is 2 m in front of them, and the picture is exposed for their face.")
            }

            Section("Person and camera") {
                Picker("Person", selection: $setup.pose) {
                    ForEach(SubjectPose.allCases) { Text($0.label).tag($0) }
                }
                .pickerStyle(.segmented)
                SliderRow(title: "Facing", value: $setup.facing, range: -180...180, step: 5) { "\(Int($0))°" }
                SliderRow(title: "Room lights", value: $setup.roomDimmer, range: 0...1, step: 0.05) { "\(Int($0 * 100))%" }
                SliderRow(title: "Exposure", value: $setup.exposureEV, range: -3...3, step: 0.25) {
                    String(format: "%+.2g EV", $0)
                }
            }

            ForEach($setup.lights) { $light in
                Section {
                    Toggle("On", isOn: $light.on)
                    Picker("Type", selection: $light.type) {
                        ForEach(LightType.allCases) { Text($0.label).tag($0) }
                    }
                    SliderRow(title: "Power", value: $light.power, range: 0.05...1, step: 0.05) { "\(Int($0 * 100))%" }
                    SliderRow(title: "Angle", value: $light.angle, range: -180...180, step: 5) { "\(Int($0))°" }
                    SliderRow(title: "Distance", value: $light.distance, range: 0.5...4, step: 0.1) { String(format: "%.1f m", $0) }
                    SliderRow(title: "Height", value: $light.height, range: 0.3...3.5, step: 0.1) { String(format: "%.1f m", $0) }
                    SliderRow(title: "Colour", value: $light.kelvin, range: 2700...6500, step: 100) { "\(Int($0)) K" }
                } header: {
                    HStack {
                        Text(light.name)
                        Spacer()
                        Button(role: .destructive) {
                            setup.lights.removeAll { $0.id == light.id }
                        } label: {
                            Image(systemName: "trash")
                        }
                        .accessibilityLabel("Remove \(light.name)")
                    }
                } footer: {
                    Text("Angle 0° is from the camera's side; 90° is the person's left. It always points at their face.")
                }
            }

            if setup.lights.count < LightingSetup.maxLights {
                Button {
                    let n = setup.lights.count + 1
                    setup.lights.append(ShotLight(name: "Light \(n)", type: .panel, power: 0.4, angle: 120, distance: 1.5,
                                                  height: setup.pose.faceHeight + 0.5))
                } label: {
                    Label("Add a light", systemImage: "plus")
                }
            }
        }
        .listStyle(.insetGrouped)
    }
}

private struct SliderRow: View {
    let title: String
    @Binding var value: Float
    let range: ClosedRange<Float>
    let step: Float
    let format: (Float) -> String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(title)
                Spacer()
                Text(format(value))
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
            }
            Slider(value: $value, in: range, step: step)
        }
    }
}
