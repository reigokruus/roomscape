#if DEBUG
import AVFoundation
import CoreImage
import SwiftUI
import simd

/// The scanning screen without a phone (Debug builds, the Simulator; docs/test-harness.md): a raw recording
/// (RawRecorder: video.mov, frames.bin, depth.bin) plays as the camera, with the scan card, the coverage overlay and
/// Cancel / Done laid out as in ScannerScreen. For screenshots and screen videos of the scan UI (tools/ui-shots.sh).
///
/// Launch with `-virtualScan <recording folder>` (optionally `-virtualScanAt <seconds>` to start part-way).
/// The hints come from the same rules as the live scan (ScanHud.hint); "This spot" is the share of the current depth
/// frame that is usable surface, "Room" the share of the recording's surface cells seen so far.
struct VirtualScanScreen: View {
    let folder: URL
    var startAt: Double = 0
    var onClose: () -> Void = {}

    @State private var model: Model?
    @State private var error: String?
    @State private var t: Double = 0
    @State private var image: CGImage?

    final class Model: @unchecked Sendable {
        let frames: [RecordingProcessor.FrameRecord]
        let depth: [RecordingProcessor.DepthFrame]
        let generator: AVAssetImageGenerator
        /// Per depth frame: usable share of the frame, and the share of all surface cells seen up to then.
        var spot: [Float] = [], room: [Float] = []
        /// World points (sampled) and the depth frame each was first seen in, for the overlay.
        var points: [SIMD3<Float>] = [], firstSeen: [Int] = []

        init(folder: URL) throws {
            frames = try RecordingProcessor.readFrames(folder.appendingPathComponent("frames.bin"))
            depth = try RecordingProcessor.readDepth(folder.appendingPathComponent("depth.bin"))
            let asset = AVURLAsset(url: folder.appendingPathComponent("video.mov"))
            generator = AVAssetImageGenerator(asset: asset)
            generator.requestedTimeToleranceBefore = CMTime(value: 1, timescale: 20)
            generator.requestedTimeToleranceAfter = CMTime(value: 1, timescale: 20)
            generator.maximumSize = CGSize(width: 960, height: 960)
            var seen = Set<SIMD3<Int32>>()
            var cum: [Int] = []
            var fi = 0
            for (n, d) in depth.enumerated() {
                while fi + 1 < frames.count, abs(frames[fi + 1].time - d.time) <= abs(frames[fi].time - d.time) { fi += 1 }
                let f = frames[fi]
                let sx = Float(d.w) / Float(f.width), sy = Float(d.h) / Float(f.height)
                let fx = f.k[0][0] * sx, fy = f.k[1][1] * sy, cx = f.k[2][0] * sx, cy = f.k[2][1] * sy
                var good = 0, all = 0
                for y in stride(from: 0, to: d.h, by: 4) {
                    for x in stride(from: 0, to: d.w, by: 4) {
                        all += 1
                        let z = Float(d.mm[y * d.w + x]) / 1000
                        guard z > 0.2, z < 4, d.conf.isEmpty || d.conf[y * d.w + x] >= 1 else { continue }
                        good += 1
                        guard f.tracking == 2 else { continue }
                        let p4 = f.pose * SIMD4((Float(x) - cx) * z / fx, -(Float(y) - cy) * z / fy, -z, 1)
                        let p = SIMD3(p4.x, p4.y, p4.z)
                        let cell = SIMD3<Int32>((p / 0.1).rounded(.down))
                        if seen.insert(cell).inserted, (x / 4 + y / 4) % 3 == 0 { points.append(p); firstSeen.append(n) }
                    }
                }
                spot.append(all == 0 ? 0 : Float(good) / Float(all))
                cum.append(seen.count)
            }
            let total = Float(max(1, seen.count))
            room = cum.map { Float($0) / total }
        }

        func depthIndex(_ time: Double) -> Int {
            guard !depth.isEmpty else { return 0 }
            var lo = 0, hi = depth.count - 1
            while lo < hi { let mid = (lo + hi) / 2; if depth[mid].time < time { lo = mid + 1 } else { hi = mid } }
            return lo
        }

