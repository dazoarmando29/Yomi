package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.smartsource.SourceHealthWorker
import eu.kanade.tachiyomi.smartsource.SmartSourceViewModel
import eu.kanade.tachiyomi.smartsource.model.HealthRecord
import eu.kanade.tachiyomi.smartsource.model.HealthStatus
import eu.kanade.tachiyomi.ui.home.HomeScreen
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

object SettingsSmartSourceScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.yomi_smart_source_manager

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val viewModel = metroViewModel<SmartSourceViewModel>()
        val state by viewModel.state.collectAsState()
        val preferences = remember { context.appGraph.smartSourcePreferences }

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.yomi_smart_source_overview),
                preferenceItems = listOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.yomi_smart_source_check_now),
                        subtitle = when {
                            state.checking -> stringResource(MR.strings.yomi_smart_source_checking)
                            state.lastReport != null -> stringResource(
                                MR.strings.yomi_smart_source_last_check,
                                state.lastReport!!.checked,
                                state.lastReport!!.problems,
                                state.lastReport!!.updates,
                            )
                            else -> stringResource(MR.strings.yomi_smart_source_check_hint)
                        },
                        widget = {
                            if (state.checking) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(horizontal = MaterialTheme.padding.small),
                                    strokeWidth = 2.dp,
                                )
                            }
                        },
                        onClick = { viewModel.runChecks() },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.label_extensions),
                        subtitle = stringResource(MR.strings.yomi_smart_source_open_extensions_hint),
                        onClick = {
                            scope.launch {
                                navigator.popUntilRoot()
                                HomeScreen.openTab(HomeScreen.Tab.Browse(toExtensions = true))
                            }
                        },
                    ),
                    Preference.PreferenceItem.CustomPreference(
                        title = stringResource(MR.strings.yomi_smart_source_health),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium)) {
                            Text(
                                text = stringResource(
                                    MR.strings.yomi_smart_source_summary,
                                    state.healthy,
                                    state.updates,
                                    state.problems,
                                    state.unknown,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (state.records.isEmpty()) {
                                Text(
                                    text = stringResource(MR.strings.yomi_smart_source_no_data),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                state.records
                                    .filter { it.status != HealthStatus.HEALTHY }
                                    .take(MAX_VISIBLE_RECORDS)
                                    .forEach { record ->
                                        Text(
                                            text = record.summary(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                            }
                        }
                    },
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.yomi_smart_source_monitoring),
                preferenceItems = listOf(
                    Preference.PreferenceItem.ListPreference(
                        preference = preferences.extensionUpdateCheckDays,
                        title = stringResource(MR.strings.yomi_smart_source_update_checks),
                        entries = mapOf(
                            0 to stringResource(MR.strings.yomi_smart_source_manual),
                            1 to stringResource(MR.strings.yomi_smart_source_daily),
                            7 to stringResource(MR.strings.yomi_smart_source_weekly),
                        ),
                        onValueChanged = { value ->
                            SourceHealthWorker.setupTask(context, value)
                            true
                        },
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = preferences.healthCheckDays,
                        title = stringResource(MR.strings.yomi_smart_source_health_checks),
                        entries = mapOf(
                            0 to stringResource(MR.strings.yomi_smart_source_manual),
                            1 to stringResource(MR.strings.yomi_smart_source_daily),
                            7 to stringResource(MR.strings.yomi_smart_source_weekly),
                        ),
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = preferences.recommendationMode,
                        title = stringResource(MR.strings.yomi_smart_source_recommendations),
                        entries = mapOf(
                            0 to stringResource(MR.strings.yomi_smart_source_notify_only),
                            1 to stringResource(MR.strings.yomi_smart_source_prepare_matches),
                            2 to stringResource(MR.strings.yomi_smart_source_disabled),
                        ),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = preferences.wifiOnly,
                        title = stringResource(MR.strings.yomi_smart_source_wifi_only),
                        subtitle = stringResource(MR.strings.yomi_smart_source_wifi_only_hint),
                    ),
                ),
            ),
        )
    }
}

private const val MAX_VISIBLE_RECORDS = 12

private fun HealthRecord.summary(): String {
    val label = when {
        key.startsWith("ext:") -> "Extension " + key.removePrefix("ext:")
        key.startsWith("repo:") -> "Repository " + key.removePrefix("repo:")
        key.startsWith("source:") -> "Source " + key.removePrefix("source:")
        else -> key
    }
    val statusText = status.name.lowercase().replace('_', ' ')
    val error = lastError?.let { " - $it" }.orEmpty()
    return "$label: $statusText$error"
}
