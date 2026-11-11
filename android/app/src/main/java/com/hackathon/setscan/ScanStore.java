package com.hackathon.setscan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Saved scans in the app's own storage: files/scans/<id>/
 *   meta.json   name, dates, counts, the room marks (corners, walls, floor, ceiling)
 *   points.bin  the points (x, y, z float + r, g, b byte)
 *   kf.bin      the photos' cameras (+ low-res depth for occlusion), kf/NN.jpg the photos
 *   path.bin    where the phone went
 *   thumb.jpg   the view at the moment of saving
 */
final class ScanStore {
    static final class Kf {
        byte[] jpg;
        float[] view = new float[16], pose = new float[16];
        float fx, fy, cx, cy, w, h;
        boolean closeUp;
        short[] depth; int dw, dh; float dfx, dfy, dcx, dcy;
    }

    static final class Data {
        String name = "";
        long created;
        float[] pos; byte[] col; int n;
        byte[] seg; // per point: which stretch of tracking it came from (0, +1 after each loss); null = all 0
        List<Kf> kfs = new ArrayList<>();
        float[] path = new float[0];
        List<float[]> corners = new ArrayList<>(), walls = new ArrayList<>();
        float[] floor, ceiling;
        float[] splPos, splNrm; byte[] splCol; int splN; // the trained splats as last shown (so reopening shows the improved model)
        String objects; // SceneObjects edits as a JSON array, or null
        String outlets, items; boolean outletsChecked, hall; // hall: the demo's back-wall stretch was applied // + objects Claude found (RoomItems JSON) // power outlets (Outlets JSON) and whether the photos were searched
        float lux; // room light measured from the camera's exposure, 0 = unknown
        Bitmap thumb;
    }

    static final class Entry {
        File dir; String name; long modified; int points, photos, corners;
        File thumb() { return new File(dir, "thumb.jpg"); }
    }

    static File root(Context c) {
        File f = new File(c.getFilesDir(), "scans");
        f.mkdirs();
        return f;
    }

    static List<Entry> list(Context c) {
        List<Entry> out = new ArrayList<>();
        File[] dirs = root(c).listFiles();
        if (dirs == null) return out;
        // A save cut off between its two renames leaves scan_X.old without scan_X: put it back (data is gold).
        for (File d : dirs) {
            String n = d.getName();
            if (n.endsWith(".old")) { File real = new File(d.getParentFile(), n.substring(0, n.length() - 4)); if (!real.exists()) d.renameTo(real); }
        }
        dirs = root(c).listFiles();
        if (dirs == null) return out;
        for (File d : dirs) {
            if (d.getName().endsWith(".saving") || d.getName().endsWith(".old")) continue;   // half-written or replaced saves
            try {
                JSONObject m = new JSONObject(readText(new File(d, "meta.json")));
                Entry e = new Entry();
                e.dir = d; e.name = m.optString("name", d.getName()); e.modified = m.optLong("modified", d.lastModified());
                e.points = m.optInt("points"); e.photos = m.optInt("photos");
                JSONArray cs = m.optJSONArray("corners"); e.corners = cs == null ? 0 : cs.length();
                out.add(e);
            } catch (Exception ignored) { }
        }
        out.sort((a, b) -> Long.compare(b.modified, a.modified));
        return out;
    }

