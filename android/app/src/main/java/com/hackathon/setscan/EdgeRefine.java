package com.hackathon.setscan;

import java.util.ArrayList;
import java.util.List;

/**
 * Puts a doorway's edges where the photos show them (rk 10 Nov: depth is right to ~1%, but the opening's edges came
 * out up to 12 cm off: voxels and depth can't place an edge, a photo can). The rough opening (on the wall plane:
 * left and right jamb along the wall, the lintel's height) is moved edge by edge, ±SEARCH in 1 cm steps; at each
 * position the 3D edge is projected into every photo that sees the doorway, and the image gradient across it is
 * summed. A door frame shows several parallel edges (the moulding's steps); the clear opening is the strong edge
 * nearest the opening's middle. Plain Java (photos as grey arrays), so it's tested on the Mac.
 */
final class EdgeRefine {
    static final float SEARCH = 0.15f, STEP = 0.01f;
    /** Tuning (offline tests): how much of the opening a photo must show (of 5 points), and the edge rule (0 = the
     *  strong edge nearest the middle, 1 = the strongest edge on the inner side of the rough one). */
    static int MIN_CORNERS = 5, RULE = 0;

    /** A photo: camera-to-world pose (OpenGL camera), intrinsics in the grey image's pixels (v down), grey levels. */
    static final class Photo { float[] pose, view; float fx, fy, cx, cy; int w, h; byte[] grey; }

    static final class Result { float u0, u1, top; float s0, s1, sTop; int photos; String why = ""; }

    /** Opening on the wall: origin (ox, oz) on the wall line, along-wall unit (ux, uz), floor height, jambs at u0 < u1,
     *  lintel at height top above the floor. Returns the refined edges (or the rough ones where no edge is clear). */
    static Result refine(List<Photo> all, float ox, float oz, float ux, float uz, float floorY, float u0, float u1, float top) {
        Result r = new Result(); r.u0 = u0; r.u1 = u1; r.top = top;
        // photos that see some of the opening (its middle or a corner) from 0.3-4.5 m; each edge then uses the samples
        // that fall inside each photo
        List<Photo> ph = new ArrayList<>();
        for (Photo p : all) {
            float[][] c = {{u0, 0.1f}, {u1, 0.1f}, {u1, top}, {u0, top}, {(u0 + u1) / 2, top / 2}};
            int in = 0;
            for (float[] q : c) { float[] s = proj(p, ox + ux * q[0], floorY + q[1], oz + uz * q[0]); if (s != null && s[0] >= 2 && s[1] >= 2 && s[0] <= p.w - 3 && s[1] <= p.h - 3) in++; }
            if (in >= MIN_CORNERS) ph.add(p);
        }
        r.photos = ph.size();
        if (ph.size() < 2) { r.why = "only " + ph.size() + " photos see the opening"; return r; }
        float mid = (u0 + u1) / 2;
        // jambs: vertical edges at u, from 0.3 m up to 0.85 of the height
        float[] sl = new float[1], sr = new float[1], st = new float[1];
        r.u0 = pick(ph, u0, mid, sl, u -> jambScore(ph, ox, oz, ux, uz, floorY, u, 0.3f, top * 0.85f));
        r.u1 = pick(ph, u1, mid, sr, u -> jambScore(ph, ox, oz, ux, uz, floorY, u, 0.3f, top * 0.85f));
        final float a = r.u0, b = r.u1;
        // lintel: a horizontal edge across the middle 70% of the opening
        r.top = pick(ph, top, top - 0.6f, st, h -> lintelScore(ph, ox, oz, ux, uz, floorY, a + (b - a) * 0.15f, b - (b - a) * 0.15f, h));
        r.s0 = sl[0]; r.s1 = sr[0]; r.sTop = st[0];
        return r;
    }

    interface Score { float at(float x); }

