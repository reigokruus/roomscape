package com.hackathon.setscan;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * The fitted walls and floor as 8 cm cells, set 2 cm behind the real surface so the fused mesh wins where it exists.
 * Each cell also gets a fallback colour (the typical colour of that wall / the floor, from the scanned points), used
 * when no photo confirms the cell, so the room reads as solid instead of full of holes. Guessed (unscanned) walls get
 * alpha 0, which means "drop if no photo covers it".
 */
final class RoomProxies {
    float[] tris = new float[0];
    byte[] cols = new byte[0]; // 4 bytes per vertex

    static RoomProxies build(RoomModel room, FloatBuffer pos, ByteBuffer col, int count) {
        RoomProxies r = new RoomProxies();
        if (!room.hasLayout) return r;
        final float C = 0.08f, BACK = 0.02f;
        ArrayList<float[]> out = new ArrayList<>();
        ArrayList<int[]> oc = new ArrayList<>();
        float y0 = room.floorY, y1 = room.topY;
        for (RoomModel.Wall w : room.layout) {
            int[] c = medianColor(pos, col, count, w, room.floorY, y1);
            if (!w.seen || c == null) c = c == null ? new int[]{200, 200, 200, 0} : new int[]{c[0], c[1], c[2], 0};
            float lx = w.x1 - w.x0, lz = w.z1 - w.z0, len = (float) Math.sqrt(lx * lx + lz * lz);
            int nu = Math.max(1, Math.round(len / C)), nv = Math.max(1, Math.round((y1 - y0) / C));
            float ox = w.nx * BACK, oz = w.nz * BACK;
            for (int i = 0; i < nu; i++) for (int j = 0; j < nv; j++) {
                float a0 = (float) i / nu, a1 = (float) (i + 1) / nu;
                float xa = w.x0 + lx * a0 + ox, za = w.z0 + lz * a0 + oz, xb = w.x0 + lx * a1 + ox, zb = w.z0 + lz * a1 + oz;
                float ya = y0 + (y1 - y0) * j / nv, yb = y0 + (y1 - y0) * (j + 1) / nv;
                out.add(new float[]{xa, ya, za, xb, ya, zb, xb, yb, zb, xa, ya, za, xb, yb, zb, xa, yb, za});
                // walls fade slightly towards the top so the filled colour doesn't look like flat paint
                int[] cc = c.clone();
                float shade = 1f - 0.12f * (j + 0.5f) / nv;
                for (int k = 0; k < 3; k++) cc[k] = Math.round(cc[k] * shade);
                oc.add(cc);
            }
        }
        RoomModel.Wall w0 = room.layout.get(0), w3 = room.layout.get(3);
        int[] fc = medianColor(pos, col, count, null, room.floorY, y1);
        if (fc == null) fc = new int[]{120, 100, 80, 0};
        float ax = w0.x0, az = w0.z0, ux = w0.x1 - w0.x0, uz = w0.z1 - w0.z0, vx = w3.x0 - w0.x0, vz = w3.z0 - w0.z0;
        float lu = (float) Math.sqrt(ux * ux + uz * uz), lv = (float) Math.sqrt(vx * vx + vz * vz);
        int nu = Math.max(1, Math.round(lu / C)), nv = Math.max(1, Math.round(lv / C));
        float fy = y0 - BACK;
        for (int i = 0; i < nu; i++) for (int j = 0; j < nv; j++) {
            float a0 = (float) i / nu, a1 = (float) (i + 1) / nu, b0 = (float) j / nv, b1 = (float) (j + 1) / nv;
            float[] p00 = {ax + ux * a0 + vx * b0, az + uz * a0 + vz * b0}, p10 = {ax + ux * a1 + vx * b0, az + uz * a1 + vz * b0};
            float[] p11 = {ax + ux * a1 + vx * b1, az + uz * a1 + vz * b1}, p01 = {ax + ux * a0 + vx * b1, az + uz * a0 + vz * b1};
            out.add(new float[]{p00[0], fy, p00[1], p10[0], fy, p10[1], p11[0], fy, p11[1], p00[0], fy, p00[1], p11[0], fy, p11[1], p01[0], fy, p01[1]});
            oc.add(fc);
        }
        r.tris = new float[out.size() * 18];
        r.cols = new byte[out.size() * 6 * 4];
        for (int i = 0; i < out.size(); i++) {
            System.arraycopy(out.get(i), 0, r.tris, i * 18, 18);
            int[] c = oc.get(i);
            for (int v = 0; v < 6; v++) for (int k = 0; k < 4; k++) r.cols[(i * 6 + v) * 4 + k] = (byte) c[k];
        }
        return r;
    }

    /** Median colour of points on this wall (within 6 cm of its plane, between 0.3 m and the top), or on the floor when w == null. */
    private static int[] medianColor(FloatBuffer pos, ByteBuffer col, int count, RoomModel.Wall w, float floorY, float topY) {
        int step = Math.max(1, count / 200000);
        int[][] ch = new int[3][count / step + 1];
        int n = 0;
        for (int i = 0; i < count; i += step) {
            float x = pos.get(i * 3), y = pos.get(i * 3 + 1), z = pos.get(i * 3 + 2);
            if (w == null) {
                if (Math.abs(y - floorY) > 0.04f) continue;
            } else {
                if (y < floorY + 0.3f || y > topY) continue;
                float d = (x - w.x0) * w.nx + (z - w.z0) * w.nz;
                if (Math.abs(d) > 0.06f) continue;
                float lx = w.x1 - w.x0, lz = w.z1 - w.z0, len2 = lx * lx + lz * lz;
                float t = ((x - w.x0) * lx + (z - w.z0) * lz) / len2;
                if (t < 0 || t > 1) continue;
            }
            for (int k = 0; k < 3; k++) ch[k][n] = col.get(i * 4 + k) & 0xFF;
            n++;
        }
        if (n < 50) return null;
        int[] c = new int[4];
        for (int k = 0; k < 3; k++) { Arrays.sort(ch[k], 0, n); c[k] = ch[k][n / 2]; }
        c[3] = 255;
        return c;
    }
}
