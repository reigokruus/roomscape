package com.hackathon.setscan;

import android.media.Image;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import com.google.ar.core.Camera;
import com.google.ar.core.CameraIntrinsics;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.NotYetAvailableException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Scans the room with ARCore depth into a colored voxel point cloud, then shows it in an orbit viewer. */
public class ScanRenderer implements GLSurfaceView.Renderer {
    private static final int MAX_POINTS = 2_000_000;
    private static final float VOXEL = 0.02f;
    private static final int MAX_PATH = 20_000;

    private final MainActivity activity;
    private Session session;
    private boolean depthSupported, rawDepth;
    private volatile boolean viewing;

    // Point storage
    private final FloatBuffer pos = ByteBuffer.allocateDirect(MAX_POINTS * 12).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final ByteBuffer col = ByteBuffer.allocateDirect(MAX_POINTS * 4).order(ByteOrder.nativeOrder());
    private int count, uploaded;
    private final LongSet voxels = new LongSet(1 << 22);
    private final FloatBuffer path = ByteBuffer.allocateDirect(MAX_PATH * 12).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private int pathCount;
    private float lastPx = 1e9f, lastPy, lastPz;

    // GL objects
    private int bgTex, bgProg, ptProg, posVbo, colVbo;
    private int width = 1, height = 1;
    private boolean textureSet;
    private final FloatBuffer quad = fb(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
    private final FloatBuffer quadUv = fb(new float[8]);

    // Viewer state
    private float yaw = 0.6f, pitch = 0.6f, dist = 4f;
    private final float[] target = new float[3];
    private boolean topView;
    private long lastDepthTs = -1;
    private int frameNo;
    // diagnostics shown on screen
    private int depthFrames, depthPixels, depthRejectedRange, depthNotYet;
    private String lastError = "", depthSize = "";

    private final float[] proj = new float[16], view = new float[16], mvp = new float[16];

    ScanRenderer(MainActivity a) { activity = a; }

    void setSession(Session s, boolean depth, boolean raw) {
        session = s; depthSupported = depth; rawDepth = raw; textureSet = false;
    }

    boolean isViewing() { return viewing; }

    // ---- called on GL thread via queueEvent ----
    void finishScan() {
        viewing = true;
        topView = false;
        if (count > 0) {
            float minX = 1e9f, minY = 1e9f, minZ = 1e9f, maxX = -1e9f, maxY = -1e9f, maxZ = -1e9f;
            for (int i = 0; i < count; i++) {
                float x = pos.get(i * 3), y = pos.get(i * 3 + 1), z = pos.get(i * 3 + 2);
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            }
            target[0] = (minX + maxX) / 2; target[1] = (minY + maxY) / 2; target[2] = (minZ + maxZ) / 2;
            float ext = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
            dist = Math.max(1f, ext * 1.2f);
        }
        yaw = 0.6f; pitch = 0.6f;
        activity.updateStatus(diagnostics() + "\n" + count + " points captured. Drag to rotate, pinch to zoom, two fingers to move. Yellow line = your walking path.");
    }

    void reset() {
        count = 0; uploaded = 0; pathCount = 0; lastPx = 1e9f;
        depthFrames = depthPixels = depthRejectedRange = depthNotYet = 0; lastError = "";
        voxels.clear();
        viewing = false;
    }

    boolean toggleTopView() { topView = !topView; return topView; }

    void orbit(float dx, float dy) {
        yaw -= dx * 0.008f;
        pitch = clamp(pitch + dy * 0.008f, -1.5f, 1.55f);
    }

    void zoom(float f) { dist = clamp(dist / f, 0.2f, 100f); }

    void pan(float dx, float dy) {
        float s = dist * 0.0015f;
        float cy = (float) Math.cos(yaw), sy = (float) Math.sin(yaw);
        if (topView) {
            target[0] -= dx * s; target[2] -= dy * s;
        } else {
            // right vector (cos yaw, 0, -sin yaw); move up/down along world Y
            target[0] -= dx * s * cy; target[2] += dx * s * sy;
            target[1] += dy * s;
        }
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.08f, 0.08f, 0.1f, 1f);
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        bgTex = t[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, bgTex);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        textureSet = false;

        bgProg = program(
                "attribute vec4 aPos; attribute vec2 aUv; varying vec2 vUv;" +
                "void main(){ gl_Position = aPos; vUv = aUv; }",
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float; varying vec2 vUv; uniform samplerExternalOES uTex;" +
                "void main(){ gl_FragColor = texture2D(uTex, vUv); }");
        ptProg = program(
                "uniform mat4 uMvp; uniform float uSize; attribute vec4 aPos; attribute vec4 aColor; varying vec4 vColor;" +
                "void main(){ gl_Position = uMvp * aPos; gl_PointSize = clamp(uSize / max(gl_Position.w, 0.05), 2.0, 40.0); vColor = aColor; }",
                "precision mediump float; varying vec4 vColor; void main(){ gl_FragColor = vColor; }");

        int[] b = new int[2];
        GLES20.glGenBuffers(2, b, 0);
        posVbo = b[0]; colVbo = b[1];
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, posVbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, MAX_POINTS * 12, null, GLES20.GL_DYNAMIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, colVbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, MAX_POINTS * 4, null, GLES20.GL_DYNAMIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        uploaded = 0;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        width = w; height = h;
        GLES20.glViewport(0, 0, w, h);
        if (session != null) session.setDisplayGeometry(0, w, h);
        displaySet = session != null;
    }

