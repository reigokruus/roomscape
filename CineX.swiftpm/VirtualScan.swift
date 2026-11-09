#if DEBUG
import AVFoundation
import CoreImage
import MetalKit
import SwiftUI
import UniformTypeIdentifiers
import simd

/// The scanning screen without a phone (Debug builds, the Simulator; docs/test-harness.md): a raw recording
/// (RawRecorder: video.mov, frames.bin, depth.bin) plays as the camera, and its depth and pictures go through the same
/// live preview as a real scan: `LiveMesh` (TSDF, orange → white), `LivePhotoStore` and the per-triangle photo choice
/// (photo-filled triangles), drawn by `LiveMeshRenderer` over the dimmed camera, with the scan card and Cancel / Done
/// laid out as in ScannerScreen. For checking the live preview and taking screenshots and screen videos of it.
///
/// Launch with `-virtualScan <recording folder>` (optionally `-virtualScanAt <seconds>` to start part-way: the
/// recording up to there is fused first, without drawing).
struct VirtualScanScreen: View {
    let folder: URL
    var startAt: Double = 0
    var onClose: () -> Void = {}

    @StateObject private var player = Player()

    var body: some View {
        ZStack(alignment: .bottom) {
            Color.black.ignoresSafeArea()
            GeometryReader { g in
                ZStack {
                    if !LivePreview.showCamera {
                        ZebraBackground() // as the scan screen: only what was captured, no camera picture
                    } else if let image = player.image {
                        // The recording keeps the sensor's landscape frame: turned a quarter clockwise for portrait.
                        Image(decorative: image, scale: 1)
                            .resizable()
                            .aspectRatio(contentMode: .fill)
                            .frame(width: g.size.height, height: g.size.width)
                            .rotationEffect(.degrees(90))
                            .frame(width: g.size.width, height: g.size.height)
                            .clipped()
                    }
                    VirtualMeshView(player: player)
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
            ScanHudCard(hud: player.hud, notes: [player.status])
                .padding(.horizontal, 12)
                .padding(.top, 8)
                .frame(maxHeight: .infinity, alignment: .top)
        }
        .task { await player.run(folder: folder, startAt: startAt) }
    }

    /// Plays a recording through the live preview.
    @MainActor final class Player: ObservableObject {
        @Published var image: CGImage?
        @Published var hud = ScanHud()
        @Published var status = "Loading the recording…"
        /// The camera pose and intrinsics of the picture shown now (for the overlay's projection).
        var pose = matrix_identity_float4x4
        var k = matrix_identity_float3x3
        var imageSize = CGSize(width: 1920, height: 1440)

        let device = MTLCreateSystemDefaultDevice()
        lazy var mesh: LiveMesh? = device.map { LiveMesh(device: $0) }
        lazy var photos: LivePhotoStore? = device.flatMap { LivePhotoStore(device: $0) }
        private let worker = DispatchQueue(label: "CineX.virtual-scan")
        private var room: Float = 0

        func run(folder: URL, startAt: Double) async {
            let frames: [RecordingProcessor.FrameRecord], depth: RecordingProcessor.DepthFrames
            do {
                frames = try RecordingProcessor.readFrames(folder.appendingPathComponent("frames.bin"))
                depth = try RecordingProcessor.readDepth(folder.appendingPathComponent("depth.bin"))
            } catch { status = "Couldn't read the recording: \(error.localizedDescription)"; return }
            guard let first = frames.first, let mesh, let photos else { status = "Nothing to play"; return }
            mesh.photos = photos
            // The video read in order (seeking for every picture was far too slow).
            let video = VideoFrames(url: folder.appendingPathComponent("video.mov"))
            let fuser = Fuser(mesh: mesh, photos: photos)
            var di = 0, fi = 0
            var t = 0.0
            let end = (frames.last?.time ?? first.time) - first.time
            while !Task.isCancelled, t <= end {
                let now = first.time + t
                while fi + 1 < frames.count, frames[fi + 1].time <= now { fi += 1 }
                let f = frames[fi]
                let drawing = t >= startAt
                var picture: CGImage?
                let started = Date()
                if let frame = await video.frame(at: t), drawing || fuser.wantsPhoto(f) {
                    picture = VideoFrames.image(frame, width: LiveCloud.photoWidth)
                }
                // Depth frames up to now, fused as LiveCloud does; a photo when the phone has moved on (as LiveCloud).
                var batch: [RecordingProcessor.DepthFrame] = []
                while di < depth.count, depth[di].time <= now { batch.append(depth[di]); di += 1 }
                let videoTime = Date().timeIntervalSince(started)
                let shot = picture
                await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
                    worker.async {
                        for d in batch { fuser.fuse(d, frames: frames) }
                        if let shot { fuser.maybePhoto(f, picture: shot, depth: batch.last) }
                        if drawing { mesh.rebuildIfDue(camera: f.pose, time: now) }
                        done.resume()
                    }
                }
                if Int(t * 10) % 10 == 0 {
                    FileHandle.standardError.write(String(format: "virtual: t=%.1f video %.0f ms, step %.0f ms, %d photos, %d triangles\n", t, videoTime * 1000,
                        Date().timeIntervalSince(started) * 1000, fuser.photoCount, (mesh.buffers?.triangleIndexCount ?? 0) / 3).data(using: .utf8)!)
                }
                if drawing {
                    pose = f.pose; k = f.k; imageSize = CGSize(width: f.width, height: f.height)
                    if let picture { image = picture }
                    let filled = mesh.filledShare
                    room = max(room, filled.room)
                    let spot = fuser.spot
                    let (hint, level) = ScanHud.hint(tracking: f.tracking == 2 ? .normal : .limited(.initializing),
                                                     spot: min(spot, filled.inView), points: fuser.samples, tooFast: false)
                    hud = ScanHud(hint: hint, level: level, spot: min(spot, filled.inView), room: room)
                    status = "Virtual scan: \(folder.lastPathComponent), \(String(format: "%.0f", t)) s, \(fuser.photoCount) photos"
                    try? await Task.sleep(nanoseconds: 100_000_000)
                } else {
                    status = "Fusing the recording up to \(Int(startAt)) s… \(Int(t)) s"
                }
                t += 0.1
            }
        }
    }

    /// Worker-queue state: the depth samples into the TSDF and the photos into the store, as LiveCloud does live.
    final class Fuser: @unchecked Sendable {
        let mesh: LiveMesh, photos: LivePhotoStore
        var spot: Float = 0, samples = 0, photoCount = 0
        private var lastPhoto: simd_float4x4?
        private var nextID = 1

        init(mesh: LiveMesh, photos: LivePhotoStore) { self.mesh = mesh; self.photos = photos }

        func fuse(_ d: RecordingProcessor.DepthFrame, frames: [RecordingProcessor.FrameRecord]) {
            guard let f = frames.min(by: { abs($0.time - d.time) < abs($1.time - d.time) }), f.tracking == 2 else { return }
            let sx = Float(d.w) / Float(f.width), sy = Float(d.h) / Float(f.height)
            let fx = f.k[0][0] * sx, fy = f.k[1][1] * sy, cx = f.k[2][0] * sx, cy = f.k[2][1] * sy
            let origin = SIMD3(f.pose.columns.3.x, f.pose.columns.3.y, f.pose.columns.3.z)
            mesh.tsdf.beginFrame()
            // The GPU fusion when it's on (LiveMesh.useGPU), as the live scan does: every confident pixel.
            var gpuDepth: [Float]? = mesh.gpuReady(origin: origin) ? [Float](repeating: 0, count: d.w * d.h) : nil
            var good = 0, all = 0
            func depth(_ x: Int, _ y: Int) -> Float { Float(d.mm[y * d.w + x]) / 1000 }
            for v in stride(from: 1, to: d.h - 1, by: 4) {
                for u in stride(from: 1, to: d.w - 1, by: 4) {
                    all += 1
                    let z = depth(u, v)
                    guard z > 0.2, z < 4, d.conf.isEmpty || d.conf[v * d.w + u] >= 2 else { continue }
                    let tol = z * 0.04 + 0.02
                    if abs(depth(u - 1, v) - z) > tol || abs(depth(u + 1, v) - z) > tol || abs(depth(u, v - 1) - z) > tol || abs(depth(u, v + 1) - z) > tol { continue }
                    good += 1
                    samples += 1
                    if gpuDepth != nil {
                        for dy in 0..<4 { for dx in 0..<4 where v + dy < d.h && u + dx < d.w { gpuDepth![(v + dy) * d.w + u + dx] = z } }
                        continue
                    }
                    let p4 = f.pose * SIMD4((Float(u) + 0.5 - cx) * z / fx, (cy - (Float(v) + 0.5)) * z / fy, -z, 1)
                    mesh.tsdf.integrate(origin: origin, point: SIMD3(p4.x, p4.y, p4.z))
                }
            }
            if let gpuDepth { mesh.fuseGPU(depth: gpuDepth, width: d.w, height: d.h, pose: f.pose, fx: fx, fy: fy, cx: cx, cy: cy) }
            spot = all == 0 ? 0 : Float(good) / Float(all)
        }

        func wantsPhoto(_ f: RecordingProcessor.FrameRecord) -> Bool {
            guard f.tracking == 2 else { return false }
            guard let last = lastPhoto else { return true }
            let moved = simd_distance(SIMD3(last.columns.3.x, last.columns.3.y, last.columns.3.z), SIMD3(f.pose.columns.3.x, f.pose.columns.3.y, f.pose.columns.3.z))
            let a = simd_normalize(SIMD3(last.columns.2.x, last.columns.2.y, last.columns.2.z))
            let b = simd_normalize(SIMD3(f.pose.columns.2.x, f.pose.columns.2.y, f.pose.columns.2.z))
            return moved >= 0.2 || acos(max(-1, min(1, simd_dot(a, b)))) * 180 / .pi >= 15
        }

        func maybePhoto(_ f: RecordingProcessor.FrameRecord, picture: CGImage, depth d: RecordingProcessor.DepthFrame?) {
            guard wantsPhoto(f) else { return }
            lastPhoto = f.pose
            let scale = Float(picture.width) / Float(f.width)
            let data = NSMutableData()
            guard let dest = CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil) else { return }
            CGImageDestinationAddImage(dest, picture, [kCGImageDestinationLossyCompressionQuality: 0.8] as CFDictionary)
            guard CGImageDestinationFinalize(dest) else { return }
            var small: [UInt16]?, dw = 0, dh = 0
            if let d {
                dw = d.w / 2; dh = d.h / 2
                var s = [UInt16](repeating: 0, count: dw * dh)
                for y in 0..<dh { for x in 0..<dw {
                    let i = (y * 2) * d.w + x * 2
                    if d.conf.isEmpty || d.conf[i] >= 1 { s[y * dw + x] = d.mm[i] }
                } }
                small = s
            }
            let k = Keyframe(timestamp: f.time, cameraToWorld: f.pose, fx: f.k[0][0] * scale, fy: f.k[1][1] * scale,
                             cx: f.k[2][0] * scale, cy: f.k[2][1] * scale, width: picture.width, height: picture.height,
                             jpeg: data as Data, exposure: nil, depth: small, depthWidth: dw, depthHeight: dh, id: nextID)
            nextID += 1
            photoCount += 1
            photos.add(k)
        }
    }
}

