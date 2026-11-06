package com.hackathon.setscan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Objects Claude boxed in a live photo, lifted into 3D: the scan points that land inside the photo box and sit
 * at the front (nearest the camera) become an oriented box or cylinder. Kept across photos and merged by position.
 */
final class ClaudeObjects {

    /** Scan points projected into one upright photo (coordinates 0..1), with their depth and world position. */
    static final class Snapshot {
        float[] u, v, d, x, y, z;
        int n;
        float radX, radY; // radians per unit of upright photo width / height
        float floorY;
    }

    final List<RoomModel.Box> objs = new ArrayList<>();

    synchronized void clear() { objs.clear(); }

    synchronized boolean has() { return !objs.isEmpty(); }

    synchronized List<RoomModel.Box> list() { return new ArrayList<>(objs); }

    private static final java.util.Set<String> ROUND = new java.util.HashSet<>(Arrays.asList("bottle", "can", "cup", "vase", "lamp", "plant", "pole"));

    /** The 3D box for one detection, or null when too few scan points back it up. */
    static RoomModel.Box lift(Snapshot s, ClaudeHelper.Det det) {
        float mu = (det.x1 - det.x0) * 0.12f, mv = (det.y1 - det.y0) * 0.12f;
        float u0 = det.x0 + mu, u1 = det.x1 - mu, v0 = det.y0 + mv, v1 = det.y1 - mv;
        int[] in = new int[s.n]; int m = 0;
        for (int i = 0; i < s.n; i++)
            if (s.u[i] >= u0 && s.u[i] <= u1 && s.v[i] >= v0 && s.v[i] <= v1 && s.y[i] > s.floorY + 0.025f) in[m++] = i;
        if (m < 25) return null;
        float[] ds = new float[m];
        for (int i = 0; i < m; i++) ds[i] = s.d[in[i]];
        Arrays.sort(ds);
        float ang = Math.max((det.x1 - det.x0) * s.radX, (det.y1 - det.y0) * s.radY);
        float extent = Math.max(0.12f, Math.min(2.0f, ang * ds[m / 2] * 1.1f));
        // the depth slab holding the most points is the object; a chair leg in front or the wall behind holds fewer
        int best = 0, bestN = 0;
        for (int i = 0, j = 0; i < m; i++) {
            while (j < m && ds[j] <= ds[i] + extent) j++;
            if (j - i > bestN * 1.15f) { bestN = j - i; best = i; } // ties go to the nearer slab
        }
        float near = ds[best] + 0.05f;
        int k = 0;
        float mx = 0, mz = 0;
        for (int i = 0; i < m; i++) {
            float d = s.d[in[i]];
            if (d >= near - 0.05f && d <= near + extent) { in[k++] = in[i]; mx += s.x[in[i]]; mz += s.z[in[i]]; }
        }
        if (k < 20) return null;
        mx /= k; mz /= k;
        double sxx = 0, szz = 0, sxz = 0;
        for (int i = 0; i < k; i++) { float dx = s.x[in[i]] - mx, dz = s.z[in[i]] - mz; sxx += dx * dx; szz += dz * dz; sxz += dx * dz; }
        float a = (float) (0.5 * Math.atan2(2 * sxz, sxx - szz));
        float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
        float[] pu = new float[k], pv = new float[k], py = new float[k];
        for (int i = 0; i < k; i++) {
            float dx = s.x[in[i]] - mx, dz = s.z[in[i]] - mz;
            pu[i] = dx * ca + dz * sa; pv[i] = -dx * sa + dz * ca; py[i] = s.y[in[i]];
        }
        Arrays.sort(pu); Arrays.sort(pv); Arrays.sort(py);
        float ulo = pu[k * 5 / 100], uhi = pu[k * 95 / 100], vlo = pv[k * 5 / 100], vhi = pv[k * 95 / 100];
        RoomModel.Box b = new RoomModel.Box();
        float cu = (ulo + uhi) / 2, cv = (vlo + vhi) / 2;
        b.cx = mx + cu * ca - cv * sa; b.cz = mz + cu * sa + cv * ca;
        b.cosA = ca; b.sinA = sa;
        b.hu = Math.max(0.03f, (uhi - ulo) / 2); b.hv = Math.max(0.03f, (vhi - vlo) / 2);
        b.y0 = py[k * 3 / 100]; b.y1 = py[Math.min(k - 1, k * 97 / 100)];
        if (b.y0 - s.floorY < 0.12f) b.y0 = s.floorY;
        if (b.y1 - b.y0 < 0.04f) b.y1 = b.y0 + 0.04f;
        if (ROUND.contains(det.kind)) { b.cyl = true; b.hu = b.hv = Math.max(b.hu, b.hv); }
        b.name = det.name; b.kind = det.kind; b.generic = det.name; b.voxels = k;
        b.rel = b.y0 <= s.floorY + 0.001f ? "on the floor" : "raised";
        b.updateAabb();
        return b;
    }

    /** Keep the newest box for each spot. */
    synchronized void add(RoomModel.Box b) {
        float rb = Math.max(b.hu, b.hv);
        for (int i = objs.size() - 1; i >= 0; i--) {
            RoomModel.Box o = objs.get(i);
            float d = (float) Math.hypot(o.cx - b.cx, o.cz - b.cz);
            float ro = Math.max(o.hu, o.hv);
            boolean same = sameKind(o, b) && d < Math.max(0.4f, 0.9f * Math.max(rb, ro)) && Math.abs(o.y0 - b.y0) < 0.4f;
            float ox = Math.min(o.x1, b.x1) - Math.max(o.x0, b.x0), oz = Math.min(o.z1, b.z1) - Math.max(o.z0, b.z0);
            boolean overlap = ox > 0 && oz > 0 && ox * oz > 0.5f * Math.min((o.x1 - o.x0) * (o.z1 - o.z0), (b.x1 - b.x0) * (b.z1 - b.z0))
                    && Math.min(o.y1, b.y1) - Math.max(o.y0, b.y0) > 0.5f * Math.min(o.y1 - o.y0, b.y1 - b.y0);
            if (same || overlap || (d < Math.max(0.2f, 0.5f * Math.max(rb, ro)) && Math.abs(o.y0 - b.y0) < 0.3f)) objs.remove(i);
        }
        objs.add(b);
        // things standing on another Claude object
        for (RoomModel.Box c : objs) {
            c.parent = null;
            for (RoomModel.Box p : objs)
                if (p != c && c.y0 > p.y0 + 0.1f && Math.abs(c.y0 - p.y1) < 0.12f && c.cx > p.x0 && c.cx < p.x1 && c.cz > p.z0 && c.cz < p.z1) { c.parent = p; c.depth = 1; }
        }
    }

    private static boolean sameKind(RoomModel.Box a, RoomModel.Box b) {
        if (a.kind != null && a.kind.equals(b.kind) && !a.kind.equals("other")) return true;
        return a.name != null && b.name != null && a.name.equalsIgnoreCase(b.name);
    }

    /** Once Claude has placed anything, only Claude's objects are shown: the scan's own guesses are dropped. */
    synchronized void mergeInto(List<RoomModel.Box> boxes) {
        if (objs.isEmpty()) return;
        boxes.clear();
        boxes.addAll(objs);
    }
}
