package com.varizpardaz.listener.core

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the shared fixtures in `contracts/fixtures`, the same files the server's tests run, so both
 * sides classify and normalise identically.
 */
class ContractFixturesTest {
    @TestFactory
    fun `every normalise vector`(): List<DynamicTest> {
        val vectors = Fixtures.json("normalise.json").jsonArray
        assertTrue(vectors.isNotEmpty(), "normalise.json has no vectors")
        return vectors.mapIndexed { index, element ->
            val vector = element.jsonObject
            val input = vector.str("in")!!
            val expected = vector.str("out")!!
            dynamicTest("normalise vector #$index") {
                assertEquals(expected, Normalizer.normalize(input))
            }
        }
    }

    @TestFactory
    fun `every sms fixture`(): List<DynamicTest> {
        val config = loadExampleConfig()
        val configuredBanks = config.raw.banks.map { it.bank }.toSet()
        val fixtures = Fixtures.smsFixtures()
        assertTrue(fixtures.isNotEmpty(), "no sms fixtures found under ${Fixtures.dir}")

        return fixtures.map { fixture ->
            dynamicTest(fixture.name) {
                if (fixture.bank !in configuredBanks) {
                    // A real bank whose templates are not in config.example.json yet cannot be classified here.
                    // Not a failure, but never silent either.
                    println("SKIPPED ${fixture.name}: bank '${fixture.bank}' is not in config.example.json")
                    return@dynamicTest
                }
                val result = Classifier.classify(
                    IncomingMessage(Channel.SMS, fixture.sender, null, fixture.body),
                    config,
                )
                assertMatchesExpected(fixture, result)
            }
        }
    }

    @Test
    fun `testbank fixtures are all exercised`() {
        val testbank = Fixtures.smsFixtures().filter { it.bank == "testbank" }
        assertTrue(testbank.size >= 5, "expected the five testbank fixtures, found ${testbank.size}")
        val classes = testbank.map { it.expected.str("class") }.toSet()
        assertEquals(setOf("deposit", "withdrawal", "otp", "other"), classes)
    }

    /**
     * Contract section 11: a template may be `verified` only if every fixture of its bank is
     * `real-anonymised`. Synthetic fixtures test plumbing only.
     */
    @Test
    fun `no template is verified without real samples`() {
        val config = loadExampleConfig()
        val fixtures = Fixtures.smsFixtures()
        for (bank in config.raw.banks) {
            val verified = (bank.deposit + bank.withdrawal).filter { it.status == "verified" }
            if (verified.isEmpty()) continue
            val bankFixtures = fixtures.filter { it.bank == bank.bank }
            assertTrue(bankFixtures.isNotEmpty(), "bank ${bank.bank} has verified templates but no fixtures")
            assertTrue(
                bankFixtures.all { it.source == "real-anonymised" },
                "bank ${bank.bank} has verified templates backed by synthetic fixtures",
            )
        }
    }

    private fun assertMatchesExpected(fixture: SmsFixture, result: Classification) {
        val expected = fixture.expected
        when (val expectedClass = expected.str("class")) {
            "deposit" -> {
                assertIs<Classification.Deposit>(result, fixture.name)
                val amount = expected.str("amount_rial")
                if (amount == null) {
                    // Keyword fallback: uploaded with no template so the server can learn the format.
                    assertNull(result.templateId, "${fixture.name}: expected the keyword fallback")
                } else {
                    val fields = result.fields
                    assertEquals(amount, fields?.amountRial?.toString(), "${fixture.name}: amount_rial")
                    assertEquals(expected.str("balance_rial"), fields?.balanceRial?.toString(), "${fixture.name}: balance_rial")
                    assertEquals(expected.str("account"), fields?.account, "${fixture.name}: account")
                }
            }
            "withdrawal" -> {
                assertIs<Classification.BalanceMarker>(result, fixture.name)
                assertEquals(expected.str("amount_rial"), result.amountRial.toString(), "${fixture.name}: amount_rial")
                assertEquals(expected.str("balance_rial"), result.balanceRial.toString(), "${fixture.name}: balance_rial")
                assertEquals(expected.str("account"), result.account, "${fixture.name}: account")
            }
            "otp" -> {
                assertIs<Classification.Dropped>(result, fixture.name)
                assertEquals(DropReason.OTP, result.reason, fixture.name)
            }
            "other" -> {
                assertIs<Classification.Dropped>(result, fixture.name)
                assertEquals(DropReason.UNCLASSIFIED, result.reason, fixture.name)
            }
            else -> error("${fixture.name}: unknown expected class '$expectedClass'")
        }
    }
}
