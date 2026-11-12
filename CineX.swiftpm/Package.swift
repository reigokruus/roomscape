// swift-tools-version: 5.9

// App package: double-click CineX.swiftpm to open it in Xcode 15+ (or Swift Playgrounds on a Mac). Pick your own
// signing team in Xcode (Signing & Capabilities); teamIdentifier stays empty here so everyone can build.

import PackageDescription
import AppleProductTypes
import Foundation

// Brush (the photo-real preview's trainer) is linked in only when its library has been made on this Mac
// (tools/brush-ios/make-xcframework.sh -> Vendor/BrushC.xcframework, git-ignored); otherwise the app builds as before.
let brushLinked = FileManager.default.fileExists(atPath: URL(fileURLWithPath: #filePath).deletingLastPathComponent()
    .appendingPathComponent("Vendor/BrushC.xcframework").path)

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
            ],
            appCategory: nil,
            additionalInfoPlistContentFilePath: "DocumentTypes.plist"
        )
    ],
    targets: [
        .executableTarget(
            name: "AppModule",
            dependencies: brushLinked ? ["BrushC"] : [],
            path: ".",
            exclude: ["Vendor"],
            // the in-app photo-real preview viewer (SplatViewerView): page, scripts, licences
            resources: [.copy("SplatViewer")],
            linkerSettings: brushLinked ? [.linkedFramework("Metal"), .linkedFramework("QuartzCore"),
                                           .linkedFramework("CoreGraphics"), .linkedFramework("Foundation"),
                                           .linkedLibrary("c++")] : []
        )
    ] + (brushLinked ? [.binaryTarget(name: "BrushC", path: "Vendor/BrushC.xcframework")] : [])
)
