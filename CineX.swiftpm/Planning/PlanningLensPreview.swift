import SwiftUI
import SceneKit

// A plan deliberately uses wireframe envelopes instead of passing off guessed assets as the scanned room.
struct PlanningLensPreview: UIViewRepresentable {
    let room:PlanRoom
    let shot:PlanShot
    func makeUIView(context:Context) -> SCNView {
        let view = SCNView(); view.backgroundColor = .black; view.allowsCameraControl = false
        view.antialiasingMode = .multisampling4X
        return view
    }
    func updateUIView(_ view:SCNView,context:Context) {
        let scene = SCNScene()
        func material(_ color:UIColor,wire:Bool = false) -> SCNMaterial {
            let m = SCNMaterial(); m.diffuse.contents = color; m.lightingModel = .constant; m.isDoubleSided = true
            m.fillMode = wire ? .lines : .fill; return m
        }
        for wall in room.walls {
            let length = PlanRoom.distanceXZ(wall.a,wall.b)
            let box = SCNBox(width:length,height:wall.top-wall.bottom,length:0.025,chamferRadius:0)
            box.materials = [material(UIColor(white:0.55,alpha:1),wire:true)]
            let node = SCNNode(geometry:box)
            node.position = SCNVector3(Float((wall.a.x+wall.b.x)/2),Float((wall.top+wall.bottom)/2),Float((wall.a.z+wall.b.z)/2))
            node.eulerAngles.y = Float(-atan2(wall.b.z-wall.a.z,wall.b.x-wall.a.x))
            scene.rootNode.addChildNode(node)
        }
        for item in shot.items where !(item.kind == .camera && item.contains(shot.camera.position)) {
            let box = SCNBox(width:item.size.x,height:item.size.y,length:item.size.z,chamferRadius:0)
            let color:UIColor = item.id == shot.targetID ? .systemGreen : (item.source == .scanned ? .systemGray : .systemOrange)
            box.materials = [material(color,wire:true)]
            let node = SCNNode(geometry:box)
            node.position = SCNVector3(Float(item.position.x),Float(item.position.y+item.size.y/2),Float(item.position.z))
            node.eulerAngles.y = Float(item.yaw)
            scene.rootNode.addChildNode(node)
        }
        let camera = SCNNode(); camera.camera = SCNCamera()
        camera.camera?.projectionDirection = .vertical
        camera.camera?.fieldOfView = CGFloat(ShotFraming.fov(sensor:shot.camera.sensorHeightMM,focal:shot.camera.focalMM)*180 / .pi)
        camera.camera?.zNear = 0.01; camera.camera?.zFar = 2000
        camera.position = SCNVector3(Float(shot.camera.position.x),Float(shot.camera.position.y),Float(shot.camera.position.z))
        camera.eulerAngles.y = Float(shot.camera.yaw)
        scene.rootNode.addChildNode(camera)
        // Custom projection preserves the sensor's aspect rather than stretching to the portrait phone viewport.
        let near:Double = 0.01, far:Double = 2000
        let x = 2*shot.camera.focalMM/shot.camera.sensorWidthMM
        let y = 2*shot.camera.focalMM/shot.camera.sensorHeightMM
        var matrix = SCNMatrix4Identity
        matrix.m11 = Float(x); matrix.m22 = Float(y)
        matrix.m33 = Float(-(far+near)/(far-near)); matrix.m34 = -1
        matrix.m43 = Float(-2*far*near/(far-near)); matrix.m44 = 0
        camera.camera?.projectionTransform = matrix
        view.scene = scene; view.pointOfView = camera
    }
}
