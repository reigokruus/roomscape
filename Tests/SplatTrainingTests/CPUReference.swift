import Foundation
import simd
@testable import SplatTraining

/// Android's `Trainer.step` forward and backward pass, line for line on the CPU, as the reference the GPU
/// version is checked against. Returns the loss and the raw per-splat gradients in the GPU's layout:
/// colour r, g, b, opacity, sigma, u, v, (unused).
enum CPUReference {
    static func lossAndGradients(_ splats: [SplatTrainer.GPUSplat], view v: SplatTrainer.View, bg: Float = SplatTrainer.background)
        -> (loss: Float, grads: [Float]) {
        let n = splats.count, W = v.width, H = v.height, np = W * H
        let m = v.worldToCamera
        var su = [Float](repeating: 0, count: n), sv = su, ss = su, so = su
        var order: [(d: Float, i: Int)] = []
        for i in 0..<n {
            let s = splats[i]
            let c = m * SIMD4(s.bx + s.ox, s.by + s.oy, s.bz + s.oz, 1)
            let d = -c.z
            if d < 0.15 { continue }
            let u = v.fx * c.x / d + v.cx, w = v.cy - v.fy * c.y / d
            let sig = max(0.35, s.rad0 * exp(s.ls) * v.fx / d * 0.6)
            if u < -3 * sig || u > Float(W) + 3 * sig || w < -3 * sig || w > Float(H) + 3 * sig || sig > Float(W) * 0.25 { continue }
            su[i] = u; sv[i] = w; ss[i] = sig; so[i] = 1 / (1 + exp(-s.a))
            order.append((d, i))
        }
        order.sort { $0.d < $1.d || ($0.d == $1.d && $0.i < $1.i) }
        let vis = order.map(\.i)
        var pT = [Float](repeating: 1, count: np), pC = [Float](repeating: 0, count: np * 3), dR = pC
        var pLast = [Int](repeating: -1, count: np)
        func box(_ i: Int) -> (Int, Int, Int, Int) {
            (max(0, Int(su[i] - 3 * ss[i])), min(W - 1, Int(su[i] + 3 * ss[i])),
             max(0, Int(sv[i] - 3 * ss[i])), min(H - 1, Int(sv[i] + 3 * ss[i])))
        }
        for (k, i) in vis.enumerated() {
            let s = splats[i], u = su[i], w = sv[i], sig = ss[i], o = so[i], inv = 1 / (2 * sig * sig)
            let (x0, x1, y0, y1) = box(i)
            if x0 > x1 || y0 > y1 { continue }
            for py in y0...y1 {
                let dy = Float(py) + 0.5 - w
                for px in x0...x1 {
                    let p = py * W + px
                    let T = pT[p]
                    if T < 1e-3 { continue }
                    let dx = Float(px) + 0.5 - u, g = exp(-(dx * dx + dy * dy) * inv)
                    let al = min(0.99, o * g)
                    if al < 1 / 255 { continue }
                    pC[p * 3] += T * al * s.r; pC[p * 3 + 1] += T * al * s.g; pC[p * 3 + 2] += T * al * s.b
                    pT[p] = T * (1 - al); pLast[p] = k
                }
            }
        }
        var loss: Double = 0
        let norm = 2 / Float(np)
        for p in 0..<np {
            let T = pT[p]
            for ch in 0..<3 {
                let e = pC[p * 3 + ch] + T * bg - Float(v.rgba[p * 4 + ch]) / 255
                loss += Double(e * e)
                dR[p * 3 + ch] = e * norm
                pC[p * 3 + ch] = T * bg
            }
        }
        var grads = [Float](repeating: 0, count: n * 8)
        for k in stride(from: vis.count - 1, through: 0, by: -1) {
            let i = vis[k]
            let s = splats[i], u = su[i], w = sv[i], sig = ss[i], o = so[i], inv = 1 / (2 * sig * sig)
            var gr: Float = 0, gg: Float = 0, gb: Float = 0, go: Float = 0, gs: Float = 0, gu: Float = 0, gv: Float = 0
            let (x0, x1, y0, y1) = box(i)
            if x0 > x1 || y0 > y1 { continue }
            for py in y0...y1 {
                let dy = Float(py) + 0.5 - w
                for px in x0...x1 {
                    let p = py * W + px
                    if k > pLast[p] { continue }
                    let dx = Float(px) + 0.5 - u, r2 = dx * dx + dy * dy, g = exp(-r2 * inv)
                    let raw = o * g, al = min(0.99, raw)
                    if al < 1 / 255 { continue }
                    let Ti = pT[p] / (1 - al), wgt = al * Ti
                    let d0 = dR[p * 3], d1 = dR[p * 3 + 1], d2 = dR[p * 3 + 2]
                    gr += d0 * wgt; gg += d1 * wgt; gb += d2 * wgt
                    let ia = 1 / (1 - al)
                    let dAl = d0 * (Ti * s.r - pC[p * 3] * ia) + d1 * (Ti * s.g - pC[p * 3 + 1] * ia) + d2 * (Ti * s.b - pC[p * 3 + 2] * ia)
                    pC[p * 3] += wgt * s.r; pC[p * 3 + 1] += wgt * s.g; pC[p * 3 + 2] += wgt * s.b
                    pT[p] = Ti
                    if raw >= 0.99 { continue }
                    go += dAl * g
                    let dG = dAl * o
                    gs += dG * g * r2 / (sig * sig * sig)
                    gu += dG * g * dx / (sig * sig)
                    gv += dG * g * dy / (sig * sig)
                }
            }
            let b = i * 8
            grads[b] = gr; grads[b + 1] = gg; grads[b + 2] = gb; grads[b + 3] = go
            grads[b + 4] = gs; grads[b + 5] = gu; grads[b + 6] = gv
        }
        return (Float(loss / Double(np)), grads)
    }
}
