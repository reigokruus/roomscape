#!/usr/bin/env python3
"""A sharp texture for one flat wall of a rebuilt room (rk 12 Nov 09:51: the papers on the wall by the ironing board
came out as a blur; "paint this area in with the material from the video, from whichever best angle you have").

  python3 wall_texture.py <replay scan> <recording.mp4> <rebuild dir> --frame N --offset S [--from T0 --to T1]
                          [--rect s0,s1,y0,y1] [--mm 2]

Why the model is blurred there: the rebuild paints colour per mesh vertex (one colour every ~1.5-2 cm after
simplifying), blended from the best few views, and those views disagree by the pose error (2-5 cm). Pencil lines on
paper can't survive that. Here instead:
  - the wall plane is fitted to the model where rebuild frame N (one that sees the wall) looks;
  - every video frame in the time window (recording.mp4 has all ~30 a second; replay clock = video clock + offset) gets
    its pose by interpolating the replay's pose-track, and is scored on how sharp, close and head-on its view of the
    wall is and how much of the wall it sees unblocked;
  - the best frame is refined: its pose is nudged (small image shift, found by matching it to the second best frame's
    view of the same wall) only if that improves the agreement; then projected onto a rectangle on the plane at --mm
    per texel, straight from the frame's pixels (no averaging across views). Texels the best frame can't see (hidden
    behind something in front, outside the picture) are filled from the next best frames, each frame alone, never mixed.
  - writes wall-NN.png (RGBA: transparent where no frame saw the wall), wall-NN.json (the rectangle's 3D corners, which
    frames were used), and before/after pictures.
"""
import sys, os, json, struct, math, numpy as np, cv2, open3d as o3d

args = sys.argv[1:]
def opt(name, default=None):
    if name in args: i = args.index(name); v = args[i + 1]; del args[i:i + 2]; return v
    return default
frameN = int(opt('--frame')); offset = float(opt('--offset')); t0 = opt('--from'); t1 = opt('--to')
rect = opt('--rect'); above = opt('--above'); mm = float(opt('--mm', 2)); name = opt('--name', 'wall-01')
scan, video, rdir = args
fr = os.path.join(scan, 'rebuild-frames')
rows = {int(l.split(',')[0]): l.strip().split(',') for l in open(os.path.join(fr, 'frames.csv'))}
def pose_of(r): return np.array([float(x) for x in r[2:18]]).reshape(4, 4).T
ii = [float(x) for x in rows[frameN][18:24]]; W, H = int(ii[4]), int(ii[5])
tN = int(rows[frameN][1]) / 1000.0
t0 = float(t0) if t0 else tN - 8; t1 = float(t1) if t1 else tN + 8

mesh = o3d.io.read_triangle_mesh(os.path.join(rdir, 'room.ply')); mesh.compute_vertex_normals()
V = np.asarray(mesh.vertices); Nv = np.asarray(mesh.vertex_normals)

def project(M, P):
    c = (P - M[:3, 3]) @ M[:3, :3]; z = -c[:, 2]
    u = ii[0] * c[:, 0] / np.maximum(z, 1e-3) + ii[2]; v = ii[3] - ii[1] * c[:, 1] / np.maximum(z, 1e-3)
    return u, v, z

