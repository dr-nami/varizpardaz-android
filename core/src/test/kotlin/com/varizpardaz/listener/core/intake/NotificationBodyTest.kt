package com.varizpardaz.listener.core.intake

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationBodyTest {
    @Test
    fun `title, main text and the rest are joined one per line`() {
        assertEquals("عنوان\nمتن\nزیرمتن", NotificationBody.compose("عنوان", "متن", null, "زیرمتن", emptyList()))
    }

    @Test
    fun `the expanded text replaces the collapsed one`() {
        assertEquals("T\nfull text here", NotificationBody.compose("T", "full text…", "full text here", null, emptyList()))
    }

    @Test
    fun `repeated pieces appear once and blanks vanish`() {
        assertEquals("a\nb", NotificationBody.compose("a", "a", "  ", "b", listOf("a", " ", "b")))
    }

    @Test
    fun `inbox style lines are kept in order`() {
        assertEquals("T\nx\ny", NotificationBody.compose("T", null, null, null, listOf("x", "y")))
    }

    @Test
    fun `nothing readable gives null`() {
        assertNull(NotificationBody.compose(null, "", " ", null, emptyList()))
    }
}
