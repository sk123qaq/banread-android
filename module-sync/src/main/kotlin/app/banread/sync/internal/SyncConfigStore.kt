package app.banread.sync.internal

import app.banread.model.DeviceId
import app.banread.sync.CloudSyncBackend
import app.banread.sync.R2RelayConfig
import app.banread.sync.S3R2Config
import app.banread.sync.WebDavConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
internal data class PersistedSyncConfig(
    val deviceId: String = "",
    val desktopBaseUrl: String? = null,
    val desktopPairedAtMs: Long? = null,
    val cloudBackend: CloudSyncBackend = CloudSyncBackend.NONE,
    val webDavConfig: WebDavConfig? = null,
    val s3R2Config: S3R2Config? = null,
    val r2RelayBaseUrl: String? = null,
    val lastSyncSummary: String? = null,
)

internal class SyncConfigStore(
    private val configFile: File,
    initialDeviceId: DeviceId? = null,
) {
    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    @Volatile
    private var cached: PersistedSyncConfig = loadOrInit(initialDeviceId)

    private fun loadOrInit(initialDeviceId: DeviceId?): PersistedSyncConfig {
        val loaded = if (configFile.isFile) {
            runCatching {
                json.decodeFromString(PersistedSyncConfig.serializer(), configFile.readText(Charsets.UTF_8))
            }.getOrNull()
        } else {
            null
        }
        val effectiveDevId = initialDeviceId?.raw
            ?: loaded?.deviceId?.takeIf { it.isNotBlank() }
            ?: "android-${UUID.randomUUID().toString().replace("-", "").take(10)}"
        val config = (loaded ?: PersistedSyncConfig()).copy(deviceId = effectiveDevId)
        if (loaded == null || loaded.deviceId != effectiveDevId) {
            writeAtomic(config)
        }
        return config
    }

    fun read(): PersistedSyncConfig = synchronized(lock) { cached }

    fun update(transform: (PersistedSyncConfig) -> PersistedSyncConfig): PersistedSyncConfig =
        synchronized(lock) {
            val next = transform(cached)
            cached = next
            writeAtomic(next)
            next
        }

    fun currentDeviceId(): DeviceId = DeviceId(read().deviceId)

    fun r2RelayConfigOrNull(hasUploadToken: Boolean): R2RelayConfig? {
        val url = read().r2RelayBaseUrl?.takeIf { it.isNotBlank() } ?: return null
        return R2RelayConfig(relayBaseUrl = url, hasUploadToken = hasUploadToken)
    }

    private fun writeAtomic(cfg: PersistedSyncConfig) {
        configFile.parentFile?.mkdirs()
        val tmp = File(configFile.parentFile, "${configFile.name}.tmp.${UUID.randomUUID().toString().take(8)}")
        tmp.writeText(json.encodeToString(PersistedSyncConfig.serializer(), cfg), Charsets.UTF_8)
        if (!tmp.renameTo(configFile)) {
            configFile.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        const val ALIAS_DESKTOP_TOKEN = "sync:desktop:bearer_token"
        const val ALIAS_WEBDAV_SECRET = "sync:cloud:webdav_secret"
        const val ALIAS_S3R2_SECRET = "sync:cloud:s3r2_secret_key"
        const val ALIAS_R2_RELAY_TOKEN = "sync:share:r2_relay_token"
    }
}
