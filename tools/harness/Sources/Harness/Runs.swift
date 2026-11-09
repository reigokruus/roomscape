import AppKit
import Foundation
import ImageIO
import simd
import UniformTypeIdentifiers

// MARK: Images

enum Images {
    static func png(_ rgba: [UInt8], width: Int, height: Int, to url: URL) {
        write(rgba, width: width, height: height, to: url, type: UTType.png.identifier as CFString)
    }
    static func jpeg(_ rgba: [UInt8], width: Int, height: Int) -> Data {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".jpg")
        write(rgba, width: width, height: height, to: url, type: UTType.jpeg.identifier as CFString)
        defer { try? FileManager.default.removeItem(at: url) }
        return (try? Data(contentsOf: url)) ?? Data()
    }
    private static func write(_ rgba: [UInt8], width: Int, height: Int, to url: URL, type: CFString) {
        var px = rgba
        guard let ctx = CGContext(data: &px, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                                  space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue),
              let image = ctx.makeImage(), let dest = CGImageDestinationCreateWithURL(url as CFURL, type, 1, nil) else { return }
        CGImageDestinationAddImage(dest, image, [kCGImageDestinationLossyCompressionQuality: 0.85] as CFDictionary)
        CGImageDestinationFinalize(dest)
    }

    /// A flat-shaded picture of a mesh from a 3/4 view outside the room (software z-buffer), for before / after.
    static func renderMesh(_ positions: [SIMD3<Float>], _ triangles: [UInt32], room: Room, to url: URL, width: Int = 640, height: Int = 480) {
        let c = room.size / 2
        let eye = c + SIMD3(room.size.x * 0.9, room.size.y * 1.4, room.size.z * 1.3)
        let fwd = simd_normalize(c - eye), right = simd_normalize(simd_cross(fwd, SIMD3(0, 1, 0))), down = simd_cross(fwd, right)
        let f = Float(width) / 2 / tan(30 * Float.pi / 180)
        var z = [Float](repeating: .infinity, count: width * height)
        var px = [UInt8](repeating: 20, count: width * height * 4)
        for i in stride(from: 0, to: px.count, by: 4) { px[i + 3] = 255 }
        let light = simd_normalize(SIMD3<Float>(0.3, 1, 0.5))
        for t in stride(from: 0, to: triangles.count, by: 3) {
            let a = positions[Int(triangles[t])], b = positions[Int(triangles[t + 1])], cc = positions[Int(triangles[t + 2])]
            // Leave out the ceiling and the two near walls, so the inside shows.
            let m = (a + b + cc) / 3
            if m.y > room.size.y - 0.15 || m.x > room.size.x - 0.12 || m.z > room.size.z - 0.12 { continue }
            var n = simd_cross(b - a, cc - a)
            guard simd_length(n) > 1e-9 else { continue }
            n = simd_normalize(n)
            let shade = UInt8(60 + 180 * abs(simd_dot(n, light)))
            func proj(_ p: SIMD3<Float>) -> SIMD3<Float> {
                let q = p - eye
                let zz = simd_dot(q, fwd)
                return SIMD3(simd_dot(q, right) / zz * f + Float(width) / 2, simd_dot(q, down) / zz * f + Float(height) / 2, zz)
            }
            let p0 = proj(a), p1 = proj(b), p2 = proj(cc)
            guard p0.z > 0.1, p1.z > 0.1, p2.z > 0.1 else { continue }
            let minX = max(0, Int(min(p0.x, p1.x, p2.x))), maxX = min(width - 1, Int(max(p0.x, p1.x, p2.x)))
            let minY = max(0, Int(min(p0.y, p1.y, p2.y))), maxY = min(height - 1, Int(max(p0.y, p1.y, p2.y)))
            guard minX <= maxX, minY <= maxY else { continue }
            let area = (p1.x - p0.x) * (p2.y - p0.y) - (p2.x - p0.x) * (p1.y - p0.y)
            guard abs(area) > 1e-6 else { continue }
            for y in minY...maxY {
                for x in minX...maxX {
                    let fx = Float(x) + 0.5, fy = Float(y) + 0.5
                    let w0 = ((p1.x - fx) * (p2.y - fy) - (p2.x - fx) * (p1.y - fy)) / area
                    let w1 = ((p2.x - fx) * (p0.y - fy) - (p0.x - fx) * (p2.y - fy)) / area
                    let w2 = 1 - w0 - w1
                    guard w0 >= 0, w1 >= 0, w2 >= 0 else { continue }
                    let d = w0 * p0.z + w1 * p1.z + w2 * p2.z
                    let i = y * width + x
                    guard d < z[i] else { continue }
                    z[i] = d
                    px[i * 4] = shade; px[i * 4 + 1] = shade; px[i * 4 + 2] = shade
                }
            }
        }
        png(px, width: width, height: height, to: url)
    }
}

