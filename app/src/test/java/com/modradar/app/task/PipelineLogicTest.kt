package com.modradar.app.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 续跑语义：已完成的阶段必须被跳过，不重算。 */
class PipelineLogicTest {

    @Test
    fun `空进度从 S1 开始`() {
        assertEquals(Stage.LOAD, Pipeline.nextStage(emptySet()))
    }

    @Test
    fun `已完成 S1 S2 时从 S3 续跑`() {
        val done = setOf(Stage.LOAD, Stage.EXTRACT)
        assertEquals(Stage.CHAIN, Pipeline.nextStage(done))
    }

    @Test
    fun `全部完成时无续跑阶段`() {
        assertNull(Pipeline.nextStage(Stage.order.toSet()))
    }

    @Test
    fun `待跑阶段列表按顺序且不含已完成`() {
        val done = setOf(Stage.LOAD, Stage.EXTRACT, Stage.CHAIN)
        val pending = Pipeline.pendingStages(done)

        assertEquals(listOf(Stage.SCORE, Stage.AI, Stage.REPORT), pending)
    }

    @Test
    fun `阶段顺序固定为 S1 到 S6`() {
        assertEquals(
            listOf("S1", "S2", "S3", "S4", "S5", "S6"),
            Stage.order.map { it.id },
        )
    }
}
