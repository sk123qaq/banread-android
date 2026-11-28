package app.banread.library.internal

import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import app.banread.model.ChapterRole
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
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

        if (containsBytes(slice, KFX_MAGIC, maxOffset = 64) ||
            (slice.size >= 4 && slice[0] == 'C'.code.toByte() && slice[1] == 'O'.code.toByte() && slice[2] == 'N'.code.toByte() && slice[3] == 'T'.code.toByte())
        ) {
            return DetectedFormat.KFX_DRM
        }

        if (containsBytes(slice, PDF_MAGIC, maxOffset = 1024)) {
            return DetectedFormat.PDF
        }

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


    data class ExtractedEpubCover(
        val formatExt: String,
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
    )

    data class ParsedEpubBook(
        val title: String,
        val author: String,
        val subjects: List<String>,
        val chapters: List<RoleAndSplit.RawChapterCandidate>,
        val blockIdToHtmlIds: Map<Int, Map<String, Set<String>>>,
        val images: Map<String, ByteArray>,
        val cover: ExtractedEpubCover? = null,
    )

    fun parseEpub(epubFile: File): ParsedEpubBook {
        ZipFile(epubFile).use { zip ->
            val entries = zip.entries().asSequence().toList()
            val entryMap = linkedMapOf<String, ZipEntry>()
            for (e in entries) {
                val normalized = e.name.trimStart('/')
                if (e.name.startsWith("/") || normalized.split('/').any { it == ".." }) {
                    throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("Unsafe EPUB entry path: ${e.name}"))
                }
                entryMap[normalized] = e
            }

            entryMap["META-INF/encryption.xml"]?.let { encEntry ->
                val encXml = zip.getInputStream(encEntry).readBytes().toString(Charsets.UTF_8)
                checkEpubEncryptionXml(encXml)
            }
            if ("META-INF/rights.xml" in entryMap) {
                throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("EPUB contains DRM rights.xml"))
            }

            val containerEntry = entryMap["META-INF/container.xml"]
                ?: throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("Invalid EPUB: missing META-INF/container.xml"))
            val opfPath = parseContainerOpfPath(zip.getInputStream(containerEntry))
                ?: throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("Invalid EPUB: missing OPF rootfile"))

            val opfEntry = entryMap[opfPath]
                ?: throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("Invalid EPUB: missing OPF at $opfPath"))
            val opfDir = opfPath.substringBeforeLast('/', "")
            val opf = parseOpf(zip.getInputStream(opfEntry))

            val navHref = opf.manifestById.values.firstOrNull { item ->
                item.properties.split(Regex("\\s+")).any { it == "nav" }
            }?.href
            val ncxHref = opf.spineTocId?.let { opf.manifestById[it]?.href }
                ?: opf.manifestById.values.firstOrNull { item ->
                    item.mediaType.equals("application/x-dtbncx+xml", ignoreCase = true) ||
                        item.href.endsWith(".ncx", ignoreCase = true)
                }?.href
            val navZipPath = navHref?.let { resolveRelativePath(opfDir, it) }
            val ncxZipPath = ncxHref?.let { resolveRelativePath(opfDir, it) }
            val tocEntries = EpubTocParser.parsePreferredToc(
                zip = zip,
                entryMap = entryMap,
                navZipPath = navZipPath,
                ncxZipPath = ncxZipPath,
            )
            val tocByPath = linkedMapOf<String, MutableList<EpubTocEntry>>()
            val tocByPathLower = linkedMapOf<String, MutableList<EpubTocEntry>>()
            for (te in tocEntries) {
                tocByPath.getOrPut(te.zipPath) { mutableListOf() }.add(te)
                tocByPathLower.getOrPut(te.zipPath.lowercase()) { mutableListOf() }.add(te)
            }

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

            val spineItems = opf.spineIdRefs.mapNotNull { idRef -> opf.manifestById[idRef] }
            if (spineItems.isEmpty()) {
                throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("Invalid EPUB: empty spine"))
            }

            data class PendingChapter(
                val fileName: String,
                val title: String,
                val cameFromTocSplit: Boolean,
                val blocks: MutableList<HardWrapStitcher.StitchInputBlock>,
                var rawBytes: ByteArray?,
                val hardWrapProfile: HardWrapStitcher.HardWrapProfile,
            )

            data class ResolvedTocAnchor(
                val blockIdx: Int,
                val entry: EpubTocEntry,
            )

            val pendingChapters = mutableListOf<PendingChapter>()
            spineItems.forEach { item ->
                val resolvedPath = resolveRelativePath(opfDir, item.href)
                val entry = entryMap[resolvedPath] ?: entryMap[item.href] ?: return@forEach
                val rawBytes = zip.getInputStream(entry).readBytes()
                val xhtml = rawBytes.toString(Charsets.UTF_8)
                val extracted = extractBlocksFromXhtml(xhtml, resolvedPath)
                val fileName = resolvedPath.substringAfterLast('/')
                val hardWrapProfile = HardWrapStitcher.analyzeProfile(extracted.blocks)

                val allInputs = extracted.blocks.mapIndexed { bIdx, blk ->
                    HardWrapStitcher.StitchInputBlock(
                        block = blk,
                        htmlIds = extracted.blockIndexToHtmlIds[bIdx].orEmpty(),
                    )
                }

                val fileTocEntries = tocByPath[resolvedPath]
                    ?: tocByPathLower[resolvedPath.lowercase()]
                    ?: emptyList()

                val htmlIdToFirstBlockIdx = hashMapOf<String, Int>()
                for ((bIdx, ids) in extracted.blockIndexToHtmlIds) {
                    for (id in ids) {
                        htmlIdToFirstBlockIdx.putIfAbsent(id, bIdx)
                    }
                }

                val resolvedAnchors = if (extracted.blocks.isNotEmpty() && fileTocEntries.isNotEmpty()) {
                    val byBlockIdx = linkedMapOf<Int, EpubTocEntry>()
                    for (te in fileTocEntries) {
                        val targetIdx = if (te.fragmentId == null) {
                            0
                        } else {
                            htmlIdToFirstBlockIdx[te.fragmentId]
                        }
                        if (targetIdx != null && targetIdx in extracted.blocks.indices) {
                            byBlockIdx.putIfAbsent(targetIdx, te)
                        }
                    }
                    byBlockIdx.entries
                        .map { (bIdx, te) -> ResolvedTocAnchor(bIdx, te) }
                        .sortedBy { it.blockIdx }
                } else {
                    emptyList()
                }

                val needsTocSplit = resolvedAnchors.size >= 2 ||
                    (resolvedAnchors.size == 1 && resolvedAnchors[0].blockIdx > 0)

                if (!needsTocSplit) {
                    val singleTocLabel = resolvedAnchors.firstOrNull()?.entry?.label
                        ?: fileTocEntries.firstOrNull { it.fragmentId == null }?.label
                    val title = when {
                        extracted.hasBodyHeading && extracted.title.isNotBlank() -> extracted.title
                        !singleTocLabel.isNullOrBlank() -> singleTocLabel
                        extracted.title.isNotBlank() && (spineItems.size == 1 || extracted.title != opf.title) -> extracted.title
                        else -> fileName.substringBeforeLast('.')
                    }
                    pendingChapters += PendingChapter(
                        fileName = fileName,
                        title = title,
                        cameFromTocSplit = false,
                        blocks = allInputs.toMutableList(),
                        rawBytes = rawBytes,
                        hardWrapProfile = hardWrapProfile,
                    )
                } else {
                    val firstAnchorIdx = resolvedAnchors.first().blockIdx
                    if (firstAnchorIdx > 0) {
                        val preambleInputs = allInputs.subList(0, firstAnchorIdx)
                        val prev = pendingChapters.lastOrNull()
                        val firstPreambleBlk = preambleInputs.first().block
                        val preambleStartsWithHeading = firstPreambleBlk.kind == MarkdownBlockKind.HEADING || firstPreambleBlk.isBoldOnly
                        val prevIsContinuationCandidate = prev != null &&
                            prev.cameFromTocSplit &&
                            prev.blocks.isNotEmpty() &&
                            !preambleStartsWithHeading &&
                            RoleAndSplit.classifyChapterRole(
                                title = prev.title,
                                body = prev.blocks.joinToString("\n\n") { it.block.cleanText },
                                fileName = prev.fileName,
                                bookTitle = opf.title,
                                isOnlyChapter = false,
                            ) == ChapterRole.BODY

                        if (prevIsContinuationCandidate) {
                            prev!!.blocks.addAll(preambleInputs)
                            prev.rawBytes = null
                        } else {
                            val preambleHeading = preambleInputs.firstOrNull {
                                (it.block.kind == MarkdownBlockKind.HEADING || it.block.isBoldOnly) && it.block.cleanText.isNotBlank()
                            }?.block?.cleanText
                            val preambleChars = preambleInputs.sumOf { TextSanitizer.nonSpaceCount(it.block.cleanText) }
                            val isOpeningFrontMatter = pendingChapters.all { it.blocks.isEmpty() } && preambleChars <= 120
                            val preambleTitle = preambleHeading
                                ?: if (isOpeningFrontMatter) "扉页" else fileName.substringBeforeLast('.')
                            pendingChapters += PendingChapter(
                                fileName = fileName,
                                title = preambleTitle,
                                cameFromTocSplit = true,
                                blocks = preambleInputs.toMutableList(),
                                rawBytes = null,
                                hardWrapProfile = hardWrapProfile,
                            )
                        }
                    }

                    for (i in resolvedAnchors.indices) {
                        val start = resolvedAnchors[i].blockIdx
                        val end = if (i + 1 < resolvedAnchors.size) resolvedAnchors[i + 1].blockIdx else allInputs.size
                        if (end <= start) continue
                        val sliceInputs = allInputs.subList(start, end)
                        pendingChapters += PendingChapter(
                            fileName = fileName,
                            title = resolvedAnchors[i].entry.label,
                            cameFromTocSplit = true,
                            blocks = sliceInputs.toMutableList(),
                            rawBytes = null,
                            hardWrapProfile = hardWrapProfile,
                        )
                    }
                }
            }

            val rawCandidates = mutableListOf<RoleAndSplit.RawChapterCandidate>()
            val htmlIdMap = linkedMapOf<Int, Map<String, Set<String>>>()
            val totalChapters = pendingChapters.size
            pendingChapters.forEachIndexed { idx, pending ->
                val stitched = HardWrapStitcher.stitch(
                    inputs = pending.blocks,
                    profile = pending.hardWrapProfile,
                    chapterTitle = pending.title,
                )
                val preNumberedBlocks = stitched.map { it.block }
                val numberedBlocks = RoleAndSplit.renumberBlocks(preNumberedBlocks)
                val remappedHtmlIds = linkedMapOf<String, Set<String>>()
                stitched.forEachIndexed { bIdx, item ->
                    val newId = numberedBlocks[bIdx].id
                    if (item.htmlIds.isNotEmpty()) {
                        remappedHtmlIds[newId] = item.htmlIds
                    }
                }
                val bodyTextForRole = numberedBlocks.joinToString("\n\n") { it.cleanText }
                val role = RoleAndSplit.classifyChapterRole(
                    title = pending.title,
                    body = bodyTextForRole,
                    fileName = pending.fileName,
                    bookTitle = opf.title,
                    isOnlyChapter = totalChapters == 1,
                )
                val effectiveRawBytes = pending.rawBytes
                    ?: "${pending.fileName}#${pending.title}\n$bodyTextForRole".toByteArray(Charsets.UTF_8)

                val candidateIdx = rawCandidates.size
                htmlIdMap[candidateIdx] = remappedHtmlIds
                val nStr = (idx + 1).toString().padStart(2, '0')
                rawCandidates += RoleAndSplit.RawChapterCandidate(
                    n = nStr,
                    stem = pending.fileName.substringBeforeLast('.'),
                    fileName = pending.fileName,
                    title = pending.title,
                    role = role,
                    chars = numberedBlocks.sumOf { TextSanitizer.nonSpaceCount(it.cleanText) },
                    blocks = numberedBlocks,
                    rawMarkdown = bodyTextForRole,
                    rawContentBytes = effectiveRawBytes,
                    blockIdToHtmlIds = remappedHtmlIds,
                )
            }

            if (rawCandidates.isEmpty()) {
                throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("EPUB produced no readable chapters"))
            }

            val cover = extractEpubCover(
                zip = zip,
                entryMap = entryMap,
                opfDir = opfDir,
                opf = opf,
            )

            return ParsedEpubBook(
                title = opf.title.ifBlank { epubFile.nameWithoutExtension },
                author = opf.author,
                subjects = opf.subjects,
                chapters = rawCandidates,
                blockIdToHtmlIds = htmlIdMap,
                images = images,
                cover = cover,
            )
        }
    }

    private fun extractEpubCover(
        zip: ZipFile,
        entryMap: Map<String, ZipEntry>,
        opfDir: String,
        opf: ParsedOpf,
    ): ExtractedEpubCover? {
        val candidates = buildList {
            // (a) EPUB3 <item properties="...cover-image...">
            opf.manifestById.values.firstOrNull { item ->
                item.properties.split(Regex("\\s+")).any { it == "cover-image" }
            }?.let { add(it) }

            // (b) EPUB2 <meta name="cover" content="id"> -> manifest <item>
            opf.metaCoverId?.let { coverId ->
                opf.manifestById[coverId]?.let { add(it) }
            }

            // (c) Fallback manifest image <item> whose id or href contains "cover"
            opf.manifestById.values.filter { item ->
                val isImage = item.mediaType.startsWith("image/", ignoreCase = true) || isImagePath(item.href)
                isImage && (item.id.contains("cover", ignoreCase = true) || item.href.contains("cover", ignoreCase = true))
            }.forEach { add(it) }
        }.distinctBy { it.id }

        for (candidate in candidates) {
            val safeZipPath = resolveSafeCoverZipPath(opfDir, candidate.href) ?: continue
            val entry = entryMap[safeZipPath] ?: continue
            if (entry.isDirectory) continue
            if (entry.size > ImageHeaderInspector.MAX_COVER_BYTES) continue
            val bytes = readBoundedBytes(zip.getInputStream(entry), ImageHeaderInspector.MAX_COVER_BYTES) ?: continue
            val inspected = ImageHeaderInspector.inspect(bytes) ?: continue
            return ExtractedEpubCover(
                formatExt = inspected.format.extension,
                bytes = bytes,
                width = inspected.width,
                height = inspected.height,
            )
        }
        return null
    }

    internal fun resolveSafeCoverZipPath(baseDir: String, rawHref: String): String? {
        val rawNoFrag = rawHref.substringBefore('#').substringBefore('?').trim()
        if (rawNoFrag.isEmpty()) return null
        val decodedHref = decodeUriPath(rawNoFrag) ?: return null
        if (decodedHref.isEmpty() ||
            decodedHref.startsWith("/") ||
            decodedHref.startsWith("\\") ||
            '\\' in decodedHref ||
            "://" in decodedHref ||
            '\u0000' in decodedHref
        ) {
            return null
        }

        val resolvedParts = mutableListOf<String>()
        if (baseDir.isNotBlank()) {
            for (seg in baseDir.split('/')) {
                when (seg) {
                    "", "." -> Unit
                    ".." -> return null
                    else -> resolvedParts += seg
                }
            }
        }
        for (seg in decodedHref.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> {
                    if (resolvedParts.isEmpty()) return null
                    resolvedParts.removeAt(resolvedParts.lastIndex)
                }
                else -> resolvedParts += seg
            }
        }
        return resolvedParts.joinToString("/").takeIf { it.isNotEmpty() }
    }

    private fun decodeUriPath(raw: String): String? {
        if ('%' !in raw) return raw
        return runCatching {
            URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8.name())
        }.getOrNull()
    }

    private fun readBoundedBytes(input: InputStream, maxBytes: Long): ByteArray? {
        return input.use { stream ->
            val buf = ByteArray(8192)
            val out = java.io.ByteArrayOutputStream()
            var total = 0L
            while (true) {
                val read = stream.read(buf)
                if (read <= 0) break
                total += read
                if (total > maxBytes) return null
                out.write(buf, 0, read)
            }
            out.toByteArray()
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

    private data class OpfManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String = "",
    )

    private data class ParsedOpf(
        val title: String,
        val author: String,
        val subjects: List<String>,
        val manifestById: Map<String, OpfManifestItem>,
        val spineIdRefs: List<String>,
        val metaCoverId: String? = null,
        val spineTocId: String? = null,
    )

    private fun parseOpf(input: InputStream): ParsedOpf {
        val parser = newSafeXmlParser(input)
        var title = ""
        val authors = mutableListOf<String>()
        val subjects = mutableListOf<String>()
        val manifest = linkedMapOf<String, OpfManifestItem>()
        val spine = mutableListOf<String>()
        var metaCoverId: String? = null
        var spineTocId: String? = null

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
                    "meta" -> {
                        val metaName = parser.getAttributeValue(null, "name").orEmpty()
                        val metaContent = parser.getAttributeValue(null, "content").orEmpty()
                        if (metaName.equals("cover", ignoreCase = true) && metaContent.isNotBlank() && metaCoverId == null) {
                            metaCoverId = metaContent.trim()
                        }
                    }
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id").orEmpty()
                        val href = parser.getAttributeValue(null, "href").orEmpty()
                        val mediaType = parser.getAttributeValue(null, "media-type").orEmpty()
                        val properties = parser.getAttributeValue(null, "properties").orEmpty()
                        if (id.isNotEmpty() && href.isNotEmpty()) {
                            manifest[id] = OpfManifestItem(id, href, mediaType, properties)
                        }
                    }
                    "spine" -> {
                        val toc = parser.getAttributeValue(null, "toc").orEmpty().trim()
                        if (toc.isNotEmpty()) spineTocId = toc
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
            metaCoverId = metaCoverId,
            spineTocId = spineTocId,
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
        val hasBodyHeading: Boolean,
        val blocks: List<ParsedBlock>,
        val blockIndexToHtmlIds: Map<Int, Set<String>>,
    )

    private fun extractBlocksFromXhtml(xhtml: String, chapterPath: String): XhtmlExtractionResult {
        val blocks = mutableListOf<ParsedBlock>()
        val htmlIdsByBlockIdx = linkedMapOf<Int, MutableSet<String>>()
        var headTitle = ""
        var bodyHeadingTitle = ""
        var hasBodyHeading = false

        val cleanedXml = TextSanitizer.sanitizeRawPreservingMath(xhtml)
        try {
            val parser = newSafeXmlParser(ByteArrayInputStream(cleanedXml.toByteArray(Charsets.UTF_8)))
            var inBody = false
            var inHeadTitle = false
            val headTitleBuf = StringBuilder()

            val pendingHtmlIds = mutableSetOf<String>()

            var blockTag: String? = null
            var blockTagDepth = 0
            var blockIsFootnote = false
            var blockHeadingLevel: Int? = null
            var currentBlockHtmlIds = mutableSetOf<String>()
            val blockTextBuf = StringBuilder()
            val blockRefs = mutableListOf<InlineFnRef>()
            var boldDepth = 0
            var hasBoldText = false
            var hasNonBoldText = false
            var listItems = mutableListOf<String>()
            var listItemRefs = mutableListOf<List<InlineFnRef>>()
            var inLi = false
            val liTextBuf = StringBuilder()
            val liRefs = mutableListOf<InlineFnRef>()

            var inNoteref = false
            var noterefHref = ""
            val noterefLabelBuf = StringBuilder()
            var inSup = false
            var supAnchorHref: String? = null

            val chapterDir = chapterPath.substringBeforeLast('/', "")

            fun flushTextSegmentOnly() {
                val tag = blockTag ?: return
                val clean = TextSanitizer.cleanInline(blockTextBuf.toString())
                if (clean.isNotEmpty() || blockRefs.isNotEmpty()) {
                    val idx = blocks.size
                    val kind = when {
                        blockIsFootnote -> MarkdownBlockKind.FOOTNOTE
                        tag in setOf("h1", "h2", "h3", "h4", "h5", "h6") -> MarkdownBlockKind.HEADING
                        tag == "blockquote" -> MarkdownBlockKind.QUOTE
                        tag == "pre" -> MarkdownBlockKind.CODE
                        else -> MarkdownBlockKind.PARAGRAPH
                    }
                    if (kind == MarkdownBlockKind.HEADING) {
                        hasBodyHeading = true
                        if (bodyHeadingTitle.isBlank()) bodyHeadingTitle = clean
                    }
                    val (fnDisp, fnKey) = if (kind == MarkdownBlockKind.FOOTNOTE) {
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
                    val finalClean = if (kind == MarkdownBlockKind.FOOTNOTE) {
                        clean.replaceFirst(Regex("""^\[?(\d{1,4})\]?(?:[.。、:：]|\s)+"""), "").ifBlank { clean }
                    } else {
                        clean
                    }
                    val isBoldOnly = kind == MarkdownBlockKind.PARAGRAPH && hasBoldText && !hasNonBoldText && finalClean.isNotEmpty()
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
                        isBoldOnly = isBoldOnly,
                    )
                    if (currentBlockHtmlIds.isNotEmpty()) {
                        htmlIdsByBlockIdx[idx] = currentBlockHtmlIds.toMutableSet()
                    }
                    currentBlockHtmlIds = mutableSetOf()
                }
                blockTextBuf.clear()
                blockRefs.clear()
                hasBoldText = false
                hasNonBoldText = false
            }

            fun flushBlock() {
                val tag = blockTag ?: return
                val idx = blocks.size
                when (tag) {
                    "ul", "ol" -> {
                        if (listItems.isNotEmpty()) {
                            blocks += ParsedBlock(
                                id = "B${(idx + 1).toString().padStart(2, '0')}",
                                blockIndex = idx,
                                kind = if (tag == "ol") MarkdownBlockKind.ORDERED_LIST else MarkdownBlockKind.LIST,
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
                    else -> flushTextSegmentOnly()
                }
                blockTag = null
                blockTagDepth = 0
                blockIsFootnote = false
                blockHeadingLevel = null
                currentBlockHtmlIds = mutableSetOf()
                blockTextBuf.clear()
                blockRefs.clear()
                boldDepth = 0
                hasBoldText = false
                hasNonBoldText = false
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
                        val elemId = parser.getAttributeValue(null, "id")?.takeIf { it.isNotBlank() }
                            ?: if (tag == "a") parser.getAttributeValue(null, "name")?.takeIf { it.isNotBlank() } else null
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
                                    if (pendingHtmlIds.isNotEmpty()) {
                                        currentBlockHtmlIds.addAll(pendingHtmlIds)
                                        pendingHtmlIds.clear()
                                    }
                                    if (elemId != null) currentBlockHtmlIds += elemId
                                } else if (tag == "hr") {
                                    val idx = blocks.size
                                    blocks += ParsedBlock(
                                        id = "B${(idx + 1).toString().padStart(2, '0')}",
                                        blockIndex = idx,
                                        kind = MarkdownBlockKind.HR,
                                        headingPath = "",
                                        cleanText = "",
                                        charStart = 0,
                                        charEnd = 0,
                                    )
                                    if (elemId != null) {
                                        htmlIdsByBlockIdx[idx] = mutableSetOf(elemId)
                                    }
                                } else if (tag == "img") {
                                    val src = parser.getAttributeValue(null, "src").orEmpty()
                                    val alt = parser.getAttributeValue(null, "alt").orEmpty()
                                    if (src.isNotBlank()) {
                                        val idx = blocks.size
                                        val rel = resolveRelativePath(chapterDir, src)
                                        blocks += ParsedBlock(
                                            id = "B${(idx + 1).toString().padStart(2, '0')}",
                                            blockIndex = idx,
                                            kind = MarkdownBlockKind.IMAGE,
                                            headingPath = "",
                                            cleanText = alt,
                                            charStart = 0,
                                            charEnd = alt.length,
                                            imageRel = rel,
                                            imageAlt = alt,
                                        )
                                        val imgIds = mutableSetOf<String>()
                                        if (pendingHtmlIds.isNotEmpty()) {
                                            imgIds.addAll(pendingHtmlIds)
                                            pendingHtmlIds.clear()
                                        }
                                        if (elemId != null) imgIds += elemId
                                        if (imgIds.isNotEmpty()) htmlIdsByBlockIdx[idx] = imgIds
                                    } else if (elemId != null) {
                                        pendingHtmlIds += elemId
                                    }
                                } else if (elemId != null) {
                                    pendingHtmlIds += elemId
                                }
                            } else {
                                // Handle malformed Calibre HTML where <p> wraps block-level <h1>..<h6>/<ul>/<ol>
                                if (blockTag == "p" && tag in setOf("h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol")) {
                                    if (blockTextBuf.isNotEmpty() || blockRefs.isNotEmpty()) {
                                        flushTextSegmentOnly()
                                    }
                                    blockTag = tag
                                    blockTagDepth = 1
                                    blockHeadingLevel = if (tag.startsWith("h") && tag.length == 2) tag[1].digitToIntOrNull() else null
                                    if (elemId != null) currentBlockHtmlIds += elemId
                                    event = parser.next()
                                    continue
                                }
                                if (tag == blockTag) blockTagDepth++
                                if (elemId != null) currentBlockHtmlIds += elemId
                                if (tag == "b" || tag == "strong") boldDepth++
                                if (tag == "br" && blockTag != "pre") {
                                    if (inLi) liTextBuf.append(' ') else blockTextBuf.append(' ')
                                }
                                if (tag == "img" && blockTag !in setOf("ul", "ol", "pre")) {
                                    val src = parser.getAttributeValue(null, "src").orEmpty()
                                    val alt = parser.getAttributeValue(null, "alt").orEmpty()
                                    if (src.isNotBlank()) {
                                        flushTextSegmentOnly()
                                        val idx = blocks.size
                                        val rel = resolveRelativePath(chapterDir, src)
                                        blocks += ParsedBlock(
                                            id = "B${(idx + 1).toString().padStart(2, '0')}",
                                            blockIndex = idx,
                                            kind = MarkdownBlockKind.IMAGE,
                                            headingPath = "",
                                            cleanText = alt,
                                            charStart = 0,
                                            charEnd = alt.length,
                                            imageRel = rel,
                                            imageAlt = alt,
                                        )
                                        if (currentBlockHtmlIds.isNotEmpty()) {
                                            htmlIdsByBlockIdx[idx] = currentBlockHtmlIds.toMutableSet()
                                            currentBlockHtmlIds = mutableSetOf()
                                        }
                                    }
                                }
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
                            if (txt.any { !it.isWhitespace() }) {
                                if (boldDepth > 0) hasBoldText = true else hasNonBoldText = true
                            }
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
                            if (headTitle.isBlank()) headTitle = headTitleBuf.toString().trim()
                        } else if (tag == "body") {
                            flushBlock()
                            inBody = false
                        } else if (inBody && blockTag != null) {
                            if ((tag == "b" || tag == "strong") && boldDepth > 0) {
                                boldDepth--
                            }
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
            val stripped = TextSanitizer.cleanInline(cleanedXml.replace(Regex("<[^>]+>"), " "))
            if (stripped.isNotBlank() && blocks.isEmpty()) {
                blocks += ParsedBlock(
                    id = "B01",
                    blockIndex = 0,
                    kind = MarkdownBlockKind.PARAGRAPH,
                    headingPath = "",
                    cleanText = stripped,
                    charStart = 0,
                    charEnd = stripped.length,
                )
            }
        }
        return XhtmlExtractionResult(
            title = bodyHeadingTitle.ifBlank { headTitle },
            hasBodyHeading = hasBodyHeading,
            blocks = blocks,
            blockIndexToHtmlIds = htmlIdsByBlockIdx,
        )
    }


    data class ParsedMobiResult(
        val formatName: String,
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
                    kind = MarkdownBlockKind.PARAGRAPH,
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

    internal fun newSafeXmlParser(input: InputStream): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }
        return factory.newPullParser().apply {
            setInput(input, "UTF-8")
        }
    }

    private fun resolveRelativePath(baseDir: String, rel: String): String {
        val rawNoFrag = rel.substringBefore('#').substringBefore('?').trim()
        val cleanRel = decodeUriPath(rawNoFrag) ?: rawNoFrag
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
