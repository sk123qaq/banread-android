package app.banread.app.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.banread.app.ui.bookshelf.AdaptiveBookCover
import app.banread.app.ui.components.BanReadSurfaceCard
import app.banread.app.ui.components.BanReadTopBar
import app.banread.app.ui.components.CompactActionButton
import app.banread.app.ui.components.ForwardJumpDialog
import app.banread.app.ui.components.PerspectivePickerDialog
import app.banread.app.ui.components.SingleLinePill
import app.banread.app.ui.components.formatProfileBadge
import app.banread.app.ui.theme.BanReadTheme
import app.banread.model.ChapterOrdinal
import app.banread.model.ChapterOutlineItem
import app.banread.model.ReadingSurfaceMode

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookDetailScreen(
    viewModel: BookDetailViewModel,
    onBack: () -> Unit,
    onNavigateToReader: (ReadingSurfaceMode) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val palette = BanReadTheme.palette

    LaunchedEffect(state.pendingNavigationMode) {
        val mode = state.pendingNavigationMode ?: return@LaunchedEffect
        viewModel.consumePendingNavigation()
        onNavigateToReader(mode)
    }

    val activeProfile = state.activeProfile
    val outline = state.outline
    val summary = state.summary
    val badgeText = if (activeProfile != null) {
        formatProfileBadge(
            profileName = activeProfile.name,
            profileId = activeProfile.readType,
            literatureSubKind = outline?.literatureSubKind ?: summary?.literatureSubKind,
        )
    } else {
        "阅读视角"
    }

    Scaffold(
        containerColor = palette.paper,
        topBar = {
            BanReadTopBar(
                title = "书页详情与目录",
                onBack = onBack,
                actions = {
                    SingleLinePill(
                        text = "$badgeText ▾",
                        containerColor = palette.cinnabarSoft,
                        contentColor = palette.cinnabar,
                        borderColor = palette.cinnabar,
                        onClick = viewModel::openPerspectivePicker,
                    )
                },
            )
        },
    ) { innerPadding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = palette.cinnabar)
            }
        } else if (outline == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.errorMessage ?: "无法加载书籍目录",
                    color = palette.danger,
                )
            }
        } else {
            val unlockedBody = outline.bodyChapters.filter {
                state.fullBookUnlocked || it.ordinal <= state.currentWatermark
            }
            val lockedBody = outline.bodyChapters.filter {
                !state.fullBookUnlocked && it.ordinal > state.currentWatermark
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Header card with 2:3 cover + metadata + 6+1 perspective badge
                item(key = "hero") {
                    BanReadSurfaceCard(padding = PaddingValues(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            if (summary != null) {
                                AdaptiveBookCover(
                                    work = summary,
                                    modifier = Modifier
                                        .width(96.dp)
                                        .aspectRatio(2f / 3f),
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = "《${outline.title}》",
                                    color = palette.ink,
                                    fontFamily = FontFamily.Serif,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp,
                                    lineHeight = 22.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = summary?.author?.ifBlank { "未知作者" } ?: "",
                                    color = palette.ink2,
                                    fontSize = 12.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    if (summary != null) {
                                        SingleLinePill(
                                            text = summary.format.uppercase(),
                                            containerColor = palette.surfaceSubtle,
                                            contentColor = palette.ink2,
                                            borderColor = palette.lineStrong,
                                        )
                                    }
                                    SingleLinePill(
                                        text = "$badgeText（6+1 视角 ▾）",
                                        containerColor = palette.cinnabarSoft,
                                        contentColor = palette.cinnabar,
                                        borderColor = palette.cinnabar,
                                        onClick = viewModel::openPerspectivePicker,
                                    )
                                }
                                Text(
                                    text = "正文 ${outline.bodyChapters.size} 章 · 防剧透锁至第 ${state.currentWatermark.value} 章",
                                    color = palette.ink3,
                                    fontSize = 11.5.sp,
                                )
                                Spacer(Modifier.height(2.dp))
                                CompactActionButton(
                                    label = "继续阅读 →",
                                    primary = true,
                                    onClick = viewModel::onContinueReadingClicked,
                                )
                            }
                        }
                    }
                }

                // Unlocked body chapters
                items(unlockedBody, key = { "body_${it.ordinal.value}" }) { chapter ->
                    ChapterOutlineRow(
                        item = chapter,
                        isCurrent = chapter.ordinal == state.currentWatermark,
                        isLocked = false,
                        onClick = { viewModel.onChapterClicked(chapter) },
                    )
                }

                // Watermark divider when there are chapters beyond watermark
                if (lockedBody.isNotEmpty()) {
                    item(key = "watermark_divider") {
                        WatermarkDividerRow(watermark = state.currentWatermark)
                    }
                    items(lockedBody, key = { "locked_${it.ordinal.value}" }) { chapter ->
                        ChapterOutlineRow(
                            item = chapter,
                            isCurrent = false,
                            isLocked = true,
                            onClick = { viewModel.onChapterClicked(chapter) },
                        )
                    }
                }

                // Notes chapters section if present
                if (outline.notesChapters.isNotEmpty()) {
                    item(key = "notes_header") {
                        Text(
                            text = "全书注释与考据（${outline.notesChapters.size}）",
                            color = palette.ink2,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    items(outline.notesChapters, key = { "notes_${it.ordinal.value}" }) { noteCh ->
                        ChapterOutlineRow(
                            item = noteCh,
                            isCurrent = false,
                            isLocked = false,
                            badgeOverride = "注释",
                            onClick = { viewModel.onChapterClicked(noteCh) },
                        )
                    }
                }

                // Collapsed frontBackChapters capsule
                if (outline.frontBackChapters.isNotEmpty()) {
                    item(key = "front_back_capsule") {
                        FrontBackCollapsibleSection(
                            items = outline.frontBackChapters,
                            expanded = state.frontBackExpanded,
                            onToggle = viewModel::toggleFrontBackExpanded,
                            onItemClick = viewModel::onChapterClicked,
                        )
                    }
                }
            }
        }
    }

    if (state.showPerspectivePicker && activeProfile != null) {
        PerspectivePickerDialog(
            profiles = state.allProfiles,
            currentProfileId = activeProfile.readType,
            currentSubKind = outline?.literatureSubKind ?: summary?.literatureSubKind,
            literatureSubKindHints = state.literatureSubKindHints,
            onSelect = { profileId, subKind ->
                viewModel.selectPerspective(profileId, subKind)
            },
            onDismiss = viewModel::closePerspectivePicker,
        )
    }

    val pendingJump = state.pendingForwardJump
    if (pendingJump != null) {
        ForwardJumpDialog(
            targetOrdinal = pendingJump.ordinal,
            targetTitle = pendingJump.title,
            currentWatermark = state.currentWatermark,
            onAdvanceAndJump = viewModel::confirmForwardJumpAdvance,
            onPreviewOnly = viewModel::confirmForwardJumpPreviewOnly,
            onDismiss = viewModel::dismissForwardJumpDialog,
        )
    }
}

