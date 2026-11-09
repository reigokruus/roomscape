import ImageIO
import Metal
import os
import simd
import UIKit

/// "What you get is what you see" (rk 9 Nov): the live scan shows the result as it will be.
///
/// - The camera picture is dimmed (`dim`), so what isn't scanned yet stays dark.
/// - New surface is orange (tentative), white wireframe once it's solid (LiveMesh).
/// - Solid triangles are then filled with the photo the Photo view will use for them, at full brightness: the same
///   photos (the scan's keyframes, at the photo room's texture size) and the same choice per triangle
///   (`PhotoLayout.best`, shared with `PhotoLayout.choose`). The final result can only add to this (more photos,
///   a rebuild from the recording), so holes, blur and low resolution show up while you can still fix them.
enum LivePreview {
    /// Show the real camera picture while scanning (dimmed, and projected in the wide overview)? rk 9 Nov: off — the
    /// scan screen shows only what was captured (the mesh, white and photo-filled) on the zebra background, so what
    /// you see is all you get.
    static let showCamera = false
    /// The camera picture's brightness under the preview, 0…1 (when it's shown).
    static let dim: Float = 0.38
    /// Tentative surface (seen a few times) and solid surface, as RGBA.
    static let tentative = SIMD4<Float>(1.0, 0.55, 0.15, 0.40)
    static let tentativeEdge = SIMD4<Float>(1.0, 0.45, 0.1, 0.85)
    static let solid = SIMD4<Float>(0.92, 0.94, 1.0, 0.42)
    static let solidEdge = SIMD4<Float>(1.0, 1.0, 1.0, 0.75)
    /// A triangle is confirmed (white, and gets a photo) at this vertex confidence (LiveMesh: 1 = solid).
    static let confirmed: Float = 0.6
    /// Photo textures: as many as the photo room uses, at its texture width (PhotoRoom).
    static let slots = PhotoRoom.maxPhotos
    static let textureWidth = PhotoRoom.textureWidth, textureHeight = PhotoRoom.textureWidth * 3 / 4
}

/// The live scan's photos as one GPU texture array (`LivePreview.slots` slices), with what's needed to project the
/// room into each. Photos arrive from LiveCloud's photo queue; the mesh assigns them on the worker queue.
final class LivePhotoStore: @unchecked Sendable {
    let texture: MTLTexture
    private let device: MTLDevice
    private var uploads: MTLCommandQueue?
    /// Per slot: world → photo (u·d, v·d, d) with u, v in 0…1 (row 0 at the top), for the fragment shader.
    let projections: MTLBuffer

    struct Entry { var id: Int; var slot: Int; var photo: PhotoLayout.Photo }
    private let state = OSAllocatedUnfairLock(initialState: State())
    private struct State {
        var entries: [Entry] = []
        var uses: [Int: Int] = [:] // photo id -> triangles using it in the last assignment
        var version = 0
    }

    init?(device: MTLDevice) {
        let d = MTLTextureDescriptor()
        d.textureType = .type2DArray
        d.pixelFormat = .rgba8Unorm
        d.width = LivePreview.textureWidth
        d.height = LivePreview.textureHeight
        d.arrayLength = LivePreview.slots
        d.usage = .shaderRead
        #if targetEnvironment(simulator)
        d.storageMode = .private // the Simulator has no shared textures: photos go up through a blit (VirtualScan)
        uploads = device.makeCommandQueue()
        #else
        d.storageMode = .shared
        #endif
        self.device = device
        guard let texture = device.makeTexture(descriptor: d),
              let projections = device.makeBuffer(length: LivePreview.slots * MemoryLayout<simd_float4x4>.stride, options: .storageModeShared)
        else { return nil }
        self.texture = texture
        self.projections = projections
    }

    /// The photos with their slot, and a number that changes whenever they change.
    var current: (entries: [Entry], version: Int) { state.withLock { ($0.entries, $0.version) } }

