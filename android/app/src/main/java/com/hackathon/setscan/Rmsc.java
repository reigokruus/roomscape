package com.hackathon.setscan;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.opengl.Matrix;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The .rmsc scan package shared with the iPhone app and the web viewer (spec: docs/rmsc-format.md).
 * A zip of manifest.json, points.ply, photos.json + photos/NNN.jpg, depth/NNN.bin and thumb.jpg, plus android/
 * extras (the full meta.json, splats, path) so an Android-to-Android round trip loses nothing.
 * ScanStore keyframe poses are already camera-to-world, column-major, OpenGL camera frame, with intrinsics
 * relative to (w, h) and pixel v counted down from the top, which is the package's convention.
 */
final class Rmsc {
    static final String MIME = "application/octet-stream";

    // ---------------------------------------------------------------- export

    /** Packs a saved scan into Downloads/RoomScape/<name>.rmsc and returns a shareable uri. Background thread. */
    static Uri export(Context ctx, File dir) throws Exception {
        ScanStore.Data d = ScanStore.load(dir);
        String base = safeName(d.name.isEmpty() ? dir.getName() : d.name);
        String file = base + ".rmsc";
        Uri uri; OutputStream raw;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, file);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, MIME);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RoomScape");
            uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception("could not create the file in Downloads");
            raw = ctx.getContentResolver().openOutputStream(uri);
        } else {
            File out = new File(ctx.getExternalFilesDir(null), file);
            uri = Uri.fromFile(out);
            raw = new FileOutputStream(out);
        }
        try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(raw, 1 << 16))) {
            String[] v = appVersion(ctx);
            write(z, d, dir, v[0], Integer.parseInt(v[1]));
        }
        return uri;
    }

    /**
     * Everything shared is written with the floor at y = 0, like the iPhone's packages (floorY 0). How far it was moved
     * goes in android/shift.json, so this app can put its own android/ extras (marks, splats, path) back in place on import.
     */
    /** The shared tiles.json from Android's own one (FlatTiles.snapshot): every tile as a quad, corners in metres in
     *  the package's frame (+Y up, floor moved to y = 0 by floorY), in order (0,0) (1,0) (1,1) (0,1) of the tile's own
     *  grid, each with its pixel in tiles.png (x right, y down from the top). Null when there are no tiles. */
    static String sharedTiles(String raw, float floorY) throws org.json.JSONException {
        JSONObject o = new JSONObject(raw);
        float tile = (float) o.getDouble("tile"); int px = o.getInt("px"), atlas = o.getInt("atlas"), rows = o.getInt("rows"), perRow = atlas / px;
        JSONArray pa = o.getJSONArray("planes"), ta = o.getJSONArray("tiles"), out = new JSONArray();
        int[][] k = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int i = 0; i < ta.length(); i++) {
            JSONArray t = ta.getJSONArray(i);
            JSONArray p = pa.getJSONArray(t.getInt(0));
            double nx = p.getDouble(0), ny = p.getDouble(1), nz = p.getDouble(2), d = p.getDouble(3);
            int iu = t.getInt(1), iv = t.getInt(2), slot = t.getInt(3);
            int x0 = (slot % perRow) * px, y0 = (slot / perRow) * px;
            JSONArray corners = new JSONArray(), pix = new JSONArray();
            for (int[] c : k) {
                double a = (iu + c[0]) * tile, b = (iv + c[1]) * tile;
                double x = p.getDouble(4) * a + p.getDouble(7) * b - nx * d, y = p.getDouble(5) * a + p.getDouble(8) * b - ny * d, z = p.getDouble(6) * a + p.getDouble(9) * b - nz * d;
                corners.put(new JSONArray().put(r4(x)).put(r4(y - floorY)).put(r4(z)));
                pix.put(new JSONArray().put(x0 + c[0] * px).put(y0 + c[1] * px));
            }
            out.put(new JSONObject().put("corners", corners).put("pixels", pix).put("normal", new JSONArray().put(r4(nx)).put(r4(ny)).put(r4(nz))));
        }
        if (out.length() == 0) return null;
        return new JSONObject().put("version", 1).put("tileSize", tile).put("image", "tiles.png").put("imageWidth", atlas).put("imageHeight", rows * px)
                .put("tiles", out).toString();
    }
    private static double r4(double v) { return Math.round(v * 10000) / 10000.0; }

    /** Debug switch "Points under tiles: hidden": the points under the scan's tiles are left out of what's shared
     *  (this in-memory copy only; the saved points.bin is never changed). */
    static void dropUnderTiles(ScanStore.Data d, File dir) {
        FlatTiles.Cover c = FlatTiles.Cover.read(dir);
        if (c == null || d.n == 0) return;
        int k = 0;
        for (int i = 0; i < d.n; i++) {
            if (c.under(d.pos[i * 3], d.pos[i * 3 + 1], d.pos[i * 3 + 2])) continue;
            if (k != i) {
                System.arraycopy(d.pos, i * 3, d.pos, k * 3, 3); System.arraycopy(d.col, i * 3, d.col, k * 3, 3);
                if (d.seg != null) d.seg[k] = d.seg[i];
            }
            k++;
        }
        android.util.Log.i("RoomScape", "export: " + (d.n - k) + " of " + d.n + " points under tiles left out");
        d.n = k;
    }

    static void write(ZipOutputStream z, ScanStore.Data d, File dir, String appVersion, int appBuild) throws Exception {
        if (ScanRenderer.DROP_UNDER_TILES) dropUnderTiles(d, dir);
        final float floorY = floorY(d);
        float[] mn = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, mx = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < d.n; i++) for (int a = 0; a < 3; a++) { float v = d.pos[i * 3 + a] - (a == 1 ? floorY : 0); if (v < mn[a]) mn[a] = v; if (v > mx[a]) mx[a] = v; }

        // points.ply
        ByteArrayOutputStream hdr = new ByteArrayOutputStream();
        hdr.write(("ply\nformat binary_little_endian 1.0\ncomment RoomScape Android\nelement vertex " + d.n
                + "\nproperty float x\nproperty float y\nproperty float z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n").getBytes("US-ASCII"));
        z.putNextEntry(new ZipEntry("points.ply"));
        z.write(hdr.toByteArray());
        ByteBuffer bb = ByteBuffer.allocate(15 * 4096).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < d.n; i++) {
            bb.putFloat(d.pos[i * 3]).putFloat(d.pos[i * 3 + 1] - floorY).putFloat(d.pos[i * 3 + 2]);
            bb.put(d.col[i * 3]).put(d.col[i * 3 + 1]).put(d.col[i * 3 + 2]);
            if (!bb.hasRemaining()) { z.write(bb.array(), 0, bb.position()); bb.clear(); }
        }
        z.write(bb.array(), 0, bb.position());
        z.closeEntry();

        // photos
        JSONArray photos = new JSONArray();
        for (int i = 0; i < d.kfs.size(); i++) {
            ScanStore.Kf k = d.kfs.get(i);
            BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(k.jpg, 0, k.jpg.length, o);
            int iw = o.outWidth > 0 ? o.outWidth : Math.round(k.w), ih = o.outHeight > 0 ? o.outHeight : Math.round(k.h);
            float sx = iw / k.w, sy = ih / k.h; // intrinsics are relative to (w, h): rescale to the stored jpeg's pixels
            String name = String.format(Locale.US, "photos/%03d.jpg", i);
            put(z, name, k.jpg);
            JSONObject p = new JSONObject();
            p.put("file", name); p.put("width", iw); p.put("height", ih);
            p.put("fx", k.fx * sx); p.put("fy", k.fy * sy); p.put("cx", k.cx * sx); p.put("cy", k.cy * sy);
            float[] pose = k.pose.clone(); pose[13] -= floorY;
            p.put("pose", vec(pose));
            if (k.closeUp) p.put("closeUp", true);
            if (k.depth != null && k.dw * k.dh > 0 && k.dfx > 0) {
                String dn = String.format(Locale.US, "depth/%03d.bin", i);
                ByteBuffer db = ByteBuffer.allocate(k.dw * k.dh * 2).order(ByteOrder.LITTLE_ENDIAN);
                for (short s : k.depth) db.putShort(s);
                put(z, dn, db.array());
                JSONObject dj = new JSONObject();
                dj.put("file", dn); dj.put("width", k.dw); dj.put("height", k.dh);
                dj.put("fx", k.dfx); dj.put("fy", k.dfy); dj.put("cx", k.dcx); dj.put("cy", k.dcy);
                p.put("depth", dj);
            }
            photos.put(p);
        }
        put(z, "photos.json", new JSONObject().put("photos", photos).toString(1).getBytes("UTF-8"));

        // manifest
        JSONObject m = new JSONObject();
        m.put("format", "rmsc"); m.put("version", 1);
        m.put("name", d.name);
        if (SyncInfo.app != null) { // shared scans: which room this is, and which version
            SyncInfo si = SyncInfo.load(SyncInfo.app, dir);
            m.put("id", si.id); m.put("revision", si.revision); m.put("revisionBy", si.revisionBy);
        }
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
        m.put("created", iso.format(new Date(d.created > 0 ? d.created : System.currentTimeMillis())));
        m.put("modified", iso.format(new Date(new File(dir, "meta.json").lastModified())));
        JSONObject src = new JSONObject().put("platform", "android").put("app", "RoomScape").put("appVersion", appVersion);
        if (appBuild > 0) src.put("appBuild", String.valueOf(appBuild));
        m.put("source", src.put("device", Build.MODEL));
        m.put("floorY", 0);
        put(z, "android/shift.json", new JSONObject().put("floorY", floorY).toString().getBytes("UTF-8"));
        float[] outline = null; // walls from the app's room model: [x0, z0, x1, z1, height] each, floor at 0
        try { File of = new File(dir, "outline.json"); if (of.exists() && of.lastModified() >= new File(dir, "points.bin").lastModified()) outline = vecf(new JSONArray(text(of))); } catch (Exception ignored) { }
        if (d.ceiling != null && d.ceiling.length >= 2) m.put("ceilingY", d.ceiling[1] - floorY);
        else if (outline != null && outline.length >= 5) m.put("ceilingY", outline[4]);
        if (d.n > 0) m.put("bounds", new JSONObject().put("min", vec(mn)).put("max", vec(mx)));
        // The surface mesh: the sending phone's when this scan came with one, else the one this app built for Photo.
        File mesh = new File(dir, "rmsc-extra/mesh.ply");
        File own = new File(dir, "surface.ply");
        if (!mesh.exists() && own.exists() && own.lastModified() >= new File(dir, "points.bin").lastModified()) mesh = own;
        int tris = mesh.exists() ? putPlyShifted(z, mesh, "mesh.ply", -floorY) : 0;
        JSONObject counts = new JSONObject().put("points", d.n).put("photos", d.kfs.size());
        if (tris > 0) counts.put("triangles", tris);
        m.put("counts", counts);
        JSONObject room = new JSONObject();
        JSONArray cs = new JSONArray(), ws = new JSONArray();
        for (float[] c : d.corners) cs.put(vec(new float[]{c[0], c[1] - floorY, c[2]}));
        if (d.corners.isEmpty() && outline != null) {
            for (int i = 0; i + 4 < outline.length; i += 5) {
                cs.put(vec(new float[]{outline[i], 0, outline[i + 1]}));
                ws.put(vec(new float[]{outline[i], outline[i + 1], outline[i + 2], outline[i + 3], outline[i + 4]}));
            }
        }
        room.put("corners", cs);
        if (ws.length() > 0) room.put("walls", ws);
        if (d.lux > 0) room.put("lux", d.lux);
        m.put("room", room);
        // Flat surfaces found on this phone (Planes: "flat colour = plane"), floor at 0 like the rest.
        try {
            File pf = new File(dir, "planes.json");
            if (pf.exists() && pf.lastModified() >= new File(dir, "points.bin").lastModified()) {
                JSONArray ps = new JSONArray(text(pf)), out = new JSONArray();
                for (int i = 0; i < ps.length(); i++) {
                    JSONObject p = ps.getJSONObject(i);
                    JSONArray nrm = p.getJSONArray("normal"), pmin = p.getJSONArray("min"), pmax = p.getJSONArray("max");
                    p.put("d", p.getDouble("d") - nrm.getDouble(1) * floorY);
                    pmin.put(1, pmin.getDouble(1) - floorY); pmax.put(1, pmax.getDouble(1) - floorY);
                    out.put(p);
                }
                if (out.length() > 0) m.put("planes", out);
            }
        } catch (Exception ignored) { }
        JSONArray objs = sharedObjects(d, floorY);
        if (objs.length() > 0) m.put("objects", objs);
        JSONArray shared = sharedOutlets(d.outlets, floorY);
        if (shared.length() > 0) m.put("outlets", shared);
        // floor area (optional, docs/rmsc-format.md "floorArea"): per room, from the walls or set by hand; outline x, z
        // need no floor shift
        File fa = new File(dir, "floor-area.json");
        if (fa.exists()) try { m.put("floorArea", new JSONObject(text(fa))); } catch (Exception ignored) { }
        put(z, "manifest.json", m.toString(1).getBytes("UTF-8"));

        // thumbnail and the Android extras
        copyIf(z, new File(dir, "thumb.jpg"), "thumb.jpg");
        copyIf(z, new File(dir, "meta.json"), "android/meta.json");
        copyIf(z, new File(dir, "splats.bin"), "android/splats.bin");
        copyIf(z, new File(dir, "path.bin"), "android/path.bin");
        copyIf(z, new File(dir, "doorways.json"), "android/doorways.json");
        copyIf(z, new File(dir, "measurements.json"), "android/measurements.json");
        // Flat tiles (optional, docs/rmsc-format.md "tiles.json"): the shared form with each tile's corners in the
        // package's frame (floor at 0) and its square in tiles.png; Android's own form (its planes and grid, scan world)
        // in android/tiles.json for a lossless re-import.
        File tj = new File(dir, "tiles.json"), tp = new File(dir, "tiles.png");
        if (tj.exists() && tp.exists()) try {
            String raw = text(tj), tilesShared = sharedTiles(raw, floorY);
            if (tilesShared != null) {
                put(z, "tiles.json", tilesShared.getBytes("UTF-8"));
                copyIf(z, tp, "tiles.png");
                put(z, "android/tiles.json", raw.getBytes("UTF-8"));
            }
        } catch (Exception ignored) { }
        // Parts of the package this app doesn't use (an iPhone's ios/ folder) go back out unchanged (mesh.ply is above).
        putTree(z, new File(dir, "rmsc-extra"), "");
        putDiagnostics(z, dir);
    }

    /** Writes `name` into Downloads/RoomScape through `fill`, returns a shareable uri (as `export`). */
    static Uri toDownloads(Context ctx, String name, String mime, ZipFill fill) throws Exception {
        Uri uri; OutputStream raw;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RoomScape");
            uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception("could not create the file in Downloads");
            raw = ctx.getContentResolver().openOutputStream(uri);
        } else {
            File out = new File(ctx.getExternalFilesDir(null), name);
            uri = Uri.fromFile(out);
            raw = new FileOutputStream(out);
        }
        try (OutputStream o = new BufferedOutputStream(raw, 1 << 16)) { fill.write(o); }
        return uri;
    }
    interface ZipFill { void write(OutputStream o) throws Exception; }

    /** Diagnostics only (no room): for when a scan never finished. The latest scan's logs too, if there is one. */
    static Uri exportDiagnostics(Context ctx, File latestScan) throws Exception {
        String name = "roomscape-diagnostics-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(new java.util.Date()) + ".zip";
        return toDownloads(ctx, name, "application/zip", o -> {
            try (ZipOutputStream z = new ZipOutputStream(o)) { putDiagnostics(z, latestScan); }
        });
    }

    /** The scan's own video (recording.mp4), as <name>-video.mp4. */
    static Uri exportVideo(Context ctx, File dir, String base) throws Exception { return exportVideo(ctx, dir, base, "recording.mp4", "-video.mp4"); }

    /** A video in the scan folder (recording.mp4 = the camera; screen.mp4 = what the screen showed), to Downloads. */
    static Uri exportVideo(Context ctx, File dir, String base, String file, String suffix) throws Exception {
        File f = new File(dir, file);
        if (!f.exists()) throw new Exception("this scan has no video");
        return toDownloads(ctx, safeName(base) + suffix, "video/mp4", o -> {
            try (InputStream in = new FileInputStream(f)) { byte[] buf = new byte[1 << 16]; int r; while ((r = in.read(buf)) > 0) o.write(buf, 0, r); }
        });
    }

    /** The app's files folder (set at start-up), for the diagnostics that aren't per scan. */
    static volatile File appFiles;

    /** android/diagnostics/: what's needed to understand a scan from another phone without a cable (rk 10 Nov): the
     *  scan's perf and event logs, the device (model, Android, ARCore, app, GPU and its limits), the GPU self-test and
     *  the last crash. No location, no account. Optional: other readers ignore it (docs/rmsc-format.md). */
    static void putDiagnostics(ZipOutputStream z, File dir) throws Exception {
        if (dir != null) {
            copyIf(z, new File(dir, "perf-scan.jsonl"), "android/diagnostics/perf-scan.jsonl");
            copyIf(z, new File(dir, "perf-scan.summary.json"), "android/diagnostics/perf-scan.summary.json");
            copyIf(z, new File(dir, "scan-log.txt"), "android/diagnostics/scan-log.txt");
            copyIf(z, new File(dir, "removed-points.bin"), "android/removed-points.bin");
            copyIf(z, new File(dir, "guessed-wall.json"), "android/guessed-wall.json");
            copyIf(z, new File(dir, "dot-confidence.bin"), "android/dot-confidence.bin");   // per dot: ARCore depth confidence and closest distance   // one plain wall guessed by the app (generated, not scanned)   // points the clean-up at Done took out (kept, data is gold)
        }
        File f = appFiles;
        if (f != null) {
            copyIf(z, new File(f, "device.json"), "android/diagnostics/device.json");
            copyIf(z, new File(f, "gpu-info.txt"), "android/diagnostics/gpu-info.txt");
            copyIf(z, new File(f, "gpu-selftest.txt"), "android/diagnostics/gpu-selftest.txt");
            copyIf(z, new File(f, "crash-last.txt"), "android/diagnostics/crash-last.txt");
            copyIf(z, new File(f, "app-log.txt"), "android/diagnostics/app-log.txt");
            copyIf(z, new File(f, "app-log-history.txt"), "android/diagnostics/app-log-history.txt");   // earlier sessions
        }
    }

    /** Copies a binary PLY mesh into the zip with every vertex moved up by dy; returns its triangle count. */
    static int putPlyShifted(ZipOutputStream z, File f, String name, float dy) throws Exception {
        byte[] all = bytes(f);
        put(z, name, shiftPly(all, dy));
        return plyFaces(all);
    }

    static int plyFaces(byte[] all) {
        String head = new String(all, 0, Math.min(all.length, 4096), java.nio.charset.StandardCharsets.US_ASCII);
        for (String line : head.split("\n")) {
            if (line.startsWith("end_header")) break;
            String[] w = line.trim().split(" ");
            if (w.length == 3 && w[0].equals("element") && w[1].equals("face")) return Integer.parseInt(w[2]);
        }
        return 0;
    }

    /** The PLY with its vertices moved up by dy (float x y z, then uchar red green blue), or unchanged for any other layout. */
    static byte[] shiftPly(byte[] all, float dy) {
        if (dy == 0) return all;
        String head = new String(all, 0, Math.min(all.length, 4096), java.nio.charset.StandardCharsets.US_ASCII);
        int end = head.indexOf("end_header\n");
        if (end < 0 || !head.contains("binary_little_endian")) return all;
        int nv = 0; java.util.List<String> props = new java.util.ArrayList<>(); boolean inVertex = false;
        for (String line : head.substring(0, end).split("\n")) {
            String[] w = line.trim().split(" ");
            if (w[0].equals("element")) { inVertex = w.length > 1 && w[1].equals("vertex"); if (inVertex) nv = Integer.parseInt(w[2]); }
            else if (w[0].equals("property") && inVertex) props.add(w[1] + " " + w[w.length - 1]);
        }
        if (!props.equals(Arrays.asList("float x", "float y", "float z", "uchar red", "uchar green", "uchar blue"))) return all;
        byte[] out = all.clone();
        ByteBuffer b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        int base = end + "end_header\n".length();
        for (int i = 0; i < nv; i++) { int at = base + i * 15 + 4; if (at + 4 > out.length) break; b.putFloat(at, b.getFloat(at) + dy); }
        return out;
    }

    private static void putTree(ZipOutputStream z, File f, String path) throws Exception {
        if (!f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) {
                if (path.isEmpty() && k.getName().equals("mesh.ply")) continue; // written (floor-shifted) by write()
                putTree(z, k, path.isEmpty() ? k.getName() : path + "/" + k.getName());
            }
        } else {
            put(z, path, bytes(f));
        }
    }

    /** The scan as a .rmsc file (for syncing to a nearby phone). Background thread. */
    static File exportToFile(Context ctx, File dir) throws Exception {
        ScanStore.Data d = ScanStore.load(dir);
        File out = new File(ctx.getCacheDir(), "sync_" + System.currentTimeMillis() + ".rmsc");
        try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out), 1 << 16))) {
            String[] v = appVersion(ctx);
            write(z, d, dir, v[0], Integer.parseInt(v[1]));
        }
        return out;
    }

    /** {"0.1", build}: the version without the build number glued on (versionName is "0.1.<build>"), and the build. */
    static String[] appVersion(Context ctx) {
        try {
            android.content.pm.PackageInfo p = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            String name = p.versionName == null ? "?" : p.versionName;
            String build = String.valueOf(p.versionCode);
            if (name.endsWith("." + build)) name = name.substring(0, name.length() - build.length() - 1);
            return new String[]{name, build};
        } catch (Exception e) { return new String[]{"?", "0"}; }
    }

    static final int NEW = 0, UPDATED = 1, UNCHANGED = 2, CONFLICT = 3;

    /** What an import did: the scan folder, and NEW / UPDATED / UNCHANGED / CONFLICT. */
    static final class Result {
        final File dir; final int outcome;
        Result(File dir, int outcome) { this.dir = dir; this.outcome = outcome; }
    }

    /**
     * Imports a .rmsc. A package whose id matches a scan here updates that scan in place when it's a newer revision
     * (if both sides changed it, this phone's version is kept as a copy first: data is never lost); anything else
     * becomes a new scan. peerId / peerName: the phone it came from (the scan is then shared with it), or null.
     */
    static Result importFrom(Context ctx, Uri uri, String peerId, String peerName) throws Exception {
        JSONObject m = peekManifest(ctx, uri);
        String id = m.optString("id", "");
        int rev = m.optInt("revision", 1);
        String by = m.optString("revisionBy", peerId == null ? "" : peerId);
        File existing = id.isEmpty() ? null : SyncInfo.find(ctx, id);
        int outcome = NEW;
        SyncInfo prev = null;
        boolean repair = false;
        JSONObject counts = m.optJSONObject("counts");
        boolean incomingHas = counts != null && (counts.optInt("points") > 0 || counts.optInt("photos") > 0);
        if (existing != null) {
            prev = SyncInfo.load(ctx, existing);
            int lp = 0, lf = 0;
            try { JSONObject lm = new JSONObject(text(new File(existing, "meta.json"))); lp = lm.optInt("points"); lf = lm.optInt("photos"); } catch (Exception ignored) { }
            boolean localHas = lp > 0 || lf > 0;
            // Data is gold: a package without points or photos never replaces a scan that has them (only its name
            // and version are taken), and a scan that lost its data takes a full package back, whatever its version.
            if (localHas && !incomingHas) {
                if (rev > prev.revision) {
                    String nm = m.optString("name", "");
                    if (!nm.isEmpty() && !nm.startsWith("Room ")) ScanStore.rename(existing, nm);
                    prev = SyncInfo.load(ctx, existing);
                    prev.revision = rev; prev.revisionBy = by; prev.syncedRevision = rev;
                }
                if (peerId != null) { prev.shared = true; prev.peers.put(peerId, peerName); }
                SyncInfo.save(existing, prev);
                return new Result(existing, UNCHANGED);
            }
            repair = !localHas && incomingHas;
            if (rev <= prev.revision && !repair) {
                if (peerId != null) SyncInfo.markShared(ctx, existing, peerId, peerName, rev);
                return new Result(existing, UNCHANGED);
            }
            if (!repair && prev.revision > prev.syncedRevision && prev.syncedRevision > 0) {
                outcome = CONFLICT;
                File copy = new File(existing.getParentFile(), "scan_" + System.currentTimeMillis());
                ScanStore.copyTree(existing, copy);
                //noinspection ResultOfMethodCallIgnored
                SyncInfo.file(copy).delete(); // a new identity
                String nm = ScanStore.load(existing).name;
                ScanStore.rename(copy, nm + " (copy from " + android.os.Build.MODEL + ")");
            } else {
                outcome = UPDATED;
            }
        }
        File out = importFrom(ctx, uri);
        if (existing != null) {
            // Swap the new version into the old folder; the old one stays until the swap worked.
            // The replaced version is kept as a backup (the last one per scan), outside the scan list.
            File backups = new File(ctx.getFilesDir(), "scan-backups");
            backups.mkdirs();
            File old = new File(backups, existing.getName());
            deleteRec(old);
            if (!existing.renameTo(old)) throw new Exception("could not update the scan");
            if (!out.renameTo(existing)) { old.renameTo(existing); throw new Exception("could not update the scan"); }
            out = existing;
        }
        if (existing != null) mergeOutlets(new File(ctx.getFilesDir(), "scan-backups/" + out.getName()), out);
        if (existing != null) mergeObjects(new File(ctx.getFilesDir(), "scan-backups/" + out.getName()), out);
        SyncInfo si = SyncInfo.load(ctx, out);
        if (!id.isEmpty()) si.id = id;
        // A repaired scan gets a revision above both, so phones with the empty version take it back.
        si.revision = repair ? Math.max(rev, prev == null ? 0 : prev.revision) + 1 : rev;
        si.revisionBy = repair ? SyncInfo.deviceId(ctx) : by;
        si.syncedRevision = repair ? 0 : rev;
        si.modified = System.currentTimeMillis();
        if (prev != null) { for (java.util.Map.Entry<String, String> e : prev.peers.entrySet()) si.peers.putIfAbsent(e.getKey(), e.getValue()); si.shared |= prev.shared; }
        if (peerId != null) { si.shared = true; si.peers.put(peerId, peerName); }
        SyncInfo.save(out, si);
        return new Result(out, outcome);
    }

    // ---------------------------------------------------------------- objects (shared manifest field)

    /**
     * The scan's objects as the manifest's shared "objects" (docs/rmsc-format.md): this app's objects (edited or not)
     * plus Claude's found items not among them, floor at 0. Android's heading A is the shared yaw's opposite.
     */
    static JSONArray sharedObjects(ScanStore.Data d, float floorY) {
        JSONArray out = new JSONArray();
        try {
            JSONArray objs = d.objects == null ? new JSONArray() : new JSONArray(d.objects);
            for (int i = 0; i < objs.length(); i++) {
                JSONObject j = objs.getJSONObject(i);
                out.put(sharedObject(j.optString("id", java.util.UUID.randomUUID().toString()), j.optString("name", "Object"),
                        j.optBoolean("catalog") ? "catalogue" : "android",
                        (float) j.getDouble("cx"), (float) j.getDouble("cz"), (float) j.getDouble("cosA"), (float) j.getDouble("sinA"),
                        (float) j.getDouble("hu"), (float) j.getDouble("hv"), (float) j.getDouble("y0"), (float) j.getDouble("y1"),
                        (float) j.optDouble("ox", 0), (float) j.optDouble("oz", 0), (float) j.optDouble("turn", 0), j.optBoolean("removed"), floorY));
            }
            if (d.items != null) {
                RoomItems ri = new RoomItems(); ri.fromJson(d.items);
                for (RoomItems.Item it : ri.snapshot()) {
                    boolean known = false;
                    for (int i = 0; i < objs.length() && !known; i++) {
                        JSONObject e = objs.getJSONObject(i);
                        known = !e.optBoolean("catalog") && Math.hypot(e.getDouble("cx") - it.cx, e.getDouble("cz") - it.cz) < 0.25 && Math.abs(e.getDouble("y1") - it.y1) < 0.4;
                    }
                    if (!known) out.put(sharedObject(SceneObjects.itemId(it), it.name, "claude", it.cx, it.cz, it.cosA, it.sinA, it.hu, it.hv, it.y0, it.y1, 0, 0, 0, false, floorY));
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static JSONObject sharedObject(String id, String name, String source, float cx, float cz, float cosA, float sinA,
                                           float hu, float hv, float y0, float y1, float ox, float oz, float turn, boolean removed, float floorY) throws Exception {
        float yaw0 = (float) -Math.atan2(sinA, cosA), yaw = yaw0 - turn, cy = (y0 + y1) / 2 - floorY;
        return new JSONObject().put("id", id).put("name", name).put("source", source)
                .put("center", new JSONArray().put(cx + ox).put(cy).put(cz + oz))
                .put("size", new JSONArray().put(2 * hu).put(y1 - y0).put(2 * hv))
                .put("yaw", yaw)
                .put("original", new JSONObject().put("center", new JSONArray().put(cx).put(cy).put(cz)).put("yaw", yaw0))
                .put("removed", removed).put("moved", ox != 0 || oz != 0 || turn != 0);
    }

    /**
     * The manifest's shared "objects" as this app's object JSON, moved up by dy. Entries without an "original" are
     * where they always were. `own`: this app's own objects from the package (android/meta.json), whose catalogue
     * items' points are kept. Own objects missing from the shared list are kept too (nothing is dropped).
     */
    static String androidObjects(JSONArray shared, float dy, JSONArray own) {
        try {
            JSONArray out = new JSONArray();
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            for (int i = 0; shared != null && i < shared.length(); i++) {
                JSONObject s = shared.getJSONObject(i);
                JSONArray c = s.getJSONArray("center"), sz = s.getJSONArray("size");
                JSONObject orig = s.optJSONObject("original");
                JSONArray oc = orig != null ? orig.getJSONArray("center") : c;
                float yaw = (float) s.optDouble("yaw", 0), yaw0 = orig != null ? (float) orig.optDouble("yaw", yaw) : yaw;
                float h = (float) sz.getDouble(1);
                float cy = (float) oc.getDouble(1);
                String id = s.optString("id", java.util.UUID.randomUUID().toString());
                JSONObject j = new JSONObject().put("id", id).put("name", s.optString("name", "Object"))
                        .put("cx", oc.getDouble(0)).put("cz", oc.getDouble(2))
                        .put("cosA", Math.cos(yaw0)).put("sinA", -Math.sin(yaw0))
                        .put("hu", sz.getDouble(0) / 2).put("hv", sz.getDouble(2) / 2)
                        .put("y0", cy - h / 2 + dy).put("y1", cy + h / 2 + dy).put("floor", cy - h / 2 < 0.1)
                        .put("ox", c.getDouble(0) - oc.getDouble(0)).put("oz", c.getDouble(2) - oc.getDouble(2))
                        .put("turn", -(yaw - yaw0)).put("removed", s.optBoolean("removed", false));
                for (int k = 0; own != null && k < own.length(); k++) {
                    JSONObject e = own.getJSONObject(k);
                    if (id.equals(e.optString("id")) && e.optBoolean("catalog")) { j.put("catalog", true); j.put("pts", e.getString("pts")); }
                }
                seen.add(id);
                out.put(j);
            }
            for (int k = 0; own != null && k < own.length(); k++) {
                JSONObject e = own.getJSONObject(k);
                if (!seen.contains(e.optString("id", ""))) out.put(e);
            }
            return out.length() == 0 ? null : out.toString();
        } catch (Exception e) { return own == null ? null : own.toString(); }
    }

    /** After a sync update: objects this phone had that the new version lacks are kept (never dropped). */
    static void mergeObjects(File previousDir, File dir) {
        try {
            File pm = new File(previousDir, "meta.json"), nm = new File(dir, "meta.json");
            if (!pm.exists() || !nm.exists()) return;
            JSONArray old = new JSONObject(text(pm)).optJSONArray("objects");
            if (old == null || old.length() == 0) return;
            JSONObject meta = new JSONObject(text(nm));
            JSONArray cur = meta.optJSONArray("objects");
            if (cur == null) cur = new JSONArray();
            java.util.HashSet<String> ids = new java.util.HashSet<>();
            for (int j = 0; j < cur.length(); j++) ids.add(cur.getJSONObject(j).optString("id", ""));
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.getJSONObject(i);
                if (!ids.contains(o.optString("id", "?"))) cur.put(o);
            }
            meta.put("objects", cur);
            try (FileOutputStream f = new FileOutputStream(nm)) { f.write(meta.toString(1).getBytes("UTF-8")); }
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- power outlets (shared manifest field)

    /** Android's outlets ([x, y, z, views, sockets, type] each) as the manifest's shared "outlets" list. */
    static JSONArray sharedOutlets(String androidJson, float floorY) {
        JSONArray out = new JSONArray();
        if (androidJson == null) return out;
        try {
            JSONArray a = new JSONArray(androidJson);
            for (int i = 0; i < a.length(); i++) {
                JSONArray r = a.getJSONArray(i);
                float x = (float) r.getDouble(0), y = (float) r.getDouble(1), zz = (float) r.getDouble(2);
                String type = r.optString(5, "wall socket");
                String kind = type.contains("strip") ? "power strip" : type.contains("floor") ? "floor socket" : "outlet";
                out.put(new JSONObject().put("kind", kind).put("count", r.optInt(4, 1))
                        .put("position", new JSONArray().put(x).put(y).put(zz)).put("heightAboveFloor", y - floorY)
                        .put("views", r.optInt(3, 1)).put("source", "claude"));
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** The manifest's shared "outlets" as Android's own outlet JSON, or null when there are none. */
    static String androidOutlets(JSONArray shared) {
        if (shared == null || shared.length() == 0) return null;
        try {
            JSONArray out = new JSONArray();
            for (int i = 0; i < shared.length(); i++) {
                JSONObject o = shared.getJSONObject(i);
                JSONArray p = o.getJSONArray("position");
                String kind = o.optString("kind", "outlet");
                String type = kind.contains("strip") ? "power strip" : kind.contains("floor") ? "floor socket" : "wall socket";
                out.put(new JSONArray().put(p.getDouble(0)).put(p.getDouble(1)).put(p.getDouble(2))
                        .put(o.optInt("views", 1)).put(o.optInt("count", 1)).put(type));
            }
            return out.toString();
        } catch (Exception e) { return null; }
    }

    /**
     * After an update replaced a scan: keep the outlets the previous version had. Both lists are merged by position
     * (within 15 cm is the same outlet), so an update without outlets never erases them.
     */
    static void mergeOutlets(File previousDir, File dir) {
        try {
            File pm = new File(previousDir, "meta.json"), nm = new File(dir, "meta.json");
            if (!pm.exists() || !nm.exists()) return;
            JSONArray old = new JSONObject(text(pm)).optJSONArray("outlets");
            if (old == null || old.length() == 0) return;
            JSONObject meta = new JSONObject(text(nm));
            JSONArray cur = meta.optJSONArray("outlets");
            if (cur == null) cur = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONArray o = old.getJSONArray(i);
                boolean same = false;
                for (int j = 0; j < cur.length() && !same; j++) {
                    JSONArray c = cur.getJSONArray(j);
                    double dx = o.getDouble(0) - c.getDouble(0), dy = o.getDouble(1) - c.getDouble(1), dz = o.getDouble(2) - c.getDouble(2);
                    same = dx * dx + dy * dy + dz * dz < 0.15 * 0.15;
                }
                if (!same) cur.put(o);
            }
            meta.put("outlets", cur);
            if (cur.length() > 0) meta.put("roomPass", Math.max(2, meta.optInt("roomPass", 0)));
            try (FileOutputStream f = new FileOutputStream(nm)) { f.write(meta.toString(1).getBytes("UTF-8")); }
        } catch (Exception ignored) { }
    }

    private static JSONObject peekManifest(Context ctx, Uri uri) throws Exception {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(new BufferedInputStream(in, 1 << 16))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (!"manifest.json".equals(e.getName())) continue;
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int r;
                while ((r = z.read(buf)) > 0) b.write(buf, 0, r);
                return new JSONObject(b.toString("UTF-8"));
            }
        }
        throw new Exception("this isn't a RoomScape .rmsc file (no manifest.json)");
    }

    /** The floor height: the tapped/estimated floor mark if there is one, else the 2nd percentile of the points. */
    static float floorY(ScanStore.Data d) {
        if (d.floor != null && d.floor.length >= 2) return d.floor[1];
        if (d.n == 0) return 0;
        int step = Math.max(1, d.n / 20000), k = 0;
        float[] ys = new float[(d.n + step - 1) / step];
        for (int i = 0; i < d.n && k < ys.length; i += step) ys[k++] = d.pos[i * 3 + 1];
        Arrays.sort(ys, 0, k);
        return ys[Math.min(k - 1, Math.max(0, k / 50))];
    }

    // ---------------------------------------------------------------- import

    /** Reads a .rmsc from uri into a new saved scan and returns its folder. Background thread. */
    static File importFrom(Context ctx, Uri uri) throws Exception {
        File tmp = new File(ctx.getCacheDir(), "rmsc_import_" + System.currentTimeMillis());
        deleteRec(tmp); tmp.mkdirs();
        try {
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new Exception("could not open the file");
                unzip(in, tmp);
            }
            File mf = new File(tmp, "manifest.json");
            if (!mf.exists()) throw new Exception("this isn't a RoomScape .rmsc file (no manifest.json)");
            JSONObject m = new JSONObject(text(mf));
            if (!"rmsc".equals(m.optString("format"))) throw new Exception("this isn't a RoomScape .rmsc file");
            if (m.optInt("version", 1) > 1) throw new Exception("this file is from a newer RoomScape version; update the app");

            ScanStore.Data d = new ScanStore.Data();
            d.name = m.optString("name", "Imported room");
            if (d.name.isEmpty()) d.name = "Imported room";
            d.created = System.currentTimeMillis();
            readPly(new File(tmp, "points.ply"), d);
            // A package from this app: put the floor back where the android/ extras (marks, splats, path) have it.
            float unshift = 0;
            File sh = new File(tmp, "android/shift.json");
            if (sh.exists()) try { unshift = (float) new JSONObject(text(sh)).optDouble("floorY", 0); } catch (Exception ignored) { }
            if (unshift != 0) for (int i = 0; i < d.n; i++) d.pos[i * 3 + 1] += unshift;

            File pj = new File(tmp, "photos.json");
            if (pj.exists()) {
                JSONArray ps = new JSONObject(text(pj)).optJSONArray("photos");
                for (int i = 0; ps != null && i < ps.length(); i++) {
                    JSONObject p = ps.getJSONObject(i);
                    File jf = new File(tmp, p.getString("file"));
                    if (!jf.exists()) continue;
                    ScanStore.Kf k = new ScanStore.Kf();
                    k.jpg = bytes(jf);
                    float[] pose = vecf(p.getJSONArray("pose"));
                    if (pose.length != 16) continue;
                    System.arraycopy(pose, 0, k.pose, 0, 16);
                    k.pose[13] += unshift;
                    Matrix.invertM(k.view, 0, k.pose, 0);
                    k.w = (float) p.getDouble("width"); k.h = (float) p.getDouble("height");
                    k.fx = (float) p.getDouble("fx"); k.fy = (float) p.getDouble("fy");
                    k.cx = (float) p.getDouble("cx"); k.cy = (float) p.getDouble("cy");
                    k.closeUp = p.optBoolean("closeUp", false);
                    JSONObject dj = p.optJSONObject("depth");
                    if (dj != null) {
                        File df = new File(tmp, dj.getString("file"));
                        int dw = dj.getInt("width"), dh = dj.getInt("height");
                        if (df.exists() && df.length() >= (long) dw * dh * 2) {
                            ByteBuffer db = ByteBuffer.wrap(bytes(df)).order(ByteOrder.LITTLE_ENDIAN);
                            k.depth = new short[dw * dh];
                            for (int j = 0; j < k.depth.length; j++) k.depth[j] = db.getShort();
                            k.dw = dw; k.dh = dh;
                            k.dfx = (float) dj.getDouble("fx"); k.dfy = (float) dj.getDouble("fy");
                            k.dcx = (float) dj.getDouble("cx"); k.dcy = (float) dj.getDouble("cy");
                        }
                    }
                    d.kfs.add(k);
                }
            }

            // room marks: from Android's own meta when present (lossless), else from the shared manifest
            File am = new File(tmp, "android/meta.json");
            JSONObject a = am.exists() ? new JSONObject(text(am)) : null;
            if (a != null) {
                d.corners = list(a.optJSONArray("corners")); d.walls = list(a.optJSONArray("walls"));
                d.floor = a.has("floor") ? vecf(a.getJSONArray("floor")) : null;
                d.ceiling = a.has("ceiling") ? vecf(a.getJSONArray("ceiling")) : null;
                d.lux = (float) a.optDouble("lux", 0);
                JSONArray oa = a.optJSONArray("objects"); d.objects = oa == null ? null : oa.toString();
                // The shared list is the latest (it may have been edited on another phone): it wins, by id.
                JSONArray so = m.optJSONArray("objects");
                if (so != null && so.length() > 0 && so.getJSONObject(0).has("id")) d.objects = androidObjects(so, unshift, oa);
                JSONArray ol = a.optJSONArray("outlets"); d.outlets = ol == null ? null : ol.toString();
                JSONArray it = a.optJSONArray("items"); d.items = it == null ? null : it.toString();
                d.hall = a.optBoolean("hall2", false); d.outletsChecked = a.optInt("roomPass", 0) >= 2;
            } else {
                JSONObject room = m.optJSONObject("room");
                if (room != null) {
                    d.corners = list(room.optJSONArray("corners"));
                    d.lux = (float) room.optDouble("lux", 0);
                }
                float cx = 0, cz = 0;
                JSONObject b = m.optJSONObject("bounds");
                if (b != null) {
                    float[] lo = vecf(b.getJSONArray("min")), hi = vecf(b.getJSONArray("max"));
                    cx = (lo[0] + hi[0]) / 2; cz = (lo[2] + hi[2]) / 2;
                }
                if (m.has("floorY")) d.floor = new float[]{cx, (float) m.getDouble("floorY"), cz};
                String ol = androidOutlets(m.optJSONArray("outlets"));
                if (ol != null) { d.outlets = ol; d.outletsChecked = true; }
                d.objects = androidObjects(m.optJSONArray("objects"), unshift, null);
                if (m.has("ceilingY")) d.ceiling = new float[]{cx, (float) m.getDouble("ceilingY"), cz};
            }
            File th = new File(tmp, "thumb.jpg");
            if (th.exists()) d.thumb = BitmapFactory.decodeFile(th.getAbsolutePath());
            File ap = new File(tmp, "android/path.bin");
            if (ap.exists()) try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(ap)))) {
                int n = in.readInt(); d.path = new float[n]; for (int i = 0; i < n; i++) d.path[i] = in.readFloat();
            } catch (Exception ignored) { d.path = new float[0]; }

            File out = ScanStore.save(ctx, null, d);
            File ios = new File(tmp, "ios");
            if (ios.exists()) ScanStore.copyTree(ios, new File(out, "rmsc-extra/ios")); // kept for the iPhone
            // The sending phone's own mesh (an iPhone's live-scan mesh): shown in Photo instead of a rebuilt one, and
            // written back out on export. Its manifest too, for the room's size, walls, windows and doors.
            File mp = new File(tmp, "mesh.ply");
            if (mp.exists()) {
                new File(out, "rmsc-extra").mkdirs();
                try (FileOutputStream o = new FileOutputStream(new File(out, "rmsc-extra/mesh.ply"))) { o.write(shiftPly(bytes(mp), unshift)); }
            }
            ScanStore.copyTree(new File(tmp, "manifest.json"), new File(out, "source-manifest.json"));
            File sp = new File(tmp, "android/splats.bin");
            if (sp.exists()) copy(sp, new File(out, "splats.bin"));
            // flat tiles from this app: its own form, in the scan world the points were put back into (above)
            File atj = new File(tmp, "android/tiles.json"), atp = new File(tmp, "tiles.png");
            if (atj.exists() && atp.exists()) { copy(atj, new File(out, "tiles.json")); copy(atp, new File(out, "tiles.png")); }
            if (d.thumb != null) d.thumb.recycle();
            return out;
        } finally {
            deleteRec(tmp);
        }
    }

    /** Binary little-endian PLY vertices: x y z (float or double) and red green blue (uchar), other properties skipped. */
    static void readPly(File f, ScanStore.Data d) throws Exception {
        if (!f.exists()) { d.n = 0; d.pos = new float[0]; d.col = new byte[0]; return; }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 16))) {
            StringBuilder line = new StringBuilder();
            int n = 0; boolean inVertex = false, le = true;
            java.util.List<String[]> props = new java.util.ArrayList<>();
            while (true) {
                line.setLength(0);
                int c;
                while ((c = in.read()) != -1 && c != '\n') if (c != '\r') line.append((char) c);
                if (c == -1) throw new Exception("points.ply has no end_header");
                String[] t = line.toString().trim().split("\\s+");
                if (t.length == 0) continue;
                if (t[0].equals("format")) {
                    if (t[1].equals("ascii")) throw new Exception("ascii points.ply isn't supported");
                    le = t[1].equals("binary_little_endian");
                } else if (t[0].equals("element")) {
                    inVertex = t[1].equals("vertex");
                    if (inVertex) n = Integer.parseInt(t[2]);
                } else if (t[0].equals("property") && inVertex) {
                    if (t[1].equals("list")) throw new Exception("points.ply vertex lists aren't supported");
                    props.add(new String[]{t[1], t[2]});
                } else if (t[0].equals("end_header")) break;
            }
            int stride = 0; int[] off = new int[props.size()], size = new int[props.size()];
            for (int i = 0; i < props.size(); i++) { off[i] = stride; size[i] = typeSize(props.get(i)[0]); stride += size[i]; }
            int[] slot = {-1, -1, -1, -1, -1, -1}; // x y z r g b
            String[] want = {"x", "y", "z", "red", "green", "blue"};
            for (int i = 0; i < props.size(); i++) for (int w = 0; w < 6; w++) if (props.get(i)[1].equals(want[w])) slot[w] = i;
            if (slot[0] < 0 || slot[1] < 0 || slot[2] < 0) throw new Exception("points.ply has no x y z");
            d.n = n; d.pos = new float[n * 3]; d.col = new byte[n * 3];
            byte[] rec = new byte[stride];
            ByteBuffer bb = ByteBuffer.wrap(rec).order(le ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
            for (int i = 0; i < n; i++) {
                in.readFully(rec);
                for (int a = 0; a < 3; a++) d.pos[i * 3 + a] = num(bb, off[slot[a]], props.get(slot[a])[0]);
                for (int a = 0; a < 3; a++) {
                    int s = slot[3 + a];
                    d.col[i * 3 + a] = s < 0 ? (byte) 200 : props.get(s)[0].matches("u?char|u?int8") ? rec[off[s]] : (byte) Math.max(0, Math.min(255, Math.round(num(bb, off[s], props.get(s)[0]) * (props.get(s)[0].startsWith("float") ? 255 : 1))));
                }
            }
        }
    }

    private static int typeSize(String t) {
        switch (t) {
            case "char": case "uchar": case "int8": case "uint8": return 1;
            case "short": case "ushort": case "int16": case "uint16": return 2;
            case "int": case "uint": case "int32": case "uint32": case "float": case "float32": return 4;
            case "double": case "float64": return 8;
            default: throw new IllegalArgumentException("unknown ply type " + t);
        }
    }

    private static float num(ByteBuffer b, int o, String t) {
        switch (t) {
            case "float": case "float32": return b.getFloat(o);
            case "double": case "float64": return (float) b.getDouble(o);
            case "char": case "int8": return b.get(o);
            case "uchar": case "uint8": return b.get(o) & 255;
            case "short": case "int16": return b.getShort(o);
            case "ushort": case "uint16": return b.getShort(o) & 0xFFFF;
            default: return b.getInt(o);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void unzip(InputStream raw, File to) throws Exception {
        String root = to.getCanonicalPath() + File.separator;
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(raw, 1 << 16))) {
            ZipEntry e; byte[] buf = new byte[1 << 16];
            while ((e = z.getNextEntry()) != null) {
                File f = new File(to, e.getName());
                if (!f.getCanonicalPath().startsWith(root)) continue; // never write outside the temp folder
                if (e.isDirectory()) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(f)) { int r; while ((r = z.read(buf)) > 0) o.write(buf, 0, r); }
            }
        }
    }

    private static void put(ZipOutputStream z, String name, byte[] data) throws Exception {
        z.putNextEntry(new ZipEntry(name)); z.write(data); z.closeEntry();
    }

    private static void copyIf(ZipOutputStream z, File f, String name) throws Exception { if (f.exists()) put(z, name, bytes(f)); }

    private static void copy(File a, File b) throws Exception {
        try (InputStream in = new FileInputStream(a); OutputStream o = new FileOutputStream(b)) {
            byte[] buf = new byte[1 << 16]; int r; while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
        }
    }

    private static byte[] bytes(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream((int) Math.max(32, f.length()));
            byte[] buf = new byte[1 << 16]; int r; while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
            return o.toByteArray();
        }
    }

    private static String text(File f) throws Exception { return new String(bytes(f), "UTF-8"); }

    private static JSONArray vec(float[] v) throws Exception { JSONArray a = new JSONArray(); for (float x : v) a.put((double) x); return a; }

    private static float[] vecf(JSONArray a) { float[] v = new float[a.length()]; for (int i = 0; i < v.length; i++) v[i] = (float) a.optDouble(i); return v; }

    private static java.util.List<float[]> list(JSONArray a) {
        java.util.List<float[]> l = new java.util.ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) { JSONArray v = a.optJSONArray(i); if (v != null) l.add(vecf(v)); }
        return l;
    }

    private static String safeName(String s) {
        String t = s.replaceAll("[\\\\/:*?\"<>|\\n\\r\\t]", " ").trim();
        if (t.isEmpty()) t = "room";
        return t.length() > 60 ? t.substring(0, 60).trim() : t;
    }

    private static void deleteRec(File f) {
        File[] c = f.listFiles();
        if (c != null) for (File x : c) deleteRec(x);
        f.delete();
    }
}
