package com.hackathon.setscan;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Flat tiles (rk 10 Nov 16:05, trial): where the depth shows a flat surface, 30 cm square tiles are laid on it and
 * painted from the camera picture, so a plain wall shows as a sharp picture instead of soft points, and a wrongly flat
 * guess shows at once. Best look wins: a tile is repainted only from a clearly better view (closer, more head-on).
 * A tile the depth later contradicts (most of it clearly in front of or behind the plane) is removed again.
 *
 * GL thread only. update() at most twice a second, from the latest depth image and its camera pose; draw() every
 * frame. The points stay saved (this is a trial).
 */
final class FlatTiles {
    static final float TILE = 0.30f;          // tile size, metres
    static final int PX = 96;                  // texture pixels per tile side (~3 mm per pixel)
    static final int ATLAS = 4096, PER_ROW = ATLAS / PX, MAX_TILES = PER_ROW * PER_ROW;
    // S24 depth noise is 3-4 cm at 2 m (today's measurements): 2 cm inliers / 4 cm 'in front' rejected most tiles
    static final float INLIER = 0.035f, FRONT = 0.07f;

    static final class Plane { float nx, ny, nz, d, ux, uy, uz, vx, vy, vz; }

    // ---- Hiding the points under painted tiles (rk 10 Nov 16:22: "remove all dots on top of tiles and behind
    // tiles"): a mask of painted tiles per plane (first 8 planes, 256 x 256 tiles each, +-38 m), read in the points'
    // vertex shader; a point within 7 cm in front of or 15 cm behind a painted tile isn't drawn. Measured wall scatter
    // is ~6-7 cm (90%); things further in front (a drying rack) and further behind (the next room) stay. ----
    static final int MP = 8, MG = 256;
    static final String MASK_DECL = "uniform vec4 uTP[8]; uniform vec4 uTU[8]; uniform vec4 uTV[8]; uniform float uTN; uniform sampler2D uTMask;";
    static final String MASK_TEST =
            "  for (int i = 0; i < 8; i++) { if (float(i) >= uTN) break;" +
            "    float dd = dot(uTP[i].xyz, aPos.xyz) + uTP[i].w;" +
            "    if (dd < 0.07 && dd > -0.15) { float a = floor(dot(uTU[i].xyz, aPos.xyz) / 0.30) + 128.0, b = floor(dot(uTV[i].xyz, aPos.xyz) / 0.30) + 128.0;" +
            "      if (a >= 0.0 && a < 256.0 && b >= 0.0 && b < 256.0 && texture2DLod(uTMask, vec2((a + 0.5) / 256.0, (b + 256.0 * float(i) + 0.5) / 2048.0), 0.0).r > 0.5) hh = 1.0; } }";
    private int maskTex; private final byte[] mask = new byte[MG * MG * MP]; private boolean maskDirty = true; private int maskN; private final int[] maskPlane = new int[MP];

