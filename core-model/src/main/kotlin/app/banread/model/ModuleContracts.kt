package app.banread.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

// ==================== Deep Module 1: :module-library ====================

interface LibraryVault {
    suspend fun importLocalBook(sourceUri: String): Result<ImportedWork>
    suspend fun ingestStudyPack(zipUriOrFile: String): Result<ImportedWork>
    suspend fun prepareTextPdfReflow(workId: WorkId): Flow<ReflowPrepProgress>
    suspend fun getWorkOutline(workId: WorkId): WorkOutline
    suspend fun resolveAnchor(workId: WorkId, ordinal: ChapterOrdinal, blockId: BlockId): ReadingAnchor
    suspend fun resolveFootnote(workId: WorkId, currentOrdinal: ChapterOrdinal, markerOrHref: String): FootnoteDetail?
    suspend fun overrideProfile(workId: WorkId, profile: ReadProfileId, literatureSubKind: String?)
    suspend fun loadGatedChapterInputs(workId: WorkId, gate: SpoilerGate): GatedBookSlice
}

data class ImportedWork(
    val workId: WorkId,
    val title: String,
    val author: String,
    val readProfileId: ReadProfileId,
    val literatureSubKind: String?,
    val clothColor: String,
    val supportedModes: Set<ReadingSurfaceMode>,
    val isScannedPdf: Boolean = false,
)

data class ReflowPrepProgress(
    val workId: WorkId,
    val processedPages: Int,
    val totalPages: Int,
    val completed: Boolean,
)

data class ChapterOutlineItem(
    val ordinal: ChapterOrdinal,
    val title: String,
    val role: ChapterRole,
    val estimatedMinutes: Int,
    val merged: Boolean = false,
)

data class WorkOutline(
    val workId: WorkId,
    val title: String,
    val bodyChapters: List<ChapterOutlineItem>,
    val notesChapters: List<ChapterOutlineItem>,
    val frontBackChapters: List<ChapterOutlineItem>,
    val readProfileId: ReadProfileId? = null,
    val literatureSubKind: String? = null,
)

data class FootnoteDetail(
    val link: FootnoteLink,
    val targetChapterTitle: String,
    val fullNoteText: String,
)

data class GatedBlock(
    val blockId: BlockId,
    val text: String,
)

data class GatedChapterSlice(
    val ordinal: ChapterOrdinal,
    val title: String,
    val role: ChapterRole,
    val contentHash: ChapterContentHash,
    val blocks: List<GatedBlock>,
)

data class GatedBookSlice(
    val workId: WorkId,
    val gate: SpoilerGate,
    val chapters: List<GatedChapterSlice>,
    val footnotes: List<FootnoteLink>,
)

// ==================== Deep Module 2: :module-reader & Seam 1 ====================

interface ReaderEngine {
    val activeState: StateFlow<ReaderSessionState>
    suspend fun open(workId: WorkId, modePreference: ReadingSurfaceMode): ReaderSessionHandle
    suspend fun switchPdfSurface(workId: WorkId, targetMode: ReadingSurfaceMode): Result<Unit>
    suspend fun goTo(anchor: ReadingAnchor, highlightPulseMs: Long = 0L)
    suspend fun confirmWatermarkAdvance(workId: WorkId, targetOrdinal: ChapterOrdinal)
    suspend fun applyTypography(profile: TypographyProfile)
    suspend fun applyTheme(theme: ReaderTheme)
    suspend fun saveAnnotation(workId: WorkId, anchor: ReadingAnchor, colorToken: String, comment: String)
    suspend fun currentSpoilerGate(workId: WorkId): SpoilerGate
    suspend fun raiseWatermarkFromRemote(workId: WorkId, remoteMaxOrdinal: ChapterOrdinal)
}

/** Seam 1: FoliateWebAdapter / PdfiumPageAdapter / FakeRendererAdapter */
interface BookRendererPort {
    val locationEvents: Flow<ReadingAnchor>
    suspend fun load(compiledAssetRoot: String, initialAnchor: ReadingAnchor?)
    suspend fun navigate(anchor: ReadingAnchor)
    suspend fun updateStyles(cssPayload: String)
    suspend fun renderDecorations(annotations: List<NoteLogEntry>)
}

data class ReaderSessionState(
    val workId: WorkId? = null,
    val mode: ReadingSurfaceMode = ReadingSurfaceMode.REFLOWABLE,
    val currentCursor: ReadingAnchor? = null,
    val spoilerWatermark: ChapterOrdinal? = null,
    val pendingWatermarkJump: ChapterOrdinal? = null,
)

