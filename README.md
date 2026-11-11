# RoomScape

**Scan a real room with a phone and get its measurements, a 3D view you can walk through, and a file you can share.**
Built for a pilot with a film production: scanning rooms and locations so the crew knows their size, height, doors and
surfaces before the shoot day, and can plan cameras, lights and set-ups around them.

Two native apps share one file format:

- **Android** (`android/`): ARCore phones, tested on a Galaxy S24. The main app for the pilot.
- **iPhone** (`CineX.swiftpm/`, the app is called RoomScape): LiDAR iPhones (12 Pro or newer Pro) on iOS 17+.

A scan from either phone can be shared as a **.rmsc** file and opened on the other ([format](docs/rmsc-format.md)).

## What you get from a scan

| | Android | iPhone |
|---|---|---|
| Live scan view | coloured points (2 cm), flat camera-picture tiles on walls and floor | RoomPlan with LiDAR; points-only and flat tiles as test switches |
| Losing tracking | an amber "Lost track" notice with the direction back, a vibration, then it lines up with the scan again | RoomPlan's own handling |
| Room size and height | width × length along the walls, height from the ceiling | RoomPlan's walls, doors and windows |
| Floor area | per room, split at doorways; tap a room for its own m² | – |
| Correcting walls | drag a wall line in the result (for curtains, furniture against a wall); Reset walls | – |
| Measure | tap two points: distance, height of a point | the same |
| Doorways | width and height of openings | RoomPlan's doors |
| Views | Photo, Points, Mesh; walk through the room | Photo, Points, Mesh, RoomPlan model; walk through |
| Larger spaces | one scan for several rooms; rooms are split at doorways | carries on past RoomPlan's ~9 × 9 m limit with points and tiles (new, not tried on a big space yet) |
| Continue scan | add to a saved scan later | the same |
| Power sockets | found in the scan's photos by Claude and marked in the scan, so a crew knows where to plug in | the same |
| Sharing | .rmsc (Share, Save to device, send to a nearby phone) | the same |

On both phones there are also **objects** (furniture boxes, and lamps found by Claude), a **lighting preview** with
cable runs to the nearest socket, and planning tools (**Storyboard**, **Plan a Shoot**). Claude features (sockets,
lamps, "Set it up with Claude") need your own Claude API key on the phone. Everything else works offline.

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
1. Open **CineX.swiftpm** in Xcode (15 or newer; tested with 27).
2. Connect the iPhone by cable, tap **Trust**, and turn on Settings › Privacy & Security › **Developer Mode**.
3. In **Signing & Capabilities**, choose your Apple ID as the Team (a free Apple ID works).
4. Press ▶︎ Run. The first time, approve the developer account on the iPhone under Settings › General › VPN & Device
   Management.

Apps signed with a free Apple ID stop working after 7 days; run again to reinstall.

## For developers

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
