package com.modradar.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowDown
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import com.modradar.app.report.ChatHtml
import com.modradar.app.report.HtmlKit
import com.modradar.app.task.TaskState
import kotlinx.coroutines.delay
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modradar.app.Navigator
import com.modradar.app.Route
import com.modradar.app.ai.ChatEvent
import com.modradar.app.ai.ToolMsgMeta
import com.modradar.app.data.db.AnalysisTaskEntity
import com.modradar.app.data.db.ChatMessageEntity
import com.modradar.app.data.db.StageCheckpointEntity
import com.modradar.app.task.Stage
import com.modradar.app.task.StageState
import com.modradar.app.ui.components.Banner
import com.modradar.app.ui.components.EmptyState
import com.modradar.app.ui.components.MrCard
import com.modradar.app.ui.components.Markdown
import com.modradar.app.ui.components.MrTopBar
import com.modradar.app.ui.ime.LocalImeController
import com.modradar.app.ui.ime.optimizedImePadding
import com.modradar.app.ui.theme.MonoStyle
import com.modradar.app.ui.theme.RiskColors
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private val SUGGESTIONS = listOf(
    "会从远端下载代码并执行吗？",
    "有没有隐私数据外传？",
    "声明的权限和实际行为一致吗？",
    "总结风险并给出建议",
)

/**
 * AI 逆向工作台。
 *
 * 与流水线状态解耦：分析暂停 / 失败 / 未跑完时都能进入对话，
 * 顶部横幅显式标注「结论基于部分数据」，工具会返回 data_not_ready。
 */
/** 对话流里的一项：连续的工具调用合并为一组。 */
private sealed interface WsItem {
    val key: String
    data class Msg(val m: ChatMessageEntity) : WsItem { override val key get() = "m${m.id}" }
    data class Tools(val done: List<ChatMessageEntity>, val running: List<ChatEvent.ToolStarted>) : WsItem {
        override val key get() = "t" + (done.firstOrNull()?.id?.toString() ?: running.first().id)
    }
}

private fun group(msgs: List<ChatMessageEntity>, running: List<ChatEvent.ToolStarted>): List<WsItem> {
    val out = mutableListOf<WsItem>()
    var buf = mutableListOf<ChatMessageEntity>()
    fun flush() { if (buf.isNotEmpty()) { out += WsItem.Tools(buf, emptyList()); buf = mutableListOf() } }
    for (m in msgs) {
        if (m.role == "tool") buf += m else { flush(); out += WsItem.Msg(m) }
    }
    if (running.isNotEmpty()) { out += WsItem.Tools(buf, running); buf = mutableListOf() }
    flush()
    return out
}

/**
 * AI 逆向工作台。
 *
 * 与流水线状态解耦：分析暂停 / 失败 / 未跑完时都能进入对话，
 * 顶部横幅显式标注「结论基于部分数据」，工具会返回 data_not_ready。
 *
 * 键盘：输入栏是页面 Column 的最后一个元素（不放 Scaffold.bottomBar），只用 [optimizedImePadding] 让位。
 */
