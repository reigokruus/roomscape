#!/usr/bin/env python3
"""iPhone scan recording -> Nerfstudio dataset for Brush / gsplat (the same rules as the app's SplatDataset.swift).

  python3 ios_to_nerfstudio.py <scan>.capture <out dir> [--max-frames 300] [--scale 0.5] [--max-points 400000]

Reads <capture>/recording (video.mov 1920x1440, frames.bin; see RawRecorder.swift) front to back once, keeps the
sharpest frame (mean absolute Laplacian of the luma) in each stretch of len/max-frames frames with tracking normal,
writes it scaled to images/NNNNN.jpg, and writes transforms.json (ARKit pose = Nerfstudio/OpenGL camera, rows; lens
scaled to the image) and points.ply (the scan's dots, <capture>/points.bin, at most --max-points).
Preview:  --max-frames 300 --scale 0.5     Full:  --max-frames 1000 --scale 1.0   (the video's own size)
Check:    tools/room-rebuild/check_nerfstudio.py (dots projected into the pictures should agree, ~0.6).
"""
import sys, os, json, struct, math, numpy as np, cv2

args = sys.argv[1:]
def opt(n, d):
    if n in args: i = args.index(n); v = args[i + 1]; del args[i:i + 2]; return v
    return d
maxf = int(opt('--max-frames', 300)); scale = float(opt('--scale', 0.5)); maxp = int(opt('--max-points', 400000))
cap_dir, out = args
rec = os.path.join(cap_dir, 'recording')
os.makedirs(os.path.join(out, 'images'), exist_ok=True)

fb = open(os.path.join(rec, 'frames.bin'), 'rb').read(); n = len(fb) // 120
F = []
for i in range(n):
    b = fb[i * 120:(i + 1) * 120]
    t = struct.unpack('<d', b[:8])[0]; pose = list(struct.unpack('<16f', b[8:72])); k = struct.unpack('<9f', b[72:108])
    w, h, trk = struct.unpack('<3i', b[108:120])
    F.append(dict(t=t, pose=pose, fx=k[0], fy=k[4], cx=k[6], cy=k[7], w=w, h=h, trk=trk))
t0 = F[0]['t']; window = max(1, math.ceil(n / maxf))

def sharp(gray):
    g = gray[4:-4:4, 4:-4:4].astype(np.int32)   # every 4th pixel, like the app
    return float(np.abs(4 * g[1:-1, 1:-1] - g[:-2, 1:-1] - g[2:, 1:-1] - g[1:-1, :-2] - g[1:-1, 2:]).mean())

cap = cv2.VideoCapture(os.path.join(rec, 'video.mov'))
fi = 0; cur = -1; best = None; frames = []
def flush():
    global best
    if best is None: return
    s, i, img = best; f = F[i]
    W, H = int(round(f['w'] * scale)), int(round(f['h'] * scale))
    name = f"images/{i:05d}.jpg"
    cv2.imwrite(os.path.join(out, name), cv2.resize(img, (W, H), interpolation=cv2.INTER_AREA), [cv2.IMWRITE_JPEG_QUALITY, 92])
    sx, sy = W / f['w'], H / f['h']; m = f['pose']
    frames.append({"file_path": name, "transform_matrix": [[m[c * 4 + r] for c in range(4)] for r in range(4)],
                   "fl_x": f['fx'] * sx, "fl_y": f['fy'] * sy, "cx": f['cx'] * sx, "cy": f['cy'] * sy, "w": W, "h": H})
    best = None
while True:
    ok, img = cap.read()
    if not ok: break
    t = t0 + cap.get(cv2.CAP_PROP_POS_MSEC) / 1000
    while fi + 1 < n and F[fi + 1]['t'] <= t + 0.001: fi += 1
    if fi // window != cur: flush(); cur = fi // window
    if F[fi]['trk'] != 2: continue
    s = sharp(cv2.cvtColor(img, cv2.COLOR_BGR2GRAY))
    if best is None or s > best[0]: best = (s, fi, img)
flush()

pb = open(os.path.join(cap_dir, 'points.bin'), 'rb').read(); m = len(pb) // 16
A = np.frombuffer(pb[:m * 16], dtype=[('p', '<f4', 3), ('c', 'u1', 4)])[::max(1, math.ceil(m / maxp))]
ply = np.zeros(len(A), dtype=[('p', '<f4', 3), ('c', 'u1', 3)]); ply['p'] = A['p']; ply['c'] = A['c'][:, :3]
with open(os.path.join(out, 'points.ply'), 'wb') as o:
    o.write(f"ply\nformat binary_little_endian 1.0\nelement vertex {len(ply)}\nproperty float x\nproperty float y\nproperty float z\n"
            "property uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n".encode()); o.write(ply.tobytes())
json.dump({"camera_model": "PERSPECTIVE", "ply_file_path": "points.ply", "frames": frames}, open(os.path.join(out, 'transforms.json'), 'w'), indent=1)
print(f"{len(frames)} pictures of {n} frames at {scale:g}x, {len(ply)} starting points -> {out}")
