"""Make a small synthetic room in the same layout the phones export for Brush.

    python make_test_room.py OUT_DIR [--frames 80] [--size 480x360] [--pose-noise 0.01] [--depth]

A 4 x 3 x 2.6 m box room with a patterned floor, posters on the walls and a
table, filmed by a camera walking a loop. Writes images/, transforms.json
(Nerfstudio, OpenGL cameras), points.ply (sampled like the phone's start points)
and, with --depth, depth/ (16-bit PNG in mm). Used to check the three tools
here without a real (private) scan.
"""
import argparse
import json
import math
import os

import numpy as np
import open3d as o3d
from PIL import Image

from common import GL_TO_CV

ROOM = np.array([4.0, 2.6, 3.0])  # x, y (up), z


def box(center, size):
    m = o3d.geometry.TriangleMesh.create_box(*size)
    m.translate(np.asarray(center) - np.asarray(size) / 2)
    return m


def room_mesh():
    parts = []
    t = 0.05
    W, H, D = ROOM
    parts.append(box([W / 2, -t / 2, D / 2], [W, t, D]))          # floor
    parts.append(box([W / 2, H + t / 2, D / 2], [W, t, D]))       # ceiling
    parts.append(box([-t / 2, H / 2, D / 2], [t, H, D]))          # walls
    parts.append(box([W + t / 2, H / 2, D / 2], [t, H, D]))
    parts.append(box([W / 2, H / 2, -t / 2], [W, H, t]))
    parts.append(box([W / 2, H / 2, D + t / 2], [W, H, t]))
    parts.append(box([2.6, 0.72, 1.0], [1.2, 0.04, 0.7]))         # table top
    for dx in (-0.55, 0.55):
        for dz in (-0.3, 0.3):
            parts.append(box([2.6 + dx, 0.35, 1.0 + dz], [0.04, 0.7, 0.04]))
    parts.append(box([0.6, 0.45, 2.4], [0.5, 0.9, 0.5]))          # a cabinet
    m = parts[0]
    for p in parts[1:]:
        m += p
    return m


def shade(p, n):
    """Procedural colour from world position and normal (N x 3 each)."""
    x, y, z = p[:, 0], p[:, 1], p[:, 2]
    c = np.full_like(p, 0.82)
    floor = n[:, 1] > 0.9
    plank = np.floor(x / 0.18)
    grain = 0.06 * np.sin(z * 40 + plank * 3.1) + 0.05 * ((plank * 7.3) % 1)
    c[floor] = (np.stack([0.55 + grain, 0.38 + grain, 0.22 + grain * 0.5], 1))[floor]
    ceil = n[:, 1] < -0.9
    c[ceil] = 0.92
    wall = ~floor & ~ceil & (y > 0.02) & (y < ROOM[1] - 0.02)
    u = np.where(np.abs(n[:, 0]) > 0.5, z, x)
    poster = wall & (y > 1.1) & (y < 1.9) & ((np.floor(u / 0.9) % 2) == 0)
    stripes = 0.5 + 0.4 * np.sin(u * 23) * np.cos(y * 17)
    c[poster] = np.stack([stripes, 0.3 + 0.5 * (u % 0.3) / 0.3, 1 - stripes], 1)[poster]
    skirting = wall & (y < 0.1)
    c[skirting] = 0.95
    table = (y > 0.69) & (y < 0.75) & (x > 1.95) & (x < 3.25)
    c[table] = np.stack([0.2 + 0.1 * np.sin(x * 60), 0.25 + 0 * x, 0.3 + 0 * x], 1)[table]
    return np.clip(c, 0, 1)


