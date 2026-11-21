import Foundation

/// Pure-Swift gzip (RFC 1952) + DEFLATE (RFC 1951) decompressor.
/// Backups on Android are written with okio's gzip sink; this decodes them
/// without any external dependency so restore works on every platform.
public enum Gzip {
    public enum GzipError: Error, Sendable, Equatable {
        case invalidHeader
        case invalidDeflate(String)
        case checksumMismatch
        case truncated
    }

    /// Decompresses gzip data. If the input does not start with the gzip magic
    /// it is returned unchanged (raw protobuf is accepted as well).
    public static func decompress(_ data: Data) throws -> Data {
        let bytes = [UInt8](data)
        guard bytes.count >= 2, bytes[0] == 0x1f, bytes[1] == 0x8b else {
            return data // not gzip — passthrough
        }
        guard bytes.count >= 10, bytes[2] == 8 else { throw GzipError.invalidHeader }

        let flags = bytes[3]
        var index = 10

        if flags & 0x04 != 0 { // FEXTRA
            guard index + 2 <= bytes.count else { throw GzipError.truncated }
            let extraLength = Int(bytes[index]) | (Int(bytes[index + 1]) << 8)
            index += 2 + extraLength
        }
        if flags & 0x08 != 0 { // FNAME
            while index < bytes.count, bytes[index] != 0 { index += 1 }
            index += 1
        }
        if flags & 0x10 != 0 { // FCOMMENT
            while index < bytes.count, bytes[index] != 0 { index += 1 }
            index += 1
        }
        if flags & 0x02 != 0 { // FHCRC
            index += 2
        }
        guard index <= bytes.count else { throw GzipError.truncated }

        var inflater = Inflater(bytes: Array(bytes[index...]))
        let inflated = try inflater.inflateAll()

        // Trailer: CRC32 + ISIZE
        let consumed = index + inflater.consumedBytes
        if consumed + 8 <= bytes.count {
            let expectedCRC = UInt32(bytes[consumed])
                | (UInt32(bytes[consumed + 1]) << 8)
                | (UInt32(bytes[consumed + 2]) << 16)
                | (UInt32(bytes[consumed + 3]) << 24)
            if CRC32.checksum(inflated) != expectedCRC {
                throw GzipError.checksumMismatch
            }
        }
        return inflated
    }

    /// Wraps data in a gzip container (stored deflate blocks) — used for export.
    public static func compress(_ data: Data) -> Data {
        var out = Data([0x1f, 0x8b, 0x08, 0x00]) // magic, deflate, no flags
        out.append(contentsOf: [0, 0, 0, 0]) // mtime
        out.append(contentsOf: [0x00, 0x03]) // XFL=0, OS=unix
        out.append(DeflateStore.blocks(data)) // raw deflate blocks
        out.appendLittleEndian(CRC32.checksum(data))
        out.appendLittleEndian(UInt32(data.count & 0xFFFF_FFFF))
        return out
    }
}

/// Stored-only DEFLATE encoding (RFC 1951 block type 00) shared by PNG and gzip.
public enum DeflateStore {
    public static func blocks(_ raw: Data) -> Data {
        var out = Data()
        var index = 0
        let bytes = [UInt8](raw)
        while index < bytes.count {
            let chunkSize = min(65535, bytes.count - index)
            let isFinal: UInt8 = (index + chunkSize >= bytes.count) ? 1 : 0
            out.append(isFinal) // BFINAL=1/0, BTYPE=00 (stored)
            let len = UInt16(chunkSize)
            out.append(UInt8(len & 0xff))
            out.append(UInt8(len >> 8))
            let nlen = ~len
            out.append(UInt8(nlen & 0xff))
            out.append(UInt8(nlen >> 8))
            out.append(contentsOf: bytes[index..<(index + chunkSize)])
            index += chunkSize
        }
        return out
    }
}

