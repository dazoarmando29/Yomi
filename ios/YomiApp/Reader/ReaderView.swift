import SwiftUI
import YomiKit

/// Reader state: page loading (online or downloaded), reading mode, progress
/// persistence and chapter transitions. The shared `AppEnvironment` is passed
/// into each call, matching the other view models in the app.
@MainActor
final class ReaderViewModel: ObservableObject {
    let manga: Manga
    @Published var chapter: Chapter
    @Published var chapters: [Chapter] = []
    @Published var pages: [ReaderPageModel] = []
    @Published var currentPageIndex = 0
    @Published var readingMode: ReadingMode = .webtoon
    @Published var isLoading = false
    @Published var errorMessage: String?
    @Published var isOffline = false
    @Published var showChrome = true

    private var sessionStartedAt = Date()
    private var recordedChapterIDs: Set<Int64> = []

    struct ReaderPageModel: Identifiable, Hashable {
        let id: Int
        let urlString: String
    }

    init(manga: Manga, chapter: Chapter) {
        self.manga = manga
        self.chapter = chapter
        let modeValue = Int(manga.viewerFlags & ReadingMode.mask)
        self.readingMode = ReadingMode(rawValue: modeValue) ?? .default
    }

    func load(environment: AppEnvironment) async {
        isLoading = true
        defer { isLoading = false }

        chapters = await environment.library.chapters(mangaID: manga.id)
        if readingMode == .default {
            readingMode = await environment.preferences.preferences.defaultReadingMode
        }

        // Offline-first: downloaded pages win over network fetches.
        if let localPages = await environment.downloads.downloadedPages(manga: manga, chapter: chapter) {
            pages = localPages.enumerated().map {
                ReaderPageModel(id: $0.offset, urlString: $0.element.absoluteString)
            }
            isOffline = true
            errorMessage = nil
        } else {
            isOffline = false
            do {
                guard let provider = await environment.registry.provider(id: manga.sourceID) else {
                    errorMessage = "Source unavailable on iOS. Downloaded chapters still open from the library."
                    return
                }
                let remotePages = try await provider.pageList(manga, chapter: chapter)
                pages = remotePages.map { ReaderPageModel(id: $0.index, urlString: $0.imageURL) }
                errorMessage = nil
            } catch {
                errorMessage = (error as? SourceError)?.message ?? error.localizedDescription
                return
            }
        }

        // Resume from the saved position (position store wins, chapter flag as fallback)
        let position = await environment.preferences.position(chapterID: chapter.id)
        let saved = position?.pageIndex ?? chapter.lastPageRead
        currentPageIndex = min(max(0, saved), max(0, pages.count - 1))
        await saveProgress(environment: environment)
        preload(environment: environment, around: currentPageIndex)
    }

    func saveProgress(environment: AppEnvironment) async {
        guard !pages.isEmpty else { return }
        let position = ReaderPosition(
            mangaID: manga.id,
            chapterID: chapter.id,
            pageIndex: currentPageIndex,
            pageCount: pages.count
        )
        try? await environment.preferences.savePosition(position)
        try? await environment.library.updateProgress(
            chapterID: chapter.id,
            page: currentPageIndex,
            pageCount: pages.count
        )
        if !recordedChapterIDs.contains(chapter.id) {
            recordedChapterIDs.insert(chapter.id)
            try? await environment.library.recordHistory(
                mangaID: manga.id,
                chapterID: chapter.id,
                duration: 0
            )
        }
    }

    func recordReadingTime(environment: AppEnvironment) async {
        let duration = Int64(Date().timeIntervalSince(sessionStartedAt))
        sessionStartedAt = Date()
        guard duration > 0 else { return }
        try? await environment.library.recordHistory(
            mangaID: manga.id,
            chapterID: chapter.id,
            duration: duration
        )
    }

    func preload(environment: AppEnvironment, around index: Int) {
        guard !pages.isEmpty else { return }
        let lower = max(0, index - 1)
        let upper = min(pages.count, index + 6)
        let urls = pages[lower..<upper].map { ($0.urlString, [String: String]()) }
        Task { await environment.pipeline.prefetch(urls: urls) }
    }

    func moveTo(page: Int, environment: AppEnvironment) async {
        guard !pages.isEmpty else { return }
        currentPageIndex = min(max(0, page), pages.count - 1)
        await saveProgress(environment: environment)
        preload(environment: environment, around: currentPageIndex)
    }

    /// Advances past the last page: transitions to the next chapter when there is one.
    func advanceBeyondEnd(environment: AppEnvironment) async {
        await recordReadingTime(environment: environment)
        guard let next = ChapterSort.nextChapter(after: chapter, in: chapters) else { return }
        chapter = next
        pages = []
        await load(environment: environment)
    }

    func rewindBeforeStart(environment: AppEnvironment) async {
        await recordReadingTime(environment: environment)
        guard let previous = ChapterSort.previousChapter(before: chapter, in: chapters) else { return }
        chapter = previous
        pages = []
        await load(environment: environment)
    }

    func setReadingMode(_ mode: ReadingMode, environment: AppEnvironment) async {
        readingMode = mode
        var updated = manga
        updated.viewerFlags = (updated.viewerFlags & ~ReadingMode.mask) | Int64(mode.rawValue)
        _ = try? await environment.library.upsertManga(updated)
    }
}

// MARK: - Reader container

