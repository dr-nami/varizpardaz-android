package com.varizpardaz.listener.core

import com.varizpardaz.listener.core.wire.BalanceMarkerItem
import com.varizpardaz.listener.core.wire.DepositItem
import com.varizpardaz.listener.core.wire.ErrorCode
import com.varizpardaz.listener.core.wire.ErrorEnvelope
import com.varizpardaz.listener.core.wire.HeartbeatResponse
import com.varizpardaz.listener.core.wire.IngestResponse
import com.varizpardaz.listener.core.wire.PairResponse
import com.varizpardaz.listener.core.wire.Wire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingLinkTest {
    private fun link(base: String, code: String = "AB12CD34", v: String = "1") =
        "paygate://pair?v=$v&base=${URLEncoder.encode(base, "UTF-8")}&code=$code"

    private fun parse(text: String, insecure: Boolean = false) =
        PairingLink.parse(text, scheme = "paygate", host = "pair", allowInsecureHttp = insecure)

    @Test
    fun `a valid link is parsed`() {
        val ok = assertIs<PairingLink.Result.Ok>(parse(link("https://api.example.com/")))
        assertEquals("https://api.example.com", ok.link.baseUrl)
        assertEquals("AB12CD34", ok.link.code)
    }

    @Test
    fun `codes are crockford base32 and forgiving about look-alikes`() {
        assertEquals("10120D34", PairingLink.normaliseCode("lo1-2od3-4"))
        assertEquals("AB12CD34", PairingLink.normaliseCode("ab12cd34"))
        assertNull(PairingLink.normaliseCode("AB12CD3"))
        assertNull(PairingLink.normaliseCode("AB12CD3U")) // U is not in the alphabet
        assertNull(PairingLink.normaliseCode("AB12CD345"))
    }

    @Test
    fun `wrong scheme, host or version are rejected`() {
        for (text in listOf("https://pair?v=1", "other://pair?v=1&base=x&code=AB12CD34", "paygate://unpair?v=1", "not a url")) {
            val r = assertIs<PairingLink.Result.Invalid>(parse(text), text)
            assertEquals(PairingLink.Problem.NOT_A_PAIRING_LINK, r.problem, text)
        }
        val v2 = assertIs<PairingLink.Result.Invalid>(parse(link("https://x.ir", v = "2")))
        assertEquals(PairingLink.Problem.UNSUPPORTED_VERSION, v2.problem)
    }

    @Test
    fun `plain http is refused unless debug allows it`() {
        val refused = assertIs<PairingLink.Result.Invalid>(parse(link("http://192.168.1.5:8787")))
        assertEquals(PairingLink.Problem.BAD_BASE_URL, refused.problem)
        assertIs<PairingLink.Result.Ok>(parse(link("http://192.168.1.5:8787"), insecure = true))
    }

    @Test
    fun `bad base urls and codes are rejected`() {
        for (base in listOf("", "ftp://x.ir", "https://", "https://x.ir/?a=b")) {
            assertEquals(PairingLink.Problem.BAD_BASE_URL, assertIs<PairingLink.Result.Invalid>(parse(link(base)), base).problem, base)
        }
        assertEquals(PairingLink.Problem.BAD_CODE, assertIs<PairingLink.Result.Invalid>(parse(link("https://x.ir", code = "SHORT"))).problem)
    }

    @Test
    fun `the code never appears in toString`() {
        val ok = assertIs<PairingLink.Result.Ok>(parse(link("https://x.ir")))
        assertFalse(ok.link.toString().contains("AB12CD34"))
    }
}

class WireTest {
    private fun obj(bytes: ByteArray) = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject

