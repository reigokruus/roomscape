package com.hackathon.setscan;

import java.util.Arrays;

/**
 * "Continue scan": finds where a new scan sits inside a saved one, from the points alone.
 * ARCore keeps "up" the same in every session, so only a turn around the vertical axis and a shift are unknown.
 * 1) coarse: for each of 48 headings, every new 25 cm cell votes for the shift that would put it on every saved cell;
 *    the strongest shifts are the candidates;
 * 2) fine: each candidate is searched around in small steps, scored by how many new 15 cm cells land on saved ones.
 * Result: {cos, sin, tx, ty, tz} with saved = (c x - s z + tx, y + ty, s x + c z + tz) for a new point (x, y, z).
 */
final class ContinueAlign {
    static final float COARSE = 0.25f, FINE = 0.15f, GHOST = 0.12f;
    private static final int MAX_Q = 8000, MAX_P = 250, MAX_F = 900, MAX_GHOST = 90000;

    /** The saved scan, simplified for matching and for the overlay. */
    static final class Base {
        ScanRenderer.LongIntMap fine;
        float[] qx, qy, qz; int qn;          // saved 25 cm cells (centres)
        float minX, maxX, minZ, maxZ;
        float[] ghost; int ghostN;            // one point per 12 cm cell, for the overlay (saved coordinates)
    }

    static long key(float x, float y, float z, float cell) {
        long ix = (long) Math.floor(x / cell) + (1 << 20), iy = (long) Math.floor(y / cell) + (1 << 20), iz = (long) Math.floor(z / cell) + (1 << 20);
        return (ix << 42) | (iy << 21) | iz;
    }

    static Base buildBase(float[] pos, int n) {
        Base b = new Base();
        b.fine = new ScanRenderer.LongIntMap(1 << 19);
        ScanRenderer.LongIntMap coarse = new ScanRenderer.LongIntMap(1 << 17), gh = new ScanRenderer.LongIntMap(1 << 18);
        int fineN = 0, cn = 0, gn = 0;
        float[] cx = new float[60000], cy = new float[60000], cz = new float[60000]; int[] cc = new int[60000];
        float[] g = new float[MAX_GHOST * 3];
        b.minX = b.minZ = 1e9f; b.maxX = b.maxZ = -1e9f;
        for (int i = 0; i < n; i++) {
            float x = pos[i * 3], y = pos[i * 3 + 1], z = pos[i * 3 + 2];
            if (fineN < (1 << 19) * 7 / 10) { long k = key(x, y, z, FINE); if (b.fine.get(k) < 0) { b.fine.put(k, 1); fineN++; } }
            long k = key(x, y, z, COARSE);
            int j = coarse.get(k);
            if (j < 0 && cn < cx.length) {
                j = cn++; coarse.put(k, j);
                cx[j] = ((float) Math.floor(x / COARSE) + 0.5f) * COARSE; cy[j] = ((float) Math.floor(y / COARSE) + 0.5f) * COARSE; cz[j] = ((float) Math.floor(z / COARSE) + 0.5f) * COARSE;
            }
            if (j >= 0) cc[j]++;
            if (gn < MAX_GHOST && gn < (1 << 18) * 7 / 10) {
                long gk = key(x, y, z, GHOST);
                if (gh.get(gk) < 0) { gh.put(gk, 1); g[gn * 3] = x; g[gn * 3 + 1] = y; g[gn * 3 + 2] = z; gn++; }
            }
        }
        int solid = 0;
        for (int j = 0; j < cn; j++) if (cc[j] >= 3) solid++;
        int step = Math.max(1, (solid + MAX_Q - 1) / MAX_Q), m = 0, s = 0;
        b.qx = new float[Math.min(solid, MAX_Q) + 1]; b.qy = new float[b.qx.length]; b.qz = new float[b.qx.length];
        for (int j = 0; j < cn && m < b.qx.length; j++) {
            if (cc[j] < 3) continue;
            if (s++ % step != 0) continue;
            b.qx[m] = cx[j]; b.qy[m] = cy[j]; b.qz[m] = cz[j]; m++;
            b.minX = Math.min(b.minX, cx[j]); b.maxX = Math.max(b.maxX, cx[j]); b.minZ = Math.min(b.minZ, cz[j]); b.maxZ = Math.max(b.maxZ, cz[j]);
        }
        b.qn = m;
        b.ghost = Arrays.copyOf(g, gn * 3); b.ghostN = gn;
        return b;
    }

    /** The new scan's sample, centred, as coarse and fine probe cells. */
    static final class Probes {
        float cenX, cenY, cenZ;
        float[] p; int pn;      // coarse (centred)
        float[] f; int fn;      // fine (centred)
        int coarseCells;        // how much of the room the new scan has seen
    }

