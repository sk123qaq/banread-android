package app.banread.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.banread.app.ui.components.BanReadSurfaceCard
import app.banread.app.ui.components.CompactActionButton
import app.banread.app.ui.components.SingleLinePill
import app.banread.app.ui.theme.BanReadTheme
import app.banread.model.DesktopBookSummary
import app.banread.model.WorkId
import app.banread.model.WorkSummary
import app.banread.sync.CloudSyncBackend
import app.banread.sync.R2RelayConfig
import app.banread.sync.ShareChannel

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopCatalogDialog(
    panel: DesktopSyncPanelState,
    onQueryChange: (String) -> Unit,
    onToggleOnlyWithCache: (Boolean) -> Unit,
    onSearch: () -> Unit,
    onPullBook: (WorkId) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = BanReadTheme.palette
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = {
            Text("桌面书库目录", color = palette.ink, style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = panel.catalogQuery,
                        onValueChange = onQueryChange,
                        label = { Text("搜索书名 / 作者 / UID") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    CompactActionButton(label = "搜索", primary = true, onClick = onSearch)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("仅显示已有预置伴读缓存", color = palette.ink2, fontSize = 12.sp)
                    Switch(
                        checked = panel.catalogOnlyWithCache,
                        onCheckedChange = onToggleOnlyWithCache,
                    )
                }
                if (panel.isLoadingCatalog) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("正在查询桌面书目…", color = palette.ink3, fontSize = 12.sp)
                    }
                } else if (panel.catalogBooks.isEmpty()) {
                    Text("未找到匹配书目", color = palette.ink3, fontSize = 12.sp)
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        panel.catalogBooks.take(40).forEach { book: DesktopBookSummary ->
                            BanReadSurfaceCard(padding = PaddingValues(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = book.title,
                                            color = palette.ink,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${book.uid.raw} · ${book.author.ifBlank { "未知作者" }} · ${book.logicalChapterCount} 章",
                                            color = palette.ink3,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Spacer(Modifier.size(6.dp))
                                    CompactActionButton(
                                        label = "拉取",
                                        enabled = !panel.isPullingBundles,
                                        onClick = { onPullBook(book.uid) },
                                    )
                                }
                                if (book.hasCompanionCache) {
                                    Spacer(Modifier.height(4.dp))
                                    SingleLinePill(
                                        text = "含桌面预置伴读缓存",
                                        containerColor = palette.pineSoft,
                                        contentColor = palette.pine,
                                        borderColor = palette.pine,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = palette.ink2)
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CloudShardConfigDialog(
    panel: DesktopSyncPanelState,
    hasStoredSecret: Boolean,
    onUpdateFields: ((DesktopSyncPanelState) -> DesktopSyncPanelState) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = BanReadTheme.palette
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = {
            Text("云端轻状态分片同步", color = palette.ink, style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "仅同步 progress/<uid>/<device>.json 与 notes/<uid>/<device>.jsonl（<= 2 MiB，严禁上传原书或大图谱）。",
                    color = palette.ink3,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        CloudSyncBackend.NONE to "关闭",
                        CloudSyncBackend.WEBDAV to "WebDAV",
                        CloudSyncBackend.S3_R2 to "S3 / R2",
                    ).forEach { (backend, label) ->
                        val selected = panel.selectedCloudBackend == backend
                        SingleLinePill(
                            text = label,
                            containerColor = if (selected) palette.cinnabar else palette.surfaceSubtle,
                            contentColor = if (selected) Color.White else palette.ink,
                            borderColor = if (selected) palette.cinnabar else palette.lineStrong,
                            onClick = { onUpdateFields { it.copy(selectedCloudBackend = backend) } },
                        )
                    }
                }

                when (panel.selectedCloudBackend) {
                    CloudSyncBackend.NONE -> {
                        Text("已关闭云端分片同步，仅保留本机或桌面网关同步。", color = palette.ink2, fontSize = 12.sp)
                    }
                    CloudSyncBackend.WEBDAV -> {
                        OutlinedTextField(
                            value = panel.webDavUrlInput,
                            onValueChange = { v -> onUpdateFields { it.copy(webDavUrlInput = v) } },
                            label = { Text("WebDAV 根地址 (https://...)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = panel.webDavUserInput,
                            onValueChange = { v -> onUpdateFields { it.copy(webDavUserInput = v) } },
                            label = { Text("用户名（Bearer 模式可留空）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = panel.webDavSecretInput,
                            onValueChange = { v -> onUpdateFields { it.copy(webDavSecretInput = v) } },
                            label = {
                                Text(if (hasStoredSecret) "密码 / Token（留空保留原密钥）" else "密码 / Token（Tink 加密）")
                            },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("使用 Bearer Token 鉴权", color = palette.ink, fontSize = 12.5.sp)
                            Switch(
                                checked = panel.webDavUseBearer,
                                onCheckedChange = { b -> onUpdateFields { it.copy(webDavUseBearer = b) } },
                            )
                        }
                    }
                    CloudSyncBackend.S3_R2 -> {
                        OutlinedTextField(
                            value = panel.s3EndpointInput,
                            onValueChange = { v -> onUpdateFields { it.copy(s3EndpointInput = v) } },
                            label = { Text("Endpoint (https://<id>.r2.cloudflarestorage.com)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = panel.s3BucketInput,
                            onValueChange = { v -> onUpdateFields { it.copy(s3BucketInput = v) } },
                            label = { Text("Bucket 名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = panel.s3RegionInput,
                            onValueChange = { v -> onUpdateFields { it.copy(s3RegionInput = v) } },
                            label = { Text("Region (R2 默认 auto)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = panel.s3AccessKeyInput,
                            onValueChange = { v -> onUpdateFields { it.copy(s3AccessKeyInput = v) } },
                            label = { Text("Access Key ID") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = panel.s3SecretKeyInput,
                            onValueChange = { v -> onUpdateFields { it.copy(s3SecretKeyInput = v) } },
                            label = {
                                Text(if (hasStoredSecret) "Secret Key（留空保留原密钥）" else "Secret Access Key（Tink 加密）")
                            },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                colors = ButtonDefaults.buttonColors(containerColor = palette.cinnabar, contentColor = Color.White),
            ) {
                Text("保存配置")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = palette.ink2)
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShareSendDialog(
    state: ShareSendDialogState,
    works: List<WorkSummary>,
    r2RelayConfig: R2RelayConfig?,
    onUpdateOptions: ((ShareSendDialogState) -> ShareSendDialogState) -> Unit,
    onSaveR2Config: () -> Unit,
    onSendR2: () -> Unit,
    onStartLan: () -> Unit,
    onStopLan: () -> Unit,
    onExportOffline: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = BanReadTheme.palette
    val context = LocalContext.current
    val selectedBook = works.firstOrNull { it.workId == state.selectedWorkId } ?: works.firstOrNull()
    val manifest = state.previewManifest
    val dataKb = maxOf(1L, (manifest?.companionSizeBytes ?: 2048L) / 1024L)
    val bookMb = "%.1f".format((manifest?.bookSizeBytes ?: 0L) / (1024.0 * 1024.0))
    val totalMb = "%.1f".format(((manifest?.bookSizeBytes ?: 0L) + (manifest?.companionSizeBytes ?: 0L)) / (1024.0 * 1024.0))

    var bookMenuExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = {
            Text("传书与伴读资料分享", color = palette.ink, style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        ShareChannel.R2_RELAY to "跨公网 R2",
                        ShareChannel.LAN_DIRECT to "局域网直连",
                        ShareChannel.OFFLINE_PACKAGE to "微信 .bcnote 包",
                    ).forEach { (ch, label) ->
                        val selected = state.channel == ch
                        SingleLinePill(
                            text = label,
                            containerColor = if (selected) palette.cinnabar else palette.surfaceSubtle,
                            contentColor = if (selected) Color.White else palette.ink,
                            borderColor = if (selected) palette.cinnabar else palette.lineStrong,
                            onClick = { onUpdateOptions { it.copy(channel = ch) } },
                        )
                    }
                }

                if (selectedBook == null) {
                    Text("书架暂无可分享书籍，请先导入或从桌面拉取图书。", color = palette.ink2, fontSize = 12.5.sp)
                } else {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        val shortSha = selectedBook.bookSourceSha256?.take(8) ?: "00000000"
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { bookMenuExpanded = true },
                            color = palette.surfaceSubtle,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, palette.lineStrong),
                        ) {
                            Text(
                                text = "《${selectedBook.title}》 · sha256:$shortSha…",
                                color = palette.ink,
                                fontSize = 12.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = bookMenuExpanded,
                            onDismissRequest = { bookMenuExpanded = false },
                        ) {
                            works.forEach { w ->
                                val wSha = w.bookSourceSha256?.take(8) ?: "00000000"
                                DropdownMenuItem(
                                    text = { Text("《${w.title}》 · sha256:$wSha…", fontSize = 12.5.sp) },
                                    onClick = {
                                        bookMenuExpanded = false
                                        onUpdateOptions {
                                            it.copy(
                                                selectedWorkId = w.workId,
                                                generatedR2Url = null,
                                                exportedPackagePath = null,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }

                    ShareCheckRow(
                        checked = state.includeMaps,
                        label = "章节导图与结构蓝图（${manifest?.mapCount ?: 0} 份）",
                        onCheckedChange = { v -> onUpdateOptions { it.copy(includeMaps = v) } },
                    )
                    ShareCheckRow(
                        checked = state.includeNotes,
                        label = "划词高亮与行内眉批（${manifest?.noteCount ?: 0} 条）",
                        onCheckedChange = { v -> onUpdateOptions { it.copy(includeNotes = v) } },
                    )
                    ShareCheckRow(
                        checked = state.includeQa,
                        label = "多轮伴读问答记录（${manifest?.qaCount ?: 0} 段会话）",
                        onCheckedChange = { v -> onUpdateOptions { it.copy(includeQa = v) } },
                    )
                    ShareCheckRow(
                        checked = state.includeProgress,
                        label = "当前阅读进度快照（默认不覆盖对方进度）",
                        onCheckedChange = { v -> onUpdateOptions { it.copy(includeProgress = v) } },
                    )

                    when (state.channel) {
                        ShareChannel.R2_RELAY -> {
                            val configured = r2RelayConfig != null && !state.isConfiguringR2
                            if (!configured) {
                                BanReadSurfaceCard(padding = PaddingValues(10.dp)) {
                                    Text(
                                        text = "尚未配置 Cloudflare R2 / Worker 中转端点：可直接改用微信 .bcnote 离线包（零服务器），或填写下方中转地址启用 24h E2EE 中转。",
                                        color = palette.amber,
                                        fontSize = 11.5.sp,
                                        lineHeight = 16.sp,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedTextField(
                                        value = state.r2RelayUrlInput,
                                        onValueChange = { v -> onUpdateOptions { it.copy(r2RelayUrlInput = v) } },
                                        label = { Text("R2 / Worker 中转 URL") },
                                        placeholder = { Text("https://share.banread.app") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    OutlinedTextField(
                                        value = state.r2UploadTokenInput,
                                        onValueChange = { v -> onUpdateOptions { it.copy(r2UploadTokenInput = v) } },
                                        label = { Text("上传 Token（可选，Tink 加密）") },
                                        singleLine = true,
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        CompactActionButton(
                                            label = "改用微信 .bcnote",
                                            primary = true,
                                            onClick = { onUpdateOptions { it.copy(channel = ShareChannel.OFFLINE_PACKAGE) } },
                                        )
                                        CompactActionButton(
                                            label = "保存 R2 端点",
                                            onClick = onSaveR2Config,
                                        )
                                    }
                                }
                            } else {
                                Surface(
                                    color = palette.pineSoft,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, palette.pine),
                                ) {
                                    Text(
                                        text = "🔒 本地 AES-GCM 端到端加密 · 解密密钥仅存于链接/二维码 #fragment（中转端零明文）· 24h 自动焚毁",
                                        color = palette.pine,
                                        fontSize = 11.5.sp,
                                        lineHeight = 16.sp,
                                        modifier = Modifier.padding(8.dp),
                                    )
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    SingleLinePill(
                                        text = "仅传资料 (${dataKb} KB 推荐)",
                                        containerColor = if (!state.r2IncludeBook) palette.cinnabar else palette.surfaceSubtle,
                                        contentColor = if (!state.r2IncludeBook) Color.White else palette.ink,
                                        borderColor = if (!state.r2IncludeBook) palette.cinnabar else palette.lineStrong,
                                        onClick = { onUpdateOptions { it.copy(r2IncludeBook = false) } },
                                    )
                                    SingleLinePill(
                                        text = "含加密原书 (+${bookMb} MB)",
                                        containerColor = if (state.r2IncludeBook) palette.cinnabar else palette.surfaceSubtle,
                                        contentColor = if (state.r2IncludeBook) Color.White else palette.ink,
                                        borderColor = if (state.r2IncludeBook) palette.cinnabar else palette.lineStrong,
                                        onClick = { onUpdateOptions { it.copy(r2IncludeBook = true) } },
                                    )
                                    SingleLinePill(
                                        text = "修改 R2 地址",
                                        onClick = { onUpdateOptions { it.copy(isConfiguringR2 = true) } },
                                    )
                                }
                                Text(
                                    text = "接收端先拉清单比对本地 SHA-256；若已有同书自动跳过 $bookMb MB 原书下载，只拉 $dataKb KB 伴读资料！",
                                    color = palette.ink3,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    SingleLinePill(
                                        text = "生成二维码",
                                        containerColor = if (state.presentationMode == SharePresentationMode.QR) palette.ink else palette.surfaceSubtle,
                                        contentColor = if (state.presentationMode == SharePresentationMode.QR) palette.surface else palette.ink,
                                        borderColor = palette.lineStrong,
                                        onClick = { onUpdateOptions { it.copy(presentationMode = SharePresentationMode.QR) } },
                                    )
                                    SingleLinePill(
                                        text = "生成分享链接",
                                        containerColor = if (state.presentationMode == SharePresentationMode.LINK) palette.ink else palette.surfaceSubtle,
                                        contentColor = if (state.presentationMode == SharePresentationMode.LINK) palette.surface else palette.ink,
                                        borderColor = palette.lineStrong,
                                        onClick = { onUpdateOptions { it.copy(presentationMode = SharePresentationMode.LINK) } },
                                    )
                                    CompactActionButton(
                                        label = if (state.isBusy) "加密上传中…" else "加密生成",
                                        primary = true,
                                        enabled = !state.isBusy,
                                        onClick = onSendR2,
                                    )
                                }
                                state.generatedR2Url?.let { url ->
                                    ShareQrAndLinkBox(
                                        url = url,
                                        mode = state.presentationMode,
                                        pairingCode = null,
                                        onCopy = { copyToClipboard(context, "banread_r2_url", url) },
                                    )
                                }
                            }
                        }

                        ShareChannel.LAN_DIRECT -> {
                            ShareCheckRow(
                                checked = state.lanIncludeBook,
                                label = "包含原书文件（${selectedBook.format} · $bookMb MB · 对方已有同 SHA 将自动跳过）",
                                onCheckedChange = { v -> onUpdateOptions { it.copy(lanIncludeBook = v) } },
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                SingleLinePill(
                                    text = "二维码",
                                    containerColor = if (state.presentationMode == SharePresentationMode.QR) palette.ink else palette.surfaceSubtle,
                                    contentColor = if (state.presentationMode == SharePresentationMode.QR) palette.surface else palette.ink,
                                    borderColor = palette.lineStrong,
                                    onClick = { onUpdateOptions { it.copy(presentationMode = SharePresentationMode.QR) } },
                                )
                                SingleLinePill(
                                    text = "直传链接",
                                    containerColor = if (state.presentationMode == SharePresentationMode.LINK) palette.ink else palette.surfaceSubtle,
                                    contentColor = if (state.presentationMode == SharePresentationMode.LINK) palette.surface else palette.ink,
                                    borderColor = palette.lineStrong,
                                    onClick = { onUpdateOptions { it.copy(presentationMode = SharePresentationMode.LINK) } },
                                )
                                if (state.activeLanSession == null) {
                                    CompactActionButton(
                                        label = if (state.isBusy) "启动中…" else "开启局域网直连",
                                        primary = true,
                                        enabled = !state.isBusy,
                                        onClick = onStartLan,
                                    )
                                } else {
                                    CompactActionButton(label = "停止服务", onClick = onStopLan)
                                }
                            }
                            state.activeLanSession?.let { sess ->
                                ShareQrAndLinkBox(
                                    url = sess.baseUrl,
                                    mode = state.presentationMode,
                                    pairingCode = sess.pairingCode,
                                    onCopy = { copyToClipboard(context, "banread_lan_url", sess.baseUrl) },
                                )
                            }
                        }

                        ShareChannel.OFFLINE_PACKAGE -> {
                            Surface(
                                color = palette.pineSoft,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, palette.pine),
                            ) {
                                Text(
                                    text = "零服务器直发文件包 · 可直接转发至微信、Telegram 或系统分享",
                                    color = palette.pine,
                                    fontSize = 11.5.sp,
                                    modifier = Modifier.padding(8.dp),
                                )
                            }
                            BanReadSurfaceCard(
                                highlighted = !state.exportAsBcShare,
                                padding = PaddingValues(10.dp),
                                onClick = { onUpdateOptions { it.copy(exportAsBcShare = false) } },
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text("仅伴读资料包 (.bcnote)", fontWeight = FontWeight.Bold, fontSize = 12.5.sp, color = palette.ink)
                                    SingleLinePill(text = "约 $dataKb KB")
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "不含原书 · 发微信秒传，对方打开按 SHA-256 自动关联本地同书并无损合并",
                                    fontSize = 11.sp,
                                    color = palette.ink2,
                                )
                            }
                            BanReadSurfaceCard(
                                highlighted = state.exportAsBcShare,
                                padding = PaddingValues(10.dp),
                                onClick = { onUpdateOptions { it.copy(exportAsBcShare = true) } },
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text("整书 + 伴读资料 (.bcshare)", fontWeight = FontWeight.Bold, fontSize = 12.5.sp, color = palette.ink)
                                    SingleLinePill(text = "约 $totalMb MB")
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "含原书包与所选资料 · 对方无此书时一步导入新书与全部笔记",
                                    fontSize = 11.sp,
                                    color = palette.ink2,
                                )
                            }
                            CompactActionButton(
                                label = if (state.isBusy) "打包中…" else "导出并分享至微信 / 系统分享",
                                primary = true,
                                enabled = !state.isBusy,
                                onClick = onExportOffline,
                            )
                            state.exportedPackagePath?.let { path ->
                                Text(
                                    text = "已导出：$path",
                                    color = palette.pine,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.5.sp,
                                )
                            }
                        }
                    }
                }

                state.errorMessage?.let { err ->
                    Text(err, color = palette.danger, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = palette.ink2)
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShareReceiveDialog(
    state: ShareReceiveDialogState,
    onUpdateInput: (String?, String?) -> Unit,
    onParse: (String?) -> Unit,
    onConfirmImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = BanReadTheme.palette
    val pkgPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            onParse(uri.toString())
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = {
            Text("扫码或解析连接接收", color = palette.ink, style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "兼容跨公网 R2 密文链接 (#k=)、192.168.x.x 局域网直传，以及微信收到的 .bcnote / .bcshare 离线包。",
                    color = palette.ink2,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                )
                OutlinedTextField(
                    value = state.linkOrUriInput,
                    onValueChange = { onUpdateInput(it, null) },
                    label = { Text("分享链接或本地路径") },
                    placeholder = { Text("https://share.banread.app/s/...#k=... 或 http://192.168...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.lanCodeInput,
                    onValueChange = { onUpdateInput(null, it) },
                    label = { Text("局域网 6 位配对码（链接未带 code 时填）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CompactActionButton(
                        label = if (state.isParsing) "解析中…" else "解析连接",
                        primary = true,
                        enabled = !state.isParsing,
                        onClick = { onParse(null) },
                    )
                    CompactActionButton(
                        label = "选择 .bcnote / .bcshare 文件",
                        enabled = !state.isParsing,
                        onClick = {
                            pkgPickerLauncher.launch(arrayOf("application/octet-stream", "application/zip", "*/*"))
                        },
                    )
                }

                state.errorMessage?.let { err ->
                    Text(err, color = palette.danger, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                }

                state.preview?.let { prev ->
                    val m = prev.manifest
                    val bookMb = "%.1f".format(m.bookSizeBytes / (1024.0 * 1024.0))
                    val chLabel = when (prev.channel) {
                        ShareChannel.R2_RELAY -> "跨公网 R2 · E2EE 24h"
                        ShareChannel.LAN_DIRECT -> "局域网直连"
                        ShareChannel.OFFLINE_PACKAGE -> if (m.includeBookFile) "离线 .bcshare 包" else "微信 .bcnote 包"
                    }
                    BanReadSurfaceCard(highlighted = true, padding = PaddingValues(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "《${m.title}》",
                                color = palette.ink,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            SingleLinePill(
                                text = chLabel,
                                containerColor = palette.pineSoft,
                                contentColor = palette.pine,
                                borderColor = palette.pine,
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "${m.senderDeviceId.raw} · sha256:${m.bookSourceSha256.take(12)}…",
                            color = palette.ink3,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            color = if (prev.willSkipBookDownload) palette.pineSoft else palette.amberSoft,
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                text = if (prev.willSkipBookDownload) {
                                    "✓ 本地已存在同 SHA 原书，跳过 $bookMb MB 原书下载，仅导入伴读资料"
                                } else {
                                    "＋ 本地无此书，将导入新书（$bookMb MB）与全部伴读资料"
                                },
                                color = if (prev.willSkipBookDownload) palette.pine else palette.amber,
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            SingleLinePill(text = "导图 · ${m.mapCount}")
                            SingleLinePill(text = "批注 · ${m.noteCount}")
                            SingleLinePill(text = "问答 · ${m.qaCount}")
                        }
                        Spacer(Modifier.height(8.dp))
                        CompactActionButton(
                            label = if (state.isImporting) "正在合并导入…" else "确认接收导入",
                            primary = true,
                            enabled = !state.isImporting,
                            onClick = onConfirmImport,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = palette.ink2)
            }
        },
    )
}

@Composable
private fun ShareCheckRow(
    checked: Boolean,
    label: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    val palette = BanReadTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = label,
            color = palette.ink,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ShareQrAndLinkBox(
    url: String,
    mode: SharePresentationMode,
    pairingCode: String?,
    onCopy: () -> Unit,
) {
    val palette = BanReadTheme.palette
    val qrBitmap = remember(url) { QrCodeGenerator.encodeBitmap(url, sizePx = 320) }

    BanReadSurfaceCard(padding = PaddingValues(10.dp)) {
        if (mode == SharePresentationMode.QR && qrBitmap != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "分享二维码",
                    modifier = Modifier
                        .size(144.dp)
                        .border(1.dp, palette.lineStrong, RoundedCornerShape(6.dp))
                        .background(Color.White)
                        .padding(4.dp),
                )
            }
        }
        if (!pairingCode.isNullOrBlank()) {
            Text(
                text = "6 位配对码：$pairingCode",
                color = palette.cinnabar,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text = url,
            color = palette.ink2,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
            lineHeight = 15.sp,
        )
        Spacer(Modifier.height(6.dp))
        CompactActionButton(label = "复制分享链接", onClick = onCopy)
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText(label, text))
}
