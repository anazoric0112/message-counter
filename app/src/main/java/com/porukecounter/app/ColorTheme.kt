package com.porukecounter.app

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import androidx.core.graphics.ColorUtils

internal enum class ColorTheme(val style: Int, val label: String) {
    NEON(R.style.AppTheme, "Cyber Shark"),
    POMEGRANATE(R.style.AppTheme_Pomegranate, "Wither"),
    PRUSSIAN(R.style.AppTheme_Prussian, "Dusk"),
    ESPRESSO(R.style.AppTheme_Espresso, "Coffee Shop"),
    JADE(R.style.AppTheme_Jade, "Garden"),
    PETROL(R.style.AppTheme_Petrol, "Console"),
    AUBERGINE(R.style.AppTheme_Aubergine, "Nostalgia"),
    TWILIGHT(R.style.AppTheme_Twilight, "Dim Light"),
    FOREST(R.style.AppTheme_Forest, "Eden"),
    CITRUS(R.style.AppTheme_Citrus, "Neon Lights"),
    MAGENTA_GOLD(R.style.AppTheme_MagentaGold, "Pacman"),
    LIME_PINK(R.style.AppTheme_LimePink, "Mediterranean"),
    PINK_ORANGE(R.style.AppTheme_PinkOrange, "Synth Wave"),
    PINK_GREEN(R.style.AppTheme_PinkGreen, "Love Letter");
}

internal fun Context.savedColorTheme(): ColorTheme {
    val saved = getSharedPreferences("poruke", 0).getString("colorTheme", null)
    return ColorTheme.entries.firstOrNull { it.name == saved } ?: ColorTheme.NEON
}

internal val Context.primaryAccent: Int get() = themeColor(android.R.attr.colorAccent)
internal val Context.secondaryAccent: Int get() = themeColor(android.R.attr.colorControlActivated)
internal val Context.primaryTextAccent: Int get() = contrastColor(primaryAccent,
    listOf(tintedSurface(primaryAccent, 0.14f), tintedSurface(secondaryAccent)).maxBy { ColorUtils.calculateLuminance(it) })
internal val Context.secondaryTextAccent: Int get() = contrastColor(secondaryAccent, tintedSurface(secondaryAccent))

internal fun contrastColor(color: Int, background: Int, minimumContrast: Double = 4.5): Int {
    if (ColorUtils.calculateContrast(color, background) >= minimumContrast) return color
    val components = FloatArray(3)
    ColorUtils.colorToHSL(color, components)
    var lower = components[2]
    var upper = 1f
    var readable = Color.WHITE
    repeat(12) {
        components[2] = (lower + upper) / 2f
        val candidate = ColorUtils.HSLToColor(components)
        if (ColorUtils.calculateContrast(candidate, background) >= minimumContrast) {
            upper = components[2]
            readable = candidate
        } else {
            lower = components[2]
        }
    }
    return readable
}

private fun Context.themeColor(attribute: Int): Int = TypedValue().also {
    check(theme.resolveAttribute(attribute, it, true))
}.data