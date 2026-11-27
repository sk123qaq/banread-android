package app.banread.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.banread.app.data.AppPreferencesPort
import app.banread.model.AiRuntime
import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import app.banread.model.DeletedProviderSnapshot
import app.banread.model.DiscoveredModel
import app.banread.model.ProviderCapabilities
import app.banread.model.ProviderConfig
import app.banread.model.ProviderKind
import app.banread.model.ProviderPreset
import app.banread.model.ProviderPresetCatalog
import app.banread.model.ProviderPresetGroup
import app.banread.model.ProviderSummary
import app.banread.model.ReaderTheme
import app.banread.model.ThinkingLevel
import app.banread.model.resolveModelsEndpoint
import app.banread.reader.FontManager
import app.banread.reader.RegisteredFont
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
)

class SettingsViewModel(
    private val aiRuntime: AiRuntime,
    private val fontManager: FontManager?,
    private val appPreferences: AppPreferencesPort,
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
            _uiState.value = _uiState.value.copy(
                providers = list,
                customFonts = fonts,
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

    // ==================== BYOK Editor Dialog ====================

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

            // Blank apiKeyInput on Edit preserves stored Tink secret in DefaultAiRuntime
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

    // ==================== App Preferences ====================

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

        /**
         * Avoids repeating the preset name if the provider's displayName already begins with it.
         */
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
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(aiRuntime, fontManager, appPreferences) as T
        }
    }
}
