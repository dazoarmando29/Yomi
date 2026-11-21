import Foundation

/// Codable app preferences (the iOS counterpart of `ReaderPreferences` /
/// `LibraryPreferences`). Persisted as `preferences.json` in the store directory.
public struct AppPreferences: Codable, Sendable, Equatable {
    // Reader
    public var defaultReadingMode: ReadingMode
    public var defaultScaleType: PageScaleType
    public var showPageNumber: Bool
    public var keepScreenOn: Bool
    public var preloadPageCount: Int

    // Library
    public var libraryDisplayMode: LibraryDisplayMode
    public var librarySortMode: LibrarySortMode
    public var sortDescending: Bool

    // Appearance
    public var accentColorName: String

    // Downloads
    public var downloadOnlyOverWiFi: Bool

    // Notifications
    public var notifyDownloadCompletion: Bool

    public init(
        defaultReadingMode: ReadingMode = .webtoon,
        defaultScaleType: PageScaleType = .fitWidth,
        showPageNumber: Bool = true,
        keepScreenOn: Bool = true,
        preloadPageCount: Int = 5,
        libraryDisplayMode: LibraryDisplayMode = .comfortableGrid,
        librarySortMode: LibrarySortMode = .alphabetical,
        sortDescending: Bool = false,
        accentColorName: String = "violet",
        downloadOnlyOverWiFi: Bool = false,
        notifyDownloadCompletion: Bool = true
    ) {
        self.defaultReadingMode = defaultReadingMode
        self.defaultScaleType = defaultScaleType
        self.showPageNumber = showPageNumber
        self.keepScreenOn = keepScreenOn
        self.preloadPageCount = preloadPageCount
        self.libraryDisplayMode = libraryDisplayMode
        self.librarySortMode = librarySortMode
        self.sortDescending = sortDescending
        self.accentColorName = accentColorName
        self.downloadOnlyOverWiFi = downloadOnlyOverWiFi
        self.notifyDownloadCompletion = notifyDownloadCompletion
    }

    public static let `default` = AppPreferences()
}

public enum PageScaleType: String, Codable, Sendable, CaseIterable {
    case fitWidth
    case fitHeight
    case fitScreen
}

/// Persists `AppPreferences` as JSON. Also keeps the last reader position per
/// chapter so resume works across launches.
public actor PreferencesStore {
    public static let preferencesFileName = "preferences.json"
    public static let positionsFileName = "reader_positions.json"

    private let directory: URL
    public private(set) var preferences: AppPreferences
    private var positions: [Int64: ReaderPosition] = [:]

    public init(directory: URL, defaults: AppPreferences = .default) throws {
        self.directory = directory
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

        let prefsURL = directory.appendingPathComponent(Self.preferencesFileName)
        if let data = try? Data(contentsOf: prefsURL) {
            self.preferences = (try? JSONDecoder().decode(AppPreferences.self, from: data)) ?? defaults
        } else {
            self.preferences = defaults
        }

        let positionsURL = directory.appendingPathComponent(Self.positionsFileName)
        if let data = try? Data(contentsOf: positionsURL),
           let decoded = try? JSONDecoder().decode([String: ReaderPosition].self, from: data) {
            for (key, value) in decoded {
                if let id = Int64(key) {
                    positions[id] = value
                }
            }
        }
    }

    public func update(_ transform: (inout AppPreferences) -> Void) throws {
        transform(&preferences)
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(preferences)
        try data.write(to: directory.appendingPathComponent(Self.preferencesFileName), options: [.atomic])
    }

    // MARK: Reader positions

    public func savePosition(_ position: ReaderPosition) throws {
        positions[position.chapterID] = position
        var snapshot: [String: ReaderPosition] = [:]
        for (key, value) in positions {
            snapshot[String(key)] = value
        }
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let data = try encoder.encode(snapshot)
        try data.write(to: directory.appendingPathComponent(Self.positionsFileName), options: [.atomic])
    }

    public func position(chapterID: Int64) -> ReaderPosition? {
        positions[chapterID]
    }

    public func loadPositionsFromDisk() throws {
        let positionsURL = directory.appendingPathComponent(Self.positionsFileName)
        guard let data = try? Data(contentsOf: positionsURL),
              let decoded = try? JSONDecoder().decode([String: ReaderPosition].self, from: data) else { return }
        positions = [:]
        for (key, value) in decoded {
            if let id = Int64(key) {
                positions[id] = value
            }
        }
    }
}
