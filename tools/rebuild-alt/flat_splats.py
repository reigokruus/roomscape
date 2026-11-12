"""Option 2: depth-guided flat splats (2D Gaussian splatting) from a RoomScape scan dataset.

    python flat_splats.py DATASET OUT_DIR [--steps 15000] [--max-splats 400000] [--test-every 8]

Same input as Brush (transforms.json, pictures, points.ply, optional depth/).
Differences from Brush:
  * every splat is a flat disc (2DGS), so walls, floors and tables come out as
    real flat surfaces instead of a cloud of blobs;
  * the phone's depth is a training target: per-frame depth when the dataset has
    it, otherwise the start points drawn into each view (sparse depth);
  * a normal-consistency and a distortion loss keep the discs on the surface.
It also writes a mesh fused from the rendered depth (the "bonus mesh").

Writes OUT_DIR/splats.ply (opens in Brush's viewer and other 3DGS viewers; the
discs are written as very thin 3D splats), mesh.ply, eval/*.png (real | render)
and report.json with the held-out PSNR (comparable to Brush's held-out dB).

Needs an NVIDIA GPU: torch with CUDA, gsplat (1.5.x), plus numpy, pillow,
plyfile, open3d (for the mesh).
"""
from __future__ import annotations

import argparse
import math
import os
import time

import numpy as np
import torch
import torch.nn.functional as F

from common import Frame, load_dataset, project, psnr, save_png, side_by_side, write_report


def log(*a):
    print(f"[{time.strftime('%H:%M:%S')}]", *a, flush=True)


C0 = 0.28209479177387814


def rgb_to_sh(rgb):
    return (rgb - 0.5) / C0


# ---------------------------------------------------------------- data

def train_size(f: Frame, max_side: int):
    s = min(1.0, max_side / max(f.width, f.height))
    return max(1, round(f.width * s)), max(1, round(f.height * s))


def depth_target(f: Frame, w: int, h: int, points: np.ndarray):
    """Depth map at w x h: the phone's own depth if there is one, else the
    start points splatted into the view (nearest wins), 0 = unknown."""
    d = f.depth()
    if d is not None:
        t = torch.from_numpy(d)[None, None]
        t = F.interpolate(t, size=(h, w), mode="nearest")[0, 0]
        return t.numpy(), "phone depth"
    fs = f.scaled(w, h)
    u, v, z = project(points, fs)
    ok = (z > 0.05) & (u >= 0) & (u < w) & (v >= 0) & (v < h)
    ui, vi, z = u[ok].astype(int), v[ok].astype(int), z[ok]
    out = np.full(h * w, np.inf, np.float32)
    np.minimum.at(out, vi * w + ui, z)
    out[~np.isfinite(out)] = 0
    out = out.reshape(h, w)
    # a point seen through a gap in nearer points is hidden: drop depths far behind their neighbourhood minimum
    t = torch.from_numpy(np.where(out > 0, out, 1e3))[None, None]
    near = -F.max_pool2d(-t, 7, 1, 3)[0, 0].numpy()
    out[(out > 0) & (out > near * 1.15 + 0.05)] = 0
    return out, "start points"


class Views:
    def __init__(self, frames, points, max_side, device):
        self.items = []
        kinds = set()
        for f in frames:
            w, h = train_size(f, max_side)
            fs = f.scaled(w, h)
            img = torch.from_numpy(f.image(max(w, h)))
            if img.shape[:2] != (h, w):
                img = F.interpolate(img.permute(2, 0, 1)[None], size=(h, w), mode="bilinear", align_corners=False)[0].permute(1, 2, 0)
            d, kind = depth_target(f, w, h, points)
            kinds.add(kind)
            self.items.append(dict(
                name=f.name, w=w, h=h,
                image=img.to(device),
                depth=torch.from_numpy(d).to(device),
                K=torch.tensor(fs.K, dtype=torch.float32, device=device),
                w2c=torch.tensor(f.w2c, dtype=torch.float32, device=device)))
        self.depth_kind = ", ".join(sorted(kinds))


# ---------------------------------------------------------------- model

