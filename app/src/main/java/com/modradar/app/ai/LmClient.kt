package com.modradar.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
)

/** OpenAI 兼容客户端（function calling）。 */
class LmClient(private val settings: AiSettings) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun chat(messages: List<ChatMsg>, toolsJson: String?): ChatResponse =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("model", settings.model)
                put("temperature", settings.temperature)
                putJsonArray("messages") {
                    messages.forEach { m ->
                        add(
                            buildJsonObject {
                                put("role", m.role)
                                if (m.content != null) put("content", m.content)
                                if (m.toolCallId != null) put("tool_call_id", m.toolCallId)
                                m.toolCalls?.let { tcs ->
                                    putJsonArray("tool_calls") {
                                        tcs.forEach { tc ->
                                            add(
                                                buildJsonObject {
                                                    put("id", tc.id)
                                                    put("type", "function")
                                                    put(
                                                        "function",
                                                        buildJsonObject {
                                                            put("name", tc.name)
                                                            put("arguments", tc.arguments)
                                                        },
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
                if (toolsJson != null) {
                    put("tools", json.parseToJsonElement(toolsJson))
                }
            }

            val url = settings.baseUrl.trimEnd('/') + "/chat/completions"
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${settings.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("AI 调用失败 HTTP ${resp.code}: ${body.take(300)}")
                }
                parseResponse(body)
            }
        }

    private fun parseResponse(body: String): ChatResponse {
        val root = json.parseToJsonElement(body).jsonObject
        val choices = root["choices"]?.jsonArray ?: JsonArray(emptyList())
        val first = choices.firstOrNull()?.jsonObject ?: return ChatResponse(null, emptyList())
        val msg = first["message"]?.jsonObject ?: return ChatResponse(null, emptyList())

        val content = (msg["content"] as? JsonPrimitive)?.contentOrNullSafe()

        val calls = msg["tool_calls"]?.jsonArray?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val fn = o["function"]?.jsonObject ?: return@mapNotNull null
            ToolCallReq(
                id = (o["id"] as? JsonPrimitive)?.content ?: "",
                name = (fn["name"] as? JsonPrimitive)?.content ?: "",
                arguments = (fn["arguments"] as? JsonPrimitive)?.content ?: "{}",
            )
        }.orEmpty()

        return ChatResponse(content, calls)
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else content

    /** 测试连接：发一条极短消息。 */
    suspend fun testConnection(): String {
        val resp = chat(listOf(ChatMsg(role = "user", content = "ping，只回复 pong")), null)
        return resp.content?.take(80) ?: "(空响应)"
    }
}
