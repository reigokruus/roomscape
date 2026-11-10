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
    /// Flat tiles (test switch, off by default): camera-painted 30 cm tiles on flat surfaces (FlatTiles).
    let tiles: FlatTiles?
    /// The raw recording of this scan (RawRecorder), when recording is on.
    var recorder: RawRecorder?
    /// Hardware use while scanning (PerfLog).
    var perf: PerfLog?
    /// How far below the safe area's top RoomPlan's mini-model sits: under the scan card.
    var previewTop: CGFloat = 12
    let session: ARSession
    private var link: CADisplayLink?
    private var discView: LiveDiscsMTKView?
    private var attachTimer: Timer?
    private var previewTimer: Timer?
    #if DEBUG
    private var loggedPreview = false
    #endif
    private let worker = DispatchQueue(label: "CineX.live-cloud", qos: .utility, autoreleaseFrequency: .workItem)
    private var busy = false
    private var lastFrameTime: TimeInterval = 0

    init(session: ARSession) {
        self.session = session
        device = MTLCreateSystemDefaultDevice()
        cloud = device.flatMap { LiveCloud(device: $0) }
        mesh = device.map { LiveMesh(device: $0) }
        tiles = device.flatMap { FlatTiles(device: $0) }
        // The live preview: the scan's photos as textures, filling the mesh as the Photo view will (LivePreview).
        let photos = device.flatMap { LivePhotoStore(device: $0) }
        cloud?.photoStore = photos
        mesh?.photos = photos
    }

    /// The big buffers the live scan keeps, in MB (for the hardware log).
    var memorySizes: [String: Double] {
        var out: [String: Double] = [:]
        if let cloud { out["points"] = Double(cloud.buffer.length + cloud.normalBuffer.length) / 1_048_576 }
        if let photos = mesh?.photos { out["photo textures"] = Double(photos.texture.allocatedSize) / 1_048_576 }
        if let mesh { out["tsdf"] = Double(mesh.tsdf.capacity * 30) / 1_048_576 }
        return out
    }

    func start() {
        guard link == nil, let cloud else { return }
        let mesh = mesh, tiles = tiles
        worker.async {
            cloud.reset()
            mesh?.reset()
            tiles?.reset()
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
    /// Tap to clear while scanning: what's inside the circle of `radius` points around `point` on the live overlay (as
    /// drawn now: the overview's camera when it's on), from the camera to the surface there plus 15 cm, is removed from
    /// the points and the live mesh's surface. Calls back on the main queue with how much went.
    func clear(at point: CGPoint, radius: CGFloat, done: @escaping (Int) -> Void) {
        guard let cloud, let view = discView, let vp = view.lastViewProjection, view.bounds.width > 0 else { done(0); return }
        let size = view.bounds.size, mesh = mesh, recorder = recorder
        let clearTime = session.currentFrame?.timestamp
        worker.async {
            func screen(_ p: SIMD3<Float>) -> (SIMD2<Float>, Float)? {
                let c = vp * SIMD4(p, 1)
                guard c.w > 0.05 else { return nil }
                return (SIMD2((c.x / c.w + 1) / 2 * Float(size.width), (1 - c.y / c.w) / 2 * Float(size.height)), c.w)
            }
            let tap = SIMD2(Float(point.x), Float(point.y)), r = Float(radius)
            // How far the surface under the tap is: the nearest fifth of the points near the middle of the circle.
            var near: [Float] = []
            let n = cloud.count, stride = max(1, n / 200_000)
            let all = cloud.buffer.contents().bindMemory(to: LivePoint.self, capacity: n)
            for i in Swift.stride(from: 0, to: n, by: stride) {
                if let (s, w) = screen(SIMD3(all[i].x, all[i].y, all[i].z)), simd_distance(s, tap) < r * 0.35 { near.append(w) }
            }
            guard near.count >= 5 else { DispatchQueue.main.async { done(0) }; return }
            near.sort()
            let limit = near[near.count / 5] + 0.15
            let inCone: (SIMD3<Float>) -> Bool = { p in
                guard let (s, w) = screen(p), w <= limit else { return false }
                return simd_distance(s, tap) < r
            }
            let removed = cloud.removeWhere(inCone)
            if let t = clearTime { recorder?.cleared(time: t, viewProjection: vp, center: point, radius: radius, viewSize: size, limit: limit) }
            _ = mesh?.tsdf.clearWhere(inCone)
            mesh?.clearGPU(inCone)
            // The photo choices there are worked out again for what gets scanned next.
            var centre = SIMD3<Float>.zero, hits: Float = 0
            for i in Swift.stride(from: 0, to: n, by: stride) {
                let q = SIMD3(all[i].x, all[i].y, all[i].z)
                if let (s, w) = screen(q), w <= limit, simd_distance(s, tap) < r * 0.35 { centre += q; hits += 1 }
            }
            if hits > 0 { mesh?.forgetPhotos(near: [centre / hits], radius: 0.5) }
            DispatchQueue.main.async { done(removed) }
        }
    }

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
                // points-only: RoomPlan's small 3D preview hidden too (only points, tiles, the hint and the buttons)
                preview.isHidden = LivePreview.livePoints
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
                                         y: captureView.safeAreaInsets.top + self.previewTop)
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
        if lastFrameTime > 0, frame.timestamp - lastFrameTime > 0.25 { perf?.gap() }
        lastFrameTime = frame.timestamp
        recorder?.append(frame)
        busy = true
        // One frame at a time, released as soon as it's read, so ARKit never runs short of camera buffers.
        let mesh = mesh
        let covered = ghost.coveredTest()
        let p = frame.camera.transform.columns.3
        _ = p
        let camera = frame.camera.transform, time = frame.timestamp
        let perfLog = perf
        let tiles = tiles
        worker.async { [weak self] in
            let t0 = CACurrentMediaTime()
            cloud.integrate(frame, tsdf: mesh?.tsdf, covered: covered, mesh: mesh)
            perfLog?.note("depth frame (points, fusion, photo)", ms: (CACurrentMediaTime() - t0) * 1000)
            if FlatTiles.enabled, let tiles {
                let t1 = CACurrentMediaTime()
                tiles.update(frame)
                perfLog?.note("flat tiles", ms: (CACurrentMediaTime() - t1) * 1000)
            }
            DispatchQueue.main.async { self?.busy = false }
        }
        // A separate block, so the frame is already let go while the mesh is built.
        if let mesh { worker.async { mesh.rebuildIfDue(camera: camera, time: time) } }
        // Every 30 s: what each part holds (iPhone memory grew 720 MB → 1.55 GB in a 3-minute scan; rk 9 Nov).
        if let perf, time - lastSizesTime >= 30 {
            lastSizesTime = time
            worker.async {
                var sizes = mesh?.sizesMB() ?? [:]
                let k = cloud.keyframeSizes
                sizes["photos"] = k.mb
                sizes["photo count"] = Double(k.count)
                sizes["points"] = Double(cloud.buffer.length + cloud.normalBuffer.length) / 1_048_576
                perf.mark("sizes", sizes: sizes)
            }
        }
    }
    private var lastSizesTime: TimeInterval = 0

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
    /// The view-projection the overlay was last drawn with (the overview's when it's on).
    var lastViewProjection: simd_float4x4? { renderer.lastViewProjection }

    init?(live: LiveCapture) {
        guard let device = live.device, let cloud = live.cloud,
              let renderer = LiveDiscRenderer(device: device, cloud: cloud, mesh: live.mesh, ghost: live.ghost, tiles: live.tiles) else { return nil }
        self.renderer = renderer
        renderer.perf = live.perf
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
    private let tilesRenderer: FlatTilesRenderer?
    private let ghost: LiveGhost
    private let queue: MTLCommandQueue
    private let depthPipeline: MTLRenderPipelineState
    private let colorPipeline: MTLRenderPipelineState
    /// Points-only: the spots in their camera colour (the white grid look is for the mesh-first view).
    private let pointPipeline: MTLRenderPipelineState
    private let depthWrite: MTLDepthStencilState
    private let depthEqual: MTLDepthStencilState
    /// The wide overview (ScanOverview): the pulled-back camera and the camera-image window.
    private let overview = OverviewCamera()
    private(set) var lastViewProjection: simd_float4x4?
    private let liveMesh: LiveMesh?
    /// Hardware use while scanning: frames drawn and GPU time (PerfLog).
    var perf: PerfLog?
    private func meshTriangles() -> Int { (liveMesh?.buffers?.triangleIndexCount ?? 0) / 3 }
    private let window: OverviewWindowRenderer?

    private struct Uniforms {
        var viewProjection: simd_float4x4
        var cameraPosition: SIMD4<Float>
        var halfSize: Float
        var cell: Float
    }

    init?(device: MTLDevice, cloud: LiveCloud, mesh: LiveMesh?, ghost: LiveGhost, tiles: FlatTiles? = nil) {
        let options = MTLCompileOptions()
        // Both passes must put every tile at exactly the same depth.
        options.preserveInvariance = true
        guard let queue = device.makeCommandQueue(),
              let library = try? device.makeLibrary(source: Self.shaderSource, options: options),
              let vertex = library.makeFunction(name: "tileVertex"),
              let fragment = library.makeFunction(name: "tileFragment"),
              let pointFragment = library.makeFunction(name: "pointFragment") else { return nil }
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
              let pointPipeline = pipeline(fragment: pointFragment),
              let depthWrite = depthState(.less, write: true),
              let depthEqual = depthState(.lessEqual, write: false) else { return nil }
        self.cloud = cloud
        meshRenderer = mesh.flatMap { LiveMeshRenderer(device: device, mesh: $0) }
        liveMesh = mesh
        ghostRenderer = LiveGhostRenderer(device: device, ghost: ghost)
        tilesRenderer = tiles.flatMap { FlatTilesRenderer(device: device, tiles: $0) }
        self.ghost = ghost
        self.queue = queue
        self.depthPipeline = depthPipeline
        self.colorPipeline = colorPipeline
        self.pointPipeline = pointPipeline
        self.depthWrite = depthWrite
        self.depthEqual = depthEqual
        window = ScanOverview.enabled ? OverviewWindowRenderer(device: device) : nil
    }

    /// A perspective projection (Metal clip space), vertical field of view in radians.
    private static func perspective(fovY: Float, aspect: Float, near: Float, far: Float) -> simd_float4x4 {
        let y = 1 / tan(fovY / 2), x = y / aspect, z = far / (near - far)
        return simd_float4x4(SIMD4(x, 0, 0, 0), SIMD4(0, y, 0, 0), SIMD4(0, 0, z, -1), SIMD4(0, 0, z * near, 0))
    }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

    func draw(in view: MTKView) {
        // The wide overview: work out the camera first, since the backdrop (the clear colour) depends on it.
        var overviewView: (view: simd_float4x4, projection: simd_float4x4)?
        // (points-only: no pulled-back overview and no camera-window frame, the clean screen as on Android)
        if ScanOverview.enabled, !LivePreview.livePoints, window != nil, let frame = session?.currentFrame, view.bounds.width > 0 {
            let camera = frame.camera
            let realView = camera.viewMatrix(for: .portrait)
            let realProjection = camera.projectionMatrix(for: .portrait, viewportSize: view.bounds.size, zNear: 0.05, zFar: 50)
            let triangles = (meshRenderer != nil ? (cloud.count > 0 ? meshTriangles() : 0) : 0)
            let tracking: Bool = { if case .normal = camera.trackingState { return true }; return false }()
            let session = self.session
            let (v, fov) = overview.update(realView: realView, realFov: 2 * atan(1 / realProjection[1][1]),
                                           ready: tracking && triangles >= ScanOverview.minTriangles) { origin, dir in
                let query = ARRaycastQuery(origin: origin, direction: dir, allowing: .estimatedPlane, alignment: .any)
                guard let hit = session?.raycast(query).first else { return nil }
                let p = hit.worldTransform.columns.3
                return simd_distance(origin, SIMD3(p.x, p.y, p.z))
            }
            if overview.blend > 0 {
                let aspect = Float(view.bounds.width / view.bounds.height)
                overviewView = (v, Self.perspective(fovY: fov, aspect: aspect, near: 0.05, far: 50))
            }
            // The backdrop over the camera feed (zebra stripes, drawn below), fading in with the overview.
            view.clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 0)
        }
        guard let pass = view.currentRenderPassDescriptor,
              let drawable = view.currentDrawable,
              let commands = queue.makeCommandBuffer(),
              let encoder = commands.makeRenderCommandEncoder(descriptor: pass) else { return }
        let count = cloud.count
        if let camera = session?.currentFrame?.camera, view.bounds.width > 0 {
            // Same portrait, aspect-fill camera mapping RoomCaptureView uses for its camera image.
            let realView = camera.viewMatrix(for: .portrait)
            let realProjection = camera.projectionMatrix(for: .portrait, viewportSize: view.bounds.size, zNear: 0.05, zFar: 50)
            if LivePreview.showCamera {
                if overviewView != nil { window?.encodeZebra(encoder, alpha: overview.blend) }
                // The live preview: the camera picture dimmed, so only what's been scanned (and photographed) is bright.
                meshRenderer?.encodeDim(encoder, alpha: (1 - LivePreview.dim) * (overviewView != nil ? 1 - overview.blend : 1))
            } else {
                // No camera picture at all: only what was captured, on the zebra background (covers RoomPlan's view).
                window?.encodeZebra(encoder, alpha: 1)
            }
            let viewMatrix = overviewView?.view ?? realView
            let projection = overviewView?.projection ?? realProjection
            lastViewProjection = projection * viewMatrix
            // The live LiDAR mesh once ARKit has one; until then (or without it) the discs.
            // Continue scan never shows the discs: they'd cover the saved (blue) areas again.
            // In the wide overview the camera picture is projected onto the mesh from the phone's own position, so it
            // lines up with the scan from any viewpoint (rk 9 Nov: the flat picture showed things twice).
            var live: LiveMeshRenderer.LiveCamera?
            if LivePreview.showCamera, overviewView != nil, overview.blend > 0.5, let frame = session?.currentFrame,
               let got = window?.liveCamera(frame) {
                live = got.camera
                let hold = got.hold
                commands.addCompletedHandler { _ in _ = hold }
            }
            if LivePreview.livePoints {
                if count > 0, !ghost.isActive { drawDiscs(encoder, count: count, viewMatrix: viewMatrix, projection: projection) }
            } else if meshRenderer?.encode(encoder, viewProjection: projection * viewMatrix, live: live) != true, count > 0, !ghost.isActive {
                drawDiscs(encoder, count: count, viewMatrix: viewMatrix, projection: projection)
            }
            tilesRenderer?.encode(encoder, viewProjection: projection * viewMatrix)
            ghostRenderer?.encode(encoder, viewProjection: projection * viewMatrix)
            if overviewView != nil, let frame = session?.currentFrame {
                // Only the outline of what the phone sees now; the picture itself is on the mesh (above) once the
                // overview is mostly in.
                window?.encode(encoder, frame: frame, viewSize: view.bounds.size, realView: realView,
                               realProjection: realProjection, viewProjection: projection * viewMatrix, alpha: overview.blend,
                               image: LivePreview.showCamera && live == nil)
            }
        }
        encoder.endEncoding()
        if let perf {
            perf.frame()
            commands.addCompletedHandler { cb in perf.gpu(cb.gpuEndTime - cb.gpuStartTime) }
        }
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
            encoder.setRenderPipelineState(LivePreview.livePoints ? pointPipeline : colorPipeline)
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
        float3 color [[flat]];
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
        out.color = float3(p.color.rgb) / 255.0;
        return out;
    }

    // Points-only live view (rk 10 Nov, as on Android): each spot in the camera's colour, round, nearly opaque.
    fragment float4 pointFragment(TileOut in [[stage_in]]) {
        return float4(in.color, mix(0.75, 0.95, in.solid));
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
