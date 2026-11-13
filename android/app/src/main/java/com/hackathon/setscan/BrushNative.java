package com.hackathon.setscan;

/**
 * Brush (https://github.com/ArthurBrussee/brush, Apache-2.0) on the phone: the photo-real room preview trainer, as on
 * the iPhone (BrushTrainer.swift). libbrush_c.so is Brush's C library built for Android (tools/brush-android), and
 * libbrushjni.so the small bridge to it (tools/brush-android/brushjni.c). Both are built off the phone and are not in
 * git: an APK without them simply has no "Make photo-real" (available() is false).
 */
final class BrushNative {
    private BrushNative() { }

    private static Boolean loaded;

    /** Whether this APK carries Brush, without loading it (the main app process never loads it; only :photoreal does). */
    static boolean present(android.content.Context ctx) {
        try { return ((dalvik.system.BaseDexClassLoader) ctx.getClassLoader()).findLibrary("brush_c") != null; }
        catch (Throwable e) { return false; }
    }

    static synchronized boolean available() {
        if (loaded == null) {
            try { System.loadLibrary("brush_c"); System.loadLibrary("brushjni"); loaded = true; }
            catch (Throwable e) { loaded = false; }
        }
        return loaded;
    }

    /** Called from Brush's thread: tag 0 new process, 1 training (iter = step), 2 done. The listener may sleep (heat
     *  rests): Brush waits for it. */
    interface Progress { void on(int tag, int iter); }

    /** Trains from a Nerfstudio dataset folder and writes the splat .ply into outDir. Blocks; returns 0 on success.
     *  maxSplats 0 = Brush's default; shDegree 255 = Brush's default. */
    static native int train(String dataset, String outDir, int steps, int refineEvery, int maxResolution, int exportEvery,
                            int maxSplats, int shDegree, Progress progress);

    /** Stops a running train() (it returns non-zero). */
    static native void cancel();

    /** setenv for Brush's settings (the refine memory guard, CubeCL), before train(). */
    static native void setenv(String name, String value);
}
