# CLAUDE.md: notes for an AI assistant (or a new developer) taking over this repo

Read [README.md](README.md) first: what the apps do, Android vs iPhone, maturity per feature and the TODO column.
This file is how to work in the code.

## Layout

- `android/`: the Android app (Java, OpenGL ES; ARCore). One Gradle module, `android/app`.
  Package `com.hackathon.setscan` (keep it: installed builds update in place). Main files in
  `android/app/src/main/java/com/hackathon/setscan/`:
  - `ScanRenderer.java`: the scan and the result view (GL thread): ARCore frames, depth fusion, tracking loss and
    re-align, flat tiles hook-up, room analysis, Measure, wall dragging, the per-second state log. Large; search by
    feature name.
  - `MainActivity.java`: all screens and buttons, debug tools (long-press the main button), touch handling.
  - `FloorArea.java`: floor area, split into rooms at doorways, wall-bounded outline. Pure Java: also runs on a Mac
    (`java FloorArea.java <scan folder> [floorY] [out.ppm]`).
  - `FlatTiles.java`: the 30 cm camera-picture tiles on flat surfaces.
  - `Realign.java`, `ContinueAlign.java`: putting the view back on the scan after a tracking loss / for Continue scan.
  - `Tsdf.java`, `GpuTsdf.java`: the surface; `RoomModel.java`: room size, walls, ceiling; `DoorwayFinder.java`.
  - `ScanStore.java` (saved scan folder), `Rmsc.java` (.rmsc export/import), `Nearby.java` (phone to phone).
- `CineX.swiftpm/`: the iPhone app (Swift, SwiftUI, Metal; RoomPlan + ARKit). Opens in Xcode.
  - `ScannerScreen.swift` (scan flow, RoomPlan session), `LiveDiscs.swift` + `LiveCloud.swift` (live points and their
    drawing), `FlatTiles.swift` (iPhone port of the tiles), `ResultView.swift` (result screen), `Rmsc.swift`.
  - Test switches live in `ProcessingQueue.swift` (`ProcessingSection`) and show on the home screen with a build stamp.
- `tools/`: Mac-side tools. `tools/harness` (Swift package: the iPhone app's non-ARKit code on synthetic rooms and
  real recordings; `swift build -c release`, then `./.build/release/Harness <command>`), `tools/tile-anchor` (offline
  Java checks on Android scan folders), install and watch scripts for the developer's own phones.
- `docs/`: [rmsc-format.md](docs/rmsc-format.md) (the shared file format: keep it version 1, add only optional
  fields, update the doc with any change), test harness, recordings, handoff notes.

## Build and test

- Android: `cd android && ./gradlew assembleDebug` (JDK 17 or 21; Gradle 8.14 doesn't run on Java 25). The version
  code is the build time `yyDDDHHmm` unless `-PbuildNumber=` is given, so a new build always installs over an older
  one. `-PguestBuild` makes camera recording and screen video start off (for someone else's phone).
  Unit tests: `./gradlew testDebugUnitTest` (e.g. `--tests com.hackathon.setscan.FloorAreaEditTest`).
- iPhone: Xcode 26 or newer, deployment target iOS 17. Command line:
  `cd CineX.swiftpm && xcodebuild -scheme RoomScape -destination 'generic/platform=iOS' build`.
  Metal shaders are compiled at runtime from strings in the Swift files: Xcode doesn't check them, so check a changed
  shader on the Mac (`MTLCreateSystemDefaultDevice().makeLibrary(source:options:)` in a small Swift script).
- Shared Swift logic: `swift test` from the repo root (needs full Xcode selected).
- CI: a push to `main` that touches `android/` builds the APK and publishes a GitHub release (`CineX-android.apk`).

## Rules that matter here

- **Scans are private data.** Scan folders, recordings (`recording.mp4`, `screen.mp4`), photos and logs show real
  homes. Never commit them (`recordings/` is git-ignored), never put them in a public place, and keep them read-only
  when analysing: work on copies, never delete or rewrite a scan.
- **Don't commit personal signing settings**: `CineX.swiftpm/Package.swift`'s bundle ID and team ID are set locally by
  each developer and must stay out of commits.
- **The .rmsc format is shared** by both apps and possibly other readers: optional additions only; readers must ignore
  what they don't know.
- **Say what was and wasn't tested.** Most features can only be really checked on a phone; offline checks (unit tests,
  the harness, `FloorArea.java` on saved scans) are useful but not the same. The README's maturity words should stay
  honest.
- Measurements shown to users are estimates (≈, "about"); don't present them as exact.

## Open work (see README TODO column)

- **Floor area for non-rectangular rooms and open-plan spaces** (suggested owner: Daniel). Code: `FloorArea.java`
  (`compute`, `wallRoom`, the room split after "rooms: open with a 45 cm disc"), tests in
  `android/app/src/test/java/com/hackathon/setscan/FloorAreaEditTest.java`, wall dragging in `ScanRenderer.java`
  (`wallEdits`, `wallDragStart`). Saved as `floor-area.json` and `floorArea` in the .rmsc manifest. Ideas: an outline
  that follows the walls instead of one rectangle per room; a manual split (draw a line across the floor). Ask for a
  few test scans: they're kept off the repo because they show a private home.
- Android tracking loss on plain walls; the tile check (`tile check (shadow)` lines in `scan-log.txt`) only logs.
- iPhone floor area (`FloorRooms.swift`): two rooms or more from the points with `Planning/Core/RoomSplit.swift` (a
  line-for-line port of `FloorArea.java`: keep the two in step; `RoomSplitTests` compares them on scans when
  `CINEX_FLOOR_SCANS` is set), else RoomPlan's floor outline; tap a room, drag its sides (`floor-edits.json`),
  numbers in `floor-area.json` and the .rmsc. Outline maths in `Planning/Core/FloorOutline.swift`, with tests.
- iPhone: the carry-on past RoomPlan's limit is untested.
- Other phones (e.g. Galaxy S23+) not tried yet.

## Reading a scan (Android)

A scan folder (`files/scans/scan_<time>/` in the app's private storage) holds `points.bin` (int count, then per point
float x, y, z + 3 bytes colour, big-endian), `meta.json`, `scan-log.txt` (the event log: tracking, re-align, tiles,
room, floor area lines), `perf-scan.summary.json` (timing and heat), `tiles.json` + `tiles.png`, `floor-area.json`,
`floor-edits.json` (walls dragged by hand), `events-depth.bin` (depth frames around tracking events), and optionally
`recording.mp4` / `screen.mp4`. Copy one off a phone with `adb exec-out run-as com.hackathon.setscan tar cf - -C
files/scans <scan> | tar xf -` (debug builds only).
