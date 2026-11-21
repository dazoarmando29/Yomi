import SwiftUI
import YomiKit

// MARK: - Library view model

@MainActor
final class LibraryViewModel: ObservableObject {
    @Published var entries: [LibraryEntry] = []
    @Published var categories: [Category] = []
    @Published var searchText = ""
    @Published var selectedCategoryID: Int64?
    @Published var displayMode: LibraryDisplayMode = .comfortableGrid
    @Published var sortMode: LibrarySortMode = .alphabetical
    @Published var sortDescending = false
    @Published var errorMessage: String?
    @Published var continueReading: [ContinueReadingItem] = []
    @Published var isLoading = false

    struct LibraryEntry: Identifiable {
        let manga: Manga
        let chapterCount: Int
        let unreadCount: Int
        let id: Int64
    }

    struct ContinueReadingItem: Identifiable {
        let id: Int64
        let manga: Manga
        let chapter: Chapter
        let progress: Double
    }

    func load(environment: AppEnvironment) async {
        isLoading = true
        defer { isLoading = false }
        do {
            let prefs = await environment.preferences.preferences
            displayMode = prefs.libraryDisplayMode
            sortMode = prefs.librarySortMode
            sortDescending = prefs.sortDescending

            categories = await environment.library.categories()
            let mangas = await environment.library.libraryMangas(categoryID: selectedCategoryID)
            var built: [LibraryEntry] = []
            for manga in mangas {
                let chapters = await environment.library.chapters(mangaID: manga.id)
                let unread = chapters.filter { !$0.read }.count
                built.append(LibraryEntry(manga: manga, chapterCount: chapters.count, unreadCount: unread, id: manga.id))
            }
            entries = sort(built, query: searchText)
            continueReading = await buildContinueReading(environment: environment, mangas: mangas)
        }
    }

    private func buildContinueReading(environment: AppEnvironment, mangas: [Manga]) async -> [ContinueReadingItem] {
        var items: [ContinueReadingItem] = []
        for manga in mangas {
            guard items.count < 10 else { break }
            let chapters = await environment.library.chapters(mangaID: manga.id)
            guard let target = chapters.first(where: { !$0.read && $0.lastPageRead > 0 })
                ?? chapters.first(where: { !$0.read }) else { continue }
            let position = await environment.preferences.position(chapterID: target.id)
            let progress = position?.fraction ?? (target.lastPageRead > 0 ? 0.25 : 0)
            items.append(
                ContinueReadingItem(id: target.id, manga: manga, chapter: target, progress: progress)
            )
        }
        return items
    }

    func search(_ query: String, environment: AppEnvironment) async {
        searchText = query
        await load(environment: environment)
    }

    func setCategory(_ id: Int64?, environment: AppEnvironment) async {
        selectedCategoryID = id
        await load(environment: environment)
    }

    private func sort(_ entries: [LibraryEntry], query: String) -> [LibraryEntry] {
        let filtered: [LibraryEntry]
        if query.isEmpty {
            filtered = entries
        } else {
            let search = LibrarySearch(query: query)
            filtered = entries.filter { search.matches($0.manga) }
        }
        let sorted: [LibraryEntry]
        switch sortMode {
        case .alphabetical:
            sorted = filtered.sorted {
                $0.manga.title.localizedCaseInsensitiveCompare($1.manga.title) == .orderedAscending
            }
        case .dateAdded:
            sorted = filtered.sorted { ($0.manga.dateAdded ?? .distantPast) < ($1.manga.dateAdded ?? .distantPast) }
        case .lastUpdated:
            sorted = filtered.sorted { ($0.manga.dateAdded ?? .distantPast) < ($1.manga.dateAdded ?? .distantPast) }
        case .lastRead:
            sorted = filtered.sorted { $0.id < $1.id }
        case .unread:
            sorted = filtered.sorted { $0.unreadCount > $1.unreadCount }
        case .totalChapters:
            sorted = filtered.sorted { $0.chapterCount > $1.chapterCount }
        case .chapterNumber:
            sorted = filtered.sorted { $0.chapterCount < $1.chapterCount }
        }
        return sortDescending ? sorted.reversed() : sorted
    }
}

