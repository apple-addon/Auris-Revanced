package com.music.bitchord.ui.reference

import java.util.Locale
import kotlin.math.roundToInt

internal fun formatDb(value: Float, digits: Int = 1): String =
    String.format(Locale.US, if (digits == 0) "%+.0f dB" else "%+.${digits}f dB", value)

internal fun formatFrequency(value: Float): String = when {
    value >= 1_000f -> String.format(Locale.US, "%.1fk", value / 1_000f).replace(".0k", "k")
    else -> "${value.roundToInt()} Hz"
}

internal fun formatPercent(value: Float): String = "${(value * 100f).roundToInt()}%"

internal fun formatMseb(value: Float): String =
    String.format(Locale.US, "%+d", value.roundToInt().coerceIn(-100, 100))
