package app.banread.library.internal

import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal data class EpubTocEntry(
    val label: String,
    val zipPath: String,
    val fragmentId: String?,
    val depth: Int,
)

internal object EpubTocParser {

    fun parsePreferredToc(
        zip: ZipFile,
        entryMap: Map<String, ZipEntry>,
        navZipPath: String?,
        ncxZipPath: String?,
    ): List<EpubTocEntry> {
        val navEntries = navZipPath?.let { path ->
            entryMap[path]?.let { entry ->
                runCatching {
                    zip.getInputStream(entry).use { parseNavXhtml(it, path) }
                }.getOrDefault(emptyList())
            }
        }.orEmpty()

        val ncxEntries = ncxZipPath?.let { path ->
            entryMap[path]?.let { entry ->
                runCatching {
                    zip.getInputStream(entry).use { parseNcx(it, path) }
                }.getOrDefault(emptyList())
            }
        }.orEmpty()

        return when {
            navEntries.isEmpty() -> ncxEntries
            ncxEntries.isEmpty() -> navEntries
            // Prefer nav.xhtml (EPUB3) unless it only has a stub/single entry while NCX has richer fragment TOC
            navEntries.size <= 1 && ncxEntries.size > 1 -> ncxEntries
            ncxEntries.any { it.fragmentId != null } && navEntries.none { it.fragmentId != null } && ncxEntries.size > navEntries.size -> ncxEntries
            else -> navEntries
        }
    }

