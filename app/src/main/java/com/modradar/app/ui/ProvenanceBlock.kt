package com.modradar.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.modradar.app.task.stages.LsPatchInfo
import com.modradar.app.task.stages.ProvenanceArtifact
import com.modradar.app.task.stages.ProvenanceArtifact.Companion as P
import com.modradar.app.task.stages.RepoRef
import com.modradar.app.ui.components.Tag
import com.modradar.app.ui.theme.MonoStyle
import com.modradar.app.ui.theme.RiskColors

/** 「包信息与签名」卡片里的来源核验段：GitHub 仓库、star、release 比对结论。 */
@Composable
fun ProvenanceBlock(p: ProvenanceArtifact?, busy: Boolean, canCheck: Boolean, onRecheck: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Public, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text("来源核验", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        p?.takeIf { it.status == P.OK }?.let { val (label, c) = verdictLabel(it); Tag(label, c, filled = true) }
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else if (canCheck && p != null) IconButton(onClick = onRecheck) { Icon(Icons.Outlined.Refresh, "重新核验", tint = muted) }
        }
    }
    when {
        p == null -> {
            Text("尚未与 GitHub 官方发布比对（旧任务或分析未完成）", style = MaterialTheme.typography.bodySmall, color = muted)
            if (canCheck && !busy) TextButton(onClick = onRecheck) { Text("联网核验") }
        }
        p.status == P.DISABLED -> Text("已在设置中关闭联网来源核验", style = MaterialTheme.typography.bodySmall, color = muted)
        p.status == P.FAILED -> {
            Text("核验失败：${p.error ?: "网络错误"}", style = MaterialTheme.typography.bodySmall, color = RiskColors.Critical)
            if (canCheck && !busy) TextButton(onClick = onRecheck) { Text("重试") }
        }
        else -> {
            p.repo?.let { RepoRow(it, p.matchedBy, weak = p.strength == P.WEAK) }
            p.release?.let { r ->
                Text("比对 release：${r.tag}${r.asset?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.labelSmall, color = muted)
            }
            if (p.detail.isNotBlank()) Text(p.detail, style = MaterialTheme.typography.bodySmall, color = muted)
            if (p.diffFiles.isNotEmpty()) {
                p.diffFiles.take(6).forEach { Text("≠ $it", style = MonoStyle, color = RiskColors.Medium) }
                if (p.diffFiles.size > 6) Text("… 共 ${p.diffFiles.size} 个", style = MaterialTheme.typography.labelSmall, color = muted)
            }
            if (p.others.isNotEmpty()) {
                Text("其他候选", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 2.dp))
                p.others.forEach { RepoRow(it, null, weak = true, compact = true) }
            }
        }
    }
}

/** 结论标签；弱来源（只靠名称搜索）不下「修改版」结论。 */
private fun verdictLabel(p: ProvenanceArtifact): Pair<String, Color> = when (p.verdict) {
    P.OFFICIAL -> "官方原版" to RiskColors.Low
    P.SAME_SIGNER -> "官方签名" to RiskColors.Low
    P.SIGNER_MISMATCH -> if (p.modified) "疑似修改版" to RiskColors.Critical else "签名不同" to RiskColors.Medium
    P.CONTENT_MISMATCH -> if (p.modified) "疑似修改版" to RiskColors.Critical else "内容有差异" to RiskColors.Medium
    P.SOURCE_FOUND -> "找到来源" to Color(0xFF7FA7FF)
    else -> "未找到来源" to RiskColors.Neutral
}

@Composable
private fun RepoRow(r: RepoRef, by: String?, weak: Boolean, compact: Boolean = false) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
            .clickable { openGithub(ctx, r.url) }
            .padding(vertical = if (compact) 2.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Code, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(r.fullName, style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Icon(Icons.Outlined.Star, null, Modifier.size(14.dp), tint = RiskColors.Medium)
                Text(formatStars(r.stars), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!compact) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    by?.let { Tag(it, MaterialTheme.colorScheme.outline) }
                    if (weak) Tag("可能来源", RiskColors.Medium)
                    if (r.archived) Tag("已归档", RiskColors.Neutral)
                }
                r.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Icon(Icons.AutoMirrored.Filled.OpenInNew, "在 GitHub 查看", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

/** LSPatch 重打包：内嵌模块与原包证书。 */
@Composable
fun LsPatchBlock(lp: LsPatchInfo, currentSigner: String?) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Extension, null, tint = RiskColors.High)
        Spacer(Modifier.width(8.dp))
        Text("LSPatch 重打包", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Tag("非原版", RiskColors.High, filled = true)
    }
    Text(
        if (lp.modules.isEmpty()) "包内含 LSPatch 加载器，模块可能以本地模式在外部加载"
        else "内嵌 ${lp.modules.size} 个 Xposed 模块，以本应用身份运行",
        style = MaterialTheme.typography.bodySmall, color = muted,
    )
    lp.modules.take(8).forEach { Text("· $it", style = MonoStyle, color = muted) }
    lp.originSignerSha256?.let { o ->
        val same = currentSigner != null && o.equals(currentSigner, true)
        Text(
            if (same) "原包证书与当前一致" else "原包证书：${lp.originSignerSubject ?: o.take(16) + "…"}（已被替换）",
            style = MaterialTheme.typography.labelSmall,
            color = if (same) muted else RiskColors.Medium,
        )
    }
}

private fun formatStars(n: Int): String = when {
    n >= 10_000 -> "%.0fk".format(n / 1000.0)
    n >= 1_000 -> "%.1fk".format(n / 1000.0)
    else -> "$n"
}

/** 只打开 github.com 链接。 */
private fun openGithub(ctx: android.content.Context, url: String) {
    if (!url.startsWith("https://github.com/")) return
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
