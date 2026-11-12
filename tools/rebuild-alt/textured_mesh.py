"""Option 1: photo-textured mesh from a RoomScape scan dataset.

    python textured_mesh.py DATASET OUT_DIR [--mesh scan-mesh.ply] [--test-every 8]

DATASET is the folder Brush trains on (transforms.json, pictures, points.ply,
optional depth/). The surface comes from, in this order: a mesh you pass with
--mesh (e.g. the app's own), the per-frame depth (TSDF fusion), or the start
points (Poisson). Each triangle is then painted with the best picture of it
(close, head-on, sharp), neighbouring triangles prefer the same picture so
seams are few, and each picture's brightness is levelled against the others.

Writes OUT_DIR/room.glb (one file, opens in Windows 3D Viewer, Blender, the
RoomScape web viewer), room.obj + room.mtl + room_atlas.png, a few held-out
comparisons (eval/*.png: real picture | textured mesh) and report.json with
the held-out PSNR in dB (same idea as Brush's held-out score).

CPU only; needs numpy, scipy, open3d, pillow, plyfile, trimesh.
"""
from __future__ import annotations

import argparse
import os
import time

import numpy as np
import open3d as o3d
from PIL import Image
from scipy import sparse
from scipy.sparse.csgraph import connected_components
from scipy.spatial import cKDTree

from common import Dataset, Frame, load_dataset, project, psnr, save_png, sharpness, side_by_side, write_report


def log(*a):
    print(f"[{time.strftime('%H:%M:%S')}]", *a, flush=True)


# ---------------------------------------------------------------- surface

def mesh_from_depth(ds: Dataset, frames, voxel: float, max_depth: float):
    # Sparse voxel-block TSDF (the legacy ScalableTSDFVolume returned nothing in open3d 0.20 here).
    import open3d.core as o3c
    vbg = o3d.t.geometry.VoxelBlockGrid(
        attr_names=("tsdf", "weight", "color"), attr_dtypes=(o3c.float32, o3c.float32, o3c.float32),
        attr_channels=((1), (1), (3)), voxel_size=voxel, block_resolution=16, block_count=100_000,
        device=o3c.Device("CPU:0"))
    n = 0
    for f in frames:
        d = f.depth()
        if d is None:
            continue
        h, w = d.shape
        fd = f.scaled(w, h)
        col = (f.image() * 255).astype(np.uint8)
        col = np.asarray(Image.fromarray(col).resize((w, h), Image.BILINEAR), np.float32) / 255
        depth = o3d.t.geometry.Image(o3c.Tensor(np.ascontiguousarray(d.astype(np.float32))))
        color = o3d.t.geometry.Image(o3c.Tensor(np.ascontiguousarray(col)))
        K = o3c.Tensor(fd.K, o3c.float64)
        E = o3c.Tensor(np.ascontiguousarray(f.w2c), o3c.float64)
        try:
            blocks = vbg.compute_unique_block_coordinates(depth, K, E, 1.0, max_depth)
            vbg.integrate(blocks, depth, color, K, K, E, 1.0, max_depth)
        except RuntimeError:  # nothing usable in this frame's depth
            continue
        n += 1
    log(f"surface: fused depth from {n} frames (voxel {voxel*100:.1f} cm)")
    return vbg.extract_triangle_mesh().to_legacy()


def mesh_from_points(ds: Dataset, depth: int):
    pcd = o3d.geometry.PointCloud(o3d.utility.Vector3dVector(ds.points.astype(np.float64)))
    pcd = pcd.voxel_down_sample(0.01)
    pcd, _ = pcd.remove_statistical_outlier(20, 2.5)
    pcd.estimate_normals(o3d.geometry.KDTreeSearchParamHybrid(radius=0.08, max_nn=30))
    # Face each normal toward the nearest camera that filmed it.
    pts = np.asarray(pcd.points)
    nrm = np.asarray(pcd.normals)
    cams = np.array([f.center for f in ds.frames])
    _, idx = cKDTree(cams).query(pts)
    flip = np.einsum("ij,ij->i", nrm, cams[idx] - pts) < 0
    nrm[flip] *= -1
    pcd.normals = o3d.utility.Vector3dVector(nrm)
    log(f"surface: Poisson from {len(pts)} points (depth {depth})")
    mesh, dens = o3d.geometry.TriangleMesh.create_from_point_cloud_poisson(pcd, depth=depth)
    dens = np.asarray(dens)
    mesh.remove_vertices_by_mask(dens < np.quantile(dens, 0.03))
    # Poisson closes the surface; drop what is far from any real point.
    v = np.asarray(mesh.vertices)
    dist, _ = cKDTree(pts).query(v)
    mesh.remove_vertices_by_mask(dist > 0.05)
    return mesh


