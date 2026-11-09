import simd

/// "Scan area again" (rk 9 Nov): the areas the user marked in the viewer, for the Continue scan that follows. In the
/// merge, the new pass's data replaces the old inside them, once it covers them well; elsewhere it merges as usual.
enum RescanTargets {
    /// Radius of a marked area, metres (a 0.8 m ball around the tapped spot).
    static let radius: Float = 0.4
    /// The areas for the scan that's starting or running (set by the viewer, cleared when the scan ends).
    nonisolated(unsafe) static var current: [SIMD3<Float>] = []

    static func inside(_ p: SIMD3<Float>, _ targets: [SIMD3<Float>] = current) -> Bool {
        targets.contains { simd_distance_squared($0, p) < radius * radius }
    }
}
