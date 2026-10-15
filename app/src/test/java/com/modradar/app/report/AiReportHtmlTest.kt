package com.modradar.app.report

import com.modradar.app.task.stages.AiFinding
import com.modradar.app.task.stages.AiVerdict
import com.modradar.app.task.stages.AiVerdictParser
import com.modradar.app.task.stages.ReportArtifact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiReportHtmlTest {

    @Test
    fun `模型文本全部转义 不会注入脚本`() {
        val v = AiVerdict(verdict = "可疑", headline = "<script>alert(1)</script>", findings = listOf(AiFinding(title = "<img onerror=x>")))
        val html = AiReportHtml.render(ReportArtifact(targetName = "a.apk", score = 70), v)
        assertFalse(html.contains("<script>alert"))
        assertFalse(html.contains("<img"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("安全分"))
        assertTrue(html.contains("70"))
    }

    @Test
    fun `解析容忍围栏 前后废话与尾逗号 且忽略已废除的 score 字段`() {
        val raw = "好的，结果如下：\n```json\n{\"verdict\":\"低风险\",\"score\":120,\"headline\":\"ok\",\"findings\":[{\"title\":\"x\",}],}\n```"
        val v = AiVerdictParser.parse(raw)!!
        assertEquals("低风险", v.verdict)
        assertEquals("x", v.findings.single().title)
    }

    @Test
    fun `自然语言回复解析为空`() {
        assertNull(AiVerdictParser.parse("这个应用看起来没问题"))
        assertNull(AiVerdictParser.parse("{}"))
    }

    @Test
    fun `页面锁定屏幕宽度并强制断行`() {
        val html = AiReportHtml.render(ReportArtifact(targetName = "a.apk"), AiVerdict(headline = "h", overview = "第一句很长很长。第二句也很长很长。"))
        assertTrue(html.contains("overflow-x:hidden"))
        assertTrue(html.contains("overflow-wrap:anywhere"))
        assertTrue(html.contains("class=\"check dot\""))
    }

    @Test
    fun `聊天 HTML 片段清洗脚本 样式与外链`() {
        val raw = """<h3 onclick="x()">结论</h3><script>alert(1)</script><style>body{}</style>
            <div class="card hi" style="width:2000px"><a href="javascript:alert(1)">x</a><img src="http://t/x.png"></div>"""
        val s = HtmlKit.sanitize(raw)
        assertFalse(s.contains("script"))
        assertFalse(s.contains("<style"))
        assertFalse(s.contains("onclick"))
        assertFalse(s.contains("width:2000px"))
        assertFalse(s.contains("<img"))
        assertFalse(s.contains("javascript"))
        assertTrue(s.contains("class=\"card hi\""))
        assertEquals("结论", HtmlKit.firstHeading(s))
    }
}
