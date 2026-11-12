"""Shared helpers for the three Brush alternatives (tools/rebuild-alt).

Input is the same dataset Brush trains on: a Nerfstudio-style folder with
transforms.json, the pictures it lists and the start points (points.ply).
Optional per-frame depth (depth_file_path, 16-bit PNG in millimetres) is used
when present.

Conventions inside these tools: OpenCV cameras (x right, y down, z forward),
world-to-camera 4x4 matrices called w2c, camera-to-world called c2w.
"""
from __future__ import annotations

import json
import math
import os
from dataclasses import dataclass, field
from typing import List, Optional

import numpy as np
from PIL import Image


@dataclass
class Frame:
    name: str            # file name, used in reports
    image_path: str
    K: np.ndarray        # 3x3, for the picture as it is on disk
    c2w: np.ndarray      # 4x4, OpenCV convention
    width: int
    height: int
    depth_path: Optional[str] = None
    depth_scale: float = 0.001  # metres per stored unit

    @property
    def w2c(self) -> np.ndarray:
        return np.linalg.inv(self.c2w)

    @property
    def center(self) -> np.ndarray:
        return self.c2w[:3, 3]

    def image(self, max_side: Optional[int] = None) -> np.ndarray:
        """RGB float32 0..1, optionally downscaled so the long side is max_side."""
        im = Image.open(self.image_path).convert("RGB")
        if max_side and max(im.size) > max_side:
            s = max_side / max(im.size)
            im = im.resize((round(im.size[0] * s), round(im.size[1] * s)), Image.LANCZOS)
        return np.asarray(im, dtype=np.float32) / 255.0

    def depth(self) -> Optional[np.ndarray]:
        if not self.depth_path or not os.path.exists(self.depth_path):
            return None
        d = np.asarray(Image.open(self.depth_path)).astype(np.float32) * self.depth_scale
        return d

    def scaled(self, w: int, h: int) -> "Frame":
        """Same camera for a picture resized to w x h."""
        K = self.K.copy()
        K[0] *= w / self.width
        K[1] *= h / self.height
        return Frame(self.name, self.image_path, K, self.c2w, w, h, self.depth_path, self.depth_scale)


@dataclass
class Dataset:
    root: str
    frames: List[Frame]
    points: np.ndarray                # N x 3 float32
    colors: np.ndarray                # N x 3 float32 0..1
    meta: dict = field(default_factory=dict)

    def split(self, test_every: int = 8):
        """Every n-th picture is held out for an honest score (0 = keep all)."""
        if test_every <= 0:
            return list(self.frames), []
        train = [f for i, f in enumerate(self.frames) if i % test_every != 0]
        test = [f for i, f in enumerate(self.frames) if i % test_every == 0]
        return train, test


GL_TO_CV = np.diag([1.0, -1.0, -1.0, 1.0])


def load_dataset(root: str) -> Dataset:
    tf_path = os.path.join(root, "transforms.json")
    with open(tf_path) as f:
        tf = json.load(f)

    frames: List[Frame] = []
    for fr in tf["frames"]:
        get = lambda k, d=None: fr.get(k, tf.get(k, d))  # per-frame value or global
        rel = fr["file_path"]
        path = os.path.join(root, rel)
        if not os.path.exists(path):
            for ext in (".png", ".jpg", ".jpeg", ".JPG", ".PNG"):
                if os.path.exists(path + ext):
                    path = path + ext
                    break
        w, h = get("w"), get("h")
        if w is None or h is None:
            with Image.open(path) as im:
                w, h = im.size
        fl_x = get("fl_x")
        fl_y = get("fl_y", fl_x)
        if fl_x is None and get("camera_angle_x") is not None:
            fl_x = fl_y = 0.5 * w / math.tan(0.5 * get("camera_angle_x"))
        cx, cy = get("cx", w / 2), get("cy", h / 2)
        K = np.array([[fl_x, 0, cx], [0, fl_y, cy], [0, 0, 1]], dtype=np.float64)
        c2w = np.array(fr["transform_matrix"], dtype=np.float64)
        if c2w.shape == (3, 4):
            c2w = np.vstack([c2w, [0, 0, 0, 1]])
        c2w = c2w @ GL_TO_CV  # Nerfstudio stores OpenGL cameras
        dp = fr.get("depth_file_path")
        frame = Frame(os.path.basename(rel), path, K, c2w, int(w), int(h),
                      os.path.join(root, dp) if dp else None,
                      float(get("depth_unit_scale_factor", 0.001)))
        # The picture on disk can be smaller than w x h (e.g. a quick export).
        with Image.open(path) as im:
            if im.size != (frame.width, frame.height):
                frame = frame.scaled(*im.size)
        frames.append(frame)

    ply = tf.get("ply_file_path", "points.ply")
    ply_path = os.path.join(root, ply)
    if os.path.exists(ply_path):
        pts, cols = read_ply_points(ply_path)
    else:
        pts, cols = np.zeros((0, 3), np.float32), np.zeros((0, 3), np.float32)
    return Dataset(root, frames, pts, cols, tf)


def read_ply_points(path: str):
    from plyfile import PlyData
    v = PlyData.read(path)["vertex"].data
    pts = np.stack([v["x"], v["y"], v["z"]], 1).astype(np.float32)
    names = v.dtype.names
    if "red" in names:
        cols = np.stack([v["red"], v["green"], v["blue"]], 1).astype(np.float32)
        if cols.max() > 1.0:
            cols /= 255.0
    else:
        cols = np.full_like(pts, 0.5)
    return pts, cols


def project(points: np.ndarray, frame: Frame):
    """World points -> pixel (u, v) and camera depth z."""
    w2c = frame.w2c
    pc = points @ w2c[:3, :3].T + w2c[:3, 3]
    z = pc[:, 2]
    zs = np.where(np.abs(z) < 1e-9, 1e-9, z)
    u = frame.K[0, 0] * pc[:, 0] / zs + frame.K[0, 2]
    v = frame.K[1, 1] * pc[:, 1] / zs + frame.K[1, 2]
    return u, v, z


def sharpness(img: np.ndarray) -> float:
    """Variance of a Laplacian on the grey picture: higher = sharper."""
    g = img.mean(axis=2) if img.ndim == 3 else img
    lap = (-4 * g[1:-1, 1:-1] + g[:-2, 1:-1] + g[2:, 1:-1] + g[1:-1, :-2] + g[1:-1, 2:])
    return float(lap.var())


def psnr(a: np.ndarray, b: np.ndarray, mask: Optional[np.ndarray] = None) -> float:
    d = (a - b) ** 2
    if mask is not None:
        d = d[mask]
    mse = float(d.mean()) if d.size else 1.0
    return 10 * math.log10(1.0 / max(mse, 1e-10))


def save_png(img: np.ndarray, path: str):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    Image.fromarray((np.clip(img, 0, 1) * 255 + 0.5).astype(np.uint8)).save(path)


def side_by_side(images: List[np.ndarray], gap: int = 4) -> np.ndarray:
    h = max(i.shape[0] for i in images)
    out = [np.pad(i, ((0, h - i.shape[0]), (0, gap), (0, 0)), constant_values=1.0) for i in images]
    return np.concatenate(out, 1)


def write_report(path: str, report: dict):
    with open(path, "w") as f:
        json.dump(report, f, indent=2)
    print(json.dumps(report, indent=2))
