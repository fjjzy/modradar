package com.modradar.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modradar.app.Navigator
import com.modradar.app.Route
import com.modradar.app.data.db.AnalysisTaskEntity
import com.modradar.app.data.db.StageCheckpointEntity
import com.modradar.app.task.Stage
import com.modradar.app.task.StageState
import com.modradar.app.task.TaskState
import com.modradar.app.ui.components.Banner
import com.modradar.app.ui.components.MrCard
import com.modradar.app.ui.components.MrTopBar
import com.modradar.app.ui.components.ProgressRing
import com.modradar.app.ui.components.TypeBadge
import com.modradar.app.ui.theme.MonoStyle
import com.modradar.app.ui.theme.RiskColors

/** 分析进度页：进度环 + 6 阶段步骤 + 实时日志。 */
@Composable
fun TaskDetailScreen(vm: AppViewModel, task: AnalysisTaskEntity, nav: Navigator) {
    val cps by vm.checkpoints.collectAsStateWithLifecycle()
    val queued by vm.queued.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var sawRunning by remember { mutableStateOf(false) }

    LaunchedEffect(task.id, task.state, task.currentStage) { vm.refreshCheckpoints(task.id) }
    LaunchedEffect(task.state) {
        if (task.state == TaskState.RUNNING.name) sawRunning = true
        // 在本页看着跑完：直接替换为报告页，返回时回到上一级而不是进度页
        if (task.state == TaskState.DONE.name && sawRunning) nav.replace(Route.Report(task.id))
    }

    val isQueued = task.id in queued
    val overall = overallProgress(task, cps)
    val stageLogs = logs[task.id].orEmpty()

    Scaffold(
        topBar = {
            MrTopBar("分析进度", subtitle = task.targetName, onBack = { nav.back() }) {
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "删除任务") }
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                MrCard {
                    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        ProgressRing(overall, caption = when {
                            isQueued -> "排队中"
                            task.state == TaskState.RUNNING.name -> "分析中"
                            else -> stateLabel(task.state)
                        })
                        Spacer(Modifier.size(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            TypeBadge(task.targetKind)
                            Text(task.targetName, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            task.lastError?.takeIf { task.state == TaskState.FAILED.name }?.let {
                item { Banner(Icons.Outlined.ErrorOutline, "失败：$it", RiskColors.Critical) }
            }

            item {
                MrCard {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Stage.order.forEach { s -> StageRow(s, cps.firstOrNull { it.stage == s.id }, task) }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when {
                        task.state == TaskState.RUNNING.name -> OutlinedButton(onClick = { vm.pause(task.id) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Pause, null); Spacer(Modifier.width(6.dp)); Text("暂停")
                        }
                        isQueued -> OutlinedButton(onClick = { vm.pause(task.id) }, modifier = Modifier.weight(1f)) { Text("移出队列") }
                        task.state != TaskState.DONE.name -> Button(onClick = { vm.resume(task.id) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(6.dp))
                            Text(if (task.state == TaskState.IDLE.name) "开始分析" else "继续分析")
                        }
                        else -> Button(onClick = { nav.go(Route.Report(task.id)) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Assessment, null); Spacer(Modifier.width(6.dp))
                            Text("查看报告${task.score?.let { "（$it 分）" } ?: ""}")
                        }
                    }
                    OutlinedButton(onClick = { nav.go(Route.Workspace(task.id)) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.SmartToy, null); Spacer(Modifier.width(6.dp)); Text("AI 工作台")
                    }
                }
            }

            if (stageLogs.isNotEmpty()) {
                item {
                    MrCard(container = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(12.dp)) {
                            stageLogs.takeLast(12).forEach {
                                Text(it, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            item {
                TextButton(onClick = { nav.go(Route.KeepAlive) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.BatteryChargingFull, null); Spacer(Modifier.width(6.dp)); Text("切后台总被杀？设置后台保活")
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Outlined.Warning, null) },
            title = { Text("删除该任务？") },
            text = { Text("会停止分析并删除报告、产物与 AI 对话，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.cancel(task.id)
                    nav.back()
                }) { Text("删除", color = RiskColors.Critical) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

private fun overallProgress(task: AnalysisTaskEntity, cps: List<StageCheckpointEntity>): Int {
    if (task.state == TaskState.DONE.name) return 100
    val done = cps.count { it.state == StageState.DONE.name }
    val curIdx = Stage.order.indexOfFirst { it.id == task.currentStage }.coerceAtLeast(0)
    val part = if (task.state == TaskState.RUNNING.name && curIdx >= done) task.progress.coerceIn(0, 100) / 100f else 0f
    return ((done + part) / Stage.order.size * 100).toInt().coerceIn(0, 100)
}

@Composable
private fun StageRow(stage: Stage, cp: StageCheckpointEntity?, task: AnalysisTaskEntity) {
    val state = cp?.state ?: StageState.PENDING.name
    val active = state == StageState.RUNNING.name ||
        (task.state == TaskState.RUNNING.name && task.currentStage == stage.id && state != StageState.DONE.name)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when {
                state == StageState.DONE.name -> Icon(Icons.Outlined.CheckCircle, null, tint = RiskColors.Low)
                state == StageState.FAILED.name -> Icon(Icons.Outlined.ErrorOutline, null, tint = RiskColors.Critical)
                active -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else -> Icon(Icons.Outlined.RadioButtonUnchecked, null, tint = MaterialTheme.colorScheme.outline)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stage.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (state == StageState.PENDING.name && !active) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            cp?.detail?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
        Text(stage.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}
