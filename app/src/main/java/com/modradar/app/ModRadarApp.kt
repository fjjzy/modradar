package com.modradar.app

import android.app.Application
import com.modradar.app.ai.AiSettings
import com.modradar.app.ai.LmAutoReviewer
import com.modradar.app.ai.LmClient
import com.modradar.app.provenance.ProvenanceChecker
import com.modradar.app.provenance.ProvenanceSettings
import com.modradar.app.provenance.ProvenanceStep
import com.modradar.app.ai.tools.ToolRegistry
import com.modradar.app.ai.tools.ToolSpec
import com.modradar.app.data.db.ModRadarDb
import com.modradar.app.mcp.McpClient
import com.modradar.app.mcp.McpProtocol
import com.modradar.app.mcp.McpServer
import com.modradar.app.mcp.McpSettings
import com.modradar.app.task.ArtifactStore
import com.modradar.app.task.Pipeline
import com.modradar.app.task.TaskRepository
import com.modradar.app.task.stages.defaultStageRunners
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.io.File

class ModRadarApp : Application() {

    val db: ModRadarDb by lazy { ModRadarDb.get(this) }

    val repo: TaskRepository by lazy { TaskRepository(db) }

    /** checkpoint 与结构化结果：必须落在 filesDir，cacheDir 会被系统清理。 */
    val store: ArtifactStore by lazy { ArtifactStore(File(filesDir, "tasks")) }

    /** 大体积中间产物：可重算。 */
    val cacheRoot: File get() = File(cacheDir, "tasks").also { it.mkdirs() }

    /** 外部 MCP 透传工具（连接成功后填充）。 */
    @Volatile
    var externalTools: List<ToolSpec> = emptyList()

    /** 工具注册表：一次定义，内置 AI / MCP Server / 外部 MCP 共用。 */
    val tools: ToolRegistry by lazy { ToolRegistry(repo, store) { externalTools } }

    /** 自动研判：设置每次调用时实时读取，未配置时返回 NOT_CONFIGURED。 */
    private val autoReviewer by lazy {
        LmAutoReviewer(tools, settingsProvider = { AiSettings.load(this) })
    }

    /** 联网来源核验（GitHub）：流水线 S5 内并发执行，报告页也可单独重跑。 */
    val provenance: ProvenanceStep by lazy {
        ProvenanceStep(ProvenanceChecker(LmClient.defaultHttp)) { ProvenanceSettings.enabled(this) }
    }

    val pipeline: Pipeline by lazy {
        Pipeline(store, repo, defaultStageRunners(autoReviewer, provenance), cacheRoot)
    }

    // ---- MCP ----

    private var mcpServer: McpServer? = null
    private val _mcpStatus = MutableStateFlow("未启动")
    val mcpStatus: StateFlow<String> get() = _mcpStatus.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        val s = McpSettings.load(this)
        if (s.serverEnabled && s.token.isNotBlank()) applyMcpServer(s)
    }

    /** 按配置启停 MCP Server；返回状态文案。 */
    fun applyMcpServer(s: McpSettings): String {
        mcpServer?.stop()
        mcpServer = null
        if (!s.serverEnabled) {
            _mcpStatus.value = "未启动"
            return _mcpStatus.value
        }
        val protocol = McpProtocol(
            tools = { tools.specs },
            invoke = { taskId, name, args -> tools.invoke(taskId, name, args) },
            listTasks = {
                val all = repo.observeAll().first()
                if (all.isEmpty()) "暂无任务" else all.joinToString("\n") {
                    "${it.id}\t${it.targetName}\t${it.targetKind}\t${it.state}\t${it.score ?: "-"}"
                }
            },
            serverVersion = BuildConfig.VERSION_NAME,
        )
        _mcpStatus.value = runCatching {
            McpServer(protocol, s.port, s.token).also { it.start(); mcpServer = it }
            "运行中 · 127.0.0.1:${s.port}"
        }.getOrElse { "启动失败：${it.message}" }
        return _mcpStatus.value
    }

    /** 连接外部 MCP，并把工具并入注册表。 */
    suspend fun connectExternalMcp(url: String, token: String): Result<Int> = runCatching {
        if (url.isBlank()) {
            externalTools = emptyList()
            return@runCatching 0
        }
        val list = McpClient(url.trim(), token.trim()).connect()
        externalTools = list
        list.size
    }

    companion object {
        fun of(context: android.content.Context): ModRadarApp =
            context.applicationContext as ModRadarApp
    }
}
