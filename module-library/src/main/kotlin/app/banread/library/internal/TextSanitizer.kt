package app.banread.library.internal

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

internal object TextSanitizer {
    private val CONTROL_RE = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
    private val ZERO_WIDTH_RE = Regex("[\\uFEFF\\u200B-\\u200D]")
    private val PUA_RE = Regex("[\\uE000-\\uF8FF]")
    private val MATH_SPAN_RE = Regex(
        """(?<!\\)\$\$(?:[^\n$]|\\\$|\$(?!\$)|\n(?!\n)){1,8000}?\$\$""" +
            """|(?<!\\)(?<!\$)\$(?!\$)(?!\d+(?:[.,]\d+)*\s+[A-Za-z\u4e00-\u9fff])(?:[^\n$]|\\\$){1,1000}?(?<!\\)\$(?![$\d])""",
    )
    private val BOLD_RE = Regex("""\*\*([^*]+)\*\*""")
    private val ITALIC_RE = Regex("""(?<!\*)\*([^*\n]+)\*(?!\*)""")
    private val INLINE_CODE_RE = Regex("""`([^`]+)`""")
    private val INLINE_IMAGE_RE = Regex("""!\[[^\]]*\]\([^)]*\)""")
    private val INLINE_LINK_RE = Regex("""\[([^\]]+)\]\([^)]*\)""")
    private val HTML_COMMENT_RE = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val HTML_TAG_NAMES =
        "(?:sup|sub|span|div|a|br|hr|i|b|u|em|strong|small|big|font|p|td|tr|th|tbody|thead|tfoot|" +
            "colgroup|col|caption|table|img|figure|figcaption|blockquote|center|section|article|header|footer|" +
            "ul|ol|li|dl|dt|dd)"
    private val HTML_ATTR_ONE =
        """(?:[A-Za-z_:][A-Za-z0-9_.:-]{0,40}[ \t]*=[ \t]*(?:"[^"\r\n]{0,400}"|'[^'\r\n]{0,400}'|[A-Za-z0-9_.:/%#-]{1,120}))"""
    private val HTML_ATTRS_SPEC = """(?:[ \t]+$HTML_ATTR_ONE|(?<=["'])$HTML_ATTR_ONE){0,16}"""
    private val HTML_TAG_STRICT_RE = Regex(
        """(?=[^\r\n]{1,1024}>)<(?:/$HTML_TAG_NAMES[ \t]*|$HTML_TAG_NAMES$HTML_ATTRS_SPEC[ \t]*/?)>""",
        RegexOption.IGNORE_CASE,
    )
    private val PANDOC_CALIBRE_RE = Regex(
        """\s*\{(?:[^{}\n]*\.calibre[^{}\n]*|#[A-Za-z_][A-Za-z0-9_.:-]*(?:\s+[^{}\n]*)?|\.[A-Za-z_][A-Za-z0-9_-]*(?:\s+[^{}\n]*)?)\}""",
    )
    private val MULTI_SPACE_RE = Regex("""[ \t\u00A0]+""")
    private val NUMERIC_ENTITY_RE = Regex("""&#(x?[0-9A-Fa-f]+);""")

    private const val FRONT_MATTER_LIMIT = 60

    data class MathState(val prefix: String, val spans: List<String>)

    fun protectMathSpans(text: String): Pair<String, MathState> {
        if ('$' !in text) return text to MathState("", emptyList())
        val spans = mutableListOf<String>()
        var prefix = "BCMATHSPAN"
        while (prefix in text) {
            prefix += "X"
        }
        val replaced = MATH_SPAN_RE.replace(text) { match ->
            val idx = spans.size
            spans += match.value
            "$prefix${idx}END"
        }
        return replaced to MathState(prefix, spans)
    }

    fun restoreMathSpans(text: String, state: MathState): String {
        if (state.spans.isEmpty() || state.prefix.isEmpty()) return text
        val regex = Regex("""${Regex.escape(state.prefix)}(\d+)END""")
        return regex.replace(text) { match ->
            val idx = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            state.spans.getOrElse(idx) { match.value }
        }
    }

    fun sanitizeRawPreservingMath(raw: String): String {
        val normalizedNewlines = raw.replace("\r\n", "\n").replace('\r', '\n')
        val (protected, mathState) = protectMathSpans(normalizedNewlines)
        var cleaned = PUA_RE.replace(protected, "")
        cleaned = CONTROL_RE.replace(cleaned, "")
        cleaned = ZERO_WIDTH_RE.replace(cleaned, "")
        return restoreMathSpans(cleaned, mathState)
    }

    fun cleanInline(raw: String): String {
        val sanitized = sanitizeRawPreservingMath(raw)
        val (protected, mathState) = protectMathSpans(sanitized)
        var text = BOLD_RE.replace(protected, "$1")
        text = INLINE_IMAGE_RE.replace(text, "")
        text = INLINE_LINK_RE.replace(text, "$1")
        text = ITALIC_RE.replace(text, "$1")
        text = INLINE_CODE_RE.replace(text, "$1")
        if ('<' in text) {
            text = HTML_COMMENT_RE.replace(text, "")
            text = HTML_TAG_STRICT_RE.replace(text, "")
        }
        text = PANDOC_CALIBRE_RE.replace(text, "")
        if ('&' in text) {
            text = unescapeHtmlEntities(text)
        }
        text = restoreMathSpans(text, mathState)
        return MULTI_SPACE_RE.replace(text, " ").trim()
    }

    fun unescapeHtmlEntities(input: String): String {
        var out = input
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
            .replace("&amp;", "&")
        if ("&#" in out) {
            out = NUMERIC_ENTITY_RE.replace(out) { m ->
                val raw = m.groupValues[1]
                val code = if (raw.startsWith("x", ignoreCase = true)) {
                    raw.substring(1).toIntOrNull(16)
                } else {
                    raw.toIntOrNull(10)
                }
                if (code != null && code in 0x20..0x10FFFF && !Character.isISOControl(code)) {
                    String(Character.toChars(code))
                } else {
                    ""
                }
            }
        }
        return out
    }

    fun nonSpaceCount(text: String?): Int {
        if (text.isNullOrEmpty()) return 0
        var count = 0
        for (ch in text) {
            if (!ch.isWhitespace()) count++
        }
        return count
    }

    fun stripYamlFrontMatter(raw: String): Pair<Map<String, String>, String> {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        val lines = text.split('\n')
        if (lines.isEmpty() || lines[0].trim() != "---") {
            return emptyMap<String, String>() to text
        }
        val meta = linkedMapOf<String, String>()
        val limit = minOf(lines.size, FRONT_MATTER_LIMIT)
        for (i in 1 until limit) {
            val line = lines[i]
            if (line.trim() == "---") {
                for (j in 1 until i) {
                    val item = lines[j]
                    val colon = item.indexOf(':')
                    if (colon <= 0) continue
                    val key = item.substring(0, colon).trim()
                    var value = item.substring(colon + 1).trim()
                    if (value.length >= 2 && value.first() == value.last() && (value.first() == '"' || value.first() == '\'')) {
                        value = value.substring(1, value.length - 1)
                    }
                    if (key.isNotEmpty()) meta[key] = value
                }
                val body = if (i + 1 < lines.size) lines.subList(i + 1, lines.size).joinToString("\n") else ""
                return meta to body
            }
        }
        return emptyMap<String, String>() to text
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(file: File): String = file.inputStream().use { sha256Hex(it) }

    fun sha256Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            md.update(buf, 0, read)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
