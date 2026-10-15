package com.modradar.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.modradar.app.Navigator
import com.modradar.app.data.db.AnalysisTaskEntity
import com.modradar.app.task.TaskState
import com.modradar.app.ui.components.EmptyState
import com.modradar.app.ui.components.IconBadge
import com.modradar.app.ui.components.MrCard
import com.modradar.app.ui.components.MrTopBar
import com.modradar.app.ui.components.ScoreChip
import com.modradar.app.ui.components.Tag
import com.modradar.app.ui.components.TypeBadge
import com.modradar.app.ui.theme.RiskColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun kindIcon(kind: String): ImageVector = when (kind) {
    "XPOSED_MODULE" -> Icons.Outlined.Extension
    "KSU_MODULE" -> Icons.Outlined.Terminal
    "APK" -> Icons.Outlined.Android
    else -> Icons.Outlined.HelpOutline
}

fun kindColor(kind: String): Color = when (kind) {
    "XPOSED_MODULE" -> Color(0xFF7FA7FF)
    "KSU_MODULE" -> Color(0xFFFFA45C)
    "APK" -> RiskColors.Low
    else -> RiskColors.Neutral
}

fun stateLabel(state: String): String = when (state) {
    TaskState.RUNNING.name -> "分析中"
    TaskState.PAUSED.name -> "已暂停"
    TaskState.FAILED.name -> "失败"
    TaskState.DONE.name -> "已完成"
    else -> "未开始"
}

private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
fun formatTime(ms: Long): String = timeFmt.format(Date(ms))

/** 任务列表行：类型图标 + 名称 + 时间/状态 + 分数；传入 [onLongClick] 时支持长按多选。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskRow(
    t: AnalysisTaskEntity,
    queued: Boolean,
    selected: Boolean? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    MrCard(container = if (selected == true) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected != null) {
                Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.size(38.dp))
            } else {
                IconBadge(kindIcon(t.targetKind), tint = kindColor(t.targetKind), size = 38.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(t.targetName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        formatTime(t.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TypeBadge(t.targetKind)
                    when {
                        queued -> Tag("排队中", MaterialTheme.colorScheme.tertiary)
                        t.state == TaskState.FAILED.name -> Tag("失败", RiskColors.Critical)
                        t.state == TaskState.PAUSED.name -> Tag("已暂停", RiskColors.Medium)
                        else -> Unit
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            if (t.state == TaskState.RUNNING.name) {
                CircularProgressIndicator(
                    progress = { t.progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp,
                )
            } else {
                ScoreChip(t.score)
            }
        }
    }
}

@Composable
fun MissingTask(nav: Navigator) {
    Scaffold(topBar = { MrTopBar("任务", onBack = { nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyState(Icons.Outlined.SearchOff, "任务不存在或已删除")
            TextButton(onClick = { nav.back() }) { Text("返回") }
        }
    }
}
