# ultrawide-bench (experiment)

`track_bench.py`: tracked detail per frame in a video (FAST corners at a fixed contrast, KLT with a forward-backward
check), to compare the ultrawide with the main camera on the same walk (`--crop-fov 104 74` for the ultrawide cropped
to the main lens's view). Needs `opencv-python-headless` and `numpy` (a venv). Recordings show a real home: Mac only.

Checked on 11 Nov against ARCore's own feature counts in the 20:48 S24 scan (its recording.mp4 and scan-log.txt):
per-second correlation 0.77 (with `--fast 25`, the default); about 60 tracked where ARCore saw under 20 features,
about 190 where it saw 60 or more. It measures the picture, not ARCore: a low count means little to track.
