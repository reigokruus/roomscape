import ARKit
import MetalKit
import SwiftUI

/// Feeds RoomPlan's ARKit frames into a `LiveCloud` while scanning.
///
/// It only *reads* `arSession.currentFrame`. It never sets the ARSession's or RoomCaptureSession's delegate:
/// RoomCaptureView stops working when someone else takes those over.
@MainActor
final class LiveCapture {
    let device: MTLDevice?
    let cloud: LiveCloud?
    /// The live mesh fused from LiDAR depth, drawn instead of the discs once it has triangles.
    let mesh: LiveMesh?
    /// Continue scan: the saved scan's coarse outline in blue (see `LiveGhost`).
    let ghost = LiveGhost()
    let session: ARSession
    private var link: CADisplayLink?
    private var discView: LiveDiscsMTKView?
    private var attachTimer: Timer?
    private var previewTimer: Timer?
    #if DEBUG
    private var loggedPreview = false
    #endif
    private let worker = DispatchQueue(label: "CineX.live-cloud", qos: .utility)
    private var busy = false
    private var lastFrameTime: TimeInterval = 0

    init(session: ARSession) {
        self.session = session
        device = MTLCreateSystemDefaultDevice()
        cloud = device.flatMap { LiveCloud(device: $0) }
        mesh = device.map { LiveMesh(device: $0) }
    }

    func start() {
        guard link == nil, let cloud else { return }
        let mesh = mesh
        worker.async {
            cloud.reset()
            mesh?.reset()
        }
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
        discView?.preferredFramesPerSecond = on ? 6 : 12
        let mesh = mesh
        if let cloud {
            worker.async {
                cloud.setPowerSaving(on)
                mesh?.setPowerSaving(on)
            }
        }
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
        previewTimer?.invalidate()
        previewTimer = nil
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

    /// Moves RoomPlan's small 3D preview of the room (normally at the bottom, behind the Cancel and Done buttons)
    /// to the top right. RoomPlan has no API for this: the preview is found as the smaller 3D view inside
    /// RoomCaptureView (not the full-screen camera view, not our mesh overlay), lifted to its outermost container
    /// that's still small, and moved with a transform, which RoomPlan's own layout doesn't reset. Checked twice a
    /// second, since RoomPlan can lay it out again or create it late.
    func pinPreviewTopRight(in captureView: UIView) {
        previewTimer?.invalidate()
        previewTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self, weak captureView] _ in
            MainActor.assumeIsolated {
                guard let self, let captureView, let preview = Self.previewView(in: captureView) else { return }
                #if DEBUG
                if !self.loggedPreview {
                    self.loggedPreview = true
                    print("LiveCapture: moving RoomPlan preview \(type(of: preview)) \(preview.frame)")
                }
                #endif
                UIView.performWithoutAnimation {
                    // Where it sits without our transform, in the capture view's coordinates.
                    let saved = preview.transform
                    preview.transform = .identity
                    let frame = preview.convert(preview.bounds, to: captureView)
                    let margin: CGFloat = 12
                    let target = CGPoint(x: captureView.bounds.width - margin - frame.width,
                                         y: captureView.safeAreaInsets.top + margin)
                    let moved = CGAffineTransform(translationX: target.x - frame.minX, y: target.y - frame.minY)
                    preview.transform = saved == moved ? saved : moved
                }
            }
        }
    }

    private static func previewView(in root: UIView) -> UIView? {
        let screen = root.bounds.width * root.bounds.height
        guard screen > 0 else { return nil }
        let camera = cameraView(in: root)
        var best: UIView?
        var bestArea: CGFloat = 0
        func visit(_ v: UIView) {
            for sub in v.subviews where !(sub is LiveDiscsMTKView) && sub !== camera {
                let name = String(describing: type(of: sub))
                let area = sub.bounds.width * sub.bounds.height
                if !sub.isHidden, area > screen * 0.01, area < screen * 0.45, area > bestArea,
                   ["ARView", "SCNView", "SceneView", "MTKView", "RealityView"].contains(where: name.contains) {
                    best = sub
                    bestArea = area
                }
                visit(sub)
            }
        }
        visit(root)
        // Its outermost container that's still small (so a frame or background around it moves with it).
        guard var view = best else { return nil }
        while let parent = view.superview, parent !== root,
              parent.bounds.width * parent.bounds.height < screen * 0.45 {
            view = parent
        }
        return view
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
        let mesh = mesh
        return await withCheckedContinuation { continuation in
            worker.async {
                var snapshot = cloud.snapshot()
                snapshot.mesh = mesh?.wholeMesh()
                continuation.resume(returning: snapshot)
            }
        }
    }

    fileprivate func tick() {
        guard !busy, let cloud, let frame = session.currentFrame, frame.timestamp != lastFrameTime else { return }
        lastFrameTime = frame.timestamp
        busy = true
        // One frame at a time, released as soon as it's read, so ARKit never runs short of camera buffers.
        let mesh = mesh
        let covered = ghost.coveredTest()
        let p = frame.camera.transform.columns.3
        let camera = SIMD3<Float>(p.x, p.y, p.z), time = frame.timestamp
        worker.async { [weak self] in
            cloud.integrate(frame, tsdf: mesh?.tsdf, covered: covered)
            DispatchQueue.main.async { self?.busy = false }
        }
        // A separate block, so the frame is already let go while the mesh is built.
        if let mesh { worker.async { mesh.rebuildIfDue(camera: camera, time: time) } }
    }

    /// CADisplayLink keeps a strong reference to its target; this keeps it from holding on to LiveCapture.
    private final class WeakTarget: NSObject {
        weak var owner: LiveCapture?
        init(_ owner: LiveCapture) { self.owner = owner }
        @objc func tick() { MainActor.assumeIsolated { owner?.tick() } }
    }
}

