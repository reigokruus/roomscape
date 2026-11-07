# Native planning integration — build and validation handoff

## Status: integrated source, NOT an iPhone-verified build

The team renamed `SetScan.swiftpm` to **`CineX.swiftpm`** on `main`. This continuation starts from the merged team code (`bf5cfb3`) and leaves the scan pipeline, gear inventory, text-based shoot suggestions and Android app intact. The app is still called CineX; Shootlocal remains the repository name. The original PR #1 was already merged before this work started; these are new commits on `frame-check`, not an update to that merged PR.

Portable planning logic is compiled and behavior-tested on Linux. The SwiftUI / SceneKit / RoomPlan integration has been syntax-parsed only: **no iOS SDK type-check, Xcode build, signing, simulator run or physical-device test was available here**. Do not call this production-ready, a shipped app or a phone-tested build.

## End-to-end route implemented

1. Home → New room scan → existing RoomPlan capture/save → saved-scan viewer.
2. Tap **Storyboard** in the scan viewer. `RoomPlanningAdapter` keeps actual wall endpoints, world-space rotation and detected object dimensions. It does not substitute the browser's sample room. Projects reopen by scan reference; their independent JSON does not change the original USDZ/RoomPlan JSON.
3. **Room**: searchable/category quick-add of dimensioned equipment, people and furniture planning envelopes; recent items; add existing My Gear; tap to select; drag on a 10 cm world grid; edit dimensions/rotation/height/rate/name; duplicate/remove. New items use a bounded search for a clear position inside the wall outline. Invalid moves keep the previous position. A room with >100 detected objects is rejected explicitly, not silently truncated.
4. **Frame**: tap the floor to place the camera; choose an item as target; set camera height/yaw, actual focal length and full-frame or nominal APS-C sensor. The SceneKit view uses the sensor aspect and a matching pinhole projection. The corner-based check uses a 10% margin and tests room containment, sampled wall obstruction and other-item envelope obstruction. An open, disconnected or self-crossing footprint cannot get a verified-fit verdict. A camera-kit envelope that contains the shot camera is its own rig, not a self-obstruction; other envelopes remain obstacles. Step-back distance is **optical only**, not a promise of space behind the operator.
5. **Budget**: edit each item's EUR/day cost and each view's days; view daily/subtotal and project allocation. Save equipment-only presets; apply them without replacing subjects/furniture/notes/camera; share presets as JSON. If a whole preset cannot fit, the operation is atomic and nothing changes.
6. **Views**: create a new view or duplicate an existing one, edit names and notes, switch and remove views. Each has its own camera/lens, items, target, rates and days. A duplicate remaps item/target IDs. A new view starts from the current scanned-furniture arrangement without copying planned equipment.
7. **Files**: export current view, entire project or preset as native JSON; import matching-room views into an open project without overwriting; open other-room exports through Home → **Planning projects**. Projects persist after each successful operation with atomic writes. Save failures remain visible; bad files are reported, not silently replaced. Undo/redo retain the last 30 operations during this editor session.

## What is deliberately NOT claimed

- No photorealistic assets, photo textures, AR item placement, realistic lighting or automatic illumination. Added objects are **explicit planning footprints / wireframe envelopes**, not guessed scanned objects. The existing real USDZ scan remains in the original viewer.
- No pitch/roll, distortion, stabilisation crop, video-mode sensor calibration or camera matching from a photograph. The camera is level; APS-C is nominal. Imported custom focal lengths remain selectable.
- Door/window holes are conservatively treated as solid wall segments. Floor/ceiling are estimated from wall extents. Tilted/irregular surfaces, scan inaccuracies and object-envelope collision approximations can cause false rejections. Collision is not a physical-safety guarantee.
- No crew accounts, cloud sync, hosted sharing, currency conversion or automatic supplier prices. JSON contains room layout and notes: export only to people you intend to share it with.
- Representative catalog dimensions/rates are estimates, not manufacturer specs/rental quotes. Owned gear defaults to a clearly labelled editable envelope at €0/day. **Project cost counts shared gear in each view again**; it is allocation, not a deduplicated rental bill, and excludes crew/tax/transport/deposits.
- Old browser-prototype JSON is a different schema and is rejected clearly; there is no silent migration of its sample room into a real scan.
- Undo is session-local, not a disk backup. Export JSON for backup. Files do not bundle original USDZ/photos; imported standalone plans still have geometry and a schematic preview, not the original scan mesh.
- No Android port or changes to Android release automation were made.

