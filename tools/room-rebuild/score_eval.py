#!/usr/bin/env python3
"""Scores a Brush run by its held-out views (--eval-split-every 8 --eval-save-to-disk): PSNR of each saved render
against the dataset picture of the same name. Brush's CLI prints nothing, so this is the quality number of the
three-computer benchmark.

  python3 score_eval.py <dataset dir> <brush --export-path dir> [--json out.json]

Brush saves <export-path>/eval_<step>/<image path>.png; the newest step is scored. The dataset picture is resized to
the render's size if Brush trained at a smaller one. Prints and (optionally) writes median and mean PSNR.
"""
import sys, os, re, json, glob, numpy as np, cv2

args = sys.argv[1:]
out_json = None
if '--json' in args: i = args.index('--json'); out_json = args[i + 1]; del args[i:i + 2]
ds, run = args
evals = sorted(glob.glob(os.path.join(run, 'eval_*')), key=lambda p: int(re.sub(r'\D', '', os.path.basename(p)) or 0))
if not evals: sys.exit(f'no eval_<step> folder in {run}')
ev = evals[-1]
by_stem = {}
for p in glob.glob(os.path.join(ds, '**', '*.*'), recursive=True):
    if p.lower().endswith(('.jpg', '.jpeg', '.png')) and os.sep + 'images' + os.sep in p:
        by_stem[os.path.splitext(os.path.basename(p))[0]] = p
scores = []
for r in sorted(glob.glob(os.path.join(ev, '**', '*.png'), recursive=True)):
    gt_path = by_stem.get(os.path.splitext(os.path.basename(r))[0])
    if not gt_path: continue
    a = cv2.imread(r).astype(np.float32); b = cv2.imread(gt_path)
    if b.shape[:2] != a.shape[:2]: b = cv2.resize(b, (a.shape[1], a.shape[0]), interpolation=cv2.INTER_AREA)
    mse = float(np.mean((a - b.astype(np.float32)) ** 2))
    scores.append((os.path.basename(r), 99.0 if mse == 0 else 10 * np.log10(255 ** 2 / mse)))
if not scores: sys.exit(f'no renders in {ev} matched pictures in {ds}')
v = np.array([s for _, s in scores])
res = dict(step=os.path.basename(ev), views=len(v), median_psnr=round(float(np.median(v)), 2),
           mean_psnr=round(float(v.mean()), 2), worst=[n for n, _ in sorted(scores, key=lambda x: x[1])[:3]])
print(f"{res['step']}: {res['views']} held-out views, PSNR median {res['median_psnr']} dB, mean {res['mean_psnr']} dB")
if out_json: json.dump(res, open(out_json, 'w'), indent=1)
