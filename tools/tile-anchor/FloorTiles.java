import java.io.*; import java.util.*; import java.util.regex.*;
/** Offline check of a scan's floor tiles against its stored points (rk 10 Nov): per floor tile, the points on the floor
 *  (within 3 cm), standing on it (7 cm to 1.8 m above), and below it (reflections). A tile with things standing on it
 *  or no floor points at all is one the stricter floor rule would not paint.
 *    java FloorTiles.java <scan folder> */
public class FloorTiles {
    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        String js = new String(java.nio.file.Files.readAllBytes(new File(dir, "tiles.json").toPath()), "UTF-8");
        float T = Float.parseFloat(rx(js, "\"tile\":([0-9.Ee-]+)"));
        List<float[]> P = arrs(js.substring(js.indexOf("\"planes\""), js.indexOf("\"tiles\"")));
        int fl = -1; for (int i = 0; i < P.size(); i++) if (P.get(i)[1] > 0.999f) { fl = i; break; }
        if (fl < 0) { System.out.println(dir.getName() + ": no floor plane"); return; }
        float[] p = P.get(fl);
        Map<Long, int[]> cnt = new HashMap<>(); List<long[]> floorTiles = new ArrayList<>();
        for (float[] t : arrs(js.substring(js.indexOf("\"tiles\"")))) if ((int) t[0] == fl) { long k = ((long) (t[1] + 100000) << 20) | (long) (t[2] + 100000); cnt.put(k, new int[3]); floorTiles.add(new long[]{k}); }
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(new File(dir, "points.bin"))));
        int n = in.readInt();
        for (int i = 0; i < n; i++) {
            float x = in.readFloat(), y = in.readFloat(), z = in.readFloat(); in.skipBytes(3);
            float s = y + p[3];
            int iu = (int) Math.floor((p[4] * x + p[6] * z) / T), iv = (int) Math.floor((p[7] * x + p[9] * z) / T);
            int[] c = cnt.get(((long) (iu + 100000) << 20) | (long) (iv + 100000)); if (c == null) continue;
            if (Math.abs(s) < 0.03f) c[0]++; else if (s > 0.07f && s < 1.8f) c[1]++; else if (s < -0.03f) c[2]++;
        }
        int standing = 0, noFloor = 0, refl = 0;
        for (int[] c : cnt.values()) { if (c[1] > Math.max(20, 0.2 * c[0])) standing++; else if (c[0] < 20) noFloor++; if (c[2] > c[0]) refl++; }
        System.out.printf(Locale.US, "%s: %d floor tiles; %d with something standing on them, %d more with (almost) no floor points; %d with more points below the floor than on it%n",
                dir.getName(), cnt.size(), standing, noFloor, refl);
    }
    static String rx(String s, String r) { Matcher m = Pattern.compile(r).matcher(s); m.find(); return m.group(1); }
    static ArrayList<float[]> arrs(String s) {
        ArrayList<float[]> out = new ArrayList<>(); Matcher m = Pattern.compile("\\[([^\\[\\]]+)\\]").matcher(s);
        while (m.find()) { String[] q = m.group(1).split(","); float[] v = new float[q.length]; for (int i = 0; i < q.length; i++) v[i] = Float.parseFloat(q[i].trim()); out.add(v); }
        return out;
    }
}
