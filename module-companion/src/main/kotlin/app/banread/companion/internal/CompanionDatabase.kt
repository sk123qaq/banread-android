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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(
    tableName = "companion_results",
    primaryKeys = ["workId", "scopeKey", "toolId", "promptVersion"],
    indices = [
        Index(value = ["workId", "toolId", "scopeKey"]),
        Index(value = ["isPinned", "lastAccessedAtMs"]),
    ],
)
internal data class CompanionResultEntity(
    val workId: String,
    val scopeKey: String,
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
    val turnIndex: Int,
    val role: String,
    val content: String,
    val createdAtMs: Long,
)

@Dao
internal interface CompanionDao {
    @Query(
        "SELECT * FROM companion_results " +
            "WHERE workId = :workId AND scopeKey = :scopeKey " +
            "AND toolId = :toolId AND promptVersion = :promptVersion LIMIT 1",
    )
    suspend fun getExactResult(
        workId: String,
        scopeKey: String,
        toolId: String,
        promptVersion: Int,
    ): CompanionResultEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertResult(entity: CompanionResultEntity)

    @Query(
        "UPDATE companion_results SET lastAccessedAtMs = :nowMs " +
            "WHERE workId = :workId AND scopeKey = :scopeKey " +
            "AND toolId = :toolId AND promptVersion = :promptVersion",
    )
    suspend fun touchResult(
        workId: String,
        scopeKey: String,
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
            "WHERE workId = :workId AND scopeKey = :scopeKey " +
            "AND toolId = :toolId AND promptVersion = :promptVersion",
    )
    suspend fun deleteResult(
        workId: String,
        scopeKey: String,
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
                deleteResult(v.workId, v.scopeKey, v.toolId, v.promptVersion)
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

    @Query("SELECT * FROM companion_results WHERE workId = :workId ORDER BY createdAtMs ASC")
    suspend fun getResultsForWork(workId: String): List<CompanionResultEntity>

    @Query("SELECT * FROM qa_threads WHERE workId = :workId ORDER BY threadId ASC, turnIndex ASC")
    suspend fun getAllQaTurnsForWork(workId: String): List<QaThreadEntity>

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
    version = 2,
    exportSchema = false,
)
internal abstract class CompanionDatabase : RoomDatabase() {
    abstract fun companionDao(): CompanionDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `companion_results_new` (
                        `workId` TEXT NOT NULL,
                        `scopeKey` TEXT NOT NULL,
                        `toolId` TEXT NOT NULL,
                        `promptVersion` INTEGER NOT NULL,
                        `chapterContentSha256` TEXT NOT NULL,
                        `artifactJson` TEXT NOT NULL,
                        `tokenCost` INTEGER NOT NULL,
                        `byteSize` INTEGER NOT NULL,
                        `isPinned` INTEGER NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        `lastAccessedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`workId`, `scopeKey`, `toolId`, `promptVersion`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO `companion_results_new` (
                        `workId`, `scopeKey`, `toolId`, `promptVersion`, `chapterContentSha256`,
                        `artifactJson`, `tokenCost`, `byteSize`, `isPinned`, `createdAtMs`, `lastAccessedAtMs`
                    )
                    SELECT `workId`, `scopeKey`, `toolId`, `promptVersion`, `chapterContentSha256`,
                           `artifactJson`, `tokenCost`, `byteSize`, `isPinned`, `createdAtMs`, `lastAccessedAtMs`
                    FROM `companion_results`
                    ORDER BY `lastAccessedAtMs` ASC
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `companion_results`")
                db.execSQL("ALTER TABLE `companion_results_new` RENAME TO `companion_results`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_companion_results_workId_toolId_scopeKey` ON `companion_results` (`workId`, `toolId`, `scopeKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_companion_results_isPinned_lastAccessedAtMs` ON `companion_results` (`isPinned`, `lastAccessedAtMs`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `qa_threads_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `threadId` TEXT NOT NULL,
                        `workId` TEXT NOT NULL,
                        `scopeKey` TEXT NOT NULL,
                        `turnIndex` INTEGER NOT NULL,
                        `role` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `createdAtMs` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `qa_threads_new` (`id`, `threadId`, `workId`, `scopeKey`, `turnIndex`, `role`, `content`, `createdAtMs`)
                    SELECT `id`, `threadId`, `workId`, `scopeKey`, `turnIndex`, `role`, `content`, `createdAtMs`
                    FROM `qa_threads`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `qa_threads`")
                db.execSQL("ALTER TABLE `qa_threads_new` RENAME TO `qa_threads`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_qa_threads_workId_threadId_turnIndex` ON `qa_threads` (`workId`, `threadId`, `turnIndex`)")
            }
        }

        @Volatile
        private var persistentInstance: CompanionDatabase? = null

        fun build(context: Context, allowInMemory: Boolean): CompanionDatabase {
            if (allowInMemory) {
                return Room.inMemoryDatabaseBuilder(context, CompanionDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            }
            return persistentInstance ?: synchronized(this) {
                persistentInstance ?: Room.databaseBuilder(
                    context.applicationContext ?: context,
                    CompanionDatabase::class.java,
                    "banread_companion_v1.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { persistentInstance = it }
            }
        }
    }
}
