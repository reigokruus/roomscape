package com.hackathon.setscan;

import java.util.ArrayList;
import java.util.List;

/**
 * "Good spots" to come back to after a tracking loss (rk 11 Nov: "instead of telling to go back to a place that was
 * scanned … show the area that was last well scanned nearby that had a good amount of detail"). Pointing back at
 * where the phone was when it lost track usually means pointing at the plain wall that lost it, so the scan never
 * carries on.
 *
 * While scanning, about once a second, a view is kept as an anchor when ARCore saw many features in it (it can find
 * its place there again) AND its depth has 3D shape in several directions (a corner, furniture, a door frame: the
 * re-align can't slide along it, unlike a plain wall, a wall and floor, or a corridor). Each anchor keeps its camera
 * pose, the point it looked at, the time, the feature count, the shape score and a small grey picture.
 *
 * Pure Java (no Android): also runs on the Mac for checks on saved scans (tools/anchors).
 */
final class Anchors {
    static final int MAX = 30;
    static final int MIN_FEATURES = 60;      // below this ARCore itself struggles to find its place again
    static final float MIN_SHAPE = 0.12f;    // sqrt(smallest / largest normal spread): 0 = one plane or a corridor
    static final long GIVE_UP_MS = 12000;    // a spot that doesn't work in this long: offer the next one

    static final class Anchor {
        final float[] cam = new float[16];   // camera -> world (corrected), as when it was kept
        float tx, ty, tz;                    // the point it looked at (camera axis, at the median depth)
        long ms;                             // when (scan clock, ms)
        int features;
        float shape;                         // 0..1, see shapeScore
        float depthM;                        // median depth, m
        byte[] gray; int gw, gh;             // small grey picture of the camera image (may be null)
        float quality() { return Math.min(1f, features / 150f) * Math.min(1f, shape / 0.35f); }
        float px() { return cam[12]; } float py() { return cam[13]; } float pz() { return cam[14]; }
        float fx() { return -cam[8]; } float fy() { return -cam[9]; } float fz() { return -cam[10]; }
    }

    final List<Anchor> list = new ArrayList<>();

    /** Shape of a depth frame (mm, 0 = none; camera intrinsics in depth pixels): normals on a grid, then how evenly
     *  they spread over three directions. out[0] = score (0..1), out[1] = median depth m, out[2] = share of the grid
     *  with a normal. A plain wall gives ~0, a wall and the floor ~0 (it can slide along the corner line), a room corner
     *  or furniture 0.3 and more. */
    static float[] shapeScore(short[] d, int dw, int dh, float fx, float fy, float cx, float cy) {
        int step = Math.max(2, dw / 20);
        double[] c = new double[6]; int n = 0, cells = 0;
        float[] depths = new float[(dw / step + 1) * (dh / step + 1)]; int nd = 0;
        for (int v = 0; v + step < dh; v += step) for (int u = 0; u + step < dw; u += step) {
            cells++;
            float z0 = (d[v * dw + u] & 0xFFFF) / 1000f, zu = (d[v * dw + u + step] & 0xFFFF) / 1000f, zv = (d[(v + step) * dw + u] & 0xFFFF) / 1000f;
            if (z0 < 0.2f || zu < 0.2f || zv < 0.2f || z0 > 5f) continue;
            depths[nd++] = z0;
            if (Math.abs(zu - z0) > 0.08f * z0 || Math.abs(zv - z0) > 0.08f * z0) continue;   // an edge, not a surface
            float x0 = (u - cx) / fx * z0, y0 = (v - cy) / fy * z0;
            float ax = (u + step - cx) / fx * zu - x0, ay = (v - cy) / fy * zu - y0, az = zu - z0;
            float bx = (u - cx) / fx * zv - x0, by = (v + step - cy) / fy * zv - y0, bz = zv - z0;
            float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (l < 1e-9f) continue;
            nx /= l; ny /= l; nz /= l;
            c[0] += nx * nx; c[1] += ny * ny; c[2] += nz * nz; c[3] += nx * ny; c[4] += nx * nz; c[5] += ny * nz; n++;
        }
        float med = 0;
        if (nd > 0) { float[] s = java.util.Arrays.copyOf(depths, nd); java.util.Arrays.sort(s); med = s[nd / 2]; }
        if (n < 20) return new float[]{0, med, cells == 0 ? 0 : (float) n / cells};
        double[] e = eig3(c[0] / n, c[1] / n, c[2] / n, c[3] / n, c[4] / n, c[5] / n);
        float score = e[0] <= 1e-9 ? 0 : (float) Math.sqrt(Math.max(0, e[2]) / e[0]);
        return new float[]{score, med, (float) n / cells};
    }

