package com.modradar.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.modradar.app.Navigator
import com.modradar.app.Route
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modradar.app.scan.ksu.KsuModuleInfo
import com.modradar.app.scan.ksu.ScriptRisk
import com.modradar.app.ui.components.Banner
import com.modradar.app.ui.components.EmptyState
import com.modradar.app.ui.components.MrCard
import com.modradar.app.ui.components.Tag
import com.modradar.app.ui.theme.RiskColors

/** KSU / Magisk 模块（root 只读）。 */
@Composable
fun KsuTab(vm: AppViewModel, nav: Navigator) {
    val ksu by vm.ksuModules.collectAsStateWithLifecycle()
    val risks by vm.ksuRisks.collectAsStateWithLifecycle()
    val root by vm.rootAvailable.collectAsStateWithLifecycle()
    val scanning by vm.moduleScanning.collectAsStateWithLifecycle()
    val error by vm.moduleError.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(Unit) { vm.scanKsuModules() }

  Box(Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("/data/adb/modules · root 只读", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                // 进度圈与刷新按钮占同样 48dp，切换时不改变行高
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = { vm.scanKsuModules() }) { Icon(Icons.Outlined.Refresh, "刷新") }
                }
            }
        }
        item {
            when (root) {
                true -> Banner(Icons.Outlined.CheckCircle, "已获取 root · 只读模式，不修改任何文件", RiskColors.Low)
                false -> Banner(Icons.Outlined.ErrorOutline, "未获取 root，无法读取 /data/adb/modules。模块 zip 可在「文件」或「文件夹」页分析", RiskColors.Medium)
                null -> Banner(Icons.Outlined.Info, "正在检测 root…", MaterialTheme.colorScheme.outline)
            }
        }
        error?.let { item { Banner(Icons.Outlined.Warning, it, RiskColors.Critical) } }

        if (root == true && ksu.isEmpty() && !scanning) {
            item { EmptyState(Icons.Outlined.Inventory2, "未发现模块", "/data/adb/modules 与 /data/adb/ksu/modules 均为空") }
        }
        items(ksu, key = { it.dir ?: it.id }) { m ->
            val k = m.dir ?: m.id
            KsuCard(m, risks[m.dir].orEmpty(), k in selected) { selected = if (k in selected) selected - k else selected + k }
        }

        item {
            Text(
                "仅执行 ls / cat / test 三类只读命令；分析时把模块文件只读复制到应用私有目录，不启停、不卸载、不挂载。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        item { SelectionBarSpacer() }
    }
    SelectionBar(selected.size, busy, "读取模块文件…", onClear = { selected = emptySet() }) {
        val pick = ksu.filter { (it.dir ?: it.id) in selected }
        vm.analyzeKsuModules(pick) { ids ->
            selected = emptySet()
            if (ids.size == 1) nav.replace(Route.Task(ids.first())) else nav.replace(Route.History)
        }
    }
  }
}

@Composable
private fun KsuCard(m: KsuModuleInfo, risks: List<String>, checked: Boolean, onToggle: () -> Unit) {
    val disabled = m.enabled == false
    MrCard(
        onClick = onToggle,
        container = if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    m.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                    color = if (disabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                )
                when (m.enabled) {
                    true -> Tag("已启用", RiskColors.Low)
                    false -> Tag("已停用", RiskColors.Neutral)
                    null -> Tag("未知", RiskColors.Neutral)
                }
                Checkbox(checked = checked, onCheckedChange = { onToggle() })
            }
            Text(
                buildString {
                    append(m.id)
                    m.version?.let { append(" · 版本 $it") }
                    m.author?.let { append(" · 作者 $it") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            m.description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (m.scripts.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    m.scripts.take(4).forEach { Tag(it, MaterialTheme.colorScheme.outline) }
                }
            }
            if (m.missingFields.isNotEmpty()) {
                Text("module.prop 缺失：${m.missingFields.joinToString("、")}", style = MaterialTheme.typography.labelSmall, color = RiskColors.Medium)
            }
            risks.sortedBy { ScriptRisk.isNormal(it) }.take(5).forEach { r ->
                // 改属性 / 挂载等是模块本职，只作灰色提示
                val normal = ScriptRisk.isNormal(r)
                val c = if (normal) MaterialTheme.colorScheme.outline else RiskColors.High
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (normal) Icons.Outlined.Info else Icons.Outlined.Warning, null, tint = c, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(r, style = MaterialTheme.typography.labelSmall, color = c)
                }
            }
        }
    }
}
