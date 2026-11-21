import Foundation

// MARK: - Import report

public struct ImportSourceReport: Sendable, Equatable {
    public var sourceId: Int64
    public var name: String
    public var mangaCount: Int
    /// Whether a provider for this source is available on iOS.
    public var isAvailable: Bool

    public init(sourceId: Int64, name: String, mangaCount: Int, isAvailable: Bool) {
        self.sourceId = sourceId
        self.name = name
        self.mangaCount = mangaCount
        self.isAvailable = isAvailable
    }
}

/// Preview of a backup import. Missing/incompatible sources are reported —
/// never silently dropped — and nothing is written until the user confirms.
public struct ImportReport: Sendable, Equatable {
    public var mangaCount: Int
    public var chapterCount: Int
    public var categoryCount: Int
    public var historyCount: Int
    public var trackingCount: Int
    public var duplicateMangaCount: Int
    public var sources: [ImportSourceReport]
    /// Sections parsed but not applied on iOS (reported, never silently lost).
    public var skippedSections: [String]

    public var missingSources: [ImportSourceReport] {
        sources.filter { !$0.isAvailable }
    }

    public init(
        mangaCount: Int = 0,
        chapterCount: Int = 0,
        categoryCount: Int = 0,
        historyCount: Int = 0,
        trackingCount: Int = 0,
        duplicateMangaCount: Int = 0,
        sources: [ImportSourceReport] = [],
        skippedSections: [String] = []
    ) {
        self.mangaCount = mangaCount
        self.chapterCount = chapterCount
        self.categoryCount = categoryCount
        self.historyCount = historyCount
        self.trackingCount = trackingCount
        self.duplicateMangaCount = duplicateMangaCount
        self.sources = sources
        self.skippedSections = skippedSections
    }
}

// MARK: - Importer

public enum BackupImporter {
    /// Parses backup bytes (gzip or raw protobuf).
    public static func parse(_ data: Data) throws -> YomiBackup {
        try BackupCodec.decode(data)
    }

    /// Builds the preview report. Pure inspection — no data is modified.
    public static func inspect(
        _ backup: YomiBackup,
        knownSources: [SourceID],
        existingMangaKeys: Set<String> = []
    ) -> ImportReport {
        var report = ImportReport()
        report.mangaCount = backup.mangas.count
        report.chapterCount = backup.mangas.reduce(0) { $0 + $1.chapters.count }
        report.categoryCount = backup.categories.count
        report.historyCount = backup.mangas.reduce(0) { $0 + $1.history.count }
        report.trackingCount = backup.mangas.reduce(0) { $0 + $1.tracking.count }

        var perSource: [Int64: (name: String, count: Int)] = [:]
        for manga in backup.mangas {
            let key = "\(manga.source)|\(manga.url)"
            if existingMangaKeys.contains(key) {
                report.duplicateMangaCount += 1
            }
            var entry = perSource[manga.source] ?? (name: "", count: 0)
            entry.count += 1
            perSource[manga.source] = entry
        }

        // Names come from the backup's source table when present.
        var namesByID: [Int64: String] = [:]
        for source in backup.sources {
            namesByID[source.sourceId] = source.name
        }

        let known = Set(knownSources.map(\.rawValue))
        report.sources = perSource.map { sourceId, entry in
            ImportSourceReport(
                sourceId: sourceId,
                name: namesByID[sourceId] ?? "Unknown source (\(sourceId))",
                mangaCount: entry.count,
                isAvailable: known.contains(sourceId)
            )
        }
        .sorted { $0.sourceId < $1.sourceId }

        var skipped: [String] = []
        if backup.skippedPreferenceCount > 0 {
            skipped.append("\(backup.skippedPreferenceCount) app preference entries (Android-only keys)")
        }
        if backup.skippedSourcePreferenceCount > 0 {
            skipped.append("\(backup.skippedSourcePreferenceCount) source preference entries (Android-only)")
        }
        if backup.skippedExtensionStoreCount > 0 {
            skipped.append("\(backup.skippedExtensionStoreCount) extension store entries (APK stores unsupported on iOS)")
        }
        report.skippedSections = skipped
        return report
    }

