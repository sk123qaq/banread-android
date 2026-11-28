package app.banread.app

import app.banread.ai.AiRuntimeFactory
import app.banread.ai.InMemorySecretVaultAdapter
import app.banread.app.data.InMemoryAppPreferencesStore
import app.banread.app.ui.settings.SettingsViewModel
import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import app.banread.model.DiscoveredModel
import app.banread.model.LlmPromptRequest
import app.banread.model.LlmProviderPort
import app.banread.model.LlmStreamEvent
import app.banread.model.ProviderCapabilities
import app.banread.model.ProviderConfig
import app.banread.model.ProviderKind
import app.banread.model.ProviderPresetCatalog
import app.banread.model.ProviderPresetGroup
import app.banread.model.ThinkingLevel
import app.banread.model.resolveModelsEndpoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @Test
    fun `editor state redacts plaintext api keys and only exposes masked summaries`() = runTest {
        val runtime = AiRuntimeFactory.createInMemory()
        val vm = SettingsViewModel(runtime, null, InMemoryAppPreferencesStore())
        vm.openAddByokDialog()

        val plaintext = "sk-live-1234567890"
        vm.updateEditorApiKey(plaintext)

        assertEquals(plaintext, vm.uiState.value.editor.apiKeyInput.raw)
        assertFalse(vm.uiState.value.editor.toString().contains(plaintext))
        assertFalse(vm.uiState.value.toString().contains(plaintext))
        assertFalse(vm.uiState.value.editor.existingKey.isConfigured)
    }

    @Test
    fun `presets catalog exposes 9 grouped presets and live resolveModelsEndpoint preview`() = runTest {
        val grouped = SettingsViewModel.presetsByGroup()
        assertEquals(4, grouped[ProviderPresetGroup.AGGREGATOR]?.size)
        assertEquals(5, grouped[ProviderPresetGroup.OFFICIAL_OR_LOCAL]?.size)
        assertEquals(9, ProviderPresetCatalog.presets.size)

        val runtime = AiRuntimeFactory.createInMemory()
        val vm = SettingsViewModel(runtime, null, InMemoryAppPreferencesStore())

        vm.openAddByokDialog()
        vm.applyPreset("gemini")
        assertEquals(ProviderKind.GEMINI, vm.uiState.value.editor.kind)
        assertTrue(
            vm.uiState.value.editor.modelsEndpointPreview.contains(
                resolveModelsEndpoint(ProviderKind.GEMINI, "https://generativelanguage.googleapis.com/v1beta"),
            ),
        )

        vm.applyPreset("ollama")
        assertEquals(ProviderKind.OLLAMA, vm.uiState.value.editor.kind)
        assertFalse(vm.uiState.value.editor.requiresApiKey)
        assertTrue(vm.uiState.value.editor.modelsEndpointPreview.endsWith("/api/tags"))
    }

    @Test
    fun `online model discovery, auth failure, edit preserving secret, swipe delete and undo`() = runTest {
        var shouldFailDiscovery = false
        val fakeOpenAiAdapter = object : LlmProviderPort {
            override val capabilities = ProviderCapabilities(true, true, 128_000)
            override fun stream(endpoint: String, apiKey: String, payload: LlmPromptRequest): Flow<LlmStreamEvent> = emptyFlow()
            override suspend fun listModels(endpoint: String, apiKey: String): Result<List<DiscoveredModel>> {
                return if (shouldFailDiscovery) {
                    Result.failure(AppFailureException(AppFailure.ProviderAuthOrQuota("openai", 401, "Invalid API key")))
                } else {
                    Result.success(
                        listOf(
                            DiscoveredModel("gpt-4.1", "GPT-4.1", supportsVision = true),
                            DiscoveredModel("o4-mini", "o4-mini", supportsVision = false),
                        ),
                    )
                }
            }
        }

        val secretVault = InMemorySecretVaultAdapter()
        val runtime = AiRuntimeFactory.createInMemory(
            secretVault = secretVault,
            providerAdapters = mapOf(
                ProviderKind.OPENAI_COMPAT to fakeOpenAiAdapter,
                ProviderKind.GEMINI to fakeOpenAiAdapter,
                ProviderKind.ANTHROPIC to fakeOpenAiAdapter,
                ProviderKind.OLLAMA to fakeOpenAiAdapter,
            ),
        )

        runtime.saveProviderConfig(
            ProviderConfig(
                id = "p1",
                displayName = "DeepSeek Reasoner",
                kind = ProviderKind.OPENAI_COMPAT,
                endpoint = "https://api.deepseek.com/v1",
                modelId = "deepseek-reasoner",
                capabilities = ProviderCapabilities(true, false, 128_000),
                providerPresetId = "deepseek",
                thinkingLevel = ThinkingLevel.HIGH,
                isDefaultCompanion = true,
            ),
            rawApiKey = "sk-secret-1234",
        )

        val vm = SettingsViewModel(runtime, null, InMemoryAppPreferencesStore())
        assertEquals(1, vm.uiState.value.providers.size)

        val (badge, title) = SettingsViewModel.formatProviderCardTitle(vm.uiState.value.providers.first())
        assertEquals(null, badge)
        assertEquals("DeepSeek Reasoner", title)

        vm.openAddByokDialog()
        vm.applyPreset("openai")
        vm.updateEditorApiKey("sk-openai-9999")
        shouldFailDiscovery = true
        vm.fetchAvailableModels()
        assertNotNull(vm.uiState.value.editor.fetchErrorMessage)
        assertTrue(vm.uiState.value.editor.fetchErrorMessage!!.contains("401"))

        shouldFailDiscovery = false
        vm.fetchAvailableModels()
        assertEquals(2, vm.uiState.value.editor.discoveredModels.size)
        vm.selectDiscoveredModel(vm.uiState.value.editor.discoveredModels.first())
        assertEquals("gpt-4.1", vm.uiState.value.editor.modelId)
        assertTrue(vm.uiState.value.editor.supportsVision)
        vm.updateEditorThinkingLevel(ThinkingLevel.MAX)
        vm.saveByokEditor()

        assertEquals(2, vm.uiState.value.providers.size)

        val p1 = vm.uiState.value.providers.first { it.id == "p1" }
        vm.openEditByokDialog(p1)
        assertEquals("", vm.uiState.value.editor.apiKeyInput.raw)
        vm.updateEditorThinkingLevel(ThinkingLevel.LOW)
        vm.saveByokEditor()

        val updatedP1 = runtime.getProviderSummary("p1")!!
        assertTrue(updatedP1.hasApiKey)
        assertEquals("sk-s••••1234", updatedP1.maskedApiKey.display)
        assertEquals(ThinkingLevel.LOW, updatedP1.thinkingLevel)

        vm.deleteProvider("p1")
        assertEquals(1, vm.uiState.value.providers.size)
        assertTrue(vm.uiState.value.providers.first().isDefaultCompanion)
        assertNotNull(vm.uiState.value.pendingUndoSnapshot)

        vm.undoDeleteProvider()
        assertEquals(2, vm.uiState.value.providers.size)
        assertTrue(vm.uiState.value.providers.first { it.id == "p1" }.isDefaultCompanion)
    }

    @Test
    fun `BYOK editor rejects endpoint without http or https scheme in both fetchAvailableModels and saveByokEditor`() = runTest {
        val runtime = AiRuntimeFactory.createInMemory()
        val vm = SettingsViewModel(runtime, null, InMemoryAppPreferencesStore())

        vm.openAddByokDialog()
        vm.updateEditorEndpoint("api.deepseek.com/v1")
        vm.updateEditorModelId("deepseek-chat")

        vm.fetchAvailableModels()
        assertEquals("Base URL 须以 http:// 或 https:// 开头", vm.uiState.value.editor.fetchErrorMessage)

        vm.saveByokEditor()
        assertEquals("Base URL 须以 http:// 或 https:// 开头", vm.uiState.value.editor.validationError)
        assertTrue(vm.uiState.value.editor.isOpen)
        assertTrue(runtime.listProviderProfiles().isEmpty())
    }
}
