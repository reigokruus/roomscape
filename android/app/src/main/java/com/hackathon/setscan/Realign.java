package com.hackathon.setscan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Re-alignment after a tracking loss (rk 10 Nov: after a loss and an AR restart the S24 came back ~18 cm off and
 * fused a second, shifted layer). The scan so far is kept as point centroids in 12 cm and 4 cm cells; a depth frame
 * after the loss is matched to them (ICP: coarse cells first, then fine) for a shift plus a turn about the vertical
 * (ARCore keeps gravity). Plain Java (no Android), so it's tested on the Mac (tools/realign-test).
 */
final class Realign {
    static final float COARSE = 0.12f, MID = 0.04f, FINE = 0.02f;

    final HashMap<Long, float[]> coarse = new HashMap<>(), mid = new HashMap<>(), fine = new HashMap<>();

    /** Builds the centroid cells from points (x, y, z triples), at most ~400k of them. */
    Realign(float[] xyz, int n) {
        int step = Math.max(1, n / 400000);
        for (int i = 0; i < n; i += step) add(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2]);
        finish();
    }

    Realign() { }

    void add(float x, float y, float z) {
        float[] c = coarse.computeIfAbsent(key(x, y, z, COARSE), k -> new float[4]); c[0] += x; c[1] += y; c[2] += z; c[3]++;
        float[] m = mid.computeIfAbsent(key(x, y, z, MID), k -> new float[4]); m[0] += x; m[1] += y; m[2] += z; m[3]++;
        float[] f = fine.computeIfAbsent(key(x, y, z, FINE), k -> new float[4]); f[0] += x; f[1] += y; f[2] += z; f[3]++;
    }

    void finish() {
        for (float[] c : coarse.values()) { c[0] /= c[3]; c[1] /= c[3]; c[2] /= c[3]; }
        for (float[] c : mid.values()) { c[0] /= c[3]; c[1] /= c[3]; c[2] /= c[3]; }
        for (float[] c : fine.values()) { c[0] /= c[3]; c[1] /= c[3]; c[2] /= c[3]; }
    }

    static long key(float x, float y, float z, float s) {
        long ix = (long) Math.floor(x / s) + (1 << 20), iy = (long) Math.floor(y / s) + (1 << 20), iz = (long) Math.floor(z / s) + (1 << 20);
        return (ix & 0x1FFFFF) | ((iy & 0x1FFFFF) << 21) | ((iz & 0x1FFFFF) << 42);
    }

    private static float[] nearest(HashMap<Long, float[]> cells, float s, int reach, float x, float y, float z) {
        long ix = (long) Math.floor(x / s), iy = (long) Math.floor(y / s), iz = (long) Math.floor(z / s);
        float[] best = null; float bd = Float.MAX_VALUE;
        for (int a = -reach; a <= reach; a++) for (int b = -reach; b <= reach; b++) for (int c = -reach; c <= reach; c++) {
            float[] q = cells.get(key((ix + a + 0.5f) * s, (iy + b + 0.5f) * s, (iz + c + 0.5f) * s, s));
            if (q == null) continue;
            float d = (q[0] - x) * (q[0] - x) + (q[1] - y) * (q[1] - y) + (q[2] - z) * (q[2] - z);
            if (d < bd) { bd = d; best = q; }
        }
        return best;
    }

    /** Column-major 4×4 product a·b. */
    static float[] mul(float[] a, float[] b) {
        float[] r = new float[16];
        for (int c = 0; c < 4; c++) for (int rr = 0; rr < 4; rr++) {
            float s = 0;
            for (int k = 0; k < 4; k++) s += a[k * 4 + rr] * b[c * 4 + k];
            r[c * 4 + rr] = s;
        }
        return r;
    }

    static float[] identity() { float[] m = new float[16]; m[0] = m[5] = m[10] = m[15] = 1; return m; }

    /** Result of one solve: the correction (world ← ARCore), the share of samples on the scan, their median distance. */
    static final class Fit { float[] fix; float inliers; float median; }

    /** Accept a fit: this share of samples on the scan, and their median distance under 2 cm. */
    static final float MIN_INLIERS = 0.7f, MAX_MEDIAN = 0.02f;
    static boolean good(Fit f) { return f.inliers >= MIN_INLIERS && f.median < MAX_MEDIAN; }

    /** ICP from `start` and from start turned ±6° / ±12° about the samples' middle (a wrong first guess of the turn
     *  can settle in the wrong place); the fit with the most samples on the scan wins. */
    Fit solve(List<float[]> pts, float[] start) {
        float mx = 0, mz = 0;
        for (float[] p : pts) { mx += p[0]; mz += p[2]; }
        if (!pts.isEmpty()) { mx /= pts.size(); mz /= pts.size(); }
        Fit best = null;
        for (float deg : new float[]{0, 6, -6, 12, -12}) {
            float[] s0 = start;
            if (deg != 0) {
                float a = (float) Math.toRadians(deg), ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                // Turn about (mx, mz) in the start's output space.
                float sx = start[0] * mx + start[8] * mz + start[12], sz = start[2] * mx + start[10] * mz + start[14];
                float[] t = identity(); t[0] = ca; t[8] = -sa; t[2] = sa; t[10] = ca;
                t[12] = sx - (ca * sx - sa * sz); t[14] = sz - (sa * sx + ca * sz);
                s0 = mul(t, start);
            }
            Fit f = solveFrom(pts, s0);
            if (best == null || f.inliers > best.inliers + 0.02f || (Math.abs(f.inliers - best.inliers) <= 0.02f && f.median < best.median)) best = f;
        }
        return best;
    }

    private Fit solveFrom(List<float[]> pts, float[] start) {
        float[] F = start.clone();
        float inl = 0, med = 1;
        for (int it = 0; it < 18; it++) {
            int stage = it < 7 ? 0 : it < 12 ? 1 : 2;   // 12 cm, 4 cm, 2 cm cells
            float maxD = stage == 0 ? 0.35f : stage == 1 ? 0.10f : 0.05f;
            ArrayList<float[]> pairs = new ArrayList<>();
            float[] res = new float[pts.size()]; int nres = 0;
            for (float[] p : pts) {
                float x = F[0] * p[0] + F[4] * p[1] + F[8] * p[2] + F[12], y = F[1] * p[0] + F[5] * p[1] + F[9] * p[2] + F[13], z = F[2] * p[0] + F[6] * p[1] + F[10] * p[2] + F[14];
                float[] q = stage == 0 ? nearest(coarse, COARSE, 2, x, y, z) : stage == 1 ? nearest(mid, MID, 1, x, y, z) : nearest(fine, FINE, 2, x, y, z);
                if (q == null) continue;
                float dd = (float) Math.sqrt((q[0] - x) * (q[0] - x) + (q[1] - y) * (q[1] - y) + (q[2] - z) * (q[2] - z));
                if (dd > maxD) continue;
                res[nres++] = dd;
                pairs.add(new float[]{x, y, z, q[0], q[1], q[2]});
            }
            inl = pts.isEmpty() ? 0 : (float) pairs.size() / pts.size();
            if (pairs.size() < 30) break;
            Arrays.sort(res, 0, nres); med = res[nres / 2];
            float sx = 0, sz = 0, tx = 0, tz = 0, sy = 0, ty = 0;
            for (float[] q : pairs) { sx += q[0]; sz += q[2]; sy += q[1]; tx += q[3]; tz += q[5]; ty += q[4]; }
            int n = pairs.size(); sx /= n; sz /= n; sy /= n; tx /= n; tz /= n; ty /= n;
            float h1 = 0, h2 = 0;
            for (float[] q : pairs) { float ax = q[0] - sx, az = q[2] - sz, bx = q[3] - tx, bz = q[5] - tz; h1 += ax * bx + az * bz; h2 += ax * bz - az * bx; }
            float ang = (float) Math.atan2(h2, h1), ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
            float[] step = identity();
            step[0] = ca; step[8] = -sa; step[2] = sa; step[10] = ca;
            step[12] = tx - (ca * sx - sa * sz); step[13] = ty - sy; step[14] = tz - (sa * sx + ca * sz);
            F = mul(step, F);
        }
        Fit f = new Fit(); f.fix = F; f.inliers = inl; f.median = med;
        return f;
    }
}
