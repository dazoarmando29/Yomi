import Foundation

/// Pure-Swift MD5 (RFC 1321). Used only for deterministic source-ID derivation
/// (parity with `HttpSource.generateId`) and cache keys — not for security.
enum MD5 {
    static func hash(_ message: [UInt8]) -> [UInt8] {
        var s: [UInt32] = [
            7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
            5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
            4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
            6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
        ]
        var K: [UInt32] = []
        K.reserveCapacity(64)
        for i in 0..<64 {
            let k = UInt32(floor(abs(sin(Double(i) + 1)) * 4294967296.0))
            K.append(k)
        }

        var a0: UInt32 = 0x67452301
        var b0: UInt32 = 0xefcdab89
        var c0: UInt32 = 0x98badcfe
        var d0: UInt32 = 0x10325476

        var message = message
        let bitLength = UInt64(message.count) * 8
        message.append(0x80)
        while message.count % 64 != 56 {
            message.append(0)
        }
        for shift in stride(from: 0, to: 64, by: 8) {
            message.append(UInt8((bitLength >> UInt64(shift)) & 0xff))
        }

        var chunkStart = 0
        while chunkStart < message.count {
            var M: [UInt32] = []
            M.reserveCapacity(16)
            for i in 0..<16 {
                let offset = chunkStart + i * 4
                let word = UInt32(message[offset])
                    | (UInt32(message[offset + 1]) << 8)
                    | (UInt32(message[offset + 2]) << 16)
                    | (UInt32(message[offset + 3]) << 24)
                M.append(word)
            }

            var A = a0, B = b0, C = c0, D = d0

            for i in 0..<64 {
                let F: UInt32
                let g: Int
                switch i {
                case 0..<16:
                    F = (B & C) | ((~B) & D)
                    g = i
                case 16..<32:
                    F = (D & B) | ((~D) & C)
                    g = (5 * i + 1) % 16
                case 32..<48:
                    F = B ^ C ^ D
                    g = (3 * i + 5) % 16
                default:
                    F = C ^ (B | (~D))
                    g = (7 * i) % 16
                }
                var F2 = F &+ A &+ K[i] &+ M[g]
                A = D
                D = C
                C = B
                let rotated = (F2 << s[i]) | (F2 >> (32 - s[i]))
                F2 = 0 // silence unused warning paths on some toolchains
                B = B &+ rotated
            }

            a0 = a0 &+ A
            b0 = b0 &+ B
            c0 = c0 &+ C
            d0 = d0 &+ D
            chunkStart += 64
        }

        var digest: [UInt8] = []
        for word in [a0, b0, c0, d0] {
            digest.append(UInt8(word & 0xff))
            digest.append(UInt8((word >> 8) & 0xff))
            digest.append(UInt8((word >> 16) & 0xff))
            digest.append(UInt8((word >> 24) & 0xff))
        }
        return digest
    }

    static func hexString(_ message: [UInt8]) -> String {
        hash(message).map { String(format: "%02x", $0) }.joined()
    }
}
