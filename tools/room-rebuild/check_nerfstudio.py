#!/usr/bin/env python3
"""Checks a Nerfstudio transforms.json made from an iPhone recording (SplatDataset.swift): the scan's own coloured
dots, projected through each frame's transform_matrix and lens into the matching video frame, should agree with the
picture (brightness correlation, nearest dot per 4 px cell). Also scored with y and z flipped (the OpenCV/COLMAP
convention) as the "wrong" baseline, so a mix-up shows.

  python3 check_nerfstudio.py <transforms dir with transforms.json + times.json> <recording dir> <capture points.bin>
"""
import sys, os, json, struct, numpy as np, cv2
d, rec, ptsf = sys.argv[1:4]
T = json.load(open(os.path.join(d, 'transforms.json')))['frames']; times = json.load(open(os.path.join(d, 'times.json')))
want = {round(t['time'], 3): T[i] for i, t in enumerate(times)}
b = open(ptsf, 'rb').read(); n = len(b) // 16
A = np.frombuffer(b[:n * 16], dtype=[('p', '<f4', 3), ('c', 'u1', 4)])
P = A['p'].astype(np.float64); G = A['c'][:, :3].astype(np.float64) @ [0.299, 0.587, 0.114]
sub = np.random.default_rng(0).choice(n, min(n, 200000), replace=False); P, G = P[sub], G[sub]
def agree(img, M, f, flip):
    R = M[:3, :3]; c = (P - M[:3, 3]) @ R
    if flip: c[:, 1] *= -1; c[:, 2] *= -1
    z = -c[:, 2]; ok = (z > 0.2) & (z < 4)
    u = f['fl_x'] * c[:, 0] / np.maximum(z, 1e-3) + f['cx']; v = f['cy'] - f['fl_y'] * c[:, 1] / np.maximum(z, 1e-3)
    H, W = img.shape; ok &= (u >= 0) & (u < W) & (v >= 0) & (v < H)
    if ok.sum() < 300: return None
    u, v, z, g = u[ok], v[ok], z[ok], G[ok]
    cell = (v // 4).astype(int) * (W // 4 + 1) + (u // 4).astype(int)
    o = np.lexsort((z, cell)); s = o[np.r_[True, cell[o][1:] != cell[o][:-1]]]
    return np.corrcoef(img[v[s].astype(int), u[s].astype(int)], g[s])[0, 1] if len(s) > 300 else None
fb = open(os.path.join(rec, 'frames.bin'), 'rb').read(); t0 = struct.unpack('<d', fb[:8])[0]
cap = cv2.VideoCapture(os.path.join(rec, 'video.mov')); good, bad = [], []
while True:
    ok = cap.grab()
    if not ok: break
    tv = round(cap.get(cv2.CAP_PROP_POS_MSEC) / 1000, 3)
    f = next((want[k] for k in (tv, round(tv - 0.001, 3), round(tv + 0.001, 3)) if k in want), None)
    if f is None: continue
    _, img = cap.retrieve(); img = cv2.cvtColor(cv2.resize(img, (f['w'], f['h']), interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2GRAY).astype(float)
    M = np.array(f['transform_matrix'], float)
    a, w = agree(img, M, f, False), agree(img, M, f, True)
    if a is not None: good.append(a)
    if w is not None: bad.append(w)
print(f"{len(good)} of {len(T)} frames checked: dots vs picture agreement median {np.median(good):.2f} (10th pct {np.percentile(good, 10):.2f}); "
      f"with y/z flipped (wrong convention): {np.median(bad) if bad else float('nan'):.2f} over {len(bad)} frames")
