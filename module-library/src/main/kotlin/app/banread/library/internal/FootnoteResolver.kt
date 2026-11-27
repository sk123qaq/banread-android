package app.banread.library.internal

import app.banread.model.ChapterOrdinal
import app.banread.model.ChapterRole
import java.io.File

internal data class ChapterForResolution(
    val ordinal: Int,
    val title: String,
    val role: ChapterRole,
    val sourceFiles: List<String>,
    val compiledHref: String,
    val blocks: List<ParsedBlock>,
    val blockIdToHtmlIds: Map<String, Set<String>> = emptyMap(),
)

internal data class ResolvedRefTarget(
    val targetChapterOrdinal: Int,
    val targetBlockId: String,
    val compiledHref: String,
    val noteText: String,
    val markerDisplay: String,
)

internal data class FootnoteResolutionResult(
    val entities: List<FootnoteEntity>,
    // Key: (sourceChapterOrdinal, sourceBlockId, markerKey, occurrenceIdxInBlock) -> resolved target
    val inlineTargetByRefKey: Map<RefLookupKey, ResolvedRefTarget>,
)

internal data class RefLookupKey(
    val sourceOrdinal: Int,
    val sourceBlockId: String,
    val markerKey: String,
    val occurrenceInBlock: Int,
)

internal object FootnoteResolver {

    private data class DefEntry(
        val chapterOrdinal: Int,
        val compiledHref: String,
        val blockId: String,
        val markerDisplay: String,
        val markerKey: String,
        val noteText: String,
        val headingPath: String,
        val sourceFiles: List<String>,
        val htmlIds: Set<String>,
        val isTranslatorOrBib: Boolean,
    )

    fun resolveAll(
        workId: String,
        chapters: List<ChapterForResolution>,
    ): FootnoteResolutionResult {
        val defsByChapterAndKey = mutableMapOf<Pair<Int, String>, MutableList<DefEntry>>()
        val defsByFileAndFragment = mutableMapOf<Pair<String, String>, DefEntry>()
        val defsByFileAndKey = mutableMapOf<Pair<String, String>, MutableList<DefEntry>>()
        val notesChapterDefsByKey = mutableMapOf<String, MutableList<DefEntry>>()
        val allDefsByKey = mutableMapOf<String, MutableList<DefEntry>>()

        for (ch in chapters) {
            val fileNames = ch.sourceFiles.map { File(it).name.lowercase() } +
                listOf(File(ch.compiledHref).name.lowercase())
            for (blk in ch.blocks) {
                val htmlIds = ch.blockIdToHtmlIds[blk.id].orEmpty()
                if (blk.kind == "footnote" && blk.fnMarkerKey.isNotEmpty()) {
                    val isBoilerplate = MarkdownBlockParser.isTranslatorOrBibNote(
                        marker = blk.fnMarkerDisplay,
                        noteText = blk.cleanText,
                        headingPath = blk.headingPath,
                    )
                    val entry = DefEntry(
                        chapterOrdinal = ch.ordinal,
                        compiledHref = ch.compiledHref,
                        blockId = blk.id,
                        markerDisplay = blk.fnMarkerDisplay.ifEmpty { blk.fnMarkerKey },
                        markerKey = blk.fnMarkerKey,
                        noteText = blk.cleanText,
                        headingPath = blk.headingPath,
                        sourceFiles = ch.sourceFiles,
                        htmlIds = htmlIds,
                        isTranslatorOrBib = isBoilerplate,
                    )
                    defsByChapterAndKey.getOrPut(ch.ordinal to blk.fnMarkerKey) { mutableListOf() }.add(entry)
                    allDefsByKey.getOrPut(blk.fnMarkerKey) { mutableListOf() }.add(entry)
                    if (ch.role == ChapterRole.NOTES) {
                        notesChapterDefsByKey.getOrPut(blk.fnMarkerKey) { mutableListOf() }.add(entry)
                    }
                    for (fname in fileNames) {
                        if (fname.isNotEmpty()) {
                            defsByFileAndKey.getOrPut(fname to blk.fnMarkerKey) { mutableListOf() }.add(entry)
                            for (hid in htmlIds) {
                                defsByFileAndFragment[fname to hid] = entry
                            }
                        }
                    }
                } else if (htmlIds.isNotEmpty()) {
                    // Even non-footnote blocks with an HTML id (e.g. <p id="fn1">) can be footnote targets
                    val entry = DefEntry(
                        chapterOrdinal = ch.ordinal,
                        compiledHref = ch.compiledHref,
                        blockId = blk.id,
                        markerDisplay = "",
                        markerKey = "",
                        noteText = blk.cleanText,
                        headingPath = blk.headingPath,
                        sourceFiles = ch.sourceFiles,
                        htmlIds = htmlIds,
                        isTranslatorOrBib = MarkdownBlockParser.isTranslatorOrBibNote("", blk.cleanText, blk.headingPath),
                    )
                    for (fname in fileNames) {
                        if (fname.isNotEmpty()) {
                            for (hid in htmlIds) {
                                defsByFileAndFragment[fname to hid] = entry
                            }
                        }
                    }
                }
            }
        }

        val entities = mutableListOf<FootnoteEntity>()
        val targetMap = linkedMapOf<RefLookupKey, ResolvedRefTarget>()
        val usedIds = hashSetOf<String>()

        for (ch in chapters) {
            val chFileNames = ch.sourceFiles.map { File(it).name.lowercase() } +
                listOf(File(ch.compiledHref).name.lowercase())

            for (blk in ch.blocks) {
                val perMarkerCounter = mutableMapOf<String, Int>()
                for (ref in blk.fnRefs) {
                    val occ = perMarkerCounter.getOrDefault(ref.markerKey, 0)
                    perMarkerCounter[ref.markerKey] = occ + 1

                    val resolved = findTarget(
                        sourceOrdinal = ch.ordinal,
                        sourceTitle = ch.title,
                        sourceFileNames = chFileNames,
                        ref = ref,
                        defsByChapterAndKey = defsByChapterAndKey,
                        defsByFileAndFragment = defsByFileAndFragment,
                        defsByFileAndKey = defsByFileAndKey,
                        notesChapterDefsByKey = notesChapterDefsByKey,
                        allDefsByKey = allDefsByKey,
                    ) ?: continue

                    val lookupKey = RefLookupKey(ch.ordinal, blk.id, ref.markerKey, occ)
                    targetMap[lookupKey] = ResolvedRefTarget(
                        targetChapterOrdinal = resolved.chapterOrdinal,
                        targetBlockId = resolved.blockId,
                        compiledHref = if (resolved.chapterOrdinal == ch.ordinal) {
                            "#${resolved.blockId}"
                        } else {
                            "${ChapterOrdinal(resolved.chapterOrdinal).let { "ch_${it.twoDigit()}.xhtml" }}#${resolved.blockId}"
                        },
                        noteText = resolved.noteText,
                        markerDisplay = ref.markerDisplay,
                    )

                    val baseId = "fn_ch${ChapterOrdinal(ch.ordinal).twoDigit()}_${blk.id}_${ref.markerKey}"
                    var fnId = baseId
                    var suffix = 2
                    while (!usedIds.add(fnId)) {
                        fnId = "${baseId}_$suffix"
                        suffix++
                    }

                    entities += FootnoteEntity(
                        workId = workId,
                        footnoteId = fnId,
                        sourceChapterOrdinal = ch.ordinal,
                        sourceBlockId = blk.id,
                        marker = ref.markerDisplay,
                        targetChapterOrdinal = resolved.chapterOrdinal,
                        targetBlockId = resolved.blockId,
                        noteText = resolved.noteText,
                        isTranslatorOrBib = resolved.isTranslatorOrBib,
                    )
                }
            }
        }

        return FootnoteResolutionResult(
            entities = entities,
            inlineTargetByRefKey = targetMap,
        )
    }

