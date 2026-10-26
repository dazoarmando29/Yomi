package eu.kanade.tachiyomi.smartsource.ranking

import eu.kanade.tachiyomi.smartsource.model.CandidateMetrics
import eu.kanade.tachiyomi.smartsource.model.Confidence
import eu.kanade.tachiyomi.smartsource.model.IdentitySignals
import eu.kanade.tachiyomi.smartsource.model.RankedCandidate
import eu.kanade.tachiyomi.smartsource.model.ScoreFactor
import kotlin.math.abs
import kotlin.math.max

/**
 * Explainable weighted ranking for replacement sources.
 *
 * The weights are initial design values, not verified reliability measurements:
 * identity 35%, chapter coverage 25%, source reliability 20%, update recency 10%,
 * preferred language 10%.
 */
object ReplacementRanker {

    const val WEIGHT_IDENTITY = 0.35
    const val WEIGHT_CHAPTERS = 0.25
    const val WEIGHT_RELIABILITY = 0.20
    const val WEIGHT_RECENCY = 0.10
    const val WEIGHT_LANGUAGE = 0.10

    /** A title similarity below this can never produce a high-confidence match. */
    const val MIN_HIGH_CONFIDENCE_TITLE_SIMILARITY = 0.85

    /** Identity score needed for high confidence. */
    const val MIN_HIGH_CONFIDENCE_IDENTITY = 0.75

    fun rank(candidates: List<CandidateMetrics>): List<RankedCandidate> {
        return candidates
            .map { it.score() }
            .sortedByDescending { it.totalScore }
    }

    fun identityScore(identity: IdentitySignals): Double {
        var score = identity.titleSimilarity * 0.6
        if (identity.normalizedTitleMatch) score += 0.15
        if (identity.sameExternalId) score += 0.15
        if (identity.authorMatch == true) score += 0.1
        if (identity.authorMatch == false) score -= 0.2
        if (identity.yearMatch == true) score += 0.05
        identity.descriptionSimilarity?.let { score += it * 0.05 }
        return score.coerceIn(0.0, 1.0)
    }

    private fun CandidateMetrics.score(): RankedCandidate {
        val identityValue = identityScore(identity)

        val chapterScore = if (originalChapterCount <= 0 && originalLatestChapterNumber == null) {
            0.5
        } else {
            val byCount = if (originalChapterCount > 0) {
                (candidateChapterCount.toDouble() / originalChapterCount).coerceIn(0.0, 1.0)
            } else {
                0.5
            }
            val byLatest = if (originalLatestChapterNumber != null && latestChapterNumber != null &&
                originalLatestChapterNumber > 0.0
            ) {
                (latestChapterNumber / originalLatestChapterNumber).coerceIn(0.0, 1.0)
            } else {
                byCount
            }
            (byCount * 0.5 + byLatest * 0.5)
        }

        val reliabilityScore = reliability.coerceIn(0.0, 1.0)

        val recencyScore = when {
            daysSinceLastUpdate == null -> 0.5
            daysSinceLastUpdate <= 7 -> 1.0
            daysSinceLastUpdate <= 30 -> 0.75
            daysSinceLastUpdate <= 90 -> 0.5
            daysSinceLastUpdate <= 365 -> 0.25
            else -> 0.0
        }

        val languageScore = if (preferredLanguage) 1.0 else 0.5

        val factors = listOf(
            ScoreFactor(
                name = "Manga identity",
                weight = WEIGHT_IDENTITY,
                score = identityValue,
                explanation = identityExplanation(identityValue),
            ),
            ScoreFactor(
                name = "Chapter coverage",
                weight = WEIGHT_CHAPTERS,
                score = chapterScore,
                explanation = "$candidateChapterCount chapters vs $originalChapterCount in the original",
            ),
            ScoreFactor(
                name = "Source reliability",
                weight = WEIGHT_RELIABILITY,
                score = reliabilityScore,
                explanation = "Recent checks succeeded ${"%.0f".format(reliabilityScore * 100)}% of the time",
            ),
            ScoreFactor(
                name = "Update recency",
                weight = WEIGHT_RECENCY,
                score = recencyScore,
                explanation = when (daysSinceLastUpdate) {
                    null -> "No update recency information"
                    else -> "Last update $daysSinceLastUpdate day(s) ago"
                },
            ),
            ScoreFactor(
                name = "Language",
                weight = WEIGHT_LANGUAGE,
                score = languageScore,
                explanation = if (preferredLanguage) "Matches your preferred language" else "Different language",
            ),
        )

        val total = factors.sumOf { it.weighted }

        val confidence = when {
            identityValue >= MIN_HIGH_CONFIDENCE_IDENTITY &&
                titleSimilarityOf(this) >= MIN_HIGH_CONFIDENCE_TITLE_SIMILARITY &&
                identity.authorMatch != false -> Confidence.HIGH
            else -> Confidence.UNCERTAIN
        }

        val reasons = buildList {
            add(factors.maxBy { it.weighted }.explanation)
            if (candidateChapterCount > originalChapterCount) {
                add("Has more chapters than the original ($candidateChapterCount > $originalChapterCount)")
            } else if (originalChapterCount > 0 && candidateChapterCount < originalChapterCount * 0.8) {
                add("Fewer available chapters than the original")
            }
            if (confidence == Confidence.UNCERTAIN) {
                add("Title match needs manual verification")
            }
        }

        return RankedCandidate(
            metrics = this,
            totalScore = total,
            factors = factors,
            confidence = confidence,
            reasons = reasons,
        )
    }

    private fun titleSimilarityOf(metrics: CandidateMetrics): Double = metrics.identity.titleSimilarity

    private fun identityExplanation(identityScore: Double): String = when {
        identityScore >= 0.9 -> "Strong identity match"
        identityScore >= MIN_HIGH_CONFIDENCE_IDENTITY -> "Good identity match"
        identityScore >= 0.5 -> "Possible identity match"
        else -> "Uncertain title match"
    }

    /**
     * Normalized similarity of two titles: cheap and deterministic (no ML dependency).
     * Uses the better of token overlap and a normalized character similarity.
     */
    fun titleSimilarity(original: String, candidate: String): Double {
        val a = normalizeTitle(original)
        val b = normalizeTitle(candidate)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val tokenScore = tokenSimilarity(a, b)
        val charScore = normalizedLevenshtein(a, b)
        return max(tokenScore, charScore)
    }

    fun normalizeTitle(title: String): String {
        return title.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun tokenSimilarity(a: String, b: String): Double {
        val ta = a.split(' ').toSet()
        val tb = b.split(' ').toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val common = ta.intersect(tb).size
        return 2.0 * common / (ta.size + tb.size)
    }

    fun normalizedLevenshtein(a: String, b: String): Double {
        if (a == b) return 1.0
        val maxLen = max(a.length, b.length)
        if (maxLen == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / maxLen
    }

    private fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(current[j] + 1, prev[j + 1] + 1, prev[j] + cost)
            }
            for (j in current.indices) prev[j] = current[j]
        }
        return prev[b.length]
    }

    /** Coverage ratio helper used by tests and previews. */
    fun coverageRatio(originalChapterCount: Int, candidateChapterCount: Int): Double {
        if (originalChapterCount <= 0) return 1.0
        return (candidateChapterCount.toDouble() / originalChapterCount).coerceIn(0.0, 1.0)
    }
}
