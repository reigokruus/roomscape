package com.hackathon.setscan;

import java.io.*;
import java.nio.*;
import java.util.*;

/** Offline check of PlaneFill on saved S24 scans (points.bin + surface.ply). The TSDF isn't saved, so "seen through"
 *  is approximated from the points: a fill cell is seen through when scan points lie 0.15-3 m behind the plane there
 *  (the side away from the scan's middle). Writes <out>/<scan>-fill.ply (surface grey, fills red). */
public class PlaneFillTest {
    public static void main(String[] a) throws Exception {
        File out = new File(a[0]);
        for (int k = 1; k < a.length; k++) run(new File(a[k]), out);
    }

    static void run(File dir, File outDir) throws Exception {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(new File(dir, "points.bin"))));
        int n = in.readInt(); float[] xyz = new float[n * 3]; byte[] rgb = new byte[n * 4];
        for (int i = 0; i < n; i++) { xyz[i * 3] = in.readFloat(); xyz[i * 3 + 1] = in.readFloat(); xyz[i * 3 + 2] = in.readFloat(); rgb[i * 4] = in.readByte(); rgb[i * 4 + 1] = in.readByte(); rgb[i * 4 + 2] = in.readByte(); rgb[i * 4 + 3] = (byte) 255; }
        in.close();
        // surface.ply (binary little endian, x y z float + rgb uchar, faces uchar count + 3 int) -> triangle soup
        byte[] b = java.nio.file.Files.readAllBytes(new File(dir, "surface.ply").toPath());
        String head = new String(b, 0, Math.min(b.length, 2000), "ISO-8859-1");
        int hEnd = head.indexOf("end_header\n") + 11;
        int nv = Integer.parseInt(head.replaceAll("(?s).*element vertex (\\d+).*", "$1")), nf = Integer.parseInt(head.replaceAll("(?s).*element face (\\d+).*", "$1"));
        ByteBuffer bb = ByteBuffer.wrap(b, hEnd, b.length - hEnd).order(ByteOrder.LITTLE_ENDIAN);
        float[] vp = new float[nv * 3];
        for (int i = 0; i < nv; i++) { vp[i * 3] = bb.getFloat(); vp[i * 3 + 1] = bb.getFloat(); vp[i * 3 + 2] = bb.getFloat(); bb.get(); bb.get(); bb.get(); }
        float[] soup = new float[nf * 9];
        for (int f = 0; f < nf; f++) { bb.get(); for (int c = 0; c < 3; c++) { int vi = bb.getInt(); System.arraycopy(vp, vi * 3, soup, f * 9 + c * 3, 3); } }
        Planes.CELL = 0.1f; Planes.MAX_FLAT = 0.15f; Planes.MAX_ANGLE_DEG = 30; Planes.MAX_OFFSET = 0.08f;
        Planes.INLIER = 0.04f; Planes.MIN_INLIERS = 0.75f; Planes.MIN_AREA = 0.4f; Planes.SNAP = 0.08f;
        long t0 = System.currentTimeMillis();
        List<Planes.Plane> planes = Planes.find(xyz, rgb, 4, n, new HashMap<>());
        // points in 5 cm voxels, for the seen-through stand-in
        HashSet<Long> occ = new HashSet<>();
        double mx = 0, my = 0, mz = 0;
        for (int i = 0; i < n; i++) { occ.add(key(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2])); mx += xyz[i * 3]; my += xyz[i * 3 + 1]; mz += xyz[i * 3 + 2]; }
        final float cx = (float) (mx / n), cy = (float) (my / n), cz = (float) (mz / n);
        final List<Planes.Plane> pl = planes;
        PlaneFill.Out o = PlaneFill.fill(planes, soup, nf * 3, xyz, n, (x, y, z) -> {
            // the plane through this point: the nearest of the planes
            Planes.Plane best = null; float bd = 1e9f;
            for (Planes.Plane p : pl) { float d = Math.abs(x * p.nx + y * p.ny + z * p.nz - p.d); if (d < bd) { bd = d; best = p; } }
            if (best == null) return false;
            float side = (cx - x) * best.nx + (cy - y) * best.ny + (cz - z) * best.nz > 0 ? -1 : 1;   // away from the middle
            for (float t = 0.15f; t < 3f; t += 0.05f) if (occ.contains(key(x + side * best.nx * t, y + side * best.ny * t, z + side * best.nz * t))) return true;
            return false;
        });
        System.out.printf("%s: %d points, %d planes (%d walls/floors), %d surface triangles; fill: %d gaps closed, %.2f m², %d triangles, %d ms; left open: edge %.1f, too big %.1f, seen through %.1f, no evidence %.1f m²%n",
                dir.getName(), n, planes.size(), planes.stream().filter(p -> "wall".equals(p.kind) || "floor".equals(p.kind)).count(), nf, o.holes, o.area, o.verts / 3, System.currentTimeMillis() - t0, o.atEdge, o.tooBig, o.seen, o.noEvidence);
        // PLY: surface grey + fills red
        outDir.mkdirs();
        try (DataOutputStream w = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(outDir, dir.getName() + "-fill.ply"))))) {
            int tv = nf * 3 + o.verts;
            w.writeBytes("ply\nformat binary_little_endian 1.0\nelement vertex " + tv + "\nproperty float x\nproperty float y\nproperty float z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\nelement face " + tv / 3 + "\nproperty list uchar int vertex_indices\nend_header\n");
            ByteBuffer v = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < tv; i++) {
                v.clear();
                if (i < nf * 3) v.putFloat(soup[i * 3]).putFloat(soup[i * 3 + 1]).putFloat(soup[i * 3 + 2]).put((byte) 180).put((byte) 180).put((byte) 180);
                else { int j = i - nf * 3; v.putFloat(o.pos[j * 3]).putFloat(o.pos[j * 3 + 1]).putFloat(o.pos[j * 3 + 2]).put((byte) 230).put((byte) 40).put((byte) 40); }
                w.write(v.array());
            }
            ByteBuffer f = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
            for (int t = 0; t < tv / 3; t++) { f.clear(); f.put((byte) 3).putInt(t * 3).putInt(t * 3 + 1).putInt(t * 3 + 2); w.write(f.array()); }
        }
    }

    static long key(float x, float y, float z) {
        long ix = (long) Math.floor(x / 0.05f) + (1 << 20), iy = (long) Math.floor(y / 0.05f) + (1 << 20), iz = (long) Math.floor(z / 0.05f) + (1 << 20);
        return (ix & 0x1FFFFF) | ((iy & 0x1FFFFF) << 21) | ((iz & 0x1FFFFF) << 42);
    }
}
