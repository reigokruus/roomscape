#!/usr/bin/env python3
"""Offline room rebuild from an Android scan folder (rk 12 Nov: "recreate the room yourself ... shrink wrap style").

  python3 rebuild.py <scan folder> <out dir> [--frames DIR] [--until SECONDS] [--skin dots|depth]

Pictures with poses come from <scan>/rebuild-frames/ (build 263160917+: camera pictures, poses, lens, raw depth with
confidence) or from --frames (match_video.py: pictures from recording.mp4 matched to pose-track.bin, no depth).
--until leaves out pictures after that time on the scan's clock (a drifting tail).

The skin: with raw depth, the depth frames fused (TSDF, each reading weighted by its confidence); without, the scan's own
dots meshed (Poisson). Either way only what was scanned is kept (rk 09:26: "no need to fill anything in ... that wasnt
in view"): skin further than 3 cm from any scanned dot or depth reading is cut away, so unscanned areas stay open.
Smoothed into one shrink-wrapped skin, then each vertex painted from the pictures that see it (not hidden behind
something nearer), the closest head-on view weighted most. Writes room.glb, room.ply, view-NN.jpg (turntable),
top.jpg (top view, 1 m grid), and top-grid.txt (how the top view maps to metres, for marking objects).
Scans show a real home: everything stays on the Mac.
"""
import sys, os, struct, math, numpy as np, cv2, open3d as o3d

args = sys.argv[1:]
def opt(name, default=None):
    if name in args: i = args.index(name); v = args[i + 1]; del args[i:i + 2]; return v
    return default
fr_dir = opt('--frames'); until = float(opt('--until', 1e9)); skin = opt('--skin')
scan, out = args[0], args[1]; os.makedirs(out, exist_ok=True)
if fr_dir is None: fr_dir = os.path.join(scan, 'rebuild-frames')

def read_points(scan):
    b = open(os.path.join(scan, 'points.bin'), 'rb').read(); n = struct.unpack('>i', b[:4])[0]
    dt = np.dtype([('x', '>f4'), ('y', '>f4'), ('z', '>f4'), ('r', 'u1'), ('g', 'u1'), ('b', 'u1')])
    A = np.frombuffer(b[4:4 + n * 15], dt)
    return np.stack([A['x'], A['y'], A['z']], 1).astype(np.float64), np.stack([A['r'], A['g'], A['b']], 1) / 255.0

frames = []   # (path stem, camera->world 4x4, image fx fy cx cy w h, depth fx fy cx cy w h)
if os.path.exists(os.path.join(fr_dir, 'frames.csv')):
    t_first = None
    for ln in open(os.path.join(fr_dir, 'frames.csv')):
        v = ln.strip().split(',')
        if len(v) < 30: continue
        ms = float(v[1]); t_first = ms if t_first is None else t_first
        if ms / 1000.0 > until: continue
        pose = np.array([float(x) for x in v[2:18]]).reshape(4, 4).T   # column-major -> row-major
        frames.append((os.path.join(fr_dir, f"{int(v[0]):05d}"), pose, [float(x) for x in v[18:24]], [float(x) for x in v[24:30]]))
has_depth = bool(frames) and os.path.exists(frames[0][0] + '.depth')
if skin is None: skin = 'depth' if has_depth else 'dots'
print(f"{len(frames)} pictures with poses (until {until if until < 1e9 else 'the end'}), raw depth: {has_depth}; skin from {skin}")

def read_depth(stem):
    b = open(stem + '.depth', 'rb').read(); w, h = struct.unpack('>ii', b[:8])
    d = np.frombuffer(b[8:8 + w * h * 2], '>u2').reshape(h, w).astype(np.float32)
    c = np.frombuffer(b[8 + w * h * 2:8 + w * h * 3], np.uint8).reshape(h, w)
    return d, c