# 1. the wall plane where frame N looks (nearest surface only, RANSAC)
M = pose_of(rows[frameN]); u, v, z = project(M, V)
box = (z > 0.2) & (u > W * 0.02) & (u < W * 0.6) & (v > H * 0.05) & (v < H * 0.55)
idx = np.nonzero(box)[0]; cell = (v[idx] // 8).astype(int) * 1000 + (u[idx] // 8).astype(int)
zmin = np.full(cell.max() + 1, np.inf); np.minimum.at(zmin, cell, z[idx]); vis = idx[z[idx] < zmin[cell] + 0.05]
pl, inl = o3d.geometry.PointCloud(o3d.utility.Vector3dVector(V[vis])).segment_plane(0.015, 3, 3000)
n = np.array(pl[:3]); d = pl[3] / np.linalg.norm(n); n /= np.linalg.norm(n)
if n @ (M[:3, 3]) + d < 0: n, d = -n, -d                     # normal towards the camera
c0 = V[vis][inl].mean(0); c0 -= (c0 @ n + d) * n
hdir = np.cross([0, 1.0, 0], n); hdir /= np.linalg.norm(hdir); vdir = np.cross(n, hdir)   # along the wall, up
# the wall's extent: model vertices on the plane near it
on = (np.abs(V @ n + d) < 0.025) & (np.linalg.norm(V - c0, axis=1) < 2.0) & (np.abs(Nv @ n) > 0.8)
s_on = (V[on] - c0) @ hdir; y_on = (V[on] - c0) @ vdir
if rect: s0, s1, y0, y1 = map(float, rect.split(','))
else: s0, s1 = np.percentile(s_on, [1, 99]); y0, y1 = np.percentile(y_on, [1, 99])
if above: y0 = max(y0, float(above) - c0[1])   # --above Y: only the wall above world height Y (e.g. above something standing in front)
print(f"wall plane: normal {np.round(n, 3)}, {on.sum()} model vertices on it; rectangle {s1 - s0:.2f} x {y1 - y0:.2f} m")
TW, TH = int((s1 - s0) * 1000 / mm), int((y1 - y0) * 1000 / mm)
gs, gy = np.meshgrid(s0 + (np.arange(TW) + 0.5) * mm / 1000, y1 - (np.arange(TH) + 0.5) * mm / 1000)
T3 = c0 + gs[..., None] * hdir + gy[..., None] * vdir                     # texel centres in 3D (TH, TW, 3)
corners = [c0 + s * hdir + y * vdir for s, y in ((s0, y0), (s1, y0), (s1, y1), (s0, y1))]

# 2. every video frame in the window, posed from the replay's pose-track
b = open(os.path.join(scan, 'pose-track.bin'), 'rb').read(); k = struct.unpack('>i', b[:4])[0]
TR = np.frombuffer(b[4:4 + k * 52], '>f4').reshape(k, 13).astype(np.float64); TR = TR[TR[:, 1] > 0]; tt = TR[:, 0] / 1000
def qmat(q):
    x, y, z, w = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)], [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)], [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])
def pose_at(t):
    i = np.searchsorted(tt, t)
    if i <= 0 or i >= len(tt) or tt[i] - tt[i - 1] > 0.35: return None
    a = (t - tt[i - 1]) / (tt[i] - tt[i - 1]); q0, q1 = TR[i - 1, 6:10], TR[i, 6:10].copy()
    if q0 @ q1 < 0: q1 = -q1
    q = q0 * (1 - a) + q1 * a; q /= np.linalg.norm(q)
    P = np.eye(4); P[:3, :3] = qmat(q); P[:3, 3] = TR[i - 1, 3:6] * (1 - a) + TR[i, 3:6] * a; return P
# the pose-track and the rebuild frames come from the same ARCore poses: check on frame N
Pn = pose_at(tN); print(f"pose-track vs rebuild frame {frameN}: {np.linalg.norm(Pn[:3, 3] - M[:3, 3]) * 100:.1f} cm")

RC = o3d.t.geometry.RaycastingScene(); RC.add_triangles(o3d.t.geometry.TriangleMesh.from_legacy(mesh))
T3f = T3.reshape(-1, 3)
# where the model really has this wall: a ray from 30 cm in front hits the model within 3 cm of the plane; small gaps
# inside the scanned wall (up to ~6 cm) are closed, the doorway and anything beyond the wall's edge stay out
_r = np.concatenate([T3f + n * 1.0, np.tile(-n, (len(T3f), 1))], 1).astype(np.float32)   # from 1 m out
_h = RC.cast_rays(o3d.core.Tensor(_r))['t_hit'].numpy()
wallhere = (np.abs(_h - 1.0) < 0.03).reshape(TH, TW).astype(np.uint8)
kk = max(3, int(160 / mm) | 1)   # holes up to ~16 cm inside the scanned wall
wallhere = cv2.morphologyEx(wallhere, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (kk, kk))).astype(bool)
# and nothing standing in front of it (the steamer): a ray along the normal hits something more than 3 cm out
infront = (_h < 0.97).reshape(TH, TW)
wallhere &= ~cv2.dilate(infront.astype(np.uint8), np.ones((int(30 / mm) | 1,) * 2, np.uint8)).astype(bool)   # the steamer etc. stay the model's own
print(f"model has wall on {wallhere.mean() * 100:.0f}% of the rectangle; something stands in front on {infront.mean() * 100:.0f}%")

