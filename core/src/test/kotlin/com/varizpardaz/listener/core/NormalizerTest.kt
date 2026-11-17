package com.varizpardaz.listener.core

import kotlin.test.Test
import kotlin.test.assertEquals

class NormalizerTest {
    @Test
    fun `persian and arabic-indic digits become ascii`() {
        assertEquals("0123456789", Normalizer.normalize("۰۱۲۳۴۵۶۷۸۹"))
        assertEquals("0123456789", Normalizer.normalize("٠١٢٣٤٥٦٧٨٩"))
    }

    @Test
    fun `arabic letter forms are unified`() {
        assertEquals("ملی بانک", Normalizer.normalize("ملي بانك"))
    }

    @Test
    fun `thousands separators become a comma`() {
        assertEquals("1,234,567", Normalizer.normalize("۱٬۲۳۴،۵۶۷"))
    }

    @Test
    fun `bidi controls are removed but zwnj stays`() {
        assertEquals("ab", Normalizer.normalize("a‎b‏"))
        val zwnj = "می‌شود"
        assertEquals(zwnj, Normalizer.normalize(zwnj))
        for (cp in listOf('‪', '‫', '‬', '‭', '‮', '⁦', '⁧', '⁨', '⁩')) {
            assertEquals("xy", Normalizer.normalize("x${cp}y"), "U+%04X".format(cp.code))
        }
    }

    @Test
    fun `whitespace runs collapse and the ends are trimmed`() {
        assertEquals("a b c", Normalizer.normalize("  a \t\r\n b  c ﻿"))
        assertEquals("", Normalizer.normalize(" \n\t "))
    }

    @Test
    fun `characters that ecmascript does not treat as whitespace are kept`() {
        assertEquals("a\u0085b", Normalizer.normalize("a\u0085b"))
    }

    @Test
    fun `removing a bidi control between spaces does not leave a double space`() {
        assertEquals("a b", Normalizer.normalize("a ‎ b"))
    }

    @Test
    fun `normalising twice changes nothing`() {
        val text = "‫حساب‬ ۱۲۳۴\r\n\tمانده ۱٬۲۰۰"
        assertEquals(Normalizer.normalize(text), Normalizer.normalize(Normalizer.normalize(text)))
    }

    @Test
    fun `sender normalisation drops spaces and one leading plus`() {
        assertEquals("98912", Normalizer.sender(" +98 912 "))
        assertEquals("BankMellat", Normalizer.sender("BankMellat"))
        assertEquals("+98", Normalizer.sender("++98"))
        assertEquals("bankmellat", Normalizer.sender("bankmellat"))
    }
}
