# Android → iOS handoff: what CineX Android learned

For whoever (person or Claude agent) takes the iPhone version on the Mac. The Android app in `android/` was built and tested on a Galaxy S24 between 6 and 7 Nov 2026 (releases android-v0.1.11 to v0.1.60). This file is the distilled result: what the product should do, what worked, what failed, and how each piece maps onto ARKit / RoomPlan. Read it before porting anything, and read the named Java file when you port that feature.

Code: `android/app/src/main/java/com/hackathon/setscan/`. The biggest file is `ScanRenderer.java` (GL, scanning, viewer); `MainActivity.java` is the UI.

## 1. Product decisions (from rk, the owner)

- **Phone only.** No desktop GPU step. Everything, including splat training, runs on the phone. The team has no NVIDIA GPU.
- **A believable room, not a measured CAD model.** rk does not want a LiDAR-grade reconstruction contest. They want a best-guess room from photos plus ARCore measurements, shown as photo-textured splats.
- **No drawn guesses of objects.** Cartoon furniture models and Claude-labelled boxes were tried and removed because they were misleading. Don't bring them back without asking.
- **Manual room marks were abandoned.** Tapping corners, walls, floor and ceiling while scanning was "so off" in practice (7 Nov). It is off on Android, except for 2 corner taps to line up a second pass. On iOS, **use RoomPlan's walls, floor and openings instead**. That is the single biggest advantage the iPhone has.
- **The live scan must show progress.** Splat discs appear over the camera as you scan, coloured by how often each spot has been seen: red (once), then yellow, then green (solid). Solid spots fade to faint dots so the remaining red and yellow show where to hold still. A status line says "Detail here: N% solid".
- **Saved scans and a home screen.** The app opens on a grid of saved scans (thumbnail, name, date) with a New scan button. A save asks for a name and takes the thumbnail from the current 3D view. A saved scan can be reopened and **improved** with a second pass.
- **The lighting preview is core to the product.** It covers gear lights (type, power, colour temperature, height), people, table and chairs, key and fill by default. The room's existing light counts, so the room is never shown dark. The camera is exposed for the subject's face, with an f-stop readout.
- **Claude in the app:** the user's API key is entered **in the app only**. Never ask for it in chat, never commit it. Model `claude-opus-5-5` (`ClaudeHelper.java`).
- Portrait only. Demo: Sun 8 Nov 2026, Film & Multimedia TECH Hackathon (IDA Hub Jõhvi).

## 2. Pipeline that works (in order)

| Stage | Android file | What it does | iOS mapping |
|---|---|---|---|
| Live capture | `ScanRenderer.integrate` | Raw depth 160x90, keep conf ≥ 55 (≥ 40 for TSDF), range 0.2–4 m, edge filter (neighbour jump > 4% + 2 cm = flying pixel). 2 cm voxel hash: first hit adds a point, later hits average position and colour. | `ARFrame.sceneDepth` (LiDAR, 256x192, confidence map: keep `.high`, `.medium` for fusion). Same voxel hash. LiDAR is far cleaner than S24 depth from motion, so expect better results with the same code. |
| Seen counter | `refine`, `seenStamp` | Count each spot **once per depth frame**, because many pixels land in one 2 cm cell, otherwise everything turns "solid" in one frame. Solid = 12 depth frames. S24 raw depth runs at only ~1.6 Hz. | LiDAR depth runs at 60 Hz, so set solid to about 30–60 frames, or count time-separated views. |
| Live discs | `drawLiveDiscs` | GL point sprites over the camera: ring = heat colour, core = 80% point colour; solid → small 35% dot. Size = 2 cm × 1.6 × focal px / depth. | Metal point sprites in the AR view, or `ARSCNView` with a custom geometry. |
| Keyframes | `PhotoRoom` | ~1024x512 photos with pose + intrinsics + low-res depth (for occlusion), taken on enough movement or turn. Projection `u=(fx*xc/d+cx)/w`, `v=(cy-fy*yc/d)/h`, view = inverse(pose). | `ARFrame.capturedImage` + `camera.intrinsics` + `camera.transform`. Same maths (ARKit is also y-up metres, camera looks down −z). |
| Clean-up | `CleanUp.java` | Floor and ceiling from height-histogram peaks, snap points within 6 cm, remove specks (seen once / seen through), fill floor holes by dilation (3 cm cells, ≤ 40 cm). **Automatic Hough walls failed** (walls through the room on a drifted scan) and are disabled. | Use RoomPlan `CapturedRoom.walls/floors/openings` as the planes to snap to. |
| Surfels | `Surfels.java` | Per point: PCA normal over 5 cm neighbours, radius from local density, snap to plane if flat. Drawn as screen-space ellipses (disc lying on the surface) with a gaussian edge. | Port directly (pure maths). |
| Splat training | `Trainer.java` | CPU differentiable gaussian rasteriser on the phone: 3 cm merged splats, 256x144 training images, front-to-back alpha with backward pass, Adam over colour / opacity logit / log-scale / 3D offset (max 12 cm), constant grey background 0.45. **The background must be constant**: with the photo as background it "cheats" by making splats transparent. Gain: 10–15% on held-out views. Live preview while training, Pause button. | Same algorithm. Port to Metal compute for big speed-ups; the CPU version is about 400 ms per step for 135k splats. |
| Depth AI | `DepthNet.java` | Depth Anything V2 Small (LiteRT int8, Apache-2.0) on each keyframe; fit `1/d = a·r + b + c·x + d·y` per frame to the ARCore points (IRLS), back-project 192x108, fuse. 6–7% median error vs ARCore. | **Probably not needed with LiDAR.** Only useful beyond LiDAR's 5 m range. Core ML has a Depth Anything V2 model if wanted. |
| Bundle adjustment | (not shipped) | Per-frame ICP 6DOF + scale reduced mismatch from 9.5 to 6.5 mm with no visible gain. | Skip. ARKit + LiDAR drift is lower anyway. |
| Saved scans | `ScanStore.java` | `scans/<id>/`: meta.json (name, dates, counts, marks), points.bin, kf.bin + kf/NNN.jpg, path.bin, thumb.jpg. Writes go to a `.saving` folder, then swap. | The iOS `ScanStore.swift` already saves RoomPlan usdz + json. Add the points and keyframes next to them. |
| Improve scan | `startImprove`, `mergeImprove` | A second session has a different origin. 2 corner taps → 2D rigid fit to saved corners; candidates scored by overlap of mid-height occupancy (10 cm cells). Saved room shown as a blue ghost. Merge on finish. | Use **ARWorldMap** save/relocalise. It lines the second pass up automatically, so no taps are needed. |
| Lighting | `Lighting.java` + `LIGHT_GLSL` in ScanRenderer | See below. | Port maths; shader → Metal / SceneKit shader modifier. |

