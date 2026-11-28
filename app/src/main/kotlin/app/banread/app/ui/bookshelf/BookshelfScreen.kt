package app.banread.app.ui.bookshelf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.banread.app.ui.components.BanReadBottomNav
import app.banread.app.ui.components.BanReadTopBar
import app.banread.app.ui.components.IconAdd
import app.banread.app.ui.components.MainTab
import app.banread.app.ui.components.SingleLinePill
import app.banread.app.ui.components.SmallIconPillButton
import app.banread.app.ui.theme.BanReadTheme
import app.banread.app.ui.theme.parseHexColorOrFallback
import app.banread.model.CoverDisplayMode
import app.banread.model.WorkSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun BookshelfScreen(
    viewModel: BookshelfViewModel,
    onOpenBook: (WorkSummary) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val palette = BanReadTheme.palette
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            val displayName = resolveDocumentDisplayName(context, uri)
            viewModel.importLocalUri(uri.toString(), displayName)
        }
    }

    LaunchedEffect(state.statusMessage) {
        val msg = state.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearStatusMessage()
    }

    Scaffold(
        containerColor = palette.paper,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            BanReadTopBar(
                title = "伴读",
                showSeal = true,
                actions = {
                    SmallIconPillButton(
                        label = if (state.isImporting) "导入中…" else "导入",
                        icon = IconAdd,
                        onClick = {
                            if (!state.isImporting) {
                                importLauncher.launch(
                                    arrayOf(
                                        "application/epub+zip",
                                        "application/pdf",
                                        "application/zip",
                                        "application/x-mobipocket-ebook",
                                        "application/vnd.amazon.ebook",
                                        "application/octet-stream",
                                        "*/*",
                                    ),
                                )
                            }
                        },
                    )
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "设置",
                            tint = palette.ink,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
            )
        },
        bottomBar = {
            BanReadBottomNav(
                activeTab = MainTab.BOOKSHELF,
                onSelectTab = { tab ->
                    if (tab == MainTab.SETTINGS) onOpenSettings()
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            SearchBarField(
                query = state.searchQuery,
                onQueryChange = viewModel::updateSearchQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item(key = "filter_all") {
                    val selected = state.selectedProfileFilter == null
                    SingleLinePill(
                        text = "全部",
                        containerColor = if (selected) palette.cinnabar else palette.surface,
                        contentColor = if (selected) Color.White else palette.ink,
                        borderColor = if (selected) palette.cinnabar else palette.lineStrong,
                        onClick = { viewModel.selectProfileFilter(null) },
                    )
                }
                items(state.profiles, key = { it.readType.raw }) { profile ->
                    val selected = state.selectedProfileFilter == profile.readType
                    SingleLinePill(
                        text = profile.name,
                        containerColor = if (selected) palette.cinnabar else palette.surface,
                        contentColor = if (selected) Color.White else palette.ink,
                        borderColor = if (selected) palette.cinnabar else palette.lineStrong,
                        onClick = { viewModel.selectProfileFilter(profile.readType) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "个人藏书（${state.filteredWorks.size}）",
                    color = palette.ink2,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (state.isImporting) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = palette.cinnabar,
                        )
                        Text(
                            text = "正在解析导入…",
                            color = palette.ink3,
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            if (state.filteredWorks.isEmpty()) {
                EmptyBookshelfPlaceholder(
                    hasAnyBooks = state.allWorks.isNotEmpty(),
                    onImportClick = {
                        importLauncher.launch(arrayOf("*/*"))
                    },
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 152.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.filteredWorks, key = { it.workId.raw }) { work ->
                        BookshelfCoverCard(
                            work = work,
                            profileLabel = viewModel.profileDisplayName(work.readProfileId),
                            onClick = { onOpenBook(work) },
                            onRequestDelete = { viewModel.requestDeleteWork(work) },
                        )
                    }
                }
            }
        }
    }

    val errTitle = state.importErrorTitle
    val errDetail = state.importErrorDetail
    if (errTitle != null && errDetail != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissImportError,
            containerColor = palette.surface,
            title = { Text(errTitle, color = palette.ink, style = MaterialTheme.typography.titleMedium) },
            text = { Text(errDetail, color = palette.ink2, fontSize = 13.sp, lineHeight = 19.sp) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissImportError) {
                    Text("知道了", color = palette.cinnabar)
                }
            },
        )
    }

    val deleteTarget = state.pendingDeleteWork
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteDialog,
            containerColor = palette.surface,
            title = { Text("移出书架？", color = palette.ink, style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    text = "确定删除《${deleteTarget.title}》及其本地编译缓存和伴读数据吗？此操作不可撤销。",
                    color = palette.ink2,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = viewModel::confirmDeleteWork,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.danger,
                        contentColor = Color.White,
                    ),
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDeleteDialog) {
                    Text("取消", color = palette.ink2)
                }
            },
        )
    }
}

