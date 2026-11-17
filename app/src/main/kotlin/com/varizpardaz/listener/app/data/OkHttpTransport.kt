package com.varizpardaz.listener.app.data

import com.varizpardaz.listener.core.http.HttpRequest
import com.varizpardaz.listener.core.http.HttpResponse
import com.varizpardaz.listener.core.http.HttpTransport
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** [HttpTransport] over OkHttp. No logging interceptor on purpose: bodies hold SMS text and secrets. */
class OkHttpTransport(
    private val client: OkHttpClient = defaultClient(),
) : HttpTransport {
    override fun execute(request: HttpRequest): HttpResponse {
        val builder = try {
            okhttp3.Request.Builder().url(request.url)
        } catch (e: IllegalArgumentException) {
            throw IOException("invalid url", e)
        }
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val contentType = request.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.toMediaTypeOrNull()
        val body = request.body?.toRequestBody(contentType)
            ?: if (request.method.equals("GET", true) || request.method.equals("HEAD", true)) null else ByteArray(0).toRequestBody(contentType)
        builder.method(request.method, body)

        client.newCall(builder.build()).execute().use { response ->
            val headers = LinkedHashMap<String, String>()
            for (i in 0 until response.headers.size) headers.putIfAbsent(response.headers.name(i), response.headers.value(i))
            // A server never needs to send more than a few KB to a phone; cap it.
            return HttpResponse(response.code, headers, response.peekBody(MAX_RESPONSE_BYTES).bytes())
        }
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 1L * 1024 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            // A redirect on an API call is never legitimate and would replay a signed body elsewhere.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
