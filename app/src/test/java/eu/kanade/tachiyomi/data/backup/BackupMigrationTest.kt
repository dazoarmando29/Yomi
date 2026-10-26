package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import okio.Buffer
import okio.BufferedSink
import okio.GzipSink
import okio.GzipSource
import okio.buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class BackupMigrationTest {

    private val parser = ProtoBuf

    @Test
    fun `modern mihon backup preserves reading progress, history, bookmarks and tracking`() {
        val backup = modernBackup()

        val decoded = roundTrip(backup)

        val manga = decoded.single()
        manga.title shouldBe "Solo Reading"
        manga.chapters[0].read shouldBe true
        manga.chapters[1].read shouldBe false
        manga.chapters[1].bookmark shouldBe true
        manga.chapters[1].lastPageRead shouldBe 12L
        manga.history.single().lastRead shouldBe 1_700_000_000_000L
        manga.history.single().readDuration shouldBe 600L
        manga.tracking.single().syncId shouldBe 2
        manga.tracking.single().lastChapterRead shouldBe 3f
    }

    @Test
    fun `legacy tachiyomi fields are skipped while modern progress fields survive`() {
        val reader = BackupReader(Buffer().write(legacyEncodedBackup()), parser)
        val entries = buildList {
            while (reader.nextEntry()) add(reader.readEntry())
        }
        val metadata = reader.readMetadata()

        val manga = entries.single()
        manga.title shouldBe "Legacy Title"
        manga.chapters.single().read shouldBe true
        manga.chapters.single().lastPageRead shouldBe 7L
        manga.history.single().lastRead shouldBe 1_600_000_000_000L
        metadata.backupSources.single().name shouldBe "Legacy Source"
    }

    @Test
    fun `gzipped proto stream inflates to the plain encoding`() {
        val backup = modernBackup()
        val plain = encodeStream(backup)

        val gzipBuffer = Buffer()
        val gzipSink = GzipSink(gzipBuffer).buffer()
        gzipSink.write(plain)
        gzipSink.close()
        val gzipped = gzipBuffer.readByteArray()

        gzipped[0] shouldBe 0x1f.toByte()
        gzipped[1] shouldBe 0x8b.toByte()

        val gzipSource = GzipSource(Buffer().write(gzipped)).buffer()
        val inflated = gzipSource.readByteArray()
        gzipSource.close()

        inflated shouldBe plain
    }

    @Test
    fun `duplicate entries both parse so restore can merge them`() {
        val backup = modernBackup()
            .let { it.copy(backupManga = it.backupManga + it.backupManga) }

        val decoded = roundTrip(backup)

        decoded.size shouldBe 2
        decoded[0].url shouldBe decoded[1].url
    }

    private fun modernBackup() = Backup(
        backupManga = listOf(
            BackupManga(
                source = 22L,
                url = "/manga/42",
                title = "Solo Reading",
                favorite = true,
                chapters = listOf(
                    BackupChapter(url = "/c/1", name = "Chapter 1", read = true, chapterNumber = 1f),
                    BackupChapter(url = "/c/2", name = "Chapter 2", bookmark = true, lastPageRead = 12L),
                ),
                categories = listOf(0L),
                tracking = listOf(
                    BackupTracking(syncId = 2, libraryId = 5L, mediaId = 9L, lastChapterRead = 3f),
                ),
                history = listOf(
                    BackupHistory(url = "/c/1", lastRead = 1_700_000_000_000L, readDuration = 600L),
                ),
            ),
        ),
        backupCategories = listOf(BackupCategory(name = "Reading", order = 0)),
        backupSources = listOf(BackupSource(name = "Source A", sourceId = 22L)),
    )

    private fun roundTrip(backup: Backup): List<BackupManga> {
        val reader = BackupReader(Buffer().write(encodeStream(backup)), parser)
        return buildList {
            while (reader.nextEntry()) add(reader.readEntry())
        }
    }

    private fun encodeStream(backup: Backup): ByteArray {
        val sink = Buffer()
        val writer = BackupWriter(sink, parser)
        backup.backupManga.forEach(writer::writeEntry)
        writer.writeMetadata(backup.copy(backupManga = emptyList()))
        return sink.readByteArray()
    }

    /**
     * A pre-Mihon Tachiyomi backup entry: modern progress fields plus the legacy
     * non-compliant field 102 that current decoders skip.
     */
    private fun legacyEncodedBackup(): ByteArray {
        val entry = parser.encodeToByteArray(
            LegacyManga.serializer(),
            LegacyManga(
                source = 7L,
                url = "/legacy/1",
                title = "Legacy Title",
                chapters = listOf(BackupChapter(url = "/lc/1", name = "Chapter 1", read = true, lastPageRead = 7L)),
                history = listOf(BackupHistory(url = "/lc/1", lastRead = 1_600_000_000_000L)),
                brokenHistory = listOf(LegacyHistory(id = "/lc/1", lastRead = 1_600_000_000_000L)),
            ),
        )
        val metadata = parser.encodeToByteArray(
            Backup.serializer(),
            Backup(emptyList()).copy(backupSources = listOf(BackupSource(name = "Legacy Source", sourceId = 7L))),
        )
        val sink = Buffer()
        sink.writeByte(0x0A)
        sink.writeVarint(entry.size.toLong())
        sink.write(entry)
        sink.write(metadata)
        return sink.readByteArray()
    }

    private fun BufferedSink.writeVarint(value: Long) {
        var remaining = value
        while ((remaining and 0x7FL.inv()) != 0L) {
            writeByte((remaining and 0x7F).toInt() or 0x80)
            remaining = remaining ushr 7
        }
        writeByte(remaining.toInt())
    }

    @Serializable
    private class LegacyManga(
        @ProtoNumber(1) val source: Long,
        @ProtoNumber(2) val url: String,
        @ProtoNumber(3) val title: String = "",
        @ProtoNumber(16) val chapters: List<BackupChapter> = emptyList(),
        @ProtoNumber(102) val brokenHistory: List<LegacyHistory> = emptyList(),
        @ProtoNumber(104) val history: List<BackupHistory> = emptyList(),
    )

    @Serializable
    private class LegacyHistory(
        @ProtoNumber(1) val id: String = "",
        @ProtoNumber(2) val lastRead: Long = 0,
    )
}
