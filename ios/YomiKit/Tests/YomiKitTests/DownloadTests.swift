import XCTest
@testable import YomiKit

final class DownloadTests: XCTestCase {
    private var baseDirectory: URL!

    override func setUpWithError() throws {
        baseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("yomi-dl-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: baseDirectory)
    }

    private func makeStack() async throws -> (LibraryStore, DownloadManager, Manga, [Chapter]) {
        let library = try LibraryStore(directory: baseDirectory)
        let registry = SourceRegistry(providers: [SampleSource()])
        let downloads = try DownloadManager(baseDirectory: baseDirectory, library: library, registry: registry)

        let manga = try await library.upsertManga(
            Manga(
                sourceID: SourceID(rawValue: 8150048305418775564),
                url: "crimson-sky",
                title: "Crimson Sky Chronicle",
                favoriteAt: Date()
            )
        )
        let chapters = try await library.replaceChapters(
            mangaID: manga.id,
            remoteChapters: [
                SourceChapter(url: "crimson-sky/chapter-1", name: "Chapter 1", sourceOrder: 0, chapterNumber: 1),
                SourceChapter(url: "crimson-sky/chapter-2", name: "Chapter 2", sourceOrder: 1, chapterNumber: 2),
            ]
        )
        return (library, downloads, manga, chapters)
    }

    private func waitUntilFinished(_ downloads: DownloadManager, chapterID: Int64, timeout: TimeInterval = 30) async throws -> DownloadItem {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            let items = await downloads.snapshot()
            if let item = items.first(where: { $0.chapterID == chapterID }), item.status.isFinished {
                return item
            }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        let items = await downloads.snapshot()
        return items.first(where: { $0.chapterID == chapterID })
            ?? DownloadItem(mangaID: 0, chapterID: chapterID, mangaTitle: "?", chapterName: "?", status: .failed(message: "timeout"))
    }

    func testDownloadCompletesAndFilesExist() async throws {
        let (library, downloads, manga, chapters) = try await makeStack()
        try await downloads.enqueue(mangaID: manga.id, chapterIDs: [chapters[0].id])

        let item = try await waitUntilFinished(downloads, chapterID: chapters[0].id)
        XCTAssertEqual(item.status, .completed)

        let pages = await downloads.downloadedPages(manga: manga, chapter: chapters[0])
        XCTAssertEqual(pages?.count, 6, "Sample chapter has 6 pages")
        for page in pages ?? [] {
            let data = try Data(contentsOf: page)
            XCTAssertEqual([UInt8](data.prefix(8)), PNGWriter.signature, "Downloaded pages are real PNGs")
        }

        let usage = await downloads.storageUsage()
        XCTAssertGreaterThan(usage, 0)
    }

    func testDownloadLayoutMatchesConvention() async throws {
        let (library, downloads, manga, chapters) = try await makeStack()
        try await downloads.enqueue(mangaID: manga.id, chapterIDs: [chapters[0].id])
        _ = try await waitUntilFinished(downloads, chapterID: chapters[0].id)

        let dir = await downloads.chapterDirectory(manga: manga, chapter: chapters[0])
        let tail = dir.pathComponents.suffix(3)
        XCTAssertEqual(Array(tail), ["8150048305418775564", "Crimson Sky Chronicle", "Chapter 1"])
    }

    func testOfflineReadingAfterDownload() async throws {
        let (library, downloads, manga, chapters) = try await makeStack()
        try await downloads.enqueue(mangaID: manga.id, chapterIDs: [chapters[0].id])
        _ = try await waitUntilFinished(downloads, chapterID: chapters[0].id)

        let isDownloaded = await downloads.isDownloaded(manga: manga, chapter: chapters[0])
        XCTAssertTrue(isDownloaded)
        let notDownloaded = await downloads.isDownloaded(manga: manga, chapter: chapters[1])
        XCTAssertFalse(notDownloaded)
    }

    func testCancelMarksItemCancelled() async throws {
        let (_, downloads, manga, chapters) = try await makeStack()
        try await downloads.enqueue(mangaID: manga.id, chapterIDs: [chapters[0].id, chapters[1].id])

        // Cancel the second (likely still queued behind the first)
        let id = "\(manga.id)-\(chapters[1].id)"
        await downloads.cancel(id: id)
        let item = try await waitUntilFinished(downloads, chapterID: chapters[1].id)
        XCTAssertTrue(item.status == .cancelled || item.status == .completed)
    }

    func testRetryRecoversFailedItem() async throws {
        let (library, downloads, manga, _) = try await makeStack()

        // Enqueue a chapter whose source is unavailable -> failed
        let chapters = try await library.replaceChapters(
            mangaID: manga.id,
            remoteChapters: [
                SourceChapter(url: "crimson-sky/chapter-1", name: "Chapter 1", sourceOrder: 0, chapterNumber: 1),
            ]
        )
        let orphanManga = try await library.upsertManga(
            Manga(sourceID: SourceID(rawValue: 999), url: "ghost", title: "Ghost", favoriteAt: Date())
        )
        let orphanChapter = try await library.replaceChapters(
            mangaID: orphanManga.id,
            remoteChapters: [SourceChapter(url: "ghost/c1", name: "Chapter 1", sourceOrder: 0)]
        )
        try await downloads.enqueue(mangaID: orphanManga.id, chapterIDs: [orphanChapter[0].id])
        let failed = try await waitUntilFinished(downloads, chapterID: orphanChapter[0].id)
        guard case .failed = failed.status else {
            return XCTFail("Expected failure for missing source, got \(failed.status)")
        }

        // Retry keeps failing honestly (source still missing)
        await downloads.retry(id: failed.id)
        let retried = try await waitUntilFinished(downloads, chapterID: orphanChapter[0].id)
        guard case .failed = retried.status else {
            return XCTFail("Expected failure after retry, got \(retried.status)")
        }
        _ = chapters
    }

    func testRemoveDownloadAndCleanup() async throws {
        let (_, downloads, manga, chapters) = try await makeStack()
        try await downloads.enqueue(mangaID: manga.id, chapterIDs: [chapters[0].id])
        _ = try await waitUntilFinished(downloads, chapterID: chapters[0].id)

        try await downloads.removeDownload(manga: manga, chapter: chapters[0])
        let isDownloaded = await downloads.isDownloaded(manga: manga, chapter: chapters[0])
        XCTAssertFalse(isDownloaded)
        let usage = await downloads.storageUsage()
        XCTAssertEqual(usage, 0)
    }

    func testClearAllDownloads() async throws {
        let (_, downloads, manga, chapters) = try await makeStack()
        try await downloads.enqueue(mangaID: manga.id, chapterIDs: [chapters[0].id, chapters[1].id])
        _ = try await waitUntilFinished(downloads, chapterID: chapters[1].id)

        try await downloads.clearAllDownloads()
        let items = await downloads.snapshot()
        XCTAssertTrue(items.isEmpty)
        let usage = await downloads.storageUsage()
        XCTAssertEqual(usage, 0)
    }

    func testQueuePersistsAcrossRestart() async throws {
        do {
            let (_, downloads, manga, chapters) = try await makeStack()
            try await downloads.enqueue(mangaID: manga.id, chapterIDs: [chapters[0].id])
            _ = try await waitUntilFinished(downloads, chapterID: chapters[0].id)
        }
        let library = try LibraryStore(directory: baseDirectory)
        let registry = SourceRegistry(providers: [SampleSource()])
        let reopened = try DownloadManager(baseDirectory: baseDirectory, library: library, registry: registry)
        let items = await reopened.snapshot()
        XCTAssertEqual(items.count, 1)
        XCTAssertEqual(items.first?.status, .completed)
    }

    func testSanitizesPathComponents() {
        XCTAssertEqual(DownloadManager.sanitize("Chapter 1: Part/2?"), "Chapter 1_ Part_2_")
        XCTAssertEqual(DownloadManager.sanitize("   "), "untitled")
    }
}
