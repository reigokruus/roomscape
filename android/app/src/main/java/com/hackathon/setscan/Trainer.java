package com.hackathon.setscan;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Random;

/**
 * A small Gaussian-splat "training" that runs on the phone's CPU.
 *
 * The scanned surfels are merged into fewer, larger splats (one per 3 cm cell). Then, over and over:
 * pick one keyframe photo (shrunk to 256x144), draw the splats from that camera as soft gaussians
 * (front-to-back alpha blending), compare with the photo pixel by pixel, and backpropagate the error:
 * for every splat, which way should its colour, opacity, size and depth (along its surface normal)
 * change to make the drawing look more like the photo. Adam takes a small step and the next photo comes up.
 *
 * Splats can move freely in 3D (up to 12 cm), so they settle where the photos agree, not where the noisy depth put them.
 */
final class Trainer {
    static final float CELL = 0.03f;
    static float BG = 0.45f; // background grey behind the splats
    static final float MAX_T = 0.12f; // splats may move at most 12 cm from where the scan put them
    static final int NP = 8;

    static final class View {
        float[] view;        // world -> camera, column-major
        float fx, fy, cu, cv; // intrinsics at training resolution (pixels; v grows downwards)
        int W, H;
        float[] img;         // rgb 0..1, row 0 = top
    }

    final int n;
    final float[] bx, by, bz, nx, ny, nz, rad0;     // base position, normal (0 = none), base radius
    final float[] ox, oy, oz, cr, cg, cb, a, ls;    // trainable: 3D offset, colour, opacity logit, log scale
    private final float[][] m1, m2;                 // Adam moments, per parameter group
    private final float[] g;                        // gradients, 6 per splat
    int iter;
    float lastLoss, firstLoss = -1;

    float cell = CELL; // splat cell size actually used
    private Trainer(int n) {
        this.n = n;
        bx = new float[n]; by = new float[n]; bz = new float[n]; nx = new float[n]; ny = new float[n]; nz = new float[n]; rad0 = new float[n];
        ox = new float[n]; oy = new float[n]; oz = new float[n]; cr = new float[n]; cg = new float[n]; cb = new float[n]; a = new float[n]; ls = new float[n];
        m1 = new float[NP][n]; m2 = new float[NP][n]; g = new float[n * NP];
    }

    /** Merge surfels into one splat per 3 cm cell (average position/colour/normal). */
    static Trainer fromSurfels(Surfels s, ByteBuffer col) {
        int n = s.n;
        long[] ki = new long[n];
        // 3 cm splats suit a room; a close-up (shoes, scan 13:36: 86k surfels -> 7k splats) needs smaller ones,
        // so shrink the cell until there are enough splats for the detail that was scanned
        int target = Math.min(60000, (int) (n * 0.35f)), cells = 0;
        float cellUsed = CELL;
        for (float cs : new float[]{CELL, 0.02f, 0.014f, 0.01f}) {
            cellUsed = cs;
            for (int i = 0; i < n; i++) {
                long ix = (long) Math.floor(s.pos.get(i * 3) / cs) + 4096, iy = (long) Math.floor(s.pos.get(i * 3 + 1) / cs) + 4096,
                        iz = (long) Math.floor(s.pos.get(i * 3 + 2) / cs) + 4096;
                ki[i] = (((ix & 8191) | ((iy & 8191) << 13) | ((iz & 8191) << 26)) << 21) | i;
            }
            Arrays.sort(ki);
            cells = 0;
            for (int j = 0; j < n; j++) if (j == 0 || (ki[j] >>> 21) != (ki[j - 1] >>> 21)) cells++;
            if (cells >= target) break;
        }
        final float cell = cellUsed;
        Trainer tr = new Trainer(cells);
        tr.cell = cell;
        int c = -1; float rx = 0, ry = 0, rz = 0, sr = 0, sg = 0, sb = 0, snx = 0, sny = 0, snz = 0, srad = 0; int cnt = 0, ncnt = 0;
        float fnx = 0, fny = 0, fnz = 0;
        for (int j = 0; j <= n; j++) {
            boolean newCell = j == n || j == 0 || (ki[j] >>> 21) != (ki[j - 1] >>> 21);
            if (newCell && c >= 0) {
                tr.bx[c] = rx / cnt; tr.by[c] = ry / cnt; tr.bz[c] = rz / cnt;
                tr.cr[c] = sr / cnt; tr.cg[c] = sg / cnt; tr.cb[c] = sb / cnt;
                float l = (float) Math.sqrt(snx * snx + sny * sny + snz * snz);
                if (ncnt * 2 >= cnt && l > ncnt * 0.5f) { tr.nx[c] = snx / l; tr.ny[c] = sny / l; tr.nz[c] = snz / l; }
                tr.rad0[c] = Math.max(srad / cnt, cell * 0.6f) * (cnt == 1 ? 0.8f : 1f);
                tr.a[c] = 2f; // opacity 0.88
            }
            if (j == n) break;
            if (newCell) { c++; rx = ry = rz = sr = sg = sb = snx = sny = snz = srad = 0; cnt = ncnt = 0; }
            int i = (int) (ki[j] & 0x1FFFFF);
            rx += s.pos.get(i * 3); ry += s.pos.get(i * 3 + 1); rz += s.pos.get(i * 3 + 2);
            sr += (col.get(i * 4) & 0xFF) / 255f; sg += (col.get(i * 4 + 1) & 0xFF) / 255f; sb += (col.get(i * 4 + 2) & 0xFF) / 255f;
            float ax = s.nrm.get(i * 4), ay = s.nrm.get(i * 4 + 1), az = s.nrm.get(i * 4 + 2);
            if (ax * ax + ay * ay + az * az > 0.25f) {
                if (ncnt == 0) { fnx = ax; fny = ay; fnz = az; }
                if (ax * fnx + ay * fny + az * fnz < 0) { ax = -ax; ay = -ay; az = -az; }
                snx += ax; sny += ay; snz += az; ncnt++;
            }
            srad += s.nrm.get(i * 4 + 3);
            cnt++;
        }
        return tr;
    }

