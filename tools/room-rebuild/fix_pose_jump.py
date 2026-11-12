#!/usr/bin/env python3
"""Repairs an iPhone recording whose world frame jumped mid-scan (12 Nov 2026, 22:37 scan: when RoomPlan hit its size
limit the app re-ran ARKit, and every later pose came out about 4 m and 70 degrees off, drawing a second rotated copy
of the room). Works on a copy; the scan itself is never changed.

  python3 fix_pose_jump.py <Scan>.capture <out dir>

1. finds the jump: consecutive frames more than 1 m or 30 degrees apart in under 1.5 s (a camera can't do that);
2. first guess: the pose before the jump times the inverse of the pose after (as yaw + translation: ARKit's world is
   gravity-aligned, so only the heading and position can change);
3. refines it by matching the two passes' LiDAR points (ICP: heading + 3D shift, nearest neighbours within 15 cm);
4. writes <out>/<Scan>.capture (recording/frames.bin corrected, video and depth linked, points.bin rebuilt from the
   corrected depth with colours from the video), <out>/pose-jump.json, and top views before.png / after.png.
"""
import sys, os, json, struct, ctypes, shutil, numpy as np, cv2
from scipy.spatial import cKDTree

cap_dir, out = sys.argv[1], sys.argv[2]
rec = os.path.join(cap_dir, 'recording')
name = os.path.basename(os.path.normpath(cap_dir))

fb = open(os.path.join(rec, 'frames.bin'), 'rb').read(); n = len(fb) // 120
T, P, K, WH, TRK = [], [], [], [], []
for i in range(n):
    b = fb[i * 120:(i + 1) * 120]
    T.append(struct.unpack('<d', b[:8])[0])
    P.append(np.array(struct.unpack('<16f', b[8:72])).reshape(4, 4).T)
    K.append(np.array(struct.unpack('<9f', b[72:108])).reshape(3, 3).T)
    w, h, trk = struct.unpack('<3i', b[108:120]); WH.append((w, h)); TRK.append(trk)
T = np.array(T); t0 = T[0]

# 1. the jump
def rot_angle(a, b):
    R = a[:3, :3].T @ b[:3, :3]
    return np.degrees(np.arccos(np.clip((np.trace(R) - 1) / 2, -1, 1)))
# the app's rule (PoseJump.isJump): more than a walking camera can do in the time between the two frames
jumps = [i for i in range(1, n) if T[i] - T[i - 1] < 1.5 and
         (np.linalg.norm(P[i][:3, 3] - P[i - 1][:3, 3]) > 0.5 + 1.0 * (T[i] - T[i - 1])
          or rot_angle(P[i - 1], P[i]) > 25 + 120 * (T[i] - T[i - 1]))]
if not jumps: sys.exit('no pose jump found')
j = jumps[0]
if len(jumps) > 1: print(f'note: {len(jumps)} jumps, repairing the first (t={T[j] - t0:.1f} s); others: {[round(T[k] - t0, 1) for k in jumps[1:]]}')
a = max(k for k in range(j) if TRK[k] == 2)                      # last normal-tracking frame before
b = min(k for k in range(j, n) if TRK[k] == 2)                   # first normal-tracking frame after
print(f'jump at frame {j} (t={T[j] - t0:.2f} s): {np.linalg.norm(P[j][:3, 3] - P[j - 1][:3, 3]):.2f} m, '
      f'{rot_angle(P[j - 1], P[j]):.1f} deg; using frames {a} and {b}')

def yaw_shift(M):
    """Keep only the heading (rotation about the gravity axis y) and the shift of a rigid transform."""
    yaw = np.arctan2(M[0, 2], M[0, 0])
    c, s = np.cos(yaw), np.sin(yaw)
    out = np.eye(4); out[:3, :3] = [[c, 0, s], [0, 1, 0], [-s, 0, c]]; out[:3, 3] = M[:3, 3]
    return out, np.degrees(yaw)
C, yaw0 = yaw_shift(P[a] @ np.linalg.inv(P[b]))
print(f'first guess: heading {yaw0:.1f} deg, shift {np.round(C[:3, 3], 2)}')

# depth frames
lib = ctypes.CDLL('/usr/lib/libcompression.dylib'); lib.compression_decode_buffer.restype = ctypes.c_size_t
def unlzfse(data, cap):
    dst = ctypes.create_string_buffer(cap)
    k = lib.compression_decode_buffer(dst, cap, data, len(data), None, 0x801)   # decode first, then read the buffer
    return dst.raw[:k]
db = open(os.path.join(rec, 'depth.bin'), 'rb').read(); p = 0; D = {}
while p + 12 <= len(db):
    t = struct.unpack('<d', db[p:p + 8])[0]; sz = struct.unpack('<I', db[p + 8:p + 12])[0]; D[round(t, 4)] = (p + 12, sz); p += 12 + sz

