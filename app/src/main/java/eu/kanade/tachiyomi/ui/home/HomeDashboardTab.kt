package eu.kanade.tachiyomi.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.library.components.ContinueReadingRow
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.presentation.more.settings.screen.SettingsSmartSourceScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.library.LibraryItem
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Book
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

data object HomeDashboardTab : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 0u,
            title = stringResource(MR.strings.yomi_home_title),
            icon = androidx.compose.ui.graphics.vector.rememberVectorPainter(MaterialSymbols.Rounded.Book),
        )

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current
        val tabNavigator = LocalTabNavigator.current
        val viewModel = metroViewModel<HomeDashboardViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        var showCustomize by remember { mutableStateOf(false) }

        val openManga: (Long) -> Unit = { navigator.push(MangaScreen(it)) }
        val resume: (tachiyomi.domain.library.model.LibraryManga) -> Unit = { libraryManga ->
            scope.launch {
                val chapterId = viewModel.continueChapterId(libraryManga.id)
                if (chapterId != null) {
                    context.startActivity(ReaderActivity.newIntent(context, libraryManga.id, chapterId))
                } else {
                    navigator.push(MangaScreen(libraryManga.id))
                }
            }
        }

        Scaffold(
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(MR.strings.yomi_home_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showCustomize = true }) {
                        Text(stringResource(MR.strings.yomi_home_customize))
                    }
                }
            },
        ) { contentPadding ->
            LazyColumn(contentPadding = contentPadding) {
                state.sections.forEach { section ->
                    when (section) {
                        HomeSection.CONTINUE_READING -> if (state.continueReading.isNotEmpty()) {
                            item {
                                ContinueReadingRow(
                                    items = state.continueReading,
                                    onClickManga = openManga,
                                    onContinueReadingClicked = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        resume(it)
                                    },
                                )
                            }
                        }

                        HomeSection.RECENTLY_UPDATED -> if (state.recentlyUpdated.isNotEmpty()) {
                            item {
                                CoverRow(
                                    title = stringResource(MR.strings.yomi_home_recently_updated),
                                    items = state.recentlyUpdated,
                                    onClickManga = openManga,
                                )
                            }
                        }

                        HomeSection.LIBRARY_SHORTCUTS -> item {
                            LibraryShortcuts(
                                librarySize = state.librarySize,
                                unreadCount = state.unreadCount,
                                onOpenLibrary = {
                                    tabNavigator.current = eu.kanade.tachiyomi.ui.library.LibraryTab
                                },
                            )
                        }

                        HomeSection.SOURCE_HEALTH -> if (state.healthProblems > 0) {
                            item {
                                SourceHealthCard(
                                    problems = state.healthProblems,
                                    onClick = { navigator.push(SettingsSmartSourceScreen) },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showCustomize) {
            CustomizeHomeDialog(
                homePreferences = remember { context.appGraph.homePreferences },
                onDismiss = {
                    showCustomize = false
                    viewModel.refresh()
                },
            )
        }
    }
}

@Composable
private fun CoverRow(
    title: String,
    items: List<LibraryItem>,
    onClickManga: (Long) -> Unit,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(
                start = MaterialTheme.padding.medium,
                end = MaterialTheme.padding.medium,
                top = MaterialTheme.padding.medium,
                bottom = MaterialTheme.padding.small,
            ),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = MaterialTheme.padding.medium),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            items(items = items, key = { it.id }, contentType = { "home_cover_item" }) { libraryItem ->
                val manga = libraryItem.libraryManga.manga
                Box(modifier = Modifier.width(112.dp)) {
                    MangaCompactGridItem(
                        title = manga.title,
                        coverData = MangaCover(
                            mangaId = manga.id,
                            sourceId = manga.source,
                            isMangaFavorite = manga.favorite,
                            url = manga.thumbnailUrl,
                            lastModified = manga.coverLastModified,
                        ),
                        onLongClick = {},
                        onClick = { onClickManga(manga.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryShortcuts(
    librarySize: Int,
    unreadCount: Long,
    onOpenLibrary: () -> Unit,
) {
    Column(modifier = Modifier.padding(MaterialTheme.padding.medium)) {
        Text(
            text = stringResource(MR.strings.yomi_home_shortcuts),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(MR.strings.yomi_home_library_stats, librarySize, unreadCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onOpenLibrary) {
            Text(stringResource(MR.strings.yomi_home_open_library))
        }
    }
}

@Composable
private fun SourceHealthCard(
    problems: Int,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.padding(MaterialTheme.padding.medium)) {
        Text(
            text = stringResource(MR.strings.yomi_home_source_health),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = stringResource(MR.strings.yomi_home_source_health_text, problems),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onClick) {
            Text(stringResource(MR.strings.yomi_home_review))
        }
    }
}

@Composable
private fun CustomizeHomeDialog(
    homePreferences: HomePreferences,
    onDismiss: () -> Unit,
) {
    var sections by remember { mutableStateOf(homePreferences.orderedSections()) }
    val hidden = remember { mutableStateOf(homePreferences.hiddenSections.get()) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.yomi_home_customize)) },
        text = {
            Column {
                sections.forEachIndexed { index, section ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = section.id !in hidden.value,
                            onCheckedChange = { checked ->
                                homePreferences.setHidden(section.id, !checked)
                                hidden.value = homePreferences.hiddenSections.get()
                            },
                        )
                        Text(
                            text = section.label(),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                if (index > 0) {
                                    val reordered = sections.toMutableList()
                                    val moved = reordered.removeAt(index)
                                    reordered.add(index - 1, moved)
                                    sections = reordered
                                    homePreferences.setOrder(reordered)
                                }
                            },
                        ) { Text("↑") }
                        TextButton(
                            onClick = {
                                if (index < sections.lastIndex) {
                                    val reordered = sections.toMutableList()
                                    val moved = reordered.removeAt(index)
                                    reordered.add(index + 1, moved)
                                    sections = reordered
                                    homePreferences.setOrder(reordered)
                                }
                            },
                        ) { Text("↓") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_ok)) }
        },
    )
}

@Composable
private fun HomeSection.label(): String = when (this) {
    HomeSection.CONTINUE_READING -> stringResource(MR.strings.yomi_continue_reading)
    HomeSection.RECENTLY_UPDATED -> stringResource(MR.strings.yomi_home_recently_updated)
    HomeSection.LIBRARY_SHORTCUTS -> stringResource(MR.strings.yomi_home_shortcuts)
    HomeSection.SOURCE_HEALTH -> stringResource(MR.strings.yomi_home_source_health)
}
