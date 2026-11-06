# Shootlocal — actual frame check

A working, offline shot-framing calculator, plus a standalone Swift core and a SwiftUI integration example. Nothing in the cloned SetScan repository was changed, committed or pushed.

## Open it

Open **`framecheck.html`** directly in a browser. It has no dependencies, network requests, uploads or build step.

- Drag the camera and subject, or focus either marker and use arrow keys (Shift = larger step).
- Set room width/depth/ceiling, actual camera height, heading, tilt, sensor and lens.
- Choose a subject preset or edit its bounding-box dimensions and centre height.
- **Aim at subject** changes the camera's real direction; dragging does not silently re-aim it.
- **Step back to fit** moves the actual camera only when sufficient backward travel is available.
- Green means the whole subject box is framed, placed inside the room, with no sampled wall blockage. Amber room-surface warnings are separate composition advice.

The HTML uses a synthetic rectangular room, **not an uploaded RoomPlan scan**. The Swift module uses actual scanned wall rectangles, including rotated walls and concave footprints under the assumptions below.

## Files

| File | Purpose |
|---|---|
| `FrameCheck.swift` | Foundation + simd only. `Lens`, `Sensor`, `Subject`, `Camera`, `Wall`, `RoomGeometry`, `FrameCheckResult`, `FrameChecker.check(...)`. No UI or RoomPlan dependency. |
| `RoomPlanAdapter.swift` | Maps `CapturedRoom.walls` transforms and dimensions into core geometry. |
| `FrameCheckView.swift` | SwiftUI numerical planner: 24/35/50/85 lens picker, sensors, three subject presets, world-position / heading / tilt sliders, verdict and alternatives. |
| `framecheck.html` | Single-file browser demo, containing the same algorithm in JavaScript. |
| `verify_math.cjs` | Runs the actual HTML math, with independent analytic expectations and deterministic randomized checks. |
| `verify_browser.mjs` | Headless Chrome interactions, errors, responsive layout and screenshots. Uses Node's built-in WebSocket / Chrome DevTools, no npm packages. |
| `verification-math.log`, `verification-browser.log`, `verification-browser.json` | Re-runnable verification evidence. |
| `framecheck_desktop.png`, `framecheck_fits.png`, `framecheck_mobile.png` | Real screenshots from headless verification. |

## What is calculated

### 1. Optics

Use actual active sensor dimensions and **physical** lens focal length in millimetres:

```text
horizontal FOV = 2 × atan(sensorWidth / (2 × focalLength))
vertical FOV   = 2 × atan(sensorHeight / (2 × focalLength))
frame width at optical-axis depth d  = 2d × tan(horizontalFOV / 2)
frame height at optical-axis depth d = 2d × tan(verticalFOV / 2)
```

Angles in the calculations are radians. The interface displays degrees. World dimensions and distances are metres.

For a centred, flat subject, with framing expansion multiplier `m`:

```text
required distance = max(
  subjectWidth  × m / (2 × tan(horizontalFOV / 2)),
  subjectHeight × m / (2 × tan(verticalFOV / 2))
)
```

Default `m = 1.10`: the subject's required width and height grow by 10% in total, **not 10% extra on each edge**. Equivalently, the real subject must lie within 1/1.10 of the frame's half-extents. Margin is also applied to off-axis displacement, preserving safe frame edges.

For a real 3D subject, the calculator projects **all eight oriented bounding-box corners** into the camera's right/up/forward basis. Every corner `(x, y, z)` must satisfy:

```text
z > 0
m × |x| ≤ z × tan(horizontalFOV / 2)
m × |y| ≤ z × tan(verticalFOV / 2)
```

The full box fits exactly inside this convex frustum when all its corners pass. This handles depth, camera tilt, orientation and off-centre placement rather than using only a subject-height shortcut.

Moving the camera backwards by `t` along its unchanged forward axis leaves `x,y` unchanged and increases every corner's `z` by `t`. Therefore:

```text
raw extra distance = max over corners of (
  m × |x| / tan(horizontalFOV / 2) − z,
  m × |y| / tan(verticalFOV / 2) − z,
  0.001 − z
)
stepBack = max(0, raw extra distance)
requiredDistance = current optical-axis centre depth + raw extra distance
```

`requiredDistance` is the minimum centre depth at this fixed orientation / lateral alignment, even if the subject already fits. `centeredRequiredDistance` is the simpler projected size-only reference. Neither distance is the diagonal Euclidean range to an off-axis subject.

### 2. How far can the camera go?

For each RoomPlan wall, use its transform to map a world ray into the wall's local coordinates. The local wall lies in the XY plane, with local +Z as its normal:

```text
localOrigin = inverse(wallTransform) × [cameraPosition, 1]
localDirection = inverse(wallTransform) × [normalisedDirection, 0]
t = −localOrigin.z / localDirection.z
```

Accept only positive `t` with the intersection inside `±wallWidth/2` and `±wallHeight/2`. A ray parallel to the wall, a hit behind the camera or a point outside the actual wall rectangle does not count. The nearest accepted hit is the wall distance.

