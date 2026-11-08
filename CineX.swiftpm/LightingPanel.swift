import SwiftUI

/// Controls for the lighting preview: the face reading, planning with Claude, the subject and camera, the room light
/// and exposure, and each light.
struct LightingPanel: View {
    @Binding var setup: LightingSetup
    /// Claude is working on a setup (see `ShotDirector`).
    var planning = false
    var planError: String?
    /// Plan with Claude: the kind of shot (nil = Claude picks it) and the user's wishes.
    var onPlan: (SceneType?, String) -> Void = { _, _ in }
    /// Opens this setup as a new storyboard view.
    var onStoryboard: () -> Void = {}
    @State private var shot: SceneType?
    @State private var wish = ""
    @State private var showingKey = false
    /// The key sheet was opened by the plan button: planning starts once a key is saved.
    @State private var planAfterKey = false

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
                Text("Tap the floor in the 3D view to move the person. The corner view shows what the camera (grey box) sees; tap it to make it bigger. Bright lights on the face stop the picture down; use Exposure to brighten it.")
            }

            planSection

            if let note = setup.planNote {
                Section(setup.planTitle.map { "Claude's plan: \($0)" } ?? "Claude's plan") {
                    Text(note)
                    ForEach(setup.planTips, id: \.self) { tip in
                        Label(tip, systemImage: "checkmark.circle")
                            .font(.callout)
                    }
                    storyboardButton
                }
            }

            Section("Person and camera") {
                Picker("Person", selection: $setup.pose) {
                    ForEach(SubjectPose.allCases) { Text($0.label).tag($0) }
                }
                .pickerStyle(.segmented)
                SliderRow(title: "Facing", value: $setup.facing, range: -180...180, step: 5) { "\(Int($0))°" }
                SliderRow(title: "Camera distance", value: $setup.cameraDistance, range: 0.6...5, step: 0.1) {
                    String(format: "%.1f m", $0)
                }
                Picker("Lens", selection: $setup.focalLength) {
                    ForEach(LightingSetup.lenses, id: \.self) { Text("\(Int($0)) mm").tag($0) }
                }
                Picker("Frame", selection: $setup.vertical) {
                    Text("Wide 16:9").tag(false)
                    Text("Upright 9:16").tag(true)
                }
                .pickerStyle(.segmented)
                SliderRow(title: roomLightsTitle, value: $setup.roomDimmer, range: 0...1, step: 0.05) { "\(Int($0 * 100))%" }
                if setup.roomSources.contains(where: \.isWindow) {
                    SliderRow(title: "Daylight", value: $setup.daylight, range: 0...1, step: 0.05) { "\(Int($0 * 100))%" }
                }
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
                    powerRow(light)
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

            Section {
                Button {
                    let gear = GearStore.load()
                    setup.lights = LightingSetup.starting(subjectX: setup.subjectX, subjectZ: setup.subjectZ,
                                                          facing: setup.facing, floorY: setup.floorY, gear: gear).lights
                } label: {
                    Label("Use the lights from My Gear", systemImage: "arrow.triangle.2.circlepath")
                }
                NavigationLink(destination: GearInventoryView()) {
                    Label("Edit My Gear", systemImage: "checklist")
                }
                if setup.planNote == nil { storyboardButton }
            } footer: {
                Text("Up to four lights from My Gear are placed as key, fill, back and background light. With none listed, a softbox key and an LED panel fill are used.")
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
        .sheet(isPresented: $showingKey) {
            ClaudeKeySheet(onSaved: planAfterKey ? { onPlan(shot, wish) } : nil)
        }
    }
}

private extension LightingPanel {
    var storyboardButton: some View {
        Button(action: onStoryboard) {
            Label("Open this setup in the Storyboard", systemImage: "rectangle.stack.badge.plus")
        }
    }

    var planSection: some View {
        Section {
            Picker("Shot", selection: $shot) {
                Label("Claude decides", systemImage: "sparkles").tag(SceneType?.none)
                ForEach(SceneType.allCases) { Label($0.rawValue, systemImage: $0.icon).tag(Optional($0)) }
            }
            TextField("Mood or wishes (optional), e.g. warm and moody", text: $wish, axis: .vertical)
                .lineLimit(1...3)
            if planning {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("Claude is planning the shot…").font(.callout)
                }
            } else {
                Button {
                    if ClaudeKeyStore.key == nil { planAfterKey = true; showingKey = true } else { onPlan(shot, wish) }
                } label: {
                    Label(shot == nil ? (setup.planNote == nil ? "Set it up with Claude" : "Set it up again")
                                      : "Plan this \(shot!.rawValue.lowercased()) with Claude",
                          systemImage: "sparkles")
                        .font(.body.weight(.semibold))
                }
            }
            if let planError {
                Text(planError)
                    .font(.caption)
                    .foregroundStyle(.red)
            }
        } header: {
            Text("Plan with Claude")
        } footer: {
            Text("One tap: Claude picks a shot that suits the room, then places the person, your lights from My Gear and the camera. Pick a shot type first if you have one in mind. Everything stays adjustable below.")
        }
    }

    var roomLightsTitle: String {
        let n = setup.roomSources.filter { !$0.isWindow }.count
        return n == 0 ? "Room lights" : "Room lights (\(n) found)"
    }

    /// Cable run from the stand to the nearest socket found in the scan.
    @ViewBuilder func powerRow(_ light: ShotLight) -> some View {
        if let run = setup.powerRun(for: light) {
            let long = run.meters > 3
            Label(String(format: "%.1f m of cable to %@%@", run.meters, run.to.label.lowercased(),
                         long ? ": bring an extension lead" : ""),
                  systemImage: long ? "exclamationmark.triangle" : "powerplug")
                .font(.caption)
                .foregroundStyle(long ? Color.orange : Color.secondary)
        } else if setup.power.isEmpty {
            Label("No sockets known yet: use Objects → Find with Claude", systemImage: "powerplug")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
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
