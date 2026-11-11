package com.hackathon.setscan;

/**
 * One plain wall filled in as a single rectangle (rk 11 Nov 21:40: "flat solid walls aren't usually for exact
 * measurements, so they can be fake filled ... fill in just the one wall that's causing problems, in one rectangle,
 * guessing its angle from the depth points it already has").
 *
 * From the scan's points ahead of the camera (where the plain-wall warning or a loss pointed): a vertical plane fitted
 * robustly (RANSAC, two points at a time, 4 cm), then the wall's own extent along it (where its points end: the corner,
 * the door frame, the opening edge), from the floor to the ceiling. Places seen through the wall (points well behind
 * it, an opening or a doorway) cut the rectangle: it keeps only the stretch around where the camera looked. Refuses
 * (null) when the plane is weak, too narrow or too wide, or isn't roughly facing the camera.
 *
 * The result is generated fill, marked as such: never written as scanned points; saved as filled-wall.json.
 * Pure Java: runs on the Mac too (tools/wall-guess).
 */
final class WallGuess {
    static final class Wall {
        float[] corners = new float[12];   // bottom-left, bottom-right, top-right, top-left (x, y, z)
        float nx, nz, d;                   // the plane: nx*x + nz*z = d (vertical), normal towards the camera
        float width, height, distance;     // m
        int support;                       // points on the plane
        float scannedShare;                // share of the rectangle (10 cm cells) with points
        int rgb;                           // flat colour: the wall's own points, median
        float lumSpread;                   // brightness spread of its points (0-255, 10th to 90th percentile): low = plain
        String why = "";
    }

    /**
     * pts: x, y, z per point (n), rgb per point (may be null); cam: camera -> world (column-major 4x4, looks along -z);
     * floorY, ceilY: heights (ceilY <= floorY + 1.8 means unknown: the plane's own top is used).
     */
    /** The scan's points, read in place (the app's point buffers, or arrays on the Mac). */
    interface Pts { float x(int i); float y(int i); float z(int i); int rgb(int i); }

    static Wall find(float[] pts, int[] rgb, int n, float[] cam, float floorY, float ceilY, StringBuilder why) {
        return find(new Pts() {
            public float x(int i) { return pts[i * 3]; } public float y(int i) { return pts[i * 3 + 1]; } public float z(int i) { return pts[i * 3 + 2]; }
            public int rgb(int i) { return rgb == null ? 0xB4B0A8 : rgb[i]; }
        }, n, cam, floorY, ceilY, why);
    }

