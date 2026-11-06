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

    static final class Box {
        float x0, z0, x1, z1, y0, y1;
        String label;
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
    float layoutAngle, roomW, roomD, topY;
    String summary = "";

    void build(List<PlaneData> planes, ShotPlanner grid, FloatBuffer pos, ByteBuffer col, int count) {
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

        // 2) Ceiling height: a ceiling plane if found, else the top of the scan if it looks like a ceiling.
        // Robust top of the scan: the 99.5th percentile of heights, so a few stray specks don't fake a tall ceiling.
        float maxY = floorY;
        if (count > 0) {
            int step = Math.max(1, count / 50000), m = 0;
            float[] ys = new float[count / step + 1];
            for (int i = 0; i < count; i += step) ys[m++] = pos.get(i * 3 + 1);
            java.util.Arrays.sort(ys, 0, m);
            maxY = ys[Math.min(m - 1, (int) (m * 0.995f))];
        }
        hasCeiling = false;
        ceilingY = maxY;
        for (Surface s : surfaces) if (s.kind == Surface.Kind.CEILING) { ceilingY = s.cy; hasCeiling = true; }
        if (!hasCeiling && maxY - floorY > 2.0f && maxY - floorY < 4f) hasCeiling = true;

        topY = hasCeiling ? ceilingY : Math.max(floorY + 2.4f, Math.min(maxY, floorY + 3f));
        fitLayout(pos, count);

        // 3) Furniture: connected blocked cells in the floor grid that aren't thin wall strips.
        int nx = grid.nx, nz = grid.nz;
        boolean[] seen = new boolean[nx * nz];
        int[] stack = new int[nx * nz];
        float cell = ShotPlanner.CELL;
        // With a fitted layout, cells hugging a wall (or outside the room) are wall/doorway clutter, not furniture.
        boolean[] furn = new boolean[nx * nz];
        float lc = (float) Math.cos(Math.toRadians(layoutAngle)), ls = (float) Math.sin(Math.toRadians(layoutAngle));
        float uMin = 0, uMax = 0, vMin = 0, vMax = 0;
        if (hasLayout) {
            Wall w0 = layout.get(0), w2 = layout.get(2);
            uMin = Math.min(w0.x0 * lc + w0.z0 * ls, w0.x1 * lc + w0.z1 * ls);
            uMax = Math.max(w0.x0 * lc + w0.z0 * ls, w0.x1 * lc + w0.z1 * ls);
            vMin = -w0.x0 * ls + w0.z0 * lc; vMax = -w2.x0 * ls + w2.z0 * lc;
            if (vMin > vMax) { float t = vMin; vMin = vMax; vMax = t; }
        }
        for (int i = 0; i < nx * nz; i++) {
            if (grid.blocked[i] <= ShotPlanner.BLOCKED) continue;
            if (hasLayout) {
                float x = grid.minX + (i % nx + 0.5f) * cell, z = grid.minZ + (i / nx + 0.5f) * cell;
                float u = x * lc + z * ls, v = -x * ls + z * lc;
                if (u < uMin + 0.25f || u > uMax - 0.25f || v < vMin + 0.25f || v > vMax - 0.25f) continue;
            }
            furn[i] = true;
        }
        float fx0 = Float.MAX_VALUE, fz0 = Float.MAX_VALUE, fx1 = -Float.MAX_VALUE, fz1 = -Float.MAX_VALUE;
        for (int i = 0; i < nx * nz; i++) {
            if (grid.floorKnown[i]) {
                float x = grid.minX + (i % nx + 0.5f) * cell, z = grid.minZ + (i / nx + 0.5f) * cell;
                fx0 = Math.min(fx0, x); fx1 = Math.max(fx1, x); fz0 = Math.min(fz0, z); fz1 = Math.max(fz1, z);
            }
            if (seen[i] || !furn[i]) continue;
            int sp = 0, n = 0;
            stack[sp++] = i; seen[i] = true;
            int ix0 = nx, iz0 = nz, ix1 = -1, iz1 = -1;
            float top = 0;
            while (sp > 0) {
                int c = stack[--sp];
                n++;
                int cx = c % nx, cz = c / nx;
                ix0 = Math.min(ix0, cx); ix1 = Math.max(ix1, cx); iz0 = Math.min(iz0, cz); iz1 = Math.max(iz1, cz);
                top = Math.max(top, grid.cellTop[c]);
                for (int dz = -1; dz <= 1; dz++)
                    for (int dx = -1; dx <= 1; dx++) {
                        int jx = cx + dx, jz = cz + dz;
                        if (jx < 0 || jz < 0 || jx >= nx || jz >= nz) continue;
                        int j = jz * nx + jx;
                        if (!seen[j] && furn[j]) { seen[j] = true; stack[sp++] = j; }
                    }
            }
            float w = (ix1 - ix0 + 1) * cell, d = (iz1 - iz0 + 1) * cell;
            float area = n * cell * cell;
            boolean thinStrip = Math.min(w, d) <= 0.3f && Math.max(w, d) > 1.2f; // probably a wall
            boolean tallWall = top > 2.0f && Math.max(w, d) > 1.5f && area < 0.35f * w * d;
            boolean huge = hasLayout && w * d > 0.5f * roomW * roomD;
            if (area < 0.06f || area > 8f || thinStrip || tallWall || huge) continue;
            Box b = new Box();
            b.x0 = grid.minX + ix0 * cell; b.x1 = grid.minX + (ix1 + 1) * cell;
            b.z0 = grid.minZ + iz0 * cell; b.z1 = grid.minZ + (iz1 + 1) * cell;
            b.y0 = floorY; b.y1 = floorY + Math.max(0.2f, Math.min(top, 2.2f));
            float h = b.y1 - b.y0;
            b.label = h < 0.6f ? "low furniture (sofa, bed, bench)" : h < 1.2f ? "table / desk height" : "tall furniture (shelf, cabinet)";
            boxes.add(b);
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
        if (hasLayout) sb.append(String.format(Locale.US, "about %.1f × %.1f m", roomW, roomD));
        else if (sizeX > 0) sb.append(String.format(Locale.US, "about %.1f × %.1f m", sizeX, sizeZ));
        if (hasCeiling) sb.append(String.format(Locale.US, ", ceiling %.1f m", ceilingY - floorY));
        sb.append('\n');
        sb.append(floor || grid.isReady() ? "Floor found" : "Floor not found");
        int seenWalls = 0;
        for (Wall w : layout) if (w.seen) seenWalls++;
        if (hasLayout) walls = seenWalls;
        sb.append(" · ").append(walls).append(walls == 1 ? " wall" : " walls");
        if (hasLayout && seenWalls < 4) sb.append(" (").append(4 - seenWalls).append(" guessed)");
        sb.append(" · ").append(tables).append(tables == 1 ? " table surface" : " table surfaces");
        sb.append(" · ").append(boxes.size()).append(boxes.size() == 1 ? " furniture block" : " furniture blocks");
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
    private void fitLayout(FloatBuffer pos, int count) {
        layout.clear(); hasLayout = false;
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
