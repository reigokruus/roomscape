import CoreVideo
import Metal
import MetalKit
import simd

/// The experimental scan's screen: the ultrawide picture, upright and filling the screen, with the live mesh (the
/// app's `LiveMeshRenderer`, used as is) drawn from where the ultrawide is.
final class UltraDisplayView: MTKView, MTKViewDelegate {
    var capture: UltraCapture?
    /// The depth camera's pose (camera coordinates x right, y down, z forward, to the world).
    var poseSource: (() -> simd_float4x4)?

    private let queue: MTLCommandQueue
    private let imagePipeline: MTLRenderPipelineState
    private let noDepth: MTLDepthStencilState
    private let meshRenderer: LiveMeshRenderer?
    private var cache: CVMetalTextureCache?

    init?(device: MTLDevice, mesh: LiveMesh) {
        guard let queue = device.makeCommandQueue(),
              let library = try? device.makeLibrary(source: Self.shader, options: nil),
              let v = library.makeFunction(name: "ultraImageVertex"), let f = library.makeFunction(name: "ultraImageFragment") else { return nil }
        let d = MTLRenderPipelineDescriptor()
        d.vertexFunction = v
        d.fragmentFunction = f
        d.colorAttachments[0].pixelFormat = .bgra8Unorm
        d.depthAttachmentPixelFormat = .depth32Float
        let ds = MTLDepthStencilDescriptor()
        ds.depthCompareFunction = .always
        ds.isDepthWriteEnabled = false
        guard let pipeline = try? device.makeRenderPipelineState(descriptor: d), let nd = device.makeDepthStencilState(descriptor: ds) else { return nil }
        self.queue = queue
        imagePipeline = pipeline
        noDepth = nd
        meshRenderer = LiveMeshRenderer(device: device, mesh: mesh)
        super.init(frame: .zero, device: device)
        colorPixelFormat = .bgra8Unorm
        depthStencilPixelFormat = .depth32Float
        clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
        preferredFramesPerSecond = 30
        isUserInteractionEnabled = false
        CVMetalTextureCacheCreate(nil, nil, device, nil, &cache)
        delegate = self
    }

    required init(coder: NSCoder) { fatalError("not supported") }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

    private struct ImageUniforms { var scale: Float }

    func draw(in view: MTKView) {
        guard let pass = currentRenderPassDescriptor, let drawable = currentDrawable,
              let commands = queue.makeCommandBuffer(), let encoder = commands.makeRenderCommandEncoder(descriptor: pass) else { return }
        if let capture, let video = capture.latestVideo, let k = capture.ultraIntrinsics, let cache, bounds.height > 0 {
            var texture: CVMetalTexture?
            CVMetalTextureCacheCreateTextureFromImage(nil, cache, video.buffer, nil, .bgra8Unorm,
                                                      CVPixelBufferGetWidth(video.buffer), CVPixelBufferGetHeight(video.buffer), 0, &texture)
            // The landscape picture turned upright (90° clockwise) and filling the screen; s widens it to crop the sides.
            let imageAspect = Float(k.height) / Float(k.width), screenAspect = Float(bounds.width / bounds.height)
            var u = ImageUniforms(scale: imageAspect / screenAspect)
            if let texture, let t = CVMetalTextureGetTexture(texture) {
                encoder.setRenderPipelineState(imagePipeline)
                encoder.setDepthStencilState(noDepth)
                encoder.setVertexBytes(&u, length: MemoryLayout<ImageUniforms>.stride, index: 0)
                encoder.setFragmentTexture(t, index: 0)
                encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
            }
            // The mesh from the ultrawide: its pose, its lens, and the same turn and crop as the picture.
            if let pose = poseSource?() {
                let view = (pose * capture.ultraToWide).inverse
                let w = Float(k.width), h = Float(k.height), n: Float = 0.05, fz: Float = 50
                let projection = simd_float4x4(rows: [
                    SIMD4(2 * k.fx / w, 0, 2 * k.cx / w - 1, 0),
                    SIMD4(0, -2 * k.fy / h, -(2 * k.cy / h - 1), 0),
                    SIMD4(0, 0, fz / (fz - n), -fz * n / (fz - n)),
                    SIMD4(0, 0, 1, 0)])
                let turn = simd_float4x4(rows: [SIMD4(0, u.scale, 0, 0), SIMD4(-1, 0, 0, 0), SIMD4(0, 0, 1, 0), SIMD4(0, 0, 0, 1)])
                _ = meshRenderer?.encode(encoder, viewProjection: turn * projection * view)
            }
        }
        encoder.endEncoding()
        commands.present(drawable)
        commands.commit()
    }

    private static let shader = """
    #include <metal_stdlib>
    using namespace metal;
    struct U { float scale; };
    struct Out { float4 position [[position]]; float2 uv; };
    vertex Out ultraImageVertex(uint vid [[vertex_id]], constant U &u [[buffer(0)]]) {
        float2 p = float2((vid & 1) ? 1.0 : -1.0, (vid & 2) ? 1.0 : -1.0); // screen corner
        // Screen (X, Y) shows picture point (x, y) = (-Y, X / scale), in the picture's own -1…1 coordinates.
        float2 q = float2(-p.y, p.x / u.scale);
        Out o;
        o.position = float4(p, 0.0, 1.0);
        o.uv = float2((q.x + 1.0) * 0.5, (1.0 - q.y) * 0.5);
        return o;
    }
    fragment float4 ultraImageFragment(Out in [[stage_in]], texture2d<float> t [[texture(0)]]) {
        constexpr sampler s(filter::linear, address::clamp_to_edge);
        return float4(t.sample(s, in.uv).rgb, 1.0);
    }
    """
}