    /// Photo queue: a new photo. Decoded at the photo room's texture size from the same JPEG the scan saves. When
    /// every slot is taken, the photo the fewest triangles use makes room (the photo room keeps the most used too).
    func add(_ k: Keyframe) {
        guard let image = Self.decode(k.jpeg) else { return }
        let w = LivePreview.textureWidth, h = LivePreview.textureHeight
        var pixels = [UInt8](repeating: 0, count: w * h * 4)
        guard let ctx = CGContext(data: &pixels, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                  space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return }
        ctx.interpolationQuality = .medium
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h)) // the bitmap's first row is the image's top
        let photo = PhotoLayout.Photo(file: "", view: k.cameraToWorld.inverse,
                                      position: SIMD3(k.cameraToWorld.columns.3.x, k.cameraToWorld.columns.3.y, k.cameraToWorld.columns.3.z),
                                      fx: k.fx, fy: k.fy, cx: k.cx, cy: k.cy, width: Float(k.width), height: Float(k.height),
                                      depth: k.depth, dw: k.depthWidth, dh: k.depthHeight)
        state.withLock { s in
            var slot = (0..<LivePreview.slots).first { sl in !s.entries.contains { $0.slot == sl } }
            if slot == nil, let least = s.entries.min(by: { (s.uses[$0.id] ?? 0) < (s.uses[$1.id] ?? 0) }) {
                slot = least.slot
                s.entries.removeAll { $0.id == least.id }
            }
            guard let slot else { return }
            if let uploads, let staging = device.makeBuffer(bytes: pixels, length: pixels.count, options: .storageModeShared),
               let commands = uploads.makeCommandBuffer(), let blit = commands.makeBlitCommandEncoder() {
                blit.copy(from: staging, sourceOffset: 0, sourceBytesPerRow: w * 4, sourceBytesPerImage: w * h * 4,
                          sourceSize: MTLSize(width: w, height: h, depth: 1), to: texture, destinationSlice: slot,
                          destinationLevel: 0, destinationOrigin: MTLOrigin(x: 0, y: 0, z: 0))
                blit.endEncoding()
                commands.commit()
            } else {
                pixels.withUnsafeBytes { raw in
                    texture.replace(region: MTLRegionMake2D(0, 0, w, h), mipmapLevel: 0, slice: slot,
                                    withBytes: raw.baseAddress!, bytesPerRow: w * 4, bytesPerImage: w * h * 4)
                }
            }
            projections.contents().bindMemory(to: simd_float4x4.self, capacity: LivePreview.slots)[slot] = Self.projection(photo)
            s.entries.append(Entry(id: k.id, slot: slot, photo: photo))
            s.version += 1
        }
    }

    /// Only these photos are left (the scan thinned its photos, or started again).
    func keep(_ ids: Set<Int>) {
        state.withLock { s in
            s.entries.removeAll { !ids.contains($0.id) }
            s.version += 1
        }
    }

    func setUses(_ uses: [Int: Int]) { state.withLock { $0.uses = uses } }

    /// World → (u·d, v·d, d), u and v in 0…1 with row 0 at the top (PhotoLayout.project / width, height).
    static func projection(_ p: PhotoLayout.Photo) -> simd_float4x4 {
        // Camera space: x right, y up, looking down -z; d = -z.
        let k = simd_float4x4(rows: [
            SIMD4(p.fx / p.width, 0, -p.cx / p.width, 0),
            SIMD4(0, -p.fy / p.height, -p.cy / p.height, 0),
            SIMD4(0, 0, -1, 0),
            SIMD4(0, 0, 0, 1),
        ])
        return k * p.view
    }

    private static func decode(_ jpeg: Data) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(jpeg as CFData, nil) else { return nil }
        let options: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                        kCGImageSourceThumbnailMaxPixelSize: LivePreview.textureWidth,
                                        kCGImageSourceShouldCacheImmediately: true]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }
}

