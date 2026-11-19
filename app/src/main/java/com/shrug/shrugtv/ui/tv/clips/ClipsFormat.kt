package com.shrug.shrugtv.ui.tv.clips

import java.util.Locale

/** Small text helpers of the clips page, kept free of Compose so they can be tested. */
object ClipsFormat {

    /** A clip length as people say it: 0:18, 1:05, 1:02:03 (the cards used to show 00:00:18). */
    fun duration(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = s % 3600 / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%d:%02d", m, sec)
    }

    /** Views short enough for a badge: 950, 1.2K, 12K, 1.2M (decimal sign of the given locale, no trailing ".0"). */
    fun compactCount(n: Int, locale: Locale = Locale.getDefault()): String = when {
        n >= 1_000_000 -> scaled(n / 1_000_000.0, "M", locale)
        n >= 10_000 -> String.format(locale, "%dK", Math.round(n / 1000.0))
        n >= 1000 -> scaled(n / 1000.0, "K", locale)
        else -> n.toString()
    }

    private fun scaled(v: Double, suffix: String, locale: Locale): String {
        val tenths = Math.round(v * 10)
        return if (tenths % 10 == 0L) String.format(locale, "%d%s", tenths / 10, suffix)
        else String.format(locale, "%d%s%d%s", tenths / 10, decimalSign(locale), tenths % 10, suffix)
    }

    private fun decimalSign(locale: Locale): Char = java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator
}