// MARK: - Library screen

struct LibraryView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @StateObject private var viewModel = LibraryViewModel()
    @State private var showSortSheet = false

    private let columns = [
        GridItem(.adaptive(minimum: 110), spacing: 12),
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if let error = environment.startupError {
                        ErrorBanner(message: error).padding(.horizontal)
                    }

                    if !viewModel.continueReading.isEmpty {
                        continueReadingRow
                    }

                    categoryBar

                    if viewModel.entries.isEmpty {
                        emptyState
                    } else if viewModel.displayMode == .list {
                        listContent
                    } else {
                        gridContent
                    }
                }
                .padding(.vertical)
            }
            .background(YomiTheme.background)
            .navigationTitle("Yomi")
            .searchable(text: $viewModel.searchText, prompt: "Search library")
            .onChange(of: viewModel.searchText) { _, newValue in
                Task { await viewModel.search(newValue, environment: environment) }
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showSortSheet = true
                    } label: {
                        Image(systemName: "arrow.up.arrow.down.circle")
                    }
                    .accessibilityLabel("Sort and filter")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        Task { await viewModel.load(environment: environment) }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .accessibilityLabel("Refresh library")
                }
            }
            .sheet(isPresented: $showSortSheet) {
                LibrarySortSheet(viewModel: viewModel)
            }
            .task {
                await viewModel.load(environment: environment)
            }
        }
    }

    private var categoryBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                Button {
                    Task { await viewModel.setCategory(nil, environment: environment) }
                } label: {
                    YomiChip(text: "All", selected: viewModel.selectedCategoryID == nil)
                }
                ForEach(viewModel.categories) { category in
                    Button {
                        Task { await viewModel.setCategory(category.id, environment: environment) }
                    } label: {
                        YomiChip(text: category.name, selected: viewModel.selectedCategoryID == category.id)
                    }
                }
            }
            .padding(.horizontal)
        }
    }

    private var gridContent: some View {
        LazyVGrid(columns: columns, spacing: 12) {
            ForEach(viewModel.entries) { entry in
                NavigationLink(value: entry.manga) {
                    MangaGridCell(entry: entry)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal)
        .navigationDestination(for: Manga.self) { manga in
            MangaDetailsView(manga: manga)
        }
    }

    private var listContent: some View {
        LazyVStack(spacing: 10) {
            ForEach(viewModel.entries) { entry in
                NavigationLink(value: entry.manga) {
                    MangaListRow(entry: entry)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal)
        .navigationDestination(for: Manga.self) { manga in
            MangaDetailsView(manga: manga)
        }
    }

    private var continueReadingRow: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Continue reading")
                .font(.headline)
                .foregroundStyle(YomiTheme.onSurface)
                .padding(.horizontal)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(viewModel.continueReading) { item in
                        NavigationLink(value: item.manga) {
                            ContinueReadingCard(item: item)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal)
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "books.vertical")
                .font(.system(size: 42))
                .foregroundStyle(YomiTheme.outline)
            Text("Your library is empty")
                .font(.headline)
                .foregroundStyle(YomiTheme.onSurface)
            Text("Browse a source and add manga to your library.")
                .font(.subheadline)
                .foregroundStyle(YomiTheme.onSurfaceVariant)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 80)
    }
}

// MARK: - Cells

struct MangaGridCell: View {
    let entry: LibraryViewModel.LibraryEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ZStack(alignment: .topTrailing) {
                SourceImageView(urlString: entry.manga.thumbnailURL)
                    .aspectRatio(2.0 / 3.0, contentMode: .fit)
                UnreadBadge(count: entry.unreadCount)
                    .padding(6)
            }
            Text(entry.manga.title)
                .font(.caption)
                .foregroundStyle(YomiTheme.onSurface)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(entry.manga.title), \(entry.unreadCount) unread")
    }
}