// MARK: Fusion and metrics

enum Fusion {
    /// Fuses depth frames with the given poses into a TSDF (the app's own, 4 cm voxels like the live mesh).
    static func fuse(_ frames: [Frame], poses: [simd_float4x4?]) -> Tsdf {
        let tsdf = Tsdf(capacity: 1_500_000, voxel: 0.04, truncation: 0.08, carve: 1.5)
        for (f, pose) in zip(frames, poses) {
            guard let pose else { continue }
            let r = simd_float3x3(pose.columns.0.ux3, pose.columns.1.ux3, pose.columns.2.ux3), o = pose.columns.3.ux3
            for p in UltraTracker.points(f.depth, step: 2) { tsdf.integrate(origin: o, point: r * p + o) }
        }
        return tsdf
    }

    static func mesh(_ tsdf: Tsdf) -> Tsdf.MeshScratch {
        let s = Tsdf.MeshScratch()
        _ = tsdf.extractMesh(near: .zero, radius: 10_000, solidWeight: 20, into: s)
        return s
    }

    /// Mean and 90th-percentile distance of the mesh's corners to the true surfaces, metres.
    static func meshError(_ positions: [SIMD3<Float>], _ room: Room) -> (mean: Float, p90: Float) {
        guard !positions.isEmpty else { return (.nan, .nan) }
        let d = positions.map(room.surfaceDistance).sorted()
        return (d.reduce(0, +) / Float(d.count), d[d.count * 9 / 10])
    }
}

// MARK: Calibration from a real .rmsc

enum Calibration {
    /// Depth noise from a real scan's depth images: in 5 × 5 windows that are flat, the spread across the plane
    /// fitted to them, by distance; fitted to σ = a + b·d². Also dropouts and depth edges.
    static func run(_ url: URL) throws {
        let archive = try Zip.Reader(url: url)
        guard let pj = try archive.read("photos.json"),
              let photos = (try JSONSerialization.jsonObject(with: pj) as? [String: Any])?["photos"] as? [[String: Any]] else {
            print("no photos.json"); return
        }
        var bins = [[Float]](repeating: [], count: 10) // 0.5 m bins
        var zeros = 0, total = 0, edges = 0, maps = 0
        var size = ""
        for p in photos {
            guard let d = p["depth"] as? [String: Any], let file = d["file"] as? String, let raw = try archive.read(file),
                  let w = d["width"] as? Int, let h = d["height"] as? Int, let fx = (d["fx"] as? NSNumber)?.floatValue,
                  let cx = (d["cx"] as? NSNumber)?.floatValue, let cy = (d["cy"] as? NSNumber)?.floatValue, raw.count >= w * h * 2 else { continue }
            maps += 1
            size = "\(w)×\(h)"
            let mm = raw.withUnsafeBytes { Array($0.bindMemory(to: UInt16.self).prefix(w * h)) }
            func point(_ x: Int, _ y: Int) -> SIMD3<Float>? {
                let z = Float(mm[y * w + x]) / 1000
                guard z > 0.1 else { return nil }
                return SIMD3((Float(x) - cx) * z / fx, (Float(y) - cy) * z / fx, z)
            }
            for y in 0..<h { for x in 0..<w {
                total += 1
                if mm[y * w + x] == 0 { zeros += 1 }
                if x + 1 < w, mm[y * w + x] > 0, mm[y * w + x + 1] > 0, abs(Int(mm[y * w + x]) - Int(mm[y * w + x + 1])) > 100 { edges += 1 }
            } }
            for y in stride(from: 2, to: h - 2, by: 3) {
                for x in stride(from: 2, to: w - 2, by: 3) {
                    var pts: [SIMD3<Float>] = []
                    for dy in -2...2 { for dx in -2...2 { if let q = point(x + dx, y + dy) { pts.append(q) } } }
                    guard pts.count >= 20 else { continue }
                    let m = pts.reduce(.zero, +) / Float(pts.count)
                    var cov = simd_float3x3(0)
                    for q in pts { let e = q - m; cov += simd_float3x3(e * e.x, e * e.y, e * e.z) }
                    cov = cov * (1 / Float(pts.count))
                    let (vals, _) = eigen(cov)
                    // Flat: the plane is clear (the second spread well above the smallest).
                    guard vals[1] > 25 * vals[0] else { continue }
                    let b = Int(min(9, m.z / 0.5))
                    bins[b].append(sqrt(max(0, vals[0])))
                }
            }
        }
        print("Calibration from \(url.lastPathComponent): \(maps) depth images (\(size), the app's half-size copy of ARKit's)")
        print(String(format: "  dropouts: %.1f%% of pixels; depth edges (>10 cm jump): %.1f%%", 100 * Float(zeros) / Float(max(1, total)), 100 * Float(edges) / Float(max(1, total))))
        var xs: [Float] = [], ys: [Float] = []
        for (i, b) in bins.enumerated() where b.count >= 30 {
            let s = b.sorted()[b.count / 2]
            let d = (Float(i) + 0.5) * 0.5
            print(String(format: "  %.2f–%.2f m: noise σ ≈ %.1f mm (%d flat windows)", Float(i) * 0.5, Float(i + 1) * 0.5, s * 1000, b.count))
            xs.append(d * d); ys.append(s)
        }
        if xs.count >= 2 {
            // Least squares σ = a + b·d².
            let n = Float(xs.count), sx = xs.reduce(0, +), sy = ys.reduce(0, +)
            let sxx = zip(xs, xs).map(*).reduce(0, +), sxy = zip(xs, ys).map(*).reduce(0, +)
            let b = (n * sxy - sx * sy) / max(1e-9, n * sxx - sx * sx), a = (sy - b * sx) / n
            print(String(format: "  fit: σ(d) = %.4f + %.5f·d² m  (use as Profile.noiseA / noiseB)", max(0, a), max(0, b)))
        }
    }

