import ARKit
import CoreImage
import Metal
import simd
import os

private let log = Logger(subsystem: "CineX", category: "livecloud")

/// One point as the GPU reads it: world position plus packed colour (r, g, b) and "how solid" (a, 0…255).
struct LivePointGPU {
    var x: Float, y: Float, z: Float
    var rgbs: UInt32
}

/// A saved point: world position (metres, ARKit world frame), colour and how many depth frames saw it (capped at 255).
struct SavedPoint {
    var x: Float, y: Float, z: Float
    var r: UInt8, g: UInt8, b: UInt8, seen: UInt8
}

/// A photo taken while scanning, with where the camera was and how it projects.
/// Image is in the sensor's landscape orientation. Projection: u = fx*xc/d + cx, v = cy - fy*yc/d, with
/// (xc, yc, -d) the point in camera space (camera space = inverse(pose) * world, y up, looking down -z).
struct Keyframe {
    let jpeg: Data
    let pose: simd_float4x4
    let fx: Float, fy: Float, cx: Float, cy: Float
    let width: Int, height: Int
    let timestamp: Double
}

struct CloudSnapshot {
    var points: [SavedPoint]
    var keyframes: [Keyframe]
}

/// Live point cloud built from LiDAR depth while RoomPlan scans.
///
/// Space is cut into 2 cm cells. The first depth hit in a cell adds a point; later hits average its position and
/// colour. Each cell counts how many depth frames have seen it, **once per frame** (many depth pixels land in one
/// cell, so counting pixels would make everything "solid" in one frame). Depth is integrated about 10 times a
/// second, so `solidFrames = 10` means a spot is solid after about one second of looking at it.
///
/// Integration runs on a private queue; the GPU buffer is written in place so the overlay can draw it at any time.
final class LiveCloud: @unchecked Sendable {
    static let voxel: Float = 0.02
    static let solidFrames: UInt16 = 10
    static let maxPoints = 500_000
    /// Seconds between integrated depth frames (LiDAR delivers 60 Hz; 10 Hz is plenty and keeps the CPU cool).
    static let integrateInterval: TimeInterval = 0.1
    static let maxKeyframes = 80
    static let keyframeWidth: CGFloat = 1024
    static let keyframeMove: Float = 0.20
    static let keyframeTurn: Float = 15 * .pi / 180

    struct Stats {
        var count = 0
        /// Share of the cells seen in the latest depth frame that are solid, smoothed. Negative until there is data.
        var detailHere: Float = -1
        var hasDepth = false
    }

    let buffer: MTLBuffer
    private let points: UnsafeMutablePointer<LivePointGPU>
    private let queue = DispatchQueue(label: "cinex.livecloud", qos: .userInitiated)
    private let shared = OSAllocatedUnfairLock(initialState: Shared())

    private struct Shared {
        var stats = Stats()
        var busy = false
    }

    // Owned by `queue`.
    private var index: [Int64: Int32] = [:]
    private var pos: [SIMD3<Float>] = []
    private var col: [SIMD3<Float>] = []
    private var seen: [UInt16] = []
    private var stamp: [UInt32] = []
    private var frameNo: UInt32 = 0
    private var detail: Float = -1
    private var keyframes: [Keyframe] = []
    private var lastKeyPose: simd_float4x4?
    private let ciContext = CIContext(options: [.useSoftwareRenderer: false])

    // Owned by the main thread (see `tick`).
    private var lastSubmit: TimeInterval = 0
    private var lastTimestamp: TimeInterval = 0
    private var lastCameraPosition: SIMD3<Float>?
    private var pausedUntil: TimeInterval = 0
    private var firstTick: TimeInterval = 0
    private var askedForDepth = false

    init?(device: MTLDevice) {
        guard let buffer = device.makeBuffer(length: MemoryLayout<LivePointGPU>.stride * Self.maxPoints,
                                             options: .storageModeShared) else { return nil }
        self.buffer = buffer
        points = buffer.contents().bindMemory(to: LivePointGPU.self, capacity: Self.maxPoints)
    }

    var stats: Stats { shared.withLock { $0.stats } }

