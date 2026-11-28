package app.banread.library.internal

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import app.banread.model.ChapterRole
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

internal enum class WorkProfileSource(val storedValue: String) {
    USER_OVERRIDE("USER_OVERRIDE"),
    DESKTOP("DESKTOP"),
    AUTO("AUTO"),
    ;

    companion object {
        fun fromStored(value: String): WorkProfileSource =
            entries.firstOrNull { it.storedValue == value }
                ?: error("Unknown work profile source: $value")
    }
}

internal enum class WorkFormat(val storedValue: String) {
    EPUB("EPUB"),
    MOBI("MOBI"),
    AZW3("AZW3"),
    PDF("PDF"),
    BCBOOK("BCBOOK"),
    ;

    companion object {
        fun fromStored(value: String): WorkFormat =
            entries.firstOrNull { it.storedValue == value }
                ?: error("Unknown work format: $value")
    }
}

internal object LibraryConverters {
    @TypeConverter
    fun profileSourceToStored(value: WorkProfileSource): String = value.storedValue

    @TypeConverter
    fun storedToProfileSource(value: String): WorkProfileSource = WorkProfileSource.fromStored(value)

    @TypeConverter
    fun workFormatToStored(value: WorkFormat): String = value.storedValue

    @TypeConverter
    fun storedToWorkFormat(value: String): WorkFormat = WorkFormat.fromStored(value)

    @TypeConverter
    fun chapterRoleToStored(value: ChapterRole): String = value.name

    @TypeConverter
    fun storedToChapterRole(value: String): ChapterRole = ChapterRole.valueOf(value)
}

@Entity(
    tableName = "works",
    indices = [Index(value = ["bookSourceSha256"])],
)
internal data class WorkEntity(
    @androidx.room.PrimaryKey
    val workId: String,
    val bookSourceSha256: String,
    val title: String,
    val author: String,
    val subjectsJson: String,
    val readProfileId: String,
    val literatureSubKind: String?,
    val profileSource: WorkProfileSource,
    val clothColor: String,
    val coverRel: String?,
    val coverWidth: Int? = null,
    val coverHeight: Int? = null,
    val originalFilePath: String,
    val compiledRootPath: String,
    val format: WorkFormat,
    val supportedModesJson: String,
    val isScannedPdf: Boolean,
    val blockVersion: Int,
    val canonicalVersion: Int,
    val addedAtMs: Long,
    val parserVersion: Int = 1,
)

@Entity(
    tableName = "chapters",
    primaryKeys = ["workId", "ordinal"],
)
internal data class ChapterEntity(
    val workId: String,
    val ordinal: Int,
    val n: String,
    val title: String,
    val chapterRole: ChapterRole,
    val merged: Boolean,
    val sourceFilesJson: String,
    val chapterContentSha256: String,
    val chars: Int,
    val blockCount: Int,
    val compiledHref: String,
    val virtualSplitParentOrdinal: Int?,
)

@Entity(
    tableName = "chapter_blocks",
    primaryKeys = ["workId", "ordinal", "blockId"],
    indices = [Index(value = ["workId", "ordinal", "blockIndex"])],
)
internal data class ChapterBlockEntity(
    val workId: String,
    val ordinal: Int,
    val blockId: String,
    val blockIndex: Int,
    val kind: String,
    val headingPath: String,
    val cleanText: String,
    val charStart: Int,
    val charEnd: Int,
    val locatorJson: String,
)

@Entity(
    tableName = "footnotes",
    primaryKeys = ["workId", "footnoteId"],
    indices = [Index(value = ["workId", "sourceChapterOrdinal"])],
)
internal data class FootnoteEntity(
    val workId: String,
    val footnoteId: String,
    val sourceChapterOrdinal: Int,
    val sourceBlockId: String,
    val marker: String,
    val targetChapterOrdinal: Int,
    val targetBlockId: String,
    val noteText: String,
    val isTranslatorOrBib: Boolean,
)

@Dao
internal interface LibraryDao {
    @Query("SELECT * FROM works ORDER BY addedAtMs DESC")
    suspend fun listAllWorks(): List<WorkEntity>

    @Query("SELECT * FROM works ORDER BY addedAtMs DESC")
    fun observeAllWorks(): Flow<List<WorkEntity>>

    @Query("SELECT * FROM works WHERE workId = :workId LIMIT 1")
    suspend fun findWorkById(workId: String): WorkEntity?

