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

## Results so far

First real test, 12 Nov 2026, LEGION (RTX 4070 laptop, 8 GB), rk's 17:05 scan (phone-match dataset: 199 pictures
960x720, 92k start points, no depth), the same 25 pictures held out for every method:

| Method | Held-out mean | Time |
|---|---|---|
| Brush, phone recipe (5k steps, 200k splats, 720 px) | 20.83 dB | 46 s |
| Brush, 15k steps, 200k splats, 720 px | 21.27 dB | 126 s |
| Brush, 15k steps, 400k splats, 960 px | 21.21 dB | 240 s |
| 2: flat splats, depth guide from start points | 20.80 dB | 641 s |
| 2: flat splats, no depth guide | 20.73 dB | 642 s |
| 1: textured mesh, Poisson from start points | 15.35 dB | 51 s |
| 3: Brush 5k + Difix (512 px) | 20.68 dB (before 20.74) | 279 s |

None beats Brush on this scan. Option 1 suffers from building the surface out of sparse points (patchwork,
gaps); option 2 gives clean surfaces but smears small clutter; Difix makes off-path views look much cleaner but
invents detail and does not move held-out pictures toward the truth. Not tried yet: options 1 and 2 with the
phone's real depth (`depth/`) or the app's own mesh, and Difix's "distil back" Brush run on `dataset_plus/`.

LEGION ran gsplat 1.5.0 (1.5.3 has no Windows wheel); `setup.cmd` was not used there.

## Pictures of real homes

Scans are private: keep datasets and results off the repo and off public places.
