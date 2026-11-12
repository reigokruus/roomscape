#!/usr/bin/env python3
"""Pictures for the rebuild from a scan's ARCore recording.mp4, for scans made before rebuild-frames/ (263160917).

  python3 match_video.py <scan folder> <out dir> [--until SECONDS] [--every SECONDS]

The video has no poses of its own; pose-track.bin has ARCore's camera pose about 7 times a second, on the scan log's
clock. The video's start on that clock and the lens (focal length, the 640x480 picture) are found by trying offsets and
focal lengths and keeping the pair where the scan's own coloured dots, projected into the pictures, best agree with
the pictures (brightness correlation, nearest dot per pixel). Writes NNNNN.jpg + frames.csv in rebuild-frames layout
(no depth files), and match.txt with how good the match is.
"""
import sys, os, struct, math, numpy as np, cv2

scan, out = sys.argv[1], sys.argv[2]; os.makedirs(out, exist_ok=True)
until = float(sys.argv[sys.argv.index('--until') + 1]) if '--until' in sys.argv else 1e9
every = float(sys.argv[sys.argv.index('--every') + 1]) if '--every' in sys.argv else 0.5

b = open(os.path.join(scan, 'pose-track.bin'), 'rb').read(); n = struct.unpack('>i', b[:4])[0]
TR = np.frombuffer(b[4:4 + n * 52], '>f4').reshape(n, 13).astype(np.float64)
TR = TR[TR[:, 1] > 0]                      # tracking samples only
tt = TR[:, 0] / 1000.0

def pose_at(t):
    """camera -> world 4x4 at log time t (s): position linear, rotation slerp; None outside the track or in a gap"""
    i = np.searchsorted(tt, t)
    if i <= 0 or i >= len(tt) or tt[i] - tt[i - 1] > 0.35: return None
    a = (t - tt[i - 1]) / (tt[i] - tt[i - 1]); p = TR[i - 1, 3:6] * (1 - a) + TR[i, 3:6] * a
    q0, q1 = TR[i - 1, 6:10], TR[i, 6:10].copy()
    if np.dot(q0, q1) < 0: q1 = -q1
    q = q0 * (1 - a) + q1 * a; q /= np.linalg.norm(q); x, y, z, w = q
    R = np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                  [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                  [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])
    M = np.eye(4); M[:3, :3] = R; M[:3, 3] = p; return M

pb = open(os.path.join(scan, 'points.bin'), 'rb').read(); pn = struct.unpack('>i', pb[:4])[0]
dt = np.dtype([('x', '>f4'), ('y', '>f4'), ('z', '>f4'), ('r', 'u1'), ('g', 'u1'), ('b', 'u1')])
A = np.frombuffer(pb[4:4 + pn * 15], dt)
P = np.stack([A['x'], A['y'], A['z']], 1).astype(np.float64); G = (0.299 * A['r'] + 0.587 * A['g'] + 0.114 * A['b']).astype(np.float64)
sub = np.random.default_rng(1).choice(len(P), min(len(P), 250000), replace=False); Ps, Gs = P[sub], G[sub]

cap = cv2.VideoCapture(os.path.join(scan, 'recording.mp4'))
W, H = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)), int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT)); fps = cap.get(cv2.CAP_PROP_FPS)
frames = []                                  # (video seconds, BGR image)
k = 0
while True:
    ok, img = cap.read()
    if not ok: break
    tv = cap.get(cv2.CAP_PROP_POS_MSEC) / 1000.0
    if not frames or tv - frames[-1][0] >= every - 1e-3: frames.append((tv, img))
    k += 1
print(f"video {W}x{H}, {k} frames, {fps:.2f} fps; kept {len(frames)} every {every} s")

def agree(img, M, f, step=4):
    R = M[:3, :3]; c = (Ps - M[:3, 3]) @ R; z = -c[:, 2]; ok = (z > 0.25) & (z < 4)
    u = f * c[:, 0] / np.maximum(z, 1e-3) + W / 2; v = H / 2 - f * c[:, 1] / np.maximum(z, 1e-3)
    ok &= (u >= 0) & (u < W) & (v >= 0) & (v < H)
    if ok.sum() < 500: return None
    u, v, z, g = u[ok], v[ok], z[ok], Gs[ok]
    cell = (v // step).astype(int) * (W // step + 1) + (u // step).astype(int)
    o = np.lexsort((z, cell)); first = np.r_[True, cell[o][1:] != cell[o][:-1]]; s = o[first]   # nearest dot per cell
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY).astype(np.float64)
    gi = gray[v[s].astype(int), u[s].astype(int)]
    if len(s) < 300: return None
    return np.corrcoef(gi, g[s])[0, 1]

# calibration: frames spread over the good part of the scan
cal = [fr for fr in frames if fr[0] > 20 and fr[0] < min(until, 1e9) - 15][::max(1, len(frames) // 30)][:24]
best = None
for f in range(420, 600, 20):
    for off in np.arange(8.0, 13.01, 0.25):
        sc = [agree(img, M, f) for tv, img in cal if (M := pose_at(tv + off)) is not None]; sc = [s for s in sc if s is not None]
        if len(sc) >= len(cal) // 2 and (best is None or np.mean(sc) > best[0]): best = (np.mean(sc), off, f)
print("coarse:", best)
_, off0, f0 = best
for f in np.arange(f0 - 20, f0 + 21, 4):
    for off in np.arange(off0 - 0.3, off0 + 0.301, 0.033):
        sc = [agree(img, M, f) for tv, img in cal if (M := pose_at(tv + off)) is not None]; sc = [s for s in sc if s is not None]
        if len(sc) >= len(cal) // 2 and np.mean(sc) > best[0]: best = (np.mean(sc), off, f)
score, off, f = best
# how good: per-frame agreement at the best match vs a wrong time (2 s off) as the baseline
per = [agree(img, M, f) for tv, img in frames if (M := pose_at(tv + off)) is not None]; per = np.array([s for s in per if s is not None])
base = [agree(img, M, f) for tv, img in cal if (M := pose_at(tv + off + 2.0)) is not None]; base = np.mean([s for s in base if s is not None])
msg = (f"video start on the log clock: {off:.2f} s; focal length {f:.0f} px for {W}x{H} (centre assumed); "
       f"agreement (brightness correlation, dots vs picture): {score:.2f} on the calibration frames, median {np.median(per):.2f} over all, "
       f"10th percentile {np.percentile(per, 10):.2f}; with the time 2 s off: {base:.2f}")
print(msg); open(os.path.join(out, 'match.txt'), 'w').write(msg + "\n")

with open(os.path.join(out, 'frames.csv'), 'w') as csv:
    i = 0
    for tv, img in frames:
        t = tv + off
        if t > until: break
        M = pose_at(t)
        if M is None: continue
        cv2.imwrite(os.path.join(out, f"{i:05d}.jpg"), img, [cv2.IMWRITE_JPEG_QUALITY, 90])
        cm = M.T.reshape(-1)        # column-major, as the app writes it
        csv.write(",".join([str(i), str(int(t * 1000))] + [f"{x:.6f}" for x in cm] + [f"{f:.2f}", f"{f:.2f}", f"{W / 2}", f"{H / 2}", str(W), str(H)] + ["0"] * 6) + "\n")
        i += 1
print(f"{i} pictures with poses written to {out}")
