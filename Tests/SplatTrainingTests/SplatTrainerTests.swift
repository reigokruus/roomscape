import Foundation
import XCTest
import simd
@testable import SplatTraining

final class SplatTrainerTests: XCTestCase {
    /// A camera at `eye` looking down -z (no rotation), 128 x 96 pixels.
    fileprivate func camera(eye: SIMD3<Float>, rgba: [UInt8], width: Int = 128, height: Int = 96) -> SplatTrainer.View {
        var toWorld = matrix_identity_float4x4
        toWorld.columns.3 = SIMD4(eye, 1)
        return SplatTrainer.View(worldToCamera: toWorld.inverse, fx: 110, fy: 110, cx: Float(width) / 2, cy: Float(height) / 2,
                                 width: width, height: height, rgba: rgba)
    }

    /// A photo of a red square on the left and a blue one on the right over grey-green.
    fileprivate func photo(width: Int = 128, height: Int = 96) -> [UInt8] {
        var px = [UInt8](repeating: 0, count: width * height * 4)
        for y in 0..<height {
            for x in 0..<width {
                let o = (y * width + x) * 4
                var c: (UInt8, UInt8, UInt8) = (90, 140, 100)
                if (20..<60).contains(x), (25..<70).contains(y) { c = (220, 40, 30) }
                if (75..<115).contains(x), (15..<55).contains(y) { c = (30, 60, 210) }
                px[o] = c.0; px[o + 1] = c.1; px[o + 2] = c.2; px[o + 3] = 255
            }
        }
        return px
    }

    /// A 2 m x 1.5 m wall of seeds 2 m in front of the camera, with a little depth noise and wrong colours.
    fileprivate func wallSeeds() -> [SplatTrainer.Seed] {
        var seeds: [SplatTrainer.Seed] = []
        var rng = SystemRandomNumberGenerator()
        for iy in 0..<30 {
            for ix in 0..<40 {
                let x = -1 + Float(ix) * 0.05, y = -0.75 + Float(iy) * 0.05
                let z = -2 + Float.random(in: -0.03...0.03, using: &rng)
                seeds.append(SplatTrainer.Seed(position: SIMD3(x, y, z), normal: SIMD3(0, 0, 1), radius: 0.03,
                                               color: SIMD3(0.5, 0.5, 0.5), wallAngle: -100))
            }
        }
        return seeds
    }

    func testGPUMatchesCPUReference() throws {
        let views = [camera(eye: SIMD3(0, 0, 0), rgba: photo()), camera(eye: SIMD3(0.2, 0.1, 0.1), rgba: photo())]
        let trainer = try SplatTrainer(seeds: wallSeeds(), views: views)
        // A few steps first, so opacity, size and offsets aren't all at their starting values.
        for i in 0..<6 { trainer.step(view: i % 2) }
        let params = trainer.parameters
        for v in 0..<2 {
            let ref = CPUReference.lossAndGradients(params, view: views[v])
            let gpuLoss = trainer.step(view: v, update: false)
            let gpu = trainer.rawGradients(view: v)
            XCTAssertEqual(gpuLoss, ref.loss, accuracy: ref.loss * 1e-3, "loss, view \(v)")
            for q in 0..<7 {
                var dot: Double = 0, nr: Double = 0, ng: Double = 0
                for i in 0..<params.count {
                    let a = Double(ref.grads[i * 8 + q]), b = Double(gpu[i * 8 + q])
                    dot += a * b; nr += a * a; ng += b * b
                }
                guard nr > 0 else { continue }
                let cosine = dot / (sqrt(nr) * sqrt(ng) + 1e-30)
                let ratio = sqrt(ng / nr)
                XCTAssertGreaterThan(cosine, 0.999, "gradient \(q) direction, view \(v)")
                XCTAssertEqual(ratio, 1, accuracy: 0.01, "gradient \(q) size, view \(v)")
            }
        }
    }

