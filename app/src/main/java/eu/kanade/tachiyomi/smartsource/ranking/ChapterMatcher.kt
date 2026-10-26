package eu.kanade.tachiyomi.smartsource.ranking

import kotlin.math.abs

data class ChapterRef(
    val id: Long = 0L,
    val number: Double?,
    val name: String,
    val volume: Int? = null,
    val order: Int = 0,
    val read: Boolean = false,
    val lastPageRead: Int = 0,
    val bookmark: Boolean = false,
)

enum class MatchType {
    EXACT_NUMBER,
    TITLE,
    ORDER_FALLBACK,
    NONE,
}

data class ChapterMatch(
    val original: ChapterRef,
    val replacement: ChapterRef?,
    val type: MatchType,
) {
    val isExact: Boolean get() = type == MatchType.EXACT_NUMBER
}

data class ReadTransferPlan(
    val matches: List<ChapterMatch>,
    val chaptersToMarkRead: List<Long>,
    val chaptersToMarkBookmarked: List<Long>,
    val lastPageTransfers: List<Pair<Long, Int>>,
    val pageProgressExact: Boolean,
    val unmatchedOriginals: Int,
) {
    val hasUnmatchedProgress: Boolean get() = unmatchedOriginals > 0
}

/**
 * Maps chapters between two different sources by chapter identity rather than index.
 *
 * Handles fractional numbers, duplicate numbers, renamed chapters, and split or
 * combined chapters. Chapter indexes are never assumed to be interchangeable, and
 * read state is only transferred to chapters that were actually read in the original.
 */
object ChapterMatcher {

    private const val NUMBER_EPSILON = 0.001

    fun match(original: List<ChapterRef>, replacement: List<ChapterRef>): List<ChapterMatch> {
        val used = mutableSetOf<Int>()
        return original.map { old ->
            val number = old.number
            val numberMatch = if (number != null) {
                replacement.firstOrNull { it.order !in used && it.number != null && abs(it.number - number) < NUMBER_EPSILON }
            } else {
                null
            }
            if (numberMatch != null) {
                used += numberMatch.order
                return@map ChapterMatch(old, numberMatch, MatchType.EXACT_NUMBER)
            }

            val titleMatch = replacement.firstOrNull {
                it.order !in used && normalizeChapterTitle(it.name) == normalizeChapterTitle(old.name)
            }
            if (titleMatch != null) {
                used += titleMatch.order
                return@map ChapterMatch(old, titleMatch, MatchType.TITLE)
            }

            ChapterMatch(old, null, MatchType.NONE)
        }
    }

    /**
     * Builds the read-state transfer plan from matches.
     *
     * Read state is only applied to chapters that were read in the original source:
     * nothing is marked read speculatively, and page progress is only transferred for
     * exact number matches where the original had saved a page.
     */
    fun planReadTransfer(matches: List<ChapterMatch>): ReadTransferPlan {
        val markRead = mutableListOf<Long>()
        val markBookmarked = mutableListOf<Long>()
        val pageTransfers = mutableListOf<Pair<Long, Int>>()
        var unmatched = 0

        matches.forEach { match ->
            val target = match.replacement
            if (target == null) {
                if (match.original.read || match.original.lastPageRead > 0 || match.original.bookmark) {
                    unmatched++
                }
                return@forEach
            }
            if (match.original.read) markRead += target.id
            if (match.original.bookmark) markBookmarked += target.id
            if (!match.original.read && match.original.lastPageRead > 0 && match.type == MatchType.EXACT_NUMBER) {
                pageTransfers += target.id to match.original.lastPageRead
            } else if (!match.original.read && match.original.lastPageRead > 0) {
                // Page numbers are source-specific; only chapter position can be preserved
                unmatched++
            }
        }

        return ReadTransferPlan(
            matches = matches,
            chaptersToMarkRead = markRead,
            chaptersToMarkBookmarked = markBookmarked,
            lastPageTransfers = pageTransfers,
            pageProgressExact = pageTransfers.isNotEmpty(),
            unmatchedOriginals = unmatched,
        )
    }

    /**
     * Plans progress for the continue-reading position: the first unread chapter at or
     * after the last read chapter number.
     */
    fun resumeChapterNumber(matches: List<ChapterMatch>): Double? {
        val lastRead = matches
            .filter { it.original.read }
            .mapNotNull { it.original.number }
            .maxOrNull() ?: return null
        return matches
            .mapNotNull { it.replacement?.number }
            .filter { it > lastRead }
            .minOrNull()
    }

    fun normalizeChapterTitle(title: String): String {
        return title.lowercase()
            .replace(Regex("chapter|ch\\.?|chapitre|capitulo|episodio|episode|vol\\.?|volume|\\bpt\\.?\\b"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }
}
