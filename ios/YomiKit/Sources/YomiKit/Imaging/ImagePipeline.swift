import Foundation

/// Memory + disk image cache with async loading. Handles three URL kinds:
/// - `sample://…`  rendered deterministically by `SamplePageRenderer`
/// - `file://…`    read from disk
/// - `http(s)://…` fetched with `URLSession` (respecting per-source headers)
///
/// Disk cache: `directory/<md5(url)>.img`, LRU-evicted by size cap.
public actor ImagePipeline {
    public let directory: URL
    public let diskLimit: Int64
    private let memoryCache = NSCache<NSString, NSData>()
    private let session: URLSession

    public init(directory: URL, diskLimit: Int64 = 256 * 1024 * 1024, session: URLSession = .shared) {
        self.directory = directory
        self.diskLimit = diskLimit
        self.session = session
        memoryCache.countLimit = 120
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    // MARK: - Loading

    public func imageData(for urlString: String, headers: [String: String] = [:]) async throws -> Data {
        let key = Self.cacheKey(for: urlString)
        if let cached = memoryCache.object(forKey: key as NSString) {
            return cached as Data
        }

        let diskURL = directory.appendingPathComponent(key + ".img")
        if let data = try? Data(contentsOf: diskURL) {
            memoryCache.setObject(data as NSData, forKey: key as NSString)
            return data
        }

        let data: Data
        if urlString.hasPrefix("sample://") {
            guard let rendered = SamplePageRenderer.renderPNG(urlString: urlString) else {
                throw SourceError.notFound
            }
            data = rendered
        } else if urlString.hasPrefix("file://"), let url = URL(string: urlString) {
            data = try Data(contentsOf: url)
        } else if let url = URL(string: urlString), url.scheme == "http" || url.scheme == "https" {
            var request = URLRequest(url: url)
            for (name, value) in headers {
                request.setValue(value, forHTTPHeaderField: name)
            }
            let (fetched, response) = try await session.data(for: request)
            if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                throw SourceError.network("HTTP \(http.statusCode)")
            }
            data = fetched
        } else {
            throw SourceError.unsupported("Unhandled URL scheme: \(urlString)")
        }

        memoryCache.setObject(data as NSData, forKey: key as NSString)
        try? data.write(to: diskURL, options: [.atomic])
        await evictDiskIfNeeded()
        return data
    }

    /// Warms the cache for upcoming pages (reader preloading).
    public func prefetch(urls: [(String, [String: String])]) {
        Task {
            for entry in urls {
                _ = try? await imageData(for: entry.0, headers: entry.1)
            }
        }
    }

    // MARK: - Cache management

    public static func cacheKey(for urlString: String) -> String {
        MD5.hexString(Array(urlString.utf8))
    }

    public func diskUsage() -> Int64 {
        let files = (try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [.fileSizeKey]
        )) ?? []
        return files.reduce(0) { total, url in
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            return total + Int64(size)
        }
    }

    public func clearMemory() {
        memoryCache.removeAllObjects()
    }

    public func clearDisk() throws {
        let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
        for file in files {
            try? FileManager.default.removeItem(at: file)
        }
    }

    private func evictDiskIfNeeded() async {
        guard await diskUsage() > diskLimit else { return }
        let files = (try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey]
        )) ?? []
        let sorted = files.sorted {
            let lhs = (try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            let rhs = (try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            return lhs < rhs
        }
        var usage = await diskUsage()
        for file in sorted where usage > diskLimit {
            let size = Int64((try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
            try? FileManager.default.removeItem(at: file)
            usage -= size
        }
    }
}
