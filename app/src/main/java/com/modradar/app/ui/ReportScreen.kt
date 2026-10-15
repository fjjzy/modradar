package com.modradar.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material.icons.outlined.Pending
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modradar.app.Navigator
import com.modradar.app.Route
import com.modradar.app.data.db.AnalysisTaskEntity
import com.modradar.app.task.TaskState
import com.modradar.app.task.stages.ExtractArtifact
import com.modradar.app.task.stages.ReportArtifact
import com.modradar.app.task.stages.RuleGlossary
import com.modradar.app.task.stages.ScoreFinding
import com.modradar.app.ui.components.Banner
import com.modradar.app.ui.components.EmptyState
import com.modradar.app.ui.components.Markdown
import com.modradar.app.ui.components.MrCard
import com.modradar.app.ui.components.MrTopBar
import com.modradar.app.ui.components.ScoreRing
import com.modradar.app.ui.components.SectionHeader
import com.modradar.app.ui.components.Tag
import com.modradar.app.ui.components.TypeBadge
import com.modradar.app.ui.theme.MonoStyle
import com.modradar.app.ui.theme.RiskColors
import com.modradar.app.ui.theme.levelLabel

/** 风险报告页：渲染 S6 产物。 */
@Composable
fun ReportScreen(vm: AppViewModel, task: AnalysisTaskEntity, nav: Navigator) {
    val report by vm.report.collectAsStateWithLifecycle()
    val extract by vm.extract.collectAsStateWithLifecycle()
    val load by vm.load.collectAsStateWithLifecycle()
    val provBusy by vm.provenanceBusy.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    LaunchedEffect(task.id, task.state) { vm.loadReport(task.id) }

    val rep = report
    var showDismissed by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            MrTopBar("风险报告", subtitle = task.targetName, onBack = { nav.back() }) {
                if (rep != null) IconButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText(rep))
                    ctx.startActivity(Intent.createChooser(send, "分享报告摘要"))
                }) { Icon(Icons.Outlined.Share, "分享") }
            }
        },
    ) { pad ->
        if (rep == null) {
            Column(Modifier.padding(pad).fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState(Icons.Outlined.Pending, "报告尚未生成", "分析完成 S6 阶段后显示；当前：${stateLabel(task.state)}")
                if (task.state != TaskState.RUNNING.name) {
                    Button(onClick = { vm.resume(task.id); nav.replace(Route.Task(task.id)) }) { Text("继续分析") }
                }
                OutlinedButton(onClick = { nav.go(Route.Workspace(task.id)) }, modifier = Modifier.padding(top = 8.dp)) { Text("进入 AI 工作台") }
            }
            return@Scaffold
        }

        val chainRules = rep.chains.map { it.ruleId }.toSet()
        val byRule = rep.findings.associateBy { it.ruleId }
        val review = rep.aiVerdict?.ruleReview.orEmpty().associateBy { it.ruleId }
        val dismissedRules = rep.findings.filter { it.aiDismissed }.map { it.ruleId }.toSet()
        val (dismissed, kept) = rep.findings.filter { it.ruleId !in chainRules }.sortedBy { it.deduction }
            .partition { it.aiDismissed }
        val (hints, findings) = kept.partition { it.expected || it.deduction == 0 }
        // 能力链：同一规则合并为一张卡，点进证据页逐条查看；AI 判误报的收进折叠区
        val (dismissedChains, chainGroups) = rep.chains.withIndex().groupBy { it.value.ruleId }
            .entries.partition { it.key in dismissedRules }
            .let { (a, b) -> a.associate { it.key to it.value } to b.associate { it.key to it.value } }
        val dismissedCount = dismissed.size + dismissedChains.size
        fun ask(q: String) = nav.go(Route.Workspace(task.id, q))

        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { ScoreHeader(rep) }

            item { SectionHeader("AI 研判") }
            item {
                AiCard(
                    rep,
                    rerunning = task.state == TaskState.RUNNING.name,
                    onRetry = { vm.retryAi(task.id) },
                    onHtml = { nav.go(Route.AiHtml(task.id)) },
                ) { nav.go(Route.Workspace(task.id)) }
            }

            if (chainGroups.isNotEmpty()) {
                item { SectionHeader("能力链 · ${chainGroups.size} 类") }
                chainGroups.forEach { (rule, hits) ->
                    item(key = "chain-$rule") {
                        val first = hits.first()
                        val f = byRule[rule]
                        val c = first.value
                        DeductionCard(
                            title = c.title,
                            rule = rule,
                            deduction = f?.deduction ?: c.deduction,
                            severity = f?.severity ?: "MEDIUM",
                            detail = RuleGlossary.of(rule)?.what ?: "${hits.size} 处命中",
                            count = hits.size,
                            ai = review[rule]?.judgement,
                            onClick = { nav.go(Route.Evidence(task.id, first.index)) },
                        )
                    }
                }
            }

            item { SectionHeader("规则发现 · ${findings.size} 条") }
            if (findings.isEmpty()) {
                item { Text("未命中扣分规则", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(findings, key = { "f-" + it.ruleId }) { f ->
                FindingCard(f, review[f.ruleId]?.judgement, ::ask, onRevert = { vm.revertAiAdjust(task.id, f.ruleId) })
            }

            if (hints.isNotEmpty()) {
                item { SectionHeader("提示 · ${hints.size} 条（预期行为，不扣分）") }
                items(hints, key = { "h-" + it.ruleId }) { f -> FindingCard(f, review[f.ruleId]?.judgement, ::ask) }
            }

            if (dismissedCount > 0) {
                item(key = "dismissed-head") {
                    MrCard(onClick = { showDismissed = !showDismissed }, container = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CheckCircle, null, tint = RiskColors.Low)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("AI 判定误报 · $dismissedCount 条", style = MaterialTheme.typography.titleSmall)
                                Text("已退回扣分，不计入基线分；点击${if (showDismissed) "收起" else "展开查看"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(if (showDismissed) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (showDismissed) {
                    dismissedChains.forEach { (rule, hits) ->
                        item(key = "dchain-$rule") {
                            val f = byRule[rule]
                            DeductionCard(
                                title = hits.first().value.title, rule = rule, deduction = 0, severity = f?.severity ?: "MEDIUM",
                                detail = review[rule]?.reason?.ifBlank { null } ?: RuleGlossary.of(rule)?.what ?: "${hits.size} 处命中",
                                count = hits.size, ai = "误报", original = f?.originalDeduction ?: hits.first().value.deduction,
                                onClick = { nav.go(Route.Evidence(task.id, hits.first().index)) },
                            )
                        }
                    }
                    items(dismissed, key = { "d-" + it.ruleId }) { f -> FindingCard(f, "误报", ::ask, review[f.ruleId]?.reason) }
                }
            }

            extract?.let { ex ->
                item { SectionHeader("包信息与签名") }
                item {
                    SignatureCard(ex, load) {
                        ex.lspatch?.let { LsPatchBlock(it, ex.signerSha256) }
                        ProvenanceBlock(
                            p = rep.provenance,
                            busy = provBusy == task.id,
                            canCheck = task.state != TaskState.RUNNING.name,
                            onRecheck = { vm.recheckProvenance(task) },
                        )
                    }
                }
            }

            if (rep.notes.isNotEmpty()) {
                item { SectionHeader("说明与边界") }
                item {
                    MrCard(container = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            rep.notes.forEach {
                                Row {
                                    Icon(Icons.Outlined.Info, null, Modifier.size(14.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.outline)
                                    Spacer(Modifier.width(6.dp))
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            item {
                Button(onClick = { nav.go(Route.Workspace(task.id)) }, modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
                    Icon(Icons.Outlined.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("AI 深度分析")
                }
            }
        }
    }
}

@Composable
private fun ScoreHeader(rep: ReportArtifact) {
    val color = RiskColors.ofLevel(rep.level)
    MrCard {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ScoreRing(rep.score, size = 140.dp, stroke = 10.dp, caption = levelLabel(rep.level))
            Text(
                when {
                    rep.aiStatus == "DONE" && rep.aiAdjustment != 0 -> "规则扣分制 · 已含 AI 复核修正"
                    rep.aiStatus == "DONE" -> "规则扣分制 · AI 复核无修正"
                    else -> "规则扣分制 · 未配置 AI"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.size(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tag("${rep.highCount} 高危", RiskColors.Critical)
                Tag("${rep.mediumCount} 中危", RiskColors.Medium)
                Tag("${rep.lowCount} 低危", RiskColors.Neutral)
            }
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                TypeBadge(rep.targetKind)
                Text(
                    buildString {
                        append("累计扣分 ${rep.totalDeduction}")
                        if (rep.aiAdjustment != 0) append(" · AI 调整 ${if (rep.aiAdjustment > 0) "+" else ""}${rep.aiAdjustment}")
                        if (rep.aiDismissedAdjustment != 0) append(" · 已退回 ${rep.aiDismissedAdjustment}")
                    },
                    style = MaterialTheme.typography.labelSmall, color = color,
                )
            }
        }
    }
}

@Composable
private fun DeductionCard(
    title: String, rule: String, deduction: Int, severity: String, detail: String,
    count: Int = 1, ai: String? = null, original: Int = 0, onClick: (() -> Unit)? = null,
) {
    val color = if (deduction == 0) RiskColors.Neutral else RiskColors.ofSeverity(severity)
    MrCard(onClick = onClick, container = color.copy(alpha = 0.12f)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (severity == "HIGH") Icons.Outlined.Warning else Icons.Outlined.Link, null, tint = color)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false), maxLines = 2)
                    Tag(rule, color)
                }
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (count > 1) Tag("$count 处命中 · 只计一次", MaterialTheme.colorScheme.outline)
                    ai?.let { AiJudgeTag(it) }
                }
            }
            Spacer(Modifier.width(8.dp))
            if (original != 0) StrikeScore(original)
            else Text(if (deduction == 0) "0" else "$deduction", style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
            if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FindingCard(
    f: ScoreFinding,
    ai: String?,
    onAsk: (String) -> Unit,
    aiReason: String? = null,
    onRevert: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val color = if (f.deduction == 0) RiskColors.Neutral else RiskColors.ofSeverity(f.severity)
    val g = RuleGlossary.of(f.ruleId)
    MrCard(onClick = { expanded = !expanded }, container = if (f.deduction != 0) color.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(f.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                        Tag(f.ruleId, color)
                        ai?.let { AiJudgeTag(it) }
                        if (f.aiAggravated) Tag("AI 加重", RiskColors.High)
                    }
                    Text(f.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (expanded) 20 else 2, overflow = TextOverflow.Ellipsis)
                    aiReason?.takeIf { it.isNotBlank() }?.let {
                        Text("AI：$it", style = MaterialTheme.typography.bodySmall, color = RiskColors.Low, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                if (f.aiAdjustment != 0 && f.aiAggravated) {
                    // 加重：原扣分划线 + 追加值
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        StrikeScore(f.deduction - f.aiAdjustment)
                        Text("${f.deduction}", style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
                    }
                } else if (f.originalDeduction != 0) {
                    Spacer(Modifier.width(8.dp))
                    StrikeScore(f.originalDeduction)
                } else if (f.deduction != 0) {
                    Spacer(Modifier.width(8.dp))
                    Text("${f.deduction}", style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
                }
            }
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                g?.let { Explain(it) }
                f.evidence.take(10).forEachIndexed { i, e ->
                    Text("E${i + 1}  ${prettySmali(e)}", style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        onAsk("请解释规则 ${f.ruleId}「${f.title}」：${f.detail}。证据：${f.evidence.take(5).joinToString("；") { prettySmali(it) }}。它在这个目标里具体做了什么？对我有什么实际危害？是否可能是误报？")
                    }) { Icon(Icons.Outlined.QuestionAnswer, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("就这条问 AI") }
                    if (onRevert != null && (f.aiAggravated || f.aiAdjustment != 0)) {
                        TextButton(onClick = onRevert) {
                            Icon(Icons.Outlined.Undo, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("退回此修正")
                        }
                    }
                }
            } else if (f.evidence.isNotEmpty() || g != null) {
                Text("${if (f.evidence.isNotEmpty()) "${f.evidence.size} 条证据 · " else ""}点击展开说明", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** 被 AI 判误报退回的扣分：划线显示原值。 */
@Composable
private fun StrikeScore(original: Int) {
    Text(
        "$original", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline,
        textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
    )
}

/** 规则释义三段：是什么 / 可能的危害 / 什么情况下正常。 */
@Composable
fun Explain(g: RuleGlossary.Entry) {
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ExplainRow(Icons.Outlined.Info, "这是什么", g.what, MaterialTheme.colorScheme.primary)
        ExplainRow(Icons.Outlined.Warning, "可能的危害", g.harm, RiskColors.High)
        ExplainRow(Icons.Outlined.CheckCircle, "什么情况下正常", g.benign, RiskColors.Low)
    }
}

@Composable
private fun ExplainRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, color: androidx.compose.ui.graphics.Color) {
    Row {
        Icon(icon, null, Modifier.size(16.dp).padding(top = 2.dp), tint = color)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.labelMedium, color = color)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun AiJudgeTag(j: String) {
    val c = when (j) { "误报" -> RiskColors.Low; "加重" -> RiskColors.Critical; "成立" -> RiskColors.Medium; else -> RiskColors.Neutral }
    Tag("AI：$j", c)
}

@Composable
private fun SignatureCard(ex: ExtractArtifact, load: com.modradar.app.task.stages.LoadArtifact?, extra: @Composable () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    MrCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Fingerprint, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("签名证书", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                val schemes = ex.signatureSchemes.ifEmpty {
                    listOfNotNull("v1".takeIf { ex.v1Signed }, "v2+".takeIf { ex.apkSigningBlock })
                }
                schemes.forEach { Spacer(Modifier.width(4.dp)); Tag(it, muted) }
            }
            Text(
                ex.signerSubject ?: if (ex.v1Signed || ex.apkSigningBlock) "未能读取证书主体" else "未签名",
                style = MaterialTheme.typography.bodySmall, color = muted,
            )
            ex.signerSha256?.let { InfoLine("证书 SHA-256", it.chunked(2).joinToString(":")) }
            ex.manifest?.let { m ->
                InfoLine("包名", m.packageName ?: "?")
                InfoLine("版本", "${m.versionName ?: "?"} (${m.versionCode ?: "?"}) · target ${m.targetSdk ?: "?"} · min ${m.minSdk ?: "?"}")
            }
            load?.let { l ->
                InfoLine("文件", "${l.fileName} · ${formatBytes(l.sizeBytes)} · ${l.entryCount} 个条目")
                InfoLine("文件 SHA-256", l.sha256)
            }
            extra()
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(value, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatBytes(n: Long): String = when {
    n >= 1 shl 20 -> "%.1f MB".format(n / 1048576.0)
    n >= 1 shl 10 -> "%.1f KB".format(n / 1024.0)
    else -> "$n B"
}

@Composable
private fun AiCard(rep: ReportArtifact, rerunning: Boolean, onRetry: () -> Unit, onHtml: () -> Unit, onOpen: () -> Unit) {
    val v = rep.aiVerdict
    if (rerunning) AiFallbackCard(rep.copy(aiStatus = "RUNNING", aiSummary = null), onRetry, onOpen)
    else if (rep.aiStatus == "DONE" && v != null) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val color = RiskColors.ofLevel(rep.level)
        // 可视化报告入口：固定模板渲染，点进去看完整版
        MrCard(onClick = onHtml, container = color.copy(alpha = 0.12f)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Insights, null, tint = color)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("AI 可视化报告", style = MaterialTheme.typography.titleSmall)
                    Text("总览 · 关键发现 · 规则复核 · 建议", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        MrCard(onClick = onOpen) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tag(v.verdict.ifBlank { "AI 研判" }, color, filled = true)
                    rep.aiModel?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
                }
                if (v.headline.isNotBlank()) Text(v.headline, style = MaterialTheme.typography.titleSmall)
                if (v.overview.isNotBlank()) Markdown(v.overview, style = MaterialTheme.typography.bodySmall)
                v.findings.take(3).forEach { f ->
                    Row(verticalAlignment = Alignment.Top) {
                        Tag(sevShort(f.severity), RiskColors.ofSeverity(f.severity))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.title, style = MaterialTheme.typography.bodyMedium)
                            if (f.impact.isNotBlank()) Text(f.impact, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text("进入工作台继续追问", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    } else AiFallbackCard(rep, onRetry, onOpen)
}

@Composable
private fun AiFallbackCard(rep: ReportArtifact, onRetry: () -> Unit, onOpen: () -> Unit) {
    val (label, color, icon) = when (rep.aiStatus) {
        "DONE" -> Triple("已完成（未按结构化格式输出，显示原文）", RiskColors.Low, Icons.Outlined.SmartToy)
        "FAILED" -> Triple("AI 调用失败", RiskColors.Critical, Icons.Outlined.Warning)
        "RUNNING" -> Triple("研判中…", MaterialTheme.colorScheme.primary, Icons.Outlined.Refresh)
        else -> Triple("未完成 AI 研判（未配置 AI 接口）", RiskColors.Neutral, Icons.Outlined.Info)
    }
    MrCard(onClick = onOpen) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (rep.aiStatus == "RUNNING") CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(icon, null, tint = color)
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = color)
            }
            if (rep.aiStatus == "FAILED") {
                rep.aiError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis) }
            } else {
                aiReadable(rep.aiSummary)?.let { Markdown(it.take(3000), style = MaterialTheme.typography.bodySmall) }
            }
            // 失败或格式不对：直接在报告页重跑 S5/S6，不必回进度页
            // 配置 AI 后也可直接补跑，不必重新分析
            if (rep.aiStatus != "RUNNING") {
                FilledTonalButton(onClick = onRetry) {
                    Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(if (rep.aiStatus == "NOT_CONFIGURED") "运行 AI 研判" else "重试 AI 研判")
                }
            }
        }
    }
}

private fun sevShort(s: String) = when (s.uppercase()) { "CRITICAL" -> "严重"; "HIGH" -> "高"; "MEDIUM" -> "中"; "LOW" -> "低"; else -> "提示" }

/** AI 结论可能是固定 JSON（P7），也可能是自然语言；尽量取出可读文本。 */
fun aiReadable(raw: String?): String? {
    val s = raw?.trim()?.removePrefix("```json")?.removePrefix("```")?.removeSuffix("```")?.trim() ?: return null
    if (s.isEmpty()) return null
    if (!s.startsWith("{")) return s
    return runCatching {
        val o = kotlinx.serialization.json.Json.parseToJsonElement(s) as kotlinx.serialization.json.JsonObject
        val summary = (o["summary"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val recs = (o["recommendations"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        buildString {
            summary?.let { append(it) }
            if (!recs.isNullOrEmpty()) {
                append("\n\n建议：\n")
                recs.forEach { append("· ").append(it).append('\n') }
            }
        }.trim().ifEmpty { s }
    }.getOrDefault(s)
}

private fun shareText(rep: ReportArtifact): String = buildString {
    append("【ModRadar 报告】${rep.targetName}\n")
    append(
        buildString {
            append("评分 ${rep.score}（${levelLabel(rep.level)}）")
            if (rep.aiAdjustment != 0) append("，AI 复核调整 ${if (rep.aiAdjustment > 0) "+" else ""}${rep.aiAdjustment}")
        },
    )
    append("，高危 ${rep.highCount} / 中危 ${rep.mediumCount} / 低危 ${rep.lowCount}\n")
    rep.findings.sortedBy { it.deduction }.take(8).forEach { append("· ${it.ruleId} ${it.title} ${it.deduction}\n") }
    append("AI 研判：").append(rep.aiVerdict?.headline?.ifBlank { null } ?: if (rep.aiStatus == "DONE") "已完成" else "未完成")
}
