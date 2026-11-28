package app.banread.ai.internal

import app.banread.model.AiRuntime
import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import app.banread.model.DeletedProviderSnapshot
import app.banread.model.DiscoveredModel
import app.banread.model.LlmPromptRequest
import app.banread.model.LlmProviderPort
import app.banread.model.LlmStreamEvent
import app.banread.model.ProviderCapabilities
import app.banread.model.ProviderConfig
import app.banread.model.ProviderConnectionStatus
import app.banread.model.ProviderKind
import app.banread.model.ProviderSummary
import app.banread.model.SecretVaultPort
import app.banread.model.ThinkingLevel
import app.banread.model.maskApiKey
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
import java.util.UUID
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
    val providerPresetId: String? = null,
    val thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    val isDefaultCompanion: Boolean = false,
    val isDefaultVision: Boolean = false,
    val requiresApiKey: Boolean = kind != ProviderKind.OLLAMA,
)

@Serializable
internal data class ProviderStoreSnapshotDto(
    val activeProviderId: String? = null,
    val defaultCompanionId: String? = null,
    val defaultVisionId: String? = null,
    val providers: List<StoredProviderMetaDto> = emptyList(),
)

internal class DefaultAiRuntime(
    private val secretVault: SecretVaultPort,
    private val configDir: File?,
    private val providerAdapters: Map<ProviderKind, LlmProviderPort>,
    private val customProviderAdapters: Map<String, LlmProviderPort>,
) : AiRuntime {

    private data class InMemoryRestoreEntry(
        val config: ProviderConfig,
        val plaintextSecret: String?,
        val wasDefaultCompanion: Boolean,
        val wasDefaultVision: Boolean,
        val previousIndex: Int,
    )

    private val mutex = Mutex()
    private val limiters = ConcurrentHashMap<String, Semaphore>()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    private var loadedFromDisk = false
    private var defaultCompanionId: String? = null
    private var defaultVisionId: String? = null
    private val configs = linkedMapOf<String, ProviderConfig>()
    private val inMemoryDeletedSnapshots = linkedMapOf<String, InMemoryRestoreEntry>()

    override suspend fun saveProviderConfig(config: ProviderConfig, rawApiKey: String?) {
        require(config.id.isNotBlank()) { "ProviderConfig.id must not be blank" }
        require(config.endpoint.isNotBlank()) { "ProviderConfig.endpoint must not be blank" }
        require(config.modelId.isNotBlank()) { "ProviderConfig.modelId must not be blank" }
        if (config.isDefaultVision) {
            require(config.capabilities.supportsVision) {
                "Provider ${config.id} cannot be default vision when supportsVision=false"
            }
        }

        mutex.withLock {
            ensureLoadedLocked()
            val existing = configs[config.id]
            val prevConfigs = configs.toMap()
            val prevCompanionId = defaultCompanionId
            val prevVisionId = defaultVisionId

            val alias = secretAlias(config.id)
            val prevSecret = if (existing != null) secretVault.getSecret(alias) else null
            if (!config.requiresApiKey) {
                if (rawApiKey.isNullOrBlank()) {
                    secretVault.deleteSecret(alias)
                } else {
                    secretVault.putSecret(alias, rawApiKey)
                }
            } else {
                if (!rawApiKey.isNullOrBlank()) {
                    secretVault.putSecret(alias, rawApiKey)
                } else if (existing == null) {
                    secretVault.deleteSecret(alias)
                }
            }

            try {
                configs[config.id] = config

                when {
                    config.isDefaultCompanion -> defaultCompanionId = config.id
                    defaultCompanionId == null || defaultCompanionId !in configs -> defaultCompanionId = config.id
                }

                when {
                    config.isDefaultVision -> defaultVisionId = config.id
                    existing != null && defaultVisionId == config.id && !config.capabilities.supportsVision -> {
                        defaultVisionId = configs.values.firstOrNull { it.capabilities.supportsVision }?.id
                    }
                    (defaultVisionId == null || defaultVisionId !in configs) && config.capabilities.supportsVision -> {
                        defaultVisionId = config.id
                    }
                }

                syncDefaultFlagsLocked()
                persistLocked()
            } catch (ex: Throwable) {
                configs.clear()
                configs.putAll(prevConfigs)
                defaultCompanionId = prevCompanionId
                defaultVisionId = prevVisionId
                runCatching {
                    if (prevSecret != null) {
                        secretVault.putSecret(alias, prevSecret)
                    } else if (existing == null) {
                        secretVault.deleteSecret(alias)
                    }
                }
                throw ex
            }
        }
    }

    override suspend fun getProviderSummary(providerId: String): ProviderSummary? {
        val (cfg, companionId, visionId) = mutex.withLock {
            ensureLoadedLocked()
            Triple(configs[providerId], defaultCompanionId, defaultVisionId)
        }
        cfg ?: return null
        val secret = secretVault.getSecret(secretAlias(cfg.id))
        return buildSummary(
            cfg = cfg,
            rawSecret = secret,
            isDefaultCompanion = cfg.id == companionId,
            isDefaultVision = cfg.id == visionId,
        )
    }

    override suspend fun listProviderProfiles(): List<ProviderSummary> {
        val (ordered, companionId, visionId) = mutex.withLock {
            ensureLoadedLocked()
            val list = mutableListOf<ProviderConfig>()
            defaultCompanionId?.let { activeId -> configs[activeId]?.let { list += it } }
            configs.values.forEach { cfg ->
                if (cfg.id != defaultCompanionId) list += cfg
            }
            Triple(list.toList(), defaultCompanionId, defaultVisionId)
        }
        return ordered.map { cfg ->
            val secret = secretVault.getSecret(secretAlias(cfg.id))
            buildSummary(
                cfg = cfg,
                rawSecret = secret,
                isDefaultCompanion = cfg.id == companionId,
                isDefaultVision = cfg.id == visionId,
            )
        }
    }

    override suspend fun setDefaultCompanion(providerId: String) {
        mutex.withLock {
            ensureLoadedLocked()
            if (providerId !in configs) {
                throw NoSuchElementException("Provider not found: $providerId")
            }
            defaultCompanionId = providerId
            syncDefaultFlagsLocked()
            persistLocked()
        }
    }

    override suspend fun setDefaultVision(providerId: String) {
        mutex.withLock {
            ensureLoadedLocked()
            val cfg = configs[providerId] ?: throw NoSuchElementException("Provider not found: $providerId")
            require(cfg.capabilities.supportsVision) {
                "Provider $providerId does not support vision (supportsVision=false)"
            }
            defaultVisionId = providerId
            syncDefaultFlagsLocked()
            persistLocked()
        }
    }

    override suspend fun deleteProviderConfig(providerId: String): DeletedProviderSnapshot? {
        return mutex.withLock {
            ensureLoadedLocked()
            val keys = configs.keys.toList()
            val previousIndex = keys.indexOf(providerId)
            if (previousIndex < 0) return@withLock null
            val removed = configs.getValue(providerId)

            val alias = secretAlias(providerId)
            val secret = secretVault.getSecret(alias)
            secretVault.deleteSecret(alias)

            val wasDefaultCompanion = (defaultCompanionId == providerId)
            val wasDefaultVision = (defaultVisionId == providerId)

            configs.remove(providerId)
            if (wasDefaultCompanion) {
                defaultCompanionId = configs.keys.firstOrNull()
            }
            if (wasDefaultVision) {
                defaultVisionId = configs.values.firstOrNull { it.capabilities.supportsVision }?.id
            }
            syncDefaultFlagsLocked()
            persistLocked()

            val token = UUID.randomUUID().toString()
            if (inMemoryDeletedSnapshots.size >= MAX_UNDO_SNAPSHOTS) {
                val oldestKey = inMemoryDeletedSnapshots.keys.firstOrNull()
                if (oldestKey != null) inMemoryDeletedSnapshots.remove(oldestKey)
            }
            inMemoryDeletedSnapshots[token] = InMemoryRestoreEntry(
                config = removed,
                plaintextSecret = secret,
                wasDefaultCompanion = wasDefaultCompanion,
                wasDefaultVision = wasDefaultVision,
                previousIndex = previousIndex,
            )

            DeletedProviderSnapshot(
                restoreToken = token,
                summary = buildSummary(
                    cfg = removed,
                    rawSecret = secret,
                    isDefaultCompanion = wasDefaultCompanion,
                    isDefaultVision = wasDefaultVision,
                ),
                previousIndex = previousIndex,
            )
        }
    }

    override suspend fun restoreDeletedProvider(snapshot: DeletedProviderSnapshot): Boolean {
        return mutex.withLock {
            ensureLoadedLocked()
            val entry = inMemoryDeletedSnapshots.remove(snapshot.restoreToken) ?: return@withLock false
            val existingList = configs.entries.filter { it.key != entry.config.id }.map { it.key to it.value }
            val targetIdx = entry.previousIndex.coerceIn(0, existingList.size)
            configs.clear()
            existingList.forEachIndexed { idx, (id, cfg) ->
                if (idx == targetIdx) {
                    configs[entry.config.id] = entry.config
                }
                configs[id] = cfg
            }
            if (targetIdx == existingList.size) {
                configs[entry.config.id] = entry.config
            }

            val alias = secretAlias(entry.config.id)
            if (!entry.plaintextSecret.isNullOrBlank()) {
                secretVault.putSecret(alias, entry.plaintextSecret)
            } else {
                secretVault.deleteSecret(alias)
            }

            if (entry.wasDefaultCompanion || defaultCompanionId == null) {
                defaultCompanionId = entry.config.id
            }
            if ((entry.wasDefaultVision || defaultVisionId == null) && entry.config.capabilities.supportsVision) {
                defaultVisionId = entry.config.id
            }
            syncDefaultFlagsLocked()
            persistLocked()
            true
        }
    }

    override suspend fun listAvailableModels(
        kind: ProviderKind,
        endpoint: String,
        rawApiKey: String?,
        existingProviderId: String?,
    ): Result<List<DiscoveredModel>> {
        val cleanEndpoint = endpoint.trim()
        if (cleanEndpoint.isEmpty()) {
            return Result.failure(
                AppFailureException(
                    AppFailure.ProviderAuthOrQuota(
                        providerId = existingProviderId ?: kind.name.lowercase(),
                        httpCode = null,
                        message = "Provider endpoint must not be blank",
                    ),
                ),
            )
        }
        val adapter = existingProviderId?.let { customProviderAdapters[it] } ?: providerAdapters[kind]
            ?: return Result.failure(
                AppFailureException(
                    AppFailure.ProviderAuthOrQuota(
                        providerId = existingProviderId ?: kind.name.lowercase(),
                        httpCode = null,
                        message = "No adapter registered for $kind",
                    ),
                ),
            )
        val effectiveKey = resolveQueryApiKey(rawApiKey, existingProviderId)
        return adapter.listModels(cleanEndpoint, effectiveKey)
    }

    override suspend fun listAvailableModels(providerId: String): Result<List<DiscoveredModel>> {
        val cfg = mutex.withLock {
            ensureLoadedLocked()
            configs[providerId]
        } ?: return Result.failure(
            AppFailureException(
                AppFailure.ProviderAuthOrQuota(
                    providerId = providerId,
                    httpCode = null,
                    message = "Provider not found: $providerId",
                ),
            ),
        )
        return listAvailableModels(
            kind = cfg.kind,
            endpoint = cfg.endpoint,
            rawApiKey = null,
            existingProviderId = cfg.id,
        )
    }

    override suspend fun testProviderConnection(
        kind: ProviderKind,
        endpoint: String,
        rawApiKey: String?,
        modelId: String?,
        existingProviderId: String?,
    ): Result<ProviderConnectionStatus> {
        val cleanEndpoint = endpoint.trim()
        if (cleanEndpoint.isEmpty()) {
            return Result.failure(
                AppFailureException(
                    AppFailure.ProviderAuthOrQuota(
                        providerId = existingProviderId ?: kind.name.lowercase(),
                        httpCode = null,
                        message = "Provider endpoint must not be blank",
                    ),
                ),
            )
        }
        val adapter = existingProviderId?.let { customProviderAdapters[it] } ?: providerAdapters[kind]
            ?: return Result.failure(
                AppFailureException(
                    AppFailure.ProviderAuthOrQuota(
                        providerId = existingProviderId ?: kind.name.lowercase(),
                        httpCode = null,
                        message = "No adapter registered for $kind",
                    ),
                ),
            )
        val effectiveKey = resolveQueryApiKey(rawApiKey, existingProviderId)
        return adapter.testConnection(cleanEndpoint, effectiveKey, modelId)
    }

    override suspend fun testProviderConnection(providerId: String): Result<ProviderConnectionStatus> {
        val cfg = mutex.withLock {
            ensureLoadedLocked()
            configs[providerId]
        } ?: return Result.failure(
            AppFailureException(
                AppFailure.ProviderAuthOrQuota(
                    providerId = providerId,
                    httpCode = null,
                    message = "Provider not found: $providerId",
                ),
            ),
        )
        return testProviderConnection(
            kind = cfg.kind,
            endpoint = cfg.endpoint,
            rawApiKey = null,
            modelId = cfg.modelId,
            existingProviderId = cfg.id,
        )
    }

    override fun streamCompletion(request: LlmPromptRequest): Flow<LlmStreamEvent> = flow {
        val config = mutex.withLock {
            ensureLoadedLocked()
            val targetId = request.providerId
            if (targetId != null) {
                configs[targetId]
            } else if (request.imagePngBytes != null) {
                defaultVisionId?.let { configs[it] }
                    ?: defaultCompanionId?.let { configs[it] }
                    ?: configs.values.firstOrNull()
            } else {
                defaultCompanionId?.let { configs[it] } ?: configs.values.firstOrNull()
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
        val effectiveThinking = if (request.thinkingLevel != ThinkingLevel.OFF) {
            request.thinkingLevel
        } else {
            config.thinkingLevel
        }
        val effectiveRequest = request.copy(
            providerId = config.id,
            thinkingLevel = effectiveThinking,
        )

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

    private suspend fun resolveQueryApiKey(rawApiKey: String?, existingProviderId: String?): String {
        if (!rawApiKey.isNullOrBlank()) return rawApiKey
        if (!existingProviderId.isNullOrBlank()) {
            return secretVault.getSecret(secretAlias(existingProviderId)).orEmpty()
        }
        return ""
    }

    private fun buildSummary(
        cfg: ProviderConfig,
        rawSecret: String?,
        isDefaultCompanion: Boolean,
        isDefaultVision: Boolean,
    ): ProviderSummary {
        val hasKey = !rawSecret.isNullOrBlank()
        return ProviderSummary(
            id = cfg.id,
            displayName = cfg.displayName,
            kind = cfg.kind,
            modelId = cfg.modelId,
            hasApiKey = hasKey,
            capabilities = cfg.capabilities,
            providerPresetId = cfg.providerPresetId,
            endpoint = cfg.endpoint,
            thinkingLevel = cfg.thinkingLevel,
            isDefaultCompanion = isDefaultCompanion,
            isDefaultVision = isDefaultVision,
            maskedApiKey = maskApiKey(rawSecret, cfg.requiresApiKey),
            requiresApiKey = cfg.requiresApiKey,
        )
    }

    private fun syncDefaultFlagsLocked() {
        for ((id, cfg) in configs) {
            val comp = (id == defaultCompanionId)
            val vis = (id == defaultVisionId) && cfg.capabilities.supportsVision
            if (cfg.isDefaultCompanion != comp || cfg.isDefaultVision != vis) {
                configs[id] = cfg.copy(
                    isDefaultCompanion = comp,
                    isDefaultVision = vis,
                )
            }
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
                        providerPresetId = item.providerPresetId,
                        thinkingLevel = item.thinkingLevel,
                        isDefaultCompanion = item.isDefaultCompanion,
                        isDefaultVision = item.isDefaultVision,
                        requiresApiKey = item.requiresApiKey,
                    )
                }
                defaultCompanionId = dto.defaultCompanionId?.takeIf { it in configs }
                    ?: dto.activeProviderId?.takeIf { it in configs }
                    ?: configs.values.firstOrNull { it.isDefaultCompanion }?.id
                    ?: configs.keys.firstOrNull()
                defaultVisionId = dto.defaultVisionId?.takeIf { configs[it]?.capabilities?.supportsVision == true }
                    ?: configs.values.firstOrNull { it.isDefaultVision && it.capabilities.supportsVision }?.id
                    ?: configs.values.firstOrNull { it.capabilities.supportsVision }?.id
                syncDefaultFlagsLocked()
            }
        }
    }

    private suspend fun persistLocked() {
        val storeFile = configFile() ?: return
        val snapshot = ProviderStoreSnapshotDto(
            activeProviderId = defaultCompanionId,
            defaultCompanionId = defaultCompanionId,
            defaultVisionId = defaultVisionId,
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
                    providerPresetId = cfg.providerPresetId,
                    thinkingLevel = cfg.thinkingLevel,
                    isDefaultCompanion = cfg.id == defaultCompanionId,
                    isDefaultVision = cfg.id == defaultVisionId,
                    requiresApiKey = cfg.requiresApiKey,
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
        private const val MAX_UNDO_SNAPSHOTS = 16
    }
}
