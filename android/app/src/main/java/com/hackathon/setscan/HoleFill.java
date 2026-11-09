package com.hackathon.setscan;

import java.util.ArrayList;
import java.util.List;

/**
 * A best guess for the surfaces a removed (or moved) object hid (rk 9 Nov): the floor under its footprint and the
 * wall right behind it. Each patch is a grid of coloured triangles just in front of the surface, coloured from a band
 * of real scan points around the hole, blended across it, so wall gradients and floorboards roughly carry on.
 *
 * Generated, never scanned: the patches aren't saved as points, aren't part of a catalogue copy, and are drawn only
 * while the object is away from its spot. The same rules as the iPhone app's (HoleFill.swift).
 *
 * The box: centre (cx, cz), its own axes u = (ux, uz) and v = (-uz, ux), half sizes hu, hv, bottom y0 and top y1.
 */
final class HoleFill {
    /** Grid step, margin around the hole, how far out the colour band reaches, and how close a wall must be. */
    static final float STEP = 0.03f, MARGIN = 0.05f, BAND = 0.15f, WALL_GAP = 0.15f;

    /** Triangles (3 floats a corner) and colours (3 bytes a corner). */
    static final class Mesh {
        float[] pos; byte[] col; int verts;
    }

    static Mesh build(float[] xyz, byte[] rgb, int colStride, int n, float cx, float cz, float ux, float uz,
                      float hu, float hv, float y0, float y1, float floorY) {
        List<float[]> tris = new ArrayList<>(); List<float[]> cols = new ArrayList<>();
        float vx = -uz, vz = ux;
        // Walls first (each side of the box with a wall just behind it): the floor's colour band must not reach up one.
        float[][] sides = {{ux, uz, hu, hv, vx, vz}, {-ux, -uz, hu, hv, vx, vz}, {vx, vz, hv, hu, ux, uz}, {-vx, -vz, hv, hu, ux, uz}};
        boolean[] walled = new boolean[4]; // +u, -u, +v, -v
        for (int i = 0; i < 4; i++) { float[] s = sides[i]; walled[i] = wallPatch(xyz, rgb, colStride, n, cx, cz, s[0], s[1], s[2], s[3], s[4], s[5], y0, y1, floorY, tris, cols); }
        // Floor: when the object stood on it.
        if (y0 - floorY < 0.12f) {
            floorPatch(xyz, rgb, colStride, n, cx, cz, ux, uz, vx, vz, hu + MARGIN, hv + MARGIN, floorY, walled, tris, cols);
        }
        Mesh m = new Mesh();
        m.verts = tris.size();
        m.pos = new float[m.verts * 3]; m.col = new byte[m.verts * 3];
        for (int i = 0; i < m.verts; i++) {
            System.arraycopy(tris.get(i), 0, m.pos, i * 3, 3);
            float[] c = cols.get(i);
            for (int k = 0; k < 3; k++) m.col[i * 3 + k] = (byte) Math.max(0, Math.min(255, Math.round(c[k])));
        }
        return m;
    }

