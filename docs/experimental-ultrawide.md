# Experimental ultrawide scanner (iPhone)

A second, experimental scanner that uses the iPhone's **ultrawide** camera with LiDAR depth and RoomScape's own
tracking (ARKit can only use the wide camera; see `ultrawide-probe-2026-11-09.txt`). It lives entirely in
`CineX.swiftpm/Experimental/Ultrawide/` and appears on Home as **Experimental ultrawide scan**.

How it works: LiDAR depth and the ultrawide stream together (AVCaptureMultiCamSession); each depth frame is
matched to the previous good one with point-to-plane ICP, starting from the gyro's rotation; good frames are fused
into the app's own TSDF / live mesh (`LiveMesh`, used unchanged); ultrawide photos are kept with their poses. A
scan is saved as an ordinary scan without a RoomPlan model (like a room from the Android app).

## Turning it off

Set `ExperimentalUltrawide.enabled = false` in `Experimental/Ultrawide/ExperimentalUltrawide.swift`.

## Removing it

1. Delete the folder `CineX.swiftpm/Experimental/Ultrawide/`.
2. In `CineX.swiftpm/HomeView.swift`, delete the line `ExperimentalUltrawideRow { scans = ScanStore.list() }`
   (and its comment).
3. Delete this file.

Scans made with it keep opening: they are ordinary scans (points, photos, mesh, an empty model), and their
`source-manifest.json` just says "experimental ultrawide".
