import Foundation

// MARK: - Decoding (Mihon/Tachiyomi/Yomi proto.gz -> YomiBackup)

public enum BackupCodec {
    /// Parses a (potentially gzipped) backup file.
    public static func decode(_ data: Data) throws -> YomiBackup {
        let payload = try Gzip.decompress(data)
        var backup = YomiBackup()
        var reader = ProtobufReader(payload)
        while let field = try reader.next() {
            switch (field.number, field.wireType) {
            case (1, .lengthDelimited):
                backup.mangas.append(try decodeManga(field.dataValue))
            case (2, .lengthDelimited):
                backup.categories.append(try decodeCategory(field.dataValue))
            case (101, .lengthDelimited):
                backup.sources.append(try decodeSource(field.dataValue))
            case (104, .lengthDelimited):
                backup.skippedPreferenceCount += 1
            case (105, .lengthDelimited):
                backup.skippedSourcePreferenceCount += 1
            case (106, .lengthDelimited):
                backup.skippedExtensionStoreCount += 1
            default:
                continue // unknown/legacy fields are skipped, never fatal
            }
        }
        return backup
    }

    static func decodeManga(_ payload: Data) throws -> YomiBackupManga {
        var manga = YomiBackupManga(source: 0, url: "")
        var reader = ProtobufReader(payload)
        while let field = try reader.next() {
            switch field.number {
            case 1: manga.source = ProtobufReader.signed(field.varintValue)
            case 2: manga.url = ProtobufReader.string(field.dataValue)
            case 3: manga.title = ProtobufReader.string(field.dataValue)
            case 4: manga.artist = ProtobufReader.string(field.dataValue)
            case 5: manga.author = ProtobufReader.string(field.dataValue)
            case 6: manga.mangaDescription = ProtobufReader.string(field.dataValue)
            case 7: manga.genres.append(ProtobufReader.string(field.dataValue))
            case 8: manga.status = Int(ProtobufReader.signed(field.varintValue))
            case 9: manga.thumbnailURL = ProtobufReader.string(field.dataValue)
            case 13: manga.dateAdded = ProtobufReader.signed(field.varintValue)
            case 14: manga.viewer = Int(ProtobufReader.signed(field.varintValue))
            case 16: manga.chapters.append(try decodeChapter(field.dataValue))
            case 17: manga.categories.append(contentsOf: try decodeInt64List(field))
            case 18: manga.tracking.append(try decodeTracking(field.dataValue))
            case 100: manga.favorite = ProtobufReader.bool(field.varintValue)
            case 101: manga.chapterFlags = Int(ProtobufReader.signed(field.varintValue))
            case 103: manga.viewerFlags = Int(ProtobufReader.signed(field.varintValue))
            case 104: manga.history.append(try decodeHistory(field.dataValue))
            case 105: manga.updateStrategy = Int(ProtobufReader.signed(field.varintValue))
            case 107: manga.favoriteModifiedAt = ProtobufReader.signed(field.varintValue)
            case 108: manga.excludedScanlators.append(ProtobufReader.string(field.dataValue))
            case 110: manga.notes = ProtobufReader.string(field.dataValue)
            case 111: manga.initialized = ProtobufReader.bool(field.varintValue)
            default:
                continue // memo (112), nextUpdate (113), legacy fields: skipped
            }
        }
        return manga
    }

    static func decodeChapter(_ payload: Data) throws -> YomiBackupChapter {
        var chapter = YomiBackupChapter(url: "", name: "")
        var reader = ProtobufReader(payload)
        while let field = try reader.next() {
            switch field.number {
            case 1: chapter.url = ProtobufReader.string(field.dataValue)
            case 2: chapter.name = ProtobufReader.string(field.dataValue)
            case 3: chapter.scanlator = ProtobufReader.string(field.dataValue)
            case 4: chapter.read = ProtobufReader.bool(field.varintValue)
            case 5: chapter.bookmark = ProtobufReader.bool(field.varintValue)
            case 6: chapter.lastPageRead = ProtobufReader.signed(field.varintValue)
            case 7: chapter.dateFetch = ProtobufReader.signed(field.varintValue)
            case 8: chapter.dateUpload = ProtobufReader.signed(field.varintValue)
            case 9: chapter.chapterNumber = decodeFloat(field)
            case 10: chapter.sourceOrder = ProtobufReader.signed(field.varintValue)
            default:
                continue
            }
        }
        return chapter
    }

    static func decodeCategory(_ payload: Data) throws -> YomiBackupCategory {
        var category = YomiBackupCategory(name: "")
        var reader = ProtobufReader(payload)
        while let field = try reader.next() {
            switch field.number {
            case 1: category.name = ProtobufReader.string(field.dataValue)
            case 2: category.order = ProtobufReader.signed(field.varintValue)
            case 3: category.id = ProtobufReader.signed(field.varintValue)
            case 100: category.flags = ProtobufReader.signed(field.varintValue)
            default:
                continue
            }
        }
        return category
    }

