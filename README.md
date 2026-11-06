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

The clickable web mockup (`ui_prototype.html` in the hackathon pitch folder) is labeled **prototype / next** for placement, illustrative lens crops and encoded plan links only.

---

## Roadmap

| Stage | Status | Scope |
| --- | --- | --- |
| SetScan room capture + 3D review | **Works today** | RoomPlan + LiDAR, USDZ/JSON, SceneKit, object summary |
| Planning layer (place kit, lens preview, share) | Prototype / next | Web concept; not wired to live SetScan data |
| Sensor-aware framing in the scanned room | Planned | Match virtual frame to real photograph |
| Permissioned crew shot-plan link | Planned | Hosted share with notes and kit layout |
| Freemium product packaging | Hypothesis | Free one project / Pro ~€19 team/month — unvalidated |

---

## Requirements

- **iPhone with LiDAR** — iPhone 12 Pro or newer Pro / Pro Max
- **iOS 17+**
- **Mac with Xcode 15 or newer** (or Swift Playgrounds on a Mac) to build and install

---

## Build and install on an iPhone

These steps are preserved from the SetScan project README:

1. Download this repo (green **Code** button › **Download ZIP**) and unzip it.
2. Double-click **SetScan.swiftpm**. It opens in Xcode.
3. Connect the iPhone by cable and tap **Trust**. On the iPhone, turn on Settings › Privacy & Security › **Developer Mode** (the phone restarts).
4. In Xcode, pick the iPhone as the run destination at the top. In the project's **Signing & Capabilities**, choose your Apple ID as the Team (add it under Xcode › Settings › Accounts first; a free Apple ID works).
5. Press ▶︎ Run. The first time, approve the developer account on the iPhone under Settings › General › VPN & Device Management, then run again.

Apps signed with a free Apple ID stop working after 7 days. Just press Run again to reinstall.

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

Local pitch pack (not necessarily in this git repo):

- `pitch_deck.html` — 8-slide deck (← → / F fullscreen)
- `pitch_script.md` — ~3-minute spoken script with SetScan live-demo cues
- `ui_prototype.html` — placement / lens / share **prototype**
- `README_shootlocal.md` — this file

Do not push pitch-only HTML into GitHub unless the team decides to; this README is written for the product repo narrative.

---

## Honesty checklist for demos

- **Claim:** SetScan scans a room and shows a colored 3D model with windows, doors and furniture — **yes, if the device has LiDAR**.
- **Claim:** Live placement of lights/cameras with accurate 3D lens simulation — **no; web mockup only, illustrative crops**.
- **Claim:** Shared hosted shot plan for remote crew — **no; prototype encodes a sample plan in a link**.
- **Claim:** Photorealistic or automatic lighting — **no**.
