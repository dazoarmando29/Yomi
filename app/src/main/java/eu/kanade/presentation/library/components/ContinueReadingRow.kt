package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.library.LibraryItem
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

private val ContinueReadingItemWidth = 112.dp

@Composable
fun ContinueReadingRow(
    items: List<LibraryItem>,
    onClickManga: (Long) -> Unit,
    onContinueReadingClicked: ((LibraryManga) -> Unit)?,
) {
    if (items.isEmpty()) return

    Column {
        Text(
            text = stringResource(MR.strings.yomi_continue_reading),
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
            items(
                items = items,
                key = { it.id },
                contentType = { "continue_reading_item" },
            ) { libraryItem ->
                val manga = libraryItem.libraryManga.manga
                Box(
                    modifier = Modifier.width(ContinueReadingItemWidth),
                ) {
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
                        onClickContinueReading = onContinueReadingClicked
                            ?.let { { it(libraryItem.libraryManga) } }
                            ?.takeIf { libraryItem.unreadCount > 0 },
                    )
                }
            }
        }
    }
}
