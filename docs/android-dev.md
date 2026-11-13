# Android: working on the UI in the emulator (for Zeynep)

From 13 Nov 2026 Zeynep owns Android development as well. She works on the UI in the Android Studio emulator, with no
phone and no scanning. rk tests on the Galaxy S24. Read [CLAUDE.md](../CLAUDE.md) first for the code layout and rules.

## Which branch

Work on **`claude/project-thread-k4b688`**, not `main`. It holds all the Android work up to now: the capture zone,
the hints and the "Circle around" animation, stray-dot removal, Make photo-real with Brush, the Photo-real tab and the
readable build stamp. It already includes everything on `main`.

`main` stays behind on purpose. A push to `main` that touches `android/` makes CI build the APK and publish it as a
public GitHub release, and CI's APK has no photo-real (see below). Merge to `main` only when rk says so.

```
git fetch origin
git checkout -b android-zeynep origin/claude/project-thread-k4b688   # her own branch off it
```

Push her own branch, and rk merges it. Two people pushing the same branch at once lose work.

## Open and run

1. In Android Studio, **File > Open** the `android/` folder (not the repo root). Use JDK 17 or 21 (Settings > Build >
   Gradle > Gradle JDK). Gradle 8.14 doesn't run on Java 25.
2. Create the emulator. On an Apple Silicon Mac, use a **Pixel** device with an **arm64-v8a "Google Play"** system
   image (API 34 or 35). The APK ships arm64 only (`abiFilters 'arm64-v8a'`), so an x86_64 image refuses it, or runs
   it only through slow translation.
3. Run the `app` configuration, or use the command line: `cd android && ./gradlew installDebug`.

The debug signing key is in the repo (`android/app/debug.keystore`), so her builds sign the same way as rk's.

## What doesn't work in the emulator

- **Scanning.** ARCore depth needs the real camera. The manifest asks for ARCore (`com.google.ar.core` required), so
  on first start the app asks to install Google Play Services for AR. On a Play image that may work, but the emulator
  has no depth camera, so a scan won't give dots. Nobody has tried the app in an emulator yet, so note what happens.
- **To see the room screen**, open a saved room file instead of scanning. Drag a `.rmsc` file onto the emulator
  (it goes to Downloads), then open it from the Files app with RoomScape. Ask rk for a test file that doesn't show his
  home. Scans are private data: never commit them or put them anywhere public.
- **Make photo-real (Brush).** The Brush libraries (`libbrush_c.so`, `libbrushjni.so`) are not in git: they go in the
  git-ignored `android/app/src/main/jniLibs/arm64-v8a/`, built by `tools/brush-android/build.sh` (Rust and NDK 27,
  not needed for UI work). Without them the app simply hides "Make photo-real". The **Photo-real tab** in the room
  view shows only for a scan that already has `splat/preview.ply`. For UI work on it, rk can send a scan that has one.
- **Heat, memory and speed** behave differently from the S24, so check anything timing-related on the phone.

## Build stamp rule

Every build shows a readable stamp, **"Build 13 Nov 17:15:11"** (Tallinn time), on the home screen and in every
"build ready" message, on its own line. It comes from `build.gradle` (`resValue build_stamp`). The numeric
versionCode (`yyDDDHHmm`) stays internal, so never quote it to rk. A new build always installs over an older one.

## Where things are

- Screens, buttons and menus: `MainActivity.java`. The room view's Photo / Points / Mesh / Photo-real tabs are around
  `styleSwitch`, the ✦ menu is `showViewerMenu()`, and the scan hints are `SCAN_TIPS`, `OrbitIcon`, `ZoneFrame`.
- Make photo-real screen: `PhotoRealActivity.java` (it runs in its own process). The Photo-real tab's viewer is the
  iPhone's `CineX.swiftpm/SplatViewer/index.html`, copied in at build time, so a change there affects both apps.
- Drawing: `ScanRenderer.java` (GL). Most UI work doesn't need it.
