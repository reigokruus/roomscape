import CoreGraphics
import Foundation
import ImageIO
import simd

/// Finds windows (daylight sources) on RoomPlan's walls from what the scan captured besides RoomPlan's model:
/// the photos and the LiDAR points. RoomPlan often misses windows, especially curtained ones, and sometimes calls a
/// picture or a glass partition a window. Two signals RoomPlan doesn't use:
/// - **Brightness**: a daylit window is the brightest thing on its wall in the photos, often blown out to white,
///   curtains or not. Measured relative to the rest of the same wall, so a white wall isn't mistaken for a window.
/// - **See-through**: LiDAR passes through clear glass, so points land behind the wall plane where a window is.
///   Alone that also fires on glass partitions to a corridor, so it only adds confidence to a bright patch.
///
/// Each wall gets a 10 cm grid in its own plane. Bright cells are grouped into rectangles; big enough ones are windows.
enum WindowFinder {
    /// A wall plane: centre, unit axes (right along the wall, up, inward = into the room) and half sizes, metres.
    struct Wall {
        var center: SIMD3<Float>
        var right: SIMD3<Float>, up: SIMD3<Float>, inward: SIMD3<Float>
        var halfWidth: Float, halfHeight: Float
    }

    /// A photo: camera-to-world pose (ARKit: x right, y up, looking down -z), intrinsics in pixels of the JPEG.
    struct Photo {
        var cameraToWorld: simd_float4x4
        var fx: Float, fy: Float, cx: Float, cy: Float
        var width: Int, height: Int
        var url: URL
    }

    enum Kind: String, Codable {
        /// Daylight straight through glass: blown out in the photos, and LiDAR sees through.
        case clearGlass
        /// A bright solid surface on the wall: curtains or blinds with daylight behind them.
        case curtained
    }

    /// A window found on a wall: a rectangle in the wall's plane.
    struct Found: Codable, Equatable {
        var wallIndex: Int
        var kind: Kind
        /// Rectangle in wall coordinates: metres along `right` and `up` from the wall centre.
        var minAlong: Float, maxAlong: Float, minUp: Float, maxUp: Float
        /// Mean photo brightness inside, 0…1, and relative to the wall's typical brightness.
        var brightness: Float, contrast: Float
        /// Share of the rectangle where LiDAR saw through the wall.
        var seeThrough: Float
        /// Share of the rectangle that is blown out to (nearly) white in the photos: typical of daylight.
        var blownOut: Float
        /// Share of the rectangle where LiDAR found a surface on the wall plane (curtain, blind, frame) rather than a
        /// hole (clear glass gives no LiDAR return).
        var solid: Float
        /// Of all the photos that saw the rectangle, the share in which it looked bright. A window is bright from
        /// every angle; glare (a reflection of a ceiling light) moves or disappears as the camera moves.
        var consistency: Float
        /// Mean brightness change between neighbouring 10 cm cells inside, across (x) and along (y) the wall.
        /// Curtain pleats and blind slats make stripes; a wall lit by a lamp is smooth.
        var textureAcross: Float, textureUp: Float
        /// True when it overlaps a window RoomPlan reported.
        var matchesRoomPlan: Bool

        var width: Float { maxAlong - minAlong }
        var height: Float { maxUp - minUp }
        var area: Float { width * height }
    }

    static let cell: Float = 0.10

    /// Per-wall measurements, kept for debugging and tuning.
    struct WallGrid {
        var cols: Int, rows: Int
        var lum: [Float]      // mean photo luminance per cell, -1 = never seen
        var views: [Int]
        var brightViews: [Int] // views in which the cell looked bright (≥ brightAbsolute)
        var onWall: [Int]     // LiDAR points on the wall plane
        var behind: [Int]     // LiDAR points seen through it
        var typical: Float    // the wall's median luminance
        var bright: [Bool]
    }

