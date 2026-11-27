package app.banread.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import app.banread.library.LibraryVaultFactory
import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import app.banread.model.BcBookManifest
import app.banread.model.BlockId
import app.banread.model.BookRendererPort
import app.banread.model.BookSourceHash
import app.banread.model.ChapterContentHash
import app.banread.model.ChapterOrdinal
import app.banread.model.ChapterRole
import app.banread.model.DeviceId
import app.banread.model.LibraryVault
import app.banread.model.LogicalChapterEntry
import app.banread.model.NormalizedRect
import app.banread.model.NoteLogEntry
import app.banread.model.ReadProfileId
import app.banread.model.ReaderEngine
import app.banread.model.ReaderTheme
import app.banread.model.ReadingAnchor
import app.banread.model.ReadingSurfaceMode
import app.banread.model.SpoilerGate
import app.banread.model.TextQuote
import app.banread.model.TypographyProfile
import app.banread.model.WorkId
import app.banread.model.registry.ProfileRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderEngineContractTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var filesRoot: File
    private lateinit var vault: LibraryVault
    private lateinit var fontManager: FontManager
    private lateinit var fakeRenderer: FakeRendererAdapter
    private lateinit var readerEngine: ReaderEngine
    private val json = Json { encodeDefaults = true; prettyPrint = false }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        filesRoot = tempFolder.newFolder("banread_files")
        val profileRegistry = ProfileRegistry.loadFromClasspath()
        vault = LibraryVaultFactory.create(
            context = context,
            filesRoot = filesRoot,
            profileRegistry = profileRegistry,
            allowInMemory = true,
        )
        fontManager = ReaderEngineFactory.createFontManager(
            context = context,
            filesRoot = filesRoot,
            allowInMemory = true,
        )
        fakeRenderer = FakeRendererAdapter()
        readerEngine = ReaderEngineFactory.create(
            context = context,
            filesRoot = filesRoot,
            libraryVault = vault,
            rendererPort = fakeRenderer,
            deviceId = DeviceId("pixel-test-01"),
            allowInMemory = true,
            fontManager = fontManager,
        )
    }

    @Test
    fun `1_open ingested bcbook and epub initializes ReaderSessionHandle and SpoilerGate`() = runTest {
        val bcBook = buildMultiChapterBcBook(tempFolder.newFile("B0100.bcbook"), chapterCount = 12)
        val imported = vault.ingestStudyPack(bcBook.absolutePath).getOrThrow()

        val handle = readerEngine.open(imported.workId, ReadingSurfaceMode.REFLOWABLE)
        assertEquals(WorkId("B0100"), handle.workId)
        assertEquals(ReadingSurfaceMode.REFLOWABLE, handle.mode)
        assertEquals(ChapterOrdinal(2), handle.initialAnchor.ordinal) // ch 1 is FRONT_BACK, ch 2 is first BODY
        assertEquals(SpoilerGate.UpTo(ChapterOrdinal(2)), handle.spoilerGate)
        assertEquals("https://appassets.androidplatform.net/books/B0100/", fakeRenderer.loadedAssetRoot)

        // Also verify EPUB open
        val epubFile = buildMinimalEpub(tempFolder.newFile("sample.epub"))
        val importedEpub = vault.importLocalBook(epubFile.absolutePath).getOrThrow()
        val epubHandle = readerEngine.open(importedEpub.workId, ReadingSurfaceMode.REFLOWABLE)
        assertEquals(ReadingSurfaceMode.REFLOWABLE, epubHandle.mode)
        assertEquals(ChapterOrdinal(1), epubHandle.initialAnchor.ordinal)
        assertEquals(SpoilerGate.UpTo(ChapterOrdinal(1)), epubHandle.spoilerGate)
    }

    @Test
    fun `2_backward flip updates currentCursor without lowering spoilerWatermark`() = runTest {
        val bcBook = buildMultiChapterBcBook(tempFolder.newFile("B0101.bcbook"), chapterCount = 12)
        val imported = vault.ingestStudyPack(bcBook.absolutePath).getOrThrow()
        readerEngine.open(imported.workId, ReadingSurfaceMode.REFLOWABLE)

        // Advance sequentially from ch 2 -> ch 3 -> ch 4 -> ch 5
        for (ord in 3..5) {
            val anchor = vault.resolveAnchor(imported.workId, ChapterOrdinal(ord), BlockId("B01"))
            fakeRenderer.emitLocation(anchor)
        }
        assertEquals(ChapterOrdinal(5), readerEngine.activeState.value.spoilerWatermark)
        assertEquals(SpoilerGate.UpTo(ChapterOrdinal(5)), readerEngine.currentSpoilerGate(imported.workId))

        // Flip backward to chapter 2
        val ch2Anchor = vault.resolveAnchor(imported.workId, ChapterOrdinal(2), BlockId("B01"))
        fakeRenderer.emitLocation(ch2Anchor)

        val state = readerEngine.activeState.value
        assertEquals(ChapterOrdinal(2), state.currentCursor?.ordinal)
        assertEquals(ChapterOrdinal(5), state.spoilerWatermark)
        assertNull(state.pendingWatermarkJump)
        assertEquals(SpoilerGate.UpTo(ChapterOrdinal(5)), readerEngine.currentSpoilerGate(imported.workId))
    }

    @Test
    fun `3_forward TOC jump sets pendingWatermarkJump and keeps SpoilerGate locked until confirmed`() = runTest {
        val bcBook = buildMultiChapterBcBook(tempFolder.newFile("B0102.bcbook"), chapterCount = 12)
        val imported = vault.ingestStudyPack(bcBook.absolutePath).getOrThrow()
        readerEngine.open(imported.workId, ReadingSurfaceMode.REFLOWABLE)

        for (ord in 3..5) {
            fakeRenderer.emitLocation(vault.resolveAnchor(imported.workId, ChapterOrdinal(ord), BlockId("B01")))
        }
        assertEquals(ChapterOrdinal(5), readerEngine.activeState.value.spoilerWatermark)

        // TOC jump to ch 10 (> watermark 5 + 1)
        val ch10Anchor = vault.resolveAnchor(imported.workId, ChapterOrdinal(10), BlockId("B01"))
        readerEngine.goTo(ch10Anchor, highlightPulseMs = 500L)

        val jumpState = readerEngine.activeState.value
        assertEquals(ChapterOrdinal(10), jumpState.currentCursor?.ordinal)
        assertEquals(ChapterOrdinal(5), jumpState.spoilerWatermark)
        assertEquals(ChapterOrdinal(10), jumpState.pendingWatermarkJump)
        assertEquals(SpoilerGate.UpTo(ChapterOrdinal(5)), readerEngine.currentSpoilerGate(imported.workId))

        // Explicit user confirmation promotes watermark and clears pending jump
        readerEngine.confirmWatermarkAdvance(imported.workId, ChapterOrdinal(10))

        val confirmedState = readerEngine.activeState.value
        assertEquals(ChapterOrdinal(10), confirmedState.spoilerWatermark)
        assertNull(confirmedState.pendingWatermarkJump)
        assertEquals(SpoilerGate.UpTo(ChapterOrdinal(10)), readerEngine.currentSpoilerGate(imported.workId))
    }

    @Test
    fun `4_raiseWatermarkFromRemote raises watermark monotonically without clobbering currentCursor`() = runTest {
        val bcBook = buildMultiChapterBcBook(tempFolder.newFile("B0103.bcbook"), chapterCount = 12)
        val imported = vault.ingestStudyPack(bcBook.absolutePath).getOrThrow()
        readerEngine.open(imported.workId, ReadingSurfaceMode.REFLOWABLE)

        // Advance sequentially to ch 4, then jump to ch 9 (pending jump = 9)
        fakeRenderer.emitLocation(vault.resolveAnchor(imported.workId, ChapterOrdinal(3), BlockId("B01")))
        fakeRenderer.emitLocation(vault.resolveAnchor(imported.workId, ChapterOrdinal(4), BlockId("B01")))
        val ch9Anchor = vault.resolveAnchor(imported.workId, ChapterOrdinal(9), BlockId("B01"))
        readerEngine.goTo(ch9Anchor)
        assertEquals(ChapterOrdinal(9), readerEngine.activeState.value.pendingWatermarkJump)

        // Remote watermark is lower (ch 3) -> no-op
        readerEngine.raiseWatermarkFromRemote(imported.workId, ChapterOrdinal(3))
        assertEquals(ChapterOrdinal(4), readerEngine.activeState.value.spoilerWatermark)
        assertEquals(ChapterOrdinal(9), readerEngine.activeState.value.currentCursor?.ordinal)
        assertEquals(ChapterOrdinal(9), readerEngine.activeState.value.pendingWatermarkJump)

        // Remote watermark is higher (ch 11) -> raises to 11, keeps cursor at ch 9, clears stale pending jump <= 11
        readerEngine.raiseWatermarkFromRemote(imported.workId, ChapterOrdinal(11))
        val raisedState = readerEngine.activeState.value
        assertEquals(ChapterOrdinal(11), raisedState.spoilerWatermark)
        assertEquals(ChapterOrdinal(9), raisedState.currentCursor?.ordinal)
        assertNull(raisedState.pendingWatermarkJump)
        assertEquals(SpoilerGate.UpTo(ChapterOrdinal(11)), readerEngine.currentSpoilerGate(imported.workId))
    }

    @Test
    fun `5_saveAnnotation persists NoteLogEntry in Room and triggers renderDecorations`() = runTest {
        val bcBook = buildMultiChapterBcBook(tempFolder.newFile("B0104.bcbook"), chapterCount = 4)
        val imported = vault.ingestStudyPack(bcBook.absolutePath).getOrThrow()
        readerEngine.open(imported.workId, ReadingSurfaceMode.REFLOWABLE)

        val anchor = vault.resolveAnchor(imported.workId, ChapterOrdinal(3), BlockId("B01"))
        readerEngine.saveAnnotation(
            workId = imported.workId,
            anchor = anchor,
            colorToken = "WAVY_UNDERLINE:BLUE",
            comment = "关键伏笔段落",
        )

        val immediate = fakeRenderer.lastRenderedDecorations
        assertEquals(1, immediate.size)
        assertEquals(imported.workId, immediate[0].workId)
        assertEquals("WAVY_UNDERLINE:BLUE", immediate[0].colorToken)
        assertEquals("关键伏笔段落", immediate[0].comment)

        // Reopen on a fresh FakeRendererAdapter sharing the same Room DB -> persisted notes load from Room
        val freshRenderer = FakeRendererAdapter()
        val reopenedEngine = ReaderEngineFactory.create(
            context = context,
            filesRoot = filesRoot,
            libraryVault = vault,
            rendererPort = freshRenderer,
            allowInMemory = true,
            fontManager = fontManager,
        )
        reopenedEngine.open(imported.workId, ReadingSurfaceMode.REFLOWABLE)
        assertEquals(1, freshRenderer.lastRenderedDecorations.size)
        assertEquals("关键伏笔段落", freshRenderer.lastRenderedDecorations[0].comment)
    }

    @Test
    fun `6_typography themes eink no-animation palt-halt and user font injection`() = runTest {
        val fontFile = tempFolder.newFile("NotoCustom.ttf").apply {
            writeBytes(buildValidSyntheticTtf("BanReadSong"))
        }
        val registeredFont = fontManager.importFont(fontFile.absolutePath).getOrThrow()
        assertEquals("BanReadSong", registeredFont.displayName)
        assertTrue(registeredFont.assetUrl.startsWith("https://appassets.androidplatform.net/user-fonts/"))

        val bcBook = buildMultiChapterBcBook(tempFolder.newFile("B0105.bcbook"), chapterCount = 4)
        val imported = vault.ingestStudyPack(bcBook.absolutePath).getOrThrow()
        readerEngine.open(imported.workId, ReadingSurfaceMode.REFLOWABLE)

        readerEngine.applyTypography(
            TypographyProfile(
                fontFamily = registeredFont.cssFontFamily,
                fontSizeSp = 22f,
                lineHeightMultiplier = 1.8f,
                paragraphSpacingDp = 14f,
                horizontalMarginDp = 20f,
                squeezeChinesePunctuation = true,
            ),
        )

        for (theme in ReaderTheme.entries) {
            readerEngine.applyTheme(theme)
            val css = fakeRenderer.lastCssPayload
            val spec = ThemePalette.colorsFor(theme)
            assertTrue("Theme $theme missing bg ${spec.backgroundHex}", css.contains(spec.backgroundHex))
            assertTrue("Theme $theme missing fg ${spec.foregroundHex}", css.contains(spec.foregroundHex))
            assertTrue(css.contains("""font-family: "BanReadSong""""))
            assertTrue(css.contains("https://appassets.androidplatform.net/user-fonts/"))
            assertTrue(css.contains("text-spacing-trim: auto;"))
            assertTrue(css.contains(""""palt" 1, "halt" 1"""))
            assertTrue(css.contains("p:not(.no-indent)"))

            if (theme == ReaderTheme.EINK_HIGH_CONTRAST) {
                assertTrue(css.contains("transition: none !important;"))
                assertTrue(css.contains("animation: none !important;"))
                assertEquals(
                    PaginationMode.NONE,
                    ThemePalette.effectivePaginationMode(theme, PaginationMode.SLIDE),
                )
            } else {
                assertFalse(css.contains("transition: none !important;"))
            }
        }
    }

    @Test
    fun `7_sfnt validator accepts valid TTF and rejects garbage bytes`() = runTest {
        val validTtf = tempFolder.newFile("ValidFont.ttf").apply {
            writeBytes(buildValidSyntheticTtf("KaiTiTest"))
        }
        val ok = fontManager.importFont(validTtf.absolutePath)
        assertTrue(ok.isSuccess)
        assertEquals("KaiTiTest", ok.getOrThrow().displayName)
        assertEquals(1, fontManager.listFonts().size)

        val garbage = tempFolder.newFile("not_a_font.ttf").apply {
            writeBytes("HELLO_NOT_A_FONT_GARBAGE_BYTES_12345".toByteArray())
        }
        val bad = fontManager.importFont(garbage.absolutePath)
        assertTrue(bad.isFailure)
        val ex = bad.exceptionOrNull()
        assertTrue(ex is AppFailureException)
        assertTrue((ex as AppFailureException).failure is AppFailure.UnsupportedDrmOrFormat)
    }

    @Test
    fun `8_offline foliate-js es2020 assets exist and pass node syntax check`() {
        val assetNames = listOf(
            "reader/LICENSE-foliate-js.txt",
            "reader/reader_host.html",
            "reader/reader_bridge.js",
            "reader/foliate-bundle.js",
        )
        for (name in assetNames) {
            val content = context.assets.open(name).bufferedReader().use { it.readText() }
            assertTrue("Asset $name is empty", content.isNotBlank())
            assertFalse("Asset $name contains forbidden CDN link", content.contains("https://cdn"))
            assertFalse("Asset $name contains unpkg link", content.contains("unpkg.com"))
        }

        val bundleText = context.assets.open("reader/foliate-bundle.js").bufferedReader().use { it.readText() }
        assertTrue(bundleText.contains("foliate-view"))
        assertTrue(bundleText.contains("foliate-paginator"))

        val bridgeText = context.assets.open("reader/reader_bridge.js").bufferedReader().use { it.readText() }
        assertTrue(bridgeText.contains("BanReadBridge"))
        assertTrue(bridgeText.contains("BanReadNative"))

        // Run `node --check` on both bundled JS assets on disk
        val assetsDir = File("src/main/assets/reader")
        assumeTrue("src/main/assets/reader not found from CWD", assetsDir.isDirectory)
        for (jsName in listOf("foliate-bundle.js", "reader_bridge.js")) {
            val jsFile = File(assetsDir, jsName)
            assertTrue("Missing $jsFile", jsFile.isFile)
            val proc = ProcessBuilder("node", "--check", jsFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            assertEquals("node --check failed for $jsName: $out", 0, proc.waitFor())
        }
    }

    @Test
    fun `auxiliary public adapters - PdfiumPageAdapter bbox & overlays, VolumeKeyPager, FoliateWebAdapter compat`() = runTest {
        // 1. PdfiumPageAdapter auto-margin bbox detection & normalized overlay mapping
        val bmp = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val rectPixels = IntArray(120 * 180) { Color.BLACK }
        bmp.setPixels(rectPixels, 0, 120, 40, 60, 120, 180)

        val bbox = PdfiumPageAdapter.detectContentBoundingBox(bmp)
        assertTrue("Expected cropped left ~0.18, got ${bbox.left}", bbox.left in 0.15f..0.22f)
        assertTrue("Expected cropped top ~0.18, got ${bbox.top}", bbox.top in 0.15f..0.22f)
        assertTrue("Expected cropped right ~0.82, got ${bbox.right}", bbox.right in 0.78f..0.86f)
        assertTrue("Expected cropped bottom ~0.82, got ${bbox.bottom}", bbox.bottom in 0.78f..0.86f)

        val pdfAdapter = PdfiumPageAdapter()
        pdfAdapter.setZoomAndPan(scale = 2.5f, panX = 12f, panY = -8f)
        assertEquals(2.5f, pdfAdapter.viewportState.value.zoomScale, 0.001f)
        pdfAdapter.renderDecorations(
            listOf(
                NoteLogEntry(
                    noteId = "n1",
                    workId = WorkId("B0104"),
                    deviceId = DeviceId("d1"),
                    anchor = ReadingAnchor.PdfPage(
                        ordinal = ChapterOrdinal(1),
                        pageIndex = 0,
                        normalizedBoxes = listOf(NormalizedRect(0.1f, 0.2f, 0.5f, 0.25f)),
                    ),
                    colorToken = "YELLOW",
                    comment = "pdf note",
                    updatedAtEpochMs = 1L,
                ),
            ),
        )
        assertEquals(1, pdfAdapter.annotationsForPage(0).size)
        pdfAdapter.close()

        // 2. VolumeKeyPager gating
        var prevCount = 0
        var nextCount = 0
        val pager = VolumeKeyPager(
            isReaderActive = false,
            isEnabled = true,
            onPrevPage = { prevCount++ },
            onNextPage = { nextCount++ },
        )
        assertFalse(pager.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP))
        pager.isReaderActive = true
        assertTrue(pager.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP))
        assertTrue(pager.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertEquals(1, prevCount)
        assertEquals(1, nextCount)

        // 3. FoliateWebAdapter WebViewCompat graceful probe
        val webAdapter = FoliateWebAdapter(context, filesRoot)
        val pkgStatus = webAdapter.checkWebViewPackage()
        assertNotNull(pkgStatus)
    }

    private class FakeRendererAdapter : BookRendererPort {
        private val _locationEvents = MutableSharedFlow<ReadingAnchor>(extraBufferCapacity = 32)
        override val locationEvents: Flow<ReadingAnchor> = _locationEvents.asSharedFlow()

        var loadedAssetRoot: String? = null
        var lastNavigatedAnchor: ReadingAnchor? = null
        var lastCssPayload: String = ""
        var lastRenderedDecorations: List<NoteLogEntry> = emptyList()

        override suspend fun load(compiledAssetRoot: String, initialAnchor: ReadingAnchor?) {
            loadedAssetRoot = compiledAssetRoot
            lastNavigatedAnchor = initialAnchor
        }

        override suspend fun navigate(anchor: ReadingAnchor) {
            lastNavigatedAnchor = anchor
        }

        override suspend fun updateStyles(cssPayload: String) {
            lastCssPayload = cssPayload
        }

        override suspend fun renderDecorations(annotations: List<NoteLogEntry>) {
            lastRenderedDecorations = annotations
        }

        fun emitLocation(anchor: ReadingAnchor) {
            check(_locationEvents.tryEmit(anchor)) { "Failed to emit location anchor" }
        }
    }

    private fun buildValidSyntheticTtf(familyName: String): ByteArray {
        val nameBytes = familyName.toByteArray(Charsets.UTF_16BE)
        val stringOffset = 6 + 12 // format(2) + count(2) + stringOffset(2) + 1 NameRecord(12)
        val nameTable = ByteBuffer.allocate(stringOffset + nameBytes.size).order(ByteOrder.BIG_ENDIAN)
        nameTable.putShort(0) // format
        nameTable.putShort(1) // count
        nameTable.putShort(stringOffset.toShort())
        // NameRecord: platformID=3 (Windows), encodingID=1, lang=0x0409, nameID=1 (Family)
        nameTable.putShort(3)
        nameTable.putShort(1)
        nameTable.putShort(0x0409)
        nameTable.putShort(1)
        nameTable.putShort(nameBytes.size.toShort())
        nameTable.putShort(0)
        nameTable.put(nameBytes)

        val nameBytesFull = nameTable.array()
        val headerSize = 12 + 16 // offset table + 1 table record
        val total = ByteBuffer.allocate(headerSize + nameBytesFull.size).order(ByteOrder.BIG_ENDIAN)
        total.putInt(0x00010000) // sfntVersion TrueType
        total.putShort(1) // numTables
        total.putShort(16) // searchRange
        total.putShort(0) // entrySelector
        total.putShort(0) // rangeShift
        // Table record for 'name'
        total.put("name".toByteArray(Charsets.US_ASCII))
        total.putInt(0) // checksum
        total.putInt(headerSize) // offset
        total.putInt(nameBytesFull.size) // length
        total.put(nameBytesFull)
        return total.array()
    }

    private fun buildMultiChapterBcBook(dest: File, chapterCount: Int): File {
        val uid = dest.nameWithoutExtension
        val entries = mutableListOf<LogicalChapterEntry>()
        val mdFiles = linkedMapOf<String, String>()

        for (ord in 1..chapterCount) {
            val n = ord.toString().padStart(2, '0')
            val fileName = "${n}_ch.md"
            val role = if (ord == 1) ChapterRole.FRONT_BACK else ChapterRole.BODY
            val title = if (ord == 1) "导言" else "第 $ord 章"
            val body = "# $title\n\n第 $ord 章第一段测试正文内容。\n\n第 $ord 章第二段测试正文内容。\n"
            mdFiles[fileName] = body
            entries += LogicalChapterEntry(
                ordinal = ChapterOrdinal(ord),
                n = n,
                title = title,
                chapterRole = role,
                merged = false,
                sourceFiles = listOf(fileName),
                chapterContentSha256 = ChapterContentHash(sha256Hex(body.toByteArray())),
                chars = body.count { !it.isWhitespace() },
            )
        }

        val manifest = BcBookManifest(
            schemaVersion = 1,
            uid = WorkId(uid),
            title = "读者引擎测试书 $uid",
            author = "测试作者",
            subjects = listOf("文学"),
            readType = ReadProfileId("literature"),
            subKind = "",
            bookSourceSha256 = BookSourceHash(sha256Hex(uid.toByteArray())),
            clothColor = "#3B5998",
            logicalChapters = entries,
        )

        ZipOutputStream(FileOutputStream(dest)).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(json.encodeToString(BcBookManifest.serializer(), manifest).toByteArray())
            zos.closeEntry()
            for ((name, content) in mdFiles) {
                zos.putNextEntry(ZipEntry("md/$name"))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return dest
    }

    private fun buildMinimalEpub(dest: File): File {
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
                    <dc:title>EPUB 样本</dc:title>
                    <dc:creator>作者甲</dc:creator>
                  </metadata>
                  <manifest>
                    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="ch1"/></spine>
                </package>""".trimIndent().toByteArray(),
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/ch1.xhtml"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>第一章</title></head>
                  <body><h1>第一章</h1><p>EPUB 测试正文。</p></body>
                </html>""".trimIndent().toByteArray(),
            )
            zos.closeEntry()
        }
        return dest
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
