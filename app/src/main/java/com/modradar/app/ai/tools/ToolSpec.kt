package com.modradar.app.ai.tools

import com.modradar.app.task.Stage
import kotlinx.serialization.json.JsonObject

/** 工具调用结果。 */
sealed interface ToolResult {
    data class Ok(val content: String) : ToolResult

    /**
     * 数据未就绪：当前阶段未完成。
     * 序列化形如 {"error":"data_not_ready","completed":"S2","required":"S3"}，
     * 由模型据此向用户解释「分析未跑到该阶段」，而不是把「未扫描」说成「未发现」。
     */
    data class NotReady(
        val completed: List<String>,
        val required: List<String>,
        val hint: String,
    ) : ToolResult {
        fun toJson(): String {
            val c = completed.joinToString(",")
            val r = required.joinToString(",")
            return """{"error":"data_not_ready","completed":"$c","required":"$r","hint":"$hint"}"""
        }
    }

    data class Error(val message: String) : ToolResult
}

/**
 * 工具定义。
 *
 * [requiredStages] 是门控依据：分析暂停/未跑到该阶段时，
 * 工具不会返回空结果，而是返回 NotReady，让对话如实说明数据边界。
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val requiredStages: Set<Stage> = emptySet(),
    val handler: suspend (ToolInvocation) -> ToolResult,
)

data class ToolInvocation(
    val taskId: String,
    val args: JsonObject,
)
