import Foundation

/// Turns a lighting-preview setup (often Claude's, see `ShotDirector`) into a new storyboard view: the room's
/// scanned furniture, the person (the frame target), a stand for each light where the preview puts it, and the
/// shot camera with its position, aim and lens. Claude's explanation and tips go into the view's notes.
enum LightingStoryboard {
    static func shot(from setup: LightingSetup, in project: PlanProject) throws -> PlanShot {
        guard project.shots.count < 50 else {
            throw PlanError.invalid("This storyboard already has 50 views. Delete one to add the lighting setup.")
        }
        let floor = project.room.floor

        // The room's furniture as it stands in the first view (kept in step with the Objects panel).
        let scanned = project.shots[0].items.filter { $0.source == .scanned }

        let height = Double(setup.pose.faceHeight) + 0.15
        let person = PlanItem(name: setup.pose == .seated ? "Person (seated)" : "Person (standing)", kind: .subject,
                              position: PlanPoint(x: Double(setup.subjectX), y: floor, z: Double(setup.subjectZ)),
                              size: PlanPoint(x: 0.5, y: height, z: 0.5))
        let lights = setup.lights.map { light -> PlanItem in
            let p = setup.position(of: light)
            return PlanItem(name: light.on ? light.name : "\(light.name) (off)", kind: .light,
                            position: PlanPoint(x: Double(p.x), y: floor, z: Double(p.z)),
                            size: PlanPoint(x: 0.6, y: max(0.3, Double(light.height)), z: 0.6))
        }

        let c = setup.cameraPosition, face = setup.faceCenter
        let d = PlanPoint(x: Double(face.x - c.x), y: 0, z: Double(face.z - c.z))
        // The lens is a full-frame equivalent; an upright frame turns the 36 × 24 mm sensor on its side.
        let camera = ShotCamera(position: PlanPoint(x: Double(c.x), y: Double(c.y), z: Double(c.z)),
                                yaw: atan2(-d.x, -d.z), focalMM: Double(setup.focalLength),
                                sensorWidthMM: setup.vertical ? 24 : 36, sensorHeightMM: setup.vertical ? 36 : 24)

        var notes = setup.planNote ?? ""
        if !setup.planTips.isEmpty {
            notes += (notes.isEmpty ? "" : "\n\n") + setup.planTips.map { "• \($0)" }.joined(separator: "\n")
        }
        let base = setup.planTitle.map { "\($0) (Claude)" } ?? "Lighting setup"
        var name = base, n = 2
        while project.shots.contains(where: { $0.name == name }) { name = "\(base) \(n)"; n += 1 }

        // Fresh IDs for the furniture copies, so this view is independent of the others.
        var shot = PlanShot(name: name, notes: String(notes.prefix(10000)), camera: camera,
                            items: scanned, targetID: nil).duplicated()
        shot.name = name
        shot.items += [person] + lights
        shot.targetID = person.id
        try PlanValidation.shot(shot)
        return shot
    }
}
