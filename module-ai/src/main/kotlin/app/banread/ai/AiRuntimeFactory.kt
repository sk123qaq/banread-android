package app.banread.ai

import android.content.Context
import app.banread.ai.internal.DefaultAiRuntime
import app.banread.model.AiRuntime
import app.banread.model.LlmProviderPort
import app.banread.model.ProviderKind
import app.banread.model.SecretVaultPort
import java.io.File

/**
 * Public entry point for `:module-ai`.
 * All HTTP DTOs, rate limiters, SSE retry mechanics, and Tink keyset persistence stay `internal`.
 */
object AiRuntimeFactory {

    fun defaultAdapters(): Map<ProviderKind, LlmProviderPort> = mapOf(
        ProviderKind.OPENAI_COMPAT to OpenAiCompatAdapter(),
        ProviderKind.GEMINI to GeminiAdapter(),
        ProviderKind.ANTHROPIC to AnthropicAdapter(),
        ProviderKind.OLLAMA to OllamaAdapter(),
    )

    fun create(
        context: Context,
        secretVault: SecretVaultPort = TinkKeystoreVaultAdapter(context),
        providerAdapters: Map<ProviderKind, LlmProviderPort> = defaultAdapters(),
        customProviderAdapters: Map<String, LlmProviderPort> = emptyMap(),
    ): AiRuntime {
        val appContext = context.applicationContext ?: context
        val configDir = File(appContext.filesDir, "banread_ai_runtime")
        return DefaultAiRuntime(
            secretVault = secretVault,
            configDir = configDir,
            providerAdapters = providerAdapters,
            customProviderAdapters = customProviderAdapters,
        )
    }

    fun createInMemory(
        secretVault: SecretVaultPort = InMemorySecretVaultAdapter(),
        configDir: File? = null,
        providerAdapters: Map<ProviderKind, LlmProviderPort> = defaultAdapters(),
        customProviderAdapters: Map<String, LlmProviderPort> = emptyMap(),
    ): AiRuntime = DefaultAiRuntime(
        secretVault = secretVault,
        configDir = configDir,
        providerAdapters = providerAdapters,
        customProviderAdapters = customProviderAdapters,
    )
}
