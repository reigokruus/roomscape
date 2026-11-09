import AVFoundation
import CoreImage
import CoreMotion
import Metal
import MetalKit
import SceneKit
import os
import simd

/// Runs the experimental ultrawide scan: capture, tracking, fusing into the live mesh (the app's own `LiveMesh` TSDF,
/// used as is), photos from the ultrawide with their poses, the top card, and saving as an ordinary scan.
@MainActor
final class UltraScanner: ObservableObject {
    enum Phase: Equatable { case scanning, saving, failed(String) }

    @Published var phase: Phase = .scanning
    @Published var hud = ScanHud()
    @Published var notes: [String] = []
    private(set) var metalView: UltraDisplayView?

    private let capture = UltraCapture()
    private let tracker = UltraTracker()
    private let motion = CMMotionManager()
    private let worker = DispatchQueue(label: "ultra.worker", qos: .userInitiated)
    private let photoQueue = DispatchQueue(label: "ultra.photos", qos: .utility)
    private let mesh: LiveMesh?
    private let state = OSAllocatedUnfairLock(initialState: Shared())
    private var hudTimer: Timer?
    private var running = false

    /// What the worker and the screen share.
    struct Shared {
        var pose = matrix_identity_float4x4
        var tracking = false
        var lostFrames = 0
        var frames = 0
        var spot: Float = 0
        var room: Float = 0
        var keyframes: [Keyframe] = []
        var pendingPhotos = 0
    }


    init() {
        let device = MTLCreateSystemDefaultDevice()
        mesh = device.map { LiveMesh(device: $0) }
        if let device, let mesh { metalView = UltraDisplayView(device: device, mesh: mesh) }
    }

