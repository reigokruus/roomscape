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
    }

    final List<RoomModel.Box> objs = new ArrayList<>();

    synchronized void clear() { objs.clear(); }

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
    synchronized void add(RoomModel.Box b) {
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
        }
        // things standing on another Claude object
        for (RoomModel.Box c : objs) {
            c.parent = null; c.depth = 0;
            for (RoomModel.Box p : objs)
                if (p != c && c.y0 > p.y0 + 0.1f && Math.abs(c.y0 - p.y1) < 0.15f && c.cx > p.x0 && c.cx < p.x1 && c.cz > p.z0 && c.cz < p.z1) { c.parent = p; c.depth = 1; }
        }
    }

    /** Once Claude has placed anything, only Claude's objects are shown: the scan's own guesses are dropped. */
    synchronized void mergeInto(List<RoomModel.Box> boxes, float floorY) {
        if (objs.isEmpty()) return;
        for (RoomModel.Box b : objs) if ("on the floor".equals(b.rel)) { float h = b.y1 - b.y0; b.y0 = floorY; b.y1 = floorY + h; } // the floor estimate improves as the scan grows
        boxes.clear();
        boxes.addAll(objs);
    }
}
