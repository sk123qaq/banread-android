package app.banread.reader

import android.content.Context
import app.banread.model.BookRendererPort
import app.banread.model.DeviceId
import app.banread.model.LibraryVault
import app.banread.model.ReaderEngine
import app.banread.reader.internal.DefaultReaderEngine
import app.banread.reader.internal.ReaderDatabase
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * Public entry point for `:module-reader`.
 * All Room entities, DAOs, CSS generators, SFNT parsers, and JS bridge DTOs remain `internal`.
 */
object ReaderEngineFactory {

    fun createFontManager(
        context: Context,
        filesRoot: File,
        allowInMemory: Boolean = false,
    ): FontManager {
        val appContext = context.applicationContext ?: context
        val db = ReaderDatabase.getOrBuild(appContext, allowInMemory = allowInMemory)
        val ioDispatcher = if (allowInMemory) Dispatchers.Unconfined else Dispatchers.IO
        return FontManager(
            context = appContext,
            filesRoot = filesRoot,
            db = db,
            ioDispatcher = ioDispatcher,
        )
    }

    fun create(
        context: Context,
        filesRoot: File,
        libraryVault: LibraryVault,
        rendererPort: BookRendererPort,
        deviceId: DeviceId = DeviceId("local-android"),
        allowInMemory: Boolean = false,
        fontManager: FontManager? = null,
    ): ReaderEngine {
        val appContext = context.applicationContext ?: context
        val db = fontManager?.db ?: ReaderDatabase.getOrBuild(appContext, allowInMemory = allowInMemory)
        val ioDispatcher = if (allowInMemory) Dispatchers.Unconfined else Dispatchers.IO
        val collectorDispatcher = if (allowInMemory) Dispatchers.Unconfined else Dispatchers.Main.immediate
        return DefaultReaderEngine(
            filesRoot = filesRoot,
            libraryVault = libraryVault,
            rendererPort = rendererPort,
            db = db,
            deviceId = deviceId,
            ioDispatcher = ioDispatcher,
            collectorDispatcher = collectorDispatcher,
        )
    }
}
