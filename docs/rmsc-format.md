# .rmsc: the RoomScape scan package (version 1)

A `.rmsc` file is one scanned room. Both the iPhone and the Android app export and import it, and the web viewer opens it. It is a plain **zip** (store or deflate), so any zip tool can open it.

MIME type: `application/vnd.roomscape.rmsc+zip`, with `application/zip` as the fallback.
Apple UTI: `com.roomscape.rmsc`, conforming to `public.zip-archive` and `public.data`.

## Coordinates (all files)

- **Units:** metres.
- **Axes:** right-handed, **+Y up** (against gravity). X and Z are whatever the scanning session used; there is no compass north.
- **Floor:** `manifest.floorY` gives the floor height. Exporters should shift everything so the floor is at y = 0, then write `floorY: 0` (both RoomScape apps do). Readers must also accept other values (older Android packages wrote the unshifted floor height). A platform that shifted its own data records the amount in its extras (`ios/shift.json`, `android/shift.json`: `{"floorY": <metres moved>}`), so it can move its extras back into place when it reads the package again.
- **Camera poses:** 4×4 **camera-to-world** matrices, **column-major** (16 numbers, OpenGL order). The camera frame is OpenGL style: +X is image right, +Y is image up, and the camera looks along −Z. This is what ARKit `camera.transform` and ARCore `Camera.getPose()` give for the sensor image.
- **Intrinsics** (`fx fy cx cy`) are in pixels of the image **exactly as stored** in the package. A stored JPEG is never rotated after the pose is taken; if an app rotates a photo, it rotates the pose and intrinsics to match.

## Files

| Path | Required | What it holds |
|---|---|---|
| `manifest.json` | yes | Name, dates, source, room info and counts (see below) |
| `points.ply` | yes | Coloured point cloud: binary_little_endian, `float x y z`, `uchar red green blue` |
| `photos.json` + `photos/NNN.jpg` | yes (may be empty) | The scan photos, with pose and intrinsics |
| `depth/NNN.bin` | no | Low-resolution depth for photo NNN: uint16 millimetres, little endian, row-major, 0 = none |
| `room.glb` | no | The photo-textured mesh, the same file as "Export .glb". glTF 2.0 binary, unlit materials, one per photo |
| `mesh.ply` | no | The untextured scan mesh: binary_little_endian, `float x y z`, `uchar red green blue`, faces as `list uchar int vertex_indices` |
| `thumb.jpg` | no | A preview picture, about 512 px wide |
| `ios/…`, `android/…` | no | Platform extras, such as the ARKit world map (`ios/worldmap.arexperience`) or Android splats. Other platforms ignore them, and a reader must keep working without them |
| `android/doorways.json` | no | Doorways the Android app found (open wall openings): `[{"x0", "z0", "x1", "z1", "floorY", "width", "height", "open"}]`, the opening's bottom corners in the scan's world (before the floor shift), metres. A first version; a shared `structure` list (with iPhone RoomPlan doors) is proposed, not yet in the manifest |
| `android/diagnostics/…` | no | For fixing problems only: the scan's perf log (`perf-scan.jsonl`, `perf-scan.summary.json`), event log (`scan-log.txt`), `device.json` (model, Android, ARCore and app versions, RAM), `gpu-info.txt` (driver and limits), `gpu-selftest.txt`, `crash-last.txt`, `app-log.txt`. No location or account data. Readers ignore it |

A reader must ignore files it doesn't know. A writer may leave out every optional file.

### manifest.json

