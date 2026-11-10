package com.hackathon.setscan;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Closes holes in plain walls and floors (rk 10 Nov: blank walls and white doors give this phone's camera depth too
 * little to build a surface, and that's where scans in new places fail). For each flat plane found in the scan
 * (Planes), the scan's surface on it is laid out on a 5 cm grid; a gap is filled with a flat patch on the plane only
 * when all of these hold:
 *  - it is enclosed: surface on the plane all round it (a gap at the plane's edge may be a doorway or the wall's end);
 *  - nothing was seen through it: no depth ray passed the plane there (an open doorway, a window to the outside or
 *    a glass pane seen through stays open);
 *  - it is small (up to SMALL m²), or the scan has some points on the plane inside it (weak depth that agreed with
 *    the plane, too few for a surface), up to LARGE m² (a door).
 * The patches are new triangles in the plane's colour; the photo texturing paints them like the rest. The points are
 * not changed, so a fill is never saved as scanned data.
 */
final class PlaneFill {
    static boolean ENABLED = true;
    static final float CELL = 0.05f, NEAR = 0.05f, SMALL = 0.6f, LARGE = 2.5f, EVIDENCE = 0.2f;

    interface SeenThrough { boolean test(float x, float y, float z); }

    /** Filled triangles (3 floats a corner) and colours (4 bytes a corner). */
    static final class Out { float[] pos = new float[0]; byte[] col = new byte[0]; int verts; int holes; float area;
        /** Gaps left open, by reason (m²): at the plane's edge, too big, seen through, too little evidence. */
        float atEdge, tooBig, seen, noEvidence; }

    static Out fill(List<Planes.Plane> planes, float[] mesh, int verts, float[] pts, int nPts, SeenThrough seen) {
        java.util.ArrayList<float[]> tris = new java.util.ArrayList<>();
        java.util.ArrayList<byte[]> cols = new java.util.ArrayList<>();
        Out out = new Out();
        for (Planes.Plane p : planes) {
            if (!"wall".equals(p.kind) && !"floor".equals(p.kind)) continue;
            float nx = p.nx, ny = p.ny, nz = p.nz;
            // In-plane axes: horizontal along a wall and up; x and z on the floor.
            float ux, uy, uz;
            if ("floor".equals(p.kind)) { ux = 1 - nx * nx; uy = -nx * ny; uz = -nx * nz; }   // x, made perpendicular to n
            else { ux = -nz; uy = 0; uz = nx; }                                              // horizontal along the wall
            float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz); if (ul < 1e-3f) continue;
            ux /= ul; uy /= ul; uz /= ul;
            float vx = ny * uz - nz * uy, vy = nz * ux - nx * uz, vz = nx * uy - ny * ux;      // n x u
            // Grid over the plane's points' bounds.
            float u0 = Float.MAX_VALUE, u1 = -Float.MAX_VALUE, v0 = Float.MAX_VALUE, v1 = -Float.MAX_VALUE;
            for (int c = 0; c < 8; c++) {
                float x = (c & 1) == 0 ? p.min[0] : p.max[0], y = (c & 2) == 0 ? p.min[1] : p.max[1], z = (c & 4) == 0 ? p.min[2] : p.max[2];
                float u = x * ux + y * uy + z * uz, v = x * vx + y * vy + z * vz;
                u0 = Math.min(u0, u); u1 = Math.max(u1, u); v0 = Math.min(v0, v); v1 = Math.max(v1, v);
            }
            int gw = (int) Math.ceil((u1 - u0) / CELL), gh = (int) Math.ceil((v1 - v0) / CELL);
            if (gw < 3 || gh < 3 || (long) gw * gh > 400 * 400) continue;
            boolean[] cov = new boolean[gw * gh];
            for (int i = 0; i < verts; i++) {
                float x = mesh[i * 3], y = mesh[i * 3 + 1], z = mesh[i * 3 + 2];
                if (Math.abs(x * nx + y * ny + z * nz - p.d) > NEAR) continue;
                int gu = (int) ((x * ux + y * uy + z * uz - u0) / CELL), gv = (int) ((x * vx + y * vy + z * vz - v0) / CELL);
                for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {   // a mesh corner covers its neighbours (triangles span cells)
                    int cu = gu + a, cv = gv + b;
                    if (cu >= 0 && cv >= 0 && cu < gw && cv < gh) cov[cv * gw + cu] = true;
                }
            }
            int[] ev = new int[gw * gh];
            for (int i = 0; i < nPts; i++) {
                float x = pts[i * 3], y = pts[i * 3 + 1], z = pts[i * 3 + 2];
                if (Math.abs(x * nx + y * ny + z * nz - p.d) > 0.04f) continue;
                int gu = (int) ((x * ux + y * uy + z * uz - u0) / CELL), gv = (int) ((x * vx + y * vy + z * vz - v0) / CELL);
                if (gu >= 0 && gv >= 0 && gu < gw && gv < gh) ev[gv * gw + gu]++;
            }
            // Gaps: 4-connected runs of uncovered cells.
            int[] comp = new int[gw * gh];
            java.util.Arrays.fill(comp, -1);
            ArrayDeque<Integer> q = new ArrayDeque<>();
            int next = 0;
            for (int s = 0; s < gw * gh; s++) {
                if (cov[s] || comp[s] >= 0) continue;
                java.util.ArrayList<Integer> cells = new java.util.ArrayList<>();
                boolean edge = false; int withPts = 0, through = 0;
                comp[s] = next; q.add(s);
                while (!q.isEmpty()) {
                    int c = q.poll(); cells.add(c);
                    int cu = c % gw, cv = c / gw;
                    if (cu == 0 || cv == 0 || cu == gw - 1 || cv == gh - 1) edge = true;
                    int[] nb = {c - 1, c + 1, c - gw, c + gw};
                    boolean[] ok = {cu > 0, cu < gw - 1, cv > 0, cv < gh - 1};
                    for (int k = 0; k < 4; k++) if (ok[k] && !cov[nb[k]] && comp[nb[k]] < 0) { comp[nb[k]] = next; q.add(nb[k]); }
                }
                next++;
                float area = cells.size() * CELL * CELL;
                if (edge) { out.atEdge += area; continue; }
                if (area > LARGE) { out.tooBig += area; continue; }
                for (int c : cells) {
                    if (ev[c] > 0) withPts++;
                    float cu = u0 + (c % gw + 0.5f) * CELL, cv = v0 + (c / gw + 0.5f) * CELL;
                    float x = cu * ux + cv * vx + p.d * nx, y = cu * uy + cv * vy + p.d * ny, z = cu * uz + cv * vz + p.d * nz;
                    if (seen.test(x, y, z)) through++;
                }
                if (through > 0) { out.seen += area; continue; }   // depth went through: an opening
                if (area > SMALL && withPts < EVIDENCE * cells.size()) { out.noEvidence += area; continue; }
                out.holes++; out.area += area;
                byte r = (byte) Math.round(p.r), g = (byte) Math.round(p.g), b = (byte) Math.round(p.b);
                for (int c : cells) {
                    float cu = u0 + (c % gw) * CELL, cv = v0 + (c / gw) * CELL;
                    float[][] q4 = new float[4][];
                    for (int k = 0; k < 4; k++) {
                        float uu = cu + ((k & 1) == 0 ? 0 : CELL), vv = cv + ((k & 2) == 0 ? 0 : CELL);
                        q4[k] = new float[]{uu * ux + vv * vx + p.d * nx, uu * uy + vv * vy + p.d * ny, uu * uz + vv * vz + p.d * nz};
                    }
                    tris.add(concat(q4[0], q4[1], q4[3])); tris.add(concat(q4[0], q4[3], q4[2]));
                    byte[] cc = {r, g, b, (byte) 255, r, g, b, (byte) 255, r, g, b, (byte) 255};
                    cols.add(cc); cols.add(cc);
                }
            }
        }
        out.verts = tris.size() * 3;
        out.pos = new float[out.verts * 3]; out.col = new byte[out.verts * 4];
        for (int t = 0; t < tris.size(); t++) { System.arraycopy(tris.get(t), 0, out.pos, t * 9, 9); System.arraycopy(cols.get(t), 0, out.col, t * 12, 12); }
        return out;
    }

    private static float[] concat(float[] a, float[] b, float[] c) {
        return new float[]{a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]};
    }
}