    @Test
    fun `a deposit item has exactly the contract fields, including explicit nulls`() {
        val item = DepositItem(
            localId = "4c0f", channel = "sms", sender = "TESTBANK", packageName = null, simSlot = 0, subscriptionId = 3,
            scTimestamp = "2026-11-01T10:15:02+03:30", receivedAt = "2026-11-01T10:15:04+03:30",
            body = "واریز", templateId = "t1", configVersion = 7, rescanRequestId = null,
        )
        val json = obj(Wire.encodeIngest(listOf(item)))
        val encoded = (json["items"] as JsonArray).single().jsonObject
        assertEquals(
            listOf(
                "local_id", "kind", "channel", "sender", "package", "sim_slot", "subscription_id", "sc_timestamp",
                "received_at", "body", "template_id", "config_version", "rescan_request_id",
            ),
            encoded.keys.toList(),
        )
        assertEquals("deposit", (encoded["kind"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(JsonNull, encoded["package"])
        assertEquals(JsonNull, encoded["rescan_request_id"])
    }

    @Test
    fun `a balance marker carries numbers and no text`() {
        val item = BalanceMarkerItem(
            localId = "9a1e", channel = "sms", sender = "TESTBANK", simSlot = 0, subscriptionId = 3,
            scTimestamp = "2026-11-01T11:02:40+03:30", receivedAt = "2026-11-01T11:02:41+03:30",
            bank = "testbank", amountRial = "1250000", balanceRial = "98000000", templateId = "testbank-wd-v1",
            configVersion = 7, rescanRequestId = "rs_1",
        )
        val encoded = (obj(Wire.encodeIngest(listOf(item)))["items"] as JsonArray).single().jsonObject
        assertEquals(
            listOf(
                "local_id", "kind", "channel", "sender", "package", "sim_slot", "subscription_id", "sc_timestamp", "received_at",
                "bank", "account", "amount_rial", "balance_rial", "template_id", "config_version", "rescan_request_id",
            ),
            encoded.keys.toList(),
        )
        assertEquals("null", encoded["package"].toString(), "an SMS marker has no package")
        assertEquals("null", encoded["account"].toString(), "and none when the template has no account group")
        assertFalse("body" in encoded)
        // Money is a decimal string, never a JSON number.
        assertTrue((encoded["amount_rial"] as kotlinx.serialization.json.JsonPrimitive).isString)
        assertTrue((encoded["balance_rial"] as kotlinx.serialization.json.JsonPrimitive).isString)
    }

    @Test
    fun `a notification marker names its package and a marker can carry the template's account (CCR-D1, CCR-1)`() {
        val item = BalanceMarkerItem(
            localId = "9a1f", channel = "notification", sender = null, packageName = "ir.example.testbank", simSlot = null, subscriptionId = null,
            scTimestamp = "2026-11-01T11:02:40+03:30", receivedAt = "2026-11-01T11:02:41+03:30",
            bank = "testbank", account = "1234", amountRial = "1250000", balanceRial = "98000000", templateId = "testbank-wd-v1",
            configVersion = 7, rescanRequestId = null,
        )
        val encoded = (obj(Wire.encodeIngest(listOf(item)))["items"] as JsonArray).single().jsonObject
        assertEquals("ir.example.testbank", (encoded["package"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("1234", (encoded["account"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("null", encoded["sender"].toString())
    }

    @Test
    fun `server responses of every shape in the contract decode`() {
        val pair = Wire.json.decodeFromString(
            PairResponse.serializer(),
            """{"device_id":"dev_1","device_secret":"abc","server_public_key":"def","heartbeat_interval_sec":30,"extra":1}""",
        )
        assertEquals("dev_1", pair.deviceId)
        assertFalse(pair.toString().contains("abc"), "the device secret must not be printed")

        val ingest = Wire.json.decodeFromString(
            IngestResponse.serializer(),
            """{"results":[{"local_id":"a","status":"accepted"},{"local_id":"b","status":"rejected","reason":"x"}]}""",
        )
        assertEquals(listOf("accepted", "rejected"), ingest.results.map { it.status })
        assertEquals("x", ingest.results[1].reason)

        val hb = Wire.json.decodeFromString(
            HeartbeatResponse.serializer(),
            """{"server_time":"t","config_version":8,"rescan":[{"request_id":"rs_1","from":"a","to":"b","sim_slot":0}]}""",
        )
        assertEquals(8, hb.configVersion)
        assertEquals("rs_1", hb.rescan.single().requestId)
        assertEquals(0, Wire.json.decodeFromString(HeartbeatResponse.serializer(), """{"server_time":"t","config_version":1}""").rescan.size)
    }

    @Test
    fun `error envelopes decode and unknown codes degrade to UNKNOWN`() {
        val e = Wire.json.decodeFromString(
            ErrorEnvelope.serializer(),
            """{"ok":false,"error":{"code":"CLOCK_SKEW","message":"ساعت","server_time":"2026-11-01T10:15:30+03:30"}}""",
        )
        assertEquals(ErrorCode.CLOCK_SKEW, ErrorCode.of(e.error.code))
        assertEquals("2026-11-01T10:15:30+03:30", e.error.serverTime)
        assertEquals(ErrorCode.UNKNOWN, ErrorCode.of("SOMETHING_NEW"))
        val withFields = Wire.json.decodeFromString(
            ErrorEnvelope.serializer(),
            """{"ok":false,"error":{"code":"VALIDATION_ERROR","message":"m","fields":["abc","def"]}}""",
        )
        assertEquals(2, (withFields.error.fields as JsonArray).size)
        assertTrue(ErrorCode.entries.filter { it != ErrorCode.UNKNOWN }.size == 8)
    }

    @Test
    fun `timestamps are whole seconds at plus 03 30`() {
        // 2026-11-01T06:45:02Z -> 10:15:02+03:30
        val millis = java.time.OffsetDateTime.parse("2026-11-01T06:45:02.987Z").toInstant().toEpochMilli()
        assertEquals("2026-11-01T10:15:02+03:30", TimestampFormat.format(millis))
        assertEquals(millis / 1000 * 1000, TimestampFormat.parseToEpochMillis("2026-11-01T10:15:02+03:30"))
        assertNull(TimestampFormat.parseToEpochMillis("yesterday"))
    }
}
