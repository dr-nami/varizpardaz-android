package com.varizpardaz.listener.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs `regex/dialect-cases.json` on the JVM. The very same file is run on a device by the instrumented
 * `DeviceEngineTest`, because Android's regex engine (ICU) disagrees with the JVM's in places that matter.
 */
class DialectCasesTest {
    @TestFactory
    fun `every dialect case`(): List<DynamicTest> {
        val text = assertNotNull(javaClass.getResourceAsStream("/regex/dialect-cases.json")).readBytes().toString(Charsets.UTF_8)
        val cases: JsonArray = Json.parseToJsonElement(text).jsonObject["cases"]!!.jsonArray
        assertTrue(cases.size > 40)
        return cases.map { element ->
            val case: JsonObject = element.jsonObject
            dynamicTest(case.str("name")!!) {
                val pattern = case.str("pattern")!!
                val flags = case.str("flags") ?: ""
                if (case["rejected"] != null) {
                    assertFailsWith<InvalidPatternException> { RegexDialect.compile(pattern, flags) }
                } else {
                    val match = RegexDialect.compile(pattern, flags).find(case.str("text")!!)
                    if (case["match"].toString() == "true") {
                        assertNotNull(match)
                        case["groups"]?.jsonObject?.forEach { (name, value) ->
                            assertEquals(value.toString().trim('"'), match.group(name), "group $name")
                        }
                    } else {
                        assertNull(match)
                    }
                }
            }
        }
    }
}
