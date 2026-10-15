package com.modradar.app.ai

import java.net.URI

/**
 * 端点与凭据规范化：把用户随手粘贴的地址 / Key 修成可直接请求的形态。
 *
 * - 去空白、引号与尾斜杠
 * - 剥掉误粘的 `/chat/completions`、`/completions`、`/models`
 * - 缺 scheme 补 `https://`；只有域名没有路径时补 `/v1`
 * - Key 去掉 `Bearer ` 前缀与换行
 */
object EndpointNormalizer {

    data class Result(
        val baseUrl: String,
        /** 实际请求的 chat/completions 地址。 */
        val chatUrl: String,
        val warnings: List<String>,
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null
    }

    private val TAIL_SUFFIXES = listOf("/chat/completions", "/completions", "/models", "/chat")

    fun normalize(raw: String): Result {
        var s = raw.trim().trim('"', '\'', '`').replace(Regex("\\s+"), "")
        if (s.isEmpty()) return Result("", "", emptyList(), "请填写 API 地址")
        val warnings = mutableListOf<String>()

        if (!s.contains("://")) {
            s = "https://$s"
            warnings += "已自动补全 https://"
        }
        s = s.trimEnd('/')
        var stripped = true
        while (stripped) {
            stripped = false
            for (suf in TAIL_SUFFIXES) {
                if (s.endsWith(suf, ignoreCase = true)) {
                    s = s.dropLast(suf.length).trimEnd('/')
                    warnings += "已去掉多余的 $suf"
                    stripped = true
                }
            }
        }

        val uri = runCatching { URI(s) }.getOrNull()
            ?: return Result(s, "", warnings, "地址格式不正确")
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Result(s, "", warnings, "只支持 http / https")
        if (uri.host.isNullOrBlank()) return Result(s, "", warnings, "缺少域名")

        if (uri.path.isNullOrEmpty() || uri.path == "/") {
            s = "$s/v1"
            warnings += "已自动补全 /v1"
        }
        if (scheme == "http" && !isLocalHost(uri.host)) {
            warnings += "使用 http 明文传输，Key 可能被窃听"
        }
        return Result(baseUrl = s, chatUrl = "$s/chat/completions", warnings = warnings.distinct())
    }

    fun cleanKey(raw: String): String =
        raw.trim().trim('"', '\'', '`')
            .removePrefix("Bearer ").removePrefix("bearer ")
            .replace(Regex("\\s+"), "")

    fun cleanModel(raw: String): String = raw.trim().trim('"', '\'', '`')

    /** 日志/界面展示用：只保留首尾少量字符。 */
    fun maskKey(key: String): String = when {
        key.length <= 8 -> "*".repeat(key.length)
        else -> key.take(4) + "…" + key.takeLast(4)
    }

    private fun isLocalHost(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" || host.startsWith("10.") ||
            host.startsWith("192.168.") || host == "10.0.2.2"
}