```json
{
  "format": "rmsc",
  "version": 1,
  "name": "Living room",
  "created": "2026-11-09T09:21:58+02:00",
  "modified": "2026-11-09T10:15:02+02:00",
  "source": { "platform": "ios", "app": "RoomScape", "appVersion": "0.4.2", "appBuild": "229", "device": "iPhone16,1" },
  "floorY": 0.0,
  "ceilingY": 2.9,
  "bounds": { "min": [-4.1, 0.0, -2.6], "max": [4.1, 2.9, 2.7] },
  "counts": { "points": 916400, "photos": 67, "triangles": 147843 },
  "room": {
    "corners": [[x, y, z], ...],
    "walls":   [[x1, z1, x2, z2, height], ...],
    "windows": 1, "doors": 1,
    "lux": 180
  },
  "objects": [
    { "id": "6F1C…", "name": "Chair", "kind": "chair", "source": "claude", "center": [x, y, z], "size": [w, h, d], "yaw": 0.3,
      "original": { "center": [x, y, z], "yaw": 0.0 }, "removed": false, "moved": true }
  ]
}
```

`room.walls` has one entry per side of the room (when the floor outline in `corners` has more sides than walls were found, each side of the outline is written as a wall, as tall as the room). `appVersion` is the version without a build number; `appBuild` is the build. An unnamed scan is called "Room <d MMM yyyy, HH:mm>", for example "Room 9 Nov 2026, 14:20", on both platforms.

`planes` (optional) lists flat surfaces the exporting phone is confident about, for example plain walls found by the Android app's "flat colour = plane" rule on phones without LiDAR: `[{"kind": "wall" | "floor" | "ceiling" | "surface", "normal": [nx, ny, nz], "d": d, "area": m², "min": [x, y, z], "max": [x, y, z], "color": [r, g, b]}]`, where points on the plane satisfy `normal · p = d`, and `min` / `max` bound the points it was fitted to. Readers may use them to flatten their surface there; nothing else depends on them.

