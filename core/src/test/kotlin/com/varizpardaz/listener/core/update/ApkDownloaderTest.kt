package com.varizpardaz.listener.core.update

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ApkDownloaderTest {
    private val dir: File = Files.createTempDirectory("apk-downloader-test").toFile()
    private val target = File(dir, "update.apk")
    private val part = File(dir, "update.apk.part")

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private val payload = ByteArray(300_000) { (it * 31 + 7).toByte() }

    private fun sha256(bytes: ByteArray) = Sha256Hex.fromBytes(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun offer(bytes: ByteArray = payload) = UpdateOffer(3, "https://updates.example.com/listener-3.apk", sha256(bytes))

    private fun serving(
        body: ByteArray = payload,
        status: Int = 200,
        declaredLength: Long? = body.size.toLong(),
        stream: (ByteArray) -> InputStream = { ByteArrayInputStream(it) },
    ) = DownloadTransport { DownloadResponse(status, declaredLength, stream(body)) }

    @Test
    fun `a file with the promised checksum is kept, and no partial file is left`() {
        val progress = ArrayList<Long>()
        val outcome = ApkDownloader(serving()).download(offer(), target, onProgress = { received, total ->
            progress += received
            assertEquals(payload.size.toLong(), total)
        })
        val verified = assertIs<DownloadOutcome.Verified>(outcome)
        assertEquals(payload.size.toLong(), verified.bytes)
        assertContentEquals(payload, target.readBytes())
        assertFalse(part.exists())
        assertEquals(payload.size.toLong(), progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b > a }, "progress only moves forward")
    }

    @Test
    fun `a file with another checksum is thrown away and never reaches the target path`() {
        val tampered = payload.copyOf().also { it[1000] = (it[1000] + 1).toByte() }
        val outcome = ApkDownloader(serving(body = tampered)).download(offer(), target)
        assertIs<DownloadOutcome.ChecksumMismatch>(outcome)
        assertFalse(target.exists())
        assertFalse(part.exists())
    }

    @Test
    fun `a failed download leaves an earlier verified file alone`() {
        target.writeBytes(byteArrayOf(1, 2, 3))
        val outcome = ApkDownloader(serving(body = payload.copyOf(10))).download(offer(), target)
        assertIs<DownloadOutcome.ChecksumMismatch>(outcome)
        assertContentEquals(byteArrayOf(1, 2, 3), target.readBytes())
    }

    @Test
    fun `an http error status is reported and nothing is written`() {
        val outcome = ApkDownloader(serving(status = 404)).download(offer(), target)
        assertEquals(404, assertIs<DownloadOutcome.HttpError>(outcome).status)
        assertFalse(target.exists())
        assertFalse(part.exists())
    }

    @Test
    fun `no connection is a network error`() {
        val outcome = ApkDownloader(DownloadTransport { throw IOException("no route") }).download(offer(), target)
        assertIs<DownloadOutcome.NetworkError>(outcome)
    }

    @Test
    fun `a connection that drops half way is a network error and leaves nothing`() {
        val dropping = serving { bytes ->
            object : InputStream() {
                private val inner = ByteArrayInputStream(bytes)
                private var served = 0
                override fun read(): Int = throw UnsupportedOperationException()

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (served > 100_000) throw IOException("connection reset")
                    return inner.read(b, off, len).also { if (it > 0) served += it }
                }
            }
        }
        assertIs<DownloadOutcome.NetworkError>(ApkDownloader(dropping).download(offer(), target))
        assertFalse(target.exists())
        assertFalse(part.exists())
    }

    @Test
    fun `fewer bytes than the server promised is a network error even if the hash of what arrived would match`() {
        val short = payload.copyOf(1000)
        val outcome = ApkDownloader(serving(body = short, declaredLength = payload.size.toLong())).download(offer(short), target)
        assertIs<DownloadOutcome.NetworkError>(outcome)
        assertFalse(target.exists())
    }

    @Test
    fun `a file bigger than the limit is refused, by its declared length or, failing that, as it streams`() {
        val declared = ApkDownloader(serving(declaredLength = 10_000_000L), maxBytes = 1_000_000).download(offer(), target)
        assertIs<DownloadOutcome.TooLarge>(declared)
        val undeclared = ApkDownloader(serving(declaredLength = null), maxBytes = 100_000).download(offer(), target)
        assertIs<DownloadOutcome.TooLarge>(undeclared)
        assertFalse(target.exists())
        assertFalse(part.exists())
    }

    @Test
    fun `cancelling stops the transfer and removes the partial file`() {
        var reads = 0
        val outcome = ApkDownloader(serving()).download(offer(), target, cancelled = { ++reads > 2 })
        assertIs<DownloadOutcome.Cancelled>(outcome)
        assertFalse(target.exists())
        assertFalse(part.exists())
    }

    @Test
    fun `an unknown length is fine when the checksum matches`() {
        val outcome = ApkDownloader(serving(declaredLength = null)).download(offer(), target)
        assertIs<DownloadOutcome.Verified>(outcome)
    }

    @Test
    fun `the connection is closed on every path`() {
        var closed = 0
        val transport = DownloadTransport { DownloadResponse(200, payload.size.toLong(), ByteArrayInputStream(payload), onClose = { closed++ }) }
        ApkDownloader(transport).download(offer(), target)
        ApkDownloader(transport).download(UpdateOffer(3, "https://x/y.apk", "0".repeat(64)), target)
        assertEquals(2, closed)
    }
}
