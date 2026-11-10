#if os(iOS)
import ARKit
#endif
import Metal
import QuartzCore
import simd

/// Flat tiles (rk 10 Nov, ported from the Android app's FlatTiles): where LiDAR depth shows a flat surface, 30 cm
/// square tiles are laid on it and painted from the camera picture, so a plain wall shows as a sharp picture instead of
/// soft points, and a wrong "flat" guess shows at once. Best look wins: a tile is repainted only from a clearly better
/// view (closer, more head-on). A tile the depth contradicts three looks in a row is taken back.
///
/// Test switch "Flat tiles (test)", off by default. `update` runs on LiveCapture's worker queue (twice a second at
/// most) with the frame it was given: plane finding and painting both use that one frame, so pose, depth and picture
/// always match (Android had to paint later on the GL thread). The renderer draws `encode` every frame.
final class FlatTiles {
    nonisolated(unsafe) static var enabled: Bool {
        get { UserDefaults.standard.bool(forKey: "flatTiles") || ProcessInfo.processInfo.arguments.contains("-flatTiles") }
        set { UserDefaults.standard.set(newValue, forKey: "flatTiles") }
    }
    /// The Mac harness runs the tiles without the switch.
    nonisolated(unsafe) static var forceForTests = false

    static let tile: Float = 0.30
    /// Texture pixels per tile side (~4.7 mm per pixel; Android 96 px). The .rmsc gives every tile's pixels, so the
    /// size can differ between phones.
    static let px = 64
    static let atlas = 2048, perRow = atlas / px, maxTiles = perRow * perRow
    /// LiDAR is ~1 cm at 2 m (the S24's depth 3-4 cm): half Android's 3.5 cm on the plane / 7 cm in front.
    static let inlier: Float = 0.02, front: Float = 0.04
    /// Grown (depth-less) tiles at most this many tiles from a depth-confirmed one.
    static let grow = 2

    struct Plane { var n: SIMD3<Float>; var d: Float; var u: SIMD3<Float>; var v: SIMD3<Float> }
    struct Key: Hashable { var plane: Int32, iu: Int32, iv: Int32 }
    final class Tile {
        let plane: Int, iu: Int, iv: Int, slot: Int
        var score: Float = -1, contra = 0, ring = 0, seen = 0
        var inferred = false
        var born: Float = -1, repainted: Float = -1
        init(plane: Int, iu: Int, iv: Int, slot: Int) { self.plane = plane; self.iu = iu; self.iv = iv; self.slot = slot }
    }

    private let lock = NSLock()
    private(set) var planes: [Plane] = []
    private(set) var tiles: [Key: Tile] = [:]
    private var banned = Set<Key>(), tallSeen = Set<Key>()
    private(set) var floorIdx = -1
    private var slots = 0
    private var lastTime: TimeInterval = 0
    var lastLookTime: TimeInterval { lastTime }
    private let start = CACurrentMediaTime()
    func now() -> Float { Float(CACurrentMediaTime() - start) }
    /// Bumped whenever the tiles change (the renderer rebuilds its quads).
    private(set) var version = 0

    /// The tile pictures (RGBA, `atlas` × `atlas`, slot s at column s % perRow, row s / perRow, row 0 at the top).
    let texture: MTLTexture

    // Counts for the log.
    private(set) var attempts = 0, noPlane = 0, looked = 0, painted = 0, lowCover = 0, blocked = 0, opening = 0,
                     tooSlanted = 0, notBetter = 0, removed = 0, grown = 0, floorPainted = 0

