package com.hackathon.setscan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * Volumetric fusion (TSDF): every depth reading votes into a sparse 3D grid of "signed distance to the
 * nearest surface". Looking at a spot again averages the votes, so wrong early readings get reworked, and
 * free space seen in front of a surface erases stale geometry. At the end a coloured triangle mesh is pulled
 * out of the grid with surface nets (one vertex per cell that the surface crosses).
 */
final class Tsdf {
    final float vs;      // voxel size (m): 4 cm for the room, ~1.2 cm for detail zones
    final float trunc;   // truncation band (m)
    final float carve;   // how far in front of a surface free space erases old geometry (m)
    static final int MAX_W = 40;        // cap the weight so later looks can still change things
    float carveWeight = 1f; // weight of a 'saw through it' observation vs a surface hit
    int minW = 2;          // voxels seen fewer times are ignored for the mesh
    int minComponent = 0;  // drop floating mesh pieces smaller than this many triangles (noise flakes)

    private final int cap;
    private final long[] keys;
    private final int[] slots;
    private final int mask;
    private final float[] sdf;
    private final short[] w;
    private final byte[] rgb;
    private final int[] at; // voxel -> its slot in the hash table (to walk the voxels without scanning the whole table)
    private int n;

    Tsdf(int maxVoxels, float voxel, float truncation, float carveDist) {
        vs = voxel; trunc = truncation; carve = carveDist;
        cap = maxVoxels;
        int tbl = Integer.highestOneBit(maxVoxels * 2 - 1) << 1;
        keys = new long[tbl];
        slots = new int[tbl];
        mask = tbl - 1;
        sdf = new float[cap];
        w = new short[cap];
        rgb = new byte[cap * 3];
        at = new int[cap];
        clear();
    }

    void clear() { Arrays.fill(keys, Long.MIN_VALUE); n = 0; }

    int size() { return n; }

    private static long key(int x, int y, int z) {
        return ((long) (x + (1 << 20)) & 0x1FFFFF) | (((long) (y + (1 << 20)) & 0x1FFFFF) << 21) | (((long) (z + (1 << 20)) & 0x1FFFFF) << 42);
    }

    private int find(long k) {
        long h = k * 0x9E3779B97F4A7C15L;
        int i = (int) (h ^ (h >>> 32)) & mask;
        while (keys[i] != Long.MIN_VALUE && keys[i] != k) i = (i + 1) & mask;
        return i;
    }

    private int get(int x, int y, int z) {
        long k = key(x, y, z);
        int i = find(k);
        return keys[i] == k ? slots[i] : -1;
    }

    private int getOrCreate(int x, int y, int z) {
        long k = key(x, y, z);
        int i = find(k);
        if (keys[i] == k) return slots[i];
        if (n >= cap) return -1;
        keys[i] = k; slots[i] = n; at[n] = i;
        sdf[n] = 0; w[n] = 0;
        return n++;
    }

    /** Forget everything inside a sphere so it gets rebuilt from fresh looks. Returns voxels reset. */
    int clearSphere(float x, float y, float z, float r) {
        int cleared = 0;
        for (int t = 0; t < keys.length; t++) {
            long k = keys[t];
            if (k == Long.MIN_VALUE) continue;
            float vx = (((int) (k & 0x1FFFFF) - (1 << 20)) + 0.5f) * vs - x;
            float vy = (((int) ((k >>> 21) & 0x1FFFFF) - (1 << 20)) + 0.5f) * vs - y;
            float vz = (((int) ((k >>> 42) & 0x1FFFFF) - (1 << 20)) + 0.5f) * vs - z;
            if (vx * vx + vy * vy + vz * vz > r * r) continue;
            int i = slots[t];
            w[i] = 0; sdf[i] = 0;
            cleared++;
        }
        return cleared;
    }