    /// - Parameters:
    ///   - points: x, y, z (metres) and colour; only positions are used.
    ///   - roomPlanWindows: RoomPlan's windows as (wall index, rectangle in wall coordinates), to mark matches.
    static func find(walls: [Wall], points: [SIMD3<Float>], photos: [Photo],
                     roomPlanWindows: [(wall: Int, minAlong: Float, maxAlong: Float, minUp: Float, maxUp: Float)] = [],
                     grids: UnsafeMutablePointer<[WallGrid]>? = nil) -> [Found] {
        let images = photos.compactMap { photo -> (Photo, GrayImage)? in
            guard let gray = GrayImage(url: photo.url, maxSide: 192) else { return nil }
            return (photo, gray)
        }
        var allGrids = walls.map { measure($0, points: points, images: images) }
        // The room's typical wall brightness. Per wall doesn't work: a curtained window can fill most of its wall.
        var seen = allGrids.flatMap { $0.lum.filter { $0 >= 0 } }
        seen.sort()
        let typical = seen.isEmpty ? 0.5 : seen[seen.count / 2]
        for gi in allGrids.indices {
            allGrids[gi].typical = typical
            for i in allGrids[gi].lum.indices where allGrids[gi].lum[i] >= 0 {
                let l = allGrids[gi].lum[i]
                allGrids[gi].bright[i] = l >= brightAbsolute && l >= typical * brightRelative
            }
        }
        var found: [Found] = []
        candidates = []
        for (wi, wall) in walls.enumerated() {
            let grid = allGrids[wi]
            for rect in rectangles(in: grid) {
                let minAlong = Float(rect.c0) * cell - wall.halfWidth, maxAlong = Float(rect.c1 + 1) * cell - wall.halfWidth
                let minUp = Float(rect.r0) * cell - wall.halfHeight, maxUp = Float(rect.r1 + 1) * cell - wall.halfHeight
                var lumSum: Float = 0, lumN = 0, through = 0, cells = 0, blown = 0, solidCells = 0
                var views = 0, brightViews = 0
                var dx: Float = 0, dxN = 0, dy: Float = 0, dyN = 0
                for r in rect.r0...rect.r1 {
                    for c in rect.c0...rect.c1 {
                        let i = r * grid.cols + c
                        cells += 1
                        if grid.lum[i] >= 0 { lumSum += grid.lum[i]; lumN += 1 }
                        if grid.lum[i] >= 0.95 { blown += 1 }
                        if grid.onWall[i] >= 2 { solidCells += 1 }
                        if grid.bright[i] { views += grid.views[i]; brightViews += grid.brightViews[i] }
                        if grid.lum[i] >= 0 {
                            if c < rect.c1, grid.lum[i + 1] >= 0 { dx += abs(grid.lum[i + 1] - grid.lum[i]); dxN += 1 }
                            if r < rect.r1, grid.lum[i + grid.cols] >= 0 {
                                dy += abs(grid.lum[i + grid.cols] - grid.lum[i]); dyN += 1
                            }
                        }
                        if grid.behind[i] > max(2, grid.onWall[i]) { through += 1 }
                    }
                }
                let brightness = lumN > 0 ? lumSum / Float(lumN) : 0
                let matches = roomPlanWindows.contains { w in
                    w.wall == wi && overlap(minAlong, maxAlong, w.minAlong, w.maxAlong) > 0
                        && overlap(minUp, maxUp, w.minUp, w.maxUp) > 0
                }
                let candidate = Found(wallIndex: wi, kind: .curtained, minAlong: minAlong, maxAlong: maxAlong, minUp: minUp, maxUp: maxUp,
                                   brightness: brightness, contrast: brightness / max(0.05, grid.typical),
                                   seeThrough: Float(through) / Float(max(1, cells)),
                                   blownOut: Float(blown) / Float(max(1, cells)),
                                   solid: Float(solidCells) / Float(max(1, cells)),
                                   consistency: Float(brightViews) / Float(max(1, views)),
                                   textureAcross: dx / Float(max(1, dxN)), textureUp: dy / Float(max(1, dyN)),
                                   matchesRoomPlan: matches)
                candidates.append(candidate)
                if let kind = classify(candidate) {
                    var window = candidate
                    window.kind = kind
                    found.append(window)
                }
            }
        }
        grids?.pointee = allGrids
        // A piece inside a bigger window on the same wall (e.g. clear glass above curtains) is part of that window.
        return found.filter { small in
            !found.contains { big in
                big != small && big.wallIndex == small.wallIndex && big.area > small.area
                    && overlap(small.minAlong, small.maxAlong, big.minAlong, big.maxAlong)
                    * overlap(small.minUp, small.maxUp, big.minUp, big.maxUp) > 0.7 * small.area
            }
        }
    }

    /// Bright patches that weren't windows, for debugging.
    nonisolated(unsafe) static var candidates: [Found] = []

