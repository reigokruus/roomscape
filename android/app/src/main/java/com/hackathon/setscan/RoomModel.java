package com.hackathon.setscan;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A simple "understood" room built from the scan: floor, walls and surfaces (from ARCore planes),
 * furniture blocks (from the occupancy grid) and bright light sources (from point colours).
 * Everything is in ARCore world coordinates (+Y up, metres).
 */
final class RoomModel {

    /** A flat surface ARCore detected, as a world-space convex polygon. */
    static final class Surface {
        enum Kind { FLOOR, WALL, TABLE, CEILING, OTHER }
        Kind kind;
        float[] poly;          // x,y,z triples
        float nx, ny, nz;      // normal
        float cx, cy, cz;      // centre
        float area;
    }

    /** A scanned object as a simple shape: an oriented box or an upright cylinder (see ObjectFinder). */
    static final class Box {
        float x0, z0, x1, z1, y0, y1; // world axis-aligned bounds (for photo cropping)
        String label;
        volatile String name; // what the photo recognition thinks it is (null until labelled)
        volatile String kind; // Claude's category (table, chair, shoe, noise, ...) or null
        float cx, cz, cosA = 1, sinA, hu, hv; // centre, heading and half sizes of the oriented footprint
        boolean cyl;           // round footprint: drawn as a cylinder of radius hu
        Box parent;            // what it stands on (null = floor or wall)
        int depth, voxels;
        String rel, generic;   // "on the table", and a size-based name until the photo names it

        String where() { return parent != null ? "on the " + parent.title() : rel != null ? rel : ""; }

        String title() { return name != null ? name.toLowerCase(Locale.US) : generic != null ? generic : "object"; }

        void updateAabb() {
            float ex = Math.abs(cosA) * hu + Math.abs(sinA) * hv, ez = Math.abs(sinA) * hu + Math.abs(cosA) * hv;
            x0 = cx - ex; x1 = cx + ex; z0 = cz - ez; z1 = cz + ez;
        }

        /** Footprint corner k (0..3) or, for cylinders, point k of n around the rim. */
        float[] rim(int k, int n) {
            if (cyl) { double a = 2 * Math.PI * k / n; return new float[]{cx + hu * (float) Math.cos(a), cz + hu * (float) Math.sin(a)}; }
            float su = (k == 1 || k == 2) ? 1 : -1, sv = k >= 2 ? 1 : -1;
            return new float[]{cx + su * hu * cosA - sv * hv * sinA, cz + su * hu * sinA + sv * hv * cosA};
        }
    }

    static final class Light {
        float x, y, z;
        int strength;
        String label;
    }

    /** One side of the fitted rectangular room. Normal points out of the room. */
    static final class Wall {
        float x0, z0, x1, z1;  // floor-level end points
        float nx, nz;          // outward normal
        boolean seen;          // true = backed by scanned points, false = guessed from the scan's edge
    }

    /** Raw plane data captured on the GL thread before the AR session pauses. */
    static final class PlaneData {
        int type; // 0 = horizontal up, 1 = horizontal down, 2 = vertical
        float[] poly;
        float[] normal;
        float[] center;
    }

    final List<Surface> surfaces = new ArrayList<>();
    final List<Box> boxes = new ArrayList<>();
    final List<Light> lights = new ArrayList<>();
    float floorY, ceilingY, sizeX, sizeZ;
    boolean hasCeiling;
    /** Fitted room layout: a rectangle of 4 walls aligned to the dominant wall direction. */
    final List<Wall> layout = new ArrayList<>();
    boolean hasLayout;
    /** Corners the user tapped on the floor (x, z), in order around the room; overrides the automatic fit. */
    List<float[]> userCorners;
    float userFloorY = Float.NaN, userCeilingY = Float.NaN;
    boolean fromCorners;
    float layoutAngle, roomW, roomD, topY;
    /** Room size the user typed in (0 = unknown). It overrides what the scan measured. */
    float knownH, knownW, knownD;
    /** Share of the room's floor (inside the fitted layout) that the scan has seen, 0..1. */
    float floorCoverage;
    String summary = "";

