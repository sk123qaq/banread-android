package app.banread.app.ui.companion

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.banread.app.reader.VizHostController
import app.banread.app.ui.components.BanReadSeal
import app.banread.app.ui.components.BanReadSurfaceCard
import app.banread.app.ui.components.ClickableBlockMarkdownText
import app.banread.app.ui.components.CompactActionButton
import app.banread.app.ui.components.PerspectivePickerDialog
import app.banread.app.ui.components.SingleLinePill
import app.banread.app.ui.components.formatProfileBadge
import app.banread.app.ui.theme.BanReadTheme
import app.banread.model.AppFailure
import app.banread.model.AuditedArtifact
import app.banread.model.BlockId
import app.banread.model.ExtItem
import app.banread.model.Provenance
import kotlinx.coroutines.launch

@Composable
fun CompanionSheetOverlay(
    viewModel: CompanionViewModel,
    vizHostController: VizHostController,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val palette = BanReadTheme.palette

    LaunchedEffect(palette.readerTheme) {
        viewModel.setTheme(palette.readerTheme)
    }

    if (state.detent == CompanionDetent.CLOSED) return

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        val density = LocalDensity.current
        val totalHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val minSheetPx = totalHeightPx * 0.18f
        val maxSheetPx = totalHeightPx * 0.94f

        val targetFraction = when (state.detent) {
            CompanionDetent.CLOSED -> 0f
            CompanionDetent.PEEK -> 0.22f
            CompanionDetent.HALF -> 0.56f
            CompanionDetent.FULL -> 0.92f
        }
        var isDragging by remember { mutableStateOf(false) }
        var dragHeightPx by remember { mutableFloatStateOf(totalHeightPx * targetFraction) }

        LaunchedEffect(state.detent, totalHeightPx) {
            if (!isDragging) {
                dragHeightPx = (totalHeightPx * targetFraction).coerceIn(minSheetPx, maxSheetPx)
            }
        }

        val targetDp = with(density) {
            (if (isDragging) dragHeightPx else totalHeightPx * targetFraction)
                .coerceIn(minSheetPx, maxSheetPx)
                .toDp()
        }
        val animatedHeight by animateDpAsState(
            targetValue = targetDp,
            animationSpec = palette.tweenSpec(),
            label = "companion_sheet_height",
        )

        if (state.detent == CompanionDetent.HALF || state.detent == CompanionDetent.FULL) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (palette.isEink) 0.12f else 0.24f))
                    .clickable { viewModel.setDetent(CompanionDetent.PEEK) },
            )
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(animatedHeight),
            color = palette.paper,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            border = BorderStroke(palette.borderWidth, palette.lineStrong),
            shadowElevation = if (palette.isEink) 0.dp else 10.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                CompanionDragHandleHeader(
                    state = state,
                    onDragStart = {
                        isDragging = true
                        dragHeightPx = totalHeightPx * targetFraction
                    },
                    onDragDelta = { dy ->
                        dragHeightPx = (dragHeightPx - dy).coerceIn(minSheetPx, maxSheetPx)
                    },
                    onDragEnd = {
                        isDragging = false
                        val ratio = dragHeightPx / totalHeightPx
                        val nextDetent = when {
                            ratio < 0.36f -> CompanionDetent.PEEK
                            ratio < 0.74f -> CompanionDetent.HALF
                            else -> CompanionDetent.FULL
                        }
                        viewModel.setDetent(nextDetent)
                    },
                    onPerspectiveClick = viewModel::openPerspectivePicker,
                    onSnapClick = viewModel::setDetent,
                    onCloseClick = { viewModel.setDetent(CompanionDetent.CLOSED) },
                )

                HorizontalDivider(thickness = palette.borderWidth, color = palette.line)

                if (state.detent != CompanionDetent.PEEK) {
                    CompanionScopeStrip(
                        state = state,
                        onSelectScope = viewModel::selectScope,
                    )

                    CompanionToolStrip(
                        tools = state.tools,
                        activeToolId = state.activeToolId,
                        onSelectTool = { viewModel.selectTool(it) },
                    )

                    HorizontalDivider(thickness = palette.borderWidth, color = palette.line)

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CompanionStageBody(
                            state = state,
                            vizHostController = vizHostController,
                            onJumpToBlock = viewModel::jumpToBlock,
                            onOpenFullscreen = { viewModel.setFullscreenVizOpen(true) },
                            onRetryTool = { viewModel.selectTool(state.activeToolId, forceRefresh = true) },
                        )

                        state.qaMessages.forEach { msg ->
                            QaTurnCard(
                                message = msg,
                                onJumpToBlock = viewModel::jumpToBlock,
                            )
                        }
                    }

                    CompanionQaInputBar(
                        onSubmit = viewModel::askFollowUp,
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .clickable { viewModel.setDetent(CompanionDetent.HALF) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val toolLabel = state.tools.firstOrNull { it.toolId == state.activeToolId }?.label ?: "伴读"
                        Text(
                            text = "当前：$toolLabel · 点击或上拉展开伴读面板",
                            color = palette.ink2,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "展开 ↑",
                            color = palette.cinnabar,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }

    if (state.showPerspectivePicker && state.activeProfile != null) {
        PerspectivePickerDialog(
            profiles = state.allProfiles,
            currentProfileId = state.activeProfile!!.readType,
            currentSubKind = state.literatureSubKind,
            literatureSubKindHints = state.literatureSubKindHints,
            onSelect = { pId, sub -> viewModel.switchPerspective(pId, sub) },
            onDismiss = viewModel::closePerspectivePicker,
        )
    }

    if (state.isFullscreenVizOpen) {
        FullscreenVizDialog(
            title = state.activeArtifact?.caption ?: "全屏图解",
            vizHostController = vizHostController,
            onDismiss = { viewModel.setFullscreenVizOpen(false) },
        )
    }
}

@Composable
private fun CompanionDragHandleHeader(
    state: CompanionUiState,
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onPerspectiveClick: () -> Unit,
    onSnapClick: (CompanionDetent) -> Unit,
    onCloseClick: () -> Unit,
) {
    val palette = BanReadTheme.palette
    val profile = state.activeProfile
    val badgeLabel = if (profile != null) {
        formatProfileBadge(profile.name, profile.readType, state.literatureSubKind)
    } else {
        "伴读"
    }
    val latestOnDragStart by rememberUpdatedState(onDragStart)
    val latestOnDragDelta by rememberUpdatedState(onDragDelta)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surface)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { latestOnDragStart() },
                    onVerticalDrag = { _, dragAmount -> latestOnDragDelta(dragAmount) },
                    onDragEnd = { latestOnDragEnd() },
                    onDragCancel = { latestOnDragEnd() },
                )
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(palette.lineStrong),
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                BanReadSeal(size = 20.dp)
                SingleLinePill(
                    text = "$badgeLabel ▾",
                    containerColor = palette.cinnabarSoft,
                    contentColor = palette.cinnabar,
                    borderColor = palette.cinnabar,
                    onClick = onPerspectiveClick,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(
                    CompanionDetent.PEEK to "20%",
                    CompanionDetent.HALF to "55%",
                    CompanionDetent.FULL to "92%",
                ).forEach { (detent, label) ->
                    val active = state.detent == detent
                    SingleLinePill(
                        text = label,
                        containerColor = if (active) palette.ink else palette.surfaceSubtle,
                        contentColor = if (active) palette.surface else palette.ink2,
                        borderColor = if (active) palette.ink else palette.line,
                        onClick = { onSnapClick(detent) },
                    )
                }
                IconButton(onClick = onCloseClick, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "收起伴读抽屉",
                        tint = palette.ink2,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompanionScopeStrip(
    state: CompanionUiState,
    onSelectScope: (CompanionScopeChoice) -> Unit,
) {
    val palette = BanReadTheme.palette
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surfaceSubtle)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CompanionScopeChoice.entries.forEach { choice ->
            val selected = state.scopeChoice == choice
            SingleLinePill(
                text = choice.labelZh,
                containerColor = if (selected) palette.cinnabar else palette.surface,
                contentColor = if (selected) Color.White else palette.ink,
                borderColor = if (selected) palette.cinnabar else palette.lineStrong,
                onClick = { onSelectScope(choice) },
            )
        }
    }
}

