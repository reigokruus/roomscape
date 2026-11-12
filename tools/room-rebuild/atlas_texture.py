#!/usr/bin/env python3
"""Paint the whole rebuilt room (furniture included) from the best single video stills, into one texture atlas.

  python atlas_texture.py <room.ply> <video> <all-frames.csv> <out dir> [--mm 2.5] [--max-tex 8192] [--step 1]
                          [--patch 0.3] [--pix 2]

The mesh is only the drawing board (rk): every triangle takes its colour from one video frame, never an average.
1. UV atlas of the mesh (xatlas, the mesh in 24 blocks so all cores work), texel size --mm.
2. Pass 1 over the video (every --step-th posed frame): cast a ray per --pix pixels against the mesh; each triangle a
   ray hits gets a score from that frame: local sharpness of the picture there x head-on (cos) x picture detail
   (mm per pixel vs --mm) x away from the picture edge x how close its colour there is to the mesh's fused colour
   (--sigma; misaligned or blocked views lose). All scores are kept (frames x triangles, in RAM).
3. Choice: the surface in patches (--patch m cubes, split by facing); each patch takes the one frame that paints it
   best overall; triangles that frame sees poorly (< 40% of their own best) take their own best frame.
4. Seam levelling (Waechter et al. 2014 style): per (vertex, frame) a colour offset, solved so the two sides of a seam
   meet and offsets vary smoothly within a frame's patch.
5. Pass 2 keeps the chosen frames; every texel is projected into its triangle's frame and sampled (+ offset).
   Triangles no frame saw keep the mesh's vertex colours. Holes stay holes: only the given mesh is painted.
Writes <out>/room.glb (mesh + atlas, for the viewer), atlas.jpg, atlas-info.json.
"""
import sys, os, json, time, numpy as np, cv2, open3d as o3d, torch, xatlas, scipy.sparse as sp, scipy.sparse.linalg as spl

args = sys.argv[1:]
def opt(name, default):
    if name in args: i = args.index(name); v = args[i + 1]; del args[i:i + 2]; return v
    return default
MM = float(opt('--mm', 2.5)); MAXT = int(opt('--max-tex', 8192)); STEP = int(opt('--step', 1))
PATCH = float(opt('--patch', 0.3)); PIX = int(opt('--pix', 2))
ply, video, allcsv, odir = args
os.makedirs(odir, exist_ok=True)
dev = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
T0 = time.time()
def log(s): print(f"[{time.time() - T0:6.0f} s] {s}", flush=True)

mesh = o3d.io.read_triangle_mesh(ply)
V = np.asarray(mesh.vertices).astype(np.float64); F = np.asarray(mesh.triangles).astype(np.int64)
VC = np.asarray(mesh.vertex_colors) if mesh.has_vertex_colors() else np.full((len(V), 3), 0.5)
nF = len(F)
FN = np.cross(V[F[:, 1]] - V[F[:, 0]], V[F[:, 2]] - V[F[:, 0]]); area = np.linalg.norm(FN, axis=1) / 2
FN /= np.maximum(np.linalg.norm(FN, axis=1, keepdims=True), 1e-12)
log(f"mesh: {len(V)} vertices, {nF} triangles, {area.sum():.1f} m2")
RC = o3d.t.geometry.RaycastingScene(); RC.add_triangles(o3d.t.geometry.TriangleMesh.from_legacy(mesh))

