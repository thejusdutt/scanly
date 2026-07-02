package com.scanly.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Smart document naming, e.g. "yyyy-MM-dd_Receipt_{n}". Supported tokens:
 *   {date:<fmt>}  - any SimpleDateFormat pattern (default yyyy-MM-dd)
 *   {n}           - zero-padded counter
 *   {type}        - document type label
 */
object RenamePattern {

    fun format(
        pattern: String,
        counter: Int = 1,
        type: String = "Doc",
        now: Date = Date(),
    ): String {
        var out = pattern
        // Escape BOTH braces: Android's ICU regex rejects a bare closing `}` that the
        // JVM's java.util.regex accepts, so unit tests alone would not catch it.
        out = Regex("\\{date(?::([^}]+))?\\}").replace(out) { m ->
            val fmt = m.groupValues[1].ifBlank { "yyyy-MM-dd" }
            runCatching { SimpleDateFormat(fmt, Locale.US).format(now) }.getOrDefault("")
        }
        out = out.replace("{n}", counter.toString().padStart(3, '0'))
        out = out.replace("{type}", type)
        return out.ifBlank { "Document" }
    }

    const val DEFAULT = "{date}_{type}_{n}"
}
