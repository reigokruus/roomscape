import Foundation
import simd

/// The camera can't teleport: a pose that is metres or tens of degrees away from the last good one a moment later means
/// ARKit's world frame changed under the scan (12 Nov 2026, 22:37 scan: when RoomPlan hit its size limit and ARKit was
/// run again, every later pose came out 3.4 m and 80° off and drew a second copy of the room). Pure logic, tested in
/// PoseJumpTests; the scanner (LiveDiscs) applies the correction with ARSession.setWorldOrigin.
public enum PoseJump {
    /// A walking camera covers at most about 1 m/s and turns at most about 120°/s, plus a margin for a short gap.
    public static func isJump(from a: simd_float4x4, to b: simd_float4x4, seconds dt: Double) -> Bool {
        let shift = simd_distance(SIMD3(a.columns.3.x, a.columns.3.y, a.columns.3.z), SIMD3(b.columns.3.x, b.columns.3.y, b.columns.3.z))
        let d = a.inverse * b
        let angle = acos(max(-1, min(1, (d.columns.0.x + d.columns.1.y + d.columns.2.z - 1) / 2))) * 180 / .pi
        return Double(shift) > 0.5 + 1.0 * dt || Double(angle) > 25 + 120 * dt
    }

    /// The correction that puts pose `b` (after the jump) back onto `a` (the last good pose): a turn about the vertical
    /// and a shift (ARKit keeps gravity, so only the heading and position can change). Apply as
    /// `session.setWorldOrigin(relativeTransform: correction.inverse)`: later poses then come out as `correction * raw`.
    public static func correction(from b: simd_float4x4, to a: simd_float4x4) -> simd_float4x4 {
        func heading(_ m: simd_float4x4) -> Float { atan2(-m.columns.2.x, -m.columns.2.z) }   // camera looks down -z
        let yaw = heading(a) - heading(b)
        var t = simd_float4x4(simd_quatf(angle: yaw, axis: SIMD3(0, 1, 0)))
        let pb = t * SIMD4(b.columns.3.x, b.columns.3.y, b.columns.3.z, 1)
        t.columns.3 = SIMD4(a.columns.3.x - pb.x, a.columns.3.y - pb.y, a.columns.3.z - pb.z, 1)
        return t
    }
}
