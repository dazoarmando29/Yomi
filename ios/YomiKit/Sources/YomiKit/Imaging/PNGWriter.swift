import Foundation

// MARK: - CRC32 (PNG chunks + gzip trailer verification)

public enum CRC32 {
    private static let table: [UInt32] = {
        (0..<256).map { index -> UInt32 in
            var c = UInt32(index)
            for _ in 0..<8 {
                c = (c & 1) == 1 ? (0xEDB88320 ^ (c >> 1)) : (c >> 1)
            }
            return c
        }
    }()

    public static func checksum(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in data {
            crc = table[Int((crc ^ UInt32(byte)) & 0xff)] ^ (crc >> 8)
        }
        return crc ^ 0xFFFF_FFFF
    }
}

// MARK: - Adler32 (zlib stream trailer)

public enum Adler32 {
    public static func checksum(_ data: Data) -> UInt32 {
        var a: UInt32 = 1
        var b: UInt32 = 0
        for byte in data {
            a = (a + UInt32(byte)) % 65521
            b = (b + a) % 65521
        }
        return (b << 16) | a
    }
}

// MARK: - PNG writer

/// Minimal, dependency-free PNG encoder (RGBA, 8-bit, non-interlaced,
/// stored-deflate zlib stream). Used for deterministic sample pages and
/// download tests; on iOS the same data flows through the reader pipeline.
public enum PNGWriter {
    static let signature: [UInt8] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]

    /// Encodes RGBA8 pixels (4 bytes/pixel, row-major, top-down) into PNG data.
    public static func encode(width: Int, height: Int, rgba: [UInt8]) -> Data {
        precondition(width > 0 && height > 0, "PNG dimensions must be positive")
        precondition(rgba.count == width * height * 4, "RGBA buffer size mismatch")

        var out = Data(signature)

        // IHDR
        var ihdr = Data()
        ihdr.appendBigEndian(UInt32(width))
        ihdr.appendBigEndian(UInt32(height))
        ihdr.append(contentsOf: [8, 6, 0, 0, 0]) // bit depth, RGBA, deflate, adaptive, no interlace
        out.append(chunk(type: "IHDR", payload: ihdr))

        // Raw scanlines with filter byte 0
        var raw = Data()
        raw.reserveCapacity(height * (1 + width * 4))
        for row in 0..<height {
            raw.append(0)
            let start = row * width * 4
            raw.append(contentsOf: rgba[start..<start + width * 4])
        }

        out.append(chunk(type: "IDAT", payload: zlibStore(raw)))
        out.append(chunk(type: "IEND", payload: Data()))
        return out
    }

    /// Wraps data in a zlib stream using only stored (uncompressed) deflate blocks.
    static func zlibStore(_ raw: Data) -> Data {
        var out = Data([0x78, 0x01]) // deflate, 32K window, no preset dict, fastest
        out.append(DeflateStore.blocks(raw))
        out.appendBigEndian(Adler32.checksum(raw))
        return out
    }

    static func chunk(type: String, payload: Data) -> Data {
        var out = Data()
        out.appendBigEndian(UInt32(payload.count))
        let typeData = Data(type.utf8)
        out.append(typeData)
        out.append(payload)
        var crcInput = typeData
        crcInput.append(payload)
        out.appendBigEndian(CRC32.checksum(crcInput))
        return out
    }
}

extension Data {
    mutating func appendBigEndian(_ value: UInt32) {
        append(UInt8((value >> 24) & 0xff))
        append(UInt8((value >> 16) & 0xff))
        append(UInt8((value >> 8) & 0xff))
        append(UInt8(value & 0xff))
    }
}
