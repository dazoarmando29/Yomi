import SwiftUI
import YomiKit

@main
struct YomiApp: App {
    @StateObject private var environment = AppEnvironment()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(environment)
                .preferredColorScheme(.dark)
                .tint(YomiTheme.primary)
        }
    }
}

/// Composition root: owns every YomiKit service and bootstraps them from
/// the app's Application Support directory.
@MainActor
final class AppEnvironment: ObservableObject {
    struct Services {
        let library: LibraryStore
        let preferences: PreferencesStore
        let registry: SourceRegistry
        let pipeline: ImagePipeline
        let downloads: DownloadManager
    }

    let baseDirectory: URL
    let library: LibraryStore
    let preferences: PreferencesStore
    let registry: SourceRegistry
    let pipeline: ImagePipeline
    let downloads: DownloadManager

    @Published var startupError: String?

    init() {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let base = support.appendingPathComponent("Yomi", isDirectory: true)
        self.baseDirectory = base

        // Bootstrap is synchronous and small (a few JSON files); failures fall
        // back to in-memory stores with a visible error instead of crashing.
        let (services, error) = Self.makeServices(base: base)
        let resolved = services ?? Self.makeFallbackServices()
        self.library = resolved.library
        self.preferences = resolved.preferences
        self.registry = resolved.registry
        self.pipeline = resolved.pipeline
        self.downloads = resolved.downloads
        self.startupError = error
    }

    private static func makeServices(base: URL) -> (Services?, String?) {
        do {
            try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
            let library = try LibraryStore(directory: base)
            let preferences = try PreferencesStore(directory: base)
            let registry = SourceRegistry(providers: [SampleSource()])
            let pipeline = ImagePipeline(
                directory: base.appendingPathComponent("image-cache", isDirectory: true)
            )
            let downloads = try DownloadManager(baseDirectory: base, library: library, registry: registry)
            return (
                Services(
                    library: library,
                    preferences: preferences,
                    registry: registry,
                    pipeline: pipeline,
                    downloads: downloads
                ),
                nil
            )
        } catch {
            return (nil, "Storage init failed: \(error.localizedDescription). Using temporary storage.")
        }
    }

    private static func makeFallbackServices() -> Services {
        let temporary = FileManager.default.temporaryDirectory
        let library = (try? LibraryStore.inMemory())!
        let preferences = (try? PreferencesStore(directory: temporary))!
        let registry = SourceRegistry(providers: [SampleSource()])
        let pipeline = ImagePipeline(directory: temporary)
        let downloads = (try? DownloadManager(
            baseDirectory: temporary,
            library: library,
            registry: registry
        ))!
        return Services(
            library: library,
            preferences: preferences,
            registry: registry,
            pipeline: pipeline,
            downloads: downloads
        )
    }

    /// Source names for backup export/reporting.
    func sourceName(for id: Int64) async -> String {
        await registry.provider(id: id)?.name ?? "Unknown source (\(id))"
    }
}

// MARK: - Root

struct RootView: View {
    @EnvironmentObject private var environment: AppEnvironment

    var body: some View {
        TabView {
            LibraryView()
                .tabItem { Label("Library", systemImage: "books.vertical.fill") }
            BrowseView()
                .tabItem { Label("Browse", systemImage: "globe") }
            HistoryView()
                .tabItem { Label("History", systemImage: "clock.fill") }
            DownloadsView()
                .tabItem { Label("Downloads", systemImage: "arrow.down.circle.fill") }
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape.fill") }
        }
        .background(YomiTheme.background)
    }
}
