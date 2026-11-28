package app.banread.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.banread.model.BlockId
import app.banread.model.ChapterOrdinal
import app.banread.model.ChapterOutlineItem
import app.banread.model.LibraryVault
import app.banread.model.ReadProfileId
import app.banread.model.ReaderEngine
import app.banread.model.ReadingAnchor
import app.banread.model.ReadingSurfaceMode
import app.banread.model.TextQuote
import app.banread.model.WorkId
import app.banread.model.WorkOutline
import app.banread.model.WorkSummary
import app.banread.model.registry.ProfileRegistry
import app.banread.model.registry.ReadProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BookDetailUiState(
    val isLoading: Boolean = true,
    val summary: WorkSummary? = null,
    val outline: WorkOutline? = null,
    val activeProfile: ReadProfile? = null,
    val allProfiles: List<ReadProfile> = emptyList(),
    val literatureSubKindHints: Map<String, String> = emptyMap(),
    val currentOrdinal: ChapterOrdinal? = null,
    val frontBackExpanded: Boolean = false,
    val showPerspectivePicker: Boolean = false,
    val errorMessage: String? = null,
    val pendingNavigationMode: ReadingSurfaceMode? = null,
)

class BookDetailViewModel(
    private val workId: WorkId,
    private val libraryVault: LibraryVault,
    private val readerEngine: ReaderEngine,
    private val profileRegistry: ProfileRegistry,
) : ViewModel() {

    private val deduplicatedProfiles = profileRegistry.profiles.distinctBy { it.readType }

    private val _uiState = MutableStateFlow(
        BookDetailUiState(
            allProfiles = deduplicatedProfiles,
            literatureSubKindHints = profileRegistry.literatureSubKindHints,
        ),
    )
    val uiState: StateFlow<BookDetailUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            runCatching {
                val summary = libraryVault.getWorkSummary(workId)
                val outline = libraryVault.getWorkOutline(workId)
                val currentOrdinal = readerEngine.activeState.value
                    .takeIf { it.workId == workId }
                    ?.currentCursor
                    ?.ordinal
                    ?: outline.bodyChapters.firstOrNull()?.ordinal
                val profileId = outline.readProfileId ?: summary?.readProfileId ?: profileRegistry.defaultReadType
                val profile = profileRegistry.profileById(profileId)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    summary = summary,
                    outline = outline,
                    activeProfile = profile,
                    currentOrdinal = currentOrdinal,
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = err.message ?: "无法加载书籍目录",
                )
            }
        }
    }

    fun toggleFrontBackExpanded() {
        _uiState.value = _uiState.value.copy(frontBackExpanded = !_uiState.value.frontBackExpanded)
    }

    fun openPerspectivePicker() {
        _uiState.value = _uiState.value.copy(showPerspectivePicker = true)
    }

    fun closePerspectivePicker() {
        _uiState.value = _uiState.value.copy(showPerspectivePicker = false)
    }

    fun selectPerspective(profileId: ReadProfileId, literatureSubKind: String?) {
        viewModelScope.launch {
            val cleanSub = if (profileId.raw == "literature") literatureSubKind?.takeIf { it.isNotBlank() } else null
            libraryVault.overrideProfile(workId, profileId, cleanSub)
            val outline = libraryVault.getWorkOutline(workId)
            val summary = libraryVault.getWorkSummary(workId)
            _uiState.value = _uiState.value.copy(
                summary = summary,
                outline = outline,
                activeProfile = profileRegistry.profileById(profileId),
                showPerspectivePicker = false,
            )
        }
    }

    fun onContinueReadingClicked() {
        val summary = _uiState.value.summary
        val mode = if (summary != null && ReadingSurfaceMode.REFLOWABLE !in summary.supportedModes &&
            ReadingSurfaceMode.PDF_FIXED in summary.supportedModes
        ) {
            ReadingSurfaceMode.PDF_FIXED
        } else {
            ReadingSurfaceMode.REFLOWABLE
        }
        viewModelScope.launch {
            readerEngine.open(workId, mode)
            _uiState.value = _uiState.value.copy(pendingNavigationMode = mode)
        }
    }

    fun onChapterClicked(item: ChapterOutlineItem) {
        jumpToOrdinal(item.ordinal)
    }

    private fun jumpToOrdinal(ordinal: ChapterOrdinal) {
        val summary = _uiState.value.summary
        val mode = if (summary != null && ReadingSurfaceMode.REFLOWABLE !in summary.supportedModes &&
            ReadingSurfaceMode.PDF_FIXED in summary.supportedModes
        ) {
            ReadingSurfaceMode.PDF_FIXED
        } else {
            ReadingSurfaceMode.REFLOWABLE
        }
        viewModelScope.launch {
            readerEngine.open(workId, mode)
            val anchor = runCatching {
                libraryVault.resolveAnchor(workId, ordinal, BlockId("B01"))
            }.getOrElse {
                if (mode == ReadingSurfaceMode.PDF_FIXED) {
                    ReadingAnchor.PdfPage(ordinal = ordinal, pageIndex = (ordinal.value - 1).coerceAtLeast(0), normalizedBoxes = emptyList())
                } else {
                    ReadingAnchor.ReflowCfi(
                        ordinal = ordinal,
                        href = "OEBPS/ch_${ordinal.twoDigit()}.xhtml",
                        progression = 0f,
                        totalProgression = 0f,
                        cfi = "",
                        textQuote = TextQuote("", "", ""),
                        blockId = null,
                    )
                }
            }
            readerEngine.goTo(anchor)
            _uiState.value = _uiState.value.copy(
                currentOrdinal = ordinal,
                pendingNavigationMode = mode,
            )
        }
    }

    fun consumePendingNavigation() {
        _uiState.value = _uiState.value.copy(pendingNavigationMode = null)
    }

    companion object {
        fun factory(
            workId: WorkId,
            libraryVault: LibraryVault,
            readerEngine: ReaderEngine,
            profileRegistry: ProfileRegistry,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                BookDetailViewModel(workId, libraryVault, readerEngine, profileRegistry) as T
        }
    }
}
