package app.banread.app.ui.pdf

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.banread.app.reader.computeNormalizedCropRect
import app.banread.app.reader.cropBitmapRegionToPng
import app.banread.app.ui.components.BanReadSurfaceCard
import app.banread.app.ui.components.BanReadTopBar
import app.banread.app.ui.components.ClickableBlockMarkdownText
import app.banread.app.ui.components.CompactActionButton
import app.banread.app.ui.components.SingleLinePill
import app.banread.app.ui.theme.BanReadTheme
import app.banread.model.AppFailure
import app.banread.model.NormalizedRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(
    viewModel: PdfReaderViewModel,
    onBack: () -> Unit,
    onSwitchedToReflow: () -> Unit,
    onOpenSettingsForVision: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val palette = BanReadTheme.palette
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.shouldSwitchToReflowScreen) {
        if (state.shouldSwitchToReflowScreen) {
            viewModel.consumeSwitchToReflowScreen()
            onSwitchedToReflow()
        }
    }

    LaunchedEffect(state.statusBanner) {
        val msg = state.statusBanner ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearStatusBanner()
    }

    Scaffold(
        containerColor = palette.paper,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            BanReadTopBar(
                title = state.title.ifBlank { "PDF 伴读" },
                onBack = onBack,
                actions = {
                    val vp = state.viewportState
                    Text(
                        text = if (vp.pageCount > 0) "${vp.pageIndex + 1}/${vp.pageCount}" else "0/0",
                        color = palette.ink2,
                        fontSize = 12.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                },
            )
        },
        bottomBar = {
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
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CompactActionButton(
                            label = "上一页",
                            enabled = state.viewportState.pageIndex > 0,
                            onClick = viewModel::prevPage,
                        )
                        CompactActionButton(
                            label = if (state.cropModeEnabled) "退出框选" else "✦ 框选图问",
                            primary = state.cropModeEnabled,
                            onClick = viewModel::toggleCropMode,
                        )
                        CompactActionButton(
                            label = "下一页",
                            enabled = state.viewportState.pageIndex + 1 < state.viewportState.pageCount,
                            onClick = viewModel::nextPage,
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Controls strip
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.surfaceSubtle)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SingleLinePill(
                    text = if (state.viewportState.autoCropEnabled) "✓ 自动裁白边" else "自动裁白边",
                    containerColor = if (state.viewportState.autoCropEnabled) palette.pineSoft else palette.surface,
                    contentColor = if (state.viewportState.autoCropEnabled) palette.pine else palette.ink2,
                    borderColor = if (state.viewportState.autoCropEnabled) palette.pine else palette.lineStrong,
                    onClick = viewModel::toggleAutoCrop,
                )
                if (state.canReflow) {
                    SingleLinePill(
                        text = "切换流式重排",
                        containerColor = palette.surface,
                        contentColor = palette.cinnabar,
                        borderColor = palette.cinnabar,
                        onClick = viewModel::switchToTextReflowMode,
                    )
                }
                if (state.isScannedPdf) {
                    SingleLinePill(
                        text = "扫描版 · 拖拽框选 VLM",
                        containerColor = palette.amberSoft,
                        contentColor = palette.amber,
                        borderColor = palette.amber,
                    )
                }
                if (state.viewportState.zoomScale > 1.05f) {
                    SingleLinePill(
                        text = "重置缩放 (${"%.1f".format(state.viewportState.zoomScale)}x)",
                        onClick = { viewModel.setZoomAndPan(1f, 0f, 0f) },
                    )
                }
            }

            // Non-intrusive RemoteCursorBanner
            val remoteBanner = state.remoteCursorBanner
            if (remoteBanner != null) {
                app.banread.app.ui.components.RemoteCursorBannerCard(
                    message = remoteBanner.message,
                    onJump = viewModel::jumpToRemoteCursor,
                    onIgnore = viewModel::dismissRemoteCursorBanner,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            // PDF page stage with pinch-zoom OR live drag-to-crop overlay
            val rendered = state.renderedPage
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(12.dp)
                    .onSizeChanged { size ->
                        if (size.width > 0) {
                            viewModel.onRenderViewportWidth(size.width)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (rendered == null) {
                    CircularProgressIndicator(color = palette.cinnabar)
                } else {
                    val bmp = rendered.bitmap
                    val aspect = bmp.width.toFloat() / maxOf(1, bmp.height).toFloat()
                    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
                    val latestViewport by rememberUpdatedState(state.viewportState)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspect)
                            .border(1.dp, palette.lineStrong, RoundedCornerShape(6.dp))
                            .graphicsLayer {
                                val s = state.viewportState.zoomScale
                                scaleX = s
                                scaleY = s
                                translationX = state.viewportState.panX
                                translationY = state.viewportState.panY
                            }
                            .onSizeChanged { canvasSize = it }
                            .then(
                                if (state.cropModeEnabled) {
                                    Modifier.pointerInput(bmp, canvasSize) {
                                        var localDragStart = Offset.Zero
                                        var latestDragRect: NormalizedRect? = null
                                        detectDragGestures(
                                            onDragStart = { start ->
                                                localDragStart = start
                                                latestDragRect = null
                                                viewModel.setPendingCropRect(null)
                                            },
                                            onDrag = { change, _ ->
                                                change.consume()
                                                val rect = computeNormalizedCropRect(
                                                    startX = localDragStart.x,
                                                    startY = localDragStart.y,
                                                    endX = change.position.x,
                                                    endY = change.position.y,
                                                    canvasWidth = canvasSize.width.toFloat(),
                                                    canvasHeight = canvasSize.height.toFloat(),
                                                )
                                                latestDragRect = rect
                                                viewModel.setPendingCropRect(rect)
                                            },
                                            onDragCancel = {
                                                latestDragRect = null
                                                viewModel.setPendingCropRect(null)
                                            },
                                            onDragEnd = {
                                                val rect = latestDragRect
                                                if (rect != null) {
                                                    scope.launch {
                                                        val pngBytes = withContext(Dispatchers.Default) {
                                                            cropBitmapRegionToPng(bmp, rect)
                                                        }
                                                        viewModel.onCropRegionPngReady(pngBytes)
                                                    }
                                                }
                                            },
                                        )
                                    }
                                } else {
                                    Modifier.pointerInput(Unit) {
                                        detectTransformGestures { _, pan, zoom, _ ->
                                            val vp = latestViewport
                                            val nextScale = (vp.zoomScale * zoom).coerceIn(1f, 5f)
                                            val nextPanX = if (nextScale > 1.02f) vp.panX + pan.x else 0f
                                            val nextPanY = if (nextScale > 1.02f) vp.panY + pan.y else 0f
                                            viewModel.setZoomAndPan(nextScale, nextPanX, nextPanY)
                                        }
                                    }
                                },
                            ),
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "PDF 第 ${rendered.pageIndex + 1} 页",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )

                        // Live dashed cinnabar (#A8442F) crop selection rectangle
                        val pendingRect: NormalizedRect? = state.pendingCropRect
                        if (state.cropModeEnabled && pendingRect != null) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val left = pendingRect.left * size.width
                                val top = pendingRect.top * size.height
                                val w = (pendingRect.right - pendingRect.left) * size.width
                                val h = (pendingRect.bottom - pendingRect.top) * size.height
                                drawRect(
                                    color = Color(0x28A8442F),
                                    topLeft = Offset(left, top),
                                    size = Size(w, h),
                                )
                                drawRect(
                                    color = Color(0xFFA8442F),
                                    topLeft = Offset(left, top),
                                    size = Size(w, h),
                                    style = Stroke(
                                        width = 2.5.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 8f), 0f),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.showVlmPromptDialog) {
        ScannedCropPromptDialog(
            pageIndex = state.viewportState.pageIndex,
            onSubmit = { q -> viewModel.submitScannedRegionQuestion(q) },
            onDismiss = viewModel::clearCropSelection,
        )
    }

    if (state.showVlmResultSheet) {
        ModalBottomSheet(
            onDismissRequest = viewModel::dismissVlmResultSheet,
            containerColor = palette.paper,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "VLM 框选图问 · 第 ${state.viewportState.pageIndex + 1} 页",
                        color = palette.ink,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    IconButton(onClick = viewModel::dismissVlmResultSheet, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭", tint = palette.ink2)
                    }
                }

                if (state.isVlmStreaming) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = palette.cinnabar,
                        )
                        Text("正在调用视觉模型解析框选区域…", color = palette.ink2, fontSize = 12.5.sp)
                    }
                }

                if (state.vlmThinkingText.isNotBlank()) {
                    BanReadSurfaceCard(padding = PaddingValues(10.dp)) {
                        Text("思考过程", color = palette.ink3, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(state.vlmThinkingText, color = palette.ink2, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }

                val vlmErr = state.vlmFailure
                if (vlmErr != null) {
                    BanReadSurfaceCard {
                        val isVisionMissing = vlmErr is AppFailure.ProviderAuthOrQuota &&
                            (vlmErr.message.contains("vision", ignoreCase = true) ||
                                vlmErr.message.contains("No active LLM provider", ignoreCase = true))
                        Text(
                            text = if (isVisionMissing) "尚未配置支持视觉 (Vision) 的默认模型" else "图问请求失败",
                            color = palette.danger,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (isVisionMissing) {
                                "请在「设置中心」添加或将支持 Vision 的模型设为默认伴读模型后重试。"
                            } else {
                                when (vlmErr) {
                                    is AppFailure.ProviderAuthOrQuota -> vlmErr.message
                                    else -> vlmErr.toString()
                                }
                            },
                            color = palette.ink2,
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                        )
                        if (isVisionMissing) {
                            Spacer(Modifier.height(8.dp))
                            CompactActionButton(
                                label = "前往设置配置 Vision 模型",
                                primary = true,
                                onClick = {
                                    viewModel.dismissVlmResultSheet()
                                    onOpenSettingsForVision()
                                },
                            )
                        }
                    }
                }

                if (state.vlmAnswerMarkdown.isNotBlank()) {
                    BanReadSurfaceCard {
                        ClickableBlockMarkdownText(
                            markdown = state.vlmAnswerMarkdown,
                            onBlockJump = {},
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScannedCropPromptDialog(
    pageIndex: Int,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = BanReadTheme.palette
    var question by remember { mutableStateOf("请识别并解释框选区域中的核心内容与公式推导") }
    val quickPrompts = listOf(
        "识别并转写这段文字",
        "解释框选图表的证明/原理",
        "翻译为通顺中文并标注术语",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = {
            Text(
                text = "第 ${pageIndex + 1} 页框选图问 (VLM)",
                color = palette.ink,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    quickPrompts.forEach { preset ->
                        SingleLinePill(
                            text = preset,
                            containerColor = palette.surfaceSubtle,
                            contentColor = palette.ink,
                            borderColor = palette.lineStrong,
                            onClick = { question = preset },
                        )
                    }
                }
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    label = { Text("提问内容") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(question) },
                enabled = question.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.cinnabar,
                    contentColor = Color.White,
                ),
            ) {
                Text("✦ 发送图问")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("重选", color = palette.ink2)
            }
        },
    )
}