def init_splats(points, colors, max_init, sh_degree, scene_scale, device):
    if len(points) > max_init:
        sel = np.random.default_rng(0).choice(len(points), max_init, replace=False)
        points, colors = points[sel], colors[sel]
    pts = torch.tensor(points, dtype=torch.float32, device=device)
    rgb = torch.tensor(colors, dtype=torch.float32, device=device)
    # mean distance to 3 nearest neighbours, in chunks
    d2 = []
    for i in range(0, len(pts), 4096):
        dd = torch.cdist(pts[i:i + 4096], pts)
        d2.append(dd.topk(4, largest=False).values[:, 1:].pow(2).mean(1))
    dist = torch.cat(d2).sqrt().clamp(1e-4, 0.1)
    N = len(pts)
    sh = torch.zeros(N, (sh_degree + 1) ** 2, 3, device=device)
    sh[:, 0] = rgb_to_sh(rgb)
    params = torch.nn.ParameterDict({
        "means": torch.nn.Parameter(pts),
        "scales": torch.nn.Parameter(torch.log(dist)[:, None].repeat(1, 3)),
        "quats": torch.nn.Parameter(torch.rand(N, 4, device=device)),
        "opacities": torch.nn.Parameter(torch.logit(torch.full((N,), 0.1, device=device))),
        "sh0": torch.nn.Parameter(sh[:, :1].contiguous()),
        "shN": torch.nn.Parameter(sh[:, 1:].contiguous()),
    })
    lrs = {"means": 1.6e-4 * scene_scale, "scales": 5e-3, "quats": 1e-3, "opacities": 5e-2,
           "sh0": 2.5e-3, "shN": 2.5e-3 / 20}
    opts = {k: torch.optim.Adam([{"params": params[k], "lr": lr, "name": k}], eps=1e-15) for k, lr in lrs.items()}
    return params, opts


def render(params, view, sh_degree, mode="RGB+ED", distloss=False):
    from gsplat import rasterization_2dgs
    colors = torch.cat([params["sh0"], params["shN"]], 1)
    return rasterization_2dgs(
        means=params["means"], quats=params["quats"], scales=torch.exp(params["scales"]),
        opacities=torch.sigmoid(params["opacities"]), colors=colors,
        viewmats=view["w2c"][None], Ks=view["K"][None], width=view["w"], height=view["h"],
        sh_degree=sh_degree, near_plane=0.05, far_plane=100.0, render_mode=mode, distloss=distloss,
        backgrounds=torch.zeros((1, 3), device=params["means"].device))  # gsplat 1.5.0 crashes on None with RGB+ED


def ssim(a, b):
    """a, b: [1, 3, H, W]. Plain 11x11 Gaussian SSIM."""
    g = torch.exp(-((torch.arange(11, device=a.device) - 5.0) ** 2) / (2 * 1.5 ** 2))
    g = (g / g.sum())
    win = (g[:, None] * g[None, :])[None, None].repeat(3, 1, 1, 1)
    f = lambda x: F.conv2d(x, win, padding=5, groups=3)
    mu_a, mu_b = f(a), f(b)
    s_a, s_b, s_ab = f(a * a) - mu_a ** 2, f(b * b) - mu_b ** 2, f(a * b) - mu_a * mu_b
    c1, c2 = 0.01 ** 2, 0.03 ** 2
    return (((2 * mu_a * mu_b + c1) * (2 * s_ab + c2)) / ((mu_a ** 2 + mu_b ** 2 + c1) * (s_a + s_b + c2))).mean()


# ---------------------------------------------------------------- export

def save_ply(params, path, sh_degree):
    from plyfile import PlyData, PlyElement
    m = params["means"].detach().cpu().numpy()
    s = params["scales"].detach().cpu().numpy().copy()
    # 2DGS only uses the first two scales; the third (thickness) is written as 1% of the smaller one
    s[:, 2] = np.log(np.exp(s[:, :2]).min(1) * 0.01 + 1e-7)
    q = F.normalize(params["quats"].detach(), dim=-1).cpu().numpy()
    o = params["opacities"].detach().cpu().numpy()
    dc = params["sh0"].detach().cpu().numpy().reshape(len(m), 3)
    rest = params["shN"].detach().cpu().numpy()  # N, K-1, 3
    rest = rest.transpose(0, 2, 1).reshape(len(m), -1)  # channel-major, as 3DGS files store it
    names = ["x", "y", "z", "nx", "ny", "nz"] + [f"f_dc_{i}" for i in range(3)] + \
            [f"f_rest_{i}" for i in range(rest.shape[1])] + ["opacity"] + \
            [f"scale_{i}" for i in range(3)] + [f"rot_{i}" for i in range(4)]
    data = np.concatenate([m, np.zeros_like(m), dc, rest, o[:, None], s, q], 1).astype(np.float32)
    v = np.empty(len(m), dtype=[(n, "f4") for n in names])
    for i, n in enumerate(names):
        v[n] = data[:, i]
    PlyData([PlyElement.describe(v, "vertex")]).write(path)


