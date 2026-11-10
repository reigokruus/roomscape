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

    // ---- Global search (rk 10 Nov 11:17: after a restart ARCore came back in a frame shifted by more than the ICP's
    // reach and the scan never lined up again). Gravity is known, so only a turn about the vertical and a shift are
    // unknown: every 10° turn and every 10 cm shift within SEARCH_R is scored on a floor plan of the scan's upright
    // parts (walls, furniture), then the best few go to the ICP. ----
    static final float PLAN = 0.10f, SEARCH_R = 4f, COARSE_STEP = 0.2f;
    private boolean[] plan; private float px0, pz0, planFloor; private int pw, ph;

    /** The floor plan: cells with scan points 25 cm to 2 m above the floor (dilated by a cell). */
    void buildPlan(float[] xyz, int n, float floorY) {
        planFloor = floorY;
        float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, z0 = Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
        int step = Math.max(1, n / 400000);
        for (int i = 0; i < n; i += step) { float x = xyz[i * 3], z = xyz[i * 3 + 2]; x0 = Math.min(x0, x); x1 = Math.max(x1, x); z0 = Math.min(z0, z); z1 = Math.max(z1, z); }
        if (x0 > x1) return;
        px0 = x0 - 1; pz0 = z0 - 1;
        pw = (int) ((x1 - x0 + 2) / PLAN) + 1; ph = (int) ((z1 - z0 + 2) / PLAN) + 1;
        if ((long) pw * ph > 4_000_000) { plan = null; return; }
        boolean[] g = new boolean[pw * ph];
        for (int i = 0; i < n; i += step) {
            float y = xyz[i * 3 + 1] - floorY; if (y < 0.25f || y > 2f) continue;
            int u = (int) ((xyz[i * 3] - px0) / PLAN), v = (int) ((xyz[i * 3 + 2] - pz0) / PLAN);
            g[v * pw + u] = true;
        }
        plan = new boolean[pw * ph];
        for (int v = 1; v < ph - 1; v++) for (int u = 1; u < pw - 1; u++) if (g[v * pw + u])
            for (int b = -1; b <= 1; b++) for (int a = -1; a <= 1; a++) plan[(v + b) * pw + u + a] = true;
    }

    /** The `keep` best (yaw deg, tx, tz, score) over turns [d0, d1) by dd about (cx, cz) and shifts within r of (tx0, tz0). */
    private float[][] top(ArrayList<float[]> up, float cx, float cz, int m, float[] rx, float[] rz, float d0, float d1, float dd,
                          float tx0, float tz0, float r, float step, int keep) {
        float[][] best = new float[keep][];
        int steps = (int) (r / step);
        for (float deg = d0; deg < d1; deg += dd) {
            double a = Math.toRadians(deg); float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            for (int i = 0; i < m; i++) { float dx = up.get(i)[0] - cx, dz = up.get(i)[1] - cz; rx[i] = cx + ca * dx - sa * dz; rz[i] = cz + sa * dx + ca * dz; }
            for (int ti = -steps; ti <= steps; ti++) for (int tj = -steps; tj <= steps; tj++) {
                if (ti * ti + tj * tj > steps * steps) continue;
                float tx = tx0 + ti * step, tz = tz0 + tj * step;
                int sc = 0;
                for (int i = 0; i < m; i++) {
                    int u = (int) ((rx[i] + tx - px0) / PLAN), v = (int) ((rz[i] + tz - pz0) / PLAN);
                    if (u >= 0 && v >= 0 && u < pw && v < ph && plan[v * pw + u]) sc++;
                }
                for (int k = 0; k < keep; k++) if (best[k] == null || sc > best[k][3]) {
                    for (int j = keep - 1; j > k; j--) best[j] = best[j - 1];
                    best[k] = new float[]{deg, tx, tz, sc}; break;
                }
            }
        }
        return best;
    }

    /** How different two placements are: metres of shift plus 1 m per 30° of turn (about the vertical). */
    static float away(float[] a, float[] b) {
        float dx = a[12] - b[12], dz = a[14] - b[14];
        double t = Math.abs(Math.atan2(a[2], a[0]) - Math.atan2(b[2], b[0])); if (t > Math.PI) t = 2 * Math.PI - t;
        return (float) (Math.sqrt(dx * dx + dz * dz) + Math.toDegrees(t) / 30);
    }

    /** Last search's numbers, for the log; and whether a different placement was nearly as good. */
    String lastSearch = "";
    boolean ambiguous;

    /** The best fit over all turns and shifts around `guess` (pts in ARCore's frame), ICP-refined; null without a plan. */
    Fit search(List<float[]> pts, float[] guess) { return search(pts, guess, Float.NaN, Float.NaN); }

    /** As above, turning about (pivotX, pivotZ) in the guess's frame: the camera's spot, so a pure turn of ARCore's
     *  frame needs no shift (about the view's middle, a half turn needs a shift of twice the viewing distance). */
    Fit search(List<float[]> pts, float[] guess, float pivotX, float pivotZ) {
        if (plan == null || pts.size() < 60) return null;
        // The upright samples, placed by the guess; at most 300.
        ArrayList<float[]> up = new ArrayList<>();
        int stride = Math.max(1, pts.size() / 600);
        for (int i = 0; i < pts.size(); i += stride) {
            float[] p = pts.get(i);
            float x = guess[0] * p[0] + guess[4] * p[1] + guess[8] * p[2] + guess[12], y = guess[1] * p[0] + guess[5] * p[1] + guess[9] * p[2] + guess[13], z = guess[2] * p[0] + guess[6] * p[1] + guess[10] * p[2] + guess[14];
            if (y - planFloor < 0.3f || y - planFloor > 1.9f) continue;
            up.add(new float[]{x, z});
            if (up.size() >= 200) break;
        }
        if (up.size() < 40) { lastSearch = "search: too few upright samples (" + up.size() + ")"; return null; }
        float cx = 0, cz = 0;
        for (float[] q : up) { cx += q[0]; cz += q[1]; }
        cx /= up.size(); cz /= up.size();
        if (!Float.isNaN(pivotX)) { cx = pivotX; cz = pivotZ; }
        int m = up.size(); float[] rx = new float[m], rz = new float[m];
        // Coarse: every 10° and 20 cm within SEARCH_R; then each of the best five again at 5° and 10 cm around it.
        float[][] coarse = top(up, cx, cz, m, rx, rz, 0, 360, 10, 0, 0, SEARCH_R, COARSE_STEP, 8);
        int[] bestS = new int[5]; float[][] bestP = new float[5][];
        for (float[] c0 : coarse) if (c0 != null) {
            float[][] fine = top(up, cx, cz, m, rx, rz, c0[0] - 10, c0[0] + 10, 5, c0[1], c0[2], 0.25f, PLAN, 1);
            float[] f0 = fine[0]; if (f0 == null) continue;
            int sc = (int) f0[3];
            for (int k = 0; k < 5; k++) if (bestP[k] == null || sc > bestS[k]) {
                for (int j = 4; j > k; j--) { bestS[j] = bestS[j - 1]; bestP[j] = bestP[j - 1]; }
                bestS[k] = sc; bestP[k] = f0; break;
            }
        }
        Fit best = null; String tried = ""; ArrayList<Fit> fits = new ArrayList<>();
        for (int k = 0; k < 5 && bestP[k] != null; k++) {
            float deg = bestP[k][0], tx = bestP[k][1], tz = bestP[k][2];
            double a = Math.toRadians(deg); float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            float[] t = identity(); t[0] = ca; t[8] = -sa; t[2] = sa; t[10] = ca;
            t[12] = cx - (ca * cx - sa * cz) + tx; t[14] = cz - (sa * cx + ca * cz) + tz;
            Fit f = solveFrom(pts, mul(t, guess));
            fits.add(f);
            tried += String.format(java.util.Locale.US, " [%.0f° %.1f,%.1f m plan %d%% -> %d%%, %.1f cm]", deg, tx, tz, 100 * bestS[k] / m, Math.round(f.inliers * 100), f.median * 100);
            if (best == null || f.inliers > best.inliers + 0.02f || (Math.abs(f.inliers - best.inliers) <= 0.02f && f.median < best.median)) best = f;
        }
        // Among placements nearly as good as the best (a symmetric room), the one closest to the guess; ambiguous when
        // another, different one is as good, unless the chosen one is close to the guess itself.
        ambiguous = false;
        if (best != null) {
            Fit pick = best;
            for (Fit f : fits) if (f.inliers > best.inliers - 0.05f && f.median < MAX_MEDIAN && away(f.fix, guess) < away(pick.fix, guess)) pick = f;
            best = pick;
            boolean nearGuess = away(best.fix, guess) < 0.3f;
            for (Fit f : fits) if (f != best && f.inliers > best.inliers - 0.05f && away(f.fix, best.fix) > 0.2f && !nearGuess) { ambiguous = true; break; }
        }
        lastSearch = "search:" + tried + (ambiguous ? " AMBIGUOUS" : "");
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
