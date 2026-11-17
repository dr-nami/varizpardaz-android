package com.varizpardaz.listener.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DisplayNameTest {
    @Test
    fun `an ordinary persian or latin name is shown as it is`() {
        assertEquals("فروشگاه نمونه", DisplayName.clean("فروشگاه نمونه"))
        assertEquals("Example Shop", DisplayName.clean("Example Shop"))
    }

    @Test
    fun `nothing printable means no name`() {
        assertNull(DisplayName.clean(null))
        assertNull(DisplayName.clean(""))
        assertNull(DisplayName.clean("   \n\t  "))
        assertNull(DisplayName.clean("\u0000‮​"))
    }

    @Test
    fun `white space is collapsed into single spaces and the ends are trimmed`() {
        assertEquals("a b c", DisplayName.clean("  a \n\n b\t  c  "))
    }

    @Test
    fun `control characters and direction overrides are removed, the persian half-space stays`() {
        assertEquals("abc", DisplayName.clean("a\u0007b‮c⁦"))
        assertEquals("می‌خواهم", DisplayName.clean("می‌خواهم"))
        assertEquals("ab", DisplayName.clean("a​b")) // zero-width space is invisible junk
    }

    @Test
    fun `a very long name is cut at the limit, without a dangling space`() {
        val long = "x".repeat(DisplayName.MAX_LENGTH - 1) + " " + "y".repeat(50)
        val cleaned = DisplayName.clean(long)!!
        assertTrue(cleaned.length <= DisplayName.MAX_LENGTH)
        assertEquals(cleaned.trimEnd(), cleaned)
        assertEquals("x".repeat(DisplayName.MAX_LENGTH - 1), cleaned)
    }
}