## Source boundaries and decision

Two whole-shape options were considered: embed the HTML in WKWebView with a scan-data bridge, or make a value-typed native document and edit it directly. The native document was chosen: it avoids two stores, sample-room assumptions and independent camera semantics.

```
CineX.swiftpm/
  HomeView.swift / ResultView.swift     # entry links only; existing team work preserved
  Planning/
    Core/PlanModels.swift              # Codable project, room, shot, equipment preset; validation
    Core/PlacementAndFrame.swift       # bounded quick placement and pinhole/room fit
    Core/PlanFiles.swift                # versioned transfer, atomic storage, import rules, history
    RoomPlanningAdapter.swift          # CapturedRoom -> real world-space document
    PlanningStore.swift                # main-actor transactional editing / native FileDocument
    PlanningLibraryView.swift          # existing scan launch + standalone import/reopen
    PlanningEditorView.swift           # room / frame / budget / views and files
    PlanningCanvas.swift               # measured floor diagram and drag interactions
    PlanningLensPreview.swift          # sensor-aspect SceneKit envelope projection
    PlanningInspectors.swift           # draft-before-save item and view forms
Package.swift                          # portable Swift test package (NOT the iOS app manifest)
Tests/PlanningCoreTests/                # tests compile the SAME Core files as the iOS app
Tests/Fixtures/synthetic-room.json      # explicitly synthetic simulator/Files smoke-test fixture
```

`FrameCheck/` is retained as the earlier standalone experiment. Its `simd` types are not duplicated into the app. The new editor calls the tested `ShotFraming` engine; the limitations above are intentionally narrower than that experiment. Existing `ShootPlannerView` remains available as **Plan a Shoot** and consumes the original My Gear store; adding owned gear in the editor creates a per-view snapshot, not a live link to the global inventory.

## Native JSON contract

UTF-8 JSON: `format: "shootlocal-native"`, `version: 1`, `content` is Swift Codable's tagged enum: exactly one of `project`, `shot` (named `room` and `shot` fields), or `preset`. See the synthetic fixture for a complete exported example. All coordinates/dimensions are metres, Y is up, item position is bottom-centre, yaw is radians in [-π, π], camera yaw zero points down -Z. Sensors/focal lengths use millimetres; costs use EUR/day.

Imports are bounded before decoding to **2,000,000 bytes**, then validate 1–50 views/project, 100 items/view, 50 presets, up to 200 walls, unique IDs, existing target IDs, finite/ranged numbers, positive dimensions, 1–365 days, 120-character names and 10,000-character notes. Library imports get a fresh project ID and drop external scan references. View imports remap item and target IDs. Cross-room view appends fail explicitly; different rooms can be opened as new library projects. All filenames come from UUIDs, never from imported names or scan references. Raw RoomPlan JSON is not a native planning export.

## Repeatable verification

From the **repo root** with Swift 5.9+ (tested with official Swift 6.0.3 on Linux):

```sh
swift test
swift test -c release
# Syntax ONLY; this does not type-check Apple's frameworks:
swiftc -frontend -parse $(rg --files CineX.swiftpm -g '*.swift' -g '!Package.swift')
git diff --check
```

The root package has no third-party dependencies. It is intentionally separate from the AppleProductTypes iOS app package; do not try `swift build` in `CineX.swiftpm` on Linux. Local toolchain location, dependencies and test logs are recorded in `native-planning-verification.md`.

## Mandatory Xcode / device gate before merging or showing as working native app

### Compile gate

