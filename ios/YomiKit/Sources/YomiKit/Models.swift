import Foundation

// MARK: - Source identity

/// Stable identifier of a catalog source. Mirrors `HttpSource.generateId` on Android:
/// first 64 bits (big-endian) of the MD5 of `"name.lowercased()/lang/versionId"`,
/// with the sign bit cleared. Backups reference sources by this id.
public struct SourceID: Hashable, Codable, Sendable, CustomStringConvertible {
    public let rawValue: Int64

    public init(rawValue: Int64) {
        self.rawValue = rawValue
    }

    /// Same derivation as `eu.kanade.tachiyomi.source.online.HttpSource.generateId`.
    public static func derive(name: String, lang: String, versionId: Int = 1) -> SourceID {
        let key = "\(name.lowercased())/\(lang)/\(versionId)"
        let digest = MD5.hash(Array(key.utf8))
        var value: Int64 = 0
        for byte in digest.prefix(8) {
            value = (value << 8) | Int64(byte)
        }
        value &= Int64.max // clear the sign bit
        return SourceID(rawValue: value)
    }

    public var description: String { String(rawValue) }
}

// MARK: - Manga

public enum MangaStatus: Int, Codable, Sendable, CaseIterable {
    case unknown = 0
    case ongoing = 1
    case completed = 2
    case licensed = 3
    case publishingFinished = 4
    case onHiatus = 5
    case cancelled = 6
}

public enum UpdateStrategy: Int, Codable, Sendable {
    case alwaysUpdate = 0
    case onlyFetchOnce = 1
}

/// Library manga. Field semantics mirror `tachiyomi.domain.manga.model.Manga`
/// (backed by the `manga` SQLDelight table on Android).
public struct Manga: Identifiable, Codable, Sendable, Equatable, Hashable {
    public var id: Int64
    public var sourceID: SourceID
    /// Remote key of the manga inside its source (the `remote_url` column).
    public var url: String
    public var title: String
    public var author: String?
    public var artist: String?
    public var mangaDescription: String?
    public var genres: [String]
    public var status: MangaStatus
    public var thumbnailURL: String?
    /// Non-nil when the manga is in the library (mirrors `user_favorite_at`).
    public var favoriteAt: Date?
    public var dateAdded: Date?
    public var notes: String
    /// Opaque viewer flags carried through backups untouched (`user_reader_flags`).
    public var viewerFlags: Int64
    /// Opaque chapter flags carried through backups untouched (`user_chapter_flags`).
    public var chapterFlags: Int64
    public var updateStrategy: UpdateStrategy
    public var initialized: Bool

    public init(
        id: Int64 = 0,
        sourceID: SourceID,
        url: String,
        title: String,
        author: String? = nil,
        artist: String? = nil,
        mangaDescription: String? = nil,
        genres: [String] = [],
        status: MangaStatus = .unknown,
        thumbnailURL: String? = nil,
        favoriteAt: Date? = nil,
        dateAdded: Date? = nil,
        notes: String = "",
        viewerFlags: Int64 = 0,
        chapterFlags: Int64 = 0,
        updateStrategy: UpdateStrategy = .alwaysUpdate,
        initialized: Bool = true
    ) {
        self.id = id
        self.sourceID = sourceID
        self.url = url
        self.title = title
        self.author = author
        self.artist = artist
        self.mangaDescription = mangaDescription
        self.genres = genres
        self.status = status
        self.thumbnailURL = thumbnailURL
        self.favoriteAt = favoriteAt
        self.dateAdded = dateAdded
        self.notes = notes
        self.viewerFlags = viewerFlags
        self.chapterFlags = chapterFlags
        self.updateStrategy = updateStrategy
        self.initialized = initialized
    }

    public var isInLibrary: Bool { favoriteAt != nil }
}

// MARK: - Chapter

/// Chapter of a manga. Mirrors `tachiyomi.domain.chapter.model.Chapter` /
/// the `chapter` SQLDelight table.
public struct Chapter: Identifiable, Codable, Sendable, Equatable, Hashable {
    public var id: Int64
    public var mangaID: Int64
    public var url: String
    public var name: String
    public var scanlator: String?
    public var chapterNumber: Double
    public var dateUpload: Date?
    public var sourceOrder: Int64
    public var read: Bool
    public var bookmark: Bool
    /// Zero-based page index to resume from (`user_last_page_read`).
    public var lastPageRead: Int
    public var dateFetch: Date

    public init(
        id: Int64 = 0,
        mangaID: Int64 = 0,
        url: String,
        name: String,
        scanlator: String? = nil,
        chapterNumber: Double = -1,
        dateUpload: Date? = nil,
        sourceOrder: Int64 = 0,
        read: Bool = false,
        bookmark: Bool = false,
        lastPageRead: Int = 0,
        dateFetch: Date = Date()
    ) {
        self.id = id
        self.mangaID = mangaID
        self.url = url
        self.name = name
        self.scanlator = scanlator
        self.chapterNumber = chapterNumber
        self.dateUpload = dateUpload
        self.sourceOrder = sourceOrder
        self.read = read
        self.bookmark = bookmark
        self.lastPageRead = lastPageRead
        self.dateFetch = dateFetch
    }

    public var isRead: Bool { read }
}

// MARK: - Category

public struct Category: Identifiable, Codable, Sendable, Equatable {
    public var id: Int64
    public var name: String
    public var order: Int64
    /// Opaque flags preserved for backup parity (`BackupCategory.flags`).
    public var flags: Int64

    public init(id: Int64 = 0, name: String, order: Int64 = 0, flags: Int64 = 0) {
        self.id = id
        self.name = name
        self.order = order
        self.flags = flags
    }
}

// MARK: - History

/// Reading history entry. Mirrors `history` table + `BackupHistory`.
public struct History: Identifiable, Codable, Sendable, Equatable {
    public var id: Int64
    public var mangaID: Int64
    public var chapterID: Int64
    public var readAt: Date
    public var readDuration: Int64

    public init(id: Int64 = 0, mangaID: Int64, chapterID: Int64, readAt: Date = Date(), readDuration: Int64 = 0) {
        self.id = id
        self.mangaID = mangaID
        self.chapterID = chapterID
        self.readAt = readAt
        self.readDuration = readDuration
    }
}

// MARK: - Reading modes

/// Reader modes with the same flag values as
/// `eu.kanade.tachiyomi.ui.reader.setting.ReadingMode` so `viewer_flags`
/// round-trip between Android and iOS backups.
public enum ReadingMode: Int, Codable, Sendable, CaseIterable {
    case `default` = 0
    case leftToRight = 1
    case rightToLeft = 2
    case vertical = 3
    case webtoon = 4
    case continuousVertical = 5

    public static let mask: Int64 = 0x00000007

    public var isPaged: Bool {
        switch self {
        case .leftToRight, .rightToLeft, .vertical: return true
        case .default, .webtoon, .continuousVertical: return false
        }
    }

    public var isHorizontal: Bool {
        switch self {
        case .leftToRight, .rightToLeft: return true
        default: return false
        }
    }
}

// MARK: - Library display / sorting

public enum LibraryDisplayMode: Int, Codable, Sendable, CaseIterable {
    case compactGrid = 0
    case comfortableGrid = 1
    case list = 2
}

public enum LibrarySortMode: String, Codable, Sendable, CaseIterable {
    case alphabetical
    case lastRead
    case lastUpdated
    case dateAdded
    case unread
    case totalChapters
    case chapterNumber
}
