package com.modradar.app.ai

import com.modradar.app.ai.prompts.Prompts
import com.modradar.app.task.Stage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 部分数据模式：必须约束模型不把「未扫描」说成「未发现」。 */
class PromptsTest {

    @Test
    fun `P0 包含不臆造与状态区分铁律`() {
        val p0 = Prompts.P0_SYSTEM

        assertTrue(p0.contains("不要臆造类名"))
        assertTrue(p0.contains("未扫描"))
        assertTrue(p0.contains("data_not_ready"))
    }

    @Test
    fun `P8 列出已完成与未完成阶段`() {
        val text = Prompts.p8PartialData(
            completed = listOf(Stage.LOAD, Stage.EXTRACT),
            pending = listOf(Stage.CHAIN, Stage.SCORE, Stage.AI, Stage.REPORT),
            pausedAt = Stage.CHAIN,
        )

        assertTrue(text.contains("S1 装载"))
        assertTrue(text.contains("S2 结构提取"))
        assertTrue(text.contains("S3 能力链分析"))
        assertTrue(text.contains("分析当前停在 S3 能力链分析"))
    }

    @Test
    fun `P8 明确禁止整体安全结论`() {
        val text = Prompts.p8PartialData(emptyList(), Stage.order, Stage.LOAD)

        assertTrue(text.contains("不得输出「该应用安全」"))
        assertTrue(text.contains("无法判断"))
    }

    @Test
    fun `P8 不含误导性措辞`() {
        val text = Prompts.p8PartialData(listOf(Stage.LOAD), Stage.order.drop(1), Stage.EXTRACT)

        assertFalse(text.contains("已确认安全"))
    }
}
