# iPhone app (CineX.swiftpm): state and handoff

State as of **Build 13 Nov 2026 13:22:13** (tested on an iPhone 15 Pro). Older background: `docs/android-to-ios-handoff.md`.

## Rules
- Scans, recordings, screen videos, photos and renders show a real home. They never leave the owner's machines and
  are never committed (`recordings/` and `harness-out/` are git-ignored). Saved data is never deleted.
- No bundle ID, team ID, signing settings, API keys or tokens in commits. The Claude API key and the share-link
  upload key are typed into the app by the user and kept in the Keychain.
- Say what was and wasn't tested on a phone.

## Build and install
1. Brush (the on-phone photo-real trainer): follow `tools/brush-ios/README.md` (clone Brush 1388f74, apply the patch,
   pinned Cargo.lock, cargo for `aarch64-apple-ios`, `make-xcframework.sh`). Without `Vendor/BrushC.xcframework`
   the app still builds, with a stand-in trainer.
2. In `CineX.swiftpm/Package.swift`, set your own `bundleIdentifier` and pick your team in Xcode (Signing &
   Capabilities). Keep these local: don't commit them.
3. `cd CineX.swiftpm && xcodebuild -scheme RoomScape -destination 'generic/platform=iOS' -configuration Release
   -allowProvisioningUpdates -derivedDataPath <build folder> build`, then
   `xcrun devicectl device install app --device <your device id> <build folder>/Build/Products/Release-iphoneos/RoomScape.app`.
4. Metal shaders are compiled at runtime from strings: check a changed shader on the Mac (CLAUDE.md).

## What works (phone-tested)
- Live scan (`LiveCloud`, `LiveDiscs`, `DotCleanup`):
  - only a centred capture zone, 0.72 × 0.7 of the screen, makes dots; it is drawn as a thin outline;
  - a clearly closer, sure look replaces a dot;
  - a dot that sure depth later sees clearly through is erased (`harness strays` replays a recording);
  - dots are sized by their cell, so scanned surfaces read filled;
  - not-yet-scanned gaps show amber stripes;
  - one rotating hint (Move slowly / Go closer / Look around);
  - the dot cores are drawn in a single pass (two passes caused the black flicker).
- After Done, the scan is rebuilt from the recording, and the open room screen reloads in place.
- Make photo-real (manual): on `PhotoRealScreen`, Brush trains on the phone in about 5 minutes. Memory safeguards:
  - a refine needs 1700 MB available until two refines have run, then 800 MB;
  - memory marks are kept in `<room>/splat/memory-marks/`;
  - the "Photo-real memory test" switch on the home screen runs it without a new scan.

- Photo-real tab in the room view (13 Nov), in `ResultView.swift`:
  - an icon segment (`cube.transparent`) next to Photo / Points / Mesh / Model (`Style.photoReal`), shown only when
    the scan has `splat/preview.ply`;
  - it lays `SplatViewerView` over the SceneKit view, starting from the room view's orbit camera
    (`RoomCameraLink` hands over cam, look and fov);
  - leaving the tab hands the camera back through `SplatViewLink` and `SplatViewer/index.html`'s `window.roomCam()`;
    the page also takes `?fov=`, the same as Android's Photo-real tab.

## Open
- Brush's first-run GPU bursts (about +2 GB at training steps 0 and 1 and at the first refine, under a second to
  4 s): not autotune (logged); likely first-time kernel or pipeline compiles, unproven.
- Rebuild see-through cleanup (`RebuildCleanup`, `harness rebuildstrays`) is written but not wired in. Inspect the
  points it drops near real surfaces first.
- Point colour from a photo where the point is actually visible (an occlusion check) is not done.
- The pose guard near RoomPlan's size limit is untested in a big space.
- The room screen doesn't keep the camera angle when it reloads.
- Android to-do: capture zone, gap stripes, rotating hints, Brush photo-real. The "This spot / Room" bars are
  hidden in code, but no APK has been built with that yet.
- Photo-real tab: the small-screen width was checked only on rk's phone (no iPhone SE / 375-pt check yet), and
  standing and top view hand over a look point 2 m ahead.