    /**
     * The top of the room from the points: for each 20 cm column with its own floor (its lowest points within 30 cm of
     * floorY) the height its points reach (98th percentile, so specks don't count) above that floor; then the 90th
     * percentile over the columns, so a small patch of phantom or drifted surface (reflections, a window, depth from
     * motion on a slope) can't make the room taller. NaN with fewer than 20 such columns.
     */
    static float columnTop(FloatBuffer pos, int count, float floorY) {
        int step = Math.max(1, count / 300000);
        java.util.HashMap<Long, float[]> cols = new java.util.HashMap<>(); // {n, then ys...}
        for (int i = 0; i < count; i += step) {
            float x = pos.get(i * 3), y = pos.get(i * 3 + 1), z = pos.get(i * 3 + 2);
            long key = (((long) Math.floor(x / 0.2f)) << 32) ^ (((long) Math.floor(z / 0.2f)) & 0xFFFFFFFFL);
            float[] c = cols.get(key);
            if (c == null) { c = new float[33]; cols.put(key, c); }
            int n = (int) c[0];
            if (n + 1 >= c.length) { c = java.util.Arrays.copyOf(c, c.length * 2); cols.put(key, c); }
            c[n + 1] = y; c[0] = n + 1;
        }
        float[] hs = new float[cols.size()];
        int m = 0;
        for (float[] c : cols.values()) {
            int n = (int) c[0];
            if (n < 30) continue;
            java.util.Arrays.sort(c, 1, n + 1);
            float bottom = c[1 + (int) (n * 0.02f)], top = c[1 + Math.min(n - 1, (int) (n * 0.98f))];
            if (Math.abs(bottom - floorY) < 0.3f) hs[m++] = top - bottom;
        }
        if (m < 20) return Float.NaN;
        java.util.Arrays.sort(hs, 0, m);
        return floorY + hs[(int) (m * 0.9f)];
    }

    ShotPlanner grid; // the floor grid this model was built from
    float[] bounds;   // layout rectangle in layout axes {uMin, uMax, vMin, vMax}, or null

