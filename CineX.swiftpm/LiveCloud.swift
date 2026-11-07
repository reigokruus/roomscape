import ARKit
import Metal
import os
import simd

/// One captured 2 cm spot, laid out for the GPU (16 bytes, matches `LivePoint` in the disc shader).
struct LivePoint {
    var x: Float, y: Float, z: Float
    /// Bytes r, g, b = colour from the camera; a = how solid the spot is (1…255, 255 = solid).
    var rgba: UInt32
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
    static let voxel: Float = 0.02
    /// We sample about 15 depth frames per second, so this is about 1 s of sightings.
    static let solidFrames = 15
    static let maxPoints = 400_000

    let buffer: MTLBuffer
    private let points: UnsafeMutablePointer<LivePoint>
    private let countLock = OSAllocatedUnfairLock(initialState: 0)
    /// Number of points in `buffer`. Safe to read from any thread.
    var count: Int { countLock.withLock { $0 } }

    // Worker-only state.
    private var index: [Int64: Int32] = [:]
    private var observations: [UInt8]
    private var seenStamp: [Int32]
    private var depthFrames: Int32 = 0
    private var lastPosition: SIMD3<Float>?
    private var lastTime: TimeInterval = 0
    private var pausedUntil: TimeInterval = 0
    /// Smoothed share of solid spots where the phone points, 0…1, or -1 before there is enough to say.
    private(set) var detailHere: Float = -1

    init?(device: MTLDevice) {
        guard let buffer = device.makeBuffer(length: MemoryLayout<LivePoint>.stride * Self.maxPoints,
                                             options: .storageModeShared) else { return nil }
        self.buffer = buffer
        points = buffer.contents().bindMemory(to: LivePoint.self, capacity: Self.maxPoints)
        observations = [UInt8](repeating: 0, count: Self.maxPoints)
        seenStamp = [Int32](repeating: -1, count: Self.maxPoints)
        index.reserveCapacity(100_000)
    }

    func reset() {
        countLock.withLock { $0 = 0 }
        index.removeAll(keepingCapacity: true)
        depthFrames = 0
        lastPosition = nil
        pausedUntil = 0
        detailHere = -1
    }

    // MARK: Integration

    private static let seenStep = UInt32(255 / solidFrames + 1)
    private static let firstSeen = UInt32(max(1, 255 / solidFrames))

    /// Adds one ARKit frame's LiDAR depth. Reads the frame's buffers and returns without keeping the frame.
    func integrate(_ frame: ARFrame) {
        guard case .normal = frame.camera.trackingState,
              let sceneDepth = frame.sceneDepth,
              let confidence = sceneDepth.confidenceMap else { return }

        // When ARKit corrects its map, the pose jumps. Capturing right then doubles walls, so pause for a moment.
        let time = frame.timestamp
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

        // Every second depth pixel: about one sample per 2 cm cell at room distances.
        for v in stride(from: 1, to: dh - 1, by: 2) {
            for u in stride(from: 1, to: dw - 1, by: 2) {
                guard cPtr[v * cRow + u] >= high else { continue }
                let d = dPtr[v * dRow + u]
                guard d > 0.2, d < 4 else { continue }
                // Flying pixels sit on depth edges: drop a pixel whose neighbour jumps by more than 4% + 2 cm.
                let tolerance = d * 0.04 + 0.02
                if abs(dPtr[v * dRow + u - 1] - d) > tolerance || abs(dPtr[v * dRow + u + 1] - d) > tolerance
                    || abs(dPtr[(v - 1) * dRow + u] - d) > tolerance || abs(dPtr[(v + 1) * dRow + u] - d) > tolerance {
                    continue
                }

                // Camera space: x right, y up, looking down -z. Image v grows downwards.
                let xc = (Float(u) + 0.5 - cx) * d / fx
                let yc = (cy - (Float(v) + 0.5)) * d / fy
                let world = pose * SIMD4<Float>(xc, yc, -d, 1)

                // Colour from the camera image at the same spot (full-range YCbCr 4:2:0).
                let iu = min(iw - 1, Int((Float(u) + 0.5) / sx))
                let iv = min(ih - 1, Int((Float(v) + 0.5) / sy))
                let luma = Float(yPtr[iv * yRow + iu])
                let chroma = (iv / 2) * uvRow + (iu / 2) * 2
                let cb = Float(uvPtr[chroma]) - 128, cr = Float(uvPtr[chroma + 1]) - 128
                let r = clampByte(luma + 1.402 * cr)
                let g = clampByte(luma - 0.344136 * cb - 0.714136 * cr)
                let b = clampByte(luma + 1.772 * cb)

                let key = Self.key(world.x, world.y, world.z)
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
                } else {
                    guard n < Self.maxPoints else { continue }
                    frameHits += 1
                    points[n] = LivePoint(x: world.x, y: world.y, z: world.z,
                                          rgba: r | (g << 8) | (b << 16) | (Self.firstSeen << 24))
                    observations[n] = 1
                    seenStamp[n] = depthFrames
                    index[key] = Int32(n)
                    n += 1
                }
            }
        }

        let total = n
        countLock.withLock { $0 = total }
        if frameHits > 200 {
            let share = Float(frameSolid) / Float(frameHits)
            detailHere = detailHere < 0 ? share : detailHere * 0.8 + share * 0.2
        }
    }

    private static func key(_ x: Float, _ y: Float, _ z: Float) -> Int64 {
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
