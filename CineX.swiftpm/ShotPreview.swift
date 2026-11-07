import SceneKit
import SwiftUI

/// What the shoot camera sees: the lighting preview's scene rendered from the setup's camera position, through
/// its lens and frame. It shares the result screen's scene (one scene, two views), so the splats, room shell,
/// person and lights are lit exactly as in the 3D view. The camera marker is left out (its category), since this
/// camera stands inside it; light stands that would be in the shot show up, as they would on set.
struct ShotPreview: UIViewRepresentable {
    let scene: SCNScene
    let setup: LightingSetup

    final class Coordinator {
        let camera = SCNNode()
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> SCNView {
        let view = SCNView()
        view.scene = scene
        view.backgroundColor = .black
        view.antialiasingMode = .multisampling4X
        // Same as the 3D view, for the few unlit parts (light stands).
        view.autoenablesDefaultLighting = true
        view.isUserInteractionEnabled = false
        let camera = SCNCamera()
        camera.zNear = 0.05
        camera.zFar = 100
        camera.categoryBitMask = ~LightingScene.cameraMarkerCategory
        context.coordinator.camera.camera = camera
        scene.rootNode.addChildNode(context.coordinator.camera)
        view.pointOfView = context.coordinator.camera
        place(context.coordinator.camera)
        return view
    }

    func updateUIView(_ view: SCNView, context: Context) {
        place(context.coordinator.camera)
    }

    static func dismantleUIView(_ view: SCNView, coordinator: Coordinator) {
        coordinator.camera.removeFromParentNode()
    }

    private func place(_ node: SCNNode) {
        node.simdPosition = setup.cameraPosition
        node.simdLook(at: setup.faceCenter)
        guard let camera = node.camera else { return }
        // The long side of the frame carries the lens's full-frame field of view.
        camera.projectionDirection = setup.vertical ? .vertical : .horizontal
        camera.fieldOfView = CGFloat(setup.fieldOfView)
    }
}

/// The shot preview as a framed inset with its lens and frame, small in a corner or large (tap to switch).
struct ShotPreviewInset: View {
    let scene: SCNScene
    let setup: LightingSetup
    @Binding var large: Bool

    var body: some View {
        let aspect: CGFloat = setup.vertical ? 9.0 / 16 : 16.0 / 9
        ShotPreview(scene: scene, setup: setup)
            .aspectRatio(aspect, contentMode: .fit)
            .frame(maxWidth: large ? .infinity : (setup.vertical ? 110 : 190),
                   maxHeight: large ? .infinity : (setup.vertical ? 196 : 107))
            .overlay(alignment: .bottomLeading) {
                Text("\(Int(setup.focalLength)) mm · \(setup.vertical ? "9:16" : "16:9")")
                    .font(.caption2.weight(.semibold).monospacedDigit())
                    .padding(.horizontal, 6).padding(.vertical, 3)
                    .background(.black.opacity(0.55), in: Capsule())
                    .foregroundStyle(.white)
                    .padding(6)
            }
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(.white.opacity(0.85), lineWidth: 1.5))
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .contentShape(Rectangle())
            .onTapGesture {
                withAnimation(.easeInOut(duration: 0.25)) { large.toggle() }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Camera view, \(Int(setup.focalLength)) millimetre lens")
            .accessibilityHint(large ? "Makes the camera view small" : "Makes the camera view large")
            .accessibilityAddTraits(.isButton)
    }
}
