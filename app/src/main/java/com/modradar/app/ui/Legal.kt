package com.modradar.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.edit

/** 随包发布的协议文档（assets 内，与仓库 PRIVACY.md / TERMS.md 同源）。 */
object LegalDocs {

    const val PRIVACY = "PRIVACY.md"
    const val TERMS = "TERMS.md"

    fun read(ctx: Context, name: String): String = runCatching {
        ctx.assets.open(name).bufferedReader().use { it.readText() }
    }.getOrDefault("（文档缺失：$name）")

    fun privacy(ctx: Context): String = read(ctx, PRIVACY)

    fun terms(ctx: Context): String = read(ctx, TERMS)
}

/** 首启同意状态。 */
object ConsentStore {

    private const val PREF = "consent"
    private const val KEY = "accepted"

    fun accepted(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun accept(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit { putBoolean(KEY, true) }
    }
}

/** 协议全文弹窗（首启页与设置页共用）。 */
@Composable
fun LegalDocDialog(
    title: String,
    content: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(content, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        modifier = Modifier.padding(8.dp),
    )
}
