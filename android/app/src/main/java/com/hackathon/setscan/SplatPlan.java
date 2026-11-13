package com.hackathon.setscan;

import java.util.Locale;

/** How much training a scan gets on the phone and the heat pacing: the iPhone's SplatPacing (Planning/Core), the same numbers. */
final class SplatPlan {
    final int steps, pictures, maxSplats, startPoints;
    private SplatPlan(int steps, int pictures, int maxSplats, int startPoints) { this.steps = steps; this.pictures = pictures; this.maxSplats = maxSplats; this.startPoints = startPoints; }

    @Override public String toString() { return String.format(Locale.US, "%d steps, %d pictures, %dk splats, %dk start points", steps, pictures, maxSplats / 1000, startPoints / 1000); }

    private static double lerp(double x, double x0, double x1, double y0, double y1) { return y0 + (y1 - y0) * Math.min(1, Math.max(0, (x - x0) / (x1 - x0))); }

    /** points: the scan's dots; frames: the saved rebuild pictures (twice a second already, so each is a candidate;
     *  the iPhone allows one in 4 of its 30 fps video frames). */
    static SplatPlan of(int points, int frames) {
        double p = Math.max(0, points);
        double steps = p <= 600_000 ? lerp(p, 150_000, 600_000, 3000, 6000) : lerp(p, 600_000, 1_500_000, 6000, 9000);
        double pics = p <= 600_000 ? lerp(p, 150_000, 600_000, 150, 250) : lerp(p, 600_000, 1_500_000, 250, 300);
        int maxSplats = p <= 150_000 ? 150_000 : 200_000;
        int pictures = Math.max(20, Math.min((int) Math.round(pics), frames > 0 ? Math.max(20, frames) : (int) pics));
        return new SplatPlan((int) Math.round(steps / 500) * 500, pictures, maxSplats, Math.min(maxSplats / 2, Math.max(1, points)));
    }

    /** Rest after training, as a share of the time it just took, by Android's thermal status (0 none, 1 light,
     *  2 moderate, 3 severe, 4+ critical / emergency / shutdown). As the iPhone: none 0.1, light 0.4 (its fair), moderate
     *  and severe 1.0 (its serious: half speed at most); null at critical: pause until the phone cools. */
    static Double restFactor(int thermal) {
        if (thermal <= 0) return 0.1;
        if (thermal == 1) return 0.4;
        if (thermal <= 3) return 1.0;
        return null;
    }

    static String note(int thermal) {
        if (thermal == 1) return "Running a little slower to keep the phone cool";
        if (thermal >= 4) return "Paused: the phone is hot, carries on when it cools";
        return null;
    }
}
