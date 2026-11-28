package app.banread.sync

import app.banread.model.StateSyncPort
import app.banread.sync.internal.ShardRedLineGuard
import java.util.concurrent.ConcurrentHashMap

class InMemoryStateSyncAdapter : StateSyncPort {

    private val objects = ConcurrentHashMap<String, ByteArray>()

    override suspend fun putShard(relativePath: String, contentBytes: ByteArray) {
        val cleanPath = ShardRedLineGuard.normalizeAndValidateShardPath(relativePath)
        ShardRedLineGuard.validatePayloadSize(cleanPath, contentBytes)
        objects[cleanPath] = contentBytes.copyOf()
    }

    override suspend fun listShards(prefix: String): List<String> {
        val cleanPrefix = ShardRedLineGuard.normalizeAndValidatePrefix(prefix)
        val matchPrefix = cleanPrefix.trimEnd('/') + "/"
        return objects.keys.filter { it.startsWith(matchPrefix) }.sorted()
    }

    override suspend fun getShard(relativePath: String): ByteArray? {
        val cleanPath = ShardRedLineGuard.normalizeAndValidateShardPath(relativePath)
        return objects[cleanPath]?.copyOf()
    }

    fun snapshotKeys(): Set<String> = objects.keys.toSet()
}
