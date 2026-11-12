package com.hackathon.setscan;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** The photo-real preview's dataset maths (SplatDataset), the same rules as the iPhone's SplatDatasetTests. */
public class SplatDatasetTest {
    @Test public void rowsOfAColumnMajorPose() {
        float[] m = {0, 0, -1, 0, 0, 1, 0, 0, 1, 0, 0, 0, 1, 2, 3, 1};
        float[][] r = SplatDataset.rows(m);
        assertArrayEquals(new float[]{0, 0, 1, 1}, r[0], 0);
        assertArrayEquals(new float[]{-1, 0, 0, 3}, r[2], 0);
        // the camera looks down its own -z: with this pose along world -x
        assertArrayEquals(new float[]{-1, 0, 0}, new float[]{-r[0][2], -r[1][2], -r[2][2]}, 0);
    }

    @Test public void pickSharpestOnePerWindow() {
        float[] s = {1, 5, 2, Float.NaN, 9, 8, 0, 0, 3, 4};
        assertEquals(Arrays.asList(1, 2, 4, 6, 9), SplatDataset.pickSharpest(s, 5));
        assertEquals(9, SplatDataset.pickSharpest(s, 100).size());
        assertTrue(SplatDataset.pickSharpest(new float[]{Float.NaN}, 5).isEmpty());
    }

    @Test public void transformsJsonHasRowsAndLens() {
        SplatDataset.Frame f = new SplatDataset.Frame();
        f.pose = new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0.5f, 1, 2, 1};
        f.fx = 429.8f; f.fy = 429; f.cx = 320.6f; f.cy = 237.7f; f.w = 640; f.h = 480;
        String j = SplatDataset.transformsJson(Collections.singletonList(f), Collections.singletonList("images/00001.jpg"), "points.ply");
        assertTrue(j, j.contains("\"ply_file_path\": \"points.ply\""));
        assertTrue(j, j.contains("[[1.0, 0.0, 0.0, 0.5], [0.0, 1.0, 0.0, 1.0], [0.0, 0.0, 1.0, 2.0], [0.0, 0.0, 0.0, 1.0]]"));
        assertTrue(j, j.contains("\"fl_x\": 429.8000") && j.contains("\"w\": 640"));
    }

    @Test public void pointsPlyLayout() {
        float[] xyz = {0, 1, 2, 3, 4, 5, 6, 7, 8};
        byte[] rgb = {10, 20, 30, 40, 50, 60, 70, 80, 90};
        byte[] d = SplatDataset.pointsPly(xyz, rgb, 3, 2);   // every 2nd: points 0 and 2
        String text = new String(d, 0, Math.min(d.length, 300), StandardCharsets.US_ASCII);
        assertTrue(text.contains("element vertex 2\n"));
        int body = text.indexOf("end_header\n") + "end_header\n".length();
        assertEquals(2 * 15, d.length - body);
        ByteBuffer bb = ByteBuffer.wrap(d, body + 15, 15).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(6f, bb.getFloat(), 0); assertEquals(7f, bb.getFloat(), 0); assertEquals(8f, bb.getFloat(), 0);
        assertEquals(70, bb.get()); assertEquals(80, bb.get()); assertEquals(90, bb.get());
    }

    /** On a real scan folder with rebuild-frames (SPLAT_SCAN=<scan dir>, SPLAT_OUT=<out dir>): writes the dataset. */
    @Test public void realScan() throws Exception {
        String scan = System.getenv("SPLAT_SCAN"), out = System.getenv("SPLAT_OUT");
        if (scan == null || out == null) return;
        int n = SplatDataset.export(new File(scan), new File(out), jpg -> (float) jpg.length());   // bigger JPEG ~ more detail
        assertTrue("pictures picked: " + n, n > 100);
        String j = new String(Files.readAllBytes(new File(out, "transforms.json").toPath()), StandardCharsets.UTF_8);
        assertTrue(j.contains("\"frames\""));
    }
}
