package com.hackathon.setscan;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.subject.Subject;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Objects as photo cut-outs. Tap a thing while scanning: ARCore measures the spot, the photo is cut out on the
 * phone (ML Kit subject segmentation) and stood upright in the room at that spot and height, facing the way it
 * was photographed, like a cardboard cut-out. A best guess that reads clearly, instead of a lumpy 3D surface.
 */
final class Cutouts {

    static final class Cutout {
        float[] corners = new float[12]; // bottom-left, bottom-right, top-right, top-left (world)
        float x, z, width, height, bottomY; // where it stands and how big it is
        String name = "object";
        Bitmap bmp; int tex;
        float camX, camZ;
    }

    final List<Cutout> list = new ArrayList<>();
    private SubjectSegmenter seg;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    volatile boolean busy;
    volatile String note;

    synchronized List<Cutout> snapshot() { return new ArrayList<>(list); }

    synchronized void clear() {
        for (Cutout c : list) { if (c.tex != 0) GLES20.glDeleteTextures(1, new int[]{c.tex}, 0); if (c.bmp != null) c.bmp.recycle(); }
        list.clear();
    }

    synchronized int size() { return list.size(); }

    interface Done { void result(Cutout c, String error); }

    /**
     * GL thread. tap: the tapped spot (texture 0..1) and its 3D point from ARCore (or null); pose: camera to world;
     * tIntr: texture intrinsics fx, fy, cx, cy, w, h.
     */
    void make(Bitmap img, float[] pose, float[] tIntr, float tapU, float tapV, float[] hit, float floorY, Done done) {
        if (busy) { img.recycle(); done.result(null, "Still cutting out the last one, a moment..."); return; }
        if (seg == null) seg = SubjectSegmentation.getClient(new SubjectSegmenterOptions.Builder()
                .enableMultipleSubjects(new SubjectSegmenterOptions.SubjectResultOptions.Builder().enableConfidenceMask().build())
                .build());
        float[] view = new float[16];
        android.opengl.Matrix.invertM(view, 0, pose, 0);
        // upright picture (the cut-out model expects people-up photos), and the map from texture 0..1 to its pixels
        int rot = Math.round((float) Math.toDegrees(Math.atan2(view[4], view[5])) / 90f) * 90;
        android.graphics.Matrix map = new android.graphics.Matrix();
        map.setScale(img.getWidth(), img.getHeight());
        android.graphics.Matrix r = new android.graphics.Matrix();
        r.postRotate(rot);
        android.graphics.RectF bounds = new android.graphics.RectF(0, 0, img.getWidth(), img.getHeight());
        r.mapRect(bounds);
        r.postTranslate(-bounds.left, -bounds.top);
        map.postConcat(r);
        Bitmap up = img;
        if (rot != 0) { android.graphics.Matrix rr = new android.graphics.Matrix(); rr.postRotate(rot); up = Bitmap.createBitmap(img, 0, 0, img.getWidth(), img.getHeight(), rr, true); img.recycle(); }
        final Bitmap fup = up;
        float[] tp = {tapU, tapV};
        map.mapPoints(tp);
        final int tx = Math.round(tp[0]), ty = Math.round(tp[1]);
        final android.graphics.Matrix inv = new android.graphics.Matrix();
        map.invert(inv);
        busy = true;
        try {
            seg.process(InputImage.fromBitmap(up, 0))
                    .addOnSuccessListener(worker, res -> {
                        try {
                            Cutout c = place(fup, res.getSubjects(), tx, ty, inv, pose, tIntr, hit, floorY);
                            if (c == null) done.result(null, "Couldn't cut that out. Tap right on the object, with some floor or wall around it.");
                            else {
                                synchronized (this) {
                                    // a new photo of the same thing replaces the old one
                                    for (int i = list.size() - 1; i >= 0; i--) {
                                        Cutout o = list.get(i);
                                        if (Math.hypot(o.x - c.x, o.z - c.z) < Math.max(0.2f, 0.5f * Math.max(o.width, c.width))) { if (o.bmp != null) o.bmp.recycle(); o.bmp = null; o.width = -1; list.remove(i); deleted.add(o); }
                                    }
                                    list.add(c);
                                }
                                done.result(c, null);
                            }
                        } catch (Throwable t) { done.result(null, "Cut-out failed: " + t); }
                        fup.recycle(); busy = false;
                    })
                    .addOnFailureListener(worker, e -> {
                        String m = String.valueOf(e.getMessage()).toLowerCase(Locale.US);
                        done.result(null, m.contains("download") || m.contains("module") || m.contains("waiting")
                                ? "Getting the cut-out model from Google Play services (needs internet once). Try again in a minute."
                                : "Cut-out failed: " + e.getMessage());
                        fup.recycle(); busy = false;
                    });
        } catch (Throwable t) { busy = false; up.recycle(); done.result(null, "Cut-out failed: " + t); }
    }

