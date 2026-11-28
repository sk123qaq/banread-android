package app.banread.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.banread.ai.AiRuntimeFactory
import app.banread.ai.InMemorySecretVaultAdapter
import app.banread.companion.CompanionEngineFactory
import app.banread.library.LibraryVaultFactory
import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import app.banread.model.AuditedArtifact
import app.banread.model.BcBookManifest
import app.banread.model.BlockId
import app.banread.model.BookRendererPort
import app.banread.model.BookSourceHash
import app.banread.model.ChapterContentHash
import app.banread.model.ChapterOrdinal
import app.banread.model.ChapterRole
import app.banread.model.CompanionEngine
import app.banread.model.DeviceId
import app.banread.model.LibraryVault
import app.banread.model.LogicalChapterEntry
import app.banread.model.NoteLogEntry
import app.banread.model.ReadProfileId
import app.banread.model.ReaderEngine
import app.banread.model.ReadingAnchor
import app.banread.model.ReadingSurfaceMode
import app.banread.model.SharedCompanionArtifact
import app.banread.model.SharedCompanionPayload
import app.banread.model.SharedQaTurn
import app.banread.model.WorkId
import app.banread.model.registry.ProfileRegistry
import app.banread.reader.ReaderEngineFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreeChannelShareTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var registry: ProfileRegistry
    private lateinit var relayServer: TestLoopbackHttpServer
    private lateinit var relayBaseUrl: String
    private val relayStorage = ConcurrentHashMap<String, ByteArray>()
    private val relayRequests = CopyOnWriteArrayList<String>()
    private val closeables = mutableListOf<Closeable>()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        registry = ProfileRegistry.loadFromClasspath()
        relayServer = TestLoopbackHttpServer { req ->
            relayRequests += "${req.method} ${req.path}"
            when (req.method) {
                "PUT" -> {
                    relayStorage[req.path] = req.bodyBytes
                    TestHttpResponse(200)
                }
                "GET" -> {
                    val bytes = relayStorage[req.path]
                    if (bytes == null) TestHttpResponse(404) else TestHttpResponse(200, bytes)
                }
                else -> TestHttpResponse(405)
            }
        }
        relayBaseUrl = relayServer.baseUrl
    }

    @After
    fun tearDown() {
        relayServer.close()
        closeables.forEach { runCatching { it.close() } }
        closeables.clear()
    }

    @Test
    fun `Channel 1 R2 AES-GCM keeps key in #k fragment, detects tampering, skips book on same SHA, and imports new SHA`() = runTest {
        val sender = createPeer("sender", DeviceId("sender-phone"))
        val sameShaReceiver = createPeer("rx_same", DeviceId("rx-same"))
        val newShaReceiver = createPeer("rx_new", DeviceId("rx-new"))

        val bookSha = "d".repeat(64)
        val bcBook = writeBcBook(tempFolder.newFile("B0042.bcbook"), "B0042", "雾夜档案", bookSha)
        sender.vault.ingestStudyPack(bcBook.absolutePath).getOrThrow()
        sameShaReceiver.vault.ingestStudyPack(bcBook.absolutePath).getOrThrow()

        sender.reader.open(WorkId("B0042"), ReadingSurfaceMode.REFLOWABLE)
        val anchor = sender.vault.resolveAnchor(WorkId("B0042"), ChapterOrdinal(3), BlockId("B01"))
        sender.reader.goTo(anchor)
        sender.reader.saveAnnotation(WorkId("B0042"), anchor, "YELLOW", "好友分享的高亮批注")
        sender.companion.importShareCompanionData(
            WorkId("B0042"),
            SharedCompanionPayload(
                artifacts = listOf(
                    SharedCompanionArtifact(
                        scopeKey = "chapter:03",
                        toolId = "chapter_map",
                        promptVersion = 1,
                        artifact = AuditedArtifact.MarkdownReadCard(
                            caption = "第三章导图",
                            markdown = "核心事件梳理 [B01]",
                        ),
                    ),
                ),
                qaTurns = listOf(
                    SharedQaTurn("t1", "chapter:03", 0, "user", "谁封缄了信封？"),
                    SharedQaTurn("t1", "chapter:03", 1, "assistant", "海关官员 [B01]"),
                ),
            ),
        )

        sender.share.configureR2Relay(relayBaseUrl, uploadToken = "relay-tok")
        val sendRes = sender.share.sendViaR2Relay(
            workId = WorkId("B0042"),
            selection = ShareScopeSelection(
                includeMaps = true,
                includeNotes = true,
                includeQa = true,
                includeProgress = true,
                includeBookFile = true,
            ),
            shareIdOverride = "br_r2test01",
        ).getOrThrow()

        val shareUrl = sendRes.shareUrl!!
        assertTrue(shareUrl.startsWith("$relayBaseUrl/s/br_r2test01#k="))

        val storedManifestCipher = relayStorage["/s/br_r2test01/manifest"]!!
        assertFalse(storedManifestCipher.toString(Charsets.UTF_8).contains("雾夜档案"))
        assertTrue(relayRequests.none { "#k=" in it })

        val tampered = storedManifestCipher.copyOf().also { it[it.size - 1] = (it.last().toInt() xor 0xFF).toByte() }
        relayStorage["/s/br_r2test01/manifest"] = tampered
        val tamperPreview = sameShaReceiver.share.previewIncomingShare(shareUrl)
        assertTrue(tamperPreview.isFailure)
        assertTrue((tamperPreview.exceptionOrNull() as AppFailureException).failure is AppFailure.NetworkOrSyncConflict)
        relayStorage["/s/br_r2test01/manifest"] = storedManifestCipher

        sameShaReceiver.reader.open(WorkId("B0042"), ReadingSurfaceMode.REFLOWABLE)
        val sameAnchor = sameShaReceiver.vault.resolveAnchor(WorkId("B0042"), ChapterOrdinal(2), BlockId("B01"))
        sameShaReceiver.reader.saveAnnotation(WorkId("B0042"), sameAnchor, "BLUE", "接收方原有个人笔记")

        relayRequests.clear()
        val samePreview = sameShaReceiver.share.previewIncomingShare(shareUrl).getOrThrow()
        assertTrue(samePreview.willSkipBookDownload)
        assertEquals(WorkId("B0042"), samePreview.matchedLocalWorkId)

        val sameImport = sameShaReceiver.share.confirmReceiveShare(samePreview).getOrThrow()
        assertTrue(sameImport.skippedBookBinary)
        assertEquals(1, sameImport.importedArtifacts)
        assertEquals(2, sameImport.importedQaTurns)
        assertEquals(1, sameShaReceiver.vault.listWorks().size)
        assertEquals(2, sameShaReceiver.reader.listNoteLogEntries(WorkId("B0042"), includeDeleted = false).size)
        assertTrue("Same-SHA receiver must NOT fetch /book", relayRequests.none { it == "GET /s/br_r2test01/book" })

        relayRequests.clear()
        val newPreview = newShaReceiver.share.previewIncomingShare(shareUrl).getOrThrow()
        assertFalse(newPreview.willSkipBookDownload)
        val newImport = newShaReceiver.share.confirmReceiveShare(newPreview).getOrThrow()
        assertFalse(newImport.skippedBookBinary)
        assertEquals("雾夜档案", newShaReceiver.vault.getWorkSummary(newImport.targetWorkId)?.title)
        assertTrue(relayRequests.contains("GET /s/br_r2test01/book"))
        assertEquals(1, newShaReceiver.reader.listNoteLogEntries(newImport.targetWorkId, includeDeleted = false).size)
    }

    @Test
    fun `Channel 2 LAN direct server enforces 6-digit pairing code, supports Range, and executes SHA-256 handshake`() = runTest {
        val sender = createPeer("lan_tx", DeviceId("lan-tx"))
        val receiver = createPeer("lan_rx", DeviceId("lan-rx"))

        val bcBook = writeBcBook(tempFolder.newFile("B0055.bcbook"), "B0055", "局域网测试书", "e".repeat(64))
        sender.vault.ingestStudyPack(bcBook.absolutePath).getOrThrow()
        sender.reader.open(WorkId("B0055"), ReadingSurfaceMode.REFLOWABLE)
        sender.reader.saveAnnotation(
            WorkId("B0055"),
            sender.vault.resolveAnchor(WorkId("B0055"), ChapterOrdinal(2), BlockId("B01")),
            "GREEN",
            "LAN 共享批注",
        )

        val session = sender.share.startLanShareServer(
            workId = WorkId("B0055"),
            selection = ShareScopeSelection(includeBookFile = true),
            bindHost = "127.0.0.1",
            preferredPort = 0,
        ).getOrThrow()

        val hostBase = session.baseUrl.substringBefore("/share/v1")
        val client = OkHttpClient()
        client.newCall(Request.Builder().url("$hostBase/share/v1/manifest").header("X-BanRead-Code", "000000").build())
            .execute().use { assertEquals(401, it.code) }

        val rawCode = session.pairingCode.replace("-", "")
        client.newCall(
            Request.Builder()
                .url("$hostBase/share/v1/book")
                .header("X-BanRead-Code", rawCode)
                .header("Range", "bytes=0-31")
                .build(),
        ).execute().use { resp ->
            assertEquals(206, resp.code)
            assertEquals(32, resp.body!!.bytes().size)
        }

        val preview = receiver.share.previewIncomingShare(session.baseUrl).getOrThrow()
        assertEquals(ShareChannel.LAN_DIRECT, preview.channel)
        assertFalse(preview.willSkipBookDownload)

        val received = receiver.share.confirmReceiveShare(preview).getOrThrow()
        assertFalse(received.skippedBookBinary)
        assertEquals("局域网测试书", receiver.vault.getWorkSummary(received.targetWorkId)?.title)
        assertEquals(listOf("LAN 共享批注"), receiver.reader.listNoteLogEntries(received.targetWorkId).map { it.comment })

        sender.share.stopLanShareServer()
    }

    @Test
    fun `Channel 3 bcnote and bcshare ZIP round-trip handles lossless same-SHA merge and new-SHA full import`() = runTest {
        val sender = createPeer("zip_tx", DeviceId("zip-tx"))
        val sameReceiver = createPeer("zip_same", DeviceId("zip-same"))
        val newReceiver = createPeer("zip_new", DeviceId("zip-new"))

        val bcBook = writeBcBook(tempFolder.newFile("B0088.bcbook"), "B0088", "时间简史", "f".repeat(64))
        sender.vault.ingestStudyPack(bcBook.absolutePath).getOrThrow()
        sameReceiver.vault.ingestStudyPack(bcBook.absolutePath).getOrThrow()

        sender.reader.open(WorkId("B0088"), ReadingSurfaceMode.REFLOWABLE)
        val anchor = sender.vault.resolveAnchor(WorkId("B0088"), ChapterOrdinal(3), BlockId("B01"))
        sender.reader.saveAnnotation(WorkId("B0088"), anchor, "PURPLE", "微信 .bcnote 批注")

        val bcnoteRes = sender.share.exportOfflinePackage(
            workId = WorkId("B0088"),
            selection = ShareScopeSelection(includeMaps = true, includeNotes = true, includeQa = true),
            exportAsBcShare = false,
        ).getOrThrow()
        val bcnoteFile = File(bcnoteRes.packageFilePath!!)
        assertTrue(bcnoteFile.name.endsWith(".bcnote"))
        assertTrue(bcnoteFile.length() < 64 * 1024)

        val notePreview = sameReceiver.share.previewIncomingShare(bcnoteFile.absolutePath).getOrThrow()
        assertEquals(ShareChannel.OFFLINE_PACKAGE, notePreview.channel)
        assertTrue(notePreview.willSkipBookDownload)
        val noteImport = sameReceiver.share.confirmReceiveShare(notePreview).getOrThrow()
        assertTrue(noteImport.skippedBookBinary)
        assertEquals(1, noteImport.importedNotes)

        val bcshareRes = sender.share.exportOfflinePackage(
            workId = WorkId("B0088"),
            selection = ShareScopeSelection(includeMaps = true, includeNotes = true, includeQa = true),
            exportAsBcShare = true,
        ).getOrThrow()
        val bcshareFile = File(bcshareRes.packageFilePath!!)
        assertTrue(bcshareFile.name.endsWith(".bcshare"))

        val sharePreview = newReceiver.share.previewIncomingShare(bcshareFile.absolutePath).getOrThrow()
        assertFalse(sharePreview.willSkipBookDownload)
        val shareImport = newReceiver.share.confirmReceiveShare(sharePreview).getOrThrow()
        assertFalse(shareImport.skippedBookBinary)
        assertEquals("时间简史", newReceiver.vault.getWorkSummary(shareImport.targetWorkId)?.title)
        assertEquals(listOf("微信 .bcnote 批注"), newReceiver.reader.listNoteLogEntries(shareImport.targetWorkId).map { it.comment })
    }

    private fun createPeer(dirName: String, deviceId: DeviceId): PeerHarness {
        val root = tempFolder.newFolder(dirName)
        val vault = LibraryVaultFactory.create(context, root, registry, allowInMemory = true).also { closeables += it as Closeable }
        val reader = ReaderEngineFactory.create(
            context, root, vault, NoOpRenderer(), deviceId, allowInMemory = true,
        ).also { closeables += it as Closeable }
        val ai = AiRuntimeFactory.createInMemory(configDir = File(root, "ai"))
        val companion = CompanionEngineFactory.create(
            vault, ai, registry, null, context, root, allowInMemoryDb = true,
        ).also { closeables += it as Closeable }
        val share = SyncCoordinatorFactory.createShareCoordinator(
            context, root, vault, reader, companion, InMemorySecretVaultAdapter(), deviceId,
        ).also { closeables += it }
        return PeerHarness(vault, reader, companion, share)
    }

    private fun writeBcBook(dest: File, uid: String, title: String, bookSourceSha: String): File {
        val chapters = (1..4).map { ord ->
            val n = ord.toString().padStart(2, '0')
            "${n}_ch.md" to "# 第 $ord 章\n\n第 $ord 章测试文本内容。\n"
        }
        val entries = chapters.mapIndexed { idx, (name, text) ->
            val ord = idx + 1
            LogicalChapterEntry(
                ordinal = ChapterOrdinal(ord),
                n = ord.toString().padStart(2, '0'),
                title = "第 $ord 章",
                chapterRole = ChapterRole.BODY,
                merged = false,
                sourceFiles = listOf(name),
                chapterContentSha256 = ChapterContentHash(sha256Hex(text.toByteArray())),
                chars = text.length,
            )
        }
        val manifest = BcBookManifest(
            schemaVersion = 1,
            uid = WorkId(uid),
            title = title,
            author = "作者",
            subjects = listOf("科学"),
            readType = ReadProfileId("science"),
            bookSourceSha256 = BookSourceHash(bookSourceSha),
            clothColor = "#1D334A",
            logicalChapters = entries,
        )
        ZipOutputStream(FileOutputStream(dest)).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(json.encodeToString(BcBookManifest.serializer(), manifest).toByteArray())
            zos.closeEntry()
            for ((name, text) in chapters) {
                zos.putNextEntry(ZipEntry("md/$name"))
                zos.write(text.toByteArray())
                zos.closeEntry()
            }
        }
        return dest
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class PeerHarness(
        val vault: LibraryVault,
        val reader: ReaderEngine,
        val companion: CompanionEngine,
        val share: ShareCoordinator,
    )

    private class NoOpRenderer : BookRendererPort {
        override val locationEvents: Flow<ReadingAnchor> = MutableSharedFlow()
        override suspend fun load(compiledAssetRoot: String, initialAnchor: ReadingAnchor?) = Unit
        override suspend fun navigate(anchor: ReadingAnchor) = Unit
        override suspend fun updateStyles(cssPayload: String) = Unit
        override suspend fun renderDecorations(annotations: List<NoteLogEntry>) = Unit
    }
}
