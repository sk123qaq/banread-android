package app.banread.reader.internal

import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import app.banread.model.BlockId
import app.banread.model.BookRendererPort
import app.banread.model.ChapterOrdinal
import app.banread.model.DeviceId
import app.banread.model.DeviceProgressShard
import app.banread.model.LibraryVault
import app.banread.model.NoteLogEntry
import app.banread.model.ReaderEngine
import app.banread.model.ReaderSessionHandle
import app.banread.model.ReaderSessionState
import app.banread.model.ReaderTheme
import app.banread.model.ReadingAnchor
import app.banread.model.ReadingSurfaceMode
import app.banread.model.TextQuote
import app.banread.model.TypographyProfile
import app.banread.model.WorkId
import app.banread.reader.RegisteredFont
import app.banread.reader.TextAlignment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File
import java.util.UUID

internal class DefaultReaderEngine(
    private val filesRoot: File,
    private val libraryVault: LibraryVault,
    private val rendererPort: BookRendererPort,
    private val db: ReaderDatabase,
    private val deviceId: DeviceId,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    collectorDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val ownsDatabase: Boolean = false,
) : ReaderEngine, Closeable {

    private val dao = db.readerDao()
    private val mutex = Mutex()
    private val engineScope = CoroutineScope(SupervisorJob() + collectorDispatcher)
    private var locationCollectorJob: Job? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _activeState = MutableStateFlow(ReaderSessionState())
    override val activeState: StateFlow<ReaderSessionState> = _activeState.asStateFlow()

    private var currentTypography = TypographyProfile(
        fontFamily = "Noto Serif CJK SC",
        fontSizeSp = 18f,
        lineHeightMultiplier = 1.6f,
        paragraphSpacingDp = 12f,
        horizontalMarginDp = 20f,
        squeezeChinesePunctuation = true,
    )
    private var currentTheme = ReaderTheme.PAPER_LIGHT
    private var currentAlignment = TextAlignment.JUSTIFY

    override suspend fun open(
        workId: WorkId,
        modePreference: ReadingSurfaceMode,
    ): ReaderSessionHandle {
        val outline = libraryVault.getWorkOutline(workId)
        val availability = probeSurfaceAvailability(workId)

        val effectiveMode = when {
            modePreference == ReadingSurfaceMode.REFLOWABLE && availability.hasReflowable ->
                ReadingSurfaceMode.REFLOWABLE
            modePreference == ReadingSurfaceMode.PDF_FIXED && availability.hasPdfFixed ->
                ReadingSurfaceMode.PDF_FIXED
            availability.hasReflowable -> ReadingSurfaceMode.REFLOWABLE
            availability.hasPdfFixed -> ReadingSurfaceMode.PDF_FIXED
            else -> throw AppFailureException(
                AppFailure.UnsupportedDrmOrFormat("No compiled XHTML or PDF asset found for ${workId.raw}"),
            )
        }

        val (initialAnchor, assetRoot, notes) = mutex.withLock {
            val saved = withContext(ioDispatcher) { dao.findProgress(workId.raw) }
            val resolvedInitial: ReadingAnchor

            if (saved != null) {
                val savedCursor = decodeAnchor(saved.cursorJson)
                val savedMode = runCatching { ReadingSurfaceMode.valueOf(saved.surfaceMode) }
                    .getOrDefault(effectiveMode)
                resolvedInitial = if (savedMode == effectiveMode) {
                    savedCursor
                } else {
                    adaptAnchorForMode(workId, savedCursor.ordinal, effectiveMode)
                }
            } else {
                val firstChapterOrd = outline.bodyChapters.firstOrNull()?.ordinal
                    ?: (outline.frontBackChapters + outline.notesChapters)
                        .minByOrNull { it.ordinal }?.ordinal
                    ?: ChapterOrdinal(1)
                resolvedInitial = adaptAnchorForMode(workId, firstChapterOrd, effectiveMode)

                withContext(ioDispatcher) {
                    dao.upsertProgress(
                        ReadingProgressEntity(
                            workId = workId.raw,
                            surfaceMode = effectiveMode.name,
                            cursorJson = encodeAnchor(resolvedInitial),
                            updatedAtMs = System.currentTimeMillis(),
                        ),
                    )
                }
            }

            val existingNotes = withContext(ioDispatcher) {
                dao.getActiveAnnotations(workId.raw).map { it.toDomain() }
            }

            _activeState.value = ReaderSessionState(
                workId = workId,
                mode = effectiveMode,
                currentCursor = resolvedInitial,
            )

            Triple(
                resolvedInitial,
                assetRootForMode(workId, effectiveMode, availability),
                existingNotes,
            )
        }

        startCollectingLocationEvents(workId)
        rendererPort.load(assetRoot, initialAnchor)
        pushStylesToRenderer()
        if (notes.isNotEmpty()) {
            rendererPort.renderDecorations(notes)
        }

        return ReaderSessionHandle(
            workId = workId,
            mode = effectiveMode,
            initialAnchor = initialAnchor,
        )
    }

    override suspend fun switchPdfSurface(
        workId: WorkId,
        targetMode: ReadingSurfaceMode,
    ): Result<Unit> {
        val availability = probeSurfaceAvailability(workId)
        val targetSupported = when (targetMode) {
            ReadingSurfaceMode.REFLOWABLE -> availability.hasReflowable
            ReadingSurfaceMode.PDF_FIXED -> availability.hasPdfFixed
        }
        if (!targetSupported) {
            return Result.failure(
                AppFailureException(
                    AppFailure.UnsupportedDrmOrFormat(
                        "Surface mode $targetMode is not available for ${workId.raw}",
                    ),
                ),
            )
        }

        val (convertedAnchor, assetRoot, notes) = mutex.withLock {
            val saved = withContext(ioDispatcher) { dao.findProgress(workId.raw) }
            val currentOrd = if (_activeState.value.workId == workId) {
                _activeState.value.currentCursor?.ordinal
            } else {
                saved?.let { decodeAnchor(it.cursorJson).ordinal }
            } ?: ChapterOrdinal(1)

            val newAnchor = adaptAnchorForMode(workId, currentOrd, targetMode)
            withContext(ioDispatcher) {
                dao.upsertProgress(
                    ReadingProgressEntity(
                        workId = workId.raw,
                        surfaceMode = targetMode.name,
                        cursorJson = encodeAnchor(newAnchor),
                        updatedAtMs = System.currentTimeMillis(),
                    ),
                )
            }

            if (_activeState.value.workId == workId) {
                _activeState.value = _activeState.value.copy(
                    mode = targetMode,
                    currentCursor = newAnchor,
                )
            }
            val activeNotes = withContext(ioDispatcher) {
                dao.getActiveAnnotations(workId.raw).map { it.toDomain() }
            }
            Triple(newAnchor, assetRootForMode(workId, targetMode, availability), activeNotes)
        }

        if (_activeState.value.workId == workId) {
            rendererPort.load(assetRoot, convertedAnchor)
            pushStylesToRenderer()
            if (notes.isNotEmpty()) {
                rendererPort.renderDecorations(notes)
            }
        }
        return Result.success(Unit)
    }

    override suspend fun goTo(anchor: ReadingAnchor, highlightPulseMs: Long) {
        val activeWorkId = _activeState.value.workId ?: return
        applyCursorObservation(activeWorkId, anchor)
        if (highlightPulseMs > 0L && rendererPort is app.banread.reader.FoliateWebAdapter) {
            rendererPort.navigateWithPulse(anchor, highlightPulseMs)
        } else {
            rendererPort.navigate(anchor)
        }
    }

    override suspend fun applyTypography(profile: TypographyProfile) {
        currentTypography = profile
        pushStylesToRenderer()
    }

    override suspend fun applyTheme(theme: ReaderTheme) {
        currentTheme = theme
        pushStylesToRenderer()
    }

    override suspend fun saveAnnotation(
        workId: WorkId,
        anchor: ReadingAnchor,
        colorToken: String,
        comment: String,
    ) {
        val now = System.currentTimeMillis()
        val entity = AnnotationEntity(
            noteId = UUID.randomUUID().toString(),
            workId = workId.raw,
            deviceId = deviceId.raw,
            anchorJson = encodeAnchor(anchor),
            colorToken = colorToken,
            comment = comment,
            updatedAtMs = now,
            deletedAtMs = null,
        )
        val notes = withContext(ioDispatcher) {
            dao.upsertAnnotation(entity)
            dao.getActiveAnnotations(workId.raw).map { it.toDomain() }
        }
        rendererPort.renderDecorations(notes)
    }

    override suspend fun snapshotProgress(workId: WorkId): DeviceProgressShard? = mutex.withLock {
        val current = _activeState.value
        val saved = withContext(ioDispatcher) { dao.findProgress(workId.raw) }
        if (current.workId == workId && current.currentCursor != null) {
            val ts = saved?.updatedAtMs ?: System.currentTimeMillis()
            return@withLock DeviceProgressShard(
                workId = workId,
                deviceId = deviceId,
                cursor = current.currentCursor!!,
                updatedAtEpochMs = ts,
            )
        }
        if (saved == null) return@withLock null
        return@withLock DeviceProgressShard(
            workId = workId,
            deviceId = deviceId,
            cursor = decodeAnchor(saved.cursorJson),
            updatedAtEpochMs = saved.updatedAtMs,
        )
    }

    override suspend fun listNoteLogEntries(workId: WorkId, includeDeleted: Boolean): List<NoteLogEntry> =
        withContext(ioDispatcher) {
            val rows = if (includeDeleted) {
                dao.getAllAnnotations(workId.raw)
            } else {
                dao.getActiveAnnotations(workId.raw)
            }
            rows.map { it.toDomain() }
        }

    override suspend fun mergeRemoteAnnotations(workId: WorkId, entries: List<NoteLogEntry>): Int {
        if (entries.isEmpty()) {
            return withContext(ioDispatcher) { dao.getActiveAnnotations(workId.raw).size }
        }
        val activeNotes = mutex.withLock {
            withContext(ioDispatcher) {
                for (remote in entries) {
                    val existing = dao.findAnnotation(workId.raw, remote.noteId)
                    val shouldApply = when {
                        existing == null -> true
                        remote.updatedAtEpochMs > existing.updatedAtMs -> true
                        remote.updatedAtEpochMs < existing.updatedAtMs -> false
                        remote.deletedAtEpochMs != null && existing.deletedAtMs == null -> true
                        remote.deletedAtEpochMs == null && existing.deletedAtMs != null -> false
                        else -> remote.deviceId.raw > existing.deviceId
                    }
                    if (shouldApply) {
                        dao.upsertAnnotation(
                            AnnotationEntity(
                                noteId = remote.noteId,
                                workId = workId.raw,
                                deviceId = remote.deviceId.raw,
                                anchorJson = encodeAnchor(remote.anchor),
                                colorToken = remote.colorToken,
                                comment = remote.comment,
                                updatedAtMs = remote.updatedAtEpochMs,
                                deletedAtMs = remote.deletedAtEpochMs,
                            ),
                        )
                    }
                }
                dao.getActiveAnnotations(workId.raw).map { it.toDomain() }
            }
        }
        if (_activeState.value.workId == workId) {
            rendererPort.renderDecorations(activeNotes)
        }
        return activeNotes.size
    }

    override suspend fun deleteAnnotation(workId: WorkId, noteId: String) {
        val activeNotes = mutex.withLock {
            val now = System.currentTimeMillis()
            withContext(ioDispatcher) {
                val existing = dao.findAnnotation(workId.raw, noteId)
                if (existing != null && existing.deletedAtMs == null) {
                    dao.upsertAnnotation(
                        existing.copy(
                            deviceId = deviceId.raw,
                            updatedAtMs = maxOf(now, existing.updatedAtMs + 1L),
                            deletedAtMs = now,
                        ),
                    )
                }
                dao.getActiveAnnotations(workId.raw).map { it.toDomain() }
            }
        }
        if (_activeState.value.workId == workId) {
            rendererPort.renderDecorations(activeNotes)
        }
    }

    override suspend fun migrateWorkState(fromWorkId: WorkId, toWorkId: WorkId) {
        if (fromWorkId == toWorkId) return
        mutex.withLock {
            withContext(ioDispatcher) {
                val oldProgress = dao.findProgress(fromWorkId.raw)
                val targetProgress = dao.findProgress(toWorkId.raw)
                if (oldProgress != null) {
                    val mergedProgress = if (targetProgress == null || oldProgress.updatedAtMs >= targetProgress.updatedAtMs) {
                        oldProgress.copy(workId = toWorkId.raw)
                    } else {
                        targetProgress
                    }
                    dao.upsertProgress(mergedProgress)
                    dao.deleteProgress(fromWorkId.raw)
                }
                val oldNotes = dao.getAllAnnotations(fromWorkId.raw)
                for (note in oldNotes) {
                    val existingTarget = dao.findAnnotation(toWorkId.raw, note.noteId)
                    if (existingTarget == null || note.updatedAtMs > existingTarget.updatedAtMs) {
                        dao.upsertAnnotation(note.copy(workId = toWorkId.raw))
                    }
                }
                if (oldNotes.isNotEmpty()) {
                    dao.reassignAnnotationsWorkId(fromWorkId.raw, toWorkId.raw)
                }
            }
        }
    }

    private fun startCollectingLocationEvents(workId: WorkId) {
        locationCollectorJob?.cancel()
        locationCollectorJob = engineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            rendererPort.locationEvents.collect { observedAnchor ->
                applyCursorObservation(workId, observedAnchor)
            }
        }
    }

    private suspend fun applyCursorObservation(workId: WorkId, anchor: ReadingAnchor) {
        mutex.withLock {
            val current = _activeState.value
            val saved = withContext(ioDispatcher) { dao.findProgress(workId.raw) }

            val effectiveMode = current.takeIf { it.workId == workId }?.mode
                ?: saved?.surfaceMode?.let { runCatching { ReadingSurfaceMode.valueOf(it) }.getOrNull() }
                ?: ReadingSurfaceMode.REFLOWABLE

            withContext(ioDispatcher) {
                dao.upsertProgress(
                    ReadingProgressEntity(
                        workId = workId.raw,
                        surfaceMode = effectiveMode.name,
                        cursorJson = encodeAnchor(anchor),
                        updatedAtMs = System.currentTimeMillis(),
                    ),
                )
            }

            if (current.workId == workId) {
                _activeState.value = current.copy(
                    currentCursor = anchor,
                )
            }
        }
    }

    private suspend fun pushStylesToRenderer() {
        val registeredFonts = withContext(ioDispatcher) {
            dao.listFonts().map { entity ->
                val fileName = File(entity.filePath).name
                RegisteredFont(
                    fontId = entity.fontId,
                    displayName = entity.displayName,
                    cssFontFamily = entity.displayName,
                    assetUrl = "https://appassets.androidplatform.net/user-fonts/$fileName",
                )
            }
        }
        val css = ReaderCssGenerator.generate(
            typography = currentTypography,
            theme = currentTheme,
            alignment = currentAlignment,
            registeredFonts = registeredFonts,
        )
        if (rendererPort is app.banread.reader.FoliateWebAdapter) {
            rendererPort.setThemeContext(currentTheme)
            rendererPort.setHorizontalMarginDp(currentTypography.horizontalMarginDp)
        }
        rendererPort.updateStyles(css)
    }

    override fun close() {
        locationCollectorJob?.cancel()
        engineScope.cancel()
        if (ownsDatabase) {
            db.close()
        }
    }

    private suspend fun adaptAnchorForMode(
        workId: WorkId,
        ordinal: ChapterOrdinal,
        mode: ReadingSurfaceMode,
    ): ReadingAnchor = when (mode) {
        ReadingSurfaceMode.REFLOWABLE -> {
            runCatching {
                libraryVault.resolveAnchor(workId, ordinal, BlockId("B01"))
            }.getOrElse {
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
        ReadingSurfaceMode.PDF_FIXED -> {
            ReadingAnchor.PdfPage(
                ordinal = ordinal,
                pageIndex = (ordinal.value - 1).coerceAtLeast(0),
                normalizedBoxes = emptyList(),
            )
        }
    }

    private fun probeSurfaceAvailability(workId: WorkId): SurfaceAvailability {
        val compiledDir = File(filesRoot, "compiled/${workId.raw}")
        val hasReflowable = File(compiledDir, "OEBPS/content.opf").isFile ||
            File(compiledDir, "META-INF/container.xml").isFile
        val pdfFile = File(filesRoot, "books/${workId.raw}.pdf")
        val hasPdfFixed = pdfFile.isFile
        return SurfaceAvailability(
            hasReflowable = hasReflowable,
            hasPdfFixed = hasPdfFixed,
            pdfFile = pdfFile,
        )
    }

    private fun assetRootForMode(
        workId: WorkId,
        mode: ReadingSurfaceMode,
        availability: SurfaceAvailability,
    ): String = when (mode) {
        ReadingSurfaceMode.REFLOWABLE -> "https://appassets.androidplatform.net/books/${workId.raw}/"
        ReadingSurfaceMode.PDF_FIXED -> availability.pdfFile.absolutePath
    }

    private fun encodeAnchor(anchor: ReadingAnchor): String =
        json.encodeToString(ReadingAnchor.serializer(), anchor)

    private fun decodeAnchor(raw: String): ReadingAnchor =
        json.decodeFromString(ReadingAnchor.serializer(), raw)

    private fun AnnotationEntity.toDomain(): NoteLogEntry = NoteLogEntry(
        noteId = noteId,
        workId = WorkId(workId),
        deviceId = DeviceId(deviceId),
        anchor = decodeAnchor(anchorJson),
        colorToken = colorToken,
        comment = comment,
        updatedAtEpochMs = updatedAtMs,
        deletedAtEpochMs = deletedAtMs,
    )

    private data class SurfaceAvailability(
        val hasReflowable: Boolean,
        val hasPdfFixed: Boolean,
        val pdfFile: File,
    )
}