    void build(List<PlaneData> planes, ShotPlanner grid, FloatBuffer pos, ByteBuffer col, int count) {
        this.grid = grid;
        surfaces.clear(); boxes.clear(); lights.clear();
        floorY = grid.floorY;

        // 1) Surfaces from ARCore planes.
        float bestFloorArea = 0;
        for (PlaneData pd : planes) {
            Surface s = new Surface();
            s.poly = pd.poly;
            s.nx = pd.normal[0]; s.ny = pd.normal[1]; s.nz = pd.normal[2];
            s.cx = pd.center[0]; s.cy = pd.center[1]; s.cz = pd.center[2];
            s.area = polygonArea(pd.poly, pd.normal);
            float h = s.cy - floorY;
            if (pd.type == 0) {
                if (Math.abs(h) < 0.15f) s.kind = Surface.Kind.FLOOR;
                else if (h > 0.3f && h < 1.3f && s.area > 0.15f) s.kind = Surface.Kind.TABLE;
                else s.kind = Surface.Kind.OTHER;
            } else if (pd.type == 1) {
                s.kind = h > 1.8f ? Surface.Kind.CEILING : Surface.Kind.OTHER;
            } else {
                s.kind = s.area > 0.3f ? Surface.Kind.WALL : Surface.Kind.OTHER;
            }
            if (s.kind == Surface.Kind.FLOOR && s.area > bestFloorArea) { bestFloorArea = s.area; }
            surfaces.add(s);
        }
        // A large ARCore floor plane is more precise than the point histogram.
        for (Surface s : surfaces)
            if (s.kind == Surface.Kind.FLOOR && s.area == bestFloorArea && bestFloorArea > 1f) floorY = s.cy;
        if (!Float.isNaN(userFloorY)) floorY = userFloorY;

        // 2) Ceiling height: a ceiling plane if found, else the top of the scan if it looks like a ceiling.
        // Robust top of the scan: the 99.5th percentile of heights, so a few stray specks don't fake a tall ceiling.
        float maxY = floorY;
        if (count > 0) {
            int step = Math.max(1, count / 50000), m = 0;
            float[] ys = new float[count / step + 1];
            for (int i = 0; i < count; i += step) ys[m++] = pos.get(i * 3 + 1);
            java.util.Arrays.sort(ys, 0, m);
            maxY = ys[Math.min(m - 1, (int) (m * 0.995f))];
            // Better: per 20 cm column, how high the scan goes above that column's own floor; the room's height is
            // the 90th percentile of those (rk 9 Nov: a room 2.9 m at its highest came out 3.16 m, from one sloped
            // band the S24's depth-from-motion put 0.3-0.45 m too high; this gives 2.84 m on that scan).
            float top = columnTop(pos, count, floorY);
            if (!Float.isNaN(top)) maxY = top;
        }
        hasCeiling = false;
        ceilingY = maxY;
        for (Surface s : surfaces) if (s.kind == Surface.Kind.CEILING) { ceilingY = s.cy; hasCeiling = true; }
        if (!hasCeiling && maxY - floorY > 2.0f && maxY - floorY < 4f) hasCeiling = true;

        if (!Float.isNaN(userCeilingY) && userCeilingY > floorY + 1.2f) { ceilingY = userCeilingY; hasCeiling = true; }
        if (knownH > 0.5f) { ceilingY = floorY + knownH; hasCeiling = true; }
        topY = hasCeiling ? ceilingY : Math.max(floorY + 2.4f, Math.min(maxY, floorY + 3f));
        fitLayout(pos, count);
        floorCoverage = 0;
        if (hasLayout) {
            float lc = (float) Math.cos(Math.toRadians(layoutAngle)), ls = (float) Math.sin(Math.toRadians(layoutAngle));
            float[] ub = layoutBounds(lc, ls);
            float u0 = ub[0], u1 = ub[1], v0 = ub[2], v1 = ub[3];
            int known = 0;
            for (int i = 0; i < grid.nx * grid.nz; i++) {
                if (!grid.floorKnown[i] && grid.blocked[i] <= ShotPlanner.BLOCKED) continue; // furniture counts as seen floor
                float x = grid.minX + (i % grid.nx + 0.5f) * ShotPlanner.CELL, z = grid.minZ + (i / grid.nx + 0.5f) * ShotPlanner.CELL;
                float u = x * lc + z * ls, v = -x * ls + z * lc;
                if (u >= u0 && u <= u1 && v >= v0 && v <= v1) known++;
            }
            floorCoverage = Math.min(1f, known * ShotPlanner.CELL * ShotPlanner.CELL / Math.max(0.01f, roomW * roomD));
        }

        // 3) Objects: separate clusters of points, each as a box or cylinder, with what it stands on.
        int nx = grid.nx, nz = grid.nz;
        float cell = ShotPlanner.CELL;
        float[] bounds = null; this.bounds = null;
        if (hasLayout) {
            float lc = (float) Math.cos(Math.toRadians(layoutAngle)), ls = (float) Math.sin(Math.toRadians(layoutAngle));
            bounds = layoutBounds(lc, ls);
            this.bounds = bounds;
        }
        boxes.addAll(ObjectFinder.find(pos, count, floorY, topY, hasLayout ? layoutAngle : 0, bounds, hasLayout ? roomW * roomD : 0));
        for (Box bx : boxes) bx.label = bx.generic;
        float fx0 = Float.MAX_VALUE, fz0 = Float.MAX_VALUE, fx1 = -Float.MAX_VALUE, fz1 = -Float.MAX_VALUE;
        for (int i = 0; i < nx * nz; i++) {
            if (grid.floorKnown[i]) {
                float x = grid.minX + (i % nx + 0.5f) * cell, z = grid.minZ + (i / nx + 0.5f) * cell;
                fx0 = Math.min(fx0, x); fx1 = Math.max(fx1, x); fz0 = Math.min(fz0, z); fz1 = Math.max(fz1, z);
            }
        }
        sizeX = fx1 > fx0 ? fx1 - fx0 + cell : 0;
        sizeZ = fz1 > fz0 ? fz1 - fz0 + cell : 0;

        // 4) Light sources: clusters of very bright points (windows, lamps, ceiling lights).
        Map<Long, int[]> bright = new HashMap<>();
        Map<Long, float[]> sums = new HashMap<>();
        for (int i = 0; i < count; i++) {
            int r = col.get(i * 4) & 0xFF, g = col.get(i * 4 + 1) & 0xFF, b = col.get(i * 4 + 2) & 0xFF;
            if (r + g + b < 3 * 235) continue;
            float x = pos.get(i * 3), y = pos.get(i * 3 + 1), z = pos.get(i * 3 + 2);
            long k = (((long) Math.floor(x / 0.4f) + 5000) << 32) | (((long) Math.floor(y / 0.4f) + 5000) << 16) | ((long) Math.floor(z / 0.4f) + 5000);
            int[] c = bright.get(k);
            if (c == null) { bright.put(k, new int[]{1}); sums.put(k, new float[]{x, y, z}); }
            else { c[0]++; float[] sm = sums.get(k); sm[0] += x; sm[1] += y; sm[2] += z; }
        }
        List<Long> keys = new ArrayList<>(bright.keySet());
        keys.sort((a, b2) -> bright.get(b2)[0] - bright.get(a)[0]);
        for (Long k : keys) {
            int n = bright.get(k)[0];
            if (n < 40 || lights.size() >= 6) break;
            float[] sm = sums.get(k);
            float x = sm[0] / n, y = sm[1] / n, z = sm[2] / n;
            boolean merged = false;
            for (Light l : lights) {
                if (Math.abs(l.x - x) < 0.9f && Math.abs(l.y - y) < 0.9f && Math.abs(l.z - z) < 0.9f) { l.strength += n; merged = true; break; }
            }
            if (merged) continue;
            Light l = new Light();
            l.x = x; l.y = y; l.z = z; l.strength = n;
            float h = y - floorY;
            if (h < 0.1f) continue; // below the floor: a reflection, not a light
            if (hasCeiling && ceilingY - y < 0.5f) l.label = "ceiling light";
            else if (h > 0.6f && nearWall(x, y, z)) l.label = "window or wall light";
            else l.label = "lamp / bright spot";
            lights.add(l);
        }

        // 5) Summary in plain words.
        int walls = 0, tables = 0; boolean floor = false;
        for (Surface s : surfaces) {
            if (s.kind == Surface.Kind.WALL) walls++;
            if (s.kind == Surface.Kind.TABLE) tables++;
            if (s.kind == Surface.Kind.FLOOR) floor = true;
        }
        StringBuilder sb = new StringBuilder("Room: ");
        if (hasLayout) sb.append(String.format(Locale.US, "about %.1f × %.1f m", roomW, roomD)).append(fromCorners ? String.format(Locale.US, " (from your %d corners, %.1f m² floor)", layout.size(), floorArea()) : "");
        else if (sizeX > 0) sb.append(String.format(Locale.US, "about %.1f × %.1f m", sizeX, sizeZ));
        if ((knownW > 0.5f || knownD > 0.5f) && hasLayout) sb.append(" (entered)");
        if (hasCeiling) sb.append(String.format(Locale.US, ", ceiling %.1f m", ceilingY - floorY)).append(knownH > 0.5f ? " (entered)" : "");
        if (hasLayout) sb.append(String.format(Locale.US, ", floor %d%% scanned", Math.round(floorCoverage * 100)));
        sb.append('\n');
        sb.append(floor || grid.isReady() ? "Floor found" : "Floor not found");
        int seenWalls = 0;
        for (Wall w : layout) if (w.seen) seenWalls++;
        if (hasLayout) walls = seenWalls;
        sb.append(" · ").append(walls).append(walls == 1 ? " wall" : " walls");
        if (hasLayout && !fromCorners && seenWalls < 4) sb.append(" (").append(4 - seenWalls).append(" guessed)");
        sb.append(" · ").append(tables).append(tables == 1 ? " table surface" : " table surfaces");
        int onTop = 0;
        for (Box bx : boxes) if (bx.parent != null) onTop++;
        sb.append(" · ").append(boxes.size()).append(boxes.size() == 1 ? " object" : " objects");
        if (onTop > 0) sb.append(" (").append(onTop).append(" on other things)");
        sb.append(" · ").append(lights.size()).append(lights.size() == 1 ? " light source" : " light sources");
        for (Light l : lights) sb.append(String.format(Locale.US, "\n💡 %s, %.1f m up", l.label, l.y - floorY));
        summary = sb.toString();
    }

