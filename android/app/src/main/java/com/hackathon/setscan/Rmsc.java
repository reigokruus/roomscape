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
            String ver = "?";
            try { ver = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; } catch (Exception ignored) { }
            write(z, d, dir, ver);
        }
        return uri;
    }

    static void write(ZipOutputStream z, ScanStore.Data d, File dir, String appVersion) throws Exception {
        float floorY = floorY(d);
        float[] mn = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, mx = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < d.n; i++) for (int a = 0; a < 3; a++) { float v = d.pos[i * 3 + a]; if (v < mn[a]) mn[a] = v; if (v > mx[a]) mx[a] = v; }

        // points.ply
        ByteArrayOutputStream hdr = new ByteArrayOutputStream();
        hdr.write(("ply\nformat binary_little_endian 1.0\ncomment RoomScape Android\nelement vertex " + d.n
                + "\nproperty float x\nproperty float y\nproperty float z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n").getBytes("US-ASCII"));
        z.putNextEntry(new ZipEntry("points.ply"));
        z.write(hdr.toByteArray());
        ByteBuffer bb = ByteBuffer.allocate(15 * 4096).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < d.n; i++) {
            bb.putFloat(d.pos[i * 3]).putFloat(d.pos[i * 3 + 1]).putFloat(d.pos[i * 3 + 2]);
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
            p.put("pose", vec(k.pose));
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
        m.put("source", new JSONObject().put("platform", "android").put("app", "RoomScape")
                .put("appVersion", appVersion).put("device", Build.MODEL));
        m.put("floorY", floorY);
        if (d.ceiling != null && d.ceiling.length >= 2) m.put("ceilingY", d.ceiling[1]);
        if (d.n > 0) m.put("bounds", new JSONObject().put("min", vec(mn)).put("max", vec(mx)));
        m.put("counts", new JSONObject().put("points", d.n).put("photos", d.kfs.size()));
        JSONObject room = new JSONObject();
        JSONArray cs = new JSONArray(); for (float[] c : d.corners) cs.put(vec(c));
        room.put("corners", cs);
        if (d.lux > 0) room.put("lux", d.lux);
        m.put("room", room);
        JSONArray shared = sharedOutlets(d.outlets, floorY);
        if (shared.length() > 0) m.put("outlets", shared);
        put(z, "manifest.json", m.toString(1).getBytes("UTF-8"));

        // thumbnail and the Android extras
        copyIf(z, new File(dir, "thumb.jpg"), "thumb.jpg");
        copyIf(z, new File(dir, "meta.json"), "android/meta.json");
        copyIf(z, new File(dir, "splats.bin"), "android/splats.bin");
        copyIf(z, new File(dir, "path.bin"), "android/path.bin");
        // Parts of the package this app doesn't use (an iPhone's ios/ folder) go back out unchanged.
        putTree(z, new File(dir, "rmsc-extra"), "");
    }

    private static void putTree(ZipOutputStream z, File f, String path) throws Exception {
        if (!f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) putTree(z, k, path.isEmpty() ? k.getName() : path + "/" + k.getName());
        } else {
            put(z, path, bytes(f));
        }
    }

    /** The scan as a .rmsc file (for syncing to a nearby phone). Background thread. */
    static File exportToFile(Context ctx, File dir) throws Exception {
        ScanStore.Data d = ScanStore.load(dir);
        File out = new File(ctx.getCacheDir(), "sync_" + System.currentTimeMillis() + ".rmsc");
        try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out), 1 << 16))) {
            String ver = "?";
            try { ver = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; } catch (Exception ignored) { }
            write(z, d, dir, ver);
        }
        return out;
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
            File sp = new File(tmp, "android/splats.bin");
            if (sp.exists()) copy(sp, new File(out, "splats.bin"));
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
