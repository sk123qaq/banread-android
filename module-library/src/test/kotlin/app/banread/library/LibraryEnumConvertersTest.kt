package app.banread.library

import app.banread.model.ChapterRole
import app.banread.library.internal.LibraryConverters
import app.banread.library.internal.WorkFormat
import app.banread.library.internal.WorkProfileSource
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryEnumConvertersTest {

    @Test
    fun `converters round-trip every persisted enum value to its legacy TEXT name`() {
        WorkProfileSource.entries.forEach {
            assertEquals(it, LibraryConverters.storedToProfileSource(LibraryConverters.profileSourceToStored(it)))
        }
        WorkFormat.entries.forEach {
            assertEquals(it, LibraryConverters.storedToWorkFormat(LibraryConverters.workFormatToStored(it)))
        }
        ChapterRole.entries.forEach {
            assertEquals(it, LibraryConverters.storedToChapterRole(LibraryConverters.chapterRoleToStored(it)))
        }

        assertEquals("USER_OVERRIDE", LibraryConverters.profileSourceToStored(WorkProfileSource.USER_OVERRIDE))
        assertEquals("AUTO", LibraryConverters.profileSourceToStored(WorkProfileSource.AUTO))
        assertEquals("BCBOOK", LibraryConverters.workFormatToStored(WorkFormat.BCBOOK))
        assertEquals("EPUB", LibraryConverters.workFormatToStored(WorkFormat.EPUB))
        assertEquals("BODY", LibraryConverters.chapterRoleToStored(ChapterRole.BODY))
        assertEquals("FRONT_BACK", LibraryConverters.chapterRoleToStored(ChapterRole.FRONT_BACK))
    }
}
