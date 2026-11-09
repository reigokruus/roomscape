import ARKit
import CoreVideo
import Metal
import simd

/// "Wide overview" while scanning (rk 9 Nov): the iPhone's cameras can't scan with the ultrawide (ARKit offers only
/// the wide camera, see docs/ultrawide-probe-2026-11-09.txt), so the scan screen shows the room from a virtual camera
/// pulled back behind the phone, with a wider view: the live mesh around you, and the live camera image as a framed
/// window where the phone is looking. Starts as the normal camera view and eases into the overview once there's mesh.
enum ScanOverview {
    /// Turn the overview off here, if it's not liked: the scan screen is then the camera view as before.
    static let enabled = true

    /// How far behind and above the phone the virtual camera sits, its vertical field of view, and where the camera
    /// window is drawn in front of the phone (metres, degrees).
    static let pullBack: Float = 0.9, lift: Float = 0.2, fovDegrees: Float = 100, windowDistance: Float = 1.2
    /// Mesh triangles needed before easing into the overview, and how long the easing takes (seconds).
    static let minTriangles = 1500, easeSeconds: Double = 1.5
}

/// The virtual camera, smoothed from frame to frame (pulling back magnifies the phone's shake).
final class OverviewCamera {
    private var position: SIMD3<Float>?
    private var rotation = simd_quatf(angle: 0, axis: SIMD3(0, 1, 0))
    private var pull: Float = 0
    private var easeStart: CFTimeInterval?

    /// 0 = the real camera, 1 = the full overview.
    private(set) var blend: Float = 0

    /// The view matrix and field of view to draw with, given the real camera (portrait view matrix, its vertical field
    /// of view in radians), whether there is enough mesh yet, and a raycast that says how far back the space is free.
    func update(realView: simd_float4x4, realFov: Float, ready: Bool, freeBehind: (SIMD3<Float>, SIMD3<Float>) -> Float?)
        -> (view: simd_float4x4, fov: Float) {
        let now = CACurrentMediaTime()
        if ready, easeStart == nil { easeStart = now }
        if let easeStart {
            let t = Float(min(1, (now - easeStart) / ScanOverview.easeSeconds))
            blend = t * t * (3 - 2 * t)
        }
        let camToWorld = realView.inverse
        let realPos = SIMD3(camToWorld.columns.3.x, camToWorld.columns.3.y, camToWorld.columns.3.z)
        let back = simd_normalize(SIMD3(camToWorld.columns.2.x, camToWorld.columns.2.y, camToWorld.columns.2.z)) // camera +z: behind
        // Don't pull back through a wall behind you: stop 25 cm in front of what's scanned there.
        var wanted = ScanOverview.pullBack
        if let hit = freeBehind(realPos, back) { wanted = max(0, min(wanted, hit - 0.25)) }
        pull += (wanted - pull) * 0.2
        let target = realPos + back * pull * blend + SIMD3(0, ScanOverview.lift * blend, 0)
        let targetRot = simd_quatf(camToWorld)
        if let p = position {
            position = p + (target - p) * 0.35
            rotation = simd_slerp(rotation, targetRot, 0.35)
        } else {
            position = target
            rotation = targetRot
        }
        var m = simd_float4x4(rotation)
        m.columns.3 = SIMD4(position!, 1)
        let fov = realFov + (ScanOverview.fovDegrees * .pi / 180 - realFov) * blend
        return (m.inverse, fov)
    }

    func reset() { position = nil; pull = 0; easeStart = nil; blend = 0 }
}

/// Draws the dark backdrop and the live camera image as a framed window on the real camera's view, at
/// `windowDistance` in front of the phone.
final class OverviewWindowRenderer {
    private let quadPipeline: MTLRenderPipelineState
    private let linePipeline: MTLRenderPipelineState
    private let meshPipeline: MTLRenderPipelineState?
    private let noDepth: MTLDepthStencilState
    private var textureCache: CVMetalTextureCache?

    private struct Vertex { var position: SIMD4<Float>; var uv: SIMD2<Float> }