@Composable
private fun CompanionToolStrip(
    tools: List<CompanionToolItem>,
    activeToolId: app.banread.model.ToolId,
    onSelectTool: (app.banread.model.ToolId) -> Unit,
) {
    val palette = BanReadTheme.palette
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surface)
            .padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(tools, key = { it.toolId.raw }) { tool ->
            val selected = tool.toolId == activeToolId
            val displayLabel = if (tool.isCrossSearch) "🔒 ${tool.label}" else tool.label
            SingleLinePill(
                text = displayLabel,
                containerColor = when {
                    selected -> palette.cinnabar
                    tool.isCrossSearch -> palette.surfaceSubtle
                    else -> palette.surface
                },
                contentColor = when {
                    selected -> Color.White
                    tool.isCrossSearch -> palette.ink3
                    else -> palette.ink
                },
                borderColor = if (selected) palette.cinnabar else palette.lineStrong,
                onClick = { onSelectTool(tool.toolId) },
            )
        }
    }
}

@Composable
private fun CompanionStageBody(
    state: CompanionUiState,
    vizHostController: VizHostController,
    onJumpToBlock: (BlockId) -> Unit,
    onOpenFullscreen: () -> Unit,
    onRetryTool: () -> Unit,
) {
    val palette = BanReadTheme.palette
    val vizViewportHeight = remember(state.vizContentHeightDp) {
        val reported = state.vizContentHeightDp
        if (reported <= 0) 280.dp else reported.dp.coerceAtLeast(200.dp)
    }

    if (state.streamingThinking.isNotBlank()) {
        ThinkingCollapsibleCard(thinkingText = state.streamingThinking)
    }

    val failure = state.toolFailure
    if (failure != null) {
        when (failure) {
            is AppFailure.DesktopConnectionRequired -> {
                BanReadSurfaceCard(highlighted = false) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = palette.amber,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "需连接桌面网关",
                            color = palette.ink,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "「${failure.featureName}」属于跨书全库检索工具，需要连接桌面伴读网关后方可使用（当前离线拦截，消耗 0 LLM Token）。离线状态下您仍可查看本地单书图谱、大纲与自由问答。",
                        color = palette.ink2,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
            else -> {
                BanReadSurfaceCard {
                    Text(
                        text = "伴读生成未完成",
                        color = palette.danger,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = when (failure) {
                            is AppFailure.ProviderAuthOrQuota -> failure.message
                            is AppFailure.CapacityPreflightBlocked -> failure.reason
                            is AppFailure.UnsupportedDrmOrFormat -> failure.reason
                            is AppFailure.ScannedPdfNeedsOcr -> "扫描版 PDF 需先执行 OCR 或使用框选图问"
                            is AppFailure.NetworkOrSyncConflict -> failure.message
                            else -> failure.toString()
                        },
                        color = palette.ink2,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    CompactActionButton(label = "重试", onClick = onRetryTool)
                }
            }
        }
        return
    }

    if (state.isRunningTool) {
        BanReadSurfaceCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = palette.cinnabar,
                )
                Text(
                    text = "正在生成伴读内容…",
                    color = palette.ink2,
                    fontSize = 12.5.sp,
                )
            }
            if (state.streamingMarkdown.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                ClickableBlockMarkdownText(
                    markdown = state.streamingMarkdown,
                    onBlockJump = onJumpToBlock,
                )
            }
        }
        return
    }

    when (val artifact = state.activeArtifact) {
        is AuditedArtifact.StructuredDiagram,
        is AuditedArtifact.FallbackList,
        -> {
            BanReadSurfaceCard(padding = PaddingValues(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = artifact.caption,
                        color = palette.ink,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    SingleLinePill(
                        text = "全屏",
                        containerColor = palette.surfaceSubtle,
                        contentColor = palette.cinnabar,
                        borderColor = palette.cinnabar,
                        onClick = onOpenFullscreen,
                    )
                }
                if (artifact.readingNote.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = artifact.readingNote,
                        color = palette.ink3,
                        fontSize = 11.5.sp,
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (!state.isFullscreenVizOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(vizViewportHeight)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, palette.line, RoundedCornerShape(8.dp)),
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                FrameLayout(ctx).apply {
                                    val wv = vizHostController.acquireWebView(ctx)
                                    addView(
                                        wv,
                                        ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                        ),
                                    )
                                }
                            },
                            onRelease = { container ->
                                if (container.childCount > 0) {
                                    vizHostController.detachWebView()
                                }
                            },
                        )
                    }
                }
                if (artifact.summary.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    ClickableBlockMarkdownText(
                        markdown = artifact.summary,
                        onBlockJump = onJumpToBlock,
                    )
                }
            }
        }

        is AuditedArtifact.ExtensionDossier -> {
            ExtensionDossierCard(
                artifact = artifact,
                onJumpToBlock = onJumpToBlock,
            )
        }

        is AuditedArtifact.MarkdownReadCard -> {
            BanReadSurfaceCard {
                Text(
                    text = artifact.caption,
                    color = palette.ink,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
                if (artifact.readingNote.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = artifact.readingNote,
                        color = palette.ink3,
                        fontSize = 11.5.sp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                ClickableBlockMarkdownText(
                    markdown = artifact.markdown,
                    onBlockJump = onJumpToBlock,
                )
            }
        }

        null -> {
            BanReadSurfaceCard {
                Text(
                    text = "选择上方工具或输入追问开始伴读",
                    color = palette.ink3,
                    fontSize = 12.5.sp,
                )
            }
        }
    }
}

