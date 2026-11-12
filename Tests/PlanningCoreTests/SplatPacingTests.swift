import XCTest
@testable import PlanningCore

final class SplatPacingTests: XCTestCase {
    func testRestGrowsWithHeatAndPausesAtCritical() {
        let r = (0...2).map { SplatPacing.restSeconds(thermal: $0)! }
        XCTAssertGreaterThan(r[0], 0)              // paced from the start
        XCTAssertLessThan(r[0], r[1]); XCTAssertLessThan(r[1], r[2])
        XCTAssertNil(SplatPacing.restSeconds(thermal: 3))
        XCTAssertNil(SplatPacing.note(thermal: 0))
        XCTAssertNotNil(SplatPacing.note(thermal: 2))
    }

    func testStageText() {
        XCTAssertTrue(SplatPacing.stage(step: 1200, of: 6000, minutesLeft: 9).hasPrefix("Step 1"))
        XCTAssertTrue(SplatPacing.stage(step: 1200, of: 6000, minutesLeft: 9).hasSuffix("· about 9 min left"))
        XCTAssertFalse(SplatPacing.stage(step: 0, of: 6000, minutesLeft: nil).contains("min"))
    }

    func testCheckpointSaveLoadClear() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("ckpt-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        XCTAssertNil(SplatPacing.Checkpoint.load(from: dir, steps: 6000))
        let export = dir.appendingPathComponent("brush-export_01500.ply")
        try Data("ply".utf8).write(to: export)
        try SplatPacing.Checkpoint(step: 1500, steps: 6000).save(ply: export, to: dir)
        XCTAssertFalse(FileManager.default.fileExists(atPath: export.path))   // moved, not copied
        XCTAssertEqual(SplatPacing.Checkpoint.load(from: dir, steps: 6000), .init(step: 1500, steps: 6000))
        XCTAssertNil(SplatPacing.Checkpoint.load(from: dir, steps: 7000))       // other training length: start over
        // other trainer settings: start over (a 400k checkpoint must not start a 200k-cap training)
        try Data("ply".utf8).write(to: export)
        try SplatPacing.Checkpoint(step: 1500, steps: 6000, setup: "brush 720px 200k sh1").save(ply: export, to: dir)
        XCTAssertNil(SplatPacing.Checkpoint.load(from: dir, steps: 6000, setup: "brush 960px 400k sh1"))
        XCTAssertEqual(SplatPacing.Checkpoint.load(from: dir, steps: 6000, setup: "brush 720px 200k sh1")?.step, 1500)
        try Data("ply".utf8).write(to: export)
        try SplatPacing.Checkpoint(step: 1500, steps: 6000).save(ply: export, to: dir)
        // a newer one replaces it
        try Data("ply2".utf8).write(to: export)
        try SplatPacing.Checkpoint(step: 2000, steps: 6000).save(ply: export, to: dir)
        XCTAssertEqual(SplatPacing.Checkpoint.load(from: dir, steps: 6000)?.step, 2000)
        // the step is never kept without its .ply
        try FileManager.default.removeItem(at: dir.appendingPathComponent("checkpoint.ply"))
        XCTAssertNil(SplatPacing.Checkpoint.load(from: dir, steps: 6000))
        SplatPacing.Checkpoint.clear(in: dir)
        XCTAssertFalse(FileManager.default.fileExists(atPath: dir.appendingPathComponent("checkpoint.json").path))
    }
}

final class SplatPlanTests: XCTestCase {
    func testScalesWithTheScan() {
        let small = SplatPacing.plan(points: 100_000, frames: 3000)
        XCTAssertEqual(small, .init(steps: 3000, pictures: 150, maxSplats: 150_000, startPoints: 75_000))
        let room = SplatPacing.plan(points: 600_000, frames: 4000)
        XCTAssertEqual(room, .init(steps: 6000, pictures: 250, maxSplats: 200_000, startPoints: 100_000))
        let big = SplatPacing.plan(points: 3_000_000, frames: 9000)
        XCTAssertEqual(big, .init(steps: 9000, pictures: 300, maxSplats: 200_000, startPoints: 100_000))
        let mid = SplatPacing.plan(points: 375_000, frames: 4000)
        XCTAssertEqual(mid.steps, 4500)          // halfway between small and typical
        XCTAssertEqual(mid.pictures, 200)
    }

    func testAlwaysInsideTheSafeLimits() {
        for pts in stride(from: 0, through: 5_000_000, by: 37_000) {
            let p = SplatPacing.plan(points: pts, frames: 2000)
            XCTAssertTrue((3000...9000).contains(p.steps))
            XCTAssertEqual(p.steps % 500, 0)
            XCTAssertLessThanOrEqual(p.maxSplats, 200_000)
            XCTAssertLessThan(p.startPoints, p.maxSplats)   // Brush's cap only holds when the start is below it
            XCTAssertTrue((20...300).contains(p.pictures))
        }
    }

    func testShortRecordingLimitsPictures() {
        XCTAssertEqual(SplatPacing.plan(points: 600_000, frames: 400).pictures, 100)   // one picture in 4 frames
        XCTAssertEqual(SplatPacing.plan(points: 600_000, frames: 40).pictures, 20)
    }
}
