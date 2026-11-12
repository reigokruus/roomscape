#!/usr/bin/env python3
"""Gaussian splat .ply (3DGS / Brush / gsplat export) -> compact .splat for the local browser viewer.

  python3 ply2splat.py in.ply out.splat [--min-opacity 0.02] [--max N]

.splat: 32 bytes per splat: float32 x, y, z; float32 scale x, y, z (linear); uint8 r, g, b, a; uint8 quaternion w, x, y, z
(q * 128 + 128). Colour = base colour only (degree-0 spherical harmonics), so view-dependent shine is dropped; that
cuts a degree-3 file about 8x and is what an 8 GB Mac's browser can hold. Nearly transparent splats are dropped; the
rest are sorted most visible first, so --max keeps the ones that matter.
"""
import sys, numpy as np
args = sys.argv[1:]
def opt(n, d):
    if n in args: i = args.index(n); v = args[i + 1]; del args[i:i + 2]; return v
    return d
minop = float(opt('--min-opacity', 0.02)); mx = int(opt('--max', 0))
src, dst = args
with open(src, 'rb') as f:
    props, n = [], 0
    while True:
        l = f.readline().decode('ascii', 'replace').strip()
        if l.startswith('element vertex'): n = int(l.split()[2])
        elif l.startswith('property'): t, name = l.split()[1:3]; props.append((name, {'float': '<f4', 'double': '<f8', 'uchar': 'u1', 'int': '<i4', 'uint': '<u4'}[t]))
        elif l == 'end_header': break
    off = f.tell()
A = np.memmap(src, dtype=np.dtype(props), mode='r', offset=off, shape=(n,))   # read from disk as needed, not loaded whole
C0 = 0.28209479177387814
op = 1 / (1 + np.exp(-A['opacity'].astype(np.float32)))
keep = op >= minop
sc = np.exp(np.stack([A['scale_0'], A['scale_1'], A['scale_2']], 1).astype(np.float32))
imp = op * sc.prod(1)
idx = np.nonzero(keep)[0]; idx = idx[np.argsort(-imp[idx])]
if mx: idx = idx[:mx]
out = np.zeros(len(idx), dtype=[('p', '<f4', 3), ('s', '<f4', 3), ('c', 'u1', 4), ('q', 'u1', 4)])
out['p'] = np.stack([A['x'], A['y'], A['z']], 1)[idx]
out['s'] = sc[idx]
rgb = np.clip((0.5 + C0 * np.stack([A['f_dc_0'], A['f_dc_1'], A['f_dc_2']], 1)[idx]) * 255, 0, 255)
out['c'] = np.concatenate([rgb, np.clip(op[idx, None] * 255, 0, 255)], 1).astype(np.uint8)
q = np.stack([A['rot_0'], A['rot_1'], A['rot_2'], A['rot_3']], 1)[idx].astype(np.float32); q /= np.linalg.norm(q, axis=1, keepdims=True) + 1e-12
out['q'] = np.clip(q * 128 + 128, 0, 255).astype(np.uint8)
out.tofile(dst)
p = out['p']; print(f"{n} splats in, {len(idx)} kept ({minop} min opacity{', max ' + str(mx) if mx else ''}); {len(idx) * 32 / 1e6:.0f} MB; "
                    f"extent x {np.percentile(p[:,0],2):.1f}..{np.percentile(p[:,0],98):.1f} y {np.percentile(p[:,1],2):.1f}..{np.percentile(p[:,1],98):.1f} z {np.percentile(p[:,2],2):.1f}..{np.percentile(p[:,2],98):.1f}")
