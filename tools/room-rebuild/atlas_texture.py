#!/usr/bin/env python3
"""Paint the whole rebuilt room from single video stills ("the mesh is the drawing board", rk 12 Nov): flat areas
take the plane textures of planes_texture.py, everything else (sofa, chairs, steamer, objects) a texture atlas where
each patch of triangles is a crop of the one best still that sees it. One textured GLB out.

  python3 atlas_texture.py <rebuild dir> <video> <all-frames.csv> <out.glb> --planes <dir with textures.json>
                           [--extra wall-01.json,...] [--step 3] [--K 8] [--lam 0.9] [--atlas 2048] [--reuse]

1. Plane triangles: a triangle near a plane texture (centroid within 6 cm of the plane, facing the same way, on a
   painted texel) is drawn with that texture (planar UVs; the viewer's own rule). --extra textures go first.
2. Every other triangle: every --step-th video frame is scored for it: sharpness at its pixel (and the frame's
   sharpness against the frames around it, so motion blur loses), closeness, head-on, away from the picture border,
   and seen unblocked (a ray from the camera hits it first). The K best frames are kept per triangle.
3. One frame per triangle, smoothed so neighbours share a frame: a few rounds where each triangle takes, among its own
   K frames, the one with the best score + --lam x the share of its neighbours that use it.
4. Patches = connected triangles on the same frame. Each patch's UVs = where its vertices fall in that frame; the crop
   (2 px margin) is copied into an atlas page (--atlas per side). No resampling, no blending: the still's own pixels.
5. Seams: each patch gets one colour gain (per channel) so its colour on its boundary edges matches its neighbours
   (other patches and the plane textures), least squares, outliers dropped (misaligned or occluded edges).
6. Triangles no frame sees keep their vertex colour. Holes stay holes: nothing is added.
The video is read front to back twice (scoring, then the chosen crops), one frame at a time. Yields to Xcode builds.
"""
import sys, os, json, struct, time, subprocess, math, numpy as np, cv2, open3d as o3d
from scipy.sparse import coo_matrix, csr_matrix
from scipy.sparse.csgraph import connected_components
from scipy.sparse.linalg import lsqr

args = sys.argv[1:]
def opt(name, default):
    if name in args: i = args.index(name); v = args[i + 1]; del args[i:i + 2]; return v
    return default
def flag(name):
    if name in args: args.remove(name); return True
    return False
PLANES = opt('--planes', None); EXTRA = opt('--extra', ''); STEP = int(opt('--step', 3)); K = int(opt('--K', 8))
LAM = float(opt('--lam', 0.9)); AS = int(opt('--atlas', 2048)); REUSE = flag('--reuse')
rdir, video, allcsv, outglb = args
work = os.path.splitext(outglb)[0] + '-work'; os.makedirs(work, exist_ok=True)
T0 = time.time()

_last = [0.0]
def yield_to_xcode(every=60):
    if time.time() - _last[0] < every: return
    while subprocess.run(['pgrep', '-f', 'xcodebuild|swift-build|swift-frontend|XCBBuildService|xctest'], capture_output=True).returncode == 0:
        time.sleep(20)
    _last[0] = time.time()

mesh = o3d.io.read_triangle_mesh(os.path.join(rdir, 'room.ply'))
V = np.asarray(mesh.vertices); T = np.asarray(mesh.triangles).astype(np.int64); VC = np.asarray(mesh.vertex_colors)
nT = len(T); P0, P1, P2 = V[T[:, 0]], V[T[:, 1]], V[T[:, 2]]
cen = (P0 + P1 + P2) / 3; cr = np.cross(P1 - P0, P2 - P0); area = np.linalg.norm(cr, axis=1) / 2
Fn = cr / np.maximum(2 * area, 1e-12)[:, None]
RC = o3d.t.geometry.RaycastingScene(); RC.add_triangles(o3d.t.geometry.TriangleMesh.from_legacy(mesh))
print(f"{nT} triangles, {area.sum():.1f} m2")

# triangle adjacency (shared edges)
E = np.concatenate([T[:, [0, 1]], T[:, [1, 2]], T[:, [2, 0]]]); E.sort(1); et = np.tile(np.arange(nT), 3)
key = E[:, 0] * len(V) + E[:, 1]; o = np.argsort(key, kind='stable'); ks = key[o]
same = np.nonzero(ks[1:] == ks[:-1])[0]
pairs = np.stack([et[o][same], et[o][same + 1]], 1); pe = E[o][same]          # adjacent triangle pairs + their edge
print(f"{len(pairs)} shared edges")