    /** One depth sample: camera at o, surface point p. Updates the voxels along the ray within the band. */
    void integrate(float ox, float oy, float oz, float px, float py, float pz, int[] c) {
        float dx = px - ox, dy = py - oy, dz = pz - oz;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-3f) return;
        dx /= len; dy /= len; dz /= len;
        int lastX = Integer.MIN_VALUE, lastY = 0, lastZ = 0;
        // Free space in front of the surface erases stale surfaces and mid-air noise (existing voxels only).
        // carve is how far back along the ray this goes (>= 10 means all the way to 25 cm from the camera).
        // A margin that grows with distance keeps depth noise from eating the real surface.
        float farS = Math.min(carve, len - 0.25f), nearS = trunc + 0.02f * len;
        for (float s = farS; s > nearS; s -= vs) {
            int vx = (int) Math.floor((px - dx * s) / vs), vy = (int) Math.floor((py - dy * s) / vs), vz = (int) Math.floor((pz - dz * s) / vs);
            int i = get(vx, vy, vz);
            if (i < 0) continue;
            int wi = w[i];
            sdf[i] = (sdf[i] * wi + carveWeight) / (wi + carveWeight); // seeing through counts extra
            if (wi < MAX_W) w[i] = (short) (wi + 1);
        }
        for (float s = -trunc; s <= trunc + 1e-4f; s += vs * 0.5f) {
            float qx = px + dx * s, qy = py + dy * s, qz = pz + dz * s;
            int vx = (int) Math.floor(qx / vs), vy = (int) Math.floor(qy / vs), vz = (int) Math.floor(qz / vs);
            if (vx == lastX && vy == lastY && vz == lastZ) continue;
            lastX = vx; lastY = vy; lastZ = vz;
            // signed distance from the voxel centre to the surface, measured along the ray (+ = in front)
            float cxv = (vx + 0.5f) * vs, cyv = (vy + 0.5f) * vs, czv = (vz + 0.5f) * vs;
            float along = (cxv - ox) * dx + (cyv - oy) * dy + (czv - oz) * dz;
            float d = (len - along) / trunc;
            if (d < -1f) continue;           // too far behind the surface: unknown, don't touch
            if (d > 1f) d = 1f;
            int i = d > 0.99f ? get(vx, vy, vz) : getOrCreate(vx, vy, vz); // free space only carves existing voxels
            if (i < 0) continue;
            int wi = w[i];
            sdf[i] = (sdf[i] * wi + d) / (wi + 1);
            if (wi < MAX_W) w[i] = (short) (wi + 1);
            if (Math.abs(d) < 0.4f) {
                int j = i * 3;
                float a = 1f / Math.min(wi + 1, 12);
                for (int k = 0; k < 3; k++) {
                    int old = rgb[j + k] & 0xFF;
                    rgb[j + k] = (byte) (wi == 0 ? c[k] : Math.round(old + (c[k] - old) * a));
                }
            }
        }
    }

    /**
     * A copy of the voxels within r of (x, y, z), for building the live mesh on another thread while scanning
     * keeps adding to this one.
     */
    Tsdf copyNear(float x, float y, float z, float r) {
        int m = 0;
        int[] pick = new int[Math.max(16, Math.min(n, 400000))];
        int nn = n; // may run on another thread while scanning adds voxels: a torn read only costs a wrong preview voxel
        for (int v = 0; v < nn && m < pick.length; v++) {
            int t = at[v];
            if (t < 0 || t >= keys.length) continue;
            long k = keys[t];
            if (k == Long.MIN_VALUE || slots[t] < 0 || slots[t] >= cap) continue;
            float vx = (((int) (k & 0x1FFFFF) - (1 << 20)) + 0.5f) * vs - x;
            float vy = (((int) ((k >>> 21) & 0x1FFFFF) - (1 << 20)) + 0.5f) * vs - y;
            float vz = (((int) ((k >>> 42) & 0x1FFFFF) - (1 << 20)) + 0.5f) * vs - z;
            if (vx * vx + vy * vy + vz * vz > r * r) continue;
            pick[m++] = t;
        }
        Tsdf c = new Tsdf(Math.max(16, m), vs, trunc, carve);
        c.minW = minW; c.minComponent = minComponent;
        for (int j = 0; j < m; j++) {
            int t = pick[j], i = slots[t];
            long k = keys[t];
            int s = c.find(k);
            if (c.keys[s] == k) continue;
            c.keys[s] = k; c.slots[s] = c.n; c.at[c.n] = s;
            c.sdf[c.n] = sdf[i]; c.w[c.n] = w[i];
            c.rgb[c.n * 3] = rgb[i * 3]; c.rgb[c.n * 3 + 1] = rgb[i * 3 + 1]; c.rgb[c.n * 3 + 2] = rgb[i * 3 + 2];
            c.n++;
        }
        return c;
    }

    /** Per mesh vertex (after extractMesh): how well observed it is, 0..255. */
    ByteBuffer meshConf;

    // ---- mesh extraction (surface nets) ----

    FloatBuffer meshPos;
    ByteBuffer meshCol;
    int meshVerts;

    private static final int[][] CORNER = {{0,0,0},{1,0,0},{0,1,0},{1,1,0},{0,0,1},{1,0,1},{0,1,1},{1,1,1}};
    private static final int[][] EDGE = {{0,1},{2,3},{4,5},{6,7},{0,2},{1,3},{4,6},{5,7},{0,4},{1,5},{2,6},{3,7}};

    /** True when depth rays have repeatedly passed through this spot: it's empty air, so a point here is noise. */
    boolean seenThrough(float x, float y, float z) {
        int i = get((int) Math.floor(x / vs), (int) Math.floor(y / vs), (int) Math.floor(z / vs));
        return i >= 0 && w[i] >= 4 && sdf[i] > 0.75f;
    }

    private boolean good(int i) { return i >= 0 && w[i] >= minW; }

    /** Builds a shaded, coloured triangle soup. Returns the triangle count. */
    int extractMesh() {
        // 1) one vertex per cell with a sign change among its 8 (well-observed) corners
        long[] cellKeys = new long[Integer.highestOneBit(Math.max(16, n) * 2 - 1) << 1];
        int[] cellVert = new int[cellKeys.length];
        Arrays.fill(cellKeys, Long.MIN_VALUE);
        int cmask = cellKeys.length - 1;
        float[] vp = new float[Math.max(16, n / 2) * 3];
        byte[] vc = new byte[vp.length];
        byte[] vw = new byte[vp.length / 3 + 1];
        int nv = 0;
        int[] ci = new int[8];
        int[][] coords = new int[n][];
        // recover voxel coords from keys
        for (int t = 0; t < keys.length; t++) {
            long k = keys[t];
            if (k == Long.MIN_VALUE) continue;
            coords[slots[t]] = new int[]{(int) (k & 0x1FFFFF) - (1 << 20), (int) ((k >>> 21) & 0x1FFFFF) - (1 << 20), (int) ((k >>> 42) & 0x1FFFFF) - (1 << 20)};
        }
        for (int i = 0; i < n; i++) {
            if (!good(i)) continue;
            int x = coords[i][0], y = coords[i][1], z = coords[i][2];
            boolean ok = true, pos = false, neg = false;
            for (int c = 0; c < 8 && ok; c++) {
                int j = c == 0 ? i : get(x + CORNER[c][0], y + CORNER[c][1], z + CORNER[c][2]);
                if (!good(j)) { ok = false; break; }
                ci[c] = j;
                if (sdf[j] >= 0) pos = true; else neg = true;
            }
            if (!ok || !pos || !neg) continue;
            float sx = 0, sy = 0, sz = 0; int cnt = 0;
            float best = 9; int bestJ = i;
            for (int[] e : EDGE) {
                float a = sdf[ci[e[0]]], b = sdf[ci[e[1]]];
                if ((a >= 0) == (b >= 0)) continue;
                if (Math.abs(a - b) > 1.6f) continue; // +1 next to -1: a truncation artefact, not a surface
                float t = a / (a - b);
                int[] p0 = CORNER[e[0]], p1 = CORNER[e[1]];
                sx += p0[0] + (p1[0] - p0[0]) * t; sy += p0[1] + (p1[1] - p0[1]) * t; sz += p0[2] + (p1[2] - p0[2]) * t;
                cnt++;
            }
            if (cnt == 0) continue;
            for (int c = 0; c < 8; c++) if (Math.abs(sdf[ci[c]]) < best) { best = Math.abs(sdf[ci[c]]); bestJ = ci[c]; }
            if (nv * 3 + 3 > vp.length) { vp = Arrays.copyOf(vp, vp.length * 2); vc = Arrays.copyOf(vc, vc.length * 2); vw = Arrays.copyOf(vw, vp.length / 3 + 1); }
            int wmin = MAX_W; for (int c = 0; c < 8; c++) wmin = Math.min(wmin, w[ci[c]]);
            vw[nv] = (byte) Math.min(255, wmin * 255 / MAX_W);
            vp[nv * 3] = (x + 0.5f + sx / cnt) * vs; vp[nv * 3 + 1] = (y + 0.5f + sy / cnt) * vs; vp[nv * 3 + 2] = (z + 0.5f + sz / cnt) * vs;
            vc[nv * 3] = rgb[bestJ * 3]; vc[nv * 3 + 1] = rgb[bestJ * 3 + 1]; vc[nv * 3 + 2] = rgb[bestJ * 3 + 2];
            long ck = key(x, y, z);
            int s = cslot(cellKeys, cmask, ck);
            cellKeys[s] = ck; cellVert[s] = nv;
            nv++;
        }
        // 2) a quad for every voxel edge that crosses the surface, joining the 4 cells around that edge
        int[] tris = new int[Math.max(16, nv * 6)];
        int nt = 0;
        int[] q = new int[4];
        for (int i = 0; i < n; i++) {
            if (!good(i)) continue;
            int x = coords[i][0], y = coords[i][1], z = coords[i][2];
            for (int axis = 0; axis < 3; axis++) {
                int j = get(x + (axis == 0 ? 1 : 0), y + (axis == 1 ? 1 : 0), z + (axis == 2 ? 1 : 0));
                if (!good(j)) continue;
                float a = sdf[i], b = sdf[j];
                if ((a >= 0) == (b >= 0) || Math.abs(a - b) > 1.6f) continue;
                boolean found = true;
                for (int k = 0; k < 4 && found; k++) {
                    int o1 = -(k & 1), o2 = -((k >> 1) & 1);
                    int cx = x, cy = y, cz = z;
                    if (axis == 0) { cy += o1; cz += o2; } else if (axis == 1) { cx += o1; cz += o2; } else { cx += o1; cy += o2; }
                    long ck = key(cx, cy, cz);
                    int s = cslot(cellKeys, cmask, ck);
                    if (cellKeys[s] != ck) found = false; else q[k] = cellVert[s];
                }
                if (!found) continue;
                if (nt * 3 + 6 > tris.length) tris = Arrays.copyOf(tris, tris.length * 2);
                // cells (0,0),(1,0),(0,1),(1,1) around the edge -> two triangles
                tris[nt * 3] = q[0]; tris[nt * 3 + 1] = q[1]; tris[nt * 3 + 2] = q[3]; nt++;
                tris[nt * 3] = q[0]; tris[nt * 3 + 1] = q[3]; tris[nt * 3 + 2] = q[2]; nt++;
            }
        }
        // 2b) drop small floating pieces: noisy depth leaves lots of little flakes hanging in the air
        if (minComponent > 0 && nt > 0) {
            int[] parent = new int[nv];
            for (int i = 0; i < nv; i++) parent[i] = i;
            for (int t = 0; t < nt; t++) { union(parent, tris[t * 3], tris[t * 3 + 1]); union(parent, tris[t * 3], tris[t * 3 + 2]); }
            int[] size = new int[nv];
            for (int t = 0; t < nt; t++) size[find(parent, tris[t * 3])]++;
            int k = 0;
            for (int t = 0; t < nt; t++) {
                if (size[find(parent, tris[t * 3])] < minComponent) continue;
                tris[k * 3] = tris[t * 3]; tris[k * 3 + 1] = tris[t * 3 + 1]; tris[k * 3 + 2] = tris[t * 3 + 2]; k++;
            }
            droppedTris = nt - k;
            nt = k;
        }
        // 3) flat-shaded triangle soup (lighting baked into the colour, two-sided)
        meshVerts = nt * 3;
        meshPos = ByteBuffer.allocateDirect(Math.max(1, meshVerts) * 12).order(ByteOrder.nativeOrder()).asFloatBuffer();
        meshCol = ByteBuffer.allocateDirect(Math.max(1, meshVerts) * 4).order(ByteOrder.nativeOrder());
        meshConf = ByteBuffer.allocateDirect(Math.max(1, meshVerts)).order(ByteOrder.nativeOrder());
        float lx = 0.35f, ly = 0.85f, lz = 0.4f;
        for (int t = 0; t < nt; t++) {
            int a = tris[t * 3], b = tris[t * 3 + 1], c = tris[t * 3 + 2];
            float ux = vp[b * 3] - vp[a * 3], uy = vp[b * 3 + 1] - vp[a * 3 + 1], uz = vp[b * 3 + 2] - vp[a * 3 + 2];
            float wx = vp[c * 3] - vp[a * 3], wy = vp[c * 3 + 1] - vp[a * 3 + 1], wz = vp[c * 3 + 2] - vp[a * 3 + 2];
            float nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
            float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            float shade = nl > 1e-9f ? 0.62f + 0.38f * Math.abs((nx * lx + ny * ly + nz * lz) / nl) : 0.8f;
            for (int v : new int[]{a, b, c}) {
                meshPos.put(vp[v * 3]).put(vp[v * 3 + 1]).put(vp[v * 3 + 2]);
                for (int k = 0; k < 3; k++) meshCol.put((byte) Math.min(255, Math.round((vc[v * 3 + k] & 0xFF) * shade)));
                meshCol.put((byte) 255);
                meshConf.put(vw[v]);
            }
        }
        meshPos.position(0); meshCol.position(0); meshConf.position(0);
        return nt;
    }

    int droppedTris;

    private static int find(int[] p, int i) { while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; } return i; }

    private static void union(int[] p, int a, int b) { a = find(p, a); b = find(p, b); if (a != b) p[a] = b; }

    private static int cslot(long[] keys, int mask, long k) {
        long h = k * 0x9E3779B97F4A7C15L;
        int i = (int) (h ^ (h >>> 32)) & mask;
        while (keys[i] != Long.MIN_VALUE && keys[i] != k) i = (i + 1) & mask;
        return i;
    }
}
