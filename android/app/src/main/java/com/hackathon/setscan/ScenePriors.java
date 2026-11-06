package com.hackathon.setscan;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Common-sense guesses for what the camera couldn't see properly:
 *  - floors are flat: surface bumps near the floor that no scanned point backs up are pressed flat,
 *    and small unseen floor gaps (behind and under things) are filled with floor;
 *  - thin upright things (chair and table legs, poles) are solid: they're rebuilt as square posts.
 */
final class ScenePriors {

    private static final float C = 0.04f; // grid cell for the floor checks
    private static final int OFF = 1 << 15;

    private static long key(int a, int b) { return ((long) (a + OFF) << 20) | (b + OFF); }

    /** Presses down mesh bumps near the floor that no points above the floor support. Returns vertices moved. */
    static int flattenFloor(FloatBuffer mesh, int verts, FloatBuffer pos, int count, float floorY) {
        // highest supported point per 4 cm column (needs 2 points, ignores the floor itself)
        HashMap<Long, float[]> top = new HashMap<>();
        for (int i = 0; i < count; i++) {
            float y = pos.get(i * 3 + 1);
            if (y < floorY + 0.03f || y > floorY + 0.6f) continue;
            long k = key((int) Math.floor(pos.get(i * 3) / C), (int) Math.floor(pos.get(i * 3 + 2) / C));
            float[] t = top.get(k);
            if (t == null) top.put(k, new float[]{y, 1, -1e9f});
            else { t[1]++; if (y > t[0]) { t[2] = t[0]; t[0] = y; } else if (y > t[2]) t[2] = y; }
        }
        int moved = 0;
        for (int v = 0; v < verts; v++) {
            float y = mesh.get(v * 3 + 1);
            if (y > floorY + 0.25f) continue;
            int cx = (int) Math.floor(mesh.get(v * 3) / C), cz = (int) Math.floor(mesh.get(v * 3 + 2) / C);
            float support = floorY;
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++) {
                    float[] t = top.get(key(cx + dx, cz + dz));
                    if (t != null && t[1] >= 2) support = Math.max(support, t[2] > -1e8f ? t[2] : t[0]); // 2nd highest: one speck isn't support
                }
            if (y > support + 0.04f || y < floorY - 0.03f) {
                mesh.put(v * 3 + 1, y < floorY ? floorY : Math.max(floorY, support));
                moved++;
            }
        }
        return moved;
    }

    /** All the guesses at once (after the room model is built). The mesh is flattened in place. */
    static Fill apply(FloatBuffer mesh, int meshVerts, RoomModel room, FloatBuffer pos, ByteBuffer col, int count) {
        Fill f = new Fill();
        f.flattened = flattenFloor(mesh, meshVerts, pos, count, room.floorY);
        if (!room.hasLayout && room.grid != null) fillFloorGaps(f, room.grid, pos, col, count, room.floorY);
        addPosts(f, pos, col, count, room.floorY);
        return f;
    }

    static float[] concat(float[] a, float[] b) { float[] o = java.util.Arrays.copyOf(a, a.length + b.length); System.arraycopy(b, 0, o, a.length, b.length); return o; }
    static byte[] concat(byte[] a, byte[] b) { byte[] o = java.util.Arrays.copyOf(a, a.length + b.length); System.arraycopy(b, 0, o, a.length, b.length); return o; }

    /** Proxy triangles (with colours) the photo texturing can paint: floor gaps and solid posts for legs. */
    static final class Fill {
        final List<float[]> tris = new ArrayList<>();
        final List<int[]> cols = new ArrayList<>();
        int floorCells, posts, flattened;

        @Override public String toString() { return "priors: " + flattened + " floor bumps pressed flat, " + floorCells + " floor gaps filled, " + posts + " legs/posts"; }

        float[] triArray() {
            int n = 0; for (float[] t : tris) n += t.length;
            float[] out = new float[n]; int k = 0;
            for (float[] t : tris) { System.arraycopy(t, 0, out, k, t.length); k += t.length; }
            return out;
        }

        byte[] colArray() {
            int n = 0; for (float[] t : tris) n += t.length / 3;
            byte[] out = new byte[n * 4]; int k = 0;
            for (int i = 0; i < tris.size(); i++) {
                int[] c = cols.get(i);
                for (int v = 0; v < tris.get(i).length / 3; v++) { out[k++] = (byte) c[0]; out[k++] = (byte) c[1]; out[k++] = (byte) c[2]; out[k++] = (byte) 255; }
            }
            return out;
        }
    }

    /** Unseen floor cells mostly surrounded by seen floor become floor (only when no room layout gives a floor). */
    static void fillFloorGaps(Fill f, ShotPlanner g, FloatBuffer pos, ByteBuffer col, int count, float floorY) {
        int[] fc = floorColour(pos, col, count, floorY);
        float cell = ShotPlanner.CELL;
        int reach = (int) Math.ceil(0.4f / cell);
        for (int iz = 0; iz < g.nz; iz++) {
            for (int ix = 0; ix < g.nx; ix++) {
                int i = iz * g.nx + ix;
                if (g.floorKnown[i]) continue;
                // seen floor within 40 cm in all 4 directions = an enclosed gap (behind / under something)
                int sides = 0;
                int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] d : dirs) {
                    for (int s = 1; s <= reach; s++) {
                        int jx = ix + d[0] * s, jz = iz + d[1] * s;
                        if (jx < 0 || jz < 0 || jx >= g.nx || jz >= g.nz) break;
                        if (g.floorKnown[jz * g.nx + jx]) { sides++; break; }
                    }
                }
                if (sides < 4) continue;
                float x0 = g.minX + ix * cell, z0 = g.minZ + iz * cell, x1 = x0 + cell, z1 = z0 + cell, y = floorY;
                f.tris.add(new float[]{x0, y, z0, x1, y, z1, x1, y, z0, x0, y, z0, x0, y, z1, x1, y, z1});
                f.cols.add(fc);
                f.floorCells++;
            }
        }
    }

    private static int[] floorColour(FloatBuffer pos, ByteBuffer col, int count, float floorY) {
        long r = 0, gg = 0, b = 0; int n = 0;
        for (int i = 0; i < count; i += 3) {
            if (Math.abs(pos.get(i * 3 + 1) - floorY) > 0.03f) continue;
            r += col.get(i * 4) & 0xFF; gg += col.get(i * 4 + 1) & 0xFF; b += col.get(i * 4 + 2) & 0xFF; n++;
        }
        return n == 0 ? new int[]{110, 100, 90} : new int[]{(int) (r / n), (int) (gg / n), (int) (b / n)};
    }

    /**
     * Legs and poles: 3 cm columns with points spread over at least 30 cm of height, grouped with their neighbours;
     * a group no wider than 10 cm is a leg. It becomes a square post from its lowest to highest point
     * (down to the floor when it starts near it).
     */
    static void addPosts(Fill f, FloatBuffer pos, ByteBuffer col, int count, float floorY) {
        final float cs = 0.03f, hs = 0.03f;
        int bins = (int) (1.3f / hs);
        HashMap<Long, int[]> colBins = new HashMap<>(); // per column: count per height bin, then r,g,b sums, n
        for (int i = 0; i < count; i++) {
            float y = pos.get(i * 3 + 1) - floorY;
            if (y < 0.02f || y >= 1.3f) continue;
            long k = key((int) Math.floor(pos.get(i * 3) / cs), (int) Math.floor(pos.get(i * 3 + 2) / cs));
            int[] c = colBins.get(k);
            if (c == null) { c = new int[bins + 4]; colBins.put(k, c); }
            c[(int) (y / hs)]++;
            c[bins] += col.get(i * 4) & 0xFF; c[bins + 1] += col.get(i * 4 + 1) & 0xFF; c[bins + 2] += col.get(i * 4 + 2) & 0xFF; c[bins + 3]++;
        }
        // tall thin columns: longest run of occupied bins (gaps of one bin allowed) >= 30 cm
        HashMap<Long, int[]> tall = new HashMap<>(); // key -> {lo, hi}
        for (java.util.Map.Entry<Long, int[]> e : colBins.entrySet()) {
            int[] c = e.getValue();
            int bestLo = -1, bestHi = -1, lo = -1, gap = 0;
            for (int b = 0; b < bins; b++) {
                if (c[b] > 0) { if (lo < 0) lo = b; gap = 0; if (b - lo > bestHi - bestLo) { bestLo = lo; bestHi = b; } }
                else if (lo >= 0 && ++gap > 1) { lo = -1; gap = 0; }
            }
            if (bestLo >= 0 && (bestHi - bestLo + 1) * hs >= 0.3f) tall.put(e.getKey(), new int[]{bestLo, bestHi});
        }
        HashMap<Long, Boolean> seen = new HashMap<>();
        for (Long k0 : tall.keySet()) {
            if (seen.containsKey(k0)) continue;
            List<Long> grp = new ArrayList<>();
            ArrayList<Long> st = new ArrayList<>(); st.add(k0); seen.put(k0, true);
            while (!st.isEmpty()) {
                long k = st.remove(st.size() - 1); grp.add(k);
                int ax = (int) ((k >>> 20) - OFF), az = (int) ((k & 0xFFFFF) - OFF);
                for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                    long n = key(ax + dx, az + dz);
                    if (tall.containsKey(n) && !seen.containsKey(n)) { seen.put(n, true); st.add(n); }
                }
            }
            int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE, lo = bins, hi = 0;
            long r = 0, gg = 0, b = 0, n = 0;
            for (long k : grp) {
                int ax = (int) ((k >>> 20) - OFF), az = (int) ((k & 0xFFFFF) - OFF);
                x0 = Math.min(x0, ax); x1 = Math.max(x1, ax); z0 = Math.min(z0, az); z1 = Math.max(z1, az);
                int[] t = tall.get(k); lo = Math.min(lo, t[0]); hi = Math.max(hi, t[1]);
                int[] c = colBins.get(k); r += c[bins]; gg += c[bins + 1]; b += c[bins + 2]; n += c[bins + 3];
            }
            float w = (x1 - x0 + 1) * cs, d = (z1 - z0 + 1) * cs;
            if (w > 0.1f || d > 0.1f || grp.size() > 8) continue; // wider than a leg: a wall edge or furniture side
            // a post is at least 3 cm and at most 6 cm thick, centred on the group
            float cx = (x0 + x1 + 1) * cs / 2, cz = (z0 + z1 + 1) * cs / 2;
            float hw = Math.max(0.015f, Math.min(0.03f, w / 2)), hd = Math.max(0.015f, Math.min(0.03f, d / 2));
            float yb = floorY + (lo * hs < 0.12f ? 0 : lo * hs), yt = floorY + (hi + 1) * hs;
            int[] c = n > 0 ? new int[]{(int) (r / n), (int) (gg / n), (int) (b / n)} : new int[]{60, 60, 60};
            addPrism(f, cx - hw, cz - hd, cx + hw, cz + hd, yb, yt, c);
            f.posts++;
        }
    }

    private static void addPrism(Fill f, float x0, float z0, float x1, float z1, float y0, float y1, int[] c) {
        float[][] sides = {
                {x0, z0, x1, z0}, {x1, z0, x1, z1}, {x1, z1, x0, z1}, {x0, z1, x0, z0}};
        for (float[] s : sides) {
            f.tris.add(new float[]{s[0], y0, s[1], s[2], y0, s[3], s[2], y1, s[3], s[0], y0, s[1], s[2], y1, s[3], s[0], y1, s[1]});
            f.cols.add(c);
        }
        f.tris.add(new float[]{x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z0, x1, y1, z1, x0, y1, z1});
        f.cols.add(c);
    }
}
