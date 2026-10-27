package eu.kanade.tachiyomi.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.smartsource.health.SourceHealthStore
import eu.kanade.tachiyomi.smartsource.model.HealthStatus
import eu.kanade.tachiyomi.ui.library.LibraryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.source.service.SourceManager
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class HomeDashboardViewModel(
    private val context: Context,
    private val getLibraryManga: GetLibraryManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val sourceManager: SourceManager,
) : ViewModel() {

    val state: StateFlow<State>
        field = MutableStateFlow(State())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val library = withContext(Dispatchers.IO) { getLibraryManga.await() }
            val sourcesById = withContext(Dispatchers.IO) {
                sourceManager.getAll().associateBy { it.id }
            }
            val homePreferences = withContext(Dispatchers.IO) {
                context.appGraph.homePreferences
            }
            val healthStore = withContext(Dispatchers.IO) { SourceHealthStore(context) }
            val healthProblems = withContext(Dispatchers.IO) {
                healthStore.getAll().count {
                    it.status in setOf(
                        HealthStatus.BROKEN,
                        HealthStatus.INCOMPATIBLE,
                        HealthStatus.REPOSITORY_MISSING,
                        HealthStatus.AUTHENTICATION_REQUIRED,
                    )
                }
            }

            val now = Clock.System.now()
            val continueReading = library
                .filter { it.hasStarted && it.unreadCount > 0 && it.lastRead != null }
                .sortedByDescending { it.lastRead }
                .take(ROW_SIZE)

            val recentlyUpdated = library
                .filter { it.chapterFetchedAt != null && now - it.chapterFetchedAt!! < RECENT_WINDOW_DAYS.days }
                .sortedByDescending { it.chapterFetchedAt }
                .take(ROW_SIZE)

            state.update {
                it.copy(
                    continueReading = continueReading.map { manga -> item(manga, sourcesById) },
                    recentlyUpdated = recentlyUpdated.map { manga -> item(manga, sourcesById) },
                    librarySize = library.size,
                    unreadCount = library.sumOf { m -> m.unreadCount },
                    healthProblems = healthProblems,
                    sections = homePreferences.visibleSections(),
                )
            }
        }
    }

    suspend fun continueChapterId(mangaId: Long): Long? {
        val chapters = getChaptersByMangaId.await(mangaId)
        return chapters
            .filter { !it.read }
            .minByOrNull { it.chapterNumber }
            ?.id
    }

    private fun item(
        manga: LibraryManga,
        sourcesById: Map<Long, eu.kanade.tachiyomi.source.Source>,
    ): LibraryItem {
        val source = sourcesById[manga.manga.source]
        val sourceName = source?.name.orEmpty()
        val sourceLanguage = source?.lang.orEmpty()
        return LibraryItem(
            libraryManga = manga,
            downloadCount = 0,
            unreadCount = manga.unreadCount,
            isLocal = false,
            sourceName = sourceName,
            sourceLanguage = sourceLanguage,
            badges = LibraryItem.Badges(
                downloadCount = 0,
                unreadCount = manga.unreadCount,
                isLocal = false,
                sourceLanguage = sourceLanguage,
            ),
        )
    }

    data class State(
        val continueReading: List<LibraryItem> = emptyList(),
        val recentlyUpdated: List<LibraryItem> = emptyList(),
        val librarySize: Int = 0,
        val unreadCount: Long = 0,
        val healthProblems: Int = 0,
        val sections: List<HomeSection> = HomeSection.entries,
    )

    private companion object {
        const val ROW_SIZE = 12
        const val RECENT_WINDOW_DAYS = 14
    }
}
