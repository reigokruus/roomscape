#!/usr/bin/env python3
"""Check a textured room.glb against the real video: render it from a few scan poses (ray cast, nearest texel) and
write picture | render side by side.

  python preview_glb.py <room.glb> <video> <all-frames.csv> <out dir> [--n 8] [--scale 0.5]
"""
import sys, os, numpy as np, cv2, open3d as o3d, trimesh

args = sys.argv[1:]
def opt(name, default):
    if name in args: i = args.index(name); v = args[i + 1]; del args[i:i + 2]; return v
    return default
NV = int(opt('--n', 8)); SC = float(opt('--scale', 0.5))
glb, video, csv, odir = args; os.makedirs(odir, exist_ok=True)
sc = trimesh.load(glb); m = sc.dump(concatenate=True) if hasattr(sc, 'dump') else sc
V = np.asarray(m.vertices, np.float32); Fc = np.asarray(m.faces, np.int64); UV = np.asarray(m.visual.uv, np.float64)
tex = np.asarray(m.visual.material.baseColorTexture.convert('RGB'))[..., ::-1]; TH, TW = tex.shape[:2]
RC = o3d.t.geometry.RaycastingScene(); RC.add_triangles(o3d.core.Tensor(V), o3d.core.Tensor(Fc.astype(np.uint32)))
rows = [l.strip().split(',') for l in open(csv)]; rows = [r for r in rows if r[3] == '2']
pick = {int(rows[k][0]): rows[k] for k in np.linspace(len(rows) * 0.05, len(rows) * 0.95, NV).astype(int)}
cap = cv2.VideoCapture(video); vi = -1
while cap.grab() and pick:
    vi += 1
    if vi not in pick: continue
    r = pick.pop(vi); img = cap.retrieve()[1]
    M = np.array([float(x) for x in r[4:20]]).reshape(4, 4).T; fx, fy, cx, cy, w, h = [float(x) for x in r[20:26]]
    w2, h2 = int(w * SC), int(h * SC)
    u, v = np.meshgrid((np.arange(w2) + 0.5) / SC, (np.arange(h2) + 0.5) / SC)
    d = np.stack([(u - cx) / fx, -(v - cy) / fy, -np.ones_like(u)], -1).reshape(-1, 3); d /= np.linalg.norm(d, axis=1, keepdims=True)
    ray = np.concatenate([np.tile(M[:3, 3], (len(d), 1)), d @ M[:3, :3].T], 1).astype(np.float32)
    hit = RC.cast_rays(o3d.core.Tensor(ray)); pid = hit['primitive_ids'].numpy().astype(np.int64); bu = hit['primitive_uvs'].numpy()
    ok = pid != RC.INVALID_ID; out = np.zeros((len(d), 3), np.uint8)
    f = Fc[pid[ok]]; b1, b2 = bu[ok, 0], bu[ok, 1]
    uv = UV[f[:, 0]] * (1 - b1 - b2)[:, None] + UV[f[:, 1]] * b1[:, None] + UV[f[:, 2]] * b2[:, None]
    tx = np.clip((uv[:, 0] * TW).astype(int), 0, TW - 1); ty = np.clip(((1 - uv[:, 1]) * TH).astype(int), 0, TH - 1)
    out[ok] = tex[ty, tx]
    pic = cv2.resize(img, (w2, h2), interpolation=cv2.INTER_AREA)
    cv2.imwrite(os.path.join(odir, f'view-{vi:05d}.jpg'), np.hstack([pic, out.reshape(h2, w2, 3)]), [cv2.IMWRITE_JPEG_QUALITY, 88])
print('done', odir)