    init?(device: MTLDevice) {
        let d = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba8Unorm, width: Self.atlas, height: Self.atlas, mipmapped: false)
        d.usage = .shaderRead
        d.storageMode = .shared
        guard let t = device.makeTexture(descriptor: d) else { return nil }
        texture = t
    }

    var countShown: Int { lock.lock(); defer { lock.unlock() }; return tiles.values.filter { $0.score > 0 }.count }

    func stats() -> String {
        lock.lock(); defer { lock.unlock() }
        return "flat tiles: \(tiles.values.filter { $0.score > 0 }.count) shown on \(planes.count) planes; \(attempts) attempts (\(noPlane) no flat surface), \(looked) tiles looked at: \(painted) painted, \(lowCover) too little depth, \(blocked) something in front, \(opening) opening, \(tooSlanted) too slanted, \(notBetter) not a better view; \(removed) taken back; \(grown) grown from neighbours; \(floorPainted) floor paints"
    }

    // MARK: - Analysis and painting (worker queue)

    /// One look for `update`: the depth (metres, 0 = none) and whether a pixel is trusted, the depth's intrinsics, the
    /// camera-to-world pose, and the camera picture's colour at a pixel (its own intrinsics `k`, `iw` × `ih`).
    struct Look {
        var time: Double
        var pose: simd_float4x4
        var dw: Int, dh: Int
        var depth: (Int, Int) -> Float
        var confident: (Int, Int) -> Bool
        var k: simd_float3x3
        var iw: Int, ih: Int
        var color: (Int, Int) -> SIMD3<UInt8>
    }

    /// Looks at one view: finds the main flat surface in its depth, decides which tiles to paint, take back or grow,
    /// and paints them from the same view's picture. At most twice a second.
    func update(_ look: Look) {
        guard Self.enabled || Self.forceForTests, look.time - lastTime >= 0.5 else { return }
        lastTime = look.time
        let dw = look.dw, dh = look.dh, k = look.k
        let sx = Float(dw) / Float(look.iw), sy = Float(dh) / Float(look.ih)
        let fx = k[0][0] * sx, fy = k[1][1] * sy, cx = k[2][0] * sx, cy = k[2][1] * sy
        let pose = look.pose
        let cam = SIMD3<Float>(pose.columns.3.x, pose.columns.3.y, pose.columns.3.z)

        // 1) the depth's points in the world (every 2nd pixel)
        var wp: [SIMD3<Float>] = []
        wp.reserveCapacity((dw / 2) * (dh / 2))
        for v in stride(from: 1, to: dh, by: 2) {
            for u in stride(from: 1, to: dw, by: 2) {
                guard look.confident(u, v) else { continue }
                let d = look.depth(u, v)
                guard d > 0.3, d < 4 else { continue }
                let pc = SIMD4<Float>((Float(u) + 0.5 - cx) * d / fx, (cy - (Float(v) + 0.5)) * d / fy, -d, 1)
                let w = pose * pc
                wp.append(SIMD3(w.x, w.y, w.z))
            }
        }
        lock.lock()
        attempts += 1
        guard wp.count >= 400 else { noPlane += 1; lock.unlock(); return }
        let n = wp.count

        // 2) the main plane (RANSAC)
        var best = (n: SIMD3<Float>(0, 1, 0), d: Float(0), inliers: 0)
        var rng = SystemRandomNumberGenerator()
        for _ in 0..<120 {
            let a = wp[Int.random(in: 0..<n, using: &rng)], b = wp[Int.random(in: 0..<n, using: &rng)], c = wp[Int.random(in: 0..<n, using: &rng)]
            let cr = simd_cross(b - a, c - a), l = simd_length(cr)
            guard l > 1e-6 else { continue }
            let nn = cr / l, d = -simd_dot(nn, a)
            var inl = 0
            for i in stride(from: 0, to: n, by: 3) where abs(simd_dot(nn, wp[i]) + d) < Self.inlier { inl += 1 }
            if inl > best.inliers { best = (nn, d, inl) }
        }
        guard Float(best.inliers * 3) >= Float(n) * 0.25 else { noPlane += 1; lock.unlock(); return }
        var bn = best.n, bd = best.d
        if simd_dot(bn, cam) + bd < 0 { bn = -bn; bd = -bd }   // normal towards the camera
        // 2b) the floor: a level plane facing up, 0.8 m or more below the phone, snapped exactly level (gravity known)
        var isFloor = false
        if bn.y > 0.95, cam.y - (-bd) > 0.8 {
            var sum: Float = 0, cnt = 0
            for p in wp where abs(simd_dot(bn, p) + bd) < Self.inlier { sum += p.y; cnt += 1 }
            let fyl = sum / Float(max(1, cnt))
            if floorIdx < 0 || abs(planes[floorIdx].d + fyl) < 0.05 {
                bn = SIMD3(0, 1, 0); bd = floorIdx >= 0 ? planes[floorIdx].d : -fyl; isFloor = true
            }
        }
        // 3) the same plane as before (within ~10 deg and 4 cm; LiDAR: Android 6 cm), else a new one
        var pi = isFloor && floorIdx >= 0 ? floorIdx : -1
        if pi < 0 { pi = planes.firstIndex { simd_dot($0.n, bn) > 0.985 && abs($0.d - bd) < 0.04 } ?? -1 }
        if pi < 0 {
            var u = abs(bn.y) > 0.8 ? SIMD3<Float>(1, 0, 0) : simd_normalize(SIMD3(bn.z, 0, -bn.x))
            u = simd_normalize(u - simd_dot(u, bn) * bn)
            planes.append(Plane(n: bn, d: bd, u: u, v: simd_cross(bn, u)))
            pi = planes.count - 1
            if isFloor { floorIdx = pi }
        }
        let p = planes[pi]

        // 4) per tile: samples on the plane, in front of it, behind it (where each ray crosses the plane)
        var cnt: [Key: SIMD3<Int32>] = [:]
        for q in wp {
            let s = simd_dot(p.n, q) + p.d
            let r = q - cam, rn = simd_dot(p.n, r)
            guard abs(rn) > 1e-4 else { continue }
            let t = -(simd_dot(p.n, cam) + p.d) / rn
            let x = cam + r * t
            let key = Key(plane: Int32(pi), iu: Int32(floor(simd_dot(x, p.u) / Self.tile)), iv: Int32(floor(simd_dot(x, p.v) / Self.tile)))
            var c = cnt[key] ?? .zero
            if abs(s) < Self.inlier { c.x += 1 } else if s > Self.front { c.y += 1 } else if s < -Self.front { c.z += 1 }
            cnt[key] = c
        }
        // 5) confirm, paint (best look), or take back
        let vdist = abs(simd_dot(p.n, cam) + p.d)
        let forward = -SIMD3<Float>(pose.columns.2.x, pose.columns.2.y, pose.columns.2.z)
        let head = abs(simd_dot(forward, p.n))   // 1 = straight on
        var toPaint: [(Tile, Float)] = []
        let score = head / max(0.3, vdist)
        for (key, c) in cnt {
            let tot = c.x + c.y + c.z
            if let t = tiles[key], tot >= 8, Float(c.x) < 0.4 * Float(tot) {   // the depth now says it isn't flat there
                t.contra += 1
                if t.contra >= 3 { tiles[key] = nil; banned.insert(key); removed += 1; version += 1 }
                continue
            }
            tiles[key]?.contra = 0
            // a 30 cm tile at 2 m is ~32 x 32 LiDAR pixels at full depth resolution, ~250 samples every 2nd pixel
            let expected = pow(Self.tile * fx / max(0.3, vdist) / 2, 2) * head
            looked += 1
            if Float(c.x) < 0.35 * expected { lowCover += 1; continue }   // (LiDAR: dense; Android needed only 0.2)
            if Float(c.y) > max(3, 0.05 * Float(tot)) { blocked += 1; continue }
            if Float(c.z) > 0.15 * Float(tot) { opening += 1; continue }
            let t: Tile
            if let old = tiles[key] { t = old } else {
                guard slots < Self.maxTiles else { continue }
                t = Tile(plane: pi, iu: Int(key.iu), iv: Int(key.iv), slot: slots); slots += 1
                tiles[key] = t
            }
            t.seen += 1; t.inferred = false
            if head <= 0.5 { tooSlanted += 1; continue }
            if score > t.score * 1.25 { toPaint.append((t, score)) } else { notBetter += 1 }
        }
        // 6) growth next to painted tiles: no depth needed (plain walls), only none against it, a fair view, and at
        // most `grow` tiles from a depth-confirmed one
        if head > 0.5 {
            let seeds = tiles.values.filter { t in t.plane == pi && (t.score > 0 || toPaint.contains { $0.0 === t }) && t.ring < Self.grow }
            for s0 in seeds {
                for (du, dv) in [(1, 0), (-1, 0), (0, 1), (0, -1)] {
                    let key = Key(plane: Int32(pi), iu: Int32(s0.iu + du), iv: Int32(s0.iv + dv))
                    if tiles[key] != nil || banned.contains(key) { continue }
                    if let c = cnt[key] { let tot = Float(c.x + c.y + c.z); if Float(c.y) > max(3, 0.05 * tot) || Float(c.z) > 0.15 * tot { continue } }
                    guard slots < Self.maxTiles else { break }
                    let t = Tile(plane: pi, iu: s0.iu + du, iv: s0.iv + dv, slot: slots); slots += 1
                    t.ring = s0.ring + 1; t.inferred = true
                    tiles[key] = t; toPaint.append((t, score * 0.9)); grown += 1
                }
            }
        }
        if floorIdx >= 0 { floorPass(wp, cam: cam, forward: forward, toPaint: &toPaint) }
        lock.unlock()
        if !toPaint.isEmpty { paint(toPaint, look) }
    }

    /// Floor tiles (strict, as on Android since 10 Nov 17:38): a picture only where depth agrees there's floor at that
    /// spot, nothing up to 2.5 m high stands in front of it, and nothing clearly stood there in any look; depth-less
    /// tiles only between two depth-confirmed floor tiles. Within 3 m of the phone. Called with the lock held.
    private func floorPass(_ wp: [SIMD3<Float>], cam: SIMD3<Float>, forward: SIMD3<Float>, toPaint: inout [(Tile, Float)]) {
        let p = planes[floorIdx], fi = Int32(floorIdx)
        var cnt: [Key: SIMD2<Int32>] = [:]
        for q in wp {
            let s = q.y + p.d
            guard s < 2.5 else { continue }
            let r = q - cam
            guard r.y < -1e-3 else { continue }
            let t = -(cam.y + p.d) / r.y
            let x = cam + r * t
            let key = Key(plane: fi, iu: Int32(floor(simd_dot(x, p.u) / Self.tile)), iv: Int32(floor(simd_dot(x, p.v) / Self.tile)))
            var c = cnt[key] ?? .zero
            if s > 0.05 { c.y += 1 } else { c.x += 1 }   // something there / floor (or its reflection); LiDAR: 5 cm (Android 7)
            cnt[key] = c
        }
        let h = cam.y + p.d, headDown = max(0.3, -forward.y)
        var cand = Set(cnt.keys)
        for t in tiles.values where t.plane == floorIdx && t.score > 0 && t.ring < Self.grow {
            for (du, dv) in [(1, 0), (-1, 0), (0, 1), (0, -1)] { cand.insert(Key(plane: fi, iu: Int32(t.iu + du), iv: Int32(t.iv + dv))) }
        }
        for key in cand {
            let cu = (Float(key.iu) + 0.5) * Self.tile, cv = (Float(key.iv) + 0.5) * Self.tile
            let centre = p.u * cu + p.v * cv
            let dist = sqrt(pow(centre.x - cam.x, 2) + h * h + pow(centre.z - cam.z, 2))
            guard dist <= 3 else { continue }
            let c = cnt[key], t = tiles[key]
            if let c, Float(c.y) > max(2, 0.1 * Float(c.x + c.y)) {   // something stands there (or in front of it)
                if let t, t.score > 0, Float(c.y) > 0.5 * Float(c.x + c.y) { t.contra += 1; if t.contra >= 3 { tiles[key] = nil; banned.insert(key); removed += 1; version += 1 } }
                if Float(c.y) > max(8, 0.3 * Float(c.x + c.y)) { tallSeen.insert(key) }
                continue
            }
            if banned.contains(key) || tallSeen.contains(key) { continue }
            if let c, c.x < 3 { continue }
            if c == nil {
                var both = false
                for (du, dv) in [(1, 0), (0, 1)] {
                    let a = tiles[Key(plane: fi, iu: key.iu + Int32(du), iv: key.iv + Int32(dv))], b = tiles[Key(plane: fi, iu: key.iu - Int32(du), iv: key.iv - Int32(dv))]
                    if let a, let b, a.score > 0, b.score > 0, !a.inferred, !b.inferred { both = true }
                }
                if !both { continue }
            }
            let tile: Tile
            if let t { tile = t; if c != nil { t.inferred = false } } else {
                guard slots < Self.maxTiles else { continue }
                tile = Tile(plane: floorIdx, iu: Int(key.iu), iv: Int(key.iv), slot: slots); slots += 1
                tile.inferred = c == nil
                tiles[key] = tile
            }
            let score = headDown / max(0.3, dist)
            if score > tile.score * 1.25, !toPaint.contains(where: { $0.0 === tile }) { toPaint.append((tile, score)); floorPainted += 1 }
        }
    }

    /// Copies each tile's square from the camera picture into its atlas slot.
    private func paint(_ list: [(Tile, Float)], _ look: Look) {
        let k = look.k, view = look.pose.inverse, iw = look.iw, ih = look.ih
        let px = Self.px
        var buf = [UInt8](repeating: 255, count: px * px * 4)
        let tnow = now()
        var done = 0
        for (t, score) in list {
            if done >= 30 { break }   // a bounded amount per update
            lock.lock(); let p = planes[t.plane]; lock.unlock()
            let origin = -p.n * p.d
            var ok = true
            for j in 0..<px where ok {
                for i in 0..<px {
                    // texel centre on the plane (row j = v from the tile's own v0 side, as in the .rmsc pixels)
                    let a = (Float(t.iu) + (Float(i) + 0.5) / Float(px)) * Self.tile, b = (Float(t.iv) + (Float(j) + 0.5) / Float(px)) * Self.tile
                    let w = origin + p.u * a + p.v * b
                    let c = view * SIMD4<Float>(w, 1)
                    let d = -c.z
                    if d < 0.2 { ok = false; break }
                    let u = k[0][0] * c.x / d + k[2][0], v = k[2][1] - k[1][1] * c.y / d
                    if u < 0 || v < 0 || u >= Float(iw) || v >= Float(ih) { ok = false; break }
                    let rgb = look.color(Int(u), Int(v))
                    let o = (j * px + i) * 4
                    buf[o] = rgb.x; buf[o + 1] = rgb.y; buf[o + 2] = rgb.z; buf[o + 3] = 255
                }
            }
            guard ok else { continue }   // not all in the picture: left for a later look
            let slot = t.slot
            buf.withUnsafeBytes { raw in
                texture.replace(region: MTLRegionMake2D((slot % Self.perRow) * px, (slot / Self.perRow) * px, px, px),
                                mipmapLevel: 0, withBytes: raw.baseAddress!, bytesPerRow: px * 4)
            }
            lock.lock()
            if t.born < 0 { t.born = tnow } else { t.repainted = tnow }
            t.score = score; painted += 1; version += 1
            lock.unlock()
            done += 1
        }
    }

    // MARK: - Drawing

    /// One corner of a tile's quad for the GPU: position (5 mm in front of the plane), atlas uv, born, repainted.
    struct Vertex { var x, y, z, u, v, born, repainted, pad: Float }

    /// The painted tiles as two triangles each.
    func vertices() -> [Vertex] {
        lock.lock(); defer { lock.unlock() }
        var out: [Vertex] = []
        out.reserveCapacity(tiles.count * 6)
        let inset = 0.5 / Float(Self.atlas), du = Float(Self.px) / Float(Self.atlas) - 2 * inset
        for t in tiles.values where t.score > 0 {
            let p = planes[t.plane]
            let u0 = Float((t.slot % Self.perRow) * Self.px) / Float(Self.atlas) + inset, v0 = Float((t.slot / Self.perRow) * Self.px) / Float(Self.atlas) + inset
            var c: [Vertex] = []
            for kk in 0..<4 {
                let a = Float(t.iu + (kk & 1)) * Self.tile, b = Float(t.iv + (kk >> 1)) * Self.tile
                let w = p.u * a + p.v * b + p.n * (0.005 - p.d)
                c.append(Vertex(x: w.x, y: w.y, z: w.z, u: u0 + Float(kk & 1) * du, v: v0 + Float(kk >> 1) * du, born: t.born, repainted: t.repainted, pad: 0))
            }
            for idx in [0, 1, 3, 0, 3, 2] { out.append(c[idx]) }
        }
        return out
    }

    /// The tiles' pictures as RGBA rows (for saving and the harness).
    func atlasBytes(rows: Int) -> [UInt8] {
        var out = [UInt8](repeating: 0, count: Self.atlas * rows * Self.px * 4)
        out.withUnsafeMutableBytes { texture.getBytes($0.baseAddress!, bytesPerRow: Self.atlas * 4, from: MTLRegionMake2D(0, 0, Self.atlas, rows * Self.px), mipmapLevel: 0) }
        return out
    }
    var usedRows: Int { lock.lock(); defer { lock.unlock() }; return (slots + Self.perRow - 1) / Self.perRow }

    /// The shared tiles.json of a .rmsc (docs/rmsc-format.md "Flat tiles"): each tile's corners in metres, floor moved
    /// to y = 0 by `floorY`, and its pixels in tiles.png (the atlas rows in use). Nil when no tile is painted.
    func sharedJSON(floorY: Float) -> Data? {
        lock.lock(); defer { lock.unlock() }
        let rows = (slots + Self.perRow - 1) / Self.perRow
        var list: [[String: Any]] = []
        func r4(_ v: Float) -> Double { (Double(v) * 10000).rounded() / 10000 }
        for t in tiles.values where t.score > 0 {
            let p = planes[t.plane], x0 = (t.slot % Self.perRow) * Self.px, y0 = (t.slot / Self.perRow) * Self.px
            var corners: [[Double]] = [], pixels: [[Int]] = []
            for (cu, cv) in [(0, 0), (1, 0), (1, 1), (0, 1)] {
                let w = p.u * (Float(t.iu + cu) * Self.tile) + p.v * (Float(t.iv + cv) * Self.tile) - p.n * p.d
                corners.append([r4(w.x), r4(w.y - floorY), r4(w.z)])
                pixels.append([x0 + cu * Self.px, y0 + cv * Self.px])
            }
            list.append(["corners": corners, "pixels": pixels, "normal": [r4(p.n.x), r4(p.n.y), r4(p.n.z)]])
        }
        guard !list.isEmpty else { return nil }
        return try? JSONSerialization.data(withJSONObject: ["version": 1, "tileSize": Double(Self.tile), "image": "tiles.png",
                                                            "imageWidth": Self.atlas, "imageHeight": rows * Self.px, "tiles": list])
    }

    func reset() {
        lock.lock(); defer { lock.unlock() }
        planes.removeAll(); tiles.removeAll(); banned.removeAll(); tallSeen.removeAll(); floorIdx = -1; slots = 0; version += 1
    }
}

