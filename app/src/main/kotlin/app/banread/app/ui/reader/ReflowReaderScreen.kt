package app.banread.app.ui.reader

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.banread.app.ui.components.BanReadSurfaceCard
import app.banread.app.ui.components.CompactActionButton
import app.banread.app.ui.components.RemoteCursorBannerCard
import app.banread.app.ui.components.SingleLinePill
import app.banread.app.ui.theme.BanReadPalettes
import app.banread.app.ui.theme.BanReadTheme
import app.banread.model.FootnoteDetail
import app.banread.model.ReaderTheme
import app.banread.model.ReadingAnchor
import app.banread.model.ToolId
import app.banread.reader.FoliateWebAdapter
import app.banread.reader.PaginationMode
import app.banread.reader.TextSelectionEvent
import kotlin.math.hypot

@SuppressLint("ClickableViewAccessibility")
@Composable
fun ReflowReaderScreen(
    viewModel: ReflowReaderViewModel,
    foliateWebAdapter: FoliateWebAdapter,
    onBackToDetail: () -> Unit,
    onOpenCompanion: (
        currentOrdinal: app.banread.model.ChapterOrdinal,
        selectionAnchor: ReadingAnchor?,
        selectedText: String,
        toolId: ToolId?,
    ) -> Unit,
    onReaderChapterChanged: (app.banread.model.ChapterOrdinal) -> Unit = {},
    companionOverlay: @Composable () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val palette = BanReadTheme.palette
    val activeOrdinal = state.sessionState.currentCursor?.ordinal
    LaunchedEffect(activeOrdinal) {
        if (activeOrdinal != null) onReaderChapterChanged(activeOrdinal)
    }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importCustomFont(uri.toString())
        }
    }

    LaunchedEffect(state.statusBanner) {
        val msg = state.statusBanner ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearStatusBanner()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.paperReading),
    ) {
        val readerWebViewRef = remember { java.util.concurrent.atomic.AtomicReference<WebView?>(null) }
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            factory = { ctx ->
                val tapSlop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
                var downX = 0f
                var downY = 0f
                var downTimeMs = 0L
                WebView(ctx).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setOnTouchListener { view, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                downX = event.x
                                downY = event.y
                                downTimeMs = System.currentTimeMillis()
                            }
                            MotionEvent.ACTION_UP -> {
                                val dt = System.currentTimeMillis() - downTimeMs
                                val dist = hypot((event.x - downX).toDouble(), (event.y - downY).toDouble())
                                if (dt <= 240L && dist <= tapSlop && view.width > 0 && view.height > 0) {
                                    val hitType = (view as? WebView)?.hitTestResult?.type
                                    if (hitType == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                                        hitType == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                                    ) {
                                        return@setOnTouchListener false
                                    }
                                    val curState = viewModel.uiState.value
                                    if (curState.activeSelection != null || curState.activeFootnote != null) {
                                        viewModel.onTapCenterZone()
                                        return@setOnTouchListener false
                                    }
                                    val nx = event.x / view.width.toFloat()
                                    val ny = event.y / view.height.toFloat()
                                    when {
                                        nx < 0.30f || (ny > 0.66f && nx < 0.42f) -> viewModel.onPrevPage()
                                        nx > 0.70f || (ny > 0.66f && nx > 0.58f) -> viewModel.onNextPage()
                                        else -> viewModel.onTapCenterZone()
                                    }
                                }
                            }
                        }
                        false
                    }
                    foliateWebAdapter.attachWebView(this)
                    readerWebViewRef.set(this)
                }
            },
            onRelease = {
                foliateWebAdapter.detachWebView()
                readerWebViewRef.getAndSet(null)?.let { webView ->
                    webView.stopLoading()
                    webView.destroy()
                }
            },
        )

        if (!state.hudVisible) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.currentChapterTitle.ifBlank { state.bookTitle },
                    color = palette.ink3,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 14.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.clickable {
                        val ord = state.sessionState.currentCursor?.ordinal
                            ?: app.banread.model.ChapterOrdinal(1)
                        onOpenCompanion(ord, null, "", null)
                    },
                    color = palette.cinnabar,
                    shape = RoundedCornerShape(999.dp),
                    shadowElevation = if (palette.isEink) 0.dp else 4.dp,
                    border = if (palette.isEink) BorderStroke(1.dp, Color.Black) else null,
                ) {
                    Text(
                        text = "✦ 伴读",
                        color = Color.White,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state.preFootnoteReturnAnchor != null) {
                Surface(
                    modifier = Modifier.clickable { viewModel.returnToPreFootnoteLocation() },
                    color = palette.pine,
                    shape = RoundedCornerShape(999.dp),
                    shadowElevation = if (palette.isEink) 0.dp else 3.dp,
                ) {
                    Text(
                        text = "↩ 返回原阅读处",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
            if (state.showReturnToCompanionPill) {
                Surface(
                    modifier = Modifier.clickable {
                        viewModel.clearReturnToCompanionPill()
                        val ord = state.sessionState.currentCursor?.ordinal
                            ?: app.banread.model.ChapterOrdinal(1)
                        onOpenCompanion(ord, null, "", null)
                    },
                    color = palette.indigo,
                    shape = RoundedCornerShape(999.dp),
                    shadowElevation = if (palette.isEink) 0.dp else 3.dp,
                ) {
                    Text(
                        text = "↩ 返回伴读图谱",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }

        val remoteBanner = state.remoteCursorBanner
        if (remoteBanner != null) {
            RemoteCursorBannerCard(
                message = remoteBanner.message,
                onJump = viewModel::jumpToRemoteCursor,
                onIgnore = viewModel::dismissRemoteCursorBanner,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = if (state.hudVisible) 56.dp else 30.dp, start = 12.dp, end = 12.dp),
            )
        }

        val activeSel = state.activeSelection
        if (activeSel != null) {
            SelectionFloatingActionBar(
                selection = activeSel,
                onHighlightColor = viewModel::highlightSelection,
                onNoteClick = viewModel::openNoteComposer,
                onAiAction = { toolId ->
                    val ord = activeSel.anchor.ordinal
                    val text = activeSel.selectedText
                    viewModel.clearSelection()
                    onOpenCompanion(ord, activeSel.anchor, text, toolId)
                },
                onCopyClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("banread_quote", activeSel.selectedText))
                    viewModel.clearSelection()
                },
                onDismiss = viewModel::clearSelection,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 48.dp, start = 12.dp, end = 12.dp),
            )
        }

        val activeFn = state.activeFootnote
        if (activeFn != null) {
            FootnotePopoverCard(
                detail = activeFn,
                onJumpFullNote = viewModel::jumpToFullFootnoteNote,
                onAskAi = {
                    val ord = activeFn.link.sourceChapterOrdinal
                    val query = "原注 [${activeFn.link.marker}]：${activeFn.fullNoteText}"
                    viewModel.dismissFootnotePopover()
                    onOpenCompanion(ord, state.sessionState.currentCursor, query, ToolId("explain"))
                },
                onDismiss = viewModel::dismissFootnotePopover,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 56.dp),
            )
        }

        AnimatedVisibility(
            visible = state.hudVisible,
            enter = fadeIn(animationSpec = palette.tweenSpec()),
            exit = fadeOut(animationSpec = palette.tweenSpec()),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopHud(
                title = state.currentChapterTitle.ifBlank { state.bookTitle },
                onBack = onBackToDetail,
            )
        }

        AnimatedVisibility(
            visible = state.hudVisible,
            enter = fadeIn(animationSpec = palette.tweenSpec()),
            exit = fadeOut(animationSpec = palette.tweenSpec()),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (state.typographySheetOpen) {
                    TypographySheet(
                        state = state,
                        onSelectTheme = viewModel::applyTheme,
                        onUpdateTypography = viewModel::updateTypography,
                        onSelectPagination = viewModel::setPaginationMode,
                        onToggleVolumeTurn = viewModel::setVolumeKeyPageTurn,
                        onPickCustomFont = {
                            fontPickerLauncher.launch(arrayOf("font/*", "application/octet-stream", "*/*"))
                        },
                        onClose = viewModel::dismissTypographySheet,
                    )
                }
                ReaderBottomHud(
                    typographyOpen = state.typographySheetOpen,
                    onBackToToc = onBackToDetail,
                    onToggleTypography = viewModel::toggleTypographySheet,
                    onCycleTheme = {
                        val all = ReaderTheme.entries
                        val next = all[(all.indexOf(state.theme) + 1) % all.size]
                        viewModel.applyTheme(next)
                    },
                    onOpenCompanion = {
                        val ord = state.sessionState.currentCursor?.ordinal
                            ?: app.banread.model.ChapterOrdinal(1)
                        onOpenCompanion(ord, null, "", null)
                    },
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 68.dp),
        )

        companionOverlay()
    }

    val noteSel = state.activeSelection
    if (state.showNoteComposerForSelection && noteSel != null) {
        SelectionNoteDialog(
            excerpt = noteSel.selectedText,
            onSave = viewModel::saveSelectionNote,
            onDismiss = viewModel::dismissNoteComposer,
        )
    }
}

