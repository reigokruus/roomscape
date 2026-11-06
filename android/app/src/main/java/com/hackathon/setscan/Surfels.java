package com.hackathon.setscan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * On-phone "gaussian splats" without training: every scanned point becomes a small soft disc (a surfel)
 * lying flat on its surface. For each point we look at its neighbours within 5 cm:
 *  - the flattest direction of the neighbours (PCA) is the surface normal, so the disc lies on the wall/floor/object;
 *  - how many neighbours there are sets the disc size, so sparse areas still close up and dense areas stay sharp;
 *  - on clearly flat patches the point is pulled onto the fitted plane, which removes most of the
 *    5-10 cm depth noise of the S24 without moving edges much.
 * Output per point: smoothed x,y,z and nx,ny,nz,radius (normal 0,0,0 = no clear surface, draw facing the camera).
 */
final class Surfels {
    static final float R = 0.05f;       // neighbour radius (m)
    static final float CELL = 0.05f;    // grid cell = radius, so a 3x3x3 block covers every neighbour
    static final int MAX_NB = 48;

    final FloatBuffer pos, raw, nrm; // 3, 3 and 4 floats per point, direct buffers ready for GL (raw = where the splat starts its settle animation)
    final ByteBuffer col;            // rgba, a = opacity
    int n; // may be less than the buffers' capacity (reused by the Trainer)
    int flat, curved, lonely;
    boolean trained;                 // from the Trainer (no settle animation)

    private Surfels(int n) {
        this.n = n;
        pos = ByteBuffer.allocateDirect(Math.max(1, n) * 12).order(ByteOrder.nativeOrder()).asFloatBuffer();
        raw = ByteBuffer.allocateDirect(Math.max(1, n) * 12).order(ByteOrder.nativeOrder()).asFloatBuffer();
        nrm = ByteBuffer.allocateDirect(Math.max(1, n) * 16).order(ByteOrder.nativeOrder()).asFloatBuffer();
        col = ByteBuffer.allocateDirect(Math.max(1, n) * 4).order(ByteOrder.nativeOrder());
    }

    static Surfels empty(int n) { return new Surfels(n); }

    /** Surfels for the points src/srcCol (colours copied, opacity 1). */
    static Surfels build(FloatBuffer src, ByteBuffer srcCol, int n) {
        Surfels s = build(src, n);
        for (int i = 0; i < n * 4; i++) s.col.put(i, (i & 3) == 3 ? (byte) 255 : srcCol.get(i));
        return s;
    }

    private static long cellKey(int ix, int iy, int iz) {
        return ((long) (ix + 1024) & 2047) | (((long) (iy + 1024) & 2047) << 11) | (((long) (iz + 1024) & 2047) << 22);
    }