FLIP = np.diag([1, -1, -1, 1.0])   # ARCore camera looks along -z with y up; Open3D's along +z with y down
if skin == 'depth':
    # TSDF weights each reading equally, so confidence is used as a graded keep: the sure readings always, the less
    # sure ones only near (where even an unsure S24 reading is within a few cm), the noise floor never.
    vol = o3d.pipelines.integration.ScalableTSDFVolume(voxel_length=0.012, sdf_trunc=0.04, color_type=o3d.pipelines.integration.TSDFVolumeColorType.RGB8)
    seen = []
    for stem, pose, ii, di in frames:
        d, c = read_depth(stem); h, w = d.shape
        keep = (c >= 200) | ((c >= 100) & (d < 1500)) | ((c >= 40) & (d < 800))
        d = np.where(keep & (d >= 200) & (d <= 4000), d, 0).astype(np.uint16)   # millimetres as uint16: Open3D takes a float image as metres already
        img = cv2.imread(stem + '.jpg'); img = cv2.cvtColor(cv2.resize(img, (w, h), interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2RGB)
        intr = o3d.camera.PinholeCameraIntrinsic(w, h, di[0], di[1], di[2], di[3])
        rgbd = o3d.geometry.RGBDImage.create_from_color_and_depth(o3d.geometry.Image(np.ascontiguousarray(img)), o3d.geometry.Image(d),
                                                                  depth_scale=1000.0, depth_trunc=4.0, convert_rgb_to_intensity=False)
        extr = np.linalg.inv(pose @ FLIP); vol.integrate(rgbd, intr, extr)
        seen.append(np.asarray(o3d.geometry.PointCloud.create_from_depth_image(o3d.geometry.Image(d), intr, extr, depth_scale=1000.0, stride=2).points))
    mesh = vol.extract_triangle_mesh()
    support = o3d.geometry.PointCloud(o3d.utility.Vector3dVector(np.concatenate(seen)))
else:
    # no raw depth: the scan's own dots drawn as depth pictures from the cameras' poses (nearest dot per pixel, small
    # gaps closed by a 3x3 minimum), fused the same way. Fusing only fills between dots that were seen together; it
    # never closes a surface the scan didn't reach. (Poisson was tried: it closes everything, and Open3D's PoissonRecon
    # quits the whole program on this scan with "Failed to close loop".)
    P, C = read_points(scan)
    vol = o3d.pipelines.integration.ScalableTSDFVolume(voxel_length=0.015, sdf_trunc=0.045, color_type=o3d.pipelines.integration.TSDFVolumeColorType.RGB8)
    w, h = 120, 90
    poses = [f[1] for f in frames] if frames else []
    if not poses:   # no pictures: poses from pose-track.bin, every 5th sample
        b = open(os.path.join(scan, 'pose-track.bin'), 'rb').read(); n = struct.unpack('>i', b[:4])[0]
        TR = np.frombuffer(b[4:4 + n * 52], '>f4').reshape(n, 13).astype(np.float64)
        for r in TR[TR[:, 1] > 0][::5]:
            x, y, z, q = *r[6:9], r[9]; M = np.eye(4); M[:3, 3] = r[3:6]
            M[:3, :3] = [[1 - 2 * (y * y + z * z), 2 * (x * y - z * q), 2 * (x * z + y * q)], [2 * (x * y + z * q), 1 - 2 * (x * x + z * z), 2 * (y * z - x * q)], [2 * (x * z - y * q), 2 * (y * z + x * q), 1 - 2 * (x * x + y * y)]]
            poses.append(M)
    fl = 85.0   # ~70 deg across, like the camera
    intr = o3d.camera.PinholeCameraIntrinsic(w, h, fl, fl, w / 2, h / 2)
    C8 = (C * 255).astype(np.uint8)
    for pose in poses:
        R = pose[:3, :3]; c = (P - pose[:3, 3]) @ R; z = -c[:, 2]; ok = (z > 0.2) & (z < 4.0)
        u = (fl * c[ok, 0] / z[ok] + w / 2).astype(int); v = (h / 2 - fl * c[ok, 1] / z[ok]).astype(int); zz = z[ok]; cc = C8[ok]
        inb = (u >= 0) & (u < w) & (v >= 0) & (v < h); u, v, zz, cc = u[inb], v[inb], zz[inb], cc[inb]
        if len(zz) < 500: continue
        cell = v * w + u; o = np.lexsort((zz, cell)); first = np.r_[True, cell[o][1:] != cell[o][:-1]]; s_ = o[first]
        D = np.zeros(w * h, np.float32); D[cell[s_]] = zz[s_] * 1000; D = D.reshape(h, w)
        RGB = np.zeros((w * h, 3), np.uint8); RGB[cell[s_]] = cc[s_]; RGB = RGB.reshape(h, w, 3)
        Dm = np.where(D > 0, D, 1e9).astype(np.float32); Dm = cv2.erode(Dm, np.ones((3, 3), np.uint8))
        D = np.where(D > 0, D, np.where(Dm < 1e8, Dm, 0)).astype(np.float32)   # fill single-pixel gaps with the nearest neighbour
        rgbd = o3d.geometry.RGBDImage.create_from_color_and_depth(o3d.geometry.Image(np.ascontiguousarray(RGB)), o3d.geometry.Image(D),
                                                                  depth_scale=1000.0, depth_trunc=4.0, convert_rgb_to_intensity=False)
        vol.integrate(rgbd, intr, np.linalg.inv(pose @ FLIP))
    mesh = vol.extract_triangle_mesh()
    support = o3d.geometry.PointCloud(o3d.utility.Vector3dVector(P))
    print(f"fused the dots from {len(poses)} camera poses")

# only what was scanned: cut skin more than 3 cm from the data (Poisson closes every hole; TSDF can bridge a little)
Vt = o3d.geometry.PointCloud(mesh.vertices)
far = np.asarray(Vt.compute_point_cloud_distance(support)) > 0.03
mesh.remove_vertices_by_mask(far)
print(f"skin over scanned areas only: {far.sum()} of {len(far)} vertices away from the data cut")

# shrink wrap: one smooth skin, crumbs dropped, simplified
mesh.remove_degenerate_triangles(); mesh.remove_duplicated_vertices(); mesh.remove_unreferenced_vertices()
cl, ncl, _ = mesh.cluster_connected_triangles(); cl = np.asarray(cl); ncl = np.asarray(ncl)
mesh.remove_triangles_by_mask(ncl[cl] < 300); mesh.remove_unreferenced_vertices()
mesh = mesh.filter_smooth_taubin(number_of_iterations=10)
mesh = mesh.simplify_quadric_decimation(target_number_of_triangles=min(400000, len(mesh.triangles)))
mesh.compute_vertex_normals()

if frames:   # paint from the pictures: visible vertices only, weight = head-on / distance, best views dominate
    V = np.asarray(mesh.vertices); N = np.asarray(mesh.vertex_normals)
    acc = np.zeros((len(V), 3)); wsum = np.zeros(len(V)); best = np.zeros(len(V))
    for stem, pose, ii, di in frames:
        img = cv2.imread(stem + '.jpg')
        if img is None: continue
        W, H = int(ii[4]), int(ii[5]); R = pose[:3, :3]; t = pose[:3, 3]; c = (V - t) @ R
        z = -c[:, 2]; ok = (z > 0.2) & (z < 4.5)
        u = ii[0] * c[:, 0] / np.maximum(z, 1e-3) + ii[2]; v = ii[3] - ii[1] * c[:, 1] / np.maximum(z, 1e-3)
        ok &= (u >= 1) & (u < W - 1) & (v >= 1) & (v < H - 1)
        idx = np.nonzero(ok)[0]
        if len(idx) == 0: continue
        # visibility: nearest skin per 8 px cell, a vertex counts only within 4 cm (+2% of distance) of it
        S = 8; cell = (v[idx] // S).astype(int) * (W // S + 1) + (u[idx] // S).astype(int)
        zmin = np.full(cell.max() + 1, np.inf); np.minimum.at(zmin, cell, z[idx])
        vis = idx[z[idx] <= zmin[cell] + 0.04 + 0.02 * z[idx]]
        view = t - V[vis]; view /= np.linalg.norm(view, axis=1, keepdims=True) + 1e-9
        cosv = (N[vis] * view).sum(1); edge = np.minimum.reduce([u[vis], W - u[vis], v[vis], H - v[vis]]) / (0.12 * W)
        wgt = np.clip(cosv, 0, 1) ** 2 / np.maximum(z[vis], 0.3) ** 2 * np.clip(edge, 0.2, 1)
        g = wgt > 0.02
        vis, wgt = vis[g], wgt[g]
        col = img[v[vis].astype(int), u[vis].astype(int)][:, ::-1] / 255.0
        w4 = wgt ** 3; acc[vis] += col * w4[:, None]; wsum[vis] += w4; best[vis] = np.maximum(best[vis], wgt)
    painted = wsum > 0
    old = np.asarray(mesh.vertex_colors) if mesh.has_vertex_colors() else np.full_like(V, 0.6)
    mesh.vertex_colors = o3d.utility.Vector3dVector(np.where(painted[:, None], acc / np.maximum(wsum, 1e-12)[:, None], old))
    print(f"painted from pictures: {painted.mean() * 100:.0f}% of the skin (the rest keeps the dots' own colour)")

o3d.io.write_triangle_mesh(os.path.join(out, 'room.ply'), mesh)
o3d.io.write_triangle_mesh(os.path.join(out, 'room.glb'), mesh)
print(f"mesh: {len(mesh.vertices)} vertices, {len(mesh.triangles)} triangles")

# pictures: a small painter's-algorithm rasteriser (no GPU), turntable around the room and a top view
rm = mesh.simplify_quadric_decimation(target_number_of_triangles=min(150000, len(mesh.triangles))); rm.compute_vertex_normals()
V = np.asarray(rm.vertices); T = np.asarray(rm.triangles); Cv = np.clip(np.asarray(rm.vertex_colors), 0, 1); Nv = np.asarray(rm.vertex_normals)
lo, hi = np.percentile(V, 2, 0), np.percentile(V, 98, 0); ctr = (lo + hi) / 2; ext = hi - lo
def render(eye, look, W=1200, H=900, fov=60, ortho=None, cut_above=None):
    fwd = look - eye; fwd /= np.linalg.norm(fwd)
    up = np.array([0, 1.0, 0]) if abs(fwd[1]) < 0.95 else np.array([0, 0, -1.0])
    rt = np.cross(fwd, up); rt /= np.linalg.norm(rt); up2 = np.cross(rt, fwd)
    c = V - eye; x = c @ rt; y = c @ up2; z = c @ fwd
    if ortho: px = W / 2 + x / ortho * W; py = H / 2 - y / ortho * W
    else: f = W / 2 / math.tan(math.radians(fov / 2)); px = W / 2 + f * x / np.maximum(z, 1e-3); py = H / 2 - f * y / np.maximum(z, 1e-3)
    light = np.array([0.3, 0.8, 0.5]); light /= np.linalg.norm(light)
    shade = 0.6 + 0.4 * np.abs(Nv @ light)
    img = np.full((H, W, 3), 250, np.uint8)
    tz = z[T].mean(1); keep = tz > 0.05
    if cut_above is not None: keep &= V[T][:, :, 1].max(1) < cut_above
    pts2 = np.stack([px[T], py[T]], 2)
    keep &= (pts2[:, :, 0].max(1) > 0) & (pts2[:, :, 0].min(1) < W) & (pts2[:, :, 1].max(1) > 0) & (pts2[:, :, 1].min(1) < H)
    cols = (Cv[T].mean(1) * shade[T].mean(1)[:, None] * 255)[:, ::-1]
    for t in np.nonzero(keep)[0][np.argsort(-tz[keep])]:
        cv2.fillConvexPoly(img, pts2[t].astype(np.int32), tuple(int(v) for v in cols[t]), lineType=cv2.LINE_AA)
    return img
r = max(ext[0], ext[2]) * 0.55 + 1.2
for k, ang in enumerate(range(0, 360, 45)):
    a = math.radians(ang)
    eye = ctr + np.array([r * math.cos(a), ext[1] * 0.5 + 1.0, r * math.sin(a)])
    cv2.imwrite(os.path.join(out, f'view-{k:02d}.jpg'), render(eye, ctr - np.array([0, ext[1] * 0.15, 0]), cut_above=hi[1] - 0.15))
# top view: ceiling cut off, looking straight down, x to the right, z downwards, 1 m grid
span = max(ext[0], ext[2]) * 1.15; W = 1200
top = render(np.array([ctr[0], hi[1] + 5, ctr[2]]), np.array([ctr[0], lo[1], ctr[2] + 1e-4]), W=W, H=W, ortho=span, cut_above=hi[1] - 0.25)
s = W / span   # px per m; world (x, z) -> px: (W/2 + (x - cx) s, W/2 + (z - cz) s)
for gx in np.arange(math.floor(ctr[0] - span / 2), ctr[0] + span / 2 + 1):
    X = int(W / 2 + (gx - ctr[0]) * s); cv2.line(top, (X, 0), (X, W), (200, 200, 200), 1); cv2.putText(top, f"{gx:.0f}", (X + 3, 18), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (90, 90, 90), 1)
for gz in np.arange(math.floor(ctr[2] - span / 2), ctr[2] + span / 2 + 1):
    Y = int(W / 2 + (gz - ctr[2]) * s); cv2.line(top, (0, Y), (W, Y), (200, 200, 200), 1); cv2.putText(top, f"{gz:.0f}", (3, Y - 3), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (90, 90, 90), 1)
cv2.imwrite(os.path.join(out, 'top.jpg'), top)
open(os.path.join(out, 'top-grid.txt'), 'w').write(f"top.jpg {W}x{W}: px = {W / 2} + (x - {ctr[0]:.3f}) * {s:.2f}, py = {W / 2} + (z - {ctr[2]:.3f}) * {s:.2f}; floor y {lo[1]:.2f}, top y {hi[1]:.2f}\n")
print("pictures written to", out, f"(room extent {ext[0]:.1f} x {ext[2]:.1f} m, {ext[1]:.1f} m high)")
