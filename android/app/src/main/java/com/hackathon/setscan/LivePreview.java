package com.hackathon.setscan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * "What you get is what you see" (rk 9 Nov), the same as the iPhone's LivePreview: the camera picture is dimmed, new
 * surface is orange, solid surface a white wireframe, and solid triangles are filled with the photo the Photo view will
 * use for them (the same keyframe textures and the same choice, PhotoRoom.best), at full brightness. Holes, blur and
 * low resolution show while they can still be fixed.
 *
 * The choice per triangle is remembered per spot (1 cm cells of its centre) and only worked out again for new photos.
 * Runs on the live-mesh builder thread.
 */
final class LivePreview {
    /** Show the real camera picture while scanning (dimmed, projected in the wide overview)? rk 9 Nov: off. The scan
     *  screen shows only what was captured (the mesh, white and photo-filled) on the zebra background. */
    static final boolean SHOW_CAMERA = false;
    /** The camera picture's brightness under the preview, 0..1 (when it's shown). */
    static final float DIM = 0.38f;
    /** Tentative (seen a few times) and solid surface, RGBA; ScanRenderer.drawLiveMesh. */
    static final float[] TENTATIVE = {1f, 0.55f, 0.15f, 0.40f}, TENTATIVE_EDGE = {1f, 0.45f, 0.1f, 0.85f};
    static final float[] SOLID = {0.92f, 0.94f, 1f, 0.42f}, SOLID_EDGE = {1f, 1f, 1f, 0.75f};
    /** A triangle is confirmed (gets a photo) at this vertex confidence, 0..1 (the shader's white). */
    static final float CONFIRMED = 0.6f;

    /** Per spot: {photo id, score, newest photo id tried}. */
    private final HashMap<Long, float[]> cache = new HashMap<>();

    /** Share of confirmed surface that has its photo: ahead of the phone (30° cone, 3.5 m) and in the whole room. */
    volatile float filledInView, filledRoom;

    /** What one build gives the GL thread. */
    static final class Result {
        FloatBuffer restPos; ByteBuffer restConf; int restVerts;          // triangles without a photo (orange / white)
        FloatBuffer lines; ByteBuffer lineConf; int lineVerts;            // their wireframe
        final List<Integer> groupTex = new ArrayList<>();                 // per photo: its texture,
        final List<FloatBuffer> groupPos = new ArrayList<>(), groupUv = new ArrayList<>(); // its triangles and uvs
        final List<Integer> groupVerts = new ArrayList<>();
        // When each vertex's bit of surface first appeared (seconds, clock()), for the fly-in.
        FloatBuffer restBorn, lineBorn;
        final List<FloatBuffer> groupBorn = new ArrayList<>();
    }

    synchronized void reset() { cache.clear(); born.clear(); filledInView = 0; filledRoom = 0; }

    // ---- fly-in (rk 10 Nov): new surface rises into place from below, ~0.4 s; keyed by the vertex's 4 cm cell so
    // surface that's only re-extracted doesn't animate again ----
    static final boolean FLY_IN = true;
    private static final long START = System.nanoTime();
    static float clock() { return (System.nanoTime() - START) / 1e9f; }
    private final HashMap<Long, Float> born = new HashMap<>();
    private float bornNow;

    /** Each new triangle starts its flight at its own moment within this window (s), so they arrive one by one, not in
     *  chunks per mesh build (rk 10 Nov). Fixed per spot, so a re-extracted triangle keeps its time. */
    static final float STAGGER = 0.6f;

    /** When the triangle around (x, y, z) (its centre) first appeared, plus its own delay. */
    private float bornOf(float x, float y, float z) {
        long k = (((long) Math.floor(x / 0.02f) + (1 << 20)) & 0x1FFFFF) | ((((long) Math.floor(y / 0.02f) + (1 << 20)) & 0x1FFFFF) << 21)
                | ((((long) Math.floor(z / 0.02f) + (1 << 20)) & 0x1FFFFF) << 42);
        Float b = born.get(k);
        if (b == null) {
            long h = k * 0x9E3779B97F4A7C15L;
            float jitter = ((h >>> 40) & 0xFFFF) / 65535f;
            b = bornNow + jitter * STAGGER;
            born.put(k, b);
        }
        return b;
    }