    static Wall find(Pts P, int n, float[] cam, float floorY, float ceilY, StringBuilder why) {
        float px = cam[12], py = cam[13], pz = cam[14], fx = -cam[8], fy = -cam[9], fz = -cam[10];
        float fl = (float) Math.sqrt(fx * fx + fz * fz);
        if (fl < 0.3f) { why.append("looking straight up or down"); return null; }
        float hx = fx / fl, hz = fz / fl;
        // points ahead: within 35 deg of the view axis, 0.3-5 m, between knee and below the ceiling
        int step = Math.max(1, n / 600000), inCone = 0;
        for (int pass = 0; pass < 2; pass++) { if (pass == 1) break; for (int i = 0; i < n; i += step) { float vx = P.x(i) - px, vy = P.y(i) - py, vz = P.z(i) - pz; float dd = (float) Math.sqrt(vx * vx + vy * vy + vz * vz); if (dd >= 0.3f && dd <= 5f && (vx * fx + vy * fy + vz * fz) / dd >= 0.82f) inCone++; } }
        int cstep = step * Math.max(1, inCone / 60000);   // an even sample of the view, not the first 60k in scan order
        int m = 0; int[] idx = new int[Math.min(n, 60000)];
        for (int i = 0; i < n && m < idx.length; i += cstep) {
            float vx = P.x(i) - px, vy = P.y(i) - py, vz = P.z(i) - pz;
            float dd = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (dd < 0.3f || dd > 5f) continue;
            if ((vx * fx + vy * fy + vz * fz) / dd < 0.82f) continue;
            float h = P.y(i) - floorY; if (h < 0.3f || h > 2.4f) continue;
            idx[m++] = i;
        }
        if (m < 300) { why.append("only ").append(m).append(" points ahead"); return null; }
        java.util.Random rnd = new java.util.Random(7);
        int best = 0; float bnx = 0, bnz = 0, bd = 0;
        for (int it = 0; it < 300; it++) {
            int a = idx[rnd.nextInt(m)], b = idx[rnd.nextInt(m)];
            float dx = P.x(b) - P.x(a), dz = P.z(b) - P.z(a), L = (float) Math.sqrt(dx * dx + dz * dz);
            if (L < 0.25f) continue;
            float nx = -dz / L, nz = dx / L, d = nx * P.x(a) + nz * P.z(a);
            int c = 0;
            for (int q = 0; q < m; q += 2) { int i = idx[q]; if (Math.abs(nx * P.x(i) + nz * P.z(i) - d) < 0.04f) c++; }
            if (c > best) { best = c; bnx = nx; bnz = nz; bd = d; }
        }
        best *= 2;
        if (best < Math.max(300, m / 5)) { why.append("no clear wall ahead (").append(best).append(" of ").append(m).append(" points on one plane)"); return null; }
        if (bnx * px + bnz * pz - bd < 0) { bnx = -bnx; bnz = -bnz; bd = -bd; }   // normal towards the camera
        float facing = -(bnx * hx + bnz * hz);
        if (facing < 0.5f) { why.append("the plane ahead is seen too edge-on (").append(Math.round(Math.toDegrees(Math.acos(Math.min(1, facing))))).append(" deg)"); return null; }
        // refit on the inliers (least squares on the direction along the wall, still vertical)
        double sx = 0, sz = 0, sxx = 0, szz = 0, sxz = 0; int k = 0;
        for (int q = 0; q < m; q++) { int i = idx[q]; float x = P.x(i), z = P.z(i); if (Math.abs(bnx * x + bnz * z - bd) < 0.04f) { sx += x; sz += z; sxx += x * x; szz += z * z; sxz += x * z; k++; } }
        double mx = sx / k, mz = sz / k, cxx = sxx / k - mx * mx, czz = szz / k - mz * mz, cxz = sxz / k - mx * mz;
        double ang = 0.5 * Math.atan2(2 * cxz, cxx - czz);   // principal direction = along the wall
        float ux = (float) Math.cos(ang), uz = (float) Math.sin(ang);
        float nx = -uz, nz = ux; if (nx * bnx + nz * bnz < 0) { nx = -nx; nz = -nz; }
        float d = (float) (nx * mx + nz * mz);
        ux = nz; uz = -nx;   // along the wall
        // where the camera looks at the plane
        float den = nx * fx + nz * fz; if (Math.abs(den) < 1e-3f) { why.append("edge-on"); return null; }
        float t = (d - nx * px - nz * pz) / den; if (t <= 0) { why.append("the wall is behind"); return null; }
        float hitX = px + fx * t, hitZ = pz + fz * t;
        // along-wall profile, 10 cm bins within 5 m either side: points on the plane, and points seen through it
        int B = 100, half = 50; int[] on = new int[B], behind = new int[B]; float topOn = floorY;
        long rs = 0, gs = 0, bs = 0; int cs = 0; java.util.ArrayList<Integer> cols = new java.util.ArrayList<>();
        for (int i = 0; i < n; i += Math.max(1, step)) {
            float x = P.x(i), y = P.y(i), z = P.z(i);
            float off = nx * x + nz * z - d;   // + towards the camera
            float s = (x - hitX) * ux + (z - hitZ) * uz;
            int bin = (int) Math.floor(s / 0.1f) + half; if (bin < 0 || bin >= B) continue;
            float h = y - floorY; if (h < 0.1f || h > (ceilY > floorY + 1.8f ? ceilY - floorY - 0.1f : 2.6f)) continue;
            if (Math.abs(off) < 0.05f) { on[bin]++; if (y > topOn) topOn = y; if (h > 0.5f && h < 2.0f && cols.size() < 20000) cols.add(P.rgb(i)); }
            else if (off < -0.25f && h > 0.3f && h < 1.9f) behind[bin]++;   // seen through: an opening
        }
        // grow from the hit while the wall goes on: stop at an opening (seen through, nothing on the wall) or where the
        // wall's points end (fewer than 5 in a 10 cm column) for more than 0.4 m
        // The wall's middle may be empty (a plain wall seen from afar: rk 21:42, "a good look from afar"): an empty stretch
        // doesn't end it unless it runs over 1 m (open space, offline on the 21:30 scan); an opening ends it (seen through:
        // points well behind the plane). It reaches the outermost columns with wall points (>= 5) on each side, at most 5 m; it must have such columns within 1.5 m of where the
        // camera looks, so it never spans open space on nothing.
        int lo = half, hi = half; boolean nearL = on[half] >= 5, nearR = on[half] >= 5;
        for (int b = half - 1; b >= 0; b--) { if (behind[b] >= 3 && on[b] < 3) break; if (on[b] >= 5) { lo = b; if (half - b <= 15) nearL = true; } else if (lo - b > 10) break; }
        for (int b = half + 1; b < B; b++) { if (behind[b] >= 3 && on[b] < 3) break; if (on[b] >= 5) { hi = b; if (b - half <= 15) nearR = true; } else if (b - hi > 10) break; }
        if (!nearL && !nearR) { why.append("no wall points within 1.5 m of where the camera looks"); return null; }
        float s0 = (lo - half) * 0.1f, s1 = (hi - half + 1) * 0.1f, width = s1 - s0;
        if (width < 0.6f) { why.append("too narrow (").append(String.format(java.util.Locale.US, "%.2f", width)).append(" m)"); return null; }
        if (width > 7f) { why.append("too wide"); return null; }
        float top = ceilY > floorY + 1.8f ? ceilY : Math.max(floorY + 2.2f, topOn);
        Wall w = new Wall();
        w.nx = nx; w.nz = nz; w.d = d; w.width = width; w.height = top - floorY; w.distance = t; w.support = k;
        float[][] c = {{s0, floorY}, {s1, floorY}, {s1, top}, {s0, top}};
        for (int q = 0; q < 4; q++) { w.corners[q * 3] = hitX + ux * c[q][0]; w.corners[q * 3 + 1] = c[q][1]; w.corners[q * 3 + 2] = hitZ + uz * c[q][0]; }
        int filled = 0, cells = 0;
        for (int b = lo; b <= hi; b++) { cells++; if (on[b] > 20) filled++; }
        w.scannedShare = cells == 0 ? 0 : (float) filled / cells;
        if (!cols.isEmpty()) {
            cols.sort((p, q) -> Integer.compare(((p >> 16) & 255) + ((p >> 8) & 255) + (p & 255), ((q >> 16) & 255) + ((q >> 8) & 255) + (q & 255)));
            w.rgb = cols.get(cols.size() / 2);
            int a = cols.get(cols.size() / 10), b = cols.get(cols.size() * 9 / 10);
            w.lumSpread = (((b >> 16) & 255) + ((b >> 8) & 255) + (b & 255) - ((a >> 16) & 255) - ((a >> 8) & 255) - (a & 255)) / 3f;
        } else w.rgb = 0xB4B0A8;
        return w;
    }
}
