package com.modradar.app.ai

import com.modradar.app.data.db.ChatMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatReplayTest {

    private var id = 0L
    private fun msg(role: String, content: String, meta: String? = null) =
        ChatMessageEntity(id = ++id, sessionId = "s", role = role, content = content, toolCallsJson = meta, createdAt = id)

    @Test fun keepsToolCallPairing() {
        val out = ChatEngine.replay(
            listOf(
                msg("user", "q"),
                msg("assistant", "", """[{"id":"c1","name":"list_stages","arguments":"{}"}]"""),
                msg("tool", "S1 DONE", """{"id":"c1","name":"list_stages"}"""),
                msg("assistant", "结论"),
            )
        )
        assertEquals(listOf("user", "assistant", "tool", "assistant"), out.map { it.role })
        assertEquals("c1", out[1].toolCalls!!.single().id)
        assertEquals("c1", out[2].toolCallId)
    }

    @Test fun dropsLegacyToolMessagesWithoutIds() {
        // 旧版本只存了工具名，无法配对 → 整组丢弃，避免接口 400
        val out = ChatEngine.replay(
            listOf(
                msg("user", "q"),
                msg("assistant", "", """[{"id":"c1","name":"x","arguments":"{}"}]"""),
                msg("tool", "r", "x"),
                msg("assistant", "a"),
                msg("user", "q2"),
            )
        )
        assertEquals(listOf("user", "assistant", "user"), out.map { it.role })
        assertNull(out[1].toolCalls)
    }

    @Test fun dropsDanglingToolCallsButKeepsText() {
        val out = ChatEngine.replay(
            listOf(
                msg("user", "q"),
                msg("assistant", "我先查一下", """[{"id":"c9","name":"x","arguments":"{}"}]"""),
            )
        )
        assertEquals(2, out.size)
        assertNull(out[1].toolCalls)
        assertEquals("我先查一下", out[1].content)
    }
}
