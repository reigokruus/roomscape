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
        float near = ds[m / 10];
        float ang = Math.max((det.x1 - det.x0) * s.radX, (det.y1 - det.y0) * s.radY);
        float extent = Math.max(0.12f, Math.min(2.0f, ang * near * 1.1f));
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
            if (d < Math.max(0.2f, 0.5f * Math.max(rb, Math.max(o.hu, o.hv))) && Math.abs(o.y0 - b.y0) < 0.3f) objs.remove(i);
        }
        objs.add(b);
        // things standing on another Claude object
        for (RoomModel.Box c : objs) {
            c.parent = null;
            for (RoomModel.Box p : objs)
                if (p != c && c.y0 > p.y0 + 0.1f && Math.abs(c.y0 - p.y1) < 0.12f && c.cx > p.x0 && c.cx < p.x1 && c.cz > p.z0 && c.cz < p.z1) { c.parent = p; c.depth = 1; }
        }
    }

    /** The scan's own boxes, minus those Claude's objects cover, plus Claude's objects. */
    synchronized void mergeInto(List<RoomModel.Box> boxes) {
        if (objs.isEmpty()) return;
        for (int i = boxes.size() - 1; i >= 0; i--) {
            RoomModel.Box f = boxes.get(i);
            float area = Math.max(1e-4f, (f.x1 - f.x0) * (f.z1 - f.z0)), cover = 0;
            boolean drop = false;
            for (RoomModel.Box c : objs) {
                if (f.cx > c.x0 - 0.1f && f.cx < c.x1 + 0.1f && f.cz > c.z0 - 0.1f && f.cz < c.z1 + 0.1f && f.y1 > c.y0 - 0.1f && f.y0 < c.y1 + 0.1f) { drop = true; break; }
                float ox = Math.min(f.x1, c.x1) - Math.max(f.x0, c.x0), oz = Math.min(f.z1, c.z1) - Math.max(f.z0, c.z0);
                if (ox > 0 && oz > 0) cover += ox * oz;
            }
            if (drop || cover > 0.3f * area) boxes.remove(i);
        }
        boxes.addAll(objs);
    }
}
