import Foundation

// MARK: - Wire types

public enum ProtobufWireType: UInt64, Sendable {
    case varint = 0
    case fixed64 = 1
    case lengthDelimited = 2
    case startGroup = 3
    case endGroup = 4
    case fixed32 = 5
}

public enum ProtobufError: Error, Sendable, Equatable {
    case truncated
    case invalid(String)
}

/// Minimal protobuf wire-format reader (compatible with kotlinx.serialization
/// ProtoBuf output used by the Android backup writer). Unknown fields can be
/// skipped without decoding, matching Android's tolerant restore behaviour.
public struct ProtobufReader: Sendable {
    private let bytes: [UInt8]
    private var index: Int

    public init(_ data: Data) {
        self.bytes = [UInt8](data)
        self.index = 0
    }

    public var isAtEnd: Bool { index >= bytes.count }

    public struct Field: Sendable {
        public let number: Int
        public let wireType: ProtobufWireType
        public let varintValue: UInt64
        public let dataValue: Data
        public let fixed32Value: UInt32
    }

    /// Reads the next field, or nil at end of input.
    public mutating func next() throws -> Field? {
        guard !isAtEnd else { return nil }
        let tag = try readVarint()
        let number = Int(tag >> 3)
        guard number > 0, let wireType = ProtobufWireType(rawValue: tag & 0x7) else {
            throw ProtobufError.invalid("Invalid field tag \(tag)")
        }
        switch wireType {
        case .varint:
            let value = try readVarint()
            return Field(number: number, wireType: wireType, varintValue: value, dataValue: Data(), fixed32Value: 0)
        case .fixed64:
            let value = try readFixed(8)
            return Field(number: number, wireType: wireType, varintValue: value, dataValue: Data(), fixed32Value: 0)
        case .fixed32:
            let value = try readFixed(4)
            return Field(number: number, wireType: wireType, varintValue: value, dataValue: Data(), fixed32Value: UInt32(value))
        case .lengthDelimited:
            let length = Int(try readVarint())
            guard length >= 0, index + length <= bytes.count else { throw ProtobufError.truncated }
            let payload = Data(bytes[index..<(index + length)])
            index += length
            return Field(number: number, wireType: wireType, varintValue: UInt64(length), dataValue: payload, fixed32Value: 0)
        case .startGroup, .endGroup:
            throw ProtobufError.invalid("Groups are not supported")
        }
    }

    public mutating func readVarint() throws -> UInt64 {
        var result: UInt64 = 0
        var shift: UInt64 = 0
        while true {
            guard index < bytes.count else { throw ProtobufError.truncated }
            let byte = bytes[index]
            index += 1
            result |= UInt64(byte & 0x7f) << shift
            if byte & 0x80 == 0 { return result }
            shift += 7
            if shift >= 64 { throw ProtobufError.invalid("Varint too long") }
        }
    }

    private mutating func readFixed(_ length: Int) throws -> UInt64 {
        guard index + length <= bytes.count else { throw ProtobufError.truncated }
        var result: UInt64 = 0
        for offset in 0..<length {
            result |= UInt64(bytes[index + offset]) << (8 * UInt64(offset))
        }
        index += length
        return result
    }
}

// MARK: - Convenience decoding helpers

extension ProtobufReader {
    /// Decodes a nested message payload into a fresh reader.
    public static func reader(_ payload: Data) -> ProtobufReader {
        ProtobufReader(payload)
    }

    /// Iterates a payload and returns one reader per repeated length-delimited entry.
    public static func repeatedMessages(_ payload: Data) throws -> [Data] {
        var reader = ProtobufReader(payload)
        var result: [Data] = []
        while let field = try reader.next() {
            if field.wireType == .lengthDelimited {
                result.append(field.dataValue)
            }
        }
        return result
    }

    public static func string(_ data: Data) -> String {
        String(decoding: data, as: UTF8.self)
    }

    public static func bool(_ varint: UInt64) -> Bool {
        varint != 0
    }

    /// Interprets a varint as a signed 64-bit value (kotlinx int semantics).
    public static func signed(_ varint: UInt64) -> Int64 {
        Int64(bitPattern: varint)
    }

    /// Interprets a fixed32 field as an IEEE-754 float (kotlinx `Float` encoding).
    public static func float(_ fixed32: UInt32) -> Float {
        Float(bitPattern: fixed32)
    }
}

// MARK: - Writer

/// Minimal protobuf wire-format writer for producing backups that the Android
/// app can restore (same schema/field numbers — see `BackupModels.swift`).
public struct ProtobufWriter: Sendable {
    public private(set) var data = Data()

    public init() {}

    public mutating func appendVarint(_ value: UInt64) {
        var value = value
        repeat {
            var byte = UInt8(value & 0x7f)
            value >>= 7
            if value != 0 { byte |= 0x80 }
            data.append(byte)
        } while value != 0
    }

    private mutating func appendTag(number: Int, wireType: ProtobufWireType) {
        appendVarint(UInt64(number) << 3 | wireType.rawValue)
    }

    public mutating func writeVarintField(_ number: Int, _ value: Int64) {
        appendTag(number: number, wireType: .varint)
        appendVarint(UInt64(bitPattern: value))
    }

    public mutating func writeBoolField(_ number: Int, _ value: Bool) {
        writeVarintField(number, value ? 1 : 0)
    }

    public mutating func writeFloatField(_ number: Int, _ value: Float) {
        appendTag(number: number, wireType: .fixed32)
        var le = value.bitPattern.littleEndian
        withUnsafeBytes(of: &le) { data.append(contentsOf: $0) }
    }

    public mutating func writeStringField(_ number: Int, _ value: String) {
        writeBytesField(number, Data(value.utf8))
    }

    public mutating func writeBytesField(_ number: Int, _ value: Data) {
        appendTag(number: number, wireType: .lengthDelimited)
        appendVarint(UInt64(value.count))
        data.append(value)
    }

    public mutating func writeMessageField(_ number: Int, _ payload: Data) {
        writeBytesField(number, payload)
    }
}
