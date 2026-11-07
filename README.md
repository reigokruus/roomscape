# CineX

**A set-up assistant for real rooms.** Scan your room with an iPhone, tell the app what gear you own, pick the kind of scene you want, and it tells you in plain language where to put the camera and lights. It's built for everyday creators, not film crews.

## Status
**v0.1 (hackathon prototype):** the scan works. Apple RoomPlan captures the room, and it comes back as a 3D model with windows (natural light), doors and furniture picked out, plus a room summary and a top-down view.

**Next:** a gear inventory, scene presets, and suggestions for camera and light placement on the scanned room.

## Why not Previs Pro (or similar apps)?
Previs Pro is a **pro storyboarding tool**. You build virtual sets from a library and plan shots for a script. It doesn't scan your room, know your gear or suggest where to put things. CineX starts from the **real room and the gear you actually own**. See [docs/positioning.md](docs/positioning.md) for the full comparison, including WATT-IF, Polycam, Scaniverse, Photo Studio AR and Shot Designer. Polycam and Scaniverse capture rooms very well, but they stop at the scan. CineX uses the scan to plan the shoot.

## iPhone version (main)
Porting the Android features to iPhone? Start with [docs/android-to-ios-handoff.md](docs/android-to-ios-handoff.md).

This is the `CineX.swiftpm` folder. It requires an **iPhone with LiDAR** (iPhone 12 Pro or newer Pro / Pro Max) on iOS 17+, and a Mac with **Xcode 15 or newer**.

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