# 1. plane triangles
plane_files = [f for f in EXTRA.split(',') if f]
if PLANES: plane_files += [os.path.join(PLANES, n + '.json') for n in json.load(open(os.path.join(PLANES, 'textures.json')))]
owner = np.full(nT, -1)          # >= 0: plane index
planes = []
for f in plane_files:
    info = json.load(open(f)); img = cv2.imread(f[:-5] + '.png', cv2.IMREAD_UNCHANGED)
    o_ = np.array(info['corners'][0]); ex = np.array(info['corners'][1]) - o_; ey = np.array(info['corners'][3]) - o_
    lx, ly = np.linalg.norm(ex), np.linalg.norm(ey); ex /= lx; ey /= ly; nn = np.array(info['normal'])
    r = cen - o_; dn = r @ nn; u = r @ ex / lx; v = r @ ey / ly; H, W = img.shape[:2]
    m = (owner < 0) & (np.abs(dn) < 0.06) & (u > 0) & (u < 1) & (v > 0) & (v < 1) & (np.abs(Fn @ nn) > 0.6)
    k = np.nonzero(m)[0]
    a = img[np.clip(((1 - v[k]) * H).astype(int), 0, H - 1), np.clip((u[k] * W).astype(int), 0, W - 1), 3] > 128
    owner[k[a]] = len(planes)
    planes.append(dict(file=f, o=o_, ex=ex, ey=ey, lx=lx, ly=ly, img=img, kind=info.get('kind', 'wall')))
print(f"plane textures: {len(planes)}; their triangles {(owner >= 0).sum()} ({area[owner >= 0].sum():.1f} m2)")

R = np.nonzero(owner < 0)[0]; nR = len(R); ridx = np.full(nT, -1); ridx[R] = np.arange(nR)
rows = {}
for l in open(allcsv):
    r = l.strip().split(',')
    if r[3] == '2': rows[int(r[0])] = r
def pose(r):
    M = np.array([float(x) for x in r[4:20]]).reshape(4, 4).T; return M, [float(x) for x in r[20:26]]
def project(M, ii, P):
    c = (P - M[:3, 3]) @ M[:3, :3]; z = -c[:, 2]; zz = np.maximum(z, 1e-3)
    return ii[0] * c[:, 0] / zz + ii[2], ii[3] - ii[1] * c[:, 1] / zz, z

# 2. scoring pass
sfile = os.path.join(work, 'scores.npz')
if REUSE and os.path.exists(sfile):
    d = np.load(sfile); bs, bf = d['bs'], d['bf']; print("scores reused")
else:
    bs = np.zeros((nR, K), np.float32); bf = np.full((nR, K), -1, np.int32)
    Cr = cen[R]; Fr = Fn[R]; Gs = []
    cap = cv2.VideoCapture(video); vi = -1; nsc = 0
    while cap.grab():
        vi += 1; yield_to_xcode()
        r = rows.get(vi)
        if r is None or vi % STEP: continue
        M, ii = pose(r); W, H = ii[4], ii[5]; cam = M[:3, 3]
        u, v, z = project(M, ii, Cr)
        k = np.nonzero((u >= 3) & (u < W - 4) & (v >= 3) & (v < H - 4) & (z > 0.25) & (z < 4.5))[0]
        _, img = cap.retrieve()
        g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        lap = cv2.GaussianBlur(np.abs(cv2.Laplacian(g, cv2.CV_32F)), (0, 0), 2.0)
        G = float(lap.mean()); Gs.append(G); b = min(1.3, G / max(np.median(Gs[-45:]), 1e-6))
        if not len(k): continue
        d = Cr[k] - cam; L = np.linalg.norm(d, axis=1); cosv = np.abs((d * Fr[k]).sum(1)) / L
        q = cosv > 0.25; k, d, L, cosv = k[q], d[q], L[q], cosv[q]
        if not len(k): continue
        ray = np.concatenate([np.tile(cam, (len(k), 1)), d / L[:, None]], 1).astype(np.float32)
        hit = RC.cast_rays(o3d.core.Tensor(ray))
        th = hit['t_hit'].numpy(); pid = hit['primitive_ids'].numpy().astype(np.int64)
        vis = (pid == R[k]) | (th > L - 0.02)
        k, cosv = k[vis], cosv[vis]; uu, vv, zz = u[k], v[k], z[k]
        local = lap[vv.astype(int), uu.astype(int)]
        edge = np.minimum.reduce([uu, W - uu, vv, H - vv])
        sc = (b ** 3) * np.sqrt(local + 2) * np.minimum(1.0, 1.0 / zz) ** 1.5 * cosv * np.where(edge < 30, 0.6, 1.0)
        sc = sc.astype(np.float32)
        j = bs[k].argmin(1); mn = bs[k, j]; up = sc > mn
        bs[k[up], j[up]] = sc[up]; bf[k[up], j[up]] = vi; nsc += 1
        if nsc % 500 == 0: print(f"  scored {nsc} frames (video frame {vi}), {time.time() - T0:.0f} s", flush=True)
    cap.release()
    np.savez(sfile, bs=bs, bf=bf); print(f"scoring: {nsc} frames, {time.time() - T0:.0f} s")

