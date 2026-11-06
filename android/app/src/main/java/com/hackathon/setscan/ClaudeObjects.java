package com.hackathon.setscan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Objects Claude boxed in a live photo, placed in 3D. The phone's depth is too noisy to measure small things
 * (a pair of sneakers barely rises out of the floor noise), so the scan only says WHERE: the middle of Claude's photo
 * box is looked up in the scan points. Claude says WHAT and HOW BIG (its real-world size estimate).
 * Repeat sightings of the same thing are averaged, so the position settles as you scan.
 */
final class ClaudeObjects {

    /** Scan points projected into one upright photo (coordinates 0..1), with their depth and world position. */
    static final class Snapshot {
        float[] u, v, d, x, y, z;
        int n;
        float radX, radY; // radians per unit of upright photo width / height
        float floorY;
        float camX, camZ;
        float[] view; float fx, fy, cx, cy, w, h; // the photo's camera (texture pixels), for carving
        float[] inv = new float[6]; float iw, ih; // upright photo pixels -> texture 0..1 (affine a b c / d e f)
    }

    /** One photo where Claude boxed this object: the camera and the box in that camera's image (0..1). */
    static final class Sighting {
        float[] view; float fx, fy, cx, cy, w, h, u0, v0, u1, v1, fx3, fy3, fz3;
        String toJson() {
            StringBuilder b = new StringBuilder("{\"box\":[");
            b.append(u0).append(',').append(v0).append(',').append(u1).append(',').append(v1).append("],\"view\":[");
            for (int i = 0; i < 16; i++) b.append(i > 0 ? "," : "").append(view[i]);
            return b.append("],\"fx\":").append(fx).append(",\"fy\":").append(fy).append(",\"cx\":").append(cx).append(",\"cy\":").append(cy)
                    .append(",\"w\":").append(w).append(",\"h\":").append(h).append('}').toString();
        }
    }

    /** Every view of each object (by identity), used to carve its real size from all angles. */
    private final java.util.IdentityHashMap<RoomModel.Box, List<Sighting>> views = new java.util.IdentityHashMap<>();
    /** Claude's own size guess per object, used until enough views agree. */
    private final java.util.IdentityHashMap<RoomModel.Box, float[]> guess = new java.util.IdentityHashMap<>();
    /** How many objects have a carved (measured) shape. */
    volatile int carved;
    private float floorY;

    static Sighting sighting(Snapshot s, ClaudeHelper.Det det) {
        Sighting g = new Sighting();
        g.view = s.view; g.fx = s.fx; g.fy = s.fy; g.cx = s.cx; g.cy = s.cy; g.w = s.w; g.h = s.h;
        float[] us = {det.x0, det.x1, det.x0, det.x1}, vs = {det.y0, det.y0, det.y1, det.y1};
        g.u0 = g.v0 = 9; g.u1 = g.v1 = -9;
        for (int i = 0; i < 4; i++) {
            float px = us[i] * s.iw, py = vs[i] * s.ih;
            float u = s.inv[0] * px + s.inv[1] * py + s.inv[2], v = s.inv[3] * px + s.inv[4] * py + s.inv[5];
            g.u0 = Math.min(g.u0, u); g.u1 = Math.max(g.u1, u); g.v0 = Math.min(g.v0, v); g.v1 = Math.max(g.v1, v);
        }
        g.fx3 = -s.view[2]; g.fy3 = -s.view[6]; g.fz3 = -s.view[10]; // camera forward in the world
        return g;
    }

    final List<RoomModel.Box> objs = new ArrayList<>();

    synchronized void clear() { objs.clear(); views.clear(); guess.clear(); carved = 0; }

    synchronized boolean has() { return !objs.isEmpty(); }

    synchronized List<RoomModel.Box> list() { return new ArrayList<>(objs); }

    private static final java.util.Set<String> ROUND = new java.util.HashSet<>(Arrays.asList("bottle", "can", "cup", "vase", "lamp", "plant", "pole"));
    private static final java.util.Set<String> FLOOR = new java.util.HashSet<>(Arrays.asList("table", "desk", "chair", "stool", "bed", "sofa", "shelf", "cabinet", "wardrobe", "pole"));

    private static float median(float[] a, int n) { float[] c = Arrays.copyOf(a, n); Arrays.sort(c); return c[n / 2]; }

