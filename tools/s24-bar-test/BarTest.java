package com.hackathon.setscan;
import java.util.Random;
public class BarTest {
    // Wall z=0, bar along x at y=1.0, z=0.20, radius 1.5 cm. Camera looks along -z.
    static final float BY = 1.0f, BZ = 0.20f, BR = 0.015f;
    static float hit(float ox, float oy, float oz, float dx, float dy, float dz) {
        // bar: cylinder along x: (y-BY)^2+(z-BZ)^2=BR^2
        float py = oy - BY, pz = oz - BZ;
        float a = dy * dy + dz * dz, b = 2 * (py * dy + pz * dz), c = py * py + pz * pz - BR * BR;
        float disc = b * b - 4 * a * c, t = Float.MAX_VALUE;
        if (a > 1e-9 && disc >= 0) { float t0 = (float) ((-b - Math.sqrt(disc)) / (2 * a)); if (t0 > 0) t = t0; }
        float tw = dz < 0 ? -oz / dz : Float.MAX_VALUE;
        return Math.min(t, tw);
    }
    static int run(boolean limit, boolean closePass) {
        Tsdf t = new Tsdf(1_500_000, 0.04f, 0.12f, 99f);
        t.minW = 3; t.carveWeight = 2f;
        int W = 160, H = 90; float fx = 120, cx = 80, cy = 45;
        Random r = new Random(3);
        int frames = 0;
        for (int pass = 0; pass < (closePass ? 2 : 1); pass++) {
            float camZ = pass == 0 ? 1.0f : 0.45f;
            int n = pass == 0 ? 40 : 120;
            for (int f = 0; f < n; f++) {
                float ox = -0.3f + 0.6f * f / n, oy = BY + (r.nextFloat() - 0.5f) * 0.1f, oz = camZ;
                float[] depth = new float[W * H];
                for (int v = 0; v < H; v++) for (int u = 0; u < W; u++) {
                    float dx = (u + 0.5f - cx) / fx, dy = (cy - (v + 0.5f)) / fx, dz = -1;
                    float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    float tt = hit(ox, oy, oz, dx / l, dy / l, dz / l);
                    depth[v * W + u] = tt * (1 / l) + (float) r.nextGaussian() * 0.003f; // z depth
                }
                t.beginFrame();
                int[] rgb = {200, 200, 200};
                for (int v = 1; v < H - 1; v++) for (int u = 1; u < W - 1; u++) {
                    float d = depth[v * W + u];
                    float tol = d * 0.04f + 0.02f;
                    if (Math.abs(depth[v * W + u - 1] - d) > tol || Math.abs(depth[v * W + u + 1] - d) > tol || Math.abs(depth[(v - 1) * W + u] - d) > tol || Math.abs(depth[(v + 1) * W + u] - d) > tol) continue;
                    float xc = (u + 0.5f - cx) * d / fx, yc = (cy - (v + 0.5f)) * d / fx, zc = -d;
                    float clear = Float.MAX_VALUE;
                    if (limit) {
                        int rpx = Math.max(1, Math.min(6, Math.round(0.6f * t.vs * fx / d)));
                        float near = d;
                        for (int ny = -1; ny <= 1; ny++) for (int nx = -1; nx <= 1; nx++) {
                            int uu = u + nx * rpx, vv = v + ny * rpx;
                            if (uu < 0 || vv < 0 || uu >= W || vv >= H) continue;
                            float m2 = depth[vv * W + uu];
                            if (m2 >= 0.2f && m2 < near) near = m2;
                        }
                        clear = (float) Math.sqrt(xc * xc + yc * yc + zc * zc) * near / d;
                    }
                    t.integrate(ox, oy, oz, ox + xc, oy + yc, oz + zc, rgb, clear);
                }
                frames++;
            }
        }
        t.extractMesh();
        int near = 0;
        for (int i = 0; i < t.meshVerts; i++) {
            float y = t.meshPos.get(i * 3 + 1), z = t.meshPos.get(i * 3 + 2), x = t.meshPos.get(i * 3);
            if (Math.abs(x) < 0.3f && Math.abs(y - BY) < 0.06f && Math.abs(z - BZ) < 0.06f) near++;
        }
        return near;
    }
    public static void main(String[] a) {
        System.out.println("bar mesh vertices after the 1 m pass only:        " + run(false, false));
        System.out.println("after 1 m + close-up pass, no limit (before fix): " + run(false, true));
        System.out.println("after 1 m + close-up pass, with limit (fix):      " + run(true, true));
    }
}
