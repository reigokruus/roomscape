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
