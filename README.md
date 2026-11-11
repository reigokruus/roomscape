# RoomScape

**Scan a real room with a phone and get its measurements, a 3D view you can walk through, and a file you can share.**
Built for a pilot with a film production: scanning rooms and locations so the crew knows their size, height, doors and
surfaces before the shoot day, and can plan cameras, lights and set-ups around them.

Two native apps share one file format:

- **Android** (`android/`): ARCore phones, tested on a Galaxy S24. The main app for the pilot.
- **iPhone** (`CineX.swiftpm/`, the app is called RoomScape): LiDAR iPhones (12 Pro or newer Pro) on iOS 17 or newer.

A scan from either phone can be shared as a **.rmsc** file and opened on the other ([format](docs/rmsc-format.md)).

## Android vs iPhone: what to expect

The two apps scan in different ways, so they don't have the same features.

- **iPhone = Apple's RoomPlan + LiDAR.** RoomPlan recognises walls, doors, windows, openings and furniture boxes and
  builds a clean room model; it guides the scan with its own prompts and stops at about 9 × 9 m. LiDAR depth is clean
  and reaches 5 m. **None of the RoomPlan features exist on Android: don't look for them there.**
- **Android = ARCore depth, no LiDAR.** The room is coloured points, plus flat photo tiles on flat surfaces. Walls,
  rooms, floor area and doorways are worked out by our own code from the points: rougher than RoomPlan, and depth only
  reaches ~3.5 m. In return Android has floor area per room, wall dragging and the "Lost track" notice, which the
  iPhone doesn't have yet.

Maturity: **solid** = used a lot, **works** = used and checked, **rough** = works but expect errors, **test switch** =
off unless turned on, **untested** = built but not tried on a phone yet.

