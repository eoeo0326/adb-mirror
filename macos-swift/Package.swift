// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "adb-mirror",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(
            name: "adb-mirror",
            resources: [
                .copy("Resources/scrcpy-server"),
                .copy("Resources/AppIcon.png"),
            ]
        ),
    ]
)
