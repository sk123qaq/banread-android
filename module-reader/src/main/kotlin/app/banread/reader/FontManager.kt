package app.banread.reader

import android.content.Context
import android.net.Uri
import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import app.banread.reader.internal.FontEntity
import app.banread.reader.internal.ReaderDatabase
import app.banread.reader.internal.SfntFontParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Manages user-imported TTF/OTF/TTC fonts under `filesRoot/fonts/`.
 * Validates SFNT headers and exposes asset URLs served via `WebViewAssetLoader`.
 */
class FontManager internal constructor(
    private val context: Context,
    private val filesRoot: File,
    internal val db: ReaderDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val dao = db.readerDao()

    suspend fun importFont(sourceUriOrFile: String): Result<RegisteredFont> = withContext(ioDispatcher) {
        val tempDir = File(filesRoot, "tmp/font_${UUID.randomUUID()}").apply { mkdirs() }
        val tempFile = File(tempDir, "candidate.font")
        try {
            val sourceName = copyFontToTemp(sourceUriOrFile, tempFile)
            val meta = SfntFontParser.parse(tempFile, fallbackName = sourceName)

            val fontId = UUID.randomUUID().toString()
            val fontsDir = File(filesRoot, "fonts").apply { mkdirs() }
            val destFile = File(fontsDir, "$fontId.${meta.extension}")
            tempFile.copyTo(destFile, overwrite = true)

            val entity = FontEntity(
                fontId = fontId,
                displayName = meta.familyName,
                filePath = destFile.absolutePath,
                addedAtMs = System.currentTimeMillis(),
            )
            dao.upsertFont(entity)
            Result.success(entity.toRegisteredFont())
        } catch (ex: AppFailureException) {
            Result.failure(ex)
        } catch (ex: Exception) {
            Result.failure(
                AppFailureException(
                    AppFailure.UnsupportedDrmOrFormat("Failed to import font: ${ex.message ?: ex::class.simpleName}"),
                ),
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    suspend fun listFonts(): List<RegisteredFont> = withContext(ioDispatcher) {
        dao.listFonts().map { it.toRegisteredFont() }
    }

    suspend fun deleteFont(fontId: String) = withContext(ioDispatcher) {
        val existing = dao.findFont(fontId)
        if (existing != null) {
            runCatching { File(existing.filePath).delete() }
            dao.deleteFont(fontId)
        }
    }

    private fun copyFontToTemp(sourceUriOrFile: String, dest: File): String {
        dest.parentFile?.mkdirs()
        return if (sourceUriOrFile.startsWith("content://")) {
            val uri = Uri.parse(sourceUriOrFile)
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("Cannot open font URI: $sourceUriOrFile"))
            stream.use { input -> dest.outputStream().buffered().use { output -> input.copyTo(output) } }
            uri.lastPathSegment ?: "custom.ttf"
        } else {
            val file = if (sourceUriOrFile.startsWith("file://")) {
                File(Uri.parse(sourceUriOrFile).path ?: "")
            } else {
                File(sourceUriOrFile)
            }
            if (!file.isFile) {
                throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("Font file not found: $sourceUriOrFile"))
            }
            file.inputStream().buffered().use { input ->
                dest.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            file.name
        }
    }

    private fun FontEntity.toRegisteredFont(): RegisteredFont {
        val fileName = File(filePath).name
        return RegisteredFont(
            fontId = fontId,
            displayName = displayName,
            cssFontFamily = displayName,
            assetUrl = "https://appassets.androidplatform.net/user-fonts/$fileName",
        )
    }
}
