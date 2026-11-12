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
