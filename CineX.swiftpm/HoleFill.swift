import SceneKit
import simd

/// A best guess for the surfaces a removed (or moved) object hid (rk 9 Nov): the floor under its footprint and the
/// wall right behind it. Each patch is a grid of coloured triangles just in front of the surface, coloured from a band
/// of real scan points around the hole, blended across it, so wall gradients and floorboards roughly carry on.
///
/// Generated, never scanned: not saved, not exported, drawn only while the object is away from its spot. The same
/// rules and numbers as the Android app's (HoleFill.java).
enum HoleFill {
    /// Grid step, margin around the hole, how far out the colour band reaches, and how close a wall must be.
    static let step: Float = 0.03, margin: Float = 0.05, band: Float = 0.15, wallGap: Float = 0.15

    struct Point { var p: SIMD3<Float>; var c: SIMD3<Float> }

    /// The fill for a box (centre, its own x axis on the floor `u`, half width / depth, bottom and top).
    static func build(_ points: [Point], center: SIMD2<Float>, u: SIMD2<Float>, hu: Float, hv: Float,
                      y0: Float, y1: Float, floorY: Float) -> (positions: [SIMD3<Float>], colors: [SIMD3<Float>]) {
        var pos: [SIMD3<Float>] = [], col: [SIMD3<Float>] = []
        let v = SIMD2(-u.y, u.x)
        if y0 - floorY < 0.12 {
            floorPatch(points, center, u, v, hu + margin, hv + margin, floorY, &pos, &col)
        }
        for (n, h, w, l) in [(u, hu, hv, v), (-u, hu, hv, v), (v, hv, hu, u), (-v, hv, hu, u)] {
            wallPatch(points, center, n, h, w, l, y1, floorY, &pos, &col)
        }
        return (pos, col)
    }

    private static func floorPatch(_ pts: [Point], _ c: SIMD2<Float>, _ u: SIMD2<Float>, _ v: SIMD2<Float>, _ a: Float, _ b: Float,
                                   _ floorY: Float, _ pos: inout [SIMD3<Float>], _ col: inout [SIMD3<Float>]) {
        let nu = max(2, Int((2 * a / step).rounded(.up)) + 1), nv = max(2, Int((2 * b / step).rounded(.up)) + 1)
        var negV = Profile(nu), posV = Profile(nu), negU = Profile(nv), posU = Profile(nv)
        for q in pts where abs(q.p.y - floorY) <= 0.04 {
            let d = SIMD2(q.p.x, q.p.z) - c
            let pu = simd_dot(d, u), pv = simd_dot(d, v)
            if abs(pu) <= a && abs(pv) <= b { continue }
            if abs(pu) > a + band || abs(pv) > b + band { continue }
            let iu = bin(pu, -a, a, nu), iv = bin(pv, -b, b, nv)
            if abs(pu) <= a { if pv < 0 { negV.add(iu, q.c) } else { posV.add(iu, q.c) } }
            if abs(pv) <= b { if pu < 0 { negU.add(iv, q.c) } else { posU.add(iv, q.c) } }
        }
        guard let avg = average([negV, posV, negU, posU]) else { return }
        negV.fill(avg); posV.fill(avg); negU.fill(avg); posU.fill(avg)
        var corner: [SIMD3<Float>] = [], colour: [SIMD3<Float>] = []
        for j in 0..<nv {
            for i in 0..<nu {
                let su = Float(i) / Float(nu - 1), sv = Float(j) / Float(nv - 1)
                let p = c + u * (-a + 2 * a * su) + v * (-b + 2 * b * sv)
                corner.append(SIMD3(p.x, floorY + 0.004, p.y))
                colour.append((simd_mix(negV.c[i], posV.c[i], SIMD3(repeating: sv)) + simd_mix(negU.c[j], posU.c[j], SIMD3(repeating: su))) / 2)
            }
        }
        grid(corner, colour, nu, nv, &pos, &col)
    }

