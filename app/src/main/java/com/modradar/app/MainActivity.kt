package com.modradar.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.modradar.app.ui.AppViewModel
import com.modradar.app.ui.ConsentScreen
import com.modradar.app.ui.ConsentStore
import com.modradar.app.ui.DashboardScreen
import com.modradar.app.ui.KeepAliveScreen
import com.modradar.app.ui.ModulesScreen
import com.modradar.app.ui.ReportScreen
import com.modradar.app.ui.SettingsScreen
import com.modradar.app.ui.TaskDetailScreen
import com.modradar.app.ui.WorkspaceScreen
import com.modradar.app.ui.theme.ModRadarTheme

private sealed interface Screen {
    data object Dashboard : Screen
    data object KeepAlive : Screen
    data object Settings : Screen
    data object Modules : Screen
    data class Detail(val taskId: String) : Screen
    data class Workspace(val taskId: String) : Screen
    data class Report(val taskId: String) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ModRadarTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // 首启协议门禁：未同意前不进入主界面
                    var consented by remember { mutableStateOf(ConsentStore.accepted(this)) }
                    if (!consented) {
                        ConsentScreen(
                            onAccept = {
                                ConsentStore.accept(this)
                                consented = true
                            },
                            onDecline = { finish() },
                        )
                    } else {
                        ModRadarNav()
                    }
                }
            }
        }
    }
}

@Composable
private fun ModRadarNav() {
    val vm: AppViewModel = viewModel()
    val tasks by vm.allTasks.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf<Screen>(Screen.Dashboard) }

    when (val s = screen) {
        Screen.Dashboard -> DashboardScreen(
            vm = vm,
            onOpenTask = { screen = Screen.Detail(it) },
            onOpenModules = { screen = Screen.Modules },
            onKeepAlive = { screen = Screen.KeepAlive },
            onSettings = { screen = Screen.Settings },
        )

        Screen.Modules -> ModulesScreen(
            vm = vm,
            onBack = { screen = Screen.Dashboard },
            onOpenTask = { screen = Screen.Detail(it) },
        )

        Screen.KeepAlive -> KeepAliveScreen(onBack = { screen = Screen.Dashboard })

        Screen.Settings -> SettingsScreen(vm = vm, onBack = { screen = Screen.Dashboard })

        is Screen.Detail -> {
            val task = tasks.firstOrNull { it.id == s.taskId }
            if (task == null) {
                MissingTask { screen = Screen.Dashboard }
            } else {
                TaskDetailScreen(
                    vm = vm,
                    task = task,
                    onBack = { screen = Screen.Dashboard },
                    onOpenWorkspace = { screen = Screen.Workspace(task.id) },
                    onOpenReport = { screen = Screen.Report(task.id) },
                    onOpenKeepAlive = { screen = Screen.KeepAlive },
                )
            }
        }

        is Screen.Report -> {
            val task = tasks.firstOrNull { it.id == s.taskId }
            if (task == null) {
                MissingTask { screen = Screen.Dashboard }
            } else {
                ReportScreen(
                    vm = vm,
                    task = task,
                    onBack = { screen = Screen.Detail(task.id) },
                    onOpenWorkspace = { screen = Screen.Workspace(task.id) },
                )
            }
        }

        is Screen.Workspace -> {
            val task = tasks.firstOrNull { it.id == s.taskId }
            if (task == null) {
                MissingTask { screen = Screen.Dashboard }
            } else {
                WorkspaceScreen(
                    vm = vm,
                    task = task,
                    onBack = { screen = Screen.Detail(task.id) },
                    onOpenKeepAlive = { screen = Screen.KeepAlive },
                )
            }
        }
    }
}

@Composable
private fun MissingTask(onBack: () -> Unit) {
    Column(Modifier.padding(24.dp)) {
        Text("任务不存在或已删除", style = MaterialTheme.typography.bodyMedium)
        Text("返回首页", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        androidx.compose.material3.TextButton(onClick = onBack) { Text("返回") }
    }
}