    /** The 3D box for one detection, or null when the scan has no points where Claude points. */
    static RoomModel.Box lift(Snapshot s, ClaudeHelper.Det det) {
        float cu = (det.x0 + det.x1) / 2, cv = (det.y0 + det.y1) / 2, hw = (det.x1 - det.x0) / 2, hh = (det.y1 - det.y0) / 2;
        float[] px = new float[s.n], py = new float[s.n], pz = new float[s.n], pd = new float[s.n];
        int m = 0;
        for (float f : new float[]{0.3f, 0.5f}) { // the middle of the box first; a wider look if that has too few points
            m = 0;
            for (int i = 0; i < s.n; i++)
                if (Math.abs(s.u[i] - cu) < hw * f && Math.abs(s.v[i] - cv) < hh * f) { px[m] = s.x[i]; py[m] = s.y[i]; pz[m] = s.z[i]; pd[m] = s.d[i]; m++; }
            if (m >= 6) break;
        }
        if (m < 6) return null;
        float ax = median(px, m), ay = median(py, m), az = median(pz, m), dist = median(pd, m);
        // size: Claude's estimate, or what the photo box spans at that distance
        float appW = 2 * hw * s.radX * dist, appH = 2 * hh * s.radY * dist;
        float w = det.sw > 0 ? det.sw : Math.max(0.05f, appW), dp = det.sd > 0 ? det.sd : w * 0.6f, h = det.sh > 0 ? det.sh : Math.max(0.05f, appH);
        // the photo shows the front: the middle of the object is half its depth further from the camera
        float dx = ax - s.camX, dz = az - s.camZ, dl = (float) Math.hypot(dx, dz);
        if (dl < 1e-3f) { dx = 0; dz = 1; dl = 1; }
        dx /= dl; dz /= dl;
        RoomModel.Box b = new RoomModel.Box();
        b.cx = ax + dx * dp / 2; b.cz = az + dz * dp / 2;
        b.cosA = -dz; b.sinA = dx; // width runs across the view, depth along it
        b.hu = w / 2; b.hv = dp / 2;
        boolean onFloor = FLOOR.contains(det.kind) || det.on.equals("floor") || ay - s.floorY < Math.max(0.25f, h * 0.8f);
        b.y0 = onFloor ? s.floorY : Math.max(s.floorY, ay - h / 2);
        b.y1 = b.y0 + h;
        if (ROUND.contains(det.kind)) { b.cyl = true; b.hu = b.hv = Math.max(w, dp) / 2; }
        b.name = det.name; b.kind = det.kind; b.generic = det.name; b.voxels = 1;
        b.rel = onFloor ? "on the floor" : !det.on.isEmpty() && !det.on.equals("floor") ? "on the " + det.on : "raised";
        b.updateAabb();
        return b;
    }

    private static boolean sameThing(RoomModel.Box a, RoomModel.Box b) {
        if (a.kind != null && a.kind.equals(b.kind) && !a.kind.equals("other")) return true;
        return a.name != null && b.name != null && a.name.equalsIgnoreCase(b.name);
    }

    /** Average a repeat sighting into the earlier one; anything else is a new object. */
    synchronized void add(RoomModel.Box b, Sighting sg, float floorY) {
        this.floorY = floorY;
        float rb = Math.max(b.hu, b.hv);
        RoomModel.Box hit = null; float hd = 1e9f;
        for (RoomModel.Box o : objs) {
            float d = (float) Math.hypot(o.cx - b.cx, o.cz - b.cz);
            float lim = Math.max(0.35f, 1.2f * Math.max(rb, Math.max(o.hu, o.hv)));
            if (sameThing(o, b) && d < lim && Math.abs(o.y0 - b.y0) < 0.4f && d < hd) { hit = o; hd = d; }
        }
        if (hit == null) {
            for (int i = objs.size() - 1; i >= 0; i--) { // a different name on the very same spot: Claude changed its mind
                RoomModel.Box o = objs.get(i);
                if (Math.hypot(o.cx - b.cx, o.cz - b.cz) < 0.12f && Math.abs(o.y0 - b.y0) < 0.2f) objs.remove(i);
            }
            objs.add(b);
            views.put(b, new ArrayList<>(Arrays.asList(sg)));
            guess.put(b, new float[]{2 * b.hu, 2 * b.hv, b.y1 - b.y0, b.cx, b.cz});
        } else {
            float n = Math.min(hit.voxels, 6), t = 1f / (n + 1); // running average, newest counts at least 1/7
            hit.cx += (b.cx - hit.cx) * t; hit.cz += (b.cz - hit.cz) * t;
            hit.hu += (b.hu - hit.hu) * t; hit.hv += (b.hv - hit.hv) * t;
            float h = (hit.y1 - hit.y0) + ((b.y1 - b.y0) - (hit.y1 - hit.y0)) * t;
            hit.y0 += (b.y0 - hit.y0) * t; hit.y1 = hit.y0 + h;
            if (!hit.cyl) { // keep the heading of the best view: the first one unless this one is a lot closer to square-on
                float c = hit.cosA + (b.cosA - hit.cosA) * t, s = hit.sinA + (b.sinA - hit.sinA) * t, l = (float) Math.hypot(c, s);
                if (l > 0.3f) { hit.cosA = c / l; hit.sinA = s / l; }
            }
            hit.name = b.name; hit.generic = b.name;
            hit.voxels++;
            hit.updateAabb();
            List<Sighting> vs = views.get(hit);
            if (vs != null) { vs.add(sg); if (vs.size() > 12) vs.remove(1); } // keep the first view and the newest ones
            float[] gs = guess.get(hit);
            if (gs != null) { gs[0] += (2 * b.hu - gs[0]) * t; gs[1] += (2 * b.hv - gs[1]) * t; gs[2] += ((b.y1 - b.y0) - gs[2]) * t; gs[3] += (b.cx - gs[3]) * t; gs[4] += (b.cz - gs[4]) * t; }
            carve(hit);
        }
        // things standing on another Claude object
        for (RoomModel.Box c : objs) {
            c.parent = null; c.depth = 0;
            for (RoomModel.Box p : objs)
                if (p != c && c.y0 > p.y0 + 0.1f && Math.abs(c.y0 - p.y1) < 0.15f && c.cx > p.x0 && c.cx < p.x1 && c.cz > p.z0 && c.cz < p.z1) { c.parent = p; c.depth = 1; }
        }
    }

