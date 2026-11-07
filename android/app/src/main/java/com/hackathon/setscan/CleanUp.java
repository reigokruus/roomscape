package com.hackathon.setscan;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Clean-up pass after a scan: find the room's big flat surfaces and use them.
 *  1. drop stray specks (points with almost no neighbours),
 *  2. find the floor and ceiling heights (peaks in the height histogram) and the walls
 *     (a Hough vote over the direction and offset of upright surfels), squared up to the room's main direction,
 *  3. snap points near a plane onto it (removes the depth noise on walls, floor and ceiling),
 *  4. fill holes in each wall and the floor from their neighbours (up to 40 cm from real data).
 * Pure Java, no Android: tested on the desktop with the exported scans.
 */
final class CleanUp {
    static final float SNAP = 0.06f;     // points within 6 cm of a plane are put onto it
    static final float CELL = 0.03f;     // hole-filling grid
    static final int FILL_CELLS = 13;    // fill at most ~40 cm away from real data

    static final class Wall {
        float nx, nz, d;            // plane: nx*x + nz*z = d (unit normal, horizontal)
        float s0, s1;               // extent along the wall (s = -nz*x + nx*z)
        int support;
        float angleDeg() { return (float) Math.toDegrees(Math.atan2(nz, nx)); }
    }

    // results
    float[] pos; byte[] col; int n;
    float floorY = Float.NaN, ceilY = Float.NaN;
    final List<Wall> walls = new ArrayList<>();
    int removed, snapped, filled;
    String summary = "", debug = "";
    private float[][] userWalls;

    /**
     * Walls found automatically were unreliable on drifted scans (scan10: lines cutting through the room), so by default
     * only the walls you marked (room corners) are used; floor and ceiling are always found automatically.
     */
    static boolean AUTO_WALLS = false;

    /** userWalls: floor-level wall segments {x0, z0, x1, z1} from your taps, or null. */
    static CleanUp run(FloatBuffer srcPos, ByteBuffer srcCol, int n0, float[][] userWalls) {
        CleanUp c = new CleanUp();
        c.userWalls = userWalls;
        float[] p = new float[n0 * 3];
        byte[] cl = new byte[n0 * 3];
        for (int i = 0; i < n0; i++) {
            p[i * 3] = srcPos.get(i * 3); p[i * 3 + 1] = srcPos.get(i * 3 + 1); p[i * 3 + 2] = srcPos.get(i * 3 + 2);
            cl[i * 3] = srcCol.get(i * 4); cl[i * 3 + 1] = srcCol.get(i * 4 + 1); cl[i * 3 + 2] = srcCol.get(i * 4 + 2);
        }
        c.process(p, cl, n0);
        return c;
    }

