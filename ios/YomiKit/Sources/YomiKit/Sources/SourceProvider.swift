import Foundation

// MARK: - Source-side models (mirror `eu.kanade.tachiyomi.source.model`)

/// Lightweight manga entry returned by discovery calls (`SManga` on Android).
public struct SourceManga: Identifiable, Codable, Sendable, Equatable, Hashable {
    /// Stable identity inside its source (the remote URL/key).
    public var url: String
    public var title: String
    public var thumbnailURL: String?

    public var id: String { url }

    public init(url: String, title: String, thumbnailURL: String? = nil) {
        self.url = url
        self.title = title
        self.thumbnailURL = thumbnailURL
    }
}

/// Page of discovery results (`MangasPage` on Android).
public struct SourceMangaPage: Sendable {
    public var mangas: [SourceManga]
    public var hasNextPage: Bool

    public init(mangas: [SourceManga], hasNextPage: Bool) {
        self.mangas = mangas
        self.hasNextPage = hasNextPage
    }
}

/// Remote chapter descriptor (`SChapter` on Android).
public struct SourceChapter: Sendable, Equatable {
    public var url: String
    public var name: String
    public var scanlator: String?
    public var dateUpload: Date?
    public var sourceOrder: Int64
    public var chapterNumber: Double

    public init(
        url: String,
        name: String,
        scanlator: String? = nil,
        dateUpload: Date? = nil,
        sourceOrder: Int64 = 0,
        chapterNumber: Double = -1
    ) {
        self.url = url
        self.name = name
        self.scanlator = scanlator
        self.dateUpload = dateUpload
        self.sourceOrder = sourceOrder
        self.chapterNumber = chapterNumber
    }
}

/// A single page of a chapter (`Page` on Android). `imageURL` may use the
/// `sample://` scheme served by `SamplePageRenderer`.
public struct SourcePage: Identifiable, Sendable, Equatable {
    public var index: Int
    public var imageURL: String
    public var headers: [String: String]

    public var id: Int { index }

    public init(index: Int, imageURL: String, headers: [String: String] = [:]) {
        self.index = index
        self.imageURL = imageURL
        self.headers = headers
    }
}

// MARK: - Provider protocol

public enum SourceError: Error, Sendable, Equatable {
    case notFound
    case network(String)
    case parse(String)
    case unauthorized
    case unsupported(String)
    case cancelled

    public var message: String {
        switch self {
        case .notFound: return "Not found"
        case .network(let detail): return "Network error: \(detail)"
        case .parse(let detail): return "Parse error: \(detail)"
        case .unauthorized: return "Unauthorized"
        case .unsupported(let detail): return "Unsupported: \(detail)"
        case .cancelled: return "Cancelled"
        }
    }
}

/// iOS-side equivalent of the Android `Source`/`CatalogueSource`/`HttpSource`
/// contract. Implementations are compiled into the app (or data-driven later) —
/// iOS cannot load Android extension APKs (see IOS_COMPATIBILITY.md).
public protocol SourceProvider: Sendable {
    /// Stable id — derived with `SourceID.derive` for backup parity.
    var id: SourceID { get }
    var name: String { get }
    var lang: String { get }
    /// `true` when the provider has a "latest updates" listing.
    var supportsLatest: Bool { get }

    func popularManga(page: Int) async throws -> SourceMangaPage
    func latestManga(page: Int) async throws -> SourceMangaPage
    func searchManga(query: String, page: Int) async throws -> SourceMangaPage
    func mangaDetails(_ sourceManga: SourceManga) async throws -> Manga
    func chapterList(_ manga: Manga) async throws -> [SourceChapter]
    func pageList(_ manga: Manga, chapter: Chapter) async throws -> [SourcePage]
}

public extension SourceProvider {
    func latestManga(page: Int) async throws -> SourceMangaPage {
        SourceMangaPage(mangas: [], hasNextPage: false)
    }
}

// MARK: - Registry (trust boundary)

/// Registry of the source providers compiled into the app.
/// This is the iOS trust boundary: no remote code is ever downloaded or executed.
public actor SourceRegistry {
    private var providers: [Int64: SourceProvider] = [:]
    private var order: [Int64] = []

    public init() {}

    public init(providers: [SourceProvider]) {
        for provider in providers {
            self.providers[provider.id.rawValue] = provider
            self.order.append(provider.id.rawValue)
        }
    }

    public func register(_ provider: SourceProvider) {
        if providers[provider.id.rawValue] == nil {
            order.append(provider.id.rawValue)
        }
        providers[provider.id.rawValue] = provider
    }

    public func provider(id: Int64) -> SourceProvider? {
        providers[id]
    }

    public func provider(id: SourceID) -> SourceProvider? {
        providers[id.rawValue]
    }

    public var all: [SourceProvider] {
        order.compactMap { providers[$0] }
    }
}