1. Check out `frame-check` and open **`CineX.swiftpm`**, not the root `Package.swift` or old SetScan path. Use Xcode 15+ / iOS 17+. Select an Apple signing team locally; do not commit a personal team ID.
2. Build for an iOS simulator first to type-check **all** SwiftUI/SceneKit/RoomPlan code. In Xcode select the app scheme and a simulator → Product → Build. If using CLI, use `xcodebuild -list` in the Xcode-supported app project/workspace to discover its actual scheme, then `xcodebuild -scheme <actual-scheme> -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO`. Do not assume the Playgrounds package is a normal root Swift package; if the local Xcode requires an app project, create a standard iOS 17 SwiftUI app and add all Swift files under `CineX.swiftpm` except its manifest, with the Camera usage-description key. No third-party packages are needed.
3. Resolve SDK/compiler errors before claiming a build. The team's merged scanner/mesh code is preserved; errors in those files may predate this integration.
4. For simulator UI testing, import `Tests/Fixtures/synthetic-room.json` through Files → Planning projects. It is visibly named **Synthetic QA room — NOT a scan**. The wide view should pass; the 85 mm view should not. A simulator cannot validate LiDAR capture.

### Real LiDAR iPhone gate

5. On a supported Pro iPhone (iOS 17+), enable Developer Mode, trust signing and grant camera permission. Also check unsupported-device messaging without LiDAR.
6. Scan a small room with an obvious rotated wall/furniture. Finish processing, open the real USDZ and confirm the existing 3D/Top controls and gear/suggestions still work.
7. Tap **Storyboard**. Compare the native plan's actual wall orientation and furniture footprint with the scan, not the synthetic fixture. Confirm metre scale and inferred floor/ceiling disclaimer. Missing walls must not show a fit pass.
8. Add a person and two different lights; search/category/recent buttons and My Gear must add real document items. Verify dimensions/rates in the inspector. Test an oversized item: clear error, no partial add.
9. Drag, rotate, duplicate and remove a planned item. Try dragging through a wall/another envelope: it must retain the old position and explain why. Update a rate on overlapping original scan envelopes without moving them; rate-only editing should still succeed.
10. Set the person as target, place camera from a floor tap, aim at target, switch 24/35/50/85 mm and sensors. Compare expected fit/rejection to the schematic preview; turn camera away and move it outside a room to force rejection. Put an item between camera and person to test obstruction. Check that preview is letterboxed to sensor aspect, not stretched across the portrait phone.
11. Duplicate a view; change its lens, notes, an item and rental days. Switch back: original must be unchanged. Create a fresh view; only scanned-furniture plan should be retained. Undo/redo these actions, then make a new edit and confirm redo is cleared.
12. Set rates €35 and €20 and 2 days: this view should allocate €110. Duplicate it: project allocation should be €220 (intentionally no shared-kit deduplication). Verify rates/days survive view switches.
13. Save a preset with named lights. Change equipment, apply the preset, and confirm camera, person, furniture, view name and notes are unchanged. Confirm preset export/import; reject a kit that cannot fit without partially replacing current equipment.
14. Export current view and full project to Files and import them. Same-room view import must **append**, not overwrite. Import a different room inside editor: explicit error; import through library: opens a separate project with no fake scan-mesh link. Share an export manually only after checking room-layout privacy.
15. Import malformed JSON, a browser-prototype JSON, a file over 2 MB, unsupported version, missing target, duplicate IDs and negative rate: explicit errors, original project unchanged. Use the automated suite for precise boundary cases.
16. Force-quit and reopen; project/view names, equipment, rate/day values, lens and notes must persist from Home → Planning projects and from the associated saved scan. Undo history should reset. Delete the original scan only if intentionally testing: standalone plan geometry should remain, but no original mesh is promised.
17. Test failed disk save/provider cancellation, empty project library and corrupt planning files. The app must never silently reset a broken project. JSON should be exported before attempting manual repair.
18. Test a small phone screen, Dynamic Type, VoiceOver item labels, keyboard dismissal and portrait navigation. Check dragging against the outer ScrollView; adjust gesture priority if a device reveals scrolling conflicts. Memory/performance with 50 views × 100 items needs device profiling; it has not been measured here.

Record Xcode version, phone model/iOS version, build result and each result above in the next PR. No PR was merged or app deployed by this work.