    /** The floor under the box (a, b: half sizes with margin), coloured from the floor around it. */
    private static void floorPatch(float[] xyz, byte[] rgb, int cs, int n, float cx, float cz, float ux, float uz, float vx, float vz,
                                   float a, float b, float floorY, boolean[] walled, List<float[]> tris, List<float[]> cols) {
        // Colour profiles along each edge: for each step along u, the floor colour just beyond -v and +v; and the same
        // along v beyond -u and +u. A spot inside blends the two opposite edges by where it is.
        int nu = Math.max(2, (int) Math.ceil(2 * a / STEP) + 1), nv = Math.max(2, (int) Math.ceil(2 * b / STEP) + 1);
        Profile negV = new Profile(nu), posV = new Profile(nu), negU = new Profile(nv), posU = new Profile(nv);
        for (int i = 0; i < n; i++) {
            float y = xyz[i * 3 + 1];
            if (Math.abs(y - floorY) > 0.04f) continue;
            float dx = xyz[i * 3] - cx, dz = xyz[i * 3 + 2] - cz;
            float u = dx * ux + dz * uz, v = dx * vx + dz * vz;
            if (Math.abs(u) <= a && Math.abs(v) <= b) continue; // inside the hole
            if (Math.abs(u) > a + BAND || Math.abs(v) > b + BAND) continue;
            int iu = bin(u, -a, a, nu), iv = bin(v, -b, b, nv);
            if (Math.abs(u) <= a) { if (v < 0) negV.add(iu, rgb, i * cs); else posV.add(iu, rgb, i * cs); }
            if (Math.abs(v) <= b) { if (u < 0) negU.add(iv, rgb, i * cs); else posU.add(iv, rgb, i * cs); }
        }
        // A side with a wall right behind it: its band is mostly wall, so the other side stands in for it.
        if (walled[0]) posU = negU; else if (walled[1]) negU = posU;
        if (walled[2]) posV = negV; else if (walled[3]) negV = posV;
        float[] avg = average(negV, posV, negU, posU);
        if (avg == null) return; // nothing around it to copy from: leave the hole
        negV.fill(avg); posV.fill(avg); negU.fill(avg); posU.fill(avg);
        float[][] corner = new float[nu * nv][], colour = new float[nu * nv][];
        for (int j = 0; j < nv; j++) for (int i = 0; i < nu; i++) {
            float su = i / (float) (nu - 1), sv = j / (float) (nv - 1);
            float u = -a + 2 * a * su, v = -b + 2 * b * sv;
            corner[j * nu + i] = new float[]{cx + u * ux + v * vx, floorY + 0.004f, cz + u * uz + v * vz};
            float[] alongV = lerp(negV.c[i], posV.c[i], sv), alongU = lerp(negU.c[j], posU.c[j], su);
            colour[j * nu + i] = lerp(alongV, alongU, 0.5f);
        }
        grid(corner, colour, nu, nv, tris, cols);
    }

    /**
     * The wall behind one side of the box, when there is one: real points 0–15 cm beyond that side, mostly at one
     * distance (a flat surface), spread along the side. Filled from the floor to the object's top, coloured from the
     * wall to its left and right.
     */
    private static boolean wallPatch(float[] xyz, byte[] rgb, int cs, int n, float cx, float cz, float nx, float nz, float h, float w,
                                  float lx, float lz, float y0, float y1, float floorY, List<float[]> tris, List<float[]> cols) {
        float top = y1 + MARGIN, half = w + MARGIN;
        // Where the wall is: the distances of points just beyond the side, within its width and height.
        float[] gaps = new float[Math.min(n, 4000)]; int ng = 0, seen = 0;
        for (int i = 0; i < n; i++) {
            float dx = xyz[i * 3] - cx, dz = xyz[i * 3 + 2] - cz, y = xyz[i * 3 + 1];
            float t = dx * nx + dz * nz - h, l = dx * lx + dz * lz;
            if (t < 0 || t > WALL_GAP || Math.abs(l) > half || y < floorY + 0.05f || y > top) continue;
            seen++;
            if (ng < gaps.length) gaps[ng++] = t; else { int k = (int) (Math.random() * seen); if (k < ng) gaps[k] = t; }
        }
        if (ng < 120) return false;
        java.util.Arrays.sort(gaps, 0, ng);
        float t0 = gaps[ng / 2];
        int near = 0; for (int i = 0; i < ng; i++) if (Math.abs(gaps[i] - t0) < 0.04f) near++;
        if (near < 0.7f * ng) return false; // not one flat surface: leave it
        // Colour: the wall to the left and right of the hole, row by row.
        int nl = Math.max(2, (int) Math.ceil(2 * half / STEP) + 1), ny = Math.max(2, (int) Math.ceil((top - floorY) / STEP) + 1);
        Profile left = new Profile(ny), right = new Profile(ny), above = new Profile(nl);
        for (int i = 0; i < n; i++) {
            float dx = xyz[i * 3] - cx, dz = xyz[i * 3 + 2] - cz, y = xyz[i * 3 + 1];
            float t = dx * nx + dz * nz - h, l = dx * lx + dz * lz;
            if (Math.abs(t - t0) > 0.04f || y < floorY) continue;
            boolean inside = Math.abs(l) <= half && y <= top;
            if (inside || Math.abs(l) > half + BAND || y > top + BAND) continue;
            if (y <= top) { int iy = bin(y, floorY, top, ny); if (l < 0) left.add(iy, rgb, i * cs); else right.add(iy, rgb, i * cs); }
            else if (Math.abs(l) <= half) above.add(bin(l, -half, half, nl), rgb, i * cs);
        }
        float[] avg = average(left, right, above, null);
        if (avg == null) return true;
        left.fill(avg); right.fill(avg); above.fill(avg);
        float[][] corner = new float[nl * ny][], colour = new float[nl * ny][];
        float d = h + t0 - 0.006f; // just in front of the wall
        for (int j = 0; j < ny; j++) for (int i = 0; i < nl; i++) {
            float sl = i / (float) (nl - 1), sy = j / (float) (ny - 1);
            float l = -half + 2 * half * sl, y = floorY + (top - floorY) * sy;
            corner[j * nl + i] = new float[]{cx + nx * d + lx * l, y, cz + nz * d + lz * l};
            float[] row = lerp(left.c[j], right.c[j], sl);
            // Near the top, lean towards the wall above it.
            colour[j * nl + i] = above.count > 0 ? lerp(row, above.c[i], sy * sy * 0.5f) : row;
        }
        grid(corner, colour, nl, ny, tris, cols);
        return true;
    }

