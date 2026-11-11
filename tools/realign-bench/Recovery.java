package com.hackathon.setscan;

import java.io.*;
import java.util.*;
import java.util.regex.*;

/**
 * After ARCore comes back from a loss: how soon could the scan carry on, and would it be right? Replays the depth
 * frames kept around each loss (events-depth.bin, ~3 a second) from ARCore's return ("pause ... quiet re-align") to
 * the live resume ("back on track ... correction"), against the scan's points from before the loss (segments.bin), and
 * tries the line-up strategies below. The live resume's correction is taken as the answer (check doubtful ones).
 *
 *   now     every frame on its own, ICP from the correction in use (+-6/12 deg starts), two agreeing frames in a row
 *   wait3   the same, but the first 3 frames after ARCore's return are skipped (depth settling)
 *   pool3   the last 3 frames pooled into one fit, two agreeing pools in a row
 *   local   only a small change from ARCore's own pose (<= 0.2 m, <= 8 deg): one good frame is enough (rk's rule: a
 *           single fit may turn at most 10 deg)
 *
 *   javac -d /tmp/rb android/app/src/main/java/com/hackathon/setscan/Realign.java tools/realign-bench/Recovery.java
 *   java -cp /tmp/rb com.hackathon.setscan.Recovery <scan folder>...
 */
public class Recovery {
    static final Pattern BACK = Pattern.compile("^\\s*([\\d.]+)s\\s+back on track after ([\\d.]+) s: correction (-?[\\d.]+), (-?[\\d.]+), (-?[\\d.]+) m, (-?[\\d.]+) deg;.*segment (\\d+)");
    static final Pattern PAUSE = Pattern.compile("^\\s*([\\d.]+)s\\s+pause ([\\d.]+) s: ARCore back ([\\d.]+) m and ([\\d.]+) deg from the held view: quiet re-align");
    static final Map<String, int[]> tot = new TreeMap<>();   // strategy -> {losses, accepted, right, wrong, savedMs}

    public static void main(String[] a) throws Exception {
        for (String s : a) try { run(new File(s)); } catch (Exception e) { System.out.println(s + ": " + e); }
        System.out.println("\nall losses where ARCore came back by itself:");
        for (Map.Entry<String, int[]> e : tot.entrySet()) { int[] v = e.getValue();
            System.out.printf(Locale.US, "  %-6s %d losses: accepted %d (right %d, WRONG %d), %.1f s sooner in all (%.1f s a loss)%n", e.getKey(), v[0], v[1], v[2], v[3], v[4] / 1000f, v[0] == 0 ? 0 : v[4] / 1000f / v[0]); }
    }

    static final class Ev { long ms; float[] raw = new float[16], fix = new float[16]; List<float[]> camPts = new ArrayList<>(); }