    /// Tuned on scans of a room with a curtained window wall, clear glass above it, a lit glass partition to a
    /// corridor and ceiling-light glare on white walls:
    /// - clear glass: blown out (daylight saturates the indoor exposure) and LiDAR sees through. Glare on a wall is
    ///   blown out too, but solid.
    /// - curtains/blinds: a large, bright, solid surface that is *not* blown out and has stripes (pleats or slats).
    ///   A lit glass partition is bright but has no surface (glass gives no LiDAR return); glare is blown out; a
    ///   white wall lit by a ceiling light is smooth (texture 0.003–0.034 measured, curtains 0.061–0.063), and
    ///   bright from every angle, so view consistency can't tell it apart.
    static func classify(_ f: Found) -> Kind? {
        if f.blownOut >= 0.2 && f.seeThrough >= 0.15 { return .clearGlass }
        if f.solid >= 0.4 && f.brightness >= 0.70 && f.blownOut < 0.12 && f.area >= 1.5
            && max(f.textureAcross, f.textureUp) >= 0.045 { return .curtained }
        return nil
    }

    private static func overlap(_ a0: Float, _ a1: Float, _ b0: Float, _ b1: Float) -> Float {
        max(0, min(a1, b1) - max(a0, b0))
    }

    // MARK: Measuring one wall

    private static func measure(_ wall: Wall, points: [SIMD3<Float>], images: [(Photo, GrayImage)]) -> WallGrid {
        let cols = max(1, Int((wall.halfWidth * 2 / cell).rounded(.up)))
        let rows = max(1, Int((wall.halfHeight * 2 / cell).rounded(.up)))
        var g = WallGrid(cols: cols, rows: rows, lum: [Float](repeating: -1, count: cols * rows),
                         views: [Int](repeating: 0, count: cols * rows),
                         brightViews: [Int](repeating: 0, count: cols * rows), onWall: [Int](repeating: 0, count: cols * rows),
                         behind: [Int](repeating: 0, count: cols * rows), typical: 0,
                         bright: [Bool](repeating: false, count: cols * rows))

        // LiDAR: on the plane, or seen through it (projected straight back onto it).
        for p in points {
            let d = p - wall.center
            let along = simd_dot(d, wall.right), up = simd_dot(d, wall.up), off = simd_dot(d, wall.inward)
            // Curtains and blinds hang a little in front of the wall: count up to 25 cm into the room as "on the wall".
            guard off > -6, off < 0.25 else { continue }
            let c = Int((along + wall.halfWidth) / cell), r = Int((up + wall.halfHeight) / cell)
            guard c >= 0, c < cols, r >= 0, r < rows else { continue }
            if off > -0.08 { g.onWall[r * cols + c] += 1 } else if off < -0.2 { g.behind[r * cols + c] += 1 }
        }

        // Photos: every cell centre seen from inside the room, not too obliquely.
        var sums = [Float](repeating: 0, count: cols * rows)
        for (photo, image) in images {
            let pose = photo.cameraToWorld
            let camPos = SIMD3(pose.columns.3.x, pose.columns.3.y, pose.columns.3.z)
            let toCam = camPos - wall.center
            guard simd_dot(toCam, wall.inward) > 0.3 else { continue } // camera on the room side
            let view = pose.inverse
            let sx = Float(image.width) / Float(photo.width), sy = Float(image.height) / Float(photo.height)
            for r in 0..<rows {
                for c in 0..<cols {
                    let along = (Float(c) + 0.5) * cell - wall.halfWidth, up = (Float(r) + 0.5) * cell - wall.halfHeight
                    let world = wall.center + wall.right * along + wall.up * up
                    let ray = simd_normalize(camPos - world)
                    guard simd_dot(ray, wall.inward) > 0.3 else { continue } // less than ~73° from head-on
                    let pc = view * SIMD4(world, 1)
                    let depth = -pc.z
                    guard depth > 0.3, depth < 12 else { continue }
                    let u = (photo.fx * pc.x / depth + photo.cx) * sx
                    let v = (photo.cy - photo.fy * pc.y / depth) * sy
                    let iu = Int(u), iv = Int(v)
                    guard iu >= 1, iv >= 1, iu < image.width - 1, iv < image.height - 1 else { continue }
                    let i = r * cols + c
                    let l = image.at(iu, iv)
                    sums[i] += l
                    g.views[i] += 1
                    if l >= brightAbsolute { g.brightViews[i] += 1 }
                }
            }
        }
        for i in 0..<(cols * rows) where g.views[i] > 0 {
            g.lum[i] = sums[i] / Float(g.views[i])
        }
        return g
    }

