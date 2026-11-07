/// Metal compute kernels for `SplatTrainer`, compiled from source at run time (the app is a Swift package).
///
/// One training step on one photo:
/// 1. `project`: every splat → its screen ellipse (centre, gaussian sigma, opacity, depth). Visible ones are appended
///    to `ids` with their depth; the CPU puts them in front-to-back `order`.
/// 2. `forward`: one threadgroup per screen tile, one thread per pixel. The tile walks the sorted splats in batches,
///    keeps the ones whose 3-sigma box touches the tile, and every pixel blends them front to back
///    (Trainer.java's forward pass). Each pixel stores its final transmittance, last contributing splat, and the
///    error against the photo.
/// 3. `backward`: the same walk back to front, recovering each splat's share of every pixel, so every splat gets the
///    gradient of the squared error for its colour, opacity, size and screen position (summed per tile, then added
///    to the splat with float atomics).
/// 4. `adam`: per visible splat, turn those into gradients for the trainable parameters (screen position → 3D offset
///    through the camera) and take an Adam step.
enum SplatTrainerShaders {
    static let source = """
    #include <metal_stdlib>
    using namespace metal;

    struct Splat { float bx, by, bz, rad0, ox, oy, oz, ls, r, g, b, a; };
    struct Proj { float u, v, sig, o, d, xc, yc, pad; };
    struct StepParams {
        float4x4 view;
        float fx, fy, cu, cv;
        uint width, height, tileW, tileH;
        uint n, nv, imageOffset, pad0;
        float bg, maxT, c1, c2;
    };

    constant uint MAX_BATCH = 256;

    kernel void project(constant StepParams& P [[buffer(0)]],
                        device const Splat* S [[buffer(1)]],
                        device Proj* proj [[buffer(2)]],
                        device uint* ids [[buffer(3)]],
                        device atomic_uint* count [[buffer(4)]],
                        device float* depths [[buffer(11)]],
                        uint i [[thread_position_in_grid]]) {
        if (i >= P.n) return;
        Splat s = S[i];
        float4 c = P.view * float4(s.bx + s.ox, s.by + s.oy, s.bz + s.oz, 1.0);
        float d = -c.z;
        if (d < 0.15) return;
        float u = P.fx * c.x / d + P.cu, w = P.cv - P.fy * c.y / d;
        float sig = max(0.35f, s.rad0 * exp(s.ls) * P.fx / d * 0.6f);
        float W = float(P.width), H = float(P.height);
        if (u < -3 * sig || u > W + 3 * sig || w < -3 * sig || w > H + 3 * sig || sig > W * 0.25) return;
        Proj p;
        p.u = u; p.v = w; p.sig = sig; p.o = 1.0 / (1.0 + exp(-s.a)); p.d = d; p.xc = c.x; p.yc = c.y; p.pad = 0;
        proj[i] = p;
        uint k = atomic_fetch_add_explicit(count, 1, memory_order_relaxed);
        ids[k] = i;
        depths[k] = d;
    }

    // The pixel box a splat covers: Trainer.java's (int)(u ± 3 sigma), clamped to the image.
    static int4 pixelBox(Proj p, constant StepParams& P) {
        return int4(max(0, int(p.u - 3 * p.sig)), min(int(P.width) - 1, int(p.u + 3 * p.sig)),
                    max(0, int(p.v - 3 * p.sig)), min(int(P.height) - 1, int(p.v + 3 * p.sig)));
    }

    // Loads splats [base, base + B) of the sorted list and keeps, in order, the ones that touch this tile.
    // Returns how many were kept. Must be called by the whole threadgroup.
    static uint loadBatch(uint base, uint lid, uint lane, uint sg, uint nsg, int4 tile,
                          constant StepParams& P, device const Splat* S, device const Proj* proj,
                          device const uint* order,
                          threadgroup float4* shP, threadgroup float4* shC, threadgroup int4* shBox,
                          threadgroup int* shK, threadgroup uint* shId, threadgroup uint* shCount) {
        uint k = base + lid;
        bool hit = false;
        uint id = 0;
        Proj p;
        int4 box = int4(0);
        if (k < P.nv) {
            id = order[k];
            p = proj[id];
            box = pixelBox(p, P);
            hit = box.x <= tile.y && box.y >= tile.x && box.z <= tile.w && box.w >= tile.z;
        }
        uint h = hit ? 1 : 0;
        uint pre = simd_prefix_exclusive_sum(h);
        uint tot = simd_sum(h);
        if (lane == 0) shCount[sg] = tot;
        threadgroup_barrier(mem_flags::mem_threadgroup);
        uint off = 0, total = 0;
        for (uint i = 0; i < nsg; i++) { uint c = shCount[i]; if (i < sg) off += c; total += c; }
        if (hit) {
            uint j = off + pre;
            Splat s = S[id];
            shP[j] = float4(p.u, p.v, p.sig, p.o);
            shC[j] = float4(s.r, s.g, s.b, 0);
            shBox[j] = box;
            shK[j] = int(k);
            shId[j] = id;
        }
        threadgroup_barrier(mem_flags::mem_threadgroup);
        return total;
    }

    kernel void forward(constant StepParams& P [[buffer(0)]],
                        device const Splat* S [[buffer(1)]],
                        device const Proj* proj [[buffer(2)]],
                        device const uint* order [[buffer(3)]],
                        device const uchar4* images [[buffer(4)]],
                        device float* pixT [[buffer(5)]],
                        device int* pixLast [[buffer(6)]],
                        device float4* pixDR [[buffer(7)]],
                        uint2 tg [[threadgroup_position_in_grid]],
                        uint2 lt [[thread_position_in_threadgroup]],
                        uint lid [[thread_index_in_threadgroup]],
                        uint lane [[thread_index_in_simdgroup]],
                        uint sg [[simdgroup_index_in_threadgroup]],
                        uint nsg [[simdgroups_per_threadgroup]]) {
        threadgroup float4 shP[MAX_BATCH];
        threadgroup float4 shC[MAX_BATCH];
        threadgroup int4 shBox[MAX_BATCH];
        threadgroup int shK[MAX_BATCH];
        threadgroup uint shId[MAX_BATCH];
        threadgroup uint shCount[32];
        threadgroup uint shDone[32];
        uint B = P.tileW * P.tileH;
        int tx0 = int(tg.x * P.tileW), ty0 = int(tg.y * P.tileH);
        int4 tile = int4(tx0, tx0 + int(P.tileW) - 1, ty0, ty0 + int(P.tileH) - 1);
        int px = tx0 + int(lt.x), py = ty0 + int(lt.y);
        bool inside = px < int(P.width) && py < int(P.height);
        float T = 1.0;
        float3 C = float3(0.0);
        int last = -1;
        for (uint base = 0; base < P.nv; base += B) {
            uint total = loadBatch(base, lid, lane, sg, nsg, tile, P, S, proj, order, shP, shC, shBox, shK, shId, shCount);
            if (inside) {
                for (uint j = 0; j < total; j++) {
                    if (T < 1e-3) break;
                    int4 bb = shBox[j];
                    if (px < bb.x || px > bb.y || py < bb.z || py > bb.w) continue;
                    float4 q = shP[j];
                    float dx = float(px) + 0.5 - q.x, dy = float(py) + 0.5 - q.y;
                    float inv = 1.0 / (2.0 * q.z * q.z);
                    float g = exp(-(dx * dx + dy * dy) * inv);
                    float al = min(0.99f, q.w * g);
                    if (al < 1.0 / 255.0) continue;
                    C += T * al * shC[j].xyz;
                    T = T * (1.0 - al);
                    last = shK[j];
                }
            }
            // Stop when every pixel of the tile is covered (the whole threadgroup decides together).
            bool done = !inside || T < 1e-3;
            if (lane == 0) shDone[sg] = simd_all(done) ? 1 : 0;
            threadgroup_barrier(mem_flags::mem_threadgroup);
            bool stop = true;
            for (uint i = 0; i < nsg; i++) if (shDone[i] == 0) stop = false;
            threadgroup_barrier(mem_flags::mem_threadgroup);
            if (stop) break;
        }
        if (!inside) return;
        uint p = uint(py) * P.width + uint(px);
        float3 img = float3(images[P.imageOffset + p].rgb) / 255.0;
        float3 e = C + T * P.bg - img;
        pixT[p] = T;
        pixLast[p] = last;
        pixDR[p] = float4(e * (2.0 / float(P.width * P.height)), dot(e, e));
    }

    kernel void backward(constant StepParams& P [[buffer(0)]],
                         device const Splat* S [[buffer(1)]],
                         device const Proj* proj [[buffer(2)]],
                         device const uint* order [[buffer(3)]],
                         device const float* pixT [[buffer(5)]],
                         device const int* pixLast [[buffer(6)]],
                         device const float4* pixDR [[buffer(7)]],
                         device atomic_float* grads [[buffer(8)]],
                         uint2 tg [[threadgroup_position_in_grid]],
                         uint2 lt [[thread_position_in_threadgroup]],
                         uint lid [[thread_index_in_threadgroup]],
                         uint lane [[thread_index_in_simdgroup]],
                         uint sg [[simdgroup_index_in_threadgroup]],
                         uint nsg [[simdgroups_per_threadgroup]]) {
        threadgroup float4 shP[MAX_BATCH];
        threadgroup float4 shC[MAX_BATCH];
        threadgroup int4 shBox[MAX_BATCH];
        threadgroup int shK[MAX_BATCH];
        threadgroup uint shId[MAX_BATCH];
        threadgroup uint shCount[32];
        threadgroup int shMax[32];
        uint B = P.tileW * P.tileH;
        int tx0 = int(tg.x * P.tileW), ty0 = int(tg.y * P.tileH);
        int4 tile = int4(tx0, tx0 + int(P.tileW) - 1, ty0, ty0 + int(P.tileH) - 1);
        int px = tx0 + int(lt.x), py = ty0 + int(lt.y);
        bool inside = px < int(P.width) && py < int(P.height);
        uint p = inside ? uint(py) * P.width + uint(px) : 0;
        float T = inside ? pixT[p] : 1.0;
        int last = inside ? pixLast[p] : -1;
        float3 dR = inside ? pixDR[p].xyz : float3(0.0);
        float3 behind = T * P.bg; // everything behind the current splat, starting with the background

        int m = simd_max(last);
        if (lane == 0) shMax[sg] = m;
        threadgroup_barrier(mem_flags::mem_threadgroup);
        int maxLast = -1;
        for (uint i = 0; i < nsg; i++) maxLast = max(maxLast, shMax[i]);
        if (maxLast < 0) return; // same for the whole threadgroup

        for (int bi = maxLast / int(B); bi >= 0; bi--) {
            uint total = loadBatch(uint(bi) * B, lid, lane, sg, nsg, tile, P, S, proj, order, shP, shC, shBox, shK, shId, shCount);
            for (int j = int(total) - 1; j >= 0; j--) {
                float3 gc = float3(0.0);
                float4 g4 = float4(0.0); // opacity, sigma, u, v
                int k = shK[j];
                if (inside && k <= last) {
                    int4 bb = shBox[j];
                    if (px >= bb.x && px <= bb.y && py >= bb.z && py <= bb.w) {
                        float4 q = shP[j];
                        float sig = q.z, o = q.w;
                        float dx = float(px) + 0.5 - q.x, dy = float(py) + 0.5 - q.y, r2 = dx * dx + dy * dy;
                        float inv = 1.0 / (2.0 * sig * sig);
                        float g = exp(-r2 * inv);
                        float raw = o * g, al = min(0.99f, raw);
                        if (al >= 1.0 / 255.0) {
                            float Ti = T / (1.0 - al); // transmittance in front of this splat
                            float wgt = al * Ti;
                            float3 c = shC[j].xyz;
                            gc = dR * wgt;
                            float ia = 1.0 / (1.0 - al);
                            float dAl = dot(dR, Ti * c - behind * ia);
                            behind += wgt * c;
                            T = Ti;
                            if (raw < 0.99) {
                                float dG = dAl * o;
                                g4 = float4(dAl * g, dG * g * r2 / (sig * sig * sig), dG * g * dx / (sig * sig), dG * g * dy / (sig * sig));
                            }
                        }
                    }
                }
                gc = simd_sum(gc);
                g4 = simd_sum(g4);
                if (lane == 0 && (any(gc != 0.0) || any(g4 != 0.0))) {
                    device atomic_float* g = grads + shId[j] * 8;
                    atomic_fetch_add_explicit(g + 0, gc.x, memory_order_relaxed);
                    atomic_fetch_add_explicit(g + 1, gc.y, memory_order_relaxed);
                    atomic_fetch_add_explicit(g + 2, gc.z, memory_order_relaxed);
                    atomic_fetch_add_explicit(g + 3, g4.x, memory_order_relaxed);
                    atomic_fetch_add_explicit(g + 4, g4.y, memory_order_relaxed);
                    atomic_fetch_add_explicit(g + 5, g4.z, memory_order_relaxed);
                    atomic_fetch_add_explicit(g + 6, g4.w, memory_order_relaxed);
                }
            }
            threadgroup_barrier(mem_flags::mem_threadgroup);
        }
    }

    kernel void adam(constant StepParams& P [[buffer(0)]],
                     device Splat* S [[buffer(1)]],
                     device const Proj* proj [[buffer(2)]],
                     device const uint* order [[buffer(3)]],
                     device float* grads [[buffer(8)]],
                     device float* m1 [[buffer(9)]],
                     device float* m2 [[buffer(10)]],
                     device const float4* planes [[buffer(12)]],
                     uint k [[thread_position_in_grid]]) {
        if (k >= P.nv) return;
        uint i = order[k];
        Proj p = proj[i];
        device float* g = grads + i * 8;
        float gq[8];
        gq[0] = g[0]; gq[1] = g[1]; gq[2] = g[2];
        gq[3] = g[3] * p.o * (1.0 - p.o);                // d/d opacity logit
        gq[4] = p.sig > 0.36 ? g[4] * p.sig : 0.0;       // d/d log scale
        // Moving the splat in 3D moves its projected centre (u, v).
        float d = p.d, gu = g[5], gv = g[6];
        float ku = P.fx / d, kv = -P.fy / d, ku2 = P.fx * p.xc / (d * d), kv2 = -P.fy * p.yc / (d * d);
        for (int ax = 0; ax < 3; ax++) {
            float4 col = P.view[ax];
            gq[5 + ax] = gu * (ku * col.x + ku2 * col.z) + gv * (kv * col.y + kv2 * col.z);
        }
        for (int q = 0; q < 8; q++) g[q] = 0.0;

        const float lr[8] = {0.02, 0.02, 0.02, 0.05, 0.01, 0.002, 0.002, 0.002};
        Splat s = S[i];
        float vals[8] = {s.r, s.g, s.b, s.a, s.ls, s.ox, s.oy, s.oz};
        for (int q = 0; q < 8; q++) {
            uint mi = i * 8 + uint(q);
            float a1 = 0.9 * m1[mi] + 0.1 * gq[q];
            float a2 = 0.999 * m2[mi] + 0.001 * gq[q] * gq[q];
            m1[mi] = a1;
            m2[mi] = a2;
            vals[q] -= lr[q] * (a1 / P.c1) / (sqrt(a2 / P.c2) + 1e-8);
        }
        s.r = clamp(vals[0], 0.0, 1.0); s.g = clamp(vals[1], 0.0, 1.0); s.b = clamp(vals[2], 0.0, 1.0);
        s.a = clamp(vals[3], -6.0, 6.0);
        s.ls = clamp(vals[4], -1.2, 0.5); // at most 1.65x: bigger splats smear into blotches
        float3 off = clamp(float3(vals[5], vals[6], vals[7]), -P.maxT, P.maxT);
        // Splats on RoomPlan's floor or a wall slide along it but don't leave it (the viewer draws that surface).
        float4 plane = planes[i];
        if (plane.w > 0.5) off -= dot(off, plane.xyz) * plane.xyz;
        s.ox = off.x; s.oy = off.y; s.oz = off.z;
        S[i] = s;
    }
    """
}
