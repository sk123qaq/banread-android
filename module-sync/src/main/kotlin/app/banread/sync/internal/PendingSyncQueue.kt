package app.banread.sync.internal

import app.banread.model.WorkId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
internal sealed interface PendingSyncOp {
    val id: String
    val workId: String
    val enqueuedAtMs: Long

    @Serializable
    @SerialName("reconcile_work")
    data class ReconcileWork(
        override val id: String,
        override val workId: String,
        override val enqueuedAtMs: Long,
    ) : PendingSyncOp

    @Serializable
    @SerialName("pull_bundle")
    data class PullStudyPack(
        override val id: String,
        override val workId: String,
        val includeCache: Boolean = true,
        override val enqueuedAtMs: Long,
    ) : PendingSyncOp
}

internal class PendingSyncQueue(
    private val queueFile: File,
) {
    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun list(): List<PendingSyncOp> = synchronized(lock) {
        readUnlocked()
    }

    fun count(): Int = synchronized(lock) {
        readUnlocked().size
    }

    fun enqueueReconcile(workId: WorkId, nowMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        val current = readUnlocked().toMutableList()
        val alreadyQueued = current.any { it is PendingSyncOp.ReconcileWork && it.workId == workId.raw }
        if (!alreadyQueued) {
            current += PendingSyncOp.ReconcileWork(
                id = "op-${UUID.randomUUID().toString().take(12)}",
                workId = workId.raw,
                enqueuedAtMs = nowMs,
            )
            writeUnlocked(current)
        }
    }

    fun enqueuePullStudyPack(
        workId: WorkId,
        includeCache: Boolean = true,
        nowMs: Long = System.currentTimeMillis(),
    ) = synchronized(lock) {
        val current = readUnlocked().toMutableList()
        val alreadyQueued = current.any { it is PendingSyncOp.PullStudyPack && it.workId == workId.raw }
        if (!alreadyQueued) {
            current += PendingSyncOp.PullStudyPack(
                id = "op-${UUID.randomUUID().toString().take(12)}",
                workId = workId.raw,
                includeCache = includeCache,
                enqueuedAtMs = nowMs,
            )
            writeUnlocked(current)
        }
    }

    fun remove(opId: String) = synchronized(lock) {
        val current = readUnlocked()
        val next = current.filterNot { it.id == opId }
        if (next.size != current.size) {
            writeUnlocked(next)
        }
    }

    fun clear() = synchronized(lock) {
        writeUnlocked(emptyList())
    }

    private fun readUnlocked(): List<PendingSyncOp> {
        if (!queueFile.isFile) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(PendingSyncOp.serializer()), queueFile.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    private fun writeUnlocked(ops: List<PendingSyncOp>) {
        queueFile.parentFile?.mkdirs()
        val tmp = File(queueFile.parentFile, "${queueFile.name}.tmp.${UUID.randomUUID().toString().take(8)}")
        tmp.writeText(json.encodeToString(ListSerializer(PendingSyncOp.serializer()), ops), Charsets.UTF_8)
        if (!tmp.renameTo(queueFile)) {
            queueFile.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
            tmp.delete()
        }
    }
}
