package eu.kanade.tachiyomi.data.backup

import android.net.Uri
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import tachiyomi.domain.source.service.SourceManager

@Inject
class BackupImportInspector(
    private val sourceManager: SourceManager,
    private val trackerManager: TrackerManager,
    private val backupDecoder: BackupDecoder,
) {

    suspend fun inspect(uri: Uri): BackupImportSummary {
        val fileName = uri.lastPathSegment
        var stats = BackupEntryStats()
        val trackerIds = mutableSetOf<Int>()

        val metadata = backupDecoder.decodeMetadata(uri, PreviewManga.serializer()) { manga ->
            stats += manga.toStats()
            manga.tracking.mapTo(trackerIds) { it.syncId }
        }

        val sources = metadata.backup.backupSources
        val installedNames = sources.associate { it.sourceId to sourceManager.get(it.sourceId)?.name }
        val stubNames = sources.associate { it.sourceId to sourceManager.getOrStub(it.sourceId).toString() }
        val missingSources = computeMissingSourceNames(
            sources = sources,
            installedSourceNames = installedNames,
            stubSourceName = stubNames::getValue,
        )
        val missingTrackers = trackerIds
            .mapNotNull { trackerManager.get(it.toLong()) }
            .filter { !it.isLoggedIn }
            .map { it.name }
            .sorted()

        return BackupImportSummary.build(
            format = resolveFormat(uri, fileName),
            entryCount = metadata.entryCount,
            stats = stats,
            backup = metadata.backup,
            missingSources = missingSources,
            missingTrackers = missingTrackers,
        )
    }

    private fun resolveFormat(uri: Uri, fileName: String?): BackupFormat {
        val byName = BackupFormat.fromFileName(fileName ?: uri.toString().substringAfterLast('/'))
        return when (byName) {
            BackupFormat.UNKNOWN -> BackupFormat.UNKNOWN_PROTO
            else -> byName
        }
    }

    // Only the fields the preview needs, so the rest of each entry is skipped while decoding
    @Serializable
    private class PreviewManga(
        @ProtoNumber(16) val chapters: List<PreviewChapter> = emptyList(),
        @ProtoNumber(18) val tracking: List<PreviewTracking> = emptyList(),
        @ProtoNumber(100) val favorite: Boolean = true,
        @ProtoNumber(104) val history: List<PreviewHistory> = emptyList(),
    ) {
        fun toStats(): BackupEntryStats = BackupEntryStats(
            favoriteCount = if (favorite) 1 else 0,
            chapterCount = chapters.size,
            readChapterCount = chapters.count { it.read },
            bookmarkedChapterCount = chapters.count { it.bookmark },
            entriesWithProgressCount = if (chapters.any { it.lastPageRead > 0 } || history.isNotEmpty()) 1 else 0,
            historyCount = history.size,
            trackingCount = tracking.size,
        )
    }

    @Serializable
    private class PreviewChapter(
        @ProtoNumber(4) val read: Boolean = false,
        @ProtoNumber(5) val bookmark: Boolean = false,
        @ProtoNumber(6) val lastPageRead: Long = 0,
    )

    @Serializable
    private class PreviewHistory(
        @ProtoNumber(1) val url: String = "",
    )

    @Serializable
    private class PreviewTracking(
        @ProtoNumber(1) val syncId: Int = 0,
    )
}
