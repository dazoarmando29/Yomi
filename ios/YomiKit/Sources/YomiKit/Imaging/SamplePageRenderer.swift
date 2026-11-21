import Foundation

/// Renders deterministic, original page artwork for the `sample://` scheme.
/// Everything is procedurally generated from the URL — no external assets.
public enum SamplePageRenderer {
    /// `sample://page/<mangaKey>/<chapter>/<page>.png` -> page PNG
    /// `sample://cover/<mangaKey>.png` -> cover PNG
    public static func renderPNG(urlString: String, width: Int = 720, height: Int = 1080) -> Data? {
        guard urlString.hasPrefix("sample://") else { return nil }
        let path = urlString.dropFirst("sample://".count)

        if path.hasPrefix("cover/") {
            let key = String(path.dropFirst("cover/".count)).replacingOccurrences(of: ".png", with: "")
            return renderPage(seedKey: "cover/\(key)", pageIndex: 0, width: width, height: height, isCover: true)
        }

        if path.hasPrefix("page/") {
            let parts = String(path.dropFirst("page/".count))
                .replacingOccurrences(of: ".png", with: "")
                .split(separator: "/")
            guard parts.count == 3,
                  let chapter = Int(parts[1]),
                  let page = Int(parts[2]) else { return nil }
            return renderPage(seedKey: String(parts[0]), pageIndex: chapter * 1000 + page, width: width, height: height)
        }
        return nil
    }

    /// Deterministic RGBA rendering: gradient background seeded by the key,
    /// geometric accents, and a blocky page counter. Stable across runs/devices.
    public static func renderPage(seedKey: String, pageIndex: Int, width: Int, height: Int, isCover: Bool = false) -> Data {
        var pixels = [UInt8](repeating: 0, count: width * height * 4)

        var seed: UInt64 = 1469598103934665603 // FNV offset basis
        for byte in seedKey.utf8 {
            seed = (seed ^ UInt64(byte)) &* 1099511628211
        }
        seed = (seed ^ UInt64(pageIndex)) &* 1099511628211

        func lcg(_ state: inout UInt64) -> Double {
            state = state &* 6364136223846793005 &+ 1442695040888963407
            return Double((state >> 33) & 0xFFFFFF) / Double(0xFFFFFF)
        }

        var rng = seed
        let baseHue = lcg(&rng)
        let accentHue = (baseHue + 0.35).truncatingRemainder(dividingBy: 1.0)

        // Vertical gradient
        for y in 0..<height {
            let t = Double(y) / Double(height)
            let r = UInt8(18 + 40 * t * (0.4 + 0.6 * baseHue))
            let g = UInt8(19 + 30 * t * (0.5 + 0.5 * accentHue))
            let b = UInt8(30 + 55 * t * (0.6 + 0.4 * baseHue))
            for x in 0..<width {
                let offset = (y * width + x) * 4
                pixels[offset] = r
                pixels[offset + 1] = g
                pixels[offset + 2] = b
                pixels[offset + 3] = 255
            }
        }

        // Accent shapes
        let shapeCount = 3 + Int(seed % 3)
        for shape in 0..<shapeCount {
            let cx = Int(lcg(&rng) * Double(width))
            let cy = Int(lcg(&rng) * Double(height))
            let radius = Int(40 + lcg(&rng) * Double(min(width, height)) * 0.18)
            let ar = UInt8(140 + lcg(&rng) * 100)
            let ag = UInt8(90 + lcg(&rng) * 80)
            let ab = UInt8(220 + lcg(&rng) * 35)
            let alpha = 90 + UInt8(lcg(&rng) * 60)
            fillCircle(&pixels, width: width, height: height, cx: cx, cy: cy, radius: radius,
                       color: (ar, ag, ab), alpha: alpha, blend: shape.isMultiple(of: 2))
        }

        // Page/cover counter in blocky digits (bottom center)
        let label = isCover ? 1000 + Int(seed % 900) : pageIndex % 1000
        drawNumber(label, &pixels, width: width, height: height)

        // Frame border
        let border: (UInt8, UInt8, UInt8) = (208, 188, 255) // #D0BCFF accent
        for x in 0..<width {
            setPixel(&pixels, width: width, x: x, y: 0, color: border, alpha: 200)
            setPixel(&pixels, width: width, x: x, y: height - 1, color: border, alpha: 200)
        }
        for y in 0..<height {
            setPixel(&pixels, width: width, x: 0, y: y, color: border, alpha: 200)
            setPixel(&pixels, width: width, x: width - 1, y: y, color: border, alpha: 200)
        }

        return PNGWriter.encode(width: width, height: height, rgba: pixels)
    }