@Composable
fun WorkspaceScreen(vm: AppViewModel, task: AnalysisTaskEntity, nav: Navigator, prefill: String? = null) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val events by vm.chatEvents.collectAsStateWithLifecycle()
    val streaming by vm.streaming.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val checkpoints by vm.checkpoints.collectAsStateWithLifecycle()
    val ai by vm.aiSettings.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf(prefill.orEmpty()) }
    val listState = rememberLazyListState()
    val ime = LocalImeController.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(task.id) { vm.refreshMessages(task.id) }
    // 分析进行中时定期刷新阶段进度，横幅显示「进行中」而不是「停在」
    val analysing = task.state == TaskState.RUNNING.name
    LaunchedEffect(task.id, task.state) {
        vm.refreshCheckpoints(task.id)
        while (analysing) { delay(1500); vm.refreshCheckpoints(task.id) }
    }

    val pending = pendingStages(checkpoints)
    val visible = messages.filter { !(it.role == "assistant" && it.content.isBlank() && it.reasoning.isNullOrBlank()) }
    val finished = events.filterIsInstance<ChatEvent.ToolFinished>().map { it.id }.toSet()
    val runningTools = events.filterIsInstance<ChatEvent.ToolStarted>().filter { it.id !in finished }
    val items = group(visible, runningTools)
    val live = streaming?.takeIf { busy && (it.content.isNotEmpty() || it.reasoning.isNotEmpty()) }

    // 跟随输出：用户往上拖动即暂停自动滚动，回到底部后恢复。程序滚动不经过 nestedScroll，不会误判。
    var follow by remember { mutableStateOf(true) }
    val followConn = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f) follow = false
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (consumed.y < 0f && !listState.canScrollForward) follow = true
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (!listState.canScrollForward) follow = true
                return Velocity.Zero
            }
        }
    }
    suspend fun toBottom() {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last < 0) return
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == last }) listState.scrollToItem(last)
        listState.scrollBy(100_000f)
    }
    LaunchedEffect(items.size, runningTools.size, busy, live?.content?.length, live?.reasoning?.length) {
        if (follow) toBottom()
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isNotEmpty() && !busy) {
            input = ""
            follow = true
            vm.sendChat(task.id, t)
        }
    }

    /** 跳转 / 弹窗前先收起键盘，避免下一页带着残留的 IME 让位。 */
    fun leave(action: () -> Unit) {
        scope.launch { ime?.hideAndAwait(); action() }
    }

    Scaffold(
        topBar = {
            MrTopBar("AI 逆向工作台", subtitle = "${task.targetName}${if (ai.configured) " · ${ai.model}" else ""}", onBack = { leave { nav.back() } }) {
                if (messages.isNotEmpty()) IconButton(onClick = { vm.clearChat(task.id) }, enabled = !busy) {
                    Icon(Icons.Outlined.DeleteSweep, "清空对话")
                }
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        Column(Modifier.padding(top = pad.calculateTopPadding()).fillMaxSize()) {
          Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize().nestedScroll(followConn),
                state = listState,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!ai.configured) {
                    item {
                        Banner(Icons.Outlined.SmartToy, "尚未配置 AI 接口，无法对话", MaterialTheme.colorScheme.primary) {
                            TextButton(onClick = { leave { nav.go(Route.AiSettings) } }) { Text("去配置") }
                        }
                    }
                }
                if (pending.isNotEmpty()) {
                    item {
                        if (analysing) Banner(
                            Icons.Outlined.Sync,
                            "分析进行中 · ${pending.first().id} ${pending.first().label} · 现在提问只能用到已完成阶段的数据",
                            MaterialTheme.colorScheme.primary,
                        ) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
                        else Banner(
                            Icons.Outlined.HourglassEmpty,
                            "分析停在 ${pending.first().id} ${pending.first().label} · 结论基于部分数据",
                            RiskColors.Medium,
                        ) { TextButton(onClick = { vm.resume(task.id) }) { Text("继续分析") } }
                    }
                }
                if (visible.isEmpty() && ai.configured) {
                    item { EmptyState(Icons.Outlined.SmartToy, "问点什么", "AI 会调用只读工具取证，再给出结论") }
                }
                items(items, key = { it.key }) { it ->
                    when (it) {
                        is WsItem.Tools -> ToolGroup(it.done, it.running)
                        is WsItem.Msg -> if (it.m.role == "user") UserBubble(it.m.content)
                        else AssistantBubble(
                            it.m.content, it.m.reasoning,
                            onHtml = { leave { nav.go(Route.ChatHtml(task.id, it.m.id)) } },
                        ) { leave { nav.go(Route.AiSettings) } }
                    }
                }
                if (live != null) {
                    item(key = "live") { AssistantBubble(live.content, live.reasoning, streaming = true, onHtml = {}, onSettings = {}) }
                } else if (busy && runningTools.isEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("思考中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (!follow && busy) {
                SmallFloatingActionButton(
                    onClick = { follow = true; scope.launch { toBottom() } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                ) { Icon(Icons.Outlined.KeyboardDoubleArrowDown, "回到最新") }
            }
          }

            // 输入栏：背景先于让位，保证抬起时背景跟随，不露出下方空白
            Column(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .optimizedImePadding()
                    .navigationBarsPadding()
                    .padding(top = 8.dp)
            ) {
                if (visible.isEmpty() && ai.configured && input.isEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SUGGESTIONS.forEach { s -> AssistChip(onClick = { send(s) }, label = { Text(s) }, enabled = !busy) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (ai.configured) "追问，例如：解密后加载的是什么？" else "先在设置里配置 AI 接口") },
                        maxLines = 5,
                        shape = RoundedCornerShape(24.dp),
                        enabled = ai.configured,
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(onClick = { send(input) }, enabled = !busy && input.isNotBlank() && ai.configured) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.AutoMirrored.Filled.Send, "发送")
                    }
                }
            }
        }
    }
}

