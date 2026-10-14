package com.modradar.app.ai

import com.modradar.app.ai.prompts.Prompts
import com.modradar.app.ai.tools.ToolRegistry
import com.modradar.app.ai.tools.ToolResult
import com.modradar.app.data.db.ChatMessageEntity
import com.modradar.app.data.db.ChatSessionEntity
import com.modradar.app.data.db.ModRadarDb
import com.modradar.app.task.Stage
import com.modradar.app.task.TaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID

sealed interface ChatEvent {
    data class AssistantText(val text: String) : ChatEvent
    data class ToolStarted(val name: String, val args: String) : ChatEvent
    data class ToolFinished(val name: String, val result: String, val notReady: Boolean) : ChatEvent
    data class Failed(val message: String) : ChatEvent
}

/**
 * 对话引擎。
 *
 * 关键点：**与流水线状态解耦**。分析暂停、失败或未跑完时都能进入，
 * 工具按 [ToolRegistry] 的阶段门控返回 data_not_ready，
 * system prompt 追加「部分数据模式」，保证结论不越界。
 */
class ChatEngine(
    private val repo: TaskRepository,
    private val registry: ToolRegistry,
    private val db: ModRadarDb,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun ensureSession(taskId: String, title: String = "分析会话"): ChatSessionEntity {
        db.chatDao().latestSessionForTask(taskId)?.let { return it }
        val now = System.currentTimeMillis()
        val s = ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            taskId = taskId,
            title = title,
            createdAt = now,
            updatedAt = now,
        )
        db.chatDao().upsertSession(s)
        return s
    }

    suspend fun history(sessionId: String): List<ChatMessageEntity> =
        db.chatDao().messages(sessionId)

    suspend fun send(
        taskId: String,
        sessionId: String,
        userText: String,
        settings: AiSettings,
        maxRounds: Int = 12,
        onEvent: (ChatEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.chatDao().insertMessage(
            ChatMessageEntity(sessionId = sessionId, role = "user", content = userText, createdAt = now)
        )

        if (!settings.configured) {
            onEvent(ChatEvent.Failed("尚未配置 AI：请到设置里填写 API 地址、Key 与模型"))
            return@withContext
        }

        val client = LmClient(settings)
        val messages = buildMessages(taskId, sessionId)

        var round = 0
        while (round < maxRounds) {
            round++
            val resp = try {
                client.chat(messages, registry.toolsJson())
            } catch (t: Throwable) {
                onEvent(ChatEvent.Failed(t.message ?: "AI 调用失败"))
                db.chatDao().insertMessage(
                    ChatMessageEntity(
                        sessionId = sessionId,
                        role = "assistant",
                        content = "（AI 调用失败：${t.message}）",
                        createdAt = System.currentTimeMillis(),
                    )
                )
                return@withContext
            }

            if (resp.toolCalls.isEmpty()) {
                val text = resp.content.orEmpty()
                onEvent(ChatEvent.AssistantText(text))
                db.chatDao().insertMessage(
                    ChatMessageEntity(
                        sessionId = sessionId,
                        role = "assistant",
                        content = text,
                        createdAt = System.currentTimeMillis(),
                    )
                )
                return@withContext
            }

            // 记录 assistant 的工具调用消息
            messages += ChatMsg(role = "assistant", content = resp.content, toolCalls = resp.toolCalls)
            db.chatDao().insertMessage(
                ChatMessageEntity(
                    sessionId = sessionId,
                    role = "assistant",
                    content = resp.content.orEmpty(),
                    toolCallsJson = json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(ToolCallReq.serializer()),
                        resp.toolCalls,
                    ),
                    createdAt = System.currentTimeMillis(),
                )
            )

            for (call in resp.toolCalls) {
                onEvent(ChatEvent.ToolStarted(call.name, call.arguments))
                val args = runCatching { json.parseToJsonElement(call.arguments).let { it as JsonObject } }
                    .getOrElse { JsonObject(emptyMap()) }
                val result = registry.invoke(taskId, call.name, args)
                val (text, notReady) = when (result) {
                    is ToolResult.Ok -> result.content to false
                    is ToolResult.NotReady -> result.toJson() to true
                    is ToolResult.Error -> """{"error":"${result.message.replace("\"", "'")}"}""" to false
                }
                onEvent(ChatEvent.ToolFinished(call.name, text, notReady))

                messages += ChatMsg(role = "tool", content = text, toolCallId = call.id)
                db.chatDao().insertMessage(
                    ChatMessageEntity(
                        sessionId = sessionId,
                        role = "tool",
                        content = text,
                        toolCallsJson = call.name,
                        createdAt = System.currentTimeMillis(),
                    )
                )
            }
        }

        onEvent(ChatEvent.Failed("已达到工具调用轮次上限（$maxRounds 轮）"))
    }

    /** 组装消息：P0 + （未跑完时）P8 部分数据 + 历史。 */
    private suspend fun buildMessages(taskId: String, sessionId: String): MutableList<ChatMsg> {
        val completed = repo.completedStages(taskId)
        val task = repo.task(taskId)
        val pending = Stage.order.filter { it !in completed }
        val pausedAt = pending.firstOrNull()
        val finished = pending.isEmpty()

        val system = buildString {
            append(Prompts.P0_SYSTEM)
            if (!finished) {
                append("\n\n")
                append(Prompts.p8PartialData(completed.toList(), pending, pausedAt))
            }
            append("\n\n【当前目标】")
            append(task?.targetName ?: "未知")
            append("（").append(task?.targetKind ?: "?").append("）")
        }

        val out = mutableListOf(ChatMsg(role = "system", content = system))
        db.chatDao().messages(sessionId).forEach { m ->
            when (m.role) {
                "user" -> out += ChatMsg(role = "user", content = m.content)
                "assistant" -> out += ChatMsg(role = "assistant", content = m.content.ifBlank { null })
                "tool" -> out += ChatMsg(role = "tool", content = m.content)
            }
        }
        return out
    }
}
