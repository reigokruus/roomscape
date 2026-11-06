// swift-tools-version: 5.9

// App package: double-click CineX.swiftpm to open it in Xcode 15+ (or Swift Playgrounds on a Mac).

import PackageDescription
import AppleProductTypes

let package = Package(
    name: "CineX",
    platforms: [
        .iOS("17.0")
    ],
    products: [
        .iOSApplication(
            name: "CineX",
            targets: ["AppModule"],
            bundleIdentifier: "com.hackathon.cinex",
            teamIdentifier: "",
            displayVersion: "0.1",
            bundleVersion: "1",
            appIcon: .placeholder(icon: .camera),
            accentColor: .presetColor(.orange),
            supportedDeviceFamilies: [
                .phone
            ],
            supportedInterfaceOrientations: [
                .portrait
            ],
            capabilities: [
                .camera(purposeString: "CineX uses the camera and LiDAR scanner to capture your room.")
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
