package com.varizpardaz.listener.core.update

import com.varizpardaz.listener.core.AppUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdatePolicyTest {
    private val goodSha = "a".repeat(64)

    private fun update(latest: Int, min: Int, url: String = "https://updates.example.com/listener-$latest.apk", sha: String = goodSha) =
        AppUpdate(latestVersionCode = latest, minVersionCode = min, url = url, sha256 = sha)

    private fun evaluate(update: AppUpdate?, current: Int, insecure: Boolean = false) = UpdatePolicy.evaluate(update, current, insecure)

    @Test
    fun `no app_update means nothing to do`() {
        assertIs<UpdateState.None>(evaluate(null, 1))
    }

    @Test
    fun `the same or an older latest version is not offered`() {
        assertIs<UpdateState.None>(evaluate(update(latest = 3, min = 1), current = 3))
        assertIs<UpdateState.None>(evaluate(update(latest = 3, min = 1), current = 7))
    }

    @Test
    fun `a newer latest version is offered and does not stop uploads`() {
        val state = evaluate(update(latest = 3, min = 1), current = 2)
        val available = assertIs<UpdateState.Available>(state)
        assertEquals(3, available.offer.versionCode)
        assertEquals("https://updates.example.com/listener-3.apk", available.offer.url)
        assertFalse(UpdatePolicy.blocksUploads(state))
    }

    @Test
    fun `below the minimum version uploads stop and the update is required`() {
        val state = evaluate(update(latest = 3, min = 2), current = 1)
        val required = assertIs<UpdateState.Required>(state)
        assertEquals(3, required.offer?.versionCode)
        assertTrue(UpdatePolicy.blocksUploads(state))
    }

    @Test
    fun `exactly at the minimum is fine`() {
        val state = evaluate(update(latest = 3, min = 2), current = 2)
        assertIs<UpdateState.Available>(state)
        assertFalse(UpdatePolicy.blocksUploads(state))
    }

    @Test
    fun `a minimum above the newest version is a server mistake and is ignored`() {
        val state = evaluate(update(latest = 3, min = 5), current = 2)
        assertIs<UpdateState.Available>(state)
        assertFalse(UpdatePolicy.blocksUploads(state))
        assertIs<UpdateState.None>(evaluate(update(latest = 2, min = 5), current = 2))
    }

    @Test
    fun `a required update whose link or checksum is unusable still blocks, but offers nothing to download`() {
        listOf(
            update(3, 2, url = "http://updates.example.com/l.apk"),
            update(3, 2, url = "not a url"),
            update(3, 2, sha = "xyz"),
            update(3, 2, sha = "a".repeat(63)),
        ).forEach {
            val required = assertIs<UpdateState.Required>(evaluate(it, current = 1), it.url)
            assertNull(required.offer)
        }
    }

    @Test
    fun `an optional update with an unusable link is not offered`() {
        assertIs<UpdateState.None>(evaluate(update(3, 1, url = "ftp://x/y.apk"), current = 2))
        assertIs<UpdateState.None>(evaluate(update(3, 1, sha = ""), current = 2))
    }

    @Test
    fun `links must be https with a host and no credentials, http only when the mock is allowed`() {
        assertTrue(UpdatePolicy.isAcceptableUrl("https://cdn.example.com/a.apk", false))
        assertTrue(UpdatePolicy.isAcceptableUrl("HTTPS://cdn.example.com:8443/a.apk?x=1", false))
        assertFalse(UpdatePolicy.isAcceptableUrl("http://cdn.example.com/a.apk", false))
        assertTrue(UpdatePolicy.isAcceptableUrl("http://10.0.2.2:8787/mock/files/a.apk", true))
        assertFalse(UpdatePolicy.isAcceptableUrl("https://user:pw@cdn.example.com/a.apk", false))
        assertFalse(UpdatePolicy.isAcceptableUrl("https:///a.apk", false))
        assertFalse(UpdatePolicy.isAcceptableUrl("file:///sdcard/a.apk", true))
        assertFalse(UpdatePolicy.isAcceptableUrl("javascript:alert(1)", true))
    }

    @Test
    fun `checksums are normalised to lower case hex and round-trip through bytes`() {
        assertEquals("ab".repeat(32), Sha256Hex.normalise("  " + "AB".repeat(32) + "\n"))
        assertNull(Sha256Hex.normalise("ab".repeat(31)))
        assertNull(Sha256Hex.normalise("zz".repeat(32)))
        val bytes = Sha256Hex.toBytes("00ff10" + "00".repeat(29))
        assertEquals(32, bytes.size)
        assertEquals("00ff10" + "00".repeat(29), Sha256Hex.fromBytes(bytes))
    }

    @Test
    fun `a downloaded file must be this app at the promised version`() {
        val offer = UpdateOffer(3, "https://x/y.apk", goodSha)
        assertNull(ArchiveCheck.check("ir.example.app", offer, "ir.example.app", 3))
        assertEquals(ArchiveCheck.Problem.WRONG_PACKAGE, ArchiveCheck.check("ir.example.app", offer, "com.other", 3))
        assertEquals(ArchiveCheck.Problem.WRONG_PACKAGE, ArchiveCheck.check("ir.example.app", offer, null, 3))
        assertEquals(ArchiveCheck.Problem.WRONG_VERSION, ArchiveCheck.check("ir.example.app", offer, "ir.example.app", 2))
        assertEquals(ArchiveCheck.Problem.WRONG_VERSION, ArchiveCheck.check("ir.example.app", offer, "ir.example.app", null))
    }
}