    /// Eigenvalues (ascending) of a symmetric 3×3 (Jacobi).
    static func eigen(_ m: simd_float3x3) -> ([Float], simd_float3x3) {
        var a = [[Double]](repeating: [0, 0, 0], count: 3)
        for i in 0..<3 { for j in 0..<3 { a[i][j] = Double(m[j][i]) } }
        for _ in 0..<20 {
            for p in 0..<2 { for q in (p + 1)..<3 where abs(a[p][q]) > 1e-15 {
                let th = (a[q][q] - a[p][p]) / (2 * a[p][q])
                let t = (th >= 0 ? 1 : -1) / (abs(th) + (th * th + 1).squareRoot())
                let c = 1 / (t * t + 1).squareRoot(), s = t * c
                for k in 0..<3 { let x = a[k][p], y = a[k][q]; a[k][p] = c * x - s * y; a[k][q] = s * x + c * y }
                for k in 0..<3 { let x = a[p][k], y = a[q][k]; a[p][k] = c * x - s * y; a[q][k] = s * x + c * y }
            } }
        }
        return ([Float(a[0][0]), Float(a[1][1]), Float(a[2][2])].sorted(), matrix_identity_float3x3)
    }
}

// MARK: Tests

enum Runs {
    /// The experimental tracker (ICP + gyro, depth only) against ground truth: drift, loop closure, and the mesh it
    /// gives; and the same mesh from the ARKit-like poses for comparison.
    static func track(_ scenario: Scenario, _ profile: Profile, out: URL) {
        let room = scenario.room
        let started = Date()
        let frames = Sensors.frames(room, profile)
        print("\(room.name), \(profile.name): \(frames.count) frames, \(String(format: "%.1f", frames.last?.walked ?? 0)) m walked (made in \(String(format: "%.1f", Date().timeIntervalSince(started))) s)")
        let tracker = UltraTracker()
        var poses: [simd_float4x4?] = []
        var failed = 0
        var align = matrix_identity_float4x4
        for (i, f) in frames.enumerated() {
            if i == 0 {
                // Gravity in camera coordinates from the true pose (the app reads it from CoreMotion).
                let r = simd_float3x3(f.truth.columns.0.ux3, f.truth.columns.1.ux3, f.truth.columns.2.ux3)
                tracker.begin(gravity: r.transpose * SIMD3(0, -1, 0))
            }
            let res = tracker.track(f.depth, gyro: f.gyro)
            if i == 0 { align = f.truth * tracker.pose.inverse }
            if res.ok { poses.append(align * tracker.pose) } else { poses.append(nil); failed += 1 }
        }
        var errs: [Float] = []
        for (f, p) in zip(frames, poses) { if let p { errs.append(simd_distance(p.columns.3.ux3, f.truth.columns.3.ux3)) } }
        let lastOK = Array(zip(frames, poses)).last { $0.1 != nil }
        let walked = frames.last?.walked ?? 1
        print(String(format: "  tracker: %d/%d frames rejected; position error mean %.1f cm, max %.1f cm; end error %.1f cm (%.1f%% of the %.1f m walked)",
                     failed, frames.count, 100 * errs.reduce(0, +) / Float(max(1, errs.count)), 100 * (errs.max() ?? 0),
                     100 * (errs.last ?? .nan), 100 * (errs.last ?? .nan) / walked, walked))
        if let lastOK, let p = lastOK.1 {
            let r0 = simd_float3x3(frames[0].truth.columns.0.ux3, frames[0].truth.columns.1.ux3, frames[0].truth.columns.2.ux3)
            let t = lastOK.0.truth
            let r1 = simd_float3x3(p.columns.0.ux3, p.columns.1.ux3, p.columns.2.ux3)
            let rt = simd_float3x3(t.columns.0.ux3, t.columns.1.ux3, t.columns.2.ux3)
            let rel: simd_float3x3 = r1.transpose * rt
            let tr: Float = rel[0][0] + rel[1][1] + rel[2][2]
            let rotErr: Float = acos(min(1, max(-1, (tr - 1) / 2))) * 180 / Float.pi
            _ = r0
            print(String(format: "  loop: back at the start, the tracker is %.1f cm and %.1f° off", 100 * (errs.last ?? .nan), rotErr))
        }
        let truthMesh = Fusion.mesh(Fusion.fuse(frames, poses: frames.map(\.truth)))
        let trackedMesh = Fusion.mesh(Fusion.fuse(frames, poses: poses))
        let arkitMesh = Fusion.mesh(Fusion.fuse(frames, poses: frames.map(\.arkit)))
        for (name, m) in [("true poses", truthMesh), ("ARKit-like poses (1% drift)", arkitMesh), ("experimental tracker", trackedMesh)] {
            let e = Fusion.meshError(m.positions, room)
            print(String(format: "  mesh from %@: %d triangles, error mean %.1f cm, 90%% %.1f cm", name, m.triangles.count / 3, 100 * e.mean, 100 * e.p90))
        }
        let base = "\(scenario.rawValue)-\(profile.name.split(separator: " ").first ?? "x")"
        Images.renderMesh(truthMesh.positions, truthMesh.triangles, room: room, to: out.appendingPathComponent("\(base)-truth.png"))
        Images.renderMesh(trackedMesh.positions, trackedMesh.triangles, room: room, to: out.appendingPathComponent("\(base)-tracker.png"))
        Images.renderMesh(arkitMesh.positions, arkitMesh.triangles, room: room, to: out.appendingPathComponent("\(base)-arkit.png"))
    }

