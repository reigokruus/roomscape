package com.hackathon.setscan;

import android.content.Context;
import android.opengl.GLES20;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Core feature (rk, 7 Nov): the objects the scan found can be removed from the room, moved, put back,
 * and saved to a catalogue as a simple sized 3D model (an oriented box plus its own scanned points)
 * that can be placed into any scan.
 *
 * Removing is done in the shaders: the object's original box goes into the scan's hide list (max 16),
 * so the room's points and splats inside it disappear. A moved or catalogue object is drawn from its own
 * copy of the points, at its new place.
 */
final class SceneObjects {
    static final class Obj {
        String name;
        // the original box in the scan (world): centre, heading, half sizes, bottom/top
        float cx, cz, cosA = 1, sinA, hu, hv, y0, y1;
        boolean onFloor;
        // where it is now: offset from the original centre and extra turn (radians)
        float ox, oz, turn;
        boolean removed;      // hidden from the scene (still listed, can be put back)
        boolean fromCatalog;  // brought in from the catalogue: has no original place in this scan
        float[] pts;          // own points relative to (cx, y0, cz), in world axes
        byte[] cols;          // rgb per point
        int n;
        FloatBuffer gpu; ByteBuffer gpuCol; // drawing copies, made on first draw

        float w() { return 2 * hu; }
        float d() { return 2 * hv; }
        float h() { return y1 - y0; }
        boolean moved() { return ox != 0 || oz != 0 || turn != 0; }
        String size() { return String.format(Locale.US, "%.0f × %.0f × %.0f cm", w() * 100, d() * 100, h() * 100); }
        /** Is its original spot hidden in the room scan? */
        boolean hidesOriginal() { return !fromCatalog && (removed || moved()); }
        /** Drawn from its own points? */
        boolean drawnSeparately() { return !removed && (fromCatalog || moved()); }
    }

    final List<Obj> list = new ArrayList<>();
    volatile int selected = -1;
    volatile boolean active; // object mode: boxes and labels show, taps select
    boolean edited;          // the user changed something (don't rebuild from detection)

    void clear() { list.clear(); selected = -1; edited = false; }

    Obj sel() { int s = selected; return s >= 0 && s < list.size() ? list.get(s) : null; }

    /** Build the list from the room's detected boxes (keeps the user's edits if there are any). */
    void fromRoom(List<RoomModel.Box> boxes, FloatBuffer pos, ByteBuffer col, int count, float floorY) {
        if (edited && !list.isEmpty()) return;
        list.clear(); selected = -1;
        for (RoomModel.Box b : boxes) {
            if ("noise".equals(b.kind)) continue;
            if (b.voxels < 25) continue;
            Obj o = new Obj();
            o.name = nice(b);
            o.cx = b.cx; o.cz = b.cz; o.cosA = b.cosA; o.sinA = b.sinA; o.hu = b.hu; o.hv = b.hv; o.y0 = b.y0; o.y1 = b.y1;
            o.onFloor = b.parent == null && b.y0 <= floorY + 0.05f;
            extract(o, pos, col, count);
            if (o.n < 30) continue;
            list.add(o);
        }
    }

    /**
     * Objects Claude pointed out (RoomItems). The user's edits are kept, and each found object that isn't one of them
     * is added, so untouched objects come back after a reopen too.
     */
    void fromItems(List<RoomItems.Item> items, FloatBuffer pos, ByteBuffer col, int count) {
        if (!edited) { list.clear(); selected = -1; }
        for (RoomItems.Item it : items) {
            boolean known = false;
            for (Obj e : list) if (!e.fromCatalog && Math.hypot(e.cx - it.cx, e.cz - it.cz) < 0.25f && Math.abs(e.y1 - it.y1) < 0.4f) { known = true; break; }
            if (known) continue;
            Obj o = new Obj();
            o.name = it.name;
            o.cx = it.cx; o.cz = it.cz; o.cosA = it.cosA; o.sinA = it.sinA; o.hu = it.hu; o.hv = it.hv; o.y0 = it.y0; o.y1 = it.y1;
            o.onFloor = it.onFloor;
            extract(o, pos, col, count);
            if (o.n < 30) continue;
            list.add(o);
        }
    }

