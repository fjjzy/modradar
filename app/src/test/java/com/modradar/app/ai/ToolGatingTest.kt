package com.modradar.app.ai

import com.modradar.app.ai.tools.ToolResult
import com.modradar.app.task.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具门控：阶段未完成时必须返回 data_not_ready，
 * 而不是空结果（否则模型会把「未扫描」说成「未发现」）。
 */
class ToolGatingTest {

    private fun missing(required: Set<Stage>, done: Set<Stage>): Set<Stage> = required - done

    @Test
    fun `S2 未完成时 read_manifest 被门控`() {
        val required = setOf(Stage.EXTRACT)
        val done = setOf(Stage.LOAD)

        assertEquals(setOf(Stage.EXTRACT), missing(required, done))
    }

    @Test
    fun `S2 完成后 read_manifest 放行`() {
        val required = setOf(Stage.EXTRACT)
        val done = setOf(Stage.LOAD, Stage.EXTRACT)

        assertTrue(missing(required, done).isEmpty())
    }

    @Test
    fun `暂停在 S3 时能力链工具仍被门控`() {
        val required = setOf(Stage.CHAIN)
        val done = setOf(Stage.LOAD, Stage.EXTRACT)

        assertEquals(setOf(Stage.CHAIN), missing(required, done))
    }

    @Test
    fun `无阶段要求的工具始终可用`() {
        assertTrue(missing(emptySet(), emptySet()).isEmpty())
    }

    @Test
    fun `NotReady 序列化包含 error completed required`() {
        val json = ToolResult.NotReady(
            completed = listOf("S1", "S2"),
            required = listOf("S3"),
            hint = "需要 S3 能力链分析完成",
        ).toJson()

        assertTrue(json.contains("\"error\":\"data_not_ready\""))
        assertTrue(json.contains("\"completed\":\"S1,S2\""))
        assertTrue(json.contains("\"required\":\"S3\""))
    }
}
