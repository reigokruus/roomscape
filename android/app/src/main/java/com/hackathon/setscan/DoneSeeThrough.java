package com.hackathon.setscan;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.util.ArrayList;

/** Done-time see-through pass over the saved points (rk 13 Nov 14:44: a cluster under the table was "also visible after
 *  Done in the points view"). On the 14:41 scan it was the desk top as placed early in the scan (slow tracking drift):
 *  seen at its depth early, seen clearly through from under the table later. This replays the scan's own rebuild frames
 *  (rebuild-frames/: NNNNN.depth raw mm + confidence, frames.csv with each frame's corrected camera -> world pose and the
 *  depth intrinsics) over the points that are left at Done, with the live rule's centre ray: per frame, the depth at the
 *  point's own pixel (confidence MIN_CONF or more, no depth edge there) clearly beyond it (max(10 cm, 8%)) counts +1,
 *  at its depth -1 (not below 0); a point at NET_DROP is dropped. Pure Java (no Android), so it runs on a Mac too
 *  (main below) against a copied scan folder. Bounded by a time budget: frames are taken newest first (the later views
 *  are the ones that see past the drifted copies) and the pass stops when the budget is used. */
final class DoneSeeThrough {
    static final int MIN_CONF = 204;    // 80%, the live painting threshold
    static final int NET_DROP = 10;

    /** Stats of the last run: frames used, frames available, points tested, points dropped, ms (read / test / all),
     *  threads, and whether the budget stopped it. */
    static final class Stats { int framesUsed, frames, tested, dropped, threads; long ms, readMs, testMs; boolean budgetHit; }

    private static final class Frame { int idx; float[] m = new float[16]; float fx, fy, cx, cy; int w, h; short[] d; byte[] c; }

    /** Which of the n points (x, y, z in xyz) to drop; null if there are no usable frames. The same result as testing
     *  every point against every frame (13 Nov, on the Mac: identical dropped sets on two scans), but: each depth file is
     *  read in one go; the points are split over the cores; a point stops being tested once it is dropped or can no
     *  longer reach NET_DROP with the frames left. The budget is a safety cap (points left untested are kept). */
    static boolean[] drop(float[] xyz, int n, File framesDir, long budgetMs, Stats st) {
        long t0 = System.currentTimeMillis();
        ArrayList<Frame> all = readFrames(new File(framesDir, "frames.csv"));
        st.frames = all.size(); st.tested = n;
        // read every depth frame once (newest first), whole files
        final ArrayList<Frame> frames = new ArrayList<>();
        for (int fi = all.size() - 1; fi >= 0; fi--) {
            Frame f = all.get(fi);
            byte[] b;
            try { b = readAll(new File(framesDir, String.format(java.util.Locale.US, "%05d.depth", f.idx))); } catch (IOException e) { continue; }
            if (b.length < 8) continue;
            int w = ((b[0] & 255) << 24) | ((b[1] & 255) << 16) | ((b[2] & 255) << 8) | (b[3] & 255);
            int h = ((b[4] & 255) << 24) | ((b[5] & 255) << 16) | ((b[6] & 255) << 8) | (b[7] & 255);
            if (w != f.w || h != f.h || w <= 2 || h <= 2 || b.length < 8 + 3 * w * h) continue;
            f.d = new short[w * h]; f.c = new byte[w * h];
            for (int i = 0, o = 8; i < w * h; i++, o += 2) f.d[i] = (short) (((b[o] & 255) << 8) | (b[o + 1] & 255));
            System.arraycopy(b, 8 + 2 * w * h, f.c, 0, w * h);
            frames.add(f);
        }
        st.readMs = System.currentTimeMillis() - t0;
        st.framesUsed = frames.size();
        if (frames.isEmpty() || n == 0) { st.ms = System.currentTimeMillis() - t0; return null; }
        final boolean[] drop = new boolean[n];
        final int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        st.threads = threads;
        final long deadline = t0 + budgetMs;
        final int[] dropped = new int[threads];
        final boolean[] hit = new boolean[threads];
        Thread[] ts = new Thread[threads];
        long t1 = System.currentTimeMillis();
        for (int ti = 0; ti < threads; ti++) {
            final int id = ti, from = (int) ((long) n * ti / threads), to = (int) ((long) n * (ti + 1) / threads);
            ts[ti] = new Thread(() -> {
                int nf = frames.size();
                for (int i = from; i < to; i++) {
                    if (((i - from) & 4095) == 0 && System.currentTimeMillis() > deadline) { hit[id] = true; return; }
                    float wx = xyz[i * 3], wy = xyz[i * 3 + 1], wz = xyz[i * 3 + 2];
                    int net = 0;
                    for (int fi = 0; fi < nf; fi++) {
                        if (net + (nf - fi) < NET_DROP) break;   // can't reach the drop any more
                        Frame f = frames.get(fi);
                        float[] m = f.m;
                        float px = wx - m[12], py = wy - m[13], pz = wz - m[14];
                        float xc = m[0] * px + m[1] * py + m[2] * pz, yc = m[4] * px + m[5] * py + m[6] * pz, zc = m[8] * px + m[9] * py + m[10] * pz;
                        float z = -zc;
                        if (z < 0.25f || z > 3.5f) continue;
                        int u = Math.round(f.fx * xc / z + f.cx), v = Math.round(f.cy - f.fy * yc / z);
                        int w = f.w;
                        if (u < 1 || v < 1 || u >= w - 1 || v >= f.h - 1) continue;
                        int p = v * w + u;
                        short[] d = f.d;
                        int mm = d[p] & 0xFFFF;
                        if (mm < 200 || mm > 4000 || (f.c[p] & 0xFF) < MIN_CONF) continue;
                        int tol = mm / 25 + 20;   // no depth edge at the point's own pixel
                        if (Math.abs((d[p - 1] & 0xFFFF) - mm) > tol || Math.abs((d[p + 1] & 0xFFFF) - mm) > tol
                                || Math.abs((d[p - w] & 0xFFFF) - mm) > tol || Math.abs((d[p + w] & 0xFFFF) - mm) > tol) continue;
                        float lim = z + Math.max(0.10f, 0.08f * z);
                        if (mm * 0.001f > lim) {
                            if (++net >= NET_DROP) { drop[i] = true; dropped[id]++; break; }
                        } else if (net > 0) net--;
                    }
                }
            }, "done-see-through-" + ti);
            ts[ti].start();
        }
        for (Thread t : ts) { try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
        for (int ti = 0; ti < threads; ti++) { st.dropped += dropped[ti]; st.budgetHit |= hit[ti]; }
        st.testMs = System.currentTimeMillis() - t1;
        st.ms = System.currentTimeMillis() - t0;
        return drop;
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int o = 0;
            while (o < b.length) { int r = in.read(b, o, b.length - o); if (r < 0) break; o += r; }
            return o == b.length ? b : java.util.Arrays.copyOf(b, o);
        }
    }