    /** Along x0 ± SEARCH: the local maxima of the edge score at least half the best; of those, the one nearest
     *  `inward` (the opening's middle, or below the lintel). */
    private static float pick(List<Photo> ph, float x0, float inward, float[] scoreOut, Score s) {
        int n = (int) (2 * SEARCH / STEP) + 1;
        float[] v = new float[n]; float best = 0;
        for (int i = 0; i < n; i++) { v[i] = s.at(x0 - SEARCH + i * STEP); best = Math.max(best, v[i]); }
        scoreOut[0] = best;
        if (best <= 0) return x0;
        float pickX = x0, pickD = Float.MAX_VALUE, pickV = 0;
        float dir = Math.signum(inward - x0);
        for (int i = 1; i < n - 1; i++) {
            if (v[i] < 0.5f * best || v[i] < v[i - 1] || v[i] < v[i + 1]) continue;
            float x = x0 - SEARCH + i * STEP, d = Math.abs(x - inward);
            if (RULE == 0) { if (d < pickD) { pickD = d; pickX = x; } }
            else if ((x - x0) * dir >= -0.03f && v[i] > pickV) { pickV = v[i]; pickX = x; }
        }
        return pickX;
    }

    /** Mean gradient across a vertical 3D edge (at u on the wall, heights y0..y1 above the floor) over the photos. */
    static float jambScore(List<Photo> ph, float ox, float oz, float ux, float uz, float floorY, float u, float y0, float y1) {
        float sum = 0; int n = 0;
        for (Photo p : ph) for (float y = y0; y <= y1; y += 0.05f) {
            float x = ox + ux * u, z = oz + uz * u;
            float[] a = proj(p, x, floorY + y, z), b = proj(p, x, floorY + y + 0.02f, z);
            if (a == null || b == null) continue;
            float g = across(p, a, b); if (g < 0) continue;
            sum += g; n++;
        }
        return n >= 8 ? sum / n : 0;
    }

    /** Mean gradient across the horizontal lintel edge at height h, between ua and ub along the wall. */
    static float lintelScore(List<Photo> ph, float ox, float oz, float ux, float uz, float floorY, float ua, float ub, float h) {
        float sum = 0; int n = 0;
        for (Photo p : ph) for (float u = ua; u <= ub; u += 0.04f) {
            float[] a = proj(p, ox + ux * u, floorY + h, oz + uz * u), b = proj(p, ox + ux * (u + 0.02f), floorY + h, oz + uz * (u + 0.02f));
            if (a == null || b == null) continue;
            float g = across(p, a, b); if (g < 0) continue;
            sum += g; n++;
        }
        return n >= 6 ? sum / n : 0;
    }

    /** The grey-level difference across the projected edge direction a->b, 2 px each side. */
    private static float across(Photo p, float[] a, float[] b) {
        float dx = b[0] - a[0], dy = b[1] - a[1], l = (float) Math.sqrt(dx * dx + dy * dy);
        if (l < 1e-3f) return 0;
        float nx = -dy / l, ny = dx / l;
        float g1 = grey(p, a[0] + nx * 2, a[1] + ny * 2), g2 = grey(p, a[0] - nx * 2, a[1] - ny * 2);
        if (g1 < 0 || g2 < 0) return -1;   // outside this photo
        return Math.abs(g1 - g2);
    }

    private static float grey(Photo p, float x, float y) {
        int ix = (int) x, iy = (int) y;
        if (ix < 0 || iy < 0 || ix >= p.w - 1 || iy >= p.h - 1) return -1;
        float fx = x - ix, fy = y - iy;
        int a = p.grey[iy * p.w + ix] & 255, b = p.grey[iy * p.w + ix + 1] & 255, c = p.grey[(iy + 1) * p.w + ix] & 255, d = p.grey[(iy + 1) * p.w + ix + 1] & 255;
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy;
    }

    /** World point to photo pixels, or null behind the camera. */
    static float[] proj(Photo p, float x, float y, float z) {
        float[] m = p.view;
        float cx = m[0] * x + m[4] * y + m[8] * z + m[12], cy = m[1] * x + m[5] * y + m[9] * z + m[13], cz = m[2] * x + m[6] * y + m[10] * z + m[14];
        float d = -cz; if (d < 0.3f || d > 4.5f) return null;
        return new float[]{p.fx * cx / d + p.cx, p.cy - p.fy * cy / d};
    }
}
