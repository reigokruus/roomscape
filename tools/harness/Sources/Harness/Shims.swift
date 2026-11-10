import SceneKit
import AppKit
import simd

// Small stand-ins for the iOS-only types the app's shared files mention, so they compile on the Mac unchanged.

typealias UIColor = NSColor

/// The experimental scanner's depth frame type (the real one lives in UltraCapture.swift, which needs iOS cameras).
enum UltraCapture {
    struct DepthFrame {
        let depth: [Float]
        let width: Int, height: Int
        let fx: Float, fy: Float, cx: Float, cy: Float
        let time: TimeInterval
    }
}

/// Just what HoleFill.hideBox reads.
struct RoomObject {
    var center: SIMD3<Float>
    var halfExtents: SIMD3<Float>
    var yaw: Float
}

/// The app's hardware-use log (PerfLog.swift); the harness doesn't log.
final class PerfLog: @unchecked Sendable {
    nonisolated(unsafe) static var processing: PerfLog?
    func note(_ name: String, ms: Double) {}
}

/// The app's Lighting preview shader (LightingScene.swift): no lighting in the harness.
enum LightingShader { static func makeLit(_ m: SCNMaterial) {} }

/// The app's saved photo record (ScanCapture.swift) and exposure (LiveCloud.swift), for PhotoRoomLayout.
struct PhotoExposure: Codable, Sendable { var duration: TimeInterval; var offset: Float }
struct KeyframeRecord: Codable {
    var file: String
    var timestamp: TimeInterval
    var width: Int, height: Int
    var fx: Float, fy: Float, cx: Float, cy: Float
    var cameraToWorld: [Float]
    var exposure: PhotoExposure?
    var depthFile: String? = nil
    var depthWidth: Int? = nil, depthHeight: Int? = nil
}
