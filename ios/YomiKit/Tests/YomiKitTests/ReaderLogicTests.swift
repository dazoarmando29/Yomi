import XCTest
@testable import YomiKit

final class ReaderLogicTests: XCTestCase {

    // MARK: - Chapter recognition (ported Android semantics)

    func testChapterNumberRecognition() {
        XCTAssertEqual(ChapterRecognition.parseChapterNumber(from: "Mokushiroku Alice Vol.1 Ch. 4: Misrepresentation"), 4)
        XCTAssertEqual(ChapterRecognition.parseChapterNumber(from: "Bleach 567: Down With Snowwhite"), 567)
        XCTAssertEqual(ChapterRecognition.parseChapterNumber(from: "Chapter 12.5: Half Step"), 12.5)
        XCTAssertEqual(ChapterRecognition.parseChapterNumber(from: "Prologue: The Beginning"), 0)
        XCTAssertEqual(ChapterRecognition.parseChapterNumber(from: "One Shot"), 0)
        XCTAssertEqual(ChapterRecognition.parseChapterNumber(from: "Extra: Beach Episode"), -1)
    }

    // MARK: - Chapter ordering

    private func makeChapters() -> [Chapter] {
        [
            Chapter(url: "c3", name: "Chapter 3", chapterNumber: 3, sourceOrder: 2),
            Chapter(url: "c1", name: "Chapter 1", chapterNumber: 1, sourceOrder: 0),
            Chapter(url: "c2", name: "Chapter 2", chapterNumber: 2, sourceOrder: 1),
        ]
    }

    func testSortBySourceOrder() {
        let sorted = ChapterSort.sorted(makeChapters(), by: .sourceOrder, descending: false)
        XCTAssertEqual(sorted.map(\.name), ["Chapter 1", "Chapter 2", "Chapter 3"])

        let reversed = ChapterSort.sorted(makeChapters(), by: .sourceOrder, descending: true)
        XCTAssertEqual(reversed.map(\.name), ["Chapter 3", "Chapter 2", "Chapter 1"])
    }

    func testSortByChapterNumber() {
        var chapters = makeChapters()
        // Swap remote orders to prove number-based sorting is independent
        chapters[0].sourceOrder = 0
        chapters[1].sourceOrder = 1
        chapters[2].sourceOrder = 2
        chapters.swapAt(0, 2) // now name order is 3, 2, 1 with orders 2, 1, 0

        let sorted = ChapterSort.sorted(chapters, by: .chapterNumber, descending: false)
        XCTAssertEqual(sorted.map(\.name), ["Chapter 1", "Chapter 2", "Chapter 3"])
    }

    func testSortByDateUploaded() {
        let base = Date(timeIntervalSince1970: 1000)
        let chapters = [
            Chapter(url: "a", name: "A", dateUpload: base.addingTimeInterval(200), sourceOrder: 0),
            Chapter(url: "b", name: "B", dateUpload: base, sourceOrder: 1),
            Chapter(url: "c", name: "C", dateUpload: base.addingTimeInterval(100), sourceOrder: 2),
        ]
        let sorted = ChapterSort.sorted(chapters, by: .dateUploaded, descending: false)
        XCTAssertEqual(sorted.map(\.name), ["B", "C", "A"])
    }

    func testNextAndPreviousChapter() {
        let chapters = makeChapters()
        let first = chapters[1] // Chapter 1

        let next = ChapterSort.nextChapter(after: first, in: chapters)
        XCTAssertEqual(next?.name, "Chapter 2")

        let last = chapters[0] // Chapter 3
        XCTAssertNil(ChapterSort.nextChapter(after: last, in: chapters))

        let previous = ChapterSort.previousChapter(before: last, in: chapters)
        XCTAssertEqual(previous?.name, "Chapter 2")
        XCTAssertNil(ChapterSort.previousChapter(before: first, in: chapters))
    }

    // MARK: - Reader navigation across chapter boundaries

    func testNavigatorAdvancesPagesThenChapters() {
        let chapters = makeChapters()
        let current = chapters[1] // Chapter 1

        let mid = ReaderNavigator.advance(pageIndex: 1, pageCount: 5, chapter: current, chapters: chapters)
        XCTAssertEqual(mid, .page(2))

        let end = ReaderNavigator.advance(pageIndex: 4, pageCount: 5, chapter: current, chapters: chapters)
        XCTAssertEqual(end, .endOfChapter(next: chapters[2]))

        let lastChapter = chapters[0] // Chapter 3 (last in source order)
        let finalEnd = ReaderNavigator.advance(pageIndex: 0, pageCount: 1, chapter: lastChapter, chapters: chapters)
        XCTAssertEqual(finalEnd, .endOfChapter(next: nil))
    }

    func testNavigatorRewindsPagesThenChapters() {
        let chapters = makeChapters()
        let current = chapters[2] // Chapter 3

        let mid = ReaderNavigator.rewind(pageIndex: 3, chapter: current, chapters: chapters)
        XCTAssertEqual(mid, .page(2))

        let start = ReaderNavigator.rewind(pageIndex: 0, chapter: current, chapters: chapters)
        XCTAssertEqual(start, .startOfChapter(previous: chapters[1]))
    }

    // MARK: - Reading progress state

    func testReaderPositionProgress() {
        let start = ReaderPosition(mangaID: 1, chapterID: 2, pageIndex: 0, pageCount: 10)
        XCTAssertEqual(start.fraction, 0.1, accuracy: 0.0001)
        XCTAssertFalse(start.isCompleted)

        let end = ReaderPosition(mangaID: 1, chapterID: 2, pageIndex: 9, pageCount: 10)
        XCTAssertEqual(end.fraction, 1.0, accuracy: 0.0001)
        XCTAssertTrue(end.isCompleted)
    }

    func testProgressPersistenceViaStore() async throws {
        let store = try LibraryStore.inMemory()
        let manga = try await store.upsertManga(Manga(sourceID: SourceID(rawValue: 1), url: "a", title: "A"))
        let chapters = try await store.replaceChapters(
            mangaID: manga.id,
            remoteChapters: [SourceChapter(url: "c1", name: "Ch 1", sourceOrder: 0)]
        )

        try await store.updateProgress(chapterID: chapters[0].id, page: 7, pageCount: 12)
        var chapter = await store.chapter(id: chapters[0].id)
        XCTAssertEqual(chapter?.lastPageRead, 7)
        XCTAssertFalse(chapter?.read ?? true)

        // Reaching the final page marks the chapter read
        try await store.updateProgress(chapterID: chapters[0].id, page: 11, pageCount: 12)
        chapter = await store.chapter(id: chapters[0].id)
        XCTAssertEqual(chapter?.lastPageRead, 11)
        XCTAssertTrue(chapter?.read ?? false)
    }

    func testReaderPositionSurvivesRestart() async throws {
        let dir = directoryURL()
        do {
            let prefs = try PreferencesStore(directory: dir)
            let position = ReaderPosition(mangaID: 1, chapterID: 2, pageIndex: 5, pageCount: 20)
            try await prefs.savePosition(position)
        }
        let reopened = try PreferencesStore(directory: dir)
        await reopened.loadPositionsFromDisk()
        let loaded = await reopened.position(chapterID: 2)
        XCTAssertEqual(loaded?.pageIndex, 5)
        XCTAssertEqual(loaded?.pageCount, 20)
    }

    private func directoryURL() -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("yomi-prefs-\(UUID().uuidString)", isDirectory: true)
        return dir
    }
}
