package eu.kanade.presentation.more.settings.screen.data

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.WarningBanner
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.backup.BackupFormat
import eu.kanade.tachiyomi.data.backup.BackupImportInspector
import eu.kanade.tachiyomi.data.backup.BackupImportSummary
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreWorker
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.LazyColumnWithAction
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

class RestoreBackupScreen(
    private val uri: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel =
            assistedMetroViewModel<RestoreBackupViewModel, RestoreBackupViewModel.Factory> { create(uri = uri) }
        val state by viewModel.state.collectAsState()

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.yomi_import_backup),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            LazyColumnWithAction(
                contentPadding = contentPadding,
                actionLabel = stringResource(MR.strings.action_restore),
                actionEnabled = state.canRestore && state.options.canRestore(),
                onClickAction = {
                    viewModel.startRestore()
                    navigator.pop()
                },
            ) {
                if (DeviceUtil.isMiui && DeviceUtil.isMiuiOptimizationDisabled()) {
                    item {
                        WarningBanner(MR.strings.restore_miui_warning)
                    }
                }

                if (state.canRestore) {
                    state.summary?.let { summary ->
                        previewItem(summary)
                        restoreContentsItem(summary)
                    }

                    item {
                        SectionCard {
                            RestoreOptions.options.forEach { option ->
                                LabeledCheckbox(
                                    label = stringResource(option.label),
                                    checked = option.getter(state.options),
                                    onCheckedChange = {
                                        viewModel.toggle(option.setter, it)
                                    },
                                )
                            }
                        }
                    }
                }

                if (state.error != null) {
                    errorMessageItem(state.error)
                }
            }
        }
    }

    private fun LazyListScope.previewItem(summary: BackupImportSummary) {
        item {
            SectionCard {
                Column(
                    modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    Text(
                        text = stringResource(MR.strings.yomi_backup_preview),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = when (summary.format) {
                            BackupFormat.MIHON_TACHIBK -> stringResource(MR.strings.yomi_backup_format_mihon)
                            BackupFormat.TACHIYOMI_PROTO -> stringResource(MR.strings.yomi_backup_format_tachiyomi)
                            else -> stringResource(MR.strings.yomi_backup_format_proto)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(MR.strings.yomi_backup_stats_manga, summary.entryCount),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(MR.strings.yomi_backup_stats_categories, summary.categoryCount),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(
                            MR.strings.yomi_backup_stats_chapters,
                            summary.stats.chapterCount,
                            summary.stats.readChapterCount,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (summary.hasReadingProgress) {
                        Text(
                            text = stringResource(
                                MR.strings.yomi_backup_stats_progress,
                                summary.stats.entriesWithProgressCount,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(MR.strings.yomi_backup_stats_history, summary.stats.historyCount),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (summary.hasTracking) {
                        Text(
                            text = stringResource(MR.strings.yomi_backup_stats_tracking, summary.stats.trackingCount),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }

    private fun LazyListScope.restoreContentsItem(summary: BackupImportSummary) {
        item {
            SectionCard {
                Column(
                    modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    Text(
                        text = stringResource(MR.strings.yomi_backup_will_restore),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "• " + stringResource(MR.strings.yomi_backup_restore_library),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "• " + stringResource(MR.strings.yomi_backup_restore_progress),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (summary.stats.historyCount > 0) {
                        Text(
                            text = "• " + stringResource(MR.strings.yomi_backup_restore_history),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (summary.hasTracking) {
                        Text(
                            text = "• " + stringResource(MR.strings.yomi_backup_restore_tracking),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (summary.hasAppPreferences) {
                        Text(
                            text = "• " + stringResource(MR.strings.yomi_backup_restore_settings),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (summary.hasSourcePreferences) {
                        Text(
                            text = "• " + stringResource(MR.strings.yomi_backup_restore_source_settings),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        text = stringResource(MR.strings.yomi_backup_resume_note),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }

    private fun LazyListScope.errorMessageItem(
        error: Any?,
    ) {
        item {
            SectionCard {
                Column(
                    modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    val msg = buildAnnotatedString {
                        when (error) {
                            is MissingRestoreComponents -> {
                                appendLine(stringResource(MR.strings.backup_restore_content_full))
                                if (error.sources.isNotEmpty()) {
                                    appendLine()
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                        appendLine(stringResource(MR.strings.backup_restore_missing_sources))
                                    }
                                    error.sources.joinTo(
                                        this,
                                        separator = "\n- ",
                                        prefix = "- ",
                                    )
                                }
                                if (error.trackers.isNotEmpty()) {
                                    appendLine()
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                        appendLine(stringResource(MR.strings.backup_restore_missing_trackers))
                                    }
                                    error.trackers.joinTo(
                                        this,
                                        separator = "\n- ",
                                        prefix = "- ",
                                    )
                                }
                            }

                            is InvalidRestore -> {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                    appendLine(stringResource(MR.strings.invalid_backup_file))
                                }
                                appendLine(error.uri.toString())

                                appendLine()

                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                    appendLine(stringResource(MR.strings.invalid_backup_file_error))
                                }
                                appendLine(error.message)
                            }

                            else -> {
                                appendLine(error.toString())
                            }
                        }
                    }

                    SelectionContainer {
                        Text(text = msg)
                    }
                }
            }
        }
    }
}

@AssistedInject
class RestoreBackupViewModel(
    @Assisted private val uri: String,
    private val backupImportInspector: BackupImportInspector,
    private val context: Context,
) : ViewModel() {

    val state: StateFlow<RestoreBackupViewModel.State>
        field = MutableStateFlow<RestoreBackupViewModel.State>(State())

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(uri: String): RestoreBackupViewModel
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            validate(uri.toUri())
        }
    }

    fun toggle(setter: (RestoreOptions, Boolean) -> RestoreOptions, enabled: Boolean) {
        state.update {
            it.copy(
                options = setter(it.options, enabled),
            )
        }
    }

    fun startRestore() {
        BackupRestoreWorker.start(
            workManager = context.workManager,
            uri = uri.toUri(),
            options = state.value.options,
        )
    }

    private suspend fun validate(uri: Uri) {
        val summary = try {
            backupImportInspector.inspect(uri)
        } catch (e: Exception) {
            state.update {
                it.copy(
                    summary = null,
                    error = InvalidRestore(uri, e.message.toString()),
                    canRestore = false,
                )
            }
            return
        }

        val error = if (summary.missingSources.isNotEmpty() || summary.missingTrackers.isNotEmpty()) {
            MissingRestoreComponents(uri, summary.missingSources, summary.missingTrackers)
        } else {
            null
        }

        state.update {
            it.copy(
                summary = summary,
                error = error,
                canRestore = true,
            )
        }
    }

    @Immutable
    data class State(
        val summary: BackupImportSummary? = null,
        val error: Any? = null,
        val canRestore: Boolean = false,
        val options: RestoreOptions = RestoreOptions(),
    )
}

private data class MissingRestoreComponents(
    val uri: Uri,
    val sources: List<String>,
    val trackers: List<String>,
)

private data class InvalidRestore(
    val uri: Uri? = null,
    val message: String,
)
