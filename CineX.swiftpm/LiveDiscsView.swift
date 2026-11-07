import ARKit
import MetalKit
import SwiftUI
import os

private let log = Logger(subsystem: "CineX", category: "livediscs")

/// Transparent Metal view laid over RoomPlan's camera view. Every display frame it feeds the AR session's latest
/// frame to the `LiveCloud` and draws each cloud point as a disc:
/// the rim is a heat colour (red → orange → yellow → green as the spot is seen more), the core is the point's own
/// colour, and solid spots shrink to a faint green dot so the spots still needing a look stand out.
final class LiveDiscsMTKView: MTKView, MTKViewDelegate {
    private let cloud: LiveCloud
    private weak var session: ARSession?
    private let queue: MTLCommandQueue
    private let pipeline: MTLRenderPipelineState
    private let depthState: MTLDepthStencilState

    private struct Uniforms {
        var mvp: simd_float4x4
        var size: Float
        var pad0: Float = 0, pad1: Float = 0, pad2: Float = 0
    }

    init?(cloud: LiveCloud, session: ARSession) {
        let device = cloud.buffer.device
        guard let queue = device.makeCommandQueue() else { return nil }
        let library: MTLLibrary
        do {
            library = try device.makeLibrary(source: Self.shaderSource, options: nil)
        } catch {
            log.error("disc shader failed to compile: \(String(describing: error), privacy: .public)")
            return nil
        }
        let desc = MTLRenderPipelineDescriptor()
        desc.vertexFunction = library.makeFunction(name: "discVertex")
        desc.fragmentFunction = library.makeFunction(name: "discFragment")
        let color = desc.colorAttachments[0]!
        color.pixelFormat = .bgra8Unorm
        color.isBlendingEnabled = true
        color.rgbBlendOperation = .add
        color.alphaBlendOperation = .add
        color.sourceRGBBlendFactor = .sourceAlpha
        color.destinationRGBBlendFactor = .oneMinusSourceAlpha
        color.sourceAlphaBlendFactor = .one
        color.destinationAlphaBlendFactor = .oneMinusSourceAlpha
        desc.depthAttachmentPixelFormat = .depth32Float
        guard let pipeline = try? device.makeRenderPipelineState(descriptor: desc) else { return nil }
        let depthDesc = MTLDepthStencilDescriptor()
        depthDesc.depthCompareFunction = .less
        depthDesc.isDepthWriteEnabled = true
        guard let depthState = device.makeDepthStencilState(descriptor: depthDesc) else { return nil }

        self.cloud = cloud
        self.session = session
        self.queue = queue
        self.pipeline = pipeline
        self.depthState = depthState
        super.init(frame: .zero, device: device)
        colorPixelFormat = .bgra8Unorm
        depthStencilPixelFormat = .depth32Float
        clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 0)
        clearDepth = 1
        isOpaque = false
        layer.isOpaque = false
        backgroundColor = .clear
        isUserInteractionEnabled = false
        preferredFramesPerSecond = 60
        delegate = self
    }

    required init(coder: NSCoder) { fatalError("not supported") }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

    func draw(in view: MTKView) {
        guard let session else { return }
        cloud.tick(session: session)
        guard let frame = session.currentFrame,
              let pass = currentRenderPassDescriptor,
              let drawable = currentDrawable,
              let commands = queue.makeCommandBuffer(),
              let encoder = commands.makeRenderCommandEncoder(descriptor: pass) else { return }

        let count = cloud.stats.count
        let viewport = bounds.size
        if count > 0, viewport.width > 0, viewport.height > 0 {
            // Same portrait, aspect-fill framing RoomPlan uses for its camera image.
            let proj = frame.camera.projectionMatrix(for: .portrait, viewportSize: viewport, zNear: 0.05, zFar: 20)
            let viewMatrix = frame.camera.viewMatrix(for: .portrait)
            // The camera's focal length in drawable pixels, so a 2 cm cell gets its true on-screen size.
            let pxPerMeter = proj[1][1] * Float(drawableSize.height) / 2
            var uniforms = Uniforms(mvp: proj * viewMatrix, size: LiveCloud.voxel * 1.6 * pxPerMeter)
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

    struct Point { packed_float3 pos; uint rgbs; };
    struct Uniforms { float4x4 mvp; float size; float pad0; float pad1; float pad2; };
    struct VOut {
        float4 position [[position]];
        float pointSize [[point_size]];
        float4 color;
    };

    vertex VOut discVertex(uint vid [[vertex_id]],
                           const device Point* pts [[buffer(0)]],
                           constant Uniforms& u [[buffer(1)]]) {
        Point p = pts[vid];
        VOut o;
        o.position = u.mvp * float4(float3(p.pos), 1.0);
        float w = max(o.position.w, 0.05);
        float4 c = float4(float(p.rgbs & 0xFF), float((p.rgbs >> 8) & 0xFF),
                          float((p.rgbs >> 16) & 0xFF), float(p.rgbs >> 24)) / 255.0;
        float solid = step(0.99, c.a);
        // Red discs start big and visibly shrink with every new look; solid ones become small dots.
        float grow = solid > 0.5 ? 0.4 : mix(1.25, 0.7, c.a);
        o.pointSize = clamp(u.size / w * grow, 2.0, 96.0);
        o.color = c;
        // Beyond 5 m (or behind the camera) there is nothing useful to show.
        if (o.position.w > 5.0 || o.position.w < 0.05) { o.position = float4(2.0, 2.0, 2.0, 1.0); }
        return o;
    }

    fragment float4 discFragment(VOut in [[stage_in]], float2 pc [[point_coord]]) {
        float2 d = pc * 2.0 - 1.0;
        float r2 = dot(d, d);
        if (r2 > 1.0) discard_fragment();
        // Four clear steps (red, orange, yellow, green) so progress pops.
        float s = floor(in.color.a * 4.0) / 4.0;
        if (s > 0.99) return float4(0.15, 1.0, 0.35, 0.35);
        float3 red = float3(1.0, 0.15, 0.1), yellow = float3(1.0, 0.85, 0.1), green = float3(0.15, 1.0, 0.35);
        float3 heat = s < 0.5 ? mix(red, yellow, s * 2.0) : mix(yellow, green, s * 2.0 - 1.0);
        if (r2 > 0.55) return float4(heat, 1.0);
        return float4(mix(in.color.rgb, heat, 0.25), 0.8);
    }
    """
}

struct LiveDiscsOverlay: UIViewRepresentable {
    let cloud: LiveCloud
    let session: ARSession

    func makeUIView(context: Context) -> UIView {
        LiveDiscsMTKView(cloud: cloud, session: session) ?? UIView()
    }

    func updateUIView(_ uiView: UIView, context: Context) {}
}
