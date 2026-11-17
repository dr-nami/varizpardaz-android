package com.varizpardaz.listener.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Behaviour of contract section 6 beyond the shared fixtures. */
class ClassifierTest {
    private fun config(json: String): CompiledConfig {
        val result = ConfigLoader.load(json.toByteArray(Charsets.UTF_8))
        check(result is ConfigResult.Accepted) { "rejected: ${(result as ConfigResult.Rejected).reason}" }
        return result.config
    }

    private fun sms(sender: String, body: String) = IncomingMessage(Channel.SMS, sender, null, body)

    private val base = """
        {
          "version": 3, "issued_at": "2026-11-01T10:00:00+03:30",
          "otp_patterns": [ { "pattern": "رمز\\s*پویا", "flags": "" } ],
          "deposit_keywords": ["واریز", "افزایش"],
          "banks": [ {
            "bank": "b1",
            "senders": ["+98 100 200", "Bank One"],
            "notification_packages": ["ir.example.bank"],
            "deposit": [ { "id": "b1-dep", "pattern": "واریز (?<amount>[0-9,]+) مانده (?<balance>[0-9,]+)", "unit": "toman" } ],
            "withdrawal": [ { "id": "b1-wd", "pattern": "برداشت (?<amount>[0-9,]+) مانده (?<balance>[0-9,]+)", "unit": "toman" } ]
          } ]
        }
    """.trimIndent()

    @Test
    fun `unknown sender is dropped before anything else`() {
        val cfg = config(base)
        // Even an OTP text from a stranger counts as a sender drop, not an OTP drop.
        val r = Classifier.classify(sms("Stranger", "رمز پویا 123456"), cfg)
        assertIs<Classification.Dropped>(r)
        assertEquals(DropReason.SENDER, r.reason)
    }

    @Test
    fun `sender match ignores spaces and a leading plus on both sides`() {
        val cfg = config(base)
        for (sender in listOf("98100200", "+98100200", "+98 100 200", "BankOne", " Bank One ")) {
            val r = Classifier.classify(sms(sender, "واریز 1,000 مانده 5,000"), cfg)
            assertIs<Classification.Deposit>(r, sender)
        }
        val case = Classifier.classify(sms("bankone", "واریز 1,000 مانده 5,000"), cfg)
        assertEquals(DropReason.SENDER, assertIs<Classification.Dropped>(case).reason)
    }

    @Test
    fun `otp beats a deposit template that would also match`() {
        val cfg = config(base)
        val r = Classifier.classify(sms("BankOne", "رمز پویا واریز 1,000 مانده 5,000"), cfg)
        assertEquals(DropReason.OTP, assertIs<Classification.Dropped>(r).reason)
    }

    @Test
    fun `toman templates are converted to rial`() {
        val cfg = config(base)
        val deposit = assertIs<Classification.Deposit>(Classifier.classify(sms("BankOne", "واریز ۵۰۰٬۰۰۰ مانده ۲,۰۰۰"), cfg))
        assertEquals("b1-dep", deposit.templateId)
        assertEquals(5_000_000L, deposit.fields?.amountRial)
        assertEquals(20_000L, deposit.fields?.balanceRial)

        val marker = assertIs<Classification.BalanceMarker>(Classifier.classify(sms("BankOne", "برداشت 100 مانده 900"), cfg))
        assertEquals(1_000L, marker.amountRial)
        assertEquals(9_000L, marker.balanceRial)
    }

