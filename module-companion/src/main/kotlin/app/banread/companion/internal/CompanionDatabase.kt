package app.banread.companion.internal

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

@Entity(
    tableName = "companion_results",
    primaryKeys = ["workId", "scopeKey", "progressGateKey", "toolId", "promptVersion"],
    indices = [
        Index(value = ["workId", "toolId", "scopeKey"]),
        Index(value = ["isPinned", "lastAccessedAtMs"]),
    ],
)
internal data class CompanionResultEntity(
    val workId: String,
    val scopeKey: String,
    val progressGateKey: String,
    val toolId: String,
    val promptVersion: Int,
    val chapterContentSha256: String,
    val artifactJson: String,
    val tokenCost: Long,
    val byteSize: Long,
    val isPinned: Boolean,
    val createdAtMs: Long,
    val lastAccessedAtMs: Long,
)

@Entity(
    tableName = "chapter_digests",
    primaryKeys = ["workId", "ordinal"],
)
internal data class ChapterDigestEntity(
    val workId: String,
    val ordinal: Int,
    val chapterTitle: String,
    val chapterContentSha256: String,
    val digestText: String,
    val anchorBlockIdsJson: String,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "qa_threads",
    indices = [Index(value = ["workId", "threadId", "turnIndex"])],
)
internal data class QaThreadEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val threadId: String,
    val workId: String,
    val scopeKey: String,
    val gateOrdinal: Int?,
    val turnIndex: Int,
    val role: String,
    val content: String,
    val createdAtMs: Long,
)

@Dao
internal interface CompanionDao {
    @Query(
        "SELECT * FROM companion_results " +
            "WHERE workId = :workId AND scopeKey = :scopeKey AND progressGateKey = :progressGateKey " +
            "AND toolId = :toolId AND promptVersion = :promptVersion LIMIT 1",
    )
    suspend fun getExactResult(
        workId: String,
        scopeKey: String,
        progressGateKey: String,
        toolId: String,
        promptVersion: Int,
    ): CompanionResultEntity?

    @Query(
        "SELECT * FROM companion_results " +
            "WHERE workId = :workId AND scopeKey = :scopeKey AND toolId = :toolId AND promptVersion = :promptVersion",
    )
    suspend fun getAllGateVariants(
        workId: String,
        scopeKey: String,
        toolId: String,
        promptVersion: Int,
    ): List<CompanionResultEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertResult(entity: CompanionResultEntity)

    @Query(
        "UPDATE companion_results SET lastAccessedAtMs = :nowMs " +
            "WHERE workId = :workId AND scopeKey = :scopeKey AND progressGateKey = :progressGateKey " +
            "AND toolId = :toolId AND promptVersion = :promptVersion",
    )
    suspend fun touchResult(
        workId: String,
        scopeKey: String,
        progressGateKey: String,
        toolId: String,
        promptVersion: Int,
        nowMs: Long,
    )

    @Query("SELECT COALESCE(SUM(byteSize), 0) FROM companion_results")
    suspend fun totalCacheBytes(): Long

    @Query("SELECT * FROM companion_results WHERE isPinned = 0 ORDER BY lastAccessedAtMs ASC LIMIT :limit")
    suspend fun oldestUnpinned(limit: Int): List<CompanionResultEntity>

    @Query(
        "DELETE FROM companion_results " +
            "WHERE workId = :workId AND scopeKey = :scopeKey AND progressGateKey = :progressGateKey " +
            "AND toolId = :toolId AND promptVersion = :promptVersion",
    )
    suspend fun deleteResult(
        workId: String,
        scopeKey: String,
        progressGateKey: String,
        toolId: String,
        promptVersion: Int,
    )

    @Transaction
    suspend fun upsertAndEnforceLru(entity: CompanionResultEntity, maxBytes: Long = MAX_L3_CACHE_BYTES) {
        upsertResult(entity)
        var total = totalCacheBytes()
        while (total > maxBytes) {
            val victims = oldestUnpinned(20)
            if (victims.isEmpty()) break
            for (v in victims) {
                if (total <= maxBytes) break
                deleteResult(v.workId, v.scopeKey, v.progressGateKey, v.toolId, v.promptVersion)
                total -= v.byteSize
            }
        }
    }

    @Query("SELECT * FROM chapter_digests WHERE workId = :workId AND ordinal = :ordinal LIMIT 1")
    suspend fun getChapterDigest(workId: String, ordinal: Int): ChapterDigestEntity?

    @Query("SELECT * FROM chapter_digests WHERE workId = :workId ORDER BY ordinal ASC")
    suspend fun getChapterDigests(workId: String): List<ChapterDigestEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChapterDigest(digest: ChapterDigestEntity)

    @Query("SELECT * FROM qa_threads WHERE workId = :workId AND threadId = :threadId ORDER BY turnIndex ASC")
    suspend fun getQaTurns(workId: String, threadId: String): List<QaThreadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQaTurn(turn: QaThreadEntity)

    companion object {
        const val MAX_L3_CACHE_BYTES = 200L * 1024L * 1024L
    }
}

@Database(
    entities = [
        CompanionResultEntity::class,
        ChapterDigestEntity::class,
        QaThreadEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
internal abstract class CompanionDatabase : RoomDatabase() {
    abstract fun companionDao(): CompanionDao

    companion object {
        fun build(context: Context, allowInMemory: Boolean): CompanionDatabase {
            val builder = if (allowInMemory) {
                Room.inMemoryDatabaseBuilder(context, CompanionDatabase::class.java)
                    .allowMainThreadQueries()
            } else {
                Room.databaseBuilder(context, CompanionDatabase::class.java, "banread_companion_v1.db")
                    .fallbackToDestructiveMigration()
            }
            return builder.build()
        }
    }
}
