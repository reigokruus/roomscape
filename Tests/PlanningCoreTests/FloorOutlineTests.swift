import XCTest
@testable import PlanningCore

final class FloorOutlineTests: XCTestCase {
    private func pts(_ p: [(Double, Double)]) -> [FloorPoint] { p.map { FloorPoint(x: $0.0, z: $0.1) } }

    func testRectangleAreaAndSize() {
        let room = pts([(0, 0), (3.95, 0), (3.95, 2.70), (0, 2.70)])
        XCTAssertEqual(FloorOutline.area(room), 10.665, accuracy: 1e-9)
        let size = FloorOutline.size(room)!
        XCTAssertEqual(size.length, 3.95, accuracy: 1e-9)
        XCTAssertEqual(size.width, 2.70, accuracy: 1e-9)
        // The other way round gives the same area.
        XCTAssertEqual(FloorOutline.area(room.reversed()), 10.665, accuracy: 1e-9)
    }

    func testTurnedRoomIsMeasuredAlongItsOwnWalls() {
        // 4 × 3 m turned 30°: the axis-aligned box would be bigger.
        let a = Double.pi / 6, c = cos(a), s = sin(a)
        let room = [(0.0, 0.0), (4, 0), (4, 3), (0, 3)].map { FloorPoint(x: $0.0 * c - $0.1 * s + 7, z: $0.0 * s + $0.1 * c - 2) }
        XCTAssertEqual(FloorOutline.area(room), 12, accuracy: 1e-9)
        let size = FloorOutline.size(room)!
        XCTAssertEqual(size.length, 4, accuracy: 1e-9)
        XCTAssertEqual(size.width, 3, accuracy: 1e-9)
    }

    func testLShapedRoomLeavesItsMissingCornerOut() {
        // 5 × 4 m with a 2 × 2 m corner missing: 16 m², size still 5 × 4.
        let room = pts([(0, 0), (5, 0), (5, 2), (3, 2), (3, 4), (0, 4)])
        XCTAssertEqual(FloorOutline.area(room), 16, accuracy: 1e-9)
        let size = FloorOutline.size(room)!
        XCTAssertEqual(size.length, 5, accuracy: 1e-9)
        XCTAssertEqual(size.width, 4, accuracy: 1e-9)
    }

    func testTooFewCorners() {
        XCTAssertEqual(FloorOutline.area(pts([(0, 0), (1, 0)])), 0)
        XCTAssertNil(FloorOutline.size(pts([(0, 0), (1, 0)])))
    }

    func testMovingAWallChangesTheAreaByItsLength() {
        // 3.95 × 2.70 m; the far wall (side 2, z = 2.70) moved out 25 cm: a curtain had been measured as the wall.
        for room in [pts([(0, 0), (3.95, 0), (3.95, 2.70), (0, 2.70)]), pts([(0, 2.70), (3.95, 2.70), (3.95, 0), (0, 0)])] {
            let side = FloorOutline.nearestSide(room, to: FloorPoint(x: 2, z: 2.68), tolerance: 0.1)!
            var out = [Double](repeating: 0, count: 4)
            out[side] = 0.25
            let moved = FloorOutline.offset(room, by: out)
            XCTAssertEqual(FloorOutline.area(moved), 3.95 * 2.95, accuracy: 1e-9)
            let size = FloorOutline.size(moved)!
            XCTAssertEqual(size.length, 3.95, accuracy: 1e-9)
            XCTAssertEqual(size.width, 2.95, accuracy: 1e-9)
            // Moved in instead.
            out[side] = -0.20
            XCTAssertEqual(FloorOutline.area(FloorOutline.offset(room, by: out)), 3.95 * 2.50, accuracy: 1e-9)
        }
    }

    func testMovingAWallOfAnLKeepsTheL() {
        // The inner side of the notch (x = 3, from z = 2 to 4) moved 50 cm out (into the notch): +1 m².
        let room = pts([(0, 0), (5, 0), (5, 2), (3, 2), (3, 4), (0, 4)])
        let side = FloorOutline.nearestSide(room, to: FloorPoint(x: 3.02, z: 3), tolerance: 0.1)!
        XCTAssertEqual(side, 3)
        var out = [Double](repeating: 0, count: 6)
        out[side] = 0.5
        let moved = FloorOutline.offset(room, by: out)
        XCTAssertEqual(FloorOutline.area(moved), 17, accuracy: 1e-9)
        XCTAssertEqual(moved.count, 6)
    }

    func testSimplifyDropsCornersAlongAStraightWall() {
        // RoomPlan's outline from a real scan has extra corners along straight walls.
        let room = pts([(0, 0), (2, 0), (4, 0.001), (4, 3), (2.5, 3), (0, 3)])
        let simple = FloorOutline.simplified(room)
        XCTAssertEqual(simple.count, 4)
        XCTAssertEqual(FloorOutline.area(simple), FloorOutline.area(room), accuracy: 0.01)
    }

    func testContains() {
        let room = pts([(0, 0), (5, 0), (5, 2), (3, 2), (3, 4), (0, 4)])
        XCTAssertTrue(FloorOutline.contains(room, FloorPoint(x: 1, z: 3)))
        XCTAssertFalse(FloorOutline.contains(room, FloorPoint(x: 4, z: 3)))   // in the notch
        XCTAssertFalse(FloorOutline.contains(room, FloorPoint(x: -1, z: 1)))
    }

    func testText() {
        XCTAssertEqual(FloorOutline.text(area: 9.94), "≈9.9 m²")
        XCTAssertEqual(FloorOutline.text(area: 14.2), "≈14 m²")
        XCTAssertEqual(FloorOutline.text(area: 7.25, rough: true), "about 7.2 m²")
    }
}