    @Test
    fun `a withdrawal text never becomes a deposit upload`() {
        val cfg = config(base)
        // Usable amounts: a marker, even though the text also contains a deposit keyword.
        val marker = assertIs<Classification.BalanceMarker>(
            Classifier.classify(sms("BankOne", "برداشت 100 مانده 900 افزایش"), cfg),
        )
        assertEquals(1_000L, marker.amountRial)

        // The withdrawal pattern matches but the balance has a decimal part that cannot be read. The text
        // contains a deposit keyword, yet it must be dropped, not sent as a keyword-fallback deposit.
        val lenient = config(
            base.replace(
                "برداشت (?<amount>[0-9,]+) مانده (?<balance>[0-9,]+)",
                "برداشت (?<amount>[0-9,]+) مانده (?<balance>[0-9,.]+)",
            ),
        )
        val dropped = Classifier.classify(sms("BankOne", "برداشت 100 مانده 900.5 افزایش"), lenient)
        assertEquals(DropReason.UNCLASSIFIED, assertIs<Classification.Dropped>(dropped).reason)
    }

    @Test
    fun `keyword fallback uploads with no template and unknown text is dropped`() {
        val cfg = config(base)
        val fallback = assertIs<Classification.Deposit>(Classifier.classify(sms("BankOne", "افزایش موجودی شما"), cfg))
        assertNull(fallback.templateId)
        assertEquals("b1", fallback.bank)

        val other = Classifier.classify(sms("BankOne", "جشنواره"), cfg)
        assertEquals(DropReason.UNCLASSIFIED, assertIs<Classification.Dropped>(other).reason)
    }

    @Test
    fun `notifications are gated by package, not sender`() {
        val cfg = config(base)
        val ok = Classifier.classify(IncomingMessage(Channel.NOTIFICATION, null, "ir.example.bank", "واریز 1,000 مانده 5,000"), cfg)
        assertIs<Classification.Deposit>(ok)
        val wrong = Classifier.classify(IncomingMessage(Channel.NOTIFICATION, null, "ir.other", "واریز 1,000 مانده 5,000"), cfg)
        assertEquals(DropReason.SENDER, assertIs<Classification.Dropped>(wrong).reason)
        // A notification must not pass the gate through a sender string.
        val sneaky = Classifier.classify(IncomingMessage(Channel.NOTIFICATION, "BankOne", "ir.other", "واریز 1,000 مانده 5,000"), cfg)
        assertEquals(DropReason.SENDER, assertIs<Classification.Dropped>(sneaky).reason)
    }

    @Test
    fun `empty sender and empty package never match`() {
        val cfg = config(base.replace("\"senders\": [\"+98 100 200\", \"Bank One\"]", "\"senders\": [\"\", \" \"]"))
        val r = Classifier.classify(sms("", "واریز 1,000 مانده 5,000"), cfg)
        assertEquals(DropReason.SENDER, assertIs<Classification.Dropped>(r).reason)
    }

    @Test
    fun `huge amounts that overflow are not trusted`() {
        val cfg = config(base)
        val r = Classifier.classify(sms("BankOne", "برداشت 999999999999999999 مانده 5"), cfg)
        // 999999999999999999 toman is about 9.99e18 rial, above Long.MAX_VALUE (9.22e18): no marker.
        assertEquals(DropReason.UNCLASSIFIED, assertIs<Classification.Dropped>(r).reason)
    }

    @Test
    fun `a template that does not compile is skipped, the rest still works`() {
        val cfg = config(base.replace("\"deposit\": [ {", "\"deposit\": [ { \"id\": \"bad\", \"pattern\": \"(?<=x)y\" }, {"))
        assertEquals(listOf("bad"), cfg.skippedTemplates)
        assertIs<Classification.Deposit>(Classifier.classify(sms("BankOne", "واریز 1,000 مانده 5,000"), cfg))
    }

    @Test
    fun `an otp pattern that does not compile rejects the whole config`() {
        val bad = base.replace("رمز\\\\s*پویا", "(?<=x)y")
        val result = ConfigLoader.load(bad.toByteArray(Charsets.UTF_8))
        assertEquals(ConfigResult.Reason.INVALID_OTP_PATTERN, assertIs<ConfigResult.Rejected>(result).reason)
    }

    @Test
    fun `message and wire toString never expose the text`() {
        val secret = "رمز پویا 998877"
        assertTrue(!IncomingMessage(Channel.SMS, "x", null, secret).toString().contains("998877"))
    }
}
