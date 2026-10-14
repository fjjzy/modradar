package com.modradar.app.ai

import android.content.Context
import androidx.core.content.edit

/** AI 配置：仅存本机。 */
data class AiSettings(
    val protocol: String = "openai",
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Double = 0.2,
    val thinkingLevel: String = "auto",
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    companion object {
        private const val PREF = "ai_settings"

        fun load(ctx: Context): AiSettings {
            val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            return AiSettings(
                protocol = sp.getString("protocol", "openai") ?: "openai",
                baseUrl = sp.getString("baseUrl", "") ?: "",
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
    }
}
