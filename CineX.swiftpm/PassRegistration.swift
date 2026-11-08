import Foundation
import simd

/// Lines up an Improve-scan pass with the saved scan by its LiDAR points.
///
/// The pass is scanned in a fresh RoomPlan session with its own origin (RoomPlan restarts tracking anyway, and on a
/// session holding the saved map it stopped with "space too big"). Where the phone was when the saved room was
/// recognised, against RoomPlan's first pose, gives a first guess (within ~20 cm on the phone); this search then finds
/// the turn about the vertical and the shift that put the most pass points onto the saved scan's points.
enum PassRegistration {
    struct Result {
        /// Maps the pass's coordinates onto the saved scan's.
        var transform: simd_float4x4
        /// Share of the pass's points that land within a 5 cm cell of a saved point.
        var overlap: Float
    }

    /// Below this overlap the pass isn't trusted to be lined up (a lined-up pass scored 0.8 on the phone; the best
    /// wrong fit found 0.37, the pass as captured 0.01).
    static let minOverlap: Float = 0.5

    static func align(pass: [SIMD3<Float>], saved: [SIMD3<Float>], guesses: [simd_float4x4]) -> Result {
        guard !pass.isEmpty, !saved.isEmpty else { return Result(transform: guesses.first ?? matrix_identity_float4x4, overlap: 0) }
        let grid = Grid(saved, cell: 0.05)
        func sample(_ n: Int) -> [SIMD3<Float>] {
            Swift.stride(from: 0, to: pass.count, by: max(1, pass.count / n)).map { pass[$0] }
        }
        let coarse = sample(800), fine = sample(2500)
        var best = Result(transform: matrix_identity_float4x4, overlap: -1)
        for guess in guesses {
            func move(_ pts: [SIMD3<Float>]) -> [SIMD3<Float>] {
                pts.map { p -> SIMD3<Float> in let q = guess * SIMD4(p, 1); return SIMD3(q.x, q.y, q.z) }
            }
            let movedCoarse = move(coarse), movedFine = move(fine)
            let centre = movedFine.reduce(SIMD3<Float>(), +) / Float(movedFine.count)
            // Coarse, then fine, around the guess: turn about the vertical through the pass's centre, then shift.
            // The guess is taken while the phone is held still, so the turn searched is small.
            var here = (yaw: Float(0), shift: SIMD3<Float>())
            var score = grid.overlap(movedCoarse, centre: centre, yaw: 0, shift: .zero)
            for (stage, (yawRange, yawStep, shiftRange, shiftStep, yRange)) in [(Float(10), Float(2), Float(0.6), Float(0.1), Float(0.15)),
                                                                                (2, 0.5, 0.12, 0.03, 0.06),
                                                                                (0.5, 0.125, 0.03, 0.01, 0.02)].enumerated() {
                let moved = stage == 0 ? movedCoarse : movedFine
                if stage == 1 { score = grid.overlap(moved, centre: centre, yaw: here.yaw * .pi / 180, shift: here.shift) }
                let around = here
                for yaw in Swift.stride(from: around.yaw - yawRange, through: around.yaw + yawRange, by: yawStep) {
                    for dx in Swift.stride(from: -shiftRange, through: shiftRange, by: shiftStep) {
                        for dz in Swift.stride(from: -shiftRange, through: shiftRange, by: shiftStep) {
                            for dy in Swift.stride(from: -yRange, through: yRange, by: yRange / 3) {
                                let shift = around.shift + SIMD3(dx, dy, dz)
                                let s = grid.overlap(moved, centre: centre, yaw: yaw * .pi / 180, shift: shift)
                                if s > score { score = s; here = (yaw, shift) }
                            }
                        }
                    }
                }
            }
            if score > best.overlap {
                // transform = T(centre + shift) · R(yaw) · T(-centre) · guess
                let r = simd_float4x4(simd_quatf(angle: here.yaw * .pi / 180, axis: SIMD3(0, 1, 0)))
                var to = matrix_identity_float4x4; to.columns.3 = SIMD4(centre + here.shift, 1)
                var from = matrix_identity_float4x4; from.columns.3 = SIMD4(-centre, 1)
                best = Result(transform: to * r * from * guess, overlap: score)
            }
        }
        return best
    }

    /// Which 5 cm cells hold a saved point: a flat bitmap over the saved scan's bounds (fast even in Debug builds).
    private struct Grid {
        let cell: Float
        let origin: SIMD3<Float>
        let nx: Int, ny: Int, nz: Int
        var bits: [UInt8]

        init(_ points: [SIMD3<Float>], cell: Float) {
            self.cell = cell
            var lo = SIMD3<Float>(repeating: .greatestFiniteMagnitude), hi = -lo
            for p in points { lo = simd_min(lo, p); hi = simd_max(hi, p) }
            origin = lo - cell
            let size = (hi - lo) / cell + 3
            nx = Int(size.x); ny = Int(size.y); nz = Int(size.z)
            bits = [UInt8](repeating: 0, count: nx * ny * nz)
            for p in points {
                let c = (p - origin) / cell
                bits[(Int(c.y) * nz + Int(c.z)) * nx + Int(c.x)] = 1
            }
        }

        func overlap(_ points: [SIMD3<Float>], centre: SIMD3<Float>, yaw: Float, shift: SIMD3<Float>) -> Float {
            let c = cos(yaw), s = sin(yaw)
            var hits = 0
            bits.withUnsafeBufferPointer { b in
                points.withUnsafeBufferPointer { pts in
                    for p in pts {
                        let d = p - centre
                        // Same turn as simd_quatf(angle: yaw, axis: +y).
                        let q = SIMD3(c * d.x + s * d.z, d.y, -s * d.x + c * d.z) + centre + shift - origin
                        let ix = Int((q.x / cell).rounded(.down)), iy = Int((q.y / cell).rounded(.down)), iz = Int((q.z / cell).rounded(.down))
                        if ix >= 0, iy >= 0, iz >= 0, ix < nx, iy < ny, iz < nz, b[(iy * nz + iz) * nx + ix] != 0 { hits += 1 }
                    }
                }
            }
            return Float(hits) / Float(points.count)
        }
    }
}