    func testTrainingLowersTheError() throws {
        let views = [camera(eye: SIMD3(0, 0, 0), rgba: photo()), camera(eye: SIMD3(0.15, 0, 0.05), rgba: photo())]
        let trainer = try SplatTrainer(seeds: wallSeeds(), views: views)
        let before = trainer.step(view: 0, update: false)
        for i in 0..<200 { trainer.step(view: i % 2) }
        let after = trainer.step(view: 0, update: false)
        print("synthetic: error \(before) -> \(after)")
        XCTAssertLessThan(after, before * 0.5)
        // Offsets stay within 12 cm, colours within 0...1.
        for s in trainer.parameters {
            XCTAssertLessThanOrEqual(max(abs(s.ox), abs(s.oy), abs(s.oz)), SplatTrainer.maxOffset + 1e-6)
            XCTAssert((0...1).contains(s.r) && (0...1).contains(s.g) && (0...1).contains(s.b))
        }
    }

    func testSeedsMergeIntoCells() {
        // 4 surfels in one 3 cm cell, 1 in another.
        let a = SplatTrainer.Surfel(position: SIMD3(0.005, 0.005, 0.005), normal: SIMD3(0, 1, 0), radius: 0.01,
                                    rgba: 0xFF_00_00_FF, wallAngle: -100)
        var b = a; b.position = SIMD3(0.015, 0.005, 0.005); b.normal = SIMD3(0, -1, 0)
        var c = a; c.position = SIMD3(0.025, 0.02, 0.01)
        var d = a; d.position = SIMD3(0.005, 0.025, 0.02); d.wallAngle = 1.5
        var e = a; e.position = SIMD3(0.5, 0.5, 0.5); e.rgba = 0xFF_FF_00_00
        let (seeds, cell) = SplatTrainer.seeds(from: [a, b, c, d, e], noWall: -100)
        XCTAssertEqual(seeds.count, 2)
        XCTAssertEqual(cell, 0.03)
        let big = seeds.first { abs($0.position.x - 0.0125) < 1e-4 }!
        XCTAssertEqual(big.color.x, 1, accuracy: 1e-5)
        XCTAssertEqual(abs(big.normal.y), 1, accuracy: 1e-5) // opposite normals were flipped to agree
        XCTAssertEqual(big.wallAngle, 1.5)
        XCTAssertEqual(big.radius, 0.018, accuracy: 1e-6) // at least 0.6 cell
        let single = seeds.first { $0.position.x > 0.4 }!
        XCTAssertEqual(single.color.z, 1, accuracy: 1e-5)
        XCTAssertEqual(single.radius, 0.018 * 0.8, accuracy: 1e-6)
    }