private fun pendingStages(cps: List<StageCheckpointEntity>): List<Stage> {
    val done = cps.filter { it.state == StageState.DONE.name }.mapNotNull { Stage.byId(it.stage) }.toSet()
    return Stage.order.filter { it !in done }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box(
            Modifier.widthIn(max = 300.dp)
                .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(text, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AssistantBubble(
    text: String,
    reasoning: String?,
    streaming: Boolean = false,
    onHtml: () -> Unit,
    onSettings: () -> Unit,
) {
    val failed = text.startsWith("（AI 调用失败）") || text.startsWith("（尚未配置")
    val split = remember(text) { ChatHtml.extract(text) }
    val body = split?.markdown ?: text
    MrCard(container = if (failed) RiskColors.Critical.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (failed) Icons.Outlined.ErrorOutline else Icons.Outlined.SmartToy, null, Modifier.size(16.dp), tint = if (failed) RiskColors.Critical else MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(if (failed) "调用失败" else if (streaming) "AI 输出中" else "AI 判定", style = MaterialTheme.typography.labelMedium, color = if (failed) RiskColors.Critical else MaterialTheme.colorScheme.primary)
            }
            if (!reasoning.isNullOrBlank()) ThinkingBlock(reasoning, live = streaming && text.isEmpty())
            if (body.isNotBlank()) {
                if (streaming) Markdown(body) else SelectionContainer { Markdown(aiReadable(body) ?: body) }
            }
            when {
                split?.html != null && !streaming -> HtmlCard(HtmlKit.firstHeading(HtmlKit.sanitize(split.html)) ?: "可视化摘要", onHtml)
                split?.pending == true || (streaming && split?.html != null) -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在生成可视化卡片…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (failed) TextButton(onClick = onSettings) { Text("检查 AI 设置") }
        }
    }
}

/** 文末可视化卡片入口：点开全屏查看（固定样式渲染，JS 与网络关闭）。 */
@Composable
private fun HtmlCard(title: String, onClick: () -> Unit) {
    MrCard(onClick = onClick, container = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Insights, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("可视化摘要 · 卡片 / 流程 / 导图", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 思考过程：默认折叠；展开后限高内部滚动，内容增长不撑高气泡、不带动整页滚动。
 * 输出中自动跟到最新，用户在框内往上翻即停止跟随。
 */
@Composable
private fun ThinkingBlock(text: String, live: Boolean) {
    var open by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    var stick by remember { mutableStateOf(true) }
    val conn = remember(scroll) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f) stick = false
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (!scroll.canScrollForward) stick = true
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(open, text.length) { if (open && live && stick) scroll.scrollTo(scroll.maxValue) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (live) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            else Icon(Icons.Outlined.Psychology, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(
                (if (live) "思考中" else "思考过程") + " · ${text.length} 字",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (open) "收起思考" else "展开思考", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) {
            Box(Modifier.fillMaxWidth().heightIn(max = 220.dp).nestedScroll(conn).verticalScroll(scroll).padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val metaJson = Json { ignoreUnknownKeys = true }

/** 连续工具调用合并为一张卡片：默认一行摘要，点开逐个查看。 */
@Composable
private fun ToolGroup(done: List<ChatMessageEntity>, running: List<ChatEvent.ToolStarted>) {
    var expanded by remember { mutableStateOf(false) }
    val metas = done.map { m -> m to m.toolCallsJson?.let { runCatching { metaJson.decodeFromString(ToolMsgMeta.serializer(), it) }.getOrNull() } }
    val names = metas.map { (m, meta) -> meta?.name ?: m.toolCallsJson ?: "tool" } + running.map { it.name }
    val failed = metas.count { (m, _) -> m.content.startsWith("{\"error\"") }
    val notReady = metas.count { (_, meta) -> meta?.notReady == true }
    val totalMs = metas.sumOf { (_, meta) -> meta?.ms ?: 0L }
    MrCard(onClick = { expanded = !expanded }, container = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (running.isNotEmpty()) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Build, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (running.isNotEmpty()) "正在调用工具 · 已完成 ${done.size}/${names.size}" else "调用了 ${names.size} 个工具",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        names.distinct().joinToString(" · "),
                        style = MonoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) 4 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val (label, color) = when {
                    failed > 0 -> "$failed 失败" to RiskColors.Critical
                    notReady > 0 -> "$notReady 未就绪" to RiskColors.Medium
                    running.isNotEmpty() -> "" to RiskColors.Neutral
                    else -> (if (totalMs > 0) "${totalMs}ms" else "完成") to RiskColors.Low
                }
                if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelSmall, color = color)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "收起" else "展开", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    metas.forEach { (m, meta) -> ToolRow(m, meta) }
                    running.forEach { e -> RunningTool(e.name, e.args) }
                }
            }
        }
    }
}

@Composable
private fun ToolRow(m: ChatMessageEntity, meta: ToolMsgMeta?) {
    var open by remember { mutableStateOf(false) }
    val name = meta?.name ?: m.toolCallsJson ?: "tool"
    val isErr = m.content.startsWith("{\"error\"")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { open = !open }.padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isErr) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle, null, Modifier.size(14.dp),
                tint = if (meta?.notReady == true) RiskColors.Medium else if (isErr) RiskColors.Critical else RiskColors.Low,
            )
            Spacer(Modifier.width(6.dp))
            Text(name, style = MonoStyle, modifier = Modifier.weight(1f))
            Text(
                when {
                    meta?.notReady == true -> "数据未就绪"
                    isErr -> "失败"
                    else -> "完成"
                } + (meta?.ms?.takeIf { it > 0 }?.let { " · ${it}ms" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            m.content,
            style = MonoStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (open) 60 else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun RunningTool(name: String, args: String) {
    Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text("$name(${args.take(60)})", style = MonoStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
