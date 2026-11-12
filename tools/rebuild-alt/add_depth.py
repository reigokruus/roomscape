"""Add the iPhone's LiDAR depth to a Brush dataset, so options 1 and 2 can use real depth.

    python add_depth.py DATASET RECORDING [--min-confidence 1] [--max-pose-cm 2]

RECORDING is the iPhone raw recording folder (frames.bin, depth.bin, video.mov;
format in CineX.swiftpm/RawRecorder.swift). Each dataset picture is matched to
the recording frame with the same camera pose (the dataset's poses come from
frames.bin, so they agree to well under a centimetre when nothing re-centred
them). Its depth (256 x 192, mm) is written to DATASET/depth/<picture>.png
(16-bit PNG, mm; low-confidence pixels set to 0) and transforms.json gets
depth_file_path per frame. The original transforms.json is kept as
transforms.before-depth.json; nothing else in the dataset changes.

If the dataset's poses were moved (re-centred, rotated or scaled) the match
fails with a clear message instead of guessing.

Needs numpy, pillow, pyliblzfse.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import struct

import numpy as np
from PIL import Image

FRAME_REC = 8 + 16 * 4 + 9 * 4 + 3 * 4  # time f64, pose 16 f32 (column-major), K 9 f32, w h tracking i32


def read_frames(path):
    data = open(path, "rb").read()
    n = len(data) // FRAME_REC
    times, poses = np.zeros(n), np.zeros((n, 4, 4))
    for i in range(n):
        r = data[i * FRAME_REC:(i + 1) * FRAME_REC]
        times[i] = struct.unpack_from("<d", r, 0)[0]
        poses[i] = np.array(struct.unpack_from("<16f", r, 8)).reshape(4, 4).T  # columns -> matrix
    return times, poses


def read_depth_index(path):
    """Time and byte range of each depth record (decoded later, only when needed)."""
    out = []
    with open(path, "rb") as f:
        data = f.read()
    o = 0
    while o + 12 <= len(data):
        t, size = struct.unpack_from("<dI", data, o)
        out.append((t, o + 12, size))
        o += 12 + size
    return data, out


def decode_depth(blob):
    import liblzfse
    try:
        raw = liblzfse.decompress(blob)
    except Exception:
        raw = blob  # the recorder stores uncompressed if compression failed
    w, h = struct.unpack_from("<HH", raw, 0)
    mm = np.frombuffer(raw, dtype="<u2", count=w * h, offset=4).reshape(h, w)
    conf = None
    if len(raw) >= 4 + w * h * 3:
        conf = np.frombuffer(raw, dtype=np.uint8, count=w * h, offset=4 + w * h * 2).reshape(h, w)
    return mm, conf


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("dataset")
    ap.add_argument("recording")
    ap.add_argument("--min-confidence", type=int, default=1, help="ARKit confidence 0 low, 1 medium, 2 high")
    ap.add_argument("--max-pose-cm", type=float, default=2.0, help="largest allowed pose difference for a match")
    ap.add_argument("--max-time-gap", type=float, default=0.05, help="seconds between a frame and its depth")
    a = ap.parse_args()

    tf_path = os.path.join(a.dataset, "transforms.json")
    tf = json.load(open(tf_path))
    times, poses = read_frames(os.path.join(a.recording, "frames.bin"))
    ddata, dindex = read_depth_index(os.path.join(a.recording, "depth.bin"))
    dtimes = np.array([d[0] for d in dindex])
    print(f"recording: {len(times)} frames, {len(dindex)} depth maps; dataset: {len(tf['frames'])} pictures")

    centres = poses[:, :3, 3]
    errs, out_dir = [], os.path.join(a.dataset, "depth")
    os.makedirs(out_dir, exist_ok=True)
    written = 0
    for fr in tf["frames"]:
        c2w = np.array(fr["transform_matrix"], float)
        if "time" in fr or "timestamp" in fr:
            t = float(fr.get("time", fr.get("timestamp")))
            i = int(np.argmin(np.abs(times - t)))
        else:
            d = np.linalg.norm(centres - c2w[:3, 3], axis=1)
            # among close positions, the one that also looks the same way
            cand = np.argsort(d)[:5]
            rot = [np.linalg.norm(poses[j, :3, :3] - c2w[:3, :3]) for j in cand]
            i = int(cand[int(np.argmin(np.array(rot) + d[cand] * 10))])
            t = times[i]
        err_cm = float(np.linalg.norm(centres[i] - c2w[:3, 3]) * 100)
        errs.append(err_cm)
        k = int(np.argmin(np.abs(dtimes - t)))
        if abs(dtimes[k] - t) > a.max_time_gap:
            continue
        _, off, size = dindex[k]
        mm, conf = decode_depth(ddata[off:off + size])
        mm = mm.copy()
        if conf is not None:
            mm[conf < a.min_confidence] = 0
        name = os.path.splitext(os.path.basename(fr["file_path"]))[0] + ".png"
        Image.fromarray(mm.astype(np.uint16)).save(os.path.join(out_dir, name))
        fr["depth_file_path"] = f"depth/{name}"
        written += 1
    errs = np.array(errs)
    print(f"pose match: median {np.median(errs):.2f} cm, worst {errs.max():.2f} cm")
    if np.median(errs) > a.max_pose_cm:
        raise SystemExit(
            "The dataset's poses do not match the recording's (median gap above "
            f"{a.max_pose_cm} cm): the prep step probably re-centred or rotated them. "
            "Add a per-frame \"time\" (frames.bin seconds) to transforms.json, or undo the transform, and rerun. "
            "Nothing was changed in transforms.json.")
    shutil.copy(tf_path, os.path.join(a.dataset, "transforms.before-depth.json"))
    tf["depth_unit_scale_factor"] = 0.001
    json.dump(tf, open(tf_path, "w"), indent=1)
    print(f"wrote {written} depth maps to {out_dir} and depth_file_path into transforms.json")


if __name__ == "__main__":
    main()
