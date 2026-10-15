package com.modradar.app.ai

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class LmClientTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun client(retries: Int = 2) = LmClient(
        AiSettings(baseUrl = server.url("/v1").toString(), apiKey = "Bearer sk-test ", model = "m1"),
        maxRetries = retries,
        baseDelayMs = 1,
    )

    private val okBody = """{"model":"m1-real","choices":[{"message":{"role":"assistant","content":"pong"}}]}"""

    private suspend fun expectError(kind: AiError.Kind, block: suspend () -> Unit) {
        try { block(); fail("应抛出 $kind") } catch (e: AiError) { assertEquals(kind, e.kind) }
    }

    @Test fun testConnectionReturnsModelAndCleansKey() = runTest {
        server.enqueue(MockResponse().setBody(okBody))
        val r = client().testConnection()
        assertEquals("m1-real", r.model)
        assertEquals("pong", r.reply)
        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
    }

    @Test fun unauthorizedIsAuthAndNotRetried() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Invalid API key"}}"""))
        expectError(AiError.Kind.AUTH) { client().testConnection() }
        assertEquals(1, server.requestCount)
    }

    @Test fun modelNotExistOn400IsNotFound() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Model Not Exist"}}"""))
        expectError(AiError.Kind.NOT_FOUND) { client().testConnection() }
    }

    @Test fun rateLimitRetriesThenSucceeds() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody(okBody))
        assertEquals("pong", client().testConnection().reply)
        assertEquals(3, server.requestCount)
    }

    @Test fun retriesAreBounded() = runTest {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500)) }
        expectError(AiError.Kind.SERVER) { client(retries = 2).testConnection() }
        assertEquals(3, server.requestCount)
    }

    @Test fun htmlBodyIsBadResponse() = runTest {
        server.enqueue(MockResponse().setBody("<html>gateway</html>"))
        expectError(AiError.Kind.BAD_RESPONSE) { client().testConnection() }
    }

    @Test fun parsesToolCallsWithMissingIds() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"choices":[{"message":{"content":null,"tool_calls":[{"type":"function","function":{"name":"read_manifest","arguments":""}}]}}]}"""
        ))
        val r = client().chat(listOf(ChatMsg("user", "hi")), "[]")
        assertEquals(1, r.toolCalls.size)
        assertEquals("call_0", r.toolCalls[0].id)
        assertEquals("{}", r.toolCalls[0].arguments)
    }

    @Test fun listModelsParsesData() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"b"},{"id":"a"},{"id":"models/c"}]}"""))
        assertEquals(listOf("a", "b", "c"), client().listModels())
        assertEquals("/v1/models", server.takeRequest().path)
    }

    @Test fun notConfiguredFailsFast() = runTest {
        expectError(AiError.Kind.NOT_CONFIGURED) {
            LmClient(AiSettings(baseUrl = "https://x.com/v1", apiKey = "", model = "m")).testConnection()
        }
    }

    @Test fun assistantToolCallMessageEncodedWithoutContent() = runTest {
        server.enqueue(MockResponse().setBody(okBody))
        client().chat(
            listOf(
                ChatMsg("assistant", null, toolCalls = listOf(ToolCallReq("c1", "list_stages", "{}"))),
                ChatMsg("tool", "ok", toolCallId = "c1"),
            ),
            null,
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"tool_call_id\":\"c1\""))
        assertTrue(body.contains("\"tool_calls\""))
    }

    @Test fun streamAccumulatesContentReasoningAndToolCalls() = runTest {
        val sse = listOf(
            """{"model":"m1","choices":[{"delta":{"reasoning_content":"先想"}}]}""",
            """{"choices":[{"delta":{"reasoning_content":"一下"}}]}""",
            """{"choices":[{"delta":{"content":"你"}}]}""",
            """{"choices":[{"delta":{"content":"好"}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"get_","arguments":"{\"a\""}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"report","arguments":":1}"}}]}}]}""",
        ).joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse))
        val seen = mutableListOf<String>()
        val r = client().chatStream(emptyList(), null) { c, _ -> seen += c }
        assertEquals("你好", r.content)
        assertEquals("先想一下", r.reasoning)
        assertEquals("get_report", r.toolCalls.single().name)
        assertEquals("{\"a\":1}", r.toolCalls.single().arguments)
        assertEquals("你好", seen.last())
        assertTrue(server.takeRequest().body.readUtf8().contains("\"stream\":true"))
    }

    @Test fun streamFallsBackToJson() = runTest {
        server.enqueue(MockResponse().setBody(okBody))
        assertEquals("pong", client().chatStream(emptyList(), null) { _, _ -> }.content)
    }
}
