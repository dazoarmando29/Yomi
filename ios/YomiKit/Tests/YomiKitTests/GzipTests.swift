import XCTest
@testable import YomiKit

final class GzipTests: XCTestCase {

    static let textFixtureBase64 = """
    H4sIAOOoAWsC/+3K2xGCMBQA0VZuBfSkEARRoiHBR/U6VuHH+dzZU6cU9zb3SxxLfqwx5mec2/W2Rd5Tifrdl8P7FUM+db+CYRiGYRiGYRiGYRiGYRiGYRiGYRiGYRiGYRiGYRiGYRiGYRiGYRiGYRiGYRiGYRiGYRiG/xJ/ABJ/fxxQRgAA
    """

    /// The fixture decompresses to "the quick brown fox jumps over the lazy dog. " x400
    /// (18000 bytes) — Python's gzip produced a dynamic-Huffman deflate stream.
    func testDecompressesDynamicHuffmanGzip() throws {
        let cleaned = Self.textFixtureBase64
            .components(separatedBy: .whitespacesAndNewlines)
            .joined()
        let data = try XCTUnwrap(Data(base64Encoded: cleaned))
        let inflated = try Gzip.decompress(data)

        XCTAssertEqual(inflated.count, 18000)
        let expected = String(repeating: "the quick brown fox jumps over the lazy dog. ", count: 400)
        XCTAssertEqual(String(decoding: inflated, as: UTF8.self), expected)
    }

    func testCompressDecompressRoundTrip() throws {
        for size in [0, 1, 100, 70000, 200000] {
            var payload = Data()
            for index in 0..<size {
                payload.append(UInt8(index % 251))
            }
            let compressed = Gzip.compress(payload)
            let decompressed = try Gzip.decompress(compressed)
            XCTAssertEqual(decompressed, payload, "round trip failed at size \(size)")
        }
    }

    func testPassthroughNonGzipData() throws {
        let raw = Data([0x08, 0x96, 0x01])
        let result = try Gzip.decompress(raw)
        XCTAssertEqual(result, raw)
    }

    func testCorruptedGzipThrowsChecksumMismatch() throws {
        let payload = Data("hello yomi".utf8)
        var compressed = Gzip.compress(payload)
        // Corrupt a payload byte inside the stored block (after 10-byte header)
        compressed[15] ^= 0xff
        XCTAssertThrowsError(try Gzip.decompress(compressed))
    }

    func testTruncatedGzipThrows() {
        let truncated = Data([0x1f, 0x8b, 0x08, 0x00, 0x00])
        XCTAssertThrowsError(try Gzip.decompress(truncated))
    }
}
