package com.hackathon.setscan;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Turns a scanned point cloud into a simple shot plan: finds the floor and free space,
 * then places a camera and lights around a subject for a chosen scene type.
 * World coordinates are ARCore's: +Y is up, units are metres.
 */
final class ShotPlanner {

    enum Scene {
        INTERVIEW("Interview"), VLOG("Vlog / talking head"), PRODUCT("Product shot");
        final String label;
        Scene(String l) { label = l; }
    }

    /** One thing to place: the camera or a light. */
    static final class Element {
        final String name, role;
        final float[] color; // rgba
        final boolean isCamera;
        float x, y, z;

        Element(String name, String role, float[] color, boolean isCamera) {
            this.name = name; this.role = role; this.color = color; this.isCamera = isCamera;
        }
    }

    static final class Plan {
        Scene scene;
        float sx, sy, sz;   // subject point (eye height, or product centre)
        float floorY;
        final List<Element> elements = new ArrayList<>();
        String explanation = "";
        boolean cramped;
    }

    private static final float CELL = 0.10f;
    private static final int BLOCKED = 15; // points in a 10 cm column before we call it furniture/wall

    float floorY;
    private float minX, minZ;
    private int nx, nz;
    private boolean[] floorKnown;
    private int[] blocked;
    private boolean ready;

    boolean isReady() { return ready; }

    /** Analyse the cloud: floor height, which floor cells were seen, which are blocked by furniture/walls. */
    void analyse(FloatBuffer pos, int count) {
        ready = false;
        if (count < 1000) return;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        minX = Float.MAX_VALUE; minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            float x = pos.get(i * 3), y = pos.get(i * 3 + 1), z = pos.get(i * 3 + 2);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        // Floor = the most populated 2 cm height band in the lower 40% of the scan.
        int bins = Math.max(1, (int) ((maxY - minY) / 0.02f) + 1);
        int[] hist = new int[bins];
        for (int i = 0; i < count; i++) {
            int b = (int) ((pos.get(i * 3 + 1) - minY) / 0.02f);
            if (b >= 0 && b < bins) hist[b]++;
        }
        int lowerBins = Math.max(1, (int) (bins * 0.4f));
        int best = 0;
        for (int b = 0; b < lowerBins; b++) if (hist[b] > hist[best]) best = b;
        floorY = minY + (best + 0.5f) * 0.02f;

        nx = Math.min(400, (int) ((maxX - minX) / CELL) + 1);
        nz = Math.min(400, (int) ((maxZ - minZ) / CELL) + 1);
        floorKnown = new boolean[nx * nz];
        blocked = new int[nx * nz];
        for (int i = 0; i < count; i++) {
            float x = pos.get(i * 3), y = pos.get(i * 3 + 1) - floorY, z = pos.get(i * 3 + 2);
            int c = cell(x, z);
            if (c < 0) continue;
            if (Math.abs(y) < 0.08f) floorKnown[c] = true;
            else if (y > 0.25f && y < 1.9f) blocked[c]++;
        }
        ready = true;
    }

    private int cell(float x, float z) {
        int ix = (int) ((x - minX) / CELL), iz = (int) ((z - minZ) / CELL);
        if (ix < 0 || iz < 0 || ix >= nx || iz >= nz) return -1;
        return iz * nx + ix;
    }

    /** A spot you can stand a tripod on: seen floor nearby and nothing in the way. */
    private boolean free(float x, float z) {
        int c = cell(x, z);
        if (c < 0) return false;
        if (blocked[c] > BLOCKED) return false;
        int ix = c % nx, iz = c / nx;
        for (int dz = -3; dz <= 3; dz++)
            for (int dx = -3; dx <= 3; dx++) {
                int jx = ix + dx, jz = iz + dz;
                if (jx >= 0 && jz >= 0 && jx < nx && jz < nz && floorKnown[jz * nx + jx]) return true;
            }
        return false;
    }