def clean_mesh(mesh, max_tris: int, min_component: int):
    mesh.remove_duplicated_vertices()
    mesh.remove_degenerate_triangles()
    mesh.remove_unreferenced_vertices()
    if len(mesh.triangles) > max_tris:
        mesh = mesh.simplify_quadric_decimation(max_tris)
    ids, counts, _ = mesh.cluster_connected_triangles()
    ids, counts = np.asarray(ids), np.asarray(counts)
    mesh.remove_triangles_by_mask(counts[ids] < min_component)
    mesh.remove_unreferenced_vertices()
    mesh.compute_triangle_normals()
    return mesh


def triangle_neighbours(tris: np.ndarray):
    """Edge pairs (a, b) of triangles sharing an edge."""
    T = len(tris)
    e = np.concatenate([tris[:, [0, 1]], tris[:, [1, 2]], tris[:, [2, 0]]])
    e.sort(1)
    owner = np.tile(np.arange(T), 3)
    key = e[:, 0].astype(np.int64) * (tris.max() + 1) + e[:, 1]
    order = np.argsort(key, kind="stable")
    k, o = key[order], owner[order]
    same = k[1:] == k[:-1]
    return np.stack([o[:-1][same], o[1:][same]], 1)


# ---------------------------------------------------------------- view choice

def score_views(mesh, frames, vis_side: int, min_cos: float):
    tris = np.asarray(mesh.triangles)
    verts = np.asarray(mesh.vertices)
    tn = np.asarray(mesh.triangle_normals)
    cent = verts[tris].mean(1)
    scene = o3d.t.geometry.RaycastingScene()
    scene.add_triangles(o3d.t.geometry.TriangleMesh.from_legacy(mesh))
    rows, cols, vals, samp = [], [], [], []
    sharp = []
    small = []
    for vi, f in enumerate(frames):
        img = f.image(vis_side)
        h, w = img.shape[:2]
        fs = f.scaled(w, h)
        small.append(((img * 255).astype(np.uint8), fs))
        sharp.append(sharpness(img))
        jj, ii = np.meshgrid(np.arange(h) + 0.5, np.arange(w) + 0.5, indexing="ij")
        d = np.stack([(ii - fs.K[0, 2]) / fs.K[0, 0], (jj - fs.K[1, 2]) / fs.K[1, 1], np.ones_like(ii)], -1).reshape(-1, 3)
        d = d @ f.c2w[:3, :3].T
        rays = np.concatenate([np.repeat(f.center[None], len(d), 0), d], 1).astype(np.float32)
        pid = scene.cast_rays(o3d.core.Tensor(rays))["primitive_ids"].numpy().astype(np.int64)
        hit = pid[pid != o3d.t.geometry.RaycastingScene.INVALID_ID]
        if hit.size == 0:
            continue
        t_ids, cnt = np.unique(hit, return_counts=True)
        view = f.center[None] - cent[t_ids]
        dist = np.linalg.norm(view, axis=1)
        cosang = np.einsum("ij,ij->i", tn[t_ids], view) / np.maximum(dist, 1e-9)
        # whole triangle must be inside the picture, else its texture would smear
        u, v, z = project(verts[tris[t_ids]].reshape(-1, 3), fs)
        inside = ((u > 1) & (u < w - 1) & (v > 1) & (v < h - 1) & (z > 0.05)).reshape(-1, 3).all(1)
        good = (np.abs(cosang) >= min_cos) & inside
        # pixels covered ~ closeness x head-on-ness; extra weight on head-on
        s = cnt * np.abs(cosang)
        rows.append(t_ids[good]); cols.append(np.full(good.sum(), vi)); vals.append(s[good])
        cu = np.clip(project(cent[t_ids[good]], fs)[0].astype(int), 0, w - 1)
        cv = np.clip(project(cent[t_ids[good]], fs)[1].astype(int), 0, h - 1)
        samp.append(img[cv, cu])
        if vi % 25 == 0:
            log(f"views: {vi + 1}/{len(frames)}")
    sharp = np.array(sharp)
    sharp_w = np.clip(sharp / np.median(sharp), 0.3, 1.5)  # blurry frames lose
    rows, cols, vals = np.concatenate(rows), np.concatenate(cols), np.concatenate(vals)
    vals = vals * sharp_w[cols]
    S = sparse.csr_matrix((vals, (rows, cols)), shape=(len(tris), len(frames)))
    C = np.concatenate(samp)
    return S, (rows, cols, C), sharp, small