/// Worker queue: which photo slot fills each triangle of the live mesh (-1 = none yet), kept per spot so it's only
/// worked out again for new triangles and new photos.
final class LivePhotoAssigner {
    private struct Choice { var id: Int; var score: Float; var checked: Int }
    /// Triangle centre (1 cm cells) → the best photo so far and the newest photo id already tried.
    private var cache: [SIMD3<Int32>: Choice] = [:]
    private var lastIDs = Set<Int>()

    /// Share of confirmed surface that has a photo: in view (a 30° cone ahead, within 3.5 m) and in the whole room.
    private(set) var filledInView: Float = 0
    private(set) var filledRoom: Float = 0

    func reset() { cache.removeAll(); lastIDs.removeAll(); filledInView = 0; filledRoom = 0 }

    /// Forget the spots near these points (tap to clear).
    func forget(near points: [SIMD3<Float>], radius: Float) {
        guard !points.isEmpty else { return }
        let r2 = radius * radius
        cache = cache.filter { key, _ in
            let p = SIMD3<Float>(key) * 0.01
            return !points.contains { simd_distance_squared($0, p) < r2 }
        }
    }

    func assign(positions: [SIMD3<Float>], confidence: [Float], triangles: [UInt32], store: LivePhotoStore,
                camera: simd_float4x4, into out: UnsafeMutablePointer<Int32>) {
        let (entries, _) = store.current
        let photos = entries.map(\.photo)
        let ids = entries.map(\.id)
        let slotOf = Dictionary(uniqueKeysWithValues: entries.map { ($0.id, $0.slot) })
        let indexOf = Dictionary(uniqueKeysWithValues: entries.enumerated().map { ($1.id, $0) })
        let newest = ids.max() ?? 0
        let camPos = SIMD3(camera.columns.3.x, camera.columns.3.y, camera.columns.3.z)
        let camFwd = -simd_normalize(SIMD3(camera.columns.2.x, camera.columns.2.y, camera.columns.2.z))
        var uses: [Int: Int] = [:]
        var inView = 0, inViewFilled = 0
        let all = Array(entries.indices)
        for t in 0..<(triangles.count / 3) {
            let i0 = Int(triangles[t * 3]), i1 = Int(triangles[t * 3 + 1]), i2 = Int(triangles[t * 3 + 2])
            out[t] = -1
            guard min(confidence[i0], confidence[i1], confidence[i2]) >= LivePreview.confirmed else { continue }
            let a = positions[i0], b = positions[i1], c = positions[i2]
            let centre = (a + b + c) / 3
            let key = SIMD3<Int32>((centre * 100).rounded(.down))
            var choice = cache[key] ?? Choice(id: -1, score: 0, checked: 0)
            if choice.id >= 0, indexOf[choice.id] == nil { choice = Choice(id: -1, score: 0, checked: 0) } // its photo went
            if choice.checked < newest {
                let fresh = choice.checked == 0 ? all : all.filter { ids[$0] > choice.checked }
                let (k, score) = PhotoLayout.best(a, b, c, photos, among: fresh, beat: choice.score)
                if k >= 0 { choice.id = ids[k]; choice.score = score }
                choice.checked = newest
                cache[key] = choice
            }
            let toward = centre - camPos
            let dist = simd_length(toward)
            let seen = dist < 3.5 && dist > 0.1 && simd_dot(toward / dist, camFwd) > 0.866
            if seen { inView += 1 }
            if choice.id >= 0, let slot = slotOf[choice.id] {
                out[t] = Int32(slot)
                uses[choice.id, default: 0] += 1
                if seen { inViewFilled += 1 }
            }
        }
        store.setUses(uses)
        filledInView = inView == 0 ? 0 : Float(inViewFilled) / Float(inView)
        let filled = cache.values.filter { $0.id >= 0 }.count
        filledRoom = cache.isEmpty ? 0 : Float(filled) / Float(cache.count)
    }
}
