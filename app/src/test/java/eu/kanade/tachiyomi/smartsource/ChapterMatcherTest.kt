package eu.kanade.tachiyomi.smartsource

import eu.kanade.tachiyomi.smartsource.migration.CandidateManga
import eu.kanade.tachiyomi.smartsource.migration.MigrationPlan
import eu.kanade.tachiyomi.smartsource.migration.MigrationPlanner
import eu.kanade.tachiyomi.smartsource.migration.MigrationSnapshotStore
import eu.kanade.tachiyomi.smartsource.migration.OriginalManga
import eu.kanade.tachiyomi.smartsource.migration.SnapshotHistory
import eu.kanade.tachiyomi.smartsource.model.CandidateMetrics
import eu.kanade.tachiyomi.smartsource.model.IdentitySignals
import eu.kanade.tachiyomi.smartsource.ranking.ChapterMatcher
import eu.kanade.tachiyomi.smartsource.ranking.ChapterRef
import eu.kanade.tachiyomi.smartsource.ranking.MatchType
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class ChapterMatcherTest {

    @Test
    fun `chapters match by number including fractional numbers`() {
        val original = listOf(
            chapter(order = 0, number = 1.0, name = "Chapter 1"),
            chapter(order = 1, number = 1.5, name = "Extra 1.5"),
            chapter(order = 2, number = 2.0, name = "Chapter 2"),
        )
        val replacement = listOf(
            chapter(order = 0, number = 2.0, name = "Ch. 2"),
            chapter(order = 1, number = 1.5, name = "Ch. 1.5"),
            chapter(order = 2, number = 1.0, name = "Ch. 1"),
        )

        val matches = ChapterMatcher.match(original, replacement)

        matches shouldHaveSize 3
        matches.all { it.type == MatchType.EXACT_NUMBER } shouldBe true
        matches[0].replacement!!.order shouldBe 2
        matches[1].replacement!!.order shouldBe 1
        matches[2].replacement!!.order shouldBe 0
    }

    @Test
    fun `renamed chapters fall back to normalized titles`() {
        val original = listOf(chapter(order = 0, number = null, name = "Chapter 12 - The End"))
        val replacement = listOf(chapter(order = 0, number = null, name = "Ch. 12: The End"))

        val matches = ChapterMatcher.match(original, replacement)

        matches.single().type shouldBe MatchType.TITLE
        matches.single().replacement!!.order shouldBe 0
    }

    @Test
    fun `duplicate numbers use distinct chapters`() {
        val original = listOf(
            chapter(order = 0, number = 10.0, name = "A"),
            chapter(order = 1, number = 10.0, name = "B"),
        )
        val replacement = listOf(
            chapter(order = 0, number = 10.0, name = "A"),
            chapter(order = 1, number = 10.0, name = "B"),
        )

        val matches = ChapterMatcher.match(original, replacement)

        matches.map { it.replacement!!.order } shouldBe listOf(0, 1)
    }

    @Test
    fun `split and combined chapters leave unmatched originals instead of guessing by index`() {
        val original = listOf(
            chapter(order = 0, number = 5.0, name = "Chapter 5"),
            chapter(order = 1, number = 6.0, name = "Chapter 6"),
        )
        val replacement = listOf(
            chapter(order = 0, number = 5.0, name = "Chapter 5"),
            chapter(order = 1, number = 5.5, name = "Chapter 5.5"),
        )

        val matches = ChapterMatcher.match(original, replacement)

        matches[0].type shouldBe MatchType.EXACT_NUMBER
        matches[1].type shouldBe MatchType.NONE
    }

    @Test
    fun `read state transfers only to actually read chapters and never marks extra chapters`() {
        val original = listOf(
            chapter(order = 0, number = 1.0, name = "C1", read = true),
            chapter(order = 1, number = 2.0, name = "C2", read = false, lastPageRead = 7),
            chapter(order = 2, number = 3.0, name = "C3", read = false, bookmark = true),
            chapter(order = 3, number = 4.0, name = "C4", read = false),
        )
        val replacement = (0..3).map { chapter(order = it, number = it + 1.0, name = "C${it + 1}") }

        val plan = ChapterMatcher.planReadTransfer(ChapterMatcher.match(original, replacement))

        plan.chaptersToMarkRead shouldBe listOf(replacement[0].id)
        plan.chaptersToMarkBookmarked shouldBe listOf(replacement[2].id)
        plan.lastPageTransfers shouldBe listOf(replacement[1].id to 7)
        plan.pageProgressExact shouldBe true
        plan.unmatchedOriginals shouldBe 0
    }

    @Test
    fun `page progress is not copied when only a fuzzy title match exists`() {
        val original = listOf(chapter(order = 0, number = null, name = "Chapter 9", lastPageRead = 12))
        val replacement = listOf(chapter(order = 0, number = null, name = "Ch. 9"))

        val plan = ChapterMatcher.planReadTransfer(ChapterMatcher.match(original, replacement))

        plan.lastPageTransfers.shouldHaveSize(0)
        plan.pageProgressExact shouldBe false
        plan.unmatchedOriginals shouldBe 1
    }

    @Test
    fun `resume chapter is the first unread chapter after the last read one`() {
        val matches = ChapterMatcher.match(
            original = listOf(
                chapter(order = 0, number = 1.0, name = "C1", read = true),
                chapter(order = 1, number = 2.0, name = "C2", read = true),
                chapter(order = 2, number = 3.0, name = "C3"),
            ),
            replacement = listOf(
                chapter(order = 0, number = 1.0, name = "C1"),
                chapter(order = 1, number = 2.0, name = "C2"),
                chapter(order = 2, number = 3.0, name = "C3"),
            ),
        )

        ChapterMatcher.resumeChapterNumber(matches) shouldBe 3.0
    }

    private fun chapter(
        order: Int,
        number: Double?,
        name: String,
        read: Boolean = false,
        lastPageRead: Int = 0,
        bookmark: Boolean = false,
    ) = ChapterRef(
        id = order.toLong() + 100L,
        number = number,
        name = name,
        order = order,
        read = read,
        lastPageRead = lastPageRead,
        bookmark = bookmark,
    )
}

