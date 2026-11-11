# Recording scans to replay

A scan recorded once on a phone can be replayed as often as needed, so changes can be checked without walking
the room again. Recordings show the inside of a home: they stay on the phone or in `recordings/` on the Mac,
which is in .gitignore and never committed.

## S24 (ARCore's own recorder)

Records the camera picture, motion sensors and depth into an .mp4; replaying it reruns the whole ARCore
pipeline on the phone (tracking, depth, our scan, live mesh, overview, saving).

1. In RoomScape, turn the debug tools on: long-press **Done** on the scan screen (or New scan), a toast says
   "Debug tools on".
2. Start a scan as usual; a **Record session** button is among the debug buttons. Tap it ("Recording…").
3. Scan as normal, then tap **Stop recording** before Done.
4. Plug the S24 into the Mac when convenient; `tools/replay-s24.sh pull` copies the recordings over.

To replay (phone free, plugged in): `tools/replay-s24.sh play session-….mp4`, then tap New scan on the phone.

## iPhone

Apple's ARKit replay needs a recording made on the phone; what the current tools offer is still being checked.
Until then the iPhone path is our own debug recorder (coming with "record raw now, process later").

## What to record (3–4 sessions, about a minute each)

1. Your tight room: a slow loop around it, about 60 s.
2. A plain wall up close: walk along it about 30 cm away.
3. A window: point at it for a while, then away.
4. A room with furniture against a wall (a chest of drawers, a chair).

## Experiment: ultrawide recorder (Android, 11 Nov)

Debug tools › "Ultrawide recorder (experiment)": records the ultrawide (or the main) camera **without ARCore**, with
per-frame timestamps, the motion sensors and the lens calibration, to check offline (`tools/ultrawide-bench`) whether
a wider lens would keep more trackable detail on plain walls. Files: `Android/data/com.hackathon.setscan/files/recordings/uw-*/`
and `main-*/` on the phone (they show the room: copy to the Mac only, never commit).

To remove the experiment: delete `android/app/src/main/java/com/hackathon/setscan/experiment/`, the "Ultrawide
recorder" button in `MainActivity.java`, the `UltrawideRecorderActivity` line in `AndroidManifest.xml` and this section.
