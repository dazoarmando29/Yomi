import Foundation

// MARK: - Snapshot

/// Codable snapshot of the whole library. Persisted atomically as JSON
/// (`library.json`) in the store directory. Field semantics mirror the
/// SQLDelight tables on Android (manga / chapter / category / manga_category /
/// history) so a backup round-trip keeps parity.
public struct LibrarySnapshot: Codable, Sendable {
    public var mangas: [Manga]
    public var chapters: [Chapter]
    public var categories: [Category]
    public var mangaCategoryLinks: [MangaCategoryLink]
    public var history: [History]
    public var nextMangaID: Int64
    public var nextChapterID: Int64
    public var nextCategoryID: Int64
    public var nextHistoryID: Int64

    public init(
        mangas: [Manga] = [],
        chapters: [Chapter] = [],
        categories: [Category] = [],
        mangaCategoryLinks: [MangaCategoryLink] = [],
        history: [History] = [],
        nextMangaID: Int64 = 1,
        nextChapterID: Int64 = 1,
        nextCategoryID: Int64 = 1,
        nextHistoryID: Int64 = 1
    ) {
        self.mangas = mangas
        self.chapters = chapters
        self.categories = categories
        self.mangaCategoryLinks = mangaCategoryLinks
        self.history = history
        self.nextMangaID = nextMangaID
        self.nextChapterID = nextChapterID
        self.nextCategoryID = nextCategoryID
        self.nextHistoryID = nextHistoryID
    }
}

public struct MangaCategoryLink: Codable, Sendable, Equatable {
    public var mangaID: Int64
    public var categoryID: Int64

    public init(mangaID: Int64, categoryID: Int64) {
        self.mangaID = mangaID
        self.categoryID = categoryID
    }
}

// MARK: - Library store

