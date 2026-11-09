# Test harness (Mac)

`tools/harness` is a small macOS Swift package that runs RoomScape's own non-ARKit code on a **virtual phone in a
synthetic room** and on **real .rmsc scans**, so changes can be checked before anyone tests on a phone.

The app's files are used unchanged through symlinks in `tools/harness/Sources/Harness/App/` (Tsdf, the experimental
tracker, hole fill, zip); `Shims.swift` stands in for the few iOS-only types they mention.

## Run

```
cd tools/harness
swift build -c release
./.build/release/Harness all                     # every test, every scenario (~2 min)
./.build/release/Harness track tight [s24]      # experimental tracker vs ground truth; meshes as PNGs
./.build/release/Harness debugtrack chest [clean] [verbose]   # frame by frame
./.build/release/Harness holefill               # chest of drawers removed: patch colours vs the real wall / floor
./.build/release/Harness clear                  # tap-to-clear's rule on the TSDF
./.build/release/Harness calibrate scan.rmsc    # depth noise / dropouts / edges from a real scan
./.build/release/Harness rmsc chest out.rmsc    # a synthetic scan the apps can open (AirDrop / send it)
```

Pictures and files go to `tools/harness/harness-out/`.

## The virtual phone

- **Rooms** (`Scene.swift`): tight 2.5 × 3 m (rk's case), normal 4 × 3 m, a plain white corridor (the hard case),
  and a chest of drawers against a wall plus a chair. Walls are plain paint or textured; parquet floor; a door,
  a window (glass: no depth), furniture boxes, one black glossy (LiDAR dropouts).
- **Path**: a slow hand-held loop around the room, sweeping left/right and up/down (to ~45° down), walking bob and
  hand tremor.
- **LiDAR** (iPhone 15 Pro profile): 256 × 192 depth on the wide camera's 71° view, 0.25–5 m; per-pixel noise and
  a per-frame slow warp; dropouts on dark / glossy / glass and at grazing angles; flying pixels at depth edges; a
  low / medium / high confidence map.
- **Gyro**: the true rotation between frames plus bias and noise. **Poses**: ground truth, and an ARKit-like pose
  with ~1% drift per metre walked, slow yaw drift and jitter.
- **Camera pictures**: shaded colour images (wide 71°, ultrawide ~106° undistorted), slow exposure change, noise.
- **S24 profile**: 160 × 120 depth from motion, noisier, dropouts on plain walls, needs movement, 2% drift.

### Calibration (9 Nov, from rk's scans)

| | iPhone (Scan_2026-11-09_14-20-34) | S24 (Room 9 Nov 14:19) |
|---|---|---|
| per-pixel noise | 0.4 mm at 1–4 m, ~4 mm at 4.5–5 m | 2 mm at 1–1.5 m → 5 mm at 2.5–3 m |
| fit σ(d) | 0.0005 + 0.00012·d² m | 0.0013 + 0.00052·d² m |
| dropouts | 9.9 % | 6.9 % |
| depth edges | 0.8 % | 3.6 % |

These are measured on the depth the apps store (ARKit's / ARCore's already-smoothed depth, at half size), so they
are mostly fine-scale noise; ARKit's real error is a slow warp across the frame, modelled as `warp` (0.4 % of
depth per frame, from published LiDAR accuracy), which single frames can't measure.

## What it can't do

- ARKit's VIO, RoomPlan and ARMeshAnchor: not emulated (RoomPlan-like walls / doors / windows are written as ground
  truth into the synthetic .rmsc). The app's scan screens and Metal views don't run here.
- Rolling shutter, motion blur, lens-distortion lookup tables, white balance: not modelled.
- Photo texturing and the viewers: open a synthetic .rmsc in the apps instead.

## What it found so far (9 Nov)

- The experimental ultrawide tracker (depth-only ICP + gyro) is not usable yet: with only a wall and the floor in
  view, sliding along them doesn't change the depth, so it can't see that motion and drifts by metres; it can't
  start facing a window. It needs camera-picture (visual) tracking for that.
- Hole fill coloured the floor patch with the wall when the object stood against a wall: fixed (the floor band
  ignores a side with a wall behind it).
- Tap-to-clear's rule clears the tapped wall and nothing past it.
