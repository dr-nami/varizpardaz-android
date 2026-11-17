package com.varizpardaz.listener.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegexDialectTest {
    private fun find(pattern: String, text: String, flags: String = "") =
        RegexDialect.compile(pattern, flags).find(text)

    @Test
    fun `named groups are readable by their contract names including underscores`() {
        val m = assertNotNull(find("(?<amount>[0-9,]+) (?<origin_last4>[0-9]{4})", "5,000 1234"))
        assertEquals("5,000", m.group("amount"))
        assertEquals("1234", m.group("origin_last4"))
        assertNull(m.group("balance"))
    }

    @Test
    fun `a group that did not participate is null`() {
        val m = assertNotNull(find("a(?<x>b)?c", "ac"))
        assertNull(m.group("x"))
    }

    @Test
    fun `word boundary uses ascii word characters like v8`() {
        // Persian letters are not word characters in ECMAScript, so there is a boundary before 'password'.
        assertTrue(RegexDialect.compile("\\bpassword\\b", "i").matches("رمزpassword"))
        assertTrue(RegexDialect.compile("\\b(otp|password|passcode)\\b", "i").matches("Your OTP: 1234"))
        assertFalse(RegexDialect.compile("\\bpassword\\b", "i").matches("xpassword"))
        assertFalse(RegexDialect.compile("\\bpassword\\b", "i").matches("password1"))
        assertTrue(RegexDialect.compile("\\Bass", "").matches("password"))
    }

    @Test
    fun `digit class is ascii only`() {
        assertNull(find("\\d", "۵"))
        assertNotNull(find("\\d", "5"))
        assertNotNull(find("[\\d,]+", "1,234"))
    }

    @Test
    fun `whitespace class matches the ecmascript set`() {
        assertNotNull(find("a\\sb", "a b"))
        assertNotNull(find("a\\sb", "a b"))
        assertNull(find("a\\sb", "a\u0085b"))
        assertNotNull(find("a\\Sb", "a\u0085b"))
    }

    @Test
    fun `dollar means the very end and dot excludes line terminators`() {
        assertNotNull(find("5$", "x5"))
        assertNull(find("5$", "5 y"))
        assertNull(find("a.b", "a\nb"))
        assertNotNull(find("a.b", "a\u0085b"))
    }

    @Test
    fun `case-insensitive flag covers ascii and unicode`() {
        assertNotNull(find("otp", "Your OTP", "i"))
        assertNull(find("otp", "Your OTP", ""))
    }

    @Test
    fun `lazy quantifiers are fine`() {
        assertNotNull(find("a+?", "aaa"))
        assertNotNull(find("a??b", "ab"))
    }

    @Test
    fun `characters special in java classes are literal as in ecmascript`() {
        assertNotNull(find("[[]", "["))
        assertNotNull(find("[a&&b]", "&"))
        assertNotNull(find("a{b", "a{b"))
        assertNotNull(find("[a\\]]", "]"))
    }

    @Test
    fun `constructs outside the dialect are rejected`() {
        val rejected = listOf(
            "(?<=a)b", "(?<!a)b", "(?>a)", "a++", "a*+", "a?+", "a{2}+", "(?i)a", "(?i:a)", "(?-i)a",
            "\\k<x>", "\\p{L}", "\\Qa\\E", "\\A", "\\Z", "\\h", "\\R", "[]", "[^]", "[abc", "(?<1x>a)",
            "(?<a_b>x)(?<ab>y)", "abc\\",
        )
        for (pattern in rejected) {
            assertFailsWith<InvalidPatternException>(pattern) { RegexDialect.compile(pattern, "") }
        }
    }

    @Test
    fun `unknown flags are rejected`() {
        assertFailsWith<InvalidPatternException> { RegexDialect.compile("a", "g") }
        assertFailsWith<InvalidPatternException> { RegexDialect.compile("a", "im") }
    }

    @Test
    fun `patterns that do not compile are rejected`() {
        assertFailsWith<InvalidPatternException> { RegexDialect.compile("(", "") }
        assertFailsWith<InvalidPatternException> { RegexDialect.compile("*a", "") }
    }

    @Test
    fun `contract example patterns compile`() {
        val patterns = listOf(
            "رمز\\s*(پویا|یکبار|دوم|عبور)" to "",
            "\\b(otp|password|passcode)\\b" to "i",
            "کد\\s*(تایید|تأیید|ورود|فعال\\s*سازی|امنیتی)" to "",
            "واریز:\\s*(?<amount>[0-9,]+)\\s*ریال\\s*حساب:\\s*(?<account>[0-9]+)\\s*مانده:\\s*(?<balance>[0-9,]+)" to "",
        )
        for ((pattern, flags) in patterns) RegexDialect.compile(pattern, flags)
    }
}
