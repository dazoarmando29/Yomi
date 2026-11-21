import Foundation

// MARK: - Backup model
//
// Field numbers mirror the Android models exactly
// (app/src/main/java/eu/kanade/tachiyomi/data/backup/models/):
// Backup.kt, BackupManga.kt, BackupChapter.kt, BackupCategory.kt,
// BackupHistory.kt, BackupSource.kt. Unknown fields are skipped on decode so
// newer/older backups stay importable.

public struct YomiBackupManga: Sendable, Equatable {
    public var source: Int64
    public var url: String
    public var title: String
    public var artist: String?
    public var author: String?
    public var mangaDescription: String?
    public var genres: [String]
    public var status: Int
    public var thumbnailURL: String?
    public var dateAdded: Int64
    public var viewer: Int
    public var chapters: [YomiBackupChapter]
    public var categories: [Int64]
    public var tracking: [YomiBackupTracking]
    public var favorite: Bool
    public var chapterFlags: Int
    public var viewerFlags: Int?
    public var history: [YomiBackupHistory]
    public var updateStrategy: Int
    public var favoriteModifiedAt: Int64?
    public var excludedScanlators: [String]
    public var notes: String
    public var initialized: Bool

    public init(
        source: Int64,
        url: String,
        title: String = "",
        artist: String? = nil,
        author: String? = nil,
        mangaDescription: String? = nil,
        genres: [String] = [],
        status: Int = 0,
        thumbnailURL: String? = nil,
        dateAdded: Int64 = 0,
        viewer: Int = 0,
        chapters: [YomiBackupChapter] = [],
        categories: [Int64] = [],
        tracking: [YomiBackupTracking] = [],
        favorite: Bool = true,
        chapterFlags: Int = 0,
        viewerFlags: Int? = nil,
        history: [YomiBackupHistory] = [],
        updateStrategy: Int = 0,
        favoriteModifiedAt: Int64? = nil,
        excludedScanlators: [String] = [],
        notes: String = "",
        initialized: Bool = false
    ) {
        self.source = source
        self.url = url
        self.title = title
        self.artist = artist
        self.author = author
        self.mangaDescription = mangaDescription
        self.genres = genres
        self.status = status
        self.thumbnailURL = thumbnailURL
        self.dateAdded = dateAdded
        self.viewer = viewer
        self.chapters = chapters
        self.categories = categories
        self.tracking = tracking
        self.favorite = favorite
        self.chapterFlags = chapterFlags
        self.viewerFlags = viewerFlags
        self.history = history
        self.updateStrategy = updateStrategy
        self.favoriteModifiedAt = favoriteModifiedAt
        self.excludedScanlators = excludedScanlators
        self.notes = notes
        self.initialized = initialized
    }
}

public struct YomiBackupChapter: Sendable, Equatable {
    public var url: String
    public var name: String
    public var scanlator: String?
    public var read: Bool
    public var bookmark: Bool
    public var lastPageRead: Int64
    public var dateFetch: Int64
    public var dateUpload: Int64
    public var chapterNumber: Float
    public var sourceOrder: Int64

    public init(
        url: String,
        name: String,
        scanlator: String? = nil,
        read: Bool = false,
        bookmark: Bool = false,
        lastPageRead: Int64 = 0,
        dateFetch: Int64 = 0,
        dateUpload: Int64 = 0,
        chapterNumber: Float = 0,
        sourceOrder: Int64 = 0
    ) {
        self.url = url
        self.name = name
        self.scanlator = scanlator
        self.read = read
        self.bookmark = bookmark
        self.lastPageRead = lastPageRead
        self.dateFetch = dateFetch
        self.dateUpload = dateUpload
        self.chapterNumber = chapterNumber
        self.sourceOrder = sourceOrder
    }
}

public struct YomiBackupCategory: Sendable, Equatable {
    public var name: String
    public var order: Int64
    public var id: Int64
    public var flags: Int64

    public init(name: String, order: Int64 = 0, id: Int64 = 0, flags: Int64 = 0) {
        self.name = name
        self.order = order
        self.id = id
        self.flags = flags
    }
}

public struct YomiBackupHistory: Sendable, Equatable {
    public var url: String
    public var lastRead: Int64
    public var readDuration: Int64

    public init(url: String, lastRead: Int64, readDuration: Int64 = 0) {
        self.url = url
        self.lastRead = lastRead
        self.readDuration = readDuration
    }
}

public struct YomiBackupSource: Sendable, Equatable {
    public var name: String
    public var sourceId: Int64

    public init(name: String, sourceId: Int64) {
        self.name = name
        self.sourceId = sourceId
    }
}

/// Tracking entries are preserved as opaque data (sync requires tracker
/// activation — see IOS_COMPATIBILITY.md).
public struct YomiBackupTracking: Sendable, Equatable {
    public var syncId: Int
    public var libraryId: Int64
    public var mediaIdInt: Int
    public var trackingURL: String
    public var title: String
    public var lastChapterRead: Float
    public var totalChapters: Int
    public var score: Float
    public var status: Int
    public var startedReadingDate: Int64
    public var finishedReadingDate: Int64
    public var isPrivate: Bool
    public var mediaId: Int64

    public init(
        syncId: Int = 0,
        libraryId: Int64 = 0,
        mediaIdInt: Int = 0,
        trackingURL: String = "",
        title: String = "",
        lastChapterRead: Float = 0,
        totalChapters: Int = 0,
        score: Float = 0,
        status: Int = 0,
        startedReadingDate: Int64 = 0,
        finishedReadingDate: Int64 = 0,
        isPrivate: Bool = false,
        mediaId: Int64 = 0
    ) {
        self.syncId = syncId
        self.libraryId = libraryId
        self.mediaIdInt = mediaIdInt
        self.trackingURL = trackingURL
        self.title = title
        self.lastChapterRead = lastChapterRead
        self.totalChapters = totalChapters
        self.score = score
        self.status = status
        self.startedReadingDate = startedReadingDate
        self.finishedReadingDate = finishedReadingDate
        self.isPrivate = isPrivate
        self.mediaId = mediaId
    }
}

public struct YomiBackup: Sendable, Equatable {
    public var mangas: [YomiBackupManga]
    public var categories: [YomiBackupCategory]
    public var sources: [YomiBackupSource]

    /// Sections that are parsed for reporting but not applied on iOS.
    public var skippedPreferenceCount: Int
    public var skippedSourcePreferenceCount: Int
    public var skippedExtensionStoreCount: Int

    public init(
        mangas: [YomiBackupManga] = [],
        categories: [YomiBackupCategory] = [],
        sources: [YomiBackupSource] = [],
        skippedPreferenceCount: Int = 0,
        skippedSourcePreferenceCount: Int = 0,
        skippedExtensionStoreCount: Int = 0
    ) {
        self.mangas = mangas
        self.categories = categories
        self.sources = sources
        self.skippedPreferenceCount = skippedPreferenceCount
        self.skippedSourcePreferenceCount = skippedSourcePreferenceCount
        self.skippedExtensionStoreCount = skippedExtensionStoreCount
    }
}