def depth_points(i, step=3):
    dd = D.get(round(float(T[i]), 4))
    if dd is None: return None, None
    raw = unlzfse(db[dd[0]:dd[0] + dd[1]], 4 + 256 * 256 * 3 * 4)
    dw, dh = struct.unpack('<HH', raw[:4])
    depth = np.frombuffer(raw[4:4 + dw * dh * 2], '<u2').reshape(dh, dw).astype(np.float32) / 1000
    conf = np.frombuffer(raw[4 + dw * dh * 2:4 + dw * dh * 3], np.uint8).reshape(dh, dw)
    v, u = np.mgrid[0:dh:step, 0:dw:step]
    z = depth[v, u]; ok = (conf[v, u] == 2) & (z > 0.2) & (z < 5)
    s = dw / WH[i][0]; fx, fy, cx, cy = K[i][0, 0] * s, K[i][1, 1] * s, K[i][0, 2] * s, K[i][1, 2] * s
    u, v, z = u[ok], v[ok], z[ok]
    cam = np.stack([(u - cx) * z / fx, -(v - cy) * z / fy, -z, np.ones_like(z)], 1)     # ARKit camera: y up, -z ahead
    return (P[i] @ cam.T).T[:, :3], (u / s, v / s)

def cloud(frames, M=np.eye(4), step=3):
    pts = [depth_points(i, step)[0] for i in frames]
    pts = np.concatenate([q for q in pts if q is not None])
    return (M[:3, :3] @ pts.T).T + M[:3, 3]

def voxel(pts, size=0.03):
    _, keep = np.unique(np.floor(pts / size).astype(np.int64), axis=0, return_index=True)
    return pts[keep]

ok_frames = [i for i in range(n) if TRK[i] == 2]
first = voxel(cloud([i for i in ok_frames if i < j][::2]))
second_raw = voxel(cloud([i for i in ok_frames if i >= b][::2]))
print(f'points: first pass {len(first)}, second pass {len(second_raw)} (3 cm cells)')

# 3. ICP, heading + shift
tree = cKDTree(first)
M = C.copy()
for it, radius in enumerate([0.5] * 5 + [0.25] * 5 + [0.15] * 10):
    moved = (M[:3, :3] @ second_raw.T).T + M[:3, 3]
    d, k = tree.query(moved, distance_upper_bound=radius)
    m = np.isfinite(d)
    if m.sum() < 500: break
    src, dst = moved[m], first[k[m]]
    sc, dc = src.mean(0), dst.mean(0)
    A = src[:, [0, 2]] - sc[[0, 2]]; B = dst[:, [0, 2]] - dc[[0, 2]]
    H = A.T @ B; ang = np.arctan2(H[0, 1] - H[1, 0], H[0, 0] + H[1, 1])            # best 2D rotation (x, z)
    c, s = np.cos(ang), np.sin(ang)
    R = np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
    step = np.eye(4); step[:3, :3] = R; step[:3, 3] = dc - R @ sc
    M = step @ M
    if it % 5 == 4: print(f'  icp {it + 1}: {m.sum()} pairs within {radius} m, mean {d[m].mean() * 100:.1f} cm')
# 3b. fine heading search around the ICP result: for each heading (±8 deg, 0.25 deg steps) shift-only ICP, and keep
# the one where most second-pass points land within 5 cm of the first pass (ICP alone stopped a few degrees off on the
# 22:37 scan: the two passes overlap only in part)
def shift_only(Mr, iters=8):
    Mr = Mr.copy()
    for radius in [0.3] * 3 + [0.12] * 3 + [0.06] * (iters - 6):
        moved = (Mr[:3, :3] @ sample.T).T + Mr[:3, 3]
        d, k = tree.query(moved, distance_upper_bound=radius); m = np.isfinite(d)
        if m.sum() < 300: return Mr, 0
        Mr[:3, 3] += (first[k[m]] - moved[m]).mean(0)
    moved = (Mr[:3, :3] @ sample.T).T + Mr[:3, 3]
    d, _ = tree.query(moved, distance_upper_bound=0.05)
    return Mr, np.isfinite(d).mean()
rng = np.random.default_rng(1); sample = second_raw[rng.choice(len(second_raw), min(40000, len(second_raw)), replace=False)]
best = (shift_only(M), 0.0)
base_yaw = np.arctan2(M[0, 2], M[0, 0])
for dd in np.radians(np.arange(-8, 8.01, 0.25)):
    yv = base_yaw + dd; c, s = np.cos(yv), np.sin(yv)
    Mt = M.copy(); Mt[:3, :3] = [[c, 0, s], [0, 1, 0], [-s, 0, c]]
    # keep the second pass's centre where it was when turning
    cen = (M[:3, :3] @ sample.mean(0)) + M[:3, 3]; Mt[:3, 3] = cen - Mt[:3, :3] @ sample.mean(0)
    res = shift_only(Mt)
    if res[1] > best[0][1]: best = (res, np.degrees(dd))