# 3. one frame per triangle, smoothed
seen = bf.max(1) >= 0
srel = bs / np.maximum(bs.max(1, keepdims=True), 1e-9); srel[bf < 0] = -1
srel[srel < 0.3] = -1                                     # never take a frame much worse than the triangle's best
lab = bf[np.arange(nR), srel.argmax(1)]
rp = pairs[(ridx[pairs[:, 0]] >= 0) & (ridx[pairs[:, 1]] >= 0)]
a_, b_ = ridx[rp[:, 0]], ridx[rp[:, 1]]
NB = np.full((nR, 6), -1)
# neighbour table (up to 6 per triangle; a skin mesh has <= 3 mostly)
order = np.argsort(np.concatenate([a_, b_]), kind='stable'); src = np.concatenate([a_, b_])[order]; dst = np.concatenate([b_, a_])[order]
first = np.searchsorted(src, np.arange(nR)); pos = np.arange(len(src)) - first[src]; ok = pos < 6
NB[src[ok], pos[ok]] = dst[ok]
rng = np.random.default_rng(0)
for it in range(10):
    nl = np.where(NB >= 0, lab[np.maximum(NB, 0)], -2)                         # neighbour labels
    nn_ = np.maximum((NB >= 0).sum(1), 1)
    vote = (bf[:, :, None] == nl[:, None, :]).sum(2) / nn_[:, None]
    E_ = np.where(srel > 0, srel + LAM * vote, -9)
    new = bf[np.arange(nR), E_.argmax(1)]
    upd = (rng.random(nR) < 0.5) & seen
    ch = (new != lab) & upd; lab[ch] = new[ch]
    print(f"  smoothing round {it + 1}: {ch.sum()} triangles changed frame")
lab[~seen] = -1
print(f"frames chosen: {len(np.unique(lab[seen]))} distinct for {seen.sum()} seen triangles; {(~seen).sum()} unseen")

# 4. patches
same_lab = (lab[a_] == lab[b_]) & (lab[a_] >= 0)
G_ = coo_matrix((np.ones(same_lab.sum()), (a_[same_lab], b_[same_lab])), shape=(nR, nR))
ncomp, comp = connected_components(G_, directed=False)
comp[~seen] = -1
pids = np.unique(comp[seen]); pmap = np.full(ncomp, -1); pmap[pids] = np.arange(len(pids)); patch = np.where(seen, pmap[np.maximum(comp, 0)], -1)
nP = len(pids); plab = np.zeros(nP, int); plab[patch[seen]] = lab[seen]
print(f"{nP} patches (median {np.median(np.bincount(patch[seen])):.0f} triangles)")

# per-patch UVs (pixel coords in its frame) and crop rectangles
TR_ = T[R]; uvpx = np.zeros((nR, 3, 2), np.float32)
byframe = {}
for p in range(nP): byframe.setdefault(int(plab[p]), []).append(p)
ptris = [[] for _ in range(nP)]
for i in np.nonzero(seen)[0]: ptris[patch[i]].append(i)
ptris = [np.array(x) for x in ptris]
PAD = 64; MARG = 2
rect = np.zeros((nP, 4), int)                 # x0, y0, w, h in (padded) frame pixels
for f, ps in byframe.items():
    M, ii = pose(rows[f])
    for p in ps:
        tt = ptris[p]; Pv = V[TR_[tt].reshape(-1)]
        u, v, z = project(M, ii, Pv); u = np.clip(u, -PAD + 1, ii[4] + PAD - 2); v = np.clip(v, -PAD + 1, ii[5] + PAD - 2)
        uvpx[tt] = np.stack([u, v], 1).reshape(-1, 3, 2)
        x0, y0 = int(np.floor(u.min())) - MARG, int(np.floor(v.min())) - MARG
        x1, y1 = int(np.ceil(u.max())) + MARG + 1, int(np.ceil(v.max())) + MARG + 1
        rect[p] = (x0, y0, x1 - x0, y1 - y0)
