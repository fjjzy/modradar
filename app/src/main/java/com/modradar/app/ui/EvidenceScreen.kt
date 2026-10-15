package com.modradar.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modradar.app.Navigator
import com.modradar.app.Route
import com.modradar.app.data.db.AnalysisTaskEntity
import com.modradar.app.task.stages.ChainHit
import com.modradar.app.task.stages.RuleGlossary
import com.modradar.app.ui.components.Banner
import com.modradar.app.ui.components.EmptyState
import com.modradar.app.ui.components.MrCard
import com.modradar.app.ui.components.MrTopBar
import com.modradar.app.ui.components.SectionHeader
import com.modradar.app.ui.components.Tag
import com.modradar.app.ui.theme.MonoStyle
import com.modradar.app.ui.theme.RiskColors
import kotlinx.coroutines.launch

/**
 * 证据链详情：同一规则的全部命中可切换查看；
 * 顶部给出通俗释义（是什么 / 危害 / 何时正常），底部可带着证据直接追问 AI。
 */
@Composable
fun EvidenceScreen(vm: AppViewModel, task: AnalysisTaskEntity, index: Int, nav: Navigator) {
    val report by vm.report.collectAsStateWithLifecycle()
    val extract by vm.extract.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(task.id) { vm.loadReport(task.id) }
    val first = report?.chains?.getOrNull(index)
    val siblings = report?.chains.orEmpty().filter { it.ruleId == first?.ruleId }
    var pick by rememberSaveable(index) { mutableIntStateOf(0) }
    val hit = siblings.getOrNull(pick) ?: first
    val finding = report?.findings?.firstOrNull { it.ruleId == first?.ruleId }
    val judge = report?.aiVerdict?.ruleReview?.firstOrNull { it.ruleId == first?.ruleId }

    Scaffold(
        topBar = {
            MrTopBar("证据链", subtitle = hit?.let { "${it.ruleId} · ${it.title}" }, onBack = { nav.back() }) {
                if (hit != null) IconButton(onClick = {
                    clipboard.setText(AnnotatedString(evidenceText(task.targetName, hit)))
                    scope.launch { snack.showSnackbar("已复制证据") }
                }) { Icon(Icons.Outlined.ContentCopy, "复制证据") }
            }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        if (hit == null) {
            Column(Modifier.padding(pad)) { EmptyState(Icons.Outlined.Shield, "证据不存在") }
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RuleGlossary.of(hit.ruleId)?.let { g ->
                item { MrCard { Column(Modifier.padding(14.dp)) { Explain(g) } } }
            }

            judge?.let { j ->
                item {
                    Banner(Icons.Outlined.SmartToy, "AI 复核：${j.judgement}${if (j.reason.isNotBlank()) " · ${j.reason}" else ""}", RiskColors.ofSeverity(if (j.judgement == "误报") "LOW" else "MEDIUM"))
                }
            }

            if (siblings.size > 1) {
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        siblings.forEachIndexed { i, _ ->
                            FilterChip(selected = i == pick, onClick = { pick = i }, label = { Text("命中 ${i + 1}") })
                        }
                    }
                }
            }

            item { ChainFlow(hit) }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val d = finding?.deduction ?: hit.deduction
                    Tag(if (d == 0) "不扣分 · 预期行为" else "扣分 $d${if (siblings.size > 1) " · 共 ${siblings.size} 处只计一次" else ""}", if (d == 0) RiskColors.Neutral else RiskColors.Critical)
                    if (hit.sameMethod) Tag("同一方法内", RiskColors.High)
                    if (hit.reachable) Tag("调用链可达", RiskColors.Medium)
                }
            }

            item { SectionHeader("代码位置 · ${hit.evidence.size} 条") }
            itemsIndexed(hit.evidence) { i, e ->
                MrCard(container = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Tag("E${i + 1}", MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Tag(kindLabel(e.kind), kindTagColor(e.kind))
                        }
                        Text(humanDetail(e.detail), style = MaterialTheme.typography.bodyMedium)
                        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            Text(prettySmali(e.location), style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (extract?.shellHints?.isNotEmpty() == true) {
                item {
                    Banner(
                        Icons.Outlined.Lock,
                        "该 APK 疑似被加固（${extract?.shellHints?.take(3)?.joinToString("、")}），真实代码可能不可见，已按不可审计计入风险",
                        RiskColors.Medium,
                    )
                }
            }

            item {
                Button(onClick = {
                    nav.go(Route.Workspace(task.id, askText(hit)))
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.QuestionAnswer, null); Spacer(Modifier.width(6.dp)); Text("就这条证据问 AI")
                }
            }
            item {
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(evidenceText(task.targetName, hit)))
                    scope.launch { snack.showSnackbar("已复制证据") }
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("复制证据")
                }
            }
        }
    }
}

