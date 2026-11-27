package app.banread.app

import app.banread.app.ui.bookshelf.BookshelfViewModel
import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import app.banread.model.ImportedWork
import app.banread.model.ReadProfileId
import app.banread.model.ReadingSurfaceMode
import app.banread.model.WorkId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookshelfViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @Test
    fun `observeWorks reacts to filter and search query with deduplicated 6+1 profiles`() = runTest {
        val vault = FakeLibraryVault()
        val registry = loadTestProfileRegistry()
        val vm = BookshelfViewModel(vault, registry)

        // 7 deduplicated 6+1 profiles
        assertEquals(7, vm.uiState.value.profiles.size)
        assertEquals(7, vm.uiState.value.profiles.map { it.readType }.distinct().size)

        vault.worksFlow.value = listOf(
            sampleWorkSummary("w1", "雾夜档案", author = "陈默", profileId = "literature"),
            sampleWorkSummary("w2", "资治通鉴选", author = "司马光", profileId = "history"),
            sampleWorkSummary("w3", "纯粹理性批判", author = "康德", profileId = "philosophy"),
        )
        assertEquals(3, vm.uiState.value.filteredWorks.size)

        // Filter by profile
        vm.selectProfileFilter(ReadProfileId("history"))
        assertEquals(listOf("w2"), vm.uiState.value.filteredWorks.map { it.workId.raw })

        // Clear filter and search by author
        vm.selectProfileFilter(null)
        vm.updateSearchQuery("康德")
        assertEquals(listOf("w3"), vm.uiState.value.filteredWorks.map { it.workId.raw })
    }

    @Test
    fun `SAF import routes bcbook vs local book, surfaces DRM KFX error, and deletes work`() = runTest {
        val vault = FakeLibraryVault()
        val registry = loadTestProfileRegistry()
        val vm = BookshelfViewModel(vault, registry)

        var ingestedPackUri: String? = null
        vault.ingestPackHandler = { uri ->
            ingestedPackUri = uri
            Result.success(
                ImportedWork(
                    workId = WorkId("bc_01"),
                    title = "伴读测试书",
                    author = "作者",
                    readProfileId = ReadProfileId("science"),
                    literatureSubKind = null,
                    clothColor = "#2C4056",
                    supportedModes = setOf(ReadingSurfaceMode.REFLOWABLE),
                ),
            )
        }
        vm.importLocalUri("content://docs/123", displayName = "sample.bcbook")
        assertEquals("content://docs/123", ingestedPackUri)
        assertEquals("已导入《伴读测试书》", vm.uiState.value.statusMessage)

        // DRM KFX import failure shows friendly DRM explanation
        vault.importLocalHandler = {
            Result.failure(AppFailureException(AppFailure.UnsupportedDrmOrFormat("Kindle KFX format/DRM is not supported")))
        }
        vm.importLocalUri("content://docs/locked", displayName = "book.kfx")
        assertNotNull(vm.uiState.value.importErrorTitle)
        assertTrue(vm.uiState.value.importErrorTitle!!.contains("DRM"))
        assertTrue(vm.uiState.value.importErrorDetail!!.contains("Kindle KFX"))

        vm.dismissImportError()
        assertNull(vm.uiState.value.importErrorTitle)

        // Delete work flow
        val target = sampleWorkSummary("w_del", "待删除图书")
        vault.worksFlow.value = listOf(target)
        vm.requestDeleteWork(target)
        assertEquals(target, vm.uiState.value.pendingDeleteWork)
        vm.confirmDeleteWork()
        assertEquals(listOf(WorkId("w_del")), vault.deletedWorkIds)
        assertTrue(vm.uiState.value.allWorks.isEmpty())
    }
}