    static func decodeHistory(_ payload: Data) throws -> YomiBackupHistory {
        var history = YomiBackupHistory(url: "")
        var reader = ProtobufReader(payload)
        while let field = try reader.next() {
            switch field.number {
            case 1: history.url = ProtobufReader.string(field.dataValue)
            case 2: history.lastRead = ProtobufReader.signed(field.varintValue)
            case 3: history.readDuration = ProtobufReader.signed(field.varintValue)
            default:
                continue
            }
        }
        return history
    }

    static func decodeSource(_ payload: Data) throws -> YomiBackupSource {
        var source = YomiBackupSource(name: "", sourceId: 0)
        var reader = ProtobufReader(payload)
        while let field = try reader.next() {
            switch field.number {
            case 1: source.name = ProtobufReader.string(field.dataValue)
            case 2: source.sourceId = ProtobufReader.signed(field.varintValue)
            default:
                continue
            }
        }
        return source
    }

    static func decodeTracking(_ payload: Data) throws -> YomiBackupTracking {
        var tracking = YomiBackupTracking()
        var reader = ProtobufReader(payload)
        while let field = try reader.next() {
            switch field.number {
            case 1: tracking.syncId = Int(ProtobufReader.signed(field.varintValue))
            case 2: tracking.libraryId = ProtobufReader.signed(field.varintValue)
            case 3: tracking.mediaIdInt = Int(ProtobufReader.signed(field.varintValue))
            case 4: tracking.trackingURL = ProtobufReader.string(field.dataValue)
            case 5: tracking.title = ProtobufReader.string(field.dataValue)
            case 6: tracking.lastChapterRead = decodeFloat(field)
            case 7: tracking.totalChapters = Int(ProtobufReader.signed(field.varintValue))
            case 8: tracking.score = decodeFloat(field)
            case 9: tracking.status = Int(ProtobufReader.signed(field.varintValue))
            case 10: tracking.startedReadingDate = ProtobufReader.signed(field.varintValue)
            case 11: tracking.finishedReadingDate = ProtobufReader.signed(field.varintValue)
            case 12: tracking.isPrivate = ProtobufReader.bool(field.varintValue)
            case 100: tracking.mediaId = ProtobufReader.signed(field.varintValue)
            default:
                continue
            }
        }
        return tracking
    }

    /// Repeated varint field, tolerating packed encoding as well.
    static func decodeInt64List(_ field: ProtobufReader.Field) throws -> [Int64] {
        if field.wireType == .lengthDelimited {
            var reader = ProtobufReader(field.dataValue)
            var values: [Int64] = []
            while !reader.isAtEnd {
                values.append(ProtobufReader.signed(try reader.readVarint()))
            }
            return values
        }
        return [ProtobufReader.signed(field.varintValue)]
    }

    static func decodeFloat(_ field: ProtobufReader.Field) -> Float {
        switch field.wireType {
        case .fixed32:
            return ProtobufReader.float(field.fixed32Value)
        case .varint:
            return ProtobufReader.float(UInt32(truncatingIfNeeded: field.varintValue))
        default:
            return 0
        }
    }

    // MARK: - Encoding (YomiBackup -> proto, optionally gzipped)

    /// Encodes a backup to protobuf bytes with the Android field numbers.
    public static func encode(_ backup: YomiBackup) -> Data {
        var writer = ProtobufWriter()
        for manga in backup.mangas {
            writer.writeMessageField(1, encodeManga(manga))
        }
        for category in backup.categories {
            writer.writeMessageField(2, encodeCategory(category))
        }
        for source in backup.sources {
            writer.writeMessageField(101, encodeSource(source))
        }
        return writer.data
    }

    /// Encodes and gzips — the on-disk `.proto.gz` format.
    public static func encodeGzipped(_ backup: YomiBackup) -> Data {
        Gzip.compress(encode(backup))
    }

