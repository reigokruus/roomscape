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

    func testText() {
        XCTAssertEqual(FloorOutline.text(area: 9.94), "≈9.9 m²")
        XCTAssertEqual(FloorOutline.text(area: 14.2), "≈14 m²")
        XCTAssertEqual(FloorOutline.text(area: 7.25, rough: true), "about 7.2 m²")
    }
}
