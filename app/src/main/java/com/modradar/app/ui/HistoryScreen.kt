package com.modradar.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import com.modradar.app.ui.theme.RiskColors
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modradar.app.Navigator
import com.modradar.app.task.TaskState
import com.modradar.app.ui.components.EmptyState
import com.modradar.app.ui.components.MrTopBar

private enum class HistoryFilter(val label: String) { ALL("全部"), RISKY("高风险"), RUNNING("未完成") }

@Composable
fun HistoryScreen(vm: AppViewModel, nav: Navigator) {
    val tasks by vm.allTasks.collectAsStateWithLifecycle()
    val queued by vm.queued.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    // 长按进入多选；非空即为多选模式
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    BackHandler(selecting) { selected = emptySet() }

    val shown = tasks.filter { t ->
        when (filter) {
            HistoryFilter.ALL -> true
            HistoryFilter.RISKY -> (t.score ?: 100) < 60
            HistoryFilter.RUNNING -> t.state != TaskState.DONE.name
        } && (query.isBlank() || t.targetName.contains(query, true))
    }

    // 已被删除的任务从选择集中剔除
    LaunchedEffect(tasks) { selected = selected.filterTo(mutableSetOf()) { id -> tasks.any { it.id == id } } }

    Scaffold(topBar = {
        if (selecting) {
            MrTopBar("已选 ${selected.size} 项", onBack = { selected = emptySet() }) {
                IconButton(onClick = {
                    val ids = shown.map { it.id }.toSet()
                    selected = if (selected.containsAll(ids)) selected - ids else selected + ids
                }) { Icon(Icons.Outlined.SelectAll, "全选当前列表") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "删除所选", tint = RiskColors.Critical) }
            }
        } else {
            MrTopBar("历史记录", subtitle = "${tasks.size} 个任务 · 长按多选", onBack = { nav.back() })
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索目标名") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "清除") } },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryFilter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }) }
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (shown.isEmpty()) item { EmptyState(Icons.Outlined.History, "没有记录") }
                items(shown, key = { it.id }) { t ->
                    fun toggle() { selected = if (t.id in selected) selected - t.id else selected + t.id }
                    TaskRow(
                        t, queued = t.id in queued,
                        selected = if (selecting) t.id in selected else null,
                        onLongClick = { toggle() },
                    ) { if (selecting) toggle() else openTask(nav, t) }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 ${selected.size} 条记录？") },
            text = { Text("将删除所选任务的报告、产物与 AI 对话；正在分析的任务会被取消。不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteTasks(selected)
                    selected = emptySet()
                }) { Text("删除", color = RiskColors.Critical) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}
