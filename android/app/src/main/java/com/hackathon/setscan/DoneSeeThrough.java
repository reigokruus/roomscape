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

    /** Stats of the last run: frames used, frames available, points tested, points dropped, ms. */
    static final class Stats { int framesUsed, frames, tested, dropped; long ms; }

    private static final class Frame { int idx; float[] m = new float[16]; float fx, fy, cx, cy; int w, h; }

    /** Which of the n points (x, y, z in xyz) to drop; null if there are no usable frames. */
    static boolean[] drop(float[] xyz, int n, File framesDir, long budgetMs, Stats st) {
        long t0 = System.currentTimeMillis();
        ArrayList<Frame> frames = readFrames(new File(framesDir, "frames.csv"));
        st.frames = frames.size(); st.tested = n;
        if (frames.isEmpty() || n == 0) { st.ms = System.currentTimeMillis() - t0; return null; }
        byte[] net = new byte[n];
        boolean[] drop = new boolean[n];
        short[] d = null; byte[] c = null;
        for (int fi = frames.size() - 1; fi >= 0; fi--) {   // newest first
            if (System.currentTimeMillis() - t0 > budgetMs) break;
            Frame f = frames.get(fi);
            File df = new File(framesDir, String.format(java.util.Locale.US, "%05d.depth", f.idx));
            int w, h;
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(df), 1 << 16))) {
                w = in.readInt(); h = in.readInt();
                if (w != f.w || h != f.h || w <= 2 || h <= 2) continue;
                if (d == null || d.length != w * h) { d = new short[w * h]; c = new byte[w * h]; }
                for (int i = 0; i < w * h; i++) d[i] = in.readShort();
                in.readFully(c);
            } catch (IOException e) { continue; }
            // world -> camera: the inverse of the rigid camera -> world pose (column-major)
            float[] m = f.m;
            float r00 = m[0], r10 = m[1], r20 = m[2], r01 = m[4], r11 = m[5], r21 = m[6], r02 = m[8], r12 = m[9], r22 = m[10];
            float tx = m[12], ty = m[13], tz = m[14];
            for (int i = 0; i < n; i++) {
                if (drop[i]) continue;
                float px = xyz[i * 3] - tx, py = xyz[i * 3 + 1] - ty, pz = xyz[i * 3 + 2] - tz;
                float xc = r00 * px + r10 * py + r20 * pz, yc = r01 * px + r11 * py + r21 * pz, zc = r02 * px + r12 * py + r22 * pz;
                float z = -zc;
                if (z < 0.25f || z > 3.5f) continue;
                int u = Math.round(f.fx * xc / z + f.cx), v = Math.round(f.cy - f.fy * yc / z);
                if (u < 1 || v < 1 || u >= w - 1 || v >= h - 1) continue;
                int p = v * w + u;
                int mm = d[p] & 0xFFFF;
                if (mm < 200 || mm > 4000 || (c[p] & 0xFF) < MIN_CONF) continue;
                int tol = mm / 25 + 20;   // no depth edge at the point's own pixel
                if (Math.abs((d[p - 1] & 0xFFFF) - mm) > tol || Math.abs((d[p + 1] & 0xFFFF) - mm) > tol
                        || Math.abs((d[p - w] & 0xFFFF) - mm) > tol || Math.abs((d[p + w] & 0xFFFF) - mm) > tol) continue;
                float lim = z + Math.max(0.10f, 0.08f * z);
                if (mm * 0.001f > lim) {
                    int t = net[i] + 1;
                    if (t >= NET_DROP) { drop[i] = true; st.dropped++; } else net[i] = (byte) t;
                } else if (net[i] > 0) net[i]--;
            }
            st.framesUsed++;
        }
        st.ms = System.currentTimeMillis() - t0;
        return drop;
    }

    private static ArrayList<Frame> readFrames(File csv) {
        ArrayList<Frame> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(csv))) {
            for (String line; (line = r.readLine()) != null; ) {
                String[] s = line.split(",");
                if (s.length < 30) continue;
                Frame f = new Frame();
                f.idx = Integer.parseInt(s[0].trim());
                for (int k = 0; k < 16; k++) f.m[k] = Float.parseFloat(s[2 + k]);
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
        System.out.printf(java.util.Locale.US, "Done see-through: tested %d, dropped %d in %d ms (%d of %d frames)%n", st.tested, st.dropped, st.ms, st.framesUsed, st.frames);
        if (drop != null && a.length > 2) {
            try (java.io.PrintWriter w = new java.io.PrintWriter(a[2])) { for (int i = 0; i < n; i++) if (drop[i]) w.println(i); }
        }
    }
}
