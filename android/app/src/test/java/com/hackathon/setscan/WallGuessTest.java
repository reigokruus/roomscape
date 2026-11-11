package com.hackathon.setscan;

import static org.junit.Assert.*;
import org.junit.Test;

public class WallGuessTest {
    /** A room corner seen from 2.5 m: wall z = -2.5 from x = -1.5 to 1.5, its middle 1.6 m empty (plain, no depth), the
     *  edges and skirting scanned; an opening from x = 1.5 to 2.4 with a far wall seen through it at z = -4. */
    static float[] scene(boolean opening, int[] nOut) {
        java.util.ArrayList<float[]> p = new java.util.ArrayList<>();
        java.util.Random r = new java.util.Random(3);
        for (int i = 0; i < 40000; i++) {
            float x = -1.5f + 3f * r.nextFloat(), y = 0.05f + 2.5f * r.nextFloat();
            boolean edge = Math.abs(x) > 0.8f || y < 0.15f || y > 2.4f;
            if (!edge) continue;
            p.add(new float[]{x, y, -2.5f + (r.nextFloat() - 0.5f) * 0.02f});
        }
        if (opening) for (int i = 0; i < 4000; i++) p.add(new float[]{1.5f + 0.9f * r.nextFloat(), 0.2f + 1.8f * r.nextFloat(), -4f});
        for (int i = 0; i < 20000; i++) p.add(new float[]{-2 + 4 * r.nextFloat(), 0f, -2.5f * r.nextFloat()});   // floor
        float[] a = new float[p.size() * 3]; for (int i = 0; i < p.size(); i++) System.arraycopy(p.get(i), 0, a, i * 3, 3);
        nOut[0] = p.size(); return a;
    }

    static float[] cam() { float[] m = new float[16]; m[0] = 1; m[5] = 1; m[10] = 1; m[15] = 1; m[13] = 1.4f; return m; }   // at the origin, looking along -z

    @Test public void fillsThePlainWallFromItsEdges() {
        int[] n = new int[1]; float[] p = scene(false, n);
        StringBuilder why = new StringBuilder();
        WallGuess.Wall w = WallGuess.find(p, null, n[0], cam(), 0f, 2.5f, why);
        assertNotNull(why.toString(), w);
        assertEquals(1f, Math.abs(w.nz), 0.01f);          // facing the camera
        assertEquals(2.5f, w.distance, 0.05f);
        assertEquals(3.0f, w.width, 0.25f);               // the whole wall, empty middle included
        assertEquals(2.5f, w.height, 0.01f);
    }

    @Test public void stopsAtAnOpening() {
        int[] n = new int[1]; float[] p = scene(true, n);
        WallGuess.Wall w = WallGuess.find(p, null, n[0], cam(), 0f, 2.5f, new StringBuilder());
        assertNotNull(w);
        float maxX = Math.max(w.corners[0], w.corners[3]);
        assertTrue("reaches into the opening: " + maxX, maxX < 1.65f);
    }

    @Test public void nothingWithoutAWall() {
        int[] n = new int[1]; java.util.Random r = new java.util.Random(1); float[] p = new float[30000 * 3];
        for (int i = 0; i < 30000; i++) { p[i * 3] = -3 + 6 * r.nextFloat(); p[i * 3 + 1] = 0; p[i * 3 + 2] = -4 * r.nextFloat(); }
        assertNull(WallGuess.find(p, null, 30000, cam(), 0f, 2.5f, new StringBuilder()));
    }
}
