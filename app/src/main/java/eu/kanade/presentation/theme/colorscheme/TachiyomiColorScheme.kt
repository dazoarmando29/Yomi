package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Yomi premium design tokens (Phase 1 design foundation)
 * Dark-first: deep charcoal surfaces with subtle purple accents
 *
 * Key tokens:
 * Background #101119, Elevated #191B26, Card #222432
 * Primary #AD91F5, Secondary #D5C7FF, Success #77D5AE, Error #F07583
 */
internal object TachiyomiColorScheme : BaseColorScheme() {

    override val darkScheme = darkColorScheme(
        primary = Color(0xFFAD91F5),
        onPrimary = Color(0xFF241255),
        primaryContainer = Color(0xFF3B2D6B),
        onPrimaryContainer = Color(0xFFEADDFF),
        inversePrimary = Color(0xFF6750A4),
        secondary = Color(0xFFD5C7FF), // Unread badge
        onSecondary = Color(0xFF33275C), // Unread badge text
        secondaryContainer = Color(0xFF453B6B), // Navigation bar selector pill & pro
        onSecondaryContainer = Color(0xFFE9DDFF), // Navigation bar selector icon
        tertiary = Color(0xFF77D5AE), // Downloaded badge / success
        onTertiary = Color(0xFF003825), // Downloaded badge text
        tertiaryContainer = Color(0xFF005238),
        onTertiaryContainer = Color(0xFF9FF2CE),
        background = Color(0xFF101119),
        onBackground = Color(0xFFF5F4FA),
        surface = Color(0xFF101119),
        onSurface = Color(0xFFF5F4FA),
        surfaceVariant = Color(0xFF222432), // Card surface / navigation bar background
        onSurfaceVariant = Color(0xFFAEACBC),
        surfaceTint = Color(0xFFAD91F5),
        inverseSurface = Color(0xFFE7E1E9),
        inverseOnSurface = Color(0xFF2E2B33),
        error = Color(0xFFF07583),
        onError = Color(0xFF4A0009),
        errorContainer = Color(0xFF7A2430),
        onErrorContainer = Color(0xFFFFDAD9),
        outline = Color(0xFF9793A6),
        outlineVariant = Color(0xFF4A4757),
        surfaceContainerLowest = Color(0xFF101119),
        surfaceContainerLow = Color(0xFF191B26), // Elevated surface
        surfaceContainer = Color(0xFF222432), // Card surface / navigation bar background
        surfaceContainerHigh = Color(0xFF2A2C3D),
        surfaceContainerHighest = Color(0xFF33354A),
    )

    override val lightScheme = lightColorScheme(
        primary = Color(0xFF6750A4),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFEADDFF),
        onPrimaryContainer = Color(0xFF21005D),
        inversePrimary = Color(0xFFAD91F5),
        secondary = Color(0xFF6750A4), // Unread badge
        onSecondary = Color(0xFFFFFFFF), // Unread badge text
        secondaryContainer = Color(0xFFE9DDFF), // Navigation bar selector pill & progress indicator
        onSecondaryContainer = Color(0xFF24005A), // Navigation bar selector icon
        tertiary = Color(0xFF2E7D5B), // Downloaded badge
        onTertiary = Color(0xFFFFFFFF), // Downloaded badge text
        tertiaryContainer = Color(0xFFB8F0D4),
        onTertiaryContainer = Color(0xFF00210D),
        background = Color(0xFFF9F7FC),
        onBackground = Color(0xFF1C1B20),
        surface = Color(0xFFF9F7FC),
        onSurface = Color(0xFF1C1B20),
        surfaceVariant = Color(0xFFE7E0EC), // Navigation bar background
        onSurfaceVariant = Color(0xFF49454F),
        surfaceTint = Color(0xFF6750A4),
        inverseSurface = Color(0xFF313033),
        inverseOnSurface = Color(0xFFF1ECF5),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        outline = Color(0xFF7A757F),
        outlineVariant = Color(0xFFCAC4D0),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF4F0F8),
        surfaceContainer = Color(0xFFF0EBF4), // Navigation bar background
        surfaceContainerHigh = Color(0xFFEAE5EF),
        surfaceContainerHighest = Color(0xFFE4DFE9),
    )
}
