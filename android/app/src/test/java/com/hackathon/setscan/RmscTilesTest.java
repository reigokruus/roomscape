package com.hackathon.setscan;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** The shared tiles.json in a .rmsc (Rmsc.sharedTiles). With -Dtiles.scan=<scan folder> it also checks a real scan's
 *  tiles against its tiles.png and, with -Dtiles.out=<folder>, writes the shared tiles.json there (tools/tile-anchor/DrawSharedTiles draws it). */
public class RmscTilesTest {
    static final String SAMPLE = "{\"tile\":0.3,\"px\":96,\"atlas\":4096,\"rows\":1,"
            + "\"planes\":[[0,0,1,-2,1,0,0,0,1,0],[0,1,0,0.1,1,0,0,0,0,-1]],"
            + "\"tiles\":[[0,3,-1,0,0.7],[1,-2,4,1,0.5]]}";

    @Test public void cornersOnPlanesAndShifted() throws Exception {
        JSONObject s = new JSONObject(Rmsc.sharedTiles(SAMPLE, -0.1f));
        assertEquals(1, s.getInt("version")); assertEquals(96, s.getInt("imageHeight"));
        JSONArray t = s.getJSONArray("tiles"); assertEquals(2, t.length());
        // wall z = 2: tile (3, -1) spans x 0.9..1.2, y -0.3..0, and the floor moves up by 0.1
        JSONArray c = t.getJSONObject(0).getJSONArray("corners");
        assertEquals(0.9, c.getJSONArray(0).getDouble(0), 1e-4); assertEquals(-0.2, c.getJSONArray(0).getDouble(1), 1e-4); assertEquals(2, c.getJSONArray(0).getDouble(2), 1e-4);
        assertEquals(1.2, c.getJSONArray(2).getDouble(0), 1e-4); assertEquals(0.1, c.getJSONArray(2).getDouble(1), 1e-4);
        // floor y = -0.1 becomes y = 0
        for (int k = 0; k < 4; k++) assertEquals(0, t.getJSONObject(1).getJSONArray("corners").getJSONArray(k).getDouble(1), 1e-4);
        assertEquals(96, t.getJSONObject(1).getJSONArray("pixels").getJSONArray(0).getInt(0));
        assertNull(Rmsc.sharedTiles("{\"tile\":0.3,\"px\":96,\"atlas\":4096,\"rows\":0,\"planes\":[],\"tiles\":[]}", 0));
    }

    @Test public void realScan() throws Exception {
        String dir = System.getProperty("tiles.scan");
        if (dir == null) return;
        String raw = new String(java.nio.file.Files.readAllBytes(new java.io.File(dir, "tiles.json").toPath()), "UTF-8");
        JSONObject s = new JSONObject(Rmsc.sharedTiles(raw, 0));
        byte[] png = java.nio.file.Files.readAllBytes(new java.io.File(dir, "tiles.png").toPath());   // size from the PNG header
        int iw = java.nio.ByteBuffer.wrap(png, 16, 4).getInt(), ih = java.nio.ByteBuffer.wrap(png, 20, 4).getInt();
        assertEquals(s.getInt("imageWidth"), iw); assertEquals(s.getInt("imageHeight"), ih);
        JSONArray t = s.getJSONArray("tiles");
        assertEquals(new JSONObject(raw).getJSONArray("tiles").length(), t.length());
        for (int i = 0; i < t.length(); i++) {
            JSONArray c = t.getJSONObject(i).getJSONArray("corners"), p = t.getJSONObject(i).getJSONArray("pixels");
            for (int k = 0; k < 4; k++) {
                JSONArray a = c.getJSONArray(k), b = c.getJSONArray((k + 1) % 4);
                double l = Math.sqrt(Math.pow(a.getDouble(0) - b.getDouble(0), 2) + Math.pow(a.getDouble(1) - b.getDouble(1), 2) + Math.pow(a.getDouble(2) - b.getDouble(2), 2));
                assertEquals(0.3, l, 0.002);
                assertTrue(p.getJSONArray(k).getInt(0) <= iw && p.getJSONArray(k).getInt(1) <= ih);
            }
        }
        String out = System.getProperty("tiles.out");
        if (out == null) return;
        java.nio.file.Files.write(new java.io.File(out, new java.io.File(dir).getName() + "-rmsc-tiles.json").toPath(), s.toString().getBytes("UTF-8"));
    }
}
