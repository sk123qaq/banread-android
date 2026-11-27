package app.banread.reader.internal

import app.banread.model.BlockId
import app.banread.model.ChapterOrdinal
import app.banread.model.NoteLogEntry
import app.banread.model.ReadingAnchor
import app.banread.model.TextQuote
import app.banread.reader.FootnoteClickEvent
import app.banread.reader.SelectionRect
import app.banread.reader.TextSelectionEvent
import kotlinx.serialization.Serializable

@Serializable
internal data class JsTextQuote(
    val prefix: String = "",
    val exact: String = "",
    val suffix: String = "",
)

@Serializable
internal data class JsRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
)

@Serializable
internal data class JsRelocatePayload(
    val ordinal: Int = 1,
    val href: String = "",
    val progression: Float = 0f,
    val totalProgression: Float = 0f,
    val cfi: String = "",
    val blockId: String? = null,
    val textQuote: JsTextQuote = JsTextQuote(),
) {
    fun toDomain(): ReadingAnchor.ReflowCfi {
        val safeOrd = ChapterOrdinal(maxOf(1, ordinal))
        val parsedBlock = blockId?.takeIf { Regex("^B\\d+$").matches(it) }?.let { BlockId(it) }
        return ReadingAnchor.ReflowCfi(
            ordinal = safeOrd,
            href = href.ifBlank { "OEBPS/ch_${safeOrd.twoDigit()}.xhtml" },
            progression = progression.coerceIn(0f, 1f),
            totalProgression = totalProgression.coerceIn(0f, 1f),
            cfi = cfi,
            textQuote = TextQuote(
                prefix = textQuote.prefix,
                exact = textQuote.exact,
                suffix = textQuote.suffix,
            ),
            blockId = parsedBlock,
        )
    }
}

@Serializable
internal data class JsFootnoteClickPayload(
    val ordinal: Int = 1,
    val href: String = "",
    val marker: String = "",
) {
    fun toDomain(): FootnoteClickEvent {
        val safeOrd = ChapterOrdinal(maxOf(1, ordinal))
        val ref = href.ifBlank { marker }
        return FootnoteClickEvent(ordinal = safeOrd, markerOrHref = ref)
    }
}

@Serializable
internal data class JsSelectionPayload(
    val ordinal: Int = 1,
    val href: String = "",
    val cfi: String = "",
    val blockId: String? = null,
    val selectedText: String = "",
    val textQuote: JsTextQuote = JsTextQuote(),
    val rect: JsRect = JsRect(),
) {
    fun toDomain(): TextSelectionEvent {
        val safeOrd = ChapterOrdinal(maxOf(1, ordinal))
        val parsedBlock = blockId?.takeIf { Regex("^B\\d+$").matches(it) }?.let { BlockId(it) }
        return TextSelectionEvent(
            anchor = ReadingAnchor.ReflowCfi(
                ordinal = safeOrd,
                href = href.ifBlank { "OEBPS/ch_${safeOrd.twoDigit()}.xhtml" },
                progression = 0f,
                totalProgression = 0f,
                cfi = cfi,
                textQuote = TextQuote(
                    prefix = textQuote.prefix,
                    exact = textQuote.exact,
                    suffix = textQuote.suffix,
                ),
                blockId = parsedBlock,
            ),
            selectedText = selectedText,
            boundingRect = SelectionRect(rect.left, rect.top, rect.right, rect.bottom),
        )
    }
}

@Serializable
internal data class JsAnchorPayload(
    val ordinal: Int,
    val href: String? = null,
    val cfi: String? = null,
    val blockId: String? = null,
    val progression: Float? = null,
    val textQuote: JsTextQuote? = null,
) {
    companion object {
        fun fromDomain(anchor: ReadingAnchor): JsAnchorPayload = when (anchor) {
            is ReadingAnchor.ReflowCfi -> JsAnchorPayload(
                ordinal = anchor.ordinal.value,
                href = anchor.href,
                cfi = anchor.cfi.ifBlank { null },
                blockId = anchor.blockId?.value,
                progression = anchor.progression,
                textQuote = JsTextQuote(
                    prefix = anchor.textQuote.prefix,
                    exact = anchor.textQuote.exact,
                    suffix = anchor.textQuote.suffix,
                ),
            )
            is ReadingAnchor.BlockOffset -> JsAnchorPayload(
                ordinal = anchor.ordinal.value,
                href = "OEBPS/ch_${anchor.ordinal.twoDigit()}.xhtml",
                blockId = anchor.blockId.value,
            )
            is ReadingAnchor.PdfPage -> JsAnchorPayload(
                ordinal = anchor.ordinal.value,
                href = "OEBPS/ch_${anchor.ordinal.twoDigit()}.xhtml",
            )
        }
    }
}

@Serializable
internal data class JsAnnotationPayload(
    val noteId: String,
    val anchor: JsAnchorPayload,
    val colorToken: String,
    val comment: String,
    val deletedAtEpochMs: Long? = null,
) {
    companion object {
        fun fromDomain(entry: NoteLogEntry): JsAnnotationPayload = JsAnnotationPayload(
            noteId = entry.noteId,
            anchor = JsAnchorPayload.fromDomain(entry.anchor),
            colorToken = entry.colorToken,
            comment = entry.comment,
            deletedAtEpochMs = entry.deletedAtEpochMs,
        )
    }
}
