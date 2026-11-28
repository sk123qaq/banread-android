package app.banread.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import app.banread.model.BcBookManifest
import app.banread.model.BlockId
import app.banread.model.BookSourceHash
import app.banread.model.ChapterContentHash
import app.banread.model.ChapterOrdinal
import app.banread.model.ChapterRole
import app.banread.model.LibraryVault
import app.banread.model.LogicalChapterEntry
import app.banread.model.ReadProfileId
import app.banread.model.ReadingAnchor
import app.banread.model.ReadingSurfaceMode
import app.banread.model.WorkId
import app.banread.model.registry.ProfileRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryVaultContractTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesRoot: File
    private lateinit var profileRegistry: ProfileRegistry
    private lateinit var vault: LibraryVault
    private val json = Json { encodeDefaults = true; prettyPrint = true }

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        filesRoot = tempFolder.newFolder("vault_files")
        profileRegistry = ProfileRegistry.loadFromClasspath()
        vault = LibraryVaultFactory.create(
            context = context,
            filesRoot = filesRoot,
            profileRegistry = profileRegistry,
            allowInMemory = true,
        )
    }

    @After
    fun tearDown() {
        (vault as? Closeable)?.close()
    }

    @Test
    fun `ingestStudyPack preserves merged title ordinals, compiles XHTML, copies cache, and loads un-gated book inputs`() = runTest {
        val zipFile = buildRealisticBcBookZip(tempFolder.newFile("B0042.bcbook"))
        val imported = vault.ingestStudyPack(zipFile.absolutePath).getOrThrow()

        assertEquals(WorkId("B0042"), imported.workId)
        assertEquals("雾夜档案", imported.title)
        assertEquals(ReadProfileId("literature"), imported.readProfileId)
        assertEquals("mystery", imported.literatureSubKind)
        assertEquals(setOf(ReadingSurfaceMode.REFLOWABLE), imported.supportedModes)
        assertFalse(imported.isScannedPdf)

        val outline = vault.getWorkOutline(imported.workId)
        assertEquals(18, outline.bodyChapters.size)
        assertEquals(1, outline.notesChapters.size)
        assertEquals(1, outline.frontBackChapters.size)

        val ch1 = outline.frontBackChapters.single()
        assertEquals(ChapterOrdinal(1), ch1.ordinal)
        assertTrue(ch1.merged)
        assertEquals(ChapterRole.FRONT_BACK, ch1.role)
        assertTrue(ch1.estimatedMinutes >= 1)

        val notesCh = outline.notesChapters.single()
        assertEquals(ChapterOrdinal(20), notesCh.ordinal)
        assertEquals(ChapterRole.NOTES, notesCh.role)

        val compiledDir = File(filesRoot, "compiled/B0042")
        assertTrue(File(compiledDir, "META-INF/container.xml").isFile)
        val opfText = File(compiledDir, "OEBPS/content.opf").readText()
        assertTrue(opfText.contains("ch_03.xhtml"))
        assertTrue(opfText.contains("images/diagram.png"))
        assertTrue(File(compiledDir, "OEBPS/images/diagram.png").isFile)

        val ch03Html = File(compiledDir, "OEBPS/ch_03.xhtml").readText()
        assertTrue(ch03Html.contains("data-block-id=\"B01\""))
        assertTrue(ch03Html.contains("epub:type=\"noteref\""))
        assertTrue(ch03Html.contains("data-fn-marker=\"45\""))
        assertFalse(ch03Html.contains("\uE005"))
        assertTrue(ch03Html.contains("\$E = mc^2\$"))

        val copiedCache = File(filesRoot, "study_packs/B0042/companion_cache/ch_02/chapter_map.json")
        assertTrue(copiedCache.isFile)

        val anchor = vault.resolveAnchor(imported.workId, ChapterOrdinal(3), BlockId("B01"))
        assertTrue(anchor is ReadingAnchor.ReflowCfi)
        val reflowAnchor = anchor as ReadingAnchor.ReflowCfi
        assertEquals("OEBPS/ch_03.xhtml", reflowAnchor.href)
        assertEquals(BlockId("B01"), reflowAnchor.blockId)
        assertTrue(reflowAnchor.textQuote.exact.contains("红蜡封缄"))

        val fnByMarker = vault.resolveFootnote(imported.workId, ChapterOrdinal(3), "[45]")
        assertNotNull(fnByMarker)
        assertEquals(ChapterOrdinal(3), fnByMarker!!.link.sourceChapterOrdinal)
        assertEquals(ChapterOrdinal(20), fnByMarker.link.targetChapterOrdinal)
        assertEquals("全书注释", fnByMarker.targetChapterTitle)
        assertTrue(fnByMarker.fullNoteText.contains("十九世纪港口海关"))

        val fnHref = "ch_20.xhtml#${fnByMarker.link.targetBlockId.value}"
        val fnByHref = vault.resolveFootnote(imported.workId, ChapterOrdinal(3), fnHref)
        assertNotNull(fnByHref)
        assertEquals(fnByMarker.link.id, fnByHref!!.link.id)

        val sliceFull = vault.loadBookInputs(imported.workId)
        assertEquals(18, sliceFull.chapters.size)
        assertTrue(sliceFull.chapters.none { it.role != ChapterRole.BODY })
        assertEquals(setOf("45", "102"), sliceFull.footnotes.map { it.marker }.toSet())
        assertTrue(sliceFull.footnotes.none { it.marker == "999" })
    }

    @Test
    fun `cross-checks real bundler output from slice1-mobile-gateway when workspace is present`() = runTest {
        val slice1Repo = File("/home/shike/orca/workspaces/book-corpus/slice1-mobile-gateway")
        val venvPython = File(slice1Repo, ".venv/bin/python")
        assumeTrue(slice1Repo.isDirectory && venvPython.isFile)

        val corpusRoot = tempFolder.newFolder("synthetic_corpus")
        File(corpusRoot, "books.toml").writeText("schema = 2\n")
        val mdDir = File(corpusRoot, "books/B0001/md").apply { mkdirs() }
        val imgDir = File(mdDir, "images").apply { mkdirs() }
        File(imgDir, "plate.png").writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))

        val ch00 = "---\nchapter_role: body\n---\n# 序章\n\n题记。\n"
        val ch01 = "---\nchapter_role: body\n---\n# 前置页\n\n" + "这是合并后的正文内容，包含足够多的字数以满足前置页合并条件。".repeat(25) + "\n"
        val ch02 = "---\nchapter_role: body\n---\n# 第一章\n\n朱元璋微时之事[^1]。\n"
        val ch03 = "---\nchapter_role: notes\n---\n# 注释\n\n[^1]: 明史本纪记载。\n"
        File(mdDir, "00_扉页.md").writeText(ch00)
        File(mdDir, "01_前置页.md").writeText(ch01)
        File(mdDir, "02_第一章.md").writeText(ch02)
        File(mdDir, "03_注释.md").writeText(ch03)

        val fakeSrcHash = "a".repeat(64)
        File(corpusRoot, "books/B0001/book.json").writeText(
            """{"uid":"B0001","title":"大明测试","author":"测试作者","subjects":["人文社科","历史"],"sources":[{"primary_src":true,"sha256":"$fakeSrcHash"}]}""",
        )
        File(mdDir, ".manifest.json").writeText("""{"canonical_version":1,"source_sha256":"$fakeSrcHash"}""")

        val outBundle = tempFolder.newFile("B0001_from_py.bcbook")
        val pyScript = """
            import sys
            from pathlib import Path
            sys.path.insert(0, str(Path("${slice1Repo.absolutePath}/src")))
            from bookcorpus.reading.library import Library
            from bookcorpus.mobile_gateway.bundler import build_bcbook_bundle
            lib = Library("${corpusRoot.absolutePath}")
            build_bcbook_bundle(lib, "B0001", "${outBundle.absolutePath}", include_cache=False)
        """.trimIndent()
        val proc = ProcessBuilder(venvPython.absolutePath, "-c", pyScript)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        assertEquals("bundler.py failed: $output", 0, proc.waitFor())

        val imported = vault.ingestStudyPack(outBundle.absolutePath).getOrThrow()
        assertEquals(WorkId("B0001"), imported.workId)
        assertEquals(ReadProfileId("history"), imported.readProfileId)

        val outline = vault.getWorkOutline(WorkId("B0001"))
        assertEquals(2, outline.bodyChapters.size)
        assertEquals(1, outline.notesChapters.size)
        assertTrue(outline.bodyChapters[0].merged)
        assertEquals(ChapterOrdinal(1), outline.bodyChapters[0].ordinal)
        assertEquals(ChapterOrdinal(2), outline.bodyChapters[1].ordinal)
        assertEquals(ChapterOrdinal(3), outline.notesChapters[0].ordinal)

        val fn = vault.resolveFootnote(WorkId("B0001"), ChapterOrdinal(2), "1")
        assertNotNull(fn)
        assertEquals(ChapterOrdinal(3), fn!!.link.targetChapterOrdinal)
        assertTrue(fn.fullNoteText.contains("明史本纪"))
    }

    @Test
    fun `importLocalBook supports DRM-free EPUB, deduplicates identical SHA-256, and allows font obfuscation`() = runTest {
        val epubFile = buildMinimalEpub(
            dest = tempFolder.newFile("history_book.epub"),
            title = "明代赋税考",
            author = "黄仁宇",
            subject = "历史",
            includeFontObfuscation = true,
        )
        val imported1 = vault.importLocalBook(epubFile.absolutePath).getOrThrow()
        assertEquals("明代赋税考", imported1.title)
        assertEquals("黄仁宇", imported1.author)
        assertEquals(ReadProfileId("history"), imported1.readProfileId)
        assertEquals(profileRegistry.clothColor(imported1.workId.raw), imported1.clothColor)
        assertEquals(setOf(ReadingSurfaceMode.REFLOWABLE), imported1.supportedModes)

        val outline = vault.getWorkOutline(imported1.workId)
        assertEquals(1, outline.bodyChapters.size)
        assertEquals(1, outline.notesChapters.size)

        val fn = vault.resolveFootnote(imported1.workId, ChapterOrdinal(1), "1")
        assertNotNull(fn)
        assertEquals(ChapterOrdinal(2), fn!!.link.targetChapterOrdinal)
        assertTrue(fn.fullNoteText.contains("一条鞭法"))

        val copyFile = tempFolder.newFile("history_book_copy.epub").apply {
            writeBytes(epubFile.readBytes())
        }
        val imported2 = vault.importLocalBook(copyFile.absolutePath).getOrThrow()
        assertEquals(imported1.workId, imported2.workId)
    }

    @Test
    fun `importLocalBook supports DRM-free MOBI and PDF scanned vs text detection`() = runTest {
        val mobiFile = buildMinimalMobi(
            dest = tempFolder.newFile("sample.mobi"),
            title = "社会契约论",
            encryptionType = 0,
            bodyText = "人是生而自由的，但却无往不在枷锁之中。",
        )
        val importedMobi = vault.importLocalBook(mobiFile.absolutePath).getOrThrow()
        assertEquals("社会契约论", importedMobi.title)
        assertEquals(setOf(ReadingSurfaceMode.REFLOWABLE), importedMobi.supportedModes)
        val mobiAnchor = vault.resolveAnchor(importedMobi.workId, ChapterOrdinal(1), BlockId("B01"))
        assertTrue(mobiAnchor is ReadingAnchor.ReflowCfi)

        val textPdf = tempFolder.newFile("text_paper.pdf").apply {
            writeText("%PDF-1.4\n1 0 obj << /Font << /F1 2 0 R >> >> endobj\nBT /F1 12 Tf (Hello PDF) Tj ET\n%%EOF")
        }
        val importedTextPdf = vault.importLocalBook(textPdf.absolutePath).getOrThrow()
        assertEquals(setOf(ReadingSurfaceMode.PDF_FIXED), importedTextPdf.supportedModes)
        assertFalse(importedTextPdf.isScannedPdf)
        val reflowProgress = vault.prepareTextPdfReflow(importedTextPdf.workId).toList()
        assertTrue(reflowProgress.last().completed)

        val scannedPdf = tempFolder.newFile("scanned.pdf").apply {
            writeText("%PDF-1.4\n1 0 obj << /Type /XObject /Subtype /Image /Width 1200 /Height 1800 >> endobj\n%%EOF")
        }
        val importedScannedPdf = vault.importLocalBook(scannedPdf.absolutePath).getOrThrow()
        assertTrue(importedScannedPdf.isScannedPdf)
    }

    @Test
    fun `fail-fast rejects DRM EPUB, encrypted MOBI, KFX magic, and Zip-Slip bcbook`() = runTest {
        val drmEpub = buildMinimalEpub(
            dest = tempFolder.newFile("drm.epub"),
            title = "Locked EPUB",
            author = "Anon",
            subject = "历史",
            includeDrmEncryption = true,
        )
        assertUnsupportedFailure(vault.importLocalBook(drmEpub.absolutePath))

        val drmMobi = buildMinimalMobi(
            dest = tempFolder.newFile("drm.mobi"),
            title = "Locked MOBI",
            encryptionType = 2,
            bodyText = "encrypted",
        )
        assertUnsupportedFailure(vault.importLocalBook(drmMobi.absolutePath))

        val kfxFile = tempFolder.newFile("locked.kfx").apply {
            writeBytes(byteArrayOf(0xEA.toByte(), 'D'.code.toByte(), 'R'.code.toByte(), 'M'.code.toByte(), 'I'.code.toByte(), 'O'.code.toByte(), 'N'.code.toByte(), 0xEE.toByte(), 1, 2, 3, 4))
        }
        assertUnsupportedFailure(vault.importLocalBook(kfxFile.absolutePath))

        val zipSlipPack = tempFolder.newFile("evil.bcbook")
        ZipOutputStream(FileOutputStream(zipSlipPack)).use { zos ->
            zos.putNextEntry(ZipEntry("../../evil.txt"))
            zos.write("pwned".toByteArray())
            zos.closeEntry()
        }
        assertUnsupportedFailure(vault.ingestStudyPack(zipSlipPack.absolutePath))
        assertFalse(File(filesRoot, "evil.txt").exists())
    }

    @Test
    fun `giant single-chapter over 24k chars splits into 8k-18k virtual logical chapters`() = runTest {
        val giantParagraph = "这是一段用于测试超长单章虚拟切分边界的长文本段落，确保切分严格落在八千到一万八千字区间。"
        val giantBody = buildString {
            appendLine("# 巨型单章")
            appendLine()
            repeat(600) { idx ->
                if (idx > 0 && idx % 150 == 0) {
                    appendLine("## 分节 ${idx / 150}")
                    appendLine()
                }
                appendLine("$idx. $giantParagraph")
                appendLine()
            }
        }
        val chBytes = giantBody.toByteArray(Charsets.UTF_8)
        val manifest = BcBookManifest(
            schemaVersion = 1,
            uid = WorkId("B9001"),
            title = "巨章测试集",
            author = "测试",
            subjects = listOf("社科"),
            readType = ReadProfileId("social"),
            subKind = "",
            bookSourceSha256 = BookSourceHash(sha256Hex(chBytes)),
            clothColor = profileRegistry.clothColor("B9001"),
            logicalChapters = listOf(
                LogicalChapterEntry(
                    ordinal = ChapterOrdinal(1),
                    n = "01",
                    title = "巨型单章",
                    chapterRole = ChapterRole.BODY,
                    merged = false,
                    sourceFiles = listOf("01_巨型单章.md"),
                    chapterContentSha256 = ChapterContentHash(sha256Hex(chBytes)),
                    chars = giantBody.count { !it.isWhitespace() },
                ),
            ),
        )
        val zipFile = tempFolder.newFile("giant.bcbook")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(json.encodeToString(BcBookManifest.serializer(), manifest).toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("md/01_巨型单章.md"))
            zos.write(chBytes)
            zos.closeEntry()
        }

        val imported = vault.ingestStudyPack(zipFile.absolutePath).getOrThrow()
        val outline = vault.getWorkOutline(imported.workId)
        assertTrue("Expected >= 2 virtual chapters, got ${outline.bodyChapters.size}", outline.bodyChapters.size >= 2)

        val bookSlice = vault.loadBookInputs(imported.workId)
        assertEquals(outline.bodyChapters.size, bookSlice.chapters.size)
        for (slice in bookSlice.chapters) {
            val charCount = slice.blocks.sumOf { blk -> blk.text.count { !it.isWhitespace() } }
            assertTrue("Virtual chapter ${slice.ordinal.value} has $charCount chars (expected 8000..18000)", charCount in 8_000..18_000)
        }
    }

    @Test
    fun `overrideProfile persists across outline queries and re-ingest`() = runTest {
        val zipFile = buildRealisticBcBookZip(tempFolder.newFile("B0042_override.bcbook"))
        val imported = vault.ingestStudyPack(zipFile.absolutePath).getOrThrow()
        assertEquals(ReadProfileId("literature"), imported.readProfileId)

        vault.overrideProfile(imported.workId, ReadProfileId("history"), null)
        val reIngested = vault.ingestStudyPack(zipFile.absolutePath).getOrThrow()
        assertEquals(ReadProfileId("history"), reIngested.readProfileId)
        assertNull(reIngested.literatureSubKind)
    }

    @Test
    fun `loadBookInputs filters translator and bibliography boilerplate notes without truncating chapters`() = runTest {
        val zipFile = buildRealisticBcBookZip(tempFolder.newFile("B0042_boilerplate.bcbook"))
        val imported = vault.ingestStudyPack(zipFile.absolutePath).getOrThrow()

        val slice = vault.loadBookInputs(imported.workId)
        assertEquals(18, slice.chapters.size)
        assertEquals(setOf("45", "102"), slice.footnotes.map { it.marker }.toSet())
        assertTrue(slice.footnotes.none { it.marker == "999" })
    }

    @Test
    fun `markdown list-item footnotes resolve to FootnoteEntity and valid compiled XHTML noterefs`() = runTest {
        val ch1Md = buildString {
            appendLine("# 第一章 论辩")
            appendLine()
            appendLine("- 第一条论据[^1]")
            appendLine("- 第二条论据[^2]")
        }
        val notesMd = buildString {
            appendLine("# 注释")
            appendLine()
            appendLine("[^1]: 第一条论据出自《史记》。")
            appendLine()
            appendLine("[^2]: 第二条论据出自《汉书》。")
        }
        val zipFile = buildCustomBcBookZip(
            dest = tempFolder.newFile("B7001_list_fn.bcbook"),
            workId = "B7001",
            sourceSha256Hex = "c".repeat(64),
            chapters = listOf(
                Triple("01_第一章.md", ChapterRole.BODY, ch1Md),
                Triple("02_注释.md", ChapterRole.NOTES, notesMd),
            ),
        )

        val imported = vault.ingestStudyPack(zipFile.absolutePath).getOrThrow()
        val fn1 = vault.resolveFootnote(imported.workId, ChapterOrdinal(1), "1")
        val fn2 = vault.resolveFootnote(imported.workId, ChapterOrdinal(1), "2")
        assertNotNull(fn1)
        assertNotNull(fn2)
        assertEquals(ChapterOrdinal(2), fn1!!.link.targetChapterOrdinal)
        assertEquals(ChapterOrdinal(2), fn2!!.link.targetChapterOrdinal)
        assertTrue(fn1.fullNoteText.contains("史记"))
        assertTrue(fn2.fullNoteText.contains("汉书"))

        val slice = vault.loadBookInputs(imported.workId)
        assertEquals(setOf("1", "2"), slice.footnotes.map { it.marker }.toSet())

        val ch01Html = File(filesRoot, "compiled/B7001/OEBPS/ch_01.xhtml").readText()
        assertTrue(ch01Html.contains("<ul "))
        assertTrue(ch01Html.contains("<li>第一条论据<a epub:type=\"noteref\" role=\"doc-noteref\" href=\"ch_02.xhtml#${fn1.link.targetBlockId.value}\""))
        assertTrue(ch01Html.contains("<li>第二条论据<a epub:type=\"noteref\" role=\"doc-noteref\" href=\"ch_02.xhtml#${fn2.link.targetBlockId.value}\""))
        val ch02Html = File(filesRoot, "compiled/B7001/OEBPS/ch_02.xhtml").readText()
        assertTrue(ch02Html.contains("id=\"${fn1.link.targetBlockId.value}\""))
        assertTrue(ch02Html.contains("id=\"${fn2.link.targetBlockId.value}\""))
    }

    @Test
    fun `bcbook supersedes local EPUB with identical bookSourceSha256 and cleans up orphaned state`() = runTest {
        val epubFile = buildMinimalEpub(
            dest = tempFolder.newFile("local_source.epub"),
            title = "待升级本地书",
            author = "作者甲",
            subject = "历史",
        )
        val localSha = sha256Hex(epubFile.readBytes())
        val localImported = vault.importLocalBook(epubFile.absolutePath).getOrThrow()
        assertTrue(localImported.workId.raw.startsWith("local_"))
        assertTrue(File(filesRoot, "compiled/${localImported.workId.raw}").isDirectory)
        assertTrue(File(filesRoot, "books/${localImported.workId.raw}.epub").isFile)

        vault.overrideProfile(localImported.workId, ReadProfileId("philosophy"), null)

        val bcbookFile = buildRealisticBcBookZip(
            dest = tempFolder.newFile("B0042_supersede.bcbook"),
            bookSourceSha256Hex = localSha,
        )
        val upgraded = vault.ingestStudyPack(bcbookFile.absolutePath).getOrThrow()
        assertEquals(WorkId("B0042"), upgraded.workId)
        assertEquals(ReadProfileId("philosophy"), upgraded.readProfileId)
        assertNull(upgraded.literatureSubKind)

        val missingOutline = runCatching { vault.getWorkOutline(localImported.workId) }
        assertTrue(missingOutline.exceptionOrNull() is NoSuchElementException)
        assertFalse(File(filesRoot, "compiled/${localImported.workId.raw}").exists())
        assertFalse(File(filesRoot, "books/${localImported.workId.raw}.epub").exists())
        assertTrue(File(filesRoot, "compiled/B0042").isDirectory)

        val reimported = vault.importLocalBook(epubFile.absolutePath).getOrThrow()
        assertEquals(WorkId("B0042"), reimported.workId)
    }

    @Test
    fun `local EPUB mergeTitlePages preserves HTML anchor IDs without off-by-one shift`() = runTest {
        val epubFile = buildFourSpineMergeEpub(tempFolder.newFile("merged_epub.epub"))
        val imported = vault.importLocalBook(epubFile.absolutePath).getOrThrow()

        val outline = vault.getWorkOutline(imported.workId)
        assertEquals(2, outline.bodyChapters.size)
        assertEquals(1, outline.notesChapters.size)
        assertTrue(outline.bodyChapters[0].merged)
        assertEquals(ChapterOrdinal(1), outline.bodyChapters[0].ordinal)
        assertEquals(ChapterOrdinal(2), outline.bodyChapters[1].ordinal)
        assertEquals(ChapterOrdinal(3), outline.notesChapters[0].ordinal)

        val fn = vault.resolveFootnote(imported.workId, ChapterOrdinal(2), "notes.xhtml#fn_after_merge")
        assertNotNull(fn)
        assertEquals(ChapterOrdinal(2), fn!!.link.sourceChapterOrdinal)
        assertEquals(ChapterOrdinal(3), fn.link.targetChapterOrdinal)
        assertTrue(fn.fullNoteText.contains("合并前置页后仍精准解析的脚注"))

        val ch02Html = File(filesRoot, "compiled/${imported.workId.raw}/OEBPS/ch_02.xhtml").readText()
        assertTrue(ch02Html.contains("href=\"ch_03.xhtml#${fn.link.targetBlockId.value}\""))
    }

    private fun assertUnsupportedFailure(result: Result<*>) {
        assertTrue("Expected failure result, got success", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("Expected AppFailureException, got $ex", ex is AppFailureException)
        assertTrue((ex as AppFailureException).failure is AppFailure.UnsupportedDrmOrFormat)
    }

    private fun buildRealisticBcBookZip(
        dest: File,
        bookSourceSha256Hex: String = "b".repeat(64),
    ): File {
        val files = linkedMapOf<String, String>()
        files["00_扉页.md"] = "# 导言\n\n题记。\n"
        files["01_前置页.md"] = "# 前置页\n\n" + "导言前置页说明文字，介绍版本来源与校勘体例。".repeat(20) + "\n"
        files["02_第一章.md"] = "# 第一章 雾夜\n\n雾夜码头人影稀疏。\n"
        files["03_第二章.md"] = "# 第二章 密信\n\n信封上有红蜡封缄\uE005，旁注公式 \$E = mc^2\$[^45]。\n"
        for (ord in 4..19) {
            val n = ord.toString().padStart(2, '0')
            files["${n}_正文.md"] = when (ord) {
                5 -> "# 第四章 码头\n\n码头旁有译者提示[^999]。\n"
                14 -> "# 第十三章 审讯\n\n笔录提及匿名线人[^102]。\n"
                else -> "# 第 ${ord - 1} 章\n\n第 $ord 章正文段落内容。\n"
            }
        }
        files["20_注释.md"] = buildString {
            appendLine("# 全书注释")
            appendLine()
            appendLine("[^45]: 此处“红蜡封缄”沿用十九世纪港口海关的加急函件规制。")
            appendLine()
            appendLine("[^102]: 审讯笔录中提及的匿名线人实为第七章失踪的账房先生（剧透尾注）。")
            appendLine()
            appendLine("[^999]: 译注：此处采用通行底本。")
        }

        val entries = mutableListOf<LogicalChapterEntry>()
        entries += LogicalChapterEntry(
            ordinal = ChapterOrdinal(1),
            n = "00",
            title = "导言",
            chapterRole = ChapterRole.FRONT_BACK,
            merged = true,
            sourceFiles = listOf("00_扉页.md", "01_前置页.md"),
            chapterContentSha256 = ChapterContentHash(
                sha256Hex(files["00_扉页.md"]!!.toByteArray() + byteArrayOf(0) + files["01_前置页.md"]!!.toByteArray()),
            ),
            chars = 650,
        )
        val restNames = files.keys.filter { it !in setOf("00_扉页.md", "01_前置页.md") }
        restNames.forEachIndexed { idx, name ->
            val ord = idx + 2
            val txt = files.getValue(name)
            entries += LogicalChapterEntry(
                ordinal = ChapterOrdinal(ord),
                n = ord.toString().padStart(2, '0'),
                title = if (ord == 20) "全书注释" else "第 $ord 节",
                chapterRole = if (ord == 20) ChapterRole.NOTES else ChapterRole.BODY,
                merged = false,
                sourceFiles = listOf(name),
                chapterContentSha256 = ChapterContentHash(sha256Hex(txt.toByteArray())),
                chars = txt.count { !it.isWhitespace() },
            )
        }

        val manifest = BcBookManifest(
            schemaVersion = 1,
            uid = WorkId("B0042"),
            title = "雾夜档案",
            author = "林某",
            subjects = listOf("文学", "推理"),
            readType = ReadProfileId("literature"),
            subKind = "mystery",
            bookSourceSha256 = BookSourceHash(bookSourceSha256Hex),
            clothColor = profileRegistry.clothColor("B0042"),
            logicalChapters = entries,
        )

        ZipOutputStream(FileOutputStream(dest)).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(json.encodeToString(BcBookManifest.serializer(), manifest).toByteArray())
            zos.closeEntry()

            for ((name, content) in files) {
                zos.putNextEntry(ZipEntry("md/$name"))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("images/diagram.png"))
            zos.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("companion_cache/ch_02/chapter_map.json"))
            zos.write("""{"title":"第二章知识图","sections":[]}""".toByteArray())
            zos.closeEntry()
        }
        return dest
    }

    private fun buildMinimalEpub(
        dest: File,
        title: String,
        author: String,
        subject: String,
        includeFontObfuscation: Boolean = false,
        includeDrmEncryption: Boolean = false,
    ): File {
        ZipOutputStream(FileOutputStream(dest)).use { zos ->
            zos.putNextEntry(ZipEntry("mimetype"))
            zos.write("application/epub+zip".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>""".trimIndent().toByteArray(),
            )
            zos.closeEntry()

            if (includeFontObfuscation || includeDrmEncryption) {
                val alg = if (includeDrmEncryption) {
                    "http://www.w3.org/2001/04/xmlenc#aes128-cbc"
                } else {
                    "http://www.idpf.org/2008/embedding"
                }
                zos.putNextEntry(ZipEntry("META-INF/encryption.xml"))
                zos.write(
                    """<?xml version="1.0" encoding="UTF-8"?>
                    <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#">
                        <EncryptionMethod Algorithm="$alg"/>
                      </EncryptedData>
                    </encryption>""".trimIndent().toByteArray(),
                )
                zos.closeEntry()
            }

            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>$title</dc:title>
                    <dc:creator>$author</dc:creator>
                    <dc:subject>$subject</dc:subject>
                  </metadata>
                  <manifest>
                    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="notes" href="notes.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="ch1"/>
                    <itemref idref="notes"/>
                  </spine>
                </package>""".trimIndent().toByteArray(),
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/ch1.xhtml"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                  <head><title>第一章 赋税改革</title></head>
                  <body>
                    <h1>第一章 赋税改革</h1>
                    <p>张居正推行新法<a epub:type="noteref" href="notes.xhtml#fn_1">1</a>。</p>
                  </body>
                </html>""".trimIndent().toByteArray(),
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/notes.xhtml"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                  <head><title>注释</title></head>
                  <body>
                    <h1>注释</h1>
                    <aside id="fn_1" epub:type="footnote"><p>即一条鞭法。</p></aside>
                  </body>
                </html>""".trimIndent().toByteArray(),
            )
            zos.closeEntry()
        }
        return dest
    }

    private fun buildMinimalMobi(
        dest: File,
        title: String,
        encryptionType: Int,
        bodyText: String,
    ): File {
        val textBytes = bodyText.toByteArray(Charsets.UTF_8)
        val titleBytes = title.toByteArray(Charsets.UTF_8)
        val r0Len = 132 + titleBytes.size
        val r0 = ByteBuffer.allocate(r0Len).order(ByteOrder.BIG_ENDIAN)
        r0.putShort(0, 1) // uncompressed
        r0.putShort(8, 1) // 1 text record
        r0.putShort(12, encryptionType.toShort())
        System.arraycopy("MOBI".toByteArray(Charsets.US_ASCII), 0, r0.array(), 16, 4)
        r0.putInt(20, 116) // mobi header len
        r0.putInt(28, 65001) // UTF-8
        r0.putInt(36, 6) // version 6
        r0.putInt(84, 132) // fullNameOffset
        r0.putInt(88, titleBytes.size)
        System.arraycopy(titleBytes, 0, r0.array(), 132, titleBytes.size)

        val numRecords = 2
        val headerSize = 78 + numRecords * 8
        val r0Offset = headerSize
        val r1Offset = r0Offset + r0Len
        val total = ByteBuffer.allocate(r1Offset + textBytes.size).order(ByteOrder.BIG_ENDIAN)
        val nameBytes = title.toByteArray(Charsets.UTF_8)
        System.arraycopy(nameBytes, 0, total.array(), 0, minOf(31, nameBytes.size))
        System.arraycopy("BOOKMOBI".toByteArray(Charsets.US_ASCII), 0, total.array(), 60, 8)
        total.putShort(76, numRecords.toShort())
        total.putInt(78, r0Offset)
        total.putInt(86, r1Offset)
        System.arraycopy(r0.array(), 0, total.array(), r0Offset, r0Len)
        System.arraycopy(textBytes, 0, total.array(), r1Offset, textBytes.size)

        dest.writeBytes(total.array())
        return dest
    }

    private fun buildCustomBcBookZip(
        dest: File,
        workId: String,
        sourceSha256Hex: String,
        chapters: List<Triple<String, ChapterRole, String>>,
    ): File {
        val entries = chapters.mapIndexed { idx, (fileName, role, md) ->
            val ord = idx + 1
            LogicalChapterEntry(
                ordinal = ChapterOrdinal(ord),
                n = ord.toString().padStart(2, '0'),
                title = md.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim().orEmpty(),
                chapterRole = role,
                merged = false,
                sourceFiles = listOf(fileName),
                chapterContentSha256 = ChapterContentHash(sha256Hex(md.toByteArray())),
                chars = md.count { !it.isWhitespace() },
            )
        }
        val manifest = BcBookManifest(
            schemaVersion = 1,
            uid = WorkId(workId),
            title = "自定义测试书",
            author = "测试者",
            subjects = listOf("历史"),
            readType = ReadProfileId("history"),
            subKind = "",
            bookSourceSha256 = BookSourceHash(sourceSha256Hex),
            clothColor = profileRegistry.clothColor(workId),
            logicalChapters = entries,
        )
        ZipOutputStream(FileOutputStream(dest)).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(json.encodeToString(BcBookManifest.serializer(), manifest).toByteArray())
            zos.closeEntry()
            for ((fileName, _, md) in chapters) {
                zos.putNextEntry(ZipEntry("md/$fileName"))
                zos.write(md.toByteArray())
                zos.closeEntry()
            }
        }
        return dest
    }

    private fun buildFourSpineMergeEpub(dest: File): File {
        val preBody = "这是合并前置页的正文说明，确保总字符数达到五百以上以触发标题页合并逻辑。".repeat(20)
        ZipOutputStream(FileOutputStream(dest)).use { zos ->
            zos.putNextEntry(ZipEntry("mimetype"))
            zos.write("application/epub+zip".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>""".trimIndent().toByteArray(),
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>合并锚点测试集</dc:title>
                    <dc:creator>测试作者</dc:creator>
                    <dc:subject>历史</dc:subject>
                  </metadata>
                  <manifest>
                    <item id="s0" href="cover.xhtml" media-type="application/xhtml+xml"/>
                    <item id="s1" href="前置页.xhtml" media-type="application/xhtml+xml"/>
                    <item id="s2" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="s3" href="notes.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="s0"/>
                    <itemref idref="s1"/>
                    <itemref idref="s2"/>
                    <itemref idref="s3"/>
                  </spine>
                </package>""".trimIndent().toByteArray(),
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/cover.xhtml"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>扉页</title></head>
                  <body><h1>扉页</h1><p>简短题记。</p></body>
                </html>""".trimIndent().toByteArray(),
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/前置页.xhtml"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>前置页</title></head>
                  <body><h1>前置页</h1><p>$preBody</p></body>
                </html>""".trimIndent().toByteArray(),
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/ch1.xhtml"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                  <head><title>第一章 正文</title></head>
                  <body>
                    <h1>第一章 正文</h1>
                    <p>正文引用合并后仍应保留的锚点<a epub:type="noteref" href="notes.xhtml#fn_after_merge">1</a>。</p>
                  </body>
                </html>""".trimIndent().toByteArray(),
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/notes.xhtml"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                  <head><title>注释</title></head>
                  <body>
                    <h1>注释</h1>
                    <aside id="fn_after_merge" epub:type="footnote"><p>这是合并前置页后仍精准解析的脚注。</p></aside>
                  </body>
                </html>""".trimIndent().toByteArray(),
            )
            zos.closeEntry()
        }
        return dest
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
