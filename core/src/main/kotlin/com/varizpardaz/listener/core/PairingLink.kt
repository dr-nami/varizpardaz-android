package com.varizpardaz.listener.core

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/** What the pairing QR carries, contract section 3: `<scheme>://pair?v=1&base=<url-encoded>&code=<8 chars>`. */
class PairingLink(val baseUrl: String, val code: String) {
    // The code is a one-time secret until it is used.
    override fun toString(): String = "PairingLink(baseUrl=$baseUrl)"

    sealed interface Result {
        class Ok(val link: PairingLink) : Result
        class Invalid(val problem: Problem) : Result
    }

    enum class Problem { NOT_A_PAIRING_LINK, UNSUPPORTED_VERSION, BAD_BASE_URL, BAD_CODE }

    companion object {
        private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private const val CODE_LENGTH = 8

        /**
         * [scheme] and [host] come from the central brand configuration. HTTPS is required unless
         * [allowInsecureHttp] (debug builds talking to the local mock server).
         */
        fun parse(text: String, scheme: String, host: String, allowInsecureHttp: Boolean = false): Result {
            val uri = try {
                URI(text.trim())
            } catch (_: URISyntaxException) {
                return Result.Invalid(Problem.NOT_A_PAIRING_LINK)
            }
            if (!scheme.equals(uri.scheme, ignoreCase = true) || !host.equals(uri.host ?: uri.authority, ignoreCase = true)) {
                return Result.Invalid(Problem.NOT_A_PAIRING_LINK)
            }
            val params = queryParameters(uri.rawQuery.orEmpty())
            if (params["v"] != "1") return Result.Invalid(Problem.UNSUPPORTED_VERSION)

            val base = params["base"].orEmpty().trimEnd('/')
            val baseUri = try {
                URI(base)
            } catch (_: URISyntaxException) {
                return Result.Invalid(Problem.BAD_BASE_URL)
            }
            val httpsOk = baseUri.scheme.equals("https", ignoreCase = true)
            val httpOk = allowInsecureHttp && baseUri.scheme.equals("http", ignoreCase = true)
            if ((!httpsOk && !httpOk) || baseUri.host.isNullOrEmpty() || baseUri.rawQuery != null || baseUri.rawFragment != null) {
                return Result.Invalid(Problem.BAD_BASE_URL)
            }

            val code = normaliseCode(params["code"].orEmpty()) ?: return Result.Invalid(Problem.BAD_CODE)
            return Result.Ok(PairingLink(base, code))
        }

        /** Crockford base32: case-insensitive, `I`/`L` read as 1, `O` as 0, hyphens ignored. Null if invalid. */
        fun normaliseCode(raw: String): String? {
            val code = raw.uppercase().replace("-", "").map {
                when (it) {
                    'I', 'L' -> '1'
                    'O' -> '0'
                    else -> it
                }
            }.joinToString("")
            return code.takeIf { it.length == CODE_LENGTH && it.all { c -> c in CROCKFORD } }
        }

        private fun queryParameters(rawQuery: String): Map<String, String> =
            rawQuery.split('&').filter { it.isNotEmpty() }.associate { pair ->
                val idx = pair.indexOf('=')
                val name = if (idx < 0) pair else pair.substring(0, idx)
                val value = if (idx < 0) "" else pair.substring(idx + 1)
                URLDecoder.decode(name, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
            }
    }
}
