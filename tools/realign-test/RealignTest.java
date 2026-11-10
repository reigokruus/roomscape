package com.hackathon.setscan;
import java.util.*;
public class RealignTest {
    public static void main(String[] a) {
        Random r = new Random(1);
        // A room 4 x 3 x 2.6 m: floor, 4 walls, a box, sampled every 2 cm with 5 mm noise.
        ArrayList<float[]> room = new ArrayList<>();
        for (float x = 0; x < 4; x += 0.02f) for (float z = 0; z < 3; z += 0.02f) room.add(new float[]{x, 0, z});
        for (float x = 0; x < 4; x += 0.02f) for (float y = 0; y < 2.6f; y += 0.02f) { room.add(new float[]{x, y, 0}); room.add(new float[]{x, y, 3}); }
        for (float z = 0; z < 3; z += 0.02f) for (float y = 0; y < 2.6f; y += 0.02f) { room.add(new float[]{0, y, z}); room.add(new float[]{4, y, z}); }
        for (float x = 2.5f; x < 3.3f; x += 0.02f) for (float y = 0; y < 0.9f; y += 0.02f) room.add(new float[]{x, y, 0.5f});
        float[] xyz = new float[room.size() * 3];
        for (int i = 0; i < room.size(); i++) for (int k = 0; k < 3; k++) xyz[i * 3 + k] = room.get(i)[k] + (float) r.nextGaussian() * 0.005f;
        Realign rl = new Realign(xyz, room.size());
        int fails = 0;
        float[][] cases = {{0.18f, 0, 0, 0}, {0.12f, 0.02f, -0.1f, 4}, {0, 0, 0, 0}, {-0.15f, 0, 0.15f, -6}, {0.25f, 0.03f, 0.1f, 8}};
        for (float[] c : cases) {
            // ARCore's shifted frame: true = T * ark, so ark = T^-1 * true. Make a "view" of the room's corner + box.
            double ang = Math.toRadians(c[3]); float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
            ArrayList<float[]> view = new ArrayList<>();
            for (float[] p : room) if (p[0] > 1.8f && p[2] < 1.6f && r.nextFloat() < 0.15f) {
                // apply inverse of (rotate by ang about y, then shift c[0..2]): ark = R^-1 (p - t)
                float x = p[0] - c[0], y = p[1] - c[1], z = p[2] - c[2];
                view.add(new float[]{ca * x + sa * z + (float) r.nextGaussian() * 0.006f, y + (float) r.nextGaussian() * 0.006f, -sa * x + ca * z + (float) r.nextGaussian() * 0.006f});
            }
            Realign.Fit f = rl.solve(view, Realign.identity());
            // Check: the fix should map each view point back onto the room: mean error over a few points.
            double err = 0; int n = 0;
            for (float[] p : room) if (p[0] > 1.8f && p[2] < 1.6f && n < 400 && r.nextFloat() < 0.02f) {
                float x = p[0] - c[0], y = p[1] - c[1], z = p[2] - c[2];
                float ax = ca * x + sa * z, ay = y, az = -sa * x + ca * z;
                float[] F = f.fix;
                float bx = F[0] * ax + F[4] * ay + F[8] * az + F[12], by = F[1] * ax + F[5] * ay + F[9] * az + F[13], bz = F[2] * ax + F[6] * ay + F[10] * az + F[14];
                err += Math.sqrt((bx - p[0]) * (bx - p[0]) + (by - p[1]) * (by - p[1]) + (bz - p[2]) * (bz - p[2])); n++;
            }
            err /= n;
            // Corrected, or safely not accepted (fusion stays paused); only a wrong fit that is accepted fails.
            boolean ok = err < 0.02 && Realign.good(f); boolean falseAccept = err >= 0.03 && Realign.good(f);
            if (falseAccept) { System.out.println("  FALSE ACCEPT"); fails++; }
            if (Math.abs(c[0]) == 0.18f && !ok) fails++; // rk's 08:20 case (18 cm) must be corrected
            System.out.printf("shift (%.2f, %.2f, %.2f) turn %.0f deg: corrected error %.1f cm, %d%% on scan, median %.1f cm -> %s%n", c[0], c[1], c[2], c[3], err * 100, Math.round(f.inliers * 100), f.median * 100, ok ? "corrected" : (Realign.good(f) ? "WRONG" : "not accepted (stays paused)"));
        }
        // A wrong place (looking at a part never scanned: shifted by 1.5 m): must not pass.
        ArrayList<float[]> stranger = new ArrayList<>();
        for (int i = 0; i < 2000; i++) stranger.add(new float[]{6 + r.nextFloat(), r.nextFloat() * 2, 6 + r.nextFloat()});
        Realign.Fit g = rl.solve(stranger, Realign.identity());
        boolean rejected = !Realign.good(g);
        System.out.printf("unscanned place: %d%% on scan, median %.1f cm -> %s%n", Math.round(g.inliers * 100), g.median * 100, rejected ? "rejected (ok)" : "ACCEPTED (bad)");
        System.exit(fails == 0 && rejected ? 0 : 1);
    }
}
