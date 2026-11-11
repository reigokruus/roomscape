package com.hackathon.setscan;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Offline check of Anchors on saved Android scan folders (read-only): for each tracking loss in scan-log.txt, which
 * anchor would have been proposed, how far and how many degrees away, its score; and where the old hint pointed.
 * The scan's keyframe photos stand in for the anchors the phone keeps live (the phone offers a view about once a
 * second; keyframes are fewer), with the feature count from the log's per-second state line nearest in time.
 *
 *   javac -d /tmp/ac android/app/src/main/java/com/hackathon/setscan/Anchors.java tools/anchors/AnchorCheck.java
 *   java -cp /tmp/ac com.hackathon.setscan.AnchorCheck <scan folder>... [-pics <out dir>]
 */
public class AnchorCheck {
    public static void main(String[] args) throws Exception {
        File pics = null; List<File> dirs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) { if (args[i].equals("-pics")) pics = new File(args[++i]); else dirs.add(new File(args[i])); }
        if (pics != null) pics.mkdirs();
        for (File d : dirs) check(d, pics);
    }

    static final Pattern STATE = Pattern.compile("^\\s*([0-9.]+)s\\s+state: (\\S+),.*ARCore features (-?\\d+)");
    static final Pattern LOST = Pattern.compile("^\\s*([0-9.]+)s\\s+tracking lost mid-scan");
    static final Pattern BACK = Pattern.compile("^\\s*([0-9.]+)s\\s+back on track after ([0-9.]+) s");

    static void check(File dir, File pics) throws Exception {
        File logF = new File(dir, "scan-log.txt"), trF = new File(dir, "pose-track.bin"), kfF = new File(dir, "kf.bin");
        if (!logF.exists() || !trF.exists() || !kfF.exists()) { System.out.println(dir.getName() + ": no scan-log / pose-track / kf.bin"); return; }
        List<float[]> states = new ArrayList<>(); List<Float> losses = new ArrayList<>(); List<float[]> backs = new ArrayList<>();
        for (String l : Files.readAllLines(logF.toPath())) {
            Matcher m = STATE.matcher(l);
            if (m.find()) states.add(new float[]{Float.parseFloat(m.group(1)), m.group(2).equals("TRACKING") ? 1 : 0, Integer.parseInt(m.group(3))});
            m = LOST.matcher(l); if (m.find()) losses.add(Float.parseFloat(m.group(1)));
            m = BACK.matcher(l); if (m.find()) backs.add(new float[]{Float.parseFloat(m.group(1)), Float.parseFloat(m.group(2))});
        }
        float[] tr; int tn;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(trF)))) {
            tn = in.readInt(); tr = new float[tn * 13]; for (int i = 0; i < tr.length; i++) tr[i] = in.readFloat();
        }
        // The scan's points (sub-sampled), to render a small depth view from each tracked pose once a second (the scan
        // folder keeps no depth frames along the way; the phone uses its real depth image).
        float[] pts; int np;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(new File(dir, "points.bin")), 1 << 16))) {
            int n = in.readInt(); int step = Math.max(1, n / 400000); np = 0; pts = new float[(n / step + 1) * 3]; byte[] c = new byte[3];
            for (int i = 0; i < n; i++) { float x = in.readFloat(), y = in.readFloat(), z = in.readFloat(); in.readFully(c); if (i % step == 0) { pts[np * 3] = x; pts[np * 3 + 1] = y; pts[np * 3 + 2] = z; np++; } }
        }
        Anchors an = new Anchors(); List<Anchors.Anchor> all = new ArrayList<>();
        int nk = 0, offered = 0; long lastMs = -10000;
        for (int i = 0; i < tn; i++) {
            long ms = (long) tr[i * 13];
            if (tr[i * 13 + 1] <= 0 || tr[i * 13 + 2] > 0 || ms - lastMs < 1000) continue;   // tracking and lined up, once a second
            if (inLoss(ms / 1000f, losses, backs)) continue;
            lastMs = ms; nk++;
            int feat = featuresAt(states, ms / 1000f);
            float[] cam = corrected(tr, i);
            short[] dep = render(cam, pts, np, false);
            float[] s = Anchors.shapeScore(dep, DW, DH, FX, FX, DW / 2f, DH / 2f);
            Anchors.Anchor a = Anchors.make(cam, ms, feat, s);
            all.add(a);
            if (Anchors.good(feat, s)) { offered++; an.keep(a); }
        }
        Map<Anchors.Anchor, Integer> kfIndex = new HashMap<>(); for (int i = 0; i < all.size(); i++) kfIndex.put(all.get(i), i);
        System.out.printf(Locale.US, "%s: %d views (1 a second), %d good enough, %d kept as anchors; %d losses%n", dir.getName(), nk, offered, an.size(), losses.size());
        for (float lt : losses) {
            int li = -1; for (int i = 0; i < tn; i++) { if (tr[i * 13] / 1000f > lt) break; if (tr[i * 13 + 1] > 0) li = i; }
            if (li < 0) continue;
            float[] lost = corrected(tr, li);
            int fBefore = 0, nB = 0; for (float[] s : states) if (s[0] <= lt && s[0] > lt - 5 && s[1] > 0) { fBefore += (int) s[2]; nB++; }
            float back = -1; for (float[] b : backs) if (b[0] > lt && b[0] - b[1] <= lt + 1) { back = b[1]; break; }
            float[] o = new float[3];
            Set<Anchors.Anchor> tried = new HashSet<>();
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < 2; k++) {
                Anchors.Anchor a = an.pick(lost, (long) (lt * 1000), tried, o);
                if (a == null) { sb.append(k == 0 ? "  no anchor" : ""); break; }
                tried.add(a);
                sb.append(String.format(Locale.US, "%n    %s: view at %.0f s, %.1f m away, turn %.0f deg, %d features, shape %.2f, %.0f s before, score %.2f",
                        k == 0 ? "first" : "next ", a.ms / 1000f, o[0], o[1], a.features, a.shape, lt - a.ms / 1000f, o[2]));
                if (pics != null && k == 0) {
                    pics.mkdirs();
                    writePic(new File(pics, dir.getName() + String.format(Locale.US, "-%.0fs-anchor.pgm", lt)), render(a.cam, pts, np, true));
                }
            }
            // the keyframe nearest the loss view, to show what the old hint pointed at
            Anchors.Anchor near = null; float nd = 1e9f;
            for (Anchors.Anchor a : all) { if (a.ms > lt * 1000) continue; float dd = Anchors.dist(a.px(), a.py(), a.pz(), lost[12], lost[13], lost[14]) + (1 - (a.fx() * -lost[8] + a.fy() * -lost[9] + a.fz() * -lost[10])); if (dd < nd) { nd = dd; near = a; } }
            System.out.printf(Locale.US, "  lost at %.1f s (features in the 5 s before: %s)%s; old hint aimed at the loss view%s:%s%n", lt,
                    nB == 0 ? "-" : String.valueOf(fBefore / nB), back >= 0 ? String.format(Locale.US, ", back on track after %.1f s", back) : ", never back on track",
                    near == null ? "" : String.format(Locale.US, " (nearest view %.0f s: %d features, shape %.2f)", near.ms / 1000f, near.features, near.shape), sb);
            if (pics != null) writePic(new File(pics, dir.getName() + String.format(Locale.US, "-%.0fs-lostview.pgm", lt)), render(lost, pts, np, true));
        }
    }

    static final int DW = 80, DH = 45; static final float FX = 62;   // about ARCore's depth view (~65 deg wide)

    static boolean inLoss(float t, List<Float> losses, List<float[]> backs) {
        for (float lt : losses) {
            float end = Float.MAX_VALUE; for (float[] b : backs) if (b[0] > lt) { end = b[0]; break; }
            if (t >= lt && t <= end) return true;
        }
        return false;
    }

    /** The points seen from cam (camera -> world; looks along -z, y up) as a depth image in mm (z-buffer), or with
     *  big=true a 320x180 grey shading of depth for a picture. */
    static short[] render(float[] cam, float[] pts, int np, boolean big) {
        int w = big ? 320 : DW, h = big ? 180 : DH; float f = big ? FX * 4 : FX;
        short[] d = new short[w * h];
        float px = cam[12], py = cam[13], pz = cam[14];
        for (int i = 0; i < np; i++) {
            float dx = pts[i * 3] - px, dy = pts[i * 3 + 1] - py, dz = pts[i * 3 + 2] - pz;
            float x = dx * cam[0] + dy * cam[1] + dz * cam[2], y = dx * cam[4] + dy * cam[5] + dz * cam[6], z = -(dx * cam[8] + dy * cam[9] + dz * cam[10]);
            if (z < 0.2f || z > 5f) continue;
            int u = Math.round(w / 2f + x / z * f), v = Math.round(h / 2f - y / z * f);
            int r = big ? 1 : 0;
            for (int vv = v - r; vv <= v + r; vv++) for (int uu = u - r; uu <= u + r; uu++) {
                if (uu < 0 || vv < 0 || uu >= w || vv >= h) continue;
                int k = vv * w + uu, mm = Math.round(z * 1000);
                if (d[k] == 0 || mm < d[k]) d[k] = (short) mm;
            }
        }
        return d;
    }

    static void writePic(File f, short[] d) throws IOException {
        int w = 320, h = 180;
        try (OutputStream o = new BufferedOutputStream(new FileOutputStream(f))) {
            o.write(("P5\n" + w + " " + h + "\n255\n").getBytes());
            for (int i = 0; i < w * h; i++) { int mm = d[i] & 0xFFFF; o.write(mm == 0 ? 0 : Math.max(30, 255 - (mm - 300) * 225 / 4000)); }
        }
    }

    static int featuresAt(List<float[]> states, float t) {
        int best = -1; float bd = 1e9f;
        for (float[] s : states) { float d = Math.abs(s[0] - t); if (d < bd && s[1] > 0) { bd = d; best = (int) s[2]; } }
        return bd < 3 ? best : -1;
    }

    /** Camera -> world with the logged correction (shift x, z and turn about the vertical) applied. */
    static float[] corrected(float[] tr, int i) {
        int o = i * 13;
        float x = tr[o + 6], y = tr[o + 7], z = tr[o + 8], w = tr[o + 9];
        float[] m = new float[16];
        m[0] = 1 - 2 * (y * y + z * z); m[1] = 2 * (x * y + w * z); m[2] = 2 * (x * z - w * y);
        m[4] = 2 * (x * y - w * z); m[5] = 1 - 2 * (x * x + z * z); m[6] = 2 * (y * z + w * x);
        m[8] = 2 * (x * z + w * y); m[9] = 2 * (y * z - w * x); m[10] = 1 - 2 * (x * x + y * y);
        m[12] = tr[o + 3]; m[13] = tr[o + 4]; m[14] = tr[o + 5]; m[15] = 1;
        double a = Math.toRadians(tr[o + 12]); float c = (float) Math.cos(a), s = (float) Math.sin(a);
        float[] r = new float[16];
        for (int col = 0; col < 4; col++) {
            float vx = m[col * 4], vy = m[col * 4 + 1], vz = m[col * 4 + 2];
            r[col * 4] = c * vx - s * vz; r[col * 4 + 1] = vy; r[col * 4 + 2] = s * vx + c * vz; r[col * 4 + 3] = m[col * 4 + 3];
        }
        r[12] += tr[o + 10]; r[14] += tr[o + 11];
        return r;
    }

    /** Scan time (ms) of a keyframe: the tracked pose nearest it in place and direction. */
    static long timeOf(float[] pose, float[] tr, int tn) {
        float best = 1e9f; long t = 0;
        for (int i = 0; i < tn; i++) {
            if (tr[i * 13 + 1] <= 0) continue;
            float[] c = corrected(tr, i);
            float d = Anchors.dist(c[12], c[13], c[14], pose[12], pose[13], pose[14]) + 0.5f * (1 - (c[8] * pose[8] + c[9] * pose[9] + c[10] * pose[10]));
            if (d < best) { best = d; t = (long) tr[i * 13]; }
        }
        return t;
    }
}