    static var brightAbsolute: Float = 0.80
    static var brightRelative: Float = 1.25
    static var minArea: Float = 0.25
    static var minSide: Float = 0.35
    static var minFill: Float = 0.2

    // MARK: Grouping bright cells

    static var closeRadius = 2

    /// Square dilation (`grow`) or erosion of a mask.
    private static func morph(_ m: [Bool], cols: Int, rows: Int, radius: Int, grow: Bool) -> [Bool] {
        var out = m
        for r in 0..<rows {
            for c in 0..<cols {
                var any = false, all = true
                for dr in -radius...radius {
                    for dc in -radius...radius {
                        let nr = r + dr, nc = c + dc
                        let v = nr >= 0 && nr < rows && nc >= 0 && nc < cols ? m[nr * cols + nc] : !grow
                        any = any || v
                        all = all && v
                    }
                }
                out[r * cols + c] = grow ? any : all
            }
        }
        return out
    }

    private struct Rect { var c0: Int, c1: Int, r0: Int, r1: Int, cells: Int }

    /// Connected groups of bright cells (after closing 1-cell gaps from window frames), as bounding rectangles,
    /// kept when big enough and mostly filled.
    private static func rectangles(in g: WallGrid) -> [Rect] {
        let cols = g.cols, rows = g.rows
        // Close gaps up to 40 cm (curtain folds, window frames, a person standing in front): dilate, then erode.
        var mask = morph(g.bright, cols: cols, rows: rows, radius: closeRadius, grow: true)
        mask = morph(mask, cols: cols, rows: rows, radius: closeRadius, grow: false)
        for i in mask.indices where g.lum[i] < 0 { mask[i] = false } // never invent cells nobody saw
        var label = [Int](repeating: -1, count: cols * rows)
        var out: [Rect] = []
        for start in 0..<(cols * rows) where mask[start] && label[start] < 0 {
            var stack = [start]
            label[start] = out.count
            var rect = Rect(c0: start % cols, c1: start % cols, r0: start / cols, r1: start / cols, cells: 0)
            while let i = stack.popLast() {
                let r = i / cols, c = i % cols
                rect.cells += 1
                rect.c0 = min(rect.c0, c); rect.c1 = max(rect.c1, c)
                rect.r0 = min(rect.r0, r); rect.r1 = max(rect.r1, r)
                for (dr, dc) in [(0, 1), (0, -1), (1, 0), (-1, 0)] {
                    let nr = r + dr, nc = c + dc
                    guard nr >= 0, nr < rows, nc >= 0, nc < cols else { continue }
                    let j = nr * cols + nc
                    if mask[j], label[j] < 0 { label[j] = out.count; stack.append(j) }
                }
            }
            out.append(rect)
        }
        return out.filter { rect in
            let w = Float(rect.c1 - rect.c0 + 1) * cell, h = Float(rect.r1 - rect.r0 + 1) * cell
            let fill = Float(rect.cells) / Float((rect.c1 - rect.c0 + 1) * (rect.r1 - rect.r0 + 1))
            return w * h >= minArea && w >= minSide && h >= minSide && fill >= minFill
        }
    }
}

/// A small greyscale copy of a photo (0…1, sRGB-encoded), for brightness lookups.
struct GrayImage {
    let width: Int, height: Int
    let pixels: [Float]

    init?(url: URL, maxSide: Int) {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let thumb = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceThumbnailMaxPixelSize: maxSide,
                kCGImageSourceCreateThumbnailWithTransform: false,
              ] as CFDictionary) else { return nil }
        let w = thumb.width, h = thumb.height
        var bytes = [UInt8](repeating: 0, count: w * h)
        let drawn = bytes.withUnsafeMutableBytes { raw -> Bool in
            guard let ctx = CGContext(data: raw.baseAddress, width: w, height: h, bitsPerComponent: 8,
                                      bytesPerRow: w, space: CGColorSpaceCreateDeviceGray(),
                                      bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return false }
            ctx.draw(thumb, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard drawn else { return nil }
        width = w
        height = h
        pixels = bytes.map { Float($0) / 255 }
    }

    /// Pixel (x right, y down from the top-left, like the photo's u, v).
    func at(_ x: Int, _ y: Int) -> Float { pixels[y * width + x] }
}
