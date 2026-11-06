# Shootlocal

**Shootlocal** turns a real room scan into a shared set plan for small production crews — indie filmmakers, photographers and video creators who need to place camera, lights and kit before they arrive on location.

This repository’s working code today is **SetScan**: Shootlocal’s native iPhone room-scanning module. A web mockup explores the next layer (item placement, lens preview, shared shot plan); that planning UI is a prototype, not shipping product yet.

> Hackathon context: IDA Hub Jõhvi, 6–8 November 2026. Final pitch Sunday 8 Nov 14:00 (3 minutes). Product name: **Shootlocal**. Room scan demo: **SetScan** on a LiDAR iPhone.

---

## The problem

Location photos show a space, but not a measurable room. Walls, windows (natural light), doors and furniture are discovered late. Different people arrive with different mental pictures of the same shot. Shootlocal’s hypothesis: resolve setup decisions earlier from a shared scan to cut crew time and rework. That hypothesis still needs validation with real shoot teams.

---

## What works now — SetScan

SetScan is a Swift / SwiftUI iPhone app built on **Apple RoomPlan** and **LiDAR**. From the code in `SetScan.swiftpm`, it currently:

1. Starts a live **RoomCaptureView** scan (Cancel / Done).
2. Processes the capture into a **CapturedRoom** and saves:
   - a **`.usdz`** 3D model
   - a **`.json`** RoomPlan data file (walls, windows, doors, openings, objects)
3. Lists saved scans; opens a **SceneKit** 3D viewer with orbit / pinch / pan and a **3D / Top** camera switch.
4. **Colorizes** the model: windows (natural light), doors, openings, walls, floor.
5. Shows a **summary**: approximate size (m), wall / window / door / opening counts, and furniture & objects by RoomPlan category.
6. Shares the USDZ via the system **ShareLink**.
7. Deletes scans from the document directory.
8. Guards unsupported devices: needs a LiDAR iPhone (`RoomCaptureSession.isSupported`).

**Not in SetScan yet:** placing lights/cameras/props, lens FOV simulation (24/35/50 mm), shared crew shot plans, automatic lighting suggestions, photorealistic lighting, or importing the scan into the web prototype.

The single-file web planner ([`hackathon/ui_prototype.html`](hackathon/ui_prototype.html)) runs without external dependencies. It is a **browser prototype** using a sample room, not live SetScan data or on-device AR.

---

## Features — browser planning prototype

Open [`hackathon/ui_prototype.html`](hackathon/ui_prototype.html) in a modern browser; no installation or external dependencies. [Desktop screenshot](hackathon/ui_prototype_v3.png).

