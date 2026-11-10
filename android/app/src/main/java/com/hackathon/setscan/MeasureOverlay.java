package com.hackathon.setscan;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;

/**
 * Measurements in Enter the room (rk 10 Nov): lines between two picked spots on the scan, with end dots and the
 * distance ("2.34 m", or "87 cm" under a metre) on a label that always faces the screen. A single picked spot shows
 * its height above the floor until the second is picked. Drawn over the 3D view each frame from the renderer's last
 * view matrix (so labels stay readable and never hide behind the scan). View-only: not saved with the scan.
 */
final class MeasureOverlay extends View {
    private final ScanRenderer r;
    private final ArrayList<float[]> lines = new ArrayList<>();   // x0 y0 z0 x1 y1 z1
    private final ArrayList<RectF> labelRects = new ArrayList<>();
    private float[] pending; private float floorY;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG),
            bg = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG), halo = new Paint(Paint.ANTI_ALIAS_FLAG),
            selLine = new Paint(Paint.ANTI_ALIAS_FLAG), del = new Paint(Paint.ANTI_ALIAS_FLAG), dashed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;

    MeasureOverlay(Context c, ScanRenderer r) {
        super(c);
        this.r = r;
        dp = c.getResources().getDisplayMetrics().density;
        line.setColor(0xFFFFD60A); line.setStrokeWidth(3 * dp); line.setStrokeCap(Paint.Cap.ROUND);
        halo.setColor(0x99000000); halo.setStrokeWidth(5.5f * dp); halo.setStrokeCap(Paint.Cap.ROUND);
        dot.setColor(0xFFFFD60A);
        selLine.set(line); selLine.setColor(0xFFFFFFFF); selLine.setStrokeWidth(4 * dp);
        dashed.set(line); dashed.setPathEffect(new android.graphics.DashPathEffect(new float[]{10 * dp, 7 * dp}, 0));
        del.setColor(0xFFE5484D);
        bg.setColor(0xE61C1C1E);
        text.setColor(0xFFFFFFFF); text.setTextSize(15 * dp); text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        setClickable(false); setFocusable(false);
    }

    int count() { return lines.size() + (pending != null ? 1 : 0); }

    private java.util.List<DoorwayFinder.Doorway> doors = new ArrayList<>();
    private final ArrayList<RectF> doorLabels = new ArrayList<>();
    void setDoorways(java.util.List<DoorwayFinder.Doorway> ds) { doors = ds == null ? new ArrayList<>() : new ArrayList<>(ds); invalidate(); }

    void add(float[] p, float floor) {
        floorY = floor;
        // A tap on the floor lands on the trusted floor (ARCore's plane): the depth's floor mesh is several cm off.
        if (!Float.isNaN(floor) && Math.abs(p[1] - floor) < 0.12f) p = new float[]{p[0], floor, p[2]};
        if (pending == null) pending = p;
        else { lines.add(new float[]{pending[0], pending[1], pending[2], p[0], p[1], p[2]}); pending = null; changed(); }
        invalidate();
    }

    void cancelPending() { pending = null; invalidate(); }
    void clearAll() { lines.clear(); pending = null; selected = -1; invalidate(); changed(); }

    // ---- Selecting and deleting one, kept with the scan (rk 10 Nov 12:10) ----
    private int selected = -1;
    private RectF deleteRect;
    java.io.File dir;   // the scan the measurements belong to (measurements.json)

    /** A tap: on the selected one's Delete removes it; on a label or line selects it; elsewhere deselects. True when used. */
    boolean tapAt(float x, float y) {
        if (selected >= 0 && deleteRect != null) {
            RectF d = new RectF(deleteRect); d.inset(-8 * dp, -8 * dp);
            if (d.contains(x, y) && selected < lines.size()) { lines.remove(selected); selected = -1; invalidate(); changed(); return true; }
        }
        for (int i = 0; i < labelRects.size() && i < lines.size(); i++) {
            RectF rr = new RectF(labelRects.get(i)); rr.inset(-8 * dp, -8 * dp);
            if (rr.contains(x, y) || nearLine(i, x, y)) { selected = selected == i ? -1 : i; invalidate(); return true; }
        }
        if (selected >= 0) { selected = -1; invalidate(); return true; }
        return false;
    }

    private boolean nearLine(int i, float x, float y) {
        float[] l = lines.get(i), a = project(l[0], l[1], l[2]), b = project(l[3], l[4], l[5]);
        if (a == null || b == null) return false;
        float dx = b[0] - a[0], dy = b[1] - a[1], len2 = dx * dx + dy * dy; if (len2 < 1) return false;
        float t = Math.max(0, Math.min(1, ((x - a[0]) * dx + (y - a[1]) * dy) / len2));
        float px = a[0] + t * dx - x, py = a[1] + t * dy - y;
        return px * px + py * py < (14 * dp) * (14 * dp);
    }

    /** measurements.json: [[x0, y0, z0, x1, y1, z1], ...] in the scan's world (metres). */
    void load(java.io.File scanDir) {
        dir = scanDir; lines.clear(); pending = null; selected = -1;
        if (scanDir != null) try {
            java.io.File f = new java.io.File(scanDir, "measurements.json");
            if (f.exists()) {
                org.json.JSONArray a = new org.json.JSONArray(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
                for (int i = 0; i < a.length(); i++) {
                    org.json.JSONArray m = a.getJSONArray(i); float[] l = new float[6];
                    for (int k = 0; k < 6; k++) l[k] = (float) m.getDouble(k);
                    lines.add(l);
                }
            }
        } catch (Exception ignored) { }
        invalidate();
    }

    private void changed() {
        java.io.File d = dir; if (d == null) return;
        final ArrayList<float[]> copy = new ArrayList<>(lines);
        new Thread(() -> {
            try {
                org.json.JSONArray a = new org.json.JSONArray();
                for (float[] l : copy) { org.json.JSONArray m = new org.json.JSONArray(); for (float v : l) m.put((double) v); a.put(m); }
                java.io.File f = new java.io.File(d, "measurements.json");
                if (copy.isEmpty()) f.delete();
                else try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(a.toString().getBytes("UTF-8")); }
            } catch (Exception ignored) { }
        }).start();
    }

    /** Removes the measurement whose label is at (x, y). */
    boolean removeAt(float x, float y) {
        for (int i = 0; i < labelRects.size() && i < lines.size(); i++) {
            RectF rr = new RectF(labelRects.get(i)); rr.inset(-8 * dp, -8 * dp);
            if (rr.contains(x, y)) { lines.remove(i); invalidate(); return true; }
        }
        return false;
    }

    static String format(float m) { return m < 1f ? Math.round(m * 100) + " cm" : String.format(java.util.Locale.US, "%.2f m", m); }

    /** Screen position of a world point, or null behind the camera. */
    private float[] project(float x, float y, float z) {
        float[] m = r.shownMvp; int w = r.shownW, h = r.shownH;
        float cx = m[0] * x + m[4] * y + m[8] * z + m[12], cy = m[1] * x + m[5] * y + m[9] * z + m[13], cw = m[3] * x + m[7] * y + m[11] * z + m[15];
        if (cw < 0.02f || w == 0) return null;
        // In the 3D view's own pixels: this overlay starts at the same top-left corner, but the 3D view can be shorter
        // (the panel under it), so no stretching to the overlay's size (rk 10 Nov 11:49: a doorway outline drawn
        // below the scan).
        return new float[]{(cx / cw + 1) / 2 * w, (1 - cy / cw) / 2 * h};
    }

    @Override protected void onDraw(Canvas c) {
        labelRects.clear(); deleteRect = null; doorLabels.clear();
        for (DoorwayFinder.Doorway d : doors) {   // a thin outline of the opening and its size
            float top = d.floorY + d.height;
            float[] a = project(d.x0, d.floorY, d.z0), b = project(d.x1, d.floorY, d.z1), cc = project(d.x1, top, d.z1), dd = project(d.x0, top, d.z0);
            if (a == null || b == null || cc == null || dd == null) continue;
            float[] seg = {a[0], a[1], dd[0], dd[1], dd[0], dd[1], cc[0], cc[1], cc[0], cc[1], b[0], b[1]};
            Paint lp = d.sure ? line : dashed;   // dashed while a side or the top wasn't seen
            for (int k = 0; k < seg.length; k += 4) { c.drawLine(seg[k], seg[k + 1], seg[k + 2], seg[k + 3], halo); c.drawLine(seg[k], seg[k + 1], seg[k + 2], seg[k + 3], lp); }
            String t = d.sure ? String.format(java.util.Locale.US, "Doorway ≈%.2f × %.2f m", d.width, d.height) : "Doorway (scan around the frame for its size)";
            float lx = (dd[0] + cc[0]) / 2, ly = (dd[1] + cc[1]) / 2 - 18 * dp;
            // never on top of another doorway's label (two doors at a corner)
            for (RectF o : doorLabels) if (Math.abs(o.centerX() - lx) < o.width() && Math.abs(o.centerY() - ly) < o.height() + 4 * dp) ly = o.top - o.height() / 2 - 6 * dp;
            doorLabels.add(label(c, t, lx, ly));
        }
        for (float[] l : lines) {
            float[] a = project(l[0], l[1], l[2]), b = project(l[3], l[4], l[5]);
            if (a == null || b == null) { labelRects.add(new RectF()); continue; }
            boolean sel = lines.indexOf(l) == selected;
            c.drawLine(a[0], a[1], b[0], b[1], halo); c.drawLine(a[0], a[1], b[0], b[1], sel ? selLine : line);
            c.drawCircle(a[0], a[1], 5 * dp, dot); c.drawCircle(b[0], b[1], 5 * dp, dot);
            float dx = l[3] - l[0], dy = l[4] - l[1], dz = l[5] - l[2];
            float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz), hz = (float) Math.sqrt(dx * dx + dz * dz);
            // Nearly upright or nearly level (within ~5°): the straight height or width, which is what's meant.
            String t = hz < 0.08f * d ? format(Math.abs(dy)) + " ↕" : Math.abs(dy) < 0.08f * d ? format(hz) + " ↔" : format(d);
            RectF lr = label(c, t, (a[0] + b[0]) / 2, (a[1] + b[1]) / 2);
            labelRects.add(lr);
            if (sel) {   // a small Delete chip beside the selected one's label
                float tw = text.measureText("Delete"), pad = 8 * dp, th = 15 * dp;
                deleteRect = new RectF(lr.right + 6 * dp, lr.top, lr.right + 6 * dp + tw + 2 * pad, lr.bottom);
                c.drawRoundRect(deleteRect, 10 * dp, 10 * dp, del);
                c.drawText("Delete", deleteRect.left + pad, (lr.top + lr.bottom) / 2 + th * 0.35f, text);
            }
        }
        if (pending != null) {
            float[] a = project(pending[0], pending[1], pending[2]);
            if (a != null) {
                c.drawCircle(a[0], a[1], 6 * dp, dot);
                label(c, format(Math.max(0, pending[1] - floorY)) + " above the floor", a[0], a[1] - 26 * dp);
            }
        }
        if (!lines.isEmpty() || pending != null || !doors.isEmpty()) postInvalidateOnAnimation();   // follows the view as it turns
    }

    private RectF label(Canvas c, String s, float x, float y) {
        float tw = text.measureText(s), pad = 8 * dp, th = 15 * dp;
        RectF rr = new RectF(x - tw / 2 - pad, y - th / 2 - pad * 0.7f, x + tw / 2 + pad, y + th / 2 + pad * 0.7f);
        c.drawRoundRect(rr, 10 * dp, 10 * dp, bg);
        c.drawText(s, x - tw / 2, y + th * 0.35f, text);
        return rr;
    }
}