    private fun findTarget(
        sourceOrdinal: Int,
        sourceTitle: String,
        sourceFileNames: List<String>,
        ref: InlineFnRef,
        defsByChapterAndKey: Map<Pair<Int, String>, List<DefEntry>>,
        defsByFileAndFragment: Map<Pair<String, String>, DefEntry>,
        defsByFileAndKey: Map<Pair<String, String>, List<DefEntry>>,
        notesChapterDefsByKey: Map<String, List<DefEntry>>,
        allDefsByKey: Map<String, List<DefEntry>>,
    ): DefEntry? {
        val targetFile = ref.targetFile.lowercase()
        val fragment = ref.targetFragment

        // 1) Explicit target file + HTML fragment id
        if (targetFile.isNotEmpty() && fragment.isNotEmpty()) {
            defsByFileAndFragment[targetFile to fragment]?.let { return it }
        }
        // 2) Same-chapter HTML fragment id
        if (targetFile.isEmpty() && fragment.isNotEmpty()) {
            for (fname in sourceFileNames) {
                defsByFileAndFragment[fname to fragment]?.let { return it }
            }
        }
        // 3) Intra-chapter definition by marker key
        defsByChapterAndKey[sourceOrdinal to ref.markerKey]?.firstOrNull()?.let { return it }

        // 4) Explicit target file + marker key
        if (targetFile.isNotEmpty()) {
            val candidates = defsByFileAndKey[targetFile to ref.markerKey]
            if (!candidates.isNullOrEmpty()) {
                return pickBestCandidate(candidates, sourceTitle)
            }
        }

        // 5) Cross-chapter NOTES chapter definition by marker key
        val notesCandidates = notesChapterDefsByKey[ref.markerKey]
        if (!notesCandidates.isNullOrEmpty()) {
            return pickBestCandidate(notesCandidates, sourceTitle)
        }

        // 6) Fallback: any other chapter's footnote block with matching marker key (excluding source if empty)
        val anyCandidates = allDefsByKey[ref.markerKey]
        if (!anyCandidates.isNullOrEmpty()) {
            return pickBestCandidate(anyCandidates, sourceTitle)
        }

        return null
    }

    private fun pickBestCandidate(candidates: List<DefEntry>, sourceTitle: String): DefEntry {
        if (candidates.size == 1 || sourceTitle.isBlank()) return candidates.first()
        val normTitle = sourceTitle.replace(Regex("""[\s\p{Punct}]+"""), "").lowercase()
        if (normTitle.isNotEmpty()) {
            val byHeading = candidates.firstOrNull { entry ->
                val normHeading = entry.headingPath.replace(Regex("""[\s\p{Punct}]+"""), "").lowercase()
                normHeading.isNotEmpty() && (normTitle in normHeading || normHeading in normTitle)
            }
            if (byHeading != null) return byHeading
        }
        return candidates.first()
    }
}
