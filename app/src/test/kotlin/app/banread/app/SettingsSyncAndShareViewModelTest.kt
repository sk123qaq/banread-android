package app.banread.app

import app.banread.ai.AiRuntimeFactory
import app.banread.ai.InMemorySecretVaultAdapter
import app.banread.app.data.InMemoryAppPreferencesStore
import app.banread.app.ui.pdf.PdfReaderViewModel
import app.banread.app.ui.reader.ReflowReaderViewModel
import app.banread.app.ui.settings.SettingsViewModel
import app.banread.model.BcBookManifest
import app.banread.model.BlockId
import app.banread.model.BookSourceHash
import app.banread.model.ChapterContentHash
import app.banread.model.ChapterOrdinal
import app.banread.model.ChapterRole
import app.banread.model.CrossBookHit
import app.banread.model.DesktopBookSummary
import app.banread.model.DesktopEndpointInfo
import app.banread.model.DeviceId
import app.banread.model.LogicalChapterEntry
import app.banread.model.OutboxTicket
import app.banread.model.ReadProfileId
import app.banread.model.ReadingAnchor
import app.banread.model.RemoteCursorBanner
import app.banread.model.SyncCoordinator
import app.banread.model.SyncMergeOutcome
import app.banread.model.TextQuote
import app.banread.model.WorkId
import app.banread.sync.CloudSyncBackend
import app.banread.sync.InMemoryDesktopGatewayAdapter
import app.banread.sync.InMemoryStateSyncAdapter
import app.banread.sync.ShareChannel
import app.banread.sync.SyncCoordinatorFactory
import app.banread.sync.WebDavConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsSyncAndShareViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { encodeDefaults = true }

    @Test
    fun `SettingsViewModel pairs desktop, checks health, pulls outbox, configures cloud shard, and drives 3-channel share`() = runTest {
        val workId = WorkId("B0001")
        val workSha = "a".repeat(64)
        val filesRoot = tempFolder.newFolder("app_sync_files")
        val bookFile = tempFolder.newFile("B0001.bcbook").also {
            writeSampleBcBook(it, "B0001", "史记选读", workSha)
        }

        val vault = FakeLibraryVault().apply {
            worksFlow.value = listOf(
                sampleWorkSummary("B0001", "史记选读", profileId = "history", format = "BCBOOK")
                    .copy(bookSourceSha256 = workSha),
            )
            ingestPackHandler = { _ ->
                val summary = sampleWorkSummary("B0001", "史记选读", profileId = "history", format = "BCBOOK")
                Result.success(
                    app.banread.model.ImportedWork(
                        workId = WorkId("B0001"),
                        title = summary.title,
                        author = summary.author,
                        readProfileId = summary.readProfileId,
                        literatureSubKind = summary.literatureSubKind,
                        clothColor = summary.clothColor,
                        supportedModes = summary.supportedModes,
                        bookSourceSha256 = workSha,
                    ),
                )
            }
        }
        val fakeVaultWithFile = object : app.banread.model.LibraryVault by vault {
            override suspend fun resolveOriginalBookFile(workId: WorkId): File? =
                if (workId.raw == "B0001") bookFile else null
        }
        val reader = FakeReaderEngine()
        val companion = FakeCompanionEngine(loadTestProfileRegistry())
        val secretVault = InMemorySecretVaultAdapter()
        val desktopGw = InMemoryDesktopGatewayAdapter(expectedToken = "tok-vm").apply {
            catalog += DesktopBookSummary(
                uid = workId,
                title = "史记选读",
                author = "司马迁",
                readType = ReadProfileId("history"),
                clothColor = "#A87A3E",
                logicalChapterCount = 2,
                hasCompanionCache = true,
                canonicalVersion = 1,
            )
            outbox += OutboxTicket("tkt-vm-1", workId, includeCache = true, createdAt = "2026-11-28T09:00:00Z")
            bundlesByWorkId["B0001"] = bookFile.readBytes()
        }
        val cloudSync = InMemoryStateSyncAdapter()
        val syncCoord = SyncCoordinatorFactory.createForTest(
            filesRoot = filesRoot,
            libraryVault = fakeVaultWithFile,
            readerEngine = reader,
            companionEngine = companion,
            secretVault = secretVault,
            deviceId = DeviceId("vm-phone"),
            desktopBridge = desktopGw,
            stateSyncPort = cloudSync,
            ioDispatcher = mainRule.dispatcher,
        )
        val shareCoord = SyncCoordinatorFactory.createShareCoordinator(
            context = null,
            filesRoot = filesRoot,
            libraryVault = fakeVaultWithFile,
            readerEngine = reader,
            companionEngine = companion,
            secretVault = secretVault,
            deviceId = DeviceId("vm-phone"),
            ioDispatcher = mainRule.dispatcher,
        )

        val vm = SettingsViewModel(
            aiRuntime = AiRuntimeFactory.createInMemory(),
            fontManager = null,
            appPreferences = InMemoryAppPreferencesStore(),
            syncCoordinator = syncCoord,
            shareCoordinator = shareCoord,
            libraryVault = fakeVaultWithFile,
        )

        // 1. Pair desktop + health check
        vm.updatePairUriInput("bc-pair://v1?base=http://127.0.0.1:8780&token=tok-vm")
        vm.pairDesktop()
        assertTrue(vm.uiState.value.syncStatus?.desktop?.isPaired == true)
        assertTrue(vm.uiState.value.syncStatus?.desktop?.isReachable == true)

        // 2. Catalog & Outbox pull with ACK
        vm.openDesktopCatalogDialog()
        assertEquals(1, vm.uiState.value.desktopSync.catalogBooks.size)
        vm.dismissDesktopCatalogDialog()

        vm.pullDesktopOutbox()
        assertEquals(listOf("tkt-vm-1"), desktopGw.ackedTicketIds.toList())

        // 3. Configure WebDAV cloud shard sync & sync bookshelf
        vm.openCloudConfigDialog()
        vm.updateCloudConfigFields {
            it.copy(
                selectedCloudBackend = CloudSyncBackend.WEBDAV,
                webDavUrlInput = "https://dav.example.com/banread",
                webDavUserInput = "alice",
                webDavSecretInput = "dav-secret-123",
            )
        }
        vm.saveCloudShardConfig()
        assertEquals(CloudSyncBackend.WEBDAV, vm.uiState.value.syncStatus?.cloudBackend)
        assertEquals("dav-secret-123", secretVault.getSecret("sync:cloud:webdav_secret"))
        // Secret input must be cleared from UI state after saving!
        assertEquals("", vm.uiState.value.desktopSync.webDavSecretInput)

        // 4. 3-Channel Share: export .bcnote, then parse and import in receiver dialog
        vm.openShareSendDialog(ShareChannel.OFFLINE_PACKAGE)
        vm.exportOfflinePackageNow()
        val exportedPath = vm.uiState.value.shareSend.exportedPackagePath!!
        assertTrue(exportedPath.endsWith(".bcnote"))
        vm.dismissShareSendDialog()

        vm.openShareReceiveDialog()
        vm.parseIncomingShare(exportedPath)
        val prev = vm.uiState.value.shareReceive.preview
        assertNotNull(prev)
        assertTrue(prev!!.willSkipBookDownload)
        vm.confirmIncomingShareImport()
        assertFalse(vm.uiState.value.shareReceive.isOpen)
        assertTrue(vm.uiState.value.lastShareLog.contains("跳过原书"))
    }

    @Test
    fun `ReflowReaderViewModel and PdfReaderViewModel surface non-intrusive RemoteCursorBanner with jump and ignore`() = runTest {
        val workId = WorkId("w_banner")
        val vault = FakeLibraryVault().apply {
            worksFlow.value = listOf(
                sampleWorkSummary("w_banner", "跨端横幅测试"),
            )
        }
        val remoteAnchor = ReadingAnchor.ReflowCfi(
            ordinal = ChapterOrdinal(10),
            href = "OEBPS/ch_10.xhtml",
            progression = 0.2f,
            totalProgression = 0.8f,
            cfi = "epubcfi(/6/20!/4/2)",
            textQuote = TextQuote("", "远程段落", ""),
            blockId = BlockId("B04"),
        )
        val bannerCoord = object : SyncCoordinator {
            override suspend fun pairDesktopViaQr(qrPayload: String) = Result.success(
                DesktopEndpointInfo("http://127.0.0.1:8780", DeviceId("d1")),
            )
            override suspend fun fetchDesktopCatalog(query: String?, onlyWithCache: Boolean) = Result.success(emptyList<DesktopBookSummary>())
            override suspend fun fetchDesktopOutbox() = Result.success(emptyList<OutboxTicket>())
            override fun pullStudyPacks(workIds: List<WorkId>) = flowOf<app.banread.model.BundleTransferState>()
            override suspend fun reconcileWork(workId: WorkId) = Result.success(
                SyncMergeOutcome(
                    workId = workId,
                    mergedWatermark = ChapterOrdinal(10),
                    mergedNoteCount = 1,
                    remoteCursorBanner = RemoteCursorBanner(
                        sourceDeviceId = DeviceId("desktop-linux"),
                        remoteCursor = remoteAnchor,
                        message = "desktop-linux 刚读至第 10 章 B04",
                    ),
                ),
            )
            override suspend fun queryCrossBookHits(workId: WorkId, queryText: String, sourcePassage: String?) =
                Result.success(emptyList<CrossBookHit>())
        }

        // Reflow reader shows banner WITHOUT force-jumping local cursor from Ch 2
        val reflowReader = FakeReaderEngine()
        val reflowVm = ReflowReaderViewModel(
            workId = workId,
            libraryVault = vault,
            readerEngine = reflowReader,
            surfaceController = FakeReflowSurfaceController(),
            fontManager = null,
            appPreferences = InMemoryAppPreferencesStore(),
            syncCoordinator = bannerCoord,
        )
        assertEquals(ChapterOrdinal(2), reflowReader.activeState.value.currentCursor?.ordinal)
        assertNotNull(reflowVm.uiState.value.remoteCursorBanner)
        reflowVm.jumpToRemoteCursor()
        assertNull(reflowVm.uiState.value.remoteCursorBanner)
        assertEquals(ChapterOrdinal(10), reflowReader.goToCalls.last().first.ordinal)

        // PDF reader shows banner and [忽略] dismisses without moving page
        val pdfReader = FakeReaderEngine()
        val pdfVm = PdfReaderViewModel(
            workId = workId,
            libraryVault = vault,
            readerEngine = pdfReader,
            pdfSurfaceController = FakePdfSurfaceController(),
            companionEngine = FakeCompanionEngine(loadTestProfileRegistry()),
            appPreferences = InMemoryAppPreferencesStore(),
            syncCoordinator = bannerCoord,
        )
        assertNotNull(pdfVm.uiState.value.remoteCursorBanner)
        val goToCountBefore = pdfReader.goToCalls.size
        pdfVm.dismissRemoteCursorBanner()
        assertNull(pdfVm.uiState.value.remoteCursorBanner)
        assertEquals(goToCountBefore, pdfReader.goToCalls.size)
    }

    private fun writeSampleBcBook(dest: File, uid: String, title: String, bookSourceSha: String) {
        val md = "# 第一章\n\n测试正文。\n".toByteArray()
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
                    sourceFiles = listOf("01_ch.md"),
                    chapterContentSha256 = ChapterContentHash(sha256Hex(md)),
                    chars = md.size,
                ),
            ),
        )
        ZipOutputStream(FileOutputStream(dest)).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(json.encodeToString(BcBookManifest.serializer(), manifest).toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("md/01_ch.md"))
            zos.write(md)
            zos.closeEntry()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
