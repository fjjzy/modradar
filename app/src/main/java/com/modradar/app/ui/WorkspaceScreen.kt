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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modradar.app.ai.ChatEvent
import com.modradar.app.data.db.AnalysisTaskEntity
import com.modradar.app.data.db.ChatMessageEntity
import com.modradar.app.data.db.StageCheckpointEntity
import com.modradar.app.task.Stage
import com.modradar.app.task.StageState

/**
 * AI 逆向工作台。
 *
 * 与流水线状态解耦：分析暂停 / 失败 / 未跑完时都能进入对话，
 * 顶部横幅显式标注「结论基于部分数据」，工具会返回 data_not_ready。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    vm: AppViewModel,
    task: AnalysisTaskEntity,
    onBack: () -> Unit,
    onOpenKeepAlive: () -> Unit,
) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val events by vm.chatEvents.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val checkpoints by vm.checkpoints.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }

    LaunchedEffect(task.id) {
        vm.refreshCheckpoints(task.id)
        vm.refreshMessages(task.id)
    }

    val pending = pendingStages(checkpoints)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AI 逆向工作台", style = MaterialTheme.typography.titleMedium)
                        Text(
                            task.targetName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("追问…") },
                    maxLines = 4,
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val t = input.trim()
                        if (t.isNotEmpty() && !busy) {
                            input = ""
                            vm.sendChat(task.id, t)
                        }
                    },
                    enabled = !busy,
                ) { Text(if (busy) "…" else "发送") }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {

            if (pending.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2E12)),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "分析暂停于 ${pending.first().id} ${pending.first().label} · 结论基于部分数据",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.resume(task.id) }) { Text("继续分析") }
                            TextButton(onClick = onOpenKeepAlive) { Text("后台保活") }
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages) { m -> MessageBubble(m) }
                items(events.filterIsInstance<ChatEvent.ToolStarted>()) { e ->
                    ToolCard(name = e.name, args = e.args)
                }
            }
        }
    }
}

private fun pendingStages(cps: List<StageCheckpointEntity>): List<Stage> {
    val done = cps.filter { it.state == StageState.DONE.name }
        .mapNotNull { Stage.byId(it.stage) }
        .toSet()
    return Stage.order.filter { it !in done }
}

@Composable
private fun MessageBubble(m: ChatMessageEntity) {
    val isUser = m.role == "user"
    val isTool = m.role == "tool"
    val bg = when {
        isUser -> MaterialTheme.colorScheme.primary
        isTool -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    val fg = if (isUser) Color.Black else MaterialTheme.colorScheme.onSurface

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.94f),
            colors = CardDefaults.cardColors(containerColor = bg),
        ) {
            Column(Modifier.padding(12.dp)) {
                if (isTool) {
                    Text(
                        "工具 · ${m.toolCallsJson ?: ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    m.content.take(if (isTool) 900 else 4000),
                    style = MaterialTheme.typography.bodySmall,
                    color = fg,
                )
            }
        }
    }
}

@Composable
private fun ToolCard(name: String, args: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(name, style = MaterialTheme.typography.labelMedium)
            Text(
                args,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
