#!/usr/bin/env python3
"""Sharp textures for every large flat area of a rebuilt room (walls, floor, table and counter tops, cupboard fronts),
each painted from the best single video frames (the wall_texture.py method, for the whole room at once).

  python3 planes_texture.py <rebuild dir> <video> <all-frames.csv> [--mm 2] [--max-tex 2048] [--min-area 0.3]
                            [--out <dir>] [--step N] [--split-normals] [--tile 1.5]

--out: where the textures go (default: the rebuild dir); --step N: score only every Nth posed frame in pass 1.
--split-normals: look for floors/tops among the vertices facing up or down and for walls among those facing sideways
(separately), so a horizontal plane slicing through furniture and walls doesn't eat the RANSAC rounds (S24 replay).
--tile M: cut patches bigger than M x M metres into tiles, each with its own best frame (a big floor seen whole from
one frame is far away and covered only in part).
For the S24 replay scans, s24_frames.py writes the all-frames.csv (poses interpolated from pose-track.bin).
Yields to a running Xcode build (waits while xcodebuild / swift-frontend run): run it under nice.

all-frames.csv (ios_frames.py): every video frame with its exact pose and full-resolution intrinsics. The video is read
front to back twice (no seeking: the iPhone's HEVC .mov doesn't seek reliably): pass 1 scores every frame for every
flat patch (sharpness measured on the patch itself, closeness, head-on, how much of it is seen unblocked: rays cast
against the model), pass 2 keeps the chosen frames. Each patch gets the best frame, then gaps filled from the next best
frames that add the most, each lined up (ECC shift) and exposure-matched to what's there; never averaged.
Only scanned surface is painted: the patch's own model cells (gaps up to ~16 cm closed), nothing standing in front.
Writes tex-NN.png (RGBA) + tex-NN.json per patch and textures.json (the list), for the viewer.
"""
import sys, os, json, math, numpy as np, cv2, open3d as o3d

args = sys.argv[1:]
def opt(name, default):
    if name in args: i = args.index(name); v = args[i + 1]; del args[i:i + 2]; return v
    return default
MM = float(opt('--mm', 2)); MAXT = int(opt('--max-tex', 2048)); MINA = float(opt('--min-area', 0.3))
OUT = opt('--out', None); STEP = int(opt('--step', 1)); TILE = float(opt('--tile', 0)); SPLITN = '--split-normals' in args
if SPLITN: args.remove('--split-normals')
rdir, video, allcsv = args
odir = OUT or rdir; os.makedirs(odir, exist_ok=True)

import subprocess, time
_last_yield = [0.0]
def yield_to_xcode(every=60):
    """the iPhone build in the foreground wins: wait while Xcode builds or tests"""
    if time.time() - _last_yield[0] < every: return
    while subprocess.run(['pgrep', '-f', 'xcodebuild|swift-build|swift-frontend|XCBBuildService|xctest'], capture_output=True).returncode == 0:
        time.sleep(20)
    _last_yield[0] = time.time()

mesh = o3d.io.read_triangle_mesh(os.path.join(rdir, 'room.ply')); mesh.compute_vertex_normals()
V = np.asarray(mesh.vertices); Nv = np.asarray(mesh.vertex_normals)
floorY = np.percentile(V[:, 1], 2); topY = np.percentile(V[:, 1], 98)
RC = o3d.t.geometry.RaycastingScene(); RC.add_triangles(o3d.t.geometry.TriangleMesh.from_legacy(mesh))