    private boolean displaySet;

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        if (viewing) { drawViewer(); return; }
        if (session == null) return;
        if (!displaySet) { session.setDisplayGeometry(0, width, height); displaySet = true; }
        if (!textureSet) { session.setCameraTextureName(bgTex); textureSet = true; }
        Frame frame;
        try {
            frame = session.update();
        } catch (Exception e) {
            return;
        }
        Camera camera = frame.getCamera();
        if (frame.hasDisplayGeometryChanged() || frameNo == 0) {
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad,
                    Coordinates2d.TEXTURE_NORMALIZED, quadUv);
        }
        frameNo++;
        if (frame.getTimestamp() != 0) drawBackground();

        TrackingState ts = camera.getTrackingState();
        if (ts == TrackingState.TRACKING) {
            try {
                integrate(frame, camera);
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            recordPath(camera.getPose());
            camera.getProjectionMatrix(proj, 0, 0.05f, 100f);
            camera.getViewMatrix(view, 0);
            Matrix.multiplyMM(mvp, 0, proj, 0, view, 0);
            uploadNew();
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            drawPoints(mvp, 0.85f);
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        }
        if (frameNo % 15 == 0) {
            String s;
            if (ts != TrackingState.TRACKING) {
                s = "Move the phone slowly to start tracking...\n" + camera.getTrackingFailureReason();
            } else {
                s = "Scanning: " + count + " points\nWalk slowly, point at walls, floor and furniture. Tap Finish when done.\n"
                        + diagnostics();
            }
            activity.updateStatus(s);
        }
    }

    // ---------------- scanning ----------------

    private Image cpuImage;

    private void integrate(Frame frame, Camera camera) {
        if (count >= MAX_POINTS) return;
        if (!depthSupported) {
            integrateFeaturePoints(frame, camera);
            return;
        }
        Image depth = null, conf = null;
        try {
            depth = rawDepth ? frame.acquireRawDepthImage16Bits() : frame.acquireDepthImage16Bits();
            if (depth.getTimestamp() == lastDepthTs) return;
            lastDepthTs = depth.getTimestamp();
            if (rawDepth) conf = frame.acquireRawDepthConfidenceImage();
            try { cpuImage = frame.acquireCameraImage(); } catch (NotYetAvailableException e) { cpuImage = null; }

            int dw = depth.getWidth(), dh = depth.getHeight();
            depthFrames++;
            depthSize = dw + "x" + dh;
            CameraIntrinsics ti = camera.getTextureIntrinsics();
            CameraIntrinsics ii = camera.getImageIntrinsics();
            int[] tDim = ti.getImageDimensions(), iDim = ii.getImageDimensions();
            float dAsp = (float) dw / dh;
            float tAsp = (float) tDim[0] / tDim[1], iAsp = (float) iDim[0] / iDim[1];
            CameraIntrinsics di = Math.abs(tAsp - dAsp) <= Math.abs(iAsp - dAsp) ? ti : ii;
            int[] diDim = di.getImageDimensions();
            float sx = (float) dw / diDim[0], sy = (float) dh / diDim[1];
            float fx = di.getFocalLength()[0] * sx, fy = di.getFocalLength()[1] * sy;
            float cx = di.getPrincipalPoint()[0] * sx, cy = di.getPrincipalPoint()[1] * sy;

            // color projection into CPU image
            float ifx = ii.getFocalLength()[0], ify = ii.getFocalLength()[1];
            float icx = ii.getPrincipalPoint()[0], icy = ii.getPrincipalPoint()[1];

            Image.Plane dp = depth.getPlanes()[0];
            ShortBuffer dbuf = dp.getBuffer().order(ByteOrder.nativeOrder()).asShortBuffer();
            int dRow = dp.getRowStride() / 2, dPix = dp.getPixelStride() / 2;
            ByteBuffer cbuf = null; int cRow = 0, cPix = 1;
            if (conf != null) {
                Image.Plane cp = conf.getPlanes()[0];
                cbuf = cp.getBuffer(); cRow = cp.getRowStride(); cPix = cp.getPixelStride();
            }

            float[] m = new float[16];
            camera.getPose().toMatrix(m, 0);
            int[] rgb = new int[3];
            for (int v = 0; v < dh; v++) {
                for (int u = 0; u < dw; u++) {
                    int mm = dbuf.get(v * dRow + u * dPix) & 0xFFFF;
                    if (mm < 200 || mm > 5000) { depthRejectedRange++; continue; }
                    depthPixels++;
                    if (cbuf != null && (cbuf.get(v * cRow + u * cPix) & 0xFF) < 110) continue;
                    float d = mm * 0.001f;
                    float xc = (u + 0.5f - cx) * d / fx;
                    float yc = (cy - (v + 0.5f)) * d / fy;
                    float zc = -d;
                    float wx = m[0] * xc + m[4] * yc + m[8] * zc + m[12];
                    float wy = m[1] * xc + m[5] * yc + m[9] * zc + m[13];
                    float wz = m[2] * xc + m[6] * yc + m[10] * zc + m[14];
                    if (!voxels.add(key(wx, wy, wz))) continue;
                    sampleColor(xc / d * ifx + icx, icy - yc / d * ify, rgb);
                    addPoint(wx, wy, wz, rgb);
                    if (count >= MAX_POINTS) return;
                }
            }
        } catch (NotYetAvailableException e) {
            depthNotYet++;
            // no depth yet
        } finally {
            if (depth != null) depth.close();
            if (conf != null) conf.close();
            if (cpuImage != null) { cpuImage.close(); cpuImage = null; }
        }
    }

    private void integrateFeaturePoints(Frame frame, Camera camera) {
        try (PointCloud pc = frame.acquirePointCloud()) {
            if (pc.getTimestamp() == lastDepthTs) return;
            lastDepthTs = pc.getTimestamp();
            try { cpuImage = frame.acquireCameraImage(); } catch (NotYetAvailableException e) { cpuImage = null; }
            CameraIntrinsics ii = camera.getImageIntrinsics();
            float ifx = ii.getFocalLength()[0], ify = ii.getFocalLength()[1];
            float icx = ii.getPrincipalPoint()[0], icy = ii.getPrincipalPoint()[1];
            Pose inv = camera.getPose().inverse();
            FloatBuffer p = pc.getPoints();
            int n = p.remaining() / 4;
            float[] w = new float[3], c = new float[3];
            int[] rgb = new int[3];
            for (int i = 0; i < n; i++) {
                w[0] = p.get(i * 4); w[1] = p.get(i * 4 + 1); w[2] = p.get(i * 4 + 2);
                if (p.get(i * 4 + 3) < 0.3f) continue;
                if (!voxels.add(key(w[0], w[1], w[2]))) continue;
                inv.transformPoint(w, 0, c, 0);
                if (c[2] < -0.05f) {
                    float d = -c[2];
                    sampleColor(c[0] / d * ifx + icx, icy - c[1] / d * ify, rgb);
                } else { rgb[0] = rgb[1] = rgb[2] = 200; }
                addPoint(w[0], w[1], w[2], rgb);
                if (count >= MAX_POINTS) break;
            }
        } finally {
            if (cpuImage != null) { cpuImage.close(); cpuImage = null; }
        }
    }

    private String diagnostics() {
        return "[depth " + (depthSupported ? "on " + depthSize : "NOT SUPPORTED") + " | frames " + depthFrames
                + " | valid px " + depthPixels + " | out of range " + depthRejectedRange
                + " | not ready " + depthNotYet + (lastError.isEmpty() ? "" : " | err " + lastError) + "]";
    }

    private void sampleColor(float fu, float fv, int[] out) {
        Image img = cpuImage;
        int u = (int) fu, v = (int) fv;
        if (img == null || u < 0 || v < 0 || u >= img.getWidth() || v >= img.getHeight()) {
            out[0] = out[1] = out[2] = 180;
            return;
        }
        Image.Plane[] pl = img.getPlanes();
        int y = pl[0].getBuffer().get(v * pl[0].getRowStride() + u * pl[0].getPixelStride()) & 0xFF;
        int uvOffU = (v / 2) * pl[1].getRowStride() + (u / 2) * pl[1].getPixelStride();
        int uvOffV = (v / 2) * pl[2].getRowStride() + (u / 2) * pl[2].getPixelStride();
        int cb = (pl[1].getBuffer().get(uvOffU) & 0xFF) - 128;
        int cr = (pl[2].getBuffer().get(uvOffV) & 0xFF) - 128;
        out[0] = clampI((int) (y + 1.402f * cr));
        out[1] = clampI((int) (y - 0.344f * cb - 0.714f * cr));
        out[2] = clampI((int) (y + 1.772f * cb));
    }

    private void addPoint(float x, float y, float z, int[] rgb) {
        int i = count * 3;
        pos.put(i, x); pos.put(i + 1, y); pos.put(i + 2, z);
        int j = count * 4;
        col.put(j, (byte) rgb[0]); col.put(j + 1, (byte) rgb[1]); col.put(j + 2, (byte) rgb[2]); col.put(j + 3, (byte) 255);
        count++;
    }

    private void recordPath(Pose p) {
        float x = p.tx(), y = p.ty(), z = p.tz();
        float dx = x - lastPx, dy = y - lastPy, dz = z - lastPz;
        if (dx * dx + dy * dy + dz * dz < 0.0025f || pathCount >= MAX_PATH) return;
        path.put(pathCount * 3, x); path.put(pathCount * 3 + 1, y); path.put(pathCount * 3 + 2, z);
        pathCount++;
        lastPx = x; lastPy = y; lastPz = z;
    }

    private static long key(float x, float y, float z) {
        long ix = (long) Math.floor(x / VOXEL) + (1 << 20);
        long iy = (long) Math.floor(y / VOXEL) + (1 << 20);
        long iz = (long) Math.floor(z / VOXEL) + (1 << 20);
        return (ix & 0x1FFFFF) | ((iy & 0x1FFFFF) << 21) | ((iz & 0x1FFFFF) << 42);
    }

    // ---------------- drawing ----------------

    private void uploadNew() {
        if (count <= uploaded) return;
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, posVbo);
        pos.position(uploaded * 3);
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, uploaded * 12, (count - uploaded) * 12, pos);
        pos.position(0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, colVbo);
        col.position(uploaded * 4);
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, uploaded * 4, (count - uploaded) * 4, col);
        col.position(0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        uploaded = count;
    }

    private void drawBackground() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);
        GLES20.glUseProgram(bgProg);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, bgTex);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(bgProg, "uTex"), 0);
        int aPos = GLES20.glGetAttribLocation(bgProg, "aPos");
        int aUv = GLES20.glGetAttribLocation(bgProg, "aUv");
        quad.position(0); quadUv.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, quadUv);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glEnableVertexAttribArray(aUv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aUv);
        GLES20.glDepthMask(true);
    }

    private void drawPoints(float[] m, float sizeScale) {
        if (count == 0) return;
        GLES20.glUseProgram(ptProg);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(ptProg, "uMvp"), 1, false, m, 0);
        float pxPerMeter = height / (2f * (float) Math.tan(Math.toRadians(30)));
        GLES20.glUniform1f(GLES20.glGetUniformLocation(ptProg, "uSize"), VOXEL * pxPerMeter * 1.4f * sizeScale);
        int aPos = GLES20.glGetAttribLocation(ptProg, "aPos");
        int aCol = GLES20.glGetAttribLocation(ptProg, "aColor");
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, posVbo);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, colVbo);
        GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_UNSIGNED_BYTE, true, 0, 0);
        GLES20.glEnableVertexAttribArray(aCol);
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, uploaded);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aCol);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }

    private void drawPath(float[] m) {
        if (pathCount < 2) return;
        GLES20.glUseProgram(ptProg);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(ptProg, "uMvp"), 1, false, m, 0);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(ptProg, "uSize"), 4f);
        int aPos = GLES20.glGetAttribLocation(ptProg, "aPos");
        int aCol = GLES20.glGetAttribLocation(ptProg, "aColor");
        path.position(0);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, path);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aCol);
        GLES20.glVertexAttrib4f(aCol, 1f, 0.85f, 0.1f, 1f);
        GLES20.glLineWidth(4f);
        GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, pathCount);
        GLES20.glDisableVertexAttribArray(aPos);
    }

    private void drawViewer() {
        uploadNew();
        float aspect = (float) width / height;
        Matrix.perspectiveM(proj, 0, 60f, aspect, 0.02f, 500f);
        float p = topView ? 1.5697f : pitch;
        float y = topView ? 0f : yaw;
        float ex = target[0] + dist * (float) (Math.cos(p) * Math.sin(y));
        float ey = target[1] + dist * (float) Math.sin(p);
        float ez = target[2] + dist * (float) (Math.cos(p) * Math.cos(y));
        if (topView) Matrix.setLookAtM(view, 0, target[0], target[1] + dist, target[2], target[0], target[1], target[2], 0, 0, -1);
        else Matrix.setLookAtM(view, 0, ex, ey, ez, target[0], target[1], target[2], 0, 1, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        drawPoints(mvp, 1f);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        drawPath(mvp);
    }

    // ---------------- helpers ----------------

    private static int program(String vs, String fs) {
        int v = shader(GLES20.GL_VERTEX_SHADER, vs), f = shader(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        return p;
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("Shader error: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private static FloatBuffer fb(float[] a) {
        FloatBuffer b = ByteBuffer.allocateDirect(a.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(a).position(0);
        return b;
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    private static int clampI(int v) { return Math.max(0, Math.min(255, v)); }

    /** Open-addressing hash set of longs. */
    static final class LongSet {
        private static final long EMPTY = Long.MIN_VALUE;
        private final long[] table;
        private final int mask;

        LongSet(int capacityPow2) {
            table = new long[capacityPow2];
            mask = capacityPow2 - 1;
            clear();
        }

        void clear() { Arrays.fill(table, EMPTY); }

        boolean add(long k) {
            long h = k * 0x9E3779B97F4A7C15L;
            int i = (int) (h ^ (h >>> 32)) & mask;
            while (true) {
                long t = table[i];
                if (t == EMPTY) { table[i] = k; return true; }
                if (t == k) return false;
                i = (i + 1) & mask;
            }
        }
    }
}
