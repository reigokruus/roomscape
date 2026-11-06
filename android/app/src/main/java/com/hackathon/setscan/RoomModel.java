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
                else if (h > 0.3f && h < 1.3f) s.kind = Surface.Kind.TABLE;
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
        float maxY = -1e9f;
        for (int i = 0; i < count; i++) maxY = Math.max(maxY, pos.get(i * 3 + 1));
        hasCeiling = false;
        ceilingY = maxY;
        for (Surface s : surfaces) if (s.kind == Surface.Kind.CEILING) { ceilingY = s.cy; hasCeiling = true; }
        if (!hasCeiling && maxY - floorY > 2.0f && maxY - floorY < 4f) hasCeiling = true;

        // 3) Furniture: connected blocked cells in the floor grid that aren't thin wall strips.
        int nx = grid.nx, nz = grid.nz;
        boolean[] seen = new boolean[nx * nz];
        int[] stack = new int[nx * nz];
        float cell = ShotPlanner.CELL;
        float fx0 = Float.MAX_VALUE, fz0 = Float.MAX_VALUE, fx1 = -Float.MAX_VALUE, fz1 = -Float.MAX_VALUE;
        for (int i = 0; i < nx * nz; i++) {
            if (grid.floorKnown[i]) {
                float x = grid.minX + (i % nx + 0.5f) * cell, z = grid.minZ + (i / nx + 0.5f) * cell;
                fx0 = Math.min(fx0, x); fx1 = Math.max(fx1, x); fz0 = Math.min(fz0, z); fz1 = Math.max(fz1, z);
            }
            if (seen[i] || grid.blocked[i] <= ShotPlanner.BLOCKED) continue;
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
                        if (!seen[j] && grid.blocked[j] > ShotPlanner.BLOCKED) { seen[j] = true; stack[sp++] = j; }
                    }
            }
            float w = (ix1 - ix0 + 1) * cell, d = (iz1 - iz0 + 1) * cell;
            float area = n * cell * cell;
            boolean thinStrip = Math.min(w, d) <= 0.3f && Math.max(w, d) > 1.2f; // probably a wall
            boolean tallWall = top > 2.0f && Math.max(w, d) > 1.5f && area < 0.35f * w * d;
            if (area < 0.06f || area > 8f || thinStrip || tallWall) continue;
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
        if (sizeX > 0) sb.append(String.format(Locale.US, "about %.1f × %.1f m", sizeX, sizeZ));
        if (hasCeiling) sb.append(String.format(Locale.US, ", ceiling %.1f m", ceilingY - floorY));
        sb.append('\n');
        sb.append(floor || grid.isReady() ? "Floor found" : "Floor not found");
        sb.append(" · ").append(walls).append(walls == 1 ? " wall" : " walls");
        sb.append(" · ").append(tables).append(tables == 1 ? " table surface" : " table surfaces");
        sb.append(" · ").append(boxes.size()).append(boxes.size() == 1 ? " furniture block" : " furniture blocks");
        sb.append(" · ").append(lights.size()).append(lights.size() == 1 ? " light source" : " light sources");
        for (Light l : lights) sb.append(String.format(Locale.US, "\n💡 %s, %.1f m up", l.label, l.y - floorY));
        summary = sb.toString();
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
        return String.format(Locale.US, "room: floorY=%.2f ceiling=%s size=%.1fx%.1f surfaces=%d boxes=%d lights=%d",
                floorY, hasCeiling ? String.format(Locale.US, "%.2f", ceilingY - floorY) : "none", sizeX, sizeZ,
                surfaces.size(), boxes.size(), lights.size());
    }
}
