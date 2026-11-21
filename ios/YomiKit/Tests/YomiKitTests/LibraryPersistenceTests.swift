import XCTest
@testable import YomiKit

final class LibraryPersistenceTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("yomi-tests-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    func testMangaSurvivesRestart() async throws {
        do {
            let store = try LibraryStore(directory: directory)
            let manga = try await store.upsertManga(
                Manga(
                    sourceID: SourceID(rawValue: 1),
                    url: "manga/one",
                    title: "Persisted",
                    author: "Author",
                    genres: ["Action"],
                    favoriteAt: Date()
                )
            )
            let chapters = try await store.replaceChapters(
                mangaID: manga.id,
                remoteChapters: [
                    SourceChapter(url: "c1", name: "Chapter 1", sourceOrder: 0, chapterNumber: 1),
                    SourceChapter(url: "c2", name: "Chapter 2", sourceOrder: 1, chapterNumber: 2),
                ]
            )
            XCTAssertEqual(chapters.count, 2)
            try await store.updateProgress(chapterID: chapters[0].id, page: 2, pageCount: 10)
        }

        // "Restart": a fresh store over the same directory
        let reopened = try LibraryStore(directory: directory)
        let manga = await reopened.manga(sourceID: SourceID(rawValue: 1), url: "manga/one")
        XCTAssertEqual(manga?.title, "Persisted")
        XCTAssertEqual(manga?.genres, ["Action"])
        XCTAssertTrue(manga?.isInLibrary ?? false)

        let chapters = await reopened.chapters(mangaID: manga!.id)
        XCTAssertEqual(chapters.count, 2)
        XCTAssertEqual(chapters[0].lastPageRead, 2)
    }

    func testUpsertIsIdentityByKey() async throws {
        let store = try LibraryStore.inMemory()
        let first = try await store.upsertManga(Manga(sourceID: SourceID(rawValue: 1), url: "a", title: "First"))
        let second = try await store.upsertManga(Manga(sourceID: SourceID(rawValue: 1), url: "a", title: "Second"))
        XCTAssertEqual(first.id, second.id)

        let count = await store.allMangas().count
        XCTAssertEqual(count, 1)
    }

    func testFavoriteFlagAndDeletion() async throws {
        let store = try LibraryStore.inMemory()
        let manga = try await store.upsertManga(Manga(sourceID: SourceID(rawValue: 1), url: "a", title: "A"))
        try await store.setFavorite(id: manga.id, favorite: true)

        var library = await store.libraryMangas()
        XCTAssertEqual(library.count, 1)

        try await store.setFavorite(id: manga.id, favorite: false)
        library = await store.libraryMangas()
        XCTAssertTrue(library.isEmpty)
        // Data is not deleted when unfavorited
        let stillThere = await store.manga(id: manga.id)
        XCTAssertNotNil(stillThere)

        try await store.deleteManga(id: manga.id)
        let gone = await store.manga(id: manga.id)
        XCTAssertNil(gone)
    }

    func testChapterRefreshPreservesUserState() async throws {
        let store = try LibraryStore.inMemory()
        let manga = try await store.upsertManga(Manga(sourceID: SourceID(rawValue: 1), url: "a", title: "A"))
        let first = try await store.replaceChapters(
            mangaID: manga.id,
            remoteChapters: [SourceChapter(url: "c1", name: "Ch 1", sourceOrder: 0)]
        )
        try await store.updateProgress(chapterID: first[0].id, page: 4, pageCount: 5)
        try await store.setBookmark(chapterID: first[0].id, bookmark: true)

        // Refresh adds a new chapter and re-sends the old one
        let refreshed = try await store.replaceChapters(
            mangaID: manga.id,
            remoteChapters: [
                SourceChapter(url: "c1", name: "Ch 1", sourceOrder: 0),
                SourceChapter(url: "c2", name: "Ch 2", sourceOrder: 1),
            ]
        )
        XCTAssertEqual(refreshed.count, 2)
        XCTAssertEqual(refreshed[0].lastPageRead, 4, "Progress must survive chapter refresh")
        XCTAssertTrue(refreshed[0].bookmark)
        XCTAssertEqual(refreshed[0].id, first[0].id, "Chapter identity is stable across refresh")
    }

    func testCategoriesAndMembership() async throws {
        let store = try LibraryStore.inMemory()
        let reading = try await store.createCategory(name: "Reading")
        let backlog = try await store.createCategory(name: "Backlog")
        let manga = try await store.upsertManga(
            Manga(sourceID: SourceID(rawValue: 1), url: "a", title: "A", favoriteAt: Date())
        )
        try await store.setCategories(mangaID: manga.id, categoryIDs: [reading.id])

        let readingList = await store.libraryMangas(categoryID: reading.id)
        XCTAssertEqual(readingList.map(\.id), [manga.id])
        let backlogList = await store.libraryMangas(categoryID: backlog.id)
        XCTAssertTrue(backlogList.isEmpty)

        try await store.renameCategory(id: backlog.id, name: "Later")
        let names = await store.categories().map(\.name)
        XCTAssertEqual(names, ["Reading", "Later"])

        try await store.deleteCategory(id: reading.id)
        let remaining = await store.categories()
        XCTAssertEqual(remaining.map(\.name), ["Later"])
    }

    func testHistoryRecordsAndAggregates() async throws {
        let store = try LibraryStore.inMemory()
        let manga = try await store.upsertManga(Manga(sourceID: SourceID(rawValue: 1), url: "a", title: "A"))
        let chapters = try await store.replaceChapters(
            mangaID: manga.id,
            remoteChapters: [SourceChapter(url: "c1", name: "Ch 1", sourceOrder: 0)]
        )

        try await store.recordHistory(mangaID: manga.id, chapterID: chapters[0].id, at: Date(), duration: 1000)
        try await store.recordHistory(mangaID: manga.id, chapterID: chapters[0].id, at: Date(), duration: 500)

        let history = await store.history()
        XCTAssertEqual(history.count, 1, "Same chapter aggregates into one history entry")
        XCTAssertEqual(history[0].readDuration, 1500)

        try await store.removeHistory(chapterID: chapters[0].id)
        let empty = await store.history()
        XCTAssertTrue(empty.isEmpty)
    }

    func testAtomicSnapshotFileWritten() async throws {
        let store = try LibraryStore(directory: directory)
        _ = try await store.upsertManga(Manga(sourceID: SourceID(rawValue: 1), url: "a", title: "A"))
        let snapshotURL = directory.appendingPathComponent(LibraryStore.snapshotFileName)
        let data = try Data(contentsOf: snapshotURL)
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let decoded = try decoder.decode(LibrarySnapshot.self, from: data)
        XCTAssertEqual(decoded.mangas.count, 1)
    }

    func testMarkPreviousRead() async throws {
        let store = try LibraryStore.inMemory()
        let manga = try await store.upsertManga(Manga(sourceID: SourceID(rawValue: 1), url: "a", title: "A"))
        let chapters = try await store.replaceChapters(
            mangaID: manga.id,
            remoteChapters: (0..<5).map { SourceChapter(url: "c\($0)", name: "Ch \($0)", sourceOrder: Int64($0)) }
        )
        try await store.markPreviousRead(mangaID: manga.id, chapterID: chapters[3].id)

        let updated = await store.chapters(mangaID: manga.id)
        XCTAssertTrue(updated[0].read && updated[1].read && updated[2].read && updated[3].read)
        XCTAssertFalse(updated[4].read)
    }

    func testUnreadCounts() async throws {
        let store = try LibraryStore.inMemory()
        let manga = try await store.upsertManga(Manga(sourceID: SourceID(rawValue: 1), url: "a", title: "A"))
        let chapters = try await store.replaceChapters(
            mangaID: manga.id,
            remoteChapters: (0..<3).map { SourceChapter(url: "c\($0)", name: "Ch \($0)", sourceOrder: Int64($0)) }
        )
        try await store.setRead(chapterID: chapters[0].id, read: true)

        let unread = await store.unreadCount(mangaID: manga.id)
        XCTAssertEqual(unread, 2)
    }
}
