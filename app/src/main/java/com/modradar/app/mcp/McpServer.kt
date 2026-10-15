package com.modradar.app.mcp

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom

/** MCP 相关配置（仅存本机）。 */
data class McpSettings(
    val serverEnabled: Boolean = false,
    val port: Int = 8787,
    val token: String = "",
    val clientUrl: String = "",
    val clientToken: String = "",
) {
    companion object {
        private const val PREF = "mcp_settings"

        fun load(ctx: Context): McpSettings {
            val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            return McpSettings(
                serverEnabled = sp.getBoolean("serverEnabled", false),
                port = sp.getInt("port", 8787),
                token = sp.getString("token", "") ?: "",
                clientUrl = sp.getString("clientUrl", "") ?: "",
                clientToken = sp.getString("clientToken", "") ?: "",
            )
        }

        fun save(ctx: Context, s: McpSettings) {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit {
                putBoolean("serverEnabled", s.serverEnabled)
                putInt("port", s.port)
                putString("token", s.token)
                putString("clientUrl", s.clientUrl)
                putString("clientToken", s.clientToken)
            }
        }

        fun newToken(): String {
            val b = ByteArray(18)
            SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * 极简 MCP Server（Streamable HTTP 的 POST 子集）。
 *
 * - **只绑定 127.0.0.1**：PC 端通过 `adb forward tcp:8787 tcp:8787` 访问，局域网不可达
 * - 每个请求必须带 `Authorization: Bearer <token>`，否则 401
 * - 工具全部只读，复用应用内 ToolRegistry
 */
class McpServer(
    private val protocol: McpProtocol,
    private val port: Int,
    private val token: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null

    val running: Boolean get() = server?.isClosed == false

    fun start() {
        if (running) return
        require(token.isNotBlank()) { "token 不能为空" }
        val ss = ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))
        server = ss
        acceptJob = scope.launch {
            while (isActive && !ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                launch { runCatching { serve(s) }; runCatching { s.close() } }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        acceptJob?.cancel()
        server = null
    }

    private suspend fun serve(socket: Socket) {
        socket.soTimeout = 15_000
        val input = BufferedInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        val req = HttpRequest.read(input) ?: return respond(out, 400, "text/plain", "bad request")

        if (!authorized(req.headers["authorization"])) {
            return respond(out, 401, "application/json", """{"error":"unauthorized"}""")
        }
        when (req.method) {
            "POST" -> {
                val reply = protocol.handle(req.body)
                if (reply == null) respond(out, 202, "application/json", "")
                else respond(out, 200, "application/json", reply)
            }
            "GET" -> respond(out, 405, "text/plain", "use POST (JSON-RPC 2.0)")
            else -> respond(out, 405, "text/plain", "method not allowed")
        }
    }

    private fun authorized(header: String?): Boolean {
        val got = header?.removePrefix("Bearer ")?.trim() ?: return false
        return MessageDigest.isEqual(got.toByteArray(), token.toByteArray())
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            405 -> "Method Not Allowed"; 413 -> "Payload Too Large"; else -> "Error"
        }
        val head = "HTTP/1.1 $code $status\r\nContent-Type: $type; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    /** 最小 HTTP/1.1 请求解析：请求行 + 头 + Content-Length 正文（上限 1MB）。 */
    data class HttpRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String) {
        companion object {
            private const val MAX_BODY = 1 shl 20

            fun read(input: InputStream): HttpRequest? {
                val line = readLine(input) ?: return null
                val parts = line.split(' ')
                if (parts.size < 2) return null
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val h = readLine(input) ?: return null
                    if (h.isEmpty()) break
                    val i = h.indexOf(':')
                    if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
                }
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                if (len < 0 || len > MAX_BODY) return null
                val buf = ByteArray(len)
                var read = 0
                while (read < len) {
                    val n = input.read(buf, read, len - read)
                    if (n < 0) return null
                    read += n
                }
                return HttpRequest(parts[0].uppercase(), parts[1], headers, String(buf, Charsets.UTF_8))
            }

            private fun readLine(input: InputStream): String? {
                val bos = ByteArrayOutputStream()
                while (true) {
                    val c = input.read()
                    if (c < 0) return if (bos.size() == 0) null else bos.toString("UTF-8")
                    if (c == '\n'.code) break
                    if (c != '\r'.code) bos.write(c)
                    if (bos.size() > 8192) return null
                }
                return bos.toString("UTF-8")
            }
        }
    }

    companion object {
        /** 供单测：同步处理一条原始 HTTP 请求。 */
        internal fun handleForTest(protocol: McpProtocol, raw: String): String? = runBlocking {
            val req = HttpRequest.read(raw.byteInputStream()) ?: return@runBlocking null
            protocol.handle(req.body)
        }
    }
}