(M, share), turned = best
print(f'  heading search: {turned:+.2f} deg from ICP; {share * 100:.1f}% of second-pass points within 5 cm of the first')
yaw = np.degrees(np.arctan2(M[0, 2], M[0, 0]))
print(f'correction: heading {yaw:.2f} deg, shift {np.round(M[:3, 3], 3)} (first guess {yaw0:.1f} deg)')

# 4. the corrected copy
oc = os.path.join(out, name); orec = os.path.join(oc, 'recording')
os.makedirs(orec, exist_ok=True)
for f in os.listdir(rec):
    if f != 'frames.bin':
        dst = os.path.join(orec, f)
        if not os.path.exists(dst): os.symlink(os.path.abspath(os.path.join(rec, f)), dst)
fixed = bytearray(fb)
for i in range(b if b > j else j, n):
    Q = M @ P[i]
    fixed[i * 120 + 8:i * 120 + 72] = struct.pack('<16f', *Q.T.reshape(-1))
# frames between the jump and the first normal one: limited tracking, left out of picking (tracking stays 1)
for i in range(j, b):
    Q = M @ P[i]; fixed[i * 120 + 8:i * 120 + 72] = struct.pack('<16f', *Q.T.reshape(-1))
open(os.path.join(orec, 'frames.bin'), 'wb').write(fixed)
for f in ['meta.json', 'keyframes.json']:
    if os.path.exists(os.path.join(cap_dir, f)): shutil.copy(os.path.join(cap_dir, f), oc)
json.dump(dict(scan=name, jump_frame=j, jump_time_s=round(T[j] - t0, 2), before_frame=a, after_frame=b,
               heading_deg=round(yaw, 3), shift_m=[round(x, 4) for x in M[:3, 3]], first_guess_heading_deg=round(yaw0, 2),
               matrix=M.round(6).tolist()), open(os.path.join(out, 'pose-jump.json'), 'w'), indent=1)

# points.bin from the corrected depth, coloured from the video (x y z f32 + r g b a; 16 bytes)
P2 = [M @ P[i] if i >= j else P[i] for i in range(n)]
picks = set(ok_frames[::3])
capv = cv2.VideoCapture(os.path.join(rec, 'video.mov')); fi = 0; pts, cols = [], []
while True:
    okv, img = capv.read()
    if not okv: break
    tv = capv.get(cv2.CAP_PROP_POS_MSEC) / 1000.0
    while fi < n - 1 and abs((T[fi + 1] - t0) - tv) < abs((T[fi] - t0) - tv): fi += 1
    if abs((T[fi] - t0) - tv) > 0.03 or fi not in picks: continue
    keep = P[fi]; P[fi] = P2[fi]
    q, uv = depth_points(fi, step=2); P[fi] = keep
    if q is None: continue
    u = np.clip(uv[0].astype(int), 0, img.shape[1] - 1); v = np.clip(uv[1].astype(int), 0, img.shape[0] - 1)
    pts.append(q); cols.append(img[v, u][:, ::-1])
pts = np.concatenate(pts); cols = np.concatenate(cols)
_, keep = np.unique(np.floor(pts / 0.02).astype(np.int64), axis=0, return_index=True)
pts, cols = pts[keep], cols[keep]
rec_dt = np.dtype([('p', '<f4', 3), ('c', 'u1', 4)])
arr = np.zeros(len(pts), rec_dt); arr['p'] = pts; arr['c'][:, :3] = cols; arr['c'][:, 3] = 255
arr.tofile(os.path.join(oc, 'points.bin'))
print(f'points.bin: {len(pts)} points (2 cm cells, corrected)')

# top views: the scan as recorded vs repaired (2 cm pixels, colour of the highest point below 2 m)
def top(points, path, title):
    lo = points[:, [0, 2]].min(0); hi = points[:, [0, 2]].max(0)
    W, H = (((hi - lo) / 0.02).astype(int) + 1).tolist()
    img = np.full((H, W, 3), 30, np.uint8)
    xi = ((points[:, 0] - lo[0]) / 0.02).astype(int); zi = ((points[:, 2] - lo[1]) / 0.02).astype(int)
    shade = np.clip((points[:, 1] - points[:, 1].min()) / 2.5 * 200 + 55, 55, 255).astype(np.uint8)
    img[zi, xi] = np.stack([shade] * 3, 1)
    cv2.putText(img, title, (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (80, 200, 255), 2)
    cv2.imwrite(path, img)
before = np.concatenate([first, second_raw]); after = np.concatenate([first, (M[:3, :3] @ second_raw.T).T + M[:3, 3]])
top(before, os.path.join(out, 'before.png'), 'as recorded (second pass shifted)')
top(after, os.path.join(out, 'after.png'), 'repaired')
print(f'wrote {oc}, pose-jump.json, before.png, after.png')