    private static func wallPatch(_ pts: [Point], _ c: SIMD2<Float>, _ n: SIMD2<Float>, _ h: Float, _ w: Float, _ l: SIMD2<Float>,
                                  _ y1: Float, _ floorY: Float, _ pos: inout [SIMD3<Float>], _ col: inout [SIMD3<Float>]) {
        let top = y1 + margin, half = w + margin
        var gaps: [Float] = []
        for q in pts {
            let d = SIMD2(q.p.x, q.p.z) - c
            let t = simd_dot(d, n) - h, s = simd_dot(d, l)
            if t < 0 || t > wallGap || abs(s) > half || q.p.y < floorY + 0.05 || q.p.y > top { continue }
            gaps.append(t)
        }
        guard gaps.count >= 120 else { return }
        gaps.sort()
        let t0 = gaps[gaps.count / 2]
        guard Float(gaps.filter { abs($0 - t0) < 0.04 }.count) >= 0.7 * Float(gaps.count) else { return }
        let nl = max(2, Int((2 * half / step).rounded(.up)) + 1), ny = max(2, Int(((top - floorY) / step).rounded(.up)) + 1)
        var left = Profile(ny), right = Profile(ny), above = Profile(nl)
        for q in pts where q.p.y >= floorY {
            let d = SIMD2(q.p.x, q.p.z) - c
            let t = simd_dot(d, n) - h, s = simd_dot(d, l)
            if abs(t - t0) > 0.04 { continue }
            let inside = abs(s) <= half && q.p.y <= top
            if inside || abs(s) > half + band || q.p.y > top + band { continue }
            if q.p.y <= top {
                let iy = bin(q.p.y, floorY, top, ny)
                if s < 0 { left.add(iy, q.c) } else { right.add(iy, q.c) }
            } else if abs(s) <= half {
                above.add(bin(s, -half, half, nl), q.c)
            }
        }
        guard let avg = average([left, right, above]) else { return }
        left.fill(avg); right.fill(avg); above.fill(avg)
        let dist = h + t0 - 0.006
        var corner: [SIMD3<Float>] = [], colour: [SIMD3<Float>] = []
        for j in 0..<ny {
            for i in 0..<nl {
                let sl = Float(i) / Float(nl - 1), sy = Float(j) / Float(ny - 1)
                let p = c + n * dist + l * (-half + 2 * half * sl)
                corner.append(SIMD3(p.x, floorY + (top - floorY) * sy, p.y))
                let row = simd_mix(left.c[j], right.c[j], SIMD3(repeating: sl))
                colour.append(above.count > 0 ? simd_mix(row, above.c[i], SIMD3(repeating: sy * sy * 0.5)) : row)
            }
        }
        grid(corner, colour, nl, ny, &pos, &col)
    }

    private static func grid(_ corner: [SIMD3<Float>], _ colour: [SIMD3<Float>], _ nu: Int, _ nv: Int,
                             _ pos: inout [SIMD3<Float>], _ col: inout [SIMD3<Float>]) {
        for j in 0..<(nv - 1) {
            for i in 0..<(nu - 1) {
                let a = j * nu + i, b = a + 1, c = a + nu, d = c + 1
                for k in [a, b, d, a, d, c] { pos.append(corner[k]); col.append(colour[k]) }
            }
        }
    }

    /// Average colours (0…1) in bins along an edge; empty bins take the nearest filled one.
    private struct Profile {
        var c: [SIMD3<Float>]
        var k: [Int]
        var count = 0
        init(_ bins: Int) { c = Array(repeating: .zero, count: bins); k = Array(repeating: 0, count: bins) }
        mutating func add(_ b: Int, _ colour: SIMD3<Float>) { c[b] += colour; k[b] += 1; count += 1 }
        mutating func fill(_ fallback: SIMD3<Float>) {
            for b in k.indices where k[b] > 0 { c[b] /= Float(k[b]) }
            let filled = c
            for b in k.indices where k[b] == 0 {
                var best: Int?
                var d = 1
                while best == nil && d < k.count {
                    if b - d >= 0, k[b - d] > 0 { best = b - d } else if b + d < k.count, k[b + d] > 0 { best = b + d }
                    d += 1
                }
                c[b] = best.map { filled[$0] } ?? fallback
            }
        }
    }

