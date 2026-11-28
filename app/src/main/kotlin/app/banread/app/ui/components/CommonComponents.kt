package app.banread.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.banread.app.ui.theme.BanReadTheme
import app.banread.model.BlockId
import app.banread.model.ChapterOrdinal
import app.banread.model.ReadProfileId
import app.banread.model.registry.ReadProfile

enum class MainTab {
    BOOKSHELF,
    SETTINGS,
}

val LITERATURE_SUB_KINDS: List<Pair<String, String>> = listOf(
    "fiction" to "小说 (fiction)",
    "drama" to "戏剧 (drama)",
    "poetry" to "诗歌 (poetry)",
    "essay" to "散文随笔 (essay)",
)

fun formatProfileBadge(
    profileName: String,
    profileId: ReadProfileId,
    literatureSubKind: String?,
): String {
    if (profileId.raw == "literature" && !literatureSubKind.isNullOrBlank()) {
        val label = when (literatureSubKind.lowercase()) {
            "fiction" -> "小说"
            "drama" -> "戏剧"
            "poetry" -> "诗歌"
            "essay" -> "散文随笔"
            else -> literatureSubKind
        }
        return "$profileName · $label"
    }
    return profileName
}

@Composable
fun BanReadSeal(
    size: Dp = 24.dp,
    modifier: Modifier = Modifier,
) {
    val palette = BanReadTheme.palette
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(palette.cinnabar),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "伴",
            color = Color.White,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.56f).sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
fun BanReadTopBar(
    title: String,
    modifier: Modifier = Modifier,
    showSeal: Boolean = false,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val palette = BanReadTheme.palette
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = palette.surface,
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = palette.ink,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                } else if (showSeal) {
                    BanReadSeal(size = 24.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = title,
                    color = palette.ink,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
            HorizontalDivider(thickness = palette.borderWidth, color = palette.line)
        }
    }
}

