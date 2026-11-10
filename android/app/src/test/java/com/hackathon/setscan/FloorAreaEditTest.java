package com.hackathon.setscan;

import static org.junit.Assert.*;
import org.junit.Test;

/** Walls moved by hand (rk 10 Nov 22:57: the area stayed the scan's own after a drag). */
public class FloorAreaEditTest {
    private static FloorArea.Room room() {
        FloorArea.Room r = new FloorArea.Room();
        r.rect = new float[]{0f, 3.6f, 0f, 2.7f};   // 3.6 × 2.7 m
        r.walled = 3.6f * 2.7f; r.area = r.walled; r.w = 3.6f; r.l = 2.7f;
        return r;
    }

    @Test public void oneSideOutByDAddsDTimesItsLength() {
        FloorArea.Room r = room();
        float d = 0.35f;
        assertEquals(r.shown() + d * 2.7f, r.editedArea(new float[]{0, d, 0, 0}), 1e-4);   // the end wall out: d × 2.7
        assertEquals(r.shown() + d * 3.6f, r.editedArea(new float[]{0, 0, d, 0}), 1e-4);   // a long wall out: d × 3.6
        assertEquals(r.shown() - 0.1f * 2.7f, r.editedArea(new float[]{0.1f * -1, 0, 0, 0}), 1e-4);   // in by 10 cm
    }

    @Test public void climbingBarsRoomGetsItsTapeSize() {
        FloorArea.Room r = room();
        float[] out = {0, 0.35f, 0, 0};
        float[] sz = r.editedSize(out);
        assertEquals(3.95f, sz[0], 1e-4); assertEquals(2.70f, sz[1], 1e-4);
        assertEquals(10.665f, r.editedArea(out), 1e-3);
    }

    @Test public void noRectangleKeepsTheSeenFloor() {
        FloorArea.Room r = new FloorArea.Room(); r.area = 5f;
        assertEquals(5f, r.editedArea(new float[]{1, 1, 1, 1}), 1e-6);
    }
}