    private void process(float[] p, byte[] cl, int n0) {
        // normals (and neighbour counts) from the surfel builder
        FloatBuffer fb = FloatBuffer.wrap(p);
        Surfels s = Surfels.build(fb, n0);
        // 1) specks: Surfels marks points with < 5 neighbours in 5 cm as "lonely" (radius 0.012 and no normal)
        boolean[] keep = new boolean[n0];
        float[] nrm = new float[n0 * 3];
        int k = 0;
        for (int i = 0; i < n0; i++) {
            float nx = s.nrm.get(i * 4), ny = s.nrm.get(i * 4 + 1), nz = s.nrm.get(i * 4 + 2);
            boolean lonely = nx == 0 && ny == 0 && nz == 0 && s.nrm.get(i * 4 + 3) == 0.012f;
            keep[i] = !lonely;
            nrm[i * 3] = nx; nrm[i * 3 + 1] = ny; nrm[i * 3 + 2] = nz;
            if (keep[i]) k++;
        }
        if (k < n0 * 0.7f) Arrays.fill(keep, true); // never throw away most of a sparse scan
        removed = 0; for (boolean b : keep) if (!b) removed++;

        // 2a) floor and ceiling: peaks of the height histogram among upward/downward facing points
        // height range from the 1st..99th percentile (a few far-off points must not stretch it)
        float[] ys = new float[n0]; int ny0 = 0;
        for (int i = 0; i < n0; i++) if (keep[i]) ys[ny0++] = p[i * 3 + 1];
        Arrays.sort(ys, 0, ny0);
        float minY = ys[(int) (ny0 * 0.01f)] - 0.05f, maxY = ys[Math.max(0, (int) (ny0 * 0.99f) - 1)] + 0.05f;
        ys = null;
        int hb = Math.max(1, (int) ((maxY - minY) / 0.02f) + 1);
        int[] hist = new int[hb];
        for (int i = 0; i < n0; i++) if (keep[i] && Math.abs(nrm[i * 3 + 1]) > 0.85f) { int hb0 = (int) ((p[i * 3 + 1] - minY) / 0.02f); if (hb0 >= 0 && hb0 < hb) hist[hb0]++; }
        int lowPeak = -1, highPeak = -1;
        for (int b = 0; b < hb; b++) {
            float y = minY + (b + 0.5f) * 0.02f;
            if (y < minY + (maxY - minY) * 0.4f) { if (lowPeak < 0 || hist[b] > hist[lowPeak]) lowPeak = b; }
            else if (y > minY + (maxY - minY) * 0.6f) { if (highPeak < 0 || hist[b] > hist[highPeak]) highPeak = b; }
        }
        int horiz = 0; for (int h : hist) horiz += h;
        if (lowPeak >= 0 && hist[lowPeak] > Math.max(200, horiz * 0.03f)) floorY = refineHeight(p, nrm, keep, n0, minY + (lowPeak + 0.5f) * 0.02f);
        if (highPeak >= 0 && hist[highPeak] > Math.max(200, horiz * 0.03f) && (Float.isNaN(floorY) || minY + highPeak * 0.02f - floorY > 1.8f))
            ceilY = refineHeight(p, nrm, keep, n0, minY + (highPeak + 0.5f) * 0.02f);

        // 2b) walls: Hough vote of upright surfels over (direction 0..180 deg, offset)
        float yLo = Float.isNaN(floorY) ? minY : floorY + 0.15f, yHi = Float.isNaN(ceilY) ? maxY : ceilY - 0.15f;
        int TH = 180; float RHO = 0.05f; int RB = 480; // +-12 m
        int[] acc = new int[TH * RB];
        int[] dirHist = new int[90];
        for (int i = 0; i < n0; i++) {
            if (!keep[i]) continue;
            float nx = nrm[i * 3], ny = nrm[i * 3 + 1], nz = nrm[i * 3 + 2];
            if (nx * nx + nz * nz < 0.8f || Math.abs(ny) > 0.3f) continue;
            float y = p[i * 3 + 1];
            if (y < yLo || y > yHi) continue;
            double th = Math.atan2(nz, nx); if (th < 0) th += Math.PI; if (th >= Math.PI) th -= Math.PI;
            int t = (int) (th / Math.PI * TH) % TH;
            dirHist[t % 90]++;
            for (int dt = -1; dt <= 1; dt++) {
                int tt = (t + dt + TH) % TH;
                double a = (tt + 0.5) / TH * Math.PI;
                double rho = p[i * 3] * Math.cos(a) + p[i * 3 + 2] * Math.sin(a);
                int r = (int) Math.floor(rho / RHO) + RB / 2;
                if (r >= 0 && r < RB) acc[tt * RB + r]++;
            }
        }
        // main room direction (walls are mostly at right angles)
        int bestDir = 0, bestDirV = -1;
        for (int t = 0; t < 90; t++) { int v = dirHist[t] + dirHist[(t + 1) % 90] + dirHist[(t + 89) % 90]; if (v > bestDirV) { bestDirV = v; bestDir = t; } }
        int wallPts = 0; for (int v : dirHist) wallPts += v;
        int minSupport = Math.max(300, wallPts / 300);
        int accMax = 0; for (int v : acc) accMax = Math.max(accMax, v);
        debug = "wallPts " + wallPts + " accMax " + accMax + " minSupport " + minSupport + " yLo " + yLo + " yHi " + yHi + " mainDir " + bestDir;
        if (userWalls != null && userWalls.length > 0) {
            for (float[] uw : userWalls) {
                float dx = uw[2] - uw[0], dz = uw[3] - uw[1], len = (float) Math.sqrt(dx * dx + dz * dz);
                if (len < 0.2f) continue;
                Wall w = new Wall();
                w.nx = -dz / len; w.nz = dx / len; w.d = w.nx * uw[0] + w.nz * uw[1];
                float sa = -w.nz * uw[0] + w.nx * uw[1], sb = -w.nz * uw[2] + w.nx * uw[3];
                w.s0 = Math.min(sa, sb); w.s1 = Math.max(sa, sb); w.support = -1;
                walls.add(w);
            }
        }
        for (int iter = 0; iter < (AUTO_WALLS && walls.isEmpty() ? 10 : 0); iter++) {
            int best = -1;
            for (int j = 0; j < acc.length; j++) if (best < 0 || acc[j] > acc[best]) best = j;
            if (best < 0 || acc[best] < minSupport) break;
            int t = best / RB, r = best % RB;
            // suppress the neighbourhood (+-6 deg, +-20 cm), also across the 0/180 wrap
            for (int dt = -6; dt <= 6; dt++) for (int dr = -4; dr <= 4; dr++) {
                int tt = t + dt, rr = r + dr;
                if (tt < 0) { tt += TH; rr = RB - 1 - rr; } else if (tt >= TH) { tt -= TH; rr = RB - 1 - rr; }
                if (rr >= 0 && rr < RB) acc[tt * RB + rr] = 0;
            }
            double a = (t + 0.5) / TH * Math.PI;
            double d0 = (r - RB / 2 + 0.5) * RHO;
            // centre of the points on this line, so squaring it up turns it about its own middle (not the world origin)
            double cx = 0, cz = 0; int cn = 0;
            for (int i = 0; i < n0; i++) {
                if (!keep[i]) continue;
                float y = p[i * 3 + 1];
                if (y < yLo || y > yHi) continue;
                if (Math.abs(p[i * 3] * Math.cos(a) + p[i * 3 + 2] * Math.sin(a) - d0) > 0.06) continue;
                if (Math.abs(nrm[i * 3] * Math.cos(a) + nrm[i * 3 + 2] * Math.sin(a)) < 0.8) continue;
                cx += p[i * 3]; cz += p[i * 3 + 2]; cn++;
            }
            if (cn < 20) continue;
            cx /= cn; cz /= cn;
            // square it up to the room's main direction if within 6 degrees
            double main = (bestDir + 0.5) * Math.PI / TH;
            for (int q = 0; q < 2; q++) {
                double m = main + q * Math.PI / 2;
                double diff = Math.abs(((a - m) % Math.PI + Math.PI * 1.5) % Math.PI - Math.PI / 2);
                if (diff < Math.toRadians(6)) a = m;
            }
            Wall w = new Wall();
            w.nx = (float) Math.cos(a); w.nz = (float) Math.sin(a); w.d = (float) (w.nx * cx + w.nz * cz);
            refineWall(w, p, nrm, keep, n0, yLo, yHi);
            if (w.support >= minSupport && w.s1 - w.s0 > 0.8f && w.support / (w.s1 - w.s0) > 400) walls.add(w);
        }

        // 3) snap
        snapped = 0;
        for (int i = 0; i < n0; i++) {
            if (!keep[i]) continue;
            float x = p[i * 3], y = p[i * 3 + 1], z = p[i * 3 + 2];
            float best = SNAP; int which = -3;
            if (!Float.isNaN(floorY) && Math.abs(y - floorY) < best && Math.abs(nrm[i * 3 + 1]) > 0.7f) { best = Math.abs(y - floorY); which = -1; }
            if (!Float.isNaN(ceilY) && Math.abs(y - ceilY) < best && Math.abs(nrm[i * 3 + 1]) > 0.7f) { best = Math.abs(y - ceilY); which = -2; }
            for (int wi = 0; wi < walls.size(); wi++) {
                Wall w = walls.get(wi);
                float dist = Math.abs(w.nx * x + w.nz * z - w.d);
                float sAlong = -w.nz * x + w.nx * z;
                if (dist >= best || sAlong < w.s0 - 0.05f || sAlong > w.s1 + 0.05f) continue;
                float nn = Math.abs(nrm[i * 3] * w.nx + nrm[i * 3 + 2] * w.nz);
                if (nn < 0.7f) continue;
                best = dist; which = wi;
            }
            if (which == -3) continue;
            if (which == -1) p[i * 3 + 1] = floorY;
            else if (which == -2) p[i * 3 + 1] = ceilY;
            else { Wall w = walls.get(which); float dd = w.nx * x + w.nz * z - w.d; p[i * 3] = x - w.nx * dd; p[i * 3 + 2] = z - w.nz * dd; }
            snapped++;
        }

        // 4) fill holes on walls and floor
        List<float[]> addP = new ArrayList<>(); List<byte[]> addC = new ArrayList<>();
        for (Wall w : walls) {
            float y0 = Float.isNaN(floorY) ? yLo : floorY, y1 = Float.isNaN(ceilY) ? yHi : ceilY;
            fillPlane(p, cl, keep, n0, addP, addC, w.s0, w.s1, y0, y1,
                    (x, y, z) -> Math.abs(w.nx * x + w.nz * z - w.d) < 0.005f,
                    (x, y, z) -> new float[]{-w.nz * x + w.nx * z, y},
                    (a, b) -> new float[]{w.nx * w.d - w.nz * a, b, w.nz * w.d + w.nx * a});
        }
        if (!Float.isNaN(floorY)) {
            float x0 = 1e9f, x1 = -1e9f, z0 = 1e9f, z1 = -1e9f;
            for (int i = 0; i < n0; i++) if (keep[i] && p[i * 3 + 1] == floorY) {
                x0 = Math.min(x0, p[i * 3]); x1 = Math.max(x1, p[i * 3]); z0 = Math.min(z0, p[i * 3 + 2]); z1 = Math.max(z1, p[i * 3 + 2]);
            }
            final float fy = floorY;
            if (x1 > x0) fillPlane(p, cl, keep, n0, addP, addC, x0, x1, z0, z1,
                    (x, y, z) -> y == fy,
                    (x, y, z) -> new float[]{x, z},
                    (a, b) -> new float[]{a, fy, b});
        }
        filled = addP.size();

        // pack
        n = 0;
        for (int i = 0; i < n0; i++) if (keep[i]) n++;
        n += filled;
        pos = new float[n * 3]; col = new byte[n * 3];
        int j = 0;
        for (int i = 0; i < n0; i++) if (keep[i]) {
            System.arraycopy(p, i * 3, pos, j * 3, 3); System.arraycopy(cl, i * 3, col, j * 3, 3); j++;
        }
        for (int i = 0; i < filled; i++) { System.arraycopy(addP.get(i), 0, pos, j * 3, 3); System.arraycopy(addC.get(i), 0, col, j * 3, 3); j++; }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(java.util.Locale.US, "Clean-up: %d walls%s", walls.size(), walls.isEmpty() ? " (mark room corners to straighten walls)" : userWalls != null ? " (from your corners)" : ""));
        if (!Float.isNaN(floorY)) sb.append(", floor");
        if (!Float.isNaN(ceilY)) sb.append(String.format(java.util.Locale.US, ", ceiling (%.2f m high)", ceilY - floorY));
        sb.append(String.format(java.util.Locale.US, ". Snapped %,d points onto them, removed %,d specks, filled %,d gaps.", snapped, removed, filled));
        summary = sb.toString();
    }

    private static float refineHeight(float[] p, float[] nrm, boolean[] keep, int n, float y) {
        double s = 0; int c = 0;
        for (int i = 0; i < n; i++) if (keep[i] && Math.abs(nrm[i * 3 + 1]) > 0.85f && Math.abs(p[i * 3 + 1] - y) < 0.04f) { s += p[i * 3 + 1]; c++; }
        return c > 0 ? (float) (s / c) : y;
    }

    private static void refineWall(Wall w, float[] p, float[] nrm, boolean[] keep, int n, float yLo, float yHi) {
        // offset = median of inliers; extent = 2nd..98th percentile along the wall
        float[] ds = new float[n], ss = new float[n]; int c = 0;
        for (int it = 0; it < 2; it++) {
            c = 0;
            for (int i = 0; i < n; i++) {
                if (!keep[i]) continue;
                float y = p[i * 3 + 1];
                if (y < yLo || y > yHi) continue;
                float d = w.nx * p[i * 3] + w.nz * p[i * 3 + 2] - w.d;
                if (Math.abs(d) > 0.05f) continue;
                if (Math.abs(nrm[i * 3] * w.nx + nrm[i * 3 + 2] * w.nz) < 0.8f) continue;
                ds[c] = d; ss[c] = -w.nz * p[i * 3] + w.nx * p[i * 3 + 2]; c++;
            }
            if (c < 20) { w.support = c; return; }
            float[] dd = Arrays.copyOf(ds, c); Arrays.sort(dd);
            w.d += dd[c / 2];
        }
        float[] s = Arrays.copyOf(ss, c); Arrays.sort(s);
        // a real wall is one continuous stretch: take the longest run of 10 cm bins with points (gaps up to 30 cm)
        float lo = s[0];
        int nb = Math.min(4000, (int) ((s[c - 1] - lo) / 0.1f) + 1);
        int[] bins = new int[nb];
        for (int i = 0; i < c; i++) bins[Math.min(nb - 1, (int) ((s[i] - lo) / 0.1f))]++;
        int bestA = 0, bestB = -1, runA = -1, lastFull = -10, bestCount = 0, runCount = 0;
        for (int b = 0; b < nb; b++) {
            if (bins[b] < 3) continue;
            if (b - lastFull > 3) { runA = b; runCount = 0; }
            runCount += bins[b]; lastFull = b;
            if (runCount > bestCount) { bestCount = runCount; bestA = runA; bestB = b; }
        }
        w.s0 = lo + bestA * 0.1f; w.s1 = lo + (bestB + 1) * 0.1f;
        w.support = bestCount;
        // and it stands up: its points must cover at least 80 cm of height
        float[] hy = new float[c]; int hn = 0;
        for (int i = 0; i < n; i++) {
            if (!keep[i]) continue;
            float y = p[i * 3 + 1];
            if (y < yLo || y > yHi) continue;
            float d = w.nx * p[i * 3] + w.nz * p[i * 3 + 2] - w.d, sa = -w.nz * p[i * 3] + w.nx * p[i * 3 + 2];
            if (Math.abs(d) > 0.05f || sa < w.s0 || sa > w.s1 || hn >= c) continue;
            if (Math.abs(nrm[i * 3] * w.nx + nrm[i * 3 + 2] * w.nz) < 0.8f) continue;
            hy[hn++] = y;
        }
        if (hn > 20) { Arrays.sort(hy, 0, hn); if (hy[(int) (hn * 0.9f)] - hy[(int) (hn * 0.1f)] < 0.8f) w.support = 0; }
        else w.support = 0;
    }

    interface Test { boolean on(float x, float y, float z); }
    interface To2D { float[] map(float x, float y, float z); }
    interface To3D { float[] map(float a, float b); }

    /** Rasterise a plane's points into 3 cm cells and fill empty cells near filled ones (by repeated dilation). */
    private static void fillPlane(float[] p, byte[] cl, boolean[] keep, int n, List<float[]> addP, List<byte[]> addC,
                                  float a0, float a1, float b0, float b1, Test on, To2D to2, To3D to3) {
        int W = (int) ((a1 - a0) / CELL) + 1, H = (int) ((b1 - b0) / CELL) + 1;
        if (W < 2 || H < 2 || (long) W * H > 4_000_000L) return;
        int[] r = new int[W * H], g = new int[W * H], b = new int[W * H], cnt = new int[W * H];
        for (int i = 0; i < n; i++) {
            if (!keep[i]) continue;
            float x = p[i * 3], y = p[i * 3 + 1], z = p[i * 3 + 2];
            if (!on.on(x, y, z)) continue;
            float[] ab = to2.map(x, y, z);
            int u = (int) ((ab[0] - a0) / CELL), v = (int) ((ab[1] - b0) / CELL);
            if (u < 0 || v < 0 || u >= W || v >= H) continue;
            int q = v * W + u;
            r[q] += cl[i * 3] & 255; g[q] += cl[i * 3 + 1] & 255; b[q] += cl[i * 3 + 2] & 255; cnt[q]++;
        }
        boolean[] real = new boolean[W * H];
        for (int q = 0; q < W * H; q++) if (cnt[q] > 0) { real[q] = true; r[q] /= cnt[q]; g[q] /= cnt[q]; b[q] /= cnt[q]; cnt[q] = 1; }
        for (int step = 0; step < FILL_CELLS; step++) {
            int[] nr = r.clone(), ng = g.clone(), nb = b.clone(), nc = cnt.clone();
            boolean any = false;
            for (int v = 0; v < H; v++) for (int u = 0; u < W; u++) {
                int q = v * W + u;
                if (cnt[q] > 0) continue;
                int sr = 0, sg = 0, sb = 0, k = 0;
                if (u > 0 && cnt[q - 1] > 0) { sr += r[q - 1]; sg += g[q - 1]; sb += b[q - 1]; k++; }
                if (u < W - 1 && cnt[q + 1] > 0) { sr += r[q + 1]; sg += g[q + 1]; sb += b[q + 1]; k++; }
                if (v > 0 && cnt[q - W] > 0) { sr += r[q - W]; sg += g[q - W]; sb += b[q - W]; k++; }
                if (v < H - 1 && cnt[q + W] > 0) { sr += r[q + W]; sg += g[q + W]; sb += b[q + W]; k++; }
                if (k < 2) continue; // grow only into gaps (two filled sides), not out past the edges
                nr[q] = sr / k; ng[q] = sg / k; nb[q] = sb / k; nc[q] = 1; any = true;
            }
            r = nr; g = ng; b = nb; cnt = nc;
            if (!any) break;
        }
        for (int v = 0; v < H; v++) for (int u = 0; u < W; u++) {
            int q = v * W + u;
            if (cnt[q] == 0 || real[q]) continue;
            addP.add(to3.map(a0 + (u + 0.5f) * CELL, b0 + (v + 0.5f) * CELL));
            addC.add(new byte[]{(byte) r[q], (byte) g[q], (byte) b[q]});
        }
    }
}
