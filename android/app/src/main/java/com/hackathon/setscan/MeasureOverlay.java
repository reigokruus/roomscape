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
            bg = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG), halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;

    MeasureOverlay(Context c, ScanRenderer r) {
        super(c);
        this.r = r;
        dp = c.getResources().getDisplayMetrics().density;
        line.setColor(0xFFFFD60A); line.setStrokeWidth(3 * dp); line.setStrokeCap(Paint.Cap.ROUND);
        halo.setColor(0x99000000); halo.setStrokeWidth(5.5f * dp); halo.setStrokeCap(Paint.Cap.ROUND);
        dot.setColor(0xFFFFD60A);
        bg.setColor(0xE61C1C1E);
        text.setColor(0xFFFFFFFF); text.setTextSize(15 * dp); text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        setClickable(false); setFocusable(false);
    }

    int count() { return lines.size() + (pending != null ? 1 : 0); }

    private java.util.List<DoorwayFinder.Doorway> doors = new ArrayList<>();
    void setDoorways(java.util.List<DoorwayFinder.Doorway> ds) { doors = ds == null ? new ArrayList<>() : new ArrayList<>(ds); invalidate(); }

    void add(float[] p, float floor) {
        floorY = floor;
        // A tap on the floor lands on the trusted floor (ARCore's plane): the depth's floor mesh is several cm off.
        if (!Float.isNaN(floor) && Math.abs(p[1] - floor) < 0.12f) p = new float[]{p[0], floor, p[2]};
        if (pending == null) pending = p;
        else { lines.add(new float[]{pending[0], pending[1], pending[2], p[0], p[1], p[2]}); pending = null; }
        invalidate();
    }

    void cancelPending() { pending = null; invalidate(); }
    void clearAll() { lines.clear(); pending = null; invalidate(); }

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
        labelRects.clear();
        for (DoorwayFinder.Doorway d : doors) {   // a thin outline of the opening and its size
            float top = d.floorY + d.height;
            float[] a = project(d.x0, d.floorY, d.z0), b = project(d.x1, d.floorY, d.z1), cc = project(d.x1, top, d.z1), dd = project(d.x0, top, d.z0);
            if (a == null || b == null || cc == null || dd == null) continue;
            float[] seg = {a[0], a[1], dd[0], dd[1], dd[0], dd[1], cc[0], cc[1], cc[0], cc[1], b[0], b[1]};
            for (int k = 0; k < seg.length; k += 4) { c.drawLine(seg[k], seg[k + 1], seg[k + 2], seg[k + 3], halo); c.drawLine(seg[k], seg[k + 1], seg[k + 2], seg[k + 3], line); }
            label(c, String.format(java.util.Locale.US, "Doorway %.2f × %.2f m", d.width, d.height), (dd[0] + cc[0]) / 2, (dd[1] + cc[1]) / 2 - 18 * dp);
        }
        for (float[] l : lines) {
            float[] a = project(l[0], l[1], l[2]), b = project(l[3], l[4], l[5]);
            if (a == null || b == null) { labelRects.add(new RectF()); continue; }
            c.drawLine(a[0], a[1], b[0], b[1], halo); c.drawLine(a[0], a[1], b[0], b[1], line);
            c.drawCircle(a[0], a[1], 5 * dp, dot); c.drawCircle(b[0], b[1], 5 * dp, dot);
            float dx = l[3] - l[0], dy = l[4] - l[1], dz = l[5] - l[2];
            float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz), hz = (float) Math.sqrt(dx * dx + dz * dz);
            // Nearly upright or nearly level (within ~5°): the straight height or width, which is what's meant.
            String t = hz < 0.08f * d ? format(Math.abs(dy)) + " ↕" : Math.abs(dy) < 0.08f * d ? format(hz) + " ↔" : format(d);
            labelRects.add(label(c, t, (a[0] + b[0]) / 2, (a[1] + b[1]) / 2));
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