    /** Eigenvalues of a symmetric 3x3 matrix, largest first (closed form). */
    static double[] eig3(double a, double b, double c, double d, double e, double f) {
        // [a d e; d b f; e f c]
        double p1 = d * d + e * e + f * f;
        double q = (a + b + c) / 3;
        if (p1 < 1e-15) { double[] r = {a, b, c}; java.util.Arrays.sort(r); return new double[]{r[2], r[1], r[0]}; }
        double p2 = (a - q) * (a - q) + (b - q) * (b - q) + (c - q) * (c - q) + 2 * p1, p = Math.sqrt(p2 / 6);
        double b11 = (a - q) / p, b22 = (b - q) / p, b33 = (c - q) / p, b12 = d / p, b13 = e / p, b23 = f / p;
        double r = (b11 * (b22 * b33 - b23 * b23) - b12 * (b12 * b33 - b23 * b13) + b13 * (b12 * b23 - b22 * b13)) / 2;
        double phi = r <= -1 ? Math.PI / 3 : r >= 1 ? 0 : Math.acos(r) / 3;
        double e1 = q + 2 * p * Math.cos(phi), e3 = q + 2 * p * Math.cos(phi + 2 * Math.PI / 3), e2 = 3 * q - e1 - e3;
        return new double[]{e1, e2, e3};
    }

    /** Whether a view with this feature count and shapeScore result is worth keeping. */
    static boolean good(int features, float[] s) {
        return features >= MIN_FEATURES && s[0] >= MIN_SHAPE && s[2] >= 0.3f && s[1] >= 0.3f && s[1] <= 3.5f;
    }

    /** An anchor from a view (camera -> world, corrected) and its shapeScore result. */
    static Anchor make(float[] cam, long ms, int features, float[] s) {
        Anchor a = new Anchor();
        System.arraycopy(cam, 0, a.cam, 0, 16);
        a.ms = ms; a.features = features; a.shape = s[0]; a.depthM = s[1];
        a.tx = a.px() + a.fx() * s[1]; a.ty = a.py() + a.fy() * s[1]; a.tz = a.pz() + a.fz() * s[1];
        return a;
    }

    /** Keeps one anchor per view (within 0.5 m and 25 degrees): the better one; when full, drops the weakest and oldest. */
    synchronized boolean keep(Anchor a) {
        for (int i = 0; i < list.size(); i++) {
            Anchor o = list.get(i);
            if (dist(o.px(), o.py(), o.pz(), a.px(), a.py(), a.pz()) < 0.5f && dot(o, a) > 0.906f) {
                if (a.quality() >= o.quality() * 0.9f) { list.set(i, a); return true; }   // newer and as good: take it
                return false;
            }
        }
        if (list.size() >= MAX) {
            int worst = 0; float ws = Float.MAX_VALUE;
            for (int i = 0; i < list.size(); i++) { float v = list.get(i).quality() + list.get(i).ms / 600000f; if (v < ws) { ws = v; worst = i; } }
            if (a.quality() + a.ms / 600000f <= ws) return false;
            list.remove(worst);
        }
        list.add(a);
        return true;
    }

    /**
     * The best anchor to go to after a loss. lostCam: the camera (camera -> world) when tracking went (the view that
     * lost it); now: the scan clock. Not the view that lost it (within 35 degrees and 1 m of what it looked at), not
     * anything kept in the last 2 s before the loss, not one already tried. Score: quality, less for walking and turning
     * further, and less for older ones. out[0] = metres away, out[1] = degrees to turn (may be null).
     */
    synchronized Anchor pick(float[] lostCam, long lostMs, java.util.Set<Anchor> tried, float[] out) {
        Anchor best = null; float bs = 0;
        float lfx = -lostCam[8], lfy = -lostCam[9], lfz = -lostCam[10];
        for (Anchor a : list) {
            if (tried != null && tried.contains(a)) continue;
            if (a.ms > lostMs - 2000) continue;
            float walk = dist(a.px(), a.py(), a.pz(), lostCam[12], lostCam[13], lostCam[14]);
            float lookDot = a.fx() * lfx + a.fy() * lfy + a.fz() * lfz;
            float ltx = lostCam[12] + lfx * 1.5f, lty = lostCam[13] + lfy * 1.5f, ltz = lostCam[14] + lfz * 1.5f;
            if (lookDot > 0.82f && dist(a.tx, a.ty, a.tz, ltx, lty, ltz) < 1f) continue;   // the view that lost it
            float turn = turnDeg(lostCam, a.tx, a.tz);
            float age = (lostMs - a.ms) / 1000f;
            float s = a.quality() * (float) Math.exp(-walk / 2.5f) * (float) Math.exp(-turn / 180f) * (float) Math.exp(-age / 180f);
            if (s > bs) { bs = s; best = a; if (out != null) { out[0] = walk; out[1] = turn; out[2] = s; } }
        }
        return best;
    }

    synchronized void clear() { list.clear(); }
    synchronized int size() { return list.size(); }

    /** Degrees to turn (about the vertical) from the camera's heading to look at x, z. */
    static float turnDeg(float[] cam, float x, float z) {
        float fx = -cam[8], fz = -cam[10], dx = x - cam[12], dz = z - cam[14];
        double a = Math.atan2(dz, dx) - Math.atan2(fz, fx);
        while (a > Math.PI) a -= 2 * Math.PI; while (a < -Math.PI) a += 2 * Math.PI;
        return (float) Math.abs(Math.toDegrees(a));
    }

    private static float dot(Anchor a, Anchor b) { return a.fx() * b.fx() + a.fy() * b.fy() + a.fz() * b.fz(); }
    static float dist(float ax, float ay, float az, float bx, float by, float bz) {
        float dx = ax - bx, dy = ay - by, dz = az - bz; return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