    /** Two triangles per grid cell. */
    private static void grid(float[][] corner, float[][] colour, int nu, int nv, List<float[]> tris, List<float[]> cols) {
        for (int j = 0; j + 1 < nv; j++) for (int i = 0; i + 1 < nu; i++) {
            int a = j * nu + i, b = a + 1, c = a + nu, d = c + 1;
            for (int k : new int[]{a, b, d, a, d, c}) { tris.add(corner[k]); cols.add(colour[k]); }
        }
    }

    /** Average colours in bins along an edge; empty bins take the nearest filled one. */
    private static final class Profile {
        final float[][] c; final int[] k; int count;
        Profile(int bins) { c = new float[bins][3]; k = new int[bins]; }
        void add(int b, byte[] rgb, int at) {
            c[b][0] += rgb[at] & 0xFF; c[b][1] += rgb[at + 1] & 0xFF; c[b][2] += rgb[at + 2] & 0xFF; k[b]++; count++;
        }
        void fill(float[] fallback) {
            for (int b = 0; b < k.length; b++) if (k[b] > 0) for (int x = 0; x < 3; x++) c[b][x] /= k[b];
            for (int b = 0; b < k.length; b++) {
                if (k[b] > 0) continue;
                int best = -1;
                for (int d = 1; d < k.length && best < 0; d++) {
                    if (b - d >= 0 && k[b - d] > 0) best = b - d; else if (b + d < k.length && k[b + d] > 0) best = b + d;
                }
                c[b] = best >= 0 ? c[best].clone() : fallback.clone();
            }
        }
    }

    private static float[] average(Profile... ps) {
        float[] s = new float[3]; int n = 0;
        for (Profile p : ps) {
            if (p == null) continue;
            for (int b = 0; b < p.k.length; b++) { s[0] += p.c[b][0]; s[1] += p.c[b][1]; s[2] += p.c[b][2]; n += p.k[b]; }
        }
        if (n < 20) return null;
        return new float[]{s[0] / n, s[1] / n, s[2] / n};
    }

    private static int bin(float x, float lo, float hi, int bins) {
        int b = Math.round((x - lo) / (hi - lo) * (bins - 1));
        return Math.max(0, Math.min(bins - 1, b));
    }

    private static float[] lerp(float[] a, float[] b, float t) {
        return new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }
}
