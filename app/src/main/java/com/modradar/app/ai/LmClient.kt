package com.modradar.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class ChatMsg(
    val role: String,
    val content: String? = null,
    val toolCalls: List<ToolCallReq>? = null,
    val toolCallId: String? = null,
)

@kotlinx.serialization.Serializable
data class ToolCallReq(val id: String, val name: String, val arguments: String)

data class ChatResponse(
    val content: String?,
    val toolCalls: List<ToolCallReq>,
    val model: String? = null,
    /** 思考过程：DeepSeek / Qwen 的 reasoning_content，OpenRouter 等的 reasoning。 */
    val reasoning: String? = null,
)

data class ConnectionReport(val model: String, val latencyMs: Long, val reply: String)

/**
 * OpenAI 兼容客户端（function calling）。
 *
 * 所有失败统一抛 [AiError]（带分类与修复建议）；429 / 5xx / 超时指数退避重试 [maxRetries] 次。
 */
class LmClient(
    settings: AiSettings,
    private val maxRetries: Int = 2,
    private val baseDelayMs: Long = 800,
    private val http: OkHttpClient = defaultHttp,
) {
    private val s = settings.cleaned()
    private val endpoint = EndpointNormalizer.normalize(s.baseUrl)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 本机回环地址不走系统代理。 */
    private val client: OkHttpClient = runCatching { java.net.URI(endpoint.baseUrl).host }.getOrNull()
        .let { h -> if (h == "127.0.0.1" || h == "localhost") http.newBuilder().proxy(java.net.Proxy.NO_PROXY).build() else http }

    suspend fun chat(messages: List<ChatMsg>, toolsJson: String?): ChatResponse {
        return parseResponse(post(endpoint.chatUrl, payload(messages, toolsJson, stream = false).toString()))
    }

    private fun payload(messages: List<ChatMsg>, toolsJson: String?, stream: Boolean) = buildJsonObject {
        put("model", s.model)
        put("temperature", s.temperature)
        if (s.thinkingLevel in setOf("low", "medium", "high")) put("reasoning_effort", s.thinkingLevel)
        if (stream) put("stream", true)
        putJsonArray("messages") { messages.forEach { add(encode(it)) } }
        if (toolsJson != null) put("tools", json.parseToJsonElement(toolsJson))
    }

    /**
     * 流式对话（SSE）。[onDelta] 收到的是累计文本（正文, 思考），由调用方节流刷新。
     * 服务端不支持 stream、直接回 JSON 时按非流式解析，行为一致。
     * 重试只发生在拿到响应头之前，开始输出后中断直接抛错（避免重复内容）。
     */
    suspend fun chatStream(
        messages: List<ChatMsg>,
        toolsJson: String?,
        onDelta: (content: String, reasoning: String) -> Unit,
    ): ChatResponse = withContext(Dispatchers.IO) {
        requireConfigured(needModel = true)
        val req = Request.Builder().url(endpoint.chatUrl)
            .header("Authorization", "Bearer ${s.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(payload(messages, toolsJson, stream = true).toString().toRequestBody("application/json".toMediaType()))
            .build()
        var attempt = 0
        while (true) {
            val resp = try {
                client.newCall(req).execute()
            } catch (t: Throwable) {
                val e = AiError.fromThrowable(t)
                if (!e.kind.retryable || attempt >= maxRetries) throw e
                delay(baseDelayMs shl attempt); attempt++; continue
            }
            if (!resp.isSuccessful) {
                val e = resp.use { AiError.fromHttp(it.code, it.body?.string().orEmpty()) }
                if (!e.kind.retryable || attempt >= maxRetries) throw e
                delay(baseDelayMs shl attempt); attempt++; continue
            }
            return@withContext resp.use { r ->
                val type = r.header("Content-Type").orEmpty()
                val source = r.body?.source() ?: throw AiError(AiError.Kind.BAD_RESPONSE, detail = "空响应")
                if (!type.contains("event-stream")) {
                    parseResponse(source.readUtf8()).also { onDelta(it.content.orEmpty(), it.reasoning.orEmpty()) }
                } else {
                    val acc = StreamAccumulator()
                    try {
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            if (acc.feed(line)) onDelta(acc.content.toString(), acc.reasoning.toString())
                            if (acc.done) break
                        }
                    } catch (t: Throwable) {
                        throw AiError.fromThrowable(t)
                    }
                    acc.result()
                }
            }
        }
        error("unreachable")
    }

    /** 拉取 /models 列表（多数 OpenAI 兼容服务支持）。 */
    suspend fun listModels(): List<String> {
        requireConfigured(needModel = false)
        val body = execute(Request.Builder().url(endpoint.baseUrl + "/models").get())
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw AiError(AiError.Kind.BAD_RESPONSE, detail = body.take(200)) }
        val data = root["data"] as? JsonArray ?: root["models"] as? JsonArray ?: JsonArray(emptyList())
        return data.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull (el as? JsonPrimitive)?.content
            ((o["id"] ?: o["name"]) as? JsonPrimitive)?.content?.removePrefix("models/")
        }.distinct().sorted()
    }

    /** 测试连接：发一条极短消息，返回实际模型名与延迟。 */
    suspend fun testConnection(): ConnectionReport {
        val t0 = System.currentTimeMillis()
        val resp = chat(listOf(ChatMsg(role = "user", content = "ping，只回复 pong")), null)
        return ConnectionReport(
            model = resp.model ?: s.model,
            latencyMs = System.currentTimeMillis() - t0,
            reply = resp.content?.trim()?.take(80) ?: "(空响应)",
        )
    }

    private suspend fun post(url: String, body: String): String {
        requireConfigured(needModel = true)
        return execute(
            Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType()))
        )
    }

    private fun requireConfigured(needModel: Boolean) {
        if (!endpoint.ok) throw AiError(AiError.Kind.NOT_CONFIGURED, detail = endpoint.error.orEmpty())
        if (s.apiKey.isBlank()) throw AiError(AiError.Kind.NOT_CONFIGURED, detail = "缺少 API Key")
        if (needModel && s.model.isBlank()) throw AiError(AiError.Kind.NOT_CONFIGURED, detail = "缺少模型名")
    }

    private suspend fun execute(builder: Request.Builder): String = withContext(Dispatchers.IO) {
        val req = builder
            .header("Authorization", "Bearer ${s.apiKey}")
            .header("Content-Type", "application/json")
            .build()
        var attempt = 0
        var body: String? = null
        while (body == null) {
            var err: AiError? = null
            try {
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) body = text else err = AiError.fromHttp(resp.code, text)
                }
            } catch (t: Throwable) {
                err = AiError.fromThrowable(t)
            }
            val e = err ?: continue
            if (!e.kind.retryable || attempt >= maxRetries) throw e
            delay(baseDelayMs shl attempt)
            attempt++
        }
        body!!
    }

    private fun encode(m: ChatMsg): JsonObject = buildJsonObject {
        put("role", m.role)
        // 带 tool_calls 的 assistant 消息 content 允许为 null；其余角色必须是字符串
        if (m.content != null) put("content", m.content)
        else if (m.toolCalls.isNullOrEmpty()) put("content", "")
        if (m.toolCallId != null) put("tool_call_id", m.toolCallId)
        m.toolCalls?.takeIf { it.isNotEmpty() }?.let { tcs ->
            putJsonArray("tool_calls") {
                tcs.forEach { tc ->
                    add(buildJsonObject {
                        put("id", tc.id)
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tc.name)
                            put("arguments", tc.arguments)
                        })
                    })
                }
            }
        }
    }

    internal fun parseResponse(body: String): ChatResponse {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw AiError(AiError.Kind.BAD_RESPONSE, detail = body.take(200)) }
        root["error"]?.let { throw AiError(AiError.Kind.BAD_REQUEST, detail = AiError.extractMessage(it.toString())) }
        val model = (root["model"] as? JsonPrimitive)?.contentOrNullSafe()
        val choices = root["choices"]?.jsonArray
            ?: throw AiError(AiError.Kind.BAD_RESPONSE, detail = "缺少 choices 字段")
        val first = choices.firstOrNull()?.jsonObject ?: return ChatResponse(null, emptyList(), model)
        val msg = first["message"]?.jsonObject ?: return ChatResponse(null, emptyList(), model)

        val content = (msg["content"] as? JsonPrimitive)?.contentOrNullSafe()
        val reasoning = ((msg["reasoning_content"] ?: msg["reasoning"]) as? JsonPrimitive)?.contentOrNullSafe()

        val calls = (msg["tool_calls"] as? JsonArray)?.mapIndexedNotNull { i, el ->
            val o = el as? JsonObject ?: return@mapIndexedNotNull null
            val fn = o["function"]?.jsonObject ?: return@mapIndexedNotNull null
            ToolCallReq(
                id = (o["id"] as? JsonPrimitive)?.contentOrNullSafe()?.takeIf { it.isNotBlank() } ?: "call_$i",
                name = (fn["name"] as? JsonPrimitive)?.content ?: "",
                arguments = (fn["arguments"] as? JsonPrimitive)?.contentOrNullSafe()?.ifBlank { "{}" } ?: "{}",
            )
        }.orEmpty()

        return ChatResponse(content, calls, model, reasoning?.ifBlank { null })
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else content

    /** SSE 增量拼装：正文、思考、按 index 合并的 tool_calls 分片。 */
    internal inner class StreamAccumulator {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        var model: String? = null
        var done = false
        private val calls = sortedMapOf<Int, Triple<StringBuilder, StringBuilder, StringBuilder>>()

        /** 处理一行 SSE；正文或思考有新增时返回 true。 */
        fun feed(line: String): Boolean {
            if (!line.startsWith("data:")) return false
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty()) return false
            if (data == "[DONE]") { done = true; return false }
            val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return false
            root["error"]?.let { throw AiError(AiError.Kind.BAD_REQUEST, detail = AiError.extractMessage(it.toString())) }
            (root["model"] as? JsonPrimitive)?.contentOrNullSafe()?.let { model = it }
            val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return false
            val delta = (choice["delta"] ?: choice["message"]) as? JsonObject ?: return false
            var changed = false
            (delta["content"] as? JsonPrimitive)?.contentOrNullSafe()?.let { if (it.isNotEmpty()) { content.append(it); changed = true } }
            ((delta["reasoning_content"] ?: delta["reasoning"]) as? JsonPrimitive)?.contentOrNullSafe()
                ?.let { if (it.isNotEmpty()) { reasoning.append(it); changed = true } }
            (delta["tool_calls"] as? JsonArray)?.forEachIndexed { i, el ->
                val o = el as? JsonObject ?: return@forEachIndexed
                val idx = (o["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: i
                val slot = calls.getOrPut(idx) { Triple(StringBuilder(), StringBuilder(), StringBuilder()) }
                (o["id"] as? JsonPrimitive)?.contentOrNullSafe()?.let { if (slot.first.isEmpty()) slot.first.append(it) }
                (o["function"] as? JsonObject)?.let { fn ->
                    (fn["name"] as? JsonPrimitive)?.contentOrNullSafe()?.let { slot.second.append(it) }
                    (fn["arguments"] as? JsonPrimitive)?.contentOrNullSafe()?.let { slot.third.append(it) }
                }
            }
            return changed
        }

        fun result(): ChatResponse = ChatResponse(
            content = content.toString().ifEmpty { null },
            toolCalls = calls.entries.mapNotNull { (i, t) ->
                val name = t.second.toString()
                if (name.isEmpty()) null
                else ToolCallReq(t.first.toString().ifBlank { "call_$i" }, name, t.third.toString().ifBlank { "{}" })
            },
            model = model,
            reasoning = reasoning.toString().ifBlank { null },
        )
    }

    companion object {
        val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}
