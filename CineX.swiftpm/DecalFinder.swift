import AVFoundation
import CoreImage
import Foundation
import ImageIO
import simd
import Vision
import SceneKit

/// Sharp text on surfaces (rk 9 Nov): text seen in the scan's recording (posters, labels, signs, book spines, screens)
/// is kept as a straightened, high-resolution picture of that patch of surface: a "decal" drawn over the photo room,
/// as clear as the text was in the video. Not OCR: the pixels, not the letters.
///
/// From a raw recording (RawRecorder): text is found in video frames (Vision, used only for where), each sighting is
/// placed in the room with the recorded depth and pose, sightings of the same patch are grouped, and the sharpest,
/// most head-on, closest one is straightened onto its surface (about 2 px per mm, capped) and saved in `decals/`.
/// Builds on iOS and macOS (the Mac's harness runs it on real recordings).
enum DecalFinder {
    struct Decal: Codable {
        /// The patch's corners in the world (top left, top right, bottom right, bottom left), metres.
        var corners: [[Float]]
        /// Which way the patch faces (towards where it was seen from), unit.
        var normal: [Float]? = nil
        var file: String
        var pixelsPerMetre: Float
        var score: Float
    }

    struct Frame { var time: Double; var pose: simd_float4x4; var k: simd_float3x3; var width: Int; var height: Int }
    typealias Depth = (time: Double, w: Int, h: Int, mm: [UInt16])

    private struct Sighting {
        var corners: [SIMD3<Float>]
        var normal: SIMD3<Float> = .zero
        var centre: SIMD3<Float>
        var pixels: [CGPoint] // in the frame's picture, top-left origin
        var frameIndex: Int
        var score: Float
    }