Both forward and backward wall distances are returned. Backward travel is bounded by the nearest wall **or inferred floor/ceiling** along `−forward`, minus 0.25m clearance. A pitched camera must not travel through the floor just because the rear wall is far away.

```text
usableBackwardTravel = max(0, nearestBackwardBoundary − clearance)
maximumSubjectDistance = currentSubjectDepth + usableBackwardTravel
canStepBack = stepBack ≤ usableBackwardTravel
```

No rear wall hit stays **unknown (`nil` / `null`)**, not infinite. A floor / ceiling may still supply a backward stop. Open wall footprints do not receive a room-fit pass.

### 3. Walls / ceiling in the frame

A 3×3 grid of rays through frame corners, edge midpoints and centre is intersected against the room shell. If a wall, floor or ceiling is reached **before the subject-centre depth plane**, that surface is reported as intruding into the ideal frame at that depth.

This does **not** mean seeing through walls. It means the frame's ideal subject-depth rectangle goes beyond the room, so opaque room surfaces appear earlier in the shot. A full-body shot will often show some floor; that is not automatically a failed shot. These are sampled presence warnings, not an exact visible-area percentage.

The centre and eight subject corners are also tested for intervening scanned walls. This is a sampled occlusion check, not full mesh visibility.

### 4. Alternative lenses

Each candidate lens is evaluated independently at the **same camera pose**. Two lists are returned:

- `fittingLensesNow`: full framing + room placement + sampled wall visibility pass at this pose.
- `fittingLensesWithStepBack`: pass after actually simulating the exact required backward move, within the available travel. This includes lenses already fitting now.

This avoids recommending a 50mm lens solely because it could frame the subject in an infinitely deep room.

## Sensor presets and focal-length caveat

| Preset | Active area | Interpretation |
|---|---|---|
| Full frame | 36 × 24mm | Full 3:2 area; not a 16:9 recording crop. |
| Super35 | 24.89 × 18.66mm | **Approximate / nominal** 35mm full-aperture gate. Digital Super35 varies by camera and mode. |
| APS-C | 23.5 × 15.6mm | Nominal example; not every APS-C camera, especially Canon. |
| iPhone main | 9.8 × 7.3mm | **Illustrative approximate physical area**, not a claim about a particular iPhone. Replace with measured active dimensions / camera calibration. |

**The Swift `Lens.focalLengthMM` always means a real physical focal length.** Calling the core directly with `Lens(24)` and a phone sensor models an actual 24mm physical lens, which is NOT the usual phone main camera.

For phone mode only, the UI's 24/35/50/85 buttons are explicitly **35mm-equivalent labels**. `sensor.menuLens(24)` converts using `physical = 24 × 9.8 / 36 = 6.533mm`, matching horizontal FOV to 24mm full frame. This is **horizontal equivalence**, not diagonal equivalence; the approximate phone aspect ratio is different. Larger equivalent settings model narrower effective coverage / a crop, not four real interchangeable iPhone lenses. Optical resolution and multi-camera switching are not modelled.

For real video, provide the camera mode's active recording width and height, including aspect-ratio crop / stabilisation. A full-frame 16:9 mode using the full 36mm width has active height 20.25mm, not 24mm. The core accepts custom `Sensor(...)` values.

References checked for this implementation:

