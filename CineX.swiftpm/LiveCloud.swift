import ARKit
import CoreImage
import Metal
import os
import simd

/// One captured 2 cm spot, laid out for the GPU (16 bytes, matches `LivePoint` in the disc shader).
struct LivePoint {
    var x: Float, y: Float, z: Float
    /// Bytes r, g, b = colour from the camera; a = how solid the spot is (1…255, 255 = solid).
    var rgba: UInt32
}

/// A photo taken during the scan, with what's needed to project the room into it (handoff: keyframes).
/// The photo keeps the camera sensor's landscape orientation, the same frame as `cameraToWorld` and the intrinsics.
struct Keyframe: Sendable {
    var timestamp: TimeInterval
    /// ARKit camera transform: x right, y up, looking down -z, metres.
    var cameraToWorld: simd_float4x4
    /// Intrinsics for the saved photo size, in pixels.
    var fx: Float, fy: Float, cx: Float, cy: Float
    var width: Int, height: Int
    var jpeg: Data
    /// How the camera exposed this photo, so pixel values can be turned back into how bright the scene really was
    /// (a daylit window is many times brighter than an LED-lit white wall, which auto-exposure hides).
    var exposure: PhotoExposure?
    /// LiDAR depth when the photo was taken, millimetres (0 = unknown), `depthWidth` × `depthHeight`, row 0 at the top,
    /// covering the same view as the photo. Lets the photo room tell whether a surface was hidden in this photo.
    var depth: [UInt16]? = nil
    var depthWidth = 0, depthHeight = 0
}

/// ARKit's exposure and light estimate for one photo.
struct PhotoExposure: Codable, Sendable {
    /// Seconds.
    var duration: TimeInterval
    /// Exposure bias ARKit applied, in EV.
    var offset: Float
    /// ARKit light estimate: ~1000 for a well-lit scene (lumens), and colour temperature in kelvin
    /// (daylight ~5500–6500 K, indoor LEDs ~2700–4000 K).
    var ambientIntensity: Float?
    var ambientKelvin: Float?
}

/// Everything captured alongside RoomPlan's model, copied out for saving.
struct CaptureSnapshot: Sendable {
    /// `pointCount` × `LivePoint` (16 bytes each).
    var points: Data
    var pointCount: Int
    var keyframes: [Keyframe]
    /// Cell size the points ended up at (2 cm, or coarser after a large room filled the budget).
    var voxelSize: Float
    /// The surface mesh fused from LiDAR depth while scanning (see `LiveMesh`), for the photo room.
    var mesh: ScanMesh? = nil
}

/// The live point cloud built from LiDAR depth while RoomPlan scans (handoff section 2: live capture + seen counter).
///
/// Every LiDAR depth pixel we trust lands in a 2 cm cell. The first hit adds a point; later hits average its position and
/// colour. Each cell also counts in how many depth frames it was seen (once per frame, because many pixels land in one cell),
/// and is "solid" after `solidFrames` frames.
///
/// Threading: `integrate` and `reset` run on one serial worker queue. The renderer reads `buffer` and `count` from the
/// main thread while the worker writes; a disc drawn from a half-updated point is harmless.
final class LiveCloud: @unchecked Sendable {
    /// Starting cell size. When a large room fills the point budget, the cloud is merged into 1.5× bigger cells
    /// and capture goes on at that size (see `coarsen`), so the whole room is covered at a little less detail.
    static let voxel: Float = 0.02
    /// Merge into bigger cells at this share of `maxPoints`, leaving room for the next frames.
    static let coarsenAt = 0.95
    /// We sample about 10 depth frames per second (5 when the phone is hot), so this is about 1 s of sightings.
    static let solidFrames = 10
    /// Point budget. Large rooms are handled by merging into coarser cells at 95% (see `coarsen`), so this doesn't
    /// need to hold a whole big room at 2 cm. Fixed memory: GPU buffer 19 MB, index ~25 MB (reserved up front, so
    /// it never doubles while growing), counters 6 MB.
    static let maxPoints = 1_200_000
    /// Photo budget. The handoff's 80 ran out in a large room; 160 JPEGs are about 16 MB.
    static let maxKeyframes = 160
    static let photoWidth = 1024