def choose_labels(S, pairs, iters: int, smooth: float):
    S = S.tocoo()
    T = S.shape[0]
    best = np.zeros(T)
    np.maximum.at(best, S.row, S.data)
    data_term = S.data / np.maximum(best[S.row], 1e-12)
    label = np.full(T, -1)
    order = np.lexsort((-data_term, S.row))
    first = np.r_[True, S.row[order][1:] != S.row[order][:-1]]
    label[S.row[order][first]] = S.col[order][first]
    # neighbour table, padded with -1 (edges shared by >2 triangles are rare)
    maxdeg = 3
    N = np.full((T, maxdeg), -1)
    src = np.concatenate([pairs[:, 0], pairs[:, 1]])
    dst = np.concatenate([pairs[:, 1], pairs[:, 0]])
    o = np.argsort(src, kind="stable")
    src, dst = src[o], dst[o]
    start = np.r_[0, np.cumsum(np.bincount(src, minlength=T))[:-1]]
    slot = np.arange(len(src)) - start[src]
    keep = slot < maxdeg
    N[src[keep], slot[keep]] = dst[keep]
    rng = np.random.default_rng(0)
    for it in range(iters):
        nl = np.where(N[S.row] >= 0, label[np.maximum(N[S.row], 0)], -2)
        agree = (nl == S.col[:, None]).sum(1) / maxdeg
        energy = data_term + smooth * agree
        o = np.lexsort((-energy, S.row))
        first = np.r_[True, S.row[o][1:] != S.row[o][:-1]]
        new = label.copy()
        win_t, win_v = S.row[o][first], S.col[o][first]
        # update a random half each round so neighbours don't flip back and forth together
        upd = rng.random(len(win_t)) < 0.5
        new[win_t[upd]] = win_v[upd]
        label = new
    return label


def view_gains(samples, label, n_views):
    rows, cols, C = samples
    T = label.shape[0]
    tot = np.zeros((T, 3)); cnt = np.zeros(T)
    np.add.at(tot, rows, C); np.add.at(cnt, rows, 1)
    mean = tot / np.maximum(cnt, 1)[:, None]
    gains = np.ones((n_views, 3))
    ok = (C.min(1) > 0.04) & (cnt[rows] >= 2)
    for v in range(n_views):
        m = ok & (cols == v)
        if m.sum() > 30:
            gains[v] = np.median(mean[rows[m]] / C[m], axis=0)
    return np.clip(gains, 0.7, 1.4)


# ---------------------------------------------------------------- atlas

