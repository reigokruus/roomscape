package com.hackathon.setscan;

import android.graphics.Bitmap;
import android.opengl.GLES20;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Names the furniture blocks: for each block, the best scan photo that saw it is cropped to the block,
 * turned upright, and run through ML Kit's on-device image labeller ("Bed", "Chair", "Table", ...).
 */
final class ObjectNamer {

    private static final Set<String> FURNITURE = new HashSet<>(Arrays.asList(
            "bed", "couch", "chair", "table", "desk", "shelf", "cabinetry", "lamp", "television", "monitor",
            "computer", "bench", "piano", "plant", "flowerpot", "curtain", "window", "stairs", "door", "bag",
            "backpack", "pillow", "cushion", "sink", "bathtub", "toilet", "musical instrument", "guitar",
            "bicycle", "shoe", "jacket", "clothing", "box", "bookcase", "wardrobe", "refrigerator", "stove"));

    /** A crop to label (made on the GL thread) and the box it belongs to. */
    static final class Job {
        RoomModel.Box box;
        Bitmap crop;
    }

    /** GL thread: pick a photo per box and cut out the box. */
    static List<Job> prepare(RoomModel room, PhotoRoom photos, int screenW, int screenH) {
        List<Job> jobs = new ArrayList<>();
        if (room.boxes.isEmpty() || photos.keyframes.isEmpty()) return jobs;
        int[] fb = new int[1];
        GLES20.glGenFramebuffers(1, fb, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb[0]);
        ByteBuffer px = ByteBuffer.allocateDirect(PhotoRoom.TEX_W * PhotoRoom.TEX_H * 4).order(ByteOrder.nativeOrder());
        for (RoomModel.Box b : room.boxes) {
            float cx = (b.x0 + b.x1) / 2, cy = (b.y0 + b.y1) / 2, cz = (b.z0 + b.z1) / 2;
            PhotoRoom.Keyframe best = null; float bestScore = 0; float[] bestRect = null;
            for (PhotoRoom.Keyframe k : photos.keyframes) {
                float[] r = rect(k, b);
                if (r == null) continue;
                float dx = cx - k.px, dy = cy - k.py, dz = cz - k.pz, d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                float facing = (dx * k.fwd[0] + dy * k.fwd[1] + dz * k.fwd[2]) / Math.max(d, 1e-3f);
                float area = (r[2] - r[0]) * (r[3] - r[1]);
                float score = facing * area; // centred and big in frame
                if (score > bestScore) { bestScore = score; best = k; bestRect = r; }
            }
            if (best == null) continue;
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, best.tex, 0);
            px.position(0);
            GLES20.glReadPixels(0, 0, PhotoRoom.TEX_W, PhotoRoom.TEX_H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
            px.position(0);
            Bitmap full = Bitmap.createBitmap(PhotoRoom.TEX_W, PhotoRoom.TEX_H, Bitmap.Config.ARGB_8888);
            full.copyPixelsFromBuffer(px);
            Bitmap img = Bitmap.createScaledBitmap(full, 1024, 576, true);
            full.recycle();
            int x0 = (int) (bestRect[0] * 1024), y0 = (int) (bestRect[1] * 576), x1 = (int) (bestRect[2] * 1024), y1 = (int) (bestRect[3] * 576);
            int w = Math.max(16, x1 - x0), h = Math.max(16, y1 - y0);
            x0 = Math.max(0, Math.min(1024 - w, x0)); y0 = Math.max(0, Math.min(576 - h, y0));
            Bitmap crop = Bitmap.createBitmap(img, x0, y0, Math.min(w, 1024 - x0), Math.min(h, 576 - y0));
            // turn the crop so the world's "up" points up (the camera sensor is sideways in portrait)
            float[] v = best.view;
            float ux = v[4], uy = v[5]; // world +Y in camera coordinates (x right, y up)
            int rot = Math.round((float) Math.toDegrees(Math.atan2(ux, uy)) / 90f) * 90;
            if (rot != 0) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(rot);
                crop = Bitmap.createBitmap(crop, 0, 0, crop.getWidth(), crop.getHeight(), m, true);
            }
            if (img != crop) img.recycle();
            Job j = new Job();
            j.box = b; j.crop = crop;
            jobs.add(j);
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glDeleteFramebuffers(1, fb, 0);
        GLES20.glViewport(0, 0, screenW, screenH);
        return jobs;
    }

