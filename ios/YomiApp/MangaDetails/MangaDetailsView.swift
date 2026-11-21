import SwiftUI
import YomiKit

// MARK: - Manga details

@MainActor
final class MangaDetailsViewModel: ObservableObject {
    @Published var manga: Manga?
    @Published var chapters: [Chapter] = []
    @Published var isLoading = false
    @Published var errorMessage: String?
    @Published var isInLibrary = false

    /// Manga sourced externally (not yet in the library).
    private var sourceManga: SourceManga?
    private var sourceID: SourceID?

    init(manga: Manga?, sourceID: SourceID? = nil, sourceManga: SourceManga? = nil) {
        self.manga = manga
        self.sourceID = sourceID ?? manga?.sourceID
        self.sourceManga = sourceManga
        self.isInLibrary = manga?.isInLibrary ?? false
    }

    func load(environment: AppEnvironment) async {
        isLoading = true
        defer { isLoading = false }
        do {
            guard let sourceID else {
                errorMessage = "No source"
                return
            }
            guard let provider = await environment.registry.provider(id: sourceID) else {
                // Manga from a missing source: show stored data, disable refresh.
                if let stored = manga {
                    chapters = await environment.library.chapters(mangaID: stored.id)
                }
                errorMessage = "This source is not available on iOS. Chapters can't be refreshed, but your data is preserved."
                return
            }

            if var current = manga {
                // Refresh remote details into the stored record.
                let remote = try await provider.mangaDetails(
                    sourceManga ?? SourceManga(url: current.url, title: current.title, thumbnailURL: current.thumbnailURL)
                )
                current.title = remote.title.isEmpty ? current.title : remote.title
                current.author = remote.author ?? current.author
                current.artist = remote.artist ?? current.artist
                current.mangaDescription = remote.mangaDescription ?? current.mangaDescription
                current.genres = remote.genres.isEmpty ? current.genres : remote.genres
                current.status = remote.status
                current.thumbnailURL = remote.thumbnailURL ?? current.thumbnailURL
                manga = try await environment.library.upsertManga(current)
            } else if let sourceManga {
                let remote = try await provider.mangaDetails(sourceManga)
                manga = try await environment.library.upsertManga(remote)
            }

            if let manga {
                let remoteChapters = try await provider.chapterList(manga)
                chapters = try await environment.library.replaceChapters(
                    mangaID: manga.id,
                    remoteChapters: remoteChapters
                )
                errorMessage = nil
            }
        } catch {
            errorMessage = (error as? SourceError)?.message ?? error.localizedDescription
            if let manga {
                chapters = await environment.library.chapters(mangaID: manga.id)
            }
        }
    }

    func toggleLibrary(environment: AppEnvironment) async {
        guard let manga else { return }
        let newState = !isInLibrary
        try? await environment.library.setFavorite(id: manga.id, favorite: newState)
        isInLibrary = newState
        self.manga = await environment.library.manga(id: manga.id)
    }

    func download(chapters: [Chapter], environment: AppEnvironment) async {
        guard let manga else { return }
        try? await environment.downloads.enqueue(mangaID: manga.id, chapterIDs: chapters.map(\.id))
    }

    func refresh(environment: AppEnvironment) async {
        await load(environment: environment)
    }
}

