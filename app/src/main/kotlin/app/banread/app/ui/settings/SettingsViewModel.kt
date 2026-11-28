package app.banread.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.banread.app.data.AppPreferencesPort
import app.banread.model.AiRuntime
import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import app.banread.model.BundleTransferState
import app.banread.model.DeletedProviderSnapshot
import app.banread.model.DesktopBookSummary
import app.banread.model.DiscoveredModel
import app.banread.model.LibraryVault
import app.banread.model.OutboxTicket
import app.banread.model.ProviderCapabilities
import app.banread.model.ProviderConfig
import app.banread.model.ProviderKind
import app.banread.model.ProviderPreset
import app.banread.model.ProviderPresetCatalog
import app.banread.model.ProviderPresetGroup
import app.banread.model.ProviderSummary
import app.banread.model.ReaderTheme
import app.banread.model.ThinkingLevel
import app.banread.model.WorkId
import app.banread.model.WorkSummary
import app.banread.model.resolveModelsEndpoint
import app.banread.reader.FontManager
import app.banread.reader.RegisteredFont
import app.banread.sync.CloudSyncBackend
import app.banread.sync.DefaultSyncCoordinator
import app.banread.sync.LanShareSession
import app.banread.sync.S3R2Config
import app.banread.sync.ShareChannel
import app.banread.sync.ShareCoordinator
import app.banread.sync.ShareManifest
import app.banread.sync.SharePreview
import app.banread.sync.ShareScopeSelection
import app.banread.sync.SyncStatusSnapshot
import app.banread.sync.WebDavConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URI
import java.util.UUID

data class ByokEditorState(
    val isOpen: Boolean = false,
    val editingProviderId: String? = null,
    val selectedPresetId: String = "deepseek",
    val kind: ProviderKind = ProviderKind.OPENAI_COMPAT,
    val endpoint: String = "https://api.deepseek.com/v1",
    val apiKeyInput: String = "",
    val apiKeyVisible: Boolean = false,
    val hasExistingStoredKey: Boolean = false,
    val modelId: String = "deepseek-reasoner",
    val displayName: String = "DeepSeek Reasoner",
    val thinkingLevel: ThinkingLevel = ThinkingLevel.HIGH,
    val supportsVision: Boolean = false,
    val requiresApiKey: Boolean = true,
    val isFetchingModels: Boolean = false,
    val discoveredModels: List<DiscoveredModel> = emptyList(),
    val modelFilterQuery: String = "",
    val fetchStatusMessage: String? = null,
    val fetchErrorMessage: String? = null,
    val validationError: String? = null,
    val isSaving: Boolean = false,
) {
    val isEditMode: Boolean get() = editingProviderId != null

    val modelsEndpointPreview: String
        get() = "模型查询：GET ${resolveModelsEndpoint(kind, endpoint.ifBlank { "https://api.openai.com/v1" })}"

    val thinkingMappingHint: String
        get() = when (kind) {
            ProviderKind.OPENAI_COMPAT -> "OpenAI / OpenCode 映射 → reasoning_effort"
            ProviderKind.GEMINI -> "Gemini 映射 → generationConfig.thinkingConfig"
            ProviderKind.ANTHROPIC -> "Anthropic 映射 → thinking: { type, budget_tokens }"
            ProviderKind.OLLAMA -> "Ollama 映射 → think / options.reasoning"
        }

    val filteredDiscoveredModels: List<DiscoveredModel>
        get() {
            val q = modelFilterQuery.trim().lowercase()
            if (q.isEmpty()) return discoveredModels
            return discoveredModels.filter {
                it.id.lowercase().contains(q) || it.displayName.lowercase().contains(q)
            }
        }
}

enum class SharePresentationMode {
    QR,
    LINK,
}

data class DesktopSyncPanelState(
    val pairUriInput: String = "",
    val isPairing: Boolean = false,
    val isCheckingHealth: Boolean = false,
    val healthStatusText: String? = null,
    val catalogDialogOpen: Boolean = false,
    val catalogQuery: String = "",
    val catalogOnlyWithCache: Boolean = false,
    val isLoadingCatalog: Boolean = false,
    val catalogBooks: List<DesktopBookSummary> = emptyList(),
    val outboxTickets: List<OutboxTicket> = emptyList(),
    val isPullingBundles: Boolean = false,
    val pullingStatusText: String? = null,
    val cloudConfigDialogOpen: Boolean = false,
    val selectedCloudBackend: CloudSyncBackend = CloudSyncBackend.NONE,
    val webDavUrlInput: String = "",
    val webDavUserInput: String = "",
    val webDavSecretInput: String = "",
    val webDavUseBearer: Boolean = false,
    val s3EndpointInput: String = "",
    val s3BucketInput: String = "",
    val s3RegionInput: String = "auto",
    val s3AccessKeyInput: String = "",
    val s3SecretKeyInput: String = "",
    val isSyncingAll: Boolean = false,
)

data class ShareSendDialogState(
    val isOpen: Boolean = false,
    val channel: ShareChannel = ShareChannel.R2_RELAY,
    val presentationMode: SharePresentationMode = SharePresentationMode.QR,
    val selectedWorkId: WorkId? = null,
    val includeMaps: Boolean = true,
    val includeNotes: Boolean = true,
    val includeQa: Boolean = true,
    val includeProgress: Boolean = false,
    val r2IncludeBook: Boolean = false,
    val lanIncludeBook: Boolean = false,
    val exportAsBcShare: Boolean = false,
    val previewManifest: ShareManifest? = null,
    val r2RelayUrlInput: String = "",
    val r2UploadTokenInput: String = "",
    val isConfiguringR2: Boolean = false,
    val generatedR2Url: String? = null,
    val activeLanSession: LanShareSession? = null,
    val exportedPackagePath: String? = null,
    val pendingSystemSharePath: String? = null,
    val isBusy: Boolean = false,
    val errorMessage: String? = null,
)

data class ShareReceiveDialogState(
    val isOpen: Boolean = false,
    val linkOrUriInput: String = "",
    val lanCodeInput: String = "",
    val isParsing: Boolean = false,
    val isImporting: Boolean = false,
    val preview: SharePreview? = null,
    val errorMessage: String? = null,
)

