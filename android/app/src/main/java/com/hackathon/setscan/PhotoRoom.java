package com.hackathon.setscan;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Photographic room: HD camera frames ("keyframes") are saved with their exact pose while scanning,
 * then every triangle of the fused surface mesh is painted with the part of the best photo that saw it
 * (closest, most head-on, not hidden behind something else). All GL calls run on the GL thread.
 */
final class PhotoRoom {
    static final int MAX_KEYFRAMES = 60;
    static final int TEX_W = 1024, TEX_H = 512; // power of two so we can mipmap; uv is normalized so the squash doesn't matter

    static final class Keyframe {
        int tex;
        float[] view = new float[16];  // world -> camera
        float[] pose = new float[16];  // camera -> world
        float fx, fy, cx, cy, w, h;   // texture (camera image) intrinsics, pixels
        short[] depth; int dw, dh; float dfx, dfy, dcx, dcy; // depth image for occlusion checks (mm)
        float px, py, pz;              // camera position
        float[] fwd = new float[3];    // viewing direction
        boolean closeUp;               // taken near a detail zone (never replaced)
    }

    final List<Keyframe> keyframes = new ArrayList<>();
    private int fbo, copyProg, meshProg;
    private final FloatBuffer quad = fb(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
    private final FloatBuffer quadUv = fb(new float[]{0, 0, 1, 0, 0, 1, 1, 1});

    // built mesh, grouped by keyframe (index = keyframe, last = untextured)
    // Built mesh. build() may run on a background thread: it fills new lists and swaps them in under the lock.
    List<FloatBuffer> groupPos = new ArrayList<>();
    List<FloatBuffer> groupUv = new ArrayList<>();
    List<Integer> groupVerts = new ArrayList<>();
    FloatBuffer plainPos; ByteBuffer plainCol; int plainVerts;
    volatile boolean ready;
    int texturedTris, plainTris;

    void onSurfaceCreated() {
        keyframes.clear(); synchronized (this) { groupPos = new ArrayList<>(); groupUv = new ArrayList<>(); groupVerts = new ArrayList<>(); } ready = false;
        int[] b = new int[1];
        GLES20.glGenFramebuffers(1, b, 0);
        fbo = b[0];
        copyProg = ScanRenderer.program(
                "attribute vec4 aPos; attribute vec2 aUv; varying vec2 vUv;" +
                "void main(){ gl_Position = aPos; vUv = aUv; }",
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float; varying vec2 vUv; uniform samplerExternalOES uTex; uniform float uGain;" +
                // same brightening curve as the point colours: shadows lifted, white stays white
                "void main(){ vec3 c = texture2D(uTex, vUv).rgb; c = c * uGain / (1.0 + (uGain - 1.0) * c); gl_FragColor = vec4(c, 1.0); }");
        meshProg = ScanRenderer.program(
                "uniform mat4 uMvp; attribute vec4 aPos; attribute vec2 aUv; attribute vec4 aColor;" +
                "varying vec2 vUv; varying vec4 vColor; varying float vHide;" +
                "uniform float uCut; uniform vec3 uCenter; uniform vec2 uDir; uniform float uCutD; uniform vec2 uDir2; uniform float uCutD2; uniform float uFloor; uniform float uCeil;" +
                "void main(){ vHide = 0.0;" +
                "  if (uCut > 0.5) { vec2 r = aPos.xz - uCenter.xz;" +
                "    if (((dot(r, uDir) > uCutD || dot(r, uDir2) > uCutD2) && aPos.y > uFloor + 0.15) || aPos.y > uCeil) vHide = 1.0; }" +
                "  gl_Position = uMvp * aPos; vUv = aUv; vColor = aColor; }",
                "precision mediump float; varying vec2 vUv; varying vec4 vColor; varying float vHide;" +
                "uniform sampler2D uTex; uniform float uUseTex;" +
                "void main(){ if (vHide > 0.5) discard;" +
                "  gl_FragColor = uUseTex > 0.5 ? vec4(texture2D(uTex, vUv).rgb, 1.0) : vColor; }");
    }

    void clear() {
        for (Keyframe k : keyframes) GLES20.glDeleteTextures(1, new int[]{k.tex}, 0);
        keyframes.clear();
        synchronized (this) {
            groupPos = new ArrayList<>(); groupUv = new ArrayList<>(); groupVerts = new ArrayList<>();
            plainPos = null; plainCol = null; plainVerts = 0;
        }
        ready = false; texturedTris = plainTris = 0;
    }

    /** Should we keep this frame? Far enough (30 cm or 15°) from every keyframe so far, and the phone is steady. */
    boolean wants(float[] pose, float moveSinceLastFrame, float turnSinceLastFrameDeg, boolean nearDetail) {
        if (keyframes.size() >= MAX_KEYFRAMES && !nearDetail) return false; // full: only close-ups may replace a wide shot
        if (!nearDetail && keyframes.size() >= MAX_KEYFRAMES - 15) return false; // keep room for close-ups of detail zones
        float minMove = nearDetail ? 0.1f : 0.3f, maxDot = nearDetail ? 0.985f : 0.966f;
        if (moveSinceLastFrame > 0.012f || turnSinceLastFrameDeg > 1.2f) return false; // blur risk
        float fx = -pose[8], fy = -pose[9], fz = -pose[10];
        for (Keyframe k : keyframes) {
            float dx = pose[12] - k.px, dy = pose[13] - k.py, dz = pose[14] - k.pz;
            float dot = fx * k.fwd[0] + fy * k.fwd[1] + fz * k.fwd[2];
            if (dx * dx + dy * dy + dz * dz < minMove * minMove && dot > maxDot) return false;
        }
        return true;
    }

    /** The wide shot whose nearest other photo is most similar (position + direction): the cheapest one to lose. */
    private int mostRedundant() {
        int best = -1; float bestD = Float.MAX_VALUE;
        for (int i = 0; i < keyframes.size(); i++) {
            Keyframe a = keyframes.get(i);
            if (a.closeUp) continue;
            float nearest = Float.MAX_VALUE;
            for (int j = 0; j < keyframes.size(); j++) {
                if (j == i) continue;
                Keyframe b = keyframes.get(j);
                float dx = a.px - b.px, dy = a.py - b.py, dz = a.pz - b.pz;
                float ang = 1f - (a.fwd[0] * b.fwd[0] + a.fwd[1] * b.fwd[1] + a.fwd[2] * b.fwd[2]);
                nearest = Math.min(nearest, (float) Math.sqrt(dx * dx + dy * dy + dz * dz) + ang * 3f);
            }
            if (nearest < bestD) { bestD = nearest; best = i; }
        }
        return best;
    }

    /** Copies the live camera texture into a new keyframe texture. */
    void capture(int cameraTex, float[] pose, float[] texIntr, short[] depth, int dw, int dh, float[] depIntr, float gain, int screenW, int screenH, boolean closeUp) {
        int[] t = new int[1];
        int replace = -1;
        if (keyframes.size() >= MAX_KEYFRAMES) {
            replace = mostRedundant();
            if (replace < 0) return;
            t[0] = keyframes.get(replace).tex; // reuse its texture
        } else {
            GLES20.glGenTextures(1, t, 0);
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        if (replace < 0) GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, TEX_W, TEX_H, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t[0], 0);
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glDeleteTextures(1, t, 0);
            return;
        }
        GLES20.glViewport(0, 0, TEX_W, TEX_H);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glUseProgram(copyProg);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTex);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(copyProg, "uTex"), 0);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(copyProg, "uGain"), gain);
        int aPos = GLES20.glGetAttribLocation(copyProg, "aPos"), aUv = GLES20.glGetAttribLocation(copyProg, "aUv");
        quad.position(0); quadUv.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, quadUv);
        GLES20.glEnableVertexAttribArray(aPos); GLES20.glEnableVertexAttribArray(aUv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aUv);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, screenW, screenH);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        Keyframe k = new Keyframe();
        k.tex = t[0];
        Matrix.invertM(k.view, 0, pose, 0);
        System.arraycopy(pose, 0, k.pose, 0, 16);
        k.fx = texIntr[0]; k.fy = texIntr[1]; k.cx = texIntr[2]; k.cy = texIntr[3]; k.w = texIntr[4]; k.h = texIntr[5];
        k.depth = depth; k.dw = dw; k.dh = dh;
        if (depIntr != null) { k.dfx = depIntr[0]; k.dfy = depIntr[1]; k.dcx = depIntr[2]; k.dcy = depIntr[3]; }
        k.px = pose[12]; k.py = pose[13]; k.pz = pose[14];
        k.fwd[0] = -pose[8]; k.fwd[1] = -pose[9]; k.fwd[2] = -pose[10];
        k.closeUp = closeUp;
        if (replace >= 0) keyframes.set(replace, k); else keyframes.add(k);
    }

    /** Picks the best photo for every triangle of the mesh (triangle soup), and builds per-photo draw groups. */
    /**
     * proxyFrom: triangles from this vertex index on are room-layout planes (walls, floor). They only take a photo
     * whose depth confirms the wall/floor is really visible there (so a bed is never painted onto the wall), and
     * when no photo covers them they get the fallback colour in col (or are dropped if its alpha is 0).
     * col holds 4 bytes per vertex for mesh and proxy vertices alike.
     */
    void build(FloatBuffer pos, ByteBuffer col, int verts, int proxyFrom) {
        List<FloatBuffer> groupPos = new ArrayList<>(), groupUv = new ArrayList<>();
        List<Integer> groupVerts = new ArrayList<>();
        List<Keyframe> keyframes = new ArrayList<>(this.keyframes);
        int texturedTris = 0, plainTris = 0;
        int nk = keyframes.size(), nt = verts / 3;
        int[] pick = new int[nt];
        int[] perKf = new int[nk];
        float[] v = new float[9];
        float[] uv = new float[6];
        for (int t = 0; t < nt; t++) {
            for (int i = 0; i < 9; i++) v[i] = pos.get(t * 9 + i);
            float cx = (v[0] + v[3] + v[6]) / 3, cy = (v[1] + v[4] + v[7]) / 3, cz = (v[2] + v[5] + v[8]) / 3;
            float ux = v[3] - v[0], uy = v[4] - v[1], uz = v[5] - v[2], wx = v[6] - v[0], wy = v[7] - v[1], wz = v[8] - v[2];
            float nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
            float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nl < 1e-9f) { pick[t] = -1; plainTris++; continue; }
            nx /= nl; ny /= nl; nz /= nl;
            int best = -1; float bestScore = 0;
            for (int k = 0; k < nk; k++) {
                Keyframe kf = keyframes.get(k);
                float dx = kf.px - cx, dy = kf.py - cy, dz = kf.pz - cz;
                float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dist < 0.15f || dist > 4.5f) continue;
                float cosA = Math.abs(nx * dx + ny * dy + nz * dz) / dist;
                if (cosA < 0.25f) continue;
                float score = cosA * cosA / dist;
                if (score <= bestScore) continue;
                if (!project(kf, v, uv)) continue;
                if (t * 3 >= proxyFrom ? !confirmed(kf, cx, cy, cz) : occluded(kf, cx, cy, cz)) continue;
                best = k; bestScore = score;
            }
            pick[t] = best;
            if (best >= 0) { perKf[best]++; texturedTris++; }
            else if (t * 3 >= proxyFrom && col.get(t * 12 + 3) == 0) pick[t] = -2; // uncovered cell of a guessed wall: leave it out
            else if (t * 3 >= proxyFrom) plainTris++; // uncovered wall/floor cell: filled with that surface's typical colour
            else plainTris++;
        }
        for (int k = 0; k < nk; k++) {
            groupPos.add(perKf[k] == 0 ? null : fbAlloc(perKf[k] * 9));
            groupUv.add(perKf[k] == 0 ? null : fbAlloc(perKf[k] * 6));
            groupVerts.add(perKf[k] * 3);
        }
        int plainVerts = plainTris * 3;
        FloatBuffer plainPos = fbAlloc(Math.max(1, plainVerts * 3));
        ByteBuffer plainCol = ByteBuffer.allocateDirect(Math.max(4, plainVerts * 4)).order(ByteOrder.nativeOrder());
        for (int t = 0; t < nt; t++) {
            for (int i = 0; i < 9; i++) v[i] = pos.get(t * 9 + i);
            int k = pick[t];
            if (k == -2) continue;
            if (k < 0) {
                plainPos.put(v);
                for (int i = 0; i < 12; i++) plainCol.put(i % 4 == 3 ? (byte) 255 : col.get(t * 12 + i));
            } else {
                project(keyframes.get(k), v, uv);
                groupPos.get(k).put(v);
                groupUv.get(k).put(uv);
            }
        }
        for (FloatBuffer b : groupPos) if (b != null) b.position(0);
        for (FloatBuffer b : groupUv) if (b != null) b.position(0);
        plainPos.position(0); plainCol.position(0);
        synchronized (this) {
            this.groupPos = groupPos; this.groupUv = groupUv; this.groupVerts = groupVerts;
            this.plainPos = plainPos; this.plainCol = plainCol; this.plainVerts = plainVerts;
            this.texturedTris = texturedTris; this.plainTris = plainTris;
        }
        ready = true;
    }

    /** Projects the 3 vertices into the keyframe; uv in 0..1 (t = image row / height). False if outside the photo. */
    private static boolean project(Keyframe k, float[] v, float[] uv) {
        float[] m = k.view;
        for (int i = 0; i < 3; i++) {
            float x = v[i * 3], y = v[i * 3 + 1], z = v[i * 3 + 2];
            float cx = m[0] * x + m[4] * y + m[8] * z + m[12];
            float cy = m[1] * x + m[5] * y + m[9] * z + m[13];
            float cz = m[2] * x + m[6] * y + m[10] * z + m[14];
            float d = -cz;
            if (d < 0.1f) return false;
            float u = k.fx * cx / d + k.cx, vv = k.cy - k.fy * cy / d;
            float s = u / k.w, t = vv / k.h;
            if (s < 0.01f || s > 0.99f || t < 0.01f || t > 0.99f) return false;
            uv[i * 2] = s; uv[i * 2 + 1] = t;
        }
        return true;
    }

    /** True if the keyframe's depth image says something closer was in front of this point. */
    private static boolean occluded(Keyframe k, float x, float y, float z) {
        if (k.depth == null || k.dfx == 0) return false;
        float[] m = k.view;
        float cx = m[0] * x + m[4] * y + m[8] * z + m[12];
        float cy = m[1] * x + m[5] * y + m[9] * z + m[13];
        float cz = m[2] * x + m[6] * y + m[10] * z + m[14];
        float d = -cz;
        if (d < 0.1f) return true;
        int u = (int) (k.dfx * cx / d + k.dcx), v = (int) (k.dcy - k.dfy * cy / d);
        if (u < 0 || v < 0 || u >= k.dw || v >= k.dh) return false;
        int mm = k.depth[v * k.dw + u] & 0xFFFF;
        if (mm == 0) return false;
        return d > mm * 0.001f + 0.1f + d * 0.04f;
    }

    /** The keyframe's depth saw a surface right here (within 12 cm + 5%), so this photo pixel belongs to it. */
    private static boolean confirmed(Keyframe k, float x, float y, float z) {
        if (k.depth == null || k.dfx == 0) return false;
        float[] m = k.view;
        float cx = m[0] * x + m[4] * y + m[8] * z + m[12];
        float cy = m[1] * x + m[5] * y + m[9] * z + m[13];
        float cz = m[2] * x + m[6] * y + m[10] * z + m[14];
        float d = -cz;
        if (d < 0.1f) return false;
        int u = (int) (k.dfx * cx / d + k.dcx), v = (int) (k.dcy - k.dfy * cy / d);
        int hits = 0, seen = 0;
        for (int dv = -1; dv <= 1; dv++) for (int du = -1; du <= 1; du++) {
            int uu = u + du, vv = v + dv;
            if (uu < 0 || vv < 0 || uu >= k.dw || vv >= k.dh) continue;
            int mm = k.depth[vv * k.dw + uu] & 0xFFFF;
            if (mm == 0) continue;
            seen++;
            if (Math.abs(mm * 0.001f - d) < 0.12f + d * 0.05f) hits++;
        }
        return seen > 0 && hits * 2 >= seen;
    }

    void draw(float[] mvp, boolean cut, float[] center, float dirX, float dirZ, float cutD, float dir2X, float dir2Z, float cutD2, float floorY, float ceilY) {
        if (!ready) return;
        List<FloatBuffer> groupPos, groupUv; List<Integer> groupVerts; FloatBuffer plainPos; ByteBuffer plainCol; int plainVerts;
        synchronized (this) {
            groupPos = this.groupPos; groupUv = this.groupUv; groupVerts = this.groupVerts;
            plainPos = this.plainPos; plainCol = this.plainCol; plainVerts = this.plainVerts;
        }
        GLES20.glUseProgram(meshProg);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(meshProg, "uMvp"), 1, false, mvp, 0);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(meshProg, "uCut"), cut ? 1f : 0f);
        GLES20.glUniform3f(GLES20.glGetUniformLocation(meshProg, "uCenter"), center[0], center[1], center[2]);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(meshProg, "uDir"), dirX, dirZ);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(meshProg, "uCutD"), cutD);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(meshProg, "uDir2"), dir2X, dir2Z);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(meshProg, "uCutD2"), cutD2);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(meshProg, "uFloor"), floorY);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(meshProg, "uCeil"), ceilY);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(meshProg, "uTex"), 0);
        int uUse = GLES20.glGetUniformLocation(meshProg, "uUseTex");
        int aPos = GLES20.glGetAttribLocation(meshProg, "aPos");
        int aUv = GLES20.glGetAttribLocation(meshProg, "aUv");
        int aCol = GLES20.glGetAttribLocation(meshProg, "aColor");
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glEnableVertexAttribArray(aPos);
        // photo-textured groups
        GLES20.glUniform1f(uUse, 1f);
        GLES20.glVertexAttrib4f(aCol, 1, 1, 1, 1);
        GLES20.glEnableVertexAttribArray(aUv);
        for (int k = 0; k < groupPos.size(); k++) {
            int n = groupVerts.get(k);
            if (n == 0) continue;
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, keyframes.get(k).tex);
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, groupPos.get(k));
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, groupUv.get(k));
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, n);
        }
        GLES20.glDisableVertexAttribArray(aUv);
        // triangles no photo covered: fused point colours
        if (plainVerts > 0) {
            GLES20.glUniform1f(uUse, 0f);
            GLES20.glVertexAttrib2f(aUv, 0, 0);
            GLES20.glEnableVertexAttribArray(aCol);
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, plainPos);
            GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_UNSIGNED_BYTE, true, 0, plainCol);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, plainVerts);
            GLES20.glDisableVertexAttribArray(aCol);
        }
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    private static FloatBuffer fb(float[] a) {
        FloatBuffer b = fbAlloc(a.length);
        b.put(a).position(0);
        return b;
    }

    private static FloatBuffer fbAlloc(int floats) {
        return ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }
}
