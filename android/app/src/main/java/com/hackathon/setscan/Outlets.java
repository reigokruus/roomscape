package com.hackathon.setscan;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Power outlets in the scanned room (rk, 7 Nov: essential for film crews and interior designers).
 *
 * Claude finds outlets in the scan's photos (2D boxes); each box is lifted into 3D with the scan's own points
 * that project inside it (the front-most surface there), and sightings from different photos within 20 cm
 * merge into one outlet.
 */
final class Outlets {
    static final class Outlet {
        float x, y, z;
        int views = 1, sockets = 1;
        String type = "wall socket";
        String label(float floorY) {
            float up = (y - floorY) * 100;
            String t = type.contains("strip") ? "Power strip" : type.contains("floor") ? "Floor socket" : "Outlet";
            return String.format(Locale.US, "⚡ %s%s · %.0f cm up", t, sockets > 1 ? " ×" + sockets : "", Math.max(0, up));
        }
    }

    final List<Outlet> list = new ArrayList<>();
    boolean checked; // the photos of this scan have been searched (saved, so a reopen doesn't pay again)

    synchronized void clear() { list.clear(); checked = false; }

    synchronized List<Outlet> snapshot() { return new ArrayList<>(list); }

    /**
     * Lift one detection into the room.
     * @param k     the photo's keyframe (pose, intrinsics, view matrix)
     * @param map   upright-photo matrix from ObjectNamer (0..1 photo coords -> upright pixels)
     * @param uw    upright image width, uh height
     * @param pts   scan points (x, y, z), n of them
     * @return world position, or null if no scan points back it up
     */
    static float[] lift(PhotoRoom.Keyframe k, android.graphics.Matrix map, int uw, int uh, ClaudeHelper.OutletDet d, float[] pts, int n) {
        android.graphics.Matrix inv = new android.graphics.Matrix();
        if (!map.invert(inv)) return null;
        // the box (shrunk to its middle half) and its centre, back in the photo's own 0..1 coordinates
        float mx = (d.x0 + d.x1) / 2, my = (d.y0 + d.y1) / 2, hx = Math.max(0.01f, (d.x1 - d.x0) / 4), hy = Math.max(0.01f, (d.y1 - d.y0) / 4);
        float[] c = {(mx - hx) * uw, (my - hy) * uh, (mx + hx) * uw, (my + hy) * uh, mx * uw, my * uh};
        inv.mapPoints(c);
        float u0 = Math.min(c[0], c[2]), u1 = Math.max(c[0], c[2]), v0 = Math.min(c[1], c[3]), v1 = Math.max(c[1], c[3]);
        float uc = c[4], vc = c[5];
        float[] vm = k.view;
        float[] ds = new float[4096]; int m = 0;
        for (int i = 0; i < n; i++) {
            float x = pts[i * 3], y = pts[i * 3 + 1], z = pts[i * 3 + 2];
            float xc = vm[0] * x + vm[4] * y + vm[8] * z + vm[12], yc = vm[1] * x + vm[5] * y + vm[9] * z + vm[13], zc = vm[2] * x + vm[6] * y + vm[10] * z + vm[14];
            float dd = -zc;
            if (dd < 0.2f || dd > 6f) continue;
            float u = (k.fx * xc / dd + k.cx) / k.w, v = (k.cy - k.fy * yc / dd) / k.h;
            if (u < u0 || u > u1 || v < v0 || v > v1) continue;
            if (m < ds.length) ds[m++] = dd;
        }
        if (m < 3) return null;
        java.util.Arrays.sort(ds, 0, m);
        float depth = ds[(int) (m * 0.3f)]; // the front surface (the wall the outlet is on), not things behind it
        // ray through the box centre
        float xc = (uc * k.w - k.cx) / k.fx * depth, yc = (k.cy - vc * k.h) / k.fy * depth, zc = -depth;
        float[] p = k.pose;
        return new float[]{p[0] * xc + p[4] * yc + p[8] * zc + p[12], p[1] * xc + p[5] * yc + p[9] * zc + p[13], p[2] * xc + p[6] * yc + p[10] * zc + p[14]};
    }

    /** Add a sighting; one within 20 cm of a known outlet is the same outlet seen again. */
    synchronized void add(float[] w, ClaudeHelper.OutletDet d) {
        for (Outlet o : list) {
            float dx = o.x - w[0], dy = o.y - w[1], dz = o.z - w[2];
            if (dx * dx + dy * dy + dz * dz < 0.2f * 0.2f) {
                o.x = (o.x * o.views + w[0]) / (o.views + 1); o.y = (o.y * o.views + w[1]) / (o.views + 1); o.z = (o.z * o.views + w[2]) / (o.views + 1);
                o.views++; o.sockets = Math.max(o.sockets, d.sockets);
                return;
            }
        }
        Outlet o = new Outlet();
        o.x = w[0]; o.y = w[1]; o.z = w[2]; o.sockets = d.sockets; o.type = d.type;
        list.add(o);
    }

    synchronized String toJson() throws Exception {
        JSONArray a = new JSONArray();
        for (Outlet o : list) {
            JSONArray r = new JSONArray();
            r.put(o.x); r.put(o.y); r.put(o.z); r.put(o.views); r.put(o.sockets); r.put(o.type);
            a.put(r);
        }
        return a.toString();
    }

    synchronized void fromJson(String s, boolean wasChecked) {
        list.clear(); checked = wasChecked;
        if (s == null) return;
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONArray r = a.getJSONArray(i);
                Outlet o = new Outlet();
                o.x = (float) r.getDouble(0); o.y = (float) r.getDouble(1); o.z = (float) r.getDouble(2);
                o.views = r.optInt(3, 1); o.sockets = r.optInt(4, 1); o.type = r.optString(5, "wall socket");
                list.add(o);
            }
        } catch (Exception ignored) { }
    }
}
