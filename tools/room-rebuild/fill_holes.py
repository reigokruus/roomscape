#!/usr/bin/env python3
"""Fill small gaps in a painted room mesh, as a separate layer (rk 12 Nov: "can this be filled in?").

  python fill_holes.py <painted room.glb> <out filled.glb> [--gap 0.30] [--voxel 0.02] [--off 0.03]

The painted room.glb stays untouched. Most gaps in the skin are not closed holes but ragged bays that run out to the
edge of the scan, so closing edge loops does little. Instead, in a 2 cm grid:
1. the surface is marked, then closed (grown by half of --gap, shrunk back): that bridges gaps up to --gap wide and
   leaves wider openings (doorways, under tables, the room's unseen sides) open;
2. of the new cells only those are kept that continue the surface around them: within --off of the plane through the
   nearest painted surface points, whose facings agree. That drops the cells bridging two different things (a sofa
   and the wall behind it), so nothing is joined that wasn't one surface;
3. each kept cell becomes a small tile on that plane, coloured by blending the nearest painted colours: no invented
   detail, just the surrounding colour carried across.
Writes filled.glb (vertex colours, only the fill) and filled-info.json.
"""
import sys, os, json, numpy as np, trimesh
from scipy import ndimage
from scipy.spatial import cKDTree

args = sys.argv[1:]
def opt(name, default):
    if name in args: i = args.index(name); v = args[i + 1]; del args[i:i + 2]; return v
    return default
GAP = float(opt('--gap', 0.30)); VX = float(opt('--voxel', 0.02)); OFF = float(opt('--off', 0.03))
src, out = args
sc = trimesh.load(src); m = sc.to_geometry() if hasattr(sc, 'to_geometry') else sc
tex = np.asarray(m.visual.material.baseColorTexture.convert('RGB')); TH, TW = tex.shape[:2]
# dense painted surface samples: position, normal, colour
n_s = int(m.area / (VX * 0.5) ** 2)
P, fi = trimesh.sample.sample_surface(m, n_s, seed=0)
tri = m.vertices[m.faces[fi]]; uvt = np.asarray(m.visual.uv)[m.faces[fi]]
bc = trimesh.triangles.points_to_barycentric(tri, P); uv = (uvt * bc[..., None]).sum(1)
C = tex[np.clip(((1 - uv[:, 1]) * TH).astype(int), 0, TH - 1), np.clip((uv[:, 0] * TW).astype(int), 0, TW - 1)].astype(np.float32)
N = m.face_normals[fi]
print(f"{len(P)} surface samples over {m.area:.1f} m2")

r = int(round(GAP / 2 / VX)); pad = r + 2
lo = P.min(0) - pad * VX; ijk = np.floor((P - lo) / VX).astype(np.int64); shape = ijk.max(0) + pad + 1
occ = np.zeros(shape, bool); occ[ijk[:, 0], ijk[:, 1], ijk[:, 2]] = True
dil = ndimage.distance_transform_edt(~occ) <= r
closed = ndimage.distance_transform_edt(dil) > r
new = closed & ~ndimage.binary_dilation(occ, iterations=1)      # not the cells right at the surface: those are the surface
X = (np.argwhere(new) + 0.5) * VX + lo
print(f"grid {tuple(shape)}, {occ.sum()} surface cells, {len(X)} candidate fill cells")

tree = cKDTree(P); d, nn = tree.query(X, 12, distance_upper_bound=GAP * 0.75, workers=-1)
ok = np.isfinite(d[:, -1])
X, d, nn = X[ok], d[ok], nn[ok]
Nn = N[nn]; nref = Nn[:, :1]; Nn = Nn * np.sign((Nn * nref).sum(2, keepdims=True) + 1e-9)     # same side
nm = Nn.mean(1); agree = np.linalg.norm(nm, axis=1); nm /= np.maximum(agree[:, None], 1e-9)
off = ((X[:, None, :] - P[nn]) * nm[:, None, :]).sum(2)                                          # signed offsets from the neighbours' planes
keep = (agree > 0.8) & (np.abs(np.median(off, 1)) < OFF) & (off.max(1) - off.min(1) < 2 * OFF)
X, nm, off, d, nn = X[keep], nm[keep], off[keep], d[keep], nn[keep]
Xp = X - np.median(off, 1)[:, None] * nm                                                         # onto the plane
# one tile per 2 cm cell of the projected points
_, first = np.unique(np.floor(Xp / VX).astype(np.int64), axis=0, return_index=True)
Xp, nm, d, nn = Xp[first], nm[first], d[first], nn[first]
# a tile must not sit on painted surface (the closing also thickens it): at least a cell away from every sample
far = tree.query(Xp, 1, workers=-1)[0] > VX * 0.9
Xp, nm, d, nn = Xp[far], nm[far], d[far], nn[far]
w = 1.0 / np.maximum(d, 1e-3) ** 2; col = (C[nn] * w[..., None]).sum(1) / w.sum(1, keepdims=True)
print(f"{len(Xp)} fill tiles ({len(Xp) * VX * VX:.2f} m2)")
if not len(Xp): sys.exit(0)
a = np.cross(nm, np.where(np.abs(nm[:, :1]) < 0.9, [[1.0, 0, 0]], [[0, 1.0, 0]])); a /= np.linalg.norm(a, axis=1, keepdims=True); b = np.cross(nm, a)
h = VX * 0.62
Vq = np.stack([Xp - a * h - b * h, Xp + a * h - b * h, Xp + a * h + b * h, Xp - a * h + b * h], 1).reshape(-1, 3)
k = np.arange(len(Xp)) * 4; Fq = np.concatenate([np.stack([k, k + 1, k + 2], 1), np.stack([k, k + 2, k + 3], 1)])
Cq = np.repeat(np.clip(col, 0, 255).astype(np.uint8), 4, 0)
trimesh.Trimesh(Vq, Fq, vertex_colors=np.concatenate([Cq, np.full((len(Cq), 1), 255, np.uint8)], 1), process=False).export(out)
json.dump(dict(method='grid closing + same-surface test', max_gap_m=GAP, voxel_m=VX, max_offset_m=OFF, tiles=int(len(Xp)),
               filled_area_m2=round(len(Xp) * VX * VX, 2), room_area_m2=round(float(m.area), 1)),
          open(os.path.join(os.path.dirname(out), 'filled-info.json'), 'w'), indent=1)
print(f"written {out}")
