package com.music.bitchord.ui.policy

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.settings.MusicProvider

/** Every new Phase 6 surface opts into the same provider token contract. */
enum class ProviderAudioSurface {
    SETTINGS,
    EQ,
    MSEB,
    PEQ,
    PRESETS,
    CONFLICT,
    PIPELINE,
}

/**
 * Semantic colors for provider-aware audio UI.
 *
 * These are roles selected from the caller's existing [ColorScheme], never raw
 * brand colors painted over an unknown light/dark background. Every foreground
 * is paired with the container Material assigns to it.
 */
data class ProviderAudioTokens(
    val provider: MusicProvider,
    val surface: ProviderAudioSurface,
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val positive: Color,
    val onPositive: Color,
    val positiveContainer: Color,
    val onPositiveContainer: Color,
    val failure: Color,
    val onFailure: Color,
    val failureContainer: Color,
    val onFailureContainer: Color,
    val canvas: Color,
    val onCanvas: Color,
    val container: Color,
    val onContainer: Color,
    val dialogContainer: Color,
    val secondaryText: Color,
    val neutralContainer: Color,
    val onNeutralContainer: Color,
    val outline: Color,
    val cardShape: Shape,
    val badgeShape: Shape,
    val dialogShape: Shape,
)

fun providerAudioTokens(
    provider: MusicProvider,
    surface: ProviderAudioSurface,
    scheme: ColorScheme,
): ProviderAudioTokens {
    // Apple's provider look already owns primary in BitChordTheme. YouTube and
    // Spotify use the existing secondary family so wrapping their surfaces is
    // visibly distinct without introducing a second, potentially unreadable
    // brand palette. The copied MaterialTheme below exposes the chosen family
    // as primary to stock chips, sliders, buttons and text fields.
    val apple = provider == MusicProvider.APPLE
    return ProviderAudioTokens(
        provider = provider,
        surface = surface,
        accent = if (apple) scheme.primary else scheme.secondary,
        onAccent = if (apple) scheme.onPrimary else scheme.onSecondary,
        accentContainer = if (apple) scheme.primaryContainer else scheme.secondaryContainer,
        onAccentContainer = if (apple) scheme.onPrimaryContainer else scheme.onSecondaryContainer,
        positive = scheme.tertiary,
        onPositive = scheme.onTertiary,
        positiveContainer = scheme.tertiaryContainer,
        onPositiveContainer = scheme.onTertiaryContainer,
        failure = scheme.error,
        onFailure = scheme.onError,
        failureContainer = scheme.errorContainer,
        onFailureContainer = scheme.onErrorContainer,
        canvas = scheme.background,
        onCanvas = scheme.onBackground,
        container = scheme.surfaceContainerLow,
        onContainer = scheme.onSurface,
        dialogContainer = scheme.surfaceContainerHigh,
        secondaryText = scheme.onSurfaceVariant,
        neutralContainer = scheme.surfaceVariant,
        onNeutralContainer = scheme.onSurfaceVariant,
        outline = scheme.outlineVariant,
        cardShape = RoundedCornerShape(if (apple) 22.dp else 18.dp),
        badgeShape = RoundedCornerShape(50),
        dialogShape = RoundedCornerShape(if (apple) 30.dp else 26.dp),
    )
}

private val LocalProviderAudioTokens = staticCompositionLocalOf<ProviderAudioTokens?> { null }

/** Current tokens, with a safe Material-derived fallback for isolated previews/tests. */
val currentProviderAudioTokens: ProviderAudioTokens
    @Composable get() = LocalProviderAudioTokens.current ?: providerAudioTokens(
        provider = MusicProvider.YOUTUBE,
        surface = ProviderAudioSurface.SETTINGS,
        scheme = MaterialTheme.colorScheme,
    )

/**
 * Applies provider tokens without replacing the app's typography or dark/light
 * scheme. Wrap each new settings/EQ/MSEB/PEQ/preset/conflict/pipeline root in
 * this function; nested standard Material controls inherit the same accents.
 */
@Composable
fun ProviderAudioTheme(
    provider: MusicProvider,
    surface: ProviderAudioSurface,
    content: @Composable () -> Unit,
) {
    val base = MaterialTheme.colorScheme
    val tokens = providerAudioTokens(provider, surface, base)
    val themedScheme = base.copy(
        primary = tokens.accent,
        onPrimary = tokens.onAccent,
        primaryContainer = tokens.accentContainer,
        onPrimaryContainer = tokens.onAccentContainer,
    )
    CompositionLocalProvider(LocalProviderAudioTokens provides tokens) {
        MaterialTheme(
            colorScheme = themedScheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
            content = content,
        )
    }
}
