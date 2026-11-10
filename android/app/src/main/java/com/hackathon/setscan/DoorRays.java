package com.hackathon.setscan;

import java.util.List;

/**
 * A doorway's clear opening from the photos' raw depth rays (no voxels, no image edges). Every depth pixel of every
 * photo that looks at the doorway's wall is a ray: where it crosses the wall plane, the spot is "wall" if the depth
 * ends at the plane (±WALL) and "through" if it goes on well past it (next room, or the open door leaf, which stands
 * behind the plane). Thousands of rays on a 1 cm grid on the wall: each jamb is where wall and through are equally
 * likely along a horizontal line (median over 0.3-1.8 m), the lintel likewise going up the middle. Plain Java.
 */
final class DoorRays {
    static final float CELL = 0.01f, WALL = 0.04f, THROUGH = 0.18f, MAX_ANGLE_COS = 0.45f;

    /** A photo's depth and camera: pose camera-to-world, view = its inverse, depth (mm) with its intrinsics. */
    static final class View { float[] pose, view; short[] depth; int dw, dh; float dfx, dfy, dcx, dcy; }

    static final class Result { float u0, u1, top; int rays; boolean ok; String why = ""; }

    /** The wall: through (ox, oz), along (ux, uz), normal (nx, nz); rough opening u0..u1 and top (above floorY). */
    static Result measure(List<View> views, float ox, float oz, float ux, float uz, float floorY, float u0, float u1, float top) {
        Result r = new Result(); r.u0 = u0; r.u1 = u1; r.top = top;
        float nx = -uz, nz = ux;
        float ua = u0 - 0.3f, ub = u1 + 0.3f, ya = 0, yb = top + 0.3f;
        int gw = (int) ((ub - ua) / CELL) + 1, gh = (int) ((yb - ya) / CELL) + 1;
        int[] wall = new int[gw * gh], thr = new int[gw * gh];
        for (View v : views) {
            if (v.depth == null) continue;
            float cxw = v.pose[12], cyw = v.pose[13], czw = v.pose[14];
            float camDist = (cxw - ox) * nx + (czw - oz) * nz;   // signed distance of the camera from the wall plane
            if (Math.abs(camDist) < 0.3f) continue;
            for (int y = 1; y < v.dh - 1; y++) for (int x = 1; x < v.dw - 1; x++) {
                int mm = v.depth[y * v.dw + x] & 0xFFFF; if (mm == 0) continue;
                // the ray through this pixel (camera space, z forward = -z), in world
                float dx = (x + 0.5f - v.dcx) / v.dfx, dy = -(y + 0.5f - v.dcy) / v.dfy, dz = -1;
                float wx = v.pose[0] * dx + v.pose[4] * dy + v.pose[8] * dz, wy = v.pose[1] * dx + v.pose[5] * dy + v.pose[9] * dz, wz = v.pose[2] * dx + v.pose[6] * dy + v.pose[10] * dz;
                float dn = wx * nx + wz * nz;   // per unit of camera depth, how far along the normal
                if (Math.abs(dn) < 1e-4f) continue;
                float tPlane = -camDist / dn;   // camera depth at which the ray meets the plane
                if (tPlane < 0.3f || tPlane > 4.5f) continue;
                float rl = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
                if (Math.abs(dn) / rl < MAX_ANGLE_COS) continue;   // grazing
                // not at a depth jump (a flying pixel between the frame and the room behind)
                int l = v.depth[y * v.dw + x - 1] & 0xFFFF, rr = v.depth[y * v.dw + x + 1] & 0xFFFF;
                if (l == 0 || rr == 0 || Math.abs(l - mm) > 60 || Math.abs(rr - mm) > 60) continue;
                float px = cxw + wx * tPlane, py = cyw + wy * tPlane, pz = czw + wz * tPlane;
                float u = (px - ox) * ux + (pz - oz) * uz, h = py - floorY;
                if (u < ua || u >= ub || h < ya || h >= yb) continue;
                int c = (int) ((h - ya) / CELL) * gw + (int) ((u - ua) / CELL);
                float d = mm * 0.001f;
                if (Math.abs(d - tPlane) < WALL + 0.02f * tPlane) wall[c]++;
                else if (d > tPlane + THROUGH) thr[c]++;
                r.rays++;
            }
        }
        if (r.rays < 500) { r.why = "only " + r.rays + " rays on the wall"; return r; }
        // jambs: per row (0.3-1.8 m), from the middle outward, where wall becomes more likely than through (5 cm window)
        float mid = (u0 + u1) / 2;
        java.util.ArrayList<Float> ls = new java.util.ArrayList<>(), rs = new java.util.ArrayList<>(), ts = new java.util.ArrayList<>();
        for (float h = 0.3f; h <= Math.min(1.8f, top - 0.1f); h += 0.02f) {
            int row = (int) ((h - ya) / CELL);
            Float a = cross(wall, thr, gw, row, (int) ((mid - ua) / CELL), -1, gw, true), b = cross(wall, thr, gw, row, (int) ((mid - ua) / CELL), 1, gw, true);
            if (a != null) ls.add(ua + a * CELL); if (b != null) rs.add(ua + b * CELL);
        }
        if (ls.size() < 10 || rs.size() < 10) { r.why = "jambs not clear (" + ls.size() + "/" + rs.size() + " rows)"; return r; }
        r.u0 = median(ls); r.u1 = median(rs);
        // lintel: per column in the middle 60%, from 1.5 m up, where wall becomes more likely
        for (float u = r.u0 + 0.2f * (r.u1 - r.u0); u <= r.u1 - 0.2f * (r.u1 - r.u0); u += 0.02f) {
            int col = (int) ((u - ua) / CELL);
            Float t = cross(wall, thr, gw, col, (int) ((1.5f - ya) / CELL), 1, gh, false);
            if (t != null) ts.add(ya + t * CELL);
        }
        if (ts.size() >= 5) r.top = median(ts); else r.why = "lintel not clear (" + ts.size() + " columns)";
        r.ok = ts.size() >= 5;
        return r;
    }

    /** Walking from `start` in `dir` along a row (or a column), the first cell where wall rays outnumber through rays
     *  over a 5-cell window (with at least 6 rays); sub-cell by the window's middle. */
    private static Float cross(int[] wall, int[] thr, int gw, int line, int start, int dir, int limit, boolean row) {
        int seenThrough = 0;
        for (int i = start; i >= 2 && i < limit - 2; i += dir) {
            int w = 0, t = 0;
            for (int k = -2; k <= 2; k++) { int c = row ? line * gw + i + k : (i + k) * gw + line; if (c < 0 || c >= wall.length) continue; w += wall[c]; t += thr[c]; }
            if (w + t < 6) continue;
            if (t > w) { seenThrough++; continue; }
            if (seenThrough >= 3) return (float) i;   // through first, then wall: the edge
        }
        return null;
    }

    private static float median(java.util.ArrayList<Float> v) { java.util.Collections.sort(v); return v.get(v.size() / 2); }
}
