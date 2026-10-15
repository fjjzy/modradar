package com.modradar.app.report

/** 从 AI 回复里拆出文末的 ```html 可视化片段。 */
object ChatHtml {

    data class Split(
        /** 去掉 HTML 块后的 Markdown 正文。 */
        val markdown: String,
        /** HTML 片段；流式输出中尚未闭合时为 null 且 [pending] = true。 */
        val html: String?,
        val pending: Boolean = false,
    )

    private val CLOSED = Regex("```html[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n?```", RegexOption.IGNORE_CASE)
    private val OPEN = Regex("```html[ \\t]*(\\r?\\n|$)", RegexOption.IGNORE_CASE)

    /** 没有 HTML 块时返回 null。只取最后一个块。 */
    fun extract(text: String): Split? {
        val m = CLOSED.findAll(text).lastOrNull()
        if (m != null) {
            val md = (text.substring(0, m.range.first) + text.substring(m.range.last + 1)).trimEnd()
            return Split(md, m.groupValues[1].trim().ifEmpty { return null })
        }
        val open = OPEN.findAll(text).lastOrNull() ?: return null
        return Split(text.substring(0, open.range.first).trimEnd(), null, pending = true)
    }
}