    private static String nice(RoomModel.Box b) {
        String t = b.title();
        return t.isEmpty() ? "Object" : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    /** Lowest y that still belongs to the object (leave the floor / table top it stands on in the room). */
    private static float cutY(Obj o) { return o.y0 + (o.onFloor ? 0.03f : 0.015f); }

    private static final float PAD = 0.03f;

    private static boolean inside(Obj o, float x, float y, float z) {
        float dx = x - o.cx, dz = z - o.cz;
        float u = dx * o.cosA + dz * o.sinA, v = -dx * o.sinA + dz * o.cosA;
        return Math.abs(u) < o.hu + PAD && Math.abs(v) < o.hv + PAD && y > cutY(o) && y < o.y1 + PAD;
    }

    private static void extract(Obj o, FloatBuffer pos, ByteBuffer col, int count) {
        int n = 0;
        float[] p = new float[Math.min(count, 200000) * 3]; byte[] c = new byte[p.length];
        for (int i = 0; i < count && n * 3 < p.length; i++) {
            float x = pos.get(i * 3), y = pos.get(i * 3 + 1), z = pos.get(i * 3 + 2);
            if ((col.get(i * 4 + 3) & 0xFF) == 0) continue; // cleared point
            if (!inside(o, x, y, z)) continue;
            p[n * 3] = x - o.cx; p[n * 3 + 1] = y - o.y0; p[n * 3 + 2] = z - o.cz;
            c[n * 3] = col.get(i * 4); c[n * 3 + 1] = col.get(i * 4 + 1); c[n * 3 + 2] = col.get(i * 4 + 2);
            n++;
        }
        o.n = n; o.pts = java.util.Arrays.copyOf(p, n * 3); o.cols = java.util.Arrays.copyOf(c, n * 3);
    }

    /** Put the hidden spots into the scan shaders' hide list. Returns the new count. */
    int addHides(float[] a, float[] b, int n) {
        for (Obj o : list) {
            if (n >= 16) break;
            if (!o.hidesOriginal()) continue;
            int i = n * 4;
            a[i] = o.cx; a[i + 1] = o.cz; a[i + 2] = o.cosA; a[i + 3] = o.sinA;
            b[i] = o.hu + PAD; b[i + 1] = o.hv + PAD; b[i + 2] = cutY(o); b[i + 3] = o.y1 + PAD;
            n++;
        }
        return n;
    }

    int hiddenCount() { int k = 0; for (Obj o : list) if (o.hidesOriginal()) k++; return k; }

    // ---------------- picking and moving ----------------

    /** Screen tap -> the object whose box covers it (nearest to the viewer wins), or -1. */
    int pick(float[] mvp, int width, int height, float tx, float ty) {
        int best = -1; float bestW = Float.MAX_VALUE;
        for (int k = 0; k < list.size(); k++) {
            Obj o = list.get(k);
            if (o.removed) continue;
            float[] c = corners(o);
            float x0 = 1e9f, x1 = -1e9f, y0 = 1e9f, y1 = -1e9f, wsum = 0; int vis = 0;
            for (int i = 0; i < 8; i++) {
                float x = c[i * 3], y = c[i * 3 + 1], z = c[i * 3 + 2];
                float cw = mvp[3] * x + mvp[7] * y + mvp[11] * z + mvp[15];
                if (cw <= 0.05f) continue;
                float sx = ((mvp[0] * x + mvp[4] * y + mvp[8] * z + mvp[12]) / cw + 1f) * 0.5f * width;
                float sy = (1f - (mvp[1] * x + mvp[5] * y + mvp[9] * z + mvp[13]) / cw) * 0.5f * height;
                x0 = Math.min(x0, sx); x1 = Math.max(x1, sx); y0 = Math.min(y0, sy); y1 = Math.max(y1, sy); wsum += cw; vis++;
            }
            if (vis < 4) continue;
            float slop = 12f * height / 1000f;
            if (tx < x0 - slop || tx > x1 + slop || ty < y0 - slop || ty > y1 + slop) continue;
            // prefer small boxes (a can on a table) over the big one around them, then the nearer one
            float score = wsum / vis * (float) Math.sqrt(Math.max(1f, (x1 - x0) * (y1 - y0)));
            if (score < bestW) { bestW = score; best = k; }
        }
        return best;
    }

    /** The 8 corners of the object's box where it is now. */
    static float[] corners(Obj o) {
        float ca = (float) Math.cos(o.turn), sa = (float) Math.sin(o.turn);
        float c = o.cosA * ca - o.sinA * sa, s = o.sinA * ca + o.cosA * sa;
        float cx = o.cx + o.ox, cz = o.cz + o.oz;
        float[] out = new float[24];
        for (int i = 0; i < 8; i++) {
            float su = (i & 1) != 0 ? 1 : -1, sv = (i & 2) != 0 ? 1 : -1;
            float y = (i & 4) != 0 ? o.y1 : o.y0;
            out[i * 3] = cx + su * o.hu * c - sv * o.hv * s;
            out[i * 3 + 1] = y;
            out[i * 3 + 2] = cz + su * o.hu * s + sv * o.hv * c;
        }
        return out;
    }

    // ---------------- drawing ----------------

    private int ptProg, lineProg;
    private FloatBuffer scratch = ByteBuffer.allocateDirect(64 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    void draw(float[] mvp, int height, float pxPerMeter) {
        if (list.isEmpty()) return;
        if (ptProg == 0) {
            ptProg = ScanRenderer.program(
                    "uniform mat4 uMvp; uniform vec4 uT; uniform float uSize; attribute vec3 aPos; attribute vec3 aCol; varying vec3 vCol;" +
                    "void main(){ vec3 p = vec3(aPos.x * uT.z - aPos.z * uT.w, aPos.y, aPos.x * uT.w + aPos.z * uT.z);" +
                    "  gl_Position = uMvp * vec4(p.x + uT.x, p.y, p.z + uT.y, 1.0); gl_PointSize = clamp(uSize / max(gl_Position.w, 0.05), 2.0, 48.0); vCol = aCol; }",
                    "precision mediump float; varying vec3 vCol;" +
                    "void main(){ vec2 d = gl_PointCoord * 2.0 - 1.0; if (dot(d, d) > 1.0) discard; gl_FragColor = vec4(vCol, 1.0); }");
            lineProg = ScanRenderer.program(
                    "uniform mat4 uMvp; attribute vec3 aPos; void main(){ gl_Position = uMvp * vec4(aPos, 1.0); }",
                    "precision mediump float; uniform vec4 uColor; void main(){ gl_FragColor = uColor; }");
        }
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        for (Obj o : list) if (o.drawnSeparately() && o.n > 0) drawPoints(mvp, o, pxPerMeter);
        float dens = height / 1000f;
        // gear placed from the catalogue has no scanned points: always show it as a see-through box of its real size
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);
        for (Obj o : list) {
            if (o.removed || o.n > 0) continue;
            float[] c = corners(o);
            tris(mvp, faces(c), GEAR_FILL);
            GLES20.glLineWidth(3f * dens);
            lines(mvp, edges(c), GEAR_EDGE);
        }
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
        if (!active && selected < 0) return;
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        for (int k = 0; k < list.size(); k++) {
            Obj o = list.get(k);
            if (o.removed) continue;
            boolean s = k == selected;
            GLES20.glLineWidth((s ? 5f : 2f) * dens);
            lines(mvp, edges(corners(o)), s ? SEL : (active ? BOX : FAINT));
        }
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private static final float[] GEAR_FILL = {0.25f, 0.75f, 0.9f, 0.18f}, GEAR_EDGE = {0.35f, 0.85f, 1f, 0.95f};

    private static float[] faces(float[] c) {
        int[] f = {0, 1, 3, 0, 3, 2, 4, 5, 7, 4, 7, 6, 0, 1, 5, 0, 5, 4, 2, 3, 7, 2, 7, 6, 0, 2, 6, 0, 6, 4, 1, 3, 7, 1, 7, 5};
        float[] out = new float[f.length * 3];
        for (int i = 0; i < f.length; i++) { out[i * 3] = c[f[i] * 3]; out[i * 3 + 1] = c[f[i] * 3 + 1]; out[i * 3 + 2] = c[f[i] * 3 + 2]; }
        return out;
    }

    private void tris(float[] mvp, float[] v, float[] color) {
        GLES20.glUseProgram(lineProg);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(lineProg, "uMvp"), 1, false, mvp, 0);
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(lineProg, "uColor"), 1, color, 0);
        if (v.length > scratch.capacity()) scratch = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        scratch.clear(); scratch.put(v).position(0);
        int aPos = GLES20.glGetAttribLocation(lineProg, "aPos");
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, scratch);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, v.length / 3);
        GLES20.glDisableVertexAttribArray(aPos);
    }

    private static final float[] SEL = {1f, 0.85f, 0.2f, 1f}, BOX = {0.4f, 0.85f, 1f, 0.7f}, FAINT = {0.4f, 0.85f, 1f, 0.25f};

    private void drawPoints(float[] mvp, Obj o, float pxPerMeter) {
        GLES20.glUseProgram(ptProg);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(ptProg, "uMvp"), 1, false, mvp, 0);
        float ca = (float) Math.cos(o.turn), sa = (float) Math.sin(o.turn);
        GLES20.glUniform4f(GLES20.glGetUniformLocation(ptProg, "uT"), o.cx + o.ox, o.cz + o.oz, ca, sa);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(ptProg, "uSize"), 0.022f * pxPerMeter);
        if (o.gpu == null) {
            o.gpu = ByteBuffer.allocateDirect(o.n * 12).order(ByteOrder.nativeOrder()).asFloatBuffer();
            for (int i = 0; i < o.n; i++) { o.gpu.put(o.pts[i * 3]); o.gpu.put(o.pts[i * 3 + 1] + o.y0); o.gpu.put(o.pts[i * 3 + 2]); }
            o.gpuCol = ByteBuffer.allocateDirect(o.n * 3).order(ByteOrder.nativeOrder());
            o.gpuCol.put(o.cols);
        }
        o.gpu.position(0); o.gpuCol.position(0);
        int aPos = GLES20.glGetAttribLocation(ptProg, "aPos"), aCol = GLES20.glGetAttribLocation(ptProg, "aCol");
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, o.gpu);
        GLES20.glVertexAttribPointer(aCol, 3, GLES20.GL_UNSIGNED_BYTE, true, 0, o.gpuCol);
        GLES20.glEnableVertexAttribArray(aPos); GLES20.glEnableVertexAttribArray(aCol);
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, o.n);
        GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aCol);
    }

    private static float[] edges(float[] c) {
        int[] e = {0, 1, 1, 3, 3, 2, 2, 0, 4, 5, 5, 7, 7, 6, 6, 4, 0, 4, 1, 5, 2, 6, 3, 7};
        float[] out = new float[e.length * 3];
        for (int i = 0; i < e.length; i++) { out[i * 3] = c[e[i] * 3]; out[i * 3 + 1] = c[e[i] * 3 + 1]; out[i * 3 + 2] = c[e[i] * 3 + 2]; }
        return out;
    }

    private void lines(float[] mvp, float[] v, float[] color) {
        GLES20.glUseProgram(lineProg);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(lineProg, "uMvp"), 1, false, mvp, 0);
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(lineProg, "uColor"), 1, color, 0);
        if (v.length > scratch.capacity()) scratch = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        scratch.clear(); scratch.put(v).position(0);
        int aPos = GLES20.glGetAttribLocation(lineProg, "aPos");
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, scratch);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, v.length / 3);
        GLES20.glDisableVertexAttribArray(aPos);
    }

    // ---------------- saving with the scan ----------------

    JSONArray toJson() throws Exception {
        JSONArray a = new JSONArray();
        for (Obj o : list) {
            if (!o.removed && !o.moved() && !o.fromCatalog) continue; // untouched detections are found again on load
            JSONObject j = new JSONObject();
            j.put("name", o.name); j.put("cx", o.cx); j.put("cz", o.cz); j.put("cosA", o.cosA); j.put("sinA", o.sinA);
            j.put("hu", o.hu); j.put("hv", o.hv); j.put("y0", o.y0); j.put("y1", o.y1); j.put("floor", o.onFloor);
            j.put("ox", o.ox); j.put("oz", o.oz); j.put("turn", o.turn); j.put("removed", o.removed);
            if (o.fromCatalog) { j.put("catalog", true); j.put("pts", b64(o)); }
            a.put(j);
        }
        return a;
    }

    /** Restore saved edits after a scan's points are loaded. */
    void fromJson(JSONArray a, FloatBuffer pos, ByteBuffer col, int count) {
        list.clear(); selected = -1;
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            try {
                JSONObject j = a.getJSONObject(i);
                Obj o = new Obj();
                o.name = j.optString("name", "Object");
                o.cx = (float) j.getDouble("cx"); o.cz = (float) j.getDouble("cz"); o.cosA = (float) j.getDouble("cosA"); o.sinA = (float) j.getDouble("sinA");
                o.hu = (float) j.getDouble("hu"); o.hv = (float) j.getDouble("hv"); o.y0 = (float) j.getDouble("y0"); o.y1 = (float) j.getDouble("y1");
                o.onFloor = j.optBoolean("floor", true);
                o.ox = (float) j.optDouble("ox", 0); o.oz = (float) j.optDouble("oz", 0); o.turn = (float) j.optDouble("turn", 0);
                o.removed = j.optBoolean("removed", false);
                o.fromCatalog = j.optBoolean("catalog", false);
                if (o.fromCatalog) unb64(o, j.getString("pts")); else extract(o, pos, col, count);
                list.add(o);
            } catch (Exception ignored) { }
        }
        edited = !list.isEmpty();
    }

    private static String b64(Obj o) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bo);
        writePts(d, o);
        d.flush();
        return android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP);
    }

    private static void unb64(Obj o, String s) throws Exception {
        readPts(new DataInputStream(new java.io.ByteArrayInputStream(android.util.Base64.decode(s, android.util.Base64.NO_WRAP))), o);
    }

    private static void writePts(DataOutputStream d, Obj o) throws Exception {
        d.writeInt(o.n);
        for (int i = 0; i < o.n * 3; i++) d.writeShort(Math.round(o.pts[i] * 1000)); // mm
        d.write(o.cols, 0, o.n * 3);
    }

    private static void readPts(DataInputStream d, Obj o) throws Exception {
        o.n = d.readInt(); o.pts = new float[o.n * 3]; o.cols = new byte[o.n * 3];
        for (int i = 0; i < o.n * 3; i++) o.pts[i] = d.readShort() / 1000f;
        d.readFully(o.cols);
    }

    // ---------------- the catalogue: objects kept across scans ----------------

    static final class Item { File file; String name, from; float w, d, h; long created; }

    static File catalogDir(Context c) { File f = new File(c.getFilesDir(), "catalog"); f.mkdirs(); return f; }

    /** Save the object as a catalogue item (its box, real size and scanned points, centred on its footprint). */
    static void saveToCatalog(Context c, Obj o, String name, String fromScan) throws Exception {
        File f = new File(catalogDir(c), "obj_" + System.currentTimeMillis() + ".bin");
        try (DataOutputStream d = new DataOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(f)))) {
            d.writeInt(0x43584f31); // "CXO1"
            d.writeUTF(name); d.writeUTF(fromScan == null ? "" : fromScan); d.writeLong(System.currentTimeMillis());
            d.writeFloat(o.hu); d.writeFloat(o.hv); d.writeFloat(o.h()); d.writeFloat(o.cosA); d.writeFloat(o.sinA);
            writePts(d, o);
        }
    }

    static List<Item> listCatalog(Context c) {
        List<Item> out = new ArrayList<>();
        File[] fs = catalogDir(c).listFiles();
        if (fs == null) return out;
        java.util.Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : fs) {
            try (DataInputStream d = new DataInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(f)))) {
                if (d.readInt() != 0x43584f31) continue;
                Item it = new Item(); it.file = f;
                it.name = d.readUTF(); it.from = d.readUTF(); it.created = d.readLong();
                it.w = 2 * d.readFloat(); it.d = 2 * d.readFloat(); it.h = d.readFloat();
                out.add(it);
            } catch (Exception ignored) { }
        }
        return out;
    }

    /** Load a catalogue item as a new object standing on the floor at (x, z). */
    static Obj loadItem(Item it, float x, float floorY, float z) throws Exception {
        try (DataInputStream d = new DataInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(it.file)))) {
            d.readInt(); d.readUTF(); d.readUTF(); d.readLong();
            Obj o = new Obj();
            o.name = it.name;
            o.hu = d.readFloat(); o.hv = d.readFloat(); float h = d.readFloat(); o.cosA = d.readFloat(); o.sinA = d.readFloat();
            readPts(d, o);
            o.cx = x; o.cz = z; o.y0 = floorY; o.y1 = floorY + h; o.onFloor = true; o.fromCatalog = true;
            return o;
        }
    }
}
