// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "FipeState",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "FipeState", targets: ["FipeState"])],
    targets: [
        .target(name: "FipeState", path: "state/swift/Sources/FipeState"),
        .testTarget(
            name: "FipeStateTests",
            dependencies: ["FipeState"],
            path: "state/swift/Tests/FipeStateTests"
        ),
    ]
)