def visible_mask_sub(P, u, v, z, sub):
    full = np.zeros(len(T3f), bool); m = np.zeros(len(T3f), bool); m[sub] = True
    inb = m & (u >= 1) & (u < W - 2) & (v >= 1) & (v < H - 2) & (z > 0.15) & wallhere.reshape(-1)
    dirs = T3f - P[:3, 3]; L = np.linalg.norm(dirs, axis=1); ii_ = np.nonzero(inb)[0]
    if len(ii_):
        r = np.concatenate([np.tile(P[:3, 3], (len(ii_), 1)), dirs[ii_] / L[ii_, None]], 1).astype(np.float32)
        full[ii_] = RC.cast_rays(o3d.core.Tensor(r))['t_hit'].numpy() > L[ii_] - 0.03
    return full[sub]

def visible_mask(P, u, v, z):
    """texel seen from camera P: inside the picture, on the model's wall, and the first thing a ray from the camera hits"""
    inb = (u >= 1) & (u < W - 2) & (v >= 1) & (v < H - 2) & (z > 0.15) & wallhere.reshape(-1)
    dirs = T3f - P[:3, 3]; L = np.linalg.norm(dirs, axis=1); dirs /= L[:, None]
    out = np.zeros(len(T3f), bool); ii_ = np.nonzero(inb)[0]
    if len(ii_):
        r = np.concatenate([np.tile(P[:3, 3], (len(ii_), 1)), dirs[ii_]], 1).astype(np.float32)
        th = RC.cast_rays(o3d.core.Tensor(r))['t_hit'].numpy()
        out[ii_] = th > L[ii_] - 0.03
    return out

cap = cv2.VideoCapture(video); cap.set(cv2.CAP_PROP_POS_MSEC, (t0 - offset) * 1000)
cands = []
while True:
    ok, img = cap.read()
    if not ok: break
    t = cap.get(cv2.CAP_PROP_POS_MSEC) / 1000 + offset
    if t > t1: break
    P = pose_at(t)
    if P is None: continue
    u, v, z = project(P, T3.reshape(-1, 3)); vis = visible_mask(P, u, v, z) if len(cands) < 0 else None
    sub = slice(None, None, 37); vis = np.zeros(len(u), bool); vis[sub] = visible_mask_sub(P, u, v, z, sub)
    vis = vis[sub]; u, v, z = u[sub], v[sub], z[sub]
    if vis.mean() < 0.3 * wallhere.mean(): continue
    # sharpness of the wall itself (not the things around it): the picture's edge energy sampled at the wall's texels
    g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY); lap = cv2.GaussianBlur(np.abs(cv2.Laplacian(g, cv2.CV_32F)), (5, 5), 0)
    sharp = float(lap[v[vis].astype(int), u[vis].astype(int)].mean())
    subvis = vis.copy()
    zc = np.median(z[vis]); res = ii[0] / zc * mm / 1000
    cosv = abs(n @ (P[:3, 3] - c0)) / np.linalg.norm(P[:3, 3] - c0)
    cands.append(dict(t=t, img=img, P=P, vis=vis.mean(), sharp=sharp, res=res, cos=cosv, z=zc, subvis=subvis))