# shelf packing onto pages
order = np.argsort(-rect[:, 3], kind='stable'); place = np.zeros((nP, 3), int)   # page, x, y
page, x, y, sh = 0, 0, 0, 0
for p in order:
    w, h = rect[p, 2], rect[p, 3]
    if x + w > AS: x = 0; y += sh; sh = 0
    if y + h > AS: page += 1; x, y, sh = 0, 0, 0
    place[p] = (page, x, y); x += w; sh = max(sh, h)
npages = page + 1
print(f"atlas: {npages} page(s) of {AS}x{AS}, crops cover {(rect[:, 2] * rect[:, 3]).sum() / (npages * AS * AS) * 100:.0f}%")

# boundary edges for seam levelling: atlas patch | atlas patch, atlas patch | plane
bnd = []      # (edge midpoint, side A patch, side B patch or -1-planeidx)
for (t1, t2), e in zip(pairs, pe):
    r1, r2 = ridx[t1], ridx[t2]
    pa = patch[r1] if r1 >= 0 else None; pb = patch[r2] if r2 >= 0 else None
    if pa is not None and pa >= 0 and pb is not None and pb >= 0 and pa != pb: bnd.append((e, pa, pb))
    elif pa is not None and pa >= 0 and r2 < 0: bnd.append((e, pa, -1 - owner[t2]))
    elif pb is not None and pb >= 0 and r1 < 0: bnd.append((e, pb, -1 - owner[t1]))
bm = np.array([(V[e[0]] + V[e[1]]) / 2 for e, _, _ in bnd]) if bnd else np.zeros((0, 3))
bA = np.array([a for _, a, _ in bnd], int); bB = np.array([b for _, _, b in bnd], int)
colA = np.full((len(bnd), 3), np.nan); colB = np.full((len(bnd), 3), np.nan)
# plane side: the plane texture's colour there
for pi, pl in enumerate(planes):
    k = np.nonzero(bB == -1 - pi)[0]
    if not len(k): continue
    r = bm[k] - pl['o']; u = r @ pl['ex'] / pl['lx']; v = r @ pl['ey'] / pl['ly']; H, W = pl['img'].shape[:2]
    blur = cv2.GaussianBlur(pl['img'], (0, 0), 3)
    px, py = np.clip((u * W).astype(int), 0, W - 1), np.clip(((1 - v) * H).astype(int), 0, H - 1)
    okp = blur[py, px, 3] > 200; colB[k[okp]] = blur[py[okp], px[okp], :3]

# 5. pass 2: the crops
pages = [np.zeros((AS, AS, 3), np.uint8) for _ in range(npages)]
edges_of_frame = {}
for i, (a, b) in enumerate(zip(bA, bB)):
    edges_of_frame.setdefault(int(plab[a]), []).append((i, 0))
    if b >= 0: edges_of_frame.setdefault(int(plab[b]), []).append((i, 1))
cap = cv2.VideoCapture(video); vi = -1; done = 0
while cap.grab():
    vi += 1; yield_to_xcode()
    if vi not in byframe: continue
    _, img = cap.retrieve(); pad = cv2.copyMakeBorder(img, PAD, PAD, PAD, PAD, cv2.BORDER_REPLICATE)
    for p in byframe[vi]:
        x0, y0, w, h = rect[p]; pg, ax, ay = place[p]
        pages[pg][ay:ay + h, ax:ax + w] = pad[y0 + PAD:y0 + PAD + h, x0 + PAD:x0 + PAD + w]
    if vi in edges_of_frame:
        M, ii = pose(rows[vi]); blur = cv2.GaussianBlur(img, (0, 0), 2)
        ie = np.array(edges_of_frame[vi]); u, v, z = project(M, ii, bm[ie[:, 0]])
        okk = (u >= 0) & (u < ii[4] - 1) & (v >= 0) & (v < ii[5] - 1)
        c = blur[np.clip(v.astype(int), 0, int(ii[5]) - 1), np.clip(u.astype(int), 0, int(ii[4]) - 1)].astype(float)
        for (ei, side), cc, o_k in zip(ie, c, okk):
            if o_k: (colA if side == 0 else colB)[ei] = cc
    done += 1