    private static func average(_ ps: [Profile]) -> SIMD3<Float>? {
        var s = SIMD3<Float>.zero, n = 0
        for p in ps { for b in p.k.indices { s += p.c[b]; n += p.k[b] } }
        return n < 20 ? nil : s / Float(n)
    }

    private static func bin(_ x: Float, _ lo: Float, _ hi: Float, _ bins: Int) -> Int {
        max(0, min(bins - 1, Int(((x - lo) / (hi - lo) * Float(bins - 1)).rounded())))
    }

    // MARK: Scene

    /// The fill as a SceneKit node: vertex colours, unlit, both sides.
    static func node(positions: [SIMD3<Float>], colors: [SIMD3<Float>]) -> SCNNode {
        let g = SCNGeometry(sources: [SCNGeometrySource(vertices: positions.map { SCNVector3($0) }),
                                      SCNGeometrySource(data: colors.withUnsafeBytes { Data($0) }, semantic: .color,
                                                        vectorCount: colors.count, usesFloatComponents: true,
                                                        componentsPerVector: 3, bytesPerComponent: 4, dataOffset: 0,
                                                        dataStride: MemoryLayout<SIMD3<Float>>.stride)],
                            elements: [SCNGeometryElement(indices: (0..<Int32(positions.count)).map { $0 }, primitiveType: .triangles)])
        let m = SCNMaterial()
        m.lightingModel = .constant
        m.diffuse.contents = UIColor.white
        m.isDoubleSided = true
        g.materials = [m]
        return SCNNode(geometry: g)
    }

    /// Hides the photo room inside up to 8 boxes (the spots removed or moved objects left), from 3 cm above their
    /// bottom, so the floor under them stays: what the Android app does with its hide boxes.
    static let hideShader = """
    #pragma arguments
    float4x4 hideBox0; float4x4 hideBox1; float4x4 hideBox2; float4x4 hideBox3;
    float4x4 hideBox4; float4x4 hideBox5; float4x4 hideBox6; float4x4 hideBox7;
    float hideCount;
    #pragma body
    if (hideCount > 0.5) {
        float4 wp = scn_frame.inverseViewTransform * float4(_surface.position, 1.0);
        float4x4 boxes[8] = { hideBox0, hideBox1, hideBox2, hideBox3, hideBox4, hideBox5, hideBox6, hideBox7 };
        for (int i = 0; i < 8; i++) {
            if (float(i) >= hideCount) { break; }
            float3 q = (boxes[i] * wp).xyz;
            if (all(abs(q) <= float3(0.5))) { discard_fragment(); }
        }
    }
    """

    /// The world-to-unit-box transform of a hide box: the object's original box from 3 cm above its bottom, 3 cm bigger
    /// around (the margin the splats use).
    static func hideBox(_ o: RoomObject) -> SCNMatrix4 {
        let bottom = o.center.y - o.halfExtents.y + 0.03, top = o.center.y + o.halfExtents.y + 0.03
        let size = SIMD3(o.halfExtents.x * 2 + 0.06, top - bottom, o.halfExtents.z * 2 + 0.06)
        var t = simd_float4x4(simd_quatf(angle: o.yaw, axis: SIMD3(0, 1, 0)))
        t.columns.3 = SIMD4(o.center.x, (bottom + top) / 2, o.center.z, 1)
        return SCNMatrix4((t * simd_float4x4(diagonal: SIMD4(size, 1))).inverse)
    }
}
