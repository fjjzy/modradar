package com.modradar.app.ai

import android.content.Context
import androidx.core.content.edit

/** 厂商预设：均走 OpenAI 兼容协议（/chat/completions + tools）。 */
data class AiPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val models: List<String>,
)

object AiPresets {
    val all = listOf(
        AiPreset("deepseek", "DeepSeek", "https://api.deepseek.com/v1", listOf("deepseek-chat", "deepseek-reasoner")),
        AiPreset("openai", "OpenAI", "https://api.openai.com/v1", listOf("gpt-4o-mini", "gpt-4.1", "gpt-4o")),
        AiPreset("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1", listOf("deepseek-ai/DeepSeek-V3", "Qwen/Qwen2.5-72B-Instruct")),
        AiPreset("gemini", "Gemini（OpenAI 兼容）", "https://generativelanguage.googleapis.com/v1beta/openai", listOf("gemini-2.5-flash", "gemini-2.5-pro")),
        AiPreset("anthropic", "Claude（OpenAI 兼容）", "https://api.anthropic.com/v1", listOf("claude-sonnet-4-5", "claude-haiku-4-5")),
        AiPreset("ollama", "Ollama（局域网）", "http://192.168.1.2:11434/v1", listOf("qwen2.5:7b")),
        AiPreset("custom", "自定义", "", emptyList()),
    )

    fun byId(id: String): AiPreset = all.firstOrNull { it.id == id } ?: all.last()

    /** 根据地址反推预设，便于老配置回显。 */
    fun guess(baseUrl: String): AiPreset =
        all.firstOrNull { it.baseUrl.isNotEmpty() && baseUrl.startsWith(it.baseUrl.substringBefore("/v")) } ?: all.last()
}

/** 思考等级：auto 不发送任何额外字段，最大化兼容。 */
object ThinkingLevels {
    val all = listOf("auto" to "自动", "low" to "低", "medium" to "中", "high" to "高")
    fun label(v: String) = all.firstOrNull { it.first == v }?.second ?: "自动"
}

/** AI 配置：仅存本机。 */
data class AiSettings(
    val protocol: String = "custom",
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Double = 0.2,
    val thinkingLevel: String = "auto",
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    /** 清洗后的副本（保存与请求前统一调用）。 */
    fun cleaned(): AiSettings {
        val ep = EndpointNormalizer.normalize(baseUrl)
        return copy(
            baseUrl = if (ep.ok) ep.baseUrl else baseUrl.trim(),
            apiKey = EndpointNormalizer.cleanKey(apiKey),
            model = EndpointNormalizer.cleanModel(model),
            temperature = temperature.coerceIn(0.0, 2.0),
        )
    }

    companion object {
        private const val PREF = "ai_settings"

        fun load(ctx: Context): AiSettings {
            val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val base = sp.getString("baseUrl", "") ?: ""
            return AiSettings(
                protocol = sp.getString("protocol", null)?.takeIf { p -> AiPresets.all.any { it.id == p } }
                    ?: AiPresets.guess(base).id,
                baseUrl = base,
                apiKey = sp.getString("apiKey", "") ?: "",
                model = sp.getString("model", "") ?: "",
                temperature = (sp.getString("temperature", "0.2") ?: "0.2").toDoubleOrNull() ?: 0.2,
                thinkingLevel = sp.getString("thinkingLevel", "auto") ?: "auto",
            )
        }

        fun save(ctx: Context, s: AiSettings) {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit {
                putString("protocol", s.protocol)
                putString("baseUrl", s.baseUrl)
                putString("apiKey", s.apiKey)
                putString("model", s.model)
                putString("temperature", s.temperature.toString())
                putString("thinkingLevel", s.thinkingLevel)
            }
        }

        fun clear(ctx: Context) {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit { clear() }
        }
    }
}
