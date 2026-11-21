import SwiftUI
import YomiKit

@MainActor
final class HistoryViewModel: ObservableObject {
    struct HistoryEntry: Identifiable {
        let id: Int64
        let manga: Manga
        let chapter: Chapter
        let readAt: Date
        let readDuration: Int64
    }

    @Published var entries: [HistoryEntry] = []

    func load(environment: AppEnvironment) async {
        let history = await environment.library.history()
        var built: [HistoryEntry] = []
        for item in history {
            guard let manga = await environment.library.manga(id: item.mangaID),
                  let chapter = await environment.library.chapter(id: item.chapterID) else { continue }
            built.append(
                HistoryEntry(
                    id: item.id,
                    manga: manga,
                    chapter: chapter,
                    readAt: item.readAt,
                    readDuration: item.readDuration
                )
            )
        }
        entries = built
    }

    func remove(_ entry: HistoryEntry, environment: AppEnvironment) async {
        try? await environment.library.removeHistory(chapterID: entry.chapter.id)
        await load(environment: environment)
    }
}

struct HistoryView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @StateObject private var viewModel = HistoryViewModel()

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.entries.isEmpty {
                    VStack(spacing: 10) {
                        Image(systemName: "clock")
                            .font(.system(size: 42))
                            .foregroundStyle(YomiTheme.outline)
                        Text("No reading history yet")
                            .foregroundStyle(YomiTheme.onSurface)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List {
                        ForEach(viewModel.entries) { entry in
                            NavigationLink(value: ReaderRoute(manga: entry.manga, chapter: entry.chapter)) {
                                HistoryRow(entry: entry)
                            }
                            .listRowBackground(YomiTheme.surfaceContainer)
                            .swipeActions {
                                Button(role: .destructive) {
                                    Task { await viewModel.remove(entry, environment: environment) }
                                } label: {
                                    Label("Remove", systemImage: "trash")
                                }
                            }
                        }
                    }
                    .scrollContentBackground(.hidden)
                }
            }
            .background(YomiTheme.background)
            .navigationTitle("History")
            .navigationDestination(for: ReaderRoute.self) { route in
                ReaderView(manga: route.manga, chapter: route.chapter)
            }
            .task {
                await viewModel.load(environment: environment)
            }
        }
    }
}

struct HistoryRow: View {
    let entry: HistoryViewModel.HistoryEntry

    var body: some View {
        HStack(spacing: 12) {
            SourceImageView(urlString: entry.manga.thumbnailURL)
                .frame(width: 44, height: 62)
            VStack(alignment: .leading, spacing: 3) {
                Text(entry.manga.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(YomiTheme.onSurface)
                    .lineLimit(1)
                Text(entry.chapter.name)
                    .font(.caption)
                    .foregroundStyle(YomiTheme.onSurfaceVariant)
                    .lineLimit(1)
                Text(entry.readAt, style: .relative)
                    .font(.caption2)
                    .foregroundStyle(YomiTheme.outline)
            }
            Spacer()
            if entry.readDuration > 0 {
                Text(durationText(entry.readDuration))
                    .font(.caption2)
                    .foregroundStyle(YomiTheme.secondary)
            }
        }
        .padding(.vertical, 4)
    }

    private func durationText(_ millis: Int64) -> String {
        let seconds = millis / 1000
        if seconds < 60 { return "\(seconds)s" }
        return "\(seconds / 60)m"
    }
}
