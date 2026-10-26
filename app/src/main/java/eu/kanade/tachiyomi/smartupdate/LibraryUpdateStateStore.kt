package eu.kanade.tachiyomi.smartupdate

import android.content.Context
import kotlinx.serialization.json.Json

/**
 * Persistent per-manga update state, events, and notification dedup watermarks.
 *
 * JSON in SharedPreferences (same pattern as DownloadStore / SourceHealthStore).
 * Failed checks are never recorded as successful, and every skip keeps its reason.
 */
class LibraryUpdateStateStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun getAll(): List<UpdateRecord> = decodeHistory(prefs.getString(KEY_STATE, null)).records

    @Synchronized
    fun get(mangaId: Long): UpdateRecord {
        return getAll().firstOrNull { it.mangaId == mangaId } ?: UpdateRecord(mangaId = mangaId)
    }

    @Synchronized
    fun update(mangaId: Long, title: String = "", transform: (UpdateRecord) -> UpdateRecord) {
        val history = decodeHistory(prefs.getString(KEY_STATE, null))
        val records = history.records.toMutableList()
        val index = records.indexOfFirst { it.mangaId == mangaId }
        val current = if (index >= 0) records[index] else UpdateRecord(mangaId = mangaId, title = title)
        val updated = transform(current).let {
            if (it.title.isEmpty() &&
                title.isNotEmpty()
            ) {
                it.copy(title = title)
            } else {
                it
            }
        }
        if (index >= 0) records[index] = updated else records += updated
        save(records, history.events)
    }

    @Synchronized
    fun recordEvent(event: UpdateEvent) {
        val history = decodeHistory(prefs.getString(KEY_STATE, null))
        val events = (history.events + event).takeLast(MAX_EVENTS)
        save(history.records, events)
    }

    @Synchronized
    fun getEvents(): List<UpdateEvent> = decodeHistory(prefs.getString(KEY_STATE, null)).events

    /** Returns true if this chapter has not been notified before, and marks it notified. */
    @Synchronized
    fun markChaptersNotified(mangaId: Long, chapterUrls: List<String>): List<String> {
        val notified = prefs.getStringSet(KEY_NOTIFIED, emptySet())!!.toMutableSet()
        val fresh = chapterUrls.filter { notified.add("$mangaId|$it") }
        prefs.edit().putStringSet(KEY_NOTIFIED, notified.toList().takeLast(MAX_NOTIFIED).toSet()).apply()
        return fresh
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_STATE).remove(KEY_NOTIFIED).apply()
    }

    private fun save(records: List<UpdateRecord>, events: List<UpdateEvent>) {
        prefs.edit()
            .putString(KEY_STATE, json.encodeToString(UpdateHistory.serializer(), UpdateHistory(records, events)))
            .apply()
    }

    private fun decodeHistory(raw: String?): UpdateHistory {
        if (raw == null) return UpdateHistory()
        return runCatching {
            json.decodeFromString(UpdateHistory.serializer(), raw)
        }.getOrDefault(UpdateHistory())
    }

    companion object {
        private const val PREFS_NAME = "yomi_library_update_state"
        private const val KEY_STATE = "update_history"
        private const val KEY_NOTIFIED = "notified_chapters"
        private const val MAX_EVENTS = 50
        private const val MAX_NOTIFIED = 1000

        fun encode(records: List<UpdateRecord>, events: List<UpdateEvent> = emptyList()): String {
            return Json.encodeToString(UpdateHistory.serializer(), UpdateHistory(records, events))
        }

        fun decode(raw: String): UpdateHistory {
            return Json.decodeFromString(UpdateHistory.serializer(), raw)
        }
    }
}
