package com.modradar.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.material.icons.outlined.Public
import com.modradar.app.provenance.ProvenanceSettings
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.platform.LocalContext
import com.modradar.app.ui.theme.ThemeMode
import com.modradar.app.ui.theme.ThemePrefs
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modradar.app.Navigator
import com.modradar.app.Route
import com.modradar.app.ui.components.ListRow
import com.modradar.app.ui.components.MrCard
import com.modradar.app.ui.components.MrTopBar
import com.modradar.app.ui.components.SectionHeader
import com.modradar.app.ui.theme.RiskColors

/** 设置入口页（gkd 风格分组列表）。 */
@Composable
fun SettingsScreen(vm: AppViewModel, nav: Navigator) {
    val ai by vm.aiSettings.collectAsStateWithLifecycle()
    val mcpStatus by vm.mcpStatus.collectAsStateWithLifecycle()
    val tasks by vm.allTasks.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var exportDialog by remember { mutableStateOf(false) }
    var withSecrets by remember { mutableStateOf(false) }
    var withTasks by remember { mutableStateOf(true) }
    var confirmImport by remember { mutableStateOf<Uri?>(null) }
    val ctx = LocalContext.current
    var provOn by remember { mutableStateOf(ProvenanceSettings.enabled(ctx)) }
    val toast by vm.toast.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(toast) { toast?.let { snack.showSnackbar(it); vm.consumeToast() } }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportConfig(uri, withSecrets, withTasks)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmImport = uri
    }

    Scaffold(topBar = { MrTopBar("设置", onBack = { nav.back() }) }, snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader("分析")
            MrCard {
                Column {
                    ListRow(
                        Icons.Outlined.SmartToy, "AI 模型",
                        if (ai.configured) "${ai.model} · ${ai.baseUrl.removePrefix("https://")}" else "未配置 · 本地分析不受影响",
                    ) { nav.go(Route.AiSettings) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ListRow(Icons.Outlined.Hub, "MCP", "Server：$mcpStatus") { nav.go(Route.Mcp) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ListRow(Icons.Outlined.BatteryChargingFull, "后台保活", "通知权限 / 省电豁免 / 厂商白名单") { nav.go(Route.KeepAlive) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ListRow(
                        Icons.Outlined.Public, "联网来源核验",
                        "在 GitHub 查找开源仓库与 release，比对是否官方原版；只发送包名 / 模块名",
                        trailing = { Switch(checked = provOn, onCheckedChange = null) },
                    ) { provOn = !provOn; ProvenanceSettings.set(ctx, provOn) }
                }
            }

            SectionHeader("外观")
            MrCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("主题模式", style = MaterialTheme.typography.titleSmall)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { i, m ->
                            SegmentedButton(
                                selected = ThemePrefs.mode == m,
                                onClick = { ThemePrefs.set(ctx, m) },
                                shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                            ) { Text(m.label) }
                        }
                    }
                }
            }

            SectionHeader("数据")
            MrCard {
                Column {
                    ListRow(Icons.Outlined.Upload, "导出配置", "AI / MCP 设置，可选含历史报告与对话") { exportDialog = true }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ListRow(Icons.Outlined.Download, "导入配置", "从 ModRadar 备份 JSON 恢复") {
                        importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ListRow(Icons.Outlined.Delete, "清空历史记录", "共 ${tasks.size} 个任务（含报告、产物与对话）") {
                        if (tasks.isNotEmpty()) confirmClear = true
                    }
                }
            }

            SectionHeader("其他")
            MrCard { ListRow(Icons.Outlined.Info, "关于", "版本、开源许可、隐私政策、用户协议") { nav.go(Route.About) } }
        }
    }

    if (exportDialog) {
        AlertDialog(
            onDismissRequest = { exportDialog = false },
            title = { Text("导出配置") },
            text = {
                Column {
                    CheckRow("包含任务历史、报告与 AI 对话", withTasks) { withTasks = it }
                    CheckRow("包含 API Key 与 MCP Token", withSecrets) { withSecrets = it }
                    if (withSecrets) Text("备份文件将以明文保存密钥，请勿分享给他人", style = MaterialTheme.typography.labelSmall, color = RiskColors.Medium, modifier = Modifier.padding(start = 12.dp))
                    Text("不包含被分析的 APK 本体。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    exportDialog = false
                    val day = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
                    exporter.launch("modradar-backup-$day.json")
                }) { Text("选择保存位置") }
            },
            dismissButton = { TextButton(onClick = { exportDialog = false }) { Text("取消") } },
        )
    }

    confirmImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmImport = null },
            title = { Text("导入备份？") },
            text = { Text("将覆盖当前 AI / MCP 设置（备份未含密钥时保留本机密钥），同 id 的任务与对话会被替换。") },
            confirmButton = { TextButton(onClick = { confirmImport = null; vm.importConfig(uri) }) { Text("导入") } },
            dismissButton = { TextButton(onClick = { confirmImport = null }) { Text("取消") } },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部历史？") },
            text = { Text("将删除 ${tasks.size} 个任务的报告、产物与 AI 对话，不可恢复。") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; vm.deleteTasks(tasks.map { it.id }) }) {
                    Text("清空", color = RiskColors.Critical)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).toggleable(checked, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(horizontal = 12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
