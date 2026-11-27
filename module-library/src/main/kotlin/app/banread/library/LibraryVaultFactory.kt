package app.banread.library

import android.content.Context
import app.banread.library.internal.AndroidPdfCoverRenderer
import app.banread.library.internal.PdfCoverRenderer
import app.banread.library.internal.RoomLibraryVault
import app.banread.model.LibraryVault
import app.banread.model.registry.ProfileRegistry
import java.io.File

/**
 * Public entry point for `:module-library`.
 * All Room entities, DAOs, parsers, and XHTML compilers remain `internal`.
 */
object LibraryVaultFactory {
    fun create(
        context: Context,
        filesRoot: File,
        profileRegistry: ProfileRegistry,
        allowInMemory: Boolean = false,
    ): LibraryVault {
        val appContext = context.applicationContext ?: context
        return RoomLibraryVault.create(
            context = appContext,
            filesRoot = filesRoot,
            profileRegistry = profileRegistry,
            allowInMemory = allowInMemory,
        )
    }

    internal fun createForTest(
        context: Context,
        filesRoot: File,
        profileRegistry: ProfileRegistry,
        allowInMemory: Boolean = true,
        pdfCoverRenderer: PdfCoverRenderer = AndroidPdfCoverRenderer(),
    ): LibraryVault {
        val appContext = context.applicationContext ?: context
        return RoomLibraryVault.create(
            context = appContext,
            filesRoot = filesRoot,
            profileRegistry = profileRegistry,
            allowInMemory = allowInMemory,
            pdfCoverRenderer = pdfCoverRenderer,
        )
    }
}
