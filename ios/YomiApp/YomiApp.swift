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

        // Bootstrap is synchronous and small (a few JSON files); failures fall
        // back to in-memory stores with a visible error instead of crashing.
        do {
            try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
            self.baseDirectory = base
            self.library = try LibraryStore(directory: base)
            self.preferences = try PreferencesStore(directory: base)
            self.registry = SourceRegistry(providers: [SampleSource()])
            self.pipeline = ImagePipeline(
                directory: base.appendingPathComponent("image-cache", isDirectory: true)
            )
            self.downloads = try DownloadManager(baseDirectory: base, library: library, registry: registry)
        } catch {
            self.startupError = "Storage init failed: \(error.localizedDescription). Using temporary storage."
            self.baseDirectory = base
            self.library = (try? LibraryStore.inMemory())!
            self.preferences = (try? PreferencesStore(directory: FileManager.default.temporaryDirectory))!
            self.registry = SourceRegistry(providers: [SampleSource()])
            self.pipeline = ImagePipeline(directory: FileManager.default.temporaryDirectory)
            self.downloads = (try? DownloadManager(
                baseDirectory: FileManager.default.temporaryDirectory,
                library: library,
                registry: registry
            ))!
        }
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
