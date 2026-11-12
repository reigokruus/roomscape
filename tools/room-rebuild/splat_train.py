#!/usr/bin/env python3
"""Photo-real 3D Gaussian splat of a scanned room from its video, using the scan's own poses (GPU, gsplat).

  python splat_train.py <all-frames.csv> <video> <room.ply> <out dir> [--every 3] [--scale 0.5] [--iters 30000]
                        [--until <s, column 3 time>] [--sh 3]

all-frames.csv: s24_frames.py / ios_frames.py layout (video index, video time, scan time, tracking, 16 pose floats
camera->world column-major with x right, y up, looking down -z; fx fy cx cy w h). The video is read front to back once;
of every --every posed frames the sharpest is kept, scaled by --scale. Start points: the room.ply vertices with colours.
Learned with the splats: a small pose correction and a colour gain/offset per picture (the scan poses are off by a
few cm; the phone changes exposure). Every 25th kept picture is held out to measure quality (PSNR).
Writes <out>/splat.ply (standard 3DGS layout), renders/*.jpg (held-out views: picture | render), train-log.txt.
Only what the video saw gets splats; nothing is added for unseen areas.
"""
import sys, os, math, time, json, numpy as np, cv2, torch, torch.nn.functional as Fn
from gsplat import rasterization
from gsplat.strategy import DefaultStrategy
from plyfile import PlyData, PlyElement
from scipy.spatial import cKDTree

args = sys.argv[1:]
def opt(name, default):
    if name in args: i = args.index(name); v = args[i + 1]; del args[i:i + 2]; return v
    return default
EVERY = int(opt('--every', 3)); SCALE = float(opt('--scale', 0.5)); ITERS = int(opt('--iters', 30000))
UNTIL = float(opt('--until', 1e9)); SHD = int(opt('--sh', 3))
csv, video, ply, odir = args
os.makedirs(os.path.join(odir, 'renders'), exist_ok=True)
dev = 'cuda'; T0 = time.time(); logf = open(os.path.join(odir, 'train-log.txt'), 'w')
def log(s):
    s = f"[{time.time() - T0:6.0f} s] {s}"; print(s, flush=True); logf.write(s + '\n'); logf.flush()

# ---- pictures
rows = {}
for l in open(csv):
    r = l.strip().split(',')
    if r[3] == '2' and float(r[2]) <= UNTIL: rows[int(r[0])] = r
cap = cv2.VideoCapture(video); vi = -1; group = []; imgs = []; poses = []; Ks = []
def flush():
    if not group: return
    b = max(group, key=lambda g: g[0]); _, img, r = b
    img = cv2.resize(img, None, fx=SCALE, fy=SCALE, interpolation=cv2.INTER_AREA) if SCALE != 1 else img
    imgs.append(torch.from_numpy(cv2.cvtColor(img, cv2.COLOR_BGR2RGB)))
    M = np.array([float(x) for x in r[4:20]]).reshape(4, 4).T @ np.diag([1, -1, -1, 1.0])   # to OpenCV camera axes
    poses.append(np.linalg.inv(M))
    fx, fy, cx, cy = [float(x) for x in r[20:24]]
    Ks.append([[fx * SCALE, 0, (cx + 0.5) * SCALE - 0.5], [0, fy * SCALE, (cy + 0.5) * SCALE - 0.5], [0, 0, 1]])
    group.clear()
while cap.grab():
    vi += 1
    r = rows.get(vi)
    if r is None: continue
    img = cap.retrieve()[1]
    g = cv2.cvtColor(cv2.resize(img, (480, 360)), cv2.COLOR_BGR2GRAY)
    group.append((cv2.Laplacian(g, cv2.CV_32F).var(), img, r))
    if len(group) >= EVERY: flush()
flush()
N = len(imgs); H, W = imgs[0].shape[:2]
IM = torch.stack(imgs).pin_memory(); del imgs
VM = torch.tensor(np.stack(poses), dtype=torch.float32, device=dev); KK = torch.tensor(Ks, dtype=torch.float32, device=dev)
test = np.arange(N) % 25 == 12; train_ids = np.nonzero(~test)[0]; test_ids = np.nonzero(test)[0]
camc = np.linalg.inv(np.stack(poses))[:, :3, 3]; scene_scale = float(np.linalg.norm(camc - camc.mean(0), axis=1).max()) * 1.1
log(f"{N} pictures {W}x{H} ({len(train_ids)} train, {len(test_ids)} held out), scene scale {scene_scale:.2f} m")