    let buffer: MTLBuffer
    private let points: UnsafeMutablePointer<LivePoint>
    /// Surface normal of each point (snorm8 x, y, z, 0; world space, facing the camera that first saw it), for the
    /// live mesh overlay only (not saved). Same index as `buffer`; zero = not known yet.
    let normalBuffer: MTLBuffer
    private let normals: UnsafeMutablePointer<SIMD4<Int8>>
    private let countLock = OSAllocatedUnfairLock(initialState: 0)
    /// Number of points in `buffer`. Safe to read from any thread.
    var count: Int { countLock.withLock { $0 } }
    private let voxelLock = OSAllocatedUnfairLock(initialState: LiveCloud.voxel)
    /// Current cell size in metres. Safe to read from any thread.
    var voxelSize: Float { voxelLock.withLock { $0 } }
    /// Worker-only copy of `voxelSize`.
    private var voxel = LiveCloud.voxel
    /// Photo spacing (metres moved, degrees turned); doubled each time the photo budget is thinned.
    private var photoMove: Float = 0.2, photoTurn: Float = 15

    // Worker-only state.
    private var index: [Int64: Int32] = [:]
    private var observations: [UInt8]
    private var seenStamp: [Int32]
    private var depthFrames: Int32 = 0
    private var lastPosition: SIMD3<Float>?
    private var lastTime: TimeInterval = 0
    private var pausedUntil: TimeInterval = 0
    /// Finished photos. Appended on `photoQueue`, read by `snapshot`.
    private let keyframes = OSAllocatedUnfairLock(initialState: [Keyframe]())
    /// Photos started (worker only), counted against `maxKeyframes` before they finish encoding.
    private var keyframesTaken = 0
    private var lastKeyframePose: simd_float4x4?
    /// Since when tracking has been normal without a break (worker only).
    private var trackingNormalSince: TimeInterval?
    /// Scaling and JPEG encoding happen here, at low priority, so they never compete with ARKit's tracking.
    /// Utility, not background: when the phone gets warm iOS throttles background work first, and every photo
    /// waiting here holds a 4 MB copy of the camera image.
    private let photoQueue = DispatchQueue(label: "CineX.keyframe-photos", qos: .utility)
    /// Photos copied but not yet encoded. New photos are skipped while two are waiting.
    private let pendingPhotos = OSAllocatedUnfairLock(initialState: 0)
    /// 1 normally, 2 in power saving: photos are taken twice as far apart (worker only).
    private var photoSpacing: Float = 1
    private lazy var imageContext = CIContext(options: [.cacheIntermediates: false, .priorityRequestLow: true])
    /// Smoothed share of solid spots where the phone points, 0…1, or -1 before there is enough to say.
    private(set) var detailHere: Float = -1
    /// True once the point budget is used up: new spots are no longer added.
    var isFull: Bool { count >= Self.maxPoints }

    init?(device: MTLDevice) {
        guard let buffer = device.makeBuffer(length: MemoryLayout<LivePoint>.stride * Self.maxPoints,
                                             options: .storageModeShared) else { return nil }
        guard let normalBuffer = device.makeBuffer(length: MemoryLayout<SIMD4<Int8>>.stride * Self.maxPoints,
                                                   options: .storageModeShared) else { return nil }
        self.buffer = buffer
        points = buffer.contents().bindMemory(to: LivePoint.self, capacity: Self.maxPoints)
        self.normalBuffer = normalBuffer
        normals = normalBuffer.contents().bindMemory(to: SIMD4<Int8>.self, capacity: Self.maxPoints)
        observations = [UInt8](repeating: 0, count: Self.maxPoints)
        seenStamp = [Int32](repeating: -1, count: Self.maxPoints)
        index.reserveCapacity(Self.maxPoints)
    }

    func reset() {
        countLock.withLock { $0 = 0 }
        voxel = Self.voxel
        voxelLock.withLock { $0 = Self.voxel }
        photoMove = 0.2
        photoTurn = 15
        index.removeAll(keepingCapacity: true)
        depthFrames = 0
        lastPosition = nil
        pausedUntil = 0
        detailHere = -1
        photoQueue.sync {}
        keyframes.withLock { $0.removeAll() }
        keyframesTaken = 0
        lastKeyframePose = nil
        trackingNormalSince = nil
    }

