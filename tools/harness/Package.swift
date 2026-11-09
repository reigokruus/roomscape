// swift-tools-version: 5.9
// Mac-side test harness for RoomScape: a synthetic room and phone sensors, calibration from real scans, and the
// app's own non-ARKit code (TSDF, the experimental tracker, hole fill, zip) run on them. See docs/test-harness.md.
import PackageDescription

let package = Package(
    name: "Harness",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(name: "Harness", path: "Sources/Harness"),
    ]
)
