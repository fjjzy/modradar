package com.modradar.app.backup

import com.modradar.app.ai.AiSettings
import com.modradar.app.data.db.AnalysisTaskEntity
import com.modradar.app.data.db.ChatMessageEntity
import com.modradar.app.data.db.ChatSessionEntity
import com.modradar.app.data.db.ModRadarDb
import com.modradar.app.data.db.StageCheckpointEntity
import com.modradar.app.mcp.McpSettings
import com.modradar.app.task.ArtifactStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 配置备份文件格式（JSON）。
 *
 * 包含：AI 设置、MCP 设置、任务历史（元数据 + 各阶段 JSON 产物，含报告）与 AI 对话。
 * 不含被分析的 APK 本体——体积大且可能涉及版权；导入后报告与对话可直接查看，重新分析需重新选择文件。
 * 密钥（API Key / MCP Token）默认不导出，需用户显式勾选。
 */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = 1,
    val exportedAt: Long = 0,
    val appVersion: String = "",
    val ai: AiBackup? = null,
    val mcp: McpBackup? = null,
    val tasks: List<TaskBackup> = emptyList(),
) {
    companion object { const val FORMAT = "modradar-backup" }
}

@Serializable
data class AiBackup(
    val protocol: String = "custom",
    val baseUrl: String = "",
    val apiKey: String? = null,
    val model: String = "",
    val temperature: Double = 0.2,
    val thinkingLevel: String = "auto",
)

@Serializable
data class McpBackup(
    val serverEnabled: Boolean = false,
    val port: Int = 8787,
    val token: String? = null,
    val clientUrl: String = "",
    val clientToken: String? = null,
)

@Serializable
data class TaskBackup(
    val id: String,
    val targetPath: String,
    val targetName: String,
    val targetKind: String,
    val state: String,
    val currentStage: String,
    val progress: Int,
    val score: Int? = null,
    val riskLevel: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val lastError: String? = null,
    val checkpoints: List<CheckpointBackup> = emptyList(),
    /** 文件名 → 内容（各阶段 JSON 产物）。 */
    val artifacts: Map<String, String> = emptyMap(),
    val sessions: List<SessionBackup> = emptyList(),
)

@Serializable
data class CheckpointBackup(
    val stage: String,
    val state: String,
    val artifactFile: String? = null,
    val sha256: String? = null,
    val completedAt: Long? = null,
    val detail: String? = null,
)

@Serializable
data class SessionBackup(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<MessageBackup> = emptyList(),
)

@Serializable
data class MessageBackup(
    val role: String,
    val content: String,
    val toolCallsJson: String? = null,
    val createdAt: Long,
    val reasoning: String? = null,
)

data class ImportSummary(val ai: Boolean, val mcp: Boolean, val tasks: Int, val messages: Int)

