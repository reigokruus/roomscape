package com.hackathon.setscan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Doorways in the scan (rk 10 Nov 11:35: "it's clearly a doorway and it should mark it as such"). Along each wall of the
 * room's outline, the scan's points close to the wall are laid out on a 2 cm grid (along the wall × height). A doorway
 * is a run of columns 0.6-1.3 m wide that are empty from the floor up to at least 1.75 m, with wall on both sides
 * (the jambs) and wall again above (the lintel), and where the depth saw through the opening into the space beyond
 * (or, without that evidence, behind it). Its size is taken from the jambs and the lintel to 2 cm, and its height
 * from the floor right in front of it (the room's floor estimate can be off by several cm).
 */
final class DoorwayFinder {
    static int DUMP = -1;
    /** Heights from the given floor (ARCore's floor plane: its tracked features are metric; this phone's depth near
     *  the floor is several cm off), not from the depth's floor right in front of the opening. */
    static boolean TRUST_FLOOR = true;
    static float LINTEL_SHARE = 0.34f;
    /** How far the scanned surface reaches into an opening at each edge (about half the 4 cm voxel). */
    static float EDGE_GROWTH = 0.02f;
    static final float CELL = 0.02f, NEAR = 0.06f, MIN_W = 0.6f, MAX_W = 1.3f, MIN_H = 1.75f, MAX_H = 2.4f;

    static final class Doorway {
        int wall; float x0, z0, x1, z1;   // the opening's bottom corners (on the floor), world
        float floorY, width, height, nx, nz; boolean open; float seenShare;
        /** Both jambs and the lintel were seen: the size is shown; otherwise only the outline. */
        boolean sure = true;
        @Override public String toString() {
            return String.format(java.util.Locale.US, "doorway on wall %d: %.2f × %.2f m at (%.2f, %.2f)-(%.2f, %.2f), floor %.2f, %s (seen through %.0f%%)",
                    wall, width, height, x0, z0, x1, z1, floorY, open ? "open" : "no see-through", seenShare * 100);
        }
    }

    interface SeenThrough { boolean test(float x, float y, float z); }

    /** The doorways saved with a scan (doorways.json), or null when there's no file. */
    static List<Doorway> load(java.io.File f) {
        if (!f.exists()) return null;
        try {
            org.json.JSONArray a = new org.json.JSONArray(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
            List<Doorway> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.getJSONObject(i); Doorway d = new Doorway();
                d.x0 = (float) o.getDouble("x0"); d.z0 = (float) o.getDouble("z0"); d.x1 = (float) o.getDouble("x1"); d.z1 = (float) o.getDouble("z1");
                d.floorY = (float) o.getDouble("floorY"); d.width = (float) o.getDouble("width"); d.height = (float) o.getDouble("height");
                d.open = o.optBoolean("open", true); d.sure = o.optBoolean("sure", true);
                float l = (float) Math.hypot(d.x1 - d.x0, d.z1 - d.z0); if (l > 1e-3f) { d.nx = -(d.z1 - d.z0) / l; d.nz = (d.x1 - d.x0) / l; }
                out.add(d);
            }
            return out;
        } catch (Exception e) { return null; }
    }

    /** walls: x0, z0, x1, z1 per wall (the outline). seen may be null. */
    static List<Doorway> find(float[] xyz, int n, float[] walls, float floorY, SeenThrough seen, StringBuilder why) {
        List<Doorway> out = new ArrayList<>();
        for (int w = 0; w + 3 < walls.length; w += 4) {
            float ax = walls[w], az = walls[w + 1], bx = walls[w + 2], bz = walls[w + 3];
            float len = (float) Math.hypot(bx - ax, bz - az); if (len < MIN_W + 0.2f) continue;
            float ux = (bx - ax) / len, uz = (bz - az) / len, nx = -uz, nz = ux;
            // The wall's real offset: the median distance of points near it (the outline can be a few cm off).
            float[] ds = new float[n]; int dn = 0;
            for (int i = 0; i < n; i++) {
                float px = xyz[i * 3] - ax, py = xyz[i * 3 + 1] - floorY, pz = xyz[i * 3 + 2] - az;
                if (py < 0.3f || py > 1.8f) continue;
                float u = px * ux + pz * uz, d = px * nx + pz * nz;
                if (u < 0 || u > len || Math.abs(d) > 0.15f) continue;
                ds[dn++] = d;
            }
            if (dn < 200) continue;
            Arrays.sort(ds, 0, dn);
            float off = ds[dn / 2];
            int gw = (int) (len / CELL) + 1, gh = (int) (MAX_H + 0.3f) / 1 * 50;
            gh = (int) ((MAX_H + 0.3f) / CELL);
            int[] occ = new int[gw * gh];
            // the floor right at the wall, per 0.5 m along it: the 10th percentile height of points near the floor
            for (int i = 0; i < n; i++) {
                float px = xyz[i * 3] - ax, py = xyz[i * 3 + 1] - floorY, pz = xyz[i * 3 + 2] - az;
                float u = px * ux + pz * uz, d = px * nx + pz * nz - off;
                if (u < 0 || u >= len || Math.abs(d) > NEAR || py < -0.2f || py >= MAX_H + 0.3f - 0.2f) continue;
                int c = (int) (u / CELL), r = (int) ((py + 0.2f) / CELL);
                if (r >= 0 && r < gh) occ[r * gw + c]++;
            }
            if (DUMP == w / 4 && why != null) {   // debug picture of this wall: '#' points, '.' none; 4 cm cells, top row first
                why.append(String.format(java.util.Locale.US, "%n wall %d: %.2f m long, offset %.3f%n", w / 4, len, off));
                for (int r = gh - 2; r >= 0; r -= 2) { StringBuilder l = new StringBuilder(String.format(java.util.Locale.US, "%5.2f ", -0.2f + r * CELL));
                    for (int cc = 0; cc + 1 < gw; cc += 2) l.append(occ[r * gw + cc] + occ[r * gw + cc + 1] + occ[(r + 1) * gw + cc] + occ[(r + 1) * gw + cc + 1] > 0 ? '#' : '.');
                    why.append(l).append('\n'); }
            }
            int r0 = (int) ((0.2f + 0.15f) / CELL), r1 = (int) ((0.2f + 1.6f) / CELL);
            float[] solid = new float[gw];
            for (int c = 0; c < gw; c++) { int k = 0; for (int r = r0; r < r1; r++) if (occ[r * gw + c] > 0) k++; solid[c] = (float) k / (r1 - r0); }
            // smooth over 3 columns
            float[] sm = new float[gw];
            for (int c = 0; c < gw; c++) { float s = 0; int k = 0; for (int j = c - 1; j <= c + 1; j++) if (j >= 0 && j < gw) { s += solid[j]; k++; } sm[c] = s / k; }
            int c = 0;
            while (c < gw) {
                if (sm[c] >= 0.15f) { c++; continue; }
                int s = c; while (c < gw && sm[c] < 0.15f) c++;
                int e = c;   // [s, e) empty columns
                float width = (e - s) * CELL;
                if (width < MIN_W - 0.05f || width > MAX_W) { if (why != null && width > 0.3f) why.append(String.format(java.util.Locale.US, " wall %d: gap %.2f m wide (not a door width)", w / 4, width)); continue; }
                // jambs: wall on both sides within 10 cm
                boolean left = false, right = false;
                for (int j = Math.max(0, s - 5); j < s; j++) if (sm[j] > 0.4f) left = true;
                for (int j = e; j < Math.min(gw, e + 5); j++) if (sm[j] > 0.4f) right = true;
                // Wall on one side only (the scan stops at the other jamb): taken only if door-sized and clearly see-through.
                boolean oneSided = left != right;
                if (!left && !right || oneSided && (seen == null || width > 1.05f)) { if (why != null) why.append(String.format(java.util.Locale.US, " wall %d: gap %.2f m without wall on both sides", w / 4, width)); continue; }
                // lintel: the first row from 1.6 m up where most of the gap's columns have points
                int lintelRow = -1;
                // (the middle 60% of the opening, a third of it with surface: the lintel's underside is thinly scanned)
                int ms = s + (e - s) / 5, me = e - (e - s) / 5;
                for (int r = (int) ((0.2f + 1.6f) / CELL); r < gh; r++) {
                    int k = 0; for (int j = ms; j < me; j++) if (occ[r * gw + j] > 0) k++;
                    if (k >= LINTEL_SHARE * (me - ms)) { lintelRow = r; break; }
                }
                if (lintelRow < 0) { if (why != null) why.append(String.format(java.util.Locale.US, " wall %d: gap %.2f m with no wall above it", w / 4, width)); continue; }
                // The opening's real top is where the free space ends (the depth stops seeing through), which is the
                // lintel's underside; the surface found above can be the architrave's top (rk 10 Nov 11:52: 2.17 m
                // read for a 2.04 m opening, the outline on the frame's top).
                if (seen != null) {
                    // only where the opening was clearly seen through below (else a thinly seen opening would end at 1.6 m)
                    float[] share = new float[gh];
                    for (int r = (int) ((0.2f + 1.5f) / CELL); r < lintelRow; r++) {
                        int through = 0, tests = 0;
                        for (int j = ms; j < me; j += 2) {
                            float u = (j + 0.5f) * CELL, xx = ax + ux * u + nx * off, zz = az + uz * u + nz * off;
                            tests++; if (seen.test(xx, floorY - 0.2f + (r + 0.5f) * CELL, zz)) through++;
                        }
                        share[r] = tests > 0 ? (float) through / tests : 0;
                    }
                    int r0b = (int) ((0.2f + 1.5f) / CELL), r1b = (int) ((0.2f + 1.6f) / CELL);
                    float below = 0; for (int r = r0b; r < r1b; r++) below = Math.max(below, share[r]);
                    if (below >= 0.5f) for (int r = r1b; r < lintelRow; r++) if (share[r] < 0.3f * below) { lintelRow = r; break; }
                }
                // the floor in front of the opening: points within 60 cm of it, within 15 cm of the floor estimate
                float cxm = ax + ux * (s + e) * CELL / 2 + nx * off, czm = az + uz * (s + e) * CELL / 2 + nz * off;
                float[] fy = new float[n]; int fn = 0;
                for (int i = 0; i < n; i++) {
                    float y = xyz[i * 3 + 1]; if (Math.abs(y - floorY) > 0.15f) continue;
                    float dx = xyz[i * 3] - cxm, dz = xyz[i * 3 + 2] - czm;
                    if (dx * dx + dz * dz < 0.6f * 0.6f) fy[fn++] = y;
                }
                float floorHere = floorY;
                if (fn > 30 && !TRUST_FLOOR) { Arrays.sort(fy, 0, fn); floorHere = fy[fn / 2]; }
                float lintelY = floorY - 0.2f + lintelRow * CELL;
                float height = lintelY - floorHere;
                if (height < MIN_H || height > MAX_H) { if (why != null) why.append(String.format(java.util.Locale.US, " wall %d: gap %.2f m wide but %.2f m high", w / 4, width, height)); continue; }
                // see-through: cells inside the opening the depth passed
                int tested = 0, through = 0;
                if (seen != null) for (int j = s; j < e; j += 2) for (float y = 0.2f; y < height - 0.1f; y += 0.1f) {
                    float u = (j + 0.5f) * CELL;
                    float x = ax + ux * u + nx * off, z = az + uz * u + nz * off;
                    tested++; if (seen.test(x, floorHere + y, z)) through++;
                }
                Doorway dw = new Doorway();
                // The clear opening: the surface grows into the opening by about half a 4 cm voxel at each jamb, and the
                // 3-column smoothing takes another 2 cm cell each side (rk's 80 cm door read 0.70 m in two scans).
                float grow = (EDGE_GROWTH + CELL);
                width += 2 * grow;
                dw.wall = w / 4; dw.width = width; dw.height = height;
                s -= Math.round(grow / CELL); e += Math.round(grow / CELL); dw.floorY = floorHere; dw.nx = nx; dw.nz = nz;
                dw.x0 = ax + ux * s * CELL + nx * off; dw.z0 = az + uz * s * CELL + nz * off;
                dw.x1 = ax + ux * e * CELL + nx * off; dw.z1 = az + uz * e * CELL + nz * off;
                dw.seenShare = tested > 0 ? (float) through / tested : 0;
                dw.open = dw.seenShare > (oneSided ? 0.5f : 0.2f);
                dw.sure = !oneSided;
                // A gap the depth never looked through could be a dark wardrobe or a mirror: only an open one counts.
                if (seen != null && !dw.open) { if (why != null) why.append(" " + dw + " -> not taken (no see-through)"); continue; }
                out.add(dw);
            }
        }
        // The same opening found from two overlapping wall planes: keep the better seen one.
        List<Doorway> kept = new ArrayList<>();
        for (Doorway d : out) {
            boolean dup = false;
            for (int i = 0; i < kept.size(); i++) {
                Doorway k = kept.get(i);
                float dx = (d.x0 + d.x1 - k.x0 - k.x1) / 2, dz = (d.z0 + d.z1 - k.z0 - k.z1) / 2;
                if (dx * dx + dz * dz < 0.4f * 0.4f) { dup = true; if (d.seenShare > k.seenShare) kept.set(i, d); break; }
            }
            if (!dup) kept.add(d);
        }
        return kept;
    }

    /** Wall segments (x0, z0, x1, z1) from the vertical planes found on a surface's corners, reaching 1.5 m past the
     *  fitted part on each side (an opening often sits just beyond where the wall was fitted). */
    static float[] wallsFromSurface(float[] verts, int n) {
        byte[] rgb = new byte[n * 4]; Arrays.fill(rgb, (byte) 200);
        float c0 = Planes.CELL, f0 = Planes.MAX_FLAT, a0 = Planes.MAX_ANGLE_DEG, o0 = Planes.MAX_OFFSET, i0 = Planes.INLIER, m0 = Planes.MIN_INLIERS, ar0 = Planes.MIN_AREA, s0 = Planes.SNAP;
        Planes.CELL = 0.1f; Planes.MAX_FLAT = 0.15f; Planes.MAX_ANGLE_DEG = 30; Planes.MAX_OFFSET = 0.08f;
        Planes.INLIER = 0.04f; Planes.MIN_INLIERS = 0.6f; Planes.MIN_AREA = 0.3f; Planes.SNAP = 0.08f;
        List<Planes.Plane> pl;
        try { pl = Planes.find(verts, rgb, 4, n, new java.util.HashMap<>()); }
        finally { Planes.CELL = c0; Planes.MAX_FLAT = f0; Planes.MAX_ANGLE_DEG = a0; Planes.MAX_OFFSET = o0; Planes.INLIER = i0; Planes.MIN_INLIERS = m0; Planes.MIN_AREA = ar0; Planes.SNAP = s0; }
        ArrayList<Float> ws = new ArrayList<>();
        for (Planes.Plane q : pl) if ("wall".equals(q.kind)) {
            float l = (float) Math.hypot(q.nx, q.nz); if (l < 0.5f) continue;
            float ux = -q.nz / l, uz = q.nx / l;
            float u0 = Float.MAX_VALUE, u1 = -Float.MAX_VALUE;
            for (int c = 0; c < 4; c++) { float x = (c & 1) == 0 ? q.min[0] : q.max[0], z = (c & 2) == 0 ? q.min[2] : q.max[2]; float u = x * ux + z * uz; u0 = Math.min(u0, u); u1 = Math.max(u1, u); }
            float px = q.nx / l * q.d / l, pz = q.nz / l * q.d / l;
            u0 -= 1.5f; u1 += 1.5f;
            ws.add(px + ux * u0); ws.add(pz + uz * u0); ws.add(px + ux * u1); ws.add(pz + uz * u1);
        }
        float[] out = new float[ws.size()];
        for (int i = 0; i < out.length; i++) out[i] = ws.get(i);
        return out;
    }
}
