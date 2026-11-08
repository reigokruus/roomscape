# RoomScape

**A set-up assistant for real rooms.** Scan your room with an iPhone, tell the app what gear you own, pick the kind of scene you want, and it shows you where to put the person, the camera and the lights. It's built for everyday creators, not film crews. (The project started as CineX; the iPhone app is now called RoomScape.)

## What the iPhone app does
- **Scan** a room with RoomPlan and LiDAR, with a live surface mesh while you walk. You get walls, doors, windows and furniture, plus the LiDAR points and photos of the room.
- **See the room** as splats in the scanned colours (Photo), a white mesh with a grid (Mesh), or RoomPlan's model. **Sharpen** trains the splats against the scan's photos on the phone's GPU.
- **Objects:** RoomPlan's furniture, plus lamps, ceiling lights and power sockets that Claude finds in the photos. Remove, move and turn things to plan around them.
- **Lighting preview:** place the person and your own lights (from My Gear) in the scanned room and see the light on their face, the exposure and a camera's-eye view. The room's own lights and windows count, and cable runs to the nearest socket are shown. **Set it up with Claude** plans a whole shot (pose, lights, camera, lens) from the floor plan and your gear.
- **Storyboard:** a measured top-down plan of the room with several views, each with its own camera, lens, kit and budget, and a frame preview rendered from the scan.
- **Plan a Shoot:** plain-language placement tips for talking heads, interviews, vlogs, product shots and lifestyle scenes.

Claude features need your own Claude API key, kept in the phone's Keychain. Everything else works offline.

## Test a native app now

See [Android APK install/build instructions and the iOS hardware/build gate](docs/testable-app.md) and [actual compile/test evidence](docs/testable-build-evidence.md). The Android APK is native ARCore code, not the browser prototype; the iOS Swift storyboard additions are a separate feature set and are not in that APK.

## Internet-sourced item catalog and scanned-item planning (Android)

[Catalog / scanned-item feature and validation guide](docs/catalog-and-scanned-items.md): offline searchable real-model dimensions with provenance, explicit internet JSON-feed/local-file imports, physical-size placement/manual overrides, and non-destructive hide/restore/undo of detected furniture shapes in a separate planning view. Original fused scans/photos are preserved; no mesh erasure, unseen-surface reconstruction or iOS feature-parity claim.


## Optional paid-pilot interest (Android)

[Local demo notebook and phone acceptance guide](docs/pilot-interest.md): explicit paid-follow-up opt-in, named demo host, editable contacts, revoke/delete, and deliberate consent-only CSV/JSON export. Encrypted app-private storage; **no central collection or automatic team receipt**, payments or tracking. Personal data is separate from room/scan exports. This is interest, not a confirmed paying customer.

## Status
**v0.1 (hackathon prototype).** The iPhone app builds with Xcode 26 and runs on LiDAR iPhones. The shared planning and splat-training logic is covered by `swift test` from the repository root (it needs full Xcode selected, e.g. `sudo xcode-select -s /Applications/Xcode.app`: the Command Line Tools alone have no XCTest). The storyboard's items are measured planning envelopes, not photorealistic assets, and catalog dimensions and rates are estimates. See [native planning notes](docs/native-planning.md) and [verification evidence](docs/native-planning-verification.md).

## Why not Previs Pro (or similar apps)?
Previs Pro is a **pro storyboarding tool**. You build virtual sets from a library and plan shots for a script. It doesn't scan your room, know your gear or suggest where to put things. RoomScape starts from the **real room and the gear you actually own**. See [docs/positioning.md](docs/positioning.md) for the full comparison, including WATT-IF, Polycam, Scaniverse, Photo Studio AR and Shot Designer. Polycam and Scaniverse capture rooms very well, but they stop at the scan. RoomScape uses the scan to plan the shoot.

## iPhone version (main)
Porting the Android features to iPhone? Start with [docs/android-to-ios-handoff.md](docs/android-to-ios-handoff.md).

This is the `CineX.swiftpm` folder (the app inside is RoomScape). It requires an **iPhone with LiDAR** (iPhone 12 Pro or newer Pro / Pro Max) on iOS 17+, and a Mac with **Xcode 15 or newer**.