    @Query("SELECT * FROM works WHERE bookSourceSha256 = :sha256 LIMIT 1")
    suspend fun findWorkBySourceHash(sha256: String): WorkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWork(work: WorkEntity)

    @Query(
        "UPDATE works SET readProfileId = :readProfileId, literatureSubKind = :literatureSubKind, " +
            "profileSource = :profileSource WHERE workId = :workId",
    )
    suspend fun updateProfileOverride(
        workId: String,
        readProfileId: String,
        literatureSubKind: String?,
        profileSource: WorkProfileSource,
    )

    @Query("SELECT * FROM chapters WHERE workId = :workId ORDER BY ordinal ASC")
    suspend fun getChapters(workId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE workId = :workId AND ordinal = :ordinal LIMIT 1")
    suspend fun getChapter(workId: String, ordinal: Int): ChapterEntity?

    @Query("SELECT * FROM chapter_blocks WHERE workId = :workId AND ordinal = :ordinal AND blockId = :blockId LIMIT 1")
    suspend fun getBlock(workId: String, ordinal: Int, blockId: String): ChapterBlockEntity?

    @Query("SELECT * FROM chapter_blocks WHERE workId = :workId AND ordinal = :ordinal ORDER BY blockIndex ASC")
    suspend fun getBlocksForChapter(workId: String, ordinal: Int): List<ChapterBlockEntity>

    @Query("SELECT * FROM chapter_blocks WHERE workId = :workId ORDER BY ordinal ASC, blockIndex ASC")
    suspend fun getAllBlocksForWork(workId: String): List<ChapterBlockEntity>

    @Query("SELECT * FROM footnotes WHERE workId = :workId ORDER BY sourceChapterOrdinal ASC, footnoteId ASC")
    suspend fun getFootnotesForWork(workId: String): List<FootnoteEntity>

    @Query(
        "SELECT * FROM footnotes WHERE workId = :workId AND isTranslatorOrBib = 0 " +
            "ORDER BY sourceChapterOrdinal ASC, footnoteId ASC",
    )
    suspend fun getFootnotesNonBoilerplate(workId: String): List<FootnoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlocks(blocks: List<ChapterBlockEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFootnotes(footnotes: List<FootnoteEntity>)

    @Query("DELETE FROM chapters WHERE workId = :workId")
    suspend fun deleteChaptersForWork(workId: String)

    @Query("DELETE FROM chapter_blocks WHERE workId = :workId")
    suspend fun deleteBlocksForWork(workId: String)

    @Query("DELETE FROM footnotes WHERE workId = :workId")
    suspend fun deleteFootnotesForWork(workId: String)

    @Query("DELETE FROM works WHERE workId = :workId")
    suspend fun deleteWorkById(workId: String)

    @Transaction
    suspend fun deleteWorkTree(workId: String) {
        deleteFootnotesForWork(workId)
        deleteBlocksForWork(workId)
        deleteChaptersForWork(workId)
        deleteWorkById(workId)
    }

    @Transaction
    suspend fun replaceWorkTree(
        work: WorkEntity,
        chapters: List<ChapterEntity>,
        blocks: List<ChapterBlockEntity>,
        footnotes: List<FootnoteEntity>,
        supersededWorkId: String? = null,
    ) {
        if (supersededWorkId != null && supersededWorkId != work.workId) {
            deleteWorkTree(supersededWorkId)
        }
        upsertWork(work)
        deleteFootnotesForWork(work.workId)
        deleteBlocksForWork(work.workId)
        deleteChaptersForWork(work.workId)
        if (chapters.isNotEmpty()) insertChapters(chapters)
        if (blocks.isNotEmpty()) insertBlocks(blocks)
        if (footnotes.isNotEmpty()) insertFootnotes(footnotes)
    }
}

@Database(
    entities = [
        WorkEntity::class,
        ChapterEntity::class,
        ChapterBlockEntity::class,
        FootnoteEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
@TypeConverters(LibraryConverters::class)
internal abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE works ADD COLUMN coverWidth INTEGER")
                db.execSQL("ALTER TABLE works ADD COLUMN coverHeight INTEGER")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE works ADD COLUMN parserVersion INTEGER NOT NULL DEFAULT 1")
            }
        }

        fun build(context: Context, allowInMemory: Boolean): LibraryDatabase {
            val builder = if (allowInMemory) {
                Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                    .allowMainThreadQueries()
            } else {
                Room.databaseBuilder(context, LibraryDatabase::class.java, "banread_library_v1.db")
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
            }
            return builder.build()
        }
    }
}
