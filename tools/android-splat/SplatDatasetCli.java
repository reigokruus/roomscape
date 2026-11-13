package com.hackathon.setscan;

import java.io.File;
import java.io.IOException;

/** The Android app's photo-real dataset (SplatDataset.export) made off the phone, to check it with Brush on a PC. */
final class SplatDatasetCli {
    /**
     * On a Mac or PC, on a COPY of a scan folder (never the phone's own):
     *   javac -d /tmp/sd android/app/src/main/java/com/hackathon/setscan/SplatDataset.java tools/android-splat/SplatDatasetCli.java
     *   java -cp /tmp/sd com.hackathon.setscan.SplatDatasetCli <scan folder copy> <out folder>
     * Picture sharpness here: the variance of a 4-neighbour Laplacian on the grey picture (javax.imageio), the same
     * idea as the phone's. Then train, e.g. brush <out folder> (Nerfstudio layout: transforms.json + images/).
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 2) { System.err.println("SplatDatasetCli <scan folder copy> <out folder>"); System.exit(2); }
        int n = SplatDataset.export(new File(args[0]), new File(args[1]), SplatDatasetCli::laplacianVariance);
        System.out.println(n + " pictures picked; transforms.json, images/ and points.ply in " + args[1]);
    }

    static float laplacianVariance(File jpg) {
        try {
            java.awt.image.BufferedImage im = javax.imageio.ImageIO.read(jpg);
            if (im == null) return Float.NaN;
            int w = im.getWidth(), h = im.getHeight(), step = Math.max(1, w / 320);
            int gw = w / step, gh = h / step; float[] g = new float[gw * gh];
            for (int y = 0; y < gh; y++) for (int x = 0; x < gw; x++) {
                int c = im.getRGB(x * step, y * step);
                g[y * gw + x] = 0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255);
            }
            double sum = 0, sum2 = 0; int k = 0;
            for (int y = 1; y < gh - 1; y++) for (int x = 1; x < gw - 1; x++) {
                float l = 4 * g[y * gw + x] - g[y * gw + x - 1] - g[y * gw + x + 1] - g[(y - 1) * gw + x] - g[(y + 1) * gw + x];
                sum += l; sum2 += l * l; k++;
            }
            if (k == 0) return Float.NaN;
            double mean = sum / k; return (float) (sum2 / k - mean * mean);
        } catch (Exception e) { return Float.NaN; }
    }

}
