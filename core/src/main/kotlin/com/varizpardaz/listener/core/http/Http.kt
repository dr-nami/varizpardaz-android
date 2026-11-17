package com.varizpardaz.listener.core.http

import java.io.IOException

/**
 * The small HTTP surface `:core` needs. The app plugs OkHttp in behind it; tests plug in fakes or the JDK
 * client. Request and response bodies are bytes because signatures cover the exact body bytes.
 */
class HttpRequest(
    val method: String,
    /** Absolute URL. */
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
) {
    // Bodies hold SMS text and secrets; headers hold signatures. Print neither.
    override fun toString(): String = "HttpRequest($method $url)"
}

class HttpResponse(
    val status: Int,
    headers: Map<String, String>,
    val body: ByteArray,
) {
    private val lowerCaseHeaders = headers.mapKeys { it.key.lowercase() }

    fun header(name: String): String? = lowerCaseHeaders[name.lowercase()]

    override fun toString(): String = "HttpResponse($status, ${body.size} bytes)"
}

fun interface HttpTransport {
    /**
     * Blocking. Throws [IOException] when no HTTP response was obtained (no route, timeout, TLS failure);
     * any HTTP status, including 4xx and 5xx, is a normal return value. Redirects are not followed.
     */
    @Throws(IOException::class)
    fun execute(request: HttpRequest): HttpResponse
}
