package com.hackathon.setscan;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Objects in the room found with Claude's help (rk, 7 Nov: clean objects are the core feature).
 *
 * Claude only says "a chair is here" with a 2D box in a photo. The object's shape comes from the scan's own points:
 * every photo that saw it carves the points down to the ones inside its box (a visual hull), so the wall behind a
 * chair drops out because from another angle it is no longer behind the chair. One photo alone keeps the front
 * cluster of depths instead.
 */
final class RoomItems {
    /** One object: an upright box around its points, same fields as SceneObjects.Obj. */
    static final class Item {
        String name;
        float cx, cz, cosA = 1, sinA, hu, hv, y0, y1;
        boolean onFloor;
        int views, points;
    }

    final List<Item> list = new ArrayList<>();

    synchronized void clear() { list.clear(); }
    synchronized List<Item> snapshot() { return new ArrayList<>(list); }
    synchronized void set(List<Item> items) { list.clear(); list.addAll(items); }

    /** A sighting in one photo: the photo, its box in the photo's own 0..1 coordinates, and the name. */
    static final class View {
        PhotoRoom.Keyframe k;
        float u0, u1, v0, v1;
        String name;
        int[] cand; int nc;   // scan points inside the box (indices)
        float front;          // depth of the nearest surface in the box
        float sx, sy, sz;     // where it is roughly (middle of the front cluster)
    }

    /** Turn Claude's box (0..1 of the upright photo) into the photo's own 0..1 coordinates. */
    static View view(PhotoRoom.Keyframe k, android.graphics.Matrix map, int uw, int uh, ClaudeHelper.OutletDet d) {
        android.graphics.Matrix inv = new android.graphics.Matrix();
        if (!map.invert(inv)) return null;
        float[] c = {d.x0 * uw, d.y0 * uh, d.x1 * uw, d.y1 * uh};
        inv.mapPoints(c);
        View v = new View();
        v.k = k; v.name = d.type;
        v.u0 = Math.min(c[0], c[2]); v.u1 = Math.max(c[0], c[2]); v.v0 = Math.min(c[1], c[3]); v.v1 = Math.max(c[1], c[3]);
        return v;
    }

    /** {u, v, depth} of a world point in a photo, or null when behind the camera. */
    private static boolean project(PhotoRoom.Keyframe k, float x, float y, float z, float[] out) {
        float[] vm = k.view;
        float xc = vm[0] * x + vm[4] * y + vm[8] * z + vm[12], yc = vm[1] * x + vm[5] * y + vm[9] * z + vm[13], zc = vm[2] * x + vm[6] * y + vm[10] * z + vm[14];
        float d = -zc;
        if (d < 0.15f) return false;
        out[0] = (k.fx * xc / d + k.cx) / k.w; out[1] = (k.cy - k.fy * yc / d) / k.h; out[2] = d;
        return true;
    }

    /** Fill in the points inside a view's box (shrunk a little: Claude's boxes are loose) and where the object is. */
    private static boolean gather(View v, float[] pts, int n, float floorY) {
        float mu = (v.u1 - v.u0) * 0.04f, mv = (v.v1 - v.v0) * 0.04f;
        float u0 = v.u0 + mu, u1 = v.u1 - mu, v0 = v.v0 + mv, v1 = v.v1 - mv;
        int[] idx = new int[4096]; float[] ds = new float[4096]; int m = 0;
        float[] o = new float[3];
        for (int i = 0; i < n; i++) {
            float y = pts[i * 3 + 1];
            if (y < floorY + 0.03f) continue; // the floor under it stays in the room
            if (!project(v.k, pts[i * 3], y, pts[i * 3 + 2], o)) continue;
            if (o[2] > 6f || o[0] < u0 || o[0] > u1 || o[1] < v0 || o[1] > v1) continue;
            if (m == idx.length) { idx = Arrays.copyOf(idx, m * 2); ds = Arrays.copyOf(ds, m * 2); }
            idx[m] = i; ds[m] = o[2]; m++;
        }
        if (m < 20) return false;
        float[] sorted = Arrays.copyOf(ds, m);
        Arrays.sort(sorted);
        v.front = sorted[(int) (m * 0.08f)];
        // keep what is not far behind the front surface; the wall behind gets carved away by the other photos
        int k = 0; float sx = 0, sy = 0, sz = 0; int ns = 0;
        for (int j = 0; j < m; j++) {
            if (ds[j] > v.front + 1.6f || ds[j] < v.front - 0.3f) continue;
            idx[k] = idx[j]; ds[k] = ds[j]; k++;
            if (ds[k - 1] < v.front + 0.35f) { int i = idx[k - 1]; sx += pts[i * 3]; sy += pts[i * 3 + 1]; sz += pts[i * 3 + 2]; ns++; }
        }
        if (k < 20 || ns < 5) return false;
        v.cand = Arrays.copyOf(idx, k); v.nc = k;
        v.sx = sx / ns; v.sy = sy / ns; v.sz = sz / ns;
        return true;
    }

