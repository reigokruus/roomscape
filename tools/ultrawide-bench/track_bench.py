#!/usr/bin/env python3
"""How much trackable detail does a camera keep while walking a room? (rk 11 Nov: would the ultrawide track plain walls
better than ARCore's main camera?)

Per frame: corners (Shi-Tomasi) tracked with pyramidal KLT, kept only when tracking back lands within 1 px of the start
(forward-backward check); new corners added where there are none. Reports tracked features per frame, the share of
frames under 10 / 20 / 40, track lengths, the longest stretch under 10, and a blur estimate (variance of the Laplacian).

  python3 track_bench.py <video> [--crop-fov FROM TO] [--log scan-log.txt --rec-start S] [--csv out.csv]

--crop-fov 104 74: keep the middle of the picture that a 74 deg lens would see (pinhole; lens distortion ignored), so
the ultrawide can be compared with the main camera on the same walk.
--log/--rec-start: mark the scan's tracking losses ("tracking lost mid-scan" times, minus the recording's start time in
the same clock) to check that low counts line up with them.
Needs opencv-python-headless and numpy (a venv is fine). Recordings show a real home: keep them on the Mac.
"""
import sys, math, argparse, re
import cv2, numpy as np

ap = argparse.ArgumentParser()
ap.add_argument('video'); ap.add_argument('--crop-fov', nargs=2, type=float); ap.add_argument('--width', type=int, default=640)
ap.add_argument('--fast', type=int, default=25); ap.add_argument('--log'); ap.add_argument('--rec-start', type=float, default=0); ap.add_argument('--csv'); ap.add_argument('--max-s', type=float, default=1e9)
a = ap.parse_args()

cap = cv2.VideoCapture(a.video)
fps = cap.get(cv2.CAP_PROP_FPS) or 30
prev = None; pts = None; ages = None; done_lengths = []; rows = []
lk = dict(winSize=(21, 21), maxLevel=3, criteria=(cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 20, 0.03), minEigThreshold=2e-3)
fast = cv2.FastFeatureDetector_create(threshold=a.fast, nonmaxSuppression=True)
i = 0
while True:
    ok, fr = cap.read()
    if not ok or i / fps > a.max_s: break
    g = cv2.cvtColor(fr, cv2.COLOR_BGR2GRAY)
    h, w = g.shape
    if h > w: g = cv2.rotate(g, cv2.ROTATE_90_CLOCKWISE); h, w = g.shape   # landscape, so --width is the long side
    if a.crop_fov:
        f = math.tan(math.radians(a.crop_fov[1] / 2)) / math.tan(math.radians(a.crop_fov[0] / 2))
        cw, ch = int(w * f), int(h * f); g = g[(h - ch) // 2:(h + ch) // 2, (w - cw) // 2:(w + cw) // 2]
    s = a.width / g.shape[1]; g = cv2.resize(g, (a.width, int(g.shape[0] * s)), interpolation=cv2.INTER_AREA)
    blur = cv2.Laplacian(g, cv2.CV_64F).var()
    tracked = 0
    if prev is not None and pts is not None and len(pts):
        p1, st, _ = cv2.calcOpticalFlowPyrLK(prev, g, pts, None, **lk)
        p0r, st2, _ = cv2.calcOpticalFlowPyrLK(g, prev, p1, None, **lk)
        fb = np.linalg.norm((pts - p0r).reshape(-1, 2), axis=1)
        good = (st.ravel() == 1) & (st2.ravel() == 1) & (fb < 1.0)
        for L in ages[~good]: done_lengths.append(L)
        pts = p1[good].reshape(-1, 1, 2); ages = ages[good] + 1; tracked = int(good.sum())
    # top up: new corners away from the tracked ones
    mask = np.full(g.shape, 255, np.uint8)
    if pts is not None:
        for p in pts.reshape(-1, 2): cv2.circle(mask, (int(p[0]), int(p[1])), 10, 0, -1)
    want = 300 - (0 if pts is None else len(pts))
    if want > 20:
        # absolute contrast (FAST): a plain wall's sensor noise doesn't count as detail, whatever the frame's best corner
        kp = sorted(fast.detect(g, mask), key=lambda k: -k.response)
        sel = []
        for k in kp:
            if len(sel) >= want: break
            x, y = k.pt
            if mask[int(y), int(x)] == 0: continue
            sel.append((x, y)); cv2.circle(mask, (int(x), int(y)), 10, 0, -1)
        new = np.array(sel, np.float32).reshape(-1, 1, 2) if sel else None
        if new is not None:
            pts = new.astype(np.float32) if pts is None or not len(pts) else np.vstack([pts, new.astype(np.float32)])
            ages = np.concatenate([np.zeros(0, int) if ages is None else ages, np.ones(len(new), int)])
    rows.append((i / fps, tracked, blur)); prev = g; i += 1
done_lengths += list(ages if ages is not None else [])

t = np.array([r[0] for r in rows]); n = np.array([r[1] for r in rows]); b = np.array([r[2] for r in rows])
n1 = n[1:] if len(n) > 1 else n
def longest(th):
    best = cur = 0
    for v in n1:
        cur = cur + 1 if v < th else 0; best = max(best, cur)
    return best / fps
print(f"{a.video}{' (cropped to %.0f deg)' % a.crop_fov[1] if a.crop_fov else ''}: {len(rows)} frames, {len(rows)/fps:.0f} s")
print(f"  tracked features per frame: median {np.median(n1):.0f}, 10th percentile {np.percentile(n1,10):.0f}")
for th in (10, 20, 40): print(f"  frames under {th}: {100*np.mean(n1 < th):.1f}%")
print(f"  longest stretch under 10: {longest(10):.1f} s; under 20: {longest(20):.1f} s")
print(f"  track length: median {np.median(done_lengths)/fps:.2f} s, 90th percentile {np.percentile(done_lengths,90)/fps:.1f} s")
print(f"  blur (variance of Laplacian, higher = sharper): median {np.median(b):.0f}, 10th percentile {np.percentile(b,10):.0f}")
if a.log:
    losses = [float(m.group(1)) for m in re.finditer(r"^\s*([\d.]+)s\s+tracking lost mid-scan", open(a.log, errors='ignore').read(), re.M)]
    for L in losses:
        x = L - a.rec_start
        win = (t >= x - 10) & (t <= x)
        if win.any():
            ser = ' '.join(f"{int(v)}" for v in n[win][::int(fps)])
            print(f"  loss at {L:.1f} s (video {x:.1f} s): tracked features in the 10 s before, once a second: {ser}")
if a.csv:
    with open(a.csv, 'w') as f:
        f.write('t_s,tracked,blur\n'); [f.write(f"{r[0]:.3f},{r[1]},{r[2]:.1f}\n") for r in rows]
