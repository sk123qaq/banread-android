package app.banread.app.ui.reader

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.banread.app.ui.components.SingleLinePill
import app.banread.app.ui.theme.BanReadPalettes
import app.banread.app.ui.theme.BanReadTheme
import app.banread.model.ReaderTheme
import app.banread.model.TypographyProfile
import app.banread.reader.PaginationMode
import app.banread.reader.RegisteredFont
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun TypographySheet(
    state: ReflowReaderUiState,
    onUpdateTypography: (TypographyProfile) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val typo = state.typography
    ReaderSheetFrame(
        title = "Aa 排版",
        subtitle = "仅排版四项 · 拖动或步进，上方正文实时联动",
        onClose = onClose,
        modifier = modifier,
        scrollable = false,
    ) {
        TypoSliderRow(
            label = "字号",
            value = typo.fontSizeSp,
            range = 12f..36f,
            step = 1f,
            display = typo.fontSizeSp.roundToInt().toString(),
            onValueChange = { onUpdateTypography(typo.copy(fontSizeSp = it)) },
        )
        TypoSliderRow(
            label = "行高",
            value = typo.lineHeightMultiplier,
            range = 1.2f..2.4f,
            step = 0.02f,
            display = String.format(Locale.US, "%.2f", typo.lineHeightMultiplier),
            onValueChange = { onUpdateTypography(typo.copy(lineHeightMultiplier = it)) },
        )
        TypoSliderRow(
            label = "段距",
            value = typo.paragraphSpacingDp,
            range = 0f..28f,
            step = 1f,
            display = typo.paragraphSpacingDp.roundToInt().toString(),
            onValueChange = { onUpdateTypography(typo.copy(paragraphSpacingDp = it)) },
        )
        TypoSliderRow(
            label = "页边距",
            value = typo.horizontalMarginDp,
            range = 8f..40f,
            step = 1f,
            display = typo.horizontalMarginDp.roundToInt().toString(),
            onValueChange = { onUpdateTypography(typo.copy(horizontalMarginDp = it)) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeSheet(
    state: ReflowReaderUiState,
    onSelectTheme: (ReaderTheme) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BanReadTheme.palette
    val selectedPal = BanReadPalettes.forTheme(state.theme)
    ReaderSheetFrame(
        title = "主题",
        subtitle = "5 套纸色 · 点选即换，正文与界面同步",
        onClose = onClose,
        modifier = modifier,
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            themeOrder.forEach { option ->
                val pal = BanReadPalettes.forTheme(option)
                val selected = state.theme == option
                Surface(
                    modifier = Modifier.clickable { onSelectTheme(option) },
                    color = if (selected) palette.cinnabarSoft else palette.surfaceSubtle,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(
                        width = if (selected) 1.5.dp else 1.dp,
                        color = if (selected) palette.cinnabar else palette.lineStrong,
                    ),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(15.dp)
                                    .clip(CircleShape)
                                    .background(pal.paperReading)
                                    .border(1.dp, pal.ink, CircleShape),
                            )
                            Text(
                                text = pal.labelZh,
                                color = palette.ink,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            ThemeDot(pal.paper)
                            ThemeDot(pal.paperReading)
                            ThemeDot(pal.ink)
                            ThemeDot(pal.cinnabar)
                        }
                    }
                }
            }
        }

        HorizontalDivider(thickness = 0.75.dp, color = palette.line)

        Text("配色", color = palette.ink2, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            PaletteLine("纸", selectedPal.paper)
            PaletteLine("面", selectedPal.paperReading)
            PaletteLine("墨", selectedPal.ink)
            PaletteLine("砂", selectedPal.cinnabar)
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = selectedPal.paperReading,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, selectedPal.line),
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    text = "午后三点，光从窗棂斜进来。",
                    color = selectedPal.ink,
                    fontFamily = FontFamily.Serif,
                    fontSize = 12.5.sp,
                    lineHeight = 20.sp,
                )
                Text(
                    text = "—— 样章预览",
                    color = selectedPal.ink2,
                    fontSize = 9.sp,
                )
            }
        }

        Text(
            text = "亮度由系统控制；夜墨与纯黑承担夜间阅读，墨水屏强制关闭动画与阴影。",
            color = palette.ink3,
            fontSize = 9.5.sp,
            lineHeight = 13.sp,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoreSheet(
    state: ReflowReaderUiState,
    onUpdateTypography: (TypographyProfile) -> Unit,
    onSelectPagination: (PaginationMode) -> Unit,
    onToggleVolumeTurn: (Boolean) -> Unit,
    onPickCustomFont: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BanReadTheme.palette
    val typo = state.typography
    val eink = state.theme == ReaderTheme.EINK_HIGH_CONTRAST
    ReaderSheetFrame(
        title = "更多",
        subtitle = "字体 · 阅读交互 · 翻页动画",
        onClose = onClose,
        modifier = modifier,
    ) {
        Text("字体", color = palette.ink2, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
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
                FontPill(
                    registered = reg,
                    selected = typo.fontFamily == reg.cssFontFamily,
                    onClick = { onUpdateTypography(typo.copy(fontFamily = reg.cssFontFamily)) },
                )
            }
            SingleLinePill(
                text = "+ 导入字体",
                containerColor = palette.surface,
                contentColor = palette.cinnabar,
                borderColor = palette.cinnabar,
                onClick = onPickCustomFont,
            )
        }

        SwitchRow(
            title = "中文标点挤压",
            subtitle = "标点与字距更紧凑",
            checked = typo.squeezeChinesePunctuation,
            onCheckedChange = { onUpdateTypography(typo.copy(squeezeChinesePunctuation = it)) },
        )
        SwitchRow(
            title = "音量键翻页",
            subtitle = "音量键作为上一页 / 下一页",
            checked = state.volumeKeyPageTurn,
            onCheckedChange = onToggleVolumeTurn,
        )

        Text("翻页动画", color = palette.ink2, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
        if (eink) {
            SingleLinePill(
                text = "无动画（墨水屏锁定）",
                containerColor = palette.cinnabar,
                contentColor = Color.White,
                borderColor = palette.cinnabar,
            )
            Text(
                text = "墨水屏主题下自动锁定无动画。",
                color = palette.ink3,
                fontSize = 9.5.sp,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                paginationOptions.forEach { (mode, label) ->
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
private fun ReaderSheetFrame(
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = BanReadTheme.palette
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        color = palette.surface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 10.dp, bottomEnd = 10.dp),
        border = BorderStroke(palette.borderWidth, palette.lineStrong),
        shadowElevation = if (palette.isEink) 0.dp else 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(34.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(palette.lineStrong),
                )
            }
            Spacer(Modifier.height(5.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = palette.ink,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = subtitle,
                        color = palette.ink3,
                        fontSize = 9.5.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(26.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭面板",
                        tint = palette.ink2,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            if (scrollable) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = content,
                )
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.SpaceEvenly,
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun TypoSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    display: String,
    onValueChange: (Float) -> Unit,
) {
    val palette = BanReadTheme.palette
    val clamped = value.coerceIn(range.start, range.endInclusive)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            color = palette.ink2,
            fontSize = 10.5.sp,
            modifier = Modifier.width(34.dp),
            maxLines = 1,
            softWrap = false,
        )
        StepButton(
            add = false,
            enabled = clamped > range.start,
            description = "减小$label",
            onClick = { onValueChange((clamped - step).coerceIn(range.start, range.endInclusive)) },
        )
        Slider(
            value = clamped,
            onValueChange = onValueChange,
            valueRange = range,
            steps = (((range.endInclusive - range.start) / step).roundToInt() - 1).coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = palette.cinnabar,
                activeTrackColor = palette.cinnabar,
                inactiveTrackColor = palette.line,
            ),
            modifier = Modifier
                .weight(1f)
                .height(26.dp),
        )
        StepButton(
            add = true,
            enabled = clamped < range.endInclusive,
            description = "增大$label",
            onClick = { onValueChange((clamped + step).coerceIn(range.start, range.endInclusive)) },
        )
        Text(
            text = display,
            color = palette.ink,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.width(32.dp),
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun StepButton(
    add: Boolean,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    val palette = BanReadTheme.palette
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(24.dp)
            .semantics { contentDescription = description },
    ) {
        Text(
            text = if (add) "+" else "−",
            color = if (enabled) palette.ink2 else palette.ink3,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val palette = BanReadTheme.palette
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = palette.ink, fontSize = 11.5.sp)
            Text(subtitle, color = palette.ink3, fontSize = 8.5.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun FontPill(
    registered: RegisteredFont,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val palette = BanReadTheme.palette
    SingleLinePill(
        text = registered.displayName,
        containerColor = if (selected) palette.cinnabar else palette.surfaceSubtle,
        contentColor = if (selected) Color.White else palette.ink,
        borderColor = if (selected) palette.cinnabar else palette.lineStrong,
        onClick = onClick,
    )
}

@Composable
private fun ThemeDot(color: Color) {
    val palette = BanReadTheme.palette
    Box(
        modifier = Modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(color)
            .border(0.5.dp, palette.line, CircleShape),
    )
}

@Composable
private fun PaletteLine(label: String, color: Color) {
    val palette = BanReadTheme.palette
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = palette.ink3, fontSize = 8.5.sp)
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .size(11.dp)
                .clip(CircleShape)
                .background(color)
                .border(0.5.dp, palette.lineStrong, CircleShape),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = String.format("#%06X", 0xFFFFFF and color.toArgb()),
            color = palette.ink3,
            fontSize = 7.5.sp,
            maxLines = 1,
        )
    }
}

private val themeOrder = listOf(
    ReaderTheme.SEPIA_WARM,
    ReaderTheme.PAPER_LIGHT,
    ReaderTheme.SLATE_NIGHT,
    ReaderTheme.AMOLED_BLACK,
    ReaderTheme.EINK_HIGH_CONTRAST,
)

private val builtinFonts = listOf(
    "Noto Serif CJK SC" to "思源宋体",
    "LXGW WenKai" to "霞鹜文楷",
    "Noto Sans CJK SC" to "现代黑体",
)

private val paginationOptions = listOf(
    PaginationMode.SLIDE to "滑动",
    PaginationMode.COVER to "覆盖",
    PaginationMode.NONE to "无动画",
    PaginationMode.VERTICAL_SCROLL to "滚屏",
)
