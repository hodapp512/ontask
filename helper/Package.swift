// swift-tools-version:5.9
import Foundation
import PackageDescription

// Info.plist is linked into the binary so it carries a bundle identifier (dev.ontask.helper) for code signing and the
// macOS privacy database. The build signs it afterwards (see desktop/build.gradle.kts, task buildHelper).
let infoPlist = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("Info.plist").path

let package = Package(
    name: "ontask-helper",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(
            name: "ontask-helper", path: "Sources/ontask-helper",
            linkerSettings: [.unsafeFlags(["-Xlinker", "-sectcreate", "-Xlinker", "__TEXT", "-Xlinker", "__info_plist", "-Xlinker", infoPlist])]
        ),
    ]
)