    /** How many blocked cells lie on the straight line between two floor positions. */
    private int obstruction(float x0, float z0, float x1, float z1) {
        float len = (float) Math.hypot(x1 - x0, z1 - z0);
        int steps = Math.max(1, (int) (len / 0.08f));
        int hits = 0;
        for (int i = 1; i < steps; i++) {
            float t = i / (float) steps;
            int c = cell(x0 + (x1 - x0) * t, z0 + (z1 - z0) * t);
            if (c >= 0 && blocked[c] > BLOCKED) hits++;
        }
        return hits;
    }

    /** Default subject spot: the free floor cell closest to the middle of all free floor. */
    float[] defaultSubject() {
        double sx = 0, sz = 0; int n = 0;
        for (int iz = 0; iz < nz; iz++)
            for (int ix = 0; ix < nx; ix++) {
                float x = minX + (ix + 0.5f) * CELL, z = minZ + (iz + 0.5f) * CELL;
                if (floorKnown[iz * nx + ix] && free(x, z)) { sx += x; sz += z; n++; }
            }
        if (n == 0) return new float[]{minX + nx * CELL / 2, floorY, minZ + nz * CELL / 2};
        float cx = (float) (sx / n), cz = (float) (sz / n);
        float bestD = Float.MAX_VALUE, bx = cx, bz = cz;
        for (int iz = 0; iz < nz; iz++)
            for (int ix = 0; ix < nx; ix++) {
                float x = minX + (ix + 0.5f) * CELL, z = minZ + (iz + 0.5f) * CELL;
                if (!floorKnown[iz * nx + ix] || !free(x, z)) continue;
                float d = (x - cx) * (x - cx) + (z - cz) * (z - cz);
                if (d < bestD) { bestD = d; bx = x; bz = z; }
            }
        return new float[]{bx, floorY, bz};
    }

    private static final float[] BLUE = {0.25f, 0.6f, 1f, 1f};
    private static final float[] ORANGE = {1f, 0.55f, 0.1f, 1f};
    private static final float[] YELLOW = {1f, 0.9f, 0.3f, 1f};
    private static final float[] PURPLE = {0.8f, 0.4f, 1f, 1f};
    private static final float[] CYAN = {0.3f, 1f, 0.9f, 1f};

    /** Light/camera layout relative to the camera axis. angle in degrees (0 = where the camera is). */
    private static final class Slot {
        final Element e; final float angleDeg, dist, height;
        Slot(Element e, float a, float d, float h) { this.e = e; angleDeg = a; dist = d; height = h; }
    }