    static func encodeManga(_ manga: YomiBackupManga) -> Data {
        var writer = ProtobufWriter()
        writer.writeVarintField(1, manga.source)
        writer.writeStringField(2, manga.url)
        writer.writeStringField(3, manga.title)
        if let artist = manga.artist { writer.writeStringField(4, artist) }
        if let author = manga.author { writer.writeStringField(5, author) }
        if let description = manga.mangaDescription { writer.writeStringField(6, description) }
        for genre in manga.genres {
            writer.writeStringField(7, genre)
        }
        if manga.status != 0 { writer.writeVarintField(8, Int64(manga.status)) }
        if let thumbnail = manga.thumbnailURL { writer.writeStringField(9, thumbnail) }
        if manga.dateAdded != 0 { writer.writeVarintField(13, manga.dateAdded) }
        if manga.viewer != 0 { writer.writeVarintField(14, Int64(manga.viewer)) }
        for chapter in manga.chapters {
            writer.writeMessageField(16, encodeChapter(chapter))
        }
        for category in manga.categories {
            writer.writeVarintField(17, category)
        }
        for tracking in manga.tracking {
            writer.writeMessageField(18, encodeTracking(tracking))
        }
        if manga.favorite { writer.writeBoolField(100, true) }
        if manga.chapterFlags != 0 { writer.writeVarintField(101, Int64(manga.chapterFlags)) }
        if let viewerFlags = manga.viewerFlags { writer.writeVarintField(103, Int64(viewerFlags)) }
        for history in manga.history {
            writer.writeMessageField(104, encodeHistory(history))
        }
        if manga.updateStrategy != 0 { writer.writeVarintField(105, Int64(manga.updateStrategy)) }
        if let modified = manga.favoriteModifiedAt { writer.writeVarintField(107, modified) }
        for scanlator in manga.excludedScanlators {
            writer.writeStringField(108, scanlator)
        }
        if !manga.notes.isEmpty { writer.writeStringField(110, manga.notes) }
        if manga.initialized { writer.writeBoolField(111, true) }
        return writer.data
    }

    static func encodeChapter(_ chapter: YomiBackupChapter) -> Data {
        var writer = ProtobufWriter()
        writer.writeStringField(1, chapter.url)
        writer.writeStringField(2, chapter.name)
        if let scanlator = chapter.scanlator { writer.writeStringField(3, scanlator) }
        if chapter.read { writer.writeBoolField(4, true) }
        if chapter.bookmark { writer.writeBoolField(5, true) }
        if chapter.lastPageRead != 0 { writer.writeVarintField(6, chapter.lastPageRead) }
        if chapter.dateFetch != 0 { writer.writeVarintField(7, chapter.dateFetch) }
        if chapter.dateUpload != 0 { writer.writeVarintField(8, chapter.dateUpload) }
        if chapter.chapterNumber != 0 { writer.writeFloatField(9, chapter.chapterNumber) }
        if chapter.sourceOrder != 0 { writer.writeVarintField(10, chapter.sourceOrder) }
        return writer.data
    }

    static func encodeCategory(_ category: YomiBackupCategory) -> Data {
        var writer = ProtobufWriter()
        writer.writeStringField(1, category.name)
        if category.order != 0 { writer.writeVarintField(2, category.order) }
        if category.id != 0 { writer.writeVarintField(3, category.id) }
        if category.flags != 0 { writer.writeVarintField(100, category.flags) }
        return writer.data
    }

    static func encodeHistory(_ history: YomiBackupHistory) -> Data {
        var writer = ProtobufWriter()
        writer.writeStringField(1, history.url)
        writer.writeVarintField(2, history.lastRead)
        if history.readDuration != 0 { writer.writeVarintField(3, history.readDuration) }
        return writer.data
    }

    static func encodeSource(_ source: YomiBackupSource) -> Data {
        var writer = ProtobufWriter()
        writer.writeStringField(1, source.name)
        writer.writeVarintField(2, source.sourceId)
        return writer.data
    }

    static func encodeTracking(_ tracking: YomiBackupTracking) -> Data {
        var writer = ProtobufWriter()
        if tracking.syncId != 0 { writer.writeVarintField(1, Int64(tracking.syncId)) }
        if tracking.libraryId != 0 { writer.writeVarintField(2, tracking.libraryId) }
        if tracking.mediaIdInt != 0 { writer.writeVarintField(3, Int64(tracking.mediaIdInt)) }
        if !tracking.trackingURL.isEmpty { writer.writeStringField(4, tracking.trackingURL) }
        if !tracking.title.isEmpty { writer.writeStringField(5, tracking.title) }
        if tracking.lastChapterRead != 0 { writer.writeFloatField(6, tracking.lastChapterRead) }
        if tracking.totalChapters != 0 { writer.writeVarintField(7, Int64(tracking.totalChapters)) }
        if tracking.score != 0 { writer.writeFloatField(8, tracking.score) }
        if tracking.status != 0 { writer.writeVarintField(9, Int64(tracking.status)) }
        if tracking.startedReadingDate != 0 { writer.writeVarintField(10, tracking.startedReadingDate) }
        if tracking.finishedReadingDate != 0 { writer.writeVarintField(11, tracking.finishedReadingDate) }
        if tracking.isPrivate { writer.writeBoolField(12, true) }
        if tracking.mediaId != 0 { writer.writeVarintField(100, tracking.mediaId) }
        return writer.data
    }
}
