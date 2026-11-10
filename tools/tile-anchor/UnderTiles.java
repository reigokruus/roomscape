import java.io.*; import java.util.*; import java.util.regex.*; import java.awt.image.BufferedImage;
/** Offline (rk 10 Nov): how many of a scan's stored points lie under its saved tiles (within FRONT in front of a tile's
 *  plane and BACK behind it), and before/after pictures of the most-tiled plane. Reads only; writes the pictures to <out>.
 *    java UnderTiles.java <scan folder> <out folder> [min tile score] */
public class UnderTiles {
    static final float FRONT = 0.07f, BACK = 0.10f;
    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]); File out = new File(a[1]); out.mkdirs(); float minScore = a.length > 2 ? Float.parseFloat(a[2]) : 0;
        String js = new String(java.nio.file.Files.readAllBytes(new File(dir, "tiles.json").toPath()), "UTF-8");
        float TILE = Float.parseFloat(rx(js, "\"tile\":([0-9.Ee-]+)"));
        float[][] P = arrs(js.substring(js.indexOf("\"planes\""), js.indexOf("\"tiles\""))).toArray(new float[0][]);
        HashSet<Long> T = new HashSet<>(); int[] perPlane = new int[P.length];
        for (float[] t : arrs(js.substring(js.indexOf("\"tiles\"")))) if (t[4] >= minScore) { T.add(key((int) t[0], (int) t[1], (int) t[2])); perPlane[(int) t[0]]++; }
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(new File(dir, "points.bin"))));
        int n = in.readInt(); float[] xyz = new float[n * 3]; int[] rgb = new int[n];
        for (int i = 0; i < n; i++) { xyz[i * 3] = in.readFloat(); xyz[i * 3 + 1] = in.readFloat(); xyz[i * 3 + 2] = in.readFloat(); rgb[i] = (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte(); }
        boolean[] drop = new boolean[n]; int dropped = 0; int[] dropPer = new int[P.length];
        for (int i = 0; i < n; i++) {
            float x = xyz[i * 3], y = xyz[i * 3 + 1], z = xyz[i * 3 + 2];
            for (int pi = 0; pi < P.length; pi++) {
                float[] p = P[pi]; float dd = p[0] * x + p[1] * y + p[2] * z + p[3];
                if (dd > FRONT || dd < -BACK) continue;
                int iu = (int) Math.floor((p[4] * x + p[5] * y + p[6] * z) / TILE), iv = (int) Math.floor((p[7] * x + p[8] * y + p[9] * z) / TILE);
                if (T.contains(key(pi, iu, iv))) { drop[i] = true; dropped++; dropPer[pi]++; break; }
            }
        }
        long before = 4 + 15L * n, after = 4 + 15L * (n - dropped);
        System.out.printf(Locale.US, "%s: %d tiles (score >= %.2f) on %d planes; points %d -> %d (%.1f%% dropped), points.bin %.2f -> %.2f MB%n",
                dir.getName(), T.size(), minScore, (int) Arrays.stream(perPlane).filter(c -> c > 0).count(), n, n - dropped, 100.0 * dropped / n, before / 1e6, after / 1e6);
        // pictures: the plane with the most tiles, seen square-on from in front, points within 1 m in front and 0.3 m behind
        int best = 0; for (int i = 1; i < P.length; i++) if (perPlane[i] > perPlane[best]) best = i;
        float[] p = P[best]; float umin = 1e9f, umax = -1e9f, vmin = 1e9f, vmax = -1e9f;
        for (long k : T) if ((int) (k >>> 40) == best) { int iu = (int) ((k >>> 20) & 0xFFFFF) - (1 << 19), iv = (int) (k & 0xFFFFF) - (1 << 19);
            umin = Math.min(umin, iu * TILE); umax = Math.max(umax, (iu + 1) * TILE); vmin = Math.min(vmin, iv * TILE); vmax = Math.max(vmax, (iv + 1) * TILE); }
        umin -= 0.6f; umax += 0.6f; vmin -= 0.6f; vmax += 0.6f;
        int S = 300; int W = (int) ((umax - umin) * S), H = (int) ((vmax - vmin) * S);
        for (int pass = 0; pass < 2; pass++) {
            BufferedImage im = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB); float[] zb = new float[W * H]; Arrays.fill(zb, -1e9f);
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) im.setRGB(x, y, 0x202020);
            for (int i = 0; i < n; i++) {
                if (pass == 1 && drop[i]) continue;
                float X = xyz[i * 3], Y = xyz[i * 3 + 1], Z = xyz[i * 3 + 2], dd = p[0] * X + p[1] * Y + p[2] * Z + p[3];
                if (dd > 1.0f || dd < -0.3f) continue;
                int px = (int) (((p[4] * X + p[5] * Y + p[6] * Z) - umin) * S), py = H - 1 - (int) (((p[7] * X + p[8] * Y + p[9] * Z) - vmin) * S);
                for (int oy = 0; oy < 2; oy++) for (int ox = 0; ox < 2; ox++) { int qx = px + ox, qy = py + oy; if (qx < 0 || qy < 0 || qx >= W || qy >= H) continue;
                    if (dd > zb[qy * W + qx]) { zb[qy * W + qx] = dd; im.setRGB(qx, qy, rgb[i]); } }
            }
            if (pass == 1) for (long k : T) if ((int) (k >>> 40) == best) {   // the tile outlines, where the image would be
                int iu = (int) ((k >>> 20) & 0xFFFFF) - (1 << 19), iv = (int) (k & 0xFFFFF) - (1 << 19);
                int x0 = (int) ((iu * TILE - umin) * S), x1 = (int) (((iu + 1) * TILE - umin) * S), y1 = H - 1 - (int) ((iv * TILE - vmin) * S), y0 = H - 1 - (int) (((iv + 1) * TILE - vmin) * S);
                for (int x = x0; x <= x1; x++) for (int y : new int[]{y0, y1}) if (x >= 0 && x < W && y >= 0 && y < H) im.setRGB(x, y, 0xFFB020);
                for (int y = y0; y <= y1; y++) for (int x : new int[]{x0, x1}) if (x >= 0 && x < W && y >= 0 && y < H) im.setRGB(x, y, 0xFFB020);
            }
            javax.imageio.ImageIO.write(im, "png", new File(out, dir.getName() + (pass == 0 ? "-before.png" : "-after.png")));
        }
        System.out.printf(Locale.US, "  pictures: plane %d (%d tiles, %d points dropped there)%n", best, perPlane[best], dropPer[best]);
    }
    static long key(int p, int u, int v) { return ((long) p << 40) | ((long) (u + (1 << 19)) << 20) | (v + (1 << 19)); }
    static String rx(String s, String r) { Matcher m = Pattern.compile(r).matcher(s); m.find(); return m.group(1); }
    static ArrayList<float[]> arrs(String s) {
        ArrayList<float[]> out = new ArrayList<>(); Matcher m = Pattern.compile("\\[([^\\[\\]]+)\\]").matcher(s);
        while (m.find()) { String[] q = m.group(1).split(","); float[] v = new float[q.length]; for (int i = 0; i < q.length; i++) v[i] = Float.parseFloat(q[i].trim()); out.add(v); }
        return out;
    }
}
