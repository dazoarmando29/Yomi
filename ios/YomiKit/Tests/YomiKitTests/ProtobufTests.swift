import XCTest
@testable import YomiKit

final class ProtobufTests: XCTestCase {

    func testVarintRoundTrip() throws {
        var writer = ProtobufWriter()
        writer.appendVarint(0)
        writer.appendVarint(127)
        writer.appendVarint(128)
        writer.appendVarint(300)
        writer.appendVarint(UInt64(Int64.max))

        var reader = ProtobufReader(writer.data)
        XCTAssertEqual(try reader.readVarint(), 0)
        XCTAssertEqual(try reader.readVarint(), 127)
        XCTAssertEqual(try reader.readVarint(), 128)
        XCTAssertEqual(try reader.readVarint(), 300)
        XCTAssertEqual(try reader.readVarint(), UInt64(Int64.max))
        XCTAssertTrue(reader.isAtEnd)
    }

    func testFieldTagsRoundTrip() throws {
        var writer = ProtobufWriter()
        writer.writeVarintField(1, 42)
        writer.writeStringField(2, "hello")
        writer.writeBoolField(3, true)
        writer.writeFloatField(4, 12.5)
        writer.writeVarintField(1000, -1)

        var reader = ProtobufReader(writer.data)

        let f1 = try XCTUnwrap(try reader.next())
        XCTAssertEqual(f1.number, 1)
        XCTAssertEqual(f1.wireType, .varint)
        XCTAssertEqual(ProtobufReader.signed(f1.varintValue), 42)

        let f2 = try XCTUnwrap(try reader.next())
        XCTAssertEqual(f2.number, 2)
        XCTAssertEqual(ProtobufReader.string(f2.dataValue), "hello")

        let f3 = try XCTUnwrap(try reader.next())
        XCTAssertTrue(ProtobufReader.bool(f3.varintValue))

        let f4 = try XCTUnwrap(try reader.next())
        XCTAssertEqual(f4.wireType, .fixed32)
        XCTAssertEqual(ProtobufReader.float(f4.fixed32Value), 12.5)

        let f5 = try XCTUnwrap(try reader.next())
        XCTAssertEqual(f5.number, 1000)
        XCTAssertEqual(ProtobufReader.signed(f5.varintValue), -1)

        XCTAssertNil(try reader.next())
    }

    func testTruncatedInputThrows() {
        var reader = ProtobufReader(Data([0x0a, 0x05, 0x41])) // field 1, len 5, only 1 byte
        XCTAssertThrowsError(try reader.next())
    }

    func testRepeatedMessageDecoding() throws {
        var writer = ProtobufWriter()
        writer.writeStringField(1, "a")
        writer.writeStringField(1, "b")
        writer.writeStringField(1, "c")

        var reader = ProtobufReader(writer.data)
        var values: [String] = []
        while let field = try reader.next() {
            values.append(ProtobufReader.string(field.dataValue))
        }
        XCTAssertEqual(values, ["a", "b", "c"])
    }

    func testPackedVarintListDecoding() throws {
        // Packed encoding of [1, 2, 300] as field 17
        var packed = Data()
        packed.append(0x08); packed.append(0x01)
        packed.append(0x08); packed.append(0x02)
        packed.append(0x08); packed.append(0xac); packed.append(0x02)
        var writer = ProtobufWriter()
        writer.writeBytesField(17, packed)

        var reader = ProtobufReader(writer.data)
        let field = try XCTUnwrap(try reader.next())
        let values = try BackupCodec.decodeInt64List(field)
        XCTAssertEqual(values, [1, 2, 300])
    }
}
