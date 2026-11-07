// swift-tools-version: 5.9

// App package: double-click CineX.swiftpm to open it in Xcode 15+ (or Swift Playgrounds on a Mac). Pick your own
// signing team in Xcode (Signing & Capabilities); teamIdentifier stays empty here so everyone can build.

import PackageDescription
import AppleProductTypes

let package = Package(
    name: "RoomScape",
    platforms: [
        .iOS("17.0")
    ],
    products: [
        .iOSApplication(
            name: "RoomScape",
            targets: ["AppModule"],
            bundleIdentifier: "com.hackathon.roomscape",
            teamIdentifier: "",
            displayVersion: "0.1",
            bundleVersion: "1",
            appIcon: .asset("AppIcon"),
            accentColor: .asset("AccentColor"),
            supportedDeviceFamilies: [
                .phone
            ],
            supportedInterfaceOrientations: [
                .portrait
            ],
            capabilities: [
                .camera(purposeString: "RoomScape uses the camera and LiDAR scanner to capture your room.")
            ]
        )
    ],
    targets: [
        .executableTarget(
            name: "AppModule",
            path: "."
        )
    ]
)