@Composable
fun BanReadBottomNav(
    activeTab: MainTab,
    onSelectTab: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BanReadTheme.palette
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = palette.surface,
        shadowElevation = 0.dp,
    ) {
        Column {
            HorizontalDivider(thickness = palette.borderWidth, color = palette.line)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(54.dp)
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BottomNavItem(
                    label = "书架",
                    glyph = "书",
                    selected = activeTab == MainTab.BOOKSHELF,
                    onClick = { onSelectTab(MainTab.BOOKSHELF) },
                    modifier = Modifier.weight(1f),
                )
                BottomNavItem(
                    label = "设置",
                    glyph = null,
                    selected = activeTab == MainTab.SETTINGS,
                    onClick = { onSelectTab(MainTab.SETTINGS) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BottomNavItem(
    label: String,
    glyph: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BanReadTheme.palette
    val tint = if (selected) palette.cinnabar else palette.ink3
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (glyph != null) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .border(
                        width = if (selected) 1.5.dp else 1.dp,
                        color = tint,
                        shape = RoundedCornerShape(4.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = glyph,
                    color = tint,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        } else {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
fun BanReadSurfaceCard(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    padding: PaddingValues = PaddingValues(12.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = BanReadTheme.palette
    val borderColor = when {
        highlighted -> palette.cinnabar
        palette.isEink -> Color.Black
        else -> palette.line
    }
    val borderW = if (highlighted && !palette.isEink) 1.5.dp else palette.borderWidth
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = palette.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(borderW, borderColor),
        shadowElevation = palette.cardElevation,
    ) {
        Column(modifier = Modifier.padding(padding), content = content)
    }
}

@Composable
fun SingleLinePill(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    contentColor: Color? = null,
    borderColor: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val palette = BanReadTheme.palette
    val bg = containerColor ?: palette.surfaceSubtle
    val fg = contentColor ?: palette.ink2
    val border = borderColor ?: palette.line
    Surface(
        modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = bg,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(if (palette.isEink) 1.dp else 0.75.dp, border),
        shadowElevation = 0.dp,
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun PerspectivePickerDialog(
    profiles: List<ReadProfile>,
    currentProfileId: ReadProfileId,
    currentSubKind: String?,
    literatureSubKindHints: Map<String, String>,
    onSelect: (ReadProfileId, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = BanReadTheme.palette
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        titleContentColor = palette.ink,
        textContentColor = palette.ink2,
        title = {
            Text(
                text = "切换伴读阅读视角（6+1）",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                profiles.forEach { profile ->
                    val isSelected = profile.readType == currentProfileId
                    val isLit = profile.readType.raw == "literature"
                    BanReadSurfaceCard(
                        highlighted = isSelected,
                        padding = PaddingValues(10.dp),
                        onClick = {
                            val nextSub = if (isLit) {
                                currentSubKind?.takeIf { it.isNotBlank() } ?: "fiction"
                            } else {
                                null
                            }
                            onSelect(profile.readType, nextSub)
                        },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = profile.adlerCategory.ifBlank { profile.name },
                                color = palette.ink,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
                                modifier = Modifier.weight(1f),
                            )
                            if (isSelected) {
                                SingleLinePill(
                                    text = "当前",
                                    containerColor = palette.cinnabarSoft,
                                    contentColor = palette.cinnabar,
                                    borderColor = palette.cinnabar,
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = profile.goal,
                            color = palette.ink2,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                        if (isLit) {
                            Spacer(Modifier.height(8.dp))
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                LITERATURE_SUB_KINDS.forEach { (key, label) ->
                                    val subSel = isSelected && (currentSubKind ?: "fiction") == key
                                    SingleLinePill(
                                        text = label,
                                        containerColor = if (subSel) palette.cinnabar else palette.surfaceSubtle,
                                        contentColor = if (subSel) Color.White else palette.ink,
                                        borderColor = if (subSel) palette.cinnabar else palette.lineStrong,
                                        onClick = { onSelect(profile.readType, key) },
                                    )
                                }
                            }
                            val activeHint = literatureSubKindHints[currentSubKind ?: "fiction"]
                            if (!activeHint.isNullOrBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = activeHint,
                                    color = palette.ink3,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                )
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

private val BLOCK_CHIP_REGEX = Regex("""`?\[(B\d+)\]`?""")
private val HEADING_PREFIX = Regex("^#{1,6} ")
private val DIVIDER_LINE = Regex("^(?:-{3,}|\\*{3,}|_{3,})$")

internal fun markdownHeadingText(line: String): String? =
    if (HEADING_PREFIX.containsMatchIn(line)) HEADING_PREFIX.replace(line, "") else null

internal fun isMarkdownDivider(line: String): Boolean = DIVIDER_LINE.matches(line)

/**
 * Renders prose with `[原文]` / `[补充]` provenance coloring and clickable `[Bxx]` block chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClickableBlockMarkdownText(
    markdown: String,
    onBlockJump: (BlockId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BanReadTheme.palette
    val lines = markdown.replace("\r\n", "\n").split('\n')

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        lines.forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            if (isMarkdownDivider(line)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(palette.line)
                )
                return@forEach
            }

            val headingText = markdownHeadingText(line)
            val isHeading = headingText != null
            val cleanLine = when {
                headingText != null -> headingText
                line.startsWith("- ") -> "• " + line.removePrefix("- ")
                line.startsWith("* ") -> "• " + line.removePrefix("* ")
                else -> line
            }

            val blockRefs = BLOCK_CHIP_REGEX.findAll(cleanLine)
                .mapNotNull { m -> runCatching { BlockId(m.groupValues[1]) }.getOrNull() }
                .distinctBy { it.value }
                .toList()

            val displayText = cleanLine
                .replace("**", "")
                .replace("`", "")

            val annotated = buildAnnotatedString {
                var idx = 0
                val tagRegex = Regex("""\[(原文|补充|举例)\]""")
                for (match in tagRegex.findAll(displayText)) {
                    if (match.range.first > idx) {
                        append(displayText.substring(idx, match.range.first))
                    }
                    val tag = match.groupValues[1]
                    val tagColor = if (tag == "原文") palette.pine else palette.amber
                    withStyle(SpanStyle(color = tagColor, fontWeight = FontWeight.Bold)) {
                        append("[$tag]")
                    }
                    idx = match.range.last + 1
                }
                if (idx < displayText.length) {
                    append(displayText.substring(idx))
                }
            }

            Text(
                text = annotated,
                color = palette.ink,
                fontSize = if (isHeading) 14.sp else 13.sp,
                fontWeight = if (isHeading) FontWeight.Bold else FontWeight.Normal,
                fontFamily = if (isHeading) FontFamily.Serif else FontFamily.Default,
                lineHeight = 20.sp,
            )

            if (blockRefs.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    blockRefs.forEach { bid ->
                        SingleLinePill(
                            text = "↗ ${bid.value}",
                            containerColor = palette.cinnabarSoft,
                            contentColor = palette.cinnabar,
                            borderColor = palette.cinnabar,
                            onClick = { onBlockJump(bid) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CompactActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val palette = BanReadTheme.palette
    if (primary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(34.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.cinnabar,
                contentColor = Color.White,
            ),
        ) {
            Text(text = label, fontSize = 12.sp, maxLines = 1, softWrap = false)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(34.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, palette.lineStrong),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = palette.surface,
                contentColor = palette.ink,
            ),
        ) {
            Text(text = label, fontSize = 12.sp, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
fun SmallIconPillButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BanReadTheme.palette
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = palette.surface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, palette.lineStrong),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = palette.ink,
                    modifier = Modifier.size(15.dp),
                )
            }
            Text(
                text = label,
                color = palette.ink,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

internal val IconAdd = Icons.Filled.Add
internal val IconCheck = Icons.Filled.Check
internal val IconClose = Icons.Filled.Close
internal val IconLock = Icons.Filled.Lock

@Composable
fun RemoteCursorBannerCard(
    message: String,
    onJump: () -> Unit,
    onIgnore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BanReadTheme.palette
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = palette.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(palette.borderWidth, palette.pine),
        shadowElevation = palette.cardElevation,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = message,
                color = palette.ink,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            SingleLinePill(
                text = "跳转",
                containerColor = palette.cinnabar,
                contentColor = Color.White,
                borderColor = palette.cinnabar,
                onClick = onJump,
            )
            SingleLinePill(
                text = "忽略",
                containerColor = palette.surfaceSubtle,
                contentColor = palette.ink2,
                borderColor = palette.lineStrong,
                onClick = onIgnore,
            )
        }
    }
}