class BackupManager(private val db: ModRadarDb, private val store: ArtifactStore) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    suspend fun export(
        ai: AiSettings,
        mcp: McpSettings,
        includeSecrets: Boolean,
        includeTasks: Boolean,
        appVersion: String,
        now: Long,
    ): String {
        val tasks = if (!includeTasks) emptyList() else db.taskDao().all().map { t ->
            TaskBackup(
                id = t.id, targetPath = t.targetPath, targetName = t.targetName, targetKind = t.targetKind,
                state = t.state, currentStage = t.currentStage, progress = t.progress, score = t.score,
                riskLevel = t.riskLevel, createdAt = t.createdAt, updatedAt = t.updatedAt, lastError = t.lastError,
                checkpoints = db.checkpointDao().byTask(t.id).map {
                    CheckpointBackup(it.stage, it.state, it.artifactFile, it.sha256, it.completedAt, it.detail)
                },
                artifacts = store.taskDir(t.id).listFiles().orEmpty()
                    .filter { it.isFile && it.name.endsWith(".json") && it.length() <= MAX_ARTIFACT_BYTES }
                    .associate { it.name to it.readText() },
                sessions = db.chatDao().sessionsForTask(t.id).map { s ->
                    SessionBackup(
                        s.id, s.title, s.createdAt, s.updatedAt,
                        db.chatDao().messages(s.id).map { MessageBackup(it.role, it.content, it.toolCallsJson, it.createdAt, it.reasoning) },
                    )
                },
            )
        }
        val file = BackupFile(
            exportedAt = now,
            appVersion = appVersion,
            ai = AiBackup(ai.protocol, ai.baseUrl, ai.apiKey.takeIf { includeSecrets }, ai.model, ai.temperature, ai.thinkingLevel),
            mcp = McpBackup(
                mcp.serverEnabled, mcp.port, mcp.token.takeIf { includeSecrets },
                mcp.clientUrl, mcp.clientToken.takeIf { includeSecrets },
            ),
            tasks = tasks,
        )
        return json.encodeToString(BackupFile.serializer(), file)
    }

    /**
     * 合并导入：AI / MCP 设置覆盖（备份里没有密钥时保留本机现有密钥）；
     * 任务按 id 覆盖，正在运行的状态改为暂停，避免导入后假装在跑。
     */
    suspend fun import(
        f: BackupFile,
        currentAi: AiSettings,
        currentMcp: McpSettings,
        saveAi: (AiSettings) -> Unit,
        saveMcp: (McpSettings) -> Unit,
    ): ImportSummary {
        f.ai?.let { a ->
            saveAi(
                AiSettings(
                    protocol = a.protocol, baseUrl = a.baseUrl, apiKey = a.apiKey ?: currentAi.apiKey,
                    model = a.model, temperature = a.temperature, thinkingLevel = a.thinkingLevel,
                ).cleaned()
            )
        }
        f.mcp?.let { m ->
            saveMcp(
                McpSettings(
                    serverEnabled = m.serverEnabled, port = m.port, token = m.token ?: currentMcp.token,
                    clientUrl = m.clientUrl, clientToken = m.clientToken ?: currentMcp.clientToken,
                )
            )
        }
        var msgCount = 0
        var taskCount = 0
        for (t in f.tasks) {
            if (!SAFE_ID.matches(t.id)) continue
            taskCount++
            db.taskDao().upsert(
                AnalysisTaskEntity(
                    id = t.id, targetPath = t.targetPath, targetName = t.targetName, targetKind = t.targetKind,
                    state = if (t.state == "RUNNING") "PAUSED" else t.state,
                    currentStage = t.currentStage, progress = t.progress, score = t.score, riskLevel = t.riskLevel,
                    createdAt = t.createdAt, updatedAt = t.updatedAt, lastError = t.lastError,
                )
            )
            t.checkpoints.forEach {
                db.checkpointDao().upsert(
                    StageCheckpointEntity(t.id, it.stage, if (it.state == "RUNNING") "PENDING" else it.state, it.artifactFile, it.sha256, it.completedAt, it.detail)
                )
            }
            // 文件名只允许简单名字，防止 ../ 穿越
            t.artifacts.filterKeys { SAFE_NAME.matches(it) }.forEach { (name, text) -> store.writeAtomic(t.id, name, text) }
            for (s in t.sessions) {
                db.chatDao().deleteMessages(s.id)
                db.chatDao().upsertSession(ChatSessionEntity(s.id, t.id, s.title, s.createdAt, s.updatedAt))
                s.messages.forEach {
                    db.chatDao().insertMessage(ChatMessageEntity(sessionId = s.id, role = it.role, content = it.content, toolCallsJson = it.toolCallsJson, createdAt = it.createdAt, reasoning = it.reasoning))
                    msgCount++
                }
            }
        }
        return ImportSummary(ai = f.ai != null, mcp = f.mcp != null, tasks = taskCount, messages = msgCount)
    }

    companion object {
        private val reader = Json { ignoreUnknownKeys = true }

        fun parse(text: String): BackupFile {
            val f = runCatching { reader.decodeFromString(BackupFile.serializer(), text) }
                .getOrElse { throw IllegalArgumentException("不是有效的 ModRadar 备份文件：${it.message?.take(80)}") }
            require(f.format == BackupFile.FORMAT) { "文件格式不符（format=${f.format}）" }
            require(f.version <= 1) { "备份来自更新版本的 ModRadar（v${f.version}），请先升级" }
            return f
        }

        private const val MAX_ARTIFACT_BYTES = 8L * 1024 * 1024
        private val SAFE_NAME = Regex("^[\\w.-]{1,64}\\.json$")
        private val SAFE_ID = Regex("^[\\w-]{1,64}$")
    }
}