cap.release()
print(f"crops copied from {done} frames, {time.time() - T0:.0f} s")

# 6. seam levelling: per patch log gain per channel
gain = np.ones((nP, 3))
good = ~np.isnan(colA).any(1) & ~np.isnan(colB).any(1)
if good.sum() > 10:
    la = np.log(colA[good] + 8); lb = np.log(colB[good] + 8); A_ = bA[good]; B_ = bB[good]
    for ch in range(3):
        keep = np.ones(len(A_), bool)
        for rnd in range(3):
            ia = A_[keep]; ib = B_[keep]; d = lb[keep, ch] - la[keep, ch]; ne = len(ia); isp = ib >= 0
            rr = np.concatenate([np.arange(ne), np.arange(ne)[isp], ne + np.arange(nP)])
            cc = np.concatenate([ia, ib[isp], np.arange(nP)])
            vv = np.concatenate([np.ones(ne), -np.ones(isp.sum()), np.full(nP, 0.5)])
            Am = csr_matrix((vv, (rr, cc)), shape=(ne + nP, nP)); bvec = np.concatenate([d, np.zeros(nP)])
            g = lsqr(Am, bvec, atol=1e-6, btol=1e-6, iter_lim=2000)[0]
            res = d - (g[ia] - np.where(isp, g[np.maximum(ib, 0)], 0))
            keep2 = np.ones(len(A_), bool); keep2[np.nonzero(keep)[0]] = np.abs(res) < 0.25
            keep = keep2
        gain[:, ch] = np.exp(np.clip(g, np.log(0.65), np.log(1.5)))
    for p in range(nP):
        x0, y0, w, h = rect[p]; pg, ax, ay = place[p]
        reg = pages[pg][ay:ay + h, ax:ax + w].astype(np.float32)
        # gain on the colour (log(c + 8) space -> multiply (c + 8))
        pages[pg][ay:ay + h, ax:ax + w] = np.clip((reg + 8) * gain[p] - 8, 0, 255).astype(np.uint8)    # all colours here are BGR
    print(f"seam levelling: {good.sum()} boundary samples, gains median {np.round(np.median(gain, 0), 3)}, "
          f"5-95% {np.round(np.percentile(gain, 5), 2).tolist()}..{np.round(np.percentile(gain, 95), 2).tolist()}")

# 7. GLB
def jpg(img_bgr): return cv2.imencode('.jpg', img_bgr, [cv2.IMWRITE_JPEG_QUALITY, 90])[1].tobytes()
def fill_holes(rgba):
    """plane textures: transparent texels take the nearest painted colour (triangles at the edge sample there)"""
    rgb = rgba[..., :3].copy(); a = rgba[..., 3] > 128
    if a.all() or not a.any(): return rgb
    small = cv2.resize(rgb, None, fx=0.25, fy=0.25, interpolation=cv2.INTER_AREA); sa = cv2.resize(a.astype(np.uint8), None, fx=0.25, fy=0.25, interpolation=cv2.INTER_NEAREST) > 0
    filled = small.copy(); m = sa.copy()
    for _ in range(64):
        if m.all(): break
        num = cv2.blur((filled * m[..., None]).astype(np.float32), (3, 3)); den = cv2.blur(m.astype(np.float32), (3, 3))
        nm = (den > 0) & ~m; filled[nm] = (num[nm] / den[nm][:, None]).astype(np.uint8); m |= nm
    big = cv2.resize(filled, (rgb.shape[1], rgb.shape[0]), interpolation=cv2.INTER_LINEAR)
    rgb[~a] = big[~a]; return rgb

bin_ = bytearray(); views = []; accessors = []; meshes_prims = []; images = []; textures = []; materials = []
def add_view(b, target=None):
    while len(bin_) % 4: bin_.append(0)
    off = len(bin_); bin_.extend(b); v = dict(buffer=0, byteOffset=off, byteLength=len(b))
    if target: v['target'] = target
    views.append(v); return len(views) - 1
def add_acc(arr, ctype, typ, target, minmax=False, normalized=False):
    vw = add_view(arr.tobytes(), target); a = dict(bufferView=vw, componentType=ctype, count=len(arr), type=typ)
    if normalized: a['normalized'] = True
    if minmax: a['min'] = arr.min(0).tolist(); a['max'] = arr.max(0).tolist()
    accessors.append(a); return len(accessors) - 1
