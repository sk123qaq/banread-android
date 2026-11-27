package app.banread.reader

import android.view.KeyEvent

/**
 * Maps hardware volume keys to previous/next page turns only while the reader surface
 * is active and volume-key paging is enabled by user settings.
 */
class VolumeKeyPager(
    var isReaderActive: Boolean = false,
    var isEnabled: Boolean = false,
    private val onPrevPage: () -> Unit,
    private val onNextPage: () -> Unit,
) {
    fun onKeyDown(keyCode: Int): Boolean {
        if (!isReaderActive || !isEnabled) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                onPrevPage()
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                onNextPage()
                true
            }
            else -> false
        }
    }
}
