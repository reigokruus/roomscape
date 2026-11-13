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
    // Fewer points on phones with a 512 MB app limit (Galaxy S23 Ultra, 11 Nov: out of memory with 1.2 million): each
    // point costs ~30 bytes held all the time (position, colour, counters)
    /** Debug tools "Force low memory" (read once at start, so it needs an app restart): behave like a 512 MB phone
     *  (Galaxy S23 Ultra) on a bigger one, to test the low-memory limits on rk's S24. MainActivity sets the property
     *  before this class loads. */
    static final boolean FORCED_LOW_MEMORY = "1".equals(System.getProperty("roomscape.lowmem"));
    static final boolean LOW_MEMORY = FORCED_LOW_MEMORY || Runtime.getRuntime().maxMemory() < 700L * 1048576;
    /** The heap limit the app keeps under: the real one, or 512 MB when low memory is forced. */
    static final long HEAP_LIMIT = FORCED_LOW_MEMORY ? Math.min(Runtime.getRuntime().maxMemory(), 512L * 1048576) : Runtime.getRuntime().maxMemory();
    private static final int MAX_POINTS = LOW_MEMORY ? 1_200_000 : 2_000_000;
    private static final float VOXEL = 0.02f;
    private static final int MAX_PATH = 20_000;
    /** The camera timestamp of the previous tracked frame (ns), for telling a map jump from a pause between frames. */
    private long prevFrameNs;

    private final MainActivity activity;
    private Session session;
    private boolean depthSupported, rawDepth;
    private volatile boolean viewing;

    // Point storage
    // The points live in memory-mapped scratch files, not on the Java heap (rk 11 Nov: "dump the data to storage so
    // memory doesn't fill up"): on Android allocateDirect counts against the app's heap limit (512 MB on a Galaxy S23
    // Ultra), a mapped file doesn't, and the system can page it out. The scratch file is unlinked at once, so it goes
    // with the app; the scan itself is saved as before (autosave, ScanStore). Falls back to a direct buffer.
    private final FloatBuffer pos = offHeap(MAX_POINTS * 12, "points").asFloatBuffer();
    private final ByteBuffer col = offHeap(MAX_POINTS * 4, "colours");

    private static volatile boolean offHeapMapped;

    private static ByteBuffer offHeap(int bytes, String what) {
        try {
            File f = File.createTempFile("scan-" + what + "-", ".buf");
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw")) {
                raf.setLength(bytes);
                ByteBuffer b = raf.getChannel().map(java.nio.channels.FileChannel.MapMode.READ_WRITE, 0, bytes);
                offHeapMapped = true;
                return b.order(ByteOrder.nativeOrder());
            } finally { f.delete(); }   // the mapping stays valid; the file goes when the app does
        } catch (Throwable e) {
            android.util.Log.w("RoomScape", "off-heap " + what + " failed, on the heap instead: " + e);
            return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        }
    }
    private int count, uploaded;
    private final LongIntMap voxels = new LongIntMap(1 << 22);
    // The surface tables are allocated up front: ~132 MB at these sizes (the hash table is 2-4x the voxels). On a 512 MB
    // phone that was half the memory before anything was scanned (S23 Ultra, 11 Nov): smaller there. A room's surface
    // is ~0.3 M voxels at 4 cm (S24 scans), so 1 M still leaves a big flat or several rooms.
    private final Tsdf tsdf = new Tsdf(LOW_MEMORY ? 1_000_000 : 1_500_000, 0.04f, 0.12f, 99f); // carve along the whole ray
    /** Fine surface for the detail zones the user tapped (shoes, the table top...). */
    private final Tsdf detail = new Tsdf(LOW_MEMORY ? 500_000 : 1_000_000, 0.012f, 0.04f, 0.2f);
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
    { photos.lightUniforms = this::setLightUniforms; }
    private final float[] prevPose = new float[16], curPose = new float[16];
    private boolean havePrevPose;
    private float frameMove = 1f, frameTurn = 90f;
    private int meshTris;
    private final java.util.ArrayList<ScanExport.Rec> recs = new java.util.ArrayList<>();
    private final ScanExport.PoseLog poseLog = new ScanExport.PoseLog();
    final SensorLog sensors = new SensorLog();
    private final float[] poseTq = new float[7];
    private final byte[] obs = new byte[MAX_POINTS]; // how many times each point was seen (capped)
    // Per dot, how sure the depth was (rk 11 Nov 21:55): ARCore's raw depth confidence of its best reading (0-255, 0 = not
    // known, e.g. loaded from an older scan) and the distance of its closest reading (cm). Saved as dot-confidence.bin.
    private final byte[] confBest = new byte[MAX_POINTS];
    private final short[] nearCm = new short[MAX_POINTS];
    /** Per dot: 1 = it lives on the fine 0.8 cm grid (set when it's made), so the live view gives each dot one size. */
    private final byte[] dotFine = new byte[MAX_POINTS];
    private boolean curFine;   // the dot being added (depth thread), for addPoint
    /** Debug tools "Confidence greys": dots drawn in grey by their confidence (white = ARCore most sure, black = least);
     *  display only, the real colours are kept and saved as always. */
    static volatile boolean CONF_GREY = false;   // (rk 22:27: live dots in their real colour again)
    /** Greys while scanning only; the result and the gallery show the real colours (rk 11 Nov 22:18: "I want to see what
     *  it picked up"). */
    private boolean greyNow() { return CONF_GREY && !viewing; }
    private boolean greyShown, mergedShown; private int paintShown = -1;
    /** Debug tools "Show merged areas": dots standing for merged flat cells drawn blue, live and in saved scans. */
    static final boolean SHOW_MERGED = false;
    private final ByteBuffer greyBuf = offHeap(MAX_POINTS * 4, "greys");
    private int curConf = 0, curMm = 0;     // the reading being added (depth thread), for addPoint/refine
    /** Only sure readings paint a dot (rk 11 Nov 22:09: "make only points above 90% paint"): ARCore depth confidence
     *  230 of 255 or more. A spot with only weaker readings isn't drawn, doesn't count as scanned, and isn't saved as a
     *  scan point (lowconf-points.bin keeps it aside); a sure reading later paints it. Debug tools "Paint from confidence". */
    static volatile int PAINT_MIN = 204;   // 80% (rk 13 Nov 14:33; was 95% = 242 from 11 Nov 22:27, before that 90% = 230)
    /** Debug tools "Closest sure look wins" (rk 11 Nov 22:25: wrong dots on the chair legs should "retry"): a sure
     *  reading taken clearly closer than a dot's closest look replaces the dots along its line of sight within
     *  +-SUPERSEDE_ALONG (2 cells of 2 cm: the S24's 90%+ depth scatters ~1-2 cm at 1 m) instead of landing beside them.
     *  Only a clearly closer look (under 80% of the stored closest distance and at least 15 cm closer) wins, never a
     *  farther or equal one (rk 10 Nov), so repeated looks settle instead of churning. Sideways only the reading's own
     *  cell: a depth pixel covers under 1 cm at 1 m (160x90 depth, ~120 px focal length). */
    static volatile boolean CLOSEST_WINS = true;
    /** The capture zone (rk 13 Nov, as on the iPhone, DotCleanup.swift): only depth seen inside the centred
     *  ZONE_W x ZONE_H share of the screen makes, updates and erases dots; the edges of the picture are ignored. The
     *  surface fusion, the rebuild frames, the recording and the photos still use the whole frame. Outlined on screen. */
    static final float ZONE_W = 0.72f, ZONE_H = 0.70f;
    /** Clip-space mapping from the depth camera's own coordinates to the screen (projection x display view x sensor
     *  pose), set per depth frame (integrate) so the depth thread can tell whether a pixel / dot is on the zone. */
    private static boolean inZoneClip(float[] z, float xc, float yc, float zc) {
        float w = z[3] * xc + z[7] * yc + z[11] * zc + z[15];
        if (w <= 1e-4f) return false;
        float x = (z[0] * xc + z[4] * yc + z[8] * zc + z[12]) / w, y = (z[1] * xc + z[5] * yc + z[9] * zc + z[13]) / w;
        return Math.abs(x) <= ZONE_W && Math.abs(y) <= ZONE_H;
    }
    /** Amber stripes where nothing is scanned yet inside the zone (rk 13 Nov: a hole behind an object read as a shadow;
     *  the iPhone's #EDB14D at 40%, 4 dp bands); drawn under the dots, so the dots cover them as the surface fills in. */
    static final float STRIPE_ALPHA = 0.40f, STRIPE_DP = 4f, ZONE_RADIUS_DP = 22f;
    private int stripeProg;
    // ---- Phone load (rk 23:34: "free up the cpu", "a cpu reading so I know to slow down") ----
    /** ARCore's depth is late: none for over 300 ms while tracking. Our extra passes wait. */
    boolean depthLate() { return lastNewDepthMs > 0 && System.currentTimeMillis() - lastNewDepthMs > 300; }
    private long cpuAtMs = 0, cpuAt = 0; private volatile float cpuCores; private int lateEvents; private boolean lateNow;
    volatile int thermal = -1;
    /** 0 green, 1 amber, 2 red: how late ARCore's depth is, the phone's heat, and the app's processor use. */
    int loadLevel() {
        long now = System.currentTimeMillis();
        if (now - cpuAtMs >= 1000) {
            long c = android.os.Process.getElapsedCpuTime();
            if (cpuAtMs > 0) cpuCores = (c - cpuAt) / (float) (now - cpuAtMs);
            cpuAt = c; cpuAtMs = now;
        }
        long age = lastNewDepthMs > 0 && !viewing ? now - lastNewDepthMs : 0;
        boolean late = age > 300; if (late && !lateNow) lateEvents++; lateNow = late;
        if (age > 1500 || thermal >= 3) return 2;
        if (age > 400 || thermal >= 2 || cpuCores > 3.2f || tooFast) return 1;   // (rk 23:50: moving a lot preceded the pause)
        return 0;
    }
    String loadNumbers() { long age = lastNewDepthMs > 0 ? System.currentTimeMillis() - lastNewDepthMs : -1;
        return String.format(java.util.Locale.US, "cpu %.1f cores, depth %d ms old, heat %d", cpuCores, age, thermal); }
    /** Debug tools "Automatic eraser": dots the camera's sure depth sees clearly past in 2 separate frames are removed. */
    static final boolean AUTO_ERASE = false;   // the 2-frame eraser stays off (12 Nov); see-through erase runs with the Android-tuned rule in carve()
    private int erasedSec, revivedSec, speckSec; private long eraseLogAt;
    static final int SPECK_FRAMES = 10;   // a one-reading dot not seen again in this many depth frames (carve)   // see-through erases and revivals, logged once a second
    // ---- Rebuild frames: picture + pose + intrinsics + raw depth with confidence, twice a second (rk 12 Nov) ----
    static volatile boolean SAVE_REBUILD = true;
    private long rebuildAt; private int rebuildN; private File rebuildTmp;
    private final java.util.concurrent.atomic.AtomicInteger rebuildPending = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.ExecutorService rebuildExec = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(() -> {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run(); }, "rebuild-frames"));
    /** The frames go to a folder of their own while scanning and move into the scan folder at Done (rebuild-frames/):
     *  NNNNN.jpg (camera picture), NNNNN.depth (int w, h; then w*h big-endian shorts, mm; then w*h confidence bytes,
     *  0-255; raw, all levels), and frames.csv: index, scan ms, the camera pose (camera -> world, corrected, 16 floats
     *  column-major), the picture's fx fy cx cy w h and the depth's fx fy cx cy w h. */
    private void saveRebuildFrame(float[] pose, short[] d, byte[] c, int dw, int dh, float fx, float fy, float cx, float cy,
                                  byte[][] yuv, int[] yi, float ifx, float ify, float icx, float icy, long ms) {
        if (rebuildTmp == null) { rebuildTmp = new File(activity.getFilesDir(), "rebuild-tmp-" + System.currentTimeMillis()); rebuildTmp.mkdirs(); rebuildN = 0; }
        final File dir = rebuildTmp; final int idx = rebuildN++;
        rebuildPending.incrementAndGet();
        rebuildExec.execute(() -> {
            try {
                int w = yi[0], h = yi[1], yRow = yi[2], yPix = yi[3], cRow = yi[4], cPix = yi[5];
                int[] px = new int[w * h];
                for (int v = 0; v < h; v++) for (int u = 0; u < w; u++) {
                    int Y = yuv[0][v * yRow + u * yPix] & 255; int ci = (v / 2) * cRow + (u / 2) * cPix;
                    int U = (ci < yuv[1].length ? yuv[1][ci] & 255 : 128) - 128, V = (ci < yuv[2].length ? yuv[2][ci] & 255 : 128) - 128;
                    int r = Math.max(0, Math.min(255, Math.round(Y + 1.402f * V))), g = Math.max(0, Math.min(255, Math.round(Y - 0.344f * U - 0.714f * V))), b = Math.max(0, Math.min(255, Math.round(Y + 1.772f * U)));
                    px[v * w + u] = 0xFF000000 | r << 16 | g << 8 | b;
                }
                android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888);
                String nm = String.format(java.util.Locale.US, "%05d", idx);
                try (java.io.FileOutputStream o = new java.io.FileOutputStream(new File(dir, nm + ".jpg"))) { bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, o); }
                bm.recycle();
                try (java.io.DataOutputStream o = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(new File(dir, nm + ".depth"))))) {
                    o.writeInt(dw); o.writeInt(dh); for (short v : d) o.writeShort(v);
                    if (c != null) o.write(c); else o.write(new byte[dw * dh]);
                }
                StringBuilder line = new StringBuilder().append(idx).append(',').append(ms);
                for (float v : pose) line.append(',').append(v);
                line.append(',').append(ifx).append(',').append(ify).append(',').append(icx).append(',').append(icy).append(',').append(w).append(',').append(h);
                line.append(',').append(fx).append(',').append(fy).append(',').append(cx).append(',').append(cy).append(',').append(dw).append(',').append(dh).append('\n');
                try (java.io.FileWriter fw = new java.io.FileWriter(new File(dir, "frames.csv"), true)) { fw.write(line.toString()); }
            } catch (Throwable e) { android.util.Log.w("RoomScape", "rebuild frame: " + e); }
            finally { rebuildPending.decrementAndGet(); }
        });
    }
    /** At Done (or a kept Cancel): the frames move into the scan folder as rebuild-frames/. */
    private void keepRebuildFrames(File scanDir) {
        File t = rebuildTmp; rebuildTmp = null;
        if (t == null || scanDir == null) return;
        rebuildExec.execute(() -> {   // after the frames still being written
            File to = new File(scanDir, "rebuild-frames");
            if (t.renameTo(to)) log("rebuild frames kept with the scan: rebuild-frames/ (" + (to.list() == null ? 0 : to.list().length) + " files)");
            else log("rebuild frames: couldn't move " + t.getName() + " into the scan folder");
        });
    }
    /** Debug tools "Merge flat areas" (rk 11 Nov 23:20: "smooth over some areas where the dots make a coherent shape"):
     *  settled fine (0.8 cm) dots in a 2 cm cell that lie flat (thinner than 4 mm) and evenly coloured (channel spread
     *  under 12) become one dot there: the average position and colour, the best confidence, the closest distance.
     *  Offline on the 23:07 scan: 30% of its dots were in such cells, ~26% fewer dots. Later readings in those cells are
     *  averaged into the merged dot. Display and dot count only; nothing else about the scan changes. */
    static final boolean MERGE_FLAT = false;   // removed 12 Nov (see above)
    private final byte[] mergedDot = new byte[MAX_POINTS];   // 1 = this dot stands for a merged flat cell
    private int mergeCursor, mergedAway; private long mergeAt, mergeLogAt;
    private int mergeRounds, mergeLateSkips, mergeRoundAway, mergeCells, mergeNotSettled, mergeRecent, mergeInView, mergeNotFlat;
    private final int[] mergeMem = new int[64];
    private volatile float centerDepth = 1f;   // the depth straight ahead (m)
    // Merging flat areas live, the Rescan sphere and the automatic eraser were removed on 12 Nov (rk 10:24: "not
    // reliable live; these smaller fixes will be done by Claude and local machines from now"). Old scans with
    // merged-dots.bin still open: mergedDot is read and kept (SHOW_MERGED stays off).
    /** Debug tools "Fine dots up close": readings from under FINE_NEAR_M get 0.8 cm cells instead of 2 cm. */
    static volatile boolean FINE_NEAR = true;
    static final float FINE_NEAR_M = 1.0f;
    /** Debug tools "Dot size" (rk 11 Nov 22:26: "can you make the dots smaller"): a factor on the drawn dot size (dots
     *  are drawn in world size, smaller on screen further away). 0.5 = about 1.4 cm across (was 2.8 cm). Display only. */
    static volatile float DOT_SCALE = 1f;   // (rk 22:40: smaller dots showed the gaps between them: back to full size)
    /** Debug tools "Camera opacity" (rk 11 Nov 22:40): the live camera picture at 30% so the sure dots show better. */
    static volatile float CAMERA_OPACITY = 0.3f;
    private void dimCamera() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        planDraw(IDENTITY, SCREEN_QUAD, GLES20.GL_TRIANGLES, new float[]{0.04f, 0.04f, 0.05f, 1f - CAMERA_OPACITY}, 1);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
    }
    static final int SUPERSEDE_ALONG = 2;
    private int supersededTotal;
    /** Depth thread. Returns true when the reading took over its own cell's dot (nothing more to do for it). */
    private boolean supersede(float wx, float wy, float wz, float cx, float cy, float cz, int mm, int[] rgb, boolean fineCells) {
        final float cell = fineCells ? VOXEL_FINE : VOXEL; final int reach = fineCells ? SUPERSEDE_ALONG * 2 : SUPERSEDE_ALONG;   // the same +-4 cm
        int newCm = mm / 10;
        float dx = wx - cx, dy = wy - cy, dz = wz - cz, l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (l < 1e-3f) return false;
        dx /= l; dy /= l; dz /= l;
        boolean tookOwn = false;
        for (int k = -reach; k <= reach; k++) {
            float px = wx + dx * k * cell, py = wy + dy * k * cell, pz = wz + dz * k * cell;
            int idx = voxels.get(fineCells ? fineKey(px, py, pz) : key(px, py, pz));
            if (idx < 0 || idx >= count || obs[idx] == 0) continue;
            int old = nearCm[idx];
            if (old <= 0 || newCm >= old * 0.8f || old - newCm < 15) continue;   // not clearly closer: leave it
            if (k == 0) {   // its own cell: the dot starts again from this closer, sure reading
                pos.put(idx * 3, wx); pos.put(idx * 3 + 1, wy); pos.put(idx * 3 + 2, wz);
                col.put(idx * 4, (byte) rgb[0]); col.put(idx * 4 + 1, (byte) rgb[1]); col.put(idx * 4 + 2, (byte) rgb[2]); col.put(idx * 4 + 3, (byte) seenAlpha(1));
                obs[idx] = 1; thru[idx] = 0; seenStamp[idx] = stampFrame; confBest[idx] = (byte) curConf; nearCm[idx] = (short) Math.min(32767, newCm);
                refined = true; tookOwn = true;
            } else {        // in front of or behind the new surface on the same line: superseded (cleared, kept aside at Done)
                obs[idx] = 0; col.put(idx * 4 + 3, (byte) 0); refined = true;
            }
            supersededTotal++;
        }
        return tookOwn;
    }
    private long depthKeptPx, depthAllPx; private final long[] zoneAll = new long[3], zoneSure = new long[3];   // depth pixels at PAINT_MIN or more, of all (for the finish line)
    static boolean painted(int conf) { return conf == 0 || conf >= PAINT_MIN; }   // 0 = no rating (older scans, other sources)
    /** What's drawn: the real colour or the confidence grey; dots below PAINT_MIN hidden (alpha 0); unrated dots black
     *  in the greys. The real colours stay in col, untouched. */
    private void fillGrey(int from, int to) {
        for (int i = from; i < to; i++) {
            int c = confBest[i] & 0xFF, a = col.get(i * 4 + 3) & 0xFF;
            int alpha = a == 0 || (c > 0 && c < PAINT_MIN) ? 0 : 255;
            int r, g, b;
            if (!greyNow()) { r = col.get(i * 4) & 0xFF; g = col.get(i * 4 + 1) & 0xFF; b = col.get(i * 4 + 2) & 0xFF; }
            else if (c == 0) { r = g = b = 0; }   // no rating: solid black
            else { r = g = b = c; }
            if (SHOW_MERGED && mergedDot[i] != 0) { r = 40; g = 120; b = 255; }   // debug: merged flat cells in blue
            // live: the alpha byte tells the dot shader the dot's cell (128+ = 0.8 cm) and how solid it is (1..127)
            if (alpha != 0 && !viewing) {
                alpha = 1 + (dotFine[i] != 0 ? 128 : 0) + Math.min(126, a * 126 / 255);
            }
            greyBuf.put(i * 4, (byte) r); greyBuf.put(i * 4 + 1, (byte) g); greyBuf.put(i * 4 + 2, (byte) b); greyBuf.put(i * 4 + 3, (byte) alpha);
        }
    }
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
    private long zoneOutPx;   // sure depth pixels outside the capture zone (no dots from them; the surface still uses them)
    private String lastError = "", depthSize = "";

    private final float[] proj = new float[16], view = new float[16], mvp = new float[16];

    ScanRenderer(MainActivity a) { activity = a; perf = new PerfLog(a); }

    /** Hardware use while scanning and after Done (PerfLog), kept with the scan as perf-scan.jsonl. */
    final PerfLog perf;
    private long lastFrameNs;

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
    /** Where the current scan's part of the log starts (rk 10 Nov: the 23:02 scan's log also held the 22:53 scan). */
    private int logScanStart;
    private final long t0 = System.currentTimeMillis();
    private final java.util.Set<String> loggedErrors = new java.util.HashSet<>();

    private int linkErrorsLogged;
    /** Shader programs that failed to link (logged once each, as ERROR lines in scan-log.txt). */
    private void logLinkErrors() { while (linkErrorsLogged < linkErrors.size()) log("ERROR shader program didn't link: " + linkErrors.get(linkErrorsLogged++)); }

    synchronized void log(String msg) {
        android.util.Log.i("RoomScape", msg); // also in logcat, for checking from the Mac
        if (log.length() > 300000) { log.delete(0, 150000); logScanStart = Math.max(0, logScanStart - 150000); }   // keep the latest part (it goes out with the diagnostics)
        log.append(String.format(java.util.Locale.US, "%6.1fs  %s%n", (System.currentTimeMillis() - t0) / 1000f, msg));
    }

    synchronized String logText() { return log.toString(); }
    int pointCount() { return count; }
    private volatile int liveDots;
    /** Dots in use (for the on-screen count): without the merged-away, erased and replaced ones still holding a slot. */
    int liveDotCount() { return liveDots > 0 ? liveDots : count; }
    int maxPoints() { return MAX_POINTS; }

    /** The app's event log as scan-log.txt in the scan folder (diagnostics in the .rmsc). */
    void saveLogInto(File dir) {
        String text; synchronized (this) { text = log.substring(Math.min(logScanStart, log.length())); }   // this scan's part only
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(new File(dir, "scan-log.txt"))) { o.write(text.getBytes("UTF-8")); }
        catch (Exception ignored) { }
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
        if (modes.size() == 1) activity.toast("This phone has no ARCore depth: scans will be rough (outlines and photos only).");
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
        try { // what Camera2 offers besides ARCore's camera: lenses, their focal lengths, and which can run together
            android.hardware.camera2.CameraManager cm = (android.hardware.camera2.CameraManager) activity.getSystemService(android.content.Context.CAMERA_SERVICE);
            StringBuilder b = new StringBuilder();
            for (String id : cm.getCameraIdList()) {
                android.hardware.camera2.CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Integer facing = ch.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                float[] fl = ch.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                android.util.SizeF sensor = ch.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                double hfov = fl != null && fl.length > 0 && sensor != null ? Math.toDegrees(2 * Math.atan(sensor.getWidth() / (2 * fl[0]))) : 0;
                b.append(String.format(java.util.Locale.US, " [id %s facing %s focal %s hfov %.0f° physical %s]", id, facing,
                        java.util.Arrays.toString(fl), hfov, ch.getPhysicalCameraIds()));
            }
            log("camera2 cameras:" + b);
            if (android.os.Build.VERSION.SDK_INT >= 30) log("camera2 concurrent sets: " + cm.getConcurrentCameraIds());
        } catch (Throwable e) { logError("camera2 list", new RuntimeException(e)); }
        modeIndex = 0;
        applyMode();
    }

    private void applyMode() {
        Config.DepthMode m = modes.get(modeIndex);
        Config config = new Config(session);
        config.setDepthMode(m);
        // Fixed focus (ARCore's default, recommended for tracking): auto focus hunting on a close plain surface can
        // cost tracking. Photos are taken from ~0.5-3 m, where the fixed focus is sharp enough.
        config.setFocusMode(FIXED_FOCUS ? Config.FocusMode.FIXED : Config.FocusMode.AUTO);
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
    boolean isImproving() { return improveDir != null; }

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

    /**
     * A reopened (or imported) scan has photos with depth but no surface mesh, so Photo would show points. Fuse each
     * photo's depth image into the TSDF in the background (like the live scan does), extract the surface and paint it
     * with the photos, so Photo looks like the iPhone's photo room. Progress shows in the processing card.
     */
    private void fuseSavedDepth() {
        // The sending phone's own mesh, when the package had one (an iPhone scan): the same surface as there.
        final File meshPly = currentDir == null ? null : new File(currentDir, "rmsc-extra/mesh.ply");
        if (meshPly != null && meshPly.exists()) {
            final int gen0 = surfGen;
            new Thread(() -> { try {
                Object[] mesh = readMeshPly(meshPly);
                final FloatBuffer pos = (FloatBuffer) mesh[0]; final ByteBuffer col = (ByteBuffer) mesh[1]; final int tris = (Integer) mesh[2];
                log("photo room surface from the package's mesh: " + tris + " triangles");
                if (tris > 0) glTasks.add(() -> { if (gen0 == surfGen) { meshPosAll = pos; meshColAll = col; meshVerts = tris * 3; meshTris = tris; buildPhotos(); } });
            } catch (Throwable e) { logError("package mesh", new RuntimeException(e)); } }, "package-mesh").start();
            return;
        }
        final java.util.List<PhotoRoom.Keyframe> kfs = new java.util.ArrayList<>(photos.keyframes);
        int withDepth = 0;
        for (PhotoRoom.Keyframe k : kfs) if (k.depth != null && k.dfx > 0) withDepth++;
        if (withDepth == 0) return;
        final int total = withDepth, gen = surfGen;
        new Thread(() -> { try {
            long t0 = System.currentTimeMillis();
            int done = 0;
            int[] grey = {170, 170, 170};
            float[] cam = new float[4], w = new float[4];
            for (PhotoRoom.Keyframe k : kfs) {
                if (gen != surfGen || !viewing) return;
                if (k.depth == null || k.dfx <= 0) continue;
                activity.updateProcess("Building the photo room", done / (float) total);
                synchronized (tsdf) { tsdf.beginFrame(); }
                float ox = k.pose[12], oy = k.pose[13], oz = k.pose[14];
                int step = Math.max(1, k.dw / 128);
                for (int v = 0; v < k.dh; v += step) for (int u = 0; u < k.dw; u += step) {
                    int mm = k.depth[v * k.dw + u] & 0xFFFF;
                    if (mm < 200 || mm > 5000) continue;
                    float d = mm * 0.001f;
                    cam[0] = (u + 0.5f - k.dcx) * d / k.dfx; cam[1] = (k.dcy - (v + 0.5f)) * d / k.dfy; cam[2] = -d; cam[3] = 1;
                    Matrix.multiplyMV(w, 0, k.pose, 0, cam, 0);
                    synchronized (tsdf) { tsdf.integrate(ox, oy, oz, w[0], w[1], w[2], grey); }
                }
                done++;
            }
            int tris;
            synchronized (tsdf) { tris = tsdf.extractMesh(); }
            log("photo room surface from " + total + " depth photos: " + tris + " triangles (" + (System.currentTimeMillis() - t0) + " ms)");
            final File saveTo = currentDir;
            if (tris > 0 && saveTo != null) glTasks.add(() -> { if (gen == surfGen) new Thread(() -> writeSurface(saveTo)).start(); });
            activity.updateProcess(null, -1);
            if (tris > 0) glTasks.add(() -> { if (gen == surfGen) { meshTris = tris; meshPosAll = null; buildPhotos(); } });
        } catch (Throwable e) { activity.updateProcess(null, -1); logError("photo room surface", new RuntimeException(e)); } }, "fuse-depth").start();
    }

    /** 0 = photo room (walls and floor with the photos on them, plus cut-outs), 1 = the raw scan. Rebuilds the photos. */
    String cycleViewMode() {
        viewMode = (viewMode + 1) % 2;
        if (viewing && count > 0) buildPhotos();
        return viewModeLabel();
    }

    String viewModeLabel() { return viewMode == 0 ? "View: photo room" : "View: scan"; }
    /**
     * Background: keeps this app's surface mesh (surface.ply, indexed binary PLY) and room outline (outline.json:
     * [x0, z0, x1, z1, height] per wall, floor at 0) in the scan folder, so a .rmsc export carries them like the
     * iPhone's (see Rmsc.write). Skipped when the scan came with another phone's mesh.
     */
    private volatile Tsdf.Band readbackRejected;

    void writeSurface(File dir) {
        try {
            if (new File(dir, "rmsc-extra/mesh.ply").exists()) return;
            FloatBuffer p = meshPosAll != null ? meshPosAll : tsdf.meshPos;
            ByteBuffer c = meshPosAll != null ? meshColAll : tsdf.meshCol;
            int verts = meshPosAll != null ? meshVerts : tsdf.meshVerts;
            writePly(dir, "surface.ply", p, c, verts);
            Tsdf.Band rjb = readbackRejected;
            if (rjb != null) {   // the read-back that was kept out, numbered, never overwritten
                readbackRejected = null;
                Tsdf rj = new Tsdf(Math.max(16, rjb.n + rjb.n / 2), tsdf.vs, tsdf.trunc, tsdf.carve); rj.minW = tsdf.minW;
                rj.restore(rjb);
                rj.extractMesh();
                int k = 1; while (new File(dir, "surface-readback-" + k + ".ply").exists()) k++;
                writePly(dir, "surface-readback-" + k + ".ply", rj.meshPos, rj.meshCol, rj.meshVerts);
            }
            writeOutline(dir);
        } catch (Exception e) { logError("surface save", e); }
    }

    private void writePly(File dir, String name, FloatBuffer p, ByteBuffer c, int verts) throws Exception {
        {
            if (p != null && c != null && verts >= 3) {
                int tris = verts / 3;
                java.util.HashMap<Long, Integer> at = new java.util.HashMap<>();
                float[] vp = new float[verts * 3]; byte[] vc = new byte[verts * 3]; int[] faces = new int[tris * 3]; int nv = 0;
                for (int i = 0; i < tris * 3; i++) {
                    float x = p.get(i * 3), y = p.get(i * 3 + 1), z = p.get(i * 3 + 2);
                    long key = (Math.round(x * 1e4) & 0x1FFFFFL) | ((Math.round(y * 1e4) & 0x1FFFFFL) << 21) | ((Math.round(z * 1e4) & 0x1FFFFFL) << 42);
                    Integer k = at.get(key);
                    if (k == null) {
                        k = nv++; at.put(key, k);
                        vp[k * 3] = x; vp[k * 3 + 1] = y; vp[k * 3 + 2] = z;
                        vc[k * 3] = c.get(i * 4); vc[k * 3 + 1] = c.get(i * 4 + 1); vc[k * 3 + 2] = c.get(i * 4 + 2);
                    }
                    faces[i] = k;
                }
                File tmp = new File(dir, "surface.ply.tmp");
                try (java.io.BufferedOutputStream o = new java.io.BufferedOutputStream(new java.io.FileOutputStream(tmp), 1 << 16)) {
                    o.write(("ply\nformat binary_little_endian 1.0\ncomment RoomScape Android surface\nelement vertex " + nv
                            + "\nproperty float x\nproperty float y\nproperty float z\nproperty uchar red\nproperty uchar green\nproperty uchar blue"
                            + "\nelement face " + tris + "\nproperty list uchar int vertex_indices\nend_header\n").getBytes("US-ASCII"));
                    ByteBuffer b = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < nv; i++) {
                        b.clear(); b.putFloat(vp[i * 3]).putFloat(vp[i * 3 + 1]).putFloat(vp[i * 3 + 2]).put(vc[i * 3]).put(vc[i * 3 + 1]).put(vc[i * 3 + 2]);
                        o.write(b.array(), 0, 15);
                    }
                    ByteBuffer f = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
                    for (int t = 0; t < tris; t++) { f.clear(); f.put((byte) 3).putInt(faces[t * 3]).putInt(faces[t * 3 + 1]).putInt(faces[t * 3 + 2]); o.write(f.array(), 0, 13); }
                }
                if (!tmp.renameTo(new File(dir, name))) tmp.delete();
                log(name + ": " + nv + " vertices, " + tris + " triangles");
            }
        }
    }

    private void writeOutline(File dir) {
        try {
            RoomModel r = room;
            if (roomReady && r != null && r.hasLayout && r.layout != null && r.layout.size() >= 3) {
                org.json.JSONArray w = new org.json.JSONArray();
                float h = r.ceilingY - r.floorY;
                for (RoomModel.Wall x : r.layout) { w.put((double) x.x0); w.put((double) x.z0); w.put((double) x.x1); w.put((double) x.z1); w.put((double) h); }
                try (java.io.FileOutputStream o = new java.io.FileOutputStream(new File(dir, "outline.json"))) { o.write(w.toString().getBytes("UTF-8")); }
            }
        } catch (Throwable e) { logError("surface save", new RuntimeException(e)); }
    }

    /** Flat colour = plane on (see Planes). */
    static boolean FLAT_WALLS = true;
    /** Camera focus for tracking: fixed (true) or auto (false, the photos' sharpness up close). */
    static boolean FIXED_FOCUS = true;
    /** Debug A/B: draw at most 30 fps. */
    volatile boolean fpsCap30 = true;
    /** Tracking health for the scan log's summary line. */
    private int healthLosses, healthMinFeatures = Integer.MAX_VALUE; private long healthMaxExposureNs;
    static boolean PHOTO_LINTEL = false;
    /** Doorway marks (DoorwayFinder) after the scan. */
    static boolean DOORWAYS = true;
    volatile java.util.List<DoorwayFinder.Doorway> doorways = new java.util.ArrayList<>();

    /** doorways.json in the scan folder: [{x0, z0, x1, z1, floorY, width, height, open}] (world, metres). */
    private void saveDoorways(File dir, java.util.List<DoorwayFinder.Doorway> ds) {
        try {
            org.json.JSONArray a = new org.json.JSONArray();
            for (DoorwayFinder.Doorway d : ds) a.put(new org.json.JSONObject().put("x0", d.x0).put("z0", d.z0).put("x1", d.x1).put("z1", d.z1)
                    .put("floorY", d.floorY).put("width", d.width).put("height", d.height).put("open", d.open).put("sure", d.sure));
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(new File(dir, "doorways.json"))) { o.write(a.toString(1).getBytes("UTF-8")); }
        } catch (Exception e) { logError("doorways save", e); }
    }
    private volatile java.util.List<Planes.Plane> fillPlanes; private volatile float[] lastPlanePts; private volatile int lastPlaneN;

    /**
     * Background: finds the plain flat surfaces in the scan's points (Planes, settings for camera depth) and returns
     * a copy of the mesh with the vertices on them pressed flat; the planes go in planes.json in the scan folder (for
     * the .rmsc). The scan itself is not changed.
     */
    private FloatBuffer flattenWalls(FloatBuffer mesh, int verts, File dir) {
        long t0 = System.currentTimeMillis();
        int n = count;
        float[] xyz = new float[n * 3]; byte[] rgb = new byte[n * 4];
        for (int i = 0; i < n * 3; i++) xyz[i] = pos.get(i);
        for (int i = 0; i < n * 4; i++) rgb[i] = col.get(i);
        // Camera depth is noisier than LiDAR: bigger cells and wider limits (tuned on rk's S24 scans, 9 Nov).
        Planes.CELL = 0.1f; Planes.MAX_FLAT = 0.15f; Planes.MAX_ANGLE_DEG = 30; Planes.MAX_OFFSET = 0.08f;
        Planes.INLIER = 0.04f; Planes.MIN_INLIERS = 0.75f; Planes.MIN_AREA = 0.4f; Planes.SNAP = 0.08f;
        java.util.Map<Long, Integer> cells = new java.util.HashMap<>();
        java.util.List<Planes.Plane> planes = Planes.find(xyz, rgb, 4, n, cells);
        float[] v = new float[verts * 3];
        for (int i = 0; i < v.length; i++) v[i] = mesh.get(i);
        int moved = Planes.snap(v, verts, planes, cells);
        fillPlanes = planes; lastPlanePts = xyz; lastPlaneN = n;
        log("flat walls: " + planes.size() + " planes, " + moved + " of " + verts + " mesh corners pressed flat (" + (System.currentTimeMillis() - t0) + " ms)");
        if (dir != null) try {
            float floor = roomReady ? room.floorY : boxMinY;
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(new File(dir, "planes.json"))) {
                o.write(Planes.toJson(planes, 0, floor).toString().getBytes("UTF-8"));
            }
        } catch (Exception e) { logError("planes save", e); }
        FloatBuffer out = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        out.put(v).position(0);
        return out;
    }

    /** Photo texturing (background): the simple photo room (walls + floor) or the full scan surface. */
    private void buildPhotos() {
        final RoomModel roomNow = room; final boolean ready = roomReady;
        if (meshPosAll == null) { meshPosAll = tsdf.meshPos; meshColAll = tsdf.meshCol; meshVerts = tsdf.meshVerts; }
        if (meshVerts == 0 || meshPosAll == null) { meshPosAll = null; return; } // no surface for this scan yet
        final FloatBuffer mp0 = meshPosAll; final ByteBuffer mc = meshColAll; final int mvAll = meshVerts;
        final File dirNow = currentDir;
        final boolean ownMesh = dirNow == null || !new File(dirNow, "rmsc-extra/mesh.ply").exists();
        photos.ready = false;
        // Photo texturing takes a few seconds on the phone, so it runs in the background; the view shows points until it's done.
        final boolean simple = viewMode == 0 && ready && roomNow.hasLayout;
        new Thread(() -> { try {
            long t1 = System.currentTimeMillis();
            // The Photo style is the scanned surface only, the same as the iPhone's photo room (rk 9 Nov: the two phones
            // must match): no flat proxy walls or gap fills from the room outline, which closed the doorway and drew
            // the near walls as a box. Holes stay holes. The simple room view (viewMode 0) is still the outline walls.
            // Flat colour = plane (rk 9 Nov): this phone's camera depth makes plain walls lumpy; press the parts that
            // are clearly one flat, single-coloured surface onto that plane (Planes). Not an iPhone's LiDAR mesh.
            FloatBuffer mp = mp0;
            ByteBuffer mcUse = mc; int mvUse = mvAll;
            if (!simple && FLAT_WALLS && ownMesh && mvAll > 0) {
                fillPlanes = null;
                mp = flattenWalls(mp0, mvAll, dirNow);
                // Plain walls and floors: enclosed gaps nothing was seen through get a flat patch (PlaneFill).
                java.util.List<Planes.Plane> pl = fillPlanes;
                if (PlaneFill.ENABLED && pl != null && !pl.isEmpty()) {
                    long tf = System.currentTimeMillis();
                    float[] mv = new float[mvAll * 3];
                    for (int i = 0; i < mv.length; i++) mv[i] = mp.get(i);
                    PlaneFill.Out f = PlaneFill.fill(pl, mv, mvAll, lastPlanePts, lastPlaneN, (x, y, z) -> tsdf.seenThrough(x, y, z));
                    log(String.format(java.util.Locale.US, "plane fill: %d gaps closed, %.2f m², %d triangles (%d ms)", f.holes, f.area, f.verts / 3, System.currentTimeMillis() - tf));
                    if (f.verts > 0) {
                        FloatBuffer np = ByteBuffer.allocateDirect((mvAll + f.verts) * 12).order(ByteOrder.nativeOrder()).asFloatBuffer();
                        np.put(mv).put(f.pos).position(0);
                        ByteBuffer nc = ByteBuffer.allocateDirect((mvAll + f.verts) * 4).order(ByteOrder.nativeOrder());
                        for (int i = 0; i < mvAll * 4; i++) nc.put(mc.get(i));
                        nc.put(f.col).position(0);
                        mp = np; mcUse = nc; mvUse = mvAll + f.verts;
                    }
                }
            }
            // Doorways (rk 10 Nov 11:35): openings in the wall planes found on the surface, the depth seen through them.
            // A scan opened again keeps the doorways it was saved with (rk 10 Nov 13:13: reopening gave different ones);
            // only a scan without doorways.json is searched.
            java.util.List<DoorwayFinder.Doorway> savedDoors = viewing && dirNow != null ? DoorwayFinder.load(new File(dirNow, "doorways.json")) : null;
            if (savedDoors != null) {
                doorways = savedDoors; activity.setDoorways(savedDoors); activity.loadMeasurements(dirNow);
                log("doorways: " + savedDoors.size() + " kept from the save");
            } else if (DOORWAYS && ownMesh && mvAll > 0) try {
                long td = System.currentTimeMillis();
                float[] vv = new float[mvAll * 3];
                for (int i = 0; i < vv.length; i++) vv[i] = mp0.get(i);
                float[] walls = DoorwayFinder.wallsFromSurface(vv, mvAll);
                // See-through from the photos' own depth (beyond the wall there) or the surface's record of rays passing:
                // with the surface on the GPU the CPU record is thin (rk 10 Nov 12:03: the 80 cm door, found at
                // 0.82 x 2.00 m from the photos' depth, was rejected on the phone as "not seen through").
                java.util.ArrayList<OutlineFill.Photo> phs = new java.util.ArrayList<>();
                for (PhotoRoom.Keyframe k : new java.util.ArrayList<>(photos.keyframes)) {
                    if (k.depth == null) continue;
                    OutlineFill.Photo ph = new OutlineFill.Photo();
                    ph.pose = k.pose; ph.fx = k.fx; ph.fy = k.fy; ph.cx = k.cx; ph.cy = k.cy; ph.w = (int) k.w; ph.h = (int) k.h;
                    ph.depth = k.depth; ph.dw = k.dw; ph.dh = k.dh; ph.dfx = k.dfx; ph.dfy = k.dfy; ph.dcx = k.dcx; ph.dcy = k.dcy;
                    phs.add(ph);
                }
                float[][] views = new float[phs.size()][];
                for (int i = 0; i < phs.size(); i++) views[i] = OutlineFill.invert(phs.get(i).pose);
                StringBuilder why = new StringBuilder();
                java.util.List<DoorwayFinder.Doorway> ds = DoorwayFinder.find(vv, mvAll, walls, walkFloorY(),
                        (x, y, z) -> tsdf.seenThrough(x, y, z) || OutlineFill.judge(phs, views, new float[]{x, y, z}, null) == 1, why);
                log("doorways: " + ds.size() + " from " + walls.length / 4 + " wall planes, " + phs.size() + " photos with depth (" + (System.currentTimeMillis() - td) + " ms)");
                // The lintel from the photos (EdgeRefine): offline on rk's 80 x 204 door it read 2.00-2.05 m where the
                // surface gave 2.06-2.17. The jambs stay as found (the photo edges weren't reliable there yet).
                // (off: on the 13:18 scan the photo edge moved both lintels from 2.02/2.04 m, right, to 2.14 m, the
                // architrave's top; offline it had helped on other scans)
                if (PHOTO_LINTEL && !ds.isEmpty()) try {
                    long te = System.currentTimeMillis();
                    java.util.List<EdgeRefine.Photo> eph = edgePhotos();
                    for (DoorwayFinder.Doorway d : ds) {
                        float len = (float) Math.hypot(d.x1 - d.x0, d.z1 - d.z0); if (len < 0.3f) continue;
                        EdgeRefine.Result r = EdgeRefine.refine(eph, d.x0, d.z0, (d.x1 - d.x0) / len, (d.z1 - d.z0) / len, d.floorY, 0, len, d.height);
                        log(String.format(java.util.Locale.US, "doorway lintel from %d photos: %.2f -> %.2f m (edge %.0f) %s", r.photos, d.height, r.top, r.sTop, r.why));
                        if (r.photos >= 3 && r.sTop > 15 && Math.abs(r.top - d.height) < 0.15f) d.height = r.top;
                    }
                    log("doorway photo edges: " + (System.currentTimeMillis() - te) + " ms");
                } catch (Throwable e) { logError("doorway edges", new RuntimeException(e)); }
                if (why.length() > 0) log("doorways, left out:" + why);
                for (DoorwayFinder.Doorway d : ds) log("  " + d);
                doorways = ds;
                activity.setDoorways(ds);
                activity.loadMeasurements(dirNow);
                if (dirNow != null) saveDoorways(dirNow, ds);
            } catch (Throwable e) { logError("doorways", new RuntimeException(e)); }
            RoomProxies rp = simple ? RoomProxies.build(roomNow, pos, col, count) : new RoomProxies();
            float[] proxy = rp.tris; byte[] proxyCol = rp.cols;
            int pv = proxy.length / 3;
            // The drawn floor onto the trusted floor (ARCore's plane): near-level triangles within 12 cm of it. The points
            // are not changed.
            if (!simple && mvUse > 0) {
                float fy = walkFloorY(); int flat = 0;
                if (mp == mp0) { FloatBuffer cp = ByteBuffer.allocateDirect(mvUse * 12).order(ByteOrder.nativeOrder()).asFloatBuffer(); for (int i = 0; i < mvUse * 3; i++) cp.put(i, mp0.get(i)); mp = cp; }
                for (int t = 0; t < mvUse / 3; t++) {
                    int o = t * 9;
                    float y0 = mp.get(o + 1), y1 = mp.get(o + 4), y2 = mp.get(o + 7);
                    if (Math.abs(y0 - fy) > 0.12f || Math.abs(y1 - fy) > 0.12f || Math.abs(y2 - fy) > 0.12f) continue;
                    float ux = mp.get(o + 3) - mp.get(o), uy = y1 - y0, uz = mp.get(o + 5) - mp.get(o + 2);
                    float vx = mp.get(o + 6) - mp.get(o), vy = y2 - y0, vz = mp.get(o + 8) - mp.get(o + 2);
                    float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
                    float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                    if (nl < 1e-9f || Math.abs(ny) / nl < 0.9f) continue;
                    mp.put(o + 1, fy); mp.put(o + 4, fy); mp.put(o + 7, fy); flat++;
                }
                if (flat > 0) log(String.format(java.util.Locale.US, "floor: %d triangles laid on ARCore's floor (y %.3f)", flat, fy));
            }
            boolean[] ceil = simple ? new boolean[0] : ceilingTriangles(mp, mvUse / 3);
            // Ragged bits inside an open doorway (see-through noise fused into the opening): left out of the photo room.
            java.util.List<DoorwayFinder.Doorway> dws = doorways;
            if (!simple && dws != null && !dws.isEmpty()) {
                int cut = 0;
                for (int t = 0; t < ceil.length; t++) {
                    if (ceil[t]) continue;
                    float x = (mp.get(t * 9) + mp.get(t * 9 + 3) + mp.get(t * 9 + 6)) / 3, y = (mp.get(t * 9 + 1) + mp.get(t * 9 + 4) + mp.get(t * 9 + 7)) / 3, z = (mp.get(t * 9 + 2) + mp.get(t * 9 + 5) + mp.get(t * 9 + 8)) / 3;
                    for (DoorwayFinder.Doorway d : dws) {
                        float wx = d.x1 - d.x0, wz = d.z1 - d.z0, wl = (float) Math.sqrt(wx * wx + wz * wz); if (wl < 0.1f) continue;
                        float u = ((x - d.x0) * wx + (z - d.z0) * wz) / wl, off = (x - d.x0) * d.nx + (z - d.z0) * d.nz;
                        if (Math.abs(off) < 0.12f && u > 0.03f && u < wl - 0.03f && y > d.floorY + 0.05f && y < d.floorY + d.height - 0.03f) { ceil[t] = true; cut++; break; }
                    }
                }
                if (cut > 0) log("doorways: " + cut + " ragged triangles inside the openings left out");
            }
            int keepTris = 0;
            for (boolean c : ceil) if (!c) keepTris++;
            int mv = simple ? 0 : keepTris * 3;
            FloatBuffer all = ByteBuffer.allocateDirect(Math.max(1, mv + pv) * 12).order(ByteOrder.nativeOrder()).asFloatBuffer();
            ByteBuffer allCol = ByteBuffer.allocateDirect(Math.max(4, (mv + pv) * 4)).order(ByteOrder.nativeOrder());
            for (int t = 0; t < ceil.length; t++) {
                if (ceil[t]) continue;
                for (int i = t * 9; i < t * 9 + 9; i++) all.put(mp.get(i));
                for (int i = t * 12; i < t * 12 + 12; i++) allCol.put(mcUse.get(i));
            }
            all.put(proxy).position(0);
            allCol.put(proxyCol).position(0);
            if (!simple) log("photo room: " + ceil.length + " triangles, " + (ceil.length - keepTris) + " ceiling left out");
            if (PHOTO_SURFACE) photos.build(all, allCol, mv + pv, mv, simple);   // off: no photo texturing (rk 10 Nov 15:34)
            log((simple ? "photo room: " : "photos: ") + photos.keyframes.size() + " keyframes, " + photos.texturedTris + " textured / " + photos.plainTris
                    + " plain triangles, " + pv / 3 + " wall/floor cells tried (" + (System.currentTimeMillis() - t1) + " ms)");
        } catch (Throwable e) { logError("photos", new RuntimeException(e)); } }).start();
    }


    /**
     * The ceiling triangles of a triangle soup, left out of the photo room exactly as the iPhone does
     * (PhotoLayout.ceilingTriangles): flat (|normal.y| > 0.7) and within 30 cm of the top (99.5th percentile vertex).
     */
    static boolean[] ceilingTriangles(FloatBuffer p, int tris) {
        boolean[] out = new boolean[tris];
        if (tris == 0) return out;
        float[] ys = new float[tris * 3];
        for (int i = 0; i < ys.length; i++) ys[i] = p.get(i * 3 + 1);
        java.util.Arrays.sort(ys);
        float top = ys[Math.min(ys.length - 1, (int) ((long) ys.length * 995 / 1000))];
        for (int t = 0; t < tris; t++) {
            int b = t * 9;
            float ax = p.get(b), ay = p.get(b + 1), az = p.get(b + 2);
            float ux = p.get(b + 3) - ax, uy = p.get(b + 4) - ay, uz = p.get(b + 5) - az;
            float vx = p.get(b + 6) - ax, vy = p.get(b + 7) - ay, vz = p.get(b + 8) - az;
            float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            float cy = (ay + p.get(b + 4) + p.get(b + 7)) / 3;
            out[t] = len > 1e-12f && cy > top - 0.3f && Math.abs(ny / len) > 0.7f;
        }
        return out;
    }

    /** No splats any more (rk 10 Nov: Lighting is on the photo room, Points draw the points themselves): the step that
     *  built them (a few seconds after Done) only finishes the automatic processing now. Old splat files stay unread. */
    private static final boolean BUILD_SPLATS = false;

    /** Turn the current points into surfel splats in the background (a few seconds on the phone). */
    private void buildSurfels() {
        if (!BUILD_SPLATS) {
            ++surfGen; surfDone = null; surfBase = null; surfPending = null; surfCount = 0; surfShown = null;
            if (autoStage == 3) glTasks.add(() -> { if (autoStage != 3) return; autoStage = 5; autoProgress("Done", 1f); autosave(); });
            return;
        }
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
            if (autoStage == 3) glTasks.add(() -> {
                if (autoStage != 3) return;
                // demo: no refining (training made the splats worse every time), finish here
                autoStage = 5; autoProgress("Done", 1f); autosave();
            });
            log("splats: " + n + " surfels (" + s.flat + " on flat surfaces, " + s.curved + " curved/edges, " + s.lonely + " lonely) in "
                    + (System.currentTimeMillis() - t0) + " ms");
        } catch (Throwable e) { logError("splats", new RuntimeException(e)); if (autoStage == 3) { autoStage = 5; autoProgress("Done", 1f); } } }).start();
    }

    // ---- shot lighting preview (see Lighting) ----
    final Lighting lighting = new Lighting();
    RoomEdits roomEdits = new RoomEdits();
    volatile boolean planningPreview;
    private String planningLoadError;
    private String planningScanKey;

    void requirePlanningScan() {
        ItemCatalog.require(isViewing() && currentDir != null && roomReady, "Open a saved, processed scan first (wait for autosave after Done).");
        if(!currentDir.getName().equals(planningScanKey)) loadPlanning(currentDir);
        ItemCatalog.require(planningLoadError == null, "Planning overlay could not be read; original scan is intact. " + planningLoadError);
    }
    void prepareScannedItems() throws Exception {
        requirePlanningScan(); roomEdits.capture(room.boxes); planningPreview = true; savePlanning();
    }
    void savePlanning() throws Exception {
        requirePlanningScan(); RoomEditStore.write(activity, currentDir, roomEdits.encode(lighting));
    }
    void planningChanged() {
        try { if(currentDir != null && isViewing() && planningLoadError == null) savePlanning(); }
        catch(Exception e) { activity.toast("Planning edits NOT saved: " + e.getMessage()); }
    }
    void placeCatalog(ItemCatalog.Item item, double[] manual, float bottom) throws Exception {
        requirePlanningScan(); ItemCatalog.require(item.complete() || manual != null, "Size unknown: supply manual dimensions first");
        ItemCatalog.require(Float.isFinite(bottom) && bottom >= 0 && bottom <= 20, "Base height must be 0–20 m");
        // rk 7 Nov: placed gear becomes an Objects item (a see-through box of its real size you can drag and turn),
        // not a solid Lighting prop: the solid box filled the whole screen when the view was near it.
        double[] size = Lighting.catalogProp(item, manual, bottom, target[0], target[2]).size(); // {w, h, d} in m
        float fy = roomReady ? room.floorY : planner.isReady() ? planner.floorY : boxMinY;
        SceneObjects.Obj o = new SceneObjects.Obj();
        o.name = item.title(); o.hu = (float) size[0] / 2; o.hv = (float) size[2] / 2;
        o.y0 = fy + bottom; o.y1 = o.y0 + (float) size[1]; o.cx = target[0]; o.cz = target[2];
        o.fromCatalog = true; o.onFloor = bottom <= 0.01f; o.n = 0; o.pts = new float[0]; o.cols = new byte[0];
        objects.list.add(o); objects.selected = objects.list.size() - 1; objects.edited = true; objects.active = true;
        autosave();
    }
    void focusDetected(String id) {
        RoomEdits.Detected d = roomEdits.find(id); if(d == null)return;
        roomEdits.selectedId = id; planningPreview = true; target[0] = d.cx; target[1] = (d.y0 + d.y1) / 2; target[2] = d.cz;
    }
    private void loadPlanning(File dir) {
        planningScanKey = dir.getName();
        try {
            String json = RoomEditStore.read(activity, dir);
            if(json != null) { roomEdits = RoomEdits.decode(json, lighting); lighting.floorY = room.floorY; planningPreview = roomEdits.captured;
                int migrated = 0;
                // gear placed with the old solid Lighting props becomes see-through Objects boxes (lighting is parked)
                synchronized (lighting) {
                    for (java.util.Iterator<Lighting.Prop> itp = lighting.props.iterator(); itp.hasNext(); ) {
                        Lighting.Prop pr = itp.next();
                        if (pr.catalog == null) continue;
                        double[] sz = pr.size();
                        SceneObjects.Obj o = new SceneObjects.Obj();
                        o.name = pr.catalog.title(); o.hu = (float) sz[0] / 2; o.hv = (float) sz[2] / 2;
                        o.y0 = room.floorY + pr.bottom; o.y1 = o.y0 + (float) sz[1]; o.cx = pr.x; o.cz = pr.z;
                        o.cosA = (float) Math.cos(Math.toRadians(pr.yawDeg)); o.sinA = (float) Math.sin(Math.toRadians(pr.yawDeg));
                        o.fromCatalog = true; o.n = 0; o.pts = new float[0]; o.cols = new byte[0];
                        objects.list.add(o); objects.edited = true; migrated++;
                        itp.remove();
                    }
                    lighting.enabled = false;
                }
                if (migrated > 0) { try { savePlanning(); } catch (Exception ignored) { } autosave(); }
                activity.planningModeChanged(); }
        } catch(Exception e) { planningLoadError = e.getMessage(); activity.updateStatus("Planning overlay could not be opened (original scan intact): " + e.getMessage()); }
    }

    private final java.util.List<float[]> fixtures = new java.util.ArrayList<>(); // bright spots of the room's own lights

    /** GL thread: switch the lighting preview on/off. On: a seated person and key + fill (from the shot plan if there is one). */
    String toggleLighting() {
        if (lighting.enabled) { lighting.enabled = false; return "Lighting"; }
        // Lit: the photo room (rk 9 Nov), or the splats when there's no photo room.
        if (!(photos.ready && meshTris > 0)) { activity.updateStatus("Lighting needs the photo room: it shows once the scan's photos are painted on."); return "Lighting"; }
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
            if(!lighting.enabled && p.catalog == null) continue;
            // people turn to the camera
            float yaw = p.yawDeg;
            if (p.isPerson() && lighting.haveCam) yaw = (float) Math.toDegrees(Math.atan2(lighting.camX - p.x, lighting.camZ - p.z));
            switch (p.kind) {
                case CATALOG:
                    double[] size = p.size();
                    propBox(m, p, yaw, 0, p.bottom + (float)size[1] / 2, 0, (float)size[0], (float)size[1], (float)size[2], new float[]{0.22f,0.65f,0.75f}, fy);
                    break;
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
            drawLabel(m, p.x, fy + h, p.z, p.label(), true);
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

    void lightingDragEnd() { lightingStatus(); planningChanged(); }

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
    // "Continue scan" (demo): the saved room is found automatically from the points, no corner taps
    private volatile boolean contAuto;
    private volatile ContinueAlign.Base contBase;
    private volatile boolean contBusy;
    private long contLastMs;
    private float[] contPrev; private int contAgree;
    private volatile float contScore, contLockedScore;
    private float appendSimplify;            // > 0: the saved scan is merged in simplified (one point per cell of this size)
    private int depthOnlyFirst = -1;         // > 0: the depth AI only looks at the first photos (the new pass)

    interface SaveDone { void done(File dir, String error); }

    /** GL thread: save the scan under this name (into dir, or a new folder). The thumbnail is the view on screen. */
    void requestSave(String name, File dir, SaveDone done) {
        pendingSave = () -> {
            try {
                ScanStore.Data d = new ScanStore.Data();
                d.name = name;
                d.lux = roomLux() > 0 ? roomLux() : loadedLux;
                Surfels ss = surfShown;
                if (ss != null && ss.trained && ss.n > 0) {
                    int sn = ss.n; d.splN = sn; d.splPos = new float[sn * 3]; d.splNrm = new float[sn * 4]; d.splCol = new byte[sn * 4];
                    for (int i = 0; i < sn * 3; i++) d.splPos[i] = ss.pos.get(i);
                    for (int i = 0; i < sn * 4; i++) { d.splNrm[i] = ss.nrm.get(i); d.splCol[i] = ss.col.get(i); }
                }
                try { if (objects.edited) d.objects = objects.toJson().toString(); } catch (Exception e) { logError("objects save", e); }
                d.hall = hallDone;
                try { d.outletsChecked = outlets.checked; if (!outlets.list.isEmpty()) d.outlets = outlets.toJson(); if (!roomItems.list.isEmpty()) d.items = roomItems.toJson(); } catch (Exception e) { logError("outlets save", e); }
                // The GL thread only copies (in bulk) what it must; the slow parts (picking the points, the thumbnail,
                // new photos' JPEGs) run on the save thread. rk 9 Nov: the old per-point copy stalled the camera frames
                // for long enough that the scan took them for map jumps and paused itself, more often as it grew.
                // thumbnail: the frame just drawn
                final int tw = width, th = height;
                final java.nio.ByteBuffer px = java.nio.ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
                GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
                drainDepth();
                final int cn = count;
                // never a scan with nothing in it (11 Nov: an empty state saved as a new scan hid the real one)
                if (cn == 0) { log("save refused: no points"); done.done(null, "nothing scanned to save"); return; }
                final float[] pa = new float[cn * 3];
                FloatBuffer pd = pos.duplicate(); pd.position(0); pd.get(pa, 0, cn * 3);
                final byte[] ca = new byte[cn * 4];
                ByteBuffer cd = col.duplicate(); cd.position(0); cd.get(ca, 0, cn * 4);
                final byte[] oa = java.util.Arrays.copyOf(obs, cn);
                final byte[] sga = seg == null ? null : java.util.Arrays.copyOf(seg, cn);
                final byte[] cfa = java.util.Arrays.copyOf(confBest, cn); final short[] nca = java.util.Arrays.copyOf(nearCm, cn);
                final byte[] mga = java.util.Arrays.copyOf(mergedDot, cn);
                final java.util.List<Object> jpgs = ScanExport.readKeyframesRaw(photos, width, height);
                final java.util.List<Runnable> encode = new java.util.ArrayList<>();
                for (int i = 0; i < photos.keyframes.size() && i < jpgs.size(); i++) {
                    PhotoRoom.Keyframe k = photos.keyframes.get(i);
                    ScanStore.Kf o = new ScanStore.Kf();
                    Object raw = jpgs.get(i);
                    if (raw instanceof byte[]) o.jpg = (byte[]) raw;
                    else encode.add(() -> { o.jpg = ScanExport.encodeKeyframe((android.graphics.Bitmap) raw); k.jpg = o.jpg; });
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
                        px.position(0);
                        android.graphics.Bitmap full = android.graphics.Bitmap.createBitmap(tw, th, android.graphics.Bitmap.Config.ARGB_8888);
                        full.copyPixelsFromBuffer(px);
                        android.graphics.Matrix flip = new android.graphics.Matrix();
                        float sc = 360f / tw;
                        flip.postScale(sc, -sc);
                        d.thumb = android.graphics.Bitmap.createBitmap(full, 0, 0, tw, th, flip, true);
                        full.recycle();
                        int live = 0;
                        java.io.ByteArrayOutputStream low = new java.io.ByteArrayOutputStream(); int lowN = 0;
                        for (int i = 0; i < cn; i++) if (oa[i] != 0 && painted(cfa[i] & 0xFF)) live++;
                        d.n = live; d.pos = new float[live * 3]; d.col = new byte[live * 3];
                        d.seg = sga == null ? null : new byte[live];
                        d.dotConf = new byte[live * 3];   // with every save (Done, autosave, a kept Cancel): matches points.bin
                        d.merged = new byte[live];
                        for (int i = 0, j = 0; i < cn; i++) {
                            if (oa[i] == 0) continue; // cleared by see-through
                            if (!painted(cfa[i] & 0xFF)) {   // only weak readings: kept aside, not a scan point
                                for (int v : new int[]{Float.floatToIntBits(pa[i * 3]), Float.floatToIntBits(pa[i * 3 + 1]), Float.floatToIntBits(pa[i * 3 + 2])}) { low.write(v >>> 24); low.write(v >>> 16); low.write(v >>> 8); low.write(v); }
                                low.write(ca[i * 4]); low.write(ca[i * 4 + 1]); low.write(ca[i * 4 + 2]); low.write(cfa[i]); lowN++;
                                continue;
                            }
                            if (d.seg != null) d.seg[j] = sga[i];
                            d.dotConf[j * 3] = cfa[i]; d.dotConf[j * 3 + 1] = (byte) (nca[i] >> 8); d.dotConf[j * 3 + 2] = (byte) nca[i];
                            d.merged[j] = mga[i];
                            d.pos[j * 3] = pa[i * 3]; d.pos[j * 3 + 1] = pa[i * 3 + 1]; d.pos[j * 3 + 2] = pa[i * 3 + 2];
                            d.col[j * 3] = ca[i * 4]; d.col[j * 3 + 1] = ca[i * 4 + 1]; d.col[j * 3 + 2] = ca[i * 4 + 2];
                            j++;
                        }
                        d.lowConf = low.toByteArray();
                        for (Runnable r : encode) r.run();
                        File out = ScanStore.save(activity, dir, d);
                        currentDir = out; currentName = name;
                        log("saved '" + name + "': " + d.n + " points, " + d.kfs.size() + " photos -> " + out + (d.lowConf.length > 0 ? " (+" + d.lowConf.length / 16 + " weak ones aside in lowconf-points.bin)" : ""));
                        done.done(out, null);
                    } catch (Throwable e) { logError("save", new RuntimeException(e)); done.done(null, String.valueOf(e.getMessage())); }
                }).start();
            } catch (Throwable e) { logError("save", new RuntimeException(e)); done.done(null, String.valueOf(e.getMessage())); }
        };
    }

    /** GL thread: open a saved scan in the viewer. Photos stream in one by one. */
    void loadScan(File dir, Runnable done) {
        runAfterDone();   // the last scan's logs into its own folder, not the one being opened
        reset();
        if (flatTiles.load(dir)) log("flat tiles: " + flatTiles.countShown() + " from the save");
        loadFeatureHeights(dir);
        improveDir = null; improveT = null; contAuto = false; contBase = null;
        idle = false; viewing = true; sensors.active = false;
        lastPlanes = new java.util.ArrayList<>();
        currentDir = dir;
        activity.updateStatus("Opening the scan...");
        appendSaved(dir, true, () -> {
            enterViewer();
            if (loadedSplats != null) { // show the improved model it was saved with, not a fresh rebuild from the raw points
                if (BUILD_SPLATS) { surfGen++; surfDone = loadedSplats; surfBase = loadedSplats; surfPending = loadedSplats; log("opened with " + loadedSplats.n + " trained splats"); }
                loadedSplats = null;
            }
            loadPlanning(dir); if (done != null) done.run();
            if (meshTris == 0) fuseSavedDepth(); // saved and imported scans keep no surface: rebuild it for Photo
            photoThumbWanted = !thumbKindIs(dir, "points"); // the list picture from the Points view (rk 10 Nov 15:22: a broken photo surface gave empty ones): redo once
            if (!outlets.checked) glTasks.add(() -> findOutlets(false)); // older scans: look once
            if (AUTO_PROCESS && DEMO_FAR && !hallDone && activity.claudeKey() != null && !photos.keyframes.isEmpty()) // older scans: place the back wall once, no rescan
                glTasks.add(() -> { if (!hallDone) { hallDone = true; estimateHall(surfGen, false); } });
        });
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
                // the dots' confidence (dot-confidence.bin, since 263152158), when it matches these points: for the greys
                // in the Points view of a reopened scan (rk 22:03; before, reopened dots had none and all showed mid grey)
                byte[] dc = null;
                File dcf = new File(dir, "dot-confidence.bin");
                if (dcf.exists() && appendSimplify <= 0) try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(dcf)))) {
                    int m = in.readInt(); if (m == d.n) { dc = new byte[m * 3]; in.readFully(dc); }
                } catch (Exception e) { logError("dot-confidence.bin", e); }
                final byte[] dotConf = dc;
                byte[] mg = null; File mgf = new File(dir, "merged-dots.bin");
                if (mgf.exists() && appendSimplify <= 0) try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(mgf)))) {
                    int m = in.readInt(); if (m == d.n) { mg = new byte[m]; in.readFully(mg); }
                } catch (Exception e) { logError("merged-dots.bin", e); }
                final byte[] mergedFile = mg;
                if (appendSimplify > 0) simplifyLoaded(d, appendSimplify);
                if (currentName == null || replaceMarks) currentName = d.name;
                loadedAny = true;
                if (d.lux > 0) { loadedLux = d.lux; Lighting.ROOM_LUX = d.lux; }
                glTasks.add(() -> {
                    if (gen != surfGen) return;
                    drainDepth();
                    int[] rgb = new int[3];
                    for (int i = 0; i < d.n && count < MAX_POINTS; i++) {
                        float x = d.pos[i * 3], y = d.pos[i * 3 + 1], z = d.pos[i * 3 + 2];
                        long vk = key(x, y, z);
                        if (voxels.get(vk) >= 0) continue;
                        rgb[0] = d.col[i * 3] & 255; rgb[1] = d.col[i * 3 + 1] & 255; rgb[2] = d.col[i * 3 + 2] & 255;
                        voxels.put(vk, count);
                        addPoint(x, y, z, rgb);
                        obs[count - 1] = 2;
                        if (mergedFile != null) mergedDot[count - 1] = mergedFile[i];
                        if (dotConf != null) { confBest[count - 1] = dotConf[i * 3]; nearCm[count - 1] = (short) (((dotConf[i * 3 + 1] & 255) << 8) | (dotConf[i * 3 + 2] & 255)); }
                        // a saved dot: the same rule rebuildVoxelIndex uses to put it on the fine grid
                        dotFine[count - 1] = (byte) (FINE_NEAR && nearCm[count - 1] > 0 && nearCm[count - 1] < FINE_NEAR_M * 100 ? 1 : 0);
                        if (d.seg != null && i < d.seg.length) seg[count - 1] = d.seg[i];
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
                    if (replaceMarks && d.splN > 0 && !DEMO_FAR) { // demo: refined splats were always worse, rebuild plain ones
                        Surfels ls = Surfels.empty(d.splN);
                        ls.pos.put(d.splPos).position(0); ls.raw.put(d.splPos).position(0); ls.nrm.put(d.splNrm).position(0); ls.col.put(d.splCol).position(0);
                        ls.trained = true; loadedSplats = ls;
                    }
                    if (replaceMarks) { outlets.fromJson(d.outlets, d.outletsChecked); roomItems.fromJson(d.items); hallDone = d.hall; }
                    if (replaceMarks && d.objects != null) try { objects.fromJson(new org.json.JSONArray(d.objects), pos, col, count); } catch (Exception e) { logError("objects load", e); }
                });
                int room = Math.max(0, PhotoRoom.MAX_KEYFRAMES + 30 - photos.keyframes.size()); // a Continue scan adds to them
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

    /** Continue scan: the saved scan's points merged to one per cell (average position and colour), so it costs far less memory. */
    private void simplifyLoaded(ScanStore.Data d, float cell) {
        int n = d.n, cap = 1 << 21, maxCells = cap * 6 / 10;
        LongIntMap m = new LongIntMap(cap);
        int cn = 0;
        float[] sp = new float[Math.min(n, maxCells) * 3]; int[] sc = new int[Math.min(n, maxCells) * 3], cnt = new int[Math.min(n, maxCells)];
        for (int i = 0; i < n; i++) {
            float x = d.pos[i * 3], y = d.pos[i * 3 + 1], z = d.pos[i * 3 + 2];
            long k = ContinueAlign.key(x, y, z, cell);
            int j = m.get(k);
            if (j < 0) { if (cn >= cnt.length) continue; j = cn++; m.put(k, j); }
            sp[j * 3] += x; sp[j * 3 + 1] += y; sp[j * 3 + 2] += z;
            sc[j * 3] += d.col[i * 3] & 255; sc[j * 3 + 1] += d.col[i * 3 + 1] & 255; sc[j * 3 + 2] += d.col[i * 3 + 2] & 255;
            cnt[j]++;
        }
        float[] p = new float[cn * 3]; byte[] c = new byte[cn * 3];
        for (int j = 0; j < cn; j++) {
            float w = cnt[j];
            for (int a = 0; a < 3; a++) { p[j * 3 + a] = sp[j * 3 + a] / w; c[j * 3 + a] = (byte) Math.round(sc[j * 3 + a] / w); }
        }
        log(String.format(java.util.Locale.US, "continue: saved scan simplified %,d -> %,d points (%.0f cm cells)", n, cn, cell * 100));
        d.pos = p; d.col = c; d.n = cn;
    }

    /** GL thread, while continuing a scan: look for the saved room every 1.5 s until found, then keep following it. */
    private void maybeContinueAlign(long nowMs) {
        ContinueAlign.Base b = contBase;
        if (!contAuto || b == null || contBusy || count < 1500) return;
        boolean locked = improveT != null;
        if (nowMs - contLastMs < (locked ? 4000 : 1500)) return;
        contLastMs = nowMs;
        int n = count, step = Math.max(1, n / 20000), k = 0;
        float[] sm = new float[(n / step + 1) * 3];
        for (int i = 0; i < n; i += step) { if (obs[i] == 0) continue; sm[k * 3] = pos.get(i * 3); sm[k * 3 + 1] = pos.get(i * 3 + 1); sm[k * 3 + 2] = pos.get(i * 3 + 2); k++; }
        final int sn = k; final float[] cur = improveT;
        contBusy = true;
        new Thread(() -> {
            try {
                long t0 = System.currentTimeMillis();
                ContinueAlign.Probes pr = ContinueAlign.probes(sm, sn);
                if (pr.coarseCells < 60) return; // too little seen yet
                if (cur != null) {
                    ContinueAlign.Result r = ContinueAlign.findNear(b, pr, cur);
                    if (r != null && r.score >= 0.8f * contLockedScore && improveDir != null) { improveT = r.t; contScore = r.score; contLockedScore = Math.max(contLockedScore * 0.9f, r.score); }
                    return;
                }
                ContinueAlign.Result r = ContinueAlign.findGlobal(b, pr);
                if (r == null) return;
                contScore = r.score;
                boolean clear = r.score >= 0.33f && r.score >= 1.4f * r.second;
                float[] p = contPrev;
                boolean same = p != null && Math.abs(Math.atan2(r.t[1], r.t[0]) - Math.atan2(p[1], p[0])) < Math.toRadians(4) && Math.hypot(r.t[2] - p[2], r.t[4] - p[4]) < 0.25f && Math.abs(r.t[3] - p[3]) < 0.2f;
                contAgree = clear ? (same ? contAgree + 1 : 1) : 0;
                contPrev = r.t;
                log(String.format(java.util.Locale.US, "continue: match %.0f%% (next best %.0f%%), %d cells seen, %s, %d ms",
                        r.score * 100, r.second * 100, pr.coarseCells, clear ? (contAgree >= 2 ? "recognised" : "checking") : "not sure", System.currentTimeMillis() - t0));
                if (contAgree >= 2 && improveDir != null && contAuto) {
                    improveT = r.t; contLockedScore = r.score;
                    activity.toast(String.format(java.util.Locale.US, "Recognised the saved room (%.0f%% match). Ready, keep scanning: the saved part is shown in blue.", r.score * 100));
                }
            } catch (Throwable e) { logError("continue align", new RuntimeException(e)); }
            finally { contBusy = false; }
        }).start();
    }

    private static final float[] CONT_GHOST_COL = {0.45f, 0.8f, 1f, 0.35f};
    private final float[] contInv = new float[16], contMvp = new float[16];

    /** While continuing: the saved scan (simplified) drawn where it was recognised. */
    private void drawContinueGhost(float[] m) {
        float[] t = improveT; ContinueAlign.Base b = contBase;
        if (!contAuto || t == null || b == null || b.ghostN == 0) return;
        float[] T = {t[0], 0, t[1], 0, 0, 1, 0, 0, -t[1], 0, t[0], 0, t[2], t[3], t[4], 1};
        Matrix.invertM(contInv, 0, T, 0);
        Matrix.multiplyMM(contMvp, 0, m, 0, contInv, 0);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        planDraw(contMvp, b.ghost, GLES20.GL_POINTS, CONT_GHOST_COL, 5f * height / 1000f);
    }

    /** GL thread: a fresh scan (not tied to a save yet). */
    private final Object afterDoneLock = new Object();
    private Runnable afterDoneWork;

    /** The last scan's after-Done saving (logs, video, diagnostics into its folder), if it hasn't run yet. */
    void runAfterDone() {
        Runnable r;
        synchronized (afterDoneLock) { r = afterDoneWork; afterDoneWork = null; }
        if (r != null) try { r.run(); } catch (Exception e) { logError("after Done", e); }
    }

    void startNewScan() {
        runAfterDone();   // before reset(): it saves the last scan's buffers
        reset();
        perf.start(new File(activity.getCacheDir(), "perf-scan-" + System.currentTimeMillis() + ".jsonl"), "scanning");
        improveDir = null; improveT = null; contAuto = false; contBase = null; currentDir = null; currentName = null;
        idle = false;
        // Each scan starts in the best depth mode again (a fallback in an earlier scan shouldn't stick).
        if (session != null && modeIndex != 0 && !modes.isEmpty()) { modeIndex = 0; try { applyMode(); } catch (Exception e) { logError("configure", e); } }
    }

    /**
     * GL thread: Cancel on the scan screen (rk 9 Nov: discard, as on the iPhone). A new scan is thrown away, including
     * the folder its autosave made. A Continue scan pass is only dropped: the saved scan it was adding to is never
     * touched (passes aren't autosaved into it; see maybeAutosaveWhileScanning).
     */
    void discardScan(Runnable after) {
        idle = true; // no more autosaves
        if (saving) { glTasks.add(() -> discardScan(after)); return; }
        File dir = improveDir == null ? currentDir : null;
        // Data is gold (11 Nov: a teammate's 1-million-point scans were deleted by Cancel): a scan with real content is
        // kept in the list as "<name> (cancelled)"; only a short one is deleted.
        if (dir != null && count >= 50000) {
            String nm = (currentName != null ? currentName : dir.getName()) + " (cancelled)";
            try { ScanStore.rename(dir, nm); } catch (Exception e) { logError("keep cancelled scan", e); }
            log("scan cancelled: kept as '" + nm + "' (" + count + " points) in " + dir.getName());
            // its diagnostics too, as after Done (11 Nov 20:12: a cancelled scan kept no log, pose track or screen video)
            try { saveEvents(dir); saveLogInto(dir); screenTargetDir = dir; finishScreenVideo(); keepRebuildFrames(dir); } catch (Exception e) { logError("keep cancelled scan's diagnostics", e); }
            dir = null;
        }
        log(dir != null ? "scan cancelled: deleting its autosave " + dir.getName() : improveDir != null ? "continue scan cancelled: saved scan kept as it was" : "scan cancelled");
        perf.stop(); if (perf.file() != null) { perf.file().delete(); new File(perf.file().getParent(), perf.file().getName().replace(".jsonl", ".summary.json")).delete(); }
        reset();
        improveDir = null; improveT = null; contAuto = false; contBase = null; currentDir = null; currentName = null;
        idle = true;
        if (dir != null) ScanStore.delete(dir);
        if (after != null) activity.runOnUiThread(after);
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
        contAuto = DEMO_FAR; contBase = null; contBusy = false; contPrev = null; contAgree = 0; contScore = 0; contLockedScore = 0; contLastMs = 0;
        new Thread(() -> {
            try {
                ScanStore.Data d = ScanStore.load(dir);
                improveName = d.name;
                if (contAuto) {
                    long t0 = System.currentTimeMillis();
                    ContinueAlign.Base b = ContinueAlign.buildBase(d.pos, d.n);
                    contBase = b;
                    log("continue: saved scan " + d.n + " points -> " + b.qn + " match cells, " + b.ghostN + " overlay points (" + (System.currentTimeMillis() - t0) + " ms)");
                }
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
        if (contAuto) return "Continuing \"" + (improveName == null ? "scan" : improveName) + "\": point the phone at a part you already scanned. You'll get a message when the saved room is recognised.";
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
        boolean wasAuto = contAuto;
        contAuto = false; contBase = null;
        if (t == null) {
            currentDir = null; currentName = null; // keep it as its own scan
            activity.toast(wasAuto ? "The saved room wasn't recognised, so this is kept as a new scan." : "Not lined up with the saved scan (no matching corners), so this is kept as a new scan.");
            enterViewer();
            startAuto();
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
        drainFuse();
        tsdf.clear(); detail.clear(); zones.clear(); meshPosAll = null; meshTris = 0; meshVerts = 0; cutouts.clear();
        lastPlanes = new java.util.ArrayList<>(); // ARCore planes were in the new scan's coordinates
        rebuildVoxelIndex();
        uploaded = 0;
        final int added = count;
        log("improve: merging " + count + " new points into " + dir);
        activity.updateStatus("Merging with the saved scan...");
        currentDir = dir;
        depthOnlyFirst = photos.keyframes.size(); // the saved photos were processed already
        appendSimplify = wasAuto ? 0.04f : 0;
        appendSaved(dir, true, () -> {
            appendSimplify = 0;
            enterViewer();
            startAuto();
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
        if (depthOnlyFirst > 0 && depthOnlyFirst < kfs.size()) kfs.subList(depthOnlyFirst, kfs.size()).clear();
        if (kfs.isEmpty()) { activity.updateStatus("No photos were saved during this scan, so there is nothing for the depth AI to look at."); return "Depth AI"; }
        training = false;
        // JPEG every photo now (GL thread), so the pass can keep reading photos while the app is in the background
        try { ScanExport.readKeyframes(photos, width, height); } catch (Throwable e) { logError("depth jpg", new RuntimeException(e)); }
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
                    if (k.jpg != null) img[0] = ObjectNamer.uprightFromJpg(k, map);
                    final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                    if (img[0] == null) glTasks.add(() -> { try { img[0] = ObjectNamer.readUpright(k, width, height, map); } catch (Throwable e) { logError("depth read", new RuntimeException(e)); } finally { latch.countDown(); } });
                    boolean got;
                    if (img[0] != null) latch.countDown();
                    while (!(got = latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) && paused && depthRunning) { /* in the background: wait for the app to come back */ }
                    if (!got || img[0] == null) continue;
                    long depthT0 = System.nanoTime();
                    float[] D = net.run(img[0]);
                    perf.note("depth AI (CPU, " + DepthNet.class.getSimpleName() + ")", (System.nanoTime() - depthT0) / 1e6);
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
                    float cdMax = 0; for (float q : cd) cdMax = Math.max(cdMax, q);
                    float[] ab = fitInverseDepth(cd, cr, cuu, cvv);
                    if (ab != null) lastAb = ab;
                    else if (DEMO_FAR && lastAb != null) { ab = lastAb; log("depth AI: photo " + ki + " looks out past what the phone measured, using the last photo's scale"); }
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
                        float dd = inv > (DEMO_FAR ? 1f / FAR_MAX : 0.15f) ? 1f / inv : 0;
                        gd[gv * GU + gu] = dd > 0 && (fillsGap(cd, cw, chh, X / 8, Y / 8, dd) || (DEMO_FAR && farGap(cd, cw, chh, X / 8, Y / 8, dd, cdMax))) ? dd : 0;
                        gc[gv * GU + gu] = pix[Y * iw + X];
                    }
                    final float[] P = new float[GU * GV * 3]; final byte[] C = new byte[GU * GV * 3];
                    int m = 0;
                    float[] pm = k.pose;
                    for (int gv = 1; gv < GV - 1; gv++) for (int gu = 1; gu < GU - 1; gu++) {
                        int g = gv * GU + gu;
                        float d = gd[g];
                        if (d <= 0.2f || d > (DEMO_FAR ? FAR_MAX : 6f)) continue;
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
                    if (autoStage == 1) autoProgress(String.format(java.util.Locale.US, "Step 1 of 3 · Sharpening depth from your photos (%d/%d)", ki + 1, kfs.size()), 0.02f + 0.48f * (ki + 1) / kfs.size());
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
                autoFailed("depth AI");
            } finally {
                if (net != null) net.close();
                depthRunning = false;
            }
        }).start();
        return "Stop depth AI";
    }

    /**
     * Robust fit of 1/depth = a * disparity + b + c * x + d * y over the filled cells (x, y = photo position, -0.5..0.5).
     * Weighted by 1/depth^2: phone depth-from-motion error grows with distance squared, so the near points set the scale
     * and the net keeps its own shape further out. The x/y tilt terms are off (TILT_FIT): they copied ARCore's tilted far
     * walls into the AI depth (scan1047 back wall 60 deg off vertical with them, 33-45 deg without and near-weighted).
     * Returns {a, b, c, d, median relative error} or null.
     */
    /**
     * The AI's depth only fills gaps: where ARCore measured nothing in this 8 px cell, but a measured cell within
     * 2 cells agrees with the AI depth (6 % + 3 cm) and none disagrees. Everywhere else ARCore's measurement wins.
     * Before this, AI points were added on top of measured surfaces and stacked them into layers (scan1150 pillar).
     */
    /**
     * DEMO (rk, auditorium from the podium): beyond what the phone measured, keep the AI's guess out to FAR_MAX.
     * Only where nothing nearby was measured and it is further than anything measured in this photo. Scale is
     * extrapolated from the near points, so far walls are an estimate (maybe 10-20 % off at 20 m), not a measurement.
     */
    static final boolean DEMO_FAR = true;
    private static float[] lastAb; // scale of the last photo that had enough measured points
    static final float FAR_MAX = 30f;
    static boolean farGap(float[] cd, int cw, int ch, int cx, int cy, float d, float cdMax) {
        if (d <= 3.5f || d > FAR_MAX) return false; // everything the phone did not measure, from 3.5 m out
        for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
            int x = cx + dx, y = cy + dy;
            if (x < 0 || y < 0 || x >= cw || y >= ch) continue;
            if (cd[y * cw + x] > 0) return false;
        }
        return true;
    }

    static boolean fillsGap(float[] cd, int cw, int ch, int cx, int cy, float d) {
        if (d > 3.5f) return false;
        if (cx < 0 || cy < 0 || cx >= cw || cy >= ch || cd[cy * cw + cx] > 0) return false;
        boolean ok = false;
        for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
            int x = cx + dx, y = cy + dy;
            if (x < 0 || y < 0 || x >= cw || y >= ch) continue;
            float n = cd[y * cw + x];
            if (n <= 0) continue;
            if (Math.abs(n - d) < 0.03f + 0.06f * d) ok = true; else return false;
        }
        return ok;
    }

    static final boolean TILT_FIT = false;
    static float[] fitInverseDepth(float[] cd, float[] cr, float[] cu, float[] cv) {
        int n = 0;
        for (float d : cd) if (d > 0) n++;
        if (n < 40) return null;
        int k = TILT_FIT && n >= 150 ? 4 : 2;
        double[][] f = new double[n][];
        double[] y = new double[n], w = new double[n], w0 = new double[n], e = new double[n];
        int j = 0;
        for (int i = 0; i < cd.length; i++) if (cd[i] > 0) {
            f[j] = k == 4 ? new double[]{cr[i], 1, cu[i], cv[i]} : new double[]{cr[i], 1};
            y[j] = 1.0 / cd[i]; w0[j] = 1.0 / (cd[i] * cd[i]); w[j] = w0[j]; j++;
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
            for (int i = 0; i < n; i++) w[i] = w0[i] / Math.max(1, e[i] / sc);
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
        drainDepth();
        int[] rgb = new int[3];
        for (int i = 0; i < m && count < MAX_POINTS; i++) {
            float x = P[i * 3], y = P[i * 3 + 1], z = P[i * 3 + 2];
            rgb[0] = C[i * 3] & 255; rgb[1] = C[i * 3 + 1] & 255; rgb[2] = C[i * 3 + 2] & 255;
            float ddx = x - camX, ddy = y - camY, ddz = z - camZ;
            if (ddx * ddx + ddy * ddy + ddz * ddz < 64f) { drainFuse(); tsdf.integrate(camX, camY, camZ, x, y, z, rgb); } // far estimates: points only
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
            gpuSyncAll();
            drainFuse();
            meshTris = tsdf.extractMesh();
            meshPosAll = null;
        } catch (Throwable e) { logError("depth surface", new RuntimeException(e)); }
        activity.depthDone();
        activity.updateStatus(String.format(java.util.Locale.US, "Depth AI done: %,d points added. Cleaning up now...", depthAdded));
        if (autoStage == 1) autoStage = 2;
        if (DEMO_FAR && !hallDone && activity.claudeKey() != null && !photos.keyframes.isEmpty()) { hallDone = true; estimateHall(gen, true); return; }
        startCleanUp();
    }

    /**
     * DEMO (rk, hall from the stage): the depth AI's far distances flatten out (its fit is scaled by near points, and
     * 1/d = a r + b caps d near 1/b: scan 17:10 had nothing past 8 m in a ~18 m hall). Claude estimates the back wall
     * from the photos and the measured width; everything beyond 4 m straight ahead is stretched so the far end lands there.
     */
    private boolean hallDone;
    private void estimateHall(int gen, boolean thenCleanUp) {
        final java.util.List<PhotoRoom.Keyframe> kfs = new java.util.ArrayList<>(photos.keyframes);
        try { ScanExport.readKeyframes(photos, width, height); } catch (Throwable e) { logError("hall jpg", new RuntimeException(e)); }
        // the main direction the phone looked, and the middle of where it stood
        float fx = 0, fz = 0, cx = 0, cy = 0, cz = 0;
        for (PhotoRoom.Keyframe k : kfs) { fx -= k.pose[8]; fz -= k.pose[10]; cx += k.pose[12]; cy += k.pose[13]; cz += k.pose[14]; }
        float fl = (float) Math.hypot(fx, fz);
        if (fl < 1e-3f) { if (thenCleanUp) startCleanUp(); return; }
        final float ux = fx / fl, uz = fz / fl, ox = cx / kfs.size(), oy = cy / kfs.size(), oz = cz / kfs.size();
        final int n = count;
        float[] al = new float[n], lat = new float[n], ys = new float[n];
        for (int i = 0; i < n; i++) {
            float dx = pos.get(i * 3) - ox, dz = pos.get(i * 3 + 2) - oz;
            al[i] = dx * ux + dz * uz; lat[i] = -dx * uz + dz * ux; ys[i] = pos.get(i * 3 + 1);
        }
        float[] sa = al.clone(), sl = lat.clone(), sy = ys.clone();
        java.util.Arrays.sort(sa); java.util.Arrays.sort(sl); java.util.Arrays.sort(sy);
        final float farNow = sa[(int) (n * 0.995f)], width = sl[(int) (n * 0.97f)] - sl[(int) (n * 0.03f)];
        final float floor = roomReady ? room.floorY : sy[(int) (n * 0.01f)], top = sy[(int) (n * 0.99f)];
        final String measured = String.format(java.util.Locale.US,
                "- room width across the view (wall to wall where measured): %.1f m\n- camera height above the floor: %.1f m\n"
                + "- highest measured point above the floor: %.1f m\n- the phone moved %.1f m sideways while scanning\n"
                + "- the phone's own estimate of the far end (unreliable past 5 m): %.1f m",
                width, oy - floor, top - floor, sideways(kfs, ux, uz), farNow);
        // photos looking straight out (level, along the main direction), spread across the stage
        java.util.List<PhotoRoom.Keyframe> pick = new java.util.ArrayList<>();
        java.util.List<PhotoRoom.Keyframe> sorted = new java.util.ArrayList<>(kfs);
        sorted.sort((a, b) -> Float.compare(-(-a.pose[8] * ux - a.pose[10] * uz) + Math.abs(a.pose[9]), -(-b.pose[8] * ux - b.pose[10] * uz) + Math.abs(b.pose[9])));
        for (PhotoRoom.Keyframe k : sorted) { if (pick.size() >= 4) break; if (k.jpg != null) pick.add(k); }
        final String key = activity.claudeKey();
        activity.updateProcess("Claude is estimating how deep the hall is", 0.5f);
        new Thread(() -> {
            double[] est = null;
            try {
                java.util.List<String> b64 = new java.util.ArrayList<>();
                for (PhotoRoom.Keyframe k : pick) {
                    android.graphics.Bitmap img = ObjectNamer.uprightFromJpg(k, new android.graphics.Matrix());
                    if (img != null) { b64.add(ClaudeHelper.jpegBase64(img, 1280)); img.recycle(); }
                }
                if (!b64.isEmpty()) est = ClaudeHelper.estimateHall(key, b64, measured);
            } catch (Throwable e) { logError("hall estimate", new RuntimeException(e)); }
            final double[] e = est;
            glTasks.add(() -> {
                if (gen != surfGen) return;
                float target = e == null ? Float.NaN : (float) e[0];
                log("hall: " + measured.replace("\n", "; ") + " -> Claude: back wall " + target + " m (" + ClaudeHelper.lastHallWhy + ")");
                final float s0 = 4f;
                if (!Float.isNaN(target)) takeUndo("Claude's back wall");
                if (!Float.isNaN(target) && farNow > s0 + 0.5f && target > farNow) {
                    float k = Math.min(4f, (target - s0) / (farNow - s0));
                    int moved = 0;
                    for (int i = 0; i < count; i++) {
                        float dx = pos.get(i * 3) - ox, dz = pos.get(i * 3 + 2) - oz;
                        float a = dx * ux + dz * uz;
                        if (a <= s0) continue;
                        float add = (a - s0) * (k - 1);
                        pos.put(i * 3, pos.get(i * 3) + ux * add); pos.put(i * 3 + 2, pos.get(i * 3 + 2) + uz * add);
                        moved++;
                    }
                    rebuildVoxelIndex(); uploaded = 0; refineCursor = 0; surfCount = 0;
                    // a reopened scan shows its trained splats: move them the same way
                    for (Surfels ss : new Surfels[]{surfDone, surfShown, surfBase}) {
                        if (ss == null || ss.stretched == gen + 1) continue;
                        ss.stretched = gen + 1;
                        for (int i = 0; i < ss.n; i++) {
                            float dx = ss.pos.get(i * 3) - ox, dz = ss.pos.get(i * 3 + 2) - oz;
                            float a = dx * ux + dz * uz;
                            if (a <= s0) continue;
                            float add = (a - s0) * (k - 1);
                            ss.pos.put(i * 3, ss.pos.get(i * 3) + ux * add); ss.pos.put(i * 3 + 2, ss.pos.get(i * 3 + 2) + uz * add);
                            ss.raw.put(i * 3, ss.raw.get(i * 3) + ux * add); ss.raw.put(i * 3 + 2, ss.raw.get(i * 3 + 2) + uz * add);
                        }
                    }
                    if (surfDone != null) surfPending = surfDone; // re-send to the GPU
                    log(String.format(java.util.Locale.US, "hall: stretched %d far points x%.2f so the back wall is at %.1f m (was %.1f m)", moved, k, target, farNow));
                    activity.toast(String.format(java.util.Locale.US, "Claude puts the back wall about %.0f m away", target));
                }
                if (!Float.isNaN(target) && target > 2f) {
                    double[] ee = e;
                    roughRoom(ox, oz, ux, uz, target, ee != null && ee.length > 2 ? (float) ee[2] : Float.NaN);
                }
                if (thenCleanUp) startCleanUp(); else { activity.updateProcess(null, -1); surfCount = 0; surfShown = null; buildSurfels(); autosave(); }
            });
        }).start();
    }

    /**
     * DEMO: commit to a rough box room. The back wall is a plane at Claude's distance (points past it are put onto
     * it), the side walls sit where the scan saw them, the floor is the floor; every 6 cm patch of those surfaces
     * that has no scan point within ~10 cm gets one, coloured like the rest of that surface. So the far end is a
     * best guess that stays put, and the gaps between scanned spots are closed.
     */
    private void roughRoom(float ox, float oz, float ux, float uz, float back, float ceilEst) {
        int n = count;
        if (n < 1000) return;
        float[] al = new float[n], lat = new float[n], ys = new float[n];
        for (int i = 0; i < n; i++) {
            float dx = pos.get(i * 3) - ox, dz = pos.get(i * 3 + 2) - oz;
            al[i] = dx * ux + dz * uz; lat[i] = -dx * uz + dz * ux; ys[i] = pos.get(i * 3 + 1);
        }
        float[] sl = lat.clone(), sy = ys.clone(), sa = al.clone();
        java.util.Arrays.sort(sl); java.util.Arrays.sort(sy); java.util.Arrays.sort(sa);
        float left = sl[(int) (n * 0.02f)], right = sl[(int) (n * 0.98f)];
        float floor = roomReady ? room.floorY : sy[(int) (n * 0.01f)];
        float ceil = sy[(int) (n * 0.99f)];
        if (!Float.isNaN(ceilEst) && ceilEst > 2f && ceilEst < 20f) ceil = Math.max(ceil, floor + ceilEst);
        float front = Math.max(sa[(int) (n * 0.01f)], -1f);
        // 1) stick to the back wall: anything past it goes onto it
        int snapped = 0;
        for (int i = 0; i < n; i++) {
            if (al[i] <= back) continue;
            float add = back - al[i];
            pos.put(i * 3, pos.get(i * 3) + ux * add); pos.put(i * 3 + 2, pos.get(i * 3 + 2) + uz * add);
            al[i] = back; snapped++;
        }
        // 2) what is there already, in 10 cm cells
        java.util.HashSet<Long> have = new java.util.HashSet<>();
        for (int i = 0; i < n; i++) have.add(cell10(al[i], lat[i], ys[i]));
        // colour of each surface: median of the points near it
        int[] cBack = surfaceColour(al, lat, ys, n, 0, back, 0.6f), cLeft = surfaceColour(al, lat, ys, n, 1, left, 0.4f),
              cRight = surfaceColour(al, lat, ys, n, 1, right, 0.4f), cFloor = surfaceColour(al, lat, ys, n, 2, floor, 0.1f);
        int added = 0; final float st = 0.06f;
        int[] rgb = new int[3];
        // back wall (lateral x height), side walls (along x height), floor (along x lateral)
        for (int pass = 0; pass < 4; pass++) {
            float a0, a1, b0, b1; int[] c;
            if (pass == 0) { a0 = left; a1 = right; b0 = floor; b1 = ceil; c = cBack; }
            else if (pass == 1 || pass == 2) { a0 = Math.max(front, 0.5f); a1 = back; b0 = floor; b1 = ceil; c = pass == 1 ? cLeft : cRight; }
            else { a0 = Math.max(front, 0.5f); a1 = back; b0 = left; b1 = right; c = cFloor; }
            for (float a = a0; a <= a1; a += st) for (float b = b0; b <= b1; b += st) {
                float A, L, Y;
                if (pass == 0) { A = back; L = a; Y = b; }
                else if (pass == 1) { A = a; L = left; Y = b; }
                else if (pass == 2) { A = a; L = right; Y = b; }
                else { A = a; L = b; Y = floor; }
                if (near10(have, A, L, Y)) continue;
                if (count >= MAX_POINTS) break;
                float x = ox + A * ux - L * uz, z = oz + A * uz + L * ux;
                float j = ((Float.floatToIntBits(x * 7.3f + z * 3.1f + Y) & 15) - 8) * 1.5f; // a little texture, not flat paint
                rgb[0] = clamp255(c[0] + j); rgb[1] = clamp255(c[1] + j); rgb[2] = clamp255(c[2] + j);
                addPoint(x, Y, z, rgb);
                obs[count - 1] = 2; col.put((count - 1) * 4 + 3, (byte) 255);
                added++;
            }
        }
        rebuildVoxelIndex(); uploaded = 0; refineCursor = 0;
        log(String.format(java.util.Locale.US, "rough room: back wall at %.1f m (%d points put onto it), width %.1f m, height %.1f m, %d gap points added",
                back, snapped, right - left, ceil - floor, added));
        activity.toast(String.format(java.util.Locale.US, "Rough room: %.0f × %.0f m, back wall about %.0f m away. Tap Undo if it looks wrong.", right - left, back - Math.max(front, 0), back));
    }

    private static int clamp255(float v) { return v < 0 ? 0 : v > 255 ? 255 : Math.round(v); }

    private static long cell10(float a, float l, float y) {
        long ia = (long) Math.floor(a / 0.1f) + 100000, il = (long) Math.floor(l / 0.1f) + 100000, iy = (long) Math.floor(y / 0.1f) + 100000;
        return (ia << 40) | (il << 20) | iy;
    }

    private static boolean near10(java.util.HashSet<Long> have, float a, float l, float y) {
        for (int da = -1; da <= 1; da++) for (int dl = -1; dl <= 1; dl++) for (int dy = -1; dy <= 1; dy++)
            if (have.contains(cell10(a + da * 0.1f, l + dl * 0.1f, y + dy * 0.1f))) return true;
        return false;
    }

    /** Median colour of the points within band of a surface (axis 0 = along, 1 = lateral, 2 = height). */
    private int[] surfaceColour(float[] al, float[] lat, float[] ys, int n, int axis, float at, float band) {
        int m = 0; int[] r = new int[Math.min(n, 20000)], g = new int[r.length], b = new int[r.length];
        int step = Math.max(1, n / 200000);
        for (int i = 0; i < n && m < r.length; i += step) {
            float v = axis == 0 ? al[i] : axis == 1 ? lat[i] : ys[i];
            if (Math.abs(v - at) > band) continue;
            r[m] = col.get(i * 4) & 255; g[m] = col.get(i * 4 + 1) & 255; b[m] = col.get(i * 4 + 2) & 255; m++;
        }
        if (m == 0) return new int[]{150, 150, 150};
        java.util.Arrays.sort(r, 0, m); java.util.Arrays.sort(g, 0, m); java.util.Arrays.sort(b, 0, m);
        return new int[]{r[m / 2], g[m / 2], b[m / 2]};
    }

    // ---- undo the last AI change (demo) ----
    private float[] undoPos; private byte[] undoCol, undoObs; private int undoCount; private Surfels undoSurf; private String undoWhat;

    /** GL thread: remember the scan as it is now, before an AI change. */
    private void takeUndo(String what) {
        int n = count;
        undoPos = new float[n * 3]; undoCol = new byte[n * 4]; undoObs = new byte[n];
        for (int i = 0; i < n * 3; i++) undoPos[i] = pos.get(i);
        for (int i = 0; i < n * 4; i++) undoCol[i] = col.get(i);
        System.arraycopy(obs, 0, undoObs, 0, n);
        undoCount = n; undoWhat = what;
        Surfels s = surfShown;
        undoSurf = s == null ? null : s.copy();
    }

    /** GL thread: put back what was there before the last AI change. Returns a message. */
    String undo() {
        if (undoPos == null) return "Nothing to undo";
        if (training) training = false;
        int n = undoCount;
        for (int i = 0; i < n * 3; i++) pos.put(i, undoPos[i]);
        for (int i = 0; i < n * 4; i++) col.put(i, undoCol[i]);
        System.arraycopy(undoObs, 0, obs, 0, n);
        count = n; uploaded = 0; refineCursor = 0;
        rebuildVoxelIndex();
        Surfels s = undoSurf;
        if (s != null) { surfGen++; surfDone = s; surfBase = s; surfPending = s; trainer = null; }
        else { surfCount = 0; surfShown = null; buildSurfels(); }
        String what = undoWhat;
        undoPos = null; undoCol = null; undoObs = null; undoSurf = null; undoWhat = null;
        autosave();
        return "Undid: " + what;
    }

    private static float sideways(java.util.List<PhotoRoom.Keyframe> kfs, float ux, float uz) {
        float lo = 1e9f, hi = -1e9f;
        for (PhotoRoom.Keyframe k : kfs) { float l = -k.pose[12] * uz + k.pose[14] * ux; lo = Math.min(lo, l); hi = Math.max(hi, l); }
        return hi - lo;
    }

    private volatile boolean cleaning;

    // ---- automatic processing after a scan: depth AI -> clean-up -> splats -> a round of training ----
    static final boolean AUTO_PROCESS = false; // demo: keep only what the live scan captured (no depth AI, clean-up, back-wall guess or refining)
    static final int AUTO_STEPS = 400;           // training steps (~4 min on the S24; 150 left the splats blotchy)
    private volatile int autoStage;             // 0 off, 1 depth AI, 2 clean-up, 3 splats, 4 training, 5 done

    private void autoProgress(String label, float frac) {
        activity.updateProcess(label, frac);
        if (frac >= 1f && !outlets.checked) glTasks.add(() -> findOutlets(false));
    }

    // ---- power outlets: Claude looks for them in the scan's photos, the scan's points place them in the room ----
    final Outlets outlets = new Outlets();
    final RoomItems roomItems = new RoomItems();
    private volatile boolean outletsRunning;
    static final int OUTLET_PHOTOS = 36, OUTLET_BATCH = 6;

    /** GL thread: search the photos of this scan for power outlets (once per scan; again if forced). */
    void findOutlets(boolean force) {
        if (outletsRunning || (outlets.checked && !force) || count == 0) return;
        final java.util.List<PhotoRoom.Keyframe> all = new java.util.ArrayList<>(photos.keyframes);
        if (all.isEmpty()) return;
        final String key = activity.claudeKey();
        if (key == null) { activity.outletsNeedKey(); return; }
        try { ScanExport.readKeyframes(photos, width, height); } catch (Throwable e) { logError("outlet jpg", new RuntimeException(e)); return; }
        // spread the photos over the scan, at most OUTLET_PHOTOS of them
        final java.util.List<PhotoRoom.Keyframe> kfs = new java.util.ArrayList<>();
        float stepK = Math.max(1f, all.size() / (float) OUTLET_PHOTOS);
        for (float f = 0; f < all.size() && kfs.size() < OUTLET_PHOTOS; f += stepK) kfs.add(all.get((int) f));
        final int n0 = count, step = Math.max(1, n0 / 300000);
        final float[] pts = new float[(n0 / step + 1) * 3];
        int pn = 0;
        for (int i = 0; i < n0; i += step) { if (obs[i] == 0) continue; pts[pn * 3] = pos.get(i * 3); pts[pn * 3 + 1] = pos.get(i * 3 + 1); pts[pn * 3 + 2] = pos.get(i * 3 + 2); pn++; }
        final int ptsN = pn, gen = surfGen;
        outletsRunning = true;
        final java.util.List<Outlets.Outlet> before = outlets.snapshot();
        if (force) synchronized (outlets) { outlets.list.clear(); }
        final float floorY = roomReady ? room.floorY : lighting.floorY;
        new Thread(() -> {
            int found = 0; boolean ok = true;
            java.util.List<RoomItems.View> objViews = new java.util.ArrayList<>();
            try {
                for (int b = 0; b < kfs.size() && gen == surfGen; b += OUTLET_BATCH) {
                    activity.updateProcess(String.format(java.util.Locale.US, "Finding power outlets (%d/%d photos)", b, kfs.size()), 0.05f + 0.9f * b / kfs.size());
                    java.util.List<PhotoRoom.Keyframe> batch = new java.util.ArrayList<>();
                    java.util.List<android.graphics.Matrix> maps = new java.util.ArrayList<>();
                    java.util.List<int[]> sizes = new java.util.ArrayList<>();
                    java.util.List<String> b64 = new java.util.ArrayList<>();
                    for (int j = b; j < Math.min(kfs.size(), b + OUTLET_BATCH); j++) {
                        PhotoRoom.Keyframe k = kfs.get(j);
                        if (k.jpg == null) continue;
                        android.graphics.Matrix map = new android.graphics.Matrix();
                        android.graphics.Bitmap img = ObjectNamer.uprightFromJpg(k, map);
                        if (img == null) continue;
                        batch.add(k); maps.add(map); sizes.add(new int[]{img.getWidth(), img.getHeight()});
                        b64.add(ClaudeHelper.jpegBase64(img, 1024));
                        img.recycle();
                    }
                    if (b64.isEmpty()) continue;
                    java.util.List<ClaudeHelper.OutletDet> dets = ClaudeHelper.detectOutlets(key, b64);
                    for (ClaudeHelper.OutletDet d : dets) {
                        if (d.object) {
                            RoomItems.View v = RoomItems.view(batch.get(d.photo), maps.get(d.photo), sizes.get(d.photo)[0], sizes.get(d.photo)[1], d);
                            if (v != null) objViews.add(v);
                            continue;
                        }
                        float[] w = Outlets.lift(batch.get(d.photo), maps.get(d.photo), sizes.get(d.photo)[0], sizes.get(d.photo)[1], d, pts, ptsN);
                        if (w != null) { outlets.add(w, d); found++; }
                    }
                }
            } catch (Throwable e) {
                ok = false; logError("outlets", new RuntimeException(e));
                if (force) synchronized (outlets) { if (outlets.list.isEmpty()) outlets.list.addAll(before); }
            }
            if (gen == surfGen && !objViews.isEmpty()) {
                try {
                    activity.updateProcess("Separating the objects Claude found", 0.97f);
                    long t0 = System.currentTimeMillis();
                    java.util.List<RoomItems.Item> items = RoomItems.build(objViews, pts, ptsN, floorY);
                    roomItems.set(items);
                    StringBuilder sb = new StringBuilder();
                    for (RoomItems.Item it : items) sb.append("\n  ").append(RoomItems.describe(it));
                    log("objects: " + objViews.size() + " sightings -> " + items.size() + " objects in " + (System.currentTimeMillis() - t0) + " ms" + sb);
                    glTasks.add(() -> { if (gen == surfGen) objects.fromItems(roomItems.snapshot(), pos, col, count); });
                } catch (Throwable e) { logError("objects", new RuntimeException(e)); }
            }
            outletsRunning = false;
            if (gen != surfGen) return;
            if (ok) outlets.checked = true;
            int n = outlets.list.size();
            log("outlets: " + found + " sightings -> " + n + " outlets from " + kfs.size() + " photos" + (ok ? "" : " (stopped by an error)"));
            activity.updateProcess(null, -1);
            activity.outletsFound(n, roomItems.list.size(), ok);
            // Saved straight into the scan (the full autosave waits for a viewer frame, which never comes if you've
            // left the scan), so the outlets are kept and, in a shared room, sync to the other phones.
            if (ok && currentDir != null) try { ScanStore.writeOutlets(currentDir, outlets.toJson()); } catch (Exception e) { logError("outlets save", e); }
            if (ok) glTasks.add(this::autosave);
        }).start();
    }

    private static final float[] OUTLET_COL = {1f, 0.62f, 0.1f, 1f};
    /** Outlet labels on or off (the ⚡ Outlets button; remembered across scans, on by default). */
    volatile boolean showOutlets = true;
    private int reportedOutlets = -1;
    private void drawOutlets(float[] m) {
        java.util.List<Outlets.Outlet> ls = outlets.snapshot();
        // Tell the button how many there are (it hides itself when there are none).
        if (ls.size() != reportedOutlets) { reportedOutlets = ls.size(); activity.outletCount(ls.size()); }
        if (ls.isEmpty() || !showOutlets) return;
        float fy = lighting.floorY, dens = activity.getResources().getDisplayMetrics().density;
        for (Outlets.Outlet o : ls) {
            // A 12 dp orange dot with a thin dark ring, as on the iPhone.
            planDraw(m, new float[]{o.x, o.y, o.z}, GLES20.GL_POINTS, new float[]{0, 0, 0, 1f}, 14 * dens);
            planDraw(m, new float[]{o.x, o.y, o.z}, GLES20.GL_POINTS, OUTLET_COL, 12 * dens);
            drawLabel(m, o.x, o.y + 0.08f, o.z, o.label(fy), false);
        }
    }

    /** GL thread: run the whole improvement chain on its own, reporting one progress bar. */
    private void startAuto() {
        if (count == 0) { activity.toast("Nothing was captured, so there's nothing to save. Start a new scan and move the phone slowly around the room."); return; }
        autosave(); // right away: Android may close the app in the background, and the scan only lives in memory
        if (!AUTO_PROCESS) return;
        autoStage = 1;
        autoProgress("Step 1 of 3 · Sharpening depth from your photos", 0.02f);
        if (photos.keyframes.isEmpty()) { autoStage = 2; startCleanUp(); return; }
        toggleDepthAI();
        if (!depthRunning) { autoStage = 2; startCleanUp(); }
    }

    volatile boolean paused;  // the app is in the background: GL work waits

    /** Save without asking (a dated name the first time, then over the same folder). */
    private volatile boolean saving;
    private long lastAutosaveMs; private int lastAutosaveCount;
    static final long AUTOSAVE_MS = 20_000; // was 8 s: each save briefly holds up the frames (rk 9 Nov)

    /** While scanning: save every few seconds when there is new data (not during an improve pass, which isn't lined up yet). */
    private void maybeAutosaveWhileScanning(long nowMs) {
        if (saving || improveDir != null || count < 2000 || count == lastAutosaveCount || nowMs - lastAutosaveMs < AUTOSAVE_MS) return;
        lastAutosaveMs = nowMs; lastAutosaveCount = count;
        autosave();
    }

    private void autosave() {
        if (saving) { glTasks.add(() -> { if (!saving) autosave(); }); return; }
        saving = true;
        String name = currentName != null ? currentName : "Room " + android.text.format.DateFormat.format("d MMM yyyy, HH:mm", System.currentTimeMillis()); // as on the iPhone
        requestSave(name, currentDir, (out, err) -> {
            saving = false;
            activity.autoSaved(out != null ? name : null, err);
            if (out != null && viewing) writeSurface(out);
        });
    }

    private void autoFailed(String what) {
        if (autoStage == 0 || autoStage == 5) return;
        log("auto: " + what + " failed, skipping ahead");
        if (autoStage == 1) glTasks.add(() -> { autoStage = 2; startCleanUp(); });
        else if (autoStage == 2) glTasks.add(() -> { autoStage = 3; autoProgress("Step 3 of 3 · Building the photo-real 3D view", 0.6f); buildSurfels(); });
        else { autoStage = 5; autoProgress("Done", 1f); }
    }

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
        if (autoStage == 2) autoProgress("Step 2 of 3 · Straightening floor and walls", 0.52f);
        new Thread(() -> { try {
            long t0 = System.currentTimeMillis();
            CleanUp c = CleanUp.run(ps, cs, n, walls);
            log(c.summary + " (" + (System.currentTimeMillis() - t0) + " ms) " + c.debug);
            glTasks.add(() -> applyCleanUp(c, gen));
        } catch (Throwable e) { cleaning = false; logError("clean-up", new RuntimeException(e)); autoFailed("clean-up"); } }).start();
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
        if (autoStage == 2) { autoStage = 3; autoProgress("Step 3 of 3 · Building the photo-real 3D view", 0.6f); autosave(); }
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
    /** GL thread: another round of training on the scan as it is (its photos and splats), then save. Returns a message. */
    String refineAgain() {
        if (training || (autoStage > 0 && autoStage < 5)) return "Already working on it…";
        if (surfBase == null) return "The model is still loading. Try again in a moment.";
        autoStage = 4;
        // the photos are the better source here (depth is noisy): let splats move further to match them, starting
        // fresh from the current model so each Refine builds on the last
        takeUndo("Refine");
        Trainer.MAX_T = 0.5f; Trainer.POS_LR = 3f; trainer = null;
        Surfels cur = surfShown;
        if (cur != null && cur.trained && cur.n > 0) surfBase = cur; // continue from the trained model, not the raw scan
        autoProgress("Refining the model against your photos", 0.65f);
        String l = toggleTraining();
        if (!training) { autoStage = 5; activity.updateProcess(null, -1); return "Couldn't refine: no photos in this scan."; }
        activity.trainingLabel(l);
        return "Refining: about " + AUTO_STEPS + " steps, saved when done";
    }

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
                log("training: " + trainer.n + " splats (" + Math.round(trainer.cell * 1000) + " mm) from " + base.n + " surfels, " + views.size() + " photos (" + (System.currentTimeMillis() - t0) + " ms)");
            }
            Trainer tr = trainer;
            java.util.Random rnd = new java.util.Random();
            long lastPub = 0, t0 = System.currentTimeMillis(); int it0 = tr.iter;
            float base0 = -1, ema = -1;
            final boolean auto = autoStage == 4;
            while (training && gen == surfGen) {
                if (auto && tr.iter - it0 >= AUTO_STEPS) { training = false; break; }
                float l = tr.step(views.get(rnd.nextInt(views.size())));
                ema = ema < 0 ? l : ema * 0.95f + l * 0.05f;
                if (tr.iter <= 20) base0 = base0 < 0 ? l : base0 + (l - base0) / tr.iter;
                long now = System.currentTimeMillis();
                if (now - lastPub > 400) {
                    lastPub = now;
                    surfPending = tr.toSurfels();
                    float better = base0 > 0 ? Math.max(0, 1 - ema / base0) * 100 : 0;
                    float ms = (now - t0) / (float) Math.max(1, tr.iter - it0);
                    if (auto) autoProgress(String.format(java.util.Locale.US, "Step 4 of 4 · Refining detail (%.0f%% sharper)", better), 0.65f + 0.35f * Math.min(1f, (tr.iter - it0) / (float) AUTO_STEPS));
                    activity.updateStatus(String.format(java.util.Locale.US,
                            "Training splats on the phone: step %d (%.0f ms each), %d splats vs %d photos.\nMatches the photos %.0f%% better than the raw scan. Tap Pause any time, or let it keep honing.",
                            tr.iter, ms, tr.n, views.size(), better));
                }
            }
            if (gen == surfGen) surfPending = tr.toSurfels();
            log("training paused at step " + tr.iter + ", error " + tr.lastLoss);
            if (auto && autoStage == 4) { autoStage = 5; autoProgress("Done", 1f); activity.trainingLabel("Train splats"); glTasks.add(this::autosave); } // keep the improved model
        } catch (Throwable e) { training = false; logError("training", new RuntimeException(e)); if (autoStage == 4) { autoStage = 5; autoProgress("Done", 1f); } } }).start();
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

    private volatile Surfels surfShown;   // what the viewer shows now (saved with the scan when it's trained)
    private Surfels loadedSplats;          // trained splats from a saved scan, shown instead of rebuilding

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
        surfCount = s.n; surfTrained = s.trained; surfShown = s;
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
        logMemory("Done");
        liveScratch = null;   // the live mesh's copy of the surface (tens of MB): the after-Done steps need the memory (15:23 scan ran out)
        style = 2;   // the result opens in Points (rk 10 Nov 15:22)
        if (roomLux() > 0) { Lighting.ROOM_LUX = Math.max(5f, roomLux()); log(String.format(java.util.Locale.US, "room light from camera exposure: %.0f lx (%d samples)", roomLux(), luxSamples.size())); }
        java.util.List<RoomModel.PlaneData> planes = capturePlanes();
        viewing = true;
        sensors.active = false;
        log(String.format(java.util.Locale.US, "tracking health: %d losses, %d pose jumps, fewest features %s, longest exposure %.0f ms; video %s, focus %s, draw %s",
                    healthLosses, poseJumps, healthMinFeatures == Integer.MAX_VALUE ? "-" : String.valueOf(healthMinFeatures), healthMaxExposureNs / 1e6,
                    MainActivity.recordWanted ? "on" : "off", FIXED_FOCUS ? "fixed" : "auto", fpsCap30 ? "30 fps" : "60 fps"));
            log("finish: " + count + " points, " + diagnostics() + ", " + carvedTotal + " cleared by see-through"
                    + (depthAllPx > 0 ? String.format(java.util.Locale.US, "; depth pixels sure enough (confidence %d+): %.0f%%", PAINT_MIN, 100.0 * depthKeptPx / depthAllPx) : "")
                    + (CLOSEST_WINS ? "; dots replaced by a closer sure look: " + supersededTotal : "")
                    + String.format(java.util.Locale.US, "; capture zone %.0f%% x %.0f%%: %d depth pixels outside it made no dots", ZONE_W * 100, ZONE_H * 100, zoneOutPx)
                    + (MERGE_FLAT ? "; fine dots merged into flat cells: " + mergedAway : "")
                    + (zoneAll[0] > 0 ? String.format(java.util.Locale.US, "; sure depth by picture area: centre %.0f%%, middle %.0f%%, edge %.0f%%",
                        100.0 * zoneSure[0] / zoneAll[0], 100.0 * zoneSure[1] / Math.max(1, zoneAll[1]), 100.0 * zoneSure[2] / Math.max(1, zoneAll[2])) : ""));
        perf.stage("after Done");
        // The logs, video and diagnostics go into THIS scan's folder: a minute after Done (the after-Done steps take well
        // under a minute), or at once when another scan is started or opened first (rk 10 Nov 15:04-15:06: three quick
        // scans; the first one's logs went into the second's folder and the second's were lost).
        final File dirAtDone = currentDir;
        final byte[][] removedAtDone = new byte[1][];
        final byte[][] confAtDone = new byte[1][];   // the points the clean-up below takes out (kept, not deleted)
        final FlatTiles.Saved tilesSaved = FLAT_TILES ? flatTiles.snapshot() : null;   // GL thread: read back now
        synchronized (afterDoneLock) {
            afterDoneWork = () -> {
                File dir = dirAtDone != null ? dirAtDone : currentDir;
                perf.stop();
                if (dir != null && tilesSaved != null) saveTiles(dir, tilesSaved);
                if (dir != null && removedAtDone[0] != null) saveRemoved(dir, removedAtDone[0]);
                if (dir != null) keepRebuildFrames(dir);
                if (dir != null) saveFilledWall(dir);
                // (dot-confidence.bin is written with every save now, by ScanStore)
                if (dir != null) { saveEvents(dir); saveLiveMesh(dir); keepScreenVideo(dir); perf.moveInto(dir, "perf-scan"); log("perf log kept with the scan " + dir.getName() + ": perf-scan.jsonl"); saveLogInto(dir); saveFeatureHeights(dir); saveDepthPairs(dir); saveHeight(dir); }
            };
        }
        new Thread(() -> {
            try { Thread.sleep(60_000); } catch (InterruptedException ignored) { }
            runAfterDone();
        }, "perf-stop").start();
        if (removed == null) { removed = new java.io.ByteArrayOutputStream(); removedN = 0; }   // (also filled while scanning: compactions near the cap)
        boolean[] early = dropEarlyDrift();
        purgeCleared(early);
        dropNoise();
        log("refined " + refinedTotal + " re-observations, dropped " + droppedNoise + " one-off specks / see-through points, kept " + count
                + "; " + removedN + " points taken out in all, kept aside in removed-points.bin");
        removedAtDone[0] = removed.toByteArray(); removed = null;
        {   // per dot, in the order of points.bin: best confidence (1 byte) and closest distance (2 bytes, cm)
            byte[] cb = new byte[count * 3];
            for (int i = 0; i < count; i++) { cb[i * 3] = confBest[i]; cb[i * 3 + 1] = (byte) (nearCm[i] >> 8); cb[i * 3 + 2] = (byte) nearCm[i]; }
            confAtDone[0] = cb;
        }
        try {
            long t0 = System.currentTimeMillis();
            // Data is gold: the read-back at Done must not take away most of the room (15:09 scan: the live surface
            // covered the whole room, the saved one 16% of it). Keep a copy; if the read-back leaves under 70% of the
            // area, the copy is kept and the read-back result is saved beside it (surface-readback-N.ply).
            drainFuse();
            Tsdf.Band before = gpuTsdf != null ? tsdf.band() : null;
            int cellsBefore = before != null ? tsdf.bandCells(before) : 0;
            gpuSyncAll();
            drainFuse();
            readbackRejected = null;
            if (before != null) {
                Tsdf.Band after = tsdf.band();
                int cellsAfter = tsdf.bandCells(after);
                log(String.format(java.util.Locale.US, "surface area at Done: %d cells before the read-back, %d after", cellsBefore, cellsAfter));
                if (cellsBefore > 40 && cellsAfter < 0.7f * cellsBefore) {
                    readbackRejected = after;
                    synchronized (tsdf) { tsdf.restore(before); }
                    log(String.format(java.util.Locale.US, "read-back kept out: it covered %.0f%% of the surface before it; the surface before is put back, the read-back saved beside it", 100f * cellsAfter / cellsBefore));
                }
                before = null;
            }
            meshTris = tsdf.extractMesh();
            log("surface: " + tsdf.size() + " voxels -> " + meshTris + " triangles (" + (System.currentTimeMillis() - t0) + " ms)");
            if (!zones.isEmpty()) {
                int dt = detail.extractMesh();
                mergeDetail();
                log("detail zones: " + zones.size() + ", " + detail.size() + " fine voxels -> " + dt + " triangles; merged mesh " + meshVerts / 3 + " triangles");
            }
            if (FLAT_TILES && flatTiles.countShown() > 0) flattenMeshToTiles();
        } catch (Throwable e) { logError("surface", new RuntimeException(e)); }
        lastPlanes = planes;
        if (improveDir != null) { mergeImprove(); return; }
        enterViewer();
        startAuto();
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
        fitView();
        finishStatus();
    }

    /**
     * Look at the whole scan from outside, zoomed so it fits the screen: robust bounds (stray points ignored),
     * facing the way the phone pointed at the end of the scan, slightly from above.
     */
    private void fitView() {
        if (count == 0) return;
        int step = Math.max(1, count / 50000), m = 0;
        float[] xs = new float[count / step + 1], ys = new float[xs.length], zs = new float[xs.length];
        for (int i = 0; i < count; i += step) {
            if (obs[i] == 0) continue;
            xs[m] = pos.get(i * 3); ys[m] = pos.get(i * 3 + 1); zs[m] = pos.get(i * 3 + 2); m++;
        }
        if (m < 10) return;
        java.util.Arrays.sort(xs, 0, m); java.util.Arrays.sort(ys, 0, m); java.util.Arrays.sort(zs, 0, m);
        int lo = (int) (m * 0.02f), hi = (int) (m * 0.98f);
        float x0 = xs[lo], x1 = xs[hi], y0 = ys[lo], y1 = ys[hi], z0 = zs[lo], z1 = zs[hi];
        target[0] = (x0 + x1) / 2; target[1] = (y0 + y1) / 2; target[2] = (z0 + z1) / 2;
        float r = 0.5f * (float) Math.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0) + (z1 - z0) * (z1 - z0));
        int last = poseLog.n - 1;
        if (last >= 0) { // stand behind where the phone was looking, so the scanned side faces us
            float qx = poseLog.p[last * 7 + 3], qy = poseLog.p[last * 7 + 4], qz = poseLog.p[last * 7 + 5], qw = poseLog.p[last * 7 + 6];
            float fx = -2 * (qx * qz + qw * qy), fz = -(1 - 2 * (qx * qx + qy * qy));
            if (fx * fx + fz * fz > 1e-4f) yaw = (float) Math.atan2(-fx, -fz);
        }
        pitch = 0.3f;
        float halfV = (float) Math.toRadians(30), aspect = width > 0 && height > 0 ? (float) width / height : 0.5f;
        float halfH = (float) Math.atan(Math.tan(halfV) * aspect);
        dist = Math.max(0.5f, r / (float) Math.sin(Math.min(halfV, halfH)) * 1.05f);
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
        activity.updateStatus((claudeAdvice != null ? claudeAdvice : "") + (ClaudeHelper.sessionCalls > 0 ? "💰 " + ClaudeHelper.costLine() + "\n" : "") + (roomReady ? room.summary + "\n" : "") + (cutouts.size() > 0 ? cutouts.describe() + "\n" : "") + (roomReady && room.hasLayout ? "" : "Walls not found clearly, so this shows the raw scan. Scan more of the walls next time.\n") + "Drag to turn it, pinch to zoom in, two fingers to move.");
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
        if (hit != null) fixPoint(hit);
        float[] pose = new float[16];
        camera.getPose().toMatrix(pose, 0);
        applyFix(pose);
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
        activity.askTapKind(!ROOM_TAPS);
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

    /** Room marks by tapping (corner, wall, floor, ceiling, object) were too far off (rk, 7 Nov): off, except corners when improving a saved scan. */
    static final boolean ROOM_TAPS = false;

    // ---- Tap to clear while scanning (rk 9 Nov): a mistake can be wiped and scanned again ----
    /** Share of the screen's short side the cleared circle reaches from the tap. */
    static final float CLEAR_RADIUS = 0.11f;
    /** How far behind the tapped surface the clearing reaches, metres. */
    static final float CLEAR_BEHIND = 0.15f;
    volatile long clearedAtMs;

    /** Handled in the next scan frame (glTasks only run in the viewer). */
    private volatile float[] pendingClear;
    void requestClear(float x, float y) { pendingClear = new float[]{x, y}; }

    /**
     * GL thread: everything in the cone through a circle around the tap (as seen on screen now: the overview's camera
     * when it's on), from the camera to the surface there plus CLEAR_BEHIND: points, surface voxels and so the live
     * mesh and the coverage. Scanning the spot again fills it back in.
     */
    private void clearAt(float sx, float sy) {
        if (viewing || count == 0) return;
        final float[] m = mvp.clone();
        final float r = CLEAR_RADIUS * Math.min(width, height), r2 = r * r;
        final float tx = sx, ty = sy, w2 = width / 2f, h2 = height / 2f;
        // Where on screen a point is, and how far from the camera (clip w); null when behind it.
        // Pass 1: how far the surface under the tap is (near the middle of the circle).
        float[] near = new float[2048]; int nn = 0;
        float mx = 0, my = 0, mz = 0;
        for (int i = 0; i < count; i += Math.max(1, count / 200000)) {
            float x = pos.get(i * 3), y = pos.get(i * 3 + 1), z = pos.get(i * 3 + 2);
            float cw = m[3] * x + m[7] * y + m[11] * z + m[15];
            if (cw <= 0.05f) continue;
            float px = (m[0] * x + m[4] * y + m[8] * z + m[12]) / cw * w2 + w2, py = h2 - (m[1] * x + m[5] * y + m[9] * z + m[13]) / cw * h2;
            float dx = px - tx, dy = py - ty;
            if (dx * dx + dy * dy < r2 * 0.12f && nn < near.length) { near[nn++] = cw; mx += x; my += y; mz += z; }
        }
        if (nn < 5) { log("tap to clear: nothing scanned under the tap (" + nn + " points)"); activity.toast("Nothing scanned there yet"); return; }
        java.util.Arrays.sort(near, 0, nn);
        final float limit = near[nn / 5] + CLEAR_BEHIND;
        Tsdf.Where inCone = (x, y, z) -> {
            float cw = m[3] * x + m[7] * y + m[11] * z + m[15];
            if (cw <= 0.05f || cw > limit) return false;
            float px = (m[0] * x + m[4] * y + m[8] * z + m[12]) / cw * w2 + w2, py = h2 - (m[1] * x + m[5] * y + m[9] * z + m[13]) / cw * h2;
            float dx = px - tx, dy = py - ty;
            return dx * dx + dy * dy < r2;
        };
        int removed = removePointsWhere(inCone);
        drainFuse();
        int vox = tsdf.clearWhere(inCone) + detail.clearWhere(inCone);
        liveMeshAt = 0; // the live mesh follows at once
        livePreview.forget(mx / nn, my / nn, mz / nn, 0.5f); // the photo choices there are worked out again
        int sc = 0; for (int i = 0; i < count; i++) if ((col.get(i * 4 + 3) & 0xFF) == 255) sc++;
        solidCount = sc;
        roomShown = count > 0 ? Math.min(roomShown, (float) sc / count) : 0; // the Room bar drops too
        detailHere = 0;
        clearedAtMs = System.currentTimeMillis();
        activity.flashCleared(sx, sy, r);
        log(String.format(java.util.Locale.US, "cleared by tap: %d points, %d voxels, to %.2f m", removed, vox, limit));
    }

    /** Removes the points that pass the test. */
    private int removePointsWhere(Tsdf.Where drop) {
        drainDepth();   // the depth thread mustn't add points while they're moved
        int k = 0;
        for (int i = 0; i < count; i++) {
            if (drop.test(pos.get(i * 3), pos.get(i * 3 + 1), pos.get(i * 3 + 2))) continue;
            if (k != i) {
                pos.put(k * 3, pos.get(i * 3)); pos.put(k * 3 + 1, pos.get(i * 3 + 1)); pos.put(k * 3 + 2, pos.get(i * 3 + 2));
                for (int j = 0; j < 4; j++) col.put(k * 4 + j, col.get(i * 4 + j));
                obs[k] = obs[i]; thru[k] = thru[i]; seenStamp[k] = seenStamp[i]; if (seg != null) seg[k] = seg[i]; confBest[k] = confBest[i]; nearCm[k] = nearCm[i]; dotFine[k] = dotFine[i]; mergedDot[k] = mergedDot[i];
            }
            k++;
        }
        int removed = count - k;
        count = k; uploaded = 0; refineCursor = 0;
        rebuildVoxelIndex();
        return removed;
    }

    void requestRescan(float x, float y) {
        if (!ROOM_TAPS && improveDir == null) return;
        pendingTap = new float[]{x, y};
    }

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
        drainFuse();
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
        // Points under tiles hidden: a tile in the way (or at most 10 cm past the nearest point) is what gets measured
        if (DROP_UNDER_TILES && FLAT_TILES) {
            float[] th = flatTiles.rayHit(ox, oy, oz, dx, dy, dz);
            if (th != null && (hit == null || th[3] <= best + 0.10f)) return new float[]{th[0], th[1], th[2]};
        }
        return hit;
    }

    private int removePointsNear(float[] c, float r) {
        drainDepth();   // the depth thread mustn't add points while they're moved
        int k = 0;
        for (int i = 0; i < count; i++) {
            float dx = pos.get(i * 3) - c[0], dy = pos.get(i * 3 + 1) - c[1], dz = pos.get(i * 3 + 2) - c[2];
            if (dx * dx + dy * dy + dz * dz < r * r) continue;
            if (k != i) {
                pos.put(k * 3, pos.get(i * 3)); pos.put(k * 3 + 1, pos.get(i * 3 + 1)); pos.put(k * 3 + 2, pos.get(i * 3 + 2));
                for (int j = 0; j < 4; j++) col.put(k * 4 + j, col.get(i * 4 + j));
                obs[k] = obs[i]; thru[k] = thru[i]; seenStamp[k] = seenStamp[i]; if (seg != null) seg[k] = seg[i]; confBest[k] = confBest[i]; nearCm[k] = nearCm[i]; dotFine[k] = dotFine[i]; mergedDot[k] = mergedDot[i];
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
            boolean fineDot = (!zones.isEmpty() && inZone(x, y, z)) || (FINE_NEAR && nearCm[i] > 0 && nearCm[i] < FINE_NEAR_M * 100);
            voxels.put(fineDot ? fineKey(x, y, z) : key(x, y, z), i);
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
        if (liveBusy || nowMs - liveLastMs < 5000 || count < 3000) return; // copies every point: not too often while scanning
        liveLastMs = nowMs; liveBusy = true;
        final int n = count;
        final FloatBuffer p = FloatBuffer.allocate(n * 3);
        final ByteBuffer c = ByteBuffer.allocate(n * 4);
        // Copies through duplicates: the depth thread writes these buffers meanwhile, and a changed limit on the shared
        // buffer made its writes past the limit fail (11:15 scan: a frame lost every 5-15 s).
        FloatBuffer pd = pos.duplicate(); pd.position(0); pd.limit(n * 3); p.put(pd); p.position(0);
        ByteBuffer cd = col.duplicate(); cd.position(0); cd.limit(n * 4); c.put(cd); c.position(0);
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
            if (!live && !planningPreview && cutHides(b)) continue;
            if ("noise".equals(b.kind)) continue;
            if (recognised(b)) { drawCartoon(m, b); continue; }
            boolean item = b.depth > 0;
            planDraw(m, objTriangles(b), GLES20.GL_TRIANGLES, item ? ITEM_COL : BOX_COL, 1);
            planDraw(m, objEdges(b), GLES20.GL_LINES, item ? ITEM_EDGE : BOX_EDGE, 1);
        }
        int labels = 0;
        for (RoomModel.Box b : boxes) {
            if (!live && !planningPreview && cutHides(b)) continue;
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
        // Same as the iPhone's labels: 13.5 dp semibold text in a ~27 dp pill, growing with large text sizes only a
        // little (capped at 1.2x), so labels stay readable without covering the room.
        android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        float sp = Math.min(dm.scaledDensity, dm.density * 1.2f);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(13.5f * sp);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        paint.setColor(0xFFFFFFFF);
        int pad = (int) (10 * dm.density);
        int w = (int) Math.ceil(paint.measureText(text)) + 2 * pad, h = (int) Math.ceil(27 * sp);
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
        activity.setRoomSize(null);
        if (planner.isReady()) {
            try {
                synchronized (featLock) { room.featureYs = java.util.Arrays.copyOf(featY, featN); room.featureN = featN; }
                room.build(planes, planner, pos, col, count);
                // The height decided when the scan was made (with ARCore's planes, which aren't saved) is kept with
                // the scan and used when it's opened again (rk 10 Nov 13:13: 2.97 m at Done, ~3.13 m on reopening).
                File hd = currentDir;
                if (viewing && hd != null && new File(hd, "height.json").exists()) {
                    try {
                        org.json.JSONObject hj = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(new File(hd, "height.json").toPath()), "UTF-8"));
                        if (Math.abs((float) hj.getDouble("floorY") - room.floorY) < 0.05f) {
                            room.ceilingY = (float) hj.getDouble("ceilingY"); room.hasCeiling = true; room.ceilingTrusted = hj.optBoolean("trusted");
                        }
                    } catch (Exception e) { logError("height.json", e); }
                } else if (!viewing) pendingHeight = new float[]{room.floorY, room.ceilingY, room.ceilingTrusted ? 1 : 0};
                log(String.format(java.util.Locale.US, "ceiling: depth %.3f, ARCore features %s (%d feature heights), used %.3f %s; floor %.3f; height %.2f m",
                        room.depthCeilingY, Float.isNaN(room.featureCeilingY) ? "none" : String.format(java.util.Locale.US, "%.3f", room.featureCeilingY), featN,
                        room.ceilingY, room.ceilingTrusted ? "(trusted)" : "(depth only)", room.floorY, room.ceilingY - room.floorY));
                applyLabels(room.boxes);
                claudeObjs.mergeInto(room.boxes, room.floorY);
                roomReady = true;
                buildFloorMesh();
                log(room.logLine());
                // floor area (the footprint, split into rooms at doorways; a teammate's request, 10 Nov)
                try {
                    long t0 = System.currentTimeMillis();
                    FloorArea.Result fa = FloorArea.compute(pos, count, room.floorY);
                    floorArea = fa; selectedRoom = 0; selectedRoomVerts = null;
                    loadWallEdits(fa); if (!scanningNowForWall()) { if (!loadedFromThisSession()) filledWall = null; loadFilledWall(currentDir); }
                    saveFloorAreaJson();
                    StringBuilder rs = new StringBuilder();
                    for (int i = 0; i < fa.rooms.size(); i++) rs.append(i == 0 ? "" : "; ").append(fa.rooms.get(i).text(i));
                    log(String.format(java.util.Locale.US, "floor area: %s (%s; outline %.0f%% walls, %.1f m² of floor in no room) in %d ms",
                            fa.text(), rs, fa.enclosed * 100, fa.other, System.currentTimeMillis() - t0));
                } catch (Exception e) { logError("floor area", e); }
                activity.setRoomSize(roomSizeText());
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
        final String planningJson;
        try { if(currentDir != null && roomReady) requirePlanningScan(); planningJson = roomEdits.encode(lighting); } catch(Exception e) { fail.accept("Planning export failed: " + e.getMessage()); return; }
        final String log = report();
        final int n = count;
        final String name = "cinex-scan-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date()) + ".zip";
        new Thread(() -> {
            try { ScanExport.claudeJson = claudeObjs.has() ? claudeObjs.toJson() : null; done.accept(ScanExport.write(activity, name, recs, photos, jpgs, pos, col, n, log, poseLog, sensors, planningJson)); }
            catch (Throwable e) { fail.accept(String.valueOf(e)); }
        }).start();
    }

    int recordedFrames() { return recs.size(); }

    /** Screen rotation relative to the phone's natural (portrait) orientation; the demo turns with the phone (portrait or landscape). */
    private int displayRotation() {
        try { return activity.getWindowManager().getDefaultDisplay().getRotation(); } catch (Throwable e) { return 1; }
    }

    void reset() {
        synchronized (this) { logScanStart = log.length(); }   // the next scan's log starts here
        memLogAt = 0;
        removed = null; removedN = 0; compactAt = 0;
        anchors.clear(); clearGoal(); anchorOfferAt = 0; lateEvents = 0; depthKeptPx = 0; depthAllPx = 0; supersededTotal = 0; mergedAway = 0; mergeCursor = 0; java.util.Arrays.fill(mergedDot, (byte) 0); java.util.Arrays.fill(zoneAll, 0); java.util.Arrays.fill(zoneSure, 0);
        filledWall = null; wallCand = null; wallCandHits = 0; guessCells = null; shownSet = false; guessWeight = 0; guessUpdates = 0;
        flatTiles.clear(); tilesJob = null;
        lastLiveMesh = null;
        synchronized (this) { evRing.clear(); evKept.clear(); evUntil = 0; } trackN = 0; shadowRealign = null;
        screenFile = null; screenTargetDir = null;   // the next scan records its own screen video
        roomEdits.clear(); planningPreview = false; planningLoadError = null; planningScanKey = null;
        synchronized(lighting) { lighting.props.removeIf(p -> p.catalog != null); lighting.selected = -1; }
        count = 0; uploaded = 0; pathCount = 0; lastPx = 1e9f;
        depthRunning = false; cleaning = false; glTasks.clear();
        gpuSurfaceReset();
        depthK = 1f; depthC = 0f; dfN = 0; dfAt = 0; dfLastFit = 0;
        needRealign = false; realignOk = 0; hasFix = false; android.opengl.Matrix.setIdentityM(poseFix, 0); segment = 0; realign = null; realignBuilding = false; haveGoodCam = false;
        surfGen++; liveMeshTriVerts = 0; liveMeshLineVerts = 0; liveMeshNew = null; surfDone = null; surfBase = null; surfPending = null; surfCount = 0; surfShown = null; loadedSplats = null; training = false; trainer = null; trainViews = null;
        drainDepth(); gpuReady.clear(); synchronized (liveTrack) { liveTrack.clear(); } liveDoorAt = 0; synchronized (featLock) { featN = 0; featAt = 0; } pairN = 0; ceilingHinted = false; healthLosses = 0; healthMinFeatures = Integer.MAX_VALUE; healthMaxExposureNs = 0; doorways = new java.util.ArrayList<>(); activity.setDoorways(null); stampFrame = 0; scanMs = 0; lastTickMs = 0; depthFrames = depthPixels = depthRejectedRange = depthRejectedConf = depthRejectedEdge = depthNotYet = 0; depthStale = 0; recoveries = 0; lastError = "";
        voxels.clear(); depthOnlyFirst = -1; appendSimplify = 0; demoTool = 0; personSpot = null; ivPerson = null;
        roomShown = 0; hallDone = false; shownHint = null; tooFast = tooFar = false;
        earlyDepthFrames = -1; loadedAny = false; objects.clear(); objects.active = false; outlets.clear(); roomItems.clear(); outletsRunning = false;
        luxSamples.clear(); loadedLux = 0; lastDepth = null; photoWanted = false; photoWantedSince = 0; Lighting.ROOM_LUX = 250f;
        refined = false; refineCursor = 0; refinedTotal = 0; droppedNoise = 0;
        drainFuse();
        tsdf.clear(); detail.clear(); zones.clear(); meshPosAll = null; meshColAll = null; meshVerts = 0; photos.clear(); livePreview.reset(); liveGroupTex.clear(); liveGroupPos.clear(); liveGroupUv.clear(); liveGroupVerts.clear(); meshTris = 0; havePrevPose = false; recs.clear(); poseLog.clear(); hideN = 0; labels.clear(); claudeObjs.clear(); synchronized (marks) { cornerList.clear(); wallList.clear(); markFloor = null; markCeiling = null; markOrder.clear(); } claudeLine = null; claudeAdvice = null; claudeLastKf = null; guideTarget = null; guideText = null; poseJumps = 0; solidCount = 0; detailHere = -1; moveSpeed = 0; carvedTotal = 0; carveCursor = 0; zoneOutPx = 0; autoStage = 0; lastAutosaveMs = System.currentTimeMillis(); lastAutosaveCount = 0; activity.updateProcess(null, -1); viewSides.clear(); hintBox = null; hintText = null; sensors.clear(); liveRoom = null; liveLastMs = 0; rescanAt = null; pendingTap = null;
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
        if (demoTool != 0 && count > 0) { demoTap(tx, ty); return; }
        if (sceneIdx == 0 && pickRoom(tx, ty)) return;
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

    // ---------------- demo: remove the person on stage, then a basic interview setup ----------------
    volatile int demoTool;                    // 0 off, 1 next tap removes a person, 2 next tap places the interview
    private float[] personSpot;               // x, local floor y, z of the last removed person
    private volatile float[] ivPerson, ivCam, ivLights, ivRays, ivCones; // interview set, world lines / triangles

    private int pickIndex(float tx, float ty) {
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
            if (d < best - 25f || (d < best + 25f && cw < bestW)) { best = Math.min(best, d); bestW = cw; bestI = i; }
        }
        return bestI;
    }

    /** The floor under a spot: the most common height of the points around it (the stage, not the hall floor). */
    private float localFloor(float sx, float sy, float sz) {
        int bins = 60; int[] h = new int[bins]; float lo = sy - 2.5f;
        for (int i = 0; i < count; i++) {
            float x = pos.get(i * 3), y = pos.get(i * 3 + 1), z = pos.get(i * 3 + 2);
            if (y > sy + 0.05f || y < lo) continue;
            float r = (float) Math.hypot(x - sx, z - sz);
            if (r < 0.35f || r > 1.6f) continue;
            h[Math.min(bins - 1, (int) ((y - lo) / 0.05f))]++;
        }
        int bi = -1;
        for (int b = 0; b < bins; b++) if (h[b] > 30 && (bi < 0 || h[b] > h[bi])) bi = b;
        if (bi < 0) return roomReady ? Math.min(room.floorY, sy) : sy - 1.0f;
        return lo + (bi + 1) * 0.05f;
    }

    private void demoTap(float tx, float ty) {
        int tool = demoTool; demoTool = 0;
        int i = pickIndex(tx, ty);
        if (i < 0) { activity.toast("Nothing there. Tap right on the person."); return; }
        float sx = pos.get(i * 3), sy = pos.get(i * 3 + 1), sz = pos.get(i * 3 + 2);
        float fl = localFloor(sx, sy, sz);
        if (tool == 2) { buildInterview(sx, fl, sz); activity.toast("Interview set placed: subject, camera and 3 lights."); return; }
        removePerson(sx, sy, sz, fl);
    }

    /** Everything standing on the floor there, joined up (12 cm gaps), within 0.7 m of the tap and up to 2.3 m tall. */
    private void removePerson(float sx, float sy, float sz, float fl) {
        final float V = 0.06f, R = 0.7f;
        java.util.HashMap<Long, Integer> cells = new java.util.HashMap<>();
        for (int i = 0; i < count; i++) {
            float x = pos.get(i * 3), y = pos.get(i * 3 + 1), z = pos.get(i * 3 + 2);
            if (y < fl + 0.05f || y > fl + 2.3f || Math.hypot(x - sx, z - sz) > R) continue;
            cells.merge(ContinueAlign.key(x, y, z, V), 1, Integer::sum);
        }
        // start from the candidate cells next to the tap
        java.util.ArrayDeque<long[]> q = new java.util.ArrayDeque<>();
        java.util.HashSet<Long> in = new java.util.HashSet<>();
        int ix0 = (int) Math.floor(sx / V), iy0 = (int) Math.floor(Math.max(sy, fl + 0.1f) / V), iz0 = (int) Math.floor(sz / V);
        for (int dx = -3; dx <= 3; dx++) for (int dy = -3; dy <= 3; dy++) for (int dz = -3; dz <= 3; dz++) {
            long k = ContinueAlign.key((ix0 + dx + 0.5f) * V, (iy0 + dy + 0.5f) * V, (iz0 + dz + 0.5f) * V, V);
            if (cells.containsKey(k) && in.add(k)) q.add(new long[]{ix0 + dx, iy0 + dy, iz0 + dz});
        }
        if (q.isEmpty()) { activity.toast("That doesn't look like something standing there. Tap the person's body."); return; }
        while (!q.isEmpty()) {
            long[] c = q.poll();
            for (int dx = -2; dx <= 2; dx++) for (int dy = -2; dy <= 2; dy++) for (int dz = -2; dz <= 2; dz++) {
                long nx = c[0] + dx, ny = c[1] + dy, nz = c[2] + dz;
                long k = ContinueAlign.key((nx + 0.5f) * V, (ny + 0.5f) * V, (nz + 0.5f) * V, V);
                if (cells.containsKey(k) && in.add(k)) q.add(new long[]{nx, ny, nz});
            }
        }
        takeUndo("Remove person");
        int j = 0, removed = 0;
        double cx = 0, cz = 0;
        for (int i = 0; i < count; i++) {
            float x = pos.get(i * 3), y = pos.get(i * 3 + 1), z = pos.get(i * 3 + 2);
            boolean gone = y >= fl + 0.05f && y <= fl + 2.3f && Math.hypot(x - sx, z - sz) <= R && in.contains(ContinueAlign.key(x, y, z, V));
            if (gone) { removed++; cx += x; cz += z; continue; }
            if (j != i) {
                pos.put(j * 3, x); pos.put(j * 3 + 1, y); pos.put(j * 3 + 2, z);
                for (int a = 0; a < 4; a++) col.put(j * 4 + a, col.get(i * 4 + a));
                obs[j] = obs[i]; seenStamp[j] = seenStamp[i]; thru[j] = thru[i];
            }
            j++;
        }
        count = j; uploaded = 0; refineCursor = 0;
        rebuildVoxelIndex();
        if (removed > 0) personSpot = new float[]{(float) (cx / removed), fl, (float) (cz / removed)};
        surfCount = 0; surfShown = null; buildSurfels();
        autosave();
        log(String.format(java.util.Locale.US, "removed person: %d points, %d cells, floor %.2f", removed, in.size(), fl));
        activity.toast(removed > 0 ? "Person removed (" + removed + " points). Undo puts them back." : "Nothing removed.");
    }

    /** Toggle the interview set: at the removed person's spot, or (none yet) where the next tap lands. Returns a message. */
    String interviewToggle() {
        if (ivPerson != null) { ivPerson = null; return "Interview set hidden"; }
        float[] p = personSpot;
        if (p != null) { buildInterview(p[0], p[1], p[2]); return "Interview set placed where the person stood"; }
        demoTool = 2;
        return "Tap the spot on stage where the person should stand";
    }

    private static final class Lines {
        float[] a = new float[600]; int n;
        void v(float x, float y, float z) { if (n + 3 > a.length) a = java.util.Arrays.copyOf(a, a.length * 2); a[n++] = x; a[n++] = y; a[n++] = z; }
        void seg(float[] p, float[] q) { v(p[0], p[1], p[2]); v(q[0], q[1], q[2]); }
        float[] get() { return java.util.Arrays.copyOf(a, n); }
    }

    private void buildInterview(float px, float fl, float pz) {
        // the subject faces where the scan was taken from (the audience side)
        double sx = 0, sz = 0; int pn = 0;
        for (int i = 0; i < pathCount; i++) { sx += path.get(i * 3); sz += path.get(i * 3 + 2); pn++; }
        float fx, fz;
        if (pn > 0) { fx = (float) (sx / pn) - px; fz = (float) (sz / pn) - pz; } else { fx = (float) Math.sin(yaw); fz = (float) Math.cos(yaw); }
        float fl2 = (float) Math.hypot(fx, fz);
        if (fl2 < 1e-3f) { fx = 0; fz = 1; } else { fx /= fl2; fz /= fl2; }
        final float rx = -fz, rz = fx; // the subject's left/right
        final float ffx = fx, ffz = fz;
        java.util.function.BiFunction<float[], Float, float[]> W = (ab, h) -> new float[]{px + ab[0] * ffx + ab[1] * rx, fl + h, pz + ab[0] * ffz + ab[1] * rz};
        // subject: a simple standing figure
        Lines per = new Lines();
        float[] head = W.apply(new float[]{0, 0}, 1.62f);
        for (int k = 0; k < 16; k++) {
            double a0 = k * Math.PI / 8, a1 = (k + 1) * Math.PI / 8;
            per.seg(W.apply(new float[]{0, 0.1f * (float) Math.cos(a0)}, 1.62f + 0.12f * (float) Math.sin(a0)), W.apply(new float[]{0, 0.1f * (float) Math.cos(a1)}, 1.62f + 0.12f * (float) Math.sin(a1)));
        }
        float[] neck = W.apply(new float[]{0, 0}, 1.5f), hip = W.apply(new float[]{0, 0}, 0.95f);
        per.seg(neck, hip);
        float[] shL = W.apply(new float[]{0, 0.21f}, 1.44f), shR = W.apply(new float[]{0, -0.21f}, 1.44f);
        per.seg(shL, shR);
        per.seg(shL, W.apply(new float[]{0.05f, 0.27f}, 0.85f)); per.seg(shR, W.apply(new float[]{0.05f, -0.27f}, 0.85f));
        float[] hL = W.apply(new float[]{0, 0.11f}, 0.95f), hR = W.apply(new float[]{0, -0.11f}, 0.95f);
        per.seg(hL, hR);
        per.seg(hL, W.apply(new float[]{0, 0.13f}, 0f)); per.seg(hR, W.apply(new float[]{0, -0.13f}, 0f));
        // camera on a tripod, 2.2 m in front at eye height, with its field of view
        Lines cam = new Lines();
        float[] C = W.apply(new float[]{2.2f, 0}, 1.5f);
        box(cam, C, 0.11f, 0.08f, 0.08f, ffx, ffz);
        for (int k = 0; k < 3; k++) {
            double a = k * 2 * Math.PI / 3;
            cam.seg(new float[]{C[0], C[1] - 0.08f, C[2]}, new float[]{C[0] + 0.35f * (float) Math.cos(a), fl, C[2] + 0.35f * (float) Math.sin(a)});
        }
        float[] lens = W.apply(new float[]{2.09f, 0}, 1.5f);
        float[][] fr = new float[4][];
        for (int k = 0; k < 4; k++) fr[k] = W.apply(new float[]{0.0f, (k == 0 || k == 3 ? 1 : -1) * 0.75f}, 1.5f + (k < 2 ? 0.42f : -0.42f));
        for (int k = 0; k < 4; k++) { cam.seg(lens, fr[k]); cam.seg(fr[k], fr[(k + 1) % 4]); }
        // lights: key, fill and back, each on a stand, pointing at the face, with its beam
        Lines li = new Lines(), ray = new Lines(), cone = new Lines();
        float[] face = W.apply(new float[]{0, 0}, 1.55f);
        float[][] at = {{1.8f, 40, 2.2f}, {2.2f, -55, 1.9f}, {1.6f, 160, 2.4f}};
        for (float[] L : at) {
            double a = Math.toRadians(L[1]);
            float[] H = W.apply(new float[]{L[0] * (float) Math.cos(a), L[0] * (float) Math.sin(a)}, L[2]);
            float[] base = {H[0], fl, H[2]};
            li.seg(base, new float[]{H[0], H[1] - 0.15f, H[2]});
            for (int k = 0; k < 3; k++) { double b = k * 2 * Math.PI / 3 + 0.5; li.seg(new float[]{H[0], fl + 0.3f, H[2]}, new float[]{H[0] + 0.4f * (float) Math.cos(b), fl, H[2] + 0.4f * (float) Math.sin(b)}); }
            float dx = face[0] - H[0], dy = face[1] - H[1], dz = face[2] - H[2], dl = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            dx /= dl; dy /= dl; dz /= dl;
            float hl = (float) Math.hypot(dx, dz);
            box(li, H, 0.15f, 0.15f, 0.15f, dx / Math.max(hl, 1e-3f), dz / Math.max(hl, 1e-3f));
            // beam: from the light's front to a circle around the face and a little past it
            float ux = -dz, uz = dx; float ul = (float) Math.hypot(ux, uz); ux /= ul; uz /= ul;          // sideways
            float wx = dy * uz, wy = dz * ux - dx * uz, wz = -dy * ux; // w = d x u
            float wl = (float) Math.sqrt(wx * wx + wy * wy + wz * wz); wx /= wl; wy /= wl; wz /= wl;
            float len = dl * 1.15f, rad = 0.55f * 1.15f;
            float[] src = {H[0] + dx * 0.16f, H[1] + dy * 0.16f, H[2] + dz * 0.16f};
            float[] end = {H[0] + dx * len, H[1] + dy * len, H[2] + dz * len};
            int segs = 24;
            float[] prev = null;
            for (int k = 0; k <= segs; k++) {
                double t = k * 2 * Math.PI / segs; float c = (float) Math.cos(t) * rad, sn = (float) Math.sin(t) * rad;
                float[] e = {end[0] + c * ux + sn * wx, end[1] + sn * wy, end[2] + c * uz + sn * wz};
                if (k % 3 == 0 && k < segs) ray.seg(src, e);
                if (prev != null) { ray.seg(prev, e); cone.v(src[0], src[1], src[2]); cone.v(prev[0], prev[1], prev[2]); cone.v(e[0], e[1], e[2]); }
                prev = e;
            }
        }
        ivCam = cam.get(); ivLights = li.get(); ivRays = ray.get(); ivCones = cone.get();
        ivPerson = per.get();
        log(String.format(java.util.Locale.US, "interview set at %.2f %.2f %.2f facing %.2f %.2f", px, fl, pz, ffx, ffz));
    }

    /** A wireframe box centred at c, turned to face (fx, fz) horizontally; a = half length along f, b = half width, h = half height. */
    private static void box(Lines l, float[] c, float a, float b, float h, float fx, float fz) {
        float rx = -fz, rz = fx;
        float[][] p = new float[8][];
        for (int k = 0; k < 8; k++) {
            float sa = (k & 1) == 0 ? -a : a, sb = (k & 2) == 0 ? -b : b, sh = (k & 4) == 0 ? -h : h;
            p[k] = new float[]{c[0] + sa * fx + sb * rx, c[1] + sh, c[2] + sa * fz + sb * rz};
        }
        int[][] e = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] q : e) l.seg(p[q[0]], p[q[1]]);
    }

    private static final float[] IV_PERSON = {1f, 1f, 1f, 0.95f}, IV_CAM = {0.35f, 0.85f, 1f, 1f}, IV_LIGHT = {0.95f, 0.66f, 0.23f, 1f},
            IV_RAY = {1f, 0.85f, 0.45f, 0.55f}, IV_CONE = {1f, 0.85f, 0.45f, 0.10f};

    private void drawInterview(float[] m) {
        float[] per = ivPerson;
        if (per == null) return;
        float dens = height / 1000f;
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        planDraw(m, ivCones, GLES20.GL_TRIANGLES, IV_CONE, 1);
        GLES20.glLineWidth(2f * dens);
        planDraw(m, ivRays, GLES20.GL_LINES, IV_RAY, 1);
        GLES20.glLineWidth(4f * dens);
        planDraw(m, ivLights, GLES20.GL_LINES, IV_LIGHT, 1);
        planDraw(m, ivCam, GLES20.GL_LINES, IV_CAM, 1);
        planDraw(m, per, GLES20.GL_LINES, IV_PERSON, 1);
    }

    private float boxHalfX, boxHalfZ, boxMinY, boxMaxY;
    private final float[] roomCenter = new float[3];
    private volatile boolean cutaway = false; // rk 7 Nov: show the whole set, no automatic wall removal
    volatile boolean overlays;               // room outline, lights, camera path: debug only (rk: "just the model")
    private boolean cutActive;
    private float cutDirX, cutDirZ, cutD, cutFloor, cutCeil, cutDir2X, cutDir2Z, cutD2 = 1e9f;

    boolean toggleCutaway() { cutaway = !cutaway; return cutaway; }

    /** Work out which wall faces the viewer (eye position) and what to hide. */
    private void updateCutaway(float ex, float ez, boolean top) {
        // Looking from outside, the ceiling is always left out so you see into the room, like the iPhone (walls stay;
        // the full cutaway with walls is still the Cut walls tool). Standing inside the room, the ceiling is shown.
        boolean ceilingOnly = viewing && !cutaway && !walking;
        cutActive = viewing && (cutaway || ceilingOnly);
        if (!cutActive) return;
        cutFloor = roomReady ? room.floorY : planner.isReady() ? planner.floorY : boxMinY;
        float roomH = boxMaxY - cutFloor;
        cutCeil = roomH > 1.9f ? boxMaxY - 0.3f : 1e9f;
        cutD2 = 1e9f;
        if (roomReady && room.hasLayout && room.ceilingY > cutFloor + 1.9f) cutCeil = room.ceilingY - 0.25f;
        if (top || ceilingOnly) { cutDirX = 0; cutDirZ = 0; cutD = 1e9f; return; }
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

    // ---- scene objects: remove, move, put back, catalogue (see SceneObjects) ----
    final SceneObjects objects = new SceneObjects();

    /** GL thread: object mode on/off. Returns the panel text. */
    String objectsOn(boolean on) {
        objects.active = on;
        if (on && !roomItems.list.isEmpty()) objects.fromItems(roomItems.snapshot(), pos, col, count); // Claude-guided: clean, named objects
        else if (on && roomReady && (objects.list.isEmpty() || !objects.edited)) objects.fromRoom(room.boxes, pos, col, count, room.floorY);
        if (!on) { objects.selected = -1; if (objects.edited) autosave(); }
        return objectInfo();
    }

    String objectInfo() {
        SceneObjects.Obj o = objects.sel();
        if (o != null) return o.name + " · " + o.size() + (o.removed ? " · removed" : o.moved() ? " · moved" : "") + (o.removed ? "" : "\nDrag it to move it. Tap empty space to let go.");
        if (!roomReady && objects.list.isEmpty()) return outletsRunning ? "Claude is still looking for objects…" : "Objects appear once the scan has finished processing.";
        if (objects.list.isEmpty()) return "No separate objects found in this scan yet.";
        int rem = 0; for (SceneObjects.Obj x : objects.list) if (x.removed) rem++;
        return objects.list.size() + " objects found" + (rem > 0 ? " (" + rem + " removed)" : "") + ". Tap one to select it.";
    }

    /** Any thread: which object is under the finger (-1 = none). */
    int objectHit(float x, float y) { return objects.pick(viewMvp, width, height, x, y); }

    void objectDrag(float dx, float dy) {
        SceneObjects.Obj o = objects.sel();
        if (o == null || o.removed) return;
        float s = dist * 2f * (float) Math.tan(Math.toRadians(30)) / height;
        if (topView) { o.ox += dx * s; o.oz += dy * s; }
        else {
            float cy = (float) Math.cos(yaw), sy = (float) Math.sin(yaw);
            o.ox += (dx * cy + dy * sy) * s; o.oz += (-dx * sy + dy * cy) * s;
        }
        objects.edited = true;
    }

    void objectTurn() { SceneObjects.Obj o = objects.sel(); if (o != null && !o.removed) { objectSnapshot(); o.turn += (float) Math.toRadians(15); objects.edited = true; } }

    /** Turns the selected object about the vertical, by `rad` (two-finger drag left / right). */
    void objectRotate(float rad) { SceneObjects.Obj o = objects.sel(); if (o != null && !o.removed) { o.turn += rad; objects.edited = true; } }

    /** After a two-finger turn: settle on a 15° step when within 3° of one. */
    void objectSnapTurn() {
        SceneObjects.Obj o = objects.sel();
        if (o == null) return;
        float step = (float) Math.toRadians(15), near = Math.round(o.turn / step) * step;
        if (Math.abs(o.turn - near) < Math.toRadians(3)) o.turn = near;
    }

    boolean objectSelectedMovable() { SceneObjects.Obj o = objects.sel(); return o != null && !o.removed; }

    /** Remove the selected object (one brought in from the catalogue goes away completely). */
    void objectRemove() {
        SceneObjects.Obj o = objects.sel();
        if (o == null || o.removed) return;
        objectSnapshot();
        if (o.fromCatalog) { objects.list.remove(o); objects.selected = -1; }
        else o.removed = true;
        objects.edited = true;
    }

    /** Put the selected object back where the scan had it: not removed, not moved, not turned. */
    void objectPutBackSelected() {
        SceneObjects.Obj o = objects.sel();
        if (o == null || !(o.removed || o.moved())) return;
        objectSnapshot();
        o.removed = false; o.ox = o.oz = 0; o.turn = 0;
        objects.edited = true;
    }

    void objectPutBack(int k) {
        if (k < 0 || k >= objects.list.size()) return;
        objectSnapshot();
        SceneObjects.Obj o = objects.list.get(k);
        o.removed = false; o.ox = o.oz = 0; o.turn = 0; objects.selected = k; objects.edited = true;
    }

    // ---- Undo for object edits: the list and every object's place, before each edit ----
    private static final class ObjState {
        final java.util.List<SceneObjects.Obj> list; final float[] ox, oz, turn; final boolean[] removed; final int selected;
        ObjState(java.util.List<SceneObjects.Obj> l, int sel) {
            list = new java.util.ArrayList<>(l); selected = sel;
            ox = new float[l.size()]; oz = new float[l.size()]; turn = new float[l.size()]; removed = new boolean[l.size()];
            for (int i = 0; i < l.size(); i++) { SceneObjects.Obj o = l.get(i); ox[i] = o.ox; oz[i] = o.oz; turn[i] = o.turn; removed[i] = o.removed; }
        }
    }
    private final java.util.ArrayDeque<ObjState> objUndo = new java.util.ArrayDeque<>();

    /** Remember the objects as they are now, before an edit (up to 50 steps). */
    void objectSnapshot() {
        objUndo.push(new ObjState(objects.list, objects.selected));
        while (objUndo.size() > 50) objUndo.removeLast();
    }

    boolean canUndoObjects() { return !objUndo.isEmpty(); }

    /** Undo the last object edit; false when there's nothing to undo. */
    boolean objectUndo() {
        ObjState s = objUndo.poll();
        if (s == null) return false;
        objects.list.clear(); objects.list.addAll(s.list);
        for (int i = 0; i < s.list.size(); i++) { SceneObjects.Obj o = s.list.get(i); o.ox = s.ox[i]; o.oz = s.oz[i]; o.turn = s.turn[i]; o.removed = s.removed[i]; }
        objects.selected = s.selected < objects.list.size() ? s.selected : -1;
        objects.edited = true;
        return true;
    }

    /** GL thread: bring a catalogue item into this scan, on the floor in the middle of the view. */
    void objectPlace(SceneObjects.Item it) {
        try {
            float fy = roomReady ? room.floorY : planner.isReady() ? planner.floorY : boxMinY;
            SceneObjects.Obj o = SceneObjects.loadItem(it, target[0], fy, target[2]);
            objectSnapshot();
            objects.list.add(o); objects.selected = objects.list.size() - 1; objects.edited = true; objects.active = true;
        } catch (Exception e) { logError("catalog place", e); }
    }

    /** Drag sensitivity, radians per screen pixel (rk: much gentler than the old 0.008). */
    static final float TURN = 0.0025f;

    void orbit(float dx, float dy) {
        yaw -= dx * TURN;
        pitch = clamp(pitch + dy * TURN, -1.5f, 1.55f);
    }

    void zoom(float f) { dist = clamp(dist / f, 0.05f, 100f); }

    // ---- Enter the room: standing in the scan at eye height (like the iPhone app) ----
    volatile boolean walking;
    /** Eye height above the floor, metres; the field of view; held walk buttons (+1 forward, -1 back). */
    volatile float eyeH = 1.5f, walkFov = 65f;
    volatile int walkMove, walkStrafe;
    private final float[] eye = new float[3];
    private volatile float walkYaw, walkPitch;
    private long walkLast;

    // ---- gyro look (rk 10 Nov): standing in the room, turning / tilting the phone looks around; drags add on top ----
    volatile boolean gyroLook = true;
    private final float[] gyroRot = new float[9];
    private volatile float gyroHeading, gyroPitch;
    private volatile boolean gyroHave;
    private float gyroHeading0;
    private volatile boolean gyroRef;
    /** The game rotation vector (gyro + gravity, no compass): the back camera's heading and tilt, smoothed. */
    final android.hardware.SensorEventListener gyroListener = new android.hardware.SensorEventListener() {
        @Override public void onSensorChanged(android.hardware.SensorEvent e) {
            android.hardware.SensorManager.getRotationMatrixFromVector(gyroRot, e.values);
            // The back camera looks along the device's -z; in the world frame (z up) that's minus the third column.
            float bx = -gyroRot[2], by = -gyroRot[5], bz = -gyroRot[8];
            float heading = (float) Math.atan2(by, bx), pitch = (float) Math.asin(clamp(bz, -1f, 1f));
            if (!gyroHave) { gyroHeading = heading; gyroPitch = pitch; gyroHave = true; return; }
            float d = heading - gyroHeading;
            while (d > Math.PI) d -= 2 * Math.PI;
            while (d < -Math.PI) d += 2 * Math.PI;
            gyroHeading += d * 0.6f;                 // light smoothing (a hand shakes)
            gyroPitch += (pitch - gyroPitch) * 0.6f;
        }
        @Override public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) { }
    };

    /** Gyro look on/off; turning it off keeps the view where it is. */
    void setGyroLook(boolean on) {
        if (!on && gyroLook && gyroHave && gyroRef) { walkYaw = walkYawNow(); walkPitch = walkPitchNow(); }
        if (on && !gyroLook) { gyroRef = false; walkPitch = 0; }
        gyroLook = on;
    }

    private float walkYawNow() {
        if (!(gyroLook && gyroHave)) return walkYaw;
        if (!gyroRef) { gyroHeading0 = gyroHeading; gyroRef = true; }
        float d = gyroHeading - gyroHeading0;
        return walkYaw + d;
    }

    private float walkPitchNow() {
        if (!(gyroLook && gyroHave)) return walkPitch;
        return clamp(walkPitch + gyroPitch, -1.45f, 1.45f);
    }

    float walkFloorY() { return roomReady ? room.floorY : planner.isReady() ? planner.floorY : boxMinY; }

    /** Where a tap's ray meets the floor plane (x, z), or null. */
    float[] floorPoint(float tx, float ty) {
        float[] inv = new float[16];
        if (!Matrix.invertM(inv, 0, viewMvp, 0)) return null;
        float nx = tx / width * 2 - 1, ny = 1 - ty / height * 2;
        float[] a = new float[4], b = new float[4];
        Matrix.multiplyMV(a, 0, inv, 0, new float[]{nx, ny, -1, 1}, 0);
        Matrix.multiplyMV(b, 0, inv, 0, new float[]{nx, ny, 1, 1}, 0);
        for (int i = 0; i < 3; i++) { a[i] /= a[3]; b[i] /= b[3]; }
        float dy = b[1] - a[1];
        if (Math.abs(dy) < 1e-6f) return null;
        float t = (walkFloorY() - a[1]) / dy;
        if (t < 0) return null;
        return new float[]{a[0] + (b[0] - a[0]) * t, a[2] + (b[2] - a[2]) * t};
    }

    /** GL thread: stand at (x, z), facing the way the view faced; when already standing, walk there. */
    boolean enterAt(float tx, float ty) {
        float[] p = floorPoint(tx, ty);
        if (p == null) return false;
        if (!walking) { walkYaw = topView ? 0 : yaw; walkPitch = gyroLook ? 0f : -0.1f; walkFov = 65f; topView = false; gyroRef = false; }
        eye[0] = p[0]; eye[1] = walkFloorY() + eyeH; eye[2] = p[1];
        walking = true;
        walkLast = 0;
        return true;
    }

    void exitWalk() { walking = false; walkMove = 0; walkStrafe = 0; }

    // ---- Measure (rk 10 Nov): tap two spots on the scanned surface in Enter the room, see the distance ----
    /** The view matrix of the last drawn frame, for the measure overlay (copied each frame). */
    final float[] shownMvp = new float[16];
    volatile int shownW, shownH;

    /** GL thread: the scanned surface under a screen spot (ray against the surface mesh; else the nearest scan point
     *  close to the ray), or null. */
    float[] measurePick(float tx, float ty) {
        float[] inv = new float[16];
        if (!Matrix.invertM(inv, 0, viewMvp, 0)) return null;
        float nx = tx / width * 2 - 1, ny = 1 - ty / height * 2;
        float[] a = new float[4], b = new float[4];
        Matrix.multiplyMV(a, 0, inv, 0, new float[]{nx, ny, -1, 1}, 0);
        Matrix.multiplyMV(b, 0, inv, 0, new float[]{nx, ny, 1, 1}, 0);
        for (int i = 0; i < 3; i++) { a[i] /= a[3]; b[i] /= b[3]; }
        float dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz); if (len < 1e-6f) return null;
        dx /= len; dy /= len; dz /= len;
        float best = Float.MAX_VALUE;
        FloatBuffer mp = meshPosAll != null ? meshPosAll : tsdf.meshPos; int mv = meshPosAll != null ? meshVerts : tsdf.meshVerts;
        if (mp != null) for (int t = 0; t + 2 < mv; t += 3) {
            int o = t * 3;
            float x0 = mp.get(o), y0 = mp.get(o + 1), z0 = mp.get(o + 2);
            float e1x = mp.get(o + 3) - x0, e1y = mp.get(o + 4) - y0, e1z = mp.get(o + 5) - z0;
            float e2x = mp.get(o + 6) - x0, e2y = mp.get(o + 7) - y0, e2z = mp.get(o + 8) - z0;
            float px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
            float det = e1x * px + e1y * py + e1z * pz;
            if (Math.abs(det) < 1e-9f) continue;
            float f = 1 / det, sx = a[0] - x0, sy = a[1] - y0, sz = a[2] - z0;
            float u = (sx * px + sy * py + sz * pz) * f; if (u < 0 || u > 1) continue;
            float qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
            float v = (dx * qx + dy * qy + dz * qz) * f; if (v < 0 || u + v > 1) continue;
            float tt = (e2x * qx + e2y * qy + e2z * qz) * f;
            if (tt > 0.05f && tt < best) best = tt;
        }
        // The points along the ray (rk 10 Nov 15:24: results open in Points, and where the surface is missing Measure
        // must still land): the nearest points within 3 cm of the ray, then the median of those within 4 cm behind
        // the first one (one point alone can be a speck).
        // On the surface where there is one (offline, picks on the points differed from the surface by ~10 cm along
        // the ray at grazing views, so the surface stays first); on the points only where there's no surface.
        if (best < Float.MAX_VALUE) return new float[]{a[0] + dx * best, a[1] + dy * best, a[2] + dz * best};
        float[] pp = pointPick(a, dx, dy, dz);
        return pp == null ? null : new float[]{pp[0], pp[1], pp[2]};
    }

    /** x, y, z and the distance along the ray of the robust point hit, or null. */
    private float[] pointPick(float[] a, float dx, float dy, float dz) {
        float bt = Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            float px = pos.get(i * 3) - a[0], py = pos.get(i * 3 + 1) - a[1], pz = pos.get(i * 3 + 2) - a[2];
            float t = px * dx + py * dy + pz * dz; if (t < 0.05f || t >= bt) continue;
            float d2 = px * px + py * py + pz * pz - t * t;
            if (d2 < 0.03f * 0.03f) bt = t;
        }
        if (bt == Float.MAX_VALUE) return null;
        java.util.ArrayList<float[]> near = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            float px = pos.get(i * 3) - a[0], py = pos.get(i * 3 + 1) - a[1], pz = pos.get(i * 3 + 2) - a[2];
            float t = px * dx + py * dy + pz * dz; if (t < bt || t > bt + 0.04f) continue;
            float d2 = px * px + py * py + pz * pz - t * t;
            if (d2 < 0.03f * 0.03f) near.add(new float[]{pos.get(i * 3), pos.get(i * 3 + 1), pos.get(i * 3 + 2), t});
        }
        float[] out = new float[4];
        for (int k = 0; k < 4; k++) { final int kk = k; near.sort((p, q) -> Float.compare(p[kk], q[kk])); out[k] = near.get(near.size() / 2)[k]; }
        return out;
    }

    /** Drag to look around (the scene follows the finger); pitch kept short of straight up or down. */
    void look(float dx, float dy) {
        float s = 0.002f * walkFov / 60f;
        walkYaw += dx * s;
        walkPitch = clamp(walkPitch + dy * s, gyroLook && gyroHave ? -2.6f : -1.3f, gyroLook && gyroHave ? 2.6f : 1.3f);
    }

    void walkZoom(float f) { walkFov = clamp(walkFov / f, 30f, 90f); }

    void setEyeHeight(float h) { eyeH = h; if (walking) eye[1] = walkFloorY() + h; }

    void pan(float dx, float dy) {
        // Two-finger pan 1:1: what's under the fingers at the target's distance stays under them (60° view).
        float s = 2f * dist * (float) Math.tan(Math.toRadians(30)) / Math.max(1, height);
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
        // A new GL context: the GPU surface block is gone with the old one. Mid-scan the CPU fuses from here (a fresh
        // block would overwrite what was synced with partial data); a new scan starts on the GPU again.
        if (gpuTsdf != null) { gpuTsdf = null; gpuSurfaceBroken = true; log("GL context lost: GPU surface off for this scan, the CPU fuses"); }
        labelTex.clear(); labelProg = 0; // old GL objects are gone with the old context
        clearBg();
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
                "uniform mediump float uLive; uniform mediump float uPass; uniform float uCell; uniform float uCellFine; uniform float uPxM; uniform float uCapPx;" +
                // Dollhouse cutaway: hide the wall band facing the viewer and the ceiling.
                "uniform float uCut; uniform vec3 uCenter; uniform vec2 uDir; uniform float uCutD; uniform vec2 uDir2; uniform float uCutD2; uniform float uFloor; uniform float uCeil;" +
                HIDE_DECL + FlatTiles.MASK_DECL +
                "void main(){ float hh = 0.0;" + HIDE_TEST.replace("HIDE", "hh = 1.0;") + FlatTiles.MASK_TEST +
                "  if (hh > 0.5 || aColor.a < 0.002) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); gl_PointSize = 1.0; vColor = aColor; return; }" +
                "  if (uCut > 0.5) { vec2 r = aPos.xz - uCenter.xz;" +
                "    if (((dot(r, uDir) > uCutD || dot(r, uDir2) > uCutD2) && aPos.y > uFloor + 0.15) || aPos.y > uCeil) {" +
                "      gl_Position = vec4(2.0, 2.0, 2.0, 1.0); gl_PointSize = 1.0; vColor = aColor; return; } }" +
                // splats shrink in world size when you get close (w < 1.5 m), so near surfaces stay detailed instead of blobby
                "  gl_Position = uMvp * aPos; float w = max(gl_Position.w, 0.05); vColor = aColor;" +
                // Live scan (uLive, rk 13 Nov, as the iPhone's 996ffc2): each dot sized by its own cell at any distance, no
                // shrink up close. The alpha byte carries the cell (128+ = a 0.8 cm cell) and how solid the spot is: the
                // opaque core is 0.5 cells across for a spot seen once (the small soft dot) up to 1.3 for one seen solid,
                // so neighbours overlap and nothing behind shows through; the soft halo 2 cells. Only a cap for the GPU.
                "  if (uLive > 0.5) { float a = floor(aColor.a * 255.0 + 0.5); float fine = step(128.0, a);" +
                "    float solid = clamp((a - 1.0 - fine * 128.0) / 126.0, 0.0, 1.0);" +
                "    float cellPx = mix(uCell, uCellFine, fine) * uPxM / w;" +
                "    gl_PointSize = (uPass > 0.5 && uPass < 1.5) ? min(cellPx * 2.0, uCapPx * 1.54) : min(cellPx * mix(0.5, 1.3, solid), uCapPx);" +
                "    gl_PointSize = max(gl_PointSize, 1.5); vColor.a = 1.0; return; }" +
                "  gl_PointSize = clamp(uSize / w * clamp(w / 1.5, 0.3, 1.0), 1.5, 48.0); }",
                // uPass: 0 = splat core (opaque, writes depth), 1 = splat halo (soft gaussian, blended), 2 = plain
                "precision mediump float; varying vec4 vColor; uniform float uPass; uniform float uLive;" +
                "void main(){" +
                "  if (uLive > 0.5 && uPass < 0.5) { vec2 q = gl_PointCoord * 2.0 - 1.0; if (dot(q, q) > 1.0) discard; gl_FragColor = vec4(vColor.rgb, 1.0); return; }" +
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
                "void main(){ gl_Position = uMvp * vec4(aPos, 1.0); if (uLit < 0.5) { vC = uAlb; return; }" +
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
    private int lastRotation = -1;

    @Override
    public void onDrawFrame(GL10 gl) {
        try { onDrawFrameInner(gl); } finally { System.arraycopy(viewMvp, 0, shownMvp, 0, 16); shownW = width; shownH = height; }
        try { screenVideoFrame(); } catch (Exception e) { logError("screen video", e); screenVideo = null; }
    }

    // ---- screen.mp4: what the user saw while scanning (ScreenVideo) ----
    /** Debug switch "Screen video" (MainActivity). */
    static volatile boolean SCREEN_VIDEO = true;
    /** Depth fusion test variant for replays (intent extra fuseVariant): 0 as before, 1 head-on weighted, 2 border steps dropped. */
    static volatile int FUSE_VARIANT = 0;
    volatile Runnable replayDone;
    private ScreenVideo screenVideo;
    private File screenFile, screenPending;
    private volatile File screenTargetDir;
    private volatile String fusionState = "";

    private void screenVideoFrame() {
        boolean scanningNow = !viewing && everTracked && width > 0;
        if (screenVideo == null && scanningNow && SCREEN_VIDEO && screenFile == null) {
            File dir = activity.getFilesDir();
            File[] old = dir.listFiles((d, n) -> n.startsWith("screen-") && n.endsWith(".mp4"));
            if (old != null) for (File f : old) if (!f.equals(screenPending)) f.delete();
            screenFile = new File(dir, "screen-" + System.currentTimeMillis() + ".mp4");
            ScreenVideo v = new ScreenVideo(screenFile);
            try { v.start(width, height); screenVideo = v; log("screen video: recording what's on screen (" + ScreenVideo.WIDTH + " px wide, " + ScreenVideo.FPS + " fps, times as in this log)"); }
            catch (Exception e) { log("screen video: could not start (" + e.getMessage() + ")"); try { v.releaseGl(); v.finish(); } catch (Throwable ignored) { } screenFile.delete(); }
        }
        ScreenVideo v = screenVideo;
        if (v == null) return;
        if (scanningNow && !v.failed()) {
            int sec = (int) (scanMs / 1000);
            String hint = shownHint == null ? "" : shownHint;
            v.frame(width, height, String.format(java.util.Locale.US, "%.1fs  %s  view %s  fusion %s  scan %d:%02d\n%s",
                    (System.currentTimeMillis() - t0) / 1000f, lastTs, heldMode == 0 ? "live" : heldMode == 1 ? "held (rotation)" : "held (ARCore)", fusionState, sec / 60, sec % 60, hint));
            return;
        }
        finishScreenVideo();
    }

    /** GL thread: finish screen.mp4 (Done, Cancel, or the GL context went away); it moves into the scan folder once
     *  that's saved (screenTargetDir). Without this a cancelled scan left an unplayable file (no index; 11 Nov 20:12). */
    private void finishScreenVideo() {
        ScreenVideo v = screenVideo;
        if (v == null) return;
        screenVideo = null;
        v.releaseGl();
        final File file = screenFile, target = screenTargetDir;   // (Cancel resets right after: keep its folder)
        new Thread(() -> {
            boolean ok = v.finish();
            log(String.format(java.util.Locale.US, "screen video: %s, %d frames, %.1f ms on the draw thread per frame", ok ? "kept" : "failed", v.frames, v.frames > 0 ? v.glMsTotal / v.frames : 0));
            if (ok && target != null && file != null) { File to = new File(target, "screen.mp4"); if (file.renameTo(to)) log("screen video kept with the scan: screen.mp4 (" + to.length() / 1024 + " KB)"); }
            else if (ok) { screenPending = file; File d = screenTargetDir; if (d != null) keepScreenVideo(d); }   // the save may already be done
            else if (file != null) file.delete();
        }, "screen-video-close").start();
        // (screenFile stays set: one video per scan; reset() clears it for the next scan)
    }

    /** live-mesh.bin: the triangle soup (float x,y,z per corner, big-endian, after an int corner count and the floor
     *  height) of the last live doorway search. Diagnostics only (not in the .rmsc). */
    private void saveLiveMesh(File dir) {
        float[] vv = lastLiveMesh;
        if (vv == null) return;
        try (java.io.DataOutputStream o = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(new File(dir, "live-mesh.bin"))))) {
            o.writeInt(vv.length / 3); o.writeFloat(lastLiveFloor); for (float v : vv) o.writeFloat(v);
        } catch (Exception e) { logError("live mesh", e); }
    }

    /** Where tiles were painted, the surface is the tile's flat plane (rk 10 Nov 16:24: "the mesh should be flat as
     *  well where the tiles land"): triangles on a tile (within its noise band) are dropped and the tiles' flat squares
     *  put in, so the Mesh view, Measure taps and the saved surface.ply are flat there. */
    private void flattenMeshToTiles() {
        FloatBuffer p = meshPosAll != null ? meshPosAll : tsdf.meshPos; ByteBuffer c = meshPosAll != null ? meshColAll : tsdf.meshCol;
        int verts = meshPosAll != null ? meshVerts : tsdf.meshVerts;
        if (p == null || c == null || verts < 3) return;
        float[] flat = flatTiles.flatTriangles();
        int tris = verts / 3, kept = 0, fv = flat.length / 3;
        boolean[] keep = new boolean[tris];
        for (int t = 0; t < tris; t++) {
            float x = 0, y = 0, z = 0;
            for (int k = 0; k < 3; k++) { x += p.get((t * 3 + k) * 3); y += p.get((t * 3 + k) * 3 + 1); z += p.get((t * 3 + k) * 3 + 2); }
            keep[t] = !flatTiles.covers(x / 3, y / 3, z / 3);
            if (keep[t]) kept++;
        }
        int nv = kept * 3 + fv;
        FloatBuffer np = ByteBuffer.allocateDirect(nv * 12).order(ByteOrder.nativeOrder()).asFloatBuffer();
        ByteBuffer nc = ByteBuffer.allocateDirect(nv * 4).order(ByteOrder.nativeOrder());
        for (int t = 0; t < tris; t++) if (keep[t]) for (int k = 0; k < 9; k++) np.put(p.get(t * 9 + k));
        for (int t = 0; t < tris; t++) if (keep[t]) for (int k = 0; k < 12; k++) nc.put(c.get(t * 12 + k));
        np.put(flat); for (int i = 0; i < fv; i++) { nc.put((byte) 205); nc.put((byte) 200); nc.put((byte) 192); nc.put((byte) 255); }
        np.position(0); nc.position(0);
        meshPosAll = np; meshColAll = nc; meshVerts = nv; meshTris = nv / 3;
        log(String.format(java.util.Locale.US, "flat tiles in the surface: %d triangles on tiles dropped, %d flat tile triangles put in", tris - kept, fv / 3));
    }

    /** tiles.json + tiles.png in the scan folder (the flat tiles; not in the .rmsc yet). */
    private void saveTiles(File dir, FlatTiles.Saved sv) {
        try {
            java.nio.file.Files.write(new File(dir, "tiles.json").toPath(), sv.json.getBytes("UTF-8"));
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(new File(dir, "tiles.png"))) { sv.img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o); }
            log("flat tiles kept with the scan: " + flatTiles.countShown() + " tiles");
        } catch (Exception e) { logError("tiles save", e); }
    }

    /** After the save: screen.mp4 into the scan folder. */
    void keepScreenVideo(File dir) {
        // (20:25 scan: the video was still being finished when the scan was saved, and stayed in files/: remember
        // the folder, the closing thread moves it there when it's done)
        screenTargetDir = dir;
        File f = screenPending;
        if (f == null || dir == null || !f.exists()) return;
        File to = new File(dir, "screen.mp4");
        if (f.renameTo(to)) { screenPending = null; log("screen video kept with the scan: screen.mp4 (" + to.length() / 1024 + " KB)"); }
    }

    private void onDrawFrameInner(GL10 gl) {
        long drawStartNs = System.nanoTime();
        // Debug A/B: draw at most 30 fps (the camera's own rate) to leave the GPU and CPU to ARCore.
        // While scanning: at most 30 draws a second, the camera's own rate (rk 10 Nov 15:39: a fast 2.5 min scan reached
        // thermal 'severe' with ~1M points drawn 60 times a second, fps fell and ARCore lost track). The viewer stays at 60.
        // rk 10 Nov 15:44: 15 a second while scanning ("no need to have it any faster; it slows the user down too").
        // The camera itself stays at 30 (the S24 offers ARCore only 30 fps configs); ARCore tracks on every camera frame
        // internally, this only sets how often we take a frame, fuse and draw.
        if (fpsCap30 && !viewing && lastFrameNs > 0 && drawStartNs - lastFrameNs < 63_000_000L) {
            try { Thread.sleep((66_000_000L - (drawStartNs - lastFrameNs)) / 1_000_000L); } catch (InterruptedException ignored) { }
            drawStartNs = System.nanoTime();
        }
        if (lastFrameNs > 0) perf.frame((drawStartNs - lastFrameNs) / 1e6); // frame interval
        lastFrameNs = drawStartNs;
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        if (idle) { for (Runnable r; (r = glTasks.poll()) != null; ) r.run(); return; }
        if (viewing) { drawViewer(); return; }
        if (session == null) return;
        if (frameNo % 10 == 0 && displayRotation() != lastRotation) displaySet = false; // turned upside down (same size, so no onSurfaceChanged)
        if (!displaySet) { lastRotation = displayRotation(); session.setDisplayGeometry(lastRotation, width, height); displaySet = true; }
        if (!textureSet) { session.setCameraTextureName(bgTex); textureSet = true; }
        Frame frame;
        try {
            long upT0 = System.nanoTime();
            frame = session.update();
            perf.note("ms: ARCore update", (System.nanoTime() - upT0) / 1e6);
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
        // Wide overview: the camera picture becomes a window in the scene (drawn later), over a dark backdrop.
        if (frame.getTimestamp() != 0) {
            if (overviewBlend > 0.001f && !viewing) {
                GLES20.glClearColor(0.05f, 0.05f, 0.06f, 1f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
                if (overviewBlend < 0.999f) drawBackground(); // fading in: the picture fades into the stripes
                drawZebra(overviewBlend);
            } else {
                drawBackground();
                if (!viewing && CAMERA_OPACITY < 0.99f) dimCamera();   // the camera faint, so the dots stand out (rk 22:40)
                if (!viewing) drawZone(0);   // amber stripes in the zone, under the dots (rk 13 Nov)
            }
        }

        TrackingState ts = camera.getTrackingState();
        if (!viewing) checkLossStop(ts, System.currentTimeMillis());
        // a replay: when the recording has played to its end, Done (MainActivity.replayFinished)
        Runnable rd = replayDone;
        if (rd != null && !viewing && frameNo % 15 == 0) {
            try { if (session.getPlaybackStatus() == com.google.ar.core.PlaybackStatus.FINISHED) { replayDone = null; log("replay: the recording ended"); rd.run(); } }
            catch (Exception ignored) { }
        }
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
        // Scan length: only while tracking and fusing (not while lost / re-aligning, not viewing).
        if (!viewing && lastTickMs > 0 && ts == TrackingState.TRACKING && !needRealign) scanMs += Math.min(250, nowMs - lastTickMs);
        lastTickMs = nowMs;
        if (ts == TrackingState.TRACKING) {
            everTracked = true;
            notTrackingSince = 0;
            // A blink (rk 10 Nov 15:34): ARCore back within 2 s, in the same place (its pose within 30 cm of where it
            // was lost): carry on with the same correction, no lining up, no search. Today the short losses came back
            // in ARCore's own frame (the final corrections were 0-4 cm); the 15:31 scan's 0.5 s loss instead ran a
            // search that put the scan 3.9 m off.
            // rk 10 Nov 15:58: on a plain corner the gyro still works: just carry on. Any length of pause: if ARCore comes
            // back where the phone was held (within 30 cm) and facing where the gyro says (within 8 deg), it simply goes on.
            if (lostSince > 0 && needRealign && jumpGuess == null && !sessionRestarted && havePrevPose) {
                float[] cp = new float[16]; camera.getPose().toMatrix(cp, 0); applyFix(cp);
                float dd = (float) Math.sqrt((cp[12] - prevPose[12]) * (cp[12] - prevPose[12]) + (cp[13] - prevPose[13]) * (cp[13] - prevPose[13]) + (cp[14] - prevPose[14]) * (cp[14] - prevPose[14]));
                float[] v = lastShownView;   // the view shown (gyro-turned): its camera axis in the world
                float gx = v[2], gy = v[6], gz = v[10], ax = cp[8], ay = cp[9], az = cp[10];
                float cos = (gx * ax + gy * ay + gz * az) / (float) Math.max(1e-6, Math.sqrt((gx * gx + gy * gy + gz * gz) * (ax * ax + ay * ay + az * az)));
                float ang = (float) Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, cos))));
                if (dd < 0.3f && ang < 8f) {
                    needRealign = false; realign = null; recentPts.clear(); realignResult = null;
                    log(String.format(java.util.Locale.US, "pause %.1f s: silent continue (ARCore back %.2f m and %.1f deg from the held view)", (nowMs - lostSince) / 1000f, dd, ang));
                    evMark();
                } else {
                    log(String.format(java.util.Locale.US, "pause %.1f s: ARCore back %.2f m and %.1f deg from the held view: quiet re-align", (nowMs - lostSince) / 1000f, dd, ang));
                    heldGuess = heldGuessFor(cp);
                }
            }
            lostSince = 0;
            if (!viewing && count > 2000 && frameNo % 10 == 0) plainWallHint(frame, nowMs);
            // (no ceiling prompt: tilting up to a plain ceiling cost tracking even with wall in view, rk 10 Nov 13:37)
        } else if (everTracked) {
            // Lost tracking after it worked. ARCore usually recovers by itself once it sees something textured again;
            // restarting only helps the S24's "paused for no reason" case, and a restart can shift the whole map.
            if (lostSince == 0) {
                lostSince = nowMs;
                if (count > 2000 && !needRealign) {
                    needRealign = true; realignOk = 0; realignSince = nowMs; realignHintAt = nowMs; sessionRestarted = false; realignTick = 0; newPartOffered = false; forceResume = false; recentPts.clear(); realignResult = null; jumpGuess = null;
                    snapshotForRealign();
                    healthLosses++;
                    log("tracking lost mid-scan: fusion paused until the view lines up with the scan again"); evMark();
                    if (STOP_ON_LOSS) lossStopAt = nowMs + LOSS_GRACE_MS;   // a hiccup under a second doesn't end the scan
                    notAddingSince = nowMs; postHud(ts);   // at once, not at the next 5th frame
                    // (no toast: the card at the top says what to do; rk 10 Nov 15:53 got two different messages at once)
                }
            }
            else if (camera.getTrackingFailureReason() == com.google.ar.core.TrackingFailureReason.NONE
                    && nowMs - lostSince > 25000 && recoveries < 10) {   // ARCore gets 25 s to find its own map first (a restart throws its map away)
                recoveries++;
                lostSince = 0;
                log("tracking lost for 25 s, restarting AR session (attempt " + recoveries + ")");
                sessionRestarted = true;   // a restart can come back in a shifted frame: re-align before fusing
                recentPts.clear();
                activity.restartSession();
            }
        } else if (!everTracked) {
            long now = System.currentTimeMillis();
            if (notTrackingSince == 0) notTrackingSince = now;
            // Only between the two depth modes, after 20 s: a first-time user standing still reading the hint, or a
            // plain wall, used to drop depth for the whole run (RAW -> AUTOMATIC -> DISABLED in 16 s).
            else if (now - notTrackingSince > 20000 && modeIndex < modes.size() - 1 && modes.get(modeIndex + 1) != Config.DepthMode.DISABLED) {
                modeIndex++; notTrackingSince = now;
                log("no tracking after 20 s, trying depth mode " + modes.get(modeIndex));
                try { applyMode(); } catch (Exception e) { logError("configure", e); }
            }
        }
        if (ts == TrackingState.TRACKING && lastTrackState != TrackingState.TRACKING) { settleUntil = nowMs + 1500; trackingBackMs = nowMs; if (lastNewDepthMs > 0) lastNewDepthMs = nowMs; }   // depth gets 4 s after tracking returns
        lastTrackState = ts;
        if (ts == TrackingState.TRACKING) {
            camera.getPose().toMatrix(curPose, 0);
            applyFix(curPose);
            long frameNs = frame.getTimestamp();
            float frameDt = havePrevPose && prevFrameNs > 0 ? Math.max(0f, (frameNs - prevFrameNs) / 1e9f) : 0f;
            prevFrameNs = frameNs;
            if (havePrevPose) {
                float dx = curPose[12] - prevPose[12], dy = curPose[13] - prevPose[13], dz = curPose[14] - prevPose[14];
                frameMove = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                float dot = curPose[8] * prevPose[8] + curPose[9] * prevPose[9] + curPose[10] * prevPose[10];
                frameTurn = (float) Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, dot))));
            }
            moveSpeed = moveSpeed * 0.9f + (frameDt > 0.005f ? Math.min(frameMove / frameDt, 4.5f) : Math.min(frameMove, 0.15f) * 30f) * 0.1f;   // m/s at any frame rate
            if (frameDt > 0.005f) turnSpeed = turnSpeed * 0.9f + Math.min(frameTurn / frameDt, 400f) * 0.1f;   // deg/s
            // A sudden jump means ARCore just corrected its map: the next moment's poses can't be trusted yet.
            // Measured against the time since the last frame we saw: after a pause (a save, a slow frame) the phone
            // has simply walked further. Up to 2 m/s counts as walking, not as ARCore moving its map.
            if (havePrevPose && frameMove > Math.max(0.15f, 2f * frameDt)) {
                settleUntil = nowMs + 1500; poseJumps++; log(String.format(java.util.Locale.US, "pose jump %.2f m, pausing capture 1.5 s", frameMove)); evMark();
                notAddingSince = nowMs;
                relAnchored = false;   // the held view doesn't follow the jump (14:01 scan: 0.29 and 0.33 m jumps while held showed as twitches)
                // ARCore moved its map without saying it lost track (11:47:09: 0.5 m): the scan, not ARCore, decides
                // where the view is; nothing is fused until it lines up again (the quick path after a loss).
                if (count > 2000 && !needRealign && realign == null && !realignBuilding) {
                    needRealign = true; realignOk = 0; realignSince = nowMs; realignHintAt = nowMs; sessionRestarted = false; realignTick = 0; newPartOffered = false; forceResume = false; recentPts.clear(); realignResult = null;
                    snapshotForRealign();
                    // We know the jump exactly: carry on in the old frame (the correction that puts this frame's camera
                    // where the last one was), checked against one depth view (rk 10 Nov 12:58: ARCore flipped +0.9 m
                    // and back -0.8 m between two versions of its map).
                    float[] inv = new float[16], t = new float[16], c = new float[16];
                    if (Matrix.invertM(inv, 0, curPose, 0)) {
                        Matrix.multiplyMM(t, 0, prevPose, 0, inv, 0);
                        Matrix.multiplyMM(c, 0, t, 0, poseFix, 0);
                        jumpGuess = c; fixTry = c.clone();
                    }
                    log("pose jump: fusion paused until the view lines up with the scan again (trying the old frame first)");
                }
            }
            System.arraycopy(curPose, 0, prevPose, 0, 16);
            havePrevPose = true;
            if (nowMs >= settleUntil) {
                // Per-stage times in the hardware log (S24 drew at 10.5 fps with the GL thread ~80% busy; rk 9 Nov).
                long intT0 = System.nanoTime();
                try {
                    nowMsFrame = nowMs;
                    integrate(frame, camera);
                    dispatchGpuReady();
                    perf.note("depth frames waiting", depthQueued.get());
                } catch (Exception e) {
                    logError("integrate", e);
                }
                long phT0 = System.nanoTime();
                perf.note("ms: depth fusion (all)", (phT0 - intT0) / 1e6);
                try { if (depthSupported) maybePhoto(frame, camera); } catch (Exception e) { logError("photo", e); }
                perf.note("ms: photo", (System.nanoTime() - phT0) / 1e6);
            }
            updateGuide(camera, nowMs);
            recordPath(camera.getPose());
        }
        // Drawing: also while lost (rk 10 Nov 13:56: the room still vanished; everything below used to sit inside
        // "only while tracking", so the held view never ran).
        final boolean trackingNow = ts == TrackingState.TRACKING;
        if (trackingNow || (everTracked && haveHeldView && !viewing)) {
            camera.getProjectionMatrix(proj, 0, 0.05f, 100f);
            camera.getViewMatrix(view, 0);
            System.arraycopy(view, 0, rawView, 0, 16);
            // The scan lives in the corrected world (after a re-alignment): draw it from the corrected camera too, so it
            // sits on the camera picture (rk 10 Nov 11:17: "the view seemed off from where the scanned world and real
            // view should merge").
            if (hasFix) { float[] fi = new float[16], v2 = new float[16]; if (Matrix.invertM(fi, 0, poseFix, 0)) { Matrix.multiplyMM(v2, 0, view, 0, fi, 0); System.arraycopy(v2, 0, view, 0, 16); } }
            // While lost (ARCore not tracking, or tracking but not yet lined up with the scan): hold the last good
            // camera position and turn the view with the phone's own rotation sensor (rk 10 Nov 13:44: "it hides the
            // whole room ... use the gyro to keep the position"), so the scan stays put and the user can aim back at
            // it. Back on track: 0.3 s from the held view to the real one.
            holding = everTracked && haveHeldView && (ts != TrackingState.TRACKING || needRealign);
            if (holding && !trackingNow) { heldView(view); relAnchored = false; }
            else if (holding) {
                // ARCore tracks again but isn't lined up with the scan yet: from where the view was shown when it came
                // back, follow ARCore's own movement since then (13:52 scan: tracking was back at 41 s, lined up at
                // 66 s, and the view stayed pinned to where it was lost, "couldn't walk away from some fixed area").
                // anchored on the view shown the frame before (14:01 scan: it used the view from the end of the previous
                // hold, seconds old, and the picture snapped there when a pose jump started a new hold)
                if (!relAnchored) { System.arraycopy(rawView, 0, relRaw, 0, 16); System.arraycopy(prevShown, 0, relShown, 0, 16); relAnchored = true; }
                float[] ri = new float[16], t = new float[16];
                if (Matrix.invertM(ri, 0, relRaw, 0)) { Matrix.multiplyMM(t, 0, rawView, 0, ri, 0); Matrix.multiplyMM(view, 0, t, 0, relShown, 0); }
            }
            heldMode = !holding ? 0 : trackingNow ? 2 : 1;
            if (holding) {
                System.arraycopy(view, 0, lastShownView, 0, 16); holdEndMs = 0;
                // following ARCore's movement: if it's lost again, the rotation sensor carries on from here
                if (trackingNow) { System.arraycopy(view, 0, heldView, 0, 16); synchronized (scanRot) { System.arraycopy(scanRot, 0, heldRot, 0, 9); heldRotOk = scanRotOk; } }
            } else {
                relAnchored = false;
                if (wasHolding) holdEndMs = nowMs;
                if (holdEndMs > 0 && nowMs - holdEndMs < 300) {
                    float t = (nowMs - holdEndMs) / 300f;
                    for (int i = 0; i < 16; i++) view[i] = lastShownView[i] + (view[i] - lastShownView[i]) * t;
                }
                if (ts == TrackingState.TRACKING) { System.arraycopy(view, 0, heldView, 0, 16); haveHeldView = true; synchronized (scanRot) { System.arraycopy(scanRot, 0, heldRot, 0, 9); heldRotOk = scanRotOk; } }
            }
            if (holding && !wasHolding) holdStartMs = nowMs;
            wasHolding = holding;
            System.arraycopy(view, 0, prevShown, 0, 16);
            // While lost, the drawn camera eases 1.5 m back and 0.3 m up over 0.8 s, so the scanned room stays in sight
            // even when the phone points at a part not scanned yet (11 Nov, S23 Ultra: only the striped background showed
            // under "Still can't find where you are"; rk: the scanned overlay always stays in view). Back on track it
            // eases in again with the 0.3 s blend above.
            // The live camera window stays where the phone really is (rk 11 Nov 20:13: "the pulled back frame isn't
            // visible on the S24": the window was placed from the pulled-back camera, so it filled the same part of the
            // screen and the pull-back looked like nothing happened).
            Matrix.multiplyMM(realMvp, 0, proj, 0, view, 0);
            if (holding && PULL_BACK) {
                float k = Math.min(1f, (nowMs - holdStartMs) / 800f); k = k * k * (3 - 2 * k);
                // Not through a scanned wall behind the phone (a small room: 1.5 m back put the camera behind the wall,
                // looking at its back): back as far as there's room, then up instead.
                if (holdPullAt != holdStartMs) {
                    holdPullAt = holdStartMs;
                    float[] inv = new float[16]; Matrix.invertM(inv, 0, view, 0);
                    float free = freeBehind(new float[]{inv[12], inv[13], inv[14]}, norm(new float[]{inv[8], inv[9], inv[10]}), 1.9f);
                    holdBack = Math.max(0f, Math.min(1.5f, free - 0.3f)); holdUp = 0.3f + (1.5f - holdBack) * 0.35f;
                }
                float[] t = new float[16], v2 = new float[16];
                Matrix.setIdentityM(t, 0); Matrix.translateM(t, 0, 0, -holdUp * k, -holdBack * k);
                Matrix.multiplyMM(v2, 0, t, 0, view, 0); System.arraycopy(v2, 0, view, 0, 16);
                System.arraycopy(view, 0, lastShownView, 0, 16);
            }
            Matrix.multiplyMM(mvp, 0, proj, 0, view, 0);
            if (OVERVIEW) overviewCamera(ts == TrackingState.TRACKING, nowMs);
            // the overlay (live doorway outlines) draws with the matrix the scan is drawn with: after the overview's
            // pulled-back camera (13:35: the outlines were projected with the phone's own camera and never showed)
            System.arraycopy(mvp, 0, viewMvp, 0, 16);
            if (!LIVE_MESH || LIVE_POINTS) uploadNew(); // the points: drawn live (Points view), else uploaded for the viewer only
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            drawPaint(mvp);
            if (OVERVIEW && overviewBlend > 0.5f) { // where the screen's corners are in the camera texture (overview projection)
                frame.transformCoordinates2d(com.google.ar.core.Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                        new float[]{-1, -1, 1, -1, -1, 1}, com.google.ar.core.Coordinates2d.TEXTURE_NORMALIZED, ovCamUv);
            }
            if (LIVE_MESH) {
                long lmT0 = System.nanoTime();
                if (trackingNow) maybeLiveMesh(camera, nowMs);   // still built: the live doorway search uses it
                if (LIVE_POINTS) {
                    drawFilledWall(mvp); if (trackingNow) maybeFillWall(nowMs);
                    consumeLiveMesh(); drawPoints(mvp, 0.6f, true);   // sized by their cell (rk 13 Nov), see ptProg
                    if (FLAT_TILES) { maybeTiles(frame, camera, nowMs); flatTiles.draw(mvp); }
                }   // rk 10 Nov 15:20: "only points, no triangles no photo"
                else drawLiveMesh(mvp);
                perf.note(LIVE_POINTS ? "ms: live points draw" : "ms: live mesh draw", (System.nanoTime() - lmT0) / 1e6);
            }
            else if (LIVE_DISCS) drawLiveDiscs(mvp);
            if (contAuto && improveDir != null && trackingNow) { maybeContinueAlign(nowMs); drawContinueGhost(mvp); }
            float[] tap = pendingTap;
            if (tap != null && trackingNow) { pendingTap = null; prepareTap(frame, camera, tap[0], tap[1]); }
            if (trackingNow) maybeStartLiveRoom(nowMs);
            // (no live room outline while scanning: unexplained teal lines on screen, rk 10 Nov 15:53)
            cutouts.draw(mvp);
            drawMarks(mvp, true);
            if (trackingNow) drawRescanTarget(mvp, camera);
            // (no darkening while paused: the scan stays fully in view, rk 10 Nov 15:58)
            if (overviewBlend > 0.001f) drawOverviewWindow();
            drawGoal(mvp, nowMs);
            float[] clearTap = pendingClear;
            if (clearTap != null) { pendingClear = null; clearAt(clearTap[0], clearTap[1]); }
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            // (no arrow to the unscanned area: rk 10 Nov 10:59 asked to remove it; the text hint stays)
            if (SHOW_OBJECTS) drawObjectHint(mvp);
            if (trackingNow) maybeAskClaudeLive(nowMs);
        }
        // (the zone's outline and outside dim are an Android View over the GL view, MainActivity.ZoneFrame: drawn here at
        // the end of the frame it showed in only 127 of 325 frames on the S24, 13 Nov 14:14 scan)
        logState(ts, nowMs);
        if (!viewing && everTracked) trackPose(camera, ts);
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
                        + "\n" + detailLine() + (ROOM_TAPS || improveDir != null ? "\n" + marksLine() : "")
                        + (improveDir != null ? "\nTap 2 room corners to line this pass up with the saved scan." : ROOM_TAPS ? "\nTap anything: a room corner, a wall, the floor, the ceiling or an object, and say which it is." : "")
                        + "\nWalk slowly. Hold still where discs are red or yellow until they turn green. Tap Finish when done.";
            }
            activity.updateStatus(s);
        }
        if (frameNo % 5 == 0) { lastFail = camera.getTrackingFailureReason(); postHud(ts); }
        if (ts == TrackingState.TRACKING) maybeAutosaveWhileScanning(nowMs);
        if (pendingSave != null) { Runnable r = pendingSave; pendingSave = null; r.run(); }
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
        if (best < 0 || bw < 25) { guideTarget = null; guideText = r.floorCoverage > 0.85f ? "Room looks covered. Fill in details or tap Done." : null; return; }
        float tx = sx[best] / w[best], tz = sz[best] / w[best];
        guideTarget = new float[]{tx, r.floorY + 0.3f, tz};
        guideText = String.format(java.util.Locale.US, "Not scanned yet: about %.1f m², look around", bw * cell * cell);
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

    /** Heap past ~85% of the app's limit: the scan stops growing (it's saved as it is) instead of crashing. */
    private volatile boolean memoryFull;
    private long memoryCheckAt;
    private boolean memoryFull() {
        long now = System.currentTimeMillis();
        if (now - memoryCheckAt > 1000) {
            memoryCheckAt = now;
            Runtime rt = Runtime.getRuntime();
            boolean full = rt.totalMemory() - rt.freeMemory() > HEAP_LIMIT * 0.85;
            if (full && !memoryFull) log(String.format(java.util.Locale.US, "memory nearly full (%d of %d MB, %d points): the scan stops growing",
                    (rt.totalMemory() - rt.freeMemory()) >> 20, HEAP_LIMIT >> 20, count));
            if (full && !memoryFull) logMemory("nearly full");
            memoryFull = full;
        }
        return memoryFull;
    }

    private long compactAt;
    private void integrate(Frame frame, Camera camera) {
        // Near the cap, erased / replaced / rescanned dots (hidden, alpha 0) give their slots back at once instead of at
        // Done (rk 23:20: keep the 1.2 M cap, pull back elsewhere; the 23:07 scan had ~125k such slots still counted).
        // They're kept aside for removed-points.bin as at Done.
        if (count >= MAX_POINTS * 0.97f && System.currentTimeMillis() - compactAt > 10000) {
            compactAt = System.currentTimeMillis();
            if (removed == null) { removed = new java.io.ByteArrayOutputStream(); removedN = 0; }
            int before = count; purgeCleared(null);
            if (count < before) log("near the dot limit: " + (before - count) + " erased / replaced dots gave their places back (" + count + " now)");
        }
        if (count >= MAX_POINTS || memoryFull()) return;
        if (!depthSupported) {
            integrateFeaturePoints(frame, camera);
            return;
        }
        Image depth = null, conf = null;
        try {
            depth = rawDepth ? frame.acquireRawDepthImage16Bits() : frame.acquireDepthImage16Bits();
            // Only newer depth: near the end of scan1240 ARCore cycled through 4 old depth images (timestamps
            // 73.4, 73.7, 74.0, 74.3 s, again and again) while the camera kept moving, and fusing them at the new
            // poses threw chunks of points into mid-air. So: skip anything not newer than the last one, or stale.
            if (depth.getTimestamp() <= lastDepthTs) { if (depth.getTimestamp() < lastDepthTs && depthStale++ == 0) log("stale depth: ARCore repeated an older depth image, skipping"); return; }
            lastDepthTs = depth.getTimestamp(); lastNewDepthMs = nowMsFrame; newDepthSince++;
            if (rawDepth) conf = frame.acquireRawDepthConfidenceImage();
            try { cpuImage = frame.acquireCameraImage(); } catch (NotYetAvailableException e) { cpuImage = null; }
            measureExposure();

            int dw = depth.getWidth(), dh = depth.getHeight();
            depthFrames++;
            if (depthFrames == 1) firstDepthMs = System.currentTimeMillis();
            else if (earlyDepthFrames < 0 && System.currentTimeMillis() - firstDepthMs > EARLY_S * 1000) earlyDepthFrames = depthFrames;
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
            // Rebuild frames (rk 12 Nov 09:15: rebuild the room offline from the depth and the pictures): twice a second
            // the raw depth with its confidence (all levels, for offline use only) and the camera picture are copied
            // here, before the 95% rule; written with the pose (below) on a low-priority thread.
            short[] rbD = null; byte[] rbC = null; byte[][] rbYuv = null; int[] rbYuvInfo = null;
            if (SAVE_REBUILD && cpuImage != null && nowMsFrame - rebuildAt >= 500 && rebuildPending.get() < 3) {
                try {
                    rbD = new short[dw * dh]; for (int v = 0; v < dh; v++) for (int u = 0; u < dw; u++) rbD[v * dw + u] = dbuf.get(v * dRow + u * dPix);
                    if (cbuf != null) { rbC = new byte[dw * dh]; for (int v = 0; v < dh; v++) for (int u = 0; u < dw; u++) rbC[v * dw + u] = cbuf.get(v * cRow + u * cPix); }
                    Image.Plane[] pl = cpuImage.getPlanes(); rbYuv = new byte[3][];
                    for (int q = 0; q < 3; q++) { ByteBuffer bb = pl[q].getBuffer().duplicate(); bb.position(0); rbYuv[q] = new byte[bb.remaining()]; bb.get(rbYuv[q]); }
                    rbYuvInfo = new int[]{cpuImage.getWidth(), cpuImage.getHeight(), pl[0].getRowStride(), pl[0].getPixelStride(), pl[1].getRowStride(), pl[1].getPixelStride()};
                    rebuildAt = nowMsFrame;
                } catch (Throwable e) { rbD = null; }
            }
            // Unsure depth is discarded at the door (rk 11 Nov 22:12: "I don't want them put on the scan at all ...
            // unconfident spots are built on other unconfident spots, which is garbage"): every reading under PAINT_MIN
            // becomes "no depth" here, before the dots, the surface, the line-up after a loss, see-through clearing,
            // the depth fit and the event frames ever see it. (At the setting "all", the old 40/55 rules apply.)
            if (cbuf != null && PAINT_MIN > 55) {
                short[] md = new short[dw * dh]; int kept = 0;
                for (int v = 0; v < dh; v++) for (int u = 0; u < dw; u++) {
                    int c = cbuf.get(v * cRow + u * cPix) & 0xFF;
                    // where in the picture the sure readings are (rk 22:54: are first placements "from the side of the view" worse?)
                    float ex = Math.abs(u - dw / 2f) / (dw / 2f), ey = Math.abs(v - dh / 2f) / (dh / 2f); int zone = Math.max(ex, ey) < 0.33f ? 0 : Math.max(ex, ey) < 0.66f ? 1 : 2;
                    zoneAll[zone]++;
                    if (c >= PAINT_MIN) { md[v * dw + u] = dbuf.get(v * dRow + u * dPix); kept++; zoneSure[zone]++; }
                }
                depthKeptPx += kept; depthAllPx += dw * dh;
                dbuf = ShortBuffer.wrap(md); dRow = dw; dPix = 1;
            }

            {   // what the middle of the screen looks at (for the Rescan sphere): median of the sure depth around the centre
                int[] vals = new int[49]; int nv = 0;
                for (int b = -3; b <= 3; b++) for (int a = -3; a <= 3; a++) { int mm = dbuf.get((dh / 2 + b * 2) * dRow + (dw / 2 + a * 2) * dPix) & 0xFFFF; if (mm >= 200 && mm < 4000) vals[nv++] = mm; }
                if (nv >= 5) { java.util.Arrays.sort(vals, 0, nv); centerDepth = vals[nv / 2] / 1000f; }
            }
            float[] m = new float[16];
            camera.getPose().toMatrix(m, 0);
            evCapture(dbuf, dRow, dPix, dw, dh, fx, fy, cx, cy, m, depth.getTimestamp());
            // After tracking was lost mid-scan: nothing is fused until the depth lines up with what's already there
            // (rk 10 Nov: after a loss and an AR restart a second, shifted layer was fused over the first).
            if (needRealign && !realigned(dbuf, dRow, dPix, dw, dh, fx, fy, cx, cy, m, nowMsFrame)) return;
            float[] rawM = m.clone();
            applyFix(m);
            shadowCheck(dbuf, dRow, dPix, dw, dh, fx, fy, cx, cy, rawM, nowMsFrame);
            System.arraycopy(m, 0, lastGoodCam, 0, 16); haveGoodCam = true;   // where the phone was, for a re-alignment guess
            if (rbD != null) saveRebuildFrame(m.clone(), rbD, rbC, dw, dh, fx, fy, cx, cy, rbYuv, rbYuvInfo, ifx, ify, icx, icy, nowMsFrame - t0);
            if (GOOD_SPOTS && nowMsFrame - anchorOfferAt >= 1000 && featuresNow >= Anchors.MIN_FEATURES) { anchorOfferAt = nowMsFrame; offerAnchor(dbuf, dRow, dPix, dw, dh, fx, fy, cx, cy, m); }
            int[] rgb = new int[3];
            frameHits = 0; frameSolid = 0; farPixelsFrame = 0;
            depthPixelsFrame = 0; nearPixelsFrame = 0; nearSum[0] = nearSum[1] = nearSum[2] = 0;
            // Record the raw frame for export (replaying the scan off the phone).
            if (recs.size() >= ScanExport.MAX_RECS && depthFrames % 2 == 0) ScanExport.thinRecs(recs);
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
                    healthMaxExposureNs = Math.max(healthMaxExposureNs, r.exposureNs);
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
            depthFitCollect(frame, camera, dbuf, dRow, dPix, cbuf, cRow, cPix, dw, dh, fx, fy, cx, cy);
            boolean gpuOnNow = gpuSurfaceReady(m);
            // The per-pixel work (points, carving, the CPU surface's samples, the GPU's depth) runs on the depth thread
            // from copies of this frame (rk 10 Nov 10:53: 4-6 fps on the drawing thread lost a door). Every new depth
            // frame is queued; the drawing thread only waits when 6 are already waiting.
            DepthJob job = new DepthJob();
            job.dw = dw; job.dh = dh; job.fx = fx; job.fy = fy; job.cx = cx; job.cy = cy;
            job.ifx = ifx; job.ify = ify; job.icx = icx; job.icy = icy; job.m = m.clone(); job.gpuOn = gpuOnNow;
            job.k = depthK; job.c = depthC; job.gain = colorGain;
            job.zm = zoneMatrix(camera);
            job.d = new short[dw * dh];
            for (int v = 0; v < dh; v++) for (int u = 0; u < dw; u++) job.d[v * dw + u] = dbuf.get(v * dRow + u * dPix);
            if (cbuf != null) {
                job.cf = new byte[dw * dh];
                for (int v = 0; v < dh; v++) for (int u = 0; u < dw; u++) job.cf[v * dw + u] = cbuf.get(v * cRow + u * cPix);
            }
            if (cpuImage != null) job.copyImage(cpuImage);
            submitDepth(job);
            sampleLux(frame);
        } catch (NotYetAvailableException e) {
            depthNotYet++;
            // no depth yet
        } finally {
            if (depth != null) depth.close();
            if (conf != null) conf.close();
            if (cpuImage != null) { cpuImage.close(); cpuImage = null; }
        }
    }


    /** Depth camera coordinates (the sensor's own camera: x right, y up, looking down -z) to the screen's clip space:
     *  projection x the display-oriented view x the sensor camera's pose, all in ARCore's own (uncorrected) frame, so
     *  a re-alignment fix doesn't matter. Null if anything is missing (then no zone: the whole frame, as before). */
    private float[] zoneMatrix(Camera camera) {
        try {
            float[] pr = new float[16], vw = new float[16], ps = new float[16], t = new float[16], out = new float[16];
            camera.getProjectionMatrix(pr, 0, 0.05f, 100f);
            camera.getDisplayOrientedPose().inverse().toMatrix(vw, 0);
            camera.getPose().toMatrix(ps, 0);
            Matrix.multiplyMM(t, 0, vw, 0, ps, 0);
            Matrix.multiplyMM(out, 0, pr, 0, t, 0);
            return out;
        } catch (Throwable e) { return null; }
    }

    /** One depth frame, copied off ARCore's images, for the depth thread. */
    static final class DepthJob {
        int dw, dh; float fx, fy, cx, cy, ifx, ify, icx, icy, k, c, gain; float[] m; boolean gpuOn;
        float[] zm;   // depth camera coordinates to clip space, for the capture zone (null = no zone)
        short[] d; byte[] cf; float[] gd; int[] grgb;
        byte[] iy, iu, iv; int iw, ih, yRow, yPix, uRow, uPix, vRow, vPix;
        void copyImage(Image img) {
            Image.Plane[] pl = img.getPlanes();
            iw = img.getWidth(); ih = img.getHeight();
            yRow = pl[0].getRowStride(); yPix = pl[0].getPixelStride(); uRow = pl[1].getRowStride(); uPix = pl[1].getPixelStride();
            vRow = pl[2].getRowStride(); vPix = pl[2].getPixelStride();
            ByteBuffer b0 = pl[0].getBuffer().duplicate(), b1 = pl[1].getBuffer().duplicate(), b2 = pl[2].getBuffer().duplicate();
            b0.rewind(); b1.rewind(); b2.rewind();
            iy = new byte[b0.remaining()]; b0.get(iy); iu = new byte[b1.remaining()]; b1.get(iu); iv = new byte[b2.remaining()]; b2.get(iv);
        }
    }

    private final java.util.concurrent.ExecutorService depthExec = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        // A little below normal: ARCore's own tracking threads come first (a plain wall is hard enough for it).
        Thread t = new Thread(() -> { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT + 4); r.run(); }, "depth-points"); return t; });   // below ARCore (rk 23:34: ARCore comes first)
    private final java.util.concurrent.atomic.AtomicInteger depthQueued = new java.util.concurrent.atomic.AtomicInteger();
    private volatile java.util.concurrent.Future<?> depthLast;
    /** Depth frames done on the depth thread whose GPU part waits for the drawing thread. */
    private final java.util.concurrent.ConcurrentLinkedQueue<DepthJob> gpuReady = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** The frame stamp of points (seenStamp): the depth thread's own count, in order with its work. */
    private int stampFrame;

    private void submitDepth(final DepthJob j) {
        if (depthQueued.get() >= 6) {   // far behind: wait for one (never drop a depth frame)
            long t0 = System.nanoTime();
            while (depthQueued.get() >= 6) { try { Thread.sleep(2); } catch (InterruptedException e) { break; } }
            perf.note("ms: waited for depth thread", (System.nanoTime() - t0) / 1e6);
        }
        depthQueued.incrementAndGet();
        depthLast = depthExec.submit(() -> {
            long t0 = System.nanoTime();
            try { synchronized (pointsLock) { processDepth(j); } }
            catch (Throwable e) { logError("depth thread", new RuntimeException(e)); }
            finally { depthQueued.decrementAndGet(); }
            perf.note("ms: depth frame (depth thread)", (System.nanoTime() - t0) / 1e6);
        });
    }

    /** Drawing thread: waits until the depth thread has done every queued frame (before anything else reads or changes
     *  the points), then dispatches their GPU parts. */
    private void drainDepth() {
        if (Thread.currentThread().getName().equals("depth-points")) return;   // called from the depth thread's own work
        java.util.concurrent.Future<?> f = depthLast;
        if (f != null && depthQueued.get() > 0) { try { f.get(); } catch (Exception e) { logError("depth drain", new RuntimeException(e)); } }
        dispatchGpuReady();
    }

    /** Drawing thread, every frame: the GPU surface part of frames the depth thread has finished. */
    private void dispatchGpuReady() {
        DepthJob j;
        while ((j = gpuReady.poll()) != null) {
            if (gpuTsdf == null) continue;
            long g0 = System.nanoTime();
            gpuTsdf.integrate(j.gd, j.grgb, j.dw, j.dh, j.m, j.fx, j.fy, j.cx, j.cy);
            if (System.currentTimeMillis() - gpuSyncAt > 500) { gpuSyncAt = System.currentTimeMillis(); gpuSyncToCpu(); }
            perf.note("ms: GPU surface (dispatch + sync)", (System.nanoTime() - g0) / 1e6);
            if (gpuTsdf.failed) { log("GPU surface stopped: " + gpuTsdf.error + "; the CPU fuses from here"); gpuTsdf = null; gpuSurfaceBroken = true; }
        }
    }

    private final Object pointsLock = new Object();

    /** Depth thread: one frame's pixels into the points, the surface samples and the carving. */
    private void processDepth(DepthJob j) {
        if (count >= MAX_POINTS || memoryFull) return;
        stampFrame++;
        final int dw = j.dw, dh = j.dh; final float fx = j.fx, fy = j.fy, cx = j.cx, cy = j.cy, ifx = j.ifx, ify = j.ify, icx = j.icx, icy = j.icy;
        final float[] m = j.m, zm = j.zm;
        ShortBuffer dbuf = ShortBuffer.wrap(j.d); final int dRow = dw, dPix = 1;
        ByteBuffer cbuf = j.cf != null ? ByteBuffer.wrap(j.cf) : null; final int cRow = dw, cPix = 1;
        int[] rgb = new int[3];
        frameHits = 0; frameSolid = 0; farPixelsFrame = 0;
        depthPixelsFrame = 0; nearPixelsFrame = 0; nearSum[0] = nearSum[1] = nearSum[2] = 0;
            final float dfK = j.k, dfC = j.c;   // this frame's depth correction (identity until the fit is sure)
            long pxT0 = System.nanoTime();
            fuseN = 0;
            boolean gpuOn = j.gpuOn;
            if (gpuOn) { j.gd = new float[dw * dh]; j.grgb = new int[dw * dh]; }
            for (int v = 0; v < dh; v++) {
                for (int u = 0; u < dw; u++) {
                    int mm = dbuf.get(v * dRow + u * dPix) & 0xFFFF;
                    if (mm < 200 || mm > (DEMO_FAR ? 8000 : 4000)) { depthRejectedRange++; continue; }
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
                    // Test variants (replays, rk 10 Nov 14:30; FUSE_VARIANT, 0 = as before):
                    // 1: head-on looks count more: towards the picture's corners fewer pixels are used (all within 40% of
                    //    the way to a corner, down to 30% of them at the corners), so a straight look outweighs an edge look;
                    // 2: no depth steps near the picture's border: the outer 3 pixels dropped, and within 8 pixels of the
                    //    border a pixel whose neighbours (2 away) differ by more than 2% + 1 cm.
                    if (FUSE_VARIANT == 1) {
                        float nu = (u + 0.5f) / dw * 2 - 1, nv = (v + 0.5f) / dh * 2 - 1, rr = (float) Math.sqrt((nu * nu + nv * nv) / 2);
                        float keep = 1f - 0.7f * Math.max(0f, Math.min(1f, (rr - 0.4f) / 0.6f));
                        int hsh = (u * 73856093) ^ (v * 19349663) ^ ((int) depthFrames * 83492791);
                        if (((hsh >>> 8) & 1023) / 1024f >= keep) { depthRejectedEdge++; continue; }
                    } else if (FUSE_VARIANT == 2) {
                        int bd = Math.min(Math.min(u, v), Math.min(dw - 1 - u, dh - 1 - v));
                        if (bd < 3) { depthRejectedEdge++; continue; }
                        if (bd < 8) {
                            int t2 = mm / 50 + 10; boolean step = false;
                            for (int q = -2; q <= 2 && !step; q += 4) {
                                int a1 = dbuf.get(v * dRow + (u + q) * dPix) & 0xFFFF, a2 = dbuf.get((v + q) * dRow + u * dPix) & 0xFFFF;
                                if ((a1 > 0 && Math.abs(a1 - mm) > t2) || (a2 > 0 && Math.abs(a2 - mm) > t2)) step = true;
                            }
                            if (step) { depthRejectedEdge++; continue; }
                        }
                    }
                    float d = (mm * 0.001f - dfC) / dfK;   // motion-based depth correction (depthFit), 1:1 until sure
                    float xc = (u + 0.5f - cx) * d / fx;
                    float yc = (cy - (v + 0.5f)) * d / fy;
                    float zc = -d;
                    float wx = m[0] * xc + m[4] * yc + m[8] * zc + m[12];
                    float wy = m[1] * xc + m[5] * yc + m[9] * zc + m[13];
                    float wz = m[2] * xc + m[6] * yc + m[10] * zc + m[14];
                    depthPixelsFrame++;
                    if (d > FAR_M) farPixelsFrame++;
                    if (d < 0.8f) { nearPixelsFrame++; nearSum[0] += wx; nearSum[1] += wy; nearSum[2] += wz; }
                    boolean fine = !zones.isEmpty() && inZone(wx, wy, wz);
                    // close readings get the fine grid (0.8 cm; rk 22:40: the gaps between dots aren't filled): an S24 depth
                    // pixel covers under 1 cm within 1 m, so a 2 cm cell threw detail away there
                    boolean fineDot = fine || (FINE_NEAR && d < FINE_NEAR_M);
                    long vk = fineDot ? fineKey(wx, wy, wz) : key(wx, wy, wz);
                    int hit = voxels.get(vk);
                    if (hit > 0) viewKnown++; else viewNew++;   // the view on surface already in the scan vs new (once-a-second log)
                    sampleColorJ(j, xc / d * ifx + icx, icy - yc / d * ify, rgb);
                    // The surface (TSDF) part goes to the GPU (GpuTsdf) or the fusion thread (below), the points stay here.
                    if (gpuOn) {
                        j.gd[v * dw + u] = d;
                        j.grgb[v * dw + u] = (rgb[0] & 255) | (rgb[1] & 255) << 8 | (rgb[2] & 255) << 16;
                    }
                    if (!gpuOn || fine) {   // with the GPU on, only detail-zone samples still go to the CPU (bit 25: not the room)
                    if (fuseN * 5 + 5 > fuseBuf.length) fuseBuf = java.util.Arrays.copyOf(fuseBuf, fuseBuf.length * 2);
                    fuseBuf[fuseN * 5] = wx; fuseBuf[fuseN * 5 + 1] = wy; fuseBuf[fuseN * 5 + 2] = wz;
                    fuseBuf[fuseN * 5 + 3] = Float.intBitsToFloat((rgb[0] & 255) | (rgb[1] & 255) << 8 | (rgb[2] & 255) << 16 | (fine ? 1 << 24 : 0) | (gpuOn ? 1 << 25 : 0));
                    // How far along this ray free space may be cleared: the nearest depth over a voxel's width around the
                    // pixel (a ray just beside a thin bar mustn't clear the bar), in ray length.
                    {   // (only the CPU fusion reads it; with the GPU on this is the detail zones' samples only)
                        int rpx = Math.max(1, Math.min(6, Math.round(0.6f * tsdf.vs * fx / d)));
                        int nearMm = mm;
                        for (int ny = -1; ny <= 1; ny++) for (int nx = -1; nx <= 1; nx++) {
                            int uu = u + nx * rpx, vv = v + ny * rpx;
                            if (uu < 0 || vv < 0 || uu >= dw || vv >= dh) continue;
                            int m2 = dbuf.get(vv * dRow + uu * dPix) & 0xFFFF;
                            if (m2 >= 200 && m2 < nearMm) nearMm = m2;
                        }
                        float rayLen = (float) Math.sqrt(xc * xc + yc * yc + zc * zc);
                        fuseBuf[fuseN * 5 + 4] = rayLen * ((nearMm * 0.001f - dfC) / dfK) / d;
                    }
                    fuseN++;
                    }
                    if (weak) continue;
                    // Dots only from the capture zone (rk 13 Nov); the surface sample above still used the pixel.
                    if (zm != null && !inZoneClip(zm, xc, yc, zc)) { zoneOutPx++; continue; }
                    if (hit < 0 || seenStamp[hit] != stampFrame) frameHits++;
                    curConf = cv; curMm = mm;
                    if (CLOSEST_WINS && painted(cv) && supersede(wx, wy, wz, m[12], m[13], m[14], mm, rgb, fineDot)) { curConf = 0; curMm = 0; continue; }
                    if (hit >= 0) { refine(hit, wx, wy, wz, rgb); curConf = 0; curMm = 0; continue; }
                    voxels.put(vk, count);
                    curFine = fineDot; addPoint(wx, wy, wz, rgb); curConf = 0; curMm = 0; curFine = false;
                    if (count >= MAX_POINTS) break;
                }
            }
            submitFuse(m[12], m[13], m[14]);
            if (gpuOn) gpuReady.add(j);   // the drawing thread dispatches it (GL context)
            long cvT0 = System.nanoTime();
            perf.note("ms: depth fusion pixels", (cvT0 - pxT0) / 1e6);
            // Behind (frames waiting): skip this frame's see-through check (it works round-robin, later frames catch up),
            // never the frame's points or surface.
            if (depthQueued.get() <= 2) {
                carve(dbuf, dRow, dPix, cbuf, cRow, cPix, dw, dh, fx, fy, cx, cy, m, zm, depthQueued.get() <= 1 ? 40000 : 20000);
                perf.note("ms: carve", (System.nanoTime() - cvT0) / 1e6);
            } else perf.note("carve skipped (depth thread behind)", 1);
            if (depthPixelsFrame > 300) { float fs = (float) farPixelsFrame / depthPixelsFrame; farShare = farShare < 0 ? fs : farShare * 0.7f + fs * 0.3f; }
            float cf = centreSolid(dbuf, dRow, dPix, dw, dh, fx, fy, cx, cy, m);
            if (cf < 0 && frameHits > 200) cf = (float) frameSolid / frameHits;
            if (cf >= 0) detailHere = detailHere < 0 ? cf : detailHere * 0.7f + cf * 0.3f;
            // Keep a sharp HD photo of this view (with its pose) for the photographic room.
            // Close-up held for a moment: make it a detail zone automatically.
            if (depthPixelsFrame > 500 && nearPixelsFrame > depthPixelsFrame / 2) closeFrames++; else closeFrames = 0;
            if (closeFrames >= 4 && nearPixelsFrame > 0) {
                closeFrames = 0;
                float zx = nearSum[0] / nearPixelsFrame, zy = nearSum[1] / nearPixelsFrame, zz = nearSum[2] / nearPixelsFrame;
                if (!inZone(zx, zy, zz) && zones.size() < 24) {
                    zones.add(new float[]{zx, zy, zz, 0.3f});
                    log(String.format(java.util.Locale.US, "auto detail zone %d at (%.2f, %.2f, %.2f)", zones.size(), zx, zy, zz));
                    // (no "Close-up spotted" message: rk 13 Nov 14:39 "fully remove the closeup detected message")
                }
            }
            // Keep this depth image for the next sharp photo (photos are taken on any camera frame, see maybePhoto).
            short[] dcopy = new short[dw * dh];
            for (int v = 0; v < dh; v++) for (int u = 0; u < dw; u++) dcopy[v * dw + u] = dbuf.get(v * dRow + u * dPix);
            lastDepth = dcopy; lastDw = dw; lastDh = dh; lastDIntr = new float[]{fx, fy, cx, cy};
            System.arraycopy(m, 0, lastDPose, 0, 16);
    }

    private void sampleColorJ(DepthJob j, float fu, float fv, int[] out) {
        int u = (int) fu, v = (int) fv;
        if (j.iy == null || u < 0 || v < 0 || u >= j.iw || v >= j.ih) { out[0] = out[1] = out[2] = 180; return; }
        int y = j.iy[v * j.yRow + u * j.yPix] & 0xFF;
        int oU = (v / 2) * j.uRow + (u / 2) * j.uPix, oV = (v / 2) * j.vRow + (u / 2) * j.vPix;
        int cb = (oU < j.iu.length ? j.iu[oU] & 0xFF : 128) - 128, cr = (oV < j.iv.length ? j.iv[oV] & 0xFF : 128) - 128;
        float r = y + 1.402f * cr, g = y - 0.344f * cb - 0.714f * cr, b = y + 1.772f * cb;
        if (j.gain > 1.02f) { r = liftG(r, j.gain); g = liftG(g, j.gain); b = liftG(b, j.gain); }
        out[0] = clampI((int) r); out[1] = clampI((int) g); out[2] = clampI((int) b);
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
                + " | not ready " + depthNotYet + " | stale " + depthStale + " | refined " + refinedTotal + String.format(java.util.Locale.US, " | gain %.2f", colorGain) + " | photos " + photos.keyframes.size() + " | surface voxels " + tsdf.size() + (lastError.isEmpty() ? "" : " | err " + lastError) + "]";
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

    /** Live disc colour code: how many times a spot has been seen, stored in the point colour's alpha (1 = once, 255 = solid). */
    static final int SOLID_SEEN = 5; // depth frames until a spot is solid (S24 depth runs ~1.6/s: 12 meant ~8 s standing still)
    private static int seenAlpha(int n) { return Math.min(255, Math.max(1, n * 255 / SOLID_SEEN)); }
    private final int[] seenStamp = new int[MAX_POINTS];
    private final byte[] thru = new byte[MAX_POINTS]; // depth frames that saw clearly past this point
    private int carveCursor, carvedTotal;

    /**
     * See-through clearing: a point the camera later sees past (the measured surface is clearly farther away at that pixel,
     * and at its 4 neighbours) gets a strike; once it has as many strikes as sightings (at least 4), it is removed.
     * Without this, a wrong first reading (e.g. "something" under a chair) could never go away.
     * Checks up to 250k points per depth frame, round-robin.
     */
    /**
     * What the "This spot" bar shows: the share of dots in the middle of the screen that are solid (green), counting
     * every dot the user can see there, not only the ones this depth frame hit (rk: "done" showed with red all over).
     * -1 when too few dots are in view.
     */
    private float centreSolid(ShortBuffer dbuf, int dRow, int dPix, int dw, int dh, float fx, float fy, float cx, float cy, float[] m) {
        int n = count, step = Math.max(1, n / 150000), vis = 0;
        float solid = 0;
        int u0 = dw / 4, u1 = dw * 3 / 4, v0 = dh / 4, v1 = dh * 3 / 4;
        for (int i = 0; i < n; i += step) {
            if (obs[i] == 0) continue;
            float rx = pos.get(i * 3) - m[12], ry = pos.get(i * 3 + 1) - m[13], rz = pos.get(i * 3 + 2) - m[14];
            float zc = m[8] * rx + m[9] * ry + m[10] * rz;
            float d = -zc;
            if (d < 0.15f || d > 4f) continue;
            float xc = m[0] * rx + m[1] * ry + m[2] * rz, yc = m[4] * rx + m[5] * ry + m[6] * rz;
            int u = (int) (fx * xc / d + cx), v = (int) (cy - fy * yc / d);
            if (u < u0 || u >= u1 || v < v0 || v >= v1) continue;
            int mm = dbuf.get(v * dRow + u * dPix) & 0xFFFF;
            if (mm > 0 && d * 1000 > mm + 100 + mm / 20) continue; // hidden behind what the camera sees now
            int a = col.get(i * 4 + 3) & 0xFF;
            vis++;
            // the disc colours as drawn (floor(a*4)/4): red 0, orange 0.25, yellow 0.5, light green 0.75, solid green 1
            solid += a >= 253 ? 1f : a >= 192 ? 0.75f : a >= 128 ? 0.5f : a >= 64 ? 0.25f : 0f;
        }
        return vis < 150 / step + 30 ? -1 : solid / vis;
    }

    private void carve(ShortBuffer dbuf, int dRow, int dPix, ByteBuffer cbuf, int cRow, int cPix, int dw, int dh,
                       float fx, float fy, float cx, float cy, float[] m, float[] zm, int budgetMax) {
        int n = count;
        if (n == 0) return;
        float[] v = new float[16];
        Matrix.invertM(v, 0, m, 0);
        // Round robin over the cloud (the whole cloud every few frames): 250k with the 3x3 footprint check took ~50 ms
        // (rk 10 Nov 10:31 scan: 19 fps), 60k ~10 ms (23:29 scan); 40k while the depth thread keeps up, else 20k.
        if (depthLate()) return;   // ARCore first: no extra pass while its frames come late
        int budget = Math.min(n, budgetMax), removed = 0;
        for (int it = 0; it < budget; it++) {
            int i = carveCursor++;
            if (carveCursor >= n) carveCursor = 0;
            if (obs[i] == 0 || seenStamp[i] == stampFrame) continue; // dead, or seen in this very frame
            float x = pos.get(i * 3), y = pos.get(i * 3 + 1), z = pos.get(i * 3 + 2);
            float zc = v[2] * x + v[6] * y + v[10] * z + v[14];
            float dp = -zc;
            if (dp < 0.25f || dp > 3.5f) continue;
            float xc = v[0] * x + v[4] * y + v[8] * z + v[12], yc = v[1] * x + v[5] * y + v[9] * z + v[13];
            // only inside the capture zone (rk 13 Nov): the edges of the depth picture neither make nor erase dots
            if (zm != null && !inZoneClip(zm, xc, yc, zc)) continue;
            int u = (int) (fx * xc / dp + cx), vv = (int) (cy - fy * yc / dp);
            // The whole spot must be seen through, not just its own pixel: close up a 2 cm point covers several pixels,
            // and a bar's point just beside its pixel saw the wall and was erased (rk 10 Nov: bars vanished up close).
            int rp = Math.max(1, Math.min(6, Math.round(0.6f * VOXEL * fx / dp)));
            if (u < rp + 1 || vv < rp + 1 || u >= dw - rp - 1 || vv >= dh - rp - 1) continue;
            // One-off specks (rk 13 Nov 14:39: "the stray dots are never removed"; the 14:34 scan erased 346 live but
            // dropped 21,002 one-reading dots only at Done): a dot seen in one frame only, SPECK_FRAMES or more frames ago,
            // whose pixel now has sure depth in view without confirming it, goes now instead of at Done.
            if ((obs[i] & 0xFF) == 1 && stampFrame - seenStamp[i] >= SPECK_FRAMES) {
                int mm0 = dbuf.get(vv * dRow + u * dPix) & 0xFFFF;
                boolean sure = mm0 >= 200 && mm0 <= 4000 && (cbuf == null || (cbuf.get(vv * cRow + u * cPix) & 0xFF) >= PAINT_MIN);
                if (sure) { obs[i] = 0; thru[i] = 0; col.put(i * 4 + 3, (byte) 0); speckSec++; removed++; continue; }
            }
            // clearly farther than the dot: max(10 cm, 8%), the Android-tuned margin (the S24's depth is rougher than the
            // iPhone's LiDAR: the iPhone's 8 cm / 5% erased good patches and they came back, 13 Nov 13:58), raw depth units
            float lim = (dp + Math.max(0.10f, 0.08f * dp)) * depthK + depthC;
            // The centre ray only (rk 13 Nov 14:50, the stray desk-top copy under the table: the 3x3 footprint all beyond
            // removed 22% of it, legs, cables and unsure pixels spoiled it; the centre ray 58%, other dots 0.20%).
            int verdict = beyondOrNot(dbuf, dRow, dPix, cbuf, cRow, cPix, u, vv, lim);   // 1 through, 0 seen at or in front, -1 can't tell
            if (verdict < 0) continue;
            // no depth edge at the spot's own pixel (flying pixels at silhouettes aren't evidence)
            int c0 = dbuf.get(vv * dRow + u * dPix) & 0xFFFF, tol = c0 / 25 + 20;
            if (Math.abs((dbuf.get(vv * dRow + (u - 1) * dPix) & 0xFFFF) - c0) > tol || Math.abs((dbuf.get(vv * dRow + (u + 1) * dPix) & 0xFFFF) - c0) > tol
                    || Math.abs((dbuf.get((vv - 1) * dRow + u * dPix) & 0xFFFF) - c0) > tol || Math.abs((dbuf.get((vv + 1) * dRow + u * dPix) & 0xFFFF) - c0) > tol) continue;
            if (verdict == 0) { if (thru[i] != 0) thru[i]--; continue; }   // seen where it is: one count back
            int t = Math.min(255, (thru[i] & 0xFF) + 1);
            thru[i] = (byte) t;
            // See-through erase (rk 13 Nov: a dot the sensor later sees through is removed), Android-tuned: 10 frames
            // (net) seeing clearly past it (the iPhone's 2 / 3 flickered on the S24).
            boolean gone = t >= 10;
            if (gone) {
                obs[i] = 0; thru[i] = 0;
                col.put(i * 4 + 3, (byte) 0); // alpha 0 = cleared: hidden, skipped by saves, purged at Done; a new reading in its cell brings it back
                removed++;
            }
        }
        if (removed > 0) { refined = true; carvedTotal += removed; erasedSec += removed; }
        if (System.currentTimeMillis() - eraseLogAt >= 1000) {
            if (erasedSec > 0 || revivedSec > 0) log("see-through: erased " + erasedSec + " (one-off specks " + speckSec + "), revived " + revivedSec);
            erasedSec = 0; revivedSec = 0; speckSec = 0; eraseLogAt = System.currentTimeMillis();
        }
    }

    /** One depth pixel against a dot's limit: 1 clearly beyond it, 0 at or in front of it, -1 no sure reading there. */
    private static int beyondOrNot(ShortBuffer dbuf, int dRow, int dPix, ByteBuffer cbuf, int cRow, int cPix, int u, int v, float lim) {
        int mm = dbuf.get(v * dRow + u * dPix) & 0xFFFF;
        if (mm < 200 || mm > 4000) return -1;
        if (cbuf != null && (cbuf.get(v * cRow + u * cPix) & 0xFF) < 55) return -1;
        return mm * 0.001f > lim ? 1 : 0;
    }

    // ---- Photos: the S24 gives depth only while moving, but sharp photos only while still. ----
    // So photos are taken on any 30 fps camera frame (not only the ~1.6 Hz depth frames) once the motion blur,
    // worked out from the gyro-fused pose rate and the camera's real exposure time, is under BLUR_PX.
    static final float BLUR_PX = 4f;         // blur on the 1920 px camera image (2 px on the saved 1024 px photo)
    private short[] lastDepth; private int lastDw, lastDh; private float[] lastDIntr; private final float[] lastDPose = new float[16];
    private long prevFrameTs; private float exposureS = 0.02f; private int isoNow = 400, metaTick;
    float blurPx;                            // current estimate, for the HUD
    boolean photoWanted;                     // this view has no photo yet and the phone is moving too much to take one
    private long photoWantedSince, photoTakenAt;
    private final java.util.ArrayList<Float> luxSamples = new java.util.ArrayList<>();

    private void maybePhoto(Frame frame, Camera camera) {
        long ts = frame.getTimestamp();
        float dt = prevFrameTs > 0 ? Math.max(0.008f, (ts - prevFrameTs) * 1e-9f) : 0.033f;
        prevFrameTs = ts;
        if (++metaTick % 6 == 0) try {
            com.google.ar.core.ImageMetadata md = frame.getImageMetadata();
            exposureS = Math.max(0.001f, md.getLong(com.google.ar.core.ImageMetadata.SENSOR_EXPOSURE_TIME) * 1e-9f);
            isoNow = md.getInt(com.google.ar.core.ImageMetadata.SENSOR_SENSITIVITY);
        } catch (Throwable ignored) { }
        CameraIntrinsics ti = camera.getTextureIntrinsics();
        float ftex = ti.getFocalLength()[0];
        // rotation blur + sideways blur of a surface ~1.5 m away, over one exposure
        blurPx = ((float) Math.toRadians(frameTurn) / dt + frameMove / dt / 1.5f) * exposureS * ftex;
        float[] m = curPose;
        if (!PHOTO_SURFACE) { photoWanted = false; photoWantedSince = 0; return; }   // no photos taken (photo surface off)
        boolean near = lookingAtZone(m);
        boolean novel = photos.novel(m, near);
        long now = System.currentTimeMillis();
        if (novel && blurPx < BLUR_PX) {
            // the latest depth image goes with the photo only if it was taken from (almost) the same place
            float dx = m[12] - lastDPose[12], dy = m[13] - lastDPose[13], dz = m[14] - lastDPose[14];
            float dot = m[8] * lastDPose[8] + m[9] * lastDPose[9] + m[10] * lastDPose[10];
            boolean depthOk = lastDepth != null && dx * dx + dy * dy + dz * dz < 0.03f * 0.03f && dot > 0.9994f;
            int[] td = ti.getImageDimensions();
            float[] tIntr = {ti.getFocalLength()[0], ti.getFocalLength()[1], ti.getPrincipalPoint()[0], ti.getPrincipalPoint()[1], td[0], td[1]};
            photos.capture(bgTex, m.clone(), tIntr, depthOk ? lastDepth : null, depthOk ? lastDw : 0, depthOk ? lastDh : 0,
                    depthOk ? lastDIntr : new float[4], colorGain, width, height, near);
            if (photos.keyframes.size() == 1) log("first photo keyframe (texture " + td[0] + "x" + td[1] + ")");
            if (photos.keyframes.size() % 5 == 1) log(String.format(java.util.Locale.US, "photo %d: blur %.1f px, exposure %.0f ms, ISO %d, depth %s",
                    photos.keyframes.size(), blurPx, exposureS * 1000, isoNow, depthOk ? "yes" : "no"));
            photoTakenAt = now; photoWanted = false; photoWantedSince = 0;
        } else if (novel) {
            if (photoWantedSince == 0) photoWantedSince = now;
            photoWanted = now - photoWantedSince > 1500; // only nag after 1.5 s of missing a new view
        } else { photoWanted = false; photoWantedSince = 0; }
    }

    boolean photoJustTaken() { return System.currentTimeMillis() - photoTakenAt < 1200; }

    /** Room light from the camera's own exposure: incident lux E = C * N^2 / (t * ISO/100), C ~ 2.5 lx·s at ISO 100, f/1.8. */
    private void sampleLux(Frame frame) {
        try {
            com.google.ar.core.ImageMetadata md = frame.getImageMetadata();
            float t = md.getLong(com.google.ar.core.ImageMetadata.SENSOR_EXPOSURE_TIME) * 1e-9f;
            int iso = md.getInt(com.google.ar.core.ImageMetadata.SENSOR_SENSITIVITY);
            if (t > 0 && iso > 0 && luxSamples.size() < 4000) luxSamples.add(2.5f * 3.24f / t * 100f / iso);
        } catch (Throwable ignored) { }
    }

    /** Median measured room light of this scan, or 0 if unknown. */
    float roomLux() {
        if (luxSamples.isEmpty()) return 0;
        java.util.ArrayList<Float> c = new java.util.ArrayList<>(luxSamples);
        java.util.Collections.sort(c);
        return c.get(c.size() / 2);
    }
    float loadedLux;

    static final float FAR_M = 2.3f;        // beyond this the S24's depth from motion bends flat walls (scan1047)
    private float farShare = -1;            // smoothed share of this view's depth further than FAR_M
    private int farPixelsFrame;
    private int depthStale;                  // depth images skipped for being older than ones already used
    private int frameHits, frameSolid;      // this depth frame: points hit, and how many of them are solid already
    private float detailHere = -1;           // smoothed share of solid points where the phone points
    private int solidCount, hudCalls;        // points seen often enough to count as solid
    private float moveSpeed;                 // smoothed phone speed, m/s
    private float turnSpeed;                 // smoothed turning speed, deg/s

    private void addPoint(float x, float y, float z, int[] rgb) {
        int i = count * 3;
        pos.put(i, x); pos.put(i + 1, y); pos.put(i + 2, z);
        int j = count * 4;
        col.put(j, (byte) rgb[0]); col.put(j + 1, (byte) rgb[1]); col.put(j + 2, (byte) rgb[2]); col.put(j + 3, (byte) seenAlpha(1));
        obs[count] = 1; seenStamp[count] = stampFrame; thru[count] = 0;
        confBest[count] = (byte) curConf; nearCm[count] = (short) Math.min(32767, curMm / 10);
        dotFine[count] = (byte) (curFine ? 1 : 0);
        if (seg == null || seg.length < pos.capacity() / 3) seg = new byte[pos.capacity() / 3];
        seg[count] = segment;
        count++;
    }

    /** A spot seen again: blend the new reading in, so repeated looks clean up a bad first one. */
    private void refine(int idx, float x, float y, float z, int[] rgb) {
        int before = confBest[idx] & 0xFF;
        boolean sureNow = painted(curConf), wasPainted = before == 0 || before >= PAINT_MIN;
        if (curConf > 0 && before > 0 && before < PAINT_MIN && curConf >= PAINT_MIN) {
            // the first sure reading of a spot that only had weak ones: the dot starts again from it
            confBest[idx] = (byte) curConf; nearCm[idx] = (short) Math.min(32767, curMm / 10);
            pos.put(idx * 3, x); pos.put(idx * 3 + 1, y); pos.put(idx * 3 + 2, z);
            col.put(idx * 4, (byte) rgb[0]); col.put(idx * 4 + 1, (byte) rgb[1]); col.put(idx * 4 + 2, (byte) rgb[2]); col.put(idx * 4 + 3, (byte) seenAlpha(1));
            obs[idx] = 1; thru[idx] = 0; seenStamp[idx] = stampFrame; refined = true;
            return;
        }
        if (wasPainted && before > 0 && !sureNow) return;   // a weak reading doesn't move or recolour a painted dot
        if (curConf > before) confBest[idx] = (byte) curConf;
        if (curMm > 0) { int cm = curMm / 10; if (nearCm[idx] == 0 || cm < nearCm[idx]) nearCm[idx] = (short) Math.min(32767, cm); }
        if (obs[idx] == 0) { // a cleared spot seen solid again: start it fresh, from its second reading (in another frame)
            if (seenStamp[idx] != stampFrame) { seenStamp[idx] = stampFrame; thru[idx] = (byte) Math.min(255, (thru[idx] & 0xFF) + 1); }
            if ((thru[idx] & 0xFF) < 2) return;
            revivedSec++;
            pos.put(idx * 3, x); pos.put(idx * 3 + 1, y); pos.put(idx * 3 + 2, z);
            col.put(idx * 4, (byte) rgb[0]); col.put(idx * 4 + 1, (byte) rgb[1]); col.put(idx * 4 + 2, (byte) rgb[2]); col.put(idx * 4 + 3, (byte) seenAlpha(1));
            obs[idx] = 1; thru[idx] = 0; seenStamp[idx] = stampFrame; refined = true;
            return;
        }
        if (thru[idx] != 0 && seenStamp[idx] != stampFrame) thru[idx]--;
        int n = obs[idx] & 0xFF;
        if (n < 255) obs[idx] = (byte) (n + 1);
        if (seenStamp[idx] != stampFrame) { // count each depth frame once (many pixels land in one 2 cm spot)
            seenStamp[idx] = stampFrame;
            int a = col.get(idx * 4 + 3) & 0xFF;
            if (painted(confBest[idx] & 0xFF)) {   // a weakly seen spot stays "to scan" until a sure reading paints it
                if (a < 255) col.put(idx * 4 + 3, (byte) Math.min(255, a + 255 / SOLID_SEEN + 1));
                if (a >= 255 - 255 / SOLID_SEEN) frameSolid++;
            }
        }
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
    /** Remove the points see-through clearing marked (they stay in place, invisible, until the scan ends). */
    /**
     * The first seconds of a scan are measured before ARCore's map has settled, and those points can float in the
     * wrong place (scan1111: a ghost wall in front of the start). Points that were only ever seen in the first
     * EARLY_S seconds and never again are cleared; anything the camera came back to is kept.
     */
    static final float EARLY_S = 8f;
    private boolean loadedAny;               // points came from a saved scan (their stamps say nothing about time)
    private int earlyDepthFrames = -1; private long firstDepthMs;
    private boolean[] dropEarlyDrift() {
        if (earlyDepthFrames <= 0 || depthFrames < earlyDepthFrames * 4 || improveDir != null || loadedAny) return null;
        // Only early points the camera later saw straight past (a ghost wall from before ARCore settled); points that
        // were simply never looked at again are real coverage and stay (rk 11 Nov 21:11: this rule took out 98,218
        // points, 25% of the scan, everything only seen in the first 8 s). At most 10% of the scan.
        boolean[] early = new boolean[count]; int n = 0, notSeenAgain = 0, cap = count / 10;
        for (int i = 0; i < count; i++) {
            if (obs[i] == 0 || seenStamp[i] > earlyDepthFrames || inZone(pos.get(i * 3), pos.get(i * 3 + 1), pos.get(i * 3 + 2))) continue;
            notSeenAgain++;
            if (thru[i] == 0 || n >= cap) continue;
            early[i] = true; obs[i] = 0; col.put(i * 4 + 3, (byte) 0); n++;
        }
        log("start-of-scan drift: " + notSeenAgain + " points only seen in the first " + (int) EARLY_S + " s (" + earlyDepthFrames + " depth frames); "
                + n + " of them seen through later, taken out (kept aside)" + (n >= cap && cap > 0 ? ", capped at 10% of the scan" : ""));
        return early;
    }

    /** Points the clean-up at Done takes out are kept aside (removed-points.bin), never just deleted (data is gold):
     *  x, y, z float + r, g, b + reason (1 early drift seen through, 2 cleared by see-through while scanning, 3 seen
     *  only once, 4 seen through at Done). */
    private java.io.ByteArrayOutputStream removed; private int removedN;
    private void keepRemoved(int i, int reason) {
        if (removed == null) return;
        int a = Float.floatToIntBits(pos.get(i * 3)), b = Float.floatToIntBits(pos.get(i * 3 + 1)), c = Float.floatToIntBits(pos.get(i * 3 + 2));
        for (int v : new int[]{a, b, c}) { removed.write(v >>> 24); removed.write(v >>> 16); removed.write(v >>> 8); removed.write(v); }
        removed.write(col.get(i * 4)); removed.write(col.get(i * 4 + 1)); removed.write(col.get(i * 4 + 2)); removed.write(reason);
        removedN++;
    }

    /** dot-confidence.bin: int count, then per dot (points.bin order) the best ARCore depth confidence (0-255, 0 = not
     *  known) and the closest distance it was seen from (big-endian short, cm). Only matches points.bin as saved at Done. */
    private void saveConfidence(File dir, byte[] data) {
        try (java.io.DataOutputStream o = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(new File(dir, "dot-confidence.bin"))))) {
            o.writeInt(data.length / 3); o.write(data);
            log("dot confidence kept with the scan: dot-confidence.bin (" + data.length / 3 + " dots)");
        } catch (Exception e) { logError("dot-confidence.bin", e); }
    }

    private void saveRemoved(File dir, byte[] data) {
        try (java.io.DataOutputStream o = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(new File(dir, "removed-points.bin"))))) {
            o.writeInt(data.length / 16); o.write(data);
            log("removed points kept with the scan: removed-points.bin (" + data.length / 16 + " points)");
        } catch (Exception e) { logError("removed-points.bin", e); }
    }

    private void purgeCleared() { purgeCleared(null); }
    private void purgeCleared(boolean[] early) {
        drainDepth();   // the depth thread mustn't add points while they're moved
        int k = 0;
        for (int i = 0; i < count; i++) {
            if (obs[i] == 0) { keepRemoved(i, early != null && i < early.length && early[i] ? 1 : 2); continue; }
            if (k != i) {
                pos.put(k * 3, pos.get(i * 3)); pos.put(k * 3 + 1, pos.get(i * 3 + 1)); pos.put(k * 3 + 2, pos.get(i * 3 + 2));
                for (int c = 0; c < 4; c++) col.put(k * 4 + c, col.get(i * 4 + c));
                obs[k] = obs[i]; thru[k] = thru[i]; seenStamp[k] = seenStamp[i]; if (seg != null) seg[k] = seg[i]; confBest[k] = confBest[i]; nearCm[k] = nearCm[i]; dotFine[k] = dotFine[i]; mergedDot[k] = mergedDot[i];
            }
            k++;
        }
        if (k == count) return;
        count = k; uploaded = 0; refineCursor = 0;
        rebuildVoxelIndex();
    }

    private void dropNoise() {
        drainDepth();   // the depth thread mustn't add points while they're moved
        int keep = 0;
        boolean[] drop = new boolean[count];
        for (int i = 0; i < count; i++) {
            float x = pos.get(i * 3), y = pos.get(i * 3 + 1), z = pos.get(i * 3 + 2);
            // one-off specks, and points in space the camera later saw straight through (mid-air noise)
            drop[i] = (obs[i] & 0xFF) < 2 || (!inZone(x, y, z) && seenThroughDrained(x, y, z));
            if (!drop[i]) keep++;
        }
        droppedNoise = 0;
        if (count < 2000 || keep < count * 0.3f) return;
        int k = 0;
        for (int i = 0; i < count; i++) {
            if (drop[i]) { keepRemoved(i, (obs[i] & 0xFF) < 2 ? 3 : 4); continue; }
            if (k != i) {
                pos.put(k * 3, pos.get(i * 3)); pos.put(k * 3 + 1, pos.get(i * 3 + 1)); pos.put(k * 3 + 2, pos.get(i * 3 + 2));
                for (int c = 0; c < 4; c++) col.put(k * 4 + c, col.get(i * 4 + c));
                obs[k] = obs[i]; thru[k] = thru[i]; seenStamp[k] = seenStamp[i]; if (seg != null) seg[k] = seg[i]; confBest[k] = confBest[i]; nearCm[k] = nearCm[i]; dotFine[k] = dotFine[i]; mergedDot[k] = mergedDot[i];
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

    // ---- surface fusion thread (rk 10 Nov: the S24's drawing thread spent 73% of its time fusing depth) ----
    private final java.util.concurrent.ExecutorService fuseExec = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run(); }, "tsdf-fuse"); return t; });
    private volatile java.util.concurrent.Future<?> fuseLast;
    private float[] fuseBuf = new float[5 * 16384];
    private int fuseN;
    private volatile boolean fuseDrained = true;

    /** Hands this depth frame's surface samples to the fusion thread. At most one frame waits behind the running one. */
    private void submitFuse(final float ox, final float oy, final float oz) {
        final int n = fuseN;
        final float[] buf = java.util.Arrays.copyOf(fuseBuf, n * 5);
        java.util.concurrent.Future<?> prev = fuseLast;
        if (prev != null && !prev.isDone()) {
            long t0 = System.nanoTime();
            try { prev.get(); } catch (Exception e) { logError("fuse wait", new RuntimeException(e)); }
            perf.note("ms: waited for fusion", (System.nanoTime() - t0) / 1e6);
        }
        synchronized (fuseExec) {
        fuseDrained = false;
        fuseLast = fuseExec.submit(() -> {
            long t0 = System.nanoTime();
            try {
                synchronized (tsdf) {
                    tsdf.beginFrame(); detail.beginFrame();
                    int[] rgb = new int[3];
                    for (int i = 0; i < n; i++) {
                        int bits = Float.floatToRawIntBits(buf[i * 5 + 3]);
                        rgb[0] = bits & 255; rgb[1] = (bits >> 8) & 255; rgb[2] = (bits >> 16) & 255;
                        float clear = buf[i * 5 + 4];
                        if ((bits & (1 << 25)) == 0) tsdf.integrate(ox, oy, oz, buf[i * 5], buf[i * 5 + 1], buf[i * 5 + 2], rgb, clear);
                        if ((bits & (1 << 24)) != 0) detail.integrate(ox, oy, oz, buf[i * 5], buf[i * 5 + 1], buf[i * 5 + 2], rgb, clear);
                    }
                }
            } catch (Throwable e) { logError("fuse", new RuntimeException(e)); }
            perf.note("ms: surface fusion (thread)", (System.nanoTime() - t0) / 1e6);
        });
        }
    }

    // ---- GPU surface (GpuTsdf, debug switch "GPU surface"; rk 10 Nov) ----
    volatile boolean gpuSurfaceWanted;
    private GpuTsdf gpuTsdf;
    private boolean gpuSurfaceBroken;
    private float[] gpuDepth; private int[] gpuRgb;
    private long gpuSyncAt;

    /** GL thread: whether this frame's room surface goes to the GPU (making the block around the camera on first use). */
    private boolean gpuSurfaceReady(float[] m) {
        if (!gpuSurfaceWanted || gpuSurfaceBroken) return false;
        if (gpuTsdf == null) {
            gpuTsdf = new GpuTsdf(m[12] - 6, m[13] - 2.2f, m[14] - 6, m[12] + 6, m[13] + 2.6f, m[14] + 6, tsdf.vs, tsdf.trunc, tsdf.carve, tsdf.carveWeight);
            saveGpuInfo(gpuTsdf.failed ? "GPU surface unavailable, the CPU fuses: " + gpuTsdf.error + "\n" + GpuTsdf.info : "GPU surface on\n" + GpuTsdf.info);
            if (gpuTsdf.failed) { log("GPU surface unavailable: " + gpuTsdf.error); gpuTsdf = null; gpuSurfaceBroken = true; return false; }
            log("GPU surface on: " + gpuTsdf.nx + "x" + gpuTsdf.ny + "x" + gpuTsdf.nz + " voxels");
        }
        return true;
    }

    private void saveGpuInfo(String s) {
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(new File(activity.getFilesDir(), "gpu-info.txt"))) { o.write(s.getBytes("UTF-8")); }
        catch (Exception ignored) { }
    }

    /** GL thread: the GPU's changed bricks into the CPU surface (on the fusion thread, in order with its work). */
    private void gpuSyncToCpu() {
        GpuTsdf g = gpuTsdf;
        if (g == null) return;
        final GpuTsdf.Sync sy = g.sync();
        if (sy == null || sy.count == 0) return;
        syncedBricks += sy.count; if (sy.count >= GpuTsdf.MAX_SYNC_BRICKS) syncFull++;
        synchronized (fuseExec) { fuseDrained = false;
        fuseLast = fuseExec.submit(() -> { synchronized (tsdf) { g.apply(sy, tsdf); } }); }
    }

    /** GL thread: everything the GPU has, into the CPU surface, before it's read (Done, depth AI). */
    private volatile int syncedBricks, syncFull;

    private void gpuSyncAll() {
        int before = tsdf.surfaceVoxels(), bricks = 0;
        for (int k = 0; k < 64 && gpuTsdf != null; k++) {
            GpuTsdf.Sync sy = gpuTsdf.sync();
            if (sy == null || sy.count == 0) break;
            final GpuTsdf g = gpuTsdf;
            synchronized (fuseExec) { fuseDrained = false;
            fuseLast = fuseExec.submit(() -> { synchronized (tsdf) { g.apply(sy, tsdf); } }); }
            bricks += sy.count;
            if (sy.count < GpuTsdf.MAX_SYNC_BRICKS) break;
        }
        drainFuse();
        // (15:09 scan: the live mesh covered the whole room at Done, the saved surface 16% of it: does this step erase it?)
        log(String.format(java.util.Locale.US, "GPU surface at Done: %d bricks read back; surface voxels %d -> %d", bricks, before, tsdf.surfaceVoxels()));
    }

    /**
     * GL thread, debug "GPU self-test": the scan's saved depth frames (recs, up to 300) fused on the CPU (as now) and on
     * the GPU, compared: surface voxels in each, how many agree (within a voxel), triangles, and timings. Logged, shown,
     * and written to files/gpu-selftest.txt.
     */
    void runGpuSelfTest() {
        java.util.List<ScanExport.Rec> rs = new java.util.ArrayList<>(recs);
        if (rs.size() < 10) { activity.updateStatus("GPU self-test: scan something first (needs saved depth frames)."); return; }
        if (rs.size() > 300) rs = rs.subList(rs.size() - 300, rs.size());
        StringBuilder out = new StringBuilder();
        try {
            Tsdf cpu = new Tsdf(1_500_000, tsdf.vs, tsdf.trunc, tsdf.carve); cpu.minW = tsdf.minW; cpu.carveWeight = tsdf.carveWeight;
            long c0 = System.nanoTime();
            int[] grey = {180, 180, 180};
            for (ScanExport.Rec r : rs) {
                cpu.beginFrame();
                for (int v = 1; v < r.h - 1; v++) for (int u = 1; u < r.w - 1; u++) {
                    float d = selfTestDepth(r, u, v);
                    if (d <= 0) continue;
                    float xc = (u + 0.5f - r.cx) * d / r.fx, yc = (r.cy - (v + 0.5f)) * d / r.fy, zc = -d;
                    float[] m = r.pose;
                    float wx = m[0] * xc + m[4] * yc + m[8] * zc + m[12], wy = m[1] * xc + m[5] * yc + m[9] * zc + m[13], wz = m[2] * xc + m[6] * yc + m[10] * zc + m[14];
                    int rpx = Math.max(1, Math.min(6, Math.round(0.6f * cpu.vs * r.fx / d)));
                    float near = d;
                    for (int ny = -1; ny <= 1; ny++) for (int nx = -1; nx <= 1; nx++) {
                        int uu = u + nx * rpx, vv = v + ny * rpx;
                        if (uu < 1 || vv < 1 || uu >= r.w - 1 || vv >= r.h - 1) continue;
                        float d2 = (r.depth[vv * r.w + uu] & 0xFFFF) * 0.001f;
                        if (d2 >= 0.2f && d2 < near) near = d2;
                    }
                    float rayLen = (float) Math.sqrt(xc * xc + yc * yc + zc * zc);
                    cpu.integrate(m[12], m[13], m[14], wx, wy, wz, grey, rayLen * near / d);
                }
            }
            long cpuMs = (System.nanoTime() - c0) / 1_000_000;
            float[] p0 = rs.get(0).pose;
            GpuTsdf g = new GpuTsdf(p0[12] - 6, p0[13] - 2.2f, p0[14] - 6, p0[12] + 6, p0[13] + 2.6f, p0[14] + 6, tsdf.vs, tsdf.trunc, tsdf.carve, tsdf.carveWeight);
            if (g.failed) { out.append("GPU unavailable: ").append(g.error); throw new RuntimeException(g.error); }
            long g0 = System.nanoTime();
            for (ScanExport.Rec r : rs) {
                float[] dd = new float[r.w * r.h]; int[] col = new int[r.w * r.h];
                for (int v = 1; v < r.h - 1; v++) for (int u = 1; u < r.w - 1; u++) { dd[v * r.w + u] = selfTestDepth(r, u, v); col[v * r.w + u] = 0xB4B4B4; }
                g.integrate(dd, col, r.w, r.h, r.pose, r.fx, r.fy, r.cx, r.cy);
                if (g.failed) break;
            }
            GLES20.glFinish();
            long gpuMs = (System.nanoTime() - g0) / 1_000_000;
            Tsdf fromGpu = new Tsdf(1_500_000, tsdf.vs, tsdf.trunc, tsdf.carve); fromGpu.minW = tsdf.minW;
            long s0 = System.nanoTime(); int bricks = 0;
            for (int k = 0; k < 64 && !g.failed; k++) {
                GpuTsdf.Sync sy = g.sync();
                if (sy == null || sy.count == 0) break;
                g.apply(sy, fromGpu); bricks += sy.count;
            }
            long syncMs = (System.nanoTime() - s0) / 1_000_000;
            boolean gpuFailed = g.failed; String gpuErr = g.error;
            g.release();
            if (gpuFailed) throw new RuntimeException(gpuErr);
            int sc = cpu.surfaceVoxels(), sg = fromGpu.surfaceVoxels();
            int cInG = cpu.surfaceOverlap(fromGpu), gInC = fromGpu.surfaceOverlap(cpu);
            int tc = cpu.extractMesh(), tg = fromGpu.extractMesh();
            out.append(String.format(java.util.Locale.US,
                    "GPU self-test, %d depth frames %dx%d: CPU %d ms, GPU %d ms (+ %d ms reading %d bricks back).%n"
                  + "Surface voxels: CPU %d, GPU %d. CPU's found by the GPU %.1f%%, GPU's found by the CPU %.1f%%.%n"
                  + "Triangles: CPU %d, GPU %d.",
                    rs.size(), rs.get(0).w, rs.get(0).h, cpuMs, gpuMs, syncMs, bricks, sc, sg,
                    sc > 0 ? 100f * cInG / sc : 0, sg > 0 ? 100f * gInC / sg : 0, tc, tg));
        } catch (Throwable e) {
            out.append("\nGPU self-test failed: ").append(e.getMessage());
        }
        String txt = out.toString();
        log(txt.replace('\n', ' '));
        activity.updateStatus(txt);
        try { java.nio.file.Files.write(new java.io.File(activity.getFilesDir(), "gpu-selftest.txt").toPath(), txt.getBytes()); } catch (Exception ignored) { }
    }

    /** A saved depth frame's pixel as the live fusion uses it (0.2–4 m, confidence ≥ 40, no flying pixels), or 0. */
    private static float selfTestDepth(ScanExport.Rec r, int u, int v) {
        int mm = r.depth[v * r.w + u] & 0xFFFF;
        if (mm < 200 || mm > 4000) return 0;
        if (r.conf != null && (r.conf[v * r.w + u] & 0xFF) < 40) return 0;
        int tol = mm / 25 + 20;
        if (Math.abs((r.depth[v * r.w + u - 1] & 0xFFFF) - mm) > tol || Math.abs((r.depth[v * r.w + u + 1] & 0xFFFF) - mm) > tol
                || Math.abs((r.depth[(v - 1) * r.w + u] & 0xFFFF) - mm) > tol || Math.abs((r.depth[(v + 1) * r.w + u] & 0xFFFF) - mm) > tol) return 0;
        return mm * 0.001f;
    }

    /** GL thread: drop the GPU block (new scan / reset). */
    private void gpuSurfaceReset() {
        if (gpuTsdf != null) { gpuTsdf.release(); gpuTsdf = null; }
        gpuSurfaceBroken = false;
    }

    /** Waits until the fusion thread has fused everything handed to it (before anything else reads or changes the surface). */
    private void drainFuse() {
        drainDepth();
        java.util.concurrent.Future<?> f = fuseLast;
        if (f == null || fuseDrained) return;
        try { f.get(); } catch (Exception e) { logError("fuse drain", new RuntimeException(e)); }
        fuseDrained = true;
    }

    private boolean seenThroughDrained(float x, float y, float z) { drainFuse(); return tsdf.seenThrough(x, y, z); }

    // ---- Depth from the phone's own motion (rk 10 Nov: S24 rooms come out ~3% big). Against the iPhone's LiDAR the
    // S24's depth is compressed (three rooms): under 1 m it reads 15-20% too far, beyond 3 m a few % too near
    // (raw ≈ 0.87·true + 0.23 m). ARCore's feature points are triangulated from the motion (camera + IMU), so their
    // distances are metric: the depth at each feature's pixel is fitted against the feature's distance (raw = k·true + c,
    // robust), and depth is corrected as (raw - c) / k before it's fused, once the fit has enough spread. ----
    static final boolean DEPTH_FIX = true;
    /** Apply the fit to the depth (false: measure and log only). Off until a real scan shows it helps: offline, a fixed
     *  correction of this form didn't improve the sizes on three rooms (the keyframes' depth and the alignment's free
     *  scale muddy the test), so first the phone's own fits are logged against the iPhone's sizes. */
    static final boolean DEPTH_FIX_APPLY = false;
    private volatile float depthK = 1f, depthC = 0f;
    private final Object featLock = new Object();
    /** depth-pairs.bin: (feature distance, raw depth, u, v, feature height) for the offline depth-correction fit. */
    private final float[] pairBuf = new float[5 * 200000]; private int pairN;
    // 400k (was 60k): a long scan filled the ring and the ceiling's features from early on were overwritten (15:09 scan:
    // 60000 heights, the ceiling read 2.35 m in a 2.88 m room)
    private final float[] featY = new float[400000]; private int featN, featAt;
    private final float[] dfTrue = new float[6000], dfRaw = new float[6000];
    private int dfN, dfAt;
    private long dfLastFit;

    private void depthFitCollect(Frame frame, Camera camera, ShortBuffer dbuf, int dRow, int dPix, ByteBuffer cbuf, int cRow, int cPix,
                                 int dw, int dh, float fx, float fy, float cx, float cy) {
        if (!DEPTH_FIX) return;
        float[] raw = new float[16], view = new float[16];
        camera.getPose().toMatrix(raw, 0);   // ARCore's own frame: the feature points are in it too
        Matrix.invertM(view, 0, raw, 0);
        try (PointCloud pc = frame.acquirePointCloud()) {
            FloatBuffer pts = pc.getPoints();
            int nf = pts.remaining() / 4;
            int[] s9 = new int[9];
            for (int i = 0; i < nf; i++) {
                float x = pts.get(i * 4), y = pts.get(i * 4 + 1), z = pts.get(i * 4 + 2), conf = pts.get(i * 4 + 3);
                if (conf < 0.3f) continue;
                // every sure feature's height (world, with the re-alignment correction), for the ceiling (RoomModel)
                float wy = hasFix ? poseFix[1] * x + poseFix[5] * y + poseFix[9] * z + poseFix[13] : y;
                synchronized (featLock) { featY[featAt] = wy; featAt = (featAt + 1) % featY.length; if (featN < featY.length) featN++; }
                float xc = view[0] * x + view[4] * y + view[8] * z + view[12], yc = view[1] * x + view[5] * y + view[9] * z + view[13];
                float zc = view[2] * x + view[6] * y + view[10] * z + view[14];
                float dz = -zc;
                if (dz < 0.3f || dz > 4f) continue;
                int u = (int) (fx * xc / dz + cx), v = (int) (cy - fy * yc / dz);
                if (u < 1 || v < 1 || u >= dw - 1 || v >= dh - 1) continue;
                int n = 0;   // the median of the sure 3x3 depth there
                for (int b = -1; b <= 1; b++) for (int a = -1; a <= 1; a++) {
                    int mm = dbuf.get((v + b) * dRow + (u + a) * dPix) & 0xFFFF;
                    if (mm < 200 || mm > 5000) continue;
                    if (cbuf != null && (cbuf.get((v + b) * cRow + (u + a) * cPix) & 0xFF) < 55) continue;
                    s9[n++] = mm;
                }
                if (n < 5) continue;
                java.util.Arrays.sort(s9, 0, n);
                float r = s9[n / 2] * 0.001f;
                if (Math.abs(r - dz) > 0.5f * dz) continue;   // a different surface there (edge, occlusion)
                dfTrue[dfAt] = dz; dfRaw[dfAt] = r; dfAt = (dfAt + 1) % dfTrue.length; if (dfN < dfTrue.length) dfN++;
                // every pair kept for the offline fit (rk 10 Nov: ceilings read 1-10 cm low): feature distance, depth,
                // the pixel's place in the image (where in the view: up/down tilt) and the feature's height
                if (pairN < pairBuf.length / 5) { int o = pairN * 5; pairBuf[o] = dz; pairBuf[o + 1] = r; pairBuf[o + 2] = (float) u / dw; pairBuf[o + 3] = (float) v / dh;
                    pairBuf[o + 4] = hasFix ? poseFix[1] * x + poseFix[5] * y + poseFix[9] * z + poseFix[13] : y; pairN++; }
            }
        } catch (Throwable ignored) { }
        long now = System.currentTimeMillis();
        if (now - dfLastFit > 2000 && dfN >= 300) { dfLastFit = now; depthFit(); }
    }

    /** Robust line raw = k·true + c (iteratively reweighted, Huber 3 cm); used once the distances spread 0.8 m+. */
    private void depthFit() {
        int n = dfN;
        float[] t = java.util.Arrays.copyOf(dfTrue, n); java.util.Arrays.sort(t);
        float spread = t[n * 9 / 10] - t[n / 10];
        double k = 1, c = 0;
        for (int it = 0; it < 8; it++) {
            double sw = 0, sx = 0, sy = 0, sxx = 0, sxy = 0;
            for (int i = 0; i < n; i++) {
                double x = dfTrue[i], y = dfRaw[i], r = Math.abs(y - (k * x + c));
                double w = r < 0.03 ? 1 : 0.03 / r;
                sw += w; sx += w * x; sy += w * y; sxx += w * x * x; sxy += w * x * y;
            }
            double den = sw * sxx - sx * sx;
            if (Math.abs(den) < 1e-9) return;
            k = (sw * sxy - sx * sy) / den; c = (sy - k * sx) / sw;
        }
        perf.note("depth fit k x1000", k * 1000); perf.note("depth fit c mm", c * 1000); perf.note("depth fit pairs", n);
        boolean sure = spread >= 0.8f && k > 0.7 && k < 1.3 && c > -0.4 && c < 0.5;
        if (!sure) { log(String.format(java.util.Locale.US, "depth fit (not used yet): raw = %.3f true %+.3f m, %d pairs, spread %.2f m", k, c, n, spread)); return; }
        if (!DEPTH_FIX_APPLY) { log(String.format(java.util.Locale.US, "depth fit (measured, not applied): raw = %.3f true %+.3f m (%d pairs, spread %.2f m)", k, c, n, spread)); return; }
        depthK += ((float) k - depthK) * 0.3f; depthC += ((float) c - depthC) * 0.3f;   // smoothly towards the fit
        log(String.format(java.util.Locale.US, "depth fit: raw = %.3f true %+.3f m (%d pairs, spread %.2f m); correcting with %.3f / %+.3f", k, c, n, spread, depthK, depthC));
    }

    // Re-alignment after a tracking loss (see integrate).
    private volatile boolean needRealign;
    private int realignOk;
    private long realignSince, realignHintAt, nowMsFrame;

    /** Correction for every camera pose after a tracking loss (ARCore came back in a shifted frame): world ← ARCore. */
    private final float[] poseFix = new float[16];
    { android.opengl.Matrix.setIdentityM(poseFix, 0); }
    private boolean hasFix;
    /** Which stretch of tracking each point was captured in (0, then +1 after every loss): saved as segments.bin so
     *  layers can be told apart later. */
    private byte segment;
    private byte[] seg;
    private float[] fixTry = new float[16];
    private final float[] fixPrev = new float[16];

    private void applyFix(float[] m) {
        if (!hasFix) return;
        float[] r = new float[16];
        android.opengl.Matrix.multiplyMM(r, 0, poseFix, 0, m, 0);
        System.arraycopy(r, 0, m, 0, 16);
    }

    private void fixPoint(float[] p) {
        if (!hasFix) return;
        float x = p[0], y = p[1], z = p[2];
        p[0] = poseFix[0] * x + poseFix[4] * y + poseFix[8] * z + poseFix[12];
        p[1] = poseFix[1] * x + poseFix[5] * y + poseFix[9] * z + poseFix[13];
        p[2] = poseFix[2] * x + poseFix[6] * y + poseFix[10] * z + poseFix[14];
    }

    /** The scan so far as point centroids (taken when tracking is lost), for the re-alignment (Realign). */
    private volatile Realign realign;
    private boolean sessionRestarted;
    private int realignTick;

    private void snapshotForRealign() {
        long t0 = System.currentTimeMillis();
        drainDepth();
        final int n = count;
        final float floorNow = walkFloorY();
        final float[] xyz = new float[n * 3];
        FloatBuffer pd = pos.duplicate(); pd.position(0); pd.get(xyz, 0, n * 3);
        fixTry = poseFix.clone();
        stayGuess = null;
        realign = null; realignBuilding = true;
        synchronized (goalTried) { goalTried.clear(); }
        heldGuess = null; viewShape = -1;
        pickGoal("lost");
        // The cells take ~3 s for a big scan (10:44 scan: 2.8 s on the drawing thread): built aside; nothing is fused
        // meanwhile (realigned() waits for them).
        new Thread(() -> {
            Realign r;
            // out of memory here killed the app on a 512 MB phone (S23 Ultra, 11 Nov): skip this try instead
            try { r = new Realign(xyz, n); r.buildPlan(xyz, n, floorNow); }
            catch (OutOfMemoryError e) {
                // an empty matcher: nothing lines up, so nothing is fused from an unchecked pose, and "Carry on from
                // here" (offered after 30 s) still works
                r = new Realign(); r.finish();
                log("ERROR realign snapshot: out of memory (" + n + " points, heap " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576 + " MB); waiting for the user");
            }
            realign = r; realignBuilding = false;
            log("realign snapshot: " + r.nCoarse + " / " + r.nFine + " cells in " + (System.currentTimeMillis() - t0) + " ms");
        }, "realign-snapshot").start();
    }

    /** Debug tools "Plain-wall warning". */
    static boolean PLAIN_WARNING = false;   // (rk 11 Nov 22:02: no tracking-loss notices; the standing line stays)
    private long warnFewSince, plainWarnBuzzAt; private volatile long plainWarnUntil;
    private long fewFeaturesSince, plainHintAt; private volatile long plainHintUntil; private volatile int featuresNow = -1;

    // ---- the held view while lost ----
    private final float[] heldView = new float[16], heldRot = new float[9];
    private long holdStartMs, holdPullAt = -1; private float holdBack = 1.5f, holdUp = 0.3f;
    private boolean haveHeldView, heldRotOk, holding, wasHolding;
    private final float[] lastShownView = new float[16], prevShown = new float[16], rawView = new float[16], relRaw = new float[16], relShown = new float[16]; private long holdEndMs;
    private boolean relAnchored; private volatile int heldMode;   // 0 live, 1 held by the rotation sensor, 2 held + ARCore's movement
    private long lastNewDepthMs, stateLogAt, trackingBackMs;
    private volatile int viewKnown, viewNew; private int newDepthSince, staleSince0, notYetSince0;
    final float[] scanRot = new float[9]; volatile boolean scanRotOk;
    private volatile float accelJitter; volatile boolean walkingWhileLost;
    /** The rotation sensor while scanning (MainActivity registers it): the device's rotation, and whether it's shaking
     *  like walking. */
    final android.hardware.SensorEventListener scanRotListener = new android.hardware.SensorEventListener() {
        private float lastA = -1;
        @Override public void onSensorChanged(android.hardware.SensorEvent e) {
            if (e.sensor.getType() == android.hardware.Sensor.TYPE_LINEAR_ACCELERATION) {
                float a = (float) Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]);
                accelJitter = accelJitter * 0.9f + a * 0.1f;   // walking: ~1-2 m/s² of bobbing; standing still: ~0.1-0.3
                walkingWhileLost = accelJitter > 0.9f;
                return;
            }
            float[] r = new float[9];
            android.hardware.SensorManager.getRotationMatrixFromVector(r, e.values);
            synchronized (scanRot) { System.arraycopy(r, 0, scanRot, 0, 9); scanRotOk = true; }
        }
        @Override public void onAccuracyChanged(android.hardware.Sensor s, int a) { }
    };

    /** Once a second: tracking, what the view shows, whether depth is fused, new/stale/missing depth images. And: ARCore
     *  handing out only old depth images for 8 s while tracking (13:52 scan: 86-122 s, 354 stale images, nothing more
     *  was fused and the re-align never ran) restarts the AR session, then re-aligns like after a loss. 8 s, and not in
     *  the first 10 s after tracking returns (14:01 scan: at 4 s it restarted twice; the second restart cost 15 s). */
    private long memLogAt;

    private static long arr(Object a) {
        if (a == null) return 0;
        if (a instanceof byte[]) return ((byte[]) a).length;
        if (a instanceof short[]) return 2L * ((short[]) a).length;
        if (a instanceof int[]) return 4L * ((int[]) a).length;
        if (a instanceof float[]) return 4L * ((float[]) a).length;
        if (a instanceof long[]) return 8L * ((long[]) a).length;
        if (a instanceof java.nio.Buffer) { java.nio.Buffer b = (java.nio.Buffer) a; return (long) b.capacity() * (a instanceof FloatBuffer || a instanceof java.nio.IntBuffer ? 4 : a instanceof java.nio.ShortBuffer ? 2 : 1); }
        return 0;
    }

    /** One "memory (when):" line in the scan log: our own big arrays and buffers by name (MB), then what the Java
     *  runtime and Android say (Debug.MemoryInfo, read on a worker thread: it takes tens of ms). So a field log shows
     *  what fills the phone (11 Nov: a Galaxy S23 Ultra ran out at 1.2 M points without us knowing what took it). */
    void logMemory(String when) {
        StringBuilder b = new StringBuilder();
        try {
            java.util.function.BiConsumer<String, Long> add = (k, v) -> { if (v >= 512 * 1024) b.append(String.format(java.util.Locale.US, ", %s %.1f", k, v / 1048576f)); };
            add.accept("surface tables", tsdf.heapBytes());
            add.accept("detail tables", detail.heapBytes());
            add.accept("voxel map", voxels.heapBytes());
            add.accept("point arrays", arr(obs) + arr(seenStamp) + arr(thru) + arr(seg));
            add.accept(offHeapMapped ? "points (mapped file, off the heap)" : "points (direct buffers, on the heap)", arr(pos) + arr(col));
            add.accept("feature heights", arr(featY));
            long kj = 0, kd = 0; int kn = 0;
            for (PhotoRoom.Keyframe k : new java.util.ArrayList<>(photos.keyframes)) { kj += arr(k.jpg); kd += arr(k.depth); kn++; }
            add.accept(kn + " keyframe photos", kj); add.accept("keyframe depth", kd);
            long rb = 0; int rn = 0;
            synchronized (this) { for (ScanExport.Rec r : recs) { rb += arr(r.depth) + arr(r.conf) + arr(r.gray); rn++; } }
            add.accept(rn + " recorded depth frames", rb);
            long eb = 0; int en = 0;
            synchronized (this) { for (EvFrame f : evRing) { eb += arr(f.d); en++; } for (EvFrame f : evKept) { eb += arr(f.d); en++; } }
            add.accept(en + " event depth frames", eb);
            add.accept("pose log", arr(poseLog.ts) + arr(poseLog.p) + arr(poseLog.state) + arr(poseLog.reason));
            Realign ra = realign, rs = shadowRealign;
            add.accept("re-align cells", (ra != null ? ra.heapBytes() : 0) + (rs != null && rs != ra ? rs.heapBytes() : 0));
            add.accept("flat tiles", flatTiles.heapBytes());
            synchronized (this) { add.accept("text log", 2L * log.length()); }
            add.accept("path", arr(path));
        } catch (Throwable e) { b.append(", (counting failed: ").append(e).append(')'); }
        final String ours = b.length() > 2 ? b.substring(2) : "-";
        final int pts = count;
        new Thread(() -> {
            Runtime rt = Runtime.getRuntime();
            String sys;
            try {
                android.os.Debug.MemoryInfo mi = new android.os.Debug.MemoryInfo();
                android.os.Debug.getMemoryInfo(mi);
                sys = String.format(java.util.Locale.US, "; Android (PSS, MB): java %s, native %s, graphics %s, code %s, other %s, total %s",
                        kb(mi.getMemoryStat("summary.java-heap")), kb(mi.getMemoryStat("summary.native-heap")), kb(mi.getMemoryStat("summary.graphics")),
                        kb(mi.getMemoryStat("summary.code")), kb(mi.getMemoryStat("summary.private-other")), kb(mi.getMemoryStat("summary.total-pss")));
            } catch (Throwable e) { sys = "; Android memory info failed: " + e; }
            log(String.format(java.util.Locale.US, "memory (%s, build %d = Build " + activity.buildStamp() + ", %d of max %d points): Java heap used %d of %d MB, free %d, limit %d MB (phone's real limit %d)%s, native heap %d MB; ours (MB): %s%s",
                    when, activity.buildCode(), pts, MAX_POINTS, (rt.totalMemory() - rt.freeMemory()) >> 20, rt.totalMemory() >> 20, rt.freeMemory() >> 20, HEAP_LIMIT >> 20, rt.maxMemory() >> 20,
                    FORCED_LOW_MEMORY ? ", low memory mode (forced, like a 512 MB phone)" : LOW_MEMORY ? ", low memory phone" : "",
                    android.os.Debug.getNativeHeapAllocatedSize() >> 20, ours, sys));
        }, "memory-log").start();
    }

    private static String kb(String v) { try { return String.valueOf(Integer.parseInt(v) / 1024); } catch (Exception e) { return "?"; } }

    private void logState(TrackingState ts, long nowMs) {
        logLinkErrors();
        if (viewing || !everTracked) return;
        if (ts == TrackingState.TRACKING && lastNewDepthMs > 0 && nowMs - lastNewDepthMs > (featuresNow >= 50 ? 8000 : 10000) && nowMs - trackingBackMs > 10000 && depthSupported && recoveries < 10 && count > 2000) {   // (any feature count after 10 s: 21:11 scan, depth frozen 25 s with 0 features; saved logs: freezes of 7, 9 and 24 s)   // (not on a plain view: there depth just has nothing to see, and a restart loses the map; 15:56 scan)
            recoveries++;
            log(String.format(java.util.Locale.US, "no new depth for %.1f s while tracking (%d old images repeated): restarting AR session (attempt %d)", (nowMs - lastNewDepthMs) / 1000f, depthStale - staleSince0, recoveries));
            lastNewDepthMs = nowMs;
            if (!needRealign) {
                needRealign = true; realignOk = 0; realignSince = nowMs; realignHintAt = nowMs; realignTick = 0; newPartOffered = false; forceResume = false; recentPts.clear(); realignResult = null; jumpGuess = null;
                snapshotForRealign();
            }
            sessionRestarted = true; recentPts.clear();
            activity.restartSession();
        }
        String fusion = ts != TrackingState.TRACKING ? "off (lost)" : needRealign ? "off (lining up)" : nowMs < settleUntil ? "off (settling)" : "on";
        fusionState = fusion;
        if (nowMs - stateLogAt < 1000) return;
        stateLogAt = nowMs;
        int vk0 = viewKnown, vn0 = viewNew; viewKnown = 0; viewNew = 0;
        log(String.format(java.util.Locale.US, "state: %s, view %s, fusion %s, depth new %d stale %d not ready %d, looking at %s%s", ts,
                heldMode == 0 ? "live" : heldMode == 1 ? "held (rotation sensor)" : "held (ARCore movement)", fusion,
                newDepthSince, depthStale - staleSince0, depthNotYet - notYetSince0,
                vk0 + vn0 > 0 ? Math.round(100f * vk0 / (vk0 + vn0)) + "% scanned surface" : "-", walkingWhileLost ? ", walking" : "")
                + (gpuTsdf != null ? ", GPU bricks read back " + syncedBricks + (syncFull > 0 ? " (" + syncFull + " full)" : "") : "")
                + String.format(java.util.Locale.US, ", heap %d MB, ARCore features %d", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20, featuresNow)
                + goalOffText());
        syncedBricks = 0; syncFull = 0;
        newDepthSince = 0; staleSince0 = depthStale; notYetSince0 = depthNotYet;
        if (memLogAt == 0 || nowMs - memLogAt >= 30000) { logMemory(memLogAt == 0 ? "scan start" : "every 30 s"); memLogAt = nowMs;
            log(String.format(java.util.Locale.US, "load: app %.1f processor cores busy (ARCore included), depth late %d times so far, heat %d", cpuCores, lateEvents, thermal)); }
    }

    // ---- Evidence for the alignment after map jumps (rk 10 Nov 15:13): depth frames around every jump, loss and
    // resume (events-depth.bin), the pose at 10 Hz with time and rotation (pose-track.bin), and a shadow check of the
    // alignment for 20 s after each resume, logged but not applied. ----
    static final class EvFrame { long ts; long ms; float[] raw, fix; float fx, fy, cx, cy; int w, h; short[] d; }
    private final java.util.ArrayDeque<EvFrame> evRing = new java.util.ArrayDeque<>();
    private final java.util.ArrayList<EvFrame> evKept = new java.util.ArrayList<>();
    private long evUntil, evLastMs; static final int EV_MAX = 400;   // ~29 KB each: at most ~12 MB

    private synchronized void evCapture(java.nio.ShortBuffer dbuf, int dRow, int dPix, int dw, int dh, float fx, float fy, float cx, float cy, float[] m, long ts) {
        long now = System.currentTimeMillis();
        if (now - evLastMs < 300) return;   // about 3 a second
        evLastMs = now;
        EvFrame f = new EvFrame(); f.ts = ts; f.ms = now - t0; f.raw = m.clone(); f.fix = poseFix.clone(); f.fx = fx; f.fy = fy; f.cx = cx; f.cy = cy; f.w = dw; f.h = dh;
        f.d = new short[dw * dh];
        for (int v = 0; v < dh; v++) for (int u = 0; u < dw; u++) f.d[v * dw + u] = dbuf.get(v * dRow + u * dPix);
        if (now < evUntil) { if (evKept.size() < EV_MAX) evKept.add(f); }
        else { evRing.addLast(f); while (evRing.size() > 15) evRing.removeFirst(); }   // the last ~5 s
    }

    /** A jump, loss or resume: keep the last ~5 s and the next 20 s of depth frames. */
    private synchronized void evMark() {
        while (!evRing.isEmpty() && evKept.size() < EV_MAX) evKept.add(evRing.removeFirst());
        evUntil = System.currentTimeMillis() + 20000;
    }

    private synchronized void saveEvents(File dir) {
        if (!evKept.isEmpty()) try (java.io.DataOutputStream o = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(new File(dir, "events-depth.bin"))))) {
            o.writeInt(evKept.size());
            for (EvFrame f : evKept) {
                o.writeLong(f.ts); o.writeLong(f.ms); for (float v : f.raw) o.writeFloat(v); for (float v : f.fix) o.writeFloat(v);
                o.writeFloat(f.fx); o.writeFloat(f.fy); o.writeFloat(f.cx); o.writeFloat(f.cy); o.writeInt(f.w); o.writeInt(f.h);
                for (short v : f.d) o.writeShort(v);
            }
        } catch (Exception e) { logError("events depth", e); }
        if (trackN > 0) try (java.io.DataOutputStream o = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(new File(dir, "pose-track.bin"))))) {
            o.writeInt(trackN); for (int i = 0; i < trackN * TRACK_F; i++) o.writeFloat(track[i]);
        } catch (Exception e) { logError("pose track", e); }
    }

    // pose-track.bin: per sample (10 a second) ms since app start, tracking (1/0), lining up (1/0), ARCore's pose
    // tx ty tz qx qy qz qw, the correction's x z and turn (deg)
    static final int TRACK_F = 13; private final float[] track = new float[13 * 36000]; private int trackN; private long trackLastMs;
    private void trackPose(Camera camera, TrackingState ts) {
        long now = System.currentTimeMillis();
        if (now - trackLastMs < 100 || trackN >= track.length / TRACK_F) return;
        trackLastMs = now;
        Pose p = camera.getPose(); int o = trackN * TRACK_F;
        track[o] = now - t0; track[o + 1] = ts == TrackingState.TRACKING ? 1 : 0; track[o + 2] = needRealign ? 1 : 0;
        track[o + 3] = p.tx(); track[o + 4] = p.ty(); track[o + 5] = p.tz(); track[o + 6] = p.qx(); track[o + 7] = p.qy(); track[o + 8] = p.qz(); track[o + 9] = p.qw();
        track[o + 10] = poseFix[12]; track[o + 11] = poseFix[14]; track[o + 12] = (float) Math.toDegrees(Math.atan2(poseFix[2], poseFix[0]));
        trackN++;
    }

    private volatile Realign shadowRealign; private long shadowUntil, shadowFrom, shadowLast; private volatile boolean shadowRunning;
    /** For 20 s after a resume: what the alignment would say now (logged, never applied). */
    private void shadowCheck(java.nio.ShortBuffer dbuf, int dRow, int dPix, int dw, int dh, float fx, float fy, float cx, float cy, float[] rawM, long nowMs) {
        final Realign rl = shadowRealign;
        if (rl == null) return;
        if (nowMs > shadowUntil) { shadowRealign = null; return; }
        if (shadowRunning || nowMs - shadowLast < 1000) return;
        shadowLast = nowMs; shadowRunning = true;
        final java.util.ArrayList<float[]> pts = new java.util.ArrayList<>();
        for (int v = 3; v < dh - 3; v += 4) for (int u = 3; u < dw - 3; u += 4) {
            int mm = dbuf.get(v * dRow + u * dPix) & 0xFFFF; if (mm < 300 || mm > 3500) continue;
            float d = mm * 0.001f, xc = (u + 0.5f - cx) * d / fx, yc = (cy - (v + 0.5f)) * d / fy, zc = -d;
            pts.add(new float[]{rawM[0] * xc + rawM[4] * yc + rawM[8] * zc + rawM[12], rawM[1] * xc + rawM[5] * yc + rawM[9] * zc + rawM[13], rawM[2] * xc + rawM[6] * yc + rawM[10] * zc + rawM[14]});
        }
        final float[] fixNow = poseFix.clone(); final float since = (nowMs - shadowFrom) / 1000f;
        Thread th = new Thread(() -> {
            try {
                if (pts.size() >= 100) {
                    Realign.Fit f = rl.solve(pts, fixNow);
                    float dx = f.fix[12] - fixNow[12], dz = f.fix[14] - fixNow[14];
                    float dd = (float) Math.toDegrees(Math.atan2(f.fix[2], f.fix[0]) - Math.atan2(fixNow[2], fixNow[0]));
                    log(String.format(java.util.Locale.US, "shadow align +%.0f s: %d%% on the scan before the loss, median %.1f cm; would nudge %.1f, %.1f cm, %.1f deg (not applied)",
                            since, Math.round(f.inliers * 100), f.median * 100, dx * 100, dz * 100, dd));
                }
            } catch (Throwable e) { logError("shadow align", new RuntimeException(e)); }
            finally { shadowRunning = false; }
        }, "shadow-align");
        th.setPriority(Thread.MIN_PRIORITY); th.start();
    }

    /** view = (the turn since the last good frame, in the camera's axes)^T · the last good view. */
    private void heldView(float[] out) {
        System.arraycopy(heldView, 0, out, 0, 16);
        float[] now = new float[9]; boolean ok;
        synchronized (scanRot) { System.arraycopy(scanRot, 0, now, 0, 9); ok = scanRotOk; }
        if (!ok || !heldRotOk) return;
        // M = R0^T · Rnow (row-major, device axes): a vector in the device frame now -> the device frame then
        float[] m = new float[9];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) { float s = 0; for (int k = 0; k < 3; k++) s += heldRot[k * 3 + i] * now[k * 3 + j]; m[i * 3 + j] = s; }
        // device axes -> the display-oriented camera axes (turned about z by the display rotation)
        int rot = displayRotation(); double ang = rot * Math.PI / 2; float c = (float) Math.cos(ang), sn = (float) Math.sin(ang);
        float[] z = {c, -sn, 0, sn, c, 0, 0, 0, 1}, zt = {c, sn, 0, -sn, c, 0, 0, 0, 1};
        float[] t = mul3(zt, mul3(m, z));
        // view_now = M^T · view_then (4x4, column-major)
        float[] mt = new float[16];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) mt[j * 4 + i] = t[j * 3 + i];   // transpose: (M^T)[i][j] = M[j][i]
        mt[15] = 1;
        float[] v = new float[16];
        Matrix.multiplyMM(v, 0, mt, 0, heldView, 0);
        System.arraycopy(v, 0, out, 0, 16);
    }

    private static float[] mul3(float[] a, float[] b) {
        float[] r = new float[9];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) { float s = 0; for (int k = 0; k < 3; k++) s += a[i * 3 + k] * b[k * 3 + j]; r[i * 3 + j] = s; }
        return r;
    }
    private boolean ceilingHinted;

    /** feature-heights.bin: ARCore's feature point heights from the scan (for the ceiling when it's opened again). */
    private float[] pendingHeight;

    /** The scan's photos as grey images with their cameras, for EdgeRefine (half size: 512 px wide). */
    private java.util.List<EdgeRefine.Photo> edgePhotos() {
        java.util.ArrayList<EdgeRefine.Photo> out = new java.util.ArrayList<>();
        for (PhotoRoom.Keyframe k : new java.util.ArrayList<>(photos.keyframes)) {
            if (k.jpg == null) continue;
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options(); o.inSampleSize = 2;
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeByteArray(k.jpg, 0, k.jpg.length, o);
            if (b == null) continue;
            EdgeRefine.Photo p = new EdgeRefine.Photo();
            p.pose = k.pose; p.view = k.view; p.w = b.getWidth(); p.h = b.getHeight();
            float sx = p.w / k.w, sy = p.h / k.h;
            p.fx = k.fx * sx; p.fy = k.fy * sy; p.cx = k.cx * sx; p.cy = k.cy * sy;
            int[] px = new int[p.w * p.h]; b.getPixels(px, 0, p.w, 0, 0, p.w, p.h); b.recycle();
            p.grey = new byte[px.length];
            for (int i = 0; i < px.length; i++) { int c = px[i]; p.grey[i] = (byte) ((((c >> 16) & 255) * 3 + ((c >> 8) & 255) * 6 + (c & 255)) / 10); }
            if (k.depth != null) { p.depth = k.depth; p.dw = k.dw; p.dh = k.dh; p.dfx = k.dfx; p.dfy = k.dfy; p.dcx = k.dcx; p.dcy = k.dcy; }
            out.add(p);
        }
        return out;
    }

    private void saveHeight(File dir) {
        float[] h = pendingHeight; if (h == null) return;
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(new File(dir, "height.json"))) {
            o.write(new org.json.JSONObject().put("floorY", h[0]).put("ceilingY", h[1]).put("trusted", h[2] > 0)
                    // the sources it was chosen from (ARCore's planes aren't kept anywhere else)
                    .put("depthCeilingY", Float.isNaN(room.depthCeilingY) ? JSONNULL : room.depthCeilingY)
                    .put("featureCeilingY", Float.isNaN(room.featureCeilingY) ? JSONNULL : room.featureCeilingY)
                    .put("arcorePlanes", arcorePlanesJson()).toString().getBytes("UTF-8"));
        } catch (Exception e) { logError("height save", e); }
    }

    private static final Object JSONNULL = org.json.JSONObject.NULL;

    /** ARCore's planes at Done: type (0 up, 1 down, 2 vertical), centre, normal (for measuring later; not saved elsewhere). */
    private org.json.JSONArray arcorePlanesJson() {
        org.json.JSONArray a = new org.json.JSONArray();
        try {
            for (RoomModel.PlaneData pd : lastPlanes) a.put(new org.json.JSONObject().put("type", pd.type)
                    .put("center", new org.json.JSONArray().put((double) pd.center[0]).put((double) pd.center[1]).put((double) pd.center[2]))
                    .put("normal", new org.json.JSONArray().put((double) pd.normal[0]).put((double) pd.normal[1]).put((double) pd.normal[2])));
        } catch (Exception ignored) { }
        return a;
    }

    private void saveDepthPairs(File dir) {
        if (pairN == 0) return;
        try (java.io.DataOutputStream o = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(new File(dir, "depth-pairs.bin"))))) {
            o.writeInt(pairN); for (int i = 0; i < pairN * 5; i++) o.writeFloat(pairBuf[i]);
        } catch (Exception e) { logError("depth pairs", e); }
    }

    private void saveFeatureHeights(File dir) {
        float[] ys; synchronized (featLock) { ys = java.util.Arrays.copyOf(featY, featN); }
        if (ys.length == 0) return;
        try (java.io.DataOutputStream o = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(new File(dir, "feature-heights.bin"))))) {
            o.writeInt(ys.length); for (float y : ys) o.writeFloat(y);
        } catch (Exception e) { logError("feature heights", e); }
    }

    private void loadFeatureHeights(File dir) {
        File f = new File(dir, "feature-heights.bin");
        if (!f.exists()) return;
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(f)))) {
            int n = Math.min(in.readInt(), featY.length);
            synchronized (featLock) { for (int i = 0; i < n; i++) featY[i] = in.readFloat(); featN = n; featAt = n % featY.length; }
        } catch (Exception e) { logError("feature heights", e); }
    }

    /** Once per scan: no ARCore features near the ceiling yet, so the height would come from depth only. */
    private void ceilingHint() {
        float floor = planner.floorY; int near = 0;
        synchronized (featLock) { for (int i = 0; i < featN; i++) if (featY[i] - floor > 2.25f) near++; }
        if (near >= 30) { ceilingHinted = true; return; }
        ceilingHinted = true;
        log("ceiling hint: only " + near + " feature points up there");
        activity.toast("Tilt up until you see where the walls meet the ceiling (keep some wall in view), so the room height can be measured.");
    }
    private long scanMs, lastTickMs;

    /** Early warning before a loss (rk 10 Nov 10:44: lost on a plain wall): ARCore tracks few features for a second. */
    private void plainWallHint(Frame frame, long nowMs) {
        int n = 0;
        try (PointCloud pc = frame.acquirePointCloud()) {
            FloatBuffer p = pc.getPoints();
            int k = p.remaining() / 4;
            for (int i = 0; i < k; i++) if (p.get(i * 4 + 3) >= 0.3f) n++;
        } catch (Exception e) { return; }
        // Advisory only, never blocks anything (rk 10:59: it nagged every 15 s at an ordinary door while depth was
        // fusing). Only when features stay very low for 3 s, the depth isn't filling the view either, at most once a
        // minute.
        healthMinFeatures = Math.min(healthMinFeatures, n);
        featuresNow = n;
        // Before ARCore loses its place on a plain wall or corner (rk 10 Nov 15:58; 11 of today's 12 losses were "not
        // enough features"): under 20 sure features for 1 s shows the calm plain-wall hint on the card (no toast).
        // Plain-wall warning (debug switch, default on; rk 11 Nov "how could we handle plain walls"): under 15 features
        // for 2 s is a stronger, earlier-ranked line with one buzz. Offline over 19 losses in rk's and Vera's logs
        // (tools/realign-bench/warn-rules.py): all 19 had it, median 4 s before; ~1.8 a minute came without a loss
        // following (some of those because the user turned away in time).
        if (PLAIN_WARNING) {
            if (n >= 15) warnFewSince = 0;
            else if (warnFewSince == 0) warnFewSince = nowMs;
            else if (nowMs - warnFewSince >= 2000 && !needRealign && lostSince == 0) {   // quiet while "Lost track" shows (21:11 scan: two warnings while lining up)
                if (plainWarnUntil < nowMs) {
                    log("plain-wall warning (" + n + " features for " + (nowMs - warnFewSince) / 1000 + " s)");
                    if (nowMs - plainWarnBuzzAt > 8000) { activity.haptic(false); plainWarnBuzzAt = nowMs; }
                }
                plainWarnUntil = nowMs + 2500;
            }
        }
        if (n >= 20) { fewFeaturesSince = 0; return; }
        if (fewFeaturesSince == 0) { fewFeaturesSince = nowMs; return; }
        if (nowMs - fewFeaturesSince > 1000) {
            if (plainHintUntil < nowMs) log("few features (" + n + "): plain-wall hint");
            plainHintUntil = nowMs + 2500;
        }
    }

    static final class RealignResult { Realign.Fit fit; boolean good, ambiguous, quick; int samples; String source = "", detail = ""; }
    private volatile RealignResult realignResult;
    private volatile boolean realignRunning;
    private long realignLastStart; private int realignAttempts;
    private final java.util.ArrayList<java.util.ArrayList<float[]>> recentPts = new java.util.ArrayList<>();
    volatile boolean forceResume;
    /** The correction that undoes ARCore's last jump (null if none). */
    private float[] jumpGuess;
    private boolean newPartOffered;

    /** The correction that puts ARCore's camera (cp: camera -> world, with the current correction) where the held view
     *  says the phone is: facing where the rotation sensor turned it, standing where tracking was lost (the hint says to
     *  stop). After a loss ARCore can come back in a shifted frame (rk 11 Nov 20:12: back 0.63 m / 24.7 deg off; every
     *  re-align then found 0.6-0.7 m / 20-24 deg and was refused as "implausible" against ARCore for 20 s). */
    private volatile float[] heldGuess; private float viewShape = -1;
    private float[] heldGuessFor(float[] cp) {
        if (!havePrevPose) return null;
        float[] v = lastShownView;
        float hx = -v[2], hz = -v[10], cx = -cp[8], cz = -cp[10];   // forward on the floor: held view, ARCore
        if (hx * hx + hz * hz < 0.05f || cx * cx + cz * cz < 0.05f) return null;   // looking straight up or down: no heading
        double d = Math.atan2(hz, hx) - Math.atan2(cz, cx);
        float c = (float) Math.cos(d), sn = (float) Math.sin(d);
        float[] r = Realign.identity();
        r[0] = c; r[8] = -sn; r[2] = sn; r[10] = c;
        float px = cp[12], pz = cp[14];
        r[12] = prevPose[12] - (c * px - sn * pz); r[14] = prevPose[14] - (sn * px + c * pz);
        float[] out = new float[16]; Matrix.multiplyMM(out, 0, r, 0, poseFix, 0);
        return out;
    }

    private final float[] lastGoodCam = new float[16];

    // ---- One plain wall filled as a rectangle (WallGuess; rk 11 Nov 21:40) ----
    static boolean FILL_WALL = true;        // debug tools "Fill the plain wall"
    static boolean STANDING_NOTE = true;    // debug tools "Standing notice"
    static final String STANDING_TEXT = "Keep corners, edges or furniture in view. If the phone loses track on a plain wall, the scan stops.";
    static final float PLAIN_SPREAD = 60f;  // brightness spread of a plain wall's points (offline: hall wall 32-52, curtains 68-84, cabinets 124-145)
    volatile WallGuess.Wall filledWall;     // the wall's current best estimate (the guessed dots go on it)
    private float guessWeight; private int guessUpdates;
    private volatile boolean[] guessCells;  // 4 cm cells (along x up) with real scanned points: no guessed dot there
    private final float[] shownCorners = new float[12]; private boolean shownSet;
    static final float GUESS_STEP = 0.04f;

    /** Which 4 cm cells of the wall already have scanned points (real points always win over guesses). */
    private boolean[] scannedCells(WallGuess.Wall w, int n) {
        int cw = Math.max(1, (int) (w.width / GUESS_STEP)), ch = Math.max(1, (int) (w.height / GUESS_STEP));
        boolean[] cells = new boolean[cw * ch];
        float ux = (w.corners[3] - w.corners[0]) / Math.max(1e-3f, w.width), uz = (w.corners[5] - w.corners[2]) / Math.max(1e-3f, w.width);
        for (int i = 0; i < n; i++) {
            float x = pos.get(i * 3), y = pos.get(i * 3 + 1), z = pos.get(i * 3 + 2);
            if (Math.abs(w.nx * x + w.nz * z - w.d) > WallGuess.ON_BAND) continue;
            int a = (int) (((x - w.corners[0]) * ux + (z - w.corners[2]) * uz) / GUESS_STEP), b = (int) ((y - w.corners[1]) / GUESS_STEP);
            if (a >= 0 && b >= 0 && a < cw && b < ch) cells[b * cw + a] = true;
        }
        return cells;
    }
    private WallGuess.Wall wallCand; private int wallCandHits; private long wallCheckAt; private volatile boolean wallChecking;

    /** GL thread, about every 1.5 s while scanning normally: is the camera looking at a plain wall it can fill? From
     *  the points already in the scan (a good look from afar is enough), never on top of an opening, one wall only. */
    private void maybeFillWall(long nowMs) {
        if (!FILL_WALL || wallChecking || nowMs - wallCheckAt < 3000 || count < 20000 || needRealign || !haveGoodCam || depthLate()) return;
        wallCheckAt = nowMs; wallChecking = true;
        final float[] cam = lastGoodCam.clone(); final float floorY = walkFloorY(), ceilY = roomReady && room.ceilingY > room.floorY + 1.8f ? room.ceilingY : 0;
        final int n = count; final boolean warned = nowMs < plainWarnUntil + 10000; final int featNow = featuresNow;
        new Thread(() -> {
            try {
                WallGuess.Pts P = new WallGuess.Pts() {
                    public float x(int i) { return pos.get(i * 3); } public float y(int i) { return pos.get(i * 3 + 1); } public float z(int i) { return pos.get(i * 3 + 2); }
                    public int rgb(int i) { return (col.get(i * 4) & 255) << 16 | (col.get(i * 4 + 1) & 255) << 8 | (col.get(i * 4 + 2) & 255); }
                };
                StringBuilder why = new StringBuilder();
                WallGuess.Wall w = WallGuess.find(P, n, cam, floorY, ceilY, why);
                // The wall that's causing problems: plain colours AND ARCore finding little to hold on to while looking at it
                // (under 40 features), or the plain-wall warning just now. Offline on tonight's 4 scans: the hall wall at
                // 2.4-2.5 m every time from about 3 m away; the curtain-and-window wall (even colours, many features) no more.
                boolean ok = w != null && w.lumSpread <= PLAIN_SPREAD && w.distance <= 3.5f && ((featNow >= 0 && featNow < 40) || warned);
                if (!ok) return;
                // Guessed dots from the first rough estimate (rk 21:52: "start placing dots ... update themselves to the more
                // accurate placement once I get closer"). Each estimate counts by its support and how close it was taken
                // (1/d^2: close-up readings are much better on the S24); a better-supported estimate pulls the wall towards
                // itself, a weaker one only a little; older ones fade (x0.9 each time).
                float wNew = w.support / Math.max(0.25f, w.distance * w.distance);
                WallGuess.Wall cur = filledWall;
                boolean same = cur != null && Math.abs(cur.d - w.d) < 0.15f && cur.nx * w.nx + cur.nz * w.nz > 0.99f;
                if (cur != null && !same && !warned) return;   // one wall only: another replaces it only where the plain-wall warning pointed
                WallGuess.Wall next;
                if (same) {
                    float W = guessWeight * 0.9f, k = wNew / (W + wNew);
                    next = new WallGuess.Wall();
                    for (int q = 0; q < 12; q++) next.corners[q] = cur.corners[q] + (w.corners[q] - cur.corners[q]) * k;
                    float nx = cur.nx + (w.nx - cur.nx) * k, nz = cur.nz + (w.nz - cur.nz) * k, l = (float) Math.sqrt(nx * nx + nz * nz);
                    next.nx = nx / l; next.nz = nz / l; next.d = cur.d + (w.d - cur.d) * k;
                    next.width = (float) Math.hypot(next.corners[3] - next.corners[0], next.corners[5] - next.corners[2]); next.height = next.corners[7] - next.corners[1];
                    next.rgb = k > 0.5f ? w.rgb : cur.rgb; next.lumSpread = w.lumSpread; next.support = Math.max(cur.support, w.support); next.distance = w.distance;
                    guessWeight = W + wNew;
                } else { next = w; guessWeight = wNew; }
                guessCells = scannedCells(next, n);
                filledWall = next;
                guessUpdates++;
                if (cur == null || !same || guessUpdates % 10 == 0)
                    log(String.format(java.util.Locale.US, "guessed wall dots (generated, not scanned): %.2f x %.2f m, seen from %.2f m, %d points on it, brightness spread %.0f; update %d%s",
                            next.width, next.height, w.distance, w.support, w.lumSpread, guessUpdates, cur == null ? "" : same ? "" : " (another wall, where the warning pointed)"));
                File d = currentDir; if (d != null) saveFilledWall(d);
            } catch (Throwable e) { logError("wall fill", new RuntimeException(e)); }
            finally { wallChecking = false; }
        }, "wall-fill").start();
    }

    /** filled-wall.json: the generated rectangle (corners x, y, z), colour and the plane; never written as points. */
    private boolean scanningNowForWall() { return !viewing; }
    private boolean loadedFromThisSession() { return loadedAny ? false : true; }

    void saveFilledWall(File d) {
        WallGuess.Wall w = filledWall; if (w == null) return;
        try {
            org.json.JSONObject j = new org.json.JSONObject().put("generated", true).put("note", "guessed by the app, not scanned: dots every 4 cm on this rectangle where no scanned point is").put("dotSpacing", GUESS_STEP)
                    .put("width", w.width).put("height", w.height).put("rgb", String.format("#%06x", w.rgb & 0xFFFFFF));
            org.json.JSONArray c = new org.json.JSONArray(); for (float v : w.corners) c.put((double) v); j.put("corners", c);
            j.put("plane", new org.json.JSONArray().put((double) w.nx).put((double) w.nz).put((double) w.d));
            java.nio.file.Files.write(new File(d, "guessed-wall.json").toPath(), j.toString(1).getBytes("UTF-8"));
        } catch (Exception e) { logError("guessed-wall.json", e); }
    }

    void loadFilledWall(File d) {
        File f = d == null ? null : new File(d, "guessed-wall.json"); if (f == null || !f.exists()) return;   // (none saved yet: keep the one in memory, e.g. right after Done)
        try {
            org.json.JSONObject j = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
            WallGuess.Wall w = new WallGuess.Wall(); org.json.JSONArray c = j.getJSONArray("corners");
            for (int i = 0; i < 12; i++) w.corners[i] = (float) c.getDouble(i);
            org.json.JSONArray pl = j.getJSONArray("plane"); w.nx = (float) pl.getDouble(0); w.nz = (float) pl.getDouble(1); w.d = (float) pl.getDouble(2);
            w.width = (float) j.getDouble("width"); w.height = (float) j.getDouble("height"); w.rgb = Integer.parseInt(j.getString("rgb").substring(1), 16);
            filledWall = w; shownSet = false;
            final WallGuess.Wall ww = w; final int nn = count; guessCells = scannedCells(ww, nn);
        } catch (Exception e) { logError("guessed-wall.json", e); }
    }

    /** The filled wall, a flat rectangle in its colour, 2 cm behind the wall so the scan's own points stay in front. */
    private void drawFilledWall(float[] m) {
        WallGuess.Wall w = filledWall; boolean[] cells = guessCells;
        if (w == null || !FILL_WALL || cells == null) return;
        // the shown wall glides to the current estimate (no jumps): a tenth of the way each frame
        if (!shownSet) { System.arraycopy(w.corners, 0, shownCorners, 0, 12); shownSet = true; }
        else for (int q = 0; q < 12; q++) shownCorners[q] += (w.corners[q] - shownCorners[q]) * 0.1f;
        float[] c = shownCorners;
        float wid = (float) Math.hypot(c[3] - c[0], c[5] - c[2]), hei = c[7] - c[1];
        int cw = Math.max(1, (int) (w.width / GUESS_STEP)), ch = Math.max(1, (int) (w.height / GUESS_STEP));
        if (cells.length != cw * ch || wid < 0.1f) return;
        float ux = (c[3] - c[0]) / wid, uz = (c[5] - c[2]) / wid, sx = wid / cw, sy = hei / ch;
        int nd = 0; for (boolean b : cells) if (!b) nd++;
        if (guessVerts == null || guessVerts.length < nd * 3) guessVerts = new float[nd * 3];
        int k = 0;
        for (int b = 0; b < ch; b++) for (int a = 0; a < cw; a++) {
            if (cells[b * cw + a]) continue;
            float s = (a + 0.5f) * sx, y = c[1] + (b + 0.5f) * sy;
            guessVerts[k++] = c[0] + ux * s - w.nx * 0.01f; guessVerts[k++] = y; guessVerts[k++] = c[2] + uz * s - w.nz * 0.01f;
        }
        if (k == 0) return;
        float r = ((w.rgb >> 16) & 255) / 255f, g = ((w.rgb >> 8) & 255) / 255f, bl = (w.rgb & 255) / 255f;
        if (!viewing) { r = g = bl = 0.04f; }   // guessed, not measured: solid black while scanning, so it's told apart (rk 22:09/22:27)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        planDraw(m, k == guessVerts.length ? guessVerts : java.util.Arrays.copyOf(guessVerts, k), GLES20.GL_POINTS, new float[]{r, g, bl, 1f}, Math.max(2f, 7f * DOT_SCALE));
    }
    private float[] guessVerts;

    // ---- Good spots (Anchors): views with many features and 3D shape, to send the user to after a loss ----
    /** Debug tools "Good spot after a loss" (on by default). */
    static boolean GOOD_SPOTS = false;     // (rk 11 Nov 22:02: "showing the picture where to point doesn't work": off)
    /** Debug tools "Stop the scan on a loss" (rk 11 Nov 22:02): when ARCore loses track the scan simply ends and is kept,
     *  no lost-track prompts. */
    static boolean STOP_ON_LOSS = true;
    static final long LOSS_GRACE_MS = 1000; private long lossStopAt;
    /** Each frame: tracking lost and not back within the grace: the scan ends as if Done was tapped (kept, processed). */
    static final long RESTART_PROOF_MS = 8000;   // from the restart: ARCore itself takes ~3 s to come back, so about 5 s to prove the line-up
    private void checkLossStop(TrackingState ts, long nowMs) {
        if (STOP_ON_LOSS && sessionRestarted && needRealign && realignSince > 0 && nowMs - realignSince > RESTART_PROOF_MS && !viewing) {
            realignSince = 0;
            log("no proven line-up " + RESTART_PROOF_MS / 1000 + " s after the AR restart: the scan stops here and is kept (nothing from the unproven part added)");
            activity.stopOnLoss();
            return;
        }
        if (lossStopAt == 0) return;
        if (ts == TrackingState.TRACKING) { lossStopAt = 0; log("tracking back within " + LOSS_GRACE_MS + " ms: the scan goes on"); return; }
        if (nowMs < lossStopAt) return;
        lossStopAt = 0;
        log("tracking lost for over " + LOSS_GRACE_MS + " ms: the scan stops here and is kept (rk 11 Nov 22:02: no lost-track prompts, just stop)");
        activity.stopOnLoss();
    }
    final Anchors anchors = new Anchors();
    private volatile Anchors.Anchor goal; private volatile long goalSince; private long anchorOfferAt;
    private final java.util.Set<Anchors.Anchor> goalTried = new java.util.HashSet<>();

    private void offerAnchor(ShortBuffer dbuf, int dRow, int dPix, int dw, int dh, float fx, float fy, float cx, float cy, float[] m) {
        try {
            short[] d = new short[dw * dh];
            for (int v = 0; v < dh; v++) for (int u = 0; u < dw; u++) d[v * dw + u] = dbuf.get(v * dRow + u * dPix);
            float[] s = Anchors.shapeScore(d, dw, dh, fx, fy, cx, cy);
            int f = featuresNow;
            if (!Anchors.good(f, s)) return;
            Anchors.Anchor a = Anchors.make(m, System.currentTimeMillis(), f, s);
            Image img = cpuImage;
            if (img != null) a.jpg = spotPicture(img);
            anchors.keep(a);
        } catch (Throwable e) { logError("good spot", new RuntimeException(e)); }
    }

    /** A colour picture of the camera image, ~320 px wide, as JPEG (YUV_420_888, every 2nd pixel). */
    private static byte[] spotPicture(Image img) {
        Image.Plane[] pl = img.getPlanes();
        ByteBuffer yb = pl[0].getBuffer(), ub = pl[1].getBuffer(), vb = pl[2].getBuffer();
        int iw = img.getWidth(), ih = img.getHeight(), step = Math.max(1, iw / 320) & ~1; if (step == 0) step = 1;
        int w = iw / step, h = ih / step, yRow = pl[0].getRowStride(), yPix = pl[0].getPixelStride(), cRow = pl[1].getRowStride(), cPix = pl[1].getPixelStride();
        int[] px = new int[w * h];
        for (int v = 0; v < h; v++) for (int u = 0; u < w; u++) {
            int x = u * step, y = v * step;
            int Y = yb.get(y * yRow + x * yPix) & 255, U = (ub.get((y / 2) * cRow + (x / 2) * cPix) & 255) - 128, V = (vb.get((y / 2) * cRow + (x / 2) * cPix) & 255) - 128;
            int r = Math.max(0, Math.min(255, Math.round(Y + 1.402f * V))), g = Math.max(0, Math.min(255, Math.round(Y - 0.344f * U - 0.714f * V))), b = Math.max(0, Math.min(255, Math.round(Y + 1.772f * U)));
            px[v * w + u] = 0xFF000000 | r << 16 | g << 8 | b;
        }
        android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888);
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, o); bm.recycle();
        return o.toByteArray();
    }

    /** Picks the good spot to send the user to (after a loss, or the next one when one didn't work). */
    private void pickGoal(String why) {
        if (!GOOD_SPOTS || !haveGoodCam) { clearGoal(); return; }
        float[] o = new float[3]; long now = System.currentTimeMillis();
        Anchors.Anchor a;
        synchronized (goalTried) {
            a = anchors.pick(lastGoodCam, now, goalTried, o);
            if (a == null && goal != null) { goalSince = now; return; }   // no other: keep showing this one
            if (a != null) goalTried.add(a);
        }
        goal = a; goalSince = now;
        if (a != null) {
            log(String.format(java.util.Locale.US, "good spot (%s): the view from %.0f s ago, %.1f m from where it was lost, turn %.0f deg, %d features, shape %.2f, score %.2f; %d spots kept",
                    why, (now - a.ms) / 1000f, o[0], o[1], a.features, a.shape, o[2], anchors.size()));
            activity.showSpot(a.jpg);
        } else {
            log("good spot (" + why + "): none of the " + anchors.size() + " kept fits; pointing back where it was lost");
            activity.showSpot(null);
        }
    }

    /** The good spot in the scan view while lost: an amber frame where it looked, lines back to where it was seen from
     *  (a camera shape), so the user sees where in the room it is, not only "to your left". */
    private void drawGoal(float[] m, long nowMs) {
        Anchors.Anchor a = goal;
        if (a == null || heldMode == 0 && !needRealign) return;
        float[] c = a.cam;
        float rx = c[0], ry = c[1], rz = c[2], ux = c[4], uy = c[5], uz = c[6];   // camera right and up in the world
        float h = Math.max(0.25f, a.depthM * 0.35f), w = h * 0.75f;   // about the middle of what it saw
        float[] k = new float[12];
        int[][] sg = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (int i = 0; i < 4; i++) { k[i * 3] = a.tx + rx * sg[i][0] * w + ux * sg[i][1] * h; k[i * 3 + 1] = a.ty + ry * sg[i][0] * w + uy * sg[i][1] * h; k[i * 3 + 2] = a.tz + rz * sg[i][0] * w + uz * sg[i][1] * h; }
        float[] lines = new float[16 * 3]; int n = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            for (int q = 0; q < 3; q++) lines[n++] = k[i * 3 + q]; for (int q = 0; q < 3; q++) lines[n++] = k[j * 3 + q];
            lines[n++] = c[12]; lines[n++] = c[13]; lines[n++] = c[14]; for (int q = 0; q < 3; q++) lines[n++] = k[i * 3 + q];
        }
        float pulse = 0.65f + 0.35f * (float) Math.sin(nowMs / 250.0);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glLineWidth(7f);
        planDraw(m, lines, GLES20.GL_LINES, new float[]{1f, 0.69f, 0.13f, pulse}, 1f);
        GLES20.glLineWidth(1f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
    }

    /** For the state line while a good spot is shown: how far the view shown is from pointing at it (did the user get there?). */
    private String goalOffText() {
        Anchors.Anchor a = goal;
        if (a == null) return "";
        float[] v = lastShownView, inv = new float[16];
        if (!Matrix.invertM(inv, 0, v, 0)) return "";
        float fx = -v[2], fy = -v[6], fz = -v[10], dx = a.tx - inv[12], dy = a.ty - inv[13], dz = a.tz - inv[14];
        float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz), lf = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (l < 1e-3f || lf < 1e-3f) return "";
        float ang = (float) Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, (fx * dx + fy * dy + fz * dz) / (l * lf)))));
        return String.format(java.util.Locale.US, ", good spot %.0f deg off (%s)", ang, backTo());
    }

    private void clearGoal() {
        if (goal != null) activity.showSpot(null);
        goal = null;
    }
    private boolean haveGoodCam;
    private volatile boolean realignBuilding;
    /** Second start for the re-alignment: the phone hasn't moved since the loss (its last good place, turned only
     *  about the vertical), for when the AR restart came back in a different frame. */
    private float[] stayGuess;

    private float[] stayGuessFor(float[] m) {
        if (!haveGoodCam) return null;
        return guessFor(lastGoodCam, m);
    }

    /** The correction (turn about the vertical + shift) that puts the camera m where ref was. */
    private static float[] guessFor(float[] ref, float[] m) {
        float[] lastGoodCam = ref;
        float[] inv = new float[16], g = new float[16];
        if (!android.opengl.Matrix.invertM(inv, 0, m, 0)) return null;
        android.opengl.Matrix.multiplyMM(g, 0, lastGoodCam, 0, inv, 0);
        float c = g[0], sn = g[2], l = (float) Math.sqrt(c * c + sn * sn);
        if (l < 1e-4f) return null;
        c /= l; sn /= l;
        // Keep gravity: the turn about the vertical only, placed so the camera lands where it was.
        float[] r = Realign.identity();
        r[0] = c; r[8] = -sn; r[2] = sn; r[10] = c;
        float px = m[12], py = m[13], pz = m[14];
        r[12] = lastGoodCam[12] - (c * px - sn * pz); r[13] = lastGoodCam[13] - py; r[14] = lastGoodCam[14] - (sn * px + c * pz);
        return r;
    }

    /** After a tracking loss: finds the rigid correction (shift + turn about the vertical; ARCore keeps gravity) that
     *  puts this depth frame onto the scan so far (Realign: ICP, coarse then fine), and resumes fusing only when three
     *  frames in a row agree with a small residual. Until then nothing is fused. */
    private boolean realigned(ShortBuffer dbuf, int dRow, int dPix, int dw, int dh, float fx, float fy, float cx, float cy, float[] m, long nowMs) {
        // Nothing is fused from a pose that hasn't been checked against the scan (rk 10 Nov 10:44: lost on a plain wall,
        // moved before it recovered; the old 5 s timeout then fused 8 s from ARCore's wrong pose as a new layer). No
        // timeout: until the view lines up, fusion stays paused and the hint says where to point.
        if (realign == null) {
            if (realignBuilding) return false;
            needRealign = false; return true;
        }
        if (stayGuess == null) stayGuess = stayGuessFor(m);
        if (heldGuess != null) {   // the view's 3D shape, for the held-view rule (a plain wall or a corridor slides)
            short[] dd = new short[dw * dh];
            for (int v = 0; v < dh; v++) for (int u = 0; u < dw; u++) dd[v * dw + u] = dbuf.get(v * dRow + u * dPix);
            float sh = Anchors.shapeScore(dd, dw, dh, fx, fy, cx, cy)[0];
            viewShape = viewShape < 0 ? sh : viewShape * 0.7f + sh * 0.3f;
        }
        // Never lined up after 30 s: the user decides (a first-time user mustn't be stuck with a scan that stopped
        // growing). Carrying on is a new part of the scan (its own segment, droppable later), never silent.
        if (forceResume) {
            forceResume = false; needRealign = false; realign = null; segment++; clearGoal();
            log(String.format(java.util.Locale.US, "carrying on as a new part after %.1f s (the user chose it); segment %d", (nowMs - realignSince) / 1000f, segment));
            return true;
        }
        if (nowMs - realignSince > 30000 && !newPartOffered && !STOP_ON_LOSS) { newPartOffered = true; activity.offerNewPart(); }
        // This depth frame's samples (ARCore's frame) join the last few frames' (a second or two of looking: more of
        // the room than one view, so a corner can't be taken for another corner as easily).
        java.util.ArrayList<float[]> pts = new java.util.ArrayList<>();
        for (int v = 3; v < dh - 3; v += 5) for (int u = 3; u < dw - 3; u += 5) {
            int mm = dbuf.get(v * dRow + u * dPix) & 0xFFFF;
            if (mm < 300 || mm > 3500) continue;
            float d = mm * 0.001f, xc = (u + 0.5f - cx) * d / fx, yc = (cy - (v + 0.5f)) * d / fy, zc = -d;
            pts.add(new float[]{m[0] * xc + m[4] * yc + m[8] * zc + m[12], m[1] * xc + m[5] * yc + m[9] * zc + m[13], m[2] * xc + m[6] * yc + m[10] * zc + m[14]});
        }
        if (pts.size() >= 30) { recentPts.add(pts); while (recentPts.size() > 8) recentPts.remove(0); }
        // A finished attempt (on its own thread): use it.
        RealignResult res = realignResult; realignResult = null;
        if (res != null) {
            float[] F = res.fit.fix;
            float drift = Realign.away(F, fixPrev);
            boolean ok = res.good && !res.ambiguous;
            // Plausible? After a loss without a camera restart or map jump, ARCore's own position is close to right:
            // a correction that moves the scan more than 0.75 m sideways, 10 cm up/down or 15 degrees is a match on a
            // look-alike part of the room (15:31 scan: lost 0.5 s, the search found a spot 3.7 m and 40 deg away, the
            // next tries grew it to 87% and it resumed 3.9 m / 53 deg off). Today's 19 other resumes all pass.
            String implausible = "";
            if (ok && !sessionRestarted && Math.abs(F[13] - poseFix[13]) > 0.10f) {   // gravity is known: no big vertical shift (a restarted session can start at another height)
                ok = false; implausible = String.format(java.util.Locale.US, " (implausible: %.2f m up/down)", F[13] - poseFix[13]);
            }
            if (ok && jumpGuess == null && !sessionRestarted) {
                float ddx = F[12] - poseFix[12], ddy = F[13] - poseFix[13], ddz = F[14] - poseFix[14];
                double dyaw = Math.toDegrees(Math.atan2(F[2], F[0]) - Math.atan2(poseFix[2], poseFix[0]));
                while (dyaw > 180) dyaw -= 360; while (dyaw < -180) dyaw += 360;
                if (Math.hypot(ddx, ddz) > 0.75f || Math.abs(ddy) > 0.10f || Math.abs(dyaw) > 15) {
                    ok = false;
                    implausible = String.format(java.util.Locale.US, " (implausible: %.2f m sideways, %.2f m up/down, %.0f deg from where ARCore says)", Math.hypot(ddx, ddz), ddy, dyaw);
                }
            }
            float[] hg = heldGuess; boolean byHeld = false;
            if (!ok && !implausible.isEmpty() && hg != null && res.good && !res.ambiguous && Realign.away(F, hg) < 0.35f && viewShape >= Anchors.MIN_SHAPE) {
                // refused only against ARCore, but where the rotation sensor and the "stop" say the phone is: ARCore
                // came back in a shifted frame; the held view is the better judge
                ok = true; byHeld = true;
                log(String.format(java.util.Locale.US, "  agrees with the held view (%.2f off, view shape %.2f): ARCore came back shifted, counts as a match%s", Realign.away(F, hg), viewShape, implausible));
                implausible = "";
            }
            realignOk = ok && (realignOk == 0 || drift < 0.08f) ? realignOk + 1 : (ok ? 1 : 0);
            // Shadow rule (11 Nov, S23 Ultra: ~100 tries over 73 s agreed on one correction, 55-70% on the scan, but were
            // all refused as "implausible" against where ARCore said it was, and ARCore can be badly off after a loss):
            // count tries refused only as implausible that agree with each other; logged, not applied yet.
            if (!ok && !implausible.isEmpty() && res.fit.inliers >= 0.55f && res.fit.median < 0.02f && !res.ambiguous) {
                agreeRun = agreePrev != null && Realign.away(F, agreePrev) < 0.06f ? agreeRun + 1 : 1;
                agreePrev = F.clone();
                if (agreeRun == 5) log(String.format(java.util.Locale.US, "  (shadow) agreement rule: 5 tries in a row agree on %.2f, %.2f m %.0f deg, refused only as implausible: would accept",
                        F[12], F[14], Math.toDegrees(Math.atan2(F[2], F[0]))));
            } else if (ok) { agreeRun = 0; agreePrev = null; }
            System.arraycopy(F, 0, fixPrev, 0, 16);
            if (ok) fixTry = F;
            log(String.format(java.util.Locale.US, "re-align attempt (%s): %d%% on the scan, median %.1f cm, correction %.2f, %.2f, %.2f m %.0f deg%s%s -> %s",
                    res.source, Math.round(res.fit.inliers * 100), res.fit.median * 100, F[12], F[13], F[14], Math.toDegrees(Math.atan2(F[2], F[0])),
                    res.ambiguous ? ", ambiguous" : "", res.detail.isEmpty() ? "" : "; " + res.detail, ok ? "match " + realignOk + "/2" : "no" + implausible));
            // Two agreeing attempts in a row; one when ARCore came back on its own onto the old correction, or when one
            // attempt fits very well (rk 10 Nov 11:47: ARCore came back after 1.2 s about 20 cm off; three attempts in
            // 16 s, 63-90% on the scan, each a few cm different from the last, so it never resumed).
            boolean strong = ok && res.fit.inliers >= 0.8f && res.fit.median < 0.015f;
            // ARCore came back on its own (no restart): one decent match is enough
            // (13:52 scan: one attempt at 66% was taken at once while two earlier ones said +16° instead of -9°: a
            // wrong turn is worse than staying lost, so 55-80% now needs a second agreeing attempt)
            // after a jump: a fit that keeps the old frame (within 15 cm / ~2°) is the jump undone, accepted at once
            float[] jg = jumpGuess;
            if (ok && jg != null && Realign.away(F, jg) < 0.2f) strong = true;
            // After a loss (not a jump): one decent fit is enough when it keeps the correction we already had, i.e.
            // ARCore came back in its old frame. Today's scans: the good returns were 0.00-0.05 away (m + deg/30), the
            // doubtful ones 0.17 and more (13:52's wrong-angle 65.8 s resume: 0.58), so 0.10.
            if (ok && jg == null && res.fit.inliers >= 0.55f && res.fit.median < 0.02f && Realign.away(F, poseFix) < 0.10f) { strong = true; log(String.format(java.util.Locale.US, "one match is enough: it keeps the old correction (%.2f away)", Realign.away(F, poseFix))); }
            // A close fit near ARCore's own pose (ARCore came back in its own frame, give or take): one very good frame
            // within 20 cm and 8 deg of the correction in use is enough (rk 11 Nov 20:48: ARCore was back after 4.3 s,
            // the scan only carried on after 18 s; offline over the saved losses (tools/realign-bench/Recovery.java)
            // this would have resumed 4 of 4 right, 0.2-15 s sooner, and the drifting far fits never pass it).
            if (ok && !strong && jg == null && !sessionRestarted && res.fit.inliers >= 0.6f && res.fit.median < 0.015f) {
                float lm = (float) Math.hypot(F[12] - poseFix[12], F[14] - poseFix[14]);
                double ld = Math.abs(Math.toDegrees(Math.atan2(F[2], F[0]) - Math.atan2(poseFix[2], poseFix[0]))); if (ld > 180) ld = 360 - ld;
                if (lm <= 0.2f && ld <= 8) { strong = true; log(String.format(java.util.Locale.US, "one match is enough: close to ARCore's own pose (%.2f m, %.1f deg)", lm, ld)); }
            }
            // A single strong match may not turn the scan by more than 10 degrees from the correction in use (or from
            // the jump guess): 16:40 scan, after a camera restart three tries said -10, -24, -36 deg, the last at 82%
            // was taken, and the tiles then disagreed with the new data (taken back 37 -> 139). Bigger turns need two
            // tries in a row that agree.
            float[] refFix = jg != null ? jg : poseFix;
            double dYaw = Math.toDegrees(Math.atan2(F[2], F[0]) - Math.atan2(refFix[2], refFix[0]));
            while (dYaw > 180) dYaw -= 360; while (dYaw < -180) dYaw += 360;
            if (strong && Math.abs(dYaw) > 10) { strong = false; log(String.format(java.util.Locale.US, "  (a %.0f deg turn: needs a second agreeing try)", dYaw)); }
            // Accepted only through the held view: always two agreeing tries in a row, whatever the fit or the turn
            // (offline replay 11 Nov: one 92% try on 10 Nov 16:26 would otherwise have resumed at once, 0.56 away from
            // where it really resumed later).
            if (byHeld) strong = false;
            // Shadow tile check (logged only, nothing decided by it yet): does this correction still put the view on
            // the tiles painted before the loss? Offline on the 16:39 scan the -36 deg resume put 1-6% of the view on
            // the old tiles where the correction from before the loss put 12-15% (tools/tile-anchor).
            if (FLAT_TILES && ok && !recentPts.isEmpty()) {
                java.util.ArrayList<float[]> tp = new java.util.ArrayList<>();
                for (java.util.ArrayList<float[]> fr : recentPts) tp.addAll(fr);
                float[] withF = flatTiles.onTiles(tp, F), withOld = flatTiles.onTiles(tp, poseFix);
                boolean veto = withOld[0] >= 0.08f && withF[0] < 0.5f * withOld[0];
                log(String.format(java.util.Locale.US, "  tile check (shadow): %.0f%% of %d samples on the old tiles with this correction, %.0f%% with the one before%s",
                        withF[0] * 100, (int) withF[1], withOld[0] * 100, veto ? " -> the tiles would refuse it" : ""));
            }
            // After an AR session restart (a depth pause), strict proof only (rk 23:50: it carried on "quite a bit off"):
            // two agreeing tries in a row, each with at least 600 sure depth samples (the 23:50 resume began on a try with
            // 112), never a single strong one. Without proof within RESTART_PROOF_MS the scan stops (checkLossStop).
            if (sessionRestarted && STOP_ON_LOSS) {
                strong = false;
                if (ok && res.samples < 600) { ok = false; realignOk = 0; log("  (after the restart: only " + res.samples + " sure samples, needs 600)"); }
            }
            if (realignOk >= (res.quick || strong ? 1 : 2)) {
                System.arraycopy(F, 0, poseFix, 0, 16);
                evMark(); shadowRealign = realign; shadowUntil = nowMs + 20000; shadowFrom = nowMs;   // check the alignment for 20 s (shadow, not applied)
                hasFix = true; needRealign = false; realign = null; recentPts.clear(); jumpGuess = null;
                if (goal != null) log(String.format(java.util.Locale.US, "good spot: back on track %.1f s after showing it (%s)", (nowMs - goalSince) / 1000f, res.source));
                clearGoal();
                havePrevPose = false;   // the new correction moves the pose: not a jump (13:52: "pose jump 0.20 m" right after resuming)
                segment++;
                log(String.format(java.util.Locale.US, "back on track after %.1f s: correction %.3f, %.3f, %.3f m, %.1f deg; %d%% of the view on the scan, median %.1f cm; segment %d",
                        (nowMs - realignSince) / 1000f, F[12], F[13], F[14], Math.toDegrees(Math.atan2(F[2], F[0])), Math.round(res.fit.inliers * 100), res.fit.median * 100, segment));
                // (silent: scanning simply goes on, rk 10 Nov 15:58)
                return true;
            }
        }
        // Start the next attempt (at most one running, about one a second).
        if (!realignRunning && nowMs - realignLastStart > 500 && !recentPts.isEmpty()) {
            realignRunning = true; realignLastStart = nowMs;
            final java.util.ArrayList<float[]> all = new java.util.ArrayList<>();
            for (java.util.ArrayList<float[]> f : recentPts) all.addAll(f);
            // at most ~1,200 samples: each attempt took 8-11 s on the phone with all of them (rk 10 Nov 13:36)
            if (all.size() > 1200) { java.util.ArrayList<float[]> sub = new java.util.ArrayList<>(1200); float st = all.size() / 1200f; for (int i = 0; i < 1200; i++) sub.add(all.get((int) (i * st))); all.clear(); all.addAll(sub); }
            final float[] tryFix = fixTry.clone(), stay = stayGuess == null ? null : stayGuess.clone(), oldFix = poseFix.clone();
            final Anchors.Anchor gs = goal; final float[] atSpot = gs == null ? null : guessFor(gs.cam, m);   // as if the phone stood where the good spot was seen
            final float[] held = heldGuess == null ? null : heldGuess.clone();
            final float pivX = haveGoodCam ? lastGoodCam[12] : Float.NaN, pivZ = haveGoodCam ? lastGoodCam[14] : Float.NaN;
            final boolean restarted = sessionRestarted; final Realign rl = realign; final int attempt = ++realignAttempts;
            new Thread(() -> {
                RealignResult r = new RealignResult();
                long ra0 = System.currentTimeMillis();
                try {
                    // 1) ARCore's pose with the correction so far; 2) the phone where it was lost; 3) the whole search.
                    Realign.Fit f = rl.solve(all, tryFix); r.source = "as before";
                    if (!(f.inliers >= 0.5f && f.median < Realign.MAX_MEDIAN) && stay != null) {
                        Realign.Fit f2 = rl.solve(all, stay);
                        if (f2.inliers > f.inliers) { f = f2; r.source = "where it was lost"; }
                    }
                    if (!(f.inliers >= 0.5f && f.median < Realign.MAX_MEDIAN) && held != null) {
                        Realign.Fit f5 = rl.solve(all, held);
                        if (f5.inliers > f.inliers) { f = f5; r.source = "the held view"; }
                    }
                    if (!(f.inliers >= 0.5f && f.median < Realign.MAX_MEDIAN) && atSpot != null) {
                        Realign.Fit f4 = rl.solve(all, atSpot);
                        if (f4.inliers > f.inliers) { f = f4; r.source = "at the good spot"; }
                    }
                    // The whole search (seconds on the phone) only when the quick attempts keep failing: every 4th attempt
                    // from the 3rd on, so the quick ones come about once a second.
                    if (!(f.inliers >= 0.5f && f.median < Realign.MAX_MEDIAN) && attempt >= 3 && attempt % 4 == 3) {
                        Realign.Fit f3 = rl.search(all, stay != null ? stay : tryFix, pivX, pivZ);
                        r.detail = rl.lastSearch;
                        if (f3 != null && f3.inliers > f.inliers) { f = f3; r.source = "searched"; r.ambiguous = rl.ambiguous; }
                    }
                    r.fit = f;
                    r.good = f.inliers >= 0.5f && f.median < Realign.MAX_MEDIAN;
                    r.quick = !restarted && r.good && Realign.away(f.fix, oldFix) < 0.05f;
                    r.samples = all.size();
                    r.detail += (r.detail.isEmpty() ? "" : "; ") + (System.currentTimeMillis() - ra0) + " ms, " + all.size() + " samples";
                    realignResult = r;
                } catch (Throwable e) { logError("re-align", new RuntimeException(e)); }
                realignRunning = false;
            }, "realign").start();
        }
        // (no repeated toast: the card at the top carries the instruction)
        return false;
    }
    private static long key(float x, float y, float z) {
        long ix = (long) Math.floor(x / VOXEL) + (1 << 20);
        long iy = (long) Math.floor(y / VOXEL) + (1 << 20);
        long iz = (long) Math.floor(z / VOXEL) + (1 << 20);
        return (ix & 0x1FFFFF) | ((iy & 0x1FFFFF) << 21) | ((iz & 0x1FFFFF) << 42);
    }

    // ---------------- drawing ----------------

    private void uploadNew() {
        if (greyShown != greyNow() || paintShown != PAINT_MIN || mergedShown != SHOW_MERGED) { greyShown = greyNow(); paintShown = PAINT_MIN; mergedShown = SHOW_MERGED; refined = true; refineCursor = 0; }   // switched: send every dot's colour again
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
            ByteBuffer src = greyBuf; fillGrey(refineCursor, end);
            src.position(refineCursor * 4);
            GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, refineCursor * 4, (end - refineCursor) * 4, src);
            src.position(0);
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
        ByteBuffer src = greyBuf; fillGrey(uploaded, count);
        src.position(uploaded * 4);
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, uploaded * 4, (count - uploaded) * 4, src);
        src.position(0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        uploaded = count;
    }

    // ---- Wide overview while scanning (as on the iPhone, ScanOverview.swift) ----
    /** Turn the overview off here: the scan screen is then the camera view as before. */
    static final boolean OVERVIEW = true;
    /** Debug tools "Pulled-back view": the scan view's camera sits behind the phone (0.6 m, wider) and pulls further back
     *  while lost. Off (rk 11 Nov 21:28: "don't pull back at all", to try scanning with plain walls kept in sight from
     *  further away): the view is the phone's own. */
    static boolean PULL_BACK = false;
    /** Virtual camera: metres behind and above the phone, vertical field of view; the camera window's distance and opacity. */
    // rk 9 Nov "the pullback is a bit too much": 0.6 m and 85° (were 0.9 m, 100°) show the camera's area ~1.5x bigger.
    static final float OV_PULL = 0.6f, OV_LIFT = 0.2f, OV_FOV = 85f, OV_WINDOW = 1.2f, OV_OPACITY = 0.7f;
    /** Live mesh corners needed before easing in, and how long the easing takes. */
    static final int OV_MIN_VERTS = 4500; static final long OV_EASE_MS = 1500;
    private float overviewBlend, ovPull;
    private long ovEaseStart = -1;
    private float[] ovPos, ovBack, ovUp;
    private final float[] realMvp = new float[16], ovFrom = new float[3];
    private float ovFree = 99f; private int ovCheck;
    private int ovProg, ovLineProg;

    /** Each scan frame: eases in once there's mesh, then replaces mvp with the pulled-back camera's. */
    private void overviewCamera(boolean tracking, long nowMs) {
        if (viewing || !PULL_BACK) { overviewBlend = 0; ovEaseStart = -1; ovPos = null; return; }
        if (ovEaseStart < 0 && tracking && (liveMeshTriVerts >= OV_MIN_VERTS || (LIVE_POINTS && count > 20000))) ovEaseStart = nowMs;
        if (ovEaseStart < 0) return;
        float t = Math.min(1f, (nowMs - ovEaseStart) / (float) OV_EASE_MS);
        overviewBlend = t * t * (3 - 2 * t);
        float[] inv = new float[16];
        Matrix.invertM(inv, 0, view, 0); // display-oriented camera to world: x right, y up, +z behind
        float[] p = {inv[12], inv[13], inv[14]}, b = norm(new float[]{inv[8], inv[9], inv[10]}), u = norm(new float[]{inv[4], inv[5], inv[6]});
        // Don't pull back through scanned things behind you (checked a few times a second on a sample of the points).
        if (++ovCheck % 8 == 0) ovFree = freeBehind(p, b, OV_PULL + 0.4f);
        float wanted = Math.max(0, Math.min(OV_PULL, ovFree - 0.25f));
        ovPull += (wanted - ovPull) * 0.2f;
        float[] target = {p[0] + b[0] * ovPull * overviewBlend, p[1] + b[1] * ovPull * overviewBlend + OV_LIFT * overviewBlend, p[2] + b[2] * ovPull * overviewBlend};
        if (ovPos == null) { ovPos = target; ovBack = b; ovUp = u; }
        else for (int i = 0; i < 3; i++) { ovPos[i] += (target[i] - ovPos[i]) * 0.35f; ovBack[i] += (b[i] - ovBack[i]) * 0.35f; ovUp[i] += (u[i] - ovUp[i]) * 0.35f; }
        ovBack = norm(ovBack); ovUp = norm(ovUp);
        float[] v = new float[16], pr = new float[16];
        Matrix.setLookAtM(v, 0, ovPos[0], ovPos[1], ovPos[2], ovPos[0] - ovBack[0], ovPos[1] - ovBack[1], ovPos[2] - ovBack[2], ovUp[0], ovUp[1], ovUp[2]);
        float realFov = (float) Math.toDegrees(2 * Math.atan(1 / proj[5]));
        Matrix.perspectiveM(pr, 0, realFov + (OV_FOV - realFov) * overviewBlend, width / (float) Math.max(1, height), 0.05f, 100f);
        Matrix.multiplyMM(mvp, 0, pr, 0, v, 0);
        ovFrom[0] = p[0]; ovFrom[1] = p[1]; ovFrom[2] = p[2];
    }

    private static float[] norm(float[] a) { float l = (float) Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]); return l < 1e-6f ? a : new float[]{a[0] / l, a[1] / l, a[2] / l}; }

    /** How far behind the phone the space is free: the nearest scanned point within 20 cm of the backward ray. */
    private float freeBehind(float[] p, float[] b, float reach) {
        int n = count, step = Math.max(1, n / 20000);
        float best = 99f;
        for (int i = 0; i < n; i += step) {
            float dx = pos.get(i * 3) - p[0], dy = pos.get(i * 3 + 1) - p[1], dz = pos.get(i * 3 + 2) - p[2];
            float t = dx * b[0] + dy * b[1] + dz * b[2];
            if (t <= 0 || t > reach) continue;
            float ox = dx - b[0] * t, oy = dy - b[1] * t, oz = dz - b[2] * t;
            if (ox * ox + oy * oy + oz * oz < 0.04f && t < best) best = t;
        }
        return best;
    }

    /** The live camera picture as a window on the real camera's view, OV_WINDOW ahead, see-through, with a thin frame. */
    private void drawOverviewWindow() {
        if (ovProg == 0) {
            ovProg = program("attribute vec4 aPos; attribute vec2 aUv; varying vec2 vUv; void main(){ gl_Position = aPos; vUv = aUv; }",
                    "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float; varying vec2 vUv; uniform samplerExternalOES uTex; uniform float uA;" +
                    "void main(){ gl_FragColor = vec4(texture2D(uTex, vUv).rgb, uA); }");
            ovLineProg = program("attribute vec4 aPos; void main(){ gl_Position = aPos; }",
                    "precision mediump float; uniform float uA; void main(){ gl_FragColor = vec4(1.0, 1.0, 1.0, 0.9 * uA); }");
        }
        float[] inv = new float[16];
        Matrix.invertM(inv, 0, realMvp, 0);
        float[] fwd = new float[16]; Matrix.invertM(fwd, 0, view, 0);
        float fx = -fwd[8], fy = -fwd[9], fz = -fwd[10];
        float[] clip = new float[16];
        for (int k = 0; k < 4; k++) {
            float[] far = new float[4];
            Matrix.multiplyMV(far, 0, inv, 0, new float[]{quad.get(k * 2), quad.get(k * 2 + 1), 1, 1}, 0);
            float[] d = norm(new float[]{far[0] / far[3] - ovFrom[0], far[1] / far[3] - ovFrom[1], far[2] / far[3] - ovFrom[2]});
            float s = OV_WINDOW / Math.max(0.2f, d[0] * fx + d[1] * fy + d[2] * fz);
            float[] w = {ovFrom[0] + d[0] * s, ovFrom[1] + d[1] * s, ovFrom[2] + d[2] * s, 1};
            Matrix.multiplyMV(clip, k * 4, mvp, 0, w, 0);
        }
        FloatBuffer cb = fb(clip);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);
        GLES20.glDepthFunc(GLES20.GL_LESS); // behind what's nearer than it, over the rest
        GLES20.glUseProgram(ovProg);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, bgTex);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(ovProg, "uTex"), 0);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(ovProg, "uA"), OV_OPACITY * overviewBlend * (heldMode != 0 ? 0.6f : 1f));
        int aPos = GLES20.glGetAttribLocation(ovProg, "aPos"), aUv = GLES20.glGetAttribLocation(ovProg, "aUv");
        quadUv.position(0);
        GLES20.glVertexAttribPointer(aPos, 4, GLES20.GL_FLOAT, false, 0, cb);
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, quadUv);
        GLES20.glEnableVertexAttribArray(aPos); GLES20.glEnableVertexAttribArray(aUv);
        // The flat picture only while the overview eases in; after that the picture is on the mesh
        // (drawOverviewProjection) and only the frame stays.
        if (LivePreview.SHOW_CAMERA && !(LIVE_MESH && overviewBlend > 0.5f)) GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aUv);
        // The frame: corners 0 1 3 2 (the strip's order), drawn over everything.
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        float[] o = new float[20]; int[] ord = {0, 1, 3, 2, 0};
        for (int i = 0; i < 5; i++) System.arraycopy(clip, ord[i] * 4, o, i * 4, 4);
        GLES20.glUseProgram(ovLineProg);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(ovLineProg, "uA"), overviewBlend);
        int lp = GLES20.glGetAttribLocation(ovLineProg, "aPos");
        GLES20.glVertexAttribPointer(lp, 4, GLES20.GL_FLOAT, false, 0, fb(o));
        GLES20.glEnableVertexAttribArray(lp);
        // (no frame line around the camera window: an unexplained white box on the scan screen, rk 10 Nov 15:53)
        GLES20.glDisableVertexAttribArray(lp);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LESS);
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    // ---- Background: diagonal zebra stripes where nothing was scanned (rk 9 Nov), fixed on screen ----
    /** Stripe colours (#6b6b6b, #3a3a3a) and width in dp, at 45°. */
    static final float[] ZEBRA_LIGHT = {0x6b / 255f, 0x6b / 255f, 0x6b / 255f}, ZEBRA_DARK = {0x3a / 255f, 0x3a / 255f, 0x3a / 255f};
    static final float ZEBRA_DP = 11;
    private int zebraProg;

    /** Full-screen stripes, `a` opaque, under everything (no depth). */
    /** With grey dots the background is blue (stripes and plain), so black and white dots both stand out. */
    static final float[] GREY_BG_LIGHT = {0.22f, 0.34f, 0.52f}, GREY_BG_DARK = {0.15f, 0.25f, 0.40f};
    private void clearBg() { if (greyNow()) GLES20.glClearColor(0.15f, 0.25f, 0.40f, 1f); else GLES20.glClearColor(0.08f, 0.08f, 0.1f, 1f); }

    /** The capture zone on screen (rk 13 Nov, as the iPhone's CaptureZone and LiveDiscs.encodeStripes): mode 0 = fine
     *  amber diagonal stripes inside the zone's rounded rectangle (drawn before the dots, so they show only where nothing
     *  is scanned yet); mode 1 = the outside very slightly darker and a thin light outline (drawn over the dots). */
    private void drawZone(int mode) {
        if (stripeProg == 0) stripeProg = program("attribute vec4 aPos; void main(){ gl_Position = aPos; }",
                "precision highp float; uniform vec4 uRect; uniform float uR; uniform float uBand; uniform vec4 uTint; uniform float uMode; uniform float uLine;"
                + "void main(){ vec2 p = gl_FragCoord.xy; vec2 c = (uRect.xy + uRect.zw) * 0.5; vec2 hs = (uRect.zw - uRect.xy) * 0.5 - uR;"
                + " vec2 q = abs(p - c) - hs; float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - uR;"
                + " if (uMode < 0.5) { if (d > 0.0) discard;"
                + "   if (mod(floor((p.x + p.y) / (uBand * 1.41421)), 2.0) > 0.5) discard; gl_FragColor = uTint; return; }"
                + " if (abs(d) <= uLine * 0.5) { gl_FragColor = vec4(1.0, 1.0, 1.0, 0.45); return; }"
                + " if (d > 0.0) { gl_FragColor = vec4(0.0, 0.0, 0.0, 0.18); return; } discard; }");
        float dens = activity.getResources().getDisplayMetrics().density, w = width, h = height;
        boolean depthWasOn = GLES20.glIsEnabled(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUseProgram(stripeProg);
        GLES20.glUniform4f(GLES20.glGetUniformLocation(stripeProg, "uRect"), w * (1 - ZONE_W) / 2, h * (1 - ZONE_H) / 2, w * (1 + ZONE_W) / 2, h * (1 + ZONE_H) / 2);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(stripeProg, "uR"), ZONE_RADIUS_DP * dens);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(stripeProg, "uBand"), STRIPE_DP * dens);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(stripeProg, "uLine"), Math.max(1f, dens));
        GLES20.glUniform4f(GLES20.glGetUniformLocation(stripeProg, "uTint"), 0xED / 255f, 0xB1 / 255f, 0x4D / 255f, STRIPE_ALPHA);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(stripeProg, "uMode"), mode);
        int aPos = GLES20.glGetAttribLocation(stripeProg, "aPos");
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);   // the quad is a client array: no buffer may be bound
        quad.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDepthMask(true);
        if (depthWasOn) GLES20.glEnable(GLES20.GL_DEPTH_TEST);
    }

    private void drawZebra(float a) {
        if (zebraProg == 0) zebraProg = program("attribute vec4 aPos; void main(){ gl_Position = aPos; }",
                "precision highp float; uniform float uW; uniform float uA; uniform vec3 uL; uniform vec3 uD;"
                + "void main(){ float s = mod(floor((gl_FragCoord.x + gl_FragCoord.y) / (uW * 1.41421)), 2.0);"
                + " gl_FragColor = vec4(mix(uD, uL, s), uA); }");
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUseProgram(zebraProg);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(zebraProg, "uW"), ZEBRA_DP * activity.getResources().getDisplayMetrics().density);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(zebraProg, "uA"), a);
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(zebraProg, "uL"), 1, greyNow() ? GREY_BG_LIGHT : ZEBRA_LIGHT, 0);
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(zebraProg, "uD"), 1, greyNow() ? GREY_BG_DARK : ZEBRA_DARK, 0);
        int aPos = GLES20.glGetAttribLocation(zebraProg, "aPos");
        quad.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDepthMask(true);
    }

    private int ovDimProg;

    /** A dark full-screen veil, `a` opaque. */
    private void drawDim(float a) {
        if (ovDimProg == 0) ovDimProg = program("attribute vec4 aPos; void main(){ gl_Position = aPos; }",
                "precision mediump float; uniform float uA; void main(){ gl_FragColor = vec4(0.05, 0.05, 0.06, uA); }");
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUseProgram(ovDimProg);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(ovDimProg, "uA"), a);
        int aPos = GLES20.glGetAttribLocation(ovDimProg, "aPos");
        quad.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisable(GLES20.GL_BLEND);
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
    private volatile int style = 2; // 0 = photo surface, 2 = dots, 3 = mesh (1 = splats: no longer offered); Points first (rk 10 Nov 15:22)

    private static boolean thumbKindIs(File dir, String kind) {
        try { return kind.equals(new String(java.nio.file.Files.readAllBytes(new File(dir, "thumbnail-kind").toPath()), "UTF-8").trim()); } catch (Exception e) { return false; }
    }

    String cycleStyle() {
        style = (style + 1) % 4; if (style == 1) style = 2; // no splats view
        splats = style != 2;
        return styleLabel();
    }

    /** 0 = photo surface, 1 = splats, 2 = dots (the viewer's Photo | Splats | Points switch). */
    /** The Home thumbnail should show the photo room (like the iPhone): taken once it's on screen. */
    private volatile boolean photoThumbWanted;

    /** GL thread, right after a frame: saves that frame (the photo room) as the scan's thumbnail. */
    private void savePhotoThumb() {
        final File dir = currentDir;
        java.nio.ByteBuffer px = java.nio.ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
        final int w = width, h = height;
        new Thread(() -> { try {
            px.position(0);
            android.graphics.Bitmap full = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            full.copyPixelsFromBuffer(px);
            // The middle of the view at 4:3 (the bars cover the top and bottom on screen), 360 px wide, upright.
            int cw = w, ch = Math.min(h, w * 3 / 4), cy = (h - ch) / 2;
            android.graphics.Matrix flip = new android.graphics.Matrix();
            float sc = 360f / cw;
            flip.postScale(sc, -sc);
            android.graphics.Bitmap thumb = android.graphics.Bitmap.createBitmap(full, 0, cy, cw, ch, flip, true);
            full.recycle();
            try (java.io.FileOutputStream f = new java.io.FileOutputStream(new File(dir, "thumb.jpg"))) { thumb.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, f); }
            try (java.io.FileOutputStream f = new java.io.FileOutputStream(new File(dir, "thumbnail-kind"))) { f.write("points".getBytes("UTF-8")); }
            log("thumbnail: photo room");
        } catch (Throwable e) { logError("photo thumbnail", new RuntimeException(e)); } }, "photo-thumb").start();
    }

    /** A binary PLY mesh (x y z float, red green blue uchar, faces as uchar-count int lists) as a triangle soup. */
    private static Object[] readMeshPly(File f) throws Exception {
        byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
        String head = new String(all, 0, Math.min(all.length, 4096), "US-ASCII");
        int end = head.indexOf("end_header\n");
        if (end < 0) throw new Exception("no PLY header");
        int nv = 0, nf = 0;
        for (String line : head.substring(0, end).split("\n")) {
            String[] w = line.trim().split(" ");
            if (w.length == 3 && w[0].equals("element")) { if (w[1].equals("vertex")) nv = Integer.parseInt(w[2]); else if (w[1].equals("face")) nf = Integer.parseInt(w[2]); }
        }
        ByteBuffer b = ByteBuffer.wrap(all, end + "end_header\n".length(), all.length - end - "end_header\n".length()).order(ByteOrder.LITTLE_ENDIAN);
        float[] v = new float[nv * 3]; byte[] c = new byte[nv * 3];
        for (int i = 0; i < nv; i++) { v[i * 3] = b.getFloat(); v[i * 3 + 1] = b.getFloat(); v[i * 3 + 2] = b.getFloat(); c[i * 3] = b.get(); c[i * 3 + 1] = b.get(); c[i * 3 + 2] = b.get(); }
        java.util.ArrayList<int[]> faces = new java.util.ArrayList<>();
        for (int i = 0; i < nf; i++) {
            int k = b.get() & 0xFF; int[] p = new int[k];
            for (int j = 0; j < k; j++) p[j] = b.getInt();
            for (int j = 1; j + 1 < k; j++) faces.add(new int[]{p[0], p[j], p[j + 1]});
        }
        int tris = faces.size();
        FloatBuffer pos = ByteBuffer.allocateDirect(Math.max(1, tris * 9) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        ByteBuffer col = ByteBuffer.allocateDirect(Math.max(4, tris * 12)).order(ByteOrder.nativeOrder());
        for (int[] t : faces) for (int vi : t) {
            if (vi < 0 || vi >= nv) vi = 0;
            pos.put(v[vi * 3]).put(v[vi * 3 + 1]).put(v[vi * 3 + 2]);
            col.put(c[vi * 3]).put(c[vi * 3 + 1]).put(c[vi * 3 + 2]).put((byte) 255);
        }
        pos.position(0); col.position(0);
        return new Object[]{pos, col, tris};
    }

    /** The sending phone's manifest (kept on import), for the room's size and counts as measured there. */
    private org.json.JSONObject sourceManifest() {
        try { return currentDir == null ? null : new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(new File(currentDir, "source-manifest.json").toPath()), "UTF-8")); }
        catch (Exception e) { return null; }
    }

    /** The scan's summary rows for the viewer, like the iPhone's: {section, label, value}. */
    java.util.List<String[]> roomFacts() {
        java.util.List<String[]> f = new java.util.ArrayList<>();
        org.json.JSONObject sm = sourceManifest();
        org.json.JSONObject sr = sm == null ? null : sm.optJSONObject("room");
        if (sr != null && sr.optJSONArray("walls") != null && sr.optJSONArray("walls").length() > 0) {
            f.add(new String[]{"Room", "Walls", String.valueOf(sr.optJSONArray("walls").length())});
            if (sr.has("windows")) f.add(new String[]{"Room", "Windows", String.valueOf(sr.optInt("windows"))});
            if (sr.has("doors")) f.add(new String[]{"Room", "Doors", String.valueOf(sr.optInt("doors"))});
        } else if (roomReady && room.hasLayout && !room.layout.isEmpty()) f.add(new String[]{"Room", "Walls", String.valueOf(room.layout.size())});
        int outletCount; synchronized (outlets) { outletCount = outlets.list.size(); }
        if (outletCount > 0) f.add(new String[]{"Room", "Power outlets", String.valueOf(outletCount)});
        if (!objects.list.isEmpty()) f.add(new String[]{"Room", "Objects", String.valueOf(objects.list.size())});
        f.add(new String[]{"Capture", "Points", String.format(java.util.Locale.US, "%,d", count)});
        f.add(new String[]{"Capture", "Photos", String.valueOf(photos.keyframes.size())});
        File rec = currentDir == null ? null : new File(currentDir, "recording.mp4");
        if (rec != null && rec.exists()) f.add(new String[]{"Capture", "Raw recording", String.format(java.util.Locale.US, "%.0f MB", rec.length() / 1e6)});
        return f;
    }

    /** "8.2 × 5.3 m, 2.9 m high" from the scan's extent, or null before it's loaded. */
    /** The floor area: the footprint split into rooms at doorways (FloorArea); a tap picks one room (0 = none). */
    private volatile FloorArea.Result floorArea;
    private volatile int selectedRoom;
    private float[] selectedRoomVerts;

    // ---- Adjusting a room's walls by hand (debug switch "Adjust room walls"; rk 10 Nov: a thick curtain read as the
    // wall, 20:25 scan 3.6 m for a 3.95 m room). The walled rectangle's sides move along their own direction; saved in
    // floor-edits.json (metres outwards per side, per room), the automatic outline stays as it was. ----
    static volatile boolean ADJUST_WALLS = true;   // (rk chose it 10 Nov 21:38: on for everyone)
    private float[][] wallEdits;           // per room: outwards metres for sides u0, u1, v0, v1
    private volatile int dragRoom = -1, dragSide = -1;

    private void loadWallEdits(FloorArea.Result fa) {
        wallEdits = new float[fa.rooms.size()][4];
        File d = currentDir; if (d == null) return;
        File f = new File(d, "floor-edits.json"); if (!f.exists()) return;
        try {
            org.json.JSONArray a = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8")).getJSONArray("rooms");
            for (int i = 0; i < a.length() && i < wallEdits.length; i++) for (int k = 0; k < 4; k++) wallEdits[i][k] = (float) a.getJSONArray(i).getDouble(k);
        } catch (Exception e) { logError("floor edits", e); }
    }
    private void saveWallEdits() {
        File d = currentDir; float[][] we = wallEdits; if (d == null || we == null) return;
        try {
            org.json.JSONArray a = new org.json.JSONArray();
            for (float[] r : we) a.put(new org.json.JSONArray(new double[]{r[0], r[1], r[2], r[3]}));
            java.nio.file.Files.write(new File(d, "floor-edits.json").toPath(), new org.json.JSONObject().put("rooms", a).toString().getBytes("UTF-8"));
        } catch (Exception e) { logError("floor edits", e); }
    }
    void resetWallEdits() {
        FloorArea.Result fa = floorArea; if (fa == null) return;
        wallEdits = new float[fa.rooms.size()][4];
        File d = currentDir; if (d != null) new File(d, "floor-edits.json").delete();
        saveFloorAreaJson();
        activity.setRoomSize(roomSizeText());
    }
    /** A room's sides with the edits (u0, u1, v0, v1), or null when it has no wall rectangle. */
    private float[] editedRect(int i) {
        FloorArea.Result fa = floorArea; if (fa == null || i < 0 || i >= fa.rooms.size()) return null;
        FloorArea.Room r = fa.rooms.get(i); if (r.rect == null) return null;
        float[] e = wallEdits != null && i < wallEdits.length ? wallEdits[i] : new float[4];
        return new float[]{r.rect[0] - e[0], r.rect[1] + e[1], r.rect[2] - e[2], r.rect[3] + e[3]};
    }
    /** Any room's walls dragged by hand (the result shows "Reset walls"). */
    boolean wallsEdited() { float[][] we = wallEdits; if (we == null || !viewing) return false; for (int i = 0; i < we.length; i++) if (edited(i)) return true; return false; }
    private boolean edited(int i) { float[][] we = wallEdits; return we != null && i < we.length && (we[i][0] != 0 || we[i][1] != 0 || we[i][2] != 0 || we[i][3] != 0); }
    /** A room's line with the edits: the walled area changes by the rectangle's change. */
    private String roomLine(int i) {
        FloorArea.Result fa = floorArea; FloorArea.Room r = fa.rooms.get(i);
        if (!edited(i)) return r.text(i);
        float[] sz = r.editedSize(wallEdits[i]);
        return String.format(java.util.Locale.US, "Room %d: %s m², %.2f × %.2f m (walls set by hand)", i + 1, FloorArea.Room.m2(r.editedArea(wallEdits[i])), sz[0], sz[1]);
    }
    private float[] worldOf(FloorArea.Result fa, float deg, float u, float v) {
        double t = Math.toRadians(deg); float ca = (float) Math.cos(t), sa = (float) Math.sin(t);
        return new float[]{fa.x0 + u * ca - v * sa, room.floorY + 0.02f, fa.z0 + u * sa + v * ca};
    }
    private float[] screenOf(float[] w) {
        float[] c = new float[4]; Matrix.multiplyMV(c, 0, viewMvp, 0, new float[]{w[0], w[1], w[2], 1}, 0);
        if (c[3] <= 0.01f) return null;
        return new float[]{(c[0] / c[3] + 1) / 2 * width, (1 - c[1] / c[3]) / 2 * height};
    }
    private int adjustRoom() { int s = selectedRoom; return s > 0 ? s - 1 : 0; }
    /** UI thread: a finger near one of the room's wall lines picks it up. */
    boolean wallDragStart(float tx, float ty) {
        if (!ADJUST_WALLS || !viewing || floorArea == null || !roomReady) return false;
        int i = adjustRoom(); float[] e = editedRect(i); if (e == null) return false;
        float deg = floorArea.rooms.get(i).deg; float bestD = 48 * height / 1000f; int best = -1;
        for (int sd = 0; sd < 4; sd++) {
            float[] a = sd < 2 ? worldOf(floorArea, deg, e[sd], e[2]) : worldOf(floorArea, deg, e[0], e[sd]);
            float[] b = sd < 2 ? worldOf(floorArea, deg, e[sd], e[3]) : worldOf(floorArea, deg, e[1], e[sd]);
            float[] sa = screenOf(a), sb = screenOf(b); if (sa == null || sb == null) continue;
            float dx = sb[0] - sa[0], dy = sb[1] - sa[1], l2 = dx * dx + dy * dy;
            float t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((tx - sa[0]) * dx + (ty - sa[1]) * dy) / l2));
            float d = (float) Math.hypot(tx - sa[0] - t * dx, ty - sa[1] - t * dy);
            if (d < bestD) { bestD = d; best = sd; }
        }
        if (best < 0) return false;
        dragRoom = i; dragSide = best; return true;
    }
    /** UI thread: the side follows the finger on the floor, in 1 cm steps, at most 1.5 m from the automatic line. */
    void wallDragTo(float tx, float ty) {
        int i = dragRoom, sd = dragSide; FloorArea.Result fa = floorArea;
        if (i < 0 || sd < 0 || fa == null || wallEdits == null) return;
        float[] inv = new float[16]; if (!Matrix.invertM(inv, 0, viewMvp, 0)) return;
        float nx = 2f * tx / width - 1f, ny = 1f - 2f * ty / height;
        float[] a = new float[4], b = new float[4];
        Matrix.multiplyMV(a, 0, inv, 0, new float[]{nx, ny, -1, 1}, 0); Matrix.multiplyMV(b, 0, inv, 0, new float[]{nx, ny, 1, 1}, 0);
        float ox = a[0] / a[3], oy = a[1] / a[3], oz = a[2] / a[3], dx = b[0] / b[3] - ox, dy = b[1] / b[3] - oy, dz = b[2] / b[3] - oz;
        if (Math.abs(dy) < 1e-6f) return;
        float t = (room.floorY - oy) / dy; if (t <= 0) return;
        FloorArea.Room r = fa.rooms.get(i);
        double rad = Math.toRadians(r.deg); float ca = (float) Math.cos(rad), sa = (float) Math.sin(rad);
        float px = ox + dx * t - fa.x0, pz = oz + dz * t - fa.z0, u = px * ca + pz * sa, v = -px * sa + pz * ca;
        float at = sd < 2 ? u : v, auto = r.rect[sd];
        float out = (sd % 2 == 0 ? auto - at : at - auto);
        out = Math.round(Math.max(-1.5f, Math.min(1.5f, out)) * 100) / 100f;
        wallEdits[i][sd] = out;
        activity.setRoomSize(roomSizeText());
    }
    void wallDragEnd() { if (dragSide >= 0) { saveWallEdits(); saveFloorAreaJson(); dragRoom = -1; dragSide = -1; activity.setRoomSize(roomSizeText()); } }

    /** floor-area.json in the scan folder (and so in the .rmsc): per room its floor area, width × length, outline corners
     *  (x, z in the scan's world) and whether the walls were set by hand. */
    private void saveFloorAreaJson() {
        FloorArea.Result fa = floorArea; File d = currentDir; if (fa == null || d == null) return;
        try {
            org.json.JSONArray rooms = new org.json.JSONArray(); float total = 0;
            for (int i = 0; i < fa.rooms.size(); i++) {
                FloorArea.Room r = fa.rooms.get(i); boolean ed = edited(i);
                float area = ed ? r.editedArea(wallEdits[i]) : r.shown(); float[] sz = ed ? r.editedSize(wallEdits[i]) : new float[]{r.w, r.l};
                total += area;
                org.json.JSONObject o = new org.json.JSONObject().put("area", Math.round(area * 100) / 100.0).put("width", Math.round(sz[0] * 100) / 100.0)
                        .put("length", Math.round(sz[1] * 100) / 100.0).put("handSet", ed).put("wallSides", r.wallSides);
                float[] e = editedRect(i);
                if (e != null) {
                    org.json.JSONArray c = new org.json.JSONArray();
                    for (float[] uv : new float[][]{{e[0], e[2]}, {e[1], e[2]}, {e[1], e[3]}, {e[0], e[3]}}) { float[] w = worldOf(fa, r.deg, uv[0], uv[1]); c.put(new org.json.JSONArray(new double[]{Math.round(w[0] * 1000) / 1000.0, Math.round(w[2] * 1000) / 1000.0})); }
                    o.put("outline", c);
                }
                rooms.put(o);
            }
            java.nio.file.Files.write(new File(d, "floor-area.json").toPath(),
                    new org.json.JSONObject().put("total", Math.round(total * 100) / 100.0).put("rooms", rooms).toString().getBytes("UTF-8"));
        } catch (Exception e) { logError("floor area save", e); }
    }

    /** The adjustable room's wall lines (the one being dragged white). */
    private void drawWallLines(float[] m) {
        if (!ADJUST_WALLS || floorArea == null) return;
        int i = adjustRoom(); float[] e = editedRect(i); if (e == null) return;
        float deg = floorArea.rooms.get(i).deg;
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        for (int sd = 0; sd < 4; sd++) {
            float[] a = sd < 2 ? worldOf(floorArea, deg, e[sd], e[2]) : worldOf(floorArea, deg, e[0], e[sd]);
            float[] b = sd < 2 ? worldOf(floorArea, deg, e[sd], e[3]) : worldOf(floorArea, deg, e[1], e[sd]);
            GLES20.glLineWidth(dragSide == sd ? 8f : 5f);
            planDraw(m, new float[]{a[0], a[1], a[2], b[0], b[1], b[2]}, GLES20.GL_LINES, dragSide == sd ? new float[]{1, 1, 1, 1} : new float[]{1f, 0.69f, 0.13f, 1f}, 1f);
        }
        GLES20.glLineWidth(1f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
    }

    String roomSizeText() {
        String base = roomSizeTextBase();
        FloorArea.Result fa = viewing ? floorArea : null;
        if (fa == null || fa.text() == null) return base;
        if (selectedRoom > 0 && selectedRoom <= fa.rooms.size()) return roomLine(selectedRoom - 1);
        if (fa.rooms.size() == 1 && edited(0)) {
            // walls set by hand (rk 10 Nov 22:57: the size and area stayed the scan's own): the size line is the edited
            // room's, no "about"; the height stays the scan's
            FloorArea.Room r = fa.rooms.get(0); float[] sz = r.editedSize(wallEdits[0]);
            String height = base != null && base.contains(" · ") ? base.substring(base.indexOf(" · ")) : "";
            if (height.contains("floor")) height = "";
            return String.format(java.util.Locale.US, "%.2f × %.2f m", sz[0], sz[1]) + height + " · floor " + FloorArea.Room.m2(r.editedArea(wallEdits[0])) + " m² (walls set by hand)";
        }
        if (fa.rooms.size() >= 2 && wallsEdited()) {
            float all = 0; for (int i = 0; i < fa.rooms.size(); i++) all += edited(i) ? fa.rooms.get(i).editedArea(wallEdits[i]) : fa.rooms.get(i).shown();
            return (base == null ? "" : base + " · ") + String.format(java.util.Locale.US, "%d rooms, floor ≈%.0f m² in all (tap a room)", fa.rooms.size(), all);
        }
        return base == null ? fa.text() : base + " · " + fa.text();
    }

    /** The short form for the title (it must fit on one line): size and floor area only. */
    String roomTitleText() {
        String full = roomSizeText(); if (full == null) return null;
        FloorArea.Result fa = viewing ? floorArea : null;
        if (fa != null && fa.rooms.size() == 1) {
            FloorArea.Room r = fa.rooms.get(0); boolean ed = edited(0);
            float[] sz = ed ? r.editedSize(wallEdits[0]) : new float[]{r.w, r.l};
            return String.format(java.util.Locale.US, "%s%.2f × %.2f m · %s m²", ed ? "" : "≈", sz[0], sz[1], FloorArea.Room.m2(ed ? r.editedArea(wallEdits[0]) : r.shown()));
        }
        int dot = full.indexOf(" · ");
        return dot > 0 && full.length() > 34 ? full.substring(0, dot) : full;
    }

    /** Viewer tap with more than one room: the room under the finger (on the floor), or none again. True if handled. */
    private boolean pickRoom(float tx, float ty) {
        FloorArea.Result fa = floorArea;
        if (!viewing || fa == null || fa.rooms.size() < 2 || !roomReady) return false;
        float[] inv = new float[16];
        if (!Matrix.invertM(inv, 0, viewMvp, 0)) return false;
        float nx = 2f * tx / width - 1f, ny = 1f - 2f * ty / height;
        float[] a = new float[4], b = new float[4];
        Matrix.multiplyMV(a, 0, inv, 0, new float[]{nx, ny, -1, 1}, 0);
        Matrix.multiplyMV(b, 0, inv, 0, new float[]{nx, ny, 1, 1}, 0);
        float ox = a[0] / a[3], oy = a[1] / a[3], oz = a[2] / a[3], dx = b[0] / b[3] - ox, dy = b[1] / b[3] - oy, dz = b[2] / b[3] - oz;
        int id = 0;
        if (Math.abs(dy) > 1e-6f) {
            float t = (room.floorY - oy) / dy;
            if (t > 0) {
                int cx = (int) ((ox + dx * t - fa.x0) / FloorArea.CELL), cz = (int) ((oz + dz * t - fa.z0) / FloorArea.CELL);
                if (cx >= 0 && cz >= 0 && cx < fa.nx && cz < fa.nz) id = fa.roomOf[cz * fa.nx + cx];
            }
        }
        selectedRoom = id == selectedRoom ? 0 : id;
        selectedRoomVerts = null;
        activity.setRoomSize(roomSizeText());
        return true;
    }

    /** The picked room's floor, tinted. */
    private void drawSelectedRoom(float[] m) {
        FloorArea.Result fa = floorArea; int id = selectedRoom;
        if (fa == null || id <= 0) return;
        if (selectedRoomVerts == null) {
            java.util.ArrayList<float[]> q = new java.util.ArrayList<>();
            float y = room.floorY + 0.015f, c = FloorArea.CELL;
            for (int i = 0; i < fa.roomOf.length; i++) if (fa.roomOf[i] == id) {
                float x0 = fa.x0 + (i % fa.nx) * c, z0 = fa.z0 + (i / fa.nx) * c;
                q.add(new float[]{x0, y, z0, x0 + c, y, z0, x0 + c, y, z0 + c, x0, y, z0, x0 + c, y, z0 + c, x0, y, z0 + c});
            }
            float[] v = new float[q.size() * 18]; for (int i = 0; i < q.size(); i++) System.arraycopy(q.get(i), 0, v, i * 18, 18);
            selectedRoomVerts = v;
        }
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);
        planDraw(m, selectedRoomVerts, GLES20.GL_TRIANGLES, new float[]{1f, 0.69f, 0.13f, 0.45f}, 1f);
        GLES20.glDepthMask(true);
    }

    private String roomSizeTextBase() {
        // As measured by the phone that scanned it (an iPhone's RoomPlan walls), when the package said.
        org.json.JSONObject sm = sourceManifest();
        org.json.JSONArray walls = sm == null || sm.optJSONObject("room") == null ? null : sm.optJSONObject("room").optJSONArray("walls");
        if (walls != null && walls.length() > 0) {
            float x0 = 1e9f, x1 = -1e9f, z0 = 1e9f, z1 = -1e9f, hh = 0;
            for (int i = 0; i < walls.length(); i++) {
                org.json.JSONArray w = walls.optJSONArray(i);
                if (w == null || w.length() < 5) continue;
                for (int k = 0; k < 4; k += 2) { float x = (float) w.optDouble(k), z = (float) w.optDouble(k + 1); x0 = Math.min(x0, x); x1 = Math.max(x1, x); z0 = Math.min(z0, z); z1 = Math.max(z1, z); }
                hh = Math.max(hh, (float) w.optDouble(4));
            }
            float h = sm.has("ceilingY") ? (float) (sm.optDouble("ceilingY") - sm.optDouble("floorY", 0)) : hh;
            if (x1 > x0) return String.format(java.util.Locale.US, "%.1f × %.1f m, %.1f m high", Math.max(x1 - x0, z1 - z0), Math.min(x1 - x0, z1 - z0), h);
        }
        // This phone's own scan: the room model's size and height (the same robust numbers the log prints), not the
        // points' bounding box, which stray points stretch (rk 9 Nov: 6.8 × 5.6 m, 3.6 m for a 5.4 × 3.8 m, ~2.9 m room).
        if (roomReady && room.sizeX > 0 && room.sizeZ > 0) {
            // One number everywhere (the title and the room card); a scan that covers little of the floor is called
            // what it is (rk 10 Nov 11:49: a door-area scan showed 2.76 × 1.48 m in the title and 3.7 × 3.3 m below).
            // "≈": the automatic sizes are estimates (S24: room size a few %, height about ±10 cm; rk 10 Nov)
            String size = (room.floorCoverage > 0 && room.floorCoverage < 0.5f ? "scanned area " : "")
                    + String.format(java.util.Locale.US, "≈%.2f × %.2f m", Math.max(room.sizeX, room.sizeZ), Math.min(room.sizeX, room.sizeZ));
            // Only a ceiling ARCore saw (its points or planes) gives a height; depth alone was 15-35 cm off (rk 10 Nov)
            if (room.hasCeiling && room.ceilingTrusted) return size + String.format(java.util.Locale.US, " · ≈%.2f m high", room.ceilingY - room.floorY);
            if (room.hasCeiling) return size + " · height: tilt up to where the walls meet the ceiling";
            return size + (room.ceilingUnknown ? " · height not scanned" : "");
        }
        float w = boxHalfX * 2, d = boxHalfZ * 2, h = boxMaxY - boxMinY;
        if (w <= 0 || d <= 0) return null;
        return String.format(java.util.Locale.US, "%.1f × %.1f m, %.1f m high", Math.max(w, d), Math.min(w, d), h);
    }

    void setStyle(int s) { style = Math.max(0, Math.min(3, s)); if (style == 1) style = 2; } // no Splats view: points
    int style() { return style; }

    String styleLabel() { return style == 0 ? "Style: photo" : style == 1 ? "Style: splats" : style == 2 ? "Style: dots" : "Style: mesh"; }

    // ---- Mesh style: the fused surface, untextured, light grey with simple lighting and a faint wireframe ----
    private FloatBuffer bareMeshSource; private int bareMeshVerts, bareMeshProg, bareLineProg;
    private FloatBuffer bareTri, bareLines; private ByteBuffer bareShade; private int bareTriVerts, bareLineVerts;

    private void drawBareMesh(float[] m) {
        logLinkErrors();
        FloatBuffer mp = meshPosAll != null ? meshPosAll : tsdf.meshPos;
        int verts = meshPosAll != null ? meshVerts : tsdf.meshVerts;
        if (mp == null || verts < 3) return;
        if (mp != bareMeshSource || verts != bareMeshVerts) {
            // Built once per surface: the ceiling left out (as in Photo), a grey shade per corner from its face's
            // direction, and the three edges of each triangle for the wireframe.
            bareMeshSource = mp; bareMeshVerts = verts;
            int tris = verts / 3;
            boolean[] ceil = ceilingTriangles(mp, tris);
            int keep = 0; for (boolean c : ceil) if (!c) keep++;
            bareTri = ByteBuffer.allocateDirect(Math.max(1, keep * 9) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            bareShade = ByteBuffer.allocateDirect(Math.max(1, keep * 3)).order(ByteOrder.nativeOrder());
            bareLines = ByteBuffer.allocateDirect(Math.max(1, keep * 18) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            for (int t = 0; t < tris; t++) {
                if (ceil[t]) continue;
                int b = t * 9;
                float[] v = new float[9];
                for (int i = 0; i < 9; i++) v[i] = mp.get(b + i);
                float ux = v[3] - v[0], uy = v[4] - v[1], uz = v[5] - v[2], wx = v[6] - v[0], wy = v[7] - v[1], wz = v[8] - v[2];
                float nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
                float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz) + 1e-9f;
                float lit = 0.55f + 0.4f * Math.abs((nx * 0.35f + ny * 0.85f + nz * 0.4f) / l);
                byte g = (byte) Math.min(255, Math.round(205 * lit));
                bareTri.put(v);
                bareShade.put(g).put(g).put(g);
                bareLines.put(v, 0, 3).put(v, 3, 3).put(v, 3, 3).put(v, 6, 3).put(v, 6, 3).put(v, 0, 3);
            }
            bareTri.position(0); bareShade.position(0); bareLines.position(0);
            bareTriVerts = keep * 3; bareLineVerts = keep * 6;
        }
        if (bareMeshProg == 0) {
            String hide = HIDE_DECL + "void main(){ float hh = 0.0;" + HIDE_TEST.replace("HIDE", "hh = 1.0;");
            bareMeshProg = program("uniform mat4 uMvp; attribute vec3 aPos; attribute float aShade; varying float vShade;" + hide
                    + " gl_Position = hh > 0.5 ? vec4(2.0, 2.0, 2.0, 1.0) : uMvp * vec4(aPos, 1.0); vShade = aShade; }",
                    "precision mediump float; varying float vShade; void main(){ gl_FragColor = vec4(vec3(vShade), 1.0); }");
            bareLineProg = program("uniform mat4 uMvp; attribute vec3 aPos;" + hide
                    + " gl_Position = hh > 0.5 ? vec4(2.0, 2.0, 2.0, 1.0) : uMvp * vec4(aPos, 1.0); }",
                    "precision mediump float; void main(){ gl_FragColor = vec4(1.0, 1.0, 1.0, 0.22); }");
        }
        GLES20.glUseProgram(bareMeshProg);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(bareMeshProg, "uMvp"), 1, false, m, 0);
        setHideUniforms(bareMeshProg); // removed and moved objects leave their spot, as in the other views
        int aPos = GLES20.glGetAttribLocation(bareMeshProg, "aPos"), aShade = GLES20.glGetAttribLocation(bareMeshProg, "aShade");
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, bareTri);
        GLES20.glVertexAttribPointer(aShade, 1, GLES20.GL_UNSIGNED_BYTE, true, 3, bareShade);
        GLES20.glEnableVertexAttribArray(aPos); GLES20.glEnableVertexAttribArray(aShade);
        GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL);
        GLES20.glPolygonOffset(1f, 1f);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, bareTriVerts);
        GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL);
        GLES20.glDisableVertexAttribArray(aShade);
        GLES20.glUseProgram(bareLineProg);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(bareLineProg, "uMvp"), 1, false, m, 0);
        setHideUniforms(bareLineProg);
        int lp = GLES20.glGetAttribLocation(bareLineProg, "aPos");
        GLES20.glVertexAttribPointer(lp, 3, GLES20.GL_FLOAT, false, 0, bareLines);
        GLES20.glEnableVertexAttribArray(lp);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);
        GLES20.glLineWidth(1f);
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, bareLineVerts);
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisableVertexAttribArray(lp);
    }

    private void drawPoints(float[] m, float sizeScale) { drawPoints(m, sizeScale, false); }

    private void drawPoints(float[] m, float sizeScale, boolean live) {
        if (count == 0) return;
        GLES20.glUseProgram(ptProg);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(ptProg, "uLive"), live && splats ? 1f : 0f);
        if (live) {   // cell sizes, the camera's own focal length in pixels, and a cap of 160 px on a 2556 px tall screen
            GLES20.glUniform1f(GLES20.glGetUniformLocation(ptProg, "uCell"), VOXEL);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(ptProg, "uCellFine"), VOXEL_FINE);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(ptProg, "uPxM"), proj[5] * height / 2f);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(ptProg, "uCapPx"), 160f * height / 2556f);
        }
        flatTiles.bindMask(ptProg, FLAT_TILES);   // points on and just behind painted tiles aren't drawn (rk 10 Nov 16:22)
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
            GLES20.glUniform1f(uSize, VOXEL * pxPerMeter * 1.4f * sizeScale * DOT_SCALE);
            GLES20.glUniform1f(uPass, 2f);
            drawPointArrays();
            return;
        }
        // Splat look: an opaque core pass fills the surface and the depth buffer,
        // then a larger soft gaussian halo is blended on top without writing depth.
        GLES20.glUniform1f(uSize, VOXEL * pxPerMeter * 2.0f * sizeScale * DOT_SCALE);
        GLES20.glUniform1f(uPass, 0f);
        drawPointArrays();
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);
        GLES20.glUniform1f(uSize, VOXEL * pxPerMeter * 3.2f * sizeScale * DOT_SCALE);
        GLES20.glUniform1f(uPass, 1f);
        drawPointArrays();
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(ptProg, "uLive"), 0f);   // other users of ptProg (path, paint) draw as before
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
            clearBg();
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
        clearBg();
    }

    // ---- live surface mesh (rk, 7 Nov: like the iPhone's LiDAR mesh, polygons on what's detected, not splats) ----
    static final boolean LIVE_MESH = true;
    /** The live scan view: the captured points (the same ones the Points view shows after Done), or the photo-textured
     *  mesh. Debug switch "Live view". */
    static volatile boolean LIVE_POINTS = true;
    /** Photos for texturing while scanning and the photo-textured surface at Done (rk 10 Nov 15:34: "stop generating the
     *  photo mode, it's only wasting performance"). Off: the result is Points (and the plain Mesh); debug switch. */
    static volatile boolean PHOTO_SURFACE = false;
    /** Flat tiles (rk 10 Nov 16:05 trial): flat surfaces painted from the camera in 30 cm tiles. Debug switch. */
    static volatile boolean FLAT_TILES = true;
    /** Debug switch, off by default (rk 10 Nov): points under tiles left out of the result and the export, Measure on the tile instead. */
    static volatile boolean DROP_UNDER_TILES = false;
    final FlatTiles flatTiles = new FlatTiles();

    /** Twice a second while tracking well and not moving fast: tiles from the latest depth image, when it was taken
     *  from where the camera is now (so the picture and the depth agree). */
    private volatile FlatTiles.Job tilesJob; private volatile boolean tilesBusy; private long tilesLogAt;

    /** Twice a second while tracking well and not moving fast: the flat surface and its tiles from the latest depth
     *  image on a background thread (it took 15 ms on the drawing thread); painting here, when the camera is still
     *  where that depth was taken (so picture and depth agree). */
    private void maybeTiles(Frame frame, Camera camera, long nowMs) {
        boolean good = camera.getTrackingState() == TrackingState.TRACKING && !needRealign && !tooFast;
        FlatTiles.Job j = tilesJob;
        if (j != null) {
            tilesJob = null;
            float dx = curPose[12] - j.pose[12], dy = curPose[13] - j.pose[13], dz = curPose[14] - j.pose[14];
            float dot = curPose[8] * j.pose[8] + curPose[9] * j.pose[9] + curPose[10] * j.pose[10];
            if (good && dx * dx + dy * dy + dz * dz < 0.03f * 0.03f && dot > 0.999f) {
                long t0 = System.nanoTime();
                CameraIntrinsics ti = camera.getTextureIntrinsics(); int[] td = ti.getImageDimensions();
                final float[] tin = {ti.getFocalLength()[0], ti.getFocalLength()[1], ti.getPrincipalPoint()[0], ti.getPrincipalPoint()[1], td[0], td[1]};
                try { flatTiles.paint(j, tin, bgTex, (px, out) -> { for (int i = 0; i < px.length; i += 2) { out[i] = px[i] / tin[4]; out[i + 1] = px[i + 1] / tin[5]; } }); }   // texture pixels -> ARCore's TEXTURE_NORMALIZED (0,0 top left)
                catch (Exception e) { logError("flat tiles", e); }
                perf.note("ms: flat tiles paint", (System.nanoTime() - t0) / 1e6);
            }
        }
        if (nowMs - tilesLogAt > 10000 && flatTiles.attempts > 0) { tilesLogAt = nowMs; log(flatTiles.stats()); }
        if (tilesBusy || nowMs - flatTiles.lastMs < 500 || !good || lastDepth == null) return;
        float dx = curPose[12] - lastDPose[12], dy = curPose[13] - lastDPose[13], dz = curPose[14] - lastDPose[14];
        float dot = curPose[8] * lastDPose[8] + curPose[9] * lastDPose[9] + curPose[10] * lastDPose[10];
        if (dx * dx + dy * dy + dz * dz > 0.03f * 0.03f || dot < 0.999f) return;
        final short[] d = lastDepth; final int dw = lastDw, dh = lastDh; final float[] di = lastDIntr, pose = lastDPose.clone();
        tilesBusy = true; flatTiles.lastMs = nowMs;
        Thread th = new Thread(() -> {
            long t0 = System.nanoTime();
            try { tilesJob = flatTiles.analyse(d, dw, dh, di, pose, nowMs); } catch (Exception e) { logError("flat tiles", e); }
            perf.note("ms: flat tiles analyse (thread)", (System.nanoTime() - t0) / 1e6);
            tilesBusy = false;
        }, "flat-tiles");
        th.setPriority(Thread.MIN_PRIORITY); th.start();
    }

    /** Points live view: the finished live mesh isn't drawn; take it so the next one gets built (for the doorways). */
    private void consumeLiveMesh() {
        Object[] nm = liveMeshNew;
        if (nm == null) return;
        liveMeshNew = null;
        liveMeshTriVerts = ((LivePreview.Result) nm[0]).restVerts;   // the wide overview starts once there's enough
    }
    static final long LIVE_MESH_MS = 1200;
    static final float LIVE_MESH_RADIUS = 8f;
    private volatile boolean liveMeshBusy;
    private Tsdf liveScratch;                // reused between live mesh builds (only the builder thread touches it)
    private java.nio.FloatBuffer liveLines; private ByteBuffer liveLineConf;
    private volatile Object[] liveMeshNew;   // {FloatBuffer tris, ByteBuffer conf, FloatBuffer lines, ByteBuffer lineConf, int[] {triVerts, lineVerts}}
    private long liveMeshAt;
    private int liveMeshProg, liveMeshTriVerts, liveMeshLineVerts;
    private final int[] liveMeshVbo = new int[6];
    private final java.util.List<java.nio.FloatBuffer> liveGroupBorn = new java.util.ArrayList<>();
    private boolean flyIn = LivePreview.FLY_IN;

    /** The live preview's photo choices (LivePreview). */
    final LivePreview livePreview = new LivePreview();
    private int dimProg, livePhotoProg;
    private final java.util.List<Integer> liveGroupTex = new java.util.ArrayList<>(), liveGroupVerts = new java.util.ArrayList<>();
    private final java.util.List<java.nio.FloatBuffer> liveGroupPos = new java.util.ArrayList<>(), liveGroupUv = new java.util.ArrayList<>();

    /** Live doorway outlines while scanning (rk 10 Nov): on/off, and when the last search ran. */
    // Off for now (rk 10 Nov 15:29): in the scans where ARCore stopped giving depth (13:52, 14:01, 15:23) a 1-5 s live
    // doorway search ran just before each stop (15:23: 4.8 s, then no new depth for 8 s); the outlines at Done stay.
    static volatile boolean LIVE_DOORS = false;
    /** The floor in a mesh: the fullest 2 cm height band in the lowest 0.7 m (above the lowest 1% of corners, which
     *  are noise), refined to the middle of the corners within 3 cm of it. */
    static float meshFloor(float[] vv, int nv) {
        float[] ys = new float[nv]; for (int i = 0; i < nv; i++) ys[i] = vv[i * 3 + 1];
        float[] srt = ys.clone(); java.util.Arrays.sort(srt);
        float lo = srt[Math.min(nv - 1, nv / 100)] - 0.1f;
        int[] h = new int[40];
        for (float y : ys) { int b = (int) ((y - lo) / 0.02f); if (b >= 0 && b < h.length) h[b]++; }
        int best = 0; for (int b = 1; b < h.length; b++) if (h[b] > h[best]) best = b;
        float c = lo + (best + 0.5f) * 0.02f; double sum = 0; int k = 0;
        for (float y : ys) if (Math.abs(y - c) < 0.03f) { sum += y; k++; }
        return k > 0 ? (float) (sum / k) : c;
    }

    private long liveDoorAt; private volatile long liveDoorMs; private volatile boolean liveDoorBusy;
    private volatile float[] lastLiveMesh; private volatile float lastLiveFloor;   // the search runs at most a third of the time (14:01: 1-2.4 s each, frame dips)
    /** Doorways being tracked during the scan: each with how many searches in a row found it and missed it. */
    private final java.util.ArrayList<float[]> liveTrack = new java.util.ArrayList<>();   // x0 z0 x1 z1 floorY width height sure hits misses shown

    /** Live-mesh thread, every ~2 s: the doorway search on the mesh so far; a doorway is shown after two searches agree
     *  (within 5 cm), moves smoothly, and goes only after 3 searches in a row miss it. */
    private void liveDoors(float[] vv, int nv, java.util.List<PhotoRoom.Keyframe> kfs, float camX, float camZ) {
        long t0 = System.currentTimeMillis();
        float[] walls = DoorwayFinder.wallsFromSurface(vv, nv);
        // only the 12 photos taken nearest to where the phone is: the see-through test looks at each photo per cell
        java.util.ArrayList<PhotoRoom.Keyframe> near12 = new java.util.ArrayList<>();
        for (PhotoRoom.Keyframe k : kfs) if (k.depth != null) near12.add(k);
        near12.sort((a, b) -> Float.compare((float) Math.hypot(a.pose[12] - camX, a.pose[14] - camZ), (float) Math.hypot(b.pose[12] - camX, b.pose[14] - camZ)));
        while (near12.size() > 12) near12.remove(near12.size() - 1);
        java.util.ArrayList<OutlineFill.Photo> phs = new java.util.ArrayList<>();
        for (PhotoRoom.Keyframe k : near12) {
            if (k.depth == null) continue;
            OutlineFill.Photo ph = new OutlineFill.Photo();
            ph.pose = k.pose; ph.fx = k.fx; ph.fy = k.fy; ph.cx = k.cx; ph.cy = k.cy; ph.w = (int) k.w; ph.h = (int) k.h;
            ph.depth = k.depth; ph.dw = k.dw; ph.dh = k.dh; ph.dfx = k.dfx; ph.dfy = k.dfy; ph.dcx = k.dcx; ph.dcy = k.dcy;
            phs.add(ph);
        }
        float[][] views = new float[phs.size()][];
        for (int i = 0; i < phs.size(); i++) views[i] = OutlineFill.invert(phs.get(i).pose);
        // The floor: during a scan neither the planner nor the room model is ready, and walkFloorY() was 0, 1.6 m above
        // the real floor, so every opening's height was wrong and nothing was found (14:58 scan; with the right floor the
        // saved live mesh gives the same 0.88 x 2.06 m doorway as the search at Done).
        float floor = roomReady ? room.floorY : planner.isReady() ? planner.floorY : meshFloor(vv, nv);
        StringBuilder why = new StringBuilder();
        java.util.List<DoorwayFinder.Doorway> ds = DoorwayFinder.find(vv, nv, walls, floor,
                (x, y, z) -> tsdf.seenThrough(x, y, z) || OutlineFill.judge(phs, views, new float[]{x, y, z}, null) == 1, why);   // the same evidence as at Done
        if (ds.isEmpty() && why.length() > 0) log("live doorways, left out:" + (why.length() > 300 ? why.substring(0, 300) : why));
        // the mesh the last live search saw, saved with the scan (live-mesh.bin) to find offline why live finds nothing
        // while the search at Done finds the doorways (13:41, 14:01 scans)
        lastLiveMesh = vv; lastLiveFloor = floor;
        synchronized (liveTrack) {
            for (float[] t : liveTrack) t[11] = 0;   // matched this time?
            for (DoorwayFinder.Doorway d : ds) {
                float cx = (d.x0 + d.x1) / 2, cz = (d.z0 + d.z1) / 2; float[] best = null; float bd = 0.3f;
                for (float[] t : liveTrack) { float dd = (float) Math.hypot((t[0] + t[2]) / 2 - cx, (t[1] + t[3]) / 2 - cz); if (dd < bd) { bd = dd; best = t; } }
                if (best == null) { liveTrack.add(new float[]{d.x0, d.z0, d.x1, d.z1, d.floorY, d.width, d.height, d.sure ? 1 : 0, 1, 0, 0, 1}); continue; }
                boolean agree = bd < 0.15f;   // the same opening again (sizes settle as more is scanned)
                float a = best[10] > 0 ? 0.3f : 1f;   // shown: move smoothly; not yet: take the new one
                float[] nw = {d.x0, d.z0, d.x1, d.z1, d.floorY, d.width, d.height};
                for (int k = 0; k < 7; k++) best[k] += (nw[k] - best[k]) * a;
                best[7] = d.sure ? 1 : best[7] > 0 && agree ? 1 : 0;
                best[8] = agree || best[10] > 0 ? best[8] + 1 : 1; best[9] = 0; best[11] = 1;
                if (best[8] >= 2) best[10] = 1;
            }
            for (int i = liveTrack.size() - 1; i >= 0; i--) { float[] t = liveTrack.get(i); if (t[11] == 0 && ++t[9] >= 3) liveTrack.remove(i); }
            java.util.ArrayList<DoorwayFinder.Doorway> show = new java.util.ArrayList<>();
            for (float[] t : liveTrack) if (t[10] > 0) {
                DoorwayFinder.Doorway d = new DoorwayFinder.Doorway();
                d.x0 = t[0]; d.z0 = t[1]; d.x1 = t[2]; d.z1 = t[3]; d.floorY = t[4]; d.width = t[5]; d.height = t[6]; d.sure = t[7] > 0;
                show.add(d);
            }
            activity.setDoorways(show);
        }
        perf.note("ms: live doorway search", System.currentTimeMillis() - t0);
        log(String.format(java.util.Locale.US, "live doorways: %d found, %d tracked, %d shown (%d ms)", ds.size(), liveTrack.size(), countShown(), System.currentTimeMillis() - t0));
    }

    private int countShown() { int n = 0; synchronized (liveTrack) { for (float[] t : liveTrack) if (t[10] > 0) n++; } return n; }

    /** GL thread: every ~1 s, copy the voxels near the phone and build their mesh on another thread. */
    private void maybeLiveMesh(Camera camera, long nowMs) {
        if (LIVE_POINTS && !LIVE_DOORS) return;   // nothing uses it then: the CPU goes to ARCore (its depth stopped after heavy work)
        if (liveMeshBusy || liveMeshNew != null || nowMs - liveMeshAt < LIVE_MESH_MS || tsdf.size() < 50) return; // the last one is uploaded before its buffers are reused
        // not while lost: the mesh is built around ARCore's position, which is wrong then, and the room vanished
        if (camera.getTrackingState() != TrackingState.TRACKING || needRealign) return;
        liveMeshAt = nowMs; liveMeshBusy = true;
        com.google.ar.core.Pose p = camera.getPose();
        final float px = p.tx(), py = p.ty(), pz = p.tz();
        float[] z = p.getZAxis();
        final float fx = -z[0], fy = -z[1], fz = -z[2];
        final int gen = surfGen;
        // The photos as they are now (their fields don't change after capture; the list does, on this thread).
        final java.util.List<PhotoRoom.Keyframe> kfs = new java.util.ArrayList<>(photos.keyframes);
        new Thread(() -> {
            try {
                // Up to 8 m (was 3.5: stepping back made the far parts vanish, rk 9 Nov).
                Tsdf near = tsdf.copyNear(px, py, pz, LIVE_MESH_RADIUS, liveScratch); // read while the GL thread keeps fusing: fine for a preview
                liveScratch = near; near.reuseBuffers = true;
                int nt = near.extractMesh();
                // The doorway search on its own thread: inline it held the live mesh for 7-15 s at a time once it could
                // find doors (rk 10 Nov 15:09: "at 3:30 it stopped adding new features": the mesh just wasn't redrawn).
                if (LIVE_DOORS && !liveDoorBusy && nowMs - liveDoorAt > Math.max(2000, 3 * liveDoorMs) && nt > 2000) {
                    liveDoorAt = nowMs; liveDoorBusy = true;
                    final int nv3 = nt * 9; final float[] vv = new float[nv3];
                    for (int i = 0; i < nv3; i++) vv[i] = near.meshPos.get(i);   // a copy: the mesh's buffers are reused
                    final float cpx = px, cpz = pz;
                    Thread dt = new Thread(() -> {
                        long ld0 = System.currentTimeMillis();
                        try { liveDoors(vv, nt * 3, kfs, cpx, cpz); } catch (Throwable e) { logError("live doorways", new RuntimeException(e)); }
                        finally { liveDoorMs = System.currentTimeMillis() - ld0; liveDoorBusy = false; }
                    }, "live-doors");
                    dt.setPriority(Thread.MIN_PRIORITY); dt.start();
                }
                LivePreview.Result r = livePreview.build(near.meshPos, near.meshConf, nt, kfs, px, py, pz, fx, fy, fz);
                if (gen == surfGen) liveMeshNew = new Object[]{r};
            } catch (Throwable e) { logError("live mesh", new RuntimeException(e)); }
            finally { liveMeshBusy = false; }
        }).start();
    }

    /**
     * The live preview (LivePreview): the camera dimmed, triangles with their photo drawn with it at full brightness,
     * the rest as light see-through polygons with a wireframe, orange until well seen, then white.
     */
    private void drawLiveMesh(float[] m) {
        Object[] nm = liveMeshNew;
        if (nm != null) {
            liveMeshNew = null;
            LivePreview.Result r = (LivePreview.Result) nm[0];
            if (liveMeshVbo[0] == 0) GLES20.glGenBuffers(6, liveMeshVbo, 0);
            // Never more bytes than the buffers hold (an upload past the end throws and kills the app).
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, liveMeshVbo[0]);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, Math.min(Math.max(1, r.restVerts) * 12, r.restPos.capacity() * 4), r.restPos, GLES20.GL_DYNAMIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, liveMeshVbo[1]);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, Math.min(Math.max(1, r.restVerts), r.restConf.capacity()), r.restConf, GLES20.GL_DYNAMIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, liveMeshVbo[2]);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, Math.min(Math.max(1, r.lineVerts) * 12, r.lines.capacity() * 4), r.lines, GLES20.GL_DYNAMIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, liveMeshVbo[3]);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, Math.min(Math.max(1, r.lineVerts), r.lineConf.capacity()), r.lineConf, GLES20.GL_DYNAMIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, liveMeshVbo[4]);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, Math.min(Math.max(1, r.restVerts) * 4, r.restBorn.capacity() * 4), r.restBorn, GLES20.GL_DYNAMIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, liveMeshVbo[5]);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, Math.min(Math.max(1, r.lineVerts) * 4, r.lineBorn.capacity() * 4), r.lineBorn, GLES20.GL_DYNAMIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
            liveMeshTriVerts = r.restVerts; liveMeshLineVerts = r.lineVerts;
            liveGroupTex.clear(); liveGroupTex.addAll(r.groupTex);
            liveGroupPos.clear(); liveGroupPos.addAll(r.groupPos);
            liveGroupUv.clear(); liveGroupUv.addAll(r.groupUv);
            liveGroupVerts.clear(); liveGroupVerts.addAll(r.groupVerts);
            liveGroupBorn.clear(); liveGroupBorn.addAll(r.groupBorn);
        }
        if (LivePreview.SHOW_CAMERA) drawLiveDim(1f - LivePreview.DIM);
        else drawZebra(1f); // no camera picture: only what was captured, on the zebra background
        if (liveMeshTriVerts == 0 && liveGroupTex.isEmpty()) return;
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        // Photo-filled triangles, opaque, with the photo the Photo view will use.
        if (!liveGroupTex.isEmpty()) {
            if (livePhotoProg == 0) {
                String fs = "precision mediump float; uniform sampler2D uTex; varying vec2 vUv;" +
                        "void main(){ gl_FragColor = vec4(texture2D(uTex, vUv).rgb, 1.0); }";
                try {
                    if (!flyIn) throw new RuntimeException("fly-in off");
                    livePhotoProg = program(
                        "uniform mat4 uMvp; uniform float uNow; attribute vec3 aPos; attribute vec2 aUv; attribute float aBorn; varying vec2 vUv;" +
                        FLY_GLSL + "void main(){ vec3 p = aPos; p.y -= 0.2 * (1.0 - flyEase(aBorn)); gl_Position = uMvp * vec4(p, 1.0); vUv = aUv; }", fs);
                } catch (RuntimeException e) {
                    if (flyIn) { log("fly-in shader failed, no animation: " + e.getMessage()); flyIn = false; }
                    livePhotoProg = program(
                        "uniform mat4 uMvp; attribute vec3 aPos; attribute vec2 aUv; varying vec2 vUv;" +
                        "void main(){ gl_Position = uMvp * vec4(aPos, 1.0); vUv = aUv; }", fs);
                }
            }
            GLES20.glUseProgram(livePhotoProg);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(livePhotoProg, "uMvp"), 1, false, m, 0);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(livePhotoProg, "uTex"), 0);
            int aPos = GLES20.glGetAttribLocation(livePhotoProg, "aPos"), aUv = GLES20.glGetAttribLocation(livePhotoProg, "aUv");
            int aBornP = GLES20.glGetAttribLocation(livePhotoProg, "aBorn");
            GLES20.glUniform1f(GLES20.glGetUniformLocation(livePhotoProg, "uNow"), LivePreview.clock());
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glEnableVertexAttribArray(aPos); GLES20.glEnableVertexAttribArray(aUv);
            if (aBornP >= 0) GLES20.glEnableVertexAttribArray(aBornP);
            for (int g = 0; g < liveGroupTex.size(); g++) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, liveGroupTex.get(g));
                GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, liveGroupPos.get(g));
                GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, liveGroupUv.get(g));
                if (aBornP >= 0 && g < liveGroupBorn.size()) GLES20.glVertexAttribPointer(aBornP, 1, GLES20.GL_FLOAT, false, 0, liveGroupBorn.get(g));
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, liveGroupVerts.get(g));
            }
            GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aUv);
            if (aBornP >= 0) GLES20.glDisableVertexAttribArray(aBornP);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
        if (liveMeshTriVerts == 0) { if (LivePreview.SHOW_CAMERA && OVERVIEW && overviewBlend > 0.5f) drawOverviewProjection(m); return; }
        if (liveMeshProg == 0) {
            try {
                if (!flyIn) throw new RuntimeException("fly-in off");
                liveMeshProg = program(
                    "uniform mat4 uMvp; uniform float uNow; attribute vec3 aPos; attribute float aConf; attribute float aBorn; varying float vConf; varying float vFade;" +
                    FLY_GLSL + "void main(){ float e = flyEase(aBorn); vec3 p = aPos; p.y -= 0.2 * (1.0 - e); gl_Position = uMvp * vec4(p, 1.0); vConf = aConf; vFade = e; }",
                    "precision mediump float; uniform vec4 uCol; uniform vec4 uNew; uniform float uConfirmed; varying float vConf; varying float vFade;" +
                    "void main(){ vec4 c = mix(uNew, uCol, smoothstep(0.15, uConfirmed, vConf)); gl_FragColor = vec4(c.rgb, c.a * vFade); }");
            } catch (RuntimeException e) {
                if (flyIn) { log("fly-in shader failed, no animation: " + e.getMessage()); flyIn = false; }
                liveMeshProg = program(
                    "uniform mat4 uMvp; attribute vec3 aPos; attribute float aConf; varying float vConf;" +
                    "void main(){ gl_Position = uMvp * vec4(aPos, 1.0); vConf = aConf; }",
                    "precision mediump float; uniform vec4 uCol; uniform vec4 uNew; uniform float uConfirmed; varying float vConf;" +
                    "void main(){ gl_FragColor = mix(uNew, uCol, smoothstep(0.15, uConfirmed, vConf)); }");
            }
        }
        GLES20.glUseProgram(liveMeshProg);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(liveMeshProg, "uMvp"), 1, false, m, 0);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(liveMeshProg, "uConfirmed"), LivePreview.CONFIRMED);
        int aPos = GLES20.glGetAttribLocation(liveMeshProg, "aPos"), aConf = GLES20.glGetAttribLocation(liveMeshProg, "aConf");
        int aBorn = GLES20.glGetAttribLocation(liveMeshProg, "aBorn");
        GLES20.glUniform1f(GLES20.glGetUniformLocation(liveMeshProg, "uNow"), LivePreview.clock());
        if (aBorn >= 0) GLES20.glEnableVertexAttribArray(aBorn);
        int uCol = GLES20.glGetUniformLocation(liveMeshProg, "uCol"), uNew = GLES20.glGetUniformLocation(liveMeshProg, "uNew");
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glEnableVertexAttribArray(aPos); GLES20.glEnableVertexAttribArray(aConf);
        // fill: writes depth so the wireframe behind a wall stays hidden
        GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL);
        GLES20.glPolygonOffset(1f, 1f);
        float[] sc = LivePreview.SOLID, tc = LivePreview.TENTATIVE, se = LivePreview.SOLID_EDGE, te = LivePreview.TENTATIVE_EDGE;
        GLES20.glUniform4f(uCol, sc[0], sc[1], sc[2], sc[3]);
        GLES20.glUniform4f(uNew, tc[0], tc[1], tc[2], tc[3]);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, liveMeshVbo[0]); GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, liveMeshVbo[1]); GLES20.glVertexAttribPointer(aConf, 1, GLES20.GL_UNSIGNED_BYTE, true, 0, 0);
        if (aBorn >= 0) { GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, liveMeshVbo[4]); GLES20.glVertexAttribPointer(aBorn, 1, GLES20.GL_FLOAT, false, 0, 0); }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, liveMeshTriVerts);
        GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL);
        // wireframe
        GLES20.glUniform4f(uCol, se[0], se[1], se[2], se[3]);
        GLES20.glUniform4f(uNew, te[0], te[1], te[2], te[3]);
        GLES20.glLineWidth(Math.max(1f, height / 1400f));
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, liveMeshVbo[2]); GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, liveMeshVbo[3]); GLES20.glVertexAttribPointer(aConf, 1, GLES20.GL_UNSIGNED_BYTE, true, 0, 0);
        if (aBorn >= 0) { GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, liveMeshVbo[5]); GLES20.glVertexAttribPointer(aBorn, 1, GLES20.GL_FLOAT, false, 0, 0); }
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, liveMeshLineVerts);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aConf);
        if (aBorn >= 0) GLES20.glDisableVertexAttribArray(aBorn);
        GLES20.glDisable(GLES20.GL_BLEND);
        if (LivePreview.SHOW_CAMERA && OVERVIEW && overviewBlend > 0.5f) drawOverviewProjection(m);
    }

    /** The fly-in's easing (rk 10 Nov): 0 when the surface appears, 1 after 0.4 s, easing out. */
    private static final String FLY_GLSL = "float flyEase(float born){ float t = clamp((uNow - born) / 0.35, 0.0, 1.0); return 1.0 - (1.0 - t) * (1.0 - t) * (1.0 - t); }";

    /** Screen corners (-1,-1), (1,-1), (-1,1) of the real camera's view in the camera texture, for the overview. */
    private final float[] ovCamUv = new float[6];
    private int ovProjProg;

    /**
     * The wide overview: the camera picture right now projected onto the live mesh from the phone's real view, so each
     * camera pixel lands on its own surface from the pulled-back viewpoint (rk 9 Nov: the flat picture at OV_WINDOW
     * showed things twice). Surfaces seen at a grazing angle are left out; hidden surfaces aren't tested yet.
     */
    private void drawOverviewProjection(float[] m) {
        if (ovProjProg == 0) ovProjProg = program(
                "uniform mat4 uMvp; attribute vec3 aPos; varying vec3 vWorld; void main(){ gl_Position = uMvp * vec4(aPos, 1.0); vWorld = aPos; }",
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision highp float; uniform samplerExternalOES uTex; uniform mat4 uReal; uniform vec2 uA0; uniform vec2 uAx; uniform vec2 uAy;" +
                "uniform float uAlpha; varying vec3 vWorld;" +
                "void main(){ vec4 c = uReal * vec4(vWorld, 1.0); if (c.w < 0.05) discard; vec2 n = c.xy / c.w;" +
                "  if (abs(n.x) > 1.0 || abs(n.y) > 1.0) discard;" +
                "  vec2 uv = uA0 + (n.x * 0.5 + 0.5) * uAx + (n.y * 0.5 + 0.5) * uAy;" +
                "  gl_FragColor = vec4(texture2D(uTex, uv).rgb, uAlpha); }");
        GLES20.glUseProgram(ovProjProg);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(ovProjProg, "uMvp"), 1, false, m, 0);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(ovProjProg, "uReal"), 1, false, realMvp, 0);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(ovProjProg, "uA0"), ovCamUv[0], ovCamUv[1]);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(ovProjProg, "uAx"), ovCamUv[2] - ovCamUv[0], ovCamUv[3] - ovCamUv[1]);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(ovProjProg, "uAy"), ovCamUv[4] - ovCamUv[0], ovCamUv[5] - ovCamUv[1]);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(ovProjProg, "uAlpha"), Math.min(1f, overviewBlend));
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, bgTex);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(ovProjProg, "uTex"), 0);
        int aPos = GLES20.glGetAttribLocation(ovProjProg, "aPos");
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL);
        GLES20.glPolygonOffset(-1f, -1f);
        GLES20.glEnableVertexAttribArray(aPos);
        for (int g = 0; g < liveGroupPos.size(); g++) {
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, liveGroupPos.get(g));
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, liveGroupVerts.get(g));
        }
        if (liveMeshTriVerts > 0) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, liveMeshVbo[0]);
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, liveMeshTriVerts);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        }
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL);
        GLES20.glDepthFunc(GLES20.GL_LESS);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
    }

    /** A black veil over the whole view (the live preview's dimmed camera). */
    private final java.nio.FloatBuffer dimQuad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
    private void drawLiveDim(float alpha) {
        if (alpha <= 0.01f) return;
        if (dimProg == 0) dimProg = program("attribute vec2 aPos; void main(){ gl_Position = vec4(aPos, 0.0, 1.0); }",
                "precision mediump float; uniform float uA; void main(){ gl_FragColor = vec4(0.0, 0.0, 0.0, uA); }");
        GLES20.glUseProgram(dimProg);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(dimProg, "uA"), alpha);
        int aPos = GLES20.glGetAttribLocation(dimProg, "aPos");
        dimQuad.position(0);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, dimQuad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDepthMask(true);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
    }

    /** Live during the scan: every captured spot as a disc over the camera, rimmed red (seen once) -> yellow -> green (solid). */
    static final boolean LIVE_DISCS = true;
    private int liveProg;

    private void drawLiveDiscs(float[] m) {
        if (count == 0) return;
        if (liveProg == 0) liveProg = program(
                "uniform mat4 uMvp; uniform float uSize; attribute vec4 aPos; attribute vec4 aColor; varying vec4 vColor;" +
                "void main(){ gl_Position = uMvp * aPos; float w = max(gl_Position.w, 0.05);" +
                "  if (aColor.a < 0.002) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); gl_PointSize = 1.0; vColor = aColor; return; }" +
                "  float solid = step(0.99, aColor.a);" + // done spots shrink to a faint dot so they stop hiding the camera
                "  float grow = solid > 0.5 ? 0.4 : mix(1.25, 0.7, aColor.a);" + // red discs start big and visibly shrink with every new look
                "  gl_PointSize = clamp(uSize / w * grow, 2.0, 64.0); vColor = aColor; }",
                "precision mediump float; varying vec4 vColor;" +
                "void main(){ vec2 d = gl_PointCoord * 2.0 - 1.0; float r2 = dot(d, d); if (r2 > 1.0) discard;" +
                "  float s = floor(vColor.a * 4.0) / 4.0;" + // 4 clear colour steps (red, orange, yellow, green) so progress pops
                "  if (s > 0.99) { gl_FragColor = vec4(0.15, 1.0, 0.35, 0.35); return; }" +
                "  vec3 heat = s < 0.5 ? mix(vec3(1.0, 0.15, 0.1), vec3(1.0, 0.85, 0.1), s * 2.0) : mix(vec3(1.0, 0.85, 0.1), vec3(0.15, 1.0, 0.35), s * 2.0 - 1.0);" +
                "  if (r2 > 0.55) { gl_FragColor = vec4(heat, 1.0); return; }" +
                "  gl_FragColor = vec4(mix(vColor.rgb, heat, 0.25), 0.8); }");
        GLES20.glUseProgram(liveProg);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(liveProg, "uMvp"), 1, false, m, 0);
        float pxPerMeter = proj[5] * height / 2f; // the camera's own focal length in pixels
        GLES20.glUniform1f(GLES20.glGetUniformLocation(liveProg, "uSize"), VOXEL * 1.6f * pxPerMeter);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        int aPos = GLES20.glGetAttribLocation(liveProg, "aPos");
        int aCol = GLES20.glGetAttribLocation(liveProg, "aColor");
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
        GLES20.glDisable(GLES20.GL_BLEND);
        // keep the view opaque for the compositor
        GLES20.glColorMask(false, false, false, true);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glColorMask(true, true, true, true);
        clearBg();
    }

    /** The scan screen's only feedback: one plain instruction and two progress bars, no buttons to press. */
    private com.google.ar.core.TrackingFailureReason lastFail = com.google.ar.core.TrackingFailureReason.NONE;
    private boolean tooFast, tooFar;         // with hysteresis, so the hint doesn't flicker
    private String shownHint; private int shownLevel; private long shownAt;
    private float roomShown;                 // the Room bar never goes down

    /** The scan screen's only feedback: one plain instruction and two progress bars, no buttons to press. */
    static final float SPOT_DONE = 0.85f; // "done" only when the middle of the screen is (nearly) all green
    /** Which way to turn to look where the phone last looked while it was lined up (rk 10 Nov 15:42: after a loss the
     *  matches only work on well-scanned surface, 80-94% there against 31-60% elsewhere): from the view shown now. */
    /** "" when the place is in view, else ": it's to your left" etc. */
    private String backToSuffix() {
        String b = backTo();
        if (b.startsWith("hold still") || b.startsWith("point at")) return "";
        if (b.contains("behind")) return ": it's behind you";
        if (b.startsWith("turn left")) return ": it's to your left";
        if (b.startsWith("turn right")) return ": it's to your right";
        if (b.startsWith("tilt up")) return ": it's above";
        return ": it's below";
    }

    private String backTo() {
        Anchors.Anchor sp = goal;
        if (!haveGoodCam && sp == null) return "point at an area you already scanned";
        float[] g = lastGoodCam;
        float tx = g[12] - g[8] * 1.5f, ty = g[13] - g[9] * 1.5f, tz = g[14] - g[10] * 1.5f;   // 1.5 m ahead of it
        if (sp != null) { tx = sp.tx; ty = sp.ty; tz = sp.tz; }   // the good spot instead (rk 11 Nov: not the plain wall that lost it)
        float[] c = new float[4]; Matrix.multiplyMV(c, 0, viewMvp, 0, new float[]{tx, ty, tz, 1}, 0);
        boolean behind = c[3] <= 0.05f;
        float nx = behind ? -Math.signum(c[0] == 0 ? 1 : c[0]) * 2 : c[0] / c[3], ny = behind ? 0 : c[1] / c[3];
        if (!behind && Math.abs(nx) < 0.35f && Math.abs(ny) < 0.35f) return "hold still here a moment";
        String h = Math.abs(nx) >= Math.abs(ny) || behind ? (nx < 0 ? "turn left" : "turn right") : (ny > 0 ? "tilt up" : "tilt down");
        return h + (behind ? " (it's behind you)" : "") + ", back to where you last scanned";
    }

    /** The lost-track instruction: at the good spot when there is one (its picture shows under the hint), else back
     *  where it was. A spot that hasn't worked in GIVE_UP_MS gives way to the next best. */
    private String lostHint() {
        Anchors.Anchor sp = goal;
        if (sp != null && System.currentTimeMillis() - goalSince > Anchors.GIVE_UP_MS) { pickGoal("next"); sp = goal; }
        return sp != null ? "Lost track. Stop and point at the spot in the picture" + backToSuffix()
                : "Lost track. Stop and point back at where you were" + backToSuffix();
    }

    private boolean notAdding; private long notAddingSince;
    private int agreeRun; private float[] agreePrev;

    private void postHud(TrackingState ts) {
        int level; String hint, sub = null;
        float d = detailHere;
        if (LIVE_MESH && d >= 0 && (livePreview.filledRoom > 0 || livePreview.filledInView > 0)) d = Math.min(d, livePreview.filledInView);
        tooFast = moveSpeed > (tooFast ? 0.45f : 0.6f) || turnSpeed > (tooFast ? 50f : 70f); // a slow walk (~0.4 m/s) is fine; quick turns lose ARCore too
        tooFar = !DEMO_FAR && farShare > (tooFar ? 0.45f : 0.6f); // demo: looking out over a hall is the point
        if (ts != TrackingState.TRACKING && everTracked) {
            // rk 10 Nov 13:45: tell the user to stay put and look back (the view keeps turning with the gyro)
            // A quiet pause (rk 10 Nov 15:58: never "lost", no red, the scan stays in view): one calm hint.
            // rk 10 Nov 16:45: an immediate, clear notice so the user knows exactly when to stop moving
            level = 3;   // amber
            hint = STOP_ON_LOSS ? STANDING_TEXT : lostHint();
        }
        else if (everTracked && lastNewDepthMs > 0 && System.currentTimeMillis() - lastNewDepthMs > 3000 && !needRealign) {
            // ARCore stopped giving new depth (15:23 scan: 8 s at the end, nothing added, nothing said); tracking itself
            // is fine, so no "Lost track": one calm line, and the session restarts by itself after 8-10 s
            level = 2;
            hint = "Depth paused: hold the phone still a moment, it restarts by itself";
        }
        else if (needRealign && everTracked) {
            // tracking again but not yet lined up with the scan: nothing is added (14:58 scan: "Good. Keep swaying" showed
            // for 24 s while nothing was being scanned)
            level = STOP_ON_LOSS ? 1 : 3;   // (without lost-track prompts: ARCore moved its map, it lines up again by itself)
            hint = STOP_ON_LOSS ? STANDING_TEXT : lostHint();
        }
        else if (ts != TrackingState.TRACKING) {
            level = 2;
            hint = lastFail == com.google.ar.core.TrackingFailureReason.INSUFFICIENT_FEATURES ? "Lost track: point at something with texture, not a plain wall"
                    : lastFail == com.google.ar.core.TrackingFailureReason.EXCESSIVE_MOTION ? "Lost track: moving too fast. Slow down"
                    : lastFail == com.google.ar.core.TrackingFailureReason.INSUFFICIENT_LIGHT ? "Lost track: too dark. Turn on a light"
                    : "Lost track: move slowly, pointing at the floor or furniture";
        }
        else if (d < 0 || count < 3000) { level = 1; hint = LIVE_POINTS ? "Walk slowly and sway the phone over the room: points appear as it's captured" : LIVE_MESH ? "Walls and objects get covered in a mesh as they're found. Walk slowly and sway the phone over bare or orange areas" : "Dots show progress: red = needs more, green = done. Walk slowly and sway the phone over red dots"; }
        else if (PLAIN_WARNING && System.currentTimeMillis() < plainWarnUntil) { level = 2; hint = "Plain wall: keep the floor line or a corner in view"; }
        else if (photoWanted && d >= 0.35f) { level = 1; hint = "Hold still a moment… taking a photo"; }
        else if (tooFar && d < 0.7f) { level = 2; hint = "Too far: walls bend past 2 m. Step closer (1 to 2 m)"; }
        else if (tooFast) { level = 2; hint = "Too fast: slow down, or the phone loses its place"; }
        else if (System.currentTimeMillis() < plainHintUntil) { level = 1; hint = "Plain wall: keep an edge or some detail in view"; }   // (was only on unfinished spots; rk 10 Nov 15:39: lost track after a fast scan)
        else if (d < 0.35f) { level = 1; hint = LIVE_POINTS ? "Sway the phone slowly over this spot" : LIVE_MESH ? "Sway the phone slowly over this spot until the mesh covers it in white" : "Sway the phone slowly over this spot until the dots turn green"; }
        else if (d < SPOT_DONE) { level = 1; hint = LIVE_MESH ? "Good. Keep swaying gently here a little longer" : "Good. Keep swaying gently here until the middle is green"; }
        else { level = 0; hint = LIVE_POINTS ? "This spot is done. Move on" : LIVE_MESH ? "This spot is done. Move on to the next orange area" : "This spot is done. Move on to the next red area"; }
        if (System.currentTimeMillis() - clearedAtMs < 3000) { hint = "Cleared, scan that spot again"; level = 1; }
        if (memoryFull || count >= MAX_POINTS) { hint = "The scan is full: tap Done to keep it"; level = 1; }   // (11 Nov: out of memory on a 512 MB phone)
        // The standing line (rk 11 Nov 21:42: "a warning always on screen ... makes the promise more honest"): it replaces
        // the ordinary guidance; anything more urgent (plain wall, lost track, scan full) still takes its place.
        else if (STANDING_NOTE && level <= 1 && count >= 3000 && System.currentTimeMillis() - clearedAtMs >= 3000) { hint = STANDING_TEXT; level = 1; }
        if (improveDir != null && contAuto) sub = improveT != null ? "✓ Saved room recognised. Keep scanning, the saved part is shown in blue"
                : contBase == null ? "Loading the saved scan…" : "Point at a part you scanned before so it can find its place" + (contScore > 0 ? String.format(java.util.Locale.US, " (best match %.0f%%)", contScore * 100) : "");
        else if (improveDir != null) sub = improveCorners.size() < 2 ? "Saved scan has no corners to match: this will be kept as a new scan" : "Tap 2 room corners (where floor meets walls) to match the saved scan";
        else if (photoJustTaken()) sub = "📷 Photo " + photos.keyframes.size() + " taken, keep swaying";
        else if (guideText != null && level == 0) sub = guideText;
        // keep a hint up for 1.5 s, unless something more urgent replaces it
        long now = System.currentTimeMillis();
        boolean photoHint = photoWanted || "Hold still a moment… taking a photo".equals(shownHint);
        if (shownHint != null && !photoHint && !hint.equals(shownHint) && now - shownAt < 1500 && !(level >= 2 && shownLevel < level) && shownLevel != 3) { hint = shownHint; level = shownLevel; }   // (the lost notice comes and goes at once)
        else if (!hint.equals(shownHint)) { shownHint = hint; shownLevel = level; shownAt = now; }
        if (++hudCalls % 12 == 1) { // recount every ~2 s (points get merged, dropped and loaded in other places)
            int n = count, sc = 0, live = 0;
            for (int i = 0; i < n; i++) { if ((col.get(i * 4 + 3) & 0xFF) == 255) sc++; if (obs[i] != 0) live++; }
            solidCount = sc; liveDots = live;   // dots really in use: merged, erased and replaced ones don't count (rk 23:35)
        }
        float room = count > 0 ? Math.min(1f, (float) solidCount / count) : 0f;
        // What you get is what you see (LivePreview): the bars count surface that has its photo, not only its shape.
        if (LIVE_MESH && (livePreview.filledRoom > 0 || livePreview.filledInView > 0)) room *= livePreview.filledRoom;
        roomShown = Math.max(roomShown, room);
        boolean lostNow = everTracked && (ts != TrackingState.TRACKING || needRealign);
        boolean stalled = everTracked && lastNewDepthMs > 0 && System.currentTimeMillis() - lastNewDepthMs > 3000;
        if ((lostNow || stalled) != notAdding) {
            notAdding = lostNow || stalled;
            long nowMs = System.currentTimeMillis();
            if (notAdding && !lostNow) notAddingSince = lastNewDepthMs + 3000;   // a depth stall: the change is when 3 s ran out
            // a stale change time (20:25 scan logged "101435 ms": the time of an earlier pose jump, kept since then)
            boolean known = nowMs - notAddingSince < 5000;
            if (!STOP_ON_LOSS) activity.haptic(notAdding);
            log(notAdding ? (known ? String.format(java.util.Locale.US, "lost-track notice shown %d ms after the change", nowMs - notAddingSince)
                    : "lost-track notice shown (" + (lostNow ? "re-aligning" : "no new depth") + "; when it changed isn't known)") : "scanning again: notice off");
            notAddingSince = nowMs;   // used once
        }
        activity.updateHud(hint, sub, level, lostNow ? -1f : Math.max(0f, d), roomShown, count, (int) (scanMs / 1000));
    }

    private String detailLine() {
        float d = detailHere;
        if (d < 0) return "Detail here: point at something";
        int pct = Math.round(d * 100);
        return pct >= 85 ? "🟢 Detail here: " + pct + "% solid, move on" : pct >= 35 ? "🟡 Detail here: " + pct + "% solid, keep swaying here" : "🔴 Detail here: " + pct + "% solid, stay here and sway gently";
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
        // "Nothing here" behind the room (not in the clean thumbnail frame), so holes can't pass for black things.
        if (!photoThumbWanted) drawZebra(1f);
        uploadNew();
        uploadSurfels();
        float aspect = (float) width / height;
        Matrix.perspectiveM(proj, 0, walking ? walkFov : 60f, aspect, walking ? 0.05f : 0.02f, 500f);
        float p = topView ? 1.5697f : pitch;
        float y = topView ? 0f : yaw;
        float ex = target[0] + dist * (float) (Math.cos(p) * Math.sin(y));
        float ey = target[1] + dist * (float) Math.sin(p);
        float ez = target[2] + dist * (float) (Math.cos(p) * Math.cos(y));
        if (walking) {
            // Held ▲/▼: about 1.2 m/s, level, in the look direction.
            long now = System.nanoTime();
            float dt = walkLast == 0 ? 0 : Math.min(0.1f, (now - walkLast) / 1e9f);
            walkLast = now;
            float yawE = walkYawNow(), pitchE = walkPitchNow();
            float fx = (float) -Math.sin(yawE), fz = (float) -Math.cos(yawE);
            eye[0] += fx * walkMove * 1.2f * dt; eye[2] += fz * walkMove * 1.2f * dt;
            eye[0] += -fz * walkStrafe * 1.0f * dt; eye[2] += fx * walkStrafe * 1.0f * dt;   // sideways: right of the look direction
            float cp = (float) Math.cos(pitchE), sp = (float) Math.sin(pitchE);
            ex = eye[0]; ey = eye[1]; ez = eye[2];
            Matrix.setLookAtM(view, 0, ex, ey, ez, ex + fx * cp, ey + sp, ez + fz * cp, 0, 1, 0);
        } else if (topView) Matrix.setLookAtM(view, 0, target[0], target[1] + dist, target[2], target[0], target[1], target[2], 0, 0, -1);
        else Matrix.setLookAtM(view, 0, ex, ey, ez, target[0], target[1], target[2], 0, 1, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0);
        updateCutaway(ex, ez, topView);
        if (SHOW_OBJECTS) updateHideList(); else hideN = 0;
        hideN = objects.addHides(hideA, hideB, hideN);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        boolean simple = viewMode == 0 && roomReady && room.hasLayout;
        if (planningPreview && roomReady) {
            drawRoom(mvp, false);
        } else if (simple) {
            if (photos.ready) photos.draw(mvp, cutActive, roomCenter, cutDirX, cutDirZ, cutD, cutDir2X, cutDir2Z, cutD2, cutFloor, cutCeil);
        } else if ((style == 0 || lighting.enabled) && photos.ready && meshTris > 0)
            // The photo room already has its ceiling left out (see buildPhotos), like the iPhone's: no height cut, so
            // the tops of the walls stay. The Cut walls tool still cuts.
            photos.draw(mvp, cutaway && cutActive, roomCenter, cutDirX, cutDirZ, cutD, cutDir2X, cutDir2Z, cutD2, cutFloor, cutCeil);
        else if (style == 3 && !lighting.enabled && meshTris > 0) drawBareMesh(mvp);
        else if ((style == 1 || lighting.enabled) && surfCount > 0 && !depthRunning) drawSurfels(mvp);
        else { drawFilledWall(mvp); drawPoints(mvp, roomReady ? 0.9f : 1f); if (FLAT_TILES) flatTiles.draw(mvp); }
        // A thumbnail frame shows just the room: no labels, markers or props.
        boolean clean = photoThumbWanted && style == 2 && !walking && count > 0 && currentDir != null;
        if (!clean) {
            objects.room(pos, col, count, roomReady ? room.floorY : planner.isReady() ? planner.floorY : boxMinY);
            objects.draw(mvp, height, proj[5] * height / 2f);
        }
        if (clean) { }
        else if (lighting.enabled) { lighting.camX = ex; lighting.camY = ey; lighting.camZ = ez; lighting.haveCam = !topView; drawProps(mvp); }
        else { drawProps(mvp); if (roomReady && overlays && !planningPreview) drawRoom(mvp, simple); }
        if (viewing && roomReady) { drawSelectedRoom(mvp); drawWallLines(mvp); }
        cutActive = false;
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        if (clean) { System.arraycopy(mvp, 0, viewMvp, 0, 16); photoThumbWanted = false; savePhotoThumb(); return; }
        if (overlays) drawPath(mvp);
        drawOutlets(mvp);
        drawInterview(mvp);
        System.arraycopy(mvp, 0, viewMvp, 0, 16);
        if (lighting.enabled) drawLightGizmos(mvp);
        else if (plan != null && overlays) drawPlan(mvp, plan);
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
            } else if (sf.kind == RoomModel.Surface.Kind.TABLE) { if (planningPreview || claudeObjs.has()) continue; fill = TABLE_COL; } // Claude's objects replace the plane guesses
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
        if(planningPreview) {
            java.util.List<RoomModel.Box> visible = new java.util.ArrayList<>();
            for(RoomEdits.Detected d:roomEdits.visible()) visible.add(d.box());
            drawObjects(m, visible, false);
            RoomEdits.Detected selected = roomEdits.find(roomEdits.selectedId);
            if(selected != null && !roomEdits.hidden.contains(selected.id)) planDraw(m, objEdges(selected.box()), GLES20.GL_LINES, SEL_COL, 1);
        } else if (SHOW_OBJECTS) drawObjects(m, room.boxes, false);
        if(!planningPreview) cutouts.draw(m);
        if(!planningPreview) for (Cutouts.Cutout c : cutouts.snapshot())
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
        // a program that doesn't link draws nothing, silently (a first S23+ result showed an empty Mesh view, 11 Nov):
        // remember it for the scan log
        int[] ok = new int[1]; GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) linkErrors.add(GLES20.glGetProgramInfoLog(p) + " | " + vs.substring(0, Math.min(80, vs.length())));
        return p;
    }
    static final java.util.List<String> linkErrors = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

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

    private static float liftG(float c, float gain) {
        float x = Math.min(1f, Math.max(0f, c) / 255f);
        x = x * gain / (1f + (gain - 1f) * x);
        return x * 255f;
    }

    private static int clampI(int v) { return Math.max(0, Math.min(255, v)); }

    /** Open-addressing hash map long -> int (voxel key -> point index). */
    static final class LongIntMap {
        private static final long EMPTY = Long.MIN_VALUE;
        private final long[] keys;
        private final int[] vals;
        private final int mask;

        long heapBytes() { return 12L * keys.length; }

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