    /// Hole fill on the chest scenario: the chest removed, how its patches compare with the real wall and floor.
    static func holeFill() {
        let room = Scenario.chest.room
        let frames = Sensors.frames(room, .iPhone15Pro, duration: 30, fps: 10)
        guard let chest = room.boxes.first(where: { $0.name.hasPrefix("Chest") }) else { return }
        var pts: [HoleFill.Point] = []
        for f in frames {
            let r = simd_float3x3(f.truth.columns.0.ux3, f.truth.columns.1.ux3, f.truth.columns.2.ux3), o = f.truth.columns.3.ux3
            for p in UltraTracker.points(f.depth, step: 4) {
                let w = r * p + o
                guard let hit = room.cast(o, simd_normalize(w - o)) else { continue }
                pts.append(HoleFill.Point(p: w, c: room.colour(hit)))
            }
        }
        let c = (chest.min + chest.max) / 2, half = (chest.max - chest.min) / 2
        let fill = HoleFill.build(pts, center: SIMD2(c.x, c.z), u: SIMD2(1, 0), hu: half.x, hv: half.z, y0: chest.min.y, y1: chest.max.y, floorY: 0)
        var floorErr: Float = 0, wallErr: Float = 0, nf = 0, nw = 0
        for (p, col) in zip(fill.positions, fill.colors) {
            let truth = p.y < 0.02 ? room.colour(Hit(t: 0, normal: SIMD3(0, 1, 0), material: room.floor, point: p, object: ""))
                                   : room.colour(Hit(t: 0, normal: SIMD3(0, 0, 1), material: room.walls[2], point: p, object: ""))
            let e = simd_length(col - truth)
            if p.y < 0.02 { floorErr += e; nf += 1 } else { wallErr += e; nw += 1 }
        }
        let floorCorners = fill.positions.filter { abs($0.y - 0.004) < 0.0005 }.count
        print("  (floor patch corners at 4 mm: \(floorCorners))")
        print("Hole fill, chest of drawers removed (\(pts.count) scan points):")
        print(String(format: "  %d triangles; floor patch %d corners, colour error %.3f; wall patch %d corners, colour error %.3f (0…1 RGB distance)",
                     fill.positions.count / 3, nf, nf > 0 ? floorErr / Float(nf) : .nan, nw, nw > 0 ? wallErr / Float(nw) : .nan))
    }

