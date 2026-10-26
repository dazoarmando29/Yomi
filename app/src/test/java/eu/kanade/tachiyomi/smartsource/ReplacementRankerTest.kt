package eu.kanade.tachiyomi.smartsource

import eu.kanade.tachiyomi.smartsource.model.CandidateMetrics
import eu.kanade.tachiyomi.smartsource.model.Confidence
import eu.kanade.tachiyomi.smartsource.model.IdentitySignals
import eu.kanade.tachiyomi.smartsource.ranking.ReplacementRanker
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class ReplacementRankerTest {

    @Test
    fun `identical titles score as perfect similarity while unrelated titles score low`() {
        ReplacementRanker.titleSimilarity("Solo Leveling", "Solo Leveling") shouldBe 1.0
        ReplacementRanker.titleSimilarity("Solo Leveling", "The Divine Comedy") shouldBeLessThan 0.2
    }

    @Test
    fun `normalized titles ignore case punctuation and extra spaces`() {
        ReplacementRanker.titleSimilarity("  Solo-Leveling!! ", "solo leveling") shouldBe 1.0
        ReplacementRanker.normalizeTitle("Berserk: Deluxe Edition") shouldBe "berserk deluxe edition"
    }

    @Test
    fun `strong candidate outranks weak candidate`() {
        val strong = candidate(
            id = 1L,
            title = "Solo Leveling",
            titleSim = 1.0,
            chapters = 156,
            reliability = 0.95,
            daysSince = 3,
            preferred = true,
        )
        val weak = candidate(
            id = 2L,
            title = "Solo Leveling Side Story",
            titleSim = 0.55,
            chapters = 120,
            reliability = 0.6,
            daysSince = 200,
            preferred = false,
        )

        val ranked = ReplacementRanker.rank(listOf(weak, strong))

        ranked shouldHaveSize 2
        ranked.first().metrics.sourceId shouldBe 1L
        ranked.first().totalScore shouldBeGreaterThan ranked.last().totalScore
    }

    @Test
    fun `high confidence requires strong identity and never fires on author mismatch`() {
        val exact = candidate(id = 1L, title = "One Piece", titleSim = 1.0, authorMatch = true)
        val authorMismatch = candidate(id = 2L, title = "One Piece", titleSim = 1.0, authorMatch = false)
        val uncertainTitle = candidate(id = 3L, title = "Piece One", titleSim = 0.5)

        val ranked = ReplacementRanker.rank(listOf(exact, authorMismatch, uncertainTitle)).associateBy {
            it.metrics.sourceId
        }

        ranked.getValue(1L).confidence shouldBe Confidence.HIGH
        ranked.getValue(2L).confidence shouldBe Confidence.UNCERTAIN
        ranked.getValue(3L).confidence shouldBe Confidence.UNCERTAIN
    }

    @Test
    fun `factors are explainable and sum to the total`() {
        val candidate = candidate(id = 1L, title = "Vagabond", titleSim = 0.9, chapters = 120)

        val ranked = ReplacementRanker.rank(listOf(candidate)).single()

        ranked.factors shouldHaveSize 5
        ranked.totalScore shouldBe ranked.factors.sumOf { it.weighted }
        ranked.reasons.isNotEmpty() shouldBe true
    }

    @Test
    fun `chapter coverage and language and recency affect the score`() {
        val base = candidate(id = 1L, title = "Base", titleSim = 1.0, chapters = 120, daysSince = 10, preferred = true)
        val moreChapters = base.copy(sourceId = 2L, candidateChapterCount = 200, latestChapterNumber = 200.0)
        val older = base.copy(sourceId = 3L, daysSinceLastUpdate = 400)
        val wrongLanguage = base.copy(sourceId = 4L, preferredLanguage = false)

        val ranked = ReplacementRanker.rank(listOf(wrongLanguage, older, moreChapters, base))
            .associateBy { it.metrics.sourceId }

        ranked.getValue(2L).totalScore shouldBeGreaterThan ranked.getValue(1L).totalScore
        ranked.getValue(1L).totalScore shouldBeGreaterThan ranked.getValue(3L).totalScore
        ranked.getValue(1L).totalScore shouldBeGreaterThan ranked.getValue(4L).totalScore
    }

    @Test
    fun `weights are the documented design values`() {
        ReplacementRanker.WEIGHT_IDENTITY shouldBe 0.35
        ReplacementRanker.WEIGHT_CHAPTERS shouldBe 0.25
        ReplacementRanker.WEIGHT_RELIABILITY shouldBe 0.20
        ReplacementRanker.WEIGHT_RECENCY shouldBe 0.10
        ReplacementRanker.WEIGHT_LANGUAGE shouldBe 0.10
    }

    private fun candidate(
        id: Long,
        title: String,
        titleSim: Double,
        chapters: Int = 150,
        reliability: Double = 0.9,
        daysSince: Int? = 5,
        preferred: Boolean = true,
        authorMatch: Boolean? = null,
    ) = CandidateMetrics(
        sourceId = id,
        sourceName = "Source $id",
        candidateTitle = title,
        identity = IdentitySignals(
            titleSimilarity = titleSim,
            normalizedTitleMatch = titleSim >= 0.95,
            authorMatch = authorMatch,
        ),
        candidateChapterCount = chapters,
        originalChapterCount = 150,
        latestChapterNumber = chapters.toDouble(),
        originalLatestChapterNumber = 150.0,
        daysSinceLastUpdate = daysSince,
        preferredLanguage = preferred,
        reliability = reliability,
    )
}
