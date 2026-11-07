import ARKit
import MetalKit
import SwiftUI

/// Feeds RoomPlan's ARKit frames into a `LiveCloud` while scanning, and publishes the "Detail here" status line.
///
/// It only *reads* `arSession.currentFrame`. It never sets the ARSession's or RoomCaptureSession's delegate:
/// RoomCaptureView stops working when someone else takes those over.
@MainActor
final class LiveCapture: ObservableObject {
    @Published private(set) var detailLine = LiveCapture.line(for: -1)
    let device: MTLDevice?
    let cloud: LiveCloud?
    let session: ARSession
    private var link: CADisplayLink?
    private var discView: LiveDiscsMTKView?
    private var attachTimer: Timer?
    private let worker = DispatchQueue(label: "CineX.live-cloud", qos: .utility)
    private var busy = false
    private var lastFrameTime: TimeInterval = 0

    init(session: ARSession) {
        self.session = session
        device = MTLCreateSystemDefaultDevice()
        cloud = device.flatMap { LiveCloud(device: $0) }
    }

    func start() {
        guard link == nil, let cloud else { return }
        worker.async { cloud.reset() }
        detailLine = Self.line(for: -1)
        let link = CADisplayLink(target: WeakTarget(self), selector: #selector(WeakTarget.tick))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 10, maximum: 15, preferred: 15)
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    /// Stops taking frames and removes the discs. The cloud keeps what was captured.
    func stop() {
        link?.invalidate()
        link = nil
        attachTimer?.invalidate()
        attachTimer = nil
        discView?.removeFromSuperview()
        discView = nil
    }

    /// Puts the discs inside RoomCaptureView, directly above its camera view, so RoomPlan's own UI (coaching text,
    /// the dollhouse preview, the glyph) stays on top of them. RoomPlan has no public API for this: its camera view
    /// is found by class name (the largest subview whose class name contains "ARView"; the dollhouse preview is a
    /// smaller one). If it isn't there within 3 s, the discs go on top of the whole view instead, as before.
    func attachDiscs(to captureView: UIView) {
        guard discView == nil, let view = LiveDiscsMTKView(live: self) else { return }
        discView = view
        let started = Date()
        attachTimer?.invalidate()
        attachTimer = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { [weak self] timer in
            MainActor.assumeIsolated {
                guard let self, let view = self.discView else { timer.invalidate(); return }
                if let camera = Self.cameraView(in: captureView), let parent = camera.superview {
                    view.frame = camera.frame
                    view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
                    parent.insertSubview(view, aboveSubview: camera)
                } else if Date().timeIntervalSince(started) > 3 {
                    view.frame = captureView.bounds
                    view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
                    captureView.addSubview(view)
                } else {
                    return
                }
                timer.invalidate()
                self.attachTimer = nil
            }
        }
    }

    private static func cameraView(in root: UIView) -> UIView? {
        var best: UIView?
        var bestArea: CGFloat = 0
        func visit(_ v: UIView) {
            for sub in v.subviews where !(sub is LiveDiscsMTKView) {
                let area = sub.bounds.width * sub.bounds.height
                if String(describing: type(of: sub)).contains("ARView"), area > bestArea {
                    best = sub
                    bestArea = area
                }
                visit(sub)
            }
        }
        visit(root)
        // Only when it covers most of the screen: otherwise it isn't the camera view (or isn't laid out yet).
        return bestArea >= root.bounds.width * root.bounds.height * 0.5 && bestArea > 0 ? best : nil
    }

    /// The points and photos captured so far, read after any frame still being processed.
    func snapshot() async -> CaptureSnapshot? {
        guard let cloud else { return nil }
        return await withCheckedContinuation { continuation in
            worker.async { continuation.resume(returning: cloud.snapshot()) }
        }
    }

    fileprivate func tick() {
        guard !busy, let cloud, let frame = session.currentFrame, frame.timestamp != lastFrameTime else { return }
        lastFrameTime = frame.timestamp
        busy = true
        // One frame at a time, released as soon as it's read, so ARKit never runs short of camera buffers.
        worker.async { [weak self] in
            cloud.integrate(frame)
            let detail = cloud.isFull ? -2 : cloud.detailHere
            DispatchQueue.main.async {
                guard let self else { return }
                self.busy = false
                let line = Self.line(for: detail)
                if line != self.detailLine { self.detailLine = line }
            }
        }
    }

    private static func line(for detail: Float) -> String {
        if detail <= -2 { return "⚪️ Capture memory is full. Tap Done to keep what you have" }
        guard detail >= 0 else { return "Detail here: point at something" }
        let percent = Int((detail * 100).rounded())
        if percent >= 70 { return "🟢 Detail here: \(percent)% solid, move on" }
        if percent >= 35 { return "🟡 Detail here: \(percent)% solid, hold a bit longer" }
        return "🔴 Detail here: \(percent)% solid, hold still here"
    }

    /// CADisplayLink keeps a strong reference to its target; this keeps it from holding on to LiveCapture.
    private final class WeakTarget: NSObject {
        weak var owner: LiveCapture?
        init(_ owner: LiveCapture) { self.owner = owner }
        @objc func tick() { MainActor.assumeIsolated { owner?.tick() } }
    }
}

/// Transparent Metal view that draws every live spot as a disc over the camera image (handoff section 2: live discs).
/// The rim goes red (seen once) → yellow → green (solid); solid spots shrink to faint green dots.
/// Holds its renderer, since an MTKView only keeps a weak reference to its delegate.
final class LiveDiscsMTKView: MTKView {
    private let renderer: LiveDiscRenderer

    init?(live: LiveCapture) {
        guard let device = live.device, let cloud = live.cloud,
              let renderer = LiveDiscRenderer(device: device, cloud: cloud) else { return nil }
        self.renderer = renderer
        super.init(frame: .zero, device: device)
        isOpaque = false
        backgroundColor = .clear
        isUserInteractionEnabled = false
        colorPixelFormat = .bgra8Unorm
        depthStencilPixelFormat = .depth32Float
        clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 0)
        preferredFramesPerSecond = 30
        renderer.session = live.session
        delegate = renderer
    }

    required init(coder: NSCoder) { fatalError("not supported") }
}

final class LiveDiscRenderer: NSObject, MTKViewDelegate {
    weak var session: ARSession?
    private let cloud: LiveCloud
    private let queue: MTLCommandQueue
    private let pipeline: MTLRenderPipelineState
    private let depthState: MTLDepthStencilState

