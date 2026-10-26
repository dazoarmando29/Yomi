package eu.kanade.tachiyomi.smartsource.health

import android.content.Context
import eu.kanade.tachiyomi.smartsource.model.HealthRecord
import eu.kanade.tachiyomi.smartsource.model.HealthStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class HealthHistory(val records: List<HealthRecord> = emptyList())

/**
 * Local, on-device health history for sources, extensions, and repositories.
 *
 * Stored as JSON in SharedPreferences (same pattern as DownloadStore). Nothing leaves
 * the device; no reading data is collected beyond the last observed chapter timestamp.
 */
class SourceHealthStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun getAll(): List<HealthRecord> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(HealthHistory.serializer(), raw).records
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun get(key: String): HealthRecord {
        return getAll().firstOrNull { it.key == key } ?: HealthRecord.unknown(key)
    }

    @Synchronized
    fun update(key: String, transform: (HealthRecord) -> HealthRecord) {
        val records = getAll().toMutableList()
        val index = records.indexOfFirst { it.key == key }
        val current = if (index >= 0) records[index] else HealthRecord.unknown(key)
        val updated = transform(current)
        if (index >= 0) records[index] = updated else records += updated
        save(records)
    }

    fun recordSuccess(key: String, now: Long, chapterSeenAt: Long? = null) {
        update(key) { it.afterSuccess(now, chapterSeenAt) }
    }

    fun recordFailure(
        key: String,
        now: Long,
        classification: Classification,
        brokenThreshold: Int = HealthRecord.DEFAULT_BROKEN_THRESHOLD,
    ) {
        update(key) {
            it.afterFailure(now, classification.reason, classification.status, brokenThreshold)
        }
    }

    fun recordUpdateAvailable(key: String, available: Boolean) {
        update(key) { it.withUpdateAvailable(available) }
    }

    fun recordIncompatible(key: String, now: Long, reason: String) {
        update(key) {
            it.afterFailure(now, reason, HealthStatus.INCOMPATIBLE)
        }
    }

    fun recordUnknown(key: String, now: Long, reason: String) {
        update(key) {
            it.copy(lastCheckedAt = now, lastError = reason)
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    private fun save(records: List<HealthRecord>) {
        prefs.edit()
            .putString(KEY_HISTORY, json.encodeToString(HealthHistory.serializer(), HealthHistory(records)))
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "smart_source_health"
        private const val KEY_HISTORY = "health_history"

        fun encode(records: List<HealthRecord>): String {
            return Json.encodeToString(HealthHistory.serializer(), HealthHistory(records))
        }

        fun decode(raw: String): List<HealthRecord> {
            return Json.decodeFromString(HealthHistory.serializer(), raw).records
        }
    }
}
