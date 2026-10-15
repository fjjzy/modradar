package com.modradar.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.modradar.app.BuildConfig
import com.modradar.app.Navigator
import com.modradar.app.service.AnalysisService
import com.modradar.app.ui.components.ListRow
import com.modradar.app.ui.components.MrCard
import com.modradar.app.ui.components.MrTopBar
import com.modradar.app.ui.components.VSpace

const val REPO_URL = "https://github.com/fjjzy/modradar"

@Composable
fun AboutScreen(nav: Navigator) {
    val ctx = LocalContext.current
    var doc by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { MrTopBar("关于", onBack = { nav.back() }) }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VSpace(16.dp)
            AppIcon(ctx.packageName, size = 72)
            Text("模块雷达", style = MaterialTheme.typography.headlineSmall)
            Text("版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            VSpace(8.dp)
            MrCard {
                Column {
                    ListRow(Icons.Outlined.Gavel, "开源许可", "GPL-3.0") { doc = "LICENSE" }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ListRow(Icons.Outlined.PrivacyTip, "隐私政策") { doc = LegalDocs.PRIVACY }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ListRow(Icons.Outlined.Description, "用户协议") { doc = LegalDocs.TERMS }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ListRow(Icons.Outlined.Download, "导出诊断日志", "本次运行的分析日志与设备信息，不含 API Key") {
                        val text = buildString {
                            append("ModRadar ${BuildConfig.VERSION_NAME}\n")
                            append("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n\n")
                            AnalysisService.logs.value.forEach { (id, lines) ->
                                append("[task ${id.take(8)}]\n"); lines.forEach { append(it).append('\n') }
                            }
                        }
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                        ctx.startActivity(Intent.createChooser(send, "导出诊断日志"))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ListRow(Icons.Outlined.Code, "GitHub 仓库", REPO_URL.removePrefix("https://")) {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))) }
                    }
                }
            }
            Text(
                "本工具仅用于分析你有权处理的应用与模块。\nAI 结论仅供参考，不构成安全背书。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            )
        }
    }

    doc?.let { name ->
        LegalDocDialog(
            title = when (name) { LegalDocs.PRIVACY -> "隐私政策"; LegalDocs.TERMS -> "用户协议"; else -> "GPL-3.0" },
            content = LegalDocs.read(ctx, name),
            onDismiss = { doc = null },
        )
    }
}
