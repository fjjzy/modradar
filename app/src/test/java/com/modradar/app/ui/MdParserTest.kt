package com.modradar.app.ui

import com.modradar.app.ui.components.MdBlock
import com.modradar.app.ui.components.MdParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MdParserTest {

    @Test
    fun `标题 列表 代码块 表格 引用都能识别`() {
        val md = """
            ### 结论
            存在**远端执行**能力。

            - 第一条
              续行
            1. 有序
            > 引用

            ```kotlin
            val a = 1
            ```

            | 规则 | 扣分 |
            |---|---|
            | C1 | -35 |
            ---
        """.trimIndent()
        val b = MdParser.parse(md)
        assertEquals(MdBlock.Heading(3, "结论"), b[0])
        assertEquals(MdBlock.Paragraph("存在**远端执行**能力。"), b[1])
        assertEquals("第一条 续行", (b[2] as MdBlock.ListItem).text)
        assertTrue((b[3] as MdBlock.ListItem).ordered)
        assertEquals(MdBlock.Quote("引用"), b[4])
        assertEquals(MdBlock.Code("kotlin", "val a = 1"), b[5])
        val t = b[6] as MdBlock.Table
        assertEquals(listOf("规则", "扣分"), t.header)
        assertEquals(listOf(listOf("C1", "-35")), t.rows)
        assertEquals(MdBlock.Rule, b[7])
    }

    @Test
    fun `纯文本保持为段落`() {
        assertEquals(listOf(MdBlock.Paragraph("hello\nworld")), MdParser.parse("hello\nworld"))
    }
}