    private final List<Cutout> deleted = new ArrayList<>();

    /** Worker thread: pick the subject under the tap, cut it out, and stand it in the room. */
    private Cutout place(Bitmap up, List<Subject> subjects, int tx, int ty, android.graphics.Matrix inv, float[] pose, float[] k, float[] hit, float floorY) {
        Subject best = null; float bestD = 1e9f;
        for (Subject s : subjects) {
            FloatBuffer fb = s.getConfidenceMask();
            if (fb == null) continue;
            int sx = s.getStartX(), sy = s.getStartY(), sw = s.getWidth(), sh = s.getHeight();
            float d;
            if (tx >= sx && ty >= sy && tx < sx + sw && ty < sy + sh && fb.get((ty - sy) * sw + (tx - sx)) > 0.5f) d = 0;
            else d = (float) Math.hypot(Math.max(0, Math.max(sx - tx, tx - (sx + sw))), Math.max(0, Math.max(sy - ty, ty - (sy + sh))));
            if (d < bestD) { bestD = d; best = s; }
        }
        if (best == null || bestD > up.getWidth() * 0.06f) return null;
        int sx = best.getStartX(), sy = best.getStartY(), sw = best.getWidth(), sh = best.getHeight();
        FloatBuffer fb = best.getConfidenceMask();
        int[] px = new int[sw * sh];
        up.getPixels(px, 0, sw, sx, sy, sw, sh);
        for (int i = 0; i < px.length; i++) {
            float a = fb.get(i);
            int al = a < 0.35f ? 0 : a > 0.65f ? 255 : Math.round((a - 0.35f) / 0.3f * 255);
            px[i] = (al << 24) | (px[i] & 0xFFFFFF);
        }
        Bitmap cut = Bitmap.createBitmap(px, sw, sh, Bitmap.Config.ARGB_8888);
        // rays through the cut-out's edges
        float[][] corner = {{sx, sy + sh}, {sx + sw, sy + sh}, {sx + sw, sy}, {sx, sy}};
        float camX = pose[12], camY = pose[13], camZ = pose[14];
        // where it stands: the ray through the middle of the cut-out meets the floor, if that agrees with the measured spot
        float[] rc = ray(new float[]{sx + sw / 2f, sy + sh / 2f}, inv, pose, k);
        float[] anchor = null;
        if (rc[1] < -0.05f) {
            float t = (floorY - camY) / rc[1];
            float fx = camX + rc[0] * t, fz = camZ + rc[2] * t;
            float hd = hit == null ? -1 : (float) Math.hypot(hit[0] - camX, hit[2] - camZ), fd = (float) Math.hypot(fx - camX, fz - camZ);
            if (hit == null || (fd > hd * 0.6f && fd < hd * 1.6f + 0.3f)) anchor = new float[]{fx, floorY, fz};
        }
        boolean onFloor = anchor != null;
        if (anchor == null && hit != null) anchor = hit;
        if (anchor == null) return null;
        // 1) measure on an upright plane through the anchor
        float hx = camX - anchor[0], hz = camZ - anchor[2], hl = (float) Math.hypot(hx, hz);
        if (hl < 0.05f) return null;
        float[] vp = plane(corner, inv, pose, k, anchor, hx / hl, 0, hz / hl);
        if (vp == null) return null;
        float spanY = Math.max(Math.max(vp[1], vp[4]), Math.max(vp[7], vp[10])) - Math.min(Math.min(vp[1], vp[4]), Math.min(vp[7], vp[10]));
        float width = (float) Math.hypot(vp[3] - vp[0], vp[5] - vp[2]);
        // seen from above, the picture's height is partly the top of the object: take the footprint (about as deep as
        // it is wide) back out, using the viewing angle
        float elev = (float) Math.atan2(camY - anchor[1], hl);
        float height = onFloor ? Math.max(0.02f, spanY - width * (float) Math.tan(Math.max(0, elev))) : spanY;
        // 2) draw it as a card facing the camera (the photo isn't stretched), standing on the floor
        float nx = camX - anchor[0], ny = camY - anchor[1], nz = camZ - anchor[2], nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        float[] card = plane(corner, inv, pose, k, anchor, nx / nl, ny / nl, nz / nl);
        if (card == null) return null;
        Cutout c = new Cutout();
        c.corners = card;
        if (onFloor) {
            float lift = floorY - Math.min(Math.min(card[1], card[4]), Math.min(card[7], card[10]));
            for (int i = 0; i < 4; i++) c.corners[i * 3 + 1] += lift;
        }
        c.bottomY = Math.min(Math.min(c.corners[1], c.corners[4]), Math.min(c.corners[7], c.corners[10]));
        c.height = height;
        c.width = width;
        c.x = anchor[0]; c.z = anchor[2];
        c.camX = camX; c.camZ = camZ;
        if (c.height > 3f || c.width > 4f || c.height < 0.01f) return null;
        // keep the texture small
        float s = Math.min(1f, 512f / Math.max(sw, sh));
        c.bmp = s < 1f ? Bitmap.createScaledBitmap(cut, Math.max(1, Math.round(sw * s)), Math.max(1, Math.round(sh * s)), true) : cut;
        if (c.bmp != cut) cut.recycle();
        return c;
    }

