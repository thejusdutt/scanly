package com.scanly.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FtsQueryTest {

    @Test
    fun `single word becomes quoted prefix match`() {
        assertThat(FtsQuery.sanitize("invoice")).isEqualTo("\"invoice\"*")
    }

    @Test
    fun `multiple words each become quoted prefix terms`() {
        assertThat(FtsQuery.sanitize("tax  2026")).isEqualTo("\"tax\"* \"2026\"*")
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertThat(FtsQuery.sanitize("  receipt \n")).isEqualTo("\"receipt\"*")
    }

    @Test
    fun `embedded quotes cannot break out of the term`() {
        assertThat(FtsQuery.sanitize("a\" OR \"b")).isEqualTo("\"a\"* \"OR\"* \"b\"*")
    }

    @Test
    fun `FTS operators are neutralized by quoting`() {
        assertThat(FtsQuery.sanitize("NEAR AND OR NOT"))
            .isEqualTo("\"NEAR\"* \"AND\"* \"OR\"* \"NOT\"*")
    }

    @Test
    fun `blank input yields harmless empty phrase`() {
        assertThat(FtsQuery.sanitize("   ")).isEqualTo("\"\"")
        assertThat(FtsQuery.sanitize("")).isEqualTo("\"\"")
    }

    @Test
    fun `input of only quotes yields harmless empty phrase term`() {
        assertThat(FtsQuery.sanitize("\"\"\"")).isEqualTo("\"\"*")
    }
}
