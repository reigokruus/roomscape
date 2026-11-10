package com.hackathon.setscan;

import java.io.*;
import java.nio.*;
import java.util.*;

/** DoorwayFinder on saved S24 scans: <out> <scan>... */
public class DoorwayTest {
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
        DataInputStream pin = new DataInputStream(new BufferedInputStream(new FileInputStream(new File(dir, "points.bin"))));
        int n = pin.readInt(); float[] xyz = new float[n * 3];
        for (int i = 0; i < n; i++) { xyz[i * 3] = pin.readFloat(); xyz[i * 3 + 1] = pin.readFloat(); xyz[i * 3 + 2] = pin.readFloat(); pin.readByte(); pin.readByte(); pin.readByte(); }
        pin.close();
        if ("mesh".equals(System.getenv("DOOR_SRC"))) {   // the surface's corners instead of the points
            n = vp.length / 3; xyz = vp;
        }
        if ("planes".equals(System.getenv("DOOR_WALLS"))) {   // walls from plane fits on the surface instead of the outline
            byte[] rgb = new byte[(vp.length / 3) * 4]; java.util.Arrays.fill(rgb, (byte) 200);
            Planes.CELL = 0.1f; Planes.MAX_FLAT = 0.15f; Planes.MAX_ANGLE_DEG = 30; Planes.MAX_OFFSET = 0.08f;
            Planes.INLIER = 0.04f; Planes.MIN_INLIERS = 0.6f; Planes.MIN_AREA = 0.3f; Planes.SNAP = 0.08f;
            List<Planes.Plane> pl = Planes.find(vp, rgb, 4, vp.length / 3, new HashMap<>());
            ArrayList<Float> ws = new ArrayList<>();
            for (Planes.Plane q : pl) if ("wall".equals(q.kind)) {
                float l = (float) Math.hypot(q.nx, q.nz), ux = -q.nz / l, uz = q.nx / l;
                float u0 = Float.MAX_VALUE, u1 = -Float.MAX_VALUE;
                for (int c = 0; c < 4; c++) { float x = (c & 1) == 0 ? q.min[0] : q.max[0], z = (c & 2) == 0 ? q.min[2] : q.max[2]; float u = x * ux + z * uz; u0 = Math.min(u0, u); u1 = Math.max(u1, u); }
                float px = q.nx / l * q.d / l, pz = q.nz / l * q.d / l;   // a point on the plane's line
                u0 -= 1.5f; u1 += 1.5f;   // the doorway may be just past the fitted part
                ws.add(px + ux * u0); ws.add(pz + uz * u0); ws.add(px + ux * u1); ws.add(pz + uz * u1);
                System.out.printf("  wall plane %.2f m², n (%.2f, %.2f), d %.2f%n", q.area, q.nx, q.nz, q.d);
            }
            walls = new float[ws.size()]; for (int i = 0; i < walls.length; i++) walls[i] = ws.get(i); nw = walls.length / 4;
        }
        float[][] views = new float[photos.size()][];
        for (int i = 0; i < photos.size(); i++) views[i] = OutlineFill.invert(photos.get(i).pose);
        final List<OutlineFill.Photo> ph = photos;
        StringBuilder why = new StringBuilder(); DoorwayFinder.DUMP = Integer.parseInt(System.getenv().getOrDefault("DOOR_DUMP", "-1"));
        long t0 = System.currentTimeMillis();
        List<DoorwayFinder.Doorway> ds = DoorwayFinder.find(xyz, n, walls, floorY, (x, y, z) -> OutlineFill.judge(ph, views, new float[]{x, y, z}, null) == 1, why);
        System.out.printf("%s: %d points, %d walls, floor %.3f: %d doorway(s) in %d ms%n", dir.getName(), n, nw, floorY, ds.size(), System.currentTimeMillis() - t0);
        for (DoorwayFinder.Doorway d : ds) System.out.println("  " + d);
        if (why.length() > 0) System.out.println("  rejected:" + why);
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
