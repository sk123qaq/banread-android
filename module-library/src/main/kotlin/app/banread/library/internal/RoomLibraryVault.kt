package app.banread.library.internal

import android.content.Context
import android.net.Uri
import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import app.banread.model.BcBookManifest
import app.banread.model.BlockId
import app.banread.model.BookSourceHash
import app.banread.model.ChapterContentHash
import app.banread.model.ChapterOrdinal
import app.banread.model.ChapterOutlineItem
import app.banread.model.ChapterRole
import app.banread.model.FootnoteDetail
import app.banread.model.FootnoteLink
import app.banread.model.GatedBlock
import app.banread.model.GatedBookSlice
import app.banread.model.GatedChapterSlice
import app.banread.model.ImportedWork
import app.banread.model.LibraryVault
import app.banread.model.ReadProfileId
import app.banread.model.ReadingAnchor
import app.banread.model.ReadingSurfaceMode
import app.banread.model.ReflowPrepProgress
import app.banread.model.SpoilerGate
import app.banread.model.TextQuote
import app.banread.model.WorkId
import app.banread.model.WorkOutline
import app.banread.model.registry.ProfileRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.io.File
import java.util.UUID
import kotlin.math.ceil

internal class RoomLibraryVault private constructor(
    private val context: Context,
    private val filesRoot: File,
    private val profileRegistry: ProfileRegistry,
    private val db: LibraryDatabase,
) : LibraryVault, Closeable {

    private val dao = db.libraryDao()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun close() = db.close()

    override suspend fun ingestStudyPack(zipUriOrFile: String): Result<ImportedWork> = withContext(Dispatchers.IO) {
        val tempDir = File(filesRoot, "tmp/ingest_${UUID.randomUUID()}").apply { mkdirs() }
        val tempZip = File(tempDir, "pack.bcbook")
        try {
            copySourceToFile(zipUriOrFile, tempZip)
            val unpackDir = File(tempDir, "unpacked")
            LocalBookInspector.safeUnpackZip(tempZip, unpackDir)

            val manifestFile = File(unpackDir, "manifest.json")
            if (!manifestFile.isFile) {
                return@withContext Result.failure(
                    AppFailureException(AppFailure.UnsupportedDrmOrFormat("Missing manifest.json in .bcbook study pack")),
                )
            }
            val manifest = json.decodeFromString(BcBookManifest.serializer(), manifestFile.readText(Charsets.UTF_8))
            if (manifest.schemaVersion != 1) {
                return@withContext Result.failure(
                    AppFailureException(AppFailure.UnsupportedDrmOrFormat("Unsupported .bcbook schema_version=${manifest.schemaVersion}")),
                )
            }

            val existing = dao.findWorkById(manifest.uid.raw)
                ?: dao.findWorkBySourceHash(manifest.bookSourceSha256.sha256Hex)

            val booksDir = File(filesRoot, "books").apply { mkdirs() }
            val storedPackFile = File(booksDir, "${manifest.uid.raw}.bcbook")
            tempZip.copyTo(storedPackFile, overwrite = true)

            val mdDir = File(unpackDir, "md")
            val sortedEntries = manifest.logicalChapters.sortedBy { it.ordinal.value }
            val logicalInputs = sortedEntries.map { entry ->
                val parsedParts = entry.sourceFiles.map { rel ->
                    val candidate = File(mdDir, rel)
                    val mdCanonical = mdDir.canonicalFile.toPath()
                    val candCanonical = candidate.canonicalFile.toPath()
                    if (!candCanonical.startsWith(mdCanonical) || !candidate.isFile) {
                        throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("Missing or unsafe chapter file: $rel"))
                    }
                    val rawText = candidate.readText(Charsets.UTF_8)
                    MarkdownBlockParser.parse(
                        rawMarkdown = rawText,
                        fallbackTitle = entry.title,
                        chapterRoleHint = entry.chapterRole,
                    )
                }
                val combinedBlocks = RoleAndSplit.renumberBlocks(parsedParts.flatMap { it.blocks })
                val parsedChars = parsedParts.sumOf { it.nonSpaceChars }
                val firstTitle = parsedParts.firstOrNull { it.title.isNotBlank() }?.title.orEmpty()
                RoleAndSplit.LogicalChapterInput(
                    originalOrdinal = entry.ordinal.value,
                    n = entry.n,
                    title = entry.title.ifBlank { firstTitle },
                    role = entry.chapterRole,
                    merged = entry.merged,
                    sourceFiles = entry.sourceFiles,
                    contentSha256 = entry.chapterContentSha256.sha256Hex,
                    chars = maxOf(entry.chars, parsedChars),
                    blocks = combinedBlocks,
                )
            }

            val finalizedChapters = RoleAndSplit.applyVirtualSplits(logicalInputs)
            val images = collectImages(File(unpackDir, "images"), File(mdDir, "images"))

            val chaptersForResolution = finalizedChapters.map { ch ->
                val chOrd = ChapterOrdinal(ch.ordinal)
                ChapterForResolution(
                    ordinal = ch.ordinal,
                    title = ch.title,
                    role = ch.role,
                    sourceFiles = ch.sourceFiles,
                    compiledHref = "OEBPS/ch_${chOrd.twoDigit()}.xhtml",
                    blocks = ch.blocks,
                )
            }

            val fnResolution = FootnoteResolver.resolveAll(manifest.uid.raw, chaptersForResolution)

            val compiledRoot = File(filesRoot, "compiled/${manifest.uid.raw}")
            XhtmlBookCompiler.compile(
                compiledRoot = compiledRoot,
                input = XhtmlBookCompiler.CompileInput(
                    workId = manifest.uid.raw,
                    title = manifest.title,
                    author = manifest.author,
                    chapters = finalizedChapters,
                    inlineTargets = fnResolution.inlineTargetByRefKey,
                    images = images,
                ),
            )

            // Inherit companion_cache/ if present
            val unpackedCacheDir = File(unpackDir, "companion_cache")
            if (unpackedCacheDir.isDirectory) {
                val destCacheDir = File(filesRoot, "study_packs/${manifest.uid.raw}/companion_cache")
                if (destCacheDir.exists()) destCacheDir.deleteRecursively()
                destCacheDir.parentFile?.mkdirs()
                unpackedCacheDir.copyRecursively(destCacheDir, overwrite = true)
            }

            val preserveOverride = existing?.profileSource == "USER_OVERRIDE"
            val effectiveProfileId = if (preserveOverride) existing!!.readProfileId else manifest.readType.raw
            val effectiveSubKind = if (preserveOverride) existing!!.literatureSubKind else manifest.subKind.ifBlank { null }
            val effectiveProfileSource = if (preserveOverride) "USER_OVERRIDE" else "DESKTOP"

            val modes = setOf(ReadingSurfaceMode.REFLOWABLE)
            val workEntity = WorkEntity(
                workId = manifest.uid.raw,
                bookSourceSha256 = manifest.bookSourceSha256.sha256Hex,
                title = manifest.title,
                author = manifest.author,
                subjectsJson = json.encodeToString(ListSerializer(String.serializer()), manifest.subjects),
                readProfileId = effectiveProfileId,
                literatureSubKind = effectiveSubKind,
                profileSource = effectiveProfileSource,
                clothColor = manifest.clothColor,
                coverRel = manifest.coverRel,
                originalFilePath = storedPackFile.absolutePath,
                compiledRootPath = compiledRoot.absolutePath,
                format = "BCBOOK",
                supportedModesJson = json.encodeToString(ListSerializer(ReadingSurfaceMode.serializer()), modes.toList()),
                isScannedPdf = false,
                blockVersion = manifest.blockVersion,
                canonicalVersion = manifest.canonicalVersion,
                addedAtMs = existing?.addedAtMs ?: System.currentTimeMillis(),
            )

            val supersededWorkId = existing?.workId?.takeIf { it != manifest.uid.raw }
            val htmlIdsByChapter = chaptersForResolution.associate { it.ordinal to it.blockIdToHtmlIds }
            val (chapterEntities, blockEntities) = toRoomEntities(manifest.uid.raw, finalizedChapters, htmlIdsByChapter)
            dao.replaceWorkTree(
                work = workEntity,
                chapters = chapterEntities,
                blocks = blockEntities,
                footnotes = fnResolution.entities,
                supersededWorkId = supersededWorkId,
            )

            if (supersededWorkId != null) {
                File(filesRoot, "compiled/$supersededWorkId").deleteRecursively()
                val oldOriginal = existing?.originalFilePath?.takeIf { it.isNotBlank() }?.let(::File)
                if (oldOriginal != null &&
                    oldOriginal.exists() &&
                    oldOriginal.canonicalPath != storedPackFile.canonicalPath
                ) {
                    oldOriginal.delete()
                }
            }

            Result.success(workEntity.toImportedWork())
        } catch (ex: AppFailureException) {
            Result.failure(ex)
        } catch (ex: Exception) {
            Result.failure(
                AppFailureException(
                    AppFailure.UnsupportedDrmOrFormat("Failed to ingest .bcbook: ${ex.message ?: ex::class.simpleName}"),
                ),
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    override suspend fun importLocalBook(sourceUri: String): Result<ImportedWork> = withContext(Dispatchers.IO) {
        val tempDir = File(filesRoot, "tmp/import_${UUID.randomUUID()}").apply { mkdirs() }
        val tempFile = File(tempDir, "book.bin")
        try {
            copySourceToFile(sourceUri, tempFile)
            val sourceHash = BookSourceHash(TextSanitizer.sha256Hex(tempFile))

            // Deduplicate by BookSourceHash
            dao.findWorkBySourceHash(sourceHash.sha256Hex)?.let { existing ->
                return@withContext Result.success(existing.toImportedWork())
            }

            val format = LocalBookInspector.sniffFormat(tempFile)
            if (format == DetectedFormat.KFX_DRM) {
                return@withContext Result.failure(
                    AppFailureException(AppFailure.UnsupportedDrmOrFormat("Kindle KFX format/DRM is not supported")),
                )
            }
            if (format == DetectedFormat.UNKNOWN) {
                return@withContext Result.failure(
                    AppFailureException(AppFailure.UnsupportedDrmOrFormat("Unsupported or unrecognized ebook file format")),
                )
            }

            val workId = WorkId("local_${sourceHash.sha256Hex.take(16)}")
            val booksDir = File(filesRoot, "books").apply { mkdirs() }

            when (format) {
                DetectedFormat.EPUB -> {
                    val parsed = LocalBookInspector.parseEpub(tempFile)
                    val destFile = File(booksDir, "${workId.raw}.epub")
                    tempFile.copyTo(destFile, overwrite = true)

                    val resolvedProfile = resolveProfileSmart(parsed.subjects)
                    val clothColor = profileRegistry.clothColor(workId.raw)

                    val merged = RoleAndSplit.mergeTitlePages(parsed.chapters)
                    val logicalInputs = merged.mapIndexed { idx, ch ->
                        RoleAndSplit.LogicalChapterInput(
                            originalOrdinal = idx + 1,
                            n = ch.n,
                            title = ch.title,
                            role = ch.role,
                            merged = ch.merged,
                            sourceFiles = ch.sourceFiles,
                            contentSha256 = ch.contentSha256,
                            chars = ch.chars,
                            blocks = ch.blocks,
                        )
                    }
                    val finalized = RoleAndSplit.applyVirtualSplits(logicalInputs)

                    // Carry forward HTML element IDs across virtual splits
                    val preSplitHtmlIds = linkedMapOf<Int, Map<String, Set<String>>>()
                    merged.forEachIndexed { idx, ch ->
                        if (ch.blockIdToHtmlIds.isNotEmpty()) {
                            preSplitHtmlIds[idx + 1] = ch.blockIdToHtmlIds
                        }
                    }

                    val chaptersForResolution = finalized.map { ch ->
                        val chOrd = ChapterOrdinal(ch.ordinal)
                        val origMap = preSplitHtmlIds[ch.originalOrdinal].orEmpty()
                        val mappedHtmlIds = linkedMapOf<String, Set<String>>()
                        for ((oldId, newPair) in ch.oldToNewBlockMap) {
                            if (newPair.first == ch.ordinal) {
                                origMap[oldId]?.let { mappedHtmlIds[newPair.second] = it }
                            }
                        }
                        ChapterForResolution(
                            ordinal = ch.ordinal,
                            title = ch.title,
                            role = ch.role,
                            sourceFiles = ch.sourceFiles,
                            compiledHref = "OEBPS/ch_${chOrd.twoDigit()}.xhtml",
                            blocks = ch.blocks,
                            blockIdToHtmlIds = mappedHtmlIds,
                        )
                    }

                    val fnResolution = FootnoteResolver.resolveAll(workId.raw, chaptersForResolution)
                    val compiledRoot = File(filesRoot, "compiled/${workId.raw}")
                    XhtmlBookCompiler.compile(
                        compiledRoot = compiledRoot,
                        input = XhtmlBookCompiler.CompileInput(
                            workId = workId.raw,
                            title = parsed.title,
                            author = parsed.author,
                            chapters = finalized,
                            inlineTargets = fnResolution.inlineTargetByRefKey,
                            images = parsed.images,
                        ),
                    )

                    val modes = setOf(ReadingSurfaceMode.REFLOWABLE)
                    val workEntity = WorkEntity(
                        workId = workId.raw,
                        bookSourceSha256 = sourceHash.sha256Hex,
                        title = parsed.title,
                        author = parsed.author,
                        subjectsJson = json.encodeToString(ListSerializer(String.serializer()), parsed.subjects),
                        readProfileId = resolvedProfile.profile.readType.raw,
                        literatureSubKind = resolvedProfile.subKind.ifBlank { null },
                        profileSource = "AUTO",
                        clothColor = clothColor,
                        coverRel = null,
                        originalFilePath = destFile.absolutePath,
                        compiledRootPath = compiledRoot.absolutePath,
                        format = "EPUB",
                        supportedModesJson = json.encodeToString(ListSerializer(ReadingSurfaceMode.serializer()), modes.toList()),
                        isScannedPdf = false,
                        blockVersion = 2,
                        canonicalVersion = 1,
                        addedAtMs = System.currentTimeMillis(),
                    )
                    val htmlIdsByChapter = chaptersForResolution.associate { it.ordinal to it.blockIdToHtmlIds }
                    val (chapterEntities, blockEntities) = toRoomEntities(workId.raw, finalized, htmlIdsByChapter)
                    dao.replaceWorkTree(workEntity, chapterEntities, blockEntities, fnResolution.entities)
                    Result.success(workEntity.toImportedWork())
                }

                DetectedFormat.MOBI, DetectedFormat.AZW3 -> {
                    val parsed = LocalBookInspector.inspectAndParseMobi(tempFile)
                    val ext = if (parsed.formatName == "AZW3") "azw3" else "mobi"
                    val destFile = File(booksDir, "${workId.raw}.$ext")
                    tempFile.copyTo(destFile, overwrite = true)

                    val resolvedProfile = resolveProfileSmart(parsed.subjects)
                    val clothColor = profileRegistry.clothColor(workId.raw)

                    val logicalInputs = parsed.chapters.mapIndexed { idx, ch ->
                        RoleAndSplit.LogicalChapterInput(
                            originalOrdinal = idx + 1,
                            n = ch.n,
                            title = ch.title,
                            role = ch.role,
                            merged = false,
                            sourceFiles = listOf(ch.fileName),
                            contentSha256 = TextSanitizer.sha256Hex(ch.rawContentBytes),
                            chars = ch.chars,
                            blocks = ch.blocks,
                        )
                    }
                    val finalized = RoleAndSplit.applyVirtualSplits(logicalInputs)
                    val chaptersForResolution = finalized.map { ch ->
                        val chOrd = ChapterOrdinal(ch.ordinal)
                        ChapterForResolution(
                            ordinal = ch.ordinal,
                            title = ch.title,
                            role = ch.role,
                            sourceFiles = ch.sourceFiles,
                            compiledHref = "OEBPS/ch_${chOrd.twoDigit()}.xhtml",
                            blocks = ch.blocks,
                        )
                    }
                    val fnResolution = FootnoteResolver.resolveAll(workId.raw, chaptersForResolution)
                    val compiledRoot = File(filesRoot, "compiled/${workId.raw}")
                    XhtmlBookCompiler.compile(
                        compiledRoot = compiledRoot,
                        input = XhtmlBookCompiler.CompileInput(
                            workId = workId.raw,
                            title = parsed.title,
                            author = parsed.author,
                            chapters = finalized,
                            inlineTargets = fnResolution.inlineTargetByRefKey,
                            images = emptyMap(),
                        ),
                    )

                    val modes = setOf(ReadingSurfaceMode.REFLOWABLE)
                    val workEntity = WorkEntity(
                        workId = workId.raw,
                        bookSourceSha256 = sourceHash.sha256Hex,
                        title = parsed.title,
                        author = parsed.author,
                        subjectsJson = json.encodeToString(ListSerializer(String.serializer()), parsed.subjects),
                        readProfileId = resolvedProfile.profile.readType.raw,
                        literatureSubKind = resolvedProfile.subKind.ifBlank { null },
                        profileSource = "AUTO",
                        clothColor = clothColor,
                        coverRel = null,
                        originalFilePath = destFile.absolutePath,
                        compiledRootPath = compiledRoot.absolutePath,
                        format = parsed.formatName,
                        supportedModesJson = json.encodeToString(ListSerializer(ReadingSurfaceMode.serializer()), modes.toList()),
                        isScannedPdf = false,
                        blockVersion = 2,
                        canonicalVersion = 1,
                        addedAtMs = System.currentTimeMillis(),
                    )
                    val (chapterEntities, blockEntities) = toRoomEntities(workId.raw, finalized)
                    dao.replaceWorkTree(workEntity, chapterEntities, blockEntities, fnResolution.entities)
                    Result.success(workEntity.toImportedWork())
                }

                DetectedFormat.PDF -> {
                    val destFile = File(booksDir, "${workId.raw}.pdf")
                    tempFile.copyTo(destFile, overwrite = true)
                    val isScanned = LocalBookInspector.probeIsScannedPdf(destFile)
                    val title = guessTitleFromUri(sourceUri).ifBlank { workId.raw }
                    val resolvedProfile = profileRegistry.resolveProfile(null, null)
                    val clothColor = profileRegistry.clothColor(workId.raw)
                    val modes = setOf(ReadingSurfaceMode.PDF_FIXED)

                    val workEntity = WorkEntity(
                        workId = workId.raw,
                        bookSourceSha256 = sourceHash.sha256Hex,
                        title = title,
                        author = "",
                        subjectsJson = "[]",
                        readProfileId = resolvedProfile.profile.readType.raw,
                        literatureSubKind = resolvedProfile.subKind.ifBlank { null },
                        profileSource = "AUTO",
                        clothColor = clothColor,
                        coverRel = null,
                        originalFilePath = destFile.absolutePath,
                        compiledRootPath = "",
                        format = "PDF",
                        supportedModesJson = json.encodeToString(ListSerializer(ReadingSurfaceMode.serializer()), modes.toList()),
                        isScannedPdf = isScanned,
                        blockVersion = 2,
                        canonicalVersion = 1,
                        addedAtMs = System.currentTimeMillis(),
                    )
                    val pdfStubChapter = ChapterEntity(
                        workId = workId.raw,
                        ordinal = 1,
                        n = "01",
                        title = title,
                        chapterRole = ChapterRole.BODY.name,
                        merged = false,
                        sourceFilesJson = json.encodeToString(ListSerializer(String.serializer()), listOf(destFile.name)),
                        chapterContentSha256 = sourceHash.sha256Hex,
                        chars = 0,
                        blockCount = 0,
                        compiledHref = "",
                        virtualSplitParentOrdinal = null,
                    )
                    dao.replaceWorkTree(workEntity, listOf(pdfStubChapter), emptyList(), emptyList())
                    Result.success(workEntity.toImportedWork())
                }

                else -> Result.failure(
                    AppFailureException(AppFailure.UnsupportedDrmOrFormat("Unsupported format: $format")),
                )
            }
        } catch (ex: AppFailureException) {
            Result.failure(ex)
        } catch (ex: Exception) {
            Result.failure(
                AppFailureException(
                    AppFailure.UnsupportedDrmOrFormat("Failed to import book: ${ex.message ?: ex::class.simpleName}"),
                ),
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    override suspend fun prepareTextPdfReflow(workId: WorkId): Flow<ReflowPrepProgress> = flow {
        val work = dao.findWorkById(workId.raw)
            ?: throw NoSuchElementException("Work not found: ${workId.raw}")
        if (work.isScannedPdf) {
            throw AppFailureException(AppFailure.ScannedPdfNeedsOcr(workId))
        }
        // Full PDFium text + image reflow arrives in Slice 8; emit deterministic completion status.
        emit(
            ReflowPrepProgress(
                workId = workId,
                processedPages = 0,
                totalPages = 0,
                completed = true,
            ),
        )
    }

    override suspend fun getWorkOutline(workId: WorkId): WorkOutline = withContext(Dispatchers.IO) {
        val work = dao.findWorkById(workId.raw)
            ?: throw NoSuchElementException("Work not found: ${workId.raw}")
        val chapters = dao.getChapters(workId.raw)

        val body = mutableListOf<ChapterOutlineItem>()
        val notes = mutableListOf<ChapterOutlineItem>()
        val frontBack = mutableListOf<ChapterOutlineItem>()

        for (ch in chapters) {
            val role = ChapterRole.valueOf(ch.chapterRole)
            val estMinutes = if (ch.chars <= 0) 0 else maxOf(1, ceil(ch.chars / 480.0).toInt())
            val item = ChapterOutlineItem(
                ordinal = ChapterOrdinal(ch.ordinal),
                title = ch.title,
                role = role,
                estimatedMinutes = estMinutes,
                merged = ch.merged,
            )
            when (role) {
                ChapterRole.BODY -> body += item
                ChapterRole.NOTES -> notes += item
                ChapterRole.FRONT_BACK -> frontBack += item
            }
        }

        WorkOutline(
            workId = workId,
            title = work.title,
            bodyChapters = body,
            notesChapters = notes,
            frontBackChapters = frontBack,
            readProfileId = ReadProfileId(work.readProfileId),
            literatureSubKind = work.literatureSubKind,
        )
    }

    override suspend fun resolveAnchor(
        workId: WorkId,
        ordinal: ChapterOrdinal,
        blockId: BlockId,
    ): ReadingAnchor = withContext(Dispatchers.IO) {
        val work = dao.findWorkById(workId.raw)
            ?: throw NoSuchElementException("Work not found: ${workId.raw}")
        val chapter = dao.getChapter(workId.raw, ordinal.value)
            ?: throw NoSuchElementException("Chapter ${ordinal.value} not found for ${workId.raw}")
        val block = dao.getBlock(workId.raw, ordinal.value, blockId.value)
            ?: throw NoSuchElementException("Block ${blockId.value} not found in chapter ${ordinal.value}")

        val modes = json.decodeFromString(ListSerializer(ReadingSurfaceMode.serializer()), work.supportedModesJson)
        if (ReadingSurfaceMode.REFLOWABLE !in modes) {
            return@withContext ReadingAnchor.BlockOffset(
                ordinal = ordinal,
                blockId = blockId,
                charStart = block.charStart,
                charEnd = block.charEnd,
                snippet = block.cleanText.take(160),
            )
        }

        val chBlocks = dao.getBlocksForChapter(workId.raw, ordinal.value)
        val prevText = chBlocks.getOrNull(block.blockIndex - 1)?.cleanText.orEmpty().takeLast(32)
        val nextText = chBlocks.getOrNull(block.blockIndex + 1)?.cleanText.orEmpty().take(32)
        val allChapters = dao.getChapters(workId.raw)
        val chCount = maxOf(1, allChapters.size)
        val chIdx = allChapters.indexOfFirst { it.ordinal == ordinal.value }.coerceAtLeast(0)
        val progression = if (chapter.blockCount > 0) {
            block.blockIndex.toFloat() / chapter.blockCount.toFloat()
        } else {
            0f
        }
        val totalProgression = ((chIdx.toFloat() + progression) / chCount.toFloat()).coerceIn(0f, 1f)

        ReadingAnchor.ReflowCfi(
            ordinal = ordinal,
            href = chapter.compiledHref,
            progression = progression,
            totalProgression = totalProgression,
            cfi = "epubcfi(/6/${ordinal.value * 2}!/4/${(block.blockIndex + 1) * 2})",
            textQuote = TextQuote(
                prefix = prevText,
                exact = block.cleanText.take(160),
                suffix = nextText,
            ),
            blockId = blockId,
        )
    }

    override suspend fun resolveFootnote(
        workId: WorkId,
        currentOrdinal: ChapterOrdinal,
        markerOrHref: String,
    ): FootnoteDetail? = withContext(Dispatchers.IO) {
        val allFootnotes = dao.getFootnotesForWork(workId.raw)
        if (allFootnotes.isEmpty()) return@withContext null

        val raw = markerOrHref.trim()
        val hasPathOrHash = '#' in raw || ".xhtml" in raw.lowercase()
        val filePart = raw.substringBefore('#', "").substringAfterLast('/').trim()
        val fragmentPart = raw.substringAfter('#', "").trim()

        val targetChapterFromHref = Regex("""ch_(\d+)\.xhtml""", RegexOption.IGNORE_CASE)
            .find(filePart)?.groupValues?.get(1)?.toIntOrNull()

        val cleanedMarkerInput = if (fragmentPart.isNotEmpty() && !fragmentPart.matches(Regex("""^B\d+$"""))) {
            fragmentPart.removePrefix("fn-").removePrefix("noteref-").removePrefix("note-")
        } else if (!hasPathOrHash) {
            raw
        } else {
            ""
        }
        val (_, normalizedKey) = MarkdownBlockParser.normalizeMarker(cleanedMarkerInput)

        val matched: FootnoteEntity = when {
            // 1) Target href with BlockId fragment (e.g. "ch_20.xhtml#B45" or "#B45")
            fragmentPart.matches(Regex("""^B\d+$""")) -> {
                val targetOrd = targetChapterFromHref ?: currentOrdinal.value
                allFootnotes.firstOrNull {
                    it.sourceChapterOrdinal == currentOrdinal.value &&
                        it.targetChapterOrdinal == targetOrd &&
                        it.targetBlockId == fragmentPart
                } ?: allFootnotes.firstOrNull {
                    it.targetChapterOrdinal == targetOrd && it.targetBlockId == fragmentPart
                }
            }
            // 2) Original HTML fragment lookup (e.g. "notes.xhtml#fn_after_merge") with marker fallback
            fragmentPart.isNotEmpty() -> {
                findByOriginalHtmlFragment(
                    workId = workId.raw,
                    currentOrdinal = currentOrdinal.value,
                    filePart = filePart,
                    targetChapterFromHref = targetChapterFromHref,
                    fragment = fragmentPart,
                    allFootnotes = allFootnotes,
                ) ?: findByNormalizedMarker(currentOrdinal.value, normalizedKey, allFootnotes)
            }
            // 3) Marker lookup preferring sourceChapterOrdinal == currentOrdinal
            normalizedKey.isNotEmpty() -> {
                findByNormalizedMarker(currentOrdinal.value, normalizedKey, allFootnotes)
            }
            else -> null
        } ?: return@withContext null

        val targetChapter = dao.getChapter(workId.raw, matched.targetChapterOrdinal)
        val link = FootnoteLink(
            id = matched.footnoteId,
            workId = workId,
            sourceChapterOrdinal = ChapterOrdinal(matched.sourceChapterOrdinal),
            sourceBlockId = BlockId(matched.sourceBlockId),
            marker = matched.marker,
            targetChapterOrdinal = ChapterOrdinal(matched.targetChapterOrdinal),
            targetBlockId = BlockId(matched.targetBlockId),
            previewText = matched.noteText.take(120),
        )
        FootnoteDetail(
            link = link,
            targetChapterTitle = targetChapter?.title.orEmpty(),
            fullNoteText = matched.noteText,
        )
    }

    override suspend fun overrideProfile(
        workId: WorkId,
        profile: ReadProfileId,
        literatureSubKind: String?,
    ) = withContext(Dispatchers.IO) {
        val existing = dao.findWorkById(workId.raw)
            ?: throw NoSuchElementException("Work not found: ${workId.raw}")
        dao.updateProfileOverride(
            workId = existing.workId,
            readProfileId = profile.raw,
            literatureSubKind = literatureSubKind?.takeIf { it.isNotBlank() },
            profileSource = "USER_OVERRIDE",
        )
    }

    override suspend fun loadGatedChapterInputs(
        workId: WorkId,
        gate: SpoilerGate,
    ): GatedBookSlice = withContext(Dispatchers.IO) {
        dao.findWorkById(workId.raw)
            ?: throw NoSuchElementException("Work not found: ${workId.raw}")

        val chapters = dao.getChapters(workId.raw)
        val gatedBodyChapters = chapters.filter { ch ->
            ch.chapterRole == ChapterRole.BODY.name && when (gate) {
                is SpoilerGate.UpTo -> ch.ordinal <= gate.unlockedMaxOrdinal.value
                SpoilerGate.FullBookUnlocked -> true
            }
        }

        val blocksByOrdinal = when (gate) {
            is SpoilerGate.UpTo -> dao.getBlocksUpToOrdinal(workId.raw, gate.unlockedMaxOrdinal.value)
            SpoilerGate.FullBookUnlocked -> dao.getAllBlocksForWork(workId.raw)
        }.groupBy { it.ordinal }

        val chapterSlices = gatedBodyChapters.map { ch ->
            val blocks = blocksByOrdinal[ch.ordinal].orEmpty().map { blk ->
                GatedBlock(
                    blockId = BlockId(blk.blockId),
                    text = blk.cleanText,
                )
            }
            GatedChapterSlice(
                ordinal = ChapterOrdinal(ch.ordinal),
                title = ch.title,
                role = ChapterRole.valueOf(ch.chapterRole),
                contentHash = ChapterContentHash(ch.chapterContentSha256),
                blocks = blocks,
            )
        }

        val footnoteEntities = when (gate) {
            is SpoilerGate.UpTo -> dao.getFootnotesUpToOrdinal(workId.raw, gate.unlockedMaxOrdinal.value)
            SpoilerGate.FullBookUnlocked -> dao.getFootnotesNonBoilerplate(workId.raw)
        }

        val footnoteLinks = footnoteEntities.map { fn ->
            FootnoteLink(
                id = fn.footnoteId,
                workId = workId,
                sourceChapterOrdinal = ChapterOrdinal(fn.sourceChapterOrdinal),
                sourceBlockId = BlockId(fn.sourceBlockId),
                marker = fn.marker,
                targetChapterOrdinal = ChapterOrdinal(fn.targetChapterOrdinal),
                targetBlockId = BlockId(fn.targetBlockId),
                previewText = fn.noteText.take(120),
            )
        }

        GatedBookSlice(
            workId = workId,
            gate = gate,
            chapters = chapterSlices,
            footnotes = footnoteLinks,
        )
    }

    private fun copySourceToFile(uriOrPath: String, dest: File) {
        dest.parentFile?.mkdirs()
        if (uriOrPath.startsWith("content://")) {
            val uri = Uri.parse(uriOrPath)
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("Unable to open content URI: $uriOrPath"))
            stream.use { input ->
                dest.outputStream().buffered().use { output -> input.copyTo(output) }
            }
        } else {
            val localFile = if (uriOrPath.startsWith("file://")) {
                File(Uri.parse(uriOrPath).path ?: "")
            } else {
                File(uriOrPath)
            }
            if (!localFile.isFile) {
                throw AppFailureException(AppFailure.UnsupportedDrmOrFormat("Source file not found: $uriOrPath"))
            }
            localFile.inputStream().buffered().use { input ->
                dest.outputStream().buffered().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun collectImages(primaryDir: File, secondaryDir: File): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        for (dir in listOf(secondaryDir, primaryDir)) {
            if (!dir.isDirectory) continue
            dir.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.relativeTo(dir).invariantSeparatorsPath }
                .forEach { file ->
                    val rel = "images/${file.relativeTo(dir).invariantSeparatorsPath}"
                    result.putIfAbsent(rel, file.readBytes())
                }
        }
        return result
    }

    private fun findByNormalizedMarker(
        currentOrdinal: Int,
        normalizedKey: String,
        allFootnotes: List<FootnoteEntity>,
    ): FootnoteEntity? {
        if (normalizedKey.isEmpty()) return null
        return allFootnotes.firstOrNull {
            it.sourceChapterOrdinal == currentOrdinal &&
                MarkdownBlockParser.normalizeMarker(it.marker).second == normalizedKey
        } ?: allFootnotes.firstOrNull {
            MarkdownBlockParser.normalizeMarker(it.marker).second == normalizedKey
        }
    }

    private suspend fun findByOriginalHtmlFragment(
        workId: String,
        currentOrdinal: Int,
        filePart: String,
        targetChapterFromHref: Int?,
        fragment: String,
        allFootnotes: List<FootnoteEntity>,
    ): FootnoteEntity? {
        val chapters = dao.getChapters(workId)
        val candidateOrdinals = if (filePart.isNotEmpty()) {
            chapters.filter { ch ->
                targetChapterFromHref == ch.ordinal ||
                    File(ch.compiledHref).name.equals(filePart, ignoreCase = true) ||
                    runCatching {
                        json.decodeFromString(ListSerializer(String.serializer()), ch.sourceFilesJson)
                    }.getOrDefault(emptyList()).any { sf ->
                        File(sf).name.equals(filePart, ignoreCase = true)
                    }
            }.map { it.ordinal }.toSet()
        } else {
            chapters.map { it.ordinal }.toSet()
        }
        if (candidateOrdinals.isEmpty()) return null

        val matchingBlocks = dao.getAllBlocksForWork(workId).filter { blk ->
            if (blk.ordinal !in candidateOrdinals) return@filter false
            val htmlIds = runCatching {
                json.parseToJsonElement(blk.locatorJson).jsonObject["htmlIds"]
                    ?.jsonArray
                    ?.map { it.jsonPrimitive.content }
                    .orEmpty()
            }.getOrDefault(emptyList())
            fragment in htmlIds
        }
        for (blk in matchingBlocks) {
            val exactSource = allFootnotes.firstOrNull {
                it.sourceChapterOrdinal == currentOrdinal &&
                    it.targetChapterOrdinal == blk.ordinal &&
                    it.targetBlockId == blk.blockId
            }
            if (exactSource != null) return exactSource
        }
        for (blk in matchingBlocks) {
            val anySource = allFootnotes.firstOrNull {
                it.targetChapterOrdinal == blk.ordinal && it.targetBlockId == blk.blockId
            }
            if (anySource != null) return anySource
        }
        return null
    }

    private fun toRoomEntities(
        workId: String,
        chapters: List<RoleAndSplit.FinalizedChapter>,
        htmlIdsByChapter: Map<Int, Map<String, Set<String>>> = emptyMap(),
    ): Pair<List<ChapterEntity>, List<ChapterBlockEntity>> {
        val chapterEntities = mutableListOf<ChapterEntity>()
        val blockEntities = mutableListOf<ChapterBlockEntity>()
        for (ch in chapters) {
            val chOrd = ChapterOrdinal(ch.ordinal)
            val href = "OEBPS/ch_${chOrd.twoDigit()}.xhtml"
            chapterEntities += ChapterEntity(
                workId = workId,
                ordinal = ch.ordinal,
                n = ch.n,
                title = ch.title,
                chapterRole = ch.role.name,
                merged = ch.merged,
                sourceFilesJson = json.encodeToString(ListSerializer(String.serializer()), ch.sourceFiles),
                chapterContentSha256 = ch.contentSha256,
                chars = ch.chars,
                blockCount = ch.blocks.size,
                compiledHref = href,
                virtualSplitParentOrdinal = ch.virtualSplitParentOrdinal,
            )
            for (blk in ch.blocks) {
                val htmlIds = htmlIdsByChapter[ch.ordinal]?.get(blk.id).orEmpty()
                val locator = if (htmlIds.isEmpty()) {
                    """{"href":"$href","blockId":"${blk.id}"}"""
                } else {
                    val idsJson = json.encodeToString(ListSerializer(String.serializer()), htmlIds.toList())
                    """{"href":"$href","blockId":"${blk.id}","htmlIds":$idsJson}"""
                }
                blockEntities += ChapterBlockEntity(
                    workId = workId,
                    ordinal = ch.ordinal,
                    blockId = blk.id,
                    blockIndex = blk.blockIndex,
                    kind = blk.kind,
                    headingPath = blk.headingPath,
                    cleanText = blk.cleanText,
                    charStart = blk.charStart,
                    charEnd = blk.charEnd,
                    locatorJson = locator,
                )
            }
        }
        return chapterEntities to blockEntities
    }

    private fun WorkEntity.toImportedWork(): ImportedWork {
        val modes = json.decodeFromString(ListSerializer(ReadingSurfaceMode.serializer()), supportedModesJson).toSet()
        return ImportedWork(
            workId = WorkId(workId),
            title = title,
            author = author,
            readProfileId = ReadProfileId(readProfileId),
            literatureSubKind = literatureSubKind,
            clothColor = clothColor,
            supportedModes = modes,
            isScannedPdf = isScannedPdf,
        )
    }

    private fun resolveProfileSmart(subjects: List<String>): app.banread.model.registry.ResolvedProfile {
        val cleaned = subjects.map { it.trim() }.filter { it.isNotEmpty() }
        val s1 = cleaned.getOrNull(0)
        val s2 = cleaned.getOrNull(1)
        if (s1 != null && s2 == null && s1 !in profileRegistry.taxonomy) {
            val parent = profileRegistry.taxonomy.entries.firstOrNull { s1 in it.value }?.key
            if (parent != null) {
                return profileRegistry.resolveProfile(parent, s1)
            }
        }
        return profileRegistry.resolveProfile(s1, s2)
    }

    private fun guessTitleFromUri(uriOrPath: String): String {
        val last = uriOrPath.substringAfterLast('/')
        return last.substringBeforeLast('.').ifBlank { last }
    }

    companion object {
        fun create(
            context: Context,
            filesRoot: File,
            profileRegistry: ProfileRegistry,
            allowInMemory: Boolean,
        ): RoomLibraryVault {
            filesRoot.mkdirs()
            val db = LibraryDatabase.build(context, allowInMemory = allowInMemory)
            return RoomLibraryVault(
                context = context,
                filesRoot = filesRoot,
                profileRegistry = profileRegistry,
                db = db,
            )
        }
    }
}
