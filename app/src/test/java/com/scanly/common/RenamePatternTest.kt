package com.scanly.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RenamePatternTest {

    private val fixed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse("2026-06-30")!!

    @Test
    fun default_pattern_expands_date_type_counter() {
        val out = RenamePattern.format(RenamePattern.DEFAULT, counter = 1, type = "Receipt", now = fixed)
        assertThat(out).isEqualTo("2026-06-30_Receipt_001")
    }

    @Test
    fun custom_date_format_is_respected() {
        val out = RenamePattern.format("{date:yyyy}-{type}", type = "Doc", now = fixed)
        assertThat(out).isEqualTo("2026-Doc")
    }

    @Test
    fun blank_pattern_falls_back() {
        assertThat(RenamePattern.format("", now = Date())).isEqualTo("Document")
    }
}
