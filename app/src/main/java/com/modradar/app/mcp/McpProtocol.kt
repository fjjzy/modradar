package com.modradar.app.mcp

import com.modradar.app.ai.tools.ToolResult
import com.modradar.app.ai.tools.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * MCP（JSON-RPC 2.0）协议层：与传输无关，便于单测。
 *
 * 复用 [ToolSpec]：每个工具在 inputSchema 里自动加上必填的 `task_id`，
 * 另外提供 `list_tasks` 让外部 Agent 先拿到任务 ID。
 */
class McpProtocol(
    private val tools: () -> List<ToolSpec>,
    private val invoke: suspend (taskId: String, name: String, args: JsonObject) -> ToolResult,
    private val listTasks: suspend () -> String,
    private val serverVersion: String,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 处理一条请求；通知类消息（无 id）返回 null。 */
    suspend fun handle(body: String): String? {
        val req = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
            return error(JsonNull, -32700, "Parse error")
        }
        val id = req["id"] ?: JsonNull
        val method = (req["method"] as? JsonPrimitive)?.contentOrNull
            ?: return error(id, -32600, "Invalid Request")
        val params = req["params"] as? JsonObject ?: JsonObject(emptyMap())
        val isNotification = req["id"] == null

        val result: JsonElement = when (method) {
            "initialize" -> buildJsonObject {
                put("protocolVersion", (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull ?: PROTOCOL_VERSION)
                put("capabilities", buildJsonObject { put("tools", buildJsonObject { put("listChanged", false) }) })
                put("serverInfo", buildJsonObject {
                    put("name", "modradar")
                    put("version", serverVersion)
                })
                put("instructions", "ModRadar 手机端逆向工具箱：先调 list_tasks 取 task_id，再调其他只读工具。")
            }
            "notifications/initialized", "notifications/cancelled" -> return null
            "ping" -> JsonObject(emptyMap())
            "tools/list" -> buildJsonObject { put("tools", JsonArray(listToolsJson())) }
            "tools/call" -> callTool(params)
            else -> return if (isNotification) null else error(id, -32601, "Method not found: $method")
        }
        if (isNotification) return null
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }.toString()
    }

    private fun listToolsJson(): List<JsonObject> {
        val list = mutableListOf(
            buildJsonObject {
                put("name", "list_tasks")
                put("description", "列出 ModRadar 中的分析任务（task_id、目标名、类型、状态、分数）。")
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    put("properties", JsonObject(emptyMap()))
                })
            }
        )
        tools().forEach { s ->
            val props = (s.parameters["properties"] as? JsonObject).orEmpty().toMutableMap()
            props["task_id"] = buildJsonObject {
                put("type", "string")
                put("description", "任务 ID（来自 list_tasks）")
            }
            val required = ((s.parameters["required"] as? JsonArray).orEmpty() + JsonPrimitive("task_id")).distinct()
            list += buildJsonObject {
                put("name", s.name)
                put("description", s.description)
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    put("properties", JsonObject(props))
                    put("required", JsonArray(required))
                })
            }
        }
        return list
    }

    private suspend fun callTool(params: JsonObject): JsonElement {
        val name = (params["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        if (name == "list_tasks") return content(listTasks(), false)
        val taskId = (args["task_id"] as? JsonPrimitive)?.contentOrNull
            ?: return content("缺少参数 task_id，请先调用 list_tasks", true)
        val rest = JsonObject(args - "task_id")
        return when (val r = invoke(taskId, name, rest)) {
            is ToolResult.Ok -> content(r.content, false)
            is ToolResult.NotReady -> content(r.toJson(), true)
            is ToolResult.Error -> content(r.message, true)
        }
    }

    private fun content(text: String, isError: Boolean) = buildJsonObject {
        put("content", JsonArray(listOf(buildJsonObject {
            put("type", "text")
            put("text", text)
        })))
        put("isError", isError)
    }

    private fun error(id: JsonElement, code: Int, msg: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("error", buildJsonObject {
            put("code", code)
            put("message", msg)
        })
    }.toString()

    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
    }
}
