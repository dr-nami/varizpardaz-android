package com.varizpardaz.listener.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.varizpardaz.listener.core.Channel
import com.varizpardaz.listener.core.Classification
import com.varizpardaz.listener.core.Classifier
import com.varizpardaz.listener.core.ConfigLoader
import com.varizpardaz.listener.core.ConfigResult
import com.varizpardaz.listener.core.DropReason
import com.varizpardaz.listener.core.IncomingMessage
import com.varizpardaz.listener.core.InvalidPatternException
import com.varizpardaz.listener.core.Normalizer
import com.varizpardaz.listener.core.RegexDialect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The contract fixtures and the regex-dialect cases, run on the phone's own regex engine. The JVM tests in `:core`
 * run the same files, but Android uses ICU, which rejects or reads some patterns differently (this suite found a
 * bare `}` that crashed the app on its first config fetch).
 */
@RunWith(AndroidJUnit4::class)
class DeviceEngineTest {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    private fun text(path: String) = assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    @Test
    fun normaliseVectors() {
        val vectors = Json.parseToJsonElement(text("normalise.json")).jsonArray
        assertTrue(vectors.size >= 6)
        for (v in vectors) {
            val o = v.jsonObject
            assertEquals(o.str("out"), Normalizer.normalize(o.str("in")!!))
        }
    }

    @Test
    fun dialectCases() {
        val cases = Json.parseToJsonElement(text("dialect-cases.json")).jsonObject["cases"]!!.jsonArray
        assertTrue(cases.size > 40)
        for (element in cases) {
            val case = element.jsonObject
            val name = case.str("name")!!
            val pattern = case.str("pattern")!!
            val flags = case.str("flags") ?: ""
            if (case["rejected"] != null) {
                try {
                    RegexDialect.compile(pattern, flags)
                    fail("'$name' should have been rejected")
                } catch (_: InvalidPatternException) {
                    // expected
                }
                continue
            }
            val match = try {
                RegexDialect.compile(pattern, flags).find(case.str("text")!!)
            } catch (e: InvalidPatternException) {
                fail("'$name' does not compile on this device: ${e.message}")
                null
            }
            if (case["match"].toString() == "true") {
                assertNotNull("'$name' should match", match)
                case["groups"]?.jsonObject?.forEach { (group, value) ->
                    assertEquals("'$name' group $group", value.toString().trim('"'), match!!.group(group))
                }
            } else {
                assertNull("'$name' should not match", match)
            }
        }
    }

    @Test
    fun smsFixturesClassifyAsExpected() {
        val config = (ConfigLoader.load(text("config.example.json").toByteArray()) as ConfigResult.Accepted).config
        var checked = 0
        for (bank in assets.list("sms").orEmpty()) {
            if (config.raw.banks.none { it.bank == bank }) continue
            for (file in assets.list("sms/$bank").orEmpty().filter { it.endsWith(".json") }) {
                val fixture = Json.parseToJsonElement(text("sms/$bank/$file")).jsonObject
                val expected = fixture["expected"]!!.jsonObject
                val result = Classifier.classify(
                    IncomingMessage(Channel.SMS, fixture.str("sender"), null, fixture.str("body")!!),
                    config,
                )
                val label = "$bank/$file"
                when (expected.str("class")) {
                    "deposit" -> {
                        assertTrue(label, result is Classification.Deposit)
                        expected.str("amount_rial")?.let { assertEquals(label, it, (result as Classification.Deposit).fields?.amountRial.toString()) }
                    }
                    "withdrawal" -> {
                        assertTrue(label, result is Classification.BalanceMarker)
                        assertEquals(label, expected.str("amount_rial"), (result as Classification.BalanceMarker).amountRial.toString())
                        assertEquals(label, expected.str("balance_rial"), result.balanceRial.toString())
                    }
                    "otp" -> assertEquals(label, DropReason.OTP, (result as Classification.Dropped).reason)
                    "other" -> assertEquals(label, DropReason.UNCLASSIFIED, (result as Classification.Dropped).reason)
                    else -> fail("$label: unknown class")
                }
                checked++
            }
        }
        assertTrue("expected the five testbank fixtures, ran $checked", checked >= 5)
    }
}
