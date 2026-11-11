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

    public static void main(String[] a) throws Exception { for (String s : a) run(new File(s)); System.out.println("tally: " + tally); }

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
            float[] live = fixes.get(k), before = fixes.getOrDefault(k - 1, Realign.identity());
            // where "carry on" would have put the segment: with the correction from before the loss instead of the live one
            float[] undo = live != null ? mul(before, inv(live)) : Realign.identity();
            List<float[]> pts = new ArrayList<>();
            int cnt = 0; for (int i = 0; i < n; i++) if ((seg[i] & 255) == k) cnt++;
            if (cnt < 2000) { System.out.printf(Locale.US, "  segment %d: %d points, too few%n", k, cnt); continue; }
            int step = Math.max(1, cnt / 3000), c = 0;
            for (int i = 0; i < n; i++) if ((seg[i] & 255) == k && (c++ % step == 0)) pts.add(apply(undo, p[i * 3], p[i * 3 + 1], p[i * 3 + 2]));
            long t0 = System.currentTimeMillis();
            float[] expect = inv(undo);
            Result r2;
            if (cnt > nr && System.getenv("SEGFIT_NOSWAP") == null) {
                // the part is bigger than the rest: fit the rest onto the part, then turn the answer round
                float[] part = new float[cnt * 3]; int q2 = 0;
                for (int i = 0; i < n; i++) if ((seg[i] & 255) == k) { float[] w = apply(undo, p[i * 3], p[i * 3 + 1], p[i * 3 + 2]); part[q2++] = w[0]; part[q2++] = w[1]; part[q2++] = w[2]; }
                List<float[]> rs = new ArrayList<>(); int stp = Math.max(1, nr / 3000);
                for (int i = 0; i < nr; i += stp) rs.add(new float[]{rest[i * 3], rest[i * 3 + 1], rest[i * 3 + 2]});
                r2 = fitV2(part, cnt, floorY, rs);
                if (r2.fix != null) r2.fix = inv(r2.fix);
                if (r2.best != null) r2.best = inv(r2.best);
                r2.text = "(swapped) " + r2.text;
            } else r2 = fitV2(rest, nr, floorY, pts);
            float err = live == null || r2.fix == null ? Float.NaN : Realign.away(r2.fix, expect);
            String verdict = r2.fix == null ? "LEFT SEPARATE (" + r2.why + ")" : live == null ? "placed" : err < 0.1f ? "RIGHT" : err < 0.25f ? "CLOSE" : "WRONG";
            System.out.printf(Locale.US, "  segment %d: %d points; carry-on offset %s; %s%s; %d ms%n",
                    k, cnt, live == null ? "unknown" : desc(expect), r2.text, live == null || r2.fix == null ? "" : String.format(Locale.US, "; vs live %.2f -> %s", err, verdict),
                    System.currentTimeMillis() - t0);
            if (r2.fix == null) System.out.printf("    -> %s%n", verdict);
            String pics = System.getenv("SEGFIT_PICS");
            if (pics != null && r2.best != null) {   // top view: the rest grey; the part as the live re-align placed it (left), as the fit places it (right)
                List<float[]> live3 = new ArrayList<>(), fit3 = new ArrayList<>();
                for (float[] q : pts) { live3.add(apply(expect, q[0], q[1], q[2])); fit3.add(apply(r2.best, q[0], q[1], q[2])); }
                plan(new File(pics, dir.getName() + "-part" + k + ".png"), rest, nr, floorY, live3, fit3);
            }
            tally.merge(verdict.startsWith("LEFT") ? "left separate" : verdict, 1, Integer::sum);
        }
    }

    static final Map<String, Integer> tally = new TreeMap<>();

    static final class Result { float[] fix, best; String text = "", why = ""; }

    /** v2: candidates from a 2D search over all turns (3 deg) and shifts (10 cm, +-4 m) on the upright parts, each
     *  ICP-refined; scored only on the part's points that land where the rest of the scan is (the overlap); the best
     *  must beat the next different placement clearly, fit well on the overlap, and both halves of the part must agree
     *  on it on their own ("two agreeing fits"). Else the part is left separate. */
    static Result fitV2(float[] rest, int nr, float floorY, List<float[]> pts) {
        Result res = new Result();
        Realign rl = new Realign(rest, nr);
        // occupied voxels of the rest (3 cm) and its footprint (25 cm cells, any height)
        HashMap<Long, float[]> vox = new HashMap<>(); HashSet<Long> foot = new HashSet<>(), wall = new HashSet<>();
        for (int i = 0; i < nr; i++) {
            float x = rest[i * 3], y = rest[i * 3 + 1], z = rest[i * 3 + 2];
            float[] cc = vox.computeIfAbsent(key(x, y, z, 0.02f), kk -> new float[4]); cc[0] += x; cc[1] += y; cc[2] += z; cc[3]++; foot.add(key(x, 0, z, 0.25f));
            if (y - floorY > 0.3f && y - floorY < 1.9f) wall.add(key(x, 0, z, 0.10f));
        }
        for (float[] cc : vox.values()) { cc[0] /= cc[3]; cc[1] /= cc[3]; cc[2] /= cc[3]; }
        // upright samples of the part, for the 2D search
        List<float[]> up = new ArrayList<>();
        for (float[] q : pts) if (q[1] - floorY > 0.3f && q[1] - floorY < 1.9f) up.add(q);
        if (up.size() > 600) { List<float[]> u2 = new ArrayList<>(); float st = up.size() / 600f; for (int i = 0; i < 600; i++) u2.add(up.get((int) (i * st))); up = u2; }
        if (up.size() < 50) { res.why = "too few upright points"; res.text = res.why; return res; }
        float mx = 0, mz = 0; for (float[] q : up) { mx += q[0]; mz += q[2]; } mx /= up.size(); mz /= up.size();
        // 2D search: score = upright samples on the rest's upright cells
        List<float[]> cands = new ArrayList<>();   // yaw deg, tx, tz, score
        for (int yd = -180; yd < 180; yd += 3) {
            double a = Math.toRadians(yd); float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            float[] rx = new float[up.size()], rz = new float[up.size()];
            for (int i = 0; i < up.size(); i++) { float dx = up.get(i)[0] - mx, dz = up.get(i)[2] - mz; rx[i] = mx + ca * dx - sa * dz; rz[i] = mz + sa * dx + ca * dz; }
            for (int ix = -40; ix <= 40; ix++) for (int iz = -40; iz <= 40; iz++) {
                float tx = ix * 0.1f, tz = iz * 0.1f; int sc = 0;
                for (int i = 0; i < rx.length; i++) if (wall.contains(key(rx[i] + tx, 0, rz[i] + tz, 0.10f))) sc++;
                if (sc > up.size() * 0.15f) cands.add(new float[]{yd, tx, tz, sc});
            }
        }
        cands.sort((u, v) -> Float.compare(v[3], u[3]));
        // ICP-refine the best distinct candidates
        List<float[][]> fits = new ArrayList<>();   // {fix, {score, inl, med, overlapN}}
        for (float[] c : cands) {
            if (fits.size() >= 8) break;
            boolean dup = false;
            for (float[][] f : fits) if (Math.abs(angle(f[0]) - c[0]) < 10 && Math.hypot(f[0][12] - (c[1]), f[0][14] - (c[2])) < 0.4f) { dup = true; break; }
            if (dup) continue;
            double a = Math.toRadians(c[0]); float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            float[] st = Realign.identity(); st[0] = ca; st[8] = -sa; st[2] = sa; st[10] = ca;
            st[12] = mx - (ca * mx - sa * mz) + c[1]; st[14] = mz - (sa * mx + ca * mz) + c[2];
            Realign.Fit f = rl.solve(pts, st);
            float[] sc = overlapScore(f.fix, pts, vox, foot);
            fits.add(new float[][]{f.fix, sc});
        }
        if (fits.isEmpty()) { res.why = "no candidate"; res.text = res.why; return res; }
        fits.sort((u, v) -> Float.compare(v[1][0], u[1][0]));
        float[][] best = fits.get(0);
        res.best = best[0];
        float[][] second = null;
        for (float[][] f : fits.subList(1, fits.size())) if (Realign.away(f[0], best[0]) > 0.3f) { second = f; break; }   // (0.3: a few cm and degrees count as the same place)
        res.text = String.format(Locale.US, "best %s: overlap %.0f pts, %.0f%% on the scan, median %.1f cm%s", desc(best[0]), best[1][3], best[1][1] * 100, best[1][2] * 100,
                second == null ? "" : String.format(Locale.US, "; next %s %.0f%% (score %.0f vs %.0f)", desc(second[0]), second[1][1] * 100, second[1][0], best[1][0]));
        if (best[1][3] < 300) { res.why = "too little overlap"; return res; }
        if (best[1][1] < 0.6f || best[1][2] > 0.03f) { res.why = "fits poorly"; return res; }
        // Prior: where "carry on" left the part (ARCore's own frame; in the app also the rotation sensor's heading). When two
        // placements score alike, the one near the prior wins if it is clearly nearer (ARCore usually comes back close).
        float[] id = Realign.identity();
        boolean priorBreaksTie = second != null && Realign.away(best[0], id) < 0.6f && Realign.away(second[0], id) > Realign.away(best[0], id) + 0.25f;
        if (priorBreaksTie && second[1][0] > 0.8f * best[1][0]) res.text += String.format(Locale.US, "; tie broken by the prior (%.2f vs %.2f from it)", Realign.away(best[0], id), Realign.away(second[0], id));
        if (second != null && second[1][0] > 0.8f * best[1][0] && !priorBreaksTie) { res.why = String.format(Locale.US, "ambiguous: next %.2f away, %.0f%% of the score", Realign.away(second[0], best[0]), 100 * second[1][0] / best[1][0]); return res; }
        // two agreeing fits: each half of the part on its own, from the best placement
        List<float[]> h1 = new ArrayList<>(), h2 = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) (i % 2 == 0 ? h1 : h2).add(pts.get(i));
        Realign.Fit a1 = rl.solve(h1, best[0]), a2 = rl.solve(h2, best[0]);
        float hd = Math.max(Realign.away(a1.fix, a2.fix), Math.max(Realign.away(a1.fix, best[0]), Realign.away(a2.fix, best[0])));
        res.text += String.format(Locale.US, "; halves %.2f apart", hd);
        if (hd > 0.15f) { res.why = "halves disagree"; return res; }
        res.fix = best[0];
        return res;
    }

    static void plan(File f, float[] rest, int nr, float floorY, List<float[]> a, List<float[]> b) throws IOException {
        float x0 = 1e9f, x1 = -1e9f, z0 = 1e9f, z1 = -1e9f;
        for (int i = 0; i < nr; i++) { x0 = Math.min(x0, rest[i * 3]); x1 = Math.max(x1, rest[i * 3]); z0 = Math.min(z0, rest[i * 3 + 2]); z1 = Math.max(z1, rest[i * 3 + 2]); }
        for (List<float[]> l : List.of(a, b)) for (float[] q : l) { x0 = Math.min(x0, q[0]); x1 = Math.max(x1, q[0]); z0 = Math.min(z0, q[2]); z1 = Math.max(z1, q[2]); }
        float s = 0.025f; int w = (int) ((x1 - x0) / s) + 2, h = (int) ((z1 - z0) / s) + 2;
        if (w > 600 || h > 600) { s = Math.max(x1 - x0, z1 - z0) / 598; w = (int) ((x1 - x0) / s) + 2; h = (int) ((z1 - z0) / s) + 2; }
        java.awt.image.BufferedImage im = new java.awt.image.BufferedImage(w * 2 + 10, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w * 2 + 10; x++) im.setRGB(x, y, 0xFFFFFF);
        for (int pnl = 0; pnl < 2; pnl++) {
            int off = pnl * (w + 10);
            for (int i = 0; i < nr; i += 2) { float y = rest[i * 3 + 1] - floorY; if (y < 0.3f || y > 1.9f) continue; im.setRGB(off + (int) ((rest[i * 3] - x0) / s), (int) ((rest[i * 3 + 2] - z0) / s), 0x9a9a9a); }
            for (float[] q : pnl == 0 ? a : b) { if (q[1] - floorY < 0.3f || q[1] - floorY > 1.9f) continue; int px = off + (int) ((q[0] - x0) / s), pz = (int) ((q[2] - z0) / s);
                for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) if (px + dx < w * 2 + 10 && pz + dy < h) im.setRGB(px + dx, pz + dy, pnl == 0 ? 0xD0302A : 0x2A60D0); }
        }
        f.getParentFile().mkdirs();
        javax.imageio.ImageIO.write(im, "png", f);
    }

    static float angle(float[] f) { return (float) Math.toDegrees(Math.atan2(f[2], f[0])); }

    /** Score on the overlap only: the part's points that land on the rest's footprint; of those, the share within 3 cm
     *  (a filled voxel or a neighbour) and their median distance proxy. {score, inliers, median, overlap count} */
    static float[] overlapScore(float[] F, List<float[]> pts, HashMap<Long, float[]> vox, HashSet<Long> foot) {
        int ov = 0, in = 0; float[] ds = new float[pts.size()];
        for (float[] q : pts) {
            float[] w = apply(F, q[0], q[1], q[2]);
            if (!foot.contains(key(w[0], 0, w[2], 0.25f))) continue;
            float best = 0.08f;
            for (int dx = -2; dx <= 2; dx++) for (int dy = -2; dy <= 2; dy++) for (int dz = -2; dz <= 2; dz++) {
                float[] c = vox.get(key(w[0] + dx * 0.02f, w[1] + dy * 0.02f, w[2] + dz * 0.02f, 0.02f));
                if (c == null) continue;
                float d = (float) Math.sqrt((c[0] - w[0]) * (c[0] - w[0]) + (c[1] - w[1]) * (c[1] - w[1]) + (c[2] - w[2]) * (c[2] - w[2]));
                if (d < best) best = d;
            }
            ds[ov++] = best;
            if (best < 0.05f) in++;   // ARCore depth points scatter 2-3 cm: 5 cm counts as on the surface
        }
        float inl = ov == 0 ? 0 : (float) in / ov;
        float med = 0.06f; if (ov > 0) { float[] d2 = Arrays.copyOf(ds, ov); Arrays.sort(d2); med = d2[ov / 2]; }
        return new float[]{in, inl, med, ov};
    }

    static long key(float x, float y, float z, float s) {
        long ix = (long) Math.floor(x / s) + (1 << 20), iy = (long) Math.floor(y / s) + (1 << 20), iz = (long) Math.floor(z / s) + (1 << 20);
        return (ix << 42) | (iy << 21) | iz;
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