    private static ArrayList<Frame> readFrames(File csv) {
        ArrayList<Frame> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(csv))) {
            for (String line; (line = r.readLine()) != null; ) {
                String[] s = line.split(",");
                if (s.length < 30) continue;
                Frame f = new Frame();
                f.idx = Integer.parseInt(s[0].trim());
                float[] p = new float[16];
                for (int k = 0; k < 16; k++) p[k] = Float.parseFloat(s[2 + k]);
                // world -> camera for a rigid camera -> world pose (column-major): rows of the transposed rotation, and
                // the camera position in 12..14
                f.m[0] = p[0]; f.m[1] = p[1]; f.m[2] = p[2];
                f.m[4] = p[4]; f.m[5] = p[5]; f.m[6] = p[6];
                f.m[8] = p[8]; f.m[9] = p[9]; f.m[10] = p[10];
                f.m[12] = p[12]; f.m[13] = p[13]; f.m[14] = p[14];
                f.fx = Float.parseFloat(s[24]); f.fy = Float.parseFloat(s[25]); f.cx = Float.parseFloat(s[26]); f.cy = Float.parseFloat(s[27]);
                f.w = Math.round(Float.parseFloat(s[28])); f.h = Math.round(Float.parseFloat(s[29]));
                out.add(f);
            }
        } catch (IOException | NumberFormatException e) { /* no frames: nothing to test against */ }
        return out;
    }

    /** Off the phone: java DoneSeeThrough.java <scan folder> [budget ms]. Reads points.bin (int count, then x y z floats +
     *  3 colour bytes, big-endian) and rebuild-frames/, prints the stats and writes drop.idx (dropped point indices). */
    public static void main(String[] a) throws IOException {
        File dir = new File(a[0]);
        long budget = a.length > 1 ? Long.parseLong(a[1]) : 2000;
        float[] xyz; int n;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(new File(dir, "points.bin"))))) {
            n = in.readInt(); xyz = new float[n * 3];
            for (int i = 0; i < n; i++) { xyz[i * 3] = in.readFloat(); xyz[i * 3 + 1] = in.readFloat(); xyz[i * 3 + 2] = in.readFloat(); in.skipBytes(3); }
        }
        Stats st = new Stats();
        boolean[] drop = drop(xyz, n, new File(dir, "rebuild-frames"), budget, st);
        System.out.printf(java.util.Locale.US, "Done see-through: tested %d, dropped %d in %d ms (read %d ms, test %d ms on %d threads%s; %d of %d frames)%n",
                st.tested, st.dropped, st.ms, st.readMs, st.testMs, st.threads, st.budgetHit ? ", budget hit" : "", st.framesUsed, st.frames);
        if (drop != null && a.length > 2) {
            try (java.io.PrintWriter w = new java.io.PrintWriter(a[2])) { for (int i = 0; i < n; i++) if (drop[i]) w.println(i); }
        }
    }
}