    init?(device: MTLDevice) {
        guard let library = try? device.makeLibrary(source: Self.shaderSource, options: nil),
              let vertex = library.makeFunction(name: "windowVertex"),
              let image = library.makeFunction(name: "windowImage"),
              let line = library.makeFunction(name: "windowLine") else { return nil }
        func pipeline(_ fragment: MTLFunction, blend: Bool, vertexFunction: MTLFunction? = nil) -> MTLRenderPipelineState? {
            let d = MTLRenderPipelineDescriptor()
            d.vertexFunction = vertexFunction ?? vertex
            d.fragmentFunction = fragment
            d.depthAttachmentPixelFormat = .depth32Float
            let c = d.colorAttachments[0]!
            c.pixelFormat = .bgra8Unorm
            c.isBlendingEnabled = blend
            c.sourceRGBBlendFactor = .sourceAlpha
            c.destinationRGBBlendFactor = .oneMinusSourceAlpha
            c.sourceAlphaBlendFactor = .one
            c.destinationAlphaBlendFactor = .oneMinusSourceAlpha
            return try? device.makeRenderPipelineState(descriptor: d)
        }
        let ds = MTLDepthStencilDescriptor()
        ds.depthCompareFunction = .always
        ds.isDepthWriteEnabled = false
        guard let quad = pipeline(image, blend: true), let lines = pipeline(line, blend: true),
              let noDepth = device.makeDepthStencilState(descriptor: ds) else { return nil }
        quadPipeline = quad
        linePipeline = lines
        if let meshLibrary = try? device.makeLibrary(source: Self.meshShaderSource, options: nil),
           let mv = meshLibrary.makeFunction(name: "windowMeshVertex"), let mf = meshLibrary.makeFunction(name: "windowMeshFragment") {
            meshPipeline = pipeline(mf, blend: true, vertexFunction: mv)
        } else {
            meshPipeline = nil
        }
        self.noDepth = noDepth
        CVMetalTextureCacheCreate(nil, nil, device, nil, &textureCache)
    }

    /// The window: the corners of what the normal scan screen shows, at `windowDistance`, textured with the frame.
    func encode(_ encoder: MTLRenderCommandEncoder, frame: ARFrame, viewSize: CGSize, realView: simd_float4x4,
                realProjection: simd_float4x4, viewProjection: simd_float4x4, alpha: Float) {
        guard alpha > 0.01, let cache = textureCache,
              let y = texture(frame.capturedImage, plane: 0, format: .r8Unorm, cache: cache),
              let cbcr = texture(frame.capturedImage, plane: 1, format: .rg8Unorm, cache: cache) else { return }
        // A point on the real camera's screen at the window's distance, in the world.
        let inv = (realProjection * realView).inverse
        let camToWorld = realView.inverse
        let eye = SIMD3(camToWorld.columns.3.x, camToWorld.columns.3.y, camToWorld.columns.3.z)
        func corner(_ nx: Float, _ ny: Float) -> SIMD4<Float> {
            let far = inv * SIMD4(nx, ny, 1, 1)
            let p = SIMD3(far.x, far.y, far.z) / far.w
            let dir = simd_normalize(p - eye)
            let forward = -simd_normalize(SIMD3(camToWorld.columns.2.x, camToWorld.columns.2.y, camToWorld.columns.2.z))
            let w = eye + dir * (ScanOverview.windowDistance / max(0.2, simd_dot(dir, forward)))
            return viewProjection * SIMD4(w, 1)
        }
        // Image coordinates for each screen corner (portrait, aspect-filled like the camera view).
        let toImage = frame.displayTransform(for: .portrait, viewportSize: viewSize).inverted()
        func uv(_ sx: CGFloat, _ sy: CGFloat) -> SIMD2<Float> {
            let p = CGPoint(x: sx, y: sy).applying(toImage)
            return SIMD2(Float(p.x), Float(p.y))
        }
        // Screen corners: normalised device (x right, y up) and view (x right, y down).
        var quad = [Vertex(position: corner(-1, 1), uv: uv(0, 0)), Vertex(position: corner(1, 1), uv: uv(1, 0)),
                    Vertex(position: corner(-1, -1), uv: uv(0, 1)), Vertex(position: corner(1, -1), uv: uv(1, 1))]
        var a = alpha
        encoder.setDepthStencilState(noDepth)
        encoder.setRenderPipelineState(quadPipeline)
        encoder.setVertexBytes(&quad, length: MemoryLayout<Vertex>.stride * quad.count, index: 0)
        encoder.setFragmentTexture(CVMetalTextureGetTexture(y), index: 0)
        encoder.setFragmentTexture(CVMetalTextureGetTexture(cbcr), index: 1)
        encoder.setFragmentBytes(&a, length: 4, index: 0)
        encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
        // A thin white frame around it.
        var outline = [quad[0], quad[1], quad[1], quad[3], quad[3], quad[2], quad[2], quad[0]]
        encoder.setRenderPipelineState(linePipeline)
        encoder.setVertexBytes(&outline, length: MemoryLayout<Vertex>.stride * outline.count, index: 0)
        encoder.setFragmentBytes(&a, length: 4, index: 0)
        encoder.drawPrimitives(type: .line, vertexStart: 0, vertexCount: outline.count)
    }

    private struct MeshUniforms {
        var realViewProjection: simd_float4x4
        var viewProjection: simd_float4x4
        var eye: SIMD4<Float>
        var forward: SIMD4<Float>
        var distance: Float
        var alpha: Float
    }

