package eu.kanade.presentation.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.library.LibraryUpdateWorker
import eu.kanade.tachiyomi.ui.library.UpdateStatusViewModel
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.text.DateFormat
import java.util.Date

class UpdateStatusScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val viewModel = metroViewModel<UpdateStatusViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.yomi_update_status),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            LazyColumn(contentPadding = contentPadding) {
                item {
                    SectionCard {
                        Column(
                            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                        ) {
                            val summary = state.summary
                            Text(
                                text = stringResource(MR.strings.yomi_update_summary_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = stringResource(MR.strings.yomi_update_summary_library, summary.total),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(
                                    MR.strings.yomi_update_summary_checked,
                                    summary.checked,
                                    summary.newChapters,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(
                                    MR.strings.yomi_update_summary_rest,
                                    summary.pending,
                                    summary.skipped,
                                    summary.failed,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = summary.lastSuccessAt
                                    ?.let { stringResource(MR.strings.yomi_update_last_success, it.format()) }
                                    ?: stringResource(MR.strings.yomi_update_never),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = summary.nextPlannedAt
                                    ?.let { stringResource(MR.strings.yomi_update_next_window, it.format()) }
                                    ?: stringResource(MR.strings.yomi_update_no_next_window),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            TextButton(
                                onClick = {
                                    scope.launch {
                                        val started = LibraryUpdateWorker.startNow(context.workManager)
                                        if (!started) context.toast(MR.strings.update_already_running)
                                        viewModel.refresh()
                                    }
                                },
                            ) {
                                Text(stringResource(MR.strings.yomi_update_check_all))
                            }
                            TextButton(onClick = { viewModel.refresh() }) {
                                Text(stringResource(MR.strings.yomi_update_refresh_status))
                            }
                        }
                    }
                }

                if (state.failed.isNotEmpty()) {
                    item {
                        SectionCard {
                            Column(
                                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                            ) {
                                Text(
                                    text = stringResource(MR.strings.yomi_update_failed_header, state.failed.size),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                state.failed.take(MAX_ROWS).forEach { record ->
                                    Text(
                                        text = "${record.title}: ${record.lastError ?: ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }

                if (state.skipped.isNotEmpty()) {
                    item {
                        SectionCard {
                            Column(
                                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                            ) {
                                Text(
                                    text = stringResource(MR.strings.yomi_update_skipped_header, state.skipped.size),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                state.skipped.take(MAX_ROWS).forEach { record ->
                                    Text(
                                        text = "${record.title}: ${record.skipReason ?: ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                if (state.events.isNotEmpty()) {
                    item {
                        SectionCard {
                            Column(
                                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                            ) {
                                Text(
                                    text = stringResource(MR.strings.yomi_update_history_header),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                state.events.take(MAX_ROWS).forEach { event ->
                                    Text(
                                        text = buildString {
                                            append(event.title)
                                            append(": ")
                                            append(event.at.format())
                                            if (event.success) {
                                                append(" (")
                                                append(event.newChapters)
                                                append(")")
                                            } else {
                                                append(" - ")
                                                append(event.detail ?: "")
                                            }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun Long.format(): String = DateFormat.getDateTimeInstance().format(Date(this))

    private companion object {
        const val MAX_ROWS = 20
    }
}
