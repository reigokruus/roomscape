import AVFoundation
import os
import simd

/// LiDAR depth and the ultrawide camera at once (AVCaptureMultiCamSession; the probe showed the 15 Pro supports the
/// pair). Depth comes with the wide camera's calibration (its frame is the one we track); the ultrawide frames are
/// for the screen and the photos, placed with the ultrawide-to-wide extrinsics.
///
/// Camera coordinates throughout: x right, y down, z forward (AVFoundation / OpenCV), in the sensor's own landscape
/// orientation.
final class UltraCapture: NSObject, AVCaptureDepthDataOutputDelegate, AVCaptureVideoDataOutputSampleBufferDelegate {
    struct DepthFrame {
        let depth: [Float]
        let width: Int, height: Int
        let fx: Float, fy: Float, cx: Float, cy: Float
        let time: TimeInterval
    }
    struct Intrinsics { var fx: Float, fy: Float, cx: Float, cy: Float, width: Int, height: Int }

    let session = AVCaptureMultiCamSession()
    private let depthQueue = DispatchQueue(label: "ultra.depth", qos: .userInitiated)
    private let videoQueue = DispatchQueue(label: "ultra.video", qos: .userInitiated)
    /// Called on the depth queue for every depth frame.
    var onDepth: ((DepthFrame) -> Void)?
    private let latest = OSAllocatedUnfairLock<(buffer: CVPixelBuffer, time: TimeInterval)?>(initialState: nil)
    /// The newest ultrawide picture (BGRA, landscape).
    var latestVideo: (buffer: CVPixelBuffer, time: TimeInterval)? { latest.withLock { $0 } }
    private let intrinsicsLock = OSAllocatedUnfairLock<Intrinsics?>(initialState: nil)
    var ultraIntrinsics: Intrinsics? { intrinsicsLock.withLock { $0 } }
    /// Ultrawide camera coordinates to the wide (depth) camera's, metres.
    private(set) var ultraToWide = matrix_identity_float4x4
    private var ultraFovDegrees: Float = 106
    private(set) var note = ""

    /// Sets the session up; nil when it worked, else why not.
    func configure() -> String? {
        guard AVCaptureMultiCamSession.isMultiCamSupported else { return "This iPhone can't run two cameras at once." }
        guard let lidar = AVCaptureDevice.default(.builtInLiDARDepthCamera, for: .video, position: .back) else {
            return "No LiDAR camera on this iPhone."
        }
        guard let ultra = AVCaptureDevice.default(.builtInUltraWideCamera, for: .video, position: .back) else {
            return "No ultrawide camera on this iPhone."
        }
        // LiDAR: a multi-camera format with depth, about 640 wide; Float32 depth, the largest.
        let lidarFormats = lidar.formats.filter { $0.isMultiCamSupported && !$0.supportedDepthDataFormats.isEmpty }
        guard let lf = lidarFormats.min(by: { abs(width($0) - 640) < abs(width($1) - 640) }) else {
            return "The LiDAR camera has no depth format for two cameras at once."
        }
        let depthFormats = lf.supportedDepthDataFormats.filter {
            CMFormatDescriptionGetMediaSubType($0.formatDescription) == kCVPixelFormatType_DepthFloat32
        }
        guard let df = depthFormats.max(by: { width($0) < width($1) }) ?? lf.supportedDepthDataFormats.first else {
            return "No depth format."
        }
        // Ultrawide: a 4:3 multi-camera format about 1280 wide.
        let uf = ultra.formats.filter { $0.isMultiCamSupported }
            .filter { let d = CMVideoFormatDescriptionGetDimensions($0.formatDescription); return abs(Float(d.width) / Float(d.height) - 4 / 3) < 0.05 }
            .min(by: { abs(width($0) - 1280) < abs(width($1) - 1280) })
        guard let uf else { return "The ultrawide camera has no format for two cameras at once." }
        do {
            try lidar.lockForConfiguration()
            lidar.activeFormat = lf
            lidar.activeDepthDataFormat = df
            lidar.unlockForConfiguration()
            try ultra.lockForConfiguration()
            ultra.activeFormat = uf
            ultra.unlockForConfiguration()
        } catch { return "Couldn't set the cameras up: \(error.localizedDescription)" }
        ultraFovDegrees = uf.videoFieldOfView

        session.beginConfiguration()
        defer { session.commitConfiguration() }
        guard let lidarIn = try? AVCaptureDeviceInput(device: lidar), let ultraIn = try? AVCaptureDeviceInput(device: ultra),
              session.canAddInput(lidarIn), session.canAddInput(ultraIn) else { return "Couldn't open the cameras." }
        session.addInputWithNoConnections(lidarIn)
        session.addInputWithNoConnections(ultraIn)

        let depthOut = AVCaptureDepthDataOutput()
        depthOut.isFilteringEnabled = true
        depthOut.alwaysDiscardsLateDepthData = true
        guard session.canAddOutput(depthOut) else { return "Can't add the depth output." }
        session.addOutputWithNoConnections(depthOut)
        guard let depthPort = lidarIn.ports(for: .depthData, sourceDeviceType: lidar.deviceType, sourceDevicePosition: .back).first else {
            return "The LiDAR camera has no depth port."
        }
        let depthConnection = AVCaptureConnection(inputPorts: [depthPort], output: depthOut)
        guard session.canAddConnection(depthConnection) else { return "Can't connect the depth stream." }
        session.addConnection(depthConnection)
        depthOut.setDelegate(self, callbackQueue: depthQueue)

        let videoOut = AVCaptureVideoDataOutput()
        videoOut.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        videoOut.alwaysDiscardsLateVideoFrames = true
        guard session.canAddOutput(videoOut) else { return "Can't add the ultrawide output." }
        session.addOutputWithNoConnections(videoOut)
        guard let videoPort = ultraIn.ports(for: .video, sourceDeviceType: ultra.deviceType, sourceDevicePosition: .back).first else {
            return "The ultrawide camera has no video port."
        }
        let videoConnection = AVCaptureConnection(inputPorts: [videoPort], output: videoOut)
        guard session.canAddConnection(videoConnection) else { return "Can't connect the ultrawide stream." }
        session.addConnection(videoConnection)
        if videoConnection.isCameraIntrinsicMatrixDeliverySupported { videoConnection.isCameraIntrinsicMatrixDeliveryEnabled = true }
        videoOut.setSampleBufferDelegate(self, queue: videoQueue)

        // Where the ultrawide sits relative to the wide camera (the depth's frame).
        let wide = lidar.constituentDevices.first { $0.deviceType == .builtInWideAngleCamera } ?? lidar
        if let data = AVCaptureDevice.extrinsicMatrix(from: ultra, to: wide), data.count >= MemoryLayout<matrix_float4x3>.size {
            let m = data.withUnsafeBytes { $0.loadUnaligned(as: matrix_float4x3.self) }
            var t = m.columns.3
            if simd_length(t) > 0.5 { t /= 1000 } // millimetres
            ultraToWide = simd_float4x4(SIMD4(m.columns.0, 0), SIMD4(m.columns.1, 0), SIMD4(m.columns.2, 0), SIMD4(t, 1))
            note = String(format: "ultrawide offset %.1f cm", simd_length(t) * 100)
        } else {
            note = "no extrinsics: ultrawide assumed at the wide camera"
        }
        if session.hardwareCost > 1 { return "The two cameras together are too much for this iPhone (cost \(session.hardwareCost))." }
        return nil
    }