def add_tex(img_bgr):
    images.append(dict(bufferView=add_view(jpg(img_bgr)), mimeType='image/jpeg'))
    textures.append(dict(source=len(images) - 1, sampler=0))
    materials.append(dict(pbrMetallicRoughness=dict(baseColorTexture=dict(index=len(textures) - 1), metallicFactor=0, roughnessFactor=1),
                          doubleSided=True, extensions=dict(KHR_materials_unlit={})))
    return len(materials) - 1
def add_prim(tris, uv, mat, color=None):
    pos = V[T[tris].reshape(-1)].astype(np.float32)
    attrs = dict(POSITION=add_acc(pos, 5126, 'VEC3', 34962, minmax=True))
    if uv is not None: attrs['TEXCOORD_0'] = add_acc(uv.reshape(-1, 2).astype(np.float32), 5126, 'VEC2', 34962)
    if color is not None: attrs['COLOR_0'] = add_acc(color.astype(np.float32), 5126, 'VEC3', 34962)
    idx = np.arange(len(pos), dtype=np.uint32)
    pr = dict(attributes=attrs, indices=add_acc(idx, 5125, 'SCALAR', 34963))
    if mat is not None: pr['material'] = mat
    meshes_prims.append(pr)

areas = dict(plane=0.0, atlas=0.0, vertex=0.0)
for pi, pl in enumerate(planes):
    tris = np.nonzero(owner == pi)[0]
    if not len(tris): continue
    r = V[T[tris].reshape(-1)] - pl['o']; u = r @ pl['ex'] / pl['lx']; v = r @ pl['ey'] / pl['ly']
    img = fill_holes(pl['img'])
    if max(img.shape[:2]) > 2048: s = 2048 / max(img.shape[:2]); img = cv2.resize(img, None, fx=s, fy=s, interpolation=cv2.INTER_AREA)
    add_prim(tris, np.stack([u, 1 - v], 1), add_tex(img)); areas['plane'] += area[tris].sum()
for pg in range(npages):
    ps = np.nonzero(place[:, 0] == pg)[0]
    if not len(ps): continue
    tl = np.concatenate([ptris[p] for p in ps]); pp = patch[tl]
    off = np.stack([place[pp, 1] - rect[pp, 0], place[pp, 2] - rect[pp, 1]], 1)[:, None, :]
    uv = (uvpx[tl] + off + 0.5) / AS
    add_prim(R[tl], uv, add_tex(pages[pg])); areas['atlas'] += area[R[tl]].sum()
un = R[~seen]
if len(un):
    add_prim(un, None, None, color=VC[T[un].reshape(-1)]); areas['vertex'] += area[un].sum()
gl = dict(asset=dict(version='2.0', generator='shootlocal atlas_texture.py'), scene=0, scenes=[dict(nodes=[0])],
          nodes=[dict(mesh=0)], meshes=[dict(primitives=meshes_prims)], accessors=accessors, bufferViews=views,
          buffers=[dict(byteLength=len(bin_))], images=images, textures=textures, materials=materials,
          samplers=[dict(magFilter=9729, minFilter=9729, wrapS=33071, wrapT=33071)], extensionsUsed=['KHR_materials_unlit'])
js = json.dumps(gl).encode()
while len(js) % 4: js += b' '
while len(bin_) % 4: bin_.append(0)
with open(outglb, 'wb') as f:
    f.write(struct.pack('<III', 0x46546C67, 2, 12 + 8 + len(js) + 8 + len(bin_)))
    f.write(struct.pack('<II', len(js), 0x4E4F534A)); f.write(js)
    f.write(struct.pack('<II', len(bin_), 0x004E4942)); f.write(bin_)
tot = area.sum()
summary = dict(triangles=int(nT), area_m2=round(float(tot), 2),
               plane_share=round(areas['plane'] / tot, 3), atlas_share=round(areas['atlas'] / tot, 3), vertex_share=round(areas['vertex'] / tot, 3),
               plane_triangles=int((owner >= 0).sum()), atlas_triangles=int(seen.sum()), vertex_triangles=int((~seen).sum()),
               patches=int(nP), frames_used=int(len(byframe)), atlas_pages=int(npages), seconds=round(time.time() - T0))
json.dump(summary, open(os.path.join(work, 'summary.json'), 'w'), indent=1)
np.savez(os.path.join(work, 'labels.npz'), R=R, lab=lab, patch=patch, owner=owner)
print(f"{outglb}: {os.path.getsize(outglb) / 1e6:.1f} MB; " + json.dumps(summary))
