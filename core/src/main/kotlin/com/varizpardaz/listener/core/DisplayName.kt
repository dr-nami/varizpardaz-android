package com.varizpardaz.listener.core

/**
 * Makes a name that came from the server (the merchant's name in the config) safe to show on one line.
 * It is display text only. The config is signed, so this is not about trust but about a name that is empty, enormous, or full of
 * control and direction-override characters that could scramble the screen around it.
 */
object DisplayName {
    const val MAX_LENGTH = 80

    private const val ZERO_WIDTH_NON_JOINER = '‌' // part of ordinary Persian spelling, so it stays

    /** The cleaned name, or null if nothing printable is left. */
    fun clean(raw: String?): String? {
        raw ?: return null
        val out = StringBuilder()
        var pendingSpace = false
        for (ch in raw) {
            when {
                ch.isWhitespace() || ch == ' ' -> pendingSpace = out.isNotEmpty()
                ch == ZERO_WIDTH_NON_JOINER -> out.append(ch)
                // Control characters and invisible format characters, including every bidi override and isolate.
                Character.getType(ch) == Character.CONTROL.toInt() || Character.getType(ch) == Character.FORMAT.toInt() -> Unit
                else -> {
                    if (pendingSpace) out.append(' ')
                    pendingSpace = false
                    out.append(ch)
                }
            }
            if (out.length >= MAX_LENGTH) break
        }
        val cleaned = out.toString().trim()
        return cleaned.takeIf { it.isNotEmpty() }?.let { if (it.length > MAX_LENGTH) it.take(MAX_LENGTH).trimEnd() else it }
    }
}