@Composable
private fun SearchBarField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BanReadTheme.palette
    Surface(
        modifier = modifier.height(38.dp),
        color = palette.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(palette.borderWidth, palette.lineStrong),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = "搜索",
                tint = palette.ink3,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = "搜索书名、作者…",
                        color = palette.ink3,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = palette.ink,
                        fontSize = 13.sp,
                    ),
                    cursorBrush = SolidColor(palette.cinnabar),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "清空搜索",
                        tint = palette.ink3,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookshelfCoverCard(
    work: WorkSummary,
    profileLabel: String,
    onClick: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val palette = BanReadTheme.palette
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = palette.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(palette.borderWidth, palette.line),
        shadowElevation = palette.cardElevation,
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            AdaptiveBookCover(
                work = work,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = work.title,
                color = palette.ink,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 13.5.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = work.author.ifBlank { "未知作者" },
                color = palette.ink3,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SingleLinePill(
                        text = profileLabel,
                        containerColor = palette.surfaceSubtle,
                        contentColor = palette.ink2,
                        borderColor = palette.line,
                    )
                    if (work.isScannedPdf) {
                        SingleLinePill(
                            text = "扫描PDF",
                            containerColor = palette.amberSoft,
                            contentColor = palette.amber,
                            borderColor = palette.amber.copy(alpha = 0.4f),
                        )
                    }
                }
                IconButton(
                    onClick = onRequestDelete,
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "删除 ${work.title}",
                        tint = palette.ink3,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}

/**
 * Renders a 2:3 adaptive cover according to [CoverDisplayMode]:
 * - [CoverDisplayMode.PORTRAIT_CROP]: async decoded cover with [ContentScale.Crop]
 * - [CoverDisplayMode.WIDE_PAPER_MAT]: async decoded cover framed inside a warm paper mat with [ContentScale.Fit]
 * - [CoverDisplayMode.CLOTH_FALLBACK]: deterministic cloth-spine binding with title & author
 */
@Composable
fun AdaptiveBookCover(
    work: WorkSummary,
    modifier: Modifier = Modifier,
) {
    val palette = BanReadTheme.palette
    val coverBitmap by produceState<Bitmap?>(initialValue = null, key1 = work.coverFilePath, key2 = work.coverDisplayMode) {
        value = if (work.coverDisplayMode != CoverDisplayMode.CLOTH_FALLBACK && !work.coverFilePath.isNullOrBlank()) {
            withContext(Dispatchers.IO) {
                decodeSampledCover(work.coverFilePath!!)
            }
        } else {
            null
        }
    }

    val effectiveMode = if (coverBitmap == null) CoverDisplayMode.CLOTH_FALLBACK else work.coverDisplayMode
    val shape = RoundedCornerShape(8.dp)

    Box(
        modifier = modifier
            .clip(shape)
            .border(1.dp, palette.lineStrong, shape),
    ) {
        when (effectiveMode) {
            CoverDisplayMode.PORTRAIT_CROP -> {
                Image(
                    bitmap = coverBitmap!!.asImageBitmap(),
                    contentDescription = "${work.title} 封面",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            CoverDisplayMode.WIDE_PAPER_MAT -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(palette.paperDeep)
                        .padding(10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = coverBitmap!!.asImageBitmap(),
                        contentDescription = "${work.title} 宽幅封面",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .border(0.75.dp, palette.lineStrong),
                    )
                }
            }
            CoverDisplayMode.CLOTH_FALLBACK -> {
                val cloth = parseHexColorOrFallback(work.clothColor)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(cloth),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(8.dp)
                            .background(Color.Black.copy(alpha = 0.22f)),
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = work.format.uppercase(),
                            color = Color(0xFFE7DFCF),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.8.sp,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = work.title,
                                color = Color(0xFFFBF8F1),
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                lineHeight = 17.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (work.author.isNotBlank()) {
                                Text(
                                    text = work.author,
                                    color = Color(0xFFD8CEBC),
                                    fontSize = 10.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(5.dp),
            color = Color.Black.copy(alpha = 0.65f),
            shape = RoundedCornerShape(4.dp),
        ) {
            Text(
                text = work.format.uppercase(),
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun EmptyBookshelfPlaceholder(
    hasAnyBooks: Boolean,
    onImportClick: () -> Unit,
) {
    val palette = BanReadTheme.palette
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (hasAnyBooks) "未找到匹配的藏书" else "书架尚空",
                color = palette.ink,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = if (hasAnyBooks) {
                    "请更换搜索关键词或切换阅读视角筛选"
                } else {
                    "支持本地导入 EPUB、MOBI、AZW3、PDF 与 .bcbook 伴读包（离线解析，自动提取封面与结构）"
                },
                color = palette.ink3,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
            )
            if (!hasAnyBooks) {
                Button(
                    onClick = onImportClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.cinnabar,
                        contentColor = Color.White,
                    ),
                ) {
                    Text("+ 导入本地图书")
                }
            }
        }
    }
}

private fun decodeSampledCover(filePath: String, maxDimPx: Int = 512): Bitmap? {
    val file = File(filePath)
    if (!file.isFile) return null
    return runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        var sample = 1
        val maxSide = maxOf(opts.outWidth, opts.outHeight)
        while (maxSide / sample > maxDimPx) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
    }.getOrNull()
}

private fun resolveDocumentDisplayName(context: android.content.Context, uri: Uri): String? {
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
    }
    return uri.lastPathSegment
}