- [CineMath — field of view, required distance and nominal Super35 gate](https://www.cinemath.app/guides/camera/field-of-view) — formula and Super35 24.89 × 18.66mm; explains recording crops and close-focus limits.
- [Canon — full-frame versus Super35](https://www.canon-europe.com/pro/stories/full-frame-vs-super-35-sensors/) — format / crop differences and confined-location implications.
- [Apple — CapturedRoom.Surface.transform](https://developer.apple.com/documentation/roomplan/capturedroom/surface/transform)
- [Apple — CapturedRoom.Surface.dimensions](https://developer.apple.com/documentation/roomplan/capturedroom/surface/dimensions)

The phone dimensions are an openly labelled illustrative engineering assumption, not manufacturer data.

## Integrating into SetScan

I read the existing `ScanStore.swift` and `ResultView.swift` as narrow compatibility lookups. The current app saves both USDZ and JSON; `ScanStore.loadRoom(scan)` decodes the JSON to `CapturedRoom`, and `ResultView` already stores it in `@State private var room: CapturedRoom?`. The calculator uses **that JSON geometry**, not the USDZ's render mesh.

### Quickest integration: a sheet

1. Copy `FrameCheck.swift`, `RoomPlanAdapter.swift` and `FrameCheckView.swift` into `SetScan.swiftpm` in Xcode / Swift Playgrounds and ensure target membership.
2. Add to `ResultView`:

```swift
@State private var showFrameCheck = false
```

3. Add a button alongside the existing toolbar `ShareLink`:

```swift
Button("Frame check") { showFrameCheck = true }
    .disabled(room == nil)
```

4. Add this modifier to the outer view, beside the existing `.task` / `.toolbar`:

```swift
.sheet(isPresented: $showFrameCheck) {
    if let room {
        NavigationStack {
            FrameCheckView(room: room)
                .toolbar {
                    Button("Done") { showFrameCheck = false }
                }
        }
    }
}
```

Do not replace the existing scan-loading task. A sheet keeps the 3D scene and summary layout intact. The demo positions start from the **actual world-coordinate bounds** of the scan. In concave rooms the initial bounding-box-based guess may lie outside the room; adjust sliders and respect the warning.

### Calling the pure core

```swift
let geometry = try RoomGeometry(capturedRoom: room)
let sensor = Sensor.fullFrame
let pose = Camera(position: SIMD3(2, 0.9, 0.9), forward: SIMD3(0, 0, 1))
let subject = Subject(center: SIMD3(2, 0.9, 3.6), size: SIMD3(0.65, 1.8, 0.35))
let result = try FrameChecker.check(
    room: geometry, camera: pose, subject: subject,
    lens: sensor.menuLens(35), sensor: sensor,
    margin: 1.10, clearance: 0.25,
    candidateLenses: [24, 35, 50, 85].map { sensor.menuLens(Float($0)) }
)
```

The example coordinates must be replaced by the coordinates of your own scan. The Swift view is a **numerical scout**, not wired to SceneKit's freely orbiting display camera or a live AR camera.

To connect SceneKit later, use the actual shot camera's world transform: position = translation column, forward = **negative** world column 2, up = world column 1. Normalize the basis. Keep its coordinate system aligned with CapturedRoom, not a separately recentered/scaled USDZ node. Changing an orbit viewer's display camera alone does not change the physical planned shot.

The Swift UI's heading 0° points toward world −Z; 180° points toward +Z, matching the browser. Subject position is the **box centre**, not feet. Subject orientation defaults to world axes but the core accepts an orthonormal `simd_float3x3`.

## Verified results

Commands run on this box:

```sh
node verify_math.cjs
node verify_browser.mjs
```

- **21 math checks passed**, including **1,000 deterministic independent algebra comparisons** for different subjects, depths, margins, sensors and focal lengths.
- **19 headless Chrome interaction checks passed**: real pointer dragging, keyboard movement, presets, sensor / lens changes, heading / aim, actual step-back, invalid input, room resize, outside-room rejection and 390px responsive layout.
- **Zero JavaScript runtime / console errors** in the browser run.
- `swiftc` is **not installed** on this box. The Swift / RoomPlan / SwiftUI files have **not** been compiled or exercised on an iPhone. The equivalent math and transformed-wall geometry are tested in JavaScript; that does not prove Apple SDK typechecking or device behaviour. On a Mac, first run `xcrun swiftc -typecheck FrameCheck.swift`, then build the app with all three files in Xcode.

Known numerical examples:

| Setup | Result |
|---|---|
| Full-frame 50mm | HFOV **39.5978°**, VFOV **26.9915°**. |
| 1.8m-tall flat subject, full-frame 50mm, 10% expansion | **4.125m** required; a 0.35m-deep centred box requires **4.300m** centre depth. |
| Default 4 × 5 × 2.7m room, full-frame 35mm, camera Z=0.9m, subject Z=3.6m | Current depth **2.700m**, required **3.0625m**, step back **0.3625m**. Rear wall **0.900m**, usable travel **0.650m**. |
| Same shot, 24mm | Fits now, required centre depth **2.155m**. |
| Same shot, 50mm / 85mm | Too tight even after using the available rear clearance. |

## Honest limits

- **Not integrated into the real app yet**; source files and integration instructions are supplied separately. No GitHub changes, PRs, deployments or messages were made.
- The web app's room is a configurable rectangle, not a scanner or a CapturedRoom JSON importer.
- Swift geometry assumes rigid RoomPlan surface transforms, world Y up, and a single closed, non-self-intersecting wall footprint. Endpoint matching uses a 3cm tolerance. Disconnected loops, self-intersections, curved/sloped walls, mixed-height surfaces and malformed scans need a stronger validated room-volume model.
- Floor and ceiling are horizontal estimates from wall extents unless supplied explicitly. Floor / ceiling hits use those planes. Geometry uncertainty is not a measured accuracy certificate.
- Doors/windows/openings remain solid wall rectangles; their apertures are not subtracted. Closed-footprint rejection is conservative around scan gaps.
- In-frame box containment is exact for the pinhole model. Room containment uses corner samples; wall occlusion uses nine subject samples; composition warnings use nine frame rays. Narrow obstacles / unusual concave configurations can be missed. No furniture or crew collision, traversable-path planning, camera-rig volume or full-mesh visibility.
- Room-surface visibility is reported at **subject depth**, not on a chosen background plane or at infinity.
- No lens distortion, anamorphic squeeze, close-focus breathing, entrance-pupil offset, depth of field, portrait sensor rotation, real phone stabilisation crop, focus / image-quality prediction or device calibration.
- The 0.25m rear clearance is a configurable planning allowance, **not a physical-safety guarantee**. Check the actual lens/rig/operator and location.
