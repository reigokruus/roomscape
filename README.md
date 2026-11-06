# SetScan

An iPhone app that scans a room with Apple RoomPlan and shows it back as a 3D model, with windows (natural light), doors and furniture picked out. It's the first step toward a set-planning tool that suggests light and camera placement.

Requires an **iPhone with LiDAR** (iPhone 12 Pro or newer Pro / Pro Max) on iOS 17+, and a Mac with **Xcode 15 or newer**.

## Build and install on an iPhone
1. Download this repo (green **Code** button › **Download ZIP**) and unzip it.
2. Double-click **SetScan.swiftpm**. It opens in Xcode.
3. Connect the iPhone by cable and tap **Trust**. On the iPhone, turn on Settings › Privacy & Security › **Developer Mode** (the phone restarts).
4. In Xcode, pick the iPhone as the run destination at the top. In the project's **Signing & Capabilities**, choose your Apple ID as the Team (add it under Xcode › Settings › Accounts first; a free Apple ID works).
5. Press ▶︎ Run. The first time, approve the developer account on the iPhone under Settings › General › VPN & Device Management, then run again.

Apps signed with a free Apple ID stop working after 7 days. Just press Run again to reinstall.
