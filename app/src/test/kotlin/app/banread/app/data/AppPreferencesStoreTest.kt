package app.banread.app.data

import android.content.Context
import app.banread.model.ReaderTheme
import app.banread.reader.PaginationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AppPreferencesStoreTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearPreferences() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `missing keys keep the existing reader defaults`() {
        val store = SharedPrefsAppPreferencesStore(context)

        val state = store.state.value
        assertEquals(ReaderTheme.SEPIA_WARM, state.theme)
        assertTrue(state.volumeKeyPageTurn)
        assertEquals(PaginationMode.SLIDE, state.paginationMode)
        assertEquals("Noto Serif CJK SC", state.typography.fontFamily)
        assertEquals(17f, state.typography.fontSizeSp, 0.01f)
        assertEquals(1.84f, state.typography.lineHeightMultiplier, 0.01f)
        assertEquals(12f, state.typography.paragraphSpacingDp, 0.01f)
        assertEquals(20f, state.typography.horizontalMarginDp, 0.01f)
        assertTrue(state.typography.squeezeChinesePunctuation)
    }

    @Test
    fun `old values outside reader ranges are clamped instead of breaking the reader`() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("theme", "NOT_A_THEME")
            .putString("pagination_mode", "NOT_A_MODE")
            .putFloat("typo_font_size", 99f)
            .putFloat("typo_line_height", 0.1f)
            .putFloat("typo_para_spacing", -4f)
            .putFloat("typo_margin_dp", 999f)
            .commit()

        val store = SharedPrefsAppPreferencesStore(context)
        val typography = store.state.value.typography
        assertEquals(ReaderTheme.SEPIA_WARM, store.state.value.theme)
        assertEquals(PaginationMode.SLIDE, store.state.value.paginationMode)
        assertEquals(36f, typography.fontSizeSp, 0.01f)
        assertEquals(1.2f, typography.lineHeightMultiplier, 0.01f)
        assertEquals(0f, typography.paragraphSpacingDp, 0.01f)
        assertEquals(40f, typography.horizontalMarginDp, 0.01f)
    }

    @Test
    fun `all reader settings continue to round trip through shared preferences`() {
        val first = SharedPrefsAppPreferencesStore(context)
        first.setTheme(ReaderTheme.SLATE_NIGHT)
        first.setVolumeKeyPageTurn(false)
        first.setPaginationMode(PaginationMode.COVER)
        first.updateTypography(
            first.state.value.typography.copy(
                fontFamily = "Imported Kai",
                fontSizeSp = 21f,
                lineHeightMultiplier = 2.1f,
                paragraphSpacingDp = 18f,
                horizontalMarginDp = 30f,
                squeezeChinesePunctuation = false,
            ),
        )

        val second = SharedPrefsAppPreferencesStore(context)
        val state = second.state.value
        assertEquals(ReaderTheme.SLATE_NIGHT, state.theme)
        assertFalse(state.volumeKeyPageTurn)
        assertEquals(PaginationMode.COVER, state.paginationMode)
        assertEquals("Imported Kai", state.typography.fontFamily)
        assertEquals(21f, state.typography.fontSizeSp, 0.01f)
        assertEquals(2.1f, state.typography.lineHeightMultiplier, 0.01f)
        assertEquals(18f, state.typography.paragraphSpacingDp, 0.01f)
        assertEquals(30f, state.typography.horizontalMarginDp, 0.01f)
        assertFalse(state.typography.squeezeChinesePunctuation)
    }

    private companion object {
        const val PREFS_NAME = "banread_app_prefs"
    }
}
