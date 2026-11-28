package app.banread.library

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.banread.library.internal.ImageHeaderInspector
import app.banread.library.internal.LibraryDatabase
import app.banread.library.internal.PdfCoverRenderer
import app.banread.model.BcBookManifest
import app.banread.model.BookSourceHash
import app.banread.model.ChapterContentHash
import app.banread.model.ChapterOrdinal
import app.banread.model.ChapterRole
import app.banread.model.CoverDisplayMode
import app.banread.model.LibraryVault
import app.banread.model.LogicalChapterEntry
import app.banread.model.ReadProfileId
import app.banread.model.WorkId
import app.banread.model.registry.ProfileRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
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
class LibraryVaultCoverAndBookshelfTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var filesRoot: File
    private lateinit var profileRegistry: ProfileRegistry
    private var vault: LibraryVault? = null
    private val json = Json { encodeDefaults = true }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        filesRoot = tempFolder.newFolder("vault_cover_files")
        profileRegistry = ProfileRegistry.loadFromClasspath()
    }

    @After
    fun tearDown() {
        (vault as? Closeable)?.close()
    }

    private fun createVault(pdfCoverRenderer: PdfCoverRenderer? = null): LibraryVault {
        val created = if (pdfCoverRenderer != null) {
            LibraryVaultFactory.createForTest(
                context = context,
                filesRoot = filesRoot,
                profileRegistry = profileRegistry,
                allowInMemory = true,
                pdfCoverRenderer = pdfCoverRenderer,
            )
        } else {
            LibraryVaultFactory.create(
                context = context,
                filesRoot = filesRoot,
                profileRegistry = profileRegistry,
                allowInMemory = true,
            )
        }
        vault = created
        return created
    }

    @Test
    fun `ImageHeaderInspector parses PNG, JPEG SOF0 and SOF2, WebP VP8 VP8L VP8X, and GIF and rejects corrupt data`() {
        val png = buildPngBytes(240, 360)
        val jpgSof0 = buildJpegBytes(800, 500, sofMarker = 0xC0)
        val jpgSof2 = buildJpegBytes(600, 900, sofMarker = 0xC2)
        val webpVp8 = buildWebpVp8Bytes(320, 480)
        val webpVp8l = buildWebpVp8lBytes(512, 512)
        val webpVp8x = buildWebpVp8xBytes(1024, 768)
        val gif = buildGifBytes(400, 600)

        assertEquals("png", ImageHeaderInspector.inspect(png)?.format)
        assertEquals(240 to 360, ImageHeaderInspector.inspect(png)?.let { it.width to it.height })
        assertEquals("jpg" to (800 to 500), ImageHeaderInspector.inspect(jpgSof0)?.let { it.format to (it.width to it.height) })
        assertEquals("jpg" to (600 to 900), ImageHeaderInspector.inspect(jpgSof2)?.let { it.format to (it.width to it.height) })
        assertEquals("webp" to (320 to 480), ImageHeaderInspector.inspect(webpVp8)?.let { it.format to (it.width to it.height) })
        assertEquals("webp" to (512 to 512), ImageHeaderInspector.inspect(webpVp8l)?.let { it.format to (it.width to it.height) })
        assertEquals("webp" to (1024 to 768), ImageHeaderInspector.inspect(webpVp8x)?.let { it.format to (it.width to it.height) })
        assertEquals("gif" to (400 to 600), ImageHeaderInspector.inspect(gif)?.let { it.format to (it.width to it.height) })

        assertNull(ImageHeaderInspector.inspect(byteArrayOf()))
        assertNull(ImageHeaderInspector.inspect("not an image".toByteArray()))
        assertNull(ImageHeaderInspector.inspect(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
        assertNull(ImageHeaderInspector.inspect(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())))
    }

    @Test
    fun `EPUB3 cover-image portrait cover extracts real dimensions and classifies as PORTRAIT_CROP`() = runTest {
        val v = createVault()
        val coverPng = buildPngBytes(240, 360)
        val epub = buildEpubWithCover(
            dest = tempFolder.newFile("epub3_portrait.epub"),
            manifestItemsXml = """
                <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="cov" href="images/cover.png" media-type="image/png" properties="cover-image"/>
            """.trimIndent(),
            coverEntryPath = "OEBPS/images/cover.png",
            coverBytes = coverPng,
        )

        val imported = v.importLocalBook(epub.absolutePath).getOrThrow()
        assertEquals("cover.png", imported.coverRel)
        assertEquals(240, imported.coverWidth)
        assertEquals(360, imported.coverHeight)
        assertEquals(CoverDisplayMode.PORTRAIT_CROP, imported.coverDisplayMode)

        val summary = v.getWorkSummary(imported.workId)!!
        assertEquals(CoverDisplayMode.PORTRAIT_CROP, summary.coverDisplayMode)
        val coverFile = v.resolveCoverFile(imported.workId)
        assertNotNull(coverFile)
        assertTrue(coverFile!!.isFile)
        assertTrue(coverFile.readBytes().contentEquals(coverPng))
    }

    @Test
    fun `EPUB2 meta cover wide or square image classifies as WIDE_PAPER_MAT`() = runTest {
        val v = createVault()
        val squareJpg = buildJpegBytes(400, 400, sofMarker = 0xC0)
        val epub = buildEpubWithCover(
            dest = tempFolder.newFile("epub2_square.epub"),
            extraMetadataXml = """<meta name="cover" content="cover-img"/>""",
            manifestItemsXml = """
                <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="cover-img" href="images/hero.jpg" media-type="image/jpeg"/>
            """.trimIndent(),
            coverEntryPath = "OEBPS/images/hero.jpg",
            coverBytes = squareJpg,
        )

        val imported = v.importLocalBook(epub.absolutePath).getOrThrow()
        assertEquals("cover.jpg", imported.coverRel)
        assertEquals(400, imported.coverWidth)
        assertEquals(400, imported.coverHeight)
        assertEquals(CoverDisplayMode.WIDE_PAPER_MAT, imported.coverDisplayMode)
    }

    @Test
    fun `EPUB with no cover, corrupt cover, or zip-slip cover path imports cleanly as CLOTH_FALLBACK`() = runTest {
        val v = createVault()

        val noCover = buildEpubWithCover(
            dest = tempFolder.newFile("no_cover.epub"),
            manifestItemsXml = """<item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>""",
        )
        val importedNoCover = v.importLocalBook(noCover.absolutePath).getOrThrow()
        assertNull(importedNoCover.coverRel)
        assertEquals(CoverDisplayMode.CLOTH_FALLBACK, importedNoCover.coverDisplayMode)
        assertNull(v.resolveCoverFile(importedNoCover.workId))

        val corruptCover = buildEpubWithCover(
            dest = tempFolder.newFile("corrupt_cover.epub"),
            manifestItemsXml = """
                <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="cov" href="images/bad.png" media-type="image/png" properties="cover-image"/>
            """.trimIndent(),
            coverEntryPath = "OEBPS/images/bad.png",
            coverBytes = "corrupted-not-a-png".toByteArray(),
        )
        val importedCorrupt = v.importLocalBook(corruptCover.absolutePath).getOrThrow()
        assertNull(importedCorrupt.coverRel)
        assertEquals(CoverDisplayMode.CLOTH_FALLBACK, importedCorrupt.coverDisplayMode)

        val zipSlipCover = buildEpubWithCover(
            dest = tempFolder.newFile("zipslip_cover.epub"),
            manifestItemsXml = """
                <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="cov" href="../../outside_secret.png" media-type="image/png" properties="cover-image"/>
            """.trimIndent(),
            coverEntryPath = "OEBPS/images/real.png",
            coverBytes = buildPngBytes(240, 360),
        )
        val importedZipSlip = v.importLocalBook(zipSlipCover.absolutePath).getOrThrow()
        assertNull(importedZipSlip.coverRel)
        assertEquals(CoverDisplayMode.CLOTH_FALLBACK, importedZipSlip.coverDisplayMode)
        assertFalse(File(filesRoot, "outside_secret.png").exists())
    }

    @Test
    fun `EPUB nested OPF relative dotdot and percent-encoded cover path extracts while root escape still blocks`() = runTest {
        val v = createVault()
        val coverPng = buildPngBytes(300, 450)
        val nestedEpub = buildNestedOpfEpub(
            dest = tempFolder.newFile("nested_relative_cover.epub"),
            opfZipPath = "OEBPS/Text/content.opf",
            manifestItemsXml = """
                <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="cov" href="../Images/my%20cover.png" media-type="image/png" properties="cover-image"/>
            """.trimIndent(),
            chapterZipPath = "OEBPS/Text/ch1.xhtml",
            coverEntryPath = "OEBPS/Images/my cover.png",
            coverBytes = coverPng,
        )
        val importedNested = v.importLocalBook(nestedEpub.absolutePath).getOrThrow()
        assertEquals("cover.png", importedNested.coverRel)
        assertEquals(300, importedNested.coverWidth)
        assertEquals(450, importedNested.coverHeight)
        assertEquals(CoverDisplayMode.PORTRAIT_CROP, importedNested.coverDisplayMode)
        val coverFile = v.resolveCoverFile(importedNested.workId)
        assertNotNull(coverFile)
        assertTrue(coverFile!!.readBytes().contentEquals(coverPng))

        val escapeEpub = buildNestedOpfEpub(
            dest = tempFolder.newFile("nested_escape_cover.epub"),
            opfZipPath = "OEBPS/Text/content.opf",
            manifestItemsXml = """
                <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="cov" href="%2e%2e/%2e%2e/%2e%2e/outside_secret.png" media-type="image/png" properties="cover-image"/>
            """.trimIndent(),
            chapterZipPath = "OEBPS/Text/ch1.xhtml",
            coverEntryPath = "OEBPS/Images/my cover.png",
            coverBytes = coverPng,
        )
        val importedEscape = v.importLocalBook(escapeEpub.absolutePath).getOrThrow()
        assertNull(importedEscape.coverRel)
        assertEquals(CoverDisplayMode.CLOTH_FALLBACK, importedEscape.coverDisplayMode)
        assertFalse(File(filesRoot, "outside_secret.png").exists())
    }

    @Test
    fun `PDF page-0 cover extraction persists cover via injectable PdfCoverRenderer and falls back cleanly on null`() = runTest {
        val fakeRenderer = PdfCoverRenderer { _, _ -> buildPngBytes(480, 720) }
        val vWithCover = createVault(pdfCoverRenderer = fakeRenderer)

        val pdf1 = tempFolder.newFile("geometry.pdf").apply {
            writeText("%PDF-1.4\n1 0 obj << /Font << /F1 2 0 R >> >> endobj\nBT /F1 12 Tf (Euclid) Tj ET\n%%EOF")
        }
        val imported1 = vWithCover.importLocalBook(pdf1.absolutePath).getOrThrow()
        assertEquals("cover.png", imported1.coverRel)
        assertEquals(480, imported1.coverWidth)
        assertEquals(720, imported1.coverHeight)
        assertEquals(CoverDisplayMode.PORTRAIT_CROP, imported1.coverDisplayMode)
        val resolved = vWithCover.resolveCoverFile(imported1.workId)
        assertNotNull(resolved)
        assertTrue(resolved!!.isFile)
        (vWithCover as Closeable).close()

        val nullRenderer = PdfCoverRenderer { _, _ -> null }
        val vNoCover = createVault(pdfCoverRenderer = nullRenderer)
        val pdf2 = tempFolder.newFile("encrypted_or_headless.pdf").apply {
            writeText("%PDF-1.4\n1 0 obj << /Subtype /Image >> endobj\n%%EOF")
        }
        val imported2 = vNoCover.importLocalBook(pdf2.absolutePath).getOrThrow()
        assertNull(imported2.coverRel)
        assertEquals(CoverDisplayMode.CLOTH_FALLBACK, imported2.coverDisplayMode)
        assertNull(vNoCover.resolveCoverFile(imported2.workId))
    }

    @Test
    fun `bcbook cover validation, bookshelf queries, observeWorks, and deleteWork remove DB and sandbox files`() = runTest {
        val v = createVault()
        val chMd = "# 第一章\n\n测试正文。"
        val manifest = BcBookManifest(
            schemaVersion = 1,
            uid = WorkId("B5001"),
            title = "书架测试书",
            author = "作者",
            subjects = listOf("历史"),
            readType = ReadProfileId("history"),
            bookSourceSha256 = BookSourceHash(sha256Hex("B5001".toByteArray())),
            clothColor = profileRegistry.clothColor("B5001"),
            coverRel = "images/cover.webp",
            logicalChapters = listOf(
                LogicalChapterEntry(
                    ordinal = ChapterOrdinal(1),
                    n = "01",
                    title = "第一章",
                    chapterRole = ChapterRole.BODY,
                    sourceFiles = listOf("01.md"),
                    chapterContentSha256 = ChapterContentHash(sha256Hex(chMd.toByteArray())),
                    chars = 6,
                ),
            ),
        )
        val bcZip = tempFolder.newFile("B5001.bcbook")
        ZipOutputStream(FileOutputStream(bcZip)).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(json.encodeToString(BcBookManifest.serializer(), manifest).toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("md/01.md"))
            zos.write(chMd.toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("images/cover.webp"))
            zos.write(buildWebpVp8xBytes(600, 300))
            zos.closeEntry()
        }

        val imported = v.ingestStudyPack(bcZip.absolutePath).getOrThrow()
        assertEquals("images/cover.webp", imported.coverRel)
        assertEquals(600, imported.coverWidth)
        assertEquals(300, imported.coverHeight)
        assertEquals(CoverDisplayMode.WIDE_PAPER_MAT, imported.coverDisplayMode)

        val listed = v.listWorks()
        assertEquals(1, listed.size)
        assertEquals(WorkId("B5001"), listed.single().workId)
        assertNotNull(listed.single().coverFilePath)

        val observed = v.observeWorks().first()
        assertEquals(1, observed.size)
        assertEquals(CoverDisplayMode.WIDE_PAPER_MAT, observed.single().coverDisplayMode)

        val sandboxCover = v.resolveCoverFile(WorkId("B5001"))!!
        assertTrue(sandboxCover.isFile)
        assertTrue(File(filesRoot, "compiled/B5001").isDirectory)
        assertTrue(File(filesRoot, "books/B5001.bcbook").isFile)

        val delRes = v.deleteWork(WorkId("B5001"))
        assertTrue(delRes.isSuccess)
        assertTrue(v.listWorks().isEmpty())
        assertNull(v.getWorkSummary(WorkId("B5001")))
        assertNull(v.resolveCoverFile(WorkId("B5001")))
        assertFalse(sandboxCover.exists())
        assertFalse(File(filesRoot, "works/B5001").exists())
        assertFalse(File(filesRoot, "compiled/B5001").exists())
        assertFalse(File(filesRoot, "books/B5001.bcbook").exists())
    }

    @Test
    fun `LibraryDatabase MIGRATION_1_2 upgrades existing v1 database without crashing`() {
        val dbName = "test_mig_v1_v2.db"
        context.deleteDatabase(dbName)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `works` (
                                `workId` TEXT NOT NULL, `bookSourceSha256` TEXT NOT NULL, `title` TEXT NOT NULL,
                                `author` TEXT NOT NULL, `subjectsJson` TEXT NOT NULL, `readProfileId` TEXT NOT NULL,
                                `literatureSubKind` TEXT, `profileSource` TEXT NOT NULL, `clothColor` TEXT NOT NULL,
                                `coverRel` TEXT, `originalFilePath` TEXT NOT NULL, `compiledRootPath` TEXT NOT NULL,
                                `format` TEXT NOT NULL, `supportedModesJson` TEXT NOT NULL, `isScannedPdf` INTEGER NOT NULL,
                                `blockVersion` INTEGER NOT NULL, `canonicalVersion` INTEGER NOT NULL, `addedAtMs` INTEGER NOT NULL,
                                PRIMARY KEY(`workId`)
                            )
                            """.trimIndent(),
                        )
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_works_bookSourceSha256` ON `works` (`bookSourceSha256`)")
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `chapters` (
                                `workId` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `n` TEXT NOT NULL, `title` TEXT NOT NULL,
                                `chapterRole` TEXT NOT NULL, `merged` INTEGER NOT NULL, `sourceFilesJson` TEXT NOT NULL,
                                `chapterContentSha256` TEXT NOT NULL, `chars` INTEGER NOT NULL, `blockCount` INTEGER NOT NULL,
                                `compiledHref` TEXT NOT NULL, `virtualSplitParentOrdinal` INTEGER,
                                PRIMARY KEY(`workId`, `ordinal`)
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `chapter_blocks` (
                                `workId` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `blockId` TEXT NOT NULL,
                                `blockIndex` INTEGER NOT NULL, `kind` TEXT NOT NULL, `headingPath` TEXT NOT NULL,
                                `cleanText` TEXT NOT NULL, `charStart` INTEGER NOT NULL, `charEnd` INTEGER NOT NULL,
                                `locatorJson` TEXT NOT NULL, PRIMARY KEY(`workId`, `ordinal`, `blockId`)
                            )
                            """.trimIndent(),
                        )
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chapter_blocks_workId_ordinal_blockIndex` ON `chapter_blocks` (`workId`, `ordinal`, `blockIndex`)")
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `footnotes` (
                                `workId` TEXT NOT NULL, `footnoteId` TEXT NOT NULL, `sourceChapterOrdinal` INTEGER NOT NULL,
                                `sourceBlockId` TEXT NOT NULL, `marker` TEXT NOT NULL, `targetChapterOrdinal` INTEGER NOT NULL,
                                `targetBlockId` TEXT NOT NULL, `noteText` TEXT NOT NULL, `isTranslatorOrBib` INTEGER NOT NULL,
                                PRIMARY KEY(`workId`, `footnoteId`)
                            )
                            """.trimIndent(),
                        )
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_footnotes_workId_sourceChapterOrdinal` ON `footnotes` (`workId`, `sourceChapterOrdinal`)")
                        db.execSQL(
                            """
                            INSERT INTO works VALUES (
                                'W1', '${"a".repeat(64)}', 'Legacy Book', 'Author', '[]', 'social', NULL, 'AUTO',
                                '#1E3A36', NULL, '/tmp/a.epub', '/tmp/c', 'EPUB', '["REFLOWABLE"]', 0, 2, 1, 1000
                            )
                            """.trimIndent(),
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                },
            )
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase.close()

        val migratedDb = Room.databaseBuilder(context, LibraryDatabase::class.java, dbName)
            .addMigrations(LibraryDatabase.MIGRATION_1_2, LibraryDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val rows = kotlinx.coroutines.runBlocking { migratedDb.libraryDao().listAllWorks() }
            assertEquals(1, rows.size)
            assertEquals("Legacy Book", rows.single().title)
            assertNull(rows.single().coverWidth)
            assertNull(rows.single().coverHeight)
        } finally {
            migratedDb.close()
            context.deleteDatabase(dbName)
        }
    }

    private fun buildEpubWithCover(
        dest: File,
        manifestItemsXml: String,
        extraMetadataXml: String = "",
        coverEntryPath: String? = null,
        coverBytes: ByteArray? = null,
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

            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>${dest.nameWithoutExtension}</dc:title>
                    <dc:creator>测试作者</dc:creator>
                    <dc:subject>历史</dc:subject>
                    $extraMetadataXml
                  </metadata>
                  <manifest>
                    $manifestItemsXml
                  </manifest>
                  <spine>
                    <itemref idref="ch1"/>
                  </spine>
                </package>""".trimIndent().toByteArray(),
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/ch1.xhtml"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>第一章</title></head>
                  <body><h1>第一章</h1><p>正文段落。</p></body>
                </html>""".trimIndent().toByteArray(),
            )
            zos.closeEntry()

            if (coverEntryPath != null && coverBytes != null) {
                zos.putNextEntry(ZipEntry(coverEntryPath))
                zos.write(coverBytes)
                zos.closeEntry()
            }
        }
        return dest
    }

    private fun buildNestedOpfEpub(
        dest: File,
        opfZipPath: String,
        manifestItemsXml: String,
        chapterZipPath: String,
        coverEntryPath: String,
        coverBytes: ByteArray,
    ): File {
        ZipOutputStream(FileOutputStream(dest)).use { zos ->
            zos.putNextEntry(ZipEntry("mimetype"))
            zos.write("application/epub+zip".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="$opfZipPath" media-type="application/oebps-package+xml"/></rootfiles>
                </container>""".trimIndent().toByteArray(),
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry(opfZipPath))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>${dest.nameWithoutExtension}</dc:title>
                    <dc:creator>测试作者</dc:creator>
                    <dc:subject>历史</dc:subject>
                  </metadata>
                  <manifest>
                    $manifestItemsXml
                  </manifest>
                  <spine>
                    <itemref idref="ch1"/>
                  </spine>
                </package>""".trimIndent().toByteArray(),
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry(chapterZipPath))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>第一章</title></head>
                  <body><h1>第一章</h1><p>正文段落。</p></body>
                </html>""".trimIndent().toByteArray(),
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry(coverEntryPath))
            zos.write(coverBytes)
            zos.closeEntry()
        }
        return dest
    }

    private fun buildPngBytes(width: Int, height: Int): ByteArray {
        val out = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
        out.put(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        out.putInt(13) // IHDR length
        out.put("IHDR".toByteArray(Charsets.US_ASCII))
        out.putInt(width)
        out.putInt(height)
        return out.array()
    }

    private fun buildJpegBytes(width: Int, height: Int, sofMarker: Int = 0xC0): ByteArray {
        val baos = ByteArrayOutputStream()
        // SOI
        baos.write(0xFF)
        baos.write(0xD8)
        // APP0 dummy segment (length = 6)
        baos.write(0xFF)
        baos.write(0xE0)
        baos.write(0x00)
        baos.write(0x06)
        baos.write(byteArrayOf(0x4A, 0x46, 0x49, 0x46))
        // SOFn segment (length = 11)
        baos.write(0xFF)
        baos.write(sofMarker)
        baos.write(0x00)
        baos.write(0x0B)
        baos.write(8) // precision
        baos.write((height ushr 8) and 0xFF)
        baos.write(height and 0xFF)
        baos.write((width ushr 8) and 0xFF)
        baos.write(width and 0xFF)
        baos.write(byteArrayOf(3, 1, 0x11, 0))
        // EOI
        baos.write(0xFF)
        baos.write(0xD9)
        return baos.toByteArray()
    }

    private fun buildGifBytes(width: Int, height: Int): ByteArray {
        val buf = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("GIF89a".toByteArray(Charsets.US_ASCII))
        buf.putShort(width.toShort())
        buf.putShort(height.toShort())
        return buf.array()
    }

    private fun buildWebpVp8Bytes(width: Int, height: Int): ByteArray {
        val buf = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(22)
        buf.put("WEBP".toByteArray(Charsets.US_ASCII))
        buf.put("VP8 ".toByteArray(Charsets.US_ASCII))
        buf.putInt(10)
        buf.put(byteArrayOf(0, 0, 0)) // frame tag
        buf.put(byteArrayOf(0x9D.toByte(), 0x01, 0x2A)) // sync code
        buf.putShort((width and 0x3FFF).toShort())
        buf.putShort((height and 0x3FFF).toShort())
        return buf.array()
    }

    private fun buildWebpVp8lBytes(width: Int, height: Int): ByteArray {
        val buf = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(17)
        buf.put("WEBP".toByteArray(Charsets.US_ASCII))
        buf.put("VP8L".toByteArray(Charsets.US_ASCII))
        buf.putInt(5)
        buf.put(0x2F.toByte())
        val packed = ((width - 1) and 0x3FFF) or (((height - 1) and 0x3FFF) shl 14)
        buf.putInt(packed)
        return buf.array()
    }

    private fun buildWebpVp8xBytes(width: Int, height: Int): ByteArray {
        val buf = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(22)
        buf.put("WEBP".toByteArray(Charsets.US_ASCII))
        buf.put("VP8X".toByteArray(Charsets.US_ASCII))
        buf.putInt(10)
        buf.putInt(0) // flags
        val w = width - 1
        val h = height - 1
        buf.put((w and 0xFF).toByte())
        buf.put(((w ushr 8) and 0xFF).toByte())
        buf.put(((w ushr 16) and 0xFF).toByte())
        buf.put((h and 0xFF).toByte())
        buf.put(((h ushr 8) and 0xFF).toByte())
        buf.put(((h ushr 16) and 0xFF).toByte())
        return buf.array()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
