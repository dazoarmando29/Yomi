package eu.kanade.tachiyomi.smartsource

import eu.kanade.tachiyomi.smartsource.health.Classification
import eu.kanade.tachiyomi.smartsource.health.ErrorClassifier
import eu.kanade.tachiyomi.smartsource.model.HealthRecord
import eu.kanade.tachiyomi.smartsource.model.HealthStatus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@Execution(ExecutionMode.CONCURRENT)
class ErrorClassifierTest {

    @Test
    fun `authentication failures map to auth required and are not retryable`() {
        ErrorClassifier.classifyHttp(401).status shouldBe HealthStatus.AUTHENTICATION_REQUIRED
        ErrorClassifier.classifyHttp(403).retryable shouldBe false
    }

    @Test
    fun `missing repository maps to repository missing while source 404 stays temporary`() {
        ErrorClassifier.classifyHttp(404, ErrorClassifier.Target.REPOSITORY).status shouldBe
            HealthStatus.REPOSITORY_MISSING
        ErrorClassifier.classifyHttp(404, ErrorClassifier.Target.SOURCE).status shouldBe
            HealthStatus.TEMPORARILY_UNAVAILABLE
    }

    @Test
    fun `server errors and rate limits are temporary and retryable`() {
        ErrorClassifier.classifyHttp(500).retryable shouldBe true
        ErrorClassifier.classifyHttp(429).status shouldBe HealthStatus.TEMPORARILY_UNAVAILABLE
    }

    @Test
    fun `network exceptions classify as temporary while unknown hosts on repos are missing`() {
        ErrorClassifier.classifyException(SocketTimeoutException("timeout")).retryable shouldBe true
        ErrorClassifier.classifyException(IOException("reset")).status shouldBe
            HealthStatus.TEMPORARILY_UNAVAILABLE
        ErrorClassifier.classifyException(UnknownHostException("gone.example"), ErrorClassifier.Target.REPOSITORY)
            .status shouldBe HealthStatus.REPOSITORY_MISSING
    }

    @Test
    fun `message with http code classifies without the exception type`() {
        val classification = ErrorClassifier.classifyException(RuntimeException("HTTP 403"))
        classification.status shouldBe HealthStatus.AUTHENTICATION_REQUIRED
    }

    @Test
    fun `a single failure never resolves to broken`() {
        val transient = Classification(HealthStatus.TEMPORARILY_UNAVAILABLE, true, "timeout")
        ErrorClassifier.resolveStatus(1, transient) shouldBe HealthStatus.TEMPORARILY_UNAVAILABLE
        ErrorClassifier.resolveStatus(3, transient) shouldBe HealthStatus.TEMPORARILY_UNAVAILABLE
        ErrorClassifier.resolveStatus(4, transient) shouldBe HealthStatus.BROKEN
    }

    @Test
    fun `backoff grows exponentially and is capped`() {
        ErrorClassifier.backoffMillis(0) shouldBe 30_000L
        ErrorClassifier.backoffMillis(1) shouldBe 60_000L
        ErrorClassifier.backoffMillis(2) shouldBe 120_000L
        val capped = ErrorClassifier.backoffMillis(20)
        (capped <= 6 * 60 * 60 * 1000L) shouldBe true
    }

    @Test
    fun `health record accumulates failures before becoming broken`() {
        var record = HealthRecord.unknown("source:1")
        record = record.afterFailure(1L, "timeout", HealthStatus.TEMPORARILY_UNAVAILABLE)
        record.status shouldBe HealthStatus.TEMPORARILY_UNAVAILABLE
        record = record.afterFailure(2L, "timeout", HealthStatus.TEMPORARILY_UNAVAILABLE)
        record = record.afterFailure(3L, "timeout", HealthStatus.TEMPORARILY_UNAVAILABLE)
        record.status shouldBe HealthStatus.TEMPORARILY_UNAVAILABLE
        record = record.afterFailure(4L, "timeout", HealthStatus.TEMPORARILY_UNAVAILABLE)
        record.status shouldBe HealthStatus.BROKEN
        record.consecutiveFailures shouldBe 4
    }

    @Test
    fun `success resets failures and marks healthy`() {
        var record = HealthRecord.unknown("source:1")
        record = record.afterFailure(1L, "timeout", HealthStatus.TEMPORARILY_UNAVAILABLE)
        record = record.afterSuccess(2L, chapterSeenAt = 99L)
        record.status shouldBe HealthStatus.HEALTHY
        record.consecutiveFailures shouldBe 0
        record.lastChapterSeenAt shouldBe 99L
    }

    @Test
    fun `incompatible and auth failures do not wait for a threshold`() {
        HealthRecord.unknown("ext:a")
            .afterFailure(1L, "lib", HealthStatus.INCOMPATIBLE)
            .status shouldBe HealthStatus.INCOMPATIBLE
        HealthRecord.unknown("source:b")
            .afterFailure(1L, "401", HealthStatus.AUTHENTICATION_REQUIRED)
            .status shouldBe HealthStatus.AUTHENTICATION_REQUIRED
    }

    @Test
    fun `update available flag survives success and restores healthy when resolved`() {
        var record = HealthRecord.unknown("ext:a").withUpdateAvailable(true)
        record.status shouldBe HealthStatus.UPDATE_AVAILABLE
        record = record.afterSuccess(5L)
        record.status shouldBe HealthStatus.UPDATE_AVAILABLE
        record = record.withUpdateAvailable(false)
        record.status shouldBe HealthStatus.HEALTHY
    }

    @Test
    fun `serialization round trip keeps records`() {
        val records = listOf(
            HealthRecord.unknown("source:1").afterSuccess(10L),
            HealthRecord.unknown("repo:https://x").afterFailure(11L, "HTTP 404", HealthStatus.REPOSITORY_MISSING),
        )
        val decoded = eu.kanade.tachiyomi.smartsource.health.SourceHealthStore.decode(
            eu.kanade.tachiyomi.smartsource.health.SourceHealthStore.encode(records),
        )
        decoded shouldBe records
    }

    @Test
    fun `classification reason is human readable`() {
        val classification = ErrorClassifier.classifyException(IOException("connection reset"))
        classification.shouldBeInstanceOf<Classification>()
        classification.reason shouldBe "connection reset"
    }
}
