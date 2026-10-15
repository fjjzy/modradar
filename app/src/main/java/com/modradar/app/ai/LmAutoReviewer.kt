package com.modradar.app.ai

import com.modradar.app.ai.prompts.Prompts
import com.modradar.app.ai.tools.ToolRegistry
import com.modradar.app.ai.tools.ToolResult
import com.modradar.app.task.AiReviewRequest
import com.modradar.app.task.AiReviewResult
import com.modradar.app.task.AiReviewer
import com.modradar.app.task.Stage
import com.modradar.app.task.stages.AiVerdictParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 无界面的自动研判：复用与工作台相同的工具循环，
 * 但不落会话记录（避免污染用户的对话）。
 */
class LmAutoReviewer(
    private val registry: ToolRegistry,
    private val settingsProvider: () -> AiSettings,
    private val maxRounds: Int = 8,
) : AiReviewer {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun review(request: AiReviewRequest): AiReviewResult = withContext(Dispatchers.IO) {
        val settings = settingsProvider()
        if (!settings.configured) {
            return@withContext AiReviewResult(status = AiReviewResult.NOT_CONFIGURED)
        }

        val client = LmClient(settings)
        val messages = mutableListOf(ChatMsg(role = "system", content = systemPrompt(request)))
        messages += ChatMsg(role = "user", content = Prompts.P7_REVIEW)

        val toolLog = mutableListOf<String>()

        try {
            repeat(maxRounds) {
                val resp = client.chat(messages, registry.toolsJson())

                if (resp.toolCalls.isEmpty()) {
                    var summary = resp.content
                    // 不是合法 JSON 时纠正一次（不带工具，避免再进取证循环）
                    if (AiVerdictParser.parse(summary) == null) {
                        messages += ChatMsg(role = "assistant", content = summary)
                        messages += ChatMsg(role = "user", content = Prompts.P7_FIX)
                        val fixed = runCatching { client.chat(messages, null).content }.getOrNull()
                        if (AiVerdictParser.parse(fixed) != null) summary = fixed.orEmpty()
                    }
                    return@withContext AiReviewResult(
                        status = AiReviewResult.DONE,
                        summary = summary,
                        toolCalls = toolLog,
                        model = settings.model,
                    )
                }

                messages += ChatMsg(
                    role = "assistant",
                    content = resp.content,
                    toolCalls = resp.toolCalls,
                )

                for (call in resp.toolCalls) {
                    toolLog += call.name
                    val args = runCatching {
                        json.parseToJsonElement(call.arguments) as JsonObject
                    }.getOrElse { JsonObject(emptyMap<String, JsonElement>()) }

                    val text = when (val r = registry.invoke(request.taskId, call.name, args)) {
                        is ToolResult.Ok -> r.content
                        is ToolResult.NotReady -> r.toJson()
                        is ToolResult.Error -> """{"error":"${r.message.replace("\"", "'")}"}"""
                    }
                    messages += ChatMsg(role = "tool", content = text, toolCallId = call.id)
                }
            }
            AiReviewResult(
                status = AiReviewResult.FAILED,
                error = "工具调用轮次超限（$maxRounds 轮）",
                toolCalls = toolLog,
                model = settings.model,
            )
        } catch (t: Throwable) {
            AiReviewResult(
                status = AiReviewResult.FAILED,
                error = AiError.fromThrowable(t).userMessage,
                toolCalls = toolLog,
                model = settings.model,
            )
        }
    }

    private fun systemPrompt(request: AiReviewRequest): String = buildString {
        append(Prompts.P0_SYSTEM)
        append("\n\n")
        if (request.pending.isNotEmpty()) {
            append(Prompts.p8PartialData(request.completed.toList(), request.pending, request.pending.first()))
        } else {
            append("【完整数据模式】S1–S4 均已完成，可基于本地规则与证据链结论作答。")
        }
        append("\n\n【当前目标】")
        append(request.targetName).append("（").append(request.targetKind).append("）")
        append("\n\n输出要求：简体中文；引用工具返回的证据；不得把未扫描的部分表述为未发现。")
    }
}
