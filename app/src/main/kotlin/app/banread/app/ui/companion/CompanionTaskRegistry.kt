package app.banread.app.ui.companion

import app.banread.model.AppFailure
import app.banread.model.AuditedArtifact
import app.banread.model.ReadingAnchor
import app.banread.model.ToolId
import app.banread.model.ToolScope
import app.banread.model.WorkId

internal data class CompanionTaskKey(
    val workId: WorkId,
    val scopeKey: String,
    val toolId: ToolId,
) {
    companion object {
        fun of(workId: WorkId, scope: ToolScope, toolId: ToolId): CompanionTaskKey =
            CompanionTaskKey(workId, scopeIdentity(scope), toolId)

        fun scopeIdentity(scope: ToolScope): String = when (scope) {
            is ToolScope.Chapter -> "chapter:${scope.ordinal.twoDigit()}"
            is ToolScope.Selection -> buildString {
                append("selection:")
                append(scope.anchor.ordinal.twoDigit())
                append(':')
                append(anchorIdentity(scope.anchor))
                append(':')
                append(scope.selectedText.take(SELECTION_TEXT_KEY_LIMIT))
            }
            ToolScope.FullBook -> "full_book"
        }

        private fun anchorIdentity(anchor: ReadingAnchor): String = when (anchor) {
            is ReadingAnchor.BlockOffset -> anchor.blockId.value
            is ReadingAnchor.ReflowCfi -> anchor.cfi.ifBlank { anchor.href }
            is ReadingAnchor.PdfPage -> "p${anchor.pageIndex}"
        }

        private const val SELECTION_TEXT_KEY_LIMIT = 64
    }
}

internal sealed interface CompanionToolSlot {
    data class Running(
        val thinking: String = "",
        val markdown: String = "",
    ) : CompanionToolSlot

    data class Ready(
        val artifact: AuditedArtifact,
        val isStale: Boolean = false,
    ) : CompanionToolSlot

    data class Failed(val failure: AppFailure) : CompanionToolSlot
}

internal class CompanionTaskRegistry(private val maxSlots: Int = DEFAULT_MAX_SLOTS) {

    sealed interface Start {
        data object Launch : Start
        data object AlreadyRunning : Start
        data class Reuse(val slot: CompanionToolSlot) : Start
    }

    private val slots = LinkedHashMap<CompanionTaskKey, CompanionToolSlot>()
    private val running = HashSet<CompanionTaskKey>()

    @Synchronized
    fun begin(key: CompanionTaskKey, forceRefresh: Boolean): Start {
        if (!forceRefresh) {
            when (val existing = slots[key]) {
                is CompanionToolSlot.Running -> return Start.AlreadyRunning
                is CompanionToolSlot.Ready -> return Start.Reuse(existing)
                is CompanionToolSlot.Failed -> return Start.Reuse(existing)
                null -> Unit
            }
        }
        if (!running.add(key)) return Start.AlreadyRunning
        slots[key] = CompanionToolSlot.Running()
        trim()
        return Start.Launch
    }

    @Synchronized
    fun slot(key: CompanionTaskKey): CompanionToolSlot? = slots[key]

    @Synchronized
    fun isRunning(key: CompanionTaskKey): Boolean = key in running

    @Synchronized
    fun appendThinking(key: CompanionTaskKey, delta: String) {
        val runningSlot = slots[key] as? CompanionToolSlot.Running ?: return
        slots[key] = runningSlot.copy(thinking = runningSlot.thinking + delta)
    }

    @Synchronized
    fun appendMarkdown(key: CompanionTaskKey, delta: String) {
        val runningSlot = slots[key] as? CompanionToolSlot.Running ?: return
        slots[key] = runningSlot.copy(markdown = runningSlot.markdown + delta)
    }

    @Synchronized
    fun ready(key: CompanionTaskKey, artifact: AuditedArtifact, isStale: Boolean = false) {
        slots[key] = CompanionToolSlot.Ready(artifact, isStale)
    }

    @Synchronized
    fun failed(key: CompanionTaskKey, failure: AppFailure) {
        slots[key] = CompanionToolSlot.Failed(failure)
    }

    @Synchronized
    fun finishOrMarkFailed(key: CompanionTaskKey, fallbackFailure: AppFailure): Boolean {
        running.remove(key)
        if (slots[key] is CompanionToolSlot.Running) {
            slots[key] = CompanionToolSlot.Failed(fallbackFailure)
            return true
        }
        return false
    }

    @Synchronized
    fun pendingCount(): Int = running.size

    private fun trim() {
        if (slots.size <= maxSlots) return
        val iterator = slots.keys.iterator()
        while (slots.size > maxSlots && iterator.hasNext()) {
            val candidate = iterator.next()
            if (candidate !in running) iterator.remove()
        }
    }

    companion object {
        const val DEFAULT_MAX_SLOTS = 48
    }
}