    private func width(_ f: AVCaptureDevice.Format) -> Float { Float(CMVideoFormatDescriptionGetDimensions(f.formatDescription).width) }

    func start() { depthQueue.async { self.session.startRunning() } }
    func stop() { depthQueue.async { if self.session.isRunning { self.session.stopRunning() } } }

    // MARK: Delegates

    func depthDataOutput(_ output: AVCaptureDepthDataOutput, didOutput depthData: AVDepthData, timestamp: CMTime,
                         connection: AVCaptureConnection) {
        let d = depthData.depthDataType == kCVPixelFormatType_DepthFloat32 ? depthData : depthData.converting(toDepthDataType: kCVPixelFormatType_DepthFloat32)
        let map = d.depthDataMap
        let w = CVPixelBufferGetWidth(map), h = CVPixelBufferGetHeight(map)
        guard let cal = d.cameraCalibrationData else { return }
        let k = cal.intrinsicMatrix, ref = cal.intrinsicMatrixReferenceDimensions
        let s = Float(w) / Float(ref.width)
        CVPixelBufferLockBaseAddress(map, .readOnly)
        var depth = [Float](repeating: 0, count: w * h)
        if let base = CVPixelBufferGetBaseAddress(map) {
            let row = CVPixelBufferGetBytesPerRow(map)
            for y in 0..<h {
                let p = base.advanced(by: y * row).assumingMemoryBound(to: Float32.self)
                for x in 0..<w { depth[y * w + x] = p[x] }
            }
        }
        CVPixelBufferUnlockBaseAddress(map, .readOnly)
        onDepth?(DepthFrame(depth: depth, width: w, height: h, fx: k[0][0] * s, fy: k[1][1] * s, cx: k[2][0] * s, cy: k[2][1] * s,
                            time: timestamp.seconds))
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let w = CVPixelBufferGetWidth(buffer), h = CVPixelBufferGetHeight(buffer)
        if ultraIntrinsics == nil {
            var k: Intrinsics
            if let data = CMGetAttachment(sampleBuffer, key: kCMSampleBufferAttachmentKey_CameraIntrinsicMatrix, attachmentModeOut: nil) as? Data,
               data.count >= MemoryLayout<matrix_float3x3>.size {
                let m = data.withUnsafeBytes { $0.loadUnaligned(as: matrix_float3x3.self) }
                k = Intrinsics(fx: m[0][0], fy: m[1][1], cx: m[2][0], cy: m[2][1], width: w, height: h)
            } else {
                // From the format's field of view (horizontal, degrees).
                let f = Float(w) / 2 / tan(ultraFovDegrees * .pi / 360)
                k = Intrinsics(fx: f, fy: f, cx: Float(w) / 2, cy: Float(h) / 2, width: w, height: h)
            }
            intrinsicsLock.withLock { $0 = k }
        }
        latest.withLock { $0 = (buffer, CMSampleBufferGetPresentationTimeStamp(sampleBuffer).seconds) }
    }
}
