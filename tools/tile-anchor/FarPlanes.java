import java.io.*; import java.util.*;
/** Offline (rk 10 Nov): would tiles from depth 3.5-4.5 m be good? For each saved depth frame (events-depth.bin), the
 *  main plane among the far samples (RANSAC, tolerance growing with distance squared), and how far that plane sits from
 *  the scan's own stored points there (median, cm) - the points are fused from many (also closer) looks.
 *    java FarPlanes.java <scan folder> */
public class FarPlanes {
    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(new File(dir, "points.bin"))));
        int n = in.readInt(); HashMap<Long, float[]> cells = new HashMap<>();   // 10 cm cells: sum x, y, z, count
        for (int i = 0; i < n; i++) { float x = in.readFloat(), y = in.readFloat(), z = in.readFloat(); in.skipBytes(3);
            long k = key(x, y, z); float[] c = cells.computeIfAbsent(k, kk -> new float[4]); c[0] += x; c[1] += y; c[2] += z; c[3]++; }
        DataInputStream e = new DataInputStream(new BufferedInputStream(new FileInputStream(new File(dir, "events-depth.bin"))));
        int k = e.readInt(); Random rnd = new Random(1);
        List<Float> devs = new ArrayList<>(); int frames = 0, withPlane = 0; long farSamples = 0, nearSamples = 0;
        for (int f = 0; f < k; f++) {
            e.readLong(); e.readLong(); float[] raw = new float[16], fix = new float[16];
            for (int i = 0; i < 16; i++) raw[i] = e.readFloat(); for (int i = 0; i < 16; i++) fix[i] = e.readFloat();
            float fx = e.readFloat(), fy = e.readFloat(), cx = e.readFloat(), cy = e.readFloat(); int w = e.readInt(), h = e.readInt();
            short[] d = new short[w * h]; for (int i = 0; i < w * h; i++) d[i] = e.readShort();
            frames++;
            float[] m = mul(fix, raw);
            List<float[]> far = new ArrayList<>();
            for (int v = 1; v < h; v += 2) for (int u = 1; u < w; u += 2) {
                int mm = d[v * w + u] & 0xFFFF; if (mm < 300) continue;
                if (mm <= 3500) { nearSamples++; continue; }
                if (mm > 4500) continue;
                float dd = mm * 0.001f, xc = (u + 0.5f - cx) * dd / fx, yc = (cy - (v + 0.5f)) * dd / fy, zc = -dd;
                far.add(new float[]{m[0] * xc + m[4] * yc + m[8] * zc + m[12], m[1] * xc + m[5] * yc + m[9] * zc + m[13], m[2] * xc + m[6] * yc + m[10] * zc + m[14], dd});
            }
            farSamples += far.size();
            if (far.size() < 150) continue;
            // RANSAC, inlier tolerance 3.5 cm at 2 m growing with distance squared (about 7-9 cm at 4 m)
            float[] best = null; int bestIn = 0;
            for (int it = 0; it < 150; it++) {
                float[] p = far.get(rnd.nextInt(far.size())), q = far.get(rnd.nextInt(far.size())), r = far.get(rnd.nextInt(far.size()));
                float[] nrm = cross(sub(q, p), sub(r, p)); float l = (float) Math.sqrt(dot(nrm, nrm)); if (l < 1e-6) continue;
                for (int j = 0; j < 3; j++) nrm[j] /= l; float dd = -dot(nrm, p); int inl = 0;
                for (float[] s : far) { float tol = 0.035f * (s[3] / 2) * (s[3] / 2); if (Math.abs(dot(nrm, s) + dd) < tol) inl++; }
                if (inl > bestIn) { bestIn = inl; best = new float[]{nrm[0], nrm[1], nrm[2], dd}; }
            }
            if (best == null || bestIn < far.size() * 0.4f) continue;
            withPlane++;
            // the stored scan's surface where the plane's inliers are: distance from the plane to the fused points (cm)
            for (float[] s : far) {
                float tol = 0.035f * (s[3] / 2) * (s[3] / 2);
                if (Math.abs(dot(best, s) + best[3]) >= tol) continue;
                float[] c = cells.get(key(s[0], s[1], s[2])); if (c == null || c[3] < 3) continue;
                float px = c[0] / c[3], py = c[1] / c[2 + 1] * 0 + c[1] / c[3], pz = c[2] / c[3];
                devs.add(Math.abs(best[0] * px + best[1] * py + best[2] * pz + best[3]) * 100);
            }
        }
        Collections.sort(devs);
        System.out.printf(Locale.US, "%s: %d frames; depth samples 0.3-3.5 m %d, 3.5-4.5 m %d (+%.0f%%); %d frames with a far plane; plane vs fused points: median %.1f cm, 80%% %.1f cm (%d checks)%n",
                dir.getName(), frames, nearSamples, farSamples, 100.0 * farSamples / Math.max(1, nearSamples), withPlane,
                devs.isEmpty() ? -1 : devs.get(devs.size() / 2), devs.isEmpty() ? -1 : devs.get((int) (devs.size() * 0.8)), devs.size());
    }
    static long key(float x, float y, float z) { return ((long) Math.floor(x / 0.1) + 100000) * 1000003L * 1000003L + ((long) Math.floor(y / 0.1) + 100000) * 1000003L + ((long) Math.floor(z / 0.1) + 100000); }
    static float[] sub(float[] a, float[] b) { return new float[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    static float dot(float[] a, float[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    static float[] cross(float[] a, float[] b) { return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }
    static float[] mul(float[] A, float[] B) { float[] C = new float[16]; for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) { float s = 0; for (int q = 0; q < 4; q++) s += A[q * 4 + i] * B[j * 4 + q]; C[j * 4 + i] = s; } return C; }
}
