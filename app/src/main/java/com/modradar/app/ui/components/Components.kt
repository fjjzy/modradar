package com.modradar.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.modradar.app.ui.theme.RiskColors

/** 统一顶栏：有 [onBack] 时显示图标返回。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MrTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MrCenterTopBar(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    CenterAlignedTopAppBar(
        title = { Text(title) },
        actions = actions,
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

/** 圆角容器卡片（surfaceContainer 底色）。 */
@Composable
fun MrCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable () -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = container)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth(), colors = colors, shape = MaterialTheme.shapes.medium) { content() }
    } else {
        Card(modifier = modifier.fillMaxWidth(), colors = colors, shape = MaterialTheme.shapes.medium) { content() }
    }
}

/** 圆形图标底座。 */
@Composable
fun IconBadge(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.primary, size: Dp = 40.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

/** 入口卡：图标 + 标题 + 副标题 + 右箭头。 */
@Composable
fun EntryCard(icon: ImageVector, title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit) {
    MrCard(onClick = if (enabled) onClick else null) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 设置风格列表行。 */
@Composable
fun ListRow(
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val content: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (trailing != null) trailing()
            else if (onClick != null) Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, color = Color.Transparent) { content() }
    } else {
        content()
    }
}

/** 评分环：越高越安全。score 为 null 时显示「—」。 */
@Composable
fun ScoreRing(score: Int?, size: Dp = 64.dp, stroke: Dp = 6.dp, caption: String? = null) {
    val color = RiskColors.ofScore(score)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val sweep by animateFloatAsState((score ?: 0) / 100f, tween(700), label = "score")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val st = Stroke(width = stroke.toPx(), cap = StrokeCap.Round)
            val inset = stroke.toPx() / 2
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val tl = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(track, 0f, 360f, false, topLeft = tl, size = arcSize, style = st)
            if (score != null) drawArc(color, -90f, 360f * sweep, false, topLeft = tl, size = arcSize, style = st)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                score?.toString() ?: "—",
                fontSize = (size.value * 0.32f).sp,
                fontWeight = FontWeight.Bold,
                color = if (score == null) MaterialTheme.colorScheme.onSurfaceVariant else color,
            )
            if (caption != null) {
                Text(caption, style = MaterialTheme.typography.labelMedium, color = color)
            }
        }
    }
}

/** 进度环（0–100）。 */
@Composable
fun ProgressRing(percent: Int, size: Dp = 120.dp, caption: String? = null) {
    val p by animateFloatAsState(percent.coerceIn(0, 100) / 100f, tween(400), label = "progress")
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val st = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
            val inset = 4.dp.toPx()
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val tl = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(track, 0f, 360f, false, topLeft = tl, size = arcSize, style = st)
            drawArc(color, -90f, 360f * p, false, topLeft = tl, size = arcSize, style = st)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$percent%", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (caption != null) Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 小型着色标签。 */
@Composable
fun Tag(text: String, color: Color = MaterialTheme.colorScheme.primary, filled: Boolean = false) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (filled) color else color.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (filled) Color.Black else color,
            maxLines = 1,
        )
    }
}

/** 目标类型标签：APK / XP 模块 / KSU 模块。 */
@Composable
fun TypeBadge(kind: String) {
    val (label, color) = when (kind) {
        "XPOSED_MODULE", "XP" -> "XP 模块" to Color(0xFF7FA7FF)
        "KSU_MODULE", "KSU" -> "KSU 模块" to Color(0xFFFFA45C)
        "APK" -> "APK" to RiskColors.Low
        else -> "未知" to RiskColors.Neutral
    }
    Tag(label, color)
}

/** 分数小胶囊。 */
@Composable
fun ScoreChip(score: Int?) {
    val c = RiskColors.ofScore(score)
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(c.copy(alpha = 0.2f)).padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(score?.toString() ?: "—", color = c, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** 空状态。 */
@Composable
fun EmptyState(icon: ImageVector, title: String, hint: String? = null) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(48.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** 提示横幅（警告 / 信息）。 */
@Composable
fun Banner(icon: ImageVector, text: String, color: Color, action: @Composable (() -> Unit)? = null) {
    MrCard(container = color.copy(alpha = 0.14f)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            action?.invoke()
        }
    }
}

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

/**
 * 统一风格滑块：6dp 圆角轨道 + 带描边的圆形拇指，无 M3 Expressive 的分段缺口与停止点。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MrSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
) {
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.surfaceContainerHighest
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        modifier = modifier,
        thumb = {
            Box(Modifier.size(20.dp).shadow(3.dp, CircleShape).clip(CircleShape).background(active))
        },
        track = { state ->
            Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                val span = state.valueRange.endInclusive - state.valueRange.start
                val f = if (span <= 0f) 0f else ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
                val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
                drawRoundRect(inactive, cornerRadius = r)
                if (f > 0f) drawRoundRect(active, size = size.copy(width = size.width * f), cornerRadius = r)
            }
        },
    )
}