print(f"{len(cands)} video frames see at least 30% of the wall between {t0:.1f} and {t1:.1f} s (replay clock)")
sh = np.array([c['sharp'] for c in cands]); shn = sh / np.percentile(sh, 90)
vmax = max(c['vis'] for c in cands)
for c, s_ in zip(cands, shn): c['score'] = min(s_, 1.3) ** 4 * min(c['res'], 1.0) * c['cos'] * min(1.0, c['vis'] / (0.7 * vmax))   # sharp first
cands.sort(key=lambda c: -c['score'])
for c in cands[:6]: print(f"  t {c['t']:.2f} s: sees {c['vis'] / max(wallhere.mean(), 1e-6) * 100:.0f}% of the wall, sharpness {c['sharp']:.0f}, {c['res']:.2f} picture px per {mm:.0f} mm texel, head-on {c['cos']:.2f}, {c['z']:.2f} m away")

# 3. refine the best frame's pose against the second best: both warped onto the wall, the shift that lines them up
def warp(c, du=0.0, dv=0.0):
    u, v, z = project(c['P'], T3.reshape(-1, 3)); vis = visible_mask(c['P'], u, v, z)
    mu = (u + du).reshape(TH, TW).astype(np.float32); mv = (v + dv).reshape(TH, TW).astype(np.float32)
    out = cv2.remap(c['img'], mu, mv, cv2.INTER_CUBIC, borderMode=cv2.BORDER_CONSTANT)
    return out, vis.reshape(TH, TW)
best = cands[0]; tex, vis = warp(best)
used = [dict(t=round(best['t'], 3), share=0)]
if len(cands) > 1:
    # second opinion from a frame at least 0.5 s apart (an independent view): if the two disagree by a whole shift, say so
    other = next((c for c in cands[1:] if abs(c['t'] - best['t']) > 0.5), cands[1])
    t2, v2 = warp(other); both = vis & v2
    if both.sum() > 1000:
        a = cv2.cvtColor(tex, cv2.COLOR_BGR2GRAY).astype(np.float32); bq = cv2.cvtColor(t2, cv2.COLOR_BGR2GRAY).astype(np.float32)
        a[~both] = a[both].mean(); bq[~both] = bq[both].mean()
        (dx, dy), resp = cv2.phaseCorrelate(bq, a)
        print(f"the best frame and the next independent one ({other['t']:.2f} s) agree on the wall to {math.hypot(dx, dy) * mm:.0f} mm (response {resp:.2f})")
alpha = vis.copy(); share = {round(best['t'], 3): int(vis.sum())}
subcov = best['subvis'].copy(); fills = []
for c in cands[1:]:              # fill what the best frame can't see: the best-scoring frame that adds the most new wall
    if len(fills) >= 6: break
    gain = (c['subvis'] & ~subcov).sum()
    if gain >= max(20, 0.03 * subcov.size * wallhere.mean()): fills.append(c); subcov |= c['subvis']
