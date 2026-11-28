package app.banread.app.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderSheetTest {

    @Test
    fun `only the typography sheet claims forty percent of the reader height`() {
        assertEquals(0.4f, ReaderSheet.TYPOGRAPHY.panelHeightFraction())
        assertEquals(0.44f, ReaderSheet.THEME.panelHeightFraction())
        assertEquals(0.52f, ReaderSheet.MORE.panelHeightFraction())
        assertNull(ReaderSheet.NONE.panelHeightFraction())
        assertNull(ReaderSheet.TOC.panelHeightFraction())
    }
}
