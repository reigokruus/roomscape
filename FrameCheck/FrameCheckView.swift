import SwiftUI
import RoomPlan
import simd

// Add this view + FrameCheck.swift + RoomPlanAdapter.swift to the app target.
// Then insert FrameCheckView(room: room) in ResultView's existing `if let room`.
// This is a numerical scout, NOT automatically connected to the orbit/AR camera.
struct FrameCheckView: View {
    let room: CapturedRoom
    @State private var lensMM = 35
    @State private var sensorIndex = 0
    @State private var presetIndex = 0
    @State private var cameraPosition = SIMD3<Float>(0, 0.9, 0)
    @State private var subjectPosition = SIMD3<Float>(0, 0.9, -2.5)
    @State private var yaw: Float = 180
    @State private var pitch: Float = 0
    @State private var initialized = false
    private var geometry: RoomGeometry? { try? RoomGeometry(capturedRoom: room) }
    private var sensor: Sensor { Sensor.presets[sensorIndex] }
    private var result: FrameCheckResult? {
        guard let geometry else { return nil }
        let y = yaw * .pi / 180, p = pitch * .pi / 180
        let camera = Camera(position: cameraPosition, forward: SIMD3(sin(y) * cos(p), sin(p), -cos(y) * cos(p)))
        let subject = Subject(center: subjectPosition, size: Subject.presets[presetIndex].1)
        return try? FrameChecker.check(room: geometry, camera: camera, subject: subject,
            lens: sensor.menuLens(Float(lensMM)), sensor: sensor,
            candidateLenses: [24, 35, 50, 85].map { sensor.menuLens(Float($0)) })
    }
    private var bounds: (min: SIMD3<Float>, max: SIMD3<Float>) {
        guard let g = geometry else { return (SIMD3(-2, 0, -2.5), SIMD3(2, 2.7, 2.5)) }
        let e = g.walls.flatMap(\.endpoints)
        return (SIMD3(e.map(\.x).min()!, g.floorY, e.map(\.z).min()!),
                SIMD3(e.map(\.x).max()!, g.ceilingY, e.map(\.z).max()!))
    }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Shootlocal · Frame check").font(.title3.bold())
                Text("Measured geometry, approximate optics. Set the camera and subject in scan-world metres.")
                    .font(.caption).foregroundStyle(.secondary)
                Picker("Sensor", selection: $sensorIndex) {
                    ForEach(Sensor.presets.indices, id: \.self) { i in Text(Sensor.presets[i].name).tag(i) }
                }
                Picker(sensor.menuUsesEquivalentMM ? "Lens (35mm equivalent)" : "Lens (actual mm)", selection: $lensMM) {
                    ForEach([24, 35, 50, 85], id: \.self) { Text("\($0) mm").tag($0) }
                }.pickerStyle(.segmented)
                if sensor.menuUsesEquivalentMM {
                    Text(String(format: "Illustrative phone area 9.8 × 7.3mm; %.2fmm physical focal length. Not calibrated to your iPhone.", sensor.menuLens(Float(lensMM)).focalLengthMM))
                        .font(.caption)
                }
                Picker("Subject", selection: $presetIndex) {
                    ForEach(Subject.presets.indices, id: \.self) { i in Text(Subject.presets[i].0).tag(i) }
                }
                Text("Subject centre, not feet. Medium is a 1m-high crop, not two full bodies.").font(.caption)
                Group {
                    positionControl("Camera X", value: $cameraPosition.x, range: bounds.min.x...max(bounds.min.x + 0.01, bounds.max.x))
                    positionControl("Camera Y", value: $cameraPosition.y, range: bounds.min.y...max(bounds.min.y + 0.01, bounds.max.y))
                    positionControl("Camera Z", value: $cameraPosition.z, range: bounds.min.z...max(bounds.min.z + 0.01, bounds.max.z))
                    positionControl("Subject X", value: $subjectPosition.x, range: bounds.min.x...max(bounds.min.x + 0.01, bounds.max.x))
                    positionControl("Subject Y", value: $subjectPosition.y, range: bounds.min.y...max(bounds.min.y + 0.01, bounds.max.y))
                    positionControl("Subject Z", value: $subjectPosition.z, range: bounds.min.z...max(bounds.min.z + 0.01, bounds.max.z))
                    positionControl("Heading ° (0 = -Z)", value: $yaw, range: -180...180)
                    positionControl("Tilt °", value: $pitch, range: -60...60)
                }
                if let r = result {
                    Text(verdict(r)).font(.headline).foregroundStyle(r.fits ? Color.green : Color.red)
                    Text(String(format: "FOV %.1f° × %.1f° · subject depth %.2fm\nRequired depth %.2fm · max depth %@\nRear wall %@ · forward wall %@ · usable backward %@",
                        r.horizontalFOVDegrees, r.verticalFOVDegrees, r.subjectDepth, r.requiredDistance,
                        metres(r.maximumSubjectDistance), metres(r.backWallDistance), metres(r.forwardWallDistance), metres(r.usableBackwardTravel)))
                        .font(.caption.monospaced())
                    Text("Fits here: " + lenses(r.fittingLensesNow))
                    Text("Fits after stepping back: " + lenses(r.fittingLensesWithStepBack)).font(.caption)
                    if !r.roomIntrusions.isEmpty {
                        Text("Frame reaches \(r.roomIntrusions.joined(separator: ", ")) before subject depth. Surfaces appear in the shot; you cannot see through them.")
                            .font(.caption).foregroundStyle(.orange)
                    }
                    ForEach(r.warnings, id: \.self) { Text($0).font(.caption).foregroundStyle(.orange) }
                } else {
                    Text("Cannot evaluate this scan / pose. Check nonzero wall dimensions and valid camera direction.").foregroundStyle(.red)
                }
                Text("10% framing expansion · 0.25m rear clearance · no furniture collision or lens distortion check.")
                    .font(.caption).foregroundStyle(.secondary)
            }.padding()
        }
        .onAppear {
            guard !initialized else { return }
            initialized = true
            let b = bounds, middleX = (b.min.x + b.max.x) / 2, depth = b.max.z - b.min.z
            cameraPosition = SIMD3(middleX, b.min.y + 0.9, b.min.z + depth * 0.18)
            subjectPosition = SIMD3(middleX, b.min.y + 0.9, b.min.z + depth * 0.72)
        }
    }
    private func positionControl(_ name: String, value: Binding<Float>, range: ClosedRange<Float>) -> some View {
        HStack {
            Text(name).font(.caption).frame(width: 125, alignment: .leading)
            Slider(value: value, in: range)
            Text(String(format: "%.2f", value.wrappedValue)).font(.caption.monospacedDigit()).frame(width: 48)
        }
    }
    private func metres(_ value: Float?) -> String { value.map { String(format: "%.2fm", $0) } ?? "unknown" }
    private func lenses(_ values: [Lens]) -> String { values.isEmpty ? "none" : values.map(\.label).joined(separator: ", ") }
    private func verdict(_ r: FrameCheckResult) -> String {
        if !r.geometryComplete { return "Unverified — incomplete room footprint" }
        if !r.cameraInsideRoom || !r.subjectInsideRoom { return "Outside room — move camera / subject inside" }
        if r.obstructedByWall { return "Blocked by a scanned wall" }
        if r.subjectDepth <= 0 { return "Turn the camera toward the subject" }
        if r.fits { return "Fits — subject fully in frame" }
        let alternative = r.fittingLensesNow.first.map { ", or use \($0.label)" } ?? ""
        if r.canStepBack == true { return String(format: "Too tight — step back %.2fm", r.stepBack) + alternative }
        return "Too tight — not enough space behind camera" + alternative
    }
}
