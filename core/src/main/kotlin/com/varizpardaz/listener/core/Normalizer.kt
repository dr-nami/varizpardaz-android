package com.varizpardaz.listener.core

/**
 * Text normalisation, contract section 1. Applied to a *copy* of the text before any matching;
 * the raw text is what gets uploaded. Must stay byte-for-byte equivalent to the server's version
 * (shared vectors: contracts/fixtures/normalise.json).
 */
object Normalizer {
    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        var pendingSpace = false
        for (ch in text) {
            val mapped = when {
                ch in '۰'..'۹' -> '0' + (ch - '۰') // Persian digits
                ch in '٠'..'٩' -> '0' + (ch - '٠') // Arabic-Indic digits
                ch == 'ي' -> 'ی' // ي -> ی
                ch == 'ك' -> 'ک' // ك -> ک
                ch == '٬' || ch == '،' -> ',' // ٬ and ، -> ,
                isBidiControl(ch) -> null // dropped entirely; ZWNJ (U+200C) is kept
                else -> ch
            } ?: continue

            if (JsWhitespace.isWhitespace(mapped)) {
                pendingSpace = true
            } else {
                if (pendingSpace && out.isNotEmpty()) out.append(' ')
                pendingSpace = false
                out.append(mapped)
            }
        }
        return out.toString()
    }

    /**
     * Sender gate normalisation (contract section 6, step 1): spaces and a leading `+` removed.
     * Case is significant.
     */
    fun sender(raw: String): String {
        val noSpaces = buildString(raw.length) {
            for (ch in raw) if (!JsWhitespace.isWhitespace(ch)) append(ch)
        }
        return noSpaces.removePrefix("+")
    }

    private fun isBidiControl(ch: Char): Boolean =
        ch == '‎' || ch == '‏' ||
            ch in '‪'..'‮' ||
            ch in '⁦'..'⁩'
}

/**
 * The exact set ECMAScript calls WhiteSpace or LineTerminator (what `\s` and `String.prototype.trim`
 * use in V8). `java.util.regex`'s `\s` is narrower, so the server and the phone would otherwise
 * disagree about e.g. NBSP.
 */
internal object JsWhitespace {
    fun isWhitespace(ch: Char): Boolean = when (ch) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ',
        ' ', ' ', ' ', ' ', ' ', ' ', '　', '﻿',
        -> true
        else -> ch in ' '..' '
    }
}
