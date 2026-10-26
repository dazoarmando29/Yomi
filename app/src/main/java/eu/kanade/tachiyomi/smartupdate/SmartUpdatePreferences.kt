package eu.kanade.tachiyomi.smartupdate

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@Inject
@SingleIn(AppScope::class)
class SmartUpdatePreferences(
    preferenceStore: PreferenceStore,
) {
    /** 0 = smart, 1 = balanced, 2 = thorough */
    val updateMode: Preference<Int> = preferenceStore.getInt("yomi_update_mode", 1)

    val thoroughIncludeCompleted: Preference<Boolean> = preferenceStore.getBoolean("yomi_thorough_completed", false)

    /** Quiet hours: notifications are posted silently inside this window. -1 disables. */
    val quietHoursStart: Preference<Int> = preferenceStore.getInt("yomi_quiet_hours_start", 22)

    val quietHoursEnd: Preference<Int> = preferenceStore.getInt("yomi_quiet_hours_end", 7)

    val notifyNewChapters: Preference<Boolean> = preferenceStore.getBoolean("yomi_notify_new_chapters", true)

    fun mode(): UpdateMode = UpdateMode.entries.getOrElse(updateMode.get()) { UpdateMode.BALANCED }

    fun isQuietHours(hourOfDay: Int): Boolean {
        val start = quietHoursStart.get()
        val end = quietHoursEnd.get()
        if (start < 0 || end < 0 || start == end) return false
        return if (start < end) hourOfDay in start until end else hourOfDay >= start || hourOfDay < end
    }
}
