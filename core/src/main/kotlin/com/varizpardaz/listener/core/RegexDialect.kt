package com.varizpardaz.listener.core

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

class InvalidPatternException(message: String) : Exception(message)

/**
 * Contract section 2. Patterns arrive in the signed config and must behave identically in
 * ECMAScript (V8, on the server) and on the phone. `java.util.regex` (JVM) and ICU (Android) differ
 * from V8 in details that matter for Persian bank texts, so patterns are *translated* to an
 * unambiguous Java form rather than passed through:
 *
 *  - `\d \s \w \b .` and `$` get the exact ECMAScript meaning (ASCII word characters, the ECMAScript
 *    whitespace set, `$` only at the very end of input);
 *  - named groups lose underscores in the Java name (`origin_last4` is not a legal Java group name);
 *    callers keep using the contract name;
 *  - constructs outside the dialect (lookbehind, atomic/possessive, inline flags, named
 *    backreferences, and Java-only escapes) are rejected instead of being silently reinterpreted.
 */
object RegexDialect {
    private const val WS = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    private const val WORD = "a-zA-Z0-9_"
    private const val BOUNDARY = "(?:(?<=[$WORD])(?![$WORD])|(?<![$WORD])(?=[$WORD]))"
    private const val NOT_BOUNDARY = "(?:(?<=[$WORD])(?=[$WORD])|(?<![$WORD])(?![$WORD]))"
    // Every brace is escaped: ICU (Android) rejects a bare `}` that the JVM accepts.
    private val BOUNDED_QUANTIFIER = Regex("""\{\d+(,\d*)?\}""")
    private val GROUP_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** Throws [InvalidPatternException] for anything outside the dialect or not compilable. */
    fun compile(pattern: String, flags: String): CompiledPattern {
        val javaFlags = when (flags) {
            "" -> 0
            "i" -> Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            else -> throw InvalidPatternException("unsupported flags")
        }
        val translated = translate(pattern)
        val regex = try {
            Pattern.compile(translated.javaPattern, javaFlags)
        } catch (e: PatternSyntaxException) {
            throw InvalidPatternException("does not compile: ${e.description}")
        }
        return CompiledPattern(regex, translated.groups)
    }

    internal class Translated(val javaPattern: String, val groups: Map<String, String>)

