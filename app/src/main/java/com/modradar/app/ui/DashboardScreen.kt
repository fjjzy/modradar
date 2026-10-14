package com.modradar.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modradar.app.data.db.AnalysisTaskEntity
import com.modradar.app.task.TaskState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    vm: AppViewModel,
    onOpenTask: (String) -> Unit,
    onOpenModules: () -> Unit,
    onKeepAlive: () -> Unit,
    onSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val tasks by vm.allTasks.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.createTaskFromUri(ctx, uri) { id -> onOpenTask(id) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模块雷达") },
                actions = {
                    TextButton(onClick = onKeepAlive) { Text("保活") }
                    TextButton(onClick = onSettings) { Text("设置") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            Button(
                onClick = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) { Text(if (busy) "导入中…" else "选择 APK / 模块包") }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenModules,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("已安装模块（Xposed / KSU）") }

            Spacer(Modifier.height(8.dp))
            Text(
                "支持 APK、Xposed 模块、KSU/Magisk 模块 zip；自动识别类型。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Text("任务", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            if (tasks.isEmpty()) {
                Text(
                    "还没有分析记录。选一个包开始。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tasks) { t -> TaskRow(t) { onOpenTask(t.id) } }
            }
        }
    }
}

@Composable
private fun TaskRow(t: AnalysisTaskEntity, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.targetName, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${t.targetKind} · ${t.currentStage} · ${stateLabel(t.state)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            t.score?.let {
                Text("$it", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

fun stateLabel(state: String): String = when (state) {
    TaskState.RUNNING.name -> "分析中"
    TaskState.PAUSED.name -> "已暂停（可继续）"
    TaskState.FAILED.name -> "失败"
    TaskState.DONE.name -> "已完成"
    else -> "未开始"
}