Only `format`, `version` and `name` are required; everything else is optional. `objects` is the shared form: a box per movable object, with yaw in radians around +Y (right-handed, +Y up). `center` and `yaw` are where it is now; `original` is where the scan had it (absent = never moved). `size` is width (the box's own x), height, depth (its own z). `id` is stable across phones and syncs; `removed` objects stay in the list (so they can be put back). Readers merge by `id`: a newer package's version of an object wins, and objects the package lacks are kept — an import never drops objects. `source` is `roomPlan`, `claude`, `android` or `catalogue`. Richer per-platform object data (lights, sockets, a catalogue item's own points) goes in `ios/` or `android/`.

### photos.json

```json
{
  "photos": [
    {
      "file": "photos/000.jpg",
      "width": 1024, "height": 768,
      "fx": 820.1, "fy": 820.1, "cx": 512.0, "cy": 384.0,
      "pose": [16 numbers, camera-to-world, column-major],
      "time": 12.84,
      "depth": { "file": "depth/000.bin", "width": 256, "height": 192,
                 "fx": 205.0, "fy": 205.0, "cx": 128.0, "cy": 96.0 }
    }
  ]
}
```

`time` is seconds since the scan started, that is since the first photo (optional; not the phone's uptime). `depth` is optional. The depth image uses the same camera frame and pose as the photo, with its own intrinsics for its own size.

## What each app does on import

- It creates a **new** local scan from the package and never overwrites an existing one.
- It rebuilds what it needs from what's present:
  - with `room.glb`, it can show the photo room directly;
  - otherwise it builds its own view from `points.ply`, `photos` and `depth`.
- **Continue scan works across platforms** by geometric alignment against `points.ply` (Android's ContinueAlign; iOS needs the same fallback). The iOS world map only helps on iOS.

### Power outlets (`outlets` in manifest.json)

Optional, version 1. These are power outlets found in the room, for example by the Android app's Claude photo search:

```json
"outlets": [
  { "kind": "outlet", "count": 2, "position": [1.2, 0.04, -0.8], "heightAboveFloor": 0.04,
    "views": 3, "source": "claude" }
]
```

- `kind` is one of `outlet`, `power strip` or `floor socket`.
- `count` is the number of sockets.
- `position` is in the package's coordinates, like everything else.
- `heightAboveFloor` is metres above `floorY`.
- `views` (how many photos showed it) and `source` are optional.

Apps show each outlet as a label, e.g. "Outlet ×2 · 4 cm up", and count them in the room summary. A found outlet is a
change, so it raises the scan's `revision` and syncs. When a synced update replaces a scan, the outlets of both versions
are merged: within 15 cm counts as the same outlet. An update without outlets never erases them.

## Sending to a nearby phone

Inside the RoomScape apps only (not the system share sheet): a scan's own menu has **Send to nearby phone**, and the
scan lands in the other phone's scan library. Both phones need to be on the same Wi-Fi (or one phone's hotspot).

- **Discovery:** while RoomScape is open, it listens on a TCP port and advertises it as the DNS-SD / Bonjour service
  `_roomscape._tcp`. Bonjour is used on iOS, and NsdManager with a MulticastLock on Android. The instance name is the
  phone's display name. TXT attributes are `id` (a random id kept per install), `name` and `platform` (`ios` or
  `android`). An app never lists its own `id`.
- **Transfer:** plain HTTP/1.1 on that port: `POST /rmsc`, with `Content-Length` set and the raw .rmsc zip as the
  body. The request carries these headers (URL-encode the values if needed):
  - `X-Device-Id`: the sender's `id`;
  - `X-Device-Name`: shown to the receiver;
  - `X-File-Name`: the package's file name.
- **Trust:** the first scan from an unknown `X-Device-Id` asks the receiver "Accept scans from <name>?", with three
  answers: Always allow, Just this once, and Decline. Always allow remembers the id; later scans from that phone import
  without asking and show a short "Scan received from <name>".
- **Reply:** sent after the import finishes (or after the user declines). `200` means it was imported as a new scan,
  `403` means it was declined, and `400` / `422` mean a bad request or a package that couldn't be imported. Senders
  should allow a few minutes for the reply, since the receiver may be waiting on the question.
- **Platform needs:** iOS declares `NSLocalNetworkUsageDescription` and `NSBonjourServices` (`_roomscape._tcp`).
  Android needs `CHANGE_WIFI_MULTICAST_STATE`, `ACCESS_WIFI_STATE` and cleartext HTTP for the local address.

## Shared scans (sync)

A room scan has an identity, so phones that have the same room keep it in sync instead of piling up copies.

- **Manifest fields** (optional; version stays 1):
  - `id`: a permanent UUID for the room;
  - `revision`: an integer, +1 for every saved change (rename, Continue scan, object edits);
  - `revisionBy`: the device id that made that revision.
  Each app keeps them with the scan (iOS: `<scan>.capture/sync.json`; Android: the scan folder's `sync.json`), along
  with `shared`, the `peers` it's shared with (device id → name), and `syncedRevision`, the last revision both
  sides agreed on.
- **Becoming shared:** sending a scan to a nearby phone marks it shared on both phones with each other as a peer. Shared
  scans show a small "shared" badge (people icon, accent colour) on the Home thumbnail and in the scan's top bar.
- **Importing a package with a known `id`** updates that scan in place when its `revision` is higher, and is ignored
  otherwise. If the local scan also changed since `syncedRevision`, the local version is first kept as a copy with a
  new id and the name "<name> (copy from <device>)": data is never lost. A package without an `id` is always a new scan.
- **Sync over the local network** (the nearby service above), between phones that share a scan or are "Always
  allow"ed (others get `403`):
  - `GET /scans` lists the shared scans as `[{"id": "...", "revision": 7}]`.
  - `GET /rmsc/<id>` returns that shared scan's .rmsc package.
  - `POST /rmsc` sends one, as before.
  While RoomScape is open, each app looks for known phones the whole time. When one appears, every 30 s, and after a
  local change to a shared scan, it compares revisions: it pulls newer packages and pushes its own where they're newer.
- **Round trips:** package folders an app doesn't use (an iPhone's `ios/`) are kept with the scan and written back out
  on export, so a scan that passes through another platform loses nothing.
- **Later:** a cloud backend can use the same `id` / `revision` and the same packages.

## Versioning

`version` goes up only for a change that breaks old readers. Adding new optional files or fields keeps version 1.
