// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "ontask-helper",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(name: "ontask-helper", path: "Sources/ontask-helper"),
    ]
)
