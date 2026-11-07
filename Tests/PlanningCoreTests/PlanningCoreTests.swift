import XCTest
@testable import PlanningCore

final class PlanningCoreTests:XCTestCase {
    func room(points:[(Double,Double)] = [(-3,-3),(3,-3),(3,3),(-3,3)]) -> PlanRoom {
        .init(walls:points.indices.map { i in
            let a = points[i], b = points[(i+1)%points.count]
            return .init(a:.init(x:a.0,y:1.4,z:a.1),b:.init(x:b.0,y:1.4,z:b.1),bottom:0,top:2.8)
        },floor:0,ceiling:2.8)
    }
    func person() -> PlanItem { .init(name:"Subject",kind:.subject,position:.init(x:0,y:0,z:-1),size:.init(x:0.65,y:1.8,z:0.4)) }
    func project() -> PlanProject {
        var p = PlanProject.make(name:"Living room",scanName:"Scan_test",room:room(),items:[person()])
        p.shots[0].camera = .init(position:.init(x:0,y:0.9,z:2),focalMM:24)
        return p
    }
    func testBundledSyntheticFixtureCanBeImportedWithoutLiDAR() throws {
        let fixture = URL(fileURLWithPath:#filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Fixtures/synthetic-room.json")
        let p = try PlanFiles.newProject(from:PlanFiles.read(fixture))
        XCTAssertNil(p.scanName); XCTAssertEqual(p.shots.count,2)
        XCTAssertTrue(try ShotFraming.check(shot:p.shots[0],room:p.room).fits)
        XCTAssertFalse(try ShotFraming.check(shot:p.shots[1],room:p.room).fits)
    }
    func testFOVMatchesFullFrame50mm() {
        XCTAssertEqual(ShotFraming.fov(sensor:36,focal:50)*180 / .pi,39.59775,accuracy:0.0001)
    }
    func testWideLensFitsRealRoomAndTarget() throws {
        let p = project(), r = try ShotFraming.check(shot:p.shots[0],room:p.room)
        XCTAssertTrue(r.fits); XCTAssertTrue(r.inFrame); XCTAssertTrue(r.geometryClosed)
    }
    func testLongLensNeedsStepBack() throws {
        var p = project(); p.shots[0].camera.focalMM = 85
        let r = try ShotFraming.check(shot:p.shots[0],room:p.room)
        XCTAssertFalse(r.fits); XCTAssertFalse(r.inFrame); XCTAssertGreaterThan(r.stepBack,2)
    }
    func testTurnedCameraRejectsSubjectBehind() throws {
        var p = project(); p.shots[0].camera.yaw = .pi
        XCTAssertFalse(try ShotFraming.check(shot:p.shots[0],room:p.room).inFrame)
    }
    func testOpenBoundaryIsNeverVerifiedFit() throws {
        var p = project(); p.room.walls.removeLast()
        let r = try ShotFraming.check(shot:p.shots[0],room:p.room)
        XCTAssertFalse(r.geometryClosed); XCTAssertFalse(r.fits)
    }
    func testOutsideCameraDoesNotPass() throws {
        var p = project(); p.shots[0].camera.position.z = 4
        XCTAssertFalse(try ShotFraming.check(shot:p.shots[0],room:p.room).fits)
    }
    func testRoomVolumeChecksEntireSubjectNotOnlyCentre() throws {
        var p = project(); p.shots[0].items[0].size.y = 4
        XCTAssertFalse(try ShotFraming.check(shot:p.shots[0],room:p.room).targetInside)
    }
    func testItemBlocksSightlineAndCameraOverlap() throws {
        var p = project()
        var block = PlanCatalog.items.last!; block.position = .init(x:0,y:0,z:0.5)
        p.shots[0].items.append(block)
        let r = try ShotFraming.check(shot:p.shots[0],room:p.room)
        XCTAssertTrue(r.itemBlocked); XCTAssertFalse(r.fits)
        p.shots[0].camera.position = .init(x:0,y:0.5,z:0.5)
        XCTAssertFalse(try ShotFraming.check(shot:p.shots[0],room:p.room).cameraInside)
    }
    func testCameraKitAtShootingPositionDoesNotOccludeItsOwnCamera() throws {
        var p = project(); var rig = PlanCatalog.items[3]
        rig.position = .init(x:0,y:0,z:2); p.shots[0].items.append(rig)
        let fit = try ShotFraming.check(shot:p.shots[0],room:p.room)
        XCTAssertTrue(fit.fits); XCTAssertFalse(fit.itemBlocked)
    }
    func testInteriorWallBlocksSightline() throws {
        var p = project()
        p.room.walls.append(.init(a:.init(x:-2,y:1.4,z:0),b:.init(x:2,y:1.4,z:0),bottom:0,top:2.8))
        XCTAssertTrue(try ShotFraming.check(shot:p.shots[0],room:p.room).wallBlocked)
    }
    func testConcaveRoomContainsRealOutlineNotBoundingBox() {
        let r = room(points:[(0,0),(4,0),(4,2),(2,2),(2,4),(0,4)])
        XCTAssertTrue(r.closed); XCTAssertTrue(r.contains(.init(x:1,y:1,z:3)))
        XCTAssertFalse(r.contains(.init(x:3,y:1,z:3)))
    }
    func testDisconnectedAndSelfIntersectingBoundariesNeverPass() {
        let bowtie = room(points:[(-2,-2),(2,2),(-2,2),(2,-2)])
        XCTAssertFalse(bowtie.closed)
        var disconnected = room()
        disconnected.walls += room(points:[(5,5),(7,5),(7,7),(5,7)]).walls
        XCTAssertFalse(disconnected.closed)
    }
    func testConcaveFootprintCrossingNotAllowedEvenWhenCornersAreInside() {
        let r = room(points:[(0,0),(4,0),(4,1),(1,1),(1,4),(0,4)])
        let item = PlanItem(name:"Crosses notch",kind:.furniture,position:.init(x:0.75,y:0,z:0.75),size:.init(x:0.9,y:0.5,z:0.9))
        XCTAssertFalse(Placement.canPlace(item,in:r,avoiding:[]))
    }
    func testInvalidImportedPresetCannotSmuggleScannedFurniture() {
        var furniture = person(); furniture.source = .scanned
        XCTAssertThrowsError(try PlanFiles.encode(.preset(.init(name:"Bad",items:[furniture]))))
    }
    func testRotatedRoomContainmentAndQuickAdd() throws {
        var r = room()
        r.walls = r.walls.map { .init(a:$0.a.rotated(yaw:.pi/4),b:$0.b.rotated(yaw:.pi/4),bottom:$0.bottom,top:$0.top) }
        var item = PlanCatalog.items[0]
        item.position = try Placement.quickPosition(for:item,in:r,avoiding:[])
        XCTAssertTrue(Placement.canPlace(item,in:r,avoiding:[]))
        item.position = .init(x:4,y:0,z:4)
        XCTAssertFalse(Placement.canPlace(item,in:r,avoiding:[]))
    }
    func testQuickAddFindsClearNonoverlappingPosition() throws {
        var item = PlanCatalog.items[0]
        item.position = try Placement.quickPosition(for:item,in:room(),avoiding:[person()])
        XCTAssertTrue(Placement.canPlace(item,in:room(),avoiding:[person()]))
        var second = item; second.id = UUID()
        second.position = try Placement.quickPosition(for:second,in:room(),avoiding:[item])
        XCTAssertNotEqual(item.position,second.position)
        XCTAssertEqual(item.position,Placement.snapped(item.position))
        XCTAssertEqual(second.position,Placement.snapped(second.position))
    }
    func testOversizedItemReturnsActionableNoSpaceError() {
        var item = PlanCatalog.items[0]; item.size.x = 20
        XCTAssertThrowsError(try Placement.quickPosition(for:item,in:room(),avoiding:[]))
    }
    func testTenCentimetreSnap() {
        XCTAssertEqual(Placement.snapped(.init(x:0.27,y:0.3,z:-0.24)),.init(x:0.3,y:0.3,z:-0.2))
    }
    func testIndependentDuplicateAndTargetRemapping() {
        let original = project().shots[0]
        var copy = original.duplicated(); copy.items[0].position.x = 1; copy.camera.focalMM = 85; copy.notes = "New angle"
        XCTAssertNotEqual(copy.id,original.id); XCTAssertNotEqual(copy.targetID,original.targetID)
        XCTAssertEqual(copy.targetID,copy.items[0].id); XCTAssertEqual(original.items[0].position.x,0)
        XCTAssertEqual(original.camera.focalMM,24); XCTAssertEqual(original.notes,"")
    }
    func testBudgetAllocationsIntentionallyCountRepeatedGear() throws {
        var p = project(); p.shots[0].items.append(PlanCatalog.items[0]); p.shots[0].days = 2
        p.shots.append(p.shots[0].duplicated())
        XCTAssertEqual(p.shots[0].dailyTotal,35); XCTAssertEqual(p.shots[0].total,70); XCTAssertEqual(p.total,140)
        try p.validate()
    }
    func testPresetsOnlyCaptureEquipmentAndRestoreRelativeToOtherRoom() throws {
        var p = project()
        var light = PlanCatalog.items[0]; light.position = .init(x:1,y:0,z:0)
        p.shots[0].items.append(light)
        var table = PlanCatalog.items[8]; table.position = .init(x:-1.5,y:0,z:0)
        p.shots[0].items.append(table)
        let preset = EquipmentPreset.capture(name:"Kit A",shot:p.shots[0],room:p.room)
        XCTAssertEqual(preset.items.count,1)
        let next = try preset.applying(to:p.shots[0],room:p.room)
        XCTAssertEqual(next.items.filter(\.equipment).count,1); XCTAssertEqual(next.items.count,3)
        XCTAssertEqual(next.targetID,p.shots[0].targetID); XCTAssertNotEqual(next.items.last!.id,light.id)
        var shifted = p.room
        shifted.walls = shifted.walls.map { var w = $0; w.a.x += 10; w.b.x += 10; return w }
        var empty = p.shots[0]; empty.items = []; empty.targetID = nil
        let applied = try preset.applying(to:empty,room:shifted)
        XCTAssertEqual(applied.items[0].position.x,11,accuracy:0.001)
    }
    func testPresetRelocationPreservesHeightAboveFloor() throws {
        var p = project(); var light = PlanCatalog.items[0]
        light.size.y = 1.0; light.position = .init(x:0,y:0.4,z:-1)
        // Original placement overlaps the subject, forcing the preset reflow path.
        let preset = EquipmentPreset(name:"Raised light",items:[light])
        p.shots[0] = try preset.applying(to:p.shots[0],room:p.room)
        XCTAssertEqual(p.shots[0].items.last!.position.y,0.4)
        XCTAssertNotEqual(p.shots[0].items.last!.position.z,-1)
    }
    func testAllStarterPresetsActuallyApplyInRoom() throws {
        var shot = project().shots[0]
        for preset in PlanCatalog.starterPresets {
            shot = try preset.applying(to:shot,room:room())
            XCTAssertEqual(shot.items.filter(\.equipment).count,preset.items.count)
            for item in shot.items.filter(\.equipment) { XCTAssertTrue(Placement.canPlace(item,in:room(),avoiding:shot.items)) }
        }
    }
    func testFailedPresetDoesNotMutateInput() {
        let p = project(); let original = p.shots[0]
        var item = PlanCatalog.items[0]; item.size.x = 20
        let preset = EquipmentPreset(name:"Too big",items:[item])
        XCTAssertThrowsError(try preset.applying(to:original,room:p.room))
        XCTAssertEqual(original,p.shots[0])
    }
    func testProjectViewAndPresetJSONRoundTrips() throws {
        let p = project()
        for transfer in [PlanTransfer.project(p),.shot(room:p.room,shot:p.shots[0]),.preset(PlanCatalog.starterPresets[0])] {
            XCTAssertEqual(try PlanFiles.decode(PlanFiles.encode(transfer)),transfer)
        }
    }
    func testInvalidVersionMalformedAndTooLargeRejected() throws {
        let p = project(), valid = try PlanFiles.encode(.project(p))
        let bad = String(decoding:valid,as:UTF8.self).replacingOccurrences(of:"\"version\" : 1",with:"\"version\" : 99")
        XCTAssertThrowsError(try PlanFiles.decode(Data(bad.utf8)))
        XCTAssertThrowsError(try PlanFiles.decode(Data("{}".utf8)))
        XCTAssertThrowsError(try PlanFiles.decode(Data(repeating:32,count:PlanFiles.maxBytes+1)))
    }
    func testInvalidNumericFieldsDuplicateIDsAndMissingTargetRejected() {
        var p = project(); p.shots[0].items[0].dailyRate = -1; XCTAssertThrowsError(try p.validate())
        p = project(); p.shots[0].camera.focalMM = .nan; XCTAssertThrowsError(try p.validate())
        p = project(); p.shots[0].items[0].size.y = 0; XCTAssertThrowsError(try p.validate())
        p = project(); p.shots[0].targetID = UUID(); XCTAssertThrowsError(try p.validate())
        p = project(); p.shots.append(p.shots[0]); XCTAssertThrowsError(try p.validate())
        p = project(); p.shots[0].items.append(p.shots[0].items[0]); XCTAssertThrowsError(try p.validate())
        p = project(); p.shots[0].days = 0; XCTAssertThrowsError(try p.validate())
        p = project(); p.name = String(repeating:"x",count:121); XCTAssertThrowsError(try p.validate())
    }
    func testFileLimitsForViewsItemsPresetsAndNotes() {
        var p = project(); p.shots = (0..<51).map { _ in p.shots[0].duplicated() }; XCTAssertThrowsError(try p.validate())
        p = project(); p.shots[0].items = (0..<101).map { _ in person() }; XCTAssertThrowsError(try p.validate())
        p = project(); p.shots[0].notes = String(repeating:"x",count:10001); XCTAssertThrowsError(try p.validate())
        p = project(); p.presets = (0..<51).map { _ in EquipmentPreset(name:"x",items:[]) }; XCTAssertThrowsError(try p.validate())
    }
    func testAppendImportsAddWithoutOverwritingAndRejectOtherRooms() throws {
        let p = project()
        let next = try PlanFiles.append(.shot(room:p.room,shot:p.shots[0]),to:p)
        XCTAssertEqual(next.shots.count,2); XCTAssertEqual(next.shots[0],p.shots[0]); XCTAssertNotEqual(next.shots[1].id,p.shots[0].id)
        var other = p.room; other.walls[0].a.x += 0.1
        XCTAssertThrowsError(try PlanFiles.append(.shot(room:other,shot:p.shots[0]),to:p))
        let imported = try PlanFiles.newProject(from:.project(p))
        XCTAssertNotEqual(imported.id,p.id); XCTAssertNil(imported.scanName)
    }
    func testAppendCapacityRejectsAtomically() {
        var p = project(); p.shots = (0..<50).map { _ in p.shots[0].duplicated() }
        let snapshot = p
        XCTAssertThrowsError(try PlanFiles.append(.shot(room:p.room,shot:p.shots[0]),to:p))
        XCTAssertEqual(p,snapshot)
    }
    func testEndToEndEditPresetStoryboardExportImportPersistAndFrame() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:dir) }
        let disk = PlanDiskStore(directory:dir)
        var p = project(); var light = PlanCatalog.items[0]
        light.position = try Placement.quickPosition(for:light,in:p.room,avoiding:p.shots[0].items)
        p.shots[0].items.append(light); p.shots[0].days = 2
        p.presets.append(EquipmentPreset.capture(name:"My kit",shot:p.shots[0],room:p.room))
        p.shots.append(p.shots[0].duplicated()); p.shots[1].camera.focalMM = 85
        let transfer = try PlanFiles.decode(PlanFiles.encode(.project(p)))
        let imported = try PlanFiles.newProject(from:transfer)
        try disk.save(imported)
        let reloaded = try disk.load(imported.id)
        XCTAssertEqual(reloaded,imported); XCTAssertEqual(reloaded.total,140)
        XCTAssertEqual(reloaded.presets.count,1); XCTAssertEqual(reloaded.presets[0].items.count,1)
        XCTAssertFalse(try ShotFraming.check(shot:reloaded.shots[1],room:reloaded.room).inFrame)
        let appended = try PlanFiles.append(transfer,to:p)
        XCTAssertEqual(appended.shots.count,4); XCTAssertEqual(appended.presets.count,2)
        XCTAssertNotEqual(appended.presets[0].id,appended.presets[1].id)
    }
    func testAtomicDiskSaveReloadAndCorruptionIsVisible() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:dir) }
        let disk = PlanDiskStore(directory:dir); var p = project()
        try disk.save(p); XCTAssertEqual(try disk.load(p.id),p)
        p.shots[0].notes = "Persist after restart"; try disk.save(p)
        XCTAssertEqual(try disk.load(p.id).shots[0].notes,p.shots[0].notes)
        let result = try disk.list(); XCTAssertEqual(result.projects.count,1); XCTAssertTrue(result.errors.isEmpty)
        let file = dir.appendingPathComponent(p.id.uuidString).appendingPathExtension("json")
        try Data("corrupt".utf8).write(to:file)
        let bad = try disk.list(); XCTAssertTrue(bad.projects.isEmpty); XCTAssertEqual(bad.errors.count,1)
        XCTAssertEqual(try Data(contentsOf:file),Data("corrupt".utf8))
    }
    func testFailedSavePreservesPreviousDiskFile() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:dir) }
        let disk = PlanDiskStore(directory:dir); let p = project(); try disk.save(p)
        var broken = p; broken.shots[0].days = -2
        XCTAssertThrowsError(try disk.save(broken)); XCTAssertEqual(try disk.load(p.id),p)
    }
    func testImportedNamesNeverBecomePathsAndIdentityMismatchFails() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:dir) }
        let disk = PlanDiskStore(directory:dir); var p = project(); p.name = "../../escape"; p.scanName = "../../secret"
        try disk.save(p)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath:dir.path),[p.id.uuidString+".json"])
        let otherID = UUID(), otherFile = dir.appendingPathComponent(otherID.uuidString).appendingPathExtension("json")
        try PlanFiles.encode(.project(p)).write(to:otherFile)
        XCTAssertThrowsError(try disk.load(otherID))
    }
    func testUndoRedoAndNewEditClearsRedoWithBoundedHistory() {
        var h = PlanHistory(); var p = project(); let original = p
        h.record(p); p.shots[0].notes = "Change"; let changed = p
        p = h.undo(current:p)!; XCTAssertEqual(p,original)
        p = h.redo(current:p)!; XCTAssertEqual(p,changed)
        p = h.undo(current:p)!; h.record(p); XCTAssertTrue(h.redo.isEmpty)
        for i in 0..<50 { h.record(p); p.name = "Change \(i)" }
        XCTAssertEqual(h.undo.count,30)
    }
}
