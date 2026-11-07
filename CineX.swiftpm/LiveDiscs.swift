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
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 5, maximum: 10, preferred: 10)
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    /// The phone is hot: sample depth and draw discs at half rate, and take photos further apart. RoomPlan keeps
    /// running at full rate; this only lowers what the app adds on top of it.
    func setPowerSaving(_ on: Bool) {
        link?.preferredFrameRateRange = on ? CAFrameRateRange(minimum: 3, maximum: 5, preferred: 5)
                                           : CAFrameRateRange(minimum: 5, maximum: 10, preferred: 10)
        discView?.preferredFramesPerSecond = on ? 10 : 20
        if let cloud { worker.async { cloud.setPowerSaving(on) } }
    }

    /// iOS warned about memory: let the cloud give some back.
    func shedMemory() {
        if let cloud { worker.async { cloud.shedMemory() } }
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

/// Transparent Metal view that draws the live capture over the camera image as a white mesh with a grid: every
/// captured spot is a small white disc lying on its surface (its normal comes from the LiDAR depth), and grid lines
/// every `LiveDiscRenderer.cell` run across them in world space. Fresh spots are faint; they brighten as they
/// become solid, so what's still thin stays visible without colour coding.
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
        // Drawing at 1x instead of the screen's 3x is 9x fewer pixels for the GPU (which is also running the camera
        // and RoomPlan); the grid lines are anti-aliased, so it still looks clean. 20 fps is plenty for feedback.
        contentScaleFactor = 1
        preferredFramesPerSecond = 20
        renderer.session = live.session
        delegate = renderer
    }

    required init(coder: NSCoder) { fatalError("not supported") }
}

final class LiveDiscRenderer: NSObject, MTKViewDelegate {
    /// Grid size, metres.
    static let cell: Float = 0.1

    weak var session: ARSession?
    private let cloud: LiveCloud
    private let queue: MTLCommandQueue
    /// Two passes, so overlapping discs never blend twice (which made the fill blotchy): depth only, then colour
    /// where a disc is the nearest surface. Both use the same shaders, so their depths match exactly.
    private let depthPipeline: MTLRenderPipelineState
    private let colorPipeline: MTLRenderPipelineState
    private let depthWrite: MTLDepthStencilState
    private let depthEqual: MTLDepthStencilState

    private struct Uniforms {
        var viewProjection: simd_float4x4
        var inverseViewProjection: simd_float4x4
        var cameraPosition: SIMD4<Float>
        var viewport: SIMD2<Float>
        var radius: Float
        var pixelsPerMeter: Float
        var cell: Float
    }