/// A video's frames in order, as pixel buffers, for the virtual scan.
final class VideoFrames: @unchecked Sendable {
    private var output: AVAssetReaderTrackOutput?
    private var reader: AVAssetReader?
    private var current: (time: Double, buffer: CVPixelBuffer)?
    private var started = false
    private let url: URL
    private static let context = CIContext(options: [.cacheIntermediates: false])

    init(url: URL) { self.url = url }

    /// The last frame at or before `time` (seconds from the start); nil before the first.
    func frame(at time: Double) async -> CVPixelBuffer? {
        if !started {
            started = true
            let asset = AVURLAsset(url: url)
            guard let track = try? await asset.loadTracks(withMediaType: .video).first, let r = try? AVAssetReader(asset: asset) else { return nil }
            let o = AVAssetReaderTrackOutput(track: track, outputSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA])
            r.add(o)
            r.startReading()
            reader = r; output = o
        }
        guard let output else { return current?.buffer }
        while true {
            if pending == nil {
                guard let sample = output.copyNextSampleBuffer() else { break }
                guard let b = CMSampleBufferGetImageBuffer(sample) else { continue }
                pending = (CMSampleBufferGetPresentationTimeStamp(sample).seconds, b)
            }
            guard let p = pending, p.time <= time + 0.0001 else { break }
            current = p
            pending = nil
        }
        return current?.buffer
    }
    private var pending: (time: Double, buffer: CVPixelBuffer)?

    static func image(_ buffer: CVPixelBuffer, width: Int) -> CGImage? {
        let image = CIImage(cvPixelBuffer: buffer)
        let s = CGFloat(width) / image.extent.width
        let scaled = image.transformed(by: CGAffineTransform(scaleX: s, y: s))
        return context.createCGImage(scaled, from: scaled.extent)
    }
}

