package com.hackathon.setscan;

import android.media.Image;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Plane;
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
    private final LongIntMap voxels = new LongIntMap(1 << 22);
    private final byte[] obs = new byte[MAX_POINTS]; // how many times each point was seen (capped)
    private int refineCursor; private boolean refined; private int refinedTotal, droppedNoise;
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
    private int frameNo, updateErrors;
    // diagnostics shown on screen
    private int depthFrames, depthPixels, depthRejectedRange, depthRejectedConf, depthRejectedEdge, depthNotYet;
    private String lastError = "", depthSize = "";

    private final float[] proj = new float[16], view = new float[16], mvp = new float[16];

    ScanRenderer(MainActivity a) { activity = a; }

    // Depth modes to try in order. If tracking doesn't start within a few seconds, move to the next one.
    private final java.util.List<Config.DepthMode> modes = new java.util.ArrayList<>();
    private int modeIndex;
    private long notTrackingSince;
    private boolean everTracked;
    private String modeLabel = "";
    private TrackingState lastTs;
    private long lastCamTs = -1, camTsChangedAt, lostSince;
    private boolean stallLogged;
    private int recoveries;
    private final StringBuilder log = new StringBuilder();
    private final long t0 = System.currentTimeMillis();
    private final java.util.Set<String> loggedErrors = new java.util.HashSet<>();

    synchronized void log(String msg) {
        if (log.length() > 20000) return;
        log.append(String.format(java.util.Locale.US, "%6.1fs  %s%n", (System.currentTimeMillis() - t0) / 1000f, msg));
    }

    private void logError(String where, Exception e) {
        String m = where + ": " + e.getClass().getSimpleName() + " " + e.getMessage();
        lastError = m;
        if (loggedErrors.add(m)) log("ERROR " + m);
    }

    /** Full report for the "Copy log" button. */
    synchronized String report() {
        StringBuilder r = new StringBuilder("CineX Android diagnostics\n");
        r.append("Device: ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
                .append(" (").append(android.os.Build.DEVICE).append("), Android ").append(android.os.Build.VERSION.RELEASE)
                .append(" / SDK ").append(android.os.Build.VERSION.SDK_INT).append('\n');
        try {
            r.append("ARCore (Play Services for AR): ").append(activity.getPackageManager()
                    .getPackageInfo("com.google.ar.core", 0).versionName).append('\n');
        } catch (Exception e) { r.append("ARCore version: unknown\n"); }
        r.append("Modes tried: ").append(modes).append(", current ").append(modeLabel).append('\n');
        r.append("Tracking: ").append(lastTs).append(", ever tracked ").append(everTracked)
                .append(", frames ").append(frameNo).append(", update errors ").append(updateErrors).append(", session restarts ").append(recoveries).append('\n');
        r.append("Points: ").append(count).append(", path samples ").append(pathCount).append('\n');
        r.append(diagnostics()).append("\n\nEvent log:\n").append(log);
        return r.toString();
    }

    void setSession(Session s) {
        session = s; textureSet = false;
        modes.clear();
        if (s.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)) modes.add(Config.DepthMode.RAW_DEPTH_ONLY);
        if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) modes.add(Config.DepthMode.AUTOMATIC);
        modes.add(Config.DepthMode.DISABLED);
        log("session created, depth modes to try: " + modes);
        try {
            com.google.ar.core.CameraConfig cc = s.getCameraConfig();
            log("camera config: cpu image " + cc.getImageSize() + ", texture " + cc.getTextureSize()
                    + ", fps " + cc.getFpsRange() + ", depth sensor " + cc.getDepthSensorUsage()
                    + ", facing " + cc.getFacingDirection());
        } catch (Exception e) { logError("cameraConfig", e); }
        modeIndex = 0;
        applyMode();
    }

    private void applyMode() {
        Config.DepthMode m = modes.get(modeIndex);
        Config config = new Config(session);
        config.setDepthMode(m);
        config.setFocusMode(Config.FocusMode.AUTO);
        config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
        config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL);
        session.configure(config);
        depthSupported = m != Config.DepthMode.DISABLED;
        rawDepth = m == Config.DepthMode.RAW_DEPTH_ONLY;
        modeLabel = m.name();
        log("depth mode set to " + m);
        notTrackingSince = 0;
        lastDepthTs = -1;
    }

    boolean isViewing() { return viewing; }

    // ---- called on GL thread via queueEvent ----
    final RoomModel room = new RoomModel();
    private boolean roomReady;
    private int viewMode; // 0 = room + scan, 1 = room only, 2 = scan only
    private FloatBuffer floorTris;
    private int floorTriVerts;

    private java.util.List<RoomModel.PlaneData> capturePlanes() {
        java.util.List<RoomModel.PlaneData> out = new java.util.ArrayList<>();
        if (session == null) return out;
        try {
            for (Plane pl : session.getAllTrackables(Plane.class)) {
                if (pl.getTrackingState() != TrackingState.TRACKING || pl.getSubsumedBy() != null) continue;
                FloatBuffer poly = pl.getPolygon();
                poly.rewind();
                int n = poly.remaining() / 2;
                if (n < 3) continue;
                Pose c = pl.getCenterPose();
                RoomModel.PlaneData pd = new RoomModel.PlaneData();
                pd.poly = new float[n * 3];
                float[] in = new float[3], o = new float[3];
                for (int i = 0; i < n; i++) {
                    in[0] = poly.get(i * 2); in[1] = 0; in[2] = poly.get(i * 2 + 1);
                    c.transformPoint(in, 0, o, 0);
                    pd.poly[i * 3] = o[0]; pd.poly[i * 3 + 1] = o[1]; pd.poly[i * 3 + 2] = o[2];
                }
                pd.normal = c.getYAxis();
                pd.center = new float[]{c.tx(), c.ty(), c.tz()};
                Plane.Type t = pl.getType();
                pd.type = t == Plane.Type.HORIZONTAL_UPWARD_FACING ? 0 : t == Plane.Type.HORIZONTAL_DOWNWARD_FACING ? 1 : 2;
                out.add(pd);
            }
        } catch (Exception e) { logError("planes", e); }
        return out;
    }

    String cycleViewMode() {
        viewMode = (viewMode + 1) % 3;
        return viewMode == 0 ? "View: both" : viewMode == 1 ? "View: room" : "View: scan";
    }

    void finishScan() {
        java.util.List<RoomModel.PlaneData> planes = capturePlanes();
        viewing = true;
        log("finish: " + count + " points, " + diagnostics());
        dropNoise();
        log("refined " + refinedTotal + " re-observations, dropped " + droppedNoise + " one-off specks, kept " + count);
        planner.analyse(pos, count);
        subjectPick = planner.isReady() ? planner.defaultSubject() : new float[]{target[0], target[1], target[2]};
        sceneIdx = 0; plan = null;
        log(planner.summary());
        log("ARCore planes: " + planes.size());
        roomReady = false;
        if (planner.isReady()) {
            try {
                room.build(planes, planner, pos, col, count);
                roomReady = true;
                buildFloorMesh();
                log(room.logLine());
            } catch (Exception e) { logError("room", e); }
        }
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
            boxHalfX = (maxX - minX) / 2; boxHalfZ = (maxZ - minZ) / 2; boxMinY = minY; boxMaxY = maxY;
            float ext = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
            dist = Math.max(1f, ext * 1.2f);
            roomCenter[0] = target[0]; roomCenter[1] = target[1]; roomCenter[2] = target[2];
        }
        yaw = 0.6f; pitch = 0.6f;
        activity.updateStatus((roomReady ? room.summary + "\n" : "") + count + " points captured. Drag to rotate, pinch to zoom, two fingers to move. Yellow line = your walking path.");
    }

    void reset() {
        count = 0; uploaded = 0; pathCount = 0; lastPx = 1e9f;
        depthFrames = depthPixels = depthRejectedRange = depthRejectedConf = depthRejectedEdge = depthNotYet = 0; recoveries = 0; lastError = "";
        voxels.clear();
        refined = false; refineCursor = 0; refinedTotal = 0; droppedNoise = 0;
        viewing = false;
        plan = null; sceneIdx = 0;
    }

    // ---- shot planning ----
    private final ShotPlanner planner = new ShotPlanner();
    private ShotPlanner.Plan plan;
    private int sceneIdx; // 0 = off
    private float[] subjectPick;
    private final float[] viewMvp = new float[16];
    private int planProg;

    /** Cycles Off -> Interview -> Vlog -> Product -> Off. Returns the button label. */
    String cyclePlan() {
        if (!planner.isReady()) {
            activity.updateStatus("Not enough of the scan to plan a shot. Rescan and capture more of the floor.");
            return "Plan shot";
        }
        sceneIdx = (sceneIdx + 1) % (ShotPlanner.Scene.values().length + 1);
        replan();
        return sceneIdx == 0 ? "Plan shot" : "Plan: " + ShotPlanner.Scene.values()[sceneIdx - 1].label;
    }

    private void replan() {
        if (sceneIdx == 0) {
            plan = null;
            activity.updateStatus(count + " points captured. Tap Plan shot to place a camera and lights.");
            return;
        }
        plan = planner.plan(ShotPlanner.Scene.values()[sceneIdx - 1], subjectPick);
        log("plan " + plan.scene + " subject (" + fmt(plan.sx) + ", " + fmt(plan.sz) + ") cramped=" + plan.cramped
                + " | " + plan.explanation.replace("\n", " / "));
        activity.updateStatus(plan.explanation);
    }

    private static String fmt(float f) { return String.format(java.util.Locale.US, "%.2f", f); }

    /** Tap on the scan: move the subject to the nearest scanned point under the finger. */
    void pickSubject(float tx, float ty) {
        if (sceneIdx == 0 || count == 0) return;
        float best = (40f * height / 1000f) * (40f * height / 1000f) + 400f, bestW = Float.MAX_VALUE;
        int bestI = -1;
        float[] m = viewMvp;
        for (int i = 0; i < count; i++) {
            float x = pos.get(i * 3), y = pos.get(i * 3 + 1), z = pos.get(i * 3 + 2);
            float cw = m[3] * x + m[7] * y + m[11] * z + m[15];
            if (cw <= 0.05f) continue;
            float sx = ((m[0] * x + m[4] * y + m[8] * z + m[12]) / cw + 1f) * 0.5f * width;
            float sy = (1f - (m[1] * x + m[5] * y + m[9] * z + m[13]) / cw) * 0.5f * height;
            float d = (sx - tx) * (sx - tx) + (sy - ty) * (sy - ty);
            // prefer the closest point to the finger, then the one nearest the viewer
            if (d < best - 25f || (d < best + 25f && cw < bestW)) { best = Math.min(best, d); bestW = cw; bestI = i; }
        }
        if (bestI < 0) return;
        subjectPick = new float[]{pos.get(bestI * 3), pos.get(bestI * 3 + 1), pos.get(bestI * 3 + 2)};
        replan();
    }

    private float boxHalfX, boxHalfZ, boxMinY, boxMaxY;
    private final float[] roomCenter = new float[3];
    private volatile boolean cutaway = true;
    private boolean cutActive;
    private float cutDirX, cutDirZ, cutD, cutFloor, cutCeil;

    boolean toggleCutaway() { cutaway = !cutaway; return cutaway; }

    /** Work out which wall faces the viewer (eye position) and what to hide. */
    private void updateCutaway(float ex, float ez, boolean top) {
        cutActive = viewing && cutaway;
        if (!cutActive) return;
        cutFloor = roomReady ? room.floorY : planner.isReady() ? planner.floorY : boxMinY;
        float roomH = boxMaxY - cutFloor;
        cutCeil = roomH > 1.9f ? boxMaxY - 0.3f : 1e9f;
        if (top) { cutDirX = 0; cutDirZ = 0; cutD = 1e9f; return; }
        float dx = ex - roomCenter[0], dz = ez - roomCenter[2];
        float l = (float) Math.sqrt(dx * dx + dz * dz);
        if (l < 1e-3f) { cutDirX = 0; cutDirZ = 0; cutD = 1e9f; return; }
        cutDirX = dx / l; cutDirZ = dz / l;
        float halfExt = Math.abs(cutDirX) * boxHalfX + Math.abs(cutDirZ) * boxHalfZ;
        cutD = Math.max(0.3f, halfExt - 0.6f); // drop the outer 60 cm band on the viewer's side
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

        planProg = program(
                "uniform mat4 uMvp; uniform float uPx; attribute vec4 aPos;" +
                "void main(){ gl_Position = uMvp * aPos; gl_PointSize = uPx; }",
                "precision mediump float; uniform vec4 uColor; uniform float uRound;" +
                "void main(){ if (uRound > 0.5) { vec2 d = gl_PointCoord * 2.0 - 1.0; float r = dot(d, d);" +
                "  if (r > 1.0) discard; if (r > 0.6) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; } }" +
                "  gl_FragColor = uColor; }");
        bgProg = program(
                "attribute vec4 aPos; attribute vec2 aUv; varying vec2 vUv;" +
                "void main(){ gl_Position = aPos; vUv = aUv; }",
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float; varying vec2 vUv; uniform samplerExternalOES uTex;" +
                "void main(){ gl_FragColor = texture2D(uTex, vUv); }");
        ptProg = program(
                "uniform mat4 uMvp; uniform float uSize; attribute vec4 aPos; attribute vec4 aColor; varying vec4 vColor;" +
                // Dollhouse cutaway: hide the wall band facing the viewer and the ceiling.
                "uniform float uCut; uniform vec3 uCenter; uniform vec2 uDir; uniform float uCutD; uniform float uFloor; uniform float uCeil;" +
                "void main(){" +
                "  if (uCut > 0.5) { vec2 r = aPos.xz - uCenter.xz;" +
                "    if ((dot(r, uDir) > uCutD && aPos.y > uFloor + 0.15) || aPos.y > uCeil) {" +
                "      gl_Position = vec4(2.0, 2.0, 2.0, 1.0); gl_PointSize = 1.0; vColor = aColor; return; } }" +
                "  gl_Position = uMvp * aPos; gl_PointSize = clamp(uSize / max(gl_Position.w, 0.05), 2.0, 96.0); vColor = aColor; }",
                // uPass: 0 = splat core (opaque, writes depth), 1 = splat halo (soft gaussian, blended), 2 = plain
                "precision mediump float; varying vec4 vColor; uniform float uPass;" +
                "void main(){" +
                "  if (uPass > 2.5) { vec2 q = gl_PointCoord * 2.0 - 1.0; if (dot(q, q) > 1.0) discard;" +
                "    gl_FragColor = vec4(mix(vColor.rgb, vec3(0.1, 0.95, 0.8), 0.6) * 0.5, 0.5); return; }" +
                "  if (uPass > 1.5) { gl_FragColor = vColor; return; }" +
                "  vec2 d = gl_PointCoord * 2.0 - 1.0; float r2 = dot(d, d);" +
                "  if (r2 > 1.0) discard;" +
                "  float a = exp(-r2 * 3.5);" +
                "  if (uPass < 0.5) { if (a < 0.6) discard; gl_FragColor = vec4(vColor.rgb, 1.0); }" +
                "  else { gl_FragColor = vec4(vColor.rgb * a, a); }" +
                "}");

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
            updateErrors++;
            logError("session.update", e);
            if (updateErrors % 30 == 1) activity.updateStatus("Camera/AR error (" + updateErrors + "): " + lastError);
            return;
        }
        Camera camera = frame.getCamera();
        long nowMs = System.currentTimeMillis();
        if (frame.getTimestamp() != lastCamTs) {
            if (stallLogged) log("camera frames resumed after " + (nowMs - camTsChangedAt) + " ms");
            lastCamTs = frame.getTimestamp(); camTsChangedAt = nowMs; stallLogged = false;
        } else if (!stallLogged && nowMs - camTsChangedAt > 1000) {
            log("camera frames STALLED (same image for 1s)");
            stallLogged = true;
        }
        if (frame.hasDisplayGeometryChanged() || frameNo == 0) {
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad,
                    Coordinates2d.TEXTURE_NORMALIZED, quadUv);
        }
        frameNo++;
        if (frame.getTimestamp() != 0) drawBackground();

        TrackingState ts = camera.getTrackingState();
        if (ts != lastTs) {
            log("tracking " + lastTs + " -> " + ts + " (reason " + camera.getTrackingFailureReason() + ", frame " + frameNo + ")");
            lastTs = ts;
        }
        if (ts == TrackingState.TRACKING) {
            everTracked = true;
            notTrackingSince = 0;
            lostSince = 0;
        } else if (everTracked) {
            // Lost tracking after it worked: if it doesn't come back by itself, restart the AR session.
            if (lostSince == 0) lostSince = nowMs;
            else if (nowMs - lostSince > 3000 && recoveries < 10) {
                recoveries++;
                lostSince = 0;
                log("tracking lost for 3s, restarting AR session (attempt " + recoveries + ")");
                activity.restartSession();
            }
        } else if (!everTracked) {
            long now = System.currentTimeMillis();
            if (notTrackingSince == 0) notTrackingSince = now;
            else if (now - notTrackingSince > 8000 && modeIndex < modes.size() - 1) {
                modeIndex++;
                log("no tracking after 8s, falling back");
                try { applyMode(); } catch (Exception e) { logError("configure", e); }
            }
        }
        if (ts == TrackingState.TRACKING) {
            try {
                integrate(frame, camera);
            } catch (Exception e) {
                logError("integrate", e);
            }
            recordPath(camera.getPose());
            camera.getProjectionMatrix(proj, 0, 0.05f, 100f);
            camera.getViewMatrix(view, 0);
            Matrix.multiplyMM(mvp, 0, proj, 0, view, 0);
            uploadNew();
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            drawPaint(mvp);
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        }
        if (frameNo % 15 == 0) {
            String s;
            if (ts != TrackingState.TRACKING) {
                s = "Move the phone slowly, pointing at the floor or furniture...\n"
                        + "[tracking " + ts + ", reason " + camera.getTrackingFailureReason() + ", frame " + frameNo + "]\n"
                        + diagnostics();
            } else {
                s = "Scanning: " + count + " points\nWalk slowly, point at walls, floor and furniture. Tap Finish when done.\n"
                        + diagnostics();
            }
            activity.updateStatus(s);
        }
    }

    // ---------------- scanning ----------------

    private Image cpuImage;
    private float colorGain = 1f; // brightens dark frames so the scan looks like the phone's photos

    /** Mean brightness of the camera frame -> a gain (up to 2.6x) plus a gentle shadow lift. */
    private void measureExposure() {
        Image img = cpuImage;
        if (img == null) return;
        Image.Plane yp = img.getPlanes()[0];
        java.nio.ByteBuffer yb = yp.getBuffer();
        int w = img.getWidth(), h = img.getHeight(), rs = yp.getRowStride(), ps = yp.getPixelStride();
        long sum = 0; int n = 0;
        for (int v = 8; v < h; v += 16) for (int u = 8; u < w; u += 16) { sum += yb.get(v * rs + u * ps) & 0xFF; n++; }
        float mean = n > 0 ? (float) sum / n : 128f;
        float target = clamp(118f / Math.max(mean, 8f), 1f, 2.6f);
        colorGain += (target - colorGain) * 0.3f; // smooth so colours don't flicker between frames
    }

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
            measureExposure();

            int dw = depth.getWidth(), dh = depth.getHeight();
            depthFrames++;
            if (depthFrames == 1) log("first depth image " + dw + "x" + dh + " (raw=" + rawDepth + ")");
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
                    if (mm < 200 || mm > 4000) { depthRejectedRange++; continue; }
                    depthPixels++;
                    if (cbuf != null && (cbuf.get(v * cRow + u * cPix) & 0xFF) < 55) { depthRejectedConf++; continue; }
                    // Edge filter: depth jumps between neighbours create "flying pixel" streaks, so skip them.
                    if (u == 0 || v == 0 || u == dw - 1 || v == dh - 1) { depthRejectedEdge++; continue; }
                    int tol = mm / 25 + 20; // ~4% of distance + 2 cm
                    if (Math.abs((dbuf.get(v * dRow + (u - 1) * dPix) & 0xFFFF) - mm) > tol
                            || Math.abs((dbuf.get(v * dRow + (u + 1) * dPix) & 0xFFFF) - mm) > tol
                            || Math.abs((dbuf.get((v - 1) * dRow + u * dPix) & 0xFFFF) - mm) > tol
                            || Math.abs((dbuf.get((v + 1) * dRow + u * dPix) & 0xFFFF) - mm) > tol) {
                        depthRejectedEdge++; continue;
                    }
                    float d = mm * 0.001f;
                    float xc = (u + 0.5f - cx) * d / fx;
                    float yc = (cy - (v + 0.5f)) * d / fy;
                    float zc = -d;
                    float wx = m[0] * xc + m[4] * yc + m[8] * zc + m[12];
                    float wy = m[1] * xc + m[5] * yc + m[9] * zc + m[13];
                    float wz = m[2] * xc + m[6] * yc + m[10] * zc + m[14];
                    long vk = key(wx, wy, wz);
                    int hit = voxels.get(vk);
                    sampleColor(xc / d * ifx + icx, icy - yc / d * ify, rgb);
                    if (hit >= 0) { refine(hit, wx, wy, wz, rgb); continue; }
                    voxels.put(vk, count);
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
            measureExposure();
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
                long fk = key(w[0], w[1], w[2]);
                if (voxels.get(fk) >= 0) continue;
                voxels.put(fk, count);
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
        return "[mode " + modeLabel + " " + depthSize + " | frames " + depthFrames
                + " | valid px " + depthPixels + " | out of range " + depthRejectedRange + " | low conf " + depthRejectedConf + " | edges " + depthRejectedEdge
                + " | not ready " + depthNotYet + " | refined " + refinedTotal + String.format(java.util.Locale.US, " | gain %.2f", colorGain) + (lastError.isEmpty() ? "" : " | err " + lastError) + "]";
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
        float r = y + 1.402f * cr, g = y - 0.344f * cb - 0.714f * cr, b = y + 1.772f * cb;
        if (colorGain > 1.02f) {
            // gain, then a soft shoulder so bright spots (lamps, windows) don't clip to flat white
            r = lift(r); g = lift(g); b = lift(b);
        }
        out[0] = clampI((int) r);
        out[1] = clampI((int) g);
        out[2] = clampI((int) b);
    }

    private void addPoint(float x, float y, float z, int[] rgb) {
        int i = count * 3;
        pos.put(i, x); pos.put(i + 1, y); pos.put(i + 2, z);
        int j = count * 4;
        col.put(j, (byte) rgb[0]); col.put(j + 1, (byte) rgb[1]); col.put(j + 2, (byte) rgb[2]); col.put(j + 3, (byte) 255);
        obs[count] = 1;
        count++;
    }

    /** A spot seen again: blend the new reading in, so repeated looks clean up a bad first one. */
    private void refine(int idx, float x, float y, float z, int[] rgb) {
        int n = obs[idx] & 0xFF;
        if (n < 255) obs[idx] = (byte) (n + 1);
        float w = 1f / (Math.min(n, 20) + 1);
        int i = idx * 3;
        pos.put(i, pos.get(i) + (x - pos.get(i)) * w);
        pos.put(i + 1, pos.get(i + 1) + (y - pos.get(i + 1)) * w);
        pos.put(i + 2, pos.get(i + 2) + (z - pos.get(i + 2)) * w);
        int j = idx * 4;
        for (int c = 0; c < 3; c++) {
            int o = col.get(j + c) & 0xFF;
            col.put(j + c, (byte) Math.round(o + (rgb[c] - o) * w));
        }
        refined = true; refinedTotal++;
    }

    /** Drop specks seen only once (noise, flying pixels), unless that would throw away most of a sparse scan. */
    private void dropNoise() {
        int keep = 0;
        for (int i = 0; i < count; i++) if ((obs[i] & 0xFF) >= 2) keep++;
        droppedNoise = 0;
        if (count < 2000 || keep < count * 0.3f) return;
        int k = 0;
        for (int i = 0; i < count; i++) {
            if ((obs[i] & 0xFF) < 2) continue;
            if (k != i) {
                pos.put(k * 3, pos.get(i * 3)); pos.put(k * 3 + 1, pos.get(i * 3 + 1)); pos.put(k * 3 + 2, pos.get(i * 3 + 2));
                for (int c = 0; c < 4; c++) col.put(k * 4 + c, col.get(i * 4 + c));
                obs[k] = obs[i];
            }
            k++;
        }
        droppedNoise = count - k;
        count = k; uploaded = 0;
        voxels.clear();
        for (int i = 0; i < count; i++) voxels.put(key(pos.get(i * 3), pos.get(i * 3 + 1), pos.get(i * 3 + 2)), i);
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
        // Re-send a slice of already-uploaded points each frame so refined positions/colours show up.
        if (refined && uploaded > 0) {
            int slice = Math.min(uploaded, 40000);
            if (refineCursor >= uploaded) refineCursor = 0;
            int end = Math.min(uploaded, refineCursor + slice);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, posVbo);
            pos.position(refineCursor * 3);
            GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, refineCursor * 12, (end - refineCursor) * 12, pos);
            pos.position(0);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, colVbo);
            col.position(refineCursor * 4);
            GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, refineCursor * 4, (end - refineCursor) * 4, col);
            col.position(0);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
            refineCursor = end;
            if (refineCursor >= uploaded) { refineCursor = 0; refined = false; }
        }
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

    private volatile boolean splats = true;

    boolean toggleSplats() { splats = !splats; return splats; }

    private void drawPoints(float[] m, float sizeScale) {
        if (count == 0) return;
        GLES20.glUseProgram(ptProg);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(ptProg, "uMvp"), 1, false, m, 0);
        float pxPerMeter = height / (2f * (float) Math.tan(Math.toRadians(30)));
        int uSize = GLES20.glGetUniformLocation(ptProg, "uSize");
        int uPass = GLES20.glGetUniformLocation(ptProg, "uPass");
        GLES20.glUniform1f(GLES20.glGetUniformLocation(ptProg, "uCut"), cutActive ? 1f : 0f);
        GLES20.glUniform3f(GLES20.glGetUniformLocation(ptProg, "uCenter"), roomCenter[0], roomCenter[1], roomCenter[2]);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(ptProg, "uDir"), cutDirX, cutDirZ);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(ptProg, "uCutD"), cutD);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(ptProg, "uFloor"), cutFloor);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(ptProg, "uCeil"), cutCeil);
        if (!splats) {
            GLES20.glUniform1f(uSize, VOXEL * pxPerMeter * 1.4f * sizeScale);
            GLES20.glUniform1f(uPass, 2f);
            drawPointArrays();
            return;
        }
        // Splat look: an opaque core pass fills the surface and the depth buffer,
        // then a larger soft gaussian halo is blended on top without writing depth.
        GLES20.glUniform1f(uSize, VOXEL * pxPerMeter * 2.0f * sizeScale);
        GLES20.glUniform1f(uPass, 0f);
        drawPointArrays();
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);
        GLES20.glUniform1f(uSize, VOXEL * pxPerMeter * 3.2f * sizeScale);
        GLES20.glUniform1f(uPass, 1f);
        drawPointArrays();
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    /** Live scan: tint everything captured so far, so the user sees which areas are done. */
    private void drawPaint(float[] m) {
        if (count == 0) return;
        GLES20.glUseProgram(ptProg);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(ptProg, "uMvp"), 1, false, m, 0);
        float pxPerMeter = height / (2f * (float) Math.tan(Math.toRadians(30)));
        GLES20.glUniform1f(GLES20.glGetUniformLocation(ptProg, "uSize"), VOXEL * pxPerMeter * 2.2f);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(ptProg, "uPass"), 3f);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(ptProg, "uCut"), 0f);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);
        drawPointArrays();
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void drawPointArrays() {
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
        GLES20.glUniform1f(GLES20.glGetUniformLocation(ptProg, "uPass"), 2f);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(ptProg, "uCut"), 0f);
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
        updateCutaway(ex, ez, topView);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        if (viewMode != 1 || !roomReady) drawPoints(mvp, viewMode == 0 && roomReady ? 0.9f : 1f);
        if (viewMode != 2 && roomReady) drawRoom(mvp);
        cutActive = false;
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        drawPath(mvp);
        System.arraycopy(mvp, 0, viewMvp, 0, 16);
        if (plan != null) drawPlan(mvp, plan);
    }

    private FloatBuffer scratch = ByteBuffer.allocateDirect(4096 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    private void planDraw(float[] m, float[] verts, int mode, float[] color, float px) {
        GLES20.glUseProgram(planProg);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(planProg, "uMvp"), 1, false, m, 0);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(planProg, "uPx"), px);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(planProg, "uRound"), mode == GLES20.GL_POINTS ? 1f : 0f);
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(planProg, "uColor"), 1, color, 0);
        int aPos = GLES20.glGetAttribLocation(planProg, "aPos");
        if (verts.length > scratch.capacity())
            scratch = ByteBuffer.allocateDirect(verts.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        scratch.clear();
        scratch.put(verts).position(0);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, scratch);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glDrawArrays(mode, 0, verts.length / 3);
        GLES20.glDisableVertexAttribArray(aPos);
    }

    /** Floor cells as a flat triangle mesh, built once per scan. */
    private void buildFloorMesh() {
        int n = 0;
        for (boolean b : planner.floorKnown) if (b) n++;
        floorTriVerts = n * 6;
        floorTris = ByteBuffer.allocateDirect(Math.max(1, floorTriVerts) * 12).order(ByteOrder.nativeOrder()).asFloatBuffer();
        float c = ShotPlanner.CELL, y = room.floorY + 0.01f;
        for (int i = 0; i < planner.floorKnown.length; i++) {
            if (!planner.floorKnown[i]) continue;
            float x0 = planner.minX + (i % planner.nx) * c, z0 = planner.minZ + (i / planner.nx) * c;
            float x1 = x0 + c, z1 = z0 + c;
            floorTris.put(new float[]{x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z0, x1, y, z1, x0, y, z1});
        }
        floorTris.position(0);
    }

    private static final float[] FLOOR_COL = {0.55f, 0.6f, 0.68f, 0.55f};
    private static final float[] WALL_COL = {0.85f, 0.88f, 0.95f, 0.35f};
    private static final float[] WALL_EDGE = {0.95f, 0.97f, 1f, 0.9f};
    private static final float[] WALL_GUESS = {0.85f, 0.88f, 0.95f, 0.12f};
    private static final float[] WALL_GUESS_EDGE = {0.95f, 0.97f, 1f, 0.35f};
    private static final float[] TABLE_COL = {0.75f, 0.5f, 0.3f, 0.75f};
    private static final float[] BOX_COL = {1f, 0.6f, 0.2f, 0.28f};
    private static final float[] BOX_EDGE = {1f, 0.65f, 0.25f, 0.95f};
    private static final float[] LIGHT_COL = {1f, 0.95f, 0.4f, 1f};

    /** The "understood" room: floor, walls, table tops, furniture blocks and light sources. */
    private void drawRoom(float[] m) {
        float dens = height / 1000f;
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);
        // floor
        if (floorTris != null && floorTriVerts > 0) {
            GLES20.glUseProgram(planProg);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(planProg, "uMvp"), 1, false, m, 0);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(planProg, "uRound"), 0f);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(planProg, "uPx"), 1f);
            GLES20.glUniform4fv(GLES20.glGetUniformLocation(planProg, "uColor"), 1, FLOOR_COL, 0);
            int aPos = GLES20.glGetAttribLocation(planProg, "aPos");
            floorTris.position(0);
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, floorTris);
            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, floorTriVerts);
            GLES20.glDisableVertexAttribArray(aPos);
        }
        // planes: walls and table tops (walls on the viewer's side are hidden in dollhouse mode)
        GLES20.glLineWidth(3f * dens);
        for (RoomModel.Surface sf : room.surfaces) {
            float[] fill;
            if (sf.kind == RoomModel.Surface.Kind.WALL) {
                if (room.hasLayout) continue; // the fitted layout replaces ARCore's wall patches
                if (cutActive && ((sf.cx - roomCenter[0]) * cutDirX + (sf.cz - roomCenter[2]) * cutDirZ) > cutD * 0.5f) continue;
                fill = WALL_COL;
            } else if (sf.kind == RoomModel.Surface.Kind.TABLE) fill = TABLE_COL;
            else continue;
            planDraw(m, sf.poly, GLES20.GL_TRIANGLE_FAN, fill, 1);
            planDraw(m, sf.poly, GLES20.GL_LINE_LOOP, sf.kind == RoomModel.Surface.Kind.WALL ? WALL_EDGE : TABLE_COL, 1);
        }
        // fitted room layout: 4 straight walls; guessed ones are fainter. The wall facing the viewer is cut away.
        if (room.hasLayout) {
            float y0 = room.floorY, y1 = room.topY;
            float[] outline = new float[room.layout.size() * 3];
            int k = 0;
            for (RoomModel.Wall w : room.layout) {
                outline[k++] = w.x0; outline[k++] = y0 + 0.012f; outline[k++] = w.z0;
                if (cutActive && w.nx * cutDirX + w.nz * cutDirZ > 0.45f) continue;
                float[] quad = {w.x0, y0, w.z0, w.x1, y0, w.z1, w.x1, y1, w.z1, w.x0, y0, w.z0, w.x1, y1, w.z1, w.x0, y1, w.z0};
                planDraw(m, quad, GLES20.GL_TRIANGLES, w.seen ? WALL_COL : WALL_GUESS, 1);
                planDraw(m, new float[]{w.x0, y0, w.z0, w.x0, y1, w.z0, w.x1, y0, w.z1, w.x1, y1, w.z1,
                        w.x0, y1, w.z0, w.x1, y1, w.z1}, GLES20.GL_LINES, w.seen ? WALL_EDGE : WALL_GUESS_EDGE, 1);
            }
            planDraw(m, outline, GLES20.GL_LINE_LOOP, WALL_EDGE, 1);
        }
        // furniture blocks
        for (RoomModel.Box b : room.boxes) {
            float[] tri = boxTriangles(b);
            planDraw(m, tri, GLES20.GL_TRIANGLES, BOX_COL, 1);
            planDraw(m, boxEdges(b), GLES20.GL_LINES, BOX_EDGE, 1);
        }
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
        // light sources: glowing markers with a drop line to the floor
        for (RoomModel.Light l : room.lights) {
            planDraw(m, new float[]{l.x, room.floorY, l.z, l.x, l.y, l.z}, GLES20.GL_LINES, LIGHT_COL, 1);
            planDraw(m, new float[]{l.x, l.y, l.z}, GLES20.GL_POINTS, LIGHT_COL, 30 * dens);
        }
    }

    private static float[] boxTriangles(RoomModel.Box b) {
        float x0 = b.x0, x1 = b.x1, y0 = b.y0, y1 = b.y1, z0 = b.z0, z1 = b.z1;
        return new float[]{
                // top
                x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z0, x1, y1, z1, x0, y1, z1,
                // sides
                x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y0, z0, x1, y1, z0, x0, y1, z0,
                x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y0, z1, x1, y1, z1, x0, y1, z1,
                x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y0, z0, x0, y1, z1, x0, y1, z0,
                x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y0, z0, x1, y1, z1, x1, y1, z0};
    }

    private static float[] boxEdges(RoomModel.Box b) {
        float x0 = b.x0, x1 = b.x1, y0 = b.y0, y1 = b.y1, z0 = b.z0, z1 = b.z1;
        return new float[]{
                x0, y1, z0, x1, y1, z0, x1, y1, z0, x1, y1, z1, x1, y1, z1, x0, y1, z1, x0, y1, z1, x0, y1, z0,
                x0, y0, z0, x0, y1, z0, x1, y0, z0, x1, y1, z0, x1, y0, z1, x1, y1, z1, x0, y0, z1, x0, y1, z1,
                x0, y0, z0, x1, y0, z0, x1, y0, z0, x1, y0, z1, x1, y0, z1, x0, y0, z1, x0, y0, z1, x0, y0, z0};
    }

    /** Draws subject, camera (with its field of view) and lights on top of the scan. */
    private void drawPlan(float[] m, ShotPlanner.Plan p) {
        float dens = height / 1000f;
        float[] white = {1f, 1f, 1f, 1f};
        GLES20.glLineWidth(5f * dens);
        // subject: a standing "pole" from the floor to eye height
        planDraw(m, new float[]{p.sx, p.floorY, p.sz, p.sx, p.sy, p.sz}, GLES20.GL_LINES, white, 1);
        planDraw(m, new float[]{p.sx, p.floorY, p.sz}, GLES20.GL_POINTS, white, 30 * dens);
        for (ShotPlanner.Element e : p.elements) {
            float[] dim = {e.color[0] * 0.6f, e.color[1] * 0.6f, e.color[2] * 0.6f, 1f};
            GLES20.glLineWidth(3f * dens);
            // stand from the floor, and aim line to the subject
            planDraw(m, new float[]{e.x, p.floorY, e.z, e.x, e.y, e.z}, GLES20.GL_LINES, dim, 1);
            if (!e.isCamera) planDraw(m, new float[]{e.x, e.y, e.z, p.sx, p.sy, p.sz}, GLES20.GL_LINES, e.color, 1);
            if (e.isCamera) drawFrustum(m, e, p);
            planDraw(m, new float[]{e.x, e.y, e.z}, GLES20.GL_POINTS, e.color, 34 * dens);
        }
        planDraw(m, new float[]{p.sx, p.sy, p.sz}, GLES20.GL_POINTS, white, 28 * dens);
    }

    private void drawFrustum(float[] m, ShotPlanner.Element c, ShotPlanner.Plan p) {
        float fx = p.sx - c.x, fy = p.sy - c.y, fz = p.sz - c.z;
        float d = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (d < 1e-3f) return;
        fx /= d; fy /= d; fz /= d;
        // right = f x up, up' = right x f
        float rx = -fz, ry = 0, rz = fx;
        float rl = (float) Math.sqrt(rx * rx + rz * rz);
        if (rl < 1e-3f) return;
        rx /= rl; rz /= rl;
        float ux = ry * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - ry * fx;
        float hw = d * (float) Math.tan(Math.toRadians(30)), hh = hw * 9f / 16f;
        float[][] k = new float[4][];
        int[][] sg = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (int i = 0; i < 4; i++)
            k[i] = new float[]{p.sx + rx * hw * sg[i][0] + ux * hh * sg[i][1],
                    p.sy + ry * hw * sg[i][0] + uy * hh * sg[i][1],
                    p.sz + rz * hw * sg[i][0] + uz * hh * sg[i][1]};
        float[] v = new float[16 * 3];
        int n = 0;
        for (int i = 0; i < 4; i++) {
            v[n++] = c.x; v[n++] = c.y; v[n++] = c.z;
            v[n++] = k[i][0]; v[n++] = k[i][1]; v[n++] = k[i][2];
            v[n++] = k[i][0]; v[n++] = k[i][1]; v[n++] = k[i][2];
            float[] q = k[(i + 1) % 4];
            v[n++] = q[0]; v[n++] = q[1]; v[n++] = q[2];
        }
        planDraw(m, v, GLES20.GL_LINES, c.color, 1);
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

    private float lift(float c) {
        float x = Math.min(1f, Math.max(0f, c) / 255f);
        x = x * colorGain / (1f + (colorGain - 1f) * x); // shadows boosted by colorGain, white stays white (light detection still works)
        return x * 255f;
    }

    private static int clampI(int v) { return Math.max(0, Math.min(255, v)); }

    /** Open-addressing hash map long -> int (voxel key -> point index). */
    static final class LongIntMap {
        private static final long EMPTY = Long.MIN_VALUE;
        private final long[] keys;
        private final int[] vals;
        private final int mask;

        LongIntMap(int capacityPow2) {
            keys = new long[capacityPow2];
            vals = new int[capacityPow2];
            mask = capacityPow2 - 1;
            clear();
        }

        void clear() { Arrays.fill(keys, EMPTY); }

        private int slot(long k) {
            long h = k * 0x9E3779B97F4A7C15L;
            int i = (int) (h ^ (h >>> 32)) & mask;
            while (keys[i] != EMPTY && keys[i] != k) i = (i + 1) & mask;
            return i;
        }

        int get(long k) { int i = slot(k); return keys[i] == k ? vals[i] : -1; }

        void put(long k, int v) { int i = slot(k); keys[i] = k; vals[i] = v; }
    }
}
