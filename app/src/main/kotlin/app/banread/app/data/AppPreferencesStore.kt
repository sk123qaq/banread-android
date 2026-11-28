package app.banread.app.data

import android.content.Context
import android.content.SharedPreferences
import app.banread.model.DeviceId
import app.banread.model.ReaderTheme
import app.banread.model.TypographyProfile
import app.banread.reader.PaginationMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class AppPreferencesState(
    val theme: ReaderTheme = ReaderTheme.SEPIA_WARM,
    val volumeKeyPageTurn: Boolean = true,
    val paginationMode: PaginationMode = PaginationMode.SLIDE,
    val defaultSpoilerLock: Boolean = true,
    val typography: TypographyProfile = TypographyProfile(
        fontFamily = "Noto Serif CJK SC",
        fontSizeSp = 17f,
        lineHeightMultiplier = 1.84f,
        paragraphSpacingDp = 12f,
        horizontalMarginDp = 20f,
        squeezeChinesePunctuation = true,
    ),
    val deviceId: DeviceId = DeviceId("local-android"),
)

interface AppPreferencesPort {
    val state: StateFlow<AppPreferencesState>
    fun setTheme(theme: ReaderTheme)
    fun setVolumeKeyPageTurn(enabled: Boolean)
    fun setPaginationMode(mode: PaginationMode)
    fun setDefaultSpoilerLock(enabled: Boolean)
    fun updateTypography(profile: TypographyProfile)
}

class InMemoryAppPreferencesStore(
    initial: AppPreferencesState = AppPreferencesState(),
) : AppPreferencesPort {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<AppPreferencesState> = _state.asStateFlow()

    override fun setTheme(theme: ReaderTheme) {
        _state.value = _state.value.copy(theme = theme)
    }

    override fun setVolumeKeyPageTurn(enabled: Boolean) {
        _state.value = _state.value.copy(volumeKeyPageTurn = enabled)
    }

    override fun setPaginationMode(mode: PaginationMode) {
        _state.value = _state.value.copy(paginationMode = mode)
    }

    override fun setDefaultSpoilerLock(enabled: Boolean) {
        _state.value = _state.value.copy(defaultSpoilerLock = enabled)
    }

    override fun updateTypography(profile: TypographyProfile) {
        _state.value = _state.value.copy(typography = profile)
    }
}

class SharedPrefsAppPreferencesStore(
    context: Context,
) : AppPreferencesPort {

    private val prefs: SharedPreferences =
        (context.applicationContext ?: context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(loadInitial())
    override val state: StateFlow<AppPreferencesState> = _state.asStateFlow()

    override fun setTheme(theme: ReaderTheme) {
        prefs.edit().putString(KEY_THEME, theme.name).apply()
        _state.value = _state.value.copy(theme = theme)
    }

    override fun setVolumeKeyPageTurn(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VOLUME_TURN, enabled).apply()
        _state.value = _state.value.copy(volumeKeyPageTurn = enabled)
    }

    override fun setPaginationMode(mode: PaginationMode) {
        prefs.edit().putString(KEY_PAGINATION, mode.name).apply()
        _state.value = _state.value.copy(paginationMode = mode)
    }

    override fun setDefaultSpoilerLock(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPOILER_LOCK, enabled).apply()
        _state.value = _state.value.copy(defaultSpoilerLock = enabled)
    }

    override fun updateTypography(profile: TypographyProfile) {
        prefs.edit()
            .putString(KEY_FONT_FAMILY, profile.fontFamily)
            .putFloat(KEY_FONT_SIZE, profile.fontSizeSp)
            .putFloat(KEY_LINE_HEIGHT, profile.lineHeightMultiplier)
            .putFloat(KEY_PARA_SPACING, profile.paragraphSpacingDp)
            .putFloat(KEY_MARGIN_DP, profile.horizontalMarginDp)
            .putBoolean(KEY_SQUEEZE_PUNCT, profile.squeezeChinesePunctuation)
            .apply()
        _state.value = _state.value.copy(typography = profile)
    }

    private fun loadInitial(): AppPreferencesState {
        val defaults = AppPreferencesState()
        val theme = prefs.getString(KEY_THEME, null)
            ?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() }
            ?: defaults.theme
        val pagination = prefs.getString(KEY_PAGINATION, null)
            ?.let { runCatching { PaginationMode.valueOf(it) }.getOrNull() }
            ?: defaults.paginationMode
        val typography = TypographyProfile(
            fontFamily = prefs.getString(KEY_FONT_FAMILY, defaults.typography.fontFamily)
                ?: defaults.typography.fontFamily,
            fontSizeSp = prefs.getFloat(KEY_FONT_SIZE, defaults.typography.fontSizeSp).coerceIn(12f, 36f),
            lineHeightMultiplier = prefs.getFloat(KEY_LINE_HEIGHT, defaults.typography.lineHeightMultiplier)
                .coerceIn(1.2f, 2.4f),
            paragraphSpacingDp = prefs.getFloat(KEY_PARA_SPACING, defaults.typography.paragraphSpacingDp)
                .coerceAtLeast(0f),
            horizontalMarginDp = prefs.getFloat(KEY_MARGIN_DP, defaults.typography.horizontalMarginDp)
                .coerceAtLeast(0f),
            squeezeChinesePunctuation = prefs.getBoolean(
                KEY_SQUEEZE_PUNCT,
                defaults.typography.squeezeChinesePunctuation,
            ),
        )
        val storedDeviceId = prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }
            ?: "android-${UUID.randomUUID().toString().replace("-", "").take(10)}".also { generated ->
                prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
            }
        return AppPreferencesState(
            theme = theme,
            volumeKeyPageTurn = prefs.getBoolean(KEY_VOLUME_TURN, defaults.volumeKeyPageTurn),
            paginationMode = pagination,
            defaultSpoilerLock = prefs.getBoolean(KEY_SPOILER_LOCK, defaults.defaultSpoilerLock),
            typography = typography,
            deviceId = DeviceId(storedDeviceId),
        )
    }

    private companion object {
        const val PREFS_NAME = "banread_app_prefs"
        const val KEY_THEME = "theme"
        const val KEY_VOLUME_TURN = "volume_key_turn"
        const val KEY_PAGINATION = "pagination_mode"
        const val KEY_SPOILER_LOCK = "default_spoiler_lock"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_FONT_FAMILY = "typo_font_family"
        const val KEY_FONT_SIZE = "typo_font_size"
        const val KEY_LINE_HEIGHT = "typo_line_height"
        const val KEY_PARA_SPACING = "typo_para_spacing"
        const val KEY_MARGIN_DP = "typo_margin_dp"
        const val KEY_SQUEEZE_PUNCT = "typo_squeeze_punct"
    }
}