    // per-step scratch
    private float[] su, sv, ss, sd, so; private int[] vis; private long[] sortKey;
    private float[] pT, pC, dR; private int[] pLast;

    private final Surfels[] outs = new Surfels[3];
    private int outAt;

    private static float sigmoid(float x) { return 1f / (1f + (float) Math.exp(-x)); }

    /** One training step on one photo. Returns the mean squared error before the update. */
    float step(View v) { return step(v, true); }

    float step(View v, boolean update) {
        int W = v.W, H = v.H, np = W * H;
        if (su == null) { su = new float[n]; sv = new float[n]; ss = new float[n]; sd = new float[n]; so = new float[n]; vis = new int[n]; sortKey = new long[n]; }
        if (pT == null || pT.length != np) { pT = new float[np]; pC = new float[np * 3]; dR = new float[np * 3]; pLast = new int[np]; }
        float[] m = v.view;
        int nv = 0;
        for (int i = 0; i < n; i++) {
            float x = bx[i] + ox[i], y = by[i] + oy[i], z = bz[i] + oz[i];
            float xc = m[0] * x + m[4] * y + m[8] * z + m[12], yc = m[1] * x + m[5] * y + m[9] * z + m[13], zc = m[2] * x + m[6] * y + m[10] * z + m[14];
            float d = -zc;
            if (d < 0.15f) continue;
            float u = v.fx * xc / d + v.cu, w = v.cv - v.fy * yc / d;
            float sig = Math.max(0.35f, rad0[i] * (float) Math.exp(ls[i]) * v.fx / d * 0.6f);
            if (u < -3 * sig || u > W + 3 * sig || w < -3 * sig || w > H + 3 * sig || sig > W * 0.25f) continue;
            su[i] = u; sv[i] = w; ss[i] = sig; sd[i] = d; so[i] = sigmoid(a[i]);
            sortKey[nv++] = ((long) Float.floatToIntBits(d) << 32) | i; // positive floats sort like ints
        }
        Arrays.sort(sortKey, 0, nv);
        for (int k = 0; k < nv; k++) vis[k] = (int) sortKey[k];
        Arrays.fill(pT, 1f); Arrays.fill(pC, 0f); Arrays.fill(pLast, -1);
        // forward, front to back
        for (int k = 0; k < nv; k++) {
            int i = vis[k];
            float u = su[i], w = sv[i], sig = ss[i], o = so[i], inv = 1f / (2 * sig * sig);
            int x0 = Math.max(0, (int) (u - 3 * sig)), x1 = Math.min(W - 1, (int) (u + 3 * sig)), y0 = Math.max(0, (int) (w - 3 * sig)), y1 = Math.min(H - 1, (int) (w + 3 * sig));
            for (int py = y0; py <= y1; py++) {
                float dy = py + 0.5f - w;
                for (int px = x0; px <= x1; px++) {
                    int p = py * W + px;
                    float T = pT[p];
                    if (T < 1e-3f) continue;
                    float dx = px + 0.5f - u, gg = (float) Math.exp(-(dx * dx + dy * dy) * inv);
                    float al = Math.min(0.99f, o * gg);
                    if (al < 1f / 255f) continue;
                    pC[p * 3] += T * al * cr[i]; pC[p * 3 + 1] += T * al * cg[i]; pC[p * 3 + 2] += T * al * cb[i];
                    pT[p] = T * (1 - al); pLast[p] = k;
                }
            }
        }
        // loss: the rendering shows the photo through whatever the splats leave uncovered
        double loss = 0;
        float norm = 2f / np;
        float[] img = v.img;
        for (int p = 0; p < np; p++) {
            float T = pT[p];
            for (int ch = 0; ch < 3; ch++) {
                float e = pC[p * 3 + ch] + T * BG - img[p * 3 + ch];
                loss += e * e;
                dR[p * 3 + ch] = e * norm;
                if (update) pC[p * 3 + ch] = T * BG; // reuse as "everything behind" (starts as the background)
            }
        }
        lastLoss = (float) (loss / np);
        if (firstLoss < 0) firstLoss = lastLoss;
        if (!update) return lastLoss;
        Arrays.fill(g, 0f);
        // backward, back to front: pT holds the transmittance after the current splat
        for (int k = nv - 1; k >= 0; k--) {
            int i = vis[k];
            float u = su[i], w = sv[i], sig = ss[i], o = so[i], inv = 1f / (2 * sig * sig);
            float c0 = cr[i], c1 = cg[i], c2 = cb[i];
            float gr = 0, ggr = 0, gbl = 0, go = 0, gs = 0, gu = 0, gv = 0;
            int x0 = Math.max(0, (int) (u - 3 * sig)), x1 = Math.min(W - 1, (int) (u + 3 * sig)), y0 = Math.max(0, (int) (w - 3 * sig)), y1 = Math.min(H - 1, (int) (w + 3 * sig));
            for (int py = y0; py <= y1; py++) {
                float dy = py + 0.5f - w;
                for (int px = x0; px <= x1; px++) {
                    int p = py * W + px;
                    if (k > pLast[p]) continue;
                    float dx = px + 0.5f - u, r2 = dx * dx + dy * dy, gg = (float) Math.exp(-r2 * inv);
                    float raw = o * gg, al = Math.min(0.99f, raw);
                    if (al < 1f / 255f) continue;
                    float Ti = pT[p] / (1 - al); // transmittance in front of this splat
                    float wgt = al * Ti;
                    float d0 = dR[p * 3], d1 = dR[p * 3 + 1], d2 = dR[p * 3 + 2];
                    gr += d0 * wgt; ggr += d1 * wgt; gbl += d2 * wgt;
                    float ia = 1f / (1 - al);
                    float dAl = d0 * (Ti * c0 - pC[p * 3] * ia) + d1 * (Ti * c1 - pC[p * 3 + 1] * ia) + d2 * (Ti * c2 - pC[p * 3 + 2] * ia);
                    pC[p * 3] += wgt * c0; pC[p * 3 + 1] += wgt * c1; pC[p * 3 + 2] += wgt * c2;
                    pT[p] = Ti;
                    if (raw >= 0.99f) continue;
                    go += dAl * gg;
                    float dG = dAl * o;
                    gs += dG * gg * r2 / (sig * sig * sig);
                    gu += dG * gg * dx / (sig * sig);
                    gv += dG * gg * dy / (sig * sig);
                }
            }
            int b = i * NP;
            g[b] = gr; g[b + 1] = ggr; g[b + 2] = gbl;
            g[b + 3] = go * o * (1 - o);                 // d/d logit
            g[b + 4] = sig > 0.36f ? gs * sig : 0;        // d/d log-scale
            // moving the splat in 3D moves its projected centre (u, v)
            float d = sd[i];
            float x = bx[i] + ox[i], y = by[i] + oy[i], z = bz[i] + oz[i];
            float xc = m[0] * x + m[4] * y + m[8] * z + m[12], yc = m[1] * x + m[5] * y + m[9] * z + m[13];
            float ku = v.fx / d, kv = -v.fy / d, ku2 = v.fx * xc / (d * d), kv2 = -v.fy * yc / (d * d);
            for (int ax = 0; ax < 3; ax++) // du/dX = fx (R0/d + xc R2/d^2), dv/dX = -fy (R1/d + yc R2/d^2)
                g[b + 5 + ax] = gu * (ku * m[ax * 4] + ku2 * m[ax * 4 + 2]) + gv * (kv * m[ax * 4 + 1] + kv2 * m[ax * 4 + 2]);
        }
        // Adam
        iter++;
        float b1 = 0.9f, b2 = 0.999f;
        float c1 = 1 - (float) Math.pow(b1, iter), c2 = 1 - (float) Math.pow(b2, iter);
        float[] lr = {0.02f, 0.02f, 0.02f, 0.05f, 0.01f, 0.002f, 0.002f, 0.002f};
        for (int k = 0; k < nv; k++) {
            int i = vis[k];
            for (int q = 0; q < NP; q++) {
                float gq = g[i * NP + q];
                m1[q][i] = b1 * m1[q][i] + (1 - b1) * gq;
                m2[q][i] = b2 * m2[q][i] + (1 - b2) * gq * gq;
                float stepv = lr[q] * (m1[q][i] / c1) / ((float) Math.sqrt(m2[q][i] / c2) + 1e-8f);
                switch (q) {
                    case 0: cr[i] = clamp01(cr[i] - stepv); break;
                    case 1: cg[i] = clamp01(cg[i] - stepv); break;
                    case 2: cb[i] = clamp01(cb[i] - stepv); break;
                    case 3: a[i] = Math.max(-6, Math.min(6, a[i] - stepv)); break;
                    case 4: ls[i] = Math.max(-1.2f, Math.min(0.5f, ls[i] - stepv)); // max 1.65x: bigger splats smeared into blotches break;
                    case 5: ox[i] = Math.max(-MAX_T, Math.min(MAX_T, ox[i] - stepv)); break;
                    case 6: oy[i] = Math.max(-MAX_T, Math.min(MAX_T, oy[i] - stepv)); break;
                    default: oz[i] = Math.max(-MAX_T, Math.min(MAX_T, oz[i] - stepv)); break;
                }
            }
        }
        return lastLoss;
    }