    /** Before drawing points: the mask and planes for the shader (off: nothing hidden). Texture unit 3. */
    synchronized void bindMask(int prog, boolean on) {
        if (on && dirty) rebuild();
        int np = on ? maskN : 0;
        if (np > 0 && (maskTex == 0 || maskDirty)) {
            if (maskTex == 0) {
                int[] t = new int[1]; GLES20.glGenTextures(1, t, 0); maskTex = t[0];
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex);
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, MG, MG * MP, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(mask));
            maskDirty = false;
        }
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uTN"), np);
        if (np == 0) return;
        float[] tp = new float[32], tu = new float[32], tv = new float[32];
        for (int i = 0; i < np; i++) { Plane p = planes.get(maskPlane[i]);
            tp[i * 4] = p.nx; tp[i * 4 + 1] = p.ny; tp[i * 4 + 2] = p.nz; tp[i * 4 + 3] = p.d;
            tu[i * 4] = p.ux; tu[i * 4 + 1] = p.uy; tu[i * 4 + 2] = p.uz; tv[i * 4] = p.vx; tv[i * 4 + 1] = p.vy; tv[i * 4 + 2] = p.vz; }
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(prog, "uTP"), 8, tp, 0);
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(prog, "uTU"), 8, tu, 0);
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(prog, "uTV"), 8, tv, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTMask"), 3);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }
    static final class Tile { int plane, iu, iv, slot; float score, pending; int contra; float born = -1, repainted = -1; }
    private final long base = System.currentTimeMillis();
    private float now() { return (System.currentTimeMillis() - base) / 1000f; }

    final ArrayList<Plane> planes = new ArrayList<>();
    final HashMap<Long, Tile> tiles = new HashMap<>();
    private int atlasTex, fbo, paintProg, drawProg, slots;
    private FloatBuffer vbo; private int vboVerts; private boolean dirty;
    int painted, removed;
    long lastMs;

    /**
     * depth: mm (0 = none), dw x dh, intrinsics (fx, fy, cx, cy) for it; pose: camera-to-world (corrected) when it was
     * taken; texture intrinsics (fx, fy, cx, cy, w, h) of the camera picture now (same pose: caller checks);
     * texToNorm: converts texture pixel coordinates to the camera texture's normalized coordinates (frame transform).
     */
    /** Counts for the log: attempts, no plane, tiles looked at, painted, and why tiles weren't. */
    int attempts, noPlane, looked, lowCover, blocked, opening, tooSlanted, notBetter;
    static final class Job { int plane; ArrayList<Tile> paint = new ArrayList<>(); float[] pose; }

    /** The CPU part (a background thread): plane, tiles to paint. Returns null when there's nothing to paint. */
    synchronized Job analyse(short[] depth, int dw, int dh, float[] di, float[] pose, long nowMs) {
        lastMs = nowMs; attempts++;
        // 1) the depth image's points in the world (every 2nd pixel), with which pixel they came from
        int n = 0; float[] wp = new float[(dw / 2 + 1) * (dh / 2 + 1) * 3];
        float camx = pose[12], camy = pose[13], camz = pose[14];
        for (int v = 1; v < dh; v += 2) for (int u = 1; u < dw; u += 2) {
            int mm = depth[v * dw + u] & 0xFFFF; if (mm < 300 || mm > 3500) continue;
            float d = mm * 0.001f, xc = (u + 0.5f - di[2]) * d / di[0], yc = (di[3] - (v + 0.5f)) * d / di[1], zc = -d;
            wp[n * 3] = pose[0] * xc + pose[4] * yc + pose[8] * zc + pose[12];
            wp[n * 3 + 1] = pose[1] * xc + pose[5] * yc + pose[9] * zc + pose[13];
            wp[n * 3 + 2] = pose[2] * xc + pose[6] * yc + pose[10] * zc + pose[14];
            n++;
        }
        if (n < 400) { noPlane++; return null; }
        // 2) the dominant plane (RANSAC), then refined by least squares on its inliers
        java.util.Random rnd = new java.util.Random(nowMs);
        float bnx = 0, bny = 0, bnz = 0, bd = 0; int bestIn = 0;
        for (int it = 0; it < 120; it++) {
            int a = rnd.nextInt(n), b = rnd.nextInt(n), c = rnd.nextInt(n);
            float e1x = wp[b * 3] - wp[a * 3], e1y = wp[b * 3 + 1] - wp[a * 3 + 1], e1z = wp[b * 3 + 2] - wp[a * 3 + 2];
            float e2x = wp[c * 3] - wp[a * 3], e2y = wp[c * 3 + 1] - wp[a * 3 + 1], e2z = wp[c * 3 + 2] - wp[a * 3 + 2];
            float nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
            float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz); if (l < 1e-6f) continue;
            nx /= l; ny /= l; nz /= l;
            float d = -(nx * wp[a * 3] + ny * wp[a * 3 + 1] + nz * wp[a * 3 + 2]);
            int in = 0;
            for (int i = 0; i < n; i += 3) if (Math.abs(nx * wp[i * 3] + ny * wp[i * 3 + 1] + nz * wp[i * 3 + 2] + d) < INLIER) in++;
            if (in > bestIn) { bestIn = in; bnx = nx; bny = ny; bnz = nz; bd = d; }
        }
        if (bestIn * 3 < n * 0.25f) { noPlane++; return null; }   // under 25% of the view on one plane: nothing flat enough here
        if (bnx * camx + bny * camy + bnz * camz + bd < 0) { bnx = -bnx; bny = -bny; bnz = -bnz; bd = -bd; }   // normal towards the camera
        // 3) the same plane as before (within 8 deg and 4 cm), so tiles stay put; else a new one
        int pi = -1;
        for (int i = 0; i < planes.size(); i++) {
            Plane p = planes.get(i);
            if (p.nx * bnx + p.ny * bny + p.nz * bnz > 0.985f && Math.abs(p.d - bd) < 0.06f) { pi = i; break; }   // (noise: was 8 deg / 4 cm, a new plane nearly every time)
        }
        if (pi < 0) {
            Plane p = new Plane(); p.nx = bnx; p.ny = bny; p.nz = bnz; p.d = bd;
            if (Math.abs(bny) > 0.8f) { p.ux = 1; p.uy = 0; p.uz = 0; }   // floor / ceiling / tabletop: x, z
            else { float l = (float) Math.hypot(bnx, bnz); p.ux = bnz / l; p.uy = 0; p.uz = -bnx / l; }   // wall: horizontal u
            // project u onto the plane, v = n x u
            float k = p.ux * p.nx + p.uy * p.ny + p.uz * p.nz; p.ux -= k * p.nx; p.uy -= k * p.ny; p.uz -= k * p.nz;
            float l = (float) Math.sqrt(p.ux * p.ux + p.uy * p.uy + p.uz * p.uz); p.ux /= l; p.uy /= l; p.uz /= l;
            p.vx = p.ny * p.uz - p.nz * p.uy; p.vy = p.nz * p.ux - p.nx * p.uz; p.vz = p.nx * p.uy - p.ny * p.ux;
            planes.add(p); pi = planes.size() - 1;
        }
        Plane p = planes.get(pi);
        // 4) per tile: samples on the plane, in front of it (something stands there), behind it (an opening)
        HashMap<Long, int[]> cnt = new HashMap<>();
        for (int i = 0; i < n; i++) {
            float x = wp[i * 3], y = wp[i * 3 + 1], z = wp[i * 3 + 2];
            float s = p.nx * x + p.ny * y + p.nz * z + p.d;
            // where the ray to this point crosses the plane
            float rx = x - camx, ry = y - camy, rz = z - camz, rn = p.nx * rx + p.ny * ry + p.nz * rz;
            if (Math.abs(rn) < 1e-4f) continue;
            float t = -(p.nx * camx + p.ny * camy + p.nz * camz + p.d) / rn;
            float qx = camx + rx * t, qy = camy + ry * t, qz = camz + rz * t;
            int iu = (int) Math.floor((qx * p.ux + qy * p.uy + qz * p.uz) / TILE), iv = (int) Math.floor((qx * p.vx + qy * p.vy + qz * p.vz) / TILE);
            long key = key(pi, iu, iv);
            int[] c = cnt.get(key); if (c == null) cnt.put(key, c = new int[3]);
            if (Math.abs(s) < INLIER) c[0]++; else if (s > FRONT) c[1]++; else if (s < -FRONT) c[2]++;
        }
        // 5) confirm, paint (best look), or take back
        float vdist = (float) Math.abs(p.nx * camx + p.ny * camy + p.nz * camz + p.d);
        float fx = -pose[8], fy = -pose[9], fz = -pose[10];   // camera looks along -z
        float head = Math.abs(fx * p.nx + fy * p.ny + fz * p.nz);   // 1 = straight on
        ArrayList<Tile> toPaint = new ArrayList<>();
        for (java.util.Map.Entry<Long, int[]> e : cnt.entrySet()) {
            int[] c = e.getValue(); int tot = c[0] + c[1] + c[2];
            long k = e.getKey(); Tile t = tiles.get(k);
            if (t != null && tot >= 8 && c[0] < 0.4f * tot) {   // the depth now says it isn't flat there
                if (++t.contra >= 2) { tiles.remove(k); removed++; dirty = true; }
                continue;
            }
            if (t != null) t.contra = 0;
            // most of the tile seen, on the plane, nothing in front: a 30 cm tile at 2 m is ~17x17 depth pixels, ~70 samples
            float expected = (float) Math.pow(TILE * di[0] / Math.max(0.3f, vdist) / 2, 2) * head;
            looked++;
            // raw depth leaves many pixels empty on plain walls: a third of the tile is enough
            if (c[0] < 0.2f * expected) { lowCover++; continue; }   // (16:16 scan: 0.3 left 421 of 598 tiles unpainted)
            if (c[1] > Math.max(3, 0.08f * tot)) { blocked++; continue; }   // something stands in front
            if (c[2] > 0.2f * tot) { opening++; continue; }                  // the depth went through: an opening
            float score = head / Math.max(0.3f, vdist);
            if (t == null) {
                if (slots >= MAX_TILES) continue;
                t = new Tile(); t.plane = pi; t.iu = (int) (k >>> 20 & 0xFFFFF) - (1 << 19); t.iv = (int) (k & 0xFFFFF) - (1 << 19); t.slot = slots++; t.score = -1;
                tiles.put(k, t);
            }
            if (head <= 0.5f) { tooSlanted++; continue; }
            if (score > t.score * 1.25f) { t.pending = score; toPaint.add(t); } else notBetter++;
        }
        if (toPaint.isEmpty()) return null;
        Job j = new Job(); j.plane = pi; j.paint = toPaint; j.pose = pose; return j;
    }

    /** GL thread: paints a job's tiles from the camera picture now (taken from where the depth was). */
    synchronized void paint(Job j, float[] ti, int cameraTex, Norm texToNorm) {
        float tn = now();
        for (Tile t : j.paint) { if (t.born < 0) t.born = tn; else t.repainted = tn; t.score = t.pending; }
        paint(j.paint, planes.get(j.plane), j.pose, ti, cameraTex, texToNorm);
    }

    String stats() {
        return String.format(java.util.Locale.US, "flat tiles: %d shown on %d planes; %d attempts (%d no flat surface), %d tiles looked at: %d painted, %d too little depth, %d something in front, %d opening, %d too slanted, %d not a better view; %d taken back",
                countShown(), planes.size(), attempts, noPlane, looked, painted, lowCover, blocked, opening, tooSlanted, notBetter, removed);
    }

    int countShown() { int c = 0; for (Tile t : tiles.values()) if (t.score > 0) c++; return c; }

    interface Norm { void apply(float[] texPx, float[] outNorm); }

    private static long key(int plane, int iu, int iv) {
        return ((long) plane << 40) | ((long) ((iu + (1 << 19)) & 0xFFFFF) << 20) | ((iv + (1 << 19)) & 0xFFFFF);
    }

    /** Copies each tile's quad from the camera picture into its atlas slot. */
    private void paint(ArrayList<Tile> list, Plane p, float[] pose, float[] ti, int cameraTex, Norm norm) {
        ensureGl();
        float[] view = new float[16]; android.opengl.Matrix.invertM(view, 0, pose, 0);
        int[] prevFbo = new int[1]; GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prevFbo, 0);
        int[] vp = new int[4]; GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, vp, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glUseProgram(paintProg);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTex);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(paintProg, "uTex"), 0);
        int aPos = GLES20.glGetAttribLocation(paintProg, "aPos"), aUv = GLES20.glGetAttribLocation(paintProg, "aUv");
        FloatBuffer q = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        float[] px = new float[8], nn = new float[8];
        int done = 0;
        for (Tile t : list) {
            if (done >= 40) break;   // a bounded amount per update
            boolean ok = true;
            for (int c = 0; c < 4; c++) {
                float a = (t.iu + ((c & 1) == 0 ? 0 : 1)) * TILE, b = (t.iv + ((c & 2) == 0 ? 0 : 1)) * TILE;
                float x = p.ux * a + p.vx * b - p.nx * p.d, y = p.uy * a + p.vy * b - p.ny * p.d, z = p.uz * a + p.vz * b - p.nz * p.d;
                float cx = view[0] * x + view[4] * y + view[8] * z + view[12], cy = view[1] * x + view[5] * y + view[9] * z + view[13], cz = view[2] * x + view[6] * y + view[10] * z + view[14];
                float d = -cz; if (d < 0.2f) { ok = false; break; }
                float u = ti[0] * cx / d + ti[2], v = ti[3] - ti[1] * cy / d;
                if (u < 0 || v < 0 || u > ti[4] || v > ti[5]) { ok = false; break; }
                px[c * 2] = u; px[c * 2 + 1] = v;
            }
            if (!ok) { t.score = -1; continue; }
            norm.apply(px, nn);
            int sx = (t.slot % PER_ROW) * PX, sy = (t.slot / PER_ROW) * PX;
            GLES20.glViewport(sx, sy, PX, PX);
            q.clear();
            q.put(new float[]{-1, -1, nn[0], nn[1], 1, -1, nn[2], nn[3], -1, 1, nn[4], nn[5], 1, 1, nn[6], nn[7]}).position(0);
            q.position(0); GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, q);
            q.position(2); GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, q);
            GLES20.glEnableVertexAttribArray(aPos); GLES20.glEnableVertexAttribArray(aUv);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            done++; painted++;
        }
        GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aUv);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0]);
        GLES20.glViewport(vp[0], vp[1], vp[2], vp[3]);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        dirty = true;
    }

    private void ensureGl() {
        if (atlasTex != 0) return;
        int[] t = new int[1]; GLES20.glGenTextures(1, t, 0); atlasTex = t[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, ATLAS, ATLAS, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glGenFramebuffers(1, t, 0); fbo = t[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, atlasTex, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        paintProg = ScanRenderer.program("attribute vec4 aPos; attribute vec2 aUv; varying vec2 vUv; void main(){ gl_Position = aPos; vUv = aUv; }",
                "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vUv; uniform samplerExternalOES uTex; void main(){ gl_FragColor = vec4(texture2D(uTex, vUv).rgb, 1.0); }");
        // rk 10 Nov 16:19: a new tile flashes white, then develops like a Polaroid (~1 s): from washed-out, warm and
        // flat to full colour. A repaint from a better look only brightens a little for 0.3 s. Time-based (15 fps).
        drawProg = ScanRenderer.program("attribute vec4 aPos; attribute vec2 aUv; attribute vec2 aAge; uniform mat4 uMvp; uniform float uNow; varying vec2 vUv; varying vec2 vAge;"
                        + " void main(){ gl_Position = uMvp * aPos; vUv = aUv; vAge = vec2(uNow - aAge.x, aAge.y < 0.0 ? 99.0 : uNow - aAge.y); }",
                "precision mediump float; varying vec2 vUv; varying vec2 vAge; uniform sampler2D uTex;"
                        + " void main(){ vec3 c = texture2D(uTex, vUv).rgb; float a = vAge.x;"
                        + "  float dev = smoothstep(0.15, 1.1, a);"                                   // developing 0..1
                        + "  float l = dot(c, vec3(0.299, 0.587, 0.114));"
                        + "  vec3 washed = mix(vec3(l), c, 0.35) * vec3(1.06, 1.0, 0.9) * 0.55 + 0.45;"   // pale, warm, low contrast
                        + "  vec3 col = mix(washed, c, dev);"
                        + "  float flash = 1.0 - smoothstep(0.0, 0.35, a);"                            // white flash at birth
                        + "  float re = (1.0 - smoothstep(0.0, 0.3, vAge.y)) * 0.15;"                  // a repaint: a slight glow
                        + "  gl_FragColor = vec4(mix(col, vec3(1.0), clamp(flash * 0.95 + re, 0.0, 1.0)), 1.0); }");
    }

    /** The painted tiles as textured quads, 5 mm in front of their plane. */
    synchronized void draw(float[] mvp) {
        if (tiles.isEmpty() || atlasTex == 0) return;
        if (dirty) rebuild();
        if (vboVerts == 0) return;
        GLES20.glUseProgram(drawProg);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(drawProg, "uMvp"), 1, false, mvp, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTex);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(drawProg, "uTex"), 0);
        int aPos = GLES20.glGetAttribLocation(drawProg, "aPos"), aUv = GLES20.glGetAttribLocation(drawProg, "aUv");
        int aAge = GLES20.glGetAttribLocation(drawProg, "aAge");
        GLES20.glUniform1f(GLES20.glGetUniformLocation(drawProg, "uNow"), now());
        vbo.position(0); GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 28, vbo);
        vbo.position(3); GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 28, vbo);
        vbo.position(5); GLES20.glVertexAttribPointer(aAge, 2, GLES20.GL_FLOAT, false, 28, vbo);
        GLES20.glEnableVertexAttribArray(aPos); GLES20.glEnableVertexAttribArray(aUv); GLES20.glEnableVertexAttribArray(aAge);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vboVerts);
        GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aUv); GLES20.glDisableVertexAttribArray(aAge);
    }

    private void rebuild() {
        dirty = false;
        ArrayList<Tile> live = new ArrayList<>(); for (Tile t : tiles.values()) if (t.score > 0) live.add(t);
        java.util.Arrays.fill(mask, (byte) 0);
        // the (up to) 8 planes with the most painted tiles get a mask layer
        HashMap<Integer, Integer> per = new HashMap<>(); for (Tile t : live) per.merge(t.plane, 1, Integer::sum);
        ArrayList<Integer> order = new ArrayList<>(per.keySet()); order.sort((x, y) -> per.get(y) - per.get(x));
        maskN = Math.min(MP, order.size());
        HashMap<Integer, Integer> layer = new HashMap<>();
        for (int i = 0; i < maskN; i++) { maskPlane[i] = order.get(i); layer.put(order.get(i), i); }
        for (Tile t : live) {
            Integer L = layer.get(t.plane); int a = t.iu + 128, b = t.iv + 128;
            if (L != null && a >= 0 && a < MG && b >= 0 && b < MG) mask[(L * MG + b) * MG + a] = (byte) 255;
        }
        maskDirty = true;
        vboVerts = live.size() * 6;
        vbo = ByteBuffer.allocateDirect(Math.max(1, vboVerts) * 28).order(ByteOrder.nativeOrder()).asFloatBuffer();
        float inset = 0.5f / PX;
        for (Tile t : live) {
            Plane p = planes.get(t.plane);
            float u0 = (t.slot % PER_ROW) * PX / (float) ATLAS + inset / PER_ROW, v0 = (t.slot / PER_ROW) * PX / (float) ATLAS + inset / PER_ROW;
            float du = (PX - 1f) / ATLAS;
            float[][] c = new float[4][];
            for (int k = 0; k < 4; k++) {
                float a = (t.iu + ((k & 1) == 0 ? 0 : 1)) * TILE, b = (t.iv + ((k & 2) == 0 ? 0 : 1)) * TILE;
                float off = 0.005f - p.d;
                c[k] = new float[]{p.ux * a + p.vx * b + p.nx * off, p.uy * a + p.vy * b + p.ny * off, p.uz * a + p.vz * b + p.nz * off,
                        u0 + ((k & 1) == 0 ? 0 : du), v0 + ((k & 2) == 0 ? 0 : du), t.born, t.repainted};
            }
            for (int idx : new int[]{0, 1, 3, 0, 3, 2}) vbo.put(c[idx]);
        }
        vbo.position(0);
    }

    synchronized void clear() { maskN = 0; tiles.clear(); planes.clear(); slots = 0; vboVerts = 0; painted = 0; removed = 0; dirty = true; }
}
