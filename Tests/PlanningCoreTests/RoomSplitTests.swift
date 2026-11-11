import XCTest
@testable import PlanningCore

final class RoomSplitTests: XCTestCase {
    /// Android's disc step, offset by offset (FloorArea.java `morph`).
    private func bruteMorph(_ a: [Bool], _ nx: Int, _ nz: Int, _ r: Int, grow: Bool) -> [Bool] {
        var out = [Bool](repeating: false, count: a.count)
        for z in 0..<nz {
            for x in 0..<nx {
                var v = !grow
                outer: for dz in -r...r {
                    for dx in -r...r where dx * dx + dz * dz <= r * r {
                        let xx = x + dx, zz = z + dz
                        let s = xx >= 0 && zz >= 0 && xx < nx && zz < nz && a[zz * nx + xx]
                        if grow && s { v = true; break outer }
                        if !grow && !s { v = false; break outer }
                    }
                }
                out[z * nx + x] = v
            }
        }
        return out
    }

    func testDiscStepsMatchAndroid() {
        var rng = SystemRandomNumberGenerator()
        for trial in 0..<12 {
            let nx = 20 + trial * 3, nz = 31 - trial
            // Blobs, so erosion keeps something.
            var a = [Bool](repeating: false, count: nx * nz)
            for _ in 0..<6 {
                let cx = Int.random(in: 0..<nx, using: &rng), cz = Int.random(in: 0..<nz, using: &rng), rr = Int.random(in: 2...9, using: &rng)
                for z in 0..<nz { for x in 0..<nx where (x - cx) * (x - cx) + (z - cz) * (z - cz) <= rr * rr { a[z * nx + x] = true } }
            }
            for r in [1, 3, 9] {
                XCTAssertEqual(RoomSplit.dilate(a, nx, nz, r), bruteMorph(a, nx, nz, r, grow: true), "dilate r=\(r) trial \(trial)")
                XCTAssertEqual(RoomSplit.erode(a, nx, nz, r), bruteMorph(a, nx, nz, r, grow: false), "erode r=\(r) trial \(trial)")
            }
        }
    }

    /// Points of a synthetic flat: two rooms (4 × 3 m and 3 × 3 m) joined by an 80 cm doorway, walls 2.5 m high.
    private func twoRooms() -> [Float] {
        var p: [Float] = []
        func add(_ x: Float, _ y: Float, _ z: Float) { p += [x, y, z] }
        let step: Float = 0.02
        // Floors.
        for x in stride(from: Float(0.02), to: 7.0, by: step) { for z in stride(from: Float(0.02), to: 3.0, by: step) { add(x, 0, z) } }
        // Walls: the outside, and the one between the rooms at x = 4 with a doorway from z = 1.1 to 1.9.
        func wall(x0: Float, z0: Float, x1: Float, z1: Float, gap: ClosedRange<Float>? = nil) {
            let len = hypot(x1 - x0, z1 - z0)
            for t in stride(from: Float(0), through: len, by: step) {
                let x = x0 + (x1 - x0) * t / len, z = z0 + (z1 - z0) * t / len
                if let gap, gap.contains(z) { continue }
                for y in stride(from: Float(0.05), to: 2.5, by: 0.05) { add(x, y, z) }
            }
        }
        wall(x0: 0, z0: 0, x1: 7, z1: 0); wall(x0: 7, z0: 0, x1: 7, z1: 3); wall(x0: 7, z0: 3, x1: 0, z1: 3); wall(x0: 0, z0: 3, x1: 0, z1: 0)
        wall(x0: 4, z0: 0, x1: 4, z1: 3, gap: 1.1...1.9)
        return p
    }

    func testTwoRoomsSplitAtTheDoorway() {
        let r = RoomSplit.compute(twoRooms(), floorY: 0)
        XCTAssertEqual(r.rooms.count, 2)
        guard r.rooms.count == 2 else { return }
        XCTAssertEqual(r.rooms[0].shown, 12, accuracy: 0.8)
        XCTAssertEqual(r.rooms[1].shown, 9, accuracy: 0.8)
        XCTAssertEqual(r.rooms[0].w, 4, accuracy: 0.15)
        XCTAssertEqual(r.rooms[0].l, 3, accuracy: 0.15)
        XCTAssertEqual(r.room(atX: 2, z: 1.5), 0)
        XCTAssertEqual(r.room(atX: 5.5, z: 1.5), 1)
        XCTAssertNil(r.room(atX: -3, z: 1.5))
    }

    /// The same numbers as Android's FloorArea.java on real iPhone scans. Run with CINEX_FLOOR_SCANS set to a folder of
    /// scan folders in Android's points.bin format, each with a floorY.txt (scans are private: never in the repo).
    func testRealScansMatchJava() throws {
        guard let root = ProcessInfo.processInfo.environment["CINEX_FLOOR_SCANS"] else { throw XCTSkip("CINEX_FLOOR_SCANS not set") }
        for name in try FileManager.default.contentsOfDirectory(atPath: root).sorted() {
            let dir = URL(fileURLWithPath: root).appendingPathComponent(name)
            guard let data = try? Data(contentsOf: dir.appendingPathComponent("points.bin")),
                  let fy = try? String(contentsOf: dir.appendingPathComponent("floorY.txt"), encoding: .utf8),
                  let floorY = Float(fy.trimmingCharacters(in: .whitespacesAndNewlines)) else { continue }
            let n = data.withUnsafeBytes { Int(Int32(bigEndian: $0.loadUnaligned(as: Int32.self))) }
            var pos = [Float](repeating: 0, count: n * 3)
            data.withUnsafeBytes { raw in
                for i in 0..<n {
                    for k in 0..<3 {
                        pos[i * 3 + k] = Float(bitPattern: UInt32(bigEndian: raw.loadUnaligned(fromByteOffset: 4 + i * 15 + k * 4, as: UInt32.self)))
                    }
                }
            }
            let start = Date()
            let r = RoomSplit.compute(pos, floorY: floorY)
            print(String(format: "%@: main %.1f m², other %.1f m², outline %.0f%% walls in %.0f ms", name, r.area, r.other,
                         r.enclosed * 100, Date().timeIntervalSince(start) * 1000))
            for (i, room) in r.rooms.enumerated() {
                print(String(format: "  Room %d: ≈%@ m², %.1f × %.1f m (walls on %d sides: %.1f m²; seen floor %.1f, wall-aligned outline %.1f m²)",
                             i + 1, FloorOutline.m2(Double(room.shown)), room.w, room.l, room.wallSides, room.walled, room.area, room.hull))
            }
        }
    }
}
