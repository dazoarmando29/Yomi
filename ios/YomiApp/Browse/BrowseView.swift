import SwiftUI
import YomiKit

// MARK: - Browse (source list)

@MainActor
final class BrowseViewModel: ObservableObject {
    @Published var sources: [SourceProvider] = []
    @Published var isLoading = false

    func load(environment: AppEnvironment) async {
        isLoading = true
        sources = await environment.registry.all
        isLoading = false
    }
}

struct BrowseView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @StateObject private var viewModel = BrowseViewModel()

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(viewModel.sources, id: \.id) { source in
                        NavigationLink(value: source.id) {
                            HStack(spacing: 12) {
                                Image(systemName: "globe")
                                    .foregroundStyle(YomiTheme.primary)
                                    .frame(width: 32, height: 32)
                                    .background(YomiTheme.surfaceHigh)
                                    .clipShape(RoundedRectangle(cornerRadius: 8))
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(source.name)
                                        .foregroundStyle(YomiTheme.onSurface)
                                    Text(source.lang.uppercased())
                                        .font(.caption)
                                        .foregroundStyle(YomiTheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                } header: {
                    Text("Sources")
                } footer: {
                    Text("iOS runs compiled, signed source providers only. Android APK extensions are unsupported — see Settings › About.")
                }
            }
            .scrollContentBackground(.hidden)
            .background(YomiTheme.background)
            .navigationTitle("Browse")
            .navigationDestination(for: SourceID.self) { sourceID in
                SourceCatalogView(sourceID: sourceID)
            }
            .task {
                await viewModel.load(environment: environment)
            }
        }
    }
}

// MARK: - Source catalog (popular / latest / search)

@MainActor
final class SourceCatalogViewModel: ObservableObject {
    @Published var mangas: [SourceManga] = []
    @Published var searchText = ""
    @Published var mode: Mode = .popular
    @Published var errorMessage: String?
    @Published var hasNextPage = false
    @Published var isLoading = false

    enum Mode { case popular, latest, search }

    private var page = 1

    func load(environment: AppEnvironment, sourceID: SourceID) async {
        guard let provider = await environment.registry.provider(id: sourceID) else {
            errorMessage = "Source not available"
            return
        }
        isLoading = true
        defer { isLoading = false }
        do {
            page = 1
            let result: SourceMangaPage
            switch mode {
            case .popular:
                result = try await provider.popularManga(page: 1)
            case .latest:
                result = try await provider.latestManga(page: 1)
            case .search:
                result = try await provider.searchManga(query: searchText, page: 1)
            }
            mangas = result.mangas
            hasNextPage = result.hasNextPage
            errorMessage = nil
        } catch {
            errorMessage = (error as? SourceError)?.message ?? error.localizedDescription
        }
    }

    func loadMore(environment: AppEnvironment, sourceID: SourceID) async {
        guard hasNextPage, !isLoading, let provider = await environment.registry.provider(id: sourceID) else { return }
        isLoading = true
        defer { isLoading = false }
        do {
            page += 1
            let result: SourceMangaPage
            switch mode {
            case .popular:
                result = try await provider.popularManga(page: page)
            case .latest:
                result = try await provider.latestManga(page: page)
            case .search:
                result = try await provider.searchManga(query: searchText, page: page)
            }
            mangas.append(contentsOf: result.mangas)
            hasNextPage = result.hasNextPage
        } catch {
            errorMessage = (error as? SourceError)?.message ?? error.localizedDescription
        }
    }
}

struct SourceCatalogView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @StateObject private var viewModel = SourceCatalogViewModel()
    let sourceID: SourceID

    private let columns = [GridItem(.adaptive(minimum: 110), spacing: 12)]

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                if let error = viewModel.errorMessage {
                    ErrorBanner(message: error).padding(.horizontal)
                }
                Picker("Mode", selection: $viewModel.mode) {
                    Text("Popular").tag(SourceCatalogViewModel.Mode.popular)
                    Text("Latest").tag(SourceCatalogViewModel.Mode.latest)
                    Text("Search").tag(SourceCatalogViewModel.Mode.search)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)

                LazyVGrid(columns: columns, spacing: 12) {
                    ForEach(viewModel.mangas) { sourceManga in
                        NavigationLink(value: SourceMangaRoute(sourceID: sourceID, sourceManga: sourceManga)) {
                            VStack(alignment: .leading, spacing: 6) {
                                SourceImageView(urlString: sourceManga.thumbnailURL)
                                    .aspectRatio(2.0 / 3.0, contentMode: .fit)
                                Text(sourceManga.title)
                                    .font(.caption)
                                    .foregroundStyle(YomiTheme.onSurface)
                                    .lineLimit(2)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal)

                if viewModel.hasNextPage {
                    Button("Load more") {
                        Task { await viewModel.loadMore(environment: environment, sourceID: sourceID) }
                    }
                    .tint(YomiTheme.primary)
                }
            }
            .padding(.vertical)
        }
        .background(YomiTheme.background)
        .navigationTitle("Source")
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(for: SourceMangaRoute.self) { route in
            MangaDetailsView(manga: nil, sourceID: route.sourceID, sourceManga: route.sourceManga)
        }
        .task {
            await viewModel.load(environment: environment, sourceID: sourceID)
        }
        .onChange(of: viewModel.mode) { _, _ in
            Task { await viewModel.load(environment: environment, sourceID: sourceID) }
        }
        .searchable(text: $viewModel.searchText, prompt: "Search this source")
        .onSubmit(of: .search) {
            viewModel.mode = .search
            Task { await viewModel.load(environment: environment, sourceID: sourceID) }
        }
    }
}

/// Route value for source-external manga before they enter the library.
struct SourceMangaRoute: Hashable {
    let sourceID: SourceID
    let sourceManga: SourceManga
}