    /// Copies out the points and keyframes captured so far, after photos still being encoded. Call on the worker queue.
    func snapshot() -> CaptureSnapshot {
        photoQueue.sync {}
        let n = count
        return CaptureSnapshot(points: Data(bytes: points, count: n * MemoryLayout<LivePoint>.stride),
                               pointCount: n, keyframes: keyframes.withLock { $0 }, voxelSize: voxel)
    }

    // MARK: Integration

    private static let seenStep = UInt32(255 / solidFrames + 1)
    private static let firstSeen = UInt32(max(1, 255 / solidFrames))

    /// Adds one ARKit frame's LiDAR depth. Reads the frame's buffers and returns without keeping the frame.
    /// When `tsdf` is given, every fourth depth pixel each way (about one ray per 4 cm voxel at room distance) is also
    /// fused into it, for the live mesh.
    /// `covered` (Continue scan) says which spots the saved scan already has: those aren't fused into `tsdf`.
    func integrate(_ frame: ARFrame, tsdf: Tsdf? = nil, covered: ((SIMD3<Float>) -> Bool)? = nil) {
        let time = frame.timestamp
        guard case .normal = frame.camera.trackingState else {
            trackingNormalSince = nil
            return
        }
        if trackingNormalSince == nil { trackingNormalSince = time }
        guard let sceneDepth = frame.sceneDepth,
              let confidence = sceneDepth.confidenceMap else { return }

        // When ARKit corrects its map, the pose jumps. Capturing right then doubles walls, so pause for a moment.
        let pose = frame.camera.transform
        let position = SIMD3<Float>(pose.columns.3.x, pose.columns.3.y, pose.columns.3.z)
        if let lastPosition, simd_distance(lastPosition, position) > 0.15, time - lastTime < 0.25 {
            pausedUntil = time + 1.5
        }
        lastPosition = position
        lastTime = time
        if time < pausedUntil { return }
        depthFrames += 1

        let depth = sceneDepth.depthMap
        let image = frame.capturedImage
        CVPixelBufferLockBaseAddress(depth, .readOnly)
        CVPixelBufferLockBaseAddress(confidence, .readOnly)
        CVPixelBufferLockBaseAddress(image, .readOnly)
        defer {
            CVPixelBufferUnlockBaseAddress(image, .readOnly)
            CVPixelBufferUnlockBaseAddress(confidence, .readOnly)
            CVPixelBufferUnlockBaseAddress(depth, .readOnly)
        }
        guard CVPixelBufferGetPlaneCount(image) >= 2,
              let depthBase = CVPixelBufferGetBaseAddress(depth),
              let confBase = CVPixelBufferGetBaseAddress(confidence),
              let lumaBase = CVPixelBufferGetBaseAddressOfPlane(image, 0),
              let chromaBase = CVPixelBufferGetBaseAddressOfPlane(image, 1) else { return }

        let dw = CVPixelBufferGetWidth(depth), dh = CVPixelBufferGetHeight(depth)
        let dRow = CVPixelBufferGetBytesPerRow(depth) / MemoryLayout<Float32>.stride
        let dPtr = depthBase.assumingMemoryBound(to: Float32.self)
        let cRow = CVPixelBufferGetBytesPerRow(confidence)
        let cPtr = confBase.assumingMemoryBound(to: UInt8.self)
        let iw = CVPixelBufferGetWidthOfPlane(image, 0), ih = CVPixelBufferGetHeightOfPlane(image, 0)
        let yRow = CVPixelBufferGetBytesPerRowOfPlane(image, 0)
        let yPtr = lumaBase.assumingMemoryBound(to: UInt8.self)
        let uvRow = CVPixelBufferGetBytesPerRowOfPlane(image, 1)
        let uvPtr = chromaBase.assumingMemoryBound(to: UInt8.self)

        // The camera intrinsics are for the full-size image; the depth map covers the same view at a lower resolution.
        let k = frame.camera.intrinsics
        let sx = Float(dw) / Float(iw), sy = Float(dh) / Float(ih)
        let fx = k[0][0] * sx, fy = k[1][1] * sy, cx = k[2][0] * sx, cy = k[2][1] * sy
        let high = UInt8(ARConfidenceLevel.high.rawValue)

        var n = count
        var frameHits = 0, frameSolid = 0
        var fusedSamples = 0, newSamples = 0

        // Every second depth pixel: about one sample per 2 cm cell at room distances.
        for v in stride(from: 1, to: dh - 1, by: 2) {
            for u in stride(from: 1, to: dw - 1, by: 2) {
                guard cPtr[v * cRow + u] >= high else { continue }
                let d = dPtr[v * dRow + u]
                guard d > 0.2, d < 4 else { continue }
                // Flying pixels sit on depth edges: drop a pixel whose neighbour jumps by more than 4% + 2 cm.
                let tolerance = d * 0.04 + 0.02
                let dl = dPtr[v * dRow + u - 1], dr = dPtr[v * dRow + u + 1]
                let du = dPtr[(v - 1) * dRow + u], dd = dPtr[(v + 1) * dRow + u]
                if abs(dl - d) > tolerance || abs(dr - d) > tolerance || abs(du - d) > tolerance || abs(dd - d) > tolerance {
                    continue
                }

                // Camera space: x right, y up, looking down -z. Image v grows downwards.
                func cam(_ u: Int, _ v: Int, _ d: Float) -> SIMD3<Float> {
                    SIMD3((Float(u) + 0.5 - cx) * d / fx, (cy - (Float(v) + 0.5)) * d / fy, -d)
                }
                let pc = cam(u, v, d)
                let world = pose * SIMD4<Float>(pc, 1)
                if let tsdf, u & 3 == 1, v & 3 == 1 {
                    let point = SIMD3(world.x, world.y, world.z)
                    fusedSamples += 1
                    if covered?(point) != true {
                        newSamples += 1
                        tsdf.integrate(origin: position, point: point)
                    }
                }
                // Surface normal from the four neighbours, turned to face the camera, then into world space.
                var nc = simd_cross(cam(u + 1, v, dr) - cam(u - 1, v, dl), cam(u, v - 1, du) - cam(u, v + 1, dd))
                if simd_dot(nc, pc) > 0 { nc = -nc }
                let nl = simd_length(nc)
                let normal: SIMD3<Float>? = nl > 1e-9 ? simd_make_float3(pose * SIMD4<Float>(nc / nl, 0)) : nil

                // Colour from the camera image at the same spot (full-range YCbCr 4:2:0).
                let iu = min(iw - 1, Int((Float(u) + 0.5) / sx))
                let iv = min(ih - 1, Int((Float(v) + 0.5) / sy))
                let luma = Float(yPtr[iv * yRow + iu])
                let chroma = (iv / 2) * uvRow + (iu / 2) * 2
                let cb = Float(uvPtr[chroma]) - 128, cr = Float(uvPtr[chroma + 1]) - 128
                let r = clampByte(luma + 1.402 * cr)
                let g = clampByte(luma - 0.344136 * cb - 0.714136 * cr)
                let b = clampByte(luma + 1.772 * cb)

                let key = Self.key(world.x, world.y, world.z, voxel)
                if let hit = index[key] {
                    let i = Int(hit)
                    var alpha = points[i].rgba >> 24
                    if seenStamp[i] != depthFrames {
                        // Count each depth frame once.
                        seenStamp[i] = depthFrames
                        frameHits += 1
                        if alpha >= 255 - Self.seenStep + 1 { frameSolid += 1 }
                        alpha = min(255, alpha + Self.seenStep)
                    }
                    // A spot seen again: blend the new reading in, so repeated looks clean up a bad first one.
                    let seen = observations[i]
                    if seen < 255 { observations[i] = seen + 1 }
                    let w = 1 / Float(min(Int(seen), 20) + 1)
                    var p = points[i]
                    p.x += (world.x - p.x) * w
                    p.y += (world.y - p.y) * w
                    p.z += (world.z - p.z) * w
                    let old = p.rgba
                    let nr = blend(old & 0xFF, r, w), ng = blend((old >> 8) & 0xFF, g, w), nb = blend((old >> 16) & 0xFF, b, w)
                    p.rgba = nr | (ng << 8) | (nb << 16) | (alpha << 24)
                    points[i] = p
                    if let normal {
                        let old = Self.decode(normals[i])
                        if simd_length_squared(old) < 0.25 {
                            normals[i] = Self.encode(normal)
                        } else {
                            let new = simd_dot(old, normal) < 0 ? -normal : normal
                            normals[i] = Self.encode(old + (new - old) * max(w, 0.1))
                        }
                    }
                } else {
                    guard n < Self.maxPoints else { continue }
                    frameHits += 1
                    points[n] = LivePoint(x: world.x, y: world.y, z: world.z,
                                          rgba: r | (g << 8) | (b << 16) | (Self.firstSeen << 24))
                    normals[n] = normal.map(Self.encode) ?? .zero
                    observations[n] = 1
                    seenStamp[n] = depthFrames
                    index[key] = Int32(n)
                    n += 1
                }
            }
        }

        let total = n
        countLock.withLock { $0 = total }
        // Up to ~7 cm cells (2 → 3 → 4.5 → 6.75 cm); past that the cloud fills and the status line says so.
        if Double(total) >= Double(Self.maxPoints) * Self.coarsenAt, voxel < 0.06 { coarsen() }
        if frameHits > 200 {
            let share = Float(frameSolid) / Float(frameHits)
            detailHere = detailHere < 0 ? share : detailHere * 0.8 + share * 0.2
        }
        // Continue scan: a photo only when at least a quarter of the view is new (not the saved scan's blue areas).
        if covered != nil, fusedSamples > 0, newSamples * 4 < fusedSamples { return }
        captureKeyframeIfMoved(frame, pose: pose)
    }