    /// Tap-to-clear's rule on the TSDF: the cone through a screen circle, up to the surface plus 15 cm, clears the
    /// tapped wall and leaves what's behind it.
    static func tapClear() {
        let room = Scenario.normal.room
        let frames = Sensors.frames(room, .iPhone15Pro, duration: 30, fps: 10)
        let tsdf = Fusion.fuse(frames, poses: frames.map(\.truth))
        let before = Fusion.mesh(tsdf).positions.count
        // A view across the room at the middle of the far wall (z = 0), a 22% circle in the middle of a 1179 × 2556 screen.
        let eye = SIMD3<Float>(room.size.x / 2, 1.4, room.size.z - 0.5)
        let fwd = SIMD3<Float>(0, 0, -1), right = SIMD3<Float>(1, 0, 0), up = SIMD3<Float>(0, 1, 0)
        let wpx: Float = 1179, hpx: Float = 2556, f = hpx / 2 / tan(50 * Float.pi / 180)
        let r = 0.22 * wpx, limit = room.size.z - 0.5 + 0.15
        let cleared = tsdf.clearWhere { p in
            let q = p - eye, z = simd_dot(q, fwd)
            guard z > 0.05, z <= limit else { return false }
            let sx = simd_dot(q, right) / z * f, sy = simd_dot(q, up) / z * f
            return sx * sx + sy * sy < r * r
        }
        let after = Fusion.mesh(tsdf)
        let farWall = after.positions.filter { $0.z < 0.1 && abs($0.x - room.size.x / 2) < 0.3 && abs($0.y - 1.4) < 0.3 }.count
        let behind = after.positions.filter { $0.z < -0.05 }.count
        print("Tap to clear (rule check): \(cleared) voxels cleared; mesh corners \(before) → \(after.positions.count); tapped wall patch left: \(farWall) corners (want 0); anything past the wall: \(behind)")
    }

