package com.hackathon.setscan;

import java.util.ArrayList;
import java.util.List;

/**
 * Fills the walls out to the room's outline (rk 10 Nov: blank walls and white doors are where this phone's depth gives
 * up, and the scan then has holes exactly there). TEST ONLY, off in the builds (ENABLED) until rk approves pictures.
 *
 * Each outline wall (corner to corner, floor to ceiling) is laid out on a 5 cm grid. A cell the scan's surface already
 * covers stays as it is. An empty cell is filled flat on the wall only when the photos agree it is wall:
 *  - no photo's depth saw through it (depth further than the wall by SEE_THROUGH: an open doorway, a window);
 *  - where a photo saw it without depth, its colour matches the wall's (a doorway into a dark room returns no depth,
 *    but it is much darker or a different colour than the wall);
 *  - at least one photo looked at it (never fill what nobody saw).
 * The fill is drawn only; the scan's points are not changed.
 */
final class OutlineFill {
    static boolean ENABLED = false;
    static final float CELL = 0.05f, NEAR = 0.07f, SEE_THROUGH = 0.15f, COLOUR_DIFF = 45f;

    /** A photo: camera-to-world (OpenGL camera: -z forward, y up), intrinsics for (w, h) pixels with v down, its
     *  low-res depth (mm, 0 = none) with its own intrinsics, and its pixels (ARGB) for the colour check. */
    static final class Photo {
        float[] pose; float fx, fy, cx, cy; int w, h; int[] argb;
        short[] depth; int dw, dh; float dfx, dfy, dcx, dcy;
    }

    static final class Out {
        float[] pos = new float[0]; byte[] col = new byte[0]; int verts;
        float filled, keptSeen, keptColour, keptUnseen;   // m²
    }

    /** walls: x0, z0, x1, z1 per wall; floorY, ceilingY; mesh: triangle soup. */
    static Out fill(float[] walls, float floorY, float ceilY, float[] mesh, int verts, List<Photo> photos) {
        ArrayList<float[]> tris = new ArrayList<>(); ArrayList<byte[]> cols = new ArrayList<>();
        Out out = new Out();
        float[][] views = new float[photos.size()][];
        for (int i = 0; i < photos.size(); i++) views[i] = invert(photos.get(i).pose);
        for (int w = 0; w + 3 < walls.length; w += 4) {
            float x0 = walls[w], z0 = walls[w + 1], x1 = walls[w + 2], z1 = walls[w + 3];
            float len = (float) Math.hypot(x1 - x0, z1 - z0); if (len < 0.3f) continue;
            float ux = (x1 - x0) / len, uz = (z1 - z0) / len, nx = -uz, nz = ux;
            int gw = (int) (len / CELL), gh = (int) ((ceilY - floorY) / CELL);
            if (gw < 2 || gh < 2) continue;
            boolean[] cov = new boolean[gw * gh];
            for (int i = 0; i < verts; i++) {
                float px = mesh[i * 3] - x0, py = mesh[i * 3 + 1] - floorY, pz = mesh[i * 3 + 2] - z0;
                if (Math.abs(px * nx + pz * nz) > NEAR) continue;
                int gu = (int) ((px * ux + pz * uz) / CELL), gv = (int) (py / CELL);
                for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {
                    int cu = gu + a, cv = gv + b;
                    if (cu >= 0 && cv >= 0 && cu < gw && cv < gh) cov[cv * gw + cu] = true;
                }
            }
            // The wall's colour: the median of what the photos show at covered cells (sampled).
            ArrayList<int[]> wallCols = new ArrayList<>();
            for (int c = 0; c < gw * gh && wallCols.size() < 400; c += 7) if (cov[c]) {
                int[] rgb = colourAt(photos, views, cellCentre(c, gw, x0, z0, ux, uz, floorY));
                if (rgb != null) wallCols.add(rgb);
            }
            int[] wallRgb = median(wallCols);
            for (int c = 0; c < gw * gh; c++) {
                if (cov[c]) continue;
                float[] p = cellCentre(c, gw, x0, z0, ux, uz, floorY);
                int verdict = judge(photos, views, p, wallRgb);
                float a = CELL * CELL;
                if (verdict == 1) out.keptSeen += a; else if (verdict == 2) out.keptColour += a; else if (verdict == 3) out.keptUnseen += a;
                if (verdict != 0) continue;
                out.filled += a;
                float u0 = (c % gw) * CELL, v0 = (c / gw) * CELL;
                float[][] q = new float[4][];
                for (int k = 0; k < 4; k++) {
                    float uu = u0 + ((k & 1) == 0 ? 0 : CELL), vv = v0 + ((k & 2) == 0 ? 0 : CELL);
                    q[k] = new float[]{x0 + ux * uu, floorY + vv, z0 + uz * uu};
                }
                tris.add(new float[]{q[0][0], q[0][1], q[0][2], q[1][0], q[1][1], q[1][2], q[3][0], q[3][1], q[3][2]});
                tris.add(new float[]{q[0][0], q[0][1], q[0][2], q[3][0], q[3][1], q[3][2], q[2][0], q[2][1], q[2][2]});
                byte r = (byte) (wallRgb == null ? 200 : wallRgb[0]), g = (byte) (wallRgb == null ? 200 : wallRgb[1]), b = (byte) (wallRgb == null ? 200 : wallRgb[2]);
                byte[] cc = {r, g, b, (byte) 255, r, g, b, (byte) 255, r, g, b, (byte) 255};
                cols.add(cc); cols.add(cc);
            }
        }
        out.verts = tris.size() * 3;
        out.pos = new float[out.verts * 3]; out.col = new byte[out.verts * 4];
        for (int t = 0; t < tris.size(); t++) { System.arraycopy(tris.get(t), 0, out.pos, t * 9, 9); System.arraycopy(cols.get(t), 0, out.col, t * 12, 12); }
        return out;
    }

