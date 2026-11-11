package com.hackathon.setscan;

import static org.junit.Assert.*;
import org.junit.Test;

public class AnchorsTest {
    static final int W = 160, H = 90; static final float F = 120, CX = 80, CY = 45;

    /** Depth image (mm) of a scene given as a function from a camera ray (x, y at z = 1) to depth in m. */
    interface Scene { float depth(float rx, float ry); }
    static short[] img(Scene s) {
        short[] d = new short[W * H];
        for (int v = 0; v < H; v++) for (int u = 0; u < W; u++) d[v * W + u] = (short) Math.round(1000 * s.depth((u - CX) / F, (v - CY) / F));
        return d;
    }
    /** Depth to the plane n . p = c along the ray (rx, ry, 1). */
    static float plane(float rx, float ry, float nx, float ny, float nz, float c) { float t = c / (nx * rx + ny * ry + nz); return t > 0 ? t : 0; }

    @Test public void plainWallScoresZero() {
        float[] s = Anchors.shapeScore(img((rx, ry) -> plane(rx, ry, 0.2f, 0, 1, 2)), W, H, F, F, CX, CY);
        assertTrue("wall " + s[0], s[0] < 0.05f);
        assertEquals(2f, s[1], 0.3f);
    }

    @Test public void wallAndFloorScoresLow() {   // can slide along the corner line
        float[] s = Anchors.shapeScore(img((rx, ry) -> Math.min(plane(rx, ry, 0, 0, 1, 2), nz(plane(rx, ry, 0, 1, 0, 1.2f)))), W, H, F, F, CX, CY);
        assertTrue("wall+floor " + s[0], s[0] < Anchors.MIN_SHAPE);
    }

    @Test public void roomCornerScoresHigh() {   // two walls and the floor (two walls alone would slide up and down)
        float[] s = Anchors.shapeScore(img((rx, ry) -> Math.min(Math.min(nz(plane(rx, ry, 0.7f, 0, 0.7f, 1.6f)), nz(plane(rx, ry, -0.7f, 0, 0.7f, 1.6f))), nz(plane(rx, ry, 0, 1, 0, 0.5f)))), W, H, F, F, CX, CY);
        assertTrue("corner " + s[0], s[0] > Anchors.MIN_SHAPE);
    }

    static float nz(float t) { return t <= 0 ? 1e9f : t; }

    static float[] cam(float x, float z, float yawDeg) {   // camera -> world, looking along -z turned by yaw about +y
        double a = Math.toRadians(yawDeg); float c = (float) Math.cos(a), s = (float) Math.sin(a);
        float[] m = new float[16];
        m[0] = c; m[2] = -s; m[5] = 1; m[8] = s; m[10] = c; m[15] = 1;
        m[12] = x; m[13] = 1.4f; m[14] = z;
        return m;
    }

    @Test public void pickSkipsTheLossViewAndWeakSpots() {
        Anchors an = new Anchors();
        float[] good = {0.4f, 1.5f, 0.9f};
        Anchors.Anchor same = Anchors.make(cam(0, 0, 0), 10000, 200, good);           // looks where it was lost
        Anchors.Anchor side = Anchors.make(cam(0.5f, 0, 90), 20000, 150, good);       // to the side, near
        Anchors.Anchor far = Anchors.make(cam(6, 0, 180), 25000, 200, good);          // far away
        assertTrue(an.keep(same)); assertTrue(an.keep(side)); assertTrue(an.keep(far));
        assertFalse(Anchors.good(20, good));
        float[] o = new float[3];
        Anchors.Anchor p = an.pick(cam(0, 0, 0), 60000, new java.util.HashSet<>(), o);
        assertSame(side, p);
        java.util.Set<Anchors.Anchor> tried = new java.util.HashSet<>(); tried.add(side);
        assertSame(far, an.pick(cam(0, 0, 0), 60000, tried, o));
        assertNull("nothing kept in the 2 s before the loss", new Anchors().pick(cam(0, 0, 0), 1000, null, o));
    }
}
