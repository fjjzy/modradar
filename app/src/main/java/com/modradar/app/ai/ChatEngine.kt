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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID

sealed interface ChatEvent {
    data class AssistantText(val text: String) : ChatEvent
    /** 流式输出中：累计正文与思考（已节流）。 */
    data class Streaming(val content: String, val reasoning: String) : ChatEvent
    /** 有消息落库（用户消息、带 tool_calls 的一轮），界面据此刷新历史。 */
    data object RoundDone : ChatEvent
    data class ToolStarted(val id: String, val name: String, val args: String) : ChatEvent
    data class ToolFinished(val id: String, val name: String, val result: String, val notReady: Boolean, val ms: Long) : ChatEvent
    data class Failed(val message: String) : ChatEvent
}

/** tool 消息的元数据（存于 toolCallsJson）。 */
@Serializable
data class ToolMsgMeta(val id: String, val name: String, val ms: Long = 0, val notReady: Boolean = false)

/**
 * 对话引擎。
 *
 * 关键点：**与流水线状态解耦**。分析暂停、失败或未跑完时都能进入，
 * 工具按 [ToolRegistry] 的阶段门控返回 data_not_ready，
 * system prompt 追加「部分数据模式」，保证结论不越界。
 *
 * 历史回放必须保留 assistant.tool_calls 与 tool.tool_call_id 的配对，
 * 否则 OpenAI 兼容接口会在第二轮直接返回 400。
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

    suspend fun clear(sessionId: String) = db.chatDao().deleteMessages(sessionId)

    suspend fun send(
        taskId: String,
        sessionId: String,
        userText: String,
        settings: AiSettings,
        maxRounds: Int = 12,
        onEvent: (ChatEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        insert(sessionId, "user", userText)
        onEvent(ChatEvent.RoundDone)

        if (!settings.configured) {
            val msg = AiError(AiError.Kind.NOT_CONFIGURED).userMessage
            insert(sessionId, "assistant", "（$msg）")
            onEvent(ChatEvent.Failed(msg))
            return@withContext
        }

        val client = LmClient(settings)
        val messages = buildMessages(taskId, sessionId)

        var round = 0
        while (round < maxRounds) {
            round++
            var last = 0L
            val resp = try {
                client.chatStream(messages, registry.toolsJson()) { c, r ->
                    val now = System.currentTimeMillis()
                    if (now - last >= STREAM_THROTTLE_MS) { last = now; onEvent(ChatEvent.Streaming(c, r)) }
                }
            } catch (t: Throwable) {
                val msg = AiError.fromThrowable(t).userMessage
                onEvent(ChatEvent.Failed(msg))
                insert(sessionId, "assistant", "（AI 调用失败）\n$msg")
                return@withContext
            }

            if (resp.toolCalls.isEmpty()) {
                val text = resp.content.orEmpty().ifBlank { "（模型返回了空内容）" }
                insert(sessionId, "assistant", text, reasoning = resp.reasoning)
                onEvent(ChatEvent.AssistantText(text))
                return@withContext
            }

            messages += ChatMsg(role = "assistant", content = resp.content, toolCalls = resp.toolCalls)
            insert(
                sessionId, "assistant", resp.content.orEmpty(),
                json.encodeToString(ListSerializer(ToolCallReq.serializer()), resp.toolCalls),
                reasoning = resp.reasoning,
            )
            onEvent(ChatEvent.RoundDone)

            for (call in resp.toolCalls) {
                onEvent(ChatEvent.ToolStarted(call.id, call.name, call.arguments))
                val args = runCatching { json.parseToJsonElement(call.arguments) as JsonObject }
                    .getOrElse { JsonObject(emptyMap()) }
                val t0 = System.currentTimeMillis()
                // 单工具 20s 超时，返回截断，避免撑爆上下文
                val result = withTimeoutOrNull(TOOL_TIMEOUT_MS) { registry.invoke(taskId, call.name, args) }
                    ?: ToolResult.Error("工具执行超时（${TOOL_TIMEOUT_MS / 1000}s）")
                val ms = System.currentTimeMillis() - t0
                val (text, notReady) = when (result) {
                    is ToolResult.Ok -> result.content.take(MAX_TOOL_CHARS) to false
                    is ToolResult.NotReady -> result.toJson() to true
                    is ToolResult.Error -> """{"error":"${result.message.replace("\"", "'")}"}""" to false
                }
                onEvent(ChatEvent.ToolFinished(call.id, call.name, text, notReady, ms))

                messages += ChatMsg(role = "tool", content = text, toolCallId = call.id)
                insert(sessionId, "tool", text, json.encodeToString(ToolMsgMeta.serializer(), ToolMsgMeta(call.id, call.name, ms, notReady)))
                // 落库后再通知刷新，否则界面可能先读到不含这条结果的历史
                onEvent(ChatEvent.RoundDone)
            }
        }

        val msg = "已达到工具调用轮次上限（$maxRounds 轮），可以继续追问让 AI 接着分析"
        insert(sessionId, "assistant", "（$msg）")
        onEvent(ChatEvent.Failed(msg))
    }

    private suspend fun insert(sessionId: String, role: String, content: String, meta: String? = null, reasoning: String? = null) {
        db.chatDao().insertMessage(
            ChatMessageEntity(
                sessionId = sessionId,
                role = role,
                content = content,
                toolCallsJson = meta,
                createdAt = System.currentTimeMillis(),
                reasoning = reasoning?.ifBlank { null },
            )
        )
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
            append("\n\n")
            append(Prompts.P0_CHAT_HTML)
            if (!finished) {
                append("\n\n")
                append(Prompts.p8PartialData(completed.toList(), pending, pausedAt))
            }
            append("\n\n【当前目标】")
            append(task?.targetName ?: "未知")
            append("（").append(task?.targetKind ?: "?").append("）")
        }

        val out = mutableListOf(ChatMsg(role = "system", content = system))
        out += replay(db.chatDao().messages(sessionId))
        return out
    }

    companion object {
        const val TOOL_TIMEOUT_MS = 20_000L
        const val MAX_TOOL_CHARS = 6_000
        const val STREAM_THROTTLE_MS = 80L

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** 历史 → 请求消息；丢弃无法配对的 tool_calls / tool 消息（兼容旧版本存储）。 */
        fun replay(history: List<ChatMessageEntity>): List<ChatMsg> {
            val raw = history.map { m ->
                when (m.role) {
                    "assistant" -> {
                        val calls = m.toolCallsJson?.let {
                            runCatching { json.decodeFromString(ListSerializer(ToolCallReq.serializer()), it) }.getOrNull()
                        }
                        ChatMsg("assistant", m.content.ifBlank { null }, toolCalls = calls?.takeIf { it.isNotEmpty() })
                    }
                    "tool" -> {
                        val meta = m.toolCallsJson?.let {
                            runCatching { json.decodeFromString(ToolMsgMeta.serializer(), it) }.getOrNull()
                        }
                        ChatMsg("tool", m.content, toolCallId = meta?.id)
                    }
                    else -> ChatMsg(m.role, m.content)
                }
            }
            val answered = raw.filter { it.role == "tool" }.mapNotNull { it.toolCallId }.toSet()
            val out = mutableListOf<ChatMsg>()
            var openIds = emptySet<String>()
            for (m in raw) {
                when {
                    m.role == "assistant" && m.toolCalls != null -> {
                        val ids = m.toolCalls.map { it.id }.toSet()
                        if (answered.containsAll(ids)) {
                            out += m; openIds = ids
                        } else {
                            openIds = emptySet()
                            if (!m.content.isNullOrBlank()) out += m.copy(toolCalls = null)
                        }
                    }
                    m.role == "tool" -> if (m.toolCallId != null && m.toolCallId in openIds) out += m
                    m.role == "assistant" && m.content == null -> Unit
                    else -> { out += m; openIds = emptySet() }
                }
            }
            return out
        }
    }
}
