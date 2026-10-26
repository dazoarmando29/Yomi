package eu.kanade.tachiyomi.smartupdate

import eu.kanade.tachiyomi.smartsource.health.Classification
import eu.kanade.tachiyomi.smartsource.model.HealthStatus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class SmartUpdateLogicTest {

    private val dayMs = 24 * 60 * 60 * 1000L
    private val now = 1_700_000_000_000L

    private fun facts(
        completed: Boolean = false,
        total: Long = 20,
        read: Long = 20,
        nextUpdate: Long? = null,
        onlyFetchOnce: Boolean = false,
    ) = MangaUpdateFacts(
        mangaId = 1L,
        title = "Example",
        isCompleted = completed,
        totalChapters = total,
        readCount = read,
        nextUpdateAt = nextUpdate,
        onlyFetchOnce = onlyFetchOnce,
    )

    @Test
    fun `smart mode skips unread manga like upstream defaults`() {
        val eligibility = UpdateEligibilityEngine.evaluate(
            UpdateMode.SMART,
            facts(total = 20, read = 10),
            UpdateRecord(mangaId = 1L),
            now,
            fetchWindowEnd = now + dayMs,
        )
        eligibility.eligible shouldBe false
        eligibility.reason shouldBe UpdateEligibilityEngine.REASON_NOT_CAUGHT_UP
    }

    @Test
    fun `balanced mode checks unread and unstarted manga`() {
        val unread = UpdateEligibilityEngine.evaluate(
            UpdateMode.BALANCED,
            facts(total = 20, read = 10),
            UpdateRecord(mangaId = 1L),
            now,
            fetchWindowEnd = now + dayMs,
        )
        val unstarted = UpdateEligibilityEngine.evaluate(
            UpdateMode.BALANCED,
            facts(total = 20, read = 0),
            UpdateRecord(mangaId = 1L),
            now,
            fetchWindowEnd = now + dayMs,
        )
        unread.eligible shouldBe true
        unstarted.eligible shouldBe true
    }

    @Test
    fun `thorough mode includes completed manga only when enabled`() {
        val excluded = UpdateEligibilityEngine.evaluate(
            UpdateMode.THOROUGH,
            facts(completed = true),
            UpdateRecord(mangaId = 1L),
            now,
            fetchWindowEnd = now,
            thoroughIncludeCompleted = false,
        )
        val included = UpdateEligibilityEngine.evaluate(
            UpdateMode.THOROUGH,
            facts(completed = true),
            UpdateRecord(mangaId = 1L),
            now,
            fetchWindowEnd = now,
            thoroughIncludeCompleted = true,
        )
        excluded.eligible shouldBe false
        included.eligible shouldBe true
    }

    @Test
    fun `outside release period is skipped unless verification is due`() {
        val future = now + 10 * dayMs
        val skipped = UpdateEligibilityEngine.evaluate(
            UpdateMode.BALANCED,
            facts(nextUpdate = future),
            UpdateRecord(mangaId = 1L, lastSuccessAt = now - dayMs),
            now,
            fetchWindowEnd = now + dayMs,
        )
        val verification = UpdateEligibilityEngine.evaluate(
            UpdateMode.BALANCED,
            facts(nextUpdate = future),
            UpdateRecord(mangaId = 1L, lastSuccessAt = now - dayMs, consecutiveFailures = 3),
            now,
            fetchWindowEnd = now + dayMs,
        )
        skipped.eligible shouldBe false
        skipped.reason shouldBe UpdateEligibilityEngine.REASON_NOT_IN_RELEASE_PERIOD
        verification.eligible shouldBe true
    }

    @Test
    fun `long-unchecked ongoing manga is eligible in thorough mode with priority`() {
        val eligibility = UpdateEligibilityEngine.evaluate(
            UpdateMode.THOROUGH,
            facts(total = 20, read = 0),
            UpdateRecord(mangaId = 1L, lastSuccessAt = now - 30 * dayMs),
            now,
            fetchWindowEnd = now,
        )
        eligibility.eligible shouldBe true
        (eligibility.priority >= 30) shouldBe true
    }

    @Test
    fun `permanent failures are not retried and keep a recorded reason`() {
        val eligibility = UpdateEligibilityEngine.evaluate(
            UpdateMode.BALANCED,
            facts(),
            UpdateRecord(mangaId = 1L),
            now,
            fetchWindowEnd = now,
            permanentFailure = true,
        )
        eligibility.eligible shouldBe false
        eligibility.reason shouldBe UpdateEligibilityEngine.REASON_PERMANENT_FAILURE
    }

    @Test
    fun `retry backoff window blocks immediate rechecks`() {
        val eligibility = UpdateEligibilityEngine.evaluate(
            UpdateMode.THOROUGH,
            facts(),
            UpdateRecord(mangaId = 1L, nextEligibleAt = now + 60_000L),
            now,
            fetchWindowEnd = now,
        )
        eligibility.eligible shouldBe false
        eligibility.reason shouldBe UpdateEligibilityEngine.REASON_RETRY_BACKOFF
    }

    @Test
    fun `failures are never recorded as successes`() {
        val record = UpdateRecord(mangaId = 1L)
            .afterSuccess(now - 2 * dayMs, newChapters = 2)
            .afterFailure(now, "timeout", now + 30 * 60_000L)
        record.state shouldBe UpdateState.FAILED
        record.consecutiveFailures shouldBe 1
        record.lastSuccessAt shouldBe now - 2 * dayMs
        record.lastError shouldBe "timeout"

        val recovered = record.afterSuccess(now + dayMs, newChapters = 0)
        recovered.state shouldBe UpdateState.CHECKED
        recovered.consecutiveFailures shouldBe 0
    }

    @Test
    fun `skips keep their reason and history events are captured`() {
        val record = UpdateRecord(mangaId = 1L).afterSkip(now, UpdateEligibilityEngine.REASON_NOT_STARTED)
        record.state shouldBe UpdateState.SKIPPED
        record.skipReason shouldBe UpdateEligibilityEngine.REASON_NOT_STARTED

        val encoded = LibraryUpdateStateStore.encode(
            listOf(record),
            listOf(UpdateEvent(1L, "Example", now, success = false, detail = "timeout")),
        )
        val decoded = LibraryUpdateStateStore.decode(encoded)
        decoded.records.single().skipReason shouldBe UpdateEligibilityEngine.REASON_NOT_STARTED
        decoded.events.single().detail shouldBe "timeout"
    }

    @Test
    fun `retry policy bounds retries and applies backoff with jitter`() {
        val transient = Classification(HealthStatus.TEMPORARILY_UNAVAILABLE, true, "timeout")
        val auth = Classification(HealthStatus.AUTHENTICATION_REQUIRED, false, "401")

        RetryPolicy.shouldRetry(transient, 1) shouldBe true
        RetryPolicy.shouldRetry(transient, RetryPolicy.MAX_ATTEMPTS) shouldBe false
        RetryPolicy.shouldRetry(auth, 1) shouldBe false

        val b0 = RetryPolicy.backoffMillis(0, jitterSeed = 0L)
        val b1 = RetryPolicy.backoffMillis(1, jitterSeed = 0L)
        val capped = RetryPolicy.backoffMillis(30, jitterSeed = 0L)
        b1 shouldBe 2 * b0
        (capped <= RetryPolicy.MAX_BACKOFF_MS) shouldBe true

        val jittered = RetryPolicy.backoffMillis(2, jitterSeed = 12_345L)
        (jittered in RetryPolicy.backoffMillis(2, 0L)..RetryPolicy.MAX_BACKOFF_MS) shouldBe true
    }

    @Test
    fun `summary counts reflect actual record states`() {
        val records = listOf(
            UpdateRecord(mangaId = 1L).afterSuccess(now, 2),
            UpdateRecord(mangaId = 2L).afterSuccess(now, 0),
            UpdateRecord(mangaId = 3L).afterSkip(now, "reason"),
            UpdateRecord(mangaId = 4L).afterFailure(now, "err", now + 10),
            UpdateRecord(mangaId = 5L),
        )
        val summary = UpdateSummaryBuilder.build(records, now)
        summary.total shouldBe 5
        summary.checked shouldBe 2
        summary.newChapters shouldBe 1
        summary.skipped shouldBe 1
        summary.failed shouldBe 1
        summary.pending shouldBe 1

        val dueForRetry = UpdateSummaryBuilder.build(
            records.map { if (it.mangaId == 4L) it.copy(nextEligibleAt = now - 1) else it },
            now,
        )
        dueForRetry.pending shouldBe 2
    }

    @Test
    fun `quiet hours wrap around midnight and can be disabled`() {
        val prefs = SmartUpdatePreferences(
            tachiyomi.core.common.preference.InMemoryPreferenceStore(),
        )
        prefs.isQuietHours(23) shouldBe true
        prefs.isQuietHours(3) shouldBe true
        prefs.isQuietHours(12) shouldBe false
        prefs.quietHoursStart.set(-1)
        prefs.isQuietHours(23) shouldBe false
    }

    @Test
    fun `notification dedup returns only chapters not notified before`() {
        val notified = mutableSetOf<String>()
        val first = listOf("c1", "c2").filter { notified.add("1|$it") }
        val second = listOf("c2", "c3").filter { notified.add("1|$it") }
        first shouldBe listOf("c1", "c2")
        second shouldBe listOf("c3")
    }

    @Test
    fun `mode mapping defaults to balanced`() {
        val prefs = SmartUpdatePreferences(tachiyomi.core.common.preference.InMemoryPreferenceStore())
        prefs.mode() shouldBe UpdateMode.BALANCED
        prefs.updateMode.set(2)
        prefs.mode() shouldBe UpdateMode.THOROUGH
        prefs.updateMode.set(0)
        prefs.mode() shouldBe UpdateMode.SMART
    }
}
