package eu.kanade.tachiyomi.smartsource.health

import eu.kanade.tachiyomi.smartsource.model.HealthStatus
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

data class Classification(
    val status: HealthStatus,
    val retryable: Boolean,
    val reason: String,
)

/**
 * Classifies source and repository failures into health statuses.
 *
 * A single failure is never proof that a source is broken: callers must accumulate
 * observations and only escalate to [HealthStatus.BROKEN] after repeated failures.
 */
object ErrorClassifier {

    fun classifyHttp(code: Int, target: Target = Target.SOURCE): Classification = when {
        code == 401 || code == 403 -> Classification(
            HealthStatus.AUTHENTICATION_REQUIRED,
            retryable = false,
            reason = "HTTP $code: authentication or permission required",
        )
        code == 404 || code == 410 -> if (target == Target.REPOSITORY) {
            Classification(
                HealthStatus.REPOSITORY_MISSING,
                retryable = false,
                reason = "HTTP $code: repository index is gone",
            )
        } else {
            Classification(HealthStatus.TEMPORARILY_UNAVAILABLE, retryable = true, reason = "HTTP $code: not found")
        }
        code == 429 -> Classification(
            HealthStatus.TEMPORARILY_UNAVAILABLE,
            retryable = true,
            reason = "HTTP $code: rate limited",
        )
        code in 500..599 -> Classification(
            HealthStatus.TEMPORARILY_UNAVAILABLE,
            retryable = true,
            reason = "HTTP $code: server error",
        )
        else -> Classification(
            HealthStatus.TEMPORARILY_UNAVAILABLE,
            retryable = true,
            reason = "HTTP $code",
        )
    }

    fun classifyException(error: Throwable, target: Target = Target.SOURCE): Classification = when (error) {
        is UnknownHostException -> if (target == Target.REPOSITORY) {
            Classification(
                HealthStatus.REPOSITORY_MISSING,
                retryable = true,
                reason = "Repository host could not be resolved",
            )
        } else {
            Classification(
                HealthStatus.TEMPORARILY_UNAVAILABLE,
                retryable = true,
                reason = "Host could not be resolved",
            )
        }
        is SocketTimeoutException -> Classification(
            HealthStatus.TEMPORARILY_UNAVAILABLE,
            retryable = true,
            reason = "The request timed out",
        )
        is IOException -> Classification(
            HealthStatus.TEMPORARILY_UNAVAILABLE,
            retryable = true,
            reason = error.message ?: "Network error",
        )
        else -> {
            val message = error.message.orEmpty()
            val code = HTTP_CODE_REGEX.find(message)?.groupValues?.get(1)?.toIntOrNull()
            if (code != null) {
                classifyHttp(code, target)
            } else {
                Classification(
                    HealthStatus.TEMPORARILY_UNAVAILABLE,
                    retryable = true,
                    reason = message.ifBlank { error::class.java.simpleName },
                )
            }
        }
    }

    /**
     * Resolves the final status after [consecutiveFailures] observations.
     * Transient problems only become [HealthStatus.BROKEN] after [brokenThreshold] failures.
     */
    fun resolveStatus(
        consecutiveFailures: Int,
        lastClassification: Classification?,
        brokenThreshold: Int = 4,
    ): HealthStatus {
        val status = lastClassification?.status ?: HealthStatus.UNKNOWN
        return when {
            status != HealthStatus.TEMPORARILY_UNAVAILABLE -> status
            consecutiveFailures >= brokenThreshold -> HealthStatus.BROKEN
            else -> HealthStatus.TEMPORARILY_UNAVAILABLE
        }
    }

    /**
     * Suggests a backoff in milliseconds for the next attempt, doubling per consecutive failure.
     */
    fun backoffMillis(
        consecutiveFailures: Int,
        baseMillis: Long = 30_000L,
        maxMillis: Long = 6 * 60 * 60 * 1000L,
    ): Long {
        if (consecutiveFailures <= 0) return baseMillis
        val shift = consecutiveFailures.coerceAtMost(8)
        return (baseMillis shl shift).coerceAtMost(maxMillis)
    }

    enum class Target { SOURCE, REPOSITORY }

    private val HTTP_CODE_REGEX = Regex("HTTP[= ](\\d{3})")
}
