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
