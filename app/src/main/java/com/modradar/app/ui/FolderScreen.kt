package com.modradar.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modradar.app.Navigator
import com.modradar.app.Route
import com.modradar.app.scan.model.TargetKind
import com.modradar.app.ui.components.Banner
import com.modradar.app.ui.components.EmptyState
import com.modradar.app.ui.components.IconBadge
import com.modradar.app.ui.components.MrCard
import com.modradar.app.ui.components.TypeBadge

@Composable
fun FolderTab(vm: AppViewModel, nav: Navigator) {
    val items by vm.folderItems.collectAsStateWithLifecycle()
    val folder by vm.folderUri.collectAsStateWithLifecycle()
    val scanning by vm.folderScanning.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(setOf<Uri>()) }

    LaunchedEffect(Unit) { vm.restoreFolder() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            selected = emptySet()
            vm.openFolder(uri)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (folder != null) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(folder?.let { displayPath(it) }.orEmpty(), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { folder?.let { vm.openFolder(it, persist = false) } }) { Icon(Icons.Outlined.Refresh, "重新扫描") }
                    IconButton(onClick = { picker.launch(null) }) { Icon(Icons.Outlined.CreateNewFolder, "选择文件夹") }
                }
            }
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (folder == null) {
                    item {
                        EmptyState(Icons.Outlined.Folder, "尚未授权文件夹", "通过系统文件选择器授权一个目录（如 Download），只读访问")
                    }
                    item {
                        Button(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("选择文件夹") }
                    }
                } else if (!scanning && items.isEmpty()) {
                    item { EmptyState(Icons.Outlined.FolderOff, "目录中没有 APK 或 zip", "最多向下扫描 2 层子目录") }
                    item { OutlinedButton(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("换一个文件夹") } }
                }
                items(items, key = { it.uri.toString() }) { f ->
                    val checked = f.uri in selected
                    val enabled = f.kind != TargetKind.UNKNOWN
                    MrCard(
                        onClick = if (enabled) { { selected = if (checked) selected - f.uri else selected + f.uri } } else null,
                        container = if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(kindIcon(f.kind.name), tint = kindColor(f.kind.name), size = 38.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    f.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                )
                                Text(formatSize(f.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TypeBadge(f.kind.name)
                            if (enabled) Checkbox(checked = checked, onCheckedChange = {
                                selected = if (checked) selected - f.uri else selected + f.uri
                            })
                        }
                    }
                }
                if (folder != null) {
                    item {
                        Banner(Icons.Outlined.Info, "按包内条目自动识别：module.prop → KSU，xposed_init → XP 模块，AndroidManifest → APK；无法识别的不可选", MaterialTheme.colorScheme.outline)
                    }
                }
                item { SelectionBarSpacer() }
            }
        }
        SelectionBar(selected.size, busy, "导入中…", onClear = { selected = emptySet() }) {
            val pick = items.filter { it.uri in selected }.map { it.uri to it.kind.takeIf { k -> k != TargetKind.UNKNOWN } }
            vm.createTasksFromUris(pick) { ids ->
                selected = emptySet()
                if (ids.size == 1) nav.replace(Route.Task(ids.first())) else nav.replace(Route.History)
            }
        }
    }
}

private fun displayPath(uri: Uri): String =
    uri.lastPathSegment?.replace("primary:", "/sdcard/")?.let { if (it.startsWith("/")) it else "/$it" } ?: uri.toString()

fun formatSize(bytes: Long): String = when {
    bytes < 0 -> "未知大小"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
