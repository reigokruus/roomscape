# Overnight work, 9–10 Nov 2026 (Mac only, no installs)

Phones disconnected at 22:45; work from the copies in recordings/ (never committed).

## Test data on the Mac
- iPhone: recordings/ios/Scan_2026-11-09_20-33-52 (8.7 min, climbing bars, tap-to-clear), _22-14-16 (6.3 min, area rescan), _22-39-49 (3 min, tonight's test), _19-00-27 (same room as S24 18:57).
- S24: recordings/s24/files/scans/scan_1794256631094 (22:37, same room as iPhone 22:39), scan_1794243449890 (18:57).
- Bars: 20:33 recording, frame 1300, depth pixel (104, 64) ≈ 0.68 m; real bar diameter 3 cm (rk), ~9–15 cm in front of the wall/ceiling.

## Done tonight (committed, not installed)
- Tsdf.unseenAsEmpty on: thin things in front of a wall get their sides; fills edge holes (19:00 room: 0.8% → 0.1% missing vs CPU, no junk in renders).
- PhotoLayout.best: occlusion checked at the centre and three inner corners (bar photos no longer painted onto the wall behind; shared by live preview and photo room).
- DetailZones (DetailZones.swift): close-up spots → 1.2 m cubes at 1.5 cm; harness ZONES=1. On 20:33: 53 spots, 24 zones, 929k fine triangles in 13 s (too many: needs flat-area decimation before it can go live).
- Splats view + splat training removed on both phones (1384608); Sharpen (the training) is gone with it.
- Lighting lights the photo room on both phones (c04eee2). iPhone checked in the Simulator (tools/ui-shots/20261109-231204/7-lighting.png); S24 builds, not yet seen running.
  The splat data stays internally: Objects (moving/removing things), hole fill, the storyboard and Lighting's fallback still read it. Removing it means porting those first.
- Scan memory (22:39: 434 MB before RoomPlan, 720 MB at start, 1.55 GB at the end): our own data grows only ~25 MB
  (photos 16 MB, mesh 1.6 MB; points, TSDF and photo textures are fixed). Scan queues now drain autoreleased
  objects per block (suspect), and the log records each part's size every 30 s (3ea305d). Needs one real scan to confirm.
- Race-to-idle pacing, per-scan heat totals, memory checkpoints, rescan logs (installed 22:35 on the iPhone; S24 logger installed by rk 22:35).

## Open
- Bar thickness measurement in the harness (BAR=x,y) picks up the bracket/wall: 12.4 cm, not a valid measure yet.
- Fine-zone triangle budget: simplify flat fine areas (or keep fine only where it differs from the coarse surface).
- Live GPU photo-fill; GPU mesh extraction; iPhone memory growth during scanning (720 MB → 1.55 GB in 3 min).
- S24 vs iPhone (22:37 vs 22:39): align, map where S24 filled better (chair back, white cupboard by the door, bars), classify, prototype hole-only fill.
- S24: big X4 core idle (0 s), drawing at 10.5 fps; height 2.21 m on a rough scan (ceiling barely covered).
- S24 GPU (Xclipse 940, Vulkan 1.3 / GLES 3.2 via ANGLE): port the TSDF kernel as a GLES 3.1 compute shader.

## S24 22:37 vs iPhone 22:39 (harness align / why)
- Aligned: yaw 348°, S24 scale 0.97, 87% of the S24 surface within 15 cm of the iPhone's; 21.5% S24-only.
- The S24-only areas checked against the iPhone's own recording (harness why): mostly the iPhone's LiDAR saw
  *past* those spots or something nearer, with high confidence (so the S24 surface there is offset or phantom,
  not an iPhone hole); one spot (1.1 m up, near the chair) had matching high-confidence iPhone depth and iPhone
  surface 6–12 cm away: an alignment residual, not a hole. rk's chair-back / cupboard / bars spots still need
  locating by hand in renders.

## Detail zones, triangle budget (20:33 recording, 24 zones)
| fine kept where it strays from the coarse surface by | fine triangles | total | time (Mac) |
|---|---|---|---|
| all (no check) | 929k | 980k | 13 s |
| > 1.2 cm | 842k | 902k | 12 s |
| > 2.5 cm | 591k | 675k | 27 s |
| > 4 cm | 167k | 272k | 27 s |

Measured to the coarse *surface* (nearest corner's normal) instead of the nearest corner (23:30):
| kept where the fine surface is off the coarse plane by | fine triangles | total |
|---|---|---|
| > 0.6 cm | 663k | 738k |
| > 1.0 cm | 566k | 648k |
| > 1.5 cm | 435k | 527k |
So it isn't the test: at 1.5 cm voxels the fine surface is rough with LiDAR noise (renders: fragments, not rods), and
most of it really is > 1.5 cm off. Fine re-fusion isn't the way to the 3 cm bars on its own. Next idea: thin-structure
placement from points seen ≥ 3 times that sit > 3 cm off the mesh (rods fitted to them), not finer voxels everywhere.
Bars: at > 4 cm the upper bar appears as a rod segment, the lower one still in fragments (renders:
tools/harness/harness-out/bars-zones-dev*.png). Not ready for the phone: needs a proper simplifier (quadric) so
flat fine areas collapse, and zone placement that follows thin things (not only the view centre).

## Points vs fused surface (rk's S24 screenshot, 22:37 Points view)
- The S24's points show the ladder bars clearly (coloured dots along each rung) where the fused surface averages
  them away: points keep each sample, the TSDF fuses and erases thin things.
- Next: a "points where the mesh has nothing" layer: points seen ≥ 3 times that are > 3 cm from any mesh corner
  are thin parts the surface lost; draw them (as small photo-coloured discs) in the Photo view and/or seed fine
  detail zones there. Test on the iPhone 22:39 and S24 22:37 scans (find the ladder frames in the 22:39 video first).

## Thin rods (ThinRods.swift, harness RODS=1; 20:33 recording), 03:30–04:00
Depth points more than 3 cm off the 4 cm surface, on the camera's side of it, in 1.5 cm cells; a cell counts when it's
hit in at least half the frames that could see it unblocked; straight runs of cells (RANSAC + PCA, no gap > 5 cm,
≥ 15 cm long) become 8-sided cylinders.
| round | candidate cells | rods | what was wrong |
|---|---|---|---|
| 1: off-surface, seen in 3+ frames | 178k | 60 | wall noise; rods lying in wall and floor planes |
| 2: + hit in ≥ half the frames that could see it | 5.3k | 60 | a plain wall above a door: the mesh 3+ cm in front of the depth there |
| 3: + only on the camera's side of the surface | 1.4k | 17–19 | rods now on real thin things (a ladder rail at x −0.50, a chair's star base on the floor) |
Not good enough for the phone yet: the upper pull-up bar is mostly inside the coarse mesh already (a flat slab), so only
its first 11 cm near the wall is off the surface; diameters come out at the 5 cm cap (cells only on the seen side);
130 s on the Mac for 4,000 frames (needs every 2nd frame / pixel for the phone). Next: replace the slab with the rod
(drop coarse triangles inside a rod's tube), fit radius from both sides, speed.
Pictures: recordings/overnight-pictures/rods/.

## S24 vs iPhone: chair / cupboards, and the hole fill (04:00–05:10)
Side by side from the same cameras (harness `align` with ALIGN_LOOK at RoomPlan's chair and storage objects): the S24
surface is blurrier but continuous; the iPhone's is sharper with holes on the floor and on box sides seen at a glancing
angle, which is where its LiDAR marks depth low-confidence (the iPhone drops it; the S24 fuses everything).
Hole fill from the iPhone's low-confidence depth (LowConfidenceFill, in the app's rebuild from recording):
| round | 22:39 surface added | far from any old surface | S24-only (lower = iPhone has more) | problem |
|---|---|---|---|---|
| before | – | – | 16.6% | holes |
| 1: all low-confidence, only into empty voxels | +27% corners | 3.2% | 10.3% | shards at thin things |
| 2: + within 3 voxels of seen surface | | 1.5% | | |
| 3: + smooth 5×5 only | +16% | 1.3% | 10.9% | 20:33: a sheet by the ladder |
| 4: + 0.4–3 m, weight ≥ 6 | +11% | 0.6% | | the ladder sheet stays |
| 5: + glancing angle only (> 60°) | +2.4% | 0.2% (20:33: 0) | 11.7% | (shipped) |
App check (Simulator, 20:33 recording through the real rebuild): 4.3k voxels filled (harness: 4.4k), surface 11 s, memory
peak 293 MB (was ~300). Pictures: recordings/overnight-pictures/hole-fill/ (view*-before/after/s24, bars-*).

## Live scan: GPU → mesh sync, and where the build time goes (05:10–05:40)
Virtual scan of the 22:39 recording in the Simulator (Debug build), per live mesh build:
| | sync from GPU | extract | photo choice | upload | recording covered in 3 min |
|---|---|---|---|---|---|
| before | 1010 ms | 77 ms | 24 ms | 3.5 ms | 31 s |
| incremental sync (only changed 8³ bricks) | 61 ms | 101 ms | 42 ms | 4.6 ms | 57 s |
(extract and photos grow with the mesh: the second run got further into the room.) Harness SYNCTEST on 22:39: incremental
vs full copy give identical meshes (104,216 triangles), 1.0 ms vs 8 ms per sync (Mac, optimised). The phone runs a
Release build, so its saving is smaller (tens of ms per build, twice a second). Extraction and photo choice would be
~10–15 ms each on the phone: not worth a risky GPU port before the phone's own stage timings (now logged: depth frame,
live mesh stages) say otherwise.

## S24 room size (fixed, b7d0e68)
- Viewer size now from the room model (5.4 × 3.8 m for 22:37, was the points' bounding box 6.8 × 5.6 m, 3.6 m);
  height "not scanned" when fewer than 20 columns reach 1.8 m above the floor.

## What a scan keeps (audit, 3-minute scans 22:39 iPhone / 22:37 S24)
| iPhone file | MB | used by | proposal |
|---|---|---|---|
| recording/ (video 131, depth 69, frames 0.2) | 200 (~67/min) | rebuild after Done, recovery, text patches | keep |
| photos/ + keyframes.json | 16 | Photo view, objects, sharing | keep |
| before-rebuild/ (photos 16, points 6, surfels 6, mesh 1) | 29 | backup of the live result when "Build scans from the recording" is on | keep for now (data is gold); offer "delete backups older than N days" |
| surfels.bin | 8 | the Points view's dots, Objects (finding and moving things), the patch colours where an object was removed, S24 imports without a photo room; Lighting only as a fallback now | keep: dropping it means dropping or rebuilding Points (rk's call); it is a cache of points.bin, so it could be rebuilt on open instead (a few seconds each time) |
| points.bin | 7 | Points view, objects, Continue scan | keep |
| worldmap.arexperience | 11 | Continue scan / Scan area again | keep |
| mesh.bin | 1.6 | Photo + Mesh views | keep |
| perf logs | 0.1 | analysis | keep |
Live per frame: per-point normals (LiveCloud.normalBuffer, 4.8 MB) only for the pre-mesh dots → can go.

| S24 file | MB | used by | proposal |
|---|---|---|---|
| recording.mp4 | 193 (~64/min) | nothing yet (no S24 rebuild) | keep for a future rebuild, or offer to delete after N days |
| kf/ + kf.bin | 5 | photos + their depth/poses | keep |
| points.bin | 6 | Points view, room analysis | keep |
| surface.ply | 2.7 | Photo/Mesh views | keep |
Splat building after Done (3–7 s): still needed for Points and Objects (see surfels.bin above).

### Splat data: what still reads it (05:45)
Lighting is off it (c04eee2). Still on it: the Points tab (the dots are the splats drawn as points), Objects (ObjectsScene
builds each object's part from them; Claude's object finding feeds it), the patch under a removed object (colours of
nearby splats), the storyboard's size fallback, and S24 scans imported without a photo room. The photo room already
cuts out / moves objects without them (HoleFill.cutOut). Options for rk: (a) keep as is; (b) Points from points.bin
directly (plain dots, no splat build) and patch colours from the photo room, then drop surfels.bin and the build.

## Backlog (rk 9 Nov, 23:00)
- S24 recordings are kept (rk). Add an S24 "rebuild from recording" (ARCore playback of recording.mp4 through the
  scan pipeline, as the iPhone's RecordingProcessor), after the iPhone overnight items, so old S24 scans can be
  reprocessed.