    private static float[] cellCentre(int c, int gw, float x0, float z0, float ux, float uz, float floorY) {
        float u = (c % gw + 0.5f) * CELL, v = (c / gw + 0.5f) * CELL;
        return new float[]{x0 + ux * u, floorY + v, z0 + uz * u};
    }

    /** 0 fill, 1 seen through, 2 colour differs from the wall, 3 no photo saw it. */
    static int judge(List<Photo> photos, float[][] views, float[] p, int[] wallRgb) {
        int looked = 0, differ = 0, agree = 0;
        for (int i = 0; i < photos.size(); i++) {
            Photo ph = photos.get(i); float[] v = views[i];
            float cx = v[0] * p[0] + v[4] * p[1] + v[8] * p[2] + v[12], cy = v[1] * p[0] + v[5] * p[1] + v[9] * p[2] + v[13], cz = v[2] * p[0] + v[6] * p[1] + v[10] * p[2] + v[14];
            float d = -cz; if (d < 0.2f || d > 4f) continue;
            float pu = ph.cx + ph.fx * cx / d, pv = ph.cy - ph.fy * cy / d;
            if (pu < 0 || pv < 0 || pu >= ph.w || pv >= ph.h) continue;
            looked++;
            // depth at this spot in the photo's own depth image
            if (ph.depth != null) {
                float du = ph.dcx + ph.dfx * cx / d, dv = ph.dcy - ph.dfy * cy / d;
                int iu = (int) du, iv = (int) dv;
                if (iu >= 0 && iv >= 0 && iu < ph.dw && iv < ph.dh) {
                    int mm = ph.depth[iv * ph.dw + iu] & 0xFFFF;
                    if (mm > 0 && mm * 0.001f > d + SEE_THROUGH) return 1;       // depth went past the wall: an opening
                    if (mm > 0 && Math.abs(mm * 0.001f - d) < 0.1f) { agree++; continue; }   // depth agrees with the wall
                }
            }
            if (ph.argb != null && wallRgb != null) {
                int x = Math.min(ph.w - 1, (int) (pu * 1)), y = Math.min(ph.h - 1, (int) pv);
                int px = ph.argb[y * ph.w + x];
                int r = (px >> 16) & 255, g = (px >> 8) & 255, b = px & 255;
                float diff = Math.abs(r - wallRgb[0]) + Math.abs(g - wallRgb[1]) + Math.abs(b - wallRgb[2]);
                if (diff > COLOUR_DIFF * 3) differ++; else agree++;
            }
        }
        if (looked == 0) return 3;
        if (differ > agree) return 2;
        return 0;
    }

    static int[] colourAt(List<Photo> photos, float[][] views, float[] p) {
        for (int i = 0; i < photos.size(); i++) {
            Photo ph = photos.get(i); if (ph.argb == null) continue; float[] v = views[i];
            float cx = v[0] * p[0] + v[4] * p[1] + v[8] * p[2] + v[12], cy = v[1] * p[0] + v[5] * p[1] + v[9] * p[2] + v[13], cz = v[2] * p[0] + v[6] * p[1] + v[10] * p[2] + v[14];
            float d = -cz; if (d < 0.2f || d > 4f) continue;
            int x = (int) (ph.cx + ph.fx * cx / d), y = (int) (ph.cy - ph.fy * cy / d);
            if (x < 0 || y < 0 || x >= ph.w || y >= ph.h) continue;
            int px = ph.argb[y * ph.w + x];
            return new int[]{(px >> 16) & 255, (px >> 8) & 255, px & 255};
        }
        return null;
    }

    static int[] median(List<int[]> c) {
        if (c.isEmpty()) return null;
        int[] out = new int[3];
        for (int k = 0; k < 3; k++) { int[] v = new int[c.size()]; for (int i = 0; i < v.length; i++) v[i] = c.get(i)[k]; java.util.Arrays.sort(v); out[k] = v[v.length / 2]; }
        return out;
    }

    /** Inverse of a rigid column-major 4x4. */
    static float[] invert(float[] m) {
        float[] r = new float[16];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) r[j * 4 + i] = m[i * 4 + j];
        for (int i = 0; i < 3; i++) r[12 + i] = -(r[i] * m[12] + r[4 + i] * m[13] + r[8 + i] * m[14]);
        r[15] = 1;
        return r;
    }
}