#if os(iOS)
extension FlatTiles {
    /// A live ARKit frame (LiDAR depth, its confidence, the full-range YCbCr 4:2:0 picture).
    func update(_ frame: ARFrame) {
        guard Self.enabled, case .normal = frame.camera.trackingState, frame.timestamp - lastLookTime >= 0.5,
              let sceneDepth = frame.sceneDepth, let confidence = sceneDepth.confidenceMap else { return }
        let depth = sceneDepth.depthMap, image = frame.capturedImage
        CVPixelBufferLockBaseAddress(depth, .readOnly); CVPixelBufferLockBaseAddress(confidence, .readOnly)
        CVPixelBufferLockBaseAddress(image, .readOnly)
        defer {
            CVPixelBufferUnlockBaseAddress(image, .readOnly); CVPixelBufferUnlockBaseAddress(confidence, .readOnly)
            CVPixelBufferUnlockBaseAddress(depth, .readOnly)
        }
        guard CVPixelBufferGetPlaneCount(image) >= 2, let dBase = CVPixelBufferGetBaseAddress(depth),
              let cBase = CVPixelBufferGetBaseAddress(confidence),
              let yBase = CVPixelBufferGetBaseAddressOfPlane(image, 0), let uvBase = CVPixelBufferGetBaseAddressOfPlane(image, 1) else { return }
        let dRow = CVPixelBufferGetBytesPerRow(depth) / MemoryLayout<Float32>.stride
        let dPtr = dBase.assumingMemoryBound(to: Float32.self)
        let cRow = CVPixelBufferGetBytesPerRow(confidence), cPtr = cBase.assumingMemoryBound(to: UInt8.self)
        let yRow = CVPixelBufferGetBytesPerRowOfPlane(image, 0), uvRow = CVPixelBufferGetBytesPerRowOfPlane(image, 1)
        let yPtr = yBase.assumingMemoryBound(to: UInt8.self), uvPtr = uvBase.assumingMemoryBound(to: UInt8.self)
        let medium = UInt8(ARConfidenceLevel.medium.rawValue)
        update(Look(time: frame.timestamp, pose: frame.camera.transform,
                    dw: CVPixelBufferGetWidth(depth), dh: CVPixelBufferGetHeight(depth),
                    depth: { u, v in dPtr[v * dRow + u] }, confident: { u, v in cPtr[v * cRow + u] >= medium },
                    k: frame.camera.intrinsics,
                    iw: CVPixelBufferGetWidthOfPlane(image, 0), ih: CVPixelBufferGetHeightOfPlane(image, 0),
                    color: { u, v in
                        let luma = Float(yPtr[v * yRow + u]), ch = (v / 2) * uvRow + (u / 2) * 2
                        let cb = Float(uvPtr[ch]) - 128, cr = Float(uvPtr[ch + 1]) - 128
                        return SIMD3(UInt8(max(0, min(255, luma + 1.402 * cr))), UInt8(max(0, min(255, luma - 0.344136 * cb - 0.714136 * cr))),
                                     UInt8(max(0, min(255, luma + 1.772 * cb))))
                    }))
    }
}
#endif