    /**
     * Layout fitting (Manhattan-world assumption, like RoomPlan): walls are flat, vertical and at right angles.
     * 1) find the rotation where wall-height points pile up into the sharpest lines (peaky histograms);
     * 2) on each axis, the outermost strong peak on each side is a wall;
     * 3) a side with no peak gets a guessed wall at the scan's edge.
     */
    /** Layout extent in layout axes {uMin, uMax, vMin, vMax} over every wall corner. */
    float[] layoutBounds(float lc, float ls) {
        float u0 = 1e9f, u1 = -1e9f, v0 = 1e9f, v1 = -1e9f;
        for (Wall w : layout) for (int e = 0; e < 2; e++) {
            float x = e == 0 ? w.x0 : w.x1, z = e == 0 ? w.z0 : w.z1, u = x * lc + z * ls, v = -x * ls + z * lc;
            u0 = Math.min(u0, u); u1 = Math.max(u1, u); v0 = Math.min(v0, v); v1 = Math.max(v1, v);
        }
        return new float[]{u0, u1, v0, v1};
    }

    float floorArea() {
        double a = 0;
        for (Wall w : layout) a += w.x0 * w.z1 - w.x1 * w.z0;
        return (float) Math.abs(a / 2);
    }

    /** Walls straight from the tapped corners (any number, any shape), normals pointing out of the room. */
    private void cornerLayout() {
        int n = userCorners.size();
        float mx = 0, mz = 0;
        for (float[] c : userCorners) { mx += c[0]; mz += c[1]; }
        mx /= n; mz /= n;
        for (int i = 0; i < n; i++) {
            float[] a = userCorners.get(i), b = userCorners.get((i + 1) % n);
            Wall w = new Wall();
            w.x0 = a[0]; w.z0 = a[1]; w.x1 = b[0]; w.z1 = b[1];
            float dx = b[0] - a[0], dz = b[1] - a[1], l = (float) Math.hypot(dx, dz);
            if (l < 0.05f) continue;
            w.nx = dz / l; w.nz = -dx / l;
            if ((((a[0] + b[0]) / 2 - mx) * w.nx + ((a[1] + b[1]) / 2 - mz) * w.nz) < 0) { w.nx = -w.nx; w.nz = -w.nz; }
            w.seen = true;
            layout.add(w);
        }
        if (layout.size() < 3) { layout.clear(); return; }
        // the longest wall sets the room's axes
        Wall lw = layout.get(0);
        for (Wall w : layout) if (Math.hypot(w.x1 - w.x0, w.z1 - w.z0) > Math.hypot(lw.x1 - lw.x0, lw.z1 - lw.z0)) lw = w;
        layoutAngle = (float) Math.toDegrees(Math.atan2(lw.z1 - lw.z0, lw.x1 - lw.x0));
        float lc = (float) Math.cos(Math.toRadians(layoutAngle)), ls = (float) Math.sin(Math.toRadians(layoutAngle));
        float[] b = layoutBounds(lc, ls);
        roomW = b[1] - b[0]; roomD = b[3] - b[2];
        hasLayout = true; fromCorners = true;
    }

