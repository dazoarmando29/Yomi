package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupSource

enum class BackupFormat {
    MIHON_TACHIBK,
    TACHIYOMI_PROTO,
    UNKNOWN_PROTO,
    UNSUPPORTED_JSON,
    UNKNOWN,
    ;

    companion object {
        fun fromFileName(fileName: String?): BackupFormat = when {
            fileName == null -> UNKNOWN
            fileName.endsWith(".tachibk", ignoreCase = true) -> MIHON_TACHIBK
            fileName.endsWith(".proto.gz", ignoreCase = true) -> TACHIYOMI_PROTO
            fileName.endsWith(".json", ignoreCase = true) -> UNSUPPORTED_JSON
            else -> UNKNOWN
        }
    }
}

data class BackupEntryStats(
    val favoriteCount: Int = 0,
    val chapterCount: Int = 0,
    val readChapterCount: Int = 0,
    val bookmarkedChapterCount: Int = 0,
    val entriesWithProgressCount: Int = 0,
    val historyCount: Int = 0,
    val trackingCount: Int = 0,
) {
    operator fun plus(other: BackupEntryStats): BackupEntryStats = BackupEntryStats(
        favoriteCount = favoriteCount + other.favoriteCount,
        chapterCount = chapterCount + other.chapterCount,
        readChapterCount = readChapterCount + other.readChapterCount,
        bookmarkedChapterCount = bookmarkedChapterCount + other.bookmarkedChapterCount,
        entriesWithProgressCount = entriesWithProgressCount + other.entriesWithProgressCount,
        historyCount = historyCount + other.historyCount,
        trackingCount = trackingCount + other.trackingCount,
    )
}

data class BackupImportSummary(
    val format: BackupFormat,
    val entryCount: Int,
    val stats: BackupEntryStats,
    val categoryCount: Int,
    val sourceCount: Int,
    val missingSources: List<String>,
    val missingTrackers: List<String>,
    val hasAppPreferences: Boolean,
    val hasSourcePreferences: Boolean,
    val hasExtensionStores: Boolean,
) {
    val hasReadingProgress: Boolean
        get() = stats.entriesWithProgressCount > 0 || stats.readChapterCount > 0 || stats.historyCount > 0

    val hasTracking: Boolean
        get() = stats.trackingCount > 0

    companion object {
        fun build(
            format: BackupFormat,
            entryCount: Int,
            stats: BackupEntryStats,
            backup: Backup,
            missingSources: List<String>,
            missingTrackers: List<String>,
        ): BackupImportSummary = BackupImportSummary(
            format = format,
            entryCount = entryCount,
            stats = stats,
            categoryCount = backup.backupCategories.size,
            sourceCount = backup.backupSources.size,
            missingSources = missingSources,
            missingTrackers = missingTrackers,
            hasAppPreferences = backup.backupPreferences.isNotEmpty(),
            hasSourcePreferences = backup.backupSourcePreferences.isNotEmpty(),
            hasExtensionStores = backup.backupExtensionStores.isNotEmpty(),
        )
    }
}

internal fun computeMissingSourceNames(
    sources: List<BackupSource>,
    installedSourceNames: Map<Long, String?>,
    stubSourceName: (Long) -> String,
): List<String> {
    return sources
        .filter { installedSourceNames[it.sourceId] == null }
        .map { source ->
            if (source.name.isBlank() || source.name.toLongOrNull() != null) {
                stubSourceName(source.sourceId)
            } else {
                source.name
            }
        }
        .distinct()
        .sorted()
}
