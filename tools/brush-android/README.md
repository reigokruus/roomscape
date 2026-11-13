# Brush for the Android app (photo-real room preview trainer)

The Android twin of `tools/brush-ios`. Brush (https://github.com/ArthurBrussee/brush, Apache-2.0) commit 1388f74 with
`brush-android.patch` (cumulative: everything in `../brush-ios/brush-ios.patch` plus the Android changes, each marked
"RoomScape change"):

- `crates/rrfd`: Android like iOS, no file picker (Brush's Android picker needs an older `jni`; the app drives brush-c).
- `crates/brush-rerun`: no rerun SDK on Android either (the no-op tools).
- `brush-process`: the `Direct` GPU memory pool, the two-stage refine memory guard (BRUSH_REFINE_MIN_AVAIL_MB 1700 until
  two refines ran, then BRUSH_REFINE_MIN_AVAIL_LATER_MB 800; skips in brush-out/refine-skips.txt), on Android too.
- `brush-train` memmarks: on Android "available" is the system's MemAvailable (/proc/meminfo, what the low-memory
  killer watches) and the footprint is the process's resident memory (/proc/self/statm).
- `brush-dataset` / `brush-c`: two loaders and a 384 MiB picture cache on Android too.
- cubecl: the same vendored copy and `../brush-ios/cubecl-ios-metal.patch` as the iPhone (Android uses Vulkan, unchanged).

Build (built 13 Nov 2026 with rustc 1.97.0, NDK 27.2.12479018, in a Linux cloud machine; about 7 min on 4 cores):

    rustup target add aarch64-linux-android
    git clone https://github.com/ArthurBrussee/brush && cd brush && git checkout 1388f74
    git apply <repo>/tools/brush-android/brush-android.patch
    cp <repo>/tools/brush-ios/Cargo.lock .
    git clone https://github.com/tracel-ai/cubecl vendor/cubecl
    (cd vendor/cubecl && git checkout 467522442c7b && git apply <repo>/tools/brush-ios/cubecl-ios-metal.patch)
    ANDROID_HOME=<sdk> <repo>/tools/brush-android/build.sh "$PWD"

That writes `android/app/src/main/jniLibs/arm64-v8a/libbrush_c.so` (about 98 MB, stripped) and `libbrushjni.so`
(`brushjni.c`, the JNI bridge for `BrushNative.java`), both git-ignored. An APK built without them has no
"Make photo-real"; CI builds are like that.

In the app: ✦ menu > Make photo-real (a warning first) opens `PhotoRealActivity` in its own process (`:photoreal`):
dataset from the scan's rebuild-frames (`SplatDataset`, plan as the iPhone's `SplatPacing`: `SplatPlan`), Brush at
720 px, SH 1, refine every 200, heat rests by Android's thermal status, then the iPhone's splat viewer
(`CineX.swiftpm/SplatViewer`, copied into the APK's assets at build time). Result: `<scan>/splat/preview.ply`, log
`<scan>/splat/train-log.txt`. Not yet: checkpoints / carrying on after the app was closed (the iPhone has them).
