package eu.kanade.tachiyomi.smartsource

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.smartsource.health.HealthCheckReport
import eu.kanade.tachiyomi.smartsource.health.SourceHealthChecker
import eu.kanade.tachiyomi.smartsource.health.SourceHealthStore
import eu.kanade.tachiyomi.smartsource.model.HealthRecord
import eu.kanade.tachiyomi.smartsource.model.HealthStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Inject
class SmartSourceViewModel(
    private val context: Context,
    private val checker: SourceHealthChecker,
) : ViewModel() {

    val state: StateFlow<State>
        field = MutableStateFlow(State())

    private val store by lazy { SourceHealthStore(context) }

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val records = withContext(Dispatchers.IO) { store.getAll() }
            state.update { it.copy(records = records.sortedByDescending { record -> record.status.priority() }) }
        }
    }

    fun runChecks() {
        if (state.value.checking) return
        state.update { it.copy(checking = true) }
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { checker.runChecks(store) }
            val records = withContext(Dispatchers.IO) { store.getAll() }
            state.update {
                it.copy(
                    checking = false,
                    lastReport = report,
                    records = records.sortedByDescending { record -> record.status.priority() },
                )
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) { store.clear() }
        refresh()
    }

    data class State(
        val records: List<HealthRecord> = emptyList(),
        val checking: Boolean = false,
        val lastReport: HealthCheckReport? = null,
    ) {
        val healthy: Int get() = records.count { it.status == HealthStatus.HEALTHY }
        val updates: Int get() = records.count { it.status == HealthStatus.UPDATE_AVAILABLE }
        val problems: Int
            get() = records.count {
                checkerlessProblemStatuses.contains(it.status) ||
                    (it.status == HealthStatus.TEMPORARILY_UNAVAILABLE && it.consecutiveFailures >= 2)
            }
        val unknown: Int get() = records.count { it.status == HealthStatus.UNKNOWN }

        private companion object {
            val checkerlessProblemStatuses = setOf(
                HealthStatus.BROKEN,
                HealthStatus.INCOMPATIBLE,
                HealthStatus.REPOSITORY_MISSING,
                HealthStatus.AUTHENTICATION_REQUIRED,
                HealthStatus.OUTDATED,
            )
        }
    }
}

internal fun HealthStatus.priority(): Int = when (this) {
    HealthStatus.BROKEN,
    HealthStatus.INCOMPATIBLE,
    HealthStatus.REPOSITORY_MISSING,
    HealthStatus.AUTHENTICATION_REQUIRED,
    -> 4
    HealthStatus.OUTDATED -> 3
    HealthStatus.TEMPORARILY_UNAVAILABLE -> 2
    HealthStatus.UPDATE_AVAILABLE -> 1
    HealthStatus.HEALTHY -> 0
    HealthStatus.UNKNOWN -> -1
}
