package eu.kanade.tachiyomi.smartsource

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@Inject
@SingleIn(AppScope::class)
class SmartSourcePreferences(
    preferenceStore: PreferenceStore,
) {
    /** 0 = manual, 1 = daily, 7 = weekly */
    val extensionUpdateCheckDays: Preference<Int> = preferenceStore.getInt("smart_ext_update_check_days", 1)

    /** 0 = manual, 1 = daily, 7 = weekly */
    val healthCheckDays: Preference<Int> = preferenceStore.getInt("smart_health_check_days", 1)

    /** 0 = notify only, 1 = prepare suggested matches, 2 = disabled */
    val recommendationMode: Preference<Int> = preferenceStore.getInt("smart_recommendation_mode", 0)

    val wifiOnly: Preference<Boolean> = preferenceStore.getBoolean("smart_monitoring_wifi_only", false)
}
