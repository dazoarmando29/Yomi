package eu.kanade.tachiyomi.smartupdate

/**
 * Decides whether a manga should be checked by the library updater and why not when skipped.
 *
 * Every skip carries a recorded reason; nothing is ever silently dropped. SMART mode mirrors
 * Mihon's standard restrictions. BALANCED also checks unread and unstarted manga. THOROUGH
 * aims for complete coverage and prioritizes titles that have not been checked recently.
 * All modes keep safeguards against indefinite skipping: an ongoing series whose predicted
 * release repeatedly passes without a successful check becomes eligible for verification.
 */
object UpdateEligibilityEngine {

    const val REASON_ELIGIBLE = "eligible"
    const val REASON_ONLY_FETCH_ONCE = "skipped_reason_not_always_update"
    const val REASON_COMPLETED = "skipped_reason_completed"
    const val REASON_NOT_CAUGHT_UP = "skipped_reason_not_caught_up"
    const val REASON_NOT_STARTED = "skipped_reason_not_started"
    const val REASON_NOT_IN_RELEASE_PERIOD = "skipped_reason_not_in_release_period"
    const val REASON_RETRY_BACKOFF = "yomi_skip_retry_backoff"
    const val REASON_PERMANENT_FAILURE = "yomi_skip_permanent_failure"
    const val REASON_RECENTLY_CHECKED = "yomi_skip_recently_checked"

    /** Ongoing series become eligible for verification after this many failed attempts. */
    const val VERIFICATION_FAILURE_THRESHOLD = 3

    /** Long-unchecked titles are always eligible in THOROUGH mode after this many days. */
    const val LONG_UNCHECKED_DAYS = 7L

    fun evaluate(
        mode: UpdateMode,
        facts: MangaUpdateFacts,
        record: UpdateRecord,
        now: Long,
        fetchWindowEnd: Long,
        thoroughIncludeCompleted: Boolean = false,
        permanentFailure: Boolean = false,
    ): Eligibility {
        if (permanentFailure) {
            return Eligibility.skip(REASON_PERMANENT_FAILURE)
        }

        if (facts.onlyFetchOnce && facts.totalChapters > 0) {
            return Eligibility.skip(REASON_ONLY_FETCH_ONCE)
        }

        val needsVerification = record.consecutiveFailures >= VERIFICATION_FAILURE_THRESHOLD ||
            (
                facts.isCompleted.not() && record.lastSuccessAt > 0 &&
                    now - record.lastSuccessAt > LONG_UNCHECKED_DAYS * DAY_MS * 2
                )

        when (mode) {
            UpdateMode.SMART -> {
                if (facts.isCompleted) return Eligibility.skip(REASON_COMPLETED)
                if (facts.hasUnread) return Eligibility.skip(REASON_NOT_CAUGHT_UP)
                if (facts.totalChapters > 0 && !facts.hasStarted) return Eligibility.skip(REASON_NOT_STARTED)
                val nextUpdate = facts.nextUpdateAt
                if (nextUpdate != null && nextUpdate > fetchWindowEnd && !needsVerification) {
                    return Eligibility.skip(REASON_NOT_IN_RELEASE_PERIOD)
                }
            }

            UpdateMode.BALANCED -> {
                if (facts.isCompleted) return Eligibility.skip(REASON_COMPLETED)
                val nextUpdate = facts.nextUpdateAt
                if (nextUpdate != null && nextUpdate > fetchWindowEnd && !needsVerification) {
                    return Eligibility.skip(REASON_NOT_IN_RELEASE_PERIOD)
                }
            }

            UpdateMode.THOROUGH -> {
                if (facts.isCompleted && !thoroughIncludeCompleted) return Eligibility.skip(REASON_COMPLETED)
            }
        }

        if (record.nextEligibleAt > now) {
            return Eligibility.skip(REASON_RETRY_BACKOFF)
        }

        if (mode == UpdateMode.THOROUGH) {
            val uncheckedFor = if (record.lastSuccessAt > 0) now - record.lastSuccessAt else Long.MAX_VALUE
            return Eligibility.check(priority = (uncheckedFor / DAY_MS).toInt().coerceAtMost(10_000))
        }

        return Eligibility.check(priority = if (needsVerification) 1 else 0)
    }

    private const val DAY_MS = 24 * 60 * 60 * 1000L
}
