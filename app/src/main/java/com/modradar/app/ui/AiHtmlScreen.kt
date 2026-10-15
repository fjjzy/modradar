package com.modradar.app.ui

import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pending
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modradar.app.Navigator
import com.modradar.app.data.db.AnalysisTaskEntity
import com.modradar.app.report.AiReportHtml
import com.modradar.app.report.ChatHtml
import com.modradar.app.report.HtmlKit
import com.modradar.app.ui.components.EmptyState
import com.modradar.app.ui.components.MrTopBar

/** AI 可视化报告：本地固定模板渲染 [com.modradar.app.task.stages.AiVerdict]，WebView 关闭 JS。 */
@Composable
fun AiHtmlScreen(vm: AppViewModel, task: AnalysisTaskEntity, nav: Navigator) {
    val report by vm.report.collectAsStateWithLifecycle()
    LaunchedEffect(task.id) { vm.loadReport(task.id) }
    val rep = report
    val html = remember(rep) { rep?.aiVerdict?.let { AiReportHtml.render(rep, it) } }
    HtmlViewer(
        vm, nav, "AI 可视化报告", task.targetName, html,
        exportName = "ModRadar-${task.targetName.substringBeforeLast('.')}.html",
        emptyTitle = "暂无结构化 AI 研判", emptyBody = "AI 研判完成且按约定格式输出后可查看",
    )
}

/** 对话里 AI 附带的可视化卡片：片段清洗后套固定样式。 */
@Composable
fun ChatHtmlScreen(vm: AppViewModel, task: AnalysisTaskEntity, messageId: Long, nav: Navigator) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    LaunchedEffect(task.id) { if (messages.none { it.id == messageId }) vm.refreshMessages(task.id) }
    val frag = messages.firstOrNull { it.id == messageId }?.content?.let { ChatHtml.extract(it)?.html }
    val html = remember(frag) { frag?.let { HtmlKit.page("ModRadar · ${task.targetName}", HtmlKit.sanitize(it)) } }
    HtmlViewer(
        vm, nav, frag?.let { HtmlKit.firstHeading(HtmlKit.sanitize(it)) } ?: "可视化摘要", task.targetName, html,
        exportName = "ModRadar-chat-$messageId.html",
        emptyTitle = "卡片不存在", emptyBody = "对话可能已被清空",
    )
}

@Composable
private fun HtmlViewer(
    vm: AppViewModel, nav: Navigator, title: String, subtitle: String, html: String?,
    exportName: String, emptyTitle: String, emptyBody: String,
) {
    val ctx = LocalContext.current
    val bg = MaterialTheme.colorScheme.background.toArgb()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        if (uri != null && html != null) vm.writeText(uri, html, "已导出 HTML")
    }
    Scaffold(
        topBar = {
            MrTopBar(title, subtitle = subtitle, onBack = { nav.back() }) {
                if (html != null) IconButton(onClick = { export.launch(exportName) }) {
                    Icon(Icons.Outlined.SaveAlt, "导出 HTML")
                }
            }
        },
    ) { pad ->
        if (html == null) {
            Column(Modifier.padding(pad)) { EmptyState(Icons.Outlined.Pending, emptyTitle, emptyBody) }
            return@Scaffold
        }
        AndroidView(
            factory = {
                WebView(ctx).apply {
                    setBackgroundColor(bg)
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = android.view.View.OVER_SCROLL_NEVER
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    // 模板不引用任何外部资源；屏蔽网络防止片段里漏网的外链发请求
                    settings.blockNetworkLoads = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = false
                    settings.textZoom = 100
                    settings.setSupportZoom(false)
                }
            },
            update = { if (it.tag != html) { it.tag = html; it.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) } },
            modifier = Modifier.padding(pad).fillMaxSize(),
        )
    }
}