    /** Build the objects from all sightings. */
    static List<Item> build(List<View> views, float[] pts, int n, float floorY) {
        List<View> ok = new ArrayList<>();
        for (View v : views) if (gather(v, pts, n, floorY)) ok.add(v);
        // the same object seen from several photos: rough positions within 60 cm
        List<List<View>> groups = new ArrayList<>();
        for (View v : ok) {
            List<View> best = null; float bd = 0.6f * 0.6f;
            for (List<View> g : groups) {
                View a = g.get(0);
                float dx = a.sx - v.sx, dz = a.sz - v.sz, dy = a.sy - v.sy;
                float d2 = dx * dx + dz * dz;
                if (d2 < bd && Math.abs(dy) < 0.8f) { bd = d2; best = g; }
            }
            if (best == null) { best = new ArrayList<>(); groups.add(best); }
            best.add(v);
        }
        List<Item> out = new ArrayList<>();
        float[] o = new float[3];
        for (List<View> g : groups) {
            // candidates: points any view put in its box
            HashMap<Integer, Boolean> seen = new HashMap<>();
            int[] cand = new int[1024]; int nc = 0;
            for (View v : g) for (int j = 0; j < v.nc; j++) {
                int i = v.cand[j];
                if (seen.put(i, Boolean.TRUE) != null) continue;
                if (nc == cand.length) cand = Arrays.copyOf(cand, nc * 2);
                cand[nc++] = i;
            }
            int[] keep = new int[nc]; int nk = 0;
            for (int j = 0; j < nc; j++) {
                int i = cand[j];
                float x = pts[i * 3], y = pts[i * 3 + 1], z = pts[i * 3 + 2];
                int visible = 0, inside = 0;
                for (View v : g) {
                    if (!project(v.k, x, y, z, o)) continue;
                    if (o[0] < 0 || o[0] > 1 || o[1] < 0 || o[1] > 1) continue; // that photo didn't see this spot: no vote
                    visible++;
                    float mu = (v.u1 - v.u0) * 0.06f, mv = (v.v1 - v.v0) * 0.06f;
                    if (o[0] >= v.u0 - mu && o[0] <= v.u1 + mu && o[1] >= v.v0 - mv && o[1] <= v.v1 + mv && o[2] <= v.front + 1.6f) inside++;
                }
                int allowMiss = visible >= 4 ? 1 : 0;
                if (inside >= 1 && inside >= visible - allowMiss) keep[nk++] = i;
            }
            if (g.size() == 1) nk = frontCluster(g.get(0), pts, keep, nk);
            Item it = box(pts, keep, nk, floorY);
            if (it == null) continue;
            it.views = g.size();
            it.name = commonName(g);
            out.add(it);
        }
        // overlapping results (two groups that turned out to be one thing): keep the better-seen one
        out.sort((a, b) -> b.views != a.views ? b.views - a.views : b.points - a.points);
        List<Item> fin = new ArrayList<>();
        for (Item a : out) {
            boolean dup = false;
            for (Item b : fin) if (Math.hypot(a.cx - b.cx, a.cz - b.cz) < Math.max(0.25f, 0.6f * Math.min(Math.max(a.hu, a.hv), Math.max(b.hu, b.hv))) && Math.abs(a.y1 - b.y1) < 0.5f) { dup = true; break; }
            if (!dup) fin.add(a);
        }
        return fin;
    }

    /** One photo only: keep the nearest cluster of depths (stop at the first 12 cm gap). */
    private static int frontCluster(View v, float[] pts, int[] keep, int nk) {
        float[] o = new float[3];
        float[] d = new float[nk];
        for (int j = 0; j < nk; j++) { int i = keep[j]; project(v.k, pts[i * 3], pts[i * 3 + 1], pts[i * 3 + 2], o); d[j] = o[2]; }
        float[] s = Arrays.copyOf(d, nk);
        Arrays.sort(s);
        int start = (int) (nk * 0.03f);
        float cut = s.length > 0 ? s[start] + 1.2f : 0;
        for (int j = start + 1; j < nk; j++) if (s[j] - s[j - 1] > 0.12f) { cut = s[j - 1] + 0.01f; break; }
        int m = 0;
        for (int j = 0; j < nk; j++) if (d[j] <= cut) keep[m++] = keep[j];
        return m;
    }

