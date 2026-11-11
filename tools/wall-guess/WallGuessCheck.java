package com.hackathon.setscan;

import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.awt.image.BufferedImage;

/**
 * Offline check of WallGuess on saved Android scans: at each plain-wall warning and tracking loss in scan-log.txt, which
 * wall would be filled (or why not), with a top view (grey: walls and furniture, amber: the rectangle, blue: the camera)
 * and a front view of the wall (its points, the rectangle). Pictures stay on the Mac (they show a home).
 *
 *   javac -d /tmp/wg android/app/src/main/java/com/hackathon/setscan/{WallGuess,AnchorCheck}.java tools/wall-guess/WallGuessCheck.java
 *   (AnchorCheck from tools/anchors, for its pose helper)
 *   java -cp /tmp/wg com.hackathon.setscan.WallGuessCheck <out dir> <scan folder>...
 */
public class WallGuessCheck {
    public static void main(String[] a) throws Exception { File out = new File(a[0]); out.mkdirs(); for (int i = 1; i < a.length; i++) run(new File(a[i]), out); }

    static void run(File dir, File out) throws Exception {
        int n; float[] p; int[] c;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(new File(dir, "points.bin")), 1 << 16))) {
            n = in.readInt(); p = new float[n * 3]; c = new int[n];
            for (int i = 0; i < n; i++) { p[i * 3] = in.readFloat(); p[i * 3 + 1] = in.readFloat(); p[i * 3 + 2] = in.readFloat(); c[i] = in.readUnsignedByte() << 16 | in.readUnsignedByte() << 8 | in.readUnsignedByte(); }
        }
        float[] tr; int tn;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(new File(dir, "pose-track.bin"))))) { tn = in.readInt(); tr = new float[tn * 13]; for (int i = 0; i < tr.length; i++) tr[i] = in.readFloat(); }
        String log = new String(java.nio.file.Files.readAllBytes(new File(dir, "scan-log.txt").toPath()));
        Matcher rm = Pattern.compile("room: floorY=(-?[\\d.]+) ceiling=([\\d.a-z]+)").matcher(log);
        float floorY = 0, ceil = 0; if (rm.find()) { floorY = Float.parseFloat(rm.group(1)); try { ceil = floorY + Float.parseFloat(rm.group(2)); } catch (Exception e) { ceil = 0; } }
        List<float[]> events = new ArrayList<>();
        if (System.getenv("WALL_EVERY") != null) {   // every few seconds along the walk, not only at warnings and losses
            float every = Float.parseFloat(System.getenv("WALL_EVERY"));
            for (float t = tr[0] / 1000f + every; t < tr[(tn - 1) * 13] / 1000f; t += every) events.add(new float[]{t, 2});
        } else {
        Matcher m = Pattern.compile("^\\s*([\\d.]+)s\\s+(plain-wall warning|tracking lost mid-scan|good spot \\(lost\\))", Pattern.MULTILINE).matcher(log);
        while (m.find()) events.add(new float[]{Float.parseFloat(m.group(1)), m.group(2).startsWith("plain") ? 0 : 1});
        }
        System.out.printf(Locale.US, "%s: %d points, floor %.2f, ceiling %s; %d warnings/losses%n", dir.getName(), n, floorY, ceil == 0 ? "unknown" : String.format(Locale.US, "%.2f m up", ceil - floorY), events.size());
        float lastT = -100;
        for (float[] e : events) {
            if (e[0] - lastT < 3) continue; lastT = e[0];
            int bi = -1; float bd = 1e9f; for (int i = 0; i < tn; i++) { if (tr[i * 13 + 1] < 1) continue; float dt = Math.abs(tr[i * 13] / 1000f - (e[0] - 0.5f)); if (dt < bd) { bd = dt; bi = i; } }
            if (bi < 0 || bd > 2) continue;
            float[] cam = AnchorCheck.corrected(tr, bi);
            StringBuilder why = new StringBuilder();
            WallGuess.Wall w = WallGuess.find(p, c, n, cam, floorY, ceil, why);
            String tag = String.format(Locale.US, "%.0fs", e[0]);
            if (w == null) { System.out.printf(Locale.US, "  %6.1f s %s: nothing filled (%s)%n", e[0], e[1] == 0 ? "warning" : e[1] == 1 ? "loss   " : "walk   ", why); continue; }
            System.out.printf(Locale.US, "  %6.1f s %s: wall %.2f m wide x %.2f m high, %.2f m from the camera, %d points on it, %.0f%% of its width already scanned, colour #%06x, brightness spread %.0f%n",
                    e[0], e[1] == 0 ? "warning" : e[1] == 1 ? "loss   " : "walk   ", w.width, w.height, w.distance, w.support, w.scannedShare * 100, w.rgb, w.lumSpread);
            pictures(new File(out, dir.getName() + "-" + tag + ".png"), p, c, n, floorY, w, cam);
        }
    }

    static void pictures(File f, float[] p, int[] c, int n, float floorY, WallGuess.Wall w, float[] cam) throws IOException {
        // top view, 3 cm per pixel, 8 m around the wall's middle
        float mx = (w.corners[0] + w.corners[3]) / 2, mz = (w.corners[2] + w.corners[5]) / 2;
        int S = 300; float sc = 0.025f; BufferedImage top = new BufferedImage(S, S, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) top.setRGB(x, y, 0xFFFFFF);
        for (int i = 0; i < n; i += 2) { float h = p[i * 3 + 1] - floorY; if (h < 0.3f || h > 1.9f) continue; int x = (int) ((p[i * 3] - mx) / sc) + S / 2, y = (int) ((p[i * 3 + 2] - mz) / sc) + S / 2; if (x >= 0 && y >= 0 && x < S && y < S) top.setRGB(x, y, 0x909090); }
        java.awt.Graphics2D g = top.createGraphics(); g.setStroke(new java.awt.BasicStroke(4)); g.setColor(new java.awt.Color(255, 176, 32));
        g.drawLine((int) ((w.corners[0] - mx) / sc) + S / 2, (int) ((w.corners[2] - mz) / sc) + S / 2, (int) ((w.corners[3] - mx) / sc) + S / 2, (int) ((w.corners[5] - mz) / sc) + S / 2);
        g.setColor(new java.awt.Color(40, 90, 220)); int cx = (int) ((cam[12] - mx) / sc) + S / 2, cz = (int) ((cam[14] - mz) / sc) + S / 2; g.fillOval(cx - 5, cz - 5, 10, 10);
        g.drawLine(cx, cz, cx + (int) (-cam[8] * 30), cz + (int) (-cam[10] * 30)); g.dispose();
        // front view of the wall: points within 15 cm of the plane, 2 cm per pixel, rectangle translucent on top
        float ux = w.nz, uz = -w.nx, x0 = w.corners[0], z0 = w.corners[2];
        int FW = (int) (w.width / 0.02f) + 80, FH = (int) (w.height / 0.02f) + 40;
        BufferedImage front = new BufferedImage(FW, FH, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < FH; y++) for (int x = 0; x < FW; x++) front.setRGB(x, y, 0x202020);
        for (int i = 0; i < n; i++) {
            float off = w.nx * p[i * 3] + w.nz * p[i * 3 + 2] - w.d; if (Math.abs(off) > 0.15f) continue;
            float s = (p[i * 3] - x0) * ux + (p[i * 3 + 2] - z0) * uz; int x = (int) (s / 0.02f) + 40, y = FH - 20 - (int) ((p[i * 3 + 1] - floorY) / 0.02f);
            if (x >= 0 && y >= 0 && x < FW && y < FH) front.setRGB(x, y, c[i]);
        }
        BufferedImage after = new BufferedImage(FW, FH, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g2 = after.createGraphics(); g2.setColor(new java.awt.Color(0x202020)); g2.fillRect(0, 0, FW, FH);
        g2.setColor(new java.awt.Color(w.rgb)); g2.fillRect(40, 20, FW - 80, FH - 40);
        for (int y = 0; y < FH; y++) for (int x = 0; x < FW; x++) { int v = front.getRGB(x, y) & 0xFFFFFF; if (v != 0x202020) after.setRGB(x, y, v); }
        g2.setColor(new java.awt.Color(255, 176, 32)); g2.setStroke(new java.awt.BasicStroke(3)); g2.drawRect(40, 20, FW - 80, FH - 40); g2.dispose();
        int W = S + 10 + FW * 2 + 10, H = Math.max(S, FH);
        BufferedImage all = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D ga = all.createGraphics(); ga.setColor(java.awt.Color.WHITE); ga.fillRect(0, 0, W, H);
        ga.drawImage(top, 0, 0, null); ga.drawImage(front, S + 10, 0, null); ga.drawImage(after, S + 20 + FW, 0, null); ga.dispose();
        javax.imageio.ImageIO.write(all, "png", f);
    }
}