def look_at(eye, target, up=np.array([0, 1.0, 0])):
    f = target - eye
    f /= np.linalg.norm(f)
    r = np.cross(f, up)
    r /= np.linalg.norm(r)
    u = np.cross(r, f)
    c2w_gl = np.eye(4)
    c2w_gl[:3, 0], c2w_gl[:3, 1], c2w_gl[:3, 2], c2w_gl[:3, 3] = r, u, -f, eye
    return c2w_gl


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--frames", type=int, default=80)
    ap.add_argument("--size", default="480x360")
    ap.add_argument("--pose-noise", type=float, default=0.0, help="metres of pose error, like ARCore drift")
    ap.add_argument("--depth", action="store_true")
    ap.add_argument("--points", type=int, default=30000)
    a = ap.parse_args()
    W, H = map(int, a.size.split("x"))
    os.makedirs(os.path.join(a.out, "images"), exist_ok=True)
    if a.depth:
        os.makedirs(os.path.join(a.out, "depth"), exist_ok=True)
    mesh = room_mesh()
    scene = o3d.t.geometry.RaycastingScene()
    scene.add_triangles(o3d.t.geometry.TriangleMesh.from_legacy(mesh))
    tris = np.asarray(mesh.triangles)
    mesh.compute_triangle_normals()
    tri_n = np.asarray(mesh.triangle_normals)
    fx = 0.5 * W / math.tan(math.radians(68) / 2)
    rng = np.random.default_rng(1)
    frames, pts_all, col_all = [], [], []
    for i in range(a.frames):
        t = 2 * math.pi * i / a.frames
        eye = np.array([2.0 + 0.9 * math.cos(t), 1.45 + 0.1 * math.sin(3 * t), 1.5 + 0.6 * math.sin(t)])
        look = t + math.pi * 0.5 + 0.4 * math.sin(2 * t)
        target = eye + np.array([math.cos(look), -0.15 + 0.2 * math.sin(t * 1.5), math.sin(look)])
        c2w_gl = look_at(eye, target)
        c2w = c2w_gl @ GL_TO_CV
        jj, ii = np.meshgrid(np.arange(H) + 0.5, np.arange(W) + 0.5, indexing="ij")
        d = np.stack([(ii - W / 2) / fx, (jj - H / 2) / fx, np.ones_like(ii)], -1).reshape(-1, 3)
        d = d @ c2w[:3, :3].T
        d /= np.linalg.norm(d, axis=1, keepdims=True)
        rays = np.concatenate([np.repeat(eye[None], len(d), 0), d], 1).astype(np.float32)
        hit = scene.cast_rays(o3d.core.Tensor(rays))
        tt = hit["t_hit"].numpy()
        pid = hit["primitive_ids"].numpy()
        ok = np.isfinite(tt)
        p = eye + d * np.where(ok, tt, 0)[:, None]
        n = tri_n[np.where(ok, pid, 0)]
        col = shade(p, n)
        lam = np.clip(0.75 + 0.25 * (n @ np.array([0.3, 0.8, 0.5])), 0.4, 1.0)  # soft light
        col = col * lam[:, None]
        img = np.where(ok[:, None], col, 0).reshape(H, W, 3)
        name = f"images/frame_{i:04d}.png"
        Image.fromarray((img * 255).astype(np.uint8)).save(os.path.join(a.out, name))
        fr = {"file_path": name}
        if a.depth:
            zc = (tt * (d @ c2w[:3, 2])).reshape(H, W)
            dm = np.where(np.isfinite(zc), zc * 1000, 0).astype(np.uint16)
            dn = f"depth/frame_{i:04d}.png"
            Image.fromarray(dm).save(os.path.join(a.out, dn))
            fr["depth_file_path"] = dn
        # recorded pose carries the phone's error, the picture does not
        noisy = c2w_gl.copy()
        if a.pose_noise > 0:
            noisy[:3, 3] += rng.normal(0, a.pose_noise, 3)
        fr["transform_matrix"] = noisy.tolist()
        frames.append(fr)
        sel = rng.choice(np.flatnonzero(ok), size=min(a.points // a.frames, ok.sum()), replace=False)
        pts_all.append(p[sel] + rng.normal(0, 0.006, (len(sel), 3)))
        col_all.append(img.reshape(-1, 3)[sel])
    tf = {"fl_x": fx, "fl_y": fx, "cx": W / 2, "cy": H / 2, "w": W, "h": H,
          "camera_model": "OPENCV", "frames": frames, "ply_file_path": "points.ply"}
    with open(os.path.join(a.out, "transforms.json"), "w") as f:
        json.dump(tf, f, indent=1)
    pts = np.concatenate(pts_all).astype(np.float32)
    cols = (np.concatenate(col_all) * 255).astype(np.uint8)
    from plyfile import PlyData, PlyElement
    v = np.empty(len(pts), dtype=[("x", "f4"), ("y", "f4"), ("z", "f4"), ("red", "u1"), ("green", "u1"), ("blue", "u1")])
    v["x"], v["y"], v["z"] = pts.T
    v["red"], v["green"], v["blue"] = cols.T
    PlyData([PlyElement.describe(v, "vertex")]).write(os.path.join(a.out, "points.ply"))
    o3d.io.write_triangle_mesh(os.path.join(a.out, "truth-mesh.ply"), mesh)
    print(f"wrote {a.frames} frames and {len(pts)} points to {a.out}")


if __name__ == "__main__":
    main()
