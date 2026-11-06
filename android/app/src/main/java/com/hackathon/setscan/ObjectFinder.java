package com.hackathon.setscan;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits the scanned points into separate objects and gives each one a simple 3D shape (a box or a cylinder),
 * plus what it stands on: the floor, or another object (a can on a table).
 *
 * 1) Everything that isn't floor, wall or ceiling goes into a 3 cm voxel grid (in the room's own axes).
 * 2) Touching voxels form clusters.
 * 3) A cluster with a wide flat layer and only smaller things above it is a surface (table, desk, bed):
 *    it is split at that layer, and the things above become their own objects that sit on it.
 * 4) Each piece gets an oriented box (or a cylinder if its footprint is round), and small pieces floating
 *    in mid air with nothing under them are dropped as noise.
 */
final class ObjectFinder {

    static final float VS = 0.03f;
    private static final int OFF = 1 << 20;

    private static long key(int a, int b, int c) {
        return (((long) (a + OFF)) << 42) | (((long) (b + OFF)) << 21) | (long) (c + OFF);
    }

    /** One voxel cluster in room-aligned grid units (u along the layout's first axis, v across, y up). */
    private static final class Blob {
        final List<int[]> cells = new ArrayList<>();
        int u0 = Integer.MAX_VALUE, u1 = Integer.MIN_VALUE, v0 = Integer.MAX_VALUE, v1 = Integer.MIN_VALUE, y0 = Integer.MAX_VALUE, y1 = Integer.MIN_VALUE;
        void add(int[] c) {
            cells.add(c);
            u0 = Math.min(u0, c[0]); u1 = Math.max(u1, c[0]); y0 = Math.min(y0, c[1]); y1 = Math.max(y1, c[1]); v0 = Math.min(v0, c[2]); v1 = Math.max(v1, c[2]);
        }
    }

    /**
     * @param angleDeg the room layout's rotation (0 without a layout)
     * @param bounds   {uMin, uMax, vMin, vMax} of the room in layout axes, or null to keep everything
     */
    static List<RoomModel.Box> find(FloatBuffer pos, int count, float floorY, float topY, float angleDeg, float[] bounds, float roomArea) {
        float lc = (float) Math.cos(Math.toRadians(angleDeg)), ls = (float) Math.sin(Math.toRadians(angleDeg));
        float yMin = floorY + 0.025f, yMax = Math.min(topY - 0.2f, floorY + 2.4f);
        float margin = 0.12f; // wall surfaces themselves are not objects
        HashMap<Long, int[]> vox = new HashMap<>();
        for (int i = 0; i < count; i++) {
            float x = pos.get(i * 3), y = pos.get(i * 3 + 1), z = pos.get(i * 3 + 2);
            if (y < yMin || y > yMax) continue;
            float u = x * lc + z * ls, v = -x * ls + z * lc;
            if (bounds != null && (u < bounds[0] + margin || u > bounds[1] - margin || v < bounds[2] + margin || v > bounds[3] - margin)) continue;
            int iu = (int) Math.floor(u / VS), iy = (int) Math.floor((y - floorY) / VS), iv = (int) Math.floor(v / VS);
            long k = key(iu, iy, iv);
            int[] c = vox.get(k);
            if (c == null) vox.put(k, new int[]{iu, iy, iv, 1});
            else c[3]++;
        }
        // a voxel needs two points, so single stray specks don't glue clusters together
        HashMap<Long, int[]> solid = new HashMap<>();
        for (Map.Entry<Long, int[]> e : vox.entrySet()) if (e.getValue()[3] >= 2) solid.put(e.getKey(), e.getValue());

        List<Blob> blobs = cluster(solid);
        List<RoomModel.Box> out = new ArrayList<>();
        for (Blob b : blobs) {
            if (b.cells.size() < 10) continue;
            Blob[] split = splitSupport(b);
            if (split != null) {
                RoomModel.Box sup = shape(split[0], floorY, lc, ls, true);
                if (!keep(sup, split[0], floorY, roomArea)) continue;
                float sh = sup.y1 - sup.y0;
                sup.generic = sh < 0.6f ? "bed / low surface" : sh < 1.2f ? "table / desk" : "shelf / bunk";
                out.add(sup);
                HashMap<Long, int[]> above = new HashMap<>();
                for (int[] c : split[1].cells) above.put(key(c[0], c[1], c[2]), c);
                for (Blob item : cluster(above)) {
                    if (item.cells.size() < 5) continue;
                    RoomModel.Box it = shape(item, floorY, lc, ls, false);
                    it.parent = sup; it.depth = 1;
                    it.y0 = sup.y1; // it sits on the surface
                    if (it.y1 - it.y0 < 0.03f) continue;
                    it.generic = genericName(it);
                    out.add(it);
                }
            } else {
                RoomModel.Box o = shape(b, floorY, lc, ls, b.u1 - b.u0 > 20 || b.v1 - b.v0 > 20);
                if (!keep(o, b, floorY, roomArea)) continue;
                o.generic = genericName(o);
                out.add(o);
            }
        }
        // things that start above the floor rest on whatever is right under them; small floaters are noise
        List<RoomModel.Box> result = new ArrayList<>();
        for (RoomModel.Box o : out) {
            if (o.parent == null && o.y0 > floorY + 0.12f) {
                RoomModel.Box under = null; float best = 0.12f;
                for (RoomModel.Box p : out) {
                    if (p == o || p.y1 > o.y0 + 0.04f) continue;
                    if (p.hu * p.hv < 0.6f * o.hu * o.hv || p.voxels < 40) continue; // only something at least as big can hold it
                    float gap = o.y0 - p.y1;
                    if (gap < best && overlap(o, p)) { best = gap; under = p; }
                }
                if (under != null) { o.parent = under; o.depth = under.depth + 1; o.y0 = under.y1; o.generic = genericName(o); }
                else if (o.voxels < 150) continue; // floating in mid air with nothing under it: noise
                else o.rel = "raised (wall / bunk / shelf)";
            }
            if (o.parent == null && o.rel == null) { o.y0 = floorY; o.rel = "on the floor"; }
            result.add(o);
        }
        return result;
    }

