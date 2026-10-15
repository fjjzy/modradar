package com.modradar.app.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modradar.app.Navigator
import com.modradar.app.Route
import com.modradar.app.ui.components.EmptyState
import com.modradar.app.ui.components.MrCard
import com.modradar.app.ui.components.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class AppFilter(val label: String) { USER("用户应用"), XPOSED("XP 模块"), SYSTEM("系统应用") }

@Composable
fun InstalledAppsTab(vm: AppViewModel, nav: Navigator) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val loading by vm.appsLoading.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(AppFilter.USER) }
    var selected by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(Unit) { if (apps.isEmpty()) vm.loadInstalledApps() }

    val shown = apps.filter { a ->
        when (filter) {
            AppFilter.USER -> !a.isSystem
            AppFilter.XPOSED -> a.isXposed
            AppFilter.SYSTEM -> a.isSystem
        } && (query.isBlank() || a.label.contains(query, true) || a.packageName.contains(query, true))
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索应用名或包名") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "清除") }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            )
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppFilter.entries.forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                }
                Spacer(Modifier.weight(1f))
                if (shown.isNotEmpty()) IconButton(onClick = {
                    val ids = shown.map { it.packageName }.toSet()
                    selected = if (selected.containsAll(ids)) selected - ids else selected + ids
                }) { Icon(Icons.Outlined.SelectAll, "全选当前列表") }
            }
            // 首次加载：居中提示；刷新时只在列表上方显示一条带边距的细进度条
            if (loading && apps.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 96.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
                    Text("正在读取已安装应用…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Column
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(2.dp)))

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!loading && shown.isEmpty()) {
                    item { EmptyState(Icons.Outlined.SearchOff, "没有匹配的应用", if (filter == AppFilter.XPOSED) "未发现 Xposed 模块（meta-data xposedmodule 或 META-INF/xposed）" else null) }
                }
                items(shown, key = { it.packageName }) { a ->
                    val checked = a.packageName in selected
                    MrCard(
                        onClick = { selected = if (checked) selected - a.packageName else selected + a.packageName },
                        container = if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(a.packageName)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(a.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (a.isXposed) Tag("XP 模块", kindColor("XPOSED_MODULE"))
                                    if (a.splitApks.isNotEmpty()) Tag("split ×${a.splitApks.size}", MaterialTheme.colorScheme.outline)
                                }
                                Text(
                                    "${a.packageName}${a.versionName?.let { " · $it" } ?: ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Checkbox(checked = checked, onCheckedChange = {
                                selected = if (checked) selected - a.packageName else selected + a.packageName
                            })
                        }
                    }
                }
                item { SelectionBarSpacer() }
            }
        }
        SelectionBar(selected.size, busy, "提取中…", onClear = { selected = emptySet() }) {
            val pick = apps.filter { it.packageName in selected }
            vm.analyzeApps(pick) { ids ->
                selected = emptySet()
                if (ids.size == 1) nav.replace(Route.Task(ids.first())) else nav.replace(Route.History)
            }
        }
    }
}

/** 异步加载应用图标；失败时回落为 Android 图标。 */
@Composable
fun AppIcon(packageName: String, size: Int = 40) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ctx.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
            }.getOrNull()
        }
    }
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, contentDescription = null, modifier = Modifier.size(size.dp))
        else Icon(Icons.Outlined.Android, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