    /** Tap to clear: the spots near here pick their photos again. */
    synchronized void forget(float x, float y, float z, float r) {
        cache.keySet().removeIf(key -> {
            float kx = (int) ((key >> 42) & 0x1FFFFF) - (1 << 20), ky = (int) ((key >> 21) & 0x1FFFFF) - (1 << 20), kz = (int) (key & 0x1FFFFF) - (1 << 20);
            float dx = kx * 0.01f - x, dy = ky * 0.01f - y, dz = kz * 0.01f - z;
            return dx * dx + dy * dy + dz * dz < r * r;
        });
    }

    private static long key(float x, float y, float z) {
        long ix = (long) Math.floor(x * 100) + (1 << 20), iy = (long) Math.floor(y * 100) + (1 << 20), iz = (long) Math.floor(z * 100) + (1 << 20);
        return (ix << 42) | (iy << 21) | iz;
    }

    /** Splits the live mesh (nt triangles, 9 floats and 3 confidence bytes each) into photo groups and the rest. */
    synchronized Result build(FloatBuffer meshPos, ByteBuffer meshConf, int nt, List<PhotoRoom.Keyframe> kfs,
                              float camX, float camY, float camZ, float fwdX, float fwdY, float fwdZ) {
        HashMap<Integer, Integer> indexOf = new HashMap<>();
        int newest = 0;
        for (int k = 0; k < kfs.size(); k++) { indexOf.put(kfs.get(k).id, k); newest = Math.max(newest, kfs.get(k).id); }
        int[] pick = new int[nt];
        int[] perKf = new int[kfs.size()];
        int rest = 0, inView = 0, inViewFilled = 0;
        float[] v = new float[9], uv = new float[6], score = new float[1];
        int confirmedMin = Math.round(CONFIRMED * 255);
        for (int t = 0; t < nt; t++) {
            pick[t] = -1;
            int c0 = meshConf.get(t * 3) & 0xFF, c1 = meshConf.get(t * 3 + 1) & 0xFF, c2 = meshConf.get(t * 3 + 2) & 0xFF;
            if (Math.min(c0, Math.min(c1, c2)) < confirmedMin) { rest++; continue; }
            for (int i = 0; i < 9; i++) v[i] = meshPos.get(t * 9 + i);
            float cx = (v[0] + v[3] + v[6]) / 3, cy = (v[1] + v[4] + v[7]) / 3, cz = (v[2] + v[5] + v[8]) / 3;
            long key = key(cx, cy, cz);
            float[] ch = cache.get(key);
            if (ch == null) ch = new float[]{-1, 0, 0};
            if (ch[0] >= 0 && !indexOf.containsKey((int) ch[0])) { ch[0] = -1; ch[1] = 0; ch[2] = 0; } // its photo went
            if (ch[2] < newest) {
                int k = PhotoRoom.best(kfs, v, uv, (int) ch[2], ch[1], score);
                if (k >= 0) { ch[0] = kfs.get(k).id; ch[1] = score[0]; }
                ch[2] = newest;
                cache.put(key, ch);
            }
            float dx = cx - camX, dy = cy - camY, dz = cz - camZ, d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            boolean seen = d > 0.1f && d < 3.5f && (dx * fwdX + dy * fwdY + dz * fwdZ) / d > 0.866f;
            if (seen) inView++;
            Integer k = ch[0] >= 0 ? indexOf.get((int) ch[0]) : null;
            if (k != null) { pick[t] = k; perKf[k]++; if (seen) inViewFilled++; } else rest++;
        }
        Result r = new Result();
        r.restVerts = rest * 3;
        // At least one whole vertex each (the upload sends max(1, count) vertices): with no unpainted triangles a
        // 1-float buffer made glBufferData throw and the app crash when a scan started (rk 10 Nov).
        r.restPos = fb(Math.max(3, rest * 9));
        r.restConf = ByteBuffer.allocateDirect(Math.max(1, rest * 3)).order(ByteOrder.nativeOrder());
        r.lines = fb(Math.max(3, rest * 18));
        r.lineConf = ByteBuffer.allocateDirect(Math.max(1, rest * 6)).order(ByteOrder.nativeOrder());
        r.lineVerts = rest * 6;
        // The first build (a reopened scan, Continue scan) shows at once; later new surface flies in.
        bornNow = born.isEmpty() && nt > 3000 ? clock() - 10 : clock();
        r.restBorn = fb(Math.max(1, rest * 3));
        r.lineBorn = fb(Math.max(1, rest * 6));
        FloatBuffer[] gb = new FloatBuffer[kfs.size()];
        for (int k = 0; k < kfs.size(); k++) if (perKf[k] > 0) gb[k] = fb(perKf[k] * 3);
        FloatBuffer[] gp = new FloatBuffer[kfs.size()], gu = new FloatBuffer[kfs.size()];
        for (int k = 0; k < kfs.size(); k++) if (perKf[k] > 0) { gp[k] = fb(perKf[k] * 9); gu[k] = fb(perKf[k] * 6); }
        byte[] c = new byte[3];
        for (int t = 0; t < nt; t++) {
            for (int i = 0; i < 9; i++) v[i] = meshPos.get(t * 9 + i);
            int k = pick[t];
            // One time per triangle (its centre): the whole triangle flies in as a piece.
            float b0 = bornOf((v[0] + v[3] + v[6]) / 3, (v[1] + v[4] + v[7]) / 3, (v[2] + v[5] + v[8]) / 3), b1 = b0, b2 = b0;
            if (k >= 0 && PhotoRoom.project(kfs.get(k), v, uv)) { gp[k].put(v); gu[k].put(uv); gb[k].put(b0).put(b1).put(b2); continue; }
            if (k >= 0) { gp[k].put(v); gu[k].put(new float[6]); gb[k].put(b0).put(b1).put(b2); continue; } // keeps the group's count (not reached)
            r.restBorn.put(b0).put(b1).put(b2);
            r.lineBorn.put(b0).put(b1).put(b1).put(b2).put(b2).put(b0);
            for (int i = 0; i < 3; i++) c[i] = meshConf.get(t * 3 + i);
            r.restPos.put(v); r.restConf.put(c);
            for (int e = 0; e < 3; e++) {
                int a = e, b = (e + 1) % 3;
                r.lines.put(v[a * 3]).put(v[a * 3 + 1]).put(v[a * 3 + 2]).put(v[b * 3]).put(v[b * 3 + 1]).put(v[b * 3 + 2]);
                r.lineConf.put(c[a]).put(c[b]);
            }
        }
        r.restPos.position(0); r.restConf.position(0); r.lines.position(0); r.lineConf.position(0);
        r.restBorn.position(0); r.lineBorn.position(0);
        for (int k = 0; k < kfs.size(); k++) {
            if (gp[k] == null) continue;
            gp[k].position(0); gu[k].position(0); gb[k].position(0);
            r.groupTex.add(kfs.get(k).tex); r.groupPos.add(gp[k]); r.groupUv.add(gu[k]); r.groupVerts.add(perKf[k] * 3); r.groupBorn.add(gb[k]);
        }
        filledInView = inView == 0 ? 0 : (float) inViewFilled / inView;
        int filled = 0;
        for (float[] ch : cache.values()) if (ch[0] >= 0) filled++;
        filledRoom = cache.isEmpty() ? 0 : (float) filled / cache.size();
        return r;
    }

    private static FloatBuffer fb(int floats) {
        return ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }
}
