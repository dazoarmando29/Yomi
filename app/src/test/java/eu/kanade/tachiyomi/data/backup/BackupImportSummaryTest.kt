package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class BackupImportSummaryTest {

    @Test
    fun `format detection from file name`() {
        BackupFormat.fromFileName("yomi_2026-10-26.tachibk") shouldBe BackupFormat.MIHON_TACHIBK
        BackupFormat.fromFileName("TACHYOMI_2021-05-01.proto.gz") shouldBe BackupFormat.TACHIYOMI_PROTO
        BackupFormat.fromFileName("old.json") shouldBe BackupFormat.UNSUPPORTED_JSON
        BackupFormat.fromFileName("weird.bin") shouldBe BackupFormat.UNKNOWN
        BackupFormat.fromFileName(null) shouldBe BackupFormat.UNKNOWN
    }

    @Test
    fun `missing sources resolve names and stub unnamed or numeric ones`() {
        val sources = listOf(
            BackupSource(name = "Installed", sourceId = 1L),
            BackupSource(name = "Missing Named", sourceId = 2L),
            BackupSource(name = "", sourceId = 3L),
            BackupSource(name = "12345", sourceId = 4L),
        )
        val installed = mapOf(1L to "Installed", 2L to null, 3L to null, 4L to null)

        val missing = computeMissingSourceNames(
            sources = sources,
            installedSourceNames = installed,
            stubSourceName = { id -> "Stub $id" },
        )

        missing shouldBe listOf("Missing Named", "Stub 3", "Stub 4")
    }

    @Test
    fun `duplicate missing source names collapse`() {
        val sources = listOf(
            BackupSource(name = "Dup", sourceId = 2L),
            BackupSource(name = "Dup", sourceId = 3L),
        )

        val missing = computeMissingSourceNames(
            sources = sources,
            installedSourceNames = emptyMap(),
            stubSourceName = { id -> "Stub $id" },
        )

        missing shouldBe listOf("Dup")
    }

    @Test
    fun `stats aggregate and summary flags reflect content`() {
        val first = BackupEntryStats(
            favoriteCount = 1,
            chapterCount = 10,
            readChapterCount = 4,
            bookmarkedChapterCount = 1,
            entriesWithProgressCount = 1,
            historyCount = 3,
            trackingCount = 2,
        )
        val second = BackupEntryStats(
            favoriteCount = 1,
            chapterCount = 5,
            readChapterCount = 1,
            entriesWithProgressCount = 1,
        )

        val total = first + second

        total.favoriteCount shouldBe 2
        total.chapterCount shouldBe 15
        total.readChapterCount shouldBe 5
        total.bookmarkedChapterCount shouldBe 1
        total.entriesWithProgressCount shouldBe 2
        total.historyCount shouldBe 3
        total.trackingCount shouldBe 2

        val summary = BackupImportSummary.build(
            format = BackupFormat.MIHON_TACHIBK,
            entryCount = 2,
            stats = total,
            backup = Backup(emptyList()).copy(
                backupCategories = listOf(BackupCategory(name = "Reading", order = 0)),
                backupSources = listOf(BackupSource(name = "Source A", sourceId = 1L)),
                backupPreferences = listOf(BackupPreference("flag", BooleanPreferenceValue(true))),
                backupSourcePreferences = emptyList(),
                backupExtensionStores = emptyList(),
            ),
            missingSources = emptyList(),
            missingTrackers = emptyList(),
        )

        summary.categoryCount shouldBe 1
        summary.sourceCount shouldBe 1
        summary.hasReadingProgress shouldBe true
        summary.hasTracking shouldBe true
        summary.hasAppPreferences shouldBe true
        summary.hasSourcePreferences shouldBe false
        summary.hasExtensionStores shouldBe false
    }

    @Test
    fun `summary without progress or tracking reports none`() {
        val summary = BackupImportSummary.build(
            format = BackupFormat.TACHIYOMI_PROTO,
            entryCount = 1,
            stats = BackupEntryStats(),
            backup = Backup(emptyList()),
            missingSources = emptyList(),
            missingTrackers = emptyList(),
        )

        summary.hasReadingProgress shouldBe false
        summary.hasTracking shouldBe false
        summary.hasAppPreferences shouldBe false
    }
}
