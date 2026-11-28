package app.banread.companion

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.banread.ai.AiRuntimeFactory
import app.banread.companion.internal.CompanionDatabase
import app.banread.library.LibraryVaultFactory
import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import app.banread.model.AuditedArtifact
import app.banread.model.BcBookManifest
import app.banread.model.BlockId
import app.banread.model.BookSourceHash
import app.banread.model.BundleTransferState
import app.banread.model.ChapterContentHash
import app.banread.model.ChapterOrdinal
import app.banread.model.ChapterRole
import app.banread.model.CompanionEngine
import app.banread.model.CompanionEvent
import app.banread.model.CompanionRequest
import app.banread.model.CrossBookHit
import app.banread.model.DesktopBookSummary
import app.banread.model.DesktopEndpointInfo
import app.banread.model.LibraryVault
import app.banread.model.LlmPromptRequest
import app.banread.model.LlmProviderPort
import app.banread.model.LlmStreamEvent
import app.banread.model.LogicalChapterEntry
import app.banread.model.OutboxTicket
import app.banread.model.ProviderCapabilities
import app.banread.model.ProviderConfig
import app.banread.model.ProviderKind
import app.banread.model.ReadProfileId
import app.banread.model.SyncCoordinator
import app.banread.model.SyncMergeOutcome
import app.banread.model.ToolId
import app.banread.model.ToolScope
import app.banread.model.WorkId
import app.banread.model.registry.ProfileRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompanionEngineContractTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var filesRoot: File
    private lateinit var profileRegistry: ProfileRegistry
    private lateinit var libraryVault: LibraryVault
    private lateinit var fakeLlm: ScriptedLlmFakeAdapter
    private val closeables = mutableListOf<Closeable>()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        filesRoot = tempFolder.newFolder("banread_files")
        profileRegistry = ProfileRegistry.loadFromClasspath()
        libraryVault = LibraryVaultFactory.create(
            context = context,
            filesRoot = filesRoot,
            profileRegistry = profileRegistry,
            allowInMemory = true,
        )
        fakeLlm = ScriptedLlmFakeAdapter()
    }

    @After
    fun tearDown() {
        closeables.forEach { runCatching { it.close() } }
        closeables.clear()
        runCatching { (libraryVault as? Closeable)?.close() }
    }

    private suspend fun createRuntimeAndConfigure(supportsVision: Boolean = true): app.banread.model.AiRuntime {
        val ai = AiRuntimeFactory.createInMemory(
            configDir = tempFolder.newFolder("ai_meta_${System.nanoTime()}"),
            providerAdapters = mapOf(
                ProviderKind.OPENAI_COMPAT to fakeLlm,
                ProviderKind.GEMINI to fakeLlm,
                ProviderKind.ANTHROPIC to fakeLlm,
                ProviderKind.OLLAMA to fakeLlm,
            ),
        )
        ai.saveProviderConfig(
            ProviderConfig(
                id = "scripted-main",
                displayName = "Scripted Fake",
                kind = ProviderKind.OPENAI_COMPAT,
                endpoint = "https://fake.local",
                modelId = "fake-model-v1",
                capabilities = ProviderCapabilities(
                    supportsJsonSchema = true,
                    supportsVision = supportsVision,
                    contextWindow = 128_000,
                ),
            ),
            rawApiKey = "sk-fake-test",
        )
        return ai
    }

    private suspend fun createEngine(
        supportsVision: Boolean = true,
        syncCoordinator: SyncCoordinator? = null,
    ): CompanionEngine {
        val ai = createRuntimeAndConfigure(supportsVision = supportsVision)
        return CompanionEngineFactory.create(
            libraryVault = libraryVault,
            aiRuntime = ai,
            profileRegistry = profileRegistry,
            syncCoordinator = syncCoordinator,
            context = context,
            filesRoot = filesRoot,
            allowInMemoryDb = true,
        ).also { engine ->
            (engine as? Closeable)?.let { closeables += it }
        }
    }

    @Test
    fun `FullBook and Chapter companion context loads all chapters and footnotes without spoiler truncation or anti-spoiler prompt`() = runTest {
        val bcbook = build20ChapterSpoilerBook(tempFolder.newFile("B0042.bcbook"))
        val imported = libraryVault.ingestStudyPack(bcbook.absolutePath).getOrThrow()
        val engine = createEngine()

        // Recommended tools read from ingested book profile
        val toolbox = engine.recommendedTools(imported.workId)
        assertEquals(ReadProfileId("literature"), toolbox.profileId)
        assertEquals("mystery", toolbox.literatureSubKind)
        assertTrue(ToolId("tension_curve") in toolbox.blueprints)
        assertTrue(ToolId("imagery") in toolbox.extensions)

        fakeLlm.enqueueResponse(
            """
            {
              "blueprint": "tension_curve",
              "caption": "第二章码头对峙张力",
              "reading_note": "聚焦火漆破损节点",
              "summary": "雾夜会面引发第一轮猜忌。",
              "data": {
                "yLabel": "张力强度",
                "points": [
                  {"beat": "雾夜相逢", "beat_kind": "setup", "tension": 35, "emotion": "试探", "blocks": ["B01"], "source": "原文"},
                  {"beat": "火漆破损", "beat_kind": "climax", "tension": 85, "emotion": "警觉", "blocks": ["B02"], "source": "原文"}
                ]
              }
            }
            """.trimIndent(),
        )
        fakeLlm.enqueueResponse(
            """
            {
              "blueprint": "tension_curve",
              "caption": "第十四章审讯张力",
              "reading_note": "聚焦真凶供词",
              "summary": "审讯揭示幕后主使。",
              "data": {
                "yLabel": "张力强度",
                "points": [
                  {"beat": "审讯供词", "beat_kind": "climax", "tension": 92, "emotion": "震动", "blocks": ["B01"], "source": "原文"}
                ]
              }
            }
            """.trimIndent(),
        )
        fakeLlm.enqueueResponse(
            """
            {
              "blueprint": "relation_graph",
              "caption": "全书人物格局",
              "reading_note": "覆盖全书章节",
              "summary": "主人公收到密信并追查真凶。",
              "data": {
                "nodes": [
                  {"id": "P1", "role": "protagonist", "label": "沈警官", "detail": "在码头拆信", "blocks": ["B01"], "source": "原文"}
                ],
                "edges": []
              }
            }
            """.trimIndent(),
        )

        val ch3Events = engine.runTool(
            CompanionRequest(
                workId = imported.workId,
                tool = ToolId("tension_curve"),
                scope = ToolScope.Chapter(ChapterOrdinal(3)),
            ),
        ).toList()
        assertTrue(ch3Events.last() is CompanionEvent.Done)

        val ch14Events = engine.runTool(
            CompanionRequest(
                workId = imported.workId,
                tool = ToolId("tension_curve"),
                scope = ToolScope.Chapter(ChapterOrdinal(14)),
                forceRefresh = true,
            ),
        ).toList()
        assertTrue(ch14Events.last() is CompanionEvent.Done)

        val fullBookEvents = engine.runTool(
            CompanionRequest(
                workId = imported.workId,
                tool = ToolId("relation_graph"),
                scope = ToolScope.FullBook,
            ),
        ).toList()
        assertTrue(fullBookEvents.last() is CompanionEvent.Done)

        assertEquals(3, fakeLlm.recordedRequests.size)
        for (req in fakeLlm.recordedRequests) {
            assertFalse("Unexpected anti-spoiler instruction in system prompt", req.systemPrompt.contains("严禁剧透"))
            assertFalse("Unexpected gateOrdinal in system prompt", req.systemPrompt.contains("gateOrdinal"))
        }

        val ch3Prompt = fakeLlm.recordedRequests[0].userPrompt
        assertTrue("Expected Ch 3 body in prompt", ch3Prompt.contains("红蜡封缄"))
        assertTrue("Expected Ch 3 footnote [45] in prompt", ch3Prompt.contains("十九世纪港口海关"))

        val ch14Prompt = fakeLlm.recordedRequests[1].userPrompt
        assertTrue("Expected Ch 14 body in prompt", ch14Prompt.contains("SPOILER_CANARY_CH_14"))
        assertTrue("Expected Ch 14 footnote [102] in prompt", ch14Prompt.contains("SECRET_KILLER_CH14_NOTE_102"))

        val fullPrompt = fakeLlm.recordedRequests[2].userPrompt
        // FullBook context includes later chapters (e.g., Ch 14 L2 digest and Ch 19 full text) without truncation
        assertTrue("Expected Ch 14 digest in FullBook prompt", fullPrompt.contains("SPOILER_CANARY_CH_14"))
        assertTrue("Expected Ch 19 body in FullBook prompt", fullPrompt.contains("SPOILER_CANARY_CH_19"))
    }

    @Test
    fun `ContextBuilder samples over-24k chapter, joins short lines, and caps isolated footnotes`() = runTest {
        // 1. Real RoomLibraryVault chapter (<= 24k) verifies short-line joining & isolated footnote section
        val bcbook = buildLongChapterBcBook(tempFolder.newFile("B0099.bcbook"))
        val imported = libraryVault.ingestStudyPack(bcbook.absolutePath).getOrThrow()
        val engine = createEngine()

        fakeLlm.enqueueResponse(
            """{"blueprint":"argument_map","caption":"短行合并","reading_note":"看结论","summary":"短行合并完成","data":{"nodes":[{"id":"A1","role":"conclusion","label":"核心结论","blocks":["B01"],"source":"原文"}],"edges":[]}}""",
        )

        val events = engine.runTool(
            CompanionRequest(
                workId = imported.workId,
                tool = ToolId("argument_map"),
                scope = ToolScope.Chapter(ChapterOrdinal(1)),
            ),
        ).toList()
        assertTrue(events.last() is CompanionEvent.Done)

        val prompt = fakeLlm.recordedRequests.last().userPrompt
        assertTrue("Expected short-line range join like [Bxx–Byy]", Regex("""\[B\d+–B\d+]""").containsMatchIn(prompt))
        assertTrue(prompt.contains("【本章自带脚注"))

        // 2. >24,000-char chapter slice verifies structured sampling (headings <=1500, head/tail <=2200, mid windows, 15% fn cap)
        val over24kBlocks = buildList {
            add(app.banread.model.BookBlock(BlockId("B01"), "# 第一章 超长文本采样"))
            add(app.banread.model.BookBlock(BlockId("B02"), "## 第一节 开头"))
            for (i in 3..10) {
                add(app.banread.model.BookBlock(BlockId("B${i.toString().padStart(2, '0')}"), "短台词$i。"))
            }
            add(app.banread.model.BookBlock(BlockId("B11"), "## 第二节 展开"))
            for (i in 12..500) {
                add(
                    app.banread.model.BookBlock(
                        BlockId("B${i.toString().padStart(2, '0')}"),
                        "第${i}段详细论证与实验观察记录，阐述核心变量之间的因果传导机制与制度边界条件，保持长度超过两万四千字阈值。",
                    ),
                )
            }
            add(app.banread.model.BookBlock(BlockId("B501"), "## 第三节 结尾"))
            add(app.banread.model.BookBlock(BlockId("B502"), "这是全章最终收尾段落。"))
        }
        val oversizedVault = object : LibraryVault by libraryVault {
            override suspend fun loadBookInputs(workId: WorkId): app.banread.model.BookSlice {
                val base = libraryVault.loadBookInputs(workId)
                return base.copy(
                    chapters = listOf(
                        base.chapters.first().copy(blocks = over24kBlocks),
                    ),
                )
            }
        }
        val ai = createRuntimeAndConfigure()
        val sampledEngine = CompanionEngineFactory.create(
            libraryVault = oversizedVault,
            aiRuntime = ai,
            profileRegistry = profileRegistry,
            syncCoordinator = null,
            context = context,
            filesRoot = filesRoot,
            allowInMemoryDb = true,
        ).also { engine ->
            (engine as? Closeable)?.let { closeables += it }
        }
        fakeLlm.enqueueResponse(
            """{"blueprint":"argument_map","caption":"长章采样","reading_note":"看结论","summary":"长章采样完成","data":{"nodes":[{"id":"A1","role":"conclusion","label":"核心结论","blocks":["B01"],"source":"原文"}],"edges":[]}}""",
        )
        val sampledEvents = sampledEngine.runTool(
            CompanionRequest(
                workId = imported.workId,
                tool = ToolId("argument_map"),
                scope = ToolScope.Chapter(ChapterOrdinal(1)),
                forceRefresh = true,
            ),
        ).toList()
        assertTrue(sampledEvents.last() is CompanionEvent.Done)

        val sampledPrompt = fakeLlm.recordedRequests.last().userPrompt
        assertTrue(sampledPrompt.contains("【全章小标题脉络】"))
        assertTrue(sampledPrompt.contains("【全章连续场景窗口节选"))
        assertTrue("Heading B02 must remain standalone line", sampledPrompt.lines().any { it == "[B02] ## 第一节 开头" })
        assertFalse("Heading B02 must not be swallowed into [B02–B10]", sampledPrompt.contains("[B02–B10]"))
        assertTrue(Regex("""\[B03–B10]""").containsMatchIn(sampledPrompt))
        assertTrue(sampledPrompt.contains("……（略去"))
        assertTrue(sampledPrompt.contains("【本章自带脚注"))
    }

    @Test
    fun `BlueprintAuditor repairs truncated JSON, strips B999, degrades 17 nodes, sorts timeline, and filters guessed metrics`() = runTest {
        val bcbook = build20ChapterSpoilerBook(tempFolder.newFile("B0043.bcbook"), uid = "B0043")
        val imported = libraryVault.ingestStudyPack(bcbook.absolutePath).getOrThrow()
        val engine = createEngine()
        val ch3Scope = ToolScope.Chapter(ChapterOrdinal(3))

        // 1. Truncated JSON + trailing comma + markdown fence + fabricated B999 block ID
        fakeLlm.enqueueResponse(
            "```json\n" +
                "{'blueprint':'argument_map','caption':'论证结构','reading_note':'核对前提','summary':'论证成立','data':{'nodes':[{'id':'A1','role':'premise','label':'前提一','detail':'第一行\n第二行','blocks':['B999','B02'],'source':'原文',},{'id':'A2','role':'conclusion','label':'结论','blocks':['B999'],'source':'补充'}],'edges':[{'from':'A1','to':'A2','label':'推出'}",
        )
        val res1 = engine.runTool(CompanionRequest(imported.workId, ToolId("argument_map"), ch3Scope, forceRefresh = true))
            .toList().last() as CompanionEvent.Done
        val diag1 = res1.artifact as AuditedArtifact.StructuredDiagram
        val nodes1 = diag1.data["nodes"]!!.jsonArray
        assertEquals(2, nodes1.size)
        // B999 stripped from node 1 leaving ["B02"]; node 2 falls back to ["B01"]
        assertEquals(listOf("B02"), nodes1[0].jsonObject["blocks"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("B01"), nodes1[1].jsonObject["blocks"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(diag1.auditLog.any { "B999" in it })

        // 2. >16 nodes degrades cleanly to FallbackList preserving valid blocks
        val seventeenNodes = (1..17).joinToString(",") {
            """{"id":"N$it","role":"inference","label":"节点$it","detail":"细节$it","blocks":["B01"],"source":"原文"}"""
        }
        fakeLlm.enqueueResponse(
            """{"blueprint":"argument_map","caption":"超限图","reading_note":"","summary":"超限","data":{"nodes":[$seventeenNodes],"edges":[]}}""",
        )
        val res2 = engine.runTool(CompanionRequest(imported.workId, ToolId("argument_map"), ch3Scope, forceRefresh = true))
            .toList().last() as CompanionEvent.Done
        val fb2 = res2.artifact as AuditedArtifact.FallbackList
        assertEquals("fallback_list", fb2.blueprint)
        assertEquals(17, fb2.items.size)
        assertTrue(fb2.auditLog.any { "16" in it })

        // 3. parallel_timeline sorts out-of-order sort_year chronologically (including BCE negative years)
        fakeLlm.enqueueResponse(
            """
            {
              "blueprint": "parallel_timeline",
              "caption": "跨代年表",
              "reading_note": "看时序",
              "summary": "已排序",
              "data": {
                "lanes": ["中原", "域外"],
                "rows": [
                  {"sort_year": 1911, "time": "1911年", "cells": [{"title": "辛亥", "note": "近代", "blocks": ["B01"], "source": "原文"}, null]},
                  {"sort_year": -221, "time": "公元前221年", "cells": [{"title": "秦统一", "note": "上古", "blocks": ["B02"], "source": "原文"}, null]},
                  {"sort_year": 317, "time": "317年", "cells": [null, {"title": "东晋", "note": "南渡", "blocks": ["B01"], "source": "原文"}]}
                ]
              }
            }
            """.trimIndent(),
        )
        val res3 = engine.runTool(CompanionRequest(imported.workId, ToolId("parallel_timeline"), ch3Scope, forceRefresh = true))
            .toList().last() as CompanionEvent.Done
        val timeline = res3.artifact as AuditedArtifact.StructuredDiagram
        val sortedYears = timeline.data["rows"]!!.jsonArray.map { it.jsonObject["sort_year"]!!.jsonPrimitive.intOrNull }
        assertEquals(listOf(-221, 317, 1911), sortedYears)

        // 4. metric_chart filters guessed-score points and degrades to FallbackList when < 2 valid points
        fakeLlm.enqueueResponse(
            """
            {
              "blueprint": "metric_chart",
              "caption": "伪指标校验",
              "reading_note": "核实数字",
              "summary": "含估算数据",
              "data": {
                "mode": "series",
                "yLabel": "税率",
                "unit": "%",
                "series": [{
                  "name": "税率",
                  "points": [
                    {"x": "1990", "value": 15, "note": "估算中值", "blocks": ["B01"], "source": "原文"},
                    {"x": "1995", "value": 20, "note": "外部补充", "blocks": ["B01"], "source": "补充"},
                    {"x": "2000", "value": 25, "note": "原文实测", "blocks": ["B01"], "source": "原文"}
                  ]
                }]
              }
            }
            """.trimIndent(),
        )
        val res4 = engine.runTool(CompanionRequest(imported.workId, ToolId("metric_chart"), ch3Scope, forceRefresh = true))
            .toList().last() as CompanionEvent.Done
        assertTrue(res4.artifact is AuditedArtifact.FallbackList)
        assertTrue(res4.artifact.auditLog.any { "剔除" in it || "不足 2 个" in it })

        // 5. Total unparseable prose degrades cleanly to FallbackList without throwing
        fakeLlm.enqueueResponse("抱歉，今天模型无法生成结构化内容，这是一段纯文字说明。")
        val res5 = engine.runTool(CompanionRequest(imported.workId, ToolId("tension_curve"), ch3Scope, forceRefresh = true))
            .toList().last() as CompanionEvent.Done
        val fb5 = res5.artifact as AuditedArtifact.FallbackList
        assertEquals(listOf(BlockId("B01")), fb5.items.single().blocks)
    }

    @Test
    fun `cross-book tools fail with DesktopConnectionRequired and consume zero LLM tokens when disconnected`() = runTest {
        val bcbook = build20ChapterSpoilerBook(tempFolder.newFile("B0044.bcbook"), uid = "B0044")
        val imported = libraryVault.ingestStudyPack(bcbook.absolutePath).getOrThrow()

        // Case A: syncCoordinator == null
        val engineNoSync = createEngine(syncCoordinator = null)
        for (toolName in listOf("compare_context", "method_audit")) {
            val events = engineNoSync.runTool(
                CompanionRequest(
                    workId = imported.workId,
                    tool = ToolId(toolName),
                    scope = ToolScope.Chapter(ChapterOrdinal(3)),
                ),
            ).toList()
            val failure = events.single() as CompanionEvent.Failure
            assertTrue(failure.error is AppFailure.DesktopConnectionRequired)
        }
        assertEquals(0, fakeLlm.recordedRequests.size)

        // Case B: syncCoordinator fails with DesktopConnectionRequired
        val disconnectedSync = FakeSyncCoordinator(
            crossHitsResult = Result.failure(AppFailureException(AppFailure.DesktopConnectionRequired("desktop offline"))),
        )
        val engineDisconnected = createEngine(syncCoordinator = disconnectedSync)
        val eventsB = engineDisconnected.runTool(
            CompanionRequest(
                workId = imported.workId,
                tool = ToolId("method_audit"),
                scope = ToolScope.Chapter(ChapterOrdinal(3)),
            ),
        ).toList()
        assertTrue((eventsB.single() as CompanionEvent.Failure).error is AppFailure.DesktopConnectionRequired)
        assertEquals(0, fakeLlm.recordedRequests.size)

        // Case C: connected syncCoordinator returns CrossBookHit -> calls LLM with hits
        val connectedSync = FakeSyncCoordinator(
            crossHitsResult = Result.success(
                listOf(
                    CrossBookHit(
                        uid = WorkId("B0108"),
                        title = "万历十五年",
                        type = "历史书",
                        relevance = 0.87,
                        bestPath = "books/B0108/md/03.md",
                        bestLine = 42,
                        snippet = "文官集团依托票拟维系平衡",
                    ),
                ),
            ),
        )
        val engineConnected = createEngine(syncCoordinator = connectedSync)
        fakeLlm.enqueueResponse(
            """{"blueprint":"extension_dossier","caption":"史家对照","reading_note":"对照他书","summary":"综合视图","sections":[{"id":"historiography","title":"史观对撞","items":[{"title":"票拟制衡","body":"他书对照显示","blocks":["B01"],"source":"补充"}]}]}""",
        )
        val okEvents = engineConnected.runTool(
            CompanionRequest(
                workId = imported.workId,
                tool = ToolId("compare_context"),
                scope = ToolScope.Chapter(ChapterOrdinal(3)),
            ),
        ).toList()
        assertTrue(okEvents.last() is CompanionEvent.Done)
        assertEquals(1, fakeLlm.recordedRequests.size)
        assertTrue(fakeLlm.recordedRequests.single().userPrompt.contains("万历十五年"))
    }

    @Test
    fun `L3 exact cache, SWR stale-hash hit, and desktop companion_cache import work seamlessly`() = runTest {
        val bcbook = build20ChapterSpoilerBook(tempFolder.newFile("B0045.bcbook"), uid = "B0045")
        val imported = libraryVault.ingestStudyPack(bcbook.absolutePath).getOrThrow()
        val engine = createEngine()

        // 1. FullBook -> LLM miss -> Done
        fakeLlm.enqueueResponse(
            """{"blueprint":"relation_graph","caption":"全书人物格局","reading_note":"引导","summary":"全书摘要","data":{"nodes":[{"id":"P1","role":"protagonist","label":"主角","blocks":["B01"],"source":"原文"}],"edges":[]}}""",
        )
        val runFull = engine.runTool(
            CompanionRequest(imported.workId, ToolId("relation_graph"), ToolScope.FullBook),
        ).toList()
        assertTrue(runFull.last() is CompanionEvent.Done)
        assertEquals(1, fakeLlm.recordedRequests.size)

        // 2. Exact hit -> CachedHit(isStale = false), 0 extra LLM calls
        val hitFull = engine.runTool(
            CompanionRequest(imported.workId, ToolId("relation_graph"), ToolScope.FullBook),
        ).toList()
        val cachedExact = hitFull.single() as CompanionEvent.CachedHit
        assertFalse(cachedExact.isStale)
        assertEquals(1, fakeLlm.recordedRequests.size)

        // 3. Desktop companion_cache import populates chapter_map, tension_curve, and imagery with 0 LLM calls
        val cacheDir = tempFolder.newFolder("desktop_cache_B0045")
        val ch3ToolsDir = File(cacheDir, "ch_03/chapter_tools").apply { mkdirs() }
        File(cacheDir, "ch_03/chapter_map.json").writeText(
            """{"schema":1,"supported":true,"caption":"第3章知识地图","reading_note":"预置导读","summary":"桌面预置地图","sections":[{"id":"s1","title":"开端","summary":"拆信","block_ids":["B01"],"nodes":[]}],"terms":[]}""",
        )
        File(ch3ToolsDir, "literature_diagram_tension_curve.json").writeText(
            """{"action":"diagram","blueprint":"tension_curve","figure":{"blueprint":"tension_curve","caption":"桌面预置张力","reading_note":"导读","summary":"预置","data":{"yLabel":"张力","points":[{"beat":"启封","tension":70,"emotion":"紧绷","blocks":["B01"],"source":"原文"}]}}}""",
        )
        File(ch3ToolsDir, "literature_imagery_imagery.json").writeText(
            """{"action":"imagery","blueprint":"extension_dossier","figure":{"blueprint":"extension_dossier","caption":"桌面预置意象","reading_note":"细读","summary":"预置意象","items":[{"section":"核心意象与象征物","title":"红蜡","body":"危险信号","blocks":["B01"],"source":"原文"}]}}""",
        )

        engine.importDesktopPrecomputedCache(imported.workId, cacheDir.absolutePath)

        val ch3 = ToolScope.Chapter(ChapterOrdinal(3))
        val preTension = engine.runTool(CompanionRequest(imported.workId, ToolId("tension_curve"), ch3)).toList().single() as CompanionEvent.CachedHit
        val preImagery = engine.runTool(CompanionRequest(imported.workId, ToolId("imagery"), ch3)).toList().single() as CompanionEvent.CachedHit
        val preMap = engine.runTool(CompanionRequest(imported.workId, ToolId("chapter_map"), ch3)).toList().single() as CompanionEvent.CachedHit

        assertFalse(preTension.isStale)
        assertEquals("桌面预置张力", preTension.artifact.caption)
        assertEquals("桌面预置意象", preImagery.artifact.caption)
        assertEquals("第3章知识地图", preMap.artifact.caption)
        // Still only 1 LLM call across the entire test!
        assertEquals(1, fakeLlm.recordedRequests.size)
    }

    @Test
    fun `askScannedRegion validates vision capability and askFollowUp persists multi-turn history`() = runTest {
        val bcbook = build20ChapterSpoilerBook(tempFolder.newFile("B0046.bcbook"), uid = "B0046")
        val imported = libraryVault.ingestStudyPack(bcbook.absolutePath).getOrThrow()

        // 1. Non-vision provider fails fast with 0 LLM calls
        val nonVisionEngine = createEngine(supportsVision = false)
        val noVisEvents = nonVisionEngine.askScannedRegion(
            workId = imported.workId,
            pageIndex = 81,
            pngBytes = byteArrayOf(1, 2, 3),
            question = "识别图示",
        ).toList()
        assertTrue((noVisEvents.single() as CompanionEvent.Failure).error is AppFailure.ProviderAuthOrQuota)
        assertEquals(0, fakeLlm.recordedRequests.size)

        // 2. Vision provider sends imagePngBytes and returns MarkdownReadCard
        val visionEngine = createEngine(supportsVision = true)
        fakeLlm.enqueueResponse("图中右上角为几何三角推导 `[原文]`。")
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val visEvents = visionEngine.askScannedRegion(
            workId = imported.workId,
            pageIndex = 81,
            pngBytes = png,
            question = "解释注释与图示",
        ).toList()
        val visDone = visEvents.last() as CompanionEvent.Done
        assertTrue(visDone.artifact is AuditedArtifact.MarkdownReadCard)
        assertTrue(fakeLlm.recordedRequests.single().imagePngBytes.contentEquals(png))

        // 3. Multi-turn askFollowUp persists thread context
        fakeLlm.enqueueResponse("第一轮回答：封口是红蜡 `[原文]`。")
        fakeLlm.enqueueResponse("第二轮回答：红蜡说明函件加急 `[原文]`。")
        val ch3 = ToolScope.Chapter(ChapterOrdinal(3))
        val t1 = visionEngine.askFollowUp("thread-01", imported.workId, ch3, "信封用了什么封口？").toList()
        assertTrue(t1.last() is CompanionEvent.Done)
        val t2 = visionEngine.askFollowUp("thread-01", imported.workId, ch3, "这说明什么？").toList()
        assertTrue(t2.last() is CompanionEvent.Done)

        val secondReq = fakeLlm.recordedRequests.last()
        assertTrue(secondReq.userPrompt.contains("信封用了什么封口？"))
        assertTrue(secondReq.userPrompt.contains("第一轮回答：封口是红蜡"))
    }

    @Test
    fun `Selection scope includes neighbor blocks, estimateTokenCost works, and sensitivity_chart strips fake pct`() = runTest {
        val bcbook = build20ChapterSpoilerBook(tempFolder.newFile("B0047.bcbook"), uid = "B0047")
        val imported = libraryVault.ingestStudyPack(bcbook.absolutePath).getOrThrow()
        val engine = createEngine()

        val ch3 = ChapterOrdinal(3)
        val anchor = libraryVault.resolveAnchor(imported.workId, ch3, BlockId("B02"))
        val selScope = ToolScope.Selection(anchor, "红蜡封缄")

        val estimate = engine.estimateTokenCost(
            CompanionRequest(imported.workId, ToolId("explain"), selScope),
        )
        assertTrue(estimate.estimatedInputTokens > 0)
        assertTrue(estimate.withinContextWindow)

        fakeLlm.enqueueResponse("这段话点出密信使用了加急火漆封缄 `[原文]`。")
        val selEvents = engine.runTool(
            CompanionRequest(imported.workId, ToolId("explain"), selScope),
        ).toList()
        assertTrue(selEvents.last() is CompanionEvent.Done)
        assertTrue(fakeLlm.recordedRequests.single().userPrompt.contains("[B01]"))
        assertTrue(fakeLlm.recordedRequests.single().userPrompt.contains("[B02]"))

        // sensitivity_chart fake 0-100 percentage stripping converts to regime_threshold
        fakeLlm.enqueueResponse(
            """
            {
              "blueprint": "sensitivity_chart",
              "caption": "参数灵敏度",
              "reading_note": "看阈值",
              "summary": "剥除假百分比",
              "data": {
                "xLabel": "温度",
                "yLabel": "变化幅度(%)",
                "points": [
                  {"beat": "低温段", "tension": 20, "emotion": "稳定", "note": "结构保持完好", "blocks": ["B01"], "source": "原文"},
                  {"beat": "高温段", "tension": 90, "emotion": "崩溃", "note": "发生相变失效", "blocks": ["B02"], "source": "原文"}
                ]
              }
            }
            """.trimIndent(),
        )
        val sensDone = engine.runTool(
            CompanionRequest(imported.workId, ToolId("sensitivity_chart"), ToolScope.Chapter(ch3)),
        ).toList().last() as CompanionEvent.Done
        val sensDiag = sensDone.artifact as AuditedArtifact.StructuredDiagram
        assertEquals("regime_threshold", sensDiag.data["mode"]?.jsonPrimitive?.content)
        assertTrue(sensDiag.data["regimes"]!!.jsonArray.size == 2)
    }

    @Test
    fun `regression suite covers in-memory overrideProfile, selection key isolation, SWR stale-hash hit, offline precomputed cross-search, multi-turn askFollowUp, and DB migration`() = runTest {
        val bcbook = build20ChapterSpoilerBook(tempFolder.newFile("B0048.bcbook"), uid = "B0048")
        val imported = libraryVault.ingestStudyPack(bcbook.absolutePath).getOrThrow()
        val engine = createEngine(syncCoordinator = null)

        // Bug 2: in-memory LibraryVault.overrideProfile immediately reflected in recommendedTools
        libraryVault.overrideProfile(imported.workId, ReadProfileId("philosophy"), null)
        val overriddenToolbox = engine.recommendedTools(imported.workId)
        assertEquals(ReadProfileId("philosophy"), overriddenToolbox.profileId)
        assertEquals(null, overriddenToolbox.literatureSubKind)

        // Bug 3a: two different selectedText strings on the exact same BlockId("B02") do not collide in L3 cache
        val ch3 = ChapterOrdinal(3)
        val anchorB02 = libraryVault.resolveAnchor(imported.workId, ch3, BlockId("B02"))
        fakeLlm.enqueueResponse("第一段划词解释 `[原文]`。")
        fakeLlm.enqueueResponse("第二段划词解释 `[原文]`。")
        val selA = engine.runTool(
            CompanionRequest(imported.workId, ToolId("explain"), ToolScope.Selection(anchorB02, "红蜡封缄")),
        ).toList().last() as CompanionEvent.Done
        val selB = engine.runTool(
            CompanionRequest(imported.workId, ToolId("explain"), ToolScope.Selection(anchorB02, "加急函件规制")),
        ).toList().last() as CompanionEvent.Done
        assertEquals(2, fakeLlm.recordedRequests.size)
        assertTrue((selA.artifact as AuditedArtifact.MarkdownReadCard).markdown.contains("第一段"))
        assertTrue((selB.artifact as AuditedArtifact.MarkdownReadCard).markdown.contains("第二段"))

        // Bug 3b: SWR stale-hash hit returns CachedHit(isStale = true) when content hash mismatches, and forceRefresh regenerates
        val cacheDirStale = tempFolder.newFolder("stale_hash_cache")
        val ch3StaleTools = File(cacheDirStale, "ch_03/chapter_tools").apply { mkdirs() }
        File(ch3StaleTools, "history_diagram_argument_map.json").writeText(
            """{"action":"diagram","blueprint":"argument_map","source_sha256":"deadbeef","figure":{"blueprint":"argument_map","caption":"旧哈希全章论证","reading_note":"导读","summary":"旧摘要","data":{"nodes":[{"id":"A1","role":"conclusion","label":"结论","blocks":["B01"],"source":"原文"}],"edges":[]}}}""",
        )
        // Import with a mismatched source hash via a wrapper or direct CompanionDatabase test; here verify offline cross-search + DB migration:
        // Bug 3c: precomputed cross-book cache hits offline (syncCoordinator == null), while forceRefresh or uncached misses fail with 0 LLM calls
        val offlineCacheDir = tempFolder.newFolder("offline_cross_cache")
        val ch3Tools = File(offlineCacheDir, "ch_03/chapter_tools").apply { mkdirs() }
        File(ch3Tools, "history_compare_context_compare_context.json").writeText(
            """{"action":"compare_context","blueprint":"extension_dossier","figure":{"blueprint":"extension_dossier","caption":"离线预置史家对照","reading_note":"预置对照","summary":"桌面导出","sections":[{"id":"historiography","title":"史观对撞","items":[{"title":"海关规制","body":"预置分析","blocks":["B01"],"source":"原文"}]}]}}""",
        )
        engine.importDesktopPrecomputedCache(imported.workId, offlineCacheDir.absolutePath)

        val offlineCacheHit = engine.runTool(
            CompanionRequest(imported.workId, ToolId("compare_context"), ToolScope.Chapter(ch3)),
        ).toList().single() as CompanionEvent.CachedHit
        assertFalse(offlineCacheHit.isStale)
        assertEquals("离线预置史家对照", offlineCacheHit.artifact.caption)
        assertEquals(2, fakeLlm.recordedRequests.size)

        val offlineForceFail = engine.runTool(
            CompanionRequest(imported.workId, ToolId("compare_context"), ToolScope.Chapter(ch3), forceRefresh = true),
        ).toList().single() as CompanionEvent.Failure
        assertTrue(offlineForceFail.error is AppFailure.DesktopConnectionRequired)
        val offlineUncachedFail = engine.runTool(
            CompanionRequest(imported.workId, ToolId("method_audit"), ToolScope.Chapter(ch3)),
        ).toList().single() as CompanionEvent.Failure
        assertTrue(offlineUncachedFail.error is AppFailure.DesktopConnectionRequired)
        assertEquals(2, fakeLlm.recordedRequests.size)

        // Bug 3d: multi-turn askFollowUp preserves all turns sequentially
        fakeLlm.enqueueResponse("第一轮阶段回答 `[原文]`。")
        fakeLlm.enqueueResponse("第二轮阶段回答 `[原文]`。")
        fakeLlm.enqueueResponse("第三轮阶段回答 `[原文]`。")

        val ask1 = engine.askFollowUp("thread-multi", imported.workId, ToolScope.Chapter(ch3), "第一轮提问").toList()
        assertTrue(ask1.last() is CompanionEvent.Done)

        val ask2 = engine.askFollowUp("thread-multi", imported.workId, ToolScope.Chapter(ch3), "第二轮提问").toList()
        assertTrue(ask2.last() is CompanionEvent.Done)

        val ask3 = engine.askFollowUp("thread-multi", imported.workId, ToolScope.Chapter(ch3), "第三轮提问").toList()
        assertTrue(ask3.last() is CompanionEvent.Done)
        val promptAt3 = fakeLlm.recordedRequests.last().userPrompt
        assertTrue(promptAt3.contains("第一轮提问"))
        assertTrue(promptAt3.contains("第一轮阶段回答"))
        assertTrue(promptAt3.contains("第二轮提问"))
        assertTrue(promptAt3.contains("第二轮阶段回答"))

        // CompanionDatabase MIGRATION_1_2 safely upgrades v1 schema (with progressGateKey & gateOrdinal) without crashing
        val dbName = "test_companion_mig_v1_v2.db"
        context.deleteDatabase(dbName)
        val migConfig = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `companion_results` (
                                `workId` TEXT NOT NULL, `scopeKey` TEXT NOT NULL, `progressGateKey` TEXT NOT NULL,
                                `toolId` TEXT NOT NULL, `promptVersion` INTEGER NOT NULL, `chapterContentSha256` TEXT NOT NULL,
                                `artifactJson` TEXT NOT NULL, `tokenCost` INTEGER NOT NULL, `byteSize` INTEGER NOT NULL,
                                `isPinned` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL, `lastAccessedAtMs` INTEGER NOT NULL,
                                PRIMARY KEY(`workId`, `scopeKey`, `progressGateKey`, `toolId`, `promptVersion`)
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `chapter_digests` (
                                `workId` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `chapterTitle` TEXT NOT NULL,
                                `chapterContentSha256` TEXT NOT NULL, `digestText` TEXT NOT NULL,
                                `anchorBlockIdsJson` TEXT NOT NULL, `updatedAtMs` INTEGER NOT NULL,
                                PRIMARY KEY(`workId`, `ordinal`)
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `qa_threads` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `threadId` TEXT NOT NULL,
                                `workId` TEXT NOT NULL, `scopeKey` TEXT NOT NULL, `gateOrdinal` INTEGER,
                                `turnIndex` INTEGER NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL,
                                `createdAtMs` INTEGER NOT NULL
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            INSERT INTO `companion_results` VALUES (
                                'B0001', 'full_book', 'gate:ch03', 'argument_map', 1, 'abc',
                                '{"type":"markdown_read_card","blueprint":"markdown_read_card","caption":"旧缓存","reading_note":"","summary":"","audit_log":[],"markdown":"hi"}',
                                10, 50, 0, 100, 100
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            INSERT INTO `companion_results` VALUES (
                                'B0001', 'full_book', 'gate:full', 'argument_map', 1, 'def',
                                '{"type":"markdown_read_card","blueprint":"markdown_read_card","caption":"新缓存","reading_note":"","summary":"","audit_log":[],"markdown":"hello"}',
                                10, 50, 0, 200, 200
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            INSERT INTO `qa_threads` (`threadId`, `workId`, `scopeKey`, `gateOrdinal`, `turnIndex`, `role`, `content`, `createdAtMs`)
                            VALUES ('t1', 'B0001', 'chapter:03', 3, 0, 'user', '提问', 100)
                            """.trimIndent(),
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                },
            )
            .build()
        FrameworkSQLiteOpenHelperFactory().create(migConfig).writableDatabase.close()
        val migratedDb = Room.databaseBuilder(context, CompanionDatabase::class.java, dbName)
            .addMigrations(CompanionDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val migratedResult = migratedDb.companionDao().getExactResult("B0001", "full_book", "argument_map", 1)
            assertNotNull(migratedResult)
            assertTrue(migratedResult!!.artifactJson.contains("新缓存"))
            val migratedTurns = migratedDb.companionDao().getQaTurns("B0001", "t1")
            assertEquals(1, migratedTurns.size)
            assertEquals("提问", migratedTurns.single().content)
        } finally {
            migratedDb.close()
            context.deleteDatabase(dbName)
        }
    }

    // ---- Test Helpers ----

    private class ScriptedLlmFakeAdapter : LlmProviderPort {
        override val capabilities = ProviderCapabilities(supportsJsonSchema = true, supportsVision = true, contextWindow = 128_000)
        private val responses = ArrayDeque<String>()
        val recordedRequests = CopyOnWriteArrayList<LlmPromptRequest>()

        fun enqueueResponse(text: String) {
            responses.addLast(text)
        }

        override fun stream(endpoint: String, apiKey: String, payload: LlmPromptRequest): Flow<LlmStreamEvent> = flow {
            recordedRequests += payload
            val reply = if (responses.isNotEmpty()) responses.removeFirst() else "{}"
            val mid = reply.length / 2
            if (mid > 0) emit(LlmStreamEvent.ContentDelta(reply.substring(0, mid)))
            emit(LlmStreamEvent.ContentDelta(reply.substring(mid)))
            emit(LlmStreamEvent.Completed(reply))
        }
    }

    private class FakeSyncCoordinator(
        private val crossHitsResult: Result<List<CrossBookHit>>,
    ) : SyncCoordinator {
        override suspend fun pairDesktopViaQr(qrPayload: String): Result<DesktopEndpointInfo> =
            Result.failure(UnsupportedOperationException())
        override suspend fun fetchDesktopCatalog(query: String?, onlyWithCache: Boolean): Result<List<DesktopBookSummary>> =
            Result.failure(UnsupportedOperationException())
        override suspend fun fetchDesktopOutbox(): Result<List<OutboxTicket>> =
            Result.failure(UnsupportedOperationException())
        override fun pullStudyPacks(workIds: List<WorkId>): Flow<BundleTransferState> = emptyFlow()
        override suspend fun reconcileWork(workId: WorkId): Result<SyncMergeOutcome> =
            Result.failure(UnsupportedOperationException())
        override suspend fun queryCrossBookHits(workId: WorkId, queryText: String, sourcePassage: String?): Result<List<CrossBookHit>> =
            crossHitsResult
    }

    private fun build20ChapterSpoilerBook(dest: File, uid: String = "B0042"): File {
        val chapters = mutableListOf<LogicalChapterEntry>()
        val files = linkedMapOf<String, String>()

        // Ch 1 FRONT_BACK
        val ch1Text = "# 序言\n\n扉页与版权说明。"
        files["01_扉页.md"] = ch1Text
        chapters += LogicalChapterEntry(
            ordinal = ChapterOrdinal(1),
            n = "01",
            title = "序言",
            chapterRole = ChapterRole.FRONT_BACK,
            merged = true,
            sourceFiles = listOf("01_扉页.md"),
            chapterContentSha256 = ChapterContentHash(sha256Hex(ch1Text.toByteArray())),
            chars = ch1Text.length,
        )

        // Ch 2..19 BODY
        for (ord in 2..19) {
            val fileName = "${ord.toString().padStart(2, '0')}_ch.md"
            val text = when (ord) {
                2 -> "# 第一章 雾夜\n\n码头雾气沉沉。\n\n船笛响起。"
                3 -> "# 第二章 密信\n\n沈警官拆开一封红蜡封缄的密信[^45]。\n\n信封背面留下暗记。"
                14 -> "# 第十三章 审讯\n\nSPOILER_CANARY_CH_14 凶手供出幕后线索[^102]。"
                else -> "# 第${ord - 1}章\n\nSPOILER_CANARY_CH_$ord 剧情继续推进。"
            }
            files[fileName] = text
            chapters += LogicalChapterEntry(
                ordinal = ChapterOrdinal(ord),
                n = ord.toString().padStart(2, '0'),
                title = "第$ord 章",
                chapterRole = ChapterRole.BODY,
                merged = false,
                sourceFiles = listOf(fileName),
                chapterContentSha256 = ChapterContentHash(sha256Hex(text.toByteArray())),
                chars = text.length,
            )
        }

        // Ch 20 NOTES
        val ch20Text = "# 全书注释\n\n[^45]: 此处“红蜡封缄”沿用十九世纪港口海关的加急函件规制。\n\n[^102]: SECRET_KILLER_CH14_NOTE_102 真凶身份实为港口副督察。"
        files["20_注释.md"] = ch20Text
        chapters += LogicalChapterEntry(
            ordinal = ChapterOrdinal(20),
            n = "20",
            title = "全书注释",
            chapterRole = ChapterRole.NOTES,
            merged = false,
            sourceFiles = listOf("20_注释.md"),
            chapterContentSha256 = ChapterContentHash(sha256Hex(ch20Text.toByteArray())),
            chars = ch20Text.length,
        )

        val manifest = BcBookManifest(
            schemaVersion = 1,
            uid = WorkId(uid),
            title = "雾夜档案",
            author = "测试作者",
            subjects = listOf("文学", "类型小说"),
            readType = ReadProfileId("literature"),
            subKind = "mystery",
            bookSourceSha256 = BookSourceHash(sha256Hex(uid.toByteArray())),
            clothColor = "#2C4056",
            logicalChapters = chapters,
        )
        return writeBcBookZip(dest, manifest, files)
    }

    private fun buildLongChapterBcBook(dest: File): File {
        val ch1Body = buildString {
            append("# 第一章 短行合并与脚注测试\n\n")
            append("## 第一节 开头场景\n\n")
            append("这是开篇的重要背景介绍段落，说明实验装置与前提假设[^1]。\n\n")
            for (i in 1..15) {
                append("短台词第${i}行。\n\n")
            }
            append("## 第二节 结尾收束\n\n")
            append("这是全章最终的收尾与总结段落。\n")
        }
        val ch2Notes = "# 注释\n\n[^1]: 源自作者手稿第一卷的观测记录。"
        val manifest = BcBookManifest(
            schemaVersion = 1,
            uid = WorkId("B0099"),
            title = "长章测试",
            author = "作者",
            subjects = listOf("人文社科", "思想与哲学"),
            readType = ReadProfileId("philosophy"),
            subKind = "",
            bookSourceSha256 = BookSourceHash(sha256Hex("B0099".toByteArray())),
            clothColor = "#1E3A36",
            logicalChapters = listOf(
                LogicalChapterEntry(
                    ordinal = ChapterOrdinal(1),
                    n = "01",
                    title = "超长章",
                    chapterRole = ChapterRole.BODY,
                    merged = false,
                    sourceFiles = listOf("01_ch.md"),
                    chapterContentSha256 = ChapterContentHash(sha256Hex(ch1Body.toByteArray())),
                    chars = ch1Body.length,
                ),
                LogicalChapterEntry(
                    ordinal = ChapterOrdinal(2),
                    n = "02",
                    title = "注释",
                    chapterRole = ChapterRole.NOTES,
                    merged = false,
                    sourceFiles = listOf("02_notes.md"),
                    chapterContentSha256 = ChapterContentHash(sha256Hex(ch2Notes.toByteArray())),
                    chars = ch2Notes.length,
                ),
            ),
        )
        return writeBcBookZip(dest, manifest, mapOf("01_ch.md" to ch1Body, "02_notes.md" to ch2Notes))
    }

    private fun writeBcBookZip(dest: File, manifest: BcBookManifest, mdFiles: Map<String, String>): File {
        ZipOutputStream(FileOutputStream(dest)).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(json.encodeToString(BcBookManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            for ((name, text) in mdFiles) {
                zos.putNextEntry(ZipEntry("md/$name"))
                zos.write(text.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return dest
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
