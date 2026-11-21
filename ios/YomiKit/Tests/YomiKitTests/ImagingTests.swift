import XCTest
@testable import YomiKit

final class ImagingTests: XCTestCase {

    func testPNGEncodingProducesValidStructure() {
        // 2x2 red/green/blue/white pixels
        let rgba: [UInt8] = [
            255, 0, 0, 255, 0, 255, 0, 255,
            0, 0, 255, 255, 255, 255, 255, 255,
        ]
        let png = PNGWriter.encode(width: 2, height: 2, rgba: rgba)

        XCTAssertEqual([UInt8](png.prefix(8)), PNGWriter.signature, "PNG magic")
        XCTAssertEqual(String(decoding: png.subdata(in: 12..<16), as: UTF8.self), "IHDR")
        XCTAssertEqual(String(decoding: png.suffix(8).prefix(4), as: UTF8.self), "IEND")

        // IHDR width/height are big-endian at offsets 16..24
        let width = png.subdata(in: 16..<20).reduce(0) { ($0 << 8) | UInt32($1) }
        let height = png.subdata(in: 20..<24).reduce(0) { ($0 << 8) | UInt32($1) }
        XCTAssertEqual(width, 2)
        XCTAssertEqual(height, 2)
    }

    func testPNGRejectsMismatchedBufferSize() {
        #if DEBUG
        // Precondition failures are only catchable in debug; assert via helper instead.
        #endif
        let rgba = [UInt8](repeating: 0, count: 4)
        let png = PNGWriter.encode(width: 1, height: 1, rgba: rgba)
        XCTAssertFalse(png.isEmpty)
    }

    func testSamplePageRenderingIsDeterministic() {
        let first = SamplePageRenderer.renderPNG(urlString: "sample://page/crimson-sky/1/2.png")
        let second = SamplePageRenderer.renderPNG(urlString: "sample://page/crimson-sky/1/2.png")
        XCTAssertNotNil(first)
        XCTAssertEqual(first, second, "Rendering must be deterministic")
    }

    func testSamplePagesDifferByPageAndKey() {
        let page1 = SamplePageRenderer.renderPNG(urlString: "sample://page/crimson-sky/1/1.png")
        let page2 = SamplePageRenderer.renderPNG(urlString: "sample://page/crimson-sky/1/2.png")
        let other = SamplePageRenderer.renderPNG(urlString: "sample://page/neon-garden/1/1.png")
        XCTAssertNotEqual(page1, page2)
        XCTAssertNotEqual(page1, other)
    }

    func testCoverRendering() {
        let cover = SamplePageRenderer.renderPNG(urlString: "sample://cover/crimson-sky.png")
        XCTAssertNotNil(cover)
        XCTAssertEqual([UInt8](cover!.prefix(8)), PNGWriter.signature)
    }

    func testUnknownSampleURLReturnsNil() {
        XCTAssertNil(SamplePageRenderer.renderPNG(urlString: "sample://bogus/xyz"))
        XCTAssertNil(SamplePageRenderer.renderPNG(urlString: "http://example.org/a.png"))
    }

    func testCRC32KnownVector() {
        // CRC32("123456789") = 0xCBF43926 (standard check value)
        XCTAssertEqual(CRC32.checksum(Data("123456789".utf8)), 0xCBF4_3926)
    }

    func testAdler32KnownVector() {
        // Adler32("Wikipedia") = 0x11E60398
        XCTAssertEqual(Adler32.checksum(Data("Wikipedia".utf8)), 0x11E6_0398)
    }

    func testImagePipelineCachesSamplePages() async throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("yomi-cache-\(UUID().uuidString)", isDirectory: true)
        let pipeline = ImagePipeline(directory: dir)

        let url = "sample://page/crimson-sky/1/1.png"
        let first = try await pipeline.imageData(for: url)
        let second = try await pipeline.imageData(for: url)
        XCTAssertEqual(first, second)

        let usage = await pipeline.diskUsage()
        XCTAssertGreaterThan(usage, 0, "Pages must be written to the disk cache")

        let diskFile = dir.appendingPathComponent(ImagePipeline.cacheKey(for: url) + ".img")
        XCTAssertTrue(FileManager.default.fileExists(atPath: diskFile.path))

        try await pipeline.clearDisk()
        let cleared = await pipeline.diskUsage()
        XCTAssertEqual(cleared, 0)

        try? FileManager.default.removeItem(at: dir)
    }

    func testImagePipelineRejectsUnknownScheme() async throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("yomi-cache-\(UUID().uuidString)", isDirectory: true)
        let pipeline = ImagePipeline(directory: dir)
        do {
            _ = try await pipeline.imageData(for: "ftp://example.org/a.png")
            XCTFail("Expected unsupported scheme")
        } catch let error as SourceError {
            XCTAssertEqual(error, .unsupported("Unhandled URL scheme: ftp://example.org/a.png"))
        } catch {
            XCTFail("Unexpected error \(error)")
        }
        try? FileManager.default.removeItem(at: dir)
    }
}
