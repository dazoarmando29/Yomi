package eu.kanade.tachiyomi.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.more.settings.screen.SettingsSmartSourceScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.ui.library.LibraryItem
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Book
import mihon.icons.materialsymbols.rounded.KeyboardArrowRight
import mihon.icons.materialsymbols.rounded.LocalLibrary
import mihon.icons.materialsymbols.rounded.Search
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.util.Calendar
import tachiyomi.domain.manga.model.MangaCover as MangaCoverModel

private enum class HomeFilter {
    ALL,
    MANHWA,
    MANGA,
}

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
        var filter by remember { mutableStateOf(HomeFilter.ALL) }

        val openManga: (Long) -> Unit = { navigator.push(MangaScreen(it)) }
        val resume: (LibraryManga) -> Unit = { libraryManga ->
            scope.launch {
                val chapterId = viewModel.continueChapterId(libraryManga.id)
                if (chapterId != null) {
                    context.startActivity(ReaderActivity.newIntent(context, libraryManga.id, chapterId))
                } else {
                    navigator.push(MangaScreen(libraryManga.id))
                }
            }
        }

        val matchesFilter: (LibraryItem) -> Boolean = when (filter) {
            HomeFilter.ALL -> {
                { true }
            }
            HomeFilter.MANHWA -> {
                { it.sourceLanguage == "ko" }
            }
            HomeFilter.MANGA -> {
                { it.sourceLanguage == "ja" }
            }
        }
        val continueItems = state.continueReading.filter(matchesFilter)
        val recentItems = state.recentlyUpdated.filter(matchesFilter)

        Scaffold(
            topBar = {
                GreetingHeader(
                    onSearch = { tabNavigator.current = LibraryTab },
                    onCustomize = { showCustomize = true },
                )
            },
        ) { contentPadding ->
            androidx.compose.foundation.lazy.LazyColumn(contentPadding = contentPadding) {
                item {
                    FilterPills(
                        selected = filter,
                        onSelect = { filter = it },
                    )
                }
                state.sections.forEach { section ->
                    when (section) {
                        HomeSection.CONTINUE_READING -> if (continueItems.isNotEmpty()) {
                            item {
                                ContinueReadingCarousel(
                                    items = continueItems,
                                    onClickManga = openManga,
                                    onResume = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        resume(it)
                                    },
                                )
                            }
                        }

                        HomeSection.RECENTLY_UPDATED -> if (recentItems.isNotEmpty()) {
                            item {
                                RecentlyUpdatedGrid(
                                    items = recentItems,
                                    onClickManga = openManga,
                                )
                            }
                        }

                        HomeSection.LIBRARY_SHORTCUTS -> item {
                            LibraryStatusBanner(
                                librarySize = state.librarySize,
                                unreadCount = state.unreadCount,
                                onOpenLibrary = { tabNavigator.current = LibraryTab },
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
private fun GreetingHeader(
    onSearch: () -> Unit,
    onCustomize: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = when (hour) {
        in 5..11 -> stringResource(MR.strings.yomi_home_greeting_morning)
        in 12..17 -> stringResource(MR.strings.yomi_home_greeting_afternoon)
        else -> stringResource(MR.strings.yomi_home_greeting_evening)
    }
    val modeLabel = if (isDark) {
        stringResource(MR.strings.yomi_home_night_mode)
    } else {
        stringResource(MR.strings.yomi_home_day_mode)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary),
                )
                Spacer(modifier = Modifier.width(MaterialTheme.padding.extraSmall))
                Text(
                    text = modeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = greeting,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onSearch),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MaterialSymbols.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onCustomize) {
            Text(stringResource(MR.strings.yomi_home_customize))
        }
    }
}

@Composable
private fun FilterPills(
    selected: HomeFilter,
    onSelect: (HomeFilter) -> Unit,
) {
    val filters = listOf(
        HomeFilter.ALL to stringResource(MR.strings.yomi_filter_all_reading),
        HomeFilter.MANHWA to stringResource(MR.strings.yomi_filter_manhwa),
        HomeFilter.MANGA to stringResource(MR.strings.yomi_filter_manga),
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = MaterialTheme.padding.medium),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        modifier = Modifier.padding(vertical = MaterialTheme.padding.small),
    ) {
        items(items = filters, key = { it.first }) { (filter, label) ->
            val isSelected = filter == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    )
                    .clickable { onSelect(filter) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun ContinueReadingCarousel(
    items: List<LibraryItem>,
    onClickManga: (Long) -> Unit,
    onResume: (LibraryManga) -> Unit,
) {
    Column(modifier = Modifier.padding(top = MaterialTheme.padding.small)) {
        SectionHeader(
            title = stringResource(MR.strings.yomi_continue_reading),
            trailing = stringResource(MR.strings.yomi_home_active_count, items.size),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = MaterialTheme.padding.medium),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = items, key = { it.id }, contentType = { "home_continue_item" }) { item ->
                ContinueReadingCard(
                    item = item,
                    onClick = { onClickManga(item.id) },
                    onResume = { onResume(item.libraryManga) },
                )
            }
        }
    }
}

@Composable
private fun ContinueReadingCard(
    item: LibraryItem,
    onClick: () -> Unit,
    onResume: () -> Unit,
) {
    val manga = item.libraryManga.manga
    val read = item.libraryManga.readCount
    val total = item.libraryManga.totalChapters
    val progress = if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else 0f
    Column(
        modifier = Modifier
            .width(170.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f),
        ) {
            MangaCover.Book(
                modifier = Modifier.fillMaxWidth(),
                data = MangaCoverModel(
                    mangaId = manga.id,
                    sourceId = manga.source,
                    isMangaFavorite = manga.favorite,
                    url = manga.thumbnailUrl,
                    lastModified = manga.coverLastModified,
                ),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.85f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(MR.strings.yomi_home_unread_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = item.unreadCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.92f),
                        ),
                    )
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(MR.strings.yomi_home_read_progress, read.toInt(), total.toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary)
                        .clickable(onClick = onResume),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MaterialSymbols.RoundedFilled.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp),
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
            )
        }
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = manga.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.sourceName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    trailing: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = MaterialTheme.padding.medium,
                end = MaterialTheme.padding.medium,
                top = MaterialTheme.padding.medium,
                bottom = MaterialTheme.padding.small,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = trailing,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LibraryStatusBanner(
    librarySize: Int,
    unreadCount: Long,
    onOpenLibrary: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(MaterialTheme.padding.medium)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    0f to MaterialTheme.colorScheme.surfaceContainer,
                    1f to MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            )
            .padding(MaterialTheme.padding.medium),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.LocalLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.width(MaterialTheme.padding.small))
            Text(
                text = stringResource(MR.strings.yomi_home_library_status),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(MR.strings.yomi_home_synced),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(modifier = Modifier.height(MaterialTheme.padding.small))
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
            StatTile(
                label = stringResource(MR.strings.yomi_home_tracked_series),
                value = librarySize.toString(),
                valueColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(MR.strings.yomi_home_unread_chapters),
                value = unreadCount.toString(),
                valueColor = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(MaterialTheme.padding.small))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.horizontalGradient(
                        0f to MaterialTheme.colorScheme.primaryContainer,
                        1f to MaterialTheme.colorScheme.secondaryContainer,
                    ),
                )
                .clickable(onClick = onOpenLibrary)
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(MR.strings.yomi_home_open_vault),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = MaterialSymbols.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.7f))
            .padding(MaterialTheme.padding.small),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = valueColor,
        )
    }
}