    /// Keeps a photo each time the phone has moved 20 cm or turned 15° since the last one, up to `maxKeyframes`.
    ///
    /// Only the pixel copy happens here, so ARKit's camera frame is let go within a millisecond or two. Scaling and
    /// JPEG encoding run later on `photoQueue`.
    private func captureKeyframeIfMoved(_ frame: ARFrame, pose: simd_float4x4) {
        if keyframesTaken >= Self.maxKeyframes { thinPhotos() }
        // Don't pile up camera-image copies while the encoder is behind (memory).
        guard pendingPhotos.withLock({ $0 }) < 2 else { return }
        // Not while tracking is still settling: that's when extra work can make ARKit lose track.
        guard let since = trackingNormalSince, frame.timestamp - since >= 1 else { return }
        if let last = lastKeyframePose {
            let moved = simd_distance(SIMD3(last.columns.3.x, last.columns.3.y, last.columns.3.z),
                                      SIMD3(pose.columns.3.x, pose.columns.3.y, pose.columns.3.z))
            let lookA = simd_normalize(SIMD3(last.columns.2.x, last.columns.2.y, last.columns.2.z))
            let lookB = simd_normalize(SIMD3(pose.columns.2.x, pose.columns.2.y, pose.columns.2.z))
            let turnedDegrees = acos(max(-1, min(1, simd_dot(lookA, lookB)))) * 180 / .pi
            guard moved >= photoMove * photoSpacing || turnedDegrees >= photoTurn * photoSpacing else { return }
        }
        guard let pixels = Self.copy(frame.capturedImage) else { return }
        let depth = frame.sceneDepth.flatMap(Self.smallDepth)
        pendingPhotos.withLock { $0 += 1 }
        keyframesTaken += 1
        lastKeyframePose = pose
        let timestamp = frame.timestamp
        let k = frame.camera.intrinsics
        let exposure = PhotoExposure(duration: frame.camera.exposureDuration, offset: frame.camera.exposureOffset,
                                     ambientIntensity: frame.lightEstimate.map { Float($0.ambientIntensity) },
                                     ambientKelvin: frame.lightEstimate.map { Float($0.ambientColorTemperature) })

        photoQueue.async { [self] in
            defer { pendingPhotos.withLock { $0 -= 1 } }
            let iw = CVPixelBufferGetWidth(pixels), ih = CVPixelBufferGetHeight(pixels)
            let scale = Float(Self.photoWidth) / Float(iw)
            let scaled = CIImage(cvPixelBuffer: pixels)
                .transformed(by: CGAffineTransform(scaleX: CGFloat(scale), y: CGFloat(scale)))
            guard let sRGB = CGColorSpace(name: CGColorSpace.sRGB),
                  let jpeg = imageContext.jpegRepresentation(
                    of: scaled, colorSpace: sRGB,
                    options: [CIImageRepresentationOption(rawValue: kCGImageDestinationLossyCompressionQuality as String): 0.8])
            else { return }
            let keyframe = Keyframe(timestamp: timestamp, cameraToWorld: pose,
                                    fx: k[0][0] * scale, fy: k[1][1] * scale, cx: k[2][0] * scale, cy: k[2][1] * scale,
                                    width: Int((Float(iw) * scale).rounded()), height: Int((Float(ih) * scale).rounded()),
                                    jpeg: jpeg, exposure: exposure,
                                    depth: depth?.values, depthWidth: depth?.width ?? 0, depthHeight: depth?.height ?? 0)
            keyframes.withLock { $0.append(keyframe) }
        }
    }