@torch.no_grad()
def fuse_mesh(params, views, sh_degree, voxel, max_depth, path):
    import open3d as o3d
    import open3d.core as o3c
    vbg = o3d.t.geometry.VoxelBlockGrid(
        attr_names=("tsdf", "weight", "color"), attr_dtypes=(o3c.float32, o3c.float32, o3c.float32),
        attr_channels=((1), (1), (3)), voxel_size=voxel, block_resolution=16, block_count=100_000,
        device=o3c.Device("CPU:0"))
    used = 0
    for v in views.items:
        out = render(params, v, sh_degree, mode="RGB+D")
        rgbd, alpha, median = out[0][0], out[1][0], out[5][0]
        rgb = rgbd[..., :3].clamp(0, 1).cpu().numpy()
        d = median[..., 0].cpu().numpy() if median is not None else rgbd[..., 3].cpu().numpy()
        d = np.where(alpha[..., 0].cpu().numpy() > 0.5, d, 0).astype(np.float32)
        depth = o3d.t.geometry.Image(o3c.Tensor(np.ascontiguousarray(d)))
        color = o3d.t.geometry.Image(o3c.Tensor(np.ascontiguousarray(rgb.astype(np.float32))))
        K = o3c.Tensor(v["K"].cpu().numpy().astype(np.float64))
        E = o3c.Tensor(v["w2c"].cpu().numpy().astype(np.float64))
        if (d > 0).sum() < 100:
            continue
        try:
            blocks = vbg.compute_unique_block_coordinates(depth, K, E, 1.0, max_depth)
            vbg.integrate(blocks, depth, color, K, K, E, 1.0, max_depth)
        except RuntimeError:
            continue
        used += 1
    if used == 0:
        print("mesh: no view had enough solid depth, skipped")
        return 0
    mesh = vbg.extract_triangle_mesh().to_legacy()
    ids, counts, _ = mesh.cluster_connected_triangles()
    ids, counts = np.asarray(ids), np.asarray(counts)
    mesh.remove_triangles_by_mask(counts[ids] < 200)
    mesh.remove_unreferenced_vertices()
    o3d.io.write_triangle_mesh(path, mesh)
    return len(mesh.triangles)


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("dataset")
    ap.add_argument("out")
    ap.add_argument("--steps", type=int, default=15000)
    ap.add_argument("--max-splats", type=int, default=400_000, help="stop growing at this many splats")
    ap.add_argument("--max-side", type=int, default=960, help="training picture size (long side)")
    ap.add_argument("--sh-degree", type=int, default=1)
    ap.add_argument("--test-every", type=int, default=8)
    ap.add_argument("--depth-weight", type=float, default=0.2, help="0 = no depth guide (plain 2DGS)")
    ap.add_argument("--normal-weight", type=float, default=0.05)
    ap.add_argument("--dist-weight", type=float, default=0.01)
    ap.add_argument("--max-init", type=int, default=200_000, help="start points used")
    ap.add_argument("--no-mesh", action="store_true")
    ap.add_argument("--voxel", type=float, default=0.015)
    ap.add_argument("--device", default="cuda", help=argparse.SUPPRESS)  # cpu only for the smoke test
    a = ap.parse_args()
    from gsplat.strategy import DefaultStrategy

    if a.device == "cuda":
        assert torch.cuda.is_available(), "needs an NVIDIA GPU with CUDA (torch.cuda.is_available() is False)"
    dev = torch.device(a.device)
    os.makedirs(os.path.join(a.out, "eval"), exist_ok=True)
    t0 = time.time()
    ds = load_dataset(a.dataset)
    train, test = ds.split(a.test_every)
    tv = Views(train, ds.points, a.max_side, dev)
    centers = np.array([f.center for f in ds.frames])
    scene_scale = float(np.linalg.norm(centers - centers.mean(0), axis=1).max()) * 1.1
    log(f"dataset: {len(train)} training pictures at {tv.items[0]['w']}x{tv.items[0]['h']}, {len(test)} held out, "
        f"depth guide from {tv.depth_kind}, scene radius {scene_scale:.2f} m")

    params, opts = init_splats(ds.points, ds.colors, a.max_init, a.sh_degree, scene_scale, dev)
    refine_stop = int(a.steps * 0.5)
    strategy = DefaultStrategy(
        prune_opa=0.05, grow_grad2d=0.0002, grow_scale3d=0.01, prune_scale3d=0.1,
        refine_start_iter=500, refine_stop_iter=refine_stop, reset_every=3000, refine_every=100,
        key_for_gradient="gradient_2dgs", verbose=False)
    strategy.check_sanity(params, opts)
    state = strategy.initialize_state(scene_scale=scene_scale)
    sched = torch.optim.lr_scheduler.ExponentialLR(opts["means"], gamma=0.01 ** (1.0 / a.steps))
    rng = np.random.default_rng(0)
    order = []
    for step in range(a.steps):
        if not order:
            order = list(rng.permutation(len(tv.items)))
        v = tv.items[order.pop()]
        sh = min(step // 1000, a.sh_degree)
        dist_on = a.dist_weight > 0 and step > a.steps * 0.2
        rc, ra, rn, rnd, rdist, rmed, info = render(params, v, sh, "RGB+ED", distloss=dist_on)
        rgb, dep = rc[0, ..., :3], rc[0, ..., 3]
        strategy.step_pre_backward(params, opts, state, step, info)
        gt = v["image"]
        loss = 0.8 * (rgb - gt).abs().mean() + 0.2 * (1 - ssim(rgb.permute(2, 0, 1)[None], gt.permute(2, 0, 1)[None]))
        if a.depth_weight > 0:
            m = v["depth"] > 0
            if m.any():
                # relative error, so far walls count as much as near ones
                dl = ((dep[m] - v["depth"][m]).abs() / v["depth"][m]).mean()
                loss = loss + a.depth_weight * dl * (1.0 if step < a.steps * 0.6 else 0.5)
        if a.normal_weight > 0 and step > a.steps * 0.3:
            n = rn[0]
            nd = (rnd[0] if rnd.dim() == 4 else rnd) * ra[0].detach()
            loss = loss + a.normal_weight * (1 - (n * nd).sum(-1)).mean()
        if dist_on:
            loss = loss + a.dist_weight * rdist.mean()
        loss.backward()
        strategy.step_post_backward(params, opts, state, step, info, packed=False)
        for o in opts.values():
            o.step()
            o.zero_grad(set_to_none=True)
        sched.step()
        if len(params["means"]) >= a.max_splats and strategy.refine_stop_iter > step:
            strategy.refine_stop_iter = step  # splat budget reached
            log(f"step {step}: reached {len(params['means'])} splats, growth stopped")
        if step % 500 == 0 or step == a.steps - 1:
            log(f"step {step}/{a.steps}  loss {loss.item():.4f}  splats {len(params['means'])}  "
                f"{(step + 1) / (time.time() - t0):.1f} steps/s")
    t_train = time.time() - t0
    save_ply(params, os.path.join(a.out, "splats.ply"), a.sh_degree)

    scores = []
    with torch.no_grad():
        ev = Views(test, ds.points, a.max_side, dev) if test else None
        for k, v in enumerate(ev.items if ev else []):
            rc = render(params, v, a.sh_degree, mode="RGB")[0][0].clamp(0, 1).cpu().numpy()
            gt = v["image"].cpu().numpy()
            scores.append(psnr(rc, gt))
            if k < 6 or k % 5 == 0:
                save_png(side_by_side([gt, rc]), os.path.join(a.out, "eval", f"{k:02d}_{os.path.splitext(v['name'])[0]}.png"))
    tris = None
    if not a.no_mesh:
        tris = fuse_mesh(params, tv, a.sh_degree, a.voxel, 6.0, os.path.join(a.out, "mesh.ply"))
    write_report(os.path.join(a.out, "report.json"), {
        "method": "depth-guided flat splats (2DGS)",
        "depth_guide": tv.depth_kind if a.depth_weight > 0 else "off",
        "steps": a.steps,
        "splats": int(len(params["means"])),
        "train_resolution": [tv.items[0]["w"], tv.items[0]["h"]],
        "heldout_pictures": len(scores),
        "heldout_psnr_db": round(float(np.mean(scores)), 2) if scores else None,
        "mesh_triangles": tris,
        "seconds_training": round(t_train, 1),
        "seconds_total": round(time.time() - t0, 1),
        "files": ["splats.ply", "mesh.ply", "eval/"],
    })


if __name__ == "__main__":
    main()