data class ReaderSessionHandle(
    val workId: WorkId,
    val mode: ReadingSurfaceMode,
    val initialAnchor: ReadingAnchor,
    val spoilerGate: SpoilerGate,
)

data class TypographyProfile(
    val fontFamily: String,
    val fontSizeSp: Float,
    val lineHeightMultiplier: Float,
    val paragraphSpacingDp: Float,
    val horizontalMarginDp: Float,
    val squeezeChinesePunctuation: Boolean = true,
)

enum class ReaderTheme {
    PAPER_LIGHT,
    SEPIA_WARM,
    SLATE_NIGHT,
    AMOLED_BLACK,
    EINK_HIGH_CONTRAST,
}

// ==================== Deep Module 3: :module-companion ====================

interface CompanionEngine {
    suspend fun recommendedTools(workId: WorkId): ProfileToolbox
    fun runTool(request: CompanionRequest): Flow<CompanionEvent>
    fun askFollowUp(
        threadId: String?,
        workId: WorkId,
        scope: ToolScope,
        gate: SpoilerGate,
        question: String,
    ): Flow<CompanionEvent>
    fun askScannedRegion(
        workId: WorkId,
        pageIndex: Int,
        pngBytes: ByteArray,
        question: String,
    ): Flow<CompanionEvent>
    suspend fun estimateTokenCost(request: CompanionRequest): TokenEstimate
    suspend fun importDesktopPrecomputedCache(workId: WorkId, bundleCacheDir: String)
}

data class ProfileToolbox(
    val profileId: ReadProfileId,
    val literatureSubKind: String?,
    val blueprints: List<ToolId>,
    val extensions: List<ToolId>,
)

sealed interface ToolScope {
    data class Selection(val anchor: ReadingAnchor, val selectedText: String) : ToolScope
    data class Chapter(val ordinal: ChapterOrdinal) : ToolScope
    data object FullBook : ToolScope
}

data class CompanionRequest(
    val workId: WorkId,
    val tool: ToolId,
    val scope: ToolScope,
    val gate: SpoilerGate,
    val forceRefresh: Boolean = false,
)

sealed interface CompanionEvent {
    data class CachedHit(val artifact: AuditedArtifact, val isStale: Boolean = false) : CompanionEvent
    data class StreamingDelta(val delta: String, val isThinking: Boolean = false) : CompanionEvent
    data class Done(val artifact: AuditedArtifact) : CompanionEvent
    data class Failure(val error: AppFailure) : CompanionEvent
}

data class TokenEstimate(
    val estimatedInputTokens: Long,
    val estimatedOutputTokens: Long,
    val withinContextWindow: Boolean,
)

// ==================== Deep Module 4: :module-ai & Seams 2, 3 ====================

interface AiRuntime {
    fun streamCompletion(request: LlmPromptRequest): Flow<LlmStreamEvent>
    suspend fun saveProviderConfig(config: ProviderConfig, rawApiKey: String)
    suspend fun listProviderProfiles(): List<ProviderSummary>
}

/** Seam 2: OpenAiCompatAdapter / GeminiAdapter / AnthropicAdapter / OllamaAdapter / ScriptedLlmFakeAdapter */
interface LlmProviderPort {
    val capabilities: ProviderCapabilities
    fun stream(endpoint: String, apiKey: String, payload: LlmPromptRequest): Flow<LlmStreamEvent>
}

/** Seam 3: TinkKeystoreVaultAdapter / InMemorySecretVaultAdapter */
interface SecretVaultPort {
    suspend fun putSecret(alias: String, plaintext: String)
    suspend fun getSecret(alias: String): String?
    suspend fun deleteSecret(alias: String)
}

@Serializable
enum class ProviderKind {
    OPENAI_COMPAT,
    GEMINI,
    ANTHROPIC,
    OLLAMA,
}

data class ProviderCapabilities(
    val supportsJsonSchema: Boolean,
    val supportsVision: Boolean,
    val contextWindow: Int,
)

data class ProviderConfig(
    val id: String,
    val displayName: String,
    val kind: ProviderKind,
    val endpoint: String,
    val modelId: String,
    val capabilities: ProviderCapabilities,
)

data class ProviderSummary(
    val id: String,
    val displayName: String,
    val kind: ProviderKind,
    val modelId: String,
    val hasApiKey: Boolean,
    val capabilities: ProviderCapabilities,
)

