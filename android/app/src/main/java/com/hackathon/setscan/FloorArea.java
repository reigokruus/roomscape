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
            if (area < 0.5f || rooms.isEmpty()) return null;
            String a = Room.m2(rooms.get(0).shown());
            if (rooms.size() >= 2) {
                float all = 0; for (Room rm : rooms) all += rm.shown();
                return String.format(java.util.Locale.US, "%d rooms, floor ≈%.0f m² in all (tap a room)", rooms.size(), all);
            }
            Room r0 = rooms.get(0);
            boolean open = r0.walled > 0 ? r0.wallSides < 4 : enclosed < 0.6f;
            return (open ? "floor area about " : "floor area ≈") + a + " m²";
        }
    }

    static final class Room {
        float area, w, l;    // m², and the size along its own walls (the smallest rectangle around it), metres
        float hull;          // m² inside its outline along its own walls (unseen floor by straight walls filled; an L stays an L)
        float walled;        // m² bounded by its walls (wallRoom); 0 when no wall was found
        int wallSides;       // how many of the 4 sides have a wall (fewer: that side is the seen floor, "about")
        int cells;
        /** The floor to show: within its convex outline when the seen floor fills most of it (the floor along the walls
         *  is often not seen: 20:25 scan, 8.7 m² seen of 10.8, outline 9.9), else what was seen. */
        float shown() { return walled > 0 ? walled : hull > 0 && area / hull >= 0.8f ? hull : area; }
        static String m2(float a) { return a < 10 ? String.format(java.util.Locale.US, "%.1f", a) : String.format(java.util.Locale.US, "%.0f", a); }
        String text(int i) { return String.format(java.util.Locale.US, "Room %d: ≈%s m², %.1f × %.1f m", i + 1, m2(shown()), w, l); }
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
        // (grown back exactly as far as the opening took away: a 45 cm disc, not grid steps, which cut the corners
        // off at 10 steps and ran into the doorway at 13; 20:25 scan)
        boolean[] kept = new boolean[n];
        for (int c = 0; c < n; c++) kept[c] = lab[c] > 0;
        boolean[] allowed = dilate(kept, nx, nz, 9);
        int[] owner = new int[n];
        for (int c = 0; c < n; c++) if (lab[c] > 0 && in[c]) { owner[c] = lab[c]; q.add(c); }
        while (!q.isEmpty()) { int d = q.poll(); int x = d % nx, z = d / nx;
            for (int e : new int[]{x > 0 ? d - 1 : -1, x < nx - 1 ? d + 1 : -1, z > 0 ? d - nx : -1, z < nz - 1 ? d + nx : -1})
                if (e >= 0 && in[e] && allowed[e] && owner[e] == 0) { owner[e] = owner[d]; q.add(e); } }
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
            room.hull = orthoHullArea(r.roomOf, i + 1, nx, nz, wl[2]);
            wallRoom(room, r.roomOf, i + 1, g, nx, nz, wl[2]);
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

    /** The room bounded by its walls (rk 10 Nov: "the area is the walls, not how much floor was seen"): in the room's own
     *  directions, each side's wall is the strongest line of wall cells near the edge of its floor (a wall doubled by a
     *  pose jump: the layer with more points); a side without one keeps the floor's edge. Inside that rectangle the
     *  cells within 60 cm of the room's floor count (an L-shaped room keeps its notch, a doorway is closed straight). */
    private static void wallRoom(Room room, int[] roomOf, int id, byte[] g, int nx, int nz, float deg) {
        double a = Math.toRadians(deg); float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
        java.util.ArrayList<float[]> fl = new java.util.ArrayList<>(), wa = new java.util.ArrayList<>();
        int bx0 = nx, bx1 = 0, bz0 = nz, bz1 = 0;
        for (int c = 0; c < roomOf.length; c++) if (roomOf[c] == id) { int x = c % nx, z = c / nx; bx0 = Math.min(bx0, x); bx1 = Math.max(bx1, x); bz0 = Math.min(bz0, z); bz1 = Math.max(bz1, z); }
        int pad = 20;
        for (int z = Math.max(0, bz0 - pad); z <= Math.min(nz - 1, bz1 + pad); z++) for (int x = Math.max(0, bx0 - pad); x <= Math.min(nx - 1, bx1 + pad); x++) {
            int c = z * nx + x; float px = x * CELL, pz = z * CELL, u = px * ca + pz * sa, v = -px * sa + pz * ca;
            if (roomOf[c] == id) fl.add(new float[]{u, v});
            else if ((g[c] & 7) == 3) wa.add(new float[]{u, v});
        }
        if (fl.size() < 50) return;
        float[] us = new float[fl.size()], vs = new float[fl.size()];
        for (int i = 0; i < fl.size(); i++) { us[i] = fl.get(i)[0]; vs[i] = fl.get(i)[1]; }
        java.util.Arrays.sort(us); java.util.Arrays.sort(vs);
        float fu0 = us[(int) (us.length * 0.02)], fu1 = us[(int) (us.length * 0.98)], fv0 = vs[(int) (vs.length * 0.02)], fv1 = vs[(int) (vs.length * 0.98)];
        float[] side = new float[4]; int found = 0;
        for (int sd = 0; sd < 4; sd++) {
            boolean alongU = sd < 2;   // sides 0, 1: walls across u (at u = const); 2, 3: across v
            float edge = sd == 0 ? fu0 : sd == 1 ? fu1 : sd == 2 ? fv0 : fv1;
            float lo = alongU ? fv0 + 0.2f : fu0 + 0.2f, hi = alongU ? fv1 - 0.2f : fu1 - 0.2f;
            float inward = sd % 2 == 0 ? 1 : -1;
            int[] hist = new int[40];   // 5 cm bins from 60 cm inside the floor's edge to 1.4 m beyond it
            for (float[] w : wa) {
                float along = alongU ? w[1] : w[0], across = alongU ? w[0] : w[1];
                if (along < lo || along > hi) continue;
                int b = (int) Math.floor(((edge - across) * inward + 1.4f) / 0.05f);   // 0 = 1.4 m outside, 28 = the edge
                if (b >= 0 && b < 40) hist[b]++;
            }
            // a wall stands at or beyond the floor's edge (30 cm leeway): a cupboard front well inside the seen floor isn't
            // the room's wall (16:40 scan: kitchen fronts cut 24 m² of seen floor to 16)
            int best = -1; for (int b = 0; b < 34; b++) if (best < 0 || hist[b] > hist[best]) best = b;
            int need = (int) Math.max(8, 0.15f * (hi - lo) / CELL);
            if (best >= 0 && hist[best] >= need) { side[sd] = edge - inward * ((best + 0.5f) * 0.05f - 1.4f); found++; }
            else side[sd] = edge;
        }
        // a "wall" with lots of this room's floor beyond it (over 0.5 m²) isn't the room's wall: a cupboard front, a
        // kitchen island (16:40 scan): that side keeps the floor's edge
        for (int sd = 0; sd < 4; sd++) {
            float edge = sd == 0 ? fu0 : sd == 1 ? fu1 : sd == 2 ? fv0 : fv1;
            if (side[sd] == edge) continue;
            int beyond = 0;
            for (float[] f : fl) {
                float c = sd < 2 ? f[0] : f[1];
                if (sd % 2 == 0 ? c < side[sd] - 0.1f : c > side[sd] + 0.1f) beyond++;
            }
            if (beyond * CELL * CELL > 0.5f) { side[sd] = sd < 2 ? (sd == 0 ? Math.min(edge, us[0]) : Math.max(edge, us[us.length - 1])) : (sd == 2 ? Math.min(edge, vs[0]) : Math.max(edge, vs[vs.length - 1])); found--; }
        }
        room.wallSides = found;
        if (found < 2) return;   // hardly any walls: the seen floor is the better answer ("about")
        float u0 = side[0], u1 = side[1], v0 = side[2], v1 = side[3];
        if (u1 - u0 < 0.5f || v1 - v0 < 0.5f) return;
        // cells of that rectangle within 60 cm of the room's floor
        int mu = (int) Math.ceil((u1 - u0) / CELL), mv = (int) Math.ceil((v1 - v0) / CELL);
        int[] d = new int[mu * mv]; java.util.Arrays.fill(d, Integer.MAX_VALUE);
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        for (float[] f : fl) { int iu = (int) ((f[0] - u0) / CELL), iv = (int) ((f[1] - v0) / CELL); if (iu >= 0 && iv >= 0 && iu < mu && iv < mv && d[iv * mu + iu] != 0) { d[iv * mu + iu] = 0; q.add(iv * mu + iu); } }
        int cells = 0;
        while (!q.isEmpty()) { int c = q.poll(); cells++; int iu = c % mu, iv = c / mu;
            if (d[c] >= 12) continue;
            for (int dv = -1; dv <= 1; dv++) for (int du = -1; du <= 1; du++) { int ju = iu + du, jv = iv + dv; if (ju < 0 || jv < 0 || ju >= mu || jv >= mv) continue;
                int e = jv * mu + ju; if (d[e] == Integer.MAX_VALUE) { d[e] = d[c] + 1; q.add(e); } } }
        // plus seen floor outside that rectangle on a side without a wall (an L's arm, the floor's 2% tails); floor beyond
        // a found wall is the next room's, it doesn't count
        boolean[] hasWall = new boolean[4];
        for (int sd = 0; sd < 4; sd++) { float e = sd == 0 ? fu0 : sd == 1 ? fu1 : sd == 2 ? fv0 : fv1; hasWall[sd] = side[sd] != e && side[sd] != (sd == 0 ? us[0] : sd == 1 ? us[us.length - 1] : sd == 2 ? vs[0] : vs[vs.length - 1]); }
        int extra = 0;
        for (float[] f : fl) {
            boolean inside = f[0] >= u0 && f[0] < u1 && f[1] >= v0 && f[1] < v1;
            if (inside) continue;
            if ((f[0] < u0 && hasWall[0]) || (f[0] >= u1 && hasWall[1]) || (f[1] < v0 && hasWall[2]) || (f[1] >= v1 && hasWall[3])) continue;
            extra++;
        }
        cells += extra;
        room.walled = cells * CELL * CELL; room.wallSides = found;
        room.w = Math.max(u1 - u0, v1 - v0); room.l = Math.min(u1 - u0, v1 - v0);
    }

    /** Area inside the room's outline along its own wall directions (orthogonally convex hull: a cell counts when
     *  room cells lie on both sides of it in its row and in its column). Fills the unseen floor along straight walls and
     *  in corners, but an L-shaped room stays an L (a convex hull would fill its missing corner). m². */
    private static float orthoHullArea(int[] roomOf, int id, int nx, int nz, float deg) {
        double a = Math.toRadians(deg); float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
        int m = (int) Math.ceil(Math.hypot(nx, nz)) + 2, half = m / 2;
        boolean[] g = new boolean[m * m];
        float cx = nx / 2f, cz = nz / 2f;
        for (int c = 0; c < roomOf.length; c++) {
            if (roomOf[c] != id) continue;
            float x = (c % nx) - cx, z = (c / nx) - cz;
            int u = Math.round(x * ca + z * sa) + half, v = Math.round(-x * sa + z * ca) + half;
            if (u >= 0 && v >= 0 && u < m && v < m) g[v * m + u] = true;
        }
        int[] u0 = new int[m], u1 = new int[m], v0 = new int[m], v1 = new int[m];
        java.util.Arrays.fill(u0, Integer.MAX_VALUE); java.util.Arrays.fill(u1, -1); java.util.Arrays.fill(v0, Integer.MAX_VALUE); java.util.Arrays.fill(v1, -1);
        for (int v = 0; v < m; v++) for (int u = 0; u < m; u++) if (g[v * m + u]) {
            u0[v] = Math.min(u0[v], u); u1[v] = Math.max(u1[v], u); v0[u] = Math.min(v0[u], v); v1[u] = Math.max(v1[u], v);
        }
        int cells = 0;
        for (int v = 0; v < m; v++) for (int u = 0; u < m; u++)
            if (u >= u0[v] && u <= u1[v] && v >= v0[u] && v <= v1[u]) cells++;
        return cells * CELL * CELL;
    }

    /** Area inside the convex outline of a room's cells (their corners), m². */
    private static float hullArea(int[] roomOf, int id, int nx, int nz) {
        java.util.ArrayList<long[]> pts = new java.util.ArrayList<>();
        for (int z = 0; z < nz; z++) {   // per row only the outermost cells matter
            int a = -1, b = -1;
            for (int x = 0; x < nx; x++) if (roomOf[z * nx + x] == id) { if (a < 0) a = x; b = x; }
            if (a < 0) continue;
            pts.add(new long[]{a, z}); pts.add(new long[]{a, z + 1}); pts.add(new long[]{b + 1, z}); pts.add(new long[]{b + 1, z + 1});
        }
        if (pts.size() < 3) return 0;
        pts.sort((p, q) -> p[0] != q[0] ? Long.compare(p[0], q[0]) : Long.compare(p[1], q[1]));
        long[][] h = new long[pts.size() * 2][]; int k = 0;
        for (long[] p : pts) { while (k >= 2 && cross(h[k - 2], h[k - 1], p) <= 0) k--; h[k++] = p; }
        for (int i = pts.size() - 2, t = k + 1; i >= 0; i--) { long[] p = pts.get(i); while (k >= t && cross(h[k - 2], h[k - 1], p) <= 0) k--; h[k++] = p; }
        double a2 = 0; for (int i = 0; i < k - 1; i++) a2 += h[i][0] * h[i + 1][1] - h[i + 1][0] * h[i][1];
        return (float) (Math.abs(a2) / 2 * CELL * CELL);
    }
    private static long cross(long[] o, long[] a, long[] b) { return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]); }

    /** The smallest rectangle (turned in 2° steps) around a room's cells: its width and length along its own walls. */
    private static float[] minRect(int[] roomOf, int id, int nx, int nz) {
        float best = Float.MAX_VALUE; float[] out = {0, 0, 0};
        for (int deg = 0; deg < 90; deg += 2) {
            double a = Math.toRadians(deg); float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            float u0 = Float.MAX_VALUE, u1 = -Float.MAX_VALUE, v0 = Float.MAX_VALUE, v1 = -Float.MAX_VALUE;
            for (int c = 0; c < roomOf.length; c++) {
                if (roomOf[c] != id) continue;
                float x = (c % nx) * CELL, z = (c / nx) * CELL, u = x * ca + z * sa, v = -x * sa + z * ca;
                u0 = Math.min(u0, u); u1 = Math.max(u1, u); v0 = Math.min(v0, v); v1 = Math.max(v1, v);
            }
            float w = u1 - u0 + CELL, l = v1 - v0 + CELL;
            if (w * l < best) { best = w * l; out[0] = w; out[1] = l; out[2] = deg; }
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
        for (int i = 0; i < r.rooms.size(); i++) System.out.println("  " + r.rooms.get(i).text(i) + String.format(java.util.Locale.US, " (walls on %d sides: %.1f m²; seen floor %.1f, wall-aligned outline %.1f m²)", r.rooms.get(i).wallSides, r.rooms.get(i).walled, r.rooms.get(i).area, r.rooms.get(i).hull));
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
