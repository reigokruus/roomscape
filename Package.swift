// swift-tools-version: 5.9
import PackageDescription
let package = Package(
    name: "ShootlocalPlanning",
    products: [.library(name: "PlanningCore", targets: ["PlanningCore"])],
    targets: [
        .target(name: "PlanningCore", path: "CineX.swiftpm/Planning/Core"),
        .testTarget(name: "PlanningCoreTests", dependencies: ["PlanningCore"], path: "Tests/PlanningCoreTests")
    ]
)