# 1. flat patches: planes one after another (RANSAC on the model's vertices), each split into connected pieces
pc = o3d.geometry.PointCloud(o3d.utility.Vector3dVector(V)); pc.normals = o3d.utility.Vector3dVector(Nv)
patches = []
groups = [np.nonzero(np.abs(Nv[:, 1]) > 0.85)[0], np.nonzero(np.abs(Nv[:, 1]) < 0.35)[0]] if SPLITN else [np.arange(len(V))]
for rest in groups:
    for it in range(40):
        if len(rest) < 2000: break
        sub = pc.select_by_index(rest)
        pl, inl = sub.segment_plane(0.02, 3, 1500)
        inl = np.asarray(inl)
        if len(inl) < 800: break
        n = np.array(pl[:3]); d = pl[3] / np.linalg.norm(n); n /= np.linalg.norm(n)
        idx = rest[inl]
        good = np.abs(Nv[idx] @ n) > 0.8                       # vertices whose own surface faces the same way
        idx = idx[good]
        rest = np.setdiff1d(rest, rest[inl])
        if len(idx) < 300: continue                          # a plane across the surfaces, not along one
        horiz = abs(n[1]) > 0.9
        if not horiz and abs(n[1]) > 0.3: continue              # slanted: skip
        c = V[idx].mean(0)
        if horiz and c[1] > topY - 0.3: continue                 # the ceiling is cut in the viewer anyway
        if horiz: hdir = np.array([1.0, 0, 0]); hdir -= (hdir @ n) * n; hdir /= np.linalg.norm(hdir)
        else: hdir = np.cross([0, 1.0, 0], n); hdir /= np.linalg.norm(hdir)
        vdir = np.cross(n, hdir)
        s = (V[idx] - c) @ hdir; y = (V[idx] - c) @ vdir
        C = 0.03; gi = np.floor(s / C).astype(int); gj = np.floor(y / C).astype(int); i0, j0 = gi.min(), gj.min()
        occ = np.zeros((gj.max() - j0 + 1, gi.max() - i0 + 1), np.uint8); occ[gj - j0, gi - i0] = 1
        k = int(0.16 / C) | 1
        occ = cv2.morphologyEx(occ, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k)))
        ncomp, lab, stats, _ = cv2.connectedComponentsWithStats(occ, 8)
        for q in range(1, ncomp):
            area = stats[q, cv2.CC_STAT_AREA] * C * C
            if area < MINA: continue
            X0, Y0, Wc, Hc = stats[q, 0], stats[q, 1], stats[q, 2], stats[q, 3]
            tc = int(TILE / C) if TILE > 0 else max(Wc, Hc)
            nx, ny = -(-Wc // tc), -(-Hc // tc); tw, th_ = -(-Wc // nx), -(-Hc // ny)     # equal tiles
            for ty in range(ny):
                for tx in range(nx):
                    x0, y0_ = X0 + tx * tw, Y0 + ty * th_; w, h = min(tw, X0 + Wc - x0), min(th_, Y0 + Hc - y0_)
                    mask = (lab[y0_:y0_ + h, x0:x0 + w] == q).astype(np.uint8)
                    if mask.sum() * C * C < min(MINA, 0.1): continue
                    s0, s1 = (i0 + x0) * C, (i0 + x0 + w) * C; ya, yb = (j0 + y0_) * C, (j0 + y0_ + h) * C
                    kind = ('floor' if c[1] < floorY + 0.15 else 'top') if horiz else 'wall'
                    patches.append(dict(n=n, d=d, c=c, h=hdir, v=vdir, s0=s0, s1=s1, y0=ya, y1=yb, mask=mask, area=mask.sum() * C * C, kind=kind))
print(f"{len(patches)} flat patches over {MINA} m2: " + ", ".join(f"{p['kind']} {p['area']:.1f}" for p in patches))

# texel grids (texel centres in 3D are recomputed when needed, not kept: a big floor is millions of texels)
def texels(p):
    mm, TW, TH = p['mm'], p['TW'], p['TH']
    gs, gy = np.meshgrid(p['s0'] + (np.arange(TW) + 0.5) * mm / 1000, p['y1'] - (np.arange(TH) + 0.5) * mm / 1000)
    T3 = (p['c'] + gs[..., None] * p['h'] + gy[..., None] * p['v']).reshape(-1, 3)
    T3 -= ((T3 @ p['n']) + p['d'])[:, None] * p['n']          # onto the plane (c lies near it, not exactly)
    return T3
for p in patches:
    w, h = p['s1'] - p['s0'], p['y1'] - p['y0']
    mm = max(MM, max(w, h) * 1000 / MAXT); p['mm'] = mm
    TW, TH = max(2, int(w * 1000 / mm)), max(2, int(h * 1000 / mm)); p['TW'], p['TH'] = TW, TH
    T3 = texels(p)
    m = cv2.resize(p['mask'][::-1], (TW, TH), interpolation=cv2.INTER_NEAREST)[::-1] if False else cv2.resize(np.flipud(p['mask']), (TW, TH), interpolation=cv2.INTER_NEAREST)
    r = np.concatenate([T3 + p['n'] * 1.0, np.tile(-p['n'], (len(T3), 1))], 1).astype(np.float32)
    th = RC.cast_rays(o3d.core.Tensor(r))['t_hit'].numpy()
    infront = (th < 0.96).reshape(TH, TW)
    infront = cv2.dilate(infront.astype(np.uint8), np.ones((int(30 / mm) | 1,) * 2, np.uint8)).astype(bool)
    p['valid'] = m.astype(bool) & ~infront
    p['sub'] = np.nonzero(p['valid'].reshape(-1))[0][::max(1, int(p['valid'].sum() // 1500))]
    p['Tsub'] = T3[p['sub']]; del T3
    p['corners'] = [p['c'] + s * p['h'] + y * p['v'] for s, y in ((p['s0'], p['y0']), (p['s1'], p['y0']), (p['s1'], p['y1']), (p['s0'], p['y1']))]
    p['corners'] = [q - ((q @ p['n']) + p['d']) * p['n'] for q in p['corners']]
patches = [p for p in patches if len(p['sub']) > 50]

rows = [l.strip().split(',') for l in open(allcsv)]
def frame_pose(r):
    M = np.array([float(x) for x in r[4:20]]).reshape(4, 4).T; ii = [float(x) for x in r[20:26]]; return M, ii

def project(M, ii, P):
    c = (P - M[:3, 3]) @ M[:3, :3]; z = -c[:, 2]
    return ii[0] * c[:, 0] / np.maximum(z, 1e-3) + ii[2], ii[3] - ii[1] * c[:, 1] / np.maximum(z, 1e-3), z

def visible(M, ii, P, u, v, z):
    W, H = ii[4], ii[5]; ok = (u >= 2) & (u < W - 3) & (v >= 2) & (v < H - 3) & (z > 0.2) & (z < 5)
    out = np.zeros(len(P), bool); k = np.nonzero(ok)[0]
    if len(k):
        dirs = P[k] - M[:3, 3]; L = np.linalg.norm(dirs, axis=1)
        r = np.concatenate([np.tile(M[:3, 3], (len(k), 1)), dirs / L[:, None]], 1).astype(np.float32)
        out[k] = RC.cast_rays(o3d.core.Tensor(r))['t_hit'].numpy() > L - 0.03
    return out

# 2. pass 1: score every frame for every patch
cap = cv2.VideoCapture(video); vi = 0; ri = 0; scores = [[] for _ in patches]
byvi = {int(r[0]): r for r in rows}
while True:
    ok = cap.grab()
    if not ok: break
    yield_to_xcode()
    r = byvi.get(vi); vi += 1
    if r is None or r[3] != '2' or (vi - 1) % STEP: continue
    M, ii = frame_pose(r); cam = M[:3, 3]; look = -M[:3, 2]
    near = []
    for pi, p in enumerate(patches):
        tc = p['Tsub']; dv = tc.mean(0) - cam; dist = np.linalg.norm(dv)
        if dist > 5 or (dv @ look) / dist < 0.3: continue
        cosv = abs(p['n'] @ (cam - tc.mean(0))) / dist
        if cosv < 0.3: continue
        near.append((pi, cosv))
    if not near: continue
    _, img = cap.retrieve()
    g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY); lap = cv2.GaussianBlur(np.abs(cv2.Laplacian(g, cv2.CV_32F)), (5, 5), 0)
    for pi, cosv in near:
        p = patches[pi]; P = p['Tsub']; u, v, z = project(M, ii, P); vis = visible(M, ii, P, u, v, z)
        if vis.mean() < 0.15: continue
        sharp = float(lap[v[vis].astype(int), u[vis].astype(int)].mean())
        res = ii[0] / np.median(z[vis]) * p['mm'] / 1000
        scores[pi].append(dict(vi=vi - 1, sharp=sharp, res=res, cos=cosv, vis=vis, z=float(np.median(z[vis]))))
print("pass 1 done: frames scored per patch:", [len(s) for s in scores])

# choose: best by score, then fills that add the most (on the sample texels)
need = {}
for pi, sc in enumerate(scores):
    if not sc: continue
    sh = np.array([s['sharp'] for s in sc]); shn = sh / max(np.percentile(sh, 90), 1e-6); vmax = max(s['vis'].mean() for s in sc)
    for s, x in zip(sc, shn): s['score'] = min(x, 1.3) ** 4 * min(s['res'], 1.0) * s['cos'] * min(1.0, s['vis'].mean() / (0.7 * vmax))
    sc.sort(key=lambda s: -s['score'])
    chosen = [sc[0]]; cov = sc[0]['vis'].copy()
    for s in sc[1:200]:
        if len(chosen) >= 6: break
        if (s['vis'] & ~cov).sum() >= max(15, 0.03 * len(cov)): chosen.append(s); cov |= s['vis']
    patches[pi]['chosen'] = chosen
    for s in chosen: need.setdefault(s['vi'], []).append(pi)

# 3. pass 2: keep the chosen frames (JPEG in memory, quality 95), then compose each patch
keep = {}; cap = cv2.VideoCapture(video); vi = 0
while True:
    ok = cap.grab()
    if not ok: break
    yield_to_xcode()
    if vi in need: _, img = cap.retrieve(); keep[vi] = cv2.imencode('.jpg', img, [cv2.IMWRITE_JPEG_QUALITY, 95])[1]
    vi += 1
out = []
for pi, p in enumerate(patches):
    if 'chosen' not in p: continue
    TW, TH, T3 = p['TW'], p['TH'], texels(p)
    def warp(s):
        M, ii = frame_pose(byvi[s['vi']]); img = cv2.imdecode(keep[s['vi']], cv2.IMREAD_COLOR)
        u, v, z = project(M, ii, T3); vis = visible(M, ii, T3, u, v, z).reshape(TH, TW) & p['valid']
        return cv2.remap(img, u.reshape(TH, TW).astype(np.float32), v.reshape(TH, TW).astype(np.float32), cv2.INTER_CUBIC), vis
    best = p['chosen'][0]; tex, alpha = warp(best); used = {best['vi']: int(alpha.sum())}
    for s in p['chosen'][1:]:
        t2, v2 = warp(s); new = v2 & ~alpha
        if new.sum() < 200: continue
        ov = cv2.erode((v2 & alpha).astype(np.uint8), np.ones((5, 5), np.uint8))
        if ov.sum() > 2000:
            ga = cv2.GaussianBlur(cv2.cvtColor(tex, cv2.COLOR_BGR2GRAY).astype(np.float32), (0, 0), 1.5)
            gb = cv2.GaussianBlur(cv2.cvtColor(t2, cv2.COLOR_BGR2GRAY).astype(np.float32), (0, 0), 1.5)
            Mx = np.eye(2, 3, dtype=np.float32)
            try: _, Mx = cv2.findTransformECC(ga, gb, Mx, cv2.MOTION_TRANSLATION, (cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 60, 1e-5), ov, 5)
            except cv2.error: Mx = np.eye(2, 3, dtype=np.float32)
            if math.hypot(Mx[0, 2], Mx[1, 2]) < 50 / p['mm']:
                t2 = cv2.warpAffine(t2, Mx, (TW, TH), flags=cv2.INTER_CUBIC | cv2.WARP_INVERSE_MAP)
                v2 = cv2.warpAffine(v2.astype(np.uint8), Mx, (TW, TH), flags=cv2.INTER_NEAREST | cv2.WARP_INVERSE_MAP).astype(bool)
            ov2 = v2 & alpha
            if ov2.sum() > 1000:
                gain = tex[ov2].reshape(-1, 3).mean(0) / np.maximum(t2[ov2].reshape(-1, 3).mean(0), 1)
                t2 = np.clip(t2.astype(np.float32) * np.clip(gain, 0.6, 1.6), 0, 255).astype(np.uint8)
        new = v2 & ~alpha & p['valid']; tex[new] = t2[new]; alpha |= new; used[s['vi']] = int(new.sum())
    name = f"tex-{len(out) + 1:02d}"
    cv2.imwrite(os.path.join(odir, name + '.png'), np.dstack([tex, (alpha * 255).astype(np.uint8)]))
    info = dict(kind=p['kind'], corners=[list(map(float, q)) for q in p['corners']], normal=list(map(float, p['n'])),
                size_m=[float(p['s1'] - p['s0']), float(p['y1'] - p['y0'])], mm_per_texel=round(p['mm'], 2),
                best=dict(frame=best['vi'], distance_m=round(best['z'], 2), picture_mm_per_px=round(best['z'] / byvi_fx * 1000, 2) if (byvi_fx := float(byvi[best['vi']][20])) else None),
                frames_used={str(k): v for k, v in used.items()}, covered=round(float(alpha.mean()), 3))
    json.dump(info, open(os.path.join(odir, name + '.json'), 'w'), indent=1)
    out.append(name)
    print(f"{name}: {p['kind']} {p['size_m'] if 'size_m' in p else ''}{p['s1'] - p['s0']:.1f} x {p['y1'] - p['y0']:.1f} m at {p['mm']:.1f} mm/texel; best frame {best['vi']} at {best['z']:.2f} m "
          f"({best['z'] / float(byvi[best['vi']][20]) * 1000:.1f} mm per picture pixel), {len(used)} frame(s), painted {alpha.sum() / max(p['valid'].sum(), 1) * 100:.0f}% of its scanned surface")
json.dump(out, open(os.path.join(odir, 'textures.json'), 'w'))
print(f"{len(out)} textures written; textures.json")