    /// A synthetic scan as a .rmsc the apps can open: photos, depth, ARKit-like poses, points, mesh, walls / doors /
    /// windows (RoomPlan-like ground truth), and objects.
    static func writeRmsc(_ scenario: Scenario, _ profile: Profile, to url: URL) throws {
        let room = scenario.room
        let frames = Sensors.frames(room, profile, duration: 40, fps: 10)
        var rng = Rand(s: 7)
        let w = try Zip.Writer(url: url)
        var photos: [[String: Any]] = []
        var lastKey: simd_float4x4?
        for f in frames {
            if let k = lastKey {
                let moved = simd_distance(k.columns.3.ux3, f.arkit.columns.3.ux3)
                let turned = acos(min(1, simd_dot(k.columns.2.ux3, f.arkit.columns.2.ux3))) * 180 / .pi
                guard moved > 0.25 || turned > 15 else { continue }
            }
            lastKey = f.arkit
            let i = photos.count
            let pw = 1024, ph = 768
            let rgba = Sensors.photo(room, f.truth, width: pw, height: ph, hfov: profile.hfovDegrees, time: Float(f.time), rng: &rng)
            try w.add(String(format: "photos/%03d.jpg", i), data: Images.jpeg(rgba, width: pw, height: ph))
            // Depth at half size, millimetres, as the app keeps it.
            let d = f.depth, dw = d.width / 2, dh = d.height / 2
            var mm = [UInt16](repeating: 0, count: dw * dh)
            for y in 0..<dh { for x in 0..<dw { mm[y * dw + x] = UInt16(max(0, min(65535, d.depth[(y * 2) * d.width + x * 2] * 1000))) } }
            try w.add(String(format: "depth/%03d.bin", i), data: mm.withUnsafeBytes { Data($0) })
            // ARKit camera axes (y up, -z forward) for the pose.
            let ar = f.arkit * simd_float4x4(diagonal: SIMD4(1, -1, -1, 1))
            let fx = Float(pw) / 2 / tan(profile.hfovDegrees * .pi / 360)
            photos.append(["file": String(format: "photos/%03d.jpg", i), "width": pw, "height": ph, "fx": fx, "fy": fx,
                           "cx": Float(pw) / 2, "cy": Float(ph) / 2, "time": f.time,
                           "pose": [ar.columns.0, ar.columns.1, ar.columns.2, ar.columns.3].flatMap { [$0.x, $0.y, $0.z, $0.w] },
                           "depth": ["file": String(format: "depth/%03d.bin", i), "width": dw, "height": dh,
                                     "fx": d.fx / 2, "fy": d.fy / 2, "cx": d.cx / 2, "cy": d.cy / 2]])
        }
        try w.add("photos.json", data: try JSONSerialization.data(withJSONObject: ["photos": photos]))
        let mesh = Fusion.mesh(Fusion.fuse(frames, poses: frames.map(\.arkit)))
        // Points: the mesh's corners in the true surface colours.
        var ply = Data("ply\nformat binary_little_endian 1.0\nelement vertex \(mesh.positions.count)\nproperty float x\nproperty float y\nproperty float z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n".utf8)
        var meshPly = Data("ply\nformat binary_little_endian 1.0\ncomment RoomScape harness\nelement vertex \(mesh.positions.count)\nproperty float x\nproperty float y\nproperty float z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\nelement face \(mesh.triangles.count / 3)\nproperty list uchar int vertex_indices\nend_header\n".utf8)
        for p in mesh.positions {
            let c = room.cast(SIMD3(room.size.x / 2, 1.3, room.size.z / 2), simd_normalize(p - SIMD3(room.size.x / 2, 1.3, room.size.z / 2))).map(room.colour) ?? SIMD3(0.5, 0.5, 0.5)
            var v = p
            for k in 0..<3 { withUnsafeBytes(of: &v[k]) { ply.append(contentsOf: $0); meshPly.append(contentsOf: $0) } }
            let rgb = [UInt8(c.x * 255), UInt8(c.y * 255), UInt8(c.z * 255)]
            ply.append(contentsOf: rgb); meshPly.append(contentsOf: rgb)
        }
        for t in stride(from: 0, to: mesh.triangles.count, by: 3) {
            meshPly.append(3)
            for k in 0..<3 { var idx = Int32(mesh.triangles[t + k]); withUnsafeBytes(of: &idx) { meshPly.append(contentsOf: $0) } }
        }
        try w.add("points.ply", data: ply)
        try w.add("mesh.ply", data: meshPly)
        let s = room.size
        let manifest: [String: Any] = [
            "format": "rmsc", "version": 1, "id": UUID().uuidString, "revision": 1, "revisionBy": "harness",
            "name": "Synthetic \(room.name)", "created": ISO8601DateFormatter().string(from: Date()),
            "source": ["platform": "harness", "app": "RoomScape test harness", "device": profile.name],
            "floorY": 0, "ceilingY": s.y,
            "counts": ["points": mesh.positions.count, "photos": photos.count, "triangles": mesh.triangles.count / 3],
            "room": ["corners": [[0, 0, 0], [s.x, 0, 0], [s.x, 0, s.z], [0, 0, s.z]],
                     "walls": [[0, 0, s.x, 0, s.y], [s.x, 0, s.x, s.z, s.y], [s.x, s.z, 0, s.z, s.y], [0, s.z, 0, 0, s.y]],
                     "windows": room.openings.filter(\.isWindow).count, "doors": room.openings.filter { !$0.isWindow }.count],
            "objects": room.boxes.map { b -> [String: Any] in
                let c = (b.min + b.max) / 2, z = b.max - b.min
                return ["id": UUID().uuidString, "name": b.name, "source": "harness", "center": [c.x, c.y, c.z],
                        "size": [z.x, z.y, z.z], "yaw": 0, "removed": false, "moved": false]
            }]
        try w.add("manifest.json", data: try JSONSerialization.data(withJSONObject: manifest, options: .prettyPrinted))
        try w.finish()
        print("Wrote \(url.path): \(photos.count) photos, \(mesh.triangles.count / 3) triangles, \(room.boxes.count) objects")
    }
}