def seam_gains(tris, label, comp, pairs, small, gains, reg: float = 0.05):
    """Brightness/colour gain per patch so neighbouring patches from different pictures meet without a step.
    Least squares in log space over the colours both pictures give at the shared vertices."""
    a, b = pairs[:, 0], pairs[:, 1]
    keep = (label[a] >= 0) & (label[b] >= 0) & (comp[a] != comp[b])
    a, b = a[keep], b[keep]
    if len(a) == 0:
        return {}
    eq = tris[a][:, :, None] == tris[b][:, None, :]
    shared = eq.any(2)  # which of a's corners are on the seam
    ai, ci = np.nonzero(shared)
    vid = tris[a][ai, ci]
    ta, tb = a[ai], b[ai]

    def sample(view_ids, points):
        out = np.zeros((len(points), 3))
        for v in np.unique(view_ids):
            m = view_ids == v
            img, fs = small[v]
            # local average (9 x 9 px box) so texture detail and small misalignments don't count as a brightness step
            ii = np.cumsum(np.cumsum(np.pad(img.astype(np.float64), ((1, 0), (1, 0), (0, 0))), 0), 1)
            u, vv, _ = project(points[m], fs)
            h, w = img.shape[:2]
            x0 = np.clip(u.astype(int) - 4, 0, w - 1); x1 = np.clip(u.astype(int) + 5, 1, w)
            y0 = np.clip(vv.astype(int) - 4, 0, h - 1); y1 = np.clip(vv.astype(int) + 5, 1, h)
            area = ((x1 - x0) * (y1 - y0))[:, None]
            box = ii[y1, x1] - ii[y0, x1] - ii[y1, x0] + ii[y0, x0]
            out[m] = box / np.maximum(area, 1) / 255.0 * gains[v]
        return out
    return ta, tb, vid, sample


def solve_chart_gains(mesh, label, comp, pairs, small, gains, reg: float = 0.05):
    tris = np.asarray(mesh.triangles)
    verts = np.asarray(mesh.vertices)
    r = seam_gains(tris, label, comp, pairs, small, gains, reg)
    if not r:
        return {}
    ta, tb, vid, sample = r
    ca = sample(label[ta], verts[vid])
    cb = sample(label[tb], verts[vid])
    ok = (ca.min(1) > 0.03) & (cb.min(1) > 0.03) & (ca.max(1) < 0.98) & (cb.max(1) < 0.98)
    ta, tb, ca, cb = ta[ok], tb[ok], ca[ok], cb[ok]
    ids, inv = np.unique(np.concatenate([comp[ta], comp[tb]]), return_inverse=True)
    n = len(ids)
    ia, ib = inv[: len(ta)], inv[len(ta):]
    m = len(ta)
    from scipy.sparse.linalg import lsqr
    A = sparse.vstack([
        sparse.coo_matrix((np.r_[np.ones(m), -np.ones(m)], (np.r_[np.arange(m), np.arange(m)], np.r_[ia, ib])), shape=(m, n)),
        sparse.identity(n) * reg * np.sqrt(max(m / n, 1.0)),
    ]).tocsr()
    out = np.zeros((n, 3))
    for ch in range(3):
        rhs = np.r_[np.log(cb[:, ch]) - np.log(ca[:, ch]), np.zeros(n)]
        out[:, ch] = lsqr(A, rhs, atol=1e-6, btol=1e-6, iter_lim=2000)[0]
    g = np.exp(np.clip(out, np.log(0.5), np.log(2.0)))
    return dict(zip(ids.tolist(), g))


