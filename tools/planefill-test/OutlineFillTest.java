package com.hackathon.setscan;

import java.io.*;
import java.nio.*;
import java.util.*;

/** OutlineFill on saved S24 scans: <out> <scan>...; writes <scan>-before.ply / -after.ply (fills red) and stats. */
public class OutlineFillTest {
    public static void main(String[] a) throws Exception {
        File out = new File(a[0]); out.mkdirs();
        for (int k = 1; k < a.length; k++) run(new File(a[k]), out);
    }

    static void run(File dir, File outDir) throws Exception {
        String ol = new String(java.nio.file.Files.readAllBytes(new File(dir, "outline.json").toPath())).replaceAll("[\\[\\]\\s]", "");
        String[] v = ol.split(",");
        int nw = v.length / 5; float[] walls = new float[nw * 4]; float h = 2.5f;
        for (int i = 0; i < nw; i++) { for (int k = 0; k < 4; k++) walls[i * 4 + k] = Float.parseFloat(v[i * 5 + k]); h = Float.parseFloat(v[i * 5 + 4]); }
        String pj = new String(java.nio.file.Files.readAllBytes(new File(dir, "planes.json").toPath()));
        // floor: the floor plane's min y (planes.json has heights above the floor? it stores min/max with floorY taken off: use the outline's own floor via meta-free guess)
        float floorY = Float.NaN;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"kind\":\"floor\",\"normal\":\\[[^\\]]*\\],\"d\":(-?[0-9.]+)").matcher(pj);
        if (m.find()) floorY = Float.parseFloat(m.group(1));
        // surface.ply soup
        byte[] b = java.nio.file.Files.readAllBytes(new File(dir, "surface.ply").toPath());
        String head = new String(b, 0, Math.min(b.length, 2000), "ISO-8859-1");
        int hEnd = head.indexOf("end_header\n") + 11;
        int nv = Integer.parseInt(head.replaceAll("(?s).*element vertex (\\d+).*", "$1")), nf = Integer.parseInt(head.replaceAll("(?s).*element face (\\d+).*", "$1"));
        ByteBuffer bb = ByteBuffer.wrap(b, hEnd, b.length - hEnd).order(ByteOrder.LITTLE_ENDIAN);
        float[] vp = new float[nv * 3];
        for (int i = 0; i < nv; i++) { vp[i * 3] = bb.getFloat(); vp[i * 3 + 1] = bb.getFloat(); vp[i * 3 + 2] = bb.getFloat(); bb.get(); bb.get(); bb.get(); }
        float[] soup = new float[nf * 9];
        for (int f = 0; f < nf; f++) { bb.get(); for (int c = 0; c < 3; c++) { int vi = bb.getInt(); System.arraycopy(vp, vi * 3, soup, f * 9 + c * 3, 3); } }
        if (Float.isNaN(floorY)) { float mn = Float.MAX_VALUE; for (int i = 1; i < vp.length; i += 3) mn = Math.min(mn, vp[i]); floorY = mn; }
        // photos
        List<OutlineFill.Photo> photos = new ArrayList<>();
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(new File(dir, "kf.bin"))));
        int nk = in.readInt();
        for (int i = 0; i < nk; i++) {
            OutlineFill.Photo p = new OutlineFill.Photo();
            float[] view = new float[16], pose = new float[16];
            for (int k = 0; k < 16; k++) view[k] = in.readFloat();
            for (int k = 0; k < 16; k++) pose[k] = in.readFloat();
            float fx = in.readFloat(), fy = in.readFloat(), cx = in.readFloat(), cy = in.readFloat(), w = in.readFloat(), hh = in.readFloat();
            in.readBoolean();
            int dw = in.readInt(), dh = in.readInt();
            p.dfx = in.readFloat(); p.dfy = in.readFloat(); p.dcx = in.readFloat(); p.dcy = in.readFloat();
            if (dw * dh > 0) { p.depth = new short[dw * dh]; for (int j = 0; j < dw * dh; j++) p.depth[j] = in.readShort(); p.dw = dw; p.dh = dh; }
            p.pose = pose;
            File jf = new File(dir, String.format(Locale.US, "kf/%03d.jpg", i));
            java.awt.image.BufferedImage img = jf.exists() ? javax.imageio.ImageIO.read(jf) : null;
            if (img != null) {
                p.w = img.getWidth(); p.h = img.getHeight();
                float s = p.w / w;
                p.fx = fx * s; p.fy = fy * s; p.cx = cx * s; p.cy = cy * s;
                p.argb = img.getRGB(0, 0, p.w, p.h, null, 0, p.w);
            } else { p.w = (int) w; p.h = (int) hh; p.fx = fx; p.fy = fy; p.cx = cx; p.cy = cy; }
            photos.add(p);
        }
        in.close();
        long t0 = System.currentTimeMillis();
        OutlineFill.Out o = OutlineFill.fill(walls, floorY, floorY + h, soup, nf * 3, photos);
        System.out.printf("%s: %d outline walls, floor %.2f, height %.2f, %d photos; filled %.2f m² (%d triangles) in %d ms; kept open: seen through %.2f, colour differs %.2f, not seen %.2f m²%n",
                dir.getName(), nw, floorY, h, nk, o.filled, o.verts / 3, System.currentTimeMillis() - t0, o.keptSeen, o.keptColour, o.keptUnseen);
        write(new File(outDir, dir.getName() + "-before.ply"), soup, nf * 3, null, 0);
        write(new File(outDir, dir.getName() + "-after.ply"), soup, nf * 3, o.pos, o.verts);
    }

    static void write(File f, float[] soup, int sv, float[] fill, int fv) throws Exception {
        try (DataOutputStream w = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f)))) {
            int tv = sv + fv;
            w.writeBytes("ply\nformat binary_little_endian 1.0\nelement vertex " + tv + "\nproperty float x\nproperty float y\nproperty float z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\nelement face " + tv / 3 + "\nproperty list uchar int vertex_indices\nend_header\n");
            ByteBuffer v = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < tv; i++) {
                v.clear();
                if (i < sv) v.putFloat(soup[i * 3]).putFloat(soup[i * 3 + 1]).putFloat(soup[i * 3 + 2]).put((byte) 180).put((byte) 180).put((byte) 180);
                else { int j = i - sv; v.putFloat(fill[j * 3]).putFloat(fill[j * 3 + 1]).putFloat(fill[j * 3 + 2]).put((byte) 230).put((byte) 40).put((byte) 40); }
                w.write(v.array());
            }
            ByteBuffer fb = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
            for (int t = 0; t < tv / 3; t++) { fb.clear(); fb.put((byte) 3).putInt(t * 3).putInt(t * 3 + 1).putInt(t * 3 + 2); w.write(fb.array()); }
        }
    }
}