struct ReaderView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @StateObject private var viewModel: ReaderViewModel
    @State private var showSettings = false
    @Environment(\.dismiss) private var dismiss

    init(manga: Manga, chapter: Chapter) {
        _viewModel = StateObject(wrappedValue: ReaderViewModel(manga: manga, chapter: chapter))
    }

    var body: some View {
        ZStack {
            YomiTheme.readerBackground.ignoresSafeArea()

            content

            if viewModel.showChrome {
                chrome
            }

            if viewModel.isLoading {
                ProgressView("Loading pages…")
                    .tint(YomiTheme.primary)
                    .padding(20)
                    .background(YomiTheme.surfaceHigh)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }
        }
        .statusBarHidden(!viewModel.showChrome)
        .task {
            await viewModel.load(environment: environment)
        }
        .onDisappear {
            Task { await viewModel.recordReadingTime(environment: environment) }
        }
        .sheet(isPresented: $showSettings) {
            ReaderSettingsSheet(viewModel: viewModel)
        }
    }

    @ViewBuilder
    private var content: some View {
        if let error = viewModel.errorMessage, viewModel.pages.isEmpty {
            VStack(spacing: 14) {
                Image(systemName: "exclamationmark.triangle")
                    .font(.largeTitle)
                    .foregroundStyle(YomiTheme.error)
                Text(error)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(YomiTheme.onSurface)
                    .padding(.horizontal, 32)
                Button("Retry") {
                    Task { await viewModel.load(environment: environment) }
                }
                .buttonStyle(.borderedProminent)
                .tint(YomiTheme.primary)
            }
        } else if viewModel.readingMode.isPaged {
            PagedReaderView(viewModel: viewModel)
        } else {
            WebtoonReaderView(viewModel: viewModel)
        }
    }

    private var chrome: some View {
        VStack {
            HStack {
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "chevron.left")
                        .font(.title3.weight(.semibold))
                        .padding(10)
                        .background(.ultraThinMaterial, in: Circle())
                }
                .accessibilityLabel("Back")
                Spacer()
                VStack(spacing: 2) {
                    Text(viewModel.manga.title)
                        .font(.footnote.weight(.semibold))
                        .lineLimit(1)
                    Text(viewModel.chapter.name)
                        .font(.caption2)
                        .foregroundStyle(YomiTheme.onSurfaceVariant)
                        .lineLimit(1)
                }
                Spacer()
                Button {
                    showSettings = true
                } label: {
                    Image(systemName: "gearshape")
                        .font(.title3)
                        .padding(10)
                        .background(.ultraThinMaterial, in: Circle())
                }
                .accessibilityLabel("Reader settings")
            }
            .foregroundStyle(YomiTheme.onSurface)
            .padding(.horizontal)
            .padding(.top, 4)

            Spacer()

            HStack {
                Button {
                    Task { await viewModel.rewindBeforeStart(environment: environment) }
                } label: {
                    Image(systemName: "chevron.backward.2")
                }
                .accessibilityLabel("Previous chapter")
                Spacer()
                Text("\(viewModel.currentPageIndex + 1) / \(max(1, viewModel.pages.count))")
                    .font(.caption.monospacedDigit())
                Spacer()
                Button {
                    Task { await viewModel.advanceBeyondEnd(environment: environment) }
                } label: {
                    Image(systemName: "chevron.forward.2")
                }
                .accessibilityLabel("Next chapter")
            }
            .foregroundStyle(YomiTheme.onSurface)
            .padding(.horizontal, 24)
            .padding(.vertical, 10)
            .background(.ultraThinMaterial)
            .clipShape(Capsule())
            .padding(.bottom, 8)
        }
    }
}

// MARK: - Settings sheet

struct ReaderSettingsSheet: View {
    @ObservedObject var viewModel: ReaderViewModel
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    @State private var keepScreenOn = true
    @State private var showPageNumber = true

    var body: some View {
        NavigationStack {
            List {
                Section("Reading mode") {
                    ForEach(ReadingMode.allCases.filter { $0 != .default }, id: \.self) { mode in
                        Button {
                            Task { await viewModel.setReadingMode(mode, environment: environment) }
                        } label: {
                            HStack {
                                Text(label(for: mode))
                                    .foregroundStyle(YomiTheme.onSurface)
                                Spacer()
                                if viewModel.readingMode == mode {
                                    Image(systemName: "checkmark")
                                        .foregroundStyle(YomiTheme.primary)
                                }
                            }
                        }
                    }
                }
                Section("Behaviour") {
                    Toggle("Keep screen on", isOn: $keepScreenOn)
                        .tint(YomiTheme.primary)
                    Toggle("Show page number", isOn: $showPageNumber)
                        .tint(YomiTheme.primary)
                }
                Section {
                    Button("Set current mode as default") {
                        Task {
                            try? await environment.preferences.update {
                                $0.defaultReadingMode = viewModel.readingMode
                            }
                        }
                    }
                    .foregroundStyle(YomiTheme.secondary)
                }
            }
            .scrollContentBackground(.hidden)
            .background(YomiTheme.background)
            .navigationTitle("Reader settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .task {
                let prefs = await environment.preferences.preferences
                keepScreenOn = prefs.keepScreenOn
                showPageNumber = prefs.showPageNumber
            }
            .onChange(of: keepScreenOn) { _, value in
                Task { try? await environment.preferences.update { $0.keepScreenOn = value } }
            }
            .onChange(of: showPageNumber) { _, value in
                Task { try? await environment.preferences.update { $0.showPageNumber = value } }
            }
        }
    }

    private func label(for mode: ReadingMode) -> String {
        switch mode {
        case .leftToRight: return "Left to right"
        case .rightToLeft: return "Right to left (manga)"
        case .vertical: return "Vertical (paged)"
        case .webtoon: return "Webtoon (long strip)"
        case .continuousVertical: return "Continuous vertical (gapped)"
        case .default: return "Default"
        }
    }
}