    private struct Uniforms {
        var mvp: simd_float4x4
        var sizeScale: Float
    }

    init?(device: MTLDevice, cloud: LiveCloud) {
        guard let queue = device.makeCommandQueue(),
              let library = try? device.makeLibrary(source: Self.shaderSource, options: nil),
              let vertex = library.makeFunction(name: "discVertex"),
              let fragment = library.makeFunction(name: "discFragment") else { return nil }
        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.vertexFunction = vertex
        descriptor.fragmentFunction = fragment
        descriptor.depthAttachmentPixelFormat = .depth32Float
        let color = descriptor.colorAttachments[0]!
        color.pixelFormat = .bgra8Unorm
        color.isBlendingEnabled = true
        // Premultiplied result over a transparent background, so the overlay composites cleanly over the camera.
        color.sourceRGBBlendFactor = .sourceAlpha
        color.destinationRGBBlendFactor = .oneMinusSourceAlpha
        color.sourceAlphaBlendFactor = .one
        color.destinationAlphaBlendFactor = .oneMinusSourceAlpha
        guard let pipeline = try? device.makeRenderPipelineState(descriptor: descriptor) else { return nil }
        let depth = MTLDepthStencilDescriptor()
        depth.depthCompareFunction = .less
        depth.isDepthWriteEnabled = true
        guard let depthState = device.makeDepthStencilState(descriptor: depth) else { return nil }
        self.cloud = cloud
        self.queue = queue
        self.pipeline = pipeline
        self.depthState = depthState
    }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

    func draw(in view: MTKView) {
        guard let pass = view.currentRenderPassDescriptor,
              let drawable = view.currentDrawable,
              let commands = queue.makeCommandBuffer(),
              let encoder = commands.makeRenderCommandEncoder(descriptor: pass) else { return }
        let count = cloud.count
        if count > 0, let camera = session?.currentFrame?.camera, view.bounds.width > 0 {
            // Same portrait, aspect-fill camera mapping RoomCaptureView uses for its camera image.
            let viewMatrix = camera.viewMatrix(for: .portrait)
            let projection = camera.projectionMatrix(for: .portrait, viewportSize: view.bounds.size, zNear: 0.05, zFar: 50)
            let pixelsPerMeter = projection[1][1] * Float(view.drawableSize.height) / 2
            var uniforms = Uniforms(mvp: projection * viewMatrix, sizeScale: LiveCloud.voxel * 1.6 * pixelsPerMeter)
            encoder.setRenderPipelineState(pipeline)
            encoder.setDepthStencilState(depthState)
            encoder.setVertexBuffer(cloud.buffer, offset: 0, index: 0)
            encoder.setVertexBytes(&uniforms, length: MemoryLayout<Uniforms>.stride, index: 1)
            encoder.drawPrimitives(type: .point, vertexStart: 0, vertexCount: count)
        }
        encoder.endEncoding()
        commands.present(drawable)
        commands.commit()
    }

    private static let shaderSource = """
    #include <metal_stdlib>
    using namespace metal;

    struct LivePoint { packed_float3 position; uchar4 color; };
    struct Uniforms { float4x4 mvp; float sizeScale; };
    struct DiscOut {
        float4 position [[position]];
        float pointSize [[point_size]];
        float4 color;
    };

    vertex DiscOut discVertex(uint id [[vertex_id]],
                              const device LivePoint *points [[buffer(0)]],
                              constant Uniforms &u [[buffer(1)]]) {
        LivePoint p = points[id];
        DiscOut out;
        out.position = u.mvp * float4(float3(p.position), 1.0);
        float w = max(out.position.w, 0.05);
        float4 c = float4(p.color) / 255.0;
        float solid = step(0.99, c.a);   // solid spots shrink so they stop hiding the camera
        out.pointSize = clamp(u.sizeScale / w * mix(1.0, 0.4, solid), 2.0, 64.0);
        out.color = c;
        return out;
    }

    fragment float4 discFragment(DiscOut in [[stage_in]], float2 pc [[point_coord]]) {
        float2 d = pc * 2.0 - 1.0;
        float r2 = dot(d, d);
        if (r2 > 1.0) discard_fragment();
        float s = in.color.a;            // 0…1 how solid
        if (s > 0.99) return float4(0.15, 1.0, 0.35, 0.35);
        float3 heat = s < 0.5 ? mix(float3(1.0, 0.15, 0.1), float3(1.0, 0.85, 0.1), s * 2.0)
                              : mix(float3(1.0, 0.85, 0.1), float3(0.15, 1.0, 0.35), s * 2.0 - 1.0);
        // See-through centres (Android uses 0.8): RoomPlan's own guidance text and wall outlines sit underneath.
        // See-through centres, slightly see-through rims: RoomPlan's white wall outlines are drawn underneath.
        if (r2 > 0.55) return float4(heat, 0.75);
        return float4(mix(in.color.rgb, heat, 0.25), 0.3);
    }
    """
}
