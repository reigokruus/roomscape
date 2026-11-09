package com.hackathon.setscan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Flat colour = plane" (rk 9 Nov), for phones without LiDAR: a plain, single-coloured surface (a painted wall, the
 * floor, a door) is taken to be flat, so its lumpy camera-depth surface can be pressed onto one clean plane.
 *
 * Conservative on purpose (whole-room wall fitting in v0.1.49–50 was unreliable): no room box is ever forced. The
 * points are grouped into 6 cm cells; a region grows only over cells that are themselves flat, face the same way, lie
 * on the same plane and have nearly the same colour. A region becomes a plane only if it's big (0.4 m² or more) and
 * nearly all its points are within 3 cm of the fitted plane. Nearly-vertical planes are made exactly vertical (walls)
 * and nearly-level ones exactly level (floor, ceiling). Only the drawn surface is snapped; the scan's points are kept.
 *
 * Plain arrays and no Android classes, so it can be tested on a computer.
 */
final class Planes {
    static float CELL = 0.06f;
    /** Smallest plane kept, m². */
    static float MIN_AREA = 0.4f;
    /** Points within this of the plane count as on it, m. */
    static float INLIER = 0.03f;
    /** Share of the region's points that must be on the plane. */
    static float MIN_INLIERS = 0.85f;
    /** Neighbouring cells join a region only within these: normal angle, distance to the plane, colour (Lab ΔE). */
    static float MAX_ANGLE_DEG = 12, MAX_OFFSET = 0.03f, MAX_DE = 9;
    /** A cell counts as flat below this (smallest spread / total spread). */
    static float MAX_FLAT = 0.06f;
    /** Mesh vertices this close to a plane, in its cells, are snapped onto it. */
    static float SNAP = 0.05f;

    static final class Plane {
        /** Unit normal and offset: n · p = d. */
        float nx, ny, nz, d;
        float area;
        /** Mean colour (0..255) and how many points. */
        float r, g, b;
        int points;
        /** Share of its points within INLIER of it. */
        float onShare;
        /** "wall", "floor", "ceiling" or "surface". */
        String kind;
        /** World-space bounds of its points. */
        float[] min = new float[3], max = new float[3];
        /** The cells it covers (keys, see `key`). */
        final List<Long> cells = new ArrayList<>();
    }

    private static final class Cell {
        int n;
        double sx, sy, sz, sxx, sxy, sxz, syy, syz, szz, sr, sg, sb, srr, sgg, sbb;
        float cx, cy, cz, nx, ny, nz, flat, L, A, B, colourSd;
        boolean ok;
        int region = -1;
    }

    static long key(int x, int y, int z) {
        return ((long) (x + (1 << 20)) & 0x1FFFFF) | (((long) (y + (1 << 20)) & 0x1FFFFF) << 21) | (((long) (z + (1 << 20)) & 0x1FFFFF) << 42);
    }

    static int cellOf(float v) { return (int) Math.floor(v / CELL); }