    // MARK: - Primitives

    private static func setPixel(
        _ pixels: inout [UInt8], width: Int, x: Int, y: Int,
        color: (UInt8, UInt8, UInt8), alpha: UInt8
    ) {
        let offset = (y * width + x) * 4
        pixels[offset] = color.0
        pixels[offset + 1] = color.1
        pixels[offset + 2] = color.2
        pixels[offset + 3] = alpha
    }

    private static func fillCircle(
        _ pixels: inout [UInt8], width: Int, height: Int,
        cx: Int, cy: Int, radius: Int,
        color: (UInt8, UInt8, UInt8), alpha: UInt8, blend: Bool
    ) {
        let r2 = radius * radius
        for y in max(0, cy - radius)..<min(height, cy + radius + 1) {
            for x in max(0, cx - radius)..<min(width, cx + radius + 1) {
                let dx = x - cx, dy = y - cy
                if dx * dx + dy * dy <= r2 {
                    let offset = (y * width + x) * 4
                    if blend {
                        pixels[offset] = UInt8((Int(pixels[offset]) + Int(color.0)) / 2)
                        pixels[offset + 1] = UInt8((Int(pixels[offset + 1]) + Int(color.1)) / 2)
                        pixels[offset + 2] = UInt8((Int(pixels[offset + 2]) + Int(color.2)) / 2)
                    } else {
                        pixels[offset] = color.0
                        pixels[offset + 1] = color.1
                        pixels[offset + 2] = color.2
                        pixels[offset + 3] = alpha
                    }
                }
            }
        }
    }

    /// Draws a number using 14-segment-ish blocky digits so pages are identifiable in tests.
    private static func drawNumber(_ value: Int, _ pixels: inout [UInt8], width: Int, height: Int) {
        let digits = String(format: "%03d", value).map { Int($0.wholeNumberValue ?? 0) }
        let scale = max(4, width / 24)
        let digitWidth = scale * 6
        let digitHeight = scale * 10
        let totalWidth = digits.count * digitWidth + (digits.count - 1) * scale
        let originX = (width - totalWidth) / 2
        let originY = height - digitHeight - scale * 4

        for (index, digit) in digits.enumerated() {
            drawDigit(digit, &pixels, width: width,
                      x: originX + index * (digitWidth + scale), y: originY, scale: scale)
        }
    }

    private static func drawDigit(_ digit: Int, _ pixels: inout [UInt8], width: Int, x: Int, y: Int, scale: Int) {
        // Segments: a b c d e f g (classic 7-seg)
        let segments: [Int: Set<Character>] = [
            0: ["a", "b", "c", "d", "e", "f"],
            1: ["b", "c"],
            2: ["a", "b", "g", "e", "d"],
            3: ["a", "b", "g", "c", "d"],
            4: ["f", "g", "b", "c"],
            5: ["a", "f", "g", "c", "d"],
            6: ["a", "f", "g", "e", "c", "d"],
            7: ["a", "b", "c"],
            8: ["a", "b", "c", "d", "e", "f", "g"],
            9: ["a", "b", "c", "d", "f", "g"],
        ]
        let on = segments[digit] ?? []
        let color: (UInt8, UInt8, UInt8) = (230, 225, 245)

        func bar(_ x0: Int, _ y0: Int, _ w: Int, _ h: Int) {
            for yy in y0..<(y0 + h) {
                for xx in x0..<(x0 + w) {
                    setPixel(&pixels, width: width, x: xx, y: yy, color: color, alpha: 255)
                }
            }
        }

        let t = scale // thickness
        if on.contains("a") { bar(x + t, y, scale * 4, t) }
        if on.contains("g") { bar(x + t, y + scale * 5 - t / 2, scale * 4, t) }
        if on.contains("d") { bar(x + t, y + scale * 10 - t, scale * 4, t) }
        if on.contains("f") { bar(x, y + t, t, scale * 4 - t) }
        if on.contains("b") { bar(x + scale * 5, y + t, t, scale * 4 - t) }
        if on.contains("e") { bar(x, y + scale * 5, t, scale * 5 - t) }
        if on.contains("c") { bar(x + scale * 5, y + scale * 5, t, scale * 5 - t) }
    }
}
