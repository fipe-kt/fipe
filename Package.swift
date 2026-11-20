// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "FipeState",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "FipeState", targets: ["FipeState"]),
        .library(name: "FipeStateSwiftUI", targets: ["FipeStateSwiftUI"]),
    ],
    targets: [
        .target(name: "FipeState", path: "state/swift/Sources/FipeState"),
        .target(name: "FipeStateSwiftUI", dependencies: ["FipeState"], path: "state/swift/Sources/FipeStateSwiftUI"),
        .testTarget(
            name: "FipeStateTests",
            dependencies: ["FipeState"],
            path: "state/swift/Tests/FipeStateTests"
        ),
    ]
)
