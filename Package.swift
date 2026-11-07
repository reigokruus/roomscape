// swift-tools-version: 5.9
import PackageDescription
let package = Package(
    name: "ShootlocalPlanning",
    platforms: [.macOS(.v13)],
    products: [.library(name: "PlanningCore", targets: ["PlanningCore"])],
    targets: [
        .target(name: "PlanningCore", path: "CineX.swiftpm/Planning/Core"),
        .testTarget(name: "PlanningCoreTests", dependencies: ["PlanningCore"], path: "Tests/PlanningCoreTests"),
        // The app's splat trainer (Metal), tested on the Mac's GPU against a CPU copy of Android's Trainer.java.
        .target(name: "SplatTraining", path: "CineX.swiftpm/Training"),
        .testTarget(name: "SplatTrainingTests", dependencies: ["SplatTraining"], path: "Tests/SplatTrainingTests")
    ]
)
