package app.banread.model

import kotlinx.serialization.Serializable

@Serializable
enum class ThinkingLevel(val labelZh: String) {
    OFF("关"),
    LOW("低"),
    MEDIUM("中"),
    HIGH("高"),
    MAX("极高"),
}

@Serializable
enum class ProviderPresetGroup(val displayLabel: String) {
    AGGREGATOR("聚合 / 代理"),
    OFFICIAL_OR_LOCAL("官方直连"),
}

@Serializable
data class PresetModelHint(
    val modelId: String,
    val displayName: String,
    val supportsVision: Boolean = false,
    val defaultThinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
)

@Serializable
data class ProviderPreset(
    val id: String,
    val displayName: String,
    val group: ProviderPresetGroup,
    val kind: ProviderKind,
    val defaultEndpoint: String,
    val requiresApiKey: Boolean = true,
    val defaultModelId: String = "",
    val suggestedModels: List<PresetModelHint> = emptyList(),
)

object ProviderPresetCatalog {
    val presets: List<ProviderPreset> = listOf(
        // 聚合 / 代理 (4)
        ProviderPreset(
            id = "opencode",
            displayName = "OpenCode Zen",
            group = ProviderPresetGroup.AGGREGATOR,
            kind = ProviderKind.OPENAI_COMPAT,
            defaultEndpoint = "https://opencode.ai/zen/v1",
            requiresApiKey = true,
            defaultModelId = "claude-sonnet-4-5",
            suggestedModels = listOf(
                PresetModelHint("claude-sonnet-4-5", "OpenCode · Claude Sonnet 4.5", supportsVision = true, ThinkingLevel.MEDIUM),
                PresetModelHint("gpt-5.2", "OpenCode · GPT-5.2", supportsVision = true, ThinkingLevel.MEDIUM),
                PresetModelHint("gemini-2.5-pro", "OpenCode · Gemini 2.5 Pro", supportsVision = true, ThinkingLevel.HIGH),
                PresetModelHint("deepseek-reasoner", "OpenCode · DeepSeek Reasoner", supportsVision = false, ThinkingLevel.HIGH),
                PresetModelHint("qwen3-coder", "OpenCode · Qwen3 Coder", supportsVision = false, ThinkingLevel.OFF),
            ),
        ),
        ProviderPreset(
            id = "openrouter",
            displayName = "OpenRouter",
            group = ProviderPresetGroup.AGGREGATOR,
            kind = ProviderKind.OPENAI_COMPAT,
            defaultEndpoint = "https://openrouter.ai/api/v1",
            requiresApiKey = true,
            defaultModelId = "anthropic/claude-sonnet-4.5",
            suggestedModels = listOf(
                PresetModelHint("anthropic/claude-sonnet-4.5", "OpenRouter · Claude Sonnet 4.5", supportsVision = true, ThinkingLevel.MEDIUM),
                PresetModelHint("openai/o3-mini", "OpenRouter · o3-mini", supportsVision = false, ThinkingLevel.HIGH),
                PresetModelHint("google/gemini-2.5-flash", "OpenRouter · Gemini 2.5 Flash", supportsVision = true, ThinkingLevel.LOW),
                PresetModelHint("deepseek/deepseek-r1", "OpenRouter · DeepSeek R1", supportsVision = false, ThinkingLevel.HIGH),
            ),
        ),
        ProviderPreset(
            id = "siliconflow",
            displayName = "硅基流动 SiliconFlow",
            group = ProviderPresetGroup.AGGREGATOR,
            kind = ProviderKind.OPENAI_COMPAT,
            defaultEndpoint = "https://api.siliconflow.cn/v1",
            requiresApiKey = true,
            defaultModelId = "Pro/deepseek-ai/DeepSeek-R1",
            suggestedModels = listOf(
                PresetModelHint("Pro/deepseek-ai/DeepSeek-R1", "硅基流动 · DeepSeek R1", supportsVision = false, ThinkingLevel.HIGH),
                PresetModelHint("Qwen/Qwen2.5-VL-72B-Instruct", "硅基流动 · Qwen2.5-VL 72B", supportsVision = true, ThinkingLevel.OFF),
                PresetModelHint("Qwen/Qwen3-32B", "硅基流动 · Qwen3 32B", supportsVision = false, ThinkingLevel.MEDIUM),
            ),
        ),
        ProviderPreset(
            id = "custom",
            displayName = "自定义兼容网关",
            group = ProviderPresetGroup.AGGREGATOR,
            kind = ProviderKind.OPENAI_COMPAT,
            defaultEndpoint = "https://your-gateway.example/v1",
            requiresApiKey = true,
            defaultModelId = "",
        ),
        // 官方直连 / 本地 (5)
        ProviderPreset(
            id = "deepseek",
            displayName = "DeepSeek",
            group = ProviderPresetGroup.OFFICIAL_OR_LOCAL,
            kind = ProviderKind.OPENAI_COMPAT,
            defaultEndpoint = "https://api.deepseek.com/v1",
            requiresApiKey = true,
            defaultModelId = "deepseek-reasoner",
            suggestedModels = listOf(
                PresetModelHint("deepseek-reasoner", "DeepSeek Reasoner", supportsVision = false, ThinkingLevel.HIGH),
                PresetModelHint("deepseek-chat", "DeepSeek Chat", supportsVision = false, ThinkingLevel.OFF),
            ),
        ),
        ProviderPreset(
            id = "openai",
            displayName = "OpenAI",
            group = ProviderPresetGroup.OFFICIAL_OR_LOCAL,
            kind = ProviderKind.OPENAI_COMPAT,
            defaultEndpoint = "https://api.openai.com/v1",
            requiresApiKey = true,
            defaultModelId = "gpt-4.1",
            suggestedModels = listOf(
                PresetModelHint("gpt-4.1", "OpenAI GPT-4.1", supportsVision = true, ThinkingLevel.OFF),
                PresetModelHint("o4-mini", "OpenAI o4-mini", supportsVision = true, ThinkingLevel.HIGH),
                PresetModelHint("gpt-4o", "OpenAI GPT-4o", supportsVision = true, ThinkingLevel.OFF),
            ),
        ),
        ProviderPreset(
            id = "gemini",
            displayName = "Google Gemini",
            group = ProviderPresetGroup.OFFICIAL_OR_LOCAL,
            kind = ProviderKind.GEMINI,
            defaultEndpoint = "https://generativelanguage.googleapis.com/v1beta",
            requiresApiKey = true,
            defaultModelId = "gemini-2.5-flash",
            suggestedModels = listOf(
                PresetModelHint("gemini-2.5-pro", "Gemini 2.5 Pro", supportsVision = true, ThinkingLevel.HIGH),
                PresetModelHint("gemini-2.5-flash", "Gemini 2.5 Flash", supportsVision = true, ThinkingLevel.LOW),
                PresetModelHint("gemini-2.5-flash-lite", "Gemini 2.5 Flash-Lite", supportsVision = true, ThinkingLevel.OFF),
            ),
        ),
        ProviderPreset(
            id = "anthropic",
            displayName = "Anthropic",
            group = ProviderPresetGroup.OFFICIAL_OR_LOCAL,
            kind = ProviderKind.ANTHROPIC,
            defaultEndpoint = "https://api.anthropic.com",
            requiresApiKey = true,
            defaultModelId = "claude-sonnet-4-5",
            suggestedModels = listOf(
                PresetModelHint("claude-sonnet-4-5", "Claude Sonnet 4.5", supportsVision = true, ThinkingLevel.MEDIUM),
                PresetModelHint("claude-3-7-sonnet-20250219", "Claude 3.7 Sonnet", supportsVision = true, ThinkingLevel.MEDIUM),
                PresetModelHint("claude-3-5-haiku", "Claude 3.5 Haiku", supportsVision = false, ThinkingLevel.OFF),
            ),
        ),
        ProviderPreset(
            id = "ollama",
            displayName = "Ollama 本地",
            group = ProviderPresetGroup.OFFICIAL_OR_LOCAL,
            kind = ProviderKind.OLLAMA,
            defaultEndpoint = "http://127.0.0.1:11434",
            requiresApiKey = false,
            defaultModelId = "qwen3:14b",
            suggestedModels = listOf(
                PresetModelHint("qwen3:14b", "Ollama 本地 qwen3:14b", supportsVision = false, ThinkingLevel.MEDIUM),
                PresetModelHint("deepseek-r1:14b", "Ollama 本地 deepseek-r1:14b", supportsVision = false, ThinkingLevel.HIGH),
                PresetModelHint("gemma3:12b", "Ollama 本地 gemma3:12b", supportsVision = true, ThinkingLevel.OFF),
            ),
        ),
    )