    /** The 4 corner rays cut by the plane through p with normal n (world), or null if one misses. */
    private static float[] plane(float[][] corner, android.graphics.Matrix inv, float[] pose, float[] k, float[] p, float nx, float ny, float nz) {
        float camX = pose[12], camY = pose[13], camZ = pose[14];
        float[] out = new float[12];
        for (int i = 0; i < 4; i++) {
            float[] d = ray(corner[i], inv, pose, k);
            float den = d[0] * nx + d[1] * ny + d[2] * nz;
            if (Math.abs(den) < 1e-4f) return null;
            float t = ((p[0] - camX) * nx + (p[1] - camY) * ny + (p[2] - camZ) * nz) / den;
            if (t <= 0) return null;
            out[i * 3] = camX + d[0] * t; out[i * 3 + 1] = camY + d[1] * t; out[i * 3 + 2] = camZ + d[2] * t;
        }
        return out;
    }

    /** World direction of the ray through an upright-photo pixel. */
    private static float[] ray(float[] p, android.graphics.Matrix inv, float[] pose, float[] k) {
        float[] uv = {p[0], p[1]};
        inv.mapPoints(uv); // -> texture 0..1
        float xc = (uv[0] * k[4] - k[2]) / k[0], yc = (k[3] - uv[1] * k[5]) / k[1];
        float dx = pose[0] * xc + pose[4] * yc - pose[8], dy = pose[1] * xc + pose[5] * yc - pose[9], dz = pose[2] * xc + pose[6] * yc - pose[10];
        float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        return new float[]{dx / l, dy / l, dz / l};
    }

    // ---- drawing (GL thread) ----
    private int prog;
    private static final String VS = "uniform mat4 uMvp; attribute vec4 aPos; attribute vec2 aUv; varying vec2 vUv; void main(){ gl_Position = uMvp * aPos; vUv = aUv; }";
    private static final String FS = "precision mediump float; varying vec2 vUv; uniform sampler2D uTex;"
            + "void main(){ vec4 c = texture2D(uTex, vUv); if (c.a < 0.5) discard; gl_FragColor = vec4(c.rgb, 1.0); }";
    private static final FloatBuffer UV = ByteBuffer.allocateDirect(48).order(ByteOrder.nativeOrder()).asFloatBuffer();
    static { UV.put(new float[]{0, 1, 1, 1, 1, 0, 0, 1, 1, 0, 0, 0}).position(0); }

    void draw(float[] mvp) {
        synchronized (this) {
            for (Cutout d : deleted) if (d.tex != 0) GLES20.glDeleteTextures(1, new int[]{d.tex}, 0);
            deleted.clear();
        }
        List<Cutout> cs = snapshot();
        if (cs.isEmpty()) return;
        if (prog == 0) prog = ScanRenderer.program(VS, FS);
        GLES20.glUseProgram(prog);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uMvp"), 1, false, mvp, 0);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTex"), 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        int aPos = GLES20.glGetAttribLocation(prog, "aPos"), aUv = GLES20.glGetAttribLocation(prog, "aUv");
        FloatBuffer pb = ByteBuffer.allocateDirect(72).order(ByteOrder.nativeOrder()).asFloatBuffer();
        for (Cutout c : cs) {
            if (c.tex == 0) {
                if (c.bmp == null) continue;
                int[] t = new int[1];
                GLES20.glGenTextures(1, t, 0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, c.bmp, 0);
                c.tex = t[0];
            }
            float[] q = c.corners;
            pb.position(0);
            pb.put(q, 0, 3).put(q, 3, 3).put(q, 6, 3).put(q, 0, 3).put(q, 6, 3).put(q, 9, 3).position(0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, c.tex);
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, pb);
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, UV);
            GLES20.glEnableVertexAttribArray(aPos); GLES20.glEnableVertexAttribArray(aUv);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
        }
        GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aUv);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    /** One line per object, for the status and the log. */
    String describe() {
        StringBuilder b = new StringBuilder();
        for (Cutout c : snapshot())
            b.append(b.length() > 0 ? "\n" : "").append(String.format(Locale.US, "✂️ %s: %.0f cm tall, %.0f cm wide", c.name, c.height * 100, c.width * 100));
        return b.toString();
    }
}
