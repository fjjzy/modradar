package com.modradar.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 首启协议同意页。
 * 未同意前不进入主界面；「不同意」由调用方负责退出应用。
 */
@Composable
fun ConsentScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val ctx = LocalContext.current
    var checked by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }

    val privacy = remember { LegalDocs.privacy(ctx) }
    val terms = remember { LegalDocs.terms(ctx) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text("模块雷达", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "APK · Xposed · KernelSU 模块分析",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(4.dp))

        DocCard(
            title = "用户协议",
            preview = terms.lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith(">") }
                .take(4).joinToString("\n"),
            onOpen = { dialog = LegalDocs.TERMS },
        )
        DocCard(
            title = "隐私政策",
            preview = privacy.lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith(">") }
                .take(4).joinToString("\n"),
            onOpen = { dialog = LegalDocs.PRIVACY },
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { checked = it })
            Text(
                "我已阅读并同意《用户协议》与《隐私政策》",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Button(
            onClick = onAccept,
            enabled = checked,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("同意并继续") }

        TextButton(onClick = onDecline) {
            Text("不同意", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(8.dp))
    }

    dialog?.let { name ->
        LegalDocDialog(
            title = if (name == LegalDocs.PRIVACY) "隐私政策" else "用户协议",
            content = if (name == LegalDocs.PRIVACY) privacy else terms,
            onDismiss = { dialog = null },
        )
    }
}

@Composable
private fun DocCard(title: String, preview: String, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpen) { Text("查看全文") }
            }
            Text(
                preview.ifBlank { "（内容缺失）" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