    /// The real scan copied from the phone, when `CINEX_SCAN` points at its `.capture` folder.
    func testRealScan() throws {
        guard let path = ProcessInfo.processInfo.environment["CINEX_SCAN"] else { throw XCTSkip("set CINEX_SCAN") }
        let folder = URL(fileURLWithPath: path)
        let surfels = try readSurfels(folder.appendingPathComponent("surfels.bin"))
        let t0 = Date()
        let (seeds, cell) = SplatTrainer.seeds(from: surfels, noWall: -100)
        print("real: \(seeds.count) splats (\(Int(cell * 1000)) mm) from \(surfels.count) surfels in \(Int(Date().timeIntervalSince(t0) * 1000)) ms")

        struct Record: Decodable { var file: String; var width: Int; var height: Int; var fx: Float; var fy: Float; var cx: Float; var cy: Float; var cameraToWorld: [Float] }
        let records = try JSONDecoder().decode([Record].self, from: Data(contentsOf: folder.appendingPathComponent("keyframes.json")))
        let views = records.compactMap { r -> SplatTrainer.View? in
            let m = r.cameraToWorld
            let toWorld = simd_float4x4(SIMD4(m[0], m[1], m[2], m[3]), SIMD4(m[4], m[5], m[6], m[7]),
                                        SIMD4(m[8], m[9], m[10], m[11]), SIMD4(m[12], m[13], m[14], m[15]))
            return SplatTrainer.view(photo: folder.appendingPathComponent(r.file), photoWidth: r.width, photoHeight: r.height,
                                     fx: r.fx, fy: r.fy, cx: r.cx, cy: r.cy, cameraToWorld: toWorld)
        }
        XCTAssertEqual(views.count, records.count)

        // The photo is the right way up: the starting splats match it better than they match it flipped.
        let flipped = views.map { v -> SplatTrainer.View in
            var f = v
            for y in 0..<v.height { for x in 0..<v.width * 4 { f.rgba[y * v.width * 4 + x] = v.rgba[(v.height - 1 - y) * v.width * 4 + x] } }
            return f
        }
        let upright = try SplatTrainer(seeds: seeds, views: Array(views.prefix(8)))
        let upsideDown = try SplatTrainer(seeds: seeds, views: Array(flipped.prefix(8)))
        let a = (0..<8).map { upright.step(view: $0, update: false) }.reduce(0, +)
        let b = (0..<8).map { upsideDown.step(view: $0, update: false) }.reduce(0, +)
        print("real: error upright \(a / 8), flipped \(b / 8)")
        XCTAssertLessThan(a, b)

        // Train like the app does after a scan, holding out every 8th photo to check the gain on unseen views.
        let held = Set(stride(from: 3, to: views.count, by: 8))
        let train = (0..<views.count).filter { !held.contains($0) }
        let trainer = try SplatTrainer(seeds: seeds, views: views)
        let heldBefore = held.map { trainer.step(view: $0, update: false) }.reduce(0, +)
        var rng = SystemRandomNumberGenerator()
        let start = Date()
        for _ in 0..<400 { trainer.step(view: train.randomElement(using: &rng)!) }
        let ms = Date().timeIntervalSince(start) * 1000 / 400
        let heldAfter = held.map { trainer.step(view: $0, update: false) }.reduce(0, +)
        print("real: 400 steps, \(String(format: "%.1f", ms)) ms each; held-out error \(heldBefore / Float(held.count)) -> \(heldAfter / Float(held.count)) (\(Int((1 - heldAfter / heldBefore) * 100))% better)")
        // Photos it trained on must match better; photos left out only do when the scan's photos vary enough
        // (a small scan from one spot learns its photos by heart, and the app then keeps the raw splats).
        let fresh = try SplatTrainer(seeds: seeds, views: views)
        let onTrained = train.prefix(8).map { trainer.step(view: $0, update: false) }.reduce(0, +)
        let onTrainedBefore = train.prefix(8).map { fresh.step(view: $0, update: false) }.reduce(0, +)
        XCTAssertLessThan(onTrained, onTrainedBefore)
    }

    private func readSurfels(_ url: URL) throws -> [SplatTrainer.Surfel] {
        let data = try Data(contentsOf: url)
        return data.withUnsafeBytes { raw in
            let count = Int(raw.load(fromByteOffset: 4, as: UInt32.self))
            return (0..<count).map { i in
                let o = 8 + i * 36
                func f(_ k: Int) -> Float { raw.loadUnaligned(fromByteOffset: o + k * 4, as: Float.self) }
                return SplatTrainer.Surfel(position: SIMD3(f(0), f(1), f(2)), normal: SIMD3(f(3), f(4), f(5)), radius: f(6),
                                           rgba: raw.loadUnaligned(fromByteOffset: o + 28, as: UInt32.self), wallAngle: f(8))
            }
        }
    }
}

/// How trained splats look as the viewer's hard discs (camera-facing circles, depth-tested): how much of a photo
/// they cover and how close they come to it, for a display rule giving each splat's disc radius (nil = hidden).
enum DiscCheck {
    static func evaluate(_ splats: [SplatTrainer.Output], view v: SplatTrainer.View,
                         radius: (SplatTrainer.Output) -> Float?) -> (coverage: Float, error: Float) {
        let W = v.width, H = v.height
        var depth = [Float](repeating: .infinity, count: W * H)
        var color = [SIMD3<Float>](repeating: SIMD3(repeating: SplatTrainer.background), count: W * H)
        for s in splats {
            guard let r = radius(s) else { continue }
            let c = v.worldToCamera * SIMD4(s.position, 1)
            let d = -c.z
            guard d > 0.15 else { continue }
            let u = v.fx * c.x / d + v.cx, w = v.cy - v.fy * c.y / d, pr = r * v.fx / d
            let x0 = max(0, Int(u - pr)), x1 = min(W - 1, Int(u + pr)), y0 = max(0, Int(w - pr)), y1 = min(H - 1, Int(w + pr))
            guard x0 <= x1, y0 <= y1 else { continue }
            for y in y0...y1 {
                for x in x0...x1 {
                    let dx = Float(x) + 0.5 - u, dy = Float(y) + 0.5 - w
                    guard dx * dx + dy * dy <= pr * pr, d < depth[y * W + x] else { continue }
                    depth[y * W + x] = d
                    color[y * W + x] = s.color
                }
            }
        }
        var covered = 0
        var err: Float = 0
        for p in 0..<(W * H) {
            if depth[p] < .infinity { covered += 1 }
            let img = SIMD3(Float(v.rgba[p * 4]), Float(v.rgba[p * 4 + 1]), Float(v.rgba[p * 4 + 2])) / 255
            let e = color[p] - img
            err += simd_dot(e, e)
        }
        return (Float(covered) / Float(W * H), err / Float(W * H))
    }
}