# ---- start splats from the mesh vertices
pd = PlyData.read(ply)['vertex']; P = np.stack([pd['x'], pd['y'], pd['z']], 1).astype(np.float32)
C = np.stack([pd['red'], pd['green'], pd['blue']], 1).astype(np.float32) / 255 if 'red' in pd.data.dtype.names else np.full_like(P, 0.5)
d, _ = cKDTree(P).query(P, 4); dist = np.clip(d[:, 1:].mean(1), 1e-4, 0.05)
n0 = len(P); C0 = 0.28209479177387814
params = torch.nn.ParameterDict({
    'means': torch.nn.Parameter(torch.tensor(P, device=dev)),
    'scales': torch.nn.Parameter(torch.tensor(np.log(dist)[:, None].repeat(3, 1), dtype=torch.float32, device=dev)),
    'quats': torch.nn.Parameter(torch.tensor(np.tile([1.0, 0, 0, 0], (n0, 1)), dtype=torch.float32, device=dev)),
    'opacities': torch.nn.Parameter(torch.logit(torch.full((n0,), 0.1, device=dev))),
    'sh0': torch.nn.Parameter(torch.tensor((C - 0.5) / C0, dtype=torch.float32, device=dev)[:, None, :]),
    'shN': torch.nn.Parameter(torch.zeros((n0, (SHD + 1) ** 2 - 1, 3), device=dev)),
})
lrs = dict(means=1.6e-4 * scene_scale, scales=5e-3, quats=1e-3, opacities=5e-2, sh0=2.5e-3, shN=2.5e-3 / 20)
opts = {k: torch.optim.Adam([{'params': params[k], 'lr': lrs[k], 'name': k}], eps=1e-15) for k in params}
sched = torch.optim.lr_scheduler.ExponentialLR(opts['means'], gamma=0.01 ** (1.0 / ITERS))
strategy = DefaultStrategy(refine_stop_iter=int(ITERS * 0.5), verbose=False, absgrad=True, grow_grad2d=0.0008)
strategy.check_sanity(params, opts); sstate = strategy.initialize_state(scene_scale=scene_scale)
# per picture: pose correction (axis-angle + translation) and colour gain/offset
pose_d = torch.nn.Parameter(torch.zeros((N, 6), device=dev)); col_d = torch.nn.Parameter(torch.zeros((N, 6), device=dev))
popt = torch.optim.Adam([{'params': [pose_d], 'lr': 1e-4}, {'params': [col_d], 'lr': 1e-3}])

def skew(w):
    z = torch.zeros_like(w[:, 0])
    return torch.stack([z, -w[:, 2], w[:, 1], w[:, 2], z, -w[:, 0], -w[:, 1], w[:, 0], z], 1).reshape(-1, 3, 3)
def viewmats(ids, correct=True):
    V = VM[ids]
    if not correct: return V
    d = pose_d[ids]; R = torch.matrix_exp(skew(d[:, :3]))
    D = torch.eye(4, device=dev).repeat(len(ids), 1, 1); D[:, :3, :3] = R; D[:, :3, 3] = d[:, 3:]
    return D @ V