    func reset() {
        queue.async { [self] in
            index.removeAll(keepingCapacity: true)
            pos.removeAll(keepingCapacity: true)
            col.removeAll(keepingCapacity: true)
            seen.removeAll(keepingCapacity: true)
            stamp.removeAll(keepingCapacity: true)
            frameNo = 0
            detail = -1
            keyframes.removeAll()
            lastKeyPose = nil
            shared.withLock { $0.stats = Stats() }
        }
        DispatchQueue.main.async { [self] in
            lastSubmit = 0; lastTimestamp = 0; lastCameraPosition = nil
            pausedUntil = 0; firstTick = 0; askedForDepth = false
        }
    }

    // MARK: Feeding (main thread)

    /// Called every display frame while scanning. Picks a frame to integrate about 10 times a second.
    @MainActor
    func tick(session: ARSession) {
        guard let frame = session.currentFrame else { return }
        let now = ProcessInfo.processInfo.systemUptime
        if firstTick == 0 { firstTick = now }

        // When ARKit corrects its map the pose jumps; capturing right after that doubles walls. Wait 1.5 s.
        let t = frame.camera.transform.columns.3
        let camPos = SIMD3<Float>(t.x, t.y, t.z)
        if let last = lastCameraPosition, simd_distance(last, camPos) > 0.15 {
            log.info("pose jump \(simd_distance(last, camPos)) m, pausing capture")
            pausedUntil = now + 1.5
        }
        lastCameraPosition = camPos
        guard case .normal = frame.camera.trackingState else {
            pausedUntil = max(pausedUntil, now + 0.5)
            return
        }
        guard now >= pausedUntil, now - lastSubmit >= Self.integrateInterval,
              frame.timestamp != lastTimestamp else { return }

        guard frame.sceneDepth != nil || frame.smoothedSceneDepth != nil else {
            if now - firstTick > 1 { requestDepth(session) }
            return
        }

        let canStart = shared.withLock { s -> Bool in
            if s.busy { return false }
            s.busy = true
            return true
        }
        guard canStart else { return }
        lastSubmit = now
        lastTimestamp = frame.timestamp
        queue.async { [self] in
            integrate(frame)
            shared.withLock { $0.busy = false }
        }
    }

    /// RoomPlan may run its AR session without LiDAR depth frames. Add them to the same configuration once.
    /// Running without reset options keeps tracking, anchors and RoomPlan's progress.
    @MainActor
    private func requestDepth(_ session: ARSession) {
        guard !askedForDepth else { return }
        askedForDepth = true
        guard let config = session.configuration as? ARWorldTrackingConfiguration else {
            log.error("no depth, and the session config is \(String(describing: session.configuration), privacy: .public)")
            return
        }
        guard ARWorldTrackingConfiguration.supportsFrameSemantics(.sceneDepth) else {
            log.error("no depth: this device doesn't support sceneDepth")
            return
        }
        log.info("RoomPlan session has no sceneDepth, adding it (semantics were \(config.frameSemantics.rawValue))")
        config.frameSemantics.insert(.sceneDepth)
        session.run(config)
    }

    // MARK: Integration (queue)

