# SetScan

**A set-up assistant for real rooms.** Scan your room with an iPhone, tell the app what gear you own, pick the kind of scene you want, and it tells you in plain language where to put the camera and lights. It's built for everyday creators, not film crews.

## Status
**v0.1 (hackathon prototype):** the scan works. Apple RoomPlan captures the room, and it comes back as a 3D model with windows (natural light), doors and furniture picked out, plus a room summary and a top-down view.

**Next:** a gear inventory, scene presets, and suggestions for camera and light placement on the scanned room.

## Why not Previs Pro (or similar apps)?
Previs Pro is a **pro storyboarding tool**. You build virtual sets from a library and plan shots for a script. It doesn't scan your room, know your gear or suggest where to put things. SetScan starts from the **real room and the gear you actually own**. See [docs/positioning.md](docs/positioning.md) for the full comparison, including WATT-IF, Polycam, Scaniverse, Photo Studio AR and Shot Designer. Polycam and Scaniverse capture rooms very well, but they stop at the scan. SetScan uses the scan to plan the shoot.

Requires an **iPhone with LiDAR** (iPhone 12 Pro or newer Pro / Pro Max) on iOS 17+, and a Mac with **Xcode 15 or newer**.

## Build and install on an iPhone
1. Download this repo (green **Code** button › **Download ZIP**) and unzip it.
2. Double-click **SetScan.swiftpm**. It opens in Xcode.
3. Connect the iPhone by cable and tap **Trust**. On the iPhone, turn on Settings › Privacy & Security › **Developer Mode** (the phone restarts).
4. In Xcode, pick the iPhone as the run destination at the top. In the project's **Signing & Capabilities**, choose your Apple ID as the Team (add it under Xcode › Settings › Accounts first; a free Apple ID works).
5. Press ▶︎ Run. The first time, approve the developer account on the iPhone under Settings › General › VPN & Device Management, then run again.

Apps signed with a free Apple ID stop working after 7 days. Just press Run again to reinstall.