struct MangaDetailsView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @StateObject private var viewModel: MangaDetailsViewModel
    @State private var showDownloadOptions = false

    init(manga: Manga?, sourceID: SourceID? = nil, sourceManga: SourceManga? = nil) {
        _viewModel = StateObject(
            wrappedValue: MangaDetailsViewModel(manga: manga, sourceID: sourceID, sourceManga: sourceManga)
        )
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let error = viewModel.errorMessage {
                    ErrorBanner(message: error).padding(.horizontal)
                }
                header
                actions
                genres
                description
                chapterSection
            }
            .padding(.vertical)
        }
        .background(YomiTheme.background)
        .navigationTitle(viewModel.manga?.title ?? "Manga")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await viewModel.load(environment: environment)
        }
        .refreshable {
            await viewModel.refresh(environment: environment)
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button {
                        Task { await viewModel.refresh(environment: environment) }
                    } label: {
                        Label("Refresh chapters", systemImage: "arrow.clockwise")
                    }
                    Button {
                        showDownloadOptions = true
                    } label: {
                        Label("Download all", systemImage: "arrow.down.circle")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .confirmationDialog("Download", isPresented: $showDownloadOptions, titleVisibility: .visible) {
            Button("Download all chapters") {
                Task { await viewModel.download(chapters: viewModel.chapters, environment: environment) }
            }
            Button("Cancel", role: .cancel) {}
        }
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 14) {
            SourceImageView(urlString: viewModel.manga?.thumbnailURL)
                .frame(width: 116, height: 168)
            VStack(alignment: .leading, spacing: 8) {
                Text(viewModel.manga?.title ?? "Loading…")
                    .font(.title3.bold())
                    .foregroundStyle(YomiTheme.onSurface)
                Text(viewModel.manga?.author ?? "Unknown author")
                    .font(.subheadline)
                    .foregroundStyle(YomiTheme.onSurfaceVariant)
                HStack(spacing: 6) {
                    Image(systemName: "circle.fill")
                        .font(.system(size: 6))
                        .foregroundStyle(statusColor)
                    Text(statusText)
                        .font(.caption)
                        .foregroundStyle(YomiTheme.onSurfaceVariant)
                }
                Text("\(viewModel.chapters.count) chapters")
                    .font(.caption)
                    .foregroundStyle(YomiTheme.outline)
            }
            Spacer()
        }
        .padding(.horizontal)
    }

    private var actions: some View {
        HStack(spacing: 10) {
            Button {
                Task { await viewModel.toggleLibrary(environment: environment) }
            } label: {
                Label(
                    viewModel.isInLibrary ? "In library" : "Add to library",
                    systemImage: viewModel.isInLibrary ? "checkmark.circle.fill" : "plus.circle"
                )
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(viewModel.isInLibrary ? YomiTheme.primaryContainer : YomiTheme.primary)
            .foregroundStyle(viewModel.isInLibrary ? YomiTheme.onPrimary : YomiTheme.onPrimary)

            if let firstUnread = viewModel.chapters.first(where: { !$0.read }) {
                NavigationLink(value: ReaderRoute(manga: viewModel.manga!, chapter: firstUnread)) {
                    Label("Read", systemImage: "book.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .tint(YomiTheme.secondary)
            }
        }
        .padding(.horizontal)
        .navigationDestination(for: ReaderRoute.self) { route in
            ReaderView(manga: route.manga, chapter: route.chapter)
        }
    }

    private var genres: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(viewModel.manga?.genres ?? [], id: \.self) { genre in
                    YomiChip(text: genre)
                }
            }
            .padding(.horizontal)
        }
    }

    private var description: some View {
        Text(viewModel.manga?.mangaDescription ?? "No description.")
            .font(.footnote)
            .foregroundStyle(YomiTheme.onSurfaceVariant)
            .padding(.horizontal)
    }

    private var chapterSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Chapters")
                    .font(.headline)
                    .foregroundStyle(YomiTheme.onSurface)
                Spacer()
                Button("Download all") {
                    Task { await viewModel.download(chapters: viewModel.chapters, environment: environment) }
                }
                .font(.caption)
                .tint(YomiTheme.secondary)
            }
            .padding(.horizontal)

            ForEach(viewModel.chapters) { chapter in
                if let manga = viewModel.manga {
                    NavigationLink(value: ReaderRoute(manga: manga, chapter: chapter)) {
                        ChapterRow(chapter: chapter)
                    }
                    .buttonStyle(.plain)
                    .contextMenu {
                        Button {
                            Task { await viewModel.download(chapters: [chapter], environment: environment) }
                        } label: {
                            Label("Download", systemImage: "arrow.down.circle")
                        }
                        Button {
                            Task {
                                try? await environment.library.setRead(chapterID: chapter.id, read: !chapter.read)
                                await viewModel.load(environment: environment)
                            }
                        } label: {
                            Label(chapter.read ? "Mark unread" : "Mark read", systemImage: "checkmark.circle")
                        }
                    }
                }
            }
        }
    }

    private var statusText: String {
        switch viewModel.manga?.status {
        case .ongoing: return "Ongoing"
        case .completed: return "Completed"
        case .licensed: return "Licensed"
        case .publishingFinished: return "Publishing finished"
        case .onHiatus: return "On hiatus"
        case .cancelled: return "Cancelled"
        default: return "Unknown status"
        }
    }

    private var statusColor: Color {
        switch viewModel.manga?.status {
        case .ongoing: return YomiTheme.secondary
        case .completed: return YomiTheme.primary
        default: return YomiTheme.outline
        }
    }
}

/// Route into the reader.
struct ReaderRoute: Hashable {
    let manga: Manga
    let chapter: Chapter
}

struct ChapterRow: View {
    let chapter: Chapter

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: chapter.read ? "checkmark.circle.fill" : "circle")
                .foregroundStyle(chapter.read ? YomiTheme.primary : YomiTheme.outline)
            VStack(alignment: .leading, spacing: 2) {
                Text(chapter.name)
                    .font(.subheadline)
                    .foregroundStyle(YomiTheme.onSurface)
                    .lineLimit(1)
                HStack(spacing: 8) {
                    if let scanlator = chapter.scanlator {
                        Text(scanlator)
                            .font(.caption2)
                            .foregroundStyle(YomiTheme.onSurfaceVariant)
                    }
                    if chapter.lastPageRead > 0 && !chapter.read {
                        Text("Page \(chapter.lastPageRead + 1)")
                            .font(.caption2)
                            .foregroundStyle(YomiTheme.secondary)
                    }
                    if chapter.bookmark {
                        Image(systemName: "bookmark.fill")
                            .font(.caption2)
                            .foregroundStyle(YomiTheme.tertiary)
                    }
                }
            }
            Spacer()
        }
        .padding(12)
        .background(YomiTheme.surfaceContainer)
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .padding(.horizontal)
    }
}