    internal fun parseNcx(input: InputStream, ncxZipPath: String): List<EpubTocEntry> {
        val bytes = input.readBytes()
        val sanitized = TextSanitizer.sanitizeRawPreservingMath(bytes.toString(Charsets.UTF_8))
        val parser = LocalBookInspector.newSafeXmlParser(ByteArrayInputStream(sanitized.toByteArray(Charsets.UTF_8)))
        val ncxDir = ncxZipPath.substringBeforeLast('/', "")
        val out = mutableListOf<EpubTocEntry>()

        class NavPointFrame(val depth: Int) {
            var label: String = ""
            var src: String = ""
            var emitted: Boolean = false
        }

        val stack = mutableListOf<NavPointFrame>()
        var inNavMap = false
        var inNavLabel = false
        var inText = false
        val textBuf = StringBuilder()

        fun emitIfReady(frame: NavPointFrame) {
            if (frame.emitted) return
            val src = frame.src.trim()
            if (src.isEmpty()) return
            val resolved = resolveTocTarget(ncxDir, ncxZipPath, src) ?: return
            val cleanLabel = TextSanitizer.cleanInline(frame.label).ifBlank {
                resolved.second ?: resolved.first.substringAfterLast('/')
            }
            out += EpubTocEntry(
                label = cleanLabel,
                zipPath = resolved.first,
                fragmentId = resolved.second,
                depth = frame.depth,
            )
            frame.emitted = true
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.substringAfter(':').lowercase()
                    when {
                        tag == "navmap" -> inNavMap = true
                        inNavMap && tag == "navpoint" -> {
                            stack.lastOrNull()?.let { emitIfReady(it) }
                            stack += NavPointFrame(depth = stack.size)
                        }
                        stack.isNotEmpty() && tag == "navlabel" -> inNavLabel = true
                        inNavLabel && tag == "text" -> {
                            inText = true
                            textBuf.clear()
                        }
                        stack.isNotEmpty() && tag == "content" -> {
                            val src = parser.getAttributeValue(null, "src").orEmpty()
                            if (src.isNotBlank()) {
                                stack.last().src = src
                            }
                        }
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    if (inText) {
                        textBuf.append(parser.text.orEmpty())
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.substringAfter(':').lowercase()
                    when {
                        tag == "navmap" -> inNavMap = false
                        inText && tag == "text" -> {
                            inText = false
                            stack.lastOrNull()?.let { frame ->
                                if (frame.label.isBlank()) {
                                    frame.label = textBuf.toString()
                                }
                            }
                            textBuf.clear()
                        }
                        tag == "navlabel" -> inNavLabel = false
                        tag == "navpoint" && stack.isNotEmpty() -> {
                            val finished = stack.removeAt(stack.lastIndex)
                            emitIfReady(finished)
                        }
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    internal fun parseNavXhtml(input: InputStream, navZipPath: String): List<EpubTocEntry> {
        val bytes = input.readBytes()
        val sanitized = TextSanitizer.sanitizeRawPreservingMath(bytes.toString(Charsets.UTF_8))
        val parser = LocalBookInspector.newSafeXmlParser(ByteArrayInputStream(sanitized.toByteArray(Charsets.UTF_8)))
        val navDir = navZipPath.substringBeforeLast('/', "")

        val explicitTocEntries = mutableListOf<EpubTocEntry>()
        val fallbackEntries = mutableListOf<EpubTocEntry>()
        var capturedExplicit = false
        var capturedFallback = false

        var inTargetNav = false
        var currentNavIsExplicitToc = false
        var navDepth = 0
        var olDepth = 0
        var inAnchor = false
        var anchorDepth = 0
        var anchorHref = ""
        val anchorTextBuf = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.substringAfter(':').lowercase()
                    if (!inTargetNav && tag == "nav") {
                        val epubType = (parser.getAttributeValue("http://www.idpf.org/2007/ops", "type")
                            ?: parser.getAttributeValue(null, "epub:type")
                            ?: parser.getAttributeValue(null, "type"))
                            .orEmpty()
                            .lowercase()
                        val roleAttr = parser.getAttributeValue(null, "role").orEmpty().lowercase()
                        val idAttr = parser.getAttributeValue(null, "id").orEmpty().lowercase()
                        val typeTokens = epubType.split(Regex("\\s+")).filter { it.isNotEmpty() }
                        val roleTokens = roleAttr.split(Regex("\\s+")).filter { it.isNotEmpty() }

                        val isExplicitToc = "toc" in typeTokens || "doc-toc" in roleTokens || idAttr == "toc"
                        val isExcludedNav = "landmarks" in typeTokens || "page-list" in typeTokens ||
                            "doc-landmarks" in roleTokens || "doc-pagelist" in roleTokens

                        if (isExplicitToc && !capturedExplicit) {
                            inTargetNav = true
                            currentNavIsExplicitToc = true
                            navDepth = 1
                            olDepth = 0
                        } else if (!isExcludedNav && !capturedExplicit && !capturedFallback) {
                            inTargetNav = true
                            currentNavIsExplicitToc = false
                            navDepth = 1
                            olDepth = 0
                        }
                    } else if (inTargetNav) {
                        if (tag == "nav") {
                            navDepth++
                        } else if (tag == "ol" || tag == "ul") {
                            olDepth++
                        } else if (tag == "a" && !inAnchor) {
                            val href = parser.getAttributeValue(null, "href").orEmpty().trim()
                            if (href.isNotEmpty()) {
                                inAnchor = true
                                anchorDepth = 1
                                anchorHref = href
                                anchorTextBuf.clear()
                            }
                        } else if (inAnchor) {
                            anchorDepth++
                        }
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    if (inTargetNav && inAnchor) {
                        anchorTextBuf.append(parser.text.orEmpty())
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.substringAfter(':').lowercase()
                    if (inTargetNav) {
                        if (inAnchor) {
                            if (tag == "a") {
                                anchorDepth--
                                if (anchorDepth <= 0) {
                                    val resolved = resolveTocTarget(navDir, navZipPath, anchorHref)
                                    val label = TextSanitizer.cleanInline(anchorTextBuf.toString())
                                    if (resolved != null && label.isNotEmpty()) {
                                        val targetList = if (currentNavIsExplicitToc) explicitTocEntries else fallbackEntries
                                        targetList += EpubTocEntry(
                                            label = label,
                                            zipPath = resolved.first,
                                            fragmentId = resolved.second,
                                            depth = (olDepth - 1).coerceAtLeast(0),
                                        )
                                    }
                                    inAnchor = false
                                    anchorHref = ""
                                    anchorTextBuf.clear()
                                }
                            }
                        }
                        when (tag) {
                            "ol", "ul" -> if (olDepth > 0) olDepth--
                            "nav" -> {
                                navDepth--
                                if (navDepth <= 0) {
                                    inTargetNav = false
                                    if (currentNavIsExplicitToc && explicitTocEntries.isNotEmpty()) {
                                        capturedExplicit = true
                                    } else if (!currentNavIsExplicitToc && fallbackEntries.isNotEmpty()) {
                                        capturedFallback = true
                                    }
                                }
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }
        return if (explicitTocEntries.isNotEmpty()) explicitTocEntries else fallbackEntries
    }

    private fun resolveTocTarget(
        baseDir: String,
        tocSelfPath: String,
        rawHref: String,
    ): Pair<String, String?>? {
        val trimmed = rawHref.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()
        if (lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("mailto:") ||
            lower.startsWith("javascript:")
        ) {
            return null
        }

        val rawPathAndQuery = trimmed.substringBefore('#')
        val rawPath = rawPathAndQuery.substringBefore('?').trim()
        val rawFrag = trimmed.substringAfter('#', "").trim()

        val decodedPath = decodeUriComponent(rawPath) ?: return null
        if ('\\' in decodedPath || '\u0000' in decodedPath) return null

        val zipPath = if (decodedPath.isEmpty()) {
            tocSelfPath
        } else {
            val clean = decodedPath.trimStart('/')
            val combined = if (baseDir.isEmpty()) clean else "$baseDir/$clean"
            val parts = mutableListOf<String>()
            for (seg in combined.split('/')) {
                when (seg) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex) else return null
                    else -> parts += seg
                }
            }
            parts.joinToString("/")
        }
        if (zipPath.isEmpty()) return null

        val fragmentId = rawFrag.takeIf { it.isNotEmpty() }?.let { decodeUriComponent(it) ?: it }
        return zipPath to fragmentId
    }

    private fun decodeUriComponent(raw: String): String? {
        if ('%' !in raw) return raw
        return runCatching {
            URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8.name())
        }.getOrNull()
    }
}