### Build and install on an iPhone
1. Download this repo (green **Code** button › **Download ZIP**) and unzip it.
2. Double-click **CineX.swiftpm**. It opens in Xcode.
3. Connect the iPhone by cable and tap **Trust**. On the iPhone, turn on Settings › Privacy & Security › **Developer Mode** (the phone restarts).
4. In Xcode, pick the iPhone as the run destination at the top. In the project's **Signing & Capabilities**, choose your Apple ID as the Team (add it under Xcode › Settings › Accounts first; a free Apple ID works).
5. Press ▶︎ Run. The first time, approve the developer account on the iPhone under Settings › General › VPN & Device Management, then run again.

Apps signed with a free Apple ID stop working after 7 days. Just press Run again to reinstall.


## Team work in this repo
- **Frame check (shot fit):** [`FrameCheck/`](FrameCheck/). See [README_framecheck.md](FrameCheck/README_framecheck.md).
- **Browser planning prototype:** [`hackathon/ui_prototype.html`](hackathon/ui_prototype.html) covers the storyboard, kit budgets, presets and scale-aware placement on a sample room. Run `node hackathon/verify_v3.mjs` to test it.
- **Pitch pack:** [`hackathon/pitch_deck.html`](hackathon/pitch_deck.html) and [`hackathon/pitch_script.md`](hackathon/pitch_script.md).
- **iPhone app additions:** gear inventory, shoot planner, and the mesh overlay while scanning, all in `CineX.swiftpm`.
- **Integrated native planning:** [`CineX.swiftpm/Planning/`](CineX.swiftpm/Planning/); [build/validation handoff](docs/native-planning.md). Export formats are native JSON (not the older browser-prototype format); no hosted sync or photorealistic lighting is claimed.
- **Synthetic UI test fixture:** [`Tests/Fixtures/synthetic-room.json`](Tests/Fixtures/synthetic-room.json), importable via Planning projects without LiDAR; explicitly not a real scan.

## Android version (Samsung and other ARCore phones)
This is the `android/` folder: a separate Android Studio project, so you can develop on Windows or Linux without a Mac. The iPhone version above is unaffected.

Android has no equivalent of Apple's RoomPlan, so this version uses ARCore depth to capture the room as a coloured 3D point cloud. You can rotate it and switch to a top-down view, and it shows the path you walked. It works on ARCore depth-capable phones such as the Galaxy S24. LiDAR isn't required.

### Develop on a Windows/Linux laptop
1. Install [Android Studio](https://developer.android.com/studio).
2. **File › Open** the `android` folder (not the repo root), and let Gradle sync.
3. On the phone, turn on Developer options (Settings › About phone › Software information › tap **Build number** 7 times), then turn on **USB debugging**.
4. Plug the phone in, accept the prompt, pick it in the device list and press ▶︎ Run.

From the command line: `cd android && ./gradlew assembleDebug` (on Windows, `gradlew.bat assembleDebug`). The APK ends up in `android/app/build/outputs/apk/debug/`.

### Get new builds on the phone automatically
Every push that changes `android/` is built by GitHub and published as a release, so the phone can always download the newest build from **https://github.com/reigokruus/shootlocal/releases/latest/download/CineX-android.apk**.

For hands-free updates, install [Obtainium](https://github.com/ImranR98/Obtainium) on the phone and add `https://github.com/reigokruus/shootlocal`. It notifies you of each new build and installs it with one tap.

(The Android package ID stays `com.hackathon.setscan` on purpose so already-installed builds keep updating.)

All builds, whether from GitHub or from Android Studio, use the same signing key (`android/app/debug.keystore`, a debug key committed on purpose), so they install over each other. If Android refuses to install, uninstall the old CineX once and try again.

On first launch, the phone may ask to install or update **Google Play Services for AR**.

## Competitor research

[Shootlocal competitor research page](https://cursor.com/canvas/store-da409d30-ab83-48e7-b175-2e0183165750/65ee7be5-f0df-44b1-93ff-f27a3643bda4) — the previously approved team research link, preserved alongside the native build handoff.
