package com.modradar.app.mcp

import com.modradar.app.ai.AiError
import com.modradar.app.ai.tools.ToolResult
import com.modradar.app.ai.tools.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 外部 MCP 客户端（Streamable HTTP）。
 *
 * 拉取远端工具并包装成 [ToolSpec]，名称加 `ext_` 前缀并入同一注册表；
 * 只放行名称符合只读白名单的工具，避免 App 内 AI 调到写操作。
 */
class McpClient(
    private val url: String,
    private val token: String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .apply {
            // 本机回环地址不走系统代理（否则请求会被代理劫走）
            val host = runCatching { java.net.URI(url).host }.getOrNull()
            if (host == "127.0.0.1" || host == "localhost") proxy(java.net.Proxy.NO_PROXY)
        }
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val ids = AtomicInteger(1)
    private var sessionId: String? = null

    suspend fun connect(): List<ToolSpec> {
        val init = rpc("initialize", buildJsonObject {
            put("protocolVersion", McpProtocol.PROTOCOL_VERSION)
            put("capabilities", JsonObject(emptyMap()))
            put("clientInfo", buildJsonObject { put("name", "modradar"); put("version", "1") })
        })
        if (init["protocolVersion"] == null && init["serverInfo"] == null) error("对端不是 MCP 服务（initialize 无 protocolVersion）")
        runCatching { notify("notifications/initialized") }
        val res = rpc("tools/list", JsonObject(emptyMap()))
        val tools = res["tools"] as? JsonArray ?: JsonArray(emptyList())
        return tools.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if (!isReadOnly(name)) return@mapNotNull null
            ToolSpec(
                name = PREFIX + name,
                description = "[外部 MCP] " + ((o["description"] as? JsonPrimitive)?.contentOrNull ?: name),
                parameters = o["inputSchema"] as? JsonObject
                    ?: buildJsonObject { put("type", "object"); put("properties", JsonObject(emptyMap())) },
            ) { inv -> call(name, inv.args) }
        }
    }

    private suspend fun call(name: String, args: JsonObject): ToolResult = runCatching {
        val res = rpc("tools/call", buildJsonObject {
            put("name", name)
            put("arguments", args)
        })
        val text = (res["content"] as? JsonArray).orEmpty().joinToString("\n") { c ->
            ((c as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty()
        }
        val isError = (res["isError"] as? JsonPrimitive)?.contentOrNull == "true"
        if (isError) ToolResult.Error(text.ifBlank { "外部工具返回错误" }) else ToolResult.Ok(text)
    }.getOrElse { ToolResult.Error("外部 MCP 调用失败：${it.message}") }

    private suspend fun notify(method: String) {
        post(buildJsonObject { put("jsonrpc", "2.0"); put("method", method) }.toString())
    }

    private suspend fun rpc(method: String, params: JsonObject): JsonObject {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", ids.getAndIncrement())
            put("method", method)
            put("params", params)
        }.toString()
        val text = post(body)
        val obj = parseBody(text)
        obj["error"]?.let { throw IllegalStateException(AiError.extractMessage(it.toString())) }
        return obj["result"] as? JsonObject ?: JsonObject(emptyMap())
    }

    /** 兼容两种返回：纯 JSON 或 SSE（取最后一个 data: 行）。 */
    internal fun parseBody(text: String): JsonObject {
        val payload = if (text.trimStart().startsWith("{")) text
        else text.lineSequence().filter { it.startsWith("data:") }.lastOrNull()?.removePrefix("data:")?.trim()
            ?: error("无法解析 MCP 响应")
        return json.parseToJsonElement(payload).jsonObject
    }

    private suspend fun post(body: String): String = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url)
            .header("Accept", "application/json, text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
        if (token.isNotBlank()) b.header("Authorization", "Bearer $token")
        sessionId?.let { b.header("Mcp-Session-Id", it) }
        http.newCall(b.build()).execute().use { resp ->
            resp.header("Mcp-Session-Id")?.let { sessionId = it }
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(200)}")
            text
        }
    }

    private fun JsonArray?.orEmpty() = this ?: JsonArray(emptyList())

    companion object {
        const val PREFIX = "ext_"
        private val READ_ONLY = Regex("^(get|list|search|read|find|query|decompile|fetch|show|lookup|describe)[_\\-a-z0-9]*", RegexOption.IGNORE_CASE)

        fun isReadOnly(name: String): Boolean = READ_ONLY.matches(name)
    }
}
