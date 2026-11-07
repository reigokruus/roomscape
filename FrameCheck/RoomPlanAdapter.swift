import Foundation
import RoomPlan
import simd

// Keep RoomPlan dependency out of FrameCheck.swift. Uses saved CapturedRoom JSON,
// not the USDZ model: ScanStore.loadRoom(scan) already decodes that JSON for ResultView.
extension RoomGeometry {
    init(capturedRoom: CapturedRoom, floorY: Float? = nil, ceilingY: Float? = nil) throws {
        let walls = capturedRoom.walls.map {
            Wall(transform: $0.transform, width: $0.dimensions.x, height: $0.dimensions.y)
        }
        guard !walls.isEmpty else { throw FrameCheckError.invalidInput("Scan contains no walls") }
        let yExtents = walls.flatMap { wall -> [Float] in
            let c = wall.center, x = wall.transform.columns.0, y = wall.transform.columns.1
            return [-1 as Float, 1].flatMap { sx in
                [-1 as Float, 1].map { sy in c.y + sx * x.y * wall.width / 2 + sy * y.y * wall.height / 2 }
            }
        }
        self.init(walls: walls, floorY: floorY ?? yExtents.min()!, ceilingY: ceilingY ?? yExtents.max()!,
                  heightsAreEstimated: floorY == nil || ceilingY == nil)
    }
}
