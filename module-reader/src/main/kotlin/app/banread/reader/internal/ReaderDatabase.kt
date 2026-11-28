package app.banread.reader.internal

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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "reading_progress")
internal data class ReadingProgressEntity(
    @PrimaryKey
    val workId: String,
    val surfaceMode: String,
    val cursorJson: String,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "annotations",
    indices = [Index(value = ["workId", "updatedAtMs"])],
)
internal data class AnnotationEntity(
    @PrimaryKey
    val noteId: String,
    val workId: String,
    val deviceId: String,
    val anchorJson: String,
    val colorToken: String,
    val comment: String,
    val updatedAtMs: Long,
    val deletedAtMs: Long? = null,
)

@Entity(tableName = "fonts")
internal data class FontEntity(
    @PrimaryKey
    val fontId: String,
    val displayName: String,
    val filePath: String,
    val addedAtMs: Long,
)

@Dao
internal interface ReaderDao {
    @Query("SELECT * FROM reading_progress WHERE workId = :workId LIMIT 1")
    suspend fun findProgress(workId: String): ReadingProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(entity: ReadingProgressEntity)

    @Query(
        "SELECT * FROM annotations WHERE workId = :workId AND deletedAtMs IS NULL " +
            "ORDER BY updatedAtMs ASC",
    )
    suspend fun getActiveAnnotations(workId: String): List<AnnotationEntity>

    @Query("SELECT * FROM annotations WHERE workId = :workId ORDER BY updatedAtMs ASC")
    suspend fun getAllAnnotations(workId: String): List<AnnotationEntity>

    @Query("SELECT * FROM annotations WHERE workId = :workId AND noteId = :noteId LIMIT 1")
    suspend fun findAnnotation(workId: String, noteId: String): AnnotationEntity?

    @Query("UPDATE annotations SET workId = :toWorkId WHERE workId = :fromWorkId")
    suspend fun reassignAnnotationsWorkId(fromWorkId: String, toWorkId: String)

    @Query("DELETE FROM reading_progress WHERE workId = :workId")
    suspend fun deleteProgress(workId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAnnotation(entity: AnnotationEntity)

    @Query("SELECT * FROM fonts ORDER BY addedAtMs ASC")
    suspend fun listFonts(): List<FontEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFont(entity: FontEntity)

    @Query("SELECT * FROM fonts WHERE fontId = :fontId LIMIT 1")
    suspend fun findFont(fontId: String): FontEntity?

    @Query("DELETE FROM fonts WHERE fontId = :fontId")
    suspend fun deleteFont(fontId: String)
}

@Database(
    entities = [
        ReadingProgressEntity::class,
        AnnotationEntity::class,
        FontEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
internal abstract class ReaderDatabase : RoomDatabase() {
    abstract fun readerDao(): ReaderDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `reading_progress_new` (
                        `workId` TEXT NOT NULL,
                        `surfaceMode` TEXT NOT NULL,
                        `cursorJson` TEXT NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`workId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO `reading_progress_new` (`workId`, `surfaceMode`, `cursorJson`, `updatedAtMs`)
                    SELECT `workId`, `surfaceMode`, `cursorJson`, `updatedAtMs` FROM `reading_progress`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `reading_progress`")
                db.execSQL("ALTER TABLE `reading_progress_new` RENAME TO `reading_progress`")
            }
        }

        @Volatile
        private var persistentInstance: ReaderDatabase? = null

        fun getOrBuild(context: Context, allowInMemory: Boolean): ReaderDatabase {
            val appContext = context.applicationContext ?: context
            if (allowInMemory) {
                val directExecutor = java.util.concurrent.Executor { runnable -> runnable.run() }
                return Room.inMemoryDatabaseBuilder(appContext, ReaderDatabase::class.java)
                    .allowMainThreadQueries()
                    .setQueryExecutor(directExecutor)
                    .setTransactionExecutor(directExecutor)
                    .build()
            }
            return persistentInstance ?: synchronized(this) {
                persistentInstance ?: Room.databaseBuilder(
                    appContext,
                    ReaderDatabase::class.java,
                    "banread_reader_v1.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { persistentInstance = it }
            }
        }
    }
}