    /**
     * The planes in a point cloud: xyz (3 floats a point) and rgb (3 bytes a point, or `colStride` bytes).
     * `cellsOut`, when not null, gets each cell key -> plane index for snapping.
     */
    static List<Plane> find(float[] xyz, byte[] rgb, int colStride, int n, Map<Long, Integer> cellsOut) {
        HashMap<Long, Cell> cells = new HashMap<>();
        for (int i = 0; i < n; i++) {
            float x = xyz[i * 3], y = xyz[i * 3 + 1], z = xyz[i * 3 + 2];
            long k = key(cellOf(x), cellOf(y), cellOf(z));
            Cell c = cells.get(k);
            if (c == null) { c = new Cell(); cells.put(k, c); }
            c.n++;
            c.sx += x; c.sy += y; c.sz += z;
            c.sxx += x * x; c.sxy += x * y; c.sxz += x * z; c.syy += y * y; c.syz += y * z; c.szz += z * z;
            int r = rgb[i * colStride] & 0xFF, g = rgb[i * colStride + 1] & 0xFF, b = rgb[i * colStride + 2] & 0xFF;
            c.sr += r; c.sg += g; c.sb += b; c.srr += r * r; c.sgg += g * g; c.sbb += b * b;
        }
        // Each cell: centre, normal (smallest axis of its points' spread), flatness and colour.
        double[][] cov = new double[3][3], vec = new double[3][3];
        double[] val = new double[3];
        for (Cell c : cells.values()) {
            if (c.n < 6) continue;
            double inv = 1.0 / c.n;
            double mx = c.sx * inv, my = c.sy * inv, mz = c.sz * inv;
            cov[0][0] = c.sxx * inv - mx * mx; cov[0][1] = cov[1][0] = c.sxy * inv - mx * my; cov[0][2] = cov[2][0] = c.sxz * inv - mx * mz;
            cov[1][1] = c.syy * inv - my * my; cov[1][2] = cov[2][1] = c.syz * inv - my * mz; cov[2][2] = c.szz * inv - mz * mz;
            eigen(cov, val, vec);
            int lo = 0; for (int a = 1; a < 3; a++) if (val[a] < val[lo]) lo = a;
            double total = val[0] + val[1] + val[2];
            if (total <= 1e-12) continue;
            c.cx = (float) mx; c.cy = (float) my; c.cz = (float) mz;
            c.nx = (float) vec[0][lo]; c.ny = (float) vec[1][lo]; c.nz = (float) vec[2][lo];
            c.flat = (float) (Math.max(0, val[lo]) / total);
            double r = c.sr * inv, g = c.sg * inv, b = c.sb * inv;
            c.colourSd = (float) Math.sqrt(Math.max(0, (c.srr * inv - r * r + c.sgg * inv - g * g + c.sbb * inv - b * b) / 3));
            float[] lab = lab((float) r, (float) g, (float) b);
            c.L = lab[0]; c.A = lab[1]; c.B = lab[2];
            // Flat (thin across, ~1 cm of noise in 6 cm) and plain (one colour).
            c.ok = c.flat < MAX_FLAT && c.colourSd < 22;
        }

        // Region growing from the flattest cells.
        List<Map.Entry<Long, Cell>> seeds = new ArrayList<>();
        for (Map.Entry<Long, Cell> e : cells.entrySet()) if (e.getValue().ok) seeds.add(e);
        seeds.sort((p, q) -> Float.compare(p.getValue().flat, q.getValue().flat));
        float cosMax = (float) Math.cos(Math.toRadians(MAX_ANGLE_DEG));
        List<Plane> planes = new ArrayList<>();
        int regions = 0;
        ArrayList<Long> queue = new ArrayList<>();
        for (Map.Entry<Long, Cell> seed : seeds) {
            Cell s = seed.getValue();
            if (s.region >= 0) continue;
            int id = regions++;
            float nx = s.nx, ny = s.ny, nz = s.nz, d = nx * s.cx + ny * s.cy + nz * s.cz;
            double L = s.L, A = s.A, B = s.B; int members = 1;
            List<Long> got = new ArrayList<>();
            queue.clear(); queue.add(seed.getKey()); s.region = id;
            for (int qi = 0; qi < queue.size(); qi++) {
                long k = queue.get(qi);
                got.add(k);
                int kx = (int) (k & 0x1FFFFF) - (1 << 20), ky = (int) ((k >> 21) & 0x1FFFFF) - (1 << 20), kz = (int) ((k >> 42) & 0x1FFFFF) - (1 << 20);
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    long nk = key(kx + dx, ky + dy, kz + dz);
                    Cell c = cells.get(nk);
                    if (c == null || !c.ok || c.region >= 0) continue;
                    if (Math.abs(c.nx * nx + c.ny * ny + c.nz * nz) < cosMax) continue;
                    if (Math.abs(nx * c.cx + ny * c.cy + nz * c.cz - d) > MAX_OFFSET) continue;
                    double dl = c.L - L / members, da = c.A - A / members, db = c.B - B / members;
                    if (Math.sqrt(dl * dl + da * da + db * db) > MAX_DE) continue;
                    c.region = id;
                    L += c.L; A += c.A; B += c.B; members++;
                    queue.add(nk);
                }
            }
            if (got.size() * CELL * CELL < MIN_AREA) continue;
            Plane p = fit(cells, got);
            if (p != null) planes.add(p);
        }

        // Accept: refit on the region's points and check they really are on the plane.
        List<Plane> kept = new ArrayList<>();
        for (Plane p : planes) {
            int on = 0, all = 0;
            float[] mn = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, mxx = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            java.util.HashSet<Long> mine = new java.util.HashSet<>(p.cells);
            double r = 0, g = 0, b = 0;
            for (int i = 0; i < n; i++) {
                float x = xyz[i * 3], y = xyz[i * 3 + 1], z = xyz[i * 3 + 2];
                if (!mine.contains(key(cellOf(x), cellOf(y), cellOf(z)))) continue;
                all++;
                if (Math.abs(p.nx * x + p.ny * y + p.nz * z - p.d) <= INLIER) on++;
                if (x < mn[0]) mn[0] = x; if (y < mn[1]) mn[1] = y; if (z < mn[2]) mn[2] = z;
                if (x > mxx[0]) mxx[0] = x; if (y > mxx[1]) mxx[1] = y; if (z > mxx[2]) mxx[2] = z;
                r += rgb[i * colStride] & 0xFF; g += rgb[i * colStride + 1] & 0xFF; b += rgb[i * colStride + 2] & 0xFF;
            }
            if (all == 0 || on < MIN_INLIERS * all) continue;
            p.onShare = on / (float) all;
            p.points = all; p.r = (float) (r / all); p.g = (float) (g / all); p.b = (float) (b / all);
            p.min = mn; p.max = mxx;
            kept.add(p);
        }

