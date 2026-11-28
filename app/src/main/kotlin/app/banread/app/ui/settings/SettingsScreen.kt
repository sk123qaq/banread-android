package app.banread.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import app.banread.sync.CloudSyncBackend
import app.banread.sync.ShareChannel
import java.io.File
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.shareSend.pendingSystemSharePath) {
        val path = state.shareSend.pendingSystemSharePath ?: return@LaunchedEffect
        val file = File(path)
        if (file.isFile) {
            runCatching {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(sendIntent, "分享伴读资料包"))
            }
        }
        viewModel.consumePendingSystemSharePath()
    }

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

            // Section 3: Desktop Gateway & Cloud Light-State Shard Sync
            item(key = "desktop_cloud_sync") {
                val sync = state.syncStatus
                val panel = state.desktopSync
                val isPaired = sync?.desktop?.isPaired == true
                BanReadSurfaceCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "桌面网关与云端分片同步",
                            color = palette.ink,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(6.dp))
                        SingleLinePill(
                            text = when {
                                isPaired && sync?.desktop?.isReachable == true -> "桌面在线"
                                isPaired -> "已配对"
                                else -> "未配对"
                            },
                            containerColor = if (isPaired) palette.pineSoft else palette.surfaceSubtle,
                            contentColor = if (isPaired) palette.pine else palette.ink2,
                            borderColor = if (isPaired) palette.pine else palette.lineStrong,
                        )
                    }
                    Spacer(Modifier.height(6.dp))

                    if (!isPaired) {
                        Text(
                            text = "在桌面执行 python -m bookcorpus.mobile_gateway --bind 0.0.0.0 后粘贴 bc-pair://v1?... 配对，令牌由 Tink + Keystore 加密保管。",
                            color = palette.ink2,
                            fontSize = 11.5.sp,
                            lineHeight = 16.5.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = panel.pairUriInput,
                            onValueChange = viewModel::updatePairUriInput,
                            label = { Text("bc-pair://v1?base=...&token=...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        CompactActionButton(
                            label = if (panel.isPairing) "配对中…" else "配对桌面网关",
                            primary = true,
                            enabled = !panel.isPairing,
                            onClick = viewModel::pairDesktop,
                        )
                    } else {
                        Text(
                            text = "桌面网关：${sync?.desktop?.baseUrl.orEmpty()}",
                            color = palette.ink2,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        panel.healthStatusText?.let { status ->
                            Spacer(Modifier.height(3.dp))
                            Text(status, color = palette.pine, fontSize = 11.5.sp)
                        }
                        panel.pullingStatusText?.let { pullMsg ->
                            Spacer(Modifier.height(3.dp))
                            Text(pullMsg, color = palette.cinnabar, fontSize = 11.5.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CompactActionButton(
                                label = if (panel.isPullingBundles) "拉取中…" else "拉取 Outbox",
                                primary = true,
                                enabled = !panel.isPullingBundles,
                                onClick = viewModel::pullDesktopOutbox,
                            )
                            CompactActionButton(
                                label = "浏览桌面书目",
                                onClick = viewModel::openDesktopCatalogDialog,
                            )
                            CompactActionButton(
                                label = if (panel.isCheckingHealth) "检测中…" else "健康检查",
                                enabled = !panel.isCheckingHealth,
                                onClick = viewModel::checkDesktopHealth,
                            )
                            CompactActionButton(
                                label = "解除配对",
                                onClick = viewModel::unpairDesktop,
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    val cloudLabel = when (sync?.cloudBackend ?: CloudSyncBackend.NONE) {
                        CloudSyncBackend.NONE -> "云端分片：未配置"
                        CloudSyncBackend.WEBDAV -> "云端分片：WebDAV"
                        CloudSyncBackend.S3_R2 -> "云端分片：S3 / R2"
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SingleLinePill(
                            text = cloudLabel,
                            containerColor = if (sync?.cloudBackend != CloudSyncBackend.NONE) palette.pineSoft else palette.surfaceSubtle,
                            contentColor = if (sync?.cloudBackend != CloudSyncBackend.NONE) palette.pine else palette.ink2,
                            borderColor = if (sync?.cloudBackend != CloudSyncBackend.NONE) palette.pine else palette.lineStrong,
                            onClick = viewModel::openCloudConfigDialog,
                        )
                        if ((sync?.pendingOpCount ?: 0) > 0) {
                            SingleLinePill(
                                text = "待联网重试：${sync?.pendingOpCount}",
                                containerColor = palette.amberSoft,
                                contentColor = palette.amber,
                                borderColor = palette.amber,
                            )
                        }
                        CompactActionButton(
                            label = "配置云端分片",
                            onClick = viewModel::openCloudConfigDialog,
                        )
                        CompactActionButton(
                            label = if (panel.isSyncingAll) "同步中…" else "立即同步进度/批注",
                            enabled = !panel.isSyncingAll,
                            onClick = viewModel::syncAllBooksNow,
                        )
                    }
                    sync?.lastSyncSummary?.let { summary ->
                        Spacer(Modifier.height(4.dp))
                        Text(summary, color = palette.ink3, fontSize = 11.sp)
                    }
                }
            }

            // Section 4: 3-Channel SHA-256 Share Card matching Demo v5
            item(key = "three_channel_share") {
                val r2Configured = state.syncStatus?.r2RelayConfig != null
                BanReadSurfaceCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "手机互联 · 三通道分享",
                            color = palette.ink,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(6.dp))
                        SingleLinePill(
                            text = "跨公网 · 局域网 · .bcnote",
                            containerColor = palette.pineSoft,
                            contentColor = palette.pine,
                            borderColor = palette.pine,
                        )
                    }
                    Spacer(Modifier.height(8.dp))

                    // 3-column compact action grid (zero horizontal overflow at 360dp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ShareGridActionButton(
                            label = "扫码连接",
                            onClick = { viewModel.openShareReceiveDialog() },
                            modifier = Modifier.weight(1f),
                        )
                        ShareGridActionButton(
                            label = "生成二维码",
                            onClick = { viewModel.openShareSendDialog(ShareChannel.R2_RELAY, SharePresentationMode.QR) },
                            modifier = Modifier.weight(1f),
                        )
                        ShareGridActionButton(
                            label = "生成分享链接",
                            onClick = { viewModel.openShareSendDialog(ShareChannel.R2_RELAY, SharePresentationMode.LINK) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    CompactActionButton(
                        label = "导出 .bcnote / .bcshare 离线包（发微信）",
                        onClick = { viewModel.openShareSendDialog(ShareChannel.OFFLINE_PACKAGE, SharePresentationMode.LINK) },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SingleLinePill(
                            text = "本机 ${state.shareWorks.size} 本书 SHA-256 已就绪",
                        )
                        SingleLinePill(
                            text = if (r2Configured) "R2 加密中转：已配置 (24h TTL)" else "R2 未配置：引导 .bcnote 兜底",
                            containerColor = if (r2Configured) palette.pineSoft else palette.amberSoft,
                            contentColor = if (r2Configured) palette.pine else palette.amber,
                            borderColor = if (r2Configured) palette.pine else palette.amber,
                            onClick = { viewModel.openShareSendDialog(ShareChannel.R2_RELAY, SharePresentationMode.LINK) },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = state.lastShareLog,
                        color = palette.ink3,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
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

    if (state.desktopSync.catalogDialogOpen) {
        DesktopCatalogDialog(
            panel = state.desktopSync,
            onQueryChange = viewModel::updateDesktopCatalogQuery,
            onToggleOnlyWithCache = viewModel::toggleDesktopCatalogOnlyWithCache,
            onSearch = viewModel::loadDesktopCatalog,
            onPullBook = viewModel::pullSingleStudyPack,
            onDismiss = viewModel::dismissDesktopCatalogDialog,
        )
    }

    if (state.desktopSync.cloudConfigDialogOpen) {
        CloudShardConfigDialog(
            panel = state.desktopSync,
            hasStoredSecret = state.syncStatus?.hasCloudSecret == true,
            onUpdateFields = viewModel::updateCloudConfigFields,
            onSave = viewModel::saveCloudShardConfig,
            onDismiss = viewModel::dismissCloudConfigDialog,
        )
    }

    if (state.shareSend.isOpen) {
        ShareSendDialog(
            state = state.shareSend,
            works = state.shareWorks,
            r2RelayConfig = state.syncStatus?.r2RelayConfig,
            onUpdateOptions = viewModel::updateShareSendOptions,
            onSaveR2Config = viewModel::saveR2RelayConfig,
            onSendR2 = viewModel::sendViaR2RelayNow,
            onStartLan = viewModel::startLanDirectShareNow,
            onStopLan = viewModel::stopLanDirectShare,
            onExportOffline = viewModel::exportOfflinePackageNow,
            onDismiss = viewModel::dismissShareSendDialog,
        )
    }

    if (state.shareReceive.isOpen) {
        ShareReceiveDialog(
            state = state.shareReceive,
            onUpdateInput = viewModel::updateReceiveInput,
            onParse = viewModel::parseIncomingShare,
            onConfirmImport = viewModel::confirmIncomingShareImport,
            onDismiss = viewModel::dismissShareReceiveDialog,
        )
    }
}

@Composable
private fun ShareGridActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BanReadTheme.palette
    androidx.compose.material3.Surface(
        modifier = modifier
            .height(34.dp)
            .clickable(onClick = onClick),
        color = palette.surfaceSubtle,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, palette.lineStrong),
        shadowElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(
                text = label,
                color = palette.ink,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
