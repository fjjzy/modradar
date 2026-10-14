package com.modradar.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modradar.app.data.db.AnalysisTaskEntity
import com.modradar.app.task.stages.ReportArtifact
import com.modradar.app.task.stages.ScoreFinding

/** 风险报告页：渲染 S6 产物。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    vm: AppViewModel,
    task: AnalysisTaskEntity,
    onBack: () -> Unit,
    onOpenWorkspace: () -> Unit,
) {
    val report by vm.report.collectAsStateWithLifecycle()

    LaunchedEffect(task.id) { vm.loadReport(task.id) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("风险报告") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { pad ->
        val rep = report
        if (rep == null) {
            Column(Modifier.padding(pad).padding(24.dp)) {
                Text("报告尚未生成（需要 S6 阶段完成）", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onOpenWorkspace) { Text("去 AI 工作台") }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { ScoreHeader(rep) }

            if (rep.chains.isNotEmpty()) {
                item { SectionTitle("能力链命中（${rep.chains.size}）") }
                items(rep.chains) { c ->
                    InfoCard(
                        title = "${c.ruleId} ${c.title}",
                        subtitle = "${c.deduction}",
                        body = buildString {
                            if (c.sources.isNotEmpty()) append("SOURCE: ${c.sources.joinToString(", ")}\n")
                            if (c.transforms.isNotEmpty()) append("TRANSFORM: ${c.transforms.joinToString(", ")}\n")
                            if (c.sinks.isNotEmpty()) append("SINK: ${c.sinks.joinToString(", ")}\n")
                            c.evidence.forEach { append("证据：${it.detail} @ ${it.location}\n") }
                        }.trim(),
                    )
                }
            }

            if (rep.findings.isNotEmpty()) {
                item { SectionTitle("规则发现（${rep.findings.size}）") }
                items(rep.findings) { f -> FindingCard(f) }
            }

            item { SectionTitle("AI 研判") }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            when (rep.aiStatus) {
                                "DONE" -> "已完成（AI 结论不计入总分）"
                                "FAILED" -> "调用失败"
                                else -> "未完成 AI 研判（未配置 AI 接口）"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        rep.aiSummary?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it.take(4000), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (rep.notes.isNotEmpty()) {
                item { SectionTitle("说明与边界") }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(14.dp)) {
                            rep.notes.forEach {
                                Text("· $it", style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScoreHeader(rep: ReportArtifact) {
    val color = when (rep.level) {
        "LOW" -> Color(0xFF4CAF50)
        "MEDIUM" -> Color(0xFFFFB300)
        "HIGH" -> Color(0xFFFF7043)
        else -> Color(0xFFE53935)
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { rep.score / 100f },
                    modifier = Modifier.size(120.dp),
                    color = color,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeWidth = 8.dp,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${rep.score}", style = MaterialTheme.typography.headlineMedium)
                    Text(levelLabel(rep.level), style = MaterialTheme.typography.labelSmall, color = color)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CountChip("高危 ${rep.highCount}", Color(0xFFE53935))
                CountChip("中危 ${rep.mediumCount}", Color(0xFFFFB300))
                CountChip("低危 ${rep.lowCount}", Color(0xFF9E9E9E))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${rep.targetName} · ${rep.targetKind}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun levelLabel(level: String) = when (level) {
    "LOW" -> "低风险"
    "MEDIUM" -> "中风险"
    "HIGH" -> "较高风险"
    else -> "高风险"
}

@Composable
private fun CountChip(text: String, color: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.18f))) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun FindingCard(f: ScoreFinding) {
    val color = when (f.severity) {
        "HIGH" -> Color(0xFFE53935)
        "MEDIUM" -> Color(0xFFFFB300)
        "LOW" -> Color(0xFF9E9E9E)
        else -> MaterialTheme.colorScheme.outline
    }
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f))) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${f.ruleId} ${f.title}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (f.deduction != 0) {
                    Text("${f.deduction}", style = MaterialTheme.typography.titleSmall, color = color)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(f.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (f.evidence.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                f.evidence.take(5).forEach {
                    Text("证据：$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, subtitle: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(subtitle, style = MaterialTheme.typography.titleSmall, color = Color(0xFFFF7043))
            }
            if (body.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
