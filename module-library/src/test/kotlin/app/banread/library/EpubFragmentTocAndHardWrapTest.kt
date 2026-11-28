package app.banread.library

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.banread.library.internal.LibraryDatabase
import app.banread.model.BlockId
import app.banread.model.ChapterOrdinal
import app.banread.model.ChapterRole
import app.banread.model.LibraryVault
import app.banread.model.WorkId
import app.banread.model.registry.ProfileRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpubFragmentTocAndHardWrapTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var filesRoot: File
    private lateinit var profileRegistry: ProfileRegistry
    private var vault: LibraryVault? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        filesRoot = tempFolder.newFolder("epub_fix_files")
        profileRegistry = ProfileRegistry.loadFromClasspath()
    }

    @After
    fun tearDown() {
        (vault as? Closeable)?.close()
    }

    private fun createVault(allowInMemory: Boolean = true): LibraryVault {
        val created = LibraryVaultFactory.create(
            context = context,
            filesRoot = filesRoot,
            profileRegistry = profileRegistry,
            allowInMemory = allowInMemory,
        )
        vault = created
        return created
    }

    @Test
    fun `toc ncx multi-fragment anchors split large spine HTML into real chapters and preserve seam continuations`() = runTest {
        val v = createVault()
        val epub = buildSyntheticFragmentNcxEpub(tempFolder.newFile("fragment_ncx.epub"))
        val imported = v.importLocalBook(epub.absolutePath).getOrThrow()

        val outline = v.getWorkOutline(imported.workId)
        val frontTitles = outline.frontBackChapters.map { it.title }
        val bodyTitles = outline.bodyChapters.map { it.title }
        val noteTitles = outline.notesChapters.map { it.title }

        assertTrue("Expected copyright in frontBack: front=$frontTitles body=$bodyTitles notes=$noteTitles", "版权信息" in frontTitles)
        assertTrue("Expected praise in frontBack: front=$frontTitles body=$bodyTitles notes=$noteTitles", "赞誉" in frontTitles)
        assertTrue("Expected Document Outline in frontBack: front=$frontTitles body=$bodyTitles notes=$noteTitles", "Document Outline" in frontTitles)
        assertEquals(
            listOf(
                "前言 对模拟时代的回顾",
                "第一章 起始阶段",
                "第一节 早期生活",
                "第二节 转型时期",
                "第二章 跨文件章节",
                "第一节 城市变迁",
                "附录 案例汇编",
            ),
            bodyTitles,
        )
        assertEquals(listOf("全书注释"), noteTitles)

        val allTitles = (outline.frontBackChapters + outline.bodyChapters + outline.notesChapters).map { it.title }
        assertTrue("Should not emit book-title fallback slice: $allTitles", allTitles.none { it.startsWith("合成长卷样本") })

        val bookSlice = v.loadBookInputs(imported.workId)
        val sec2 = bookSlice.chapters.first { it.title == "第二节 转型时期" }
        val sec2Joined = sec2.blocks.joinToString("\n") { it.text }
        assertTrue(
            "Expected cross-spine mid-sentence continuation stitched cleanly: $sec2Joined",
            sec2Joined.contains("跨文件延续的下半句也应当完整接回上一节末尾。"),
        )

        val ch2 = bookSlice.chapters.first { it.title == "第二章 跨文件章节" }
        val ch2Html = File(filesRoot, "compiled/${imported.workId.raw}/OEBPS/ch_${ch2.ordinal.twoDigit()}.xhtml").readText()
        assertTrue("Expected image preserved in ch2: $ch2Html", ch2Html.contains("images/chart1.png"))
    }

    @Test
    fun `epub3 nav xhtml nested fragment anchors split spine HTML cleanly`() = runTest {
        val v = createVault()
        val epub = buildSyntheticEpub3NavFragmentEpub(tempFolder.newFile("fragment_nav.epub"))
        val imported = v.importLocalBook(epub.absolutePath).getOrThrow()

        val outline = v.getWorkOutline(imported.workId)
        assertTrue(outline.frontBackChapters.any { it.title == "版权信息" })
        assertEquals(
            listOf(
                "导言 城市研究方法",
                "第一章 社区空间",
                "1.1 邻里广场",
                "1.2 公共厨房",
            ),
            outline.bodyChapters.map { it.title },
        )

        val slice = v.loadBookInputs(imported.workId)
        val s11 = slice.chapters.first { it.title == "1.1 邻里广场" }
        assertTrue(s11.blocks.any { it.text.contains("广场在周末成为居民自发交流的核心节点。") })
        val s12 = slice.chapters.first { it.title == "1.2 公共厨房" }
        assertTrue(s12.blocks.any { it.text.contains("公共厨房降低了单身住户独自做饭的心理门槛。") })
    }

    @Test
    fun `hard-wrapped calibre paragraphs stitch mid-sentence wraps while preserving headings, signatures, and epigraphs`() = runTest {
        val v = createVault()
        val epub = buildSyntheticHardWrapEpub(tempFolder.newFile("hardwrap_test.epub"))
        val imported = v.importLocalBook(epub.absolutePath).getOrThrow()

        val slice = v.loadBookInputs(imported.workId)
        val ch1 = slice.chapters.first { it.title == "第一章 物品与生活" }
        val texts = ch1.blocks.map { it.text }

        assertTrue(
            "Expected stitched sentence around pocket/socks: $texts",
            texts.any {
                it.contains("把旧布条缝在外套袖口当装饰，本身袖口的设计就更像是个点缀，现在变得几乎没有任何实用价值了。我把改造后的照片发到主页上，收获了许多点赞。")
            },
        )
        assertTrue(
            "Expected multi-line inline note 【...】 stitched cleanly: $texts",
            texts.any {
                it.contains("我们在工作室讨论了木工工艺 ㊟ 【木工工艺：强调保留天然木纹与可拆卸结构的传统手工技法。】 并在周末举办了展示。")
            },
        )

        assertTrue("Short subheading should stay separate: $texts", "如何整理日常旧物" in texts)
        assertTrue("Epigraph attribution should stay separate: $texts", "——林致远《手艺笔记》" in texts)
        assertTrue("Author signature should stay separate: $texts", "陈默然" in texts)
        assertTrue("Date line should stay separate: $texts", "2024年3月18日于杭州" in texts)

        assertTrue("Caption should stay separate: $texts", "图1-1 改造样本分布" in texts)
        assertTrue("Source line should stay separate: $texts", "资料来源：生活实验研究所" in texts)
    }

    @Test
    fun `normal well-formed EPUB without hard-wrapping has zero false paragraph merges`() = runTest {
        val v = createVault()
        val epub = buildNormalWellFormedEpub(tempFolder.newFile("well_formed.epub"))
        val imported = v.importLocalBook(epub.absolutePath).getOrThrow()

        val slice = v.loadBookInputs(imported.workId)
        val ch1 = slice.chapters.single { it.role == ChapterRole.BODY }
        assertEquals(
            listOf(
                "第一章 竞争优势的来源",
                "企业必须在成本领先与差异化之间做出清晰抉择",
                "第一段完整论述产业结构对利润率的长期影响。",
                "第二段进一步说明价值链各环节之间的协同效应。",
                "小结",
            ),
            ch1.blocks.map { it.text },
        )
    }

    @Test
    fun `stale parserVersion local EPUB auto-recompiles on outline access and re-import while preserving workId and profile override`() = runTest {
        val dbName = "banread_library_v1.db"
        context.deleteDatabase(dbName)

        val epub = buildSyntheticHardWrapEpub(tempFolder.newFile("legacy_upgrade.epub"))
        val sha256 = sha256Hex(epub.readBytes())
        val workId = "local_${sha256.take(16)}"

        val booksDir = File(filesRoot, "books").apply { mkdirs() }
        val storedEpub = File(booksDir, "$workId.epub")
        epub.copyTo(storedEpub, overwrite = true)
        val compiledDir = File(filesRoot, "compiled/$workId/OEBPS").apply { mkdirs() }
        File(compiledDir, "ch_01.xhtml").writeText("<html><body><p>旧版未切分正文</p></body></html>")

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `works` (
                                `workId` TEXT NOT NULL, `bookSourceSha256` TEXT NOT NULL, `title` TEXT NOT NULL,
                                `author` TEXT NOT NULL, `subjectsJson` TEXT NOT NULL, `readProfileId` TEXT NOT NULL,
                                `literatureSubKind` TEXT, `profileSource` TEXT NOT NULL, `clothColor` TEXT NOT NULL,
                                `coverRel` TEXT, `coverWidth` INTEGER, `coverHeight` INTEGER,
                                `originalFilePath` TEXT NOT NULL, `compiledRootPath` TEXT NOT NULL,
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
                                '$workId', '$sha256', '硬换行样本', '陈默然', '["社科"]', 'philosophy', NULL, 'USER_OVERRIDE',
                                '#1E3A36', NULL, NULL, NULL, '${storedEpub.absolutePath}', '${File(filesRoot, "compiled/$workId").absolutePath}',
                                'EPUB', '["REFLOWABLE"]', 0, 2, 1, 123456
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            INSERT INTO chapters VALUES (
                                '$workId', 1, '01', '硬换行样本（第 1 节）', 'BODY', 0, '["index_split_000.html"]',
                                'oldhash', 100, 1, 'OEBPS/ch_01.xhtml', NULL
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            INSERT INTO chapter_blocks VALUES (
                                '$workId', 1, 'B01', 0, 'paragraph', '', '旧版未切分正文', 0, 7, '{"href":"OEBPS/ch_01.xhtml","blockId":"B01"}'
                            )
                            """.trimIndent(),
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                },
            )
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase.close()

        val upgradedVault = createVault(allowInMemory = false)
        try {
            val wid = WorkId(workId)
            val outline = upgradedVault.getWorkOutline(wid)
            assertEquals("philosophy", outline.readProfileId?.raw)
            assertTrue(outline.bodyChapters.any { it.title == "第一章 物品与生活" })
            assertTrue(outline.bodyChapters.any { it.title == "第二章 日常秩序" })
            assertTrue(outline.frontBackChapters.any { it.title == "版权信息" })

            val firstBody = outline.bodyChapters.first()
            val compiledHtml = File(filesRoot, "compiled/$workId/OEBPS/ch_${firstBody.ordinal.twoDigit()}.xhtml").readText()
            assertFalse(compiledHtml.contains("旧版未切分正文"))
            assertTrue(compiledHtml.contains("把旧布条缝在外套袖口当装饰，本身袖口的设计就更像是个点缀"))

            val reimported = upgradedVault.importLocalBook(epub.absolutePath).getOrThrow()
            assertEquals(wid, reimported.workId)
            assertEquals("philosophy", reimported.readProfileId.raw)

            val summary = upgradedVault.getWorkSummary(wid)
            assertNotNull(summary)
            assertEquals(123456L, summary!!.addedAtMs)
        } finally {
            (upgradedVault as? Closeable)?.close()
            vault = null
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `LibraryDatabase MIGRATION_2_3 upgrades v2 schema cleanly with parserVersion defaulted to 1`() {
        val dbName = "test_mig_v2_v3.db"
        context.deleteDatabase(dbName)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `works` (
                                `workId` TEXT NOT NULL, `bookSourceSha256` TEXT NOT NULL, `title` TEXT NOT NULL,
                                `author` TEXT NOT NULL, `subjectsJson` TEXT NOT NULL, `readProfileId` TEXT NOT NULL,
                                `literatureSubKind` TEXT, `profileSource` TEXT NOT NULL, `clothColor` TEXT NOT NULL,
                                `coverRel` TEXT, `coverWidth` INTEGER, `coverHeight` INTEGER,
                                `originalFilePath` TEXT NOT NULL, `compiledRootPath` TEXT NOT NULL,
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
                                'W_HEALTHY', '${"d".repeat(64)}', '竞争战略', '迈克尔·波特', '["经济"]', 'economics', NULL, 'AUTO',
                                '#1E3A36', NULL, 400, 600, '/tmp/comp.epub', '/tmp/comp_c', 'EPUB', '["REFLOWABLE"]', 0, 2, 1, 9000
                            )
                            """.trimIndent(),
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                },
            )
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase.close()

        val migrated = Room.databaseBuilder(context, LibraryDatabase::class.java, dbName)
            .addMigrations(LibraryDatabase.MIGRATION_1_2, LibraryDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val works = kotlinx.coroutines.runBlocking { migrated.libraryDao().listAllWorks() }
            assertEquals(1, works.size)
            assertEquals("竞争战略", works.single().title)
            assertEquals(1, works.single().parserVersion)
            assertEquals(400, works.single().coverWidth)
            assertNotEquals(0, works.single().addedAtMs)
        } finally {
            migrated.close()
            context.deleteDatabase(dbName)
        }
    }

    private fun buildSyntheticFragmentNcxEpub(dest: File): File {
        val fillerPara = "这是一个用于模拟真实多章长卷文本的合成段落，用来验证按锚点切分不会落入超长章节虚拟切分。\n"
        val split000 = buildString {
            appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
            appendLine("""<html xmlns="http://www.w3.org/1999/xhtml"><head><title>合成长卷样本</title></head><body>""")
            appendLine("""<p><a id="p1"></a><img src="cover_inner.png" alt="扉页图"/></p>""")
            appendLine("""<p><a id="p2"></a><b>版权信息</b></p>""")
            appendLine("""<p>书名：合成长卷样本</p><p>作者：测试作者</p>""")
            appendLine("""<p><a id="p4"></a><b>赞誉</b></p>""")
            appendLine("""<p>这是一部结构清晰的合成测试样本著作，用来检验多锚点目录识别与切分能力。</p>""")
            appendLine("""<p>——某位评论者</p>""")
            appendLine("""<p><a id="p9"></a><b>前言　对模拟时代的回顾</b></p>""")
            appendLine("""<p>${fillerPara.repeat(12).trim()}</p>""")
            appendLine("""<p><a id="p13"></a><b>第一章　起始阶段</b></p>""")
            appendLine("""<p>这是第一章导语段落，引出接下来的两个核心分节内容。</p>""")
            appendLine("""<p><a id="p15"></a><b>第一节　早期生活</b></p>""")
            appendLine("""<p>早期生活章节的完整叙述内容在这里展开，包含日常作息与社区交往细节。</p>""")
            appendLine("""<p><a id="p24"></a><b>第二节　转型时期</b></p>""")
            appendLine("""<p>在转型时期的最后一段里，作者总结了关键变化，并且留下了</p>""")
            appendLine("""</body></html>""")
        }
        val split001 = buildString {
            appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
            appendLine("""<html xmlns="http://www.w3.org/1999/xhtml"><head><title>合成长卷样本</title></head><body>""")
            appendLine("""<p>跨文件延续的下半句也应当完整接回上一节末尾。</p>""")
            appendLine("""<p><a id="p111"></a><img src="chart1.png" alt="图表1"/></p>""")
            appendLine("""<p><b>第二章　跨文件章节</b></p>""")
            appendLine("""<p>第二章正文由图片锚点引导开始，说明跨分片锚点定位的正确性。</p>""")
            appendLine("""<p><a id="p120"></a><b>第一节　城市变迁</b></p>""")
            appendLine("""<p>城市变迁章节讨论公共空间与步行街区的复兴过程。</p>""")
            appendLine("""<p><a id="p202"></a><b>附录　案例汇编</b></p>""")
            appendLine("""<p><b>案例导读</b></p>""")
            appendLine("""<p>附录汇编了若干共享社区与公共客厅的实践样例。</p>""")
            appendLine("""<p><a id="p240"></a><b>全书注释</b></p>""")
            appendLine("""<p>[1] 此处为合成测试尾注内容。</p>""")
            appendLine("""</body></html>""")
        }
        val split002 = """
            <?xml version="1.0" encoding="utf-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>合成长卷样本</title></head><body>
            <p class="calibre1">
            <h1>Document Outline</h1>
            <ul><li><a href="index_split_000.html#p2">版权信息</a></li><li><a href="index_split_000.html#p13">第一章 起始阶段</a></li></ul>
            </p>
            </body></html>
        """.trimIndent()
        val tocNcx = """
            <?xml version="1.0" encoding="utf-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <head><meta name="dtb:uid" content="synth-001"/></head>
              <docTitle><text>合成长卷样本</text></docTitle>
              <navMap>
                <navPoint id="n1" playOrder="1"><navLabel><text>版权信息</text></navLabel><content src="index_split_000.html#p2"/></navPoint>
                <navPoint id="n2" playOrder="2"><navLabel><text>赞誉</text></navLabel><content src="index_split_000.html#p4"/></navPoint>
                <navPoint id="n3" playOrder="3"><navLabel><text>前言 对模拟时代的回顾</text></navLabel><content src="index_split_000.html#p9"/></navPoint>
                <navPoint id="n4" playOrder="4">
                  <navLabel><text>第一章 起始阶段</text></navLabel><content src="index_split_000.html#p13"/>
                  <navPoint id="n5" playOrder="5"><navLabel><text>第一节 早期生活</text></navLabel><content src="index_split_000.html#p15"/></navPoint>
                  <navPoint id="n6" playOrder="6"><navLabel><text>第二节 转型时期</text></navLabel><content src="index_split_000.html#p24"/></navPoint>
                </navPoint>
                <navPoint id="n7" playOrder="7">
                  <navLabel><text>第二章 跨文件章节</text></navLabel><content src="index_split_001.html#p111"/>
                  <navPoint id="n8" playOrder="8"><navLabel><text>第一节 城市变迁</text></navLabel><content src="index_split_001.html#p120"/></navPoint>
                </navPoint>
                <navPoint id="n9" playOrder="9">
                  <navLabel><text>附录 案例汇编</text></navLabel><content src="index_split_001.html#p202"/>
                  <navPoint id="n10" playOrder="9"><navLabel><text>案例导读</text></navLabel><content src="index_split_001.html#p202"/></navPoint>
                </navPoint>
                <navPoint id="n11" playOrder="10"><navLabel><text>全书注释</text></navLabel><content src="index_split_001.html#p240"/></navPoint>
              </navMap>
            </ncx>
        """.trimIndent()
        val opf = """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>合成长卷样本</dc:title><dc:creator>测试作者</dc:creator><dc:subject>社会学</dc:subject>
              </metadata>
              <manifest>
                <item id="s0" href="index_split_000.html" media-type="application/xhtml+xml"/>
                <item id="s1" href="index_split_001.html" media-type="application/xhtml+xml"/>
                <item id="s2" href="index_split_002.html" media-type="application/xhtml+xml"/>
                <item id="img0" href="cover_inner.png" media-type="image/png"/>
                <item id="img1" href="chart1.png" media-type="image/png"/>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
              </manifest>
              <spine toc="ncx">
                <itemref idref="s0"/><itemref idref="s1"/><itemref idref="s2"/>
              </spine>
            </package>
        """.trimIndent()
        writeEpubZip(
            dest = dest,
            opfPath = "content.opf",
            entries = linkedMapOf(
                "content.opf" to opf.toByteArray(),
                "toc.ncx" to tocNcx.toByteArray(),
                "index_split_000.html" to split000.toByteArray(),
                "index_split_001.html" to split001.toByteArray(),
                "index_split_002.html" to split002.toByteArray(),
                "cover_inner.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
                "chart1.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
            ),
        )
        return dest
    }

    private fun buildSyntheticEpub3NavFragmentEpub(dest: File): File {
        val navXhtml = """
            <?xml version="1.0" encoding="utf-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <head><title>目录</title></head>
              <body>
                <nav epub:type="toc" id="toc">
                  <ol>
                    <li><a href="text/book.xhtml#copy">版权信息</a></li>
                    <li><a href="text/book.xhtml#intro">导言 城市研究方法</a></li>
                    <li>
                      <a href="text/book.xhtml#ch1">第一章 社区空间</a>
                      <ol>
                        <li><a href="text/book.xhtml#sec1_1">1.1 邻里广场</a></li>
                        <li><a href="text/book.xhtml#sec1_2">1.2 公共厨房</a></li>
                      </ol>
                    </li>
                  </ol>
                </nav>
                <nav epub:type="landmarks"><ol><li><a href="text/book.xhtml#ch1">正文</a></li></ol></nav>
              </body>
            </html>
        """.trimIndent()
        val bookXhtml = """
            <?xml version="1.0" encoding="utf-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><title>城市观察</title></head>
              <body>
                <section id="copy"><h2>版权信息</h2><p>测试出版社，2025。</p></section>
                <section id="intro"><h2>导言 城市研究方法</h2><p>本章介绍田野观察的基本方法。</p></section>
                <div id="ch1"><h2>第一章 社区空间</h2><p>社区空间决定邻里互动密度。</p></div>
                <p id="sec1_1"><b>1.1 邻里广场</b></p><p>广场在周末成为居民自发交流的核心节点。</p>
                <p><a name="sec1_2"></a><b>1.2 公共厨房</b></p><p>公共厨房降低了单身住户独自做饭的心理门槛。</p>
              </body>
            </html>
        """.trimIndent()
        val opf = """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>城市观察</dc:title><dc:creator>测试者</dc:creator><dc:subject>社科</dc:subject>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="book" href="text/book.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="book"/></spine>
            </package>
        """.trimIndent()
        writeEpubZip(
            dest = dest,
            opfPath = "OEBPS/content.opf",
            entries = linkedMapOf(
                "OEBPS/content.opf" to opf.toByteArray(),
                "OEBPS/nav.xhtml" to navXhtml.toByteArray(),
                "OEBPS/text/book.xhtml" to bookXhtml.toByteArray(),
            ),
        )
        return dest
    }

    private fun buildSyntheticHardWrapEpub(dest: File): File {
        val html = """
            <?xml version="1.0" encoding="utf-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>硬换行样本</title></head><body class="calibre">
            <p class="calibre1"><a id="p1"></a><b class="calibre3">版权信息</b></p>
            <p class="calibre1">书名：硬换行样本</p>
            <p class="calibre1">作者：陈默然</p>
            <p class="calibre1">出版社：测试书局</p>
            <p class="calibre1"><a id="p5"></a><b class="calibre3">第一章　物品与生活</b></p>
            <p class="calibre1">旧物并非只能丢弃，只要稍加修补就能继续参与日常生活。</p>
            <p class="calibre1">——林致远《手艺笔记》</p>
            <p class="calibre1">把旧布条缝在外套袖口当装饰，本身袖口的设计就更像是个点</p>
            <p class="calibre1">缀，现在变得几乎没有任何实用价值了。我把改造后的照片发</p>
            <p class="calibre1">到主页上，收获了许多点赞。</p>
            <p class="calibre1">如何整理日常旧物</p>
            <p class="calibre1">我们在工作室讨论了木工工艺 ㊟ 【木工工艺：强调保留天然木</p>
            <p class="calibre1">纹与可拆卸结构的传统手工技法。】 并在周末举办了展示。</p>
            <p class="calibre1">图1-1 改造样本分布</p>
            <p class="calibre1">资料来源：生活实验研究所</p>
            <p class="calibre1">经过反复试验，这套整理办法已经可以稳定推广给更多家庭使</p>
            <p class="calibre1">用，希望大家都能减少不必要的浪费。</p>
            <p class="calibre1">陈默然</p>
            <p class="calibre1">2024年3月18日于杭州</p>
            <p class="calibre1"><a id="p20"></a><b class="calibre3">第二章　日常秩序</b></p>
            <p class="calibre1">第二章的普通正文应当保持独立成章且自然连贯。</p>
            </body></html>
        """.trimIndent()
        val ncx = """
            <?xml version="1.0" encoding="utf-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <navMap>
                <navPoint id="n1" playOrder="1"><navLabel><text>版权信息</text></navLabel><content src="index_split_000.html#p1"/></navPoint>
                <navPoint id="n2" playOrder="2"><navLabel><text>第一章 物品与生活</text></navLabel><content src="index_split_000.html#p5"/></navPoint>
                <navPoint id="n3" playOrder="3"><navLabel><text>第二章 日常秩序</text></navLabel><content src="index_split_000.html#p20"/></navPoint>
              </navMap>
            </ncx>
        """.trimIndent()
        val opf = """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>硬换行样本</dc:title><dc:creator>陈默然</dc:creator><dc:subject>社科</dc:subject>
              </metadata>
              <manifest>
                <item id="s0" href="index_split_000.html" media-type="application/xhtml+xml"/>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
              </manifest>
              <spine toc="ncx"><itemref idref="s0"/></spine>
            </package>
        """.trimIndent()
        writeEpubZip(
            dest = dest,
            opfPath = "content.opf",
            entries = linkedMapOf(
                "content.opf" to opf.toByteArray(),
                "toc.ncx" to ncx.toByteArray(),
                "index_split_000.html" to html.toByteArray(),
            ),
        )
        return dest
    }

    private fun buildNormalWellFormedEpub(dest: File): File {
        val ch1 = """
            <?xml version="1.0" encoding="utf-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>第一章</title></head><body>
            <h1>第一章 竞争优势的来源</h1>
            <p>企业必须在成本领先与差异化之间做出清晰抉择</p>
            <p>第一段完整论述产业结构对利润率的长期影响。</p>
            <p>第二段进一步说明价值链各环节之间的协同效应。</p>
            <p>小结</p>
            </body></html>
        """.trimIndent()
        val opf = """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>竞争战略样本</dc:title><dc:creator>迈克尔·波特</dc:creator><dc:subject>经济</dc:subject>
              </metadata>
              <manifest><item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine><itemref idref="c1"/></spine>
            </package>
        """.trimIndent()
        writeEpubZip(
            dest = dest,
            opfPath = "OEBPS/content.opf",
            entries = linkedMapOf(
                "OEBPS/content.opf" to opf.toByteArray(),
                "OEBPS/ch1.xhtml" to ch1.toByteArray(),
            ),
        )
        return dest
    }

    private fun writeEpubZip(
        dest: File,
        opfPath: String,
        entries: Map<String, ByteArray>,
    ) {
        ZipOutputStream(FileOutputStream(dest)).use { zos ->
            zos.putNextEntry(ZipEntry("mimetype"))
            zos.write("application/epub+zip".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="$opfPath" media-type="application/oebps-package+xml"/></rootfiles>
                </container>""".trimIndent().toByteArray(),
            )
            zos.closeEntry()

            for ((path, bytes) in entries) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