    /** An upright box around the points (heading from their spread on the floor plan, 3-97% so specks don't stretch it). */
    private static Item box(float[] pts, int[] keep, int nk, float floorY) {
        if (nk < 40) return null;
        double mx = 0, mz = 0;
        for (int j = 0; j < nk; j++) { mx += pts[keep[j] * 3]; mz += pts[keep[j] * 3 + 2]; }
        mx /= nk; mz /= nk;
        double sxx = 0, sxz = 0, szz = 0;
        for (int j = 0; j < nk; j++) { double dx = pts[keep[j] * 3] - mx, dz = pts[keep[j] * 3 + 2] - mz; sxx += dx * dx; sxz += dx * dz; szz += dz * dz; }
        double ang = 0.5 * Math.atan2(2 * sxz, sxx - szz);
        float c = (float) Math.cos(ang), s = (float) Math.sin(ang);
        float[] us = new float[nk], vs = new float[nk], ys = new float[nk];
        for (int j = 0; j < nk; j++) {
            float dx = pts[keep[j] * 3] - (float) mx, dz = pts[keep[j] * 3 + 2] - (float) mz;
            us[j] = dx * c + dz * s; vs[j] = -dx * s + dz * c; ys[j] = pts[keep[j] * 3 + 1];
        }
        Arrays.sort(us); Arrays.sort(vs); Arrays.sort(ys);
        int lo = (int) (nk * 0.03f), hi = (int) (nk * 0.97f);
        float u0 = us[lo], u1 = us[hi], v0 = vs[lo], v1 = vs[hi];
        Item it = new Item();
        it.hu = Math.max(0.04f, (u1 - u0) / 2); it.hv = Math.max(0.04f, (v1 - v0) / 2);
        float um = (u0 + u1) / 2, vm = (v0 + v1) / 2;
        it.cx = (float) mx + um * c - vm * s; it.cz = (float) mz + um * s + vm * c;
        it.cosA = c; it.sinA = s;
        it.y0 = ys[(int) (nk * 0.02f)]; it.y1 = ys[(int) (nk * 0.98f)];
        if (it.y0 < floorY + 0.12f) { it.y0 = floorY; it.onFloor = true; }
        it.points = nk;
        if (it.y1 - it.y0 < 0.06f) return null;
        if (Math.max(it.hu, it.hv) > 1.6f) return null; // wall-sized: the carving failed
        return it;
    }

    private static String commonName(List<View> g) {
        HashMap<String, Integer> c = new HashMap<>();
        String best = "object"; int bn = 0;
        for (View v : g) {
            String k = v.name == null || v.name.isEmpty() ? "object" : v.name;
            int x = c.merge(k, 1, Integer::sum);
            if (x > bn) { bn = x; best = k; }
        }
        return Character.toUpperCase(best.charAt(0)) + best.substring(1);
    }

    synchronized String toJson() throws Exception {
        JSONArray a = new JSONArray();
        for (Item it : list) {
            JSONObject j = new JSONObject();
            j.put("name", it.name); j.put("cx", it.cx); j.put("cz", it.cz); j.put("cosA", it.cosA); j.put("sinA", it.sinA);
            j.put("hu", it.hu); j.put("hv", it.hv); j.put("y0", it.y0); j.put("y1", it.y1); j.put("floor", it.onFloor);
            j.put("views", it.views); j.put("points", it.points);
            a.put(j);
        }
        return a.toString();
    }

    synchronized void fromJson(String s) {
        list.clear();
        if (s == null) return;
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.getJSONObject(i);
                Item it = new Item();
                it.name = j.optString("name", "Object");
                it.cx = (float) j.getDouble("cx"); it.cz = (float) j.getDouble("cz"); it.cosA = (float) j.getDouble("cosA"); it.sinA = (float) j.getDouble("sinA");
                it.hu = (float) j.getDouble("hu"); it.hv = (float) j.getDouble("hv"); it.y0 = (float) j.getDouble("y0"); it.y1 = (float) j.getDouble("y1");
                it.onFloor = j.optBoolean("floor", true); it.views = j.optInt("views", 1); it.points = j.optInt("points", 0);
                list.add(it);
            }
        } catch (Exception ignored) { }
    }

    static String describe(Item it) {
        return String.format(Locale.US, "%s %.0fx%.0fx%.0f cm, %d photos", it.name, it.hu * 200, it.hv * 200, (it.y1 - it.y0) * 100, it.views);
    }
}