    /// A copy of a camera image in a buffer we own, so ARKit gets its own buffer back straight away.
    /// The depth map at half size (128 × 96 from ARKit's 256 × 192), in millimetres; pixels ARKit isn't at least
    /// medium-confident about are 0.
    private static func smallDepth(_ sceneDepth: ARDepthData) -> (values: [UInt16], width: Int, height: Int)? {
        let depth = sceneDepth.depthMap
        guard let confidence = sceneDepth.confidenceMap else { return nil }
        CVPixelBufferLockBaseAddress(depth, .readOnly)
        CVPixelBufferLockBaseAddress(confidence, .readOnly)
        defer {
            CVPixelBufferUnlockBaseAddress(confidence, .readOnly)
            CVPixelBufferUnlockBaseAddress(depth, .readOnly)
        }
        guard let dBase = CVPixelBufferGetBaseAddress(depth), let cBase = CVPixelBufferGetBaseAddress(confidence) else { return nil }
        let dw = CVPixelBufferGetWidth(depth), dh = CVPixelBufferGetHeight(depth)
        let dRow = CVPixelBufferGetBytesPerRow(depth) / MemoryLayout<Float32>.stride
        let cRow = CVPixelBufferGetBytesPerRow(confidence)
        let d = dBase.assumingMemoryBound(to: Float32.self), c = cBase.assumingMemoryBound(to: UInt8.self)
        let w = dw / 2, h = dh / 2
        let medium = UInt8(ARConfidenceLevel.medium.rawValue)
        var values = [UInt16](repeating: 0, count: w * h)
        for y in 0..<h {
            for x in 0..<w {
                let u = x * 2, v = y * 2
                guard c[v * cRow + u] >= medium else { continue }
                let m = d[v * dRow + u] * 1000
                if m > 0, m < 65000 { values[y * w + x] = UInt16(m) }
            }
        }
        return (values, w, h)
    }

