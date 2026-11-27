package app.banread.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.banread.app.ui.components.BanReadBottomNav
import app.banread.app.ui.components.BanReadSurfaceCard
import app.banread.app.ui.components.BanReadTopBar
import app.banread.app.ui.components.CompactActionButton
import app.banread.app.ui.components.IconAdd
import app.banread.app.ui.components.MainTab
import app.banread.app.ui.components.SingleLinePill
import app.banread.app.ui.components.SmallIconPillButton
import app.banread.app.ui.theme.BanReadPalettes
import app.banread.app.ui.theme.BanReadTheme
import app.banread.model.ProviderSummary
import app.banread.model.ReaderTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenBookshelf: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val palette = BanReadTheme.palette
    val snackbarHostState = remember { SnackbarHostState() }

    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importCustomFont(uri.toString())
        }
    }

    // 5-second Undo Snackbar on right-swipe delete or standard status snackbar
    LaunchedEffect(state.pendingUndoSnapshot, state.snackbarMessage) {
        val undoSnap = state.pendingUndoSnapshot
        val msg = state.snackbarMessage ?: return@LaunchedEffect
        if (undoSnap != null) {
            val timeoutJob = launch {
                delay(5_000L)
                snackbarHostState.currentSnackbarData?.dismiss()
            }
            val res = snackbarHostState.showSnackbar(
                message = msg,
                actionLabel = "撤销",
                duration = SnackbarDuration.Indefinite,
            )
            timeoutJob.cancel()
            if (res == SnackbarResult.ActionPerformed) {
                viewModel.undoDeleteProvider()
            } else {
                viewModel.clearUndoSnapshot()
            }
            viewModel.clearSnackbarMessage()
        } else {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbarMessage()
        }
    }

    Scaffold(
        containerColor = palette.paper,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            BanReadTopBar(
                title = "设置中心",
                showSeal = true,
                actions = {
                    SmallIconPillButton(
                        label = "添加模型",
                        icon = IconAdd,
                        onClick = viewModel::openAddByokDialog,
                    )
                },
            )
        },
        bottomBar = {
            BanReadBottomNav(
                activeTab = MainTab.SETTINGS,
                onSelectTab = { tab ->
                    if (tab == MainTab.BOOKSHELF) onOpenBookshelf()
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Section 1: BYOK Providers
            item(key = "byok_header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "BYOK 模型与加密密钥库",
                            color = palette.ink,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                        Text(
                            text = "右滑卡片可移除（5 秒内支持撤销）",
                            color = palette.ink3,
                            fontSize = 11.5.sp,
                        )
                    }
                    SingleLinePill(
                        text = "Tink + Keystore",
                        containerColor = palette.pineSoft,
                        contentColor = palette.pine,
                        borderColor = palette.pine,
                    )
                }
            }

            if (state.providers.isEmpty()) {
                item(key = "byok_empty") {
                    BanReadSurfaceCard {
                        Text(
                            text = "尚未配置 BYOK 大模型",
                            color = palette.ink,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "点击右上角「+ 添加模型」，支持 OpenCode Zen、OpenRouter、SiliconFlow、DeepSeek、OpenAI、Gemini、Anthropic 与本地 Ollama。",
                            color = palette.ink2,
                            fontSize = 12.sp,
                            lineHeight = 17.5.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        CompactActionButton(
                            label = "+ 添加第一个模型",
                            primary = true,
                            onClick = viewModel::openAddByokDialog,
                        )
                    }
                }
            } else {
                items(state.providers, key = { it.id }) { summary ->
                    SwipeToDeleteProviderCard(
                        summary = summary,
                        connectionStatus = state.connectionStatuses[summary.id],
                        isTesting = summary.id in state.testingProviderIds,
                        onSetDefaultCompanion = { viewModel.setDefaultCompanion(summary.id) },
                        onSetDefaultVision = { viewModel.setDefaultVision(summary.id) },
                        onEdit = { viewModel.openEditByokDialog(summary) },
                        onTest = { viewModel.testProviderConnection(summary.id) },
                        onSwipeRemove = { viewModel.deleteProvider(summary.id) },
                    )
                }
            }

            // Section 2: App preferences (5 themes, volume key, custom fonts)
            item(key = "prefs_section") {
                BanReadSurfaceCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "阅读器与全局主题",
                            color = palette.ink,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                        SingleLinePill(
                            text = "+ 导入 .ttf/.otf",
                            containerColor = palette.surfaceSubtle,
                            contentColor = palette.cinnabar,
                            borderColor = palette.cinnabar,
                            onClick = {
                                fontPickerLauncher.launch(arrayOf("font/*", "application/octet-stream", "*/*"))
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ReaderTheme.entries.forEach { themeOption ->
                            val pal = BanReadPalettes.forTheme(themeOption)
                            val selected = state.theme == themeOption
                            SingleLinePill(
                                text = pal.labelZh,
                                containerColor = if (selected) palette.cinnabar else palette.surfaceSubtle,
                                contentColor = if (selected) Color.White else palette.ink,
                                borderColor = if (selected) palette.cinnabar else palette.lineStrong,
                                onClick = { viewModel.selectTheme(themeOption) },
                            )
                        }
                    }
                    if (state.customFonts.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            state.customFonts.forEach { font ->
                                SingleLinePill(text = font.displayName)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("音量键翻页（阅读界面启用）", color = palette.ink, fontSize = 13.sp)
                        Switch(
                            checked = state.volumeKeyPageTurn,
                            onCheckedChange = viewModel::toggleVolumeKeyPageTurn,
                        )
                    }
                }
            }

            // Section 3: Honest Slice 6 Preview Card (Zero fake simulation!)
            item(key = "slice6_preview") {
                BanReadSurfaceCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "手机互联 · 三通道分享与同步（Slice 6 待接入）",
                            color = palette.ink,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(6.dp))
                        SingleLinePill(
                            text = "Slice 6 接入后启用",
                            containerColor = palette.amberSoft,
                            contentColor = palette.amber,
                            borderColor = palette.amber,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "跨公网 R2 24h E2EE · 局域网直连 · 微信 .bcnote",
                        color = palette.cinnabar,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "当前版本尚未接入双机网络传输与桌面网关，跨书对照（compare_context / method_audit）与 R2 / 局域网 / .bcnote 分享通道将在 Slice 6 接入后启用。",
                        color = palette.ink2,
                        fontSize = 12.sp,
                        lineHeight = 17.5.sp,
                    )
                }
            }
        }
    }

    if (state.editor.isOpen) {
        ByokEditorDialog(
            editor = state.editor,
            onApplyPreset = viewModel::applyPreset,
            onKindChange = viewModel::updateEditorKind,
            onEndpointChange = viewModel::updateEditorEndpoint,
            onApiKeyChange = viewModel::updateEditorApiKey,
            onToggleKeyVisibility = viewModel::toggleEditorApiKeyVisibility,
            onModelIdChange = viewModel::updateEditorModelId,
            onFetchModels = viewModel::fetchAvailableModels,
            onSelectDiscoveredModel = viewModel::selectDiscoveredModel,
            onThinkingLevelChange = viewModel::updateEditorThinkingLevel,
            onDisplayNameChange = viewModel::updateEditorDisplayName,
            onVisionChange = viewModel::updateEditorSupportsVision,
            onRemove = viewModel::removeEditingProvider,
            onSave = viewModel::saveByokEditor,
            onDismiss = viewModel::dismissByokDialog,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SwipeToDeleteProviderCard(
    summary: ProviderSummary,
    connectionStatus: String?,
    isTesting: Boolean,
    onSetDefaultCompanion: () -> Unit,
    onSetDefaultVision: () -> Unit,
    onEdit: () -> Unit,
    onTest: () -> Unit,
    onSwipeRemove: () -> Unit,
) {
    val palette = BanReadTheme.palette
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) {
                onSwipeRemove()
                true
            } else {
                false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.dangerSoft)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "右滑移除",
                        tint = palette.danger,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "松手移除",
                        color = palette.danger,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.5.sp,
                    )
                }
            }
        },
    ) {
        val (presetBadge, cleanTitle) = SettingsViewModel.formatProviderCardTitle(summary)
        BanReadSurfaceCard(highlighted = summary.isDefaultCompanion) {
            // Title row + preset badge
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (presetBadge != null) {
                    SingleLinePill(
                        text = presetBadge,
                        containerColor = palette.surfaceSubtle,
                        contentColor = palette.ink2,
                        borderColor = palette.lineStrong,
                    )
                }
                Text(
                    text = cleanTitle,
                    color = palette.ink,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(6.dp))

            // Single-line capability pills (wrapped cleanly on 360dp phones via FlowRow)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SingleLinePill(
                    text = "思考: ${summary.thinkingLevel.labelZh}",
                    containerColor = palette.cinnabarSoft,
                    contentColor = palette.cinnabar,
                    borderColor = palette.cinnabar,
                )
                if (summary.capabilities.supportsVision) {
                    SingleLinePill(
                        text = "Vision",
                        containerColor = palette.pineSoft,
                        contentColor = palette.pine,
                        borderColor = palette.pine,
                    )
                }
                if (summary.capabilities.supportsJsonSchema) {
                    SingleLinePill(text = "JSON Schema")
                }
                SingleLinePill(text = "${summary.capabilities.contextWindow / 1000}K ctx")
            }

            Spacer(Modifier.height(6.dp))

            // Endpoint, modelId, maskedApiKey (NEVER plaintext!)
            Text(
                text = "${summary.endpoint} · ${summary.modelId}",
                color = palette.ink2,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Key：${summary.maskedApiKey}",
                color = palette.ink3,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )

            if (!connectionStatus.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = connectionStatus,
                    color = if (connectionStatus.startsWith("连接失败")) palette.danger else palette.pine,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(Modifier.height(8.dp))

            // Default companion / vision controls + Edit & Test buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(onClick = onSetDefaultCompanion),
                    ) {
                        RadioButton(
                            selected = summary.isDefaultCompanion,
                            onClick = onSetDefaultCompanion,
                            colors = RadioButtonDefaults.colors(selectedColor = palette.cinnabar),
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("设为默认伴读", color = palette.ink, fontSize = 11.5.sp, maxLines = 1, softWrap = false)
                    }
                    if (summary.capabilities.supportsVision) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable(onClick = onSetDefaultVision),
                        ) {
                            RadioButton(
                                selected = summary.isDefaultVision,
                                onClick = onSetDefaultVision,
                                colors = RadioButtonDefaults.colors(selectedColor = palette.pine),
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("设为默认视觉 VLM", color = palette.ink, fontSize = 11.5.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                CompactActionButton(label = "编辑", onClick = onEdit)
                CompactActionButton(
                    label = if (isTesting) "测试中…" else "测试连通性",
                    enabled = !isTesting,
                    onClick = onTest,
                )
            }
        }
    }
}
