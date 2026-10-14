package com.modradar.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modradar.app.scan.ksu.KsuModuleInfo
import com.modradar.app.scan.xposed.XposedModuleInfo

/**
 * 已安装模块页：Xposed 模块（免 root）+ KSU/Magisk 模块（root 只读）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModulesScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenTask: (String) -> Unit,
) {
    val xposed by vm.xposedModules.collectAsStateWithLifecycle()
    val ksu by vm.ksuModules.collectAsStateWithLifecycle()
    val root by vm.rootAvailable.collectAsStateWithLifecycle()
    val scanning by vm.moduleScanning.collectAsStateWithLifecycle()
    val error by vm.moduleError.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.scanXposedModules()
        vm.scanKsuModules()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("已安装模块") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.scanXposedModules(); vm.scanKsuModules() },
                        enabled = !scanning,
                    ) { Text(if (scanning) "扫描中…" else "重新扫描") }
                }
            }

            error?.let {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2E12))) {
                        Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                SectionHeader("Xposed 模块（${xposed.size}）")
            }
            if (xposed.isEmpty()) {
                item { Hint("未发现 Xposed 模块。") }
            }
            items(xposed) { m -> XposedCard(m) { vm.analyzeInstalledApk(m.apkPath ?: "", m.label, onOpenTask) } }

            item {
                SectionHeader(
                    when (root) {
                        true -> "KSU / Magisk 模块（${ksu.size}）"
                        false -> "KSU / Magisk 模块（需 root）"
                        null -> "KSU / Magisk 模块"
                    }
                )
            }
            if (root == false) {
                item { Hint("未获取 root，无法读取模块目录。zip 包可直接选文件分析。") }
            } else if (ksu.isEmpty()) {
                item { Hint(if (scanning) "扫描中…" else "未发现模块，或模块目录为空。") }
            }
            items(ksu) { m -> KsuCard(m) }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun XposedCard(m: XposedModuleInfo, onAnalyze: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Text(m.label, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "${m.packageName}${m.versionName?.let { " · $it" } ?: ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            m.description?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "激活状态：未知（无公开 API，需 LSPosed 接口）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onAnalyze) { Text("分析此模块") }
        }
    }
}

@Composable
private fun KsuCard(m: KsuModuleInfo) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    when (m.enabled) {
                        true -> "已启用"
                        false -> "已停用"
                        null -> "未知"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (m.enabled == false) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append(m.id)
                    m.version?.let { append(" · $it") }
                    m.author?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            m.description?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (m.scripts.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "脚本：${m.scripts.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (m.missingFields.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "module.prop 缺失：${m.missingFields.joinToString("、")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFFB300),
                )
            }
        }
    }
}
