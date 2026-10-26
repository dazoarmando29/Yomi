package eu.kanade.tachiyomi.smartupdate

import kotlinx.serialization.Serializable

enum class UpdateMode {
    /** Mihon's predictive release scheduling with all standard skip restrictions. */
    SMART,

    /** Checks unread and unstarted manga too; recommended default. */
    BALANCED,

    /** Near-complete coverage; optionally includes completed manga. */
    THOROUGH,
}

enum class UpdateState {
    NEVER_CHECKED,
    CHECKED,
    NEW_CHAPTERS,
    SKIPPED,
    FAILED,
    PENDING,
}

@Serializable
data class UpdateRecord(
    val mangaId: Long,
    val title: String = "",
    val state: UpdateState = UpdateState.NEVER_CHECKED,
    val lastAttemptAt: Long = 0L,
    val lastSuccessAt: Long = 0L,
    val lastNewChapterAt: Long = 0L,
    val consecutiveFailures: Int = 0,
    val nextEligibleAt: Long = 0L,
    val lastError: String? = null,
    val skipReason: String? = null,
    val chaptersFound: Int = 0,
) {
    fun afterSuccess(now: Long, newChapters: Int, nextEligibleAt: Long = 0L): UpdateRecord {
        return copy(
            state = if (newChapters > 0) UpdateState.NEW_CHAPTERS else UpdateState.CHECKED,
            lastAttemptAt = now,
            lastSuccessAt = now,
            lastNewChapterAt = if (newChapters > 0) now else lastNewChapterAt,
            consecutiveFailures = 0,
            nextEligibleAt = nextEligibleAt,
            lastError = null,
            skipReason = null,
            chaptersFound = newChapters,
        )
    }

    fun afterFailure(now: Long, error: String, nextEligibleAt: Long): UpdateRecord {
        return copy(
            state = UpdateState.FAILED,
            lastAttemptAt = now,
            consecutiveFailures = consecutiveFailures + 1,
            nextEligibleAt = nextEligibleAt,
            lastError = error,
            skipReason = null,
        )
    }

    fun afterSkip(now: Long, reason: String): UpdateRecord {
        return copy(
            state = UpdateState.SKIPPED,
            lastAttemptAt = now,
            skipReason = reason,
        )
    }

    fun afterPending(): UpdateRecord = copy(state = UpdateState.PENDING)
}

@Serializable
data class UpdateEvent(
    val mangaId: Long,
    val title: String,
    val at: Long,
    val success: Boolean,
    val newChapters: Int = 0,
    val detail: String? = null,
)

@Serializable
data class UpdateHistory(val records: List<UpdateRecord> = emptyList(), val events: List<UpdateEvent> = emptyList())

data class MangaUpdateFacts(
    val mangaId: Long,
    val title: String,
    val isCompleted: Boolean,
    val totalChapters: Long,
    val readCount: Long,
    val nextUpdateAt: Long?,
    val onlyFetchOnce: Boolean,
) {
    val hasUnread: Boolean get() = totalChapters - readCount > 0
    val hasStarted: Boolean get() = readCount > 0
}

data class Eligibility(
    val eligible: Boolean,
    val reason: String,
    val priority: Int = 0,
) {
    companion object {
        fun skip(reason: String) = Eligibility(eligible = false, reason = reason)
        fun check(priority: Int = 0) = Eligibility(eligible = true, reason = "eligible", priority = priority)
    }
}

data class UpdateSummary(
    val total: Int,
    val checked: Int,
    val newChapters: Int,
    val pending: Int,
    val skipped: Int,
    val failed: Int,
    val lastSuccessAt: Long?,
    val nextPlannedAt: Long?,
)

object UpdateSummaryBuilder {
    fun build(records: List<UpdateRecord>, now: Long): UpdateSummary {
        return UpdateSummary(
            total = records.size,
            checked = records.count {
                it.state == UpdateState.CHECKED || it.state == UpdateState.NEW_CHAPTERS
            },
            newChapters = records.count { it.state == UpdateState.NEW_CHAPTERS },
            pending = records.count {
                it.state == UpdateState.PENDING ||
                    it.state == UpdateState.NEVER_CHECKED ||
                    (it.state == UpdateState.FAILED && it.nextEligibleAt <= now)
            },
            skipped = records.count { it.state == UpdateState.SKIPPED },
            failed = records.count { it.state == UpdateState.FAILED },
            lastSuccessAt = records.map { it.lastSuccessAt }.filter { it > 0 }.maxOrNull(),
            nextPlannedAt = records.map { it.nextEligibleAt }.filter { it > now }.minOrNull(),
        )
    }
}
