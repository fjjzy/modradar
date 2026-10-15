package com.modradar.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modradar.app.Navigator
import com.modradar.app.Route
import com.modradar.app.ui.components.Banner
import com.modradar.app.ui.components.IconBadge
import com.modradar.app.ui.components.MrCard
import com.modradar.app.ui.components.MrTopBar
import kotlinx.coroutines.launch

/** 「扫描目标」四个来源；顺序即 Tab 顺序。 */
enum class TargetTab(val label: String, val icon: ImageVector) {
    APPS("已安装", Icons.Outlined.Apps),
    FILE("文件", Icons.Outlined.Description),
    FOLDER("文件夹", Icons.Outlined.FolderOpen),
    KSU("KSU 模块", Icons.Outlined.Terminal),
}

/** 扫描目标：已安装应用 / APK·zip 文件 / 文件夹 / KSU 模块 合并为一个页面。 */
@Composable
fun TargetsScreen(vm: AppViewModel, nav: Navigator, initial: Int) {
    val pager = rememberPagerState(initialPage = initial.coerceIn(0, TargetTab.entries.lastIndex)) { TargetTab.entries.size }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            Column {
                MrTopBar("扫描目标", subtitle = "选择要分析的应用、文件或模块", onBack = { nav.back() })
                PrimaryTabRow(selectedTabIndex = pager.currentPage, containerColor = MaterialTheme.colorScheme.background) {
                    TargetTab.entries.forEachIndexed { i, t ->
                        Tab(
                            selected = pager.currentPage == i,
                            onClick = { scope.launch { pager.animateScrollToPage(i) } },
                            text = { Text(t.label, maxLines = 1) },
                            icon = { Icon(t.icon, null, Modifier.size(20.dp)) },
                            selectedContentColor = MaterialTheme.colorScheme.primary,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    ) { pad ->
        HorizontalPager(pager, Modifier.padding(pad).fillMaxSize(), key = { it }) { page ->
            when (TargetTab.entries[page]) {
                TargetTab.APPS -> InstalledAppsTab(vm, nav)
                TargetTab.FILE -> FileTab(vm, nav)
                TargetTab.FOLDER -> FolderTab(vm, nav)
                TargetTab.KSU -> KsuTab(vm, nav)
            }
        }
    }
}

@Composable
private fun FileTab(vm: AppViewModel, nav: Navigator) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            vm.createTasksFromUris(uris.map { it to null }) { ids ->
                if (ids.size == 1) nav.replace(Route.Task(ids.first())) else nav.replace(Route.History)
            }
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MrCard {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                IconBadge(Icons.Outlined.UploadFile, size = 64.dp)
                Spacer(Modifier.size(12.dp))
                Text("选择 APK 或模块 zip", style = MaterialTheme.typography.titleMedium)
                Text(
                    "可多选，逐个排队分析",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(16.dp))
                Button(
                    enabled = !busy,
                    onClick = { picker.launch(arrayOf("application/vnd.android.package-archive", "application/zip", "application/octet-stream", "*/*")) },
                ) { Text(if (busy) "导入中…" else "选择文件") }
            }
        }
        Banner(
            Icons.Outlined.Info,
            "支持：APK、Xposed 模块 APK、KSU / Magisk 模块 zip。按包内条目自动识别类型，文件只复制到应用私有目录，不会上传。",
            MaterialTheme.colorScheme.outline,
        )
    }
}

/** 多选列表底部的操作条（悬浮在 Tab 内容底部）。 */
@Composable
fun BoxScope.SelectionBar(count: Int, busy: Boolean, busyText: String, onClear: () -> Unit, onStart: () -> Unit) {
    if (count == 0) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("已选 $count 个", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = onClear) { Icon(Icons.Outlined.Close, "清空选择") }
            Spacer(Modifier.width(4.dp))
            Button(enabled = !busy, onClick = onStart) { Text(if (busy) busyText else "开始分析") }
        }
    }
}

/** 给悬浮操作条留出的列表底部空间。 */
@Composable
fun SelectionBarSpacer() = Box(Modifier.navigationBarsPadding().size(88.dp))