    /// Finds the decals in a recording and writes them into `out` (decal images + decals.json). `pace` is called
    /// between frames (the thermal governor), `progress` with how far through the video it is (0…1). Stops with
    /// CancellationError when its task is cancelled. Returns how many it kept.
    static func find(recording rec: URL, frames: [Frame], depth: [Depth], out: URL,
                     pace: () async -> Void = {}, progress: (Float) -> Void = { _ in },
                     log: (String) -> Void = { _ in }) async throws -> Int {
        let asset = AVURLAsset(url: rec.appendingPathComponent("video.mov"))
        guard let track = try await asset.loadTracks(withMediaType: .video).first, !frames.isEmpty else { return 0 }
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA])
        reader.add(output)
        reader.startReading()
        let start = frames[0].time
        var sightings: [Sighting] = []
        var kept: [Int: CIImage] = [:] // frame index -> picture, only for frames with text
        var lastLook = -1.0
        var fi = 0
        while let sample = output.copyNextSampleBuffer() {
            let t = start + CMSampleBufferGetPresentationTimeStamp(sample).seconds
            while fi + 1 < frames.count, frames[fi + 1].time <= t + 0.001 { fi += 1 }
            guard t - lastLook >= 0.5, let buffer = CMSampleBufferGetImageBuffer(sample) else { continue } // 2 looks a second
            lastLook = t
            try Task.checkCancellation()
            progress(Float(fi) / Float(frames.count))
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = false
            let visionStart = Date()
            try? VNImageRequestHandler(cvPixelBuffer: buffer, options: [:]).perform([request])
            PerfLog.processing?.note("vision text", ms: Date().timeIntervalSince(visionStart) * 1000)
            // Real text only: what it read has at least two letters or digits, read with some confidence (patterned
            // fabric and radiators passed as "text" with the fast detector).
            let boxes = (request.results ?? []).filter { obs in
                guard let top = obs.topCandidates(1).first, top.confidence >= 0.5 else { return false }
                return top.string.unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) }.count >= 2
            }
            guard !boxes.isEmpty, let d = depth.min(by: { abs($0.time - t) < abs($1.time - t) }), abs(d.time - t) < 0.15 else { await pace(); continue }
            let f = frames[fi]
            let image = CIImage(cvPixelBuffer: buffer)
            var any = false
            for box in boxes {
                if let s = place(box.boundingBox, frame: f, frameIndex: fi, depth: d, image: image) { sightings.append(s); any = true }
            }
            if any { kept[fi] = image.cropped(to: image.extent) }
            await pace()
        }
        // Group sightings of the same patch (centres within 10 cm), keep the best of each.
        var groups: [[Sighting]] = []
        for s in sightings {
            if let i = groups.firstIndex(where: { simd_distance($0[0].centre, s.centre) < 0.1 }) { groups[i].append(s) } else { groups.append([s]) }
        }
        try? FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
        let ctx = CIContext(options: [.cacheIntermediates: false])
        var decals: [Decal] = []
        for (n, g) in groups.enumerated() {
            guard let best = g.max(by: { $0.score < $1.score }), let image = kept[best.frameIndex] else { continue }
            // Straighten: the four corners in the picture onto a rectangle, ~2 px per mm, at most 1024 px wide.
            let w = simd_distance(best.corners[0], best.corners[1]), h = simd_distance(best.corners[0], best.corners[3])
            guard w > 0.01, h > 0.005, max(w, h) < 0.8 else { continue } // text patches, not whole walls
            let ppm = min(2000, 1024 / max(w, h))
            let H = CGFloat(image.extent.height)
            func ci(_ p: CGPoint) -> CIVector { CIVector(x: p.x, y: H - p.y) } // CIImage: bottom-left origin
            guard let filter = CIFilter(name: "CIPerspectiveCorrection") else { continue }
            filter.setValue(image, forKey: kCIInputImageKey)
            filter.setValue(ci(best.pixels[0]), forKey: "inputTopLeft")
            filter.setValue(ci(best.pixels[1]), forKey: "inputTopRight")
            filter.setValue(ci(best.pixels[2]), forKey: "inputBottomRight")
            filter.setValue(ci(best.pixels[3]), forKey: "inputBottomLeft")
            guard var straight = filter.outputImage else { continue }
            let target = CGSize(width: CGFloat(w * ppm), height: CGFloat(h * ppm))
            straight = straight.transformed(by: CGAffineTransform(scaleX: target.width / straight.extent.width, y: target.height / straight.extent.height))
            let file = String(format: "decal-%03d.jpg", n)
            guard let jpeg = ctx.jpegRepresentation(of: straight, colorSpace: CGColorSpace(name: CGColorSpace.sRGB)!,
                                                    options: [kCGImageDestinationLossyCompressionQuality as CIImageRepresentationOption: 0.9]) else { continue }
            try jpeg.write(to: out.appendingPathComponent(file))
            decals.append(Decal(corners: best.corners.map { [$0.x, $0.y, $0.z] }, normal: [best.normal.x, best.normal.y, best.normal.z],
                                file: file, pixelsPerMetre: ppm, score: best.score))
        }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted]
        try encoder.encode(decals).write(to: out.appendingPathComponent("decals.json"))
        log("decals: \(sightings.count) text sightings, \(groups.count) patches, \(decals.count) kept")
        return decals.count
    }

    /// A text box (Vision's normalised, bottom-left origin) placed in the room: its corners on the surface plane fitted
    /// to the depth inside it, and how good this view is (sharp, head-on, close).
    private static func place(_ box: CGRect, frame f: Frame, frameIndex: Int, depth d: Depth, image: CIImage) -> Sighting? {
        // A little margin around the letters.
        let b = box.insetBy(dx: -box.width * 0.08, dy: -box.height * 0.25)
        let W = Float(f.width), H = Float(f.height)
        func pixel(_ x: CGFloat, _ y: CGFloat) -> SIMD2<Float> { SIMD2(Float(x) * W, (1 - Float(y)) * H) } // top-left origin
        let px = [pixel(b.minX, b.maxY), pixel(b.maxX, b.maxY), pixel(b.maxX, b.minY), pixel(b.minX, b.minY)]
        // Depth samples inside the box, as camera points (ARKit camera: x right, y up, looking down -z).
        let sx = Float(d.w) / W, sy = Float(d.h) / H
        let fx = f.k[0][0], fy = f.k[1][1], cx = f.k[2][0], cy = f.k[2][1]
        var pts: [SIMD3<Float>] = []
        let x0 = Int(px[0].x * sx), x1 = Int(px[1].x * sx), y0 = Int(px[0].y * sy), y1 = Int(px[2].y * sy)
        guard x1 > x0, y1 > y0 else { return nil }
        for y in max(0, y0)..<min(d.h, y1 + 1) { for x in max(0, x0)..<min(d.w, x1 + 1) {
            let z = Float(d.mm[y * d.w + x]) / 1000
            guard z > 0.2, z < 4 else { continue }
            let u = (Float(x) + 0.5) / sx, v = (Float(y) + 0.5) / sy
            pts.append(SIMD3((u - cx) * z / fx, -(v - cy) * z / fy, -z))
        } }
        guard pts.count >= 4 else { return nil }
        // Plane through them (centroid + normal from the spread), facing the camera.
        let m = pts.reduce(.zero, +) / Float(pts.count)
        var cov = simd_float3x3(0)
        for p in pts { let e = p - m; cov += simd_float3x3(e * e.x, e * e.y, e * e.z) }
        var n = smallestAxis(cov)
        if simd_dot(n, m) > 0 { n = -n }
        // Each corner's ray onto that plane.
        var cam: [SIMD3<Float>] = []
        for p in px {
            let ray = simd_normalize(SIMD3((p.x - cx) / fx, -(p.y - cy) / fy, -1))
            let denom = simd_dot(n, ray)
            guard abs(denom) > 0.05 else { return nil }
            let t = simd_dot(n, m) / denom
            guard t > 0.1, t < 5 else { return nil }
            cam.append(ray * t)
        }
        let world = cam.map { c -> SIMD3<Float> in let w = f.pose * SIMD4(c, 1); return SIMD3(w.x, w.y, w.z) }
        let centre = world.reduce(.zero, +) / 4
        // Score: sharp (gradient energy in the box), head-on, close.
        let headOn = abs(simd_dot(n, simd_normalize(-m)))
        let sharp = sharpness(image, CGRect(x: CGFloat(px[0].x), y: CGFloat(H - px[2].y), width: CGFloat(px[1].x - px[0].x), height: CGFloat(px[2].y - px[0].y)))
        let score = sharp * headOn * headOn / max(0.3, simd_length(m))
        let nw4 = f.pose * SIMD4(n, 0)
        return Sighting(corners: world, normal: simd_normalize(SIMD3(nw4.x, nw4.y, nw4.z)), centre: centre,
                        pixels: px.map { CGPoint(x: CGFloat($0.x), y: CGFloat($0.y)) }, frameIndex: frameIndex, score: score)
    }

    /// How sharp a region is: mean absolute difference between neighbouring pixels of a small grey copy.
    private static func sharpness(_ image: CIImage, _ rect: CGRect) -> Float {
        let crop = image.cropped(to: rect.intersection(image.extent))
        guard crop.extent.width > 4, crop.extent.height > 4 else { return 0 }
        let scale = min(1, 128 / crop.extent.width)
        let small = crop.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let w = Int(small.extent.width), h = Int(small.extent.height)
        guard w > 2, h > 2 else { return 0 }
        var px = [UInt8](repeating: 0, count: w * h * 4)
        CIContext(options: [.cacheIntermediates: false]).render(small, toBitmap: &px, rowBytes: w * 4, bounds: small.extent, format: .RGBA8, colorSpace: nil)
        var sum: Float = 0
        for y in 0..<h { for x in 1..<w { let i = (y * w + x) * 4; sum += abs(Float(px[i + 1]) - Float(px[i - 3])) } }
        return sum / Float(w * h)
    }

    private static func smallestAxis(_ m: simd_float3x3) -> SIMD3<Float> {
        // Power iteration on (trace·I − M) gives the axis with the smallest spread.
        let tr = m[0][0] + m[1][1] + m[2][2]
        let a = simd_float3x3(diagonal: SIMD3(repeating: tr)) - m
        var v = simd_normalize(SIMD3<Float>(0.3, 0.5, 0.8))
        for _ in 0..<30 { let w = a * v; let l = simd_length(w); if l < 1e-12 { break }; v = w / l }
        return v
    }
}