/// Actor owning all library data. Mutations persist automatically
/// (atomic JSON write) so data survives app restarts.
public actor LibraryStore {
    public static let snapshotFileName = "library.json"

    public let directory: URL
    private var state: LibrarySnapshot

    // In-memory indices
    private var mangaByID: [Int64: Manga] = [:]
    private var mangaByRemoteKey: [String: Int64] = [:] // "\(sourceID)|\(url)"
    private var chaptersByManga: [Int64: [Chapter]] = [:]
    private var chapterByID: [Int64: Chapter] = [:]

    private var autosave: Bool

    /// - Parameters:
    ///   - directory: folder for `library.json` (created if missing).
    ///   - autosave: persist after each mutation (disable in tests that build bulk state).
    public init(directory: URL, autosave: Bool = true) throws {
        self.directory = directory
        self.autosave = autosave
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

        let snapshotURL = directory.appendingPathComponent(Self.snapshotFileName)
        if FileManager.default.fileExists(atPath: snapshotURL.path) {
            let data = try Data(contentsOf: snapshotURL)
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .iso8601
            self.state = try decoder.decode(LibrarySnapshot.self, from: data)
        } else {
            self.state = LibrarySnapshot()
        }
        let indices = Self.buildIndices(from: state)
        self.mangaByID = indices.mangaByID
        self.mangaByRemoteKey = indices.mangaByRemoteKey
        self.chaptersByManga = indices.chaptersByManga
        self.chapterByID = indices.chapterByID
    }

    /// Store that never touches disk (unit tests).
    public static func inMemory() throws -> LibraryStore {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("yomi-inmemory-\(UUID().uuidString)", isDirectory: true)
        let store = try LibraryStore(directory: dir, autosave: false)
        return store
    }

    private struct Indices {
        var mangaByID: [Int64: Manga] = [:]
        var mangaByRemoteKey: [String: Int64] = [:]
        var chaptersByManga: [Int64: [Chapter]] = [:]
        var chapterByID: [Int64: Chapter] = [:]
    }

    private static func buildIndices(from state: LibrarySnapshot) -> Indices {
        var indices = Indices()
        for manga in state.mangas {
            indices.mangaByID[manga.id] = manga
            indices.mangaByRemoteKey[remoteKey(sourceID: manga.sourceID, url: manga.url)] = manga.id
        }
        for chapter in state.chapters {
            indices.chapterByID[chapter.id] = chapter
            indices.chaptersByManga[chapter.mangaID, default: []].append(chapter)
        }
        for key in indices.chaptersByManga.keys {
            indices.chaptersByManga[key]?.sort { $0.sourceOrder < $1.sourceOrder }
        }
        return indices
    }

    private func rebuildIndices() {
        let indices = Self.buildIndices(from: state)
        mangaByID = indices.mangaByID
        mangaByRemoteKey = indices.mangaByRemoteKey
        chaptersByManga = indices.chaptersByManga
        chapterByID = indices.chapterByID
    }

    private static func remoteKey(sourceID: SourceID, url: String) -> String {
        "\(sourceID.rawValue)|\(url)"
    }

    private func persistIfNeeded() throws {
        guard autosave else { return }
        try persist()
    }

    /// Writes the snapshot to disk atomically.
    public func persist() throws {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(state)
        let snapshotURL = directory.appendingPathComponent(Self.snapshotFileName)
        try data.write(to: snapshotURL, options: [.atomic])
    }

    // MARK: - Manga

    /// Inserts or updates a manga by `(sourceID, url)` identity. Returns the stored record.
    @discardableResult
    public func upsertManga(_ manga: Manga) throws -> Manga {
        let key = Self.remoteKey(sourceID: manga.sourceID, url: manga.url)
        var stored = manga
        if let existingID = mangaByRemoteKey[key] {
            stored.id = existingID
            // Keep library-managed fields
            let existing = mangaByID[existingID]!
            stored.favoriteAt = existing.favoriteAt ?? manga.favoriteAt
            stored.dateAdded = existing.dateAdded ?? manga.dateAdded
            stored.viewerFlags = manga.viewerFlags != 0 ? manga.viewerFlags : existing.viewerFlags
            stored.chapterFlags = manga.chapterFlags != 0 ? manga.chapterFlags : existing.chapterFlags
            if stored.notes.isEmpty { stored.notes = existing.notes }
        } else {
            stored.id = state.nextMangaID
            state.nextMangaID += 1
        }
        if let index = state.mangas.firstIndex(where: { $0.id == stored.id }) {
            state.mangas[index] = stored
        } else {
            state.mangas.append(stored)
        }
        mangaByID[stored.id] = stored
        mangaByRemoteKey[key] = stored.id
        try persistIfNeeded()
        return stored
    }

    public func manga(id: Int64) -> Manga? {
        mangaByID[id]
    }

    public func manga(sourceID: SourceID, url: String) -> Manga? {
        mangaByRemoteKey[Self.remoteKey(sourceID: sourceID, url: url)].flatMap { mangaByID[$0] }
    }

    /// Marks a manga as favorite/in-library (or removes the flag without deleting data).
    /// Writes through directly: unlike `upsertManga`, this must be able to clear
    /// `favoriteAt` even when a stored record exists.
    public func setFavorite(id: Int64, favorite: Bool) throws {
        guard var manga = mangaByID[id] else { return }
        if favorite {
            manga.favoriteAt = manga.favoriteAt ?? Date()
            manga.dateAdded = manga.dateAdded ?? Date()
        } else {
            manga.favoriteAt = nil
        }
        if let index = state.mangas.firstIndex(where: { $0.id == id }) {
            state.mangas[index] = manga
        }
        mangaByID[id] = manga
        try persistIfNeeded()
    }

    /// Fully removes a manga and its chapters/history/category links.
    public func deleteManga(id: Int64) throws {
        guard let manga = mangaByID[id] else { return }
        state.mangas.removeAll { $0.id == id }
        mangaByID[id] = nil
        mangaByRemoteKey[Self.remoteKey(sourceID: manga.sourceID, url: manga.url)] = nil
        state.chapters.removeAll { $0.mangaID == id }
        chaptersByManga[id] = nil
        state.history.removeAll { $0.mangaID == id }
        state.mangaCategoryLinks.removeAll { $0.mangaID == id }
        try persistIfNeeded()
    }

    // MARK: - Library queries

    /// Favorites, optionally filtered by category (`nil` = all).
    public func libraryMangas(categoryID: Int64? = nil) -> [Manga] {
        let favorites = state.mangas.filter(\.isInLibrary)
        guard let categoryID else { return favorites }
        let ids = Set(state.mangaCategoryLinks.filter { $0.categoryID == categoryID }.map(\.mangaID))
        return favorites.filter { ids.contains($0.id) }
    }

    public func allMangas() -> [Manga] {
        state.mangas
    }

    // MARK: - Chapters

    /// Replaces the chapter list of a manga from a source refresh, preserving
    /// user state (read/bookmark/progress) for chapters matched by URL.
    public func replaceChapters(mangaID: Int64, remoteChapters: [SourceChapter]) throws -> [Chapter] {
        let existingByURL = Dictionary(
            (chaptersByManga[mangaID] ?? []).map { ($0.url, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        var nextID = state.nextChapterID
        let merged: [Chapter] = remoteChapters.map { remote in
            var chapter = Chapter(
                id: 0,
                mangaID: mangaID,
                url: remote.url,
                name: remote.name,
                scanlator: remote.scanlator,
                chapterNumber: remote.chapterNumber,
                dateUpload: remote.dateUpload,
                sourceOrder: remote.sourceOrder
            )
            if let existing = existingByURL[remote.url] {
                chapter.id = existing.id
                chapter.read = existing.read
                chapter.bookmark = existing.bookmark
                chapter.lastPageRead = existing.lastPageRead
                chapter.dateFetch = Date()
            } else {
                chapter.id = nextID
                nextID += 1
            }
            return chapter
        }
        state.nextChapterID = nextID
        state.chapters.removeAll { $0.mangaID == mangaID }
        state.chapters.append(contentsOf: merged)
        rebuildIndices()
        try persistIfNeeded()
        return merged.sorted { $0.sourceOrder < $1.sourceOrder }
    }

    public func chapters(mangaID: Int64) -> [Chapter] {
        (chaptersByManga[mangaID] ?? []).sorted { $0.sourceOrder < $1.sourceOrder }
    }

    public func chapter(id: Int64) -> Chapter? {
        chapterByID[id]
    }

    public func updateChapter(_ chapter: Chapter) throws {
        guard chapterByID[chapter.id] != nil else { return }
        if let index = state.chapters.firstIndex(where: { $0.id == chapter.id }) {
            state.chapters[index] = chapter
        }
        chapterByID[chapter.id] = chapter
        chaptersByManga[chapter.mangaID] = state.chapters.filter { $0.mangaID == chapter.mangaID }
        try persistIfNeeded()
    }

    // MARK: - Reading progress

    /// Saves the reading position (0-based page) and optionally marks read state.
    public func updateProgress(chapterID: Int64, page: Int, pageCount: Int) throws {
        guard var chapter = chapterByID[chapterID] else { return }
        chapter.lastPageRead = max(0, page)
        if pageCount > 0, page >= pageCount - 1 {
            chapter.read = true
        }
        try updateChapter(chapter)
    }

    public func setRead(chapterID: Int64, read: Bool) throws {
        guard var chapter = chapterByID[chapterID] else { return }
        chapter.read = read
        if read { chapter.lastPageRead = 0 }
        try updateChapter(chapter)
    }

    public func setBookmark(chapterID: Int64, bookmark: Bool) throws {
        guard var chapter = chapterByID[chapterID] else { return }
        chapter.bookmark = bookmark
        try updateChapter(chapter)
    }

    /// Marks all chapters up to (and including) `chapterID` in source order as read.
    public func markPreviousRead(mangaID: Int64, chapterID: Int64) throws {
        let ordered = chapters(mangaID: mangaID)
        for chapter in ordered {
            try setRead(chapterID: chapter.id, read: true)
            if chapter.id == chapterID { break }
        }
    }

    // MARK: - History

    public func recordHistory(mangaID: Int64, chapterID: Int64, at date: Date = Date(), duration: Int64 = 0) throws {
        if let index = state.history.firstIndex(where: { $0.chapterID == chapterID }) {
            var entry = state.history[index]
            entry.readAt = date
            entry.readDuration += duration
            state.history[index] = entry
        } else {
            state.history.append(
                History(
                    id: state.nextHistoryID,
                    mangaID: mangaID,
                    chapterID: chapterID,
                    readAt: date,
                    readDuration: duration
                )
            )
            state.nextHistoryID += 1
        }
        try persistIfNeeded()
    }

    public func history() -> [History] {
        state.history.sorted { $0.readAt > $1.readAt }
    }

    public func removeHistory(chapterID: Int64) throws {
        state.history.removeAll { $0.chapterID == chapterID }
        try persistIfNeeded()
    }

    // MARK: - Categories

    @discardableResult
    public func createCategory(name: String) throws -> Category {
        let category = Category(id: state.nextCategoryID, name: name, order: Int64(state.categories.count))
        state.nextCategoryID += 1
        state.categories.append(category)
        try persistIfNeeded()
        return category
    }

    public func renameCategory(id: Int64, name: String) throws {
        guard let index = state.categories.firstIndex(where: { $0.id == id }) else { return }
        state.categories[index].name = name
        try persistIfNeeded()
    }

    public func deleteCategory(id: Int64) throws {
        state.categories.removeAll { $0.id == id }
        state.mangaCategoryLinks.removeAll { $0.categoryID == id }
        try persistIfNeeded()
    }

    public func categories() -> [Category] {
        state.categories.sorted { $0.order < $1.order }
    }

    public func setCategories(mangaID: Int64, categoryIDs: [Int64]) throws {
        state.mangaCategoryLinks.removeAll { $0.mangaID == mangaID }
        for categoryID in categoryIDs {
            state.mangaCategoryLinks.append(MangaCategoryLink(mangaID: mangaID, categoryID: categoryID))
        }
        try persistIfNeeded()
    }

    public func categoryIDs(mangaID: Int64) -> [Int64] {
        state.mangaCategoryLinks.filter { $0.mangaID == mangaID }.map(\.categoryID)
    }

    // MARK: - Misc

    public func unreadCount(mangaID: Int64) -> Int {
        chapters(mangaID: mangaID).filter { !$0.read }.count
    }

    public func totalReadCount(mangaID: Int64) -> Int {
        chapters(mangaID: mangaID).filter(\.read).count
    }

    /// Raw snapshot access for backup export.
    public func snapshot() -> LibrarySnapshot {
        state
    }

    // MARK: - Backup import support

    /// Applies an import plan. Existing data is never deleted: manga are merged
    /// by `(sourceID, url)`, chapters by URL, history by chapter.
    public func apply(_ plan: LibraryImportPlan) throws -> ImportApplyResult {
        var addedManga = 0
        var mergedManga = 0
        var addedChapters = 0
        var addedCategories = 0

        var categoryByBackupID: [Int64: Int64] = [:]
        for backupCategory in plan.categories {
            if let existing = state.categories.first(where: { $0.name == backupCategory.name }) {
                categoryByBackupID[backupCategory.id] = existing.id
            } else {
                let created = try createCategory(name: backupCategory.name)
                categoryByBackupID[backupCategory.id] = created.id
                addedCategories += 1
            }
        }

        for entry in plan.mangas {
            let existing = manga(sourceID: entry.manga.sourceID, url: entry.manga.url)
            if existing == nil { addedManga += 1 } else { mergedManga += 1 }
            let stored = try upsertManga(entry.manga)

            let remoteChapters = entry.chapters.map {
                SourceChapter(
                    url: $0.url,
                    name: $0.name,
                    scanlator: $0.scanlator,
                    dateUpload: $0.dateUpload,
                    sourceOrder: $0.sourceOrder,
                    chapterNumber: $0.chapterNumber
                )
            }
            let before = Set(chapters(mangaID: stored.id).map(\.url))
            let merged = try replaceChapters(mangaID: stored.id, remoteChapters: remoteChapters)
            addedChapters += merged.filter { !before.contains($0.url) }.count

            // Restore per-chapter user state from the backup
            for backupChapter in entry.chapters {
                if let chapter = merged.first(where: { $0.url == backupChapter.url }) {
                    var updated = chapter
                    updated.read = backupChapter.read
                    updated.bookmark = backupChapter.bookmark
                    updated.lastPageRead = backupChapter.lastPageRead
                    try updateChapter(updated)
                }
            }

            var categoryIDs = entry.categoryRefs.compactMap { categoryByBackupID[$0] }
            categoryIDs.append(contentsOf: self.categoryIDs(mangaID: stored.id))
            try setCategories(mangaID: stored.id, categoryIDs: Array(Set(categoryIDs)))
        }

        for history in plan.history {
            // Resolve backup-local references to the ids assigned during this import.
            guard let storedManga = self.manga(sourceID: history.sourceID, url: history.mangaURL),
                  let chapter = chapters(mangaID: storedManga.id).first(where: { $0.url == history.chapterURL })
            else { continue }
            try recordHistory(
                mangaID: storedManga.id,
                chapterID: chapter.id,
                at: history.readAt,
                duration: history.readDuration
            )
        }

        return ImportApplyResult(
            addedManga: addedManga,
            mergedManga: mergedManga,
            addedChapters: addedChapters,
            addedCategories: addedCategories
        )
    }
}

// MARK: - Import plan (see BackupImporter)

public struct PlannedManga: Sendable {
    public var manga: Manga
    public var chapters: [PlannedChapter]
    /// Backup category ids this manga belongs to (matched against `Category.id`
    /// of the plan's categories).
    public var categoryRefs: [Int64]

    public init(manga: Manga, chapters: [PlannedChapter], categoryRefs: [Int64]) {
        self.manga = manga
        self.chapters = chapters
        self.categoryRefs = categoryRefs
    }
}

public struct PlannedChapter: Sendable {
    public var url: String
    public var name: String
    public var scanlator: String?
    public var chapterNumber: Double
    public var dateUpload: Date?
    public var sourceOrder: Int64
    public var read: Bool
    public var bookmark: Bool
    public var lastPageRead: Int

    public init(
        url: String,
        name: String,
        scanlator: String? = nil,
        chapterNumber: Double = -1,
        dateUpload: Date? = nil,
        sourceOrder: Int64 = 0,
        read: Bool = false,
        bookmark: Bool = false,
        lastPageRead: Int = 0
    ) {
        self.url = url
        self.name = name
        self.scanlator = scanlator
        self.chapterNumber = chapterNumber
        self.dateUpload = dateUpload
        self.sourceOrder = sourceOrder
        self.read = read
        self.bookmark = bookmark
        self.lastPageRead = lastPageRead
    }
}

public struct PlannedHistory: Sendable {
    /// Backup-local references, resolved to store ids during `apply` via
    /// `(sourceID, mangaURL)` + `chapterURL` matching.
    public var sourceID: SourceID
    public var mangaURL: String
    public var chapterURL: String
    public var readAt: Date
    public var readDuration: Int64

    public init(sourceID: SourceID, mangaURL: String, chapterURL: String, readAt: Date, readDuration: Int64) {
        self.sourceID = sourceID
        self.mangaURL = mangaURL
        self.chapterURL = chapterURL
        self.readAt = readAt
        self.readDuration = readDuration
    }
}

public struct LibraryImportPlan: Sendable {
    public var mangas: [PlannedManga]
    public var categories: [Category]
    public var history: [PlannedHistory]

    public init(mangas: [PlannedManga], categories: [Category], history: [PlannedHistory]) {
        self.mangas = mangas
        self.categories = categories
        self.history = history
    }
}

public struct ImportApplyResult: Sendable, Equatable {
    public var addedManga: Int
    public var mergedManga: Int
    public var addedChapters: Int
    public var addedCategories: Int

    public init(addedManga: Int, mergedManga: Int, addedChapters: Int, addedCategories: Int) {
        self.addedManga = addedManga
        self.mergedManga = mergedManga
        self.addedChapters = addedChapters
        self.addedCategories = addedCategories
    }
}
