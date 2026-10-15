package com.modradar.app.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHtmlTest {

    @Test
    fun `拆出文末 html 块`() {
        val s = ChatHtml.extract("### 结论\n正文\n\n```html\n<h3>摘要</h3><div class=\"card\">x</div>\n```\n")!!
        assertEquals("### 结论\n正文", s.markdown)
        assertEquals("<h3>摘要</h3><div class=\"card\">x</div>", s.html)
    }

    @Test
    fun `流式中未闭合的块隐藏并标记生成中`() {
        val s = ChatHtml.extract("正文\n```html\n<h3>摘")!!
        assertEquals("正文", s.markdown)
        assertNull(s.html)
        assertTrue(s.pending)
    }

    @Test
    fun `没有 html 块返回 null`() {
        assertNull(ChatHtml.extract("```kotlin\nval a = 1\n```"))
    }
}
