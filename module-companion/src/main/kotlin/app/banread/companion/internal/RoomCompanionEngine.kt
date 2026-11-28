package app.banread.companion.internal

import android.content.Context
import app.banread.model.AiRuntime
import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import app.banread.model.AuditedArtifact
import app.banread.model.CompanionEngine
import app.banread.model.CompanionEvent
import app.banread.model.CompanionRequest
import app.banread.model.CrossBookHit
import app.banread.model.LibraryVault
import app.banread.model.LlmPromptRequest
import app.banread.model.LlmStreamEvent
import app.banread.model.ProfileToolbox
import app.banread.model.ReadProfileId
import app.banread.model.ReadingAnchor
import app.banread.model.ShareCompanionMergeResult
import app.banread.model.SharedCompanionArtifact
import app.banread.model.SharedCompanionPayload
import app.banread.model.SharedQaTurn
import app.banread.model.SpoilerGate
import app.banread.model.SyncCoordinator
import app.banread.model.TokenEstimate
import app.banread.model.ToolId
import app.banread.model.ToolScope
import app.banread.model.WorkId
import app.banread.model.registry.ProfileRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.ceil

internal class RoomCompanionEngine(
    private val libraryVault: LibraryVault,
    private val aiRuntime: AiRuntime,
    private val profileRegistry: ProfileRegistry,
    private val syncCoordinator: SyncCoordinator?,
    private val db: CompanionDatabase,
    private val ownsDatabase: Boolean,
) : CompanionEngine, Closeable {

    private val dao = db.companionDao()
    private val contextBuilder = ContextBuilder(libraryVault, dao)
    private val cacheImporter = DesktopCacheImporter(libraryVault, dao)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun close() {
        if (ownsDatabase) {
            db.close()
        }
    }

    override suspend fun recommendedTools(workId: WorkId): ProfileToolbox {
        val (profileId, subKind) = resolveWorkProfile(workId)
        val profile = profileRegistry.profileById(profileId)
        return ProfileToolbox(
            profileId = profile.readType,
            literatureSubKind = subKind,
            blueprints = profile.blueprints.map { it.toolId },
            extensions = profile.extensions.map { it.toolId },
        )
    }

    override fun runTool(request: CompanionRequest): Flow<CompanionEvent> = flow {
        val (profileId, subKind) = resolveWorkProfile(request.workId)
        val effectiveToolId = resolveEffectiveToolId(request.tool, profileId)

        // 1. Build anti-spoiler gated context exclusively via LibraryVault
        val ctx = when (val outcome = contextBuilder.build(request.workId, request.scope, request.gate)) {
            is ContextBuildOutcome.GateLocked -> {
                emit(CompanionEvent.Failure(AppFailure.CapacityPreflightBlocked(0L, outcome.reason)))
                return@flow
            }
            is ContextBuildOutcome.Ready -> outcome.context
        }

        val scopeKey = scopeKeyFor(request.scope)
        val gateKey = progressGateKeyFor(request.scope, request.gate)

        // 2. Room L3 Cache Lookup & FullBook SWR (allows offline hits for precomputed cross-book results)
        if (!request.forceRefresh) {
            val exact = dao.getExactResult(
                workId = request.workId.raw,
                scopeKey = scopeKey,
                progressGateKey = gateKey,
                toolId = effectiveToolId,
                promptVersion = PromptCatalog.PROMPT_VERSION,
            )
            if (exact != null && isContentHashCompatible(exact.chapterContentSha256, ctx.chapterContentSha256)) {
                dao.touchResult(
                    workId = request.workId.raw,
                    scopeKey = scopeKey,
                    progressGateKey = gateKey,
                    toolId = effectiveToolId,
                    promptVersion = PromptCatalog.PROMPT_VERSION,
                    nowMs = System.currentTimeMillis(),
                )
                val artifact = json.decodeFromString(AuditedArtifact.serializer(), exact.artifactJson)
                emit(CompanionEvent.CachedHit(artifact = artifact, isStale = false))
                return@flow
            }

            if (request.scope is ToolScope.FullBook) {
                val targetGateOrdinal = when (val g = request.gate) {
                    is SpoilerGate.UpTo -> g.unlockedMaxOrdinal.value
                    SpoilerGate.FullBookUnlocked -> Int.MAX_VALUE
                }
                val swrCandidate = findBestEarlierFullBookCache(
                    workId = request.workId,
                    toolId = effectiveToolId,
                    targetGateOrdinal = targetGateOrdinal,
                )
                if (swrCandidate != null) {
                    dao.touchResult(
                        workId = swrCandidate.workId,
                        scopeKey = swrCandidate.scopeKey,
                        progressGateKey = swrCandidate.progressGateKey,
                        toolId = swrCandidate.toolId,
                        promptVersion = swrCandidate.promptVersion,
                        nowMs = System.currentTimeMillis(),
                    )
                    val artifact = json.decodeFromString(AuditedArtifact.serializer(), swrCandidate.artifactJson)
                    emit(CompanionEvent.CachedHit(artifact = artifact, isStale = true))
                    return@flow
                }
            }
        }

        // 3. Strict 0-Token Cross-Book Guard when cache misses or forceRefresh = true
        val crossBookHits: List<CrossBookHit> = if (profileRegistry.isCrossSearchTool(request.tool)) {
            val coord = syncCoordinator
            if (coord == null) {
                emit(CompanionEvent.Failure(AppFailure.DesktopConnectionRequired(request.tool.raw)))
                return@flow
            }
            val queryText = listOf(ctx.workTitle, ctx.chapterTitle, ctx.selectedText.take(120))
                .filter { it.isNotBlank() }
                .joinToString(" ")
            val passage = ctx.selectedText.ifBlank { ctx.contextText.take(240) }
            val hitResult = coord.queryCrossBookHits(request.workId, queryText, passage)
            if (hitResult.isFailure) {
                val ex = hitResult.exceptionOrNull()
                val failure = when (ex) {
                    is AppFailureException -> when (ex.failure) {
                        is AppFailure.DesktopConnectionRequired -> ex.failure
                        else -> AppFailure.DesktopConnectionRequired(request.tool.raw)
                    }
                    else -> AppFailure.DesktopConnectionRequired(request.tool.raw)
                }
                emit(CompanionEvent.Failure(failure))
                return@flow
            }
            hitResult.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        // 4. Build prompt and stream from AiRuntime
        val builtPrompt = PromptCatalog.buildPrompt(
            toolId = ToolId(effectiveToolId),
            readProfileId = profileId,
            literatureSubKind = subKind,
            profileRegistry = profileRegistry,
            ctx = ctx,
            gate = request.gate,
            crossBookHits = crossBookHits,
        )

        val llmRequest = LlmPromptRequest(
            systemPrompt = builtPrompt.systemPrompt,
            userPrompt = builtPrompt.userPrompt,
            expectJson = builtPrompt.expectJson,
            conversationId = "companion:${request.workId.raw}",
        )

        var completedText: String? = null
        var failed: AppFailure? = null

        aiRuntime.streamCompletion(llmRequest).collect { event ->
            when (event) {
                is LlmStreamEvent.ThinkingDelta -> {
                    emit(CompanionEvent.StreamingDelta(delta = event.delta, isThinking = true))
                }
                is LlmStreamEvent.ContentDelta -> {
                    if (!builtPrompt.expectJson) {
                        emit(CompanionEvent.StreamingDelta(delta = event.delta, isThinking = false))
                    }
                }
                is LlmStreamEvent.Completed -> {
                    completedText = event.fullText
                }
                is LlmStreamEvent.Failed -> {
                    failed = event.failure
                }
            }
        }

        failed?.let {
            emit(CompanionEvent.Failure(it))
            return@flow
        }

        val rawResponse = completedText.orEmpty()
        val artifact: AuditedArtifact = if (builtPrompt.expectJson) {
            BlueprintAuditor.auditRawResponse(
                rawText = rawResponse,
                validBlockIds = ctx.validBlockIds,
                fallbackBlockIds = ctx.fallbackBlockIds,
                requestedBlueprint = builtPrompt.effectiveBlueprint,
                requestedAction = builtPrompt.effectiveAction,
            )
        } else {
            AuditedArtifact.MarkdownReadCard(
                caption = "${ctx.chapterTitle} · ${builtPrompt.effectiveAction}",
                readingNote = "可对照原文段落核验",
                summary = rawResponse.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(140),
                auditLog = listOf("Markdown伴读回答生成完成。"),
                markdown = rawResponse,
            )
        }

        val serialized = json.encodeToString(AuditedArtifact.serializer(), artifact)
        val now = System.currentTimeMillis()
        val approxTokens = ceil((builtPrompt.systemPrompt.length + builtPrompt.userPrompt.length + rawResponse.length) * 1.1).toLong()
        dao.upsertAndEnforceLru(
            CompanionResultEntity(
                workId = request.workId.raw,
                scopeKey = scopeKey,
                progressGateKey = gateKey,
                toolId = effectiveToolId,
                promptVersion = PromptCatalog.PROMPT_VERSION,
                chapterContentSha256 = ctx.chapterContentSha256,
                artifactJson = serialized,
                tokenCost = approxTokens,
                byteSize = serialized.toByteArray(Charsets.UTF_8).size.toLong(),
                isPinned = false,
                createdAtMs = now,
                lastAccessedAtMs = now,
            ),
        )

        emit(CompanionEvent.Done(artifact))
    }

    override fun askFollowUp(
        threadId: String?,
        workId: WorkId,
        scope: ToolScope,
        gate: SpoilerGate,
        question: String,
    ): Flow<CompanionEvent> = flow {
        val effectiveThreadId = threadId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val (profileId, subKind) = resolveWorkProfile(workId)

        val ctx = when (val outcome = contextBuilder.build(workId, scope, gate)) {
            is ContextBuildOutcome.GateLocked -> {
                emit(CompanionEvent.Failure(AppFailure.CapacityPreflightBlocked(0L, outcome.reason)))
                return@flow
            }
            is ContextBuildOutcome.Ready -> outcome.context
        }

        val currentGateOrd = (gate as? SpoilerGate.UpTo)?.unlockedMaxOrdinal?.value
        val allTurns = dao.getQaTurns(workId.raw, effectiveThreadId)
        val existingTurns = allTurns.filter { turn ->
            currentGateOrd == null || (turn.gateOrdinal != null && turn.gateOrdinal <= currentGateOrd)
        }

        val historyText = if (existingTurns.isNotEmpty()) {
            buildString {
                append("\n\n【历史追问对话】\n")
                for (turn in existingTurns.takeLast(12)) {
                    val speaker = if (turn.role == "user") "读者" else "助手"
                    append("$speaker：${turn.content}\n")
                }
            }
        } else {
            ""
        }

        val basePrompt = PromptCatalog.buildPrompt(
            toolId = ToolId("ask"),
            readProfileId = profileId,
            literatureSubKind = subKind,
            profileRegistry = profileRegistry,
            ctx = ctx,
            gate = gate,
            question = question,
        )
        val fullUserPrompt = basePrompt.userPrompt + historyText

        var completedText: String? = null
        var failed: AppFailure? = null

        aiRuntime.streamCompletion(
            LlmPromptRequest(
                systemPrompt = basePrompt.systemPrompt,
                userPrompt = fullUserPrompt,
                expectJson = false,
                conversationId = effectiveThreadId,
            ),
        ).collect { event ->
            when (event) {
                is LlmStreamEvent.ThinkingDelta -> emit(CompanionEvent.StreamingDelta(event.delta, isThinking = true))
                is LlmStreamEvent.ContentDelta -> emit(CompanionEvent.StreamingDelta(event.delta, isThinking = false))
                is LlmStreamEvent.Completed -> completedText = event.fullText
                is LlmStreamEvent.Failed -> failed = event.failure
            }
        }

        failed?.let {
            emit(CompanionEvent.Failure(it))
            return@flow
        }

        val answer = completedText.orEmpty()
        val nextTurn = (allTurns.maxOfOrNull { it.turnIndex } ?: -1) + 1
        val scopeKey = scopeKeyFor(scope)
        val now = System.currentTimeMillis()
        dao.insertQaTurn(
            QaThreadEntity(
                threadId = effectiveThreadId,
                workId = workId.raw,
                scopeKey = scopeKey,
                gateOrdinal = currentGateOrd,
                turnIndex = nextTurn,
                role = "user",
                content = question,
                createdAtMs = now,
            ),
        )
        dao.insertQaTurn(
            QaThreadEntity(
                threadId = effectiveThreadId,
                workId = workId.raw,
                scopeKey = scopeKey,
                gateOrdinal = currentGateOrd,
                turnIndex = nextTurn + 1,
                role = "assistant",
                content = answer,
                createdAtMs = now + 1L,
            ),
        )

        emit(
            CompanionEvent.Done(
                AuditedArtifact.MarkdownReadCard(
                    caption = "自由追问 · ${ctx.chapterTitle}",
                    readingNote = "会话编号：$effectiveThreadId",
                    summary = answer.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(140),
                    auditLog = listOf("多轮追问完成并已写入 QaThreadEntity"),
                    markdown = answer,
                ),
            ),
        )
    }

    override fun askScannedRegion(
        workId: WorkId,
        pageIndex: Int,
        pngBytes: ByteArray,
        question: String,
    ): Flow<CompanionEvent> = flow {
        val profiles = aiRuntime.listProviderProfiles()
        val active = profiles.firstOrNull()
        if (active == null || !active.capabilities.supportsVision) {
            emit(
                CompanionEvent.Failure(
                    AppFailure.ProviderAuthOrQuota(
                        providerId = active?.id ?: "none",
                        httpCode = null,
                        message = if (active == null) {
                            "No active LLM provider configured for vision query"
                        } else {
                            "Provider ${active.id} does not support vision (supportsVision=false)"
                        },
                    ),
                ),
            )
            return@flow
        }

        val outline = libraryVault.getWorkOutline(workId)
        val request = LlmPromptRequest(
            providerId = active.id,
            systemPrompt = "你是扫描书页多模态伴读助手。请结合截图中的版面文字、公式与图示回答读者问题；" +
                "直接基于页面可见内容作答标 `[原文]`，背景延伸解释标 `[补充]`。",
            userPrompt = "《${outline.title}》扫描页第 ${pageIndex + 1} 页框选区域提问：\n$question",
            imagePngBytes = pngBytes,
            expectJson = false,
            conversationId = "scanned:${workId.raw}:$pageIndex",
        )

        var completedText: String? = null
        var failed: AppFailure? = null

        aiRuntime.streamCompletion(request).collect { event ->
            when (event) {
                is LlmStreamEvent.ThinkingDelta -> emit(CompanionEvent.StreamingDelta(event.delta, isThinking = true))
                is LlmStreamEvent.ContentDelta -> emit(CompanionEvent.StreamingDelta(event.delta, isThinking = false))
                is LlmStreamEvent.Completed -> completedText = event.fullText
                is LlmStreamEvent.Failed -> failed = event.failure
            }
        }

        failed?.let {
            emit(CompanionEvent.Failure(it))
            return@flow
        }

        val answer = completedText.orEmpty()
        emit(
            CompanionEvent.Done(
                AuditedArtifact.MarkdownReadCard(
                    caption = "第 ${pageIndex + 1} 页框选图问",
                    readingNote = "基于扫描页框选区域的 VLM 多模态解析",
                    summary = answer.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(140),
                    auditLog = listOf("VLM 扫描页图问完成"),
                    markdown = answer,
                ),
            ),
        )
    }

    override suspend fun estimateTokenCost(request: CompanionRequest): TokenEstimate {
        val (profileId, subKind) = resolveWorkProfile(request.workId)
        val builtPrompt = when (val outcome = contextBuilder.build(request.workId, request.scope, request.gate)) {
            is ContextBuildOutcome.GateLocked -> {
                return TokenEstimate(estimatedInputTokens = 0L, estimatedOutputTokens = 0L, withinContextWindow = false)
            }
            is ContextBuildOutcome.Ready -> PromptCatalog.buildPrompt(
                toolId = request.tool,
                readProfileId = profileId,
                literatureSubKind = subKind,
                profileRegistry = profileRegistry,
                ctx = outcome.context,
                gate = request.gate,
            )
        }
        val totalChars = builtPrompt.systemPrompt.length + builtPrompt.userPrompt.length
        val inputTokens = ceil(totalChars * 1.15).toLong()
        val outputTokens = if (builtPrompt.expectJson) 1_200L else 800L
        val contextWindow = aiRuntime.listProviderProfiles().firstOrNull()?.capabilities?.contextWindow ?: 32_000
        return TokenEstimate(
            estimatedInputTokens = inputTokens,
            estimatedOutputTokens = outputTokens,
            withinContextWindow = (inputTokens + outputTokens) <= contextWindow.toLong(),
        )
    }

    override suspend fun importDesktopPrecomputedCache(workId: WorkId, bundleCacheDir: String) {
        withContext(Dispatchers.IO) {
            cacheImporter.import(workId, bundleCacheDir)
        }
    }

    override suspend fun exportShareCompanionData(
        workId: WorkId,
        includeMaps: Boolean,
        includeQa: Boolean,
    ): SharedCompanionPayload = withContext(Dispatchers.IO) {
        val artifacts = if (includeMaps) {
            dao.getResultsForWork(workId.raw).mapNotNull { row ->
                runCatching {
                    val decoded = json.decodeFromString(AuditedArtifact.serializer(), row.artifactJson)
                    SharedCompanionArtifact(
                        scopeKey = row.scopeKey,
                        progressGateKey = row.progressGateKey,
                        toolId = row.toolId,
                        promptVersion = row.promptVersion,
                        chapterContentSha256 = row.chapterContentSha256,
                        artifact = decoded,
                        isPinned = row.isPinned,
                        createdAtMs = row.createdAtMs,
                    )
                }.getOrNull()
            }
        } else {
            emptyList()
        }
        val qaTurns = if (includeQa) {
            dao.getAllQaTurnsForWork(workId.raw).map { turn ->
                SharedQaTurn(
                    threadId = turn.threadId,
                    scopeKey = turn.scopeKey,
                    gateOrdinal = turn.gateOrdinal,
                    turnIndex = turn.turnIndex,
                    role = turn.role,
                    content = turn.content,
                    createdAtMs = turn.createdAtMs,
                )
            }
        } else {
            emptyList()
        }
        SharedCompanionPayload(artifacts = artifacts, qaTurns = qaTurns)
    }

    override suspend fun importShareCompanionData(
        workId: WorkId,
        payload: SharedCompanionPayload,
    ): ShareCompanionMergeResult = withContext(Dispatchers.IO) {
        var importedArtifacts = 0
        var importedQaTurns = 0
        val now = System.currentTimeMillis()
        for (item in payload.artifacts) {
            val existing = dao.getExactResult(
                workId = workId.raw,
                scopeKey = item.scopeKey,
                progressGateKey = item.progressGateKey,
                toolId = item.toolId,
                promptVersion = item.promptVersion,
            )
            if (existing == null) {
                val serialized = json.encodeToString(AuditedArtifact.serializer(), item.artifact)
                dao.upsertAndEnforceLru(
                    CompanionResultEntity(
                        workId = workId.raw,
                        scopeKey = item.scopeKey,
                        progressGateKey = item.progressGateKey,
                        toolId = item.toolId,
                        promptVersion = item.promptVersion,
                        chapterContentSha256 = item.chapterContentSha256,
                        artifactJson = serialized,
                        tokenCost = 0L,
                        byteSize = serialized.toByteArray(Charsets.UTF_8).size.toLong(),
                        isPinned = item.isPinned,
                        createdAtMs = item.createdAtMs.takeIf { it > 0L } ?: now,
                        lastAccessedAtMs = now,
                    ),
                )
                importedArtifacts++
            }
        }
        val existingByThread = mutableMapOf<String, MutableList<QaThreadEntity>>()
        for (turn in payload.qaTurns) {
            val threadTurns = existingByThread.getOrPut(turn.threadId) {
                dao.getQaTurns(workId.raw, turn.threadId).toMutableList()
            }
            val alreadyPresent = threadTurns.any {
                it.turnIndex == turn.turnIndex && it.role == turn.role && it.content == turn.content
            }
            if (!alreadyPresent) {
                val nextIndex = if (threadTurns.any { it.turnIndex == turn.turnIndex }) {
                    (threadTurns.maxOfOrNull { it.turnIndex } ?: -1) + 1
                } else {
                    turn.turnIndex
                }
                val entity = QaThreadEntity(
                    threadId = turn.threadId,
                    workId = workId.raw,
                    scopeKey = turn.scopeKey,
                    gateOrdinal = turn.gateOrdinal,
                    turnIndex = nextIndex,
                    role = turn.role,
                    content = turn.content,
                    createdAtMs = turn.createdAtMs.takeIf { it > 0L } ?: now,
                )
                dao.insertQaTurn(entity)
                threadTurns += entity
                importedQaTurns++
            }
        }
        ShareCompanionMergeResult(
            importedArtifacts = importedArtifacts,
            importedQaTurns = importedQaTurns,
        )
    }

    private suspend fun resolveWorkProfile(workId: WorkId): Pair<ReadProfileId, String?> {
        val outline = libraryVault.getWorkOutline(workId)
        return (outline.readProfileId ?: profileRegistry.defaultReadType) to outline.literatureSubKind
    }

    private fun resolveEffectiveToolId(tool: ToolId, profileId: ReadProfileId): String {
        val raw = tool.raw.trim().lowercase()
        return if (raw == "diagram") {
            profileRegistry.profileById(profileId).blueprints.firstOrNull()?.blueprint ?: "argument_map"
        } else {
            raw
        }
    }

    private fun scopeKeyFor(scope: ToolScope): String = when (scope) {
        is ToolScope.Selection -> {
            val blk = when (val a = scope.anchor) {
                is ReadingAnchor.ReflowCfi -> a.blockId?.value ?: "cfi"
                is ReadingAnchor.BlockOffset -> a.blockId.value
                is ReadingAnchor.PdfPage -> "p${a.pageIndex}"
            }
            val textHash12 = MessageDigest.getInstance("SHA-256")
                .digest(scope.selectedText.trim().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(12)
            "selection:${scope.anchor.ordinal.twoDigit()}:$blk:$textHash12"
        }
        is ToolScope.Chapter -> "chapter:${scope.ordinal.twoDigit()}"
        ToolScope.FullBook -> "full_book"
    }

    private fun progressGateKeyFor(scope: ToolScope, gate: SpoilerGate): String = when (scope) {
        ToolScope.FullBook -> gate.cacheKey
        is ToolScope.Chapter, is ToolScope.Selection -> DesktopCacheImporter.CHAPTER_SELF_GATE_KEY
    }

    private fun isContentHashCompatible(cachedHash: String, currentHash: String): Boolean {
        if (cachedHash.isBlank() || currentHash.isBlank()) return true
        return cachedHash == currentHash
    }

    private suspend fun findBestEarlierFullBookCache(
        workId: WorkId,
        toolId: String,
        targetGateOrdinal: Int,
    ): CompanionResultEntity? {
        val candidates = dao.getAllGateVariants(
            workId = workId.raw,
            scopeKey = "full_book",
            toolId = toolId,
            promptVersion = PromptCatalog.PROMPT_VERSION,
        )
        var best: CompanionResultEntity? = null
        var bestOrd = -1
        for (item in candidates) {
            val ord = GATE_CH_REGEX.matchEntire(item.progressGateKey)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (ord < targetGateOrdinal && ord > bestOrd) {
                bestOrd = ord
                best = item
            }
        }
        return best
    }

    companion object {
        private val GATE_CH_REGEX = Regex("""^gate:ch(\d+)$""")

        fun create(
            libraryVault: LibraryVault,
            aiRuntime: AiRuntime,
            profileRegistry: ProfileRegistry,
            syncCoordinator: SyncCoordinator?,
            context: Context,
            filesRoot: File,
            allowInMemoryDb: Boolean,
        ): RoomCompanionEngine {
            filesRoot.mkdirs()
            val db = CompanionDatabase.build(context, allowInMemory = allowInMemoryDb)
            return RoomCompanionEngine(
                libraryVault = libraryVault,
                aiRuntime = aiRuntime,
                profileRegistry = profileRegistry,
                syncCoordinator = syncCoordinator,
                db = db,
                ownsDatabase = allowInMemoryDb,
            )
        }
    }
}