extension SplatTrainerTests {
    func testDisplayRules() throws {
        guard let path = ProcessInfo.processInfo.environment["CINEX_SCAN"] else { throw XCTSkip("set CINEX_SCAN") }
        let folder = URL(fileURLWithPath: path)
        let data = try Data(contentsOf: folder.appendingPathComponent("surfels.bin"))
        let surfels: [SplatTrainer.Surfel] = data.withUnsafeBytes { raw in
            (0..<Int(raw.load(fromByteOffset: 4, as: UInt32.self))).map { i in
                let o = 8 + i * 36
                func f(_ k: Int) -> Float { raw.loadUnaligned(fromByteOffset: o + k * 4, as: Float.self) }
                return SplatTrainer.Surfel(position: SIMD3(f(0), f(1), f(2)), normal: SIMD3(f(3), f(4), f(5)), radius: f(6),
                                           rgba: raw.loadUnaligned(fromByteOffset: o + 28, as: UInt32.self), wallAngle: f(8))
            }
        }
        struct Record: Decodable { var file: String; var width: Int; var height: Int; var fx: Float; var fy: Float; var cx: Float; var cy: Float; var cameraToWorld: [Float] }
        let records = try JSONDecoder().decode([Record].self, from: Data(contentsOf: folder.appendingPathComponent("keyframes.json")))
        let views = records.compactMap { r -> SplatTrainer.View? in
            let m = r.cameraToWorld
            return SplatTrainer.view(photo: folder.appendingPathComponent(r.file), photoWidth: r.width, photoHeight: r.height,
                                     fx: r.fx, fy: r.fy, cx: r.cx, cy: r.cy,
                                     cameraToWorld: simd_float4x4(SIMD4(m[0], m[1], m[2], m[3]), SIMD4(m[4], m[5], m[6], m[7]),
                                                                  SIMD4(m[8], m[9], m[10], m[11]), SIMD4(m[12], m[13], m[14], m[15])))
        }
        let (seeds, _) = SplatTrainer.seeds(from: surfels, noWall: -100)
        let trainer = try SplatTrainer(seeds: seeds, views: views)
        let held = Array(stride(from: 3, to: views.count, by: 8))
        let untrained = trainer.output(minOpacity: 0)
        for _ in 0..<400 { trainer.step(view: (0..<views.count).filter { !held.contains($0) }.randomElement()!) }
        let trained = trainer.output(minOpacity: 0.08)
        let opacities = trained.map(\.opacity).sorted()
        print("display: opacity quartiles \(opacities[opacities.count / 4]) \(opacities[opacities.count / 2]) \(opacities[3 * opacities.count / 4])")
        let rules: [(String, [SplatTrainer.Output], (SplatTrainer.Output) -> Float?)] = [
            ("untrained, 1.4 x radius", untrained, { $0.sigma / 0.6 * 1.4 }),
            ("trained, alpha >= 0.25 core", trained, { s in s.opacity > 0.25 ? s.sigma * (2 * log(s.opacity / 0.25)).squareRoot() : nil }),
            ("trained, 1.4 x trained radius", trained, { $0.sigma / 0.6 * 1.4 }),
            ("trained, 1.4 x radius, opacity > 0.3", trained, { $0.opacity > 0.3 ? $0.sigma / 0.6 * 1.4 : nil }),
            ("trained, 2 sigma", trained, { $0.sigma * 2 }),
        ]
        for (name, splats, rule) in rules {
            var cov: Float = 0, err: Float = 0
            for h in held { let r = DiscCheck.evaluate(splats, view: views[h], radius: rule); cov += r.coverage; err += r.error }
            print("display: \(name): covers \(Int(cov / Float(held.count) * 100))%, error \(err / Float(held.count))")
        }
    }
}

