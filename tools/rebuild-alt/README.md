# Rebuild alternatives: three other ways to make the final room view

Brush (Gaussian splats) is how we rebuild a photo-real room from a scan today. These three tools take
**the same dataset Brush trains on** (a Nerfstudio folder: `transforms.json`, the pictures, `points.ply`,
optionally `depth/` with 16-bit PNG depth in mm via `depth_file_path`) so the results can be compared
side by side. Each one writes a `report.json` with a held-out score in dB: every 8th picture is left out
of the build and the result is compared against it, the same idea as Brush's held-out dB.

| | Tool | What it makes | Runs on | Better than Brush at | Weak at |
|---|---|---|---|---|---|
| 1 | `textured_mesh.py` | `room.glb` (+ `.obj`/atlas) | CPU, minutes | flat walls, no floaters, true sizes, small file that opens anywhere | shine/reflections, thin things (plants, cables), seams |
| 2 | `flat_splats.py` | `splats.ply` (open in Brush) + `mesh.ply` | NVIDIA GPU | photo-real but flat walls, fewer floaters, depth from the phone used as a guide | slower to train than Brush; still a PC job |
| 3 | `ai_cleanup.py` | before/after pictures + video, `dataset_plus/` for a second Brush run | NVIDIA GPU, ≥ 8 GB | views off the walked path, blur and holes | **makes things up**; NVIDIA research-only licence |

## Setup (Windows PC with an NVIDIA GPU, e.g. LEGION)

Double-click `setup.cmd` once. It makes `.venv` here, installs the packages (`requirements-mesh.txt`,
`requirements-gpu.txt`, torch with CUDA 12.4) and clones Difix3D at a fixed commit. gsplat compiles its CUDA
code the first time it is used unless a prebuilt wheel matches; on Windows that compile needs the CUDA toolkit
and Visual Studio Build Tools. The Difix model (`nvidia/difix_ref`) downloads from Hugging Face on first use.

Option 1 alone needs only `pip install -r requirements-mesh.txt` and runs on any machine, the Mac included.

## Running

```
run-1-textured-mesh.cmd  C:\path\to\dataset                -> dataset-mesh\room.glb
run-2-flat-splats.cmd    C:\path\to\dataset                -> dataset-flat\splats.ply, mesh.ply
run-3-ai-cleanup.cmd     C:\path\to\dataset  splats.ply    -> dataset-ai\before_after\, novel_before_after.mp4
```

or the Python scripts directly (`--help` lists the options). Useful options:

- `textured_mesh.py --mesh app-mesh.ply`: paint the app's own mesh instead of building one. Without it the
  surface comes from fused depth (if the dataset has `depth/`), else from the start points (Poisson).
- `flat_splats.py --steps 15000 --max-splats 400000 --depth-weight 0.2`: `--depth-weight 0` gives plain 2DGS,
  to see what the depth guide adds.
- `ai_cleanup.py --no-difix`: only render the splats (checks they line up with the dataset).
  `dataset_plus/` can be trained again with Brush (Difix3D's "distil back" step).

For a fair score against Brush, train Brush with the same hold-out (every 8th picture) or compare pictures by eye.

## Checked so far

- On a synthetic 4 × 3 m room (`make_test_room.py`, 80 frames, 5 mm pose noise), CPU, in the cloud:
  option 1 runs end to end (depth fusion: 43 s, 97 % of triangles textured, held-out 28.8 dB on the surface it
  covers, 21.8 dB over the whole picture because the unseen ceiling is black; Poisson from points: 23 s).
- Options 2 and 3: data loading, training loop, densification hook-up, export, dataset_plus and reports were
  run on CPU with a stand-in renderer and a stand-in for Difix. **The real gsplat rasteriser and the Difix
  model have not run yet**; that needs the GPU PC.
- Nothing has been run on a real scan yet.

## Pictures of real homes

Scans are private: keep datasets and results off the repo and off public places.
