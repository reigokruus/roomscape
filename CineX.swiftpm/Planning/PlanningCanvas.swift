import SwiftUI

/// World (x, z) to the plan's screen and back. The drawing is turned (never more than 45°) so the room's longest wall
/// lies square to the screen: RoomPlan keeps the room at whatever angle it was scanned, and the stored plan keeps
/// those coordinates. Only the picture turns.
private struct PlanMapping {
    let room:PlanRoom
    let size:CGSize
    let angle:Double
    let center:PlanPoint
    let scale:Double
    /// The room's size along its own walls (width across the screen, depth down it).
    let extent:(width:Double,depth:Double)

    init(room:PlanRoom,size:CGSize) {
        self.room = room
        self.size = size
        let longest = room.walls.max { Self.length($0) < Self.length($1) }
        let wallAngle = longest.map { atan2($0.b.z-$0.a.z,$0.b.x-$0.a.x) } ?? 0
        let turn = -remainder(wallAngle,.pi/2)
        angle = turn
        // Bounds in the turned frame, then their middle back in the world.
        let pivot = room.center
        let turned = room.walls.flatMap { [$0.a,$0.b] }.map { Self.rotate($0.x-pivot.x,$0.z-pivot.z,by:turn) }
        let minX = turned.map(\.x).min() ?? -2, maxX = turned.map(\.x).max() ?? 2
        let minY = turned.map(\.y).min() ?? -2, maxY = turned.map(\.y).max() ?? 2
        let mid = Self.rotate((minX+maxX)/2,(minY+maxY)/2,by:-turn)
        center = PlanPoint(x:pivot.x+mid.x,y:room.floor,z:pivot.z+mid.y)
        extent = (maxX-minX,maxY-minY)
        scale = max(0.01,min((Double(size.width)-40)/max(0.1,maxX-minX),(Double(size.height)-40)/max(0.1,maxY-minY)))
    }

    func screen(_ p:PlanPoint) -> CGPoint {
        let r = Self.rotate(p.x-center.x,p.z-center.z,by:angle)
        return CGPoint(x:Double(size.width)/2+r.x*scale,y:Double(size.height)/2+r.y*scale)
    }
    func world(_ p:CGPoint) -> PlanPoint {
        let d = delta(CGSize(width:Double(p.x)-Double(size.width)/2,height:Double(p.y)-Double(size.height)/2))
        return .init(x:center.x+d.x,y:room.floor,z:center.z+d.z)
    }
    /// A drag on the screen as a move on the floor.
    func delta(_ drag:CGSize) -> PlanPoint {
        let r = Self.rotate(Double(drag.width)/scale,Double(drag.height)/scale,by:-angle)
        return .init(x:r.x,y:0,z:r.y)
    }

    private static func rotate(_ x:Double,_ y:Double,by a:Double) -> (x:Double,y:Double) {
        (x*cos(a)-y*sin(a),x*sin(a)+y*cos(a))
    }
    private static func length(_ w:PlanWall) -> Double { hypot(w.b.x-w.a.x,w.b.z-w.a.z) }
}

