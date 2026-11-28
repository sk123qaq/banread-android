package app.banread.sync

import app.banread.model.DeviceId
import app.banread.model.ReadProfileId
import app.banread.model.WorkId
import kotlinx.serialization.Serializable

@Serializable
enum class CloudSyncBackend {
    NONE,
    WEBDAV,
    S3_R2,
}

@Serializable
data class WebDavConfig(
    val baseUrl: String,
    val username: String = "",
    val useBearerAuth: Boolean = false,
)

@Serializable
data class S3R2Config(
    val endpointUrl: String,
    val bucket: String,
    val region: String = "auto",
    val accessKeyId: String,
    val keyPrefix: String = "",
    val usePathStyle: Boolean = true,
)

@Serializable
data class R2RelayConfig(
    val relayBaseUrl: String,
    val hasUploadToken: Boolean = false,
)

data class DesktopPairState(
    val isPaired: Boolean = false,
    val baseUrl: String? = null,
    val pairedDeviceId: DeviceId? = null,
    val isReachable: Boolean? = null,
)

data class SyncStatusSnapshot(
    val deviceId: DeviceId,
    val desktop: DesktopPairState = DesktopPairState(),
    val cloudBackend: CloudSyncBackend = CloudSyncBackend.NONE,
    val webDavConfig: WebDavConfig? = null,
    val s3R2Config: S3R2Config? = null,
    val hasCloudSecret: Boolean = false,
    val r2RelayConfig: R2RelayConfig? = null,
    val pendingOpCount: Int = 0,
    val lastSyncSummary: String? = null,
)

enum class ShareChannel {
    R2_RELAY,
    LAN_DIRECT,
    OFFLINE_PACKAGE,
}

data class ShareScopeSelection(
    val includeMaps: Boolean = true,
    val includeNotes: Boolean = true,
    val includeQa: Boolean = true,
    val includeProgress: Boolean = false,
    val includeBookFile: Boolean = false,
)

@Serializable
data class ShareManifest(
    val schemaVersion: Int = 1,
    val shareId: String,
    val bookSourceSha256: String,
    val workId: WorkId,
    val title: String,
    val author: String = "",
    val readProfileId: ReadProfileId,
    val literatureSubKind: String? = null,
    val clothColor: String = "#2C4056",
    val bookFormat: String = "BCBOOK",
    val bookFileName: String = "",
    val bookSizeBytes: Long = 0L,
    val companionSizeBytes: Long = 0L,
    val includeMaps: Boolean = true,
    val includeNotes: Boolean = true,
    val includeQa: Boolean = true,
    val includeProgress: Boolean = false,
    val includeBookFile: Boolean = false,
    val mapCount: Int = 0,
    val noteCount: Int = 0,
    val qaCount: Int = 0,
    val senderDeviceId: DeviceId,
    val createdAtEpochMs: Long,
)

data class SharePreview(
    val channel: ShareChannel,
    val sourceInput: String,
    val manifest: ShareManifest,
    val matchedLocalWorkId: WorkId? = null,
    val matchedLocalTitle: String? = null,
    val willSkipBookDownload: Boolean = false,
)

data class ShareSendResult(
    val channel: ShareChannel,
    val manifest: ShareManifest,
    val shareUrl: String? = null,
    val pairingCode: String? = null,
    val packageFilePath: String? = null,
    val uploadedBytes: Long = 0L,
)

data class ShareReceiveResult(
    val channel: ShareChannel,
    val targetWorkId: WorkId,
    val title: String,
    val skippedBookBinary: Boolean,
    val importedArtifacts: Int,
    val importedNotes: Int,
    val importedQaTurns: Int,
)

data class LanShareSession(
    val shareId: String,
    val baseUrl: String,
    val pairingCode: String,
    val manifest: ShareManifest,
)
