package com.hackathon.setscan;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packs a full scan into one zip so it can be replayed off the phone:
 *  frames.csv + frames/NNNN.depth (uint16 mm, little endian) + frames/NNNN.conf (uint8) — every depth frame with its pose
 *  keyframes.csv + keyframes/NN.jpg — the HD photos (1024x576) with pose and intrinsics
 *  points.ply — the cleaned coloured point cloud, log.txt — the diagnostics report.
 * Poses are 4x4 column-major camera-to-world matrices (ARCore world, +Y up, metres; camera looks down -Z, +Y up).
 */
final class ScanExport {

    /** One recorded depth frame. */
    static final class Rec {
        long ts;
        float[] pose = new float[16];
        float fx, fy, cx, cy;
        int w, h;
        short[] depth;
        byte[] conf;
    }

    static final int MAX_RECS = 1500;
    static final int MAX_EXPORT_FRAMES = 300;

    /** Reads keyframe textures back as JPEG bytes. Must run on the GL thread. */
    static List<byte[]> readKeyframes(PhotoRoom photos, int screenW, int screenH) {
        List<byte[]> out = new ArrayList<>();
        int[] fb = new int[1];
        GLES20.glGenFramebuffers(1, fb, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb[0]);
        ByteBuffer px = ByteBuffer.allocateDirect(PhotoRoom.TEX_W * PhotoRoom.TEX_H * 4).order(ByteOrder.nativeOrder());
        for (PhotoRoom.Keyframe k : photos.keyframes) {
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, k.tex, 0);
            px.position(0);
            GLES20.glReadPixels(0, 0, PhotoRoom.TEX_W, PhotoRoom.TEX_H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
            px.position(0);
            // FBO row 0 holds image row 0 (top), so the buffer is already top-down.
            Bitmap b = Bitmap.createBitmap(PhotoRoom.TEX_W, PhotoRoom.TEX_H, Bitmap.Config.ARGB_8888);
            b.copyPixelsFromBuffer(px);
            Bitmap s = Bitmap.createScaledBitmap(b, 1024, 576, true); // back to the camera's 16:9
            ByteArrayOutputStream jpg = new ByteArrayOutputStream();
            s.compress(Bitmap.CompressFormat.JPEG, 80, jpg);
            b.recycle(); s.recycle();
            out.add(jpg.toByteArray());
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glDeleteFramebuffers(1, fb, 0);
        GLES20.glViewport(0, 0, screenW, screenH);
        return out;
    }

    /** Writes the zip to Downloads/CineX and returns a shareable uri (or a file uri on old Android). Background thread. */
    static Uri write(Context ctx, String name, List<Rec> recs, PhotoRoom photos, List<byte[]> jpgs,
                     FloatBuffer pos, ByteBuffer col, int count, String log) throws Exception {
        Uri uri;
        OutputStream os;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CineX");
            uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IllegalStateException("could not create download");
            os = ctx.getContentResolver().openOutputStream(uri);
        } else {
            File f = new File(ctx.getExternalFilesDir(null), name);
            uri = Uri.fromFile(f);
            os = new FileOutputStream(f);
        }
        // Keep the zip under the chat upload limit: at most MAX_EXPORT_FRAMES depth frames, evenly spread.
        if (recs.size() > MAX_EXPORT_FRAMES) {
            List<Rec> thin = new ArrayList<>();
            for (int i = 0; i < MAX_EXPORT_FRAMES; i++) thin.add(recs.get((int) ((long) i * recs.size() / MAX_EXPORT_FRAMES)));
            recs = thin;
        }
        try (ZipOutputStream z = new ZipOutputStream(os)) {
            z.setLevel(9);
            StringBuilder csv = new StringBuilder("idx,timestamp_ns,w,h,fx,fy,cx,cy");
            for (int i = 0; i < 16; i++) csv.append(",m").append(i);
            csv.append('\n');
            for (int i = 0; i < recs.size(); i++) {
                Rec r = recs.get(i);
                csv.append(i).append(',').append(r.ts).append(',').append(r.w).append(',').append(r.h);
                csv.append(String.format(Locale.US, ",%.4f,%.4f,%.4f,%.4f", r.fx, r.fy, r.cx, r.cy));
                for (float v : r.pose) csv.append(String.format(Locale.US, ",%.6f", v));
                csv.append('\n');
                ByteBuffer d = ByteBuffer.allocate(r.depth.length * 2).order(ByteOrder.LITTLE_ENDIAN);
                for (short s : r.depth) d.putShort(s);
                put(z, String.format(Locale.US, "frames/%04d.depth", i), d.array());
                if (r.conf != null) put(z, String.format(Locale.US, "frames/%04d.conf", i), r.conf);
            }
            put(z, "frames.csv", csv.toString().getBytes("UTF-8"));

            StringBuilder kc = new StringBuilder("idx,file,jpg_w,jpg_h,cam_w,cam_h,fx,fy,cx,cy");
            for (int i = 0; i < 16; i++) kc.append(",m").append(i);
            kc.append('\n');
            for (int i = 0; i < photos.keyframes.size() && i < jpgs.size(); i++) {
                PhotoRoom.Keyframe k = photos.keyframes.get(i);
                String f = String.format(Locale.US, "keyframes/%02d.jpg", i);
                kc.append(i).append(',').append(f).append(",1024,576,").append((int) k.w).append(',').append((int) k.h);
                kc.append(String.format(Locale.US, ",%.4f,%.4f,%.4f,%.4f", k.fx, k.fy, k.cx, k.cy));
                for (float v : k.pose) kc.append(String.format(Locale.US, ",%.6f", v));
                kc.append('\n');
                put(z, f, jpgs.get(i));
            }
            put(z, "keyframes.csv", kc.toString().getBytes("UTF-8"));

            String hdr = "ply\nformat binary_little_endian 1.0\nelement vertex " + count
                    + "\nproperty float x\nproperty float y\nproperty float z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n";
            ByteBuffer ply = ByteBuffer.allocate(hdr.length() + count * 15).order(ByteOrder.LITTLE_ENDIAN);
            ply.put(hdr.getBytes("US-ASCII"));
            for (int i = 0; i < count; i++) {
                ply.putFloat(pos.get(i * 3)).putFloat(pos.get(i * 3 + 1)).putFloat(pos.get(i * 3 + 2));
                ply.put(col.get(i * 4)).put(col.get(i * 4 + 1)).put(col.get(i * 4 + 2));
            }
            put(z, "points.ply", ply.array());
            put(z, "log.txt", log.getBytes("UTF-8"));
        }
        return uri;
    }

    private static void put(ZipOutputStream z, String name, byte[] data) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }
}
