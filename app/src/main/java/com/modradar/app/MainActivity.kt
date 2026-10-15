package com.modradar.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.modradar.app.ui.AboutScreen
import com.modradar.app.ui.AiHtmlScreen
import com.modradar.app.ui.AiSettingsScreen
import com.modradar.app.ui.ChatHtmlScreen
import com.modradar.app.ui.AppViewModel
import com.modradar.app.ui.ConsentDialog
import com.modradar.app.ui.ConsentStore
import com.modradar.app.ui.DashboardScreen
import com.modradar.app.ui.EvidenceScreen
import com.modradar.app.ui.HistoryScreen
import com.modradar.app.ui.KeepAliveScreen
import com.modradar.app.ui.McpScreen
import com.modradar.app.ui.MissingTask
import com.modradar.app.ui.ReportScreen
import com.modradar.app.ui.SettingsScreen
import com.modradar.app.ui.TargetsScreen
import com.modradar.app.ui.TaskDetailScreen
import com.modradar.app.ui.WorkspaceScreen
import com.modradar.app.ui.ime.ImeController
import com.modradar.app.ui.ime.LocalImeController
import com.modradar.app.ui.theme.ModRadarTheme
import com.modradar.app.ui.theme.ThemePrefs
import com.modradar.app.ui.theme.isDarkTheme

/** 路由：返回栈里的每一项；系统返回逐级出栈，只有首页返回才退出应用。 */
sealed interface Route {
    data object Dashboard : Route
    /** 扫描目标页；tab 见 [com.modradar.app.ui.TargetTab]。 */
    data class Targets(val tab: Int = 0) : Route
    data object History : Route
    data object Settings : Route
    data object AiSettings : Route
    data object Mcp : Route
    data object KeepAlive : Route
    data object About : Route
    data class Task(val id: String) : Route
    data class Report(val id: String) : Route
    data class Evidence(val id: String, val index: Int) : Route
    /** prefill：从证据页带入的追问草稿。 */
    data class Workspace(val id: String, val prefill: String? = null) : Route
    data class AiHtml(val id: String) : Route
    data class ChatHtml(val id: String, val messageId: Long) : Route
}

class MainActivity : ComponentActivity() {
    private val ime by lazy { ImeController(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ime.attach()
        ThemePrefs.load(this)
        setContent {
            val dark = isDarkTheme()
            // 系统栏图标颜色跟随主题（旧系统否则会出现白底白字的导航栏）
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            CompositionLocalProvider(LocalImeController provides ime) { ModRadarTheme(dark = dark) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ModRadarNav()
                    // 首启协议：首页上弹窗，未同意前不可关闭
                    var consented by remember { mutableStateOf(ConsentStore.accepted(this)) }
                    if (!consented) {
                        ConsentDialog(
                            onAccept = {
                                ConsentStore.accept(this)
                                consented = true
                            },
                            onDecline = { finish() },
                        )
                    }
                }
            } }
        }
    }

    override fun onDestroy() {
        ime.detach()
        super.onDestroy()
    }
}

/** 返回栈操作封装。 */
class Navigator(val stack: SnapshotStateList<Route>) {
    fun go(r: Route) {
        if (stack.lastOrNull() != r) stack.add(r)
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /** 替换栈顶（如任务页 → 报告页后不希望返回到进度页时使用）。 */
    fun replace(r: Route) {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
        stack.add(r)
    }
}

private const val SLIDE_MS = 300

@Composable
private fun ModRadarNav() {
    val vm: AppViewModel = viewModel()
    val tasks by vm.allTasks.collectAsStateWithLifecycle()
    val stack = remember { mutableStateListOf<Route>(Route.Dashboard) }
    val nav = remember { Navigator(stack) }

    NavDisplay(
        backStack = stack,
        onBack = { nav.back() },
        // 新页面从右侧推入，返回时反向滑出
        transitionSpec = { slideInHorizontally(tween(SLIDE_MS)) { it } togetherWith slideOutHorizontally(tween(SLIDE_MS)) { -it / 4 } },
        popTransitionSpec = { slideInHorizontally(tween(SLIDE_MS)) { -it / 4 } togetherWith slideOutHorizontally(tween(SLIDE_MS)) { it } },
        predictivePopTransitionSpec = { slideInHorizontally(tween(SLIDE_MS)) { -it / 4 } togetherWith slideOutHorizontally(tween(SLIDE_MS)) { it } },
        entryProvider = { route ->
            NavEntry(route) {
                when (route) {
                    Route.Dashboard -> DashboardScreen(vm, nav)
                    is Route.Targets -> TargetsScreen(vm, nav, route.tab)
                    Route.History -> HistoryScreen(vm, nav)
                    Route.Settings -> SettingsScreen(vm, nav)
                    Route.AiSettings -> AiSettingsScreen(vm, nav)
                    Route.Mcp -> McpScreen(vm, nav)
                    Route.KeepAlive -> KeepAliveScreen(onBack = { nav.back() })
                    Route.About -> AboutScreen(nav)
                    is Route.Task -> tasks.firstOrNull { it.id == route.id }
                        ?.let { TaskDetailScreen(vm, it, nav) } ?: MissingTask(nav)
                    is Route.Report -> tasks.firstOrNull { it.id == route.id }
                        ?.let { ReportScreen(vm, it, nav) } ?: MissingTask(nav)
                    is Route.Evidence -> tasks.firstOrNull { it.id == route.id }
                        ?.let { EvidenceScreen(vm, it, route.index, nav) } ?: MissingTask(nav)
                    is Route.Workspace -> tasks.firstOrNull { it.id == route.id }
                        ?.let { WorkspaceScreen(vm, it, nav, route.prefill) } ?: MissingTask(nav)
                    is Route.AiHtml -> tasks.firstOrNull { it.id == route.id }
                        ?.let { AiHtmlScreen(vm, it, nav) } ?: MissingTask(nav)
                    is Route.ChatHtml -> tasks.firstOrNull { it.id == route.id }
                        ?.let { ChatHtmlScreen(vm, it, route.messageId, nav) } ?: MissingTask(nav)
                }
            }
        },
    )
}