    private static func copy(_ source: CVPixelBuffer) -> CVPixelBuffer? {
        var copy: CVPixelBuffer?
        let attributes = [kCVPixelBufferIOSurfacePropertiesKey: [String: Any]()] as CFDictionary
        guard CVPixelBufferCreate(nil, CVPixelBufferGetWidth(source), CVPixelBufferGetHeight(source),
                                  CVPixelBufferGetPixelFormatType(source), attributes, &copy) == kCVReturnSuccess,
              let copy else { return nil }
        CVPixelBufferLockBaseAddress(source, .readOnly)
        CVPixelBufferLockBaseAddress(copy, [])
        defer {
            CVPixelBufferUnlockBaseAddress(copy, [])
            CVPixelBufferUnlockBaseAddress(source, .readOnly)
        }
        for plane in 0..<CVPixelBufferGetPlaneCount(source) {
            guard let from = CVPixelBufferGetBaseAddressOfPlane(source, plane),
                  let to = CVPixelBufferGetBaseAddressOfPlane(copy, plane) else { return nil }
            let fromRow = CVPixelBufferGetBytesPerRowOfPlane(source, plane)
            let toRow = CVPixelBufferGetBytesPerRowOfPlane(copy, plane)
            let rows = CVPixelBufferGetHeightOfPlane(source, plane)
            if fromRow == toRow {
                memcpy(to, from, fromRow * rows)
            } else {
                for row in 0..<rows {
                    memcpy(to + row * toRow, from + row * fromRow, min(fromRow, toRow))
                }
            }
        }
        // Keeps the colour information (YCbCr matrix, colour space) so the photo converts to RGB correctly.
        CVBufferPropagateAttachments(source, copy)
        return copy
    }

    // MARK: Large rooms

