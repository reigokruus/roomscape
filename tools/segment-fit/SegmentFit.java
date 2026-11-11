package com.hackathon.setscan;

import java.io.*;
import java.util.*;
import java.util.regex.*;

/**
 * Offline check for "carry on after a loss": can a whole segment be fitted onto the rest of the scan afterwards?
 * Segments the live re-align placed have a known answer: the correction it chose (scan-log "back on track ...
 * correction x, y, z m, deg; ... segment N"). Undo it back to where "carry on" would have left the segment (the
 * correction from before the loss), fit the whole segment onto the earlier segments (Realign: ICP from there, and the
 * full search), and compare with the live answer. A user-chosen "new part" has no answer: just the fit is shown.
 *
 *   javac -d /tmp/sf android/app/src/main/java/com/hackathon/setscan/Realign.java tools/segment-fit/SegmentFit.java
 *   java -cp /tmp/sf com.hackathon.setscan.SegmentFit <scan folder>...
 */
public class SegmentFit {
    static final Pattern BACK = Pattern.compile("back on track after [\\d.]+ s: correction (-?[\\d.]+), (-?[\\d.]+), (-?[\\d.]+) m, (-?[\\d.]+) deg;.*segment (\\d+)");

    public static void main(String[] a) throws Exception { for (String s : a) run(new File(s)); }

    static float[] fix(float x, float y, float z, float deg) {
        float[] r = Realign.identity(); double t = Math.toRadians(deg); float c = (float) Math.cos(t), s = (float) Math.sin(t);
        r[0] = c; r[8] = -s; r[2] = s; r[10] = c; r[12] = x; r[13] = y; r[14] = z; return r;
    }
    static float[] mul(float[] a, float[] b) { float[] r = new float[16]; for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) { float s = 0; for (int k = 0; k < 4; k++) s += a[k * 4 + j] * b[i * 4 + k]; r[i * 4 + j] = s; } return r; }
    static float[] inv(float[] m) {   // rigid: R^T, -R^T t
        float[] r = Realign.identity();
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) r[j * 4 + i] = m[i * 4 + j];
        for (int i = 0; i < 3; i++) r[12 + i] = -(r[i] * m[12] + r[4 + i] * m[13] + r[8 + i] * m[14]);
        return r;
    }
    static float[] apply(float[] m, float x, float y, float z) { return new float[]{m[0] * x + m[4] * y + m[8] * z + m[12], m[1] * x + m[5] * y + m[9] * z + m[13], m[2] * x + m[6] * y + m[10] * z + m[14]}; }
    static String desc(float[] f) { return String.format(Locale.US, "%.2f, %.2f, %.2f m %.1f deg", f[12], f[13], f[14], Math.toDegrees(Math.atan2(f[2], f[0]))); }

    static void run(File dir) throws Exception {
        File segF = new File(dir, "segments.bin"), logF = new File(dir, "scan-log.txt");
        if (!segF.exists()) return;
        int n; float[] p;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(new File(dir, "points.bin")), 1 << 16))) {
            n = in.readInt(); p = new float[n * 3];
            for (int i = 0; i < n; i++) { p[i * 3] = in.readFloat(); p[i * 3 + 1] = in.readFloat(); p[i * 3 + 2] = in.readFloat(); in.skipBytes(3); }
        }
        byte[] seg;
        try (DataInputStream in = new DataInputStream(new FileInputStream(segF))) { int m = in.readInt(); if (m != n) return; seg = new byte[n]; in.readFully(seg); }
        Map<Integer, float[]> fixes = new HashMap<>(); boolean userPart = false;
        if (logF.exists()) for (String l : java.nio.file.Files.readAllLines(logF.toPath())) {
            Matcher m = BACK.matcher(l);
            if (m.find()) fixes.put(Integer.parseInt(m.group(5)), fix(Float.parseFloat(m.group(1)), Float.parseFloat(m.group(2)), Float.parseFloat(m.group(3)), Float.parseFloat(m.group(4))));
            if (l.contains("new part after")) userPart = true;
        }
        int maxSeg = 0; for (byte b : seg) maxSeg = Math.max(maxSeg, b & 255);
        float[] ys = new float[n]; for (int i = 0; i < n; i++) ys[i] = p[i * 3 + 1];
        float floorY = WallFloor.of(p, n);
        System.out.printf(Locale.US, "%s: %d points, %d segments%s%n", dir.getName(), n, maxSeg + 1, userPart ? " (a user-chosen new part)" : "");
        for (int k = 1; k <= maxSeg; k++) {
            // the rest: earlier segments
            int nr = 0; for (int i = 0; i < n; i++) if ((seg[i] & 255) < k) nr++;
            if (nr < 5000) continue;
            float[] rest = new float[nr * 3]; int j = 0;
            for (int i = 0; i < n; i++) if ((seg[i] & 255) < k) { rest[j++] = p[i * 3]; rest[j++] = p[i * 3 + 1]; rest[j++] = p[i * 3 + 2]; }
            Realign rl = new Realign(rest, nr); rl.buildPlan(rest, nr, floorY);
            float[] live = fixes.get(k), before = fixes.getOrDefault(k - 1, Realign.identity());
            // where "carry on" would have put the segment: with the correction from before the loss instead of the live one
            float[] undo = live != null ? mul(before, inv(live)) : Realign.identity();
            List<float[]> pts = new ArrayList<>();
            int cnt = 0; for (int i = 0; i < n; i++) if ((seg[i] & 255) == k) cnt++;
            if (cnt < 2000) { System.out.printf(Locale.US, "  segment %d: %d points, too few%n", k, cnt); continue; }
            int step = Math.max(1, cnt / 3000), c = 0;
            for (int i = 0; i < n; i++) if ((seg[i] & 255) == k && (c++ % step == 0)) pts.add(apply(undo, p[i * 3], p[i * 3 + 1], p[i * 3 + 2]));
            long t0 = System.currentTimeMillis();
            Realign.Fit f1 = rl.solve(pts, Realign.identity());
            Realign.Fit f2 = rl.search(pts, Realign.identity());
            Realign.Fit best = f2 != null && f2.inliers > f1.inliers ? f2 : f1;
            // the expected answer: the inverse of the undo (puts the points back where the live re-align had them)
            float[] expect = inv(undo);
            float err = live == null ? Float.NaN : Realign.away(best.fix, expect);
            System.out.printf(Locale.US, "  segment %d: %d points; carry-on offset %s; fit %s, %.0f%% on the scan, median %.1f cm%s%s; %d ms%n",
                    k, cnt, live == null ? "unknown (no live fit)" : desc(expect), desc(best.fix), best.inliers * 100, best.median * 100,
                    rl.ambiguous ? ", AMBIGUOUS" : "", live == null ? "" : String.format(Locale.US, "; vs the live answer: %.2f off (m + deg/30)%s", err, err < 0.1f ? " = same" : ""),
                    System.currentTimeMillis() - t0);
        }
    }

    static final class WallFloor {
        static float of(float[] p, int n) {
            float[] ys = new float[n]; for (int i = 0; i < n; i++) ys[i] = p[i * 3 + 1];
            Arrays.sort(ys); float base = ys[(int) (n * 0.005)];
            int[] hist = new int[60];
            for (int i = 0; i < n; i++) { int b = (int) ((p[i * 3 + 1] - base) / 0.02f); if (b >= 0 && b < 60) hist[b]++; }
            int bb = 0; for (int b = 1; b < 60; b++) if (hist[b] > hist[bb]) bb = b;
            return base + (bb + 0.5f) * 0.02f;
        }
    }
}