    init?(device: MTLDevice, cloud: LiveCloud) {
        guard let queue = device.makeCommandQueue(),
              let library = try? device.makeLibrary(source: Self.shaderSource, options: nil),
              let vertex = library.makeFunction(name: "tileVertex"),
              let fragment = library.makeFunction(name: "tileFragment") else { return nil }
        func pipeline(colorWrites: Bool) -> MTLRenderPipelineState? {
            let descriptor = MTLRenderPipelineDescriptor()
            descriptor.vertexFunction = vertex
            descriptor.fragmentFunction = fragment
            descriptor.depthAttachmentPixelFormat = .depth32Float
            let color = descriptor.colorAttachments[0]!
            color.pixelFormat = .bgra8Unorm
            color.writeMask = colorWrites ? .all : []
            color.isBlendingEnabled = colorWrites
            // Premultiplied result over a transparent background, so the overlay composites cleanly over the camera.
            color.sourceRGBBlendFactor = .sourceAlpha
            color.destinationRGBBlendFactor = .oneMinusSourceAlpha
            color.sourceAlphaBlendFactor = .one
            color.destinationAlphaBlendFactor = .oneMinusSourceAlpha
            return try? device.makeRenderPipelineState(descriptor: descriptor)
        }
        func depthState(_ compare: MTLCompareFunction, write: Bool) -> MTLDepthStencilState? {
            let depth = MTLDepthStencilDescriptor()
            depth.depthCompareFunction = compare
            depth.isDepthWriteEnabled = write
            return device.makeDepthStencilState(descriptor: depth)
        }
        guard let depthPipeline = pipeline(colorWrites: false), let colorPipeline = pipeline(colorWrites: true),
              let depthWrite = depthState(.less, write: true),
              let depthEqual = depthState(.lessEqual, write: false) else { return nil }
        self.cloud = cloud
        self.queue = queue
        self.depthPipeline = depthPipeline
        self.colorPipeline = colorPipeline
        self.depthWrite = depthWrite
        self.depthEqual = depthEqual
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
            let size = view.drawableSize
            let viewProjection = projection * viewMatrix
            let eye = viewMatrix.inverse.columns.3
            var uniforms = Uniforms(viewProjection: viewProjection, inverseViewProjection: viewProjection.inverse,
                                    cameraPosition: eye, viewport: SIMD2(Float(size.width), Float(size.height)),
                                    radius: cloud.voxelSize * 0.9,
                                    pixelsPerMeter: projection[1][1] * Float(size.height) / 2, cell: Self.cell)
            encoder.setVertexBuffer(cloud.buffer, offset: 0, index: 0)
            encoder.setVertexBuffer(cloud.normalBuffer, offset: 0, index: 1)
            encoder.setVertexBytes(&uniforms, length: MemoryLayout<Uniforms>.stride, index: 2)
            encoder.setFragmentBytes(&uniforms, length: MemoryLayout<Uniforms>.stride, index: 0)
            encoder.setRenderPipelineState(depthPipeline)
            encoder.setDepthStencilState(depthWrite)
            encoder.drawPrimitives(type: .point, vertexStart: 0, vertexCount: count)
            encoder.setRenderPipelineState(colorPipeline)
            encoder.setDepthStencilState(depthEqual)
            encoder.drawPrimitives(type: .point, vertexStart: 0, vertexCount: count)
        }
        encoder.endEncoding()
        commands.present(drawable)
        commands.commit()
    }

    /// Each spot is one point sprite big enough to hold its disc. The fragment shader casts the pixel's ray onto
    /// the spot's plane, keeps it if it lands within `radius` of the centre, and writes that hit's real depth, so
    /// neighbouring discs meet like a surface and the grid is drawn where the hit is in the room.
    private static let shaderSource = """
    #include <metal_stdlib>
    using namespace metal;

    struct LivePoint { packed_float3 position; uchar4 color; };
    struct Uniforms {
        float4x4 viewProjection;
        float4x4 inverseViewProjection;
        float4 cameraPosition;
        float2 viewport;
        float radius;
        float pixelsPerMeter;
        float cell;
    };
    struct TileOut {
        float4 position [[position]];
        float pointSize [[point_size]];
        float3 center [[flat]];
        float3 normal [[flat]];
        float solid [[flat]];
    };
    struct TileColor {
        float4 color [[color(0)]];
        float depth [[depth(any)]];
    };

    vertex TileOut tileVertex(uint id [[vertex_id]],
                              const device LivePoint *points [[buffer(0)]],
                              const device char4 *normals [[buffer(1)]],
                              constant Uniforms &u [[buffer(2)]]) {
        LivePoint p = points[id];
        float3 c = float3(p.position);
        float3 n = float3(normals[id].xyz) / 127.0;
        // No normal yet: a disc facing the camera.
        n = dot(n, n) > 0.25 ? normalize(n) : normalize(u.cameraPosition.xyz - c);
        TileOut out;
        out.position = u.viewProjection * float4(c, 1.0);
        float w = max(out.position.w, 0.05);
        out.pointSize = clamp(2.4 * u.radius * u.pixelsPerMeter / w, 1.0, 64.0); // 1x pixels
        out.center = c;
        out.normal = n;
        out.solid = float(p.color.a) / 255.0;
        return out;
    }

    fragment TileColor tileFragment(TileOut in [[stage_in]], constant Uniforms &u [[buffer(0)]]) {
        // The pixel's ray, from the near to the far plane.
        float2 ndc = float2(in.position.x / u.viewport.x * 2.0 - 1.0, 1.0 - in.position.y / u.viewport.y * 2.0);
        float4 a = u.inverseViewProjection * float4(ndc, 0.0, 1.0);
        float4 b = u.inverseViewProjection * float4(ndc, 1.0, 1.0);
        float3 o = a.xyz / a.w;
        float3 dir = b.xyz / b.w - o;
        float3 n = in.normal;
        float denom = dot(dir, n);
        float t = abs(denom) > 1e-6 ? dot(in.center - o, n) / denom : -1.0;
        float3 hit = o + dir * t;

        // Grid on the plane the surface faces: x-z on floors and tabletops, along the wall and up on walls.
        float2 uv;
        if (abs(n.y) > 0.7) {
            uv = hit.xz;
        } else {
            float2 along = normalize(float2(-n.z, n.x) + float2(1e-6, 0.0));
            uv = float2(dot(hit.xz, along), hit.y);
        }
        uv /= u.cell;
        float2 fw = max(fwidth(uv), float2(1e-4));
        float2 g = abs(fract(uv - 0.5) - 0.5) / fw;
        float line = (1.0 - min(min(g.x, g.y), 1.0)) * (1.0 - smoothstep(0.15, 0.4, max(fw.x, fw.y)));

        if (t <= 0.0 || length(hit - in.center) > u.radius) discard_fragment();
        float4 clip = u.viewProjection * float4(hit, 1.0);
        float s = in.solid;
        float fill = mix(0.05, 0.14, s);
        float stroke = mix(0.3, 0.85, s);
        TileColor out;
        out.color = float4(1.0, 1.0, 1.0, max(fill, line * stroke));
        out.depth = clip.z / clip.w;
        return out;
    }
    """
}
