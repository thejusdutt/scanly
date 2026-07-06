package com.scanly.platform

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** cropQuad persistence (PageEntity) depends on this round-tripping exactly. */
class DocumentQuadTest {

    @Test
    fun `serialize then deserialize returns the same quad`() {
        val quad = DocumentQuad(
            QuadPoint(1.5f, 2.25f),
            QuadPoint(1023.75f, 0f),
            QuadPoint(1020f, 767.5f),
            QuadPoint(0.125f, 760f),
        )
        assertThat(DocumentQuad.deserialize(quad.serialize())).isEqualTo(quad)
    }

    @Test
    fun `full-frame quad round-trips`() {
        val quad = DocumentQuad.full(4000, 3000)
        assertThat(DocumentQuad.deserialize(quad.serialize())).isEqualTo(quad)
    }

    @Test
    fun `deserialize returns null on malformed input`() {
        assertThat(DocumentQuad.deserialize("")).isNull()
        assertThat(DocumentQuad.deserialize("1,2,3")).isNull()
        assertThat(DocumentQuad.deserialize("a,b,c,d,e,f,g,h")).isNull()
        assertThat(DocumentQuad.deserialize("1,2,3,4,5,6,7,8,9,10")).isNull()
    }
}