    static Surfels build(FloatBuffer src, int n) {
        Surfels s = new Surfels(n);
        float[] p = new float[n * 3];
        for (int i = 0; i < n * 3; i++) p[i] = src.get(i);
        // sort point indices by grid cell (cell key in the high bits, index in the low 21 bits)
        long[] ki = new long[n];
        for (int i = 0; i < n; i++) {
            int ix = (int) Math.floor(p[i * 3] / CELL), iy = (int) Math.floor(p[i * 3 + 1] / CELL), iz = (int) Math.floor(p[i * 3 + 2] / CELL);
            ki[i] = (cellKey(ix, iy, iz) << 21) | i;
        }
        Arrays.sort(ki);
        int uniq = 0;
        long[] keys = new long[n]; int[] start = new int[n + 1];
        for (int j = 0; j < n; j++) {
            long k = ki[j] >>> 21;
            if (uniq == 0 || keys[uniq - 1] != k) { keys[uniq] = k; start[uniq] = j; uniq++; }
        }
        start[uniq] = n;
        int[] order = new int[n];
        for (int j = 0; j < n; j++) order[j] = (int) (ki[j] & 0x1FFFFF);
        ki = null;

        int[] nb = new int[MAX_NB];
        float[] d2s = new float[MAX_NB];
        double[] c = new double[9], v = new double[9], e = new double[3];
        float r2max = R * R;
        for (int i = 0; i < n; i++) {
            float x = p[i * 3], y = p[i * 3 + 1], z = p[i * 3 + 2];
            int cx = (int) Math.floor(x / CELL), cy = (int) Math.floor(y / CELL), cz = (int) Math.floor(z / CELL);
            int m = 0, found = 0; float worst = 0; int worstAt = 0;
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                int u = Arrays.binarySearch(keys, 0, uniq, cellKey(cx + dx, cy + dy, cz + dz));
                if (u < 0) continue;
                for (int j = start[u]; j < start[u + 1]; j++) {
                    int q = order[j];
                    float ax = p[q * 3] - x, ay = p[q * 3 + 1] - y, az = p[q * 3 + 2] - z;
                    float d2 = ax * ax + ay * ay + az * az;
                    if (d2 > r2max) continue;
                    found++;
                    if (m < MAX_NB) { nb[m] = q; d2s[m] = d2; if (d2 > worst) { worst = d2; worstAt = m; } m++; }
                    else if (d2 < worst) { // keep the closest MAX_NB
                        nb[worstAt] = q; d2s[worstAt] = d2;
                        worst = 0; for (int t = 0; t < m; t++) if (d2s[t] > worst) { worst = d2s[t]; worstAt = t; }
                    }
                }
            }
            float ox = x, oy = y, oz = z, nx = 0, ny = 0, nz = 0;
            // disc radius from density: `found` points share the pi*R^2 patch around this one
            float spacing = (float) Math.sqrt(Math.PI * r2max / Math.max(1, found));
            float rad = Math.max(0.008f, Math.min(0.035f, spacing * 0.75f));
            if (m < 5) { s.lonely++; rad = 0.012f; }
            else {
                double mx = 0, my = 0, mz = 0;
                for (int t = 0; t < m; t++) { int q = nb[t]; mx += p[q * 3]; my += p[q * 3 + 1]; mz += p[q * 3 + 2]; }
                mx /= m; my /= m; mz /= m;
                Arrays.fill(c, 0);
                for (int t = 0; t < m; t++) {
                    int q = nb[t];
                    double ax = p[q * 3] - mx, ay = p[q * 3 + 1] - my, az = p[q * 3 + 2] - mz;
                    c[0] += ax * ax; c[1] += ax * ay; c[2] += ax * az; c[4] += ay * ay; c[5] += ay * az; c[8] += az * az;
                }
                c[3] = c[1]; c[6] = c[2]; c[7] = c[5];
                jacobi(c, v, e);
                int lo = 0; for (int t = 1; t < 3; t++) if (e[t] < e[lo]) lo = t;
                double sum = Math.max(1e-12, e[0] + e[1] + e[2]);
                double curv = e[lo] / sum; // 0 = perfectly flat, 1/3 = a blob
                nx = (float) v[lo]; ny = (float) v[3 + lo]; nz = (float) v[6 + lo];
                if (curv < 0.22) {
                    s.flat++;
                    // pull onto the local plane: fully when clearly flat, fading out towards edges/corners
                    float w = (float) Math.max(0, Math.min(1, (0.22 - curv) / 0.12));
                    float off = (float) ((x - mx) * nx + (y - my) * ny + (z - mz) * nz);
                    ox = x - nx * off * w; oy = y - ny * off * w; oz = z - nz * off * w;
                } else { s.curved++; nx = ny = nz = 0; }
            }
            s.raw.put(i * 3, x); s.raw.put(i * 3 + 1, y); s.raw.put(i * 3 + 2, z);
            s.pos.put(i * 3, ox); s.pos.put(i * 3 + 1, oy); s.pos.put(i * 3 + 2, oz);
            s.nrm.put(i * 4, nx); s.nrm.put(i * 4 + 1, ny); s.nrm.put(i * 4 + 2, nz); s.nrm.put(i * 4 + 3, rad);
        }
        return s;
    }

    /** Eigen-decomposition of a symmetric 3x3 (row-major a), eigenvectors in the columns of v. */
    static void jacobi(double[] a0, double[] v, double[] e) {
        double[] a = a0.clone();
        for (int i = 0; i < 9; i++) v[i] = (i % 4 == 0) ? 1 : 0;
        for (int sweep = 0; sweep < 12; sweep++) {
            double off = a[1] * a[1] + a[2] * a[2] + a[5] * a[5];
            if (off < 1e-20) break;
            for (int pq = 0; pq < 3; pq++) {
                int pp = pq == 2 ? 1 : 0, qq = pq == 0 ? 1 : 2;
                double apq = a[pp * 3 + qq];
                if (Math.abs(apq) < 1e-30) continue;
                double theta = (a[qq * 3 + qq] - a[pp * 3 + pp]) / (2 * apq);
                double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                if (theta == 0) t = 1;
                double cs = 1 / Math.sqrt(t * t + 1), sn = t * cs;
                for (int k = 0; k < 3; k++) { // a = J^T a J
                    double akp = a[k * 3 + pp], akq = a[k * 3 + qq];
                    a[k * 3 + pp] = cs * akp - sn * akq; a[k * 3 + qq] = sn * akp + cs * akq;
                }
                for (int k = 0; k < 3; k++) {
                    double apk = a[pp * 3 + k], aqk = a[qq * 3 + k];
                    a[pp * 3 + k] = cs * apk - sn * aqk; a[qq * 3 + k] = sn * apk + cs * aqk;
                }
                for (int k = 0; k < 3; k++) {
                    double vkp = v[k * 3 + pp], vkq = v[k * 3 + qq];
                    v[k * 3 + pp] = cs * vkp - sn * vkq; v[k * 3 + qq] = sn * vkp + cs * vkq;
                }
            }
        }
        e[0] = a[0]; e[1] = a[4]; e[2] = a[8];
    }
}