        // Same plane found as two regions (split by a picture or a socket): merge when they agree (10°, 5 cm, colour).
        float cosMerge = (float) Math.cos(Math.toRadians(10));
        for (int i = 0; i < kept.size(); i++) for (int j = kept.size() - 1; j > i; j--) {
            Plane a = kept.get(i), b = kept.get(j);
            float dot = a.nx * b.nx + a.ny * b.ny + a.nz * b.nz;
            if (dot < cosMerge) continue;
            if (Math.abs(a.d - b.d) > 0.05f) continue;
            float[] la = lab(a.r, a.g, a.b), lb = lab(b.r, b.g, b.b);
            if (dist(la, lb) > MAX_DE * 1.5f) continue;
            float wa = a.points, wb = b.points, w = wa + wb;
            a.r = (a.r * wa + b.r * wb) / w; a.g = (a.g * wa + b.g * wb) / w; a.b = (a.b * wa + b.b * wb) / w;
            a.d = (a.d * wa + b.d * wb) / w;
            a.points += b.points; a.area += b.area; a.cells.addAll(b.cells);
            for (int k = 0; k < 3; k++) { a.min[k] = Math.min(a.min[k], b.min[k]); a.max[k] = Math.max(a.max[k], b.max[k]); }
            kept.remove(j);
        }
        if (cellsOut != null) for (int i = 0; i < kept.size(); i++) for (long k : kept.get(i).cells) cellsOut.put(k, i);
        return kept;
    }

    /** Least-squares plane through the cells' points (from their sums), squared up to vertical / level when close. */
    private static Plane fit(HashMap<Long, Cell> cells, List<Long> keys) {
        double n = 0, sx = 0, sy = 0, sz = 0, sxx = 0, sxy = 0, sxz = 0, syy = 0, syz = 0, szz = 0;
        for (long k : keys) {
            Cell c = cells.get(k);
            n += c.n; sx += c.sx; sy += c.sy; sz += c.sz;
            sxx += c.sxx; sxy += c.sxy; sxz += c.sxz; syy += c.syy; syz += c.syz; szz += c.szz;
        }
        if (n < 50) return null;
        double mx = sx / n, my = sy / n, mz = sz / n;
        double[][] cov = {{sxx / n - mx * mx, sxy / n - mx * my, sxz / n - mx * mz},
                {sxy / n - mx * my, syy / n - my * my, syz / n - my * mz},
                {sxz / n - mx * mz, syz / n - my * mz, szz / n - mz * mz}};
        double[] val = new double[3]; double[][] vec = new double[3][3];
        eigen(cov, val, vec);
        int lo = 0; for (int a = 1; a < 3; a++) if (val[a] < val[lo]) lo = a;
        float nx = (float) vec[0][lo], ny = (float) vec[1][lo], nz = (float) vec[2][lo];
        // Walls exactly vertical, floors and ceilings exactly level (within 8°).
        String kind = "surface";
        if (Math.abs(ny) < Math.sin(Math.toRadians(8))) {
            ny = 0; float l = (float) Math.sqrt(nx * nx + nz * nz); nx /= l; nz /= l; kind = "wall";
        } else if (Math.abs(ny) > Math.cos(Math.toRadians(8))) {
            nx = 0; nz = 0; ny = ny > 0 ? 1 : -1; kind = "level";
        }
        Plane p = new Plane();
        p.nx = nx; p.ny = ny; p.nz = nz; p.d = (float) (nx * mx + ny * my + nz * mz);
        p.area = keys.size() * CELL * CELL;
        p.kind = kind;
        p.cells.addAll(keys);
        return p;
    }

    /**
     * Snaps a triangle soup's vertices (3 floats each) onto the planes: a vertex in (or next to) a plane's cells and
     * within `SNAP` of it moves onto it. Returns how many moved. The same position always moves the same way, so
     * shared corners of the soup stay together.
     */
    static int snap(float[] v, int verts, List<Plane> planes, Map<Long, Integer> cellPlane) {
        int moved = 0;
        for (int i = 0; i < verts; i++) {
            float x = v[i * 3], y = v[i * 3 + 1], z = v[i * 3 + 2];
            int cx = cellOf(x), cy = cellOf(y), cz = cellOf(z);
            Plane best = null; float bestD = SNAP;
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                Integer pi = cellPlane.get(key(cx + dx, cy + dy, cz + dz));
                if (pi == null) continue;
                Plane p = planes.get(pi);
                float dist = Math.abs(p.nx * x + p.ny * y + p.nz * z - p.d);
                if (dist < bestD || (dist == bestD && best == null)) { best = p; bestD = dist; }
            }
            if (best == null) continue;
            float s = best.nx * x + best.ny * y + best.nz * z - best.d;
            v[i * 3] = x - best.nx * s; v[i * 3 + 1] = y - best.ny * s; v[i * 3 + 2] = z - best.nz * s;
            moved++;
        }
        return moved;
    }

    /** The planes as JSON for the .rmsc manifest ("planes", docs/rmsc-format.md), moved so the floor is at y = floorY. */
    static org.json.JSONArray toJson(List<Plane> planes, float floorY, float floorLevel) throws org.json.JSONException {
        org.json.JSONArray out = new org.json.JSONArray();
        for (Plane p : planes) {
            String kind = p.kind.equals("level") ? ((p.min[1] + p.max[1]) / 2 - floorLevel < 0.5f ? "floor" : "ceiling") : p.kind;
            out.put(new org.json.JSONObject()
                    .put("kind", kind)
                    .put("normal", new org.json.JSONArray().put(p.nx).put(p.ny).put(p.nz))
                    .put("d", p.d - p.ny * floorY)
                    .put("area", Math.round(p.area * 100) / 100.0)
                    .put("min", new org.json.JSONArray().put(p.min[0]).put(p.min[1] - floorY).put(p.min[2]))
                    .put("max", new org.json.JSONArray().put(p.max[0]).put(p.max[1] - floorY).put(p.max[2]))
                    .put("color", new org.json.JSONArray().put(Math.round(p.r)).put(Math.round(p.g)).put(Math.round(p.b))));
        }
        return out;
    }

    // ---- maths ----

    /** Jacobi eigen-decomposition of a symmetric 3×3: eigenvalues in val, eigenvectors in vec's columns. */
    static void eigen(double[][] m, double[] val, double[][] vec) {
        double[][] a = {m[0].clone(), m[1].clone(), m[2].clone()};
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) vec[i][j] = i == j ? 1 : 0;
        for (int sweep = 0; sweep < 30; sweep++) {
            double off = Math.abs(a[0][1]) + Math.abs(a[0][2]) + Math.abs(a[1][2]);
            if (off < 1e-15) break;
            for (int p = 0; p < 2; p++) for (int q = p + 1; q < 3; q++) {
                if (Math.abs(a[p][q]) < 1e-18) continue;
                double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                if (theta == 0) t = 1;
                double c = 1 / Math.sqrt(t * t + 1), s = t * c;
                for (int k = 0; k < 3; k++) {
                    double akp = a[k][p], akq = a[k][q];
                    a[k][p] = c * akp - s * akq; a[k][q] = s * akp + c * akq;
                }
                for (int k = 0; k < 3; k++) {
                    double apk = a[p][k], aqk = a[q][k];
                    a[p][k] = c * apk - s * aqk; a[q][k] = s * apk + c * aqk;
                }
                for (int k = 0; k < 3; k++) {
                    double vkp = vec[k][p], vkq = vec[k][q];
                    vec[k][p] = c * vkp - s * vkq; vec[k][q] = s * vkp + c * vkq;
                }
            }
        }
        for (int i = 0; i < 3; i++) val[i] = a[i][i];
    }

    /** sRGB 0..255 to CIE Lab (D65). */
    static float[] lab(float r, float g, float b) {
        double[] c = {r / 255.0, g / 255.0, b / 255.0};
        for (int i = 0; i < 3; i++) c[i] = c[i] <= 0.04045 ? c[i] / 12.92 : Math.pow((c[i] + 0.055) / 1.055, 2.4);
        double x = (0.4124 * c[0] + 0.3576 * c[1] + 0.1805 * c[2]) / 0.95047;
        double y = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
        double z = (0.0193 * c[0] + 0.1192 * c[1] + 0.9505 * c[2]) / 1.08883;
        double fx = f(x), fy = f(y), fz = f(z);
        return new float[]{(float) (116 * fy - 16), (float) (500 * (fx - fy)), (float) (200 * (fy - fz))};
    }

    private static double f(double t) { return t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16 / 116.0; }

    private static float dist(float[] a, float[] b) {
        float dl = a[0] - b[0], da = a[1] - b[1], db = a[2] - b[2];
        return (float) Math.sqrt(dl * dl + da * da + db * db);
    }
}