/// Transparent Metal view that draws the live capture over the camera image as a white mesh with a grid: every
/// captured spot is a small white square tile lying on its surface (its normal comes from the LiDAR depth), and
/// grid lines every `LiveDiscRenderer.cell` run across them in world space. Fresh spots are faint; they brighten as
/// they become solid, so what's still thin stays visible without colour coding.
/// Holds its renderer, since an MTKView only keeps a weak reference to its delegate.
final class LiveDiscsMTKView: MTKView {
    private let renderer: LiveDiscRenderer

    init?(live: LiveCapture) {
        guard let device = live.device, let cloud = live.cloud,
              let renderer = LiveDiscRenderer(device: device, cloud: cloud, mesh: live.mesh, ghost: live.ghost) else { return nil }
        self.renderer = renderer
        super.init(frame: .zero, device: device)
        isOpaque = false
        backgroundColor = .clear
        isUserInteractionEnabled = false
        colorPixelFormat = .bgra8Unorm
        depthStencilPixelFormat = .depth32Float
        clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 0)
        // Drawing at 1x instead of the screen's 3x is 9x fewer pixels for the GPU (which is also running the camera
        // and RoomPlan); the grid lines are anti-aliased, so it still looks clean. 12 fps is enough for feedback
        // and keeps the phone cooler (6 when it's warm, see `LiveCapture.setPowerSaving`).
        contentScaleFactor = 1
        preferredFramesPerSecond = 12
        renderer.session = live.session
        delegate = renderer
    }

    required init(coder: NSCoder) { fatalError("not supported") }
}

/// Kept cheap for the GPU, which is also running the camera, ARKit and RoomPlan: tiles are built in the vertex
/// shader (no discard, no depth written from the fragment shader, which would turn off the GPU's hidden-surface
/// removal), a depth-only pass with no fragment shader finds the nearest tile per pixel, and the colour pass then
/// shades only that tile, so each pixel is shaded about once and overlapping tiles never blend twice.
final class LiveDiscRenderer: NSObject, MTKViewDelegate {
    /// Grid size, metres.
    static let cell: Float = 0.1

    weak var session: ARSession?
    private let cloud: LiveCloud
    private let meshRenderer: LiveMeshRenderer?
    private let ghostRenderer: LiveGhostRenderer?
    private let ghost: LiveGhost
    private let queue: MTLCommandQueue
    private let depthPipeline: MTLRenderPipelineState
    private let colorPipeline: MTLRenderPipelineState
    private let depthWrite: MTLDepthStencilState
    private let depthEqual: MTLDepthStencilState

    private struct Uniforms {
        var viewProjection: simd_float4x4
        var cameraPosition: SIMD4<Float>
        var halfSize: Float
        var cell: Float
    }