extension Data {
    mutating func appendLittleEndian(_ value: UInt32) {
        append(UInt8(value & 0xff))
        append(UInt8((value >> 8) & 0xff))
        append(UInt8((value >> 16) & 0xff))
        append(UInt8((value >> 24) & 0xff))
    }
}

// MARK: - DEFLATE inflater

struct Inflater {
    private let bytes: [UInt8]
    private var bitPosition = 0
    private(set) var consumedBytes = 0

    init(bytes: [UInt8]) {
        self.bytes = bytes
    }

    mutating func inflateAll() throws -> [UInt8] {
        var out: [UInt8] = []
        while true {
            let isFinal = try readBits(1)
            let blockType = try readBits(2)
            switch blockType {
            case 0:
                try inflateStored(&out)
            case 1:
                try inflateHuffman(&out, literal: Self.fixedLiteralTable(), distance: Self.fixedDistanceTable())
            case 2:
                let (literal, distance) = try readDynamicTables()
                try inflateHuffman(&out, literal: literal, distance: distance)
            default:
                throw Gzip.GzipError.invalidDeflate("Reserved block type")
            }
            if isFinal == 1 { break }
        }
        consumedBytes = (bitPosition + 7) / 8
        return out
    }

    // MARK: Blocks

    private mutating func inflateStored(_ out: inout [UInt8]) throws {
        bitPosition = (bitPosition + 7) & ~7 // align to byte
        let byteIndex = bitPosition / 8
        guard byteIndex + 4 <= bytes.count else { throw Gzip.GzipError.truncated }
        let length = Int(bytes[byteIndex]) | (Int(bytes[byteIndex + 1]) << 8)
        let nlen = Int(bytes[byteIndex + 2]) | (Int(bytes[byteIndex + 3]) << 8)
        guard nlen == (~length) & 0xffff else { throw Gzip.GzipError.invalidDeflate("Stored block LEN/NLEN mismatch") }
        let start = byteIndex + 4
        guard start + length <= bytes.count else { throw Gzip.GzipError.truncated }
        out.append(contentsOf: bytes[start..<(start + length)])
        bitPosition = (start + length) * 8
    }

    private mutating func inflateHuffman(
        _ out: inout [UInt8],
        literal: HuffmanDecoder,
        distance: HuffmanDecoder
    ) throws {
        while true {
            let symbol = try literal.decode(&self)
            if symbol < 256 {
                out.append(UInt8(symbol))
            } else if symbol == 256 {
                return
            } else {
                let lengthIndex = symbol - 257
                guard lengthIndex < Self.lengthBase.count else {
                    throw Gzip.GzipError.invalidDeflate("Bad length symbol")
                }
                let length = Self.lengthBase[lengthIndex] + Int(try readBits(UInt64(Self.lengthExtra[lengthIndex])))
                let distSymbol = try distance.decode(&self)
                guard distSymbol < Self.distanceBase.count else {
                    throw Gzip.GzipError.invalidDeflate("Bad distance symbol")
                }
                let dist = Self.distanceBase[distSymbol] + Int(try readBits(UInt64(Self.distanceExtra[distSymbol])))
                guard dist <= out.count else { throw Gzip.GzipError.invalidDeflate("Distance beyond output") }
                let start = out.count - dist
                for offset in 0..<length {
                    out.append(out[start + offset])
                }
            }
        }
    }

    // MARK: Dynamic tables