extension SplatTrainerTests {
    func testSplatsOnAPlaneStayOnIt() throws {
        let views = [camera(eye: SIMD3(0, 0, 0), rgba: photo()), camera(eye: SIMD3(0.15, 0, 0.05), rgba: photo())]
        var seeds = wallSeeds()
        for i in seeds.indices where i % 2 == 0 { seeds[i].plane = SIMD3(0, 0, 1) } // half of them lie on the wall
        let trainer = try SplatTrainer(seeds: seeds, views: views)
        for i in 0..<200 { trainer.step(view: i % 2) }
        let params = trainer.parameters
        var freeMoved = false
        for (i, s) in params.enumerated() {
            if i % 2 == 0 { XCTAssertEqual(s.oz, 0, accuracy: 1e-6, "pinned splat \(i) left its plane") }
            else if abs(s.oz) > 1e-3 { freeMoved = true }
            XCTAssertLessThanOrEqual(max(abs(s.ox), abs(s.oy)), SplatTrainer.maxOffset + 1e-6)
        }
        XCTAssert(freeMoved, "free splats should still move in depth")
    }
}

extension SplatTrainerTests {
    /// How the error on photos it doesn't train on falls over a long run (for choosing when to stop).
    func testHeldOutCurve() throws {
        guard let path = ProcessInfo.processInfo.environment["CINEX_SCAN"] else { throw XCTSkip("set CINEX_SCAN") }
        let folder = URL(fileURLWithPath: path)
        let data = try Data(contentsOf: folder.appendingPathComponent("surfels.bin"))
        let surfels: [SplatTrainer.Surfel] = data.withUnsafeBytes { raw in
            (0..<Int(raw.load(fromByteOffset: 4, as: UInt32.self))).map { i in
                let o = 8 + i * 36
                func f(_ k: Int) -> Float { raw.loadUnaligned(fromByteOffset: o + k * 4, as: Float.self) }
                return SplatTrainer.Surfel(position: SIMD3(f(0), f(1), f(2)), normal: SIMD3(f(3), f(4), f(5)), radius: f(6),
                                           rgba: raw.loadUnaligned(fromByteOffset: o + 28, as: UInt32.self), wallAngle: f(8))
            }
        }
        struct Record: Decodable { var file: String; var width: Int; var height: Int; var fx: Float; var fy: Float; var cx: Float; var cy: Float; var cameraToWorld: [Float] }
        let records = try JSONDecoder().decode([Record].self, from: Data(contentsOf: folder.appendingPathComponent("keyframes.json")))
        let views = records.compactMap { r -> SplatTrainer.View? in
            let m = r.cameraToWorld
            return SplatTrainer.view(photo: folder.appendingPathComponent(r.file), photoWidth: r.width, photoHeight: r.height,
                                     fx: r.fx, fy: r.fy, cx: r.cx, cy: r.cy,
                                     cameraToWorld: simd_float4x4(SIMD4(m[0], m[1], m[2], m[3]), SIMD4(m[4], m[5], m[6], m[7]),
                                                                  SIMD4(m[8], m[9], m[10], m[11]), SIMD4(m[12], m[13], m[14], m[15])))
        }
        let (seeds, _) = SplatTrainer.seeds(from: surfels, noWall: -100)
        let trainer = try SplatTrainer(seeds: seeds, views: views)
        let held = Array(stride(from: 3, to: views.count, by: 8)), train = (0..<views.count).filter { !held.contains($0) }
        let heldBase = held.map { trainer.step(view: $0, update: false) }
        let trainBase = train.map { trainer.step(view: $0, update: false) }
        func gain(_ ids: [Int], _ base: [Float]) -> Int {
            let r = zip(ids, base).map { trainer.step(view: $0, update: false) / $1 }.reduce(0, +) / Float(ids.count)
            return Int((1 - r) * 100)
        }
        var line = "curve:"
        for step in 1...6000 {
            trainer.step(view: train.randomElement()!)
            if step % 250 == 0 { line += " \(step):\(gain(held, heldBase))/\(gain(train, trainBase))" }
        }
        print(line + "  (held-out % / trained-on %)")
    }
}