    private void fitLayout(FloatBuffer pos, int count) {
        layout.clear(); hasLayout = false; fromCorners = false;
        if (userCorners != null && userCorners.size() >= 3) { cornerLayout(); if (hasLayout) return; }
        float lo = floorY + 0.3f, hi = topY - 0.15f;
        if (hi - lo < 0.5f) hi = lo + 1.5f;
        int step = Math.max(1, count / 80000);
        float[] xs = new float[count / step + 1], zs = new float[xs.length];
        int n = 0;
        for (int i = 0; i < count; i += step) {
            float y = pos.get(i * 3 + 1);
            if (y < lo || y > hi) continue;
            xs[n] = pos.get(i * 3); zs[n] = pos.get(i * 3 + 2); n++;
        }
        if (n < 500) return;
        // ARCore wall planes give good candidate angles; the histogram search covers the rest.
        float best = 0; double bestScore = -1;
        for (int a = 0; a < 90; a++) {
            double sc = alignScore(xs, zs, n, (float) Math.toRadians(a));
            if (sc > bestScore) { bestScore = sc; best = a; }
        }
        for (Surface s : surfaces) {
            if (s.kind != Surface.Kind.WALL) continue;
            float a = (float) Math.toDegrees(Math.atan2(s.nz, s.nx));
            a = ((a % 90) + 90) % 90;
            double sc = alignScore(xs, zs, n, (float) Math.toRadians(a));
            if (sc > bestScore) { bestScore = sc; best = a; }
        }
        for (float a = best - 1f; a <= best + 1f; a += 0.1f) {
            double sc = alignScore(xs, zs, n, (float) Math.toRadians(a));
            if (sc > bestScore) { bestScore = sc; best = a; }
        }
        layoutAngle = best;
        float th = (float) Math.toRadians(best), c = (float) Math.cos(th), sn = (float) Math.sin(th);
        float[] us = new float[n], vs = new float[n];
        for (int i = 0; i < n; i++) { us[i] = xs[i] * c + zs[i] * sn; vs[i] = -xs[i] * sn + zs[i] * c; }
        float[] ub = walls1D(us, n), vb = walls1D(vs, n);
        if (ub == null || vb == null) return;
        // Known width/depth: the longer known size goes on the longer scanned side. Walls the scan saw stay put.
        if (knownW > 0.5f || knownD > 0.5f) {
            float a = Math.max(knownW, knownD), b = Math.min(knownW, knownD);
            boolean uLong = ub[1] - ub[0] >= vb[1] - vb[0];
            if (b <= 0.5f) { // only one size given: put it on the side it is closest to
                if (Math.abs((ub[1] - ub[0]) - a) <= Math.abs((vb[1] - vb[0]) - a)) fitKnown(ub, a); else fitKnown(vb, a);
            } else {
                fitKnown(uLong ? ub : vb, a);
                fitKnown(uLong ? vb : ub, b);
            }
        }
        roomW = ub[1] - ub[0]; roomD = vb[1] - vb[0];
        if (roomW < 1f || roomD < 1f || roomW > 20f || roomD > 20f) return;
        // corners in (u,v), back to world (x,z): x = u*c - v*sn, z = u*sn + v*c
        float[][] uv = {{ub[0], vb[0]}, {ub[1], vb[0]}, {ub[1], vb[1]}, {ub[0], vb[1]}};
        float[][] out = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}}; // outward normal of side k (from corner k to k+1) in (u,v)
        boolean[] seen = {vb[2] > 0, ub[3] > 0, vb[3] > 0, ub[2] > 0};
        for (int k = 0; k < 4; k++) {
            float[] p0 = uv[k], p1 = uv[(k + 1) % 4];
            Wall w = new Wall();
            w.x0 = p0[0] * c - p0[1] * sn; w.z0 = p0[0] * sn + p0[1] * c;
            w.x1 = p1[0] * c - p1[1] * sn; w.z1 = p1[0] * sn + p1[1] * c;
            w.nx = out[k][0] * c - out[k][1] * sn; w.nz = out[k][0] * sn + out[k][1] * c;
            w.seen = seen[k];
            layout.add(w);
        }
        hasLayout = true;
    }

    private static void fitKnown(float[] b, float len) {
        boolean lo = b[2] > 0, hi = b[3] > 0;
        if (lo && !hi) b[1] = b[0] + len;
        else if (hi && !lo) b[0] = b[1] - len;
        else { float m = (b[0] + b[1]) / 2; b[0] = m - len / 2; b[1] = m + len / 2; }
    }

    /** Sum of squared 5 cm histogram counts along both rotated axes: highest when walls line up with the bins. */
    private static double alignScore(float[] xs, float[] zs, int n, float th) {
        float c = (float) Math.cos(th), s = (float) Math.sin(th);
        java.util.HashMap<Integer, int[]> hu = new java.util.HashMap<>(), hv = new java.util.HashMap<>();
        int step = Math.max(1, n / 20000);
        for (int i = 0; i < n; i += step) {
            int bu = (int) Math.floor((xs[i] * c + zs[i] * s) / 0.05f), bv = (int) Math.floor((-xs[i] * s + zs[i] * c) / 0.05f);
            int[] a = hu.get(bu); if (a == null) hu.put(bu, new int[]{1}); else a[0]++;
            int[] b = hv.get(bv); if (b == null) hv.put(bv, new int[]{1}); else b[0]++;
        }
        double sc = 0;
        for (int[] a : hu.values()) sc += (double) a[0] * a[0];
        for (int[] b : hv.values()) sc += (double) b[0] * b[0];
        return sc;
    }

    /** Returns {low wall, high wall, lowSeen(1/0), highSeen(1/0)} along one axis, or null. */
    private static float[] walls1D(float[] v, int n) {
        float[] sorted = java.util.Arrays.copyOf(v, n);
        java.util.Arrays.sort(sorted);
        float p1 = sorted[(int) (n * 0.01f)], p99 = sorted[(int) (n * 0.99f) - 1];
        float bin = 0.05f, lo = sorted[0] - 2 * bin; // pad so a wall at the very edge can still be a peak
        int nb = (int) ((sorted[n - 1] - lo) / bin) + 5;
        if (nb > 2000) { lo = p1 - 0.5f; nb = (int) ((p99 + 0.5f - lo) / bin) + 5; }
        int[] h = new int[nb];
        for (int i = 0; i < n; i++) { int b = (int) ((v[i] - lo) / bin); if (b >= 0 && b < nb) h[b]++; }
        float[] sm = new float[nb];
        float mx = 0;
        for (int b = 0; b < nb; b++) {
            sm[b] = h[b] + (b > 0 ? h[b - 1] : 0) * 0.5f + (b < nb - 1 ? h[b + 1] : 0) * 0.5f;
            mx = Math.max(mx, sm[b]);
        }
        // A wall is a peak that stands well above its surroundings and holds a fair share of points.
        float thr = Math.max(mx * 0.18f, n * 0.004f);
        int lowB = -1, highB = -1;
        for (int b = 1; b < nb - 1; b++) if (sm[b] >= thr && sm[b] >= sm[b - 1] && sm[b] >= sm[b + 1]) { lowB = b; break; }
        for (int b = nb - 2; b > 0; b--) if (sm[b] >= thr && sm[b] >= sm[b - 1] && sm[b] >= sm[b + 1]) { highB = b; break; }
        float a = p1, z = p99, aSeen = 0, zSeen = 0;
        float mid = (p1 + p99) / 2;
        if (lowB >= 0) { float w = lo + (lowB + 0.5f) * bin; if (w < mid) { a = w; aSeen = 1; } }
        if (highB >= 0) { float w = lo + (highB + 0.5f) * bin; if (w > mid) { z = w; zSeen = 1; } }
        // A "wall" with lots of scanned stuff behind it is really furniture/stairs inside a bigger (partly scanned) room:
        // push it out to the edge of the scan (the real wall is behind it).
        int behindLo = 0, behindHi = 0;
        for (int i = 0; i < n; i++) { if (v[i] < a - 0.15f) behindLo++; if (v[i] > z + 0.15f) behindHi++; }
        if (behindLo > n * 0.05f) a = p1;
        if (behindHi > n * 0.05f) z = p99;
        if (z - a < 0.5f) return null;
        return new float[]{a, z, aSeen, zSeen};
    }

    private boolean nearWall(float x, float y, float z) {
        for (Surface s : surfaces) {
            if (s.kind != Surface.Kind.WALL) continue;
            float d = (x - s.cx) * s.nx + (y - s.cy) * s.ny + (z - s.cz) * s.nz;
            float lateral = (float) Math.hypot(x - s.cx, z - s.cz);
            if (Math.abs(d) < 0.3f && lateral < 3f) return true;
        }
        return false;
    }

    private String seenMask() {
        StringBuilder b = new StringBuilder();
        for (Wall w : layout) b.append(w.seen ? '1' : '0');
        return b.toString();
    }

    private static float polygonArea(float[] p, float[] n) {
        // area of a planar polygon = |sum(cross(v_i, v_{i+1})) . n| / 2
        float ax = 0, ay = 0, az = 0;
        int m = p.length / 3;
        for (int i = 0; i < m; i++) {
            int j = (i + 1) % m;
            float x0 = p[i * 3], y0 = p[i * 3 + 1], z0 = p[i * 3 + 2];
            float x1 = p[j * 3], y1 = p[j * 3 + 1], z1 = p[j * 3 + 2];
            ax += y0 * z1 - z0 * y1; ay += z0 * x1 - x0 * z1; az += x0 * y1 - y0 * x1;
        }
        return Math.abs(ax * n[0] + ay * n[1] + az * n[2]) / 2f;
    }

    String logLine() {
        return String.format(Locale.US, "room: floorY=%.2f ceiling=%s size=%.1fx%.1f layout=%s surfaces=%d boxes=%d lights=%d",
                floorY, hasCeiling ? String.format(Locale.US, "%.2f", ceilingY - floorY) : "none", sizeX, sizeZ,
                hasLayout ? String.format(Locale.US, "%.1fx%.1f@%.1fdeg seen=%s", roomW, roomD, layoutAngle, seenMask()) : "none",
                surfaces.size(), boxes.size(), lights.size());
    }
}
