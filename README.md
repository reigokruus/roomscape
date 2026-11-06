# SetScan

An iPhone app (LiDAR models only: iPhone 12 Pro or newer Pro) that scans a room with Apple RoomPlan and shows it back as a 3D model, with windows, doors and furniture picked out. It's the first step toward a set-planning tool that suggests light and camera placement.

## Install on an iPhone without a recent Mac
1. Every push runs **Actions › Build IPA** on a GitHub macOS runner. Download the `SetScan-ipa` artifact.
2. Install it with [Sideloadly](https://sideloadly.io) and your Apple ID (a free one works, but the app expires after 7 days).
3. On the iPhone, turn on Settings › Privacy & Security › Developer Mode, and trust your Apple ID under Settings › General › VPN & Device Management.

## Build locally (Xcode 15+)
```
brew install xcodegen && xcodegen generate && open SetScan.xcodeproj
```