    fun byGroup(group: ProviderPresetGroup): List<ProviderPreset> =
        presets.filter { it.group == group }

    fun findById(id: String): ProviderPreset? =
        presets.firstOrNull { it.id == id }
}

/**
 * Normalizes a base endpoint URL into the model discovery endpoint for [kind],
 * trimming trailing slashes and preventing duplicate `/v1/v1` segments.
 */
fun resolveModelsEndpoint(kind: ProviderKind, endpoint: String): String {
    val clean = endpoint.trim().trimEnd('/')
    return when (kind) {
        ProviderKind.OPENAI_COMPAT -> when {
            clean.endsWith("/models") -> clean
            clean.endsWith("/chat/completions") -> clean.removeSuffix("/chat/completions") + "/models"
            clean.endsWith("/v1") -> "$clean/models"
            else -> "$clean/v1/models"
        }
        ProviderKind.GEMINI -> when {
            clean.endsWith("/models") -> clean
            clean.endsWith("/v1beta") || clean.endsWith("/v1") -> "$clean/models"
            else -> "$clean/v1beta/models"
        }
        ProviderKind.ANTHROPIC -> when {
            clean.endsWith("/models") -> clean
            clean.endsWith("/messages") -> clean.removeSuffix("/messages") + "/models"
            clean.endsWith("/v1") -> "$clean/models"
            else -> "$clean/v1/models"
        }
        ProviderKind.OLLAMA -> when {
            clean.endsWith("/api/tags") -> clean
            clean.endsWith("/api/chat") -> clean.removeSuffix("/api/chat") + "/api/tags"
            else -> "$clean/api/tags"
        }
    }
}

/**
 * Masks an API key for UI display without exposing plaintext secrets.
 */
fun maskApiKey(rawApiKey: String?, requiresApiKey: Boolean = true): String {
    val trimmed = rawApiKey?.trim().orEmpty()
    if (!requiresApiKey || trimmed.isEmpty()) return "本地免 Key"
    if (trimmed.length < 8) return "••••"
    return "${trimmed.take(4)}••••${trimmed.takeLast(4)}"
}

data class DiscoveredModel(
    val id: String,
    val displayName: String = id,
    val ownedBy: String? = null,
    val supportsVision: Boolean = false,
    val supportsThinking: Boolean = false,
)

data class ProviderConnectionStatus(
    val ok: Boolean,
    val latencyMs: Long,
    val modelCount: Int = 0,
    val message: String = "",
)

data class DeletedProviderSnapshot(
    val restoreToken: String,
    val summary: ProviderSummary,
    val previousIndex: Int,
)
