import simd

/// A finer surface for the rebuild from the recording (test switch, rk 10 Nov: the 3 cm climbing bars came out as a
/// flat slab at 4 cm voxels). The whole room is fused at `voxel` (1.9 cm) in one GPU block; the mesh is extracted in
/// tiles so the CPU only ever holds one tile's voxels, and each tile is simplified (`MeshSimplify`, `maxError`):
/// flat walls go back to few triangles, bars and edges keep theirs.
/// Harness (20:33 recording): the bars appear as rods; 624k triangles → 191k at 8 mm; against the 4 cm surface,
/// 1.1% of it missing and 2.3% added, nothing far off.
enum FineSurface {
    static let voxel: Float = 0.019
    static var maxError: Float = 0.008
    /// Tile edge in voxels (~1.8 m).
    static let tile: Int32 = 96
    /// The biggest block allowed (voxels): ~240 MB on the GPU.
    static let maxVoxels = 40_000_000

    /// The surface of `gpu`, tile by tile. Each tile copies one voxel more on every side so its surface meets the
    /// next tile's, and keeps only triangles whose centre lies in its own part.
    static func extract(_ gpu: TsdfGPU, solidWeight: Float = 20, cancelled: () -> Bool = { false },
                        progress: (Float) -> Void = { _ in }) -> ScanMesh {
        var positions: [SIMD3<Float>] = [], triangles: [UInt32] = []
        let dims = gpu.dims
        let tiles = (dims &+ (tile - 1)) / tile
        let count = Int(tiles.x * tiles.y * tiles.z)
        let scratch = Tsdf.MeshScratch()
        var done = 0
        for tz in 0..<tiles.z { for ty in 0..<tiles.y { for tx in 0..<tiles.x {
            if cancelled() { return ScanMesh(positions: positions, triangles: triangles) }
            let lo = SIMD3(tx, ty, tz) &* tile, hi = lo &+ tile
            let t = Tsdf(capacity: Int(tile + 4) * Int(tile + 4) * Int(tile + 4), voxel: gpu.vs, truncation: gpu.trunc, carve: gpu.carve)
            gpu.copy(into: t, gridLo: lo &- 2, gridHi: hi &+ 2)
            _ = t.extractMesh(near: .zero, radius: 10_000, solidWeight: solidWeight, into: scratch)
            // Keep the triangles whose centre is in this tile's own part (world → block voxel coordinates).
            let coreLo = SIMD3<Float>(gpu.origin &+ lo) * gpu.vs, coreHi = SIMD3<Float>(gpu.origin &+ hi) * gpu.vs
            var keep: [UInt32] = []
            keep.reserveCapacity(scratch.triangles.count)
            for i in stride(from: 0, to: scratch.triangles.count - 2, by: 3) {
                let c = (scratch.positions[Int(scratch.triangles[i])] + scratch.positions[Int(scratch.triangles[i + 1])]
                         + scratch.positions[Int(scratch.triangles[i + 2])]) / 3
                if all(c .>= coreLo), all(c .< coreHi) { keep += scratch.triangles[i..<(i + 3)] }
            }
            if !keep.isEmpty {
                let simple = MeshSimplify.simplify(positions: scratch.positions, triangles: keep, maxError: maxError)
                let base = UInt32(positions.count)
                positions += simple.positions
                triangles += simple.triangles.map { $0 + base }
            }
            done += 1
            progress(Float(done) / Float(count))
        } } }
        return ScanMesh(positions: positions, triangles: triangles)
    }
}
