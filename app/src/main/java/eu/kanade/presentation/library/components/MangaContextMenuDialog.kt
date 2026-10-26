package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Contextual actions for a single library manga.
 *
 * Long-press opens this menu when no selection is active; bulk actions stay available
 * through "Select" which enters multi-selection, where long-press keeps range selection.
 */
@Composable
fun MangaContextMenuDialog(
    manga: LibraryManga,
    onContinueReading: (() -> Unit)?,
    onViewDetails: () -> Unit,
    onFindAlternatives: () -> Unit,
    onSelect: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = manga.manga.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
            )
        },
        text = {
            Column {
                if (onContinueReading != null && manga.hasStarted && manga.unreadCount > 0) {
                    TextButton(
                        onClick = { onDismiss(); onContinueReading() },
                        modifier = Modifier,
                    ) {
                        Text(stringResource(MR.strings.yomi_menu_continue_reading))
                    }
                }
                TextButton(onClick = { onDismiss(); onViewDetails() }) {
                    Text(stringResource(MR.strings.yomi_menu_view_details))
                }
                TextButton(onClick = { onDismiss(); onFindAlternatives() }) {
                    Text(stringResource(MR.strings.yomi_menu_find_alternatives))
                }
                TextButton(onClick = { onDismiss(); onSelect() }) {
                    Text(stringResource(MR.strings.yomi_menu_select))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_close))
            }
        },
    )
}
