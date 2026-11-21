import Foundation

/// Library search supporting a practical subset of the Android `LibrarySearchAst`
/// query language: whitespace-separated terms, quoted phrases, `-` negation and
/// `field:value` prefixes (`title:`, `author:`, `artist:`, `genre:`, `description:`).
public struct LibrarySearch {
    public enum Token: Equatable, Sendable {
        case text(String, negated: Bool)
        case field(name: String, value: String, negated: Bool)
    }

    public let tokens: [Token]

    public init(query: String) {
        self.tokens = Self.tokenize(query)
    }

    /// Simple "matches all tokens" filtering.
    public func matches(_ manga: Manga) -> Bool {
        tokens.allSatisfy { token in
            switch token {
            case .text(let value, let negated):
                return Self.textMatches(value, in: manga) != negated
            case .field(let name, let value, let negated):
                return Self.fieldMatches(name: name, value: value, in: manga) != negated
            }
        }
    }

    public func filter(_ mangas: [Manga]) -> [Manga] {
        mangas.filter { matches($0) }
    }

    // MARK: - Parsing

    static func tokenize(_ query: String) -> [Token] {
        var tokens: [Token] = []
        var current = ""
        var inQuotes = false
        var negated = false
        var field: String? = nil

        func flush() {
            defer {
                current = ""
                negated = false
                field = nil
            }
            let value = current.trimmingCharacters(in: .whitespaces)
            guard !value.isEmpty else { return }
            if let field {
                tokens.append(.field(name: field, value: value, negated: negated))
            } else {
                tokens.append(.text(value, negated: negated))
            }
        }

        var iterator = query.makeIterator()
        while let char = iterator.next() {
            switch char {
            case "\"":
                inQuotes.toggle()
            case "-" where !inQuotes && current.isEmpty && field == nil:
                negated = true
            case ":" where !inQuotes && field == nil && !current.isEmpty:
                field = current.lowercased()
                current = ""
            case " " where !inQuotes:
                flush()
            default:
                current.append(char)
            }
        }
        flush()
        return tokens
    }

    private static func textMatches(_ value: String, in manga: Manga) -> Bool {
        fieldMatches(name: "all", value: value, in: manga)
    }

    private static func fieldMatches(name: String, value: String, in manga: Manga) -> Bool {
        let needle = value.lowercased()
        switch name {
        case "title":
            return manga.title.lowercased().contains(needle)
        case "author":
            return (manga.author ?? "").lowercased().contains(needle)
        case "artist":
            return (manga.artist ?? "").lowercased().contains(needle)
        case "genre":
            return manga.genres.contains { $0.lowercased().contains(needle) }
        case "description":
            return (manga.mangaDescription ?? "").lowercased().contains(needle)
        default:
            return manga.title.lowercased().contains(needle)
                || (manga.author ?? "").lowercased().contains(needle)
                || (manga.artist ?? "").lowercased().contains(needle)
                || (manga.mangaDescription ?? "").lowercased().contains(needle)
                || manga.genres.contains { $0.lowercased().contains(needle) }
        }
    }
}