    /**
     * Build a plan for the scene around a subject. pick is a tapped scan point (or the default spot):
     * for people we use its floor position, for products its height (a table top).
     */
    Plan plan(Scene scene, float[] pick) {
        Plan p = new Plan();
        p.scene = scene;
        p.floorY = floorY;
        p.sx = pick[0]; p.sz = pick[2];
        List<Slot> slots = new ArrayList<>();
        switch (scene) {
            case INTERVIEW: {
                p.sy = floorY + 1.2f; // seated eye height
                slots.add(new Slot(new Element("Camera", "eye height, 1.8 m away", BLUE, true), 0, 1.8f, 0f));
                slots.add(new Slot(new Element("Key light", "main soft light, 45° to the side, above eyes", ORANGE, false), 45, 1.5f, 0.5f));
                slots.add(new Slot(new Element("Fill", "dimmer light or white sheet, other side", YELLOW, false), -50, 1.9f, 0.1f));
                slots.add(new Slot(new Element("Back light", "behind, separates subject from background", PURPLE, false), 155, 1.1f, 0.7f));
                break;
            }
            case VLOG: {
                p.sy = floorY + 1.5f; // standing / desk eye height
                slots.add(new Slot(new Element("Camera", "eye level, about 1 m away", BLUE, true), 0, 0.9f, 0f));
                slots.add(new Slot(new Element("Key light", "ring light or lamp just above the camera", ORANGE, false), 20, 0.85f, 0.25f));
                slots.add(new Slot(new Element("Background light", "small lamp lighting the wall behind you", CYAN, false), 180, 1.2f, -0.9f));
                break;
            }
            default: { // PRODUCT
                float h = pick[1] - floorY;
                p.sy = (h > 0.4f && h < 1.3f) ? pick[1] + 0.08f : floorY + 0.8f; // on a table if you tapped one
                slots.add(new Slot(new Element("Camera", "0.7 m away, looking down ~25°", BLUE, true), 0, 0.7f, 0.33f));
                slots.add(new Slot(new Element("Key light", "big soft light from the side", ORANGE, false), 70, 0.6f, 0.45f));
                slots.add(new Slot(new Element("Reflector", "white card opposite the key", YELLOW, false), -70, 0.45f, 0.1f));
                slots.add(new Slot(new Element("Rim light", "behind, for a bright edge", PURPLE, false), 160, 0.55f, 0.3f));
                break;
            }
        }

        // Try 24 camera directions around the subject and keep the one where everything fits best.
        float bestScore = -1e9f, bestTheta = 0;
        for (int k = 0; k < 24; k++) {
            float theta = (float) (k * Math.PI * 2 / 24);
            float score = 0;
            for (Slot s : slots) {
                double a = theta + Math.toRadians(s.angleDeg);
                float x = p.sx + (float) Math.cos(a) * s.dist, z = p.sz + (float) Math.sin(a) * s.dist;
                float w = s.e.isCamera ? 3f : 1f;
                score += free(x, z) ? w : -w;
                score -= 0.4f * w * obstruction(p.sx, p.sz, x, z);
            }
            if (score > bestScore) { bestScore = score; bestTheta = theta; }
        }
        // Product lights sit on or around the table, so free floor doesn't matter there.
        boolean onTable = scene == Scene.PRODUCT;
        p.cramped = !onTable && bestScore < slots.size();
        for (Slot s : slots) {
            double a = bestTheta + Math.toRadians(s.angleDeg);
            s.e.x = p.sx + (float) Math.cos(a) * s.dist;
            s.e.z = p.sz + (float) Math.sin(a) * s.dist;
            s.e.y = p.sy + s.height;
            p.elements.add(s.e);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(scene.label).append(" (tap the scan to move the subject)\n");
        int unsure = 0;
        for (Element e : p.elements) {
            boolean ok = onTable || free(e.x, e.z);
            if (!ok) unsure++;
            sb.append(colorName(e.color)).append(' ').append(e.name).append(" - ").append(e.role)
                    .append(ok ? "" : " *").append('\n');
        }
        if (unsure > 0) sb.append("* spot not clearly scanned, check it");
        else if (p.cramped) sb.append("Tight space: scan more floor for a better layout");
        p.explanation = sb.toString().trim();
        return p;
    }

    private static String colorName(float[] c) {
        if (c == BLUE) return "\uD83D\uDD35";
        if (c == ORANGE) return "\uD83D\uDFE0";
        if (c == YELLOW) return "\uD83D\uDFE1";
        if (c == PURPLE) return "\uD83D\uDFE3";
        if (c == CYAN) return "\uD83D\uDFE2";
        return "";
    }

    String summary() {
        if (!ready) return "planner: not analysed";
        int known = 0, blk = 0;
        for (int i = 0; i < floorKnown.length; i++) { if (floorKnown[i]) known++; if (blocked[i] > BLOCKED) blk++; }
        return String.format(Locale.US, "planner: floor y=%.2f, grid %dx%d, floor cells %d, blocked cells %d",
                floorY, nx, nz, known, blk);
    }

    @SuppressWarnings("unused")
    private static float[] copy(float[] a) { return Arrays.copyOf(a, a.length); }
}