        func frame(_ time: Double) -> RecordingProcessor.FrameRecord? {
            guard !frames.isEmpty else { return nil }
            var lo = 0, hi = frames.count - 1
            while lo < hi { let mid = (lo + hi) / 2; if frames[mid].time < time { lo = mid + 1 } else { hi = mid } }
            return frames[lo]
        }
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            Color.black.ignoresSafeArea()
            if let model {
                GeometryReader { g in
                    ZStack {
                        if let image {
                            // The recording keeps the sensor's landscape frame: turned a quarter clockwise for portrait.
                            Image(decorative: image, scale: 1)
                                .resizable()
                                .aspectRatio(contentMode: .fill)
                                .frame(width: g.size.height, height: g.size.width)
                                .rotationEffect(.degrees(90))
                                .frame(width: g.size.width, height: g.size.height)
                                .clipped()
                        }
                        overlay(model, size: g.size)
                    }
                }
                .ignoresSafeArea()
                HStack(spacing: 16) {
                    Button { onClose() } label: { Text("Cancel").frame(minWidth: 80) }
                        .buttonStyle(.bordered)
                        .buttonBorderShape(.capsule)
                        .background(Color(white: 0.17).opacity(0.9), in: Capsule())
                    Button { onClose() } label: { Text("Done").fontWeight(.semibold).foregroundStyle(.black).frame(minWidth: 80) }
                        .buttonStyle(.borderedProminent)
                        .buttonBorderShape(.capsule)
                }
                .controlSize(.large)
                .padding(.bottom, 32)
                ScanHudCard(hud: hud(model), notes: ["Virtual scan: \(folder.lastPathComponent), \(String(format: "%.0f", t)) s"])
                    .padding(.horizontal, 12)
                    .padding(.top, 8)
                    .frame(maxHeight: .infinity, alignment: .top)
            } else {
                Text(error ?? "Loading the recording…").foregroundStyle(.white).padding()
                    .frame(maxHeight: .infinity)
            }
        }
        .task {
            do {
                let folder = folder
                let m = try await Task.detached(priority: .userInitiated) { try Model(folder: folder) }.value
                model = m
                await play(m)
            } catch { self.error = "Couldn't read the recording: \(error.localizedDescription)" }
        }
    }

    /// Plays the recording at its own pace (10 frames a second, as recorded).
    private func play(_ m: Model) async {
        guard let first = m.frames.first?.time, let last = m.frames.last?.time else { return }
        t = startAt
        while !Task.isCancelled, first + t <= last {
            let time = CMTime(seconds: t, preferredTimescale: 600)
            if let cg = try? await m.generator.image(at: time).image { image = cg }
            try? await Task.sleep(nanoseconds: 100_000_000)
            t += 0.1
        }
    }

    private func hud(_ m: Model) -> ScanHud {
        guard let first = m.frames.first?.time, !m.depth.isEmpty else { return ScanHud() }
        let i = m.depthIndex(first + t)
        let tracking = m.frame(first + t)?.tracking ?? 0
        let spot = m.spot[i], points = m.firstSeen.prefix { $0 <= i }.count * 30
        let (hint, level) = ScanHud.hint(tracking: tracking == 2 ? .normal : .limited(.initializing), spot: spot, points: points, tooFast: false)
        return ScanHud(hint: hint, level: level, spot: spot, room: m.room[i])
    }

    /// The light coverage overlay: what has been seen so far, as small dots projected into the camera view.
    private func overlay(_ m: Model, size: CGSize) -> some View {
        Canvas { ctx, _ in
            guard let first = m.frames.first?.time, let f = m.frame(first + t) else { return }
            let i = m.depthIndex(first + t)
            let view = f.pose.inverse
            // Portrait screen from the landscape image: aspect fill of the rotated image.
            let imgW = Float(f.width), imgH = Float(f.height)
            let s = max(Float(size.width) / imgH, Float(size.height) / imgW)
            let ox = (Float(size.width) - imgH * s) / 2, oy = (Float(size.height) - imgW * s) / 2
            var path = Path()
            for (n, p) in m.points.enumerated() where m.firstSeen[n] <= i {
                let c = view * SIMD4(p, 1)
                guard c.z < -0.1 else { continue }
                let u = f.k[0][0] * c.x / -c.z + f.k[2][0], v = f.k[1][1] * -c.y / -c.z + f.k[2][1]
                guard u >= 0, v >= 0, u < imgW, v < imgH else { continue }
                // A quarter turn clockwise: screen x from the image's bottom edge, screen y along the image's x.
                let x = ox + (imgH - v) * s, y = oy + u * s
                path.addEllipse(in: CGRect(x: CGFloat(x) - 1.5, y: CGFloat(y) - 1.5, width: 3, height: 3))
            }
            ctx.fill(path, with: .color(.white.opacity(0.55)))
        }
        .allowsHitTesting(false)
    }
}
#endif
