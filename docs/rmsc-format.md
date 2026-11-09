# .rmsc: the RoomScape scan package (version 1)

A `.rmsc` file is one scanned room. Both the iPhone and the Android app export and import it, and the web viewer opens it. It is a plain **zip** (store or deflate), so any zip tool can open it.

MIME type: `application/vnd.roomscape.rmsc+zip`, with `application/zip` as the fallback.
Apple UTI: `com.roomscape.rmsc`, conforming to `public.zip-archive` and `public.data`.

## Coordinates (all files)

- **Units:** metres.
- **Axes:** right-handed, **+Y up** (against gravity). X and Z are whatever the scanning session used; there is no compass north.
- **Floor:** `manifest.floorY` gives the floor height. Exporters should shift everything so the floor is at y = 0, then write `floorY: 0`.
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

A reader must ignore files it doesn't know. A writer may leave out every optional file.

### manifest.json

```json
{
  "format": "rmsc",
  "version": 1,
  "name": "Living room",
  "created": "2026-11-09T09:21:58+02:00",
  "modified": "2026-11-09T10:15:02+02:00",
  "source": { "platform": "ios", "app": "RoomScape", "appVersion": "0.4.2", "device": "iPhone16,1" },
  "floorY": 0.0,
  "ceilingY": 2.9,
  "bounds": { "min": [-4.1, 0.0, -2.6], "max": [4.1, 2.9, 2.7] },
  "counts": { "points": 916400, "photos": 67, "triangles": 147843 },
  "room": {
    "corners": [[x, y, z], ...],
    "walls":   [[x1, z1, x2, z2, height], ...],
    "lux": 180
  },
  "objects": [
    { "name": "Chair", "center": [x, y, z], "size": [w, h, d], "yaw": 0.3 }
  ]
}
```

Only `format`, `version` and `name` are required; everything else is optional. `objects` is the simple shared form: a box per object, with yaw in radians around +Y. Richer per-platform object data goes in `ios/` or `android/`.

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

`time` is seconds since the scan started (optional). `depth` is optional. The depth image uses the same camera frame and pose as the photo, with its own intrinsics for its own size.

## What each app does on import

- It creates a **new** local scan from the package and never overwrites an existing one.
- It rebuilds what it needs from what's present:
  - with `room.glb`, it can show the photo room directly;
  - otherwise it builds its own view from `points.ply`, `photos` and `depth`.
- **Continue scan works across platforms** by geometric alignment against `points.ply` (Android's ContinueAlign; iOS needs the same fallback). The iOS world map only helps on iOS.

## Versioning

`version` goes up only for a change that breaks old readers. Adding new optional files or fields keeps version 1.
