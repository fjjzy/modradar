package com.modradar.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.window.DialogProperties

/**
 * 首启协议弹窗：盖在首页上，未同意前不可关闭；「不同意」由调用方负责退出应用。
 */
@Composable
fun ConsentDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val ctx = LocalContext.current
    var doc by remember { mutableStateOf<String?>(null) }
    val link = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary))

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("用户协议与隐私政策") },
        text = {
            Column {
                Text(
                    buildAnnotatedString {
                        append("欢迎使用模块雷达。分析在本机完成；配置 AI 接口后会向该接口发送分析摘要；联网来源核验（默认开启，可在设置关闭）会向 GitHub 发送包名 / 模块名，不上传文件。\n\n请阅读并同意")
                        withLink(LinkAnnotation.Clickable("terms", link) { doc = LegalDocs.TERMS }) { append("《用户协议》") }
                        append("与")
                        withLink(LinkAnnotation.Clickable("privacy", link) { doc = LegalDocs.PRIVACY }) { append("《隐私政策》") }
                        append("后继续。")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("同意并继续") } },
        dismissButton = {
            TextButton(onClick = onDecline) { Text("不同意并退出", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
    )

    doc?.let { name ->
        LegalDocDialog(
            title = if (name == LegalDocs.PRIVACY) "隐私政策" else "用户协议",
            content = if (name == LegalDocs.PRIVACY) LegalDocs.privacy(ctx) else LegalDocs.terms(ctx),
            onDismiss = { doc = null },
        )
    }
}