    /** Writes the scan into dir (a new folder when null) and returns the folder. Background thread. */
    static File save(Context c, File dir, Data d) throws Exception {
        if (dir == null) dir = new File(root(c), "scan_" + System.currentTimeMillis());
        // What this save changes, for syncing shared scans: a rename, or new points or photos (Continue scan).
        String before = null;
        boolean samePoints = false;
        try {
            JSONObject om = new JSONObject(readText(new File(dir, "meta.json")));
            JSONArray oo = om.optJSONArray("outlets");
            JSONArray ob = om.optJSONArray("objects");
            before = om.optString("name") + "|" + om.optInt("points") + "|" + om.optInt("photos") + "|" + (oo == null ? 0 : oo.length())
                    + "|" + (ob == null ? "" : ob.toString()).hashCode();
            samePoints = om.optInt("points") == d.n;
            // Data is gold: never save an empty scan over one that has points or photos (a half-loaded view).
            if (d.n == 0 && d.kfs.isEmpty() && (om.optInt("points") > 0 || om.optInt("photos") > 0))
                throw new IllegalStateException("not saving an empty scan over " + om.optInt("points") + " points");
            // Nor a scan whose photos are gone (a lost GL context clears the live photo list) over one that has them.
            if (d.kfs.isEmpty() && om.optInt("photos") > 0 && d.n > 0)
                throw new IllegalStateException("not saving a scan without its " + om.optInt("photos") + " photos over the saved one");
        } catch (IllegalStateException e) { throw e; } catch (Exception ignored) { }
        File tmp = new File(dir.getParentFile(), dir.getName() + ".saving");
        deleteRec(tmp);
        new File(tmp, "kf").mkdirs();
        try (DataOutputStream o = out(new File(tmp, "points.bin"))) {
            o.writeInt(d.n);
            for (int i = 0; i < d.n; i++) {
                o.writeFloat(d.pos[i * 3]); o.writeFloat(d.pos[i * 3 + 1]); o.writeFloat(d.pos[i * 3 + 2]);
                o.writeByte(d.col[i * 3]); o.writeByte(d.col[i * 3 + 1]); o.writeByte(d.col[i * 3 + 2]);
            }
        }
        // Which tracking stretch each point came from (rk 10 Nov: layers after a tracking loss can be told apart).
        if (d.seg != null && d.seg.length >= d.n) {
            boolean any = false; for (int i = 0; i < d.n; i++) if (d.seg[i] != 0) { any = true; break; }
            if (any) try (DataOutputStream o = out(new File(tmp, "segments.bin"))) { o.writeInt(d.n); o.write(d.seg, 0, d.n); }
        }
        try (DataOutputStream o = out(new File(tmp, "kf.bin"))) {
            o.writeInt(d.kfs.size());
            for (int i = 0; i < d.kfs.size(); i++) {
                Kf k = d.kfs.get(i);
                for (float v : k.view) o.writeFloat(v);
                for (float v : k.pose) o.writeFloat(v);
                o.writeFloat(k.fx); o.writeFloat(k.fy); o.writeFloat(k.cx); o.writeFloat(k.cy); o.writeFloat(k.w); o.writeFloat(k.h);
                o.writeBoolean(k.closeUp);
                int dn = k.depth == null ? 0 : k.dw * k.dh;
                o.writeInt(k.depth == null ? 0 : k.dw); o.writeInt(k.depth == null ? 0 : k.dh);
                o.writeFloat(k.dfx); o.writeFloat(k.dfy); o.writeFloat(k.dcx); o.writeFloat(k.dcy);
                for (int j = 0; j < dn; j++) o.writeShort(k.depth[j]);
                try (FileOutputStream f = new FileOutputStream(new File(tmp, String.format(java.util.Locale.US, "kf/%03d.jpg", i)))) { f.write(k.jpg); }
            }
        }
        if (d.splN > 0) try (DataOutputStream o = out(new File(tmp, "splats.bin"))) {
            o.writeInt(d.splN);
            for (int i = 0; i < d.splN * 3; i++) o.writeFloat(d.splPos[i]);
            for (int i = 0; i < d.splN * 4; i++) o.writeFloat(d.splNrm[i]);
            o.write(d.splCol, 0, d.splN * 4);
        }
        try (DataOutputStream o = out(new File(tmp, "path.bin"))) {
            o.writeInt(d.path.length);
            for (float v : d.path) o.writeFloat(v);
        }
        if (d.thumb != null) try (FileOutputStream f = new FileOutputStream(new File(tmp, "thumb.jpg"))) { d.thumb.compress(Bitmap.CompressFormat.JPEG, 85, f); }
        JSONObject m = new JSONObject();
        long now = System.currentTimeMillis();
        m.put("name", d.name); m.put("created", d.created > 0 ? d.created : now); m.put("modified", now);
        m.put("points", d.n); m.put("photos", d.kfs.size());
        if (d.lux > 0) m.put("lux", d.lux);
        if (d.objects != null) m.put("objects", new JSONArray(d.objects));
        if (d.outlets != null) m.put("outlets", new JSONArray(d.outlets));
        if (d.items != null) m.put("items", new JSONArray(d.items));
        if (d.hall) m.put("hall2", true); // back wall placed + rough room filled
        if (d.outletsChecked) m.put("roomPass", 2); // outlets + objects searched
        m.put("corners", arr(d.corners)); m.put("walls", arr(d.walls));
        if (d.floor != null) m.put("floor", vec(d.floor));
        if (d.ceiling != null) m.put("ceiling", vec(d.ceiling));
        try (FileOutputStream f = new FileOutputStream(new File(tmp, "meta.json"))) { f.write(m.toString(1).getBytes("UTF-8")); }
        // Kept across saves: the sync identity, and package parts this app doesn't use (an iPhone's ios/ folder).
        // Also the files written into the scan folder after a save (measurements, doorways, height, logs and diagnostics):
        // a later save (rename, Continue scan, an edit) used to leave them behind.
        for (String keep : new String[]{"sync.json", "rmsc-extra", "splats.bin", "source-manifest.json",
                "measurements.json", "scan-log.txt", "perf-scan.jsonl", "perf-scan.summary.json",
                "depth-pairs.bin", "feature-heights.bin", "live-mesh.bin", "events-depth.bin", "pose-track.bin", "tiles.json", "tiles.png", "floor-edits.json", "floor-area.json", "removed-points.bin", "guessed-wall.json", "dot-confidence.bin"}) {
            File from = new File(dir, keep), to = new File(tmp, keep);
            if (from.exists() && !to.exists()) copyTree(from, to);
        }
        // Built from the points (the Photo view's surface, room outline, flat planes): kept while the points are the
        // same; after Continue scan they're stale, and are built again.
        if (samePoints) for (String keep : new String[]{"surface.ply", "outline.json", "planes.json", "doorways.json", "height.json"}) {
            File from = new File(dir, keep), to = new File(tmp, keep);
            if (from.exists() && !to.exists()) copyTree(from, to);
        }
        // The raw recording (big): moved, not copied, last (a failed save before this point leaves it where it was).
        File recFrom = new File(dir, "recording.mp4"), recTo = new File(tmp, "recording.mp4");
        if (recFrom.exists() && !recTo.exists() && !recFrom.renameTo(recTo)) copyTree(recFrom, recTo);
        File scrFrom = new File(dir, "screen.mp4"), scrTo = new File(tmp, "screen.mp4");   // the screen video (ScreenVideo)
        if (scrFrom.exists() && !scrTo.exists() && !scrFrom.renameTo(scrTo)) copyTree(scrFrom, scrTo);
        // swap in the new version
        File old = new File(dir.getParentFile(), dir.getName() + ".old");
        deleteRec(old);
        if (dir.exists() && !dir.renameTo(old)) {
            if (recTo.exists() && !recFrom.exists()) recTo.renameTo(recFrom);   // put the recording back
            if (scrTo.exists() && !scrFrom.exists()) scrTo.renameTo(scrFrom);
            throw new Exception("could not replace the old save");
        }
        if (!tmp.renameTo(dir)) {
            old.renameTo(dir);   // keep the old save (it has the recording back only if the move above was undone)
            File r2 = new File(dir, "recording.mp4");
            if (recTo.exists() && !r2.exists()) recTo.renameTo(r2);
            File s2 = new File(dir, "screen.mp4"); if (scrTo.exists() && !s2.exists()) scrTo.renameTo(s2);
            throw new Exception("could not write the save");
        }
        deleteRec(old);
        int outletsAfter = 0;
        try { if (d.outlets != null) outletsAfter = new JSONArray(d.outlets).length(); } catch (Exception ignored) { }
        String after = d.name + "|" + d.n + "|" + d.kfs.size() + "|" + outletsAfter // found outlets count as a change too
                + "|" + (d.objects == null ? "" : new JSONArray(d.objects).toString()).hashCode(); // and object edits
        if (before != null && !before.equals(after) && SyncInfo.file(dir).exists()) SyncInfo.touch(c, dir);
        return dir;
    }