    init?(device: MTLDevice, cloud: LiveCloud, mesh: LiveMesh?, ghost: LiveGhost) {
        let options = MTLCompileOptions()
        // Both passes must put every tile at exactly the same depth.
        options.preserveInvariance = true
        guard let queue = device.makeCommandQueue(),
              let library = try? device.makeLibrary(source: Self.shaderSource, options: options),
              let vertex = library.makeFunction(name: "tileVertex"),
              let fragment = library.makeFunction(name: "tileFragment") else { return nil }
        func pipeline(fragment: MTLFunction?) -> MTLRenderPipelineState? {
            let descriptor = MTLRenderPipelineDescriptor()
            descriptor.vertexFunction = vertex
            descriptor.fragmentFunction = fragment
            descriptor.depthAttachmentPixelFormat = .depth32Float
            let color = descriptor.colorAttachments[0]!
            color.pixelFormat = .bgra8Unorm
            color.writeMask = fragment == nil ? [] : .all
            color.isBlendingEnabled = fragment != nil
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
        guard let depthPipeline = pipeline(fragment: nil), let colorPipeline = pipeline(fragment: fragment),
              let depthWrite = depthState(.less, write: true),
              let depthEqual = depthState(.lessEqual, write: false) else { return nil }
        self.cloud = cloud
        meshRenderer = mesh.flatMap { LiveMeshRenderer(device: device, mesh: $0) }
        ghostRenderer = LiveGhostRenderer(device: device, ghost: ghost)
        self.ghost = ghost
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
        if let camera = session?.currentFrame?.camera, view.bounds.width > 0 {
            // Same portrait, aspect-fill camera mapping RoomCaptureView uses for its camera image.
            let viewMatrix = camera.viewMatrix(for: .portrait)
            let projection = camera.projectionMatrix(for: .portrait, viewportSize: view.bounds.size, zNear: 0.05, zFar: 50)
            // The live LiDAR mesh once ARKit has one; until then (or without it) the discs.
            // Continue scan never shows the discs: they'd cover the saved (blue) areas again.
            if meshRenderer?.encode(encoder, viewProjection: projection * viewMatrix) != true, count > 0, !ghost.isActive {
                drawDiscs(encoder, count: count, viewMatrix: viewMatrix, projection: projection)
            }
            ghostRenderer?.encode(encoder, viewProjection: projection * viewMatrix)
        }
        encoder.endEncoding()
        commands.present(drawable)
        commands.commit()
    }

    private func drawDiscs(_ encoder: MTLRenderCommandEncoder, count: Int, viewMatrix: simd_float4x4,
                           projection: simd_float4x4) {
        do {
            var uniforms = Uniforms(viewProjection: projection * viewMatrix,
                                    cameraPosition: viewMatrix.inverse.columns.3,
                                    halfSize: cloud.voxelSize * 0.7, cell: Self.cell)
            encoder.setVertexBuffer(cloud.buffer, offset: 0, index: 0)
            encoder.setVertexBuffer(cloud.normalBuffer, offset: 0, index: 1)
            encoder.setVertexBytes(&uniforms, length: MemoryLayout<Uniforms>.stride, index: 2)
            encoder.setFragmentBytes(&uniforms, length: MemoryLayout<Uniforms>.stride, index: 0)
            encoder.setRenderPipelineState(depthPipeline)
            encoder.setDepthStencilState(depthWrite)
            encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4, instanceCount: count)
            encoder.setRenderPipelineState(colorPipeline)
            encoder.setDepthStencilState(depthEqual)
            encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4, instanceCount: count)
        }
    }

    /// One instance per spot, four vertices each: a square tile on the spot's plane, edges along the wall and up on
    /// walls, along x and z on floors, so neighbouring tiles line up.
    private static let shaderSource = """
    #include <metal_stdlib>
    using namespace metal;

    struct LivePoint { packed_float3 position; uchar4 color; };
    struct Uniforms {
        float4x4 viewProjection;
        float4 cameraPosition;
        float halfSize;
        float cell;
    };
    struct TileOut {
        float4 position [[position, invariant]];
        float3 world;
        float3 normal [[flat]];
        float solid [[flat]];
    };

    vertex TileOut tileVertex(uint vid [[vertex_id]], uint iid [[instance_id]],
                              const device LivePoint *points [[buffer(0)]],
                              const device char4 *normals [[buffer(1)]],
                              constant Uniforms &u [[buffer(2)]]) {
        LivePoint p = points[iid];
        float3 c = float3(p.position);
        float3 n = float3(normals[iid].xyz) / 127.0;
        // No normal yet: a tile facing the camera.
        n = dot(n, n) > 0.25 ? normalize(n) : normalize(u.cameraPosition.xyz - c);
        float3 a = abs(n.y) < 0.9 ? float3(0.0, 1.0, 0.0) : float3(1.0, 0.0, 0.0);
        float3 t1 = normalize(cross(a, n));
        float3 t2 = cross(n, t1);
        float2 corner = float2((vid & 1) ? 1.0 : -1.0, (vid & 2) ? 1.0 : -1.0);
        float3 world = c + (t1 * corner.x + t2 * corner.y) * u.halfSize;
        TileOut out;
        out.position = u.viewProjection * float4(world, 1.0);
        out.world = world;
        out.normal = n;
        out.solid = float(p.color.a) / 255.0;
        return out;
    }

    fragment float4 tileFragment(TileOut in [[stage_in]], constant Uniforms &u [[buffer(0)]]) {
        // Grid on the plane the surface faces: x-z on floors and tabletops, along the wall and up on walls.
        float3 n = in.normal;
        float2 uv;
        if (abs(n.y) > 0.7) {
            uv = in.world.xz;
        } else {
            float2 along = normalize(float2(-n.z, n.x) + float2(1e-6, 0.0));
            uv = float2(dot(in.world.xz, along), in.world.y);
        }
        uv /= u.cell;
        float2 fw = max(fwidth(uv), float2(1e-4));
        float2 g = abs(fract(uv - 0.5) - 0.5) / fw;
        float line = (1.0 - min(min(g.x, g.y), 1.0)) * (1.0 - smoothstep(0.15, 0.4, max(fw.x, fw.y)));
        float s = in.solid;
        return float4(1.0, 1.0, 1.0, max(mix(0.05, 0.14, s), line * mix(0.3, 0.85, s)));
    }
    """
}