extension DecalFinder {
    /// The scan's decals as SceneKit quads, 4 mm in front of their surface (drawn in Photo over the photo room).
    static func node(folder: URL) -> SCNNode? {
        let dir = folder.appendingPathComponent("decals")
        guard let data = try? Data(contentsOf: dir.appendingPathComponent("decals.json")),
              let decals = try? JSONDecoder().decode([Decal].self, from: data), !decals.isEmpty else { return nil }
        let root = SCNNode()
        root.name = "decals"
        for d in decals where d.corners.count == 4 {
            let c = d.corners.map { SIMD3<Float>($0[0], $0[1], $0[2]) }
            let n = d.normal.map { SIMD3<Float>($0[0], $0[1], $0[2]) } ?? simd_normalize(simd_cross(c[1] - c[0], c[3] - c[0]))
            let lifted = c.map { SCNVector3($0 + n * 0.004) }
            let uv = [CGPoint(x: 0, y: 0), CGPoint(x: 1, y: 0), CGPoint(x: 1, y: 1), CGPoint(x: 0, y: 1)]
            let g = SCNGeometry(sources: [SCNGeometrySource(vertices: lifted), SCNGeometrySource(textureCoordinates: uv)],
                                elements: [SCNGeometryElement(indices: [Int32(0), 1, 2, 0, 2, 3], primitiveType: .triangles)])
            let m = SCNMaterial()
            m.diffuse.contents = dir.appendingPathComponent(d.file)
            m.lightingModel = .constant
            m.isDoubleSided = true
            g.materials = [m]
            root.addChildNode(SCNNode(geometry: g))
        }
        return root
    }
}
