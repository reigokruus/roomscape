package com.hackathon.setscan;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Depth Anything V2 Small (Apache-2.0, LiteRT export from litert-community, int8 weights) on the phone's CPU.
 * One upright photo in, relative inverse depth ("disparity", bigger = closer) out, 686x518.
 * The scale is unknown: callers fit 1/depth = a * disparity + b against ARCore's measured points.
 */
final class DepthNet {
    static final String MODEL = "depth_anything_v2_small.tflite";
    static final int IW = 686, IH = 518;
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f}, STD = {0.229f, 0.224f, 0.225f};

    private final Interpreter it;
    private final ByteBuffer in = ByteBuffer.allocateDirect(3 * IW * IH * 4).order(ByteOrder.nativeOrder());
    private final ByteBuffer out = ByteBuffer.allocateDirect(IW * IH * 4).order(ByteOrder.nativeOrder());
    private final int[] px = new int[IW * IH];

    DepthNet(Context ctx) throws Exception {
        AssetFileDescriptor fd = ctx.getAssets().openFd(MODEL);
        FileChannel ch = new FileInputStream(fd.getFileDescriptor()).getChannel();
        MappedByteBuffer mb = ch.map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
        Interpreter.Options o = new Interpreter.Options();
        o.setNumThreads(Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() - 2)));
        it = new Interpreter(mb, o);
    }

    /** Relative disparity for an upright photo, row-major IW x IH (stretched to the model's fixed size, as it was exported). */
    float[] run(Bitmap upright) {
        Bitmap s = Bitmap.createScaledBitmap(upright, IW, IH, true);
        s.getPixels(px, 0, IW, 0, 0, IW, IH);
        if (s != upright) s.recycle();
        FloatBuffer f = in.asFloatBuffer();
        int plane = IW * IH;
        for (int i = 0; i < plane; i++) {
            int c = px[i];
            f.put(i, (((c >> 16) & 255) / 255f - MEAN[0]) / STD[0]);
            f.put(plane + i, (((c >> 8) & 255) / 255f - MEAN[1]) / STD[1]);
            f.put(2 * plane + i, ((c & 255) / 255f - MEAN[2]) / STD[2]);
        }
        in.rewind(); out.rewind();
        it.run(in, out);
        out.rewind();
        float[] r = new float[plane];
        out.asFloatBuffer().get(r);
        return r;
    }

    void close() { it.close(); }
}
