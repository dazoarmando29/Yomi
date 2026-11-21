import XCTest
@testable import YomiKit

final class LibrarySearchTests: XCTestCase {
    private func manga(
        title: String,
        author: String? = nil,
        artist: String? = nil,
        description: String? = nil,
        genres: [String] = []
    ) -> Manga {
        Manga(
            sourceID: SourceID(rawValue: 1),
            url: title.lowercased(),
            title: title,
            author: author,
            artist: artist,
            mangaDescription: description,
            genres: genres,
            favoriteAt: Date()
        )
    }

    func testSimpleTermMatchesTitleAuthorDescriptionAndGenre() {
        let entries = [
            manga(title: "Crimson Sky", author: "A. Sample", description: "pirates", genres: ["Action"]),
            manga(title: "Neon Garden", author: "B. Example", description: "rooftop plants", genres: ["Slice of Life"]),
        ]
        XCTAssertEqual(LibrarySearch(query: "crimson").filter(entries).count, 1)
        XCTAssertEqual(LibrarySearch(query: "sample").filter(entries).count, 1)
        XCTAssertEqual(LibrarySearch(query: "rooftop").filter(entries).count, 1)
        XCTAssertEqual(LibrarySearch(query: "action").filter(entries).count, 1)
        XCTAssertEqual(LibrarySearch(query: "zzz").filter(entries).count, 0)
    }

    func testQuotedPhrases() {
        let entries = [
            manga(title: "Long Strip Theory"),
            manga(title: "Crimson Sky Chronicle"),
        ]
        XCTAssertEqual(LibrarySearch(query: "\"strip theory\"").filter(entries).map(\.title), ["Long Strip Theory"])
    }

    func testNegation() {
        let entries = [
            manga(title: "Alpha", genres: ["Action"]),
            manga(title: "Beta", genres: ["Romance"]),
        ]
        XCTAssertEqual(LibrarySearch(query: "-romance").filter(entries).map(\.title), ["Alpha"])
    }

    func testFieldPrefixes() {
        let entries = [
            manga(title: "Alpha", author: "Taro"),
            manga(title: "Beta", author: "Hanako"),
        ]
        XCTAssertEqual(LibrarySearch(query: "author:taro").filter(entries).map(\.title), ["Alpha"])
        XCTAssertEqual(LibrarySearch(query: "title:beta").filter(entries).map(\.title), ["Beta"])
        XCTAssertEqual(LibrarySearch(query: "-author:hanako").filter(entries).map(\.title), ["Alpha"])
    }

    func testTokenizer() {
        let tokens = LibrarySearch.tokenize("genre:\"slice of life\" -title:cake extra")
        XCTAssertEqual(
            tokens,
            [
                .field(name: "genre", value: "slice of life", negated: false),
                .field(name: "title", value: "cake", negated: true),
                .text("extra", negated: false),
            ]
        )
    }
}

final class SourceIDTests: XCTestCase {
    func testDerivationMatchesAndroidMD5Scheme() {
        // MD5("yomi sample source/en/1") = f11ac9c0c2c2ac0c9d12f05c5d6fa507
        // first 64 bits big-endian, sign bit cleared
        let id = SourceID.derive(name: "Yomi Sample Source", lang: "en", versionId: 1)
        XCTAssertEqual(id.rawValue, 8150048305418775564)
    }

    func testDerivationIsCaseInsensitiveOnName() {
        let lower = SourceID.derive(name: "yomi sample source", lang: "en", versionId: 1)
        let upper = SourceID.derive(name: "YOMI SAMPLE SOURCE", lang: "en", versionId: 1)
        XCTAssertEqual(lower, upper)
    }

    func testVersionChangesID() {
        let v1 = SourceID.derive(name: "X", lang: "en", versionId: 1)
        let v2 = SourceID.derive(name: "X", lang: "en", versionId: 2)
        XCTAssertNotEqual(v1, v2)
    }

    func testSignBitAlwaysCleared() {
        for name in ["a", "b", "source", "test source", "😀"] {
            let id = SourceID.derive(name: name, lang: "en", versionId: 1)
            XCTAssertGreaterThanOrEqual(id.rawValue, 0, "Sign bit must stay clear for \(name)")
        }
    }

    func testMD5KnownVector() {
        // MD5("abc") = 900150983cd24fb0d6963f7d28e17f72
        XCTAssertEqual(
            MD5.hexString(Array("abc".utf8)),
            "900150983cd24fb0d6963f7d28e17f72"
        )
    }
}