    private static float clamp01(float x) { return x < 0 ? 0 : x > 1 ? 1 : x; }

    /** Splats for drawing: position, normal + radius, colour + opacity. Nearly transparent ones are dropped. */
    Surfels toSurfels() {
        int k = 0;
        for (int i = 0; i < n; i++) if (sigmoid(a[i]) > 0.08f) k++;
        // three buffers in rotation: the GL thread may still be uploading the previous one
        if (outs[outAt] == null) outs[outAt] = Surfels.empty(n);
        Surfels s = outs[outAt]; outAt = (outAt + 1) % outs.length;
        s.n = k;
        s.trained = true;
        int j = 0;
        for (int i = 0; i < n; i++) {
            float o = sigmoid(a[i]);
            if (o <= 0.08f) continue;
            s.pos.put(j * 3, bx[i] + ox[i]); s.pos.put(j * 3 + 1, by[i] + oy[i]); s.pos.put(j * 3 + 2, bz[i] + oz[i]);
            s.raw.put(j * 3, s.pos.get(j * 3)); s.raw.put(j * 3 + 1, s.pos.get(j * 3 + 1)); s.raw.put(j * 3 + 2, s.pos.get(j * 3 + 2));
            s.nrm.put(j * 4, nx[i]); s.nrm.put(j * 4 + 1, ny[i]); s.nrm.put(j * 4 + 2, nz[i]);
            s.nrm.put(j * 4 + 3, rad0[i] * (float) Math.exp(ls[i]));
            s.col.put(j * 4, (byte) Math.round(cr[i] * 255)); s.col.put(j * 4 + 1, (byte) Math.round(cg[i] * 255));
            s.col.put(j * 4 + 2, (byte) Math.round(cb[i] * 255)); s.col.put(j * 4 + 3, (byte) Math.round(o * 255));
            j++;
        }
        return s;
    }

    /** Render the current splats into an rgb image (for tests and previews). */
    float[] render(View v) {
        float[] keep = v.img;
        v.img = new float[v.W * v.H * 3];
        step(v, false);
        float[] out = new float[v.W * v.H * 3];
        for (int p = 0; p < v.W * v.H; p++) for (int ch = 0; ch < 3; ch++) out[p * 3 + ch] = pC[p * 3 + ch] / Math.max(1e-3f, 1 - pT[p]) * (pT[p] < 0.999f ? 1 : 0);
        v.img = keep;
        return out;
    }

    static int pick(Random r, int nViews) { return r.nextInt(nViews); }
}
