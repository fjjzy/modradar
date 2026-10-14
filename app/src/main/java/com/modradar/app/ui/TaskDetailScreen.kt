package com.modradar.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
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
import com.modradar.app.data.db.AnalysisTaskEntity
import com.modradar.app.data.db.StageCheckpointEntity
import com.modradar.app.task.Stage
import com.modradar.app.task.StageState
import com.modradar.app.task.TaskState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    vm: AppViewModel,
    task: AnalysisTaskEntity,
    onBack: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenReport: () -> Unit,
    onOpenKeepAlive: () -> Unit,
) {
    val cps by vm.checkpoints.collectAsStateWithLifecycle()

    LaunchedEffect(task.id, task.state) { vm.refreshCheckpoints(task.id) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(task.targetName, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "${task.targetKind} · ${stateLabel(task.state)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            task.lastError?.let {
                Text("上次中断：$it", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFB4A2))
            }

            LinearProgressIndicator(
                progress = { (task.progress.coerceIn(0, 100)) / 100f },
                modifier = Modifier.fillMaxWidth(),
            )

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stage.order.forEach { s -> StageRow(s, cps.firstOrNull { it.stage == s.id }) }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (task.state) {
                    TaskState.RUNNING.name -> {
                        Button(onClick = { vm.pause(task.id) }) { Text("暂停") }
                    }
                    TaskState.PAUSED.name, TaskState.FAILED.name, TaskState.IDLE.name -> {
                        Button(onClick = { vm.resume(task.id) }) {
                            Text(if (task.state == TaskState.IDLE.name) "开始分析" else "继续分析")
                        }
                    }
                    TaskState.DONE.name -> {
                        Text("分析已完成", style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedButton(onClick = { vm.cancel(task.id) }) { Text("取消") }
            }

            Spacer(Modifier.height(4.dp))
            Button(onClick = onOpenReport, modifier = Modifier.fillMaxWidth()) {
                Text(if (task.score != null) "查看报告（${task.score} 分）" else "查看报告")
            }
            OutlinedButton(onClick = onOpenWorkspace, modifier = Modifier.fillMaxWidth()) {
                Text("进入 AI 工作台")
            }
            OutlinedButton(onClick = onOpenKeepAlive, modifier = Modifier.fillMaxWidth()) {
                Text("后台保活设置")
            }
        }
    }
}

@Composable
private fun StageRow(stage: Stage, cp: StageCheckpointEntity?) {
    val state = cp?.state ?: StageState.PENDING.name
    val mark = when (state) {
        StageState.DONE.name -> "✓"
        StageState.RUNNING.name -> "●"
        StageState.FAILED.name -> "!"
        else -> "○"
    }
    val color = when (state) {
        StageState.DONE.name -> MaterialTheme.colorScheme.primary
        StageState.RUNNING.name -> Color(0xFF8C8CFF)
        StageState.FAILED.name -> Color(0xFFFFB4A2)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(mark, color = color, modifier = Modifier.width(24.dp))
        Text(
            "${stage.id} ${stage.label}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (state == StageState.RUNNING.name) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        cp?.detail?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
