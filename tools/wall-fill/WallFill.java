package com.hackathon.setscan;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

/**
 * Offline prototype (pictures only, nothing in the app): fill blank walls from their outline. For each room FloorArea
 * finds (its wall rectangle along the room's own directions), each side becomes a wall from the floor to the ceiling.
 * Points within 8 cm of a wall cover it; points seen well behind it (through a doorway or window) mark an opening that
 * stays open; everything else on the wall is blank (plain wall the phone couldn't place) and is filled flat with the
 * colour of the nearest covered part. Writes, per scan, one picture with each wall twice: as scanned, then filled.
 *
 *   javac -d /tmp/wf android/app/src/main/java/com/hackathon/setscan/FloorArea.java tools/wall-fill/WallFill.java
 *   java -cp /tmp/wf com.hackathon.setscan.WallFill <out dir> <scan folder>...
 */
public class WallFill {
    static final float CELL = 0.04f;

    public static void main(String[] args) throws Exception {
        File out = new File(args[0]); out.mkdirs();
        for (int i = 1; i < args.length; i++) run(new File(args[i]), out);
    }

    static void run(File dir, File out) throws Exception {
        int n; float[] p; int[] c;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(new File(dir, "points.bin")), 1 << 16))) {
            n = in.readInt(); p = new float[n * 3]; c = new int[n];
            for (int i = 0; i < n; i++) { p[i * 3] = in.readFloat(); p[i * 3 + 1] = in.readFloat(); p[i * 3 + 2] = in.readFloat(); c[i] = (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte(); }
        }
        float floorY = floorOf(p, n);
        FloorArea.Result r = FloorArea.compute(p, n, floorY);
        StringBuilder rep = new StringBuilder(String.format(Locale.US, "%s: %d points, floor y %.2f, %s%n", dir.getName(), n, floorY, r.text()));
        List<BufferedImage> rows = new ArrayList<>(); List<String> labels = new ArrayList<>();
        int ri = 0;
        for (FloorArea.Room room : r.rooms) {
            ri++;
            if (room.rect == null || room.walled <= 0) { rep.append(String.format(Locale.US, "  room %d: %.1f m², no walls found: skipped%n", ri, room.area)); continue; }
            double a = Math.toRadians(room.deg); float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            float u0 = room.rect[0], u1 = room.rect[1], v0 = room.rect[2], v1 = room.rect[3];
            // ceiling: a clear peak (a flat surface) among the heights 2.2-3.4 m inside the room, away from the walls
            int[] hist = new int[150];
            float[] uv = new float[2];
            for (int i = 0; i < n; i++) {
                toUV(r, ca, sa, p[i * 3], p[i * 3 + 2], uv);
                if (uv[0] < u0 + 0.3f || uv[0] > u1 - 0.3f || uv[1] < v0 + 0.3f || uv[1] > v1 - 0.3f) continue;
                int b = (int) ((p[i * 3 + 1] - floorY - 2.2f) / 0.02f); if (b >= 0 && b < 60) hist[b]++;
            }
            int bb = -1; for (int b = 0; b < 60; b++) if (hist[b] > 30 && (bb < 0 || hist[b] > hist[bb])) bb = b;
            if (bb >= 0) { int[] srt = Arrays.copyOf(hist, 60); Arrays.sort(srt); if (hist[bb] < 3 * Math.max(10, srt[30])) bb = -1; }
            float H = bb >= 0 ? 2.2f + (bb + 0.5f) * 0.02f : 2.6f;
            boolean ceilingSeen = bb >= 0;
            rep.append(String.format(Locale.US, "  room %d: %.2f × %.2f m walls (%d sides seen), floor %.1f m², ceiling %s %.2f m%n",
                    ri, u1 - u0, v1 - v0, room.wallSides, room.walled, ceilingSeen ? "at" : "not seen, assumed", H));
            float tot = 0, blank = 0, open = 0, front = 0;
            for (int side = 0; side < 4; side++) {
                float L = side < 2 ? v1 - v0 : u1 - u0;
                int W = Math.max(1, (int) Math.ceil(L / CELL)), Hc = Math.max(1, (int) Math.ceil(H / CELL));
                int[] cnt = new int[W * Hc], behind = new int[W * Hc], before = new int[W * Hc]; long[] rs = new long[W * Hc], gs = new long[W * Hc], bs = new long[W * Hc];
                for (int i = 0; i < n; i++) {
                    toUV(r, ca, sa, p[i * 3], p[i * 3 + 2], uv);
                    float d, s;   // d: inwards from the wall (m), s: along it
                    switch (side) {
                        case 0: d = uv[0] - u0; s = uv[1] - v0; break;
                        case 1: d = u1 - uv[0]; s = v1 - uv[1]; break;
                        case 2: d = uv[1] - v0; s = u1 - uv[0]; break;
                        default: d = v1 - uv[1]; s = uv[0] - u0;
                    }
                    float h = p[i * 3 + 1] - floorY;
                    if (s < 0 || s >= L || h < 0 || h >= H) continue;
                    int k = (Hc - 1 - (int) (h / CELL)) * W + (int) (s / CELL);
                    if (Math.abs(d) < 0.08f) { cnt[k]++; rs[k] += c[i] >> 16 & 255; gs[k] += c[i] >> 8 & 255; bs[k] += c[i] & 255; }
                    else if (d < -0.25f) behind[k]++;
                    else if (d > 0.08f && d < 0.6f) before[k]++;
                }
                // openings: seen through (points behind, nothing on the wall), grown 1 cell
                boolean[] opn = new boolean[W * Hc];
                for (int k = 0; k < W * Hc; k++) if (cnt[k] < 2 && behind[k] >= 2) opn[k] = true;
                boolean[] o2 = opn.clone();
                for (int y = 0; y < Hc; y++) for (int x = 0; x < W; x++) if (opn[y * W + x]) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int xx = x + dx, yy = y + dy; if (xx >= 0 && yy >= 0 && xx < W && yy < Hc && cnt[yy * W + xx] < 2) o2[yy * W + xx] = true;
                }
                opn = o2;
                {   // only door- or window-sized openings (a few cells seen through are noise or a wall placed a bit in front)
                    boolean[] keep = new boolean[W * Hc]; int[] stack = new int[W * Hc]; boolean[] seen = new boolean[W * Hc];
                    for (int k0 = 0; k0 < W * Hc; k0++) {
                        if (!opn[k0] || seen[k0]) continue;
                        int sp = 0, nreg = 0; stack[sp++] = k0; seen[k0] = true; java.util.ArrayList<Integer> reg = new java.util.ArrayList<>();
                        while (sp > 0) { int k = stack[--sp]; reg.add(k); int x = k % W, y = k / W;
                            int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                            for (int[] d2 : nb) { int xx = x + d2[0], yy = y + d2[1]; if (xx < 0 || yy < 0 || xx >= W || yy >= Hc) continue; int kk = yy * W + xx; if (opn[kk] && !seen[kk]) { seen[kk] = true; stack[sp++] = kk; } } }
                        if (reg.size() * CELL * CELL >= 0.25f) for (int k : reg) keep[k] = true;
                    }
                    opn = keep;
                }
                int[] col = new int[W * Hc]; boolean[] cov = new boolean[W * Hc];
                ArrayDeque<Integer> q = new ArrayDeque<>();
                for (int k = 0; k < W * Hc; k++) if (cnt[k] >= 2) { cov[k] = true; col[k] = (int) (rs[k] / cnt[k]) << 16 | (int) (gs[k] / cnt[k]) << 8 | (int) (bs[k] / cnt[k]); q.add(k); }
                int paint = 0xB0B0B0; {
                    java.util.ArrayList<Integer> cs = new java.util.ArrayList<>();
                    for (int y = 0; y < Hc; y++) { float hh = (Hc - 1 - y) * CELL; if (hh < 0.5f || hh > 2.2f) continue; for (int x = 0; x < W; x++) if (cov[y * W + x]) cs.add(col[y * W + x]); }
                    if (cs.isEmpty()) for (int k = 0; k < W * Hc; k++) if (cov[k]) cs.add(col[k]);
                    cs.sort((a1, b1) -> Integer.compare((a1 >> 16 & 255) + (a1 >> 8 & 255) + (a1 & 255), (b1 >> 16 & 255) + (b1 >> 8 & 255) + (b1 & 255)));
                    if (!cs.isEmpty()) paint = cs.get(cs.size() * 2 / 3);   // walls are usually the lighter part of what's on them
                }
                int[] fill = col.clone(); boolean[] has = cov.clone();
                while (!q.isEmpty()) {   // nearest covered colour, spread over the blank cells
                    int k = q.poll(), x = k % W, y = k / W;
                    int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                    for (int[] d2 : nb) { int xx = x + d2[0], yy = y + d2[1]; if (xx < 0 || yy < 0 || xx >= W || yy >= Hc) continue; int kk = yy * W + xx; if (has[kk] || opn[kk]) continue; has[kk] = true; fill[kk] = fill[k]; q.add(kk); }
                }
                int nCov = 0, nOpen = 0, nBlank = 0, nFront = 0;
                for (int k = 0; k < W * Hc; k++) { if (cov[k]) nCov++; else if (opn[k]) nOpen++; else { nBlank++; if (before[k] >= 3) nFront++; } }
                float cellA = CELL * CELL;
                tot += W * Hc * cellA; blank += nBlank * cellA; open += nOpen * cellA; front += nFront * cellA;
                rep.append(String.format(Locale.US, "    wall %c (%.2f m): %.1f m², scanned %.0f%%, blank and filled %.0f%% (%.0f%% of that has something in front of it), left open %.0f%%%n",
                        "ABCD".charAt(side), L, W * Hc * cellA, 100f * nCov / (W * Hc), 100f * nBlank / (W * Hc), nBlank == 0 ? 0 : 100f * nFront / nBlank, 100f * nOpen / (W * Hc)));
                // picture: as scanned (grey stripes = blank, black = open), then filled (fill slightly lighter, thin outline)
                int S = 3, gap = 6;
                BufferedImage im = new BufferedImage(W * S * 2 + gap, Hc * S, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < Hc; y++) for (int x = 0; x < W; x++) {
                    int k = y * W + x;
                    int before0 = cov[k] ? col[k] : opn[k] ? 0x000000 : (((x + y) / 3) % 2 == 0 ? 0x505050 : 0x3a3a3a);
                    int after0 = cov[k] ? col[k] : opn[k] ? 0x000000 : paint;
                    for (int yy = 0; yy < S; yy++) for (int xx = 0; xx < S; xx++) { im.setRGB(x * S + xx, y * S + yy, before0); im.setRGB(W * S + gap + x * S + xx, y * S + yy, after0); }
                }
                for (int y = 0; y < Hc * S; y++) for (int x = W * S; x < W * S + gap; x++) im.setRGB(x, y, 0xFFFFFF);
                rows.add(im); labels.add(String.format(Locale.US, "room %d wall %c", ri, "ABCD".charAt(side)));
            }
            rep.append(String.format(Locale.US, "  room %d walls: %.1f m² in all, %.0f%% blank and filled (%.0f%% of that behind furniture or curtains), %.0f%% left open%n",
                    ri, tot, 100 * blank / tot, blank == 0 ? 0 : 100 * front / blank, 100 * open / tot));
        }
        System.out.print(rep);
        if (rows.isEmpty()) return;
        int w = 0, h = 0; for (BufferedImage b : rows) { w = Math.max(w, b.getWidth()); h += b.getHeight() + 10; }
        BufferedImage all = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = all.createGraphics(); g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, w, h);
        int y = 0; for (BufferedImage b : rows) { g.drawImage(b, 0, y, null); y += b.getHeight() + 10; }
        g.dispose();
        javax.imageio.ImageIO.write(all, "png", new File(out, dir.getName() + "-walls.png"));
        try (Writer wr = new FileWriter(new File(out, dir.getName() + "-walls.txt"))) { wr.write(rep.toString()); }
    }

    static int lighten(int c) {
        int r = c >> 16 & 255, g = c >> 8 & 255, b = c & 255;
        return Math.min(255, r + 18) << 16 | Math.min(255, g + 18) << 8 | Math.min(255, b + 18);
    }

    static void toUV(FloorArea.Result r, float ca, float sa, float x, float z, float[] uv) {
        float dx = x - r.x0, dz = z - r.z0;   // x = x0 + u cos - v sin, z = z0 + u sin + v cos
        uv[0] = dx * ca + dz * sa; uv[1] = -dx * sa + dz * ca;
    }

    static float floorOf(float[] p, int n) {   // as FloorArea.main: the busiest 2 cm height in the lowest metre
        float[] ys = new float[n]; for (int i = 0; i < n; i++) ys[i] = p[i * 3 + 1];
        Arrays.sort(ys); float base = ys[(int) (n * 0.005)];
        int[] hist = new int[60];
        for (int i = 0; i < n; i++) { int b = (int) ((p[i * 3 + 1] - base) / 0.02f); if (b >= 0 && b < 60) hist[b]++; }
        int bb = 0; for (int b = 1; b < 60; b++) if (hist[b] > hist[bb]) bb = b;
        return base + (bb + 0.5f) * 0.02f;
    }
}
