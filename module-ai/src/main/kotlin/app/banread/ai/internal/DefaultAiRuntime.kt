package app.banread.ai.internal

import app.banread.ai.internal.HttpStreamingSupport
import app.banread.model.AiRuntime
import app.banread.model.AppFailure
import app.banread.model.LlmPromptRequest
import app.banread.model.LlmProviderPort
import app.banread.model.LlmStreamEvent
import app.banread.model.ProviderCapabilities
import app.banread.model.ProviderConfig
import app.banread.model.ProviderKind
import app.banread.model.ProviderSummary
import app.banread.model.SecretVaultPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Serializable
internal data class StoredCapabilitiesDto(
    val supportsJsonSchema: Boolean,
    val supportsVision: Boolean,
    val contextWindow: Int,
)

@Serializable
internal data class StoredProviderMetaDto(
    val id: String,
    val displayName: String,
    val kind: ProviderKind,
    val endpoint: String,
    val modelId: String,
    val capabilities: StoredCapabilitiesDto,
)

@Serializable
internal data class ProviderStoreSnapshotDto(
    val activeProviderId: String? = null,
    val providers: List<StoredProviderMetaDto> = emptyList(),
)

internal class DefaultAiRuntime(
    private val secretVault: SecretVaultPort,
    private val configDir: File?,
    private val providerAdapters: Map<ProviderKind, LlmProviderPort>,
    private val customProviderAdapters: Map<String, LlmProviderPort>,
) : AiRuntime {

    private val mutex = Mutex()
    private val limiters = ConcurrentHashMap<String, Semaphore>()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    private var loadedFromDisk = false
    private var activeProviderId: String? = null
    private val configs = linkedMapOf<String, ProviderConfig>()

    override suspend fun saveProviderConfig(config: ProviderConfig, rawApiKey: String) {
        require(config.id.isNotBlank()) { "ProviderConfig.id must not be blank" }
        require(config.endpoint.isNotBlank()) { "ProviderConfig.endpoint must not be blank" }
        require(config.modelId.isNotBlank()) { "ProviderConfig.modelId must not be blank" }

        mutex.withLock {
            ensureLoadedLocked()
            configs[config.id] = config
            activeProviderId = config.id
            persistLocked()
        }
        val alias = secretAlias(config.id)
        if (rawApiKey.isNotBlank()) {
            secretVault.putSecret(alias, rawApiKey)
        } else {
            secretVault.deleteSecret(alias)
        }
    }

    override suspend fun listProviderProfiles(): List<ProviderSummary> {
        val snapshot = mutex.withLock {
            ensureLoadedLocked()
            val ordered = mutableListOf<ProviderConfig>()
            activeProviderId?.let { activeId -> configs[activeId]?.let { ordered += it } }
            configs.values.forEach { cfg ->
                if (cfg.id != activeProviderId) ordered += cfg
            }
            ordered.toList()
        }
        return snapshot.map { cfg ->
            val hasKey = !secretVault.getSecret(secretAlias(cfg.id)).isNullOrBlank()
            ProviderSummary(
                id = cfg.id,
                displayName = cfg.displayName,
                kind = cfg.kind,
                modelId = cfg.modelId,
                hasApiKey = hasKey,
                capabilities = cfg.capabilities,
            )
        }
    }

    override fun streamCompletion(request: LlmPromptRequest): Flow<LlmStreamEvent> = flow {
        val config = mutex.withLock {
            ensureLoadedLocked()
            val targetId = request.providerId
            if (targetId != null) {
                configs[targetId]
            } else {
                activeProviderId?.let { configs[it] } ?: configs.values.firstOrNull()
            }
        }
        if (config == null) {
            emit(
                LlmStreamEvent.Failed(
                    AppFailure.ProviderAuthOrQuota(
                        providerId = request.providerId ?: "none",
                        httpCode = null,
                        message = "No LLM provider configured",
                    ),
                ),
            )
            return@flow
        }

        val adapter = customProviderAdapters[config.id] ?: providerAdapters[config.kind]
        if (adapter == null) {
            emit(
                LlmStreamEvent.Failed(
                    AppFailure.ProviderAuthOrQuota(
                        providerId = config.id,
                        httpCode = null,
                        message = "No adapter registered for ${config.kind}",
                    ),
                ),
            )
            return@flow
        }

        val apiKey = secretVault.getSecret(secretAlias(config.id)).orEmpty()
        val semaphore = limiters.computeIfAbsent(config.id) { Semaphore(permits = MAX_CONCURRENT_PER_PROVIDER) }
        val effectiveRequest = if (request.providerId == config.id) {
            request
        } else {
            request.copy(providerId = config.id)
        }

        semaphore.withPermit {
            val upstream = adapter.stream(config.endpoint, apiKey, effectiveRequest)
                .flowOn(ResolvedModelContext(config.modelId))
                .catch { ex ->
                    if (ex is CancellationException) throw ex
                    emit(
                        LlmStreamEvent.Failed(
                            AppFailure.ProviderAuthOrQuota(
                                providerId = config.id,
                                httpCode = null,
                                message = Redactor.redact(ex.message ?: "Provider stream failure", apiKey),
                            ),
                        ),
                    )
                }
            emitAll(upstream)
        }
    }

    private suspend fun ensureLoadedLocked() {
        if (loadedFromDisk) return
        loadedFromDisk = true
        val storeFile = configFile() ?: return
        if (!storeFile.isFile) return
        withContext(Dispatchers.IO) {
            runCatching {
                val text = storeFile.readText(Charsets.UTF_8)
                val dto = json.decodeFromString(ProviderStoreSnapshotDto.serializer(), text)
                configs.clear()
                for (item in dto.providers) {
                    configs[item.id] = ProviderConfig(
                        id = item.id,
                        displayName = item.displayName,
                        kind = item.kind,
                        endpoint = item.endpoint,
                        modelId = item.modelId,
                        capabilities = ProviderCapabilities(
                            supportsJsonSchema = item.capabilities.supportsJsonSchema,
                            supportsVision = item.capabilities.supportsVision,
                            contextWindow = item.capabilities.contextWindow,
                        ),
                    )
                }
                activeProviderId = dto.activeProviderId
            }
        }
    }

    private suspend fun persistLocked() {
        val storeFile = configFile() ?: return
        val snapshot = ProviderStoreSnapshotDto(
            activeProviderId = activeProviderId,
            providers = configs.values.map { cfg ->
                StoredProviderMetaDto(
                    id = cfg.id,
                    displayName = cfg.displayName,
                    kind = cfg.kind,
                    endpoint = cfg.endpoint,
                    modelId = cfg.modelId,
                    capabilities = StoredCapabilitiesDto(
                        supportsJsonSchema = cfg.capabilities.supportsJsonSchema,
                        supportsVision = cfg.capabilities.supportsVision,
                        contextWindow = cfg.capabilities.contextWindow,
                    ),
                )
            },
        )
        val serialized = json.encodeToString(snapshot)
        withContext(Dispatchers.IO) {
            storeFile.parentFile?.mkdirs()
            val tmp = File(storeFile.parentFile, "${storeFile.name}.tmp")
            tmp.writeText(serialized, Charsets.UTF_8)
            if (!tmp.renameTo(storeFile)) {
                storeFile.writeText(serialized, Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    private fun configFile(): File? = configDir?.let { File(it, "providers_meta.json") }

    private fun secretAlias(providerId: String): String = "provider_api_key_$providerId"

    companion object {
        private const val MAX_CONCURRENT_PER_PROVIDER = 2
    }
}