1. **Storyboard:** independent editable views retain camera position, 24/35/50 mm lens, placed items and notes. Save, duplicate or switch views; export the current frame as JSON or PNG, import frame/project JSON, or export/share the whole storyboard. Edits autosave in this browser when local storage is available. Legacy v1 shot-plan links still open.
2. **Equipment & budget:** each kit/prop instance has an editable illustrative EUR rental/day rate. Each view lists its items and daily total; allocated days give a view subtotal. Project allocation sums view subtotals, **without deduplicating shared kit**; excludes crew, transport and tax.
3. **Presets:** interview three-point lighting, product-tabletop lighting/camera kit (add a desk separately), and vlog starters. Save named equipment-only setups locally and apply them to any view in one click; furniture stays while equipment is replaced. Presets export/import as JSON too.
4. **Detailed, scale-aware items:** 12 shaded top-down drawings, including Aputure 300d + stand, 90 cm softbox, C-stand, FX3 + tripod, boom, sofa, chair, desk, plant and rug. Uniform centimetre-based footprints; dimensions appear on hover/in the inspector. Dimensions, heights and rates are **representative planning estimates**, not verified manufacturer specifications or rental quotes.
5. **Quick add:** searchable/category palette, recent items, one-tap placement at a low-overlap spot, drag/drop, floor-plane bounds, 10 cm grid and wall snapping. **D** duplicates, **R / Shift-R** rotates ±15°, **Delete/Backspace** removes, arrows move 10 cm (Shift: 1 cm), **Esc** deselects, **/** searches. Shortcuts do not fire while typing.

**Limits:** detailed 2D illustrations, not photorealistic 3D assets; camera sketch is an illustrative lens crop and does not simulate camera pose, depth, collision or lighting. All items sit on the floor plane. JSON import is bounded (2 MB/file, 50 views/project, 100 items/view); importing adds views without overwriting existing ones. Browser-local saves are not cloud backup: export JSON before changing browser/device. Encoded links need a hosted copy and may exceed URL length limits; JSON is the portable fallback.

### Prototype verification

Run `node hackathon/verify_v3.mjs` with Google Chrome installed at `/usr/bin/google-chrome` (Node.js 22+; no npm dependencies). It checks real headless Chromium interactions, frame/project/preset JSON download/upload round-trips, PNG export, reload persistence, legacy links, invalid imports, responsive overflow and browser errors. The test writes its log/download artifacts beside the HTML; only its verification report and screenshots are checked in.

---

## Roadmap

| Stage | Status | Scope |
| --- | --- | --- |
| SetScan room capture + 3D review | **Works today** | RoomPlan + LiDAR, USDZ/JSON, SceneKit, object summary |
| Planning layer (storyboard, kit budgets, presets, placement, share) | Interactive browser prototype | Sample room only; not wired to live SetScan data |
| Sensor-aware framing in the scanned room | Planned | Match virtual frame to real photograph |
| Permissioned crew shot-plan link | Planned | Hosted share with notes and kit layout |
| Freemium product packaging | Hypothesis | Free one project / Pro ~€19 team/month — unvalidated |

---

## iPhone version (main)
This is the `SetScan.swiftpm` folder. It requires an **iPhone with LiDAR** (iPhone 12 Pro or newer Pro / Pro Max) on iOS 17+, and a Mac with **Xcode 15 or newer**.

### Build and install on an iPhone
1. Download this repo (green **Code** button › **Download ZIP**) and unzip it.
2. Double-click **SetScan.swiftpm**. It opens in Xcode.
3. Connect the iPhone by cable and tap **Trust**. On the iPhone, turn on Settings › Privacy & Security › **Developer Mode** (the phone restarts).
4. In Xcode, pick the iPhone as the run destination at the top. In the project's **Signing & Capabilities**, choose your Apple ID as the Team (add it under Xcode › Settings › Accounts first; a free Apple ID works).
5. Press ▶︎ Run. The first time, approve the developer account on the iPhone under Settings › General › VPN & Device Management, then run again.

Apps signed with a free Apple ID stop working after 7 days. Just press Run again to reinstall.

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
Every push that changes `android/` is built by GitHub and published as a release, so the phone can always download the newest build from **https://github.com/reigokruus/shootlocal/releases/latest/download/SetScan-android.apk**.

For hands-free updates, install [Obtainium](https://github.com/ImranR98/Obtainium) on the phone and add `https://github.com/reigokruus/shootlocal`. It notifies you of each new build and installs it with one tap.

All builds, whether from GitHub or from Android Studio, use the same signing key (`android/app/debug.keystore`, a debug key committed on purpose), so they install over each other. If Android refuses to install, uninstall the old SetScan once and try again.

On first launch, the phone may ask to install or update **Google Play Services for AR**.

---

## Project layout

```
SetScan.swiftpm/
  Package.swift          # iOS 17 app package, camera capability
  SetScanApp.swift       # App entry
  HomeView.swift         # New scan + saved list
  ScannerScreen.swift    # RoomCaptureView + save pipeline
  ScanStore.swift        # USDZ + JSON persistence
  ResultView.swift       # SceneKit 3D / Top + RoomSummary
android/                 # Separate Android Studio project (ARCore depth scanner)
```

Source of truth for the app: https://github.com/reigokruus/shootlocal

---

## Team

| Name | Role |
| --- | --- |
| Marje Kikas | Indie PWA / product; film & design background |
| _[Teammate]_ | _[Contribution — fill before pitch]_ |
| _[Teammate]_ | _[Contribution — fill before pitch]_ |

---

## Pitch materials (hackathon weekend)

Pitch pack in [`hackathon/`](hackathon/):

- `pitch_deck.html` — 9-slide deck (← → / F fullscreen)
- `pitch_script.md` — ~3-minute spoken script with SetScan live-demo cues
- `ui_prototype.html` — storyboard / equipment / presets / placement / share **prototype**
- `ui_prototype_v3.png` — desktop planning screenshot

Pitch materials are included with Marje’s approval. They do not change the native iOS feature status above.

---

## Honesty checklist for demos

- **Claim:** SetScan scans a room and shows a colored 3D model with windows, doors and furniture — **yes, if the device has LiDAR**.
- **Claim:** Live placement of lights/cameras with accurate 3D lens simulation — **no; web mockup only, illustrative crops**.
- **Claim:** Shared hosted shot plan for remote crew — **no; prototype encodes a sample plan in a link**.
- **Claim:** Photorealistic or automatic lighting — **no**.

## Competitor research

See the [Shootlocal competitor research page](https://cursor.com/canvas/store-da409d30-ab83-48e7-b175-2e0183165750/65ee7be5-f0df-44b1-93ff-f27a3643bda4).
