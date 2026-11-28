package app.banread.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import app.banread.sync.internal.SyncPendingWorker
import app.banread.ai.InMemorySecretVaultAdapter
import app.banread.library.LibraryVaultFactory
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
import app.banread.model.LogicalChapterEntry
import app.banread.model.NoteLogEntry
import app.banread.model.ReadProfileId
import app.banread.model.ReadingAnchor
import app.banread.model.ReadingSurfaceMode
import app.banread.model.StateSyncPort
import app.banread.model.TextQuote
import app.banread.model.WorkId
import app.banread.model.registry.ProfileRegistry
import app.banread.reader.ReaderEngineFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MultiDeviceSyncAndWorkManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var registry: ProfileRegistry
    private val closeables = mutableListOf<Closeable>()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        registry = ProfileRegistry.loadFromClasspath()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().build(),
        )
    }

    @After
    fun tearDown() {
        closeables.forEach { runCatching { it.close() } }
        closeables.clear()
    }

    @Test
    fun `two phones and one desktop reconcile monotonically, propagate soft-delete tombstones, and show non-intrusive RemoteCursorBanner`() = runTest {
        val workId = WorkId("B0001")
        val bcBookFile = writeMultiChapterBcBook(tempFolder.newFile("B0001.bcbook"), "B0001", "明史纪事")

        val rootA = tempFolder.newFolder("phone_a")
        val vaultA = LibraryVaultFactory.create(context, rootA, registry, allowInMemory = true).also { closeables += it as Closeable }
        vaultA.ingestStudyPack(bcBookFile.absolutePath).getOrThrow()
        val readerA = ReaderEngineFactory.create(
            context, rootA, vaultA, NoOpRenderer(), DeviceId("phone-a"), allowInMemory = true,
        ).also { closeables += it as Closeable }

        val rootB = tempFolder.newFolder("phone_b")
        val vaultB = LibraryVaultFactory.create(context, rootB, registry, allowInMemory = true).also { closeables += it as Closeable }
        vaultB.ingestStudyPack(bcBookFile.absolutePath).getOrThrow()
        val readerB = ReaderEngineFactory.create(
            context, rootB, vaultB, NoOpRenderer(), DeviceId("phone-b"), allowInMemory = true,
        ).also { closeables += it as Closeable }

        val cloudSync = InMemoryStateSyncAdapter()
        val desktopGw = InMemoryDesktopGatewayAdapter(expectedToken = "tok-sync", isOnline = true)

        val coordA = SyncCoordinatorFactory.createForTest(
            rootA, vaultA, readerA, null, InMemorySecretVaultAdapter(), DeviceId("phone-a"), desktopGw, cloudSync,
        ).also { it.pairDesktopViaQr("bc-pair://v1?base=http://127.0.0.1:8780&token=tok-sync") }

        val coordB = SyncCoordinatorFactory.createForTest(
            rootB, vaultB, readerB, null, InMemorySecretVaultAdapter(), DeviceId("phone-b"), desktopGw, cloudSync,
        ).also { it.pairDesktopViaQr("bc-pair://v1?base=http://127.0.0.1:8780&token=tok-sync") }

        readerA.open(workId, ReadingSurfaceMode.REFLOWABLE)
        val anchorA12 = vaultA.resolveAnchor(workId, ChapterOrdinal(12), BlockId("B01"))
        readerA.goTo(anchorA12)
        readerA.saveAnnotation(workId, anchorA12, "YELLOW", "Phone A 第12章批注")

        readerB.open(workId, ReadingSurfaceMode.REFLOWABLE)
        val anchorB3 = vaultB.resolveAnchor(workId, ChapterOrdinal(3), BlockId("B01"))
        readerB.goTo(anchorB3)
        readerB.saveAnnotation(workId, anchorB3, "BLUE", "Phone B 保留批注")
        readerB.saveAnnotation(workId, anchorB3, "PINK", "Phone B 待删批注")

        coordA.reconcileWork(workId).getOrThrow()
        coordB.reconcileWork(workId).getOrThrow()

        val progressKeys = cloudSync.listShards("progress/B0001/")
        assertEquals(listOf("progress/B0001/phone-a.json", "progress/B0001/phone-b.json"), progressKeys)

        assertEquals(ChapterOrdinal(3), readerB.activeState.value.currentCursor?.ordinal)

        coordA.reconcileWork(workId).getOrThrow()
        val notesOnA = readerA.listNoteLogEntries(workId, includeDeleted = false)
        assertEquals(3, notesOnA.size)
        val targetToDelete = notesOnA.first { it.comment == "Phone B 待删批注" }
        readerA.deleteAnnotation(workId, targetToDelete.noteId)

        val desktopNewerTs = System.currentTimeMillis() + 60_000L
        desktopGw.pushProgressShard(
            "tok-sync",
            "http://127.0.0.1:8780",
            DeviceProgressShard(
                workId = workId,
                deviceId = DeviceId("desktop-linux"),
                cursor = ReadingAnchor.BlockOffset(ChapterOrdinal(2), BlockId("B01"), 0, 10, "翻查旧章"),
                updatedAtEpochMs = desktopNewerTs,
            ),
        )

        val outcomeA = coordA.reconcileWork(workId).getOrThrow()
        assertEquals(ChapterOrdinal(12), readerA.activeState.value.currentCursor?.ordinal)
        assertNotNull(outcomeA.remoteCursorBanner)
        assertEquals(DeviceId("desktop-linux"), outcomeA.remoteCursorBanner!!.sourceDeviceId)
        assertEquals(ChapterOrdinal(2), outcomeA.remoteCursorBanner!!.remoteCursor.ordinal)

        val outcomeB = coordB.reconcileWork(workId).getOrThrow()
        assertEquals(2, outcomeB.mergedNoteCount)
        val activeOnB = readerB.listNoteLogEntries(workId, includeDeleted = false)
        assertEquals(setOf("Phone A 第12章批注", "Phone B 保留批注"), activeOnB.map { it.comment }.toSet())

        val staleNote = targetToDelete.copy(
            deviceId = DeviceId("stale-tablet"),
            updatedAtEpochMs = targetToDelete.updatedAtEpochMs,
            deletedAtEpochMs = null,
        )
        cloudSync.putShard(
            "notes/B0001/stale-tablet.jsonl",
            (json.encodeToString(NoteLogEntry.serializer(), staleNote) + "\n").toByteArray(),
        )
        val outcomeBAfterStale = coordB.reconcileWork(workId).getOrThrow()
        assertEquals(2, outcomeBAfterStale.mergedNoteCount)
        assertFalse(readerB.listNoteLogEntries(workId, includeDeleted = false).any { it.noteId == targetToDelete.noteId })
    }

    @Test
    fun `offline reconcile failure enqueues persistent retry and drains cleanly via WorkManager on reconnect without packet loss`() = runTest {
        val workId = WorkId("B0001")
        val bcBookFile = writeMultiChapterBcBook(tempFolder.newFile("B0001_offline.bcbook"), "B0001", "离线重连测试")

        val root = tempFolder.newFolder("offline_phone")
        val vault = LibraryVaultFactory.create(context, root, registry, allowInMemory = true).also { closeables += it as Closeable }
        vault.ingestStudyPack(bcBookFile.absolutePath).getOrThrow()
        val reader = ReaderEngineFactory.create(
            context, root, vault, NoOpRenderer(), DeviceId("offline-phone"), allowInMemory = true,
        ).also { closeables += it as Closeable }

        reader.open(workId, ReadingSurfaceMode.REFLOWABLE)
        val anchor4 = vault.resolveAnchor(workId, ChapterOrdinal(4), BlockId("B01"))
        reader.goTo(anchor4)
        reader.saveAnnotation(workId, anchor4, "GREEN", "断网期间保存的笔记")

        val desktopGw = InMemoryDesktopGatewayAdapter(expectedToken = "tok-wm", isOnline = false)
        val cloudDown = object : StateSyncPort {
            var online = false
            val delegate = InMemoryStateSyncAdapter()
            override suspend fun putShard(relativePath: String, contentBytes: ByteArray) {
                if (!online) throw app.banread.model.AppFailureException(app.banread.model.AppFailure.NetworkOrSyncConflict("offline"))
                delegate.putShard(relativePath, contentBytes)
            }
            override suspend fun listShards(prefix: String): List<String> {
                if (!online) throw app.banread.model.AppFailureException(app.banread.model.AppFailure.NetworkOrSyncConflict("offline"))
                return delegate.listShards(prefix)
            }
            override suspend fun getShard(relativePath: String): ByteArray? {
                if (!online) throw app.banread.model.AppFailureException(app.banread.model.AppFailure.NetworkOrSyncConflict("offline"))
                return delegate.getShard(relativePath)
            }
        }

        val coord = SyncCoordinatorFactory.createForTest(
            root, vault, reader, null, InMemorySecretVaultAdapter(), DeviceId("offline-phone"), desktopGw, cloudDown,
        )
        coord.pairDesktopViaQr("bc-pair://v1?base=http://127.0.0.1:8780&token=tok-wm")

        val offlineAttempt = coord.reconcileWork(workId)
        assertTrue(offlineAttempt.isFailure)
        assertEquals(1, coord.getSyncStatus().pendingOpCount)

        SyncCoordinatorFactory.registerBackgroundSyncProvider { coord }
        val offlineWorker = TestListenableWorkerBuilder<SyncPendingWorker>(context).build()
        assertEquals(ListenableWorker.Result.retry(), offlineWorker.doWork())
        assertEquals(1, coord.getSyncStatus().pendingOpCount)

        cloudDown.online = true
        desktopGw.isOnline = true
        val onlineWorker = TestListenableWorkerBuilder<SyncPendingWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), onlineWorker.doWork())
        assertEquals(0, coord.getSyncStatus().pendingOpCount)
        assertTrue(cloudDown.delegate.listShards("progress/B0001/").contains("progress/B0001/offline-phone.json"))
        assertTrue(cloudDown.delegate.listShards("notes/B0001/").contains("notes/B0001/offline-phone.jsonl"))
        assertEquals(ChapterOrdinal(4), desktopGw.fetchProgressShards("tok-wm", "http://127.0.0.1:8780", workId).single().cursor.ordinal)
    }

    @Test
    fun `pulling bcbook matching already-imported local EPUB preserves annotations and cursor without duplicating work`() = runTest {
        val epubFile = buildMinimalEpub(tempFolder.newFile("local.epub"), title = "万历十五年", author = "黄仁宇")
        val epubSha = sha256Hex(epubFile.readBytes())

        val root = tempFolder.newFolder("upgrade_phone")
        val vault = LibraryVaultFactory.create(context, root, registry, allowInMemory = true).also { closeables += it as Closeable }
        val localImported = vault.importLocalBook(epubFile.absolutePath).getOrThrow()
        assertTrue(localImported.workId.raw.startsWith("local_"))

        val reader = ReaderEngineFactory.create(
            context, root, vault, NoOpRenderer(), DeviceId("upgrade-phone"), allowInMemory = true,
        ).also { closeables += it as Closeable }
        reader.open(localImported.workId, ReadingSurfaceMode.REFLOWABLE)
        val localAnchor = vault.resolveAnchor(localImported.workId, ChapterOrdinal(1), BlockId("B01"))
        reader.saveAnnotation(localImported.workId, localAnchor, "YELLOW", "本地EPUB旧高亮")

        val bcBookFile = writeMultiChapterBcBook(
            tempFolder.newFile("B0777.bcbook"),
            uid = "B0777",
            title = "万历十五年",
            bookSourceSha256 = epubSha,
        )
        val desktopGw = InMemoryDesktopGatewayAdapter(expectedToken = "tok-up", isOnline = true).apply {
            bundlesByWorkId["B0777"] = bcBookFile.readBytes()
        }
        val coord = SyncCoordinatorFactory.createForTest(
            root, vault, reader, null, InMemorySecretVaultAdapter(), DeviceId("upgrade-phone"), desktopGw,
        )
        coord.pairDesktopViaQr("bc-pair://v1?base=http://127.0.0.1:8780&token=tok-up")

        val pullStates = mutableListOf<BundleTransferState>()
        coord.pullStudyPacks(listOf(WorkId("B0777"))).collect { pullStates += it }
        assertTrue(pullStates.last() is BundleTransferState.Ingested)

        val allWorks = vault.listWorks()
        assertEquals(1, allWorks.size)
        assertEquals(WorkId("B0777"), allWorks.single().workId)
        assertNotNull(reader.snapshotProgress(WorkId("B0777")))
        val migratedNotes = reader.listNoteLogEntries(WorkId("B0777"), includeDeleted = false)
        assertEquals(listOf("本地EPUB旧高亮"), migratedNotes.map { it.comment })
        assertNull(vault.getWorkSummary(localImported.workId))
    }

    private fun writeMultiChapterBcBook(
        dest: File,
        uid: String,
        title: String,
        bookSourceSha256: String = "a".repeat(64),
    ): File {
        val chapters = (1..12).map { ord ->
            val n = ord.toString().padStart(2, '0')
            val name = "${n}_第${ord}章.md"
            val text = "# 第 ${ord} 章\n\n第 ${ord} 章第一段正文内容。\n"
            name to text
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
            author = "黄仁宇",
            subjects = listOf("历史"),
            readType = ReadProfileId("history"),
            bookSourceSha256 = BookSourceHash(bookSourceSha256),
            clothColor = "#A87A3E",
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

    private fun buildMinimalEpub(dest: File, title: String, author: String): File {
        ZipOutputStream(FileOutputStream(dest)).use { zos ->
            zos.putNextEntry(ZipEntry("mimetype"))
            zos.write("application/epub+zip".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
            )
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>$title</dc:title><dc:creator>$author</dc:creator><dc:subject>历史</dc:subject></metadata><manifest><item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="ch1"/></spine></package>""".toByteArray(),
            )
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("OEBPS/ch1.xhtml"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>第一章</title></head><body><h1>第一章</h1><p>万历十五年，海瑞辞官归里。</p></body></html>""".toByteArray(),
            )
            zos.closeEntry()
        }
        return dest
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class NoOpRenderer : BookRendererPort {
        override val locationEvents: Flow<ReadingAnchor> = MutableSharedFlow()
        override suspend fun load(compiledAssetRoot: String, initialAnchor: ReadingAnchor?) = Unit
        override suspend fun navigate(anchor: ReadingAnchor) = Unit
        override suspend fun updateStyles(cssPayload: String) = Unit
        override suspend fun renderDecorations(annotations: List<NoteLogEntry>) = Unit
    }
}
