package eu.kanade.tachiyomi.smartsource.model

import kotlinx.serialization.Serializable

enum class HealthStatus {
    HEALTHY,
    UPDATE_AVAILABLE,
    TEMPORARILY_UNAVAILABLE,
    AUTHENTICATION_REQUIRED,
    OUTDATED,
    INCOMPATIBLE,
    REPOSITORY_MISSING,
    BROKEN,
    UNKNOWN,
}

@Serializable
data class HealthRecord(
    val key: String,
    val status: HealthStatus = HealthStatus.UNKNOWN,
    val consecutiveFailures: Int = 0,
    val totalChecks: Int = 0,
    val totalFailures: Int = 0,
    val lastCheckedAt: Long = 0L,
    val lastSuccessAt: Long = 0L,
    val lastFailureAt: Long = 0L,
    val lastError: String? = null,
    val lastChapterSeenAt: Long = 0L,
    val updateAvailable: Boolean = false,
) {
    val successRate: Double
        get() = if (totalChecks == 0) 1.0 else (totalChecks - totalFailures).toDouble() / totalChecks

    fun afterSuccess(now: Long, chapterSeenAt: Long? = null): HealthRecord {
        return copy(
            status = if (updateAvailable) HealthStatus.UPDATE_AVAILABLE else HealthStatus.HEALTHY,
            consecutiveFailures = 0,
            totalChecks = totalChecks + 1,
            lastCheckedAt = now,
            lastSuccessAt = now,
            lastError = null,
            lastChapterSeenAt = maxOf(lastChapterSeenAt, chapterSeenAt ?: 0L),
        )
    }

    fun afterFailure(
        now: Long,
        error: String,
        failureStatus: HealthStatus,
        brokenThreshold: Int = DEFAULT_BROKEN_THRESHOLD,
    ): HealthRecord {
        val failures = consecutiveFailures + 1
        val status = when {
            failureStatus == HealthStatus.AUTHENTICATION_REQUIRED -> HealthStatus.AUTHENTICATION_REQUIRED
            failureStatus == HealthStatus.INCOMPATIBLE -> HealthStatus.INCOMPATIBLE
            failureStatus == HealthStatus.REPOSITORY_MISSING -> HealthStatus.REPOSITORY_MISSING
            failureStatus == HealthStatus.OUTDATED -> HealthStatus.OUTDATED
            failures >= brokenThreshold -> HealthStatus.BROKEN
            else -> HealthStatus.TEMPORARILY_UNAVAILABLE
        }
        return copy(
            status = status,
            consecutiveFailures = failures,
            totalChecks = totalChecks + 1,
            totalFailures = totalFailures + 1,
            lastCheckedAt = now,
            lastFailureAt = now,
            lastError = error,
        )
    }

    fun withUpdateAvailable(available: Boolean): HealthRecord {
        return copy(
            updateAvailable = available,
            status = when {
                available -> HealthStatus.UPDATE_AVAILABLE
                status == HealthStatus.UPDATE_AVAILABLE -> HealthStatus.HEALTHY
                else -> status
            },
        )
    }

    companion object {
        const val DEFAULT_BROKEN_THRESHOLD = 4

        fun unknown(key: String) = HealthRecord(key = key)

        fun sourceKey(sourceId: Long) = "source:$sourceId"
        fun extensionKey(pkgName: String) = "ext:$pkgName"
        fun repoKey(indexUrl: String) = "repo:$indexUrl"
    }
}

enum class Confidence {
    HIGH,
    UNCERTAIN,
}

data class IdentitySignals(
    val titleSimilarity: Double,
    val normalizedTitleMatch: Boolean = false,
    val authorMatch: Boolean? = null,
    val yearMatch: Boolean? = null,
    val descriptionSimilarity: Double? = null,
    val sameExternalId: Boolean = false,
)

data class CandidateMetrics(
    val sourceId: Long,
    val sourceName: String,
    val candidateTitle: String,
    val identity: IdentitySignals,
    val candidateChapterCount: Int,
    val originalChapterCount: Int,
    val latestChapterNumber: Double?,
    val originalLatestChapterNumber: Double?,
    val daysSinceLastUpdate: Int?,
    val preferredLanguage: Boolean,
    val reliability: Double,
)

data class ScoreFactor(
    val name: String,
    val weight: Double,
    val score: Double,
    val explanation: String,
) {
    val weighted: Double get() = weight * score
}

data class RankedCandidate(
    val metrics: CandidateMetrics,
    val totalScore: Double,
    val factors: List<ScoreFactor>,
    val confidence: Confidence,
    val reasons: List<String>,
)
