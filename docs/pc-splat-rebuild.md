# Photo-real room on a PC (Brush), from an iPhone scan

For the Legion (RTX 4070) or the 5060 PC. The package comes from the Mac (`tools/pull-latest-ios-scan.sh`), over the
home Wi-Fi (the `serve-ios-<stamp>.sh` address) or a USB stick. Private: it shows a real home; keep it on local disks.

## 1. Dataset (Python 3 with numpy and opencv-python)

From the package's `code/tools-room-rebuild-and-viewer.tar` (`tools/room-rebuild/ios_to_nerfstudio.py`):

    # preview: 300 sharpest frames at half size (960x720)
    python ios_to_nerfstudio.py scan-<Scan>/<Scan>.capture  ds-preview --max-frames 300  --scale 0.5 --max-points 400000
    # full: up to 1000 sharpest frames at the video's own size (1920x1440)
    python ios_to_nerfstudio.py scan-<Scan>/<Scan>.capture  ds-full    --max-frames 1000 --scale 1.0 --max-points 1000000

Optional check (should print about 0.6): `python check_nerfstudio.py` needs times.json; or trust the app's rules
(the same as SplatDataset.swift, checked on the 10 Nov 20:09 scan: 0.61-0.63).

## 2. Train with Brush (brush-cli or the Brush app, v0.3.0 as on the PCs; newer main calls the flag --total-train-iters)

    # (a) preview, about 3 minutes on the 5060 / 4070: phone quality
    brush ds-preview --total-steps 7000 --max-splats 400000 --max-resolution 960 \
          --export-every 7000 --export-path out --export-name preview.ply
    # (b) full version, about 45-60 minutes
    brush ds-full --total-steps 30000 --max-resolution 1920 \
          --export-every 10000 --export-path out --export-name full_{iter}.ply

The camera poses are ARKit's, which Brush reads as they are (Nerfstudio/OpenGL convention); the splat comes out in the
scan's own coordinates (y up, metres), like the Mac's room models.

## 3. Back to the Mac viewer

Send the .ply (LAN server with a random path and SHA256SUMS.txt, as before). On the Mac it goes to
`recordings/rebuild/<room>-splat/` and gets a light copy for the browser:

    tools/room-viewer/ply2splat.py <file>.ply <name>-light.splat --max 1500000

Then it's listed in http://127.0.0.1:8765/tools/room-viewer/splat.html (fly with W A S D).

## From the iPhone's Share menu: "For a PC rebuild (.zip)"

On location there's no Mac in between: the phone's room screen → Share → For a PC rebuild (.zip) → Quick or Full
quality, sent with the share sheet (AirDrop, Files, Google Drive). Made by `CineX.swiftpm/PCRebuildShare.swift`; the
scan itself is only read. Files are stored uncompressed (video and JPEGs are compressed already). **No top-level
folder**: the entries sit at the zip's root, so unzip into a folder of its own.

Name: `RoomScape-<yyyy-MM-dd_HH-mm-ss>-for-PC-quick.zip` or `…-for-PC-full.zip` (the scan's time, from
`Scan_<yyyy-MM-dd_HH-mm-ss>`).

Quick (about 30-40 MB): the phone's own preview dataset, ready for Brush as it is (`brush <unzipped folder> …`):

    README.txt          what it is, a Brush example line
    transforms.json     Nerfstudio: "camera_model": "PERSPECTIVE", "ply_file_path": "points.ply", "frames": [
                        { "file_path": "images/00014.jpg", "transform_matrix": 4x4 rows (ARKit camera-to-world =
                          OpenGL/Nerfstudio), "fl_x", "fl_y", "cx", "cy", "w", "h" (per frame, at the image size) } ]
    points.ply          starting points: binary_little_endian, x y z float + red green blue uchar (15 bytes each),
                        at most 300,000 (the phone's setting; subsample to 200,000 for benchmark run A parity)
    images/NNNNN.jpg    up to 300 sharpest frames at half size (960x720); NNNNN = the frame's index in the
                        recording, 5 digits

Full quality (100-500 MB): the raw recording, for `ios_to_nerfstudio.py` (section 1) with any frame count and size:

    README.txt
    Scan_<time>.capture/recording/video.mov    1920x1440 HEVC
    Scan_<time>.capture/recording/frames.bin   120 bytes per video frame (time, pose, lens, size, tracking)
    Scan_<time>.capture/recording/depth.bin    LiDAR depth (LZFSE), when recorded
    Scan_<time>.capture/points.bin             the scan's dots
    Scan_<time>.capture/meta.json              when present

    python ios_to_nerfstudio.py <unzipped>/Scan_<time>.capture ds-full --max-frames 1000 --scale 1.0 --max-points 1000000

No manifest or hash file inside: the zip's own CRC-32 per entry checks the transfer (`unzip -t` / Python
`zipfile.testzip()`).
