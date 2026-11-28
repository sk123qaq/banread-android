package app.banread.companion.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal object JsonRepair {
    private val fenceRegex = Regex("""^```(?:json)?\s*|\s*```$""", RegexOption.IGNORE_CASE)
    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseObject(raw: String): JsonObject? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val stripped = fenceRegex.replace(trimmed, "").trim()

        for (candidate in buildCandidates(stripped, trimmed)) {
            tryParseObject(candidate)?.let { return it }
            val repaired = repairJsonText(candidate)
            tryParseObject(repaired)?.let { return it }
            val closed = closeTruncatedJson(repaired)
            tryParseObject(closed)?.let { return it }
        }
        return null
    }

    private fun buildCandidates(stripped: String, raw: String): List<String> {
        val candidates = linkedSetOf<String>()
        candidates += stripped
        candidates += raw
        for (base in listOf(stripped, raw)) {
            val start = base.indexOf('{')
            if (start >= 0) {
                val end = base.lastIndexOf('}')
                if (end > start) {
                    candidates += base.substring(start, end + 1)
                }
                candidates += base.substring(start)
            }
        }
        return candidates.toList()
    }

    private fun tryParseObject(text: String): JsonObject? {
        if (text.isBlank()) return null
        return runCatching {
            lenientJson.parseToJsonElement(text) as? JsonObject
        }.getOrNull()
    }

    internal fun repairJsonText(input: String): String {
        val convertedQuotes = convertSingleQuotesOutsideDoubleStrings(input)
        val escapedNewlines = escapeControlCharsInStrings(convertedQuotes)
        return stripTrailingCommas(escapedNewlines)
    }

    private fun convertSingleQuotesOutsideDoubleStrings(input: String): String {
        val out = StringBuilder(input.length)
        var i = 0
        var inDouble = false
        var inSingle = false
        var escaped = false

        while (i < input.length) {
            val ch = input[i]
            if (escaped) {
                out.append(ch)
                escaped = false
                i++
                continue
            }
            if (inDouble) {
                if (ch == '\\') {
                    escaped = true
                    out.append(ch)
                } else if (ch == '"') {
                    inDouble = false
                    out.append(ch)
                } else {
                    out.append(ch)
                }
                i++
                continue
            }
            if (inSingle) {
                if (ch == '\\') {
                    val nxt = input.getOrNull(i + 1)
                    if (nxt == '\'') {
                        out.append('\'')
                        i += 2
                        continue
                    }
                    escaped = true
                    out.append(ch)
                } else if (ch == '\'') {
                    inSingle = false
                    out.append('"')
                } else if (ch == '"') {
                    out.append("\\\"")
                } else {
                    out.append(ch)
                }
                i++
                continue
            }
            when (ch) {
                '"' -> {
                    inDouble = true
                    out.append(ch)
                }
                '\'' -> {
                    inSingle = true
                    out.append('"')
                }
                else -> out.append(ch)
            }
            i++
        }
        return out.toString()
    }

    private fun escapeControlCharsInStrings(input: String): String {
        val out = StringBuilder(input.length)
        var inString = false
        var escaped = false
        for (ch in input) {
            if (escaped) {
                out.append(ch)
                escaped = false
                continue
            }
            if (inString) {
                when (ch) {
                    '\\' -> {
                        escaped = true
                        out.append(ch)
                    }
                    '"' -> {
                        inString = false
                        out.append(ch)
                    }
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    else -> if (ch.code < 0x20) {
                        out.append("\\u%04x".format(ch.code))
                    } else {
                        out.append(ch)
                    }
                }
            } else {
                if (ch == '"') {
                    inString = true
                }
                out.append(ch)
            }
        }
        return out.toString()
    }

    private fun stripTrailingCommas(input: String): String {
        val out = StringBuilder(input.length)
        var inString = false
        var escaped = false
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            if (escaped) {
                out.append(ch)
                escaped = false
                i++
                continue
            }
            if (inString) {
                if (ch == '\\') escaped = true else if (ch == '"') inString = false
                out.append(ch)
                i++
                continue
            }
            if (ch == '"') {
                inString = true
                out.append(ch)
                i++
                continue
            }
            if (ch == ',') {
                var j = i + 1
                while (j < input.length && input[j].isWhitespace()) j++
                if (j < input.length && (input[j] == '}' || input[j] == ']')) {
                    i++
                    continue
                }
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    internal fun closeTruncatedJson(input: String): String {
        val trimmed = input.trimEnd()
        if (trimmed.isEmpty()) return trimmed

        val out = StringBuilder(trimmed.length + 16)
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false

        for (ch in trimmed) {
            out.append(ch)
            if (escaped) {
                escaped = false
                continue
            }
            if (inString) {
                if (ch == '\\') escaped = true else if (ch == '"') inString = false
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> stack.addLast('}')
                '[' -> stack.addLast(']')
                '}', ']' -> if (stack.isNotEmpty() && stack.last() == ch) stack.removeLast()
            }
        }

        if (escaped && out.isNotEmpty()) {
            out.setLength(out.length - 1)
        }
        if (inString) {
            out.append('"')
        }

        while (out.isNotEmpty() && out.last().isWhitespace()) {
            out.setLength(out.length - 1)
        }
        if (out.isNotEmpty() && out.last() == ':') {
            out.append("null")
        } else if (out.isNotEmpty() && out.last() == ',') {
            out.setLength(out.length - 1)
        }

        while (stack.isNotEmpty()) {
            out.append(stack.removeLast())
        }
        return stripTrailingCommas(out.toString())
    }
}