/// Draws the tiles over the live scan: new tiles flash white and develop like a Polaroid (~1 s), a repaint from a
/// better look glows a little (as on Android).
final class FlatTilesRenderer {
    private let tiles: FlatTiles
    private let device: MTLDevice
    private let pipeline: MTLRenderPipelineState
    private let depthState: MTLDepthStencilState
    private let sampler: MTLSamplerState
    private var buffer: MTLBuffer?, count = 0, builtVersion = -1

    private struct Uniforms { var viewProjection: simd_float4x4; var now: Float; var pad: SIMD3<Float> = .zero }

    init?(device: MTLDevice, tiles: FlatTiles) {
        guard let library = try? device.makeLibrary(source: Self.shaderSource, options: nil),
              let vertex = library.makeFunction(name: "flatTileVertex"), let fragment = library.makeFunction(name: "flatTileFragment") else { return nil }
        let d = MTLRenderPipelineDescriptor()
        d.vertexFunction = vertex; d.fragmentFunction = fragment
        d.depthAttachmentPixelFormat = .depth32Float
        d.colorAttachments[0].pixelFormat = .bgra8Unorm
        guard let pipeline = try? device.makeRenderPipelineState(descriptor: d) else { return nil }
        let ds = MTLDepthStencilDescriptor(); ds.depthCompareFunction = .less; ds.isDepthWriteEnabled = true
        let sd = MTLSamplerDescriptor(); sd.minFilter = .linear; sd.magFilter = .linear
        sd.sAddressMode = .clampToEdge; sd.tAddressMode = .clampToEdge
        guard let depthState = device.makeDepthStencilState(descriptor: ds), let sampler = device.makeSamplerState(descriptor: sd) else { return nil }
        self.tiles = tiles; self.device = device; self.pipeline = pipeline; self.depthState = depthState; self.sampler = sampler
    }