    /** The box's 8 corners in the photo as a padded rectangle (0..1), or null if it isn't well inside the photo. */
    static float[] rect(PhotoRoom.Keyframe k, RoomModel.Box b) {
        float minU = 9, minV = 9, maxU = -9, maxV = -9;
        float[] m = k.view;
        for (int i = 0; i < 8; i++) {
            float x = (i & 1) == 0 ? b.x0 : b.x1, y = (i & 2) == 0 ? b.y0 : b.y1, z = (i & 4) == 0 ? b.z0 : b.z1;
            float cx = m[0] * x + m[4] * y + m[8] * z + m[12];
            float cy = m[1] * x + m[5] * y + m[9] * z + m[13];
            float cz = m[2] * x + m[6] * y + m[10] * z + m[14];
            float d = -cz;
            if (d < 0.2f) return null;
            float u = (k.fx * cx / d + k.cx) / k.w, v = (k.cy - k.fy * cy / d) / k.h;
            minU = Math.min(minU, u); maxU = Math.max(maxU, u); minV = Math.min(minV, v); maxV = Math.max(maxV, v);
        }
        // mostly in frame?
        float iw = Math.min(maxU, 1) - Math.max(minU, 0), ih = Math.min(maxV, 1) - Math.max(minV, 0);
        if (iw <= 0 || ih <= 0 || iw * ih < 0.6f * (maxU - minU) * (maxV - minV)) return null;
        float pu = (maxU - minU) * 0.15f, pv = (maxV - minV) * 0.15f;
        return new float[]{Math.max(0, minU - pu), Math.max(0, minV - pv), Math.min(1, maxU + pu), Math.min(1, maxV + pv)};
    }

    /** GL thread: one photo as an upright 1024-wide bitmap, plus the matrix that maps its 0..1 coordinates into it. */
    static Bitmap readUpright(PhotoRoom.Keyframe k, int screenW, int screenH, android.graphics.Matrix outMap) {
        int[] fb = new int[1];
        GLES20.glGenFramebuffers(1, fb, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb[0]);
        ByteBuffer px = ByteBuffer.allocateDirect(PhotoRoom.TEX_W * PhotoRoom.TEX_H * 4).order(ByteOrder.nativeOrder());
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, k.tex, 0);
        GLES20.glReadPixels(0, 0, PhotoRoom.TEX_W, PhotoRoom.TEX_H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glDeleteFramebuffers(1, fb, 0);
        GLES20.glViewport(0, 0, screenW, screenH);
        px.position(0);
        Bitmap full = Bitmap.createBitmap(PhotoRoom.TEX_W, PhotoRoom.TEX_H, Bitmap.Config.ARGB_8888);
        full.copyPixelsFromBuffer(px);
        Bitmap img = Bitmap.createScaledBitmap(full, 1024, 576, true);
        full.recycle();
        float ux = k.view[4], uy = k.view[5];
        int rot = Math.round((float) Math.toDegrees(Math.atan2(ux, uy)) / 90f) * 90;
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(1024, 576);
        android.graphics.Matrix r = new android.graphics.Matrix();
        r.postRotate(rot);
        android.graphics.RectF bounds = new android.graphics.RectF(0, 0, 1024, 576);
        r.mapRect(bounds);
        r.postTranslate(-bounds.left, -bounds.top);
        m.postConcat(r);
        if (outMap != null) outMap.set(m);
        if (rot == 0) return img;
        android.graphics.Matrix rr = new android.graphics.Matrix();
        rr.postRotate(rot);
        Bitmap up = Bitmap.createBitmap(img, 0, 0, 1024, 576, rr, true);
        img.recycle();
        return up;
    }

    /** Any thread: label the crops; callbacks arrive on the main thread. done() runs once all are labelled. */
    static void label(List<Job> jobs, Runnable done) {
        if (jobs.isEmpty()) return;
        ImageLabeler labeler = ImageLabeling.getClient(new ImageLabelerOptions.Builder().setConfidenceThreshold(0.35f).build());
        int[] left = {jobs.size()};
        for (Job j : jobs) {
            labeler.process(InputImage.fromBitmap(j.crop, 0))
                    .addOnSuccessListener(labels -> {
                        String name = pick(labels);
                        if (name != null) j.box.name = name;
                    })
                    .addOnCompleteListener(t -> {
                        j.crop.recycle();
                        if (--left[0] == 0) { labeler.close(); done.run(); }
                    });
        }
    }

    private static String pick(List<ImageLabel> labels) {
        String fallback = null;
        for (ImageLabel l : labels) { // sorted by confidence
            String t = l.getText().toLowerCase(Locale.US);
            if (FURNITURE.contains(t)) return l.getText();
            if (fallback == null && l.getConfidence() > 0.7f) fallback = l.getText();
        }
        return fallback;
    }
}