@Execution(ExecutionMode.CONCURRENT)
class MigrationPlannerTest {

    private fun metrics(id: Long, title: String, similarity: Double, chapters: Int) = CandidateMetrics(
        sourceId = id,
        sourceName = "Source $id",
        candidateTitle = title,
        identity = IdentitySignals(titleSimilarity = similarity, normalizedTitleMatch = similarity >= 0.95),
        candidateChapterCount = chapters,
        originalChapterCount = 150,
        latestChapterNumber = chapters.toDouble(),
        originalLatestChapterNumber = 150.0,
        daysSinceLastUpdate = 2,
        preferredLanguage = true,
        reliability = 0.9,
    )

    private fun chapters(count: Int) = (0 until count).map {
        ChapterRef(id = it.toLong(), number = it + 1.0, name = "Chapter ${it + 1}", order = it)
    }

    @Test
    fun `plan separates exact and uncertain matches`() {
        val original = OriginalManga(
            mangaId = 1L,
            title = "Example Series",
            sourceId = 99L,
            chapterCount = 150,
            latestChapterNumber = 150.0,
            chapters = chapters(3),
        )
        val exact = CandidateManga(metrics(1L, "Example Series", 1.0, 156), chapters(3))
        val uncertain = CandidateManga(metrics(2L, "Example Series Spin-Off", 0.55, 160), chapters(3))

        val plan = MigrationPlanner.plan(original, listOf(uncertain, exact))

        plan.exactMatches shouldHaveSize 1
        plan.uncertainMatches shouldHaveSize 1
        plan.noReliableMatch shouldBe false
        plan.exactMatches.single().readTransfer.matches shouldHaveSize 3
    }

    @Test
    fun `plan reports no reliable match instead of guessing`() {
        val original = OriginalManga(
            mangaId = 1L,
            title = "Example Series",
            sourceId = 99L,
            chapterCount = 150,
            latestChapterNumber = 150.0,
            chapters = chapters(1),
        )
        val uncertain = CandidateManga(metrics(2L, "Totally Different", 0.2, 999), chapters(1))

        val plan = MigrationPlanner.plan(original, listOf(uncertain))

        plan.noReliableMatch shouldBe true
        plan.exactMatches shouldHaveSize 0
        MigrationPlan.NO_RELIABLE_MATCH_MESSAGE shouldBe "No reliable match found"
    }

    @Test
    fun `snapshot round trip preserves chapters history and categories`() {
        val snapshot = MigrationPlanner.snapshotOf(
            mangaId = 7L,
            title = "Example Series",
            sourceId = 3L,
            favorite = true,
            categoryIds = listOf(1L, 2L),
            notes = "my notes",
            chapters = chapters(2).mapIndexed { i, c -> if (i == 0) c.copy(read = true, lastPageRead = 3) else c },
            history = listOf(SnapshotHistory(chapterId = 0L, readAtMillis = 123L, readDuration = 60L)),
            createdAtMillis = 999L,
        )

        val decoded = MigrationSnapshotStore.decode(MigrationSnapshotStore.encode(listOf(snapshot)))

        decoded.single() shouldBe snapshot
    }

    @Test
    fun `planner keeps original progress separate from the replacement mapping`() {
        val originalChapters = listOf(
            ChapterRef(id = 1L, number = 1.0, name = "C1", order = 0, read = true),
            ChapterRef(id = 2L, number = 2.0, name = "C2", order = 1, lastPageRead = 5),
        )
        val original = OriginalManga(
            mangaId = 1L,
            title = "Example Series",
            sourceId = 99L,
            chapterCount = 2,
            latestChapterNumber = 2.0,
            chapters = originalChapters,
        )
        val candidate = CandidateManga(
            metrics(1L, "Example Series", 1.0, 2),
            chapters(2).map { it.copy(id = it.id + 500L) },
        )

        val recommendation = MigrationPlanner.plan(original, listOf(candidate)).exactMatches.single()

        recommendation.chapterMatches.map { it.original.id } shouldBe listOf(1L, 2L)
        recommendation.readTransfer.chaptersToMarkRead shouldBe listOf(500L)
        recommendation.readTransfer.lastPageTransfers shouldBe listOf(501L to 5)
    }
}
