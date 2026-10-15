package com.modradar.app.mcp

import com.modradar.app.ai.tools.ToolResult
import com.modradar.app.ai.tools.ToolSpec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpProtocolTest {

    private var lastCall: Triple<String, String, JsonObject>? = null

    private val spec = ToolSpec(
        name = "read_manifest",
        description = "读清单",
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", JsonObject(emptyMap()))
        },
    ) { ToolResult.Ok("unused") }

    private val protocol = McpProtocol(
        tools = { listOf(spec) },
        invoke = { taskId, name, args ->
            lastCall = Triple(taskId, name, args)
            if (taskId == "missing") ToolResult.NotReady(listOf("S1"), listOf("S2"), "未就绪") else ToolResult.Ok("包名: a.b")
        },
        listTasks = { "t1\tdemo\tAPK\tDONE\t88" },
        serverVersion = "test",
    )

    private fun obj(s: String?) = Json.parseToJsonElement(s!!).jsonObject

    @Test fun initializeAdvertisesTools() = runTest {
        val r = obj(protocol.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}"""))
        val res = r["result"]!!.jsonObject
        assertEquals("2025-03-26", res["protocolVersion"]!!.jsonPrimitive.content)
        assertTrue(res["capabilities"]!!.jsonObject.containsKey("tools"))
        assertEquals("modradar", res["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun notificationsHaveNoResponse() = runTest {
        assertNull(protocol.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }

    @Test fun toolsListInjectsTaskId() = runTest {
        val tools = obj(protocol.handle("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""))["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(listOf("list_tasks", "read_manifest"), tools.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        val schema = tools[1].jsonObject["inputSchema"]!!.jsonObject
        assertTrue(schema["properties"]!!.jsonObject.containsKey("task_id"))
        assertEquals("task_id", schema["required"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test fun toolsCallStripsTaskIdAndReturnsText() = runTest {
        val r = obj(protocol.handle("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"read_manifest","arguments":{"task_id":"t1","x":"y"}}}"""))
        val res = r["result"]!!.jsonObject
        assertFalse(res["isError"]!!.jsonPrimitive.boolean)
        assertEquals("包名: a.b", res["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("t1", lastCall!!.first)
        assertFalse(lastCall!!.third.containsKey("task_id"))
        assertTrue(lastCall!!.third.containsKey("x"))
    }

    @Test fun missingTaskIdIsToolError() = runTest {
        val res = obj(protocol.handle("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"read_manifest","arguments":{}}}"""))["result"]!!.jsonObject
        assertTrue(res["isError"]!!.jsonPrimitive.boolean)
    }

    @Test fun notReadyIsToolError() = runTest {
        val res = obj(protocol.handle("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"read_manifest","arguments":{"task_id":"missing"}}}"""))["result"]!!.jsonObject
        assertTrue(res["isError"]!!.jsonPrimitive.boolean)
        assertTrue(res["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.contains("data_not_ready"))
    }

    @Test fun listTasksNeedsNoTaskId() = runTest {
        val res = obj(protocol.handle("""{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"list_tasks"}}"""))["result"]!!.jsonObject
        assertTrue(res["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.startsWith("t1"))
    }

    @Test fun unknownMethodAndParseError() = runTest {
        assertEquals(-32601, obj(protocol.handle("""{"jsonrpc":"2.0","id":7,"method":"foo"}"""))["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals(-32700, obj(protocol.handle("{oops"))["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    @Test fun httpRequestParsing() {
        val body = """{"jsonrpc":"2.0","id":9,"method":"ping"}"""
        val raw = "POST /mcp HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer t\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body"
        val req = McpServer.HttpRequest.read(raw.byteInputStream())!!
        assertEquals("POST", req.method)
        assertEquals("Bearer t", req.headers["authorization"])
        assertEquals(body, req.body)
        assertTrue(McpServer.handleForTest(protocol, raw)!!.contains("\"result\""))
    }

    @Test fun clientReadOnlyFilterAndSse() {
        assertTrue(McpClient.isReadOnly("get_class_source"))
        assertTrue(McpClient.isReadOnly("search_strings"))
        assertFalse(McpClient.isReadOnly("rename_class"))
        assertFalse(McpClient.isReadOnly("delete_file"))
        val c = McpClient("http://127.0.0.1:1/mcp", "")
        val o = c.parseBody("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n\n")
        assertEquals(1, o["id"]!!.jsonPrimitive.int)
    }
}
