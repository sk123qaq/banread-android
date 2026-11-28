package app.banread.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.banread.ai.InMemorySecretVaultAdapter
import app.banread.library.LibraryVaultFactory
import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import app.banread.model.BcBookManifest
import app.banread.model.BlockId
import app.banread.model.BookRendererPort
import app.banread.model.BookSourceHash
import app.banread.model.BundleTransferState
import app.banread.model.ChapterContentHash
import app.banread.model.ChapterOrdinal
import app.banread.model.ChapterRole
import app.banread.model.DeviceId
import app.banread.model.DeviceProgressShard
import app.banread.model.LibraryVault
import app.banread.model.LogicalChapterEntry
import app.banread.model.NoteLogEntry
import app.banread.model.ReadProfileId
import app.banread.model.ReaderEngine
import app.banread.model.ReadingAnchor
import app.banread.model.TextQuote
import app.banread.model.WorkId
import app.banread.model.registry.ProfileRegistry
import app.banread.reader.ReaderEngineFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HttpDesktopGatewayAdapterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var filesRoot: File
    private lateinit var libraryVault: LibraryVault
    private lateinit var readerEngine: ReaderEngine
    private lateinit var secretVault: InMemorySecretVaultAdapter
    private lateinit var server: TestLoopbackHttpServer
    private lateinit var baseUrl: String

    private val expectedToken = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private var crossSearchReturns503 = false
    private lateinit var activeBundleBytes: ByteArray
    private lateinit var activeBundleEtag: String
    private val ackedIds = CopyOnWriteArrayList<String>()
    private val storedDesktopShards = LinkedHashMap<String, String>()
    private val receivedCrossSearchBodies = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        filesRoot = tempFolder.newFolder("gw_files")
        val registry = ProfileRegistry.loadFromClasspath()
        libraryVault = LibraryVaultFactory.create(context, filesRoot, registry, allowInMemory = true)
        readerEngine = ReaderEngineFactory.create(
            context = context,
            filesRoot = filesRoot,
            libraryVault = libraryVault,
            rendererPort = NoOpRendererPort(),
            deviceId = DeviceId("pixel-9"),
            allowInMemory = true,
        )
        secretVault = InMemorySecretVaultAdapter()

        activeBundleBytes = buildValidBcBookBytes("B0001", "史记选读", "a".repeat(64))
        activeBundleEtag = "\"${sha256Hex(activeBundleBytes)}\""

        server = TestLoopbackHttpServer { req ->
            val auth = req.headers["authorization"]
            if (auth != "Bearer $expectedToken") {
                return@TestLoopbackHttpServer jsonResp(401, """{"ok":false,"error":"unauthorized"}""")
            }
            val path = req.path
            val query = req.rawQuery
            val method = req.method

            when {
                method == "GET" && path == "/api/mobile/v1/health" -> {
                    jsonResp(200, """{"ok":true,"service":"mobile-gateway","schema_version":1}""")
                }
                method == "GET" && path == "/api/mobile/v1/catalog" -> {
                    val hasCacheFilter = "has_cache=1" in query
                    val booksJson = if (hasCacheFilter) {
                        """[{"uid":"B0001","title":"史记选读","author":"司马迁","subjects":["历史"],"read_type":"history","sub_kind":"","cloth_color":"#A87A3E","logical_chapter_count":1,"has_companion_cache":true,"canonical_version":1}]"""
                    } else {
                        """[{"uid":"B0001","title":"史记选读","author":"司马迁","subjects":["历史"],"read_type":"history","sub_kind":"","cloth_color":"#A87A3E","logical_chapter_count":1,"has_companion_cache":true,"canonical_version":1},{"uid":"B0002","title":"呐喊","author":"鲁迅","subjects":["文学"],"read_type":"literature","sub_kind":"fiction","cloth_color":"#1E3A36","logical_chapter_count":1,"has_companion_cache":false,"canonical_version":1}]"""
                    }
                    val total = if (hasCacheFilter) 1 else 2
                    jsonResp(200, """{"books":$booksJson,"total":$total}""")
                }
                method == "GET" && path == "/api/mobile/v1/outbox" -> {
                    jsonResp(
                        200,
                        """{"tickets":[{"ticket_id":"tkt-001","uid":"B0001","include_cache":true,"created_at":"2026-11-28T09:00:00Z"}]}""",
                    )
                }
                method == "POST" && path == "/api/mobile/v1/outbox/ack" -> {
                    val body = req.bodyBytes.toString(Charsets.UTF_8)
                    val match = Regex(""""tkt-[^"]+"""").findAll(body).map { it.value.trim('"') }.toList()
                    ackedIds += match
                    jsonResp(200, """{"ok":true,"acked":${match.map { "\"$it\"" }},"remaining":0,"tickets":[]}""")
                }
                (method == "GET" || method == "HEAD") && path.startsWith("/api/mobile/v1/bundle/") -> {
                    val range = req.headers["range"]
                    val baseHdrs = linkedMapOf(
                        "Accept-Ranges" to "bytes",
                        "ETag" to activeBundleEtag,
                    )
                    if (method == "HEAD") {
                        baseHdrs["Content-Length"] = activeBundleBytes.size.toString()
                        return@TestLoopbackHttpServer TestHttpResponse(200, ByteArray(0), baseHdrs)
                    }
                    if (range != null && range.startsWith("bytes=")) {
                        val start = range.removePrefix("bytes=").substringBefore('-').toIntOrNull() ?: 0
                        if (start >= activeBundleBytes.size) {
                            baseHdrs["Content-Range"] = "bytes */${activeBundleBytes.size}"
                            return@TestLoopbackHttpServer TestHttpResponse(416, ByteArray(0), baseHdrs)
                        }
                        val slice = activeBundleBytes.copyOfRange(start, activeBundleBytes.size)
                        baseHdrs["Content-Range"] = "bytes $start-${activeBundleBytes.size - 1}/${activeBundleBytes.size}"
                        TestHttpResponse(206, slice, baseHdrs)
                    } else {
                        TestHttpResponse(200, activeBundleBytes, baseHdrs)
                    }
                }
                method == "POST" && path.startsWith("/api/mobile/v1/progress/") -> {
                    val body = req.bodyBytes.toString(Charsets.UTF_8)
                    val devId = Regex(""""device_id"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1) ?: "unknown"
                    storedDesktopShards[devId] = body
                    jsonResp(200, """{"ok":true}""")
                }
                method == "GET" && path.startsWith("/api/mobile/v1/progress/") -> {
                    val uid = path.substringAfterLast('/')
                    val shardsArray = storedDesktopShards.values.joinToString(",")
                    jsonResp(200, """{"uid":"$uid","shards":[$shardsArray]}""")
                }
                method == "POST" && path == "/api/mobile/v1/cross-search" -> {
                    val body = req.bodyBytes.toString(Charsets.UTF_8)
                    receivedCrossSearchBodies += body
                    if (crossSearchReturns503) {
                        jsonResp(503, """{"ok":false,"error":"search_down"}""")
                    } else {
                        jsonResp(
                            200,
                            """{"ok":true,"hits":[{"uid":"B0099","title":"汉书选读","type":"历史","relevance":0.91,"best_path":"books/B0099/md/01.md","best_line":12,"snippet":"高帝纪命中片段"}]}""",
                        )
                    }
                }
                else -> jsonResp(404, """{"ok":false,"error":"not_found"}""")
            }
        }
        baseUrl = server.baseUrl
    }

    @After
    fun tearDown() {
        server.close()
        (readerEngine as? Closeable)?.close()
        (libraryVault as? Closeable)?.close()
    }

    @Test
    fun `pairs via bc-pair URI without leaking token, handles 401, catalog, Range 206 resume, ETag restart, post-ingest ACK, progress, and cross-search`() = runTest {
        val adapter = HttpDesktopGatewayAdapter()
        val coordinator = SyncCoordinatorFactory.createForTest(
            filesRoot = filesRoot,
            libraryVault = libraryVault,
            readerEngine = readerEngine,
            companionEngine = null,
            secretVault = secretVault,
            deviceId = DeviceId("pixel-9"),
            desktopBridge = adapter,
        )

        val badHealth = runCatching { adapter.checkHealth("wrong-token", baseUrl) }
        assertTrue(badHealth.isFailure)
        assertTrue(badHealth.exceptionOrNull()!!.message!!.contains("401"))

        val pairInfo = coordinator.pairDesktopViaQr("bc-pair://v1?base=$baseUrl&token=$expectedToken").getOrThrow()
        assertEquals(baseUrl, pairInfo.baseUrl)
        assertEquals(expectedToken, secretVault.getSecret("sync:desktop:bearer_token"))
        val cfgFile = File(filesRoot, "sync_pixel-9/sync_config.json")
        assertFalse("Secret token must NEVER appear in sync_config.json", cfgFile.readText().contains(expectedToken))

        assertTrue(coordinator.checkDesktopHealth().getOrThrow())
        assertEquals(2, coordinator.fetchDesktopCatalog().getOrThrow().size)
        val cachedOnly = coordinator.fetchDesktopCatalog(onlyWithCache = true).getOrThrow()
        assertEquals(listOf(WorkId("B0001")), cachedOnly.map { it.uid })

        val partFile = File(filesRoot, "sync_pixel-9/downloads/B0001.bcbook.part").apply {
            parentFile?.mkdirs()
            writeBytes(activeBundleBytes.copyOfRange(0, 120))
        }
        File(partFile.parentFile, "${partFile.name}.etag").writeText(activeBundleEtag)

        val states = coordinator.pullStudyPacks(listOf(WorkId("B0001"))).toList()
        assertTrue(states.last() is BundleTransferState.Ingested)
        assertEquals(listOf("tkt-001"), ackedIds.toList())
        assertEquals("史记选读", libraryVault.getWorkSummary(WorkId("B0001"))?.title)

        val oldEtag = "\"stale-etag-0000\""
        val part2 = tempFolder.newFile("etag_mismatch.part").apply {
            writeBytes(ByteArray(80) { 0x55 })
        }
        File(part2.parentFile, "${part2.name}.etag").writeText(oldEtag)
        adapter.downloadBundleStream(expectedToken, baseUrl, WorkId("B0001"), part2.absolutePath, resumeOffset = 80L).toList()
        assertEquals(activeBundleBytes.size, part2.readBytes().size)
        assertEquals(sha256Hex(activeBundleBytes), sha256Hex(part2.readBytes()))

        ackedIds.clear()
        activeBundleBytes = "NOT_A_ZIP_CORRUPT_BUNDLE".toByteArray()
        activeBundleEtag = "\"${sha256Hex(activeBundleBytes)}\""
        val badStates = coordinator.pullStudyPacks(listOf(WorkId("B0001"))).toList()
        assertTrue(badStates.last() is BundleTransferState.Failed)
        assertTrue("ACK must NOT be sent when ingestion fails", ackedIds.isEmpty())

        val shard = DeviceProgressShard(
            workId = WorkId("B0001"),
            deviceId = DeviceId("pixel-9"),
            cursor = ReadingAnchor.ReflowCfi(
                ordinal = ChapterOrdinal(1),
                href = "OEBPS/ch_01.xhtml",
                progression = 0.5f,
                totalProgression = 0.5f,
                cfi = "epubcfi(/6/2!/4/2)",
                textQuote = TextQuote("", "高祖", ""),
                blockId = BlockId("B01"),
            ),
            updatedAtEpochMs = 1764230400000L,
        )
        adapter.pushProgressShard(expectedToken, baseUrl, shard)
        val pulledShards = adapter.fetchProgressShards(expectedToken, baseUrl, WorkId("B0001"))
        assertEquals(1, pulledShards.size)
        assertEquals(DeviceId("pixel-9"), pulledShards.single().deviceId)
        assertEquals(ChapterOrdinal(1), pulledShards.single().cursor.ordinal)

        val hits = coordinator.queryCrossBookHits(WorkId("B0001"), "楚汉制度", "秦二世元年").getOrThrow()
        assertEquals(1, hits.size)
        assertEquals(WorkId("B0099"), hits.single().uid)
        assertTrue(receivedCrossSearchBodies.single().contains("\"exclude_books\":[\"B0001\"]"))

        crossSearchReturns503 = true
        val offlineRes = coordinator.queryCrossBookHits(WorkId("B0001"), "楚汉制度", null)
        assertTrue(offlineRes.isFailure)
        val ex = offlineRes.exceptionOrNull() as AppFailureException
        assertTrue(ex.failure is AppFailure.DesktopConnectionRequired)

        coordinator.unpairDesktop()
        assertNull(secretVault.getSecret("sync:desktop:bearer_token"))
        val unpairedRes = coordinator.queryCrossBookHits(WorkId("B0001"), "test", null)
        assertTrue((unpairedRes.exceptionOrNull() as AppFailureException).failure is AppFailure.DesktopConnectionRequired)
    }

    private fun jsonResp(code: Int, body: String): TestHttpResponse = TestHttpResponse(
        code = code,
        bodyBytes = body.toByteArray(Charsets.UTF_8),
        headers = mapOf("Content-Type" to "application/json; charset=utf-8"),
    )

    private fun buildValidBcBookBytes(uid: String, title: String, bookSourceSha: String): ByteArray {
        val md = "# 第一章\n\n高祖，沛丰邑中阳里人，姓刘氏。\n".toByteArray(Charsets.UTF_8)
        val manifest = BcBookManifest(
            schemaVersion = 1,
            uid = WorkId(uid),
            title = title,
            author = "司马迁",
            subjects = listOf("历史"),
            readType = ReadProfileId("history"),
            bookSourceSha256 = BookSourceHash(bookSourceSha),
            clothColor = "#A87A3E",
            logicalChapters = listOf(
                LogicalChapterEntry(
                    ordinal = ChapterOrdinal(1),
                    n = "01",
                    title = "第一章",
                    chapterRole = ChapterRole.BODY,
                    merged = false,
                    sourceFiles = listOf("01_第一章.md"),
                    chapterContentSha256 = ChapterContentHash(sha256Hex(md)),
                    chars = 16,
                ),
            ),
        )
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(json.encodeToString(BcBookManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("md/01_第一章.md"))
            zos.write(md)
            zos.closeEntry()
        }
        return baos.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class NoOpRendererPort : BookRendererPort {
        override val locationEvents: Flow<ReadingAnchor> = MutableSharedFlow()
        override suspend fun load(compiledAssetRoot: String, initialAnchor: ReadingAnchor?) = Unit
        override suspend fun navigate(anchor: ReadingAnchor) = Unit
        override suspend fun updateStyles(cssPayload: String) = Unit
        override suspend fun renderDecorations(annotations: List<NoteLogEntry>) = Unit
    }
}
