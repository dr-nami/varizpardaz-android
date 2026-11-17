package com.varizpardaz.listener.app.update

import com.varizpardaz.listener.core.update.DownloadResponse
import com.varizpardaz.listener.core.update.DownloadTransport
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * [DownloadTransport] over OkHttp, streaming the body. Separate from the API client on purpose: that one follows no redirects
 * and gives up after a minute, an update download may legitimately be redirected to a CDN and take longer.
 * Integrity does not rest on the connection: the file is accepted only if its SHA-256 equals the one in the signed config.
 */
class OkHttpDownloadTransport(
    private val client: OkHttpClient = defaultClient(),
) : DownloadTransport {
    override fun open(url: String): DownloadResponse {
        val request = try {
            Request.Builder().url(url)
                // The length the server announces is how a cut-off download is noticed, so no transparent compression.
                .header("Accept-Encoding", "identity")
                .build()
        } catch (e: IllegalArgumentException) {
            throw IOException("invalid url", e)
        }
        val response = client.newCall(request).execute()
        val body = response.body
        return DownloadResponse(
            status = response.code,
            contentLength = body.contentLength().takeIf { it >= 0 },
            body = body.byteStream(),
            onClose = response::close,
        )
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS) // between bytes, not for the whole transfer
            .callTimeout(0, TimeUnit.SECONDS)
            .followRedirects(true)
            // Never follow a redirect from https to http or the other way round.
            .followSslRedirects(false)
            .build()
    }
}