struct MangaListRow: View {
    let entry: LibraryViewModel.LibraryEntry

    var body: some View {
        HStack(spacing: 12) {
            SourceImageView(urlString: entry.manga.thumbnailURL)
                .frame(width: 56, height: 80)
            VStack(alignment: .leading, spacing: 4) {
                Text(entry.manga.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(YomiTheme.onSurface)
                    .lineLimit(1)
                Text(entry.manga.author ?? "Unknown author")
                    .font(.caption)
                    .foregroundStyle(YomiTheme.onSurfaceVariant)
                    .lineLimit(1)
                Text("\(entry.chapterCount) chapters")
                    .font(.caption2)
                    .foregroundStyle(YomiTheme.outline)
            }
            Spacer()
            UnreadBadge(count: entry.unreadCount)
        }
        .padding(10)
        .background(YomiTheme.surfaceContainer)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

struct ContinueReadingCard: View {
    let item: LibraryViewModel.ContinueReadingItem

    var body: some View {
        HStack(spacing: 10) {
            SourceImageView(urlString: item.manga.thumbnailURL)
                .frame(width: 44, height: 62)
            VStack(alignment: .leading, spacing: 4) {
                Text(item.manga.title)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(YomiTheme.onSurface)
                    .lineLimit(1)
                Text(item.chapter.name)
                    .font(.caption2)
                    .foregroundStyle(YomiTheme.onSurfaceVariant)
                    .lineLimit(1)
                YomiProgressBar(fraction: item.progress)
                    .frame(width: 120)
            }
        }
        .padding(10)
        .background(YomiTheme.surfaceContainer)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

// MARK: - Sort sheet

struct LibrarySortSheet: View {
    @ObservedObject var viewModel: LibraryViewModel
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("Sort by") {
                    ForEach(LibrarySortMode.allCases, id: \.self) { mode in
                        Button {
                            viewModel.sortMode = mode
                            Task {
                                try? await environment.preferences.update {
                                    $0.librarySortMode = mode
                                }
                                await viewModel.load(environment: environment)
                                dismiss()
                            }
                        } label: {
                            HStack {
                                Text(mode.rawValue.capitalized)
                                    .foregroundStyle(YomiTheme.onSurface)
                                Spacer()
                                if viewModel.sortMode == mode {
                                    Image(systemName: "checkmark")
                                        .foregroundStyle(YomiTheme.primary)
                                }
                            }
                        }
                    }
                }
                Section("Direction") {
                    Toggle("Descending", isOn: $viewModel.sortDescending)
                        .tint(YomiTheme.primary)
                        .onChange(of: viewModel.sortDescending) { _, value in
                            Task {
                                try? await environment.preferences.update { $0.sortDescending = value }
                                await viewModel.load(environment: environment)
                            }
                        }
                }
                Section("Display") {
                    ForEach(LibraryDisplayMode.allCases, id: \.self) { mode in
                        Button {
                            viewModel.displayMode = mode
                            Task {
                                try? await environment.preferences.update { $0.libraryDisplayMode = mode }
                                await viewModel.load(environment: environment)
                                dismiss()
                            }
                        } label: {
                            HStack {
                                Text(label(for: mode))
                                    .foregroundStyle(YomiTheme.onSurface)
                                Spacer()
                                if viewModel.displayMode == mode {
                                    Image(systemName: "checkmark")
                                        .foregroundStyle(YomiTheme.primary)
                                }
                            }
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(YomiTheme.background)
            .navigationTitle("Sort & display")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    private func label(for mode: LibraryDisplayMode) -> String {
        switch mode {
        case .compactGrid: return "Compact grid"
        case .comfortableGrid: return "Comfortable grid"
        case .list: return "List"
        }
    }
}