# ---- 1. atlas
# the mesh in ~24 spatial blocks: xatlas works on separate meshes in parallel (one big mesh runs on ~2 cores)
NCH = 24
cen = V[F].mean(1); nx = 6; nz = max(1, NCH // nx)
qx = np.searchsorted(np.quantile(cen[:, 0], np.linspace(0, 1, nx + 1)[1:-1]), cen[:, 0])
blk = np.zeros(nF, np.int64)
for i in range(nx):
    m = qx == i
    if m.any(): blk[m] = i * nz + np.searchsorted(np.quantile(cen[m, 2], np.linspace(0, 1, nz + 1)[1:-1]), cen[m, 2])
chunks = [np.nonzero(blk == c)[0] for c in range(nx * nz) if (blk == c).any()]
while True:
    at = xatlas.Atlas(); cmaps = []
    for fi in chunks:
        vu, inv = np.unique(F[fi].reshape(-1), return_inverse=True); cmaps.append(vu)
        at.add_mesh(V[vu].astype(np.float32), inv.reshape(-1, 3).astype(np.uint32))
    co = xatlas.ChartOptions(); po = xatlas.PackOptions()
    po.texels_per_unit = 1000.0 / MM; po.padding = 2; po.bilinear = True; po.resolution = 0
    at.generate(co, po)
    if max(at.width, at.height) <= MAXT and at.atlas_count == 1: break
    MM *= 1.25; log(f"atlas {at.width}x{at.height} x{at.atlas_count} too big, texel -> {MM:.2f} mm")
AW, AH = at.width, at.height
vm_l, af_l, uv_l, F2 = [], [], [], np.zeros_like(F); base = 0
for ci, (fi, vu) in enumerate(zip(chunks, cmaps)):
    vm, af, uv = at[ci]; vm_l.append(vu[vm]); af_l.append(af.astype(np.int64) + base); uv_l.append(uv); base += len(vm)
vmap = np.concatenate(vm_l); UV = np.concatenate(uv_l)
AF = np.zeros((nF, 3), np.int64)
for fi, af in zip(chunks, af_l): AF[fi] = af
log(f"atlas {AW}x{AH} at {MM:.2f} mm/texel, {len(vmap)} atlas vertices")

# ---- frames
rows = [l.strip().split(',') for l in open(allcsv)]
byvi = {int(r[0]): r for r in rows if r[3] == '2'}
def frame_pose(r):
    return np.array([float(x) for x in r[4:20]]).reshape(4, 4).T, [float(x) for x in r[20:26]]
r0 = rows[0]; _, ii0 = frame_pose(r0); W, H = int(ii0[4]), int(ii0[5])
gu, gv = np.meshgrid(np.arange(PIX / 2, W, PIX), np.arange(PIX / 2, H, PIX))
gu = gu.reshape(-1).astype(np.float32); gv = gv.reshape(-1).astype(np.float32)

# ---- 2. pass 1: score every frame for every triangle (kept whole in RAM: frames x triangles, float16)
FN_t = torch.tensor(FN, dtype=torch.float32, device=dev)
# reference colour per triangle: the mesh's fused vertex colours (an average over many depth frames). A frame whose
# picture there is far off that colour is misaligned or blocked: sharp texture from the wrong object must not win.
REF = torch.tensor(VC[F].mean(1) * 255, dtype=torch.float32, device=dev); SIG = float(opt('--sigma', 35))
fids = [v for v in sorted(byvi) if v % STEP == 0]; fcol = {v: j for j, v in enumerate(fids)}
S = np.zeros((len(fids), nF), np.float16)
log(f"score table: {len(fids)} frames x {nF} triangles = {S.nbytes / 1e9:.1f} GB in RAM")
cap = cv2.VideoCapture(video); vi = -1; nscored = 0
while cap.grab():
    vi += 1
    if vi not in fcol: continue
    r = byvi[vi]; _, img = cap.retrieve()
    M, ii = frame_pose(r); fx, fy, cx, cy = ii[:4]
    d = np.stack([(gu - cx) / fx, -(gv - cy) / fy, -np.ones_like(gu)], 1)
    dn = d / np.linalg.norm(d, axis=1, keepdims=True); dw = dn @ M[:3, :3].T
    ray = np.concatenate([np.tile(M[:3, 3], (len(dw), 1)), dw], 1).astype(np.float32)
    hit = RC.cast_rays(o3d.core.Tensor(ray))
    th = hit['t_hit'].numpy(); pid = hit['primitive_ids'].numpy().astype(np.int64)
    ok = np.isfinite(th) & (th < 5)
    if ok.sum() < 50: continue
    z = th * -dn[:, 2]
    ok &= z > 0.25
    g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    lap = cv2.GaussianBlur(np.abs(cv2.Laplacian(g, cv2.CV_32F)), (0, 0), 3)
    sh = lap[gv.astype(int), gu.astype(int)]
    k = np.nonzero(ok)[0]
    pk_ = torch.tensor(pid[k], device=dev)
    cosv = (torch.tensor(dw[k], dtype=torch.float32, device=dev) * FN_t[pk_]).sum(1).abs()
    res = torch.clamp(torch.tensor(MM / 1000 / (z[k] / fx), dtype=torch.float32, device=dev), max=1.0)
    # away from the picture's edge (lens distortion, motion blur at the edges): soft weight
    ex = torch.tensor(np.minimum(np.minimum(gu[k], W - gu[k]) / (0.1 * W), np.minimum(gv[k], H - gv[k]) / (0.1 * H)).clip(0, 1), dtype=torch.float32, device=dev)
    q = torch.tensor(sh[k], dtype=torch.float32, device=dev) * cosv ** 1.5 * res * (0.5 + 0.5 * ex)
    qs = torch.zeros(nF, device=dev).index_add_(0, pk_, q); qc = torch.zeros(nF, device=dev).index_add_(0, pk_, torch.ones_like(q))
    rgb = cv2.cvtColor(cv2.blur(img, (5, 5)), cv2.COLOR_BGR2RGB)[gv.astype(int)[k], gu.astype(int)[k]]
    csum = torch.zeros((nF, 3), device=dev).index_add_(0, pk_, torch.tensor(rgb, dtype=torch.float32, device=dev))
    cm = csum / qc.clamp(min=1)[:, None]
    cdiff = (cm - REF).norm(dim=1) / 1.732
    cons = torch.exp(-(cdiff / SIG) ** 2)
    S[fcol[vi]] = (qs / qc.clamp(min=1) * cons).half().cpu().numpy()
    nscored += 1
    if nscored % 1000 == 0: log(f"pass 1: {nscored} frames (video frame {vi})")
fmax = S.max(0).astype(np.float32); seen = fmax > 0
log(f"pass 1 done: {nscored} frames, {int(seen.sum())} of {nF} triangles seen ({float(area[seen].sum() / area.sum()) * 100:.0f}% of area)")

# ---- 3. choice: one frame per surface patch (~--patch m, same facing), the frame that paints the patch best overall;
# triangles that frame doesn't see well take their own best. Few frames, big pieces, few seams.
cen = V[F].mean(1); ax = np.argmax(np.abs(FN), 1) * 2 + (FN[np.arange(nF), np.argmax(np.abs(FN), 1)] > 0)
vox = np.floor(cen / PATCH).astype(np.int64); vox -= vox.min(0)
pkey = ((vox[:, 0] * 4096 + vox[:, 1]) * 4096 + vox[:, 2]) * 6 + ax
_, patch = np.unique(pkey, return_inverse=True); nPa = patch.max() + 1
patch_t = torch.tensor(patch, device=dev); w_t = torch.tensor(area / fmax.clip(1e-6), dtype=torch.float32, device=dev)  # each triangle's scores relative to its best
# greedy, in rounds: each patch's best frame takes the triangles it sees well; the triangles left over choose again
# together (the next best frame for what is left of the patch), so leftovers come in pieces, not triangle by triangle
Lc = np.full(nF, -1, np.int64); todo = seen.copy(); cols = np.arange(nF)
for rnd in range(4):
    if not todo.any(): break
    wr = w_t * torch.tensor(todo, dtype=torch.float32, device=dev)
    best_val = torch.full((nPa,), -1.0, device=dev); best_fr = torch.zeros(nPa, dtype=torch.int64, device=dev)
    for a in range(0, len(fids), 128):
        T = torch.tensor(S[a:a + 128], device=dev).float() * wr          # (b, nF)
        PSm = torch.zeros((T.shape[0], nPa), device=dev).index_add_(1, patch_t, T)
        v, j = PSm.max(0); upd = v > best_val; best_val[upd] = v[upd]; best_fr[upd] = j[upd] + a
    pf = best_fr[patch_t].cpu().numpy()
    own = S[pf, cols].astype(np.float32)
    take = todo & (own >= (0.25 if rnd < 3 else 0.0) * fmax) & (own > 0)
    Lc[take] = pf[take]; todo &= ~take
    log(f"choice round {rnd + 1}: {int(take.sum())} triangles placed, {int(todo.sum())} left")
fallback = todo
if fallback.any(): Lc[fallback] = np.argmax(S[:, fallback], 0)
L = np.where(Lc >= 0, np.array(fids)[Lc.clip(0)], -1).astype(np.int64)
labels = np.unique(L[L >= 0])
log(f"choice: {nPa} patches, {len(labels)} frames used; {int(fallback.sum())} triangles took their own best frame")
del S

# ---- pass 2: keep chosen frames
keep = {}; need = set(labels.tolist()); cap = cv2.VideoCapture(video); vi = -1
while cap.grab():
    vi += 1
    if vi in need: keep[vi] = cv2.imencode('.jpg', cap.retrieve()[1], [cv2.IMWRITE_JPEG_QUALITY, 96])[1]   # in memory, compressed (a 1920x1440 video has big frames)
    if vi > labels.max(): break
log(f"pass 2: {len(keep)} frames kept")
lidx = {v: i for i, v in enumerate(labels)}
_cache = [None, None]
def frame_img(li):
    if _cache[0] != li: _cache[0], _cache[1] = li, torch.tensor(cv2.cvtColor(cv2.imdecode(keep[int(labels[li])], cv2.IMREAD_COLOR), cv2.COLOR_BGR2RGB))
    return _cache[1]
POSE = np.stack([frame_pose(byvi[v])[0] for v in labels]); INT = np.stack([frame_pose(byvi[v])[1][:4] for v in labels])

def sample(li, P, blur=False):
    """colour (0..255 float) of 3D points P in kept frame index li (one frame), bilinear; NaN outside"""
    M = POSE[li]; fx, fy, cx, cy = INT[li]
    c = (P - M[:3, 3]) @ M[:3, :3]; z = np.maximum(-c[:, 2], 1e-3)
    u = fx * c[:, 0] / z + cx; v = cy - fy * c[:, 1] / z
    img = frame_img(li).to(dev).permute(2, 0, 1)[None].float()
    if blur: img = torch.nn.functional.avg_pool2d(img, 5, 1, 2, count_include_pad=False)
    gx = torch.tensor(u / (W - 1) * 2 - 1, dtype=torch.float32, device=dev); gy = torch.tensor(v / (H - 1) * 2 - 1, dtype=torch.float32, device=dev)
    out = torch.nn.functional.grid_sample(img, torch.stack([gx, gy], 1)[None, None], align_corners=True, padding_mode='border')[0, :, 0].T
    return out.cpu().numpy()

# ---- 4. seam levelling: offsets g per (vertex, label)
fl = L[:, None].repeat(3, 1)                        # label per face corner
cv_ = F.reshape(-1); cl = fl.reshape(-1); good = cl >= 0
pk = np.unique(cv_[good] * (len(labels) + 1) + np.vectorize(lidx.get)(cl[good]))
pv, pl = pk // (len(labels) + 1), pk % (len(labels) + 1)
nP = len(pk); log(f"seam levelling: {nP} (vertex, frame) pairs")
col = np.zeros((nP, 3))
order = np.argsort(pl); bounds = np.searchsorted(pl[order], np.arange(len(labels) + 1))
for li in range(len(labels)):
    sel = order[bounds[li]:bounds[li + 1]]
    if len(sel): col[sel] = sample(li, V[pv[sel]], blur=True)
# seam constraints: consecutive pairs of the same vertex
sv = pv[1:] == pv[:-1]; ia, ib = np.nonzero(sv)[0], np.nonzero(sv)[0] + 1
dc = np.abs(col[ia] - col[ib]).max(1); keepc = dc < 30            # bigger jumps: misaligned or something in front, not exposure
log(f"seam points: {len(ia)}, {int((~keepc).sum())} dropped as not the same surface colour")
ia, ib = ia[keepc], ib[keepc]
# smoothness: edges of faces with the same label
def pidx(v, l): return np.searchsorted(pk, v * (len(labels) + 1) + l)
fe = np.nonzero(L >= 0)[0]; flab = np.vectorize(lidx.get)(L[fe]) if len(fe) else np.zeros(0, np.int64)
ea, eb = [], []
for (x, y) in ((0, 1), (1, 2), (2, 0)):
    ea.append(pidx(F[fe, x], flab)); eb.append(pidx(F[fe, y], flab))
ea = np.concatenate(ea); eb = np.concatenate(eb)
WS = 1.0; REG = 0.1
nS, nE = len(ia), len(ea)
rr = np.concatenate([np.arange(nS), np.arange(nS), nS + np.arange(nE), nS + np.arange(nE), nS + nE + np.arange(nP)])
cc = np.concatenate([ia, ib, ea, eb, np.arange(nP)])
vv = np.concatenate([np.ones(nS), -np.ones(nS), np.full(nE, WS), np.full(nE, -WS), np.full(nP, REG)])
A = sp.csr_matrix((vv, (rr, cc)), shape=(nS + nE + nP, nP))
G = np.zeros((nP, 3))
for ch in range(3):
    b = np.concatenate([-(col[ia, ch] - col[ib, ch]), np.zeros(nE + nP)])
    G[:, ch] = spl.lsqr(A, b, atol=1e-6, btol=1e-6, iter_lim=3000)[0]
G = np.clip(G, -15, 15)
log(f"seam levelling: {nS} seam points, offsets median {np.median(np.abs(G)):.1f}, 95% {np.percentile(np.abs(G), 95):.1f} (of 255)")

# ---- 5. bake
Fid = np.zeros((AH, AW, 3), np.uint8)
UVp = np.stack([UV[:, 0] * AW - 0.5, (1 - UV[:, 1]) * AH - 0.5], 1)
for f in range(nF):
    c = f + 1; cv2.fillConvexPoly(Fid, np.round(UVp[AF[f]] * 16).astype(np.int32), (c & 255, (c >> 8) & 255, (c >> 16) & 255), cv2.LINE_8, 4)
fid = Fid[..., 0].astype(np.int64) | (Fid[..., 1].astype(np.int64) << 8) | (Fid[..., 2].astype(np.int64) << 16); del Fid
ty, tx = np.nonzero(fid); tf = fid[ty, tx] - 1; del fid
log(f"bake: {len(tf)} texels ({len(tf) / (AW * AH) * 100:.0f}% of the atlas)")
A2 = UVp[AF[tf]]                                     # (n, 3, 2)
p = np.stack([tx, ty], 1).astype(np.float64)
v0, v1 = A2[:, 1] - A2[:, 0], A2[:, 2] - A2[:, 0]; v2 = p - A2[:, 0]
den = v0[:, 0] * v1[:, 1] - v1[:, 0] * v0[:, 1]; den[np.abs(den) < 1e-12] = 1e-12
b1 = (v2[:, 0] * v1[:, 1] - v1[:, 0] * v2[:, 1]) / den; b2 = (v0[:, 0] * v2[:, 1] - v2[:, 0] * v0[:, 1]) / den
B = np.clip(np.stack([1 - b1 - b2, b1, b2], 1), 0, 1); B /= B.sum(1, keepdims=True); del A2, p, v0, v1, v2
TEX = np.zeros((AH, AW, 3), np.uint8); MASK = np.zeros((AH, AW), np.uint8)
tl = L[tf]
un = tl < 0
if un.any():
    c = (VC[F[tf[un]]] * B[un][..., None]).sum(1) * 255
    TEX[ty[un], tx[un]] = np.clip(c, 0, 255).astype(np.uint8); MASK[ty[un], tx[un]] = 255
o = np.argsort(tl, kind='stable'); tls = tl[o]
for li, lv in enumerate(labels):
    a, b = np.searchsorted(tls, lv), np.searchsorted(tls, lv, 'right')
    if a == b: continue
    s = o[a:b]; ff = tf[s]
    P = (V[F[ff]] * B[s][..., None]).sum(1)
    c = sample(li, P)
    gi = pidx(F[ff].reshape(-1), np.full(len(ff) * 3, li)).reshape(-1, 3)
    c += (G[gi] * B[s][..., None]).sum(1)
    TEX[ty[s], tx[s]] = np.clip(c, 0, 255).astype(np.uint8); MASK[ty[s], tx[s]] = 255
log("bake done; filling gutters")
for _ in range(4):
    e = MASK == 0
    if not e.any(): break
    w = cv2.blur((MASK > 0).astype(np.float32), (3, 3))
    s = cv2.blur(TEX.astype(np.float32) * (MASK > 0)[..., None], (3, 3))
    f2 = e & (w > 0)
    TEX[f2] = (s[f2] / w[f2][:, None]).astype(np.uint8); MASK[f2] = 255

cv2.imwrite(os.path.join(odir, 'atlas.jpg'), cv2.cvtColor(TEX, cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, 90])
import trimesh, PIL.Image
im = PIL.Image.open(os.path.join(odir, 'atlas.jpg'))
tm = trimesh.Trimesh(V[vmap], AF, process=False,
                     visual=trimesh.visual.TextureVisuals(uv=UV, material=trimesh.visual.material.PBRMaterial(baseColorTexture=im, metallicFactor=0.0, roughnessFactor=1.0)))
tm.export(os.path.join(odir, 'room.glb'))
json.dump(dict(atlas=[AW, AH], mm_per_texel=round(MM, 2), triangles=nF, frames_scored=nscored, frames_used=len(labels), patch_m=PATCH,
               seen_area=round(float(area[L >= 0].sum() / area.sum()), 3), step=STEP),
          open(os.path.join(odir, 'atlas-info.json'), 'w'), indent=1)
log(f"written {odir}/room.glb ({os.path.getsize(os.path.join(odir, 'room.glb')) / 1e6:.0f} MB)")