    private func integrate(_ frame: ARFrame) {
        guard let depth = frame.sceneDepth ?? frame.smoothedSceneDepth else { return }
        let depthMap = depth.depthMap
        let image = frame.capturedImage
        guard CVPixelBufferGetPlaneCount(image) >= 2 else { return }

        CVPixelBufferLockBaseAddress(depthMap, .readOnly)
        CVPixelBufferLockBaseAddress(image, .readOnly)
        if let conf = depth.confidenceMap { CVPixelBufferLockBaseAddress(conf, .readOnly) }
        defer {
            CVPixelBufferUnlockBaseAddress(depthMap, .readOnly)
            CVPixelBufferUnlockBaseAddress(image, .readOnly)
            if let conf = depth.confidenceMap { CVPixelBufferUnlockBaseAddress(conf, .readOnly) }
        }

        let dw = CVPixelBufferGetWidth(depthMap), dh = CVPixelBufferGetHeight(depthMap)
        guard let dBase = CVPixelBufferGetBaseAddress(depthMap) else { return }
        let dRow = CVPixelBufferGetBytesPerRow(depthMap) / MemoryLayout<Float32>.stride
        let dPtr = dBase.assumingMemoryBound(to: Float32.self)

        var cPtr: UnsafePointer<UInt8>?
        var cRow = 0
        if let conf = depth.confidenceMap, let base = CVPixelBufferGetBaseAddress(conf) {
            cPtr = UnsafePointer(base.assumingMemoryBound(to: UInt8.self))
            cRow = CVPixelBufferGetBytesPerRow(conf)
        }

        let iw = CVPixelBufferGetWidthOfPlane(image, 0), ih = CVPixelBufferGetHeightOfPlane(image, 0)
        guard let yBase = CVPixelBufferGetBaseAddressOfPlane(image, 0),
              let uvBase = CVPixelBufferGetBaseAddressOfPlane(image, 1) else { return }
        let yPtr = yBase.assumingMemoryBound(to: UInt8.self)
        let uvPtr = uvBase.assumingMemoryBound(to: UInt8.self)
        let yRow = CVPixelBufferGetBytesPerRowOfPlane(image, 0)
        let uvRow = CVPixelBufferGetBytesPerRowOfPlane(image, 1)

        // Intrinsics are for the captured image; the depth map covers the same view at a lower resolution.
        let k = frame.camera.intrinsics
        let fx = k[0][0], fy = k[1][1], cx = k[2][0], cy = k[2][1]
        let sx = Float(iw) / Float(dw), sy = Float(ih) / Float(dh)
        let pose = frame.camera.transform

        frameNo &+= 1
        var touched = 0, touchedSolid = 0
        let step = 2 // every other depth pixel: ~12k samples per frame, still denser than 2 cm at 1–3 m

        @inline(__always) func d(_ x: Int, _ y: Int) -> Float { dPtr[y * dRow + x] }

        for y in Swift.stride(from: 1, to: dh - 1, by: step) {
            for x in Swift.stride(from: 1, to: dw - 1, by: step) {
                let z = d(x, y)
                guard z >= 0.2, z <= 4 else { continue }
                if let cPtr, cPtr[y * cRow + x] < UInt8(ARConfidenceLevel.high.rawValue) { continue }
                // Flying pixels sit on depth edges: skip a pixel whose neighbour jumps by more than 4% + 2 cm.
                let limit = 0.04 * z + 0.02
                if abs(d(x - 1, y) - z) > limit || abs(d(x + 1, y) - z) > limit
                    || abs(d(x, y - 1) - z) > limit || abs(d(x, y + 1) - z) > limit { continue }

                let u = (Float(x) + 0.5) * sx, v = (Float(y) + 0.5) * sy
                let pc = SIMD4<Float>((u - cx) / fx * z, -(v - cy) / fy * z, -z, 1)
                let pw = pose * pc

                let ix = min(Int(u), iw - 1), iy = min(Int(v), ih - 1)
                let Y = Float(yPtr[iy * yRow + ix])
                let uvi = (iy / 2) * uvRow + (ix / 2) * 2
                let cb = Float(uvPtr[uvi]) - 128, cr = Float(uvPtr[uvi + 1]) - 128
                let rgb = simd_clamp(SIMD3<Float>(Y + 1.402 * cr,
                                                  Y - 0.344136 * cb - 0.714136 * cr,
                                                  Y + 1.772 * cb) / 255,
                                     SIMD3<Float>(repeating: 0), SIMD3<Float>(repeating: 1))

                switch add(SIMD3(pw.x, pw.y, pw.z), rgb) {
                case .newThisFrame(let solid):
                    touched += 1
                    if solid { touchedSolid += 1 }
                case .alreadyThisFrame, .full:
                    break
                }
            }
        }

        if touched > 50 {
            let here = Float(touchedSolid) / Float(touched)
            detail = detail < 0 ? here : detail * 0.7 + here * 0.3
        }
        let count = pos.count, detail = detail
        shared.withLock { $0.stats = Stats(count: count, detailHere: detail, hasDepth: true) }

        maybeKeyframe(frame)
    }

    /// Everything captured so far, for saving. Runs after any integration already queued.
    func snapshot() async -> CloudSnapshot {
        await withCheckedContinuation { done in
            queue.async { [self] in
                var out = [SavedPoint]()
                out.reserveCapacity(pos.count)
                for i in pos.indices {
                    let c = col[i] * 255
                    out.append(SavedPoint(x: pos[i].x, y: pos[i].y, z: pos[i].z,
                                          r: UInt8(c.x), g: UInt8(c.y), b: UInt8(c.z),
                                          seen: UInt8(min(seen[i], 255))))
                }
                done.resume(returning: CloudSnapshot(points: out, keyframes: keyframes))
            }
        }
    }