for c in fills:                  # one frame at a time, never mixed
    if alpha.all(): break
    t2, v2 = warp(c); new = v2 & ~alpha
    if new.sum() < 200: continue
    ov = cv2.erode((v2 & alpha).astype(np.uint8), np.ones((5, 5), np.uint8))
    if ov.sum() > 2000:
        # line the fill frame up with what's there: ECC (translation) on edge images, inside the overlap only
        ga = cv2.GaussianBlur(cv2.cvtColor(tex, cv2.COLOR_BGR2GRAY).astype(np.float32), (0, 0), 1.5)
        gb = cv2.GaussianBlur(cv2.cvtColor(t2, cv2.COLOR_BGR2GRAY).astype(np.float32), (0, 0), 1.5)
        Mx = np.eye(2, 3, dtype=np.float32)
        try:
            _, Mx = cv2.findTransformECC(ga, gb, Mx, cv2.MOTION_TRANSLATION, (cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 100, 1e-5), ov, 5)
        except cv2.error: Mx = np.eye(2, 3, dtype=np.float32)
        dx, dy = Mx[0, 2], Mx[1, 2]
        if math.hypot(dx, dy) < 50 / mm:
            t2 = cv2.warpAffine(t2, Mx, (TW, TH), flags=cv2.INTER_CUBIC | cv2.WARP_INVERSE_MAP)
            v2 = cv2.warpAffine(v2.astype(np.uint8), Mx, (TW, TH), flags=cv2.INTER_NEAREST | cv2.WARP_INVERSE_MAP).astype(bool)
            new = v2 & ~alpha
        # the fill frame was exposed differently (auto exposure): match its colour to what's there, per channel, on the overlap
        ov2 = (v2 & alpha)
        if ov2.sum() > 1000:
            gain = tex[ov2].reshape(-1, 3).mean(0) / np.maximum(t2[ov2].reshape(-1, 3).mean(0), 1)
            t2 = np.clip(t2.astype(np.float32) * gain, 0, 255).astype(np.uint8)
            print(f"  fill from {c['t']:.2f} s: colour gain {np.round(gain, 2)}")
            print(f"  fill from {c['t']:.2f} s: shifted {dx * mm:.1f}, {dy * mm:.1f} mm to line up with the frames already used")
    tex[new] = t2[new]; alpha |= new; share[round(c['t'], 3)] = int(new.sum())
alpha &= wallhere
rgba = np.dstack([tex, (alpha * 255).astype(np.uint8)])
cv2.imwrite(os.path.join(rdir, name + '.png'), rgba)
json.dump(dict(corners=[list(map(float, p)) for p in corners], normal=list(map(float, n)), size_m=[float(s1 - s0), float(y1 - y0)],
               mm_per_texel=mm, texels=[TW, TH], frames_used={f"{t:.3f}": k for t, k in share.items()},
               note="texture from single video frames projected onto the fitted wall plane; transparent = no frame saw it"),
          open(os.path.join(rdir, name + '.json'), 'w'), indent=1)
print(f"{name}.png {TW}x{TH} texels at {mm} mm: {alpha.mean() * 100:.0f}% covered, from {len(share)} frame(s): {share}")

# 4. before / after: the model's own paint (vertex colours) on the same rectangle vs the new texture
mv_ = o3d.geometry.TriangleMesh(mesh); scene = o3d.t.geometry.RaycastingScene(); scene.add_triangles(o3d.t.geometry.TriangleMesh.from_legacy(mv_))
rays = np.concatenate([T3.reshape(-1, 3) + n * 0.3, np.tile(-n, (TW * TH, 1))], 1).astype(np.float32)
hit = scene.cast_rays(o3d.core.Tensor(rays)); tri = hit['primitive_ids'].numpy(); bc = hit['primitive_uvs'].numpy(); dist = hit['t_hit'].numpy()
Ct = np.asarray(mesh.vertex_colors); Tr = np.asarray(mesh.triangles)
okh = np.isfinite(dist) & (np.abs(dist - 0.3) < 0.15)
col = np.zeros((TW * TH, 3)); trv = Tr[np.where(okh, tri, 0)]
col = Ct[trv[:, 0]] * (1 - bc[:, 0:1] - bc[:, 1:2]) + Ct[trv[:, 1]] * bc[:, 0:1] + Ct[trv[:, 2]] * bc[:, 1:2]
before = np.where(okh[:, None], col, 0.1).reshape(TH, TW, 3)[..., ::-1] * 255
after = np.where(alpha[..., None], tex, 25)
pair = np.hstack([before.astype(np.uint8), np.full((TH, 12, 3), 255, np.uint8), after.astype(np.uint8)])
cv2.putText(pair, 'before: model paint', (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (0, 0, 220), 2)
cv2.putText(pair, f'after: video frame {best["t"]:.2f} s, {mm:.0f} mm texels', (TW + 22, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (0, 0, 220), 2)
cv2.imwrite(os.path.join(rdir, name + '-before-after.jpg'), pair, [cv2.IMWRITE_JPEG_QUALITY, 92])
print("written", name + '.png', name + '.json', name + '-before-after.jpg')