data class LlmPromptRequest(
    val providerId: String? = null,
    val systemPrompt: String,
    val userPrompt: String,
    val imagePngBytes: ByteArray? = null,
    val expectJson: Boolean = false,
    val maxOutputTokens: Int? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LlmPromptRequest) return false
        return providerId == other.providerId &&
            systemPrompt == other.systemPrompt &&
            userPrompt == other.userPrompt &&
            imagePngBytes.contentEquals(other.imagePngBytes) &&
            expectJson == other.expectJson &&
            maxOutputTokens == other.maxOutputTokens
    }

    override fun hashCode(): Int {
        var result = providerId?.hashCode() ?: 0
        result = 31 * result + systemPrompt.hashCode()
        result = 31 * result + userPrompt.hashCode()
        result = 31 * result + (imagePngBytes?.contentHashCode() ?: 0)
        result = 31 * result + expectJson.hashCode()
        result = 31 * result + (maxOutputTokens ?: 0)
        return result
    }
}

sealed interface LlmStreamEvent {
    data class ThinkingDelta(val delta: String) : LlmStreamEvent
    data class ContentDelta(val delta: String) : LlmStreamEvent
    data class Completed(val fullText: String) : LlmStreamEvent
    data class Failed(val failure: AppFailure) : LlmStreamEvent
}

// ==================== Deep Module 5: :module-viz ====================

interface VizHost {
    fun attachWebView(hostCallback: (VizHostEvent) -> Unit)
    suspend fun renderArtifact(artifact: AuditedArtifact, theme: ReaderTheme)
}

sealed interface VizHostEvent {
    data class JumpToBlock(val blockId: BlockId) : VizHostEvent
    data object OpenFullscreen : VizHostEvent
    data class Error(val message: String) : VizHostEvent
}

// ==================== Deep Module 6: :module-sync & Seams 4, 5 ====================

interface SyncCoordinator {
    suspend fun pairDesktopViaQr(qrPayload: String): Result<DesktopEndpointInfo>
    suspend fun fetchDesktopCatalog(query: String? = null, onlyWithCache: Boolean = false): Result<List<DesktopBookSummary>>
    suspend fun fetchDesktopOutbox(): Result<List<OutboxTicket>>
    fun pullStudyPacks(workIds: List<WorkId>): Flow<BundleTransferState>
    suspend fun reconcileWork(workId: WorkId): Result<SyncMergeOutcome>
    suspend fun queryCrossBookHits(
        workId: WorkId,
        queryText: String,
        sourcePassage: String?,
    ): Result<List<CrossBookHit>>
}

/** Seam 4: HttpDesktopGatewayAdapter / InMemoryDesktopGatewayAdapter */
interface DesktopBridgePort {
    suspend fun fetchCatalog(token: String, baseUrl: String, query: String?): List<DesktopBookSummary>
    suspend fun fetchOutbox(token: String, baseUrl: String): List<OutboxTicket>
    suspend fun ackOutbox(token: String, baseUrl: String, ticketIds: List<String>)
    suspend fun downloadBundleStream(
        token: String,
        baseUrl: String,
        workId: WorkId,
        destFile: String,
        resumeOffset: Long,
    ): Flow<Long>
    suspend fun searchRelated(
        token: String,
        baseUrl: String,
        excludeWorkId: WorkId,
        query: String,
        sourceText: String?,
    ): List<CrossBookHit>
    suspend fun pushProgressShard(token: String, baseUrl: String, shard: DeviceProgressShard)
}

/** Seam 5: WebDavShardAdapter / S3R2ShardAdapter / InMemoryStateSyncAdapter */
interface StateSyncPort {
    suspend fun putShard(relativePath: String, contentBytes: ByteArray)
    suspend fun listShards(prefix: String): List<String>
    suspend fun getShard(relativePath: String): ByteArray?
}

data class DesktopEndpointInfo(
    val baseUrl: String,
    val pairedDeviceId: DeviceId,
)

sealed interface BundleTransferState {
    data class Downloading(val workId: WorkId, val bytesReceived: Long, val totalBytes: Long?) : BundleTransferState
    data class Ingested(val workId: WorkId) : BundleTransferState
    data class Failed(val workId: WorkId, val failure: AppFailure) : BundleTransferState
}

data class RemoteCursorBanner(
    val sourceDeviceId: DeviceId,
    val remoteCursor: ReadingAnchor,
    val message: String,
)

data class SyncMergeOutcome(
    val workId: WorkId,
    val mergedWatermark: ChapterOrdinal,
    val mergedNoteCount: Int,
    val remoteCursorBanner: RemoteCursorBanner? = null,
)
