package com.github.supersliser.utils

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

fun parseEpochLikeToMillis(value: Long): Long {
    return if (value in -99_999_999_999L..99_999_999_999L) value * 1000L else value
}

fun parseTimestampStringToMillis(raw: String): Long? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    val numeric = trimmed.toLongOrNull()
    if (numeric != null) return parseEpochLikeToMillis(numeric)

    var normalized = trimmed
    if (normalized.indexOf(' ') >= 0 && normalized.indexOf('T') < 0) {
        normalized = normalized.replace(' ', 'T')
    }

    // Supabase/Postgres can send microseconds; reduce to millis precision for parsing.
    val fracStart = normalized.indexOf('.')
    if (fracStart >= 0) {
        var fracEnd = normalized.indexOf('Z', fracStart)
        if (fracEnd < 0) {
            fracEnd = normalized.indexOf('+', fracStart)
            if (fracEnd < 0) fracEnd = normalized.indexOf('-', fracStart + 1)
            if (fracEnd < 0) fracEnd = normalized.length
        }

        val frac = normalized.substring(fracStart + 1, fracEnd)
        if (frac.length > 3) {
            normalized = normalized.substring(0, fracStart + 4) + normalized.substring(fracEnd)
        }
    }

    val patterns = arrayOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSX",
        "yyyy-MM-dd'T'HH:mm:ssX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss.SSS",
        "yyyy-MM-dd HH:mm:ss"
    )

    for (pattern in patterns) {
        val parser = SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val parsed = runCatching { parser.parse(normalized) }.getOrNull()
        if (parsed != null) return parsed.time
    }

    return null
}
