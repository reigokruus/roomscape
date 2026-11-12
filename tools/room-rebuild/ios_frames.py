#!/usr/bin/env python3
"""iPhone scan recording -> rebuild-frames (the layout rebuild.py and the texture tools read).

  python3 ios_frames.py <scan>.capture/recording <out dir> [--every 0.5] [--jpg-width 960]

The iPhone app keeps a raw recording with every scan (CineX.swiftpm/RawRecorder.swift):
  video.mov   ARKit's camera picture, HEVC, full resolution (1920x1440 on the 15 Pro), ~10 frames a second
  frames.bin  per frame: time f64, camera->world pose (4 columns of 4 f32), intrinsics (3 columns of 3 f32),
              width, height, tracking (i32): 120 bytes, little-endian
  depth.bin   per frame: time f64, size u32, LZFSE(width u16, height u16, depth mm u16[w*h], confidence u8[w*h])
ARKit and ARCore share the camera convention (x right, y up, looking down -z; the picture in the sensor's landscape
orientation), so the poses go straight into frames.csv. LiDAR confidence 0/1/2 (low/medium/high) is written as
30/150/255 so rebuild.py's confidence rule treats it like the S24's.

Writes every --every seconds: NNNNN.jpg (scaled to --jpg-width, intrinsics scaled to match), NNNNN.depth (rebuild-frames
format: int w, h big-endian; depth mm big-endian u16; confidence u8), frames.csv; and all-frames.csv: every recorded
frame with its video time and full-resolution intrinsics (for picking the sharpest single frame from the whole video).
The video is read frame by frame, never whole.
"""
import sys, os, struct, ctypes, numpy as np, cv2

args = sys.argv[1:]
def opt(name, default):
    if name in args: i = args.index(name); v = args[i + 1]; del args[i:i + 2]; return v
    return default
every = float(opt('--every', 0.5)); jw = int(opt('--jpg-width', 960))
rec, out = args; os.makedirs(out, exist_ok=True)
# a recording whose world frame jumped mid-scan (RoomPlan's size limit, 12 Nov) is repaired first (posefix.py)
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__))); import posefix
rec = os.path.join(posefix.checked_capture(os.path.dirname(os.path.abspath(rec.rstrip('/')))), 'recording')

# frames.bin
fb = open(os.path.join(rec, 'frames.bin'), 'rb').read(); n = len(fb) // 120
F = []
for i in range(n):
    b = fb[i * 120:(i + 1) * 120]
    t = struct.unpack('<d', b[:8])[0]; pose = np.array(struct.unpack('<16f', b[8:72])).reshape(4, 4).T   # columns -> matrix
    K = np.array(struct.unpack('<9f', b[72:108])).reshape(3, 3).T; w, h, trk = struct.unpack('<3i', b[108:120])
    F.append((t, pose, K, w, h, trk))
t0 = F[0][0]

# depth.bin (LZFSE via macOS libcompression)
lib = ctypes.CDLL('/usr/lib/libcompression.dylib')
lib.compression_decode_buffer.restype = ctypes.c_size_t
LZFSE = 0x801
def unlzfse(data, cap):
    dst = ctypes.create_string_buffer(cap)
    k = lib.compression_decode_buffer(dst, cap, data, len(data), None, LZFSE)
    return dst.raw[:k]
D = {}
db = open(os.path.join(rec, 'depth.bin'), 'rb').read(); p = 0
while p + 12 <= len(db):
    t = struct.unpack('<d', db[p:p + 8])[0]; sz = struct.unpack('<I', db[p + 8:p + 12])[0]; D[round(t, 4)] = (p + 12, sz); p += 12 + sz
print(f"{n} frames over {F[-1][0] - t0:.1f} s, {len(D)} depth frames, picture {F[0][3]}x{F[0][4]}")

cap = cv2.VideoCapture(os.path.join(rec, 'video.mov'))
csv = open(os.path.join(out, 'frames.csv'), 'w'); allf = open(os.path.join(out, 'all-frames.csv'), 'w')
fi = 0; k = 0; last = -1e9; vi = 0; skipped = 0
while True:
    ok, img = cap.read()
    if not ok: break
    tv = cap.get(cv2.CAP_PROP_POS_MSEC) / 1000.0
    # the video frame's time = recorded time - start; match it to frames.bin (frames can be skipped in the video)
    while fi < n - 1 and abs((F[fi + 1][0] - t0) - tv) < abs((F[fi][0] - t0) - tv): fi += 1
    t, pose, K, w, h, trk = F[fi]
    if abs((t - t0) - tv) > 0.03: skipped += 1; vi += 1; continue
    cm = pose.T.reshape(-1)
    allf.write(",".join([str(vi), f"{tv:.4f}", f"{t - t0:.4f}", str(trk)] + [f"{x:.6f}" for x in cm] + [f"{K[0, 0]:.3f}", f"{K[1, 1]:.3f}", f"{K[0, 2]:.3f}", f"{K[1, 2]:.3f}", str(w), str(h)]) + "\n")
    vi += 1
    if trk != 2 or t - t0 - last < every - 1e-3: continue
    key = round(t, 4); dd = D.get(key)
    if dd is None: continue
    raw = unlzfse(db[dd[0]:dd[0] + dd[1]], 4 + 256 * 256 * 3 * 4)
    dw, dh = struct.unpack('<HH', raw[:4])
    depth = np.frombuffer(raw[4:4 + dw * dh * 2], '<u2').reshape(dh, dw)
    conf = np.frombuffer(raw[4 + dw * dh * 2:4 + dw * dh * 3], np.uint8).reshape(dh, dw)
    conf8 = np.choose(np.clip(conf, 0, 2), [30, 150, 255]).astype(np.uint8)
    s = jw / w; small = cv2.resize(img, (jw, int(round(h * s))), interpolation=cv2.INTER_AREA)
    cv2.imwrite(os.path.join(out, f"{k:05d}.jpg"), small, [cv2.IMWRITE_JPEG_QUALITY, 90])
    with open(os.path.join(out, f"{k:05d}.depth"), 'wb') as o:
        o.write(struct.pack('>ii', dw, dh)); o.write(depth.astype('>u2').tobytes()); o.write(conf8.tobytes())
    ds = dw / w
    csv.write(",".join([str(k), str(int((t - t0) * 1000))] + [f"{x:.6f}" for x in cm]
                       + [f"{K[0, 0] * s:.3f}", f"{K[1, 1] * s:.3f}", f"{K[0, 2] * s:.3f}", f"{K[1, 2] * s:.3f}", str(jw), str(small.shape[0])]
                       + [f"{K[0, 0] * ds:.3f}", f"{K[1, 1] * ds:.3f}", f"{K[0, 2] * ds:.3f}", f"{K[1, 2] * ds:.3f}", str(dw), str(dh)]) + "\n")
    k += 1; last = t - t0
print(f"{vi} video frames ({skipped} without a matching pose), {k} rebuild frames written to {out}")
