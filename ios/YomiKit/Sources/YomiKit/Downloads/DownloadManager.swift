import Foundation

// MARK: - Model

public enum DownloadStatus: Codable, Sendable, Equatable {
    case queued
    case downloading(page: Int, total: Int)
    case completed
    case failed(message: String)
    case cancelled

    public var isFinished: Bool {
        switch self {
        case .completed, .cancelled: return true
        case .failed: return true
        default: return false
        }
    }
}

public struct DownloadItem: Identifiable, Codable, Sendable, Equatable {
    public var id: String { "\(mangaID)-\(chapterID)" }
    public var mangaID: Int64
    public var chapterID: Int64
    public var mangaTitle: String
    public var chapterName: String
    public var status: DownloadStatus
    public var totalPages: Int

    public init(
        mangaID: Int64,
        chapterID: Int64,
        mangaTitle: String,
        chapterName: String,
        status: DownloadStatus = .queued,
        totalPages: Int = 0
    ) {
        self.mangaID = mangaID
        self.chapterID = chapterID
        self.mangaTitle = mangaTitle
        self.chapterName = chapterName
        self.status = status
        self.totalPages = totalPages
    }
}

// MARK: - Manager

/// Download queue with retry/cancel, offline storage layout and cleanup.
/// Chapters are stored as page PNGs under
/// `<downloads>/<sourceID>/<manga title>/<chapter name>/page_%04d.png`
/// (mirroring the Android download layout). The queue persists across launches;
/// interrupted items are re-queued on startup.
public actor DownloadManager {
    public static let queueFileName = "downloads.json"

    public let downloadsDirectory: URL
    private let queueFileURL: URL
    private let library: LibraryStore
    private let registry: SourceRegistry

    private var items: [DownloadItem] = []
    private var activeTask: Task<Void, Never>?
    private var running = false

    /// Broadcast of queue snapshots for UI updates.
    private var subscribers: [UUID: AsyncStream<[DownloadItem]>.Continuation] = [:]

    public init(
        baseDirectory: URL,
        library: LibraryStore,
        registry: SourceRegistry
    ) throws {
        self.downloadsDirectory = baseDirectory.appendingPathComponent("downloads", isDirectory: true)
        self.queueFileURL = baseDirectory.appendingPathComponent(Self.queueFileName)
        self.library = library
        self.registry = registry
        try FileManager.default.createDirectory(at: downloadsDirectory, withIntermediateDirectories: true)

        if let data = try? Data(contentsOf: queueFileURL),
           let decoded = try? JSONDecoder().decode([DownloadItem].self, from: data) {
            // Interrupted downloads return to the queue; finished states persist.
            items = decoded.map { item in
                var item = item
                if case .downloading = item.status {
                    item.status = .queued
                }
                return item
            }
        }
    }

    // MARK: - Subscription

    public func subscribe() -> AsyncStream<[DownloadItem]> {
        let id = UUID()
        return AsyncStream { continuation in
            subscribers[id] = continuation
            continuation.yield(items)
            continuation.onTermination = { [weak self] _ in
                Task { await self?.removeSubscriber(id) }
            }
        }
    }

    private func removeSubscriber(_ id: UUID) {
        subscribers[id] = nil
    }

    private func broadcast() {
        let snapshot = items
        for continuation in subscribers.values {
            continuation.yield(snapshot)
        }
    }

    // MARK: - Queue control

    /// Enqueues chapters for download (deduplicates pending/finished entries).
    public func enqueue(mangaID: Int64, chapterIDs: [Int64]) async throws {
        guard let manga = await library.manga(id: mangaID) else {
            throw SourceError.notFound
        }
        for chapterID in chapterIDs {
            guard let chapter = await library.chapter(id: chapterID) else { continue }
            if let existing = items.first(where: { $0.chapterID == chapterID }) {
                switch existing.status {
                case .completed, .downloading, .queued:
                    continue
                case .failed, .cancelled:
                    // Replace the finished attempt with a fresh queue entry.
                    items.removeAll { $0.chapterID == chapterID }
                }
            }
            items.append(
                DownloadItem(
                    mangaID: mangaID,
                    chapterID: chapterID,
                    mangaTitle: manga.title,
                    chapterName: chapter.name
                )
            )
        }
        try await persist()
        broadcast()
        pumpQueue()
    }

    public func retry(id: String) {
        guard let index = items.firstIndex(where: { $0.id == id }) else { return }
        if case .failed = items[index].status {
            items[index].status = .queued
        } else if case .cancelled = items[index].status {
            items[index].status = .queued
        }
        broadcast()
        pumpQueue()
    }

    public func retryAllFailed() {
        for index in items.indices {
            if case .failed = items[index].status {
                items[index].status = .queued
            }
        }
        broadcast()
        pumpQueue()
    }

    public func cancel(id: String) {
        if let index = items.firstIndex(where: { $0.id == id }) {
            switch items[index].status {
            case .completed:
                break
            default:
                items[index].status = .cancelled
            }
        }
        broadcast()
        Task { try? await persist() }
    }

    public func clearFinished() {
        items.removeAll { $0.status.isFinished }
        broadcast()
        Task { try? await persist() }
    }

    public func snapshot() -> [DownloadItem] {
        items
    }

    // MARK: - Worker

    private func pumpQueue() {
        guard !running else { return }
        guard items.contains(where: {
            if case .queued = $0.status { return true }
            return false
        }) else { return }
        running = true
        activeTask = Task {
            await processQueue()
            await finishRun()
        }
    }

    private func finishRun() {
        running = false
    }

    private func processQueue() async {
        while let index = items.firstIndex(where: {
            if case .queued = $0.status { return true }
            return false
        }) {
            let item = items[index]
            await process(item: item, at: index)
        }
    }

    private func process(item: DownloadItem, at index: Int) async {
        guard let manga = await library.manga(id: item.mangaID),
              let chapter = await library.chapter(id: item.chapterID) else {
            updateStatus(of: item.id, to: .failed(message: "Missing manga or chapter"))
            return
        }
        guard let provider = await registry.provider(id: manga.sourceID) else {
            updateStatus(of: item.id, to: .failed(message: "Source unavailable on iOS (missing source)"))
            return
        }

        do {
            let pages = try await provider.pageList(manga, chapter: chapter)
            guard !pages.isEmpty else {
                updateStatus(of: item.id, to: .failed(message: "Chapter has no pages"))
                return
            }
            updateStatus(of: item.id, to: .downloading(page: 0, total: pages.count))

            let chapterDir = chapterDirectory(manga: manga, chapter: chapter)
            try FileManager.default.createDirectory(at: chapterDir, withIntermediateDirectories: true)

            for page in pages {
                // Cancellation is checked per page
                if let current = items.first(where: { $0.id == item.id }),
                   case .cancelled = current.status {
                    return
                }
                let data = try await fetchPage(page)
                let fileURL = chapterDir.appendingPathComponent(
                    String(format: "page_%04d.png", page.index + 1)
                )
                try data.write(to: fileURL, options: [.atomic])
                updateStatus(of: item.id, to: .downloading(page: page.index + 1, total: pages.count))
            }

            updateStatus(of: item.id, to: .completed)
        } catch let error as SourceError {
            if case .cancelled = error {
                updateStatus(of: item.id, to: .cancelled)
            } else {
                updateStatus(of: item.id, to: .failed(message: error.message))
            }
        } catch {
            updateStatus(of: item.id, to: .failed(message: error.localizedDescription))
        }
        try? await persist()
    }

    private func fetchPage(_ page: SourcePage) async throws -> Data {
        if page.imageURL.hasPrefix("sample://") {
            guard let data = SamplePageRenderer.renderPNG(urlString: page.imageURL) else {
                throw SourceError.notFound
            }
            return data
        }
        guard let url = URL(string: page.imageURL) else {
            throw SourceError.parse("Invalid page URL")
        }
        var request = URLRequest(url: url)
        for (name, value) in page.headers {
            request.setValue(value, forHTTPHeaderField: name)
        }
        let (data, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw SourceError.network("HTTP \(http.statusCode)")
        }
        return data
    }

    private func updateStatus(of id: String, to status: DownloadStatus) {
        guard let index = items.firstIndex(where: { $0.id == id }) else { return }
        items[index].status = status
        broadcast()
    }

    private func persist() throws {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let data = try encoder.encode(items)
        try data.write(to: queueFileURL, options: [.atomic])
    }

    // MARK: - Storage layout

    /// `<downloads>/<sourceID>/<manga title>/<chapter name>/`
    public func chapterDirectory(manga: Manga, chapter: Chapter) -> URL {
        downloadsDirectory
            .appendingPathComponent(String(manga.sourceID.rawValue), isDirectory: true)
            .appendingPathComponent(Self.sanitize(manga.title), isDirectory: true)
            .appendingPathComponent(Self.sanitize(chapter.name), isDirectory: true)
    }

    static func sanitize(_ name: String) -> String {
        var invalid = CharacterSet(charactersIn: "/\\:*?\"<>|")
        invalid.formUnion(.newlines)
        let cleaned = name.components(separatedBy: invalid).joined(separator: "_")
        let trimmed = cleaned.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "untitled" : trimmed
    }

    // MARK: - Offline reading

    /// Page file URLs of a completed download, or nil when not fully downloaded.
    public func downloadedPages(manga: Manga, chapter: Chapter) -> [URL]? {
        let dir = chapterDirectory(manga: manga, chapter: chapter)
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: dir,
            includingPropertiesForKeys: nil
        ) else { return nil }
        let pages = files
            .filter { $0.lastPathComponent.hasPrefix("page_") && $0.pathExtension == "png" }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        guard !pages.isEmpty else { return nil }
        return pages
    }

    public func isDownloaded(manga: Manga, chapter: Chapter) -> Bool {
        downloadedPages(manga: manga, chapter: chapter) != nil
    }

    // MARK: - Storage usage / cleanup

    public func storageUsage() -> Int64 {
        let files = (try? FileManager.default.subpathsOfDirectory(atPath: downloadsDirectory.path)) ?? []
        var total: Int64 = 0
        for file in files {
            let path = downloadsDirectory.appendingPathComponent(file).path
            let attributes = try? FileManager.default.attributesOfItem(atPath: path)
            total += (attributes?[.size] as? Int64) ?? 0
        }
        return total
    }

    public func removeDownload(manga: Manga, chapter: Chapter) throws {
        let dir = chapterDirectory(manga: manga, chapter: chapter)
        if FileManager.default.fileExists(atPath: dir.path) {
            try FileManager.default.removeItem(at: dir)
        }
        items.removeAll { $0.mangaID == manga.id && $0.chapterID == chapter.id }
        try persist()
        broadcast()
    }

    public func clearAllDownloads() throws {
        if FileManager.default.fileExists(atPath: downloadsDirectory.path) {
            try FileManager.default.removeItem(at: downloadsDirectory)
        }
        try FileManager.default.createDirectory(at: downloadsDirectory, withIntermediateDirectories: true)
        items.removeAll()
        try persist()
        broadcast()
    }
}