    func encode(_ encoder: MTLRenderCommandEncoder, viewProjection: simd_float4x4) {
        guard FlatTiles.enabled else { return }
        if builtVersion != tiles.version {
            builtVersion = tiles.version
            let v = tiles.vertices()
            count = v.count
            buffer = v.isEmpty ? nil : device.makeBuffer(bytes: v, length: v.count * MemoryLayout<FlatTiles.Vertex>.stride, options: .storageModeShared)
        }
        guard let buffer, count > 0 else { return }
        var u = Uniforms(viewProjection: viewProjection, now: tiles.now())
        encoder.setCullMode(.none)
        encoder.setRenderPipelineState(pipeline)
        encoder.setDepthStencilState(depthState)
        encoder.setVertexBuffer(buffer, offset: 0, index: 0)
        encoder.setVertexBytes(&u, length: MemoryLayout<Uniforms>.stride, index: 1)
        encoder.setFragmentTexture(tiles.texture, index: 0)
        encoder.setFragmentSamplerState(sampler, index: 0)
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: count)
    }

    private static let shaderSource = """
    #include <metal_stdlib>
    using namespace metal;
    struct TileVertex { float x, y, z, u, v, born, repainted, pad; };
    struct Uniforms { float4x4 viewProjection; float now; float3 pad; };
    struct TileOut { float4 position [[position]]; float2 uv; float2 age; };

    vertex TileOut flatTileVertex(uint vid [[vertex_id]], const device TileVertex *vs [[buffer(0)]], constant Uniforms &u [[buffer(1)]]) {
        TileVertex t = vs[vid];
        TileOut o;
        o.position = u.viewProjection * float4(t.x, t.y, t.z, 1.0);
        o.uv = float2(t.u, t.v);
        o.age = float2(u.now - t.born, t.repainted < 0.0 ? 99.0 : u.now - t.repainted);
        return o;
    }
    fragment float4 flatTileFragment(TileOut in [[stage_in]], texture2d<float> tex [[texture(0)]], sampler s [[sampler(0)]]) {
        float3 c = tex.sample(s, in.uv).rgb;
        float a = in.age.x;
        float dev = smoothstep(0.15, 1.1, a);                                   // developing 0..1
        float l = dot(c, float3(0.299, 0.587, 0.114));
        float3 washed = mix(float3(l), c, 0.35) * float3(1.06, 1.0, 0.9) * 0.55 + 0.45;   // pale, warm, low contrast
        float3 col = mix(washed, c, dev);
        float flash = 1.0 - smoothstep(0.0, 0.35, a);                           // white flash at birth
        float re = (1.0 - smoothstep(0.0, 0.3, in.age.y)) * 0.15;               // a repaint: a slight glow
        return float4(mix(col, float3(1.0), clamp(flash * 0.95 + re, 0.0, 1.0)), 1.0);
    }
    """
}
