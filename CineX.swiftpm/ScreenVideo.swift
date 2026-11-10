#if os(iOS)
import AVFoundation
import ReplayKit
import VideoToolbox
import os

/// What the screen showed while scanning (rk 10 Nov, as Android's screen.mp4): the app's own screen, captured with
/// ReplayKit's in-app capture (iOS asks once: "Allow screen recording in RoomScape?"), at 480 px wide and 8 frames a
/// second, H.264, no sound. Saved in the scan's folder as `screen.mp4`; it stays on the phone (never in a .rmsc or a
/// sync). Test switch "Screen video (test)", off by default.
final class ScreenVideo: @unchecked Sendable {
    nonisolated(unsafe) static var enabled: Bool {
        get { UserDefaults.standard.bool(forKey: "screenVideo") || ProcessInfo.processInfo.arguments.contains("-screenVideo") }
        set { UserDefaults.standard.set(newValue, forKey: "screenVideo") }
    }
    static let width = 480, fps = 8.0

    private let log = Logger(subsystem: "CineX", category: "ScreenVideo")
    private let queue = DispatchQueue(label: "CineX.screen-video", qos: .utility)
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("screen-\(UUID().uuidString).mp4")
    private var writer: AVAssetWriter?
    private var input: AVAssetWriterInput?
    private var adaptor: AVAssetWriterInputPixelBufferAdaptor?
    private var transfer: VTPixelTransferSession?
    private var start: CMTime?, last: CMTime?
    private(set) var frames = 0
    private var failed = false

    func begin() {
        let recorder = RPScreenRecorder.shared()
        guard recorder.isAvailable else { log.info("screen video: not available"); return }
        recorder.isMicrophoneEnabled = false
        recorder.startCapture(handler: { [weak self] sample, type, error in
            guard let self, type == .video, error == nil, CMSampleBufferDataIsReady(sample) else { return }
            self.queue.sync { self.append(sample) }   // ReplayKit keeps the buffer only for the call
        }, completionHandler: { [weak self] error in
            if let error { self?.log.info("screen video: not started (\(error.localizedDescription, privacy: .public))") }
        })
    }

    /// Stops; calls back with the finished file (nil when nothing was recorded).
    func end(_ done: @escaping (URL?) -> Void) {
        RPScreenRecorder.shared().stopCapture { [weak self] _ in
            guard let self else { done(nil); return }
            self.queue.async {
                guard let writer = self.writer, writer.status == .writing else { done(nil); return }
                self.input?.markAsFinished()
                writer.finishWriting { done(writer.status == .completed ? self.url : nil) }
            }
        }
    }

    private func append(_ sample: CMSampleBuffer) {
        guard !failed, let image = CMSampleBufferGetImageBuffer(sample) else { return }
        let time = CMSampleBufferGetPresentationTimeStamp(sample)
        if let last, (time - last).seconds < 1 / Self.fps - 0.005 { return }
        let sw = CVPixelBufferGetWidth(image), sh = CVPixelBufferGetHeight(image)
        if writer == nil {
            let w = Self.width, h = (Int(Double(w) * Double(sh) / Double(sw)) / 2) * 2
            guard let wr = try? AVAssetWriter(outputURL: url, fileType: .mp4) else { failed = true; return }
            let i = AVAssetWriterInput(mediaType: .video, outputSettings: [
                AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: w, AVVideoHeightKey: h,
                AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 700_000, AVVideoMaxKeyFrameIntervalKey: 16]])
            i.expectsMediaDataInRealTime = true
            let a = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: i, sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
                kCVPixelBufferWidthKey as String: w, kCVPixelBufferHeightKey as String: h])
            guard wr.canAdd(i) else { failed = true; return }
            wr.add(i)
            guard wr.startWriting() else { failed = true; return }
            wr.startSession(atSourceTime: .zero)
            var session: VTPixelTransferSession?
            VTPixelTransferSessionCreate(allocator: nil, pixelTransferSessionOut: &session)
            writer = wr; input = i; adaptor = a; transfer = session; start = time
        }
        guard let input, input.isReadyForMoreMediaData, let adaptor, let pool = adaptor.pixelBufferPool, let transfer, let start else { return }
        var out: CVPixelBuffer?
        CVPixelBufferPoolCreatePixelBuffer(nil, pool, &out)
        guard let out, VTPixelTransferSessionTransferImage(transfer, from: image, to: out) == noErr else { return }
        if adaptor.append(out, withPresentationTime: time - start) { frames += 1; last = time }
    }

    /// Puts the finished file into the scan's folder as screen.mp4 (a Continue scan's goes next to it).
    static func move(_ file: URL, into folder: URL) {
        let fm = FileManager.default
        var target = folder.appendingPathComponent("screen.mp4"), n = 1
        while fm.fileExists(atPath: target.path) { target = folder.appendingPathComponent("screen-continue\(n == 1 ? "" : "-\(n)").mp4"); n += 1 }
        try? fm.moveItem(at: file, to: target)
    }
}
#endif