    private mutating func readDynamicTables() throws -> (HuffmanDecoder, HuffmanDecoder) {
        let hlit = Int(try readBits(5)) + 257
        let hdist = Int(try readBits(5)) + 1
        let hclen = Int(try readBits(4)) + 4

        let order: [Int] = [16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15]
        var codeLengthLengths = [Int](repeating: 0, count: 19)
        for i in 0..<hclen {
            codeLengthLengths[order[i]] = Int(try readBits(3))
        }
        let codeLengthDecoder = try HuffmanDecoder(lengths: codeLengthLengths)

        var lengths = [Int](repeating: 0, count: hlit + hdist)
        var i = 0
        while i < lengths.count {
            let symbol = try codeLengthDecoder.decode(&self)
            switch symbol {
            case 0...15:
                lengths[i] = symbol
                i += 1
            case 16:
                guard i > 0 else { throw Gzip.GzipError.invalidDeflate("Repeat with no previous length") }
                let repeatCount = 3 + Int(try readBits(2))
                let previous = lengths[i - 1]
                guard i + repeatCount <= lengths.count else { throw Gzip.GzipError.invalidDeflate("Repeat overflow") }
                for _ in 0..<repeatCount {
                    lengths[i] = previous
                    i += 1
                }
            case 17:
                let repeatCount = 3 + Int(try readBits(3))
                guard i + repeatCount <= lengths.count else { throw Gzip.GzipError.invalidDeflate("Repeat overflow") }
                i += repeatCount
            case 18:
                let repeatCount = 11 + Int(try readBits(7))
                guard i + repeatCount <= lengths.count else { throw Gzip.GzipError.invalidDeflate("Repeat overflow") }
                i += repeatCount
            default:
                throw Gzip.GzipError.invalidDeflate("Bad code length symbol")
            }
        }

        let literal = try HuffmanDecoder(lengths: Array(lengths[0..<hlit]))
        let distance = try HuffmanDecoder(lengths: Array(lengths[hlit..<(hlit + hdist)]))
        return (literal, distance)
    }

    // MARK: Bit reader (LSB-first)

    mutating func readBits(_ count: UInt64) throws -> UInt64 {
        var result: UInt64 = 0
        for shift in 0..<count {
            let byteIndex = bitPosition / 8
            guard byteIndex < bytes.count else { throw Gzip.GzipError.truncated }
            let bit = (bytes[byteIndex] >> (bitPosition % 8)) & 1
            result |= UInt64(bit) << shift
            bitPosition += 1
        }
        return result
    }

    // MARK: Fixed tables

    static let lengthBase = [
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
    ]
    static let lengthExtra = [
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
    ]
    static let distanceBase = [
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097,
        6145, 8193, 12289, 16385, 24577,
    ]
    static let distanceExtra = [
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
    ]

    static func fixedLiteralTable() throws -> HuffmanDecoder {
        var lengths = [Int](repeating: 8, count: 288)
        for i in 144..<256 { lengths[i] = 9 }
        for i in 256..<280 { lengths[i] = 7 }
        return try HuffmanDecoder(lengths: lengths)
    }

    static func fixedDistanceTable() throws -> HuffmanDecoder {
        HuffmanDecoder(lengths: [Int](repeating: 5, count: 32))
    }
}

// MARK: - Canonical Huffman decoding ("puff"-style)

struct HuffmanDecoder {
    private let counts: [Int]
    private let symbols: [Int]

    init(lengths: [Int]) throws {
        var counts = [Int](repeating: 0, count: 16)
        for length in lengths {
            guard length >= 0, length <= 15 else {
                throw Gzip.GzipError.invalidDeflate("Code length out of range")
            }
            counts[length] += 1
        }
        counts[0] = 0
        self.counts = counts

        var offsets = [Int](repeating: 0, count: 16)
        for length in 1...15 {
            offsets[length] = offsets[length - 1] + counts[length - 1]
        }
        var symbols = [Int](repeating: 0, count: lengths.count)
        for (symbol, length) in lengths.enumerated() where length != 0 {
            symbols[offsets[length]] = symbol
            offsets[length] += 1
        }
        self.symbols = symbols
    }

    func decode(_ inflater: inout Inflater) throws -> Int {
        var code = 0
        var first = 0
        var index = 0
        for length in 1...15 {
            code |= Int(try inflater.readBits(1))
            let count = counts[length]
            if code - first < count {
                return symbols[index + (code - first)]
            }
            index += count
            first = (first + count) << 1
            code <<= 1
        }
        throw Gzip.GzipError.invalidDeflate("Invalid Huffman code")
    }
}