data class SettingsUiState(
    val providers: List<ProviderSummary> = emptyList(),
    val connectionStatuses: Map<String, String> = emptyMap(),
    val testingProviderIds: Set<String> = emptySet(),
    val theme: ReaderTheme = ReaderTheme.SEPIA_WARM,
    val volumeKeyPageTurn: Boolean = true,
    val customFonts: List<RegisteredFont> = emptyList(),
    val editor: ByokEditorState = ByokEditorState(),
    val pendingUndoSnapshot: DeletedProviderSnapshot? = null,
    val snackbarMessage: String? = null,
    val syncStatus: SyncStatusSnapshot? = null,
    val desktopSync: DesktopSyncPanelState = DesktopSyncPanelState(),
    val shareWorks: List<WorkSummary> = emptyList(),
    val shareSend: ShareSendDialogState = ShareSendDialogState(),
    val shareReceive: ShareReceiveDialogState = ShareReceiveDialogState(),
    val lastShareLog: String = "尚未发起双机互传",
)

class SettingsViewModel(
    private val aiRuntime: AiRuntime,
    private val fontManager: FontManager?,
    private val appPreferences: AppPreferencesPort,
    private val syncCoordinator: DefaultSyncCoordinator? = null,
    private val shareCoordinator: ShareCoordinator? = null,
    private val libraryVault: LibraryVault? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            theme = appPreferences.state.value.theme,
            volumeKeyPageTurn = appPreferences.state.value.volumeKeyPageTurn,
        ),
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            appPreferences.state.collect { prefs ->
                _uiState.value = _uiState.value.copy(
                    theme = prefs.theme,
                    volumeKeyPageTurn = prefs.volumeKeyPageTurn,
                )
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val list = aiRuntime.listProviderProfiles()
            val fonts = runCatching { fontManager?.listFonts().orEmpty() }.getOrDefault(emptyList())
            val syncSnap = runCatching { syncCoordinator?.getSyncStatus() }.getOrNull()
            val works = runCatching { libraryVault?.listWorks().orEmpty() }.getOrDefault(emptyList())
            val curDesktop = _uiState.value.desktopSync
            val nextDesktop = if (syncSnap != null) {
                curDesktop.copy(
                    selectedCloudBackend = syncSnap.cloudBackend,
                    webDavUrlInput = curDesktop.webDavUrlInput.ifBlank { syncSnap.webDavConfig?.baseUrl.orEmpty() },
                    webDavUserInput = curDesktop.webDavUserInput.ifBlank { syncSnap.webDavConfig?.username.orEmpty() },
                    webDavUseBearer = syncSnap.webDavConfig?.useBearerAuth ?: curDesktop.webDavUseBearer,
                    s3EndpointInput = curDesktop.s3EndpointInput.ifBlank { syncSnap.s3R2Config?.endpointUrl.orEmpty() },
                    s3BucketInput = curDesktop.s3BucketInput.ifBlank { syncSnap.s3R2Config?.bucket.orEmpty() },
                    s3RegionInput = curDesktop.s3RegionInput.ifBlank { syncSnap.s3R2Config?.region ?: "auto" },
                    s3AccessKeyInput = curDesktop.s3AccessKeyInput.ifBlank { syncSnap.s3R2Config?.accessKeyId.orEmpty() },
                )
            } else {
                curDesktop
            }
            _uiState.value = _uiState.value.copy(
                providers = list,
                customFonts = fonts,
                syncStatus = syncSnap,
                desktopSync = nextDesktop,
                shareWorks = works,
                shareSend = _uiState.value.shareSend.copy(
                    r2RelayUrlInput = _uiState.value.shareSend.r2RelayUrlInput.ifBlank {
                        syncSnap?.r2RelayConfig?.relayBaseUrl.orEmpty()
                    },
                    selectedWorkId = _uiState.value.shareSend.selectedWorkId
                        ?.takeIf { sel -> works.any { it.workId == sel } }
                        ?: works.firstOrNull()?.workId,
                ),
            )
        }
    }

    fun setDefaultCompanion(providerId: String) {
        viewModelScope.launch {
            aiRuntime.setDefaultCompanion(providerId)
            refresh()
            _uiState.value = _uiState.value.copy(snackbarMessage = "已设为默认伴读模型")
        }
    }

    fun setDefaultVision(providerId: String) {
        viewModelScope.launch {
            runCatching { aiRuntime.setDefaultVision(providerId) }
                .onSuccess {
                    refresh()
                    _uiState.value = _uiState.value.copy(snackbarMessage = "已设为默认视觉 VLM 模型")
                }
                .onFailure { err ->
                    _uiState.value = _uiState.value.copy(snackbarMessage = err.message ?: "无法设为默认视觉模型")
                }
        }
    }

    fun testProviderConnection(providerId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                testingProviderIds = _uiState.value.testingProviderIds + providerId,
            )
            val result = aiRuntime.testProviderConnection(providerId)
            val statusText = result.fold(
                onSuccess = { status -> "${status.latencyMs}ms · ${status.message}" },
                onFailure = { err ->
                    val failure = (err as? AppFailureException)?.failure
                    when (failure) {
                        is AppFailure.ProviderAuthOrQuota ->
                            "连接失败${failure.httpCode?.let { " ($it)" }.orEmpty()}：${failure.message}"
                        else -> "连接失败：${err.message ?: "网络不可达"}"
                    }
                },
            )
            _uiState.value = _uiState.value.copy(
                testingProviderIds = _uiState.value.testingProviderIds - providerId,
                connectionStatuses = _uiState.value.connectionStatuses + (providerId to statusText),
            )
        }
    }

    fun deleteProvider(providerId: String) {
        viewModelScope.launch {
            val snapshot = aiRuntime.deleteProviderConfig(providerId) ?: return@launch
            val list = aiRuntime.listProviderProfiles()
            _uiState.value = _uiState.value.copy(
                providers = list,
                pendingUndoSnapshot = snapshot,
                snackbarMessage = "已移除「${snapshot.summary.displayName}」",
            )
        }
    }

    fun undoDeleteProvider() {
        val snap = _uiState.value.pendingUndoSnapshot ?: return
        viewModelScope.launch {
            aiRuntime.restoreDeletedProvider(snap)
            val list = aiRuntime.listProviderProfiles()
            _uiState.value = _uiState.value.copy(
                providers = list,
                pendingUndoSnapshot = null,
                snackbarMessage = "已恢复模型：${snap.summary.displayName}",
            )
        }
    }

    fun clearUndoSnapshot() {
        _uiState.value = _uiState.value.copy(pendingUndoSnapshot = null)
    }

    fun clearSnackbarMessage() {
        _uiState.value = _uiState.value.copy(snackbarMessage = null)
    }


    fun openAddByokDialog() {
        val preset = ProviderPresetCatalog.findById("deepseek") ?: ProviderPresetCatalog.presets.first()
        val firstHint = preset.suggestedModels.firstOrNull()
        _uiState.value = _uiState.value.copy(
            editor = ByokEditorState(
                isOpen = true,
                editingProviderId = null,
                selectedPresetId = preset.id,
                kind = preset.kind,
                endpoint = preset.defaultEndpoint,
                apiKeyInput = "",
                hasExistingStoredKey = false,
                modelId = firstHint?.modelId ?: preset.defaultModelId,
                displayName = firstHint?.displayName ?: preset.displayName,
                thinkingLevel = firstHint?.defaultThinkingLevel ?: ThinkingLevel.OFF,
                supportsVision = firstHint?.supportsVision ?: false,
                requiresApiKey = preset.requiresApiKey,
            ),
        )
    }

    fun openEditByokDialog(summary: ProviderSummary) {
        val presetId = summary.providerPresetId ?: when (summary.kind) {
            ProviderKind.GEMINI -> "gemini"
            ProviderKind.ANTHROPIC -> "anthropic"
            ProviderKind.OLLAMA -> "ollama"
            ProviderKind.OPENAI_COMPAT -> "custom"
        }
        val preset = ProviderPresetCatalog.findById(presetId)
        _uiState.value = _uiState.value.copy(
            editor = ByokEditorState(
                isOpen = true,
                editingProviderId = summary.id,
                selectedPresetId = preset?.id ?: "custom",
                kind = summary.kind,
                endpoint = summary.endpoint.ifBlank { preset?.defaultEndpoint.orEmpty() },
                apiKeyInput = "",
                hasExistingStoredKey = summary.hasApiKey,
                modelId = summary.modelId,
                displayName = summary.displayName,
                thinkingLevel = summary.thinkingLevel,
                supportsVision = summary.capabilities.supportsVision,
                requiresApiKey = summary.requiresApiKey,
            ),
        )
    }

    fun dismissByokDialog() {
        _uiState.value = _uiState.value.copy(editor = ByokEditorState(isOpen = false))
    }

    fun applyPreset(presetId: String) {
        val preset = ProviderPresetCatalog.findById(presetId) ?: return
        val firstHint = preset.suggestedModels.firstOrNull()
        val cur = _uiState.value.editor
        _uiState.value = _uiState.value.copy(
            editor = cur.copy(
                selectedPresetId = preset.id,
                kind = preset.kind,
                endpoint = preset.defaultEndpoint,
                requiresApiKey = preset.requiresApiKey,
                modelId = firstHint?.modelId ?: preset.defaultModelId,
                displayName = firstHint?.displayName ?: preset.displayName,
                thinkingLevel = firstHint?.defaultThinkingLevel ?: cur.thinkingLevel,
                supportsVision = firstHint?.supportsVision ?: false,
                discoveredModels = emptyList(),
                fetchStatusMessage = null,
                fetchErrorMessage = null,
                validationError = null,
            ),
        )
    }

    fun updateEditorKind(kind: ProviderKind) {
        val cur = _uiState.value.editor
        val requiresKey = kind != ProviderKind.OLLAMA
        _uiState.value = _uiState.value.copy(
            editor = cur.copy(
                kind = kind,
                requiresApiKey = requiresKey,
                discoveredModels = emptyList(),
                fetchStatusMessage = null,
                fetchErrorMessage = null,
            ),
        )
    }

    fun updateEditorEndpoint(endpoint: String) {
        _uiState.value = _uiState.value.copy(
            editor = _uiState.value.editor.copy(endpoint = endpoint, validationError = null),
        )
    }

    fun updateEditorApiKey(apiKey: String) {
        _uiState.value = _uiState.value.copy(
            editor = _uiState.value.editor.copy(apiKeyInput = apiKey, validationError = null),
        )
    }

    fun toggleEditorApiKeyVisibility() {
        val cur = _uiState.value.editor
        _uiState.value = _uiState.value.copy(
            editor = cur.copy(apiKeyVisible = !cur.apiKeyVisible),
        )
    }

    fun updateEditorModelId(modelId: String) {
        _uiState.value = _uiState.value.copy(
            editor = _uiState.value.editor.copy(
                modelId = modelId,
                modelFilterQuery = modelId,
                validationError = null,
            ),
        )
    }

    fun updateEditorDisplayName(name: String) {
        _uiState.value = _uiState.value.copy(
            editor = _uiState.value.editor.copy(displayName = name, validationError = null),
        )
    }

    fun updateEditorThinkingLevel(level: ThinkingLevel) {
        _uiState.value = _uiState.value.copy(
            editor = _uiState.value.editor.copy(thinkingLevel = level),
        )
    }

    fun updateEditorSupportsVision(supportsVision: Boolean) {
        _uiState.value = _uiState.value.copy(
            editor = _uiState.value.editor.copy(supportsVision = supportsVision),
        )
    }

    fun selectDiscoveredModel(model: DiscoveredModel) {
        val cur = _uiState.value.editor
        val presetName = ProviderPresetCatalog.findById(cur.selectedPresetId)?.displayName
        val nextName = if (cur.displayName.isBlank() || cur.displayName == presetName || !cur.isEditMode) {
            model.displayName.ifBlank { model.id }
        } else {
            cur.displayName
        }
        _uiState.value = _uiState.value.copy(
            editor = cur.copy(
                modelId = model.id,
                displayName = nextName,
                supportsVision = if (model.supportsVision) true else cur.supportsVision,
                modelFilterQuery = "",
                validationError = null,
            ),
        )
    }

    fun fetchAvailableModels() {
        val cur = _uiState.value.editor
        val endpointErr = validateByokEndpoint(cur.endpoint)
        if (endpointErr != null) {
            _uiState.value = _uiState.value.copy(
                editor = cur.copy(
                    isFetchingModels = false,
                    fetchStatusMessage = null,
                    fetchErrorMessage = endpointErr,
                ),
            )
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                editor = cur.copy(
                    isFetchingModels = true,
                    fetchStatusMessage = null,
                    fetchErrorMessage = null,
                ),
            )
            val result = aiRuntime.listAvailableModels(
                kind = cur.kind,
                endpoint = cur.endpoint,
                rawApiKey = cur.apiKeyInput.takeIf { it.isNotBlank() },
                existingProviderId = cur.editingProviderId,
            )
            result.fold(
                onSuccess = { models ->
                    _uiState.value = _uiState.value.copy(
                        editor = _uiState.value.editor.copy(
                            isFetchingModels = false,
                            discoveredModels = models,
                            modelFilterQuery = "",
                            fetchStatusMessage = "已拉取 ${models.size} 个可用模型 · 点击条目直接填入",
                            fetchErrorMessage = null,
                        ),
                    )
                },
                onFailure = { err ->
                    val failure = (err as? AppFailureException)?.failure
                    val msg = when (failure) {
                        is AppFailure.ProviderAuthOrQuota ->
                            "拉取失败${failure.httpCode?.let { " (HTTP $it)" }.orEmpty()}：${failure.message}"
                        else -> "拉取失败：${err.message ?: "请检查网络或 Endpoint"}"
                    }
                    _uiState.value = _uiState.value.copy(
                        editor = _uiState.value.editor.copy(
                            isFetchingModels = false,
                            discoveredModels = emptyList(),
                            fetchStatusMessage = null,
                            fetchErrorMessage = msg,
                        ),
                    )
                },
            )
        }
    }

    fun saveByokEditor() {
        val cur = _uiState.value.editor
        val cleanEndpoint = cur.endpoint.trim()
        val cleanModelId = cur.modelId.trim()
        val cleanDisplayName = cur.displayName.trim().ifBlank { cleanModelId }

        val endpointErr = validateByokEndpoint(cleanEndpoint)
        if (endpointErr != null) {
            _uiState.value = _uiState.value.copy(
                editor = cur.copy(validationError = endpointErr),
            )
            return
        }
        if (cleanModelId.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                editor = cur.copy(validationError = "模型 ID 不能为空"),
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(editor = cur.copy(isSaving = true))
            val existing = cur.editingProviderId?.let { id ->
                _uiState.value.providers.firstOrNull { it.id == id }
            }
            val id = existing?.id ?: "${cur.selectedPresetId}_${UUID.randomUUID().toString().take(8)}"
            val config = ProviderConfig(
                id = id,
                displayName = cleanDisplayName,
                kind = cur.kind,
                endpoint = cleanEndpoint,
                modelId = cleanModelId,
                capabilities = ProviderCapabilities(
                    supportsJsonSchema = cur.kind != ProviderKind.ANTHROPIC,
                    supportsVision = cur.supportsVision,
                    contextWindow = existing?.capabilities?.contextWindow ?: defaultContextWindowFor(cur.kind),
                ),
                providerPresetId = cur.selectedPresetId,
                thinkingLevel = cur.thinkingLevel,
                isDefaultCompanion = existing?.isDefaultCompanion ?: false,
                isDefaultVision = if (cur.supportsVision) (existing?.isDefaultVision ?: false) else false,
                requiresApiKey = cur.requiresApiKey,
            )

            val rawKeyToSave = cur.apiKeyInput.trim().takeIf { it.isNotEmpty() }
            runCatching {
                aiRuntime.saveProviderConfig(config, rawApiKey = rawKeyToSave)
            }.onSuccess {
                val list = aiRuntime.listProviderProfiles()
                _uiState.value = _uiState.value.copy(
                    providers = list,
                    editor = ByokEditorState(isOpen = false),
                    snackbarMessage = if (cur.isEditMode) "已更新模型配置：$cleanDisplayName" else "已加密保存模型：$cleanDisplayName",
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    editor = _uiState.value.editor.copy(
                        isSaving = false,
                        validationError = err.message ?: "保存配置失败",
                    ),
                )
            }
        }
    }

    fun removeEditingProvider() {
        val targetId = _uiState.value.editor.editingProviderId ?: return
        dismissByokDialog()
        deleteProvider(targetId)
    }


    fun selectTheme(theme: ReaderTheme) {
        appPreferences.setTheme(theme)
    }

    fun toggleVolumeKeyPageTurn(enabled: Boolean) {
        appPreferences.setVolumeKeyPageTurn(enabled)
    }

    fun importCustomFont(sourceUri: String) {
        val fm = fontManager ?: return
        viewModelScope.launch {
            fm.importFont(sourceUri).fold(
                onSuccess = { reg ->
                    val fonts = fm.listFonts()
                    _uiState.value = _uiState.value.copy(
                        customFonts = fonts,
                        snackbarMessage = "已导入字体：${reg.displayName}",
                    )
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        snackbarMessage = "字体导入失败：${err.message ?: "不支持的字体格式"}",
                    )
                },
            )
        }
    }


    fun updatePairUriInput(value: String) {
        _uiState.value = _uiState.value.copy(
            desktopSync = _uiState.value.desktopSync.copy(pairUriInput = value),
        )
    }

    fun pairDesktop() {
        val coord = syncCoordinator ?: return
        val raw = _uiState.value.desktopSync.pairUriInput.trim()
        if (raw.isEmpty()) {
            _uiState.value = _uiState.value.copy(snackbarMessage = "请先粘贴 bc-pair://v1?... 配对链接")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                desktopSync = _uiState.value.desktopSync.copy(isPairing = true),
            )
            coord.pairDesktopViaQr(raw).fold(
                onSuccess = { info ->
                    val snap = coord.getSyncStatus()
                    val healthy = coord.checkDesktopHealth().getOrNull()
                    _uiState.value = _uiState.value.copy(
                        syncStatus = snap.copy(desktop = snap.desktop.copy(isReachable = healthy)),
                        desktopSync = _uiState.value.desktopSync.copy(
                            isPairing = false,
                            pairUriInput = "",
                            healthStatusText = when (healthy) {
                                true -> "桌面网关在线 (${info.baseUrl})"
                                false -> "桌面网关返回异常"
                                null -> "已配对（暂未连上 ${info.baseUrl}）"
                            },
                        ),
                        snackbarMessage = "已配对桌面网关：${info.baseUrl}",
                    )
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        desktopSync = _uiState.value.desktopSync.copy(isPairing = false),
                        snackbarMessage = "配对失败：${err.message ?: "无效的配对二维码"}",
                    )
                },
            )
        }
    }

    fun unpairDesktop() {
        val coord = syncCoordinator ?: return
        viewModelScope.launch {
            coord.unpairDesktop()
            refresh()
            _uiState.value = _uiState.value.copy(
                desktopSync = _uiState.value.desktopSync.copy(healthStatusText = null),
                snackbarMessage = "已解除桌面网关配对",
            )
        }
    }

    fun checkDesktopHealth() {
        val coord = syncCoordinator ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                desktopSync = _uiState.value.desktopSync.copy(isCheckingHealth = true),
            )
            coord.checkDesktopHealth().fold(
                onSuccess = { ok ->
                    val snap = coord.getSyncStatus()
                    _uiState.value = _uiState.value.copy(
                        syncStatus = snap.copy(desktop = snap.desktop.copy(isReachable = ok)),
                        desktopSync = _uiState.value.desktopSync.copy(
                            isCheckingHealth = false,
                            healthStatusText = if (ok) "连接正常 · /api/mobile/v1/health OK" else "网关未就绪",
                        ),
                    )
                },
                onFailure = { err ->
                    val snap = coord.getSyncStatus()
                    _uiState.value = _uiState.value.copy(
                        syncStatus = snap.copy(desktop = snap.desktop.copy(isReachable = false)),
                        desktopSync = _uiState.value.desktopSync.copy(
                            isCheckingHealth = false,
                            healthStatusText = "离线：${err.message ?: "无法连接"}",
                        ),
                    )
                },
            )
        }
    }

    fun openDesktopCatalogDialog() {
        _uiState.value = _uiState.value.copy(
            desktopSync = _uiState.value.desktopSync.copy(catalogDialogOpen = true),
        )
        loadDesktopCatalog()
    }

    fun dismissDesktopCatalogDialog() {
        _uiState.value = _uiState.value.copy(
            desktopSync = _uiState.value.desktopSync.copy(catalogDialogOpen = false),
        )
    }

    fun updateDesktopCatalogQuery(query: String) {
        _uiState.value = _uiState.value.copy(
            desktopSync = _uiState.value.desktopSync.copy(catalogQuery = query),
        )
    }

    fun toggleDesktopCatalogOnlyWithCache(onlyWithCache: Boolean) {
        _uiState.value = _uiState.value.copy(
            desktopSync = _uiState.value.desktopSync.copy(catalogOnlyWithCache = onlyWithCache),
        )
        loadDesktopCatalog()
    }

    fun loadDesktopCatalog() {
        val coord = syncCoordinator ?: return
        val cur = _uiState.value.desktopSync
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                desktopSync = cur.copy(isLoadingCatalog = true),
            )
            coord.fetchDesktopCatalog(
                query = cur.catalogQuery.takeIf { it.isNotBlank() },
                onlyWithCache = cur.catalogOnlyWithCache,
            ).fold(
                onSuccess = { books ->
                    _uiState.value = _uiState.value.copy(
                        desktopSync = _uiState.value.desktopSync.copy(
                            isLoadingCatalog = false,
                            catalogBooks = books,
                        ),
                    )
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        desktopSync = _uiState.value.desktopSync.copy(isLoadingCatalog = false),
                        snackbarMessage = "拉取桌面书目失败：${err.message ?: "请检查配对与网络"}",
                    )
                },
            )
        }
    }

    fun pullDesktopOutbox() {
        val coord = syncCoordinator ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                desktopSync = _uiState.value.desktopSync.copy(
                    isPullingBundles = true,
                    pullingStatusText = "正在查询桌面待接收队列…",
                ),
            )
            coord.fetchDesktopOutbox().fold(
                onSuccess = { tickets ->
                    if (tickets.isEmpty()) {
                        _uiState.value = _uiState.value.copy(
                            desktopSync = _uiState.value.desktopSync.copy(
                                isPullingBundles = false,
                                outboxTickets = emptyList(),
                                pullingStatusText = "待接收队列为空",
                            ),
                            snackbarMessage = "桌面待接收队列为空",
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            desktopSync = _uiState.value.desktopSync.copy(outboxTickets = tickets),
                        )
                        pullStudyPackList(tickets.map { it.uid })
                    }
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        desktopSync = _uiState.value.desktopSync.copy(
                            isPullingBundles = false,
                            pullingStatusText = "拉取 Outbox 失败：${err.message ?: "离线"}",
                        ),
                    )
                },
            )
        }
    }

    fun pullSingleStudyPack(workId: WorkId) {
        viewModelScope.launch {
            pullStudyPackList(listOf(workId))
        }
    }

    private suspend fun pullStudyPackList(workIds: List<WorkId>) {
        val coord = syncCoordinator ?: return
        _uiState.value = _uiState.value.copy(
            desktopSync = _uiState.value.desktopSync.copy(
                isPullingBundles = true,
                pullingStatusText = "准备拉取 ${workIds.size} 本 .bcbook…",
            ),
        )
        var ingestedCount = 0
        var lastError: String? = null
        coord.pullStudyPacks(workIds).collect { state ->
            when (state) {
                is BundleTransferState.Downloading -> {
                    val kb = maxOf(1L, state.bytesReceived / 1024L)
                    _uiState.value = _uiState.value.copy(
                        desktopSync = _uiState.value.desktopSync.copy(
                            pullingStatusText = "下载 ${state.workId.raw}：已接收 ${kb} KB…",
                        ),
                    )
                }
                is BundleTransferState.Ingested -> {
                    ingestedCount++
                    _uiState.value = _uiState.value.copy(
                        desktopSync = _uiState.value.desktopSync.copy(
                            pullingStatusText = "已入库并核销 ${state.workId.raw} ($ingestedCount/${workIds.size})",
                        ),
                    )
                }
                is BundleTransferState.Failed -> {
                    lastError = state.failure.toString()
                }
            }
        }
        refresh()
        val finalMsg = if (lastError != null) {
            "部分拉取中断（已入库 $ingestedCount/${workIds.size}）：$lastError"
        } else {
            "已完成拉取并核销 $ingestedCount 本伴读包"
        }
        _uiState.value = _uiState.value.copy(
            desktopSync = _uiState.value.desktopSync.copy(
                isPullingBundles = false,
                pullingStatusText = finalMsg,
            ),
            snackbarMessage = finalMsg,
        )
    }

    fun openCloudConfigDialog() {
        _uiState.value = _uiState.value.copy(
            desktopSync = _uiState.value.desktopSync.copy(cloudConfigDialogOpen = true),
        )
    }

    fun dismissCloudConfigDialog() {
        _uiState.value = _uiState.value.copy(
            desktopSync = _uiState.value.desktopSync.copy(cloudConfigDialogOpen = false),
        )
    }

    fun updateCloudConfigFields(transform: (DesktopSyncPanelState) -> DesktopSyncPanelState) {
        _uiState.value = _uiState.value.copy(
            desktopSync = transform(_uiState.value.desktopSync),
        )
    }

    fun saveCloudShardConfig() {
        val coord = syncCoordinator ?: return
        val cfg = _uiState.value.desktopSync
        viewModelScope.launch {
            runCatching {
                when (cfg.selectedCloudBackend) {
                    CloudSyncBackend.NONE -> coord.disableCloudSync()
                    CloudSyncBackend.WEBDAV -> coord.configureWebDav(
                        WebDavConfig(
                            baseUrl = cfg.webDavUrlInput,
                            username = cfg.webDavUserInput.trim(),
                            useBearerAuth = cfg.webDavUseBearer,
                        ),
                        rawSecret = cfg.webDavSecretInput.takeIf { it.isNotBlank() },
                    )
                    CloudSyncBackend.S3_R2 -> coord.configureS3R2(
                        S3R2Config(
                            endpointUrl = cfg.s3EndpointInput,
                            bucket = cfg.s3BucketInput,
                            region = cfg.s3RegionInput.ifBlank { "auto" },
                            accessKeyId = cfg.s3AccessKeyInput,
                        ),
                        rawSecretAccessKey = cfg.s3SecretKeyInput.takeIf { it.isNotBlank() },
                    )
                }
            }.onSuccess {
                refresh()
                _uiState.value = _uiState.value.copy(
                    desktopSync = _uiState.value.desktopSync.copy(
                        cloudConfigDialogOpen = false,
                        webDavSecretInput = "",
                        s3SecretKeyInput = "",
                    ),
                    snackbarMessage = "云端分片配置已加密保存",
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    snackbarMessage = "保存失败：${err.message ?: "请检查配置"}",
                )
            }
        }
    }

    fun syncAllBooksNow() {
        val coord = syncCoordinator ?: return
        val vault = libraryVault ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                desktopSync = _uiState.value.desktopSync.copy(isSyncingAll = true),
            )
            coord.flushPendingQueue()
            val works = vault.listWorks()
            var synced = 0
            var queued = 0
            for (w in works) {
                val res = coord.reconcileWork(w.workId)
                if (res.isSuccess) synced++ else queued++
            }
            val snap = coord.getSyncStatus()
            _uiState.value = _uiState.value.copy(
                syncStatus = snap,
                desktopSync = _uiState.value.desktopSync.copy(isSyncingAll = false),
                snackbarMessage = if (queued == 0) {
                    "已完成 $synced 本书分片同步"
                } else {
                    "已同步 $synced 本，$queued 本已加入联网自动重试队列"
                },
            )
        }
    }


    fun openShareSendDialog(
        channel: ShareChannel = ShareChannel.R2_RELAY,
        mode: SharePresentationMode = SharePresentationMode.QR,
    ) {
        viewModelScope.launch {
            val works = runCatching { libraryVault?.listWorks().orEmpty() }.getOrDefault(_uiState.value.shareWorks)
            val chosenWork = _uiState.value.shareSend.selectedWorkId
                ?.takeIf { id -> works.any { it.workId == id } }
                ?: works.firstOrNull()?.workId
            val syncSnap = runCatching { syncCoordinator?.getSyncStatus() }.getOrNull() ?: _uiState.value.syncStatus
            _uiState.value = _uiState.value.copy(
                shareWorks = works,
                syncStatus = syncSnap,
                shareSend = _uiState.value.shareSend.copy(
                    isOpen = true,
                    channel = channel,
                    presentationMode = mode,
                    selectedWorkId = chosenWork,
                    exportAsBcShare = false,
                    errorMessage = null,
                    exportedPackagePath = null,
                    pendingSystemSharePath = null,
                    r2RelayUrlInput = _uiState.value.shareSend.r2RelayUrlInput.ifBlank {
                        syncSnap?.r2RelayConfig?.relayBaseUrl.orEmpty()
                    },
                ),
            )
            refreshShareManifestPreview()
        }
    }

    fun dismissShareSendDialog() {
        shareCoordinator?.stopLanShareServer()
        _uiState.value = _uiState.value.copy(
            shareSend = _uiState.value.shareSend.copy(
                isOpen = false,
                activeLanSession = null,
                pendingSystemSharePath = null,
                errorMessage = null,
            ),
        )
    }

    fun updateShareSendOptions(transform: (ShareSendDialogState) -> ShareSendDialogState) {
        val next = transform(_uiState.value.shareSend)
        _uiState.value = _uiState.value.copy(shareSend = next.copy(errorMessage = null))
        refreshShareManifestPreview()
    }

    private fun refreshShareManifestPreview() {
        val share = shareCoordinator ?: return
        val cur = _uiState.value.shareSend
        val workId = cur.selectedWorkId ?: return
        val includeBook = when (cur.channel) {
            ShareChannel.R2_RELAY -> cur.r2IncludeBook
            ShareChannel.LAN_DIRECT -> cur.lanIncludeBook
            ShareChannel.OFFLINE_PACKAGE -> cur.exportAsBcShare
        }
        viewModelScope.launch {
            runCatching {
                share.estimateShareManifest(
                    workId = workId,
                    selection = ShareScopeSelection(
                        includeMaps = cur.includeMaps,
                        includeNotes = cur.includeNotes,
                        includeQa = cur.includeQa,
                        includeProgress = cur.includeProgress,
                        includeBookFile = includeBook,
                    ),
                )
            }.onSuccess { manifest ->
                _uiState.value = _uiState.value.copy(
                    shareSend = _uiState.value.shareSend.copy(previewManifest = manifest),
                )
            }
        }
    }

    fun saveR2RelayConfig() {
        val share = shareCoordinator ?: return
        val cur = _uiState.value.shareSend
        viewModelScope.launch {
            runCatching {
                share.configureR2Relay(
                    relayBaseUrl = cur.r2RelayUrlInput,
                    uploadToken = cur.r2UploadTokenInput.takeIf { it.isNotBlank() },
                )
            }.onSuccess {
                refresh()
                _uiState.value = _uiState.value.copy(
                    shareSend = _uiState.value.shareSend.copy(
                        isConfiguringR2 = false,
                        r2UploadTokenInput = "",
                        errorMessage = null,
                    ),
                    snackbarMessage = "已启用 R2 24h E2EE 加密中转",
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    shareSend = _uiState.value.shareSend.copy(
                        errorMessage = err.message ?: "R2 中转地址无效",
                    ),
                )
            }
        }
    }

    fun sendViaR2RelayNow() {
        val share = shareCoordinator ?: return
        val cur = _uiState.value.shareSend
        val workId = cur.selectedWorkId ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                shareSend = _uiState.value.shareSend.copy(isBusy = true, errorMessage = null),
            )
            share.sendViaR2Relay(
                workId = workId,
                selection = ShareScopeSelection(
                    includeMaps = cur.includeMaps,
                    includeNotes = cur.includeNotes,
                    includeQa = cur.includeQa,
                    includeProgress = cur.includeProgress,
                    includeBookFile = cur.r2IncludeBook,
                ),
            ).fold(
                onSuccess = { res ->
                    _uiState.value = _uiState.value.copy(
                        shareSend = _uiState.value.shareSend.copy(
                            isBusy = false,
                            generatedR2Url = res.shareUrl,
                            previewManifest = res.manifest,
                        ),
                        lastShareLog = "最近传输：R2 24h E2EE · 《${res.manifest.title}》(${formatBytesShort(res.uploadedBytes)})",
                    )
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        shareSend = _uiState.value.shareSend.copy(
                            isBusy = false,
                            errorMessage = err.message ?: "R2 上传失败",
                        ),
                    )
                },
            )
        }
    }

    fun startLanDirectShareNow() {
        val share = shareCoordinator ?: return
        val cur = _uiState.value.shareSend
        val workId = cur.selectedWorkId ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                shareSend = _uiState.value.shareSend.copy(isBusy = true, errorMessage = null),
            )
            share.startLanShareServer(
                workId = workId,
                selection = ShareScopeSelection(
                    includeMaps = cur.includeMaps,
                    includeNotes = cur.includeNotes,
                    includeQa = cur.includeQa,
                    includeProgress = cur.includeProgress,
                    includeBookFile = cur.lanIncludeBook,
                ),
            ).fold(
                onSuccess = { session ->
                    _uiState.value = _uiState.value.copy(
                        shareSend = _uiState.value.shareSend.copy(
                            isBusy = false,
                            activeLanSession = session,
                            previewManifest = session.manifest,
                        ),
                        lastShareLog = "最近传输：局域网直连已就绪 · 《${session.manifest.title}》(${session.pairingCode})",
                    )
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        shareSend = _uiState.value.shareSend.copy(
                            isBusy = false,
                            errorMessage = err.message ?: "无法启动局域网直连服务",
                        ),
                    )
                },
            )
        }
    }

    fun stopLanDirectShare() {
        shareCoordinator?.stopLanShareServer()
        _uiState.value = _uiState.value.copy(
            shareSend = _uiState.value.shareSend.copy(activeLanSession = null),
        )
    }

    fun exportOfflinePackageNow() {
        val share = shareCoordinator ?: return
        val cur = _uiState.value.shareSend
        val workId = cur.selectedWorkId ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                shareSend = _uiState.value.shareSend.copy(isBusy = true, errorMessage = null),
            )
            share.exportOfflinePackage(
                workId = workId,
                selection = ShareScopeSelection(
                    includeMaps = cur.includeMaps,
                    includeNotes = cur.includeNotes,
                    includeQa = cur.includeQa,
                    includeProgress = cur.includeProgress,
                    includeBookFile = cur.exportAsBcShare,
                ),
                exportAsBcShare = cur.exportAsBcShare,
            ).fold(
                onSuccess = { res ->
                    val ext = if (cur.exportAsBcShare) ".bcshare" else ".bcnote"
                    _uiState.value = _uiState.value.copy(
                        shareSend = _uiState.value.shareSend.copy(
                            isBusy = false,
                            exportedPackagePath = res.packageFilePath,
                            pendingSystemSharePath = res.packageFilePath,
                            previewManifest = res.manifest,
                        ),
                        lastShareLog = "最近传输：已导出《${res.manifest.title}》$ext (${formatBytesShort(res.uploadedBytes)})",
                    )
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        shareSend = _uiState.value.shareSend.copy(
                            isBusy = false,
                            errorMessage = err.message ?: "导出离线包失败",
                        ),
                    )
                },
            )
        }
    }

    fun consumePendingSystemSharePath() {
        _uiState.value = _uiState.value.copy(
            shareSend = _uiState.value.shareSend.copy(pendingSystemSharePath = null),
        )
    }

    fun openShareReceiveDialog(initialInput: String? = null) {
        _uiState.value = _uiState.value.copy(
            shareReceive = ShareReceiveDialogState(
                isOpen = true,
                linkOrUriInput = initialInput.orEmpty(),
            ),
        )
        if (!initialInput.isNullOrBlank()) {
            parseIncomingShare(initialInput)
        }
    }

    fun dismissShareReceiveDialog() {
        _uiState.value = _uiState.value.copy(shareReceive = ShareReceiveDialogState(isOpen = false))
    }

    fun updateReceiveInput(linkOrUri: String? = null, lanCode: String? = null) {
        val cur = _uiState.value.shareReceive
        _uiState.value = _uiState.value.copy(
            shareReceive = cur.copy(
                linkOrUriInput = linkOrUri ?: cur.linkOrUriInput,
                lanCodeInput = lanCode ?: cur.lanCodeInput,
                errorMessage = null,
            ),
        )
    }

    fun parseIncomingShare(overrideInput: String? = null) {
        val share = shareCoordinator ?: return
        val cur = _uiState.value.shareReceive
        val target = (overrideInput ?: cur.linkOrUriInput).trim()
        if (target.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                shareReceive = cur.copy(errorMessage = "请输入分享链接或选择 .bcnote / .bcshare 文件"),
            )
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                shareReceive = _uiState.value.shareReceive.copy(
                    linkOrUriInput = target,
                    isParsing = true,
                    errorMessage = null,
                ),
            )
            share.previewIncomingShare(
                sourceInput = target,
                lanCodeOverride = cur.lanCodeInput.takeIf { it.isNotBlank() },
            ).fold(
                onSuccess = { preview ->
                    _uiState.value = _uiState.value.copy(
                        shareReceive = _uiState.value.shareReceive.copy(
                            isParsing = false,
                            preview = preview,
                        ),
                    )
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        shareReceive = _uiState.value.shareReceive.copy(
                            isParsing = false,
                            preview = null,
                            errorMessage = err.message ?: "解析分享内容失败",
                        ),
                    )
                },
            )
        }
    }

    fun confirmIncomingShareImport() {
        val share = shareCoordinator ?: return
        val cur = _uiState.value.shareReceive
        val preview = cur.preview ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                shareReceive = cur.copy(isImporting = true, errorMessage = null),
            )
            share.confirmReceiveShare(
                preview = preview,
                lanCodeOverride = cur.lanCodeInput.takeIf { it.isNotBlank() },
            ).fold(
                onSuccess = { res ->
                    refresh()
                    val msg = if (res.skippedBookBinary) {
                        "已跳过原书，无损合并《${res.title}》导图 ${res.importedArtifacts} / 批注 ${res.importedNotes} / 问答 ${res.importedQaTurns}"
                    } else {
                        "已导入新书《${res.title}》并合并伴读资料"
                    }
                    _uiState.value = _uiState.value.copy(
                        shareReceive = ShareReceiveDialogState(isOpen = false),
                        lastShareLog = "最近传输：$msg",
                        snackbarMessage = msg,
                    )
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        shareReceive = _uiState.value.shareReceive.copy(
                            isImporting = false,
                            errorMessage = err.message ?: "接收导入失败",
                        ),
                    )
                },
            )
        }
    }

    fun handleIncomingExternalUri(rawUriOrPayload: String) {
        val clean = rawUriOrPayload.trim()
        if (clean.startsWith("bc-pair://", ignoreCase = true)) {
            updatePairUriInput(clean)
            pairDesktop()
        } else if (clean.isNotEmpty()) {
            openShareReceiveDialog(clean)
        }
    }

    private fun formatBytesShort(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${maxOf(1L, bytes / 1024L)} KB"
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    private fun defaultContextWindowFor(kind: ProviderKind): Int = when (kind) {
        ProviderKind.GEMINI -> 1_000_000
        ProviderKind.ANTHROPIC -> 200_000
        ProviderKind.OPENAI_COMPAT -> 128_000
        ProviderKind.OLLAMA -> 32_768
    }

    companion object {
        internal fun validateByokEndpoint(endpoint: String): String? {
            val clean = endpoint.trim()
            if (clean.isEmpty()) return "Base URL 不能为空"
            val parsed = runCatching { URI(clean) }.getOrNull()
            val scheme = parsed?.scheme?.lowercase()
            val host = parsed?.host
            if ((scheme != "http" && scheme != "https") || host.isNullOrBlank()) {
                return "Base URL 须以 http:// 或 https:// 开头"
            }
            return null
        }

        fun presetsByGroup(): Map<ProviderPresetGroup, List<ProviderPreset>> = mapOf(
            ProviderPresetGroup.AGGREGATOR to ProviderPresetCatalog.byGroup(ProviderPresetGroup.AGGREGATOR),
            ProviderPresetGroup.OFFICIAL_OR_LOCAL to ProviderPresetCatalog.byGroup(ProviderPresetGroup.OFFICIAL_OR_LOCAL),
        )

        fun formatProviderCardTitle(summary: ProviderSummary): Pair<String?, String> {
            val preset = summary.providerPresetId?.let { ProviderPresetCatalog.findById(it) }
            val badgeLabel = preset?.displayName ?: when (summary.kind) {
                ProviderKind.OPENAI_COMPAT -> "OpenAI 兼容"
                ProviderKind.GEMINI -> "Gemini"
                ProviderKind.ANTHROPIC -> "Anthropic"
                ProviderKind.OLLAMA -> "Ollama"
            }
            val name = summary.displayName.trim()
            val normalizedBadge = badgeLabel.substringBefore(' ').trim()
            val shouldOmitBadge = name.startsWith(badgeLabel, ignoreCase = true) ||
                (normalizedBadge.length >= 2 && name.startsWith(normalizedBadge, ignoreCase = true))
            return if (shouldOmitBadge) null to name else badgeLabel to name
        }

        fun factory(
            aiRuntime: AiRuntime,
            fontManager: FontManager?,
            appPreferences: AppPreferencesPort,
            syncCoordinator: DefaultSyncCoordinator? = null,
            shareCoordinator: ShareCoordinator? = null,
            libraryVault: LibraryVault? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(
                    aiRuntime = aiRuntime,
                    fontManager = fontManager,
                    appPreferences = appPreferences,
                    syncCoordinator = syncCoordinator,
                    shareCoordinator = shareCoordinator,
                    libraryVault = libraryVault,
                ) as T
        }
    }
}
