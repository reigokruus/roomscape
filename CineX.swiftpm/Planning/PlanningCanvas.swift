import SwiftUI

private struct PlanMapping {
    let room:PlanRoom
    let size:CGSize
    var scale:Double {
        let b = room.bounds
        return max(0.01,min((Double(size.width)-40)/max(0.1,b.maxX-b.minX),(Double(size.height)-40)/max(0.1,b.maxZ-b.minZ)))
    }
    func screen(_ p:PlanPoint) -> CGPoint {
        CGPoint(x:Double(size.width)/2+(p.x-room.center.x)*scale,y:Double(size.height)/2+(p.z-room.center.z)*scale)
    }
    func world(_ p:CGPoint) -> PlanPoint {
        .init(x:(Double(p.x)-Double(size.width)/2)/scale+room.center.x,y:room.floor,z:(Double(p.y)-Double(size.height)/2)/scale+room.center.z)
    }
}

struct PlanningCanvas: View {
    let room:PlanRoom
    let shot:PlanShot
    let selectedID:UUID?
    let select:(UUID) -> Void
    let move:(UUID,PlanPoint) -> Void
    let placeCamera:Bool
    let cameraPoint:(PlanPoint) -> Void
    var body:some View {
        GeometryReader { geo in
            let mapping = PlanMapping(room:room,size:geo.size)
            ZStack {
                Color(white:0.08)
                    .contentShape(Rectangle())
                    .onTapGesture { location in
                        if placeCamera {
                            var p = mapping.world(location); p.y = shot.camera.position.y; cameraPoint(p)
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
                }.allowsHitTesting(false)
                ForEach(shot.items) { item in
                    PlanningFootprint(item:item,scale:mapping.scale,selected:item.id == selectedID,target:item.id == shot.targetID,
                                      select:{ select(item.id) },move:{ delta in
                        move(item.id,item.position+PlanPoint(x:Double(delta.width)/mapping.scale,y:0,z:Double(delta.height)/mapping.scale))
                    })
                    .position(mapping.screen(item.position))
                    .allowsHitTesting(!placeCamera)
                }
                Image(systemName:"location.north.fill").foregroundStyle(.cyan)
                    .rotationEffect(.radians(-shot.camera.yaw)).position(mapping.screen(shot.camera.position)).allowsHitTesting(false)
                VStack { HStack { Text(placeCamera ? "Tap inside room: camera" : "Top plan · metres"); Spacer() }; Spacer()
                    HStack { Text(String(format:"%.1f × %.1f m",room.bounds.maxX-room.bounds.minX,room.bounds.maxZ-room.bounds.minZ)); Spacer(); Text("10 cm snap") }
                }.font(.caption2).foregroundStyle(.white).padding(8).allowsHitTesting(false)
            }.clipShape(RoundedRectangle(cornerRadius:12))
        }
    }
}

private struct PlanningFootprint: View {
    let item:PlanItem
    let scale:Double
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
            Text(item.name).font(.system(size:9)).lineLimit(2).foregroundStyle(.white).padding(2)
                .rotationEffect(.radians(item.yaw))
        }
        .frame(width:max(12,item.size.x*scale),height:max(12,item.size.z*scale))
        .rotationEffect(.radians(-item.yaw)).offset(drag)
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