@Composable
private fun ThinkingCollapsibleCard(thinkingText: String) {
    val palette = BanReadTheme.palette
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        color = palette.surfaceSubtle,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, palette.line),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "思考过程",
                    color = palette.ink2,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起思考" else "展开思考",
                    tint = palette.ink3,
                    modifier = Modifier.size(16.dp),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = palette.tweenSpec()),
                exit = shrinkVertically(animationSpec = palette.tweenSpec()),
            ) {
                Text(
                    text = thinkingText,
                    color = palette.ink3,
                    fontSize = 11.5.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ExtensionDossierCard(
    artifact: AuditedArtifact.ExtensionDossier,
    onJumpToBlock: (BlockId) -> Unit,
) {
    val palette = BanReadTheme.palette
    BanReadSurfaceCard {
        Text(
            text = artifact.caption,
            color = palette.ink,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            fontSize = 14.5.sp,
        )
        if (artifact.readingNote.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(text = artifact.readingNote, color = palette.ink3, fontSize = 11.5.sp)
        }
        if (artifact.summary.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(text = artifact.summary, color = palette.ink2, fontSize = 12.5.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.height(8.dp))

        artifact.sections.forEach { section ->
            Text(
                text = section.title,
                color = palette.cinnabar,
                fontWeight = FontWeight.Bold,
                fontSize = 12.5.sp,
                modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
            )
            section.items.forEach { item ->
                ExtItemRow(item = item, onJumpToBlock = onJumpToBlock)
                Spacer(Modifier.height(6.dp))
            }
        }

        artifact.items.forEach { item ->
            ExtItemRow(item = item, onJumpToBlock = onJumpToBlock)
            Spacer(Modifier.height(6.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtItemRow(
    item: ExtItem,
    onJumpToBlock: (BlockId) -> Unit,
) {
    val palette = BanReadTheme.palette
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = palette.paper,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, palette.line),
    ) {
        Column(
            modifier = Modifier.padding(9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val heading = item.title.ifBlank { item.text }
                if (heading.isNotBlank()) {
                    Text(
                        text = heading,
                        color = palette.ink,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.5.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
                SingleLinePill(
                    text = "[${item.source.wireValue}]",
                    containerColor = if (item.source == Provenance.SOURCE_TEXT) palette.pineSoft else palette.amberSoft,
                    contentColor = if (item.source == Provenance.SOURCE_TEXT) palette.pine else palette.amber,
                    borderColor = if (item.source == Provenance.SOURCE_TEXT) palette.pine else palette.amber,
                )
            }
            val body = item.detail.ifBlank { item.body }
            if (body.isNotBlank()) {
                Text(text = body, color = palette.ink2, fontSize = 12.sp, lineHeight = 17.5.sp)
            }
            if (item.blocks.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item.blocks.forEach { bid ->
                        SingleLinePill(
                            text = "↗ ${bid.value}",
                            containerColor = palette.cinnabarSoft,
                            contentColor = palette.cinnabar,
                            borderColor = palette.cinnabar,
                            onClick = { onJumpToBlock(bid) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QaTurnCard(
    message: CompanionQaMessage,
    onJumpToBlock: (BlockId) -> Unit,
) {
    val palette = BanReadTheme.palette
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            color = palette.cinnabarSoft,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, palette.cinnabar.copy(alpha = 0.35f)),
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(
                text = message.question,
                color = palette.ink,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }

        if (message.thinkingText.isNotBlank()) {
            ThinkingCollapsibleCard(thinkingText = message.thinkingText)
        }

        BanReadSurfaceCard {
            when {
                message.errorText != null -> {
                    Text(text = message.errorText, color = palette.danger, fontSize = 12.5.sp)
                }
                message.isStreaming && message.answerMarkdown.isBlank() -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = palette.cinnabar,
                        )
                        Text("正在思考回答…", color = palette.ink3, fontSize = 12.sp)
                    }
                }
                else -> {
                    ClickableBlockMarkdownText(
                        markdown = message.answerMarkdown,
                        onBlockJump = onJumpToBlock,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompanionQaInputBar(
    onSubmit: (String) -> Unit,
) {
    val palette = BanReadTheme.palette
    var inputText by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = palette.surface,
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            HorizontalDivider(thickness = palette.borderWidth, color = palette.line)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp),
                    color = palette.paper,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, palette.lineStrong),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (inputText.isEmpty()) {
                            Text(
                                text = "基于当前视角继续追问…",
                                color = palette.ink3,
                                fontSize = 12.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BasicTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            singleLine = true,
                            textStyle = TextStyle(color = palette.ink, fontSize = 12.5.sp),
                            cursorBrush = SolidColor(palette.cinnabar),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                CompactActionButton(
                    label = "发送",
                    primary = true,
                    enabled = inputText.isNotBlank(),
                    onClick = {
                        val q = inputText.trim()
                        if (q.isNotEmpty()) {
                            inputText = ""
                            onSubmit(q)
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun FullscreenVizDialog(
    title: String,
    vizHostController: VizHostController,
    onDismiss: () -> Unit,
) {
    val palette = BanReadTheme.palette
    DisposableEffect(vizHostController) {
        vizHostController.setFullscreenMode(true)
        onDispose {
            vizHostController.setFullscreenMode(false)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = palette.paper,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.surface)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = title,
                        color = palette.ink,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    CompactActionButton(label = "退出全屏", onClick = onDismiss)
                }
                HorizontalDivider(thickness = palette.borderWidth, color = palette.line)
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            val wv = vizHostController.acquireWebView(ctx)
                            addView(
                                wv,
                                ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                ),
                            )
                        }
                    },
                    onRelease = { container ->
                        if (container.childCount > 0) {
                            vizHostController.detachWebView()
                        }
                    },
                )
            }
        }
    }
}