/** 证据追问草稿：带上规则、三要素与定位，AI 可直接据此调用工具。 */
private fun askText(hit: ChainHit): String = buildString {
    append("请解读这条证据（规则 ${hit.ruleId}「${hit.title}」）：")
    if (hit.sources.isNotEmpty()) append("来源 ${hit.sources.joinToString("、")}；")
    if (hit.transforms.isNotEmpty()) append("变换 ${hit.transforms.joinToString("、")}；")
    if (hit.sinks.isNotEmpty()) append("执行点 ${hit.sinks.joinToString("、")}；")
    hit.evidence.map { it.location }.filter { it != "全局" }.distinct().take(3).forEach { append("位置 ").append(prettySmali(it)).append("；") }
    append("\n这段代码实际在做什么？对我有什么危害？是否可能是误报？")
}

private fun kindLabel(kind: String) = when (kind) {
    "method" -> "方法"; "api" -> "调用"; "chain" -> "调用链"; "capability" -> "能力"; "file" -> "文件"; else -> kind
}

/** "SOURCE: OkHttp, DexClassLoader" → 带中文说明的版本。 */
private fun humanDetail(d: String): String {
    val m = Regex("^(SOURCE|TRANSFORM|SINK): (.*)$").find(d) ?: return d
    val head = when (m.groupValues[1]) { "SOURCE" -> "数据来源"; "TRANSFORM" -> "数据变换"; else -> "执行点" }
    return head + "：" + m.groupValues[2].split(", ").joinToString("、") { RuleGlossary.apiLabel(it) }
}

@Composable
private fun ChainFlow(hit: ChainHit) {
    MrCard {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            FlowNode(Icons.Outlined.Public, "取数据", hit.sources.map(RuleGlossary::apiLabel), Color(0xFF7FA7FF))
            if (hit.transforms.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.outline)
                FlowNode(Icons.Outlined.Lock, "解密/解码", hit.transforms.map(RuleGlossary::apiLabel), RiskColors.Medium)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.outline)
            FlowNode(Icons.Outlined.Warning, "执行", hit.sinks.map(RuleGlossary::apiLabel), RiskColors.Critical)
        }
    }
}

@Composable
private fun FlowNode(icon: ImageVector, label: String, items: List<String>, color: Color) {
    MrCard(modifier = Modifier.widthIn(max = 104.dp), container = color.copy(alpha = 0.16f)) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = color)
            Text(
                items.take(2).joinToString("\n").ifEmpty { "—" },
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 3,
            )
        }
    }
}

private fun kindTagColor(kind: String): Color = when (kind.uppercase()) {
    "SOURCE" -> Color(0xFF7FA7FF)
    "SINK" -> RiskColors.Critical
    "TRANSFORM" -> RiskColors.Medium
    else -> RiskColors.Neutral
}

private fun evidenceText(target: String, hit: ChainHit): String = buildString {
    append("目标：$target\n规则：${hit.ruleId} ${hit.title}（${hit.deduction}）\n")
    if (hit.sources.isNotEmpty()) append("SOURCE: ${hit.sources.joinToString(", ")}\n")
    if (hit.transforms.isNotEmpty()) append("TRANSFORM: ${hit.transforms.joinToString(", ")}\n")
    if (hit.sinks.isNotEmpty()) append("SINK: ${hit.sinks.joinToString(", ")}\n")
    hit.evidence.forEachIndexed { i, e -> append("E${i + 1} [${e.kind}] ${e.detail} @ ${e.location}\n") }
}

/** smali 引用 `La/b/C;->m` → `a.b.C#m`，便于阅读。 */
fun prettySmali(s: String): String =
    Regex("L([\\w/$]+);->([\\w<>$]+)").replace(s) { m -> m.groupValues[1].replace('/', '.') + "#" + m.groupValues[2] }
