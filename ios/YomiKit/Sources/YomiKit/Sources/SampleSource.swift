import Foundation

/// Deterministic in-app source used to verify the complete pipeline on iOS:
/// discovery → details → chapters → pages → downloads → offline reading.
///
/// All content is **original and procedurally generated** — no third-party
/// copyrighted material is bundled or referenced. Page images use the
/// `sample://` URL scheme and are rendered on-device by `SamplePageRenderer`.
///
/// The provider id derives from the same MD5 scheme as Android's
/// `HttpSource.generateId`, so backups map sources across platforms.
public struct SampleSource: SourceProvider {
    public let id: SourceID
    public let name = "Yomi Sample Source"
    public let lang = "en"
    public let supportsLatest = true

    public init() {
        self.id = SourceID.derive(name: "Yomi Sample Source", lang: "en", versionId: 1)
    }

    // MARK: Catalog

    struct Entry {
        let key: String
        let title: String
        let author: String
        let artist: String
        let description: String
        let genres: [String]
        let status: MangaStatus
        let chapterCount: Int
        let pagesPerChapter: Int
    }

    static let catalog: [Entry] = [
        Entry(
            key: "crimson-sky",
            title: "Crimson Sky Chronicle",
            author: "A. Sample",
            artist: "A. Sample",
            description: "A sky-pirate crew chases a storm that moves against the wind. "
                + "Sample entry shipped with Yomi to verify discovery, reading and downloads.",
            genres: ["Action", "Adventure", "Fantasy"],
            status: .ongoing,
            chapterCount: 4,
            pagesPerChapter: 6
        ),
        Entry(
            key: "neon-garden",
            title: "Neon Garden",
            author: "B. Example",
            artist: "B. Example",
            description: "A rooftop gardener discovers the city's power grid grows like a plant.",
            genres: ["Slice of Life", "Sci-Fi"],
            status: .completed,
            chapterCount: 3,
            pagesPerChapter: 5
        ),
        Entry(
            key: "quiet-ember",
            title: "Quiet Ember",
            author: "C. Fixture",
            artist: "C. Fixture",
            description: "Two lantern makers exchange anonymous letters across a mountain pass.",
            genres: ["Romance", "Drama"],
            status: .ongoing,
            chapterCount: 3,
            pagesPerChapter: 5
        ),
        Entry(
            key: "long-strip-theory",
            title: "Long Strip Theory",
            author: "D. Datum",
            artist: "D. Datum",
            description: "A deliberately long webtoon-style chapter for testing continuous scrolling, "
                + "memory behaviour and resumable progress.",
            genres: ["Webtoon", "Experimental"],
            status: .ongoing,
            chapterCount: 2,
            pagesPerChapter: 40
        ),
        Entry(
            key: "paper-moon",
            title: "Paper Moon Detective",
            author: "E. Mock",
            artist: "E. Mock",
            description: "A detective who can only investigate crimes committed during eclipses.",
            genres: ["Mystery", "Supernatural"],
            status: .onHiatus,
            chapterCount: 3,
            pagesPerChapter: 5
        ),
        Entry(
            key: "terminal-velvet",
            title: "Terminal Velvet",
            author: "F. Stub",
            artist: "F. Stub",
            description: "Late-night radio hosts take calls from tomorrow.",
            genres: ["Horror", "Comedy"],
            status: .cancelled,
            chapterCount: 2,
            pagesPerChapter: 4
        ),
    ]

    // MARK: - SourceProvider

    public func popularManga(page: Int) async throws -> SourceMangaPage {
        paginate(Self.catalog.map { sourceManga(for: $0) }, page: page)
    }

    public func latestManga(page: Int) async throws -> SourceMangaPage {
        let reversed = Self.catalog.reversed().map { sourceManga(for: $0) }
        return paginate(reversed, page: page)
    }

    public func searchManga(query: String, page: Int) async throws -> SourceMangaPage {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !trimmed.isEmpty else {
            return try await popularManga(page: page)
        }
        let matches = Self.catalog
            .filter {
                $0.title.lowercased().contains(trimmed)
                    || $0.author.lowercased().contains(trimmed)
                    || $0.genres.contains { $0.lowercased().contains(trimmed) }
            }
            .map { sourceManga(for: $0) }
        return paginate(matches, page: page)
    }

    public func mangaDetails(_ sourceManga: SourceManga) async throws -> Manga {
        guard let entry = Self.entry(forURL: sourceManga.url) else {
            throw SourceError.notFound
        }
        return Manga(
            sourceID: id,
            url: entry.key,
            title: entry.title,
            author: entry.author,
            artist: entry.artist,
            mangaDescription: entry.description,
            genres: entry.genres,
            status: entry.status,
            thumbnailURL: "sample://cover/\(entry.key).png",
            initialized: true
        )
    }

    public func chapterList(_ manga: Manga) async throws -> [SourceChapter] {
        guard let entry = Self.entry(forURL: manga.url) else {
            throw SourceError.notFound
        }
        return (0..<entry.chapterCount).map { index in
            SourceChapter(
                url: "\(entry.key)/chapter-\(index + 1)",
                name: "Chapter \(index + 1)",
                scanlator: "Yomi Sample",
                dateUpload: Date(timeIntervalSince1970: 1_700_000_000 + Double(index) * 86_400),
                sourceOrder: Int64(index),
                chapterNumber: Double(index + 1)
            )
        }
    }

    public func pageList(_ manga: Manga, chapter: Chapter) async throws -> [SourcePage] {
        guard let entry = Self.entry(forURL: manga.url) else {
            throw SourceError.notFound
        }
        let chapterIndex = Self.chapterIndex(fromChapterURL: chapter.url) ?? 0
        return (0..<entry.pagesPerChapter).map { page in
            SourcePage(
                index: page,
                imageURL: "sample://page/\(entry.key)/\(chapterIndex + 1)/\(page + 1).png"
            )
        }
    }

    // MARK: - Helpers

    static func entry(forURL url: String) -> Entry? {
        catalog.first { $0.key == url }
    }

    static func chapterIndex(fromChapterURL url: String) -> Int? {
        // "<key>/chapter-<n>" -> n-1
        guard let dash = url.lastIndex(of: "-") else { return nil }
        return Int(url[url.index(after: dash)...]).map { $0 - 1 }
    }

    private func sourceManga(for entry: Entry) -> SourceManga {
        SourceManga(
            url: entry.key,
            title: entry.title,
            thumbnailURL: "sample://cover/\(entry.key).png"
        )
    }

    private func paginate(_ items: [SourceManga], page: Int, pageSize: Int = 10) -> SourceMangaPage {
        guard page >= 1 else { return SourceMangaPage(mangas: [], hasNextPage: false) }
        let start = (page - 1) * pageSize
        guard start < items.count else { return SourceMangaPage(mangas: [], hasNextPage: false) }
        let end = min(start + pageSize, items.count)
        return SourceMangaPage(
            mangas: Array(items[start..<end]),
            hasNextPage: end < items.count
        )
    }
}
