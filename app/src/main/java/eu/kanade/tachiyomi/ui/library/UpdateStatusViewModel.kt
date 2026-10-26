package eu.kanade.tachiyomi.ui.library

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.smartupdate.LibraryUpdateStateStore
import eu.kanade.tachiyomi.smartupdate.UpdateEvent
import eu.kanade.tachiyomi.smartupdate.UpdateRecord
import eu.kanade.tachiyomi.smartupdate.UpdateState
import eu.kanade.tachiyomi.smartupdate.UpdateSummary
import eu.kanade.tachiyomi.smartupdate.UpdateSummaryBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Inject
class UpdateStatusViewModel(private val context: Context) : ViewModel() {

    val state: StateFlow<State>
        field = MutableStateFlow(State())

    private val store by lazy { LibraryUpdateStateStore(context) }

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val records = withContext(Dispatchers.IO) { store.getAll() }
            val events = withContext(Dispatchers.IO) { store.getEvents() }
            state.update {
                it.copy(
                    records = records.sortedBy { record -> record.title.lowercase() },
                    events = events.sortedByDescending { event -> event.at },
                    summary = UpdateSummaryBuilder.build(records, System.currentTimeMillis()),
                )
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) { store.clear() }
        refresh()
    }

    data class State(
        val records: List<UpdateRecord> = emptyList(),
        val events: List<UpdateEvent> = emptyList(),
        val summary: UpdateSummary = UpdateSummaryBuilder.build(emptyList(), 0L),
    ) {
        val skipped: List<UpdateRecord> get() = records.filter { it.state == UpdateState.SKIPPED }
        val failed: List<UpdateRecord> get() = records.filter { it.state == UpdateState.FAILED }
        val pending: List<UpdateRecord>
            get() = records.filter {
                it.state == UpdateState.PENDING || it.state == UpdateState.NEVER_CHECKED
            }
    }
}
