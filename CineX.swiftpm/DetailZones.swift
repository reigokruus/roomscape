import simd

/// Close-up detail (rk 9 Nov: "keep adding detail when I'm intentionally moving closer to a subject"): where the phone
/// looked at something from close by (the middle of the view under `closeDepth`) for a while, a cube around that spot
/// is fused again from finer voxels (`voxel`), from the close-up depth only, and its fine surface replaces the coarse
/// one inside the cube. Everywhere else the room stays at the base voxel size, so memory stays flat.
///
/// The LiDAR's dots are ~4 cm apart at 1 m and ~2 cm at 0.5 m (the depth map in between is filled in from the
/// camera), so ~1.5 cm voxels from within ~1 m is about the finest that's real: 3 cm bars become rods.
enum DetailZones {
    /// The middle of the view nearer than this (metres) counts as close up.
    static let closeDepth: Float = 1.0
    /// Close-up depth used in a zone: nearer than this (metres).
    static let maxDepth: Float = 1.3
    /// Cube edge (metres) and fine voxel size.
    static let size: Float = 1.2
    static let voxel: Float = 0.015
    /// Close-up frames needed before a spot becomes a zone (about a second at 10 Hz).
    static let minFrames = 8
    /// At most this many zones in a scan (each ~3.3 MB on the GPU at 1.5 cm; rk scans close up a lot: 48 spots in 9 min).
    static let maxZones = 24

    struct Zone: Equatable { var centre: SIMD3<Float>; var frames: Int }

    /// The spot the phone looks at close up in this frame (the median depth of the middle of the view, along the view
    /// axis), or nil when the middle is further than `closeDepth` or mostly unknown.
    static func focus(depth: [Float], width: Int, height: Int, pose: simd_float4x4, fx: Float, fy: Float, cx: Float, cy: Float) -> SIMD3<Float>? {
        var near: [Float] = []
        let x0 = width * 3 / 8, x1 = width * 5 / 8, y0 = height * 3 / 8, y1 = height * 5 / 8
        for y in stride(from: y0, to: y1, by: 2) { for x in stride(from: x0, to: x1, by: 2) {
            let z = depth[y * width + x]
            if z > 0.15 { near.append(z) }
        } }
        guard near.count * 3 >= ((x1 - x0) / 2) * ((y1 - y0) / 2) else { return nil }
        near.sort()
        let z = near[near.count / 2]
        guard z < closeDepth else { return nil }
        let p = pose * SIMD4((Float(width) / 2 - cx) * z / fx, -(Float(height) / 2 - cy) * z / fy, -z, 1)
        return SIMD3(p.x, p.y, p.z)
    }

    /// Adds a close-up sighting: to the zone it falls in (within half a cube of its centre), or a new one.
    static func add(_ p: SIMD3<Float>, to zones: inout [Zone]) {
        if let i = zones.firstIndex(where: { max(abs($0.centre.x - p.x), abs($0.centre.y - p.y), abs($0.centre.z - p.z)) < size * 0.35 }) {
            zones[i].frames += 1
        } else if zones.count < maxZones * 4 {
            zones.append(Zone(centre: p, frames: 1))
        }
    }

    /// The zones seen long enough, most-seen first, at most `maxZones`.
    static func settled(_ zones: [Zone]) -> [Zone] {
        Array(zones.filter { $0.frames >= minFrames }.sorted { $0.frames > $1.frames }.prefix(maxZones))
    }

    static func contains(_ zone: Zone, _ p: SIMD3<Float>, margin: Float = 0) -> Bool {
        let h = size / 2 - margin
        return abs(p.x - zone.centre.x) < h && abs(p.y - zone.centre.y) < h && abs(p.z - zone.centre.z) < h
    }
}