/// Top-down floor plan of one storyboard view. Tap a thing to select it and drag it to move it; tap the camera to
/// select it, then drag it or tap the floor to put it there; tap empty floor to deselect. The editor shows the
/// selection's actions in a bar over the bottom of the plan.
struct PlanningCanvas: View {
    let room:PlanRoom
    let shot:PlanShot
    let selectedID:UUID?
    let cameraSelected:Bool
    let select:(UUID) -> Void
    let selectCamera:() -> Void
    let deselect:() -> Void
    let move:(UUID,PlanPoint) -> Void
    let cameraPoint:(PlanPoint) -> Void
    @GestureState private var cameraDrag = CGSize.zero
    var body:some View {
        GeometryReader { geo in
            let mapping = PlanMapping(room:room,size:geo.size)
            ZStack {
                Color(white:0.08)
                    .contentShape(Rectangle())
                    .onTapGesture { location in
                        if cameraSelected {
                            var p = mapping.world(location); p.y = shot.camera.position.y; cameraPoint(p)
                        } else {
                            deselect()
                        }
                    }
                Canvas { context,_ in
                    var walls = Path()
                    for wall in room.walls { walls.move(to:mapping.screen(wall.a)); walls.addLine(to:mapping.screen(wall.b)) }
                    context.stroke(walls,with:.color(.white),lineWidth:3)
                    let origin = mapping.screen(shot.camera.position)
                    let angle = ShotFraming.fov(sensor:shot.camera.sensorWidthMM,focal:shot.camera.focalMM)/2
                    let f = shot.camera.forward
                    let right = PlanPoint(x:1,y:0,z:0).rotated(yaw:shot.camera.yaw)
                    let length = 2.0
                    var cone = Path(); cone.move(to:origin)
                    cone.addLine(to:mapping.screen(shot.camera.position+(f+right*tan(angle))*length))
                    cone.addLine(to:mapping.screen(shot.camera.position+(f-right*tan(angle))*length)); cone.closeSubpath()
                    context.fill(cone,with:.color(.cyan.opacity(0.15)))
                    context.stroke(cone,with:.color(.cyan),style:StrokeStyle(lineWidth:1,dash:[5,4]))
                }
                .allowsHitTesting(false)
                ForEach(shot.items) { item in
                    PlanningFootprint(item:item,scale:mapping.scale,viewAngle:mapping.angle,selected:item.id == selectedID,
                                      target:item.id == shot.targetID,select:{ select(item.id) },move:{ drag in
                        move(item.id,item.position+mapping.delta(drag))
                    })
                    .position(mapping.screen(item.position))
                }
                cameraMarker(viewAngle:mapping.angle)
                    .position(mapping.screen(shot.camera.position))
                    .offset(cameraDrag)
                    .gesture(DragGesture(minimumDistance:4)
                        .updating($cameraDrag) { value,state,_ in state = value.translation }
                        .onEnded { value in
                            selectCamera()
                            cameraPoint(shot.camera.position+mapping.delta(value.translation))
                        })
                    .onTapGesture(perform:selectCamera)
                VStack {
                    HStack(alignment:.top) {
                        Text(hint)
                        Spacer()
                        Text(String(format:"%.1f × %.1f m",mapping.extent.width,mapping.extent.depth))
                    }
                    Spacer()
                }.font(.caption2).foregroundStyle(.white).padding(8).allowsHitTesting(false)
            }.clipShape(RoundedRectangle(cornerRadius:12))
        }
    }

    private var hint:String {
        if cameraSelected { return "Drag the camera, or tap the floor to put it there" }
        if selectedID != nil { return "Drag to move · actions below" }
        return "Tap something to select it · drag to move it"
    }

    /// The camera: an arrow in a ring, larger than the arrow alone so it's easy to grab.
    private func cameraMarker(viewAngle:Double) -> some View {
        ZStack {
            Circle().fill(Color.cyan.opacity(cameraSelected ? 0.35 : 0.15))
            Circle().strokeBorder(cameraSelected ? Color.white : Color.cyan,lineWidth:cameraSelected ? 2.5 : 1)
            Image(systemName:"location.north.fill").foregroundStyle(.cyan)
                .rotationEffect(.radians(viewAngle-shot.camera.yaw))
        }
        .frame(width:34,height:34)
        .contentShape(Circle())
        .accessibilityElement(children:.ignore)
        .accessibilityLabel("Shot camera, \(Int(shot.camera.focalMM)) millimetre lens")
        .accessibilityAddTraits(.isButton)
    }
}

private struct PlanningFootprint: View {
    let item:PlanItem
    let scale:Double
    /// How far the plan's drawing is turned (see `PlanMapping`).
    var viewAngle:Double = 0
    let selected:Bool
    let target:Bool
    let select:() -> Void
    let move:(CGSize) -> Void
    @GestureState private var drag = CGSize.zero
    private var color:Color { target ? .green : (item.source == .scanned ? .gray : .orange) }
    var body:some View {
        ZStack {
            Rectangle().fill(color.opacity(0.2))
            Rectangle().strokeBorder(selected ? .white : color,lineWidth:selected ? 3 : 1)
            Text(item.name).font(.system(size:10)).lineLimit(2).foregroundStyle(.white).padding(2)
                .rotationEffect(.radians(item.yaw-viewAngle))
        }
        // Small things still get a footprint big enough to tap and drag.
        .frame(width:max(26,item.size.x*scale),height:max(26,item.size.z*scale))
        .rotationEffect(.radians(viewAngle-item.yaw)).offset(drag)
        .contentShape(Rectangle())
        .onTapGesture(perform:select)
        .gesture(DragGesture(minimumDistance:4,coordinateSpace:.named("planFloor"))
            .updating($drag) { value,state,_ in state = value.translation }
            .onEnded { value in select(); move(value.translation) })
        .accessibilityElement(children:.ignore)
        .accessibilityLabel("\(item.name), \(Int(item.size.x*100)) by \(Int(item.size.z*100)) centimetres")
        .accessibilityAddTraits(.isButton)
    }
}
