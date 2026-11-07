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

import java.io.File;
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
    private final Tsdf tsdf = new Tsdf(1_500_000, 0.04f, 0.12f, 99f); // carve along the whole ray
    /** Fine surface for the detail zones the user tapped (shoes, the table top...). */
    private final Tsdf detail = new Tsdf(1_000_000, 0.012f, 0.04f, 0.2f);
    { tsdf.minW = 3; tsdf.minComponent = 200; tsdf.carveWeight = 2f; detail.minComponent = 60; } // fewer one-off and floating flakes
    private final java.util.ArrayList<float[]> zones = new java.util.ArrayList<>(); // x, y, z, radius
    private static final float VOXEL_FINE = 0.008f;
    private int depthPixelsFrame, nearPixelsFrame, closeFrames;
    private final float[] nearSum = new float[3];

    private boolean inZone(float x, float y, float z) {
        for (int i = 0; i < zones.size(); i++) {
            float[] q = zones.get(i);
            float dx = x - q[0], dy = y - q[1], dz = z - q[2];
            if (dx * dx + dy * dy + dz * dz < q[3] * q[3]) return true;
        }
        return false;
    }
    private final PhotoRoom photos = new PhotoRoom();
    private final float[] prevPose = new float[16], curPose = new float[16];
    private boolean havePrevPose;
    private float frameMove = 1f, frameTurn = 90f;
    private int meshTris;
    private final java.util.ArrayList<ScanExport.Rec> recs = new java.util.ArrayList<>();
    private final ScanExport.PoseLog poseLog = new ScanExport.PoseLog();
    final SensorLog sensors = new SensorLog();
    private final float[] poseTq = new float[7];
    private final byte[] obs = new byte[MAX_POINTS]; // how many times each point was seen (capped)
    private int refineCursor; private boolean refined; private int refinedTotal, droppedNoise;
    private final FloatBuffer path = ByteBuffer.allocateDirect(MAX_PATH * 12).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private int pathCount;
    private float lastPx = 1e9f, lastPy, lastPz;

    // GL objects
    private int bgTex, bgProg, ptProg, posVbo, colVbo;
    // surfel splats (see Surfels): built in the background after a scan, uploaded on the GL thread
    private int propProg;
    private int surfProg, surfPosVbo, surfNrmVbo, surfColVbo, surfRawVbo, surfCount;
    private boolean surfTrained;
    private volatile Surfels surfPending, surfDone;
    private volatile int surfGen;
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
        try { // which lenses ARCore offers on this phone (it decides; apps can only pick from this list)
            com.google.ar.core.CameraConfigFilter f = new com.google.ar.core.CameraConfigFilter(s);
            StringBuilder b = new StringBuilder();
            for (com.google.ar.core.CameraConfig c : s.getSupportedCameraConfigs(f))
                b.append(" [id ").append(c.getCameraId()).append(' ').append(c.getImageSize()).append(' ').append(c.getFpsRange()).append(']');
            log("supported camera configs:" + b);
        } catch (Throwable e) { logError("cameraConfigs", new RuntimeException(e)); }
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
        config.setLightEstimationMode(Config.LightEstimationMode.AMBIENT_INTENSITY); // scene brightness + colour cast per frame
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
    private int viewMode = 1; // 0 = photo room, 1 = raw scan (surfel splats, the default again since v0.1.51)
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

    /** 0 = photo room (walls and floor with the photos on them, plus cut-outs), 1 = the raw scan. Rebuilds the photos. */
    String cycleViewMode() {
        viewMode = (viewMode + 1) % 2;
        if (viewing && count > 0) buildPhotos();
        return viewModeLabel();
    }

    String viewModeLabel() { return viewMode == 0 ? "View: photo room" : "View: scan"; }
    /** Photo texturing (background): the simple photo room (walls + floor) or the full scan surface. */
    private void buildPhotos() {
        final RoomModel roomNow = room; final boolean ready = roomReady;
        if (meshPosAll == null) { meshPosAll = tsdf.meshPos; meshColAll = tsdf.meshCol; meshVerts = tsdf.meshVerts; }
        final FloatBuffer mp = meshPosAll; final ByteBuffer mc = meshColAll; final int mvAll = meshVerts;
        photos.ready = false;
        // Photo texturing takes a few seconds on the phone, so it runs in the background; the view shows points until it's done.
        final boolean simple = viewMode == 0 && ready && roomNow.hasLayout;
        new Thread(() -> { try {
            long t1 = System.currentTimeMillis();
            RoomProxies rp = ready && roomNow.hasLayout ? RoomProxies.build(roomNow, pos, col, count) : new RoomProxies();
            float[] proxy = rp.tris; byte[] proxyCol = rp.cols;
            if (ready && !simple) {
                ScenePriors.Fill f = ScenePriors.apply(mp, mvAll, roomNow, pos, col, count);
                proxy = ScenePriors.concat(proxy, f.triArray()); proxyCol = ScenePriors.concat(proxyCol, f.colArray());
                log(f.toString());
            }
            int mv = simple ? 0 : mvAll, pv = proxy.length / 3; // the photo room is walls and floor only
            FloatBuffer all = ByteBuffer.allocateDirect(Math.max(1, mv + pv) * 12).order(ByteOrder.nativeOrder()).asFloatBuffer();
            mp.position(0); mp.limit(mv * 3);
            all.put(mp).put(proxy).position(0);
            mp.limit(mp.capacity()); mp.position(0);
            ByteBuffer allCol = ByteBuffer.allocateDirect(Math.max(4, (mv + pv) * 4)).order(ByteOrder.nativeOrder());
            mc.position(0); mc.limit(mv * 4);
            allCol.put(mc).put(proxyCol).position(0);
            mc.limit(mc.capacity()); mc.position(0);
            photos.build(all, allCol, mv + pv, mv, simple);
            log((simple ? "photo room: " : "photos: ") + photos.keyframes.size() + " keyframes, " + photos.texturedTris + " textured / " + photos.plainTris
                    + " plain triangles, " + pv / 3 + " wall/floor cells tried (" + (System.currentTimeMillis() - t1) + " ms)");
        } catch (Throwable e) { logError("photos", new RuntimeException(e)); } }).start();
    }


    /** Turn the current points into surfel splats in the background (a few seconds on the phone). */
    private void buildSurfels() {
        final int n = count, gen = ++surfGen;
        if (n == 0) return;
        final java.nio.FloatBuffer snap = java.nio.FloatBuffer.allocate(n * 3);
        for (int i = 0; i < n * 3; i++) snap.put(i, pos.get(i));
        final ByteBuffer colSnap = ByteBuffer.allocate(n * 4);
        for (int i = 0; i < n * 4; i++) colSnap.put(i, col.get(i));
        surfDone = null; surfBase = null; training = false; trainer = null; trainViews = null;
        new Thread(() -> { try {
            long t0 = System.currentTimeMillis();
            Surfels s = Surfels.build(snap, colSnap, n);
            if (gen != surfGen) return;
            surfDone = s; surfBase = s; surfPending = s;
            log("splats: " + n + " surfels (" + s.flat + " on flat surfaces, " + s.curved + " curved/edges, " + s.lonely + " lonely) in "
                    + (System.currentTimeMillis() - t0) + " ms");
        } catch (Throwable e) { logError("splats", new RuntimeException(e)); } }).start();
    }

    // ---- shot lighting preview (see Lighting) ----
    final Lighting lighting = new Lighting();
    private final java.util.List<float[]> fixtures = new java.util.ArrayList<>(); // bright spots of the room's own lights

    /** GL thread: switch the lighting preview on/off. On: a seated person and key + fill (from the shot plan if there is one). */
    String toggleLighting() {
        if (lighting.enabled) { lighting.enabled = false; return "Lighting"; }
        if (surfCount == 0) { activity.updateStatus("The splats are still being built. Try again in a moment."); return "Lighting"; }
        synchronized (lighting) {
            float fy = roomReady ? room.floorY : planner.isReady() ? planner.floorY : boxMinY;
            if (lighting.props.isEmpty()) {
                float sx = roomCenter[0], sz = roomCenter[2];
                lighting.defaultSetup(plan, sx, sz, fy);
            }
            lighting.floorY = fy;
            lighting.selected = 0;
            lighting.enabled = true;
        }
        findFixtures();
        lightingStatus();
        return "Lighting: on";
    }

    void lightingStatus() {
        String fx = fixtures.isEmpty() ? "" : fixtures.size() + " room light" + (fixtures.size() == 1 ? "" : "s") + " found (yellow). ";
        activity.updateStatus(lighting.readout() + "\n" + fx + "Selected: " + lighting.selectedLabel() + ". Tap a light or prop to select it, drag it to move.");
    }

    /** The room's own lamps and windows: clusters of very bright points above 1 m. */
    private void findFixtures() {
        fixtures.clear();
        float fy = lighting.floorY;
        java.util.HashMap<Long, float[]> cells = new java.util.HashMap<>();
        for (int i = 0; i < count; i++) {
            int r = col.get(i * 4) & 255, g = col.get(i * 4 + 1) & 255, b = col.get(i * 4 + 2) & 255;
            if (r + g + b < 3 * 238) continue;
            float x = pos.get(i * 3), y = pos.get(i * 3 + 1), z = pos.get(i * 3 + 2);
            if (y < fy + 1.0f) continue;
            long k = ((long) Math.floor(x / 0.3f) + 10000) * 100000000L + ((long) Math.floor(y / 0.3f) + 10000) * 10000L + ((long) Math.floor(z / 0.3f) + 10000);
            float[] c = cells.get(k);
            if (c == null) cells.put(k, c = new float[4]);
            c[0] += x; c[1] += y; c[2] += z; c[3]++;
        }
        for (float[] c : cells.values()) if (c[3] >= 25) fixtures.add(new float[]{c[0] / c[3], c[1] / c[3], c[2] / c[3], c[3]});
        fixtures.sort((a, b) -> Float.compare(b[3], a[3]));
        while (fixtures.size() > 8) fixtures.remove(fixtures.size() - 1);
        log("lighting: " + fixtures.size() + " bright fixtures/windows found");
    }

    private void setLightUniforms(int prog) {
        Lighting L = lighting;
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uLit"), L.enabled ? 1f : 0f);
        if (!L.enabled) return;
        float[] pos4 = new float[12], aim4 = new float[12], col4 = new float[12], cone4 = new float[12];
        for (int i = 0; i < 4; i++) cone4[i * 3 + 1] = 1f; // unused slots: a valid smoothstep range
        float[] face;
        synchronized (L) {
            face = L.subjectFace();
            int n = Math.min(4, L.lights.size());
            for (int i = 0; i < n; i++) {
                Lighting.Light l = L.lights.get(i);
                float[] t = Lighting.tint(l.kelvin, L.wbKelvin);
                float k = l.on ? l.lux1m() / Lighting.ROOM_LUX : 0;
                pos4[i * 3] = l.x; pos4[i * 3 + 1] = l.y; pos4[i * 3 + 2] = l.z;
                float[] a = face != null ? face : new float[]{l.x, L.floorY + 1f, l.z - 1};
                aim4[i * 3] = a[0]; aim4[i * 3 + 1] = a[1]; aim4[i * 3 + 2] = a[2];
                col4[i * 3] = t[0] * k; col4[i * 3 + 1] = t[1] * k; col4[i * 3 + 2] = t[2] * k;
                cone4[i * 3] = (float) Math.cos(Math.toRadians(l.beamDeg / 2)); cone4[i * 3 + 1] = (float) Math.cos(Math.toRadians(l.beamDeg / 2 * 0.6f)); cone4[i * 3 + 2] = l.type.wrap;
            }
        }
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uRoom"), L.roomDimmer);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uExpo"), L.exposure());
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(prog, "uLPos"), 4, pos4, 0);
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(prog, "uLAim"), 4, aim4, 0);
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(prog, "uLCol"), 4, col4, 0);
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(prog, "uLCone"), 4, cone4, 0);
    }

    // simple low-poly props: each part is a box or a ball, with its own colour
    private static final float[] SKIN = {0.78f, 0.58f, 0.47f}, CLOTH = {0.22f, 0.27f, 0.36f}, TROUSERS = {0.18f, 0.18f, 0.2f},
            WOOD = {0.55f, 0.4f, 0.26f}, CHAIR = {0.32f, 0.32f, 0.34f};

    private void drawProps(float[] m) {
        java.util.List<Lighting.Prop> ps;
        synchronized (lighting) { ps = new java.util.ArrayList<>(lighting.props); }
        GLES20.glUseProgram(propProg);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(propProg, "uMvp"), 1, false, m, 0);
        setLightUniforms(propProg);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        float fy = lighting.floorY;
        for (Lighting.Prop p : ps) {
            // people turn to the camera
            float yaw = p.yawDeg;
            if (p.isPerson() && lighting.haveCam) yaw = (float) Math.toDegrees(Math.atan2(lighting.camX - p.x, lighting.camZ - p.z));
            switch (p.kind) {
                case PERSON_STANDING:
                    propBox(m, p, yaw, 0, 0.45f, 0, 0.32f, 0.9f, 0.2f, TROUSERS, fy);
                    propBox(m, p, yaw, 0, 1.18f, 0, 0.42f, 0.56f, 0.24f, CLOTH, fy);
                    propBox(m, p, yaw, 0.26f, 1.12f, 0, 0.1f, 0.58f, 0.1f, CLOTH, fy);
                    propBox(m, p, yaw, -0.26f, 1.12f, 0, 0.1f, 0.58f, 0.1f, CLOTH, fy);
                    propBall(m, p, 0, 1.6f, 0, 0.11f, SKIN, fy);
                    break;
                case PERSON_SEATED:
                    propBox(m, p, yaw, 0, 0.22f, 0.32f, 0.3f, 0.44f, 0.12f, TROUSERS, fy);   // lower legs
                    propBox(m, p, yaw, 0, 0.5f, 0.15f, 0.32f, 0.14f, 0.42f, TROUSERS, fy);   // thighs
                    propBox(m, p, yaw, 0, 0.84f, -0.05f, 0.42f, 0.56f, 0.24f, CLOTH, fy);    // torso
                    propBox(m, p, yaw, 0.26f, 0.8f, 0.02f, 0.1f, 0.5f, 0.1f, CLOTH, fy);
                    propBox(m, p, yaw, -0.26f, 0.8f, 0.02f, 0.1f, 0.5f, 0.1f, CLOTH, fy);
                    propBall(m, p, 0, 1.24f, -0.03f, 0.11f, SKIN, fy);
                    propBox(m, p, yaw, 0, 0.42f, -0.05f, 0.46f, 0.04f, 0.46f, CHAIR, fy);    // its chair
                    propBox(m, p, yaw, 0, 0.7f, -0.27f, 0.46f, 0.52f, 0.04f, CHAIR, fy);
                    break;
                case TABLE:
                    propBox(m, p, yaw, 0, 0.74f, 0, 1.2f, 0.04f, 0.75f, WOOD, fy);
                    for (int sx = -1; sx <= 1; sx += 2) for (int sz = -1; sz <= 1; sz += 2)
                        propBox(m, p, yaw, sx * 0.55f, 0.36f, sz * 0.32f, 0.05f, 0.72f, 0.05f, WOOD, fy);
                    break;
                case CHAIR:
                    propBox(m, p, yaw, 0, 0.45f, 0, 0.46f, 0.04f, 0.46f, CHAIR, fy);
                    propBox(m, p, yaw, 0, 0.72f, -0.21f, 0.46f, 0.5f, 0.04f, CHAIR, fy);
                    for (int sx = -1; sx <= 1; sx += 2) for (int sz = -1; sz <= 1; sz += 2)
                        propBox(m, p, yaw, sx * 0.2f, 0.22f, sz * 0.2f, 0.04f, 0.44f, 0.04f, CHAIR, fy);
                    break;
            }
        }
    }

    private final float[] propV = new float[36 * 6];
    private FloatBuffer propBuf = ByteBuffer.allocateDirect(2048 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    /** A box centred at local (ox, oy above the floor, oz), size (sx, sy, sz), turned by yaw around the prop's spot. */
    private void propBox(float[] m, Lighting.Prop p, float yawDeg, float ox, float oy, float oz, float sx, float sy, float sz, float[] alb, float fy) {
        float c = (float) Math.cos(Math.toRadians(yawDeg)), s = (float) Math.sin(Math.toRadians(yawDeg));
        int[][] faces = {{0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        int k = 0;
        for (int[] fc : faces) {
            // two in-face axes
            int[] u = fc[0] != 0 ? new int[]{0, 1, 0} : new int[]{1, 0, 0};
            int[] v = {fc[1] * u[2] - fc[2] * u[1], fc[2] * u[0] - fc[0] * u[2], fc[0] * u[1] - fc[1] * u[0]};
            float[][] q = new float[4][];
            int[][] sg = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
            for (int i = 0; i < 4; i++) {
                float lx = (fc[0] + u[0] * sg[i][0] + v[0] * sg[i][1]) * sx / 2 + ox;
                float ly = (fc[1] + u[1] * sg[i][0] + v[1] * sg[i][1]) * sy / 2 + oy;
                float lz = (fc[2] + u[2] * sg[i][0] + v[2] * sg[i][1]) * sz / 2 + oz;
                q[i] = new float[]{p.x + c * lx + s * lz, fy + ly, p.z - s * lx + c * lz};
            }
            float nx = c * fc[0] + s * fc[2], ny = fc[1], nz = -s * fc[0] + c * fc[2];
            int[] order = {0, 1, 2, 0, 2, 3};
            for (int o : order) { propV[k++] = q[o][0]; propV[k++] = q[o][1]; propV[k++] = q[o][2]; propV[k++] = nx; propV[k++] = ny; propV[k++] = nz; }
        }
        propDraw(propV, 36, alb);
    }

    private void propBall(float[] m, Lighting.Prop p, float ox, float oy, float oz, float r, float[] alb, float fy) {
        int NU = 12, NV = 8;
        float[] v = new float[NU * NV * 6 * 6];
        int k = 0;
        for (int i = 0; i < NU; i++) for (int j = 0; j < NV; j++) {
            float[][] q = new float[4][];
            int[][] sg = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
            for (int t = 0; t < 4; t++) {
                double a = (i + sg[t][0]) * 2 * Math.PI / NU, b = (j + sg[t][1]) * Math.PI / NV - Math.PI / 2;
                q[t] = new float[]{(float) (Math.cos(b) * Math.cos(a)), (float) Math.sin(b), (float) (Math.cos(b) * Math.sin(a))};
            }
            int[] order = {0, 1, 2, 0, 2, 3};
            for (int o : order) {
                v[k++] = p.x + ox + q[o][0] * r; v[k++] = fy + oy + q[o][1] * r * 1.15f; v[k++] = p.z + oz + q[o][2] * r;
                v[k++] = q[o][0]; v[k++] = q[o][1]; v[k++] = q[o][2];
            }
        }
        propDraw(v, k / 6, alb);
    }

    private void propDraw(float[] v, int verts, float[] alb) {
        if (propBuf.capacity() < verts * 6) propBuf = ByteBuffer.allocateDirect(verts * 6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        propBuf.clear(); propBuf.put(v, 0, verts * 6); propBuf.position(0);
        GLES20.glUniform3f(GLES20.glGetUniformLocation(propProg, "uAlb"), alb[0], alb[1], alb[2]);
        int aPos = GLES20.glGetAttribLocation(propProg, "aPos"), aN = GLES20.glGetAttribLocation(propProg, "aN");
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 24, propBuf);
        propBuf.position(3);
        GLES20.glVertexAttribPointer(aN, 3, GLES20.GL_FLOAT, false, 24, propBuf);
        GLES20.glEnableVertexAttribArray(aPos); GLES20.glEnableVertexAttribArray(aN);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, verts);
        GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aN);
    }

    private static final float[] FIXTURE_COL = {1f, 0.92f, 0.3f, 1f}, SEL_COL = {1f, 1f, 1f, 1f};

    /** Light stands, aim lines and names; the room's own lights in yellow. */
    private void drawLightGizmos(float[] m) {
        float dens = height / 1000f;
        java.util.List<Lighting.Light> ls; float[] face; int sel;
        synchronized (lighting) { ls = new java.util.ArrayList<>(lighting.lights); face = lighting.subjectFace(); sel = lighting.selected; }
        float fy = lighting.floorY;
        GLES20.glLineWidth(3f * dens);
        for (int i = 0; i < ls.size(); i++) {
            Lighting.Light l = ls.get(i);
            float[] t = Lighting.kelvin(l.kelvin);
            float[] c = l.on ? new float[]{t[0], t[1], t[2], 1f} : new float[]{0.4f, 0.4f, 0.4f, 1f};
            planDraw(m, new float[]{l.x, fy, l.z, l.x, l.y, l.z}, GLES20.GL_LINES, new float[]{0.6f, 0.6f, 0.6f, 1f}, 1);
            if (face != null && l.on) planDraw(m, new float[]{l.x, l.y, l.z, face[0], face[1], face[2]}, GLES20.GL_LINES, c, 1);
            if (i == sel) planDraw(m, new float[]{l.x, l.y, l.z}, GLES20.GL_POINTS, SEL_COL, 52 * dens);
            planDraw(m, new float[]{l.x, l.y, l.z}, GLES20.GL_POINTS, c, 36 * dens);
            drawLabel(m, l.x, l.y + 0.15f, l.z, String.format(java.util.Locale.US, "%s · %d%% · %.0fK", l.name, Math.round(l.dimmer * 100), l.kelvin), false);
        }
        java.util.List<Lighting.Prop> ps;
        synchronized (lighting) { ps = new java.util.ArrayList<>(lighting.props); }
        for (int i = 0; i < ps.size(); i++) {
            Lighting.Prop p = ps.get(i);
            float h = p.faceH() + 0.25f;
            if (ls.size() + i == sel) planDraw(m, new float[]{p.x, fy + 0.02f, p.z}, GLES20.GL_POINTS, SEL_COL, 40 * dens);
            drawLabel(m, p.x, fy + h, p.z, p.kind.label, true);
        }
        for (float[] f : fixtures) {
            planDraw(m, new float[]{f[0], f[1], f[2]}, GLES20.GL_POINTS, FIXTURE_COL, 26 * dens);
            drawLabel(m, f[0], f[1] + 0.12f, f[2], "room light", false);
        }
    }

    /** Screen position of world point, or null if behind the viewer. */
    private float[] toScreen(float x, float y, float z) {
        float[] m = viewMvp;
        float cw = m[3] * x + m[7] * y + m[11] * z + m[15];
        if (cw <= 0.05f) return null;
        return new float[]{((m[0] * x + m[4] * y + m[8] * z + m[12]) / cw + 1f) * 0.5f * width, (1f - (m[1] * x + m[5] * y + m[9] * z + m[13]) / cw) * 0.5f * height};
    }

    /** Tap: select the light or prop nearest the finger (within ~60 px). Returns true if something was picked. */
    boolean lightingPick(float tx, float ty) {
        if (!lighting.enabled) return false;
        int best = -1; float bd = (70f * height / 1000f) * (70f * height / 1000f);
        synchronized (lighting) {
            for (int i = 0; i < lighting.lights.size(); i++) {
                Lighting.Light l = lighting.lights.get(i);
                float[] s = toScreen(l.x, l.y, l.z);
                if (s == null) continue;
                float d = (s[0] - tx) * (s[0] - tx) + (s[1] - ty) * (s[1] - ty);
                if (d < bd) { bd = d; best = i; }
            }
            for (int i = 0; i < lighting.props.size(); i++) {
                Lighting.Prop p = lighting.props.get(i);
                for (float hh : new float[]{0.1f, p.faceH() * 0.5f, p.faceH()}) {
                    float[] s = toScreen(p.x, lighting.floorY + hh, p.z);
                    if (s == null) continue;
                    float d = (s[0] - tx) * (s[0] - tx) + (s[1] - ty) * (s[1] - ty);
                    if (d < bd) { bd = d; best = lighting.lights.size() + i; }
                }
            }
            if (best >= 0) lighting.selected = best;
        }
        if (best >= 0) { activity.lightingSelected(); lightingStatus(); }
        return best >= 0;
    }

    private float dragDX, dragDZ;

    /** Finger down: start dragging if it's on the selected item. */
    boolean lightingDragStart(float tx, float ty) {
        if (!lighting.enabled) return false;
        if (!lightingPick(tx, ty)) return false;
        float[] xz = lighting.selectedXZ(), w = floorAt(tx, ty);
        if (xz == null || w == null) return false;
        dragDX = xz[0] - w[0]; dragDZ = xz[1] - w[1];
        return true;
    }

    void lightingDragTo(float tx, float ty) {
        float[] w = floorAt(tx, ty);
        if (w == null) return;
        synchronized (lighting) { lighting.moveSelected(w[0] + dragDX, w[1] + dragDZ); }
    }

    void lightingDragEnd() { lightingStatus(); }

    /** Where the finger's ray meets the floor (x, z), or null. */
    private float[] floorAt(float tx, float ty) {
        float[] inv = new float[16];
        if (!Matrix.invertM(inv, 0, viewMvp, 0)) return null;
        float nx = tx / width * 2 - 1, ny = 1 - ty / height * 2;
        float[] a = new float[4], b = new float[4];
        Matrix.multiplyMV(a, 0, inv, 0, new float[]{nx, ny, -1, 1}, 0);
        Matrix.multiplyMV(b, 0, inv, 0, new float[]{nx, ny, 1, 1}, 0);
        for (int i = 0; i < 3; i++) { a[i] /= a[3]; b[i] /= b[3]; }
        float dy = b[1] - a[1];
        if (Math.abs(dy) < 1e-6f) return null;
        float t = (lighting.floorY - a[1]) / dy;
        if (t < 0) return null;
        return new float[]{a[0] + (b[0] - a[0]) * t, a[2] + (b[2] - a[2]) * t};
    }

    // ---- saved scans (see ScanStore) ----
    volatile boolean idle = true;            // home screen: draw nothing, no AR
    private volatile Runnable pendingSave;   // runs at the end of the next viewer frame (so the thumbnail is the current view)
    File currentDir;                         // the saved scan being shown (null = not saved yet)
    String currentName;
    private File improveDir;                 // improving this saved scan with a second pass
    private String improveName;
    private java.util.List<float[]> improveCorners = new java.util.ArrayList<>();
    private float[] improveBase;             // its points (sub-sampled), to check which corners match
    private int improveBaseN;
    private volatile float[] improveT;       // new scan -> saved scan: {cos, sin, tx, ty, tz}

    interface SaveDone { void done(File dir, String error); }

    /** GL thread: save the scan under this name (into dir, or a new folder). The thumbnail is the view on screen. */
    void requestSave(String name, File dir, SaveDone done) {
        pendingSave = () -> {
            try {
                ScanStore.Data d = new ScanStore.Data();
                d.name = name;
                // thumbnail: the frame just drawn
                java.nio.ByteBuffer px = java.nio.ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
                GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
                px.position(0);
                android.graphics.Bitmap full = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888);
                full.copyPixelsFromBuffer(px);
                android.graphics.Matrix flip = new android.graphics.Matrix();
                float sc = 360f / width;
                flip.postScale(sc, -sc);
                d.thumb = android.graphics.Bitmap.createBitmap(full, 0, 0, width, height, flip, true);
                full.recycle();
                d.n = count; d.pos = new float[count * 3]; d.col = new byte[count * 3];
                for (int i = 0; i < count; i++) {
                    d.pos[i * 3] = pos.get(i * 3); d.pos[i * 3 + 1] = pos.get(i * 3 + 1); d.pos[i * 3 + 2] = pos.get(i * 3 + 2);
                    d.col[i * 3] = col.get(i * 4); d.col[i * 3 + 1] = col.get(i * 4 + 1); d.col[i * 3 + 2] = col.get(i * 4 + 2);
                }
                java.util.List<byte[]> jpgs = ScanExport.readKeyframes(photos, width, height);
                for (int i = 0; i < photos.keyframes.size() && i < jpgs.size(); i++) {
                    PhotoRoom.Keyframe k = photos.keyframes.get(i);
                    ScanStore.Kf o = new ScanStore.Kf();
                    o.jpg = jpgs.get(i);
                    System.arraycopy(k.view, 0, o.view, 0, 16); System.arraycopy(k.pose, 0, o.pose, 0, 16);
                    o.fx = k.fx; o.fy = k.fy; o.cx = k.cx; o.cy = k.cy; o.w = k.w; o.h = k.h; o.closeUp = k.closeUp;
                    o.depth = k.depth; o.dw = k.dw; o.dh = k.dh; o.dfx = k.dfx; o.dfy = k.dfy; o.dcx = k.dcx; o.dcy = k.dcy;
                    d.kfs.add(o);
                }
                d.path = new float[pathCount * 3];
                for (int i = 0; i < d.path.length; i++) d.path[i] = path.get(i);
                synchronized (marks) {
                    for (float[] c : cornerList) d.corners.add(c.clone());
                    for (float[] w : wallList) d.walls.add(w.clone());
                    d.floor = markFloor == null ? null : markFloor.clone();
                    d.ceiling = markCeiling == null ? null : markCeiling.clone();
                }
                new Thread(() -> {
                    try {
                        File out = ScanStore.save(activity, dir, d);
                        currentDir = out; currentName = name;
                        log("saved '" + name + "': " + d.n + " points, " + d.kfs.size() + " photos -> " + out);
                        done.done(out, null);
                    } catch (Throwable e) { logError("save", new RuntimeException(e)); done.done(null, String.valueOf(e.getMessage())); }
                }).start();
            } catch (Throwable e) { logError("save", new RuntimeException(e)); done.done(null, String.valueOf(e.getMessage())); }
        };
    }

    /** GL thread: open a saved scan in the viewer. Photos stream in one by one. */
    void loadScan(File dir, Runnable done) {
        reset();
        improveDir = null; improveT = null;
        idle = false; viewing = true; sensors.active = false;
        lastPlanes = new java.util.ArrayList<>();
        currentDir = dir;
        activity.updateStatus("Opening the scan...");
        appendSaved(dir, true, () -> { enterViewer(); if (done != null) done.run(); });
    }

    /**
     * Load a saved scan and add it to what is here now (points deduplicated, photos appended, at most 80 photos),
     * then run then() on the GL thread. replaceMarks: take the saved room marks too.
     */
    private void appendSaved(File dir, boolean replaceMarks, Runnable then) {
        final int gen = surfGen;
        new Thread(() -> {
            try {
                ScanStore.Data d = ScanStore.load(dir);
                if (currentName == null || replaceMarks) currentName = d.name;
                glTasks.add(() -> {
                    if (gen != surfGen) return;
                    int[] rgb = new int[3];
                    for (int i = 0; i < d.n && count < MAX_POINTS; i++) {
                        float x = d.pos[i * 3], y = d.pos[i * 3 + 1], z = d.pos[i * 3 + 2];
                        long vk = key(x, y, z);
                        if (voxels.get(vk) >= 0) continue;
                        rgb[0] = d.col[i * 3] & 255; rgb[1] = d.col[i * 3 + 1] & 255; rgb[2] = d.col[i * 3 + 2] & 255;
                        voxels.put(vk, count);
                        addPoint(x, y, z, rgb);
                        obs[count - 1] = 2;
                    }
                    uploaded = 0;
                    for (int i = 0; i + 2 < d.path.length && pathCount < MAX_PATH; i += 3) {
                        path.put(pathCount * 3, d.path[i]); path.put(pathCount * 3 + 1, d.path[i + 1]); path.put(pathCount * 3 + 2, d.path[i + 2]); pathCount++;
                    }
                    if (replaceMarks) synchronized (marks) {
                        cornerList.clear(); wallList.clear(); markOrder.clear();
                        cornerList.addAll(d.corners); wallList.addAll(d.walls);
                        markFloor = d.floor; markCeiling = d.ceiling;
                    }
                });
                int room = Math.max(0, 80 - photos.keyframes.size());
                for (int i = 0; i < d.kfs.size() && i < room; i++) {
                    ScanStore.Kf k = d.kfs.get(i);
                    android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeByteArray(k.jpg, 0, k.jpg.length);
                    k.jpg = null;
                    if (b == null) continue;
                    final java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
                    glTasks.add(() -> { try { if (gen == surfGen) photos.addSaved(b, k); } finally { b.recycle(); l.countDown(); } });
                    l.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    if (i % 8 == 7) activity.updateStatus("Opening the scan: photo " + (i + 1) + " of " + d.kfs.size());
                }
                glTasks.add(() -> { if (gen == surfGen) then.run(); });
            } catch (Throwable e) {
                logError("load", new RuntimeException(e));
                activity.updateStatus("Could not open the scan: " + e.getMessage());
            }
        }).start();
    }

    /** GL thread: a fresh scan (not tied to a save yet). */
    void startNewScan() {
        reset();
        improveDir = null; improveT = null; currentDir = null; currentName = null;
        idle = false;
    }

    /** GL thread: back to the home screen. */
    void goHome() {
        training = false; depthRunning = false;
        idle = true;
    }

    /** GL thread: start a second pass over a saved scan. The new scan lines up once two of its corners are tapped. */
    void startImprove(File dir) {
        String keepName = currentName;
        reset();
        idle = false;
        currentName = keepName;
        improveDir = dir; improveT = null;
        improveCorners = ScanStore.loadCorners(dir);
        improveName = currentName;
        currentDir = dir;
        improveBase = null; improveBaseN = 0;
        new Thread(() -> {
            try {
                ScanStore.Data d = ScanStore.load(dir);
                improveName = d.name;
                int step = Math.max(1, d.n / 150000), k = 0;
                float[] b = new float[(d.n / step + 1) * 3];
                for (int i = 0; i < d.n; i += step) { b[k * 3] = d.pos[i * 3]; b[k * 3 + 1] = d.pos[i * 3 + 1]; b[k * 3 + 2] = d.pos[i * 3 + 2]; k++; }
                improveBase = b; improveBaseN = k;
            } catch (Throwable e) { logError("improve load", new RuntimeException(e)); }
        }).start();
        log("improving " + dir + " (" + improveCorners.size() + " saved corners)");
    }

    String improveHint() {
        if (improveDir == null) return null;
        if (improveCorners.size() < 2) return "This saved scan has no room corners marked, so the two scans can't be lined up. Scan normally; it will be kept as a new scan.";
        return "Improving \"" + (improveName == null ? "scan" : improveName) + "\": tap two of the room's corners (Room corner) to line this scan up with the saved one, then scan the parts you want sharper.";
    }

    /** GL thread (from a corner tap): find which saved corners the two newest taps are, and the transform. */
    private void lineUpWithSaved() {
        java.util.List<float[]> nc;
        synchronized (marks) { nc = new java.util.ArrayList<>(cornerList); }
        if (improveCorners.size() < 2 || nc.size() < 2) return;
        float[] a = nc.get(nc.size() - 2), b = nc.get(nc.size() - 1);
        float ln = (float) Math.hypot(b[0] - a[0], b[2] - a[2]);
        if (ln < 0.3f) return;
        // new points at mid height, for scoring each guess against the saved points
        int n = count, step = Math.max(1, n / 30000);
        float fy = floorGuess();
        java.util.ArrayList<float[]> probe = new java.util.ArrayList<>();
        for (int i = 0; i < n; i += step) { float y = pos.get(i * 3 + 1); if (y > fy + 0.3f && y < fy + 1.9f) probe.add(new float[]{pos.get(i * 3), y, pos.get(i * 3 + 2)}); }
        java.util.HashSet<Long> occ = new java.util.HashSet<>();
        float baseFloor = improveCorners.get(0)[1];
        if (improveBase != null) for (int i = 0; i < improveBaseN; i++) {
            float y = improveBase[i * 3 + 1];
            if (y > baseFloor + 0.3f && y < baseFloor + 1.9f) occ.add(cell(improveBase[i * 3], improveBase[i * 3 + 2]));
        }
        float[] best = null; float bestScore = -1; int bi = -1, bj = -1;
        int m = improveCorners.size();
        for (int i = 0; i < m; i++) for (int j = 0; j < m; j++) {
            if (i == j) continue;
            float[] p = improveCorners.get(i), q = improveCorners.get(j);
            float lb = (float) Math.hypot(q[0] - p[0], q[2] - p[2]);
            if (Math.abs(lb - ln) > 0.25f * Math.max(lb, ln)) continue;
            double th = Math.atan2(q[2] - p[2], q[0] - p[0]) - Math.atan2(b[2] - a[2], b[0] - a[0]);
            float c = (float) Math.cos(th), sn = (float) Math.sin(th);
            float nmx = (a[0] + b[0]) / 2, nmz = (a[2] + b[2]) / 2, bmx = (p[0] + q[0]) / 2, bmz = (p[2] + q[2]) / 2;
            float tx = bmx - (c * nmx - sn * nmz), tz = bmz - (sn * nmx + c * nmz), ty = p[1] - a[1];
            float score = Math.abs(lb - ln) < 0.05f ? 0.01f : 0; // tiny tie-break: lengths that match exactly
            if (!occ.isEmpty() && !probe.isEmpty()) {
                int hit = 0;
                for (float[] v : probe) if (occ.contains(cell(c * v[0] - sn * v[2] + tx, sn * v[0] + c * v[2] + tz))) hit++;
                score += hit / (float) probe.size();
            }
            if (score > bestScore) { bestScore = score; best = new float[]{c, sn, tx, ty, tz}; bi = i; bj = j; }
        }
        if (best == null) { activity.toast("Those two corners don't match any wall of the saved room (" + String.format(java.util.Locale.US, "%.2f m", ln) + "). Try two other corners."); return; }
        improveT = best;
        log(String.format(java.util.Locale.US, "lined up with saved corners %d-%d, overlap %.0f%%", bi + 1, bj + 1, bestScore * 100));
        activity.toast(String.format(java.util.Locale.US, "Lined up with the saved room (corners %d and %d, %.0f%% overlap). Its outline is shown in blue.", bi + 1, bj + 1, bestScore * 100));
    }

    private static long cell(float x, float z) { return ((long) Math.floor(x / 0.1f) + 100000) * 1000003L + ((long) Math.floor(z / 0.1f) + 100000); }

    private static final float[] GHOST_COL = {0.35f, 0.8f, 1f, 0.9f};

    /** Live view while improving: the saved room's corners and walls, moved into this scan's coordinates. */
    private void drawImproveGhost(float[] m, float fy, float top, float dens) {
        float[] t = improveT;
        if (t == null || improveCorners.size() < 2) return;
        int n = improveCorners.size();
        float c = t[0], sn = t[1];
        float[] ring = new float[n * 3], posts = new float[n * 6];
        for (int i = 0; i < n; i++) {
            float[] p = improveCorners.get(i);
            float x = p[0] - t[2], z = p[2] - t[4]; // inverse: R^T (p - t)
            float nx = c * x + sn * z, nz = -sn * x + c * z;
            ring[i * 3] = nx; ring[i * 3 + 1] = fy + 0.02f; ring[i * 3 + 2] = nz;
            posts[i * 6] = nx; posts[i * 6 + 1] = fy; posts[i * 6 + 2] = nz; posts[i * 6 + 3] = nx; posts[i * 6 + 4] = top; posts[i * 6 + 5] = nz;
        }
        planDraw(m, ring, n >= 3 ? GLES20.GL_LINE_LOOP : GLES20.GL_LINE_STRIP, GHOST_COL, 1);
        planDraw(m, posts, GLES20.GL_LINES, GHOST_COL, 1);
        float[] ceil = ring.clone();
        for (int i = 0; i < n; i++) ceil[i * 3 + 1] = top;
        planDraw(m, ceil, n >= 3 ? GLES20.GL_LINE_LOOP : GLES20.GL_LINE_STRIP, GHOST_COL, 1);
    }

    /** GL thread, end of an improve pass: move this scan into the saved scan's coordinates and merge them. */
    private void mergeImprove() {
        float[] t = improveT;
        File dir = improveDir;
        improveDir = null;
        if (t == null) {
            currentDir = null; currentName = null; // keep it as its own scan
            activity.toast("Not lined up with the saved scan (no matching corners), so this is kept as a new scan.");
            enterViewer();
            return;
        }
        float c = t[0], sn = t[1];
        float[] T = {c, 0, sn, 0, 0, 1, 0, 0, -sn, 0, c, 0, t[2], t[3], t[4], 1};
        for (int i = 0; i < count; i++) {
            float x = pos.get(i * 3), y = pos.get(i * 3 + 1), z = pos.get(i * 3 + 2);
            pos.put(i * 3, c * x - sn * z + t[2]); pos.put(i * 3 + 1, y + t[3]); pos.put(i * 3 + 2, sn * x + c * z + t[4]);
        }
        for (int i = 0; i < pathCount; i++) {
            float x = path.get(i * 3), y = path.get(i * 3 + 1), z = path.get(i * 3 + 2);
            path.put(i * 3, c * x - sn * z + t[2]); path.put(i * 3 + 1, y + t[3]); path.put(i * 3 + 2, sn * x + c * z + t[4]);
        }
        for (PhotoRoom.Keyframe k : photos.keyframes) {
            float[] np = new float[16];
            Matrix.multiplyMM(np, 0, T, 0, k.pose, 0);
            System.arraycopy(np, 0, k.pose, 0, 16);
            Matrix.invertM(k.view, 0, k.pose, 0);
            k.px = np[12]; k.py = np[13]; k.pz = np[14];
            k.fwd[0] = -np[8]; k.fwd[1] = -np[9]; k.fwd[2] = -np[10];
        }
        // the new scan's surface and cut-outs were in its own coordinates: drop them (Depth AI / Clean up rebuild)
        tsdf.clear(); detail.clear(); zones.clear(); meshPosAll = null; meshTris = 0; meshVerts = 0; cutouts.clear();
        lastPlanes = new java.util.ArrayList<>(); // ARCore planes were in the new scan's coordinates
        rebuildVoxelIndex();
        uploaded = 0;
        final int added = count;
        log("improve: merging " + count + " new points into " + dir);
        activity.updateStatus("Merging with the saved scan...");
        currentDir = dir;
        appendSaved(dir, true, () -> {
            enterViewer();
            activity.updateStatus(String.format(java.util.Locale.US, "Merged: %,d points from this pass + the saved scan = %,d points, %d photos. Run Depth AI and Clean up, then Save.\n%s",
                    added, count, photos.keyframes.size(), roomReady ? room.summary : ""));
        });
    }

    // ---- Depth AI: Depth Anything V2 on every keyframe photo, scaled by ARCore's points (see DepthNet) ----
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> glTasks = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile boolean depthRunning;
    private int depthAdded;

    boolean isDepthRunning() { return depthRunning; }

    /** GL thread: start or stop the depth AI pass. Returns the button label. */
    String toggleDepthAI() {
        if (depthRunning) { depthRunning = false; return "Depth AI"; }
        final java.util.List<PhotoRoom.Keyframe> kfs = new java.util.ArrayList<>(photos.keyframes);
        if (kfs.isEmpty()) { activity.updateStatus("No photos were saved during this scan, so there is nothing for the depth AI to look at."); return "Depth AI"; }
        training = false;
        // ARCore's measured points set the scale of each depth map
        final int n = count, step = Math.max(1, n / 60000);
        final float[] ref = new float[(n / step + 1) * 3];
        int rn = 0;
        for (int i = 0; i < n; i += step) { ref[rn * 3] = pos.get(i * 3); ref[rn * 3 + 1] = pos.get(i * 3 + 1); ref[rn * 3 + 2] = pos.get(i * 3 + 2); rn++; }
        final int refN = rn, gen = surfGen;
        depthRunning = true; depthAdded = 0;
        new Thread(() -> {
            DepthNet net = null;
            try {
                long tl = System.currentTimeMillis();
                net = new DepthNet(activity);
                log("depth AI: model loaded in " + (System.currentTimeMillis() - tl) + " ms, " + kfs.size() + " photos, " + refN + " ARCore points for scale");
                long t0 = System.currentTimeMillis(); int done = 0; float errSum = 0;
                for (int ki = 0; ki < kfs.size() && depthRunning && gen == surfGen; ki++) {
                    final PhotoRoom.Keyframe k = kfs.get(ki);
                    final android.graphics.Bitmap[] img = new android.graphics.Bitmap[1];
                    final android.graphics.Matrix map = new android.graphics.Matrix();
                    final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                    glTasks.add(() -> { try { img[0] = ObjectNamer.readUpright(k, width, height, map); } catch (Throwable e) { logError("depth read", new RuntimeException(e)); } finally { latch.countDown(); } });
                    if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS) || img[0] == null) continue;
                    float[] D = net.run(img[0]);
                    int iw = img[0].getWidth(), ih = img[0].getHeight();
                    int[] pix = new int[iw * ih];
                    img[0].getPixels(pix, 0, iw, 0, 0, iw, ih);
                    img[0].recycle();
                    float sx = (float) DepthNet.IW / iw, sy = (float) DepthNet.IH / ih;
                    // 1) scale fit: nearest ARCore point per 8 px cell vs the net's disparity there
                    int cw = iw / 8 + 1, chh = ih / 8 + 1;
                    float[] cd = new float[cw * chh], cr = new float[cw * chh], cuu = new float[cw * chh], cvv = new float[cw * chh];
                    float[] vm = k.view, pt = new float[2];
                    for (int i = 0; i < refN; i++) {
                        float x = ref[i * 3], y = ref[i * 3 + 1], z = ref[i * 3 + 2];
                        float xc = vm[0] * x + vm[4] * y + vm[8] * z + vm[12], yc = vm[1] * x + vm[5] * y + vm[9] * z + vm[13], zc = vm[2] * x + vm[6] * y + vm[10] * z + vm[14];
                        float d = -zc;
                        if (d < 0.15f || d > 8f) continue;
                        pt[0] = (k.fx * xc / d + k.cx) / k.w; pt[1] = (k.cy - k.fy * yc / d) / k.h;
                        if (pt[0] < 0 || pt[0] >= 1 || pt[1] < 0 || pt[1] >= 1) continue;
                        map.mapPoints(pt);
                        int X = (int) pt[0], Y = (int) pt[1];
                        if (X < 0 || Y < 0 || X >= iw || Y >= ih) continue;
                        int c = (Y / 8) * cw + X / 8;
                        if (cd[c] == 0 || d < cd[c]) { cd[c] = d; cuu[c] = (float) X / iw - 0.5f; cvv[c] = (float) Y / ih - 0.5f; cr[c] = D[Math.min(DepthNet.IH - 1, (int) (Y * sy)) * DepthNet.IW + Math.min(DepthNet.IW - 1, (int) (X * sx))]; }
                    }
                    float[] ab = fitInverseDepth(cd, cr, cuu, cvv);
                    if (ab == null) { log("depth AI: photo " + ki + " skipped (too few ARCore points to scale it)"); continue; }
                    errSum += ab[4]; done++;
                    // 2) back-project a 192x108 grid of the photo with the fitted depth
                    final int GU = 192, GV = 108;
                    float[] gd = new float[GU * GV]; int[] gc = new int[GU * GV];
                    for (int gv = 0; gv < GV; gv++) for (int gu = 0; gu < GU; gu++) {
                        pt[0] = (gu + 0.5f) / GU; pt[1] = (gv + 0.5f) / GV;
                        map.mapPoints(pt);
                        int X = Math.min(iw - 1, Math.max(0, (int) pt[0])), Y = Math.min(ih - 1, Math.max(0, (int) pt[1]));
                        float r = D[Math.min(DepthNet.IH - 1, (int) (Y * sy)) * DepthNet.IW + Math.min(DepthNet.IW - 1, (int) (X * sx))];
                        float inv = ab[0] * r + ab[1] + ab[2] * ((float) X / iw - 0.5f) + ab[3] * ((float) Y / ih - 0.5f);
                        gd[gv * GU + gu] = inv > 0.15f ? 1f / inv : 0;
                        gc[gv * GU + gu] = pix[Y * iw + X];
                    }
                    final float[] P = new float[GU * GV * 3]; final byte[] C = new byte[GU * GV * 3];
                    int m = 0;
                    float[] pm = k.pose;
                    for (int gv = 1; gv < GV - 1; gv++) for (int gu = 1; gu < GU - 1; gu++) {
                        int g = gv * GU + gu;
                        float d = gd[g];
                        if (d <= 0.2f || d > 6f) continue;
                        // flying pixels at depth edges: drop where the neighbours disagree by more than 4 %
                        float mx = Math.max(Math.max(Math.abs(gd[g - 1] - d), Math.abs(gd[g + 1] - d)), Math.max(Math.abs(gd[g - GU] - d), Math.abs(gd[g + GU] - d)));
                        if (mx > 0.04f * d) continue;
                        float u = (gu + 0.5f) / GU * k.w, v = (gv + 0.5f) / GV * k.h;
                        float xc = (u - k.cx) * d / k.fx, yc = (k.cy - v) * d / k.fy, zc = -d;
                        P[m * 3] = pm[0] * xc + pm[4] * yc + pm[8] * zc + pm[12];
                        P[m * 3 + 1] = pm[1] * xc + pm[5] * yc + pm[9] * zc + pm[13];
                        P[m * 3 + 2] = pm[2] * xc + pm[6] * yc + pm[10] * zc + pm[14];
                        int c = gc[g];
                        C[m * 3] = (byte) ((c >> 16) & 255); C[m * 3 + 1] = (byte) ((c >> 8) & 255); C[m * 3 + 2] = (byte) (c & 255);
                        m++;
                    }
                    final int mm = m; final float camX = pm[12], camY = pm[13], camZ = pm[14];
                    glTasks.add(() -> addDepthPoints(P, C, mm, camX, camY, camZ));
                    float each = (System.currentTimeMillis() - t0) / 1000f / (ki + 1);
                    activity.updateStatus(String.format(java.util.Locale.US,
                            "Depth AI: photo %d of %d (%.1f s each). Each photo's depth is guessed by a neural net and scaled with ARCore's measurements (agrees to %.0f%%). %,d points so far.",
                            ki + 1, kfs.size(), each, errSum / done * 100, depthAdded));
                }
                log("depth AI: " + done + " photos in " + (System.currentTimeMillis() - t0) + " ms, median disagreement with ARCore " + (done > 0 ? errSum / done : 0));
                glTasks.add(() -> finishDepthAI(gen));
            } catch (Throwable e) {
                logError("depth AI", new RuntimeException(e));
                activity.updateStatus("Depth AI failed: " + e);
            } finally {
                if (net != null) net.close();
                depthRunning = false;
            }
        }).start();
        return "Stop depth AI";
    }

    /**
     * Robust fit of 1/depth = a * disparity + b + c * x + d * y over the filled cells (x, y = photo position, -0.5..0.5).
     * The x/y terms take out the slight tilt the net puts into some photos (7.6 % -> 6.1 % median error on scan10).
     * Returns {a, b, c, d, median relative error} or null.
     */
    static float[] fitInverseDepth(float[] cd, float[] cr, float[] cu, float[] cv) {
        int n = 0;
        for (float d : cd) if (d > 0) n++;
        if (n < 40) return null;
        int k = n >= 150 ? 4 : 2;
        double[][] f = new double[n][];
        double[] y = new double[n], w = new double[n], e = new double[n];
        int j = 0;
        for (int i = 0; i < cd.length; i++) if (cd[i] > 0) {
            f[j] = k == 4 ? new double[]{cr[i], 1, cu[i], cv[i]} : new double[]{cr[i], 1};
            y[j] = 1.0 / cd[i]; w[j] = 1; j++;
        }
        double[] sol = null;
        for (int iter = 0; iter < 12; iter++) {
            double[][] h = new double[k][k + 1];
            for (int i = 0; i < n; i++) for (int p = 0; p < k; p++) {
                for (int q = 0; q < k; q++) h[p][q] += w[i] * f[i][p] * f[i][q];
                h[p][k] += w[i] * f[i][p] * y[i];
            }
            sol = solve(h, k);
            if (sol == null) return null;
            for (int i = 0; i < n; i++) { double p = 0; for (int q = 0; q < k; q++) p += sol[q] * f[i][q]; e[i] = Math.abs(p - y[i]); }
            double[] s = e.clone(); java.util.Arrays.sort(s);
            double sc = s[n / 2] * 1.5 + 1e-6;
            for (int i = 0; i < n; i++) w[i] = 1.0 / Math.max(1, e[i] / sc);
        }
        if (sol[0] <= 0) return null; // the net's disparity must grow as things get closer
        double[] rel = new double[n];
        for (int i = 0; i < n; i++) {
            double p = 0; for (int q = 0; q < k; q++) p += sol[q] * f[i][q];
            rel[i] = p > 1e-3 ? Math.abs(1 / p - 1 / y[i]) * y[i] : 1;
        }
        java.util.Arrays.sort(rel);
        return new float[]{(float) sol[0], (float) sol[1], k == 4 ? (float) sol[2] : 0, k == 4 ? (float) sol[3] : 0, (float) rel[n / 2]};
    }

    /** Gaussian elimination with partial pivoting on an augmented k x (k+1) matrix. */
    private static double[] solve(double[][] h, int k) {
        for (int c = 0; c < k; c++) {
            int p = c;
            for (int r = c + 1; r < k; r++) if (Math.abs(h[r][c]) > Math.abs(h[p][c])) p = r;
            if (Math.abs(h[p][c]) < 1e-12) return null;
            double[] t = h[c]; h[c] = h[p]; h[p] = t;
            for (int r = 0; r < k; r++) if (r != c) {
                double m = h[r][c] / h[c][c];
                for (int q = c; q <= k; q++) h[r][q] -= m * h[c][q];
            }
        }
        double[] x = new double[k];
        for (int c = 0; c < k; c++) x[c] = h[c][k] / h[c][c];
        return x;
    }

    /** GL thread: merge one photo's depth points into the scan (2 cm dedupe) and the surface. */
    private void addDepthPoints(float[] P, byte[] C, int m, float camX, float camY, float camZ) {
        int[] rgb = new int[3];
        for (int i = 0; i < m && count < MAX_POINTS; i++) {
            float x = P[i * 3], y = P[i * 3 + 1], z = P[i * 3 + 2];
            rgb[0] = C[i * 3] & 255; rgb[1] = C[i * 3 + 1] & 255; rgb[2] = C[i * 3 + 2] & 255;
            tsdf.integrate(camX, camY, camZ, x, y, z, rgb);
            long vk = key(x, y, z);
            int hit = voxels.get(vk);
            if (hit >= 0) { refine(hit, x, y, z, rgb); continue; }
            voxels.put(vk, count);
            addPoint(x, y, z, rgb);
            obs[count - 1] = 2;
            depthAdded++;
        }
    }

    /** GL thread: rebuild the surface, the room and the splats from the denser scan. */
    private void finishDepthAI(int gen) {
        if (gen != surfGen) return;
        try {
            meshTris = tsdf.extractMesh();
            meshPosAll = null;
        } catch (Throwable e) { logError("depth surface", new RuntimeException(e)); }
        activity.depthDone();
        activity.updateStatus(String.format(java.util.Locale.US, "Depth AI done: %,d points added. Cleaning up now...", depthAdded));
        startCleanUp();
    }

    private volatile boolean cleaning;

    /** GL thread: snap the scan onto its floor, ceiling and marked walls, drop specks, fill gaps (see CleanUp). */
    void startCleanUp() {
        if (cleaning || count == 0) return;
        cleaning = true; training = false;
        final int n = count, gen = surfGen;
        final FloatBuffer ps = FloatBuffer.allocate(n * 3);
        for (int i = 0; i < n * 3; i++) ps.put(i, pos.get(i));
        final ByteBuffer cs = ByteBuffer.allocate(n * 4);
        for (int i = 0; i < n * 4; i++) cs.put(i, col.get(i));
        float[][] uw = null;
        if (roomReady && room.fromCorners && !room.layout.isEmpty()) {
            uw = new float[room.layout.size()][];
            for (int i = 0; i < uw.length; i++) { RoomModel.Wall w = room.layout.get(i); uw[i] = new float[]{w.x0, w.z0, w.x1, w.z1}; }
        }
        final float[][] walls = uw;
        activity.updateStatus("Cleaning up the scan: finding floor, ceiling and walls...");
        new Thread(() -> { try {
            long t0 = System.currentTimeMillis();
            CleanUp c = CleanUp.run(ps, cs, n, walls);
            log(c.summary + " (" + (System.currentTimeMillis() - t0) + " ms) " + c.debug);
            glTasks.add(() -> applyCleanUp(c, gen));
        } catch (Throwable e) { cleaning = false; logError("clean-up", new RuntimeException(e)); } }).start();
    }

    private void applyCleanUp(CleanUp c, int gen) {
        cleaning = false;
        if (gen != surfGen) return;
        int n = Math.min(c.n, MAX_POINTS);
        for (int i = 0; i < n; i++) {
            pos.put(i * 3, c.pos[i * 3]); pos.put(i * 3 + 1, c.pos[i * 3 + 1]); pos.put(i * 3 + 2, c.pos[i * 3 + 2]);
            col.put(i * 4, c.col[i * 3]); col.put(i * 4 + 1, c.col[i * 3 + 1]); col.put(i * 4 + 2, c.col[i * 3 + 2]); col.put(i * 4 + 3, (byte) 255);
            obs[i] = 2;
        }
        count = n; uploaded = 0; refineCursor = 0;
        rebuildVoxelIndex();
        analyseRoom();
        buildSurfels();
        activity.updateStatus(c.summary + "\nBuilding splats now (a few seconds), then you can Train splats.\n" + (roomReady ? room.summary : ""));
    }

    // ---- on-phone splat training (see Trainer) ----
    private volatile Surfels surfBase;
    private volatile Trainer trainer;
    private volatile boolean training;
    private volatile java.util.List<Trainer.View> trainViews;

    boolean isTraining() { return training; }

    /** GL thread: start or pause the training. Returns the button label. */
    String toggleTraining() {
        if (training) { training = false; return "Train splats"; }
        final Surfels base = surfBase;
        if (base == null) { activity.updateStatus("The splats are still being built. Try again in a few seconds."); return "Train splats"; }
        if (trainViews == null) trainViews = readTrainViews();
        final java.util.List<Trainer.View> views = trainViews;
        if (views.isEmpty()) { activity.updateStatus("No photos were saved during this scan, so there is nothing to train against."); return "Train splats"; }
        style = 1; splats = true;
        training = true;
        final int gen = surfGen;
        new Thread(() -> { try {
            if (trainer == null) {
                long t0 = System.currentTimeMillis();
                trainer = Trainer.fromSurfels(base, base.col);
                log("training: " + trainer.n + " splats from " + base.n + " surfels, " + views.size() + " photos (" + (System.currentTimeMillis() - t0) + " ms)");
            }
            Trainer tr = trainer;
            java.util.Random rnd = new java.util.Random();
            long lastPub = 0, t0 = System.currentTimeMillis(); int it0 = tr.iter;
            float base0 = -1, ema = -1;
            while (training && gen == surfGen) {
                float l = tr.step(views.get(rnd.nextInt(views.size())));
                ema = ema < 0 ? l : ema * 0.95f + l * 0.05f;
                if (tr.iter <= 20) base0 = base0 < 0 ? l : base0 + (l - base0) / tr.iter;
                long now = System.currentTimeMillis();
                if (now - lastPub > 400) {
                    lastPub = now;
                    surfPending = tr.toSurfels();
                    float better = base0 > 0 ? Math.max(0, 1 - ema / base0) * 100 : 0;
                    float ms = (now - t0) / (float) Math.max(1, tr.iter - it0);
                    activity.updateStatus(String.format(java.util.Locale.US,
                            "Training splats on the phone: step %d (%.0f ms each), %d splats vs %d photos.\nMatches the photos %.0f%% better than the raw scan. Tap Pause any time, or let it keep honing.",
                            tr.iter, ms, tr.n, views.size(), better));
                }
            }
            if (gen == surfGen) surfPending = tr.toSurfels();
            log("training paused at step " + tr.iter + ", error " + tr.lastLoss);
        } catch (Throwable e) { training = false; logError("training", new RuntimeException(e)); } }).start();
        return "Pause training";
    }

    /** GL thread: the keyframe photos shrunk to 256x144, with their cameras. */
    private java.util.List<Trainer.View> readTrainViews() {
        java.util.List<Trainer.View> out = new java.util.ArrayList<>();
        final int W = 256, H = 144, TW = PhotoRoom.TEX_W, TH = PhotoRoom.TEX_H;
        int[] fb = new int[1];
        GLES20.glGenFramebuffers(1, fb, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb[0]);
        ByteBuffer px = ByteBuffer.allocateDirect(TW * TH * 4).order(ByteOrder.nativeOrder());
        for (PhotoRoom.Keyframe k : new java.util.ArrayList<>(photos.keyframes)) {
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, k.tex, 0);
            px.position(0);
            GLES20.glReadPixels(0, 0, TW, TH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
            Trainer.View v = new Trainer.View();
            v.view = k.view.clone(); v.W = W; v.H = H;
            v.fx = k.fx * W / k.w; v.fy = k.fy * H / k.h; v.cu = k.cx * W / k.w; v.cv = k.cy * H / k.h;
            v.img = new float[W * H * 3];
            for (int y = 0; y < H; y++) {
                int sy0 = y * TH / H, sy1 = Math.max(sy0 + 1, (y + 1) * TH / H);
                for (int x = 0; x < W; x++) {
                    int sx0 = x * TW / W, sx1 = Math.max(sx0 + 1, (x + 1) * TW / W);
                    float r = 0, g = 0, b = 0; int c = 0;
                    for (int sy = sy0; sy < sy1; sy++) for (int sx = sx0; sx < sx1; sx++) {
                        int o = (sy * TW + sx) * 4;
                        r += px.get(o) & 0xFF; g += px.get(o + 1) & 0xFF; b += px.get(o + 2) & 0xFF; c++;
                    }
                    int q = (y * W + x) * 3;
                    v.img[q] = r / c / 255f; v.img[q + 1] = g / c / 255f; v.img[q + 2] = b / c / 255f;
                }
            }
            out.add(v);
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glDeleteFramebuffers(1, fb, 0);
        GLES20.glViewport(0, 0, width, height);
        log("training photos: " + out.size());
        return out;
    }

    private void uploadSurfels() {
        Surfels s = surfPending;
        if (s == null) return;
        surfPending = null;
        s.pos.position(0); s.nrm.position(0); s.raw.position(0); s.col.position(0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, surfPosVbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, s.n * 12, s.pos, GLES20.GL_DYNAMIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, surfRawVbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, s.n * 12, s.raw, GLES20.GL_DYNAMIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, surfNrmVbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, s.n * 16, s.nrm, GLES20.GL_DYNAMIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, surfColVbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, s.n * 4, s.col, GLES20.GL_DYNAMIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        if (!s.trained) surfShownAt = System.currentTimeMillis();
        surfCount = s.n; surfTrained = s.trained;
    }
    private long surfShownAt;

    private void drawSurfels(float[] m) {
        GLES20.glUseProgram(surfProg);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(surfProg, "uMvp"), 1, false, m, 0);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(surfProg, "uView"), 1, false, view, 0);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(surfProg, "uPx"), height / (2f * (float) Math.tan(Math.toRadians(30))));
        GLES20.glUniform1f(GLES20.glGetUniformLocation(surfProg, "uCut"), cutActive ? 1f : 0f);
        GLES20.glUniform3f(GLES20.glGetUniformLocation(surfProg, "uCenter"), roomCenter[0], roomCenter[1], roomCenter[2]);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(surfProg, "uDir"), cutDirX, cutDirZ);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(surfProg, "uCutD"), cutD);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(surfProg, "uDir2"), cutDir2X, cutDir2Z);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(surfProg, "uCutD2"), cutD2);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(surfProg, "uFloor"), cutFloor);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(surfProg, "uCeil"), cutCeil);
        setHideUniforms(surfProg);
        setLightUniforms(surfProg);
        int aPos = GLES20.glGetAttribLocation(surfProg, "aPos");
        int aCol = GLES20.glGetAttribLocation(surfProg, "aColor");
        int aNrm = GLES20.glGetAttribLocation(surfProg, "aNrm");
        int aRaw = GLES20.glGetAttribLocation(surfProg, "aRaw");
        float t = Math.min(1f, (System.currentTimeMillis() - surfShownAt) / 2500f);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(surfProg, "uT"), t * t * (3 - 2 * t));
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, surfPosVbo);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, surfColVbo);
        GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_UNSIGNED_BYTE, true, 0, 0);
        GLES20.glEnableVertexAttribArray(aCol);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, surfRawVbo);
        GLES20.glVertexAttribPointer(aRaw, 3, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glEnableVertexAttribArray(aRaw);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, surfNrmVbo);
        GLES20.glVertexAttribPointer(aNrm, 4, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glEnableVertexAttribArray(aNrm);
        int uScale = GLES20.glGetUniformLocation(surfProg, "uScale"), uPass = GLES20.glGetUniformLocation(surfProg, "uPass");
        GLES20.glUniform1f(GLES20.glGetUniformLocation(surfProg, "uTrained"), surfTrained ? 1f : 0f);
        GLES20.glUniform1f(uScale, surfTrained ? 1.8f : 1.6f);
        GLES20.glUniform1f(uPass, 0f);
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, surfCount);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);
        GLES20.glUniform1f(uScale, surfTrained ? 1.8f : 2.4f);
        GLES20.glUniform1f(uPass, 1f);
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, surfCount);
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aCol);
        GLES20.glDisableVertexAttribArray(aNrm);
        GLES20.glDisableVertexAttribArray(aRaw);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }

    void finishScan() {
        java.util.List<RoomModel.PlaneData> planes = capturePlanes();
        viewing = true;
        sensors.active = false;
        log("finish: " + count + " points, " + diagnostics());
        dropNoise();
        log("refined " + refinedTotal + " re-observations, dropped " + droppedNoise + " one-off specks / see-through points, kept " + count);
        try {
            long t0 = System.currentTimeMillis();
            meshTris = tsdf.extractMesh();
            log("surface: " + tsdf.size() + " voxels -> " + meshTris + " triangles (" + (System.currentTimeMillis() - t0) + " ms)");
            if (!zones.isEmpty()) {
                int dt = detail.extractMesh();
                mergeDetail();
                log("detail zones: " + zones.size() + ", " + detail.size() + " fine voxels -> " + dt + " triangles; merged mesh " + meshVerts / 3 + " triangles");
            }
        } catch (Throwable e) { logError("surface", new RuntimeException(e)); }
        lastPlanes = planes;
        if (improveDir != null) { mergeImprove(); return; }
        enterViewer();
    }

    /** GL thread: room, splats and camera for the viewer, from whatever is in the scan now. */
    private void enterViewer() {
        viewing = true;
        buildSurfels();
        analyseRoom();
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
        standInRoom();
        finishStatus();
    }

    /**
     * Put the viewer in the middle of the room at the height the phone was held while scanning,
     * so the first view after a scan looks like standing there. Pinch out to see the room from outside.
     */
    private void standInRoom() {
        if (count == 0) return;
        float cx = target[0], cz = target[2];
        if (roomReady && room.hasLayout) {
            cx = 0; cz = 0;
            for (RoomModel.Wall w : room.layout) { cx += w.x0; cz += w.z0; }
            cx /= room.layout.size(); cz /= room.layout.size();
            roomCenter[0] = cx; roomCenter[2] = cz;
        }
        float eyeY = target[1];
        if (pathCount > 0) { // median phone height while scanning
            float[] ys = new float[pathCount];
            for (int i = 0; i < pathCount; i++) ys[i] = path.get(i * 3 + 1);
            java.util.Arrays.sort(ys);
            eyeY = ys[pathCount / 2];
        } else if (roomReady) eyeY = room.floorY + 1.5f;
        // face the way the phone was pointing when the scan was finished (camera looks down its -Z)
        int last = poseLog.n - 1;
        if (last >= 0) {
            float qx = poseLog.p[last * 7 + 3], qy = poseLog.p[last * 7 + 4], qz = poseLog.p[last * 7 + 5], qw = poseLog.p[last * 7 + 6];
            float fx = -2 * (qx * qz + qw * qy), fz = -(1 - 2 * (qx * qx + qy * qy));
            if (fx * fx + fz * fz > 1e-4f) yaw = (float) Math.atan2(-fx, -fz);
        }
        target[0] = cx; target[1] = eyeY; target[2] = cz;
        dist = 0.05f; pitch = 0.12f; // eye just behind the target, looking slightly down
    }

    private void finishStatus() {
        StringBuilder objs = new StringBuilder();
        if (roomReady) for (RoomModel.Box bx : room.boxes) {
            if (bx.name == null && bx.depth == 0 && bx.voxels < 40) continue;
            if ("noise".equals(bx.kind)) continue;
            objs.append(objs.length() > 0 ? ", " : "").append(bx.title());
            if (bx.parent != null) objs.append(" (on ").append(bx.parent.title()).append(")");
        }
        activity.updateStatus((claudeAdvice != null ? claudeAdvice : "") + (ClaudeHelper.sessionCalls > 0 ? "💰 " + ClaudeHelper.costLine() + "\n" : "") + (roomReady ? room.summary + "\n" : "") + (cutouts.size() > 0 ? cutouts.describe() + "\n" : "") + (roomReady && room.hasLayout ? "" : "Walls not found clearly, so this shows the raw scan. Scan more of the walls next time.\n") + "Drag to look around, pinch out to see it from outside, two fingers to move.");
    }

    private java.util.List<RoomModel.PlaneData> lastPlanes = new java.util.ArrayList<>();
    private volatile float knownH, knownW, knownD; // room size typed in by the user (0 = unknown)

    float[] knownSize() { return new float[]{knownH, knownW, knownD}; }

    /** GL thread: store the room size the user knows; if a scan is finished, rebuild the room model with it. */
    void setKnownSize(float h, float w, float d) {
        knownH = h; knownW = w; knownD = d;
        log(String.format(java.util.Locale.US, "known room size: height %.2f width %.2f depth %.2f", h, w, d));
        if (viewing && count > 0) { analyseRoom(); finishStatus(); }
    }

    // ---- tap a thing: cut it out of the photo and stand it in the room ----
    final Cutouts cutouts = new Cutouts();
    private final com.google.mlkit.vision.label.ImageLabeler cutLabeler = com.google.mlkit.vision.label.ImageLabeling.getClient(
            new com.google.mlkit.vision.label.defaults.ImageLabelerOptions.Builder().setConfidenceThreshold(0.4f).build());

    /** Everything measured at the moment of a tap, kept while the user says what they tapped. */
    private static final class TapInfo { float[] hit6, hit, pose, k, cam; float u, v, floorY; android.graphics.Bitmap img; }
    private volatile TapInfo lastTap;

    /** GL thread: measure the tapped spot with ARCore and keep the photo, then ask what it is. */
    private void prepareTap(Frame frame, Camera camera, float x, float y) {
        float[] h6 = hitAny(frame, camera, x, y);
        float[] hit = h6 == null ? null : new float[]{h6[0], h6[1], h6[2]};
        float[] pose = new float[16];
        camera.getPose().toMatrix(pose, 0);
        CameraIntrinsics ti = camera.getTextureIntrinsics();
        int[] td = ti.getImageDimensions();
        float[] k = {ti.getFocalLength()[0], ti.getFocalLength()[1], ti.getPrincipalPoint()[0], ti.getPrincipalPoint()[1], td[0], td[1]};
        float[] uv = new float[2];
        frame.transformCoordinates2d(com.google.ar.core.Coordinates2d.VIEW, new float[]{x, y}, com.google.ar.core.Coordinates2d.TEXTURE_NORMALIZED, uv);
        if (hit != null) { // the measured point says exactly where the tap is in the photo
            float[] v = new float[16]; Matrix.invertM(v, 0, pose, 0);
            float cx = v[0] * hit[0] + v[4] * hit[1] + v[8] * hit[2] + v[12], cy = v[1] * hit[0] + v[5] * hit[1] + v[9] * hit[2] + v[13], cz = v[2] * hit[0] + v[6] * hit[1] + v[10] * hit[2] + v[14];
            if (-cz > 0.05f) { uv[0] = (k[0] * cx / -cz + k[2]) / k[4]; uv[1] = (k[3] - k[1] * cy / -cz) / k[5]; }
        }
        RoomModel lr = liveRoom;
        float floorY = markFloor != null ? markFloor[1] : lr != null ? lr.floorY : planner.isReady() ? planner.floorY : hit != null ? hit[1] : pose[13] - 1.4f;
        android.graphics.Bitmap img = photos.grab(bgTex, colorGain, width, height);
        TapInfo t = new TapInfo();
        t.hit6 = h6; t.hit = hit; t.pose = pose; t.k = k; t.u = uv[0]; t.v = uv[1]; t.floorY = floorY; t.img = img;
        t.cam = new float[]{pose[12], pose[13], pose[14]};
        TapInfo old = lastTap;
        lastTap = t;
        if (old != null && old.img != null) old.img.recycle();
        activity.askTapKind();
    }

    /** GL thread: the user said what the tap was. kind: 0 object, 1 corner, 2 floor, 3 wall, 4 ceiling, 5 undo, -1 cancel. */
    void applyTap(int kind) {
        TapInfo t = lastTap;
        lastTap = null;
        if (t == null) return;
        if (kind != 0 && t.img != null) { t.img.recycle(); t.img = null; }
        if (kind < 0) return;
        if (kind == 5) { activity.toast(undoLast()); return; }
        if (kind != 0) { if (t.hit6 == null) { activity.toast("Couldn't measure there: point at it for a moment first, then tap again"); return; } handleMarkTap(t.hit6, t.cam, kind); return; }
        if (t.img == null) { activity.toast("Couldn't read the camera picture"); return; }
        float[] pose = t.pose, k = t.k, hit = t.hit, uv = {t.u, t.v};
        float floorY = t.floorY;
        android.graphics.Bitmap img = t.img;
        activity.toast("Cutting it out...");
        final float[] fhit = hit;
        cutouts.make(img, pose, k, uv[0], uv[1], hit, floorY, (c, err) -> {
            if (c == null) { activity.toast(err); log("cut-out failed: " + err); return; }
            log(String.format(java.util.Locale.US, "cut-out at (%.2f, %.2f) %.0f cm tall, %.0f cm wide%s", c.x, c.z, c.height * 100, c.width * 100, fhit == null ? " (no ARCore hit)" : ""));
            activity.toast(String.format(java.util.Locale.US, "Placed: %.0f cm tall. Tap it again from another side to improve it.", c.height * 100));
            android.graphics.Bitmap b = c.bmp;
            if (b != null) {
                android.graphics.Bitmap flat = android.graphics.Bitmap.createBitmap(b.getWidth(), b.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
                new android.graphics.Canvas(flat).drawColor(0xFF808080);
                new android.graphics.Canvas(flat).drawBitmap(b, 0, 0, null);
                cutLabeler.process(com.google.mlkit.vision.common.InputImage.fromBitmap(flat, 0)).addOnSuccessListener(labels -> {
                    if (!labels.isEmpty()) c.name = labels.get(0).getText().toLowerCase(java.util.Locale.US);
                    flat.recycle();
                }).addOnFailureListener(e -> flat.recycle());
            }
        });
    }

    // ---- tap to mark the room: corners, floor, walls, ceiling ----
    private final Object marks = new Object();
    private final java.util.List<float[]> cornerList = new java.util.ArrayList<>(); // x, y, z
    private final java.util.List<float[]> wallList = new java.util.ArrayList<>();   // x, y, z, nx, nz (normal into the room)
    private float[] markFloor, markCeiling;


    private final java.util.List<Integer> markOrder = new java.util.ArrayList<>();

    /** Take back the most recent mark (corner, wall, floor or ceiling). */
    String undoLast() {
        synchronized (marks) {
            if (markOrder.isEmpty()) return "Nothing to undo";
            int kind = markOrder.remove(markOrder.size() - 1);
            switch (kind) {
                case 1: if (!cornerList.isEmpty()) cornerList.remove(cornerList.size() - 1); return "Corner removed";
                case 2: markFloor = null; return "Floor mark removed";
                case 3: if (!wallList.isEmpty()) wallList.remove(wallList.size() - 1); return "Wall removed";
                case 4: markCeiling = null; return "Ceiling mark removed";
                default: return "Nothing to undo";
            }
        }
    }

    private String marksLine() {
        synchronized (marks) {
            StringBuilder b = new StringBuilder("📐 ");
            b.append(cornerList.size()).append(cornerList.size() == 1 ? " corner" : " corners").append(" · ").append(wallList.size()).append(wallList.size() == 1 ? " wall" : " walls");
            b.append(" · floor ").append(markFloor != null ? "set" : "not set");
            b.append(" · ceiling ").append(markCeiling != null ? String.format(java.util.Locale.US, "%.2f m", markCeiling[1] - floorGuess()) : "not set");
            b.append(" · ").append(cutouts.size()).append(" objects");
            return b.toString();
        }
    }

    private float floorGuess() {
        if (markFloor != null) return markFloor[1];
        RoomModel lr = liveRoom;
        return lr != null ? lr.floorY : planner.isReady() ? planner.floorY : 0;
    }

    /** First ARCore hit (or the nearest scanned point on the ray): position and surface normal. */
    private float[] hitAny(Frame frame, Camera camera, float x, float y) {
        try {
            for (com.google.ar.core.HitResult h : frame.hitTest(x, y)) {
                Pose p = h.getHitPose();
                float[] n = p.getYAxis();
                return new float[]{p.tx(), p.ty(), p.tz(), n[0], n[1], n[2]};
            }
        } catch (Exception e) { logError("hitTest", e); }
        float[] c = nearestPointOnRay(camera, x, y);
        return c == null ? null : new float[]{c[0], c[1], c[2], 0, 0, 0};
    }

    private void handleMarkTap(float[] h, float[] cam, int kind) {
        synchronized (marks) {
            switch (kind) {
                case 1: {
                    float fy = floorGuess();
                    if (cornerList.size() >= 3) { // tapping the first corner again closes the room
                        float[] f = cornerList.get(0);
                        if (Math.hypot(f[0] - h[0], f[2] - h[2]) < 0.25f) { activity.toast("Room closed with " + cornerList.size() + " corners."); return; }
                    }
                    cornerList.add(new float[]{h[0], fy, h[2]});
                    int n = cornerList.size();
                    if (n >= 2) {
                        float[] a = cornerList.get(n - 2);
                        activity.toast(String.format(java.util.Locale.US, "Corner %d · wall %.2f m", n, Math.hypot(h[0] - a[0], h[2] - a[2])));
                    } else activity.toast("Corner 1. Now the next corner along the wall.");
                    log(String.format(java.util.Locale.US, "corner %d at (%.2f, %.2f)", n, h[0], h[2]));
                    markOrder.add(1);
                    if (improveDir != null && n >= 2) lineUpWithSaved();
                    break;
                }
                case 2:
                    markFloor = new float[]{h[0], h[1], h[2]};
                    activity.toast(String.format(java.util.Locale.US, "Floor set (%.2f m below the phone)", cam[1] - h[1]));
                    log(String.format(java.util.Locale.US, "floor mark y=%.2f", h[1]));
                    markOrder.add(2);
                    break;
                case 3: {
                    // a wall is upright: use the hit's normal if it's horizontal-ish, else face the phone
                    float nx = h[3], nz = h[5];
                    if (Math.hypot(nx, nz) < 0.7f) { nx = cam[0] - h[0]; nz = cam[2] - h[2]; }
                    float l = (float) Math.hypot(nx, nz);
                    if (l < 1e-3f) return;
                    nx /= l; nz /= l;
                    if (nx * (cam[0] - h[0]) + nz * (cam[2] - h[2]) < 0) { nx = -nx; nz = -nz; } // into the room
                    wallList.add(new float[]{h[0], h[1], h[2], nx, nz});
                    activity.toast(String.format(java.util.Locale.US, "Wall %d · %.2f m from you", wallList.size(), Math.hypot(cam[0] - h[0], cam[2] - h[2])));
                    log(String.format(java.util.Locale.US, "wall %d at (%.2f, %.2f) facing (%.2f, %.2f)", wallList.size(), h[0], h[2], nx, nz));
                    markOrder.add(3);
                    break;
                }
                case 4:
                    markCeiling = new float[]{h[0], h[1], h[2]};
                    activity.toast(String.format(java.util.Locale.US, "Ceiling set: room %.2f m high", h[1] - floorGuess()));
                    log(String.format(java.util.Locale.US, "ceiling mark y=%.2f (%.2f m above the floor)", h[1], h[1] - floorGuess()));
                    markOrder.add(4);
                    break;
            }
        }
    }

    /** The room outline from your taps: the corners if 3+, else the walls cut against each other (convex room). */
    private java.util.List<float[]> roomPolygon() {
        if (cornerList.size() >= 3) {
            java.util.List<float[]> p = new java.util.ArrayList<>();
            for (float[] c : cornerList) p.add(new float[]{c[0], c[2]});
            return p;
        }
        if (wallList.size() < 3) return null;
        float mx = 0, mz = 0;
        for (float[] w : wallList) { mx += w[0]; mz += w[2]; }
        mx /= wallList.size(); mz /= wallList.size();
        java.util.List<float[]> poly = new java.util.ArrayList<>();
        float big = 30;
        poly.add(new float[]{mx - big, mz - big}); poly.add(new float[]{mx + big, mz - big}); poly.add(new float[]{mx + big, mz + big}); poly.add(new float[]{mx - big, mz + big});
        for (float[] w : wallList) { // keep the side the normal points to
            java.util.List<float[]> out = new java.util.ArrayList<>();
            for (int i = 0; i < poly.size(); i++) {
                float[] a = poly.get(i), b = poly.get((i + 1) % poly.size());
                float da = (a[0] - w[0]) * w[3] + (a[1] - w[2]) * w[4], db = (b[0] - w[0]) * w[3] + (b[1] - w[2]) * w[4];
                if (da >= 0) out.add(a);
                if ((da >= 0) != (db >= 0)) { float t = da / (da - db); out.add(new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t}); }
            }
            poly = out;
            if (poly.size() < 3) return null;
        }
        for (float[] c : poly) if (Math.abs(c[0] - mx) > big * 0.9f || Math.abs(c[1] - mz) > big * 0.9f) return null; // open side: not enough walls
        return poly;
    }

    private static final float[] MARK_COL = {1f, 0.85f, 0.2f, 1f}, MARK_FILL = {1f, 0.85f, 0.2f, 0.18f}, CEIL_COL = {0.5f, 0.85f, 1f, 1f};

    /** Your marks: corners joined on the floor with posts, walls as tall panels, floor and ceiling marks. */
    private void drawMarks(float[] m, boolean live) {
        float dens = height / 1000f;
        java.util.List<float[]> poly; float fy, top;
        java.util.List<float[]> walls;
        float[] fl, ce;
        synchronized (marks) {
            poly = roomPolygon();
            walls = new java.util.ArrayList<>(wallList);
            fl = markFloor; ce = markCeiling;
            fy = floorGuess();
            top = ce != null ? ce[1] : fy + 2.5f;
            if (cornerList.size() > 0 && cornerList.size() < 3) { poly = new java.util.ArrayList<>(); for (float[] c : cornerList) poly.add(new float[]{c[0], c[2]}); }
        }
        if (!live && roomReady && room.fromCorners) poly = null; // the finished room already shows them
        if (live) drawImproveGhost(m, fy, top, dens);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glLineWidth(4f * dens);
        if (poly != null && !poly.isEmpty()) {
            int n = poly.size();
            float[] ring = new float[n * 3], posts = new float[n * 6];
            for (int i = 0; i < n; i++) {
                float[] c = poly.get(i);
                ring[i * 3] = c[0]; ring[i * 3 + 1] = fy + 0.01f; ring[i * 3 + 2] = c[1];
                posts[i * 6] = c[0]; posts[i * 6 + 1] = fy; posts[i * 6 + 2] = c[1]; posts[i * 6 + 3] = c[0]; posts[i * 6 + 4] = top; posts[i * 6 + 5] = c[1];
            }
            planDraw(m, ring, n >= 3 ? GLES20.GL_LINE_LOOP : GLES20.GL_LINE_STRIP, MARK_COL, 1);
            planDraw(m, posts, GLES20.GL_LINES, MARK_COL, 1);
            planDraw(m, ring, GLES20.GL_POINTS, MARK_COL, 22 * dens);
            for (int i = 0; i + 1 < n || (n >= 3 && i < n); i++) { // wall lengths
                float[] a = poly.get(i), b = poly.get((i + 1) % n);
                drawLabel(m, (a[0] + b[0]) / 2, fy + 0.05f, (a[1] + b[1]) / 2, String.format(java.util.Locale.US, "%.2f m", Math.hypot(b[0] - a[0], b[1] - a[1])), false);
            }
        }
        for (float[] w : walls) { // a 1.2 m wide panel on each tapped wall
            float tx = -w[4], tz = w[3];
            float x0 = w[0] - tx * 0.6f, z0 = w[2] - tz * 0.6f, x1 = w[0] + tx * 0.6f, z1 = w[2] + tz * 0.6f;
            float[] q = {x0, fy, z0, x1, fy, z1, x1, top, z1, x0, fy, z0, x1, top, z1, x0, top, z0};
            planDraw(m, q, GLES20.GL_TRIANGLES, MARK_FILL, 1);
            planDraw(m, new float[]{x0, fy, z0, x1, fy, z1, x1, fy, z1, x1, top, z1, x1, top, z1, x0, top, z0, x0, top, z0, x0, fy, z0}, GLES20.GL_LINES, MARK_COL, 1);
        }
        if (fl != null) planDraw(m, new float[]{fl[0], fl[1], fl[2]}, GLES20.GL_POINTS, MARK_COL, 30 * dens);
        if (ce != null) {
            planDraw(m, new float[]{ce[0], ce[1], ce[2]}, GLES20.GL_POINTS, CEIL_COL, 30 * dens);
            planDraw(m, new float[]{ce[0], fy, ce[2], ce[0], ce[1], ce[2]}, GLES20.GL_LINES, CEIL_COL, 1);
        }
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
    }

    // ---- tap to rescan an area (no longer bound to a tap) ----
    private volatile float[] pendingTap;
    private float[] rescanAt;      // centre of the area being redone
    private int rescanFound;       // fresh points in it so far
    private static final float RESCAN_R = 0.35f;

    void requestRescan(float x, float y) { pendingTap = new float[]{x, y}; }

    /** GL thread: find what was tapped, wipe the points and surface around it, and mark it until it's rescanned. */
    private void handleRescanTap(Frame frame, Camera camera, float x, float y) {
        float[] c = null;
        try {
            for (com.google.ar.core.HitResult h : frame.hitTest(x, y)) {
                Pose p = h.getHitPose();
                c = new float[]{p.tx(), p.ty(), p.tz()};
                break;
            }
        } catch (Exception e) { logError("hitTest", e); }
        if (c == null) c = nearestPointOnRay(camera, x, y);
        if (c == null) { activity.toast("Nothing scanned there yet, just point the camera at it."); return; }
        int removed = removePointsNear(c, RESCAN_R);
        int vox = tsdf.clearSphere(c[0], c[1], c[2], RESCAN_R);
        detail.clearSphere(c[0], c[1], c[2], RESCAN_R);
        if (zones.size() < 12) zones.add(new float[]{c[0], c[1], c[2], RESCAN_R});
        rescanAt = c; rescanFound = 0;
        log(String.format(java.util.Locale.US, "rescan at (%.2f, %.2f, %.2f): removed %d points, reset %d surface voxels", c[0], c[1], c[2], removed, vox));
        activity.toast("Detail zone added. Move close and scan the ring slowly from a few angles.");
    }

    /** A detail zone is within 1.5 m in front of the camera. */
    private boolean lookingAtZone(float[] pose) {
        for (float[] q : zones) {
            float dx = q[0] - pose[12], dy = q[1] - pose[13], dz = q[2] - pose[14];
            float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < 1.5f && d > 0.05f && -(dx * pose[8] + dy * pose[9] + dz * pose[10]) / d > 0.8f) return true;
        }
        return false;
    }

    /** Closest scanned point to the tapped screen ray (within 15 cm of it). */
    private float[] nearestPointOnRay(Camera camera, float sx, float sy) {
        float[] pv = new float[16], inv = new float[16];
        Matrix.multiplyMM(pv, 0, proj, 0, view, 0);
        if (!Matrix.invertM(inv, 0, pv, 0)) return null;
        float nx = 2f * sx / width - 1f, ny = 1f - 2f * sy / height;
        float[] a = new float[4], b = new float[4];
        Matrix.multiplyMV(a, 0, inv, 0, new float[]{nx, ny, -1, 1}, 0);
        Matrix.multiplyMV(b, 0, inv, 0, new float[]{nx, ny, 1, 1}, 0);
        float ox = a[0] / a[3], oy = a[1] / a[3], oz = a[2] / a[3];
        float dx = b[0] / b[3] - ox, dy = b[1] / b[3] - oy, dz = b[2] / b[3] - oz;
        float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz); dx /= l; dy /= l; dz /= l;
        float best = Float.MAX_VALUE; float[] hit = null;
        for (int i = 0; i < count; i++) {
            float px = pos.get(i * 3) - ox, py = pos.get(i * 3 + 1) - oy, pz = pos.get(i * 3 + 2) - oz;
            float t = px * dx + py * dy + pz * dz;
            if (t < 0.1f) continue;
            float qx = px - dx * t, qy = py - dy * t, qz = pz - dz * t;
            float off = qx * qx + qy * qy + qz * qz;
            if (off > 0.15f * 0.15f) continue;
            if (t < best) { best = t; hit = new float[]{pos.get(i * 3), pos.get(i * 3 + 1), pos.get(i * 3 + 2)}; }
        }
        return hit;
    }

    private int removePointsNear(float[] c, float r) {
        int k = 0;
        for (int i = 0; i < count; i++) {
            float dx = pos.get(i * 3) - c[0], dy = pos.get(i * 3 + 1) - c[1], dz = pos.get(i * 3 + 2) - c[2];
            if (dx * dx + dy * dy + dz * dz < r * r) continue;
            if (k != i) {
                pos.put(k * 3, pos.get(i * 3)); pos.put(k * 3 + 1, pos.get(i * 3 + 1)); pos.put(k * 3 + 2, pos.get(i * 3 + 2));
                for (int j = 0; j < 4; j++) col.put(k * 4 + j, col.get(i * 4 + j));
                obs[k] = obs[i];
            }
            k++;
        }
        int removed = count - k;
        count = k; uploaded = 0; refineCursor = 0;
        voxels.clear();
        rebuildVoxelIndex();
        surfCount = 0;
        if (viewing && removed > 0) buildSurfels();
        return removed;
    }

    private void rebuildVoxelIndex() {
        voxels.clear();
        for (int i = 0; i < count; i++) {
            float x = pos.get(i * 3), y = pos.get(i * 3 + 1), z = pos.get(i * 3 + 2);
            voxels.put(!zones.isEmpty() && inZone(x, y, z) ? fineKey(x, y, z) : key(x, y, z), i);
        }
    }

    private static final float[] RING_TODO = {1f, 0.75f, 0.1f, 1f}, RING_DONE = {0.2f, 1f, 0.5f, 1f};

    /** A ring facing the camera around the area being rescanned; turns green and goes away once it's filled in. */
    private void drawRescanTarget(float[] m, Camera camera) {
        float[] c = rescanAt;
        if (c == null) return;
        if (frameNo % 15 == 0) {
            int n = 0;
            for (int i = 0; i < count; i++) {
                float dx = pos.get(i * 3) - c[0], dy = pos.get(i * 3 + 1) - c[1], dz = pos.get(i * 3 + 2) - c[2];
                if (dx * dx + dy * dy + dz * dz < RESCAN_R * RESCAN_R) n++;
            }
            rescanFound = n;
            if (n >= 600) {
                log("rescan done: " + n + " fresh points");
                activity.toast("Area rescanned ✓");
                rescanAt = null;
                return;
            }
        }
        float[] pm = new float[16];
        camera.getPose().toMatrix(pm, 0);
        float rx = pm[0], ry = pm[1], rz = pm[2], ux = pm[4], uy = pm[5], uz = pm[6];
        float[] ring = new float[48 * 3];
        float pulse = RESCAN_R * (0.9f + 0.1f * (float) Math.sin(frameNo * 0.2f));
        for (int i = 0; i < 48; i++) {
            double a = i * Math.PI * 2 / 48;
            float ca = (float) Math.cos(a) * pulse, sa = (float) Math.sin(a) * pulse;
            ring[i * 3] = c[0] + rx * ca + ux * sa; ring[i * 3 + 1] = c[1] + ry * ca + uy * sa; ring[i * 3 + 2] = c[2] + rz * ca + uz * sa;
        }
        GLES20.glLineWidth(6f * height / 1000f);
        float done = Math.min(1f, rescanFound / 600f);
        float[] col4 = {RING_TODO[0] + (RING_DONE[0] - RING_TODO[0]) * done, RING_TODO[1] + (RING_DONE[1] - RING_TODO[1]) * done, RING_TODO[2] + (RING_DONE[2] - RING_TODO[2]) * done, 1f};
        planDraw(m, ring, GLES20.GL_LINE_LOOP, col4, 1);
    }

    // ---- live room estimate while scanning ----
    private volatile RoomModel liveRoom;
    private volatile boolean liveBusy;
    private long liveLastMs;

    /** Every 2 s, estimate the room from a snapshot of the points on a background thread. */
    private void maybeStartLiveRoom(long nowMs) {
        if (liveBusy || nowMs - liveLastMs < 2000 || count < 3000) return;
        liveLastMs = nowMs; liveBusy = true;
        final int n = count;
        final FloatBuffer p = FloatBuffer.allocate(n * 3);
        final ByteBuffer c = ByteBuffer.allocate(n * 4);
        pos.position(0); pos.limit(n * 3); p.put(pos); pos.limit(pos.capacity()); pos.position(0); p.position(0);
        col.position(0); col.limit(n * 4); c.put(col); col.limit(col.capacity()); col.position(0); c.position(0);
        final float h = knownH, w = knownW, d = knownD;
        final java.util.List<float[]> poly; final float uf, uc;
        synchronized (marks) { poly = roomPolygon(); uf = markFloor != null ? markFloor[1] : Float.NaN; uc = markCeiling != null ? markCeiling[1] : Float.NaN; }
        new Thread(() -> {
            try {
                ShotPlanner sp = new ShotPlanner();
                sp.analyse(p, n);
                if (sp.isReady()) {
                    RoomModel rm = new RoomModel();
                    rm.knownH = h; rm.knownW = w; rm.knownD = d;
                    rm.userCorners = poly; rm.userFloorY = uf; rm.userCeilingY = uc;
                    rm.build(new java.util.ArrayList<>(), sp, p, c, n);
                    applyLabels(rm.boxes);
                    claudeObjs.mergeInto(rm.boxes, rm.floorY);
                    liveRoom = rm;
                }
            } catch (Throwable e) { /* a live estimate is best effort */ }
            liveBusy = false;
        }).start();
    }

    /** One line for the scanning status: size so far, walls found, how much floor is covered. */
    private String liveRoomLine() {
        RoomModel r = liveRoom;
        if (r == null) return "Room: point at the floor and walls to start measuring";
        if (!r.hasLayout) return "Room: floor found, keep scanning the walls";
        int seen = 0;
        for (RoomModel.Wall w : r.layout) if (w.seen) seen++;
        return String.format(java.util.Locale.US, "Room so far: %.1f × %.1f m%s · height %s · walls %d/4 · floor %d%% covered",
                r.roomW, r.roomD, knownW > 0 || knownD > 0 ? " (entered)" : "",
                r.hasCeiling ? String.format(java.util.Locale.US, "%.1f m%s", r.topY - r.floorY, knownH > 0 ? " (entered)" : "") : "?",
                seen, Math.round(r.floorCoverage * 100));
    }

    private static final float[] LIVE_SEEN = {0.2f, 1f, 0.85f, 1f}, LIVE_GUESS = {1f, 0.6f, 0.2f, 0.8f};

    /** Draws the estimated room box over the camera view: solid teal for walls found, orange for guessed ones. */
    private void drawLiveRoom(float[] m) {
        RoomModel r = liveRoom;
        if (r == null) return;
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        if (SHOW_OBJECTS) drawObjects(m, r.boxes, true);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDepthMask(true);
        if (!r.hasLayout) return;
        float dens = height / 1000f;
        GLES20.glLineWidth(4f * dens);
        float y0 = r.floorY, y1 = r.topY;
        for (RoomModel.Wall w : r.layout) {
            float[] c = w.seen ? LIVE_SEEN : LIVE_GUESS;
            planDraw(m, new float[]{w.x0, y0, w.z0, w.x1, y0, w.z1}, GLES20.GL_LINES, c, 1);
            planDraw(m, new float[]{w.x0, y1, w.z0, w.x1, y1, w.z1}, GLES20.GL_LINES, c, 1);
            planDraw(m, new float[]{w.x0, y0, w.z0, w.x0, y1, w.z0}, GLES20.GL_LINES, LIVE_SEEN, 1);
        }
    }

    // ---- clean view: recognised objects are drawn as simple models and the scan inside them is hidden ----
    /** Shot lighting (see Lighting): up to 4 gear lights added on top of the room light baked into the scan colours. */
    static final String LIGHT_GLSL =
            "uniform float uLit; uniform float uRoom; uniform float uExpo; uniform vec3 uLPos[4]; uniform vec3 uLAim[4]; uniform vec3 uLCol[4]; uniform vec3 uLCone[4];" +
            "vec3 gear(vec3 p, vec3 n, float hasN, float twoSided) { vec3 E = vec3(0.0);" +
            "  for (int i = 0; i < 4; i++) { vec3 L = uLPos[i] - p; float d2 = max(0.04, dot(L, L)); L *= inversesqrt(d2);" +
            "    float ndl = dot(n, L); if (twoSided > 0.5) ndl = abs(ndl); if (hasN < 0.5) ndl = 0.6;" +
            "    float w = uLCone[i].z; float lam = max(0.0, (ndl + w) / (1.0 + w));" +
            "    vec3 A = uLAim[i] - uLPos[i]; float al = length(A); float c = al > 0.001 ? dot(-L, A / al) : 1.0;" +
            "    E += uLCol[i] * smoothstep(uLCone[i].x, uLCone[i].y, c) * lam / d2; }" +
            "  return E; }" +
            "vec3 tone(vec3 c) { vec3 s = 0.8 + 0.2 * (1.0 - exp(-(c - 0.8) / 0.2)); return pow(clamp(mix(c, s, step(0.8, c)), 0.0, 1.0), vec3(1.0 / 2.2)); }";

    static final String HIDE_DECL = "uniform vec4 uHA[16]; uniform vec4 uHB[16]; uniform float uHN;";
    static final String HIDE_TEST = "for (int i = 0; i < 16; i++) { if (float(i) >= uHN) break; vec2 hd = aPos.xz - uHA[i].xy;"
            + " float hu = dot(hd, uHA[i].zw), hv = dot(hd, vec2(-uHA[i].w, uHA[i].z));"
            + " if (abs(hu) < uHB[i].x && abs(hv) < uHB[i].y && aPos.y > uHB[i].z && aPos.y < uHB[i].w) { HIDE } }";
    private static final float[] hideA = new float[64], hideB = new float[64];
    private static int hideN;
    private volatile boolean cleanView = true;

    boolean toggleClean() { cleanView = !cleanView; return cleanView; }

    static void setHideUniforms(int prog) {
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(prog, "uHA"), 16, hideA, 0);
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(prog, "uHB"), 16, hideB, 0);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uHN"), hideN);
    }

    private static boolean recognised(RoomModel.Box b) { return b.kind != null && !b.kind.equals("other"); }

    /** Objects Claude recognised (or called noise) hide the raw scan inside them. GL thread, once per frame. */
    private void updateHideList() {
        hideN = 0;
        if (!cleanView || !roomReady) return;
        for (RoomModel.Box b : room.boxes) {
            if (hideN >= 16) break;
            if (!recognised(b)) continue;
            int i = hideN * 4;
            hideA[i] = b.cx; hideA[i + 1] = b.cz; hideA[i + 2] = b.cosA; hideA[i + 3] = b.sinA;
            hideB[i] = b.hu + 0.03f; hideB[i + 1] = b.hv + 0.03f; hideB[i + 2] = b.y0 - 0.02f; hideB[i + 3] = b.y1 + 0.03f;
            hideN++;
        }
    }

    private static final float[] ITEM_COL = {0.3f, 0.85f, 1f, 0.3f}, ITEM_EDGE = {0.35f, 0.9f, 1f, 1f};

    /** Every object the scan found as a translucent box / cylinder with a name tag (furniture orange, things on it blue). */
    private void drawObjects(float[] m, java.util.List<RoomModel.Box> boxes, boolean live) {
        float dens = height / 1000f;
        GLES20.glLineWidth((live ? 3f : 2.5f) * dens);
        for (RoomModel.Box b : boxes) {
            if (!live && cutHides(b)) continue;
            if ("noise".equals(b.kind)) continue;
            if (recognised(b)) { drawCartoon(m, b); continue; }
            boolean item = b.depth > 0;
            planDraw(m, objTriangles(b), GLES20.GL_TRIANGLES, item ? ITEM_COL : BOX_COL, 1);
            planDraw(m, objEdges(b), GLES20.GL_LINES, item ? ITEM_EDGE : BOX_EDGE, 1);
        }
        int labels = 0;
        for (RoomModel.Box b : boxes) {
            if (!live && cutHides(b)) continue;
            if ("noise".equals(b.kind)) continue;
            if (labels++ > 40) break;
            String where = b.where();
            drawLabel(m, b.cx, b.y1 + 0.03f, b.cz, b.title() + (where.isEmpty() ? "" : "  ·  " + where), b.depth > 0);
        }
    }

    // ---- low-poly cartoon models for what Claude recognised ----
    private static final float[] OUTLINE = {0.08f, 0.07f, 0.06f, 1f};

    private static float[] kindColour(String k) {
        switch (k) {
            case "table": case "desk": return new float[]{0.80f, 0.58f, 0.35f, 1f};
            case "chair": case "stool": return new float[]{0.95f, 0.55f, 0.30f, 1f};
            case "bed": return new float[]{0.55f, 0.70f, 0.95f, 1f};
            case "sofa": return new float[]{0.60f, 0.45f, 0.85f, 1f};
            case "shelf": case "cabinet": case "wardrobe": return new float[]{0.70f, 0.52f, 0.38f, 1f};
            case "shoe": return new float[]{0.95f, 0.95f, 0.92f, 1f};
            case "bottle": case "can": case "cup": case "vase": return new float[]{0.35f, 0.85f, 0.75f, 1f};
            case "lamp": return new float[]{1f, 0.88f, 0.35f, 1f};
            case "plant": return new float[]{0.40f, 0.80f, 0.40f, 1f};
            case "bag": return new float[]{0.85f, 0.40f, 0.45f, 1f};
            case "screen": return new float[]{0.25f, 0.28f, 0.35f, 1f};
            default: return new float[]{0.80f, 0.80f, 0.85f, 1f};
        }
    }

    /** One part of a cartoon model: a box in the object's own axes (u along hu, v along hv), shaded per face, outlined. */
    private void cbox(float[] m, RoomModel.Box b, float u0, float u1, float v0, float v1, float y0, float y1, float[] col) {
        float[][] c = new float[4][];
        float[][] uv = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
        for (int i = 0; i < 4; i++)
            c[i] = new float[]{b.cx + uv[i][0] * b.cosA - uv[i][1] * b.sinA, b.cz + uv[i][0] * b.sinA + uv[i][1] * b.cosA};
        float[] top = {c[0][0], y1, c[0][1], c[1][0], y1, c[1][1], c[2][0], y1, c[2][1], c[0][0], y1, c[0][1], c[2][0], y1, c[2][1], c[3][0], y1, c[3][1]};
        planDraw(m, top, GLES20.GL_TRIANGLES, col, 1);
        for (int i = 0; i < 4; i++) {
            float[] p = c[i], q = c[(i + 1) % 4];
            float shade = (i % 2 == 0) ? 0.78f : 0.62f; // two side tones give the flat cartoon look
            float[] sc = {col[0] * shade, col[1] * shade, col[2] * shade, 1f};
            planDraw(m, new float[]{p[0], y0, p[1], q[0], y0, q[1], q[0], y1, q[1], p[0], y0, p[1], q[0], y1, q[1], p[0], y1, p[1]}, GLES20.GL_TRIANGLES, sc, 1);
        }
        float[] e = new float[24 * 3]; int k = 0;
        for (int i = 0; i < 4; i++) {
            float[] p = c[i], q = c[(i + 1) % 4];
            float[] seg = {p[0], y1, p[1], q[0], y1, q[1], p[0], y0, p[1], q[0], y0, q[1], p[0], y0, p[1], p[0], y1, p[1]};
            System.arraycopy(seg, 0, e, k, 18); k += 18;
        }
        planDraw(m, java.util.Arrays.copyOf(e, k), GLES20.GL_LINES, OUTLINE, 1);
    }

    private void drawCartoon(float[] m, RoomModel.Box b) {
        String k = b.kind;
        float[] col = kindColour(k);
        float hu = b.hu, hv = b.hv, y0 = b.y0, y1 = b.y1, h = y1 - y0, leg = Math.min(0.05f, Math.min(hu, hv) * 0.3f);
        GLES20.glDepthMask(true);
        GLES20.glLineWidth(3f * height / 1000f);
        switch (k) {
            case "table": case "desk": {
                cbox(m, b, -hu, hu, -hv, hv, y1 - 0.05f, y1, col);
                for (int i = 0; i < 4; i++) {
                    float su = (i & 1) == 0 ? -1 : 1, sv = (i & 2) == 0 ? -1 : 1;
                    float u = su * (hu - leg * 1.5f), v = sv * (hv - leg * 1.5f);
                    cbox(m, b, u - leg / 2, u + leg / 2, v - leg / 2, v + leg / 2, y0, y1 - 0.05f, col);
                }
                break;
            }
            case "chair": case "stool": {
                float seat = y0 + Math.min(0.48f, h * (k.equals("stool") ? 0.92f : 0.55f));
                cbox(m, b, -hu, hu, -hv, hv, seat - 0.05f, seat, col);
                for (int i = 0; i < 4; i++) {
                    float su = (i & 1) == 0 ? -1 : 1, sv = (i & 2) == 0 ? -1 : 1;
                    float u = su * (hu - leg), v = sv * (hv - leg);
                    cbox(m, b, u - leg / 2, u + leg / 2, v - leg / 2, v + leg / 2, y0, seat - 0.05f, col);
                }
                if (k.equals("chair") && y1 > seat + 0.12f) cbox(m, b, -hu, hu, -hv, -hv + 0.05f, seat, y1, col);
                break;
            }
            case "bed": case "sofa": {
                float base = y0 + Math.max(0.15f, h * 0.55f);
                cbox(m, b, -hu, hu, -hv, hv, y0, base, col);
                if (k.equals("sofa")) {
                    cbox(m, b, -hu, hu, -hv, -hv + Math.min(0.2f, hv * 0.4f), base, y1, col);
                    cbox(m, b, -hu, -hu + 0.12f, -hv, hv, base, base + (y1 - base) * 0.5f, col);
                    cbox(m, b, hu - 0.12f, hu, -hv, hv, base, base + (y1 - base) * 0.5f, col);
                } else {
                    cbox(m, b, -hu, -hu + 0.06f, -hv, hv, base, Math.max(y1, base + 0.25f), col); // headboard
                    cbox(m, b, -hu + 0.1f, -hu + 0.45f, -hv * 0.8f, hv * 0.8f, base, base + 0.08f, new float[]{1f, 1f, 1f, 1f}); // pillow
                }
                break;
            }
            case "shoe": {
                cbox(m, b, -hu, hu, -hv, hv, y0, y0 + h * 0.45f, col);
                cbox(m, b, -hu, hu * 0.1f, -hv * 0.85f, hv * 0.85f, y0 + h * 0.45f, y1, col);
                break;
            }
            case "bottle": case "can": case "cup": case "vase": case "lamp": case "plant": case "pole": {
                RoomModel.Box c = new RoomModel.Box();
                c.cx = b.cx; c.cz = b.cz; c.hu = c.hv = Math.max(0.02f, Math.min(hu, hv)); c.cyl = true; c.y0 = y0; c.y1 = y1;
                c.cosA = 1; c.sinA = 0;
                if (k.equals("plant")) {
                    c.y1 = y0 + h * 0.35f; planDraw(m, objTriangles(c), GLES20.GL_TRIANGLES, new float[]{0.75f, 0.45f, 0.30f, 1f}, 1);
                    c.y0 = c.y1; c.y1 = y1; c.hu = c.hv = Math.max(hu, hv);
                }
                planDraw(m, objTriangles(c), GLES20.GL_TRIANGLES, col, 1);
                planDraw(m, objEdges(c), GLES20.GL_LINES, OUTLINE, 1);
                break;
            }
            case "shelf": case "cabinet": case "wardrobe": {
                cbox(m, b, -hu, hu, -hv, hv, y0, y1, col);
                if (!k.equals("wardrobe")) for (int i = 1; i < 4; i++) {
                    float yy = y0 + h * i / 4f;
                    cbox(m, b, -hu - 0.005f, hu + 0.005f, -hv - 0.005f, hv + 0.005f, yy - 0.012f, yy + 0.012f, OUTLINE);
                }
                break;
            }
            default: {
                if (b.cyl) { planDraw(m, objTriangles(b), GLES20.GL_TRIANGLES, col, 1); planDraw(m, objEdges(b), GLES20.GL_LINES, OUTLINE, 1); }
                else cbox(m, b, -hu, hu, -hv, hv, y0, y1, col);
            }
        }
        GLES20.glDepthMask(false);
    }

    /** Same rule as the shaders' dollhouse cut, for whole objects. */
    private boolean cutHides(RoomModel.Box b) {
        if (!cutActive) return false;
        if (b.y1 > cutCeil + 0.2f) return false;
        float rx = b.cx - roomCenter[0], rz = b.cz - roomCenter[2];
        return b.y0 > cutFloor + 0.15f && (rx * cutDirX + rz * cutDirZ > cutD || rx * cutDir2X + rz * cutDir2Z > cutD2);
    }

    private static float[] objTriangles(RoomModel.Box b) {
        int n = b.cyl ? 16 : 4;
        float[] out = new float[n * 9 + n * 18];
        int k = 0;
        for (int i = 0; i < n; i++) {
            float[] p = b.rim(i, n), q = b.rim((i + 1) % n, n);
            // top fan
            out[k++] = b.cx; out[k++] = b.y1; out[k++] = b.cz;
            out[k++] = p[0]; out[k++] = b.y1; out[k++] = p[1];
            out[k++] = q[0]; out[k++] = b.y1; out[k++] = q[1];
            // side
            float[] side = {p[0], b.y0, p[1], q[0], b.y0, q[1], q[0], b.y1, q[1], p[0], b.y0, p[1], q[0], b.y1, q[1], p[0], b.y1, p[1]};
            System.arraycopy(side, 0, out, k, 18); k += 18;
        }
        return out;
    }

    private static float[] objEdges(RoomModel.Box b) {
        int n = b.cyl ? 16 : 4, verticals = 4;
        float[] out = new float[n * 12 + verticals * 6];
        int k = 0;
        for (int i = 0; i < n; i++) {
            float[] p = b.rim(i, n), q = b.rim((i + 1) % n, n);
            for (float y : new float[]{b.y0, b.y1}) {
                out[k++] = p[0]; out[k++] = y; out[k++] = p[1];
                out[k++] = q[0]; out[k++] = y; out[k++] = q[1];
            }
        }
        for (int i = 0; i < verticals; i++) {
            float[] p = b.rim(b.cyl ? i * n / 4 : i, n);
            out[k++] = p[0]; out[k++] = b.y0; out[k++] = p[1];
            out[k++] = p[0]; out[k++] = b.y1; out[k++] = p[1];
        }
        return out;
    }

    // ---- name tags: text drawn into small textures, shown as screen-facing quads ----
    private int labelProg;
    private final java.util.HashMap<String, int[]> labelTex = new java.util.HashMap<>();
    private final float[] labelClip = new float[4];
    private static final float[] QUAD = {-1, -1, 1, -1, 1, 1, -1, -1, 1, 1, -1, 1};
    private FloatBuffer quadBuf;

    private int[] labelTexture(String text, boolean item) {
        String key = (item ? "1" : "0") + text;
        int[] t = labelTex.get(key);
        if (t != null) return t;
        if (labelTex.size() > 300) {
            for (int[] v : labelTex.values()) GLES20.glDeleteTextures(1, new int[]{v[0]}, 0);
            labelTex.clear();
        }
        float sp = activity.getResources().getDisplayMetrics().scaledDensity;
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(13 * sp);
        paint.setColor(0xFFFFFFFF);
        int pad = (int) (5 * sp);
        int w = (int) Math.ceil(paint.measureText(text)) + 2 * pad, h = (int) Math.ceil(paint.getFontSpacing()) + pad;
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
        android.graphics.Paint bg = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        bg.setColor(item ? 0xD0105A78 : 0xD0804010);
        cv.drawRoundRect(0, 0, w, h, h / 2f, h / 2f, bg);
        cv.drawText(text, pad, h / 2f - (paint.ascent() + paint.descent()) / 2f, paint);
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        bmp.recycle();
        t = new int[]{id[0], w, h};
        labelTex.put(key, t);
        return t;
    }

    private void drawLabel(float[] m, float x, float y, float z, String text, boolean item) {
        Matrix.multiplyMV(labelClip, 0, m, 0, new float[]{x, y, z, 1}, 0);
        if (labelClip[3] < 0.05f) return; // behind the camera
        if (labelProg == 0) {
            labelProg = program(
                    "uniform mat4 uMvp; uniform vec3 uAnchor; uniform vec2 uHalf; attribute vec2 aCorner; varying vec2 vUv;" +
                    "void main(){ vec4 p = uMvp * vec4(uAnchor, 1.0); p.xy += (aCorner + vec2(0.0, 1.0)) * uHalf * p.w;" +
                    "  gl_Position = p; vUv = vec2((aCorner.x + 1.0) * 0.5, (1.0 - aCorner.y) * 0.5); }",
                    "precision mediump float; varying vec2 vUv; uniform sampler2D uTex;" +
                    "void main(){ gl_FragColor = texture2D(uTex, vUv); }");
            quadBuf = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quadBuf.put(QUAD).position(0);
        }
        int[] t = labelTexture(text, item);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA); // bitmaps are premultiplied
        GLES20.glUseProgram(labelProg);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(labelProg, "uMvp"), 1, false, m, 0);
        GLES20.glUniform3f(GLES20.glGetUniformLocation(labelProg, "uAnchor"), x, y, z);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(labelProg, "uHalf"), (float) t[1] / width, (float) t[2] / height);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(labelProg, "uTex"), 0);
        int a = GLES20.glGetAttribLocation(labelProg, "aCorner");
        quadBuf.position(0);
        GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, quadBuf);
        GLES20.glEnableVertexAttribArray(a);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
        GLES20.glDisableVertexAttribArray(a);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
    }

    /** Planner, room model, layout proxies and photo texturing (re-run when the user enters a known room size). */
    private void analyseRoom() {
        java.util.List<RoomModel.PlaneData> planes = lastPlanes;
        room.knownH = knownH; room.knownW = knownW; room.knownD = knownD;
        synchronized (marks) {
            java.util.List<float[]> poly = roomPolygon();
            room.userCorners = poly;
            room.userFloorY = markFloor != null ? markFloor[1] : Float.NaN;
            room.userCeilingY = markCeiling != null ? markCeiling[1] : Float.NaN;
            if (poly != null) log("room from your taps: " + poly.size() + " corners" + (cornerList.size() >= 3 ? "" : " (from " + wallList.size() + " walls)"));
        }
        planner.analyse(pos, count);
        subjectPick = planner.isReady() ? planner.defaultSubject() : new float[]{target[0], target[1], target[2]};
        sceneIdx = 0; plan = null;
        log(planner.summary());
        log("ARCore planes: " + planes.size());
        roomReady = false;
        if (planner.isReady()) {
            try {
                room.build(planes, planner, pos, col, count);
                applyLabels(room.boxes);
                claudeObjs.mergeInto(room.boxes, room.floorY);
                roomReady = true;
                buildFloorMesh();
                log(room.logLine());
            } catch (Exception e) { logError("room", e); }
        }
        buildPhotos();
        try {
            java.util.List<ObjectNamer.Job> jobs = claudeObjs.has() ? new java.util.ArrayList<>() : ObjectNamer.prepare(room, photos, width, height); // Claude already named everything
            if (!jobs.isEmpty()) {
                log("naming " + jobs.size() + " furniture blocks from photos");
                ObjectNamer.label(jobs, () -> {
                    StringBuilder b = new StringBuilder();
                    for (RoomModel.Box bx : room.boxes) if (bx.name != null) b.append(b.length() > 0 ? ", " : "").append(bx.name);
                    log("objects: " + (b.length() > 0 ? b : "none recognised"));
                    finishStatus();
                });
            }
        } catch (Throwable e) { logError("objects", new RuntimeException(e)); }
    }

    private FloatBuffer meshPosAll; private ByteBuffer meshColAll; private int meshVerts;

    /** Room mesh with the detail zones cut out, plus the fine mesh inside them. */
    private void mergeDetail() {
        java.util.ArrayList<Integer> keepC = new java.util.ArrayList<>(), keepD = new java.util.ArrayList<>();
        for (int t = 0; t < tsdf.meshVerts / 3; t++) {
            float x = 0, y = 0, z = 0;
            for (int v = 0; v < 3; v++) { x += tsdf.meshPos.get(t * 9 + v * 3); y += tsdf.meshPos.get(t * 9 + v * 3 + 1); z += tsdf.meshPos.get(t * 9 + v * 3 + 2); }
            if (!inZoneScaled(x / 3, y / 3, z / 3, 0.9f) || !hasDetailNear(x / 3, y / 3, z / 3)) keepC.add(t);
        }
        for (int t = 0; t < detail.meshVerts / 3; t++) {
            float x = 0, y = 0, z = 0;
            for (int v = 0; v < 3; v++) { x += detail.meshPos.get(t * 9 + v * 3); y += detail.meshPos.get(t * 9 + v * 3 + 1); z += detail.meshPos.get(t * 9 + v * 3 + 2); }
            if (inZoneScaled(x / 3, y / 3, z / 3, 1f)) keepD.add(t);
        }
        int n = keepC.size() + keepD.size();
        meshPosAll = ByteBuffer.allocateDirect(Math.max(1, n * 9) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        meshColAll = ByteBuffer.allocateDirect(Math.max(1, n * 12)).order(ByteOrder.nativeOrder());
        for (int t : keepC) { for (int i = 0; i < 9; i++) meshPosAll.put(tsdf.meshPos.get(t * 9 + i)); for (int i = 0; i < 12; i++) meshColAll.put(tsdf.meshCol.get(t * 12 + i)); }
        for (int t : keepD) { for (int i = 0; i < 9; i++) meshPosAll.put(detail.meshPos.get(t * 9 + i)); for (int i = 0; i < 12; i++) meshColAll.put(detail.meshCol.get(t * 12 + i)); }
        meshPosAll.position(0); meshColAll.position(0);
        meshVerts = n * 3;
        meshTris = n;
    }

    private boolean inZoneScaled(float x, float y, float z, float k) {
        for (float[] q : zones) {
            float dx = x - q[0], dy = y - q[1], dz = z - q[2], r = q[3] * k;
            if (dx * dx + dy * dy + dz * dz < r * r) return true;
        }
        return false;
    }

    /** Only cut the room mesh where the fine scan actually produced something (so an unfinished zone isn't a hole). */
    private boolean hasDetailNear(float x, float y, float z) { return detail.meshVerts > 300; }

    /**
     * Ask Claude (GL thread part): grab a few room photos and a crop per object, then call Claude in the background.
     * Names come back into the objects; done() gets the room advice.
     */
    void askClaude(String apiKey, java.util.function.Consumer<ClaudeHelper.Result> done, java.util.function.Consumer<String> fail) {
        if (!roomReady) { fail.accept("Finish a scan first (no room found yet)."); return; }
        final java.util.List<String> photosB64 = new java.util.ArrayList<>(), crops = new java.util.ArrayList<>(), info = new java.util.ArrayList<>();
        final java.util.List<RoomModel.Box> asked = new java.util.ArrayList<>();
        try {
            java.util.List<byte[]> jpgs = ScanExport.readKeyframes(photos, width, height);
            int want = Math.min(4, jpgs.size());
            for (int i = 0; i < want; i++) photosB64.add(ClaudeHelper.jpegBase64(jpgs.get(i * jpgs.size() / want)));
            java.util.List<ObjectNamer.Job> jobs = claudeObjs.has() ? new java.util.ArrayList<>() : ObjectNamer.prepare(room, photos, width, height); // Claude already named everything
            jobs.sort((a, b) -> b.box.voxels - a.box.voxels); // biggest first, at most 16
            for (ObjectNamer.Job j : jobs) {
                if (asked.size() < 16) {
                    RoomModel.Box b = j.box;
                    crops.add(ClaudeHelper.jpegBase64(j.crop, 512));
                    info.add(String.format(java.util.Locale.US, "%.2f x %.2f m footprint, %.2f m tall, %s; on-device guess: %s",
                            2 * b.hu, 2 * b.hv, b.y1 - b.y0, b.where().isEmpty() ? "on the floor" : b.where(), b.title()));
                    asked.add(b);
                }
                j.crop.recycle();
            }
        } catch (Throwable e) { fail.accept("Couldn't read the scan photos: " + e); return; }
        final String summary = room.summary.replace('\n', ' ');
        new Thread(() -> {
            try {
                ClaudeHelper.Result r = ClaudeHelper.ask(apiKey, summary, photosB64, crops, info);
                for (int i = 0; i < asked.size(); i++) {
                    RoomModel.Box b = asked.get(i);
                    if (r.names.get(i) != null) b.name = r.names.get(i);
                    if (r.kinds.get(i) != null) b.kind = r.kinds.get(i);
                    remember(b);
                }
                claudeAdvice = (r.room.isEmpty() ? "" : "🤖 " + r.room + "\n") + (r.lighting.isEmpty() ? "" : "💡 " + r.lighting + "\n")
                        + (r.shots.isEmpty() ? "" : "🎬 " + String.join(" · ", r.shots) + "\n");
                log("Claude: " + r.room + " | " + r.lighting + " | " + r.shots);
                finishStatus();
                done.accept(r);
            } catch (Throwable e) {
                logError("claude", new RuntimeException(e));
                String m = String.valueOf(e.getMessage());
                fail.accept(m.contains("401") || m.toLowerCase(java.util.Locale.US).contains("authentication")
                        ? "The API key was rejected. Long-press Ask Claude to enter it again." : "Couldn't reach Claude: " + m);
            }
        }).start();
    }

    // ---- live Claude: labels what's in view every few seconds while scanning ----
    volatile String claudeLiveKey; // set when the user turns live Claude on
    private volatile boolean claudeBusy;
    private long claudeLastMs;
    private PhotoRoom.Keyframe claudeLastKf;
    private volatile String claudeLine, claudeAdvice;
    private int claudeCalls;

    /** What Claude said is at a spot; applied to whatever object the scan finds there later. */
    private static final class Label { float x, z, y0, y1, r; String name, kind; }
    private final java.util.List<Label> labels = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private void remember(RoomModel.Box b) {
        if (b.kind == null && b.name == null) return;
        Label l = new Label();
        l.x = b.cx; l.z = b.cz; l.y0 = b.y0; l.y1 = b.y1; l.r = Math.max(b.hu, b.hv); l.name = b.name; l.kind = b.kind;
        synchronized (labels) {
            for (int i = labels.size() - 1; i >= 0; i--) {
                Label o = labels.get(i);
                if (Math.hypot(o.x - l.x, o.z - l.z) < Math.max(0.15f, 0.5f * Math.min(o.r, l.r)) && Math.abs(o.y0 - l.y0) < 0.25f) labels.remove(i);
            }
            labels.add(l);
        }
    }

    /** Give objects the newest label found at their spot. */
    private void applyLabels(java.util.List<RoomModel.Box> boxes) {
        synchronized (labels) {
            if (labels.isEmpty()) return;
            for (RoomModel.Box b : boxes) {
                Label best = null; float bd = 1e9f;
                for (Label l : labels) {
                    float d = (float) Math.hypot(l.x - b.cx, l.z - b.cz);
                    float lim = Math.max(0.2f, 0.6f * Math.max(l.r, Math.max(b.hu, b.hv)));
                    if (d < lim && Math.abs(l.y0 - b.y0) < 0.3f && d < bd) { bd = d; best = l; }
                }
                if (best != null) { if (best.name != null) b.name = best.name; b.kind = best.kind; }
            }
        }
    }

    final ClaudeObjects claudeObjs = new ClaudeObjects();

    private void maybeAskClaudeLive(long nowMs) {
        String key = claudeLiveKey;
        if (key == null) return;
        RoomModel r = liveRoom;
        PhotoRoom.Keyframe k = photos.last;
        if (claudeLine == null) claudeLine = "🤖 Claude: waiting for the first photo";
        if (claudeBusy || r == null || k == null || nowMs - claudeLastMs < 7000) return;
        if (k == claudeLastKf && nowMs - claudeLastMs < 20000) return; // nothing new to show it
        claudeLastMs = nowMs; claudeLastKf = k; claudeBusy = true;
        final String b64; final ClaudeObjects.Snapshot snap = new ClaudeObjects.Snapshot();
        try {
            android.graphics.Matrix map = new android.graphics.Matrix();
            android.graphics.Bitmap img = ObjectNamer.readUpright(k, width, height, map);
            float iw = img.getWidth(), ih = img.getHeight();
            b64 = ClaudeHelper.jpegBase64(img, 1024);
            img.recycle();
            // project the scan points into this photo (upright 0..1 coordinates)
            float[] q = {0, 0, 1, 0};
            map.mapPoints(q);
            boolean swap = Math.abs(q[2] - q[0]) < Math.abs(q[3] - q[1]); // texture u runs along the photo's height
            snap.radX = swap ? k.h / k.fy : k.w / k.fx; snap.radY = swap ? k.w / k.fx : k.h / k.fy;
            snap.floorY = r.floorY; snap.camX = k.px; snap.camZ = k.pz;
            snap.view = k.view.clone(); snap.fx = k.fx; snap.fy = k.fy; snap.cx = k.cx; snap.cy = k.cy; snap.w = k.w; snap.h = k.h;
            snap.iw = iw; snap.ih = ih;
            android.graphics.Matrix inv = new android.graphics.Matrix();
            map.invert(inv);
            float[] mv = new float[9]; inv.getValues(mv);
            snap.inv[0] = mv[0]; snap.inv[1] = mv[1]; snap.inv[2] = mv[2]; snap.inv[3] = mv[3]; snap.inv[4] = mv[4]; snap.inv[5] = mv[5];
            int n = count, step = Math.max(1, n / 60000), cap = n / step + 1;
            snap.u = new float[cap]; snap.v = new float[cap]; snap.d = new float[cap]; snap.x = new float[cap]; snap.y = new float[cap]; snap.z = new float[cap];
            float[] vm = k.view, pt = new float[2];
            int j = 0;
            for (int i = 0; i < n; i += step) {
                float x = pos.get(i * 3), y = pos.get(i * 3 + 1), z = pos.get(i * 3 + 2);
                float cx = vm[0] * x + vm[4] * y + vm[8] * z + vm[12], cy = vm[1] * x + vm[5] * y + vm[9] * z + vm[13], cz = vm[2] * x + vm[6] * y + vm[10] * z + vm[14];
                float d = -cz;
                if (d < 0.15f || d > 6f) continue;
                pt[0] = (k.fx * cx / d + k.cx) / k.w; pt[1] = (k.cy - k.fy * cy / d) / k.h;
                if (pt[0] < 0 || pt[0] > 1 || pt[1] < 0 || pt[1] > 1) continue;
                map.mapPoints(pt);
                snap.u[j] = pt[0] / iw; snap.v[j] = pt[1] / ih; snap.d[j] = d; snap.x[j] = x; snap.y[j] = y; snap.z[j] = z; j++;
            }
            snap.n = j;
        } catch (Throwable e) { claudeBusy = false; logError("claude live photo", new RuntimeException(e)); return; }
        claudeLine = "🤖 Claude is looking...";
        new Thread(() -> {
            try {
                java.util.List<ClaudeHelper.Det> dets = ClaudeHelper.detectLive(key, b64);
                StringBuilder seen = new StringBuilder(), placed = new StringBuilder();
                for (ClaudeHelper.Det d : dets) {
                    seen.append(seen.length() > 0 ? ", " : "").append(d.name);
                    RoomModel.Box b = ClaudeObjects.lift(snap, d);
                    if (b == null) continue;
                    claudeObjs.add(b, ClaudeObjects.sighting(snap, d), snap.floorY);
                    placed.append(placed.length() > 0 ? ", " : "").append(String.format(java.util.Locale.US, "%s %.2fx%.2fx%.2f at (%.2f, %.2f, %.2f) %s", d.name, 2 * b.hu, 2 * b.hv, b.y1 - b.y0, b.cx, b.y0, b.cz, b.rel));
                }
                claudeCalls++;
                claudeLine = "🤖 Claude sees: " + (seen.length() > 0 ? seen : "nothing it can name") + (claudeObjs.carved > 0 ? "\n📐 " + claudeObjs.carved + " measured from several angles (walk around things to measure more)" : "") + (dets.size() > 0 && placed.length() == 0 ? " (not scanned enough yet to place)" : "");
                log("claude live " + claudeCalls + ": " + dets.size() + " found, placed " + (placed.length() > 0 ? placed : "none") + " (" + snap.n + " points in view) " + ClaudeHelper.costLine());
            } catch (Throwable e) {
                String m = String.valueOf(e.getMessage());
                claudeLine = m.contains("401") ? "🤖 Claude: API key rejected (long-press Ask Claude to change it)" : "🤖 Claude: " + m;
                logError("claude live", new RuntimeException(e));
            }
            claudeBusy = false;
        }).start();
    }

    /** Export the whole scan as a zip (GL thread part), then write it in the background and hand back a share uri. */
    void exportScan(java.util.function.Consumer<android.net.Uri> done, java.util.function.Consumer<String> fail) {
        final java.util.List<byte[]> jpgs;
        try { jpgs = ScanExport.readKeyframes(photos, width, height); }
        catch (Throwable e) { fail.accept("photo readback failed: " + e); return; }
        final String log = report();
        final int n = count;
        final String name = "cinex-scan-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date()) + ".zip";
        new Thread(() -> {
            try { ScanExport.claudeJson = claudeObjs.has() ? claudeObjs.toJson() : null; done.accept(ScanExport.write(activity, name, recs, photos, jpgs, pos, col, n, log, poseLog, sensors)); }
            catch (Throwable e) { fail.accept(String.valueOf(e)); }
        }).start();
    }

    int recordedFrames() { return recs.size(); }

    /** Screen rotation relative to the phone's natural (portrait) orientation; the app is locked to landscape. */
    private int displayRotation() {
        try { return activity.getWindowManager().getDefaultDisplay().getRotation(); } catch (Throwable e) { return 1; }
    }

    void reset() {
        count = 0; uploaded = 0; pathCount = 0; lastPx = 1e9f;
        depthRunning = false; cleaning = false; glTasks.clear();
        surfGen++; surfDone = null; surfBase = null; surfPending = null; surfCount = 0; training = false; trainer = null; trainViews = null;
        depthFrames = depthPixels = depthRejectedRange = depthRejectedConf = depthRejectedEdge = depthNotYet = 0; recoveries = 0; lastError = "";
        voxels.clear();
        refined = false; refineCursor = 0; refinedTotal = 0; droppedNoise = 0;
        tsdf.clear(); detail.clear(); zones.clear(); meshPosAll = null; meshColAll = null; meshVerts = 0; photos.clear(); meshTris = 0; havePrevPose = false; recs.clear(); poseLog.clear(); hideN = 0; labels.clear(); claudeObjs.clear(); synchronized (marks) { cornerList.clear(); wallList.clear(); markFloor = null; markCeiling = null; markOrder.clear(); } claudeLine = null; claudeAdvice = null; claudeLastKf = null; guideTarget = null; guideText = null; poseJumps = 0; viewSides.clear(); hintBox = null; hintText = null; sensors.clear(); liveRoom = null; liveLastMs = 0; rescanAt = null; pendingTap = null;
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
    private float cutDirX, cutDirZ, cutD, cutFloor, cutCeil, cutDir2X, cutDir2Z, cutD2 = 1e9f;

    boolean toggleCutaway() { cutaway = !cutaway; return cutaway; }

    /** Work out which wall faces the viewer (eye position) and what to hide. */
    private void updateCutaway(float ex, float ez, boolean top) {
        cutActive = viewing && cutaway;
        if (!cutActive) return;
        cutFloor = roomReady ? room.floorY : planner.isReady() ? planner.floorY : boxMinY;
        float roomH = boxMaxY - cutFloor;
        cutCeil = roomH > 1.9f ? boxMaxY - 0.3f : 1e9f;
        cutD2 = 1e9f;
        if (roomReady && room.hasLayout && room.ceilingY > cutFloor + 1.9f) cutCeil = room.ceilingY - 0.25f;
        if (top) { cutDirX = 0; cutDirZ = 0; cutD = 1e9f; return; }
        if (roomReady && room.hasLayout && room.layout.size() >= 3) {
            // Dollhouse view: cut away the (up to two) fitted walls that face the viewer, plus 35 cm inside them,
            // so you look into the room instead of at its outside.
            float best1 = 0, best2 = 0; RoomModel.Wall w1 = null, w2 = null;
            for (RoomModel.Wall w : room.layout) {
                float mx = (w.x0 + w.x1) / 2, mz = (w.z0 + w.z1) / 2;
                float facing = w.nx * (ex - mx) + w.nz * (ez - mz);
                if (facing <= 0) continue;
                float score = facing / (float) Math.sqrt((ex - mx) * (ex - mx) + (ez - mz) * (ez - mz) + 1e-6f);
                if (score > best1) { best2 = best1; w2 = w1; best1 = score; w1 = w; }
                else if (score > best2) { best2 = score; w2 = w; }
            }
            cutDirX = 0; cutDirZ = 0; cutD = 1e9f;
            if (w1 != null) { cutDirX = w1.nx; cutDirZ = w1.nz; cutD = wallCut(w1); }
            if (w2 != null && best2 > 0.25f) { cutDir2X = w2.nx; cutDir2Z = w2.nz; cutD2 = wallCut(w2); }
            return;
        }
        float dx = ex - roomCenter[0], dz = ez - roomCenter[2];
        float l = (float) Math.sqrt(dx * dx + dz * dz);
        if (l < 1e-3f) { cutDirX = 0; cutDirZ = 0; cutD = 1e9f; return; }
        cutDirX = dx / l; cutDirZ = dz / l;
        float halfExt = Math.abs(cutDirX) * boxHalfX + Math.abs(cutDirZ) * boxHalfZ;
        cutD = Math.max(0.3f, halfExt - 0.6f); // drop the outer 60 cm band on the viewer's side
    }

    /** Distance (from the room centre along the wall's outward normal) beyond which the wall gets hidden. */
    private float wallCut(RoomModel.Wall w) {
        float mx = (w.x0 + w.x1) / 2 - roomCenter[0], mz = (w.z0 + w.z1) / 2 - roomCenter[2];
        return mx * w.nx + mz * w.nz - 0.35f;
    }

    boolean toggleTopView() { topView = !topView; return topView; }

    void orbit(float dx, float dy) {
        yaw -= dx * 0.008f;
        pitch = clamp(pitch + dy * 0.008f, -1.5f, 1.55f);
    }

    void zoom(float f) { dist = clamp(dist / f, 0.05f, 100f); }

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
        labelTex.clear(); labelProg = 0; // old GL objects are gone with the old context
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
        photos.onSurfaceCreated();

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
                "uniform float uCut; uniform vec3 uCenter; uniform vec2 uDir; uniform float uCutD; uniform vec2 uDir2; uniform float uCutD2; uniform float uFloor; uniform float uCeil;" +
                HIDE_DECL +
                "void main(){ float hh = 0.0;" + HIDE_TEST.replace("HIDE", "hh = 1.0;") +
                "  if (hh > 0.5) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); gl_PointSize = 1.0; vColor = aColor; return; }" +
                "  if (uCut > 0.5) { vec2 r = aPos.xz - uCenter.xz;" +
                "    if (((dot(r, uDir) > uCutD || dot(r, uDir2) > uCutD2) && aPos.y > uFloor + 0.15) || aPos.y > uCeil) {" +
                "      gl_Position = vec4(2.0, 2.0, 2.0, 1.0); gl_PointSize = 1.0; vColor = aColor; return; } }" +
                // splats shrink in world size when you get close (w < 1.5 m), so near surfaces stay detailed instead of blobby
                "  gl_Position = uMvp * aPos; float w = max(gl_Position.w, 0.05);" +
                "  gl_PointSize = clamp(uSize / w * clamp(w / 1.5, 0.3, 1.0), 1.5, 48.0); vColor = aColor; }",
                // uPass: 0 = splat core (opaque, writes depth), 1 = splat halo (soft gaussian, blended), 2 = plain
                "precision mediump float; varying vec4 vColor; uniform float uPass;" +
                "void main(){" +
                "  if (uPass > 2.5) { vec2 q = gl_PointCoord * 2.0 - 1.0; if (dot(q, q) > 1.0) discard;" +
                "    gl_FragColor = vec4(0.0); return; }" +
                "  if (uPass > 1.5) { gl_FragColor = vColor; return; }" +
                "  vec2 d = gl_PointCoord * 2.0 - 1.0; float r2 = dot(d, d);" +
                "  if (r2 > 1.0) discard;" +
                "  float a = exp(-r2 * 3.5);" +
                "  if (uPass < 0.5) { if (a < 0.6) discard; gl_FragColor = vec4(vColor.rgb, 1.0); }" +
                "  else { gl_FragColor = vec4(vColor.rgb * a, a); }" +
                "}");

        surfProg = program(
                "uniform mat4 uMvp; uniform mat4 uView; uniform float uPx; uniform float uScale; uniform float uT; attribute vec3 aRaw;" +
                "attribute vec4 aPos; attribute vec4 aColor; attribute vec4 aNrm; varying vec4 vColor; varying vec3 vN;" + LIGHT_GLSL +
                "uniform float uCut; uniform vec3 uCenter; uniform vec2 uDir; uniform float uCutD; uniform vec2 uDir2; uniform float uCutD2; uniform float uFloor; uniform float uCeil;" +
                HIDE_DECL +
                "void main(){ float hh = 0.0;" + HIDE_TEST.replace("HIDE", "hh = 1.0;") +
                "  vColor = aColor; vN = vec3(0.0);" +
                "  if (hh > 0.5) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); gl_PointSize = 1.0; return; }" +
                "  if (uCut > 0.5) { vec2 r = aPos.xz - uCenter.xz;" +
                "    if (((dot(r, uDir) > uCutD || dot(r, uDir2) > uCutD2) && aPos.y > uFloor + 0.15) || aPos.y > uCeil) {" +
                "      gl_Position = vec4(2.0, 2.0, 2.0, 1.0); gl_PointSize = 1.0; return; } }" +
                // uT 0 -> 1: the splats settle from the raw noisy points onto their surfaces and grow into discs (shown live)
                "  vec3 p = mix(aRaw, aPos.xyz, uT); gl_Position = uMvp * vec4(p, 1.0); float w = max(gl_Position.w, 0.05);" +
                "  gl_PointSize = clamp(2.0 * aNrm.w * uScale * mix(0.45, 1.0, uT) * uPx / w, 1.5, 96.0);" +
                "  vN = (uView * vec4(aNrm.xyz, 0.0)).xyz;" +
                "  if (uLit > 0.5) { vec3 lin = pow(aColor.rgb, vec3(2.2)); float hasN = step(0.25, dot(aNrm.xyz, aNrm.xyz));" +
                "    vec3 E = vec3(uRoom) + gear(p, aNrm.xyz, hasN, 1.0);" +
                "    if (dot(lin, vec3(0.3, 0.59, 0.11)) > 0.8) E = vec3(uRoom);" + // lamps and windows: they glow, they aren't lit
                "    vColor.rgb = tone(lin * E * uExpo); } }",
                // Surfel: the point sprite becomes a disc lying on the surface (an ellipse on screen), with a gaussian falloff.
                // uPass 0 = opaque core (writes depth), 1 = soft halo blended on top.
                "precision mediump float; varying vec4 vColor; varying vec3 vN; uniform float uPass; uniform float uTrained;" +
                "void main(){ vec2 d = gl_PointCoord * 2.0 - 1.0; d.y = -d.y; float dz = 0.0;" +
                "  if (dot(vN, vN) > 0.25) { float nz = vN.z; if (abs(nz) < 0.25) nz = nz < 0.0 ? -0.25 : 0.25;" +
                "    dz = -(vN.x * d.x + vN.y * d.y) / nz; }" +
                "  float r2 = dot(d, d) + dz * dz; if (r2 > 1.0) discard;" +
                "  if (uTrained > 0.5) { float ta = vColor.a * exp(-r2 * 4.5);" + // the same gaussian the trainer fitted
                "    if (uPass < 0.5) { if (ta < 0.5) discard; gl_FragColor = vec4(vColor.rgb, 1.0); }" +
                "    else { gl_FragColor = vec4(vColor.rgb * ta, ta); } return; }" +
                "  if (uPass < 0.5) { if (r2 > 0.45) discard; gl_FragColor = vec4(vColor.rgb, 1.0); }" +
                "  else { float a = exp(-r2 * 2.5) * 0.85; gl_FragColor = vec4(vColor.rgb * a, a); }" +
                "}");
        propProg = program(
                "uniform mat4 uMvp; uniform vec3 uAlb; attribute vec3 aPos; attribute vec3 aN; varying vec3 vC;" + LIGHT_GLSL +
                "void main(){ gl_Position = uMvp * vec4(aPos, 1.0);" +
                "  vec3 E = vec3(uRoom * 0.7) + gear(aPos, aN, 1.0, 0.0);" +
                "  vC = tone(pow(uAlb, vec3(2.2)) * E * uExpo); }",
                "precision mediump float; varying vec3 vC; void main(){ gl_FragColor = vec4(vC, 1.0); }");
        int[] sb = new int[4];
        GLES20.glGenBuffers(4, sb, 0);
        surfPosVbo = sb[0]; surfNrmVbo = sb[1]; surfColVbo = sb[2]; surfRawVbo = sb[3]; surfCount = 0;
        if (surfDone != null) surfPending = surfDone; // GL context was recreated: upload again

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
        if (session != null) session.setDisplayGeometry(displayRotation(), w, h);
        displaySet = session != null;
    }

    private boolean displaySet;

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        if (idle) { for (Runnable r; (r = glTasks.poll()) != null; ) r.run(); return; }
        if (viewing) { drawViewer(); return; }
        if (session == null) return;
        if (!displaySet) { session.setDisplayGeometry(displayRotation(), width, height); displaySet = true; }
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
        sensors.active = !viewing;
        if (!viewing && frame.getTimestamp() != 0) {
            com.google.ar.core.Pose cp = camera.getPose();
            cp.getTranslation(poseTq, 0); cp.getRotationQuaternion(poseTq, 3);
            poseLog.add(frame.getTimestamp(), poseTq, ts.ordinal(), camera.getTrackingFailureReason().ordinal());
        }
        if (ts != lastTs) {
            log("tracking " + lastTs + " -> " + ts + " (reason " + camera.getTrackingFailureReason() + ", frame " + frameNo + ")");
            lastTs = ts;
        }
        if (ts == TrackingState.TRACKING) {
            everTracked = true;
            notTrackingSince = 0;
            lostSince = 0;
        } else if (everTracked) {
            // Lost tracking after it worked. ARCore usually recovers by itself once it sees something textured again;
            // restarting only helps the S24's "paused for no reason" case, and a restart can shift the whole map.
            if (lostSince == 0) lostSince = nowMs;
            else if (camera.getTrackingFailureReason() == com.google.ar.core.TrackingFailureReason.NONE
                    && nowMs - lostSince > 3000 && recoveries < 10) {
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
        if (ts == TrackingState.TRACKING && lastTrackState != TrackingState.TRACKING) settleUntil = nowMs + 1500;
        lastTrackState = ts;
        if (ts == TrackingState.TRACKING) {
            camera.getPose().toMatrix(curPose, 0);
            if (havePrevPose) {
                float dx = curPose[12] - prevPose[12], dy = curPose[13] - prevPose[13], dz = curPose[14] - prevPose[14];
                frameMove = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                float dot = curPose[8] * prevPose[8] + curPose[9] * prevPose[9] + curPose[10] * prevPose[10];
                frameTurn = (float) Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, dot))));
            }
            // A sudden jump means ARCore just corrected its map: the next moment's poses can't be trusted yet.
            if (havePrevPose && frameMove > 0.15f) { settleUntil = nowMs + 1500; poseJumps++; log(String.format(java.util.Locale.US, "pose jump %.2f m, pausing capture 1.5 s", frameMove)); }
            System.arraycopy(curPose, 0, prevPose, 0, 16);
            havePrevPose = true;
            if (nowMs >= settleUntil) {
                try {
                    integrate(frame, camera);
                } catch (Exception e) {
                    logError("integrate", e);
                }
            }
            updateGuide(camera, nowMs);
            recordPath(camera.getPose());
            camera.getProjectionMatrix(proj, 0, 0.05f, 100f);
            camera.getViewMatrix(view, 0);
            Matrix.multiplyMM(mvp, 0, proj, 0, view, 0);
            uploadNew();
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            drawPaint(mvp);
            float[] tap = pendingTap;
            if (tap != null) { pendingTap = null; prepareTap(frame, camera, tap[0], tap[1]); }
            maybeStartLiveRoom(nowMs);
            drawLiveRoom(mvp);
            cutouts.draw(mvp);
            drawMarks(mvp, true);
            drawRescanTarget(mvp, camera);
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            drawGuide(mvp);
            if (SHOW_OBJECTS) drawObjectHint(mvp);
            maybeAskClaudeLive(nowMs);
        }
        if (frameNo % 15 == 0) {
            String s;
            if (ts != TrackingState.TRACKING) {
                com.google.ar.core.TrackingFailureReason why = camera.getTrackingFailureReason();
                String hint = why == com.google.ar.core.TrackingFailureReason.INSUFFICIENT_FEATURES ? "Too close or a plain surface: step back and point at something with detail"
                        : why == com.google.ar.core.TrackingFailureReason.EXCESSIVE_MOTION ? "Moving too fast: hold still for a second"
                        : why == com.google.ar.core.TrackingFailureReason.INSUFFICIENT_LIGHT ? "Too dark: turn on a light"
                        : "Move the phone slowly, pointing at the floor or furniture...";
                s = hint + (everTracked ? " (tracking lost, capture paused)" : "") + "\n"
                        + "[tracking " + ts + ", reason " + camera.getTrackingFailureReason() + ", frame " + frameNo + "]\n"
                        + diagnostics();
            } else {
                s = "Scanning: " + photos.keyframes.size() + " photos · " + cutouts.size() + " objects placed\n" + liveRoomLine() + (hintText != null ? "\n👀 " + hintText : guideText != null ? "\n➜ " + guideText : "") + (claudeLiveKey != null && claudeLine != null ? "\n" + claudeLine : "") + (ClaudeHelper.sessionCalls > 0 ? "\n💰 " + ClaudeHelper.costLine() : "")
                        + "\n" + marksLine() + "\nWalk slowly round the room. Tap anything: a room corner, a wall, the floor, the ceiling or an object, and say which it is. Tap Finish when done.";
            }
            activity.updateStatus(s);
        }
    }

    // ---------------- "scan over there" guide ----------------
    private TrackingState lastTrackState;
    private long settleUntil, guideLastMs;
    private int poseJumps;
    private volatile float[] guideTarget; // world x, y, z of the biggest unscanned area
    private volatile String guideText;
    private final float[] camPos = new float[3], camFwd = new float[3];

    /**
     * Twice a second: find the biggest part of the room that hasn't been seen yet (unscanned floor inside the
     * fitted walls, and walls with no points), as seen from where the phone is now.
     */
    private void updateGuide(Camera camera, long nowMs) {
        Pose p = camera.getPose();
        camPos[0] = p.tx(); camPos[1] = p.ty(); camPos[2] = p.tz();
        float[] z = p.getZAxis();
        camFwd[0] = -z[0]; camFwd[1] = -z[1]; camFwd[2] = -z[2];
        if (frameNo % 3 == 0) updateObjectViews();
        if (nowMs - guideLastMs < 500) return;
        guideLastMs = nowMs;
        RoomModel r = liveRoom;
        if (r == null || r.grid == null || !r.hasLayout || r.bounds == null) { guideTarget = null; guideText = null; return; }
        ShotPlanner g = r.grid;
        float lc = (float) Math.cos(Math.toRadians(r.layoutAngle)), ls = (float) Math.sin(Math.toRadians(r.layoutAngle));
        float[] bd = r.bounds;
        // 12 compass sectors around the phone; each unscanned spot votes for its sector
        float[] w = new float[12], sx = new float[12], sz = new float[12];
        float cell = ShotPlanner.CELL;
        for (float u = bd[0] + 0.15f; u < bd[1] - 0.15f; u += cell) {
            for (float v = bd[2] + 0.15f; v < bd[3] - 0.15f; v += cell) {
                float x = u * lc - v * ls, zz = u * ls + v * lc;
                int ix = (int) ((x - g.minX) / cell), iz = (int) ((zz - g.minZ) / cell);
                boolean known = ix >= 0 && iz >= 0 && ix < g.nx && iz < g.nz
                        && (g.floorKnown[iz * g.nx + ix] || g.blocked[iz * g.nx + ix] > ShotPlanner.BLOCKED);
                if (known) continue;
                vote(w, sx, sz, x, zz, 1f);
            }
        }
        for (RoomModel.Wall wl : r.layout) {
            if (wl.seen) continue;
            float len = (float) Math.hypot(wl.x1 - wl.x0, wl.z1 - wl.z0);
            vote(w, sx, sz, (wl.x0 + wl.x1) / 2 - wl.nx * 0.2f, (wl.z0 + wl.z1) / 2 - wl.nz * 0.2f, len / cell * 2f);
        }
        int best = -1; float bw = 0;
        for (int i = 0; i < 12; i++) if (w[i] > bw) { bw = w[i]; best = i; }
        if (best < 0 || bw < 25) { guideTarget = null; guideText = r.floorCoverage > 0.85f ? "Room looks covered. Fill in details or tap Finish." : null; return; }
        float tx = sx[best] / w[best], tz = sz[best] / w[best];
        guideTarget = new float[]{tx, r.floorY + 0.3f, tz};
        guideText = String.format(java.util.Locale.US, "Not scanned yet: about %.1f m², follow the arrow", bw * cell * cell);
    }

    private void vote(float[] w, float[] sx, float[] sz, float x, float z, float weight) {
        double a = Math.atan2(x - camPos[0], z - camPos[2]);
        int s = (int) Math.floor((a + Math.PI) / (2 * Math.PI) * 12) % 12;
        w[s] += weight; sx[s] += x * weight; sz[s] += z * weight;
    }

    // ---- "look at this thing from another side" ----
    /** Per 30 cm floor cell: which of 8 compass sides the camera has looked at it from (bits), and from above (bit 8). */
    private final java.util.HashMap<Long, int[]> viewSides = new java.util.HashMap<>();
    private volatile RoomModel.Box hintBox;
    private volatile int hintDir; // 0 right, 1 left, 2 up (look down on it), 3 down (look from lower)
    private volatile String hintText;

    private static long sideKey(float x, float z) { return ((long) Math.floor(x / 0.3f) + 100000) * 1000003L + ((long) Math.floor(z / 0.3f) + 100000); }

    private static int sector(float dx, float dz) {
        double a = Math.atan2(dx, dz);
        return (int) Math.floor((a + Math.PI) / (2 * Math.PI) * 8) & 7;
    }

    private void updateObjectViews() {
        RoomModel r = liveRoom;
        if (r == null) { hintBox = null; hintText = null; return; }
        // camera right vector (horizontal)
        float rx = -camFwd[2], rz = camFwd[0];
        float rl = (float) Math.sqrt(rx * rx + rz * rz);
        if (rl < 1e-3f) return;
        rx /= rl; rz /= rl;
        RoomModel.Box best = null; float bestAng = 0.5f; int bestDir = -1;
        int n = 0;
        for (RoomModel.Box b : r.boxes) {
            if (n++ > 60) break;
            float cy = (b.y0 + b.y1) / 2;
            float dx = b.cx - camPos[0], dy = cy - camPos[1], dz = b.cz - camPos[2];
            float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < 0.2f || d > 3f) continue;
            float cosA = (dx * camFwd[0] + dy * camFwd[1] + dz * camFwd[2]) / d;
            if (cosA < 0.87f) continue; // within ~30 degrees of where the phone points
            long k = sideKey(b.cx, b.cz);
            int[] m = viewSides.get(k);
            if (m == null) { m = new int[1]; viewSides.put(k, m); }
            m[0] |= 1 << sector(-dx, -dz);
            if (camPos[1] > b.y1 + 0.25f && -dy / d > 0.35f) m[0] |= 1 << 8;
            // what's still missing, as a move the person can make from here
            int sides = Integer.bitCount(m[0] & 0xFF);
            if (sides >= 3 && (m[0] & (1 << 8)) != 0) continue;
            int dir;
            if ((m[0] & (1 << sector(-dx + rx * 0.8f, -dz + rz * 0.8f))) == 0 && sides < 3) dir = 0;
            else if ((m[0] & (1 << sector(-dx - rx * 0.8f, -dz - rz * 0.8f))) == 0 && sides < 3) dir = 1;
            else if ((m[0] & (1 << 8)) == 0) dir = 2;
            else continue;
            float ang = (float) Math.acos(Math.min(1f, cosA));
            if (ang < bestAng) { bestAng = ang; best = b; bestDir = dir; }
        }
        hintBox = best; hintDir = bestDir;
        if (best == null) { hintText = null; return; }
        String what = best.title();
        hintText = bestDir == 0 ? "Step right around the " + what + ", keep it in view"
                : bestDir == 1 ? "Step left around the " + what + ", keep it in view"
                : "Raise the phone and look down at the " + what;
    }

    /** Off for now (rk, 6 Nov): the object models were off in shape, size and place. Names still show in the status. */
    static final boolean SHOW_OBJECTS = false;

    private void drawObjectHint(float[] m) {
        RoomModel.Box b = hintBox;
        if (b == null) return;
        Matrix.multiplyMV(guideClip, 0, m, 0, new float[]{b.cx, b.y1 + 0.05f, b.cz, 1}, 0);
        if (guideClip[3] < 0.05f) return;
        float asp = (float) width / height;
        float px = guideClip[0] / guideClip[3] * asp, py = guideClip[1] / guideClip[3];
        float ax = hintDir == 0 ? 1 : hintDir == 1 ? -1 : 0, ay = hintDir == 2 ? 1 : hintDir == 3 ? -1 : 0;
        px += ax * 0.14f; py += ay * 0.14f + (ay == 0 ? 0.06f : 0);
        float s = 0.06f;
        float pulse = 0.7f + 0.3f * (float) Math.sin(System.currentTimeMillis() / 150.0);
        float[] tri = {px + ax * s, py + ay * s, px - ax * s * 0.6f - ay * s * 0.7f, py - ay * s * 0.6f + ax * s * 0.7f,
                px - ax * s * 0.6f + ay * s * 0.7f, py - ay * s * 0.6f - ax * s * 0.7f};
        float[] v = new float[9];
        for (int i = 0; i < 3; i++) { v[i * 3] = tri[i * 2] / asp; v[i * 3 + 1] = tri[i * 2 + 1]; }
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        planDraw(IDENTITY, v, GLES20.GL_TRIANGLES, new float[]{0.35f, 0.9f, 1f, 0.95f * pulse}, 1);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private static final float[] GUIDE_COL = {1f, 0.85f, 0.2f, 0.95f};
    private final float[] guideClip = new float[4];

    /** An arrow at the screen edge pointing to the unscanned area, or a marker on it when it's in view. */
    private void drawGuide(float[] m) {
        float[] t = guideTarget;
        if (t == null) return;
        Matrix.multiplyMV(guideClip, 0, m, 0, new float[]{t[0], t[1], t[2], 1}, 0);
        float w = guideClip[3];
        float nx = guideClip[0] / Math.abs(w), ny = guideClip[1] / Math.abs(w);
        boolean inView = w > 0.05f && Math.abs(nx) < 0.85f && Math.abs(ny) < 0.85f;
        float pulse = 0.75f + 0.25f * (float) Math.sin(System.currentTimeMillis() / 180.0);
        float[] col = {GUIDE_COL[0], GUIDE_COL[1], GUIDE_COL[2], GUIDE_COL[3] * pulse};
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        if (inView) {
            GLES20.glLineWidth(4f * height / 1000f);
            int n = 24; float[] ring = new float[n * 3];
            for (int i = 0; i < n; i++) {
                double a = 2 * Math.PI * i / n;
                ring[i * 3] = t[0] + 0.35f * (float) Math.cos(a); ring[i * 3 + 1] = t[1]; ring[i * 3 + 2] = t[2] + 0.35f * (float) Math.sin(a);
            }
            planDraw(m, ring, GLES20.GL_LINE_LOOP, col, 1);
            planDraw(m, new float[]{t[0], t[1], t[2]}, GLES20.GL_POINTS, col, 26 * height / 1000f);
        } else {
            // direction on screen (behind the camera: flip it so the arrow points the way to turn)
            float dx = nx, dy = ny;
            if (w < 0) { dx = -dx; dy = -dy; }
            float asp = (float) width / height;
            float ax = dx * asp, ay = dy; // in screen-proportional units
            float l = (float) Math.sqrt(ax * ax + ay * ay);
            if (l < 1e-4f) { ax = 1; ay = 0; l = 1; }
            ax /= l; ay /= l;
            // place it at the edge: scale so the larger component hits 0.85
            float kx = Math.abs(ax) > 1e-4f ? 0.85f * asp / Math.abs(ax) : 1e9f, ky = Math.abs(ay) > 1e-4f ? 0.85f / Math.abs(ay) : 1e9f;
            float k = Math.min(kx, ky);
            float px = ax * k, py = ay * k; // screen-proportional
            float s = 0.09f; // arrow size
            float[] tri = {px + ax * s, py + ay * s, px - ax * s * 0.6f - ay * s * 0.7f, py - ay * s * 0.6f + ax * s * 0.7f,
                    px - ax * s * 0.6f + ay * s * 0.7f, py - ay * s * 0.6f - ax * s * 0.7f};
            float[] v = new float[9];
            for (int i = 0; i < 3; i++) { v[i * 3] = tri[i * 2] / asp; v[i * 3 + 1] = tri[i * 2 + 1]; v[i * 3 + 2] = 0; }
            planDraw(IDENTITY, v, GLES20.GL_TRIANGLES, col, 1);
        }
        GLES20.glDisable(GLES20.GL_BLEND);
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
            depthPixelsFrame = 0; nearPixelsFrame = 0; nearSum[0] = nearSum[1] = nearSum[2] = 0;
            // Record the raw frame for export (replaying the scan off the phone).
            if (recs.size() < ScanExport.MAX_RECS) {
                ScanExport.Rec r = new ScanExport.Rec();
                r.ts = depth.getTimestamp(); r.w = dw; r.h = dh; r.fx = fx; r.fy = fy; r.cx = cx; r.cy = cy;
                System.arraycopy(m, 0, r.pose, 0, 16);
                r.depth = new short[dw * dh];
                for (int v = 0; v < dh; v++) for (int u = 0; u < dw; u++) r.depth[v * dw + u] = dbuf.get(v * dRow + u * dPix);
                if (cbuf != null) {
                    r.conf = new byte[dw * dh];
                    for (int v = 0; v < dh; v++) for (int u = 0; u < dw; u++) r.conf[v * dw + u] = cbuf.get(v * cRow + u * cPix);
                }
                if (cpuImage != null) { // small greyscale copy of the camera's Y plane
                    Image.Plane yp = cpuImage.getPlanes()[0];
                    ByteBuffer yb = yp.getBuffer();
                    int iw = cpuImage.getWidth(), ih = cpuImage.getHeight(), step = Math.max(1, iw / 160);
                    int gw = iw / step, gh = ih / step, yRow = yp.getRowStride(), yPix = yp.getPixelStride();
                    r.gray = new byte[gw * gh]; r.gw = gw; r.gh = gh;
                    for (int v = 0; v < gh; v++) for (int u = 0; u < gw; u++) r.gray[v * gw + u] = yb.get(v * step * yRow + u * step * yPix);
                    float gs = (float) iw / ii.getImageDimensions()[0] / step;
                    r.gfx = ifx * gs; r.gfy = ify * gs; r.gcx = icx * gs; r.gcy = icy * gs;
                }
                try {
                    com.google.ar.core.LightEstimate le = frame.getLightEstimate();
                    if (le.getState() == com.google.ar.core.LightEstimate.State.VALID) {
                        r.ambient = le.getPixelIntensity(); le.getColorCorrection(r.colorCorr, 0);
                    }
                } catch (Throwable ignored) { }
                try {
                    com.google.ar.core.ImageMetadata md = frame.getImageMetadata();
                    r.exposureNs = md.getLong(com.google.ar.core.ImageMetadata.SENSOR_EXPOSURE_TIME);
                    r.iso = md.getInt(com.google.ar.core.ImageMetadata.SENSOR_SENSITIVITY);
                } catch (Throwable ignored) { }
                r.lux = sensors.lastLux; r.heading = sensors.lastHeadingDeg;
                try (PointCloud fpc = frame.acquirePointCloud()) {
                    java.nio.IntBuffer ids = fpc.getIds(); java.nio.FloatBuffer pts = fpc.getPoints();
                    int nf = ids.remaining();
                    r.fpId = new int[nf]; r.fp = new float[nf * 4];
                    ids.get(r.fpId); pts.get(r.fp, 0, Math.min(r.fp.length, pts.remaining()));
                } catch (Throwable ignored) { }
                recs.add(r);
            }
            for (int v = 0; v < dh; v++) {
                for (int u = 0; u < dw; u++) {
                    int mm = dbuf.get(v * dRow + u * dPix) & 0xFFFF;
                    if (mm < 200 || mm > 4000) { depthRejectedRange++; continue; }
                    depthPixels++;
                    int cv = cbuf != null ? cbuf.get(v * cRow + u * cPix) & 0xFF : 255;
                    if (cv < 40) { depthRejectedConf++; continue; } // below this the S24's raw depth is mostly noise (scan3 replay)
                    boolean weak = cv < 55; // too unsure for the point cloud, but the surface fusion averages it out
                    if (weak) depthRejectedConf++;
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
                    depthPixelsFrame++;
                    if (d < 0.8f) { nearPixelsFrame++; nearSum[0] += wx; nearSum[1] += wy; nearSum[2] += wz; }
                    boolean fine = !zones.isEmpty() && inZone(wx, wy, wz);
                    long vk = fine ? fineKey(wx, wy, wz) : key(wx, wy, wz);
                    int hit = voxels.get(vk);
                    sampleColor(xc / d * ifx + icx, icy - yc / d * ify, rgb);
                    tsdf.integrate(m[12], m[13], m[14], wx, wy, wz, rgb);
                    if (fine) detail.integrate(m[12], m[13], m[14], wx, wy, wz, rgb);
                    if (weak) continue;
                    if (hit >= 0) { refine(hit, wx, wy, wz, rgb); continue; }
                    voxels.put(vk, count);
                    addPoint(wx, wy, wz, rgb);
                    if (count >= MAX_POINTS) return;
                }
            }
            // Keep a sharp HD photo of this view (with its pose) for the photographic room.
            // Close-up held for a moment: make it a detail zone automatically.
            if (depthPixelsFrame > 500 && nearPixelsFrame > depthPixelsFrame / 2) closeFrames++; else closeFrames = 0;
            if (closeFrames >= 4 && nearPixelsFrame > 0) {
                closeFrames = 0;
                float zx = nearSum[0] / nearPixelsFrame, zy = nearSum[1] / nearPixelsFrame, zz = nearSum[2] / nearPixelsFrame;
                if (!inZone(zx, zy, zz) && zones.size() < 24) {
                    zones.add(new float[]{zx, zy, zz, 0.3f});
                    log(String.format(java.util.Locale.US, "auto detail zone %d at (%.2f, %.2f, %.2f)", zones.size(), zx, zy, zz));
                    activity.toast("Close-up: adding detail here");
                }
            }
            boolean near = lookingAtZone(m);
            if (photos.wants(m, frameMove, frameTurn, near)) {
                short[] dcopy = new short[dw * dh];
                for (int v = 0; v < dh; v++) for (int u = 0; u < dw; u++) dcopy[v * dw + u] = dbuf.get(v * dRow + u * dPix);
                int[] td = ti.getImageDimensions();
                float[] tIntr = {ti.getFocalLength()[0], ti.getFocalLength()[1], ti.getPrincipalPoint()[0], ti.getPrincipalPoint()[1], td[0], td[1]};
                photos.capture(bgTex, m, tIntr, dcopy, dw, dh, new float[]{fx, fy, cx, cy}, colorGain, width, height, near);
                if (photos.keyframes.size() == 1) log("first photo keyframe (texture " + td[0] + "x" + td[1] + ")");
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
                + " | not ready " + depthNotYet + " | refined " + refinedTotal + String.format(java.util.Locale.US, " | gain %.2f", colorGain) + " | photos " + photos.keyframes.size() + " | surface voxels " + tsdf.size() + (lastError.isEmpty() ? "" : " | err " + lastError) + "]";
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
        boolean[] drop = new boolean[count];
        for (int i = 0; i < count; i++) {
            float x = pos.get(i * 3), y = pos.get(i * 3 + 1), z = pos.get(i * 3 + 2);
            // one-off specks, and points in space the camera later saw straight through (mid-air noise)
            drop[i] = (obs[i] & 0xFF) < 2 || (!inZone(x, y, z) && tsdf.seenThrough(x, y, z));
            if (!drop[i]) keep++;
        }
        droppedNoise = 0;
        if (count < 2000 || keep < count * 0.3f) return;
        int k = 0;
        for (int i = 0; i < count; i++) {
            if (drop[i]) continue;
            if (k != i) {
                pos.put(k * 3, pos.get(i * 3)); pos.put(k * 3 + 1, pos.get(i * 3 + 1)); pos.put(k * 3 + 2, pos.get(i * 3 + 2));
                for (int c = 0; c < 4; c++) col.put(k * 4 + c, col.get(i * 4 + c));
                obs[k] = obs[i];
            }
            k++;
        }
        droppedNoise = count - k;
        count = k; uploaded = 0;
        rebuildVoxelIndex();
    }

    private void recordPath(Pose p) {
        float x = p.tx(), y = p.ty(), z = p.tz();
        float dx = x - lastPx, dy = y - lastPy, dz = z - lastPz;
        if (dx * dx + dy * dy + dz * dz < 0.0025f || pathCount >= MAX_PATH) return;
        path.put(pathCount * 3, x); path.put(pathCount * 3 + 1, y); path.put(pathCount * 3 + 2, z);
        pathCount++;
        lastPx = x; lastPy = y; lastPz = z;
    }

    /** Finer point dedupe inside detail zones; the top bit keeps these keys apart from the 2 cm ones. */
    private static long fineKey(float x, float y, float z) {
        long ix = (long) Math.floor(x / VOXEL_FINE) + (1 << 20);
        long iy = (long) Math.floor(y / VOXEL_FINE) + (1 << 20);
        long iz = (long) Math.floor(z / VOXEL_FINE) + (1 << 20);
        return ((ix & 0x1FFFFF) | ((iy & 0x1FFFFF) << 21) | ((iz & 0x1FFFFF) << 42)) | Long.MIN_VALUE;
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
    private volatile int style = 1; // 0 = photo surface, 1 = splats (surfels), 2 = dots

    String cycleStyle() {
        style = (style + 1) % 3;
        splats = style != 2;
        return styleLabel();
    }

    String styleLabel() { return style == 0 ? "Style: photo" : style == 1 ? "Style: splats" : "Style: dots"; }

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
        setHideUniforms(ptProg);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(ptProg, "uDir2"), cutDir2X, cutDir2Z);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(ptProg, "uCutD2"), cutD2);
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
    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    private static final float[] SCREEN_QUAD = {-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, -1, 0, 1, 1, 0, -1, 1, 0};
    private static final float[] DIM_TINT = {0.03f, 0.04f, 0.08f, 1f};

    /**
     * Live coverage: what's already scanned shows the clear camera image, everything else is dimmed.
     * The scanned points stamp a mask into the framebuffer's alpha, then one dark quad is blended by that mask.
     */
    static final boolean DIM_UNSCANNED = false;

    private void drawPaint(float[] m) {
        if (!DIM_UNSCANNED) { // the greyed-out overlay wasn't helping (rk, 6 Nov): just keep the view opaque
            GLES20.glColorMask(false, false, false, true);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glColorMask(true, true, true, true);
            GLES20.glClearColor(0.08f, 0.08f, 0.1f, 1f);
            return;
        }
        // 1) alpha = 0.62 everywhere ("not scanned"), then scanned points punch it to 0
        GLES20.glColorMask(false, false, false, true);
        GLES20.glClearColor(0f, 0f, 0f, 0.62f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (count > 0) {
            GLES20.glUseProgram(ptProg);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(ptProg, "uMvp"), 1, false, m, 0);
            float pxPerMeter = height / (2f * (float) Math.tan(Math.toRadians(30)));
            GLES20.glUniform1f(GLES20.glGetUniformLocation(ptProg, "uSize"), VOXEL * pxPerMeter * 3.4f);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(ptProg, "uPass"), 3f);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(ptProg, "uCut"), 0f);
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            drawPointArrays();
        }
        // 2) dark tint weighted by the mask: result = tint * a + camera * (1 - a)
        GLES20.glColorMask(true, true, true, false);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_DST_ALPHA, GLES20.GL_ONE_MINUS_DST_ALPHA);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        planDraw(IDENTITY, SCREEN_QUAD, GLES20.GL_TRIANGLES, DIM_TINT, 1);
        GLES20.glDisable(GLES20.GL_BLEND);
        // 3) restore an opaque alpha so the compositor never sees through the view
        GLES20.glColorMask(false, false, false, true);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glColorMask(true, true, true, true);
        GLES20.glClearColor(0.08f, 0.08f, 0.1f, 1f);
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
        for (Runnable r; (r = glTasks.poll()) != null; ) r.run();
        uploadNew();
        uploadSurfels();
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
        if (SHOW_OBJECTS) updateHideList(); else hideN = 0;
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        boolean simple = viewMode == 0 && roomReady && room.hasLayout;
        if (simple) {
            if (photos.ready) photos.draw(mvp, cutActive, roomCenter, cutDirX, cutDirZ, cutD, cutDir2X, cutDir2Z, cutD2, cutFloor, cutCeil);
        } else if (style == 0 && photos.ready && meshTris > 0)
            photos.draw(mvp, cutActive, roomCenter, cutDirX, cutDirZ, cutD, cutDir2X, cutDir2Z, cutD2, cutFloor, cutCeil);
        else if ((style == 1 || lighting.enabled) && surfCount > 0 && !depthRunning) drawSurfels(mvp);
        else drawPoints(mvp, roomReady ? 0.9f : 1f);
        if (lighting.enabled) { lighting.camX = ex; lighting.camY = ey; lighting.camZ = ez; lighting.haveCam = !topView; drawProps(mvp); }
        else if (roomReady) drawRoom(mvp, simple);
        cutActive = false;
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        drawPath(mvp);
        System.arraycopy(mvp, 0, viewMvp, 0, 16);
        if (lighting.enabled) drawLightGizmos(mvp);
        else if (plan != null) drawPlan(mvp, plan);
        if (pendingSave != null) { Runnable r = pendingSave; pendingSave = null; r.run(); }
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
    /** simple: the photo room is showing, so draw only edges (no striped wall or floor fills over the photos). */
    private void drawRoom(float[] m, boolean simple) {
        float dens = height / 1000f;
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);
        // floor
        if (!simple && floorTris != null && floorTriVerts > 0) {
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
            } else if (sf.kind == RoomModel.Surface.Kind.TABLE) { if (claudeObjs.has()) continue; fill = TABLE_COL; } // Claude's objects replace the plane guesses
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
                if (cutActive && (w.nx * cutDirX + w.nz * cutDirZ > 0.45f || (cutD2 < 1e8f && w.nx * cutDir2X + w.nz * cutDir2Z > 0.45f))) continue;
                float[] quad = {w.x0, y0, w.z0, w.x1, y0, w.z1, w.x1, y1, w.z1, w.x0, y0, w.z0, w.x1, y1, w.z1, w.x0, y1, w.z0};
                if (!simple) planDraw(m, quad, GLES20.GL_TRIANGLES, w.seen ? WALL_COL : WALL_GUESS, 1);
                planDraw(m, new float[]{w.x0, y0, w.z0, w.x0, y1, w.z0, w.x1, y0, w.z1, w.x1, y1, w.z1,
                        w.x0, y1, w.z0, w.x1, y1, w.z1}, GLES20.GL_LINES, w.seen ? WALL_EDGE : WALL_GUESS_EDGE, 1);
            }
            planDraw(m, outline, GLES20.GL_LINE_LOOP, WALL_EDGE, 1);
        }
        // objects: furniture and the things on it, as boxes and cylinders
        if (SHOW_OBJECTS) drawObjects(m, room.boxes, false);
        cutouts.draw(m);
        for (Cutouts.Cutout c : cutouts.snapshot())
            drawLabel(m, c.x, c.bottomY + c.height + 0.04f, c.z, String.format(java.util.Locale.US, "%s · %.0f cm tall", c.name, c.height * 100), false);
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
        // light sources: glowing markers with a drop line to the floor
        for (RoomModel.Light l : room.lights) {
            planDraw(m, new float[]{l.x, room.floorY, l.z, l.x, l.y, l.z}, GLES20.GL_LINES, LIGHT_COL, 1);
            planDraw(m, new float[]{l.x, l.y, l.z}, GLES20.GL_POINTS, LIGHT_COL, 30 * dens);
        }
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

    static int program(String vs, String fs) {
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
