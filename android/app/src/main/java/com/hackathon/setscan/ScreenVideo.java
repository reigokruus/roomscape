package com.hackathon.setscan;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * What the user saw while scanning, as a small video (rk 10 Nov 13:56: "can you screen record the app content yourself
 * automatically?"). No screen-capture permission: on the GL thread, a few times a second, the finished frame is scaled
 * into a hardware H.264 encoder's input surface (one blit), and the hint, tracking state and scan time are burnt in at
 * the top as text (the Android views over the GL view aren't in the GL frame). The encoder's output is written by its
 * own thread. ~480 px wide, FPS frames a second. Kept in the scan folder as screen.mp4, never in the .rmsc.
 */
final class ScreenVideo {
    static final int WIDTH = 480, FPS = 8, BITRATE = 1_200_000;

    private final File out;
    private MediaCodec codec;
    private MediaMuxer muxer;
    private Surface input;
    private EGLDisplay dpy;
    private EGLContext ctx;
    private EGLSurface encSurf = EGL14.EGL_NO_SURFACE;
    private int w, h, track = -1, textTex, textProg, bandH;
    private long firstNs = -1, lastFrameNs, textAt;
    private String lastText = "";
    private Thread drainer;
    private volatile boolean stopping;
    private boolean muxerStarted, failed;
    private final FloatBuffer quad = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    int frames;
    /** For the log: how long the GL thread spent on it. */
    double glMsTotal;

    ScreenVideo(File out) { this.out = out; }

    boolean failed() { return failed; }

    /** On the GL thread with the window's context current. */
    void start(int srcW, int srcH) throws Exception {
        w = WIDTH; h = Math.round(WIDTH * srcH / (float) srcW / 16f) * 16;
        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        input = codec.createInputSurface();
        codec.start();
        muxer = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        dpy = EGL14.eglGetCurrentDisplay();
        ctx = EGL14.eglGetCurrentContext();
        // a config the encoder accepts (recordable), compatible with the window's context
        int[] attrs = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, 0x3142 /* EGL_RECORDABLE_ANDROID */, 1, EGL14.EGL_NONE};
        EGLConfig[] cfg = new EGLConfig[1]; int[] n = new int[1];
        if (!EGL14.eglChooseConfig(dpy, attrs, 0, cfg, 0, 1, n, 0) || n[0] < 1) {
            // fall back to the context's own config
            int[] id = new int[1];
            EGL14.eglQueryContext(dpy, ctx, EGL14.EGL_CONFIG_ID, id, 0);
            int[] a2 = {EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE};
            if (!EGL14.eglChooseConfig(dpy, a2, 0, cfg, 0, 1, n, 0) || n[0] < 1) throw new Exception("no EGL config for the encoder");
        }
        encSurf = EGL14.eglCreateWindowSurface(dpy, cfg[0], input, new int[]{EGL14.EGL_NONE}, 0);
        recordableCfg = n[0] >= 1 && cfg[0] != null;
        if (encSurf == null || encSurf == EGL14.EGL_NO_SURFACE) throw new Exception("encoder surface: EGL error " + EGL14.eglGetError());
        drainer = new Thread(this::drainLoop, "screen-video");
        drainer.start();
    }

    private boolean recordableCfg, triedOwnConfig;

    private boolean useOwnConfig() {
        triedOwnConfig = true;
        int[] id = new int[1];
        EGL14.eglQueryContext(dpy, ctx, EGL14.EGL_CONFIG_ID, id, 0);
        EGLConfig[] cfg = new EGLConfig[1]; int[] n = new int[1];
        if (!EGL14.eglChooseConfig(dpy, new int[]{EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE}, 0, cfg, 0, 1, n, 0) || n[0] < 1) return false;
        EGL14.eglDestroySurface(dpy, encSurf);
        encSurf = EGL14.eglCreateWindowSurface(dpy, cfg[0], input, new int[]{EGL14.EGL_NONE}, 0);
        return encSurf != null && encSurf != EGL14.EGL_NO_SURFACE;
    }

    /** On the GL thread right after the frame is drawn (before it's shown). `text`: the burnt-in lines. */
    void frame(int srcW, int srcH, String text) {
        if (failed || encSurf == EGL14.EGL_NO_SURFACE) return;
        long now = System.nanoTime();
        if (lastFrameNs != 0 && now - lastFrameNs < 1_000_000_000L / FPS) return;
        if (!EGL14.eglGetCurrentContext().equals(ctx)) { failed = true; return; }   // the GL context was replaced (app paused)
        lastFrameNs = now;
        long t0 = now;
        EGLSurface draw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW), read = EGL14.eglGetCurrentSurface(EGL14.EGL_READ);
        try {
            if (!EGL14.eglMakeCurrent(dpy, encSurf, draw, ctx)) {
                // some drivers want the surface in the context's own config: once, try that
                if (triedOwnConfig || !useOwnConfig()) { failed = true; return; }
                if (!EGL14.eglMakeCurrent(dpy, encSurf, draw, ctx)) { failed = true; return; }
            }
            // read = the window's back buffer (the frame just drawn), draw = the encoder
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0);
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, w, h);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            GLES30.glBlitFramebuffer(0, 0, srcW, srcH, 0, 0, w, h, GLES20.GL_COLOR_BUFFER_BIT, GLES20.GL_LINEAR);
            drawText(text, now);
            if (firstNs < 0) firstNs = now;
            EGLExt.eglPresentationTimeANDROID(dpy, encSurf, now);
            EGL14.eglSwapBuffers(dpy, encSurf);
            frames++;
        } catch (Throwable e) {
            failed = true;
        } finally {
            EGL14.eglMakeCurrent(dpy, draw, read, ctx);
            GLES20.glViewport(0, 0, srcW, srcH);
        }
        glMsTotal += (System.nanoTime() - t0) / 1e6;
    }

    private void drawText(String text, long now) {
        if (textProg == 0) {
            textProg = ScanRenderer.program("attribute vec4 aPos; attribute vec2 aUv; varying vec2 vUv; void main(){ gl_Position = aPos; vUv = aUv; }",
                    "precision mediump float; varying vec2 vUv; uniform sampler2D uTex; void main(){ gl_FragColor = texture2D(uTex, vUv); }");
            int[] t = new int[1]; GLES20.glGenTextures(1, t, 0); textTex = t[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textTex);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textTex);
        if (!text.equals(lastText) || textAt == 0) {   // redrawn only when it changes
            lastText = text; textAt = now;
            String[] lines = text.split("\n");
            float size = 15f; bandH = (int) (lines.length * size * 1.3f + 8);
            Bitmap b = Bitmap.createBitmap(w, bandH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.drawColor(Color.argb(170, 0, 0, 0));
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(Color.WHITE); p.setTextSize(size);
            for (int i = 0; i < lines.length; i++) c.drawText(lines[i], 6, 4 + size * (1.3f * i + 1), p);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
            b.recycle();
        }
        float top = 1f, bottom = 1f - 2f * bandH / h;
        quad.clear();
        quad.put(new float[]{-1, bottom, 0, 1, 1, bottom, 1, 1, -1, top, 0, 0, 1, top, 1, 0}).position(0);
        GLES20.glUseProgram(textProg);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        int aPos = GLES20.glGetAttribLocation(textProg, "aPos"), aUv = GLES20.glGetAttribLocation(textProg, "aUv");
        quad.position(0); GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
        quad.position(2); GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aPos); GLES20.glEnableVertexAttribArray(aUv);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(textProg, "uTex"), 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aUv);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (true) {
                int i = codec.dequeueOutputBuffer(info, 20_000);
                if (i == MediaCodec.INFO_TRY_AGAIN_LATER) { if (stopping && muxerStarted == false && frames == 0) break; continue; }
                if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = muxer.addTrack(codec.getOutputFormat()); muxer.start(); muxerStarted = true; continue; }
                if (i < 0) continue;
                ByteBuffer buf = codec.getOutputBuffer(i);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0 && muxerStarted && buf != null) { buf.position(info.offset); buf.limit(info.offset + info.size); muxer.writeSampleData(track, buf, info); }
                codec.releaseOutputBuffer(i, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        } catch (Throwable e) {
            failed = true;
        }
    }

    /** On the GL thread: lets go of the GL side. Then finish() on any thread. */
    void releaseGl() {
        stopping = true;
        try { if (encSurf != EGL14.EGL_NO_SURFACE && dpy != null) EGL14.eglDestroySurface(dpy, encSurf); } catch (Throwable ignored) { }
        encSurf = EGL14.EGL_NO_SURFACE;
        if (textTex != 0) { try { GLES20.glDeleteTextures(1, new int[]{textTex}, 0); } catch (Throwable ignored) { } textTex = 0; }
    }

    /** Waits for the encoder and closes the file (up to a few seconds: not on the GL thread). Whether it's usable. */
    boolean finish() {
        stopping = true;
        try { codec.signalEndOfInputStream(); } catch (Throwable ignored) { }
        try { if (drainer != null) drainer.join(3000); } catch (InterruptedException ignored) { }
        boolean ok = muxerStarted && frames > 0;
        try { codec.stop(); } catch (Throwable ignored) { }
        try { codec.release(); } catch (Throwable ignored) { }
        try { if (muxerStarted) muxer.stop(); } catch (Throwable e) { ok = false; }
        try { muxer.release(); } catch (Throwable ignored) { }
        try { input.release(); } catch (Throwable ignored) { }
        return ok;
    }
}