    /// Merges the cloud into cells 1.5× bigger and carries on at that size. Points that land in the same new cell
    /// are averaged, weighted by how often each was seen; the cell keeps the highest "solid" level. Compacts in
    /// place (a point only ever moves to a lower index), so the renderer may show one odd frame at worst.
    private func coarsen() {
        let n = count
        let newVoxel = voxel * 1.5
        let started = Date()
        index.removeAll(keepingCapacity: true)
        var weights = [Float](repeating: 0, count: n)
        var out = 0
        for i in 0..<n {
            let p = points[i]
            let w = Float(max(1, observations[i]))
            let key = Self.key(p.x, p.y, p.z, newVoxel)
            if let hit = index[key] {
                let k = Int(hit)
                var q = points[k]
                let wk = weights[k], total = wk + w
                q.x = (q.x * wk + p.x * w) / total
                q.y = (q.y * wk + p.y * w) / total
                q.z = (q.z * wk + p.z * w) / total
                let a = q.rgba, b = p.rgba
                func mix(_ shift: UInt32) -> UInt32 {
                    clampByte((Float((a >> shift) & 0xFF) * wk + Float((b >> shift) & 0xFF) * w) / total)
                }
                q.rgba = mix(0) | (mix(8) << 8) | (mix(16) << 16) | (max(a >> 24, b >> 24) << 24)
                points[k] = q
                let nk = Self.decode(normals[k]), ni = Self.decode(normals[i])
                normals[k] = Self.encode(nk * wk + (simd_dot(nk, ni) < 0 ? -ni : ni) * w)
                weights[k] = total
                observations[k] = UInt8(min(255, Int(observations[k]) + Int(observations[i])))
                seenStamp[k] = max(seenStamp[k], seenStamp[i])
            } else {
                points[out] = p
                normals[out] = normals[i]
                weights[out] = w
                observations[out] = observations[i]
                seenStamp[out] = seenStamp[i]
                index[key] = Int32(out)
                out += 1
            }
        }
        voxel = newVoxel
        voxelLock.withLock { $0 = newVoxel }
        let kept = out
        countLock.withLock { $0 = kept }
        print("LiveCloud: budget full, merged \(n) points into \(kept) at \(Int((newVoxel * 1000).rounded())) mm cells "
              + "in \(Int(Date().timeIntervalSince(started) * 1000)) ms")
    }

    // MARK: Pressure (call on the worker queue)

    /// When the phone is hot: photos twice as far apart (the caller also samples fewer frames and draws less).
    func setPowerSaving(_ on: Bool) {
        photoSpacing = on ? 2 : 1
    }

    /// iOS says memory is low: give back what we can without losing coverage — merge the points into coarser cells
    /// (roughly halves them) and keep every other photo.
    func shedMemory() {
        if count > 50_000, voxel < 0.06 { coarsen() }
        if keyframesTaken > 20 { thinPhotos() }
    }

    /// The photo budget is used up: keep every other photo and take new ones twice as far apart, so photos cover the
    /// whole room instead of the first part of it.
    private func thinPhotos() {
        photoQueue.sync {}
        let kept = keyframes.withLock { frames -> Int in
            frames = frames.enumerated().filter { $0.offset % 2 == 0 }.map(\.element)
            return frames.count
        }
        keyframesTaken = kept
        photoMove *= 2
        photoTurn *= 2
        print("LiveCloud: photo budget full, kept \(kept), now one photo per \(photoMove) m or \(photoTurn)°")
    }

    /// Unit vector ↔ snorm8 (zero stays zero).
    private static func encode(_ n: SIMD3<Float>) -> SIMD4<Int8> {
        let l = simd_length(n)
        guard l > 1e-6 else { return .zero }
        let q = (n / l * 127).rounded(.toNearestOrAwayFromZero)
        return SIMD4(Int8(q.x), Int8(q.y), Int8(q.z), 0)
    }

    private static func decode(_ n: SIMD4<Int8>) -> SIMD3<Float> {
        SIMD3(Float(n.x), Float(n.y), Float(n.z)) / 127
    }

    private static func key(_ x: Float, _ y: Float, _ z: Float, _ voxel: Float) -> Int64 {
        let ix = Int64(floor(x / voxel)) & 0x1F_FFFF
        let iy = Int64(floor(y / voxel)) & 0x1F_FFFF
        let iz = Int64(floor(z / voxel)) & 0x1F_FFFF
        return (ix << 42) | (iy << 21) | iz
    }
}

private func clampByte(_ value: Float) -> UInt32 {
    UInt32(max(0, min(255, value.rounded())))
}

private func blend(_ old: UInt32, _ new: UInt32, _ weight: Float) -> UInt32 {
    clampByte(Float(old) + (Float(new) - Float(old)) * weight)
}