## 3. Lighting model (keep these numbers)

- Scan colours already contain the room's light. Assume `ROOM_LUX = 250`, so albedo = colour / 250 lx.
- Displayed colour = `lin(col) * (roomDimmer + Σ lux_i / ROOM_LUX) * exposure`, then a tone curve (shoulder above 0.8) and gamma 2.2.
- Per light: inverse square (min distance² 0.04), cone smoothstep between beam/2 and 0.6·beam/2, wrap-lambert `max(0,(n·l+wrap)/(1+wrap))`, `abs(n·l)` for unoriented points.
- Bright points (luminance > 0.8) are lamps and windows: lit by room light only, never by gear. Clusters of them above 1 m are marked "room light".
- Exposure: bring the subject's face (person prop, normal towards the camera) back to how the room looked in the scan, ± EV slider. f-stop readout: `N = sqrt(E · t · ISO / 250)` with ISO 800, 1/50 s.
- Kelvin tint: Tanner Helland's black-body approximation, divided by the white balance.

| Type | Lux at 1 m | Beam | Wrap |
|---|---|---|---|
| Fresnel 300 W LED | 20000 | 30° | 0 |
| LED panel 1x1 | 3000 | 60° | 0.3 |
| Softbox 90 cm | 6000 | 100° | 0.6 |
| LED tube | 800 | 140° | 0.5 |
| Practical lamp | 300 | 170° | 0.7 |

Props: standing person face height 1.62 m, seated 1.18 m, table 0.75 m, chair 0.45 m. The default setup is a seated person with the key light (softbox, 60%) up and to one side and the fill (panel, 30%) on the other. Missing: **shadows** (light still passes through furniture and people). That's the next step on both platforms.

## 4. Pitfalls we hit

- Pose jumps: when ARCore corrects its map, the pose jumps more than 15 cm in one frame. Pause capture for 1.5 s after that, or the scan doubles walls. ARKit has the same behaviour; watch `camera.trackingState`.
- Doubled walls on long scans come from drift, not from depth noise. A second pass aligned to the first beats more filtering.
- Fill unused shader uniform slots with safe values: unused light cone directions are set to (0,1,0), never a zero-length vector that would be normalised.
- Keep the view opaque: anything drawn with blending can leave alpha < 1 and the compositor shows through. Clear alpha to 1 after overlay passes.
- Thumbnails: read back the GL view at save time, flip vertically, scale to 360 px wide.
- The keyframe budget is about 80 photos; more costs memory without visible gain.

## 5. Suggested port order for iOS

1. Merge the team branches (gear-inventory, shoot-planner, zeynep/FrameCheck; mesh-overlay needs moving from SetScan.swiftpm to CineX.swiftpm).
2. Run the ARKit session alongside RoomPlan (RoomPlan's `RoomCaptureSession` exposes its `arSession`). Collect LiDAR depth into the voxel point cloud with seen counters, and draw the **live discs**.
3. Keyframes plus surfels in the result view; save points and keyframes with the scan.
4. The lighting preview on top of RoomPlan's room (walls/floor from RoomPlan, colour from the splats).
5. Splat training (Metal), then improve scan via ARWorldMap.

Test data: scan exports from the S24 (zip: frames/*.depth 160x90 uint16 mm, *.conf, *.pgm grey, frames.csv with column-major poses and intrinsics, keyframes/*.jpg + keyframes.csv, poses.csv at 30 fps, sensors.bin). These are useful for checking algorithms off-device.
