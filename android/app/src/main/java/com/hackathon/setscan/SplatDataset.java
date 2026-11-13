package com.hackathon.setscan;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The photo-real room preview's dataset (rk 12 Nov: "put in the lightweight brush thing into both phones"), made from a
 * scan's rebuild-frames/ (camera pictures with poses, lens and raw depth, saved since 263160917): the Nerfstudio
 * transforms.json that Brush (and gsplat) read, the picked pictures, and the scan's sure dots as starting points.
 * The same format as the iPhone's (CineX.swiftpm/Planning/Core/SplatDataset.swift).
 *
 * Convention: Nerfstudio uses the OpenGL camera (x right, y up, looking down -z), which is ARCore's camera too, so the
 * saved camera-to-world pose goes in unchanged, written row by row (frames.csv keeps it column-major).
 *
 * Pure Java (no Android classes): the picture sharpness is passed in, so this runs in unit tests and on a Mac.
 */
final class SplatDataset {
    static final int MAX_FRAMES = 300, MAX_POINTS = 300_000;

    /** One rebuild frame: index, scan time, pose (column-major camera to world), lens of its picture. */
    static final class Frame {
        int idx; long ms; float[] pose = new float[16]; float fx, fy, cx, cy; int w, h;
    }

    /** rebuild-frames/frames.csv: idx, ms, 16 pose floats (column-major), image fx fy cx cy w h, depth lens (ignored). */
    static List<Frame> readFrames(File csv) throws IOException {
        List<Frame> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(csv))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] v = line.trim().split(",");
                if (v.length < 24) continue;
                Frame f = new Frame();
                f.idx = Integer.parseInt(v[0]); f.ms = Long.parseLong(v[1]);
                for (int i = 0; i < 16; i++) f.pose[i] = Float.parseFloat(v[2 + i]);
                f.fx = Float.parseFloat(v[18]); f.fy = Float.parseFloat(v[19]); f.cx = Float.parseFloat(v[20]); f.cy = Float.parseFloat(v[21]);
                f.w = (int) Float.parseFloat(v[22]); f.h = (int) Float.parseFloat(v[23]);
                out.add(f);
            }
        }
        return out;
    }

    /** The pose as 4 rows (row r = column-major values r, r+4, r+8, r+12). */
    static float[][] rows(float[] m) {
        float[][] r = new float[4][4];
        for (int i = 0; i < 4; i++) for (int c = 0; c < 4; c++) r[i][c] = m[c * 4 + i];
        return r;
    }

    /**
     * The sharpest frame in each run of consecutive frames, at most maxFrames spread evenly (NaN score = unusable).
     * Same windows as the iPhone's SplatDataset.pickSharpest.
     */
    static List<Integer> pickSharpest(float[] scores, int maxFrames) {
        List<Integer> out = new ArrayList<>();
        if (maxFrames <= 0 || scores.length == 0) return out;
        int window = Math.max(1, (int) Math.ceil(scores.length / (double) maxFrames));
        for (int start = 0; start < scores.length; start += window) {
            int best = -1;
            for (int i = start; i < Math.min(scores.length, start + window); i++)
                if (!Float.isNaN(scores[i]) && (best < 0 || scores[i] > scores[best])) best = i;
            if (best >= 0) out.add(best);
        }
        return out;
    }

    /** transforms.json: per frame its picture, pose rows and lens; the starting points file. */
    static String transformsJson(List<Frame> frames, List<String> files, String pointsFile) {
        StringBuilder b = new StringBuilder("{\n \"camera_model\": \"PERSPECTIVE\",\n");
        if (pointsFile != null) b.append(" \"ply_file_path\": \"").append(pointsFile).append("\",\n");
        b.append(" \"frames\": [\n");
        for (int k = 0; k < frames.size(); k++) {
            Frame f = frames.get(k);
            float[][] r = rows(f.pose);
            b.append("  {\"file_path\": \"").append(files.get(k)).append("\", \"transform_matrix\": [");
            for (int i = 0; i < 4; i++) {
                b.append(i == 0 ? "[" : ", [");
                for (int c = 0; c < 4; c++) b.append(c == 0 ? "" : ", ").append(r[i][c]);
                b.append("]");
            }
            b.append(String.format(Locale.US, "], \"fl_x\": %.4f, \"fl_y\": %.4f, \"cx\": %.4f, \"cy\": %.4f, \"w\": %d, \"h\": %d}%s\n",
                    f.fx, f.fy, f.cx, f.cy, f.w, f.h, k + 1 < frames.size() ? "," : ""));
        }
        return b.append(" ]\n}\n").toString();
    }

    /** Starting points as a binary little-endian PLY (x y z float, red green blue uchar), at most maxPoints, taken evenly. */
    static byte[] pointsPly(float[] xyz, byte[] rgb, int n, int maxPoints) {
        int step = Math.max(1, (int) Math.ceil(n / (double) Math.max(1, maxPoints)));
        int kept = (n + step - 1) / step;
        byte[] head = ("ply\nformat binary_little_endian 1.0\nelement vertex " + kept + "\nproperty float x\nproperty float y\n"
                + "property float z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n").getBytes(StandardCharsets.US_ASCII);
        ByteBuffer bb = ByteBuffer.allocate(head.length + kept * 15).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(head);
        for (int i = 0; i < n; i += step) {
            bb.putFloat(xyz[i * 3]); bb.putFloat(xyz[i * 3 + 1]); bb.putFloat(xyz[i * 3 + 2]);
            bb.put(rgb[i * 3]); bb.put(rgb[i * 3 + 1]); bb.put(rgb[i * 3 + 2]);
        }
        return bb.array();
    }

    /** Sharpness score source: given a picture file, a number (higher = sharper), or NaN if it can't be read. */
    interface Sharpness { float of(File jpg); }

    /**
     * Writes the dataset for a scan folder into outDir: images/ (the picked pictures, copied), transforms.json and
     * points.ply (the scan's sure dots: points.bin, with dot-confidence.bin when it matches, keeping 95%+ readings).
     * Returns how many pictures were picked.
     */
    static int export(File scanDir, File outDir, Sharpness sharpness) throws IOException {
        return export(scanDir, outDir, sharpness, MAX_FRAMES, MAX_POINTS);
    }

    /** As above, with the picture and starting-point limits of a phone plan (SplatPlan). */
    static int export(File scanDir, File outDir, Sharpness sharpness, int maxFrames, int maxPoints) throws IOException {
        File rf = new File(scanDir, "rebuild-frames");
        List<Frame> frames = readFrames(new File(rf, "frames.csv"));
        float[] scores = new float[frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            File jpg = new File(rf, String.format(Locale.US, "%05d.jpg", frames.get(i).idx));
            scores[i] = jpg.exists() ? sharpness.of(jpg) : Float.NaN;
        }
        List<Integer> pick = pickSharpest(scores, maxFrames);
        File images = new File(outDir, "images");
        if (!images.isDirectory() && !images.mkdirs()) throw new IOException("can't make " + images);
        List<Frame> picked = new ArrayList<>(); List<String> files = new ArrayList<>();
        for (int i : pick) {
            Frame f = frames.get(i);
            String name = String.format(Locale.US, "%05d.jpg", f.idx);
            copy(new File(rf, name), new File(images, name));
            picked.add(f); files.add("images/" + name);
        }
        // starting points: the scan's dots (big-endian: int n, then float x y z + 3 colour bytes each)
        float[] xyz; byte[] rgb; int n;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(new File(scanDir, "points.bin")), 1 << 16))) {
            n = in.readInt(); xyz = new float[n * 3]; rgb = new byte[n * 3];
            for (int i = 0; i < n; i++) { xyz[i * 3] = in.readFloat(); xyz[i * 3 + 1] = in.readFloat(); xyz[i * 3 + 2] = in.readFloat(); in.readFully(rgb, i * 3, 3); }
        }
        File dc = new File(scanDir, "dot-confidence.bin");
        if (dc.exists()) try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(dc)))) {
            if (in.readInt() == n) {   // per dot: best confidence (0-255, 0 = not known), closest distance (2 bytes)
                int k = 0;
                for (int i = 0; i < n; i++) {
                    int conf = in.readUnsignedByte(); in.skipBytes(2);
                    if (conf != 0 && conf < 242) continue;   // keep sure (95%+) and unknown
                    if (k != i) { System.arraycopy(xyz, i * 3, xyz, k * 3, 3); System.arraycopy(rgb, i * 3, rgb, k * 3, 3); }
                    k++;
                }
                n = k;
            }
        }
        write(new File(outDir, "points.ply"), pointsPly(xyz, rgb, n, maxPoints));
        write(new File(outDir, "transforms.json"), transformsJson(picked, files, "points.ply").getBytes(StandardCharsets.UTF_8));
        return picked.size();
    }

    private static void copy(File a, File b) throws IOException {
        try (InputStream in = new FileInputStream(a); OutputStream out = new FileOutputStream(b)) {
            byte[] buf = new byte[1 << 16]; int k;
            while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
        }
    }

    private static void write(File f, byte[] data) throws IOException {
        try (OutputStream out = new FileOutputStream(f)) { out.write(data); }
    }
}
