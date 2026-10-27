package eu.kanade.presentation.browse

import android.content.Context

/**
 * Local, on-device recent search queries. Never leaves the device.
 */
class RecentSearchesStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun recent(): List<String> {
        return prefs.getString(KEY_RECENT, null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }

    fun add(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val updated = (listOf(trimmed) + recent().filterNot { it.equals(trimmed, ignoreCase = true) })
            .take(MAX_ENTRIES)
        prefs.edit().putString(KEY_RECENT, updated.joinToString(SEPARATOR)).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_RECENT).apply()
    }

    private companion object {
        const val PREFS_NAME = "yomi_recent_searches"
        const val KEY_RECENT = "recent"
        const val SEPARATOR = "\u0001"
        const val MAX_ENTRIES = 15
    }
}
