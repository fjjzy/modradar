package com.modradar.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.modradar.app.service.BackgroundGuard
import com.modradar.app.service.KeepAliveState

/**
 * 后台保活引导：四项检测 + 一键跳转。
 * 厂商白名单与最近任务锁定都没有公开 API，只能引导。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeepAliveScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf(BackgroundGuard.check(ctx)) }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { state = BackgroundGuard.check(ctx) }

    fun launch(intent: android.content.Intent) {
        runCatching { ctx.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        state = BackgroundGuard.check(ctx)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("后台保活") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "分析是长时间任务，建议完成以下四项，避免切后台后被系统杀掉。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            StepCard(
                index = 1,
                title = "通知权限",
                desc = "前台服务需要通知才能显示进度与暂停/继续按钮。",
                done = state.notificationsGranted,
                actionLabel = "授权",
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )

            StepCard(
                index = 2,
                title = "省电优化豁免",
                desc = "加入电池优化白名单，避免息屏后被限制后台。",
                done = state.batteryOptimizationIgnored,
                actionLabel = "去开启",
                onAction = {
                    val direct = runCatching { BackgroundGuard.requestIgnoreBatteryOptimization(ctx) }.getOrNull()
                    val ok = direct?.let {
                        runCatching { ctx.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                    } ?: false
                    if (!ok) launch(BackgroundGuard.batteryOptimizationSettings())
                },
            )

            StepCard(
                index = 3,
                title = "后台运行白名单",
                desc = if (state.vendorPages.isEmpty()) {
                    "未检测到厂商专用页面，将跳转应用详情页手动设置「允许后台运行」。"
                } else {
                    "检测到 ${state.vendorPages.first().vendor} 的专用页面，可直达设置。"
                },
                done = false,
                unknown = true,
                actionLabel = "去设置",
                onAction = { launch(BackgroundGuard.backgroundRunPage(ctx)) },
            )

            StepCard(
                index = 4,
                title = "锁定应用（最近任务）",
                desc = "打开最近任务 → 下拉或长按本应用卡片 → 点「锁定」。锁定后系统清理时不会杀掉本应用。无公开 API，只能手动操作。",
                done = false,
                unknown = true,
                actionLabel = null,
                onAction = {},
            )

            if (state.backgroundRestricted) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        "系统当前对本应用启用了后台限制（后台受限）。请在上面的第 3、4 步解除。",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            OutlinedButton(
                onClick = { state = BackgroundGuard.check(ctx) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("重新检测") }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StepCard(
    index: Int,
    title: String,
    desc: String,
    done: Boolean,
    actionLabel: String?,
    onAction: () -> Unit,
    unknown: Boolean = false,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$index. $title", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                val tag = when {
                    unknown -> "需手动"
                    done -> "已完成"
                    else -> "未完成"
                }
                Text(
                    tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (done) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null) {
                Spacer(Modifier.height(10.dp))
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