    /// The live mesh over the window, lighter and sparser (about every third edge, 30% white, orange where not solid
    /// yet): each point is placed where the real camera sees it on the window, so it lines up with the photo.
    func encodeMesh(_ encoder: MTLRenderCommandEncoder, mesh: LiveMesh.Buffers, realView: simd_float4x4,
                    realProjection: simd_float4x4, viewProjection: simd_float4x4, alpha: Float) {
        guard alpha > 0.01, let meshPipeline, mesh.edgeIndexCount > 0 else { return }
        let camToWorld = realView.inverse
        var u = MeshUniforms(realViewProjection: realProjection * realView, viewProjection: viewProjection,
                             eye: camToWorld.columns.3,
                             forward: SIMD4(-simd_normalize(SIMD3(camToWorld.columns.2.x, camToWorld.columns.2.y, camToWorld.columns.2.z)), 0),
                             distance: ScanOverview.windowDistance, alpha: alpha)
        encoder.setDepthStencilState(noDepth)
        encoder.setRenderPipelineState(meshPipeline)
        encoder.setVertexBuffer(mesh.vertices, offset: 0, index: 0)
        encoder.setVertexBytes(&u, length: MemoryLayout<MeshUniforms>.stride, index: 1)
        encoder.setFragmentBytes(&u, length: MemoryLayout<MeshUniforms>.stride, index: 0)
        encoder.drawIndexedPrimitives(type: .line, indexCount: mesh.edgeIndexCount, indexType: .uint32,
                                      indexBuffer: mesh.edges, indexBufferOffset: 0)
    }

    private func texture(_ buffer: CVPixelBuffer, plane: Int, format: MTLPixelFormat, cache: CVMetalTextureCache) -> CVMetalTexture? {
        var out: CVMetalTexture?
        CVMetalTextureCacheCreateTextureFromImage(nil, cache, buffer, nil, format, CVPixelBufferGetWidthOfPlane(buffer, plane),
                                                  CVPixelBufferGetHeightOfPlane(buffer, plane), plane, &out)
        return out
    }

    /// Separate, so that if it doesn't compile on a phone only the mesh over the window is missing, not the overview.
    private static let meshShaderSource = """
    #include <metal_stdlib>
    using namespace metal;
    struct MeshVertex { packed_float3 position; float confidence; };
    struct MeshUniforms { float4x4 realViewProjection; float4x4 viewProjection; float4 eye; float4 forward; float distance; float alpha; };
    struct MeshOut { float4 position [[position]]; float2 ndc; float solid; };
    vertex MeshOut windowMeshVertex(uint vid [[vertex_id]], const device MeshVertex *v [[buffer(0)]],
                                    constant MeshUniforms &u [[buffer(1)]]) {
        float3 p = float3(v[vid].position);
        float4 r = u.realViewProjection * float4(p, 1.0);
        float3 dir = normalize(p - u.eye.xyz);
        float3 w = u.eye.xyz + dir * (u.distance / max(0.2, dot(dir, u.forward.xyz)));
        MeshOut o;
        o.position = u.viewProjection * float4(w, 1.0);
        o.ndc = r.w > 0.0 ? r.xy / r.w : float2(9.0);
        o.solid = smoothstep(0.15, 0.6, v[vid].confidence);
        return o;
    }
    fragment float4 windowMeshFragment(MeshOut in [[stage_in]], constant MeshUniforms &u [[buffer(0)]],
                                       uint prim [[primitive_id]]) {
        // Only on the window, and only every third edge: a lighter grid that leaves the photo readable.
        if (any(abs(in.ndc) > 1.0) || prim % 3u != 0u) { discard_fragment(); }
        float3 c = mix(float3(1.0, 0.55, 0.15), float3(1.0), saturate(in.solid));
        return float4(c, 0.3 * u.alpha);
    }
    """

    private static let shaderSource = """
    #include <metal_stdlib>
    using namespace metal;
    struct V { float4 position; float2 uv; };
    struct Out { float4 position [[position]]; float2 uv; };
    vertex Out windowVertex(uint vid [[vertex_id]], const device V *v [[buffer(0)]]) {
        Out o; o.position = v[vid].position; o.uv = v[vid].uv; return o;
    }
    fragment float4 windowImage(Out in [[stage_in]], texture2d<float> y [[texture(0)]], texture2d<float> cbcr [[texture(1)]],
                                constant float &alpha [[buffer(0)]]) {
        constexpr sampler s(filter::linear, address::clamp_to_edge);
        float Y = y.sample(s, in.uv).r;
        float2 C = cbcr.sample(s, in.uv).rg - 0.5;
        float3 rgb = float3(Y + 1.402 * C.y, Y - 0.344136 * C.x - 0.714136 * C.y, Y + 1.772 * C.x);
        return float4(saturate(rgb), alpha);
    }
    fragment float4 windowLine(Out in [[stage_in]], constant float &alpha [[buffer(0)]]) {
        return float4(1.0, 1.0, 1.0, 0.9 * alpha);
    }

    """
}
