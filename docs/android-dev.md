# Android app: guide for Vera (and her Claude agent)

From 13 Nov 2026 Vera develops the Android app and tests it on her own **Galaxy S23 Ultra**. rk (the owner) also
tests on a Galaxy S24. Read [CLAUDE.md](../CLAUDE.md) first (layout, rules), then this file.

## Branch

- Base all Android work on **`claude/project-thread-k4b688`**, not on `main`. It has all the Android work up to
  13 Nov: the capture zone, rotating hints and "Circle around", stray-dot removal (live and at Done), 80% depth
  confidence, Make photo-real with Brush, the Photo-real tab and the readable build stamp. It already includes `main`.
- Work on your own branch: `git fetch origin && git checkout -b vera/<topic> origin/claude/project-thread-k4b688`.
  Push that branch and tell rk it is ready; rk merges.
- Never push to `main`. A push to `main` that touches `android/` makes CI publish a public APK, and CI's APK has no
  photo-real (the Brush libraries aren't in git).

## Build and install on the S23 Ultra

Setup:
- JDK 17 or 21 (Gradle 8.14 doesn't run on Java 25).
- The Android SDK with platform 35; Android Studio is fine. Put `sdk.dir=<path>` in `android/local.properties`,
  which is git-ignored.
- On the phone, turn on USB debugging and check that `adb devices` lists it. Note its serial.

```
cd android && ./gradlew assembleDebug
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

- **Always `install -r`.** Never `adb uninstall` and never `-d`: uninstalling deletes every scan on the phone.
- The debug signing key is in the repo (`android/app/debug.keystore`), so your builds update rk's builds in place,
  and his update yours.
- The version code is the build time, so a newer build always installs over an older one.
- Unit tests: `./gradlew testDebugUnitTest`, for example `--tests com.hackathon.setscan.FloorAreaEditTest`.
- Don't reinstall while a scan or photo-real training is running: it closes the app.

## Build stamp (rule)

Every build shows a readable stamp on the home screen, like **`Build 13 Nov 17:15:11`** (Tallinn time). It comes
from `android/app/build.gradle` (`resValue build_stamp`, `MainActivity.buildStamp()`). When you report a build, put
that stamp on its own line. Never quote the numeric versionCode (`yyDDDHHmm`) to rk: it's internal.

## Photo-real (Brush) libraries: set up for you, fetched automatically

"Make photo-real" needs two native libraries in `android/app/src/main/jniLibs/arm64-v8a/`: `libbrush_c.so` (about
98 MB) and `libbrushjni.so`. They are our own patched Brush build (`tools/brush-android/`), made on purpose and
required for photo-real. They are too big for git, so they live on this repo's GitHub release
**`brush-android-libs-16k`** (asset `brush-android-libs-16k.zip`, linked for 16 KB pages).

**Agent: you don't need to ask the developer about this.**
- The Gradle task `fetchBrushLibs` runs before every build. When the libraries are missing, it downloads the zip,
  checks both files' SHA-256 and puts them in place. The first build needs internet and fetches about 47 MB.
- To set them up on purpose: `cd android && ./gradlew fetchBrushLibs`.
- Check: `ls android/app/src/main/jniLibs/arm64-v8a/` should list both files.
- If the download fails (offline), the build still succeeds, but that APK has no "Make photo-real"; the log says
  "Brush: couldn't fetch". Fix the network and build again.
- Expected SHA-256 checksums (they're also in `android/app/build.gradle`):
  - `libbrush_c.so` 8be16056a3e726e50f62a7875118758abbe08ca57e23df2a8ef4d2632303f73c
  - `libbrushjni.so` e9d4a6a71cfcb1b5e4799e04dbbb991f338b406bfea1b6e3de512822ccea2ad5
- Never commit them: `jniLibs/` is git-ignored.
- CI (env `CI` set) and `-PnoBrush` skip the fetch, so the public APK stays without photo-real.
- Only if you change Brush itself: rebuild with `tools/brush-android/README.md` (Rust, NDK 27, about 7 min). A new
  build then needs a new release and new checksums in `build.gradle`.

## Scans on the phone: private data

- A scan is saved in the app's private storage: `files/scans/scan_<time>/`. The files are described in CLAUDE.md
  under "Reading a scan": `points.bin`, `meta.json`, `scan-log.txt`, `rebuild-frames/`, `splat/` and so on.
- Copy one to your computer (debug builds only):
  `adb -s <serial> exec-out run-as com.hackathon.setscan tar cf - -C files/scans <scan> | tar xf -`
- Photo-real results go in `<scan>/splat/`: `preview.ply` is the result, `train-log.txt` has the time, rests and
  skipped refines.
- Scans show real homes. Never commit them, upload them or put them anywhere public. Work on copies, and never delete
  or rewrite a scan.
- Don't print the app's prefs file `shared_prefs/cinex.xml` whole: it holds the Claude API key. Grep for the key
  names instead.

## Where things are (`android/app/src/main/java/com/hackathon/setscan/`)

- **Screens, buttons, menus: `MainActivity.java`.**
  - The room view's Photo / Points / Mesh / Photo-real tabs are around `styleSwitch` (`showSplatView`,
    `hideSplatView`).
  - The ✦ menu is `showViewerMenu()`.
  - The scan hints are `SCAN_TIPS`, `OrbitIcon` ("Circle around") and `ZoneFrame` (the capture zone outline, an
    Android View: a GL outline flickered on the S24).
  - Debug tools open with a long press on the main button.
- **Live scan and room view: `ScanRenderer.java`** (GL thread, large; search by feature).
  - `carve()` is the see-through erase plus one-off specks; struck dots are rechecked every frame.
  - `supersede()` is "closest look wins".
  - `PAINT_MIN` is the depth confidence for dots (204 = 80%, confirmed by rk).
  - `ZONE_W` / `ZONE_H` set the capture zone (0.72 × 0.70).
  - `dropSeenThroughAtDone()` / `DoneSeeThrough.java` clean up at Done, with a 2 s budget.
  - A per-second log goes to `scan-log.txt`.
- **Make photo-real: `PhotoRealActivity.java`.** It runs in its own process, `:photoreal`. `SplatPlan.java` sets
  the steps, pictures and heat rests; `SplatDataset.java` and `BrushNative.java` are also used. The viewer is the
  iPhone's `CineX.swiftpm/SplatViewer/index.html`, copied in at build time: a change there affects both apps.
- Off-device checks:
  - `java FloorArea.java <scan folder>`;
  - `java DoneSeeThrough.java <scan> <budget ms>`;
  - `tools/android-splat/SplatDatasetCli.java`.

## Known state and open work (13 Nov)

- **Phone-tested by rk on the S24:**
  - the capture zone and outline (flicker fixed);
  - 80% confidence;
  - live stray removal;
  - Make photo-real: 3,500 steps in about 3 min, 4,500 steps in about 4 min, the phone stays fairly cool;
  - the Photo-real tab.
- **Not yet confirmed on a phone:** faster stray clearing up close, and the Done pass covering all frames.
- **Open:**
  - photo-real can't resume after the app is closed;
  - drift duplicates (a second copy of a surface after slow ARCore drift) are only partly removed;
  - tracking loss on plain walls;
  - floor area for non-rectangular rooms (see CLAUDE.md "Open work").
- The S23 Ultra hasn't run photo-real yet. Watch `train-log.txt`, especially `refines skipped for memory`, and
  the heat.