def gauss_win(s=11, sig=1.5):
    g = torch.exp(-(torch.arange(s, device=dev) - s // 2) ** 2 / (2 * sig ** 2)); g /= g.sum(); return (g[:, None] * g[None]).expand(3, 1, s, s)
WIN = gauss_win()
def ssim(a, b):
    mu_a = Fn.conv2d(a, WIN, padding=5, groups=3); mu_b = Fn.conv2d(b, WIN, padding=5, groups=3)
    saa = Fn.conv2d(a * a, WIN, padding=5, groups=3) - mu_a ** 2; sbb = Fn.conv2d(b * b, WIN, padding=5, groups=3) - mu_b ** 2
    sab = Fn.conv2d(a * b, WIN, padding=5, groups=3) - mu_a * mu_b
    return (((2 * mu_a * mu_b + 1e-4) * (2 * sab + 9e-4)) / ((mu_a ** 2 + mu_b ** 2 + 1e-4) * (saa + sbb + 9e-4))).mean()
def render(ids, sh_deg, correct=True):
    colors = torch.cat([params['sh0'], params['shN']], 1)
    out, alpha, info = rasterization(params['means'], params['quats'], torch.exp(params['scales']), torch.sigmoid(params['opacities']),
                                     colors, viewmats(ids, correct), KK[ids], W, H, sh_degree=sh_deg, packed=False, absgrad=True)
    return out, alpha, info

rng = np.random.default_rng(0)
for it in range(ITERS):
    i = int(rng.choice(train_ids)); ids = torch.tensor([i], device=dev)
    gt = IM[i].to(dev, non_blocking=True).float()[None] / 255
    sh_deg = min(SHD, it // 1000)
    out, alpha, info = render(ids, sh_deg)
    cd = col_d[ids]; out = out * (1 + cd[:, None, None, :3]) + cd[:, None, None, 3:]
    strategy.step_pre_backward(params, opts, sstate, it, info)
    l1 = (out - gt).abs().mean(); ls = 1 - ssim(out.permute(0, 3, 1, 2), gt.permute(0, 3, 1, 2))
    loss = 0.8 * l1 + 0.2 * ls + 1e-3 * (pose_d[ids] ** 2).sum() + 1e-3 * (cd ** 2).sum()
    loss.backward()
    for o in opts.values(): o.step(); o.zero_grad(set_to_none=True)
    popt.step(); popt.zero_grad(set_to_none=True); sched.step()
    strategy.step_post_backward(params, opts, sstate, it, info, packed=False)
    if it % 1000 == 0 or it == ITERS - 1:
        log(f"iter {it}: loss {loss.item():.4f}, {len(params['means'])} splats, GPU {torch.cuda.max_memory_allocated() / 1e9:.1f} GB")

# ---- held-out quality (their own pose/colour corrections are not learned: plain scan poses)
ps = []
with torch.no_grad():
    for j, i in enumerate(test_ids):
        out, _, _ = render(torch.tensor([i], device=dev), SHD, correct=False)
        gt = IM[i].to(dev).float()[None] / 255; out = out.clamp(0, 1)
        ps.append(float(-10 * torch.log10(((out - gt) ** 2).mean())))
        if j % 2 == 0:
            pair = torch.cat([gt[0], out[0]], 1).cpu().numpy() * 255
            cv2.imwrite(os.path.join(odir, 'renders', f'held-{i:04d}.jpg'), cv2.cvtColor(pair.astype(np.uint8), cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, 90])
log(f"held-out pictures: PSNR median {np.median(ps):.2f} dB over {len(ps)} (picture | render in renders/)")

# ---- splat.ply (standard 3DGS layout)
with torch.no_grad():
    m = params['means'].cpu().numpy(); n = len(m)
    f_dc = params['sh0'].cpu().numpy().reshape(n, 3); f_rest = params['shN'].transpose(1, 2).cpu().numpy().reshape(n, -1)
    op = params['opacities'].cpu().numpy()[:, None]; sc = params['scales'].cpu().numpy(); q = Fn.normalize(params['quats'], dim=1).cpu().numpy()
names = ['x', 'y', 'z', 'nx', 'ny', 'nz'] + [f'f_dc_{k}' for k in range(3)] + [f'f_rest_{k}' for k in range(f_rest.shape[1])] + ['opacity'] + [f'scale_{k}' for k in range(3)] + [f'rot_{k}' for k in range(4)]
arr = np.concatenate([m, np.zeros_like(m), f_dc, f_rest, op, sc, q], 1).astype(np.float32)
el = np.empty(n, dtype=[(k, 'f4') for k in names]);
for k, nm in enumerate(names): el[nm] = arr[:, k]
PlyData([PlyElement.describe(el, 'vertex')]).write(os.path.join(odir, 'splat.ply'))
json.dump(dict(pictures=N, size=[W, H], splats=n, iters=ITERS, psnr_held_out=round(float(np.median(ps)), 2), every=EVERY, scale=SCALE),
          open(os.path.join(odir, 'splat-info.json'), 'w'), indent=1)
log(f"written splat.ply: {n} splats, {os.path.getsize(os.path.join(odir, 'splat.ply')) / 1e6:.0f} MB")
