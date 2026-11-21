// swift-tools-version: 5.9
// YomiKit — cross-platform core of the Yomi iOS port.
// Pure Swift + Foundation only: unit-testable headlessly (`swift test`) on macOS,
// Windows and Linux; consumed by the Yomi SwiftUI app target.
import PackageDescription

let package = Package(
    name: "YomiKit",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),
        .watchOS(.v10),
        .tvOS(.v17),
    ],
    products: [
        .library(name: "YomiKit", targets: ["YomiKit"]),
    ],
    targets: [
        .target(
            name: "YomiKit",
            path: "Sources/YomiKit"
        ),
        .testTarget(
            name: "YomiKitTests",
            dependencies: ["YomiKit"],
            path: "Tests/YomiKitTests"
        ),
    ]
)