    // MARK: Keyframes (queue)

    /// Keeps a photo whenever the phone has moved 20 cm or turned 15 degrees since the last one, up to 80.
    private func maybeKeyframe(_ frame: ARFrame) {
        guard keyframes.count < Self.maxKeyframes else { return }
        let pose = frame.camera.transform
        if let last = lastKeyPose {
            let moved = simd_distance(SIMD3(last.columns.3.x, last.columns.3.y, last.columns.3.z),
                                      SIMD3(pose.columns.3.x, pose.columns.3.y, pose.columns.3.z))
            let relative = simd_quatf(Self.rotation(last).transpose * Self.rotation(pose))
            var turned = relative.angle
            if turned > .pi { turned = 2 * .pi - turned }
            guard moved >= Self.keyframeMove || turned >= Self.keyframeTurn else { return }
        }

        let buffer = frame.capturedImage
        let w = CGFloat(CVPixelBufferGetWidth(buffer)), h = CGFloat(CVPixelBufferGetHeight(buffer))
        let scale = Self.keyframeWidth / w
        let image = CIImage(cvPixelBuffer: buffer).transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        guard let srgb = CGColorSpace(name: CGColorSpace.sRGB),
              let jpeg = ciContext.jpegRepresentation(
                of: image, colorSpace: srgb,
                options: [CIImageRepresentationOption(rawValue: kCGImageDestinationLossyCompressionQuality as String): 0.8])
        else { return }

        let k = frame.camera.intrinsics
        let s = Float(scale)
        keyframes.append(Keyframe(jpeg: jpeg, pose: pose,
                                  fx: k[0][0] * s, fy: k[1][1] * s, cx: k[2][0] * s, cy: k[2][1] * s,
                                  width: Int((w * scale).rounded()), height: Int((h * scale).rounded()),
                                  timestamp: frame.timestamp))
        lastKeyPose = pose
    }

    private static func rotation(_ m: simd_float4x4) -> simd_float3x3 {
        simd_float3x3(SIMD3(m.columns.0.x, m.columns.0.y, m.columns.0.z),
                      SIMD3(m.columns.1.x, m.columns.1.y, m.columns.1.z),
                      SIMD3(m.columns.2.x, m.columns.2.y, m.columns.2.z))
    }

    private enum AddResult { case newThisFrame(solid: Bool), alreadyThisFrame, full }

    @inline(__always)
    private func add(_ p: SIMD3<Float>, _ c: SIMD3<Float>) -> AddResult {
        let cell = SIMD3<Int64>(Int64((p.x / Self.voxel).rounded(.down)),
                                Int64((p.y / Self.voxel).rounded(.down)),
                                Int64((p.z / Self.voxel).rounded(.down)))
        let mask: Int64 = 0x1F_FFFF
        let key = ((cell.x & mask) << 42) | ((cell.y & mask) << 21) | (cell.z & mask)

        if let found = index[key] {
            let i = Int(found)
            guard stamp[i] != frameNo else { return .alreadyThisFrame }
            stamp[i] = frameNo
            if seen[i] < UInt16.max { seen[i] += 1 }
            let n = Float(min(seen[i], 20))
            pos[i] += (p - pos[i]) / n
            col[i] += (c - col[i]) / n
            write(i)
            return .newThisFrame(solid: seen[i] >= Self.solidFrames)
        }
        guard pos.count < Self.maxPoints else { return .full }
        let i = pos.count
        index[key] = Int32(i)
        pos.append(p)
        col.append(c)
        seen.append(1)
        stamp.append(frameNo)
        write(i)
        return .newThisFrame(solid: false)
    }

    @inline(__always)
    private func write(_ i: Int) {
        let c = col[i] * 255
        let s = min(Float(seen[i]) / Float(Self.solidFrames), 1) * 255
        let packed = UInt32(c.x) | UInt32(c.y) << 8 | UInt32(c.z) << 16 | UInt32(s) << 24
        let p = pos[i]
        points[i] = LivePointGPU(x: p.x, y: p.y, z: p.z, rgbs: packed)
    }
}
