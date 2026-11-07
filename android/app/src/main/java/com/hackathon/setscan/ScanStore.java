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
        List<Kf> kfs = new ArrayList<>();
        float[] path = new float[0];
        List<float[]> corners = new ArrayList<>(), walls = new ArrayList<>();
        float[] floor, ceiling;
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
        for (File d : dirs) {
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
        try (DataOutputStream o = out(new File(tmp, "path.bin"))) {
            o.writeInt(d.path.length);
            for (float v : d.path) o.writeFloat(v);
        }
        if (d.thumb != null) try (FileOutputStream f = new FileOutputStream(new File(tmp, "thumb.jpg"))) { d.thumb.compress(Bitmap.CompressFormat.JPEG, 85, f); }
        JSONObject m = new JSONObject();
        long now = System.currentTimeMillis();
        m.put("name", d.name); m.put("created", d.created > 0 ? d.created : now); m.put("modified", now);
        m.put("points", d.n); m.put("photos", d.kfs.size());
        m.put("corners", arr(d.corners)); m.put("walls", arr(d.walls));
        if (d.floor != null) m.put("floor", vec(d.floor));
        if (d.ceiling != null) m.put("ceiling", vec(d.ceiling));
        try (FileOutputStream f = new FileOutputStream(new File(tmp, "meta.json"))) { f.write(m.toString(1).getBytes("UTF-8")); }
        // swap in the new version
        File old = new File(dir.getParentFile(), dir.getName() + ".old");
        deleteRec(old);
        if (dir.exists() && !dir.renameTo(old)) throw new Exception("could not replace the old save");
        if (!tmp.renameTo(dir)) throw new Exception("could not write the save");
        deleteRec(old);
        return dir;
    }

    /** Everything but the photos' pixels decoded (photos stay JPEG bytes). Background thread. */
    static Data load(File dir) throws Exception {
        Data d = new Data();
        JSONObject m = new JSONObject(readText(new File(dir, "meta.json")));
        d.name = m.optString("name"); d.created = m.optLong("created");
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
        return d;
    }

    static List<float[]> loadCorners(File dir) {
        try { return list(new JSONObject(readText(new File(dir, "meta.json"))).optJSONArray("corners")); } catch (Exception e) { return new ArrayList<>(); }
    }

    static void rename(File dir, String name) throws Exception {
        File f = new File(dir, "meta.json");
        JSONObject m = new JSONObject(readText(f));
        m.put("name", name);
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(m.toString(1).getBytes("UTF-8")); }
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