    internal fun translate(src: String): Translated {
        val out = StringBuilder(src.length + 16)
        val groups = LinkedHashMap<String, String>()
        var inClass = false
        var prevQuantifier = false
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '\\') {
                if (i + 1 >= src.length) throw InvalidPatternException("trailing backslash")
                val n = src[i + 1]
                i += 2
                prevQuantifier = false
                when (n) {
                    'd' -> out.append(if (inClass) "0-9" else "[0-9]")
                    'D' -> out.append("[^0-9]")
                    's' -> out.append(if (inClass) WS else "[$WS]")
                    'S' -> out.append("[^$WS]")
                    'w' -> out.append(if (inClass) WORD else "[$WORD]")
                    'W' -> out.append("[^$WORD]")
                    'b' -> out.append(if (inClass) "\\u0008" else BOUNDARY)
                    'B' -> if (inClass) throw InvalidPatternException("\\B inside a class") else out.append(NOT_BOUNDARY)
                    'v' -> out.append("\\u000B")
                    'n', 'r', 't', 'f', 'x', 'u', 'c' -> out.append('\\').append(n)
                    in '1'..'9' -> out.append('\\').append(n)
                    else -> {
                        if (n.isLetterOrDigit()) throw InvalidPatternException("unsupported escape \\$n")
                        out.append('\\').append(n)
                    }
                }
                continue
            }
            if (inClass) {
                when (c) {
                    ']' -> {
                        inClass = false
                        out.append(']')
                    }
                    '[', '&' -> out.append('\\').append(c) // Java treats these specially inside a class
                    else -> out.append(c)
                }
                i++
                continue
            }
            when (c) {
                '[' -> {
                    val next = src.getOrNull(i + 1)
                    val afterCaret = if (next == '^') src.getOrNull(i + 2) else next
                    if (afterCaret == ']' || afterCaret == null) {
                        throw InvalidPatternException("empty or open character class")
                    }
                    inClass = true
                    out.append('[')
                    if (next == '^') {
                        out.append('^')
                        i++
                    }
                    prevQuantifier = false
                    i++
                }
                '(' -> {
                    prevQuantifier = false
                    if (src.startsWith("(?<", i)) {
                        val after = src.getOrNull(i + 3)
                        if (after == '=' || after == '!') throw InvalidPatternException("lookbehind is not allowed")
                        val close = src.indexOf('>', i + 3)
                        if (close < 0) throw InvalidPatternException("unterminated group name")
                        val name = src.substring(i + 3, close)
                        if (!GROUP_NAME.matches(name)) throw InvalidPatternException("bad group name")
                        val javaName = name.replace("_", "")
                        if (javaName.isEmpty() || javaName[0].isDigit()) throw InvalidPatternException("bad group name")
                        if (name in groups || javaName in groups.values) {
                            throw InvalidPatternException("duplicate group name")
                        }
                        groups[name] = javaName
                        out.append("(?<").append(javaName).append('>')
                        i = close + 1
                    } else if (src.startsWith("(?", i)) {
                        val kind = src.getOrNull(i + 2)
                        when (kind) {
                            ':', '=', '!' -> {
                                out.append("(?").append(kind)
                                i += 3
                            }
                            '>' -> throw InvalidPatternException("atomic groups are not allowed")
                            else -> throw InvalidPatternException("inline flags are not allowed")
                        }
                    } else {
                        out.append('(')
                        i++
                    }
                }
                '.' -> {
                    out.append("[^\\n\\r\\u2028\\u2029]")
                    prevQuantifier = false
                    i++
                }
                '$' -> {
                    out.append("\\z")
                    prevQuantifier = false
                    i++
                }
                '*' -> {
                    out.append('*')
                    prevQuantifier = true
                    i++
                }
                '+' -> {
                    if (prevQuantifier) throw InvalidPatternException("possessive quantifiers are not allowed")
                    out.append('+')
                    prevQuantifier = true
                    i++
                }
                '?' -> {
                    out.append('?')
                    prevQuantifier = !prevQuantifier // `?` right after a quantifier is the lazy marker
                    i++
                }
                '{' -> {
                    val m = BOUNDED_QUANTIFIER.matchAt(src, i)
                    if (m != null) {
                        out.append(m.value)
                        prevQuantifier = true
                        i += m.value.length
                    } else {
                        out.append("\\{") // literal, as in ECMAScript
                        prevQuantifier = false
                        i++
                    }
                }
                '}' -> {
                    out.append("\\}")
                    prevQuantifier = false
                    i++
                }
                else -> {
                    out.append(c)
                    prevQuantifier = false
                    i++
                }
            }
        }
        if (inClass) throw InvalidPatternException("unterminated character class")
        return Translated(out.toString(), groups)
    }
}

class CompiledPattern internal constructor(
    private val pattern: Pattern,
    private val groupNames: Map<String, String>,
) {
    /** Search anywhere in [text] (already normalised), like `RegExp.prototype.exec`. */
    fun find(text: String): PatternMatch? {
        val m = pattern.matcher(text)
        if (!m.find()) return null
        val captured = HashMap<String, String>()
        for ((contractName, javaName) in groupNames) {
            m.group(javaName)?.let { captured[contractName] = it }
        }
        return PatternMatch(captured)
    }

    fun matches(text: String): Boolean = pattern.matcher(text).find()
}

class PatternMatch internal constructor(private val groups: Map<String, String>) {
    /** Captured text of the named group (contract name), or null if absent or not participating. */
    fun group(name: String): String? = groups[name]
}
