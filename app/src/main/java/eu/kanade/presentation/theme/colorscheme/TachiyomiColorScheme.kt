package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Yomi default palette
 * Deep charcoal neutrals with subtle purple accents
 *
 * Key colors:
 * Primary #7C5CE0
 * Secondary #7C5CE0
 * Tertiary #3F6E4F
 * Neutral #16151A
 */
internal object TachiyomiColorScheme : BaseColorScheme() {

    override val darkScheme = darkColorScheme(
        primary = Color(0xFFCBB7FF),
        onPrimary = Color(0xFF3B2080),
        primaryContainer = Color(0xFF523FA8),
        onPrimaryContainer = Color(0xFFEADDFF),
        inversePrimary = Color(0xFF6750A4),
        secondary = Color(0xFFCBB7FF), // Unread badge
        onSecondary = Color(0xFF3B2080), // Unread badge text
        secondaryContainer = Color(0xFF4A3D8B), // Navigation bar selector pill & pro
        onSecondaryContainer = Color(0xFFE9DDFF), // Navigation bar selector icon
        tertiary = Color(0xFF86D9A6), // Downloaded badge
        onTertiary = Color(0xFF00391C), // Downloaded badge text
        tertiaryContainer = Color(0xFF00522B),
        onTertiaryContainer = Color(0xFFA7F2C1),
        background = Color(0xFF16151A),
        onBackground = Color(0xFFE7E1E9),
        surface = Color(0xFF16151A),
        onSurface = Color(0xFFE7E1E9),
        surfaceVariant = Color(0xFF25222B), // Navigation bar background (ThemePrefWidget)
        onSurfaceVariant = Color(0xFFCBC3D4),
        surfaceTint = Color(0xFFCBB7FF),
        inverseSurface = Color(0xFFE7E1E9),
        inverseOnSurface = Color(0xFF2E2B33),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF958FA3),
        outlineVariant = Color(0xFF494452),
        surfaceContainerLowest = Color(0xFF121116),
        surfaceContainerLow = Color(0xFF1C1A21),
        surfaceContainer = Color(0xFF211F27), // Navigation bar background
        surfaceContainerHigh = Color(0xFF2B2832),
        surfaceContainerHighest = Color(0xFF36323D),
    )

    override val lightScheme = lightColorScheme(
        primary = Color(0xFF6750A4),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFEADDFF),
        onPrimaryContainer = Color(0xFF21005D),
        inversePrimary = Color(0xFFD0BCFF),
        secondary = Color(0xFF6750A4), // Unread badge
        onSecondary = Color(0xFFFFFFFF), // Unread badge text
        secondaryContainer = Color(0xFFE9DDFF), // Navigation bar selector pill & progress indicator (remaining)
        onSecondaryContainer = Color(0xFF24005A), // Navigation bar selector icon
        tertiary = Color(0xFF3F6E4F), // Downloaded badge
        onTertiary = Color(0xFFFFFFFF), // Downloaded badge text
        tertiaryContainer = Color(0xFFC0F0CE),
        onTertiaryContainer = Color(0xFF00210D),
        background = Color(0xFFF9F7FC),
        onBackground = Color(0xFF1C1B20),
        surface = Color(0xFFF9F7FC),
        onSurface = Color(0xFF1C1B20),
        surfaceVariant = Color(0xFFE7E0EC), // Navigation bar background (ThemePrefWidget)
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