    private static boolean overlap(RoomModel.Box a, RoomModel.Box b) {
        return a.x0 < b.x1 && b.x0 < a.x1 && a.z0 < b.z1 && b.z0 < a.z1;
    }

    private static boolean keep(RoomModel.Box o, Blob b, float floorY, float roomArea) {
        float w = 2 * o.hu, d = 2 * o.hv, h = o.y1 - o.y0;
        if (h < 0.05f && o.y0 < floorY + 0.06f) return false;              // a rug or floor noise
        if (Math.min(w, d) <= 0.2f && Math.max(w, d) > 1.2f && h > 1.2f) return false; // a strip of wall
        if (roomArea > 0 && w * d > 0.5f * roomArea) return false;          // the room itself
        return true;
    }

    /** 26-connected clusters of voxels. */
    private static List<Blob> cluster(HashMap<Long, int[]> cells) {
        List<Blob> blobs = new ArrayList<>();
        HashMap<Long, Boolean> seen = new HashMap<>();
        ArrayList<int[]> stack = new ArrayList<>();
        for (Map.Entry<Long, int[]> e : cells.entrySet()) {
            if (seen.containsKey(e.getKey())) continue;
            Blob b = new Blob();
            seen.put(e.getKey(), true);
            stack.add(e.getValue());
            while (!stack.isEmpty()) {
                int[] c = stack.remove(stack.size() - 1);
                b.add(c);
                for (int du = -1; du <= 1; du++)
                    for (int dy = -1; dy <= 1; dy++)
                        for (int dv = -1; dv <= 1; dv++) {
                            if (du == 0 && dy == 0 && dv == 0) continue;
                            long k = key(c[0] + du, c[1] + dy, c[2] + dv);
                            if (seen.containsKey(k)) continue;
                            int[] n = cells.get(k);
                            if (n == null) continue;
                            seen.put(k, true);
                            stack.add(n);
                        }
            }
            blobs.add(b);
        }
        return blobs;
    }

