package app.banread.library.internal

import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import app.banread.model.ChapterRole
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

internal enum class DetectedFormat {
    EPUB,
    MOBI,
    AZW3,
    PDF,
    KFX_DRM,
    UNKNOWN,
}

internal object LocalBookInspector {

    private val ALLOWED_FONT_OBFUSCATION = setOf(
        "http://www.idpf.org/2008/embedding",
        "http://ns.adobe.com/pdf/enc#RC",
    )

    private val KFX_MAGIC = byteArrayOf(0xEA.toByte(), 'D'.code.toByte(), 'R'.code.toByte(), 'M'.code.toByte(), 'I'.code.toByte(), 'O'.code.toByte(), 'N'.code.toByte(), 0xEE.toByte())
    private val PDF_MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)

    fun safeUnpackZip(zipFile: File, destRoot: File) {
        destRoot.mkdirs()
        val destCanonical = destRoot.canonicalFile.toPath()
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val entryName = entry.name
                if (entryName.startsWith("/") || entryName.startsWith("\\") || entryName.contains("..")) {
                    // Canonical check below is authoritative, and reject obvious traversal patterns early
                    val candidate = File(destRoot, entryName).canonicalFile.toPath()
                    if (!candidate.startsWith(destCanonical) || candidate == destCanonical) {
                        throw AppFailureException(
                            AppFailure.UnsupportedDrmOrFormat("Unsafe archive path (Zip Slip): $entryName"),
                        )
                    }
                }
                val targetFile = File(destRoot, entryName)
                val targetCanonical = targetFile.canonicalFile.toPath()
                if (!targetCanonical.startsWith(destCanonical) || (targetCanonical == destCanonical && !entry.isDirectory)) {
                    throw AppFailureException(
                        AppFailure.UnsupportedDrmOrFormat("Unsafe archive path (Zip Slip): $entryName"),
                    )
                }
                if (entry.isDirectory) {
                    targetFile.mkdirs()
                } else {
                    targetFile.parentFile?.mkdirs()
                    targetFile.outputStream().buffered().use { out ->
                        zis.copyTo(out)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    fun sniffFormat(file: File): DetectedFormat {
        val header = ByteArray(1024)
        val read = file.inputStream().use { it.read(header) }
        if (read <= 0) return DetectedFormat.UNKNOWN
        val slice = header.copyOf(read)

        // KFX / DRMION magic
        if (containsBytes(slice, KFX_MAGIC, maxOffset = 64) ||
            (slice.size >= 4 && slice[0] == 'C'.code.toByte() && slice[1] == 'O'.code.toByte() && slice[2] == 'N'.code.toByte() && slice[3] == 'T'.code.toByte())
        ) {
            return DetectedFormat.KFX_DRM
        }

        // PDF
        if (containsBytes(slice, PDF_MAGIC, maxOffset = 1024)) {
            return DetectedFormat.PDF
        }

        // ZIP / EPUB
        if (slice.size >= 4 && slice[0] == 'P'.code.toByte() && slice[1] == 'K'.code.toByte() &&
            ((slice[2] == 0x03.toByte() && slice[3] == 0x04.toByte()) || (slice[2] == 0x05.toByte() && slice[3] == 0x06.toByte()))
        ) {
            return DetectedFormat.EPUB
        }

        // PalmDB MOBI/AZW3: bytes 60..67 are type+creator ("BOOKMOBI" or "TEXtREAd")
        if (slice.size >= 68) {
            val pdbIdent = String(slice, 60, 8, Charsets.US_ASCII)
            if (pdbIdent == "BOOKMOBI" || pdbIdent == "TEXtREAd") {
                return DetectedFormat.MOBI
            }
        }

        return DetectedFormat.UNKNOWN
    }

    // ==================== EPUB ====================

    data class ParsedEpubBook(
        val title: String,
        val author: String,
        val subjects: List<String>,
        val chapters: List<RoleAndSplit.RawChapterCandidate>,
        val blockIdToHtmlIds: Map<Int, Map<String, Set<String>>>, // pre-merge candidate index -> blockId -> htmlIds
        val images: Map<String, ByteArray>,
    )

    fun parseEpub(epubFile: File): ParsedEpubBook {
        ZipFile(epubFile).use { zip ->
            // 1) Zip Slip check across all entries
            val entries = zip.entries().asSequence().toList()
            val entryMap = linkedMapOf<String, ZipEntry>()
            for (e in entries) {
                val normalized = e.name.trimStart('/')
                if (e.name.startsWith("/") || normalized.split('/').any { it == ".." }) {
                    throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("Unsafe EPUB entry path: ${e.name}"))
                }
                entryMap[normalized] = e
            }

            // 2) DRM fail-fast check on META-INF/encryption.xml & rights.xml
            entryMap["META-INF/encryption.xml"]?.let { encEntry ->
                val encXml = zip.getInputStream(encEntry).readBytes().toString(Charsets.UTF_8)
                checkEpubEncryptionXml(encXml)
            }
            if ("META-INF/rights.xml" in entryMap) {
                throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("EPUB contains DRM rights.xml"))
            }

            // 3) Parse META-INF/container.xml
            val containerEntry = entryMap["META-INF/container.xml"]
                ?: throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("Invalid EPUB: missing META-INF/container.xml"))
            val opfPath = parseContainerOpfPath(zip.getInputStream(containerEntry))
                ?: throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("Invalid EPUB: missing OPF rootfile"))

            val opfEntry = entryMap[opfPath]
                ?: throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("Invalid EPUB: missing OPF at $opfPath"))
            val opfDir = opfPath.substringBeforeLast('/', "")
            val opf = parseOpf(zip.getInputStream(opfEntry))

            val images = linkedMapOf<String, ByteArray>()
            for ((path, entry) in entryMap) {
                if (!entry.isDirectory && isImagePath(path)) {
                    val relName = if (opfDir.isNotEmpty() && path.startsWith("$opfDir/")) {
                        path.removePrefix("$opfDir/")
                    } else {
                        path
                    }
                    val cleanRel = relName.trimStart('/').let {
                        if (it.startsWith("images/")) it else "images/$it"
                    }
                    images[cleanRel] = zip.getInputStream(entry).readBytes()
                }
            }

            val rawCandidates = mutableListOf<RoleAndSplit.RawChapterCandidate>()
            val htmlIdMap = linkedMapOf<Int, Map<String, Set<String>>>()
            val spineItems = opf.spineIdRefs.mapNotNull { idRef -> opf.manifestById[idRef] }
            if (spineItems.isEmpty()) {
                throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("Invalid EPUB: empty spine"))
            }

            val totalSpine = spineItems.size
            spineItems.forEachIndexed { idx, item ->
                val resolvedPath = resolveRelativePath(opfDir, item.href)
                val entry = entryMap[resolvedPath] ?: entryMap[item.href] ?: return@forEachIndexed
                val rawBytes = zip.getInputStream(entry).readBytes()
                val xhtml = rawBytes.toString(Charsets.UTF_8)
                val extracted = extractBlocksFromXhtml(xhtml, resolvedPath)
                val title = extracted.title.ifBlank {
                    resolvedPath.substringAfterLast('/').substringBeforeLast('.')
                }
                val bodyTextForRole = extracted.blocks.joinToString("\n\n") { it.cleanText }
                val role = RoleAndSplit.classifyChapterRole(
                    title = title,
                    body = bodyTextForRole,
                    fileName = resolvedPath.substringAfterLast('/'),
                    bookTitle = opf.title,
                    isOnlyChapter = totalSpine == 1,
                )
                val numberedBlocks = RoleAndSplit.renumberBlocks(extracted.blocks)
                // Remap extracted HTML IDs according to renumbered block IDs
                val remappedHtmlIds = linkedMapOf<String, Set<String>>()
                extracted.blocks.forEachIndexed { bIdx, oldBlk ->
                    val newId = numberedBlocks[bIdx].id
                    val ids = extracted.blockIndexToHtmlIds[bIdx]
                    if (!ids.isNullOrEmpty()) remappedHtmlIds[newId] = ids
                }

                val candidateIdx = rawCandidates.size
                htmlIdMap[candidateIdx] = remappedHtmlIds
                val nStr = (idx + 1).toString().padStart(2, '0')
                val fileName = resolvedPath.substringAfterLast('/')
                rawCandidates += RoleAndSplit.RawChapterCandidate(
                    n = nStr,
                    stem = fileName.substringBeforeLast('.'),
                    fileName = fileName,
                    title = title,
                    role = role,
                    chars = numberedBlocks.sumOf { TextSanitizer.nonSpaceCount(it.cleanText) },
                    blocks = numberedBlocks,
                    rawMarkdown = bodyTextForRole,
                    rawContentBytes = rawBytes,
                )
            }

            if (rawCandidates.isEmpty()) {
                throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("EPUB produced no readable chapters"))
            }

            return ParsedEpubBook(
                title = opf.title.ifBlank { epubFile.nameWithoutExtension },
                author = opf.author,
                subjects = opf.subjects,
                chapters = rawCandidates,
                blockIdToHtmlIds = htmlIdMap,
                images = images,
            )
        }
    }

    private fun checkEpubEncryptionXml(xml: String) {
        val parser = newSafeXmlParser(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        var hasEncryptedData = false
        val algorithms = mutableListOf<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val name = parser.name.substringAfter(':')
                if (name == "EncryptedData") hasEncryptedData = true
                if (name == "EncryptionMethod") {
                    parser.getAttributeValue(null, "Algorithm")?.let { algorithms += it.trim() }
                }
            }
            event = parser.next()
        }
        if (hasEncryptedData) {
            val nonFont = algorithms.filter { it !in ALLOWED_FONT_OBFUSCATION }
            if (algorithms.isEmpty() || nonFont.isNotEmpty()) {
                throw AppFailureException(
                    AppFailure.UnsupportedDrmOrFormat("EPUB is protected by DRM (${nonFont.joinToString().ifBlank { "unknown cipher" }})"),
                )
            }
        }
    }

    private fun parseContainerOpfPath(input: InputStream): String? {
        val parser = newSafeXmlParser(input)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.substringAfter(':') == "rootfile") {
                val fullPath = parser.getAttributeValue(null, "full-path")
                if (!fullPath.isNullOrBlank()) return fullPath.trim().trimStart('/')
            }
            event = parser.next()
        }
        return null
    }

    private data class OpfManifestItem(val id: String, val href: String, val mediaType: String)
    private data class ParsedOpf(
        val title: String,
        val author: String,
        val subjects: List<String>,
        val manifestById: Map<String, OpfManifestItem>,
        val spineIdRefs: List<String>,
    )

    private fun parseOpf(input: InputStream): ParsedOpf {
        val parser = newSafeXmlParser(input)
        var title = ""
        val authors = mutableListOf<String>()
        val subjects = mutableListOf<String>()
        val manifest = linkedMapOf<String, OpfManifestItem>()
        val spine = mutableListOf<String>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val localName = parser.name.substringAfter(':')
                when (localName) {
                    "title" -> {
                        val t = readTextSafe(parser)
                        if (title.isBlank() && t.isNotBlank()) title = t.trim()
                    }
                    "creator" -> {
                        val a = readTextSafe(parser).trim()
                        if (a.isNotEmpty()) authors += a
                    }
                    "subject" -> {
                        val s = readTextSafe(parser).trim()
                        if (s.isNotEmpty()) subjects += s
                    }
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id").orEmpty()
                        val href = parser.getAttributeValue(null, "href").orEmpty()
                        val mediaType = parser.getAttributeValue(null, "media-type").orEmpty()
                        if (id.isNotEmpty() && href.isNotEmpty()) {
                            manifest[id] = OpfManifestItem(id, href, mediaType)
                        }
                    }
                    "itemref" -> {
                        val idref = parser.getAttributeValue(null, "idref").orEmpty()
                        if (idref.isNotEmpty()) spine += idref
                    }
                }
            }
            event = parser.next()
        }
        return ParsedOpf(
            title = title,
            author = authors.joinToString(", "),
            subjects = subjects,
            manifestById = manifest,
            spineIdRefs = spine,
        )
    }

    private fun readTextSafe(parser: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> sb.append(parser.text)
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return sb.toString()
    }

    private data class XhtmlExtractionResult(
        val title: String,
        val blocks: List<ParsedBlock>,
        val blockIndexToHtmlIds: Map<Int, Set<String>>,
    )

    private fun extractBlocksFromXhtml(xhtml: String, chapterPath: String): XhtmlExtractionResult {
        val blocks = mutableListOf<ParsedBlock>()
        val htmlIdsByBlockIdx = linkedMapOf<Int, MutableSet<String>>()
        var docTitle = ""

        val cleanedXml = TextSanitizer.sanitizeRawPreservingMath(xhtml)
        try {
            val parser = newSafeXmlParser(ByteArrayInputStream(cleanedXml.toByteArray(Charsets.UTF_8)))
            var inBody = false
            var inHeadTitle = false
            val headTitleBuf = StringBuilder()

            // Block capture state
            var blockTag: String? = null
            var blockTagDepth = 0
            var blockIsFootnote = false
            var blockHeadingLevel: Int? = null
            var currentBlockHtmlIds = mutableSetOf<String>()
            val blockTextBuf = StringBuilder()
            val blockRefs = mutableListOf<InlineFnRef>()
            var listItems = mutableListOf<String>()
            var listItemRefs = mutableListOf<List<InlineFnRef>>()
            var inLi = false
            val liTextBuf = StringBuilder()
            val liRefs = mutableListOf<InlineFnRef>()

            // Link/noteref state inside block
            var inNoteref = false
            var noterefHref = ""
            val noterefLabelBuf = StringBuilder()
            var inSup = false
            var supAnchorHref: String? = null

            val chapterDir = chapterPath.substringBeforeLast('/', "")

            fun flushBlock() {
                val tag = blockTag ?: return
                val idx = blocks.size
                when (tag) {
                    "ul", "ol" -> {
                        if (listItems.isNotEmpty()) {
                            blocks += ParsedBlock(
                                id = "B${(idx + 1).toString().padStart(2, '0')}",
                                blockIndex = idx,
                                kind = "list",
                                headingPath = "",
                                cleanText = listItems.joinToString("\n"),
                                charStart = 0,
                                charEnd = 0,
                                listItems = listItems.toList(),
                                listItemRefs = listItemRefs.toList(),
                                fnRefs = listItemRefs.flatten(),
                            )
                            if (currentBlockHtmlIds.isNotEmpty()) {
                                htmlIdsByBlockIdx[idx] = currentBlockHtmlIds.toMutableSet()
                            }
                        }
                    }
                    else -> {
                        val clean = TextSanitizer.cleanInline(blockTextBuf.toString())
                        if (clean.isNotEmpty() || blockRefs.isNotEmpty()) {
                            val kind = when {
                                blockIsFootnote -> "footnote"
                                tag in setOf("h1", "h2", "h3", "h4", "h5", "h6") -> "heading"
                                tag == "blockquote" -> "quote"
                                tag == "pre" -> "code"
                                else -> "paragraph"
                            }
                            if (kind == "heading" && docTitle.isBlank()) {
                                docTitle = clean
                            }
                            val (fnDisp, fnKey) = if (kind == "footnote") {
                                val firstId = currentBlockHtmlIds.firstOrNull().orEmpty()
                                val m = Regex("""^\[?(\d{1,4}|[^\]\s]{1,12})\]?(?:[.。、:：]|\s)+(.*)$""").matchEntire(clean)
                                if (m != null) {
                                    MarkdownBlockParser.normalizeMarker(m.groupValues[1])
                                } else {
                                    MarkdownBlockParser.normalizeMarker(firstId.ifBlank { (idx + 1).toString() })
                                }
                            } else {
                                "" to ""
                            }
                            val finalClean = if (kind == "footnote") {
                                clean.replaceFirst(Regex("""^\[?(\d{1,4})\]?(?:[.。、:：]|\s)+"""), "").ifBlank { clean }
                            } else {
                                clean
                            }
                            blocks += ParsedBlock(
                                id = "B${(idx + 1).toString().padStart(2, '0')}",
                                blockIndex = idx,
                                kind = kind,
                                headingPath = "",
                                cleanText = finalClean,
                                charStart = 0,
                                charEnd = finalClean.length,
                                level = blockHeadingLevel,
                                fnRefs = blockRefs.toList(),
                                fnMarkerDisplay = fnDisp,
                                fnMarkerKey = fnKey,
                            )
                            if (currentBlockHtmlIds.isNotEmpty()) {
                                htmlIdsByBlockIdx[idx] = currentBlockHtmlIds.toMutableSet()
                            }
                        }
                    }
                }
                blockTag = null
                blockTagDepth = 0
                blockIsFootnote = false
                blockHeadingLevel = null
                currentBlockHtmlIds = mutableSetOf()
                blockTextBuf.clear()
                blockRefs.clear()
                listItems = mutableListOf()
                listItemRefs = mutableListOf()
                inLi = false
                liTextBuf.clear()
                liRefs.clear()
            }

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name.substringAfter(':').lowercase()
                        val elemId = parser.getAttributeValue(null, "id")
                        if (!inBody && tag == "title") {
                            inHeadTitle = true
                        } else if (tag == "body") {
                            inBody = true
                        } else if (inBody) {
                            if (blockTag == null) {
                                if (tag in setOf("h1", "h2", "h3", "h4", "h5", "h6", "p", "blockquote", "pre", "ul", "ol", "aside")) {
                                    blockTag = tag
                                    blockTagDepth = 1
                                    val epubType = parser.getAttributeValue(null, "epub:type")
                                        ?: parser.getAttributeValue("http://www.idpf.org/2007/ops", "type")
                                    val roleAttr = parser.getAttributeValue(null, "role")
                                    val clsAttr = parser.getAttributeValue(null, "class").orEmpty()
                                    blockIsFootnote = tag == "aside" ||
                                        epubType?.contains("footnote") == true ||
                                        roleAttr?.contains("doc-footnote") == true ||
                                        clsAttr.contains("footnote")
                                    blockHeadingLevel = if (tag.startsWith("h") && tag.length == 2) tag[1].digitToIntOrNull() else null
                                    if (!elemId.isNullOrBlank()) currentBlockHtmlIds += elemId
                                } else if (tag == "img") {
                                    val src = parser.getAttributeValue(null, "src").orEmpty()
                                    val alt = parser.getAttributeValue(null, "alt").orEmpty()
                                    if (src.isNotBlank()) {
                                        val idx = blocks.size
                                        val rel = resolveRelativePath(chapterDir, src)
                                        blocks += ParsedBlock(
                                            id = "B${(idx + 1).toString().padStart(2, '0')}",
                                            blockIndex = idx,
                                            kind = "image",
                                            headingPath = "",
                                            cleanText = alt,
                                            charStart = 0,
                                            charEnd = alt.length,
                                            imageRel = rel,
                                            imageAlt = alt,
                                        )
                                        if (!elemId.isNullOrBlank()) htmlIdsByBlockIdx[idx] = mutableSetOf(elemId)
                                    }
                                }
                            } else {
                                if (tag == blockTag) blockTagDepth++
                                if (!elemId.isNullOrBlank()) currentBlockHtmlIds += elemId
                                if ((blockTag == "ul" || blockTag == "ol") && tag == "li") {
                                    inLi = true
                                    liTextBuf.clear()
                                    liRefs.clear()
                                }
                                if (tag == "sup") {
                                    inSup = true
                                    supAnchorHref = null
                                }
                                if (tag == "a") {
                                    val href = parser.getAttributeValue(null, "href").orEmpty()
                                    val epubType = parser.getAttributeValue(null, "epub:type")
                                        ?: parser.getAttributeValue("http://www.idpf.org/2007/ops", "type")
                                    val roleAttr = parser.getAttributeValue(null, "role")
                                    val isNoteLink = epubType?.contains("noteref") == true ||
                                        roleAttr?.contains("doc-noteref") == true ||
                                        (inSup && href.contains("#")) ||
                                        href.contains("#fn") ||
                                        href.contains("#note")
                                    if (isNoteLink && href.isNotBlank() && !href.startsWith("http")) {
                                        inNoteref = true
                                        noterefHref = href
                                        noterefLabelBuf.clear()
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                        val txt = parser.text.orEmpty()
                        if (inHeadTitle) {
                            headTitleBuf.append(txt)
                        } else if (inBody && blockTag != null) {
                            if (inNoteref) {
                                noterefLabelBuf.append(txt)
                            } else if (inLi) {
                                liTextBuf.append(txt)
                            } else {
                                blockTextBuf.append(txt)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val tag = parser.name.substringAfter(':').lowercase()
                        if (!inBody && tag == "title") {
                            inHeadTitle = false
                            if (docTitle.isBlank()) docTitle = headTitleBuf.toString().trim()
                        } else if (tag == "body") {
                            flushBlock()
                            inBody = false
                        } else if (inBody && blockTag != null) {
                            if (tag == "a" && inNoteref) {
                                val rawLabel = noterefLabelBuf.toString().trim()
                                val (display, key) = MarkdownBlockParser.normalizeMarker(rawLabel.ifBlank { "1" })
                                val filePart = noterefHref.substringBefore('#').let {
                                    if (it.isBlank()) "" else resolveRelativePath(chapterDir, it).substringAfterLast('/')
                                }
                                val fragPart = noterefHref.substringAfter('#', "")
                                val targetBuf = if (inLi) liTextBuf else blockTextBuf
                                val targetList = if (inLi) liRefs else blockRefs
                                val offset = TextSanitizer.cleanInline(targetBuf.toString()).length
                                if (key.isNotEmpty()) {
                                    targetList += InlineFnRef(
                                        markerDisplay = display,
                                        markerKey = key,
                                        targetFile = filePart,
                                        targetFragment = fragPart,
                                        charOffsetInBlock = offset,
                                    )
                                }
                                inNoteref = false
                                noterefHref = ""
                                noterefLabelBuf.clear()
                            }
                            if (tag == "sup") {
                                inSup = false
                                supAnchorHref = null
                            }
                            if ((blockTag == "ul" || blockTag == "ol") && tag == "li" && inLi) {
                                val cleanLi = TextSanitizer.cleanInline(liTextBuf.toString())
                                if (cleanLi.isNotEmpty() || liRefs.isNotEmpty()) {
                                    listItems += cleanLi
                                    listItemRefs += liRefs.toList()
                                }
                                inLi = false
                            }
                            if (tag == blockTag) {
                                blockTagDepth--
                                if (blockTagDepth <= 0) {
                                    flushBlock()
                                }
                            }
                        }
                    }
                }
                event = parser.next()
            }
            flushBlock()
        } catch (_: Exception) {
            // Fallback plain-text strip if malformed HTML
            val stripped = TextSanitizer.cleanInline(cleanedXml.replace(Regex("<[^>]+>"), " "))
            if (stripped.isNotBlank() && blocks.isEmpty()) {
                blocks += ParsedBlock(
                    id = "B01",
                    blockIndex = 0,
                    kind = "paragraph",
                    headingPath = "",
                    cleanText = stripped,
                    charStart = 0,
                    charEnd = stripped.length,
                )
            }
        }
        return XhtmlExtractionResult(
            title = docTitle,
            blocks = blocks,
            blockIndexToHtmlIds = htmlIdsByBlockIdx,
        )
    }

    // ==================== MOBI / AZW3 ====================

    data class ParsedMobiResult(
        val formatName: String, // "MOBI" or "AZW3"
        val title: String,
        val author: String,
        val subjects: List<String>,
        val chapters: List<RoleAndSplit.RawChapterCandidate>,
    )

    fun inspectAndParseMobi(file: File): ParsedMobiResult {
        val bytes = file.readBytes()
        if (bytes.size < 86) {
            throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("Corrupt MOBI/AZW3 header"))
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val pdbName = String(bytes, 0, 32, Charsets.UTF_8).trimEnd('\u0000').trim()
        val numRecords = buf.getShort(76).toInt() and 0xFFFF
        if (numRecords < 1 || 78 + numRecords * 8 > bytes.size) {
            throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("Invalid PalmDB record table"))
        }

        val recordOffsets = IntArray(numRecords)
        for (i in 0 until numRecords) {
            recordOffsets[i] = buf.getInt(78 + i * 8)
        }
        val r0Start = recordOffsets[0]
        val r0End = if (numRecords > 1) recordOffsets[1] else bytes.size
        if (r0Start < 0 || r0End > bytes.size || r0End - r0Start < 16) {
            throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("Invalid MOBI Record 0"))
        }

        val r0 = ByteBuffer.wrap(bytes, r0Start, r0End - r0Start).slice().order(ByteOrder.BIG_ENDIAN)
        val compression = r0.getShort(0).toInt() and 0xFFFF
        val textRecordCount = r0.getShort(8).toInt() and 0xFFFF
        val encryptionType = r0.getShort(12).toInt() and 0xFFFF

        if (encryptionType != 0) {
            throw AppFailureException(
                AppFailure.UnsupportedDrmOrFormat("MOBI/AZW3 is DRM-encrypted (encryption_type=$encryptionType)"),
            )
        }

        var title = pdbName.ifBlank { file.nameWithoutExtension }
        var author = ""
        val subjects = mutableListOf<String>()
        var formatName = if (file.extension.equals("azw3", ignoreCase = true)) "AZW3" else "MOBI"
        var charset = Charsets.UTF_8

        val r0Len = r0End - r0Start
        if (r0Len >= 132 && String(bytes, r0Start + 16, 4, Charsets.US_ASCII) == "MOBI") {
            val mobiHeaderLen = r0.getInt(20)
            val textEncoding = r0.getInt(28)
            charset = if (textEncoding == 1252) Charset.forName("windows-1252") else Charsets.UTF_8
            val fileVersion = r0.getInt(36)
            if (fileVersion >= 8) formatName = "AZW3"

            val fullNameOffset = r0.getInt(84)
            val fullNameLen = r0.getInt(88)
            if (fullNameOffset > 0 && fullNameLen > 0 && fullNameOffset + fullNameLen <= r0Len) {
                val parsedName = String(bytes, r0Start + fullNameOffset, fullNameLen, charset).trim()
                if (parsedName.isNotEmpty()) title = parsedName
            }

            val exthFlags = r0.getInt(128)
            if ((exthFlags and 0x40) != 0) {
                val exthStart = 16 + mobiHeaderLen
                if (exthStart + 12 <= r0Len && String(bytes, r0Start + exthStart, 4, Charsets.US_ASCII) == "EXTH") {
                    val exthCount = r0.getInt(exthStart + 8)
                    var pos = r0Start + exthStart + 12
                    repeat(exthCount.coerceIn(0, 128)) {
                        if (pos + 8 > r0End) return@repeat
                        val recType = buf.getInt(pos)
                        val recLen = buf.getInt(pos + 4)
                        if (recLen < 8 || pos + recLen > r0End) return@repeat
                        val value = String(bytes, pos + 8, recLen - 8, charset).trim('\u0000').trim()
                        when (recType) {
                            100 -> if (value.isNotEmpty()) author = value
                            105 -> if (value.isNotEmpty()) subjects += value
                            503 -> if (value.isNotEmpty()) title = value
                        }
                        pos += recLen
                    }
                }
            }
        }

        // Decode text when compression is uncompressed (1) or PalmDOC LZ77 (2)
        val rawText = if (compression == 1 || compression == 2) {
            val outStream = java.io.ByteArrayOutputStream()
            val maxIdx = minOf(textRecordCount, numRecords - 1)
            for (recIdx in 1..maxIdx) {
                val start = recordOffsets[recIdx]
                val end = if (recIdx + 1 < numRecords) recordOffsets[recIdx + 1] else bytes.size
                if (start < 0 || end > bytes.size || end <= start) continue
                val recBytes = bytes.copyOfRange(start, end)
                val decoded = if (compression == 1) recBytes else decompressPalmDoc(recBytes)
                outStream.write(decoded)
            }
            outStream.toByteArray().toString(charset)
        } else {
            // HuffCDIC (17480): original is registered for foliate-js mobi.js in Slice 3
            ""
        }

        val cleanBody = if (rawText.isNotBlank()) {
            TextSanitizer.cleanInline(rawText.replace(Regex("<[^>]+>"), "\n\n"))
        } else {
            ""
        }

        val blocks = if (cleanBody.isNotBlank()) {
            MarkdownBlockParser.parse(cleanBody, fallbackTitle = title).blocks
        } else {
            listOf(
                ParsedBlock(
                    id = "B01",
                    blockIndex = 0,
                    kind = "paragraph",
                    headingPath = title,
                    cleanText = title,
                    charStart = 0,
                    charEnd = title.length,
                ),
            )
        }

        val candidate = RoleAndSplit.RawChapterCandidate(
            n = "01",
            stem = "01_$title",
            fileName = "01_book.md",
            title = title,
            role = ChapterRole.BODY,
            chars = blocks.sumOf { TextSanitizer.nonSpaceCount(it.cleanText) },
            blocks = blocks,
            rawMarkdown = cleanBody,
            rawContentBytes = cleanBody.toByteArray(Charsets.UTF_8),
        )

        return ParsedMobiResult(
            formatName = formatName,
            title = title,
            author = author,
            subjects = subjects,
            chapters = listOf(candidate),
        )
    }

    private fun decompressPalmDoc(src: ByteArray): ByteArray {
        val out = ArrayList<Byte>(src.size * 2)
        var i = 0
        while (i < src.size) {
            val b = src[i].toInt() and 0xFF
            i++
            when {
                b == 0 -> out.add(0)
                b in 1..8 -> {
                    val count = minOf(b, src.size - i)
                    for (k in 0 until count) {
                        out.add(src[i + k])
                    }
                    i += count
                }
                b in 0x09..0x7F -> out.add(b.toByte())
                b in 0x80..0xBF -> {
                    if (i >= src.size) break
                    val b2 = src[i].toInt() and 0xFF
                    i++
                    val pair = (b shl 8) or b2
                    val dist = (pair ushr 3) and 0x7FF
                    val len = (pair and 0x07) + 3
                    if (dist > 0 && dist <= out.size) {
                        var pos = out.size - dist
                        repeat(len) {
                            out.add(out[pos])
                            pos++
                        }
                    }
                }
                else -> {
                    out.add(' '.code.toByte())
                    out.add((b xor 0x80).toByte())
                }
            }
        }
        return out.toByteArray()
    }

    // ==================== PDF ====================

    fun probeIsScannedPdf(pdfFile: File): Boolean {
        val sampleLimit = minOf(pdfFile.length(), 2L * 1024 * 1024).toInt()
        val buf = ByteArray(sampleLimit)
        pdfFile.inputStream().use { input ->
            var offset = 0
            while (offset < sampleLimit) {
                val read = input.read(buf, offset, sampleLimit - offset)
                if (read <= 0) break
                offset += read
            }
        }
        val ascii = String(buf, Charsets.ISO_8859_1)
        val hasFont = "/Font" in ascii || "/ToUnicode" in ascii
        val hasTextOps = Regex("""\bBT\b.*?\b(?:Tj|TJ)\b""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(ascii)
        val hasImages = "/Subtype /Image" in ascii || "/Subtype/Image" in ascii
        return hasImages && !hasTextOps && !hasFont
    }

    // ==================== Helpers ====================

    private fun containsBytes(haystack: ByteArray, needle: ByteArray, maxOffset: Int): Boolean {
        val limit = minOf(haystack.size, maxOffset) - needle.size
        if (limit < 0) return false
        for (i in 0..limit) {
            var match = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }

    private fun newSafeXmlParser(input: InputStream): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }
        return factory.newPullParser().apply {
            setInput(input, "UTF-8")
        }
    }

    private fun resolveRelativePath(baseDir: String, rel: String): String {
        val cleanRel = rel.substringBefore('#').trim()
        val combined = if (baseDir.isEmpty()) cleanRel else "$baseDir/$cleanRel"
        val parts = mutableListOf<String>()
        for (seg in combined.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += seg
            }
        }
        return parts.joinToString("/")
    }

    private fun isImagePath(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in setOf("png", "jpg", "jpeg", "gif", "webp", "svg")
    }
}
