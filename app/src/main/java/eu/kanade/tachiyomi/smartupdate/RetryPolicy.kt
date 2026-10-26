package eu.kanade.tachiyomi.smartupdate

import eu.kanade.tachiyomi.smartsource.health.Classification
import eu.kanade.tachiyomi.smartsource.health.ErrorClassifier
import eu.kanade.tachiyomi.smartsource.model.HealthStatus

/**
 * Bounded retries with exponential backoff and jitter.
 *
 * Transient failures are retried; authentication problems, incompatible extensions,
 * and missing repositories are reported without aggressive retrying.
 */
object RetryPolicy {

    const val MAX_ATTEMPTS = 5
    const val BASE_BACKOFF_MS = 30 * 60 * 1000L
    const val MAX_BACKOFF_MS = 24 * 60 * 60 * 1000L

    fun isPermanent(status: HealthStatus): Boolean = when (status) {
        HealthStatus.AUTHENTICATION_REQUIRED,
        HealthStatus.INCOMPATIBLE,
        HealthStatus.REPOSITORY_MISSING,
        -> true
        else -> false
    }

    fun classify(error: Throwable): Classification = ErrorClassifier.classifyException(error)

    fun shouldRetry(classification: Classification, consecutiveFailures: Int): Boolean {
        return !isPermanent(classification.status) && consecutiveFailures < MAX_ATTEMPTS
    }

    /**
     * Backoff for the next attempt: exponential from [consecutiveFailures], capped at
     * [MAX_BACKOFF_MS], with up to 25% jitter so retries do not synchronize.
     */
    fun backoffMillis(consecutiveFailures: Int, jitterSeed: Long = 0L): Long {
        val shift = consecutiveFailures.coerceIn(0, 10)
        val base = (BASE_BACKOFF_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
        val jitter = if (base >= MAX_BACKOFF_MS) 0 else (jitterSeed and JITTER_MASK) % (base / 4 + 1)
        return (base + jitter).coerceAtMost(MAX_BACKOFF_MS)
    }

    private const val JITTER_MASK = 0x7FFF_FFFFL
}
