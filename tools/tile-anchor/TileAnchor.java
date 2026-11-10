import java.io.*; import java.util.*; import java.util.regex.*;
/** Shadow check of tile anchoring, offline (rk 10 Nov): each saved depth frame around a loss or jump (events-depth.bin)
 *  is fitted to the scan's painted tiles only (tiles.json: flat walls and the level floor), with a 4-DOF correction
 *  (turn about the vertical + shift). Prints, per frame: how well the correction in use sits on the tiles, and the
 *  correction the tiles alone would give, starting from the one in use and from the one before the loss.
 *    java TileAnchor.java <scan folder> [only planes below this index] */
public class TileAnchor {
    static float TILE; static float[][] P; static HashSet<Long> T = new HashSet<>();
    static long key(int p, int u, int v) { return ((long) p << 40) | ((long) (u + (1 << 19)) << 20) | (v + (1 << 19)); }

    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        String js = new String(java.nio.file.Files.readAllBytes(new File(dir, "tiles.json").toPath()), "UTF-8");
        TILE = Float.parseFloat(rx(js, "\"tile\":([0-9.Ee-]+)"));
        ArrayList<float[]> pl = arrs(js.substring(js.indexOf("\"planes\""), js.indexOf("\"tiles\"")));
        P = pl.toArray(new float[0][]);
        int maxPlane = a.length > 1 ? Integer.parseInt(a[1]) : Integer.MAX_VALUE;   // only the planes found before then (planes are appended in order)
        for (float[] t : arrs(js.substring(js.indexOf("\"tiles\"")))) if (t[0] < maxPlane) T.add(key((int) t[0], (int) t[1], (int) t[2]));
        System.out.printf(Locale.US, "%d planes, %d tiles%n", P.length, T.size());
        DataInputStream e = new DataInputStream(new BufferedInputStream(new FileInputStream(new File(dir, "events-depth.bin"))));
        int k = e.readInt(); float[] before = null; long lastMs = -99999;
        for (int f = 0; f < k; f++) {
            e.readLong(); long ms = e.readLong(); float[] raw = new float[16], fix = new float[16];
            for (int i = 0; i < 16; i++) raw[i] = e.readFloat(); for (int i = 0; i < 16; i++) fix[i] = e.readFloat();
            float fx = e.readFloat(), fy = e.readFloat(), cx = e.readFloat(), cy = e.readFloat(); int w = e.readInt(), h = e.readInt();
            short[] d = new short[w * h]; for (int i = 0; i < w * h; i++) d[i] = e.readShort();
            if (ms - lastMs > 4000) { before = fix.clone(); System.out.println("-- event"); }   // a new loss: the correction in use before it
            lastMs = ms;
            ArrayList<float[]> pts = new ArrayList<>();
            for (int v = 2; v < h - 2; v += 2) for (int u = 2; u < w - 2; u += 2) {
                int mm = d[v * w + u] & 0xFFFF; if (mm < 300 || mm > 4000) continue;
                float dd = mm * 0.001f, xc = (u + 0.5f - cx) * dd / fx, yc = (cy - (v + 0.5f)) * dd / fy, zc = -dd;
                pts.add(new float[]{raw[0] * xc + raw[4] * yc + raw[8] * zc + raw[12], raw[1] * xc + raw[5] * yc + raw[9] * zc + raw[13], raw[2] * xc + raw[6] * yc + raw[10] * zc + raw[14]});
            }
            if (pts.size() < 100) { System.out.printf(Locale.US, "%6.1fs: %d samples%n", ms / 1000f, pts.size()); continue; }
            float[] onUse = score(pts, fix);
            float[] A = solve(pts, fix), B = solve(pts, before);
            System.out.printf(Locale.US, "%6.1fs: in use %s on tiles %2.0f%% (%d)  | tiles from in-use: %s %s | tiles from before-loss: %s %s%n",
                    ms / 1000f, desc(fix), onUse[0] * 100, (int) onUse[1], A == null ? "-" : desc(A), A == null ? "" : String.format(Locale.US, "%2.0f%% %s", score(pts, A)[0] * 100, cond),
                    B == null ? "-" : desc(B), B == null ? "" : String.format(Locale.US, "%2.0f%% %s", score(pts, B)[0] * 100, cond));
        }
    }
    static String cond = "";
    static String desc(float[] F) { return String.format(Locale.US, "[%+.2f %+.2f %+.2f m %+5.1f°]", F[12], F[13], F[14], Math.toDegrees(Math.atan2(F[2], F[0]))); }

    /** The tile plane a point lies on (within tol), or -1. */
    static int onTile(float x, float y, float z, float tol) {
        int best = -1; float bd = tol;
        for (int i = 0; i < P.length; i++) {
            float[] p = P[i]; float dd = Math.abs(p[0] * x + p[1] * y + p[2] * z + p[3]); if (dd >= bd) continue;
            int iu = (int) Math.floor((p[4] * x + p[5] * y + p[6] * z) / TILE), iv = (int) Math.floor((p[7] * x + p[8] * y + p[9] * z) / TILE);
            if (T.contains(key(i, iu, iv))) { best = i; bd = dd; }
        }
        return best;
    }
    static float[] apply(float[] F, float[] q) { return new float[]{F[0] * q[0] + F[4] * q[1] + F[8] * q[2] + F[12], F[1] * q[0] + F[5] * q[1] + F[9] * q[2] + F[13], F[2] * q[0] + F[6] * q[1] + F[10] * q[2] + F[14]}; }
    static float[] score(List<float[]> pts, float[] F) {
        int on = 0; for (float[] q : pts) { float[] r = apply(F, q); if (onTile(r[0], r[1], r[2], 0.03f) >= 0) on++; }
        return new float[]{(float) on / pts.size(), on};
    }
    /** Point-to-plane Gauss-Newton on the tiles, 4 DOF (yaw about +y through the samples' middle, x, y, z). */
    static float[] solve(List<float[]> pts, float[] F0) {
        float[] F = F0.clone(); float[] tols = {0.25f, 0.15f, 0.08f, 0.05f, 0.04f, 0.03f, 0.03f, 0.03f};
        for (float tol : tols) {
            double[][] H = new double[4][4]; double[] g = new double[4]; int n = 0; double cxm = 0, czm = 0;
            ArrayList<float[]> rr = new ArrayList<>(); for (float[] q : pts) rr.add(apply(F, q));
            for (float[] r : rr) { cxm += r[0]; czm += r[2]; } cxm /= rr.size(); czm /= rr.size();
            for (float[] r : rr) {
                int i = onTile(r[0], r[1], r[2], tol); if (i < 0) continue; float[] p = P[i];
                double res = p[0] * r[0] + p[1] * r[1] + p[2] * r[2] + p[3];
                // d(point)/d(yaw) about +y through (cxm, czm): (dz, 0, -dx)
                double dx = r[0] - cxm, dz = r[2] - czm, j0 = p[0] * dz - p[2] * dx;
                double[] J = {j0, p[0], p[1], p[2]};
                double wgt = 1 / (1 + (res * res) / (0.02 * 0.02));   // robust
                for (int x = 0; x < 4; x++) { g[x] += wgt * J[x] * res; for (int y = 0; y < 4; y++) H[x][y] += wgt * J[x] * J[y]; }
                n++;
            }
            if (n < 60) return null;
            // observability: the weakest direction of the shift (walls in only one direction leave a slide along them)
            double[] ev = eig3(new double[][]{{H[1][1], H[1][2], H[1][3]}, {H[2][1], H[2][2], H[2][3]}, {H[3][1], H[3][2], H[3][3]}});
            cond = String.format(Locale.US, "n%d weak%.0f%%", n, 100 * ev[0] / Math.max(1e-9, ev[2]));
            for (int x = 0; x < 4; x++) H[x][x] += 1e-3 * n;   // damping
            double[] s = solve4(H, g); for (int x = 0; x < 4; x++) s[x] = -s[x];
            float c = (float) Math.cos(s[0]), sn = (float) Math.sin(s[0]);
            // D = translate(t) * T(c) * Ry(yaw) * T(-c); world after = D * F
            float[] D = new float[16]; D[0] = c; D[2] = -sn; D[8] = sn; D[10] = c; D[5] = 1; D[15] = 1;
            D[12] = (float) (cxm - c * cxm - sn * czm + s[1]); D[13] = (float) s[2]; D[14] = (float) (czm + sn * cxm - c * czm + s[3]);
            F = mul(D, F);
        }
        return F;
    }
    static float[] mul(float[] A, float[] B) { float[] C = new float[16]; for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) { float s = 0; for (int k = 0; k < 4; k++) s += A[k * 4 + i] * B[j * 4 + k]; C[j * 4 + i] = s; } return C; }
    static double[] solve4(double[][] H, double[] g) {
        int n = 4; double[][] M = new double[n][n + 1]; for (int i = 0; i < n; i++) { System.arraycopy(H[i], 0, M[i], 0, n); M[i][n] = g[i]; }
        for (int c = 0; c < n; c++) { int p = c; for (int r = c + 1; r < n; r++) if (Math.abs(M[r][c]) > Math.abs(M[p][c])) p = r; double[] t = M[c]; M[c] = M[p]; M[p] = t;
            for (int r = 0; r < n; r++) if (r != c) { double f = M[r][c] / M[c][c]; for (int k = c; k <= n; k++) M[r][k] -= f * M[c][k]; } }
        double[] x = new double[n]; for (int i = 0; i < n; i++) x[i] = M[i][n] / M[i][i]; return x;
    }
    static double[] eig3(double[][] A) {   // Jacobi, sorted ascending
        double[][] a = new double[3][3]; for (int i = 0; i < 3; i++) a[i] = A[i].clone();
        for (int it = 0; it < 30; it++) for (int p = 0; p < 3; p++) for (int q = p + 1; q < 3; q++) {
            if (Math.abs(a[p][q]) < 1e-12) continue; double th = 0.5 * Math.atan2(2 * a[p][q], a[q][q] - a[p][p]), c = Math.cos(th), s = Math.sin(th);
            for (int k = 0; k < 3; k++) { double kp = a[k][p], kq = a[k][q]; a[k][p] = c * kp - s * kq; a[k][q] = s * kp + c * kq; }
            for (int k = 0; k < 3; k++) { double pk = a[p][k], qk = a[q][k]; a[p][k] = c * pk - s * qk; a[q][k] = s * pk + c * qk; }
        }
        double[] e = {a[0][0], a[1][1], a[2][2]}; Arrays.sort(e); return e;
    }
    static String rx(String s, String r) { Matcher m = Pattern.compile(r).matcher(s); m.find(); return m.group(1); }
    static ArrayList<float[]> arrs(String s) {
        ArrayList<float[]> out = new ArrayList<>(); Matcher m = Pattern.compile("\\[([^\\[\\]]+)\\]").matcher(s);
        while (m.find()) { String[] p = m.group(1).split(","); float[] v = new float[p.length]; for (int i = 0; i < p.length; i++) v[i] = Float.parseFloat(p[i].trim()); out.add(v); }
        return out;
    }
}