    /**
     * Shape from moving around it: keep only the space that lands inside Claude's box in every photo that saw it
     * (the box's viewing frustum, intersected over all views). Needs views at least 25 degrees apart; the result
     * replaces the size guess. The scan's noisy depth isn't used at all here.
     */
    private void carve(RoomModel.Box b) {
        List<Sighting> vs = views.get(b);
        float[] gs = guess.get(b);
        if (vs == null || gs == null || vs.size() < 2) return;
        // how far apart the views are (horizontal angle) and whether any looks from the side (for the height)
        float spread = 0; boolean side = false;
        for (Sighting a : vs) {
            if (Math.abs(a.fy3) < 0.35f) side = true; // within ~20 degrees of level
            for (Sighting c : vs) {
                float ax = a.fx3, az = a.fz3, cx = c.fx3, cz = c.fz3, la = (float) Math.hypot(ax, az), lc = (float) Math.hypot(cx, cz);
                if (la < 0.2f || lc < 0.2f) continue;
                spread = Math.max(spread, (float) Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (ax * cx + az * cz) / (la * lc))))));
            }
        }
        if (spread < 25) return;
        float R = Math.max(0.2f, Math.min(1.2f, 1.5f * Math.max(gs[0], gs[1]) / 2 + 0.15f));
        int nxz = 32, ny = 40;
        // looking down from one side, height and depth trade off; without a level view, Claude's height caps it
        float top = side ? Math.min(2.2f, Math.max(0.3f, gs[2] * 2 + 0.2f)) : gs[2] * 1.1f + 0.02f;
        float vxz = 2 * R / nxz, vy = top / ny;
        float x0 = gs[3] - R, z0 = gs[4] - R;
        List<float[]> kept = new ArrayList<>();
        for (int iy = 0; iy < ny; iy++) for (int iz = 0; iz < nxz; iz++) for (int ix = 0; ix < nxz; ix++) {
            float x = x0 + (ix + 0.5f) * vxz, y = floorY + (iy + 0.5f) * vy, z = z0 + (iz + 0.5f) * vxz;
            int seen = 0; boolean out = false;
            for (Sighting g : vs) {
                float[] m = g.view;
                float cx = m[0] * x + m[4] * y + m[8] * z + m[12], cy = m[1] * x + m[5] * y + m[9] * z + m[13], cz = m[2] * x + m[6] * y + m[10] * z + m[14];
                float d = -cz;
                if (d < 0.1f) continue;
                float u = (g.fx * cx / d + g.cx) / g.w, v = (g.cy - g.fy * cy / d) / g.h;
                if (u < 0 || u > 1 || v < 0 || v > 1) continue; // outside this photo: it says nothing
                float pu = (g.u1 - g.u0) * 0.05f + 0.01f, pv = (g.v1 - g.v0) * 0.05f + 0.01f;
                if (u < g.u0 - pu || u > g.u1 + pu || v < g.v0 - pv || v > g.v1 + pv) { out = true; break; }
                seen++;
            }
            if (!out && seen >= 2) kept.add(new float[]{x, y, z});
        }
        if (kept.size() < 3) return; // the views disagree (drift, or two different things merged): keep the guess
        float mx = 0, mz = 0;
        for (float[] p : kept) { mx += p[0]; mz += p[2]; }
        mx /= kept.size(); mz /= kept.size();
        double sxx = 0, szz = 0, sxz = 0;
        for (float[] p : kept) { float dx = p[0] - mx, dz = p[2] - mz; sxx += dx * dx; szz += dz * dz; sxz += dx * dz; }
        float a = (float) (0.5 * Math.atan2(2 * sxz, sxx - szz)), ca = (float) Math.cos(a), sa = (float) Math.sin(a);
        float ulo = 1e9f, uhi = -1e9f, vlo = 1e9f, vhi = -1e9f, ylo = 1e9f, yhi = -1e9f;
        for (float[] p : kept) {
            float dx = p[0] - mx, dz = p[2] - mz, u = dx * ca + dz * sa, v = -dx * sa + dz * ca;
            ulo = Math.min(ulo, u); uhi = Math.max(uhi, u); vlo = Math.min(vlo, v); vhi = Math.max(vhi, v); ylo = Math.min(ylo, p[1]); yhi = Math.max(yhi, p[1]);
        }
        float cu = (ulo + uhi) / 2, cv = (vlo + vhi) / 2;
        float hu = (uhi - ulo + vxz) / 2, hv = (vhi - vlo + vxz) / 2;
        // a carved shape much bigger than Claude's guess means too few angles yet: don't let it balloon
        // the carved space is an upper bound (it always contains the object, plus some slack from coarse boxes):
        // Claude's size, held between 75% and 100% of what was carved
        float gBig = Math.max(gs[0], gs[1]) / 2, gSmall = Math.min(gs[0], gs[1]) / 2;
        boolean uBig = hu >= hv;
        float gu = uBig ? gBig : gSmall, gv = uBig ? gSmall : gBig;
        hu = Math.min(hu, Math.max(gu, 0.75f * hu)); hv = Math.min(hv, Math.max(gv, 0.75f * hv));
        b.cx = mx + cu * ca - cv * sa; b.cz = mz + cu * sa + cv * ca;
        b.cosA = ca; b.sinA = sa; b.hu = hu; b.hv = hv;
        if (b.cyl) b.hu = b.hv = Math.max(hu, hv);
        if (!"on the floor".equals(b.rel)) b.y0 = Math.max(floorY, ylo - vy / 2);
        if (side) b.y1 = Math.max(b.y0 + 0.04f, yhi + vy / 2);
        b.updateAabb();
        b.generic = "measured";
        carved = 0;
        for (RoomModel.Box o : objs) if ("measured".equals(o.generic)) carved++;
    }

    /** All objects with every view, for the export. */
    synchronized String toJson() {
        StringBuilder sb = new StringBuilder("{\"objects\":[\n");
        for (int i = 0; i < objs.size(); i++) {
            RoomModel.Box b = objs.get(i);
            sb.append(i > 0 ? ",\n" : "").append(String.format(java.util.Locale.US,
                    "{\"name\":%s,\"kind\":%s,\"centre\":[%.3f,%.3f,%.3f],\"size\":[%.3f,%.3f,%.3f],\"heading\":[%.3f,%.3f],\"round\":%b,\"measured\":%b,\"views\":[",
                    org.json.JSONObject.quote(b.name), org.json.JSONObject.quote(b.kind), b.cx, (b.y0 + b.y1) / 2, b.cz, 2 * b.hu, 2 * b.hv, b.y1 - b.y0, b.cosA, b.sinA, b.cyl, "measured".equals(b.generic)));
            List<Sighting> vs = views.get(b);
            if (vs != null) for (int j = 0; j < vs.size(); j++) sb.append(j > 0 ? "," : "").append(vs.get(j).toJson());
            sb.append("]}");
        }
        return sb.append("\n]}\n").toString();
    }

    /** Once Claude has placed anything, only Claude's objects are shown: the scan's own guesses are dropped. */
    synchronized void mergeInto(List<RoomModel.Box> boxes, float floorY) {
        if (objs.isEmpty()) return;
        for (RoomModel.Box b : objs) if ("on the floor".equals(b.rel)) { float h = b.y1 - b.y0; b.y0 = floorY; b.y1 = floorY + h; } // the floor estimate improves as the scan grows
        boxes.clear();
        boxes.addAll(objs);
    }
}