    func start() {
        guard !running else { return }
        running = true
        if let error = capture.configure() { phase = .failed(error); return }
        metalView?.capture = capture
        metalView?.poseSource = { [state] in state.withLock { $0.pose } }
        motion.deviceMotionUpdateInterval = 1 / 100
        motion.startDeviceMotionUpdates(using: .xArbitraryZVertical)
        let tracker = tracker, motion = motion, worker = worker
        var busy = false
        capture.onDepth = { [weak self] frame in
            guard !busy else { return }
            busy = true
            worker.async {
                self?.process(frame, tracker: tracker, motion: motion)
                busy = false
            }
        }
        capture.start()
        notes = capture.note.isEmpty ? [] : ["Experimental: \(capture.note)"]
        hudTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.updateHud() }
        }
    }

    func cancel() {
        hudTimer?.invalidate()
        motion.stopDeviceMotionUpdates()
        capture.onDepth = nil
        capture.stop()
        running = false
    }

    // MARK: Worker

    /// Device (CoreMotion: x right, y up, z out of the screen, portrait) to camera (x right, y down, z forward in the
    /// sensor's landscape orientation): x = -device y, y = -device x, z = -device z.
    nonisolated private static let deviceToCamera = simd_float3x3(rows: [SIMD3(0, -1, 0), SIMD3(-1, 0, 0), SIMD3(0, 0, -1)])

    nonisolated private func process(_ f: UltraCapture.DepthFrame, tracker: UltraTracker, motion: CMMotionManager) {
        let attitude = motion.deviceMotion?.attitude.copy() as? CMAttitude
        if !tracker.started || state.withLock({ $0.frames == 0 }) {
            guard let g = motion.deviceMotion?.gravity else { return }
            let gc = Self.deviceToCamera * SIMD3(Float(g.x), Float(g.y), Float(g.z))
            tracker.begin(gravity: gc)
        }
        var gyro: simd_float3x3?
        if let attitude, let last = lastAttitudeShared() {
            let rel = attitude.copy() as! CMAttitude
            rel.multiply(byInverseOf: last) // previous-to-current in the reference frame
            let m = rel.rotationMatrix
            // CMAttitude's matrix maps reference to device; the turn between frames, current device to previous.
            let r = simd_float3x3(rows: [SIMD3(Float(m.m11), Float(m.m12), Float(m.m13)),
                                         SIMD3(Float(m.m21), Float(m.m22), Float(m.m23)),
                                         SIMD3(Float(m.m31), Float(m.m32), Float(m.m33))])
            gyro = Self.deviceToCamera * r.transpose * Self.deviceToCamera.transpose
        }
        setLastAttitude(attitude)
        let result = tracker.track(f, gyro: gyro)
        guard result.ok else {
            state.withLock { $0.lostFrames += 1; $0.tracking = false; $0.frames += 1 }
            return
        }
        let pose = tracker.pose
        // Fuse into the TSDF (the same one the live mesh is drawn from) and keep coverage counts for the card.
        let origin = SIMD3(pose.columns.3.x, pose.columns.3.y, pose.columns.3.z)
        let r = simd_float3x3(SIMD3(pose.columns.0.x, pose.columns.0.y, pose.columns.0.z),
                              SIMD3(pose.columns.1.x, pose.columns.1.y, pose.columns.1.z),
                              SIMD3(pose.columns.2.x, pose.columns.2.y, pose.columns.2.z))
        let pts = UltraTracker.points(f, step: 2)
        let (spot, room) = fuse(pts.map { r * $0 + origin }, origin: origin)
        mesh?.rebuildIfDue(camera: pose, time: f.time)
        state.withLock { s in
            s.pose = pose; s.tracking = true; s.lostFrames = 0; s.frames += 1
            s.spot = s.spot * 0.7 + spot * 0.3
            s.room = max(s.room, room)
        }
        maybeKeyframe(pose)
    }

    nonisolated(unsafe) private var _lastAttitude: CMAttitude?
    nonisolated private func lastAttitudeShared() -> CMAttitude? { _lastAttitude }
    nonisolated private func setLastAttitude(_ a: CMAttitude?) { _lastAttitude = a }
    nonisolated(unsafe) private var _cells: [Int64: (seen: UInt16, stamp: Int32)] = [:]
    nonisolated(unsafe) private var _frame: Int32 = 0
    nonisolated(unsafe) private var _solid = 0
    nonisolated(unsafe) private var _lastKeyPose: simd_float4x4?

    /// Fuses the points; returns the share of this frame's 8 cm cells seen in at least 8 frames (This spot) and of all
    /// cells so far (Room).
    nonisolated private func fuse(_ world: [SIMD3<Float>], origin: SIMD3<Float>) -> (Float, Float) {
        _frame += 1
        mesh?.tsdf.beginFrame()
        var solidHere = 0, here = 0
        for p in world {
            mesh?.tsdf.integrate(origin: origin, point: p)
            let k = Int64(Int32((p.x / 0.08).rounded(.down))) & 0x1FFFFF
                | ((Int64(Int32((p.y / 0.08).rounded(.down))) & 0x1FFFFF) << 21)
                | ((Int64(Int32((p.z / 0.08).rounded(.down))) & 0x1FFFFF) << 42)
            var c = _cells[k] ?? (0, -1)
            if c.stamp != _frame {
                c.stamp = _frame
                if c.seen < 1000 { c.seen += 1 }
                if c.seen == 8 { _solid += 1 }
                here += 1
                if c.seen >= 8 { solidHere += 1 }
                _cells[k] = c
            }
        }
        return (here > 0 ? Float(solidHere) / Float(here) : 0, _cells.isEmpty ? 0 : Float(_solid) / Float(_cells.count))
    }

    /// A photo from the ultrawide every 25 cm or 15° (up to 150), with where the ultrawide was.
    nonisolated private func maybeKeyframe(_ pose: simd_float4x4) {
        if let last = _lastKeyPose {
            let moved = simd_distance(SIMD3(pose.columns.3.x, pose.columns.3.y, pose.columns.3.z), SIMD3(last.columns.3.x, last.columns.3.y, last.columns.3.z))
            let turned = acos(min(1, max(-1, simd_dot(SIMD3(pose.columns.2.x, pose.columns.2.y, pose.columns.2.z),
                                                     SIMD3(last.columns.2.x, last.columns.2.y, last.columns.2.z))))) * 180 / .pi
            guard moved > 0.25 || turned > 15 else { return }
        }
        let busy = state.withLock { $0.pendingPhotos >= 2 || $0.keyframes.count >= 150 }
        guard !busy, let video = capture.latestVideo, let k = capture.ultraIntrinsics else { return }
        _lastKeyPose = pose
        state.withLock { $0.pendingPhotos += 1 }
        let ultraPose = pose * capture.ultraToWide
        // ARKit's camera axes (y up, looking down -z), which the photo room expects.
        let arPose = ultraPose * simd_float4x4(diagonal: SIMD4(1, -1, -1, 1))
        let buffer = video.buffer, time = video.time
        photoQueue.async { [state] in
            defer { state.withLock { $0.pendingPhotos -= 1 } }
            let image = CIImage(cvPixelBuffer: buffer)
            let scale = 1024 / Float(k.width)
            let small = image.transformed(by: CGAffineTransform(scaleX: CGFloat(scale), y: CGFloat(scale)))
            guard let jpeg = UltraScanner.ciContext.jpegRepresentation(of: small, colorSpace: CGColorSpace(name: CGColorSpace.sRGB)!,
                                                                       options: [kCGImageDestinationLossyCompressionQuality as CIImageRepresentationOption: 0.8]) else { return }
            let w = Int((Float(k.width) * scale).rounded()), h = Int((Float(k.height) * scale).rounded())
            let frame = Keyframe(timestamp: time, cameraToWorld: arPose, fx: k.fx * scale, fy: k.fy * scale,
                                 cx: k.cx * scale, cy: k.cy * scale, width: w, height: h, jpeg: jpeg, exposure: nil)
            state.withLock { $0.keyframes.append(frame) }
        }
    }

    nonisolated static let ciContext = CIContext(options: [.cacheIntermediates: false])

    // MARK: Card

    private func updateHud() {
        let s = state.withLock { $0 }
        var hint: String, level: Int
        if s.frames < 5 {
            hint = "Point at the room and move the phone slowly"; level = 1
        } else if !s.tracking && s.lostFrames > 3 {
            hint = "Lost track: move slower and point back at what you scanned"; level = 2
        } else if s.spot < 0.35 {
            hint = "Sway the phone slowly over this spot until the mesh covers it in white"; level = 2
        } else if s.spot < ScanHud.spotDone {
            hint = "Good. Keep swaying gently here a little longer"; level = 1
        } else {
            hint = "This spot is done. Move on to the next orange area"; level = 0
        }
        hud = ScanHud(hint: hint, level: level, spot: s.spot, room: s.room)
        notes = ["Experimental ultrawide scan · \(s.keyframes.count) photos"]
    }

    // MARK: Saving

    /// Saves as an ordinary scan: points (the mesh's corners, coloured from the photos), photos with poses, the
    /// mesh, an empty RoomPlan model, and a note of how it was made. The floor is moved to y = 0.
    func finish(onSaved: @escaping () -> Void) {
        guard phase == .scanning else { return }
        phase = .saving
        hudTimer?.invalidate()
        capture.onDepth = nil
        capture.stop()
        motion.stopDeviceMotionUpdates()
        let mesh = mesh, state = state
        worker.async {
            // Wait for photos still being encoded.
            for _ in 0..<50 where state.withLock({ $0.pendingPhotos > 0 }) { usleep(50_000) }
            let keyframes = state.withLock { $0.keyframes }
            let result = UltraScanner.save(mesh: mesh?.wholeMesh(), keyframes: keyframes)
            DispatchQueue.main.async {
                switch result {
                case .success: onSaved()
                case .failure(let e): self.phase = .failed("Couldn't save the scan: \(e.localizedDescription)")
                }
            }
        }
    }

    nonisolated private static func save(mesh: ScanMesh?, keyframes: [Keyframe]) -> Result<Void, Error> {
        do {
            guard var mesh, !mesh.positions.isEmpty else { throw NSError(domain: "Ultra", code: 1, userInfo: [NSLocalizedDescriptionKey: "nothing was scanned"]) }
            // Floor at y = 0: the 2nd percentile of the surface's heights.
            let ys = mesh.positions.map(\.y).sorted()
            let floor = ys[ys.count / 50]
            mesh = ScanMesh(positions: mesh.positions.map { $0 - SIMD3(0, floor, 0) }, triangles: mesh.triangles)
            let frames = keyframes.map { k -> Keyframe in
                var k = k
                k.cameraToWorld.columns.3.y -= floor
                return k
            }
            let colours = colour(mesh.positions, frames)
            var points: [LivePoint] = []
            points.reserveCapacity(mesh.positions.count)
            for (i, p) in mesh.positions.enumerated() {
                let c = colours[i]
                points.append(LivePoint(x: p.x, y: p.y, z: p.z, rgba: UInt32(c.x) | (UInt32(c.y) << 8) | (UInt32(c.z) << 16) | (255 << 24)))
            }
            let snapshot = CaptureSnapshot(points: points.withUnsafeBytes { Data($0) }, pointCount: points.count, keyframes: frames,
                                           voxelSize: 0.04, mesh: mesh)
            let name = ScanStore.newScanName()
            let modelURL = ScanStore.directory.appendingPathComponent(name + ".usdz")
            let scan = SavedScan(name: name, modelURL: modelURL, dataURL: ScanStore.directory.appendingPathComponent(name + ".json"), date: Date())
            try CaptureStore.write(snapshot, to: scan.captureURL)
            // The scan list goes by the .usdz files: an empty model (no RoomPlan here).
            _ = SCNScene().write(to: modelURL, options: nil, delegate: nil, progressHandler: nil)
            let manifest: [String: Any] = ["format": "rmsc", "version": 1,
                                           "created": ISO8601DateFormatter().string(from: Date()),
                                           "source": ["platform": "ios", "app": "RoomScape", "device": "iPhone · experimental ultrawide"]]
            try JSONSerialization.data(withJSONObject: manifest).write(to: scan.captureURL.appendingPathComponent("source-manifest.json"))
            return .success(())
        } catch {
            return .failure(error)
        }
    }

    /// Each point's colour from the first photo that sees it (decoded small), grey where none does.
    nonisolated private static func colour(_ positions: [SIMD3<Float>], _ frames: [Keyframe]) -> [SIMD3<UInt8>] {
        var out = [SIMD3<UInt8>](repeating: SIMD3(150, 150, 150), count: positions.count)
        var done = [Bool](repeating: false, count: positions.count)
        for f in frames {
            guard let source = CGImageSourceCreateWithData(f.jpeg as CFData, nil),
                  let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                                                          kCGImageSourceThumbnailMaxPixelSize: 320] as CFDictionary) else { continue }
            let w = cg.width, h = cg.height
            var px = [UInt8](repeating: 0, count: w * h * 4)
            guard let ctx = CGContext(data: &px, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { continue }
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
            let s = Float(w) / Float(f.width)
            let inv = f.cameraToWorld.inverse
            for i in positions.indices where !done[i] {
                let c = inv * SIMD4(positions[i], 1) // ARKit camera: looking down -z, y up
                guard c.z < -0.1 else { continue }
                let u = (f.fx * c.x / -c.z + f.cx) * s, v = (f.fy * -c.y / -c.z + f.cy) * s
                guard u >= 0, v >= 0, u < Float(w), v < Float(h) else { continue }
                let o = (Int(v) * w + Int(u)) * 4
                out[i] = SIMD3(px[o], px[o + 1], px[o + 2])
                done[i] = true
            }
        }
        return out
    }
}
