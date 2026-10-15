package com.modradar.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modradar.app.Navigator
import com.modradar.app.ai.AiPresets
import com.modradar.app.ai.AiSettings
import com.modradar.app.ai.EndpointNormalizer
import com.modradar.app.ai.ThinkingLevels
import com.modradar.app.ui.components.MrCard
import com.modradar.app.ui.components.MrSlider
import com.modradar.app.ui.components.Tag
import com.modradar.app.ui.ime.LocalImeController
import com.modradar.app.ui.ime.optimizedImePadding
import kotlinx.coroutines.launch
import com.modradar.app.ui.components.MrTopBar
import com.modradar.app.ui.components.SectionHeader
import com.modradar.app.ui.theme.MonoStyle
import com.modradar.app.ui.theme.RiskColors

/** AI 设置：表单草稿与已保存值解耦，保存/测试前统一规范化。 */
@Composable
fun AiSettingsScreen(vm: AppViewModel, nav: Navigator) {
    val saved by vm.aiSettings.collectAsStateWithLifecycle()
    val test by vm.testState.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val modelsError by vm.modelsError.collectAsStateWithLifecycle()
    val loadingModels by vm.loadingModels.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }

    // 只在进入页面时用已保存值初始化一次，之后完全由用户输入驱动
    var draft by remember { mutableStateOf(saved) }
    var tempText by remember { mutableStateOf(saved.temperature.toString()) }
    var showKey by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }

    val ime = LocalImeController.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(toast) { toast?.let { snack.showSnackbar(it); vm.consumeToast() } }

    val endpoint = remember(draft.baseUrl) { EndpointNormalizer.normalize(draft.baseUrl) }
    val preset = AiPresets.byId(draft.protocol)
    val dirty = draft.cleaned() != saved.cleaned()
    val canSubmit = endpoint.ok && draft.apiKey.isNotBlank() && draft.model.isNotBlank()

    Scaffold(
        topBar = { MrTopBar("AI 设置", onBack = { scope.launch { ime?.hideAndAwait(); nav.back() } }) },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).optimizedImePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader("模型配置")
            MrCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DropdownField(
                        label = "服务商（均为 OpenAI 兼容协议）",
                        value = preset.label,
                        options = AiPresets.all.map { it.id to it.label },
                    ) { id ->
                        val p = AiPresets.byId(id)
                        draft = draft.copy(
                            protocol = id,
                            baseUrl = p.baseUrl.ifEmpty { draft.baseUrl },
                            model = p.models.firstOrNull() ?: draft.model,
                        )
                    }

                    OutlinedTextField(
                        value = draft.baseUrl,
                        onValueChange = { draft = draft.copy(baseUrl = it) },
                        label = { Text("API 地址") },
                        placeholder = { Text("https://api.example.com/v1") },
                        singleLine = true,
                        isError = draft.baseUrl.isNotBlank() && !endpoint.ok,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        supportingText = {
                            when {
                                draft.baseUrl.isBlank() -> Text("填到版本号为止，如 /v1；误粘 /chat/completions 会自动去掉")
                                !endpoint.ok -> Text(endpoint.error.orEmpty())
                                else -> Column {
                                    Text("实际请求：${endpoint.chatUrl}", style = MonoStyle)
                                    endpoint.warnings.forEach { Text("· $it", color = RiskColors.Medium) }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = draft.apiKey,
                        onValueChange = { draft = draft.copy(apiKey = it) },
                        label = { Text("API Key") },
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showKey) "隐藏" else "显示")
                            }
                        },
                        supportingText = {
                            val cleaned = EndpointNormalizer.cleanKey(draft.apiKey)
                            when {
                                draft.apiKey.isBlank() -> Text("仅保存在本机")
                                cleaned != draft.apiKey -> Text("保存时将自动去除空格 / 换行 / Bearer 前缀", color = RiskColors.Medium)
                                else -> Text("${EndpointNormalizer.maskKey(cleaned)} · 仅保存在本机")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Box {
                        OutlinedTextField(
                            value = draft.model,
                            onValueChange = { draft = draft.copy(model = it) },
                            label = { Text("模型") },
                            singleLine = true,
                            trailingIcon = {
                                Row {
                                    if (loadingModels) CircularProgressIndicator(Modifier.size(20.dp).padding(2.dp), strokeWidth = 2.dp)
                                    else IconButton(onClick = { vm.fetchModels(draft); modelMenu = true }, enabled = endpoint.ok && draft.apiKey.isNotBlank()) {
                                        Icon(Icons.Outlined.CloudDownload, "拉取模型列表")
                                    }
                                    if (models.isNotEmpty()) IconButton(onClick = { modelMenu = true }) { Icon(Icons.Outlined.ArrowDropDown, "选择模型") }
                                }
                            },
                            supportingText = {
                                Text(modelsError ?: if (models.isNotEmpty()) "已拉取 ${models.size} 个模型，点击右侧箭头选择" else "点击云图标从接口拉取可用模型")
                            },
                            isError = modelsError != null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        DropdownMenu(expanded = modelMenu && models.isNotEmpty(), onDismissRequest = { modelMenu = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                            models.forEach { m ->
                                DropdownMenuItem(text = { Text(m) }, onClick = { draft = draft.copy(model = m); modelMenu = false })
                            }
                        }
                    }
                    if (preset.models.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            preset.models.take(3).forEach { m ->
                                SuggestionChip(onClick = { draft = draft.copy(model = m) }, label = { Text(m) })
                            }
                        }
                    }
                }
            }

            SectionHeader("推理参数")
            MrCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DropdownField(
                        label = "思考等级",
                        value = ThinkingLevels.label(draft.thinkingLevel),
                        options = ThinkingLevels.all,
                    ) { draft = draft.copy(thinkingLevel = it) }
                    Text(
                        "「自动」不发送额外参数，兼容性最好；低/中/高会发送 reasoning_effort，仅部分模型支持",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("温度", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Tag(tempText, MaterialTheme.colorScheme.primary)
                    }
                    MrSlider(
                        value = draft.temperature.toFloat(),
                        onValueChange = {
                            val v = Math.round(it * 10) / 10.0
                            draft = draft.copy(temperature = v); tempText = v.toString()
                        },
                        valueRange = 0f..1.5f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row {
                        Text("稳定 · 适合研判", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text("发散 · 更有创意", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { vm.testConnection(draft) }, enabled = canSubmit && test !is TestState.Running, modifier = Modifier.weight(1f)) {
                    if (test is TestState.Running) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
                    }
                    Text("测试连接")
                }
                Button(onClick = { vm.saveAiSettings(draft) }, enabled = dirty && canSubmit, modifier = Modifier.weight(1f)) {
                    Text(if (dirty) "保存" else "已保存")
                }
            }

            when (val t = test) {
                is TestState.Ok -> ResultCard(Icons.Outlined.CheckCircle, RiskColors.Low, "连接正常", "模型 ${t.report.model} · 延迟 ${t.report.latencyMs} ms\n回复：${t.report.reply}")
                is TestState.Fail -> ResultCard(Icons.Outlined.ErrorOutline, RiskColors.Critical, "连接失败", t.message)
                else -> Unit
            }

            SectionHeader("数据边界")
            MrCard(container = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        "仅上传特征摘要与反编译片段，不上传 APK 本体",
                        "只发往你填写的接口，API Key 仅保存在本机",
                        "不配置 AI 时本地分析照常可用，报告标注「未完成 AI 研判」",
                        "AI 复核本地规则：误报退回、漏检补充扣分；不另给独立分数",
                    ).forEach {
                        Row {
                            Icon(Icons.Outlined.Info, null, Modifier.size(14.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.width(6.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (saved.configured) {
                TextButton(onClick = { vm.clearAiSettings(); draft = AiSettings() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.DeleteOutline, null, tint = RiskColors.Critical); Spacer(Modifier.width(6.dp))
                    Text("清除 AI 配置", color = RiskColors.Critical)
                }
            }
            Spacer(Modifier.navigationBarsPadding().padding(bottom = 12.dp))
        }
    }
}

@Composable
private fun ResultCard(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, title: String, body: String) {
    MrCard(container = color.copy(alpha = 0.12f)) {
        Row(Modifier.padding(14.dp)) {
            Icon(icon, null, tint = color)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, color = color)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** 只读下拉字段：点击整行弹出菜单（不依赖实验性 ExposedDropdownMenu API）。 */
@Composable
fun DropdownField(label: String, value: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null) },
            modifier = Modifier.fillMaxWidth(),
        )
        // 覆盖层接收点击（readOnly 的 TextField 自身会吞掉点击事件）
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(id); open = false })
            }
        }
    }
}
