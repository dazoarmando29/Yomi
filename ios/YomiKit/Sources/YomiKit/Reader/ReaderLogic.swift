import Foundation

// MARK: - Chapter number recognition

/// Port of `tachiyomi.domain.chapter.service.ChapterRecognition` (subset):
/// extracts volume/chapter numbers from chapter names so ordering and
/// "next chapter" logic behave the same as on Android.
public enum ChapterRecognition {
    /// Parses the chapter number from a chapter name, e.g. "Ch. 12.5" -> 12.5.
    /// Returns -1 when nothing can be recognized (Android semantics).
    public static func parseChapterNumber(from name: String) -> Double {
        let lowered = name.lowercased()

        // Prologue/one-shot/pilot -> 0
        if regex(#"\b(prologue|one.?shot|pilot)\b"#).firstMatch(in: lowered, range: full(lowered)) != nil {
            return 0
        }

        // "ch." prefix form: "Mokushiroku Alice Vol.1 Ch. 4: Misrepresentation" -> 4
        if let match = regex(#"(?<=ch\.) *([0-9]+)(\.[0-9]+)?"#).firstMatch(in: lowered, range: full(lowered)),
           let number = Double(nsString(lowered).substring(with: match.range(at: 1))
                + (match.range(at: 2).location != NSNotFound ? nsString(lowered).substring(with: match.range(at: 2)) : "")) {
            return number
        }

        // "#12" style
        if let match = regex(#"#"#).firstMatch(in: lowered, range: full(lowered)) {
            let tail = nsString(lowered).substring(from: match.range.location)
            if let number = firstNumber(in: tail) { return number }
        }

        // First number in the name (after removing "ver/version" noise)
        let cleaned = lowered.replacingOccurrences(
            of: #"\b(?:ver|version)[^a-z]?[0-9]+"#,
            with: "",
            options: .regularExpression
        )
        if let number = firstNumber(in: cleaned) {
            return number
        }
        return -1
    }

    /// Fills in chapter numbers for a whole list: entries that failed single-name
    /// parsing inherit neighbours' values like the Android implementation.
    public static func parseChapterNumbers(_ chapters: [(name: String, parsed: Double)]) -> [Double] {
        var results = chapters.map(\.parsed)
        var lastKnown: Double = -1
        for index in results.indices {
            if results[index] >= 0 {
                lastKnown = results[index]
            } else if lastKnown >= 0 {
                results[index] = lastKnown + 1
            }
        }
        return results
    }

    private static func firstNumber(in text: String) -> Double? {
        guard let match = regex(#"[0-9]+(\.[0-9]+)?"#).firstMatch(in: text, range: full(text)) else {
            return nil
        }
        return Double(nsString(text).substring(with: match.range))
    }

    private static func full(_ text: String) -> NSRange {
        NSRange(location: 0, length: nsString(text).length)
    }

    private static func nsString(_ text: String) -> NSString {
        text as NSString
    }

    private static var cache: [String: NSRegularExpression] = [:]
    private static let lock = NSLock()

    private static func regex(_ pattern: String) -> NSRegularExpression {
        lock.lock()
        defer { lock.unlock() }
        if let cached = cache[pattern] { return cached }
        let compiled = try! NSRegularExpression(pattern: pattern)
        cache[pattern] = compiled
        return compiled
    }
}

// MARK: - Chapter ordering

public enum ChapterSort {
    /// Android-compatible display ordering. `descending` reverses the result.
    public static func sorted(_ chapters: [Chapter], by mode: ChapterSortMode, descending: Bool) -> [Chapter] {
        let base: [Chapter]
        switch mode {
        case .sourceOrder:
            base = chapters.sorted {
                ($0.sourceOrder, $0.name) < ($1.sourceOrder, $1.name)
            }
        case .chapterNumber:
            base = chapters.sorted {
                let lhs = $0.chapterNumber >= 0 ? $0.chapterNumber : ChapterRecognition.parseChapterNumber(from: $0.name)
                let rhs = $1.chapterNumber >= 0 ? $1.chapterNumber : ChapterRecognition.parseChapterNumber(from: $1.name)
                if lhs != rhs { return lhs < rhs }
                return $0.sourceOrder < $1.sourceOrder
            }
        case .dateUploaded:
            base = chapters.sorted {
                let lhs = $0.dateUpload ?? .distantPast
                let rhs = $1.dateUpload ?? .distantPast
                if lhs != rhs { return lhs < rhs }
                return $0.sourceOrder < $1.sourceOrder
            }
        case .alphabetical:
            base = chapters.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        }
        return descending ? base.reversed() : base
    }

    /// The chapter after `chapter` in reading order (source order), or nil.
    /// Chapters are matched by URL (their stable identity), falling back to id.
    public static func nextChapter(after chapter: Chapter, in chapters: [Chapter]) -> Chapter? {
        let ordered = sorted(chapters, by: .sourceOrder, descending: false)
        guard let index = ordered.firstIndex(where: { $0.url == chapter.url }) else {
            return nil
        }
        let next = ordered.index(after: index)
        return next < ordered.endIndex ? ordered[next] : nil
    }

    /// The chapter before `chapter` in reading order, or nil.
    public static func previousChapter(before chapter: Chapter, in chapters: [Chapter]) -> Chapter? {
        let ordered = sorted(chapters, by: .sourceOrder, descending: false)
        guard let index = ordered.firstIndex(where: { $0.url == chapter.url }) else {
            return nil
        }
        let previous = ordered.index(before: index)
        return previous >= ordered.startIndex ? ordered[previous] : nil
    }
}

public enum ChapterSortMode: String, Codable, Sendable, CaseIterable {
    case sourceOrder
    case chapterNumber
    case dateUploaded
    case alphabetical
}

// MARK: - Reader position

/// Reader position tracking — the iOS counterpart of `user_last_page_read`
/// plus in-session progress. Used to resume reliably after relaunch.
public struct ReaderPosition: Codable, Sendable, Equatable {
    public var mangaID: Int64
    public var chapterID: Int64
    public var pageIndex: Int
    public var pageCount: Int
    public var updatedAt: Date

    public init(mangaID: Int64, chapterID: Int64, pageIndex: Int, pageCount: Int, updatedAt: Date = Date()) {
        self.mangaID = mangaID
        self.chapterID = chapterID
        self.pageIndex = pageIndex
        self.pageCount = pageCount
        self.updatedAt = updatedAt
    }

    public var isCompleted: Bool {
        pageCount > 0 && pageIndex >= pageCount - 1
    }

    /// Progress in 0...1 for UI indicators.
    public var fraction: Double {
        guard pageCount > 0 else { return 0 }
        return min(1, max(0, Double(pageIndex + 1) / Double(pageCount)))
    }
}

/// Computes page transitions between chapters (end-of-chapter "next" flow).
public enum ReaderNavigator {
    public enum Transition: Equatable, Sendable {
        case page(Int)
        case endOfChapter(next: Chapter?)
        case startOfChapter(previous: Chapter?)
        case none
    }

    public static func advance(pageIndex: Int, pageCount: Int, chapter: Chapter, chapters: [Chapter]) -> Transition {
        if pageIndex + 1 < pageCount {
            return .page(pageIndex + 1)
        }
        return .endOfChapter(next: ChapterSort.nextChapter(after: chapter, in: chapters))
    }

    public static func rewind(pageIndex: Int, chapter: Chapter, chapters: [Chapter]) -> Transition {
        if pageIndex > 0 {
            return .page(pageIndex - 1)
        }
        return .startOfChapter(previous: ChapterSort.previousChapter(before: chapter, in: chapters))
    }
}