@Composable
private fun ReaderTopHud(
    title: String,
    onBack: () -> Unit,
) {
    val palette = BanReadTheme.palette
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = palette.surface.copy(alpha = 0.96f),
        shadowElevation = if (palette.isEink) 0.dp else 3.dp,
    ) {
        Column(modifier = Modifier.statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回目录",
                        tint = palette.ink,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = title,
                    color = palette.ink,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider(thickness = palette.borderWidth, color = palette.line)
        }
    }
}

@Composable
private fun ReaderBottomHud(
    typographyOpen: Boolean,
    onBackToToc: () -> Unit,
    onToggleTypography: () -> Unit,
    onCycleTheme: () -> Unit,
    onOpenCompanion: () -> Unit,
) {
    val palette = BanReadTheme.palette
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = palette.surface.copy(alpha = 0.96f),
        shadowElevation = if (palette.isEink) 0.dp else 4.dp,
    ) {
        Column {
            HorizontalDivider(thickness = palette.borderWidth, color = palette.line)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactActionButton(label = "目录", onClick = onBackToToc)
                CompactActionButton(
                    label = "Aa 排版",
                    primary = typographyOpen,
                    onClick = onToggleTypography,
                )
                CompactActionButton(label = "主题", onClick = onCycleTheme)
                CompactActionButton(
                    label = "✦ 伴读",
                    primary = true,
                    onClick = onOpenCompanion,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypographySheet(
    state: ReflowReaderUiState,
    onSelectTheme: (ReaderTheme) -> Unit,
    onUpdateTypography: (app.banread.model.TypographyProfile) -> Unit,
    onSelectPagination: (PaginationMode) -> Unit,
    onToggleVolumeTurn: (Boolean) -> Unit,
    onPickCustomFont: () -> Unit,
    onClose: () -> Unit,
) {
    val palette = BanReadTheme.palette
    val typo = state.typography
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = palette.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(palette.borderWidth, palette.lineStrong),
        shadowElevation = if (palette.isEink) 0.dp else 6.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Aa 排版与主题设置（零重载实时生效）",
                    color = palette.ink,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.5.sp,
                )
                IconButton(onClick = onClose, modifier = Modifier.size(26.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭排版面板",
                        tint = palette.ink2,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ReaderTheme.entries.forEach { themeOption ->
                    val pal = BanReadPalettes.forTheme(themeOption)
                    val selected = state.theme == themeOption
                    Surface(
                        modifier = Modifier.clickable { onSelectTheme(themeOption) },
                        color = if (selected) palette.cinnabarSoft else palette.surfaceSubtle,
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) palette.cinnabar else palette.lineStrong,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(pal.paperReading)
                                    .border(1.dp, pal.ink, CircleShape),
                            )
                            Text(
                                text = pal.labelZh,
                                color = palette.ink,
                                fontSize = 11.5.sp,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }

            TypoSliderRow(
                label = "字号 ${typo.fontSizeSp.toInt()}sp",
                value = typo.fontSizeSp,
                range = 12f..36f,
                onValueChange = { onUpdateTypography(typo.copy(fontSizeSp = it)) },
            )
            TypoSliderRow(
                label = "行高 ${"%.2f".format(typo.lineHeightMultiplier)}",
                value = typo.lineHeightMultiplier,
                range = 1.2f..2.4f,
                onValueChange = { onUpdateTypography(typo.copy(lineHeightMultiplier = it)) },
            )
            TypoSliderRow(
                label = "段距 ${typo.paragraphSpacingDp.toInt()}dp",
                value = typo.paragraphSpacingDp,
                range = 0f..28f,
                onValueChange = { onUpdateTypography(typo.copy(paragraphSpacingDp = it)) },
            )
            TypoSliderRow(
                label = "页边距 ${typo.horizontalMarginDp.toInt()}dp",
                value = typo.horizontalMarginDp,
                range = 8f..40f,
                onValueChange = { onUpdateTypography(typo.copy(horizontalMarginDp = it)) },
            )

            Text("字体", color = palette.ink2, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val builtinFonts = listOf(
                    "Noto Serif CJK SC" to "思源宋体",
                    "LXGW WenKai" to "霞鹜文楷",
                    "Noto Sans CJK SC" to "现代黑体",
                )
                builtinFonts.forEach { (family, label) ->
                    val selected = typo.fontFamily == family
                    SingleLinePill(
                        text = label,
                        containerColor = if (selected) palette.cinnabar else palette.surfaceSubtle,
                        contentColor = if (selected) Color.White else palette.ink,
                        borderColor = if (selected) palette.cinnabar else palette.lineStrong,
                        onClick = { onUpdateTypography(typo.copy(fontFamily = family)) },
                    )
                }
                state.registeredFonts.forEach { reg ->
                    val selected = typo.fontFamily == reg.cssFontFamily
                    SingleLinePill(
                        text = reg.displayName,
                        containerColor = if (selected) palette.cinnabar else palette.surfaceSubtle,
                        contentColor = if (selected) Color.White else palette.ink,
                        borderColor = if (selected) palette.cinnabar else palette.lineStrong,
                        onClick = { onUpdateTypography(typo.copy(fontFamily = reg.cssFontFamily)) },
                    )
                }
                SingleLinePill(
                    text = "+ 导入 .ttf/.otf",
                    containerColor = palette.surface,
                    contentColor = palette.cinnabar,
                    borderColor = palette.cinnabar,
                    onClick = onPickCustomFont,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("CJK 标点挤压", color = palette.ink, fontSize = 12.5.sp)
                Switch(
                    checked = typo.squeezeChinesePunctuation,
                    onCheckedChange = { onUpdateTypography(typo.copy(squeezeChinesePunctuation = it)) },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("音量键翻页", color = palette.ink, fontSize = 12.5.sp)
                Switch(
                    checked = state.volumeKeyPageTurn,
                    onCheckedChange = onToggleVolumeTurn,
                )
            }

            Text("翻页动画", color = palette.ink2, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val modes = if (state.theme == ReaderTheme.EINK_HIGH_CONTRAST) {
                    listOf(PaginationMode.NONE to "无动画（墨水屏锁定）")
                } else {
                    listOf(
                        PaginationMode.SLIDE to "滑动",
                        PaginationMode.COVER to "覆盖",
                        PaginationMode.NONE to "无动画",
                        PaginationMode.VERTICAL_SCROLL to "滚屏",
                    )
                }
                modes.forEach { (mode, label) ->
                    val selected = state.paginationMode == mode
                    SingleLinePill(
                        text = label,
                        containerColor = if (selected) palette.cinnabar else palette.surfaceSubtle,
                        contentColor = if (selected) Color.White else palette.ink,
                        borderColor = if (selected) palette.cinnabar else palette.lineStrong,
                        onClick = { onSelectPagination(mode) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TypoSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    val palette = BanReadTheme.palette
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = label,
            color = palette.ink2,
            fontSize = 12.sp,
            modifier = Modifier.width(88.dp),
            maxLines = 1,
            softWrap = false,
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = palette.cinnabar,
                activeTrackColor = palette.cinnabar,
                inactiveTrackColor = palette.line,
            ),
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionFloatingActionBar(
    selection: TextSelectionEvent,
    onHighlightColor: (String) -> Unit,
    onNoteClick: () -> Unit,
    onAiAction: (ToolId) -> Unit,
    onCopyClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BanReadTheme.palette
    val colors = listOf(
        "YELLOW" to Color(0xFFFDE047),
        "GREEN" to Color(0xFF86EFAC),
        "BLUE" to Color(0xFF93C5FD),
        "PINK" to Color(0xFFF9A8D4),
        "PURPLE" to Color(0xFFD8B4FE),
    )
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = palette.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(palette.borderWidth, palette.lineStrong),
        shadowElevation = if (palette.isEink) 0.dp else 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    colors.forEach { (token, swatch) ->
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(swatch)
                                .border(1.dp, palette.ink3, CircleShape)
                                .clickable { onHighlightColor(token) },
                        )
                    }
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(22.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "取消划词",
                        tint = palette.ink3,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SingleLinePill(text = "笔记", onClick = onNoteClick)
                SingleLinePill(
                    text = "✦ 细读",
                    containerColor = palette.cinnabarSoft,
                    contentColor = palette.cinnabar,
                    borderColor = palette.cinnabar,
                    onClick = { onAiAction(ToolId("critique")) },
                )
                SingleLinePill(
                    text = "✦ 解释",
                    containerColor = palette.cinnabarSoft,
                    contentColor = palette.cinnabar,
                    borderColor = palette.cinnabar,
                    onClick = { onAiAction(ToolId("explain")) },
                )
                SingleLinePill(
                    text = "✦ 关联",
                    containerColor = palette.cinnabarSoft,
                    contentColor = palette.cinnabar,
                    borderColor = palette.cinnabar,
                    onClick = { onAiAction(ToolId("diagram")) },
                )
                SingleLinePill(text = "复制", onClick = onCopyClick)
            }
            Text(
                text = "“${selection.selectedText.take(48)}${if (selection.selectedText.length > 48) "…" else ""}”",
                color = palette.ink3,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FootnotePopoverCard(
    detail: FootnoteDetail,
    onJumpFullNote: () -> Unit,
    onAskAi: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BanReadTheme.palette
    BanReadSurfaceCard(
        modifier = modifier,
        highlighted = true,
        padding = PaddingValues(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "原注 ${detail.link.marker} · 原地查看",
                color = palette.cinnabar,
                fontWeight = FontWeight.Bold,
                fontSize = 12.5.sp,
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭脚注",
                    tint = palette.ink3,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = detail.fullNoteText,
            color = palette.ink,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        if (detail.targetChapterTitle.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "出处：${detail.targetChapterTitle}",
                color = palette.ink3,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompactActionButton(
                label = "跳转原注",
                onClick = onJumpFullNote,
                modifier = Modifier.weight(1f),
            )
            CompactActionButton(
                label = "✦ AI 延伸解释",
                primary = true,
                onClick = onAskAi,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SelectionNoteDialog(
    excerpt: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = BanReadTheme.palette
    var noteText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = { Text("添加阅读笔记", color = palette.ink, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "摘录：“${excerpt.take(80)}${if (excerpt.length > 80) "…" else ""}”",
                    color = palette.ink3,
                    fontSize = 12.sp,
                )
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("笔记内容") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(noteText) },
                enabled = noteText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.cinnabar,
                    contentColor = Color.White,
                ),
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = palette.ink2)
            }
        },
    )
}