def build_atlas(mesh, frames, label, gains, ds: Dataset, pairs, max_atlas: int, small=None,
                texel: float = 0.0, level: bool = True, pad: int = 2):
    tris = np.asarray(mesh.triangles)
    verts = np.asarray(mesh.vertices)
    T = len(tris)
    same = label[pairs[:, 0]] == label[pairs[:, 1]]
    p = pairs[same]
    g = sparse.coo_matrix((np.ones(len(p)), (p[:, 0], p[:, 1])), shape=(T, T))
    _, comp = connected_components(g, directed=False)
    cgain = solve_chart_gains(mesh, label, comp, pairs, small, gains) if (level and small) else {}
    if cgain:
        gv = np.array(list(cgain.values()))
        log(f"levelling: {len(cgain)} patches, gains {np.percentile(gv, 5):.2f}..{np.percentile(gv, 95):.2f}")
    charts = {}
    for t, c in enumerate(comp):
        if label[t] >= 0:
            charts.setdefault(c, []).append(t)
    crops = []  # (view, x0, y0, x1, y1, tri ids, pixel uv per corner)
    for c, ts in charts.items():
        ts = np.array(ts)
        f = frames[label[ts[0]]]
        u, v, z = project(verts[tris[ts]].reshape(-1, 3), f)
        uv = np.stack([u, v], 1).reshape(-1, 3, 2)
        # don't store more detail than --texel (metres per texture pixel): close-up pictures are shrunk
        own = min(1.0, (float(np.median(z)) / f.K[0, 0]) / texel) if texel > 0 else 1.0
        x0, y0 = np.floor(uv.reshape(-1, 2).min(0)).astype(int) - pad
        x1, y1 = np.ceil(uv.reshape(-1, 2).max(0)).astype(int) + pad
        x0, y0 = max(x0, 0), max(y0, 0)
        x1, y1 = min(x1, f.width), min(y1, f.height)
        crops.append([label[ts[0]], x0, y0, x1, y1, ts, uv, own, cgain.get(c, np.ones(3))])
    unseen = np.flatnonzero(label < 0)
    # shelf packing; shrink everything if it would not fit
    area = sum((c[3] - c[1]) * (c[4] - c[2]) * c[7] ** 2 for c in crops) + len(unseen) * 9
    size = lambda c, sc: (max(1, int(round((c[3] - c[1]) * c[7] * sc))), max(1, int(round((c[4] - c[2]) * c[7] * sc))))
    scale = min(1.0, 0.85 * max_atlas / np.sqrt(max(area, 1)))
    while True:
        order = sorted(range(len(crops)), key=lambda i: -(crops[i][4] - crops[i][2]))
        x = y = shelf = 0
        place = {}
        for i in order:
            w, h = size(crops[i], scale)
            w, h = w + 1, h + 1
            if x + w > max_atlas:
                x, y, shelf = 0, y + shelf, 0
            place[i] = (x, y, w, h)
            x += w
            shelf = max(shelf, h)
        cells_per_row = max_atlas // 3
        pal_rows = int(np.ceil(len(unseen) / cells_per_row))
        H = y + shelf + pal_rows * 3
        if H <= max_atlas:
            break
        scale *= 0.9
    H = int(2 ** np.ceil(np.log2(max(H, 16))))
    W = max_atlas
    atlas = np.zeros((H, W, 3), np.float32)
    corner_uv = np.zeros((T, 3, 2))
    cache = {}
    for i in sorted(range(len(crops)), key=lambda i: crops[i][0]):  # by picture: each is read once
        vi, x0, y0, x1, y1, ts, uv, own, cg = crops[i]
        if vi not in cache:
            cache = {vi: frames[vi].image() * gains[vi]}
        img = cache[vi]
        px, py, w, h = place[i]
        crop = img[y0:y1, x0:x1] * cg
        cw, chh = size(crops[i], scale)
        if (cw, chh) != (x1 - x0, y1 - y0):
            crop = np.asarray(Image.fromarray((np.clip(crop, 0, 1) * 255).astype(np.uint8)).resize(
                (cw, chh), Image.LANCZOS), np.float32) / 255
        atlas[py:py + crop.shape[0], px:px + crop.shape[1]] = crop[: H - py, : W - px]
        corner_uv[ts] = (uv - [x0, y0]) * [cw / max(x1 - x0, 1), chh / max(y1 - y0, 1)] + [px, py]
    # unseen triangles: colour of the nearest start point
    if len(unseen):
        cent = verts[tris[unseen]].mean(1)
        if len(ds.points):
            _, idx = cKDTree(ds.points).query(cent)
            col = ds.colors[idx]
        else:
            col = np.full((len(unseen), 3), 0.5)
        base = H - pal_rows * 3 if pal_rows else H
        for k, t in enumerate(unseen):
            cx, cy = (k % cells_per_row) * 3, base + (k // cells_per_row) * 3
            atlas[cy:cy + 3, cx:cx + 3] = col[k]
            corner_uv[t] = [cx + 1.5, cy + 1.5]
    return np.clip(atlas, 0, 1), corner_uv, scale, len(charts)


def write_outputs(mesh, atlas, corner_uv, out):
    tris = np.asarray(mesh.triangles)
    verts = np.asarray(mesh.vertices)
    H, W = atlas.shape[:2]
    uvn = np.stack([corner_uv[..., 0] / W, 1 - corner_uv[..., 1] / H], -1).reshape(-1, 2)
    save_png(atlas, os.path.join(out, "room_atlas.png"))
    with open(os.path.join(out, "room.mtl"), "w") as f:
        f.write("newmtl room\nKa 1 1 1\nKd 1 1 1\nKs 0 0 0\nillum 1\nmap_Kd room_atlas.png\n")
    with open(os.path.join(out, "room.obj"), "w") as f:
        f.write("mtllib room.mtl\nusemtl room\n")
        np.savetxt(f, verts, fmt="v %.5f %.5f %.5f")
        np.savetxt(f, uvn, fmt="vt %.6f %.6f")
        idx = np.arange(len(tris) * 3).reshape(-1, 3) + 1
        np.savetxt(f, np.stack([tris[:, 0] + 1, idx[:, 0], tris[:, 1] + 1, idx[:, 1], tris[:, 2] + 1, idx[:, 2]], 1),
                   fmt="f %d/%d %d/%d %d/%d")
    import trimesh
    v3 = verts[tris].reshape(-1, 3)
    tm = trimesh.Trimesh(v3, np.arange(len(v3)).reshape(-1, 3), process=False,
                         visual=trimesh.visual.TextureVisuals(
                             uv=np.stack([corner_uv[..., 0] / W, 1 - corner_uv[..., 1] / H], -1).reshape(-1, 2),
                             image=Image.open(os.path.join(out, "room_atlas.png"))))
    tm.export(os.path.join(out, "room.glb"))


# ---------------------------------------------------------------- held-out check

def render_view(scene, mesh, corner_uv, atlas, f: Frame):
    w, h = f.width, f.height
    jj, ii = np.meshgrid(np.arange(h) + 0.5, np.arange(w) + 0.5, indexing="ij")
    d = np.stack([(ii - f.K[0, 2]) / f.K[0, 0], (jj - f.K[1, 2]) / f.K[1, 1], np.ones_like(ii)], -1).reshape(-1, 3)
    d = d @ f.c2w[:3, :3].T
    rays = np.concatenate([np.repeat(f.center[None], len(d), 0), d], 1).astype(np.float32)
    r = scene.cast_rays(o3d.core.Tensor(rays))
    pid = r["primitive_ids"].numpy().astype(np.int64)
    bary = r["primitive_uvs"].numpy()
    ok = pid != o3d.t.geometry.RaycastingScene.INVALID_ID
    out = np.zeros((h * w, 3), np.float32)
    p = pid[ok]
    b1, b2 = bary[ok, 0], bary[ok, 1]
    uv = corner_uv[p, 0] * (1 - b1 - b2)[:, None] + corner_uv[p, 1] * b1[:, None] + corner_uv[p, 2] * b2[:, None]
    x = np.clip(uv[:, 0] - 0.5, 0, atlas.shape[1] - 1.001)
    y = np.clip(uv[:, 1] - 0.5, 0, atlas.shape[0] - 1.001)
    x0, y0 = x.astype(int), y.astype(int)
    fx, fy = (x - x0)[:, None], (y - y0)[:, None]
    out[ok] = (atlas[y0, x0] * (1 - fx) * (1 - fy) + atlas[y0, x0 + 1] * fx * (1 - fy)
               + atlas[y0 + 1, x0] * (1 - fx) * fy + atlas[y0 + 1, x0 + 1] * fx * fy)
    return out.reshape(h, w, 3), ok.reshape(h, w)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("dataset")
    ap.add_argument("out")
    ap.add_argument("--mesh", help="use this surface instead of building one (ply/obj/glb)")
    ap.add_argument("--test-every", type=int, default=8, help="hold out every n-th picture for the score (0 = none)")
    ap.add_argument("--voxel", type=float, default=0.015, help="depth fusion voxel in metres")
    ap.add_argument("--max-depth", type=float, default=4.0, help="ignore depth beyond this (metres)")
    ap.add_argument("--poisson-depth", type=int, default=10)
    ap.add_argument("--max-tris", type=int, default=400_000)
    ap.add_argument("--vis-side", type=int, default=480, help="picture size used to decide visibility")
    ap.add_argument("--min-cos", type=float, default=0.2, help="skip views more grazing than this")
    ap.add_argument("--smooth", type=float, default=1.2, help="how strongly neighbours share a picture")
    ap.add_argument("--atlas", type=int, default=8192, help="texture size in pixels (square)")
    ap.add_argument("--no-gains", action="store_true", help="skip per-picture brightness levelling")
    ap.add_argument("--no-level", action="store_true", help="skip per-patch seam levelling")
    ap.add_argument("--texel", type=float, default=0.002, help="finest texture detail kept, metres per pixel (0 = full picture)")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    t0 = time.time()
    ds = load_dataset(a.dataset)
    train, test = ds.split(a.test_every)
    log(f"dataset: {len(ds.frames)} pictures ({len(train)} used, {len(test)} held out), {len(ds.points)} points")

    if a.mesh:
        mesh = o3d.io.read_triangle_mesh(a.mesh)
        source = "given mesh"
    elif any(f.depth_path for f in train):
        mesh = mesh_from_depth(ds, train, a.voxel, a.max_depth)
        source = "depth fusion"
    else:
        mesh = mesh_from_points(ds, a.poisson_depth)
        source = "Poisson from points"
    mesh = clean_mesh(mesh, a.max_tris, 200)
    tris = np.asarray(mesh.triangles)
    log(f"surface: {len(tris)} triangles from {source}")
    t_surface = time.time() - t0

    pairs = triangle_neighbours(tris)
    S, samples, sharp, small = score_views(mesh, train, a.vis_side, a.min_cos)
    label = choose_labels(S, pairs, iters=20, smooth=a.smooth)
    gains = np.ones((len(train), 3)) if a.no_gains else view_gains(samples, label, len(train))
    seen = float((label >= 0).mean())
    log(f"views: {seen*100:.1f}% of triangles have a picture, {len(np.unique(label[label >= 0]))} pictures used")
    atlas, corner_uv, scale, n_charts = build_atlas(mesh, train, label, gains, ds, pairs, a.atlas, small,
                                                    a.texel, not a.no_level)
    write_outputs(mesh, atlas, corner_uv, a.out)
    t_tex = time.time() - t0 - t_surface
    log(f"texture: {n_charts} patches, atlas {atlas.shape[1]}x{atlas.shape[0]} at {scale:.2f} of picture resolution")

    scores = []
    if test:
        scene = o3d.t.geometry.RaycastingScene()
        scene.add_triangles(o3d.t.geometry.TriangleMesh.from_legacy(mesh))
        os.makedirs(os.path.join(a.out, "eval"), exist_ok=True)
        for k, f in enumerate(test):
            img = f.image()
            ren, ok = render_view(scene, mesh, corner_uv, atlas, f)
            scores.append((psnr(ren, img), psnr(ren, img, ok), float(ok.mean())))
            if k < 6 or k % 5 == 0:
                save_png(side_by_side([img, ren]), os.path.join(a.out, "eval", f"{k:02d}_{os.path.splitext(f.name)[0]}.png"))
    sc = np.array(scores) if scores else np.zeros((0, 3))
    write_report(os.path.join(a.out, "report.json"), {
        "method": "photo-textured mesh",
        "surface": source,
        "triangles": int(len(tris)),
        "textured_share": round(seen, 4),
        "pictures_used": int(len(np.unique(label[label >= 0]))),
        "atlas": [int(atlas.shape[1]), int(atlas.shape[0])],
        "texture_scale": round(float(scale), 3),
        "heldout_pictures": len(test),
        "heldout_psnr_db": round(float(sc[:, 0].mean()), 2) if len(sc) else None,
        "heldout_psnr_on_surface_db": round(float(sc[:, 1].mean()), 2) if len(sc) else None,
        "heldout_coverage": round(float(sc[:, 2].mean()), 4) if len(sc) else None,
        "seconds_surface": round(t_surface, 1),
        "seconds_texture": round(t_tex, 1),
        "seconds_total": round(time.time() - t0, 1),
        "files": ["room.glb", "room.obj", "room.mtl", "room_atlas.png", "eval/"],
    })


if __name__ == "__main__":
    main()