    static void run(File dir) throws Exception {
        File ev = new File(dir, "events-depth.bin"), lg = new File(dir, "scan-log.txt"), sg = new File(dir, "segments.bin");
        if (!ev.exists() || !lg.exists()) return;
        // losses: ARCore back (pause line) then back on track
        List<float[]> losses = new ArrayList<>();   // {tBack, tResume, x, y, z, deg, segment}
        float pendingBack = -1;
        for (String l : java.nio.file.Files.readAllLines(lg.toPath())) {
            Matcher m = PAUSE.matcher(l); if (m.find()) pendingBack = Float.parseFloat(m.group(1));
            m = BACK.matcher(l);
            if (m.find() && pendingBack > 0) {
                losses.add(new float[]{pendingBack, Float.parseFloat(m.group(1)), Float.parseFloat(m.group(3)), Float.parseFloat(m.group(4)), Float.parseFloat(m.group(5)), Float.parseFloat(m.group(6)), Integer.parseInt(m.group(7))});
                pendingBack = -1;
            }
        }
        if (losses.isEmpty()) return;
        int n; float[] p; byte[] seg = null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(new File(dir, "points.bin")), 1 << 16))) {
            n = in.readInt(); p = new float[n * 3];
            for (int i = 0; i < n; i++) { p[i * 3] = in.readFloat(); p[i * 3 + 1] = in.readFloat(); p[i * 3 + 2] = in.readFloat(); in.skipBytes(3); }
        }
        if (sg.exists()) try (DataInputStream in = new DataInputStream(new FileInputStream(sg))) { int m = in.readInt(); if (m == n) { seg = new byte[n]; in.readFully(seg); } }
        List<Ev> evs = new ArrayList<>();
        try (DataInputStream e = new DataInputStream(new BufferedInputStream(new FileInputStream(ev)))) {
            int k = e.readInt();
            for (int f = 0; f < k; f++) {
                Ev x = new Ev(); e.readLong(); x.ms = e.readLong();
                for (int i = 0; i < 16; i++) x.raw[i] = e.readFloat(); for (int i = 0; i < 16; i++) x.fix[i] = e.readFloat();
                float fx = e.readFloat(), fy = e.readFloat(), cx = e.readFloat(), cy = e.readFloat(); int w = e.readInt(), h = e.readInt();
                short[] d = new short[w * h]; for (int i = 0; i < w * h; i++) d[i] = e.readShort();
                for (int v = 3; v < h - 3; v += 3) for (int u = 3; u < w - 3; u += 3) {
                    int mm = d[v * w + u] & 0xFFFF; if (mm < 300 || mm > 3500) continue;
                    float dd = mm * 0.001f, xc = (u + 0.5f - cx) * dd / fx, yc = (cy - (v + 0.5f)) * dd / fy, zc = -dd;
                    x.camPts.add(new float[]{x.raw[0] * xc + x.raw[4] * yc + x.raw[8] * zc + x.raw[12], x.raw[1] * xc + x.raw[5] * yc + x.raw[9] * zc + x.raw[13], x.raw[2] * xc + x.raw[6] * yc + x.raw[10] * zc + x.raw[14]});
                }
                evs.add(x);
            }
        }
        System.out.printf(Locale.US, "%s: %d losses with ARCore back by itself, %d event frames%n", dir.getName(), losses.size(), evs.size());
        for (float[] L : losses) {
            int sgk = (int) L[6];
            int nr = 0; for (int i = 0; i < n; i++) if (seg == null || (seg[i] & 255) < sgk) nr++;
            float[] rest = new float[nr * 3]; int j = 0;
            for (int i = 0; i < n; i++) if (seg == null || (seg[i] & 255) < sgk) { rest[j++] = p[i * 3]; rest[j++] = p[i * 3 + 1]; rest[j++] = p[i * 3 + 2]; }
            Realign rl = new Realign(rest, nr);
            float[] truth = Realign.identity(); double t = Math.toRadians(L[5]); truth[0] = (float) Math.cos(t); truth[8] = -(float) Math.sin(t); truth[2] = (float) Math.sin(t); truth[10] = (float) Math.cos(t); truth[12] = L[2]; truth[13] = L[3]; truth[14] = L[4];
            List<Ev> win = new ArrayList<>();
            for (Ev x : evs) if (x.ms >= L[0] * 1000 - 200 && x.ms <= L[1] * 1000 + 200 && x.camPts.size() >= 100) win.add(x);
            System.out.printf(Locale.US, "  ARCore back at %.1f s, live resume at %.1f s (%.1f s later), answer %s; %d frames in between%n", L[0], L[1], L[1] - L[0], d(truth), win.size());
            if (win.size() < 2) continue;
            for (String st : new String[]{"now", "wait3", "pool3", "local"}) {
                float[] prev = null; long acc = -1; float[] got = null;
                for (int i = 0; i < win.size(); i++) {
                    Ev x = win.get(i);
                    if (st.equals("wait3") && i < 3) continue;
                    List<float[]> pts = new ArrayList<>(x.camPts);
                    if (st.equals("pool3")) for (int q = Math.max(0, i - 2); q < i; q++) pts.addAll(win.get(q).camPts);
                    if (pts.size() > 1200) { List<float[]> s2 = new ArrayList<>(); float stp = pts.size() / 1200f; for (int q = 0; q < 1200; q++) s2.add(pts.get((int) (q * stp))); pts = s2; }
                    Realign.Fit f = rl.solve(pts, x.fix);
                    boolean good = f.inliers >= 0.5f && f.median < Realign.MAX_MEDIAN;
                    if (st.equals("local")) {
                        float dm = (float) Math.hypot(f.fix[12] - x.fix[12], f.fix[14] - x.fix[14]);
                        double dy = Math.abs(Math.toDegrees(Math.atan2(f.fix[2], f.fix[0]) - Math.atan2(x.fix[2], x.fix[0])));
                        if (good && f.inliers >= 0.6f && f.median < 0.015f && dm <= 0.2f && dy <= 8) { acc = x.ms; got = f.fix; break; }
                        continue;
                    }
                    if (good && prev != null && Realign.away(f.fix, prev) < 0.08f) { acc = x.ms; got = f.fix; break; }
                    prev = good ? f.fix : null;
                }
                int[] v = tot.computeIfAbsent(st, k2 -> new int[5]); v[0]++;
                if (acc < 0) { System.out.printf(Locale.US, "    %-6s never in this window%n", st); continue; }
                float off = Realign.away(got, truth); boolean right = off < 0.15f;
                v[1]++; if (right) v[2]++; else v[3]++;
                long saved = (long) (L[1] * 1000 - acc); if (right) v[4] += Math.max(0, saved);
                System.out.printf(Locale.US, "    %-6s at %.1f s (%.1f s after ARCore was back, %.1f s sooner): %s, %.2f from the answer -> %s%n", st, acc / 1000f, acc / 1000f - L[0], saved / 1000f, d(got), off, right ? "right" : "WRONG");
            }
        }
    }

    static String d(float[] f) { return String.format(Locale.US, "%.2f, %.2f, %.2f m %.1f deg", f[12], f[13], f[14], Math.toDegrees(Math.atan2(f[2], f[0]))); }
}
