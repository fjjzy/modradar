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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modradar.app.ai.AiSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val saved by vm.aiSettings.collectAsStateWithLifecycle()
    val testResult by vm.testResult.collectAsStateWithLifecycle()

    var baseUrl by remember(saved) { mutableStateOf(saved.baseUrl) }
    var apiKey by remember(saved) { mutableStateOf(saved.apiKey) }
    var model by remember(saved) { mutableStateOf(saved.model) }
    var temperature by remember(saved) { mutableStateOf(saved.temperature.toString()) }
    var thinking by remember(saved) { mutableStateOf(saved.thinkingLevel) }
    var doc by remember { mutableStateOf<String?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 设置") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("模型配置", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("API 地址（OpenAI 兼容，到 /v1 为止）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("模型") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = temperature,
                            onValueChange = { temperature = it },
                            label = { Text("温度") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = thinking,
                            onValueChange = { thinking = it },
                            label = { Text("思考等级") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        vm.saveAiSettings(
                            AiSettings(
                                baseUrl = baseUrl.trim(),
                                apiKey = apiKey.trim(),
                                model = model.trim(),
                                temperature = temperature.toDoubleOrNull() ?: 0.2,
                                thinkingLevel = thinking.trim(),
                            )
                        )
                    }
                ) { Text("保存") }
                OutlinedButton(onClick = { vm.testConnection() }) { Text("测试连接") }
            }

            testResult?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(6.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("数据边界", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "· 仅上传特征摘要与反编译片段，不上传 APK 本体\n· API Key 仅保存在本机\n· 不配置 AI 时本地分析照常可用，报告标注「未完成 AI 研判」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("法律与协议", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { doc = LegalDocs.TERMS }) { Text("用户协议") }
                        OutlinedButton(onClick = { doc = LegalDocs.PRIVACY }) { Text("隐私政策") }
                    }
                    Text(
                        "GPL-3.0 开源；仅用于分析你有权处理的应用与模块。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    doc?.let { name ->
        LegalDocDialog(
            title = if (name == LegalDocs.PRIVACY) "隐私政策" else "用户协议",
            content = LegalDocs.read(ctx, name),
            onDismiss = { doc = null },
        )
    }
}
