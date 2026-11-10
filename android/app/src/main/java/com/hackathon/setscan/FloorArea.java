package com.hackathon.setscan;

import java.util.ArrayDeque;

/**
 * Floor area from the scan (a teammate's request, 10 Nov): the floor's real footprint, not width × length. The points
 * go onto a 5 cm grid over the floor: floor cells (points on the floor, or just below it: glossy floors reflect),
 * furniture cells (something standing on the floor: the floor under it still counts) and wall cells (a surface that
 * goes from low to above head height). Small gaps are closed (15 cm), holes the walls enclose are filled, and doorways
 * are cut off by opening the area with a 45 cm disc (a doorway is 70-90 cm wide, so it disappears) and keeping the
 * biggest part: the next room seen through a door doesn't count. Plain Java (no Android), so tools can run it too:
 *   java FloorArea.java <scan folder> [floorY]
 */
final class FloorArea {
    static final float CELL = 0.05f;

    static final class Result {
        float area;          // m², the main room
        float other;         // m² of floor in other parts (another room through a doorway)
        float enclosed;      // share of the room's edge that is a wall (low = open outline, partly scanned)
        /** Each room (the floor split at doorways), biggest first. */
        final java.util.ArrayList<Room> rooms = new java.util.ArrayList<>();
        int[] roomOf;        // per cell: room index + 1, 0 = none
        int nx, nz; float x0, z0;
        byte[] grid;         // 0 none, 1 floor seen, 2 furniture, 3 wall, 4 filled (counted, not seen); +8 = in the room
        /** The line for the result: "Floor area ≈ 14.2 m²", "about" when the outline is open, "scanned area" with more parts. */
        String text() {
            if (area < 0.5f) return null;
            String a = area < 10 ? String.format(java.util.Locale.US, "%.1f", area) : String.format(java.util.Locale.US, "%.0f", area);
            if (rooms.size() >= 2) {
                float all = 0; for (Room rm : rooms) all += rm.area;
                return String.format(java.util.Locale.US, "%d rooms, floor ≈%.0f m² in all (tap a room)", rooms.size(), all);
            }
            return (enclosed < 0.6f ? "floor area about " : "floor area ≈") + a + " m²";
        }
    }

    static final class Room {
        float area, w, l;    // m², and the size along its own walls (the smallest rectangle around it), metres
        int cells;
        String text(int i) { return String.format(java.util.Locale.US, "Room %d: %s m², %.1f × %.1f m", i + 1, area < 10 ? String.format(java.util.Locale.US, "%.1f", area) : String.format(java.util.Locale.US, "%.0f", area), w, l); }
    }

    /** pos: x, y, z per point (count points); floorY: the floor's height. */
    static Result compute(float[] pos, int count, float floorY) { return compute(java.nio.FloatBuffer.wrap(pos), count, floorY); }