    static Probes probes(float[] pts, int n) {
        Probes r = new Probes();
        double sx = 0, sy = 0, sz = 0;
        for (int i = 0; i < n; i++) { sx += pts[i * 3]; sy += pts[i * 3 + 1]; sz += pts[i * 3 + 2]; }
        r.cenX = (float) (sx / Math.max(1, n)); r.cenY = (float) (sy / Math.max(1, n)); r.cenZ = (float) (sz / Math.max(1, n));
        ScanRenderer.LongIntMap c = new ScanRenderer.LongIntMap(1 << 15), f = new ScanRenderer.LongIntMap(1 << 16);
        float[] cp = new float[20000 * 3], fp = new float[40000 * 3]; int cn = 0, fn = 0;
        for (int i = 0; i < n; i++) {
            float x = pts[i * 3], y = pts[i * 3 + 1], z = pts[i * 3 + 2];
            long k = key(x, y, z, COARSE);
            if (cn < 20000 && c.get(k) < 0) { c.put(k, 1); cp[cn * 3] = ((float) Math.floor(x / COARSE) + 0.5f) * COARSE - r.cenX; cp[cn * 3 + 1] = ((float) Math.floor(y / COARSE) + 0.5f) * COARSE - r.cenY; cp[cn * 3 + 2] = ((float) Math.floor(z / COARSE) + 0.5f) * COARSE - r.cenZ; cn++; }
            k = key(x, y, z, FINE);
            if (fn < 40000 && f.get(k) < 0) { f.put(k, 1); fp[fn * 3] = x - r.cenX; fp[fn * 3 + 1] = y - r.cenY; fp[fn * 3 + 2] = z - r.cenZ; fn++; }
        }
        r.coarseCells = cn;
        r.p = thin(cp, cn, MAX_P); r.pn = r.p.length / 3;
        r.f = thin(fp, fn, MAX_F); r.fn = r.f.length / 3;
        return r;
    }

    private static float[] thin(float[] a, int n, int max) {
        int step = Math.max(1, (n + max - 1) / max), m = 0;
        float[] o = new float[((n + step - 1) / step) * 3];
        for (int i = 0; i < n && m * 3 < o.length; i += step) { o[m * 3] = a[i * 3]; o[m * 3 + 1] = a[i * 3 + 1]; o[m * 3 + 2] = a[i * 3 + 2]; m++; }
        return Arrays.copyOf(o, m * 3);
    }

    /** {theta, Tx, Ty, Tz, score} in centred form: saved = R(theta) (p - cen) + T. */
    private static float score(Base b, Probes pr, float th, float tx, float ty, float tz) {
        float c = (float) Math.cos(th), s = (float) Math.sin(th);
        int h = 0;
        float[] f = pr.f;
        for (int i = 0; i < pr.fn; i++) {
            float x = f[i * 3], y = f[i * 3 + 1], z = f[i * 3 + 2];
            if (b.fine.get(key(c * x - s * z + tx, y + ty, s * x + c * z + tz, FINE)) >= 0) h++;
        }
        return h / (float) Math.max(1, pr.fn);
    }

    private static float[] refine(Base b, Probes pr, float[] g, float dth, int nth, float dt, int nt, float dy, int ny) {
        float[] best = g.clone();
        best[4] = score(b, pr, g[0], g[1], g[2], g[3]);
        for (int a = -nth; a <= nth; a++) for (int i = -nt; i <= nt; i++) for (int k = -nt; k <= nt; k++) for (int j = -ny; j <= ny; j++) {
            float th = g[0] + a * dth, tx = g[1] + i * dt, ty = g[2] + j * dy, tz = g[3] + k * dt;
            float sc = score(b, pr, th, tx, ty, tz);
            if (sc > best[4]) { best[0] = th; best[1] = tx; best[2] = ty; best[3] = tz; best[4] = sc; }
        }
        return best;
    }

    private static float[] fineSearch(Base b, Probes pr, float[] g) {
        float[] r = refine(b, pr, g, (float) Math.toRadians(1.5), 4, 0.125f, 3, 0.125f, 2);
        return refine(b, pr, r, (float) Math.toRadians(0.5), 3, 0.05f, 2, 0.05f, 2);
    }

    /** Result of one try: transform (final form), the best score and the best score of a clearly different placement. */
    static final class Result { float[] t; float score, second; }

