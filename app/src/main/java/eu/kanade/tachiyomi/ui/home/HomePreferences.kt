package eu.kanade.tachiyomi.ui.home

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

enum class HomeSection(val id: String) {
    CONTINUE_READING("continue_reading"),
    RECENTLY_UPDATED("recently_updated"),
    LIBRARY_SHORTCUTS("library_shortcuts"),
    SOURCE_HEALTH("source_health"),
}

@Inject
@SingleIn(AppScope::class)
class HomePreferences(
    preferenceStore: PreferenceStore,
) {
    val sectionOrder: Preference<String> = preferenceStore.getString(
        "yomi_home_section_order",
        HomeSection.entries.joinToString(",") { it.id },
    )

    val hiddenSections: Preference<Set<String>> = preferenceStore.getStringSet(
        "yomi_home_hidden_sections",
        emptySet(),
    )

    fun orderedSections(): List<HomeSection> {
        val known = HomeSection.entries
        val order = sectionOrder.get().split(',').mapNotNull { id -> known.firstOrNull { it.id == id } }
        return order + known.filterNot { it in order }
    }

    fun visibleSections(): List<HomeSection> {
        val hidden = hiddenSections.get()
        return orderedSections().filterNot { it.id in hidden }
    }

    fun setOrder(sections: List<HomeSection>) {
        sectionOrder.set(sections.joinToString(",") { it.id })
    }

    fun setHidden(id: String, hidden: Boolean) {
        val current = hiddenSections.get().toMutableSet()
        if (hidden) current += id else current -= id
        hiddenSections.set(current)
    }
}