@Composable
private fun ChapterOutlineRow(
    item: ChapterOutlineItem,
    isCurrent: Boolean,
    isLocked: Boolean,
    badgeOverride: String? = null,
    onClick: () -> Unit,
) {
    val palette = BanReadTheme.palette
    val borderColor = when {
        isCurrent -> palette.cinnabar
        palette.isEink -> Color.Black
        else -> palette.line
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (isCurrent) palette.cinnabarSoft else palette.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(if (isCurrent) 1.5.dp else palette.borderWidth, borderColor),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = item.ordinal.twoDigit(),
                color = if (isCurrent) palette.cinnabar else palette.ink3,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.5.sp,
                maxLines = 1,
                softWrap = false,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    color = if (isLocked) palette.ink2 else palette.ink,
                    fontSize = 13.5.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.estimatedMinutes > 0 || item.merged) {
                    val sub = buildString {
                        if (item.estimatedMinutes > 0) append("约 ${item.estimatedMinutes} 分钟")
                        if (item.merged) {
                            if (isNotEmpty()) append(" · ")
                            append("合并节")
                        }
                    }
                    Text(
                        text = sub,
                        color = palette.ink3,
                        fontSize = 11.sp,
                    )
                }
            }
            when {
                badgeOverride != null -> SingleLinePill(
                    text = badgeOverride,
                    containerColor = palette.pineSoft,
                    contentColor = palette.pine,
                    borderColor = palette.pine,
                )
                isCurrent -> SingleLinePill(
                    text = "在读",
                    containerColor = palette.cinnabar,
                    contentColor = Color.White,
                    borderColor = palette.cinnabar,
                )
                isLocked -> SingleLinePill(
                    text = "🔒 未读",
                    containerColor = palette.surfaceSubtle,
                    contentColor = palette.ink3,
                    borderColor = palette.line,
                )
            }
        }
    }
}

@Composable
private fun WatermarkDividerRow(watermark: ChapterOrdinal) {
    val palette = BanReadTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = palette.cinnabar.copy(alpha = 0.4f))
        Row(
            modifier = Modifier
                .background(palette.amberSoft, RoundedCornerShape(999.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = palette.amber,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = "防剧透水位线 · 以上锁至第 ${watermark.value} 章",
                color = palette.amber,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
        }
        HorizontalDivider(modifier = Modifier.weight(1f), color = palette.cinnabar.copy(alpha = 0.4f))
    }
}

@Composable
private fun FrontBackCollapsibleSection(
    items: List<ChapterOutlineItem>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onItemClick: (ChapterOutlineItem) -> Unit,
) {
    val palette = BanReadTheme.palette
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
            color = palette.surfaceSubtle,
            shape = RoundedCornerShape(999.dp),
            border = BorderStroke(palette.borderWidth, palette.lineStrong),
            shadowElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "前置/版权等水章 · ${items.size} 节 [${if (expanded) "收起" else "展开"}]",
                    color = palette.ink2,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = palette.ink2,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = palette.tweenSpec()),
            exit = shrinkVertically(animationSpec = palette.tweenSpec()),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEach { fb ->
                    ChapterOutlineRow(
                        item = fb,
                        isCurrent = false,
                        isLocked = false,
                        badgeOverride = "水章",
                        onClick = { onItemClick(fb) },
                    )
                }
            }
        }
    }
}
