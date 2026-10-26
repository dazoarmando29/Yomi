package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Yomi premium design tokens (new screen designs, yomi_reader/DESIGN.md)
 * Dark-first: deep obsidian surfaces with violet + cyan electric accents
 *
 * Key tokens:
 * Surface #111319, containers #0C0E14 -> #33343B
 * Primary #D0BCFF (violet), Secondary #4CD7F6 (cyan), Tertiary #ADC6FF (sapphire)
 */
internal object TachiyomiColorScheme : BaseColorScheme() {

    override val darkScheme = darkColorScheme(
        primary = Color(0xFFD0BCFF),
        onPrimary = Color(0xFF3C0091),
        primaryContainer = Color(0xFFA078FF),
        onPrimaryContainer = Color(0xFF340080),
        inversePrimary = Color(0xFF6D3BD7),
        secondary = Color(0xFF4CD7F6), // Unread badge / reading progress (cyan)
        onSecondary = Color(0xFF003640), // Unread badge text
        secondaryContainer = Color(0xFF03B5D3), // Active filter chips / counters
        onSecondaryContainer = Color(0xFF00424E), // Active filter chip text
        tertiary = Color(0xFFADC6FF), // Page labels / secondary metadata (sapphire)
        onTertiary = Color(0xFF002E6A),
        tertiaryContainer = Color(0xFF4D8EFF),
        onTertiaryContainer = Color(0xFF00285D),
        background = Color(0xFF111319),
        onBackground = Color(0xFFE2E2EB),
        surface = Color(0xFF111319),
        onSurface = Color(0xFFE2E2EB),
        surfaceVariant = Color(0xFF33343B), // Card surface / navigation bar background
        onSurfaceVariant = Color(0xFFCBC3D7),
        surfaceTint = Color(0xFFD0BCFF),
        inverseSurface = Color(0xFFE2E2EB),
        inverseOnSurface = Color(0xFF2E3037),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF958EA0),
        outlineVariant = Color(0xFF494454),
        surfaceContainerLowest = Color(0xFF0C0E14), // Deepest wells / cover scrims
        surfaceContainerLow = Color(0xFF191B22), // Elevated surface
        surfaceContainer = Color(0xFF1E1F26), // Card surface / navigation bar background
        surfaceContainerHigh = Color(0xFF282A30),
        surfaceContainerHighest = Color(0xFF33343B),
    )

    override val lightScheme = lightColorScheme(
        primary = Color(0xFF6D3BD7),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFEADDFF),
        onPrimaryContainer = Color(0xFF23005C),
        inversePrimary = Color(0xFFD0BCFF),
        secondary = Color(0xFF006B7E), // Unread badge
        onSecondary = Color(0xFFFFFFFF), // Unread badge text
        secondaryContainer = Color(0xFFB4ECFA), // Navigation bar selector pill & progress indicator
        onSecondaryContainer = Color(0xFF002E36), // Navigation bar selector icon
        tertiary = Color(0xFF004395), // Downloaded badge
        onTertiary = Color(0xFFFFFFFF), // Downloaded badge text
        tertiaryContainer = Color(0xFFD8E2FF),
        onTertiaryContainer = Color(0xFF001A42),
        background = Color(0xFFF9F7FC),
        onBackground = Color(0xFF1C1B20),
        surface = Color(0xFFF9F7FC),
        onSurface = Color(0xFF1C1B20),
        surfaceVariant = Color(0xFFE7E0EC), // Navigation bar background
        onSurfaceVariant = Color(0xFF49454F),
        surfaceTint = Color(0xFF6D3BD7),
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