/// The live preview's Metal overlay for the virtual scan: the dimmed camera and LiveMeshRenderer, with the camera
/// projection worked out from the recording's intrinsics (portrait, aspect-filled like the picture under it).
struct VirtualMeshView: UIViewRepresentable {
    let player: VirtualScanScreen.Player

    func makeCoordinator() -> Coordinator { Coordinator(player: player) }

    func makeUIView(context: Context) -> MTKView {
        let view = MTKView(frame: .zero, device: player.device)
        view.isOpaque = false
        view.backgroundColor = .clear
        view.clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 0)
        view.colorPixelFormat = .bgra8Unorm
        view.depthStencilPixelFormat = .depth32Float
        view.preferredFramesPerSecond = 20
        view.delegate = context.coordinator
        return view
    }

    func updateUIView(_ uiView: MTKView, context: Context) {}

    @MainActor final class Coordinator: NSObject, MTKViewDelegate {
        let player: VirtualScanScreen.Player
        private let queue: MTLCommandQueue?
        private let renderer: LiveMeshRenderer?

        init(player: VirtualScanScreen.Player) {
            self.player = player
            queue = player.device?.makeCommandQueue()
            renderer = player.device.flatMap { d in player.mesh.flatMap { LiveMeshRenderer(device: d, mesh: $0) } }
        }

        nonisolated func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

        nonisolated func draw(in view: MTKView) {
            MainActor.assumeIsolated {
                guard let renderer, let queue, let pass = view.currentRenderPassDescriptor, let drawable = view.currentDrawable,
                      let commands = queue.makeCommandBuffer(), let encoder = commands.makeRenderCommandEncoder(descriptor: pass) else { return }
                if LivePreview.showCamera { renderer.encodeDim(encoder, alpha: 1 - LivePreview.dim) }
                let vp = Self.projection(k: player.k, image: player.imageSize, view: view.bounds.size) * player.pose.inverse
                _ = renderer.encode(encoder, viewProjection: vp)
                encoder.endEncoding()
                commands.present(drawable)
                commands.commit()
            }
        }

        /// Camera space (x right, y up, looking down -z, landscape sensor) → clip space of the portrait screen, the
        /// picture turned a quarter clockwise and aspect-filled (as VirtualScanScreen shows it).
        static func projection(k: simd_float3x3, image: CGSize, view: CGSize) -> simd_float4x4 {
            let fx = k[0][0], fy = k[1][1], cx = k[2][0], cy = k[2][1]
            let iw = Float(image.width), ih = Float(image.height), sw = Float(max(1, view.width)), sh = Float(max(1, view.height))
            let s = max(sw / ih, sh / iw)
            let ox = (sw - ih * s) / 2, oy = (sh - iw * s) / 2
            let n: Float = 0.05, f: Float = 50, zs = f / (n - f)
            // px·d = s·fy·y + (ox + s·(ih − cy))·d,  py·d = s·fx·x + (oy + s·cx)·d,  d = −z.
            let ax = 2 * (ox + s * (ih - cy)) / sw - 1, ay = 2 * (oy + s * cx) / sh - 1
            return simd_float4x4(rows: [
                SIMD4(0, 2 * s * fy / sw, -ax, 0),
                SIMD4(-2 * s * fx / sh, 0, ay, 0),
                SIMD4(0, 0, zs, zs * n),
                SIMD4(0, 0, -1, 0),
            ])
        }
    }
}
#endif
