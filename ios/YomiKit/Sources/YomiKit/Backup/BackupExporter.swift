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
            let backupChapters = (chaptersByManga[manga.id] ?? []).map { chapter in
                YomiBackupChapter(
                    url: chapter.url,
                    name: chapter.name,
                    scanlator: chapter.scanlator,
                    read: chapter.read,
                    bookmark: chapter.bookmark,
                    lastPageRead: Int64(chapter.lastPageRead),
                    dateFetch: Int64(chapter.dateFetch.timeIntervalSince1970 * 1000),
                    dateUpload: Int64((chapter.dateUpload ?? Date(timeIntervalSince1970: 0)).timeIntervalSince1970 * 1000),
                    chapterNumber: Float(chapter.chapterNumber),
                    sourceOrder: chapter.sourceOrder
                )
            }
            let backupHistory = (historyByManga[manga.id] ?? []).compactMap { history -> YomiBackupHistory? in
                guard let chapterURL = chapterURLByID[history.chapterID] else { return nil }
                return YomiBackupHistory(
                    url: chapterURL,
                    lastRead: Int64(history.readAt.timeIntervalSince1970 * 1000),
                    readDuration: history.readDuration
                )
            }
            return YomiBackupManga(
                source: manga.sourceID.rawValue,
                url: manga.url,
                title: manga.title,
                artist: manga.artist,
                author: manga.author,
                mangaDescription: manga.mangaDescription,
                genres: manga.genres,
                status: manga.status.rawValue,
                thumbnailURL: manga.thumbnailURL,
                dateAdded: Int64((manga.dateAdded ?? manga.favoriteAt ?? Date()).timeIntervalSince1970 * 1000),
                viewer: 0,
                chapters: backupChapters,
                categories: snapshot.mangaCategoryLinks
                    .filter { $0.mangaID == manga.id }
                    .map(\.categoryID),
                tracking: [],
                favorite: manga.isInLibrary,
                chapterFlags: Int(manga.chapterFlags),
                viewerFlags: Int(manga.viewerFlags),
                history: backupHistory,
                updateStrategy: manga.updateStrategy.rawValue,
                favoriteModifiedAt: manga.favoriteAt.map { Int64($0.timeIntervalSince1970) },
                excludedScanlators: [],
                notes: manga.notes,
                initialized: manga.initialized
            )
        }
        return backup
    }

    /// Encodes the backup as gzipped protobuf (the `.proto.gz` interchange format).
    public static func exportGzipped(
        from snapshot: LibrarySnapshot,
        sourceNames: [Int64: String] = [:]
    ) -> Data {
        BackupCodec.encodeGzipped(makeBackup(from: snapshot, sourceNames: sourceNames))
    }
}