    /** Found power outlets into the saved scan's meta.json; a change syncs to the phones a shared scan is with. */
    static void writeOutlets(File dir, String outletsJson) throws Exception {
        File f = new File(dir, "meta.json");
        JSONObject m = new JSONObject(readText(f));
        JSONArray now = new JSONArray(outletsJson), before = m.optJSONArray("outlets");
        m.put("outlets", now);
        m.put("roomPass", Math.max(2, m.optInt("roomPass", 0)));
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(m.toString(1).getBytes("UTF-8")); }
        if (!now.toString().equals(before == null ? "[]" : before.toString()) && SyncInfo.file(dir).exists()) SyncInfo.touch(dir);
    }

    static void copyTree(File from, File to) throws Exception {
        if (from.isDirectory()) {
            to.mkdirs();
            File[] kids = from.listFiles();
            if (kids != null) for (File k : kids) copyTree(k, new File(to, k.getName()));
        } else {
            java.nio.file.Files.copy(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Everything but the photos' pixels decoded (photos stay JPEG bytes). Background thread. */
    static Data load(File dir) throws Exception {
        Data d = new Data();
        JSONObject m = new JSONObject(readText(new File(dir, "meta.json")));
        d.name = m.optString("name"); d.created = m.optLong("created");
        d.lux = (float) m.optDouble("lux", 0);
        JSONArray oa = m.optJSONArray("objects");
        d.objects = oa == null ? null : oa.toString();
        JSONArray ol = m.optJSONArray("outlets");
        d.outlets = ol == null ? null : ol.toString();
        JSONArray it = m.optJSONArray("items");
        d.items = it == null ? null : it.toString();
        d.hall = m.optBoolean("hall2", false); // scans from the first demo (stretch only) get the rough room once
        d.outletsChecked = m.optInt("roomPass", 0) >= 2; // older scans (outlets only) get the object pass once
        d.corners = list(m.optJSONArray("corners")); d.walls = list(m.optJSONArray("walls"));
        d.floor = m.has("floor") ? vec(m.getJSONArray("floor")) : null;
        d.ceiling = m.has("ceiling") ? vec(m.getJSONArray("ceiling")) : null;
        try (DataInputStream in = in(new File(dir, "points.bin"))) {
            d.n = in.readInt(); d.pos = new float[d.n * 3]; d.col = new byte[d.n * 3];
            for (int i = 0; i < d.n; i++) {
                d.pos[i * 3] = in.readFloat(); d.pos[i * 3 + 1] = in.readFloat(); d.pos[i * 3 + 2] = in.readFloat();
                d.col[i * 3] = in.readByte(); d.col[i * 3 + 1] = in.readByte(); d.col[i * 3 + 2] = in.readByte();
            }
        }
        File segf = new File(dir, "segments.bin");
        if (segf.exists()) try (DataInputStream in = in(segf)) {
            int n = in.readInt();
            if (n == d.n) { d.seg = new byte[n]; in.readFully(d.seg); }
        } catch (Exception ignored) { }
        try (DataInputStream in = in(new File(dir, "kf.bin"))) {
            int nk = in.readInt();
            for (int i = 0; i < nk; i++) {
                Kf k = new Kf();
                for (int j = 0; j < 16; j++) k.view[j] = in.readFloat();
                for (int j = 0; j < 16; j++) k.pose[j] = in.readFloat();
                k.fx = in.readFloat(); k.fy = in.readFloat(); k.cx = in.readFloat(); k.cy = in.readFloat(); k.w = in.readFloat(); k.h = in.readFloat();
                k.closeUp = in.readBoolean();
                k.dw = in.readInt(); k.dh = in.readInt();
                k.dfx = in.readFloat(); k.dfy = in.readFloat(); k.dcx = in.readFloat(); k.dcy = in.readFloat();
                if (k.dw * k.dh > 0) { k.depth = new short[k.dw * k.dh]; for (int j = 0; j < k.depth.length; j++) k.depth[j] = in.readShort(); }
                File jf = new File(dir, String.format(java.util.Locale.US, "kf/%03d.jpg", i));
                k.jpg = readBytes(jf);
                d.kfs.add(k);
            }
        }
        File pf = new File(dir, "path.bin");
        if (pf.exists()) try (DataInputStream in = in(pf)) { int n = in.readInt(); d.path = new float[n]; for (int i = 0; i < n; i++) d.path[i] = in.readFloat(); }
        File sf = new File(dir, "splats.bin");
        if (sf.exists()) try (DataInputStream in = in(sf)) {
            int n = in.readInt(); float[] p = new float[n * 3], q = new float[n * 4]; byte[] c = new byte[n * 4];
            for (int i = 0; i < n * 3; i++) p[i] = in.readFloat();
            for (int i = 0; i < n * 4; i++) q[i] = in.readFloat();
            in.readFully(c);
            d.splPos = p; d.splNrm = q; d.splCol = c; d.splN = n;
        } catch (Exception ignored) { d.splN = 0; } // an unreadable splat file just means the splats get rebuilt
        return d;
    }

    static List<float[]> loadCorners(File dir) {
        try { return list(new JSONObject(readText(new File(dir, "meta.json"))).optJSONArray("corners")); } catch (Exception e) { return new ArrayList<>(); }
    }

    static void rename(File dir, String name) throws Exception {
        File f = new File(dir, "meta.json");
        JSONObject m = new JSONObject(readText(f));
        boolean changed = !name.equals(m.optString("name"));
        m.put("name", name);
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(m.toString(1).getBytes("UTF-8")); }
        if (changed && SyncInfo.file(dir).exists()) SyncInfo.touch(dir); // syncs to the phones it's shared with
    }

    static void delete(File dir) { deleteRec(dir); }

    static Bitmap thumb(Entry e) {
        File f = e.thumb();
        return f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }

    static byte[] jpeg(Bitmap b, int q) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, q, o);
        return o.toByteArray();
    }

    // ---- helpers ----
    private static DataOutputStream out(File f) throws Exception { return new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f), 1 << 16)); }
    private static DataInputStream in(File f) throws Exception { return new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 16)); }

    private static byte[] readBytes(File f) throws Exception {
        byte[] b = new byte[(int) f.length()];
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) { in.readFully(b); }
        return b;
    }

    private static String readText(File f) throws Exception { return new String(readBytes(f), "UTF-8"); }

    private static JSONArray vec(float[] v) throws Exception { JSONArray a = new JSONArray(); for (float x : v) a.put((double) x); return a; }
    private static float[] vec(JSONArray a) { float[] v = new float[a.length()]; for (int i = 0; i < v.length; i++) v[i] = (float) a.optDouble(i); return v; }
    private static JSONArray arr(List<float[]> l) throws Exception { JSONArray a = new JSONArray(); for (float[] v : l) a.put(vec(v)); return a; }
    private static List<float[]> list(JSONArray a) { List<float[]> l = new ArrayList<>(); if (a != null) for (int i = 0; i < a.length(); i++) l.add(vec(a.optJSONArray(i))); return l; }

    private static void deleteRec(File f) {
        if (!f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRec(k);
        f.delete();
    }
}
