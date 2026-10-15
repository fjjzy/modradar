package com.modradar.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import com.modradar.app.ui.ime.LocalImeController
import com.modradar.app.ui.ime.optimizedImePadding
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modradar.app.Navigator
import com.modradar.app.ui.components.Banner
import com.modradar.app.ui.components.MrCard
import com.modradar.app.ui.components.MrTopBar
import com.modradar.app.ui.components.SectionHeader
import com.modradar.app.ui.theme.MonoStyle
import com.modradar.app.ui.theme.RiskColors

/** MCP：Server（把 ModRadar 暴露给 PC 端 Agent）+ Client（接入外部 MCP 工具）。 */
@Composable
fun McpScreen(vm: AppViewModel, nav: Navigator) {
    val mcp by vm.mcp.collectAsStateWithLifecycle()
    val status by vm.mcpStatus.collectAsStateWithLifecycle()
    val clientStatus by vm.mcpClientStatus.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var url by remember { mutableStateOf(mcp.clientUrl) }
    var token by remember { mutableStateOf(mcp.clientToken) }

    val curl = """adb forward tcp:${mcp.port} tcp:${mcp.port}
curl -s http://127.0.0.1:${mcp.port}/mcp \
  -H 'Authorization: Bearer ${mcp.token}' \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'"""

    val ime = LocalImeController.current
    val scope = rememberCoroutineScope()
    Scaffold(topBar = { MrTopBar("MCP", onBack = { scope.launch { ime?.hideAndAwait(); nav.back() } }) }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).optimizedImePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionHeader("MCP Server")
            MrCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Lan, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("对 PC 端 Agent 暴露工具", style = MaterialTheme.typography.titleSmall)
                            Text(status, style = MaterialTheme.typography.bodySmall, color = if (status.startsWith("运行中")) RiskColors.Low else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = mcp.serverEnabled, onCheckedChange = { vm.setMcpServer(it) })
                    }
                    Text(
                        "只监听 127.0.0.1:${mcp.port}，需通过 adb forward 访问；每个请求必须携带 Bearer Token。工具全部只读。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (mcp.token.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Token", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
                            SelectionContainer(Modifier.weight(1f)) { Text(mcp.token, style = MonoStyle) }
                            IconButton(onClick = { clipboard.setText(AnnotatedString(mcp.token)) }) { Icon(Icons.Outlined.ContentCopy, "复制 Token") }
                            IconButton(onClick = { vm.regenerateMcpToken() }) { Icon(Icons.Outlined.Refresh, "重新生成") }
                        }
                    }
                }
            }
            if (mcp.serverEnabled) {
                MrCard(container = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("PC 端冒烟测试", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = { clipboard.setText(AnnotatedString(curl)) }) { Icon(Icons.Outlined.ContentCopy, "复制命令") }
                        }
                        SelectionContainer { Text(curl, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                Banner(Icons.Outlined.Warning, "持有 Token 的本机进程可读取分析结果；不用时请关闭", RiskColors.Medium)
            }

            SectionHeader("MCP Client（接入外部工具）")
            MrCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = url, onValueChange = { url = it },
                        label = { Text("MCP 端点（Streamable HTTP）") },
                        placeholder = { Text("http://192.168.1.2:8650/mcp") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = token, onValueChange = { token = it },
                        label = { Text("Bearer Token（可选）") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { vm.connectMcpClient(url, token) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (url.isBlank()) "断开" else "连接并拉取工具")
                    }
                    clientStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("已接入")) RiskColors.Low else MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(
                        "外部工具以 ext_ 前缀并入工具表，App 内 AI 可直接调用；只放行 get/list/search/read/decompile 等只读命名的工具。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}
