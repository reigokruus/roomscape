import XCTest
import simd
@testable import PlanningCore

final class PoseJumpTests: XCTestCase {
    func pose(x: Float, z: Float, heading deg: Float) -> simd_float4x4 {
        var m = simd_float4x4(simd_quatf(angle: deg * .pi / 180, axis: SIMD3(0, 1, 0)))
        m.columns.3 = SIMD4(x, 1.4, z, 1); return m
    }

    func testWalkingIsNotAJump() {
        XCTAssertFalse(PoseJump.isJump(from: pose(x: 0, z: 0, heading: 0), to: pose(x: 0.3, z: 0.1, heading: 12), seconds: 0.5))
        XCTAssertFalse(PoseJump.isJump(from: pose(x: 0, z: 0, heading: 0), to: pose(x: 1.2, z: 0, heading: 40), seconds: 1.5))
    }

    func testTheRecordedJumpIsOne() {
        // 22:37 scan, frames 388 -> 389: 4.22 m and about 80 degrees in 0.75 s
        XCTAssertTrue(PoseJump.isJump(from: pose(x: 0.5, z: -2.2, heading: 10), to: pose(x: -2.0, z: 1.2, heading: 90), seconds: 0.75))
    }

    func testCorrectionPutsThePoseBack() {
        let a = pose(x: 0.5, z: -2.2, heading: 10), b = pose(x: -2.0, z: 1.2, heading: 90)
        let c = PoseJump.correction(from: b, to: a)
        let fixed = c * b
        XCTAssertEqual(simd_distance(SIMD3(fixed.columns.3.x, fixed.columns.3.y, fixed.columns.3.z), SIMD3(a.columns.3.x, a.columns.3.y, a.columns.3.z)), 0, accuracy: 1e-4)
        XCTAssertFalse(PoseJump.isJump(from: a, to: fixed, seconds: 0.1))
        // a later pose 1 m ahead of b in b's frame lands 1 m ahead of a
        var later = b; later.columns.3 += b.columns.2 * -1
        let l = c * later
        XCTAssertEqual(simd_distance(SIMD3(l.columns.3.x, l.columns.3.y, l.columns.3.z), SIMD3(a.columns.3.x, a.columns.3.y, a.columns.3.z)), 1, accuracy: 1e-4)
    }
}
