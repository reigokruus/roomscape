#!/usr/bin/env python3
"""S24 (ARCore) replay scan + its full recording.mp4 -> all-frames.csv, the layout planes_texture.py and
atlas_texture.py read (the same columns ios_frames.py writes for the iPhone).

  python3 s24_frames.py <replay scan dir> <recording.mp4> <out all-frames.csv> --offset 4.46 [--until 371.5]

The replay scan's rebuild-frames are only ~2 a second; the video has all ~30. Each video frame gets its pose from
the replay's pose-track.bin (~7 Hz; position linear, rotation slerp; no pose across gaps over 0.35 s) at
replay time = video time + --offset. Frames after --until (replay clock) are left out (rk: nothing after 379 s on
the original scan's clock = 371.5 s replay). Intrinsics: the rebuild-frames' (the video is the same 640x480 picture).

Columns: video frame index, video time s, replay time s, tracking (2 = good), 16 floats camera->world column-major
(ARCore: x right, y up, looking down -z), fx fy cx cy w h. The video is only grabbed (frame times), never decoded whole.
"""
import sys, os, struct, numpy as np, cv2

args = sys.argv[1:]
def opt(name, default):
    if name in args: i = args.index(name); v = args[i + 1]; del args[i:i + 2]; return v
    return default
offset = float(opt('--offset', 4.46)); until = float(opt('--until', 371.5))
scan, video, out = args

r0 = open(os.path.join(scan, 'rebuild-frames', 'frames.csv')).readline().strip().split(',')
fx, fy, cx, cy, w, h = r0[18:24]

b = open(os.path.join(scan, 'pose-track.bin'), 'rb').read(); k = struct.unpack('>i', b[:4])[0]
TR = np.frombuffer(b[4:4 + k * 52], '>f4').reshape(k, 13).astype(np.float64)
TR = TR[(TR[:, 1] > 0) & (TR[:, 2] == 0)]          # tracking, not lining up
tt = TR[:, 0] / 1000

def qmat(q):
    x, y, z, w_ = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w_), 2 * (x * z + y * w_)],
                     [2 * (x * y + z * w_), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w_)],
                     [2 * (x * z - y * w_), 2 * (y * z + x * w_), 1 - 2 * (x * x + y * y)]])

def slerp(q0, q1, a):
    if q0 @ q1 < 0: q1 = -q1
    d = min(1.0, float(q0 @ q1)); th = np.arccos(d)
    if th < 1e-6: q = q0 * (1 - a) + q1 * a
    else: q = (np.sin((1 - a) * th) * q0 + np.sin(a * th) * q1) / np.sin(th)
    return q / np.linalg.norm(q)

def pose_at(t):
    i = np.searchsorted(tt, t)
    if i <= 0 or i >= len(tt) or tt[i] - tt[i - 1] > 0.35: return None
    a = (t - tt[i - 1]) / (tt[i] - tt[i - 1])
    P = np.eye(4); P[:3, :3] = qmat(slerp(TR[i - 1, 6:10], TR[i, 6:10], a)); P[:3, 3] = TR[i - 1, 3:6] * (1 - a) + TR[i, 3:6] * a
    return P

# check against the rebuild frames (same ARCore poses)
errs = []
for l in open(os.path.join(scan, 'rebuild-frames', 'frames.csv')):
    r = l.strip().split(','); P = pose_at(int(r[1]) / 1000)
    if P is not None: errs.append(np.linalg.norm(P[:3, 3] - np.array([float(x) for x in r[14:17]])))
print(f"pose-track vs rebuild frames: median {np.median(errs) * 100:.2f} cm, 95% {np.percentile(errs, 95) * 100:.2f} cm over {len(errs)} frames")

cap = cv2.VideoCapture(video); vi = 0; n = 0; f = open(out, 'w')
while cap.grab():
    tv = cap.get(cv2.CAP_PROP_POS_MSEC) / 1000.0; tr = tv + offset
    if tr > until: break
    P = pose_at(tr)
    if P is not None:
        f.write(",".join([str(vi), f"{tv:.4f}", f"{tr:.4f}", "2"] + [f"{x:.6f}" for x in P.T.reshape(-1)] + [fx, fy, cx, cy, w, h]) + "\n"); n += 1
    vi += 1
print(f"{vi} video frames up to {until} s replay, {n} posed, written to {out}")
