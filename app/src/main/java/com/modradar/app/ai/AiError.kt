package com.modradar.app.ai

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** AI 调用错误分类：每类带中文说明与修复建议，界面直接展示。 */
class AiError(
    val kind: Kind,
    val httpCode: Int? = null,
    val detail: String = "",
    cause: Throwable? = null,
) : Exception("${kind.title}${httpCode?.let { "（HTTP $it）" } ?: ""}：${kind.hint}", cause) {

    enum class Kind(val title: String, val hint: String, val retryable: Boolean = false) {
        NOT_CONFIGURED("尚未配置", "请填写 API 地址、Key 与模型后保存"),
        AUTH("Key 无效或无权限", "检查 API Key 是否复制完整、是否过期，或该 Key 是否有此模型的权限"),
        NOT_FOUND("地址或模型不存在", "确认地址以 /v1 之类的版本号结尾，模型名可点「拉取模型列表」选择"),
        BAD_REQUEST("请求被拒绝", "模型可能不支持工具调用或参数；换一个模型或把思考等级设为自动"),
        QUOTA("额度不足或被限流", "账户余额 / 配额用尽，或请求过快；充值或稍后再试", retryable = true),
        SERVER("服务端错误", "接口暂时不可用，稍后重试", retryable = true),
        TIMEOUT("请求超时", "网络较慢或模型响应时间过长，稍后重试或换个模型", retryable = true),
        DNS("域名解析失败", "检查地址拼写与网络连接（是否需要代理）"),
        CONNECT("无法连接", "检查地址、端口与网络；本机服务请确认已启动"),
        SSL("TLS 握手失败", "证书异常或被中间人拦截；检查系统时间或换用正规 https 地址"),
        BAD_RESPONSE("返回格式异常", "接口返回的不是 OpenAI 兼容 JSON；确认协议与地址是否对应"),
        UNKNOWN("未知错误", "查看详情信息"),
    }

    val userMessage: String
        get() = buildString {
            append(kind.title)
            httpCode?.let { append("（HTTP $it）") }
            append("\n").append(kind.hint)
            if (detail.isNotBlank()) append("\n详情：").append(detail.take(240))
        }

    companion object {
        fun fromHttp(code: Int, body: String): AiError {
            val b = body.lowercase()
            val kind = when {
                code == 401 || code == 403 -> Kind.AUTH
                code == 402 -> Kind.QUOTA
                code == 429 -> Kind.QUOTA
                code == 404 -> Kind.NOT_FOUND
                code in 500..599 -> Kind.SERVER
                code == 400 && ("model" in b && ("not exist" in b || "not found" in b || "does not exist" in b)) -> Kind.NOT_FOUND
                code == 400 && ("insufficient" in b || "balance" in b || "quota" in b) -> Kind.QUOTA
                code == 400 && ("api key" in b || "apikey" in b || "authentication" in b) -> Kind.AUTH
                code in 400..499 -> Kind.BAD_REQUEST
                else -> Kind.UNKNOWN
            }
            return AiError(kind, code, extractMessage(body))
        }

        fun fromThrowable(t: Throwable): AiError = when (t) {
            is AiError -> t
            is UnknownHostException -> AiError(Kind.DNS, detail = t.message.orEmpty(), cause = t)
            is SocketTimeoutException -> AiError(Kind.TIMEOUT, detail = t.message.orEmpty(), cause = t)
            is InterruptedIOException -> AiError(Kind.TIMEOUT, detail = t.message.orEmpty(), cause = t)
            is ConnectException -> AiError(Kind.CONNECT, detail = t.message.orEmpty() + proxyHint(), cause = t)
            is SSLException -> AiError(Kind.SSL, detail = t.message.orEmpty(), cause = t)
            is java.net.UnknownServiceException -> AiError(Kind.CONNECT, detail = "系统禁止明文 HTTP：${t.message.orEmpty()}", cause = t)
            is kotlinx.serialization.SerializationException,
            is IllegalArgumentException -> AiError(Kind.BAD_RESPONSE, detail = t.message.orEmpty(), cause = t)
            else -> AiError(Kind.UNKNOWN, detail = t.message ?: t::class.java.simpleName, cause = t)
        }

        /** 系统设置了 HTTP 代理时，连接失败多半是代理不可达。 */
        private fun proxyHint(): String {
            val host = System.getProperty("http.proxyHost").orEmpty()
            if (host.isBlank()) return ""
            return "（当前系统代理 $host:${System.getProperty("http.proxyPort").orEmpty()}，请确认代理可用或关闭代理）"
        }

        /** 从 `{"error":{"message":"..."}}` 等常见格式里取出可读信息。 */
        fun extractMessage(body: String): String {
            val m = Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)
            return (m?.groupValues?.get(1) ?: body).replace("\\n", " ").take(300)
        }
    }
}
