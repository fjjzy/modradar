package com.modradar.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modradar.app.Navigator
import com.modradar.app.Route
import com.modradar.app.data.db.AnalysisTaskEntity
import com.modradar.app.task.TaskState
import com.modradar.app.ui.components.Banner
import com.modradar.app.ui.components.EmptyState
import com.modradar.app.ui.components.IconBadge
import com.modradar.app.ui.components.MrCard
import com.modradar.app.ui.components.MrTopBar
import com.modradar.app.ui.components.ScoreRing
import com.modradar.app.ui.components.SectionHeader
import com.modradar.app.ui.components.Tag
import com.modradar.app.ui.components.TypeBadge
import com.modradar.app.ui.theme.RiskColors
import com.modradar.app.ui.theme.levelLabel

/** 任务点击：已完成进报告，其余进进度页。 */
fun openTask(nav: Navigator, t: AnalysisTaskEntity) {
    nav.go(if (t.state == TaskState.DONE.name) Route.Report(t.id) else Route.Task(t.id))
}

@Composable
fun DashboardScreen(vm: AppViewModel, nav: Navigator) {
    val tasks by vm.allTasks.collectAsStateWithLifecycle()
    val queued by vm.queued.collectAsStateWithLifecycle()
    val ai by vm.aiSettings.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(toast) {
        toast?.let { snack.showSnackbar(it); vm.consumeToast() }
    }

    Scaffold(
        topBar = {
            MrTopBar("模块雷达") {
                IconButton(onClick = { nav.go(Route.History) }) { Icon(Icons.Outlined.History, "历史记录") }
                IconButton(onClick = { nav.go(Route.Settings) }) { Icon(Icons.Outlined.Settings, "设置") }
            }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { LastScanCard(tasks.firstOrNull { it.score != null }, nav) }

            if (!ai.configured) {
                item {
                    Banner(
                        Icons.Outlined.SmartToy,
                        "未配置 AI：本地分析照常完成，报告会标注「未完成 AI 研判」",
                        MaterialTheme.colorScheme.primary,
                    ) { TextButton(onClick = { nav.go(Route.AiSettings) }) { Text("去配置") } }
                }
            }

            item { ScanEntry(nav) }

            item {
                SectionHeader("最近扫描") {
                    if (tasks.size > 5) TextButton(onClick = { nav.go(Route.History) }) { Text("全部 ${tasks.size}") }
                }
            }
            if (tasks.isEmpty()) {
                item { EmptyState(Icons.Outlined.Radar, "还没有分析记录", "从上面任意入口选一个目标开始") }
            }
            items(tasks.take(5), key = { it.id }) { t ->
                TaskRow(t, queued = t.id in queued) { openTask(nav, t) }
            }
        }
    }
}

/** 唯一的扫描入口：主卡进「扫描目标」，下方四个快捷方式直达对应 Tab。 */
@Composable
private fun ScanEntry(nav: Navigator) {
    MrCard(onClick = { nav.go(Route.Targets()) }) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Outlined.Radar)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("扫描目标", style = MaterialTheme.typography.titleMedium)
                    Text("已安装应用、APK / zip、文件夹、KSU 模块", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TargetTab.entries.forEachIndexed { i, t ->
                    Surface(
                        onClick = { nav.go(Route.Targets(i)) },
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(t.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.height(4.dp))
                            Text(t.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LastScanCard(t: AnalysisTaskEntity?, nav: Navigator) {
    MrCard(onClick = t?.let { { nav.go(Route.Report(it.id)) } }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(t?.score, size = 72.dp, stroke = 7.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (t == null) {
                    Text("尚未扫描", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "分数越高越安全：≥80 低 · 60-79 中 · 40-59 较高 · <40 高",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(levelLabel(t.riskLevel), style = MaterialTheme.typography.titleLarge, color = RiskColors.ofLevel(t.riskLevel))
                    Text(t.targetName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TypeBadge(t.targetKind)
                        Tag("上次扫描 · ${formatTime(t.updatedAt)}", MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
