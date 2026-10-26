package eu.kanade.tachiyomi.smartsource.migration

import eu.kanade.tachiyomi.smartsource.model.CandidateMetrics
import eu.kanade.tachiyomi.smartsource.model.Confidence
import eu.kanade.tachiyomi.smartsource.model.RankedCandidate
import eu.kanade.tachiyomi.smartsource.ranking.ChapterMatch
import eu.kanade.tachiyomi.smartsource.ranking.ChapterMatcher
import eu.kanade.tachiyomi.smartsource.ranking.ChapterRef
import eu.kanade.tachiyomi.smartsource.ranking.ReadTransferPlan
import eu.kanade.tachiyomi.smartsource.ranking.ReplacementRanker
import kotlinx.serialization.Serializable

data class OriginalManga(
    val mangaId: Long,
    val title: String,
    val sourceId: Long,
    val author: String? = null,
    val chapterCount: Int,
    val latestChapterNumber: Double?,
    val chapters: List<ChapterRef>,
)

data class CandidateManga(
    val metrics: CandidateMetrics,
    val chapters: List<ChapterRef>,
)

data class MigrationRecommendation(
    val candidate: RankedCandidate,
    val chapterMatches: List<ChapterMatch>,
    val readTransfer: ReadTransferPlan,
    val resumeChapterNumber: Double?,
) {
    val exact: Boolean get() = candidate.confidence == Confidence.HIGH
}

data class MigrationPlan(
    val original: OriginalManga,
    val exactMatches: List<MigrationRecommendation>,
    val uncertainMatches: List<MigrationRecommendation>,
    val noReliableMatch: Boolean,
) {
    companion object {
        const val NO_RELIABLE_MATCH_MESSAGE = "No reliable match found"
    }
}

@Serializable
data class SnapshotChapter(
    val chapterId: Long,
    val name: String,
    val number: Double?,
    val read: Boolean,
    val lastPageRead: Int,
    val bookmark: Boolean,
)

@Serializable
data class SnapshotHistory(
    val chapterId: Long,
    val readAtMillis: Long?,
    val readDuration: Long,
)

@Serializable
data class MangaSnapshot(
    val mangaId: Long,
    val title: String,
    val sourceId: Long,
    val favorite: Boolean,
    val categoryIds: List<Long>,
    val notes: String,
    val chapters: List<SnapshotChapter>,
    val history: List<SnapshotHistory>,
    val createdAtMillis: Long,
)

/**
 * Builds a reviewable migration plan: exact and uncertain matches are kept separate,
 * chapter identity is mapped by number and title (never by index), and a plan with no
 * sufficiently verified match reports [MigrationPlan.NO_RELIABLE_MATCH_MESSAGE] instead
 * of suggesting a potentially incorrect migration.
 */
object MigrationPlanner {

    fun plan(original: OriginalManga, candidates: List<CandidateManga>): MigrationPlan {
        val ranked = ReplacementRanker.rank(candidates.map { it.metrics })

        val recommendations = ranked.mapNotNull { rankedCandidate ->
            val candidate = candidates.firstOrNull {
                it.metrics.sourceId == rankedCandidate.metrics.sourceId &&
                    it.metrics.candidateTitle == rankedCandidate.metrics.candidateTitle
            } ?: return@mapNotNull null

            val matches = ChapterMatcher.match(original.chapters, candidate.chapters)
            val readTransfer = ChapterMatcher.planReadTransfer(matches)

            MigrationRecommendation(
                candidate = rankedCandidate,
                chapterMatches = matches,
                readTransfer = readTransfer,
                resumeChapterNumber = ChapterMatcher.resumeChapterNumber(matches),
            )
        }

        val (exact, uncertain) = recommendations.partition { it.exact }

        return MigrationPlan(
            original = original,
            exactMatches = exact,
            uncertainMatches = uncertain,
            noReliableMatch = exact.isEmpty(),
        )
    }

    fun snapshotOf(
        mangaId: Long,
        title: String,
        sourceId: Long,
        favorite: Boolean,
        categoryIds: List<Long>,
        notes: String,
        chapters: List<ChapterRef>,
        history: List<SnapshotHistory>,
        createdAtMillis: Long,
    ): MangaSnapshot {
        return MangaSnapshot(
            mangaId = mangaId,
            title = title,
            sourceId = sourceId,
            favorite = favorite,
            categoryIds = categoryIds,
            notes = notes,
            chapters = chapters.map {
                SnapshotChapter(
                    chapterId = it.id,
                    name = it.name,
                    number = it.number,
                    read = it.read,
                    lastPageRead = it.lastPageRead,
                    bookmark = it.bookmark,
                )
            },
            history = history,
            createdAtMillis = createdAtMillis,
        )
    }
}
