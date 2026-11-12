"""Option 3: AI clean-up of rendered views (NVIDIA Difix3D+), on top of a splat room.

    python ai_cleanup.py DATASET SPLATS.ply OUT_DIR [--novel 40] [--test-every 8] [--difix PATH_TO_Difix3D]

DATASET is the folder the splats were trained from; SPLATS.ply is Brush's export
(or flat_splats.py's splats.ply). The script
  1. renders the held-out pictures and a set of new viewpoints off the walked
     path (sideways, stepped back, turned), where splat rooms usually break up;
  2. runs Difix ("remove degradation", one diffusion step) on each render, with
     the nearest real photo as its reference;
  3. scores the held-out renders before and after against the real photo, so we
     can see whether the clean-up moves the picture toward the truth or just
     makes it look nicer;
  4. writes before | after pictures, a before/after fly-through video, and
     dataset_plus/: the original pictures plus the cleaned new views, ready
     for a second Brush run (Difix3D's "distil back" step).

LICENCE: Difix3D and its weights are under the NVIDIA License, research and
evaluation use only (non-commercial). Fine for trying it; not for the pilot
or the app without NVIDIA's permission.

Needs an NVIDIA GPU (>= 8 GB): torch with CUDA, gsplat 1.5.x, a checkout of
github.com/nv-tlabs/Difix3D (its src/ folder) and its Python requirements
(diffusers 0.25.1, transformers 4.38, huggingface-hub 0.25.1, peft 0.9.0).
The model (nvidia/difix_ref) is downloaded from Hugging Face on first use.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import shutil
import sys
import time

import numpy as np
import torch
import torch.nn.functional as F
from PIL import Image

from common import Frame, GL_TO_CV, load_dataset, psnr, save_png, side_by_side, write_report


def log(*a):
    print(f"[{time.strftime('%H:%M:%S')}]", *a, flush=True)


def load_splats(path, device):
    from plyfile import PlyData
    v = PlyData.read(path)["vertex"].data
    names = v.dtype.names
    g = lambda *ks: np.stack([v[k] for k in ks], 1).astype(np.float32)
    rest = sorted([n for n in names if n.startswith("f_rest_")], key=lambda n: int(n.split("_")[-1]))
    K = len(rest) // 3
    deg = int(round(math.sqrt(K + 1))) - 1
    sh = np.zeros((len(v), K + 1, 3), np.float32)
    sh[:, 0] = g("f_dc_0", "f_dc_1", "f_dc_2")
    if K:
        sh[:, 1:] = g(*rest).reshape(len(v), 3, K).transpose(0, 2, 1)  # stored channel-major
    t = lambda a: torch.from_numpy(np.ascontiguousarray(a)).to(device)
    return dict(
        means=t(g("x", "y", "z")),
        scales=torch.exp(t(g("scale_0", "scale_1", "scale_2"))),
        quats=F.normalize(t(g("rot_0", "rot_1", "rot_2", "rot_3")), dim=-1),
        opacities=torch.sigmoid(t(v["opacity"].astype(np.float32))),
        colors=t(sh), sh_degree=deg)


@torch.no_grad()
def render(spl, f: Frame, device):
    from gsplat import rasterization
    rc, ra, _ = rasterization(
        spl["means"], spl["quats"], spl["scales"], spl["opacities"], spl["colors"],
        viewmats=torch.tensor(f.w2c, dtype=torch.float32, device=device)[None],
        Ks=torch.tensor(f.K, dtype=torch.float32, device=device)[None],
        width=f.width, height=f.height, sh_degree=spl["sh_degree"], near_plane=0.05)
    return rc[0].clamp(0, 1).cpu().numpy(), ra[0, ..., 0].cpu().numpy()


def novel_views(frames, n, rng):
    """Viewpoints off the walked path: step sideways / back up to ~40 cm, turn up to 15 degrees."""
    out = []
    picks = np.linspace(0, len(frames) - 1, n).astype(int)
    for k, i in enumerate(picks):
        f = frames[i]
        c2w = f.c2w.copy()
        right, down, fwd = c2w[:3, 0], c2w[:3, 1], c2w[:3, 2]
        side = rng.uniform(-0.4, 0.4)
        back = rng.uniform(0.1, 0.4)
        c2w[:3, 3] = c2w[:3, 3] + right * side - fwd * back + down * rng.uniform(-0.1, 0.1)
        yaw = math.radians(rng.uniform(-15, 15))
        R = np.array([[math.cos(yaw), 0, math.sin(yaw)], [0, 1, 0], [-math.sin(yaw), 0, math.cos(yaw)]])
        c2w[:3, :3] = c2w[:3, :3] @ R
        out.append((Frame(f"novel_{k:03d}.png", "", f.K.copy(), c2w, f.width, f.height), i))
    return out


def alignment_check(ds, spl):
    """Splats and dataset should share one world frame; report the typical gap."""
    if len(ds.points) == 0:
        return None
    from scipy.spatial import cKDTree
    m = spl["means"].cpu().numpy()
    sel = np.random.default_rng(0).choice(len(ds.points), min(5000, len(ds.points)), replace=False)
    d, _ = cKDTree(m).query(ds.points[sel])
    return float(np.median(d))


class Difix:
    def __init__(self, difix_dir, device):
        sys.path.insert(0, os.path.join(difix_dir, "src"))
        from pipeline_difix import DifixPipeline
        self.pipe = DifixPipeline.from_pretrained("nvidia/difix_ref", trust_remote_code=True).to(device)
        self.pipe.set_progress_bar_config(disable=True)

    def __call__(self, img: np.ndarray, ref: np.ndarray) -> np.ndarray:
        h, w = img.shape[:2]
        W8, H8 = (w // 8) * 8, (h // 8) * 8
        to_pil = lambda a: Image.fromarray((np.clip(a, 0, 1) * 255).astype(np.uint8)).resize((W8, H8), Image.LANCZOS)
        out = self.pipe("remove degradation", image=to_pil(img), ref_image=to_pil(ref), height=H8, width=W8,
                        num_inference_steps=1, timesteps=[199], guidance_scale=0.0).images[0]
        return np.asarray(out.resize((w, h), Image.LANCZOS), np.float32) / 255


def write_video(frames, path, fps=8):
    try:
        import imageio.v2 as imageio
        with imageio.get_writer(path, fps=fps, quality=8, macro_block_size=8) as wr:
            for f in frames:
                wr.append_data((np.clip(f, 0, 1) * 255).astype(np.uint8))
        return True
    except Exception as e:  # no ffmpeg: pictures are still there
        log(f"video skipped ({e})")
        return False


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("dataset")
    ap.add_argument("splats")
    ap.add_argument("out")
    ap.add_argument("--difix", default=os.environ.get("DIFIX3D_DIR", "Difix3D"), help="Difix3D checkout")
    ap.add_argument("--novel", type=int, default=40, help="new viewpoints to render and clean")
    ap.add_argument("--test-every", type=int, default=8, help="which pictures count as held out (match the splat run)")
    ap.add_argument("--max-side", type=int, default=960)
    ap.add_argument("--no-difix", action="store_true", help="only render (check the splats line up)")
    ap.add_argument("--device", default="cuda", help=argparse.SUPPRESS)  # cpu only for the smoke test
    a = ap.parse_args()
    dev = torch.device(a.device)
    t0 = time.time()
    for d in ("before_after", "dataset_plus/images"):
        os.makedirs(os.path.join(a.out, d), exist_ok=True)
    ds = load_dataset(a.dataset)
    train, test = ds.split(a.test_every)
    spl = load_splats(a.splats, dev)
    gap = alignment_check(ds, spl)
    log(f"{len(spl['means'])} splats (SH {spl['sh_degree']}); median gap scan points -> splats {gap*100:.1f} cm" if gap is not None else "no start points to check alignment")
    if gap is not None and gap > 0.10:
        log("WARNING: the splats do not sit on the scan's points; the splat file may be in a different (normalised) frame. Renders will be wrong.")

    def sized(f):
        s = min(1.0, a.max_side / max(f.width, f.height))
        return f.scaled(round(f.width * s), round(f.height * s))

    fixer = None if a.no_difix else Difix(a.difix, dev)
    centers = np.array([f.center for f in train])
    held = []
    for k, f in enumerate(test):
        fs = sized(f)
        gt = f.image(max(fs.width, fs.height))
        ren, _ = render(spl, fs, dev)
        row = {"picture": f.name, "before_db": round(psnr(ren, gt), 2)}
        if fixer:
            ref = train[int(np.argmin(np.linalg.norm(centers - f.center, axis=1)))]
            fixed = fixer(ren, ref.image(max(fs.width, fs.height)))
            row["after_db"] = round(psnr(fixed, gt), 2)
            save_png(side_by_side([gt, ren, fixed]), os.path.join(a.out, "before_after", f"heldout_{k:02d}.png"))
        else:
            save_png(side_by_side([gt, ren]), os.path.join(a.out, "before_after", f"heldout_{k:02d}.png"))
        held.append(row)
        log(f"held-out {k + 1}/{len(test)}: {row}")

    rng = np.random.default_rng(0)
    vid_before, vid_after, plus_frames = [], [], []
    for k, (nf, src) in enumerate(novel_views(train, a.novel, rng)):
        nfs = sized(nf)
        ren, alpha = render(spl, nfs, dev)
        vid_before.append(ren)
        if fixer:
            fixed = fixer(ren, train[src].image(max(nfs.width, nfs.height)))
            vid_after.append(fixed)
            save_png(side_by_side([ren, fixed]), os.path.join(a.out, "before_after", f"novel_{k:03d}.png"))
            save_png(fixed, os.path.join(a.out, "dataset_plus", "images", nf.name))
            plus_frames.append(nfs)
        if k % 10 == 0:
            log(f"new views: {k + 1}/{a.novel}")
    write_video(vid_before, os.path.join(a.out, "novel_before.mp4"))
    if vid_after:
        write_video([side_by_side([b, f]) for b, f in zip(vid_before, vid_after)], os.path.join(a.out, "novel_before_after.mp4"))

    # dataset_plus: the training pictures + cleaned new views (fed back to Brush for a second run)
    if plus_frames:
        tf = {k: v for k, v in ds.meta.items() if k not in ("frames",)}
        tf.pop("fl_x", None); tf.pop("fl_y", None); tf.pop("cx", None); tf.pop("cy", None); tf.pop("w", None); tf.pop("h", None)
        frames = []
        for f in train + plus_frames:
            name = os.path.basename(f.image_path) if f.image_path else f.name
            if f.image_path:
                shutil.copy(f.image_path, os.path.join(a.out, "dataset_plus", "images", name))
            frames.append({"file_path": f"images/{name}", "transform_matrix": (f.c2w @ GL_TO_CV).tolist(),
                           "fl_x": f.K[0, 0], "fl_y": f.K[1, 1], "cx": f.K[0, 2], "cy": f.K[1, 2],
                           "w": f.width, "h": f.height})
        tf["frames"] = frames
        if len(ds.points):
            shutil.copy(os.path.join(ds.root, ds.meta.get("ply_file_path", "points.ply")),
                        os.path.join(a.out, "dataset_plus", "points.ply"))
            tf["ply_file_path"] = "points.ply"
        with open(os.path.join(a.out, "dataset_plus", "transforms.json"), "w") as fh:
            json.dump(tf, fh, indent=1)

    hb = [r["before_db"] for r in held]
    ha = [r["after_db"] for r in held if "after_db" in r]
    write_report(os.path.join(a.out, "report.json"), {
        "method": "AI clean-up (Difix3D+, research licence)",
        "splats": int(len(spl["means"])),
        "alignment_gap_cm": round(gap * 100, 1) if gap is not None else None,
        "heldout_pictures": len(held),
        "heldout_psnr_before_db": round(float(np.mean(hb)), 2) if hb else None,
        "heldout_psnr_after_db": round(float(np.mean(ha)), 2) if ha else None,
        "heldout": held,
        "novel_views": a.novel,
        "seconds_total": round(time.time() - t0, 1),
        "files": ["before_after/", "novel_before_after.mp4", "dataset_plus/"],
        "note": "heldout scores only mean something if the splats were trained without the held-out pictures",
    })


if __name__ == "__main__":
    main()
