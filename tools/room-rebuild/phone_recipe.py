#!/usr/bin/env python3
"""The phone's photo-real preview recipe, for running the same job on the LEGION / MAC (rk 12 Nov: compare times).

  python3 phone_recipe.py <Scan>.capture <out dir>

Computes the phone's plan exactly as CineX.swiftpm/Planning/Core/SplatPacing.swift `plan(points:frames:)` does
(from points.bin and recording/frames.bin), makes the dataset with ios_to_nerfstudio.py (half-size pictures, the
plan's picture count and start points: the same picking rules as the app), and writes <out>/plan.json and
<out>/brush-args.txt: the Brush v0.3.0 options the phone uses (720 px, the plan's splat cap, SH degree 1, refine every
200, the plan's steps). Needs numpy + opencv for the dataset step (PYTHON=… for the venv).
"""
import json, os, subprocess, sys


def lerp(x, x0, x1, y0, y1):
    return y0 + (y1 - y0) * min(1, max(0, (x - x0) / (x1 - x0)))


def plan(points, frames):
    p = float(max(0, points))
    steps = lerp(p, 150_000, 600_000, 3000, 6000) if p <= 600_000 else lerp(p, 600_000, 1_500_000, 6000, 9000)
    pics = lerp(p, 150_000, 600_000, 150, 250) if p <= 600_000 else lerp(p, 600_000, 1_500_000, 250, 300)
    max_splats = 150_000 if p <= 150_000 else 200_000
    pictures = max(20, min(int(round(pics)), max(20, frames // 4) if frames > 0 else int(pics)))
    # Swift's .rounded() rounds half away from zero, as Python's round() does not: do it by hand
    s = steps / 500
    steps_r = int(s + 0.5) * 500
    return dict(steps=steps_r, pictures=pictures, maxSplats=max_splats, startPoints=min(max_splats // 2, max(1, points)),
                maxResolution=720, shDegree=1, refineEvery=200)


def main():
    cap, out = sys.argv[1], sys.argv[2]
    points = os.path.getsize(os.path.join(cap, 'points.bin')) // 16
    frames = os.path.getsize(os.path.join(cap, 'recording', 'frames.bin')) // 120
    pl = plan(points, frames)
    pl.update(points=points, frames=frames)
    os.makedirs(out, exist_ok=True)
    py = os.environ.get('PYTHON', sys.executable)
    here = os.path.dirname(os.path.abspath(__file__))
    subprocess.run([py, os.path.join(here, 'ios_to_nerfstudio.py'), cap, out, '--max-frames', str(pl['pictures']),
                    '--scale', '0.5', '--max-points', str(pl['startPoints'])], check=True)
    json.dump(pl, open(os.path.join(out, 'plan.json'), 'w'), indent=1)
    args = (f"--total-steps {pl['steps']} --max-splats {pl['maxSplats']} --max-resolution {pl['maxResolution']} "
            f"--sh-degree {pl['shDegree']} --refine-every {pl['refineEvery']}")
    open(os.path.join(out, 'brush-args.txt'), 'w').write(args + '\n')
    print(f"plan: {pl}\nbrush <dataset> {args}")


if __name__ == '__main__':
    main()
