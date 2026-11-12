"""Recordings with a world-frame jump (12 Nov 2026, 22:37 scan) are repaired before use. The app corrects such a jump
live since Build 12 Nov ~23:00 (recording/pose-jump.json, poses already fixed); older recordings still have it in
frames.bin. `checked_capture` returns a capture folder whose poses are consistent: the scan's own one, or a repaired
copy made once with fix_pose_jump.py next to it (<Scan>.capture-posefixed/<Scan>.capture).
"""
import os, struct, subprocess, sys, numpy as np

def find_jump(frames_bin):
    fb = open(frames_bin, 'rb').read(); n = len(fb) // 120
    prev = None
    for i in range(n):
        b = fb[i * 120:(i + 1) * 120]
        t = struct.unpack('<d', b[:8])[0]; P = np.array(struct.unpack('<16f', b[8:72])).reshape(4, 4).T
        if prev is not None and t - prev[0] < 1.5:
            R = prev[1][:3, :3].T @ P[:3, :3]
            ang = np.degrees(np.arccos(np.clip((np.trace(R) - 1) / 2, -1, 1)))
            if np.linalg.norm(P[:3, 3] - prev[1][:3, 3]) > 1.0 or ang > 30:
                return i, t
        prev = (t, P)
    return None

def checked_capture(cap_dir):
    cap_dir = os.path.abspath(cap_dir.rstrip('/'))
    rec = os.path.join(cap_dir, 'recording')
    if os.path.exists(os.path.join(rec, 'pose-jump.json')):
        return cap_dir                      # corrected live by the app
    jump = find_jump(os.path.join(rec, 'frames.bin'))
    if not jump:
        return cap_dir
    name = os.path.basename(cap_dir)
    out = cap_dir + '-posefixed'
    fixed = os.path.join(out, name)
    if not os.path.exists(os.path.join(fixed, 'recording', 'frames.bin')):
        print(f'world-frame jump in the recording at frame {jump[0]}: repairing it first (fix_pose_jump.py → {out})')
        subprocess.run([sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), 'fix_pose_jump.py'), cap_dir, out], check=True)
    else:
        print(f'world-frame jump in the recording: using the repaired copy {fixed}')
    return fixed
