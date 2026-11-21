import Foundation

// MARK: - Exporter (library -> Yomi/Mihon-compatible proto.gz)

public enum BackupExporter {
    /// Builds a backup model from the current library state.
    public static func makeBackup(
        from snapshot: LibrarySnapshot,
        sourceNames: [Int64: String] = [:]
    ) -> YomiBackup {
        var backup = YomiBackup()

        var sources: [Int64: String] = [:]
        for manga in snapshot.mangas {
            sources[manga.sourceID.rawValue] = sourceNames[manga.sourceID.rawValue] ?? ""
        }
        backup.sources = sources
            .sorted { $0.key < $1.key }
            .map { YomiBackupSource(name: $0.value, sourceId: $0.key) }

        backup.categories = snapshot.categories.map {
            YomiBackupCategory(name: $0.name, order: $0.order, id: $0.id, flags: $0.flags)
        }

        let chaptersByManga = Dictionary(grouping: snapshot.chapters, by: \.mangaID)
        let historyByManga = Dictionary(grouping: snapshot.history, by: \.mangaID)
        let chapterURLByID: [Int64: String] = Dictionary(
            snapshot.chapters.map { ($0.id, $0.url) },
            uniquingKeysWith: { first, _ in first }
        )

        backup.mangas = snapshot.mangas.map { manga in
            makeBackupManga(
                manga: manga,
                chapters: chaptersByManga[manga.id] ?? [],
                history: historyByManga[manga.id] ?? [],
                chapterURLByID: chapterURLByID,
                categoryIDs: snapshot.mangaCategoryLinks
                    .filter { $0.mangaID == manga.id }
                    .map(\.categoryID)
            )
        }
        return backup
    }

    private static func makeBackupManga(
        manga: Manga,
        chapters: [Chapter],
        history: [History],
        chapterURLByID: [Int64: String],
        categoryIDs: [Int64]
    ) -> YomiBackupManga {
        var entry = YomiBackupManga(
            source: manga.sourceID.rawValue,
            url: manga.url,
            title: manga.title
        )
        entry.artist = manga.artist
        entry.author = manga.author
        entry.mangaDescription = manga.mangaDescription
        entry.genres = manga.genres
        entry.status = manga.status.rawValue
        entry.thumbnailURL = manga.thumbnailURL
        entry.dateAdded = Int64((manga.dateAdded ?? manga.favoriteAt ?? Date()).timeIntervalSince1970 * 1000)
        entry.viewer = 0
        entry.chapters = chapters.map { chapter in
            let uploadMillis = Int64((chapter.dateUpload ?? Date(timeIntervalSince1970: 0)).timeIntervalSince1970 * 1000)
            var backupChapter = YomiBackupChapter(url: chapter.url, name: chapter.name)
            backupChapter.scanlator = chapter.scanlator
            backupChapter.read = chapter.read
            backupChapter.bookmark = chapter.bookmark
            backupChapter.lastPageRead = Int64(chapter.lastPageRead)
            backupChapter.dateFetch = Int64(chapter.dateFetch.timeIntervalSince1970 * 1000)
            backupChapter.dateUpload = uploadMillis
            backupChapter.chapterNumber = Float(chapter.chapterNumber)
            backupChapter.sourceOrder = chapter.sourceOrder
            return backupChapter
        }
        entry.categories = categoryIDs
        entry.tracking = []
        entry.favorite = manga.isInLibrary
        entry.chapterFlags = Int(manga.chapterFlags)
        entry.viewerFlags = Int(manga.viewerFlags)
        entry.history = history.compactMap { item in
            guard let chapterURL = chapterURLByID[item.chapterID] else { return nil }
            return YomiBackupHistory(
                url: chapterURL,
                lastRead: Int64(item.readAt.timeIntervalSince1970 * 1000),
                readDuration: item.readDuration
            )
        }
        entry.updateStrategy = manga.updateStrategy.rawValue
        entry.favoriteModifiedAt = manga.favoriteAt.map { Int64($0.timeIntervalSince1970) }
        entry.excludedScanlators = []
        entry.notes = manga.notes
        entry.initialized = manga.initialized
        return entry
    }

    /// Encodes the backup as gzipped protobuf (the `.proto.gz` interchange format).
    public static func exportGzipped(
        from snapshot: LibrarySnapshot,
        sourceNames: [Int64: String] = [:]
    ) -> Data {
        BackupCodec.encodeGzipped(makeBackup(from: snapshot, sourceNames: sourceNames))
    }
}