    /**
     * A table-like cluster: some layer at least 25 cm up is wide (the top), and whatever is above it is much
     * narrower. Returns {surface part, things on top}, or null.
     */
    private static Blob[] splitSupport(Blob b) {
        int ny = b.y1 - b.y0 + 1;
        if (ny < 4) return null;
        int[] area = new int[ny];
        // footprint area per layer (unique u,v cells)
        @SuppressWarnings("unchecked") java.util.HashSet<Long>[] fp = new java.util.HashSet[ny];
        for (int i = 0; i < ny; i++) fp[i] = new java.util.HashSet<>();
        for (int[] c : b.cells) fp[c[1] - b.y0].add(key(c[0], 0, c[2]));
        for (int i = 0; i < ny; i++) area[i] = fp[i].size();
        int minLayer = (int) (0.25f / VS);
        for (int L = ny - 2; L >= 0; L--) {
            if (b.y0 + L < minLayer) break;
            int top = Math.max(area[L], L + 1 < ny ? area[L + 1] : 0);
            if (top < 30) continue; // at least ~16x16 cm of flat top
            int aboveMax = 0, aboveCount = 0;
            for (int j = L + 2; j < ny; j++) { aboveMax = Math.max(aboveMax, area[j]); aboveCount += area[j]; }
            if (aboveCount < 3 || aboveMax > top * 0.5f) continue;
            Blob sup = new Blob(), items = new Blob();
            for (int[] c : b.cells) (c[1] - b.y0 <= L + 1 ? sup : items).add(c);
            if (items.cells.size() < 3) return null;
            return new Blob[]{sup, items};
        }
        return null;
    }

    /** Oriented box (or cylinder) around a cluster; big things are kept square to the room. */
    private static RoomModel.Box shape(Blob b, float floorY, float lc, float ls, boolean squareToRoom) {
        // footprint cells
        java.util.HashSet<Long> fp = new java.util.HashSet<>();
        double su = 0, sv = 0; int n = 0;
        for (int[] c : b.cells) if (fp.add(key(c[0], 0, c[2]))) { su += c[0] + 0.5; sv += c[2] + 0.5; n++; }
        double mu = su / n, mv = sv / n, cuu = 0, cvv = 0, cuv = 0;
        for (Long k : fp) {
            double u = ((k >>> 42) & 0x1FFFFF) - OFF + 0.5 - mu, v = (k & 0x1FFFFF) - OFF + 0.5 - mv;
            cuu += u * u; cvv += v * v; cuv += u * v;
        }
        double ang = squareToRoom ? 0 : 0.5 * Math.atan2(2 * cuv, cuu - cvv); // principal axis, in room axes
        double ca = Math.cos(ang), sa = Math.sin(ang);
        double a0 = 1e9, a1 = -1e9, b0 = 1e9, b1 = -1e9;
        for (Long k : fp) {
            double u = ((k >>> 42) & 0x1FFFFF) - OFF + 0.5 - mu, v = (k & 0x1FFFFF) - OFF + 0.5 - mv;
            double p = u * ca + v * sa, q = -u * sa + v * ca;
            a0 = Math.min(a0, p - 0.5); a1 = Math.max(a1, p + 0.5); b0 = Math.min(b0, q - 0.5); b1 = Math.max(b1, q + 0.5);
        }
        RoomModel.Box o = new RoomModel.Box();
        o.voxels = b.cells.size();
        o.hu = (float) ((a1 - a0) / 2 * VS); o.hv = (float) ((b1 - b0) / 2 * VS);
        double cu = (mu + (a0 + a1) / 2 * ca - (b0 + b1) / 2 * sa) * VS, cv = (mv + (a0 + a1) / 2 * sa + (b0 + b1) / 2 * ca) * VS;
        // back to world: x = u*lc - v*ls, z = u*ls + v*lc
        o.cx = (float) (cu * lc - cv * ls); o.cz = (float) (cu * ls + cv * lc);
        double wa = Math.atan2(ls, lc) + ang;
        o.cosA = (float) Math.cos(wa); o.sinA = (float) Math.sin(wa);
        o.y0 = floorY + b.y0 * VS; o.y1 = floorY + (b.y1 + 1) * VS;
        float aspect = o.hu / Math.max(o.hv, 1e-3f), fill = n * VS * VS / Math.max(1e-4f, 4 * o.hu * o.hv);
        o.cyl = !squareToRoom && Math.max(o.hu, o.hv) < 0.3f && Math.max(o.hu, o.hv) >= 0.045f && aspect > 0.75f && aspect < 1.33f && fill > 0.55f && fill < 0.9f;
        if (o.cyl) o.hu = o.hv = (o.hu + o.hv) / 2;
        o.updateAabb();
        return o;
    }

    private static String genericName(RoomModel.Box o) {
        float big = 2 * Math.max(o.hu, o.hv), h = o.y1 - o.y0;
        if (big < 0.4f) return o.cyl ? "round item" : "small item";
        if (o.depth > 0) return "object";
        if (h < 0.6f) return "low furniture";
        if (h < 1.2f) return "table-height furniture";
        return "tall furniture";
    }
}
