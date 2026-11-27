package app.banread.app.ui.bookshelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import app.banread.model.LibraryVault
import app.banread.model.ReadProfileId
import app.banread.model.WorkSummary
import app.banread.model.registry.ProfileRegistry
import app.banread.model.registry.ReadProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BookshelfUiState(
    val allWorks: List<WorkSummary> = emptyList(),
    val filteredWorks: List<WorkSummary> = emptyList(),
    val profiles: List<ReadProfile> = emptyList(),
    val selectedProfileFilter: ReadProfileId? = null,
    val searchQuery: String = "",
    val isImporting: Boolean = false,
    val statusMessage: String? = null,
    val importErrorTitle: String? = null,
    val importErrorDetail: String? = null,
    val pendingDeleteWork: WorkSummary? = null,
)

class BookshelfViewModel(
    private val libraryVault: LibraryVault,
    private val profileRegistry: ProfileRegistry,
) : ViewModel() {

    private val deduplicatedProfiles: List<ReadProfile> =
        profileRegistry.profiles.distinctBy { it.readType }

    private val selectedFilter = MutableStateFlow<ReadProfileId?>(null)
    private val searchQuery = MutableStateFlow("")
    private val localOverlay = MutableStateFlow(LocalBookshelfOverlay())

    val uiState: StateFlow<BookshelfUiState> = combine(
        libraryVault.observeWorks(),
        selectedFilter,
        searchQuery,
        localOverlay,
    ) { works, filter, query, overlay ->
        val trimmed = query.trim().lowercase()
        val filtered = works.filter { work ->
            val matchesProfile = filter == null || work.readProfileId == filter
            val matchesQuery = trimmed.isEmpty() ||
                work.title.lowercase().contains(trimmed) ||
                work.author.lowercase().contains(trimmed)
            matchesProfile && matchesQuery
        }
        BookshelfUiState(
            allWorks = works,
            filteredWorks = filtered,
            profiles = deduplicatedProfiles,
            selectedProfileFilter = filter,
            searchQuery = query,
            isImporting = overlay.isImporting,
            statusMessage = overlay.statusMessage,
            importErrorTitle = overlay.importErrorTitle,
            importErrorDetail = overlay.importErrorDetail,
            pendingDeleteWork = overlay.pendingDeleteWork,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = BookshelfUiState(profiles = deduplicatedProfiles),
    )

    fun selectProfileFilter(profileId: ReadProfileId?) {
        selectedFilter.value = profileId
    }

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun profileDisplayName(profileId: ReadProfileId): String =
        profileRegistry.profileById(profileId).name

    fun importLocalUri(sourceUri: String, displayName: String? = null) {
        viewModelScope.launch {
            localOverlay.value = localOverlay.value.copy(
                isImporting = true,
                importErrorTitle = null,
                importErrorDetail = null,
                statusMessage = null,
            )

            val lowerName = (displayName ?: sourceUri).lowercase()
            val result = when {
                lowerName.endsWith(".bcbook") -> libraryVault.ingestStudyPack(sourceUri)
                lowerName.endsWith(".zip") -> {
                    val packResult = libraryVault.ingestStudyPack(sourceUri)
                    if (packResult.isFailure && packResult.exceptionOrNull()?.message?.contains("manifest.json", ignoreCase = true) == true) {
                        libraryVault.importLocalBook(sourceUri)
                    } else {
                        packResult
                    }
                }
                else -> libraryVault.importLocalBook(sourceUri)
            }

            result.fold(
                onSuccess = { imported ->
                    localOverlay.value = localOverlay.value.copy(
                        isImporting = false,
                        statusMessage = "已导入《${imported.title}》",
                    )
                },
                onFailure = { error ->
                    val failure = (error as? AppFailureException)?.failure
                    val (title, detail) = when (failure) {
                        is AppFailure.UnsupportedDrmOrFormat -> {
                            val isDrm = failure.reason.contains("DRM", ignoreCase = true) ||
                                failure.reason.contains("KFX", ignoreCase = true) ||
                                failure.reason.contains("rights", ignoreCase = true) ||
                                failure.reason.contains("encrypt", ignoreCase = true)
                            if (isDrm) {
                                "受 DRM 保护的文件无法导入" to
                                    "检测到 Kindle KFX / Adobe DRM 等受版权保护加密格式（${failure.reason}）。伴读仅支持无 DRM 的 EPUB、MOBI、AZW3、PDF 与 .bcbook 伴读包。"
                            } else {
                                "不支持或损坏的图书格式" to
                                    "无法解析所选文件（${failure.reason}）。请确认文件为无 DRM 的 EPUB、MOBI、AZW3、PDF 或 .bcbook。"
                            }
                        }
                        else -> "导入失败" to (error.message ?: "读取本地文件时发生错误")
                    }
                    localOverlay.value = localOverlay.value.copy(
                        isImporting = false,
                        importErrorTitle = title,
                        importErrorDetail = detail,
                    )
                },
            )
        }
    }

    fun requestDeleteWork(work: WorkSummary) {
        localOverlay.value = localOverlay.value.copy(pendingDeleteWork = work)
    }

    fun dismissDeleteDialog() {
        localOverlay.value = localOverlay.value.copy(pendingDeleteWork = null)
    }

    fun confirmDeleteWork() {
        val target = localOverlay.value.pendingDeleteWork ?: return
        viewModelScope.launch {
            localOverlay.value = localOverlay.value.copy(pendingDeleteWork = null)
            libraryVault.deleteWork(target.workId).fold(
                onSuccess = {
                    localOverlay.value = localOverlay.value.copy(
                        statusMessage = "已移除《${target.title}》",
                    )
                },
                onFailure = { err ->
                    localOverlay.value = localOverlay.value.copy(
                        statusMessage = "移除失败：${err.message ?: "未知错误"}",
                    )
                },
            )
        }
    }

    fun dismissImportError() {
        localOverlay.value = localOverlay.value.copy(
            importErrorTitle = null,
            importErrorDetail = null,
        )
    }

    fun clearStatusMessage() {
        localOverlay.value = localOverlay.value.copy(statusMessage = null)
    }

    private data class LocalBookshelfOverlay(
        val isImporting: Boolean = false,
        val statusMessage: String? = null,
        val importErrorTitle: String? = null,
        val importErrorDetail: String? = null,
        val pendingDeleteWork: WorkSummary? = null,
    )

    companion object {
        fun factory(
            libraryVault: LibraryVault,
            profileRegistry: ProfileRegistry,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                BookshelfViewModel(libraryVault, profileRegistry) as T
        }
    }
}
