import XCTest
@testable import YomiKit

final class SampleSourceTests: XCTestCase {
    private let source = SampleSource()

    func testSourceIDMatchesAndroidDerivation() {
        // MD5("yomi sample source/en/1") first 64 bits, sign bit cleared —
        // identical to HttpSource.generateId on Android.
        XCTAssertEqual(source.id.rawValue, 8150048305418775564)
    }

    func testPopularMangaPaginates() async throws {
        let page1 = try await source.popularManga(page: 1)
        XCTAssertEqual(page1.mangas.count, 6)
        XCTAssertFalse(page1.hasNextPage)

        let empty = try await source.popularManga(page: 2)
        XCTAssertTrue(empty.mangas.isEmpty)

        let invalid = try await source.popularManga(page: 0)
        XCTAssertTrue(invalid.mangas.isEmpty)
    }

    func testSearchFiltersByTitleAuthorAndGenre() async throws {
        let byTitle = try await source.searchManga(query: "crimson", page: 1)
        XCTAssertEqual(byTitle.mangas.map(\.title), ["Crimson Sky Chronicle"])

        let byAuthor = try await source.searchManga(query: "datum", page: 1)
        XCTAssertEqual(byAuthor.mangas.map(\.title), ["Long Strip Theory"])

        let byGenre = try await source.searchManga(query: "romance", page: 1)
        XCTAssertEqual(byGenre.mangas.map(\.title), ["Quiet Ember"])

        let none = try await source.searchManga(query: "zzzz-not-a-manga", page: 1)
        XCTAssertTrue(none.mangas.isEmpty)

        let all = try await source.searchManga(query: "", page: 1)
        XCTAssertEqual(all.mangas.count, 6, "Empty query falls back to popular")
    }

    func testMangaDetailsRoundTrip() async throws {
        let listing = try await source.popularManga(page: 1)
        let entry = try XCTUnwrap(listing.mangas.first { $0.url == "crimson-sky" })
        let details = try await source.mangaDetails(entry)

        XCTAssertEqual(details.title, "Crimson Sky Chronicle")
        XCTAssertEqual(details.author, "A. Sample")
        XCTAssertEqual(details.genres, ["Action", "Adventure", "Fantasy"])
        XCTAssertEqual(details.status, .ongoing)
        XCTAssertEqual(details.sourceID, source.id)
    }

    func testUnknownMangaThrowsNotFound() async {
        do {
            _ = try await source.mangaDetails(SourceManga(url: "nope", title: "Nope"))
            XCTFail("Expected notFound")
        } catch let error as SourceError {
            XCTAssertEqual(error, .notFound)
        } catch {
            XCTFail("Unexpected error \(error)")
        }
    }

    func testChapterListIsOrdered() async throws {
        let details = try await source.mangaDetails(SourceManga(url: "crimson-sky", title: "Crimson Sky Chronicle"))
        let chapters = try await source.chapterList(details)

        XCTAssertEqual(chapters.count, 4)
        XCTAssertEqual(chapters.map(\.sourceOrder), [0, 1, 2, 3])
        XCTAssertEqual(chapters.map(\.chapterNumber), [1, 2, 3, 4])
        XCTAssertEqual(chapters[0].name, "Chapter 1")
    }

    func testPageListDeterministic() async throws {
        let details = try await source.mangaDetails(SourceManga(url: "long-strip-theory", title: "Long Strip Theory"))
        let chapters = try await source.chapterList(details)
        let pages = try await source.pageList(details, chapter: Chapter(url: chapters[0].url, name: chapters[0].name))

        XCTAssertEqual(pages.count, 40, "Long chapter exercises webtoon scrolling")
        XCTAssertEqual(pages.first?.index, 0)
        XCTAssertEqual(pages.first?.imageURL, "sample://page/long-strip-theory/1/1.png")
        XCTAssertEqual(pages.last?.imageURL, "sample://page/long-strip-theory/1/40.png")

        // Deterministic across calls
        let again = try await source.pageList(details, chapter: Chapter(url: chapters[0].url, name: chapters[0].name))
        XCTAssertEqual(pages, again)
    }

    func testChapterURLIndexParsing() {
        XCTAssertEqual(SampleSource.chapterIndex(fromChapterURL: "crimson-sky/chapter-3"), 2)
        XCTAssertNil(SampleSource.chapterIndex(fromChapterURL: "crimson-sky"))
    }

    func testRegistryLookup() async {
        let registry = SourceRegistry(providers: [SampleSource()])
        let found = await registry.provider(id: SourceID(rawValue: 8150048305418775564))
        XCTAssertNotNil(found)
        let missing = await registry.provider(id: SourceID(rawValue: 1))
        XCTAssertNil(missing)
    }

    func testCatalogIsDeterministic() {
        let first = SampleSource.catalog.map(\.key)
        let second = SampleSource.catalog.map(\.key)
        XCTAssertEqual(first, second)
        XCTAssertEqual(first.count, 6)
    }
}
