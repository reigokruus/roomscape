import java.awt.image.BufferedImage; import java.io.File; import java.util.*; import java.util.regex.*;
/** A second reader of the .rmsc's shared tiles.json (docs/rmsc-format.md), only from that file and tiles.png: draws
 *  the tiles of the most common facing, square-on, so the corner/pixel mapping can be checked by eye.
 *    java DrawSharedTiles.java <tiles.json> <tiles.png> <out.png> [facing: 0 = most common, 1 = next, ...] */
public class DrawSharedTiles {
    public static void main(String[] a) throws Exception {
        String js = new String(java.nio.file.Files.readAllBytes(new File(a[0]).toPath()), "UTF-8");
        BufferedImage img = javax.imageio.ImageIO.read(new File(a[1]));
        int pick = a.length > 3 ? Integer.parseInt(a[3]) : 0;
        Matcher m = Pattern.compile("\\{[^{}]*\"corners\"[^{}]*\\}").matcher(js);   // each tile object (keys in any order)
        List<double[]> C = new ArrayList<>(), P = new ArrayList<>(); List<String> N = new ArrayList<>(); Map<String, Integer> per = new HashMap<>();
        while (m.find()) { String o = m.group();
            String c = field(o, "corners"), px = field(o, "pixels"), nn = field(o, "normal");
            C.add(nums(c)); P.add(nums(px)); N.add(nn); per.merge(nn, 1, Integer::sum); }
        List<String> facings = new ArrayList<>(per.keySet()); facings.sort((x, y) -> per.get(y) - per.get(x));
        String best = facings.get(pick); double[] n = nums(best);
        double[] u = Math.abs(n[1]) > 0.9 ? new double[]{1, 0, 0} : new double[]{-n[2], 0, n[0]};
        double ul = Math.hypot(u[0], u[2]); u[0] /= ul; u[2] /= ul;
        double[] v = {n[1] * u[2] - n[2] * u[1], n[2] * u[0] - n[0] * u[2], n[0] * u[1] - n[1] * u[0]};
        double S = 300, minU = 1e9, minV = 1e9, maxU = -1e9, maxV = -1e9;
        for (int i = 0; i < C.size(); i++) if (N.get(i).equals(best)) for (int k = 0; k < 4; k++) {
            double pu = dot(C.get(i), k, u), pv = dot(C.get(i), k, v); minU = Math.min(minU, pu); maxU = Math.max(maxU, pu); minV = Math.min(minV, pv); maxV = Math.max(maxV, pv); }
        int W = (int) ((maxU - minU) * S) + 2, H = (int) ((maxV - minV) * S) + 2;
        BufferedImage im = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < C.size(); i++) if (N.get(i).equals(best)) {
            double[][] q = new double[4][2];
            for (int k = 0; k < 4; k++) { q[k][0] = (dot(C.get(i), k, u) - minU) * S; q[k][1] = H - 1 - (dot(C.get(i), k, v) - minV) * S; }
            double[] p = P.get(i); int R = 100;
            for (int sy = 0; sy < R; sy++) for (int sx = 0; sx < R; sx++) {
                double s = (sx + 0.5) / R, t = (sy + 0.5) / R;
                double X = (1 - s) * (1 - t) * q[0][0] + s * (1 - t) * q[1][0] + s * t * q[2][0] + (1 - s) * t * q[3][0];
                double Y = (1 - s) * (1 - t) * q[0][1] + s * (1 - t) * q[1][1] + s * t * q[2][1] + (1 - s) * t * q[3][1];
                double ix = (1 - s) * (1 - t) * p[0] + s * (1 - t) * p[2] + s * t * p[4] + (1 - s) * t * p[6];
                double iy = (1 - s) * (1 - t) * p[1] + s * (1 - t) * p[3] + s * t * p[5] + (1 - s) * t * p[7];
                int x = (int) X, y = (int) Y; if (x < 0 || y < 0 || x >= W || y >= H) continue;
                im.setRGB(x, y, img.getRGB(Math.min(img.getWidth() - 1, (int) ix), Math.min(img.getHeight() - 1, (int) iy)));
            }
        }
        javax.imageio.ImageIO.write(im, "png", new File(a[2]));
        System.out.println(per.get(best) + " tiles facing " + best + " of " + C.size());
    }
    static String field(String o, String k) { Matcher m = Pattern.compile("\"" + k + "\":(\\[(?:\\[[^\\]]*\\],?)+\\]|\\[[^\\]]*\\])").matcher(o); m.find(); return m.group(1); }
    static double dot(double[] c, int k, double[] u) { return c[k * 3] * u[0] + c[k * 3 + 1] * u[1] + c[k * 3 + 2] * u[2]; }
    static double[] nums(String s) { String[] p = s.replaceAll("[\\[\\]]", "").split(","); double[] v = new double[p.length]; for (int i = 0; i < p.length; i++) v[i] = Double.parseDouble(p[i].trim()); return v; }
}