    /** Global search: no idea where the new scan is yet. */
    static Result findGlobal(Base b, Probes pr) {
        if (b.qn < 20 || pr.pn < 20) return null;
        float r = 0;
        for (int i = 0; i < pr.pn; i++) r = Math.max(r, (float) Math.hypot(pr.p[i * 3], pr.p[i * 3 + 2]));
        float minTx = b.minX - r - COARSE, minTz = b.minZ - r - COARSE;
        int nx = (int) ((b.maxX - b.minX + 2 * r) / COARSE) + 3, nz = (int) ((b.maxZ - b.minZ + 2 * r) / COARSE) + 3, ny = 9;
        if ((long) nx * nz * ny > 3_000_000L) return null;
        float minTy = pr.cenY - 1.125f; // ARCore's origin is where the phone was at the start: about the same height in both sessions
        int[] acc = new int[nx * nz * ny];
        final int YAWS = 48;
        float[] yawVotes = new float[YAWS], yawT = new float[YAWS * 3];
        for (int k = 0; k < YAWS; k++) {
            Arrays.fill(acc, 0);
            double th = k * 2 * Math.PI / YAWS;
            float c = (float) Math.cos(th), s = (float) Math.sin(th);
            int best = 0, bi = -1;
            for (int j = 0; j < pr.pn; j++) {
                float x = pr.p[j * 3], y = pr.p[j * 3 + 1], z = pr.p[j * 3 + 2];
                float px = c * x - s * z, pz = s * x + c * z;
                for (int q = 0; q < b.qn; q++) {
                    int iy = (int) ((b.qy[q] - y - minTy) / COARSE);
                    if (iy < 0 || iy >= ny) continue;
                    int ix = (int) ((b.qx[q] - px - minTx) / COARSE), iz = (int) ((b.qz[q] - pz - minTz) / COARSE);
                    if (ix < 0 || ix >= nx || iz < 0 || iz >= nz) continue;
                    int idx = (iy * nz + iz) * nx + ix;
                    int v = ++acc[idx];
                    if (v > best) { best = v; bi = idx; }
                }
            }
            yawVotes[k] = best;
            if (bi >= 0) {
                int ix = bi % nx, iz = (bi / nx) % nz, iy = bi / (nx * nz);
                yawT[k * 3] = minTx + (ix + 0.5f) * COARSE; yawT[k * 3 + 1] = minTy + (iy + 0.5f) * COARSE; yawT[k * 3 + 2] = minTz + (iz + 0.5f) * COARSE;
            }
        }
        // the 4 strongest headings that aren't neighbours, each searched finely
        Integer[] ord = new Integer[YAWS];
        for (int k = 0; k < YAWS; k++) ord[k] = k;
        Arrays.sort(ord, (a, c) -> Float.compare(yawVotes[c], yawVotes[a]));
        java.util.List<float[]> found = new java.util.ArrayList<>();
        java.util.List<Integer> used = new java.util.ArrayList<>();
        for (int o = 0; o < YAWS && used.size() < 4; o++) {
            int k = ord[o];
            if (yawVotes[k] <= 0) break;
            boolean near = false;
            for (int u : used) { int d = Math.abs(u - k); d = Math.min(d, YAWS - d); if (d < 2) near = true; }
            if (near) continue;
            used.add(k);
            float[] g = {(float) (k * 2 * Math.PI / YAWS), yawT[k * 3], yawT[k * 3 + 1], yawT[k * 3 + 2], 0};
            found.add(fineSearch(b, pr, g));
        }
        return pick(found, pr);
    }

    /** Local search around a known placement (after it was recognised): follows ARCore's drift as the scan grows. */
    static Result findNear(Base b, Probes pr, float[] t) {
        float th = (float) Math.atan2(t[1], t[0]), c = t[0], s = t[1];
        float[] g = {th, c * pr.cenX - s * pr.cenZ + t[2], pr.cenY + t[3], s * pr.cenX + c * pr.cenZ + t[4], 0};
        float[] r = refine(b, pr, g, (float) Math.toRadians(0.5), 3, 0.05f, 2, 0.05f, 1);
        java.util.List<float[]> l = new java.util.ArrayList<>();
        l.add(r);
        return pick(l, pr);
    }

    private static Result pick(java.util.List<float[]> found, Probes pr) {
        if (found.isEmpty()) return null;
        found.sort((a, c) -> Float.compare(c[4], a[4]));
        float[] g = found.get(0);
        Result res = new Result();
        res.score = g[4];
        for (int i = 1; i < found.size(); i++) {
            float[] o = found.get(i);
            double dth = Math.abs(Math.atan2(Math.sin(o[0] - g[0]), Math.cos(o[0] - g[0])));
            if (dth > Math.toRadians(15) || Math.hypot(o[1] - g[1], o[3] - g[3]) > 1f) { res.second = o[4]; break; }
        }
        float c = (float) Math.cos(g[0]), s = (float) Math.sin(g[0]);
        res.t = new float[]{c, s, g[1] - (c * pr.cenX - s * pr.cenZ), g[2] - pr.cenY, g[3] - (s * pr.cenX + c * pr.cenZ)};
        return res;
    }
}
