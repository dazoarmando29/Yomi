package eu.kanade.presentation.manga

import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.manga.ChapterList
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

/**
 * Chapter search is shared by the phone and tablet manga screens through
 * [filterChapterListItems]; these tests pin its behavior so both layouts stay
 * in parity and regressions fail here instead of silently diverging.
 */
class ChapterSearchFilterTest {

    private fun item(id: Long, name: String, number: Double) = ChapterList.Item(
        chapter = Chapter.create().copy(id = id, name = name, chapterNumber = number),
        downloadState = Download.State.NOT_DOWNLOADED,
    )

    private val missingCount = ChapterList.MissingCount(id = "missing-3", count = 3)

    private val items = listOf(
        missingCount,
        item(id = 1, name = "Chapter 12 - The Storm", number = 12.0),
        item(id = 2, name = "Bonus Side Story", number = -1.0),
        item(id = 3, name = "Extra: storm interlude", number = 12.5),
    )

    @Test
    fun `blank query keeps every row including missing-count rows`() {
        filterChapterListItems(items, "") shouldContainExactly items
        filterChapterListItems(items, "   ") shouldContainExactly items
    }

    @Test
    fun `title matches are case insensitive`() {
        filterChapterListItems(items, "STORM") shouldContainExactly listOf(items[1], items[3])
    }

    @Test
    fun `chapter number matches`() {
        filterChapterListItems(items, "12.5") shouldContainExactly listOf(items[3])
        filterChapterListItems(items, "12") shouldContainExactly listOf(items[1], items[3])
    }

    @Test
    fun `non matching rows are dropped and missing-count rows never match`() {
        filterChapterListItems(items, "missing") shouldContainExactly emptyList()
        filterChapterListItems(items, "zzz") shouldContainExactly emptyList()
    }
}