| Feature | Android | iPhone | What it looks like | TODO |
|---|---|---|---|---|
| Live scan view | **works**: coloured 2 cm dots | **works**: RoomPlan's view; dots-only as a test switch | coloured dots on a grey striped background; the stripes show where nothing is scanned yet; hint card with "This spot" / "Room" bars at the top | |
| Flat tiles | **works**, on by default | **test switch** ("Flat tiles (test)") | 30 cm photo squares that flash white and develop like a Polaroid on flat walls and floor; dots under them disappear | Android: only within ~3.5 m of the phone |
| Losing tracking | **works**: notice, buzz, lines up again by itself | RoomPlan's own prompts | amber line "Lost track. Stop and point back at where you were", with the direction, and one buzz; a tick when it carries on | Android: tracking still gets lost on plain walls; the tile check that would refuse a wrong line-up only logs for now; lining up from the camera picture isn't built |
| RoomPlan model (walls, doors, windows, furniture boxes) | – | **solid** | "Model" tab: a clean grey 3D model of the room | |
| Room size and height | **works** (≈, a few cm on width, ~10 cm on height) | **solid** (from RoomPlan) | the line under the scan name, e.g. "≈3.80 × 2.86 m · ≈2.82 m high", also in the ROOM card | walls hidden by curtains or furniture are measured at the front of them: drag the wall by hand |
| Floor area | **rough**: right for rectangular rooms | – | "floor area ≈9.9 m²" in that line; with several rooms "2 rooms, floor ≈35 m² in all (tap a room)" | see "Floor area" below; iPhone: not built |
| Tap a room | **works** | – | tapping a room's floor tints it amber and shows "Room 2: 6.4 m², 3.3 × 2.9 m" | iPhone: not built |
| Correcting walls | **works** (new) | – | amber wall lines on the floor of the result; drag one, the size and m² update; "Reset walls" appears next to the size line | iPhone: not built |
| Measure | **works** | **works** | in Enter the room: tap two spots, a yellow line with the distance; one spot gives its height | |
| Doorways | **rough** (approximate sizes) | **solid** (RoomPlan's doors) | Android: openings outlined in the result, dashed until the frame is scanned | |
| Views | **solid**: Photo, Points, Mesh | **solid**: Photo, Points, Mesh, Model | tabs at the top of the result; "Enter the room" to walk through | |
| Larger spaces | **works**: one scan, split into rooms at doorways | **untested**: carries on past RoomPlan's ~9 × 9 m limit with dots and tiles | iPhone: a line "Large space: points and tiles keep going…" | iPhone: not yet tried on a real large space |
| Continue scan | **works** | **works** | "Continue scan" on a saved scan; the saved part shows in blue | |
| Power sockets | **works** (needs a Claude key) | **works** (needs a Claude key) | sockets marked in the scan, found by Claude in the scan's photos | |
| Objects | **works** | **works** | "Objects" button: furniture boxes you can hide, move and turn | |
| Lighting preview | **rough** | **works**, with cable runs to the nearest socket | "Lighting" button: place a person and your lights, see the light on their face | |
| Storyboard, Plan a Shoot, "Set it up with Claude" | – | **works** | buttons in the result's title bar and in Lighting | Android: not built |
| Catalogue and scanned-item planning | **rough** | – | "Catalog" / "Scanned items": real gear sizes, hide detected furniture in a planning view | |
| Sharing | **works**: Share (.rmsc), Save to device, Send to nearby phone | **works**: the same | Share button in the result | no cloud sync; phone to phone on the same Wi-Fi only |
| Recording for fixing problems | **works**: camera recording, screen video, logs | **test switch** ("Screen video (test)"); camera recording on | kept in the scan folder on the phone, never in a .rmsc | |
| Test switches | debug tools: long-press the main button | home screen, with the build time ("Build 10 Nov 19:55") | | |
| Phones | Galaxy S24 | iPhone 15 Pro | | Galaxy S23+ and other phones not tried yet |

### Floor area: what works and what's still to do

**Works:** simple rectangular rooms. The walls found by the scan bound the room; on a tape-measured 3.95 × 2.70 m
room the width came out exact and the area about 7 % low (a thick curtain read as the end wall), which a wall drag
fixes.

**Not yet (suggested owner: Daniel):** other shapes. L-shaped rooms, and open-plan spaces like a living room +
kitchen + hall (tested on 10 Nov), don't get a correct outline or a split into rooms yet. When the scan finds few
walls, the outline falls back to a rectangle around the floor it saw (marked "about"), and an open space with no
narrow doorway stays one room.

Starting points:
- The code: `android/app/src/main/java/com/hackathon/setscan/FloorArea.java` (rooms, walls, outline; it also runs on
  the Mac: `java FloorArea.java <scan folder>` prints the rooms and draws a top-down picture). Its unit tests:
  `android/app/src/test/java/com/hackathon/setscan/FloorAreaEditTest.java`. The result view's wall dragging is in
  `ScanRenderer.java` (search `wallEdits`).
- The saved numbers: `floor-area.json` in each scan folder, and `floorArea` in a .rmsc's manifest
  ([format](docs/rmsc-format.md)).
- An idea: let the user split a room by drawing a line across the floor, the same way walls are dragged now.
- Test scans (Android scan folders) are kept locally on the developer's Mac, not in this repo: they show a private
  home. Ask for a few to work with.

Claude features (sockets, lamps, "Set it up with Claude") need your own Claude API key on the phone. Everything else
works offline.

**Scans are private.** A scan holds photos and a 3D copy of the room, and the phones can keep a camera recording and a
screen video with it. Share .rmsc files only with the people who need them.

## How to scan a room well

- Move slowly, and keep within about **1–3 m of the walls**: the S24's depth doesn't reach further than ~3.5 m.
- Sweep every wall **from the floor to above head height**, and include the corners. A side counts as a wall only when
  the scan sees it reach above ~1.6 m.
- Curtains and furniture against a wall are measured as the wall. Pull curtains aside, or drag that wall line in the
  result.
- If the amber "Lost track" notice appears, stop and point back where it says. Scanning carries on by itself.
- Tap **Done**. The size, height and floor area appear at the top and in the room card.

How accurate it is so far (Galaxy S24, tape-measured rooms): the width is within a few cm, room heights within
about 10 cm, and floor areas are typically 5–10 % low where a wall or the floor along it wasn't seen. Dragging the walls
to the right place fixes that.

## Install

### Android
- **Test build for the pilot:** [releases](https://github.com/reigokruus/roomscape/releases). Open the link on the
  phone, download the APK, open it and allow installing from that source once. On Samsung phones with **Auto
  Blocker** on (Settings › Security and privacy › Auto Blocker), turn it off for the install. Play Store installs
  Google Play Services for AR (ARCore) if the phone doesn't have it.
- **Builds from `main`:** every push that changes `android/` is built by GitHub and published as a release
  (`releases/latest/download/CineX-android.apk`). Work that isn't pushed yet isn't in it.
- All builds use the same signing key (`android/app/debug.keystore`, committed on purpose) and package ID
  (`com.hackathon.setscan`), so they install over each other and scans are kept.

Develop: open the `android` folder in [Android Studio](https://developer.android.com/studio), or
`cd android && ./gradlew assembleDebug`. The APK is in `android/app/build/outputs/apk/debug/`.

### iPhone
1. Open **CineX.swiftpm** in **Xcode 26 or newer** (it uses iOS 26 APIs when the phone has them; the app itself runs on
   iOS 17 or newer).
2. Connect the iPhone by cable, tap **Trust**, and turn on Settings › Privacy & Security › **Developer Mode**.
3. In **Signing & Capabilities**, choose your Apple ID as the Team (a free Apple ID works).
4. Press ▶︎ Run. The first time, approve the developer account on the iPhone under Settings › General › VPN & Device
   Management.

Apps signed with a free Apple ID stop working after 7 days; run again to reinstall.

## For developers

- [CLAUDE.md](CLAUDE.md): how to work in this code (layout, builds, tests, the rules that matter, open work). Claude Code,
  including the VS Code extension, reads it automatically.
- [.rmsc format](docs/rmsc-format.md): points, photos, depth, mesh, flat tiles, floor area. Version 1, optional
  fields only.
- [Test harness](docs/test-harness.md): the apps' own code run on the Mac on synthetic rooms and on real recordings
  (`tools/harness`).
- [Recordings](docs/recordings.md): what each phone keeps with a scan for replaying and fixing problems (camera
  recording, screen video, logs). These stay on the phone and the Mac; they are never in a .rmsc.
- [Android → iPhone handoff](docs/android-to-ios-handoff.md), [native planning](docs/native-planning.md),
  [catalogue and scanned items](docs/catalog-and-scanned-items.md), [positioning](docs/positioning.md).

## Status

**v0.1, a working prototype for the pilot.** The Android app is used daily on a Galaxy S24; other ARCore phones (such
as the Galaxy S23+) haven't been tried yet. Measurements are estimates until checked against a tape. The planning tools'
items are measured envelopes, not photorealistic assets.

Other work in this repo: [`FrameCheck/`](FrameCheck/) (shot fit), [`hackathon/`](hackathon/) (browser planning
prototype and pitch pack).
