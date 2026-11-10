package com.hackathon.setscan;
import java.io.*; import java.util.*; import java.awt.*; import java.awt.image.BufferedImage;
/** Draws a 3D rectangle (doorway from doorways.json) into the keyframes that see it: <scan> <out dir> [flip] */
public class DoorProjTest {
    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]), out = new File(a[1]); out.mkdirs(); boolean flip = a.length > 2;
        String dj = new String(java.nio.file.Files.readAllBytes(new File(dir, "doorways.json").toPath()));
        float[] v = num(dj, "x0","z0","x1","z1","floorY","height");
        float x0=v[0],z0=v[1],x1=v[2],z1=v[3],fy=v[4],h=v[5];
        float[][] corners = {{x0,fy,z0},{x1,fy,z1},{x1,fy+h,z1},{x0,fy+h,z0}};
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(new File(dir, "kf.bin"))));
        int nk = in.readInt(); int drawn = 0;
        for (int i = 0; i < nk; i++) {
            float[] view = new float[16], pose = new float[16];
            for (int k = 0; k < 16; k++) view[k] = in.readFloat();
            for (int k = 0; k < 16; k++) pose[k] = in.readFloat();
            float fx = in.readFloat(), fyy = in.readFloat(), cx = in.readFloat(), cy = in.readFloat(), w = in.readFloat(), hh = in.readFloat();
            in.readBoolean(); int dw = in.readInt(), dh = in.readInt(); for (int k = 0; k < 4; k++) in.readFloat(); in.skipBytes(dw * dh * 2);
            File jf = new File(dir, String.format(Locale.US, "kf/%03d.jpg", i));
            BufferedImage img = javax.imageio.ImageIO.read(jf); if (img == null) continue;
            float sx = img.getWidth() / w, sy = img.getHeight() / hh;
            int[] px = new int[4], py = new int[4]; boolean ok = true;
            for (int c = 0; c < 4; c++) {
                float X=corners[c][0],Y=corners[c][1],Z=corners[c][2];
                float qx = view[0]*X+view[4]*Y+view[8]*Z+view[12], qy = view[1]*X+view[5]*Y+view[9]*Z+view[13], qz = view[2]*X+view[6]*Y+view[10]*Z+view[14];
                float d = -qz; if (d < 0.2f) { ok = false; break; }
                float u = (fx*qx/d + cx) * sx, vv = (cy - fyy*qy/d) * sy;
                if (flip) vv = img.getHeight() - vv;
                px[c] = Math.round(u); py[c] = Math.round(vv);
                if (u < 0 || vv < 0 || u >= img.getWidth() || vv >= img.getHeight()) ok = false;
            }
            if (!ok) continue;
            Graphics2D g = img.createGraphics(); g.setColor(Color.YELLOW); g.setStroke(new BasicStroke(3)); g.drawPolygon(px, py, 4); g.dispose();
            javax.imageio.ImageIO.write(img, "jpg", new File(out, String.format(Locale.US, "kf%03d%s.jpg", i, flip ? "-flip" : "")));
            if (++drawn >= 4) break;
        }
        System.out.println("drew " + drawn);
    }
    static float[] num(String s, String... keys) { float[] r = new float[keys.length]; for (int i = 0; i < keys.length; i++) { java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + keys[i] + "\"\\s*:\\s*(-?[0-9.Ee-]+)").matcher(s); if (m.find()) r[i] = Float.parseFloat(m.group(1)); } return r; }
}