@Composable
private fun RecentlyUpdatedGrid(
    items: List<LibraryItem>,
    onClickManga: (Long) -> Unit,
) {
    Column {
        SectionHeader(
            title = stringResource(MR.strings.yomi_home_recently_updated),
            trailing = stringResource(MR.strings.yomi_home_view_all),
        )
        Column(
            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            items.chunked(3).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                    rowItems.forEach { item ->
                        Box(modifier = Modifier.weight(1f)) {
                            RecentUpdateCell(
                                item = item,
                                onClick = { onClickManga(item.id) },
                            )
                        }
                    }
                    repeat(3 - rowItems.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentUpdateCell(
    item: LibraryItem,
    onClick: () -> Unit,
) {
    val manga = item.libraryManga.manga
    val fetchedAt = item.libraryManga.chapterFetchedAt
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(12.dp)),
        ) {
            MangaCover.Book(
                modifier = Modifier.fillMaxWidth(),
                data = MangaCoverModel(
                    mangaId = manga.id,
                    sourceId = manga.source,
                    isMangaFavorite = manga.favorite,
                    url = manga.thumbnailUrl,
                    lastModified = manga.coverLastModified,
                ),
            )
            if (fetchedAt != null) {
                Text(
                    text = relativeTimeSpanString(fetchedAt.toEpochMilliseconds()),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.9f))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
            Text(
                text = "Ch. ${item.libraryManga.totalChapters}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
        Text(
            text = manga.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = item.sourceName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    HomeSection.LIBRARY_SHORTCUTS -> stringResource(MR.strings.yomi_home_library_status)
    HomeSection.SOURCE_HEALTH -> stringResource(MR.strings.yomi_home_source_health)
}
