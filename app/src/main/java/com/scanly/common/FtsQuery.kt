package com.scanly.common

/** Quotes raw user input into a safe FTS4 prefix-match query. Pure logic, unit-tested. */
object FtsQuery {

    fun sanitize(raw: String): String =
        raw.trim().split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { "\"" + it.replace("\"", "") + "\"*" }
            .ifBlank { "\"\"" }
}