    static Result compute(java.nio.FloatBuffer pos, int count, float floorY) {
        float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, z0 = Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            float h = pos.get(i * 3 + 1) - floorY; if (h < -0.4f || h > 2.4f) continue;
            x0 = Math.min(x0, pos.get(i * 3)); x1 = Math.max(x1, pos.get(i * 3)); z0 = Math.min(z0, pos.get(i * 3 + 2)); z1 = Math.max(z1, pos.get(i * 3 + 2));
        }
        Result r = new Result();
        if (x1 <= x0 || z1 <= z0 || (x1 - x0) * (z1 - z0) > 60 * 60) return r;   // nothing, or something odd
        x0 -= 1; z0 -= 1; x1 += 1; z1 += 1;
        int nx = (int) Math.ceil((x1 - x0) / CELL), nz = (int) Math.ceil((z1 - z0) / CELL), n = nx * nz;
        int[] floor = new int[n], low = new int[n], high = new int[n], mid = new int[n];
        for (int i = 0; i < count; i++) {
            float h = pos.get(i * 3 + 1) - floorY;
            if (h < -0.4f || h > 2.4f) continue;
            int cx = (int) ((pos.get(i * 3) - x0) / CELL), cz = (int) ((pos.get(i * 3 + 2) - z0) / CELL), c = cz * nx + cx;
            if (h < 0.04f) floor[c]++;                      // the floor, or its reflection below it
            else if (h < 1.0f) { low[c]++; if (h > 0.3f) mid[c]++; }
            else if (h > 1.6f) high[c]++;                   // above head height: walls (and tall cupboards)
        }
        byte[] g = new byte[n];
        for (int c = 0; c < n; c++) {
            if (high[c] >= 2 && mid[c] >= 2) g[c] = 3;     // a surface from low to high: a wall
            else if (floor[c] >= 1) g[c] = 1;
            else if (low[c] >= 2) g[c] = 2;                 // furniture: the floor under it counts
        }
        boolean[] in = new boolean[n];
        for (int c = 0; c < n; c++) in[c] = g[c] == 1 || g[c] == 2;
        // close small gaps (15 cm), not across walls
        in = erode(dilate(in, nx, nz, 3), nx, nz, 3);
        for (int c = 0; c < n; c++) if (g[c] == 3) in[c] = false;
        // holes the room encloses (under a sofa, a rug the depth missed): what the outside can't reach
        boolean[] outside = new boolean[n];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int x = 0; x < nx; x++) { q.add(x); q.add((nz - 1) * nx + x); }
        for (int z = 0; z < nz; z++) { q.add(z * nx); q.add(z * nx + nx - 1); }
        while (!q.isEmpty()) {
            int c = q.poll(); if (outside[c] || in[c] || g[c] == 3) continue;
            outside[c] = true; int x = c % nx, z = c / nx;
            if (x > 0) q.add(c - 1); if (x < nx - 1) q.add(c + 1); if (z > 0) q.add(c - nx); if (z < nz - 1) q.add(c + nx);
        }
        for (int c = 0; c < n; c++) if (!outside[c] && g[c] != 3 && !in[c]) { in[c] = true; if (g[c] == 0) g[c] = 4; }
        // rooms: open with a 45 cm disc (doorways, 70-90 cm, disappear), each big remaining part is a room's core; every
        // floor cell then goes to the nearest core (walking over the floor), so a room keeps its own doorway half
        boolean[] core = erode(in, nx, nz, 9);
        int[] lab = new int[n]; int k = 0;
        java.util.ArrayList<Integer> sizes = new java.util.ArrayList<>();
        for (int c = 0; c < n; c++) if (core[c] && lab[c] == 0) {
            k++; int size = 0; q.add(c); lab[c] = k;
            while (!q.isEmpty()) { int d = q.poll(); size++; int x = d % nx, z = d / nx;
                for (int e : new int[]{x > 0 ? d - 1 : -1, x < nx - 1 ? d + 1 : -1, z > 0 ? d - nx : -1, z < nz - 1 ? d + nx : -1})
                    if (e >= 0 && core[e] && lab[e] == 0) { lab[e] = k; q.add(e); } }
            sizes.add(size);
        }
        // cores under 0.5 m² (a corridor end, a niche) don't make a room of their own
        for (int c = 0; c < n; c++) if (lab[c] > 0 && sizes.get(lab[c] - 1) * CELL * CELL < 0.5f) lab[c] = 0;
        // grow each core back by the 45 cm it lost (walking over the floor, nearest core first): a doorway's floor goes
        // to the room on its side, and floor further through a doorway without a core of its own stays out
        int[] owner = new int[n], dist = new int[n];
        for (int c = 0; c < n; c++) if (lab[c] > 0 && in[c]) { owner[c] = lab[c]; q.add(c); }
        while (!q.isEmpty()) { int d = q.poll(); int x = d % nx, z = d / nx;
            if (dist[d] >= 10) continue;
            for (int e : new int[]{x > 0 ? d - 1 : -1, x < nx - 1 ? d + 1 : -1, z > 0 ? d - nx : -1, z < nz - 1 ? d + nx : -1})
                if (e >= 0 && in[e] && owner[e] == 0) { owner[e] = owner[d]; dist[e] = dist[d] + 1; q.add(e); } }
        int[] count2 = new int[k + 1];
        for (int c = 0; c < n; c++) count2[owner[c]]++;
        // rooms of 2 m² or more, biggest first; smaller bits stay with nobody
        java.util.ArrayList<Integer> order = new java.util.ArrayList<>();
        for (int i = 1; i <= k; i++) if (count2[i] * CELL * CELL >= 2f) order.add(i);
        order.sort((x, y) -> count2[y] - count2[x]);
        int[] index = new int[k + 1];
        for (int i = 0; i < order.size(); i++) index[order.get(i)] = i + 1;
        r.roomOf = new int[n];
        for (int c = 0; c < n; c++) r.roomOf[c] = owner[c] > 0 ? index[owner[c]] : 0;
        for (int i = 0; i < order.size(); i++) {
            Room room = new Room(); room.cells = count2[order.get(i)]; room.area = room.cells * CELL * CELL;
            float[] wl = minRect(r.roomOf, i + 1, nx, nz); room.w = Math.max(wl[0], wl[1]); room.l = Math.min(wl[0], wl[1]);
            r.rooms.add(room);
        }
        boolean[] main = new boolean[n];
        for (int c = 0; c < n; c++) main[c] = r.roomOf[c] == 1;
        int cells = 0, otherCells = 0, edge = 0, edgeWall = 0;
        for (int c = 0; c < n; c++) {
            if (main[c]) { cells++; g[c] |= 8; } else if (in[c]) otherCells++;
        }
        // how closed the outline is: room-edge cells with a wall within 15 cm outward
        for (int c = 0; c < n; c++) {
            if (!main[c]) continue; int x = c % nx, z = c / nx;
            boolean border = x == 0 || z == 0 || x == nx - 1 || z == nz - 1 || !main[c - 1] || !main[c + 1] || !main[c - nx] || !main[c + nx];
            if (!border) continue;
            edge++;
            search:
            for (int dz = -3; dz <= 3; dz++) for (int dx = -3; dx <= 3; dx++) {
                int xx = x + dx, zz = z + dz; if (xx < 0 || zz < 0 || xx >= nx || zz >= nz) continue;
                if ((g[zz * nx + xx] & 7) == 3) { edgeWall++; break search; }
            }
        }
        r.area = cells * CELL * CELL; r.other = otherCells * CELL * CELL; r.enclosed = edge == 0 ? 0 : (float) edgeWall / edge;
        r.nx = nx; r.nz = nz; r.x0 = x0; r.z0 = z0; r.grid = g;
        return r;
    }

    /** The smallest rectangle (turned in 2° steps) around a room's cells: its width and length along its own walls. */
    private static float[] minRect(int[] roomOf, int id, int nx, int nz) {
        float best = Float.MAX_VALUE; float[] out = {0, 0};
        for (int deg = 0; deg < 90; deg += 2) {
            double a = Math.toRadians(deg); float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            float u0 = Float.MAX_VALUE, u1 = -Float.MAX_VALUE, v0 = Float.MAX_VALUE, v1 = -Float.MAX_VALUE;
            for (int c = 0; c < roomOf.length; c++) {
                if (roomOf[c] != id) continue;
                float x = (c % nx) * CELL, z = (c / nx) * CELL, u = x * ca + z * sa, v = -x * sa + z * ca;
                u0 = Math.min(u0, u); u1 = Math.max(u1, u); v0 = Math.min(v0, v); v1 = Math.max(v1, v);
            }
            float w = u1 - u0 + CELL, l = v1 - v0 + CELL;
            if (w * l < best) { best = w * l; out[0] = w; out[1] = l; }
        }
        return out;
    }

    private static boolean[] dilate(boolean[] a, int nx, int nz, int r) { return morph(a, nx, nz, r, true); }
    private static boolean[] erode(boolean[] a, int nx, int nz, int r) { return morph(a, nx, nz, r, false); }
    /** Disc of radius r cells, done as separable passes over a distance-limited square then a disc check (r is small). */
    private static boolean[] morph(boolean[] a, int nx, int nz, int r, boolean grow) {
        boolean[] out = new boolean[a.length];
        int[][] off = discOffsets(r);
        for (int z = 0; z < nz; z++) for (int x = 0; x < nx; x++) {
            boolean v = !grow;
            for (int[] o : off) {
                int xx = x + o[0], zz = z + o[1];
                boolean s = xx >= 0 && zz >= 0 && xx < nx && zz < nz && a[zz * nx + xx];
                if (grow && s) { v = true; break; }
                if (!grow && !s) { v = false; break; }
            }
            out[z * nx + x] = v;
        }
        return out;
    }
    private static int[][] discOffsets(int r) {
        java.util.ArrayList<int[]> l = new java.util.ArrayList<>();
        for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) if (dx * dx + dz * dz <= r * r) l.add(new int[]{dx, dz});
        return l.toArray(new int[0][]);
    }

    // ---- offline: java FloorArea.java <scan folder> [floorY] [out.png] ----
    public static void main(String[] args) throws Exception {
        java.io.File dir = new java.io.File(args[0]);
        java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(new java.io.File(dir, "points.bin"))));
        int n = in.readInt(); float[] p = new float[n * 3];
        for (int i = 0; i < n; i++) { p[i * 3] = in.readFloat(); p[i * 3 + 1] = in.readFloat(); p[i * 3 + 2] = in.readFloat(); in.skipBytes(3); }
        float floorY;
        if (args.length > 1 && !args[1].equals("-")) floorY = Float.parseFloat(args[1]);
        else {   // the busiest 2 cm height in the lowest metre
            float lo = Float.MAX_VALUE; for (int i = 0; i < n; i++) lo = Math.min(lo, p[i * 3 + 1]);
            int[] hist = new int[200]; float base = lo;
            float[] ys = new float[n]; for (int i = 0; i < n; i++) ys[i] = p[i * 3 + 1];
            java.util.Arrays.sort(ys); base = ys[(int) (n * 0.005)];
            for (int i = 0; i < n; i++) { int b = (int) ((p[i * 3 + 1] - base) / 0.02f); if (b >= 0 && b < 60) hist[b]++; }
            int bb = 0; for (int b = 1; b < 60; b++) if (hist[b] > hist[bb]) bb = b;
            floorY = base + (bb + 0.5f) * 0.02f;
        }
        long t0 = System.currentTimeMillis();
        Result r = compute(p, n, floorY);
        System.out.printf(java.util.Locale.US, "%s: floor y %.2f; %s (main %.1f m², other %.1f m², outline %.0f%% walls) in %d ms%n",
                dir.getName(), floorY, r.text(), r.area, r.other, r.enclosed * 100, System.currentTimeMillis() - t0);
        for (int i = 0; i < r.rooms.size(); i++) System.out.println("  " + r.rooms.get(i).text(i));
        if (args.length > 2) {
            // a top-down picture as PPM (no java.awt on Android): sips -s format png out.ppm --out out.png
            int S = 4, W = r.nx * S, H = r.nz * S; byte[] rgb = new byte[W * H * 3];
            int[] col = {0x202020, 0x9a7b4f, 0x5a6b8a, 0xf0f0f0, 0x3d3d3d};
            int[] tint = {0x205020, 0x202060, 0x602020, 0x505010, 0x105050};
            for (int z = 0; z < r.nz; z++) for (int x = 0; x < r.nx; x++) {
                int g = r.grid[z * r.nx + x], c = col[g & 7], ro = r.roomOf[z * r.nx + x];
                if (ro > 0 && (g & 7) != 3) c = ((c >> 1) & 0x7f7f7f) + tint[(ro - 1) % tint.length];   // each room its own tint
                for (int dy = 0; dy < S; dy++) for (int dx = 0; dx < S; dx++) { int o = ((z * S + dy) * W + x * S + dx) * 3; rgb[o] = (byte) (c >> 16); rgb[o + 1] = (byte) (c >> 8); rgb[o + 2] = (byte) c; }
            }
            try (java.io.OutputStream o = new java.io.FileOutputStream(args[2])) { o.write(("P6\n" + W + " " + H + "\n255\n").getBytes("US-ASCII")); o.write(rgb); }
        }
    }
}
