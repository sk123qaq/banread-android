package app.banread.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.banread.app.ui.components.CompactActionButton
import app.banread.app.ui.components.SingleLinePill
import app.banread.app.ui.theme.BanReadTheme
import app.banread.model.ProviderKind
import app.banread.model.ProviderPresetGroup
import app.banread.model.ThinkingLevel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ByokEditorDialog(
    editor: ByokEditorState,
    onApplyPreset: (String) -> Unit,
    onKindChange: (ProviderKind) -> Unit,
    onEndpointChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onToggleKeyVisibility: () -> Unit,
    onModelIdChange: (String) -> Unit,
    onFetchModels: () -> Unit,
    onSelectDiscoveredModel: (app.banread.model.DiscoveredModel) -> Unit,
    onThinkingLevelChange: (ThinkingLevel) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onVisionChange: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = BanReadTheme.palette
    val presetsByGroup = SettingsViewModel.presetsByGroup()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = {
            Text(
                text = if (editor.isEditMode) "编辑 BYOK 模型" else "添加 BYOK 模型",
                color = palette.ink,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("服务商预设", color = palette.ink2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                listOf(ProviderPresetGroup.AGGREGATOR, ProviderPresetGroup.OFFICIAL_OR_LOCAL).forEach { grp ->
                    Text(grp.displayLabel, color = palette.ink3, fontSize = 11.sp)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        presetsByGroup[grp].orEmpty().forEach { preset ->
                            val selected = editor.selectedPresetId == preset.id
                            SingleLinePill(
                                text = preset.displayName,
                                containerColor = if (selected) palette.cinnabar else palette.surfaceSubtle,
                                contentColor = if (selected) Color.White else palette.ink,
                                borderColor = if (selected) palette.cinnabar else palette.lineStrong,
                                onClick = { onApplyPreset(preset.id) },
                            )
                        }
                    }
                }

                Text("API 协议", color = palette.ink2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        ProviderKind.OPENAI_COMPAT to "OpenAI 兼容",
                        ProviderKind.GEMINI to "Gemini",
                        ProviderKind.ANTHROPIC to "Anthropic",
                        ProviderKind.OLLAMA to "Ollama",
                    ).forEach { (kind, label) ->
                        val selected = editor.kind == kind
                        SingleLinePill(
                            text = label,
                            containerColor = if (selected) palette.ink else palette.surfaceSubtle,
                            contentColor = if (selected) palette.surface else palette.ink,
                            borderColor = if (selected) palette.ink else palette.lineStrong,
                            onClick = { onKindChange(kind) },
                        )
                    }
                }

                OutlinedTextField(
                    value = editor.endpoint,
                    onValueChange = onEndpointChange,
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = editor.modelsEndpointPreview,
                    color = palette.ink3,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp,
                )

                OutlinedTextField(
                    value = editor.apiKeyInput.raw,
                    onValueChange = onApiKeyChange,
                    enabled = editor.requiresApiKey || !editor.apiKeyInput.isBlank,
                    label = {
                        Text(
                            when {
                                !editor.requiresApiKey -> "API Key（本地 Ollama 免填）"
                                editor.isEditMode && editor.hasExistingStoredKey -> "API Key（留空保留原密钥，输入新 Key 可覆盖）"
                                else -> "API Key（Tink + Keystore 加密）"
                            },
                        )
                    },
                    placeholder = {
                        Text(
                            when {
                                !editor.requiresApiKey -> "本地免 Key"
                                editor.isEditMode && editor.hasExistingStoredKey -> "输入新 Key 可覆盖"
                                else -> "sk-..."
                            },
                        )
                    },
                    singleLine = true,
                    visualTransformation = if (editor.apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = onToggleKeyVisibility) {
                            Text(if (editor.apiKeyVisible) "隐藏" else "显示", fontSize = 11.sp)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = editor.modelId,
                        onValueChange = onModelIdChange,
                        label = { Text("模型 ID") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    CompactActionButton(
                        label = if (editor.isFetchingModels) "查询中…" else "查询可用模型",
                        enabled = !editor.isFetchingModels,
                        onClick = onFetchModels,
                    )
                }

                if (editor.isFetchingModels) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text("正在拉取可用模型…", color = palette.ink3, fontSize = 11.5.sp)
                    }
                }
                editor.fetchStatusMessage?.let { msg ->
                    Text(msg, color = palette.pine, fontSize = 11.5.sp)
                }
                editor.fetchErrorMessage?.let { err ->
                    Text(err, color = palette.danger, fontSize = 11.5.sp)
                }

                if (editor.discoveredModels.isNotEmpty()) {
                    val candidates = editor.filteredDiscoveredModels
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (candidates.isEmpty()) {
                            Text("无匹配候选，可直接使用当前输入 ID", color = palette.ink3, fontSize = 11.sp)
                        } else {
                            candidates.take(40).forEach { model ->
                                val picked = editor.modelId == model.id
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectDiscoveredModel(model) },
                                    color = if (picked) palette.cinnabarSoft else palette.paper,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, if (picked) palette.cinnabar else palette.line),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = model.id,
                                            color = palette.ink,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.5.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (model.supportsVision) {
                                            SingleLinePill(
                                                text = "Vision",
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
                }

                Text("思考等级（Thinking Level）", color = palette.ink2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ThinkingLevel.entries.forEach { level ->
                        val selected = editor.thinkingLevel == level
                        SingleLinePill(
                            text = level.labelZh,
                            containerColor = if (selected) palette.cinnabar else palette.surfaceSubtle,
                            contentColor = if (selected) Color.White else palette.ink,
                            borderColor = if (selected) palette.cinnabar else palette.lineStrong,
                            onClick = { onThinkingLevelChange(level) },
                        )
                    }
                }
                Text(
                    text = editor.thinkingMappingHint,
                    color = palette.ink3,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp,
                )

                OutlinedTextField(
                    value = editor.displayName,
                    onValueChange = onDisplayNameChange,
                    label = { Text("显示名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "支持视觉图片理解 (Vision)",
                        color = palette.ink,
                        fontSize = 12.5.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = editor.supportsVision,
                        onCheckedChange = onVisionChange,
                    )
                }

                editor.validationError?.let { err ->
                    Text(err, color = palette.danger, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }

                if (editor.isEditMode) {
                    Spacer(Modifier.height(2.dp))
                    TextButton(
                        onClick = onRemove,
                        modifier = Modifier.align(Alignment.Start),
                    ) {
                        Text("移除此模型", color = palette.danger, fontSize = 12.5.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                enabled = !editor.isSaving,
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.cinnabar,
                    contentColor = Color.White,
                ),
            ) {
                Text("加密保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = palette.ink2)
            }
        },
    )
}