    /// Converts a backup into an additive import plan. History is linked via
    /// `(source, mangaURL, chapterURL)` because backup-local ids are meaningless
    /// after import.
    public static func plan(_ backup: YomiBackup) -> LibraryImportPlan {
        // `Category.id` carries the backup-side category id so `apply` can map
        // manga category references; created categories get fresh store ids.
        let categories = backup.categories.map {
            Category(id: $0.id, name: $0.name, order: $0.order, flags: $0.flags)
        }

        var plannedManga: [PlannedManga] = []
        var plannedHistory: [PlannedHistory] = []
        let sourceNames: [Int64: String] = Dictionary(
            backup.sources.map { ($0.sourceId, $0.name) },
            uniquingKeysWith: { first, _ in first }
        )

        for backupManga in backup.mangas {
            let sourceID = SourceID(rawValue: backupManga.source)
            let favoriteAt: Date? = backupManga.favorite
                ? Date(timeIntervalSince1970: Double(backupManga.dateAdded) / 1000)
                : nil
            let manga = Manga(
                sourceID: sourceID,
                url: backupManga.url,
                title: backupManga.title.isEmpty ? "Untitled" : backupManga.title,
                author: backupManga.author,
                artist: backupManga.artist,
                mangaDescription: backupManga.mangaDescription,
                genres: backupManga.genres,
                status: MangaStatus(rawValue: backupManga.status) ?? .unknown,
                thumbnailURL: backupManga.thumbnailURL,
                favoriteAt: favoriteAt,
                dateAdded: backupManga.dateAdded > 0
                    ? Date(timeIntervalSince1970: Double(backupManga.dateAdded) / 1000)
                    : nil,
                notes: backupManga.notes,
                viewerFlags: Int64(backupManga.viewerFlags ?? backupManga.viewer),
                chapterFlags: Int64(backupManga.chapterFlags),
                updateStrategy: UpdateStrategy(rawValue: backupManga.updateStrategy) ?? .alwaysUpdate,
                initialized: backupManga.initialized
            )

            let chapters = backupManga.chapters.map {
                PlannedChapter(
                    url: $0.url,
                    name: $0.name,
                    scanlator: $0.scanlator,
                    chapterNumber: Double($0.chapterNumber),
                    dateUpload: $0.dateUpload > 0
                        ? Date(timeIntervalSince1970: Double($0.dateUpload) / 1000)
                        : nil,
                    sourceOrder: $0.sourceOrder,
                    read: $0.read,
                    bookmark: $0.bookmark,
                    lastPageRead: Int(max(0, min(Int64(Int32.max), $0.lastPageRead)))
                )
            }

            plannedManga.append(
                PlannedManga(manga: manga, chapters: chapters, categoryRefs: backupManga.categories)
            )

            for history in backupManga.history {
                plannedHistory.append(
                    PlannedHistory(
                        sourceID: sourceID,
                        mangaURL: backupManga.url,
                        chapterURL: history.url,
                        readAt: Date(timeIntervalSince1970: Double(history.lastRead) / 1000),
                        readDuration: history.readDuration
                    )
                )
            }
            _ = sourceNames // names are surfaced via `inspect`
        }

        return LibraryImportPlan(mangas: plannedManga, categories: categories, history: plannedHistory)
    }

    /// Parses, inspects and prepares everything except the final apply step.
    public static func prepare(
        data: Data,
        knownSources: [SourceID],
        store: LibraryStore
    ) async throws -> (backup: YomiBackup, report: ImportReport, plan: LibraryImportPlan) {
        let backup = try parse(data)
        let existing = Set((await store.allMangas()).map { "\($0.sourceID.rawValue)|\($0.url)" })
        let report = inspect(backup, knownSources: knownSources, existingMangaKeys: existing)
        return (backup, report, plan(backup))
    }
}
