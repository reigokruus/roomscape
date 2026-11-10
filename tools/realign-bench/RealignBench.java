package com.hackathon.setscan;

import java.io.*;
import java.util.*;

/** Times Realign.solve on a saved scan: a view-sized patch of its own points, moved by a known shift and turn, is
 *  lined up again from the identity (as after a loss). Prints time per solve and the recovered correction. */
public class RealignBench {
    public static void main(String[] a) throws Exception {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(new File(a[0], "points.bin"))));
        int n = in.readInt(); float[] xyz = new float[n * 3];
        for (int i = 0; i < n; i++) { xyz[i * 3] = in.readFloat(); xyz[i * 3 + 1] = in.readFloat(); xyz[i * 3 + 2] = in.readFloat(); in.readByte(); in.readByte(); in.readByte(); }
        long t0 = System.nanoTime();
        Realign r = new Realign(xyz, n);
        System.out.printf(Locale.US, "%s: %d points, model built in %.0f ms%n", new File(a[0]).getName(), n, (System.nanoTime() - t0) / 1e6);
        Random rnd = new Random(1);
        double total = 0; int runs = 0, ok = 0;
        for (int trial = 0; trial < 6; trial++) {
            int c = rnd.nextInt(n); float cx = xyz[c * 3], cy = xyz[c * 3 + 1], cz = xyz[c * 3 + 2];
            ArrayList<float[]> pts = new ArrayList<>();
            for (int i = 0; i < n; i++) { float dx = xyz[i * 3] - cx, dz = xyz[i * 3 + 2] - cz; if (dx * dx + dz * dz < 1.5f * 1.5f) pts.add(new float[]{xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2]}); }
            if (pts.size() < 2000) continue;
            Collections.shuffle(pts, rnd); List<float[]> s = new ArrayList<>(pts.subList(0, 1200));
            // move the patch by 0.15 m and 4 degrees: the correction must undo it
            float ang = (float) Math.toRadians(4), ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
            for (float[] p : s) { float x = p[0] - cx, z = p[2] - cz; p[0] = cx + ca * x - sa * z + 0.15f; p[2] = cz + sa * x + ca * z - 0.05f; }
            long t1 = System.nanoTime();
            Realign.Fit f = r.solve(s, Realign.identity());
            double ms = (System.nanoTime() - t1) / 1e6; total += ms; runs++;
            // check: the corrected patch centre lands back near (cx, cz)
            float px = cx + 0.15f, pz = cz - 0.05f;
            float qx = f.fix[0] * px + f.fix[8] * pz + f.fix[12], qz = f.fix[2] * px + f.fix[10] * pz + f.fix[14];
            float err = (float) Math.hypot(qx - cx, qz - cz), deg = (float) Math.toDegrees(Math.atan2(f.fix[2], f.fix[0]));
            if (err < 0.03f && Math.abs(deg + 4) < 1) ok++;
            System.out.printf(Locale.US, "  trial %d: %.0f ms, %d%% on the scan, median %.1f cm, back within %.1f cm, turn %.1f deg (should be -4)%n", trial, ms, Math.round(f.inliers * 100), f.median * 100, err * 100, deg);
        }
        System.out.printf(Locale.US, "  mean %.0f ms per solve over %d trials, %d recovered%n", total / Math.max(1, runs), runs, ok);
    }
}
