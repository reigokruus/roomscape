package com.hackathon.setscan;
import java.io.*; import java.util.*; import java.awt.image.BufferedImage;
/** DoorRays on saved S24 scans' doorways: <scan>[::doorways.json]... */
public class RayTest {
    public static void main(String[] a) throws Exception {
        EdgeRefine.MIN_CORNERS = Integer.parseInt(System.getenv().getOrDefault("MINC", "5")); EdgeRefine.RULE = Integer.parseInt(System.getenv().getOrDefault("RULE", "0"));
        for (String s : a) { String[] p = s.split("::"); run(new File(p[0]), p.length > 1 ? new File(p[1]) : null); } }
    static void run(File dir, File over) throws Exception {
        File dj = over != null ? over : new File(dir, "doorways.json");
        if (!dj.exists()) { System.out.println(dir.getName() + ": no doorways.json"); return; }
        String js = new String(java.nio.file.Files.readAllBytes(dj.toPath()));
        List<EdgeRefine.Photo> ph = new ArrayList<>(); List<DoorRays.View> vs = new ArrayList<>();
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(new File(dir, "kf.bin"))));
        int nk = in.readInt();
        for (int i = 0; i < nk; i++) {
            float[] view = new float[16], pose = new float[16];
            for (int k = 0; k < 16; k++) view[k] = in.readFloat();
            for (int k = 0; k < 16; k++) pose[k] = in.readFloat();
            float fx = in.readFloat(), fy = in.readFloat(), cx = in.readFloat(), cy = in.readFloat(), w = in.readFloat(), h = in.readFloat();
            in.readBoolean(); int dw = in.readInt(), dh = in.readInt(); float dfx = in.readFloat(), dfy = in.readFloat(), dcx = in.readFloat(), dcy = in.readFloat(); short[] dep = new short[dw * dh]; for (int k = 0; k < dw * dh; k++) dep[k] = in.readShort();
            File jf = new File(dir, String.format(Locale.US, "kf/%03d.jpg", i));
            BufferedImage img = jf.exists() ? javax.imageio.ImageIO.read(jf) : null; if (img == null) continue;
            EdgeRefine.Photo p = new EdgeRefine.Photo(); p.pose = pose; p.view = OutlineFill.invert(pose);
            float sx = img.getWidth() / w, sy = img.getHeight() / h;
            p.fx = fx * sx; p.fy = fy * sy; p.cx = cx * sx; p.cy = cy * sy; p.w = img.getWidth(); p.h = img.getHeight();
            int[] rgb = img.getRGB(0, 0, p.w, p.h, null, 0, p.w); p.grey = new byte[p.w * p.h];
            for (int j = 0; j < rgb.length; j++) { int c = rgb[j]; p.grey[j] = (byte) ((((c >> 16) & 255) * 3 + ((c >> 8) & 255) * 6 + (c & 255)) / 10); }
            if (dw * dh > 0) { DoorRays.View dv = new DoorRays.View(); dv.pose = pose; dv.view = OutlineFill.invert(pose); dv.depth = dep; dv.dw = dw; dv.dh = dh; dv.dfx = dfx; dv.dfy = dfy; dv.dcx = dcx; dv.dcy = dcy; vs.add(dv); }
            if (dw * dh > 0 && System.getenv("NODEPTH") == null) { p.depth = dep; p.dw = dw; p.dh = dh; p.dfx = dfx; p.dfy = dfy; p.dcx = dcx; p.dcy = dcy; }
            ph.add(p);
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{[^}]*\\}").matcher(js);
        while (m.find()) {
            String o = m.group();
            float x0 = f(o, "x0"), z0 = f(o, "z0"), x1 = f(o, "x1"), z1 = f(o, "z1"), fy = f(o, "floorY"), h = f(o, "height"), wd = f(o, "width");
            float len = (float) Math.hypot(x1 - x0, z1 - z0), ux = (x1 - x0) / len, uz = (z1 - z0) / len;
            DoorRays.Result r = DoorRays.measure(vs, x0, z0, ux, uz, fy, 0, len, h);
            System.out.printf(Locale.US, "%s: rough %.2f x %.2f -> rays %.2f x %.2f (%d rays, %d depth photos) %s%n", dir.getName(), wd, h, r.u1 - r.u0, r.top, r.rays, vs.size(), r.why);
        }
    }
    static float f(String s, String k) { java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + k + "\"\\s*:\\s*(-?[0-9.Ee-]+)").matcher(s); return m.find() ? Float.parseFloat(m.group(1)) : Float.NaN; }
}
